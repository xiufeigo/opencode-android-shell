package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Source pins for the mobile Changes parity fix (P1): the narrow form mounts
 * `ReviewChangesTab` directly and used to pass `diffs` only — no scope, no
 * branch, no baseLabel — so the page had no branch chip, no Git / Branch /
 * Last-turn switch and no 「相对 name@ref」 base line, and stayed pinned to
 * LAST_TURN. The desktop `SessionReviewPanel` call site already passes all
 * three; the mobile branch now hands the same inputs down.
 */
class MobileReviewScopeParityTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        // CRLF sources would break `\n`-sensitive windows otherwise.
        return file.readText().replace("\r\n", "\n")
    }

    private fun sessionScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private fun reviewChangesTab() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewChangesTab.kt")

    private fun reviewPanel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewPanel.kt")

    @Test
    fun mobileChangesReceivesScopeBranchAndBaseLabel() {
        val call = sessionScreen()
            .substringAfter("MobileForm.Tab.CHANGES -> {")
            .substringBefore("MobileForm.Tab.FILES ->")
        assertTrue(call.contains("ReviewScopes.diffsFor(mobileReviewScope, state.diff, vcsDiffs)"))
        assertTrue(call.contains("scope = mobileReviewScope"))
        assertTrue(call.contains("baseLabel = ReviewVcsBase.label(vcsBase)"))
        assertTrue(call.contains("branch = reviewBranch"))
        // the switch is wired (and the VCS scopes actually fetch): the page asks
        // the screen's one request seam, which is what reaches the ViewModel.
        assertTrue(call.contains("onScopeChange = { mobileReviewScopeName = it.name }"))
        assertTrue(call.contains("requestVcsDiff(mobileReviewScope)"))
        assertTrue(sessionScreen().contains("chatViewModel.reviewVcsDiff(reviewScope.name.lowercase())"))
    }

    /**
     * Step two of the parity fix: passing the switch down is not enough while
     * the page's own default is still `LAST_TURN` and its VCS verdict is still
     * `reviewBranch != null`. The desktop panel opens on
     * `ReviewScopes.defaultScope()` — upstream's `git` whenever the project has
     * a repository (`review/model.ts:40 mode() ?? "git"`) — and gates its
     * 「Create a Git repository」 CTA on the project-table verdict; the mobile
     * page must resolve both the same way, off the same helpers.
     */
    @Test
    fun mobileChangesOpensOnTheSameDefaultScopeAsTheDesktopPanel() {
        val screen = sessionScreen()
        val call = screen
            .substringAfter("MobileForm.Tab.CHANGES -> {")
            .substringBefore("MobileForm.Tab.FILES ->")

        // The default is resolved from availability, not seeded to LAST_TURN.
        assertTrue(call.contains("ReviewScopes.available("))
        assertTrue(call.contains("ReviewScopes.defaultScope(availableScopes)"))
        assertTrue(
            "a stored scope only survives while it is still offered",
            call.contains(".firstOrNull { it.name == mobileReviewScopeName && it in availableScopes }"),
        )
        // …which requires the stored name to be able to be "unset".
        assertTrue(screen.contains("var mobileReviewScopeName by rememberSaveable { mutableStateOf<String?>(null) }"))
        assertFalse(
            "the old LAST_TURN seed is what drifted from the panel",
            screen.contains("mutableStateOf(ReviewChangeScope.LAST_TURN.name)"),
        )
    }

    @Test
    fun mobileChangesTakesItsVcsVerdictFromTheProjectTable() {
        val screen = sessionScreen()
        val call = screen
            .substringAfter("MobileForm.Tab.CHANGES -> {")
            .substringBefore("MobileForm.Tab.FILES ->")

        assertTrue("hasVcs is project.vcs, not “a branch answered”", call.contains("hasVcs = reviewProjectVcs"))
        assertFalse(call.contains("hasVcs = if (reviewBranch != null)"))
        assertTrue(
            "the verdict comes from the same probe as the panel's",
            screen.contains("rememberReviewProjectVcs(reviewRootPath, reviewReprobeKey)"),
        )
    }

    @Test
    fun mobileChangesGatesTheCreateRepoStateOnTheSameRuleAsThePanel() {
        val screen = sessionScreen()
        val call = screen
            .substringAfter("MobileForm.Tab.CHANGES -> {")
            .substringBefore("MobileForm.Tab.FILES ->")

        // The narrow form passes `diffs` but never `noGit`, so
        // `ReviewChangesTab`'s `noGit = false` default kept the 「Create a Git
        // repository」 empty state — the panel's, and upstream's
        // `mode() === "turn" && project() && !project().vcs` — unreachable on a
        // phone. The gate comes from the same helper as the desktop panel's.
        assertTrue("noGit comes from ReviewScopes.noGit", call.contains("noGit = ReviewScopes.noGit("))
        assertTrue(call.contains("hasVcs = reviewProjectVcs"))
        assertTrue(
            "the CTA never displaces a non-empty list",
            call.contains("hasChanges = mobileVisibleDiffs.isNotEmpty()"),
        )
        // Parity with the panel, which owns the other half of the same rule.
        assertTrue(
            reviewPanel().contains("ReviewScopes.noGit(hasVcs, projectVcs, visibleDiffs.isNotEmpty())"),
        )
        // The state itself still needs a repository-less project, so it can never
        // be reached by "the read answered nothing" (v8-B's false CTA).
        assertFalse(call.contains("noGit = vcsDiffFailed"))
        assertFalse(call.contains("noGit = vcsDiffPending"))
    }

    @Test
    fun changesTabRendersTheScopeChromeOnlyWhenWired() {
        val tab = reviewChangesTab()
        // the mobile header (branch chip + scope switch) — behind `onScopeChange`
        assertTrue(tab.contains("ReviewScopeHeader("))
        assertTrue(tab.contains("if (onScopeChange != null)"))
        assertTrue(tab.contains("ReviewScopes.labelRes(option)"))
        assertTrue(tab.contains("ReviewScopes.enabled(option, available, vcsWired)"))
        assertTrue(tab.contains("onScopeChange(option)"))
    }

    @Test
    fun desktopPanelKeepsOwningItsScopeChromeAlone() {
        // The desktop panel still renders its own switch and still passes the
        // same args to the tab — no behaviour change for the wide form.
        val panel = reviewPanel()
        assertTrue(panel.contains("ReviewScopes.enabled(option, available, vcsWired)"))
        val call = panel
            .substringAfter("else -> ReviewChangesTab(")
            .substringBefore(")\n                }")
        assertTrue(call.contains("scope = scope"))
        assertTrue(call.contains("baseLabel = vcsBaseLabel"))
    }
}
