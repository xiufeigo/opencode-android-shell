# CLEANUP_C1 — 卫生/清理轮：两处死代码/小缺陷的处置（每处带反向验证）

- **执行日期**：2026-10-04（硬期限 2026-10-07 之前 ✅）
- **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`，HEAD `b19fe52`
- **写权限**：`webapp/**` + `research/**` + 新增测试文件。**未碰** `app/**`、`_upstream/**`、`.github/**`。
- **行尾**：`git config core.autocrlf` = `false`（本轮实测）。全程只用 `edit`/`write` 编辑工具，
  **未**用 `git checkout` / `git restore` / `git stash` 改行尾。
- **断言**：**未删除、未放宽任何断言**；用例数 **16 → 20（+4）**。
- **真机**：**一次都没碰**（本轮不需要设备，见 §5）。**未启停后端**（Lead 的 `127.0.0.1:4095`）。
- **gradle 锁**：`Test-Path .agent-gradle-lock` = 不存在 → 等 0 s → 写锁 → 全部 gradle 前台跑完 → 单条顶层
  `rm -- ".agent-gradle-lock"` 释放（走 mavis-trash，**未绕过**安全策略）。收尾复检 = `False`。

---

## 0. 一句话结论

| # | 任务 | 处置 | 35dp 缺陷 | 反向验证 |
|---|---|---|---|---|
| 1 | 占位页 | **保留并改造成真正的错误/加载兜底页**（**不删**，见 §1.1 三条硬引用） | ✅ **一并解决**（35px → 48px） | ✅ 变红 |
| 2 | `BuildConfig.DEBUG` | **前提被证伪**：debug 变体里它**本来就是 `true`**；不加开关，只加**守约绊线** | — | ✅ 变红 |
| 3 | 回归 | 全绿 | — | — |

---

## 1. 任务 1：占位页 —— **保留（b），不删（a）**

### 1.1 依据：**它有 3 条活引用，不是死代码**

任务书给的证据是 `NARROW_BOTTOMBAR_E7.md`（路线 A 下 WebView 直连服务端 UI、实测兜底触发 0 次）。
「实测 0 次」我采信，但**「0 次可达」≠「无引用」**。逐条核实（`grep` 全仓，限 `webapp/src`）：

| # | 位置（改前行号） | 原文 | 性质 |
|---|---|---|---|
| R1 | `webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt:242-246` | `}.getOrElse {`<br>`    Log.e(TAG, "cannot start loopback proxy, falling back to placeholder", it)`<br>`    webView.loadUrl(LEGACY_PLACEHOLDER_URL)`<br>`    return` | **活引用**：回环代理起不来 ⇒ 兜底页是唯一落点 |
| R2 | `WebHostActivity.kt:611` | `outState.putString(STATE_URL, webView.url ?: HOME_URL)` | **活引用**：`webView.url` 为 null 时的存档回退 |
| R3 | `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt:43` | `put("homeUrl", WebHostActivity.HOME_URL)` | **活引用**：把它**以 `homeUrl` 暴露给官方 UI**（跨模块契约） |

`WebHostActivity.kt:54-59`（改前原文）也自证了它仍是设计的一部分：

```kotlin
/**
 * W1 的占位页（资产托管）。R1 起**不再**用它当首页，只在代理起不来时兜底，
 * 好让"页面全白"和"代理没起来"两种故障在屏幕上可区分。
 */
const val LEGACY_PLACEHOLDER_URL = "https://$ASSET_DOMAIN$ASSET_PREFIX/www/index.html"
val HOME_URL: String get() = LEGACY_PLACEHOLDER_URL
```

`WebHostActivity.kt:275` 另有一行注释明确「起不来仍然走 `LEGACY_PLACEHOLDER_URL` 兜底页」。

> ⚠️ 顺带核实到一处**原先没记录**的细节：`ASSET_PREFIX = "/assets/"`（`WebHostActivity.kt:53`）
> 末尾已带 `/`，所以 `LEGACY_PLACEHOLDER_URL` 实际拼出的是
> **`https://appassets.androidplatform.net/assets//www/index.html`（双斜杠）**。
> `WebViewAssetLoader` 能正常归一化，所以功能上无害；本轮**未改**（不在三件任务内），
> 记为 §4 未闭环。

**⇒ 决定 (b)：改成真正的错误/加载兜底页。** 理由：R1 是唯一的故障落点，删掉它 = 代理起不来时**白屏**，
正好抵消掉它当初被设计出来要解决的「白屏 vs 代理没起来」不可区分问题；R3 更硬，
删资产会让 `homeUrl` 指向不存在的资源。

### 1.2 改前原文 → diff

改前（`webapp/src/main/assets/www/index.html`）：

- L7 `<title>OpenCode Web Host — W1 骨架自检</title>`
- L52-55 `button { font: inherit; margin-top: 6px; padding: 6px 10px; … }`（**无 `min-height`**）
- L67-71 `<h1>OpenCode Web Host — W1 骨架自检</h1>` +「占位页。真正的上游产物……把本页换掉即可。」

```diff
--- a/webapp/src/main/assets/www/index.html
+++ b/webapp/src/main/assets/www/index.html
@@ -4,7 +4,7 @@
-    <title>OpenCode Web Host — W1 骨架自检</title>
+    <title>OpenCode Web Host — 连接失败</title>
@@ -52,6 +52,9 @@
       button {
         font: inherit; margin-top: 6px; padding: 6px 10px; border-radius: 6px;
         border: 1px solid var(--line); background: transparent; color: var(--fg);
+        /* C1/F2：触摸靶地板 48dp。固有盒高只有 21(行盒)+12(padding)+2(border)=35px，
+           低于 48dp 硬要求 ⇒ 显式抬到 48px。`FallbackPageTouchTargetTest` 钉这条。 */
+        min-height: 48px;
       }
@@ -64,10 +67,11 @@
   <body>
-    <h1>OpenCode Web Host — W1 骨架自检</h1>
+    <h1>无法连接到 OpenCode 服务</h1>
     <p class="sub">
-      占位页。真正的上游产物（<code>@opencode-ai/app</code> dist）在后续轮次塞进
-      <code>assets/www/</code>，把本页换掉即可。
+      本页是<b>回环代理起不来时的兜底页</b>（<code>WebHostActivity.kt:244</code>）。<br />
+      代理没能监听端口 ⇒ 上游 UI 拿不到，下面这些是宿主侧的自检读数，用于区分
+      「代理没起来」和「页面全白」。<b>它们不提供 OpenCode 功能</b>。
     </p>
```

> 保留原有的 8 行读数表（origin / AssetLoader / fetch / UA / bridge / hostInfo / insets / theme / back / secret / notify / IME）：
> 它们正是「区分代理没起来 / 页面全白」这件事的全部依据，删掉兜底页就失去意义了。
> 桥在此页仍可用（`addDocumentStartJavaScript` 与代理状态无关，facade 照常注入）。

### 1.3 35dp：**已一并解决**

F2（`research/web/TOUCH_TARGET_FIX.md:427-436`）记的未闭环原文：

> CSS 盒模型静态推导：内容行高 `14px × 1.5 = 21` + 上下 padding `6+6 = 12` + 上下 border `1+1 = 2`
> ⇒ **高 35px = 35dp**，**同样 < 48dp**（`index.html` L82/L85/L88/L91 四个 `<button>`）。
> ⚠️ 该 35dp 是**静态推导，未上设备复核**（本轮不碰设备）。

处置：`button` 规则加 `min-height: 48px` ⇒ 有效盒高 `max(35, 48) = 48px`。
页内 `<meta viewport>` 写着「1 CSS px == 1 dp」，故 **px 即 dp**，可直接比 48（`index.html:5-6`）。
**本轮仍未上设备复核 48px 的实测值**（见 §5）—— 静态推导部分已由单测钉住，实测部分记为未闭环。

### 1.4 绊线：`FallbackPageTouchTargetTest`（新增 2 例）

文件：`webapp/src/test/java/com/xiufeigo/opencodeshell/web/FallbackPageTouchTargetTest.kt`

| 用例 | 断言 |
|---|---|
| `everyInteractiveControlOnTheFallbackPageClears48dp` | 从 `index.html` 解析 `body` 的 `font: 14px/1.5` 与 `button` 规则的 `padding`/`border`/`min-height`，算 `max(内容+padding+border, min-height) ≥ 48`；且断言页内 `<button>` 个数 **> 0**（防空转） |
| `theFallbackPageIsStillReachableFromTheHost` | `LEGACY_PLACEHOLDER_URL` 仍含 `ASSET_DOMAIN` 且以 `/www/index.html` 结尾 —— 把「它还在兜底路径上」钉住 |

> 第二条在写完后**第一次跑就抓到我自己写错的断言**：我原本断言
> `.endsWith("/assets/www/index.html")`，实测红，暴露了 §1.1 那个双斜杠。已按真值改成
> 「含 `ASSET_DOMAIN` + 以 `/www/index.html` 结尾」。

### 1.5 反向验证 ①（**已变红**，原文）

把 `min-height: 48px` 整段撤掉（只留注释）后跑 `:webapp:testDebugUnitTest`：

```
> Task :webapp:testDebugUnitTest FAILED
FallbackPageTouchTargetTest > everyInteractiveControlOnTheFallbackPageClears48dp FAILED
    java.lang.AssertionError at FallbackPageTouchTargetTest.kt:76
20 tests completed, 1 failed
BUILD FAILED in 2s
```

失败断言原文（从 `TEST-…FallbackPageTouchTargetTest.xml` 读出，中文在该 XML 里是 GBK 乱码，
数值部分逐字可读）：

```
C1: 兜底页可交互控件的触摸靶只有 35.0 px(=dp)，必须 ≥ 48 px(=dp)。
    固有盒高 = 内容 21.0 + padding 12.0 + border 2.0 = 35.0，
    声明的 min-height = 0.0。页内 `<button>` 个数 = 4。
```

✅ **与 F2 的静态推导（21+12+2=35）逐位吻合**，且实测 `<button>` 个数 = **4**，与 F2 记的
L82/L85/L88/L91 四个按钮一致。随后已还原。

---

## 2. 任务 2：`BuildConfig.DEBUG` —— **前提被证伪，改为守约绊线**

### 2.1 查清「为什么是 false」：**它不是 false**

改前 `webapp/build.gradle.kts` 全文里**没有任何** `buildConfigField`；`buildTypes` 只有
L36-49 的 `debug`（`signingConfig` + `applicationIdSuffix = ".debug"`）与 `release`。
所以 `BuildConfig.DEBUG` 恒等于 AGP 写入的**该变体 `isDebuggable`**。

生成物逐字（`webapp/build/generated/source/buildConfig/debug/com/xiufeigo/opencodeshell/web/BuildConfig.java`）：

```java
public static final boolean DEBUG = Boolean.parseBoolean("true");
public static final String APPLICATION_ID = "com.xiufeigo.opencodeshell.web.debug";
public static final String BUILD_TYPE = "debug";
```

> ⇒ `com.xiufeigo.opencodeshell.web.debug` 这个包的 `BuildConfig.DEBUG` **编译期就是 `true`**。
> `PHONE_VIEWPORT_5636.md:109-110` / `NARROW_PHONE_E6.md:204` 记的「编译期为 false」**不成立**。
> （`WebHostActivity.kt:544` 的门卫 `if (!BuildConfig.DEBUG) return` 因此在 debug 下**不会**短路，
> 通道代码是进得去的。）

**⇒ 所以本任务不按「加 `buildConfigField` 修开关」处理** —— 那等于给一个本来为真的东西打补丁，
只会制造 `DEBUG` 与 `isDebuggable` 双源、真值漂移。改为**把「debug 开 / release 关」变成可断言的契约**。

### 2.2 处置：只加绊线，**不动开关**

`webapp/build.gradle.kts` 唯一改动是**把新增断言要读的文件声明成测试输入**（见 §2.4），
`buildTypes` **逐字未动**。

`WebHostActivity.kt:542-553` 逐字未动（`if (!BuildConfig.DEBUG) return` 门卫保留 ⇒
release 的 `DEBUG=false` ⇒ 静默忽略该 extra，**release 下仍然关闭**）。

### 2.3 真实根因：**未闭环**（诚实标注）

通道当时**确实**没回日志（`OcWebHost/Eval` 零命中），但既然 `DEBUG=true`、门卫不是原因，
真实原因本轮**没有定论**。两条**未验证**的候选（我都没有在设备上复现，不猜着改）：

1. **extra 传参在设备 shell 上被拆坏**。`research/env/probe_5636.ps1:15` 自己的注释就写着
   「设备 shell 会剥掉外层单引号，JS 内部用双引号」；`phoneE4_pipeline.ps1:36` /
   `phoneC_run3.ps1:114` 是把含空格/引号的 JS 直接塞进 `am start --es`，很可能被 shell 重切。
2. **注入落在 `about:blank`/中间态文档**。`EVAL_SETTLE_MS = 1200L`（`WebHostActivity.kt:75`）
   是固定 1.2 s；E4 记录过「通道存在但页面从未加载」（`PHONE_AVD_E4.md:158`），
   页面没起来时 `evaluateJavascript` 无处可落，且回调**不报错**、只是没有 `OcWebHost/Eval` 行。

⇒ 本轮**只把开关契约钉住**，不去改 `EVAL_SETTLE_MS` 之类没证据的东西。E5 因此改用 CDP 的决定**不需要回退**。

### 2.4 绊线：`DebugEvalChannelTest`（新增 2 例）+ 测试输入声明

文件：`webapp/src/test/java/com/xiufeigo/opencodeshell/web/DebugEvalChannelTest.kt`

| 用例 | 断言 |
|---|---|
| `debugBuildEnablesTheChannel` | `BuildConfig.BUILD_TYPE == "debug"`（确认跑在 debug 变体）且 `BuildConfig.DEBUG == true` |
| `releaseBuildKeepsTheChannelClosed` | ① 任何变体都**没有** `buildConfigField("boolean", "DEBUG", …)` 覆盖；② `release { … }` 块里**没有** `isDebuggable = true`；③ `WebHostActivity.kt` 里 `if (!BuildConfig.DEBUG) return` 门卫仍在；④ `EXTRA_DEBUG_EVAL == "oc_debug_eval"`（`research/env/*.ps1` 全靠这个名字） |

**测试输入声明**（`webapp/build.gradle.kts:86-108`）—— 这是让上面两条断言**真能变红**的前提。
沿用文件里 IME1 已有的做法与其理由（改前 L86-92 原文：*"单测任务默认不把 `src/main/assets` 当输入
… 会被判为 UP-TO-DATE **整段跳过**，绊线于是"假绿"——本轮真跑验证过：撤回修法后 gradle 仍然
BUILD SUCCESSFUL"*）：

```diff
 tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
-    inputs.file(layout.projectDirectory.file("src/main/assets/www/oc-host.js"))
-        .withPropertyName("ocHostFacadeAsset")
-        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
+    listOf(
+        "src/main/assets/www/oc-host.js",
+        "src/main/assets/www/index.html",
+        "build.gradle.kts",
+        "src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt",
+    ).forEach { rel ->
+        inputs.file(layout.projectDirectory.file(rel))
+            .withPropertyName("contractAsset_$rel")
+            .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
+    }
 }
```

### 2.5 反向验证 ②（**已变红**，原文）

在 `buildTypes { debug { … } }` 里临时加 `isDebuggable = false`（即「把开关改回 false」）：

```
> Task :webapp:testDebugUnitTest FAILED
DebugEvalChannelTest > debugBuildEnablesTheChannel FAILED
20 tests completed, 1 failed
BUILD FAILED in 5s

FAILED: debugBuildEnablesTheChannel
```

✅ 变红。随后已还原（`git diff` 见 §1.2 上方的 `build.gradle.kts` diff，`buildTypes` 无残留）。

---

## 3. 回归

命令（前台、持锁、一次跑完）：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --offline :webapp:cleanTestDebugUnitTest :webapp:testDebugUnitTest `
              :webapp:assembleDebug :app:cleanTestDebugUnitTest :app:testDebugUnitTest
```

`BUILD SUCCESSFUL in 2m 31s` / `95 actionable tasks: 18 executed, 77 up-to-date`

| 模块 | 任务 | 测试类 | 用例 | 失败 | 错误 |
|---|---|---|---|---|---|
| `:webapp` | `testDebugUnitTest` | 4 | **20** | **0** | **0** |
| `:webapp` | `assembleDebug` | — | `webapp-debug.apk` **8,636,901 bytes** ✅ | — | — |
| `:app` | `testDebugUnitTest` | **677** | **6,136** | **0** | **0** |

`:webapp` 逐类：

| 类 | 用例 | 失败 |
|---|---|---|
| `DebugEvalChannelTest`（**本轮新增**） | 2 | 0 |
| `FallbackPageTouchTargetTest`（**本轮新增**） | 2 | 0 |
| `host.ImeOcclusionTest`（既有） | 8 | 0 |
| `SetupDialogTouchTargetTest`（既有，**F2 那 8 条**） | 8 | 0 |

**用例数只增不减**：`:webapp` 16 → **20**（**+4**）。`:app` **6,136** 条**一条未删、一条未放宽**
（本轮**根本没改 `app/**`**，该模块全绿是纯回归确认）。

---

## 4. 未闭环

1. **`oc_debug_eval` 通道的真实根因未定论**（§2.3）。本轮只钉住开关契约；`EVAL_SETTLE_MS`、extra 传参
   编码这两条候选**都没有在设备上复现**，**未改**。要闭环需在 `emulator-5824` 上用 base64/单引号
   安全的传参方式实跑一次。
2. **48px 只做了静态推导，未上设备复核**（§1.3）。F2 原话「未上设备复核」的限制本轮仍然成立 ——
   单测能证明 CSS 盒模型算出 ≥48dp，**证明不了** 设备上 `getBoundingClientRect()` 量出来是 48。
3. **`ASSET_PREFIX` 双斜杠**（§1.1）：`.../assets//www/index.html`。
   功能无害（`WebViewAssetLoader` 归一化），本轮未改，不在三件任务内。
4. **兜底页没有「重试」按钮**。真正的恢复动作是重启 Activity（会重走 `launch()`），
   而现有 bridge（`WebHostBridge.kt`）没有暴露 reload 能力 —— **没有现成方法可调，本轮不臆造**。
   兜底页因此是「可诊断」而不是「可自愈」。
5. **占位页上另外两个原生控件未复核**：F2 修的是首启 `AlertDialog` 的两个 `EditText`（45→48dp），
   本轮**未触碰** `SetupDialogTouchTargetTest` 的 8 条断言（仍全绿）。

---

## 5. 设备与后端纪律

| 项 | 本轮 |
|---|---|
| 真机/模拟器 | **一次都没碰**。本轮三件任务全是构建期/静态断言，**不需要设备** ⇒ 无 serial 需要记录指纹 |
| 触碰过的 serial | **无**（`emulator-5824` 及其它任何 serial 均未下发过 adb 命令） |
| 后端 `127.0.0.1:4095` | **未启停、未探活、未 `adb reverse`**（本轮无设备交互） |
| `wm` | **未改**（收尾无需恢复） |
| gradle 锁 | 等 0 s → 写锁 → 前台跑完 → 单条顶层 `rm -- ".agent-gradle-lock"` 释放 → 复检 `Test-Path` = `False` |
