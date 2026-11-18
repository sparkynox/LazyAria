package aria.sparkynox.lazyaria.browser

import android.graphics.Bitmap

data class BrowserTab(
    val id: Int,
    var url: String = "",
    var title: String = "New Tab",
    var favicon: Bitmap? = null,
    var progress: Int = 0,
    var isIncognito: Boolean = false,
    var isLoading: Boolean = false,
)

class TabManager {

    private val _tabs = mutableListOf<BrowserTab>()
    val tabs: List<BrowserTab> get() = _tabs

    private var _currentIndex = 0
    val currentIndex get() = _currentIndex

    val current: BrowserTab? get() = _tabs.getOrNull(_currentIndex)
    val count get() = _tabs.size

    private var nextId = 1

    fun newTab(url: String = "", incognito: Boolean = false): BrowserTab {
        val tab = BrowserTab(id = nextId++, url = url, isIncognito = incognito)
        _tabs.add(tab)
        _currentIndex = _tabs.lastIndex
        return tab
    }

    fun closeTab(index: Int) {
        if (_tabs.size <= 1) return // keep at least one
        _tabs.removeAt(index)
        _currentIndex = (_currentIndex).coerceIn(0, _tabs.lastIndex)
    }

    fun selectTab(index: Int) {
        _currentIndex = index.coerceIn(0, _tabs.lastIndex)
    }

    fun updateTab(id: Int, block: BrowserTab.() -> Unit) {
        _tabs.firstOrNull { it.id == id }?.block()
    }
}
