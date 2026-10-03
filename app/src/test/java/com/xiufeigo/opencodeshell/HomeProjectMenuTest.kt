package com.xiufeigo.opencodeshell

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-3: Home's project row menu gains the two upstream rows it was missing
 * (`home/projects/view.tsx:731-760`).
 *
 * Upstream's `MenuV2` order is `command.session.new` / 「导入会话」
 * (`Show when={canImportSession}`) / 「Edit project…」 / 「Refresh worktrees」 /
 * 「清除通知」 (`disabled={unseen === 0}`) / ─── / 「Close」; this shell had only
 * the middle two plus Close.
 *
 * Source-pinned (Compose structure), plus the two things a JVM test can check
 * about the import host: it is remembered **per project** — because
 * `rememberSessionImporter` bakes the import location (`Location.PublicRef`) in
 * at construction, so one shared instance could only ever import into one
 * project — and it reports every outcome instead of failing silently.
 */
class HomeProjectMenuTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun list() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectsList.kt")

    @Test
    fun bothProjectMenusCarryTheImportRowAndTheClearRow() {
        val body = list()
        // The import row reuses the palette's own label (upstream uses
        // `command.session.import` in both places).
        assertTrue(body.contains("R.string.command_session_import"))
        assertTrue(body.contains("R.string.oc_home_project_clear_notifications"))
        // Upstream's own gate for each row.
        assertTrue(body.contains("if (onImport != null)"))
        assertTrue(body.contains("enabled = clearEnabled"))
        // Both mounting points (the desktop column and the mobile dropdown).
        assertTrue(body.contains("internal fun HomeProjectsColumn("))
        assertTrue(body.contains("internal fun HomeProjectsDropdown("))
    }

    @Test
    fun theImportHostIsSelfServeAndPerProject() {
        val host = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeRowActions.kt")
        assertTrue(host.contains("fun rememberHomeProjectImport("))
        // The importer is keyed on the tapped project's path: that key *is* the
        // import location.
        assertTrue(host.contains("rememberSessionImporter(target?.path)"))
        assertTrue(host.contains("R.string.oc_session_import_success"))
        assertTrue(host.contains("R.string.oc_session_import_timeout"))
        assertTrue(host.contains("R.string.oc_session_import_failed"))
    }

    @Test
    fun theClearRowIsDisabledUntilAHandlerAndACountArrive() {
        val body = list()
        assertTrue(body.contains("clearEnabled = projectUnseen(project) > 0 &&"))
        assertTrue(body.contains("onClearProjectNotifications != null"))
        // Defaulted to 0, so a host that passes nothing renders the row
        // disabled (upstream's `disabled={unseen === 0}`), never live-but-dead.
        assertTrue(body.contains("projectUnseen: (HomeProjects.Project) -> Int = { 0 }"))
    }

    @Test
    fun theClearNotificationsStringExistsInAllLocales() {
        val locales = listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-zh-rCN/strings.xml",
            "src/main/res/values-zh-rTW/strings.xml",
        )
        locales.forEach { relative ->
            assertTrue(
                relative,
                source(relative).contains("name=\"oc_home_project_clear_notifications\""),
            )
        }
    }
}
