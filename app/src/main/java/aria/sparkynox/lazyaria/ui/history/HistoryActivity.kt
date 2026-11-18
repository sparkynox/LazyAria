package aria.sparkynox.lazyaria.ui.history

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.SearchView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import aria.sparkynox.lazyaria.R
import aria.sparkynox.lazyaria.core.RustCore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

    data class HistoryItem(val id: Long, val title: String, val url: String, val visited_at: Long)

    private lateinit var adapter: HistoryAdapter
    private val items = mutableListOf<HistoryItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "History"

        val rv = findViewById<RecyclerView>(R.id.historyList)
        adapter = HistoryAdapter(items,
            onClick = { item ->
                setResult(Activity.RESULT_OK, Intent().putExtra("url", item.url))
                finish()
            },
            onDelete = { item ->
                // single entry delete via Rust direct SQL — extend RustCore if needed
            }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        loadHistory()
    }

    private fun loadHistory(query: String = "") {
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                if (query.isBlank()) RustCore.getHistoryJson(100, 0)
                else RustCore.searchHistory(query)
            }
            val type = object : TypeToken<List<HistoryItem>>() {}.type
            val list: List<HistoryItem> = Gson().fromJson(json, type)
            items.clear()
            items.addAll(list)
            adapter.notifyDataSetChanged()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_history, menu)
        val searchItem = menu.findItem(R.id.action_search)
        (searchItem.actionView as? SearchView)?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String) = run { loadHistory(q); true }
            override fun onQueryTextChange(q: String) = run { loadHistory(q); true }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_history -> {
                AlertDialog.Builder(this)
                    .setTitle("Clear all history?")
                    .setPositiveButton("Clear") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            RustCore.clearHistory()
                            withContext(Dispatchers.Main) {
                                items.clear(); adapter.notifyDataSetChanged()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            android.R.id.home -> { onBackPressed(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    class HistoryAdapter(
        private val items: List<HistoryItem>,
        private val onClick: (HistoryItem) -> Unit,
        private val onDelete: (HistoryItem) -> Unit,
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        private val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.histTitle)
            val url: TextView = v.findViewById(R.id.histUrl)
            val time: TextView = v.findViewById(R.id.histTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, pos: Int) {
            val item = items[pos]
            holder.title.text = item.title
            holder.url.text = item.url
            holder.time.text = sdf.format(Date(item.visited_at * 1000))
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
