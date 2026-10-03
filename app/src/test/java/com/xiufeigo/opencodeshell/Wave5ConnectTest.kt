package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.HealthFailure
import com.xiufeigo.opencodeshell.ui.servers.HealthMessage
import com.xiufeigo.opencodeshell.ui.servers.HealthResult
import com.xiufeigo.opencodeshell.ui.servers.HealthTarget
import com.xiufeigo.opencodeshell.ui.servers.ServerHealthProbe
import com.xiufeigo.opencodeshell.ui.servers.connectedServer
import com.xiufeigo.opencodeshell.ui.servers.healthFailureMessage
import com.xiufeigo.opencodeshell.ui.servers.healthResult
import com.xiufeigo.opencodeshell.ui.servers.normalizeServerUrl
import com.xiufeigo.opencodeshell.ui.servers.requiresConnectScreen
import com.xiufeigo.opencodeshell.ui.servers.serverAddress
import com.xiufeigo.opencodeshell.ui.servers.withConnectedServer
import com.xiufeigo.opencodeshell.ui.servers.withHealth
import com.xiufeigo.opencodeshell.ui.settings.ServerHealth
import com.xiufeigo.opencodeshell.ui.settings.ServerListItem
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Wave 5-E1 (first-run connect page) + W5-D3 (server health wiring).
 *
 * Three layers, all JVM:
 *
 * * the **pure** address rule (`serverAddress` / `normalizeServerUrl`,
 *   `servers/connect/pairing.ts:12-20` + `runtime/server/registry.tsx:15-20`) and
 *   the health-document mapping (`runtime/server/health.ts:72-107`);
 * * the **wire** behaviour of [ServerHealthProbe] against `MockWebServer`,
 *   including the Basic-auth rule (`health.ts:81-85`) and the timeout budget;
 * * the **resource** contract: every `oc_connect_*` / `oc_health_*` key exists in
 *   all three locales.
 */
class Wave5ConnectTest {

    // ------------------------------------------------ address normalization

    @Test
    fun bareHostGetsHttpSchemeAndLosesTrailingSlashes() {
        // `normalizeServerUrl` (`registry.tsx:15-20`).
        assertEquals("http://10.0.2.2:4096", serverAddress("10.0.2.2:4096"))
        assertEquals("http://localhost:4096", serverAddress("  localhost:4096/  "))
        assertEquals("https://backend.example.com", serverAddress("https://backend.example.com///"))
        assertEquals("http://127.0.0.1:4096", normalizeServerUrl("127.0.0.1:4096"))
    }

    @Test
    fun ipv6LoopbackIsAUsableAddress() {
        // `adb reverse` setups and `[::1]` are real addresses on Android.
        assertEquals("http://[::1]:4096", serverAddress("[::1]:4096"))
    }

    @Test
    fun nonHttpSchemesAreRejected() {
        // `serverAddress` (`pairing.ts:12-13`): a value that carries a scheme must
        // carry http/https.
        assertNull(serverAddress("ftp://host"))
        assertNull(serverAddress("ws://host:4096"))
        // The upstream scheme test is case-sensitive, so `HTTP://` is not treated
        // as a scheme at all and cannot be salvaged into a valid URL.
        assertNull(serverAddress("HTTP://host:4096"))
    }

    @Test
    fun credentialsQueryAndHashAreRejected() {
        assertNull(serverAddress("http://alice:secret@host:4096"))
        assertNull(serverAddress("http://host:4096?directory=/tmp"))
        assertNull(serverAddress("http://host:4096#frag"))
    }

    @Test
    fun blankAndSchemeOnlyInputsHaveNoAddress() {
        assertNull(serverAddress(""))
        assertNull(serverAddress("   "))
        assertNull(normalizeServerUrl("   "))
        // Documented deviation: upstream returns the string `http:` here (its
        // trailing-slash strip runs *after* the scheme and `URL.canParse("http:")`
        // is true); OkHttp needs a host, so the form shows the readable
        // "Enter a valid HTTP or HTTPS server address." instead.
        assertNull(serverAddress("http://"))
        assertNull(serverAddress("https://"))
    }

    @Test
    fun pathIsKeptBecauseUpstreamKeepsIt() {
        // No query/hash ⇒ upstream accepts a base with a path; the probe appends
        // its own `/api/info` to whatever the user typed.
        assertEquals("http://host:4096/oc", serverAddress("http://host:4096/oc"))
    }

    // --------------------------------------------------- health mapping

    @Test
    fun liveInfoDocumentIsHealthyWithVersion() {
        val result = healthResult(200, "application/json", """{"version":"2.0.11"}""")
        assertEquals(ServerHealth.HEALTHY, result.state)
        assertEquals("2.0.11", result.version)
        assertNull(result.failure)
        assertTrue(result.healthy)
    }

    @Test
    fun infoDocumentWithoutA2xVersionIsInvalid() {
        val result = healthResult(200, "application/json", """{"version":"1.18.31"}""")
        assertEquals(ServerHealth.UNHEALTHY, result.state)
        assertEquals(HealthFailure.InvalidResponse, result.failure)
    }

    @Test
    fun nonJsonAndEmptyBodiesAreInvalidResponses() {
        assertEquals(HealthFailure.InvalidResponse, healthResult(200, "text/html", "<html>hi</html>").failure)
        assertEquals(HealthFailure.InvalidResponse, healthResult(200, null, "").failure)
        assertEquals(HealthFailure.InvalidResponse, healthResult(200, null, null).failure)
        assertEquals(HealthFailure.InvalidResponse, healthResult(204, null, "").failure)
    }

    @Test
    fun statusCodesMapToTheirOwnFailure() {
        assertEquals(HealthFailure.Unauthorized, healthResult(401, null, "").failure)
        assertEquals(HealthFailure.Unauthorized, healthResult(403, null, "").failure)
        assertEquals(HealthFailure.HttpStatus(500), healthResult(500, null, "boom").failure)
        assertEquals(HealthFailure.HttpStatus(404), healthResult(404, null, "").failure)
        assertEquals(ServerHealth.UNHEALTHY, healthResult(500, null, "boom").state)
    }

    @Test
    fun failureMessagesAreReadableAndDistinct() {
        val ids = listOf(
            HealthFailure.InvalidUrl,
            HealthFailure.Unreachable,
            HealthFailure.Timeout,
            HealthFailure.Unauthorized,
            HealthFailure.NotHealthy,
            HealthFailure.InvalidResponse,
            HealthFailure.HttpStatus(503),
        ).map { healthFailureMessage(it).resId }
        assertTrue("every failure must map to a real string resource", ids.none { it == 0 })
        assertEquals("distinct failures must not share one sentence", ids.size, ids.toSet().size)
    }

    @Test
    fun statusFailureCarriesTheCodeAsFormatArgument() {
        val message: HealthMessage = healthFailureMessage(HealthFailure.HttpStatus(503))
        assertEquals(R.string.oc_connect_error_status, message.resId)
        assertEquals("503", message.formatArg)
        // The generic upstream sentence (`server.connect.failed`) is the fallback.
        assertEquals(R.string.oc_connect_failed, healthFailureMessage(HealthFailure.Unreachable).resId)
        assertEquals(R.string.oc_connect_failed, healthFailureMessage(null).resId)
        // An address the probe cannot turn into a request reuses upstream's own
        // `server.connect.address.invalid` sentence.
        assertEquals(R.string.oc_connect_address_invalid, healthFailureMessage(HealthFailure.InvalidUrl).resId)
    }

    // ------------------------------------------------ row folding + gate

    @Test
    fun healthyProbeFillsTheRowVersionAndUnhealthyOverridesIt() {
        val row = ServerListItem(url = "http://a", name = "a", username = null)
        val healthy = row.withHealth(HealthResult(ServerHealth.HEALTHY, version = "1.18.31"))
        assertEquals(ServerHealth.HEALTHY, healthy.health)
        assertEquals("1.18.31", healthy.version)

        // A healthy answer with no version keeps whatever the row already knew.
        val known = row.copy(version = "1.17.0").withHealth(HealthResult(ServerHealth.HEALTHY))
        assertEquals("1.17.0", known.version)

        val unhealthy = row.withHealth(HealthResult(ServerHealth.UNHEALTHY, failure = HealthFailure.Timeout))
        assertEquals(ServerHealth.UNHEALTHY, unhealthy.health)
        assertNull(unhealthy.version)
    }

    @Test
    fun unprobedRowStaysUnknown() {
        // "Unknown" is the *absence* of a result (upstream `health === undefined`,
        // `registry/row.tsx:127`), so a null result must not claim a state.
        val row = ServerListItem(url = "http://a", name = "a", username = null)
        assertEquals(ServerHealth.UNKNOWN, row.withHealth(null).health)
    }

    @Test
    fun connectScreenIsRequiredOnlyWithoutAUsableServer() {
        // `routes.tsx:79` — `servers.list.length === 0` ⇒ the connect page.
        assertTrue(requiresConnectScreen(emptyList()))
        assertTrue(requiresConnectScreen(listOf(ServerConfigStore.Server("localhost:4096", null, null))))
        assertFalse(requiresConnectScreen(listOf(ServerConfigStore.Server("http://a", null, null))))
    }

    @Test
    fun connectingReplacesTheSameUrlAndAppendsANewOne() {
        val existing = listOf(
            ServerConfigStore.Server("http://a", "opencode", "old"),
            ServerConfigStore.Server("http://b", null, null),
        )
        val replaced = withConnectedServer(existing, "http://a", "new")
        assertEquals(listOf("http://b", "http://a"), replaced.map { it.url })
        assertEquals("new", replaced.last().password)

        val appended = withConnectedServer(existing, "http://c", "pw")
        assertEquals(listOf("http://a", "http://b", "http://c"), appended.map { it.url })
    }

    @Test
    fun connectedRowHasNoUsernameAndNoEmptyPassword() {
        // `screen.tsx:38` adds `{ url, password }` only: the Basic-auth user is the
        // literal `opencode` (`api.ts:6`) and an empty password means "no header".
        val withPassword = connectedServer("http://a", "secret")
        assertNull(withPassword.username)
        assertEquals("secret", withPassword.password)
        assertNull(connectedServer("http://a", "").password)
        assertNull(connectedServer("http://a", null).password)
    }

    // ------------------------------------------------------- wire probe

    @Test
    fun probeHitsApiInfoAndSendsNoAuthorizationWithoutAPassword() {
        MockWebServer().use { server ->
            server.enqueue(MockWebServer200())
            val target = HealthTarget(url = base(server))

            val result = ServerHealthProbe().check(target)

            assertEquals(ServerHealth.HEALTHY, result.state)
            assertEquals("2.0.11", result.version)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/info", request.path)
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test
    fun probeSendsTheUpstreamBasicCredentialWhenAPasswordExists() {
        MockWebServer().use { server ->
            server.enqueue(MockWebServer200())
            val result = ServerHealthProbe().check(
                HealthTarget(url = base(server), username = null, password = "pw"),
            )
            assertEquals(ServerHealth.HEALTHY, result.state)
            assertEquals(basic("opencode:pw"), server.takeRequest().getHeader("Authorization"))
        }

        MockWebServer().use { server ->
            server.enqueue(MockWebServer200())
            ServerHealthProbe().check(HealthTarget(url = base(server), username = "alice", password = "pw"))
            assertEquals(basic("alice:pw"), server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test
    fun unauthorizedIsReportedAsItsOwnFailureAndNeverThrows() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val result = ServerHealthProbe().check(HealthTarget(url = base(server), password = "wrong"))
            assertEquals(ServerHealth.UNHEALTHY, result.state)
            assertEquals(HealthFailure.Unauthorized, result.failure)
        }
    }

    @Test
    fun serverErrorIsReportedWithItsStatusCode() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))
            val result = ServerHealthProbe().check(HealthTarget(url = base(server)))
            assertEquals(HealthFailure.HttpStatus(502), result.failure)
            assertEquals("502", healthFailureMessage(result.failure).formatArg)
        }
    }

    @Test
    fun aSlowServerHitsTheProbeBudgetInsteadOfHanging() {
        MockWebServer().use { server ->
            server.enqueue(MockWebServer200().setBodyDelay(5, TimeUnit.SECONDS))
            val started = System.currentTimeMillis()
            // 400 ms budget instead of the 3 s production constant, so the test
            // itself stays fast; the mapping is the same code path.
            val result = ServerHealthProbe(timeoutMs = 400L).check(HealthTarget(url = base(server)))
            val elapsed = System.currentTimeMillis() - started

            assertEquals(ServerHealth.UNHEALTHY, result.state)
            assertEquals(HealthFailure.Timeout, result.failure)
            assertTrue("the probe must give up inside its budget, took ${elapsed}ms", elapsed < 3_000)
        }
    }

    @Test
    fun aDeadHostIsUnreachableNotAThrow() {
        val server = MockWebServer()
        server.start()
        val url = base(server)
        server.shutdown()

        val result = ServerHealthProbe(timeoutMs = 1_000L).check(HealthTarget(url = url))

        assertEquals(ServerHealth.UNHEALTHY, result.state)
        assertEquals(HealthFailure.Unreachable, result.failure)
    }

    @Test
    fun unusableAddressNeverOpensASocket() {
        val result = ServerHealthProbe().check(HealthTarget(url = "not a url"))
        assertEquals(HealthFailure.InvalidUrl, result.failure)
    }

    @Test
    fun aSpaShellIsAnInvalidResponse() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "text/html")
                    .setBody("<!doctype html><title>OpenCode</title>"),
            )
            val result = ServerHealthProbe().check(HealthTarget(url = base(server)))
            assertEquals(ServerHealth.UNHEALTHY, result.state)
            assertEquals(HealthFailure.InvalidResponse, result.failure)
            assertEquals("/api/info", server.takeRequest().path)
        }
    }

    @Test
    fun authWalledV2ServerProbesHealthyViaApiInfo() {
        MockWebServer().use { server ->
            server.enqueue(MockWebServer200())
            val result = ServerHealthProbe().check(HealthTarget(url = base(server), password = "opencode"))
            assertEquals(ServerHealth.HEALTHY, result.state)
            assertEquals("2.0.11", result.version)
            val info = server.takeRequest()
            assertEquals("/api/info", info.path)
            assertEquals(basic("opencode:opencode"), info.getHeader("Authorization"))
        }
    }

    @Test
    fun wrongCredentialsStayUnauthorized() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val result = ServerHealthProbe().check(HealthTarget(url = base(server), password = "wrong"))
            assertEquals(ServerHealth.UNHEALTHY, result.state)
            assertEquals(HealthFailure.Unauthorized, result.failure)
            assertEquals("/api/info", server.takeRequest().path)
        }
    }

    @Test
    fun checkAllKeysEveryResultByUrl() {
        MockWebServer().use { healthy ->
            MockWebServer().use { dead ->
                healthy.enqueue(MockWebServer200())
                val targets = listOf(
                    HealthTarget(url = base(healthy), password = "pw"),
                    HealthTarget(url = base(dead)),
                )

                val results = ServerHealthProbe(timeoutMs = 1_000L).checkAll(targets)

                assertEquals(2, results.size)
                assertEquals(ServerHealth.HEALTHY, results.getValue(base(healthy)).state)
                assertEquals(ServerHealth.UNHEALTHY, results.getValue(base(dead)).state)
            }
        }
    }

    private fun base(server: MockWebServer): String = server.url("/").toString().trimEnd('/')

    private fun MockWebServer200() =
        MockResponse().setResponseCode(200).setBody("""{"version":"2.0.11"}""")

    private fun basic(credentials: String): String =
        "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))

    // -------------------------------------------------- locale contract

    @Test
    fun everyConnectAndHealthKeyExistsInAllThreeLocales() {
        val locales = mapOf(
            // The unit-test working directory is the module root (`app/`), the same
            // convention as `ConnectCorsTest`.
            "en" to "src/main/res/values/strings.xml",
            "zh-rCN" to "src/main/res/values-zh-rCN/strings.xml",
            "zh-rTW" to "src/main/res/values-zh-rTW/strings.xml",
        ).mapValues { (_, path) -> File(path) }

        for ((locale, file) in locales) {
            assertTrue("$locale strings.xml must exist at ${file.path}", file.isFile)
        }
        val bodies = locales.mapValues { (_, file) -> file.readText() }

        for (key in CONNECT_HEALTH_KEYS) {
            for ((locale, body) in bodies) {
                assertTrue(
                    "$locale is missing <string name=\"$key\">",
                    body.contains("name=\"$key\""),
                )
            }
        }

        // The two Chinese locales must actually translate, not carry English over.
        for ((locale, body) in bodies.filterKeys { it != "en" }) {
            for (key in CONNECT_HEALTH_KEYS - UNTRANSLATED_KEYS) {
                val value = stringValue(body, key)
                assertNotNull("$locale.$key has no value", value)
                assertTrue("$locale.$key is empty", value!!.isNotBlank())
                assertNotEquals(
                    "$locale.$key must be translated, not copied from English",
                    stringValue(bodies.getValue("en"), key),
                    value,
                )
            }
            // …and the untranslatable ones still carry the same value.
            for (key in UNTRANSLATED_KEYS) {
                assertEquals(
                    "$locale.$key must stay identical (address/command, not copy)",
                    stringValue(bodies.getValue("en"), key),
                    stringValue(body, key),
                )
            }
        }
    }

    /** Value of `<string name="[key]">…</string>` in a `strings.xml` body. */
    private fun stringValue(body: String, key: String): String? =
        Regex("<string name=\"$key\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(body)
            ?.groupValues
            ?.get(1)

    private companion object {
        /**
         * Every key the E1 page and the D3 indicator render. Kept as an explicit
         * list (not a scan) so a deleted key fails here instead of silently
         * falling back to English at runtime.
         */
        val CONNECT_HEALTH_KEYS = listOf(
            "oc_connect_title",
            "oc_connect_description",
            "oc_connect_url_label",
            "oc_connect_url_placeholder",
            "oc_connect_password_label",
            "oc_connect_button",
            "oc_connect_checking",
            "oc_connect_pair_description",
            "oc_connect_address_invalid",
            "oc_connect_failed",
            "oc_connect_error_timeout",
            "oc_connect_error_auth",
            "oc_connect_error_unhealthy",
            "oc_connect_error_invalid_response",
            "oc_connect_error_status",
            "oc_health_healthy",
            "oc_health_unhealthy",
            "oc_health_unknown",
            "oc_health_save_anyway",
        )

        /**
         * Values that are not copy: the placeholder is the emulator's host
         * address, so it is byte-identical in every locale (like the
         * `opencode pair` command, which is a literal in the layout, not a key).
         */
        val UNTRANSLATED_KEYS = setOf("oc_connect_url_placeholder")
    }
}
