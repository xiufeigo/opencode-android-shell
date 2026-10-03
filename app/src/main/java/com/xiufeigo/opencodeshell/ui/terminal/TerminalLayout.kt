package com.xiufeigo.opencodeshell.ui.terminal

import androidx.compose.ui.text.TextRange
import com.xiufeigo.opencodeshell.ui.session.drawer.MobileForm
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Terminal-pane **placement and geometry** (B3) — the pure half of the docked
 * terminal, kept free of Compose so the policy is JVM-testable.
 *
 * Upstream truth:
 *
 * - `session/screen-layout.ts:19-20` — both placements are gated on
 *   `session.isDesktop()`: `sideTerminal = isDesktop() && settings.general
 *   .terminalPlacement() === "side"`, `bottomTerminal = … === "bottom"`;
 * - `settings/model.tsx:95,252` — `terminalPlacement: Schema.Literals(["side",
 *   "bottom"])`, `defaultSettings.general.terminalPlacement = "side"`;
 * - `settings/general/general.tsx:130-148` — one `SettingsRow` with the
 *   `data-action="settings-terminal-placement"` select, options in `side`,
 *   `bottom` order;
 * - `session/terminal/panel.tsx:75-86` — `max = view * 0.6`,
 *   `pane = Math.min(height, max)`; the stacked (bottom) pane is `pane()` tall,
 *   the side pane fills its region;
 * - `session/screen.tsx:455-464,517-531` — the drag handle is
 *   `min={100}`, `max={window.innerHeight * 0.6}`, `collapseThreshold={50}`,
 *   and `@opencode/ui/src/components/resize-handle.tsx:53-83` is what the
 *   numbers below are transcribed from: the *rendered* size is clamped while the
 *   *collapse* verdict is taken from the unclamped drag (`current < threshold`,
 *   so exactly 50 is **not** collapsed), and the pane closes on pointer-up.
 *
 * Android adds one thing upstream has no native equivalent for: **one pane is
 * never wider/taller than the other axis' default**, i.e. the side pane is a
 * share of the row ([TerminalPaneSize.SIDE_DEFAULT_FRACTION]) instead of the
 * upstream side region's own width, because the native review column is a fixed
 * 240 dp (`ReviewPanelWidth.DEFAULT`) rather than a user-resizable sidebar.
 */

/**
 * Where the session terminal docks on a desktop window
 * (`settings.general.terminalPlacement`).
 */
enum class TerminalPlacement(val id: String) {
    /** A pane of the right side region, next to the review panel (upstream default). */
    SIDE("side"),

    /** The full-width panel under the timeline row (the pre-B3 native behaviour). */
    BOTTOM("bottom"),
    ;

    companion object {
        /** `settings/model.tsx:252` — `terminalPlacement: "side"`. */
        val Default: TerminalPlacement = SIDE

        /** `settings/general/general.tsx:133-136` render order. */
        val Options: List<TerminalPlacement> = listOf(SIDE, BOTTOM)

        /** Stored values, for `SettingsStore` (JVM-testable, no Compose). */
        val Ids: List<String> = Options.map { it.id }

        /** A stored/unknown value reads as the upstream default, never as a missing pane. */
        fun fromId(raw: String?): TerminalPlacement = entries.firstOrNull { it.id == raw } ?: Default
    }
}

/**
 * Which slot renders the pane for a window of [widthDp], or `null` when there is
 * no docked pane at all — upstream gates both placements on `isDesktop()`
 * (`session/screen-layout.ts:19-20`), and below the breakpoint the mobile form's
 * full-view terminal tab is the terminal (`session/screen.tsx:298-305`).
 */
fun dockedPanePlacement(placement: TerminalPlacement, widthDp: Int): TerminalPlacement? =
    if (MobileForm.isDesktop(widthDp)) placement else null

/**
 * Size policy of the docked pane, in dp, on the axis it grows along: height for
 * [TerminalPlacement.BOTTOM], width for [TerminalPlacement.SIDE].
 *
 * The default is deliberately the value the pre-B3 pane used (280 dp capped at
 * 60% of the viewport), so opening the pane before the first drag renders
 * exactly what the shipped build rendered — on both placements.
 */
object TerminalPaneSize {

    /** Upstream `min={100}` (both handles). */
    const val MIN_DP: Int = 100

    /** Upstream `collapseThreshold={50}`: a drag past this closes the pane. */
    const val COLLAPSE_THRESHOLD_DP: Int = 50

    /** Upstream `max = view * 0.6` / `window.innerHeight * 0.6`. */
    const val MAX_FRACTION: Float = 0.6f

    /** The pre-B3 docked height (`session/terminal/layout.tsx:30` `DEFAULT_TERMINAL_HEIGHT`). */
    const val BOTTOM_DEFAULT_DP: Int = 280

    /**
     * Width the side pane opens at: 40% of the window (≈307 dp at the 768 dp
     * desktop breakpoint, ≈36 mono columns of the 14 sp terminal font). Chosen
     * over `ReviewPanelWidth.DEFAULT` (240 dp) because the two panes can be open
     * at once and a terminal needs more columns than a file list; clamped by the
     * same 100 dp / 60% bounds as the bottom pane.
     */
    const val SIDE_DEFAULT_FRACTION: Float = 0.4f

    /** `Math.min(height, view * 0.6)` — the ceiling on one axis of the viewport. */
    fun maxDp(viewportDp: Int): Int = (viewportDp * MAX_FRACTION).roundToInt().coerceAtLeast(0)

    /**
     * One pane size clamped to `[MIN_DP, maxDp(viewportDp)]`. A viewport whose
     * 60% is below [MIN_DP] (a degenerate/degenerate-preview window) wins over
     * the minimum instead of throwing: `coerceIn` requires an ordered range.
     */
    fun clamp(sizeDp: Int, viewportDp: Int): Int {
        val upper = maxDp(viewportDp)
        if (upper <= MIN_DP) return upper
        return sizeDp.coerceIn(MIN_DP, upper)
    }

    /** The size the pane opens at before any drag (and after a double-tap reset). */
    fun defaultDp(placement: TerminalPlacement, viewportDp: Int): Int = clamp(
        when (placement) {
            TerminalPlacement.BOTTOM -> BOTTOM_DEFAULT_DP
            TerminalPlacement.SIDE -> (viewportDp * SIDE_DEFAULT_FRACTION).roundToInt()
        },
        viewportDp,
    )

    /**
     * The value a remembered size restores to: an absent one falls back to
     * [defaultDp], a remembered one is re-clamped against *today's* viewport (a
     * window that shrank must not keep a pane that swallows the timeline).
     */
    fun restore(savedDp: Int?, placement: TerminalPlacement, viewportDp: Int): Int =
        if (savedDp == null) defaultDp(placement, viewportDp) else clamp(savedDp, viewportDp)

    /** One drag step: the clamped pane size plus the unclamped collapse verdict. */
    data class DragStep(val sizeDp: Int, val collapsed: Boolean)

    /**
     * One drag step from the gesture's start size, exactly like
     * `resize-handle.tsx:53-70`: [growthDp] is how much *bigger* the pane should
     * become (accumulated over the gesture, so a drag that ran into the minimum
     * and came back does not immediately grow again), the rendered [DragStep.sizeDp]
     * is clamped, and [DragStep.collapsed] is taken from the raw value
     * (`raw < 50`, so exactly 50 is still an open pane).
     */
    fun drag(startDp: Int, growthDp: Float, viewportDp: Int): DragStep {
        val raw = startDp + growthDp
        return DragStep(
            sizeDp = clamp(raw.toInt(), viewportDp),
            collapsed = raw < COLLAPSE_THRESHOLD_DP.toFloat(),
        )
    }

    /**
     * Sign of one raw pointer delta as *growth* of the pane
     * (`resize-handle.tsx:55-62`): the handle sits on the splitting edge, so the
     * bottom pane grows when the pointer moves **up** (`-dy`) and the side pane —
     * docked at the end of the row — grows when its leading edge moves towards the
     * start (`-dx`, flipped for RTL layouts).
     */
    fun growth(deltaDp: Float, vertical: Boolean, rtl: Boolean): Float = when {
        vertical -> -deltaDp
        rtl -> deltaDp
        else -> -deltaDp
    }
}

/**
 * The remembered pane sizes, one per `sessionId|placement`, kept in a
 * `rememberSaveable` holder so a drag survives a configuration change and a
 * process recreation, and a session switch does not carry the previous session's
 * split over (`SettingsStore` deliberately stays out of it: this is view state,
 * not a preference).
 *
 * Encoding is a `List<String>` of `key=value` rows — the shape a Compose
 * `listSaver` can hand to a `Bundle` — and both directions are pure, so the
 * round trip is unit-tested without a device.
 */
class TerminalPaneSizes(initial: Map<String, Int> = emptyMap()) {

    private val values: MutableMap<String, Int> = LinkedHashMap(initial)

    operator fun get(key: String): Int? = values[key]

    operator fun set(key: String, value: Int) {
        values[key] = value
    }

    /** The saveable projection (`listSaver` writes this into the saved state). */
    fun encoded(): List<String> = values.entries.map { (key, value) -> "$key=$value" }

    companion object {
        /** One session's pane size per placement axis (the two are not interchangeable). */
        fun key(sessionId: String, placement: TerminalPlacement): String = "$sessionId|${placement.id}"

        /** Inverse of [encoded]; a malformed row is dropped rather than crashing a restore. */
        fun decode(saved: List<String>): TerminalPaneSizes = TerminalPaneSizes(
            saved.mapNotNull { row ->
                val separator = row.lastIndexOf('=')
                if (separator <= 0) return@mapNotNull null
                val value = row.substring(separator + 1).toIntOrNull() ?: return@mapNotNull null
                row.substring(0, separator) to value
            }.toMap(),
        )
    }
}

/**
 * Scroll arithmetic of the terminal tab strip (`TerminalTabStrip`).
 *
 * The strip is a horizontally scrolling row of fixed-width chips, and the pane
 * it lives in is often only wide enough for **one** of them: the side placement
 * opens at 40% of the window (≈307 dp at the 768 dp breakpoint) and the
 * trailing affordances (session terminals / shells / `+`, 40 dp each) leave
 * ≈155 dp of chip space against a ≈160 dp chip. A tab created by the strip's
 * `+` is focused by the model, so without scrolling the new chip sits entirely
 * outside the viewport and the user sees the *pane* switch to a fresh shell
 * (the new tab) while the strip still reads 「Terminal 1」 — the "the `+` did
 * nothing and my terminal restarted" report. The session strips
 * (`DesktopTabStrip`/`BottomTabStrip`) carry the same overflow behaviour.
 *
 * Pure so the "keep the active chip visible" rule is unit-testable without a
 * device: [reveal] only ever moves the strip as far as the chip needs.
 */
object TerminalTabScroll {

    /**
     * Offset that brings the chip spanning `[start, start + width)` of a
     * [viewport]-wide strip into view, measured in scroll px.
     *
     * Returns [current] when the chip is already fully visible (a strip that
     * scrolls on every tab switch would fight the user's own scrolling), and
     * the result is clamped to `[0, maxScroll]`. A strip that has not been
     * measured yet ([viewport] `<= 0`) also stays where it is.
     *
     * [endPadding] is scrollable content past the chip's own end that the
     * reveal keeps visible too. The strip passes `0` (its scrollable content
     * is the chips alone since v47) — the parameter stays for the
     * "keep N px past the chip visible" shape, which is still unit-tested.
     */
    fun reveal(
        start: Int,
        width: Int,
        viewport: Int,
        current: Int,
        maxScroll: Int,
        endPadding: Int = 0,
    ): Int {
        val ceiling = maxScroll.coerceAtLeast(0)
        if (viewport <= 0) return current.coerceIn(0, ceiling)
        val end = start + width
        val target = when {
            // Off to the leading side: line the chip's start up with the viewport.
            start < current -> start
            // Off to the trailing side: the chip's end is the viewport's end,
            // plus the trailing slot when one was asked for.
            end > current + viewport -> end + endPadding.coerceAtLeast(0) - viewport
            else -> current
        }
        return target.coerceIn(0, ceiling)
    }
}

/**
 * v46 (`research/v46_verify/tD`) / v47 (`research/v47_verify/tD`): the strip's
 * edge-fade policy — the pure half of the "tab bar occlusion + edges" fix.
 *
 * The chips row scrolls inside the space left by the fixed trailing actions
 * (`>_`, shells, `+`, 40 dp each). v46 also reserved a trailing slot *inside*
 * the scroll container exactly as wide as that group, so the last chip would
 * park clear of the button-adjacent clip edge — but the slot scrolled as dead
 * travel past the last chip (v47 `tD_35_right2/39`: the last tab stopped at
 * `x≈590` while the viewport ran to `980`), so v47 removed it: the scrollable
 * content is the chips alone, the active-chip reveal ends at the chip's own
 * end ([TerminalTabScroll.reveal] still clamps to `[0, maxScroll]`), and the
 * last chip parks with its end flush at the viewport end, fully visible.
 *
 * The edge fade itself ([fadeEdges]) is unchanged: a mid-scroll chip must fade
 * out instead of hard-cutting mid-glyph (`erminal 14`, `tD_28/29/47`). What
 * v47 changed is *how* it draws — an overlay box pinned to the viewport
 * (`TerminalStripEdgeFade`, `TerminalPanel.kt`), because a mask drawn inside
 * `horizontalScroll` scrolls away with the content (`placeRelativeWithLayer`).
 */
object TerminalStripChrome {

    /** Which edges of the chips viewport need the fade overlay. */
    data class EdgeFade(val leading: Boolean, val trailing: Boolean)

    /**
     * The fade rides the scroll position: no overflow (`maxScroll <= 0`) needs
     * no overlay, the leading edge only fades once scrolled past the start, and
     * the trailing edge only while there is content still to the right. A
     * negative max (an unmeasured strip) reads as "cannot scroll".
     */
    fun fadeEdges(value: Int, maxScroll: Int): EdgeFade {
        val ceiling = maxScroll.coerceAtLeast(0)
        return EdgeFade(leading = value > 0, trailing = value < ceiling)
    }
}

/**
 * v47 (`research/v47_verify/tD/tD_45_rename` vs `tD_46_renamed`): the terminal
 * tab rename's entry policy — the pure half of the "prefill the current name"
 * fix.
 *
 * The rename field opens prefilled with the chip's displayed label
 * ([TerminalTabTitle.resolve]: the custom title when set, else the numbered
 * default), so typing edits the current name instead of replacing an empty
 * value — the session strips prefill the same way (`renameDraft = tab.title…`
 * in `DesktopTabStrip.kt` / `MobileTabsDrawer.kt`; a session always carries a
 * title, while a numbered terminal tab usually has no custom one, which is why
 * the prefill reads through the resolved label rather than the raw model).
 * The field then opens with the whole title selected ([entrySelection]), the
 * same entry contract as the session strip's `tabRenameEntrySelection`
 * (`BottomTabStrip.kt`) and the header's `InlineTitleEdit.entrySelection`.
 */
object TerminalTabRename {

    /** The selection the rename field opens with: the **whole** title. */
    fun entrySelection(title: String): TextRange = TextRange(0, title.length)
}

/**
 * A chip's label (`session/terminal/tab.tsx:31-44`): the user's title when they
 * set one, else the `terminal.title.numbered` default **of that terminal's own
 * counter** ([com.xiufeigo.opencodeshell.ui.terminal.share.TerminalTabs.Tab.number]).
 *
 * The number is a property of the terminal, not of its slot, which is what makes
 * a reorder a pure relabel-free permutation: deriving the label from the chip's
 * position instead renames the neighbours on every move (the v40 report — "Move
 * tab left" turned 「Terminal 2」 into 「Terminal 1」 with no tab created or
 * closed). The title is resolved in one place so that rule cannot drift.
 */
object TerminalTabTitle {

    /** The numbered default for a terminal, e.g. `terminal.title.numbered`. */
    fun numbered(number: Int, defaultTitle: (Int) -> String): String = defaultTitle(number)

    /** The chip label: the custom title when set, else the numbered default. */
    fun resolve(custom: String?, number: Int, defaultTitle: (Int) -> String): String =
        custom?.takeIf { it.isNotBlank() } ?: numbered(number, defaultTitle)
}

/**
 * v51: the「旧目录」marker a chip carries after its session was moved out from
 * under the live shell (`research/v51_verify/tD/v51_62_term_after_move.png`).
 *
 * The marker is a prefix on a 96 dp-wide single line, so the full wording ate
 * the label: **both** stale chips read「Old dir · Termi…」and the number — the
 * one thing that tells two terminals apart — was gone behind the ellipsis.
 *
 * The prefix keeps its head word only (`Old dir` → `Old`, `旧目录` stays whole
 * because CJK has no space to cut on), which keeps the meaning in every locale
 * while leaving the number on screen. The full wording still rides the chip's
 * content description ([semantics]), so nothing is lost to a screen reader.
 */
object TerminalTabStaleLabel {

    /** Separator between the marker and the title — the same ` · ` the rest of the shell uses. */
    const val SEPARATOR = " · "

    /**
     * The marker as it may appear **on the chip**: the first whitespace-delimited
     * word of [fullLabel], capped at [MAX_PREFIX_CHARS] so a long translation
     * cannot eat the title either. A label without a space is used as is.
     */
    fun chipPrefix(fullLabel: String): String {
        val trimmed = fullLabel.trim()
        val head = trimmed.substringBefore(' ').take(MAX_PREFIX_CHARS)
        return head.ifEmpty { trimmed.take(MAX_PREFIX_CHARS) }
    }

    /** The chip's visible text: the shortened marker plus the terminal's own label. */
    fun chip(fullLabel: String, title: String): String =
        chipPrefix(fullLabel) + SEPARATOR + title

    /** The chip's content description: the **full** marker, never truncated. */
    fun semantics(fullLabel: String, title: String): String =
        fullLabel + SEPARATOR + title
}

/** Marker budget on the chip: past this the ellipsis starts eating the number. */
private const val MAX_PREFIX_CHARS = 4

/**
 * Which move rows a chip's ⋮ menu may show. Both directions are decided by the
 * same rule — the chip needs a neighbour on that side — so the two can never
 * disagree: the first chip has no left neighbour and the last no right one
 * (`session/terminal/tab.tsx:104-142` only renders the row that moves).
 */
object TerminalTabMenu {

    fun canMoveLeft(index: Int, count: Int): Boolean = index in 1 until count

    fun canMoveRight(index: Int, count: Int): Boolean = index in 0 until (count - 1)
}

/**
 * v40: the strip's own scroll vs. the chip's long-press drag. They are the same
 * gesture until the press has been still for the long-press deadline, so the
 * travel measured up to that deadline is the whole arbitration.
 *
 * The previous design kept the scroll disabled for as long as a finger rested
 * on a chip, which is what let a long press survive the touch slop — but the
 * chips sit flush with no gap between them, so a finger on a chip is the only
 * place there is to drag, and the strip became completely unscrollable (v40
 * `tD_45_stripleft`: the drag moved the strip not one pixel). Scrolling is
 * therefore never suspended, and a press that already passed the slop is read
 * as a scroll instead of the seed of a reorder.
 */
object TerminalTabGesture {

    enum class Press {
        /** Still at the deadline: the hold becomes a reorder / opens the menu. */
        HOLD,

        /** Already past the slop: the press belongs to the strip's scroll. */
        SCROLL,
    }

    /** [travelPx] is the |x| travel since the down, measured before the deadline. */
    fun press(travelPx: Float, touchSlopPx: Float): Press =
        if (abs(travelPx) > touchSlopPx) Press.SCROLL else Press.HOLD
}
