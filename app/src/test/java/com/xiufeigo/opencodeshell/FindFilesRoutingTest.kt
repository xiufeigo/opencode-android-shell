package com.xiufeigo.opencodeshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FindFilesRoutingTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun mainSources(): List<File> {
        val root = listOf(
            File("src/main/java/com/xiufeigo/opencodeshell"),
            File("app/src/main/java/com/xiufeigo/opencodeshell"),
        ).firstOrNull { it.isDirectory } ?: error("missing main source root")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun window(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("missing window start: $from", start >= 0)
        val end = src.indexOf(to, start + from.length)
        assertTrue("missing window end: $to", end > start)
        return src.substring(start, end)
    }

    @Test
    fun noProductionCallerReachesV1FindFiles() {
        val offenders = mainSources()
            .filter { it.name != "OpenCodeClient.kt" && it.readText().contains(".findFiles(") }
            .map { it.name }
        assertTrue("v1 findFiles callers: $offenders", offenders.isEmpty())
        assertTrue(source("src/main/java/com/xiufeigo/opencodeshell/OpenCodeClient.kt").contains("fun findFiles("))
    }

    @Test
    fun unscopedSearchFilesAnchorsOnTheTrackedSessionDirectory() {
        val vm = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        val body = window(
            vm,
            "fun searchFiles(query: String, onFailure:",
            "fun searchFiles(query: String, directory:",
        )
        assertTrue(body.contains("transport.searchFiles(query, directory = directory)"))
        assertFalse(body.contains("transport.searchFiles(query)"))
    }

    @Test
    fun pickerRendersTheFullCatalogNotTheConnectedSlice() {
        val picker = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelPicker.kt")
        assertTrue(picker.contains("ModelPickerItems.groups("))
        assertTrue(picker.contains("includeUnconnected = true"))
    }
}
