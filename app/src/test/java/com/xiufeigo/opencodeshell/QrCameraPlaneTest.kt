package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.yPlaneToArgb
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * A-12: the `YUV_420_888` luma-plane walk behind the in-app camera scanner
 * (`ui/settings/PairingPanel.kt` `yPlaneToArgb`). The stride handling is the
 * part that silently shears a frame, so it is pinned here: a padded row
 * (`rowStride > width`) and a subsampled plane (`pixelStride > 1`) both have to
 * come out as one ARGB int per pixel, in row-major order, with the luma in all
 * three channels — which is what zxing's `RGBLuminanceSource` averages back.
 */
class QrCameraPlaneTest {

    private fun plane(vararg rows: IntArray, rowStride: Int? = null): ByteBuffer {
        val stride = rowStride ?: rows[0].size
        val buffer = ByteBuffer.allocate(stride * rows.size)
        rows.forEach { row ->
            row.forEach { buffer.put(it.toByte()) }
            repeat(stride - row.size) { buffer.put(0) }
        }
        buffer.rewind()
        return buffer
    }

    private fun gray(luma: Int) = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma

    @Test
    fun aTightlyPackedPlaneCopiesRowMajor() {
        val pixels = yPlaneToArgb(
            buffer = plane(intArrayOf(10, 20), intArrayOf(30, 40)),
            width = 2,
            height = 2,
            rowStride = 2,
            pixelStride = 1,
        )
        assertArrayEquals(intArrayOf(gray(10), gray(20), gray(30), gray(40)), pixels)
    }

    @Test
    fun paddedRowsDoNotShearTheFrame() {
        // rowStride 4 with a width of 2: the two padding bytes must be skipped,
        // not read as the next row's pixels.
        val pixels = yPlaneToArgb(
            buffer = plane(intArrayOf(10, 20), intArrayOf(30, 40), rowStride = 4),
            width = 2,
            height = 2,
            rowStride = 4,
            pixelStride = 1,
        )
        assertArrayEquals(intArrayOf(gray(10), gray(20), gray(30), gray(40)), pixels)
    }

    @Test
    fun aSubsampledPlaneStepsByPixelStride() {
        // Interleaved chroma: only every second byte is luma.
        val buffer = ByteBuffer.allocate(8)
        listOf(10, 99, 20, 99, 30, 99, 40, 99).forEach { buffer.put(it.toByte()) }
        buffer.rewind()
        val pixels = yPlaneToArgb(buffer, width = 2, height = 2, rowStride = 4, pixelStride = 2)
        assertArrayEquals(intArrayOf(gray(10), gray(20), gray(30), gray(40)), pixels)
    }

    @Test
    fun aTruncatedBufferKeepsOnlyTheRowsItHas() {
        // One row of the two the geometry promises: the walk stops at the limit
        // instead of reading past it (a frame dropped mid-copy).
        val pixels = yPlaneToArgb(
            buffer = plane(intArrayOf(7, 8)),
            width = 2,
            height = 2,
            rowStride = 2,
            pixelStride = 1,
        )
        assertArrayEquals(intArrayOf(gray(7), gray(8), 0, 0), pixels)
    }

    @Test
    fun aDegenerateGeometryReturnsAnEmptyBuffer() {
        val empty = yPlaneToArgb(
            buffer = plane(intArrayOf(1)),
            width = 0,
            height = 1,
            rowStride = 1,
            pixelStride = 1,
        )
        assertArrayEquals(IntArray(0), empty)
    }
}
