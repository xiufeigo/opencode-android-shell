package com.xiufeigo.opencodeshell.ui.servers

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.xiufeigo.opencodeshell.ui.settings.PairingModel
import kotlin.math.max

/**
 * QR scanning without a camera library: hand live capture to the device's
 * scanner app through the de-facto ZXing scan intent, and decode a picked photo
 * in-app with `zxing:core` (`servers/connect/scanner.tsx`'s `qr-scanner` is the
 * upstream camera path — a preview still needs a camera library, so that half
 * remains the system-scanner hand-off). Both paths end at
 * [PairingModel.decodePairingScan] via [pairingFromResult].
 *
 * Launching [scanIntent] throws `ActivityNotFoundException` when no scanner app
 * is installed — callers surface `oc_pair_scan_unavailable` /
 * `oc_connect_scan_unavailable` for that case and keep the manual pairing-code
 * paste available. A cancelled scan returns `RESULT_CANCELED` with no extras;
 * a cancelled [pickImageIntent] returns a null `Uri`.
 *
 * Wire contract (ZXing Barcode Scanner intent protocol):
 * request action [ACTION_SCAN] + `SCAN_MODE=QR_CODE_MODE`, result string extra
 * [EXTRA_SCAN_RESULT].
 */
object QrScan {

    /** `com.google.zxing.client.android.SCAN`. */
    const val ACTION_SCAN: String = "com.google.zxing.client.android.SCAN"

    const val EXTRA_SCAN_MODE: String = "SCAN_MODE"

    const val SCAN_MODE_QR: String = "QR_CODE_MODE"

    /** The scanned text. Format/bytes extras exist on the wire but are unused. */
    const val EXTRA_SCAN_RESULT: String = "SCAN_RESULT"

    /** Longest decoded edge before a picked photo is downsampled. */
    const val MAX_DECODE_EDGE: Int = 1280

    fun scanIntent(): Intent = Intent(ACTION_SCAN).putExtra(EXTRA_SCAN_MODE, SCAN_MODE_QR)

    /** `ACTION_GET_CONTENT` over image types — the in-app "scan from photo" entry. */
    fun pickImageIntent(): Intent =
        Intent(Intent.ACTION_GET_CONTENT)
            .setType("image/*")
            .addCategory(Intent.CATEGORY_OPENABLE)

    fun resultFrom(data: Intent?): String? =
        data?.getStringExtra(EXTRA_SCAN_RESULT)?.takeIf { it.isNotBlank() }

    /** The picked image, or `null` when the picker was cancelled. */
    fun imageUriFrom(data: Intent?): Uri? = data?.data

    /**
     * Scanned text → pairing details, or `null` for anything that is not an
     * OpenCode pairing link/code (`server.connect.scan.invalid`).
     */
    fun pairingFromResult(payload: String?): PairingModel.PairingDetails? =
        payload?.trim()?.takeIf { it.isNotEmpty() }?.let(PairingModel::decodePairingScan)

    /**
     * ARGB samples (`Bitmap.getPixels` order) → scanned text, or `null` when
     * the picture holds no QR code. Pure: [RGBLuminanceSource] is plain Java.
     * The inverted pass mirrors upstream `scanner.setInversionMode("both")` —
     * terminal QR codes can be light-on-dark (`scanner.tsx`).
     */
    fun decodeQrFromPixels(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val source = RGBLuminanceSource(width, height, pixels)
        return decodeLuminance(source) ?: decodeLuminance(source.invert())
    }

    /**
     * Android glue over [decodeQrFromPixels]: a picked image [uri] → scanned
     * text. Downsamples first so a camera photo cannot blow the pixel buffer.
     */
    fun decodeImage(context: Context, uri: Uri): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > MAX_DECODE_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return decodeQrFromPixels(pixels, bitmap.width, bitmap.height)
    }

    private val DECODE_HINTS = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    private fun decodeLuminance(source: LuminanceSource): String? = try {
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), DECODE_HINTS).text
    } catch (_: Exception) {
        null
    }
}
