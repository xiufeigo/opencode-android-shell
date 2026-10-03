package com.xiufeigo.opencodeshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-21: the new-session page's visible exit.
 *
 * The route is a full-screen destination and, on device, BACK was its only exit
 * (verified on the Pixel Tablet run) — the page rendered a title, the workspace
 * picker, the git chip and the Create button, with no ✕ and no arrow.
 *
 * Upstream does not give that page a close control either, so this is not a
 * parity gap that can be closed by copying one: there the draft is a shell
 * **tab** (`new-session/route.tsx` resolves `DraftTab`), and the way out is
 * closing that tab in the strip; `new-session/view.tsx:98-104` pins only the
 * summary trigger into the page's `absolute inset-x-0 top-0 h-12` band. This
 * shell's draft route has no tab of its own — the top strip shows Home as the
 * active item, which is not an exit the user can read as "leave this page" — so
 * the page adds the Android-conventional leading back arrow, reusing the session
 * header's own back chevron (`ic_oc_chevron_down` rotated 90°, `oc_back`) so the
 * two surfaces are one control to TalkBack.
 *
 * The page has no JVM-renderable surface in this project (no Compose test
 * dependency), so this is a source pin: the control exists, it is wired to
 * [onBack], it sits at the leading edge, and it is announced.
 */
class NewSessionBackExitTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun screen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt")

    private fun window(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("missing window start: $from", start >= 0)
        val end = src.indexOf(to, start + from.length)
        assertTrue("missing window end: $to", end > start)
        return src.substring(start, end)
    }

    @Test
    fun thePageRendersALeadingBackControlWiredToOnBack() {
        val page = window(screen(), "Box(\n        modifier = modifier", "Column(\n            horizontalAlignment")
        assertTrue("the page mounts the back control", page.contains("BackChevronButton("))
        assertTrue("wired to the route's own exit", page.contains("onClick = onBack,"))
        assertTrue(
            "at the leading edge of the top band, inset like upstream's summary band",
            page.contains(".align(Alignment.TopStart)") && page.contains(".padding(start = 8.dp, top = 8.dp)"),
        )
    }

    @Test
    fun theControlIsTheSessionHeadersOwnBackChevron() {
        val button = window(screen(), "private fun BackChevronButton(", "private fun SummaryToggle(")
        assertTrue(
            "the same glyph the session header rotates for its back chevron",
            button.contains("painterResource(R.drawable.ic_oc_chevron_down)") &&
                button.contains("Modifier.rotate(90f).size(16.dp)"),
        )
        assertTrue("the shared back description", button.contains("stringResource(R.string.oc_back)"))
        assertTrue(
            "and it is a real button to TalkBack, not a bare Canvas",
            button.contains(".clickable(role = Role.Button, onClick = onClick)") &&
                button.contains(".semantics { contentDescription = backLabel }"),
        )
        val header = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt")
        assertTrue(
            "the header's back control is the same pair (glyph + description)",
            header.contains("iconRes = R.drawable.ic_oc_chevron_down") &&
                header.contains("contentDescription = stringResource(R.string.oc_back)"),
        )
    }

    @Test
    fun backStaysTheOtherExitAndIsStillRegistered() {
        // The arrow is an addition, not a replacement: the system BACK gesture
        // keeps its own handler (pinned by `BackNavigationWiringTest` too).
        assertTrue(screen().contains("BackHandler { onBack() }"))
    }

    @Test
    fun theUpstreamFindingIsRecordedWhereTheControlLives() {
        val kdoc = window(screen(), " * **Exit (B-21):**", "@Composable\nfun NewSessionScreen(")
        assertTrue("the page documents that upstream has no control of its own", kdoc.contains("no close/back control"))
        assertTrue("and why: the draft is a tab there", kdoc.contains("new-session/route.tsx"))
        assertTrue("the added control is named", kdoc.contains("BackChevronButton"))
    }

    @Test
    fun thePageStillOwnsNoDraftTabOfItsOwn() {
        // The reason the arrow is needed rather than a tab strip entry: the route
        // is a destination, not a tab (`ComposerDraftIsolationTest` pins the other
        // half — no `ComposerDrafts` use here).
        val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        val route = window(app, "composable(\n                        route = Route.NEW_SESSION", "composable(Route.SESSION)")
        assertTrue("the route is a plain destination", route.contains("NewSessionScreen("))
        assertFalse("with no tab registration of its own", route.contains("SessionTabRef"))
        assertTrue(
            "and the same call site keeps the pop it always had",
            route.contains("onBack = { navController.popBackStack() },"),
        )
    }
}
