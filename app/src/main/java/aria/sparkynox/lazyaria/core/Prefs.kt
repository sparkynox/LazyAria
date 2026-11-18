package aria.sparkynox.lazyaria.core

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

object Prefs {

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
    }

    // ── Search ────────────────────────────────────────────────────────────────
    var searchEngine: String
        get() = prefs.getString("search_engine", "https://duckduckgo.com/?q=") ?: "https://duckduckgo.com/?q="
        set(v) = prefs.edit().putString("search_engine", v).apply()

    // ── Homepage ──────────────────────────────────────────────────────────────
    var homepage: String
        get() = prefs.getString("homepage", "about:home") ?: "about:home"
        set(v) = prefs.edit().putString("homepage", v).apply()

    // ── User-Agent ────────────────────────────────────────────────────────────
    var userAgent: String
        get() = prefs.getString("user_agent", "default") ?: "default"
        set(v) = prefs.edit().putString("user_agent", v).apply()

    var desktopMode: Boolean
        get() = prefs.getBoolean("desktop_mode", false)
        set(v) = prefs.edit().putBoolean("desktop_mode", v).apply()

    // ── Privacy ───────────────────────────────────────────────────────────────
    var adBlockEnabled: Boolean
        get() = prefs.getBoolean("ad_block", true)
        set(v) = prefs.edit().putBoolean("ad_block", v).apply()

    var javascriptEnabled: Boolean
        get() = prefs.getBoolean("javascript", true)
        set(v) = prefs.edit().putBoolean("javascript", v).apply()

    var webRtcEnabled: Boolean
        get() = prefs.getBoolean("webrtc", false)
        set(v) = prefs.edit().putBoolean("webrtc", v).apply()

    var saveCookies: Boolean
        get() = prefs.getBoolean("save_cookies", true)
        set(v) = prefs.edit().putBoolean("save_cookies", v).apply()

    var dntEnabled: Boolean
        get() = prefs.getBoolean("dnt", true)
        set(v) = prefs.edit().putBoolean("dnt", v).apply()

    // ── Reader mode ───────────────────────────────────────────────────────────
    var readerFontSize: Int
        get() = prefs.getInt("reader_font_size", 16)
        set(v) = prefs.edit().putInt("reader_font_size", v).apply()

    var readerFont: String
        get() = prefs.getString("reader_font", "serif") ?: "serif"
        set(v) = prefs.edit().putString("reader_font", v).apply()

    // ── Display ───────────────────────────────────────────────────────────────
    var nightMode: Boolean
        get() = prefs.getBoolean("night_mode", false)
        set(v) = prefs.edit().putBoolean("night_mode", v).apply()

    var textZoom: Int
        get() = prefs.getInt("text_zoom", 100)
        set(v) = prefs.edit().putInt("text_zoom", v).apply()

    var forceZoom: Boolean
        get() = prefs.getBoolean("force_zoom", false)
        set(v) = prefs.edit().putBoolean("force_zoom", v).apply()

    // ── Downloads ─────────────────────────────────────────────────────────────
    var downloadPath: String
        get() = prefs.getString("download_path", "") ?: ""
        set(v) = prefs.edit().putString("download_path", v).apply()

    // ── DNS ───────────────────────────────────────────────────────────────────
    var dohUrl: String
        get() = prefs.getString("doh_url", "") ?: ""
        set(v) = prefs.edit().putString("doh_url", v).apply()

    // ── Gestures ──────────────────────────────────────────────────────────────
    var gesturesEnabled: Boolean
        get() = prefs.getBoolean("gestures", true)
        set(v) = prefs.edit().putBoolean("gestures", v).apply()

    // ── Custom CSS/JS ─────────────────────────────────────────────────────────
    var customCss: String
        get() = prefs.getString("custom_css", "") ?: ""
        set(v) = prefs.edit().putString("custom_css", v).apply()

    var customJs: String
        get() = prefs.getString("custom_js", "") ?: ""
        set(v) = prefs.edit().putString("custom_js", v).apply()
}
