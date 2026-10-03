package com.xiufeigo.opencodeshell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The project row's ✕ must be a *labelled* accessibility node of its own.
 *
 * `uiautomator dump` on the emulator showed every project row's close
 * affordance as a node that owns the click but has no name, with the label left
 * on a separate, non-clickable child:
 *
 * ```
 * View desc='' click=true  b=[1164,237][1308,321]   <- the ✕ Box (ACTION_CLICK)
 *   View desc='Close' click=false b=[1218,291][1254,321]  <- the Icon
 * ```
 *
 * The label sat on the descendant `Icon`. Compose does **not** hoist a
 * descendant's description onto a clickable ancestor in the accessibility tree:
 * the delegate walks `SemanticsOwner.unmergedRootSemanticsNode`
 * (`getAllUncoveredSemanticsNodesToIntObjectMap`), where
 * `isMergingSemanticsOfDescendants` is false for every node, and
 * `populateAccessibilityNodeInfoProperties` assigns `info.contentDescription`
 * from the node's *own* config — so the node carrying ACTION_CLICK stayed
 * nameless and TalkBack/automation had no button to focus and trigger.
 *
 * The fix is the shell's existing idiom (`FileTabStrip`'s ✕, `AttachmentPreviewRow`'s
 * badges, `TerminalPanel`'s title bar): the label rides the clickable modifier
 * chain of the Box, the `Icon` keeps `contentDescription = null`.
 *
 * A JVM test cannot render Compose here (no `ui-test-junit4` in the module), so
 * the semantics are pinned at source level; the re-dump on device is the field
 * check behind this.
 */
class HomeProjectRowCloseA11yTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun list() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectsList.kt")

    /** The ✕'s modifier chain and its glyph, up to the row menu that follows. */
    private fun closeButton(): String {
        val body = list()
        val start = body.indexOf(".clickable(role = Role.Button, onClick = onClose)")
        val end = body.indexOf("DropdownMenu(", start)
        assertTrue("the row's ✕ clickable is gone", start >= 0 && end > start)
        return body.substring(start, end)
    }

    private fun value(dir: String, key: String): String =
        Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(source("src/main/res/$dir/strings.xml"))
            ?.groupValues
            ?.get(1)
            ?: error("$dir missing $key")

    // ---- the label rides the clickable node --------------------------------

    @Test
    fun `the close label is attached to the clickable modifier chain`() {
        // Adjacency is the point: `.semantics` on the same chain as `.clickable`
        // lands in the same `SemanticsConfiguration`, i.e. on the one node that
        // exposes ACTION_CLICK. Anywhere else (on the Icon) reproduces the dump.
        assertTrue(
            "contentDescription must sit on the clickable Box, not the Icon",
            Regex(
                """\.clickable\(role = Role\.Button, onClick = onClose\)\s*""" +
                    """\.semantics \{ contentDescription = closeLabel \}"""
            ).containsMatchIn(closeButton()),
        )
    }

    @Test
    fun `the label is resolved once, outside the semantics block`() {
        // `semantics {}` is not a composable scope, so `stringResource` cannot be
        // called inside it — the label has to be hoisted (the tab strip does the
        // same with its own `closeLabel`).
        assertTrue(
            list().contains("val closeLabel = stringResource(R.string.oc_home_project_close)"),
        )
        // The ✕ and the menu's 「Close」 row share that one label.
        assertTrue(list().contains("R.string.oc_home_project_close"))
    }

    @Test
    fun `the glyph no longer owns the label`() {
        val button = closeButton()
        // Null, so the button and the glyph are not announced twice.
        assertTrue(button.contains("contentDescription = null"))
        // The defective shape — the description on the descendant Icon — is gone.
        assertFalse(
            list().contains("contentDescription = stringResource(R.string.oc_home_project_close)"),
        )
    }

    // ---- every row, on both mount points ----------------------------------

    @Test
    fun `both project-list mount points render the one fixed row`() {
        val body = list()
        assertTrue(body.contains("private fun ProjectRow("))
        // 1 declaration + the desktop column's call + the mobile dropdown's call:
        // there is no second row renderer that could keep the old shape.
        assertEquals(3, Regex("""\bProjectRow\(""").findAll(body).count())
        assertTrue(body.contains("internal fun HomeProjectsColumn("))
        assertTrue(body.contains("internal fun HomeProjectsDropdown("))
    }

    // ---- the reused label is localised ------------------------------------

    @Test
    fun `the close label exists in all three locales and is translated`() {
        val locales = listOf("values", "values-zh-rCN", "values-zh-rTW")
        locales.forEach { dir ->
            assertNotEquals("$dir/oc_home_project_close is empty", "", value(dir, "oc_home_project_close"))
        }
        // Android falls back to English silently, and TalkBack reads the label
        // out loud — a missing translation is audible, not just visible.
        for (dir in listOf("values-zh-rCN", "values-zh-rTW")) {
            assertNotEquals(
                "$dir/oc_home_project_close is still the English copy",
                value("values", "oc_home_project_close"),
                value(dir, "oc_home_project_close"),
            )
        }
    }
}
