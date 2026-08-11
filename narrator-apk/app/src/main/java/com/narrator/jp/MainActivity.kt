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

    private lateinit var btnToggle: Button
    private lateinit var tvStats: TextView
    private lateinit var tvGain: TextView
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
        tvGain = findViewById(R.id.tv_gain)
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

        findViewById<Button>(R.id.btn_preview).setOnClickListener {
            doPreview()
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

        // 音量：0.20 ～ 1.00，滑桿 0～80。即時生效，不需重啟服務。
        sbGain.progress = ((Prefs.masterGain(this) - Prefs.GAIN_MIN) * 100f).toInt().coerceIn(0, 80)
        renderGain()
        sbGain.onChange { p ->
            val g = Prefs.GAIN_MIN + p / 100f
            Prefs.setMasterGain(this, g)
            renderGain()
            preview.applyVolume(Prefs.masterGain(this))
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
            toast("語音索引是空的")
            return
        }
        val clip = clips[(Math.random() * clips.size).toInt().coerceIn(0, clips.size - 1)]
        lifecycleScope.launch {
            preview.play(clip, Prefs.masterGain(this@MainActivity), duck = true)
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

    private fun renderGain() {
        val g = Prefs.masterGain(this)
        tvGain.text = String.format(Locale.US, "旁白音量 %.2f", g)
    }

    private fun renderWeights() {
        val n = sbW1.progress
        val b = sbW2.progress
        val l = sbW3.progress
        val total = (n + b + l).coerceAtLeast(1)
        tvW1.text = "常態 120～240 秒　$n（${pct(n, total)}）"
        tvW2.text = "連發 20～40 秒　$b（${pct(b, total)}）"
        tvW3.text = "長靜默 300～420 秒　$l（${pct(l, total)}）"
    }

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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
