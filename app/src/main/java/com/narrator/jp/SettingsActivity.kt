package com.narrator.jp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var swShake: SwitchCompat
    private lateinit var swExclude: SwitchCompat
    private lateinit var sbSens: SeekBar
    private lateinit var tvSens: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.title_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        swShake = findViewById(R.id.sw_shake)
        swExclude = findViewById(R.id.sw_exclude)
        sbSens = findViewById(R.id.sb_sens)
        tvSens = findViewById(R.id.tv_sens)

        swShake.isChecked = Prefs.shakeEnabled(this)
        swShake.setOnCheckedChangeListener { _, checked ->
            Prefs.setShakeEnabled(this, checked)
            if (NarratorService.isRunning) {
                // 重新送一次 START，服務會依新設定重掛/卸載感測器。
                NarratorService.start(this)
            }
        }

        swExclude.isChecked = Prefs.excludeFlagged(this)
        swExclude.setOnCheckedChangeListener { _, checked ->
            Prefs.setExcludeFlagged(this, checked)
        }

        sbSens.progress = Prefs.shakeSensitivity(this) - 1
        renderSens()
        sbSens.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Prefs.setShakeSensitivity(this@SettingsActivity, progress + 1)
                renderSens()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.btn_export).setOnClickListener { doExport() }
        findViewById<Button>(R.id.btn_battery).setOnClickListener { openBatterySettings() }

        findViewById<TextView>(R.id.tv_about).text =
            "版本 ${BuildConfig.VERSION_NAME}（建置日 ${BuildConfig.BUILD_DATE}）\n" +
                "語音 ${VoiceIndex.all(this).size} 條"
    }

    private fun renderSens() {
        tvSens.text = "搖晃靈敏度 ${Prefs.shakeSensitivity(this)}（1 遲鈍、10 靈敏）"
    }

    private fun doExport() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 201
                )
                return
            }
        }
        lifecycleScope.launch {
            val msg = try {
                Exporter.export(this@SettingsActivity)
            } catch (t: Throwable) {
                "匯出失敗：${t.javaClass.simpleName} ${t.message ?: ""}"
            }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("匯出")
                .setMessage(msg)
                .setPositiveButton("好", null)
                .show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 201 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            doExport()
        }
    }

    private fun openBatterySettings() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已在白名單中", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (t2: Throwable) {
                Toast.makeText(this, "請手動到系統設定的電池最佳化中設定", Toast.LENGTH_LONG).show()
            }
        }
    }
}
