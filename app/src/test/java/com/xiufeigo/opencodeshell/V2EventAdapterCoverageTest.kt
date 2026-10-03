package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2EventAdapter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2 frames that used to be silently dropped: one mapping test per wire type
 * plus the `ChatStore.reduce` effect the mapping feeds. Wire shapes are from
 * `_upstream/packages/schema/src/` (`session-event.ts`,
 * `session-compaction-event.ts`, `session-message.ts`, `shell.ts`,
 * `session-revert.ts`, `persistent-pty.ts`, `worktree*.ts`, …); row-id rules
 * follow the upstream projector (`core/src/session/message-updater.ts`) and
 * `SessionMessage.ID.fromEvent` (`session-message.ts:27`, `evt_` → `msg_`).
 */
class V2EventAdapterCoverageTest {

    private fun adapt(type: String, id: String = "evt_1", data: String = "{}"): List<ChatStore.Event> =
        V2EventAdapter.adapt(
            type,
            JSONObject("""{"id":"$id","type":"$type","created":1700000000000,"data":$data}"""),
        )

    private fun single(type: String, id: String = "evt_1", data: String = "{}"): ChatStore.Event =
        adapt(type, id, data).single()

    /**
     * The frame's **own** event, for the frames the A-3 row-write signal rides
     * beside (`session.model.selected` / `agent.selected` / `permissions` /
     * `revert.*` also stale the session list — `SessionDrawerFreshnessTest`).
     */
    private fun own(type: String, id: String = "evt_1", data: String = "{}"): ChatStore.Event =
        adapt(type, id, data).filterNot { it is ChatStore.Event.ServerSignal }.single()

    private fun state(sessionID: String? = "ses_1") = ChatStore.ChatState(sessionID = sessionID)

    // --------------------------------------------- usage (cost / tokens)

    @Test
    fun usageUpdatedAndRecordedCarryCostAndTokenTotal() {
        // `TokenUsage.Info{input, output, reasoning, cache{read, write}}`
        // (`token-usage.ts:8-18`) flattens like `UiMessage.tokens`.
        val payload = """{"sessionID":"ses_1","cost":0.42,
           "tokens":{"input":10,"output":20,"reasoning":5,"cache":{"read":3,"write":2}}}"""
        val updated = single("session.usage.updated", data = payload) as ChatStore.Event.SessionUsageUpdated
        assertEquals("ses_1", updated.sessionID)
        assertEquals(0.42, updated.cost, 0.0)
        assertEquals(40L, updated.tokens)

        // `usage.recorded{source, cost, tokens}` (`session-event.ts:148-158`):
        // `source` is accounting-only and dropped.
        val recorded = single(
            "session.usage.recorded",
            data = """{"sessionID":"ses_1","source":"compaction","cost":1.5,"tokens":{"input":1,"output":1,
               "reasoning":0,"cache":{"read":0,"write":0}}}""",
        ) as ChatStore.Event.SessionUsageUpdated
        assertEquals(1.5, recorded.cost, 0.0)
        assertEquals(2L, recorded.tokens)

        val s = ChatStore.reduce(state(), updated)
        assertEquals(0.42, s.usageCost!!, 0.0)
        assertEquals(40L, s.usageTokens)
    }

    @Test
    fun usageIsGatedToTheTrackedSession() {
        val foreign = single(
            "session.usage.updated",
            data = """{"sessionID":"ses_other","cost":9.0,"tokens":{"input":1,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}""",
        )
        assertEquals(state(), ChatStore.reduce(state(), foreign))
    }

    // --------------------------------------------- step.streamed (liveness)

    @Test
    fun stepStreamedIsALivenessFrame() {
        // `session.step.streamed{assistantMessageID}` (`session-event.ts:337-345`).
        val event = single(
            "session.step.streamed",
            data = """{"sessionID":"ses_1","assistantMessageID":"msg_a1"}""",
        )
        assertEquals(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING), event)
    }

    // --------------------------------------------- compaction row lifecycle

    @Test
    fun compactionPhasesDriveOneRunningRow() {
        var s = state()
        // `started{reason, recent, inputID?}` — the row id is `inputID` when
        // present (`message-updater.ts:411-423`).
        s = ChatStore.reduce(
            s,
            single(
                "session.compaction.started",
                id = "evt_c1",
                data = """{"sessionID":"ses_1","reason":"auto","recent":"r","inputID":"msg_in"}""",
            ),
        )
        var row = s.messages.single()
        assertEquals("msg_in", row.id)
        var part = row.parts.single()
        assertEquals("compaction", part.type)
        assertEquals("msg_in:notice", part.id)
        assertEquals("running", part.toolStatus)

        // `delta{text}` (ephemeral fragment) appends to the running summary.
        s = ChatStore.reduce(
            s,
            single("session.compaction.delta", data = """{"sessionID":"ses_1","text":"sum"}"""),
        )
        s = ChatStore.reduce(
            s,
            single("session.compaction.delta", data = """{"sessionID":"ses_1","text":"mary"}"""),
        )
        assertEquals("summary", s.messages.single().parts.single().text)

        // `ended{text, …}` settles with the FULL summary, not another fragment.
        s = ChatStore.reduce(
            s,
            single(
                "session.compaction.ended",
                id = "evt_c2",
                data = """{"sessionID":"ses_1","reason":"auto","text":"final summary","cost":0.1,
                   "tokens":{"input":1,"output":1,"reasoning":0,"cache":{"read":0,"write":0}}}""",
            ),
        )
        row = s.messages.single()
        part = row.parts.single()
        assertEquals("completed", part.toolStatus)
        assertEquals("final summary", part.text)
    }

    @Test
    fun compactionFailedSettlesTheRunningRowAndKeepsTheErrorLine() {
        var s = ChatStore.reduce(
            state(),
            single(
                "session.compaction.started",
                id = "evt_c1",
                data = """{"sessionID":"ses_1","reason":"manual","recent":"","inputID":"msg_in"}""",
            ),
        )
        s = ChatStore.reduce(
            s,
            single(
                "session.compaction.failed",
                data = """{"sessionID":"ses_1","reason":"manual","inputID":"msg_in",
                   "error":{"type":"compaction.failed","message":"model exploded"}}""",
            ),
        )
        val part = s.messages.single().parts.single()
        assertEquals("error", part.toolStatus)
        // The readable error line lands in `text` (`errorMessageOf` unwrapping).
        assertEquals("model exploded", part.text)
    }

    @Test
    fun compactedSettlesAStrayRunningRow() {
        // `session.compacted{sessionID}` (`session-compaction-event.ts:6-11`):
        // history was replaced — a still-running row (its `ended` lost in a
        // replay gap) must not stay "running" forever.
        var s = ChatStore.reduce(
            state(),
            single(
                "session.compaction.started",
                data = """{"sessionID":"ses_1","reason":"auto","recent":"","inputID":"msg_in"}""",
            ),
        )
        s = ChatStore.reduce(
            s,
            single("session.compacted", data = """{"sessionID":"ses_1"}"""),
        )
        assertEquals("completed", s.messages.single().parts.single().toolStatus)
    }

    // --------------------------------------------- renamed / moved / forked

    @Test
    fun renamedFollowsTheTitleAndRefreshesListRows() {
        val events = adapt(
            "session.renamed",
            data = """{"sessionID":"ses_1","title":"New title"}""",
        )
        val rename = events.filterIsInstance<ChatStore.Event.SessionRenamed>().single()
        assertEquals("New title", rename.title)
        // List rows carry the title too — the same re-read as before.
        assertTrue(events.any {
            it == ChatStore.Event.ServerSignal(ChatStore.Signal.SESSION_CREATED, sessionID = "ses_1")
        })
        assertEquals("New title", ChatStore.reduce(state(), rename).sessionTitle)
    }

    @Test
    fun movedInsertsALocationSwitchedRowAndRefreshesTheList() {
        // `session.moved{location, projectID?, subpath?}` (`session-inbox.ts:31-35`).
        val events = adapt(
            "session.moved",
            id = "evt_m1",
            data = """{"sessionID":"ses_1","location":{"directory":"/other"},
               "projectID":"prj_2","subpath":"app"}""",
        )
        val row = events.filterIsInstance<ChatStore.Event.MessageUpdated>().single().message
        assertEquals("msg_m1", row.id)
        assertEquals("location-switched", row.parts.single().type)
        assertTrue(row.parts.single().rawJson!!.contains("/other"))
        assertTrue(events.any {
            it is ChatStore.Event.ServerSignal && it.signal == ChatStore.Signal.SESSION_CREATED
        })
    }

    @Test
    fun forkedRefreshesTheSessionListAsTheChildRow() {
        // `session.forked{sessionID, parentID, boundary}` — `sessionID` is the
        // child (`session-event.ts:180-194`).
        val event = single(
            "session.forked",
            data = """{"sessionID":"ses_child","parentID":"ses_parent",
               "boundary":{"type":"through","messageID":"msg_1"}}""",
        )
        assertEquals(
            ChatStore.Event.ServerSignal(ChatStore.Signal.SESSION_CREATED, sessionID = "ses_child"),
            event,
        )
    }

    // --------------------------------------------- revert boundary

    @Test
    fun revertStagedClearedAndCommittedTruncateTheTranscript() {
        val stagedRaw = """{"messageID":"msg_1","snapshot":"snap_1"}"""
        var s = ChatStore.reduce(
            state(),
            ChatStore.Event.Snapshot(
                listOf(
                    ChatStore.UiMessage("msg_1", "user", 1, listOf(ChatStore.UiPart("p1", "text", "a"))),
                    ChatStore.UiMessage("msg_2", "assistant", 2, listOf(ChatStore.UiPart("p2", "text", "b"))),
                ),
            ),
        )
        s = ChatStore.reduce(
            s,
            own(
                "session.revert.staged",
                data = """{"sessionID":"ses_1","revert":$stagedRaw}""",
            ) as ChatStore.Event.SessionRevertStaged,
        )
        assertEquals("msg_1", s.stagedRevert?.messageID)
        assertTrue(s.stagedRevert!!.rawJson.contains("snap_1"))

        // `committed{to}` drops the boundary message and everything after it
        // (`data.ts:1076-1092`) and clears the staged boundary.
        s = ChatStore.reduce(
            s,
            own("session.revert.committed", data = """{"sessionID":"ses_1","to":"msg_1"}"""),
        )
        assertEquals(emptyList<ChatStore.UiMessage>(), s.messages)
        assertNull(s.stagedRevert)

        s = ChatStore.reduce(s, ChatStore.Event.SessionRevertStaged("ses_1", "msg_9", "{}"))
        s = ChatStore.reduce(s, own("session.revert.cleared", data = """{"sessionID":"ses_1"}"""))
        assertNull(s.stagedRevert)
    }

    // --------------------------------------------- instructions / synthetic / skill

    @Test
    fun instructionsUpdatedWithTextInsertsASystemRow() {
        // `instructions.updated{delta, text?}` — no `text` ⇒ no transcript row
        // (`session-event.ts:263-266`, projector `message-updater.ts:147-158`).
        val withText = adapt(
            "session.instructions.updated",
            id = "evt_i1",
            data = """{"sessionID":"ses_1","delta":{"src":{"key":"k"}},"text":"New guidance"}""",
        ).filterIsInstance<ChatStore.Event.MessageUpdated>().single().message
        assertEquals("msg_i1", withText.id)
        assertEquals("system", withText.parts.single().type)
        assertEquals("New guidance", withText.parts.single().text)

        assertTrue(
            adapt(
                "session.instructions.updated",
                data = """{"sessionID":"ses_1","delta":{"src":{"key":"k"}}}""",
            ).isEmpty(),
        )
    }

    @Test
    fun syntheticAndSkillRowsCarryTheirText() {
        val synthetic = adapt(
            "session.synthetic",
            id = "evt_s1",
            data = """{"sessionID":"ses_1","text":"Heads up","description":"d"}""",
        ).filterIsInstance<ChatStore.Event.MessageUpdated>().single().message
        assertEquals("synthetic", synthetic.parts.single().type)
        assertEquals("Heads up", synthetic.parts.single().text)

        // `skill.activated{id, name, text}` (`session-event.ts:284-296`).
        val skill = adapt(
            "session.skill.activated",
            id = "evt_k1",
            data = """{"sessionID":"ses_1","id":"sk_1","name":"review","text":"SKILL BODY"}""",
        ).filterIsInstance<ChatStore.Event.MessageUpdated>().single().message
        assertEquals("msg_k1", skill.id)
        val part = skill.parts.single()
        assertEquals("skill", part.type)
        assertEquals("review", part.toolName)
        assertEquals("SKILL BODY", part.text)
    }

    // --------------------------------------------- shell rows

    @Test
    fun shellStartedRowIsKeyedLikeTheProjectorAndEndedSettlesIt() {
        // Row id = `ID.fromEvent(event.id)` (`message-updater.ts:185-198`);
        // `ended` finds it by `shell.id` in the row payload (`:199-214`).
        val events = adapt(
            "session.shell.started",
            id = "evt_sh1",
            data = """{"sessionID":"ses_1","shell":{"id":"sh_9","command":"ls","status":"running"}}""",
        )
        var s = ChatStore.reduce(state(), events.single())
        val row = s.messages.single()
        assertEquals("msg_sh1", row.id)
        assertEquals("shell", row.parts.single().type)
        assertEquals("msg_sh1:notice", row.parts.single().id)
        assertEquals("ls", row.parts.single().text)

        s = ChatStore.reduce(
            s,
            ChatStore.Event.ShellNoticeEnded(
                sessionID = "ses_1",
                shellID = "sh_9",
                text = "ls\nfile.txt",
                toolStatus = "exited",
                toolOutput = "file.txt",
                rawJson = """{"shell":{"id":"sh_9"},"output":{"output":"file.txt"}}""",
            ),
        )
        val settled = s.messages.single().parts.single()
        assertEquals("exited", settled.toolStatus)
        assertEquals("file.txt", settled.toolOutput)
        assertEquals("ls\nfile.txt", settled.text)

        // Orphan end (no started row) is a no-op, like upstream's `getShell`.
        val before = s
        assertEquals(
            before,
            ChatStore.reduce(
                s,
                ChatStore.Event.ShellNoticeEnded("ses_1", "sh_missing", "x", "exited", null, null),
            ),
        )
    }

    @Test
    fun shellEndedFrameMapsToTheSettleEvent() {
        val event = single(
            "session.shell.ended",
            data = """{"sessionID":"ses_1","shell":{"id":"sh_9","command":"ls","status":"exited","exit":0},
               "output":{"output":"file.txt","cursor":8,"size":8,"truncated":false}}""",
        ) as ChatStore.Event.ShellNoticeEnded
        assertEquals("sh_9", event.shellID)
        assertEquals("exited", event.toolStatus)
        assertEquals("file.txt", event.toolOutput)
        assertEquals("ls\nfile.txt", event.text)
    }

    // --------------------------------------------- message.content.updated

    @Test
    fun contentUpdatedReplacesPartsWholesaleWithStableIds() {
        // `content[]` = `AssistantContentEncoded` (`session-message.ts:194-202`).
        // Ids must match `V2Transport.stableParts` (per-kind ordinals; tools by
        // call id) or a later snapshot stacks duplicates.
        val event = single(
            "session.message.content.updated",
            data = """{"sessionID":"ses_1","messageID":"msg_a1","content":[
               {"type":"text","text":"first"},
               {"type":"reasoning","text":"why"},
               {"type":"text","text":"second"},
               {"type":"tool","id":"call_1","name":"shell","state":{"status":"completed",
                "input":{"command":"ls"},"content":[{"type":"text","text":"files"}]}}]}""",
        ) as ChatStore.Event.MessageContentUpdated
        assertEquals(
            listOf("msg_a1:text:0", "msg_a1:reasoning:0", "msg_a1:text:1", "call_1"),
            event.parts.map { it.id },
        )
        assertEquals("completed", event.parts[3].toolStatus)
        assertEquals("files", event.parts[3].toolOutput)

        // Full replacement: parts the new content no longer carries disappear.
        var s = ChatStore.reduce(
            state(),
            ChatStore.Event.Snapshot(
                listOf(
                    ChatStore.UiMessage(
                        "msg_a1",
                        "assistant",
                        1,
                        listOf(
                            ChatStore.UiPart("old", "text", "gone"),
                            ChatStore.UiPart("call_1", "tool", toolName = "shell"),
                        ),
                    ),
                ),
            ),
        )
        s = ChatStore.reduce(s, event)
        assertEquals(event.parts.map { it.id }, s.messages.single().parts.map { it.id })
        assertEquals("first", s.messages.single().parts[0].text)
    }

    // --------------------------------------------- selection / permissions / viewed

    @Test
    fun agentAndModelSelectionUpdateStateAndInsertNoticeRows() {
        val agentEvents = adapt(
            "session.agent.selected",
            id = "evt_a1",
            data = """{"sessionID":"ses_1","agent":"build","previous":"plan"}""",
        )
        val agentRow = agentEvents.filterIsInstance<ChatStore.Event.MessageUpdated>().single().message
        assertEquals("agent-switched", agentRow.parts.single().type)
        assertEquals("build", agentRow.agent)
        var s = ChatStore.reduce(state(), agentEvents.single { it is ChatStore.Event.SessionSelectionChanged })
        assertEquals("build", s.sessionAgent)
        assertNull(s.sessionModel)

        val modelEvents = adapt(
            "session.model.selected",
            id = "evt_o1",
            // `previous` names the model this one moved off, so the frame is a
            // **switch** and not the session's initial selection (which writes
            // no row — `ModelSwitchNoticeV69Test`).
            data = """{"sessionID":"ses_1","model":{"providerID":"anthropic","id":"claude-4"},""" +
                """"previous":{"providerID":"anthropic","id":"claude-3"}}""",
        )
        val modelRow = modelEvents.filterIsInstance<ChatStore.Event.MessageUpdated>().single().message
        assertEquals("model-switched", modelRow.parts.single().type)
        // `Model.Ref` becomes the `"providerID/modelID"` display pair.
        assertEquals("anthropic/claude-4", modelRow.model)
        s = ChatStore.reduce(s, modelEvents.single { it is ChatStore.Event.SessionSelectionChanged })
        assertEquals("anthropic/claude-4", s.sessionModel)
        assertEquals("build", s.sessionAgent)
    }

    @Test
    fun permissionsReplaceTheStoredRulesetVerbatim() {
        // A `Permission.Ruleset` is an **array** of `Rule{action, resource,
        // effect}` (`schema/src/permission.ts:59-66`) — the shape pinned by
        // `permissionsRulesetArrayReplacesTheStoredRuleset` below. This fixture
        // keeps the object shape on purpose: it pins the pass-through of a
        // non-protocol payload, which must not blank the stored ruleset.
        val event = own(
            "session.permissions",
            data = """{"sessionID":"ses_1","permissions":{"edit":{"ask":[]},"bash":{"allow":["ls"]}}}""",
        ) as ChatStore.Event.SessionPermissionsUpdated
        assertTrue(event.permissionsJson.contains("\"bash\""))
        val s = ChatStore.reduce(state(), event)
        assertTrue(s.permissionsJson!!.contains("\"allow\""))
    }

    @Test
    fun permissionsRulesetArrayReplacesTheStoredRuleset() {
        // The real wire shape: `session.permissions{permissions: Permission.Ruleset}`
        // with `Ruleset = Rule[]` (`schema/src/permission.ts:59-66`,
        // `session-event.ts:114-122`), written wholesale by the projector
        // (`core/src/session/projector.ts:576-583`) — a replacement, not a merge.
        val event = own(
            "session.permissions",
            data = """{"sessionID":"ses_1","permissions":[{"action":"edit","resource":"*","effect":"ask"},{"action":"bash","resource":"ls","effect":"allow"}]}""",
        ) as ChatStore.Event.SessionPermissionsUpdated
        assertEquals("ses_1", event.sessionID)
        assertTrue("the ruleset stays an array", event.permissionsJson.startsWith("["))
        assertTrue(event.permissionsJson.contains("\"effect\":\"allow\""))
        assertTrue(event.permissionsJson.contains("\"resource\":\"ls\""))
        // The store takes the frame's ruleset verbatim.
        val s = ChatStore.reduce(state(), event)
        assertEquals(event.permissionsJson, s.permissionsJson)

        // A later frame replaces the whole ruleset (the projector's `set`).
        val replaced = own(
            "session.permissions",
            data = """{"sessionID":"ses_1","permissions":[{"action":"webfetch","resource":"*","effect":"deny"}]}""",
        ) as ChatStore.Event.SessionPermissionsUpdated
        val after = ChatStore.reduce(s, replaced)
        assertEquals(replaced.permissionsJson, after.permissionsJson)
        assertFalse(after.permissionsJson!!.contains("bash"))
    }

    @Test
    fun permissionsEmptyRulesetClearsEveryRule() {
        // `[]` is a legal `Permission.Ruleset`: every rule cleared, still a
        // wholesale replacement (`session-event.ts:114-122`).
        val event = own(
            "session.permissions",
            data = """{"sessionID":"ses_1","permissions":[]}""",
        ) as ChatStore.Event.SessionPermissionsUpdated
        assertEquals("[]", event.permissionsJson)
        assertEquals("[]", ChatStore.reduce(state(), event).permissionsJson)
    }

    @Test
    fun permissionsWithoutARulesetOnlyStaleTheSessionList() {
        // `permissions` is required by the schema; a frame that cannot carry a
        // ruleset (missing / JSON null / string / number) must not invent one —
        // the row-write signal is all that survives.
        val payloads = listOf(
            """{"sessionID":"ses_1"}""",
            """{"sessionID":"ses_1","permissions":null}""",
            """{"sessionID":"ses_1","permissions":"[]"}""",
            """{"sessionID":"ses_1","permissions":7}""",
        )
        for (payload in payloads) {
            val events = adapt("session.permissions", data = payload)
            assertTrue(
                "$payload must not carry a ruleset",
                events.none { it is ChatStore.Event.SessionPermissionsUpdated },
            )
            assertEquals(
                payload,
                listOf(ChatStore.Event.ServerSignal(ChatStore.Signal.SESSION_UPDATED, sessionID = "ses_1")),
                events,
            )
        }
    }

    @Test
    fun viewedRefreshesTheSessionListForTheUnreadMarker() {
        assertEquals(
            ChatStore.Event.ServerSignal(ChatStore.Signal.SESSION_CREATED, sessionID = "ses_1"),
            single("session.viewed", data = """{"sessionID":"ses_1","idle":1700000000000}"""),
        )
    }

    // --------------------------------------------- B: catalog/toolchain signals

    @Test
    fun catalogAndToolchainDomainsMapToInvalidationSignals() {
        val rows = mapOf(
            "credential.updated" to ChatStore.Signal.CREDENTIAL_UPDATED,
            "credential.switched" to ChatStore.Signal.CREDENTIAL_SWITCHED,
            "integration.updated" to ChatStore.Signal.INTEGRATION_UPDATED,
            "plugin.updated" to ChatStore.Signal.PLUGIN_UPDATED,
            "worktree.updated" to ChatStore.Signal.WORKTREE_UPDATED,
            "worktree.resolved" to ChatStore.Signal.WORKTREE_RESOLVED,
            "worktree.ready" to ChatStore.Signal.WORKTREE_READY,
            "worktree.failed" to ChatStore.Signal.WORKTREE_FAILED,
            "persistent-pty.added" to ChatStore.Signal.PERSISTENT_PTY_ADDED,
            "persistent-pty.removed" to ChatStore.Signal.PERSISTENT_PTY_REMOVED,
            "shell.created" to ChatStore.Signal.SHELL_CREATED,
            "shell.exited" to ChatStore.Signal.SHELL_EXITED,
            "shell.deleted" to ChatStore.Signal.SHELL_DELETED,
            "websearch.updated" to ChatStore.Signal.WEBSEARCH_UPDATED,
            "reference.updated" to ChatStore.Signal.REFERENCE_UPDATED,
            "skill.updated" to ChatStore.Signal.SKILL_UPDATED,
            "command.updated" to ChatStore.Signal.COMMAND_UPDATED,
            "lsp.updated" to ChatStore.Signal.LSP_UPDATED,
            "location.shutdown" to ChatStore.Signal.LOCATION_SHUTDOWN,
        )
        for ((wire, signal) in rows) {
            val event = single(wire, data = """{"sessionID":"ses_1"}""")
            assertEquals(wire, ChatStore.Event.ServerSignal(signal, sessionID = "ses_1"), event)
            assertEquals(wire, signal, ChatStore.Signal.ofWireType(wire))
        }
    }

    @Test
    fun filesystemChangedIsTheV2FileEditedSignal() {
        // `filesystem.changed{file, event}` (`filesystem.ts:6-13`) is the v2
        // counterpart of v1 `file.edited{file}` — same files-revision nudge.
        val event = single(
            "filesystem.changed",
            data = """{"file":"src/App.kt","event":"change"}""",
        ) as ChatStore.Event.ServerSignal
        assertEquals(ChatStore.Signal.FILE_EDITED, event.signal)
        assertEquals("src/App.kt", event.subject)
    }

    @Test
    fun signalSubjectsNameTheChangedEntity() {
        val added = single(
            "persistent-pty.added",
            data = """{"sessionID":"ses_1","terminal":{"id":"pty_7","ptyID":"pty_7"}}""",
        ) as ChatStore.Event.ServerSignal
        assertEquals("pty_7", added.subject)
        val removed = single(
            "persistent-pty.removed",
            data = """{"sessionID":"ses_1","ptyID":"pty_7"}""",
        ) as ChatStore.Event.ServerSignal
        assertEquals("pty_7", removed.subject)
        val shell = single(
            "shell.created",
            data = """{"info":{"id":"sh_1","command":"ls"}}""",
        ) as ChatStore.Event.ServerSignal
        assertEquals("sh_1", shell.subject)
        val worktree = single(
            "worktree.updated",
            data = """{"projectID":"prj_1"}""",
        ) as ChatStore.Event.ServerSignal
        assertEquals("prj_1", worktree.subject)
    }

    @Test
    fun mcpFramesMapToInvalidationSignalsCarryingTheServerName() {
        // `mcp-event.ts:5-26` — `{server}` is the server *name*; one event per
        // server as it settles (`core/src/mcp/index.ts:362-508`). Upstream
        // re-reads the server list on `status.changed` and the resource catalog
        // on `resources.changed` (`client/src/solid/data.ts:1284-1294`).
        val rows = mapOf(
            "mcp.status.changed" to ChatStore.Signal.MCP_STATUS_CHANGED,
            "mcp.tools.changed" to ChatStore.Signal.MCP_TOOLS_CHANGED,
            "mcp.resources.changed" to ChatStore.Signal.MCP_RESOURCES_CHANGED,
        )
        for ((wire, signal) in rows) {
            val event = single(wire, data = """{"server":"github"}""")
            assertEquals(wire, ChatStore.Event.ServerSignal(signal, sessionID = null, subject = "github"), event)
            assertEquals(wire, signal, ChatStore.Signal.ofWireType(wire))
            // No `sessionID` on these payloads: the signal is location-scoped,
            // so the store must not file it under a session.
            assertEquals(wire, null, (event as ChatStore.Event.ServerSignal).sessionID)
        }
        // A frame without `server` still invalidates — only the subject is lost.
        assertEquals(
            ChatStore.Event.ServerSignal(ChatStore.Signal.MCP_STATUS_CHANGED),
            single("mcp.status.changed"),
        )
        // …and a signal never touches the transcript.
        assertEquals(state(), ChatStore.reduce(state(), single("mcp.status.changed")))
    }

    @Test
    fun noticeRowsNeverTouchUnrelatedState() {
        var s = state()
        for (event in adapt("session.renamed", data = """{"sessionID":"ses_other","title":"not mine"}""")) {
            s = ChatStore.reduce(s, event)
        }
        assertNull(s.sessionTitle)
        assertFalse(s.messages.isNotEmpty())
    }
}
