package com.narrator.jp

import android.content.Context
import java.util.Random

/**
 * 間隔採加權隨機而非均勻隨機：均勻隨機聽起來像節拍器，
 * 加權之後才有「路口密集區」與「長直路」的體感落差。
 */
class Picker(private val ctx: Context) {

    companion object {
        // 診斷版只有 60 條，ring buffer 必須明顯小於池子，否則每一條都會被排除。
        const val RING_SIZE = 20

        const val NORMAL_MIN_MS = 120_000L
        const val NORMAL_MAX_MS = 240_000L
        const val BURST_MIN_MS = 20_000L
        const val BURST_MAX_MS = 40_000L
        const val LONG_MIN_MS = 300_000L
        const val LONG_MAX_MS = 420_000L
    }

    private val rng = Random()
    private val recent = ArrayDeque<String>()

    @Volatile
    private var excluded: Set<String> = emptySet()

    fun setExcluded(ids: Collection<String>) {
        excluded = ids.toSet()
    }

    /**
     * 回傳「未套用倍率」的基礎間隔。通勤模式的 0.5 倍是由 Service 在等待迴圈裡
     * 每秒重算時才乘上去的——這樣使用者在等待中途勾選通勤模式，當下就會生效，
     * 不必等到下一輪。
     */
    fun nextIntervalMs(): Long {
        val wn = Prefs.wNormal(ctx).coerceAtLeast(0)
        val wb = Prefs.wBurst(ctx).coerceAtLeast(0)
        val wl = Prefs.wLong(ctx).coerceAtLeast(0)
        val total = wn + wb + wl
        if (total <= 0) return range(NORMAL_MIN_MS, NORMAL_MAX_MS)
        val r = rng.nextInt(total)
        return when {
            r < wn -> range(NORMAL_MIN_MS, NORMAL_MAX_MS)
            r < wn + wb -> range(BURST_MIN_MS, BURST_MAX_MS)
            else -> range(LONG_MIN_MS, LONG_MAX_MS)
        }
    }

    /** 抽中最近 60 條之內的就重抽，避免短期重複。隔離區的句子不進池子。 */
    fun pick(): Clip? {
        val pool = VoiceIndex.playable(ctx).filter { c -> !excluded.contains(c.id) }
        if (pool.isEmpty()) return null
        repeat(200) {
            val c = pool[rng.nextInt(pool.size)]
            if (!recent.contains(c.id)) {
                remember(c.id)
                return c
            }
        }
        // 池子比 ring buffer 還小的退化情形：接受重複，不要卡住。
        val c = pool[rng.nextInt(pool.size)]
        remember(c.id)
        return c
    }

    private fun remember(id: String) {
        recent.addLast(id)
        while (recent.size > RING_SIZE) recent.removeFirst()
    }

    private fun range(minMs: Long, maxMs: Long): Long {
        if (maxMs <= minMs) return minMs
        return minMs + (rng.nextDouble() * (maxMs - minMs)).toLong()
    }
}
