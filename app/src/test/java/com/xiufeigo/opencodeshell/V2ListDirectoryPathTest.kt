package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2Transport
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `V2Transport.listDirectory` rides the anchor contract: the filesystem root
 * (`/` on POSIX, `C:/` on Windows — derived from one `GET /api/location`
 * boot) stays the `location[directory]` for every in-anchor browse, with
 * `path=` carrying the anchor-relative descent. Only an out-of-anchor target
 * (another drive / UNC share) falls back to the legacy per-directory location
 * with no `path=`.
 *
 * A *trusted* anchor (one a location read actually answered) is cached for the
 * transport's lifetime; a *guessed* one (the location read answered nothing —
 * the picker opens in the probe window) is re-derived on the next call and,
 * if it cannot list, falls through to the location-booting shape rather than
 * leaving the browser blank. A trusted anchor's failure is the target's own
 * (a missing directory is a bodiless `500`, measured live on 4095) and is
 * surfaced without booting a project row for a directory that is not there.
 *
 * Entries stay rooted at the request's location, so in-anchor bodies carry
 * the requested prefix (`?path=home/x` → `home/x/child/`); they join onto
 * the anchor, not onto the browsed directory. The cross-checks for the reuse
 * shape live in `Wave1TransportTest` (`listDirectoryReusesOneAnchorAcrossBrowses`
 * et al.); these pins keep the single-listing row mapping (names, absolutes,
 * `isDirectory`) plus the boot-then-list call sequence for the POSIX subdir,
 * the `/` root, the Windows drive root, and the three anchor-trust cases.
 */
class V2ListDirectoryPathTest {

    private fun transport(server: MockWebServer) = V2Transport(
        V2Api(
            baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
            http = V2Api.defaultClient(),
        ),
    )

    private fun locationBody(directory: String) =
        """{"directory":"$directory","project":{"id":"g","directory":"$directory"}}"""

    private fun listBody(location: String, vararg entries: String) =
        """{"location":{"directory":"$location"},"data":[${entries.joinToString(",")}]}"""

    @Test
    fun listSubdirRidesAnchorWithRelativePath() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(locationBody("/home/xiufeigo")))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    listBody(
                        "/",
                        """{"path":"home/xiufeigo/proj/.agents/","type":"directory"}""",
                        """{"path":"home/xiufeigo/proj/notes.md","type":"file"}""",
                    ),
                ),
            )
            // One `/api/location` boot (anchor `/`), then the anchor with the
            // anchor-relative descent — never the browsed dir as the location.
            val rows = transport(server).listDirectory("/home/xiufeigo/proj")
            assertEquals(2, server.requestCount)
            assertEquals("/api/location", server.takeRequest().requestUrl!!.encodedPath)
            val list = server.takeRequest()
            assertEquals("/api/fs/list", list.requestUrl!!.encodedPath)
            assertEquals("/", list.requestUrl?.queryParameter("location[directory]"))
            assertEquals("home/xiufeigo/proj", list.requestUrl?.queryParameter("path"))
            assertEquals(".agents", rows[0].name)
            assertEquals("/home/xiufeigo/proj/.agents", rows[0].absolute)
            assertEquals("/home/xiufeigo/proj/notes.md", rows[1].absolute)
            assertEquals(listOf(true, false), rows.map { it.isDirectory })
        }
    }

    @Test
    fun listRootAtAnchorWithoutPath() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(locationBody("/home/me")))
            val rootBody = listBody(
                "/",
                """{"path":"etc/","type":"directory"}""",
                """{"path":"home/","type":"directory"}""",
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(rootBody))
            server.enqueue(MockResponse().setResponseCode(200).setBody(rootBody))
            val transport = transport(server)
            val rows = transport.listDirectory("/")
            assertEquals(2, server.requestCount)
            assertEquals("/api/location", server.takeRequest().requestUrl!!.encodedPath)
            val list = server.takeRequest()
            assertEquals("/api/fs/list", list.requestUrl!!.encodedPath)
            assertEquals("/", list.requestUrl?.queryParameter("location[directory]"))
            assertNull(list.requestUrl?.queryParameter("path"))
            assertEquals(listOf("/etc", "/home"), rows.map { it.absolute })
            assertEquals(listOf("etc", "home"), rows.map { it.name })
            // The anchor is cached: relisting the root boots no new location.
            val again = transport.listDirectory("/")
            assertEquals(3, server.requestCount)
            val relist = server.takeRequest()
            assertEquals("/api/fs/list", relist.requestUrl!!.encodedPath)
            assertEquals("/", relist.requestUrl?.queryParameter("location[directory]"))
            assertNull(relist.requestUrl?.queryParameter("path"))
            assertEquals(rows.map { it.absolute }, again.map { it.absolute })
        }
    }

    @Test
    fun listDriveRootAnchorKeepsWindowsSeparators() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(locationBody("C:/Users/me")))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    listBody(
                        "C:/",
                        """{"path":"Windows\\","type":"directory"}""",
                        """{"path":"pagefile.sys","type":"file"}""",
                    ),
                ),
            )
            // Home on `C:` anchors at the drive root, so the root itself lists
            // with no `path=`; the `\`-suffixed entry keeps its Windows join.
            val rows = transport(server).listDirectory("C:/")
            assertEquals(2, server.requestCount)
            assertEquals("/api/location", server.takeRequest().requestUrl!!.encodedPath)
            val list = server.takeRequest()
            assertEquals("/api/fs/list", list.requestUrl!!.encodedPath)
            assertEquals("C:/", list.requestUrl?.queryParameter("location[directory]"))
            assertNull(list.requestUrl?.queryParameter("path"))
            assertEquals(listOf("Windows", "pagefile.sys"), rows.map { it.name })
            assertEquals(listOf("C:\\Windows", "C:/pagefile.sys"), rows.map { it.absolute })
            assertEquals(listOf(true, false), rows.map { it.isDirectory })
        }
    }

    @Test
    fun guessedAnchorFailureFallsBackToTheLocationScope() {
        MockWebServer().use { server ->
            // The picker opens in the same frame as `ensureClient`, so the
            // location read can answer nothing: the anchor is only guessed.
            server.enqueue(MockResponse().setResponseCode(500).setBody(""))
            // The guessed anchor cannot list — a wrong volume root 500s.
            server.enqueue(MockResponse().setResponseCode(500).setBody(""))
            // The location-booting shape still lists instead of blanking.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{"directory":"/home/me/proj"},"data":[{"path":"a/","type":"directory"}]}""",
                ),
            )
            val rows = transport(server).listDirectory("/home/me/proj")
            assertEquals(listOf("/home/me/proj/a"), rows.map { it.absolute })
            assertEquals(3, server.requestCount)
            assertEquals("/api/location", server.takeRequest().requestUrl!!.encodedPath)
            val anchorTry = server.takeRequest().requestUrl!!
            assertEquals("/", anchorTry.queryParameter("location[directory]"))
            assertEquals("home/me/proj", anchorTry.queryParameter("path"))
            val fallback = server.takeRequest().requestUrl!!
            assertEquals("/home/me/proj", fallback.queryParameter("location[directory]"))
            assertNull(fallback.queryParameter("path"))
        }
    }

    @Test
    fun trustedAnchorFailureSurfacesWithoutBootingAMissingDirectory() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(locationBody("/home/me")))
            // A missing directory answers a bodiless 500 on the anchor shape.
            server.enqueue(MockResponse().setResponseCode(500).setBody(""))
            val transport = transport(server)
            val failure = try {
                transport.listDirectory("/home/me/gone")
                null
            } catch (e: Exception) {
                e
            }
            // A trusted anchor is a prefix of the target by construction, so the
            // failure is the target's own: falling through would persist a
            // project row for a directory that does not exist.
            assertNotNull("the target's own failure must surface", failure)
            assertEquals(2, server.requestCount)
            assertEquals("/api/location", server.takeRequest().requestUrl!!.encodedPath)
            val browse = server.takeRequest().requestUrl!!
            assertEquals("/", browse.queryParameter("location[directory]"))
            assertEquals("home/me/gone", browse.queryParameter("path"))
        }
    }

    @Test
    fun aGuessedAnchorIsReDerivedOnceTheLocationAnswers() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody(""))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    listBody("/", """{"path":"home/me/","type":"directory"}"""),
                ),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(locationBody("C:/Users/me")))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    listBody("C:/", """{"path":"Users/me/x/","type":"directory"}"""),
                ),
            )
            val transport = transport(server)
            assertEquals(listOf("/home/me"), transport.listDirectory("/home").map { it.absolute })
            // The probe window closed: the guess must not outlive it, or a
            // Windows host stays pinned to `/` for the whole session.
            assertEquals(listOf("C:/Users/me/x"), transport.listDirectory("C:/Users/me").map { it.absolute })
            assertEquals(4, server.requestCount)
            server.takeRequest() // location (500) — the guess
            server.takeRequest() // anchor `/` + `path=home`
            assertEquals("/api/location", server.takeRequest().requestUrl!!.encodedPath)
            val browse = server.takeRequest().requestUrl!!
            assertEquals("C:/", browse.queryParameter("location[directory]"))
            assertEquals("Users/me", browse.queryParameter("path"))
        }
    }
}
