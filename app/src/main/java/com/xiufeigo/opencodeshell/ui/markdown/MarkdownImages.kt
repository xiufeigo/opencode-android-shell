package com.xiufeigo.opencodeshell.ui.markdown

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.basicAuthorizationHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A-1: markdown images. Upstream's `markdown-image.ts` + `image-preview.tsx`
 * pair: an `<img>` whose `src` is a **local path** is rewritten to a blob URL by
 * reading the file through `GET /api/fs/read/<rel>` (`runtime/server/image.ts`
 * `readLocalImage`), a remote `https://` src stays browser-owned (no auth header
 * is ever attached to it), anything with an unsafe scheme or an unknown
 * extension is left inert, and every rendered image is clickable into the
 * full-size preview dialog.
 *
 * The Android shell has no browser and adds no image dependency, so the bytes
 * are fetched and decoded here:
 *
 * | source | upstream | here |
 * |---|---|---|
 * | `https://…/a.png` | browser-owned `<img src>` | plain GET, **no** `Authorization` |
 * | `chart.png` / `/abs/chart.png` | `fs.read` → blob URL | `GET /api/fs/read/<rel>` with the server's Basic auth |
 * | `javascript:` / `ftp:` / unknown extension / `.svg` | left inert (no `src`) | [Decision.Rejected] → the alt text stays |
 *
 * Everything decision-shaped is pure ([localImagePath], [extension], [decide])
 * and JVM-tested by `MarkdownImageTest`; only [reader] touches Android + network.
 *
 * A read that **fails** is not final: upstream's browser re-requests the `img`
 * element whenever the markdown re-renders, while a Compose body keeps its node
 * for the life of the row — [Retry] is the shell's stand-in for that re-request.
 */
object MarkdownImages {

    /**
     * Extensions the shell can hand to `BitmapFactory` (`readLocalImage`'s
     * `types` map, `runtime/server/image.ts:4-14`, minus the formats Android
     * cannot decode — `svg` needs a vector renderer this app deliberately does
     * not depend on, `ico` is unsupported by `BitmapFactory`).
     */
    private val DECODABLE = mapOf(
        "png" to "image/png",
        "jpeg" to "image/jpeg",
        "jpg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "avif" to "image/avif",
    )

    /** Extensions upstream reads but this shell must fall back on (documented gap). */
    private val UNSUPPORTED = mapOf(
        "svg" to "image/svg+xml",
        "ico" to "image/x-icon",
    )

    /** Hard cap on one image read; a markdown body must not pull a huge blob. */
    const val MAX_BYTES = 12 * 1024 * 1024

    /** What one `![alt](src)` resolves to. */
    sealed interface Decision {
        /** A workspace path (or an absolute one) to read through `/api/fs/read`. */
        data class Local(val path: String, val mime: String) : Decision

        /** An `http(s)` URL the shell fetches itself, without credentials. */
        data class Remote(val url: String) : Decision

        /**
         * No image: an unsafe scheme, an unknown extension or a format Android
         * cannot decode. The renderer keeps the alt text.
         */
        data object Rejected : Decision
    }

    /**
     * `markdown-image.ts:3-23` `localImagePath`: the **local** path behind a
     * markdown image `src`, or null when the source is not a local path at all
     * (an `http(s)` URL, a `javascript:` payload, a protocol-relative
     * `//host/x`, a control character, a `file:` URL with a real host).
     *
     * Kept verbatim so the two shells classify the same way — the one addition
     * here is that the result is *not* percent-decoded twice (`decodeURIComponent`
     * is applied once, like upstream).
     */
    fun localImagePath(source: String): String? {
        val value = source.trim().replace('\\', '/')
        if (value.isEmpty()) return null
        if (value.any { it.code < 0x20 || it.code == 0x7f }) return null
        if (value.startsWith("//")) return null
        if (value.startsWith("file:", ignoreCase = true)) {
            val url = runCatching { java.net.URI(value) }.getOrNull() ?: return null
            val host = url.host
            if (!host.isNullOrEmpty() && host != "localhost") return null
            val path = url.path ?: return null
            return decodePath(path.replace(Regex("^/([a-zA-Z]:/)"), "$1"))
        }
        // Any other scheme (http, https, data, javascript, ftp…) is not a local
        // path — a Windows drive letter (`C:/…`) is the one exception.
        if (SCHEME.containsMatchIn(value) && !DRIVE.containsMatchIn(value)) return null
        return decodePath(value)
    }

    /** The lowercased extension of a path, or null when it has none. */
    fun extension(path: String): String? = path
        .substringAfterLast('/')
        .substringAfterLast('.', "")
        .takeIf { it.isNotEmpty() && it.length <= 5 && it.all { ch -> ch.isLetterOrDigit() } }
        ?.lowercase()

    /** The mime for a decodable extension, or null. */
    fun imageMime(path: String): String? = extension(path)?.let { DECODABLE[it] }

    /**
     * One `src` → [Decision]. A remote URL wins first (`http`/`https` is what
     * upstream leaves to the browser); everything else must be a local path with
     * a decodable extension.
     */
    fun decide(source: String): Decision {
        val trimmed = source.trim()
        val lower = trimmed.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            // `https://` only when it actually parses — a half-typed URL in a
            // streaming answer must not throw.
            return if (trimmed.toHttpUrlOrNull() != null) Decision.Remote(trimmed) else Decision.Rejected
        }
        val path = localImagePath(trimmed) ?: return Decision.Rejected
        val mime = imageMime(path) ?: return Decision.Rejected
        return Decision.Local(path, mime)
    }

    /** True when the extension is one upstream reads but Android cannot decode. */
    fun unsupportedFormat(path: String): Boolean = extension(path)?.let { UNSUPPORTED.containsKey(it) } == true

    // ------------------------------------------------------------------- retry

    /**
     * Retry policy of one markdown image body: a read that failed is
     * re-attempted a bounded number of times on a growing delay, and the failed
     * body stays tappable afterwards ([MarkdownImageView] drives both).
     *
     * Upstream needs no such policy — a remote `src` is browser-owned and every
     * markdown re-render rebuilds the `img` element, so the browser asks again
     * by itself. A Compose body is composed once and then kept, so without this
     * a single failed read (a momentary network drop, a local file the server
     * had not written yet) stayed failed for the whole session: the image only
     * came back by leaving and re-entering the session.
     */
    object Retry {

        /** Automatic re-attempts after the first failed read (4 reads in total). */
        const val AUTO_ATTEMPTS = 3

        private const val BASE_DELAY_MS = 1_000L
        private const val MAX_DELAY_MS = 4_000L
        private const val MAX_SHIFT = 8

        /**
         * The attempt index the automatic schedule grants after [failed]
         * failures (1-based: 1 = the first read failed), or null once the
         * budget is spent — the body then waits for a tap.
         */
        fun nextAttempt(failed: Int): Int? = failed.takeIf { it in 1..AUTO_ATTEMPTS }

        /** Wait before that re-read: 1 s, 2 s, 4 s (doubling, capped at [MAX_DELAY_MS]). */
        fun delayMillis(failed: Int): Long {
            if (failed < 1) return 0L
            val shift = (failed - 1).coerceAtMost(MAX_SHIFT)
            return (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
        }

        /**
         * Whether a failed body offers a tap-to-retry. There must be a reader
         * wired and something *readable* to re-read: a [Decision.Rejected]
         * source (unsafe scheme, unknown or undecodable format) is inert by
         * decision, not by failure, so its alt text stays as unclickable as
         * upstream's `src`-less `<img>`.
         */
        fun canRetry(reader: Reader?, source: String): Boolean =
            reader != null && decide(source) != Decision.Rejected
    }

    /**
     * `decodeURIComponent`: percent escapes only — `+` stays a literal plus
     * (unlike `URLDecoder`, which would turn it into a space and change the
     * path), and multi-byte escapes are reassembled as UTF-8.
     */
    private fun decodePath(value: String): String? {
        val bytes = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%' && i + 2 < value.length) {
                val code = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    bytes.write(code)
                    i += 3
                    continue
                }
            }
            bytes.write(ch.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        val path = String(bytes.toByteArray(), Charsets.UTF_8)
        if (path.any { it.code < 0x20 || it.code == 0x7f }) return null
        if (path.startsWith("//") || path.startsWith("\\\\")) return null
        return path
    }

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z\\d+.-]*:")
    private val DRIVE = Regex("^[a-zA-Z]:/")

    // ------------------------------------------------------------------ reader

    /**
     * Reads the bytes behind one markdown image source, or null when it cannot
     * be read (unsafe source, no server configured, HTTP/decode failure) — the
     * caller then keeps the alt text, which is exactly what upstream's inert
     * `<img>` degrades to.
     */
    fun interface Reader {
        suspend fun read(source: String): ByteArray?
    }

    /**
     * The reader a markdown body uses when the host provided one
     * ([LocalMarkdownImageReader]); null (the default) renders the alt text.
     */
    fun reader(context: Context, directory: String? = null): Reader = ServerReader(context.applicationContext, directory)

    /**
     * Server-backed reader. The active server (URL + credentials) is re-read
     * from [ServerConfigStore] on every call, so switching servers takes effect
     * without rebuilding the reader; the location directory is resolved once
     * (`GET /api/location`, the same read `V2Transport.browseCwd` uses) and
     * cached for the reader's lifetime.
     *
     * A remote `http(s)` image is fetched **without** any credentials, mirroring
     * upstream (`markdown.spec.ts` asserts the request carries no
     * `authorization`).
     */
    private class ServerReader(
        private val context: Context,
        private val directory: String?,
    ) : Reader {

        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        @Volatile
        private var locationDirectory: String? = directory

        override suspend fun read(source: String): ByteArray? = withContext(Dispatchers.IO) {
            when (val decision = decide(source)) {
                is Decision.Remote -> fetch(decision.url, authorization = null)
                is Decision.Local -> readLocal(decision.path)
                Decision.Rejected -> null
            }
        }

        private fun readLocal(path: String): ByteArray? {
            val server = ServerConfigStore(context).freshServer() ?: return null
            val base = server.url.trimEnd('/').toHttpUrlOrNull() ?: return null
            val absolute = path.startsWith("/") || DRIVE.containsMatchIn(path)
            // `readLocalImage`: an absolute image is scoped to its own parent
            // directory (so a path outside the project still resolves), a
            // relative one to the session/location directory.
            val scope = if (absolute) parentOf(path) else location()
            val relative = if (absolute) path.substringAfterLast('/') else path.trimStart('/')
            if (relative.isEmpty()) return null
            val url = base.newBuilder()
                .addPathSegment("api")
                .addPathSegment("fs")
                .addPathSegment("read")
                .addPathSegments(relative)
                .apply { if (!scope.isNullOrBlank()) addQueryParameter("location[directory]", scope) }
                .build()
            return fetch(url.toString(), authorization = basicAuthorizationHeader(server.username, server.password))
        }

        private fun location(): String? {
            locationDirectory?.let { return it }
            val server = ServerConfigStore(context).freshServer() ?: return null
            val base = server.url.trimEnd('/').toHttpUrlOrNull() ?: return null
            val url = base.newBuilder().addPathSegment("api").addPathSegment("location").build()
            val body = fetch(url.toString(), authorization = basicAuthorizationHeader(server.username, server.password))
                ?: return null
            val json = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull() ?: return null
            // `{"directory":…,"project":{…}}` — a JSON `null` must not become
            // the literal "null" scope (the org.json trap).
            val resolved = if (json.isNull("directory")) null else json.optString("directory", "").takeIf { it.isNotBlank() }
            locationDirectory = resolved
            return resolved
        }

        private fun fetch(url: String, authorization: String?): ByteArray? {
            val request = Request.Builder().url(url)
            authorization?.let { request.header("Authorization", it) }
            return try {
                client.newCall(request.build()).execute().use { response ->
                    if (!response.isSuccessful) return null
                    val body = response.body ?: return null
                    if (body.contentLength() > MAX_BYTES) return null
                    val bytes = body.bytes()
                    bytes.takeIf { it.isNotEmpty() && it.size <= MAX_BYTES }
                }
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun parentOf(path: String): String? {
            val normalized = path.replace('\\', '/').trimEnd('/')
            val index = normalized.lastIndexOf('/')
            return if (index <= 0) null else normalized.substring(0, index)
        }
    }
}

/**
 * The markdown image reader the current subtree renders with, provided once by
 * the timeline ([com.xiufeigo.opencodeshell.ui.session.timeline.MessageTimeline]).
 * Null — every other surface (the btw panel, the artifact viewer) and every
 * preview — keeps the pre-A-1 alt-text rendering.
 */
val LocalMarkdownImageReader = staticCompositionLocalOf<MarkdownImages.Reader?> { null }
