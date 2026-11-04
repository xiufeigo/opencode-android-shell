package com.xiufeigo.opencodeshell.web.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App 内更新检查的**行为断言**（真跑 [UpdateChecker] 这个纯逻辑件）。
 *
 * ## 守护对象
 *  1. [UpdateChecker.parseVersionTag] —— tag → 版本号（`v2.0.28` → 2.0.28）。
 *  2. [UpdateChecker.parseVersionFromText] —— asset 文件名 → 版本号。
 *  3. [UpdateChecker.SemVer] —— 语义化比较（major.minor.patch，远端更高才算更新）。
 *  4. [UpdateChecker.decide] —— **节流**：同一远端版本最多提醒一次。
 *  5. [UpdateChecker.parseRelease] / [UpdateChecker.selectDownloadUrl] ——
 *     GitHub Release JSON 的结构契约 + `.apk` 优先的落点选择。
 *
 * ## 每条断言分别能红在哪
 * - `tagParsingKeepsTheVersionNumber`：把 `removePrefix("v")` 去掉 ⇒ 红。
 * - `tagParsingRejectsDatesAndGarbage`：改成宽松数字匹配 ⇒ 红（`2026` 会被当成版本号）。
 * - `versionComparisonIsSemantic`：`compareTo` 退化成字符串比较 ⇒ 红（`"2.0.9" > "2.0.28"` 为假）。
 * - `sameRemoteVersionIsNotifiedOnlyOnce`：节流键从"远端版本"改成"永远同一串" ⇒ 红。
 * - `newRemoteVersionNotifiesAgain`：节流键写成"本机版本" ⇒ 红（换了远端版本就不再提醒）。
 * - `downloadPrefersApkAsset` / `downloadFallsBackToReleasePage`：落点选择退化成只用 html_url ⇒ 红。
 * - `releaseJsonIsParsedAsExpected`：JSON 字段名改了（`tag_name` → `tag`）⇒ 红。
 *
 * ⚠️ 本文件**不 import 任何 android.\* 符号** —— `webapp` 没开
 * `unitTests.isReturnDefaultValues`，碰了 android 的调用会抛 `not mocked`。
 * 纯逻辑放 [UpdateChecker] 正是为了让这层断言能在 JVM 里真跑。
 */
class UpdateCheckerTest {

    // ---- 1. tag 解析 ----------------------------------------------------

    @Test
    fun tagParsingKeepsTheVersionNumber() {
        assertEquals(
            "tag `v2.0.28` 必须解析成 2.0.28 —— GitHub 上就是这个形态。",
            UpdateChecker.SemVer(2, 0, 28),
            UpdateChecker.parseVersionTag("v2.0.28"),
        )
        assertEquals(
            "tag 没有 `v` 前缀也要能解析。",
            UpdateChecker.SemVer(2, 0, 28),
            UpdateChecker.parseVersionTag("2.0.28"),
        )
        assertEquals(
            "大写 `V` 前缀同样接受。",
            UpdateChecker.SemVer(2, 0, 28),
            UpdateChecker.parseVersionTag("V2.0.28"),
        )
        assertEquals(
            "`release-` 前缀要能剥掉。",
            UpdateChecker.SemVer(2, 0, 28),
            UpdateChecker.parseVersionTag("release-2.0.28"),
        )
        assertEquals(
            "预发布后缀不参与版本号比较（是否预发布由 API 的 prerelease 字段判）。",
            UpdateChecker.SemVer(2, 0, 28),
            UpdateChecker.parseVersionTag("v2.0.28-beta.1"),
        )
        assertEquals(
            "缺省次段按 0：`v2.0` ⇒ 2.0.0。",
            UpdateChecker.SemVer(2, 0, 0),
            UpdateChecker.parseVersionTag("v2.0"),
        )
        assertEquals(
            "缺省修订段按 0：`v2` ⇒ 2.0.0。",
            UpdateChecker.SemVer(2, 0, 0),
            UpdateChecker.parseVersionTag("v2"),
        )
        assertEquals(
            "归一化串就是版本名本身（节流键用它）。",
            "2.0.28",
            UpdateChecker.parseVersionTag("v2.0.28")?.normalized,
        )
    }

    @Test
    fun tagParsingRejectsDatesAndGarbage() {
        assertNull(
            "`nightly-2026-10-30` 这种 tag 不是版本号 —— 解析成 2026 会让" +
                "「2026 > 2」⇒ 误报新版本。",
            UpdateChecker.parseVersionTag("nightly-2026-10-30"),
        )
        assertNull(
            "纯日期 tag 同样不是版本号。",
            UpdateChecker.parseVersionTag("2026-10-30"),
        )
        assertNull("空串不是版本号。", UpdateChecker.parseVersionTag(""))
        assertNull("null 不是版本号。", UpdateChecker.parseVersionTag(null))
        assertNull("只有一个 `v` 没有数字不是版本号。", UpdateChecker.parseVersionTag("v"))
        assertNull(
            "`version-2.0.28` 这种词头不该被当成 `v` 前缀（v 后面必须紧跟数字）。",
            UpdateChecker.parseVersionTag("version-2.0.28"),
        )
    }

    @Test
    fun assetNameParsingFindsTheVersion() {
        assertEquals(
            "APK 文件名里的版本号是 tag 解析不出来时的兜底来源。",
            UpdateChecker.SemVer(2, 0, 28),
            UpdateChecker.parseVersionFromText("opencode-2.0.28.apk"),
        )
        assertEquals(
            "web 包文件名同样能解析。",
            UpdateChecker.SemVer(2, 0, 28),
            UpdateChecker.parseVersionFromText("opencode-web-2.0.28.zip"),
        )
        assertEquals(
            "server 浏览器包是另一个版本线（0.1.0），不能当成 App 版本。",
            UpdateChecker.SemVer(0, 1, 0),
            UpdateChecker.parseVersionFromText("opencode-server-browser-0.1.0.zip"),
        )
        assertNull("校验和文件里没有版本号。", UpdateChecker.parseVersionFromText("SHA256SUMS"))
        assertNull("空串不是版本号。", UpdateChecker.parseVersionFromText(""))
    }

    // ---- 2. 语义化比较 --------------------------------------------------

    @Test
    fun versionComparisonIsSemantic() {
        val local = UpdateChecker.SemVer(2, 0, 27)
        val remote = UpdateChecker.SemVer(2, 0, 28)

        assertTrue("2.0.28 必须比 2.0.27 新。", remote.isNewerThan(local))
        assertFalse("相等不算更新。", remote.isNewerThan(remote))
        assertFalse("旧版本不算更新。", local.isNewerThan(remote))
        assertTrue(
            "主版本号优先：3.0.0 比 2.99.99 新。",
            UpdateChecker.SemVer(3, 0, 0).isNewerThan(UpdateChecker.SemVer(2, 99, 99)),
        )
        assertTrue(
            "次版本号次之：2.1.0 比 2.0.99 新。",
            UpdateChecker.SemVer(2, 1, 0).isNewerThan(UpdateChecker.SemVer(2, 0, 99)),
        )
        assertFalse(
            "字符串比较会判 `2.0.9` 比 `2.0.28` 新 —— 这里必须是数值比较。",
            UpdateChecker.SemVer(2, 0, 9).isNewerThan(UpdateChecker.SemVer(2, 0, 28)),
        )
    }

    // ---- 3. 节流 --------------------------------------------------------

    private fun release(tag: String? = "v2.0.28", draft: Boolean = false, prerelease: Boolean = false) =
        UpdateChecker.ReleaseInfo(
            tag = tag,
            htmlUrl = "https://github.com/xiufeigo/opencode-android-shell/releases/tag/v2.0.28",
            draft = draft,
            prerelease = prerelease,
            assets = listOf(
                UpdateChecker.ReleaseAsset(
                    name = "opencode-2.0.28.apk",
                    browserDownloadUrl = "https://github.com/xiufeigo/opencode-android-shell/releases/download/v2.0.28/opencode-2.0.28.apk",
                ),
                UpdateChecker.ReleaseAsset(
                    name = "SHA256SUMS",
                    browserDownloadUrl = "https://github.com/xiufeigo/opencode-android-shell/releases/download/v2.0.28/SHA256SUMS",
                ),
            ),
        )

    private val localVersion = UpdateChecker.SemVer(2, 0, 27)

    @Test
    fun sameRemoteVersionIsNotifiedOnlyOnce() {
        // 第一次看到 2.0.28 ⇒ 该提醒。
        val first = UpdateChecker.decide(release(), localVersion, lastNotified = null, lastDialog = null)
        assertTrue("首次看到新版本必须提醒。", first.hasUpdate)
        assertTrue("首次看到新版本必须发通知。", first.notify)
        assertTrue("首次看到新版本应该弹一次对话框。", first.dialog)
        assertEquals("节流键就是归一化的远端版本号。", "2.0.28", first.offer?.throttleKey)

        // 记下节流键之后再看到同一个 2.0.28 ⇒ 不再提醒。
        val second = UpdateChecker.decide(
            release(),
            localVersion,
            lastNotified = "2.0.28",
            lastDialog = "2.0.28",
        )
        assertFalse("同一远端版本不该重复发通知。", second.notify)
        assertFalse("同一远端版本不该重复弹对话框。", second.dialog)
        assertTrue("结论仍然是「有新版」（只是不再重复提醒）。", second.hasUpdate)
    }

    @Test
    fun newRemoteVersionNotifiesAgain() {
        val next = UpdateChecker.decide(
            release(tag = "v2.0.29"),
            localVersion,
            lastNotified = "2.0.28",
            lastDialog = "2.0.28",
        )
        assertTrue("换了远端版本必须重新提醒。", next.notify)
        assertTrue("换了远端版本应该再弹一次对话框。", next.dialog)
        assertEquals("节流键跟着远端版本走。", "2.0.29", next.offer?.throttleKey)
    }

    @Test
    fun notificationAndDialogThrottleIndependently() {
        // 通知发过、对话框没弹过（用户当时在后台）⇒ 只补弹对话框。
        val onlyDialog = UpdateChecker.decide(
            release(),
            localVersion,
            lastNotified = "2.0.28",
            lastDialog = null,
        )
        assertFalse("通知已经发过了，不该再发。", onlyDialog.notify)
        assertTrue("对话框没弹过，该补一次。", onlyDialog.dialog)
    }

    @Test
    fun noUpdateCasesProduceNoReminder() {
        val networkFail = UpdateChecker.decide(null, localVersion, null, null)
        assertFalse("网络失败不提醒。", networkFail.hasUpdate)
        assertFalse("网络失败不发通知。", networkFail.notify)
        assertFalse("网络失败不弹框。", networkFail.dialog)

        val draft = UpdateChecker.decide(release(draft = true), localVersion, null, null)
        assertFalse("草稿不是正式版。", draft.hasUpdate)

        val pre = UpdateChecker.decide(release(prerelease = true), localVersion, null, null)
        assertFalse("预发布不是正式版。", pre.hasUpdate)

        val unparsable = UpdateChecker.decide(
            release(tag = "nightly").copy(
                // 附件名里也没有版本号 ⇒ tag 与 asset 两条路都断 ⇒ 无版本可比。
                assets = listOf(
                    UpdateChecker.ReleaseAsset(
                        name = "SHA256SUMS",
                        browserDownloadUrl = "https://example.invalid/SHA256SUMS",
                    ),
                ),
            ),
            localVersion,
            null,
            null,
        )
        assertFalse(
            "版本号解析不出来（tag 与 asset 都没有版本号）⇒ 宁可漏提醒也不误报。",
            unparsable.hasUpdate,
        )

        val upToDate = UpdateChecker.decide(release(tag = "v2.0.27"), localVersion, null, null)
        assertFalse("本机已是最新（版本号相等）⇒ 不提醒。", upToDate.hasUpdate)

        val older = UpdateChecker.decide(release(tag = "v2.0.26"), localVersion, null, null)
        assertFalse("远端比本机旧 ⇒ 不提醒。", older.hasUpdate)
    }

    // ---- 4. 落点选择 ----------------------------------------------------

    @Test
    fun downloadPrefersApkAsset() {
        val url = UpdateChecker.selectDownloadUrl(release())
        assertEquals(
            "落点必须优先 `.apk` 的 browser_download_url（直接下 APK）。",
            "https://github.com/xiufeigo/opencode-android-shell/releases/download/v2.0.28/opencode-2.0.28.apk",
            url,
        )
    }

    @Test
    fun downloadFallsBackToReleasePage() {
        val noApk = release().copy(
            assets = listOf(
                UpdateChecker.ReleaseAsset(
                    name = "opencode-web-2.0.28.zip",
                    browserDownloadUrl = "https://example.invalid/web.zip",
                ),
            ),
        )
        assertEquals(
            "没有 `.apk` 时退回 Release 页（而不是退回到 zip 包）。",
            "https://github.com/xiufeigo/opencode-android-shell/releases/tag/v2.0.28",
            UpdateChecker.selectDownloadUrl(noApk),
        )
    }

    @Test
    fun releaseVersionFallsBackToApkAssetName() {
        val brokenTag = release(tag = "nightly-build").copy(
            assets = listOf(
                UpdateChecker.ReleaseAsset(
                    name = "opencode-2.0.28.apk",
                    browserDownloadUrl = "https://example.invalid/app.apk",
                ),
            ),
        )
        assertEquals(
            "tag 解析不出来时退回 `.apk` 文件名里的版本号。",
            UpdateChecker.SemVer(2, 0, 28),
            UpdateChecker.releaseVersion(brokenTag),
        )
    }

    // ---- 5. GitHub Release JSON 的结构契约 ------------------------------

    /**
     * 真实响应的**裁剪版**（字段与 GitHub `releases/latest` 一致，体积裁到能进源码）。
     *
     * 为什么要留这份 fixture：GitHub 改了字段名/结构 ⇒ 这里直接红，
     * 而不是等到真机点"去更新"才发现下不到包。
     */
    private val realPayload = """
        {
          "url": "https://api.github.com/repos/xiufeigo/opencode-android-shell/releases/429015881",
          "html_url": "https://github.com/xiufeigo/opencode-android-shell/releases/tag/v2.0.28",
          "id": 429015881,
          "tag_name": "v2.0.28",
          "target_commitish": "main",
          "name": "OpenCode v2.0.28",
          "draft": false,
          "immutable": false,
          "prerelease": false,
          "created_at": "2026-10-30T12:31:36Z",
          "published_at": "2026-10-30T12:37:10Z",
          "assets": [
            {
              "url": "https://api.github.com/repos/xiufeigo/opencode-android-shell/releases/assets/692078361",
              "id": 692078361,
              "name": "opencode-2.0.28.apk",
              "label": "",
              "uploader": {
                "login": "github-actions[bot]",
                "id": 41898282,
                "node_id": "MDM6Qm90NDE4OTgyODI=",
                "avatar_url": "https://avatars.githubusercontent.com/u/41898282?v=4"
              },
              "content_type": "application/vnd.android.package-archive",
              "state": "uploaded",
              "size": 59400842,
              "download_count": 3,
              "created_at": "2026-10-30T12:37:07Z",
              "updated_at": "2026-10-30T12:37:09Z",
              "browser_download_url": "https://github.com/xiufeigo/opencode-android-shell/releases/download/v2.0.28/opencode-2.0.28.apk"
            },
            {
              "url": "https://api.github.com/repos/xiufeigo/opencode-android-shell/releases/assets/692078358",
              "id": 692078358,
              "name": "opencode-server-browser-0.1.0.zip",
              "content_type": "application/zip",
              "state": "uploaded",
              "size": 57399,
              "browser_download_url": "https://github.com/xiufeigo/opencode-android-shell/releases/download/v2.0.28/opencode-server-browser-0.1.0.zip"
            },
            {
              "url": "https://api.github.com/repos/xiufeigo/opencode-android-shell/releases/assets/692078360",
              "id": 692078360,
              "name": "SHA256SUMS",
              "content_type": "application/octet-stream",
              "state": "uploaded",
              "size": 276,
              "browser_download_url": "https://github.com/xiufeigo/opencode-android-shell/releases/download/v2.0.28/SHA256SUMS"
            }
          ],
          "tarball_url": "https://api.github.com/repos/xiufeigo/opencode-android-shell/tarball/v2.0.28",
          "body": null
        }
    """.trimIndent()

    @Test
    fun releaseJsonIsParsedAsExpected() {
        val parsed = UpdateChecker.parseRelease(realPayload)
        assertNotNull("真实的 Release JSON 必须能解析出来。", parsed)
        val info = parsed!!
        assertEquals("tag_name 读对了。", "v2.0.28", info.tag)
        assertFalse("draft 读对了。", info.draft)
        assertFalse("prerelease 读对了。", info.prerelease)
        assertEquals(
            "html_url 读对了（落点兜底用）。",
            "https://github.com/xiufeigo/opencode-android-shell/releases/tag/v2.0.28",
            info.htmlUrl,
        )
        assertEquals("附件数量读对了。", 3, info.assets.size)
        assertEquals(
            "`.apk` 附件被正确挑出来（嵌套的 uploader 对象不能打乱解析）。",
            "opencode-2.0.28.apk",
            UpdateChecker.selectApkAsset(info.assets)?.name,
        )
        assertEquals(
            "`.apk` 直链读对了。",
            "https://github.com/xiufeigo/opencode-android-shell/releases/download/v2.0.28/opencode-2.0.28.apk",
            UpdateChecker.selectDownloadUrl(info),
        )
        assertEquals(
            "版本号从 tag 解析。",
            UpdateChecker.SemVer(2, 0, 28),
            UpdateChecker.releaseVersion(info),
        )
    }

    @Test
    fun brokenPayloadsParseToNull() {
        assertNull("空串不是 JSON。", UpdateChecker.parseRelease(""))
        assertNull("HTML 错误页不是 JSON。", UpdateChecker.parseRelease("<html>rate limited</html>"))
        assertNull("截断的 JSON 不能解析。", UpdateChecker.parseRelease("{\"tag_name\":\"v2.0.28\""))
    }

    @Test
    fun endToEndDecisionFromRealPayload() {
        val info = UpdateChecker.parseRelease(realPayload)!!
        val decision = UpdateChecker.decide(info, localVersion, lastNotified = null, lastDialog = null)
        assertTrue("真实 payload + 本机 2.0.27 ⇒ 判为有新版。", decision.hasUpdate)
        assertEquals("远端版本号 2.0.28。", UpdateChecker.SemVer(2, 0, 28), decision.offer?.version)
        assertTrue("首次必须提醒。", decision.notify && decision.dialog)
    }

    // ---- 6. 本机版本口径 ------------------------------------------------

    @Test
    fun localVersionNameParsesWithTheSameRule() {
        // build.gradle.kts 的 versionName 就是这个形态 ⇒ 同一套解析规则能吃下。
        assertEquals(
            UpdateChecker.SemVer(2, 0, 27),
            UpdateChecker.parseVersionTag("2.0.27"),
        )
    }
}
