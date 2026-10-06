# UX13-P3 · 修复相机扫码被拒（方案 A 回归）+ 重建三产物

- 仓库：`D:\XiaoZhuTouTou\opencode-android-shell`
- 基线 commit：`bc87e72 feat(web): bundle official frontend with local http origin (Plan A, UX12)`
- 轮次：UX13-P3　执行时间：2026-10-06
- 结论：**成功**。宿主 bug 已修、反向验证真红、三产物重建并通过包内自查。

## 0. 根因（承接 UX13/P1 实测，本轮未重新调查）

`research/web/UX13_P1_CAPABILITIES.md` 步骤 2 实测：官方 Add server 对话框 → Scan QR code →
PairingScanner **正常渲染**，但宿主 logcat 连打 6 次：

```
OcWebHost: camera permission DENIED origin=http://127.0.0.1:4173 resources=[VIDEO_CAPTURE]
```

方案 A（`bc87e72`）之后页面由 App 自带的 `LocalUiOriginServer` 以 `http://127.0.0.1:4173` 提供，
但 `WebHostActivity.kt` 的 `isTrustedWebOrigin()` 可信名单里只有 `SecretStore.KEY_BACKEND_URL`
与默认后端 `http://127.0.0.1:4095` ⇒ 页面来源不在名单 ⇒ `onPermissionRequest` 走 deny 分支。

**定性：宿主 bug。** 浏览器侧 `window.isSecureContext` 按 potentially-trustworthy 规则认定
`127.0.0.1` 为安全上下文（上游 `camera.ts:6` 的 `window.isSecureContext` 检查因此通过，
WebView 才把 getUserMedia 转成 `onPermissionRequest`），宿主却用一份更窄的名单把它拒了。
两边判定不一致，宿主是错的一方。

## 1. 授权改动

### 1.1 `webapp/src/main/java/.../Web/WebHostActivity.kt`

`isTrustedWebOrigin` 的 `candidates` buildList 内，在 `add(SecretStore.DEFAULT_BACKEND_URL)`
之后追加**一行**代码：

```kotlin
add(LocalUiOriginServer.origin())
```

并同步更新该函数 KDoc，写明第三个可信来源是自带官方前端的页面来源及其理由。

边界遵守情况：

- 未泛化成域名/正则（`sameOrigin()` 精确比 scheme/host/port 未动）；
- 未碰凭据逻辑（`SecretStore.getSecret` 调用原样保留）；
- 未碰代理逻辑（方案 A 已移除 `proxy?.publicOrigin()`，本轮未新增任何代理代码）；
- import 已存在（`bootUrl()` 第 331 行在用），**无新增 import**；
- 全文件 diff 只有 **1 个 hunk**（`git diff -U0` 原文）：

```
@@ -1017 +1017,4 @@ class WebHostActivity : AppCompatActivity() {
@@ -1027,0 +1031 @@ class WebHostActivity : AppCompatActivity() {
+            add(LocalUiOriginServer.origin())
```

`git diff --stat`：`WebHostActivity.kt | 6 +++++-`（1 file changed, 5 insertions(+), 1 deletion(-)）
= KDoc 4 行 + 代码 1 行。

改动后函数全文（UTF-8 正确解码，无 BOM、无乱码，LF 行尾与原文件一致）：

```kotlin
    /**
     * F3: 该 origin 是否是本宿主**自己**加载的页面。
     *
     * 可信来源：配置的后端 origin（SecretStore.KEY_BACKEND_URL，默认
     * `http://127.0.0.1:4095`）—— 页面直连后端，origin 就是它；
     * 以及**自带官方前端的页面来源** `LocalUiOriginServer.origin()`
     * （UX13/P1 实测：方案 A 之后页面 origin 是 `http://127.0.0.1:4173`，
     * 不在名单会让扫码被宿主误拒；该端点只服务 App 自带 assets，与本应用同一信任级）。
     *
     * 刻意**不做**的：不按域名/正则泛化、不允许任意站点。
     * 一个 origin 拿到摄像头 = 用户不知道自己正被谁看着。
     */
    private fun isTrustedWebOrigin(origin: String): Boolean {
        val candidates = buildList {
            runCatching {
                SecretStore.getSecret(this@WebHostActivity, SecretStore.KEY_BACKEND_URL)
            }.getOrNull()?.let { add(it) }
            add(SecretStore.DEFAULT_BACKEND_URL)
            add(LocalUiOriginServer.origin())
        }
        return candidates.any { trusted ->
            trusted.isNotBlank() && sameOrigin(trusted, origin)
        }
    }
```

### 1.2 绊线 `webapp/src/test/java/.../CameraPermissionHostTest.kt`

新增私有切源助手 `isTrustedWebOriginSource()`：从 `private fun isTrustedWebOrigin` 起
**按花括号配对**切到该函数结束（`depth` 计数，遇 `depth==0` 的 `}` 收尾）。

> ⚠️ 这是本轮绊线的关键设计：**不能对整个 activity 源做 contains**。
> `bootUrl()`（第 331 行）早在方案 A 就在用同一字符串 `LocalUiOriginServer.origin()`，
> 那样**旧代码也会假绿**。实测证实（见 §2.2）：旧 blob 里该字符串全文件出现 1 次、
> 函数体内 0 次；新代码 3 次（bootUrl + KDoc 提及 + 代码行）、函数体内 1 次。

`grantIsRestrictedToTrustedOrigins` 追加一条断言（复用同一助手），并新增独立用例：

```kotlin
@Test
fun bundledUiOriginIsTrustedForCamera() {
    assertTrue(
        "isTrustedWebOrigin 的可信来源必须包含 LocalUiOriginServer.origin()：" +
            "扫码配对的页面来源就是它，缺失会让 onPermissionRequest 误拒 RESOURCE_VIDEO_CAPTURE",
        isTrustedWebOriginSource().contains("LocalUiOriginServer.origin()"),
    )
}
```

`CameraPermissionHostTest.kt` 合计 `+47` 行。

## 2. 验证（三段：绿 → 真红 → 绿）

环境：`JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`（JDK 21，JBR 的 Java 25 会被拒）、
`ANDROID_HOME=$env:LOCALAPPDATA\Android\Sdk`。每段均走锁协议：确认无锁 → 建锁 →
前台/脱离会话跑 → **跑完单条顶层 `rm -- .agent-gradle-lock`**。长编译一律用
`Start-Process` 脱离会话启动并轮询 `test-results` XML，避免会话回收子进程。

### 2.1 run1 — 修复后全绿

```
> Task :webapp:testDebugUnitTest
BUILD SUCCESSFUL in 33s
24 actionable tasks: 5 executed, 19 up-to-date
```

`TEST-com.xiufeigo.opencodeshell.web.CameraPermissionHostTest.xml`：

```
<testsuite name="com.xiufeigo.opencodeshell.web.CameraPermissionHostTest"
           tests="10" skipped="0" failures="0" errors="0" time="0.076">
```

10 条用例全过，含 `bundledUiOriginIsTrustedForCamera`。

### 2.2 run2 — 回退到 `bc87e72` 旧 blob，必须真红

先备份修复版到 `$env:TEMP`（`Copy-Item`，md5 `B82E6A4EB0037D2511CCEDB186DF27DA`），
再按授权命令回退：

```
cmd /c "git show bc87e72:webapp/src/main/java/.../WebHostActivity.kt > ...\WebHostActivity.kt"
```

回退后立刻体检（证明绊线不会被 bootUrl 骗过）：

```
REVERTED hasOrigin_in_fn=False
REVERTED total_origin_occurrences=1  (bootUrl only = 1)
```

跑测试 —— **预期中的真红，实际确实红了**：

```
> Task :webapp:testDebugUnitTest
CameraPermissionHostTest > grantIsRestrictedToTrustedOrigins FAILED
    java.lang.AssertionError at CameraPermissionHostTest.kt:163
CameraPermissionHostTest > bundledUiOriginIsTrustedForCamera FAILED
    java.lang.AssertionError at CameraPermissionHostTest.kt:179
> Task :webapp:testDebugUnitTest FAILED
```

XML 佐证：

```
<testsuite name="..." tests="10" skipped="0" failures="2" errors="0">
TEST=grantIsRestrictedToTrustedOrigins
  MSG=AssertionError: 可信来源必须包含自带官方前端的页面来源 LocalUiOriginServer.origin()——
      方案 A 之后页面 origin 是 http://127.0.0.1:4173，不在名单会让扫码被宿主误拒
TEST=bundledUiOriginIsTrustedForCamera
  MSG=AssertionError: isTrustedWebOrigin 的可信来源必须包含 LocalUiOriginServer.origin()：
      扫码配对的页面来源就是它，缺失会让 onPermissionRequest 误拒 RESOURCE_VIDEO_CAPTURE
```

（上面中文是按 UTF-8 正确解码后的可读文本；XML 原始字节为 UTF-8，PowerShell 直读会显示乱码。）

证据文件已另存 `$env:TEMP\ux13p3_run2_CameraPermissionHostTest.xml`，未被 run3 覆盖。

### 2.3 run3 — 恢复备份后全绿

```
RESTORED md5=B82E6A4EB0037D2511CCEDB186DF27DA   （与备份一致）
RESTORED hasOrigin_in_fn=True
```

```
> Task :webapp:testDebugUnitTest
BUILD SUCCESSFUL in 6s
24 actionable tasks: 5 executed, 19 up-to-date

<testsuite name="..." tests="10" skipped="0" failures="0" errors="0">
failures_count=0  errors_count=0
```

> 注：回退过程中有一次「ABORT: lock held」——是我自己在 run2 跑完后漏了释放锁，
> 锁守卫正确拦住了第三次启动，没有出现两个 gradle 并跑。随后单条 `rm` 释放后重跑。
> 这正是锁协议该有的行为。

## 3. 重建三产物

```
.\gradlew.bat --console=plain :webapp:assembleDebug :webapp:assembleRelease :webapp:assembleReleaseR8
...
> Task :webapp:assembleReleaseR8
BUILD SUCCESSFUL in 58s
128 actionable tasks: 22 executed, 106 up-to-date
```

复制到 `research/deliver/`（覆盖三份，规范名不变）。

### 3.1 体积 / md5（新旧对比）

| 产物 | 旧（UX12 批） | 新（UX13-P3） | 差 |
|---|---:|---:|---:|
| `opencode-shell-web.apk`（debug） | 16,796,038 · `BF688F8A…` | **16,821,985 · `9E3C5C61ABED3F210859BC5807010101`** | +25,947 |
| `opencode-shell-web-release-nor8.apk` | 14,304,138 · `B3F9D303…` | **14,304,358 · `67DCB22841F6D486BD85E2EB2AE14F9A`** | +220 |
| `opencode-shell-web-release-r8.apk` | 12,024,280 · `F3D3911A…` | **12,024,268 · `840D34F3EF3423EFFE0891FA8FAA35EA`** | −12 |

体积变化与改动量相符：debug 涨 25,947（多了一行常量字符串 + KDoc 不进 dex，主要来自
`LocalUiOriginServer.origin()` 的常量与调用点）；nor8 几乎不变；r8 反而小了 12 字节——
R8 内联常量后重新排布的正常抖动，不必深究。三份与旧批同量级，无异常膨胀。

### 3.2 包内自查（三项，三份全过）

```
opencode-shell-web.apk
  ui=1613 (exp 1613 OK)  oa=1590 (exp 1590 OK)  oc-host.js md5=8A3B3E93EBF61BEC347067D4E4074A69 (match=True)
opencode-shell-web-release-nor8.apk
  ui=1613 (exp 1613 OK)  oa=1590 (exp 1590 OK)  oc-host.js md5=8A3B3E93EBF61BEC347067D4E4074A69 (match=True)
opencode-shell-web-release-r8.apk
  ui=1613 (exp 1613 OK)  oa=1590 (exp 1590 OK)  oc-host.js md5=8A3B3E93EBF61BEC347067D4E4074A69 (match=True)
```

源文件对照：

```
SOURCE=8A3B3E93EBF61BEC347067D4E4074A69  (expected 8A3B3E93EBF61BEC347067D4E4074A69)
```

三份包内 md5 与磁盘源文件一致 ⇒ **注入件（`oc-host.js`）一个字节都没动**，
官方 `ui/` 资源数量也分毫不差。

### 3.3 凭据扫描（同 UX12 模式）

```
--- LocalUiOriginServer.kt : 0 hits ---
--- WebHostActivity.kt : 1 hits ---
  L64: * 支持的字段：Basic `opencode:<password>`，见 `runtime/server/api.ts:5-7`
TOTAL_HITS=1
```

唯一命中是**既有 KDoc 注释**，不是本轮引入，且不是真实凭据（只是描述上游 Basic 认证的字段格式）。
已用 HEAD blob 直接对证：

```
HEAD   blob contains 'opencode:<password>' : True
CURRENT contains 'opencode:<password>'   : True
```

本轮**新增命中数 = 0**。

## 4. 红线遵守

- `app/**`：**0 改动**（`git status --porcelain -- app/` 为空）
- `webapp/src/main/assets/www/**`（5 个注入件 + `ui/` 官方资源）：**0 改动**（porcelain 为空）
- **未 git commit**，`HEAD` 仍是 `bc87e72`
- 未起停 4095 后端；本轮**未起模拟器**（无需）
- gradle 锁：每段跑完均以单条顶层 `rm -- .agent-gradle-lock` 释放，
  收尾时 `.agent-gradle-lock` **不存在**（`lock_exists=False`）
- 未改 `www/` 注入件、`ui/` 官方资源任何字节
- 唯一被改动的两个文件：`WebHostActivity.kt`（1 行代码 + KDoc）、`CameraPermissionHostTest.kt`（+47 行绊线）

最终 `git status --porcelain`：

```
 M webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt
 M webapp/src/test/java/com/xiufeigo/opencodeshell/web/CameraPermissionHostTest.kt
（其余 ?? 均为 research/ 下既有未跟踪的 prompt 与 P1/P2 报告，本轮新增 UX13_P3_CAMERA_FIX.md）
```

## 5. 遗留与建议

1. **未上真机复验。** 本轮只做到「宿主 bug 在代码层已修 + 绊线真红」，
   没有在模拟器/真机上重跑 P1 步骤 2 去确认 logcat 不再打 DENIED。建议下轮
   起模拟器复跑一次 P1 步骤 2，验收标准是 `camera permission GRANTED origin=http://127.0.0.1:4173`
   出现且 DENIED 计数为 0。
2. `isTrustedWebOrigin` 现在的三个来源里，4173 端口是**固定**的
   （`LocalUiOriginServer.origin()` 常量），而真实绑定端口在 `localUiServer.boundPort`
   （第 265 行 `PerfMarks.mark("B2.localUiOrigin", "bound=…")`）。本轮按授权照做，
   但若将来 4173 被占用而回退到别的端口，可信名单会与实际页面来源脱节。
   根治办法是把 `localUiServer` 的实际地址也纳入 candidates，或改为在
   `onPermissionRequest` 里比对 `bootUrl()` 的实时来源。当前不属本轮授权范围，仅记录。