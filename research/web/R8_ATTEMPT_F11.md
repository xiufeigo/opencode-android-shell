# R8_ATTEMPT_F11 — webapp R8 试产 + 设备验证（F11 轮）

> 角色：构建瘦身工程师（F11：试产 R8 版并用设备验证它没坏；做不成就如实留下非 R8 为推荐）
> 日期：2026-10-05 09:55–10:20 (+08:00) ｜ 硬期限 2026-10-07 前
> 设备：`emulator-6290`（AVD `OC_Deliver_R2`，端口 **6290**，**本轮自建 → 本轮已关**）
> 写权限：`webapp/build.gradle.kts`（仅 R8 开关/变体）、`webapp/proguard-rules.pro`、`research/**`、`research/deliver/**`
> **未碰**：`app/**`、`webapp/src/**`（**一行都没改**）、`_upstream/**`、`.github/**`、未删/放宽任何断言
> 后端 `127.0.0.1:4095`：**全程未起停**（开轮与收尾均 `LISTEN pid=3748`，同一 pid），只做 `adb reverse`

---

## 0. 一句话结论

**R8 版构建成功（1,435,193 B，−63.90%），并且在设备上通过了口令存储 / JS 桥 / 错误态三态的逐条验证。**
但**常亮只验到"机制存活 + 空闲态正确"，没有验到"回合进行中 flag 真的置起"这一段**——
按本轮的红线（不许拿没验过的项冒充 PASS），**结论是「R8 版有条件可分发，默认推荐仍保持非 R8」**。

| 判定项 | 结果 | 依据 |
|---|---|---|
| R8 构建 | ✅ **成功** | §3，`minifyReleaseR8WithR8` + `shrinkReleaseR8Res` 均执行 |
| 口令存储（Tink 最高风险） | ✅ **PASS** | §5.4：杀进程重开免密、`EncryptedSharedPreferences unavailable` 命中 **0** |
| JS 桥 | ✅ **PASS** | §5.5：回环代理吐出完整官方 UI 资产（31 条命中）、`@JavascriptInterface` 类名/方法名未被改名 |
| 错误态三态 | ✅ **PASS** | §5.6：正确/错口令/不可达三态各自独立且不串态 |
| **常亮三段** | 🟡 **部分验到**（未闭环 1 段） | §5.7：三段空闲态均 `flagSet=false`（**这是正确值**）+ `D1 applyKeepScreenOn` 日志存活；**回合进行中那 0x80 置起段没验** |
| 首屏（同机同轮） | R8 **644 ms** vs 非 R8 **691 ms** | §5.3，n=3，极值 612–644 / 625–691 |
| **结论** | **有条件可分发；默认推荐仍为非 R8** | §8 |

---

## 1. 环境事实（开轮与收尾）

| 项 | 值 |
|---|---|
| `adb` | `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe` |
| `JAVA_HOME` | `C:\Program Files\Android\Android Studio\jbr` |
| 镜像 | `android-35;google_apis_playstore_tablet` + `pixel_tablet`（AVD `OC_Deliver_R2`） |
| **几何** | `wm size 1080x1600` + `wm density 420` 覆盖（**不传 `-skin`**：`pixel_tablet` 不是合法 skin 名，V7 轮已踩） |
| 设备指纹 | `google/sdk_gtablet_x86_64/emu64xa:15/AE3A.240806.046.T1/13135149:user/dev-keys` |
| 机型/版本 | Pixel Tablet / Android 15 / SDK 35 / ABI x86_64 |
| WebView | `124.0.6367.219` |
| 后端 | `4095 LISTEN pid=3748`（开轮 = 收尾，**未起停**） |

> 端口选择：**6290**（偶数、**≥6290**），启动前 6290/6292/6294 全部 `FREE`（原文见 `out_f11/boot.log`）。

---

## 2. 12 项风险 → keep 规则映射表（**先落盘再动手**）

**落盘位置：`webapp/proguard-rules.pro`（本轮整体重写，文件内每条规则都标注了它对应下面哪一项）。**
风险清单本体取自 `research/web/WEB_RELEASE_BUILD.md` §5.2（E1 轮的纯静态评估），**本轮按实际读到的源码逐条复核过**。

> ⚠️ 一条总原则先钉死：**本轮没有为了让构建通过而把混淆放宽成"等于没混淆"。**
> 文件里**没有任何** `-dontshrink` / `-dontobfuscate` / `-dontoptimize`；每条规则都是定向的。
> 混淆**确实生效**的证据见 §3.3。

| # | 风险点 | 它为什么会坏 | 对应 keep/配置（文件:行） | 构建后如何验 | 本轮实测 |
|---|---|---|---|---|---|
| **7** 🔴 | **Tink / `EncryptedSharedPreferences`** | `SecretStore.kt:63-71` 用 MasterKey+ESP 存口令。Tink 内部**基于反射装配** keyset 与 AEAD primitive；裁坏后**不崩**，而是触发 `SecretStore.kt:76` 的 `Log.w(... falling back to in-memory only)` ⇒ **静默降级** ⇒ 口令不落盘 ⇒ 重启反复要密码 | `proguard-rules.pro:63-66` `-keep class com.google.crypto.tink.** { *; }` + `-keep class androidx.security.crypto.** { *; }` + `-dontwarn` 两条；`:73-77` `-keepattributes Signature / *Annotation* / InnerClasses,EnclosingMethod` | 杀进程重开仍免密 + grep `EncryptedSharedPreferences unavailable` | ✅ **PASS**（§5.4） |
| **1** | `WebHostBridge` 的 `@JavascriptInterface` 方法 | `addJavascriptInterface` 是 **native 侧按方法名反射**；改名/删方法 ⇒ JS 侧静默拿到 `undefined`。编译期与单测**都测不出** | `proguard-rules.pro:44-46` `-keepclassmembers class …web.** { @android.webkit.JavascriptInterface <methods>; }`；另 `:58` 整包 keep 二次兜底 | 触发桥 → 看代理/日志是否正常；核对 mapping 里类名未改 | ✅ **PASS**（§5.5） |
| **4** | `WebHostBridge.INJECT_NAME`（`addJavascriptInterface` 的名字） | 同上，**按名字**反射查找；`INJECT_NAME` 若被内联/改名，桥整体失联 | 同 #1（`@JavascriptInterface` 成员保留 + 整包 keep 保住常量） | mapping 查 `WebHostBridge` 未改名 | ✅ **PASS** |
| **2** | `WebHostActivity` / `WebHostApp` 被 manifest 引用 | 被 R8 裁掉 ⇒ 启动即崩 | AGP 对 manifest 声明组件**自动 keep**；另被 `:58` 整包 keep 覆盖 | 冷启 3 次无崩 | ✅ PASS（§5.3 冷启 3 次 + 全程 `CRASH_LINES: NONE`） |
| **5** | `FileChooserHost`/`LoopbackProxy`/`SecretStore`/`InsetsHost`/`NotifierHost`/`PerfMarks` | 反射/注解调用点被裁 | `proguard-rules.pro:58` `-keep class …web.** { *; }` | mapping 逐个查未改名 | ✅ PASS（`LoopbackProxy`/`SecretStore`/`InsetsHost`/`NotifierHost` 全部未改名，§3.3） |
| **3** | `assets/www/oc-host.js`/`oc-perf.js`/`oc-preflight.js` 经 `addDocumentStartJavaScript` 注入 | 若被裁 ⇒ 注入层失效（IME 抬升、首屏探针全哑） | **不需要规则**（正确处置是"不加"）：R8 与 shrinkResources **不处理 `assets/`** | 冷启日志里三个探针是否注册 | ✅ PASS（`E2.perfProbeRegistered` / `E3.g1ProbeRegistered` / `facade installed` 齐全，§5.3） |
| **6** | `androidx.webkit`（`WebViewAssetLoader`/`WebViewCompat`/`WebViewFeature`） | `isFeatureSupported` 若被裁 ⇒ 特性判定错 | 库**自带 consumer rules**；`isFeatureSupported` 是常规静态调用非反射 ⇒ **不加**整包 keep（加了等于扔掉 R8 收益） | 冷启日志 `G1 webview version=124 major=124 tooOld=false` | ✅ PASS（`tooOld=false`，且 `blockedOldWebview=0`） |
| **8** | `okhttp` | 可选 platform provider 缺失时炸 | 自带 consumer rules；补 `:80-81` `-dontwarn okhttp3.internal.platform.**`（**只压警告，不改裁剪行为**） | 代理能否正常发上游请求 | ✅ PASS（代理 31 条资产 GET 正常） |
| **9** | `kotlinx-coroutines` | debug agent 被剥 | 自带 consumer rules | 功能不受影响 | ✅ PASS（异步链路 `Z.firstDraw`/`L.onPageFinished` 正常） |
| **10** | `res/` 收缩（`isShrinkResources = true`） | 官方 UI 是页面 HTML，不引本 APK 的 `R.string`；若 `@string/app_name`/`@style/Theme.OcWebHost`/`@mipmap` 被裁 ⇒ 首启配置框缺文案/图标 | **不加 `tools:keep` 兜底**：manifest 显式引用这三项 ⇒ shrinkResources 按引用图自动保留。`resources.txt` 共 5,811 行，**故意用实机截图证伪而不是用规则掩盖** | 首启配置框文案 + 截图 + 应用名 | ✅ PASS（§5.2 截图：口令框/保存并连接/应用名/图标均正常） |
| **11** | `::class.java` 取 `NotificationManager`/`WebHostActivity` | 混淆后类名变 → `ClassNotFound` | 常规 Kotlin 引用，编译期已知；被 `:58` 整包 keep 覆盖 | 通知点击回跳（**本轮未单独触发**，见 §7） | 🟡 **未单独验**（非 R8 基线同样未在本轮触发） |
| **12** | `data class`/序列化 | 反射构造/字段名丢失 | 本模块**无** `@Serializable`/Gson/Moshi/`readResolve`（`grep` 全量确认）⇒ 正确处置是"不需要"；enum（`ImeMode`/`Kind`/`StreamingAction`/`RoundStreamSignal`）与 `valueOf` 均在本包内 ⇒ 被 `:58` 覆盖 | — | ✅ 安全（静态闭环） |

**清单之外我按派单要求补查的三项，结论都是"不需要规则"，并写进了规则文件末尾**：

| 派单点名项 | 实际查到什么 | 处置 |
|---|---|---|
| `ServiceLoader` | `webapp/src/main` 全量 `grep ServiceLoader` / `Class.forName` **零命中** | **不加**任何 ServiceLoader 规则（加了是凭空扩大保留面） |
| 注解处理器生成物 | 本模块**无** Dagger/Room/Glide；Tink 的 `*Proto` 是上游 AAR **打包期**带入的既有产物 | 由 §#7 的 Tink 整包 keep 覆盖 |
| Kotlin 元数据 / `kotlin-reflect` | `build.gradle.kts:83-96` 依赖里**没有** `kotlin-reflect` ⇒ 无"kotlin-reflect 使用点" | 不加规则；元数据靠 `-keepattributes` + 本包整包 keep |

---

## 3. 构建

### 3.1 构建配置改动（唯一改动的构建文件：`webapp/build.gradle.kts`）

E1 轮留的是**开关式** `-Pwebapp.r8=true`。本轮**故意改成独立 buildType**，理由写在文件注释里：

> 开关式的隐患是 —— 同一个输出路径 `outputs/apk/release/webapp-release.apk` 在带不带 `-P` 时**静默变成两份不同的包**。
> 只要有人（或 CI）顺手带上那个开关，**已验收的分发物就被 R8 版顶替**，而且从变体列表上看不出来。

```diff
 release {
-    val r8 = providers.gradleProperty("webapp.r8").orNull == "true"
-    isMinifyEnabled = r8
-    isShrinkResources = r8
+    isMinifyEnabled = false      // F11：改成常量 —— release 永远是已验收的非 R8
+    isShrinkResources = false
     signingConfig = signingConfigs.getByName("sharedDebug")
     applicationIdSuffix = ".debug"
     proguardFiles( … )
 }
+create("releaseR8") {
+    initWith(getByName("release"))
+    isMinifyEnabled = true
+    isShrinkResources = true
+    matchingFallbacks += listOf("release")
+}
```

- ✅ **`assembleDebug` 未动**；✅ **`assembleRelease` 仍是非 R8**（硬要求）。
- `releaseR8` 沿用 `applicationIdSuffix = ".debug"` ⇒ 与 release **同 applicationId** ⇒ 可**互相覆盖安装**，这正是同机同轮 A/B 需要的。
- 未加 `isDebuggable = true`（C1 契约要求 release 侧 debug 通道关闭，`releaseR8` 同样关闭）。
- **`-Pwebapp.r8=true` 现在是 no-op**：留着它是为了不破坏任何已有脚本的调用，但**它不再能改变 `release` 的产物**。
- **契约测试未触犯**：`DebugEvalChannelTest` 的三条断言逐条自查通过（无 `buildConfigField("boolean","DEBUG"…)` 覆盖；`release` 块内无 `isDebuggable = true`，且正则 `\brelease\s*\{(.*?)\n        \}` 仍能匹配到该块；`initWith` 在 release 块之后不影响惰性匹配；源码侧 `if (!BuildConfig.DEBUG) return` 未动）。
  - ✅ **并且真跑过、不是静态推断**（原文见 `out_f11/contract-test.log`）：
    ```
    > Task :webapp:testDebugUnitTest
    BUILD SUCCESSFUL in 9s
    24 actionable tasks: 1 executed, 23 up-to-date      ← 1 executed = 契约测试真跑
    EXITCODE=0
    ```
    `build.gradle.kts` 本身是该任务的**声明输入**（`build.gradle.kts:110-120`），所以改了它就必重跑。
    **16 suites / 147 tests / 0 failures / 0 errors / 0 skipped**（含 `DebugEvalChannelTest` 2/2、
    `RoundActivityTrackerTest` 16/16、`KeepScreenOnWiringTest` 14/14 —— 后两个正好覆盖常亮链路）。
    **未删、未放宽任何断言**；`webapp/src/**` 本轮 **0 个文件被改**。

### 3.2 构建命令与 `BUILD SUCCESSFUL` 原文

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :webapp:assembleReleaseR8 --console=plain --no-daemon
```

原文尾段（`research/web/out_f11/build-r8.log`）：

```
> Task :webapp:lintVitalAnalyzeReleaseR8
> Task :webapp:lintVitalReportReleaseR8
> Task :webapp:lintVitalReleaseR8
> Task :webapp:minifyReleaseR8WithR8          ← R8 真的跑了
> Task :webapp:compileReleaseR8ArtProfile
> Task :webapp:shrinkReleaseR8Res             ← 资源收缩也跑了
> Task :webapp:convertShrunkResourcesToBinaryReleaseR8
> Task :webapp:optimizeReleaseR8Resources
> Task :webapp:packageReleaseR8
> Task :webapp:assembleReleaseR8

BUILD SUCCESSFUL in 45s
48 actionable tasks: 46 executed, 2 up-to-date
EXITCODE=0
```

**E1 轮的阻塞（"webapp 源码不可编译"）本轮已不复存在** —— `compileReleaseKotlin` 正常通过，只剩 4 条 deprecation 警告。

### 3.3 混淆**确实生效**的证据（不是"等于没混淆"）

| 证据 | 原文 |
|---|---|
| 依赖类被改名 | `okhttp3.OkHttpClient -> q0.q`；`androidx.appcompat.app.AppCompatActivity -> g.l`；`kotlin.jvm.internal.Intrinsics -> i0.d` |
| Tink 被改名 | `com.google.crypto.tink.EncryptionConfig -> …`（在 mapping 里有映射行） |
| 被删条目 | `usage.txt` **21,927** 行 |
| **我自己的桥/宿主类未改名**（keep 生效） | `WebHostBridge -> com.xiufeigo.opencodeshell.web.host.WebHostBridge`；`RoundActivityTracker` / `PowerPolicy` / `SecretStore` / `InsetsHost` / `NotifierHost` / `LoopbackProxy` **全部原名保留** |
| 体积形态 | 两个 dex（8,745,636 + 960,316 B）→ **单 dex 2,594,228 B** |

### 3.4 产物体积 · md5

| 产物 | 体积 | md5 | 说明 |
|---|---:|---|---|
| **webapp `releaseR8`（R8）** | **1,435,193 B** | `0598817026665544a08803055107a9fd` | `webapp/build/outputs/apk/releaseR8/webapp-releaseR8.apk` |
| webapp `release`（非 R8，默认推荐） | 3,975,389 B | `76ca064b7de9246ee446f765a9198b37` | **未改动**，与 §0.1 现行交付包逐位一致 |
| 差值 | **−2,540,196 B（−63.90%）** | — | R8 版只有非 R8 的 **36.10%** |

已复制到 `research/deliver/opencode-shell-web-release-r8.apk`（**新文件名，不与 `-nor8` 混淆**，规避 HANDOFF §4.2 记的"文件名陷阱"）。
`research/deliver/opencode-shell-web-release-nor8.apk` **一个字节都没动**（md5 复核仍为 `76CA064B…`）。

APK 内条目构成（R8 版，压缩前/后）：

| 条目 | raw | stored |
|---|---:|---:|
| `classes.dex` | 2,594,228 | 987,606 |
| `resources.arsc` | 198,444 | 198,444 |
| `okhttp3/internal/publicsuffix/publicsuffixes.gz` | 41,394 | 41,409 |
| `assets/www/*`（4 个） | ~40,800 | ~15,475 |
| 合计条目数 | 368 | |

---

## 4. 设备验证方法（诚实交代两个坑）

1. **`uiautomator` 看不见 WebView 的 DOM** ⇒ 错误态文案不能靠无障碍树判读。
   - 错口令态：靠**原生对话框层**的 `text=` 节点（这一层抓得到）。
   - 不可达态：靠**截图肉眼判读**（`out_f11/E1_final.png`）+ logcat 的 `F5 probe unreachable`。
2. **播种口令必须逐字符输入**：F5 轮实测本镜像软键盘下 `input text "opencode"` 会丢字符 ⇒ 本轮一律 `foreach ($ch in $pw.ToCharArray())`。
3. `run-as` 取不到 `/data/data/<pkg>/shared_prefs`（`NO_RUNAS`）⇒ 口令落盘的直接证据改用**行为证据**（杀进程重开免密）+ **静默降级日志 grep**。

---

## 5. 逐条验证结果

### 5.1 前置断言：已鉴权 + 数据已到（**真实会话列表，不是骨架屏**）

```
R8A_AUTHFAILED=0 (expect 0)
R8A_probeOK=True
R8A_API_GETS_UNIQUE=10
  GET /api/event  /api/info  /api/location  /api/project
  GET /api/session?limit=5000&order=desc&parentID=null
  GET /api/session/active  /api/vcs  /api/location
  GET /api/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y
  GET /api/session/ses_f408ec4a0ffeKjtcGKG4kfGEBR
R8A_SESSION_IDS_n=2 -> ses_efc722bf9ffeWaRdoFTwbmYj1Y, ses_f408ec4a0ffeKjtcGKG4kfGEBR
R8A_HAS_AUTH_DIALOG=False
R8A_SKELETON=False
R8A_firstContent=True
```

✅ **PASS**：拿到**真实 session id 列表**（`ses_…`），且与非 R8 基线**拿到完全相同的 2 个 id** ⇒ 数据确实到了、不是骨架屏。

### 5.2 风险 #10：res/ 收缩（`shrinkResources = true`）

首启配置框截图（`out_f11/*.png`）+ `uiautomator` 均显示：口令输入框存在、`保存并连接` 按钮存在、应用名/图标正常。
✅ **PASS** —— `shrinkResources` **没有**把首启配置框的资源裁坏。

### 5.3 首屏（≥3 次，同机同轮，不跨轮沿用）

| 版本 | 3 次实测 | 中位数 | 极值 |
|---|---|---:|---|
| **非 R8**（基线，同一轮） | 625 / 691 / 665 ms | **691 ms** | 625–691 |
| **R8** | 618 / 612 / 644 ms | **644 ms** | 612–644 |

- 口径：`M.g1.firstContent t=`（`kind=dcl`），冷启（`force-stop` + 3 s 停顿）×3，已鉴权。
- R8 两次 `blockedOldWebview=0`；WebView 判定 `G1 webview version=124.0.6367.219 major=124 tooOld=false`。
- **差值 = −47 ms（−6.8%）**。⚠️ 见 §8：**这个量级不足以支撑改推荐**。

### 5.4 🔴 风险 #7：口令存储（Tink 静默降级）—— **最高风险项，PASS**

```
R8B_AFTER_RELAUNCH_HAS_AUTH_DIALOG=False (expect False => secret survived)
R8B_probeOK=True
R8B_AUTHFAILED=0
R8B_TINK_SILENT_FALLBACK_HITS=0 (expect 0)
```

流程：播种正确口令 → `am force-stop` → 4 s → 冷启重开。
✅ **PASS**：重开后**没有要口令**、`probe OK`、0 次 AUTH FAILED，
且**全量 logcat grep `EncryptedSharedPreferences unavailable` 命中 0** ⇒
**没有触发 `SecretStore.kt:76` 的静默降级分支**。这是 Tink 没被裁坏的行为级证据。

### 5.5 风险 #1/#4：JS 桥 —— **PASS**

| 证据 | 值 |
|---|---|
| 回环代理吐出官方 UI 资产 | **31 条** `OcWebHost/Proxy` 命中（`_assets/session-mobile-files-*.js`、`sequenceDiagram-*.js`、`screen-*.css` …） |
| 桥宿主类名未被改名 | `WebHostBridge -> com.xiufeigo.opencodeshell.web.host.WebHostBridge`（`@JavascriptInterface` 按方法名反射的前提） |
| 注入层存活 | `facade registered as document-start script` / `E2.perfProbeRegistered` / `E3.g1ProbeRegistered` |
| 常亮联动（`RoundActivityTracker`）类存活 | `RoundActivityTracker -> …RoundActivityTracker`（未改名）**且运行时真的在打日志**（§5.7） |
| debug eval 通道 | release 侧**正确保持静默**（`--es oc_debug_eval` 在 release 无效，与 C1 契约一致）⇒ **不适用**于 R8 版验证 |
| 崩溃/渲染进程 | 全程 `CRASH_LINES: NONE`（见 §5.6 备注）、`E1_RENDERGONE: NONE` |

### 5.6 错误态三态 —— **PASS（三态独立、不串态）**

| 态 | 造法 | 观测 | 判定 |
|---|---|---|---|
| **① 正确口令** | `Seed opencode` | `probeOK=True`、`AUTHFAILED=0`、**2 个 session id**、无鉴权对话框 | ✅ |
| **② 错口令** | `Seed wrongpass` | 原生层文案原文：**「登录没通过 / 服务器拒绝了登录：用户名或口令不对。请确认后重试，或重新输入口令」** + `AUTHFAILED=1` + `probeOK=False`；**不回显 `wrongpass`** | ✅ |
| **③ 不可达** | `reverse tcp:4095 tcp:4099` | logcat `F5 probe unreachable reason=IOException, loading anyway`；`upstream GET http://127.0.0.1:4095/ failed: unexpected end of stream`；**截图肉眼判读**（`out_f11/E1_final.png`）：**「连不上服务器 / 请检查服务器地址是否正确、设备是否连上了服务器所在的网络，然后重试。」+「重试」按钮**；**不泄露 4095 端口**（`LEAKS_4095=False`）；`AUTHFAILED=0`（不可达 ≠ 鉴权失败，**没有串态**） | ✅ |

> 不可达态的截图我已逐字看过（见本轮交付附件），文案是友好的中文提示，不是裸错误码。

### 5.7 🟡 常亮三段（含 +90 s）—— **部分验到，1 段未闭环**

**采样（三段，含 +90 s）**：

| 段 | 时刻 | `D1 applyKeepScreenOn` 原文 | 判读 |
|---|---|---|---|
| ① t0 | +0 s | `roundActive=false foreground=true shouldKeep=false flagSet=false` | ✅ 空闲态**正确**（不置旗） |
| ② +30 s | +30 s | 同上（`D1_COUNT=1`，无新增） | ✅ 空闲态**正确** |
| ③ +90 s | +90 s | 同上 | ✅ 空闲态**正确**；**+90 s 兜底没有把旗错误地挂回** |

`dumpsys power` 三段均为 `mWakefulness=Awake`。

**✅ 已闭环的部分**：
- `RoundActivityTracker` **类未被改名/未删除**（mapping 原文），且**运行时真的在执行**（`D1 applyKeepScreenOn` 有真实日志行）⇒ **代码路径没被 R8 死掉**。
- F4 轮确立的语义：`flagSet` 只在 `roundActive=true` 时应为 true；**空闲态 `false` 是正确值**，不是缺陷（我第一版探针把它当"期望 true"是我的判读错误，已在 §7 记录）。

**❌ 未闭环的部分（如实写，不冒充 PASS）**：
- **`roundActive=true` 期间 0x80 置起这一段没验**。原因：本轮要在设备上驱动一个**真实编码回合**（发 prompt → 回合置起 → 回合结束 → 兜底复查），
  超出本轮时间预算（F11 窗口内还要留出构建/验证/收尾）。⇒ **该段维持 F4 轮的 🟡 级别，不因本轮升级**。
- 另外 `dumpsys window` 的窗口 flags 位在本轮**三版探针都没取到**（`grep: bad regex` / `bad regex 'Window\\{|…'` / 换行被压平导致 `Window{` 块匹配不到），
  所以 **0x80 位是从应用自身日志 `flagSet=` 判读的，不是从窗口属性独立判读的** ⇒ 这是本项的口径**弱点**，一并如实记账。

---

## 6. 与非 R8 版的同机同轮对比

> 全部在 `emulator-6290` 同一台机、同一轮（10:03–10:14）内取数，**不跨轮、不混轴**。

| 维度 | 非 R8 `76ca064b…` | R8 `0598817026…` | 差 | 判读 |
|---|---:|---:|---:|---|
| **体积** | 3,975,389 B | **1,435,193 B** | **−63.90%** | R8 收益显著 |
| **首屏中位数（n=3）** | 691 ms | **644 ms** | −47 ms（−6.8%） | 方向性偏好 R8，量级小 |
| 首屏极值 | 625–691 ms | 612–644 ms | — | 区间不重叠，但 n=3 不足以定论 |
| 鉴权 + 数据到达 | ✅ 2 个 session id | ✅ **同样 2 个 session id** | 一致 | 等价 |
| 口令存储（杀进程重开） | —（本轮未单独测非 R8） | ✅ PASS | — | 非 R8 侧**未测**，不代它下结论 |
| 错误态三态 | —（本轮未单独测非 R8） | ✅ PASS | — | 同上 |
| 崩溃 | — | ✅ 全程 `NONE` | — | 同上 |
| 混淆 | 无 | ✅ 依赖侧实改（`q0.q`/`g.l`/`i0.d`） | — | 真实生效 |

⚠️ **诚实边界**：非 R8 基线本轮**只**测了"鉴权 + 数据到达 + 首屏 3 次"三项（因为它就是已验收的交付包，V12/F10 已有其三态与常亮证据）。
**R8 版的口令存储/三态/崩溃项没有同轮非 R8 对照数** ⇒ 报告里不写"R8 优于/等于非 R8"的对齐结论，只写"R8 自身是否达标"。

---

## 7. 本轮踩的坑与判读错误（如实记账）

1. **脚本编码**：`powershell` 5.1 把 UTF-8 无 BOM 的 `.ps1` 按 GBK 读 ⇒ 中文正则全部引号失配、脚本**解析即失败**。
   修法：写入时带 **UTF-8 BOM**。（pwsh 7 的可执行文件在本机是 WindowsApps 版，`C:\Program Files\PowerShell\7\pwsh.exe` **不存在**。）
2. **常亮判读错误（我的错，不是应用坏）**：第一版探针把"空闲 `flagSet=false`"当成"期望 true"⇒ 报 `UNKNOWN`。
   F4 轮 `FIX_F4_KEEPSCREEN.md` 已写明 `flagSet` 只在回合进行中为 true。已在 §5.7 更正。
3. **`uiautomator` 读不到 WebView DOM** ⇒ 不可达态文案改用截图判读（已附）。
4. `dumpsys window` 三版探针都失败（`bad regex` / 换行压平）⇒ 常亮 0x80 位只能靠应用日志判读（§5.7 口径弱点）。

---

## 8. 结论：**有条件可分发；默认推荐仍保持非 R8**

**判定依据（逐条）**：

- ✅ **支持"可分发"的**：构建成功；体积 **−63.90%**；最高风险 #7（Tink 静默降级）**行为级验证 PASS**；
  #1/#4 JS 桥 PASS；#3/#6 注入层与 WebView 特性判定 PASS；#10 `shrinkResources` PASS；错误态三态 PASS；全程无崩溃/无渲染进程丢失。
- ❌ **不足以"改默认推荐"的**：
  1. **首屏只快 47 ms（−6.8%）**。派单要求"改推荐必须给出量化理由，例如首屏明显更快"——
     **−6.8% 不构成"明显更快"**，n=3 也不足以定论。**收益几乎全部在体积/分发侧，不在首屏侧。**
  2. **常亮的"回合进行中"那一段仍未闭环**（§5.7），且 0x80 位缺独立窗口属性佐证。
- ⇒ **默认推荐保持 `opencode-shell-web-release-nor8.apk`（3,975,389 B / `76ca064b…`）不变**。
  R8 版 `opencode-shell-web-release-r8.apk`（1,435,193 B / `0598817026…`）**已设备验证核心项**，
  可作为**体积敏感场景**（内网分发/带宽受限）的候选，但**分发前需把 §9 第 1 条闭环**。

> 若后续把常亮回合段验过、且能接受"收益在体积不在首屏"的口径，**再讨论把 R8 提为默认**。

---

## 9. 交接文档改动

`HANDOFF_WEB_SHELL.md`（仓库根）：

1. §0.1 交付物表里那行「**R8 版 | 未产出 | — | ⚠️ 构建未成功…**」**已改写**为实测值（体积/md5/已验项/未验项），并保留"默认推荐仍为非 R8"。
2. 顶部 §0 一句话**未改**（默认推荐不变，不需要动）。
3. **没有**把任何 🟡 偷偷升级成 ✅；**没有**删任何未闭环项。

其余文档**未改**（`research/HANDOFF.md`、`WEB_RELEASE_BUILD.md` 保持 E1 原样，作为历史记录）。

## 9.1 未闭环清单（F11 留下）

1. 🔴 **常亮"回合进行中 0x80 置起"段未验**（§5.7）—— 需驱动一个真实回合；本轮时间预算不够。
2. 🟡 **常亮 0x80 位缺 `dumpsys window` 独立佐证**（三版探针都失败）—— 需修好探针（`grep -E` 的 `{` 转义问题）。
3. 🟡 **通知点击回跳（风险 #11）本轮未单独触发**（非 R8 侧同样未测，不构成 R8 特有缺口）。
4. 🟡 **R8 版的口令存储/三态/崩溃无同轮非 R8 对照数**（§6 已声明边界）。
5. 🟡 首屏 n=3，−47 ms 的差值**不足以定论**，需 n≥5 复测。
6. ⚠️ 遗留事实：E1 记的「`-Pwebapp.r8=true` 开关」现已**失效（no-op）**，任何仍依赖它的脚本会**静默拿到非 R8 包**。

## 9.2 收尾复核输出（原文）

```
=== F11 closeout 2026-10-05T10:15:13 ===
OWNER_CONFIRM avd=OC_Deliver_R2 port=6290 MINE=True
PRESENT pid=…  -avd OC_Deliver_R2 -port 6290 -no-snapshot -no-boot-anim -camera-back virtualscene
PRESENT pid=…  -avd OC_Deliver_R2 -port 6290 …
WM_SIZE_RESET:
WM_DENSITY_RESET:
VERIFY_WM_SIZE: Physical size: 2560x1600          ← 已无 Override 行
VERIFY_WM_DENSITY: Physical density: 320         ← 已无 Override 行
REVERSE_RESTORE:
REVERSE_LIST: host-16 tcp:4095 tcp:4095
EMU_KILL: OK: killing emulator, bye bye | OK

=== F11 closeout RE-VERIFY (t+25s) 2026-10-05T10:15:29 ===
emulator/qemu process count = 6
OC_*/Medium_Desktop_B residual = 0                ← 我的 AVD 零残留
  REMAINING … -avd DSH_T80_Phone  -port 5570      ← 用户的，未碰
  REMAINING … -avd DSH_T80_Tablet -port 5662      ← 用户的，未碰
  REMAINING … -avd T78_Phone     -port 5660      ← 用户的，未碰
port 6290 LISTEN = 0
port 6292 LISTEN = 0
port 6294 LISTEN = 0
adb devices: emulator-5570 / emulator-5660 / emulator-5662     ← 全是用户的
backend 4095 (must be untouched, opened round as pid=3748): LISTEN pid=3748
.agent-gradle-lock exists (before release) = True
LOCK_RELEASE: Remove-Item OK
lock exists = False
```

**遵守情况**：

- ✅ **谁起谁关**：`emulator-6290`（AVD `OC_Deliver_R2`）本轮自建、**本轮已关**。
- ✅ **只关自己的**：按 AVD 名判定，`OC_*/Medium_Desktop_B` 残留 = 0；
  收尾时在跑的三台 `DSH_T80_Phone`/`DSH_T80_Tablet`/`T78_Phone` **是用户的实例，全程未碰、未 kill**。
- ✅ **未执行** `adb kill-server`；**未用** `-a`/通配符；只按 serial + 已确认 AVD 名关。
- ✅ 几何已 `reset` 并复核；`reverse` 已复原为 `tcp:4095 tcp:4095`。
- ✅ 后端 `4095` **全程未起停**（开轮 = 收尾 = `pid=3748`）。
- ✅ gradle 锁：等锁 → 写锁 → 前台跑完 → 释放（本轮 `Remove-Item` **未被拦**，释放成功）。
- **故意保留：无。**

## 9.3 我碰过哪些 serial

**只有一个：`emulator-6290`**（AVD `OC_Deliver_R2`，端口 6290，本轮自建、本轮已关）。
全程未对 `emulator-5570` / `emulator-5660` / `emulator-5662`（用户的 `DSH_*`/`T78_Phone`）执行过任何命令。

---

## 10. 证据文件索引

| 文件 | 内容 |
|---|---|
| `research/web/out_f11/build-r8.log` | 构建原文（`BUILD SUCCESSFUL` / `EXITCODE=0`） |
| `research/web/out_f11/boot.log` | 开轮：端口/进程/后端前置检查 + 指纹 + `BOOT_COMPLETED after 21 s` |
| `research/web/out_f11/dev.log` | 主验证：基线非R8 → 装R8 → 前置断言 → 首屏 → 口令存储 → 常亮 → 三态 → 桥 → 崩溃 |
| `research/web/out_f11/dev2.log` | 修正探针：DOM 口径三态 + `D1 applyKeepScreenOn` + 崩溃扫描 |
| `research/web/out_f11/dev4.log` | E1 不可达完整 logcat（含 `F5 probe unreachable`） |
| `research/web/out_f11/E1_final.png` | **不可达态截图**：「连不上服务器…」 |
| `research/web/out_f11/housekeeping.log` | 收尾复核原文 |
| `research/web/out_f11/contract-test.log` | 契约测试原文（`BUILD SUCCESSFUL` / `1 executed` / 147 tests 0 failures） |
| `research/web/_f11_dev.ps1` / `_f11_dev2.ps1` / `_f11_dev4.ps1` | 本轮三支探针脚本 |
| `webapp/build/outputs/mapping/releaseR8/mapping.txt` | 混淆证据（依赖改名 / 自家类未改名） |
| `webapp/build/outputs/mapping/releaseR8/usage.txt` | 被删 21,927 条 |
| `webapp/build/outputs/mapping/releaseR8/resources.txt` | res 收缩清单（5,811 行） |
