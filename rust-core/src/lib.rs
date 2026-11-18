// LazyAria Rust Core — JNI bridge
// Targets: aarch64-linux-android, armv7-linux-androideabi, x86_64-linux-android
// Provides: URL ops, ad-block (Bloom + exact), redirect block + allowlist, SQLite bookmark/history

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jboolean, jint, JNI_TRUE, JNI_FALSE};
use once_cell::sync::Lazy;
use rusqlite::{Connection, params};
use serde_json;
use std::collections::HashSet;
use std::sync::{Mutex, RwLock};
use url::Url;
use bloomfilter::Bloom;

// ══════════════════════════════════════════════════════════════════════════════
// Globals
// ══════════════════════════════════════════════════════════════════════════════

static DB: Lazy<Mutex<Option<Connection>>> = Lazy::new(|| Mutex::new(None));

/// Two-stage ad-block:
///   1. Bloom filter  — probabilistic, O(1), zero allocations on miss
///   2. Exact HashSet — confirms positives, eliminates false positives
struct BlockEngine {
    bloom: Option<Bloom<String>>,
    exact: HashSet<String>,
    /// Redirect rules: blocked destination hosts
    redirect_blocked: HashSet<String>,
    /// Redirect allowlist: destination hosts explicitly allowed despite rules
    redirect_allowed: HashSet<String>,
}

impl BlockEngine {
    fn new() -> Self {
        Self {
            bloom: None,
            exact: HashSet::new(),
            redirect_blocked: HashSet::new(),
            redirect_allowed: HashSet::new(),
        }
    }

    /// Load hosts-format content. Returns (rule_count, bloom_size_bytes).
    fn load_hosts(&mut self, content: &str) -> (usize, usize) {
        self.exact.clear();

        let hosts: Vec<String> = content
            .lines()
            .filter_map(|line| {
                let line = line.trim();
                if line.is_empty() || line.starts_with('#') { return None; }
                // hosts format: "0.0.0.0 ads.example.com" or bare "ads.example.com"
                let host = line.split_whitespace().nth(1).unwrap_or(line);
                if host.is_empty() || host == "localhost" || host == "0.0.0.0" || host == "127.0.0.1" {
                    return None;
                }
                Some(host.to_lowercase())
            })
            .collect();

        let count = hosts.len();

        // Bloom: 1% FPR, bitmap size scales with rule count
        // ~1.2MB for 100k rules, ~6MB for 500k rules
        let bloom_items = (count + 1000).max(10_000);
        let mut bloom = Bloom::new_for_fp_rate(bloom_items, 0.01);
        for h in &hosts {
            bloom.set(h);
            self.exact.insert(h.clone());
        }
        let bloom_bytes = bloom.number_of_bits() / 8;
        self.bloom = Some(bloom);

        (count, bloom_bytes as usize)
    }

    /// O(1) average — Bloom first, exact only on potential hit
    #[inline]
    fn should_block_host(&self, host: &str) -> bool {
        let bloom = match &self.bloom {
            Some(b) => b,
            None => return false,
        };
        // Check exact host, then walk up subdomain chain
        // e.g. sub.ads.example.com → ads.example.com → example.com
        let mut candidate = host.to_string();
        loop {
            if bloom.check(&candidate) && self.exact.contains(&candidate) {
                return true;
            }
            // Walk up: strip leftmost label
            if let Some(dot) = candidate.find('.') {
                let parent = &candidate[dot + 1..];
                if parent.contains('.') {
                    candidate = parent.to_string();
                } else {
                    break;
                }
            } else {
                break;
            }
        }
        false
    }

    fn load_redirect_rules(&mut self, json: &str) {
        // JSON: { "blocked": ["host1", ...], "allowed": ["host2", ...] }
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(json) {
            self.redirect_blocked.clear();
            self.redirect_allowed.clear();
            if let Some(arr) = v["blocked"].as_array() {
                for h in arr {
                    if let Some(s) = h.as_str() {
                        self.redirect_blocked.insert(s.to_lowercase());
                    }
                }
            }
            if let Some(arr) = v["allowed"].as_array() {
                for h in arr {
                    if let Some(s) = h.as_str() {
                        self.redirect_allowed.insert(s.to_lowercase());
                    }
                }
            }
        }
    }

    fn should_block_redirect(&self, destination_url: &str) -> bool {
        let host = extract_host(destination_url);
        if host.is_empty() { return false; }
        // Allowlist wins
        if self.redirect_allowed.contains(&host) { return false; }
        // Check redirect block list first
        if self.redirect_blocked.contains(&host) { return true; }
        // Also run ad-block engine on redirect destination
        self.should_block_host(&host)
    }

    fn add_redirect_rule(&mut self, host: &str, block: bool) {
        let h = host.to_lowercase();
        if block {
            self.redirect_allowed.remove(&h);
            self.redirect_blocked.insert(h);
        } else {
            self.redirect_blocked.remove(&h);
            self.redirect_allowed.insert(h);
        }
    }

    fn remove_redirect_rule(&mut self, host: &str) {
        let h = host.to_lowercase();
        self.redirect_blocked.remove(&h);
        self.redirect_allowed.remove(&h);
    }

    fn get_redirect_rules_json(&self) -> String {
        let blocked: Vec<&str> = self.redirect_blocked.iter().map(|s| s.as_str()).collect();
        let allowed: Vec<&str> = self.redirect_allowed.iter().map(|s| s.as_str()).collect();
        serde_json::json!({ "blocked": blocked, "allowed": allowed }).to_string()
    }
}

static ENGINE: Lazy<RwLock<BlockEngine>> = Lazy::new(|| RwLock::new(BlockEngine::new()));

// ══════════════════════════════════════════════════════════════════════════════
// DB init
// ══════════════════════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn Java_aria_sparkynox_lazyaria_core_RustCore_initDb(
    mut env: JNIEnv, _class: JClass, db_path: JString,
) {
    let path: String = env.get_string(&db_path).unwrap().into();
    let conn = Connection::open(&path).expect("DB open failed");
    conn.execute_batch("
        PRAGMA journal_mode=WAL;
        PRAGMA synchronous=NORMAL;
        CREATE TABLE IF NOT EXISTS bookmarks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL, url TEXT NOT NULL UNIQUE,
            created_at INTEGER NOT NULL, folder TEXT DEFAULT 'root'
        );
        CREATE TABLE IF NOT EXISTS history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL, url TEXT NOT NULL,
            visited_at INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_history_url  ON history(url);
        CREATE INDEX IF NOT EXISTS idx_history_time ON history(visited_at DESC);
        CREATE TABLE IF NOT EXISTS redirect_rules (
            host TEXT PRIMARY KEY,
            blocked INTEGER NOT NULL DEFAULT 1
        );
        CREATE TABLE IF NOT EXISTS blocklist_meta (
            key TEXT PRIMARY KEY, value TEXT
        );
    ").expect("Schema init failed");

    // Restore persisted redirect rules into ENGINE
    {
        let mut rows_blocked: Vec<String> = Vec::new();
        let mut rows_allowed: Vec<String> = Vec::new();
        {
            let mut stmt = conn.prepare(
                "SELECT host, blocked FROM redirect_rules"
            ).unwrap();
            let _ = stmt.query_map([], |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?))
            }).unwrap().filter_map(|r| r.ok()).for_each(|(host, blocked)| {
                if blocked == 1 { rows_blocked.push(host); }
                else { rows_allowed.push(host); }
            });
        }
        let json = serde_json::json!({
            "blocked": rows_blocked,
            "allowed": rows_allowed
        }).to_string();
        ENGINE.write().unwrap().load_redirect_rules(&json);
    }

    *DB.lock().unwrap() = Some(conn);
}

// ══════════════════════════════════════════════════════════════════════════════
// URL normalization
// ══════════════════════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn Java_aria_sparkynox_lazyaria_core_RustCore_normalizeUrl(
    mut env: JNIEnv, _class: JClass, input: JString,
) -> jstring {
    let raw: String = env.get_string(&input).unwrap().into();
    let normalized = normalize_url(&raw);
    env.new_string(normalized).unwrap().into_raw()
}

fn normalize_url(raw: &str) -> String {
    let trimmed = raw.trim();
    if let Ok(u) = Url::parse(trimmed) {
        if matches!(u.scheme(), "http" | "https" | "file" | "data") {
            return u.to_string();
        }
    }
    if !trimmed.contains(' ') && trimmed.contains('.') {
        return format!("https://{}", trimmed);
    }
    format!("https://duckduckgo.com/?q={}", url_encode(trimmed))
}

fn url_encode(s: &str) -> String {
    s.chars().map(|c| match c {
        ' ' => "+".to_string(),
        c if c.is_alphanumeric() || "-_.~".contains(c) => c.to_string(),
        c => format!("%{:02X}", c as u32),
    }).collect()
}

// ══════════════════════════════════════════════════════════════════════════════
// Ad-block
// ══════════════════════════════════════════════════════════════════════════════

/// Load a hosts-format blocklist into the Bloom+exact engine.
/// Returns JSON: { "rules": N, "bloom_kb": N }
#[no_mangle]
pub extern "C" fn Java_aria_sparkynox_lazyaria_core_RustCore_loadBlocklist(
    mut env: JNIEnv, _class: JClass, hosts_content: JString,
) -> jstring {
    let content: String = env.get_string(&hosts_content).unwrap().into();
    let (rules, bloom_bytes) = ENGINE.write().unwrap().load_hosts(&content);
    let result = serde_json::json!({
        "rules": rules,
        "bloom_kb": bloom_bytes / 1024
    }).to_string();
    env.new_string(result).unwrap().into_raw()
}

/// Hot-reload: same as loadBlocklist but also persists last-updated timestamp.
#[no_mangle]
pub extern "C" fn Java_aria_sparkynox_lazyaria_core_RustCore_reloadBlocklist(
    mut env: JNIEnv, _class: JClass, hosts_content: JString, source_url: JString,
) -> jstring {
    let content: String = env.get_string(&hosts_content).unwrap().into();
    let source: String = env.get_string(&source_url).unwrap().into();
    let (rules, bloom_bytes) = ENGINE.write().unwrap().load_hosts(&content);
    let now = unix_now();

    // Persist metadata
    {
        let lock = DB.lock().unwrap();
        if let Some(conn) = lock.as_ref() {
            let _ = conn.execute(
                "INSERT OR REPLACE INTO blocklist_meta (key, value) VALUES ('last_updated', ?1)",
                params![now.to_string()],
            );
            let _ = conn.execute(
                "INSERT OR REPLACE INTO blocklist_meta (key, value) VALUES ('source_url', ?1)",
                params![source],
            );
            let _ = conn.execute(
                "INSERT OR REPLACE INTO blocklist_meta (key, value) VALUES ('rule_count', ?1)",
                params![rules.to_string()],
            );
        }
    }

    let result = serde_json::json!({
        "rules": rules,
        "bloom_kb": bloom_bytes / 1024,
        "updated_at": now
    }).to_string();
    env.new_string(result).unwrap().into_raw()
}

/// Returns JSON: { "last_updated": N, "source_url": "...", "rule_count": N }
#[no_mangle]
pub extern "C" fn Java_aria_sparkynox_lazyaria_core_RustCore_getBlocklistMeta(
    mut env: JNIEnv, _class: JClass,
) -> jstring {
    let lock = DB.lock().unwrap();
    let json = if let Some(conn) = lock.as_ref() {
        let mut map = serde_json::Map::new();
        let mut stmt = conn.prepare("SELECT key, value FROM blocklist_meta").unwrap();
        let _ = stmt.query_map([], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
        }).unwrap().filter_map(|r| r.ok()).for_each(|(k, v)| {
            map.insert(k, serde_json::Value::String(v));
        });
        serde_json::Value::Object(map).to_string()
    } else {
        "{}".to_string()
    };
    env.new_string(json).unwrap().into_raw()
}

#[no_mangle]
pub extern "C" fn Java_aria_sparkynox_lazyaria_core_RustCore_shouldBlock(
    mut env: JNIEnv, _class: JClass, url_str: JString,
) -> jboolean {
    let url_raw: String = env.get_string(&url_str).unwrap().into();
    let host = extract_host(&url_raw);
    if host.is_empty() { return JNI_FALSE; }
    ENGINE.read().unwrap().should_block_host(&host) as u8
}

// ══════════════════════════════════════════════════════════════════════════════
// Redirect block + allowlist
// ══════════════════════════════════════════════════════════════════════════════

/// Check if a redirect destination should be blocked.
/// Call from WebViewClient.shouldOverrideUrlLoading for 3xx / JS redirects.
#[no_mangle]
pub extern "C" fn Java_aria_sparkynox_lazyaria_core_RustCore_shouldBlockRedirect(
    mut env: JNIEnv, _class: JClass, destination_url: JString,
) -> jboolean {
    let url: String = env.get_string(&destination_url).unwrap().into();
    ENGINE.read().unwrap().should_block_redirect(&url) as u8
}

/// Add or update a redirect rule.
/// block=true → block this host; block=false → allowlist it
#[no_mangle]
pub extern "C" fn Java_aria_sparkynox_lazyaria_core_RustCore_setRedirectRule(
    mut env: JNIEnv, _class: JClass, host: JString, block: jboolean,
) {
    let host: String = env.get_string(&host).unwrap().into();
    let block_bool = block == JNI_TRUE;
    ENGINE.write().unwrap().add_redirect_rule(&host, block_bool);

    // Persist to DB
    let lock = DB.lock().unwrap();
    if let Some(conn) = lock.as_ref() {
        let _ = conn.execute(
            "INSERT OR REPLACE INTO redirect_rules (host, blocked) VALUES (?1, ?2)",
            params![host.to_lowercase(), if block_bool { 1i64 } else { 0i64 }],
        );
    }
}

/// Remove a redirect rule (neither blocked nor allowed — back to default).
#[no_mangle]
pub extern "C" fn Java_aria_sparkynox_lazyaria_core_RustCore_removeRedirectRule(
    mut env: JNIEnv, _class: JClass, host: JString,
) {
    let host: String = env.get_string(&host).unwrap().into();
    ENGINE.write().unwrap().remove_redirect_rule(&host);
    let lock = DB.lock().unwrap();
    if let Some(conn) = lock.as_ref() {
        let _ = conn.execute(
            "DELETE FROM redirect_rules WHERE host = ?1",
            params![host.to_lowercase()],
        );
    }
}

/// Returns JSON: { "blocked": [...], "allowed": [...] }
#[no_mangle]
pub extern "C" fn Java_aria_sparkynox_lazyaria_core_RustCore_getRedirectRulesJson(
    mut env: JNIEnv, _class: JClass,
) -> jstring {
    let json = ENGINE.read().unwrap().get_redirect_rules_json();
    env.new_string(json).unwrap().into_raw()
}

// ══════════════════════════════════════════════════════════════════════════════
// Bookmarks
// ══════════════════════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn Java_aria_sparkynox_lazyaria_core_RustCore_addBookmark(
    mut env: JNIEnv, _class: JClass, title: JString, url: JString, folder: JString,
) -> jboolean {
    let title: String = env.get_string(&title).unwrap().into();
    let url: String   = env.get_string(&url).unwrap().into();
    let folder: String = env.get_string(&folder).unwrap().into();
    let now = unix_now();
    let lock = DB.lock().unwrap();
    if let Some(conn) = lock.as_ref() {
        conn.execute(
            "INSERT OR REPLACE INTO bookmarks (title,url,created_at,folder) VALUES(?1,?2,?3,?4)",
            params![title, url, now, folder],
        ).is_ok() as u8
    } else { JNI_FALSE }
}

#[no_mangle]
pub extern "C" fn Java_aria_sparkynox_lazyaria_core_RustCore_removeBookmark(
    mut env: JNIEnv, _class: JClass, url: JString,
) -> jboolean {
    let url: String = env.get_string(&url).unwrap().into();
    let lock = DB.lock().unwrap();
    if let Some(conn) = lock.as_ref() {
        conn.execute("DELETE FROM bookmarks WHERE url=?1", params![url]).is_ok() as u8
    } else { JNI_FALSE }
}

#[no_mangle]
pub extern "C" fn Java_aria_sparkynox_lazyaria_core_RustCore_isBookmarked(
    mut env: JNIEnv, _class: JClass, url: JString,
) -> jboolean {
    let url: String = env.get_string(&url).unwrap().into();
    let lock = DB.lock().unwrap();
    if let Some(conn) = lock.as_ref() {
        let count: i64 = conn.query_row(
            "SELECT COUNT(*) FROM bookmarks WHERE url=?1", params![url], |r| r.get(0)
        ).unwrap_or(0);
        (count > 0) as u8
    } else { JNI_FALSE }
}

#[no_mangle]
pub extern "C" fn Java_aria_sparkynox_lazyaria_core_RustCore_getBookmarksJson(
    mut env: JNIEnv, _class: JClass, folder: JString,
) -> jstring {
    let folder: String = env.get_string(&folder).unwrap().into();
    let lock = DB.lock().unwrap();
    let json = if let Some(conn) = lock.as_ref() {
        let mut stmt = conn.prepare(
            "SELECT id,title,url,created_at,folder FROM bookmarks WHERE folder=?1 ORDER BY created_at DESC"
        ).unwrap();
        let rows: Vec<serde_json::Value> = stmt.query_map(params![folder], |r| {
            Ok(serde_json::json!({
                "id": r.get::<_,i64>(0)?,
                "title": r.get::<_,String>(1)?,
                "url": r.get::<_,String>(2)?,
                "created_at": r.get::<_,i64>(3)?,
                "folder": r.get::<_,String>(4)?
            }))
        }).unwrap().filter_map(|r| r.ok()).collect();
        serde_json::to_string(&rows).unwrap_or_else(|_| "[]".into())
    } else { "[]".into() };
    env.new_string(json).unwrap().into_raw()
}

// ══════════════════════════════════════════════════════════════════════════════
// History
// ══════════════════════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn Java_aria_sparkynox_lazyaria_core_RustCore_addHistory(
    mut env: JNIEnv, _class: JClass, title: JString, url: JString,
) {
    let title: String = env.get_string(&title).unwrap().into();
    let url: String   = env.get_string(&url).unwrap().into();
    let now = unix_now();
    let lock = DB.lock().unwrap();
    if let Some(conn) = lock.as_ref() {
        let _ = conn.execute(
            "INSERT INTO history (title,url,visited_at) VALUES(?1,?2,?3)",
            params![title, url, now],
        );
        let _ = conn.execute(
            "DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY visited_at DESC LIMIT 10000)",
            [],
        );
    }
}

#[no_mangle]
pub extern "C" fn Java_aria_sparkynox_lazyaria_core_RustCore_getHistoryJson(
    mut env: JNIEnv, _class: JClass, limit: jint, offset: jint,
) -> jstring {
    let lock = DB.lock().unwrap();
    let json = if let Some(conn) = lock.as_ref() {
        let mut stmt = conn.prepare(
            "SELECT id,title,url,visited_at FROM history ORDER BY visited_at DESC LIMIT ?1 OFFSET ?2"
        ).unwrap();
        let rows: Vec<serde_json::Value> = stmt.query_map(params![limit, offset], |r| {
            Ok(serde_json::json!({
                "id": r.get::<_,i64>(0)?,
                "title": r.get::<_,String>(1)?,
                "url": r.get::<_,String>(2)?,
                "visited_at": r.get::<_,i64>(3)?
            }))
        }).unwrap().filter_map(|r| r.ok()).collect();
        serde_json::to_string(&rows).unwrap_or_else(|_| "[]".into())
    } else { "[]".into() };
    env.new_string(json).unwrap().into_raw()
}

#[no_mangle]
pub extern "C" fn Java_aria_sparkynox_lazyaria_core_RustCore_clearHistory(
    _env: JNIEnv, _class: JClass,
) {
    let lock = DB.lock().unwrap();
    if let Some(conn) = lock.as_ref() {
        let _ = conn.execute("DELETE FROM history", []);
    }
}

#[no_mangle]
pub extern "C" fn Java_aria_sparkynox_lazyaria_core_RustCore_searchHistory(
    mut env: JNIEnv, _class: JClass, query: JString,
) -> jstring {
    let q: String = env.get_string(&query).unwrap().into();
    let pattern = format!("%{}%", q);
    let lock = DB.lock().unwrap();
    let json = if let Some(conn) = lock.as_ref() {
        let mut stmt = conn.prepare(
            "SELECT DISTINCT url,title,MAX(visited_at) t FROM history WHERE url LIKE ?1 OR title LIKE ?1 GROUP BY url ORDER BY t DESC LIMIT 20"
        ).unwrap();
        let rows: Vec<serde_json::Value> = stmt.query_map(params![pattern], |r| {
            Ok(serde_json::json!({
                "url": r.get::<_,String>(0)?,
                "title": r.get::<_,String>(1)?,
                "visited_at": r.get::<_,i64>(2)?
            }))
        }).unwrap().filter_map(|r| r.ok()).collect();
        serde_json::to_string(&rows).unwrap_or_else(|_| "[]".into())
    } else { "[]".into() };
    env.new_string(json).unwrap().into_raw()
}

// ══════════════════════════════════════════════════════════════════════════════
// Utility
// ══════════════════════════════════════════════════════════════════════════════

fn extract_host(url_str: &str) -> String {
    Url::parse(url_str)
        .ok()
        .and_then(|u| u.host_str().map(|h| h.to_lowercase()))
        .unwrap_or_default()
}

fn unix_now() -> i64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs() as i64
}
