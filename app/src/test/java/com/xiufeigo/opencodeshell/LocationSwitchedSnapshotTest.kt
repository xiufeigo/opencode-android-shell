package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.RawParts
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2Transport
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v46 tD 「Location changed 孤儿行 + 项目名丢失」之下半：快照行不再显示
 * 项目名。
 *
 * 成因：快照投影（`V2Transport.noticePart`）不保留 `rawJson`，而行渲染
 * （`PartRenderer`）只从 `rawJson` 读项目名——于是 reopen / 快照刷新之后
 * 的每一行都只剩裸 label（早期 live 行是 `Location changed v36projB`
 * 形态，`research/v46_verify/tD/tD_26_afterX.png`）。
 *
 * 修法沿用 `system` / `synthetic` 的先例：投影时把项目名（basename，
 * 与 live 行一致）写进 `text`，渲染侧优先 `rawJson`、回退 `text`。
 */
class LocationSwitchedSnapshotTest {

    private val transport = V2Transport(V2Api("http://127.0.0.1:1/".toHttpUrl()))

    /** One wire row → the store row the snapshot merge applies. */
    private fun snapshot(row: JSONObject): ChatStore.UiMessage =
        with(transport) { V2Api.Message.fromJson(row).toUi() }
            ?: error("the row projects to nothing: $row")

    private fun movedRow(
        id: String = "msg_moved1",
        directory: String = "/home/xiufeigo/v36projB",
    ): JSONObject = JSONObject(
        """{"id":"$id","type":"location-switched",""" +
            """"location":{"directory":"$directory"},"projectID":"pro_1",""" +
            """"time":{"created":1786000000000}}""",
    )

    @Test
    fun snapshotRowCarriesTheProjectName() {
        val message = snapshot(movedRow())
        assertEquals("msg_moved1", message.id)
        val part = message.parts.single()
        assertEquals("msg_moved1:notice", part.id)
        assertEquals("location-switched", part.type)
        // The render-side fallback slot: the name the bare label was missing.
        assertEquals("v36projB", part.text)
    }

    @Test
    fun snapshotTextAgreesWithTheLiveReader() {
        val part = snapshot(movedRow(directory = "/home/xiufeigo/v36projC")).parts.single()
        assertEquals("v36projC", part.text)
        // Same basename rule the live path (`RawParts.switch`) applies.
        assertEquals(
            part.text,
            RawParts.switch("""{"location":{"directory":"/home/xiufeigo/v36projC"}}""").value,
        )
    }

    @Test
    fun snapshotRowWithoutALocationStaysBare() {
        // Tolerate a payload that names nothing: empty text, never "null".
        val part = snapshot(movedRow(directory = "")).parts.single()
        assertEquals("", part.text)
    }

    @Test
    fun snapshotRowForARootMoveShowsTheSlash() {
        // v47 tD_53: the move to `/` rendered a bare label after reopen —
        // the projection kept the empty basename. It now carries the same
        // fallback the live reader shows.
        val part = snapshot(movedRow(directory = "/")).parts.single()
        assertEquals("/", part.text)
        assertEquals(
            part.text,
            RawParts.switch("""{"location":{"directory":"/"}}""").value,
        )
    }
}
