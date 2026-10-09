package com.xiufeigo.opencodeshell.web

import com.xiufeigo.opencodeshell.web.host.ContentBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class ContentBytesTest {
    @Test fun allowsExactLimitWithoutTruncation() {
        val bytes = ByteArray(20_000) { (it % 256).toByte() }
        assertArrayEquals(bytes, ContentBytes.read(ByteArrayInputStream(bytes), bytes.size))
    }

    @Test fun rejectsProviderContentBeyondLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            ContentBytes.read(ByteArrayInputStream(ByteArray(20_001)), 20_000)
        }
    }

    @Test fun readsEmptyContent() {
        assertArrayEquals(byteArrayOf(), ContentBytes.read(ByteArrayInputStream(byteArrayOf()), 1))
    }
}
