package com.xiufeigo.opencodeshell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.xiufeigo.opencodeshell.ui.overlay.DialogScrimState
import com.xiufeigo.opencodeshell.ui.overlay.scrimTapDismisses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The hand-rolled modals' backdrops, after the device finding that motivated
 * `HomeProjectEditDialog`'s fix: a full-screen `clickable` scrim dismissed on
 * *every* unconsumed tap (so tapping a dialog's own padding closed it) and put a
 * screen-filling **unlabeled button** at the top of the accessibility tree
 * (`uiautomator dump` landed on it before the dialog it was covering).
 *
 * Every such surface now shares [DialogScrimState] / [DialogScrim] /
 * [com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds], whose tap rule is
 * the host's own ([scrimTapDismisses]): dismiss only outside the card's measured
 * bounds, never from an unmeasured state.
 *
 * The Compose wiring is not reachable from a JVM test, so it is pinned with the
 * repo's established source guard (`DialogLayerPolicyTest`,
 * `DropdownMenuDarkSurfaceTest`). The residue scan walks the whole main source
 * tree and asserts **zero** backdrops are left on the bare-`clickable` idiom.
 *
 * One shape is deliberately *not* residue: the image lightboxes
 * (`ui/markdown/MarkdownImageView.kt`, `ui/session/composer/AttachmentPreviewRow.kt`)
 * are full-screen click targets **by design** — the whole surface is the
 * content, there is no card to protect, and the node carries the
 * `contentDescription` that makes it a named, announced control. The scan
 * therefore flags an *unlabeled* full-screen backdrop; `theLightboxesAreTheOnly
 * LabeledFullScreenTargets` keeps that exclusion honest rather than vacuous.
 */
class DialogScrimResidueTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun mainSources(): List<File> {
        val root = listOf(
            File("src/main/java/com/xiufeigo/opencodeshell"),
            File("app/src/main/java/com/xiufeigo/opencodeshell"),
        ).firstOrNull { it.isDirectory } ?: error("missing main source root")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sorted().toList()
    }

    // --------------------------------------------------------- the tap rule

    @Test
    fun anUnmeasuredCardNeverDismisses() {
        // Before the card is laid out there are no bounds to compare against —
        // dismissing then would close the layer on its own first frame.
        assertFalse(DialogScrimState().dismisses(Offset(0f, 0f)))
        assertFalse(DialogScrimState().dismisses(Offset(120f, 640f)))

        // The decision itself is the host's, so the two scrims cannot drift.
        val card = Rect(40f, 200f, 360f, 800f)
        assertFalse(scrimTapDismisses(card, Offset(200f, 500f)))
        assertTrue(scrimTapDismisses(card, Offset(200f, 100f)))
    }

    // ------------------------------------------------------- the shared code

    @Test
    fun theSharedScrimUsesTheHostsRuleAndCarriesNoSemantics() {
        val scrim = source("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/DialogScrim.kt")
        // The tap detector is a raw gesture detector — `clickable` would add the
        // unlabeled button back to the accessibility tree.
        assertTrue(scrim.contains("detectTapGestures(onTap = {"))
        assertTrue(scrim.contains("pointerInput(state)"))
        // v73: the shared rule gained the backdrop override argument; the call site
        // is still the single one both scrims share.
        assertTrue(scrim.contains("scrimTapDismisses(guardedBounds, tap + scrimOrigin, backdropBounds)"))
        assertTrue(scrim.contains("fun Modifier.dialogCardBounds("))
        assertTrue(scrim.contains("boundsInRoot()"))
        assertTrue(scrim.contains("positionInRoot()"))
        assertFalse("the backdrop must not become a button again", scrim.contains(".clickable"))
        assertFalse(scrim.contains("contentDescription"))
    }

    /** Every surface that draws its own backdrop instead of riding `DialogHost`. */
    private val scrimSurfaces = listOf(
        "ui/home/HomeProjectEditDialog.kt",
        "ui/home/HomeDeleteDialog.kt",
        "ui/nav/MobileTabsDrawer.kt",
        "ui/palette/AgentDetails.kt",
        "ui/palette/DebugLocations.kt",
        "ui/palette/PluginRpc.kt",
        "ui/session/actions/SessionActionsDialogs.kt",
        "ui/session/drawer/SessionBottomSheet.kt",
        "ui/session/drawer/SessionRowDialogs.kt",
        "ui/session/review/ReviewChangesTab.kt",
        "ui/settings/SettingsPanels.kt",
        "ui/settings/SettingsScreen.kt",
        "ui/workspace/WorktreeCreateDialog.kt",
    )

    @Test
    fun everyHandRolledModalRoutesThroughTheSharedScrim() {
        for (relative in scrimSurfaces) {
            val body = source("src/main/java/com/xiufeigo/opencodeshell/$relative")
            assertTrue("$relative: no shared backdrop", body.contains("DialogScrim(scrim"))
            assertTrue("$relative: no remembered state", body.contains("val scrim = rememberDialogScrimState()"))
            assertTrue("$relative: card never reports its bounds", body.contains(".dialogCardBounds(scrim)"))
        }
    }

    @Test
    fun theReferenceDialogKeepsItsOwnBoundsRule() {
        val dialog = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectEditDialog.kt")
        // The marker sits after the size/clip modifiers and *before* the card's
        // own 16 dp padding, so the whole card counts as "inside"…
        val marker = dialog.substringAfter(".dialogCardBounds(scrim)").substringBefore("padding(16.dp)")
        assertTrue(marker.trim().startsWith(".verticalScroll"))
        // …and the backdrop is the shared one, not a private copy of the idiom.
        assertFalse(dialog.contains("detectTapGestures"))
        assertFalse(dialog.contains("scrimTapDismisses"))
    }

    // ------------------------------------------------------- the residue scan

    /**
     * Every modifier chain in [body] that is a full-screen `clickable`
     * backdrop: `fillMaxSize` + a `background` + `clickable` in one chain.
     *
     * The chain is reassembled from the lines around the `clickable` — backwards
     * while the line is a chain continuation (`.foo(`, or the `Modifier`/`modifier`
     * head), skipping the comments the repo puts between modifiers, and forwards
     * while the next line still belongs to the same chain. A paren-balanced
     * *expression* is what separates chains: `SessionDrawer`'s drawer-sized
     * `Modifier.fillMaxSize()` sits in a `.then { … }` of its own, so the row
     * `clickable` below it is never blamed for it.
     */
    private fun fullScreenBackdrops(body: String): List<Pair<Int, String>> {
        val lines = body.lines()
        val found = mutableListOf<Pair<Int, String>>()
        for (j in lines.indices) {
            if (!lines[j].contains(".clickable")) continue
            val parts = mutableListOf(lines[j].substringBefore(".clickable"))
            var k = j - 1
            while (k >= 0) {
                val t = lines[k].trim()
                if (t.isEmpty() || t.startsWith("//")) {
                    k--
                    continue
                }
                if (t.startsWith(".") || t.startsWith("Modifier.") || t.startsWith("modifier.")) {
                    parts.add(0, t)
                    k--
                    continue
                }
                break
            }
            parts.add(lines[j].trim())
            var m = j + 1
            while (m < lines.size && lines[m].trim().startsWith(".")) {
                parts.add(lines[m].trim())
                m++
            }
            val chain = parts.joinToString(" ")
            if (chain.contains("fillMaxSize") && chain.contains("background")) {
                found.add((j + 1) to chain)
            }
        }
        return found
    }

    @Test
    fun noFullScreenClickableBackdropIsLeftAnywhere() {
        val residue = mutableListOf<String>()
        var scanned = 0
        for (file in mainSources()) {
            scanned++
            for ((line, chain) in fullScreenBackdrops(file.readText())) {
                // A labeled full-screen target is the lightbox shape, not a
                // backdrop: the whole surface is its content and it announces
                // itself. An unlabeled one is exactly the residue this class
                // exists to prevent.
                if (chain.contains("contentDescription")) continue
                residue.add("${file.path}:$line  ${chain.trim().take(120)}")
            }
        }
        assertTrue("the sweep must walk the real tree", scanned > 100)
        assertEquals(
            "a full-screen `clickable` scrim is back — use `DialogScrim` + " +
                "`Modifier.dialogCardBounds` instead:\n" + residue.joinToString("\n"),
            emptyList<String>(),
            residue,
        )
    }

    @Test
    fun theLightboxesAreTheOnlyLabeledFullScreenTargets() {
        // The exclusion above must be doing real work: the two image previews
        // are full-screen click targets that stay — they carry the label.
        val labeled = mainSources()
            .flatMap { file -> fullScreenBackdrops(file.readText()).map { file.name to it.second } }
            .filter { (_, chain) -> chain.contains("contentDescription") }
            .map { (name, _) -> name }
            .distinct()
            .sorted()
        assertEquals(listOf("AttachmentPreviewRow.kt", "MarkdownImageView.kt"), labeled)
    }
}
