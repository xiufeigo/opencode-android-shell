package com.xiufeigo.opencodeshell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `ChatStore.errorMessageOf` must never surface org.json's literal `"null"`:
 * `optString("name", "")` on `{"name":null}` yields the string `"null"`,
 * which sails through `isNotBlank` and lands on the error card header.
 * Regression pin for the third hit of this trap (cf. the `optStr` precedent
 * at the top of `OpenCodeClient.kt`).
 */
class ChatStoreErrorMessageTest {

    @Test
    fun jsonNullNameFallsBackToMessage() {
        assertEquals(
            "boom",
            ChatStore.errorMessageOf(JSONObject("""{"name":null,"message":"boom"}""")),
        )
    }

    @Test
    fun jsonNullMessageFallsBackToType() {
        assertEquals(
            "rate_limit",
            ChatStore.errorMessageOf(JSONObject("""{"name":null,"message":null,"type":"rate_limit"}""")),
        )
    }

    @Test
    fun allJsonNullYieldsNull() {
        assertNull(
            ChatStore.errorMessageOf(JSONObject("""{"name":null,"message":null,"type":null}""")),
        )
    }

    @Test
    fun jsonNullNestedDataMessageFallsThrough() {
        assertEquals(
            "MessageOutputLengthError",
            ChatStore.errorMessageOf(
                JSONObject("""{"data":{"message":null},"name":"MessageOutputLengthError"}"""),
            ),
        )
    }

    @Test
    fun literalNullStringCountsAsAbsent() {
        assertEquals(
            "boom",
            ChatStore.errorMessageOf(JSONObject("""{"name":"null","message":"boom"}""")),
        )
        assertNull(ChatStore.errorMessageOf("null"))
    }

    @Test
    fun plainShapesStillPassThrough() {
        assertEquals(
            "boom",
            ChatStore.errorMessageOf(JSONObject("""{"message":"boom"}""")),
        )
        assertEquals("boom", ChatStore.errorMessageOf("boom"))
        assertNull(ChatStore.errorMessageOf(JSONObject.NULL))
        assertNull(ChatStore.errorMessageOf(null))
        assertNull(ChatStore.errorMessageOf("   "))
    }
}
