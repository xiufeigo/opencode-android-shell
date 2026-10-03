package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.ToolPresentation
import com.xiufeigo.opencodeshell.ui.session.timeline.optStringOrNull
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.v2StringList
import com.xiufeigo.opencodeshell.v2.optStringOrNull as v2OptStringOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `org.json` double-runtime trap, swept (fourth occurrence).
 *
 * `JSONObject.optString(name, fallback)` behaves differently on the two
 * `org.json` builds this app runs on: Android funnels the value through
 * `JSON.toString`, so the JSON `null` sentinel comes back as the **literal
 * string `"null"`** — only a *missing* key reaches the fallback — while
 * `org.json:json` (the JVM build the unit tests run on) returns the fallback for
 * the same payload. The device therefore renders `null` where the tests see an
 * empty string. Three incidents so far: `Cursor.previous`/`next` twice (a
 * `?cursor=null` drain that answered `400 InvalidCursorError` on every export)
 * and `ChatStore.errorMessageOf` (a card header reading `null`).
 *
 * Every optional string read in the touched files now goes through a null-safe
 * reader — `v2.optStringOrNull`, `ChatStore.optStr`, and the timeline package's
 * `JSONObject?.optStringOrNull` — all of which use [JSONObject.isNull] (the half
 * both builds agree on) and additionally treat a server-sent literal `"null"` as
 * absent. This test pins both shapes on every reader that was swept.
 *
 * The last test documents the trap itself, so a future reader cannot "simplify"
 * a helper back to `optString` and stay green: it asserts the *plain* reader's
 * behavior for the literal shape, which is wrong on every runtime.
 */
class JsonNullStringTrapTest {

    /** The two shapes a field can arrive as when it carries no value. */
    private fun absentShapes(key: String, value: String = "null") = listOf(
        // The wire's JSON null — Android stringifies it to the literal "null".
        JSONObject("""{"$key":null}"""),
        // A server that serialized the token as a string.
        JSONObject("""{"$key":"$value"}"""),
        // …and the field simply missing.
        JSONObject("""{}"""),
    )

    @Test
    fun thePlainReaderIsTheTrap() {
        // Documents why no reader may use `optString` for an optional field: the
        // literal shape survives `takeIf { it.isNotEmpty() }` on BOTH runtimes,
        // and the JSON-null shape survives it on Android (not reproducible here —
        // the JVM build under test returns the fallback, which is exactly why the
        // bug shipped three times).
        val literal = JSONObject("""{"title":"null"}""")
        assertEquals("null", literal.optString("title", ""))
        assertEquals("null", literal.optString("title", "").takeIf { it.isNotEmpty() })

        val jsonNull = JSONObject("""{"title":null}""")
        // On Android this is the string "null"; on the JVM (here) the fallback.
        assertTrue(jsonNull.isNull("title"))
    }

    @Test
    fun bothReadersAnswerNullForEveryAbsentShape() {
        for (shape in absentShapes("title")) {
            assertNull(v2OptStringOrNull(shape, "title"))
            assertNull(shape.optStringOrNull("title"))
        }
        // A real value still comes through verbatim.
        val present = JSONObject("""{"title":"Hostname lookup"}""")
        assertEquals("Hostname lookup", v2OptStringOrNull(present, "title"))
        assertEquals("Hostname lookup", present.optStringOrNull("title"))
        // A null *receiver* is absent too, so `parent?.opt…` needs no extra guard.
        val missing: JSONObject? = null
        assertNull(missing.optStringOrNull("title"))
        assertNull(v2OptStringOrNull(null, "title"))
    }

    @Test
    fun theLivenessDocNeverReportsNullAsAVersion() {
        // `GET /api/info` → `{"version":null}`: "null" would read as a version
        // marker, i.e. as a healthy v2 server.
        val health = V2Api.Health.fromJson(JSONObject("""{"version":null,"pid":1}"""))
        assertNull(health.version)
        assertFalse(health.healthy)
        assertEquals("2.0.11", V2Api.Health.fromJson(JSONObject("""{"version":"2.0.11"}""")).version)
        // The legacy document's flag is unaffected.
        assertTrue(V2Api.Health.fromJson(JSONObject("""{"healthy":true}""")).healthy)
    }

    @Test
    fun theV2ReadersDropNullStrings() {
        assertNull(V2Api.ModelRef.fromJson(JSONObject("""{"id":"m","providerID":"p","variant":null}""")).variant)
        assertNull(
            V2Api.LocationInfo.fromJson(JSONObject("""{"directory":null,"workspaceID":null,"project":null}"""))
                .workspaceID,
        )

        val session = V2Api.SessionInfo.fromJson(
            JSONObject(
                """{"id":"ses_1","projectID":"p","parentID":null,"agent":null,
                   "title":null,"subpath":null,"location":{"directory":null}}""",
            ),
        )
        assertNull(session.parentID)
        assertNull(session.agent)
        assertNull(session.directory)
        assertNull(session.subpath)
        // A null title still gets the placeholder, never the word "null".
        assertEquals(V2Api.SessionInfo.UNTITLED, session.title)

        // The durable `message` envelope: `has("text")` is true for a JSON null,
        // so the key check alone must not be trusted.
        val durable = V2Api.Message.fromJson(
            JSONObject("""{"id":"msg_1","type":"assistant","text":null,"command":null,"output":null,"summary":null}"""),
        )
        assertEquals("", durable.text)
        assertNull(durable.command)
        assertNull(durable.output)
        assertNull(durable.compactionSummary)

        val admitted = V2Api.SessionAdmitted.fromJson(
            JSONObject("""{"id":"msg_1","sessionID":"ses_1","payload":{"text":null},"delivery":null}"""),
        )
        assertEquals("", admitted.text)
        assertNull(admitted.delivery)

        val model = V2Api.ModelInfo.fromJson(
            JSONObject("""{"id":"m","providerID":"p","modelID":null,"name":null,"family":null,"status":null}"""),
        )
        assertEquals("m", model.modelID)
        assertEquals("m", model.name)
        assertNull(model.family)
        assertNull(model.status)

        val provider = V2Api.ProviderInfo.fromJson(
            JSONObject("""{"id":"p","name":"P","activation":null,"integrationID":null,"package":null,"api":null}"""),
        )
        assertNull(provider.activation)
        assertNull(provider.integrationID)
        assertNull(provider.pkg)
        assertNull(provider.apiType)

        assertNull(V2Api.SkillInfo.fromJson(JSONObject("""{"name":"s","path":null,"content":null}"""))?.content)
        assertNull(V2Api.VcsInfo.fromJson(JSONObject("""{"branch":{"current":null,"default":null}}"""))?.current)

        val revert = V2Api.RevertState.fromJson(
            JSONObject("""{"messageID":"msg_1","partID":null,"snapshot":null,"diff":null}"""),
        )
        assertNull(revert.partID)
        assertNull(revert.snapshot)
        assertNull(revert.diff)

        val permission = V2Api.PermissionRequest.fromJson(
            JSONObject("""{"id":"per_1","sessionID":"ses_1","action":"write","source":null}"""),
        )
        assertNull(permission.callID)
        assertNull(permission.messageID)

        val event = V2Api.DurableEvent.fromJson(
            JSONObject("""{"id":"evt_1","type":"session.next.text.ended","durable":null,"location":null,"data":{}}"""),
        )
        assertNull(event.aggregateID)
        assertNull(event.locationDirectory)
        assertNull(event.sessionID)
        assertNull(event.assistantMessageID)
        assertNull(event.text)
    }

    @Test
    fun theV2ListReaderDropsNullElements() {
        // `resources: [null, "src/", "null"]` — the literal and the sentinel must
        // both fall out, or a permission row lists a resource named "null".
        val list = v2StringList(JSONArray("""[null,"src/","null",""]"""))
        assertEquals(listOf("src/"), list)
    }

    @Test
    fun theErrorBodyNeverPrintsTheWordNull() {
        val api = V2Api("http://127.0.0.1:1/".toHttpUrl())
        // A null `_tag` must not become a "null: " prefix — on Android the plain
        // reader produced exactly that, so the card read `HTTP 429 null: boom`.
        assertEquals("HTTP 429 boom", api.errorText(429, """{"_tag":null,"message":"boom"}"""))
        // Every readable field null → no human line at all, so the detail falls
        // back to the raw body (the JSON's own `null` tokens are the wire's bytes,
        // not a value this code rendered).
        assertEquals(
            """HTTP 429 {"_tag":null,"message":null,"name":null}""",
            api.errorText(429, """{"_tag":null,"message":null,"name":null}"""),
        )
        assertNull(api.errorTypeOf("""{"type":null}"""))
        assertEquals(
            "HTTP 429 [rate_limit_error] Rate limit exceeded, top up",
            api.errorText(429, """{"type":"rate_limit_error","message":"Rate limit exceeded, top up"}"""),
        )
    }

    @Test
    fun theChatStoreReadersDropNullStrings() {
        // Tool part display fields: a `{"tool":null,"filename":null}` part would
        // otherwise render the tool's name as "null" on the card.
        val part = ChatStore.parsePart(
            JSONObject("""{"id":"prt_1","type":"tool","tool":null,"filename":null,"mime":null,"url":null,
                          "state":{"status":null,"error":null}}"""),
        )
        assertNull(part.toolName)
        assertNull(part.filename)
        assertNull(part.mime)
        assertNull(part.url)
        assertNull(part.toolStatus)
        assertNull(part.toolError)

        // The model chip: "null/m" (or "/text-embedding") must never be built.
        val message = ChatStore.parseMessage(
            JSONObject("""{"info":{"id":"msg_1","role":"assistant","providerID":null,"modelID":"m"},"parts":[]}"""),
        )!!
        assertEquals("m", message.model)
        assertNull(message.agent)

        // Abort detection: a null discriminator must not classify the turn.
        assertFalse(ChatStore.isInterrupted(JSONObject("""{"name":null,"type":null}""")))
        assertFalse(ChatStore.isInterrupted(JSONObject("""{"name":"null"}""")))
        assertTrue(ChatStore.isInterrupted(JSONObject("""{"name":"MessageAbortedError"}""")))

        // Frame routing: a `{"sessionID":null}` frame carries no routing, and
        // must never be attributed to a session called "null".
        assertNull(ChatStore.eventSessionId(JSONObject("""{"data":{"sessionID":null}}""")))
        assertNull(ChatStore.eventSessionId(JSONObject("""{"data":{},"durable":{"aggregateID":null}}""")))

        // The retry / upsell payload: label and link are display strings.
        val retry = ChatStore.RetryInfo.fromJson(
            JSONObject(
                """{"attempt":2,"next":5,"message":null,
                   "action":{"reason":null,"provider":null,"title":null,"message":null,"label":null,"link":null}}""",
            ),
        )
        assertNull(retry.message)
        val action = retry.action!!
        assertEquals("", action.label)
        assertEquals("", action.title)
        assertNull(action.link)
    }

    @Test
    fun theTimelineToolReadersDropNullStrings() {
        // The bash card's command line (`ToolPresentation.subtitle`).
        val subtitle = ToolPresentation.subtitle(
            ChatStore.UiPart(
                id = "prt_1",
                type = "tool",
                toolName = "bash",
                toolInput = """{"command":null,"filePath":null}""",
            ),
        )
        assertNull(subtitle)

        val part = ChatStore.UiPart(
            id = "prt_2",
            type = "tool",
            toolName = "bash",
            toolInput = """{"command":"sleep 5"}""",
        )
        assertEquals("sleep 5", ToolPresentation.subtitle(part))
    }
}
