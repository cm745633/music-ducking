package com.narrator.jp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.util.Locale

/** 事後分類。此頁的試聽不觸發 ducking、不寫入 PlayLog。 */
class ReviewActivity : AppCompatActivity() {

    private data class Row(
        val flagId: Long,
        val clipId: String,
        val character: String,
        val text: String,
        val latencyMs: Long,
        val gainDb: Float
    )

    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var player: VoicePlayer

    private var shown: List<Row> = emptyList()
    private val adapter = RowAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review)
        title = getString(R.string.title_review)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        player = VoicePlayer(this)

        rv = findViewById(R.id.rv)
        tvEmpty = findViewById(R.id.tv_empty)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        try {
            player.stop()
        } catch (t: Throwable) {
            // 忽略
        }
        super.onDestroy()
    }

    private fun load() {
        lifecycleScope.launch {
            val dao = AppDb.get(this@ReviewActivity).dao()
            val flags = dao.unclassifiedFlags()
            val gains = dao.allGains().associate { g -> g.clipId to g.gainDb }
            shown = flags.map { f ->
                val clip = VoiceIndex.byId(this@ReviewActivity, f.clipId)
                Row(
                    flagId = f.flagId,
                    clipId = f.clipId,
                    character = clip?.character ?: "?",
                    text = clip?.text ?: f.clipId,
                    latencyMs = f.latencyMs,
                    gainDb = gains[f.clipId] ?: 0f
                )
            }
            adapter.notifyDataSetChanged()
            val empty = shown.isEmpty()
            tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            rv.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    private fun listen(row: Row) {
        val clip = VoiceIndex.byId(this, row.clipId) ?: return
        lifecycleScope.launch {
            val gain = Prefs.masterGain(this@ReviewActivity) *
                Math.pow(10.0, row.gainDb / 20.0).toFloat()
            player.play(clip, gain.coerceIn(0f, 1f), duck = false)
        }
    }

    private fun classify(row: Row) {
        AlertDialog.Builder(this)
            .setTitle(row.text)
            .setItems(Reasons.LABELS.toTypedArray()) { _, which ->
                val code = Reasons.CODES[which]
                lifecycleScope.launch {
                    val dao = AppDb.get(this@ReviewActivity).dao()
                    if (code == Reasons.MISTAKE) {
                        // 標錯了：取消標記，還原至抽選池。沒有退路的話使用者會不敢標。
                        dao.deleteFlag(row.flagId)
                        Toast.makeText(this@ReviewActivity, "已取消標記，還原至抽選池", Toast.LENGTH_SHORT).show()
                    } else {
                        dao.classify(row.flagId, code, null)
                        Toast.makeText(this@ReviewActivity, "已分類：$code", Toast.LENGTH_SHORT).show()
                    }
                    load()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun adjustGain(row: Row) {
        val steps = (-6..6).toList()
        val labels = steps.map { d -> String.format(Locale.US, "%+d dB", d) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("單條增益（目前 ${String.format(Locale.US, "%+.0f dB", row.gainDb)}）")
            .setItems(labels) { _, which ->
                lifecycleScope.launch {
                    AppDb.get(this@ReviewActivity).dao()
                        .setGain(ClipGain(row.clipId, steps[which].toFloat()))
                    Toast.makeText(this@ReviewActivity, "已設定 ${labels[which]}", Toast.LENGTH_SHORT).show()
                    load()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.tv_text)
            val meta: TextView = view.findViewById(R.id.tv_meta)
            val listen: Button = view.findViewById(R.id.btn_listen)
            val gain: Button = view.findViewById(R.id.btn_gain)
            val classify: Button = view.findViewById(R.id.btn_classify)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_review, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = shown.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = shown[position]
            holder.text.text = r.text
            val sec = r.latencyMs / 1000L
            val gainNote = if (r.gainDb != 0f) String.format(Locale.US, "　增益 %+.0f dB", r.gainDb) else ""
            val stale = r.latencyMs > Flagger.STALE_THRESHOLD_MS
            holder.meta.text = if (stale) {
                "${r.character}　${r.clipId}　延遲 ${sec}s — 這筆可能標錯對象$gainNote"
            } else {
                "${r.character}　${r.clipId}　延遲 ${sec}s$gainNote"
            }
            holder.meta.setBackgroundColor(
                if (stale) ContextCompat.getColor(this@ReviewActivity, R.color.stale_warning) else 0
            )
            holder.meta.setTextColor(if (stale) 0xFF3A2E00.toInt() else 0xFF888888.toInt())
            holder.listen.setOnClickListener { listen(r) }
            holder.gain.setOnClickListener { adjustGain(r) }
            holder.classify.setOnClickListener { classify(r) }
        }
    }
}
