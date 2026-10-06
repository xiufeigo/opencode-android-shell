# UX14-P1 —— 接线 `onRenderProcessGone`（渲染进程崩溃自愈）

> **日期**：2026-10-06 · **轮次**：UX14-P1
> **闭环项**：HANDOFF §5 **第 11 条**「`onRenderProcessGone` 未接」
> **红线**：未碰 `ui/` / `app/**` / 五个注入件；未 commit；4095 后端（pid **12748**）只读未起停；本轮**不起模拟器**

---

## 1. 结论

**HANDOFF §5 第 11 条已闭环。** `WebViewPreflight.Reason.RENDER_CRASH` 从"有枚举、没人用"变成**真正接线的分支**。

同时纠正了一条**写进历史代码里的错误结论**：旧注释断言 `onRenderProcessGone` "本模块编译期解析不到"，并点名了一个**不存在的类名**。见 §2。

---

## 2. 先证实缺口，并推翻旧结论

### 2.1 缺口确实存在（grep 证实）

全模块 `onRenderProcessGone` **零实现**。宿主侧只有一处空壳：

```kotlin
// WebHostActivity.kt（改动前）
private fun onRenderProcessGoneNotWired() = Unit
```

同时 `WebViewPreflight.kt:83` 的 `RENDER_CRASH("rendercrash")` 枚举**确实在**，只是没人调用 —— 与 HANDOFF 的记述一致。

### 2.2 旧结论是错的：类名写错了，不是环境问题 🔴

历史空壳注释（原文，`research/env/d1_revert_backup/WebHostActivity.kt.bak:971-984` 仍在）称：

> 想接的是 `onRenderProcessGone`（API 26），但本模块编译期解析不到
> `android.webkit.WebViewRenderProcessDetail`（真跑报错：
> `Unresolved reference 'WebViewRenderProcessDetail'` / `'onRenderProcessGone' overrides nothing`）。
> 根因**未核对**（本机 SDK 的 android.jar 路径未能定位，来不及深挖）。

**`WebViewRenderProcessDetail` 这个类在 Android SDK 里根本不存在** —— 平台真名是
**`android.webkit.RenderProcessGoneDetail`**。所以"解析不到"只是因为写错了类名，
**从来不是 SDK 缺件或 compileSdk 不足**。

本轮实测（非记忆，PowerShell 直接打开 jar 的 zip 目录）：

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z=[System.IO.Compression.ZipFile]::OpenRead("$env:LOCALAPPDATA\Android\Sdk\platforms\android-35\android.jar")
$z.Entries | ? { $_.FullName -match 'RenderProcessGoneDetail' }
# → android/webkit/RenderProcessGoneDetail.class
```

配合 `compileSdk = 35` / `minSdk = 26`（`webapp/build.gradle.kts`），API 26 门槛**双向满足**。

> 这解释了为什么这个缺口能"诚实记为未闭环"地活过好几轮：**它被错误地归因为环境限制，
> 于是没人再去核对平台真名。** 归因错误比缺口本身更贵 —— 它让一个 30 行的改动看起来像不可能。

---

## 3. 改法（宿主侧，红线干净）

只动两个文件：

| 文件 | 性质 |
|---|---|
| `webapp/.../web/WebHostActivity.kt` | 新增 1 个 import、2 个计数字段、1 个常量、1 个 override（**并删除**旧空壳） |
| `webapp/src/test/.../RenderProcessRecoveryTest.kt` | **新增**，4 用例 |

`git status --porcelain -- webapp/ app/ ui/` 实测输出（原文）：

```
 M webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt
?? webapp/src/test/java/com/xiufeigo/opencodeshell/web/RenderProcessRecoveryTest.kt
```

**没有第三个文件。** 凭据/代理/注入件一行未动。

### 3.1 节流状态（两个字段）

```kotlin
private var lastRenderCrashAtMs: Long = 0L
private var renderCrashCount: Int = 0
private const val RENDER_CRASH_RELOAD_WINDOW_MS = 60_000L   // companion
```

**为什么必须节流（写进代码注释）**：崩溃成因若是**页面自身**（超大 DOM、内存吃紧、
某个 `postMessage` 触发的 OOM），"崩了就 reload"会变成
*reload → 起新 Chromium 渲染进程 → 同样输入 → 再崩* 的**无限重启风暴**，
每次都真起一个进程，很快把内存/电量吃干，用户看到的是疯狂闪烁而不是恢复。

所以：**窗口内只放行第 1 次 reload，第 2 次就认输、切诊断页。** 出了 60 s 窗口计数清零，
把新崩溃当独立事件再给一次自愈机会。

### 3.2 override 行为

```kotlin
override fun onRenderProcessGone(
    view: WebView,
    detail: RenderProcessGoneDetail,
): Boolean {
    val now = SystemClock.elapsedRealtime()
    Log.w(TAG, "UX14 render process gone didCrash=${detail.didCrash()} " +
        "priorityAtExit=${detail.rendererPriorityAtExit()}")          // ① 记日志（含两个真值）
    PerfMarks.mark("N.onRenderProcessGone", "didCrash=... count=$renderCrashCount")

    val withinWindow = lastRenderCrashAtMs > 0L &&
        (now - lastRenderCrashAtMs) < RENDER_CRASH_RELOAD_WINDOW_MS
    val crashesInWindow = if (withinWindow) renderCrashCount + 1 else 1
    lastRenderCrashAtMs = now
    renderCrashCount = crashesInWindow

    if (!::webView.isInitialized) return true      // 崩溃发生在 onCreate 完成前：只消费

    if (crashesInWindow >= 2) {                    // ② 崩溃循环 → 认输
        showDiagnostic(WebViewPreflight.Reason.RENDER_CRASH, "didCrash=... crashes=...")
    } else {                                       // ② 第 1 次 → 自愈
        armWatchdog()
        webView.reload()
    }
    return true                                    // ③ 消费，阻止 finish
}
```

四点说明：

- **① 日志**含 `detail.didCrash()`（区分"崩了"与"被系统杀掉"）与
  `rendererPriorityAtExit()`，两者都是**平台真值**，不是猜测。
- **② reload 路径**先 `armWatchdog()` 重挂 12 s 看门狗（`showDiagnostic` 之外的路径
  原本不受看门狗保护）。页面从本地 4173 源重新加载，会话/服务器状态都在
  `localStorage` ⇒ 恢复后与桌面端一致。
- **③ `return true`** = **消费**事件，宿主已接管，WebView 不再按默认行为 finish Activity。
  **刻意不调 super**（super 的实现就是 `return false`，即"不处理"⇒ 走默认销毁）。
- 诊断调用**对齐既有签名** `showDiagnostic(reason: WebViewPreflight.Reason, detail: String?)`
  （`WebHostActivity.kt:238`），未新增重载、未改既有方法。

### 3.3 ⚠️ 已知边界（诚实记录，未在本轮设备验证）

平台文档说渲染进程消失后该 WebView 实例**不应**再被使用，惯例是把旧实例移出视图树
并新建。本轮按产品口径先做**低成本 reload 自愈**（未新建 WebView、未改视图树）。

理由与兜底：绝大多数"崩溃后 reload 又能起来"的情形 WebView 会自行拉起新渲染进程；
真起不来时**第 2 次崩溃会被节流挡下**并落到诊断页，**不会**无限重启 ——
节流正是这里的安全带。

⚠️ **本轮不起模拟器 ⇒ 该路径未经设备验证。** 这一条必须留在台账上，
不能因为"编译过 + 绊线绿"就当成"运行时已证"。

---

## 4. 绊线：`RenderProcessRecoveryTest.kt`（4 用例）

### 4.1 为什么是源码断言

`onRenderProcessGone` 需要一个真的、并且**会崩掉**的渲染进程才能触发，JVM 单测造不出来
（本轮也不起模拟器）。沿用 `Ux13P13BackgroundGateOrderTripwireTest` 的既有模式：断言源码结构。

> ⚠️ **4 条断言全部作用在「剥掉注释后」的代码上。** 本 override 的设计说明里就写着
> `return true` / `reload()` / `super` 这些字面量 —— 不剥注释会把散文当语句读，
> **必然假绿**。`stripComments()` 同时跳过字符串字面量。

### 4.2 四条断言各自的语义

| 用例 | 钉住什么 |
|---|---|
| `onRenderProcessGoneIsOverriddenAndConsumed` | override 存在 + `return true` + 参数类型是对的那个类名（防再次写成 `WebViewRenderProcessDetail`） |
| `firstCrashReloadsWithThrottle` | 有 `reload()` + 常量**确实是 60_000L** + 方法体**真的读了**该常量（防"常量摆设"） |
| `crashLoopFallsBackToDiagnostic` | 有 `showDiagnostic(` + 引用 `WebViewPreflight.Reason.RENDER_CRASH` + 存在 `crashesInWindow >= 2` 判定 |
| `defaultFinishIsPrevented` | 方法体**不含** `super.onRenderProcessGone` —— 消费语义的结构证据 |

### 4.3 反向验证：外科撤除 ⇒ 4 条全红 ⇒ 恢复全绿 ✅

撤除手法（**只改一个变量**：撤掉 `override` 语义，函数体其余部分原样保留，
故仍能编译、失败必然来自绊线本身而非编译错误）：

```diff
- override fun onRenderProcessGone(
+ private fun onRenderProcessGoneAblated(
```

**红条（gradle 原文）**：

```
230 tests completed, 4 failed
BUILD FAILED in 36s
=== REVERSE_TEST_EXIT=1 2026-10-06T21:16:36.8075452+08:00 ===
```

4 条全红，且**红在同一句**（XML 原文，UTF-8，控制台仅是代码页乱码）：

```
[RED] onRenderProcessGoneIsOverriddenAndConsumed
[RED] firstCrashReloadsWithThrottle
[RED] crashLoopFallsBackToDiagnostic
[RED] defaultFinishIsPrevented
  java.lang.AssertionError: 🔴 UX14-P1 回归：WebHostActivity 里没有 onRenderProcessGone 的 override。
  渲染进程崩溃会走 WebView 默认行为 finish 掉整个 Activity，用户只能杀进程重启
  （正在跑的回合全丢），与桌面端 Electron 的自动恢复不一致。
```

**恢复**：从 `research/tmp/WebHostActivity.ux14p1.GREEN.bak` 逐字节还原，
md5 比对 `45A3C0D03600185C6910B941C8A37379` **一致** ⇒ 还原无残留。

```
=== GREEN_TEST_EXIT=0 2026-10-06T21:17:29.9975180+08:00 ===
BUILD SUCCESSFUL in 35s
classes=29 tests=230 failures=0 errors=0
```

---

## 5. 测试与重建

锁协议：`JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`；
内联 `ANDROID_HOME/ANDROID_SDK_ROOT=C:\Users\xiufeigo\AppData\Local\Android\Sdk`；
长编译经 `Start-Process` **脱离会话** + 轮询日志/test-results。

### 5.1 用例数

| 阶段 | classes | tests | failures | errors |
|---|---:|---:|---:|---:|
| 本轮（基线 226 + 新增 4） | 29 | **230** | **0** | 0 |
| 反向验证（撤除 override） | 29 | 230 | **4** | 0 |
| 恢复后 | 29 | **230** | **0** | 0 |

新增 4 条逐条 PASS（`testDebugUnitTest` XML 实录）：

```
suite=...RenderProcessRecoveryTest tests=4 failures=0 errors=0
  PASS crashLoopFallsBackToDiagnostic (0.002s)
  PASS onRenderProcessGoneIsOverriddenAndConsumed (0.002s)
  PASS firstCrashReloadsWithThrottle (0.002s)
  PASS defaultFinishIsPrevented (0.001s)
```

三套构建均 `--rerun-tasks`（防 UX13-P4 踩过的 UP-TO-DATE 静默放过）：

```
=== TEST_EXIT=0     2026-10-06T21:13:33 ===
=== ASSEMBLE_EXIT=0 2026-10-06T21:15:04 ===
```

### 5.2 产物（批 #6，`research/deliver/`，`Get-FileHash` 本轮亲手实测）

| 产物 | 体积 | **md5** | SHA256 |
|---|---:|---|---|
| `opencode-shell-web.apk`（debug） | **16,800,362 B** | **`A71B0F45D796B257B96780EE7AA1C42D`** | `CDD3EE9BBB61ED84FE00B7BEDC7D489D14838ACB30C9D36C67CEB76F76EA7524` |
| `opencode-shell-web-release-nor8.apk` | **14,307,626 B** | **`BC3E9027819CF4B7CBB8B37CC0EDAF48`** | `90F587DB04BE2530077C6D2610AF05A7F9E229554925163ACFEFAF5C0ED0F014` |
| `opencode-shell-web-release-r8.apk` | **12,027,472 B** | **`B6A346F6E9BE56F2280AECF9B73CE26B`** | `A4DF3DDA7F6E98FDAC067BD5A8177133D4103B2142632AD5D89B124298EFA013` |

对比 UX13 批 #5：release **+704 B**、R8 **+620 B**（与新增 Kotlin 代码体量相符）；
**debug 反而 −66,713 B** —— 归因于 `--rerun-tasks` 全量重建清掉了上一轮的增量 dex 残留，
**不是**本轮删了东西。

> 为消除疑虑，做了**端到端落地验证**：直接在三个 APK 的 `classes*.dex` 原始字节里
> 搜新增日志串 `"UX14 render process gone"`，**三份全部 `True`**（含 R8 —— 该串是
> 日志字面量，不参与混淆）。即新增代码**确实进了包**，debug 变小与本轮改动无关。

⚠️ 三份仍是 **debug 签名（`CN=Android Debug`）+ `.debug` 包名 ⇒ 仅内部灰度**（HANDOFF §5②）。

---

## 6. 红线自检汇总

| 红线 | 结果 |
|---|---|
| **`ui/` 官方前端资源字节未动** | ✅ `git status --porcelain -- webapp/src/main/assets/www/ui/` **恒为空**；三份包内 `ui/` 条目恒为 **1613**（`oa/` **1590**） |
| **`app/**` 零改动** | ✅ `git status --porcelain -- app/` **为空** |
| **`oc-host.js` md5 保持** | ✅ 源文件 **62,186 B / md5 `232E916B5B16E99A6D94C3C4561A9B8A`**，三份包内**同值同体积** ⇒ **本轮未变** |
| **凭据扫描 0 命中** | ✅ `aapt2 dump strings` 逐包扫 `password="` / `sk-*` / `api_key="` / `Bearer …` / `token="` / `BEGIN PRIVATE KEY`：debug **2404** 串、nor8 **2404**、R8 **1322** —— 与 UX13 基线**完全一致** ⇒ 注入面未膨胀；命中 **0** |
| **未 commit** | ✅ 全程仅工作区改动（`M` + `??`），HEAD 仍是 `290ed63` |
| **4095 后端只读** | ✅ 收尾复测 `127.0.0.1:4095` LISTENING **pid 12748**（与任务书一致，**未起停**） |
| **本轮不起模拟器** | ✅ 无 `adb` / `emulator` 调用；运行时路径**未经设备验证**（§3.3） |
| **五个注入件未动** | ✅ 本轮未编辑任何注入件；`oc-host.js` md5 即为物证 |

---

## 7. 遗留与建议

1. **【OPEN · 设备验证缺口】** reload 自愈路径**未在真机/模拟器跑过**。下一轮若起设备，
   建议用 `adb shell am crash`/渲染进程 kill 制造真实崩溃，核对三件事：
   ① 日志出现 `UX14 render process gone didCrash=…`；② Activity **未** finish；
   ③ 二次崩溃落到 `rendercrash` 诊断页而非无限闪烁。
2. **【可选 · 更彻底的自愈】** 若实测发现 `reload()` 拉不起新渲染进程，
   下一步应按平台文档改为「旧实例移出视图树 + 新建 WebView」。当前实现的
   60 s 节流保证这条路**失败也不会变成重启风暴**，所以可以安全地在设备上验证。
3. **【已闭环】** HANDOFF §5 第 **11** 条可勾销；`PARITY_LEDGER_A4.md` **N-6** 中
   「`onRenderProcessGone` 未接」那半句亦可勾销（N-6 的另一半 —— release 包不含预检代码 ——
   **本轮未动，仍 OPEN**）。

---

**改点**：`WebHostActivity.kt`（import + 2 计数字段 + 60 s 常量 + override，删旧空壳）、新增 `RenderProcessRecoveryTest.kt`（4 用例）
**用例数 / 反向验证**：230（226+4）全绿；外科撤除 override ⇒ **4 红**（`230 tests completed, 4 failed`）；恢复 ⇒ 230 全绿
**三份产物**：16,800,362 B / `A71B0F45…`、14,307,626 B / `BC3E9027…`、12,027,472 B / `B6A346F6…`
**oc-host.js md5**：**保持** `232E916B5B16E99A6D94C3C4561A9B8A`（62,186 B，源与三包同值）
**锁**：Gradle `--no-daemon` 子进程已随脚本结束自然退出；4095 后端 pid 12748 未起停；**已释放**
**报告**：`research/web/UX14_P1_RENDER_RECOVERY.md`