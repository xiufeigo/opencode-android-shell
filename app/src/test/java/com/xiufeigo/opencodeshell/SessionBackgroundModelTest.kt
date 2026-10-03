package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.summary.SessionSummaryModel.BackgroundTask
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure rules of `SessionBackgroundModel` — the v2 `createSessionBackground`
 * memos (`_upstream/packages/app/src/session/requests/background.ts:1-115`,
 * v2 head `8ce629b`): history half (completed shell/subagent tool parts whose
 * `metadata.status` is still `running`, retired by synthetic notices) + live
 * half (non-idle child sessions, running shells), blocking-filtered and
 * deduped by id.
 */
class SessionBackgroundModelTest {

    // ------------------------------------------------------------- fixtures

    private fun assistant(
        id: String = "msg_1",
        completedAt: Long? = null,
        parts: List<JSONObject> = emptyList(),
    ): JSONObject = JSONObject().apply {
        put("type", "assistant")
        put("id", id)
        put(
            "time",
            JSONObject().put("created", 1L).apply { if (completedAt != null) put("completed", completedAt) },
        )
        put("content", JSONArray().also { array -> parts.forEach(array::put) })
    }

    private fun toolPart(
        id: String,
        name: String,
        status: String,
        command: String? = null,
        description: String? = null,
        agent: String? = null,
        shellID: String? = null,
        sessionID: String? = null,
        targetStatus: String? = null,
    ): JSONObject = JSONObject().apply {
        put("type", "tool")
        put("id", id)
        put("name", name)
        put(
            "state",
            JSONObject().apply {
                put("status", status)
                put("input", JSONObject().apply {
                    command?.let { put("command", it) }
                    description?.let { put("description", it) }
                    agent?.let { put("agent", it) }
                })
                put("metadata", JSONObject().apply {
                    shellID?.let { put("shellID", it) }
                    sessionID?.let { put("sessionID", it) }
                    targetStatus?.let { put("status", it) }
                })
            },
        )
    }

    private fun synthetic(source: String, childID: String? = null, shellID: String? = null, jobID: String? = null): JSONObject =
        JSONObject().apply {
            put("type", "synthetic")
            put("id", "syn_$source")
            put("metadata", JSONObject().apply {
                put("source", source)
                childID?.let { put("childID", it) }
                shellID?.let { put("shellID", it) }
                jobID?.let { put("jobID", it) }
            })
        }

    private fun rows(tasks: List<BackgroundTask>): List<Triple<String, String, String?>> =
        tasks.map { Triple(it.type, it.label, it.agent) }

    // --------------------------------------------------------- history half

    @Test
    fun completedToolPartWithRunningMetadataIsBackgroundWork() {
        val tasks = SessionBackgroundModel.tasks(
            sessionID = "ses_1",
            messages = listOf(
                assistant(
                    completedAt = 9L,
                    parts = listOf(
                        toolPart(
                            "prt_1", "shell", status = "completed",
                            command = "npm test", shellID = "shl_1", targetStatus = "running",
                        ),
                    ),
                ),
            ),
            children = emptyList(),
            shells = emptyList(),
        )
        assertEquals(listOf(Triple("shell", "npm test", null)), rows(tasks))
    }

    @Test
    fun completedToolPartWithoutRunningMetadataIsNotBackgroundWork() {
        val tasks = SessionBackgroundModel.tasks(
            sessionID = "ses_1",
            messages = listOf(
                assistant(
                    completedAt = 9L,
                    parts = listOf(
                        // finished shell
                        toolPart(
                            "prt_1", "shell", status = "completed",
                            command = "ls", shellID = "shl_1", targetStatus = "exited",
                        ),
                        // still blocking — the unfinished-turn half only
                        toolPart("prt_2", "shell", status = "running", command = "top", shellID = "shl_2"),
                    ),
                ),
            ),
            children = emptyList(),
            shells = emptyList(),
        )
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun subagentTaskCarriesDescriptionAndAgent() {
        val tasks = SessionBackgroundModel.tasks(
            sessionID = "ses_1",
            messages = listOf(
                assistant(
                    completedAt = 9L,
                    parts = listOf(
                        toolPart(
                            "prt_1", "subagent", status = "completed",
                            description = "dig the parser", agent = "explore",
                            sessionID = "ses_kid", targetStatus = "running",
                        ),
                    ),
                ),
            ),
            children = emptyList(),
            shells = emptyList(),
        )
        assertEquals(listOf(Triple("subagent", "dig the parser", "explore")), rows(tasks))
    }

    @Test
    fun syntheticNoticesRetireHistoryTasks() {
        val history = listOf(
            assistant(
                completedAt = 9L,
                parts = listOf(
                    toolPart(
                        "prt_1", "shell", status = "completed",
                        command = "npm test", shellID = "shl_1", targetStatus = "running",
                    ),
                    toolPart(
                        "prt_2", "subagent", status = "completed",
                        description = "dig", sessionID = "ses_kid", targetStatus = "running",
                    ),
                ),
            ),
        )
        val tasks = SessionBackgroundModel.tasks(
            sessionID = "ses_1",
            messages = history + listOf(synthetic("shell", shellID = "shl_1"), synthetic("subagent", childID = "ses_kid")),
            children = emptyList(),
            shells = emptyList(),
        )
        assertTrue(tasks.isEmpty())
    }

    // ----------------------------------------------------------- live halves

    @Test
    fun childSessionsNeedTheParentLinkAndARunningStatus() {
        val tasks = SessionBackgroundModel.tasks(
            sessionID = "ses_1",
            messages = emptyList(),
            children = listOf(
                SessionBackgroundModel.Child("ses_run", "ses_1", "exploring"),
                SessionBackgroundModel.Child("ses_idle", "ses_1", "done"),
                SessionBackgroundModel.Child("ses_other", "ses_x", "not mine"),
                SessionBackgroundModel.Child("ses_unknown", "ses_1", null),
            ),
            shells = emptyList(),
            active = mapOf("ses_run" to "running", "ses_idle" to "idle", "ses_other" to "running"),
        )
        // Absent from `GET /api/session/active` means idle (`background.ts:91`).
        assertEquals(
            listOf(Triple("subagent", "exploring", null)),
            rows(tasks),
        )
    }

    @Test
    fun runningShellsNeedTheSessionLink() {
        val tasks = SessionBackgroundModel.tasks(
            sessionID = "ses_1",
            messages = emptyList(),
            children = emptyList(),
            shells = listOf(
                SessionBackgroundModel.Shell("shl_1", "npm test", "ses_1", "running"),
                SessionBackgroundModel.Shell("shl_2", "exited", "ses_1", "exited"),
                SessionBackgroundModel.Shell("shl_3", "foreign", "ses_x", "running"),
                SessionBackgroundModel.Shell("shl_4", null, "ses_1", "running"),
            ),
        )
        assertEquals(
            listOf(
                Triple("shell", "npm test", null),
                Triple("shell", "shl_4", null),
            ),
            rows(tasks),
        )
    }

    @Test
    fun blockingToolCallsHideTheMatchingLiveRows() {
        val tasks = SessionBackgroundModel.tasks(
            sessionID = "ses_1",
            messages = listOf(
                // unfinished turn: its running tool calls are the blocking half
                assistant(
                    parts = listOf(
                        toolPart(
                            "prt_b", "subagent", status = "running",
                            description = "dig", sessionID = "ses_kid",
                        ),
                    ),
                ),
            ),
            children = listOf(
                SessionBackgroundModel.Child("ses_kid", "ses_1", "dig"),
                SessionBackgroundModel.Child("ses_free", "ses_1", "free"),
            ),
            shells = listOf(
                SessionBackgroundModel.Shell("shl_1", "npm test", "ses_1", "running"),
            ),
            active = mapOf("ses_kid" to "running", "ses_free" to "running"),
        )
        // `ses_kid` matches the blocking subagent by id (also by title/label);
        // the un-matched shell stays.
        assertEquals(
            listOf(
                Triple("subagent", "free", null),
                Triple("shell", "npm test", null),
            ),
            rows(tasks),
        )
    }

    @Test
    fun blockingIsOnlyReadOffTheUnfinishedTurn() {
        val tasks = SessionBackgroundModel.tasks(
            sessionID = "ses_1",
            messages = listOf(
                assistant(
                    completedAt = 9L,
                    parts = listOf(
                        toolPart("prt_old", "shell", status = "running", command = "old", shellID = "shl_old"),
                    ),
                ),
            ),
            children = emptyList(),
            shells = listOf(SessionBackgroundModel.Shell("shl_old", "old", "ses_1", "running")),
        )
        // A finished turn's running tool part is not blocking — and without
        // `metadata.status: running` it is not history either; the live shell
        // row still surfaces the work.
        assertEquals(listOf(Triple("shell", "old", null)), rows(tasks))
    }

    // ------------------------------------------------------------- ordering

    @Test
    fun duplicateIdsKeepFirstPositionAndLastLabel() {
        val tasks = SessionBackgroundModel.tasks(
            sessionID = "ses_1",
            messages = listOf(
                assistant(
                    completedAt = 9L,
                    parts = listOf(
                        toolPart(
                            "prt_1", "shell", status = "completed",
                            command = "old label", shellID = "shl_1", targetStatus = "running",
                        ),
                    ),
                ),
            ),
            children = emptyList(),
            shells = listOf(
                SessionBackgroundModel.Shell("shl_1", "new label", "ses_1", "running"),
                SessionBackgroundModel.Shell("shl_2", "second", "ses_1", "running"),
            ),
        )
        // JS `Map` semantics (`background.ts:112-114`): first key position,
        // last value — the live shell row wins the label of `shl_1`.
        assertEquals(
            listOf(
                Triple("shell", "new label", null),
                Triple("shell", "second", null),
            ),
            rows(tasks),
        )
    }

    @Test
    fun orderIsHistorySubagentsThenLiveChildrenThenHistoryShellsThenLiveShells() {
        val tasks = SessionBackgroundModel.tasks(
            sessionID = "ses_1",
            messages = listOf(
                assistant(
                    completedAt = 9L,
                    parts = listOf(
                        toolPart(
                            "prt_s", "shell", status = "completed",
                            command = "hist shell", shellID = "shl_h", targetStatus = "running",
                        ),
                        toolPart(
                            "prt_a", "subagent", status = "completed",
                            description = "hist agent", sessionID = "ses_h", targetStatus = "running",
                        ),
                    ),
                ),
            ),
            children = listOf(SessionBackgroundModel.Child("ses_live", "ses_1", "live agent")),
            shells = listOf(SessionBackgroundModel.Shell("shl_live", "live shell", "ses_1", "running")),
            active = mapOf("ses_live" to "running"),
        )
        assertEquals(
            listOf(
                Triple("subagent", "hist agent", null),
                Triple("subagent", "live agent", null),
                Triple("shell", "hist shell", null),
                Triple("shell", "live shell", null),
            ),
            rows(tasks),
        )
    }

    // ---------------------------------------------------------------- misc

    @Test
    fun noTrackedSessionMeansNoTasks() {
        assertTrue(
            SessionBackgroundModel.tasks(
                sessionID = null,
                messages = listOf(assistant()),
                children = listOf(SessionBackgroundModel.Child("ses_kid", null, "kid")),
                shells = listOf(SessionBackgroundModel.Shell("shl_1", "ls", null, "running")),
            ).isEmpty(),
        )
    }

    @Test
    fun v1InfoPartsRowsAreReadToo() {
        val v1 = JSONObject()
            .put(
                "info",
                JSONObject()
                    .put("role", "assistant")
                    .put("time", JSONObject().put("created", 1L).put("completed", 9L)),
            )
            .put(
                "parts",
                JSONArray().put(
                    JSONObject()
                        .put("type", "tool")
                        .put("tool", "shell")
                        .put(
                            "state",
                            JSONObject()
                                .put("status", "completed")
                                .put("input", JSONObject().put("command", "make"))
                                .put("metadata", JSONObject().put("shellID", "shl_1").put("status", "running")),
                        ),
                ),
            )
        val tasks = SessionBackgroundModel.tasks(
            sessionID = "ses_1",
            messages = listOf(v1),
            children = emptyList(),
            shells = emptyList(),
        )
        assertEquals(listOf(Triple("shell", "make", null)), rows(tasks))
    }
}
