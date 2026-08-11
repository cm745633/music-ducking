package com.narrator.jp

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/** 免視覺入口。預設關閉，由使用者自行開啟。 */
class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {

    companion object {
        private const val COOLDOWN_MS = 2000L
    }

    /** 由設定頁的靈敏度 1～10 換算而來，數字越小越靈敏。 */
    @Volatile
    var thresholdMs2: Float = 12f

    private var lastFired = 0L

    fun applySensitivity(level: Int) {
        // 1 -> 18 m/s^2（要用力甩），10 -> 6 m/s^2（輕晃即可）
        val clamped = level.coerceIn(1, 10)
        thresholdMs2 = 18f - (clamped - 1) * (12f / 9f)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH
        if (magnitude > thresholdMs2) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFired > COOLDOWN_MS) {
                lastFired = now
                onShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 不需要
    }
}
