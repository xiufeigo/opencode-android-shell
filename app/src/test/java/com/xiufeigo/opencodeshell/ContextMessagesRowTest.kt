package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel
import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel.Kind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The context tab's top `Messages` row after the v20 device finding (P4,
 * `research/v20_verify/panel_compact/v20_panel_baseline.png`): the panel read
 * `Messages 7` above `User Messages` / `Assistant Messages` that summed to fewer
 * than 7, which reads as a contradiction. It is not one — the top row counts
 * **every frame** of the message list, `system` and injected records included,
 * while the two rows below it count roles.
 *
 * Upstream's semantics, verbatim (`session-context-tab.tsx:133-141`):
 *
 * ```ts
 * const counts = createMemo(() => {
 *   const all = messages()
 *   const user = all.reduce((count, message) => count + (message.type === "user" ? 1 : 0), 0)
 *   const assistant = all.reduce((count, message) => count + (message.type === "assistant" ? 1 : 0), 0)
 *   return { all: all.length, user, assistant }
 * })
 * ```
 *
 * with `messages()` = `data.session.message.list(id)` unfiltered, rendered as
 * `context.stats.messages` → `counts().all.toLocaleString()`. So the *value* is
 * already upstream-correct; what was wrong was the label, which said only
 * `Messages` next to two role counts. This shell keeps the frame semantics and
 * states the口径 in the label (`oc_context_stats_messages`).
 */
class ContextMessagesRowTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    /** One `GET /session/:id/message` row in this server's v1 `{info, parts}` shape. */
    private fun row(id: String, role: String): JSONObject = JSONObject()
        .put(
            "info",
            JSONObject()
                .put("id", id)
                .put("sessionID", "ses_1")
                .put("role", role)
                .put("time", JSONObject().put("created", 1_735_689_600_000L)),
        )
        .put("parts", JSONArray())

    private fun valueOf(snapshot: SessionContextModel.Snapshot, kind: Kind): String =
        snapshot.stats.first { it.kind == kind }.value

    // ------------------------------------------------------------- the value

    @Test
    fun theTopRowCountsFramesNotRoles() {
        // The v20 shape: a system frame (the injected session record) plus one
        // prompt and one answer. The top row is 3, the role counts are 1 / 1 —
        // upstream's `counts().all` against `counts().user` / `.assistant`.
        val snapshot = SessionContextModel.project(
            session = JSONObject().put("id", "ses_1"),
            messages = listOf(
                row("msg_0", "system"),
                row("msg_1", "user"),
                row("msg_2", "assistant"),
            ),
        )
        assertEquals("3", valueOf(snapshot, Kind.MESSAGES))
        assertEquals("1", valueOf(snapshot, Kind.USER_MESSAGES))
        assertEquals("1", valueOf(snapshot, Kind.ASSISTANT_MESSAGES))
        // The raw list below the stats is the same frame list, so the top row is
        // "how many rows that accordion holds".
        assertEquals(3, snapshot.messages.size)
    }

    @Test
    fun theFrameCountIsNotTheSumOfTheRoleCounts() {
        val snapshot = SessionContextModel.project(
            session = JSONObject().put("id", "ses_1"),
            messages = listOf(
                row("msg_0", "system"),
                row("msg_1", "user"),
                row("msg_2", "assistant"),
                row("msg_3", "assistant"),
            ),
        )
        val frames = valueOf(snapshot, Kind.MESSAGES).toInt()
        val roles = valueOf(snapshot, Kind.USER_MESSAGES).toInt() +
            valueOf(snapshot, Kind.ASSISTANT_MESSAGES).toInt()
        assertEquals(4, frames)
        assertEquals(3, roles)
        assertNotEquals("the label must explain this gap, not the value hide it", frames, roles)
    }

    // ------------------------------------------------------------ the label

    @Test
    fun everyLocaleStatesThatTheRowCountsAllFrames() {
        fun value(dir: String, name: String): String =
            Regex("""<string name="$name">(.*?)</string>""")
                .find(source("src/main/res/$dir/strings.xml"))
                ?.groupValues
                ?.get(1)
                ?: error("$dir missing $name")

        for (dir in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val label = value(dir, "oc_context_stats_messages")
            assertNotEquals(
                "$dir: the top row must not read as a plain message count next to the role rows",
                "Messages",
                label,
            )
            assertTrue(
                "$dir: the label must name the frame口径",
                label.contains("帧") || label.contains("幀") || label.contains("frame"),
            )
            // The role rows keep their own names — the three must stay distinct.
            assertNotEquals(label, value(dir, "oc_context_stats_user_messages"))
            assertNotEquals(label, value(dir, "oc_context_stats_assistant_messages"))
        }
        assertEquals("Messages (all frames)", value("values", "oc_context_stats_messages"))
        assertEquals("消息数（全部帧）", value("values-zh-rCN", "oc_context_stats_messages"))
        assertEquals("訊息數（全部幀）", value("values-zh-rTW", "oc_context_stats_messages"))
    }

    // -------------------------------------------------------- the source rule

    @Test
    fun theRuleAndItsUpstreamEvidenceStayInTheSource() {
        val model = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/context/SessionContextModel.kt")
        assertTrue("the projection must count every frame", model.contains("Stat(Kind.MESSAGES, number(messages.size.toLong()))"))
        assertTrue("and say why, with the upstream line", model.contains("counts().all"))
        val panel = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/context/SessionContextPanel.kt")
        assertTrue(panel.contains("SessionContextModel.Kind.MESSAGES -> R.string.oc_context_stats_messages"))
    }
}
