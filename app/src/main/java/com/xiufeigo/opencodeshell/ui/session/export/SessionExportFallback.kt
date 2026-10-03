package com.xiufeigo.opencodeshell.ui.session.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore

/**
 * Where one export attempt ended. [Saved.viaFallback] tells the Download/
 * direct write apart from the SAF picker path; [Cancelled] is the user
 * dismissing the picker; [Failed] exhausted every path.
 *
 * All three members reach the caller — the silent dismiss is what wedged
 * `exportWorking` (see [SessionExportSaver.deliver]). `ui/home/HomeRowActions.kt`
 * matches the members **by name** (`ExportFinish.Cancelled ->`, no `is`), so
 * the shape is a fixed cross-file contract: adding a member breaks that
 * `when`, and the new member must carry its own copy there (and in
 * [ExportFeedback]) before it can ship.
 */
sealed interface ExportFinish {
    data class Saved(val filename: String, val viaFallback: Boolean = false) : ExportFinish
    data object Cancelled : ExportFinish
    data object Failed : ExportFinish
}

/**
 * Pure routing of a SAF picker answer: no picker answer is a cancel, an
 * answered picker reports its write result. The Download/ fallback decision
 * lives in [SessionExportSaver] (`!picked` never falls back — the user said
 * no — while a failed write and a missing/throwing picker do).
 *
 * `picked = false` is the rule [SessionExportSaver.deliver] applies to a
 * dismissed dialog, so a cancel is one value `Cancelled`, not a missing
 * callback.
 */
fun routeSaveOutcome(picked: Boolean, writeOk: Boolean, filename: String): ExportFinish =
    if (!picked) {
        ExportFinish.Cancelled
    } else if (writeOk) {
        ExportFinish.Saved(filename)
    } else {
        ExportFinish.Failed
    }

/**
 * The SAF-bypass path for session export (P0: the `ACTION_CREATE_DOCUMENT`
 * picker hangs or is missing on some desktop-mode builds, wedging the UI with
 * no callback ever arriving).
 *
 * The fallback writes straight to the public Download/ collection through
 * `MediaStore`, which needs no picker UI and — on API 29+ — no permission.
 * Everything Android-touching funnels through [writeToDownloads]; the column
 * map ([downloadEntry]) and the timeout stay pure so they are JVM-testable.
 */
object SessionExportFallback {

    const val MIME_TYPE = "application/json"

    /** `MediaStore` relative directory for the bypass write, trailing slash included. */
    const val DOWNLOAD_DIR = "Download/"

    /**
     * How long the SAF picker may stay unanswered before the saver reclaims the
     * request and falls back to Download/. Generous on purpose: a user browsing
     * for a folder is not a hang, and a late picker answer after the reclaim is
     * dropped, never double-written.
     */
    const val SAF_TIMEOUT_MS = 60_000L

    /**
     * The `MediaStore` insert columns for one download, as plain strings so
     * unit tests need no Android runtime. Keys are the `MediaColumns` values
     * (`_display_name`, `mime_type`, `relative_path`); the directory is the
     * literal `Download/` `MediaStore` expects (trailing slash) — spelled out
     * rather than read from `Environment.DIRECTORY_DOWNLOADS`, whose stub
     * value is `null` under plain JVM unit tests.
     */
    fun downloadEntry(filename: String): Map<String, String> = mapOf(
        MediaStore.MediaColumns.DISPLAY_NAME to filename,
        MediaStore.MediaColumns.MIME_TYPE to MIME_TYPE,
        MediaStore.MediaColumns.RELATIVE_PATH to DOWNLOAD_DIR,
    )

    /**
     * True when some activity answers `ACTION_CREATE_DOCUMENT` for our mime
     * type. False means launching the picker would throw or hang, so the
     * caller must skip SAF and go straight to [writeToDownloads].
     *
     * Android 11+ package visibility: `queryIntentActivities` only reports
     * handlers the app may see, which without a matching `<queries>` entry is
     * the app's own package plus the auto-visible system components. A false
     * negative here is safe (the export still lands in `Download/` and the
     * caller reports [ExportFeedback.SAVED_TO_DOWNLOADS]) but it costs the
     * user the picker; `AndroidManifest.xml` declares `ACTION_VIEW` and
     * `ACTION_SEND` only, so an `ACTION_CREATE_DOCUMENT` query is what makes
     * this check tell the truth on such a build.
     */
    fun hasSaveHandler(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MIME_TYPE
        }
        val pm = context.packageManager
        val hits = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return hits.isNotEmpty()
    }

    /**
     * Writes [json] to Download/[filename] without any picker UI. Never
     * throws: every failure (missing collection pre-Q without the legacy
     * permission, denied insert, dead stream) is a plain `false`.
     */
    fun writeToDownloads(context: Context, filename: String, json: String): Boolean {
        val entry = downloadEntry(filename)
        return runCatching {
            val resolver = context.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    entry.forEach { (key, value) -> put(key, value) }
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                resolver.openOutputStream(uri)?.use { stream ->
                    stream.write(json.toByteArray(Charsets.UTF_8))
                    stream.flush()
                } ?: return false
                true
            } else {
                @Suppress("DEPRECATION")
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, entry.getValue(MediaStore.MediaColumns.DISPLAY_NAME))
                    put(MediaStore.MediaColumns.MIME_TYPE, entry.getValue(MediaStore.MediaColumns.MIME_TYPE))
                }) ?: return false
                resolver.openOutputStream(uri)?.use { stream ->
                    stream.write(json.toByteArray(Charsets.UTF_8))
                    stream.flush()
                } ?: return false
                true
            }
        }.getOrDefault(false)
    }
}
