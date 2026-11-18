package aria.sparkynox.lazyaria.ui.bookmarks

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import aria.sparkynox.lazyaria.R
import aria.sparkynox.lazyaria.core.RustCore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*

class BookmarksActivity : AppCompatActivity() {

    data class Bookmark(val id: Long, val title: String, val url: String, val created_at: Long, val folder: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bookmarks)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Bookmarks"
        loadBookmarks()
    }

    private fun loadBookmarks() {
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) { RustCore.getBookmarksJson("root") }
            val type = object : TypeToken<List<Bookmark>>() {}.type
            val bookmarks: List<Bookmark> = Gson().fromJson(json, type)

            val rv = findViewById<RecyclerView>(R.id.bookmarksList)
            rv.layoutManager = LinearLayoutManager(this@BookmarksActivity)
            rv.adapter = BookmarksAdapter(bookmarks.toMutableList(),
                onClick = { bm ->
                    val result = Intent().putExtra("url", bm.url)
                    setResult(Activity.RESULT_OK, result)
                    finish()
                },
                onDelete = { bm ->
                    lifecycleScope.launch(Dispatchers.IO) { RustCore.removeBookmark(bm.url) }
                }
            )
        }
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressed(); return true }

    class BookmarksAdapter(
        private val items: MutableList<Bookmark>,
        private val onClick: (Bookmark) -> Unit,
        private val onDelete: (Bookmark) -> Unit,
    ) : RecyclerView.Adapter<BookmarksAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.bmTitle)
            val url: TextView = v.findViewById(R.id.bmUrl)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, pos: Int) {
            val bm = items[pos]
            holder.title.text = bm.title
            holder.url.text = bm.url
            holder.itemView.setOnClickListener { onClick(bm) }
            holder.itemView.setOnLongClickListener {
                PopupMenu(holder.itemView.context, holder.itemView).apply {
                    menu.add("Delete")
                    setOnMenuItemClickListener {
                        onDelete(bm)
                        items.removeAt(pos)
                        notifyItemRemoved(pos)
                        true
                    }
                    show()
                }
                true
            }
        }

        override fun getItemCount() = items.size
    }
}
