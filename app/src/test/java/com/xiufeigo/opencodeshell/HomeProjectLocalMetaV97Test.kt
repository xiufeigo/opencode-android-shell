package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.data.ProjectPaths
import com.xiufeigo.opencodeshell.ui.home.HomeProjectEdit
import com.xiufeigo.opencodeshell.ui.home.HomeProjects
import com.xiufeigo.opencodeshell.ui.home.WriteTarget
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v97 P1（`research/v97_fix/PROJECT_EDIT_LOCAL_META.md`，上游取证见
 * `research/v97_audit/PROJECT_EDIT_UPSTREAM.md`）——「编辑项目」对**未在服务器
 * 注册**的本地目录项目 100% 不可用。
 *
 * 缺陷现场：面板两个 EditText `enabled=false focusable=false`、保存禁用、文案
 * 「该项目未在服务器注册」，4 种输入手法零字符落地（5560 Pixel_Tablet 真机）。
 *
 * 上游真相：**没有这个闸门**。`project.id == null` 在上游只出现在**传输层三元**
 * （`project-model.ts:41-46`、`project-row.tsx:60-65` 两处独立出现）里，用来选
 * 写哪个通道 —— `PATCH /api/project/{id}` 还是 `sync.project.meta(worktree,
 * patch)`（客户端本地 `ProjectMeta`，`persistence.ts:119-132`）。编辑面板
 * `project.tsx` 全文 grep `disabled|readOnly` **命中 0**；唯一的编辑期禁用是
 * `project-row.tsx:103` 的 `disabled={store.editor?.saving}`，只看 `saving`。
 *
 * 本类把修复后的四段语义逐条钉住：
 * 1. 持久化往返（长 `data:` URL 也在内）与旧格式解码；
 * 2. `setMeta` 的**字段级浅合并**（`child-store.ts:335-351`）；
 * 3. 保存路由 `writeTargetFor` 与展示合并 `localMetaApplies`（`runtime.tsx:151`）；
 * 4. 弹层源码不再存在 `projectID != null &&` 闸门 / `?: return` 早退。
 */
class HomeProjectLocalMetaV97Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val dialog = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectEditDialog.kt")

    /** `data:image/png;base64,` + 8 KB of payload — an icon that is genuinely large. */
    private val bigImage: String =
        "data:image/png;base64," + "iVBORw0KGgoAAAANSUhEUg".repeat(400)

    private fun record(
        worktree: String = "/work/api",
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

    // ------------------------------------------------ 1. persistence round trip

    @Test
    fun everyMetaFieldSurvivesEncodeAndDecode() {
        val state = opened(
            record(
                worktree = "C:\\src\\app",
                name = "E2ECHK_v97",
                iconColor = "cyan",
                iconOverride = bigImage,
                startup = "npm run dev",
            ),
        )
        val decoded = HomeProjectRecords.decode(HomeProjectRecords.encode(state))
        assertEquals(state, decoded)
        val row = decoded.list.single()
        assertEquals("E2ECHK_v97", row.name)
        assertEquals("cyan", row.iconColor)
        assertEquals(bigImage, row.iconOverride)
        assertEquals("npm run dev", row.startup)
    }

    @Test
    fun aLongIconOverrideSurvivesTheRoundTripByteForByte() {
        // The pick is downsampled, but a `data:` URL is still by far the largest
        // thing the record holds; a truncation or an escape in the persisted
        // form would corrupt the avatar on the very next launch.
        assertTrue("the fixture must be large", bigImage.length > 8_000)
        val state = opened(record(worktree = "/work/big", iconOverride = bigImage))
        val row = HomeProjectRecords.decode(HomeProjectRecords.encode(state)).list.single()
        assertEquals(bigImage, row.iconOverride)
        assertEquals(bigImage.length, row.iconOverride!!.length)
    }

    @Test
    fun aRecordWrittenBeforeTheMetaExistedDecodesToTheDefaults() {
        // The exact shape an older build persisted: worktree + sandboxes only.
        val decoded = HomeProjectRecords.decode(
            """
            {"projects":[
              {"worktree":"/work/api"},
              {"worktree":"C:\\src\\app","sandboxes":["C:\\sb"],"name":"Old"},
              "/work/bare"
            ],"recentlyClosed":[],"lastProject":"/work/api"}
            """.trimIndent(),
        )
        assertEquals(listOf("/work/api", "C:\\src\\app", "/work/bare"), decoded.list.map { it.worktree })
        val old = decoded.list[1]
        assertEquals("Old", old.name)
        assertNull(old.iconColor)
        assertNull(old.iconOverride)
        assertNull(old.startup)
        // ...and the defaults must be what an encode writes back: the same
        // record, unchanged, rather than three explicit nulls.
        val again = HomeProjectRecords.encode(decoded)
        assertFalse("a default meta must not be persisted", again.contains("iconOverride"))
        assertFalse(again.contains("iconColor"))
        assertFalse(again.contains("startup"))
        assertEquals(decoded, HomeProjectRecords.decode(again))
    }

    @Test
    fun aJsonNullMetaReadsAsNothingRatherThanTheLiteralNull() {
        // `org.json`'s `optString` answers "null" for a JSON null — the same trap
        // the record's `name` and the server row's `icon.override` already carry.
        val decoded = HomeProjectRecords.decode(
            """{"projects":[{"worktree":"/w","name":null,"iconColor":null,"iconOverride":null,"startup":"null"}]}""",
        )
        val row = decoded.list.single()
        assertEquals("", row.name)
        assertNull(row.iconColor)
        assertNull(row.iconOverride)
        assertNull(row.startup)
    }

    // ------------------------------------------------------ 2. the meta merge

    @Test
    fun aNameOnlyPatchLeavesTheIconAndTheCommandAlone() {
        // `child-store.ts:341-348`: `icon = {...previous.icon, ...patch.icon}` —
        // renaming must never drop the image or the tint, and vice versa.
        val before = opened(
            record(name = "Old", iconColor = "pink", iconOverride = bigImage, startup = "make dev"),
        )
        val after = HomeProjectRecords.setMeta(before, "/work/api", HomeProjectRecords.MetaPatch(name = "New"))
        val row = after.list.single()
        assertEquals("New", row.name)
        assertEquals("pink", row.iconColor)
        assertEquals(bigImage, row.iconOverride)
        assertEquals("make dev", row.startup)
    }

    @Test
    fun aColorOnlyPatchLeavesTheImageAlone() {
        val before = opened(record(name = "Keep", iconColor = "pink", iconOverride = bigImage, startup = "dev"))
        val row = HomeProjectRecords
            .setMeta(before, "/work/api", HomeProjectRecords.MetaPatch(iconColor = "green"))
            .list
            .single()
        assertEquals("Keep", row.name)
        assertEquals("green", row.iconColor)
        assertEquals(bigImage, row.iconOverride)
        assertEquals("dev", row.startup)
    }

    @Test
    fun anEmptyStringClearsAndANullLeavesTheFieldUntouched() {
        val full = opened(record(name = "N", iconColor = "cyan", iconOverride = "data:image/png;base64,AA", startup = "dev"))
        // null = "not in this patch" (upstream omits the key), "" = clear it.
        val untouched = HomeProjectRecords.setMeta(full, "/work/api", HomeProjectRecords.MetaPatch())
        assertEquals(full, untouched)
        val cleared = HomeProjectRecords.setMeta(
            full,
            "/work/api",
            HomeProjectRecords.MetaPatch(
                name = "",
                iconColor = "",
                iconOverride = "",
                startup = "",
            ),
        ).list.single()
        assertEquals("", cleared.name)
        assertNull(cleared.iconColor)
        assertNull(cleared.iconOverride)
        assertNull(cleared.startup)
    }

    @Test
    fun setMetaUsesTheStoresOwnDirectoryIdentityAndNeverInventsARow() {
        val before = opened(record(worktree = "C:\\src\\app", name = "Kept"))
        val twin = HomeProjectRecords.setMeta(
            before,
            "c:/SRC/app/",
            HomeProjectRecords.MetaPatch(iconColor = "orange"),
        )
        assertEquals("Kept", twin.list.single().name)
        assertEquals("orange", twin.list.single().iconColor)
        // A directory this client has not opened is not a row (membership is
        // `open`'s job) and a blank directory is nothing at all.
        assertEquals(before, HomeProjectRecords.setMeta(before, "/elsewhere", HomeProjectRecords.MetaPatch(name = "X")))
        assertEquals(before, HomeProjectRecords.setMeta(before, "   ", HomeProjectRecords.MetaPatch(name = "X")))
        assertEquals(before, HomeProjectRecords.setMeta(before, "/work/api", HomeProjectRecords.MetaPatch()))
        assertFalse(HomeProjectRecords.MetaPatch(name = "X").isEmpty)
    }

    @Test
    fun theRecordReadSeamAnswersWhatWasWritten() {
        var state = opened(record(worktree = "/work/api"))
        assertEquals(HomeProjectRecords.LocalMeta.NONE, HomeProjectRecords.localMeta(state, "/work/api"))
        state = HomeProjectRecords.setMeta(
            state,
            "/work/api",
            HomeProjectRecords.MetaPatch(name = "Local", iconColor = "gray", startup = "dev"),
        )
        val meta = HomeProjectRecords.localMeta(state, "C:/WORK/API")
        assertEquals("Local", meta.name)
        assertEquals("gray", meta.iconColor)
        assertEquals("dev", meta.startup)
        assertEquals(HomeProjectRecords.LocalMeta.NONE, HomeProjectRecords.localMeta(state, "/nope"))
    }

    @Test
    fun twinsAndARestoreDoNotWipeTheMeta() {
        // `decode` heals a record an older build persisted twice; the twin that
        // carries the meta must survive the collapse.
        val healed = HomeProjectRecords.decode(
            """{"projects":[{"worktree":"C:\\repo","name":"First","iconColor":"pink"}, {"worktree":"c:/repo/","iconOverride":"data:image/png;base64,BB"}]}""",
        )
        val row = healed.list.single()
        assertEquals("First", row.name)
        assertEquals("pink", row.iconColor)
        assertEquals("data:image/png;base64,BB", row.iconOverride)
    }

    // --------------------------------------------- 3. the two transport forks

    @Test
    fun theSaveRouteIsAProjectIdQuestionAndNothingElse() {
        // `project-model.ts:41-46` / `project-row.tsx:60-65`: one ternary, twice.
        assertEquals(
            WriteTarget.Server("prj_1"),
            HomeProjectEdit.writeTargetFor("prj_1"),
        )
        // The three ways a worktree has no server project, all of them ordinary
        // upstream (a client-local directory, a blank id, the pseudo `global`).
        assertEquals(WriteTarget.Local, HomeProjectEdit.writeTargetFor(null))
        assertEquals(WriteTarget.Local, HomeProjectEdit.writeTargetFor(""))
        assertEquals(WriteTarget.Local, HomeProjectEdit.writeTargetFor("  "))
        assertEquals(WriteTarget.Local, HomeProjectEdit.writeTargetFor("global"))
    }

    @Test
    fun theLocalMetaCoversTheServerOnlyWhenThereIsNoServerProject() {
        // `runtime.tsx:151`: `...(!metadata || metadata.id === "global" ?
        // childStore.projectMeta : undefined)`.
        assertTrue(HomeProjectEdit.localMetaApplies(null))
        assertFalse("a registered project is owned by the server", HomeProjectEdit.localMetaApplies("prj_1"))
    }

    @Test
    fun aReloadedUnregisteredProjectOffersBackWhatWasSaved() {
        // What `loadOriginal` hands the dialog for a worktree the catalogue does
        // not list, now that the local meta is merged in.
        val empty = HomeProjectEdit.Original(
            projectID = null,
            name = "",
            color = null,
            startup = "",
            folderName = "api",
        )
        val reloaded = HomeProjectEdit.originalWithLocalMeta(
            empty,
            HomeProjectRecords.LocalMeta(
                name = "E2ECHK_v97",
                iconColor = "cyan",
                iconOverride = bigImage,
                startup = "npm run dev",
            ),
        )
        assertEquals("E2ECHK_v97", reloaded.name)
        assertEquals("cyan", reloaded.color)
        assertEquals(bigImage, reloaded.iconOverride)
        assertEquals("npm run dev", reloaded.startup)
        assertEquals("api", reloaded.folderName)
        // A field the record never set stays the original's (field-level, again).
        val partial = HomeProjectEdit.originalWithLocalMeta(
            empty,
            HomeProjectRecords.LocalMeta(name = "OnlyName"),
        )
        assertEquals("OnlyName", partial.name)
        assertNull(partial.color)
        assertNull(partial.iconOverride)
        assertEquals("", partial.startup)
    }

    @Test
    fun theServerPatchAndTheLocalPatchCarryTheSameTriState() {
        val patch = HomeProjectEdit.Patch(name = "N", iconColor = "cyan", startup = "dev")
        val meta = HomeProjectEdit.metaPatchFor(patch)
        assertEquals("N", meta.name)
        assertEquals("cyan", meta.iconColor)
        assertEquals("dev", meta.startup)
        // An untouched field is null on both channels: the server route drops the
        // key off the body, the local route keeps the stored value.
        assertNull(meta.iconOverride)
        assertTrue(meta.isEmpty.not())
        assertTrue(HomeProjectEdit.metaPatchFor(HomeProjectEdit.Patch()).isEmpty)
        // `""` is a real value on both — it clears.
        assertEquals(
            HomeProjectRecords.MetaPatch(name = "", iconColor = ""),
            HomeProjectEdit.metaPatchFor(HomeProjectEdit.Patch(name = "", iconColor = "")),
        )
    }

    @Test
    fun theColumnDrawsTheLocalIconForAnUnregisteredRowAndTheCatalogueForARegisteredOne() {
        val local = HomeProjectRecords.Project(
            worktree = "/work/api",
            name = "Local",
            iconColor = "pink",
            iconOverride = "data:image/png;base64,LOCAL",
        )
        val unregistered = HomeProjects.openRows(opened = listOf(local), known = emptyList())
        assertEquals("pink", unregistered.single().iconColor)
        assertEquals("data:image/png;base64,LOCAL", unregistered.single().iconOverride)

        val catalogue = HomeProjects.merge(
            projects = listOf(
                OpenCodeClient.Project(
                    id = "prj_1",
                    worktree = "/work/api",
                    vcs = "git",
                    iconColor = "mint",
                    iconOverride = "data:image/png;base64,SERVER",
                ),
            ),
            sessions = emptyList(),
        )
        val registered = HomeProjects.openRows(opened = listOf(local), known = catalogue)
        assertEquals("mint", registered.single().iconColor)
        assertEquals("data:image/png;base64,SERVER", registered.single().iconOverride)
        // The server also owns the name, which it always did.
        assertEquals("Local", registered.single().name)
        assertTrue(
            "the server pseudo project has no PATCH route, so it does not own the row",
            HomeProjectEdit.localMetaApplies(HomeProjects.merge(
                projects = listOf(
                    OpenCodeClient.Project(id = "global", worktree = "/work/api", vcs = null, iconColor = null),
                ),
                sessions = emptyList(),
            ).single().serverID),
        )
    }

    // ------------------------------------------------------ 4. the dialog gates

    @Test
    fun theDialogHasNoProjectIdGateLeft() {
        // The two lines the device run failed on, verbatim from the audit
        // (`:222` and `:266`). Either shape of gate re-closes the feature.
        assertFalse(
            "editable must not be gated on a server project id",
            Regex("""projectID\s*!=\s*null\s*&&""").containsMatchIn(dialog),
        )
        assertFalse(
            "Save must not be gated on a server project id",
            Regex("""enabled\s*=\s*state\?\.projectID""").containsMatchIn(dialog),
        )
        // The `:269` early return: the local route is unreachable if a missing
        // id still returns from the click handler.
        assertFalse(
            "a missing project id must not return from the save click",
            Regex("""projectID\s*=\s*state\?\.projectID\s*\?:""").containsMatchIn(dialog),
        )
        // Upstream's only edit-time gate is the write in flight.
        assertTrue(dialog.contains("val editable = state != null && !saving"))
        assertTrue(dialog.contains("enabled = currentPatch?.isEmpty == false && !saving,"))
    }

    @Test
    fun theDialogRoutesTheSaveThroughThePureFork() {
        assertTrue(dialog.contains("HomeProjectEdit.writeTargetFor(state?.projectID)"))
        assertTrue(dialog.contains("is WriteTarget.Server -> api.save(target.projectID, patch)"))
        assertTrue(dialog.contains("WriteTarget.Local -> api.saveLocal(project.path, patch)"))
    }

    @Test
    fun theNotRegisteredCopyIsGoneFromThePanelAndFromTheRefreshToast() {
        // Q3: upstream's i18n has no such string at all (the audit's grep over
        // `runtime/i18n/en.ts` hit one line, a trademark).
        //
        // Superseded by the v97 parity round (`research/v97_fix/PROJECT_PARITY_FIX.md` ①),
        // which closed the second user this test used to require. 「Refresh worktrees」
        // upstream is the session workspace menu's own open-time refresh, typed on
        // a **server** project (`session-workspace-menu.tsx:18`) and swallowed on
        // failure (`.catch(() => undefined)`, `:57`) — so a directory with no
        // server project has no such action at all, and this shell now hides the
        // menu item there and stays quiet if the call still finds none. The copy
        // therefore has **no** user left in `ui/home/**`; the string itself stays
        // in all three locales (removing a key is a separate change), unused.
        assertFalse(dialog.contains("R.string.oc_home_project_edit_missing"))
        val home = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
        assertFalse(
            "the refusal toast is gone — upstream never shows this state at all",
            // The `R.string.` form, so the assertion reads the code and not the
            // handler's KDoc, which deliberately discusses the key it dropped.
            home.contains("R.string.oc_home_project_edit_missing"),
        )
        assertTrue(home.contains("if (result is ProjectWrite.NotRegistered) return@launch"))
    }

    @Test
    fun theHostHandsThePanelTheStoreTheColumnObserves() {
        // The instance question (report §设计决策): a second store would leave
        // the save invisible until the next cold start.
        val home = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
        assertTrue(home.contains("val projectApi = rememberHomeProjectApi(homeProjectStore)"))
        val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        assertTrue(
            "one store per server, remembered at the root",
            app.contains("val homeProjects = remember(context, serverKey) { HomeProjectStore(context, serverKey) }"),
        )
        // ...and the column reads that very flow, keyed so a meta write recomputes.
        assertTrue(home.contains("val openedProjects = homeProjectStore?.state?.collectAsStateWithLifecycle()?.value"))
        assertTrue(
            home.contains(
                "val projects = remember(openedProjects.list, openedProjects.recentlyClosed, " +
                    "serverProjects, visibleSessions, serverNames)",
            ),
        )
    }

    @Test
    fun theApiSeamIsInjectedNotSelfServed() {
        val api = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectApi.kt")
        assertTrue(api.contains("private val localStore: HomeProjectStore? = null"))
        assertTrue(api.contains("store.setMeta(worktree, HomeProjectEdit.metaPatchFor(patch))"))
        assertTrue(api.contains("localStore?.localMeta(worktree) ?: HomeProjectRecords.LocalMeta.NONE"))
        // No server at all stays a failure — the existing contract is untouched.
        assertTrue(
            "a host with no server still fails the load, it does not silently answer local",
            api.contains("val api = api() ?: return ProjectLoad.Failed(ProjectFault.NoServer)"),
        )
    }

    @Test
    fun theDirectoryIdentityIsStillTheStoresOwn() {
        // No invented key: the meta write answers on `sameDirectory`, so the
        // drive-less spelling the add-project picker produces (`/SRC/app` for
        // `C:\src\app`) finds its twin's record.
        assertTrue(ProjectPaths.sameDirectory("/work/api", "/work/api/"))
        val state = opened(record(worktree = "C:\\src\\app", startup = "dev"))
        val hit = HomeProjectRecords.setMeta(
            state,
            "/SRC/app",
            HomeProjectRecords.MetaPatch(iconColor = "orange"),
        )
        assertEquals("orange", hit.list.single().iconColor)
        assertEquals("dev", hit.list.single().startup)
    }
}
