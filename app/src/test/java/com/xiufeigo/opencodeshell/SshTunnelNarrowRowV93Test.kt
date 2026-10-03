package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.servers.SshTunnelRowIdentityMin
import com.xiufeigo.opencodeshell.ui.servers.SshTunnelRowStackedWidth
import com.xiufeigo.opencodeshell.ui.servers.sshTunnelDialogDp
import com.xiufeigo.opencodeshell.ui.servers.sshTunnelRowDp
import com.xiufeigo.opencodeshell.ui.servers.sshTunnelRowStacked
import com.xiufeigo.opencodeshell.ui.servers.sshTunnelStackedForWindow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 「极窄窗口 SSH 行无身份」: the arithmetic behind `SshTunnelCard`'s
 * adaptive rule — threshold, floor, wide-window identity.
 *
 * Device evidence: `research/v91_verify/5556/` (350 dp freeform — the weight
 * column squeezed to 0 dp, no tunnel name, "Disconnect" hard-wrapped) and
 * `research/v91_verify/5558/s13_failedclean.png` (the phone — the status line
 * at ~2 chars/line). The fitting shape is
 * `research/v91_verify/5554/ui_46_row3.xml` (tablet, one line).
 *
 * The project has no JVM-renderable Compose surface (no `ui-test-junit4`), so
 * the decision is pinned as the **pure arithmetic** the card calls — with the
 * real windows and insets those rows measure — plus source facts that the card
 * really calls it (the house style: `SettingsRowStackedTest`,
 * `HomeRowNarrowTest`, `SettingsModalImeBandV92Test`).
 */
class SshTunnelNarrowRowV93Test {

    private fun source(relative: String): String =
        listOf(File("app/$relative"), File(relative))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun host() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelHost.kt")

    /** The card itself, from its declaration to the end of the host file. */
    private fun card() = host().substringAfter("fun SshTunnelCard(")

    /** The dialog → row derivation the threshold reads. */
    private fun derivation(windowDp: Float, sidebarDp: Float = 0f): Float =
        sshTunnelRowDp(sshTunnelDialogDp(windowDp), sidebarDp)

    // ------------------------------------------------------- the rule's numbers

    @Test
    fun theThresholdAndTheFloorAreTheDocumentedValues() {
        assertEquals("the stack threshold", 450f, SshTunnelRowStackedWidth.value)
        assertEquals("the identity floor", 96f, SshTunnelRowIdentityMin.value)
    }

    @Test
    fun theRuleEngagesBelowTheThresholdAndOnlyThere() {
        assertTrue("a 198 dp row (the 350 dp freeform) stacks", sshTunnelRowStacked(198.dp))
        assertTrue("a 259 dp row (the phone) stacks", sshTunnelRowStacked(259.dp))
        assertTrue("one dp under the threshold still stacks", sshTunnelRowStacked(449.dp))
        assertFalse("the threshold itself keeps the one-line shape", sshTunnelRowStacked(450.dp))
        assertFalse("a 460 dp row (the foldable inner) keeps it", sshTunnelRowStacked(460.dp))
        assertFalse("a 620 dp row (the tablet) keeps it", sshTunnelRowStacked(620.dp))
        // No measurement, no behaviour change (a collapsed host, a preview).
        assertFalse(sshTunnelRowStacked(0.dp))
        assertFalse(sshTunnelRowStacked((-1).dp))
    }

    @Test
    fun theFloorFitsInsideTheThreshold() {
        // The floor is what the weighted column keeps; the threshold is what
        // decides the shape. A floor wider than the threshold could never hold
        // on the one-line branch it is meant to guard.
        assertTrue(
            "the identity floor must fit inside the stack threshold",
            SshTunnelRowIdentityMin < SshTunnelRowStackedWidth,
        )
    }

    // --------------------------------------------- the dialog → row derivation

    @Test
    fun theDialogIsTheDocumentedMinWindowMinus32CappedAt980() {
        assertEquals(318f, sshTunnelDialogDp(350f))
        assertEquals(379f, sshTunnelDialogDp(411f))
        assertEquals(820f, sshTunnelDialogDp(852f))
        assertEquals(980f, sshTunnelDialogDp(1280f))
        assertEquals(980f, sshTunnelDialogDp(2560f))
    }

    @Test
    fun theRowIsTheDialogMinusInsetsAndTheSidebarWhereOneIsShown() {
        // SettingsList (20 dp a side) + SettingsPanelBody (40 dp a side).
        assertEquals(198f, sshTunnelRowDp(318f))
        assertEquals(259f, sshTunnelRowDp(379f))
        assertEquals(460f, sshTunnelRowDp(820f, sidebarDp = 240f))
        assertEquals(620f, sshTunnelRowDp(980f, sidebarDp = 240f))
    }

    @Test
    fun theDerivationMatchesTheDocumentedDeviceRows() {
        assertEquals("the 350 dp freeform row", 198f, derivation(350f))
        assertEquals("the phone row", 259f, derivation(411f))
        assertEquals("the foldable-inner row", 460f, derivation(852f, sidebarDp = 240f))
        assertEquals("the tablet row", 620f, derivation(1280f, sidebarDp = 240f))
    }

    // ------------------------------------------- the reporting windows, by name

    @Test
    fun theTwoReportingWindowsStackAndTheFittingOnesDoNot() {
        assertTrue("the 350 dp freeform (5556) stacks", sshTunnelStackedForWindow(350f))
        assertTrue("the phone (5558) stacks", sshTunnelStackedForWindow(411f))
        assertFalse(
            "the foldable inner (5556, 852 dp + sidebar) keeps the line",
            sshTunnelStackedForWindow(852f, sidebarDp = 240f),
        )
        assertFalse(
            "the tablet (5554, 1280 dp + sidebar) keeps the line",
            sshTunnelStackedForWindow(1280f, sidebarDp = 240f),
        )
    }

    @Test
    fun theFittingWindowsKeepTheirShapeWithMargin() {
        // The rule must not sit so close to a fitting window that a longer
        // CJK label or a wider font scale tips a healthy row into the stack:
        // both fitting rows clear the threshold by at least one button width.
        assertTrue(derivation(852f, sidebarDp = 240f) - SshTunnelRowStackedWidth.value >= 10f)
        assertTrue(derivation(1280f, sidebarDp = 240f) - SshTunnelRowStackedWidth.value >= 100f)
    }

    // --------------------------------------- the card really reads the decision

    @Test
    fun theCardMeasuresItselfAndBranchesOnThePureRule() {
        val body = card()
        assertTrue("the card measures its own width", body.contains("BoxWithConstraints("))
        assertTrue(
            "the branch reads the pure rule",
            body.contains("sshTunnelRowStacked(maxWidth)"),
        )
        assertTrue("the stacked shape exists", body.contains("if (stacked)"))
    }

    @Test
    fun theAdaptiveControlsShareOneRowPairAndOneMenu() {
        // The two shapes must not drift into two wirings: one button pair, one
        // menu, called from both branches.
        val body = card()
        assertEquals(
            "one shared reconnect/disconnect pair",
            1,
            Regex("fun SshTunnelRowButtons\\(").findAll(body).count(),
        )
        assertEquals(
            "one shared ⋮ menu",
            1,
            Regex("fun SshTunnelRowMenu\\(").findAll(body).count(),
        )
        assertEquals(
            "both shapes draw the pair",
            2,
            Regex("SshTunnelRowButtons\\(tunnel = tunnel, host = host\\)").findAll(body).count(),
        )
        assertEquals(
            "both shapes draw the menu",
            2,
            // The `(?<!fun )` lookbehind is a precision fix, not a loosening:
            // this regex is meant to count *call sites*, and it used to reach the
            // `private fun SshTunnelRowMenu(` declaration too — which only
            // missed because the declaration happened to be single-line. v99
            // gave that declaration one parameter per line, so the count went to
            // 3 for a reason that has nothing to do with the two shapes. The
            // asserted value stays 2 (one call per shape).
            Regex("(?<!fun )SshTunnelRowMenu\\(\n").findAll(body).count(),
        )
    }

    // -------------------------------- wide windows stay pixel-identical

    @Test
    fun theWideShapeKeepsTheDocumentedLineGeometry() {
        // The pre-v93 line, restated so this suite fails with the change that
        // disturbs it: the same Row, the same vertical padding, the same
        // 8/8/4 spacers in the same order. (The confirm bar below is pinned by
        // SshTunnelDeleteV91Test and untouched here.)
        val body = card()
        assertTrue(body.contains("Modifier.fillMaxWidth().padding(vertical = 12.dp)"))
        assertEquals(
            "the wide line keeps its two 8 dp spacers",
            2,
            Regex("Spacer\\(Modifier\\.width\\(8\\.dp\\)\\)").findAll(body).count(),
        )
        assertEquals(
            "the wide line keeps its 4 dp spacer before the ⋮",
            2,
            Regex("Spacer\\(Modifier\\.width\\(4\\.dp\\)\\)").findAll(body).count(),
        )
        val wide = body.substringAfter("} else {")
        assertTrue("the wide shape is still a Row", wide.contains("Row("))
        assertTrue("the wide Row keeps its vertical centring", wide.contains("verticalAlignment = Alignment.CenterVertically"))
    }

    @Test
    fun theWideShapeKeepsTheFittingRowsOutOfTheStack() {
        // The pixel-identity half of the contract, in the arithmetic: every
        // window the v91 round verified as fitting must keep the one-line
        // shape, so the adaptive branch can only engage where the bug
        // reproduces.
        assertFalse(sshTunnelStackedForWindow(852f, sidebarDp = 240f))
        assertFalse(sshTunnelStackedForWindow(1280f, sidebarDp = 240f))
        assertFalse(sshTunnelStackedForWindow(2560f, sidebarDp = 240f))
    }
}
