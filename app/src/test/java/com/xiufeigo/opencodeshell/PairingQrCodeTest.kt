package com.xiufeigo.opencodeshell

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.xiufeigo.opencodeshell.ui.settings.PairingModel
import com.xiufeigo.opencodeshell.ui.servers.QrScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure QR half of device pairing: `PairingModel.qrModules` (uqr `encode`
 * over zxing — `settings/pairing/pairing.tsx`'s `renderSVG(value, { border: 4 })`)
 * and `QrScan.decodeQrFromPixels` (the `qr-scanner` decode of
 * `servers/connect/scanner.tsx` minus the camera). Bitmap glue stays out.
 */
class PairingQrCodeTest {

    private val url = PairingModel.pairingUrl("secret", "http://10.0.2.2:4096")!!

    /** ARGB samples of [modules], [scale] pixels per module — `Bitmap.getPixels` order. */
    private fun pixels(modules: Array<BooleanArray>, scale: Int = 4, invert: Boolean = false): IntArray {
        val width = modules[0].size * scale
        val height = modules.size * scale
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dark = modules[y / scale][x / scale] != invert
                out[y * width + x] = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return out
    }

    @Test
    fun theRenderMatchesUpstreamUqrParameters() {
        // `renderSVG(value, { border: 4, … })` + uqr `encode`'s `ecc = 'L'`.
        assertEquals(4, PairingModel.QR_BORDER)
        assertEquals(ErrorCorrectionLevel.L, PairingModel.QR_ECC)
    }

    @Test
    fun theMatrixCarriesTheQuietZoneAndARealFinderPattern() {
        val modules = PairingModel.qrModules(url)
        val symbol = modules.size - 2 * PairingModel.QR_BORDER
        assertTrue("symbol must be a QR version size, was $symbol", symbol >= 21 && (symbol - 17) % 4 == 0)
        assertEquals(modules.size, modules[0].size)

        // Quiet zone: every module inside the border is light.
        for (i in 0 until PairingModel.QR_BORDER) {
            assertFalse(modules[i].any { it })
            assertFalse(modules[modules.lastIndex - i].any { it })
            assertFalse(modules.any { it[i] })
            assertFalse(modules.any { it[it.lastIndex - i] })
        }
        // Top-left finder: dark 7×7 ring with a dark 3×3 core at (border, border).
        val b = PairingModel.QR_BORDER
        assertTrue(modules[b][b])
        assertTrue(modules[b][b + 6])
        assertTrue(modules[b + 6][b])
        assertFalse(modules[b + 1][b + 1])
        assertTrue(modules[b + 3][b + 3])
    }

    @Test
    fun theMatrixRoundTripsThroughThePixelDecoder() {
        val modules = PairingModel.qrModules(url)
        val width = modules[0].size
        assertEquals(url, QrScan.decodeQrFromPixels(pixels(modules), width * 4, modules.size * 4))
    }

    @Test
    fun lightOnDarkTerminalCodesStillDecode() {
        // `scanner.tsx`: `scanner.setInversionMode("both")`.
        val modules = PairingModel.qrModules(url)
        val width = modules[0].size
        assertEquals(url, QrScan.decodeQrFromPixels(pixels(modules, invert = true), width * 4, modules.size * 4))
    }

    @Test
    fun aScannedPairingLinkYieldsItsDetails() {
        val modules = PairingModel.qrModules(url)
        val width = modules[0].size
        val payload = QrScan.decodeQrFromPixels(pixels(modules), width * 4, modules.size * 4)
        val details = QrScan.pairingFromResult(payload)
        assertEquals("secret", details?.password)
        assertEquals(listOf("http://10.0.2.2:4096"), details?.urls)
    }

    @Test
    fun picturesWithoutAQrCodeDecodeToNothing() {
        val blank = IntArray(64 * 64) { 0xFFFFFFFF.toInt() }
        assertNull(QrScan.decodeQrFromPixels(blank, 64, 64))
        assertNull(QrScan.decodeQrFromPixels(IntArray(16), 4, 4))
    }

    @Test
    fun impossibleDimensionsAreRejectedBeforeDecoding() {
        assertNull(QrScan.decodeQrFromPixels(IntArray(0), 0, 0))
        assertNull(QrScan.decodeQrFromPixels(IntArray(3), 2, 2)) // too few samples
        assertNull(QrScan.decodeQrFromPixels(IntArray(4), -1, 4))
    }
}
