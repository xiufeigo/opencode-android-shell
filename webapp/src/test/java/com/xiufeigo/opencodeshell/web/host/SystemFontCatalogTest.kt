package com.xiufeigo.opencodeshell.web.host

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

class SystemFontCatalogTest {
    @Test
    fun eachFontRequestReadsAnIndependentReadOnlyBufferFromTheBeginning() {
        val original = ByteBuffer.wrap(byteArrayOf(0, 1, -1, 3)).apply { position(2) }
        val font = SystemFontEntry("id", "font.ttc") {
            FontBufferInputStream(original.asReadOnlyBuffer().apply { position(0) })
        }
        val first = font.open()
        val second = font.open()

        assertEquals(0, first.read())
        assertEquals(0, second.read())
        val target = ByteArray(5)
        assertEquals(3, first.read(target, 1, 3))
        assertArrayEquals(byteArrayOf(0, 1, -1, 3, 0), target)
        assertEquals(-1, first.read())
        assertEquals(0, first.read(target, 0, 0))
        assertEquals(2, original.position())
        assertThrows(IndexOutOfBoundsException::class.java) { second.read(target, 4, 2) }
    }
}
