package aria.sparkynox.lazyaria.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import aria.sparkynox.lazyaria.R
import aria.sparkynox.lazyaria.browser.BrowserTab
import aria.sparkynox.lazyaria.browser.LazyDownloader
import aria.sparkynox.lazyaria.browser.LazyWebView
import aria.sparkynox.lazyaria.browser.TabManager
import aria.sparkynox.lazyaria.core.Prefs
import aria.sparkynox.lazyaria.core.RustCore
import aria.sparkynox.lazyaria.databinding.ActivityMainBinding
import aria.sparkynox.lazyaria.ui.bookmarks.BookmarksActivity
import aria.sparkynox.lazyaria.ui.downloads.DownloadsActivity
import aria.sparkynox.lazyaria.ui.history.HistoryActivity
import aria.sparkynox.lazyaria.ui.settings.SettingsActivity
import aria.sparkynox.lazyaria.ui.tabs.TabsBottomSheet
import aria.sparkynox.lazyaria.utils.GestureHandler
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.*
import java.io.File

class MainActivity : AppCompatActivity(), LazyWebView.TabCallback {

    private lateinit var binding: ActivityMainBinding
    private val tabManager = TabManager()
    private val webViews = mutableMapOf<Int, LazyWebView>()
    private var currentWebView: LazyWebView? = null
    private var incognitoMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        initRustCore()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAddressBar()
        setupBottomBar()
        setupGestures()
        newTab(Prefs.homepage)

        // Handle incoming URL from intent
        intent?.data?.toString()?.let { url ->
            loadUrl(url)
        }
    }

    private fun initRustCore() {
        val dbPath = File(filesDir, "lazyaria.db").absolutePath
        lifecycleScope.launch(Dispatchers.IO) {
            RustCore.initDb(dbPath)
            loadBlocklist()
        }
    }

    private suspend fun loadBlocklist() {
        try {
            val hosts = assets.open("blocklist.txt").bufferedReader().readText()
            val count = RustCore.loadBlocklist(hosts)
        } catch (_: Exception) {}
    }

    // ── Address bar ───────────────────────────────────────────────────────────

    private fun setupAddressBar() {
        binding.urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                val input = binding.urlBar.text.toString().trim()
                if (input.isNotBlank()) {
                    loadUrl(RustCore.normalizeUrl(input))
                    hideKeyboard()
                }
                true
            } else false
        }

        binding.urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.urlBar.selectAll()
                lifecycleScope.launch {
                    showSuggestions(binding.urlBar.text.toString())
                }
            }
        }

        binding.urlBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                lifecycleScope.launch { showSuggestions(s.toString()) }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private suspend fun showSuggestions(query: String) {
        if (query.isBlank()) return
        withContext(Dispatchers.IO) {
            val results = RustCore.searchHistory(query)
            // Parse and show in dropdown — simplified; full impl uses ArrayAdapter
        }
    }

    // ── Tab management ────────────────────────────────────────────────────────

    fun newTab(url: String = "", incognito: Boolean = false): BrowserTab {
        val tab = tabManager.newTab(url, incognito)
        val wv = LazyWebView(this).also { wv ->
            wv.tabCallback = this
            webViews[tab.id] = wv
        }
        binding.webViewContainer.removeAllViews()
        binding.webViewContainer.addView(wv)
        currentWebView = wv
        updateTabCount()

        if (url.isNotBlank()) wv.loadUrl(url, incognito)
        return tab
    }

    fun switchToTab(index: Int) {
        tabManager.selectTab(index)
        val tab = tabManager.current ?: return
        val wv = webViews[tab.id] ?: return
        binding.webViewContainer.removeAllViews()
        binding.webViewContainer.addView(wv)
        currentWebView = wv
        binding.urlBar.setText(tab.url)
        binding.tabTitle.text = tab.title
    }

    fun closeTab(index: Int) {
        val tab = tabManager.tabs.getOrNull(index) ?: return
        webViews.remove(tab.id)?.destroy()
        tabManager.closeTab(index)
        switchToTab(tabManager.currentIndex)
        updateTabCount()
    }

    private fun updateTabCount() {
        binding.tabCount.text = tabManager.count.toString()
    }

    fun loadUrl(url: String) {
        currentWebView?.loadUrl(url, incognitoMode)
        binding.urlBar.setText(url)
        hideKeyboard()
    }

    // ── Bottom bar ────────────────────────────────────────────────────────────

    private fun setupBottomBar() {
        binding.btnBack.setOnClickListener {
            if (currentWebView?.canGoBack() == true) currentWebView?.goBack()
        }
        binding.btnBack.setOnLongClickListener {
            showNavigationHistory(back = true)
            true
        }
        binding.btnForward.setOnClickListener {
            if (currentWebView?.canGoForward() == true) currentWebView?.goForward()
        }
        binding.btnForward.setOnLongClickListener {
            showNavigationHistory(back = false)
            true
        }
        binding.btnHome.setOnClickListener {
            loadUrl(Prefs.homepage)
        }
        binding.btnTabs.setOnClickListener {
            showTabsSheet()
        }
        binding.btnMenu.setOnClickListener {
            showMainMenu()
        }
    }

    private fun showTabsSheet() {
        TabsBottomSheet.newInstance(tabManager.tabs, tabManager.currentIndex).also {
            it.onTabSelect = { idx -> switchToTab(idx) }
            it.onTabClose = { idx -> closeTab(idx) }
            it.onNewTab = { newTab() }
        }.show(supportFragmentManager, "tabs")
    }

    private fun showMainMenu() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_menu, null)
        sheet.setContentView(view)

        view.findViewById<View>(R.id.menu_bookmark).setOnClickListener {
            toggleBookmark(); sheet.dismiss()
        }
        view.findViewById<View>(R.id.menu_bookmarks_list).setOnClickListener {
            startActivity(Intent(this, BookmarksActivity::class.java)); sheet.dismiss()
        }
        view.findViewById<View>(R.id.menu_history).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java)); sheet.dismiss()
        }
        view.findViewById<View>(R.id.menu_downloads).setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java)); sheet.dismiss()
        }
        view.findViewById<View>(R.id.menu_share).setOnClickListener {
            shareCurrentUrl(); sheet.dismiss()
        }
        view.findViewById<View>(R.id.menu_find).setOnClickListener {
            showFindInPage(); sheet.dismiss()
        }
        view.findViewById<View>(R.id.menu_desktop_mode).setOnClickListener {
            Prefs.desktopMode = !Prefs.desktopMode
            currentWebView?.applySettings()
            currentWebView?.reload()
            sheet.dismiss()
        }
        view.findViewById<View>(R.id.menu_reader_mode).setOnClickListener {
            currentWebView?.injectReaderMode(); sheet.dismiss()
        }
        view.findViewById<View>(R.id.menu_incognito).setOnClickListener {
            newTab(incognito = true); sheet.dismiss()
        }
        view.findViewById<View>(R.id.menu_night_mode).setOnClickListener {
            Prefs.nightMode = !Prefs.nightMode
            currentWebView?.injectNightMode()
            sheet.dismiss()
        }
        view.findViewById<View>(R.id.menu_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java)); sheet.dismiss()
        }
        view.findViewById<View>(R.id.menu_refresh).setOnClickListener {
            currentWebView?.reload(); sheet.dismiss()
        }
        view.findViewById<View>(R.id.menu_print).setOnClickListener {
            printPage(); sheet.dismiss()
        }
        view.findViewById<View>(R.id.menu_text_size).setOnClickListener {
            showTextSizeDialog(); sheet.dismiss()
        }
        view.findViewById<View>(R.id.menu_clear_data).setOnClickListener {
            showClearDataDialog(); sheet.dismiss()
        }

        sheet.show()
    }

    // ── Feature actions ───────────────────────────────────────────────────────

    private fun toggleBookmark() {
        val url = currentWebView?.url ?: return
        val title = currentWebView?.title ?: url
        lifecycleScope.launch(Dispatchers.IO) {
            val isBookmarked = RustCore.isBookmarked(url)
            if (isBookmarked) {
                RustCore.removeBookmark(url)
                withContext(Dispatchers.Main) { toast("Bookmark removed") }
            } else {
                RustCore.addBookmark(title, url, "root")
                withContext(Dispatchers.Main) { toast("Bookmarked") }
            }
        }
    }

    private fun shareCurrentUrl() {
        val url = currentWebView?.url ?: return
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }, "Share via"
        ))
    }

    private fun showFindInPage() {
        binding.findBar.visibility = View.VISIBLE
        binding.findInput.requestFocus()
        binding.findNext.setOnClickListener {
            currentWebView?.findNext(true)
        }
        binding.findPrev.setOnClickListener {
            currentWebView?.findNext(false)
        }
        binding.findClose.setOnClickListener {
            binding.findBar.visibility = View.GONE
            currentWebView?.clearMatches()
        }
        binding.findInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentWebView?.findAllAsync(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun showTextSizeDialog() {
        val sizes = arrayOf("50%", "75%", "100%", "125%", "150%", "175%", "200%")
        val values = intArrayOf(50, 75, 100, 125, 150, 175, 200)
        AlertDialog.Builder(this)
            .setTitle("Text size")
            .setItems(sizes) { _, i ->
                Prefs.textZoom = values[i]
                currentWebView?.applySettings()
            }
            .show()
    }

    private fun showClearDataDialog() {
        val options = arrayOf("Cache", "Cookies", "History", "All")
        AlertDialog.Builder(this)
            .setTitle("Clear data")
            .setItems(options) { _, i ->
                when (i) {
                    0 -> currentWebView?.clearCache(true)
                    1 -> android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    2 -> lifecycleScope.launch(Dispatchers.IO) { RustCore.clearHistory() }
                    3 -> {
                        currentWebView?.clearCache(true)
                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                        lifecycleScope.launch(Dispatchers.IO) { RustCore.clearHistory() }
                    }
                }
                toast("Cleared")
            }
            .show()
    }

    private fun showNavigationHistory(back: Boolean) {
        val wv = currentWebView ?: return
        val list = wv.copyBackForwardList()
        val items = (0 until list.size).map { list.getItemAtIndex(it).url }
        AlertDialog.Builder(this)
            .setTitle(if (back) "Back history" else "Forward history")
            .setItems(items.toTypedArray()) { _, i ->
                val current = list.currentIndex
                val steps = i - current
                wv.goBackOrForward(steps)
            }
            .show()
    }

    private fun printPage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val pm = getSystemService(android.print.PrintManager::class.java)
            val jobName = "${currentWebView?.title ?: "LazyAria"} - Print"
            val printAdapter = currentWebView?.createPrintDocumentAdapter(jobName)
            printAdapter?.let { pm.print(jobName, it, null) }
        }
    }

    private fun setupGestures() {
        if (!Prefs.gesturesEnabled) return
        GestureHandler(this, binding.webViewContainer) { gesture ->
            when (gesture) {
                GestureHandler.Gesture.SWIPE_DOWN -> showMainMenu()
                GestureHandler.Gesture.SWIPE_UP -> { /* hide UI / fullscreen */ }
                else -> {}
            }
        }
    }

    // ── TabCallback ───────────────────────────────────────────────────────────

    override fun onPageStart(url: String, title: String) {
        runOnUiThread {
            binding.urlBar.setText(url)
            binding.progressBar.visibility = View.VISIBLE
            binding.progressBar.progress = 0
            tabManager.current?.isLoading = true
        }
    }

    override fun onPageFinish(url: String, title: String) {
        runOnUiThread {
            binding.tabTitle.text = title
            binding.urlBar.setText(url)
            binding.progressBar.visibility = View.GONE
            tabManager.current?.let { tab ->
                tab.url = url
                tab.title = title
                tab.isLoading = false
            }
        }
        if (!incognitoMode && url.isNotBlank() && url != "about:home") {
            lifecycleScope.launch(Dispatchers.IO) {
                RustCore.addHistory(title, url)
            }
        }
    }

    override fun onProgressChanged(progress: Int) {
        runOnUiThread {
            binding.progressBar.progress = progress
            if (progress == 100) binding.progressBar.visibility = View.GONE
        }
    }

    override fun onReceivedIcon(icon: Bitmap?) {
        tabManager.current?.favicon = icon
    }

    override fun onCreateNewTab(url: String) {
        runOnUiThread { newTab(url) }
    }

    override fun onDownloadRequest(url: String, mime: String?, contentDisposition: String?) {
        LazyDownloader.enqueue(this, url, mime, contentDisposition)
        toast("Download started")
    }

    // ── Back button ───────────────────────────────────────────────────────────

    override fun onBackPressed() {
        when {
            binding.findBar.visibility == View.VISIBLE -> {
                binding.findBar.visibility = View.GONE
                currentWebView?.clearMatches()
            }
            currentWebView?.canGoBack() == true -> currentWebView?.goBack()
            tabManager.count > 1 -> closeTab(tabManager.currentIndex)
            else -> super.onBackPressed()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.urlBar.windowToken, 0)
        binding.urlBar.clearFocus()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        webViews.values.forEach { it.destroy() }
        super.onDestroy()
    }
}
