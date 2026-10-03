package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.SettingsRowStackedViewportWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Workspaces filter row's three sub-items, which used to measure
 * differently at every width above a phone.
 *
 * `WorktreesPanel`'s toolbar (`settings/workspaces/workspaces.tsx:375-397`,
 * `settings.css` `.settings-workspaces-toolbar`) is `count | project filter | ⋮`.
 * The landed version of it gave the count and the select a weight each and left
 * the ⋮ at its intrinsic width, which is where the row's *visible* widths came
 * apart:
 *
 * * the select's `weight` sizes the **box** the select sits in, not the trigger
 *   inside it — `OcSelect`'s own 140–260 dp band kept the visible box at 170 dp
 *   inside a 268 dp share on the v30 tA capture (`research/v30_verify/tA_notify_ws
 *   /tA_08_worktrees.png`), so the count's cell and the select's box were never
 *   the same width;
 * * the ⋮ was a text button ("More options"), ~107 dp wide — wider than either
 *   share on a phone and narrower than both on a tablet, i.e. a third width of
 *   its own.
 *
 * The row is three **equal** children now: a `weight(1f)` on all three,
 * `fillWidth = true` on the select so its box *is* its third, and the ⋮ as
 * upstream's `IconButton` + `outline-dots` trigger (`aria-label={common
 * .moreOptions}` → `oc_more_options` here, the same label every other ⋮ in the
 * shell carries) at the end of its third. An icon and not a label because a
 * third of a narrow row is 90–94 dp and the label is ~107 dp.
 *
 * The panels are Compose code with no JVM-renderable surface in this project, so
 * the decision is pinned as source facts plus the geometry chain the toolbar
 * actually renders through — the same chain `SettingsRowStackedTest` pins for
 * the settings rows (`BoxWithCappedSize` = `min(100vw − 32, 980)`, the 240 dp
 * nav + 1 dp divider from 640 dp up, then `SettingsPanelBody`'s 40 dp inset a
 * side; the toolbar sits *outside* `SettingsList`, so it takes no list inset).
 */
class WorkspacesToolbarColumnsTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun panels() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")

    private fun components() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsComponents.kt")

    /** [text] from [from] up to (excluding) the next [to]. */
    private fun window(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("missing window start: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("missing window end: $to", end > start)
        return text.substring(start, end)
    }

    private fun toolbar() = window(panels(), "private fun WorkspacesToolbar(", "private fun WorkspaceRowUi(")

    /** The ⋮ block: its cell `Box` through to the menu it opens. */
    private fun menuCell() = window(toolbar(), "Box(", "DropdownMenu(")

    /**
     * The toolbar's width for a window of [windowWidthDp]: the dialog's cap, the
     * nav column above 640 dp, and the panel body's own 40 dp inset a side. The
     * list inset `SettingsRowStackedTest` adds for a *row* does not apply — the
     * toolbar is the panel body's first child, not a `SettingsList` row.
     */
    private fun toolbarWidthForWindow(windowWidthDp: Int): Float {
        val dialog = minOf(windowWidthDp - 32, 980)
        val panel = if (windowWidthDp >= SettingsRowStackedViewportWidth) dialog - 241 else dialog
        return (panel - 80).toFloat()
    }

    /** One of the row's three equal columns: the toolbar minus its two 8 dp gaps, in thirds. */
    private fun columnForWindow(windowWidthDp: Int): Float = (toolbarWidthForWindow(windowWidthDp) - 16f) / 3f

    // ------------------------------------------------------------ the decision

    @Test
    fun theThreeSubItemsEachTakeAWeightOfTheRow() {
        val row = toolbar()
        assertEquals(
            "count, select and ⋮ — one weight each, no child left to its intrinsic width",
            3,
            Regex("Modifier\\.weight\\(1f\\)").findAll(row).count(),
        )
        assertTrue(
            "and the row still separates them by 8 dp",
            row.contains("horizontalArrangement = Arrangement.spacedBy(8.dp)"),
        )
        assertTrue(
            "the ⋮'s cell is the third weighted child, not a fixed box",
            menuCell().contains("Modifier.weight(1f)"),
        )
    }

    /**
     * The select's weight sizes its **box**; the trigger inside it used to keep
     * `OcSelect`'s own 140–260 dp band, so the visible control was narrower than
     * the share it was handed (170 dp inside 268 dp on the v30 tA capture). The
     * row now asks the component to fill that share instead.
     */
    @Test
    fun theSelectFillsTheThirdItsWeightHandsIt() {
        val select = window(toolbar(), "OcSelect(", "Box(")
        assertTrue(
            "the call site asks for a filled trigger",
            select.contains("fillWidth = true"),
        )
        assertTrue(
            "and the component has the branch that fills",
            components().contains(
                "(if (fillWidth) Modifier.fillMaxWidth() else Modifier.widthIn(min = 140.dp, max = 260.dp))",
            ),
        )
        assertTrue(
            "the default keeps the band, so a SettingsRow control is unchanged",
            components().contains("fillWidth: Boolean = false,"),
        )
    }

    /**
     * The third child is upstream's icon trigger. A text label could not stay
     * legible in a third of a phone-width row, and the ⋮ shape is what every
     * other menu trigger in the shell uses (`ServerRowMenu`, `HomeProjectsList`,
     * `SessionHeader`) — all of them labelled through `oc_more_options`.
     */
    @Test
    fun theMenuTriggerIsTheDotsIconUpstreamUses() {
        val cell = menuCell()
        assertTrue("the trigger is an icon button", cell.contains("OcIconButton("))
        assertTrue("drawing the dots icon", cell.contains("OcDotsIcon("))
        assertFalse("and not a text button, whose label would not fit", cell.contains("OcButton("))
        assertTrue(
            "the label rides the content description instead",
            cell.contains("contentDescription = menuLabel") &&
                toolbar().contains("val menuLabel = stringResource(R.string.oc_more_options)"),
        )
        assertTrue(
            "the trigger still disables with the page's write gate",
            cell.contains("enabled = menuEnabled") &&
                toolbar().contains("val menuEnabled = canDelete && totalRows > 0"),
        )
    }

    /**
     * The geometry the row renders through, for the windows the device runs
     * actually had: the v37 tB phone (1080 px @ 420 dpi = 411 dp), the v36 tC
     * foldable (1080 px @ 320 dpi = 540 dp) and the 1280 dp tablet
     * (2560x1600 px @ 320 dpi). All three columns are the same number by
     * construction — which is the point: no width band of a child's own can move
     * one of them away from the others any more.
     */
    @Test
    fun theThreeColumnsAreEqualAtTheDeviceGeometries() {
        assertEquals("the 411 dp phone's toolbar is 299 dp", 299f, toolbarWidthForWindow(411), 0.01f)
        assertEquals("so each third is 94.33 dp", 94.33f, columnForWindow(411), 0.01f)
        assertEquals("the 540 dp foldable's toolbar is 428 dp", 428f, toolbarWidthForWindow(540), 0.01f)
        assertEquals("so each third is 137.33 dp", 137.33f, columnForWindow(540), 0.01f)
        assertEquals("the 1280 dp tablet's toolbar is 659 dp", 659f, toolbarWidthForWindow(1280), 0.01f)
        assertEquals("so each third is 214.33 dp", 214.33f, columnForWindow(1280), 0.01f)
        // The nav column only exists from 640 dp up, so the toolbar jumps *down*
        // 240 dp at that boundary: 639 dp gives a 527 dp toolbar, 640 dp a 287 dp
        // one — the narrowest the row ever gets, and the width the ~90 dp third
        // in `WorkspacesToolbar`'s KDoc is measured at.
        assertEquals("a 639 dp window's toolbar", 527f, toolbarWidthForWindow(639), 0.01f)
        assertEquals("and a 640 dp window's, 240 dp narrower", 287f, toolbarWidthForWindow(640), 0.01f)
        assertEquals("which is the row's narrowest third", 90.33f, columnForWindow(640), 0.01f)
        assertTrue(
            "and still a third a count, a select and an icon each fit in",
            columnForWindow(640) >= 90f,
        )
    }

    /**
     * Why the third child is an icon: the label it used to show cannot fit a
     * third of a narrow row. "More options" is 12 glyphs at 13 sp — 6.6 dp per
     * glyph, measured off `research/vm5558_shots/24_worktrees.png` (1080 px @
     * 420 dpi), where the row's 16-glyph "Delete worktrees" button is 134 dp
     * including `OcButton`'s 14 dp padding a side — so ~107 dp with the same
     * padding, against the 90–94 dp third above.
     */
    @Test
    fun aTextTriggerCouldNotFitTheNarrowRowsThird() {
        val label = 12 * 6.6f + 28f
        assertTrue("the label is ~107 dp", label > 106f && label < 108f)
        assertTrue(
            "and the row's narrowest third is narrower than it: $label > ${columnForWindow(640)}",
            label > columnForWindow(640),
        )
        assertTrue(
            "as is a phone's: $label > ${columnForWindow(411)}",
            label > columnForWindow(411),
        )
    }
}
