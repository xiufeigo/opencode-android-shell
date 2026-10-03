package com.xiufeigo.opencodeshell.ui.palette

import androidx.compose.runtime.compositionLocalOf

/**
 * The shell-level half of the command palette's entries (v35 tA-terminal).
 *
 * Upstream opens the palette from **one** visible control: the "search and
 * command palette button in the title bar" that
 * `settings.general.row.showSearch` (`settings.general.row.showSearch.description`)
 * toggles, mounted into the titlebar's trailing slot (`TitlebarRight`, the
 * `right-slot.tsx` portal). Every other entry is a chord (`mod+k` /
 * `mod+shift+p`, `command.tsx:397-424`). A phone shell has no hardware keyboard —
 * the tester's `input keyevent` chord injection does not reach
 * `GlobalKeybindFocus` — so the strip button
 * ([com.xiufeigo.opencodeshell.ui.nav.DesktopTabStrip] /
 * [com.xiufeigo.opencodeshell.ui.nav.BottomTabStrip]) is the port of that
 * control, and the session header's ⋮ menu carries a second, always-visible row.
 *
 * Why a host object instead of parameters on `SessionHeader`: `SessionScreen`
 * (owned elsewhere) calls that composable with a fixed argument list, exactly
 * the reason [com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsHost]
 * exists. The shell builds this object in `ui/App.kt` — the only place holding
 * the palette's `DialogController` and the `debugBarOpen` flag — and publishes it
 * as [LocalPaletteHost]; the header picks it up, and a null host simply means
 * "no palette / debug rows" (a preview, or a header rendered outside the shell).
 */
class PaletteHost(
    /**
     * Opens the palette in [kind]'s shape. The session route passes
     * [PaletteHostKind.SESSION], Home [PaletteHostKind.HOME] — the same
     * decision `ui/App.kt`'s `paletteHost()` makes for the chord.
     */
    val open: (PaletteHostKind) -> Unit,
    /**
     * `debugBar.toggle`'s state (`ui/App.kt`'s `debugBarOpen`). Read from
     * composition, so the ⋮ row follows the bar the palette row / chord opened.
     */
    val debugBarOpen: Boolean,
    /**
     * The same flip the palette row and `GlobalChord.DEBUG_BAR` run — one flag,
     * three entries (`ShellActions.onToggleDebugBar`).
     */
    val toggleDebugBar: () -> Unit,
)

/**
 * The palette host of the shell on screen; null wherever the shell is not
 * mounted (previews, the legacy chat tab).
 */
val LocalPaletteHost = compositionLocalOf<PaletteHost?> { null }
