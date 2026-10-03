package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins for the settings Pairing page (upstream
 * `settings/pairing/pairing.tsx` text half, the manual-entry counterpart, and
 * the three scan entries: the in-app `camera2` + `zxing:core` preview with the
 * system-scanner and photo-picker fallbacks).
 */
class PairingPanelRenderTest {

    private fun panel(): String {
        val relative = "src/main/java/com/xiufeigo/opencodeshell/ui/settings/PairingPanel.kt"
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    @Test
    fun thePageShowsThePairingCodeAsTextWithCopy() {
        val body = panel()
        assertTrue(body.contains("PairingModel.pairingUrl("))
        assertTrue(body.contains("R.string.oc_pair_copy"))
        assertTrue(body.contains("clipboard.setText(AnnotatedString(url))"))
    }

    @Test
    fun manualEntryDecodesThroughPairingScan() {
        val body = panel()
        assertTrue(body.contains("PairingModel::decodePairingScan"))
        assertTrue(body.contains("R.string.oc_pair_manual_placeholder"))
        assertTrue(body.contains("R.string.oc_pair_invalid"))
        assertTrue(body.contains("R.string.oc_pair_manual_result"))
    }

    @Test
    fun theScanEntryOpensTheInAppPreviewWithHonestFallbacks() {
        val body = panel()
        // A-12: the entry now requests `android.permission.CAMERA` and opens the
        // in-app preview (platform `camera2` + `zxing:core`) instead of handing
        // the scan straight to a scanner app; the grant check is skipped when
        // the permission is already held.
        assertTrue(body.contains("ScanCameraRow("))
        assertTrue(body.contains("ActivityResultContracts.RequestPermission()"))
        assertTrue(body.contains("cameraPermissionLauncher.launch(Manifest.permission.CAMERA)"))
        assertTrue(body.contains("hasCameraPermission(context)"))
        assertTrue(body.contains("CameraScanOverlay("))
        assertTrue(body.contains("QrCameraController("))
        assertTrue(body.contains("QrScan.decodeQrFromPixels("))
        assertTrue(body.contains("R.string.oc_pair_scan_camera_description"))
        // Honest failures: a refused permission and a camera that will not open
        // both replace the row's "ready" copy and leave the other entries.
        assertTrue(body.contains("R.string.oc_pair_scan_camera_denied"))
        assertTrue(body.contains("R.string.oc_pair_scan_camera_failed"))
        assertTrue(body.contains("ScanEntryRow("))
        assertTrue(body.contains("QrScan.scanIntent()"))
        assertTrue(body.contains("ActivityNotFoundException"))
        assertTrue(body.contains("R.string.oc_pair_scan"))
        assertTrue(body.contains("R.string.oc_pair_scan_description"))
        assertTrue(body.contains("R.string.oc_pair_scan_unavailable"))
        assertTrue(body.contains("PhotoPickRow("))
        assertTrue(body.contains("R.string.oc_pair_scan_photo_failed"))
        // All three entries hand their payload to the one decode path.
        assertTrue(body.contains("PairingModel::decodePairingScan"))
    }

    @Test
    fun missingServerRendersTheHonestEmptyState() {
        val body = panel()
        assertTrue(body.contains("R.string.oc_pair_unavailable"))
    }

    @Test
    fun theRootTabIsWiredIntoTheSettingsNav() {
        val relative = "src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsModel.kt"
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        val body = file.readText()
        assertTrue(body.contains("PAIRING(\"pairing\")"))
        assertTrue(body.contains("tabItem(SettingsTab.PAIRING)"))
    }
}
