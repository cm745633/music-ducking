package com.narrator.jp

import android.content.Context

/**
 * 標記寫入。刻意不放在 Service 裡：快速設定磚可能在服務沒跑的時候被點，
 * 這時不該（也不能）為了寫一筆標記去啟動前景服務。
 */
object Flagger {

    /** 延遲超過這個門檻的標記，事後檢視時標黃提示「這筆可能標錯對象」。 */
    const val STALE_THRESHOLD_MS = 5 * 60 * 1000L

    suspend fun flag(ctx: Context, logId: Long): Boolean {
        val dao = AppDb.get(ctx).dao()
        if (dao.flagForLog(logId) != null) return false
        val log = dao.logById(logId) ?: return false
        val now = System.currentTimeMillis()
        dao.insertFlag(
            Flag(
                logId = log.logId,
                clipId = log.clipId,
                flaggedAt = now,
                latencyMs = now - log.playedAt
            )
        )
        return true
    }

    /** 標記「直前」那條——搖晃與快速設定磚用。 */
    suspend fun flagMostRecent(ctx: Context): Boolean {
        val dao = AppDb.get(ctx).dao()
        val last = dao.recentLogs(1).firstOrNull() ?: return false
        return flag(ctx, last.logId)
    }

    suspend fun unflag(ctx: Context, logId: Long) {
        AppDb.get(ctx).dao().deleteFlagByLog(logId)
    }
}
