package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F3 / N-2 · **相机权限与扫码配对**接线绊线。
 *
 * ## 上游依据（`origin/v2`，行号原文见 `research/web/FIX_F3.md`）
 *
 * 上游**真取流**，用于服务器配对扫码：
 *  - `packages/app/src/servers/connect/camera.ts:6`
 *    `const supported = platform.platform === "web" && window.isSecureContext && !!navigator.mediaDevices?.getUserMedia`
 *  - `packages/app/src/servers/connect/scanner.tsx:12-19` 建 `<video>` 并交给 `QrScanner`；
 *    `:38` `void scanner.start()`（QrScanner 内部走 getUserMedia）。
 *
 * WebView 把 `getUserMedia` 变成 `WebChromeClient.onPermissionRequest`
 * （资源 `RESOURCE_VIDEO_CAPTURE`）。所以要跑通扫码，宿主必须
 * **① 声明 CAMERA**、**② 真正授予**。缺任一条 ⇒ 拿不到流。
 *
 * ## 本文件钉的三件事
 *  1. manifest 有 `CAMERA` 且 `uses-feature required="false"`（不把无摄像头设备挡在安装之外）；
 *  2. `onPermissionRequest` **只**对 `RESOURCE_VIDEO_CAPTURE` 放行（白名单，不整包放行）；
 *  3. **只对可信来源授予**（回环代理 origin / 配置的后端 origin），任意站点一律 deny。
 *
 * ## 反向验证
 * 撤掉 CAMERA 声明、把 `onPermissionRequest` 换成不 override、
 * 或把可信来源判断删掉改成整包 `grant(request.resources, …)`，
 * 对应用例必然**变红**（已实跑，见 FIX_F3.md）。
 */
class CameraPermissionHostTest {

    private fun src(rel: String): String {
        val relPath = File(rel)
        var dir: File? = File(".").absoluteFile
        repeat(4) {
            val f = File(dir, rel)
            if (f.isFile) return f.readText(Charsets.UTF_8)
            dir = dir?.parentFile
        }
        throw AssertionError("找不到源文件: $rel（从 ${File(".").absolutePath} 向上找了 4 级）")
    }

    private val manifest: String
        get() = src("src/main/AndroidManifest.xml")

    private val activity: String
        get() = src("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")

    /**
     * 只切出 `isTrustedWebOrigin` **函数体**（从声明处到配对的收尾 `}`）。
     *
     * ⚠️ 不能对整个 activity 源做 contains：`bootUrl()` 也在用
     * `LocalUiOriginServer.origin()`，那样旧代码（未修）会假绿。
     */
    private fun isTrustedWebOriginSource(): String {
        val source = activity
        val start = source.indexOf("private fun isTrustedWebOrigin")
        assertTrue("必须存在 isTrustedWebOrigin(origin)", start >= 0)
        var depth = 0
        var i = source.indexOf('{', start)
        assertTrue("isTrustedWebOrigin 必须有函数体", i >= 0)
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        throw AssertionError("isTrustedWebOrigin 的花括号不配对")
    }

    // ---- ① manifest 声明 -------------------------------------------------

    @Test
    fun manifestDeclaresCameraPermission() {
        assertTrue(
            "webapp manifest 必须声明 CAMERA：上游 scanner.tsx:19-38 真走 getUserMedia，" +
                "WebView 没有 CAMERA 声明就永远不会回调 onPermissionRequest",
            manifest.contains("android.permission.CAMERA"),
        )
    }

    @Test
    fun manifestCameraFeatureIsNotRequired() {
        assertTrue(
            "必须声明 <uses-feature android:name=\"android.hardware.camera\" " +
                "android:required=\"false\" /> —— required=true 会把无摄像头设备挡在安装之外",
            Regex(
                "<uses-feature\\s+android:name=\"android\\.hardware\\.camera\"\\s+" +
                    "android:required=\"false\"\\s*/>",
            ).containsMatchIn(manifest),
        )
        assertFalse(
            "camera must not be a hard install requirement",
            Regex("android\\.hardware\\.camera\"\\s+android:required=\"true\"").containsMatchIn(manifest),
        )
    }

    // ---- ② 只对 VIDEO_CAPTURE 放行（白名单） -----------------------------

    @Test
    fun onPermissionRequestIsOverridden() {
        assertTrue(
            "HostWebChromeClient 必须 override onPermissionRequest，否则 WebView 默认拒绝、" +
                "扫码配对在本宿主不可用",
            activity.contains("override fun onPermissionRequest("),
        )
    }

    @Test
    fun onlyVideoCaptureIsGranted() {
        assertTrue(
            "必须按资源白名单过滤出 RESOURCE_VIDEO_CAPTURE",
            activity.contains("PermissionRequest.RESOURCE_VIDEO_CAPTURE"),
        )
        assertTrue(
            "授予时只能回传白名单里的那一个资源",
            activity.contains("arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)"),
        )
        assertFalse(
            "禁止整包 grant(request.resources)：那会把页面以后新增的资源类型（如 " +
                "RESOURCE_AUDIO_CAPTURE）一并放行，等于对整站开放硬件",
            Regex("request\\.grant\\(\\s*request\\.resources").containsMatchIn(activity),
        )
    }

    @Test
    fun deniedWhenRuntimePermissionNotHeld() {
        // 未持有运行期权限时必须走「先申请、结果回来再答复」，
        // 直接 grant 等于骗页面拿到了一个随即死掉的流。
        assertTrue(
            "必须先走运行期权限申请（cameraPermission.launch(Manifest.permission.CAMERA)）",
            activity.contains("cameraPermission.launch(Manifest.permission.CAMERA)"),
        )
        assertTrue(
            "必须在注册回调里把拒绝如实回报给页面（deny ⇒ 上游 camera.ts:10-14 走既有降级文案）",
            activity.contains("request.deny()"),
        )
    }

    // ---- ③ 只对可信来源授予 ---------------------------------------------

    @Test
    fun grantIsRestrictedToTrustedOrigins() {
        assertTrue(
            "必须校验 request.origin，只对本宿主加载的可信 origin 授予",
            activity.contains("isTrustedWebOrigin(origin)"),
        )
        assertTrue(
            "可信来源必须包含配置的后端 origin（页面直连后端，origin 就是它）",
            activity.contains("SecretStore.KEY_BACKEND_URL"),
        )
        assertTrue(
            "可信来源必须包含默认后端 origin（未配置时的启动地址）",
            activity.contains("SecretStore.DEFAULT_BACKEND_URL"),
        )
        assertTrue(
            "可信来源必须包含自带官方前端的页面来源 LocalUiOriginServer.origin()——" +
                "方案 A 之后页面 origin 是 http://127.0.0.1:4173，不在名单会让扫码被宿主误拒",
            isTrustedWebOriginSource().contains("LocalUiOriginServer.origin()"),
        )
    }

    /**
     * UX13/P3 回归：相机权限必须认自带官方前端的页面来源。
     *
     * P1 实测（`research/web/UX13_P1_CAPABILITIES.md` 步骤 2）：PairingScanner 正常渲染，
     * 但宿主 logcat 打出 `camera permission DENIED origin=http://127.0.0.1:4173` ×6 ——
     * 宿主实现与浏览器 `isSecureContext` 判定不一致导致的宿主 bug。
     */
    @Test
    fun bundledUiOriginIsTrustedForCamera() {
        assertTrue(
            "isTrustedWebOrigin 的可信来源必须包含 LocalUiOriginServer.origin()：" +
                "扫码配对的页面来源就是它，缺失会让 onPermissionRequest 误拒 RESOURCE_VIDEO_CAPTURE",
            isTrustedWebOriginSource().contains("LocalUiOriginServer.origin()"),
        )
    }

    @Test
    fun untrustedOriginIsDenied() {
        assertTrue(
            "不可信来源必须走 deny 分支，且必须在 grant 之前",
            Regex(
                "if \\(!trusted \\|\\| wanted\\.isEmpty\\(\\)\\) \\{[\\s\\S]{0,400}?" +
                    "request\\.deny\\(\\)[\\s\\S]{0,200}?return",
            ).containsMatchIn(activity),
        )
        // deny 分支必须在 grant 分支**之前**，否则不可信 origin 也会被授予
        val denyAt = activity.indexOf("camera permission DENIED origin=")
        val grantAt = activity.indexOf("camera permission GRANTED (already held)")
        assertTrue(
            "必须存在 deny 分支", denyAt >= 0
        )
        assertTrue(
            "必须存在 grant 分支", grantAt >= 0
        )
        assertTrue(
            "deny 分支必须排在 grant 分支之前（不可信来源不得被授予）",
            denyAt < grantAt,
        )
    }

    @Test
    fun trustedOriginComparisonIsExactNotSubstring() {
        // 不可信来源判定必须精确到 scheme://host:port；
        // 任何"包含即信任"的做法都会被 https://evil.test/?x=127.0.0.1 这类 origin 绕过。
        assertTrue(
            "origin 比较必须解析后逐段比 scheme/host/port，而不是字符串包含",
            activity.contains("private fun sameOrigin(a: String, b: String)"),
        )
        assertFalse(
            "禁止 startsWith/contains 式的 origin 信任判定（可被子域/查询串绕过）",
            Regex("origin\\.startsWith\\(|origin\\.contains\\(").containsMatchIn(activity),
        )
    }

    // ---- ④ 播放手势：上游 <video muted playsinline> 依赖自动播放 -----------

    @Test
    fun mediaPlaybackDoesNotRequireUserGesture() {
        // 上游 scanner.tsx:12-15 建的是 muted + playsinline 的 <video>，
        // 没有用户手势；WebView 默认要求手势 ⇒ 起流后画面出不来。
        assertTrue(
            "必须 setMediaPlaybackRequiresUserGesture(false)，否则扫码预览无画面",
            activity.contains("mediaPlaybackRequiresUserGesture = false"),
        )
    }
}
