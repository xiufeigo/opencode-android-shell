package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins for the connect page's scan half (`ui/servers/ConnectServerScreen.kt`
 * + `QrScan.kt`): upstream `servers/connect/screen.tsx:75-84,138-157`'s
 * button and `onScan` behaviour, delivered through the system-scanner intent.
 */
class ConnectScanRenderTest {

    private fun screen(): String {
        val relative = "src/main/java/com/xiufeigo/opencodeshell/ui/servers/ConnectServerScreen.kt"
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    @Test
    fun theScanButtonHandsOffToTheSystemScanner() {
        val body = screen()
        assertTrue(body.contains("QrScan.scanIntent()"))
        assertTrue(body.contains("scanLauncher.launch("))
        assertTrue(body.contains("ActivityNotFoundException"))
        assertTrue(body.contains("R.string.oc_connect_scan_unavailable"))
        assertTrue(body.contains("R.string.oc_pair_scan"))
    }

    @Test
    fun aScanFillsTheFormAndSubmitsLikeUpstreamOnScan() {
        val body = screen()
        assertTrue(body.contains("QrScan.pairingFromResult(payload)"))
        assertTrue(body.contains("url = pairing.urls.first()"))
        assertTrue(body.contains("password = pairing.password"))
    }

    @Test
    fun anInvalidScanUsesThePairingInvalidSentence() {
        // `server.connect.scan.invalid` has no `oc_connect_*` twin; the
        // `oc_pair_invalid` sentence says the same thing.
        assertTrue(screen().contains("R.string.oc_pair_invalid"))
    }

    @Test
    fun theScanKeysExistInAllThreeLocales() {
        val keys = listOf(
            "oc_connect_scan_unavailable",
            "oc_pair_scan_description",
            "oc_pair_scan_unavailable",
        )
        for (locale in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val xml = listOf(
                File("src/main/res/$locale/strings.xml"),
                File("app/src/main/res/$locale/strings.xml"),
            ).first { it.isFile }.readText()
            val missing = keys.filterNot { xml.contains("name=\"$it\"") }
            assertEquals("$locale is missing $missing", emptyList<String>(), missing)
        }
    }
}
