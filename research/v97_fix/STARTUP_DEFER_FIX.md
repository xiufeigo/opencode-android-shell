# v97 ④-b — 首帧前同步初始化：定位、搬移与证据

- 角色：修复工程师（④-b），把首帧前的同步初始化搬出首帧路径
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 依据：`research/v97_fix/COLD_START_PERF.md`（④ 的结论 #3：「首帧前在主线程同步构造 7 个对象」）
- 本轮**无设备**：所有"首帧代价"仍是**静态判读**，本文件里没有任何实测毫秒数

---

## 0. 结论先说

1. **报告点的 7 处，实际是 8 个 `remember{}`**（报告把 `LastRouteStore`/`AppearancePrefs` 并成了一组）。我按 8 个逐条量化。
2. **报告的两条前提经核对是错的**，这直接改变了"该搬谁"的答案：
   - **`SystemNotifier` 构造期不碰 `NotificationManager`**。`ensureChannel()` 只在 `post()` 内被调用（`SystemNotifier.kt:353`），即**每次投递时**才建 channel。构造函数只做 `Handler(主 Looper)` + 3 个 `ConcurrentHashMap` + `ForegroundTracker` + `registerActivityLifecycleCallbacks` + 一行 `Log.d`（`SystemNotifier.kt:213-228`）。报告点名的最大嫌疑之一**根本不在首帧路径上**。
   - **`NotificationPrefs` 构造期只是拿句柄**。`getSharedPreferences` 立即返回，`SharedPreferencesImpl` 在后台线程加载文件，阻塞发生在**第一次读**，而第一次读在 `AppNotifier` 侧、不在构造期。
3. **真正重量级且首帧真的要用的只有 `ServerConfigStore`（Keystore + 加密 prefs + JSON）和 `SettingsStore`（首载读盘 + `LocaleManager`，且 `applyLanguage` 可能 `setApplicationLocales` 重建 Activity）——这两处不能安全搬**（搬了会改变首帧内容，等于引入闪屏，即 v65/v97 N-1 那类回归）。
4. **实际可安全搬的只有 1 处**：`NetworkRecoveryWatch` 的 `start()`——它是一次 **connectivity 服务的 binder 调用**（`registerDefaultNetworkCallback`），却挂在一个 `DisposableEffect` 上，而 `DisposableEffect` 的 block **在首帧绘制前就被 apply**。已搬，并补了 7 个 JVM pin。
5. **闸门：105 用例 / 0 失败**，且做了**反向对照**（把修复改回去，恰好 2 个结构 pin 失败），证明 pin 不是空转。

---

## 1. 首帧路径清单（8 项，逐条量化）

全部位于 `OcActivity.setContent { … }`（`app/src/main/java/com/xiufeigo/opencodeshell/OcActivity.kt`）。
行号为**本轮改动后**的当前行号。

| # | 位置 | 代码（原文） | 重活是什么 | 代价 | 依据 | 处置 |
|---|---|---|---|---|---|---|
| 1 | `OcActivity.kt:74` | `val serverStore = remember { ServerConfigStore(applicationContext).also { it.restore() } }` | **Keystore binder**（`MasterKey.Builder().build()`）+ `EncryptedSharedPreferences.create`（整文件读盘 + 逐条 AES 解密）+ `restore()` 两次 `getString` + JSON 解析 | **高** | `ServerConfigStore.kt:89` → `:318-337`（`MasterKey` / `EncryptedSharedPreferences.create`），`:274-280`（`restore`） | **不搬**（见 §3.1） |
| 2 | `OcActivity.kt:77` | `val settingsStore = remember { SettingsStore(applicationContext) }` | `getSharedPreferences` **首载**（读盘 + XML 解析）+ `read()` 读约 20 个键 + `init { applyLanguage() }` → `getSystemService(LocaleManager)`，API33+ 还可能 `setApplicationLocales` **重建 Activity** | **高** | `SettingsStore.kt:35`、`:37`、`:40-42`、`:65-69` | **不搬**（见 §3.2） |
| 3 | `OcActivity.kt:82` | `val notificationPrefs = remember { NotificationPrefs(applicationContext) }` | 构造期只拿 prefs 句柄（后台加载）；真正的读发生在别处 | **中** | `NotificationPrefs.kt:22` | **不搬**（是 #5 的构造参数） |
| 4 | `OcActivity.kt:83-93` | `val notifications = remember { SystemNotifier(...).also { systemNotifier = it } }` | `Handler(主 Looper)` + 3×`ConcurrentHashMap` + `ForegroundTracker` + `registerActivityLifecycleCallbacks` + `Log.d`。**无 `NotificationManager`、无磁盘** | **低** | `SystemNotifier.kt:113-155`、`:213-228`；`ensureChannel()` 仅在 `post()` 内 → `SystemNotifier.kt:353` | **不搬**（见 §3.3） |
| 5 | `OcActivity.kt:94` | `val chatViewModel = remember { ChatViewModel(...) }` | 493 方法类的**类加载/校验**（属报告 #1「无 AOT ⇒ JIT + 首次类验证」那条）+ `init` 建 `AppNotifier`、恢复模型选择 | **高**（但不是"可搬"的那类） | `ChatViewModel.kt:754-758`、`:850` | **不搬**（首帧要） |
| 6 | `OcActivity.kt:128-137` | `LaunchedEffect(applicationContext, chatViewModel) { … networkWatch.start() … }`（**本轮改动**；原为 `:110` 的 `remember` + `DisposableEffect(networkWatch) { start() }`） | `getSystemService(ConnectivityManager)` + **`registerDefaultNetworkCallback` = 一次 connectivity 服务 binder 调用** | **中** | `NetworkRecoveryWatch.kt:16-17`、`:31-38` | **✅ 已搬** |
| 7 | `OcActivity.kt:149-150` | `val lastRoute = remember { LastRouteStore(applicationContext) }` / `val appearance = remember { AppearancePrefs(applicationContext) }` | 两个 prefs **句柄**（`AppearancePrefs` 与 `SettingsStore` **同一个文件** ⇒ 进程内已缓存，无二次读盘）；`appearance.bottomTabStrip()` 在首帧被同步读一次 | **低** | `LastRouteStore.kt:24`；`AppearancePrefs.kt:38-39`（复用 `SettingsStore.PREFS_NAME`）；真正的 `load()` 早已在 `App.kt:796` 的 `withContext(Dispatchers.IO)` 里 | **不搬**（见 §3.4） |
| 8 | `OcActivity.kt:151` | `val serverKey = remember(serverStore.server) { NavRestore.serverKeyOf(serverStore.server?.url) }` | 纯函数（`NavRestore.kt:184-188`），无 IO | **低** | — | 不动 |

> 报告说 7 个，是因为把 7、8 并成了一组（`LastRouteStore`/`AppearancePrefs`）。实际 `remember{}` 共 8 个，如上。

---

## 2. 改了什么

只改了 **1 个文件 + 1 个新测试文件**：

- `app/src/main/java/com/xiufeigo/opencodeshell/OcActivity.kt`（唯一被改的产品代码）
- `app/src/test/java/com/xiufeigo/opencodeshell/StartupDeferV97Test.kt`（新增）

`MainActivity.kt` / `ui/App.kt` **未触碰**，`navRestoreSettled` 相关代码原样未动，`ui/home|servers|session|terminal/**` 与 `ui/nav/BottomTabStrip.kt` 未触碰。

### diff

```diff
--- a/app/src/main/java/com/xiufeigo/opencodeshell/OcActivity.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/OcActivity.kt
@@ imports @@
 import com.xiufeigo.opencodeshell.ui.theme.OcSystemBars
+import kotlinx.coroutines.awaitCancellation

@@ setContent { … } @@
-            val networkWatch = remember(applicationContext, chatViewModel) {
-                NetworkRecoveryWatch(applicationContext) { chatViewModel.onNetworkAvailable() }
-            }
-            DisposableEffect(networkWatch) {
-                networkWatch.start()
-                onDispose { networkWatch.stop() }
-            }
+            // （v97 ④-b 说明注释，14 行：为什么原路径在首帧前、为什么
+            //   LaunchedEffect 的 body 是在首帧之后才被 dispatch）
+            LaunchedEffect(applicationContext, chatViewModel) {
+                val networkWatch =
+                    NetworkRecoveryWatch(applicationContext) { chatViewModel.onNetworkAvailable() }
+                networkWatch.start()
+                try {
+                    awaitCancellation()
+                } finally {
+                    networkWatch.stop()
+                }
+            }
```

**没有新增任何用户可见字符串，没有降低任何既有断言强度，没有改任何运行语义**——只改了 `start()` 的**触发时机**。

---

## 3. 为什么语义等价（逐条论证）

### 3.0 通用机制

Compose 里三者时序不同，这是本次改动的全部依据：

| 结构 | 何时执行 | 相对首帧 |
|---|---|---|
| `remember { … }` 初始化块 | 组合期（composition） | **首帧之前** |
| `DisposableEffect { … }` 的 block | apply 阶段 | **首帧之前**（apply 完才 draw） |
| `LaunchedEffect { … }` 的 body | 组合 apply **之后**才被 dispatch | **首帧之后** |

所以把 `start()` 从 `DisposableEffect` 挪进 `LaunchedEffect`，就是把它从"首帧前"挪到"首帧后"，**函数、参数、调用次数全都不变**。

### 3.1 `ServerConfigStore`（#1，高）为什么**不能**搬

首帧真的要用它：`serverStore.server?.url` 在 `:151-153` 当场算出 `serverKey`，而 `serverKey` 是 `OcApp` 的 nav graph 入参。若改成"先给默认值、加载后更新"，首帧的 `serverKey` 会从真实值变成 `NavRestore.DEFAULT_SERVER_KEY`，**nav 路由的 session 段 key 就错了**——这正是 v97 N-1（`LastRouteRestoreV97Test` 钉的那类）已经修过一次的东西，搬它等于把那个 bug 重新引进来。故不动。

### 3.2 `SettingsStore`（#2，高）为什么**不能**搬

两点：
1. `state` 在第一次组合就被 `SystemBarIconAppearance`（`:144`）和 `OcApp` 主题消费。搬 ⇒ 首帧先用默认主题渲染再切 ⇒ **可见的深/浅色闪一下**，这就是"改变用户可见行为"。
2. `init { applyLanguage(...) }`（`SettingsStore.kt:40-42`）在 API33+ 会 `setApplicationLocales`，**框架会据此重建 Activity**。把一个"会重建 Activity"的调用推迟到首帧之后，语义从"启动时定语言"变成"首帧后定语言"，是实质行为变更。

故不动。

### 3.3 `SystemNotifier`（#4）为什么**不能**搬（且报告前提有误）

报告说它在首帧路径上建 channel / 拿 `NotificationManager`。核对结果：**不成立**。`ensureChannel()` 的唯一调用点是 `post()`（`SystemNotifier.kt:353`），即每次真正投递时。所以：
- 首帧路径上**本来就没有 `NotificationManager` 调用**，无物可搬；
- 构造期剩下的只是 Handler + 几个 map + 生命周期注册，代价低；
- 而且它是 `ChatViewModel` 的构造参数（`notificationDelivery`），搬它就得改 `ChatViewModel` 的注入形态——超出我的写权限，且会让"投递前 notifier 还不存在"产生**丢通知**的竞态。

故不动。

### 3.4 `LastRouteStore` / `AppearancePrefs`（#7，低）为什么**不能**搬

- `LastRouteStore` 的**真正读盘 `load()` 早已不在首帧前**：`App.kt:796` 是 `withContext(Dispatchers.IO) { lastRoute?.load() }`（v64 的修复）。这里只构造句柄。
- `AppearancePrefs` 与 `SettingsStore` 共用 `SettingsStore.PREFS_NAME`（`AppearancePrefs.kt:38-39`），进程内拿到的是**同一个已加载的 SharedPreferences 实例**，等于一次 map 查找，不是二次读盘。
- `bottomTabStrip` 的值首帧就要用（`OcApp` 入参），搬它=布局闪一下。

故不动。

### 3.5 `NetworkRecoveryWatch`（#6）为什么搬得动、且不丢 heal

- **搬得动**：网络断流恢复这件事，在冷启动第一帧**不可能需要**——`ChatViewModel` 的首次拉取走自己的 `viewModelScope`，不以这个 watch 为前提。把 binder 调用挪后不影响任何首帧内容（首帧不消费它的任何值）。
- **不丢 heal**：`ConnectivityManager.registerDefaultNetworkCallback` 在注册成功后会**立即对当前活动网络回调一次 `onAvailable`**。所以"启动时网络是好的"这个初始状态依然会被观察到，只是晚一帧。
- **不引入竞态**：effect 的 key 是 `applicationContext` 与 `chatViewModel` 两个**稳定值**，重组不会重启它（若 key 不稳，每次重组都会 cancel+重注册，那才是行为变更）。
- **不泄漏、且比原写法更严**：原写法里 `onDispose { stop() }` 可能对一个 `start()` 从未执行过的 watch 调用 `stop()`（靠 `NetworkRecoveryWatch` 内的 `AtomicBoolean` CAS 兜住、不报错但语义模糊）。新写法 `awaitCancellation()` + `finally { stop() }` 让注册与释放**必定成对且各一次**。
- **通知时序不变**：`SystemNotifier` / `NotificationPrefs` 完全未动，channel 仍按投递创建。
- **埋点不变**：没有增删或移动任何 `Log` 语句；`ChatViewModel.onNetworkAvailable` 内部埋点原样。

---

## 4. 测试证据

### 4.1 新增 pin：`StartupDeferV97Test`（7 例，纯 JVM）

钉两半：

**（a）"被搬走的"那一半** — 结构语义"首帧路径上不再有同步 binder 注册"：
- `the first-frame path no longer registers a network callback`：断言 `setContent` 体内不再出现 `remember(applicationContext, chatViewModel) {` 与 `DisposableEffect(networkWatch)`，并用正则 `remember[^}]*\{[^}]*\.start\(\)` 兜住"任何 `remember{}` 初始化块里调 `start()`"。
- `the watch is reached only from a LaunchedEffect that owns its teardown`：断言 `LaunchedEffect(applicationContext, chatViewModel)` + `start()` + `awaitCancellation()` + `stop()`，且 key 稳定。
- `the heal still forwards to the ViewModel`：断言回调体仍是 `chatViewModel.onNetworkAvailable()`（防止"搬走时把语义丢了"）。

**（b）"被搬走的初始化最终仍会执行一次"** — 纯协程生命周期回放（fake 计数 register/unregister）：
- `the deferred start runs exactly once and stops exactly once`
- `a recomposition does not multiply registrations`
- `the moved init is still reached after the first frame`
- `the coalescing rule that dedupes the heal is unchanged`（钉 `NetworkRecoveryWatch.COALESCE_MS == 2000L`，证明去抖规则没被顺手改掉）

### 4.2 回归结果

命令（抢锁 → 跑 → 删锁，锁协议已遵守）：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --console=plain `
  --tests StartupDeferV97Test --tests LastRouteRestoreV97Test `
  --tests ColdStartV64Test --tests BootPathTest --tests HomeColdStartTest `
  --tests ShellWiringTest --tests Wave5IntegrationWiringTest `
  --tests NotificationSystemTest --tests NotificationWithheldOnBackgroundV63Test `
  --tests StringsLocaleParityTest
```

原文（`research/v97_fix/mcode_startup_defer.log`）：

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 19s
52 actionable tasks: 5 executed, 47 up-to-date
```

JUnit XML 逐类统计（`app/build/test-results/testDebugUnitTest`）：

| 测试类 | 用例数 | 失败 | 错误 |
|---|---:|---:|---:|
| `StartupDeferV97Test`（新增） | 7 | 0 | 0 |
| `LastRouteRestoreV97Test` | 15 | 0 | 0 |
| `ColdStartV64Test` | 6 | 0 | 0 |
| `BootPathTest` | 2 | 0 | 0 |
| `HomeColdStartTest` | 4 | 0 | 0 |
| `ShellWiringTest` | 17 | 0 | 0 |
| `Wave5IntegrationWiringTest` | 25 | 0 | 0 |
| `NotificationSystemTest` | 21 | 0 | 0 |
| `NotificationWithheldOnBackgroundV63Test` | 5 | 0 | 0 |
| `StringsLocaleParityTest` | 3 | 0 | 0 |
| **合计** | **105** | **0** | **0** |

日志：`mcode_startup_defer.log`（首跑）、`mcode_startup_defer_reverify.log`（还原后复跑，BUILD SUCCESSFUL in 8s，105/0/0 一致）。

### 4.3 反向对照（证明 pin 不是空转）

把 `OcActivity.kt` 临时改回**修复前**形态（`remember` + `DisposableEffect(networkWatch) { start() }`），只跑新 pin：

```
> Task :app:testDebugUnitTest FAILED

StartupDeferV97Test > the first-frame path no longer registers a network callback FAILED
    java.lang.AssertionError at StartupDeferV97Test.kt:70

StartupDeferV97Test > the watch is reached only from a LaunchedEffect that owns its teardown FAILED
    java.lang.AssertionError at StartupDeferV97Test.kt:88

7 tests completed, 2 failed
BUILD FAILED in 8s
```

即：恰好 2 个**结构** pin 失败，5 个**行为** pin 仍绿（它们不依赖源码形状，这是对的）。随后已从备份**完整还原**并复跑绿。日志：`mcode_startup_defer_negative.log`。

### 4.4 这套闸门**证明不了**什么（与 ④ B.1 同一个洞）

105 个用例全是 JVM 单测，跑的是 `build/tmp/kotlin-classes`，**与 APK 内的 dex 完全无关**。R8/裁剪类改动它们测不到；**本次改动虽然不改类结构、不改产物字节，但仍需真机确认 `registerDefaultNetworkCallback` 在真机上按预期回调**。不要把"BUILD SUCCESSFUL"读成"运行时已验证"。

---

## 5. 真机复验步骤（Lead 用 `am start -W` 量首帧）

```bash
# 0) 装 debug 包（由 Lead 在闸门里 assembleDebug）
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 1) 清数据，量一次干净的冷启动（改前/改后各跑 7 次取中位数，别只跑 1 次）
adb shell pm clear com.xiufeigo.opencodeshell
adb shell am start -W -S -n com.xiufeigo.opencodeshell/.OcActivity
# 关注 ThisTime / TotalTime；同一条命令重复 7 次，丢弃首次

# 2) 行为回归：断网 → 恢复，heal 仍应触发
adb shell svc wifi disable          # 或 airplane 模式
adb shell svc wifi enable
adb logcat -c
# 恢复网络后观察：流应重连、配置提示应被清掉
adb logcat -d | grep -iE "OcNotify|onNetworkAvailable|ChatStore|Transport"

# 3) 后台保活/旋转/退出，确认没有回调泄漏或崩溃
adb shell am start -n com.xiufeigo.opencodeshell/.OcActivity
adb shell input keyevent KEYCODE_HOME
adb shell am force-stop com.xiufeigo.opencodeshell   # 确认无残留 registered callback 告警
```

**期望**：首帧指标小幅变好（量级取决于那一次 binder 调用的真实成本，**本报告不给预估数字**）；断网恢复的 heal 行为**与改前完全一致**；`logcat` 无新增异常。

---

## 6. 未闭环 / 已知局限

1. **没有任何实测数据**。本轮无设备，§1 的代价等级全是**静态判读**（源码 + Android 平台行为），不是测量值。`am start -W` 的 A/B 还没做。
2. **报告的 A.5 #3 前提被证伪两条**（`SystemNotifier` 不碰 `NotificationManager`；`NotificationPrefs` 构造期只是句柄）。建议 `COLD_START_PERF.md` 的 #3 相应修正，否则后续会有人继续去"优化"一个不在首帧路径上的东西。
3. **真正的大头没动，也不该在本轮动**：`ServerConfigStore`（Keystore + 加密 prefs）与 `SettingsStore`（首载读盘 + `LocaleManager`）都真的在首帧前且真的重，但搬它们会改首帧内容或语言切换时机。要动需要**独立立项 + 真机 A/B + 明确的闪屏验收标准**，不能夹带在"只搬时间点、不改语义"的约束里。
4. **`AppearancePrefs.bottomTabStrip()` 仍是首帧一次同步读**（虽然同文件已缓存、代价低）。要彻底消除需要把它也做成"默认值 + 加载后更新"，那是**布局闪一下**的可见变更，不在本轮授权内。
5. **未跑全量 629 个测试**（命令白名单只允许按类跑）。我按"启动/装配相关"grep 找了上表 10 类；`OcActivity` 的改动只触及 `NetworkRecoveryWatch` 的装配，接线面很窄，但不等于全量无回归。
6. **`MainActivity.kt` / `ui/App.kt` 我有写权限但没动**——本轮没有需要改它们的地方，不为了"显得有产出"而制造 diff。
