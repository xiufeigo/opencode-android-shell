package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.PairingModel
import com.xiufeigo.opencodeshell.ui.servers.QrScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The system-scanner hand-off (`ui/servers/QrScan.kt`): the ZXing scan-intent
 * wire contract and the pure decode seam. `scanIntent()`/`resultFrom()` are
 * thin `Intent` glue and are pinned as source in [ConnectScanRenderTest].
 */
class QrScanTest {

    @Test
    fun theScanContractIsTheZxingIntentProtocol() {
        assertEquals("com.google.zxing.client.android.SCAN", QrScan.ACTION_SCAN)
        assertEquals("SCAN_MODE", QrScan.EXTRA_SCAN_MODE)
        assertEquals("QR_CODE_MODE", QrScan.SCAN_MODE_QR)
        assertEquals("SCAN_RESULT", QrScan.EXTRA_SCAN_RESULT)
    }

    @Test
    fun pairingFromResultDecodesAnOpencodePairingLink() {
        val url = PairingModel.pairingUrl("secret", "http://10.0.2.2:4096")!!
        val decoded = QrScan.pairingFromResult(url)
        assertEquals("secret", decoded?.password)
        assertEquals(listOf("http://10.0.2.2:4096"), decoded?.urls)
    }

    @Test
    fun pairingFromResultRejectsForeignOrEmptyPayloads() {
        // A cancelled scan delivers no extra at all (`resultFrom` → null).
        assertNull(QrScan.pairingFromResult(null))
        assertNull(QrScan.pairingFromResult(""))
        assertNull(QrScan.pairingFromResult("   "))
        assertNull(QrScan.pairingFromResult("https://example.com"))
        assertNull(QrScan.pairingFromResult("not a pairing code"))
    }
}
