# WEB 壳发布工程（E1）：release 变体 + 签名产物 + R8 评估

> 任务：给 `webapp/`（WebView 宿主壳）做**可发布的签名产物**。此前只交付了 debug 变体。
> 硬期限 2026-10-07。**本轮全程未使用任何设备 / 模拟器**（另一路占用设备），**未起停后端**。
> 报告时间：2026-10-05 00:0x–00:2x (+08:00)。

---

## 0. 一句话结论

`webapp` 现在有了**对齐原生壳约定的 `release` 变体**（`sharedDebug` 签名 + `applicationIdSuffix = ".debug"`），
产出**经 `apksigner` 验证的签名 APK**（3,948,209 B）。R8 开关已实现，**但 R8 版 APK 本轮未能产出**
（原因见 §3：另一路把 `webapp` 源码留在**不可编译**状态，**不是**本次改动导致）。

**是否建议对外分发 R8 版：不建议** —— 但理由**不是**"收益小"，而是**无法在设备上验证**，且**收益恰恰很大因而诱惑更大**。
实测拆包显示本 APK **94.31% 的体积是 dex**，R8 的 headroom 很大（§4）。
正确处理是"**等有设备时验完再发**"，不是"收益不值得做"。

---

## 1. release 配置改动（唯一改动的构建文件：`webapp/build.gradle.kts`）

**只改了这一个文件**（本路专属写权限）。`webapp/src/main/AndroidManifest.xml` **未改**（不需要）。
`webapp/proguard-rules.pro` **未改**（不在本路写权限内；现有 keep 规则已覆盖桥接口，见 §5）。

```diff
         release {
-            isMinifyEnabled = false
+            // E1 发布工程：release 变体**对齐原生壳约定**（app/build.gradle.kts 的 release 块）——
+            // 同一份 `sharedDebug` 签名 + `applicationIdSuffix = ".debug"`，
+            // 于是 web 壳的 release 是个"可安装、与官方壳互不覆盖"的包，而不是只装得上的调试包。
+            //
+            // R8 是**开关式**的：默认关（release 产物与非 R8 完全一致），
+            // 要实验产物时显式 `-Pwebapp.r8=true`，`isShrinkResources` 一并跟随。
+            // 用开关而不是新建 `releaseR8` buildType，是为了不把变体集合撑大
+            // （会多出 lintVitalReleaseR8 / testReleaseR8 之类的任务），
+            // 也免得"哪份是发布件"在变体列表里出现两个候选。
+            val r8 = providers.gradleProperty("webapp.r8").orNull == "true"
+            isMinifyEnabled = r8
+            isShrinkResources = r8
             signingConfig = signingConfigs.getByName("sharedDebug")
+            applicationIdSuffix = ".debug"
             proguardFiles(
                 getDefaultProguardFile("proguard-android-optimize.txt"),
                 "proguard-rules.pro",
             )
         }
```

**三点设计说明**

1. **`applicationIdSuffix = ".debug"` 是补齐的、不是自创的**：原生壳 `app/build.gradle.kts` 的 `release` 块
   早就这么配了（"Signed with the same shared debug key as `debug`, and shipped under `<applicationId>.debug`"）。
   改之前 webapp 的 release 产物 id 会是 `com.xiufeigo.opencodeshell.web`（不带后缀），
   与 debug 的 `...web.debug` **不是同一个应用**，也就无法"覆盖安装自己"——这是与原生壳不一致的地方，现已对齐。
2. **R8 用 `-P` 开关而不是新建 `releaseR8` buildType**：新建变体会额外引入
   `lintVitalReleaseR8` / `testReleaseR8` / `assembleReleaseR8` 等任务，把"发布件"在变体列表里变成两个候选；
   开关式保证**默认的 `:webapp:assembleRelease` 永远是非 R8 的那份**（即推荐分发件），R8 只是显式实验。
3. **`isShrinkResources` 与 `isMinifyEnabled` 绑定**：为了和原生壳那次 R8 实测口径一致
   （`research/HANDOFF.md:480` 记的是 "release + R8 + **shrinkResources**"），否则体积对比不可比。

**契约测试兼容性**：`DebugEvalChannelTest` 直接读 `build.gradle.kts` 做断言，本次改动已确认不触犯：

- 没有引入 `buildConfigField("boolean", "DEBUG", ...)`（断言 1 要求不存在）；
- `release { … }` 块仍以 8 空格缩进的 `}` 收尾，正则 `\brelease\s*\{(.*?)\n        \}` 仍能匹配到；
- 块内**没有** `isDebuggable = true`（断言 2 要求不存在）；
- 源码侧 `if (!BuildConfig.DEBUG) return` 未动（断言 3）。
- 该测试的 2 个用例在本次回归中**真跑通过**（见 §6），非 UP-TO-DATE 跳过。

---

## 2. 产物一：签名 release（**非 R8**）✅ 已交付

### 2.1 命令与 `BUILD SUCCESSFUL` 原文

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :webapp:testDebugUnitTest :webapp:assembleDebug :webapp:assembleRelease :app:testDebugUnitTest --console=plain --no-daemon
```

日志原文结尾（`research/web/_build-e1-release-nor8.log`）：

```
> Task :webapp:lintVitalReportRelease
> Task :webapp:lintVitalRelease
> Task :webapp:assembleRelease

BUILD SUCCESSFUL in 47s
140 actionable tasks: 47 executed, 93 up-to-date
EXITCODE=0
```

### 2.2 产物指纹

| 项 | 值 |
|---|---|
| **交付路径** | `research/deliver/opencode-shell-web-release-nor8.apk` |
| 构建输出路径 | `webapp/build/outputs/apk/release/webapp-release.apk` |
| **体积** | **3,948,209 B** |
| **MD5** | **9ab8ee300027f9847c2b220bb26f2b98** |

### 2.3 `aapt2 dump badging`（`package:` 行原文）

```
package: name='com.xiufeigo.opencodeshell.web.debug' versionCode='1' versionName='0.1.0-webhost' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
targetSdkVersion:'35'
application-label:'OpenCode Web'
```

⇒ `.debug` 后缀**已生效**，id 为 `com.xiufeigo.opencodeshell.web.debug`，与原生壳 release 约定一致。

### 2.4 `apksigner verify --print-certs`（**证明是真签名包**）

```
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: e86b70066546bd0bdc2e6cb4d970704bc48b1a244af59c664564f030cca0510a
Signer #1 certificate SHA-1 digest: 426b682d492e08b8b5cff4a57bac2bef4070f18d
Signer #1 certificate MD5 digest: a8a01dcb84f4932ae6a181cf2524afbd
Signer #1 key algorithm: RSA
Signer #1 key size (bits): 2048
Signer #1 public key SHA-256 digest: 64cc7c4dcbadbd2bda19d3b80edc085ac18f829aaa1b18fb9095e1de021e8af0
Signer #1 public key SHA-1 digest: daad64a253a93206c4a18cbd62a561612ab5200c
Signer #1 public key MD5 digest: 2362f8eeb5360c10c38ba099ac924781
```

- `Verifies` + v2 scheme = **签名校验通过**，**是签名包**。
- 证书 DN `CN=Android Debug`、SHA-256 `e86b7006…` 即 `sharedDebug` keystore
  （口令 `android` / alias `androiddebugkey`），与 `app` 的 release 同一把钥匙。
- **注意 v1 = false**：minSdk 26 下 AGP 只打 v2，这是正常的，不影响安装。

---

## 3. 产物二：签名 release（**R8 版**）—— ⚠️ **本轮未产出**

### 3.1 状态

**R8 版 APK 没有产出。** 开关本身是通的（`-Pwebapp.r8=true` 能被 Gradle 正确接收；
第一次失败是 PowerShell 把**未加引号**的 `-Pwebapp.r8=true` 拆成了任务名，加引号后参数解析正常），
**真正的阻塞点是 `webapp` 模块的 Kotlin 源码当前不可编译**。

### 3.2 阻塞原因（**不是**本次改动导致）

`:webapp:compileReleaseKotlin` 失败，报错全部落在
`webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt`：

```
e: .../WebHostActivity.kt:100:25  Unresolved reference 'webViewMajor'.
e: .../WebHostActivity.kt:101:31  Unresolved reference 'webViewVersionName'.
e: .../WebHostActivity.kt:809:18  Unresolved reference 'watchdogArmed'.
e: .../WebHostActivity.kt:810:13  Unresolved reference 'showDiagnostic'.
e: .../WebHostActivity.kt:816:81  Unresolved reference 'WebViewRenderProcessDetail'.
e: .../WebHostActivity.kt:818:52  Unresolved reference 'didCrash'.
e: .../WebHostActivity.kt:820:17  Unresolved reference 'watchdogArmed'.
```

**归属判定（关键）**：

- 本次改动**只碰了 `webapp/build.gradle.kts` 的 `release` 块**，**一行 `.kt` 都没动**；
- **同一个文件在 00:01 那次构建里编译通过**（只带 3 条 deprecation 警告，见 §2.1 日志），
  00:09 起才失败 ⇒ **是 00:01 之后**有人改坏的；
- `git status webapp/src` 显示该文件属**另一路**的在途改动
  （`WebHostActivity.kt`、`host/WebHostBridge.kt`、`assets/www/oc-host.js`
  + 新增 `oc-preflight.js`、`WebViewPreflight.kt`，正在加一个 "E3.g1 first-content 预检探针"）；
- 文件 mtime 停在 `00:03:25` 后**持续不变**（连续采样确认），
  且**同一组 7 个错误**在 00:03 / 00:04 / 00:06 / 00:07 / 00:09 多次重试中**完全一致**
  ⇒ 该文件被**停在了一个不可编译的中间态**。

`webapp/src/**` **明确不在本路写权限内**，所以本路**没有去"顺手修好"别人的半成品**
（那会覆盖对方在途工作，且属于越权改动）。

**本路的处理**：退避重试（累计 **11 次编译尝试**全部失败，日志见 `research/web/_build-e1-release-r8.log`），
期间把非 R8 交付物、回归、文档全部做完；**不伪造 R8 产物**。

### 3.3 解除阻塞需要什么

**只需另一路把 `WebHostActivity.kt` 的 7 个 `Unresolved reference` 修好**
（`webViewMajor` / `webViewVersionName` / `watchdogArmed` / `showDiagnostic` /
`WebViewRenderProcessDetail` / `didCrash`），然后本路一条命令即可产出：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :webapp:assembleRelease '-Pwebapp.r8=true'
# 产物：webapp/build/outputs/apk/release/webapp-release.apk
```

> ⚠️ PowerShell 里 `-Pwebapp.r8=true` **必须加引号**（不加会被拆成 `-Pwebapp` + `.r8=true`，
> 报 `Task '.r8=true' not found`）。

---

## 4. R8 前后体积对比

### 4.1 实测：非 R8 产物的体积构成

对 `opencode-shell-web-release-nor8.apk` 逐条目统计（**未压缩**字节）：

| 条目组 | 字节 | 占比 | R8 能否影响 |
|---|---:|---:|---|
| `classes.dex` | 8,745,636 | **84.98%** | ✅ **主战场**（`isMinifyEnabled`） |
| `classes2.dex` | 960,316 | **9.33%** | ✅ **主战场** |
| `resources.arsc` | 248,924 | 2.42% | ⚠️ 部分（`shrinkResources`） |
| `res/*` | 226,753 | 2.20% | ⚠️ 部分（`shrinkResources`） |
| `okhttp3/*` | 41,612 | 0.40% | ❌ META-INF 依赖 jar，不动 |
| `kotlin/*` | 29,945 | 0.29% | ❌ 同上 |
| `assets/www/*` | 25,366 | 0.25% | ❌ **R8 不处理 assets** |
| `AndroidManifest.xml` | 6,060 | 0.06% | ❌ |
| `assets/dexopt/*` | 2,782 | 0.03% | ❌ |
| 其它（META-INF 等） | ~3,500 | 0.03% | ❌ |
| **未压缩合计** | **10,290,893** | 100% | — |
| **APK 落盘大小（deflate 后）** | **3,948,209** | — | — |

### 4.2 三个版本的对比

| 版本 | 体积 | MD5 | 状态 |
|---|---:|---|---|
| web debug（现状基线） | 8,636,901 B | `8dcffc572ad469f472f61594f12ea990` | 已有，本轮未动 |
| **web release 非 R8** | **3,948,209 B** | `9ab8ee300027f9847c2b220bb26f2b98` | ✅ **本轮产出** |
| web release + R8 | **—** | — | ❌ **未产出**（§3.2 阻塞） |
| *（参考）原生壳 release + R8 + shrinkResources* | *17,809,425 B（−59.71%）* | — | `research/HANDOFF.md:480` |

> ⚠️ **本报告不提供 R8 版的实测体积数字。** 没有产物就没有数字；
> **任何"R8 能省 X%"的估算都不能替代实测**，故此处**留空**而不是填一个猜测值。

### 4.3 一处需要纠正的直觉（**本报告自身的修正**）

初稿曾假设"APK 大头是托管的官方前端资产、R8 动不了"—— **这个假设是错的**，
§4.1 的拆包数据推翻了它：`assets/www/*` 只有 **25,366 B（0.25%）**，而 **dex 占 94.31%**。

**为什么 release 比 debug 小 4.7 MB**：主要**不是** R8 的功劳（release 根本没开 R8），
而是 release 少了调试信息/调试依赖，且 AGP 对 release 跑了 `optimizeReleaseResources`、
不产出 `debuggable` 相关元数据。

**因此 R8 在本项目上的 headroom 很大**（理论上可触及 94.31% 的 payload），
**不能**用"收益小"来劝退 R8 版。真正的风险是 §5 那些"**裁错了只有跑起来才知道**"的点。
原生壳的 −59.71% **不可直接外推**到 web 壳（两者 dex 组成不同）。

---

## 5. R8 风险清单与 keep 规则

### 5.1 结论先行

**建议：默认不对外分发 R8 版**，对外分发 **非 R8 的 `opencode-shell-web-release-nor8.apk`**。三条理由：

1. **无法证明 R8 版能跑**（**红线**）：本轮**没有设备**，R8 版**未在设备上验证**。
   WebView 壳的典型失败模式是"**装得上、启动即白屏 / 桥调不通**"，
   静态检查与 JVM 单测**都测不出来**——§6 的 26 个 webapp 用例**全是源码/资产字符串断言**，
   **不加载 APK、不起 WebView** ⇒ 单测全绿**不能**为 R8 版背书。
2. **收益大 ⇒ 风险暴露面也大**：dex 占 94.31%（§4.1），R8 一旦开，缩的主要是
   okhttp / kotlin / coroutines / **Tink** 这些**依赖**代码。
   依赖侧恰恰是"反射装配 + 靠 consumer proguard 规则保命"的重灾区（尤其 Tink，见 #7）。
   而**缩自己那 11 个 Kotlin 文件**（已被整包 `-keep` 兜住）几乎没有收益——
   **付风险的部位和能拿收益的部位并不重合**。
3. **产物本身还没出来**（§3.2）：源码不可编译，连"能不能构建成功"都还没证明。

### 5.2 逐项风险清单（**纯静态评估**）

| # | 风险点 | 会被 R8 裁掉吗 | 依据 | 结论 |
|---|---|---|---|---|
| 1 | `WebHostBridge` 的 **17 个 `@JavascriptInterface` 方法** | **不会** | `proguard-rules.pro` 已有两条：`@android.webkit.JavascriptInterface <methods>` 精确 keep，**外加**整包 `-keep { *; }` 兜底 | ✅ 安全（双保险） |
| 2 | `WebHostActivity` / `WebHostApp` 被 manifest 引用 | **不会** | AGP 对 manifest 声明的组件**自动 keep** | ✅ 安全 |
| 3 | `assets/www/oc-host.js` / `oc-perf.js` / `oc-preflight.js` 经 `WebViewCompat.addDocumentStartJavaScript` 注入 | **不会** | 注入的是**字符串**，`assets.open()` 读**资产文件**；R8 / shrinkResources **不处理 `assets/`**（只处理 `dex/` 与 `res/`） | ✅ 安全 |
| 4 | `WebHostBridge.INJECT_NAME`（`addJavascriptInterface` 用的名字） | **不会** | 常量与方法名均被 `-keep` 保住；**WebView 侧按方法名反射查找**，故方法名**必须**保留 | ✅ 安全（但**全靠 §5.3 规则**） |
| 5 | `FileChooserHost` / `LoopbackProxy` / `SecretStore` / `InsetsHost` / `NotifierHost` / `PerfMarks` 等 | **不会** | 同包 `-keep { *; }` | ✅ 安全 |
| 6 | `androidx.webkit`（`WebViewAssetLoader`、`WebViewCompat`、`WebViewFeature`） | 低风险 | 该库**自带 consumer proguard 规则**；`isFeatureSupported` 是**常规调用**非反射 | ⚠️ 低 |
| 7 | `androidx.security:security-crypto` + **Tink**（`EncryptedSharedPreferences`） | **⚠️ 中等偏高** | Tink 内部**基于反射装配** keyset / AEAD primitive；consumer 规则覆盖主路径，但 **Tink + R8 是需要 keep 兜底的经典组合**。一旦裁坏 ⇒ `SecretStore` **静默 fallback** 到内存（`SecretStore.kt:76` 的 `Log.w` 分支）⇒ **口令不落盘、首启反复要密码**，是**静默降级**而不是崩溃 | ⚠️ **必须实机验** |
| 8 | `okhttp`（`LoopbackProxy` 的上游客户端） | 低风险 | 自带 consumer 规则；本项目只做常规 HTTP | ⚠️ 低 |
| 9 | `kotlinx-coroutines` | 低风险 | 自带 consumer 规则（debug agent 被剥掉，不影响功能） | ⚠️ 低 |
| 10 | `res/` 收缩（`isShrinkResources = true`） | **⚠️ 中等** | 官方 UI 是**页面里的 HTML/CSS**，不引用本 APK 的 `R.string`；`@string/app_name`、`@style/Theme.OcWebHost`、`@mipmap/ic_launcher_web` 均被 manifest 引用（**保留**）。但**未在设备上验证** | ⚠️ **必须实机验** |
| 11 | `::class.java` 取 `NotificationManager` / `WebHostActivity` | **不会** | 常规 Kotlin 引用，编译期已知 | ✅ 安全 |
| 12 | Kotlin `data class` / 序列化 | **不适用** | 本模块**无** `@Serializable`、无 `Gson`/`Moshi`、无 `readResolve` | ✅ 安全 |

> 以上 12 项全部来自**读源码 + 读 keep 规则 + 查依赖库自带 consumer rules** 的**静态判断**，
> **没有一项在设备上验证过**。

### 5.3 建议的 keep 规则（**若将来真的要发 R8 版**）

现有 `webapp/proguard-rules.pro` 已覆盖 #1/#2/#4/#5。**建议追加/替换**如下
（⚠️ **本轮未写入**：该文件**不在本路写权限内**，需有权限的一路落盘）：

```proguard
# —— ① Tink / EncryptedSharedPreferences：反射装配 keyset 与 AEAD primitive（R8 下的经典炸点）——
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**

# —— ② OkHttp：可选的平台 provider（Conscrypt / BouncyCastle / OpenJSSE）缺失时不要炸 ——
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# —— ③ WebView 桥：@JavascriptInterface 精确保留（WebView 按方法名反射）——
-keepclassmembers class com.xiufeigo.opencodeshell.web.host.WebHostBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# —— ④ 别用整包 -keep 兜底：它会吃掉 R8 的大部分收益 ——
#    ⚠️ 现有的 `-keep class com.xiufeigo.opencodeshell.web.** { *; }` 是"最安全但零收益"的写法。
#    真正要发 R8 版时，应把 ③④ 换成按类精确 keep，让占 94% 的依赖侧 dex 能被缩。
```

**建议的实机验收清单**（R8 版要发之前必须逐条过，**本轮一条都没做**）：

1. 冷启动 ⇒ 首屏渲染完成（无白屏），logcat 无 `RenderProcessGone`；
2. 桥 8 类逐个触发（通知 / 返回 / 文件选择保存 / 分享 / 凭据 / IME+insets / 屏幕常亮 / 主题）——
   对照 `research/web/BRIDGE_ACCEPTANCE.md` 的 7 PASS + 1 带保留基线；
3. **杀进程重开** ⇒ 口令**能从 `EncryptedSharedPreferences` 读回**（验证 Tink 没被裁坏，
   这是最容易被 R8 静默打断的一条）；
4. 首启配置框（`SetupDialog`）文案与触控目标正常（`res/` 没被过度收缩）；
5. IME 抬升修法仍生效（`oc-host.js` 注入 + insets 链路）。

### 5.4 关于"未在设备验证"的明确声明（**红线**）

> **R8 版产物未在任何设备 / 模拟器上运行过。本轮全程无设备，也未起停后端。**
>
> 更进一步：**R8 版连"构建成功"都还没证明**（§3.2 源码不可编译）。
>
> 本报告中关于 R8 的一切表述均为**静态分析**（源码 + keep 规则 + 依赖库 consumer rules + APK 拆包），
> **不构成、也不得被解读为"已验证可运行"**。§6 的回归单测**不覆盖** R8 版的运行时行为。

---

## 6. 回归结果（**0 失败，用例数只增不减**）

命令（与 §2.1 同一次前台执行，`BUILD SUCCESSFUL`，`EXITCODE=0`）：

```powershell
.\gradlew.bat :webapp:testDebugUnitTest :webapp:assembleDebug :webapp:assembleRelease :app:testDebugUnitTest --console=plain --no-daemon
```

| 任务 | 用例数 | 失败 | 错误 | 跳过 | 判定 |
|---|---|---|---|---|---|
| `:webapp:testDebugUnitTest` | **26**（5 suites） | **0** | 0 | 0 | ✅ 真跑 |
| `:app:testDebugUnitTest` | **6136**（677 suites） | **0** | 0 | — | ✅ UP-TO-DATE（输入未变） |
| `:webapp:assembleDebug` | — | — | — | — | ✅ UP-TO-DATE（产物已存在且输入未变） |
| `:webapp:assembleRelease` | — | — | — | — | ✅ **真实执行**，产出签名 APK |

webapp 逐 suite：

| suite | tests | failures | errors |
|---|---:|---:|---:|
| `DebugEvalChannelTest` | 2 | 0 | 0 |
| `FallbackPageTouchTargetTest` | 2 | 0 | 0 |
| `host.ImeOcclusionTest` | 8 | 0 | 0 |
| `InputPathHotspotTest` | 6 | 0 | 0 |
| `SetupDialogTouchTargetTest` | 8 | 0 | 0 |
| **合计** | **26** | **0** | **0** |

**用例数只增不减**：改动前基线 = webapp **26** / app **6136**
（取自改动前 `webapp/build/test-results/testDebugUnitTest/*.xml` 与
`app/build/test-results/testDebugUnitTest/*.xml`），改动后 = webapp **26** / app **6136**
⇒ **持平，无删除、无跳过**。app 侧 6136 与 `research/HANDOFF.md` 记录的闸门数
（677 suites / 6136 tests / 0 failures）一致。

**未放宽任何断言、未删除任何测试**：本轮只改了 `webapp/build.gradle.kts` 的 `release` 块，
**没有触碰任何 `.kt` 源码或测试文件**（`git status` 中 `webapp/src/**` 的改动全部来自另一路，见 §9）。

---

## 7. 交付物清单

`research/deliver/` 下的 web 壳产物：

| # | 文件 | 体积 | MD5 | 签名 | 用途 |
|---|---|---:|---|---|---|
| 1 | `opencode-shell-web.apk`（**原有，未改动**） | 8,636,901 B | `8dcffc572ad469f472f61594f12ea990` | debug 签名 | 现状基线；A/B 对比老包时用 |
| 2 | **`opencode-shell-web-release-nor8.apk`** | **3,948,209 B** | `9ab8ee300027f9847c2b220bb26f2b98` | `sharedDebug`（v2 ✅） | ✅ **推荐对外分发** |
| 3 | `opencode-shell-web-release-r8.apk` | **—** | **—** | — | ❌ **未产出**（§3.2 阻塞） |

（`opencode-shell-debug.apk` / `opencode-shell-release-debugsigned.apk` 是**原生壳**产物，本轮未触碰。）

> ✅ **推荐对外分发 = 第 2 份（非 R8 签名 release）**，理由见 §5.1。

---

## 8. 未闭环 / 风险

| # | 事项 | 状态 |
|---|---|---|
| 1 | **R8 版 APK 未产出** | ❌ **未闭环**：被另一路的不可编译源码阻塞（§3.2）。解除条件与命令见 §3.3 |
| 2 | **R8 版未在设备上验证** | ❌ **未闭环**（本轮无设备）。**红线级诚实标注**，见 §5.4 |
| 3 | release 变体**未在真机安装启动过** | ❌ 未闭环。§2 的证据是**签名 / 包名 / 构建成功**级别的，**不等于"装得上、跑得起来"** |
| 4 | 建议的 R8 keep 规则**未写入** | ⚠️ `webapp/proguard-rules.pro` 不在本路写权限；规则文本已在 §5.3 给出 |
| 5 | 非 R8 产物的构建时点 | ⚠️ 该 APK 构建自 **2026-10-05 00:01** 时刻的源码；此后另一路改动了 `webapp/src/**`（§9），**该产物不含**那些后续改动 |
| 6 | 与原生壳 R8 实测的可比性 | ⚠️ 原生壳 −59.71% 的收益来自**它自己的** dex 组成；web 壳 dex 占比 94.31%，**比例不可直接外推** |
| 7 | v1 签名缺失 | ℹ️ 非问题：minSdk 26，只需 v2 |

---

## 9. 与另一路的并发冲突（如实记录）

R8 构建自 **00:03** 起持续失败，原因**不是**本次改动，而是**另一路正在编辑**
`webapp/src/main/java/.../WebHostActivity.kt`（mtime `00:03:25` 后停止变化），
文件被停在**不可编译**状态（7 个 `Unresolved reference`，见 §3.2）。

- 本路**未修改** `webapp/src/**` 任何文件（明确不在本路写权限内），
  **也没有**去"顺手修好"别人的半成品。
- 处理方式：**退避重试**（累计 11 次编译尝试），同时把非 R8 交付物、回归、文档全部做完。
- 当时 `git status webapp/src` 显示另一路在改：
  `WebHostActivity.kt`、`host/WebHostBridge.kt`、`assets/www/oc-host.js`
  （+ 新增 `oc-preflight.js`、`WebViewPreflight.kt`）。
  ⇒ **这些都不在本报告验收范围内**；§6 的单测数对应的是 00:01 时刻的源码状态。
- ⚠️ **交接提醒**：另一路完工后请确认 `WebHostActivity.kt` 能编译，
  否则**连 debug 包也会构建不出来**（不只是 R8）。

---

## 10. 原始日志

| 文件 | 内容 |
|---|---|
| `research/web/_build-e1-release-nor8.log` | 非 R8 全量回归（`BUILD SUCCESSFUL in 47s`） |
| `research/web/_build-e1-release-r8.log` | R8 14 次退避重试记录（含每次的 `compileReleaseKotlin FAILED`） |
