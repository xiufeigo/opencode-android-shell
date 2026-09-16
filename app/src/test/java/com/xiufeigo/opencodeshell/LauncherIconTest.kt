package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class LauncherIconTest {
    @Test
    fun xxxhdpiLauncherIsOfficialMarkNotWhiteSquare() {
        val png = File("src/main/res/mipmap-xxxhdpi/ic_launcher.png")
        assertTrue("missing ${png.path}", png.isFile)
        val image = ImageIO.read(png)
        assertEquals(192, image.width)
        assertEquals(192, image.height)
        assertMark(image)
    }

    @Test
    fun xxxhdpiForegroundIsOfficialMarkNotWhiteSquare() {
        val png = File("src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png")
        assertTrue("missing ${png.path}", png.isFile)
        val image = ImageIO.read(png)
        assertEquals(432, image.width)
        assertEquals(432, image.height)
        assertMark(image)
        // Adaptive safe-zone edge must stay the dark field, not white chrome.
        val edge = (image.width * 0.17).toInt()
        val pixel = image.getRGB(image.width / 2, edge)
        assertTrue("safe-zone edge should be dark, got 0x${Integer.toHexString(pixel)}", isDark(pixel))
    }

    @Test
    fun roundLauncherHasTransparencyOutsideCircle() {
        val png = File("src/main/res/mipmap-xxxhdpi/ic_launcher_round.png")
        assertTrue("missing ${png.path}", png.isFile)
        val image = ImageIO.read(png)
        val corner = image.getRGB(0, 0)
        assertEquals(0, (corner ushr 24) and 0xFF)
        assertMark(image)
    }

    private fun assertMark(image: BufferedImage) {
        var dark = 0
        var white = 0
        var gray = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val p = image.getRGB(x, y)
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
}
