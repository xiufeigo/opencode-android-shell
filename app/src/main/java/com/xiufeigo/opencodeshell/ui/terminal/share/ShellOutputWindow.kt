package com.xiufeigo.opencodeshell.ui.terminal.share

/**
 * The tail window the shell-output viewer keeps
 * (`tui/src/component/dialog-shell-output.tsx`: `PAGE_BYTES = 64 * 1024`,
 * "Earlier output omitted · showing recent output"). Combined stdout/stderr is
 * paged by absolute byte cursor; this holds the accumulated text, the next
 * cursor and the omitted flag while the viewer polls `V2Api.shellOutput`.
 * Pure — JVM-testable without a server.
 */
class ShellOutputWindow(private val pageBytes: Int = PAGE_BYTES) {

    /** Accumulated (already truncated) output. */
    var text: String = ""
        private set

    /** `true` once older output was dropped from the window. */
    var omitted: Boolean = false
        private set

    /** Absolute byte cursor for the next page. */
    var cursor: Long = 0L
        private set

    /**
     * Chooses the first page to load from the capture's total [size]: the last
     * [pageBytes] bytes, or everything when the capture is smaller. Returns the
     * start cursor for `shellOutput(id, cursor, limit)`.
     */
    fun prime(size: Long): Long {
        val start = (size - pageBytes).coerceAtLeast(0L)
        omitted = start > 0L
        cursor = start
        text = ""
        return start
    }

    /**
     * Appends one `Shell.Output` page and returns `true` while more remains
     * (`cursor < size` — poll immediately; `false` = caught up).
     */
    fun append(output: String, cursor: Long, size: Long): Boolean {
        val next = text + output
        if (next.length > pageBytes) omitted = true
        text = if (next.length > pageBytes) next.takeLast(pageBytes) else next
        this.cursor = cursor
        return cursor < size
    }

    companion object {
        /** Upstream `PAGE_BYTES`. */
        const val PAGE_BYTES: Int = 64 * 1024

        /**
         * `cursor` of the size probe: a caught-up page answers
         * `{output:"", cursor:size, size}` (`core/src/shell.ts:198-199`), which
         * is how the viewer learns the capture size before loading the tail
         * (upstream sends `Number.MAX_SAFE_INTEGER`).
         */
        const val CATCHUP_CURSOR: Long = 9007199254740991L
    }
}
