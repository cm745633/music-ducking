package com.narrator.jp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var tvStats: TextView
    private lateinit var swCommute: SwitchCompat
    private lateinit var tvGain: TextView
    private lateinit var tvGainNote: TextView
    private lateinit var sbGain: SeekBar
    private lateinit var tvW1: TextView
    private lateinit var tvW2: TextView
    private lateinit var tvW3: TextView
    private lateinit var sbW1: SeekBar
    private lateinit var sbW2: SeekBar
    private lateinit var sbW3: SeekBar

    /** 試播用的獨立播放器，不經過服務，服務沒開也能校準。 */
    private lateinit var preview: VoicePlayer

    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = VoicePlayer(this)

        btnToggle = findViewById(R.id.btn_toggle)
        tvStats = findViewById(R.id.tv_stats)
        swCommute = findViewById(R.id.sw_commute)
        tvGain = findViewById(R.id.tv_gain)
        tvGainNote = findViewById(R.id.tv_gain_note)
        sbGain = findViewById(R.id.sb_gain)
        tvW1 = findViewById(R.id.tv_w1)
        tvW2 = findViewById(R.id.tv_w2)
        tvW3 = findViewById(R.id.tv_w3)
        sbW1 = findViewById(R.id.sb_w1)
        sbW2 = findViewById(R.id.sb_w2)
        sbW3 = findViewById(R.id.sb_w3)

        requestNotificationPermissionIfNeeded()

        btnToggle.setOnClickListener {
            if (NarratorService.isRunning) {
                NarratorService.stop(this)
            } else {
                NarratorService.start(this)
            }
            btnToggle.postDelayed({ syncToggle() }, 300L)
        }

        // 通勤模式：勾了就立刻生效，服務等待中也會馬上縮短，不必重開。
        swCommute.isChecked = Prefs.commuteMode(this)
        renderCommute()
        swCommute.setOnCheckedChangeListener { _, checked ->
            Prefs.setCommuteMode(this, checked)
            renderCommute()
            renderWeights()
        }

        findViewById<Button>(R.id.btn_preview).setOnClickListener { doPreview() }

        findViewById<Button>(R.id.btn_timeline).setOnClickListener {
            startActivity(Intent(this, TimelineActivity::class.java))
        }
        findViewById<Button>(R.id.btn_review).setOnClickListener {
            startActivity(Intent(this, ReviewActivity::class.java))
        }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 音量以 dB 表示：-18 ～ +12，0 dB 就是音檔本身的響度。
        sbGain.max = Prefs.DB_MAX - Prefs.DB_MIN
        sbGain.progress = (Prefs.masterDb(this).toInt() - Prefs.DB_MIN)
            .coerceIn(0, sbGain.max)
        renderGain()
        sbGain.onChange { p ->
            Prefs.setMasterDb(this, p + Prefs.DB_MIN)
            renderGain()
            preview.applyGain(Prefs.masterDb(this))
        }

        sbW1.progress = Prefs.wNormal(this).coerceIn(0, 100)
        sbW2.progress = Prefs.wBurst(this).coerceIn(0, 100)
        sbW3.progress = Prefs.wLong(this).coerceIn(0, 100)
        renderWeights()
        val saveWeights = { _: Int ->
            Prefs.setWeights(this, sbW1.progress, sbW2.progress, sbW3.progress)
            renderWeights()
        }
        sbW1.onChange(saveWeights)
        sbW2.onChange(saveWeights)
        sbW3.onChange(saveWeights)
    }

    override fun onStart() {
        super.onStart()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                syncToggle()
                refreshStats()
                renderGain()
                delay(3000L)
            }
        }
    }

    override fun onStop() {
        refreshJob?.cancel()
        refreshJob = null
        super.onStop()
    }

    override fun onDestroy() {
        try {
            preview.stop()
        } catch (t: Throwable) {
            // 忽略
        }
        super.onDestroy()
    }

    private fun doPreview() {
        val clips = VoiceIndex.all(this)
        if (clips.isEmpty()) {
            Toast.makeText(this, "語音索引是空的", Toast.LENGTH_SHORT).show()
            return
        }
        val clip = clips[(Math.random() * clips.size).toInt().coerceIn(0, clips.size - 1)]
        lifecycleScope.launch {
            preview.play(clip, Prefs.masterDb(this@MainActivity), duck = true)
            renderGain()
        }
    }

    private fun syncToggle() {
        btnToggle.text = if (NarratorService.isRunning) "停止" else "開始"
    }

    private fun refreshStats() {
        lifecycleScope.launch {
            val dao = AppDb.get(this@MainActivity).dao()
            val since = startOfToday()
            val plays = dao.countLogsSince(since)
            val flags = dao.countFlagsSince(since)
            tvStats.text = "今日播放 $plays　今日標記 $flags"
        }
    }

    private fun renderCommute() {
        swCommute.text = if (Prefs.commuteMode(this)) {
            "通勤模式（間隔減半，已開啟）"
        } else {
            "通勤模式（間隔減半）"
        }
    }

    private fun renderGain() {
        val db = Prefs.masterDb(this).toInt()
        val times = 10.0.pow(db / 20.0)
        tvGain.text = String.format(Locale.US, "旁白音量 %+d dB（約 %.2f 倍）", db, times)
        tvGainNote.text = when {
            db <= 0 -> "0 dB 以下是單純衰減。放著音樂按試播，邊聽邊調。"
            VoicePlayer.boostSupported == false ->
                "這台裝置不支援系統增幅，超過 0 dB 沒有效果。請改用手機本身的媒體音量。"
            else -> "0 dB 以上由系統的增幅效果處理，不會削波。放著音樂按試播，邊聽邊調。"
        }
    }

    private fun renderWeights() {
        val n = sbW1.progress
        val b = sbW2.progress
        val l = sbW3.progress
        val total = (n + b + l).coerceAtLeast(1)
        val s = Prefs.intervalScale(this)
        tvW1.text = "常態 ${rng(120, 240, s)}　$n（${pct(n, total)}）"
        tvW2.text = "連發 ${rng(20, 40, s)}　$b（${pct(b, total)}）"
        tvW3.text = "長靜默 ${rng(300, 420, s)}　$l（${pct(l, total)}）"
    }

    private fun rng(lo: Int, hi: Int, scale: Double): String =
        "${(lo * scale).toInt()}～${(hi * scale).toInt()} 秒"

    private fun pct(v: Int, total: Int): String =
        String.format(Locale.US, "%.0f%%", v * 100.0 / total)

    private fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
            )
        }
    }

    private fun SeekBar.onChange(block: (Int) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                block(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
}
