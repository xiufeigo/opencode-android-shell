package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionDefaultTabTest {
    private fun layoutPatch(): File =
        listOf(
            File("../frontend/patches/android-webview-z-session-default-tab.patch"),
            File("../../frontend/patches/android-webview-z-session-default-tab.patch"),
            File("frontend/patches/android-webview-z-session-default-tab.patch"),
        ).firstOrNull { it.isFile } ?: error("missing session default tab patch")

    private fun contextPatch(): File = layoutPatch()

    @Test
    fun enteringSessionDefaultsToSessionWithHandGate() {
        val text = layoutPatch().readText()
        assertTrue(text.contains("__ocAndroidCtxTap"))
        assertTrue(text.contains("requestAndroidContext()"))
        assertTrue(text.contains("if (!consumeAndroidContext()) return"))
        assertTrue(text.contains("clearAndroidContext()"))
        assertTrue(text.contains("setStore(\"mobileTab\", \"session\")"))
        assertTrue(text.contains("mobileTab: \"session\""))
    }

    @Test
    fun headerRingSetsHandGate() {
        val text = contextPatch().readText()
        assertTrue(text.contains("__ocAndroidCtxTap"))
        assertTrue(text.contains("requestAndroidContext()"))
    }

    @Test
    fun handoffFiltersContextWithoutLightingReview() {
        val text = layoutPatch().readText()
        assertTrue(text.contains("filter((t: string) => t !== \"context\")"))
        assertTrue(text.contains("tabs().close(\"context\")"))
        assertTrue(text.contains("tabs().close(\"review\")"))
        assertTrue(text.contains("tabs().setAll([])"))
    }
}
