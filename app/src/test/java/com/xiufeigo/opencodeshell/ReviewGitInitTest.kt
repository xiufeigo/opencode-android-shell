package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.review.ReviewGitInit
import com.xiufeigo.opencodeshell.v2.ShellCommandInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The 「Create Git repository」 CTA (task: wire the mobile no-git action).
 *
 * Upstream wires nothing: `SessionReviewEmptyNoGitV2`'s `onInitGit`
 * (`session-review-empty-no-git-v2.tsx:11-27`) has no caller in the desktop
 * tree, the desktop's own no-git state renders copy without a button
 * (`session/review/view.tsx:263-272`), and the v2 server has no init route
 * (`protocol/src/groups/vcs.ts:23-95` = get / base / status / branch.list /
 * diff). The app therefore runs `git init` over the location-scoped command
 * route that does exist — `POST /api/shell` — and reads the exit code back on
 * `GET /api/shell/{id}` ([ReviewGitInit]).
 *
 * Pinned here: the exit-code rule, the poll budget, and the source facts that
 * make the button reachable on both review surfaces.
 */
class ReviewGitInitTest {

    private fun shell(json: String) = ShellCommandInfo.fromJson(JSONObject(json))

    private fun source(relative: String): String {
        // The unit-test JVM runs with `app/` as its working directory; the repo
        // root is the other candidate (same fallback as `ReviewReprobeActionTest`).
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    private fun sessionScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private fun reviewGitInit() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewGitInit.kt")

    // -------------------------------------------------------------- exit code

    @Test
    fun onlyAnExitZeroCountsAsCreated() {
        assertTrue(ReviewGitInit.succeeded(shell("""{"id":"sh_1","status":"exited","exit":0}""")))
        assertFalse(ReviewGitInit.succeeded(shell("""{"id":"sh_1","status":"exited","exit":128}""")))
        // Still running: `exit` is absent — the wire only carries it once finished.
        assertFalse(ReviewGitInit.succeeded(shell("""{"id":"sh_1","status":"running"}""")))
        assertFalse(ReviewGitInit.succeeded(null))
    }

    @Test
    fun theWaitStopsAtTheTerminalStatusAndAtTheBudget() {
        val running = shell("""{"id":"sh_1","status":"running"}""")
        val exited = shell("""{"id":"sh_1","status":"exited","exit":0}""")
        assertTrue(ReviewGitInit.keepPolling(running, 0))
        assertFalse(ReviewGitInit.keepPolling(exited, 0))
        // A failed read ends the wait too: the command exists, its exit code is
        // simply not readable.
        assertFalse(ReviewGitInit.keepPolling(null, 0))
        assertFalse(ReviewGitInit.keepPolling(running, ReviewGitInit.POLL_ATTEMPTS))
        assertEquals("git init", ReviewGitInit.COMMAND)
    }

    // ------------------------------------------------------------------ wire

    @Test
    fun theRepositoryIsCreatedThroughTheLocationScopedCommandRoute() {
        val text = reviewGitInit()
        assertTrue(text.contains("api.shellCreate("))
        assertTrue(text.contains("command = COMMAND"))
        // The directory travels as `cwd` — never interpolated into the command
        // line, which would need quoting — and as the location scope.
        assertTrue(text.contains("cwd = directory"))
        assertTrue(text.contains("locationDirectory = directory"))
        assertTrue(text.contains("api.shellGet(created.id, directory)"))
        // No invented route: there is no server-side init endpoint to call.
        assertFalse(text.contains("vcs/init"))
        assertFalse(text.contains("project/init"))
    }

    // ------------------------------------------------------------- affordance

    @Test
    fun bothReviewSurfacesReceiveTheActionAndItsBusyState() {
        val screen = sessionScreen()
        assertEquals(2, Regex("onInitGit = initReviewGit").findAll(screen).count())
        assertEquals(2, Regex("gitPending = gitInitPending").findAll(screen).count())
        assertTrue(screen.contains("var gitInitPending by remember(activeSession.id)"))
        assertTrue(screen.contains("val reviewGitInit = rememberReviewGitInit()"))
        assertTrue(screen.contains("reviewGitInit(reviewRootPath) { ok ->"))
    }

    @Test
    fun theCopyExistsInEveryLocale() {
        val keys = listOf("oc_review_no_git_done", "oc_review_no_git_failed")
        for (file in listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-zh-rCN/strings.xml",
            "src/main/res/values-zh-rTW/strings.xml",
        )) {
            val text = source(file)
            keys.forEach { assertTrue("$file is missing $it", text.contains("name=\"$it\"")) }
        }
        assertTrue(source("src/main/res/values/strings.xml").contains("Could not create the Git repository"))
        assertTrue(source("src/main/res/values-zh-rCN/strings.xml").contains("创建 Git 仓库失败"))
    }
}
