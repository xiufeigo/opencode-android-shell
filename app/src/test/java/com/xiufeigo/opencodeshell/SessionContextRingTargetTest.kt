package com.xiufeigo.opencodeshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v9-3: the header's context ring (`content-desc="Context usage"`) opened the
 * **Changes** tab instead of the Context view — three taps, three Changes
 * panels. The ring's tap handler set `reviewOpen` (desktop) or switched to
 * `MobileForm.Tab.CHANGES` (phone) and left the *selected tab* at its default,
 * which is `ReviewPanelTab.CHANGES`.
 *
 * Upstream's tap goes to the context surface — `openSessionContext`
 * (`session-context-usage.tsx:30-37`) opens the review panel **and** the
 * `context` tab; on mobile `SessionContextTab` *is* the `usage` view
 * (`session/screen.tsx:307-309`). The pins below hold the tap to those two
 * targets, and to the fact that they are the two surfaces the context payload
 * load keys on — a ring that opens a panel which never loads its payload would
 * look just as broken.
 *
 * Source pin, not a Compose test: the repo's established answer for screen
 * wiring ([SessionScreenExportErrorTest], [SessionContextExportStateTest]).
 */
class SessionContextRingTargetTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        // CRLF sources would break `\n`-sensitive windows otherwise.
        return file.readText().replace("\r\n", "\n")
    }

    private fun sessionScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    /** The ring's `onOpenContext` lambda alone: from its argument to the menu's. */
    private fun ringTap(): String {
        val body = sessionScreen()
        val start = body.indexOf("onOpenContext = {")
        val end = body.indexOf("onMenuAction = { action ->", start)
        assertTrue("ring tap not found", start >= 0 && end > start)
        return body.substring(start, end)
    }

    @Test
    fun theRingOpensTheContextTabNotTheDefaultReviewTab() {
        val tap = ringTap()
        // desktop: the review sidebar, **on the context tab** (its default is
        // CHANGES — the tab v9 saw three times)
        assertTrue(tap.contains("reviewOpen = true"))
        assertTrue(tap.contains("reviewTabName = ReviewPanelTab.CONTEXT.name"))
        // phone: the usage view, which is upstream's `SessionContextTab` on a
        // narrow layout — the same surface `⋮ → Usage` opens
        assertTrue(tap.contains("usageOpen = true"))
        assertTrue(tap.contains("summaryOpen = false"))
        // and the bug itself must not come back: no landing on Changes
        assertFalse("the ring must not open the diff view", tap.contains("MobileForm.Tab.CHANGES"))
    }

    @Test
    fun theRingTargetIsTheSurfaceThatLoadsThePayload() {
        val body = sessionScreen()
        // `contextVisible` is what the payload effect keys on; the tap's two
        // targets have to be exactly those two expressions, or the ring opens an
        // empty projection
        assertTrue(body.contains("reviewOpen && reviewTab == ReviewPanelTab.CONTEXT"))
        assertTrue(body.contains("SessionContextLoad.needsLoad(contextLoadedKey, contextVisible, key)"))
        // the effect pulls the same raw frames the panel renders
        assertTrue(body.contains("val result = chatViewModel.fetchExportFrameResult(activeSession.id, reviewRootPath)"))
    }

    @Test
    fun theTapAlwaysOpensAndOnlyTheLongPressShowsTheTooltip() {
        val tap = ringTap()
        // Upstream closes the tab when it is already the visible one
        // (`openContext`'s `contextVisible()` branch, `:93-100`). This shell
        // always opens on purpose: the phone has no other ring affordance
        // besides the long-press tooltip, and a tap that sometimes closes is a
        // tap that reads as broken. Pinned so the deviation is deliberate.
        assertFalse("the ring tap is open-only", tap.contains("reviewOpen = !reviewOpen"))
        assertFalse("the ring tap is open-only", tap.contains("usageOpen = !usageOpen"))
        val popover = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/ContextUsagePopover.kt")
        assertTrue("the tooltip keeps the long press", popover.contains("onLongClick = { open = !open }"))
    }
}
