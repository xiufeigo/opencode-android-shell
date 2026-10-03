package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins for the `DialogPairing` QR block and the in-app photo decode
 * (`ui/settings/PairingPanel.kt` + `ui/servers/QrScan.kt`), plus the three
 * locales of every key they render.
 */
class PairingQrPanelPinTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    @Test
    fun thePanelRendersThePairingUrlAsQr() {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/PairingPanel.kt")
        assertTrue(body.contains("PairingModel.qrModules("))
        assertTrue(body.contains("R.string.oc_pair_qr"))
        assertTrue(body.contains("FilterQuality.None"))
    }

    @Test
    fun photoDecodeRunsThroughTheSharedPairingPath() {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/PairingPanel.kt")
        assertTrue(body.contains("PhotoPickRow("))
        assertTrue(body.contains("QrScan.pickImageIntent()"))
        assertTrue(body.contains("QrScan.decodeImage("))
        // Scan and photo land on the same manual-entry decode path.
        assertTrue(body.contains("codeInput = payload"))
        assertTrue(body.contains("R.string.oc_pair_scan_photo_failed"))
    }

    @Test
    fun theDecodeHalfIsTheZxingPixelPath() {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/QrScan.kt")
        assertTrue(body.contains("decodeQrFromPixels"))
        assertTrue(body.contains("RGBLuminanceSource"))
        assertTrue(body.contains("source.invert()"))
    }

    @Test
    fun everyQrKeyExistsInAllThreeLocales() {
        val keys = listOf(
            "oc_pair_qr",
            "oc_pair_scan_photo",
            "oc_pair_scan_photo_description",
            "oc_pair_scan_photo_failed",
        )
        for (locale in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val xml = source("src/main/res/$locale/strings.xml")
            val missing = keys.filterNot { xml.contains("name=\"$it\"") }
            assertEquals("$locale is missing $missing", emptyList<String>(), missing)
        }
    }
}
