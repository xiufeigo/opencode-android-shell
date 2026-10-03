package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.HealthFailure
import com.xiufeigo.opencodeshell.ui.servers.HealthTarget
import com.xiufeigo.opencodeshell.ui.servers.ServerHealthProbe
import com.xiufeigo.opencodeshell.ui.servers.healthFailureMessage
import com.xiufeigo.opencodeshell.ui.servers.healthResult
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * P2 contract: a `401` is two different stories and must not share a sentence.
 *
 * With no `Authorization` on the wire (empty password → `basicAuthorizationHeader`
 * returns `null`) the server is only saying "who are you?" — the honest copy is
 * `oc_server_error_auth` ("enter username and password"). When a credential was
 * sent and refused, `oc_connect_error_auth` ("the server rejected that
 * password") stays. The old mapping collapsed both onto the rejection sentence,
 * so a blank password box read as "the server rejected that password".
 */
class ServerAuth401CopyTest {

    @Test
    fun bare401WithoutCredentialsIsMissingCredentialsNotRejected() {
        assertEquals(
            HealthFailure.MissingCredentials,
            healthResult(401, null, "", credentialsSent = false).failure,
        )
        assertEquals(
            HealthFailure.MissingCredentials,
            healthResult(403, null, "", credentialsSent = false).failure,
        )
    }

    @Test
    fun rejectedCredentialStaysUnauthorized() {
        assertEquals(
            HealthFailure.Unauthorized,
            healthResult(401, null, "", credentialsSent = true).failure,
        )
        assertEquals(
            HealthFailure.Unauthorized,
            healthResult(403, null, "", credentialsSent = true).failure,
        )
    }

    @Test
    fun theTwoAuth401SentencesAreDifferentStrings() {
        val missing = healthFailureMessage(HealthFailure.MissingCredentials)
        val rejected = healthFailureMessage(HealthFailure.Unauthorized)
        assertNotEquals(
            "empty-password 401 and rejected-credential 401 must not share copy",
            missing.resId,
            rejected.resId,
        )
        assertEquals(R.string.oc_server_error_auth, missing.resId)
        assertEquals(R.string.oc_connect_error_auth, rejected.resId)
    }

    @Test
    fun probeSplits401ByWhetherItSentACredential() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val anonymous = ServerHealthProbe().check(HealthTarget(url = base(server)))
            assertEquals(HealthFailure.MissingCredentials, anonymous.failure)

            server.enqueue(MockResponse().setResponseCode(401))
            val rejected = ServerHealthProbe()
                .check(HealthTarget(url = base(server), password = "wrong"))
            assertEquals(HealthFailure.Unauthorized, rejected.failure)
        }
    }

    private fun base(server: MockWebServer): String = server.url("/").toString().trimEnd('/')
}
