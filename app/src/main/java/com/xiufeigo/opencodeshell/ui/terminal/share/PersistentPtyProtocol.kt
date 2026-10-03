package com.xiufeigo.opencodeshell.ui.terminal.share

import org.json.JSONObject
import java.util.Base64

/**
 * Wire codec of the **persistent-pty** WebSocket (`handlers/persistent-pty.ts`
 * + `tui/src/component/terminal-pane.tsx`) — pure functions, JVM-testable.
 *
 * Down-link: binary frames are raw terminal output; text frames are JSON
 * control messages (`attached` / `replay_complete` / `resized` / `exited` /
 * `controller_changed`). Up-link (with `input_protocol=1`, which
 * [PersistentPtyClient] always requests) is the framed
 * `[type:u8, cols:u16, rows:u16, payload…]` protocol:
 *
 * | type | meaning |
 * |---|---|
 * | `0` | interact/resize — declare the live `{cols,rows}` (also "take focus") |
 * | `1` | input — same header, payload is the bytes for the PTY |
 *
 * Big-endian `u16` sizes, exactly like upstream's `interactionFrame`
 * (`terminal-pane.tsx:371-379`).
 */
object PersistentPtyProtocol {

    /** `input_protocol` query value that switches up-link to the framed protocol. */
    const val INPUT_PROTOCOL = 1

    private const val FRAME_INTERACT: Int = 0
    private const val FRAME_INPUT: Int = 1

    /**
     * One up-link frame (`terminal-pane.tsx` `interactionFrame`). [payload] of
     * `null`/empty = interact/resize only; otherwise it is PTY input.
     */
    fun interactionFrame(cols: Int, rows: Int, payload: ByteArray? = null): ByteArray {
        val data = payload ?: ByteArray(0)
        val frame = ByteArray(5 + data.size)
        frame[0] = if (data.isEmpty()) FRAME_INTERACT.toByte() else FRAME_INPUT.toByte()
        frame[1] = ((cols ushr 8) and 0xFF).toByte()
        frame[2] = (cols and 0xFF).toByte()
        frame[3] = ((rows ushr 8) and 0xFF).toByte()
        frame[4] = (rows and 0xFF).toByte()
        data.copyInto(frame, 5)
        return frame
    }

    /** Down-link control message. */
    sealed interface ServerMessage {
        /** The shell exited (`{"type":"exited"}`) — never reconnect afterwards. */
        data object Exited : ServerMessage

        /** `{"type":"resized","cols","rows","checkpoint"(base64)}` — repaint from the checkpoint. */
        data class Resized(val cols: Int, val rows: Int, val checkpoint: ByteArray) : ServerMessage

        /** `{"type":"replay_complete"}` — the restore is done; queued input may flush. */
        data object ReplayComplete : ServerMessage

        /** `{"type":"controller_changed","attachmentID"}` — `null` id = nobody controls. */
        data class ControllerChanged(val attachmentID: String?) : ServerMessage

        /**
         * `{"type":"attached","inputProtocol","role","info"}` — the handshake
         * ack. [inputProtocol] must be [INPUT_PROTOCOL] or the daemon is too
         * old for this client (upstream fails the pane on anything else).
         */
        data class Attached(
            val inputProtocol: Int?,
            val role: String?,
            val cols: Int,
            val rows: Int,
        ) : ServerMessage

        /** Anything else (including unparseable JSON): ignore, never feed to the screen. */
        data object Ignored : ServerMessage
    }

    /** Parses one down-link text frame; malformed input is [ServerMessage.Ignored]. */
    fun parseMessage(text: String): ServerMessage {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return ServerMessage.Ignored
        return when (json.optString("type", "")) {
            "exited" -> ServerMessage.Exited
            "replay_complete" -> ServerMessage.ReplayComplete
            "resized" -> {
                val checkpoint = json.optString("checkpoint", "")
                ServerMessage.Resized(
                    cols = json.optInt("cols", 0),
                    rows = json.optInt("rows", 0),
                    checkpoint = if (checkpoint.isEmpty()) {
                        ByteArray(0)
                    } else {
                        runCatching { Base64.getDecoder().decode(checkpoint) }.getOrDefault(ByteArray(0))
                    },
                )
            }
            "controller_changed" -> ServerMessage.ControllerChanged(
                attachmentID = json.optString("attachmentID", "").takeIf { it.isNotEmpty() },
            )
            "attached" -> {
                val info = json.optJSONObject("info")
                val size = info?.optJSONObject("size")
                ServerMessage.Attached(
                    inputProtocol = if (json.has("inputProtocol")) json.optInt("inputProtocol") else null,
                    role = json.optString("role", "").takeIf { it.isNotEmpty() },
                    cols = size?.optInt("cols", 0) ?: 0,
                    rows = size?.optInt("rows", 0) ?: 0,
                )
            }
            else -> ServerMessage.Ignored
        }
    }
}
