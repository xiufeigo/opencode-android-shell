package com.xiufeigo.opencodeshell.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.ui.overlay.DialogScrim
import com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds
import com.xiufeigo.opencodeshell.ui.overlay.rememberDialogScrimState
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocShadow
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.rpcCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 「Call plugin RPC」 — the `POST /api/rpc/{rpcID}/{method}` console
 * (`rpc.ts:7-27`, bare `{output?}` response). [parseInput] / [formatOutput]
 * keep the wire mapping JVM-testable; the dialog is a thin form over them.
 */
object PluginRpc {

    /** What the input box maps to on the wire (`{"input"?: Unknown}`). */
    sealed interface Input {
        /** Blank box — the `input` key is omitted entirely. */
        object Blank : Input

        /** Parsed JSON (object / array / scalar) to send as `input`. */
        data class Value(val value: Any) : Input

        /** Non-JSON text: refuse to send rather than invent a string payload. */
        object Invalid : Input
    }

    fun parseInput(text: String): Input {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Input.Blank
        return try {
            when (val value = JSONTokener(trimmed).nextValue()) {
                JSONObject.NULL -> Input.Blank
                is JSONObject, is JSONArray, is String, is Boolean, is Number -> Input.Value(value)
                else -> Input.Invalid
            }
        } catch (_: Exception) {
            Input.Invalid
        }
    }

    /** The `output` slot of the bare response, as the panel shows it. */
    fun formatOutput(output: Any?): String = when (output) {
        null -> ""
        JSONObject.NULL -> ""
        else -> output.toString()
    }
}

/**
 * The RPC form: `rpcID` / `method` / optional JSON `input` → `rpcCall`, with
 * the `output` slot rendered under it. Failures toast `common.requestFailed`;
 * invalid input refuses locally with [R.string.oc_palette_rpc_invalid_input].
 */
@Composable
fun PluginRpcDialog(onClose: () -> Unit) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.xl
    val context = LocalContext.current
    val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
    val scope = rememberCoroutineScope()

    var rpcId by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf<String?>(null) }
    var invalidInput by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val scrim = rememberDialogScrimState()
    Box(modifier = Modifier.fillMaxSize()) {
        DialogScrim(scrim, onClose)
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                .widthIn(max = 360.dp)
                .clip(shape)
                .background(c.bgBase)
                .border(0.5.dp, c.borderBase, shape)
                .ocShadow(OcTheme.elevation.floating, shape)
                .dialogCardBounds(scrim)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.oc_palette_rpc_title),
                color = c.textBase,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 21.sp,
            )
            Spacer(Modifier.height(8.dp))
            RpcField(
                value = rpcId,
                label = stringResource(R.string.oc_palette_rpc_id),
                onValueChange = { rpcId = it },
            )
            RpcField(
                value = method,
                label = stringResource(R.string.oc_palette_rpc_method),
                onValueChange = { method = it },
            )
            RpcField(
                value = input,
                label = stringResource(R.string.oc_palette_rpc_input),
                onValueChange = {
                    input = it
                    invalidInput = false
                },
                multiline = true,
            )
            if (invalidInput) {
                Text(
                    text = stringResource(R.string.oc_palette_rpc_invalid_input),
                    color = c.stateDangerFg,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            output?.let { text ->
                Text(
                    text = stringResource(R.string.oc_palette_rpc_output),
                    color = c.textMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp, max = 160.dp)
                        .background(c.layer02.copy(alpha = 0.6f), OcTheme.shapes.md)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Text(
                        text = text.ifEmpty { "null" },
                        color = c.textBase,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = stringResource(R.string.oc_common_cancel),
                    color = c.textMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight(530),
                    modifier = Modifier
                        .clip(OcTheme.shapes.md)
                        .clickable(role = Role.Button, onClick = onClose)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
                Text(
                    text = stringResource(R.string.oc_palette_rpc_call),
                    color = c.textBase,
                    fontSize = 13.sp,
                    fontWeight = FontWeight(530),
                    modifier = Modifier
                        .clip(OcTheme.shapes.md)
                        .clickable(role = Role.Button, enabled = !busy) {
                            val parsed = PluginRpc.parseInput(input)
                            if (parsed is PluginRpc.Input.Invalid || rpcId.isBlank() || method.isBlank()) {
                                invalidInput = parsed is PluginRpc.Input.Invalid
                                return@clickable
                            }
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val server = serverStore.server
                                            ?: return@runCatching null
                                        val api = V2Api.forServer(
                                            server.url,
                                            server.username,
                                            server.password,
                                        ) ?: return@runCatching null
                                        val payload = (parsed as? PluginRpc.Input.Value)?.value
                                        PluginRpc.formatOutput(
                                            api.rpcCall(rpcId.trim(), method.trim(), payload),
                                        )
                                    }
                                }
                                busy = false
                                output = result.getOrNull()
                                if (result.isFailure) {
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.common_requestFailed),
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun RpcField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    multiline: Boolean = false,
) {
    val c = OcTheme.colors
    Text(
        text = label,
        color = c.textMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (multiline) Modifier.heightIn(min = 64.dp) else Modifier.height(36.dp))
            .background(c.layer02.copy(alpha = 0.6f), OcTheme.shapes.md)
            .border(0.5.dp, c.borderBase, OcTheme.shapes.md)
            .padding(horizontal = 12.dp, vertical = if (multiline) 8.dp else 0.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = !multiline,
            textStyle = TextStyle(
                color = c.textBase,
                fontSize = 13.sp,
                fontFamily = if (multiline) FontFamily.Monospace else null,
            ),
            cursorBrush = SolidColor(c.textAccent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
