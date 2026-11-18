package aria.sparkynox.lazyaria.core

/**
 * JNI bridge to Rust core (liblazyaria_core.so)
 * All calls are synchronous — invoke from IO coroutine where needed.
 */
object RustCore {

    init {
        System.loadLibrary("lazyaria_core")
    }

    // ── Init ──────────────────────────────────────────────────────────────────
    external fun initDb(dbPath: String)

    // ── URL ───────────────────────────────────────────────────────────────────
    external fun normalizeUrl(input: String): String

    // ── Ad-block ──────────────────────────────────────────────────────────────
    /** Load hosts-file content. Returns JSON: { "rules": N, "bloom_kb": N } */
    external fun loadBlocklist(hostsContent: String): String

    /** Hot-reload from updater. Persists metadata. Returns same JSON + updated_at. */
    external fun reloadBlocklist(hostsContent: String, sourceUrl: String): String

    /** Returns JSON: { "last_updated": N, "source_url": "...", "rule_count": N } */
    external fun getBlocklistMeta(): String

    /** true if URL's host matches the blocklist (Bloom + exact). */
    external fun shouldBlock(url: String): Boolean

    // ── Redirect block ────────────────────────────────────────────────────────
    /** true if the redirect destination should be blocked. */
    external fun shouldBlockRedirect(destinationUrl: String): Boolean

    /** Add/update a rule. block=true → block; block=false → allowlist. */
    external fun setRedirectRule(host: String, block: Boolean)

    /** Remove rule entirely — host goes back to default behavior. */
    external fun removeRedirectRule(host: String)

    /** Returns JSON: { "blocked": [...], "allowed": [...] } */
    external fun getRedirectRulesJson(): String

    // ── Bookmarks ─────────────────────────────────────────────────────────────
    external fun addBookmark(title: String, url: String, folder: String): Boolean
    external fun removeBookmark(url: String): Boolean
    external fun isBookmarked(url: String): Boolean
    external fun getBookmarksJson(folder: String): String

    // ── History ───────────────────────────────────────────────────────────────
    external fun addHistory(title: String, url: String)
    external fun getHistoryJson(limit: Int, offset: Int): String
    external fun clearHistory()
    external fun searchHistory(query: String): String
}
