package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2Transport
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `V2Transport.browseRoot` must follow the server's own filesystem
 * (`FsBrowse.root` of the location sample path): a Windows v2 host browses
 * `C:/`, not the hardcoded POSIX `/` that produced empty listings there.
 */
class V2BrowseRootTest {

    private fun transport(server: MockWebServer) = V2Transport(
        V2Api(
            baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
            http = V2Api.defaultClient(),
        ),
    )

    @Test
    fun browseRootDerivesFromTheLocationSamplePath() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"directory":"C:\\Users\\xiufe"}"""))
            assertEquals("C:/", transport(server).browseRoot)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"directory":"/home/xiufeigo"}"""))
            assertEquals("/", transport(server).browseRoot)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"directory":"\\\\server\\share\\proj"}"""))
            assertEquals("//server/share", transport(server).browseRoot)
        }
    }

    @Test
    fun browseRootFallsBackToPosixSlashWhenTheLocationIsUnreachable() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            assertEquals("/", transport(server).browseRoot)
        }
    }
}
