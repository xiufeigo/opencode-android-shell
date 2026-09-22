package com.xiufeigo.opencodeshell.ui.terminal

import com.xiufeigo.opencodeshell.ui.session.drawer.MobileForm
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
