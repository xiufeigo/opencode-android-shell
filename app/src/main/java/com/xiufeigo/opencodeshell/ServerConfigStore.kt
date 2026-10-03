package com.xiufeigo.opencodeshell

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Native mirror of the WebView's server config. The WebView stays the single
 * source of truth: the JS side pushes the raw localStorage value of
 * `opencode.global.dat:server` through
 * [OpenCodeAndroidBridge.syncServerConfig] after page load, and the shell only
 * caches it here so [OpenCodeClient] can talk to the server directly (Basic
 * auth included) without the loopback gateway hop.
 *
 * Wire schema (upstream `server` store):
 * `{"list":[ "http://host:port" | {"type":"http","http":{"url","username?","password?"}} ...],
 *   "active":"<url key>"}`
 * Legacy top-level `{url, username, password}` objects are tolerated too.
 *
 * `username?` above is Android-only tolerance, not upstream: `ServerHttpBase`
 * carries `url` + optional `password` and nothing else
 * (`_upstream/packages/app/src/runtime/server/persistence.ts:6-9`), and the
 * Basic-auth user is always the literal `opencode` (`api.ts:6`). A stored
 * username is read when present and round-tripped by [encodeStoreValue], but a
 * blank one falls back to `opencode` the moment a password exists — the same
 * default [basicAuthorizationHeader] applies.
 */
class ServerConfigStore(
    context: Context,
    private val onChanged: (String) -> Unit = {},
) {

    data class Server(
        val url: String,
        val username: String?,
        val password: String?,
    ) {
        val isComplete: Boolean get() = url.startsWith("http://") || url.startsWith("https://")

        /** Stable key for this server entry (the trimmed URL). */
        val serverKey: String get() = url.trim()
    }

    /** SSH auth method as stored: `password` or private-`key` (+ passphrase at dial time). */
    enum class SshAuthKind(val wire: String) {
        PASSWORD("password"),
        KEY("key"),
    }

    /**
     * Redial recipe for one SSH tunnel. No secret material: password and key
     * passphrase are collected interactively on every fresh dial (see
     * [sshEntries]).
     */
    data class SshEntry(
        val id: String,
        val target: String,
        val name: String,
        val authKind: SshAuthKind,
        val privateKeyPath: String? = null,
        /** Last loopback url this tunnel fed the server list (`http://127.0.0.1:<port>`). */
        val lastLocalUrl: String? = null,
    )

    /**
     * Legal decoded store payload: [active] is null when the value is valid
     * but holds no usable entry — `{"list":[]}` after deleting the last
     * server, or only incomplete entries.
     */
    internal data class StoreValue(val active: Server?)

    /**
     * One `SharedPreferences` handle per process (see [sharedPrefs]).
     * `EncryptedSharedPreferences.create` is not safe to call concurrently on
     * the same file (first-run keyset generation races) and every call returns
     * a distinct wrapper — per-instance creation let one [ServerConfigStore]
     * write the password into the encrypted file while another instance, which
     * had silently fallen back to the plain file, read `null` back and sent no
     * `Authorization` at all.
     */
    private val prefs = sharedPrefs(context)

    @Volatile
    var server: Server? = null
        private set

    private val _revision = MutableStateFlow(0)
    /** Emits after every accepted config mutation so Compose clients refresh immediately. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Last raw JSON seen — the only place the *full* list survives. */
    @Volatile
    private var lastJson: String? = null

    /**
     * Accepts a new store value.
     *
     * [notify] controls whether [onChanged] fires for this write. The
     * WebView→native direction (`syncServerConfig`) passes `false` so the
     * value is not echoed straight back into the WebView it came from; the
     * native→WebView direction ([replaceAll]) keeps the default `true` so the
     * WebView's localStorage mirror receives every native edit — including
     * list edits that do not change the active server (adding a second
     * server), which the old `next == server` early-return used to swallow.
     *
     * [retainCredentials] separates automatic sync from explicit edits. The
     * upstream wire schema (`ServerHttpBase`: `url` + optional `password`, no
     * `username`) has no "password cleared" marker — a missing key means both
     * "never had one" and "the user removed it" — so the WebView sync payload
     * can never express an explicit clear. With the default `true`, a same-URL
     * entry whose password/username is missing inherits the previously
     * persisted one (stale no-secret echoes after a native password write no
     * longer wipe `KEY_PASS`). Native edits go through [replaceAll], which
     * passes `false`: the list it builds is authoritative, so clearing a
     * secret there takes effect verbatim.
     *
     * A payload is rejected only when [decodeStoreValue] deems it illegal:
     * `{"list":[]}` — deleting the last server — is legal and persists with
     * no active server.
     */
    fun updateFromJson(json: String?, notify: Boolean = true, retainCredentials: Boolean = true): Boolean {
        if (json.isNullOrBlank()) return false
        if (decodeStoreValue(json) == null) return false
        val previousRaw = prefs.getString(KEY_LIST_JSON, null) ?: lastJson
        val effective = if (retainCredentials) mergeJsonRetainingCredentials(previousRaw, json) else json
        val decoded = decodeStoreValue(effective) ?: return false
        val next = decoded.active
        val serverChanged = next != server
        val rawChanged = previousRaw != effective
        if (!serverChanged && !rawChanged) return false
        lastJson = effective
        prefs.edit()
            .putString(KEY_LIST_JSON, effective)
            .apply()
        if (serverChanged || next == null) {
            server = next
        }
        // The credential mirror (KEY_URL / KEY_USER / KEY_PASS) is rewritten on
        // **every** accepted payload, not only when the active server object
        // changes: a password-only edit whose decoded `next` already equals the
        // in-memory [server] used to skip this write and leave a stale KEY_PASS
        // behind, which [restore] then preferred over the fresh list JSON —
        // every client built from `store.server` kept sending the old (or
        // empty) secret. `next == null` clears all three as well: an accepted
        // "no active server" payload must never leave a stale url/user/pass.
        prefs.edit()
            .putString(KEY_URL, next?.url)
            .putString(KEY_USER, next?.username)
            .putString(KEY_PASS, next?.password)
            .apply()
        _revision.value = _revision.value + 1
        if (notify && rawChanged) onChanged(effective)
        return true
    }

    /** The full `{"list":[...],"active":...}` payload exactly as stored, or null. */
    fun rawJson(): String? = prefs.getString(KEY_LIST_JSON, null) ?: lastJson

    /**
     * The active server re-decoded from prefs **at call time** — the credential
     * source `listServers()` / `SettingsServerBridge.healthTargets()` (and
     * therefore `ServerHealthProbe`) already use, as opposed to the in-memory
     * [server] snapshot.
     *
     * Race window this closes (3-device logcat + curl, 2026-09-24): the
     * generation probe captured `serverConfig.server` once in
     * `ChatViewModel.ensureClient()` and ran `GET /api/info` later on IO. The
     * in-memory field is only written by [restore]/[updateFromJson], so a
     * probe that fires before the WebView's `syncServerConfig` lands — or that
     * rides a first-match pick of a bare-string twin (see [pickActive]) — sends
     * no `Authorization` at all (`basicAuthorizationHeader` is null without a
     * password) and the server answers `401`. `ServerHealthProbe`, which
     * re-reads the same prefs at probe time, reports that very server healthy
     * with the `opencode` user. Probing through this method makes the two
     * reads see the same credential at the same instant.
     */
    fun freshServer(): Server? {
        restore()
        return server
    }

    /**
     * M4 read-only accessor: the *whole* configured list plus the active key.
     *
     * The M2 code path only ever needed the single active server (see [server]);
     * the Settings → Servers tab has to render every entry, so this exposes the
     * same parse without changing [updateFromJson] / [server] semantics.
     */
    fun listServers(): List<Server> {
        val json = prefs.getString(KEY_LIST_JSON, null) ?: lastJson ?: return server?.let { listOf(it) } ?: emptyList()
        return try {
            parseAll(json)
        } catch (_: JSONException) {
            server?.let { listOf(it) } ?: emptyList()
        }
    }

    /** The active server's URL key (`server.active` in the wire schema). */
    fun activeUrl(): String? {
        val json = prefs.getString(KEY_LIST_JSON, null) ?: lastJson ?: return server?.url
        return try {
            JSONObject(json).optString("active", "").takeIf { it.isNotEmpty() } ?: server?.url
        } catch (_: JSONException) {
            server?.url
        }
    }

    /**
     * M4 write path: replace the persisted list + active key.
     *
     * The list passed here is authoritative — native callers build it from
     * [listServers] plus their own edit, so no credential merge applies (a
     * secret the editor dropped stays dropped; see [updateFromJson]).
     *
     * **Known limitation (reported to the parent):** the WebView still holds the
     * authoritative copy in `localStorage["opencode.global.dat"]`. This writes
     * the DataStore mirror that [OpenCodeClient] reads, so the native client
     * immediately targets the edited server; re-opening the WebView re-syncs it
     * through the existing `syncServerConfig` bridge call. Pushing the new value
     * *into* the WebView needs a JS hook that M4 does not add (the bridge is
     * read-only today) — tracked in `research/_M4_settings_palette_status.md`.
     */
    fun replaceAll(servers: List<Server>, activeUrl: String?): Boolean =
        updateFromJson(encodeStoreValue(servers, activeUrl), retainCredentials = false)

    /**
     * Durable SSH tunnel configs for cross-restart reconnect. The live
     * `SshConnection` stays process-scoped ([SshTunnelHost]); only the redial
     * recipe persists here: target, display name, auth method and private-key
     * **path**.
     *
     * Secrets (password / key passphrase) are never persisted — not even in
     * this file's `EncryptedSharedPreferences`, which silently falls back to a
     * plain file when the platform keystore is unavailable. Rehydrated entries
     * carry no secret, and jsch's `UserInfo` already re-prompts for exactly
     * that case, so reconnect straightforwardly becomes an interactive-auth
     * dial again. The key path alone cannot authenticate without the file and
     * its passphrase.
     *
     * Reconnect triggering lives outside this store (tunnel host / session
     * route); see the parent handoff.
     */
    fun sshEntries(): List<SshEntry> =
        decodeSshEntries(prefs.getString(KEY_SSH_JSON, null))

    /** The stored SSH config for [id], or null. */
    fun sshEntry(id: String): SshEntry? = sshEntries().firstOrNull { it.id == id }

    /** Inserts or replaces the entry with the same id. False when id/target is blank. */
    fun putSshEntry(entry: SshEntry): Boolean {
        if (entry.id.isBlank() || entry.target.isBlank()) return false
        val next = sshEntries().filterNot { it.id == entry.id } + entry
        prefs.edit().putString(KEY_SSH_JSON, encodeSshEntries(next)).apply()
        return true
    }

    /** Drops the entry with [id]. False when no such entry exists. */
    fun removeSshEntry(id: String): Boolean {
        val current = sshEntries()
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return false
        prefs.edit().putString(KEY_SSH_JSON, encodeSshEntries(next)).apply()
        return true
    }

    fun restore() {
        lastJson = prefs.getString(KEY_LIST_JSON, null)
        // The list JSON is rewritten on every accepted update; the scalar
        // mirror can lag behind it (a password-only edit on an older build
        // skipped the mirror write entirely). Decoding it with the same rules
        // as [listServers] is what keeps `server.password` equal to the
        // credential the rows display, the probes send and the clients use.
        val decoded = lastJson?.let { raw -> runCatching { decodeStoreValue(raw) }.getOrNull() }
        if (decoded != null) {
            server = decoded.active
            return
        }
        // Legacy fallback: installs that predate `list_json`.
        val url = prefs.getString(KEY_URL, null) ?: return
        server = Server(url, prefs.getString(KEY_USER, null), prefs.getString(KEY_PASS, null))
    }

    companion object {
        private const val TAG = "ServerConfigStore"
        private const val DEFAULT_USERNAME = "opencode"
        private const val KEY_URL = "url"
        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"

        /** M4: the untouched `{"list":[...],"active":...}` payload. */
        private const val KEY_LIST_JSON = "list_json"

        /** SSH redial recipes (`{"entries":[...]}`). */
        private const val KEY_SSH_JSON = "ssh_json"

        /** `kind` discriminator written on every SSH entry. */
        private const val SSH_KIND = "ssh"

        @Volatile
        private var sharedPrefs: SharedPreferences? = null

        /**
         * Process-wide [SharedPreferences] for the server config — one
         * encrypted handle (or one plain fallback) shared by every
         * [ServerConfigStore] instance, so a password written through one
         * screen's store is the same value every other screen reads back.
         */
        private fun sharedPrefs(context: Context): SharedPreferences {
            sharedPrefs?.let { return it }
            synchronized(this) {
                sharedPrefs?.let { return it }
                val created = try {
                    val masterKey = MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    EncryptedSharedPreferences.create(
                        context,
                        "oc_server_config",
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "encrypted prefs unavailable, falling back to plain prefs: ${e.message}")
                    context.getSharedPreferences("oc_server_config_plain", Context.MODE_PRIVATE)
                }
                sharedPrefs = created
                return created
            }
        }

        /**
         * Pure wire encode for [replaceAll] — the exact inverse of
         * [decodeStoreValue]. In the companion so the password round-trip is
         * JVM-testable without an Android `Context`.
         */
        internal fun encodeStoreValue(servers: List<Server>, activeUrl: String?): String {
            val json = JSONObject().put(
                "list",
                JSONArray().apply {
                    for (entry in servers) {
                        val http = JSONObject().put("url", entry.url)
                        entry.username?.let { http.put("username", it) }
                        entry.password?.let { http.put("password", it) }
                        put(JSONObject().put("type", "http").put("http", http))
                    }
                },
            )
            activeUrl?.takeIf { it.isNotEmpty() }?.let { json.put("active", it) }
            return json.toString()
        }

        /**
         * Pure wire decode for [updateFromJson] — lives in the companion so
         * the payload rules are JVM-testable without an Android `Context`
         * (same split as `SettingsStore.encodeServerList`).
         *
         * Null ⇔ illegal payload: not JSON, or an object that is neither a
         * `list` store nor a decodable legacy `{url,…}`. A `{"list":[…]}`
         * payload is legal even when empty or when no entry is usable —
         * deleting the last server writes `{"list":[]}` and that write must
         * persist — so the active server is carried separately and may be null.
         */
        internal fun decodeStoreValue(json: String): StoreValue? {
            val obj = try {
                JSONObject(json)
            } catch (_: JSONException) {
                return null
            }
            val active = parse(obj)
            if (active == null && obj.optJSONArray("list") == null) return null
            return StoreValue(active)
        }

        /** All complete entries of a `{"list":[...]}` payload (legacy single object included). */
        internal fun parseAll(json: String): List<Server> {
            val obj = JSONObject(json)
            val list = obj.optJSONArray("list") ?: return parseLegacy(obj)?.let { listOf(it) } ?: emptyList()
            val result = ArrayList<Server>(list.length())
            for (i in 0 until list.length()) {
                val entry = list.opt(i)
                val server = when (entry) {
                    is String -> fromBareUrl(entry)
                    is JSONObject -> parseEntry(entry)
                    else -> null
                } ?: continue
                if (server.isComplete) result.add(server)
            }
            return result
        }

        /**
         * Picks the active http server from the store value, preferring exact
         * matches on `active`, then the first usable http entry. Delegates to
         * [pickActive] so the twin-entry rule is JVM-testable.
         */
        private fun parse(obj: JSONObject): Server? {
            val list: JSONArray = obj.optJSONArray("list") ?: return parseLegacy(obj)
            val activeKey = obj.optString("active", "")
            val usable = ArrayList<Server>(list.length())
            for (i in 0 until list.length()) {
                val entry = list.opt(i)
                val server = when (entry) {
                    is String -> fromBareUrl(entry)
                    is JSONObject -> parseEntry(entry)
                    else -> null
                } ?: continue
                if (server.isComplete) usable.add(server)
            }
            return pickActive(usable, activeKey)
        }

        /**
         * Active-row rule over already-parsed entries: exact `active` matches
         * first (else the first usable row), and when the list holds several
         * rows for the winning URL the **credential-bearing** one wins.
         *
         * Upstream's `StoredServer` union (`_upstream/packages/app/src/runtime/
         * server/persistence.ts:21-34`) accepts a bare URL string next to
         * `{type:"http",http:{url,password}}`, so one address can appear twice —
         * once without a secret. The old first-match-then-break pick took the
         * bare row and dropped the password: the generation probe then sent no
         * `Authorization`, got `401`, and decided `UNKNOWN`, while
         * `listServers()` (which returns *every* row) still showed the
         * credential-bearing twin as healthy in the Servers panel. Preferring
         * the row with a password makes [server] and `listServers()` agree.
         */
        internal fun pickActive(entries: List<Server>, activeKey: String): Server? {
            if (entries.isEmpty()) return null
            val chosenUrl = if (activeKey.isNotEmpty() && entries.any { it.url == activeKey }) {
                activeKey
            } else {
                entries.first().url
            }
            val twins = entries.filter { it.url == chosenUrl }
            return twins.firstOrNull { it.password != null } ?: twins.first()
        }

        private fun parseEntry(entry: JSONObject): Server? {
            // Entries may be `{type:"http", http:{url,...}}`; upstream also folds
            // legacy `{url,...}` objects into that shape before storing.
            val http = entry.optJSONObject("http")
            if (http != null) {
                val type = entry.optString("type", "http")
                if (type.isNotEmpty() && type != "http") return null
                return fromHttp(http)
            }
            val topLevelUrl = entry.optString("url", "")
            if (topLevelUrl.isNotEmpty()) return fromHttp(entry)
            return null
        }

        private fun fromHttp(http: JSONObject): Server {
            val rawUrl = http.optString("url", "").trim()
            val (url, infoUser, infoPass) = splitUserInfo(rawUrl)
            val password = http.optString("password", "").takeIf { it.isNotEmpty() } ?: infoPass
            // Upstream defaults the basic-auth username to "opencode".
            val username = http.optString("username", "").takeIf { it.isNotEmpty() }
                ?: infoUser
                ?: password?.let { DEFAULT_USERNAME }
            return Server(url, username, password)
        }

        /** Top-level `{url, username?, password?}` (early M1 shape). */
        private fun parseLegacy(obj: JSONObject): Server? {
            val rawUrl = obj.optString("url", "").trim()
            if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) return null
            val (url, infoUser, infoPass) = splitUserInfo(rawUrl)
            val password = obj.optString("password", "").takeIf { it.isNotEmpty() } ?: infoPass
            val username = obj.optString("username", "").takeIf { it.isNotEmpty() }
                ?: infoUser
                ?: password?.let { DEFAULT_USERNAME }
            return Server(url, username, password)
        }

        /**
         * One bare-string list entry. Historically credential-free by
         * construction (upstream folds it to `{url}` with no password), but a
         * hand-written `http://user:pass@host` string keeps its secret through
         * [splitUserInfo] instead of decoding to a password-less row that 401s.
         */
        internal fun fromBareUrl(entry: String): Server {
            val (url, user, pass) = splitUserInfo(entry.trim())
            return Server(url, user ?: pass?.let { DEFAULT_USERNAME }, pass)
        }

        /**
         * Splits `http://user:pass@host…` into the clean URL plus the userinfo
         * halves. Only the authority section (between `://` and the first
         * `/`, `?` or `#`) is inspected, so `@` in a path, query or fragment
         * and bracketed IPv6 hosts pass through untouched; the userinfo cut is
         * the *last* `@` per RFC 3986. Explicit-field precedence is the
         * caller's job — this returns the raw halves, percent-decoded
         * (`%XX`; a literal `+` stays a `+`, malformed sequences stay literal).
         */
        internal fun splitUserInfo(url: String): Triple<String, String?, String?> {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return Triple(url, null, null)
            val authorityStart = schemeEnd + 3
            val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
                .takeIf { it >= 0 } ?: url.length
            val at = url.lastIndexOf('@', authorityEnd - 1)
            if (at < authorityStart) return Triple(url, null, null)
            val clean = url.substring(0, authorityStart) + url.substring(at + 1)
            val info = url.substring(authorityStart, at)
            if (info.isEmpty()) return Triple(clean, null, null)
            val colon = info.indexOf(':')
            val user = (if (colon < 0) info else info.substring(0, colon))
                .let(::percentDecode).takeIf { it.isNotEmpty() }
            val pass = (if (colon < 0) null else info.substring(colon + 1))
                ?.let(::percentDecode)?.takeIf { it.isNotEmpty() }
            return Triple(clean, user, pass)
        }

        private fun percentDecode(value: String): String {
            if ('%' !in value) return value
            val pending = java.io.ByteArrayOutputStream()
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val hex = if (value[i] == '%' && i + 3 <= value.length) {
                    value.substring(i + 1, i + 3).toIntOrNull(16)
                } else {
                    null
                }
                if (hex != null) {
                    pending.write(hex)
                    i += 3
                } else {
                    if (pending.size() > 0) {
                        out.append(pending.toByteArray().toString(Charsets.UTF_8))
                        pending.reset()
                    }
                    out.append(value[i])
                    i += 1
                }
            }
            if (pending.size() > 0) out.append(pending.toByteArray().toString(Charsets.UTF_8))
            return out.toString()
        }

        /**
         * Per-URL credential merge for the WebView sync direction: every entry
         * of [newPayload] whose password/username is missing inherits the
         * previously persisted same-URL values. A genuinely new secret always
         * wins; a URL absent from [newPayload] stays deleted — nothing is
         * resurrected.
         */
        internal fun mergeCredentials(old: List<Server>, newPayload: List<Server>): List<Server> =
            newPayload.map { mergeOne(old, it) }

        private fun mergeOne(old: List<Server>, entry: Server): Server {
            if (entry.password?.isNotEmpty() == true && entry.username?.isNotEmpty() == true) return entry
            val donor = old.firstOrNull { it.url == entry.url && it.password != null }
                ?: old.firstOrNull { it.url == entry.url }
                ?: return entry
            return entry.copy(
                username = entry.username.takeUnless { it.isNullOrEmpty() } ?: donor.username,
                password = entry.password.takeUnless { it.isNullOrEmpty() } ?: donor.password,
            )
        }

        /**
         * Applies [mergeCredentials] to the raw sync payload, patching only
         * the secret fields that come back empty. Returns the original [json]
         * untouched when there is nothing to inherit, so the common path keeps
         * today's byte-identical persist; unknown keys (`hidden`, `projects`,
         * `displayName`, …) are never stripped because entries are patched in
         * place instead of re-encoded.
         */
        internal fun mergeJsonRetainingCredentials(previousRaw: String?, json: String): String {
            val old = previousRaw?.let { runCatching { parseAll(it) }.getOrNull() }.orEmpty()
            if (old.isEmpty()) return json
            return try {
                val obj = JSONObject(json)
                val list = obj.optJSONArray("list")
                var changed = false
                if (list != null) {
                    for (i in 0 until list.length()) {
                        changed = patchEntry(list, i, old) || changed
                    }
                } else {
                    val legacy = parseLegacy(obj) ?: return json
                    val merged = mergeOne(old, legacy)
                    if (stripStoredUserInfo(obj)) changed = true
                    if (fillMissingSecrets(obj, merged)) changed = true
                }
                if (changed) obj.toString() else json
            } catch (_: JSONException) {
                json
            }
        }

        private fun patchEntry(list: JSONArray, index: Int, old: List<Server>): Boolean {
            val raw = list.opt(index) ?: return false
            val parsed = when (raw) {
                is String -> fromBareUrl(raw)
                is JSONObject -> parseEntry(raw)
                else -> null
            }?.takeIf { it.isComplete } ?: return false
            val merged = mergeOne(old, parsed)
            if (raw is String) {
                // A bare string carrying userinfo is rewritten to the object
                // shape even with no donor secret: the credential moves from
                // the URL (which surfaces in logs and the server key) into
                // fields, matching what [encodeStoreValue] writes.
                if (merged == parsed && merged.url == raw.trim()) return false
                val http = JSONObject().put("url", merged.url)
                merged.username?.let { http.put("username", it) }
                merged.password?.let { http.put("password", it) }
                list.put(index, JSONObject().put("type", "http").put("http", http))
                return true
            }
            val entry = raw as JSONObject
            val target = entry.optJSONObject("http")
                ?: entry.takeIf { it.optString("url", "").isNotEmpty() }
                ?: return false
            val stripped = stripStoredUserInfo(target)
            val filled = fillMissingSecrets(target, merged)
            if (merged == parsed) return stripped || filled
            return true
        }

        /**
         * Writes [server]'s password/username into [target] where the fields
         * are missing. Covers both the inherited donor secret and halves that
         * only lived in a stored userinfo URL, so the persisted JSON stays
         * canonical. True when a field was written.
         */
        private fun fillMissingSecrets(target: JSONObject, server: Server): Boolean {
            var filled = false
            if (server.password != null && target.optString("password", "").isEmpty()) {
                target.put("password", server.password)
                filled = true
            }
            if (server.username != null && target.optString("username", "").isEmpty()) {
                target.put("username", server.username)
                filled = true
            }
            return filled
        }

        /**
         * Rewrites a stored `http://user:pass@host` URL to its clean form in
         * place. True when the URL was rewritten.
         */
        private fun stripStoredUserInfo(target: JSONObject): Boolean {
            val stored = target.optString("url", "")
            val clean = splitUserInfo(stored).first
            if (clean == stored) return false
            target.put("url", clean)
            return true
        }

        /** Pure wire encode for [sshEntries] — JVM-testable without an Android `Context`. */
        internal fun encodeSshEntries(entries: List<SshEntry>): String {
            val array = JSONArray()
            for (entry in entries) {
                val obj = JSONObject()
                    .put("kind", SSH_KIND)
                    .put("id", entry.id)
                    .put("target", entry.target)
                    .put("name", entry.name)
                    .put("authKind", entry.authKind.wire)
                entry.privateKeyPath?.takeIf { it.isNotEmpty() }?.let { obj.put("privateKeyPath", it) }
                entry.lastLocalUrl?.takeIf { it.isNotEmpty() }?.let { obj.put("lastLocalUrl", it) }
                array.put(obj)
            }
            return JSONObject().put("entries", array).toString()
        }

        /**
         * Pure wire decode for [sshEntries]. Corrupt rows (blank id/target,
         * unknown `authKind`/`kind`) are skipped, never fatal — a future entry
         * shape must not brick the whole tunnel list.
         */
        internal fun decodeSshEntries(json: String?): List<SshEntry> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONObject(json).optJSONArray("entries") ?: return emptyList()
                val result = ArrayList<SshEntry>(array.length())
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val kind = obj.optString("kind", SSH_KIND)
                    if (kind.isNotEmpty() && kind != SSH_KIND) continue
                    val authKind = SshAuthKind.entries.firstOrNull { it.wire == obj.optString("authKind", "") }
                        ?: continue
                    val id = obj.optString("id", "")
                    val target = obj.optString("target", "")
                    if (id.isEmpty() || target.isEmpty()) continue
                    result.add(
                        SshEntry(
                            id = id,
                            target = target,
                            name = obj.optString("name", ""),
                            authKind = authKind,
                            privateKeyPath = obj.optString("privateKeyPath", "").takeIf { it.isNotEmpty() },
                            lastLocalUrl = obj.optString("lastLocalUrl", "").takeIf { it.isNotEmpty() },
                        ),
                    )
                }
                result
            } catch (_: JSONException) {
                emptyList()
            }
        }
    }
}
