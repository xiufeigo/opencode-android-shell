package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.DEFAULT_SERVER_URL_PLACEHOLDER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P0 contract: the URL the APK points users at must be the port the opencode
 * 2.x backend actually listens on.
 *
 * `http://10.0.2.2:4096` (upstream's example port plus the emulator host) had
 * no listener on the host, so every request ended in `unexpected end of
 * stream`. The real default is **4095** (`opencode serve`), reachable as
 * `10.0.2.2:4095` from an emulator and as `127.0.0.1:4095` under
 * `adb reverse tcp:4095 tcp:4095`.
 */
class ServerDefaultUrl4095Test {

    private fun strings(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun value(body: String, key: String): String =
        Regex("<string name=\"$key\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(body)
            ?.groupValues
            ?.get(1)
            ?: error("missing $key")

    @Test
    fun defaultServerUrlIsTheRealBackendPort() {
        assertEquals("http://10.0.2.2:4095", DEFAULT_SERVER_URL_PLACEHOLDER)
    }

    @Test
    fun everyLocaleSuggestsPort4095InBothPlaceholders() {
        val locales = listOf("values", "values-zh-rCN", "values-zh-rTW")
        for (dir in locales) {
            val body = strings("src/main/res/$dir/strings.xml")
            assertEquals(
                "$dir oc_server_url_placeholder",
                "http://10.0.2.2:4095",
                value(body, "oc_server_url_placeholder"),
            )
            assertEquals(
                "$dir oc_connect_url_placeholder",
                "http://10.0.2.2:4095",
                value(body, "oc_connect_url_placeholder"),
            )
        }
    }

    @Test
    fun corsHintDocumentsThe4095Reverse() {
        for (dir in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val hint = value(strings("src/main/res/$dir/strings.xml"), "oc_server_cors_hint")
            assertTrue("$dir must document tcp:4095", hint.contains("tcp:4095"))
            assertTrue("$dir must show 127.0.0.1:4095", hint.contains("127.0.0.1:4095"))
        }
    }
}
