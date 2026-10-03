package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.BuiltinCommands
import com.xiufeigo.opencodeshell.ui.palette.PALETTE_MIN_HEIGHT
import com.xiufeigo.opencodeshell.ui.palette.PaletteCommand
import com.xiufeigo.opencodeshell.ui.palette.PaletteEntry
import com.xiufeigo.opencodeshell.ui.palette.PaletteHostKind
import com.xiufeigo.opencodeshell.ui.palette.PaletteSources
import com.xiufeigo.opencodeshell.ui.palette.SessionCommands
import com.xiufeigo.opencodeshell.ui.palette.appCommands
import com.xiufeigo.opencodeshell.ui.palette.browseEntries
import com.xiufeigo.opencodeshell.ui.palette.commandEntries
import com.xiufeigo.opencodeshell.ui.palette.paletteBoxHeight
import com.xiufeigo.opencodeshell.ui.palette.paletteRows
import com.xiufeigo.opencodeshell.ui.palette.paletteShell
import com.xiufeigo.opencodeshell.ui.palette.placeholderResId
import com.xiufeigo.opencodeshell.ui.palette.searchEntries
import com.xiufeigo.opencodeshell.ui.palette.sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v37 tB: the **device's** palette assembly must be the whole command registry —
 * on both hosts.
 *
 * The tester's report (`research/v37_verify/tB/40_palette_export_none.png`): the
 * palette the Home tab strip opens answered `export` / `import` / `share` with
 * 「没有找到结果」, and its blank query listed the app rows only. The rows exist:
 * `ui/App.kt` gated the session table on `kind == PaletteHostKind.SESSION`, so the
 * Home host's registry — the one the strip button and the `mod+k` chord open
 * whenever Home is the route — was `appCommands` alone (11 rows) instead of
 * `appCommands + sessionCommands` (35), and every `session.*` / `terminal.new` /
 * `browser.*` / `file.attach` row was unreachable *and* unsearchable there.
 *
 * Why no existing test caught it: every palette test builds its own registry
 * (`PaletteScrollTest.sessionRegistry`, `PaletteSearchTest.registry`), so the
 * *host* half of the device's assembly — the part that decides how many rows the
 * palette actually gets — was never exercised. `PaletteScrollTest`'s
 * 「空查询全量」 was green while the phone showed six rows because it browses a
 * registry it assembled itself.
 *
 * This class therefore pins the device's own assembly, end to end:
 *
 * * the registry `ui/App.kt` wires (its exact action set, including the ids it
 *   leaves to a stub),
 * * the projection the Compose layer renders it through ([commandEntries], the
 *   JVM half of `stringResolvedEntries`) plus the [BuiltinCommands] rows
 *   `CommandPalette` injects,
 * * the rows a blank query browses and the rows a query searches — the same set,
 * * and the shell geometry that keeps that results area above the soft keyboard.
 *
 * The wiring itself (App.kt's host gate, CommandPalette's IME read) cannot run on
 * the JVM harness, so it is pinned against those two sources — the same
 * technique `PaletteEntryPointsTest` / `ChordCommandWiringTest` use.
 */
class PaletteDeviceProjectionTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun palette() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/palette/CommandPalette.kt")

    private fun app() = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")

    // ------------------------------------------------------- the device registry

    private val noop = {}

    /**
     * The registry `ui/App.kt` builds: `appCommands(...) + sessionCommands`, with
     * the exact action set that file passes — including `onArchive = null` and the
     * nine `SessionCommandActions` it leaves unset (those rows stay out of the
     * list, the rule `AppCommands`' KDoc states: a null action never leaves an
     * inert row behind).
     */
    private fun registry(): List<PaletteCommand> =
        appCommands(
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
        ) + SessionCommands.sessionCommands(
            actions = SessionCommands.SessionCommandActions(
                onUndo = noop,
                onRedo = noop,
                onCompact = noop,
                onBackground = noop,
                onToggleMcp = noop,
                onFork = noop,
                onCopySessionID = noop,
                onArchive = null,
                onToggleAutoAccept = noop,
                onAskAside = { },
                onExport = noop,
                onPreviousMessage = noop,
                onNextMessage = noop,
                onFocusInput = noop,
                onNewTerminal = noop,
                onChooseModel = noop,
                onOpenBrowser = noop,
                onReloadBrowser = noop,
                onAttachFile = noop,
                onShellMode = noop,
                onNormalMode = noop,
                onCloseFile = noop,
            ),
        )

    /** Every registered row a palette may list (the hidden ones never render). */
    private fun registryIds(): List<String> = registry().filterNot { it.hidden }.map { it.entryId }

    /** The `CommandPalette`-injected rows (`builtinCommandEntries`). */
    private fun builtinRows(): List<PaletteEntry> =
        BuiltinCommands.IDS.map { PaletteEntry.command(id = it, title = "builtin") }

    /**
     * The rows the device renders: the Compose projection of the registry
     * (`stringResolvedEntries`' JVM half) with the builtins merged in, assembled
     * for [kind] exactly like `PaletteDialogLayer`.
     */
    private fun deviceSources(kind: PaletteHostKind): PaletteSources {
        val projected = commandEntries(registry()) { "title" }
        val merged = BuiltinCommands.withBuiltinCommands(
            PaletteSources(commands = projected),
            builtinRows(),
        )
        return kind.sources(commands = merged.commands)
    }

    private fun browsed(kind: PaletteHostKind): List<String> =
        paletteRows(deviceSources(kind), "").map { it.id }

    // ------------------------------------------------- the projection == registry

    @Test
    fun theProjectedRowsAreEveryRegisteredCommandPlusTheBuiltins() {
        val projected = commandEntries(registry()) { "title" }.map { it.id }
        assertEquals(registryIds(), projected)
        // Nothing is dropped and nothing is invented: the palette lists each
        // registered row exactly once, then the three builtin panels.
        val expected = (registryIds() + BuiltinCommands.IDS.map(BuiltinCommands::entryId)).toSet()
        for (kind in listOf(PaletteHostKind.HOME, PaletteHostKind.SESSION)) {
            val rows = browsed(kind)
            assertEquals("$kind rows: $rows", expected, rows.toSet())
            assertEquals("$kind duplicated a row", expected.size, rows.size)
        }
    }

    @Test
    fun bothHostsBrowseTheSameCommandTable() {
        // The regression: the Home host's registry was gated on `kind == SESSION`,
        // so this pair differed by 24 rows and the phone's Home palette listed the
        // app commands only. The two hosts differ in the *file* category and the
        // placeholder (upstream), never in the commands — only the pinned head
        // (`preferredCommandEntries`, upstream's `COMMON_COMMAND_IDS` order) may
        // order the session host's rows differently.
        assertEquals(browsed(PaletteHostKind.SESSION).toSet(), browsed(PaletteHostKind.HOME).toSet())
        assertTrue("the registry is empty?", registryIds().size >= 30)
    }

    @Test
    fun theExportFamilyIsReachableFromBothHosts() {
        // The tester's queries, in the palette they typed them in (Home).
        for (kind in listOf(PaletteHostKind.HOME, PaletteHostKind.SESSION)) {
            val ids = paletteRows(deviceSources(kind), "export").map { it.id }
            assertTrue("$kind: export -> $ids", "command:session.export" in ids)
            assertTrue("$kind: import", "command:session.import" in paletteRows(deviceSources(kind), "import").map { it.id })
            assertTrue("$kind: terminal", "command:terminal.toggle" in paletteRows(deviceSources(kind), "terminal").map { it.id })
            assertTrue("$kind: browser", "command:browser.open" in paletteRows(deviceSources(kind), "browser").map { it.id })
            // …and the debug rows the tester could only reach by typing stay
            // reachable by scrolling: both are in the blank-query catalog.
            val catalog = browsed(kind)
            assertTrue("$kind: debugBar.toggle", "command:debugBar.toggle" in catalog)
            assertTrue("$kind: debug.locations", "command:debug.locations" in catalog)
        }
    }

    @Test
    fun offRouteSessionRowsAreListedButInert() {
        // Home lists the session rows (the blank query is the whole registry) but
        // has no session screen to serve them, so their action is replaced by a
        // no-op — the state their chords already have off-route. The row keeps its
        // id, keybind and category, which is what makes it *findable*.
        val fired = mutableListOf<String>()
        val actions = SessionCommands.SessionCommandActions(
            onUndo = { fired.add("session.undo") },
            onCompact = { fired.add("session.compact") },
            onExport = { fired.add("session.export") },
        )
        val live = SessionCommands.sessionCommands(actions = actions, enabled = true)
        val offRoute = SessionCommands.sessionCommands(actions = actions, enabled = false)

        assertEquals(live.map { it.id }, offRoute.map { it.id })
        assertEquals(live.map { it.keybind }, offRoute.map { it.keybind })
        assertEquals(live.map { it.category }, offRoute.map { it.category })

        for (row in offRoute) row.onSelect()
        assertEquals(emptyList<String>(), fired)
        live.first { it.id == "session.export" }.onSelect()
        assertEquals(listOf("session.export"), fired)
    }

    @Test
    fun everySearchHitIsARowTheBlankQueryBrowses() {
        // 「搜索结果与列表不一致」: a hit the browse list does not carry is a row
        // the user can find by typing but never see in the list. Same sources, so
        // the search can only ever be a subset of the catalog.
        val queries = listOf("export", "import", "debug", "terminal", "browser", "model", "session", "文件")
        for (kind in listOf(PaletteHostKind.HOME, PaletteHostKind.SESSION)) {
            val sources = deviceSources(kind)
            val catalog = browseEntries(sources).map { it.id }.toSet()
            for (query in queries) {
                for (hit in searchEntries(sources, query)) {
                    assertTrue("$kind: '$query' hit $hit outside the catalog", hit.id in catalog)
                }
            }
        }
    }

    @Test
    fun theSessionHostKeepsItsFilesAndHomeItsPlaceholder() {
        // The parts that *are* per-host (upstream's two dialog components): Home
        // has no file category, the session host adds `/find/file` results.
        val files = listOf(PaletteEntry.file("src/a.kt"))
        val home = PaletteHostKind.HOME.sources(commands = emptyList(), files = files)
        assertTrue(home.files.isEmpty())
        assertTrue(searchEntries(home, "a.kt").isEmpty())
        val session = PaletteHostKind.SESSION.sources(commands = emptyList(), files = files)
        assertEquals(listOf("file:src/a.kt"), searchEntries(session, "a.kt").map { it.id })
        assertEquals(R.string.oc_palette_placeholder_home, PaletteHostKind.HOME.placeholderResId())
        assertEquals(R.string.oc_palette_placeholder, PaletteHostKind.SESSION.placeholderResId())
    }

    // ------------------------------------------------------------ the results area

    @Test
    fun theResultsAreaStaysAboveTheKeyboard() {
        // Phone geometry (1080x2424 @2.625 ≈ 411x923dp) with the IME up: the
        // keyboard is drawn over the composition, so the card is sized against
        // `window - ime`. The regression sized it against the window, which put
        // `topMargin + maxHeight` far past the keyboard's edge.
        val window = 923f
        val ime = 348f
        val visible = window - ime
        val shell = paletteShell(411f, visible)
        assertTrue(
            "${shell.topMargin} + ${shell.maxHeight} > $visible",
            shell.topMargin + shell.maxHeight <= visible,
        )
        assertEquals(48f, shell.topMargin)
        assertEquals(479f, shell.maxHeight)
        // A taller visible area keeps upstream's 480dp cap.
        assertEquals(480f, paletteShell(411f, 700f).maxHeight)
        assertEquals(110f, paletteShell(411f, 700f).topMargin)

        // No keyboard: upstream's own numbers, unchanged.
        val full = paletteShell(411f, window)
        assertEquals(480f, full.maxHeight)
        assertEquals((window - 480f) / 2f, full.topMargin)
        assertEquals(387f, paletteShell(411f, window).width)

        // Desktop/tablet width keeps the 640dp cap, and the shell never shrinks
        // below its 280dp floor even in a window shorter than the card.
        assertEquals(640f, paletteShell(1440f, window).width)
        assertTrue(paletteShell(411f, 300f).maxHeight >= PALETTE_MIN_HEIGHT)

        // …and the card lands where §6.3's `margin-top` says it does: the dialog
        // layer centres the palette box in the window, so the box's own height is
        // what decides the offset (`paletteBoxHeight`), and the card is top-aligned
        // inside it.
        val box = paletteBoxHeight(window, shell.topMargin)
        val cardTop = (window - box) / 2f
        val cardBottom = cardTop + shell.maxHeight
        assertEquals(48f, cardTop)
        assertTrue("card bottom $cardBottom > visible $visible", cardBottom <= visible)
        // Without a keyboard the same maths reproduces upstream's own placement.
        val fullTop = (window - paletteBoxHeight(window, full.topMargin)) / 2f
        assertEquals(full.topMargin, fullTop)
        assertTrue(fullTop + full.maxHeight <= window)
    }

    // ------------------------------------------------------------- the wiring pins

    @Test
    fun theHostGateKeepsEveryCommandExceptFilesOnly() {
        val body = app()
        assertTrue(
            "only FILES_ONLY drops the command table (upstream `filesOnly()`)",
            body.contains("if (kind == PaletteHostKind.FILES_ONLY) return@remember emptyList()"),
        )
        assertTrue(
            "the command table must not be gated on the session host",
            !body.contains("if (kind != PaletteHostKind.SESSION) return@remember emptyList()"),
        )
        assertTrue(
            "off the session route the rows stay listed and only lose their action",
            body.contains("enabled = kind == PaletteHostKind.SESSION"),
        )
        // FILES_ONLY keeps upstream's contract: no command rows at all.
        val filesOnly = PaletteHostKind.FILES_ONLY.sources(
            commands = commandEntries(registry()) { "title" },
            files = listOf(PaletteEntry.file("src/a.kt")),
        )
        assertTrue(filesOnly.commands.isEmpty())
    }

    @Test
    fun thePaletteSizesItselfAgainstTheImeInset() {
        val body = palette()
        assertTrue(body.contains("WindowInsets.ime.getBottom(this)"))
        assertTrue(body.contains("paletteShell(viewportWidth.value, visibleHeight.value)"))
        // The card is anchored to §6.3's `margin-top` inside the window, not left
        // wherever the dialog layer's centring puts a content-sized box (that is
        // what pushed the results area under the keyboard).
        assertTrue(body.contains("paletteBoxHeight(windowHeight.value, shell.topMargin)"))
        assertTrue(body.contains("modifier.fillMaxWidth().height(shellBoxHeight)"))
        assertTrue(body.contains("contentAlignment = Alignment.TopCenter"))
        assertTrue(body.contains("heightIn(min = PALETTE_MIN_HEIGHT.dp, max = maxHeight)"))
        // The old window-only geometry must not come back.
        assertTrue(!body.contains("minOf(viewportHeight - 96.dp, 480.dp)"))
    }

    @Test
    fun theComposeProjectionMatchesThePureOne() {
        // `stringResolvedEntries` is the Compose half of `commandEntries`: same
        // hidden filter, same title/description, same English terms. The device
        // renders the former; every palette test asserts against the latter — so
        // a divergence between them would be invisible to the whole suite.
        val projection = palette().substringAfter("fun stringResolvedEntries")
        assertTrue(projection.contains("filterNot { (command, _, _) -> command.hidden }"))
        assertTrue(projection.contains("searchTerms = commandEnglishTerms(command, englishTerm)"))
        val pure = source("src/main/java/com/xiufeigo/opencodeshell/ui/palette/PaletteModel.kt")
            .substringAfter("fun commandEntries(")
        assertTrue(pure.contains("commands.filterNot { it.hidden }"))
        assertTrue(pure.contains("searchTerms = commandEnglishTerms(command, resolveEnglish)"))
    }
}
