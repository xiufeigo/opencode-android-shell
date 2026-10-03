package com.xiufeigo.opencodeshell.v2

import okio.BufferedSink
import okio.buffer
import okio.source
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.util.UUID

/**
 * `POST /api/experimental/fs/write` — upstream `composer/attachments/
 * destination.ts:24-31` (client) and `protocol/src/groups/fs.ts:76-89` (wire).
 *
 * Held as extension functions rather than [V2Api] members (parallel change to
 * `V2Api.kt`); they use the class's `internal` HTTP seam only — same OkHttp
 * client, same `basicAuthorizationHeader`, same `ApiException` on non-2xx.
 *
 * | piece | contract |
 * |---|---|
 * | query | `location[directory]` (deep-object, like `fs/list`) + required `path` |
 * | body | raw `application/octet-stream` bytes (`Schema.Uint8Array`) |
 * | success | `200` `{"location":…,"data":{"path":…}}` — resolved absolute path |
 * | `path` | absolute, or relative to the location directory; parents are created |
 *
 * `UNVERIFIED` against a live server: openapi/`fs.ts` is the contract, and the
 * route is absent from the 1.18.31 probe (it lands with the 2.x server).
 */

private val OCTET_STREAM = "application/octet-stream".toMediaType()

/**
 * Streams [body] to [path] and returns `data.path` — the resolved absolute
 * path on the server (blank when the server omits the field; treat as failure).
 *
 * Unlike `fs/read`, the target is **not** confined to the location directory
 * (`fs.ts:85-86`), so callers can write into the server's temp dir.
 */
fun V2Api.fsWrite(
    path: String,
    body: RequestBody,
    locationDirectory: String? = null,
): String {
    val request = builder("experimental/fs/write", true) {
        located(it, locationDirectory)
        it.addQueryParameter("path", path)
    }.post(body).build()
    return dataObject(callBody(request)).optString("path", "")
}

/** [fsWrite] for an in-memory payload. */
fun V2Api.fsWrite(path: String, bytes: ByteArray, locationDirectory: String? = null): String =
    fsWrite(path, bytes.toRequestBody(OCTET_STREAM), locationDirectory)

/**
 * Upstream `destination.ts:26-31`: one directory per pick under the server's
 * temp dir keeps the original filename without collisions —
 * `<tmp>/uploads/<uuid>/<filename>`. The server normalizes separators and
 * returns the resolved path. Blocking; call on an IO dispatcher.
 *
 * @param tmpDirectory `GET /api/info`'s `paths.tmp` — see [tempDirectory].
 */
fun V2Api.fsWriteUpload(
    filename: String,
    body: RequestBody,
    tmpDirectory: String,
    locationDirectory: String? = null,
): String = fsWrite(
    path = "$tmpDirectory/uploads/${UUID.randomUUID()}/$filename",
    body = body,
    locationDirectory = locationDirectory,
)

/**
 * `GET /api/info` → `paths.tmp`, the upload root [fsWriteUpload] expects
 * (`protocol/src/groups/server.ts:7-23`; **bare** envelope — no `data`). Blank
 * when the server hides it. `UNVERIFIED` live — `/api/info` answered the SPA
 * shell on 1.18.31 and is a 2.x route.
 */
fun V2Api.tempDirectory(): String =
    parseObject(callBody(builder("info", false).get().build()))
        .optJSONObject("paths")
        ?.optString("tmp", "")
        .orEmpty()

/**
 * Non-buffering octet-stream body over an open stream: a hundreds-of-MB SAF
 * pick is uploaded by chunks and must never be slurped (see
 * `AttachmentPolicy.readCapped` for the OOM an unbounded read causes). The
 * stream is closed when the body has been written.
 */
fun InputStream.octetStreamBody(): RequestBody = object : RequestBody() {
    override fun contentType(): MediaType? = OCTET_STREAM

    override fun writeTo(sink: BufferedSink) {
        use { sink.writeAll(it.source()) }
    }
}
