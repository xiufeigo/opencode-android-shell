package com.xiufeigo.opencodeshell.web.host

/**
 * App 内更新检查的**纯逻辑层**（本文件**不 import 任何 android.\* 符号**）。
 *
 * ## 为什么要单独抽一层
 *
 * 与 [PowerPolicy] 同理：`webapp` 模块**没有**开 `unitTests.isReturnDefaultValues`
 * （`app` 模块开了，见 `app/build.gradle.kts:65`），于是任何在 JVM 单测里碰到
 * `android.util.Log` / `SharedPreferences` / `Context` 的代码都会抛
 * `RuntimeException: Method … not mocked`。
 *
 * 所以：**决策**全放在这个无 android 依赖的对象里（解析版本、比大小、该不该提醒），
 * 宿主（[AppUpdateChecker]）只做**接线**（联网、读偏好、发通知）。
 * 单测直接测决策（真行为断言），另有 `UpdateWiringTest` 做源码接线断言。
 *
 * ## 版本口径
 *
 * 产品版本以 `webapp/build.gradle.kts` 的 `versionName` 为准（当前 `2.0.27`，
 * `versionCode=55`）。GitHub Release 的 tag 是 `v2.0.28` 这种形态 ⇒ 去掉 `v` 就是
 * 同一个 `versionName`。**不按 versionCode 比**：GitHub Release 的元数据里没有
 * versionCode（只有 tag / asset 文件名），两边对不上号（`2.0.27 ↔ 55` 不是线性关系）。
 *
 * ⚠️ 已知的取舍：tag 解析不出来时退回 **asset 文件名**（`opencode-2.0.28.apk`），
 * 这是"同一个版本号"的另一个来源；仍解析不出来就**当作没有更新**（宁可漏提醒，
 * 不可误报版本）。
 */
object UpdateChecker {

    /**
     * GitHub Release 的**公开** API（无需 token）。
     *
     * 仓 `xiufeigo/opencode-android-shell` 已公开 ⇒ 这个端点匿名可读。
     * 真被限流（403）或改名（404）时，[AppUpdateChecker] 会静默失败。
     */
    const val RELEASES_API =
        "https://api.github.com/repos/xiufeigo/opencode-android-shell/releases/latest"

    /** Release 页（没有 `.apk` asset 时的兜底落点）。 */
    const val REPO_RELEASES_PAGE = "https://github.com/xiufeigo/opencode-android-shell/releases/latest"

    /**
     * 主版本号上限：超过它就**判定解析到的多半不是版本号**。
     *
     * 真实场景：某些 tag 形如 `nightly-2026-10-30`，宽松的数字匹配会捞出
     * `2026` 当版本号 ⇒ `2026 > 2` ⇒ 误报"有新版本"。这里用上限把这类噪声挡掉。
     */
    private const val MAX_MAJOR = 999

    /**
     * tag 的严格形态：`数字[.数字[.数字]]`，**且必须在开头**。
     *
     * 必须"在开头"才能区分 `v2.0.28`（版本）与 `nightly-2026-10-30`（日期）。
     */
    private val VERSION_TAG_REGEX = Regex("""^(\d+)(?:\.(\d+))?(?:\.(\d+))?""")

    /** 宽松形态：文件名里**任意位置**的第一段 `数字[.数字[.数字]]`。 */
    private val VERSION_IN_TEXT_REGEX = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""")

    // ======================================================================
    // 版本号
    // ======================================================================

    /**
     * 语义化版本（major.minor.patch）。
     *
     * 只比这三段 ⇒ `2.0.28 > 2.0.27`，`2.1.0 > 2.0.99`。
     * 预发布后缀（`-beta.1` / `+build`）在解析阶段就被剥掉，不参与比较。
     */
    data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {

        /** 归一化后的版本串（`2.0.28`）——**节流键**就用它。 */
        val normalized: String get() = "$major.$minor.$patch"

        override fun compareTo(other: SemVer): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            return patch.compareTo(other.patch)
        }

        /** 远端是否**严格更新**（相等不算更新）。 */
        fun isNewerThan(other: SemVer): Boolean = compareTo(other) > 0

        override fun toString(): String = normalized
    }

    /**
     * 解析 **tag** 里的版本号（严格）。
     *
     * 接受：`v2.0.28`、`V2.0.28`、`2.0.28`、`release-2.0.28`、`v2.0`（⇒ 2.0.0）、
     * `v2.0.28-beta.1`（⇒ 2.0.28，预发布标记交给 API 的 `prerelease` 字段判）。
     *
     * 拒绝：`nightly-2026-10-30`（开头不是数字）、空串、`v`（没有数字部分）。
     *
     * @return 解析不出版本号时返回 `null`（**不是**抛异常）。
     */
    fun parseVersionTag(tag: String?): SemVer? {
        var body = tag?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (body.startsWith("release-", ignoreCase = true)) {
            body = body.substring("release-".length)
        } else if (body.startsWith("ver-", ignoreCase = true)) {
            body = body.substring("ver-".length)
        }
        // 前缀 `v` 只在后面紧跟数字时才剥（`version-…` 这种词不该被当成 `v` + `ersion`）。
        if (body.length > 1 && (body[0] == 'v' || body[0] == 'V') && body[1].isDigit()) {
            body = body.substring(1)
        }
        val m = VERSION_TAG_REGEX.find(body) ?: return null
        if (m.range.first != 0) return null
        return m.toSemVer()
    }

    /**
     * 从**任意文本**（典型是 asset 文件名）里捞版本号（宽松）。
     *
     * `opencode-2.0.28.apk` ⇒ `2.0.28`；`opencode-web-2.0.28.zip` ⇒ `2.0.28`。
     *
     * 只用于 tag 解析不出来时的**兜底**（见 [releaseVersion]），因为宽松匹配
     * 可能捞到日期之类的噪声，所以有 [MAX_MAJOR] 上限兜底。
     */
    fun parseVersionFromText(text: String?): SemVer? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val m = VERSION_IN_TEXT_REGEX.find(t) ?: return null
        return m.toSemVer()
    }

    private fun MatchResult.toSemVer(): SemVer? {
        val major = groupValues[1].toIntOrNull() ?: return null
        // 次段/修订段缺省时按 0（`v2` ⇒ 2.0.0，`v2.1` ⇒ 2.1.0）。
        val minor = groupValues[2].toIntOrNull() ?: 0
        val patch = groupValues[3].toIntOrNull() ?: 0
        if (major > MAX_MAJOR) return null
        return SemVer(major, minor, patch)
    }

    // ======================================================================
    // Release 结构
    // ======================================================================

    /** Release 里的一个附件。 */
    data class ReleaseAsset(val name: String, val browserDownloadUrl: String)

    /**
     * Release 元数据（只保留更新检查真正要用的字段）。
     *
     * @param draft 草稿 ⇒ 不算正式版
     * @param prerelease 预发布 ⇒ 不算正式版
     */
    data class ReleaseInfo(
        val tag: String?,
        val htmlUrl: String?,
        val draft: Boolean,
        val prerelease: Boolean,
        val assets: List<ReleaseAsset>,
    )

    /**
     * 解析 GitHub Release 的 JSON（[JsonLite]）。
     *
     * @return 解析失败（网络错误/HTML 错误页/结构变了）时返回 `null` ⇒ 上层静默。
     */
    fun parseRelease(json: String): ReleaseInfo? {
        val root = runCatching { JsonLite.parseObject(json) }.getOrNull() ?: return null
        val assets = (root["assets"] as? List<*>).orEmpty().mapNotNull { raw ->
            val obj = raw as? Map<*, *> ?: return@mapNotNull null
            val name = obj["name"] as? String ?: return@mapNotNull null
            val url = obj["browser_download_url"] as? String ?: return@mapNotNull null
            ReleaseAsset(name = name, browserDownloadUrl = url)
        }
        return ReleaseInfo(
            tag = root["tag_name"] as? String,
            htmlUrl = root["html_url"] as? String,
            draft = root["draft"] as? Boolean ?: false,
            prerelease = root["prerelease"] as? Boolean ?: false,
            assets = assets,
        )
    }

    /**
     * 这一版 Release 对应的**版本号**。
     *
     * 优先级：**tag** → **`.apk` asset 文件名** → 其它 asset 文件名。
     * （tag 是发版时写的权威版本号；asset 名只是兜底。）
     */
    fun releaseVersion(release: ReleaseInfo): SemVer? {
        parseVersionTag(release.tag)?.let { return it }
        val apk = selectApkAsset(release.assets)
        parseVersionFromText(apk?.name)?.let { return it }
        for (asset in release.assets) {
            parseVersionFromText(asset.name)?.let { return it }
        }
        return null
    }

    /** 挑出 `.apk` 附件（大小写不敏感；没有则 `null`）。 */
    fun selectApkAsset(assets: List<ReleaseAsset>): ReleaseAsset? =
        assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    /**
     * 用户点了"去更新"应当打开的 URL。
     *
     * **优先 `.apk` 的 `browser_download_url`**（直接下 APK），
     * 没有 `.apk` 就退回 Release 页。
     */
    fun selectDownloadUrl(release: ReleaseInfo): String? =
        selectApkAsset(release.assets)?.browserDownloadUrl
            ?: release.htmlUrl?.takeIf { it.isNotBlank() }
            ?: REPO_RELEASES_PAGE

    // ======================================================================
    // 决策（比较 + 节流）
    // ======================================================================

    /** 一条"有新版"的结论（供通知/对话框用）。 */
    data class Offer(
        val version: SemVer,
        val downloadUrl: String?,
        val htmlUrl: String?,
    ) {
        /** 节流键：归一化后的远端版本号（`2.0.28`）。 */
        val throttleKey: String get() = version.normalized
    }

    /**
     * 更新检查的**完整结论**。
     *
     * @param offer 有新版时非空；没有新版时为 `null`
     * @param notify 该不该发系统通知（同一远端版本只发一次）
     * @param dialog 该不该弹启动对话框（同一远端版本只弹一次）
     * @param reason 结论的原因（中文，进日志便于事后查）
     */
    data class Decision(
        val offer: Offer?,
        val notify: Boolean,
        val dialog: Boolean,
        val reason: String,
    ) {
        val hasUpdate: Boolean get() = offer != null
    }

    /**
     * **决策的唯一入口**（纯函数，单测直接打它）。
     *
     * @param release 远端 Release；`null` = 网络失败/解析失败
     * @param localVersion 本机版本（`BuildConfig.VERSION_NAME` 解析而来）
     * @param lastNotified 上一次**已发过通知**的远端版本（`null` = 没发过）
     * @param lastDialog 上一次**已弹过对话框**的远端版本（`null` = 没弹过）
     */
    fun decide(
        release: ReleaseInfo?,
        localVersion: SemVer?,
        lastNotified: String?,
        lastDialog: String?,
    ): Decision {
        if (release == null) return Decision(null, false, false, "网络失败或响应无法解析")
        if (release.draft) return Decision(null, false, false, "草稿稿不算正式版")
        if (release.prerelease) return Decision(null, false, false, "预发布版不算正式版")
        val remote = releaseVersion(release)
            ?: return Decision(null, false, false, "远端版本号解析不出来")
        if (localVersion == null) return Decision(null, false, false, "本机版本号解析不出来")
        if (!remote.isNewerThan(localVersion)) {
            return Decision(null, false, false, "本机已是最新（$localVersion）")
        }
        val key = remote.normalized
        val offer = Offer(
            version = remote,
            downloadUrl = selectDownloadUrl(release),
            htmlUrl = release.htmlUrl,
        )
        return Decision(
            offer = offer,
            // 节流：**同一个远端版本只提醒一次**（换了版本才再提醒）。
            notify = lastNotified != key,
            dialog = lastDialog != key,
            reason = "发现新版本 $key（本机 $localVersion）",
        )
    }
}
