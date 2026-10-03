package com.xiufeigo.opencodeshell.ui.settings

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

/**
 * Device pairing (`servers/connect/pairing.ts` at the v2 head) — pairing-code
 * encode/decode plus the pure half of the QR render (`uqr` in
 * `settings/pairing/pairing.tsx`'s `DialogPairing`): content in, module matrix
 * out. No Android types here; `PairingPanel` paints the matrix and
 * `QrScan` owns the decode half.
 *
 * A pairing code is the JSON `{"username":"opencode","password":…,"urls":[…]}`
 * (v2 makes `urls` optional, defaulting to the link's origin); `opencode pair`
 * and the desktop app wrap it as `http(s)://<host>/connect#<base64url>` where
 * the fragment is `base64Encode(JSON)` — URL-safe, unpadded
 * (`@opencode/util/encode.ts`). Pure and JVM-testable: the settings page only
 * paints what these return.
 */
object PairingModel {

    /** `Schema.Struct` of the pairing JSON — `username` is the literal `opencode`. */
    data class PairingDetails(val password: String, val urls: List<String>) {
        val username: String get() = PAIRING_USERNAME
    }

    const val PAIRING_USERNAME: String = "opencode"

    /**
     * `serverAddress` (`pairing.ts:12-20`): a usable `http(s)` base URL with no
     * credentials, query or fragment. A value that carries another scheme
     * (`ssh://…`) is rejected; a bare `host:port` is normalised to `http://`.
     * Upstream's `normalizeServerUrl` only adds the scheme and strips trailing
     * slashes — both are done here directly.
     */
    fun serverAddress(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains("://") && !trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        val normalized = (if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed")
            .trimEnd('/')
        if (normalized.isEmpty()) return null
        val url = normalized.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.querySize > 0 || url.fragment != null) return null
        return normalized
    }

    /**
     * `pairingUrl({username, password}, host)` — `host`'s `/connect` page with
     * the code in the fragment (what the upstream dialog renders as QR and as
     * the copyable origin). The JSON keeps upstream's `JSON.stringify` key
     * order (`username`, `password`) so the fragment matches desktop output.
     */
    fun pairingUrl(password: String, host: String): String? {
        val base = host.trim().trimEnd('/')
        val root = if (base.startsWith("http://") || base.startsWith("https://")) base else "http://$base"
        val connect = root.toHttpUrlOrNull()?.resolve("/connect")?.toString() ?: return null
        return "$connect#${encodeCode(jsonBody(password))}"
    }

    /** The copyable text of a pairing link — upstream shows `url.origin`. */
    fun pairingOrigin(pairingUrl: String): String? =
        pairingUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }

    /**
     * `decodePairingCode(value, origin?)` — the raw JSON code. [origin] fills in
     * `urls` when the code omits them (v2's optional field).
     */
    fun decodePairingCode(value: String, origin: String? = null): PairingDetails? {
        val obj = runCatching { JSONObject(value) }.getOrNull() ?: return null
        if (obj.optString("username") != PAIRING_USERNAME) return null
        if (!obj.has("password")) return null
        val password = obj.optString("password")
        val rawUrls = obj.optJSONArray("urls")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it, "").takeIf(String::isNotBlank) }
        } ?: listOfNotNull(origin)
        val urls = rawUrls.mapNotNull(::serverAddress).distinct()
        if (urls.isEmpty()) return null
        return PairingDetails(password = password, urls = urls)
    }

    /**
     * `decodePairingUrl(value, origin?)` — the `#<base64url>` / `?data=<json>`
     * forms of a `/connect` link (or the bare fragment).
     */
    fun decodePairingUrl(value: String, origin: String? = null): PairingDetails? {
        if (value.startsWith("?")) {
            val data = value.trimStart('?').split('&')
                .firstOrNull { it.startsWith("data=") }
                ?.substring("data=".length)
                ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
            return data?.let { decodePairingCode(it, origin) }
        }
        val encoded = if (value.startsWith("#")) value.substring(1) else value
        if (encoded.isEmpty()) return null
        // Legacy raw-JSON form (`URLSearchParams("value=…")` upstream).
        if (encoded.startsWith("{")) return decodePairingCode(encoded, origin)
        if (!BASE64URL.matches(encoded) || encoded.length % 4 == 1) return null
        val padded = encoded.replace('-', '+').replace('_', '/')
            .padEnd((encoded.length + 3) / 4 * 4, '=')
        val json = runCatching {
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return decodePairingCode(json, origin)
    }

    /**
     * `decodePairingScan` — a full `/connect` link or a raw code. (The camera
     * hands the scanned string here; this milestone only reaches it from the
     * manual input.)
     */
    fun decodePairingScan(value: String): PairingDetails? {
        val trimmed = value.trim()
        val url = trimmed.toHttpUrlOrNull()
            ?: return decodePairingCode(trimmed) ?: decodePairingUrl(trimmed)
        val origin = "${url.scheme}://${url.host}:${url.port}"
        return decodePairingUrl(url.query?.let { "?$it" } ?: "", origin)
            ?: decodePairingUrl(url.encodedFragment?.let { "#$it" } ?: "", origin)
    }

    /** URL-safe, unpadded base64 — `@opencode/util/encode.ts` `base64Encode`. */
    fun encodeCode(json: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))

    /**
     * `renderSVG(value, { border: 4, … })` in `DialogPairing` — the quiet zone
     * is uqr's `border`, in modules.
     */
    const val QR_BORDER: Int = 4

    /** uqr `encode`'s default (`ecc = 'L'`, `boostEcc` off). */
    val QR_ECC: ErrorCorrectionLevel = ErrorCorrectionLevel.L

    /**
     * `uqr` `encode` — [content] as a QR module matrix (`true` = dark), quiet
     * zone included, one cell per module. zxing's writer is plain Java, so this
     * stays JVM-testable; the panel turns the matrix into a `Bitmap`.
     */
    fun qrModules(content: String): Array<BooleanArray> {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to QR_ECC,
            EncodeHintType.MARGIN to QR_BORDER,
            EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        return Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y) } }
    }

    /** `JSON.stringify({username, password})` in upstream's key order. */
    private fun jsonBody(password: String): String =
        "{\"username\":\"$PAIRING_USERNAME\",\"password\":${jsonString(password)}}"

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when {
                ch == '\\' -> append("\\\\")
                ch == '"' -> append("\\\"")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch.code < 0x20 -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }

    private val BASE64URL = Regex("^[A-Za-z0-9_-]+$")
}
