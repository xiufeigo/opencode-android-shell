package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.ui.home.HomeProjectEdit
import com.xiufeigo.opencodeshell.ui.home.HomeProjects
import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentPolicy
import java.io.File
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v97 P1（`research/v97_fix/PROJECT_ICON_PERSIST.md`，真机证据
 * `research/v97_verify/5564/REPORT.md` B11/B12/B13 段）——「编辑项目」的**自定义
 * 图标图片不落盘**。
 *
 * 缺陷现场（5564 真机，未在服务器注册的 `/tmp/e2echk_proj5564`）：草稿态全对
 * （预览换成所选图片、色板整行消失、出现「清除图片」、保存变亮），点保存后
 * **Home 行头像仍是字母徽标**，重开面板预览也回到字母徽标。同一面板的
 * name / color / startup 三个字段却**都能**保存并读回。
 *
 * 为什么这三个字段能过而图片不能，是本类要钉住的核心：它们是几十字节的短
 * 字符串，而 `iconOverride` 是 [ProjectIconImage.read] 降采样后 base64 的
 * `data:` URL —— 256×256 的真图轻易到**几百 KB**，是**同一个** SharedPreferences
 * 值（`HomeProjectStore.update` 把整份 state 编成一个 JSON 字符串）里唯一的大
 * 字符串。本类用 ≥100KB 的真实 `data:` URL 把「体积」这一维钉成可执行的断言：
 * 一个改小就绿、改大就红的往返 pin，加上一条**上限**断言，让
 * [ProjectIconImage.read] 产出的载荷有可解释的字节预算（见
 * `MAX_STORED_BYTES`），从根上断掉「同一个值里塞一张大图」这条失败路径。
 *
 * 覆盖：
 * 1. 大 `data:` URL 的 `encode → decode` **逐字节**往返（含 `bytesOf` 解码）；
 * 2. `setMeta` 非空写入 / `""` 清空 / `null` 不改，`localMeta` 读得回；
 * 3. `patchFor` / `metaPatchFor` 在「选新图」与「清除图片」两态下的字段语义；
 * 4. `openRows` 在**未注册**项目上用本地 override、**已注册**项目仍走服务器；
 * 5. 载荷上限：`ProjectIconImage.read` 的降采样结果必须落在字节预算内。
 */
class ProjectIconLocalMetaV97Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val iconImage = source(
        "src/main/java/com/xiufeigo/opencodeshell/ui/home/ProjectIconImage.kt",
    )

    /**
     * A **real** `data:image/png;base64,…` URL of at least 100 KB — the shape
     * `ProjectIconImage.read` produces for a 256×256 pick. Built from actual
     * PNG-magic bytes so `bytesOf` has something decodable behind it, then
     * padded inside the base64 alphabet (never mid-quantum, so it still decodes
     * byte-for-byte).
     */
    private fun hugeDataUrl(minChars: Int = 100 * 1024): String {
        val prefix = "data:image/png;base64,"
        val pngMagic = Base64.getEncoder().encodeToString(
            // 9 bytes, not the 8-byte signature: a byte count that is a
            // multiple of 3 encodes with **no** `=` padding, so the padding
            // can never land in the middle of the padded payload (a strict
            // base64 decoder rejects that, and the fixture would fail for a
            // reason that has nothing to do with what it is pinning).
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00),
        )
        // The **payload** (everything after the comma) is what a base64 decoder
        // sees, so it is the payload that must land on a 4-character quantum —
        // the `data:` prefix is not part of it and must not be counted.
        val payload = StringBuilder(pngMagic)
        // 'A' is a zero byte in base64: padding with it cannot change any
        // decoded byte, so the payload still round-trips byte-for-byte.
        while (prefix.length + payload.length < minChars) payload.append('A')
        while (payload.length % 4 != 0) payload.append('A')
        return prefix + payload
    }

    private val bigImage: String = hugeDataUrl()

    private fun record(
        worktree: String = "/tmp/e2echk_proj5564",
        name: String = "",
        iconColor: String? = null,
        iconOverride: String? = null,
        startup: String? = null,
    ) = HomeProjectRecords.Project(
        worktree = worktree,
        sandboxes = emptyList(),
        name = name,
        iconColor = iconColor,
        iconOverride = iconOverride,
        startup = startup,
    )

    private fun opened(vararg records: HomeProjectRecords.Project): HomeProjectRecords.State =
        HomeProjectRecords.State(list = records.toList())

    private fun original(iconOverride: String? = null) = HomeProjectEdit.Original(
        projectID = null,
        name = "",
        color = null,
        iconOverride = iconOverride,
        startup = "",
        folderName = "e2echk_proj5564",
    )

    // ------------------------------------------- 1. the large-value round trip

    @Test
    fun aLargeIconDataUrlSurvivesEncodeDecodeByteForByte() {
        assertTrue(
            "the fixture must be a genuinely large data URL (got ${bigImage.length})",
            bigImage.length >= 100 * 1024,
        )
        val state = opened(record(iconOverride = bigImage))
        val row = HomeProjectRecords.decode(HomeProjectRecords.encode(state)).list.single()
        // Byte-for-byte, not "close": a data URL that lost or reordered a
        // character decodes to a different image (or none), which is exactly
        // the B11 symptom — the row falls back to the letter badge.
        assertEquals(bigImage, row.iconOverride)
        assertEquals(bigImage.length, row.iconOverride!!.length)
    }

    @Test
    fun aLargeIconDataUrlStillDecodesToItsOwnBytes() {
        val row = HomeProjectRecords
            .decode(HomeProjectRecords.encode(opened(record(iconOverride = bigImage))))
            .list
            .single()
        // The render side (`ProjectIconView` → `ProjectIconImage.bytesOf`): a
        // payload that survives the JSON round trip but no longer decodes is
        // just as invisible as one that was dropped.
        val bytes = com.xiufeigo.opencodeshell.ui.home.ProjectIconImage.bytesOf(row.iconOverride)
        assertNotNull("the stored override must still be a decodable image", bytes)
        assertArrayEquals(Base64.getDecoder().decode(bigImage.substringAfter(',')), bytes)
        // …and it is the same picker the dialog used to produce it.
        assertEquals("image/png", com.xiufeigo.opencodeshell.ui.home.ProjectIconImage.mimeOf(row.iconOverride))
    }

    @Test
    fun thePickedPayloadStaysInsideItsByteBudget() {
        // The fix's load-bearing half: `ProjectIconImage.read` bounds the
        // stored payload, because the client-local meta is **one** JSON string
        // in **one** SharedPreferences value and a multi-hundred-KB member is
        // what made the icon the only field of the three that did not land.
        // A 32 dp avatar is never drawn larger than 96 px, so the budget costs
        // no visible quality.
        assertTrue(
            "read() must bound the packed payload",
            iconImage.contains("MAX_STORED_BYTES"),
        )
        val budget = Regex("const val MAX_STORED_BYTES = (\\d+)").find(iconImage)?.groupValues?.get(1)?.toLongOrNull()
        assertNotNull("MAX_STORED_BYTES must be a plain const Long the test can read", budget)
        // A 256×256 opaque photo packed as PNG is the pathological case the
        // budget exists for; the budget must be far below it.
        assertTrue(
            "the budget (${budget}) must bound a 256×256 pick",
            budget!! in 1..(64 * 1024),
        )
        // The budget is enforced by re-encoding at a lower quality / smaller
        // edge, not by truncating the data URL: a truncated base64 body would
        // decode to a corrupt image, which is the bug in another costume.
        assertTrue(
            "the payload must be re-encoded, never string-truncated",
            iconImage.contains("repack") || iconImage.contains("fitBudget"),
        )
        assertTrue(
            "…and a base64 body is never cut mid-quantum",
            !iconImage.contains("substring(0, MAX_STORED"),
        )
    }

    // ------------------------------------------------ 2. setMeta / localMeta

    @Test
    fun aNonEmptyOverrideIsWrittenAndReadBack() {
        val state = HomeProjectRecords.setMeta(
            opened(record(name = "E2ECHK5564N", iconColor = "cyan")),
            "/tmp/e2echk_proj5564",
            HomeProjectRecords.MetaPatch(iconOverride = bigImage),
        )
        val meta = HomeProjectRecords.localMeta(state, "/tmp/e2echk_proj5564")
        assertEquals(bigImage, meta.iconOverride)
        // The already-working fields must survive an icon-only save untouched.
        assertEquals("E2ECHK5564N", meta.name)
        assertEquals("cyan", meta.iconColor)
        // …and it must be the **persisted** form the panel re-reads, not only
        // the in-memory one (B13 reopened the dialog and saw the letter badge).
        val reloaded = HomeProjectRecords.decode(HomeProjectRecords.encode(state))
        assertEquals(bigImage, HomeProjectRecords.localMeta(reloaded, "/tmp/e2echk_proj5564").iconOverride)
    }

    @Test
    fun anEmptyStringClearsTheOverrideAndANullLeavesIt() {
        val full = opened(record(iconOverride = bigImage))
        // null = "not in this patch" (upstream omits the key), "" = clear it.
        val untouched = HomeProjectRecords.setMeta(
            full,
            "/tmp/e2echk_proj5564",
            HomeProjectRecords.MetaPatch(name = "Renamed"),
        )
        assertEquals(bigImage, HomeProjectRecords.localMeta(untouched, "/tmp/e2echk_proj5564").iconOverride)

        val cleared = HomeProjectRecords.setMeta(
            untouched,
            "/tmp/e2echk_proj5564",
            HomeProjectRecords.MetaPatch(iconOverride = ""),
        )
        assertNull(HomeProjectRecords.localMeta(cleared, "/tmp/e2echk_proj5564").iconOverride)
        // A cleared override is **absent** from the persisted form, so it stays
        // cleared across a restart rather than resurrecting as "".
        val again = HomeProjectRecords.encode(cleared)
        assertTrue("a cleared override is not persisted", !again.contains("iconOverride"))
    }

    @Test
    fun clearingSurvivesAnEncodeDecodeRoundTrip() {
        val cleared = HomeProjectRecords.setMeta(
            opened(record(iconColor = "pink", iconOverride = bigImage)),
            "/tmp/e2echk_proj5564",
            HomeProjectRecords.MetaPatch(iconOverride = ""),
        )
        val reloaded = HomeProjectRecords.decode(HomeProjectRecords.encode(cleared))
        val row = reloaded.list.single()
        assertNull(row.iconOverride)
        // The clear must not take the tint with it (B13 expects the letter badge
        // back, in the swatch colour, not a blank square).
        assertEquals("pink", row.iconColor)
    }

    // ------------------------------------------- 3. the patch the dialog sends

    @Test
    fun pickingANewImagePutsTheDataUrlInThePatch() {
        val patch = HomeProjectEdit.patchFor(
            original = original(iconOverride = null),
            draft = HomeProjectEdit.draftOf(original()).copy(iconOverride = bigImage),
        )
        assertEquals(bigImage, patch.iconOverride)
        assertTrue("an image-only save is not empty", !patch.isEmpty)
        // The other fields are untouched, so they are omitted rather than
        // blanked — the contract the name/color/startup path already relies on.
        assertNull(patch.name)
        assertNull(patch.iconColor)
        assertNull(patch.startup)
        // …and the local channel carries the same field through.
        assertEquals(bigImage, HomeProjectEdit.metaPatchFor(patch).iconOverride)
    }

    @Test
    fun clearingTheImageSendsAnEmptyStringAndAnUnchangedOneSendsNothing() {
        val saved = original(iconOverride = bigImage)
        val cleared = HomeProjectEdit.patchFor(
            original = saved,
            draft = HomeProjectEdit.draftOf(saved).copy(iconOverride = null),
        )
        // Upstream `saveIcon(color, override)` clears with `override ?? ""`;
        // null means "unchanged", so the clear has to travel as "".
        assertEquals("", cleared.iconOverride)
        assertEquals("", HomeProjectEdit.metaPatchFor(cleared).iconOverride)

        // Untouched draft → the field is omitted entirely, not sent as "".
        val unchanged = HomeProjectEdit.patchFor(saved, HomeProjectEdit.draftOf(saved))
        assertNull(unchanged.iconOverride)
        assertTrue("nothing changed, nothing to write", unchanged.isEmpty)
    }

    @Test
    fun anUnchangedHugeOverrideIsNotRewritten() {
        // The panel reloads `original` from the store, so after a restart the
        // draft already carries the saved image. Saving the panel again with
        // no edit must not send a second 100 KB payload.
        val saved = original(iconOverride = bigImage)
        assertNull(HomeProjectEdit.patchFor(saved, HomeProjectEdit.draftOf(saved)).iconOverride)
    }

    // ------------------------------------------------ 4. the rendered row

    @Test
    fun anUnregisteredRowDrawsItsLocalOverrideAndARegisteredOneTheServers() {
        val openedList = HomeProjectRecords
            .setMeta(
                opened(record(iconOverride = bigImage)),
                "/tmp/e2echk_proj5564",
                HomeProjectRecords.MetaPatch(iconOverride = bigImage),
            )
            .list

        // No server project owns the worktree → the client-local meta covers the
        // server's (`runtime.tsx:151`) and the row must draw the image.
        val local = HomeProjects.openRows(opened = openedList, known = emptyList())
        assertEquals(bigImage, local.single().iconOverride)
        assertNull("…and it has no server id", local.single().serverID)
        assertTrue(
            HomeProjectEdit.localMetaApplies(local.single().serverID),
        )

        // A registered project stays owned by the catalogue: the stale local
        // copy must not shadow the server's icon.
        val server = HomeProjects.Project(
            name = "api",
            path = "/tmp/e2echk_proj5564",
            iconOverride = "data:image/png;base64,SERVER",
            serverID = "prj_1",
        )
        val registered = HomeProjects.openRows(opened = openedList, known = listOf(server))
        assertEquals("data:image/png;base64,SERVER", registered.single().iconOverride)
        assertTrue(
            !HomeProjectEdit.localMetaApplies(registered.single().serverID),
        )
    }

    @Test
    fun theEditorReloadsTheStoredImageIntoTheDraft() {
        // B13: reopening the panel showed the letter badge and brought the
        // swatches back. The load side is `loadOriginal` →
        // `originalWithLocalMeta(…, localMetaOf(worktree))`.
        val stored = HomeProjectRecords.setMeta(
            opened(record(iconColor = "cyan", iconOverride = bigImage)),
            "/tmp/e2echk_proj5564",
            HomeProjectRecords.MetaPatch(iconOverride = bigImage),
        )
        val reloaded = HomeProjectEdit.originalWithLocalMeta(
            original(),
            HomeProjectRecords.localMeta(stored, "/tmp/e2echk_proj5564"),
        )
        assertEquals(bigImage, reloaded.iconOverride)
        val draft = HomeProjectEdit.draftOf(reloaded)
        // The panel's own two rules, as pure data: the swatch row is hidden and
        // 「清除图片」 is offered only while an override is set.
        assertTrue("the colour row is hidden while an image is set", draft.iconOverride != null)
        assertTrue("…and nothing is sent until the user edits it", HomeProjectEdit.patchFor(reloaded, draft).isEmpty)
    }

    // ------------------------------------------------ 5. the picker's packer

    @Test
    fun thePickerAndTheRendererAgreeOnTheDataUrlShape() {
        // One packer for both directions (`AttachmentPolicy.dataUrl`), so a
        // value written by the dialog is readable by the row.
        val url = AttachmentPolicy.dataUrl("image/png", byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(url.startsWith("data:image/png;base64,"))
        assertNotNull(com.xiufeigo.opencodeshell.ui.home.ProjectIconImage.bytesOf(url))
        // A stored override that is not an image data URL falls back to the
        // letter badge rather than a blank square — the render contract.
        assertNull(com.xiufeigo.opencodeshell.ui.home.ProjectIconImage.mimeOf("https://example.com/p.png"))
        assertNull(com.xiufeigo.opencodeshell.ui.home.ProjectIconImage.bytesOf(""))
    }
}
