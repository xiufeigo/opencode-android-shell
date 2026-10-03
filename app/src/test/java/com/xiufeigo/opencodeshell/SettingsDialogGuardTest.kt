package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.PairingModel
import com.xiufeigo.opencodeshell.ui.settings.mcpAddSpec
import com.xiufeigo.opencodeshell.ui.settings.splitCommandTokens
import com.xiufeigo.opencodeshell.ui.settings.validateCredentialLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Submit guards behind the settings modal dialogs (credential rename, MCP
 * add) plus the QR matrix invariants the pairing bitmap path relies on:
 * a dialog button enables exactly when its guard returns non-null, and the
 * bitmap builder indexes `modules[0]`, so the matrix must never be empty.
 */
class SettingsDialogGuardTest {

    @Test
    fun renameSaveEnablesOnlyOnANonBlankLabel() {
        assertEquals("ops", validateCredentialLabel("  ops  "))
        assertNull(validateCredentialLabel(null))
        assertNull(validateCredentialLabel(""))
        assertNull(validateCredentialLabel("   "))
    }

    @Test
    fun mcpAddEnablesOnlyWithATransportPayload() {
        assertTrue(mcpAddSpec("gh", remote = false, command = "npx  -y  pkg", url = "") != null)
        assertNull(mcpAddSpec("  ", remote = false, command = "npx", url = ""))
        assertNull(mcpAddSpec("n", remote = false, command = "   ", url = ""))
        assertNull(mcpAddSpec("n", remote = true, command = "", url = "   "))
    }

    @Test
    fun commandTokensSplitOnWhitespaceRunsOnly() {
        assertEquals(listOf("npx", "-y", "pkg"), splitCommandTokens("  npx   -y pkg "))
        assertTrue(splitCommandTokens("   ").isEmpty())
    }

    @Test
    fun longPairingUrlsStillYieldABitmapReadyMatrix() {
        val url = PairingModel.pairingUrl("a-long-secret-value-0123456789", "http://192.168.1.10:4095")!!
        val modules = PairingModel.qrModules(url)
        assertTrue(modules.isNotEmpty())
        assertEquals(modules.size, modules[0].size)
        assertTrue(modules.any { row -> row.any { it } })
        for (i in 0 until PairingModel.QR_BORDER) {
            assertFalse(modules[i].any { it })
            assertFalse(modules.any { it[i] })
        }
    }
}
