package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.PickerUp
import com.xiufeigo.opencodeshell.ui.home.pickerAutoBrowseTarget
import com.xiufeigo.opencodeshell.ui.home.pickerUp
import com.xiufeigo.opencodeshell.ui.home.scopedSearch
import com.xiufeigo.opencodeshell.v2.DirEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v63: a hand-typed path in the picker's path field used to change **only the
 * field** — the body kept listing the previous directory and 「选择此目录」 stayed
 * grey until the IME's Search key was pressed (`research/v63_verify/5556/
 * 55_path3.png` → `57_enter.png`).
 *
 * [pickerAutoBrowseTarget] is the debounced browse that fixes it: the answer
 * comes from the listing the suggestion read already made, so a **complete**
 * path is browsed, a half-typed one is not, and nothing that does not exist is
 * ever probed.
 *
 * The second half pins the rule the device run tripped over — the ghost 「Parent」
 * walks the **browsed** directory, never the text in the field. That wiring is
 * inside the composable, so it is pinned the way this repo pins the rest of it
 * (source guard), against [pickerUp]'s own contract.
 */
class DirectoryPickerAutoBrowseTest {

    private fun entry(name: String, path: String, directory: Boolean = true, ignored: Boolean = false) =
        DirEntry(name = name, absolute = path, isDirectory = directory, ignored = ignored)

    private fun mainSource(): String {
        val relative = "src/main/java/com/xiufeigo/opencodeshell/ui/home/DirectoryPickerDialog.kt"
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return requireNotNull(file.takeIf { it.isFile }) { "missing $relative" }.readText()
    }

    private fun scopeOf(input: String, home: String? = "/home/xiufeigo", base: String = "/home/xiufeigo") =
        scopedSearch(input, home, base)

    @Test
    fun `a complete absolute path browses without the search key`() {
        val input = "/mnt/c/sensorsdata/main/program"
        val scope = scopeOf(input, home = null, base = "/mnt/c/sensorsdata/main")
        assertEquals("/mnt/c/sensorsdata/main", scope.directory)
        assertEquals("program", scope.name)
        val target = pickerAutoBrowseTarget(input, scope, listOf(entry("program", "/mnt/c/sensorsdata/main/program")), "/mnt/c/sensorsdata/main")
        assertEquals("/mnt/c/sensorsdata/main/program", target)
    }

    @Test
    fun `a path already on screen does not re-list`() {
        val input = "/mnt/c/sensorsdata/main/program"
        val scope = scopeOf(input, home = null, base = "/mnt/c/sensorsdata/main")
        assertNull(
            pickerAutoBrowseTarget(input, scope, listOf(entry("program", "/mnt/c/sensorsdata/main/program")), "/mnt/c/sensorsdata/main/program"),
        )
    }

    @Test
    fun `a half-typed segment only suggests`() {
        val input = "/mnt/c/sensorsdata/main/prog"
        val scope = scopeOf(input, home = null, base = "/mnt/c/sensorsdata/main")
        assertEquals("prog", scope.name)
        assertNull(pickerAutoBrowseTarget(input, scope, listOf(entry("program", "/mnt/c/sensorsdata/main/program")), "/"))
    }

    @Test
    fun `a relative name never auto-browses`() {
        val input = "v36projB"
        val scope = scopeOf(input)
        assertNull(pickerAutoBrowseTarget(input, scope, listOf(entry("v36projB", "/home/xiufeigo/v36projB")), "/home/xiufeigo"))
    }

    @Test
    fun `a relative segment with a separator is a suggestion, not a browse`() {
        val input = "sub/dir"
        val scope = scopeOf(input)
        assertEquals("/home/xiufeigo/sub", scope.directory)
        assertEquals("dir", scope.name)
        assertNull(pickerAutoBrowseTarget(input, scope, listOf(entry("dir", "/home/xiufeigo/sub/dir")), "/home/xiufeigo"))
    }

    @Test
    fun `a trailing separator asks for children and browses none`() {
        val input = "/mnt/c/sensorsdata/main/"
        val scope = scopeOf(input, home = null, base = "/mnt/c")
        assertEquals("", scope.name)
        assertNull(pickerAutoBrowseTarget(input, scope, listOf(entry("program", "/mnt/c/sensorsdata/main/program")), "/mnt/c"))
    }

    @Test
    fun `a home-relative path browses`() {
        val input = "~/v36projB"
        val scope = scopeOf(input)
        assertEquals("/home/xiufeigo", scope.directory)
        assertEquals("v36projB", scope.name)
        assertEquals(
            "/home/xiufeigo/v36projB",
            pickerAutoBrowseTarget(input, scope, listOf(entry("v36projB", "/home/xiufeigo/v36projB")), "/home/xiufeigo"),
        )
    }

    @Test
    fun `a drive letter without its colon resolves in the server spelling`() {
        val input = "c/sensorsdata/main"
        val scope = scopeOf(input, home = null, base = "/mnt/c/sensorsdata/main")
        val target = pickerAutoBrowseTarget(input, scope, listOf(entry("main", "/mnt/c/sensorsdata/main")), "/mnt/c/sensorsdata")
        assertEquals("/mnt/c/sensorsdata/main", target)
    }

    @Test
    fun `only a unique directory name matches`() {
        val input = "/mnt/c/sensorsdata"
        val scope = scopeOf(input, home = null, base = "/mnt/c")
        val entries = listOf(
            entry("sensorsdata", "/mnt/c/sensorsdata"),
            entry("sensorsdata", "/mnt/c/sensorsdata "),
        )
        assertNull(pickerAutoBrowseTarget(input, scope, entries, "/mnt/c"))
    }

    @Test
    fun `files and ignored entries are not browsable targets`() {
        val input = "/mnt/c/sensorsdata/README.md"
        val scope = scopeOf(input, home = null, base = "/mnt/c")
        assertNull(
            pickerAutoBrowseTarget(
                input,
                scope,
                listOf(
                    entry("README.md", "/mnt/c/sensorsdata/README.md", directory = false),
                    entry("README.md", "/mnt/c/sensorsdata/README.md", directory = true, ignored = true),
                ),
                "/mnt/c",
            ),
        )
    }

    @Test
    fun `the parent step reads the browsed path, not the field`() {
        assertEquals(
            PickerUp.Parent("/mnt/c/sensorsdata"),
            pickerUp("/mnt/c/sensorsdata/main", escapeAllowed = true) { true },
        )
        val source = mainSource()
        val parentButton = source.substringAfter("label = stringResource(R.string.oc_home_projects_parent)")
            .substringBefore("is PickerUp.None -> Unit")
        assertTrue("parent button must navigate the browsed parent", parentButton.contains("is PickerUp.Parent -> navigate(target.path)"))
        val parentBranch = parentButton.substringAfter("is PickerUp.Parent -> ")
            .substringBefore("is PickerUp.Drives -> ")
        assertTrue(
            "the parent step must not read the typed field",
            !parentBranch.contains("pathInput") && !parentBranch.contains("displayPath") && !parentBranch.contains("pickerArmTarget"),
        )
        val upBinding = source.substringAfter("val up = pickerUp(").substringBefore("\n")
        assertTrue("the parent target must be derived from currentPath", upBinding.contains("currentPath,"))
    }
}
