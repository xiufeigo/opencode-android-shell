package com.xiufeigo.opencodeshell.ui.session.timeline

import com.xiufeigo.opencodeshell.ChatStore
import kotlin.math.abs

/**
 * Pure (JVM-testable) row model of the message timeline: the message rows
 * **interleaved with the timeline separators**, plus the tick geometry of the
 * message-navigation rail.
 *
 * ## Separators — upstream anchors
 *
 * `TimelineSeparator` (`_upstream/packages/session-ui/src/components/
 * timeline-separator.tsx:4-32`) is rendered at exactly three places in the
 * upstream timeline:
 *
 * | case | upstream | label |
 * |---|---|---|
 * | model switch | `session-timeline-row.tsx:509-513` (`data-type="model-switched"`, wrapped in `py-2`) | `model().label` (+ `providerID` icon, + `variant` suffix) |
 * | compaction | `message-content.tsx:434-465` | `ui.messagePart.compaction.started` before the summary, the outcome (`ui.messagePart.compaction` → "Session compacted") after it |
 * | interrupted turn | `session-timeline-row.tsx:637-653` (`TimelineRow.TurnDivider`) | `ui.message.interrupted` → "Interrupted" |
 *
 * Upstream decides those from its normalised projection
 * (`timeline/projection.ts:274-278,327-353`): durable `model-switched` notice
 * messages, `compaction` notice messages, and
 * `isInterrupted(message.error)` (`projection.ts:408-410`, error type contains
 * `abort`/`interrupt`). The Android store keeps the same facts on a flatter
 * model, so the rules are re-derived here:
 *
 * - **model switch** — `UiMessage.model` (`"providerID/modelID"`) differs from
 *   the previous message's model. The first model of a session is the initial
 *   selection, not a switch, so it never opens the timeline with a separator.
 * - **compaction start / end** — a message carrying a `compaction` part
 *   (`UiPart.type == "compaction"`). The store has no compaction *status*, so
 *   the end separator always follows the message and never carries an outcome
 *   suffix (documented data gap, `research/wave5_timeline.md`).
 * - **interrupted** — `UiMessage.interrupted` (`isInterrupted(info.error)`,
 *   `ChatStore.kt:455,476`). The divider is placed *after* the interrupted
 *   turn, matching `projection.ts:349-352` ("a divider after interrupted output
 *   unless the turn compacts" — `rows-current.test.ts:1019`), which is why a
 *   compaction turns it off.
 *
 * Rows are returned **top → bottom**; `MessageTimeline` hands the reversed list
 * to its `reverseLayout` `LazyColumn` (index 0 = the visual bottom), so the
 * call site does `rows.asReversed()` exactly like the message-only list did.
 *
 * ## Rail ticks — upstream anchor
 *
 * `MessageNav` at `size="compact"` (`components/message-nav.tsx:33-79`) is the
 * 24 px tick rail: one `li[data-slot=message-nav-item]` per **user** message,
 * the active one flagged `data-active` and drawn as a 2 px × 24 px
 * `icon-strong-base` line instead of the 1 px × 16 px `icon-base` line
 * (`components/message-nav.css:30-59`). [userTicks]/[currentTick] produce the
 * tick list and the active tick for that rail.
 */
object TimelineRows {

    /** Which separator a [Row.Separator] is. */
    enum class SeparatorKind {
        /** `TimelineRow.TurnDivider` — "Interrupted", placed after the turn. */
        INTERRUPTED,

        /** `model-switched` — the new model's display name. */
        MODEL,

        /** `ui.messagePart.compaction.started`. */
        COMPACTION_START,

        /** `ui.messagePart.compaction` ("Session compacted"). */
        COMPACTION_END,
    }

    /** One row of the timeline, top → bottom. */
    sealed interface Row {
        /** Stable `LazyColumn` key. */
        val key: String

        /** One session message (the M2 `MessageRow`). */
        data class Message(val message: ChatStore.UiMessage) : Row {
            override val key: String get() = message.id
        }

        /**
         * A separator row. [anchorID] is the message the separator belongs to
         * (the one it precedes, except for a trailing [SeparatorKind.INTERRUPTED]
         * divider, which anchors to the interrupted message itself).
         */
        data class Separator(
            val kind: SeparatorKind,
            val anchorID: String,
            /** [SeparatorKind.MODEL] only: `"providerID/modelID"`. */
            val model: String? = null,
        ) : Row {
            override val key: String get() = "sep:" + kind.name.lowercase() + ":" + anchorID
        }
    }

    /** One tick of the message-navigation rail. */
    data class Tick(
        val messageID: String,
        /**
         * Index of the tick's message row in the `reverseLayout` `LazyColumn`
         * (already offset by the caller's [leading] rows), i.e. the argument of
         * `LazyListState.animateScrollToItem`.
         */
        val index: Int,
        /** `ui.messageNav.newMessage` fallback is the label used when this is null. */
        val preview: String?,
    )

    /** `ui.messageNav.newMessage` length guard for the a11y label. */
    const val PREVIEW_MAX = 48

    /**
     * `projection.ts:311-376` for the Android data: the message rows plus the
     * separators that belong between them, in visual (top → bottom) order.
     */
    fun build(messages: List<ChatStore.UiMessage>): List<Row> {
        val rows = ArrayList<Row>(messages.size + 4)
        messages.forEachIndexed { index, message ->
            val previous = messages.getOrNull(index - 1)
            if (previous != null) {
                if (previous.interrupted && !hasCompaction(previous)) {
                    rows += Row.Separator(SeparatorKind.INTERRUPTED, previous.id)
                }
                if (message.model != null && message.model != previous.model) {
                    rows += Row.Separator(SeparatorKind.MODEL, message.id, model = message.model)
                }
            }
            val compaction = hasCompaction(message)
            if (compaction) rows += Row.Separator(SeparatorKind.COMPACTION_START, message.id)
            rows += Row.Message(message)
            if (compaction) rows += Row.Separator(SeparatorKind.COMPACTION_END, message.id)
        }
        // The last turn is interrupted and nothing follows it: the divider has no
        // "next message" to precede, so it closes the timeline.
        val last = messages.lastOrNull()
        if (last != null && last.interrupted && !hasCompaction(last)) {
            rows += Row.Separator(SeparatorKind.INTERRUPTED, last.id)
        }
        return rows
    }

    /**
     * One tick per user message, **oldest first** (the order the rail paints
     * top → bottom), each carrying its `reverseLayout` index.
     *
     * @param leading number of rows the caller emits *before* `rows.asReversed()`
     *   (`Working`, the session error card): they occupy the first indices of the
     *   list, i.e. the visual bottom, and shift every message index.
     */
    fun userTicks(rows: List<Row>, leading: Int = 0): List<Tick> {
        val ticks = ArrayList<Tick>()
        rows.asReversed().forEachIndexed { index, row ->
            if (row is Row.Message && row.message.role == "user") {
                ticks += Tick(row.message.id, leading + index, preview(row.message))
            }
        }
        // `asReversed()` walks newest → oldest; the rail reads top → bottom.
        ticks.reverse()
        return ticks
    }

    /**
     * The tick the viewport is on: the user message closest to the middle of the
     * visible range. Indices grow towards the visual top (index 0 is the newest
     * row, pinned to the bottom — `MessageTimeline`'s `reverseLayout` contract),
     * so the visible range is `firstVisibleIndex … lastVisibleIndex` and a tie
     * resolves to the newer tick.
     */
    fun currentTick(ticks: List<Tick>, firstVisibleIndex: Int, lastVisibleIndex: Int): String? {
        val first = ticks.firstOrNull() ?: return null
        val anchor = (firstVisibleIndex + lastVisibleIndex) / 2
        var best = first
        var bestDistance = abs(first.index - anchor)
        for (tick in ticks) {
            val distance = abs(tick.index - anchor)
            if (distance <= bestDistance) {
                best = tick
                bestDistance = distance
            }
        }
        return best.messageID
    }

    /**
     * Display name of a separator's model. Upstream labels the notice with the
     * provider catalog's `models[providerID][modelID].name` and falls back to the
     * **model id** when the catalog has no entry
     * (`message-content.tsx:489-492`). `MessageTimeline` only receives
     * `UiMessage.model`, so the id is what it can show.
     */
    fun modelLabel(model: String?): String {
        val value = model?.trim().orEmpty()
        if (value.isEmpty()) return ""
        return value.substringAfterLast('/')
    }

    /** `UiPart.type == "compaction"` (`schema/src/v1/session.ts:357-383`). */
    fun hasCompaction(message: ChatStore.UiMessage): Boolean =
        message.parts.any { it.type == "compaction" }

    /**
     * `MessageNav`'s `getLabel` / `ui.messageNav.newMessage` fallback
     * (`message-nav.tsx:67-69`): the first line of the user's own text, collapsed
     * and truncated. `null` when the turn has no text at all (attachment only).
     */
    fun preview(message: ChatStore.UiMessage): String? {
        val text = message.parts
            .firstOrNull { it.type == "text" && it.text.isNotBlank() }
            ?.text
            ?: return null
        val flat = text.replace(WHITESPACE, " ").trim()
        if (flat.isEmpty()) return null
        return if (flat.length > PREVIEW_MAX) flat.take(PREVIEW_MAX) + "…" else flat
    }

    private val WHITESPACE = Regex("\\s+")
}
