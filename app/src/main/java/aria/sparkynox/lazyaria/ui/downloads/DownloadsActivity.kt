package aria.sparkynox.lazyaria.ui.downloads

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import aria.sparkynox.lazyaria.R

class DownloadsActivity : AppCompatActivity() {

    data class DownloadItem(
        val id: Long,
        val title: String,
        val uri: String,
        val status: Int,
        val size: Long,
        val downloaded: Long,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Downloads"
        loadDownloads()
    }

    private fun loadDownloads() {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query()
        val cursor: Cursor = dm.query(query)
        val items = mutableListOf<DownloadItem>()

        while (cursor.moveToNext()) {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
            val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: ""
            val uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)) ?: ""
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            items.add(DownloadItem(id, title, uri, status, size, downloaded))
        }
        cursor.close()

        val rv = findViewById<RecyclerView>(R.id.downloadsList)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = DownloadsAdapter(items)
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressed(); return true }

    class DownloadsAdapter(private val items: List<DownloadItem>) : RecyclerView.Adapter<DownloadsAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.dlTitle)
            val status: TextView = v.findViewById(R.id.dlStatus)
            val size: TextView = v.findViewById(R.id.dlSize)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, pos: Int) {
            val item = items[pos]
            holder.title.text = item.title.ifBlank { item.uri.substringAfterLast('/') }
            holder.status.text = statusString(item.status)
            holder.size.text = if (item.size > 0)
                "${formatBytes(item.downloaded)} / ${formatBytes(item.size)}"
            else formatBytes(item.downloaded)
        }

        override fun getItemCount() = items.size

        private fun statusString(status: Int) = when (status) {
            DownloadManager.STATUS_PENDING -> "Pending"
            DownloadManager.STATUS_RUNNING -> "Downloading"
            DownloadManager.STATUS_PAUSED -> "Paused"
            DownloadManager.STATUS_SUCCESSFUL -> "Complete"
            DownloadManager.STATUS_FAILED -> "Failed"
            else -> "Unknown"
        }

        private fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            var value = bytes.toDouble()
            var i = 0
            while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
            return "%.1f %s".format(value, units[i])
        }
    }
}
