package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.export.IMPORT_DETAIL_CAP
import com.xiufeigo.opencodeshell.ui.session.export.ImportFailure
import com.xiufeigo.opencodeshell.ui.session.export.ImportFinish
import com.xiufeigo.opencodeshell.ui.session.export.MESSAGE_ID_PREFIX
import com.xiufeigo.opencodeshell.ui.session.export.SESSION_ID_PREFIX
import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import com.xiufeigo.opencodeshell.ui.session.export.SessionImporter
import com.xiufeigo.opencodeshell.ui.session.export.hasUnimportableRows
import com.xiufeigo.opencodeshell.ui.session.export.mintTransferId
import com.xiufeigo.opencodeshell.ui.session.export.parseTransfer
import com.xiufeigo.opencodeshell.ui.session.export.postSessionImport
import com.xiufeigo.opencodeshell.ui.session.export.rekeyForImport
import com.xiufeigo.opencodeshell.ui.session.export.transferFrame
import com.xiufeigo.opencodeshell.v2.SessionTransferData
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.sessionExport
import com.xiufeigo.opencodeshell.v2.sessionImport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * The export→import roundtrip the device could not close (v36 tB: both the
 * official and the sanitized file toasted 「Import failed」 and no session
 * appeared).
 *
 * Root cause, measured against the real route (`ServerFetch` + 2.0.11 in-memory
 * DB, same `SessionTransfer.import` the user's server runs):
 *
 * | request | answer |
 * |---|---|
 * | the export's own `info.id` | `409 ConflictError` "Session already exists: ses_…" |
 * | a fresh session id, the export's message ids | `500` (`session_message.id` is a PRIMARY KEY) |
 * | a fresh session id **and** fresh message ids | `200` + the new session |
 *
 * A file just exported carries the **source** session's id, which exists on the
 * server the app exported from — so the collision was guaranteed, not unlucky.
 * [SessionImporter] now re-keys both halves ([rekeyForImport]) before the POST;
 * these tests pin that roundtrip end to end on the wire, plus the failure
 * classification the toast reads.
 */
class SessionImportRoundtripTest {

    // --------------------------------------------------------------- harness

    private fun api(server: MockWebServer, directory: String? = null) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = null,
        password = null,
        http = V2Api.defaultClient(),
        directory = directory,
    )

    /** The server entry the app would have stored for [server]. */
    private fun serverConfig(server: MockWebServer) =
        ServerConfigStore.Server(server.url("/").toString(), null, null)

    /**
     * A 2.0.11 export body, shape-faithful to a measured one: the public
     * `Session.Info` (with `subpath`, `metadata`, `permissions`, `revert`, and a
     * `fork` boundary) plus the flat tagged `SessionMessage.Info` rows. Only ids
     * and free text are shortened.
     */
    private val exportBody = """
        {"data":{"info":{"id":"ses_src","projectID":"p1","agent":"build",
         "model":{"id":"m","providerID":"p","variant":"default"},"cost":0.25,
         "tokens":{"input":8234,"output":6,"reasoning":0,"cache":{"read":113,"write":0}},
         "outcome":"succeeded",
         "time":{"created":1790068013215,"updated":1790068016441,"idle":1790068016441,"viewed":1790068016500},
         "title":"Probe","subpath":"program",
         "location":{"directory":"C:/proj"},
         "metadata":{"k":"v"},"permissions":[{"action":"edit","resource":"*","effect":"allow"}],
         "revert":{"messageID":"msg_u1","files":[{"file":"a.kt","status":"modified","additions":1,"deletions":2,"patch":"p"}]},
         "fork":{"sessionID":"ses_origin","boundary":{"type":"through","messageID":"msg_a1"}}},
         "messages":[
          {"id":"msg_u1","time":{"created":1790068013215},"text":"hi","type":"user"},
          {"id":"msg_a1","time":{"created":1790068013300,"completed":1790068016441},"type":"assistant",
           "agent":"build","model":{"id":"m","providerID":"p","variant":"default"},
           "content":[{"type":"text","id":"pt1","text":"yo"}],"cost":0,
           "tokens":{"input":1,"output":2,"reasoning":0,"cache":{"read":0,"write":0}}}]}}
    """.trimIndent()

    /** The sanitized form of the same export (`?sanitize=true`): redacted text, ids and times real. */
    private val sanitizedBody = """
        {"data":{"info":{"id":"ses_src","projectID":"p1","cost":0,
         "tokens":{"input":1,"output":2,"reasoning":0,"cache":{"read":0,"write":0}},
         "time":{"created":1790068013215,"updated":1790068016441},
         "title":"[redacted:session-title:ses_src]","location":{"directory":"/[redacted:session-directory:ses_src]"}},
         "messages":[{"id":"msg_u1","type":"user","time":{"created":1790068013215},
          "text":"[redacted:text:msg_u1]","metadata":{"redacted":"message-metadata:msg_u1"}}]}}
    """.trimIndent()

    private fun importer(
        import: suspend (SessionTransferData) -> String? = { "ses_new" },
        sleeper: suspend (Long) -> Unit = {},
    ) = SessionImporter(
        readBytes = { error("tests drive readAndImport directly") },
        launchOpenDocument = {},
        importTransfer = import,
        sleeper = sleeper,
    )

    /** The bytes the SAF saver would have written for one export — the file the user picks back. */
    private fun artifactOf(transfer: SessionTransferData): String =
        SessionExport.serialize(SessionExport.transferFrame(transfer))

    // ------------------------------------------------------- the roundtrip

    @Test
    fun theExportedArtifactParsesAndImportsAsANewSession() = runBlocking {
        MockWebServer().use { server ->
            val api = api(server, directory = "/w")
            server.enqueue(MockResponse().setResponseCode(200).setBody(exportBody))
            val artifact = artifactOf(api.sessionExport("ses_src"))

            // The saver's own output must be consumable by the import's parser.
            val parsed = SessionExport.parseTransfer(artifact)
            assertTrue("the export artifact must parse back", parsed != null)
            assertEquals("ses_src", parsed?.info?.optString("id"))
            assertEquals(2, parsed?.messages?.size)
            assertFalse("the route's rows are the flat shape", SessionExport.hasUnimportableRows(parsed!!))

            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SESSION_CREATED))
            // The production thunk verbatim: it is what re-keys the document.
            val outcome = importer(import = { postSessionImport(serverConfig(server), null, it) })
                .readAndImport { artifact }
            assertEquals(ImportFinish.Imported("ses_f3d84f9d4ffeoLtv5OmiMrU6yO"), outcome)

            val export = server.takeRequest()
            assertEquals("/api/experimental/session/ses_src/export", export.requestUrl!!.encodedPath)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/experimental/session/import", request.requestUrl!!.encodedPath)
            assertNull(
                "import carries location in the body, never location[directory]",
                request.requestUrl!!.queryParameter("location[directory]"),
            )
            assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(";"))

            val body = JSONObject(request.body.readUtf8())
            val info = body.getJSONObject("info")
            val rows = body.getJSONArray("messages")
            // the identity is re-minted on both halves …
            assertNotEquals("ses_src", info.getString("id"))
            assertTrue(info.getString("id").startsWith(SESSION_ID_PREFIX))
            assertEquals(2, rows.length())
            val fresh = (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }
            assertTrue("message ids must be re-minted", fresh.none { it in setOf("msg_u1", "msg_a1") })
            assertTrue("message ids must keep the route's prefix", fresh.all { it.startsWith(MESSAGE_ID_PREFIX) })
            assertEquals("the two rows must not share an id", 2, fresh.toSet().size)
            // … and the transcript itself rides through verbatim.
            assertEquals("Probe", info.getString("title"))
            assertEquals("p1", info.getString("projectID"))
            assertEquals(1790068013215L, info.getJSONObject("time").getLong("created"))
            assertEquals("C:/proj", info.getJSONObject("location").getString("directory"))
            assertEquals("succeeded", info.getString("outcome"))
            assertEquals("v", info.getJSONObject("metadata").getString("k"))
            assertEquals(0.25, info.getDouble("cost"), 1e-9)
            assertEquals("user", rows.getJSONObject(0).getString("type"))
            assertEquals("hi", rows.getJSONObject(0).getString("text"))
            assertEquals("assistant", rows.getJSONObject(1).getString("type"))
            assertEquals("yo", rows.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text"))
            assertTrue("every row must keep the route's tagged shape", (0 until rows.length()).all { rows.getJSONObject(it).has("type") })
            // revert/fork name message ids, so they follow the rename
            assertEquals(fresh[0], info.getJSONObject("revert").getString("messageID"))
            assertEquals(fresh[1], info.getJSONObject("fork").getJSONObject("boundary").getString("messageID"))
            assertEquals("ses_origin", info.getJSONObject("fork").getString("sessionID"))
            // the default location is the server's; the app sends none
            assertFalse(body.has("location"))
        }
    }

    @Test
    fun importingTheSameArtifactTwiceCreatesTwoDistinctSessions() = runBlocking {
        MockWebServer().use { server ->
            val api = api(server)
            server.enqueue(MockResponse().setResponseCode(200).setBody(exportBody))
            val artifact = artifactOf(api.sessionExport("ses_src"))
            server.takeRequest()

            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SESSION_CREATED))
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SESSION_CREATED))
            val importer = importer(import = { postSessionImport(serverConfig(server), null, it) })
            assertEquals(ImportFinish.Imported("ses_f3d84f9d4ffeoLtv5OmiMrU6yO"), importer.readAndImport { artifact })
            assertEquals(ImportFinish.Imported("ses_f3d84f9d4ffeoLtv5OmiMrU6yO"), importer.readAndImport { artifact })

            val first = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("info").getString("id")
            val second = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("info").getString("id")
            assertNotEquals("a re-import must not re-use the id it already created (409)", first, second)
        }
    }

    @Test
    fun theSanitizedArtifactRoundtripsToo() = runBlocking {
        MockWebServer().use { server ->
            val api = api(server)
            server.enqueue(MockResponse().setResponseCode(200).setBody(sanitizedBody))
            val artifact = artifactOf(api.sessionExport("ses_src", sanitize = true))
            assertEquals("true", server.takeRequest().requestUrl!!.queryParameter("sanitize"))

            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SESSION_CREATED))
            val outcome = importer(import = { postSessionImport(serverConfig(server), null, it) })
                .readAndImport { artifact }
            assertEquals(ImportFinish.Imported("ses_f3d84f9d4ffeoLtv5OmiMrU6yO"), outcome)

            val body = JSONObject(server.takeRequest().body.readUtf8())
            val info = body.getJSONObject("info")
            assertNotEquals("ses_src", info.getString("id"))
            assertEquals("[redacted:session-title:ses_src]", info.getString("title"))
            assertEquals("/[redacted:session-directory:ses_src]", info.getJSONObject("location").getString("directory"))
            val row = body.getJSONArray("messages").getJSONObject(0)
            assertNotEquals("msg_u1", row.getString("id"))
            assertEquals("[redacted:text:msg_u1]", row.getString("text"))
            assertEquals("message-metadata:msg_u1", row.getJSONObject("metadata").getString("redacted"))
        }
    }

    // ------------------------------------------------------------ the re-key

    @Test
    fun theReKeyKeepsEverythingButTheIdentity() {
        val source = SessionTransferData(
            info = JSONObject("""{"id":"ses_src","parentID":"ses_parent","title":"T"}"""),
            messages = listOf(JSONObject("""{"id":"msg_1","type":"user","text":"hi"}""")),
        )
        val rekeyed = SessionExport.rekeyForImport(source, sessionId = "ses_fresh") { "msg_fresh" }

        assertEquals("ses_fresh", rekeyed.info.getString("id"))
        assertEquals("ses_parent", rekeyed.info.getString("parentID"))
        assertEquals("T", rekeyed.info.getString("title"))
        assertEquals("msg_fresh", rekeyed.messages[0].getString("id"))
        assertEquals("user", rekeyed.messages[0].getString("type"))
        assertEquals("hi", rekeyed.messages[0].getString("text"))
        // the caller's document is never written through
        assertEquals("ses_src", source.info.getString("id"))
        assertEquals("msg_1", source.messages[0].getString("id"))
    }

    @Test
    fun aBoundaryWhoseMessageIsNotInTheTranscriptIsDroppedNotLeftDangling() {
        val source = SessionTransferData(
            info = JSONObject(
                """{"id":"ses_src","revert":{"messageID":"msg_gone"},"fork":{"sessionID":"ses_o","boundary":{"type":"before","messageID":"msg_1"}}}""",
            ),
            messages = listOf(JSONObject("""{"id":"msg_1","type":"user","text":"hi"}""")),
        )
        val rekeyed = SessionExport.rekeyForImport(source, sessionId = "ses_fresh") { "msg_fresh" }

        assertFalse("a dangling revert boundary must go", rekeyed.info.has("revert"))
        assertEquals("msg_fresh", rekeyed.info.getJSONObject("fork").getJSONObject("boundary").getString("messageID"))
    }

    @Test
    fun aMessageRowWithoutAnIdGetsOneInsteadOfFailingTheRoute() {
        val source = SessionTransferData(
            info = JSONObject("""{"id":"ses_src"}"""),
            messages = listOf(JSONObject("""{"type":"user","text":"hi"}""")),
        )
        val rekeyed = SessionExport.rekeyForImport(source, sessionId = "ses_fresh") { "msg_fresh" }
        assertEquals("msg_fresh", rekeyed.messages[0].getString("id"))
    }

    @Test
    fun mintedIdsWearTheServersShape() {
        val now = 1_790_068_013_215L
        val id = mintTransferId(SESSION_ID_PREFIX, now = now, random = Random(7))
        assertTrue(id.startsWith(SESSION_ID_PREFIX))
        assertEquals("ses_ + 26 chars, the shape the server mints", 30, id.length)
        assertEquals(
            "the first six bytes are the millisecond (`identifier.ts:20-25`)",
            now and 0xffffffffffffL,
            id.substring(4, 16).toLong(16),
        )
        val tail = id.substring(16)
        assertTrue("the tail is base62", tail.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' })
        assertNotEquals(
            "two ids minted in the same millisecond must differ",
            id,
            mintTransferId(SESSION_ID_PREFIX, now = now, random = Random(8)),
        )
    }

    // ------------------------------------------------- the failure reasons

    @Test
    fun everyFailureNamesItsStage() = runBlocking {
        assertEquals(
            ImportFailure.FILE_UNREADABLE,
            (importer().readAndImport { null } as ImportFinish.Failed).reason,
        )
        assertEquals(
            ImportFailure.FILE_FORMAT,
            (importer().readAndImport { "nope" } as ImportFinish.Failed).reason,
        )
        assertEquals(
            ImportFailure.FILE_FORMAT,
            (importer().readAndImport { """{"messages":[]}""" } as ImportFinish.Failed).reason,
        )
        // the hand-rolled export's rows: measured 400 "Expected Session.Message.Info at ["messages"][0]"
        assertEquals(
            ImportFailure.FILE_ASSEMBLED,
            (importer().readAndImport { assembledArtifact } as ImportFinish.Failed).reason,
        )
        assertEquals(
            ImportFailure.NO_SERVER,
            (importer(import = { null }).readAndImport { validArtifact } as ImportFinish.Failed).reason,
        )
        assertEquals(
            ImportFailure.UNKNOWN,
            (importer(import = { error("boom") }).readAndImport { validArtifact } as ImportFinish.Failed).reason,
        )
        assertEquals(
            ImportFailure.NETWORK,
            (importer(import = { throw IOException("connection reset") }).readAndImport { validArtifact } as ImportFinish.Failed).reason,
        )
        // the timeout keeps the pre-existing `timedOut` flag in step with its reason
        val stalled = importer(sleeper = {}).readAndImport { CompletableDeferred<String?>().await() }
        assertEquals(ImportFailure.TIMEOUT, (stalled as ImportFinish.Failed).reason)
        assertTrue(stalled.timedOut)
    }

    @Test
    fun aServerRefusalCarriesTheServersOwnWords() = runBlocking {
        MockWebServer().use { server ->
            val api = api(server)
            server.enqueue(
                MockResponse().setResponseCode(409)
                    .setBody("""{"_tag":"ConflictError","message":"Session already exists: ses_src"}"""),
            )
            val failed = importer(import = { api.sessionImport(it).id })
                .readAndImport { validArtifact } as ImportFinish.Failed
            assertEquals(ImportFailure.SERVER, failed.reason)
            assertFalse("a refusal is not a timeout", failed.timedOut)
            val detail = failed.detail.orEmpty()
            assertTrue("the toast shows the server's reason: $detail", detail.contains("409"))
            assertTrue(detail.contains("Session already exists: ses_src"))
        }
    }

    @Test
    fun theServerDetailIsCappedForAToast() {
        assertTrue("a wall of JSON must not reach the toast", IMPORT_DETAIL_CAP in 40..400)
    }

    @Test
    fun anEmptyMessagesHalfIsNeverAFormatFailure() {
        val transfer = SessionExport.parseTransfer("""{"info":{"id":"ses_1"}}""")!!
        assertFalse(SessionExport.hasUnimportableRows(transfer))
        // A bare `{info: …}` row is not the assembled export's `{info, parts}`
        // pair: the pre-existing fixtures use that shape and stay importable.
        val bare = SessionExport.parseTransfer("""{"info":{"id":"ses_1"},"messages":[{"info":{"id":"m1"}}]}""")!!
        assertFalse(SessionExport.hasUnimportableRows(bare))
        val assembled = SessionExport.parseTransfer(assembledArtifact)!!
        assertTrue(SessionExport.hasUnimportableRows(assembled))
    }

    // ------------------------------------------------------------ the copy

    /** The exact artifact 「导出会话」 (ASSEMBLED) writes: `{info, parts}` rows. */
    private val assembledArtifact = SessionExport.serialize(
        SessionExport.Frame(
            info = JSONObject("""{"id":"ses_src"}"""),
            messages = listOf(
                JSONObject()
                    .put("info", JSONObject("""{"id":"msg_1","sessionID":"ses_src","role":"user"}"""))
                    .put("parts", JSONArray().put(JSONObject("""{"type":"text","text":"hi"}"""))),
            ),
        ),
    )

    private val validArtifact = """{"info":{"id":"ses_src"},"messages":[{"id":"msg_1","type":"user","text":"hi"}]}"""

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    @Test
    fun bothImportCallSitesShowTheReasonCopy() {
        val keys = listOf(
            "oc_session_import_unreadable",
            "oc_session_import_bad_file",
            "oc_session_import_assembled",
            "oc_session_import_rejected",
            "oc_session_import_offline",
            "oc_session_import_no_server",
        )
        for (file in listOf(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/context/SessionContextPanel.kt",
            "src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeRowActions.kt",
        )) {
            val body = source(file)
            assertTrue("$file must keep the timeout line", body.contains("R.string.oc_session_import_timeout"))
            assertTrue("$file must keep a generic fallback", body.contains("R.string.oc_session_import_failed"))
            for (key in keys) {
                assertTrue("$file must render R.string.$key", body.contains("R.string.$key"))
            }
            assertTrue(
                "$file must pass the server's reason into the refusal line",
                body.contains("R.string.oc_session_import_rejected, finish.detail.orEmpty()"),
            )
        }
        // every reason has an arm: a new ImportFailure member must fail to compile here
        for (reason in ImportFailure.entries) {
            assertTrue(
                "$reason needs a copy arm in the panel",
                source("src/main/java/com/xiufeigo/opencodeshell/ui/session/context/SessionContextPanel.kt")
                    .contains("ImportFailure.$reason ->"),
            )
        }
    }

    @Test
    fun everyReasonCopyExistsInAllThreeLocales() {
        val keys = listOf(
            "oc_session_import_unreadable",
            "oc_session_import_bad_file",
            "oc_session_import_assembled",
            "oc_session_import_rejected",
            "oc_session_import_offline",
            "oc_session_import_no_server",
        )
        for (dir in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val body = source("src/main/res/$dir/strings.xml")
            for (key in keys) {
                val value = Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .find(body)
                    ?.groupValues
                    ?.get(1)
                assertTrue("$dir is missing a non-blank $key", !value.isNullOrBlank())
            }
            assertTrue(
                "$dir: the refusal line must keep its server-reason slot",
                body.contains("""<string name="oc_session_import_rejected">""") &&
                    Regex("""<string name="oc_session_import_rejected">[^<]*%1\${'$'}s""").containsMatchIn(body),
            )
        }
        // the shipped copy is localized, not the English line again
        for (dir in listOf("values-zh-rCN", "values-zh-rTW")) {
            val body = source("src/main/res/$dir/strings.xml")
            assertTrue("$dir: the refusal line is still English", !body.contains("the server refused it"))
        }
    }
}
