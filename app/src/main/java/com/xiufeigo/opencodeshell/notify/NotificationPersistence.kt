package com.xiufeigo.opencodeshell.notify

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * B-3: where the notification feed survives a process restart. Upstream keeps
 * the list in `Persistence.struct` (`notification.tsx:35` `NotificationStore`,
 * read through `persisted(Persist.serverGlobal(sdk.scope, "notification"), …)`)
 * so an unseen turn-complete from before a reload still counts as unseen; the
 * Android shell has no WebView blob to write into, so the same list is handed to
 * whatever storage the platform offers ([NotificationPrefs] on device, a fake in
 * tests).
 *
 * An interface, not a concrete store, for the same reason as `NavMemory`: the
 * notifier itself stays JVM-testable (it owns no Android API) while the file
 * layout stays a one-file decision.
 */
interface NotificationPersistence {
    /** The persisted feed, oldest first. A missing/corrupt blob reads as empty. */
    fun load(): List<AppNotification>

    /** Replaces the persisted feed with [notifications] (already pruned). */
    fun save(notifications: List<AppNotification>)
}

/**
 * The on-disk wire format of the feed: a JSON array of objects with exactly the
 * fields [AppNotification] carries (`notification.tsx:23-33`'s `NotificationBase`
 * plus the kind). `org.json` is the codec the rest of the app's stores use
 * (`SettingsStore.encodeKeybinds`, `ServerConfigStore`), and it is on the unit
 * test classpath (`testImplementation("org.json:json")`), so encode/decode stay
 * pinned by a plain JVM test.
 *
 * Decoding is **tolerant**: the blob is written by an older build of this app and
 * read by a newer one, so an entry with an unknown kind, a missing timestamp or a
 * non-object element is dropped instead of failing the whole read — a notification
 * history is not worth crashing a cold start over.
 */
object NotificationCodec {

    fun encode(notifications: List<AppNotification>): String {
        val array = JSONArray()
        for (notification in notifications) {
            val obj = JSONObject()
            obj.put("id", notification.id)
            obj.put("kind", notification.kind.name)
            obj.put("time", notification.time)
            obj.put("viewed", notification.viewed)
            // Absent key, not `null`: `JSONObject.isNull` reads both as "no value",
            // and dropping the key keeps the blob small.
            notification.sessionID?.let { obj.put("sessionID", it) }
            notification.sessionTitle?.let { obj.put("sessionTitle", it) }
            notification.detail?.let { obj.put("detail", it) }
            array.put(obj)
        }
        return array.toString()
    }

    fun decode(raw: String?): List<AppNotification> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        val array = try {
            JSONArray(trimmed)
        } catch (_: JSONException) {
            return emptyList()
        }
        val out = ArrayList<AppNotification>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            out += decodeOne(obj) ?: continue
        }
        return out
    }

    private fun decodeOne(obj: JSONObject): AppNotification? {
        val kind = AppNotificationKind.values().firstOrNull { it.name == obj.optString("kind") } ?: return null
        // A zero/absent time would place the entry at the epoch and the 30-day
        // TTL would drop it on the next prune anyway; reject it up front so the
        // list never carries an entry the UI cannot render a relative time for.
        val time = obj.optLong("time", 0L)
        if (time <= 0L) return null
        val sessionID = obj.stringOrNull("sessionID")
        return AppNotification(
            id = obj.optString("id").takeIf { it.isNotBlank() }
                ?: "${kind.name}:${sessionID.orEmpty()}:$time",
            kind = kind,
            sessionID = sessionID,
            sessionTitle = obj.stringOrNull("sessionTitle"),
            detail = obj.stringOrNull("detail"),
            time = time,
            viewed = obj.optBoolean("viewed", false),
        )
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key, "").takeIf { it.isNotBlank() }
}
