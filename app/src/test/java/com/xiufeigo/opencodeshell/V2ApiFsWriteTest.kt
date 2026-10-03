package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.fsWrite
import com.xiufeigo.opencodeshell.v2.fsWriteUpload
import com.xiufeigo.opencodeshell.v2.octetStreamBody
import com.xiufeigo.opencodeshell.v2.tempDirectory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * `POST /api/experimental/fs/write` (`composer/attachments/destination.ts:24-31`,
 * `protocol/src/groups/fs.ts:76-89`) and its upload-root helpers — asserted on
 * the wire against [MockWebServer], same harness as [V2ApiTest]. `UNVERIFIED`
 * live: the contract is the openapi document.
 */
class V2ApiFsWriteTest {

    private fun api(
        server: MockWebServer,
        directory: String? = null,
        username: String? = null,
        password: String? = null,
    ) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = username,
        password = password,
        http = V2Api.defaultClient(),
        directory = directory,
    )

    @Test
    fun writePostsOctetStreamBodyWithPathAndLocationQueries() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"location":{"directory":"/w"},"data":{"path":"/tmp/opencode/uploads/u1/report.pdf"}}"""),
            )
            val resolved = api(server, directory = "/w").fsWrite(
                path = "/tmp/opencode/uploads/u1/report.pdf",
                bytes = byteArrayOf(1, 2, 3),
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/experimental/fs/write", request.requestUrl!!.encodedPath)
            assertEquals("/tmp/opencode/uploads/u1/report.pdf", request.requestUrl!!.queryParameter("path"))
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("application/octet-stream", request.headers["Content-Type"]?.substringBefore(";"))
            assertTrue(
                "raw body bytes, not base64 or multipart",
                request.body.readByteArray().contentEquals(byteArrayOf(1, 2, 3)),
            )
            assertEquals("/tmp/opencode/uploads/u1/report.pdf", resolved)
        }
    }

    @Test
    fun streamWriteSendsBasicAuthAndWritesTheStreamBytes() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"data":{"path":"/tmp/a/b.txt"}}"""),
            )
            val resolved = api(server, password = "secret").fsWrite(
                path = "/tmp/a/b.txt",
                body = "hello".byteInputStream().octetStreamBody(),
            )

            val request = server.takeRequest()
            val expected = "Basic " + Base64.getEncoder().encodeToString("opencode:secret".toByteArray())
            assertEquals(expected, request.getHeader("Authorization"))
            assertEquals("hello", request.body.readUtf8())
            assertEquals("/tmp/a/b.txt", resolved)
        }
    }

    @Test
    fun writeWithoutAConfiguredDirectorySendsNoLocationParameter() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"path":"/tmp/x"}}"""))
            api(server).fsWrite(path = "/tmp/x", bytes = ByteArray(0))

            val request = server.takeRequest()
            assertNull(request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("/tmp/x", request.requestUrl!!.queryParameter("path"))
            assertEquals(0L, request.bodySize)
        }
    }

    @Test
    fun writeUploadComposesOneTempSubdirectoryPerPick() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"path":"/tmp/opencode/uploads/id-1/report.pdf"}}"""))
            api(server, directory = "/w").fsWriteUpload(
                filename = "report.pdf",
                body = "pdf".byteInputStream().octetStreamBody(),
                tmpDirectory = "/tmp/opencode",
            )

            val request = server.takeRequest()
            val path = request.requestUrl!!.queryParameter("path")!!
            val pattern = Regex("""^/tmp/opencode/uploads/[^/]+/report\.pdf$""")
            assertTrue("upstream tmp/uploads/<uuid>/<name> shape, got $path", pattern.matches(path))
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
        }
    }

    @Test
    fun tempDirectoryReadsPathsTmpFromTheBareInfoEnvelope() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.INFO_V2))
            assertEquals("/tmp/opencode", api(server).tempDirectory())

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/info", request.requestUrl!!.encodedPath)
        }
    }

    @Test
    fun non2xxThrowsApiExceptionAndAJsonBodyBecomesAParseError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody(V2ApiFixtures.MISSING_QUERY_ERROR))
            try {
                api(server).fsWrite(path = "p", bytes = ByteArray(0))
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(400, e.code)
                assertTrue(e.message!!.contains("InvalidRequestError"))
            }

            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SPA_HTML))
            try {
                api(server).fsWrite(path = "p", bytes = ByteArray(0))
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(
                    "unimplemented paths answer the SPA shell as 200 html",
                    V2Api.JSON_BODY_CODE,
                    e.code,
                )
            }
        }
    }
}
