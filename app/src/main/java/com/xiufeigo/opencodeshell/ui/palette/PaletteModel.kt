package com.xiufeigo.opencodeshell.ui.palette

import com.xiufeigo.opencodeshell.R

/**
 * Pure (JVM-testable) model of the opencode v2 command palette.
 *
 * Source of truth: `research/02_ui_screens.md` §6 (data union, grouping,
 * empty-query contents, row CSS) and `research/05_palette_host_session.md` §1/§2
 * (the palette is pushed onto a GLOBAL dialog stack; Home and session/new-session
 * share one view and differ only in data assembly + placeholder).
 */

/** `CommandPaletteEntry.type` — exactly three kinds, no `model` kind. */
enum class PaletteEntryType { COMMAND, FILE, SESSION }

/** `CommandPaletteEntry` (the fields M4 renders). */
data class PaletteEntry(
    val id: String,
    val type: PaletteEntryType,
    val title: String,
    val description: String? = null,
    val keybind: String? = null,
    val category: String,
    /** `file` only. */
    val path: String? = null,
    /** `session` only. */
    val sessionID: String? = null,
    /** `session` only — relative-time bucket, pre-rendered. */
    val meta: String? = null,
    /** `session` only — the row shows the "already open" bar. */
    val open: Boolean = false,
    val archived: Boolean = false,
    /**
     * `command` only — the row's **English source copy** (`values/`, the
     * resource table's default locale; `values-zh-rCN` / `values-zh-rTW` hold
     * the translations). Search-only, never rendered: the row shows the UI
     * locale's [title]/[description], while these keep the upstream English
     * name reachable on a translated device (`debug` → 「切换调试栏」). The
     * Compose layer fills them from a locale-forced string table
     * ([commandEnglishTerms]); nothing is hand-written here.
     */
    val searchTerms: List<String> = emptyList(),
) {
    companion object {
        /** `"command:" + option.id`. */
        fun command(
            id: String,
            title: String,
            description: String? = null,
            keybind: String? = null,
            category: String = CATEGORY_COMMANDS,
            searchTerms: List<String> = emptyList(),
        ) = PaletteEntry(
            "command:$id",
            PaletteEntryType.COMMAND,
            title,
            description,
            keybind,
            category,
            searchTerms = searchTerms,
        )

        /** `"file:" + path`. */
        fun file(path: String, category: String = CATEGORY_FILES) =
            PaletteEntry(
                id = "file:$path",
                type = PaletteEntryType.FILE,
                title = path,
                category = category,
                path = path,
            )

        /** `` `session:${server}:${sessionID}` ``. */
        fun session(
            server: String,
            sessionID: String,
            title: String,
            description: String? = null,
            meta: String? = null,
            open: Boolean = false,
            archived: Boolean = false,
        ) = PaletteEntry(
            id = "session:$server:$sessionID",
            type = PaletteEntryType.SESSION,
            title = title,
            description = description,
            category = CATEGORY_SESSION,
            sessionID = sessionID,
            meta = meta,
            open = open,
            archived = archived,
        )
    }
}

/** `palette.group.commands` = "Commands". */
const val CATEGORY_COMMANDS: String = "Commands"

/** `palette.group.files` = "Files". */
const val CATEGORY_FILES: String = "Files"

/** `command.category.session` = "Session" — session rows hang under THIS group. */
const val CATEGORY_SESSION: String = "Session"

/** `ENTRY_LIMIT = 5`. */
const val ENTRY_LIMIT: Int = 5

/** `COMMON_COMMAND_IDS` (first five are used for the empty query). */
val COMMON_COMMAND_IDS: List<String> = listOf(
    "session.new",
    "workspace.new",
    "session.previous",
    "session.next",
    "terminal.toggle",
    "review.toggle",
)

/** `DEFAULT_PALETTE_KEYBIND = "mod+k,mod+shift+p"`. */
const val DEFAULT_PALETTE_KEYBIND: String = "mod+k,mod+shift+p"

/** `PALETTE_ID = "command.palette"`. */
const val PALETTE_ID: String = "command.palette"

/** `min-height: 280px` of the shell (§6.3). */
const val PALETTE_MIN_HEIGHT: Float = 280f

/**
 * The query every open starts from — always blank.
 *
 * v54 (`research/v54_verify/tB/tB_71_palette_redo.png` →
 * `tB_73_after_redo2.png`): typing「redo」, running Redo and reopening the
 * palette came back with 「redo」 still in the search field, so the next word
 * landed as 「redoredo」 and matched nothing. Upstream's `dialog.tsx` opens the
 * palette with an empty `query` every time — it is dialog-local state, not
 * something a dialog remembers.
 *
 * Named here rather than inlined so the rule is one readable constant instead of
 * a `""` buried in a `remember`, and so the JVM test can pin it.
 */
const val PALETTE_OPEN_QUERY: String = ""

/** The palette shell's §6.3 geometry, in dp. */
data class PaletteShell(
    /** `width: min(100vw - 24px, 640px)`. */
    val width: Float,
    /** `max-height: min(100vh - 96px, 480px)`, never below [PALETTE_MIN_HEIGHT]. */
    val maxHeight: Float,
    /** `margin-top: max(48px, (100vh - 480px) / 2)`. */
    val topMargin: Float,
)

/**
 * The palette shell's size, measured against the space the user can actually
 * see: the window minus the soft keyboard ([visibleHeight]).
 *
 * §6.3 sizes the container against `100vh`, and on this shell that is
 * `LocalConfiguration.screenHeightDp` — the **display** height, which does not
 * change when the keyboard comes up (`OcActivity` is edge-to-edge and the IME is
 * drawn *over* the composition, exactly the reason `SessionScreen` reads
 * `WindowInsets.ime` to lift its composer). The palette autofocuses its search
 * field, so the keyboard is always up when it opens: on a phone the card was
 * laid out for a ~920dp window, and its results area — every row below the head
 * of the list — ended up behind the keyboard, where a swipe lands on the IME and
 * nothing scrolls. That is the 「空查询只剩 6 条命令且滚不动」 report, and why the
 * device disagreed with the model tests: the *rows* were all there, the
 * *viewport* was not.
 *
 * With [visibleHeight] the card keeps upstream's numbers for the space above the
 * keyboard, so the whole results area stays reachable while typing.
 */
fun paletteShell(viewportWidth: Float, visibleHeight: Float): PaletteShell {
    val width = minOf(viewportWidth - 24f, 640f)
    val maxHeight = minOf(visibleHeight - 96f, 480f).coerceAtLeast(PALETTE_MIN_HEIGHT)
    val topMargin = maxOf(48f, (visibleHeight - 480f) / 2f)
    return PaletteShell(width, maxHeight, topMargin)
}

/**
 * The height the palette's own box takes inside the dialog layer.
 *
 * `DialogHost` centres a layer's content box in the **window**
 * (`Alignment.Center`), so a box sized to its content lands
 * `(windowHeight - boxHeight) / 2` from the top and the shell's `margin-top`
 * ([PaletteShell.topMargin]) is added *again* below that — the card sat lower
 * than §6.3's `max(48px, (100vh - 480px) / 2)` and, with the keyboard up, most of
 * its results area fell behind the IME.
 *
 * At `windowHeight - 2 * topMargin` the centring offset works out to exactly
 * `topMargin`, so the card's top edge is §6.3's `margin-top` — and the box keeps
 * real bounds for the scrim-tap dismissal (`scrimTapDismisses`): a tap outside it
 * still closes the palette.
 */
fun paletteBoxHeight(windowHeight: Float, topMargin: Float): Float =
    (windowHeight - topMargin * 2f).coerceAtLeast(PALETTE_MIN_HEIGHT)

/**
 * v98 — **the palette's dead zone** (`research/v97_verify/5564_v98/REPORT_PERF.md` §6.3).
 *
 * `DialogHost` guards its scrim's taps against the box it measured as the
 * layer's content ([com.xiufeigo.opencodeshell.ui.overlay.DialogScrimState.contentBounds])
 * unless the layer reports its own card. `CommandPalette` did not report one, so
 * the guarded box was [paletteBoxHeight] laid out **centred** in the window
 * ([DialogHost]'s `Alignment.Center`) — and centring makes the box's top edge
 * land on [topMargin], so its **bottom edge sits at `windowHeight - topMargin`**.
 *
 * The palette autofocuses its search field, so the IME is up by default; the IME
 * shrinks `visibleHeight`, which collapses [PaletteShell.topMargin] onto its
 * 48 dp floor and pushes that bottom edge up into the bottom tab strip. Every
 * tap inside the strip was then answered "inside the content box" by
 * [com.xiufeigo.opencodeshell.ui.overlay.scrimTapDismisses]: the layer neither
 * dismissed nor let the event reach the strip — a tap that does nothing at all,
 * which is what the perf run measured (node count constant over 28 s, 196 frames
 * rendered, zero ANR).
 *
 * This is the pure statement of that box so a JVM test can pin the dead zone
 * without a device. The fix is structural, not arithmetic: the card reports its
 * own bounds ([com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds]), so the
 * guarded box becomes the card — at most `topMargin + maxHeight` tall — and
 * everything the user sees dimmed is backdrop again.
 */
fun paletteFallbackGuardedBottom(windowHeight: Float, topMargin: Float): Float =
    windowHeight - topMargin

/**
 * v98 — the close exit the palette must always offer, as a target state.
 *
 * [onScrimTap] is the whole contract of the fix in one pure function: a layer
 * that is open and gets a tap on its backdrop has exactly one legal outcome,
 * [PaletteLayerState.CLOSED]. There is no third state where the tap is
 * swallowed, and a tap *on the card* leaves the layer open (a card tap is a tap
 * on the dialog, not on the backdrop — the rule in
 * [com.xiufeigo.opencodeshell.ui.overlay.DialogLayerPolicy] that the palette
 * keeps).
 */
enum class PaletteLayerState {
    /** No layer mounted — the palette is not on screen. */
    CLOSED,

    /** The palette layer is up; its scrim still dismisses. */
    OPEN,
}

/**
 * The state a tap on the layer's backdrop must leave behind.
 *
 * - open + backdrop tap → [PaletteLayerState.CLOSED] (the dead zone's exit)
 * - open + card tap → [PaletteLayerState.OPEN] (unchanged: the card owns it)
 * - closed + any tap → [PaletteLayerState.CLOSED] (nothing to close)
 *
 * Mirrors the wiring in [com.xiufeigo.opencodeshell.ui.overlay.DialogHost] —
 * `if (cardState.dismisses(local)) controller.close()`.
 */
fun onScrimTap(open: Boolean, tapInsideCard: Boolean): PaletteLayerState = when {
    !open -> PaletteLayerState.CLOSED
    tapInsideCard -> PaletteLayerState.OPEN
    else -> PaletteLayerState.CLOSED
}

/**
 * `uniqueCommandPaletteEntries` — dedupe by id, keeping the FIRST occurrence.
 */
fun uniqueEntries(entries: List<PaletteEntry>): List<PaletteEntry> {
    val seen = HashSet<String>(entries.size)
    return entries.filter { seen.add(it.id) }
}

/**
 * `groups(entries)` — group by `category`, preserving first-seen order; a group
 * with no entries is never rendered (`Show(category)`).
 */
fun groupEntries(entries: List<PaletteEntry>): List<Pair<String, List<PaletteEntry>>> {
    val order = LinkedHashMap<String, MutableList<PaletteEntry>>()
    for (entry in entries) order.getOrPut(entry.category) { mutableListOf() }.add(entry)
    return order.entries.filter { it.value.isNotEmpty() }.map { it.key to it.value.toList() }
}

/**
 * `matchesEntry` — case-insensitive `contains` over the row's [searchHaystacks].
 *
 * Upstream (`dialog.tsx:25-28`) matches `[title, description, category]` and
 * every one of those is *localized*: on a Chinese device 「切换调试栏」 is found
 * by 「调试」 but `debug` matches nothing, so the ~40 commands behind the
 * palette become unreachable by the name a developer knows them by
 * (`research/v36_verify/tC/tC_04_palette_search_debug.png` — query `debug`,
 * 「没有找到结果」). The shell therefore widens the haystack by the row's
 * identity, both halves derived from the registration rather than a hand-kept
 * alias table:
 *
 * * a command row's **id words** — `debugBar.toggle` ⇒ `debug`, `bar`, `toggle`
 *   ([commandIdSearchTerms]),
 * * its **English source copy** ([PaletteEntry.searchTerms]).
 *
 * A haystack answers a query by upstream's `contains` **or** by the fuzzy
 * subsequence ([matchesTerm]) — `trm` finds 「Toggle terminal」, which the
 * desktop keybinds page's `fuzzysort` filter also finds.
 */
fun matchesEntry(entry: PaletteEntry, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return searchHaystacks(entry).any { matchesTerm(q, it) }
}

/**
 * One haystack against [query] — upstream's `includes` first, then the fuzzy
 * subsequence the desktop app uses everywhere else (the keybinds page runs its
 * rows through `fuzzysort`, `settings/keybinds/keybinds.tsx:183-186`).
 *
 * Upstream's palette filter is a plain `includes` (`dialog.tsx:26-28`), so on
 * the phone 「trm」 — the abbreviation every developer types for *Toggle
 * terminal* — matched nothing, and the one command whose name contains all
 * three letters in order was unreachable except by its full title. The
 * subsequence fallback is strictly wider than `includes` (it accepts everything
 * `contains` does, plus the abbreviated spellings), so no query that answered
 * before can stop answering.
 */
fun matchesTerm(query: String, haystack: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val h = haystack.lowercase()
    if (h.contains(q)) return true
    return fuzzySubsequence(q, h)
}

/**
 * Every character of [query] occurs in [candidate], in order but not
 * necessarily adjacent — `trm` against `toggle terminal` (`t`, `r`, then `m`).
 *
 * Empty query matches everything; a query with no characters in the candidate
 * does not. The scan is greedy left-to-right, which is the reading order a
 * person types an abbreviation in.
 */
fun fuzzySubsequence(query: String, candidate: String): Boolean {
    if (query.isEmpty()) return true
    var index = 0
    for (ch in candidate) {
        if (ch == query[index]) {
            index++
            if (index == query.length) return true
        }
    }
    return false
}

/**
 * Every string a row matches against: upstream's three, then the shell's two
 * extra dimensions (a command's id words and its English copy).
 */
fun searchHaystacks(entry: PaletteEntry): List<String> = buildList {
    add(entry.title)
    entry.description?.let(::add)
    add(entry.category)
    if (entry.type == PaletteEntryType.COMMAND) addAll(commandIdSearchTerms(entry.id))
    addAll(entry.searchTerms)
}

/**
 * A command row's id, cut into the words a person would type: the `command:`
 * prefix is dropped (it is not part of the id and would make the query
 * "command" hit every row), camel humps are split, and `.`/`_`/`-`/`:`/`/` are
 * separators.
 *
 * The result is the id with its punctuation removed, the same words joined by
 * spaces, then each word — so `debugBar.toggle` answers to `debugBar`,
 * `debug bar` and `debug` alike:
 * `["debugbar.toggle", "debug bar toggle", "debug", "bar", "toggle"]`.
 */
fun commandIdSearchTerms(entryId: String): List<String> {
    val id = entryId.removePrefix("command:")
    if (id.isEmpty()) return emptyList()
    val words = id.split('.', ':', '_', '-', '/', ' ')
        .flatMap { CAMEL_HUMP.split(it) }
        .filter { it.isNotEmpty() }
        .map { it.lowercase() }
    return buildList {
        add(id.lowercase())
        if (words.isNotEmpty()) add(words.joinToString(" "))
        addAll(words)
    }
}

/** `copyID` ⇒ `copy` + `ID`; `debugBar` ⇒ `debug` + `Bar`. */
private val CAMEL_HUMP = Regex("(?<=[a-z0-9])(?=[A-Z])")

/**
 * A command's English source copy, given a table that resolves resource ids
 * against `values/` (the Compose layer hands in a locale-forced `Resources`,
 * see `stringResolvedEntries`). A key the default table does not define
 * contributes nothing rather than failing the search.
 */
fun commandEnglishTerms(command: PaletteCommand, english: (Int) -> String?): List<String> =
    listOfNotNull(command.titleResId, command.descriptionResId)
        .mapNotNull(english)
        .filter { it.isNotBlank() }

/** Which catalog data a palette host assembles (Home vs session/new-session). */
data class PaletteSources(
    /** All registered commands. */
    val commands: List<PaletteEntry>,
    /** Session entries (already built from `session.list`). */
    val sessions: List<PaletteEntry> = emptyList(),
    /** File entries — only the session/new-session hosts pass these. */
    val files: List<PaletteEntry> = emptyList(),
    /** `recentFileEntries()` = opened tabs, active file first, deduped, 5 max. */
    val recentFiles: List<PaletteEntry> = emptyList(),
    /** Home has no file category at all (§6.2). */
    val includeFiles: Boolean = true,
)

/**
 * `preferredCommandEntries` — the `COMMON_COMMAND_IDS` in their declared order
 * when present, otherwise the first [ENTRY_LIMIT] commands from the registry.
 */
fun preferredCommandEntries(commands: List<PaletteEntry>, limit: Int = ENTRY_LIMIT): List<PaletteEntry> {
    val byId = commands.associateBy { it.id }
    val preferred = COMMON_COMMAND_IDS.mapNotNull { byId["command:$it"] }
    return (if (preferred.isNotEmpty()) preferred else commands).take(limit)
}

/**
 * Empty-query contents.
 *
 * - `DialogCommandPaletteV2` (session/new-session): common commands + recent files.
 * - `DialogHomeCommandPaletteV2` (Home): `commandEntries().slice(0, 5)`, no files.
 */
fun emptyQueryEntries(sources: PaletteSources): List<PaletteEntry> =
    if (sources.includeFiles) {
        uniqueEntries(preferredCommandEntries(sources.commands) + sources.recentFiles.take(ENTRY_LIMIT))
    } else {
        uniqueEntries(sources.commands.take(ENTRY_LIMIT))
    }

/**
 * Non-empty query: matching commands + matching sessions (+ files for the
 * session/new-session hosts).
 */
fun searchEntries(sources: PaletteSources, query: String): List<PaletteEntry> {
    if (query.isBlank()) return emptyQueryEntries(sources)
    val matches = sources.commands.filter { matchesEntry(it, query) } +
        sources.sessions.filter { matchesEntry(it, query) }
    val files = if (sources.includeFiles) sources.files.filter { matchesEntry(it, query) } else emptyList()
    return uniqueEntries(matches + files)
}

/** Upstream's row contract for a query — the empty-query contents when blank. */
fun visibleEntries(sources: PaletteSources, query: String): List<PaletteEntry> =
    uniqueEntries(if (query.isBlank()) emptyQueryEntries(sources) else searchEntries(sources, query))

/**
 * The rows the native palette renders for a **blank** query.
 *
 * Upstream keeps a blank query to the preferred rows and leaves the rest of the
 * registry reachable only by typing (`dialog.tsx:32-35` — a desktop always has a
 * keyboard). On the phone that cap is a dead end: the rows are shorter than the
 * palette shell's 280dp minimum, so the results area has nothing to scroll and
 * every command below the cap reads as missing
 * (`research/v30_verify/tB_ui_spot/tB_palette_scroll.png`).
 *
 * The browse list keeps upstream's rows pinned at the top — its head is
 * [emptyQueryEntries] verbatim — and appends the rest of the catalog after
 * them, so the whole registry is reachable by scrolling. The builtin rows
 * ([BuiltinCommands]) ride at the end of that tail instead of displacing the
 * preferred rows.
 */
fun browseEntries(sources: PaletteSources): List<PaletteEntry> {
    val head = emptyQueryEntries(sources)
    val tail = if (sources.includeFiles) sources.commands + sources.recentFiles else sources.commands
    return uniqueEntries(head + tail)
}

/**
 * The row source the Compose palette view renders: a blank query browses the
 * whole catalog ([browseEntries]), anything else keeps upstream's search
 * semantics ([visibleEntries]).
 */
fun paletteRows(sources: PaletteSources, query: String): List<PaletteEntry> =
    if (query.isBlank()) browseEntries(sources) else visibleEntries(sources, query)

/**
 * Lazy-list index of every entry row, keyed by entry id.
 *
 * The palette's `LazyColumn` emits one group-title item per group before that
 * group's rows, while [flattenRows] counts entries only — so a flattened index
 * addresses the wrong slot as soon as a title precedes it.
 */
fun lazyRowIndices(grouped: List<Pair<String, List<PaletteEntry>>>): Map<String, Int> {
    val indices = LinkedHashMap<String, Int>()
    var index = 0
    for ((_, entries) in grouped) {
        index++ // the group's title item
        for (entry in entries) indices[entry.id] = index++
    }
    return indices
}

/** `.command-palette-v2-results` → `gap: 16px`, the space *between* two groups. */
const val PALETTE_GROUP_SPACING: Float = 16f

/** `.command-palette-v2-group` → `gap: 1px`, the space between two rows of one group. */
const val PALETTE_ROW_SPACING: Float = 1f

/**
 * The vertical space above every slot of the results list, in dp.
 *
 * Upstream spaces that list in two dimensions — `.command-palette-v2-results
 * { gap:16px }` *between* groups and `.command-palette-v2-group { gap:1px }`
 * *inside* one (§6.5) — while the native list carried a single
 * `Arrangement.spacedBy(16.dp)` for both, so two rows of the same group read as
 * far apart as two different groups. A `LazyColumn`'s arrangement is uniform, so
 * the gap is applied per item ([CommandPalette]) and this computes it.
 *
 * One entry per lazy slot, in emission order: a group title contributes no gap
 * of its own when it opens the list, every later title keeps the 16px between
 * groups, and every row keeps the 1px inside its group. The title's own
 * `margin: 6px 0` stays on the composable. The indices line up with
 * [lazyRowIndices], which maps the rows of the same [grouped] list.
 */
fun paletteSlotSpacings(grouped: List<Pair<String, List<PaletteEntry>>>): List<Float> =
    buildList {
        grouped.forEachIndexed { groupIndex, (_, entries) ->
            add(if (groupIndex == 0) 0f else PALETTE_GROUP_SPACING)
            repeat(entries.size) { add(PALETTE_ROW_SPACING) }
        }
    }

/**
 * `.command-palette-v2-row { scroll-margin: 6px 0 }` — the row's vertical
 * breathing room inside the scroller, in dp (1 CSS px ≈ 1 dp).
 */
const val PALETTE_ROW_SCROLL_MARGIN: Float = 6f

/**
 * The follow-scroll nudge that reproduces that margin in the native list.
 *
 * Upstream's follow (`dialog.tsx:93-95`) is `scrollIntoView({ block:
 * 'nearest' })` on the row, whose 6px scroll-margin keeps it 6px inside the
 * viewport edge. The native follow snaps the slot to the edge with
 * `scrollToItem(slot)` and then nudges it back by exactly the margin
 * (`scrollBy`, negative = backward), so the active row rests 6px inside like
 * upstream.
 *
 * Pure px math so it stays JVM-testable: the Compose layer passes
 * `LocalDensity.density` for [density]. Both inputs clamp at zero — a zero
 * margin or density means "no nudge", never a forward jump.
 */
fun paletteRowScrollNudgePx(density: Float, marginDp: Float = PALETTE_ROW_SCROLL_MARGIN): Float =
    -(marginDp.coerceAtLeast(0f) * density.coerceAtLeast(0f))

/**
 * Trailing gap the row keeps between its last glyph and the card's clip edge.
 *
 * v43 (`research/v43_verify/tE/v43_42_palette.png`) reported the trailing hint
 * column — the `Ctrl+…` chord, and the meta text beside it — as being cut by
 * the card's right edge: the `Ctrl+`` chord read as a half backtick and
 * `Ctrl+,` lost the tail of its comma. The row already has 12 dp of horizontal
 * padding and the list 6 dp, but neither is a *contract*: the hint `Text` had
 * no width cap, so a long chord (or a user-registered override) measured at
 * its intrinsic width against the row's full constraint and the label column
 * absorbed the difference until the last glyph sat on the card's own rounded
 * edge. The hint now takes a capped box ([paletteHintMaxWidth]) and every row
 * keeps [PALETTE_ROW_EDGE_INSET] of clearance past it.
 */
const val PALETTE_ROW_EDGE_INSET: Float = 4f

/** Share of a row the trailing hint column may occupy before the label wins. */
const val PALETTE_HINT_MAX_SHARE: Float = 0.34f

/** Absolute ceiling for the hint column, so a wide window does not float it. */
const val PALETTE_HINT_MAX_WIDTH: Float = 200f

/** Label width a row always keeps, so the hint can never starve it. */
const val PALETTE_LABEL_MIN_WIDTH: Float = 96f

/**
 * `.command-palette-v2-row { padding: 0 12px }` (§6.5) — per side. The stacked
 * layout below `@media (max-width: 640px)` keeps the same horizontal padding.
 */
const val PALETTE_ROW_PADDING: Float = 12f

/**
 * Width budget for a row's trailing hint (keybind / meta), in dp.
 *
 * The chord is a *hint*: it must never be able to squeeze the row's label out
 * or push itself past the card. So it gets the smaller of a share of the row
 * and an absolute cap, and in every case what is left after reserving
 * [PALETTE_LABEL_MIN_WIDTH] for the label plus the two edge insets — a row too
 * narrow for both gives the budget to the label and the hint ellipsises.
 * Non-positive [rowWidthDp] yields `0f`, never a negative budget.
 */
fun paletteHintMaxWidth(rowWidthDp: Float): Float {
    if (rowWidthDp <= 0f) return 0f
    val inner = rowWidthDp - 2f * PALETTE_ROW_EDGE_INSET
    return minOf(
        inner * PALETTE_HINT_MAX_SHARE,
        PALETTE_HINT_MAX_WIDTH,
        (inner - PALETTE_LABEL_MIN_WIDTH).coerceAtLeast(0f),
    ).coerceAtLeast(0f)
}

/**
 * `move(delta)` over the flattened row order: wraps around and returns 0 for an
 * empty list (§6.6 — "首尾循环").
 */
fun moveIndex(current: Int, delta: Int, count: Int): Int {
    if (count == 0) return 0
    return ((current + delta) % count + count) % count
}

/** Flattened render order used for keyboard navigation and `data-active`. */
fun flattenRows(grouped: List<Pair<String, List<PaletteEntry>>>): List<PaletteEntry> =
    grouped.flatMap { it.second }

/** `palette.search.placeholder` / `palette.search.placeholder.home` (§6.2). */
enum class PaletteHostKind {
    /** `DialogHomeCommandPaletteV2`. */
    HOME,

    /** `DialogCommandPaletteV2` (session + new-session, same component). */
    SESSION,

    /**
     * `file.open` (`use-session-commands.tsx:163-168` + `palette.ts:96-99`):
     * the same session palette with `filesOnly()` — `allowedCommands` is empty,
     * so only the Files group survives.
     */
    FILES_ONLY,
}

fun PaletteHostKind.placeholderResId(): Int =
    when (this) {
        PaletteHostKind.HOME -> R.string.oc_palette_placeholder_home
        PaletteHostKind.FILES_ONLY -> R.string.oc_palette_placeholder_files
        PaletteHostKind.SESSION -> R.string.oc_palette_placeholder
    }

/**
 * Localized title for a row [category]: `palette.group.*` for the three labels
 * §6.2 names, `command.category.*` for the groups the session registry declares
 * (upstream registers those as localized strings — `command.category.view` …
 * `command.category.workspace`). `null` when the shell does not know the
 * category; the raw literal is then the only label available.
 *
 * The browse list (`browseEntries`) surfaces every group at once, so a category
 * without a label would read as English inside a translated UI.
 */
fun paletteGroupLabelResId(category: String): Int? = when (category) {
    CATEGORY_COMMANDS -> R.string.oc_palette_group_commands
    CATEGORY_FILES -> R.string.oc_palette_group_files
    CATEGORY_SESSION -> R.string.oc_palette_group_session
    CATEGORY_MCP -> R.string.command_category_mcp
    CATEGORY_PERMISSIONS -> R.string.command_category_permissions
    CATEGORY_VIEW -> R.string.command_category_view
    CATEGORY_TERMINAL -> R.string.command_category_terminal
    CATEGORY_FILE -> R.string.command_category_file
    CATEGORY_CONTEXT -> R.string.command_category_context
    CATEGORY_PROJECT -> R.string.command_category_project
    CATEGORY_WORKSPACE -> R.string.command_category_workspace
    CATEGORY_MODEL -> R.string.command_category_model
    else -> null
}

/**
 * A host's data assembly. Home omits the whole `file` category; session and
 * new-session add `file.searchFiles` results and the recent (opened) files.
 * [FILES_ONLY] drops every command and session row (`filesOnly()`).
 */
fun PaletteHostKind.sources(
    commands: List<PaletteEntry>,
    sessions: List<PaletteEntry> = emptyList(),
    files: List<PaletteEntry> = emptyList(),
    recentFiles: List<PaletteEntry> = emptyList(),
): PaletteSources = when (this) {
    PaletteHostKind.HOME -> PaletteSources(commands = commands, sessions = sessions, includeFiles = false)
    PaletteHostKind.SESSION -> PaletteSources(
        commands = commands,
        sessions = sessions,
        files = files,
        recentFiles = recentFiles,
        includeFiles = true,
    )

    PaletteHostKind.FILES_ONLY -> PaletteSources(
        commands = emptyList(),
        sessions = emptyList(),
        files = files,
        recentFiles = recentFiles,
        includeFiles = true,
    )
}

/** `getDirectory(path)` / `getFilename(path)` for the `file` row (§6.4). */
fun paletteSplitPath(path: String): Pair<String, String> {
    val normalized = path.replace('\\', '/')
    val slash = normalized.lastIndexOf('/')
    return if (slash < 0) "" to normalized else normalized.substring(0, slash + 1) to normalized.substring(slash + 1)
}

/**
 * `GET /session` rows for the session host (W2-C). The input order is
 * preserved — [com.xiufeigo.opencodeshell.ChatViewModel.listSessions] already
 * sorts `updatedAt` descending, which is the palette's session order. [server]
 * is the `NavRestore` server key baked into the entry id
 * (`` `session:${server}:${sessionID}` ``); [openSessionIds] marks rows with
 * the "already open" bar.
 *
 * WAVE 2.3a (A4 #4): archived rows are dropped (`palette.ts:272`
 * `!session.time.archived` — the native predicate is `archivedAt > 0`, see
 * `HomeSessions.isArchived`) and the list is capped at [SESSION_SEARCH_LIMIT]
 * (v2 `limit: 50`, `palette.ts:152`).
 */
const val SESSION_SEARCH_LIMIT: Int = 50

fun sessionPaletteEntries(
    sessions: List<com.xiufeigo.opencodeshell.OpenCodeClient.Session>,
    server: String,
    openSessionIds: Set<String> = emptySet(),
    /**
     * Row meta pinned to the right edge — upstream `command-palette-meta`
     * (`shell/commands/dialog.tsx:267-271` renders `getRelativeTime(item.updated)`).
     * A lambda keeps the localized bucket out of this Compose-free file; `null`
     * omits the meta cell.
     */
    meta: ((com.xiufeigo.opencodeshell.OpenCodeClient.Session) -> String?)? = null,
): List<PaletteEntry> = sessions
    .filter { (it.archivedAt ?: 0L) <= 0L }
    .take(SESSION_SEARCH_LIMIT)
    .map { session ->
        PaletteEntry.session(
            server = server,
            sessionID = session.id,
            title = session.title,
            description = session.directory,
            meta = meta?.invoke(session),
            open = session.id in openSessionIds,
            archived = (session.archivedAt ?: 0L) > 0L,
        )
    }

/**
 * `session.previous` / `session.next` (`desktop-menu.ts:245-266`): the session
 * [delta] rows away from [currentId] in the palette's own `updatedAt`-descending
 * order.
 *
 * Wraps around at both ends (the phone has no menu bar to grey the entry out at
 * the end of the list) and returns `null` when there is nothing to move to —
 * either an empty list or a single session. A [currentId] that is not in the
 * list (Home, or a session outside the loaded page) starts from the top.
 */
fun steppedSessionId(
    sessions: List<com.xiufeigo.opencodeshell.OpenCodeClient.Session>,
    currentId: String?,
    delta: Int,
): String? {
    if (sessions.isEmpty() || delta == 0) return null
    val current = sessions.indexOfFirst { it.id == currentId }
    if (current < 0) return if (delta > 0) sessions.first().id else sessions.last().id
    val target = moveIndex(current, delta, sessions.size)
    return sessions[target].id.takeIf { it != currentId }
}

/**
 * `/find/file` paths for the session host (W2-C): the server's relevance
 * order is preserved and blank entries are dropped (they would render an
 * empty row).
 *
 * WAVE 2.3a (A4 #6): capped at [ENTRY_LIMIT] — upstream slices the root-file
 * list to 5 (`palette.ts:119-144`) and the async search is bounded by the
 * same budget on the client.
 */
fun filePaletteEntries(paths: List<String>): List<PaletteEntry> =
    paths.filter { it.isNotBlank() }.take(ENTRY_LIMIT).map { PaletteEntry.file(it) }

/**
 * JVM-testable keyboard contract for the palette's `handleKeyDown` + the global
 * `Ctrl+K / Ctrl+Shift+P` hook (`command.tsx` captures at document level).
 *
 * Modelled as a small enum so the Compose layer only maps `KeyEvent` ⇒ this.
 */
enum class PaletteKey { ARROW_DOWN, ARROW_UP, ENTER, ESCAPE }

/**
 * Global "open the palette" chord: `mod+k` or `mod+shift+p`.
 *
 * Upstream `matchKeybind` (`shell/commands/command.tsx:188-204`) requires every
 * modifier to match *exactly*, so `mod+shift+k` does **not** open the palette and
 * `mod+p` (without Shift) is `file.open` instead.
 */
fun isPaletteChord(ctrlOrMeta: Boolean, keyK: Boolean, keyP: Boolean, shift: Boolean): Boolean = when {
    !ctrlOrMeta -> false
    keyK -> !shift
    keyP -> shift
    else -> false
}

/** Settings opens with `mod+comma` (§5.0). */
fun isSettingsChord(ctrlOrMeta: Boolean, keyComma: Boolean): Boolean = ctrlOrMeta && keyComma

/**
 * A registered command. The web registry is a list of
 * `{ id, title, description?, keybind?, category, onSelect }` records pushed by
 * whichever route is mounted (`research/05` §1.4); [id] is the stable identity
 * used by [COMMON_COMMAND_IDS] and by the banner-row lookup.
 */
class PaletteCommand(
    val id: String,
    val titleResId: Int,
    val category: String = CATEGORY_COMMANDS,
    val descriptionResId: Int? = null,
    val keybind: String? = null,
    /** `hidden: true` commands are registered but never listed. */
    val hidden: Boolean = false,
    val onSelect: () -> Unit,
) {
    /** `"command:" + id`. */
    val entryId: String get() = "command:$id"

    /**
     * The same registration with a different [onSelect] — the row stays listed
     * (and searchable) while its action is replaced, which is how a command whose
     * screen is not on screen is kept in the palette without acting on a session
     * the user is not looking at ([sessionCommands]' `enabled`).
     */
    fun withSelect(onSelect: () -> Unit): PaletteCommand =
        PaletteCommand(id, titleResId, category, descriptionResId, keybind, hidden, onSelect)
}

/**
 * Projects a registered command into a list row. The title stays unresolved
 * (`""`) because mapping [PaletteCommand.titleResId] needs a resource table: the
 * Compose layer fills it through [commandEntries].
 */
fun PaletteCommand.toEntry() = PaletteEntry(
    id = entryId,
    type = PaletteEntryType.COMMAND,
    title = "",
    description = null,
    keybind = keybind,
    category = category,
)

/**
 * Registered commands reduced to rows. [resolveTitle] lets the Compose layer
 * pass `stringResource` output while this stays JVM-testable; [resolveEnglish]
 * is the same projection against the `values/` table, which is what makes a
 * translated row still answer to its upstream English name
 * ([commandEnglishTerms]). It sits before [resolveTitle] so the existing
 * trailing-lambda call sites keep binding to the title.
 */
fun commandEntries(
    commands: List<PaletteCommand>,
    resolveEnglish: (Int) -> String? = { null },
    resolveTitle: (Int) -> String,
): List<PaletteEntry> =
    commands.filterNot { it.hidden }
        .map { command ->
            val title = resolveTitle(command.titleResId)
            command.toEntry().copy(
                title = title,
                description = ocPaletteRowDescription(title, command.descriptionResId?.let(resolveTitle)),
                searchTerms = commandEnglishTerms(command, resolveEnglish),
            )
        }

/**
 * The description line of a palette row, or `null` when there is nothing to add
 * under the title.
 *
 * Several upstream commands ship a description that is a verbatim copy of their
 * title (`command.mcp.toggle` and `command.mcp.toggle.description` are both
 * "Toggle MCPs", and both translations repeat it too), so the row rendered the
 * same words twice — a two-line "Toggle MCPs / Toggle MCPs"
 * (`research/v44_verify/tE/tE_09_mcpsearch.png`). A description that only
 * restates the title carries no information, so it is dropped rather than
 * repeated; one that adds something still renders.
 */
internal fun ocPaletteRowDescription(title: String, description: String?): String? {
    val trimmed = description?.trim()
    if (trimmed.isNullOrEmpty()) return null
    return if (trimmed == title.trim()) null else trimmed
}

/** Looks up the command a row belongs to. */
fun commandFor(commands: List<PaletteCommand>, entry: PaletteEntry): PaletteCommand? =
    commands.firstOrNull { it.entryId == entry.id }
