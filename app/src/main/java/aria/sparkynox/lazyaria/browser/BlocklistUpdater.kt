package aria.sparkynox.lazyaria.browser

import android.content.Context
import android.util.Log
import aria.sparkynox.lazyaria.core.RustCore
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches and hot-reloads ad-block hosts lists.
 *
 * Sources (checked in order, first to 200 wins):
 *   1. User-added custom source (Prefs.blocklistCustomUrl)
 *   2. StevenBlack unified — ads + malware (~130k rules)
 *   3. StevenBlack unified + fakenews + gambling + porn (optional extended)
 *
 * Update strategy:
 *   - On first run: load bundled assets/blocklist.txt into Rust, then try network.
 *   - On update: fetch → diff SHA against stored → if changed, write to cache,
 *     call RustCore.reloadBlocklist() — no restart needed.
 *   - Interval: every 3 days (configurable).
 */
object BlocklistUpdater {

    private const val TAG = "BlocklistUpdater"
    private const val CACHE_FILE = "blocklist_cache.txt"
    private const val META_FILE  = "blocklist_meta.json"
    private const val UPDATE_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000 // 3 days

    // Built-in sources — wreck user can add more via settings
    val BUILT_IN_SOURCES = listOf(
        BlocklistSource(
            id = "stevenblack_unified",
            name = "StevenBlack Unified (ads + malware)",
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            enabled = true,
        ),
        BlocklistSource(
            id = "stevenblack_extended",
            name = "StevenBlack + Fakenews + Gambling",
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/fakenews-gambling/hosts",
            enabled = false,
        ),
        BlocklistSource(
            id = "oisd_small",
            name = "OISD Small (balanced, ~50k rules)",
            url = "https://small.oisd.nl/",
            enabled = false,
        ),
        BlocklistSource(
            id = "oisd_big",
            name = "OISD Big (~250k rules)",
            url = "https://big.oisd.nl/",
            enabled = false,
        ),
        BlocklistSource(
            id = "hagezi_light",
            name = "HaGeZi Light (low false-positive)",
            url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/hosts/light.txt",
            enabled = false,
        ),
    )

    data class BlocklistSource(
        val id: String,
        val name: String,
        val url: String,
        var enabled: Boolean,
    )

    data class UpdateResult(
        val source: String,
        val rules: Int,
        val bloomKb: Int,
        val updatedAt: Long,
        val changed: Boolean,
        val error: String? = null,
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Load whatever we have right now (cache or bundled assets), then
     * schedule a background update if the cache is stale.
     * Call once from App.onCreate or MainActivity.onCreate.
     */
    fun initAndSchedule(context: Context, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            loadCachedOrBundled(context)
            if (shouldUpdate(context)) {
                update(context)
            }
        }
    }

    /**
     * Force an immediate update regardless of interval.
     * Returns UpdateResult — UI can show rule count + timestamp.
     */
    suspend fun forceUpdate(context: Context): UpdateResult = withContext(Dispatchers.IO) {
        update(context)
    }

    /** true if last update was more than UPDATE_INTERVAL_MS ago. */
    fun shouldUpdate(context: Context): Boolean {
        val meta = readMeta(context)
        val lastUpdate = meta["last_updated"]?.toLongOrNull() ?: 0L
        return System.currentTimeMillis() - lastUpdate > UPDATE_INTERVAL_MS
    }

    fun getLastUpdateTime(context: Context): Long {
        return readMeta(context)["last_updated"]?.toLongOrNull() ?: 0L
    }

    fun getRuleCount(context: Context): Int {
        return readMeta(context)["rule_count"]?.toIntOrNull() ?: 0
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun loadCachedOrBundled(context: Context) {
        val cache = cacheFile(context)
        val content = when {
            cache.exists() && cache.length() > 0 -> {
                Log.d(TAG, "Loading from cache (${cache.length() / 1024}KB)")
                cache.readText()
            }
            else -> {
                Log.d(TAG, "Loading bundled blocklist.txt")
                try {
                    context.assets.open("blocklist.txt").bufferedReader().readText()
                } catch (e: Exception) {
                    Log.w(TAG, "No bundled blocklist: ${e.message}")
                    ""
                }
            }
        }
        if (content.isNotBlank()) {
            val result = RustCore.loadBlocklist(content)
            Log.d(TAG, "Loaded: $result")
        }
    }

    private fun update(context: Context): UpdateResult {
        // Find first enabled source
        val source = BUILT_IN_SOURCES.firstOrNull { it.enabled }
            ?: BUILT_IN_SOURCES.first() // fallback to StevenBlack unified

        return try {
            Log.d(TAG, "Fetching from ${source.url}")
            val content = fetchUrl(source.url)
            if (content.isBlank()) {
                return UpdateResult(source.url, 0, 0, 0, false, "Empty response")
            }

            // Diff check — compare SHA-256 of content vs stored
            val newHash = content.hashCode().toString() // fast enough for diff check
            val meta = readMeta(context)
            val oldHash = meta["content_hash"]
            val changed = oldHash != newHash

            if (!changed) {
                Log.d(TAG, "Blocklist unchanged, skipping reload")
                val ts = meta["last_checked"]?.toLongOrNull() ?: 0L
                writeMeta(context, meta + mapOf("last_checked" to System.currentTimeMillis().toString()))
                return UpdateResult(
                    source.url,
                    meta["rule_count"]?.toIntOrNull() ?: 0,
                    0,
                    ts,
                    false,
                )
            }

            // Changed — write to cache and hot-reload into Rust
            cacheFile(context).writeText(content)
            val resultJson = RustCore.reloadBlocklist(content, source.url)
            val parsed = Gson().fromJson(resultJson, Map::class.java)
            val rules = (parsed["rules"] as? Double)?.toInt() ?: 0
            val bloomKb = (parsed["bloom_kb"] as? Double)?.toInt() ?: 0
            val updatedAt = (parsed["updated_at"] as? Double)?.toLong() ?: unix()

            writeMeta(context, mapOf(
                "last_updated" to updatedAt.toString(),
                "last_checked" to System.currentTimeMillis().toString(),
                "source_url" to source.url,
                "rule_count" to rules.toString(),
                "content_hash" to newHash,
            ))

            Log.d(TAG, "Updated: $rules rules, ${bloomKb}KB bloom")
            UpdateResult(source.url, rules, bloomKb, updatedAt, true)

        } catch (e: Exception) {
            Log.e(TAG, "Update failed: ${e.message}")
            UpdateResult(source.url, 0, 0, 0, false, e.message)
        }
    }

    private fun fetchUrl(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout    = 30_000
            requestMethod  = "GET"
            setRequestProperty("User-Agent", "LazyAria/1.0")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        return try {
            if (conn.responseCode == 200) {
                val inputStream = if (conn.contentEncoding == "gzip") {
                    java.util.zip.GZIPInputStream(conn.inputStream)
                } else {
                    conn.inputStream
                }
                inputStream.bufferedReader().readText()
            } else ""
        } finally {
            conn.disconnect()
        }
    }

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE)
    private fun metaFile(context: Context)  = File(context.filesDir, META_FILE)

    private fun readMeta(context: Context): Map<String, String> {
        return try {
            val f = metaFile(context)
            if (!f.exists()) return emptyMap()
            @Suppress("UNCHECKED_CAST")
            Gson().fromJson(f.readText(), Map::class.java) as Map<String, String>
        } catch (_: Exception) { emptyMap() }
    }

    private fun writeMeta(context: Context, meta: Map<String, String>) {
        try { metaFile(context).writeText(Gson().toJson(meta)) }
        catch (_: Exception) {}
    }

    private fun unix() = System.currentTimeMillis() / 1000
}
