package com.xiufeigo.opencodeshell

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EventStreamTest {

    private class RecordingListener(expectedEvents: Int) : EventStream.Listener {
        val events = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val states = CopyOnWriteArrayList<EventStream.State>()
        val eventsLatch = CountDownLatch(expectedEvents)
        val closedLatch = CountDownLatch(1)

        override fun onEvent(type: String, data: JSONObject) {
            events.add(type to data)
            eventsLatch.countDown()
        }

        override fun onState(state: EventStream.State) {
            states.add(state)
            if (state == EventStream.State.CLOSED) closedLatch.countDown()
        }
    }

    private fun stream(server: MockWebServer, listener: EventStream.Listener): EventStream = EventStream(
        baseHttpUrl = server.url("/"),
        username = null,
        password = null,
        client = EventStream.sseClient(OkHttpClient()),
        listener = listener,
    )

    @Test
    fun sseParsesTypeAndJsonSkipsHeartbeatsAndReportsStates() {
        MockWebServer().use { server ->
            val body = "event: message.updated\n" +
                "data: {\"type\":\"message.updated\",\"info\":{\"id\":\"m1\"}}\n" +
                "\n" +
                ": heartbeat\n" +
                "\n" +
                "data: {\"type\":\"server.connected\"}\n" +
                "\n"
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body),
            )
            val listener = RecordingListener(expectedEvents = 2)
            val stream = stream(server, listener)
            stream.start()
            assertTrue("expected 2 events", listener.eventsLatch.await(10, TimeUnit.SECONDS))
            stream.close()
            assertEquals("message.updated", listener.events[0].first)
            assertEquals("m1", listener.events[0].second.getJSONObject("info").getString("id"))
            assertEquals("server.connected", listener.events[1].first)
            // The heartbeat comment dispatched nothing but did not end the stream.
            assertEquals(2, listener.events.size)
            assertEquals(EventStream.State.CONNECTING, listener.states[0])
            assertEquals(EventStream.State.ESTABLISHED, listener.states[1])
        }
    }

    @Test
    fun dataLinesConcatenateAndKeepInternalWhitespace() {
        MockWebServer().use { server ->
            // Two data: lines join into one JSON payload; the second line keeps
            // its second leading space (SSE strips exactly one); a bare `data:`
            // without space also parses.
            val body = "data: {\"s\":\"a  b\",\n" +
                "data:  \"n\":1}\n" +
                "\n" +
                "data:{\"tight\":true}\n" +
                "\n"
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body),
            )
            val listener = RecordingListener(expectedEvents = 2)
            val stream = stream(server, listener)
            stream.start()
            assertTrue("expected 2 events", listener.eventsLatch.await(10, TimeUnit.SECONDS))
            stream.close()
            // No `event:` line and no JSON "type" field -> the fallback type.
            assertEquals("unknown", listener.events[0].first)
            val multi = listener.events[0].second
            assertEquals("a  b", multi.getString("s"))
            assertEquals(1, multi.getInt("n"))
            assertEquals(true, listener.events[1].second.getBoolean("tight"))
        }
    }

    @Test
    fun appendsDirectoryQueryParamWhenProvided() {
        MockWebServer().use { server ->
            val body = "data: {\"type\":\"server.connected\"}\n\n"
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body),
            )
            val listener = RecordingListener(expectedEvents = 1)
            val stream = EventStream(
                baseHttpUrl = server.url("/"),
                username = null,
                password = null,
                client = EventStream.sseClient(OkHttpClient()),
                listener = listener,
                directory = "/data/workspace/proj",
            )
            stream.start()
            assertTrue(listener.eventsLatch.await(10, TimeUnit.SECONDS))
            stream.close()
            val request = server.takeRequest()
            assertEquals("/event", request.requestUrl?.encodedPath)
            assertEquals("/data/workspace/proj", request.requestUrl?.queryParameter("directory"))
        }
    }

    /**
     * 4xx is permanent (bad URL/auth): after MAX_CLIENT_ERROR_RETRIES consecutive
     * client errors the stream must go CLOSED and stop dialing the server.
     */
    @Test
    fun consecutiveClientErrorsEventuallyCloseWithoutFurtherRequests() {
        val server = MockWebServer()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(404).setBody("nope")
            }
            server.start()
            val listener = RecordingListener(expectedEvents = 0)
            val stream = stream(server, listener)
            stream.start()
            assertTrue(
                "stream must give up after repeated 4xx",
                listener.closedLatch.await(30, TimeUnit.SECONDS),
            )
            assertEquals(EventStream.State.CLOSED, listener.states.last())
            assertFalse(listener.states.contains(EventStream.State.ESTABLISHED))
            assertEquals(5, server.requestCount)
            val requestsAtClose = server.requestCount
            Thread.sleep(2_500)
            assertEquals("no new requests after CLOSED", requestsAtClose, server.requestCount)
        } finally {
            try {
                server.shutdown()
            } catch (_: Exception) {
            }
        }
    }
}
