package com.xiufeigo.opencodeshell

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class LocalGatewayTest {
    @Test
    fun eventStreamPathsAreDetected() {
        assertTrue(LocalGateway.isEventStreamRequestPath("/event"))
        assertTrue(LocalGateway.isEventStreamRequestPath("/global/event"))
        assertTrue(LocalGateway.isEventStreamRequestPath("/api/event"))
        assertTrue(LocalGateway.isEventStreamRequestPath("/api/session/ses_1/event"))
        assertFalse(LocalGateway.isEventStreamRequestPath("/api/session/ses_1/message"))
        assertFalse(LocalGateway.isEventStreamRequestPath("/eventual"))
        assertFalse(LocalGateway.isEventStreamRequestPath("/events"))
        assertFalse(LocalGateway.isEventStreamRequestPath("/api/health"))
    }

    /**
     * CN↔SG links blackhole long-lived SSE streams without FIN/RST. Without a
     * read timeout OkHttp waits forever and the WebView page never reconnects.
     * The watchdog must cut the loopback stream shortly after upstream silence
     * (35 s in production, short here) so the page reconnects and reconciles.
     */
    @Test
    fun stalledSseIsCutByIdleWatchdog() {
        val upstream = ServerSocket(0)
        Thread {
            try {
                upstream.accept().use { sock ->
                    val input = sock.getInputStream()
                    val head = StringBuilder()
                    val one = ByteArray(1)
                    while (head.length < 65_536) {
                        val n = input.read(one)
                        if (n < 0) break
                        head.append(one[0].toInt().toChar())
                        if (head.endsWith("\r\n\r\n")) break
                    }
                    val out = sock.getOutputStream()
                    out.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/event-stream\r\n" +
                                "Connection: close\r\n\r\n" +
                                "data: one\n\n"
                            ).toByteArray(),
                    )
                    out.flush()
                    // Half-open: stay silent, never send FIN/RST on our own.
                    Thread.sleep(15_000)
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
        try {
            LocalGateway(httpClient = OkHttpClient(), sseIdleReadTimeoutMs = 600).use { gateway ->
                gateway.start()
                val backend = "http://127.0.0.1:${upstream.localPort}"
                val url =
                    "${gateway.publicOrigin()}${gateway.publicPrefix()}${ProxyPath.encodeBackend(backend)}/event"
                val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
                val response = client.newCall(
                    Request.Builder().url(url).header("Origin", "https://appassets.androidplatform.net").build(),
                ).execute()
                assertEquals(200, response.code)
                val stream = response.body!!.byteStream()
                val first = ByteArray(64)
                assertTrue("first SSE chunk must arrive", stream.read(first) > 0)
                val beforeEof = System.nanoTime()
                val eof = stream.read()
                val waitedMs = (System.nanoTime() - beforeEof) / 1_000_000
                assertEquals(-1, eof)
                assertTrue("watchdog must cut the stalled SSE, waited=${waitedMs}ms", waitedMs < 5_000)
            }
        } finally {
            try {
                upstream.close()
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun slowNonSseResponseIsNotCutBySseWatchdog() {
        MockWebServer().use { upstream ->
            upstream.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBodyDelay(900, TimeUnit.MILLISECONDS)
                    .setBody("""{"ok":true}"""),
            )
            LocalGateway(httpClient = OkHttpClient(), sseIdleReadTimeoutMs = 300).use { gateway ->
                gateway.start()
                val backend = upstream.url("/").toString().trimEnd('/')
                val url =
                    "${gateway.publicOrigin()}${gateway.publicPrefix()}${ProxyPath.encodeBackend(backend)}/api/health"
                val response = OkHttpClient.Builder()
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                    .newCall(
                        Request.Builder().url(url).header("Origin", "https://appassets.androidplatform.net").build(),
                    )
                    .execute()
                assertEquals(200, response.code)
                assertTrue(response.body!!.string().contains("\"ok\":true"))
            }
        }
    }

    @Test
    fun healthAndPostDoNotNeedUpstreamCors() {
        MockWebServer().use { upstream ->
            upstream.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"healthy":true,"version":"1.18.31"}"""),
            )
            upstream.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true}"""),
            )
            LocalGateway(httpClient = OkHttpClient()).use { gateway ->
                gateway.start()
                val origin = "https://appassets.androidplatform.net"
                val backend = upstream.url("/").toString().trimEnd('/')
                val prefix = "${gateway.publicOrigin()}${gateway.publicPrefix()}${ProxyPath.encodeBackend(backend)}"

                val health = OkHttpClient().newCall(
                    Request.Builder()
                        .url("$prefix/api/health")
                        .header("Origin", origin)
                        .build(),
                ).execute()
                assertEquals(200, health.code)
                assertEquals(origin, health.header("Access-Control-Allow-Origin"))
                assertTrue(health.body!!.string().contains("\"healthy\":true"))
                val recordedHealth = upstream.takeRequest()
                assertEquals("/api/health", recordedHealth.path)
                assertEquals(null, recordedHealth.getHeader("Origin"))

                val post = OkHttpClient().newCall(
                    Request.Builder()
                        .url("$prefix/api/session")
                        .header("Origin", origin)
                        .header("Authorization", "Basic dXNlcjpwYXNz")
                        .header("Content-Type", "application/json")
                        .post("""{"title":"hi"}""".toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute()
                assertEquals(200, post.code)
                val recordedPost = upstream.takeRequest()
                assertEquals("POST", recordedPost.method)
                assertEquals("/api/session", recordedPost.path)
                assertEquals("""{"title":"hi"}""", recordedPost.body.readUtf8())
                assertEquals("Basic dXNlcjpwYXNz", recordedPost.getHeader("Authorization"))
            }
        }
    }

    @Test
    fun sseIsForwardedWithoutBufferingTheEntireBody() {
        MockWebServer().use { upstream ->
            upstream.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: one\n\ndata: two\n\n")
                    .throttleBody(10, 250, TimeUnit.MILLISECONDS),
            )
            LocalGateway(httpClient = OkHttpClient()).use { gateway ->
                gateway.start()
                val backend = upstream.url("/").toString().trimEnd('/')
                val url =
                    "${gateway.publicOrigin()}${gateway.publicPrefix()}${ProxyPath.encodeBackend(backend)}/event"
                val response = OkHttpClient.Builder()
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                    .newCall(Request.Builder().url(url).header("Origin", "https://appassets.androidplatform.net").build())
                    .execute()
                assertEquals(200, response.code)
                val stream = response.body!!.byteStream()
                val first = ByteArray(10)
                val n = stream.read(first)
                val firstAt = System.nanoTime()
                assertTrue(n > 0)
                val rest = stream.readBytes()
                val doneAt = System.nanoTime()
                val combined = String(first.copyOf(n)) + String(rest)
                assertTrue(combined.contains("data: one"))
                assertTrue(combined.contains("data: two"))
                assertTrue(
                    "SSE tail must arrive after the first chunk, not as one buffered blob",
                    doneAt - firstAt > TimeUnit.MILLISECONDS.toNanos(150),
                )
            }
        }
    }

    @Test
    fun preflightDoesNotHitUpstream() {
        MockWebServer().use { upstream ->
            LocalGateway(httpClient = OkHttpClient()).use { gateway ->
                gateway.start()
                val backend = upstream.url("/").toString().trimEnd('/')
                val url =
                    "${gateway.publicOrigin()}${gateway.publicPrefix()}${ProxyPath.encodeBackend(backend)}/api/health"
                val response = OkHttpClient().newCall(
                    Request.Builder()
                        .url(url)
                        .method("OPTIONS", null)
                        .header("Origin", "https://appassets.androidplatform.net")
                        .header("Access-Control-Request-Method", "GET")
                        .build(),
                ).execute()
                assertEquals(204, response.code)
                assertEquals("https://appassets.androidplatform.net", response.header("Access-Control-Allow-Origin"))
                assertEquals("true", response.header("Access-Control-Allow-Private-Network"))
                assertEquals(0, upstream.requestCount)
            }
        }
    }

    @Test
    fun dedicatedOriginKeepsAbsoluteApiPathsWithoutProxyPrefix() {
        MockWebServer().use { upstream ->
            upstream.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"all":{"openai":{"id":"openai","name":"OpenAI","models":{}}},"connected":["openai"],"default":{}}"""),
            )
            upstream.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"models":[{"id":"gpt-4o","providerID":"openai"}]}"""),
            )
            upstream.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"theme":"system"}"""),
            )
            upstream.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"type\":\"server.connected\"}\n\n"),
            )
            LocalGateway(httpClient = OkHttpClient()).use { gateway ->
                gateway.start()
                val originHeader = "https://appassets.androidplatform.net"
                val backend = upstream.url("/").toString().trimEnd('/')
                val origin = gateway.originFor(backend)
                assertTrue(origin.startsWith("http://127.0.0.1:"))
                assertFalse(origin.contains("opencode-proxy"))
                assertTrue(gateway.isBoundPort(java.net.URI(origin).port))

                // WHATWG `new URL('/api/provider', origin)` — the v1 client join.
                val providerUrl = java.net.URI(origin + "/").resolve("/api/provider").toString()
                val providers = OkHttpClient().newCall(
                    Request.Builder()
                        .url(providerUrl)
                        .header("Origin", originHeader)
                        .header("Authorization", "Basic dXNlcjpwYXNz")
                        .build(),
                ).execute()
                assertEquals(200, providers.code)
                assertTrue(providers.body!!.string().contains("openai"))
                val recordedProviders = upstream.takeRequest()
                assertEquals("/api/provider", recordedProviders.path)
                assertEquals("Basic dXNlcjpwYXNz", recordedProviders.getHeader("Authorization"))
                assertEquals(null, recordedProviders.getHeader("Origin"))

                val models = OkHttpClient().newCall(
                    Request.Builder().url("$origin/api/model").header("Origin", originHeader).build(),
                ).execute()
                assertEquals(200, models.code)
                assertEquals("/api/model", upstream.takeRequest().path)

                val config = OkHttpClient().newCall(
                    Request.Builder().url("$origin/global/config").header("Origin", originHeader).build(),
                ).execute()
                assertEquals(200, config.code)
                assertEquals("/global/config", upstream.takeRequest().path)

                val events = OkHttpClient().newCall(
                    Request.Builder().url("$origin/event").header("Origin", originHeader).build(),
                ).execute()
                assertEquals(200, events.code)
                assertTrue(events.body!!.string().contains("server.connected"))
                assertEquals("/event", upstream.takeRequest().path)
            }
        }
    }

    @Test
    fun corsAllowList() {
        assertEquals(
            "https://appassets.androidplatform.net",
            LocalGateway.allowedWebOrigin("https://appassets.androidplatform.net"),
        )
        assertEquals("http://127.0.0.1:9", LocalGateway.allowedWebOrigin("http://127.0.0.1:9"))
        assertEquals(null, LocalGateway.allowedWebOrigin("https://evil.example"))
        val headers = LocalGateway.corsHeaders("https://appassets.androidplatform.net")
        assertTrue(headers["Access-Control-Allow-Headers"]!!.contains("x-opencode-directory"))
        assertTrue(headers["Access-Control-Allow-Headers"]!!.contains("x-opencode-ticket"))
        assertTrue(headers["Access-Control-Expose-Headers"]!!.contains("Last-Event-ID"))
    }

    @Test
    fun dedicatedOriginForwardsEveryOfficialServePath() {
        val paths = listOf(
            "/path",
            "/file?path=/workspace",
            "/file/content?path=/workspace/README.md",
            "/file/status",
            "/find?pattern=hello",
            "/find/file?query=README",
            "/project",
            "/pty",
            "/mcp",
            "/permission",
            "/vcs",
            "/vcs/diff",
            "/session/ses_1/diff",
            "/global/event",
            "/api/pty",
            "/api/fs/list",
            "/api/fs/find",
            "/api/event",
            "/api/agent",
            "/api/plugin",
            "/api/command",
            "/api/skill",
            "/api/mcp",
            "/api/mcp/resource",
            "/api/permission/request",
            "/api/question/request",
            "/api/vcs/status",
            "/api/vcs/diff",
            "/api/project",
            "/api/project/current",
            "/api/shell",
            "/api/session/ses_1/diff",
            "/api/session/ses_1/message",
            "/find/symbol",
            "/file/status",
            "/pty/shells",
            "/global/event",
        )
        MockWebServer().use { upstream ->
            repeat(paths.size) {
                upstream.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"ok":true}"""),
                )
            }
            LocalGateway(httpClient = OkHttpClient()).use { gateway ->
                gateway.start()
                val originHeader = "https://appassets.androidplatform.net"
                val backend = upstream.url("/").toString().trimEnd('/')
                val origin = gateway.originFor(backend)
                val client = OkHttpClient()
                for (path in paths) {
                    val response = client.newCall(
                        Request.Builder()
                            .url("$origin$path")
                            .header("Origin", originHeader)
                            .header("x-opencode-directory", "%2Fworkspace")
                            .build(),
                    ).execute()
                    assertEquals("GET $path", 200, response.code)
                    val recorded = upstream.takeRequest()
                    val recordedPath = recorded.path ?: ""
                    assertTrue("upstream $recordedPath should keep $path", recordedPath.startsWith(path.substringBefore("?")))
                    assertEquals("%2Fworkspace", recorded.getHeader("x-opencode-directory"))
                    assertEquals(null, recorded.getHeader("Origin"))
                }
            }
        }
    }

    @Test
    fun websocketUpgradeTunnelsOnDedicatedOriginAndMux() {
        val upstream = MockWebServer()
        try {
            val listener = object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    webSocket.send("pty-ready")
                }

                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    webSocket.send("echo:$text")
                }
            }
            upstream.enqueue(MockResponse().withWebSocketUpgrade(listener))
            upstream.enqueue(MockResponse().withWebSocketUpgrade(listener))
            LocalGateway(httpClient = OkHttpClient()).use { gateway ->
                gateway.start()
                val backend = upstream.url("/").toString().trimEnd('/')
                val origin = gateway.originFor(backend)
                val dedicatedWs = origin.replace("http://", "ws://") + "/pty/pty_test/connect?directory=/workspace&cursor=0"
                assertWsEcho(dedicatedWs, "hello-dedicated")
                val mux = "${gateway.publicOrigin()}${gateway.publicPrefix()}${ProxyPath.encodeBackend(backend)}"
                    .replace("http://", "ws://") + "/api/pty/pty_test/connect?location[directory]=/workspace&cursor=0"
                assertWsEcho(mux, "hello-mux")
            }
        } finally {
            try {
                upstream.shutdown()
            } catch (_: Exception) {
            }
            try {
                upstream.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun assertWsEcho(url: String, payload: String) {
        val opened = java.util.concurrent.CountDownLatch(1)
        val echoed = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val seen = java.util.concurrent.atomic.AtomicReference<String>()
        val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        val ws = client.newWebSocket(
            Request.Builder()
                .url(url.replace("ws://", "http://").replace("wss://", "https://"))
                .header("Origin", "https://appassets.androidplatform.net")
                .build(),
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    opened.countDown()
                    webSocket.send(payload)
                }

                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    if (text == "echo:$payload") {
                        seen.set(text)
                        echoed.countDown()
                    }
                }

                override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    closed.countDown()
                }

                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                    closed.countDown()
                }
            },
        )
        assertTrue("websocket open $url", opened.await(4, TimeUnit.SECONDS))
        assertTrue("websocket echo $url", echoed.await(4, TimeUnit.SECONDS))
        assertEquals("echo:$payload", seen.get())
        ws.close(1000, "done")
        closed.await(2, TimeUnit.SECONDS)
        ws.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
