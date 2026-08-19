package com.narrator.jp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NarratorService : Service() {

    companion object {
        const val ACTION_START = "com.narrator.jp.START"
        const val ACTION_STOP = "com.narrator.jp.STOP"
        const val ACTION_FLAG_SLOT = "com.narrator.jp.FLAG_SLOT"
        const val ACTION_NOTIF_DISMISSED = "com.narrator.jp.NOTIF_DISMISSED"
        const val EXTRA_SLOT = "slot"

        const val BROADCAST_UPDATED = "com.narrator.jp.UPDATED"

        // 注意：NotificationChannel 一旦建立，重要性就無法用程式改。
        // 從 IMPORTANCE_LOW 改成 DEFAULT 必須換一個新的 id，否則舊安裝不會生效。
        private const val CHANNEL_ID = "narrator_service_v2"
        private const val CHANNEL_ID_OLD = "narrator_service"
        private const val NOTIF_ID = 4711
        private const val RECENT_SHOWN = 3
        private const val LOG_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        private const val PURGE_EVERY_MS = 60L * 60 * 1000

        /** 等待期間每秒重算一次截止時間，通勤模式切換才能立刻反映。 */
        private const val WAIT_TICK_MS = 1000L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, NarratorService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, NarratorService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private data class Recent(
        val logId: Long,
        val clipId: String,
        val playedAt: Long,
        val flagged: Boolean
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val playLock = Mutex()

    private lateinit var player: VoicePlayer
    private lateinit var picker: Picker

    private var loopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorManager: SensorManager? = null
    private var shakeDetector: ShakeDetector? = null
    private var lastPurgeAt = 0L

    private val recent = ArrayList<Recent>()
    private var gains: Map<String, Float> = emptyMap()

    override fun onCreate() {
        super.onCreate()
        player = VoicePlayer(this)
        picker = Picker(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必須在 5 秒內進入前景，任何分支都不能跳過。
        enterForeground()

        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_FLAG_SLOT -> {
                handleFlagSlot(intent.getIntExtra(EXTRA_SLOT, 0))
            }

            ACTION_NOTIF_DISMISSED -> {
                // Android 14 起系統允許使用者滑掉前景服務通知，App 無法真的禁止。
                // 唯一的辦法是被滑掉的當下立刻貼回去——上面的 enterForeground()
                // 已經重貼了，這裡只處理「服務其實已經該收工」的情況。
                if (loopJob?.isActive != true) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }

            else -> {
                startLoop()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 迴圈 ----------

    private fun startLoop() {
        if (loopJob?.isActive == true) {
            // 已在跑：這次多半是設定變更（例如搖晃開關），重新套用即可。
            applyShakeSetting()
            return
        }
        isRunning = true
        Prefs.setWasRunning(this, true)
        acquireWakeLock()
        applyShakeSetting()
        loopJob = scope.launch {
            refreshState()
            updateNotification()
            while (isActive) {
                // 等待期間每秒重算截止時間：通勤模式是「隨時勾選隨時生效」的，
                // 一次 delay 掉整段間隔的話，切換要等下一輪才有反應。
                val base = picker.nextIntervalMs()
                val startedAt = SystemClock.elapsedRealtime()
                while (isActive) {
                    val target = (base * Prefs.intervalScale(this@NarratorService)).toLong()
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    if (elapsed >= target) break
                    delay(minOf(WAIT_TICK_MS, target - elapsed))
                }
                if (!isActive) break
                playOne()
            }
        }
    }

    private suspend fun playOne() {
        refreshState()
        val clip = picker.pick() ?: return
        val dao = AppDb.get(this).dao()
        val now = System.currentTimeMillis()
        val logId = dao.insertLog(PlayLog(clipId = clip.id, playedAt = now))

        synchronized(recent) {
            recent.add(0, Recent(logId, clip.id, now, false))
            while (recent.size > RECENT_SHOWN) recent.removeAt(recent.size - 1)
        }
        updateNotification()

        playLock.withLock {
            player.play(clip, Prefs.audioMode(this), effectiveDb(clip.id), duck = true)
        }

        updateNotification()
        notifyUpdated()
        purgeIfDue()
    }

    /** 主音量（dB）疊加單條微調（dB）。診斷版一律 <= 0，只做衰減。 */
    private fun effectiveDb(clipId: String): Float =
        Prefs.masterDb(this) + (gains[clipId] ?: 0f)

    private suspend fun refreshState() {
        val dao = AppDb.get(this).dao()
        gains = dao.allGains().associate { g -> g.clipId to g.gainDb }
        picker.setExcluded(
            if (Prefs.excludeFlagged(this)) dao.excludedClipIds() else emptyList()
        )
    }

    private suspend fun purgeIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastPurgeAt < PURGE_EVERY_MS) return
        lastPurgeAt = now
        AppDb.get(this).dao().purgeLogsBefore(now - LOG_RETENTION_MS)
    }

    private fun shutdown() {
        loopJob?.cancel()
        loopJob = null
        isRunning = false
        Prefs.setWasRunning(this, false)
        try {
            player.stop()
        } catch (t: Throwable) {
            // 忽略
        }
        releaseWakeLock()
        stopShake()
        notifyUpdated()
    }

    // ---------- 標記 ----------

    private fun handleFlagSlot(slot: Int) {
        val entry = synchronized(recent) { recent.getOrNull(slot) } ?: return
        scope.launch {
            val ok = Flagger.flag(this@NarratorService, entry.logId)
            if (ok) {
                Buzz.tick(this@NarratorService)
                synchronized(recent) {
                    val idx = recent.indexOfFirst { r -> r.logId == entry.logId }
                    if (idx >= 0) recent[idx] = recent[idx].copy(flagged = true)
                }
                refreshState()
                updateNotification()
                notifyUpdated()
            }
        }
    }

    private fun notifyUpdated() {
        try {
            sendBroadcast(Intent(BROADCAST_UPDATED).setPackage(packageName))
        } catch (t: Throwable) {
            // 忽略
        }
    }

    // ---------- 搖晃 ----------

    private fun applyShakeSetting() {
        if (Prefs.shakeEnabled(this)) startShake() else stopShake()
    }

    private fun startShake() {
        if (shakeDetector != null) return
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val detector = ShakeDetector {
            scope.launch {
                if (Flagger.flagMostRecent(this@NarratorService)) {
                    Buzz.tick(this@NarratorService)
                    synchronized(recent) {
                        if (recent.isNotEmpty()) recent[0] = recent[0].copy(flagged = true)
                    }
                    refreshState()
                    updateNotification()
                    notifyUpdated()
                }
            }
        }
        detector.applySensitivity(Prefs.shakeSensitivity(this))
        sm.registerListener(detector, accel, SensorManager.SENSOR_DELAY_UI)
        sensorManager = sm
        shakeDetector = detector
    }

    private fun stopShake() {
        val d = shakeDetector ?: return
        try {
            sensorManager?.unregisterListener(d)
        } catch (t: Throwable) {
            // 忽略
        }
        shakeDetector = null
    }

    // ---------- 電源 ----------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "narrator:loop")
        wl.setReferenceCounted(false)
        try {
            wl.acquire()
        } catch (t: Throwable) {
            return
        }
        wakeLock = wl
    }

    private fun releaseWakeLock() {
        val wl = wakeLock ?: return
        wakeLock = null
        try {
            if (wl.isHeld) wl.release()
        } catch (t: Throwable) {
            // 忽略
        }
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.deleteNotificationChannel(CHANNEL_ID_OLD)
        } catch (t: Throwable) {
            // 舊版沒裝過，忽略
        }
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        // IMPORTANCE_LOW 會被系統歸類為「靜音通知」，在鎖定畫面上會被摺疊或直接隱藏。
        // 要在鎖定畫面看得到就必須是 DEFAULT 以上；靜音改由 channel 的 sound=null
        // 與關閉震動來達成，所以提高重要性並不會發出任何聲音。
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.enableLights(false)
        channel.setShowBadge(false)
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(channel)
    }

    private fun enterForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (t: Throwable) {
            // 忽略
        }
    }

    private fun buildNotification(): Notification {
        val snapshot = synchronized(recent) { ArrayList(recent) }

        val body = StringBuilder()
        if (snapshot.isEmpty()) {
            body.append("尚未播放。等待第一次插播。")
        } else {
            for (i in snapshot.indices) {
                val e = snapshot[i]
                val clip = VoiceIndex.byId(this, e.clipId)
                if (i > 0) body.append("\n")
                body.append(if (e.flagged) "✓ " else "")
                body.append("${i + 1}. ")
                body.append(clip?.character ?: "?")
                body.append("「")
                body.append(clip?.text ?: e.clipId)
                body.append("」  ")
                body.append(ago(e.playedAt))
            }
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 被滑掉時觸發，服務收到後立刻把通知貼回去。
        val revive = PendingIntent.getService(
            this, 200,
            Intent(this, NarratorService::class.java).setAction(ACTION_NOTIF_DISMISSED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val commute = if (Prefs.commuteMode(this)) "　通勤模式" else ""
        val head = if (snapshot.isEmpty()) "等待第一次插播$commute"
        else "最近 ${snapshot.size} 條，可直接標記$commute"

        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("旁白插播中")
            .setContentText(head)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.toString()))
            .setContentIntent(open)
            .setDeleteIntent(revive)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 預設前景服務通知會延後約 10 秒才顯示，這裡要求立即顯示。
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        for (i in snapshot.indices) {
            val label = if (snapshot[i].flagged) "✓ ${i + 1}" else "✕ ${i + 1}"
            b.addAction(R.drawable.ic_flag, label, slotIntent(i))
        }

        val n = b.build()
        n.flags = n.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
        return n
    }

    private fun slotIntent(slot: Int): PendingIntent {
        val i = Intent(this, NarratorService::class.java)
            .setAction(ACTION_FLAG_SLOT)
            .putExtra(EXTRA_SLOT, slot)
        return PendingIntent.getService(
            this, 100 + slot, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ago(t: Long): String {
        val s = ((System.currentTimeMillis() - t) / 1000L).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}秒前"
            s < 3600 -> "${s / 60}分前"
            else -> "${s / 3600}小時前"
        }
    }
}
