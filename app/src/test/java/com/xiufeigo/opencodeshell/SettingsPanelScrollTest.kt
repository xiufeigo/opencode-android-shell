package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins for the settings rows' layout, from two device findings.
 *
 * **v32 BUG-2** (`research/v32_verify/tB/12_appearance.png` at scroll 0 vs
 * `12b_settings_scroll.png` after the swipes), two defects in the 外观 / 通用
 * panels:
 *
 * 1. the panel was **split** — a fixed header (`settings-v2-tab-header`) above an
 *    inner `verticalScroll` body — so the page's top band (the title and its
 *    description, the first thing under the nav) belonged to no scroll container
 *    and a swipe that started there was dropped with no feedback; upstream
 *    scrolls the whole tab body (`settings-v2-tab-body` is `overflow:auto`), and
 *    the Pairing page already uses the single-scroll shape on device;
 * 2. the 终端字体 row's `OcTextField` is `fillMaxWidth()` by design, so inside a
 *    row it took the whole line, the weighted label column collapsed to 0 dp and
 *    the row grew to ~290 dp of blank surface with a full-width text field in
 *    the middle — `12b` shows exactly that, which is also why the scrolled page
 *    read as an empty, broken panel.
 *
 * **v33 tB** (`research/v33_verify/tB/v33tb_1c_after_swipe2.png`): the 外观 →
 * **Code Font** row did the same thing one control shape over. The row is
 * `copy (weight 1f) | 16 dp | control` and Compose measures the unweighted
 * control first, so an `OcSelect` trigger — which hugs its value — measured
 * ~217 dp inside a ~260 dp row and left the copy column ~27 dp: the title and
 * `Customise the font used in code blocks` wrapped one fragment per line beside
 * a full-width dropdown, while the sibling 终端字体 row (already fixed in v32)
 * read fine.
 *
 * Both are structural, so they are pinned as source facts here (the panels are
 * Compose code with no JVM-renderable surface in this project): **one band for
 * every control shape a row can render**, plus the scan that no other select in
 * the settings package is left unbounded.
 */
class SettingsPanelScrollTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun panels() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")

    private fun components() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsComponents.kt")

    private fun rowsPanel(): String {
        val text = panels()
        val from = text.indexOf("private fun SettingsPanelRows(")
        assertTrue("SettingsPanelRows is gone", from >= 0)
        val to = text.indexOf("\nprivate fun SettingsPanelSections(", from)
        assertTrue("SettingsPanelSections anchor is gone", to > from)
        return text.substring(from, to)
    }

    /** [text] from [from] up to (excluding) the next [to]. */
    private fun window(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("$from is gone", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("$to is gone", end > start)
        return text.substring(start, end)
    }

    @Test
    fun theRowPanelsHeaderScrollsWithItsBody() {
        val body = rowsPanel()
        // v48: the scroller is a named `ScrollState` now — the search jump has
        // to `animateScrollTo` a row, which an inline `rememberScrollState()`
        // inside the modifier cannot do.
        assertTrue(
            "the panel's scroll state must be a value the jump can drive",
            body.contains("val scrollState = rememberScrollState()"),
        )
        assertTrue(
            "the header must live inside the panel's own scroll",
            body.contains("val scrollerModifier = Modifier") &&
                body.contains(".fillMaxSize()") &&
                body.contains(".verticalScroll(scrollState)"),
        )
        assertTrue(
            "and the body must not bring a second scroller",
            body.contains("SettingsPanelBody(scroll = false)"),
        )
        assertEquals(
            "one vertical scroller per panel — nesting two is what swallows the first drag",
            1,
            Regex("verticalScroll\\(").findAll(body).count(),
        )
        assertTrue("the header is still the first child", body.contains("SettingsPanelHeader("))
    }

    @Test
    fun thePairingPrecedentIsUnchanged() {
        val pairing = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/PairingPanel.kt")
        assertTrue(
            pairing.contains("Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()))"),
        )
        assertTrue(pairing.contains("SettingsPanelBody(scroll = false)"))
    }

    @Test
    fun aTextControlCannotCollapseItsRowsLabelColumn() {
        val body = panels()
        val text = window(body, "SettingsControl.TEXT ->", "private fun TimelineDetailControl(")
        assertTrue(
            "the TEXT control is bounded relative to the row it sits in",
            text.contains("modifier = SettingsRowControlWidth"),
        )
        assertTrue(
            "the copy column is still the weighted one the control must not starve",
            components().contains("Modifier.weight(1f).then(toggle)"),
        )
    }

    /**
     * v33 tB: the SELECT shape starved the Code Font row's copy column exactly
     * like the TEXT shape did in v32, so every shape a row can render now carries
     * the same band — the fraction is what guarantees the copy column at every
     * window width (a fixed dp cap still starves it on a 320 dp window, where the
     * whole row is ~200 dp), the 140–260 dp part keeps the control usable on a
     * wide window and lines the shapes up down the page.
     */
    @Test
    fun everySettingsRowControlShapeIsBounded() {
        val body = panels()
        val components = components()
        assertTrue(
            "the band must reserve the copy column its share of the row",
            components.contains("Modifier.fillMaxWidth(0.6f).widthIn(min = 140.dp, max = 260.dp)"),
        )
        val select = window(body, "SettingsControl.SELECT ->", "SettingsControl.TEXT ->")
        assertTrue(
            "the SELECT control (the v33 defect: Code Font) must carry the band",
            select.contains("modifier = SettingsRowControlWidth"),
        )
        val text = window(body, "SettingsControl.TEXT ->", "private fun TimelineDetailControl(")
        assertTrue(
            "the TEXT control (the v32 defect: Terminal Font) must carry the band",
            text.contains("modifier = SettingsRowControlWidth"),
        )
        val switch = window(body, "SettingsControl.SWITCH ->", "SettingsControl.SELECT ->")
        assertTrue("the SWITCH shape still renders the knob", switch.contains("OcSwitch("))
        assertTrue(
            "and the knob is fixed-size, so it can never grow into the copy column",
            components.contains(".size(width = 40.dp, height = 24.dp)"),
        )
    }

    /**
     * The two selects that do not come through [SettingsRowControl]: the General
     * page's timeline preset and the Experimental page's tab layout. Both sit in
     * a `SettingsRow` — the same copy column, the same defect — so both take the
     * band as well.
     */
    @Test
    fun theSelectsOutsideTheControlTableAreBoundedToo() {
        val body = panels()
        val timeline = window(
            body,
            "private fun TimelineDetailControl(",
            "fun switchValue(",
        )
        assertTrue(
            "the timeline preset row's select must carry the band",
            timeline.contains("modifier = SettingsRowControlWidth"),
        )
        val experimental = window(
            body,
            "fun ExperimentalPanel(",
            "private fun MigrationStatusRowUi(",
        )
        assertTrue(
            "the Experimental tab-layout row's select must carry the band",
            experimental.contains("modifier = SettingsRowControlWidth"),
        )
    }

    /**
     * The scan for a *third* starving select — the point of the v33 finding is
     * that the defect is a family, not a row. Every `OcSelect(` call in the
     * settings package must be bounded, either by the band a `SettingsRow`
     * control takes or by a weight that makes it share the row with its sibling.
     * Exactly two are allowed to keep the component's own default (140–260 dp)
     * instead:
     *
     * * `SettingsConnectUi.kt` ×2 — the provider-method picker and the MCP
     *   local/remote picker. Both sit in a full-width form `Column`, where there
     *   is no weighted label beside them to starve.
     *
     * The Workspaces filter row used to be the third entry: its label is a
     * `weight(1f)` count *and* its row has a third unweighted child (the ⋮
     * button), so the band would have taken 60 % of the row and starved the
     * count from the other side. It now takes the row's own decision instead —
     * `weight(1f)` on the select beside `weight(1f)` on the count, which is
     * checked by [theWorkspacesFilterRowSplitsItsWidthByWeight] below. A select
     * is only *bounded* here if it carries one of those two: a fourth one fails,
     * which is the reminder to bound it deliberately.
     */
    @Test
    fun noOtherSettingsSurfaceLeavesItsSelectUnbounded() {
        val dir = File("src/main/java/com/xiufeigo/opencodeshell/ui/settings")
            .takeIf { it.isDirectory }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/settings")
        assertTrue("the settings package is gone", dir.isDirectory)
        val unbounded = mutableListOf<String>()
        for (file in dir.listFiles { f: File -> f.name.endsWith(".kt") }.orEmpty().sortedBy { it.name }) {
            val text = file.readText()
            // `(?<!fun )` skips `OcSelect`'s own declaration in SettingsComponents.
            for (call in Regex("(?<!fun )OcSelect\\(").findAll(text)) {
                val tail = text.substring(call.range.first, minOf(text.length, call.range.first + 500))
                if (tail.contains("SettingsRowControlWidth")) continue
                if (tail.contains("modifier = Modifier.weight(1f)")) continue
                unbounded += file.name
            }
        }
        assertEquals(
            "unbounded selects left in the settings package — one per allowed call site",
            mapOf("SettingsConnectUi.kt" to 2),
            unbounded.groupingBy { it }.eachCount(),
        )
    }

    /**
     * The Workspaces filter row's own layout decision (B-10 follow-up, the
     * fourth entry the scan above used to whitelist).
     *
     * The defect: `count (weight 1f) | select | ⋮` with the select **unweighted**
     * and hugging its value. Compose measures the unweighted children first, so
     * the trigger took its full 140–260 dp out of the row and the count — the
     * only weighted child, measured last with what was left — collapsed to 0 dp
     * on a phone-width window (the 328 dp dialog minus the panel's 40 dp inset
     * is a ~248 dp row; 260 + the ⋮ button is more than that).
     *
     * The landed version kept the ⋮ at its intrinsic width and split the rest
     * between the count and the select. That left the row's three sub-items
     * measuring differently at every width above a phone: the select's own
     * 140–260 dp band clamped its **visible** box below the share it was handed
     * (a `weight` sizes the box, not the trigger inside it), and the ⋮ was an
     * intrinsic-width text button. The row is three **equal** children now —
     * a weight on every child, `fillWidth` on the select so its box *is* its
     * third, and the ⋮ as upstream's dots icon at the end of its third, because
     * a text label ("More options", ~107 dp) cannot stay legible in a third of a
     * phone-width row (~94 dp). This pin moves with that decision: it is the
     * same three children, all three weighted, and the ⋮ no longer a text
     * button.
     */
    @Test
    fun theWorkspacesFilterRowSplitsItsWidthByWeight() {
        val toolbar = window(panels(), "private fun WorkspacesToolbar(", "private fun WorkspaceRowUi(")
        val count = window(toolbar, "Row(", "if (projectOptions.isNotEmpty())")
        val select = window(toolbar, "OcSelect(", "Box(")
        assertTrue(
            "the count is the first weighted child",
            count.contains("modifier = Modifier.weight(1f),"),
        )
        assertTrue(
            "and it cannot wrap into a one-character column on a narrow row",
            count.contains("maxLines = 2") && count.contains("overflow = TextOverflow.Ellipsis"),
        )
        assertTrue(
            "the select shares the row instead of hugging its value",
            select.contains("modifier = Modifier.weight(1f)"),
        )
        assertTrue(
            "and fills that share, so its band cannot narrow it below the count's cell",
            select.contains("fillWidth = true"),
        )
        assertFalse(
            "the control band is the wrong bound here — 60 % would starve the count",
            select.contains("SettingsRowControlWidth"),
        )
        val more = window(toolbar, "Box(", "DropdownMenu(")
        assertTrue(
            "the ⋮ is the third equal child, not an intrinsic-width one",
            more.contains("Modifier.weight(1f)"),
        )
        assertTrue(
            "its trigger is upstream's dots icon, which fits a third at every width",
            more.contains("OcDotsIcon("),
        )
        assertFalse(
            "and no text button, whose label could not fit that third",
            more.contains("OcButton("),
        )
    }

    /**
     * The band list above is only complete if it covers every shape the model can
     * ask for: this reads [SettingsControl]'s constants out of the source and
     * requires a branch for each, so adding a shape fails the pin instead of
     * silently rendering an unbounded control.
     */
    @Test
    fun thePinCoversEveryDeclaredControlShape() {
        val model = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsModel.kt")
        val shapes = Regex("^\\s*([A-Z][A-Z_]*),\\s*$", RegexOption.MULTILINE)
            .findAll(window(model, "enum class SettingsControl {", "}"))
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "a new SettingsControl shape needs a bound of its own (and this pin updated)",
            listOf("SELECT", "TEXT", "SWITCH"),
            shapes,
        )
        val control = window(
            panels(),
            "private fun SettingsRowControl(",
            "internal const val FOLLOW_UP_BEHAVIOR_KEYBIND",
        )
        for (shape in shapes) {
            assertTrue("$shape has no branch in SettingsRowControl", control.contains("SettingsControl.$shape ->"))
        }
    }

    @Test
    fun theTextFieldsFullWidthContractIsUnchangedForDialogs() {
        val text = components()
        val from = text.indexOf("fun OcTextField(")
        assertTrue(from >= 0)
        val body = text.substring(from, text.indexOf("/**", from + 10).let { if (it < 0) text.length else it })
        assertTrue(
            "the search field and every dialog input still span their container",
            body.contains(".fillMaxWidth()"),
        )
        // The fix belongs to the settings row, not to the shared component: the
        // bound must not appear inside `OcTextField` itself.
        assertTrue(!body.contains("widthIn(min = 140.dp"))
    }
}
