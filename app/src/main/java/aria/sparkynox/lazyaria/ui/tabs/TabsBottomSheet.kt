package aria.sparkynox.lazyaria.ui.tabs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import aria.sparkynox.lazyaria.R
import aria.sparkynox.lazyaria.browser.BrowserTab

class TabsBottomSheet : BottomSheetDialogFragment() {

    var onTabSelect: ((Int) -> Unit)? = null
    var onTabClose: ((Int) -> Unit)? = null
    var onNewTab: (() -> Unit)? = null

    companion object {
        fun newInstance(tabs: List<BrowserTab>, currentIndex: Int): TabsBottomSheet {
            return TabsBottomSheet().apply {
                this.tabs = tabs.toMutableList()
                this.currentIndex = currentIndex
            }
        }
    }

    private var tabs = mutableListOf<BrowserTab>()
    private var currentIndex = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.bottom_sheet_tabs, container, false)

        val rv = view.findViewById<RecyclerView>(R.id.tabsList)
        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = TabsAdapter(tabs, currentIndex,
            onSelect = { idx -> onTabSelect?.invoke(idx); dismiss() },
            onClose = { idx -> onTabClose?.invoke(idx); dismiss() }
        )

        view.findViewById<View>(R.id.btnNewTab).setOnClickListener {
            onNewTab?.invoke(); dismiss()
        }

        return view
    }

    class TabsAdapter(
        private val tabs: List<BrowserTab>,
        private val current: Int,
        private val onSelect: (Int) -> Unit,
        private val onClose: (Int) -> Unit,
    ) : RecyclerView.Adapter<TabsAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tabTitle)
            val url: TextView = v.findViewById(R.id.tabUrl)
            val favicon: ImageView = v.findViewById(R.id.tabFavicon)
            val close: ImageButton = v.findViewById(R.id.tabClose)
            val incognito: ImageView = v.findViewById(R.id.tabIncognito)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, pos: Int) {
            val tab = tabs[pos]
            holder.title.text = tab.title.ifBlank { "New Tab" }
            holder.url.text = tab.url
            holder.favicon.setImageBitmap(tab.favicon)
            holder.incognito.visibility = if (tab.isIncognito) View.VISIBLE else View.GONE
            holder.itemView.isSelected = pos == current
            holder.itemView.setOnClickListener { onSelect(pos) }
            holder.close.setOnClickListener { onClose(pos) }
        }

        override fun getItemCount() = tabs.size
    }
}
