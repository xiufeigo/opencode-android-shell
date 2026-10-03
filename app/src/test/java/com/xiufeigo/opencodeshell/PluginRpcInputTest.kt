package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.PluginRpc
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `PluginRpc.parseInput` / `formatOutput` — the RPC console's wire mapping. */
class PluginRpcInputTest {

    @Test
    fun blankInputOmitsTheKey() {
        assertTrue(PluginRpc.parseInput("") is PluginRpc.Input.Blank)
        assertTrue(PluginRpc.parseInput("   ") is PluginRpc.Input.Blank)
        // Explicit JSON null is "no input" too.
        assertTrue(PluginRpc.parseInput("null") is PluginRpc.Input.Blank)
    }

    @Test
    fun jsonValuesPassThrough() {
        val obj = PluginRpc.parseInput("""{"a":1}""") as PluginRpc.Input.Value
        assertEquals(1, (obj.value as JSONObject).optInt("a"))
        val arr = PluginRpc.parseInput("[1,2]") as PluginRpc.Input.Value
        assertEquals(2, (arr.value as JSONArray).length())
        val scalar = PluginRpc.parseInput("42") as PluginRpc.Input.Value
        assertEquals(42, scalar.value)
    }

    @Test
    fun nonJsonTextIsRefusedNotStringified() {
        assertTrue(PluginRpc.parseInput("{oops") is PluginRpc.Input.Invalid)
        assertTrue(PluginRpc.parseInput("\"unterminated") is PluginRpc.Input.Invalid)
    }

    @Test
    fun scalarsAreRealValues() {
        val bool = PluginRpc.parseInput("true") as PluginRpc.Input.Value
        assertEquals(true, bool.value)
    }

    @Test
    fun formatOutputRendersTheBareOutputSlot() {
        assertEquals("", PluginRpc.formatOutput(null))
        assertEquals("", PluginRpc.formatOutput(JSONObject.NULL))
        assertEquals("""{"x":1}""", PluginRpc.formatOutput(JSONObject("""{"x":1}""")))
        assertEquals("ok", PluginRpc.formatOutput("ok"))
    }
}
