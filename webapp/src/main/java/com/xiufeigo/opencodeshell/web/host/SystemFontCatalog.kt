package com.xiufeigo.opencodeshell.web.host

import android.graphics.fonts.SystemFonts
import android.os.Build
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** System fonts are addressed only by opaque IDs returned by the platform bridge. */
class SystemFontCatalog {
    companion object {
        const val URL_PREFIX = "/oc-system-font/"
        const val ID_PREFIX = "ocsys-"
        private const val ACTIVE_MIUI_FONT = "/data/system/fonts/theme_webview/Roboto-Regular.ttf"
    }

    @Volatile private var entries: Map<String, SystemFontEntry> = emptyMap()

    @Synchronized
    fun list(): JSONArray {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("System font enumeration requires Android 10 or newer")
        }
        if (entries.isEmpty()) entries = enumerate()
        return JSONArray().apply {
            entries.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).forEach {
                put(JSONObject().put("id", it.id).put("name", it.name))
            }
        }
    }

    fun open(id: String): InputStream? = entries[id]?.open()

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun enumerate(): Map<String, SystemFontEntry> {
        // TTC files are deliberately one option: WebView renders the collection's first face.
        val activePath = File(ACTIVE_MIUI_FONT).takeIf { it.isFile && it.canRead() }?.canonicalPath
        val system = SystemFonts.getAvailableFonts().associateBy { font ->
            requireNotNull(font.file) { "System font has no file identity" }.canonicalPath
        }.filter { (path, font) ->
            path == activePath || (requireNotNull(font.file).path.startsWith("/system/fonts/") && font.localeList.isEmpty)
        }.map { (path, font) ->
            val id = idFor(path)
            SystemFontEntry(id, displayName(File(path).name)) { FontBufferInputStream(font.buffer.asReadOnlyBuffer().apply { position(0) }) }
        }.toMutableList()
        if (activePath != null && system.none { it.id == idFor(activePath) }) {
            val file = File(activePath)
            system += SystemFontEntry(idFor(activePath), displayName(file.name)) { FileInputStream(file) }
        }
        return system.associateBy { it.id }.also { check(it.isNotEmpty()) { "No system fonts are available" } }
    }

    private fun displayName(fileName: String): String {
        if (fileName.equals("MiSansVF.ttf", ignoreCase = true)) return "MiSans"
        return fileName
    }

    private fun idFor(path: String): String = ID_PREFIX + MessageDigest.getInstance("SHA-256")
        .digest(path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal class SystemFontEntry(val id: String, val name: String, private val openStream: () -> InputStream) {
    fun open(): InputStream = openStream()
}

internal class FontBufferInputStream(private val buffer: ByteBuffer) : InputStream() {
    override fun read(): Int = if (buffer.hasRemaining()) buffer.get().toInt() and 0xff else -1

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > target.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (!buffer.hasRemaining()) return -1
        val count = minOf(length, buffer.remaining())
        buffer.get(target, offset, count)
        return count
    }

    override fun available(): Int = buffer.remaining()
}
