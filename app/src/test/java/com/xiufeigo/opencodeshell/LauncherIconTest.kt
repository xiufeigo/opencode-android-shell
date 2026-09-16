package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

class LauncherIconTest {
    @Test
    fun noAdaptiveIconXmlForHyperOS() {
        val anydpi = File("src/main/res/mipmap-anydpi-v26")
        assertFalse(
            "mipmap-anydpi-v26 adaptive XML lets HyperOS compose a white plate + tiny glyph",
            anydpi.exists() && anydpi.walk().any { it.extension == "xml" },
        )
        val res = File("src/main/res")
        val adaptive =
            res.walk().filter { it.isFile && it.extension == "xml" }.any {
                it.readText().contains("<adaptive-icon")
            }
        assertFalse("no adaptive-icon XML may remain under res/", adaptive)
        assertFalse(File("src/main/res/drawable-nodpi/ic_launcher_background.png").exists())
        for (density in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) {
            assertFalse(File("src/main/res/mipmap-$density/ic_launcher_foreground.png").exists())
            for (file in listOf("ic_launcher.png", "ic_launcher_round.png")) {
                val png = File("src/main/res/mipmap-$density/$file")
                assertTrue("missing $png", png.isFile && png.length() > 100)
            }
        }
    }

    @Test
    fun xxxhdpiLauncherIsOfficialMarkNotWhiteSquare() {
        val image = readPng(File("src/main/res/mipmap-xxxhdpi/ic_launcher.png"))
        assertEquals(192, image.width)
        assertEquals(192, image.height)
        assertMark(image)
        assertFullBleedDarkOpaque(image)
    }

    @Test
    fun roundLauncherIsIdenticalOpaqueBitmap() {
        val square = File("src/main/res/mipmap-xxxhdpi/ic_launcher.png").readBytes()
        val round = File("src/main/res/mipmap-xxxhdpi/ic_launcher_round.png").readBytes()
        assertTrue("round icon must be the same opaque PNG as ic_launcher", square.contentEquals(round))
        val image = readPng(File("src/main/res/mipmap-xxxhdpi/ic_launcher_round.png"))
        val corner = image.argb(0, 0)
        assertEquals(0xFF, (corner ushr 24) and 0xFF)
        assertTrue("round corner must be dark, not white/transparent: 0x${Integer.toHexString(corner)}", isDark(corner))
        assertMark(image)
    }

    @Test
    fun manifestUsesLegacyMipmapNotAdaptive() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(
            manifest.contains("android:roundIcon=\"@mipmap/ic_launcher\"") ||
                manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""),
        )
        assertFalse(manifest.contains("@mipmap/ic_launcher_foreground"))
    }

    private fun assertMark(image: PngImage) {
        var dark = 0
        var white = 0
        var gray = 0
        var opaque = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val p = image.argb(x, y)
                if (((p ushr 24) and 0xFF) < 16) continue
                opaque++
                when {
                    isDark(p) -> dark++
                    isWhite(p) -> white++
                    isGray(p) -> gray++
                }
            }
        }
        val total = opaque
        assertTrue("too few dark pixels: $dark / $total (mask must be the dark field)", dark > total / 2)
        assertTrue("missing white window chrome: $white", white > 100)
        assertTrue("missing gray inner pane: $gray", gray > 50)
        assertTrue("white chrome fills the plate ($white white of $total) — window zoomed too far", white < total / 3)
        val cx = image.width / 2
        val cy = image.height * 9 / 16
        val center = image.argb(cx, cy)
        assertTrue("center should be the gray/dark hole, not white chrome: 0x${Integer.toHexString(center)}", isGray(center) || isDark(center))
        assertEquals("launcher PNG must be fully opaque", image.width * image.height, opaque)
    }

    private fun assertFullBleedDarkOpaque(image: PngImage) {
        for (x in listOf(0, 1, image.width - 1, image.width - 2)) {
            for (y in listOf(0, 1, image.height - 1, image.height - 2)) {
                val p = image.argb(x, y)
                assertEquals("corner alpha at $x,$y", 0xFF, (p ushr 24) and 0xFF)
                assertTrue("corner 0x${Integer.toHexString(p)} at $x,$y must be dark, not white/transparent", isDark(p))
            }
        }
        val midLeft = image.argb(0, image.height / 2)
        assertTrue("left edge 0x${Integer.toHexString(midLeft)} should be dark", isDark(midLeft))
    }

    private fun isDark(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return r < 40 && g < 40 && b < 40
    }

    private fun isWhite(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return r > 240 && g > 240 && b > 240
    }

    private fun isGray(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return r in 70..120 && g in 70..120 && b in 70..120
    }

    private data class PngImage(val width: Int, val height: Int, val rgba: ByteArray) {
        fun argb(x: Int, y: Int): Int {
            val i = (y * width + x) * 4
            val r = rgba[i].toInt() and 0xFF
            val g = rgba[i + 1].toInt() and 0xFF
            val b = rgba[i + 2].toInt() and 0xFF
            val a = rgba[i + 3].toInt() and 0xFF
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun readPng(file: File): PngImage {
        assertTrue("missing ${file.path}", file.isFile)
        val bytes = file.readBytes()
        require(bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)))
        var offset = 8
        var width = 0
        var height = 0
        val idat = ByteArrayOutputStream()
        while (offset + 12 <= bytes.size) {
            val len =
                ((bytes[offset].toInt() and 0xFF) shl 24) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 3].toInt() and 0xFF)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val dataStart = offset + 8
            val data = bytes.copyOfRange(dataStart, dataStart + len)
            when (type) {
                "IHDR" -> {
                    width =
                        ((data[0].toInt() and 0xFF) shl 24) or
                            ((data[1].toInt() and 0xFF) shl 16) or
                            ((data[2].toInt() and 0xFF) shl 8) or
                            (data[3].toInt() and 0xFF)
                    height =
                        ((data[4].toInt() and 0xFF) shl 24) or
                            ((data[5].toInt() and 0xFF) shl 16) or
                            ((data[6].toInt() and 0xFF) shl 8) or
                            (data[7].toInt() and 0xFF)
                    require(data[8].toInt() == 8 && data[9].toInt() == 6) { "expected 8-bit RGBA PNG" }
                }
                "IDAT" -> idat.write(data)
                "IEND" -> offset = bytes.size
            }
            offset = dataStart + len + 4
        }
        val inflater = Inflater()
        inflater.setInput(idat.toByteArray())
        val raw = ByteArray((width * 4 + 1) * height)
        val n = inflater.inflate(raw)
        inflater.end()
        require(n == raw.size) { "PNG inflate size $n != ${raw.size}" }
        val rgba = ByteArray(width * height * 4)
        var src = 0
        var dst = 0
        for (y in 0 until height) {
            src += 1 // filter byte; generator writes filter 0
            System.arraycopy(raw, src, rgba, dst, width * 4)
            src += width * 4
            dst += width * 4
        }
        return PngImage(width, height, rgba)
    }
}
