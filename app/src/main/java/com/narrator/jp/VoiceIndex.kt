package com.narrator.jp

import android.content.Context
import org.json.JSONArray

/** 一句話的其中一個版本。blob 是 assets 內的檔名，offset/length 指向其中一段。 */
data class Variant(
    val blob: String,
    val offset: Long,
    val length: Long,
    val lufs: Float
)

data class Clip(
    val id: String,
    val file: String,
    val character: String,
    val text: String,
    val domain: String,
    val variants: List<Variant>,
    /** 隔離區。與正式版共用同一套語意：標成 true 就不進抽選池，音訊仍在。 */
    val quarantined: Boolean = false
) {
    /**
     * 四個版本裡最小聲的那個。播放時把其餘三個衰減到這個水準，
     * A/B 比較才不會被音量差異干擾——人耳會把比較大聲的誤判成比較清楚或比較吵。
     * 取最小值也保證所有調整都是衰減，不需要動用任何增幅效果。
     */
    val refLufs: Float = if (variants.isEmpty()) -16f else variants.minOf { v -> v.lufs }

    fun variant(mode: Int): Variant? = variants.getOrNull(mode)
}

object VoiceIndex {

    /**
     * 順序即 AudioMode 的 0..3，也就是 UI 上的 A / B / C / D。
     *
     * **不要把這個排序改成字母序。** 資產裡的 `c` / `d` 兩組欄位與檔名，
     * 內容跟 AudioMode 的 C / D 標籤是相反的——實測 60 條的響度可以確認：
     *
     * | 欄位 | 響度中位 | 實際內容 | 對應標籤 |
     * |---|---|---|---|
     * | `b_*` / voices_b.bin | -16.65 LUFS | 只正規化 → 128k | B |
     * | `d_*` / voices_d.bin | -16.61 LUFS | 只正規化 → 64k | **C** |
     * | `c_*` / voices_c.bin | -12.34 LUFS | 正式版壓縮＋軟削 → 64k | **D** |
     *
     * 判準是 b 與 d 的響度只差 0.04 dB（同一條正規化鏈路，只有位元率不同），
     * 而 c 高出 4.31 dB，正好落在 v1.1 正式版的 -12.30 LUFS 上。
     *
     * 把這裡改成 raw/b/c/d，UI 的 C 與 D 就會互換，
     * 使用者回報「C 開始有雜音」會被解讀成位元率不足，實際上是處理鏈的問題——
     * 剛好是整個診斷版最不能出錯的地方。
     */
    private val SPEC: List<Pair<String, String>> = listOf(
        "raw" to "voices_raw.bin",
        "b" to "voices_b.bin",
        "d" to "voices_d.bin",
        "c" to "voices_c.bin"
    )

    @Volatile
    private var cache: List<Clip>? = null

    @Volatile
    private var byId: Map<String, Clip> = emptyMap()

    fun all(ctx: Context): List<Clip> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val loaded = load(ctx)
            byId = loaded.associateBy { c -> c.id }
            cache = loaded
            return loaded
        }
    }

    fun byId(ctx: Context, id: String): Clip? {
        all(ctx)
        return byId[id]
    }

    /** 可被抽到的句子（不含隔離區）。 */
    fun playable(ctx: Context): List<Clip> = all(ctx).filter { c -> !c.quarantined }

    fun quarantinedCount(ctx: Context): Int = all(ctx).count { c -> c.quarantined }

    private fun load(ctx: Context): List<Clip> {
        val raw = ctx.applicationContext.assets.open("index.json")
            .bufferedReader(Charsets.UTF_8).use { r -> r.readText() }
        val arr = JSONArray(raw)
        val out = ArrayList<Clip>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val vs = ArrayList<Variant>(SPEC.size)
            for (spec in SPEC) {
                val key = spec.first
                vs.add(
                    Variant(
                        blob = spec.second,
                        offset = o.getLong(key + "_offset"),
                        length = o.getLong(key + "_length"),
                        lufs = o.getDouble(key + "_lufs").toFloat()
                    )
                )
            }
            out.add(
                Clip(
                    id = o.getString("id"),
                    file = o.getString("file"),
                    character = o.getString("character"),
                    text = o.getString("text"),
                    domain = o.getString("domain"),
                    variants = vs,
                    quarantined = o.optBoolean("quarantined", false)
                )
            )
        }
        return out
    }
}
