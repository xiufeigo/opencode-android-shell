package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.PersistentPtyProtocol
import com.xiufeigo.opencodeshell.ui.terminal.share.PersistentPtyProtocol.ServerMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * [PersistentPtyProtocol] — the `input_protocol=1` up-link frames
 * (`terminal-pane.tsx` `interactionFrame`) and the JSON down-link control
 * messages. Pure codec assertions.
 */
class PersistentPtyProtocolTest {

    @Test
    fun `an empty payload is the interact resize frame`() {
        val frame = PersistentPtyProtocol.interactionFrame(cols = 80, rows = 24)
        assertArrayEquals(byteArrayOf(0, 0, 80, 0, 24), frame)
    }

    @Test
    fun `an input frame carries the big-endian size and the payload`() {
        val frame = PersistentPtyProtocol.interactionFrame(cols = 256 + 3, rows = 10, payload = byteArrayOf(65, 66))
        assertArrayEquals(byteArrayOf(1, 1, 3, 0, 10, 65, 66), frame)
    }

    @Test
    fun `exited and replay_complete parse to their own messages`() {
        assertEquals(ServerMessage.Exited, PersistentPtyProtocol.parseMessage("""{"type":"exited"}"""))
        assertEquals(ServerMessage.ReplayComplete, PersistentPtyProtocol.parseMessage("""{"type":"replay_complete"}"""))
    }

    @Test
    fun `a resized frame decodes its base64 checkpoint`() {
        val checkpoint = Base64.getEncoder().encodeToString("ckpt".toByteArray())
        val message = PersistentPtyProtocol.parseMessage(
            """{"type":"resized","cols":120,"rows":40,"checkpoint":"$checkpoint"}""",
        )
        assertTrue(message is ServerMessage.Resized)
        message as ServerMessage.Resized
        assertEquals(120, message.cols)
        assertEquals(40, message.rows)
        assertArrayEquals("ckpt".toByteArray(), message.checkpoint)
    }

    @Test
    fun `controller_changed keeps the attachment id and admits nobody`() {
        val ours = PersistentPtyProtocol.parseMessage(
            """{"type":"controller_changed","attachmentID":"att_1"}""",
        ) as ServerMessage.ControllerChanged
        assertEquals("att_1", ours.attachmentID)

        val nobody = PersistentPtyProtocol.parseMessage(
            """{"type":"controller_changed","attachmentID":null}""",
        ) as ServerMessage.ControllerChanged
        assertNull(nobody.attachmentID)
    }

    @Test
    fun `attached exposes the protocol role and info size`() {
        val message = PersistentPtyProtocol.parseMessage(
            """{"type":"attached","inputProtocol":1,"role":"controller","info":{"size":{"cols":80,"rows":24}}}""",
        ) as ServerMessage.Attached
        assertEquals(1, message.inputProtocol)
        assertEquals("controller", message.role)
        assertEquals(80, message.cols)
        assertEquals(24, message.rows)
    }

    @Test
    fun `unknown or malformed frames are ignored and never reach the screen`() {
        assertEquals(ServerMessage.Ignored, PersistentPtyProtocol.parseMessage("""{"type":"mystery"}"""))
        assertEquals(ServerMessage.Ignored, PersistentPtyProtocol.parseMessage("not json"))
        assertEquals(ServerMessage.Ignored, PersistentPtyProtocol.parseMessage(""))
    }
}
