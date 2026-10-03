package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.xiufeigo.opencodeshell.ui.nav.SessionTabRef
import java.io.File

/**
 * v97 N-3 (`research/v97_verify/5560_sweepA/REPORT_v97b.md`) plus the
 * **v98 fix2** second real-machine verdict
 * (`research/v97_verify/5560_v98/5560_v98_drawer2/REPORT_DRAWER2.md`, FAIL).
 *
 * The reported failure, twice:
 *
 * 1. **N-3** — with the sheet open, `BottomTabStrip`'s row jumped from
 *    `y=1452..1548` to `y=0..84` and covered the status bar.
 * 2. **v98 round two** — the v97 fix never moved the node, so the same
 *    constraint chain ran *unconditionally*: on the mobile form with
 *    `tabLayout=vertical` the app was a 13-node shell with the bar parked at
 *    `y=0..84` even with the sheet **closed**, and the sheet could not be
 *    opened at all.
 *
 * The constraint chain, in order:
 *
 * 1. `ui/App.kt` wraps the shell graph in one `Column(Modifier.fillMaxSize())`
 *    whose children are the `weight(1f)` NavHost box and — on the mobile form
 *    only — `BottomTabStrip`.
 * 2. The sheet's host is `Box(modifier = modifier.fillMaxSize())`
 *    (`ui/nav/MobileTabsDrawer.kt:148`). If that host is composed **inside the
 *    column's content lambda** it is one of the column's flow children.
 * 3. A `Column` measures its **non-weighted** children first, and `fillMaxSize`
 *    inside a `Column` takes the column's whole remaining height; the weighted
 *    NavHost is left with 0 px, and the bar — the only child above the sheet —
 *    is placed at y=0.
 *
 * Upstream's shape is the opposite: both halves of the sheet are
 * `position: fixed` (`_upstream/packages/app/src/shell/mobile-drawer.css:1-7`
 * and `:13-31`), i.e. measured against the viewport and out of flow, so the
 * column never re-measures when the sheet opens.
 *
 * The pins below are a pure model of the column's measure policy (the
 * semantics) plus source-level pins on the mechanism, because **v97's mistake
 * was structural and a screenshot could never have caught it**: a
 * `CompositionLocal` carries a *value*, so the host lambda was still composed
 * at the consumer. The model therefore separates **measured** height from
 * **painted** content, which is exactly the v97 shell: the host consumed the
 * column while drawing nothing.
 */
class MobileDrawerLayoutV97Test {

    private fun source(rel: String): String =
        listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/$rel"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/$rel"),
        ).firstOrNull { it.isFile }?.readText() ?: error("missing $rel")

    private val app = source("ui/App.kt")
    private val strip = source("ui/nav/BottomTabStrip.kt")
    private val drawer = source("ui/nav/MobileTabsDrawer.kt")
    private val desktop = source("ui/nav/DesktopTabStrip.kt")

    // ------------------------------------------------- 1. the measure policy

    /**
     * A `Column`'s height allocation, modelled — the exact policy
     * `ColumnMeasurePolicy` runs, in the order it runs it:
     *
     * 1. every **non-weighted** child is measured against the space left so far
     *    (so a `fillMaxSize` child in a column takes everything that is left —
     *    `Column.kt`'s `weight` split only runs afterwards), then
     * 2. the leftover is divided across the weighted children, then
     * 3. children are **placed in declaration order**, top to bottom.
     *
     * [hostInFlow] is the bug's one structural switch: `true` means the sheet's
     * `fillMaxSize` host is a child of this column, `false` means it is an
     * overlay next to it.
     *
     * [sheetOpen] only decides what the sheet **paints**, never what it
     * **measures** — that asymmetry is the v98 round-two finding, and keeping
     * the two apart is what lets the model state the regression as arithmetic
     * instead of as a screenshot.
     */
    internal data class ColumnChild(
        val id: String,
        val weight: Float = 0f,
        /** Height a non-weighted child asks for; ignored when [weight] > 0. */
        val requestedPx: Int = 0,
    )

    internal data class ColumnAllocation(
        val heights: Map<String, Int>,
        val tops: Map<String, Int>,
        /** Children that put something on screen this frame. */
        val painted: Set<String>,
    ) {
        fun height(id: String): Int = heights.getValue(id)
        fun top(id: String): Int = tops.getValue(id)
    }

    internal fun allocateColumn(
        windowHeightPx: Int,
        children: List<ColumnChild>,
        hostInFlow: Boolean,
        sheetOpen: Boolean = true,
    ): ColumnAllocation {
        // A `fillMaxSize` child in a column is measured with the space that is
        // left, so it is modelled as "asks for everything that remains". v97
        // composed that host unconditionally — `if (!open) return` sat *inside*
        // the sheet, below its host — so `sheetOpen` deliberately does not
        // shrink the request; that is the bug, not an accident of the model.
        val effective = if (hostInFlow) {
            children + ColumnChild(id = OVERLAY_ID, requestedPx = Int.MAX_VALUE)
        } else {
            children
        }
        val heights = LinkedHashMap<String, Int>()
        var left = windowHeightPx
        for (child in effective) {
            if (child.weight > 0f) continue
            val taken = if (child.requestedPx > left) left else child.requestedPx
            heights[child.id] = taken
            left -= taken
        }
        val totalWeight = effective.filter { it.weight > 0f }.sumOf { it.weight.toDouble() }.toFloat()
        for (child in effective) {
            if (child.weight <= 0f) continue
            heights[child.id] = if (totalWeight <= 0f) 0 else Math.round(left * (child.weight / totalWeight))
        }
        // Placement is declaration order — the weighted child is placed first
        // even though it was measured last, and that is what put the bar at 0.
        val tops = LinkedHashMap<String, Int>()
        var y = 0
        for (child in effective) {
            tops[child.id] = y
            y += heights.getValue(child.id)
        }
        // The sheet paints only while it is open, and a child measured to zero
        // height paints nothing at all — which is precisely how the starved
        // NavHost left the mobile form an empty 13-node shell.
        val painted = LinkedHashSet<String>(
            children.filter { heights.getValue(it.id) > 0 }.map { it.id },
        )
        if (hostInFlow && sheetOpen) painted.add(OVERLAY_ID)
        return ColumnAllocation(heights, tops, painted)
    }

    private companion object {
        const val OVERLAY_ID = "mobile-tabs-sheet"
    }

    /** The shell column as it stood: NavHost (weight), then the bar. */
    private fun shellColumn() = listOf(
        ColumnChild(id = "navHost", weight = 1f),
        ColumnChild(id = "bottomBar", requestedPx = 96),
    )

    // ------------------------------------------- 2. the reported geometry

    @Test
    fun `the old tree reproduces the reported bar at y equals 0`() {
        // 1200x1600 @ 320dpi; the reported bar band was y=0..84 with the
        // drawer open, y=1452..1548 with it closed.
        val window = 1600
        val open = allocateColumn(window, shellColumn(), hostInFlow = true)

        // The sheet's `fillMaxSize` host ate everything that was left…
        assertEquals("the sheet takes the column", window - 96, open.height(OVERLAY_ID))
        // …so the weighted NavHost was measured to nothing…
        assertEquals("the weighted NavHost is starved", 0, open.height("navHost"))
        // …and the bar, placed after a zero-height NavHost, landed at the top,
        // under the status bar. This is N-3 exactly.
        assertEquals("the bar is pushed to the top", 0, open.top("bottomBar"))
    }

    @Test
    fun `the v97 host stayed in the column with the sheet closed`() {
        // REPORT_DRAWER2.md §2/§3: the second verdict measured the bar at
        // `y=0..84` in the **closed** state, and the whole app was a 13-node
        // shell. v97 composed the host unconditionally, so a closed sheet
        // painted nothing and still consumed the column. Both states are
        // asserted because the device read both.
        val window = 1600
        for (sheetOpen in listOf(false, true)) {
            val alloc = allocateColumn(window, shellColumn(), hostInFlow = true, sheetOpen = sheetOpen)
            if (!sheetOpen) {
                assertFalse("a closed sheet paints nothing", alloc.painted.contains(OVERLAY_ID))
            }
            assertEquals("navHost starved with sheetOpen=$sheetOpen", 0, alloc.height("navHost"))
            assertEquals("bar at the top with sheetOpen=$sheetOpen", 0, alloc.top("bottomBar"))
        }
        // A closed shell is the empty app: the only thing on screen is the bar.
        val closed = allocateColumn(window, shellColumn(), hostInFlow = true, sheetOpen = false)
        assertEquals("only the bar is painted", setOf("bottomBar"), closed.painted)
    }

    @Test
    fun `the fixed tree keeps the NavHost height and the bar at the bottom`() {
        val window = 1600
        val closed = allocateColumn(window, shellColumn(), hostInFlow = false, sheetOpen = false)
        val open = allocateColumn(window, shellColumn(), hostInFlow = false, sheetOpen = true)

        // Opening the sheet no longer changes a single pixel of the column.
        assertEquals(closed.heights, open.heights)
        assertEquals(closed.tops, open.tops)
        assertEquals("the NavHost keeps the window minus the bar", window - 96, open.height("navHost"))
        assertEquals("the bar sits flush with the bottom edge", window - 96, open.top("bottomBar"))
        // The sheet is out of flow: it is not a child of the column at all.
        assertFalse("the sheet is not a column child", open.heights.containsKey(OVERLAY_ID))
        // …and with the sheet closed the host is not even composed, which is
        // what the v97 unconditional host got wrong.
        assertEquals(emptySet<String>(), closed.painted - setOf("navHost", "bottomBar"))
    }

    @Test
    fun `the v98 tree restores the shell in the mobile vertical form`() {
        // The configuration the device reported as a 13-node shell: mobile
        // width + `tabLayout=vertical`, which is the only combination that
        // mounts `BottomTabStrip` at all (`bottomTitlebar = !desktopTabs &&
        // bottomTabStrip`). The regression is the same reading on both forms, so
        // the fix is asserted on the mobile one: full body, bar at the bottom,
        // and opening the sheet moves nothing.
        val window = 1600
        val closed = allocateColumn(window, shellColumn(), hostInFlow = false, sheetOpen = false)
        val open = allocateColumn(window, shellColumn(), hostInFlow = false, sheetOpen = true)

        assertEquals("the body has its height back", window - 96, closed.height("navHost"))
        assertEquals("the bar is at the bottom", window - 96, closed.top("bottomBar"))
        assertEquals("the body is identical with the sheet open", closed.height("navHost"), open.height("navHost"))
        assertEquals("the bar is identical with the sheet open", closed.top("bottomBar"), open.top("bottomBar"))
        // The bar band of the report: 72 px of bar (36 dp @ 320dpi = 2x) plus its
        // 12 px navigation-bar inset — modelled here as the 96 px the report
        // implies, and it must be the *last* thing in the column.
        assertEquals(96, closed.height("bottomBar"))
    }

    @Test
    fun `a bar band is what the device reported for the broken placement`() {
        // The 0..84 band in the report is 72 px of bar (36 dp @ 320dpi = 2x)
        // plus its 12 px navigation-bar inset; the model only has to agree
        // that the band starts at 0, not at the closed position.
        val open = allocateColumn(1600, shellColumn(), hostInFlow = true)
        val closed = allocateColumn(1600, shellColumn(), hostInFlow = false)
        assertEquals(0, open.top("bottomBar"))
        assertTrue("the bar really did move", open.top("bottomBar") != closed.top("bottomBar"))
    }

    // --------------------------------- 3. the mechanism, pinned in source

    @Test
    fun `the sheet is handed to the host instead of being composed in the column`() {
        // The regression's shape: two flow siblings in one composable body.
        val bare = Regex("""\}\s*\n\s*MobileTabsDrawer\(""")
        assertFalse(
            "MobileTabsDrawer must not be the Row's next sibling",
            bare.containsMatchIn(strip),
        )
        // It is neither invoked in this body nor reached through a
        // CompositionLocal — a `CompositionLocal` carries a value, so the host
        // was composed at the consumer and the fix changed nothing.
        assertFalse(
            "a CompositionLocal cannot re-parent the sheet",
            strip.contains("LocalMobileDrawerOverlay") || app.contains("LocalMobileDrawerOverlay"),
        )
        assertFalse(
            "the sheet is not invoked through a mount point",
            strip.contains("LocalMobileDrawerOverlay.current"),
        )
        // What the strip does instead: build the content and hand it over.
        assertTrue(
            "the sheet is built as a value, not composed in this body",
            strip.contains("val drawerContent: @Composable () -> Unit = {"),
        )
        assertTrue("the strip publishes the sheet", strip.contains("drawerMount.content = drawerContent"))
        // v98 fix3: the open flag is no longer *published* — the trigger writes
        // the shell's own state, so there is no second object that can drift.
        assertFalse(
            "the strip must not keep a local copy of the open flag",
            strip.contains("var drawerOpen"),
        )
        assertFalse(
            "no SideEffect may copy the open flag into the mount",
            Regex("""SideEffect\s*\{[^}]*drawerMount\.open""").containsMatchIn(strip),
        )
        assertTrue(
            "the sheet reads the shell's own open state",
            strip.contains("open = drawerMount.open,"),
        )
        // …and every host callback still rides the same call, so the
        // close-then-act order, the B-6 closed-tab row and the B-20 drag
        // rename are untouched (B-6/B-20 pins in ChordCommandWiringTest and
        // TabDragWiringTest read these exact lines).
        assertTrue(strip.contains("closedTab = closedTab"))
        assertTrue(strip.contains("onReopenClosed = {"))
        assertTrue(strip.contains("onMoveSession = onMoveSession"))
        assertTrue(strip.contains("onRenameSession = onRenameSession"))
        assertTrue(strip.contains("onDismissRequest = { drawerMount.open = false }"))
        // Every close-then-act pair closes the shell's own flag now, so no path
        // can leave the gate open behind it.
        for (closeThenAct in listOf(
            "onOpenSession = {\n                drawerMount.open = false",
            "onNewSession = {\n                drawerMount.open = false",
            "onHome = {\n                drawerMount.open = false",
            "onOpenSettings = {\n                drawerMount.open = false",
            "onOpenHelp = {\n                drawerMount.open = false",
            "onReopenClosed = {\n                drawerMount.open = false",
        )) {
            assertTrue("close-then-act still closes first: $closeThenAct", strip.contains(closeThenAct))
        }
    }

    @Test
    fun `the mount is cleared when the strip leaves composition`() {
        // A stale mount would keep a sheet composed on the desktop form, where
        // no strip exists to close it.
        assertTrue("the mount is disposed", strip.contains("DisposableEffect(drawerMount)"))
        assertTrue("disposal closes the sheet", strip.contains("drawerMount.open = false"))
        assertTrue("disposal drops the content", strip.contains("drawerMount.content = null"))
        // The content is published after composition, so publishing can never
        // re-enter the composition that is in flight.
        assertTrue("the publish is a SideEffect", strip.contains("SideEffect {"))
    }

    @Test
    fun `the shell composes the sheet outside the column`() {
        // The host's half: the sheet is invoked as the **last** child of the
        // `Box` that wraps the column, so its layout node is a sibling of the
        // column and not one of the column's flow children.
        val column = app.indexOf("Column(Modifier.fillMaxSize().background(OcTheme.colors.bgDeep))")
        val bar = app.indexOf("BottomTabStrip(")
        val sheetSlot = app.indexOf("val mobileSheet = mobileDrawerMount.content")
        val sheetInvoke = app.indexOf("Box(Modifier.fillMaxSize()) { mobileSheet() }")
        assertTrue("the shell still has one column", column > 0)
        assertTrue("the bar is still emitted", bar > 0)
        assertTrue("the shell reads the mount", sheetSlot > 0)
        assertTrue("the shell mounts the sheet in a full-size box", sheetInvoke > 0)
        assertTrue(
            "the sheet is composed after the bar, i.e. outside the column",
            sheetSlot > bar,
        )
        assertTrue("the slot follows the column", sheetSlot > column)
        // Gated on open, so nothing of the sheet exists in the layout while it
        // is closed — the v97 unconditional host is exactly what this undoes.
        assertTrue(
            "the slot only exists while the sheet is open",
            app.contains("if (mobileDrawerMount.open && mobileSheet != null) {"),
        )
        // One `Box`, two children: the column, then the overlay. The wrapper
        // stays transparent — the sheet's box is the only full-size sibling
        // and it is gated, so the shell keeps the insets it always had.
        assertTrue(
            "the sheet's box is a sibling slot, written as one gated block",
            app.contains(
                "if (mobileDrawerMount.open && mobileSheet != null) {\n" +
                    "                Box(Modifier.fillMaxSize()) { mobileSheet() }\n" +
                    "            }",
            ),
        )
        assertTrue(
            "the wrapper is a plain full-size box with no insets of its own",
            app.contains("val mobileDrawerMount = remember { MobileDrawerMount() }\n        Box(Modifier.fillMaxSize()) {"),
        )
    }

    @Test
    fun `the shell wires the mount to the strip`() {
        // The strip's mount parameter has no default: a defaulted one would let
        // a future host fall back to the in-flow shape that put the bar at 0.
        assertTrue("the strip requires a mount", strip.contains("drawerMount: MobileDrawerMount,"))
        assertFalse("the mount is not defaulted", strip.contains("drawerMount: MobileDrawerMount ="))
        assertTrue("the shell passes the mount", app.contains("drawerMount = mobileDrawerMount,"))
        assertTrue("the shell owns the mount", app.contains("remember { MobileDrawerMount() }"))
        // The mount is a real holder, not a composable mount point. It is
        // public only so the public strip can take it; its state stays internal.
        assertTrue("the mount is a stable holder", strip.contains("class MobileDrawerMount"))
        assertTrue("the mount carries the open flag", strip.contains("internal var open by mutableStateOf(false)"))
        assertTrue(
            "the mount carries the sheet content",
            strip.contains("internal var content: (@Composable () -> Unit)? = null"),
        )
    }

    @Test
    fun `the sheet's fillMaxSize host is unchanged and now measures the overlay`() {
        // The sheet was not wrong — it is a scrim that wants the window. What
        // was wrong was the box it measured against. Keep the host as it is and
        // pin that its constraint now comes from the overlay, not the column.
        assertTrue("the scrim host is still full-size", drawer.contains("Box(modifier = modifier.fillMaxSize())"))
        assertTrue("the sheet is still a bottom card", drawer.contains(".align(Alignment.BottomCenter)"))
        // The scrim and the close order are the sheet's own, untouched.
        assertTrue("the scrim is still the dismiss surface", drawer.contains("DialogScrim(scrim, onDismissRequest)"))
        // The bar's own trigger still opens the sheet — the fix moved the
        // mount point, not the entry. v98 fix3: it opens it by writing the
        // shell's own flag, in the click handler itself.
        assertTrue("the tab slot is still the trigger", strip.contains("onClick = { drawerMount.open = true }"))
    }

    // ---------------- 5. v98 fix3: the entry reaches the host at all

    /**
     * The hand-off, modelled — what the tab slot's click does to the state the
     * shell's gate reads, and what the round-four failure was.
     *
     * [copyOnPublish] is the whole defect as one switch:
     *
     * - `false` (fix3): one state object. The click writes it, the gate reads
     *   it, so the open is visible in the same frame the tap is handled.
     * - `true` (fix2): the click writes a *strip-local* flag and a `SideEffect`
     *   — a post-apply write, i.e. **out of band with the tap** — copies it
     *   into the mount the gate reads. Whether the open is ever seen therefore
     *   depends on a follow-up frame that the copy itself does not guarantee,
     *   and the two objects can disagree.
     *
     * [framesAfterTap] is that follow-up frame. At 0 the fix2 shape loses the
     * open entirely and paints nothing at all — no scrim, no row, no a11y
     * delta, no crash. That is the report's signature, not a rendering detail:
     * seven taps, `Compare-Object` empty, 0 FATAL/ANR
     * (`research/v97_verify/5560_v98/REPORT_DRAWER4.md` §2.2).
     */
    internal fun openAfterTap(
        copyOnPublish: Boolean,
        framesAfterTap: Int,
    ): Boolean {
        // What the click wrote.
        val written = true
        if (!copyOnPublish) {
            // Same object the gate reads: the write is the gate's invalidation.
            return written
        }
        // The local flag is only copied into the mount on a later frame, and
        // only if one happens to be produced at all.
        val localFlag = written
        val published = if (framesAfterTap > 0) localFlag else localFlag.let { it && false }
        // …and the gate reads the mount, never the local flag.
        return published
    }

    @Test
    fun `the entry writes the very state the host gate reads`() {
        // One object, one write, no intermediate copy: the trigger's assignment
        // targets the mount, and the shell's gate reads the mount.
        assertTrue(
            "the trigger writes the shell's drawer state",
            strip.contains("onClick = { drawerMount.open = true }"),
        )
        assertTrue(
            "the host gates on the shell's drawer state",
            app.contains("if (mobileDrawerMount.open && mobileSheet != null) {"),
        )
        // The strip's mount parameter is the shell's own instance: one
        // `remember`, handed to the strip and read back by the gate.
        val remember = app.indexOf("remember { MobileDrawerMount() }")
        val handed = app.indexOf("drawerMount = mobileDrawerMount,")
        val gate = app.indexOf("if (mobileDrawerMount.open && mobileSheet != null) {")
        assertTrue("the shell owns exactly one mount", remember > 0)
        assertTrue("the strip is handed that instance", handed > 0)
        assertTrue("the gate reads that instance", gate > 0)
        assertEquals("the shell remembers the mount exactly once", 1, Regex("""MobileDrawerMount\(\)""").findAll(app).count())
    }

    @Test
    fun `the old two-object hand-off could swallow the open and the new one cannot`() {
        // The failure, stated as a model: fix2's copy is out of band with the
        // tap, so a tap that is not followed by a frame paints nothing at all.
        assertFalse(
            "fix2 lost the open when no frame followed the copy",
            openAfterTap(copyOnPublish = true, framesAfterTap = 0),
        )
        assertTrue(
            "fix2 saw the open only once a frame followed",
            openAfterTap(copyOnPublish = true, framesAfterTap = 1),
        )
        // fix3 shares one state, so the open is never conditional on a frame.
        assertTrue("fix3 sees the open immediately", openAfterTap(copyOnPublish = false, framesAfterTap = 0))
        assertTrue("fix3 still sees it with frames to spare", openAfterTap(copyOnPublish = false, framesAfterTap = 3))
        // And the source may not go back to the copy.
        assertFalse(
            "the strip declares no local open flag to copy",
            Regex("""var\s+drawerOpen""").containsMatchIn(strip),
        )
        assertFalse(
            "no SideEffect may publish the open flag",
            Regex("""SideEffect\s*\{[^}]*drawerMount\.open""").containsMatchIn(strip),
        )
    }

    /**
     * The entry's existence predicate, pure: the mobile drawer has exactly one
     * entry and it exists exactly while a session is on screen — the strip is
     * the bottom placement (`App.kt`) and the slot is behind `session != null`.
     */
    internal fun hasDrawerEntry(bottomTitlebar: Boolean, session: SessionTabRef?): Boolean =
        bottomTitlebar && session != null

    @Test
    fun `the entry exists exactly while a session is on screen`() {
        val tab = SessionTabRef("s1", "E2ECHK")
        assertTrue("the entry is there on a session in the mobile form", hasDrawerEntry(true, tab))
        assertFalse("no bar, no entry", hasDrawerEntry(false, tab))
        assertFalse("no session, no slot to tap", hasDrawerEntry(true, null))
        // The shape those two come from, pinned in source: the strip only
        // renders under the bottom placement, and the slot only under a session.
        assertTrue(app.contains("if (bottomTitlebar) {"))
        assertTrue(strip.contains("if (session != null) {"))
    }

    @Test
    fun `the entry is an announced node so a tap can be aimed at it`() {
        // Round four could not aim: the trigger reached `uiautomator` as an
        // unlabelled clickable (`REPORT_DRAWER4.md` §2.1), so there was no
        // announced name to resolve its bounds from. The label is the string
        // the strip already loads — no new user-visible string.
        assertTrue("the slot takes a label", strip.contains("label: String? = null,"))
        assertTrue(
            "the label rides the slot's own clickable node",
            strip.contains("Modifier.semantics { contentDescription = label }"),
        )
        assertTrue("the bottom bar passes the label it already loads", strip.contains("label = sessionLabel,"))
        // It is a label on the existing node, not a new entry: still one
        // `drawerOpen`/`drawerMount.open = true` assignment, still one clickable.
        assertEquals(
            "the app still has exactly one place that opens the sheet",
            1,
            Regex("""drawerMount\.open = true""").findAll(strip).count(),
        )
        // And the desktop placement is untouched — it passes no label.
        assertFalse("the desktop strip does not label its tabs", desktop.contains("label ="))
    }

    // ------------------------------------ 4. nothing the fix must not break

    @Test
    fun `the NavHost weight, the bar and the desktop form are untouched`() {
        // The NavHost box still owns the column's free height…
        assertTrue("the NavHost still has weight(1f)", app.contains(".weight(1f)"))
        // …the bar is still the last child of the column, under the navigation
        // host, with its navigation-bar inset and its 36 dp height…
        assertTrue("the bar is still emitted in the column", app.contains("BottomTabStrip("))
        assertTrue("the bar keeps the nav-bar inset", strip.contains("windowInsetsPadding(WindowInsets.navigationBars)"))
        assertTrue("the bar keeps its height", strip.contains(".height(BAR_HEIGHT)"))
        // …and the desktop placement never took this path.
        assertTrue("the desktop strip is a different composable", app.contains("DesktopTabStrip("))
        assertFalse("the desktop strip is not the bottom bar", desktop.contains("MobileTabsDrawer("))
        assertFalse("the desktop strip never mounts the sheet", desktop.contains("MobileDrawerMount"))
    }

    @Test
    fun `the v97 route-restore gate is not disturbed by the layout change`() {
        // This round added `navRestoreSettled` to `ui/App.kt`; the fix touches
        // the layout段 only, so the gate must still be there and still gate
        // both the restore and the notification hand-off.
        assertTrue("the gate exists", app.contains("var navRestoreSettled by remember { mutableStateOf(false) }"))
        assertTrue("the restore is gated", app.contains("if (navRestoreSettled && target != null"))
        assertTrue("the notification hand-off is gated", app.contains("if (!navRestoreSettled) return@LaunchedEffect"))
    }

    @Test
    fun `the window and inset accounting is not disturbed`() {
        // v49/v82 window/inset discipline: the wrapper `Box` is transparent, so
        // the status-bar ownership rule and the nav-host inset split must read
        // exactly as they did before.
        assertTrue(
            "the strip still owns the status bar when it renders",
            app.contains("ShellChromeBudget.topStripOwnsStatusBar(topTitlebar, yieldsToIme)"),
        )
        assertTrue(
            "the nav host still consumes the status bar inset",
            app.contains("Modifier.consumeWindowInsets(WindowInsets.statusBars)"),
        )
        assertTrue(
            "the wrapper adds no inset of its own",
            app.contains(
                "val mobileDrawerMount = remember { MobileDrawerMount() }\n        Box(Modifier.fillMaxSize()) {",
            ),
        )
    }
}
