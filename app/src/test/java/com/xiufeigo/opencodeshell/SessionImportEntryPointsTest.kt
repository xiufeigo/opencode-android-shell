package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.header.SessionMenuAction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v39/v40: the **findable** entries for 「导入会话」 — the project row's ⋯, the
 * session header's ⋮ and (v40) the Home session row's long-press menu.
 *
 * The import itself was never the problem (the API roundtrip is covered by
 * [SessionImportRoundtripTest] / [SessionImporterTest]); *reaching* it was. Two
 * consecutive device passes reported the entry as missing (v36 tB, v38 tB)
 * because the only one on the phone was the Context tab's four-row menu —
 * ⋯(view tabs) → 「用量」 → scroll to the bottom → 「原始消息」 → ⋯ → 「导入会话…」
 * — and neither tester walked that far.
 *
 * So the shell now has the three entries upstream's UI implies:
 *
 * 1. the Home project row's ⋯ trigger. Upstream renders it
 *    (`Menu.Trigger as={IconButton} data-action="home-project-menu"`,
 *    `home/projects/view.tsx:724-731`) beside the row's right-click
 *    (`:660-663`); this shell had shipped only the long-press half, so nothing
 *    on the row said a menu existed;
 * 2. the session header's ⋮ menu, which carries 「导入会话…」 directly under
 *    「导出会话」. That one is a deliberate deviation — upstream has no import
 *    entry in the session screen at all — and [SessionOverflowMenu]'s KDoc says
 *    so; the Context tab's four-row menu stays the upstream-faithful home of
 *    the flow (`SessionContextExportMenuTest` keeps it);
 * 3. the Home **session** row's menu (v40), the flow's third call site: the
 *    long-press / `ContextMenu` menu upstream fills with
 *    Rename / Export… / Delete… (`home/sessions/view.tsx:694-701`) gains
 *    「导入会话」 under 「导出会话」. A deviation too — upstream has no such row —
 *    but the long-press menu is where a phone user reaches that session's
 *    actions, and the row's own directory is the one import location this row
 *    can name (`location: {directory: …}`).
 *
 * Compose structure cannot be rendered by this module's JVM tests (no
 * `ui-test-junit4`), so the wiring is pinned against source — the house style
 * ([HomeProjectMenuTest], [SessionOverflowMenuTest], [HomeProjectRowCloseA11yTest]).
 */
class SessionImportEntryPointsTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    private fun list() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectsList.kt")

    private fun header() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt")

    private fun screen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private fun sessionsList() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")

    /** The session row menu's body, up to the rename editor that follows it. */
    private fun rowMenu(): String {
        val body = sessionsList()
        val start = body.indexOf("DropdownMenu(")
        val end = body.indexOf("private fun HomeSessionRenameEditor", start)
        assertTrue("the session row menu is gone", start >= 0 && end > start)
        return body.substring(start, end)
    }

    /** The ⋮ menu's body, up to the `MenuRow` helper that follows it. */
    private fun overflowMenu(): String {
        val body = header()
        val start = body.indexOf("private fun SessionOverflowMenu")
        val end = body.indexOf("private fun MenuDivider", start)
        assertTrue("the overflow menu is gone", start >= 0 && end > start)
        return body.substring(start, end)
    }

    // ---------------------------------------------------- entry 1: the Home row

    /**
     * The row's ⋯ is upstream's own trigger, and it is what opens the menu the
     * 「导入会话」 row lives in. It has to be *visible* — that is the whole bug:
     * a `combinedClickable` long-press alone leaves the menu undiscoverable.
     */
    @Test
    fun theHomeRowCarriesUpstreamsVisibleMenuTrigger() {
        val body = list()
        // upstream's glyph (`outline-dots`) …
        assertTrue(body.contains("R.drawable.ic_oc_dots"))
        // … labelled with upstream's `aria-label` (`common.moreOptions`), hoisted
        // out of the `semantics {}` block the way `closeLabel` is …
        assertTrue(body.contains("val moreOptionsLabel = stringResource(R.string.oc_more_options)"))
        // … and riding the *clickable* node, or TalkBack sees an unnamed button
        // (the `HomeProjectRowCloseA11yTest` shape).
        assertTrue(
            "the ⋯ must label its own clickable node",
            Regex(
                """\.clickable\(role = Role\.Button, onClick = onOpenMenu\)\s*""" +
                    """\.semantics \{ contentDescription = moreOptionsLabel \}"""
            ).containsMatchIn(body),
        )
        // The long-press (the touch stand-in for upstream's right-click) stays.
        assertTrue(body.contains("onLongClick = onOpenMenu"))
    }

    /**
     * Both mount points of the row render the same [ProjectRow], so the trigger
     * cannot exist on only one of them.
     */
    @Test
    fun bothProjectListMountPointsGetTheTrigger() {
        val body = list()
        assertTrue(body.contains("internal fun HomeProjectsColumn("))
        assertTrue(body.contains("internal fun HomeProjectsDropdown("))
        // 1 declaration + 2 call sites — the same count the a11y test pins.
        assertEquals(3, Regex("""\bProjectRow\(""").findAll(body).count())
        // …and the ⋯ is inside the row, not in one of the two call sites.
        val row = body.substringAfter("private fun ProjectRow(")
        assertTrue(row.contains("onClick = onOpenMenu"))
    }

    /** The menu the ⋯ opens still lists the import row on both mount points. */
    @Test
    fun theHomeMenuStillCarriesTheImportRow() {
        val body = list()
        assertTrue(body.contains("R.string.command_session_import"))
        assertTrue(body.contains("if (onImport != null)"))
        // The row's own host is per project (the import location is baked in).
        assertTrue(
            source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeRowActions.kt")
                .contains("rememberSessionImporter(target?.path)"),
        )
    }

    // ----------------------------------- entry 3: the Home session row menu

    /**
     * 「导入会话」 sits directly under 「导出会话」 — the file an export wrote is
     * what an import reads — and before the divider that leads to Delete, so the
     * destructive row stays last.
     */
    @Test
    fun theSessionRowMenuPutsTheImportRowBesideTheExportRow() {
        val menu = rowMenu()
        val export = menu.indexOf("R.string.oc_context_export_session")
        val importRow = menu.indexOf("if (importEnabled)")
        val delete = menu.indexOf("R.string.oc_delete_session", export)
        assertTrue("the export row is gone", export >= 0)
        assertTrue("the import row must follow the export row", importRow > export)
        assertTrue("the import row must precede Delete", importRow in export until delete)

        // Up to the divider that leads to Delete: the window this reads is the
        // row's own body, not the whole menu.
        val row = menu.substring(importRow, menu.indexOf("HorizontalDivider", importRow))
        // Upstream's own `command.session.import` — the key the project row's menu
        // and the header ⋮ use, so the three entries read alike.
        assertTrue(row.contains("R.string.command_session_import"))
        assertFalse("the Context tab's own key stays there", row.contains("R.string.oc_session_import"))
        assertTrue(row.contains("onClick = onImport"))
        // Gated on the host, like the other two: absent rather than dead.
        assertTrue(row.contains("if (importEnabled)"))
    }

    /**
     * The row closes its menu before dispatching, so the SAF picker cannot open
     * under a still-expanded `DropdownMenu` (the header ⋮ row's rule), and the
     * action is threaded down from the column's nullable host.
     */
    @Test
    fun theSessionRowClosesItsMenuBeforeThePickerOpens() {
        val body = sessionsList()
        assertTrue(body.contains("onImportSession: ((HomeSessions.Row) -> Unit)? = null"))
        assertTrue(body.contains("importEnabled = onImportSession != null"))
        assertTrue(
            "the menu must close first",
            Regex(
                """rowMenu = HomeRowMenu\.closeMenu\(rowMenu, row\.id\)\s*""" +
                    """\n\s*onImportSession\?\.invoke\(row\)"""
            ).containsMatchIn(body),
        )
    }

    /**
     * The host chain: `HomeScreen` remembers the row importer and passes it to
     * both column layouts, and the importer's location is the **row's own
     * directory** — upstream's `location: {directory: …}` for this flow.
     */
    @Test
    fun theHomeScreenHostsTheSessionRowsImport() {
        val home = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
        assertTrue(home.contains("val importSessionRow = rememberHomeSessionImport(onSessionImported)"))
        assertEquals(
            "both column layouts must pass it",
            2,
            Regex("""onImportSession = importSessionRow,""").findAll(home).count(),
        )

        val host = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeRowActions.kt")
        assertTrue(host.contains("fun rememberHomeSessionImport("))
        assertTrue(host.contains("rememberSessionImporter(target?.directory)"))
        assertTrue(host.contains("R.string.oc_session_import_success"))
        assertTrue(host.contains("importFailureCopy(context, finish)"))
        // The mapping is the one private copy this file already kept for the
        // project row — the new host reuses it instead of adding a second.
        assertEquals(1, Regex("""fun importFailureCopy\(""").findAll(host).count())
    }

    // ------------------------------------------------ entry 2: the header ⋮ row

    /**
     * 「导入会话…」 sits directly under 「导出会话」, gated on the host, and
     * collapses the menu before dispatching (`SessionOverflowMenuTest`'s rule:
     * the `Popup` only closes on an outside tap, so a row that forgets
     * `expanded = false` leaves the menu on top of the SAF sheet).
     */
    @Test
    fun theHeaderMenuPutsTheImportRowBesideTheExportRow() {
        val menu = overflowMenu()
        val export = menu.indexOf("R.string.command_session_export")
        val importRow = menu.indexOf("if (onImportSession != null)")
        val delete = menu.indexOf("R.string.oc_delete_session", export)
        assertTrue("the export row is gone", export >= 0)
        assertTrue("the import row must follow the export row", importRow > export)
        assertTrue("the import row must precede Delete", importRow in export until delete)

        val row = menu.substring(importRow, menu.indexOf("SessionMenuAction.DELETE", importRow))
        assertTrue(row.contains("R.string.command_session_import"))
        assertTrue("the row must close the menu first", row.contains("expanded = false"))
        assertTrue(row.contains("onImportSession()"))
        // Not a fourth `SessionMenuAction`: the call site's exhaustive `when`
        // stays as it is (`SessionOverflowMenuTest.menuActionsStayAtThreeWithASingleExport`).
        assertFalse(row.contains("SessionMenuAction"))
    }

    /** The new row must not have grown the enum the call site switches on. */
    @Test
    fun theImportRowDidNotJoinTheMenuActionEnum() {
        assertEquals(
            setOf(SessionMenuAction.RENAME, SessionMenuAction.DELETE, SessionMenuAction.EXPORT),
            SessionMenuAction.values().toSet(),
        )
    }

    /**
     * The header row reuses upstream's `command.session.import` — the same id
     * the Home menu labels its import row with — and **not** the Context tab's
     * `oc_session_import`, which `SessionContextExportMenuTest` keeps out of the
     * header on purpose. The export variants stay in the Context tab too.
     */
    @Test
    fun theHeaderRowUsesThePaletteLabelAndNoExportVariants() {
        val body = header()
        assertTrue(body.contains("R.string.command_session_import"))
        assertFalse("the Context tab's four-row menu keeps its own key", body.contains("R.string.oc_session_import"))
        // Scoped to the menu itself, not the file: the KDoc above it names the
        // three variants while explaining where they stayed.
        val menu = overflowMenu()
        for (variant in listOf("ASSEMBLED", "OFFICIAL", "SANITIZED")) {
            assertFalse("the header ⋮ must not carry the $variant variant", menu.contains(variant))
        }
        // one export entry, one import entry — the header is not a second
        // four-row menu (`SessionContextExportMenuTest`).
        assertEquals(1, Regex("R.string.command_session_export").findAll(body).count())
    }

    // ------------------------------------------------------- the screen's wiring

    /**
     * The header row's chain end to end at source level: the row's callback →
     * `SessionScreen`'s remembered importer → the toast → the navigation the
     * host runs. The picker/read/route stages themselves are covered by
     * [SessionImportRoundtripTest].
     */
    @Test
    fun theScreenWiresTheRowToTheImporterAndTheHost() {
        val body = screen()
        // The import target is this session's directory — upstream's
        // `location: {directory: project.worktree}` for the project row.
        assertTrue(body.contains("rememberSessionImporter(activeSession.directory)"))
        assertTrue(body.contains("onImportSession = importSession,"))
        // Every outcome is reported; a success also hands the id to the host.
        assertTrue(body.contains("R.string.oc_session_import_success"))
        assertTrue(body.contains("importFailureCopy(context, finish)"))
        assertTrue(body.contains("onSessionImported(it.sessionId)"))
        // The scope overload (bounded picker/read/route), not the boolean one.
        assertTrue(body.contains("sessionImporter.importSession(scope) { finish ->"))
        // The host opens what the import created (upstream's
        // `home.project.openProjectSession`).
        val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        assertTrue(app.contains("onSessionImported = { imported ->"))
        assertTrue(app.contains("NavRestore.session(serverKey, imported)"))
    }

    /**
     * One mapping, two homes — not three. The new call site reuses the Context
     * tab's `importFailureCopy` (made `internal` for exactly this), so a new
     * `ImportFailure` member cannot land in two of three copies.
     */
    @Test
    fun theNewCallSiteReusesTheContextTabsFailureMapping() {
        val panel =
            source("src/main/java/com/xiufeigo/opencodeshell/ui/session/context/SessionContextPanel.kt")
        assertTrue(panel.contains("internal fun importFailureCopy("))
        assertTrue(
            screen().contains(
                "import com.xiufeigo.opencodeshell.ui.session.context.importFailureCopy",
            ),
        )
        assertFalse("a third hand-kept copy is the drift this avoids", screen().contains("fun importFailureCopy("))
    }

    // --------------------------------------------------------------- the label

    /**
     * The ⋯ is now the only *visible* name of the row menu, so its label has to
     * be translated, not just present.
     */
    @Test
    fun theMoreOptionsLabelIsLocalisedInAllThreeLocales() {
        fun value(dir: String): String =
            Regex("""<string name="oc_more_options">(.*?)</string>""")
                .find(source("src/main/res/$dir/strings.xml"))
                ?.groupValues
                ?.get(1)
                ?: error("$dir is missing oc_more_options")

        val locales = listOf("values", "values-zh-rCN", "values-zh-rTW")
        locales.forEach { assertNotEquals("$it/oc_more_options is empty", "", value(it)) }
        for (dir in listOf("values-zh-rCN", "values-zh-rTW")) {
            assertNotEquals("$dir is still the English copy", value("values"), value(dir))
        }
    }

    /**
     * One flow, one name: the Home row and the header row label it with the same
     * key (upstream's `command.session.import`), so a user who learned it in one
     * place recognises it in the other.
     */
    @Test
    fun bothEntriesShareTheOneLabelKey() {
        assertTrue(list().contains("R.string.command_session_import"))
        assertTrue(header().contains("R.string.command_session_import"))
        for (dir in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val body = source("src/main/res/$dir/strings_oc_i18n.xml")
            assertTrue(
                "$dir is missing command_session_import",
                Regex("""<string name="command_session_import">[^<]+</string>""").containsMatchIn(body),
            )
        }
    }
}
