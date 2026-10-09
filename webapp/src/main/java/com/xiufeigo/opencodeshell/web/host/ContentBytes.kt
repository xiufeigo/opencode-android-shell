package com.xiufeigo.opencodeshell.web.host

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Bounds memory use when an external content provider does not report its size. */
object ContentBytes {
    private const val CHUNK_BYTES = 16 * 1024

    fun read(input: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "Content limit must be positive" }
        val result = ByteArrayOutputStream(minOf(maxBytes, CHUNK_BYTES))
        val chunk = ByteArray(CHUNK_BYTES)
        while (true) {
            val count = input.read(chunk)
            if (count == -1) return result.toByteArray()
            require(result.size().toLong() + count <= maxBytes) { "Content exceeds $maxBytes bytes" }
            result.write(chunk, 0, count)
        }
    }
}
