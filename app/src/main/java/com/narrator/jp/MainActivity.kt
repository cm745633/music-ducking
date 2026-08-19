package com.narrator.jp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
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

class MainActivity : AppCompatActivity() {

    private val modeIds = intArrayOf(
        R.id.rb_mode_a, R.id.rb_mode_b, R.id.rb_mode_c, R.id.rb_mode_d
    )

    private lateinit var rgMode: RadioGroup
    private lateinit var tvModeNote: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnCompare: Button
    private lateinit var tvStats: TextView
    private lateinit var swCommute: SwitchCompat
    private lateinit var tvGain: TextView
    private lateinit var sbGain: SeekBar
    private lateinit var tvW1: TextView
    private lateinit var tvW2: TextView
    private lateinit var tvW3: TextView
    private lateinit var sbW1: SeekBar
    private lateinit var sbW2: SeekBar
    private lateinit var sbW3: SeekBar

    private lateinit var preview: VoicePlayer

    private var refreshJob: Job? = null
    private var compareJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = VoicePlayer(this)

        rgMode = findViewById(R.id.rg_mode)
        tvModeNote = findViewById(R.id.tv_mode_note)
        btnToggle = findViewById(R.id.btn_toggle)
        btnCompare = findViewById(R.id.btn_compare)
        tvStats = findViewById(R.id.tv_stats)
        swCommute = findViewById(R.id.sw_commute)
        tvGain = findViewById(R.id.tv_gain)
        sbGain = findViewById(R.id.sb_gain)
        tvW1 = findViewById(R.id.tv_w1)
        tvW2 = findViewById(R.id.tv_w2)
        tvW3 = findViewById(R.id.tv_w3)
        sbW1 = findViewById(R.id.sb_w1)
        sbW2 = findViewById(R.id.sb_w2)
        sbW3 = findViewById(R.id.sb_w3)

        requestNotificationPermissionIfNeeded()

        for (i in modeIds.indices) {
            findViewById<RadioButton>(modeIds[i]).text = AudioMode.label(i)
        }
        rgMode.check(modeIds[Prefs.audioMode(this)])
        renderMode()
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val i = modeIds.indexOf(checkedId)
            if (i >= 0) {
                Prefs.setAudioMode(this, i)
                renderMode()
            }
        }

        btnCompare.setOnClickListener { doCompare() }
        findViewById<Button>(R.id.btn_preview).setOnClickListener { doPreview() }

        btnToggle.setOnClickListener {
            if (NarratorService.isRunning) {
                NarratorService.stop(this)
            } else {
                NarratorService.start(this)
            }
            btnToggle.postDelayed({ syncToggle() }, 300L)
        }

        swCommute.isChecked = Prefs.commuteMode(this)
        swCommute.setOnCheckedChangeListener { _, checked ->
            Prefs.setCommuteMode(this, checked)
            renderWeights()
        }

        findViewById<Button>(R.id.btn_timeline).setOnClickListener {
            startActivity(Intent(this, TimelineActivity::class.java))
        }
        findViewById<Button>(R.id.btn_review).setOnClickListener {
            startActivity(Intent(this, ReviewActivity::class.java))
        }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        sbGain.max = Prefs.DB_MAX - Prefs.DB_MIN
        sbGain.progress = (Prefs.masterDb(this).toInt() - Prefs.DB_MIN).coerceIn(0, sbGain.max)
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
        compareJob?.cancel()
        try {
            preview.stop()
        } catch (t: Throwable) {
            // 忽略
        }
        super.onDestroy()
    }

    private fun randomClip(): Clip? {
        val clips = VoiceIndex.all(this)
        if (clips.isEmpty()) return null
        return clips[(Math.random() * clips.size).toInt().coerceIn(0, clips.size - 1)]
    }

    private fun doPreview() {
        val clip = randomClip()
        if (clip == null) {
            Toast.makeText(this, "語音索引是空的", Toast.LENGTH_SHORT).show()
            return
        }
        val mode = Prefs.audioMode(this)
        compareJob?.cancel()
        compareJob = lifecycleScope.launch {
            tvModeNote.text = "${AudioMode.label(mode)}\n${clip.text}"
            preview.play(clip, mode, Prefs.masterDb(this@MainActivity), duck = true)
            renderMode()
        }
    }

    /** 同一句連續播四個版本。差異要背靠背才聽得出來，隔一分鐘再比是比不出來的。 */
    private fun doCompare() {
        if (compareJob?.isActive == true) {
            compareJob?.cancel()
            preview.stop()
            renderMode()
            return
        }
        val clip = randomClip()
        if (clip == null) {
            Toast.makeText(this, "語音索引是空的", Toast.LENGTH_SHORT).show()
            return
        }
        compareJob = lifecycleScope.launch {
            for (m in 0 until AudioMode.COUNT) {
                tvModeNote.text = "循環試播 ${m + 1}/${AudioMode.COUNT}　${AudioMode.label(m)}\n${clip.text}"
                preview.play(clip, m, Prefs.masterDb(this@MainActivity), duck = true)
                delay(600L)
            }
            tvModeNote.text = "循環試播結束。\n${clip.text}"
            delay(2500L)
            renderMode()
        }
    }

    private fun syncToggle() {
        btnToggle.text = if (NarratorService.isRunning) "停止" else "開始"
    }

    private fun refreshStats() {
        lifecycleScope.launch {
            val dao = AppDb.get(this@MainActivity).dao()
            val since = startOfToday()
            tvStats.text = "今日播放 ${dao.countLogsSince(since)}　今日標記 ${dao.countFlagsSince(since)}"
        }
    }

    private fun renderMode() {
        val m = Prefs.audioMode(this)
        tvModeNote.text = AudioMode.hint(m)
    }

    private fun renderGain() {
        tvGain.text = String.format(Locale.US, "旁白音量 %+d dB（只能衰減）", Prefs.masterDb(this).toInt())
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
