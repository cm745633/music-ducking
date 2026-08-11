package com.narrator.jp

import android.content.Context
import android.content.SharedPreferences

object Prefs {

    private const val FILE = "narrator_prefs"

    private const val K_GAIN = "master_gain"
    private const val K_W_NORMAL = "w_normal"
    private const val K_W_BURST = "w_burst"
    private const val K_W_LONG = "w_long"
    private const val K_EXCLUDE = "exclude_flagged"
    private const val K_SHAKE = "shake_enabled"
    private const val K_SHAKE_SENS = "shake_sensitivity"
    private const val K_WAS_RUNNING = "was_running"

    const val GAIN_MIN = 0.2f
    const val GAIN_MAX = 1.0f

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun masterGain(ctx: Context): Float =
        sp(ctx).getFloat(K_GAIN, 0.8f).coerceIn(GAIN_MIN, GAIN_MAX)

    fun setMasterGain(ctx: Context, v: Float) {
        sp(ctx).edit().putFloat(K_GAIN, v.coerceIn(GAIN_MIN, GAIN_MAX)).apply()
    }

    fun wNormal(ctx: Context): Int = sp(ctx).getInt(K_W_NORMAL, 70)
    fun wBurst(ctx: Context): Int = sp(ctx).getInt(K_W_BURST, 20)
    fun wLong(ctx: Context): Int = sp(ctx).getInt(K_W_LONG, 10)

    fun setWeights(ctx: Context, normal: Int, burst: Int, long: Int) {
        sp(ctx).edit()
            .putInt(K_W_NORMAL, normal.coerceAtLeast(0))
            .putInt(K_W_BURST, burst.coerceAtLeast(0))
            .putInt(K_W_LONG, long.coerceAtLeast(0))
            .apply()
    }

    fun excludeFlagged(ctx: Context): Boolean = sp(ctx).getBoolean(K_EXCLUDE, true)
    fun setExcludeFlagged(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(K_EXCLUDE, v).apply()
    }

    /** 預設關閉，避免誤觸。 */
    fun shakeEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(K_SHAKE, false)
    fun setShakeEnabled(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(K_SHAKE, v).apply()
    }

    /** 1（最遲鈍）～ 10（最靈敏）。 */
    fun shakeSensitivity(ctx: Context): Int = sp(ctx).getInt(K_SHAKE_SENS, 5).coerceIn(1, 10)
    fun setShakeSensitivity(ctx: Context, v: Int) {
        sp(ctx).edit().putInt(K_SHAKE_SENS, v.coerceIn(1, 10)).apply()
    }

    fun wasRunning(ctx: Context): Boolean = sp(ctx).getBoolean(K_WAS_RUNNING, false)
    fun setWasRunning(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(K_WAS_RUNNING, v).apply()
    }
}
