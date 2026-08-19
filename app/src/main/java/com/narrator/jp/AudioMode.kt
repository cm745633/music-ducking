package com.narrator.jp

/**
 * 四個版本的同一句話，用來把「雜音是哪一段造成的」拆成可以逐一排除的變因。
 *
 *   A → B  有差 ＝ mp3 編碼本身的影響（128k 已經是高品質，通常聽不出差別）
 *   B → C  有差 ＝ 位元率不夠（64 kbps 對 44.1 kHz 語音偏低）
 *   C → D  有差 ＝ 壓縮器與軟削造成的
 *   四個都有雜音 ＝ 問題在合成引擎的原始輸出，或在耳機
 */
object AudioMode {

    const val COUNT = 4

    val LABELS: List<String> = listOf(
        "A　原始 WAV（未處理、未編碼）",
        "B　只正規化 → 128 kbps mp3",
        "C　只正規化 → 64 kbps mp3",
        "D　正式版：壓縮＋軟削 → 64 kbps mp3"
    )

    val HINTS: List<String> = listOf(
        "基準。這個版本就有雜音的話，問題在合成引擎或耳機，不在轉檔。",
        "跟 A 有差別 ＝ mp3 編碼本身造成的。",
        "跟 B 有差別 ＝ 位元率不夠（正式版用的就是 64k）。",
        "跟 C 有差別 ＝ 壓縮器與軟削造成的。這是正式版實際在用的版本。"
    )

    fun label(mode: Int): String = LABELS.getOrElse(mode) { LABELS[0] }
    fun hint(mode: Int): String = HINTS.getOrElse(mode) { HINTS[0] }
}
