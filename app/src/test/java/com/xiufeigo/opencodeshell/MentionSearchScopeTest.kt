package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.MentionSearchScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionSearchScopeTest {

    @Test
    fun scopedPassesTheRootAsTheSearchDirectory() {
        var seenDirectory: String? = "unset"
        val search = MentionSearchScope.scoped("/ws/proj") { query, directory, onResult ->
            seenDirectory = directory
            assertEquals("E2ECHK_", query)
            onResult(emptyList())
        }
        search("E2ECHK_") {}
        assertEquals("/ws/proj", seenDirectory)
    }

    @Test
    fun scopedDropsPathsOutsideTheProject() {
        val search = MentionSearchScope.scoped("/ws/proj") { _, _, onResult ->
            onResult(
                listOf(
                    "/ws/proj/research/E2ECHK_\u4e2d\u6587\u5907\u6ce8.md",
                    "/usr/share/man/man1/ls.1.gz",
                    "/usr/share/vim/vimrc",
                    "/mnt/wslg/versions.txt",
                ),
            )
        }
        var shown: List<String> = listOf("unset")
        search("E2ECHK_") { shown = it }
        assertEquals(listOf("/ws/proj/research/E2ECHK_\u4e2d\u6587\u5907\u6ce8.md"), shown)
    }

    @Test
    fun withinRootRejectsSiblingPrefixesAndEscapes() {
        val paths = MentionSearchScope.withinRoot(
            "/ws/proj",
            listOf("/ws/proj/a.md", "/ws/proj2/b.md", "/ws/proj/../etc/c.md", "rel/d.md"),
        )
        assertEquals(listOf("/ws/proj/a.md", "rel/d.md"), paths)
    }

    @Test
    fun blankRootStaysUnscopedAndUnfiltered() {
        var seenDirectory: String? = "unset"
        val search = MentionSearchScope.scoped(null) { _, directory, onResult ->
            seenDirectory = directory
            onResult(listOf("/usr/share/man/x"))
        }
        var shown: List<String> = emptyList()
        search("q") { shown = it }
        assertEquals(null, seenDirectory)
        assertTrue(shown == listOf("/usr/share/man/x"))
    }

    /**
     * v63 BUG-3: the shared backend answers `/api/fs/find` from its **own**
     * `location.directory`, not from the `directory=` we ask with — measured on
     * `emulator-5558` (`/home/xiufeigo/v36projC` asked, `/mnt/c/…` answered). The
     * strict filter then dropped every row and the `@` context panel showed only
     * its agent / command / skill entries, with no file or directory row at all.
     */
    @Test
    fun withinRootFallsBackToTheUnscopedAnswerWhenNothingSurvives() {
        val answered = listOf("research/v62_verify/5560/71_c3.png", "README.md")
        val paths = MentionSearchScope.withinRoot("/home/xiufeigo/v36projC", answered)
        assertEquals(answered, paths)
    }

    @Test
    fun scopedStillShowsTheRowsWhenNothingSurvivesTheFilter() {
        val search = MentionSearchScope.scoped("/home/xiufeigo/v36projC") { _, _, onResult ->
            onResult(listOf("/mnt/c/sensorsdata/main/program/opencode-android-shell/README.md"))
        }
        var shown: List<String> = emptyList()
        search("") { shown = it }
        assertEquals(
            listOf("/mnt/c/sensorsdata/main/program/opencode-android-shell/README.md"),
            shown,
        )
    }

    /** A blank answer stays blank — the fallback never invents rows. */
    @Test
    fun withinRootOfNothingIsNothing() {
        assertEquals(emptyList<String>(), MentionSearchScope.withinRoot("/ws/proj", emptyList()))
    }
}
