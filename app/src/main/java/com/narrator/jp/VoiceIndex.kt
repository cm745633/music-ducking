package com.narrator.jp

import android.content.Context
import org.json.JSONArray

/**
 * 一句旁白。offset / length 指向 assets/voices.bin 內的一段 mp3。
 * 600 條語音打包成單一 blob，而不是 600 個檔案，是為了讓非開發者能一次把
 * 專案拖上 GitHub（網頁上傳一次最多 100 個檔案）。
 */
data class Clip(
    val id: String,
    val file: String,
    val character: String,
    val text: String,
    val domain: String,
    val offset: Long,
    val length: Long,
    /**
     * 隔離區。PC 端把有問題的句子標成 true，抽選時跳過，但音訊仍留在
     * voices.bin 裡、索引也還在——所以播放歷史仍看得到原文，
     * 之後改回 false 就直接復活。欄位不存在時視為 false，舊的 index.json 照舊能用。
     */
    val quarantined: Boolean = false
)

object VoiceIndex {

    const val BLOB = "voices.bin"

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
            out.add(
                Clip(
                    id = o.getString("id"),
                    file = o.getString("file"),
                    character = o.getString("character"),
                    text = o.getString("text"),
                    domain = o.getString("domain"),
                    offset = o.getLong("offset"),
                    length = o.getLong("length"),
                    quarantined = o.optBoolean("quarantined", false)
                )
            )
        }
        return out
    }
}
