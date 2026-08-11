package com.narrator.jp

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 匯出給 PC 端腳本直接讀取，不做裝飾性排版。
 * flags.csv 帶 UTF-8 BOM，Excel 開啟才不會把日文變亂碼。
 */
object Exporter {

    private val TS = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    suspend fun export(ctx: Context): String = withContext(Dispatchers.IO) {
        val dao = AppDb.get(ctx).dao()
        val flags = dao.allFlags()
        val logs = dao.allLogs()
        val playedAtById = HashMap<Long, Long>(logs.size)
        for (l in logs) playedAtById[l.logId] = l.playedAt

        val csv = buildCsv(ctx, flags, playedAtById)
        val blacklist = buildBlacklist(ctx, flags)
        val stats = buildStats(ctx, flags, logs)

        val written = ArrayList<String>()
        written.add(write(ctx, "flags.csv", "text/csv", csv))
        written.add(write(ctx, "blacklist.txt", "text/plain", blacklist))
        written.add(write(ctx, "stats.md", "text/markdown", stats))

        "已匯出到 Download/：\n" + written.joinToString("\n")
    }

    private fun buildCsv(
        ctx: Context,
        flags: List<Flag>,
        playedAtById: Map<Long, Long>
    ): String {
        val sb = StringBuilder()
        sb.append('\uFEFF')  // UTF-8 BOM：Excel 開啟時日文才不會亂碼
        sb.append("clip_id,file,character,text,reason,note,played_at,flagged_at,latency_sec\n")
        for (f in flags) {
            val clip = VoiceIndex.byId(ctx, f.clipId)
            val playedAt = playedAtById[f.logId]
            val row = listOf(
                f.clipId,
                clip?.file ?: "",
                clip?.character ?: "",
                clip?.text ?: "",
                f.reason ?: "",
                f.note ?: "",
                if (playedAt != null) TS.format(Date(playedAt)) else "",
                TS.format(Date(f.flaggedAt)),
                (f.latencyMs / 1000L).toString()
            )
            sb.append(row.joinToString(",") { v -> csvField(v) })
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun csvField(v: String): String {
        val cleaned = v.replace("\r", " ").replace("\n", " ")
        return "\"" + cleaned.replace("\"", "\"\"") + "\""
    }

    /** 一行一個檔名。排除 mistake（標錯）與 good（正面標記）。 */
    private fun buildBlacklist(ctx: Context, flags: List<Flag>): String {
        val names = LinkedHashSet<String>()
        for (f in flags) {
            if (f.reason == Reasons.MISTAKE || f.reason == Reasons.GOOD) continue
            val clip = VoiceIndex.byId(ctx, f.clipId) ?: continue
            names.add(clip.file)
        }
        return names.joinToString("\n") + if (names.isEmpty()) "" else "\n"
    }

    private fun buildStats(ctx: Context, flags: List<Flag>, logs: List<PlayLog>): String {
        val clips = VoiceIndex.all(ctx)
        val charOf = HashMap<String, String>(clips.size)
        for (c in clips) charOf[c.id] = c.character

        val playsPerChar = LinkedHashMap<String, Int>()
        for (c in clips) playsPerChar[c.character] = 0
        for (l in logs) {
            val ch = charOf[l.clipId] ?: continue
            playsPerChar[ch] = (playsPerChar[ch] ?: 0) + 1
        }

        val flagsPerChar = LinkedHashMap<String, Int>()
        val reasonCount = LinkedHashMap<String, Int>()
        var stale = 0
        for (f in flags) {
            val ch = charOf[f.clipId] ?: "?"
            flagsPerChar[ch] = (flagsPerChar[ch] ?: 0) + 1
            val r = f.reason ?: "(未分類)"
            reasonCount[r] = (reasonCount[r] ?: 0) + 1
            if (f.latencyMs > Flagger.STALE_THRESHOLD_MS) stale++
        }

        val sb = StringBuilder()
        sb.append("# 標記統計\n\n")
        sb.append("匯出時間: ").append(TS.format(Date())).append("\n")
        sb.append("APK 版本: ").append(BuildConfig.VERSION_NAME)
            .append(" (建置日 ").append(BuildConfig.BUILD_DATE).append(")\n\n")
        sb.append("- 播放總數（保留 30 天內）: ").append(logs.size).append("\n")
        sb.append("- 標記總數: ").append(flags.size).append("\n")
        sb.append("- 延遲超過 5 分鐘的標記: ").append(stale).append("（可信度較低）\n\n")

        sb.append("## 各角色被標記率\n\n")
        sb.append("| 角色 | 播放 | 標記 | 標記率 |\n|---|---|---|---|\n")
        for (ch in playsPerChar.keys) {
            val plays = playsPerChar[ch] ?: 0
            val fl = flagsPerChar[ch] ?: 0
            val rate = if (plays > 0) String.format(Locale.US, "%.1f%%", fl * 100.0 / plays) else "-"
            sb.append("| ").append(ch).append(" | ").append(plays)
                .append(" | ").append(fl).append(" | ").append(rate).append(" |\n")
        }

        sb.append("\n## 各 reason 分布\n\n")
        sb.append("| reason | 數量 |\n|---|---|\n")
        if (reasonCount.isEmpty()) {
            sb.append("| (無) | 0 |\n")
        } else {
            for (r in reasonCount.keys) {
                sb.append("| ").append(r).append(" | ").append(reasonCount[r]).append(" |\n")
            }
        }
        sb.append("\n角色標記率若明顯偏高，代表該角色的音色或領域分配有問題。\n")
        return sb.toString()
    }

    private fun write(ctx: Context, name: String, mime: String, content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "$name（寫入失敗）"
            resolver.openOutputStream(uri)?.use { os -> os.write(bytes) }
            name
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            f.outputStream().use { os -> os.write(bytes) }
            f.absolutePath
        }
    }
}
