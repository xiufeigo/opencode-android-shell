package com.xiufeigo.opencodeshell.web.testutil

import java.io.File

/** Reads repository sources from the webapp test working directory, with the historical parent fallback. */
object TestSource {
    fun read(relative: String): String {
        var dir: File? = File(".").absoluteFile
        repeat(4) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            dir = dir?.parentFile
        }
        throw AssertionError("找不到源文件: $relative（从 ${File(".").absolutePath} 向上找了 4 级）")
    }
}
