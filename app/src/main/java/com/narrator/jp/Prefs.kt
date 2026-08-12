package com.narrator.jp

import android.content.Context
import android.content.SharedPreferences

object Prefs {

    private const val FILE = "narrator_prefs"

    private const val K_DB = "master_db"
    private const val K_W_NORMAL = "w_normal"
    private const val K_W_BURST = "w_burst"
    private const val K_W_LONG = "w_long"
    private const val K_EXCLUDE = "exclude_flagged"
    private const val K_SHAKE = "shake_enabled"
    private const val K_SHAKE_SENS = "shake_sensitivity"
    private const val K_COMMUTE = "commute_mode"
    private const val K_WAS_RUNNING = "was_running"

    /** 音量以 dB 表示。0 dB = 音檔原始響度；負值走 MediaPlayer 衰減，正值走 LoudnessEnhancer 增幅。 */
    const val DB_MIN = -18
    const val DB_MAX = 12
    const val DB_DEFAULT = 3

    /** 通勤模式的間隔倍率。 */
    const val COMMUTE_SCALE = 0.5

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun masterDb(ctx: Context): Float =
        sp(ctx).getInt(K_DB, DB_DEFAULT).coerceIn(DB_MIN, DB_MAX).toFloat()

    fun setMasterDb(ctx: Context, db: Int) {
        sp(ctx).edit().putInt(K_DB, db.coerceIn(DB_MIN, DB_MAX)).apply()
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

    /** 通勤模式：三組區間全部乘 0.5。隨時可切換，等待中切換也會立刻反映。 */
    fun commuteMode(ctx: Context): Boolean = sp(ctx).getBoolean(K_COMMUTE, false)

    fun setCommuteMode(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(K_COMMUTE, v).apply()
    }

    fun intervalScale(ctx: Context): Double = if (commuteMode(ctx)) COMMUTE_SCALE else 1.0

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
