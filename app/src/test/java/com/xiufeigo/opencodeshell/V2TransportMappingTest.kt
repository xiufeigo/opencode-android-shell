package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.fileContentFromBytes
import com.xiufeigo.opencodeshell.v2.v2ProjectRow
import com.xiufeigo.opencodeshell.v2.v2VcsDiffMode
import com.xiufeigo.opencodeshell.v2.v2VcsInfoRow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The pure v2→v1 mappings behind the review panel and Home's projects column
 * ([v2ProjectRow], [v2VcsInfoRow], [v2VcsDiffMode], [fileContentFromBytes]).
 * Every fixture mirrors a payload measured live on opencode 2.0.11
 * (2026-09-22): `/api/project` is a bare array keyed `canonical`, `/api/vcs`
 * nests `branch:{current,default}` inside the envelope, `/api/vcs/diff` rejects
 * `mode=git`, and `/api/fs/read` answers bytes where v1 answered
 * `{type,content}` JSON.
 */
class V2TransportMappingTest {

    // ---- v2ProjectRow: GET /api/project → OpenCodeClient.Project ----------

    @Test
    fun `v2 canonical maps into the v1 worktree slot with vcs and sandboxes`() {
        val row = v2ProjectRow(
            V2Api.V2Project.fromJson(
                JSONObject(
                    """{"id":"16a80bf9","canonical":"/home/xiufeigo/opencode-android-shell",""" +
                        """"time":{"created":1790075402947,"updated":1790075402947},""" +
                        """"sandboxes":["/tmp/scratch"],"vcs":"git"}""",
                ),
            ),
        )
        assertNotNull(row)
        assertEquals("16a80bf9", row!!.id)
        assertEquals("/home/xiufeigo/opencode-android-shell", row.worktree)
        assertEquals("git", row.vcs)
        assertEquals(listOf("/tmp/scratch"), row.sandboxes)
        // v2 carries no `icon.color`; Home renders the untinted avatar.
        assertNull(row.iconColor)
    }

    @Test
    fun `v2 project without a canonical path is dropped like a worktreeless v1 row`() {
        assertNull(
            v2ProjectRow(V2Api.V2Project.fromJson(JSONObject("""{"id":"broken","sandboxes":[]}"""))),
        )
        assertNull(
            v2ProjectRow(V2Api.V2Project.fromJson(JSONObject("""{"id":"blank","canonical":"  "}"""))),
        )
    }

    @Test
    fun `v2 project without vcs reads null not the literal null`() {
        val row = v2ProjectRow(
            V2Api.V2Project.fromJson(JSONObject("""{"id":"p","canonical":"/plain"}""")),
        )
        assertNull(row!!.vcs)
        assertEquals(emptyList<String>(), row.sandboxes)
    }

    // ---- v2VcsInfoRow: GET /api/vcs → OpenCodeClient.VcsInfo --------------

    @Test
    fun `v2 nested branch object maps to the flat v1 pair`() {
        val info = v2VcsInfoRow(
            V2Api.VcsInfo.fromJson(JSONObject("""{"branch":{"current":"feature","default":"main"}}""")),
        )
        assertEquals("feature", info.branch)
        assertEquals("main", info.defaultBranch)
    }

    @Test
    fun `empty v2 branch object reads nulls like the v1 JSON nulls`() {
        val info = v2VcsInfoRow(V2Api.VcsInfo.fromJson(JSONObject("""{"branch":{}}""")))
        assertNull(info.branch)
        assertNull(info.defaultBranch)
        val absent = v2VcsInfoRow(V2Api.VcsInfo.fromJson(JSONObject("{}")))
        assertNull(absent.branch)
        assertNull(absent.defaultBranch)
    }

    // ---- v2VcsDiffMode: v1 enum → v2 enum ---------------------------------

    @Test
    fun `git maps to working on v2 and everything else passes through`() {
        // Measured: v1 accepts git|branch and 400s working; v2 the exact inverse.
        assertEquals("working", v2VcsDiffMode("git"))
        assertEquals("branch", v2VcsDiffMode("branch"))
        assertEquals("working", v2VcsDiffMode("working"))
        assertEquals("committed", v2VcsDiffMode("committed"))
        // Unknown modes stay untouched so the server's own 400 surfaces.
        assertEquals("last_turn", v2VcsDiffMode("last_turn"))
    }

    // ---- fileContentFromBytes: fs/read bytes → the v1 JSON shape ----------

    @Test
    fun `utf8 bytes become the v1 text shape`() {
        val content = fileContentFromBytes("héllo\nworld".toByteArray(Charsets.UTF_8))
        assertFalse(content.binary)
        assertEquals("héllo\nworld", content.content)
        assertNull(content.encoding)
        assertNull(content.mimeType)
        assertNull(content.diff)
        assertNull(content.patch)
    }

    @Test
    fun `bytes carrying a nul become the v1 binary shape with base64 content`() {
        val bytes = byteArrayOf(0x4D, 0x5A, 0x00, 0x01, 0xFF.toByte())
        val content = fileContentFromBytes(bytes)
        assertTrue(content.binary)
        assertEquals(Base64.getEncoder().encodeToString(bytes), content.content)
        // v1 measured encoding:"base64" on binary answers; mimeType is
        // extension-derived server-side and never read by the preview.
        assertEquals("base64", content.encoding)
        assertNull(content.mimeType)
    }

    @Test
    fun `an empty file reads as empty text not binary`() {
        val content = fileContentFromBytes(ByteArray(0))
        assertFalse(content.binary)
        assertEquals("", content.content)
        assertNull(content.encoding)
    }
}
