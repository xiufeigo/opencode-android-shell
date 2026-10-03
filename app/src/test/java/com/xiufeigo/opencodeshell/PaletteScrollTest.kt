package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.BuiltinCommands
import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_COMMANDS
import com.xiufeigo.opencodeshell.ui.palette.PaletteEntry
import com.xiufeigo.opencodeshell.ui.palette.PaletteHostKind
import com.xiufeigo.opencodeshell.ui.palette.PaletteSources
import com.xiufeigo.opencodeshell.ui.palette.SessionCommands
import com.xiufeigo.opencodeshell.ui.palette.appCommands
import com.xiufeigo.opencodeshell.ui.palette.browseEntries
import com.xiufeigo.opencodeshell.ui.palette.commandEntries
import com.xiufeigo.opencodeshell.ui.palette.groupEntries
import com.xiufeigo.opencodeshell.ui.palette.lazyRowIndices
import com.xiufeigo.opencodeshell.ui.palette.paletteGroupLabelResId
import com.xiufeigo.opencodeshell.ui.palette.paletteRows
import com.xiufeigo.opencodeshell.ui.palette.sources
import com.xiufeigo.opencodeshell.ui.palette.visibleEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The palette's results area must scroll, and a blank query must reach the whole
 * registry.
 *
 * The phone report (`research/v30_verify/tB_ui_spot/tB_palette_scroll.png`): a
 * Ctrl+K palette with an empty query rendered only the preferred rows, and
 * swiping never moved — the row list was shorter than the shell's 280dp
 * minimum, so the results area had nothing to scroll and every command below
 * the cap (browser.*, file.attach, …) looked missing unless it was typed out.
 * Two things keep that from coming back: the rows for a blank query are the
 * whole catalog (`browseEntries`), and the results area stays a single bounded
 * `LazyColumn` — the shell's `heightIn` caps it, so it scrolls by touch drag and
 * by wheel (`scrollable` handles `PointerEventType.Scroll`) instead of growing
 * the dialog.
 */
class PaletteScrollTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun palette() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/palette/CommandPalette.kt")

    private fun command(id: String, title: String = id, category: String = CATEGORY_COMMANDS) =
        PaletteEntry.command(id = id, title = title, category = category)

    // ------------------------------------------------------- results scroller

    @Test
    fun resultsAreaIsOneLazyColumnDrivenByItsOwnState() {
        val body = palette()
        assertTrue(body.contains("LazyColumn("))
        assertTrue(body.contains("state = listState"))
        assertTrue(body.contains("paletteRows(effectiveSources, query)"))
        // One scroller only: a nested `verticalScroll` would fragment drags the
        // way the settings context tab's did (`SessionContextPanelScrollTest`).
        assertEquals(0, Regex("verticalScroll\\(").findAll(body).count())
    }

    @Test
    fun followScrollTargetsTheActiveRowsLazySlot() {
        val body = palette()
        assertTrue(body.contains("lazyRowIndices(grouped)"))
        assertTrue(body.contains("listState.scrollToItem(slot)"))
        // `block:nearest`: only scroll when the active row left the viewport.
        assertTrue(body.contains("visibleItemsInfo.none { it.index == slot }"))
    }

    @Test
    fun lazyRowIndicesCountTheGroupTitleItems() {
        val grouped = groupEntries(
            listOf(
                command("a"),
                command("b"),
                PaletteEntry.file("x.kt"),
                PaletteEntry.session("srv", "s1", "s"),
            ),
        )
        val indices = lazyRowIndices(grouped)
        // [title Commands, a, b, title Files, x.kt, title Session, s]
        assertEquals(1, indices["command:a"])
        assertEquals(2, indices["command:b"])
        assertEquals(4, indices["file:x.kt"])
        assertEquals(6, indices["session:srv:s1"])
    }

    // ------------------------------------------------------ full catalog rows

    @Test
    fun blankQueryBrowsesTheWholeCatalog() {
        val commands = (1..30).map { command("cmd$it") }
        val sources = PaletteSources(commands = commands)
        assertEquals(30, paletteRows(sources, "").size)
        // Registry order, every row exactly once, no cap.
        assertEquals(commands.map { it.id }, paletteRows(sources, "").map { it.id })
        assertEquals(commands.map { it.id }, browseEntries(sources).map { it.id })
        // `isBlank`, not `isEmpty` — a whitespace query browses too.
        assertEquals(30, paletteRows(sources, "   ").size)
    }

    @Test
    fun blankQueryKeepsUpstreamsRowsPinnedOnTop() {
        val commands = listOf(
            "settings.open",
            "session.new",
            "workspace.new",
            "session.previous",
            "session.next",
            "terminal.toggle",
            "review.toggle",
            "file.open",
            "zz.last",
        ).map { command(it) }
        val sources = PaletteSources(commands = commands)
        val rows = browseEntries(sources)
        // The head is `emptyQueryEntries` verbatim — upstream's preferred rows,
        // `ENTRY_LIMIT`-capped as `SettingsPaletteModelTest` pins them.
        assertEquals(
            listOf(
                "command:session.new",
                "command:workspace.new",
                "command:session.previous",
                "command:session.next",
                "command:terminal.toggle",
            ),
            rows.take(5).map { it.id },
        )
        // The 6th common id is the one the pinned cap leaves out, so it rides
        // the tail like every other command…
        assertTrue(rows.drop(5).any { it.id == "command:review.toggle" })
        // …and no row is lost or duplicated: the whole registry is browsable.
        assertEquals(commands.size, rows.size)
        assertEquals(commands.map { it.id }.toSet(), rows.map { it.id }.toSet())
    }

    @Test
    fun blankQueryKeepsRecentFilesRightAfterThePreferredRows() {
        // Upstream's blank-query order is `[...preferred, ...recentFiles]`; the
        // browse tail must not duplicate or displace them.
        val sources = PaletteSources(
            commands = listOf(command("session.new"), command("zz.last")),
            recentFiles = listOf(PaletteEntry.file("src/recent.kt")),
        )
        assertEquals(
            listOf("command:session.new", "file:src/recent.kt", "command:zz.last"),
            browseEntries(sources).map { it.id },
        )
    }

    @Test
    fun blankQueryAlsoCarriesTheBuiltinRows() {
        val builtin = PaletteEntry.command(id = BuiltinCommands.PLUGIN_RPC, title = "Plugin RPC")
        val merged = BuiltinCommands.withBuiltinCommands(
            PaletteSources(commands = listOf(command("session.new"))),
            listOf(builtin),
        )
        val rows = browseEntries(merged)
        assertEquals(listOf("command:session.new", builtin.id), rows.map { it.id })
    }

    @Test
    fun blankQueryReachesTheCommandsThatUsedToNeedATypedQuery() {
        // The rows the tester could only reach by typing "browser" / "attach",
        // through the real session registry (`ui/App.kt`'s assembly).
        val rows = browseEntries(PaletteSources(commands = sessionRegistry()))
        assertTrue(rows.any { it.id == "command:browser.open" })
        assertTrue(rows.any { it.id == "command:browser.reload" })
        assertTrue(rows.any { it.id == "command:file.attach" })
        // …and there is now something to scroll: the shell is at most 480dp
        // tall, the search field takes 48dp, a row is 36dp.
        assertTrue("${rows.size} rows", rows.size * 36 > 480 - 48)
    }

    @Test
    fun filesOnlyPaletteKeepsItsEmptyQueryContract() {
        // `file.open` re-opens the palette with `filesOnly()`: no command rows at
        // all, and a blank query still shows only the recent files upstream
        // feeds it (the `/find/file` results need a query).
        val sources = PaletteHostKind.FILES_ONLY.sources(
            commands = listOf(command("session.new")),
            files = listOf(PaletteEntry.file("src/a.kt")),
            recentFiles = listOf(PaletteEntry.file("src/recent.kt")),
        )
        assertEquals(listOf("file:src/recent.kt"), paletteRows(sources, "").map { it.id })
    }

    @Test
    fun aTypedQueryKeepsUpstreamsSearchSemantics() {
        val sources = PaletteSources(
            commands = listOf(command("browser.open", "Open browser"), command("settings.open", "Open settings")),
            sessions = listOf(PaletteEntry.session("srv", "ses_1", "browser session")),
        )
        assertEquals(visibleEntries(sources, "browser"), paletteRows(sources, "browser"))
        assertEquals(
            listOf("command:browser.open", "session:srv:ses_1"),
            paletteRows(sources, "browser").map { it.id },
        )
        assertTrue(paletteRows(sources, "zzz").isEmpty())
    }

    @Test
    fun everyBrowsedGroupHasALabel() {
        // The browse list shows every group at once, so a category the palette
        // cannot name would read as raw English inside a translated UI.
        val categories = sessionRegistry().map { it.category } + CATEGORY_COMMANDS
        assertTrue(categories.isNotEmpty())
        for (category in categories.toSet()) {
            assertNotNull("no label for $category", paletteGroupLabelResId(category))
        }
        // An unknown host category keeps the literal (`null` ⇒ `?: category`).
        assertNull(paletteGroupLabelResId("Host Category"))
    }

    // -------------------------------------------------------------- fixtures

    /**
     * The session route's registry as `ui/App.kt` wires it: app commands plus
     * every session row whose action the host provides.
     */
    private fun sessionRegistry(): List<PaletteEntry> {
        val noop = {}
        val session = SessionCommands.sessionCommands(
            actions = SessionCommands.SessionCommandActions(
                onUndo = noop,
                onRedo = noop,
                onCompact = noop,
                onBackground = noop,
                onFork = noop,
                onExport = noop,
                onCopySessionID = noop,
                onAskAside = { },
                onPreviousMessage = noop,
                onNextMessage = noop,
                onToggleMcp = noop,
                onToggleAutoAccept = noop,
                onFocusInput = noop,
                onNewTerminal = noop,
                onCloseFile = noop,
                onAddSelection = noop,
                onCopyProjectID = noop,
                onSelectProject = noop,
                onCycleLocation = noop,
                onChooseModel = noop,
                onCycleVariant = noop,
                onCycleAgent = noop,
                onCycleAgentReverse = noop,
                onAttachFile = noop,
                onShellMode = noop,
                onNormalMode = noop,
                onOpenBrowser = noop,
                onReloadBrowser = noop,
            ),
            stubReporter = null,
        )
        val app = appCommands(
            onOpenSettings = noop,
            onOpenTerminal = noop,
            onNewSession = noop,
            onNewWorkspace = noop,
            onPreviousSession = noop,
            onNextSession = noop,
            onToggleReview = noop,
            onOpenFilePicker = noop,
            onToggleFileTree = noop,
            onToggleSummary = noop,
            onToggleDebugBar = noop,
        )
        return commandEntries(app + session) { "title" }
    }
}
