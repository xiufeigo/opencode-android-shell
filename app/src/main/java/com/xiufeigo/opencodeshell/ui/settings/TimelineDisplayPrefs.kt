package com.xiufeigo.opencodeshell.ui.settings

import androidx.compose.runtime.compositionLocalOf
import com.xiufeigo.opencodeshell.SettingsStore
import com.xiufeigo.opencodeshell.ui.terminal.TerminalPlacement

/**
 * The stored preferences the *session UI* consumes, published once by
 * `ui/App.kt` instead of being threaded through `SessionScreen` as more
 * parameters: the three timeline-display switches, (B3) the terminal
 * placement and the terminal font.
 *
 * The values exist to be *consumed*: `shellToolPartsExpanded` /
 * `editToolPartsExpanded` are the `shellExpanded` / `editExpanded` arguments
 * `MessageTimeline` already forwards to `ToolCardPolicy.resolveOpen`,
 * `reasoningSummaries` gates the reasoning parts (`PartRenderer`),
 * `terminalPlacement` picks the slot `ui/terminal/TerminalDock.kt` docks into
 * and `terminalFont` is the family the panel resolves for the VT renderer.
 *
 * ```kotlin
 * // session UI (ui/session/timeline):
 * @Composable fun MessageTimeline(
 *     ...
 *     shellExpanded: Boolean = LocalTimelineDisplayPrefs.current.shellToolPartsExpanded,
 *     editExpanded: Boolean = LocalTimelineDisplayPrefs.current.editToolPartsExpanded,
 * )
 * ```
 *
 * Defaults mirror [SettingsStore]'s stored defaults (`read()`), so a preview or
 * a test without the provider renders exactly what a fresh install does. The
 * shell-level provider lives in `ui/App.kt`; providing it is not optional there,
 * otherwise the stored value would be ignored.
 */
data class TimelineDisplayPrefs(
    val reasoningSummaries: Boolean = DEFAULT_REASONING_SUMMARIES,
    val shellToolPartsExpanded: Boolean = DEFAULT_SHELL_EXPANDED,
    val editToolPartsExpanded: Boolean = DEFAULT_EDIT_EXPANDED,
    /**
     * B3: where the desktop session docks the terminal
     * (`settings.general.terminalPlacement`). Carried on this channel because it
     * is the settings-backed preference projection the session UI already
     * consumes — `ui/App.kt` publishes it from the store, so flipping the
     * setting re-docks the pane without a route change.
     */
    val terminalPlacement: TerminalPlacement = TerminalPlacement.Default,
    /**
     * `settings.appearance.terminalFont` (`TerminalPanelHost.terminalFont`).
     *
     * The docked pane reads it from here rather than taking a parameter: the
     * standalone `Route.TERMINAL` host gets `settings.terminalFont` directly
     * (`ui/App.kt`), so threading it as an argument through `TerminalDock` was
     * what left the session's terminal on the default face — the setting
     * changed, the echoed row came back different and the glyphs did not move.
     */
    val terminalFont: String = "",
) {
    companion object {
        /** Same defaults as `SettingsStore.read()`. */
        const val DEFAULT_REASONING_SUMMARIES: Boolean = true
        const val DEFAULT_SHELL_EXPANDED: Boolean = false
        const val DEFAULT_EDIT_EXPANDED: Boolean = false

        val Default: TimelineDisplayPrefs = TimelineDisplayPrefs()
    }
}

/** The stored settings projected onto the timeline contract. */
fun SettingsStore.Settings.timelineDisplayPrefs(): TimelineDisplayPrefs = TimelineDisplayPrefs(
    reasoningSummaries = reasoningSummaries,
    shellToolPartsExpanded = shellToolPartsExpanded,
    editToolPartsExpanded = editToolPartsExpanded,
    terminalPlacement = TerminalPlacement.fromId(this.terminalPlacement),
    terminalFont = this.terminalFont,
)

/** Provided by `ui/App.kt` around the navigation tree. */
val LocalTimelineDisplayPrefs = compositionLocalOf { TimelineDisplayPrefs.Default }
