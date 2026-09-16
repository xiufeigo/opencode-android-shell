package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

class LauncherIconTest {
    @Test
    fun adaptiveXmlDoesNotSelfReferenceLauncher() {
        for (name in listOf("ic_launcher.xml", "ic_launcher_round.xml")) {
            val xml = File("src/main/res/mipmap-anydpi-v26/$name").readText()
            assertTrue("$name must use the foreground PNG", xml.contains("@mipmap/ic_launcher_foreground"))
            assertTrue("$name must use the dark background drawable", xml.contains("@drawable/ic_launcher_background"))
            assertTrue(
                "$name must not point foreground at @mipmap/ic_launcher (API 26+ that is this XML → Android robot)",
                !xml.contains("android:drawable=\"@mipmap/ic_launcher\""),
            )
        }
        for (density in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) {
            for (file in listOf("ic_launcher.png", "ic_launcher_round.png", "ic_launcher_foreground.png")) {
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
        assertFullBleedDark(image)
    }

    @Test
    fun xxxhdpiForegroundIsOfficialMarkNotWhiteSquare() {
        val image = readPng(File("src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png"))
        assertEquals(432, image.width)
        assertEquals(432, image.height)
        assertMark(image)
        assertFullBleedDark(image)
    }

    @Test
    fun roundLauncherHasTransparencyOutsideCircle() {
        val image = readPng(File("src/main/res/mipmap-xxxhdpi/ic_launcher_round.png"))
        val corner = image.argb(0, 0)
        assertEquals(0, (corner ushr 24) and 0xFF)
        assertMark(image)
    }

    private fun assertMark(image: PngImage) {
        var dark = 0
        var white = 0
        var gray = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val p = image.argb(x, y)
                if (((p ushr 24) and 0xFF) < 16) continue
                when {
                    isDark(p) -> dark++
                    isWhite(p) -> white++
                    isGray(p) -> gray++
                }
            }
        }
        val total = image.width * image.height
        assertTrue("too few dark pixels: $dark / $total", dark > total / 4)
        assertTrue("missing white window chrome: $white", white > 100)
        assertTrue("missing gray inner pane: $gray", gray > 50)
        assertTrue("icon is a white square ($white white of $total)", white < total / 2)
    }

    private fun assertFullBleedDark(image: PngImage) {
        val inset = image.width / 20
        for (x in listOf(inset, image.width - 1 - inset)) {
            for (y in listOf(inset, image.height - 1 - inset)) {
                val p = image.argb(x, y)
                assertTrue("corner/edge 0x${Integer.toHexString(p)} at $x,$y should be dark full-bleed", isDark(p))
            }
        }
        val top = image.argb(image.width / 2, inset)
        assertTrue("top edge should be dark, got 0x${Integer.toHexString(top)}", isDark(top))
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
