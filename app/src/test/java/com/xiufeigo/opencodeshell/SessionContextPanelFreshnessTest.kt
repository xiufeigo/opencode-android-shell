package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.context.SessionContextLoad
import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel
import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel.Kind
import com.xiufeigo.opencodeshell.ui.session.header.ContextUsage
import java.io.File
import java.time.ZoneId
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v17-23: the `Context usage` panel was **stale** while the header ring was
 * right — one screen, two answers. The ring showed `42 %` of the live total
 * (439,809 + 2 + 489 = 440,300 of a 1,048,576 window) and the panel right beside
 * it still printed `Total Tokens 9,154` / `Usage 1 %`, the numbers of the first
 * turn (25 + 2 + 9,127 = 9,154). The panel's `Refresh` tap changed nothing.
 *
 * Root cause, both halves of it:
 *
 * - **numerator**: the ring reads `GET /api/session/{id}/context` on every
 *   snapshot (`ChatViewModel.activeContextTokens`), while the tab held a
 *   *second*, one-shot read of the same route (`ChatViewModel.sessionContext`,
 *   started once inside the payload effect). The tab's copy froze at its first
 *   open, so the two halves of the same ratio described different turns.
 * - **Refresh**: the button is the review chrome's `oc_review_refresh`, whose
 *   handler (`reprobeReviewVcs` → `ChatViewModel.reviewReprobeLocation`) evicted
 *   the review location and re-probed the diff/branch — none of which touches the
 *   context payloads. With the frame latched by a boolean (`contextLoaded`), the
 *   tap could not re-read anything the tab was showing.
 *
 * The pins below are (1) the same-source rule the fix centralises in
 * [SessionContextModel.lastAssistantTotal], (2) that a reload key really forces
 * the frame read, and (3) that the stale value cannot survive a re-read. Sources
 * are pinned like the other screen-wiring tests in this repo
 * ([SessionContextRingTargetTest], [SessionContextExportStateTest]) because the
 * defect *was* a wiring one.
 */
class SessionContextPanelFreshnessTest {

    private val utc = ZoneId.of("UTC")
    private val en = Locale.US

    /** The device's window: `Context Limit 1,048,576` on both surfaces. */
    private val limit = 1_048_576

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        // CRLF sources would break `\n`-sensitive windows otherwise.
        return file.readText().replace("\r\n", "\n")
    }

    private fun sessionScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private fun viewModel() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    // ------------------------------------------------------------- fixtures

    private fun session(): JSONObject = JSONObject()
        .put("id", "ses_f23aca4b6ffeg95V6JBPrMe4Yd")
        .put("title", "E2ECHK ring test confirmation")
        .put("time", JSONObject().put("created", 1_790_402_845_529L))

    /**
     * One flat v2 row as `GET /api/session/{id}/context` answers it (`Message.raw`
     * — `type`, nested `model`, flat `tokens`), i.e. exactly what
     * `ChatViewModel.activeContextRows` hands the projection.
     */
    private fun assistant(id: String, input: Long, output: Long, cacheRead: Long): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "assistant")
            .put("time", JSONObject().put("created", 1_790_403_077_705L))
            .put(
                "model",
                JSONObject().put("id", "space-bunny-free").put("providerID", "opencode-go"),
            )
            .put(
                "tokens",
                JSONObject()
                    .put("input", input)
                    .put("output", output)
                    .put("reasoning", 0L)
                    .put("cache", JSONObject().put("read", cacheRead).put("write", 0L)),
            )
            .put("content", JSONArray())

    private fun user(id: String): JSONObject = JSONObject()
        .put("id", id)
        .put("type", "user")
        .put("time", JSONObject().put("created", 1_790_403_496_857L))
        .put("content", JSONArray())

    /** The first turn's assistant row: 25 + 2 + 9,127 = **9,154** (the stale value). */
    private fun firstTurn() = assistant("msg_first", input = 25, output = 2, cacheRead = 9_127)

    /** The newest assistant row: 439,809 + 2 + 489 = **440,300** (the ring's 42 %). */
    private fun lastTurn() = assistant("msg_last", input = 439_809, output = 2, cacheRead = 489)

    private val catalog = OpenCodeClient.Providers(
        all = listOf(
            OpenCodeClient.Providers.Provider(
                id = "opencode-go",
                name = "OpenCode Go",
                models = listOf(
                    OpenCodeClient.Providers.Model(
                        id = "space-bunny-free",
                        name = "Space Bunny Free",
                        capabilities = null,
                        limitContext = 1_048_576,
                    ),
                ),
            ),
        ),
        connected = listOf("opencode-go"),
        default = emptyMap(),
    )

    private fun project(rows: List<JSONObject>): SessionContextModel.Snapshot =
        SessionContextModel.project(
            session = session(),
            messages = emptyList(),
            providers = catalog,
            zone = utc,
            locale = en,
            contextLimit = limit,
            activeMessages = rows,
        )

    private fun valueOf(snapshot: SessionContextModel.Snapshot, kind: Kind): String =
        snapshot.stats.first { it.kind == kind }.value

    // ------------------------------------------------------- same source (1)

    @Test
    fun theRingAndTheTabReadTheSameMessageOfTheSameFetch() {
        val rows = listOf(user("msg_u"), firstTurn(), user("msg_u2"), lastTurn())

        // The ring's numerator: `ChatViewModel.activeContextTokens` is this
        // function applied to these rows.
        val ringTotal = SessionContextModel.lastAssistantTotal(rows)
        assertEquals(440_300L, ringTotal)
        assertEquals(42, ContextUsage.percentOf(ringTotal, limit.toLong()))

        // …and the tab's rows, from the very same list.
        val snapshot = project(rows)
        assertEquals("440,300", valueOf(snapshot, Kind.TOTAL_TOKENS))
        assertEquals("42%", valueOf(snapshot, Kind.USAGE))
        assertEquals("439,809", valueOf(snapshot, Kind.INPUT_TOKENS))
        assertEquals("2", valueOf(snapshot, Kind.OUTPUT_TOKENS))
        assertEquals("0", valueOf(snapshot, Kind.REASONING_TOKENS))
        assertEquals("489 / 0", valueOf(snapshot, Kind.CACHE_TOKENS))
        // Provider / model / limit off the same row, through the same catalog the
        // ring's `contextLimit` resolves against — the tab may not answer a
        // different window than the arc it sits next to.
        assertEquals("OpenCode Go", valueOf(snapshot, Kind.PROVIDER))
        assertEquals("Space Bunny Free", valueOf(snapshot, Kind.MODEL))
        assertEquals("1,048,576", valueOf(snapshot, Kind.LIMIT))
        assertEquals(
            limit,
            ChatViewModel.contextLimitFrom(
                providers = catalog,
                transcriptModel = "opencode-go" to "space-bunny-free",
                sessionModel = null,
                selectedModel = null,
            ),
        )
    }

    @Test
    fun theNumeratorIsTheNewestAssistantRowWithUsage() {
        val rows = listOf(firstTurn(), lastTurn(), user("msg_u3"), assistant("msg_no_tokens", 0, 0, 0))
        // A trailing assistant row without usage must not blank the numerator —
        // the same rule the tab's `ctx()` half applies.
        assertEquals(440_300L, SessionContextModel.lastAssistantTotal(rows))
        assertEquals("440,300", valueOf(project(rows), Kind.TOTAL_TOKENS))
        // Nothing to read: the tab dashes and the ring gets `null` (empty track).
        assertNull(SessionContextModel.lastAssistantTotal(emptyList()))
        assertNull(SessionContextModel.lastAssistantTotal(null))
        assertNull(SessionContextModel.lastAssistantTotal(listOf(user("msg_u"))))
        assertNull(ContextUsage.percentOf(SessionContextModel.lastAssistantTotal(emptyList()), limit.toLong()))
    }

    // --------------------------------------------------- no stale value (3)

    @Test
    fun theFrozenFirstTurnIsExactlyWhatTheDeviceShowedAndItDoesNotSurvive() {
        // What the one-shot read held when the tab was first opened.
        val frozen = listOf(firstTurn())
        assertEquals("9,154", valueOf(project(frozen), Kind.TOTAL_TOKENS))
        assertEquals("1%", valueOf(project(frozen), Kind.USAGE))

        // The live read the ring had been using all along.
        val live = listOf(user("msg_u"), firstTurn(), user("msg_u2"), lastTurn())
        val fresh = project(live)
        assertEquals("440,300", valueOf(fresh, Kind.TOTAL_TOKENS))
        assertEquals("42%", valueOf(fresh, Kind.USAGE))

        // The projection is a pure function of the rows it is handed: re-reading
        // cannot leave the previous turn's number behind anywhere in the snapshot.
        val values = fresh.stats.map { it.value }
        assertFalse("the stale total survived the re-read", values.contains("9,154"))
        assertFalse("the stale usage survived the re-read", values.contains("1%"))
        assertEquals(
            42,
            ContextUsage.percentOf(SessionContextModel.lastAssistantTotal(live), limit.toLong()),
        )
    }

    // -------------------------------------------------- reload rule (2 + 3)

    @Test
    fun theReloadKeyIsWhatMakesTheFrameReadAgain() {
        val key = SessionContextLoad.Key(session = "ses_1", serverRevision = 0, reload = 0)

        // First open, nothing loaded yet.
        assertTrue(SessionContextLoad.needsLoad(loaded = null, visible = true, key = key))
        // Loaded for this key: a plain recomposition must not re-drain the frame.
        assertFalse(SessionContextLoad.needsLoad(loaded = key, visible = true, key = key))
        // 「刷新」: the bump is the re-read (v17-23's tap changed nothing).
        assertTrue(SessionContextLoad.needsLoad(loaded = key, visible = true, key = key.copy(reload = 1)))
        // Session and server revision are identities too.
        assertTrue(SessionContextLoad.needsLoad(loaded = key, visible = true, key = key.copy(session = "ses_2")))
        assertTrue(SessionContextLoad.needsLoad(loaded = key, visible = true, key = key.copy(serverRevision = 1)))
        // A settled turn is one as well — the v19 finding's half of the identity
        // (`settleCount` is the rule; here the key it produces).
        assertTrue(SessionContextLoad.needsLoad(loaded = key, visible = true, key = key.copy(settle = 1)))
        // A hidden panel reads nothing, not even on a bump.
        assertFalse(SessionContextLoad.needsLoad(loaded = null, visible = false, key = key))
        assertFalse(SessionContextLoad.needsLoad(loaded = key, visible = false, key = key.copy(reload = 9)))
        assertFalse(SessionContextLoad.needsLoad(loaded = key, visible = false, key = key.copy(settle = 9)))
        // A failed read never latches: `loaded` stays null, so the next attempt
        // reads again instead of showing the empty projection forever.
        assertTrue(SessionContextLoad.needsLoad(loaded = null, visible = true, key = key.copy(reload = 1)))
    }

    // --------------------------------------------------------- wiring pins

    @Test
    fun theTabReadsTheRingsLiveRowsInsteadOfASecondFetch() {
        val screen = sessionScreen()
        // The `ctx()` half subscribes to the ViewModel's live read…
        assertTrue(screen.contains("val contextActive by chatViewModel.activeContextRows.collectAsState()"))
        assertTrue(screen.contains("activeMessages = contextActive"))
        // …and the duplicate one-shot fetch of the same route is gone for good.
        assertFalse(screen.contains("chatViewModel.sessionContext"))
        assertFalse(viewModel().contains("fun sessionContext("))

        val vm = viewModel()
        // One read feeds the ring's total and the tab's rows, through the one
        // rule (`SessionContextModel.lastAssistantTotal`).
        assertTrue(vm.contains("val activeContextRows: StateFlow<List<JSONObject>?>"))
        val read = vm.substringAfter("fun refreshActiveContext()").substringBefore("fun waitSession")
        assertTrue(read.contains("api.context(id).map { it.raw }"))
        assertTrue(read.contains("_activeContextRows.value = rows"))
        assertTrue(read.contains("SessionContextModel.lastAssistantTotal(rows)?.let { _activeContextTokens.value = it }"))
        // The read belongs to the session it was issued for.
        assertTrue(read.contains("if (sessionID != id || rows == null) return@launch"))
        // Both flows are dropped on both session transitions.
        assertEquals(2, Regex("_activeContextRows\\.value = null").findAll(vm).count())
    }

    @Test
    fun theRefreshTapReReadsTheFrameAndTheLiveRows() {
        val screen = sessionScreen()
        // The one evict tail both 「刷新」 and the no-git CTA funnel through.
        val tail = screen.substringAfter("fun reloadAfterEvict(").substringBefore("// Single evict entry point")
        assertTrue("the tap must bump the context load key", tail.contains("contextReloadKey++"))
        assertTrue("the tap must re-fetch the ring's rows", tail.contains("chatViewModel.refreshActiveContext()"))
        // The frame effect keys on that bump…
        assertTrue(
            screen.contains(
                "LaunchedEffect(activeSession.id, serverRevision, contextVisible, contextReloadKey, contextSettleKey)",
            ),
        )
        assertTrue(
            screen.contains(
                "SessionContextLoad.Key(activeSession.id, serverRevision, contextReloadKey, contextSettleKey)",
            ),
        )
        assertTrue(screen.contains("SessionContextLoad.needsLoad(contextLoadedKey, contextVisible, key)"))
        assertTrue(screen.contains("contextLoadedKey = key"))
        // …and closing the panel drops the key, so re-opening it reads again.
        assertTrue(screen.contains("if (!contextVisible) contextLoadedKey = null"))
    }

    // ------------------------------------------- the settle edge (v19 device)

    /**
     * P2 on the v19 device: with the panel **open**, a new turn's `user_messages`
     * count and its token/usage rows kept the previous turn's numbers until the
     * panel was closed and re-opened. Two reads were frozen behind that:
     *
     * - the frame half, whose effect only re-runs for a new
     *   [SessionContextLoad.Key] — a turn was not one;
     * - the live token rows, which `ChatViewModel` re-reads on its own snapshot
     *   cadence, throttled by `SNAPSHOT_THROTTLE_MS` — and a fast turn/finish
     *   cycle is exactly what that throttle drops.
     *
     * The rule below is the fix's pure half: one key bump per run that **ended**,
     * none for the streaming half. It is deliberately not "bump on every state
     * change": a part event per streamed token must not turn into a paged
     * transcript drain.
     */
    @Test
    fun theKeyAdvancesOncePerSettledRunAndNeverWhileStreaming() {
        var count = 0
        // Opened on an idle session: the read the open already issues is the one
        // this key describes — bumping here would drain the frame twice.
        count = SessionContextLoad.settleCount(null, ChatStore.RunStatus.IDLE, count)
        assertEquals(0, count)
        // An idle session stays idle for every snapshot it takes.
        count = SessionContextLoad.settleCount(ChatStore.RunStatus.IDLE, ChatStore.RunStatus.IDLE, count)
        assertEquals(0, count)

        // The turn starts, and streams: the key must not move.
        count = SessionContextLoad.settleCount(ChatStore.RunStatus.IDLE, ChatStore.RunStatus.RUNNING, count)
        assertEquals(0, count)
        count = SessionContextLoad.settleCount(ChatStore.RunStatus.RUNNING, ChatStore.RunStatus.RUNNING, count)
        assertEquals(0, count)

        // The settle is the one bump.
        count = SessionContextLoad.settleCount(ChatStore.RunStatus.RUNNING, ChatStore.RunStatus.IDLE, count)
        assertEquals(1, count)

        // A second turn, same shape.
        count = SessionContextLoad.settleCount(ChatStore.RunStatus.IDLE, ChatStore.RunStatus.RUNNING, count)
        assertEquals(1, count)
        count = SessionContextLoad.settleCount(ChatStore.RunStatus.RUNNING, ChatStore.RunStatus.IDLE, count)
        assertEquals(2, count)

        // A failed run settles too — the rows it left behind are as stale as a
        // clean turn's.
        count = SessionContextLoad.settleCount(ChatStore.RunStatus.ERROR, ChatStore.RunStatus.IDLE, count)
        assertEquals(3, count)

        // …and the bumped key is what forces the frame read.
        val loaded = SessionContextLoad.Key(session = "ses_1", serverRevision = 0, reload = 0, settle = 2)
        assertFalse(SessionContextLoad.needsLoad(loaded = loaded, visible = true, key = loaded))
        assertTrue(
            SessionContextLoad.needsLoad(loaded = loaded, visible = true, key = loaded.copy(settle = 3)),
        )
        // A hidden panel still reads nothing on a settle.
        assertFalse(
            SessionContextLoad.needsLoad(loaded = loaded, visible = false, key = loaded.copy(settle = 3)),
        )
    }

    @Test
    fun theSettleEdgeDrivesTheFrameAndTheLiveRowsAndNothingElse() {
        val screen = sessionScreen()
        // The effect is keyed on the run status **value**, so a part event per
        // streamed token (which moves `state.messages`, not `state.runStatus`)
        // cannot restart it.
        val settle = screen.substringAfter("LaunchedEffect(state.runStatus)")
            .substringBefore("LaunchedEffect(activeSession.id, serverRevision, contextVisible, contextReloadKey")
        assertTrue(settle.contains("SessionContextLoad.settleCount(previous, state.runStatus, contextSettleKey)"))
        assertTrue(settle.contains("contextSettleKey = next"))
        // The O(1) live read is issued on the edge itself; the frame half is left
        // to the effect below (which the bumped key re-runs, and which refuses for
        // an invisible panel).
        assertTrue(settle.contains("chatViewModel.refreshActiveContext()"))
        assertFalse(settle.contains("fetchExportFrameResult"))
        assertTrue(screen.contains("LaunchedEffect(state.runStatus)"))
        assertFalse(screen.contains("LaunchedEffect(state.messages)"))
    }

    @Test
    fun theReloadRuleStaysPureAndDocumented() {
        val load = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/context/SessionContextLoad.kt")
        assertTrue(load.contains("fun needsLoad(loaded: Key?, visible: Boolean, key: Key): Boolean"))
        assertTrue(load.contains("visible && loaded != key"))
        // The frame half's cost is the reason the rule exists: the raw list comes
        // from a paged drain, so "once per key" is deliberate, not an oversight.
        assertTrue(load.contains("paged export drain"))
        // …and the settle rule is the same pure bookkeeping, not a second latch.
        assertTrue(
            load.contains(
                "fun settleCount(previous: ChatStore.RunStatus?, status: ChatStore.RunStatus, count: Int): Int",
            ),
        )
    }
}
