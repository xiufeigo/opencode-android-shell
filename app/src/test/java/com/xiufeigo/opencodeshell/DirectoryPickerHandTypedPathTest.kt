package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.pickerCanConfirm
import com.xiufeigo.opencodeshell.ui.home.pickerNameMatches
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v56 P2 — 「Move to…」 手输路径: two dead ends on the same card, both measured
 * on the device (`research/v56_verify/tD/07_badpath.png`, `25_noclick.png`,
 * `28_namesearch.png`, `30_sugg4.png`).
 *
 * **1. The greyed 「选择此目录」 dispatched nothing.** The v54 `verifiedPath`
 * gate was right — a hand-typed path must not be selected unread — but it was
 * wired `clickable(enabled = canConfirm)`, and a *disabled* Compose node emits
 * no click at all. The "browse the unverified target instead" branch in
 * `tapConfirm` therefore had no caller: the one action the button was allowed
 * to take was the one action it was forbidden from taking, and typing a path
 * by hand went nowhere. Both entry points now dispatch unconditionally and
 * the gate decides only what the tap may *submit* — so a greyed tap browses,
 * the listing verifies the target, the button lights up, and the two-stage
 * confirm runs from there.
 *
 * **2. The suggestions were empty for every input shape.** The non-empty tail
 * went to `searchDirectories` — a tree-wide fuzzy read that the transport
 * scopes to the server's location root and truncates at its own limit — and
 * the client's own `filterScoped` then dropped all of the survivors as
 * out-of-scope. Zero rows for a name typed while browsing a directory that
 * *contains* it, and the same for a hand-typed absolute path. A truncated
 * global search cannot be re-scoped after the fact, so the match now happens
 * where the candidates are: the scoped directory is listed and the tail is
 * matched against its children (upstream's segment walk, which the empty-tail
 * branch already did).
 */
class DirectoryPickerHandTypedPathTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun picker() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/DirectoryPickerDialog.kt")

    // ------------------------------------------- 1. grey is a look, not a lock

    @Test
    fun aGreyedConfirmStillDispatchesTheTap() {
        val body = picker()
        assertFalse(
            "a disabled Compose node emits no click, so the greyed 「选择此目录」 " +
                "left `tapConfirm`'s browse-first branch with no caller — the whole " +
                "hand-typed path was a dead end (v56 tD_25_noclick.png)",
            body.contains("clickable(enabled = canConfirm"),
        )
        assertTrue(
            "the browse-first branch must stay: it is what a greyed tap runs now",
            body.contains("if (!pickerCanConfirm(target, atDrives, verifiedPath))"),
        )
        assertTrue(
            "…and it browses the target rather than arming it",
            body.contains("if (target.isNotEmpty() && target != currentPath) navigate(target)"),
        )
    }

    @Test
    fun theGateItselfIsUnchangedSoNothingUnverifiedCanBeSelected() {
        // The dispatch became unconditional; the decision did not. A directory
        // the server has never listed still cannot be armed or submitted, which
        // is what v54 P1 fixed and must survive the v56 unwiring.
        assertFalse(pickerCanConfirm(target = "", atDrives = false, verifiedPath = "/tmp"))
        assertFalse(pickerCanConfirm(target = "/tmp", atDrives = false, verifiedPath = null))
        assertFalse(pickerCanConfirm(target = "/tmp", atDrives = true, verifiedPath = "/tmp"))
        assertFalse(
            "a verified *other* directory is not this target",
            pickerCanConfirm(target = "/tmp/other", atDrives = false, verifiedPath = "/tmp"),
        )
        assertTrue(pickerCanConfirm(target = "/tmp", atDrives = false, verifiedPath = "/tmp"))
    }

    // --------------------------------------------------- 2. suggestions return

    @Test
    fun theSuggestionReadIsScopedToTheDirectoryItMatchesIn() {
        val body = picker()
        assertFalse(
            "a tree-wide `searchDirectories` hit is scoped to the server's " +
                "location root and truncated at its limit, so `filterScoped` threw " +
                "every survivor away and the dropdown rendered nothing " +
                "(v56 tD_23_sugg.png, tD_28_namesearch.png, tD_30_sugg4.png)",
            body.contains("chatViewModel.searchDirectories(scope.name"),
        )
        assertTrue(
            "the candidates have to come from the scope itself — upstream's " +
                "segment walk lists the scoped directory",
            body.contains("chatViewModel.listFiles(\n            scope.directory,"),
        )
        assertTrue(
            "…and the tail decides which of its children are offered",
            body.contains("pickerNameMatches(it.name, scope.name)"),
        )
    }

    @Test
    fun theTailIsMatchedTheWayUpstreamFuzziesASegment() {
        // An empty tail (`~`, a root, a trailing `/`) asks for the scope's
        // children, not for one of them by name.
        assertTrue(pickerNameMatches(name = "opencode", query = ""))
        assertTrue(pickerNameMatches(name = "opencode", query = "   "))
        // A half-typed segment still offers its directory…
        assertTrue(pickerNameMatches(name = "opencode", query = "openc"))
        assertTrue(pickerNameMatches(name = "opencode-android-shell", query = "opencode"))
        assertTrue(pickerNameMatches(name = "opencode", query = "OPEN"))
        // …a subsequence match is upstream's fuzzy, not a substring one…
        assertTrue(pickerNameMatches(name = "opencode", query = "ocde"))
        // …and an absent name stays absent.
        assertFalse(pickerNameMatches(name = "opencode", query = "zzz"))
        assertFalse(pickerNameMatches(name = "opencode", query = "opencodex"))
    }
}
