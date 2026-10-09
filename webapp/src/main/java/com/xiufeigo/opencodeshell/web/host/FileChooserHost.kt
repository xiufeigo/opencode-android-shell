package com.xiufeigo.opencodeshell.web.host

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * 文件选择 / 保存（SAF）。最小可用版。
 *
 * 两条路：
 * - **选**：`WebChromeClient.onShowFileChooser` → [FileChooserParams] 的
 *   `ACTION_GET_CONTENT` 或 `ACTION_OPEN_DOCUMENT`。`file://` 与 `content://` 都能收。
 * - **存**：桥上 `saveContent(name, mime, base64)` → [ACTION_CREATE_DOCUMENT]，
 *   用户选完目标再把 base64 解码写进去。
 *
 * 为什么不自己造 `file://`：宿主 origin 是 https，页面拿不到 `file://` 读权限，
 * SAF 的 `content://` 才是跨进程正确的句柄。
 *
 * 标准 file input 接收选中的 URI 后，由 WebView 暴露为 File，页面直接读取字节。
 * [MAX_INLINE_BYTES] 只限制旧桥的 readInline 路径，不限制标准 File 导入。
 */
class FileChooserHost(private val activity: AppCompatActivity) {
    private val writer = Executors.newSingleThreadExecutor()
    private var closed = false

    companion object {
        private const val TAG = "OcWebHost/FileChooser"
        const val MAX_INLINE_BYTES = 1L shl 20 // 1 MiB
    }

    /**
     * 单个 Uri 的元数据（不含字节）。
     *
     * 名字/大小是**可选**的：SAF 返回的 Uri 不保证有 `OpenableColumns`，
     * 查不到就是 `null`，页面要自己兜底。
     */
    fun describe(uri: Uri): JSONObject = JSONObject().apply {
        put("uri", uri.toString())
        put("name", queryName(uri))
        put("size", querySize(uri))
    }

    private fun queryName(uri: Uri): String? = runCatching {
        activity.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
    }.getOrNull()

    private fun querySize(uri: Uri): Long? = runCatching {
        activity.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (i >= 0 && c.moveToFirst() && !c.isNull(i)) c.getLong(i) else null
            }
    }.getOrNull()

    /** 待写入的 base64（saveContent 发起时暂存，SAF 回目标后消费）。 */
    private var pendingSaveBase64: String? = null
    private var pendingSaveMime: String = "application/octet-stream"

    private var onPickResult: ((List<Uri>) -> Unit)? = null
    private var onSaved: ((JSONObject) -> Unit)? = null

    val openLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (closed) return@registerForActivityResult
            val cb = onPickResult
            onPickResult = null
            if (res.resultCode != Activity.RESULT_OK) {
                cb?.invoke(emptyList())
                return@registerForActivityResult
            }
            val clip = res.data?.clipData
            val uris = when {
                clip != null && clip.itemCount > 0 ->
                    (0 until clip.itemCount).map { clip.getItemAt(it).uri }
                else -> listOfNotNull(res.data?.data)
            }
            cb?.invoke(uris)
        }

    val saveLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (closed) return@registerForActivityResult
            val b64 = pendingSaveBase64
            val mime = pendingSaveMime
            pendingSaveBase64 = null
            val cb = onSaved
            onSaved = null
            val out = JSONObject()
            if (res.resultCode != Activity.RESULT_OK || b64 == null) {
                out.put("ok", false).put("cancelled", true)
                cb?.invoke(out)
                return@registerForActivityResult
            }
            val target = res.data?.data
            if (target == null) {
                out.put("ok", false).put("error", "no target uri")
                cb?.invoke(out)
                return@registerForActivityResult
            }
            writer.execute {
                try {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    activity.contentResolver.openOutputStream(target, "wt")?.use { it.write(bytes) }
                        ?: throw IllegalStateException("openOutputStream returned null")
                    out.put("ok", true).put("uri", target.toString()).put("bytes", bytes.size)
                        .put("mime", mime)
                } catch (error: Exception) {
                    Log.w(TAG, "save failed", error)
                    out.put("ok", false).put("error", error.javaClass.simpleName + ": " + error.message)
                }
                activity.runOnUiThread { if (!closed) cb?.invoke(out) }
            }
        }

    /** 供 `WebChromeClient.onShowFileChooser` 调用。 */
    fun launchOpen(
        intent: Intent,
        callback: (List<Uri>) -> Unit,
    ) {
        check(!closed) { "File chooser is closed" }
        onPickResult = callback
        runCatching { openLauncher.launch(intent) }.onFailure { t ->
            Log.w(TAG, "open launch failed", t)
            onPickResult = null
            callback(emptyList())
        }
    }

    fun launchSave(suggestedName: String, mime: String, base64: String?, callback: (JSONObject) -> Unit) {
        check(!closed) { "File chooser is closed" }
        if (onSaved != null) {
            callback(JSONObject().put("ok", false).put("error", "Another save dialog is already open"))
            return
        }
        pendingSaveBase64 = base64
        pendingSaveMime = mime
        onSaved = callback
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }
        runCatching { saveLauncher.launch(intent) }.onFailure { t ->
            Log.w(TAG, "save launch failed", t)
            pendingSaveBase64 = null
            onSaved = null
            callback(JSONObject().put("ok", false).put("error", t.javaClass.simpleName))
        }
    }

    fun close() {
        closed = true
        onPickResult = null
        onSaved = null
        pendingSaveBase64 = null
        writer.shutdown()
    }

    /**
     * 读一个小文件并 inline 成 base64。
     *
     * 超过 [MAX_INLINE_BYTES] 立刻返回 `truncated=true` + 空 base64，而不是读一半
     * 给页面一个**看起来成功但内容被砍掉**的文件——那样比报错难查得多。
     */
    fun readInline(uri: Uri, maxBytes: Long = MAX_INLINE_BYTES): JSONObject {
        val out = describe(uri)
        val size = querySize(uri)
        if (size != null && size > maxBytes) {
            out.put("truncated", true).put("base64", "")
            return out
        }
        return try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    total += n
                    if (total > maxBytes) {
                        out.put("truncated", true).put("base64", "")
                        return out
                    }
                    buf.write(chunk, 0, n)
                }
                val bytes = buf.toByteArray()
                if (querySize(uri) == null) out.put("size", bytes.size.toLong())
                out.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            } ?: JSONObject().put("error", "openInputStream returned null")
        } catch (t: Throwable) {
            Log.w(TAG, "readInline failed", t)
            JSONObject().put("error", t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }
}
