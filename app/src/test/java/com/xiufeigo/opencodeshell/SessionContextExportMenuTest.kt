package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel
import com.xiufeigo.opencodeshell.ui.session.export.SessionExportKind
import com.xiufeigo.opencodeshell.ui.session.export.sanitizeParam
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The raw-messages export menu is **four rows, in this order** —
 * 「导出会话」 (hand-rolled `{info, messages}` pair) / 「官方格式导出」 /
 * 「脱敏导出」 / 「导入会话…」 — each wired to its own handler. v33 measured
 * all four on device (`research/v33_verify/tB/v33tb_4c_export_menu.png`); a
 * later v35 pass (`research/v35_verify/tB_lifecycle/71_ctx_more.png`) read the
 * **header `⋮`** menu — whose single 「Export session」 row goes straight to the
 * SAF saver by design (`research/HANDOFF.md`, 有意偏离) — and reported the four
 * rows as lost. The pins below are what makes that distinction fail loudly
 * instead of by re-reading screenshots: a dropped or re-pointed row breaks
 * [theMenuRendersFourRowsInTheV33Order] / [eachRowRunsItsOwnHandler], and a
 * lost *entry* breaks [theUsageEntryMountsThisPanel].
 *
 * Panel structure is pinned by source assertions — the house style for Compose
 * ([SessionContextPanelScrollTest], [SessionContextExportStateTest]); the menu's
 * data (row order, gates, formats) is pinned purely on [SessionContextModel].
 */
class SessionContextExportMenuTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun panel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/context/SessionContextPanel.kt")

    /**
     * The four `ExportMenuItem` call sites of the panel's one `DropdownMenu`,
     * in render order. Slicing ends at the helper's own declaration, so the
     * helper body can never be mistaken for a fifth row.
     */
    private fun menuRows(): List<String> {
        val body = panel()
        val start = body.indexOf("DropdownMenu(")
        val end = body.indexOf("private fun ExportMenuItem(")
        assertTrue("the raw-messages header must keep its DropdownMenu", start >= 0)
        assertTrue("the ExportMenuItem helper must stay declared after the menu", end > start)
        return body.substring(start, end).split("ExportMenuItem(").drop(1)
    }

    /** The label keys of the four rows, in render order. */
    private val labelKeys = listOf(
        "oc_context_export_session",
        "oc_session_export_official",
        "oc_session_export_sanitized",
        "oc_session_import",
    )

    /** The row each of those labels is gated by, in render order. */
    private val gateRows = listOf("ASSEMBLED", "OFFICIAL", "SANITIZED", "IMPORT")

    // ---------------------------------------------------------------- the rows

    @Test
    fun theMenuRendersFourRowsInTheV33Order() {
        val rows = menuRows()
        assertEquals(
            "v33's four rows: 导出会话 / 官方格式导出 / 脱敏导出 / 导入会话…",
            4,
            rows.size,
        )
        labelKeys.forEachIndexed { index, key ->
            assertTrue("row $index must be $key", rows[index].contains("R.string.$key"))
        }
        // …and no row is duplicated inside the menu. (The first key is the
        // trigger's own label too — the ghost button reads 「导出会话」 as well —
        // so the count is scoped to the menu, not to the file.)
        val menu = rows.joinToString("\n")
        labelKeys.forEach { key ->
            // The trailing guard keeps `oc_session_import` from matching its own
            // outcome copies (`oc_session_import_success` and friends).
            val uses = Regex(Regex.escape("R.string.$key") + "(?![A-Za-z0-9_])").findAll(menu).count()
            assertEquals("R.string.$key must label exactly one row", 1, uses)
        }
    }

    @Test
    fun eachRowIsGatedByItsOwnExportRow() {
        val rows = menuRows()
        rows.forEachIndexed { index, row ->
            assertTrue(
                "row $index must use ${gateRows[index]}'s gate",
                row.contains("enabled = rowEnabled(SessionContextModel.ExportRow.${gateRows[index]})"),
            )
        }
    }

    @Test
    fun eachExportRowSavesItsOwnFormat() {
        val rows = menuRows()
        listOf("ASSEMBLED", "OFFICIAL", "SANITIZED").forEachIndexed { index, kind ->
            assertTrue(
                "row $index must save $kind",
                rows[index].contains("onClick = { save(SessionExportKind.$kind) }"),
            )
        }
        // the fourth row is not a save format at all: it picks a file and posts it
        assertTrue(rows[3].contains("importer.importSession(scope) { finish ->"))
        assertTrue("Import must not go through the saver", !rows[3].contains("save(SessionExportKind."))
        assertTrue("Import must not go through the saver", !rows[3].contains("saver.save"))
    }

    @Test
    fun theTwoRouteBackedRowsCarryTheirOwnSanitizeQuery() {
        // the rows hand their kind to `save`, and the kind owns the query value
        assertNull(SessionExportKind.ASSEMBLED.sanitizeParam())
        assertNull(SessionExportKind.OFFICIAL.sanitizeParam())
        assertEquals(true, SessionExportKind.SANITIZED.sanitizeParam())
        // …which is the one place the fetch reads it from
        assertTrue(panel().contains("source.fetch(sessionID, kind.sanitizeParam())"))
    }

    @Test
    fun theMenuIsTheModelsFourRowsInOrder() {
        assertEquals(
            listOf("ASSEMBLED", "OFFICIAL", "SANITIZED", "IMPORT"),
            SessionContextModel.ExportRow.entries.map { it.name },
        )
        assertEquals(
            listOf(
                SessionExportKind.ASSEMBLED,
                SessionExportKind.OFFICIAL,
                SessionExportKind.SANITIZED,
                null,
            ),
            SessionContextModel.ExportRow.entries.map { it.kind },
        )
    }

    // -------------------------------------------------------------- the entry

    /**
     * The path the v35 pass never walked: the tab strip's `⋯` (`oc_more_options`)
     * → 「Usage」 → the context tab body → the Raw messages header's button. The
     * `Usage` row is upstream's own mobile entry (`review/view.tsx:94`), so it is
     * the one that must keep mounting this panel with the live payload.
     */
    @Test
    fun theUsageEntryMountsThisPanel() {
        val tabs =
            source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/MobileSessionTabs.kt")
        // the `⋯` menu's Usage row calls the host's open callback
        assertTrue(tabs.contains("label = stringResource(R.string.oc_mobile_usage)"))
        assertTrue(tabs.contains("onClick = openUsage,"))
        assertTrue(tabs.contains("onOpenUsage()"))
        // …the screen opens the usage view and hands it the projected snapshot
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        assertTrue(screen.contains("onOpenUsage = {"))
        assertTrue(screen.contains("MobileUsagePanel("))
        assertTrue(screen.contains("snapshot = contextSnapshot"))
        // …and the usage view *is* the context tab, raw messages included
        val usage =
            source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/MobileUsagePanel.kt")
        assertTrue(usage.contains("SessionContextPanel("))
        assertTrue(usage.contains("snapshot = snapshot"))
    }

    /**
     * The header `⋮`'s single 「导出会话」 is an intentional deviation
     * (`research/HANDOFF.md`) — this pin keeps a future pass from "restoring"
     * the four rows there instead of walking the entry above.
     */
    @Test
    fun theHeaderMenuKeepsItsSingleExportRow() {
        val header = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt")
        assertEquals(
            1,
            Regex("R.string.command_session_export").findAll(header).count(),
        )
        labelKeys.forEach { key ->
            assertTrue(
                "the header ⋮ must not carry the four-row menu's $key",
                !header.contains("R.string.$key"),
            )
        }
    }

    /**
     * The Import row's own chain, end to end at source level: the panel's
     * remembered importer → the `OpenDocument` SAF picker → the read → the
     * `{info, messages}` parse → `POST /api/experimental/session/import`. The
     * logic of each stage is covered purely by [SessionImporterTest] /
     * [SessionImportParseTest], and the route itself by
     * [V2ApiSessionTransferTest]; what this pins is that the row is still
     * plugged into all of them.
     */
    @Test
    fun theImportRowWalksSafToTheImportRoute() {
        assertTrue(panel().contains("val importer = rememberSessionImporter()"))

        val importer =
            source("src/main/java/com/xiufeigo/opencodeshell/ui/session/export/SessionImporter.kt")
        // the picker …
        assertTrue(importer.contains("ActivityResultContracts.OpenDocument()"))
        // … the provider read …
        assertTrue(importer.contains("context.contentResolver.openInputStream(uri)"))
        // … the transfer parse …
        assertTrue(importer.contains("SessionExport.parseTransfer(body)"))
        // … and the route, with the picked file's halves verbatim
        assertTrue(importer.contains("api.sessionImport(transfer, locationDirectory).id"))

        val transfer = source("src/main/java/com/xiufeigo/opencodeshell/v2/V2ApiSessionTransfer.kt")
        assertTrue(transfer.contains("\"experimental/session/import\""))
    }

    @Test
    fun theFourLabelsExistInAllThreeLocales() {
        for (dir in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val body = source("src/main/res/$dir/strings.xml")
            for (key in labelKeys) {
                val value = Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .find(body)
                    ?.groupValues
                    ?.get(1)
                assertTrue("$dir is missing a non-blank $key", !value.isNullOrBlank())
            }
        }
    }
}
