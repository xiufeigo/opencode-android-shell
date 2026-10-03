package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.evictDebugLocation
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The review panel's 「刷新」 (task: self-healing review refresh).
 *
 * The server builds one `Location`'s services per process
 * (`LayerMap`, `idleTimeToLive: Duration.infinity`, `sdk/src/internal/instances.ts:89`)
 * from a value that captured `project.vcs` at build time, so a directory that
 * became a Git repository afterwards keeps `vcs: undefined` until the process
 * ends: `GET /api/vcs*` answers no provider and `Snapshot.capture()` is off, so
 * **both** review diffs stay empty forever. Upstream's remedy is the manual
 * `opencode reload`; this app's is the panel's explicit refresh, which evicts
 * that **one** location (`DELETE /api/debug/location?location[directory]=<dir>`,
 * `debug.ts:19-31`) and lets the next reads rebuild it.
 *
 * Two invariants are easy to break later and are pinned here:
 * - the evict is **per directory** (never `POST /api/location/reload`, which
 *   rebuilds every location and is the summary card's separate action);
 * - it happens **only** on the user's tap — never from an effect, because it
 *   cancels that directory's in-flight permissions and forms.
 *
 * The wire shape of the route itself is pinned against [MockWebServer] in
 * `V2ApiCatalogExtrasTest.evictDebugLocationDeletesTheDefaultThenTheOverrideDirectory`.
 */
class ReviewReprobeActionTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    private fun reviewSources(): Map<String, String> {
        // The unit-test JVM runs with `app/` as its working directory; the repo
        // root is the other candidate (same fallback as `source`).
        val relative = "src/main/java/com/xiufeigo/opencodeshell/ui/session/review"
        val dir = File(relative).takeIf { it.isDirectory } ?: File("app/$relative")
        return dir.listFiles().orEmpty()
            .filter { it.extension == "kt" }
            .associate { it.name to it.readText().replace("\r\n", "\n") }
    }

    private fun viewModel() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    private fun sessionScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    // ------------------------------------------------------------ one location

    @Test
    fun theRefreshEvictsTheSingleDirectoryThroughTheDebugRoute() {
        val vm = viewModel()
        val reprobe = vm.substringAfter("fun reviewReprobeLocation(").substringBefore("private fun forgetReviewVcsProbe(")

        assertTrue(
            "the refresh must evict the one location",
            reprobe.contains("api.evictDebugLocation(dir)"),
        )
        // Never the every-location reload: that is the summary card's own action
        // (`SessionSummaryActions.rememberLocationReload`), too broad for a
        // review refresh and it rebuilds unrelated projects.
        assertFalse("no POST /api/location/reload on the review path", reprobe.contains("locationReload"))
        // The directory is passed verbatim — the route keys its cache by the raw
        // ref (a normalised one would evict a different entry, see
        // `ReviewVcsReprobe`).
        assertTrue(reprobe.contains("evictDebugLocation(dir)"))
    }

    @Test
    fun theEvictRouteIsTheDebugEndpointWithTheDirectoryKey() {
        // `V2ApiCatalogExtras.evictDebugLocation` — the only caller-facing route
        // the refresh uses. Pin both halves here so a future refactor cannot move
        // the review refresh onto a different (broader) endpoint silently.
        val api = source("src/main/java/com/xiufeigo/opencodeshell/v2/V2ApiCatalogExtras.kt")
        val evict = api.substringAfter("fun V2Api.evictDebugLocation(").substringBefore("\n}")
        assertTrue(evict.contains("""builder("debug/location", true)"""))
        assertTrue(evict.contains(".delete()"))
        assertTrue(evict.contains("located(it, locationDirectory)"))
    }

    @Test
    fun theEvictedDirectoryIsReProbedFromScratch() {
        val vm = viewModel()
        val reprobe = vm.substringAfter("fun reviewReprobeLocation(").substringBefore("private fun forgetReviewVcsProbe(")
        // The read-only workaround (`<dir>/.`) and the "already probed" latch are
        // dropped after a successful evict, so the canonical ref is tried again.
        assertTrue(reprobe.contains("if (ok) forgetReviewVcsProbe(dir)"))
        val forget = vm.substringAfter("private fun forgetReviewVcsProbe(").substringBefore("\n    }")
        assertTrue(forget.contains("reviewVcsRefs.remove(dir)"))
        assertTrue(forget.contains("reviewVcsProbed.remove(dir)"))
    }

    // ------------------------------------------------------- explicit click only

    @Test
    fun noEffectEverFiresTheEvict() {
        val screen = sessionScreen()
        // The single entry point both review surfaces share …
        assertTrue(screen.contains("val reprobeReviewVcs: () -> Unit = {"))
        assertTrue(screen.contains("chatViewModel.reviewReprobeLocation(reviewRootPath)"))

        // … appears exactly three times: its own declaration and the two click
        // surfaces. Anything else would be a second call site — the shape an
        // automatic (effect-driven) evict would need.
        assertEquals(3, Regex("reprobeReviewVcs").findAll(screen).count())
        // The VM call has exactly two callers, both inside a user-tap lambda:
        // 「刷新」 and the no-git CTA's `git init` follow-up (`ReviewGitInit`
        // runs the command, then evicts so the fresh graph sees the repo).
        assertEquals(2, Regex("reviewReprobeLocation").findAll(screen).count())
        assertTrue(screen.contains("if (!gitInitPending) {"))
        assertTrue(screen.contains("if (!reviewRefreshPending) {"))
        assertTrue(screen.contains("onRefreshVcs = reprobeReviewVcs"))
        assertTrue(screen.contains("onRefresh = reprobeReviewVcs"))
    }

    @Test
    fun theReviewPackageNeverEvictsOnItsOwn() {
        // The panel and the mobile tab are pure views: they ask the host through
        // their refresh callback and never reach the route themselves.
        reviewSources().forEach { (name, text) ->
            assertFalse("$name must not call the evict route", text.contains("evictDebugLocation"))
            // The route may appear in prose only (the `ReviewVcsReprobe` note
            // that records why the read-only path exists) — never as code.
            text.lines().forEachIndexed { index, line ->
                if (line.contains("debug/location")) {
                    val trimmed = line.trimStart()
                    assertTrue(
                        "$name:${index + 1} reaches the evict route outside a comment: $line",
                        trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*"),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ the affordance

    @Test
    fun theRefreshActionIsAClickTargetWithABusyState() {
        val panel = reviewSources().getValue("ReviewPanel.kt")
        val action = panel.substringAfter("internal fun ReviewRefreshAction(").substringBefore("/** Tab strip entry")
        assertTrue(action.contains("clickable(enabled = !pending"))
        assertTrue(action.contains("stringResource(R.string.oc_review_refresh)"))
        // The panel header renders it, and only when the host wired the action.
        assertTrue(panel.contains("if (onRefreshVcs != null)"))
        assertTrue(panel.contains("ReviewRefreshAction("))

        // The mobile form hosts the same action in its scope header.
        val tab = reviewSources().getValue("ReviewChangesTab.kt")
        assertTrue(tab.contains("onRefresh: (() -> Unit)? = null"))
        assertTrue(tab.contains("ReviewRefreshAction("))
        assertTrue(tab.contains("if (onRefresh != null)"))
    }

    @Test
    fun theReprobeKeyReReadsEveryReviewRead() {
        val panel = reviewSources().getValue("ReviewPanel.kt")
        assertTrue(panel.contains("rememberReviewProjectVcs(rootPath, reprobeKey)"))
        assertTrue(panel.contains("rememberVcsBase(rootPath, reprobeKey)"))
        assertTrue("the scope diff is re-requested after a refresh", panel.contains("LaunchedEffect(scope, reprobeKey)"))

        val screen = sessionScreen()
        assertTrue(screen.contains("rememberVcsBase(reviewRootPath, reviewReprobeKey)"))
        assertTrue(screen.contains("LaunchedEffect(mobileReviewScope, reviewReprobeKey)"))
        // The host re-reads its own payload too (session diff + vcs branch).
        assertTrue(screen.contains("chatViewModel.refreshDiff()"))
        assertTrue(screen.contains("chatViewModel.reviewVcsInfo {"))
    }

    @Test
    fun theCopyNamesTheSideEffectOfAnEvict() {
        val screen = sessionScreen()
        assertTrue(screen.contains("R.string.oc_review_refresh_done"))
        assertTrue(screen.contains("R.string.oc_review_refresh_failed"))

        val zh = source("src/main/res/values-zh-rCN/strings.xml")
        assertTrue(
            "the success copy must name the cancelled permissions/forms",
            zh.contains("已重建目录状态，进行中的权限/表单可能被取消"),
        )
        val en = source("src/main/res/values/strings.xml")
        assertTrue(en.contains("in-flight permissions/forms may have been cancelled"))
    }

    // ------------------------------------------------------------- wire pin

    /**
     * The refresh's own request, exercised end to end: `DELETE
     * /api/debug/location` with exactly the `location[directory]` key and no
     * other parameter (no `location[project]`, no global flag), 204 accepted.
     */
    @Test
    fun theEvictRequestCarriesOnlyTheDirectoryAndTheDeleteVerb() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val api = V2Api(
                baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
                username = null,
                password = null,
                http = V2Api.defaultClient(),
                // No instance default: `reviewReprobeLocation` builds its handle
                // this way on purpose, so the evict names the one location
                // explicitly rather than leaning on a default (`V2Api.url` would
                // let the override replace that default, but the refresh asks for
                // a directory the handle does not track).
                directory = null,
            )
            api.evictDebugLocation("/home/repo")

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/debug/location", request.requestUrl!!.encodedPath)
            assertEquals("/home/repo", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals(1, request.requestUrl!!.querySize)
            assertEquals("no body on this route", 0L, request.body.size)
        }
    }

    @Test
    fun theRefreshHandleCarriesNoDefaultLocation() {
        val reprobe = viewModel()
            .substringAfter("fun reviewReprobeLocation(")
            .substringBefore("private fun forgetReviewVcsProbe(")

        assertTrue(
            "the evict handle must be built without an instance default location",
            reprobe.contains("transportApi(it, location = null)"),
        )
    }
}
