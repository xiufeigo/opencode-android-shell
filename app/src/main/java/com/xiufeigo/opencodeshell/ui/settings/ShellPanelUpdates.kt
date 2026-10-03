package com.xiufeigo.opencodeshell.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import org.json.JSONArray
import org.json.JSONObject

/**
 * The What's-New / update surface: pure changelog projection +
 * `shell/updates/{release-notes,highlights,download}.tsx` dialogs.
 *
 * Nothing here talks to the network or to the platform updater — upstream
 * splits that across `highlights.tsx` (fetch `https://opencode.ai/changelog.json`
 * on version change), `release-notes.tsx` (the dialog) and `download.tsx`
 * (the stable-migration confirm). The native shell ships the dialog + the pure
 * projection first; hooking the fetch and the `updater.install()` call is the
 * host layer's job (`ui/App.kt` keeps its log-only stub until then).
 */

// ------------------------------------------------------ highlights (pure)

/** Upstream `Highlight` (`shell/updates/release-notes.tsx:7-14`). */
data class ReleaseHighlight(
    val title: String,
    val description: String,
    val media: ReleaseHighlightMedia? = null,
)

/**
 * Upstream `Highlight["media"]`. The native dialog draws the text half only —
 * the project has no image/video loader — so the field is parsed and retained
 * (dedupe keys include it) but not painted.
 */
data class ReleaseHighlightMedia(
    /** `image` | `video` (`highlights.tsx` `parseMedia`). */
    val type: String,
    val src: String,
    val alt: String? = null,
)

/** One changelog entry after `parseRelease` (`highlights.tsx`). */
data class ParsedRelease(val tag: String?, val highlights: List<ReleaseHighlight>)

/** `getText` (`highlights.tsx:36-45`): trimmed non-empty string, or a number. */
private fun getText(value: Any?): String? = when (value) {
    is String -> value.trim().takeIf { it.isNotEmpty() }
    is Number -> value.toString()
    else -> null
}

/** `normalizeVersion` (`highlights.tsx:47-51`): strips one leading `v`/`V`. */
fun normalizeReleaseVersion(value: String?): String? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (text.startsWith("v") || text.startsWith("V")) text.substring(1) else text
}

/** `parseMedia` (`highlights.tsx:53-62`). */
fun parseHighlightMedia(value: Any?, alt: String): ReleaseHighlightMedia? {
    if (value !is JSONObject) return null
    val type = getText(value.opt("type"))?.lowercase() ?: return null
    val src = getText(value.opt("src")) ?: getText(value.opt("url")) ?: return null
    if (type != "image" && type != "video") return null
    return ReleaseHighlightMedia(type = type, src = src, alt = alt)
}

/** `parseHighlight` (`highlights.tsx:64-75`). */
fun parseHighlight(value: Any?): ReleaseHighlight? {
    if (value !is JSONObject) return null
    val title = getText(value.opt("title")) ?: return null
    val description = getText(value.opt("description"))
        ?: getText(value.opt("shortDescription"))
        ?: return null
    return ReleaseHighlight(
        title = title,
        description = description,
        media = parseHighlightMedia(value.opt("media"), alt = title),
    )
}

/**
 * `parseRelease` (`highlights.tsx:77-108`): only groups whose `source`
 * mentions `desktop` contribute, via `group.items` or the group itself.
 */
fun parseRelease(value: Any?): ParsedRelease? {
    if (value !is JSONObject) return null
    val tag = getText(value.opt("tag"))
        ?: getText(value.opt("tag_name"))
        ?: getText(value.opt("name"))
    val raw = value.opt("highlights")
    if (raw !is JSONArray) return ParsedRelease(tag, emptyList())
    val highlights = buildList {
        for (i in 0 until raw.length()) {
            val group = raw.opt(i) as? JSONObject ?: continue
            val source = getText(group.opt("source")) ?: continue
            if (!source.lowercase().contains("desktop")) continue
            val items = group.opt("items")
            if (items is JSONArray) {
                for (j in 0 until items.length()) {
                    parseHighlight(items.opt(j))?.let(::add)
                }
            } else {
                parseHighlight(group)?.let(::add)
            }
        }
    }
    return ParsedRelease(tag, highlights)
}

/**
 * `parseChangelog` (`highlights.tsx:110-119`): a top-level array of releases,
 * or `{ releases: [...] }`.
 */
fun parseChangelog(value: Any?): List<ParsedRelease>? {
    if (value is JSONArray) {
        return (0 until value.length()).mapNotNull { parseRelease(value.opt(it)) }
    }
    if (value !is JSONObject) return null
    val releases = value.opt("releases") as? JSONArray ?: return null
    return (0 until releases.length()).mapNotNull { parseRelease(releases.opt(it)) }
}

/** `dedupeKey` (`highlights.tsx:133-135`). */
fun releaseHighlightKey(highlight: ReleaseHighlight): String = listOf(
    highlight.title,
    highlight.description,
    highlight.media?.type ?: "",
    highlight.media?.src ?: "",
).joinToString("\n")

/**
 * `sliceHighlights` (`highlights.tsx:121-131`): releases in `[current, previous)`
 * (open-ended at either side), deduped, first 5.
 */
fun sliceReleaseHighlights(
    releases: List<ParsedRelease>,
    current: String?,
    previous: String?,
): List<ReleaseHighlight> {
    val from = normalizeReleaseVersion(current)
    val to = normalizeReleaseVersion(previous)
    val start = if (from == null) {
        0
    } else {
        releases.indexOfFirst { normalizeReleaseVersion(it.tag) == from }.let { if (it == -1) 0 else it }
    }
    val end = if (to == null) {
        releases.size
    } else {
        (start until releases.size).firstOrNull { normalizeReleaseVersion(releases[it].tag) == to }
            ?: releases.size
    }
    val seen = mutableSetOf<String>()
    return releases.subList(start, end)
        .flatMap { it.highlights }
        .filter { seen.add(releaseHighlightKey(it)) }
        .take(5)
}

/** `loadReleaseHighlights` (`highlights.tsx:137-141`). */
fun loadReleaseHighlights(value: Any?, current: String?, previous: String?): List<ReleaseHighlight> {
    val releases = parseChangelog(value)?.takeIf { it.isNotEmpty() } ?: return emptyList()
    return sliceReleaseHighlights(releases, current, previous)
}

// ------------------------------------------------- launch decision (pure)

/**
 * What a launch with version change should do, per `highlights.tsx`
 * `createEffect` + `start` (`:186-230`):
 *
 * | condition | upstream | here |
 * |---|---|---|
 * | no `platform.version` | wait | [SKIP] |
 * | no stored version | store current, no dialog | [RECORD_ONLY] |
 * | stored == current (raw `===`) | nothing | [SKIP] |
 * | `general.releaseNotes` off | `markSeen()`, no fetch | [RECORD_ONLY] |
 * | else | fetch changelog → dialog if any highlight | [CHECK_CHANGELOG] |
 */
enum class ReleaseNotesLaunch {
    SKIP,
    RECORD_ONLY,
    CHECK_CHANGELOG,
}

/** The decision table above — pure, JVM-testable. */
fun releaseNotesLaunch(
    enabled: Boolean,
    lastSeenVersion: String?,
    currentVersion: String?,
): ReleaseNotesLaunch {
    if (currentVersion.isNullOrBlank()) return ReleaseNotesLaunch.SKIP
    if (lastSeenVersion == null) return ReleaseNotesLaunch.RECORD_ONLY
    // Upstream compares the raw strings (`previous === platform.version`), not
    // the normalized pair `sliceHighlights` uses.
    if (lastSeenVersion == currentVersion) return ReleaseNotesLaunch.SKIP
    return if (enabled) ReleaseNotesLaunch.CHECK_CHANGELOG else ReleaseNotesLaunch.RECORD_ONLY
}

/** `total`/`last`/`feature` paging of `DialogReleaseNotes` (`release-notes.tsx:28-34`). */
fun releaseNotesLastIndex(total: Int): Int = maxOf(0, total - 1)

/** Index fallback when the cursor walked off the list (`highlights[index] ?? highlights[last]`). */
fun releaseNotesFeatureIndex(index: Int, total: Int): Int =
    if (total > 0 && index in 0 until total) index else releaseNotesLastIndex(total)

// ------------------------------------------------------ dialogs (Compose)

/**
 * `DialogReleaseNotes` (`shell/updates/release-notes.tsx:22-163`) — the text
 * half. Media is parsed but not drawn (no loader in this shell); the paging
 * indicator and the three actions match upstream 1:1:
 * `Next` until the last highlight, then `Get started`, plus the persistent
 * `Don't show these in the future` (→ `settings.general.setReleaseNotes(false)`,
 * wired by the host).
 *
 * Upstream's left/right arrow paging and Escape-to-close ride the dialog
 * container's key handling (`release-notes.tsx:47-67`); [DialogController]
 * owns back/escape dismissal for every native dialog, so this stays pointer-only.
 */
@Composable
fun DialogReleaseNotes(
    highlights: List<ReleaseHighlight>,
    onClose: () -> Unit,
    onHideFuture: () -> Unit,
) {
    val c = OcTheme.colors
    var index by remember(highlights) { mutableStateOf(0) }
    val total = highlights.size
    val last = releaseNotesLastIndex(total)
    val feature = highlights.getOrNull(releaseNotesFeatureIndex(index, total))
    val isLast = index >= last
    val paged = total > 1

    Column(
        Modifier
            .fillMaxWidth()
            .background(c.bgBase)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = feature?.title.orEmpty(),
            color = c.textBase,
            fontSize = 16.sp,
            fontWeight = FontWeight(530),
        )
        Text(
            text = feature?.description.orEmpty(),
            color = c.textBase,
            fontSize = 14.sp,
            fontWeight = FontWeight(400),
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(12.dp))
        if (isLast) {
            OcButton(
                label = stringResource(R.string.dialog_releaseNotes_action_getStarted),
                primary = true,
                onClick = onClose,
            )
        } else {
            OcButton(
                label = stringResource(R.string.dialog_releaseNotes_action_next),
                primary = true,
                onClick = { index++ },
            )
        }
        OcButton(
            label = stringResource(R.string.dialog_releaseNotes_action_hideFuture),
            primary = false,
            onClick = onHideFuture,
        )
        if (paged) {
            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (i in 0 until total) {
                    val current = i == index
                    Box(
                        Modifier
                            .width(if (current) 32.dp else 12.dp)
                            .height(2.dp)
                            .background(
                                if (current) c.iconBase else c.iconFaint,
                                RoundedCornerShape(1.dp),
                            )
                            .clickable(role = Role.Button) { index = i },
                    )
                }
            }
        }
    }
}

/**
 * `DialogStableDownload` (`shell/updates/download.tsx:32-59`) — the beta →
 * stable migration confirm ahead of `updater.install()` (`useUpdaterInstall`).
 * The download action is the host's (`platform.updater.install` has no native
 * counterpart yet), so both ends are callbacks.
 */
@Composable
fun DialogStableDownload(
    version: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = OcTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.bgBase)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_updates_migration_title),
            color = c.textBase,
            fontSize = 15.sp,
            fontWeight = FontWeight(640),
        )
        Text(
            text = stringResource(R.string.settings_updates_migration_description, version),
            color = c.textMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight(440),
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OcButton(
                label = stringResource(R.string.common_cancel),
                primary = false,
                onClick = onCancel,
            )
            OcButton(
                label = stringResource(R.string.settings_updates_action_download),
                primary = true,
                onClick = onDownload,
            )
        }
    }
}
