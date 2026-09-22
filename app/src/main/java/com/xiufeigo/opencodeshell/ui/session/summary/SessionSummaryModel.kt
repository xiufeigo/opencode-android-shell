package com.xiufeigo.opencodeshell.ui.session.summary

/**
 * Row/segment model for the session summary panel (A1).
 *
 * Upstream renders it as a popover with two cards
 * (`_upstream/packages/app/src/session/summary/panel.tsx:44-142`): a **project
 * card** with workspace / branch / changes / background-task rows, and a
 * **server card** (`summary/server-panel.tsx:66-101`). This object owns the pure
 * part — which rows exist, in what order, and how their values are derived — so
 * the rules are JVM-testable; the composable
 * ([SessionSummaryPanel]) owns only layout and copy.
 *
 * Deliberate gaps (see `research/wave6_summary.md`):
 * - the **background-task row is not produced**: upstream derives it from
 *   assistant tool parts plus child-session state
 *   (`session/requests/background.ts:85-115`) and there is no endpoint for it,
 * - the server card lists a plain identity row set instead of upstream's four
 *   submenus (MCP / LSP / Plugins / Skills), because those read v2 APIs that
 *   this server does not expose (plugins) or need their own wave.
 */
object SessionSummaryModel {

    /** Row kind; the composable maps each to its icon/label resource. */
    enum class Kind { WORKSPACE, BRANCH, CHANGES, SERVER }

    /**
     * @param value already display-ready (may be long; the row ellipsizes)
     * @param mono paths/branches read as code, counts do not
     */
    data class Row(
        val kind: Kind,
        val value: String,
        val mono: Boolean,
    )

    /**
     * Project card rows in upstream order (`panel.tsx:48-114`): workspace,
     * branch, changes — each omitted when unknown rather than shown blank.
     */
    fun projectRows(
        directory: String?,
        branch: String?,
        changedFiles: Int?,
        additions: Int? = null,
        deletions: Int? = null,
    ): List<Row> = buildList {
        directoryLabel(directory)?.let { add(Row(Kind.WORKSPACE, it, mono = true)) }
        branchLabel(branch)?.let { add(Row(Kind.BRANCH, it, mono = true)) }
        changesLabel(changedFiles, additions, deletions)?.let { add(Row(Kind.CHANGES, it, mono = false)) }
    }

    /** Server card row: the resolved label, falling back to the raw URL. */
    fun serverRows(label: String?, url: String?): List<Row> {
        val value = label?.takeIf { it.isNotBlank() } ?: url?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(Row(Kind.SERVER, value, mono = false))
    }

    /** Last path segment, tolerating both separators (the app runs on Windows too). */
    fun directoryLabel(path: String?): String? {
        val trimmed = path?.trim()?.trimEnd('\\', '/') ?: return null
        if (trimmed.isEmpty()) return null
        val cut = maxOf(trimmed.lastIndexOf('\\'), trimmed.lastIndexOf('/'))
        return trimmed.substring(cut + 1).ifEmpty { trimmed }
    }

    /** `null` when the workspace is not a repository (the server reports `branch: null`). */
    fun branchLabel(branch: String?): String? = branch?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * `"3 files · +12 −4"`; the `+A −D` half only appears when the server sent
     * both counts, and a zero-change session yields `null` so the row disappears
     * instead of claiming "0 files".
     */
    fun changesLabel(files: Int?, additions: Int?, deletions: Int?): String? {
        val count = files ?: return null
        if (count <= 0) return null
        val base = "$count file" + if (count == 1) "" else "s"
        val both = additions != null && deletions != null
        if (!both) return base
        return "$base · +$additions \u2212$deletions"
    }
}
