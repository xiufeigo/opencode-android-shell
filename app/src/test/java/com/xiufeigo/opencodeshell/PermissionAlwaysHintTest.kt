package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.dock.permissionAlwaysHint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route C: the permission dock's "always" transparency row.
 *
 * v1's reply takes `prefixes` (`always[]`) — what an "allow always" answer
 * persists — while `patterns[]` is only what matched the current ask. The dock
 * shows the difference, so the helper has to subtract correctly.
 */
class PermissionAlwaysHintTest {

    private fun request(json: String): OpenCodeClient.PermissionRequest =
        OpenCodeClient.PermissionRequest.fromJson(JSONObject(json))

    @Test
    fun widensWhenTheServerWouldRememberMoreThanTheCurrentAsk() {
        val req = request(
            """{"id":"per_1","sessionID":"ses_1","permission":"bash","patterns":["cmd.exe /c dir"],
                "always":["cmd.exe /c *","cmd.exe /c dir"],"metadata":{},"tool":{"messageID":"m","callID":"c"}}""",
        )
        assertEquals(listOf("cmd.exe /c *"), permissionAlwaysHint(req))
    }

    @Test
    fun emptyWhenNothingWiderWouldBeRemembered() {
        val same = request(
            """{"id":"per_2","permission":"edit","patterns":["src/A.kt"],"always":["src/A.kt"]}""",
        )
        assertTrue(permissionAlwaysHint(same).isEmpty())

        val none = request("""{"id":"per_3","permission":"edit","patterns":["src/A.kt"]}""")
        assertTrue(permissionAlwaysHint(none).isEmpty())
    }

    @Test
    fun ignoresBlankEntriesAndKeepsServerOrder() {
        val req = request(
            """{"id":"per_4","permission":"bash","patterns":["a"],
                "always":["","b","a","  ","c"]}""",
        )
        assertEquals(listOf("b", "c"), permissionAlwaysHint(req))
    }

    @Test
    fun requestWithoutPatternsStillShowsEveryRememberedPattern() {
        val req = request("""{"id":"per_5","permission":"webfetch","always":["https://*"]}""")
        assertEquals(listOf("https://*"), permissionAlwaysHint(req))
    }
}
