package com.narrator.jp

/** 事後分類的代碼與顯示文字。順序即對話框中的順序。 */
object Reasons {

    const val MISTAKE = "mistake"
    const val GOOD = "good"

    val CODES: List<String> = listOf(
        "reading", "accent", "too_quiet", "too_loud",
        "noise", "text", "voice", GOOD, MISTAKE
    )

    val LABELS: List<String> = listOf(
        "reading — 発音・読み間違い",
        "accent — イントネーションが不自然",
        "too_quiet — 音量が小さすぎる",
        "too_loud — 音量が大きすぎる",
        "noise — ノイズ・音質異常",
        "text — 文章自体に問題",
        "voice — 声とテキストが合わない",
        "good — 效果特別好（正面標記）",
        "mistake — 標記錯了，還原至抽選池"
    )

    fun label(code: String?): String {
        if (code == null) return "未分類"
        val i = CODES.indexOf(code)
        return if (i >= 0) LABELS[i] else code
    }
}
