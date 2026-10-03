package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.filterScoped
import com.xiufeigo.opencodeshell.ui.home.scopedSearch
import com.xiufeigo.opencodeshell.v2.DirEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The directory picker's search normalisation — the Kotlin collapse of
 * upstream `createDirectorySearch`'s `scoped()` + segment walk
 * (`_upstream/packages/app/src/workspaces/selection/domain.ts:350-361,415-428`):
 * a typed path must become a {directory, name} pair (so `/home/xiufe` searches
 * *in* `/home` for `xiufe`) instead of reaching `searchDirectories` as one
 * fuzzy query string, and results must stay scoped to that directory.
 */
class DirectoryPickerScopedSearchTest {

    private fun dir(path: String, name: String = path.substringAfterLast('/').ifEmpty { path }) =
        DirEntry(name = name, absolute = path, isDirectory = true)

    @Test
    fun absoluteInputScopesToTheParentAndQueriesTheLastSegment() {
        val scope = scopedSearch("/home/xiufe", home = "/home/u", base = "/w")
        assertEquals("/home", scope.directory)
        assertEquals("xiufe", scope.name)
        assertTrue(scope.pathLike)
    }

    @Test
    fun tildeExpandsToHomeBeforeScoping() {
        val scope = scopedSearch("~/proj/sub", home = "/home/u", base = "/w")
        assertEquals("/home/u/proj", scope.directory)
        assertEquals("sub", scope.name)
        assertTrue(scope.pathLike)
    }

    @Test
    fun bareTildeScopesHomeWithNoNameQuery() {
        val scope = scopedSearch("~", home = "/home/u", base = "/w")
        assertEquals("/home/u", scope.directory)
        assertEquals("", scope.name)
    }

    @Test
    fun tildeFallsBackToTheBaseWhenHomeIsUnknown() {
        // Upstream `home || base`.
        val scope = scopedSearch("~/x", home = null, base = "/w")
        assertEquals("/w", scope.directory)
        assertEquals("x", scope.name)
    }

    @Test
    fun trailingSlashScopesToTheTypedDirectoryItself() {
        val scope = scopedSearch("/home/xiufe/", home = "/h", base = "/w")
        assertEquals("/home/xiufe", scope.directory)
        assertEquals("", scope.name)
        assertTrue(scope.pathLike)
    }

    @Test
    fun relativeInputResolvesAgainstTheBrowsedDirectory() {
        val scope = scopedSearch("proj/x", home = "/h", base = "/w/src")
        assertEquals("/w/src/proj", scope.directory)
        assertEquals("x", scope.name)
        assertTrue(scope.pathLike)
    }

    @Test
    fun bareWordStaysANameQueryUnderTheBrowsedDirectory() {
        val scope = scopedSearch("foo", home = "/h", base = "/w")
        assertEquals("/w", scope.directory)
        assertEquals("foo", scope.name)
        assertFalse(scope.pathLike)
    }

    @Test
    fun driveRootInputKeepsTheRootScope() {
        val scope = scopedSearch("""C:\Users\xiufe\proj""", home = null, base = "/w")
        assertEquals("C:/Users/xiufe", scope.directory)
        assertEquals("proj", scope.name)
    }

    @Test
    fun pathInputResultsKeepOnlyTheScopedDirectorysDirectChildren() {
        val scope = scopedSearch("/home/xiufe", home = "/h", base = "/w")
        val kept = filterScoped(
            listOf(
                dir("/home/xiufe"),
                dir("/home/other"),
                dir("/home/deep/xiufe"),
                dir("/elsewhere/xiufe"),
                DirEntry(name = "f", absolute = "/home/xiufe.txt", isDirectory = false),
                DirEntry(name = "i", absolute = "/home/.cache", isDirectory = true, ignored = true),
            ),
            scope,
            root = "/",
        )
        assertEquals(listOf("/home/xiufe", "/home/other"), kept.map { it.absolute })
    }

    @Test
    fun bareWordResultsStayWithinTheScopedDirectory() {
        val scope = scopedSearch("foo", home = "/h", base = "/w/proj")
        val kept = filterScoped(
            listOf(
                dir("/w/proj/deep/foo"),
                dir("/w/proj/foo"),
                dir("/elsewhere/foo"),
                DirEntry(name = "f", absolute = "/w/proj/xfoo", isDirectory = false),
            ),
            scope,
            root = "/",
        )
        assertEquals(listOf("/w/proj/deep/foo", "/w/proj/foo"), kept.map { it.absolute })
    }

    @Test
    fun relativeV1ResultsAreAnchoredOnTheBrowseRootBeforeTheScopeCheck() {
        // v1 `/find/file` answers cwd-relative directory paths (`"AMD\\"`);
        // unanchored they have no parent to scope against and would all drop.
        val scope = scopedSearch("""C:\Windows\AMD""", home = null, base = """C:\Windows""")
        val kept = filterScoped(
            listOf(
                DirEntry(name = "AMD", absolute = """AMD\""", isDirectory = true),
                DirEntry(name = "OTHER", absolute = """OTHER\""", isDirectory = true),
                DirEntry(name = "NESTED", absolute = """DEEP\NESTED\""", isDirectory = true),
            ),
            scope,
            root = """C:\Windows""",
        )
        // Name matching is the transport's fuzzy pass; the scope keeps direct
        // children of C:/Windows and drops the nested one. Entries are
        // returned untouched — without root-anchoring, `AMD\` has no parent
        // and both direct children would drop.
        assertEquals(listOf("AMD", "OTHER"), kept.map { it.name })
    }
}
