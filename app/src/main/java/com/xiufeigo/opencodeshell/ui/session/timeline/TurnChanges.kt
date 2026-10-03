package com.xiufeigo.opencodeshell.ui.session.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcColors
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import org.json.JSONObject

/**
 * A6 — "this turn's changed files" (`N Changed files` + `Show all`).
 *
 * ## Upstream anchors
 *
 * - i18n: `ui.sessionTurn.diffs.changed` (`{{count}} Changed files`),
 *   `ui.sessionTurn.diffs.showAll` / `.showLess` / `.more` (`+{{count}} more
 *   files`) — `_upstream/packages/ui/src/i18n/en.ts:62-66`. The count is
 *   rendered as a plural (`app/src/session/summary/panel.tsx:105`).
 * - layout/styling: `session-ui/src/components/session-turn.css:137-206` —
 *   `[data-slot=session-turn-diffs-header]` (44 px, 8 px gap, sticky),
 *   `[data-slot=session-turn-diffs-label]` (medium, `text-strong`),
 *   `[data-slot=session-turn-diffs-toggle]` (`text-interactive-base`),
 *   `[data-component=session-turn-diffs-group][data-show-all]` (expanded state),
 *   `[data-slot=session-turn-diffs-more]` (`text-weak`, small),
 *   `[data-component=session-turn-diffs-content]`, and the per-file row
 *   `session-turn-diff-filename` (medium `text-strong` over
 *   `session-turn-diff-directory` (`text-base`, ellipsized from the left)).
 * - the expansion semantic (`showAll`, `+N more files`) is the one the tool card
 *   uses: `session-ui/src/tools/tool-renderer.tsx:439-480`
 *   (`showAll || all.length <= 10`, then a `{…} more` row).
 *
 * ## Data gap (why this is path-only)
 *
 * The upstream block is fed by `session.diff` (`FileDiffInfo`: `file`, `patch`,
 * `additions`, `deletions`, `status` — `components/apply-patch-file.ts:17-26`).
 * `ChatStore.UiMessage`/`UiPart` never carry it (`ChatStore.kt:67-92`) and
 * `ChatStore` is out of this task's scope, so per-file additions/deletions (and
 * the diff bars of the message rail, `message-nav.tsx:109-150`) cannot be shown.
 * The file **paths** are recoverable from two in-scope sources:
 *
 * 1. the edit-family tool parts — `edit` / `write` / `patch` / `apply_patch`
 *    (`session-ui/src/timeline/detail.ts:96`, the same set
 *    [ToolPresentation.isEditLike] uses) and their `state.input` JSON
 *    (`filePath` / `path` / `apply_patch`'s `files[].path` /
 *    `patchText` headers);
 * 2. the `patch` **part** (`schema/src/v1/session.ts:94-100`
 *    `{ hash, files: string[] }`), read by [RawParts.patch].
 *
 * Nothing is invented: a turn with no recoverable path renders no block.
 */
object TurnChanges {

    /** `["edit", "write", "patch", "apply_patch"]` (`timeline/detail.ts:96`). */
    val EDIT_TOOLS = setOf("edit", "write", "patch", "apply_patch")

    /**
     * `tool-renderer.tsx:443-444`: the collapsed list stops at 10 entries and
     * the remainder collapses into a `+{n} more files` row.
     */
    const val COLLAPSED_MAX = 10

    /** `state.input` keys that carry the touched path, in probe order. */
    private val PATH_KEYS = listOf("filePath", "file_path", "path", "file")

    /** `apply_patch` ships `{"files":[{"path":…,"patch":…}]}`. */
    private const val FILES_KEY = "files"

    /** `*** Update File: x` / `+++ b/x` headers of a unified patch. */
    private val PATCH_HEADER = Regex("^(?:\\*\\*\\* (?:Update|Add|Delete) File: |\\+\\+\\+ b/)(\\S+)")

    /**
     * Distinct changed-file paths of one assistant turn, in order of first
     * appearance (tool parts in part order, then `patch` parts).
     */
    fun of(message: ChatStore.UiMessage): List<String> {
        val paths = LinkedHashSet<String>()
        message.parts.forEach { paths += pathsOf(it) }
        return paths.toList()
    }

    /** The paths a single part claims to have touched (empty for other parts). */
    fun pathsOf(part: ChatStore.UiPart): List<String> = when {
        part.type == "tool" && part.toolName in EDIT_TOOLS -> toolPaths(part)
        part.type == "patch" -> RawParts.patch(part.rawJson)?.files.orEmpty()
        else -> emptyList()
    }

    private fun toolPaths(part: ChatStore.UiPart): List<String> {
        val input = ToolPresentation.parseObject(part.toolInput) ?: return emptyList()
        val paths = LinkedHashSet<String>()
        paths += fileArrayPaths(input)
        for (key in PATH_KEYS) {
            val value = input.optStringOrNull(key)?.trim().orEmpty()
            if (value.isNotEmpty()) paths += value
        }
        if (paths.isEmpty()) {
            for (text in patchTexts(input)) paths += patchHeaderPaths(text)
        }
        return paths.toList()
    }

    /** `files: [{ path | filePath | file }]` (the `apply_patch` shape). */
    private fun fileArrayPaths(input: JSONObject): List<String> {
        val files = input.optJSONArray(FILES_KEY) ?: return emptyList()
        val paths = ArrayList<String>(files.length())
        for (i in 0 until files.length()) {
            val entry = files.optJSONObject(i) ?: continue
            for (key in PATH_KEYS) {
                val value = entry.optStringOrNull(key)?.trim().orEmpty()
                if (value.isNotEmpty()) {
                    paths += value
                    break
                }
            }
        }
        return paths
    }

    /** `apply_patch`'s unified diff text: `patchText`, or each `files[].patch`. */
    private fun patchTexts(input: JSONObject): List<String> {
        val texts = ArrayList<String>(1)
        val direct = input.optStringOrNull("patchText")?.trim().orEmpty()
        if (direct.isNotEmpty()) texts += direct
        val files = input.optJSONArray(FILES_KEY) ?: return texts
        for (i in 0 until files.length()) {
            val patch = files.optJSONObject(i)?.optStringOrNull("patch")?.trim().orEmpty()
            if (patch.isNotEmpty()) texts += patch
        }
        return texts
    }

    /**
     * `*** Update File: path` / `+++ b/path` headers; `/dev/null` and the `b/`
     * prefix are dropped so the printed path matches the other sources.
     */
    private fun patchHeaderPaths(patchText: String): List<String> {
        val paths = ArrayList<String>(2)
        for (line in ToolPresentation.splitLines(patchText)) {
            val match = PATCH_HEADER.find(line.trim()) ?: continue
            val path = match.groupValues[1].trim()
            if (path == "/dev/null") continue
            paths += path
        }
        return paths
    }

    /** How many paths the collapsed/expanded list shows (`tool-renderer.tsx:443`). */
    fun visibleCount(total: Int, expanded: Boolean): Int =
        if (expanded) total else total.coerceAtMost(COLLAPSED_MAX)

    /** `+{n} more files` count of the collapsed list. */
    fun hiddenCount(total: Int, expanded: Boolean): Int = total - visibleCount(total, expanded)
}

/**
 * The trailing block of an assistant turn: `{n} Changed files` + the toggle and
 * the file list (`Show all` / `Show less`, `+{n} more files`).
 *
 * Rendered by `AssistantMessageRow` after the turn's parts, which is where
 * upstream keeps `[data-slot=session-turn-diffs]` (`session-turn.css:137`).
 * Deviations, both recorded in `research/wave5_timeline.md`: the header is not
 * sticky (`position:sticky` + the 44 px row would fight the timeline's own
 * pinned session header) and the per-file chevron / diff body of
 * `session-turn-diff-view` (`:254-277`) is omitted — the Android data has no
 * per-file patch.
 */
@Composable
internal fun TurnChangesBlock(
    files: List<String>,
    messageId: String,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) return
    val c = OcTheme.colors
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    val expandable = files.size > TurnChanges.COLLAPSED_MAX
    val shown = remember(files, expanded) { files.take(TurnChanges.visibleCount(files.size, expanded)) }
    val hidden = TurnChanges.hiddenCount(files.size, expanded)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HEADER_GAP),
        ) {
            Text(
                text = pluralStringResource(R.plurals.oc_turn_changed_files, files.size, files.size),
                color = c.textBase,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (expandable) {
                Text(
                    text = stringResource(
                        if (expanded) R.string.oc_turn_show_less else R.string.oc_turn_show_all,
                    ),
                    color = c.textAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W400,
                    maxLines = 1,
                    modifier = Modifier
                        .clickable(role = Role.Button) { expanded = !expanded }
                        .padding(vertical = 2.dp),
                )
            }
        }
        shown.forEach { path -> TurnChangeRow(path) }
        if (hidden > 0) {
            // `[data-slot=session-turn-diffs-more]` — small, weak, clickable.
            Text(
                text = pluralStringResource(R.plurals.oc_turn_more_files, hidden, hidden),
                color = c.textFaint,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable(role = Role.Button) { expanded = true }
                    .padding(top = 4.dp, bottom = 2.dp),
            )
        }
    }
}

/**
 * One changed-file row: the filename in medium `textBase` after its
 * `textMuted` directory (`session-turn.css:217-245`), one ellipsized line.
 */
@Composable
private fun TurnChangeRow(path: String) {
    val c = OcTheme.colors
    val annotated = remember(path, c) { filePathAnnotated(path, c) }
    Text(
        text = annotated,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** `directory` muted + `filename` strong — the two CSS slots of one row. */
private fun filePathAnnotated(path: String, colors: OcColors): AnnotatedString {
    val slash = path.lastIndexOf('/')
    val directory = if (slash < 0) "" else path.substring(0, slash + 1)
    val name = if (slash < 0) path else path.substring(slash + 1)
    return buildAnnotatedString {
        if (directory.isNotEmpty()) withStyle(SpanStyle(color = colors.textMuted)) { append(directory) }
        withStyle(SpanStyle(color = colors.textBase, fontWeight = FontWeight.Medium)) { append(name) }
    }
}

/** `[data-slot=session-turn-diffs-header] gap: 8px`. */
private val HEADER_GAP = 8.dp
