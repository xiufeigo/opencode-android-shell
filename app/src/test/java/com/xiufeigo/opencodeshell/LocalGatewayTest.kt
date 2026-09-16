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
import java.util.concurrent.TimeUnit

class LocalGatewayTest {
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
    fun corsAllowList() {
        assertEquals(
            "https://appassets.androidplatform.net",
            LocalGateway.allowedWebOrigin("https://appassets.androidplatform.net"),
        )
        assertEquals("http://127.0.0.1:9", LocalGateway.allowedWebOrigin("http://127.0.0.1:9"))
        assertEquals(null, LocalGateway.allowedWebOrigin("https://evil.example"))
    }
}
