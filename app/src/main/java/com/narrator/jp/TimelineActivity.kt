package com.narrator.jp

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 事後補標。最後的補救管道——當使用者事後想起
 * 「下午三點左右好像有一條怪的」時用。
 */
class TimelineActivity : AppCompatActivity() {

    private data class Row(
        val logId: Long,
        val playedAt: Long,
        val clipId: String,
        val character: String,
        val text: String,
        val flagged: Boolean
    )

    private val fmt = SimpleDateFormat("MM/dd HH:mm:ss", Locale.US)

    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var etSearch: EditText

    private var all: List<Row> = emptyList()
    private var shown: List<Row> = emptyList()
    private val adapter = RowAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timeline)
        title = getString(R.string.title_timeline)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        rv = findViewById(R.id.rv)
        tvEmpty = findViewById(R.id.tv_empty)
        etSearch = findViewById(R.id.et_search)

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString() ?: "")
            }
        })
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val dao = AppDb.get(this@TimelineActivity).dao()
            val since = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            val logs = dao.logsSince(since)
            val flaggedIds = dao.flaggedLogIds().toHashSet()
            all = logs.map { log ->
                val clip = VoiceIndex.byId(this@TimelineActivity, log.clipId)
                Row(
                    logId = log.logId,
                    playedAt = log.playedAt,
                    clipId = log.clipId,
                    character = clip?.character ?: "?",
                    text = clip?.text ?: log.clipId,
                    flagged = flaggedIds.contains(log.logId)
                )
            }
            applyFilter(etSearch.text?.toString() ?: "")
        }
    }

    private fun applyFilter(q: String) {
        shown = if (q.isBlank()) all else all.filter { r -> r.text.contains(q) }
        adapter.notifyDataSetChanged()
        val empty = shown.isEmpty()
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        rv.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun toggle(row: Row) {
        lifecycleScope.launch {
            if (row.flagged) {
                Flagger.unflag(this@TimelineActivity, row.logId)
                Toast.makeText(this@TimelineActivity, "已取消標記", Toast.LENGTH_SHORT).show()
            } else {
                if (Flagger.flag(this@TimelineActivity, row.logId)) {
                    Buzz.tick(this@TimelineActivity)
                    Toast.makeText(this@TimelineActivity, "已標記", Toast.LENGTH_SHORT).show()
                }
            }
            load()
        }
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val meta: TextView = view.findViewById(R.id.tv_meta)
            val text: TextView = view.findViewById(R.id.tv_text)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_timeline, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = shown.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = shown[position]
            val mark = if (r.flagged) "　✕ 已標記" else ""
            holder.meta.text = "${fmt.format(Date(r.playedAt))}　${r.character}　${r.clipId}$mark"
            holder.text.text = r.text
            holder.itemView.setOnClickListener { toggle(r) }
        }
    }
}
