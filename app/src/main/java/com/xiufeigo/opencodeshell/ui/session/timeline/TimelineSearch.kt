package com.xiufeigo.opencodeshell.ui.session.timeline

import androidx.compose.runtime.mutableStateOf
import com.xiufeigo.opencodeshell.ChatStore
import java.util.Locale

/**
 * A2 — in-session search (`mod+f`), the Android port of
 * `_upstream/packages/app/src/session/timeline/search-controller.ts` and
 * `search-bar.tsx`.
 *
 * ## The upstream contract, line by line
 *
 * ```
 * query     = value.trim().toLowerCase()                     search-controller.ts:94
 * matches   = for each message (user|assistant), for each text part:
 *               every non-overlapping occurrence of query    :96-135
 *               occurrence counts inside the *part*          :116-131
 * active     = clamped 0-based index, 0 when out of range     :137-143
 * setValue   = jump to the first match                        :207-219
 * move(+/-1) = (active + delta + len) % len                   :242-252
 * close()    = value/active/visible reset                     :202-205
 * placeholder= "Find..."    (session.search.placeholder)      app/runtime/i18n/en.ts:852
 * noResults  = "No matches" (session.search.noResults)        :853
 * ```
 *
 * The text surface is `[data-slot="text-part-body"]` +
 * `[data-slot="user-message-text"]` (`:19`), i.e. **text parts only** — a
 * reasoning body or a tool body is never searched. The tool-name surface below
 * is the one documented Android extension of this task (Wave 5-T A2 asks for
 * “消息文本、工具名”), because a tool card's title is the only stable "text" a
 * tool part has: it matches the raw name (`read`, `grep`) and the English card
 * title, both resource-free. Localized tool titles (`oc_tool_edit` → “编辑”)
 * are **not** searchable from here — the pure matcher has no `Resources`; see
 * `research/wave5_timeline.md` for the resolver hook a Lead can thread in.
 *
 * ## Why the state lives in an object
 *
 * Upstream creates **one** controller per session screen and registers it
 * globally: `command.register("session.search", …)` (`:184-192`) plus a
 * document-level `opencode:timeline-search-open` event (`:193-195`). The Android
 * shell has no screen-level parameter that reaches both the header and the
 * timeline (`ui/session/SessionScreen.kt` is out of scope for this wave), and on
 * a phone the header is rendered *outside* `MessageTimeline`
 * (`SessionScreen.kt:552` vs `:597`) while on desktop it is rendered *inside* it
 * (`:462`). A process-wide holder is the only shape that serves both, and it
 * mirrors upstream's global registration. The owner contract is:
 *
 * - `MessageTimeline` publishes the hit count and the "a timeline is mounted"
 *   flag, and consumes `open` / `query` / `active`;
 * - `SessionHeader` (via `ui/session/header/TimelineSearchBar.kt`) renders the
 *   entry button + the bar, and drives `open` / `query` / `active`;
 * - the timeline calls [reset] when it leaves the composition, so a search never
 *   survives a session switch (`search-controller.ts` closes with the screen).
 */
object TimelineSearch {

    /** What a hit matched: a `text` part body, or a tool card's title. */
    enum class HitKind { TEXT, TOOL }

    /**
     * One match. `occurrence` is the 0-based index of the occurrence **inside
     * its part** (`search-controller.ts:116-131`), which is what makes the
     * active one distinguishable when a part contains the same word twice.
     */
    data class Hit(
        val messageID: String,
        val partID: String,
        val occurrence: Int,
        val kind: HitKind,
        /** Char range inside the part text (null for a tool-title hit). */
        val range: IntRange? = null,
    )

    /** Downstream view of the hits the timeline paints. */
    data class Highlight(
        /** Parts with at least one hit (they get the hit surface). */
        val parts: Set<String>,
        /** The part of the active hit (it gets the active surface). */
        val activePart: String?,
        /** Normalized needle — the user-bubble span highlighter needs it. */
        val needle: String,
    ) {
        val isEmpty: Boolean get() = needle.isEmpty()

        companion object {
            val None = Highlight(emptySet(), null, "")
        }
    }

    // ------------------------------------------------------------ pure logic

    /** `query` (`search-controller.ts:94`): trimmed + case-folded. */
    fun normalize(query: String): String = query.trim().lowercase(Locale.ROOT)

    /**
     * Non-overlapping, case-insensitive occurrences of [needle] in [text]
     * (`:116-131` walks forward by `query.length`, so `"aa"` in `"aaa"` is one
     * hit, not two).
     */
    fun ranges(text: String, needle: String): List<IntRange> {
        if (text.isEmpty() || needle.isEmpty()) return emptyList()
        val lower = text.lowercase(Locale.ROOT)
        val out = ArrayList<IntRange>()
        var from = 0
        var at = lower.indexOf(needle, from)
        while (at >= 0) {
            out += at until (at + needle.length)
            from = at + needle.length
            at = lower.indexOf(needle, from)
        }
        return out
    }

    /**
     * Every match of [query] over the session's messages, in message order and
     * part order (`:96-135`). An empty (or whitespace-only) query matches
     * nothing.
     */
    fun hits(
        messages: List<ChatStore.UiMessage>,
        query: String,
        includeToolNames: Boolean = true,
    ): List<Hit> {
        val needle = normalize(query)
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<Hit>()
        for (message in messages) {
            for (part in message.parts) {
                if (part.type == "text") {
                    ranges(part.text, needle).forEachIndexed { occurrence, range ->
                        out += Hit(
                            messageID = message.id,
                            partID = part.id,
                            occurrence = occurrence,
                            kind = HitKind.TEXT,
                            range = range,
                        )
                    }
                    continue
                }
                if (includeToolNames && matchesToolTitle(part, needle)) {
                    // A tool part has a single searchable title, so it has one hit.
                    out += Hit(message.id, part.id, 0, HitKind.TOOL)
                }
            }
        }
        return out
    }

    /** `activeIndex` (`:137-143`): 0 when the list is empty or the index is stale. */
    fun clamp(active: Int, count: Int): Int {
        if (count <= 0) return 0
        if (active < 0 || active >= count) return 0
        return active
    }

    /** `move()` (`:242-252`): wraps around in both directions. */
    fun move(active: Int, count: Int, delta: Int): Int {
        if (count <= 0) return 0
        val from = clamp(active, count)
        return ((from + delta) % count + count) % count
    }

    /** The hit a cursor points at, or null when there is nothing to show. */
    fun hitAt(hits: List<Hit>, index: Int): Hit? =
        hits.getOrNull(if (hits.isEmpty()) 0 else clamp(index, hits.size))

    fun highlight(hits: List<Hit>, active: Int, query: String): Highlight {
        val needle = normalize(query)
        if (needle.isEmpty() || hits.isEmpty()) return Highlight.None
        return Highlight(
            parts = hits.mapTo(LinkedHashSet()) { it.partID },
            activePart = hitAt(hits, active)?.partID,
            needle = needle,
        )
    }

    /**
     * Tool hits: the raw tool name (`edit`, `bash`, `grep`) or the English card
     * title [TimelineGrouping.toolLabel] shows. Hidden tools (`todowrite`, which
     * renders no card at all — `tool-renderer.tsx:1197`) are skipped.
     */
    private fun matchesToolTitle(part: ChatStore.UiPart, needle: String): Boolean {
        if (part.type != "tool") return false
        val name = part.toolName ?: return false
        if (name in TimelineGrouping.HIDDEN_TOOLS) return false
        if (name.lowercase(Locale.ROOT).contains(needle)) return true
        return TimelineGrouping.toolLabel(name).lowercase(Locale.ROOT).contains(needle)
    }

    // ------------------------------------------- session-scoped controller

    private val opened = mutableStateOf(false)
    private val needle = mutableStateOf("")
    private val cursor = mutableStateOf(0)
    private val total = mutableStateOf(0)
    private val mounted = mutableStateOf(false)

    /** `c.visible()` (`search-bar.tsx:11`) — the bar replaces the header title. */
    val open: Boolean get() = opened.value

    /** The raw input value (upstream `query.value()`, not the normalized one). */
    val query: String get() = needle.value

    /** `c.result.activeIndex()` — 0-based. */
    val active: Int get() = cursor.value

    /** `c.result.count()` — published by whichever timeline is mounted. */
    val count: Int get() = total.value

    /** True while a session timeline is on screen: gates the header entry. */
    val available: Boolean get() = mounted.value

    fun toggle() {
        if (opened.value) close() else open()
    }

    /** `open()` (`:197-200`). */
    fun open() {
        opened.value = true
    }

    /** `close()` (`:202-205`): value, active and visibility all reset. */
    fun close() {
        opened.value = false
        needle.value = ""
        cursor.value = 0
    }

    /** `setValue()` (`:207-219`): a new value restarts the cursor at the first hit. */
    fun setQuery(value: String) {
        needle.value = value
        cursor.value = 0
    }

    /** `move()` (`search-controller.ts:242-252`). */
    fun moveBy(delta: Int) {
        cursor.value = move(cursor.value, total.value, delta)
    }

    /**
     * Called by the timeline as it recomputes its hit list. Also pulls a stale
     * cursor back into range, which is what upstream's `activeIndex` memo does on
     * read.
     */
    fun publish(count: Int) {
        if (total.value != count) total.value = count
        val clamped = clamp(cursor.value, count)
        if (cursor.value != clamped) cursor.value = clamped
    }

    /** Called by the timeline's `DisposableEffect` (mount / leave). */
    fun setAvailable(value: Boolean) {
        mounted.value = value
    }

    /** Leaving the session screen: no search state may outlive the timeline. */
    fun reset() {
        mounted.value = false
        close()
        total.value = 0
    }
}
