package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.ServerShellInfo
import com.xiufeigo.opencodeshell.ui.settings.ShellPageRow
import com.xiufeigo.opencodeshell.ui.settings.shellPageRows
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v63 tB: the Shell settings page listed `bash` twice and tagged **both** rows
 * 「当前」 — once under the 「当前 Shell」 summary and once in the option list
 * (`research/v63_verify/5554/28_shell.png`).
 *
 * The summary row is only worth drawing when the current shell is not one of
 * the options the server offers; otherwise the option row already says it, with
 * the same tag.
 */
class ShellSettingsPageRowsTest {

    private fun rows(info: ServerShellInfo) =
        shellPageRows(info, summaryTitle = "当前 Shell", autoLabel = "auto")

    private fun options(info: ServerShellInfo) =
        rows(info).filterIsInstance<ShellPageRow.Option>()

    @Test
    fun `a current shell that is an option is listed once`() {
        val info = ServerShellInfo(
            current = "bash",
            options = listOf("sh", "bash", "rbash", "systemd-home-fallback-shell", "git-shell"),
        )
        val rows = rows(info)
        assertEquals(listOf<ShellPageRow.Option>(*options(info).toTypedArray()), rows.filter { it !is ShellPageRow.Summary })
        assertEquals(5, rows.size)
        assertEquals(1, rows.count { it is ShellPageRow.Option && it.current })
    }

    @Test
    fun `the summary stays when the current shell is not an option`() {
        val info = ServerShellInfo(current = "zsh", options = listOf("sh", "bash"))
        val rows = rows(info)
        assertEquals(3, rows.size)
        assertEquals(ShellPageRow.Summary("当前 Shell", "zsh"), rows.first())
        assertEquals(0, options(info).count { it.current })
    }

    @Test
    fun `no current shell reads as the auto label and tags nothing`() {
        val rows = rows(ServerShellInfo(current = null, options = listOf("sh", "bash")))
        assertEquals(3, rows.size)
        assertEquals(ShellPageRow.Summary("当前 Shell", "auto"), rows.first())
        assertEquals(0, options(ServerShellInfo(null, listOf("sh"))).count { it.current })
    }

    @Test
    fun `a blank current shell is no current shell`() {
        val rows = rows(ServerShellInfo(current = "  ", options = listOf("sh")))
        assertEquals(ShellPageRow.Summary("当前 Shell", "auto"), rows.first())
        assertEquals(1, options(ServerShellInfo("  ", listOf("sh"))).size)
    }

    @Test
    fun `no options yields only the summary`() {
        val rows = rows(ServerShellInfo(current = "bash", options = emptyList()))
        assertEquals(listOf(ShellPageRow.Summary("当前 Shell", "bash")), rows)
    }
}
