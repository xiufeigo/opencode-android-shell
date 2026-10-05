# ADVERSARIAL_AUDIT_A5 —— 交接文档头牌声明的对抗性机械复核

> **审计对象**：`HANDOFF_WEB_SHELL.md`（只读）及其引用的 `research/**`
> **审计立场**：**试图证伪**。每条声明三选一：`可独立复验` / `部分可复验` / `无法复验`。
> **执行者**：A5 对抗性审计工程师（mcode）
> **审计时间**：2026-10-05 · 硬期限 2026-10-07
> **纪律**：**纯只读**——未碰设备、未起模拟器、未跑 gradle、未改任何生产代码。唯一写入 = 本文件。
> **写入范围**：`research/audit/**`（本文件）。未碰 `app/**`、`webapp/**`、`_upstream/**` 工作区（仅 `git rev-parse` / `git cat-file` 读对象）、`.github/**`、`research/deliver/**`、他人报告、`HANDOFF_WEB_SHELL.md`。
> **环境**：`local.properties` → SDK `C:/Users/xiufe/AppData/Local/Android/Sdk`，`build-tools/36.0.0`（`aapt.exe` / `apksigner.bat` / `dexdump.exe`）。
> **复核前状态**：`git status --porcelain` = 0 行（工作区干净，审计未产生任何改动）。

---

## 0. 判定汇总

| 判定 | 条数 |
|---|---|
| ✅ 可独立复验 | **28** |
| 🟡 部分可复验 | **3** |
| ❌ 声明与包内事实冲突（被推翻） | **2** |
| **编号声明合计** | **33** |

> 另有 **U1–U4 四条"完全无法复验"**（§7）：全部是**依赖真机 logcat / `dumpsys` 的运行期观测**，本审计禁止碰设备 ⇒ 静态磁盘上无任何一条原文可查。
> 其中 U1、U4 已在 §3/§6 的编号声明里以"部分可复验"或"依赖源报告"体现，**不重复计数**。
> ⇒ **本次共审 33 条编号声明 + 4 条无法复验清单条目 = 37 个复核点**；其中 1 条（§1.7）、1 条（§5.2）**被明确推翻**。

**最尖锐的三处**（详见 §10）：**"一个前端字节都不打包"被一条 `unzip` 直接推翻**；**裂缝① 把已登记的 `627f8ed8` 说成"两栏都不在"**；**`origin/v2 = 0a46301` 里的 hash 根本不是有效对象，且同一文档三处对 `origin/v2` 存在性互斥**。

---

## 1. 交付物（声明 1）

### 1.1 两行交付物的体积 + md5 —— ✅ 可独立复验

```powershell
cd research\deliver; Get-ChildItem *.apk | ForEach-Object { $h=(Get-FileHash $_.FullName -Algorithm MD5).Hash.ToLower(); "{0}`t{1}`t{2}" -f $_.Name,$_.Length,$h }
```

| 文件 | 磁盘实测 | 文档声明 | 判定 |
|---|---|---|---|
| `opencode-shell-web-release-nor8.apk` | 3,975,389 B / `76ca064b7de9246ee446f765a9198b37` | 同 | ✅ |
| `opencode-shell-web.apk` | 5,275,479 B / `16c6cf8b7330e5d02879f30333800ad9` | 同 | ✅ |
| `opencode-shell-debug.apk` | 44,198,253 B / `bd21ff21e72e75db62fc371d290f438e` | 同 | ✅ |
| `opencode-shell-release-debugsigned.apk` | 29,393,986 B / `b6073ac63f5d8796708fb269fc855a8d` | 同 | ✅ |

**四个 md5 逐位一致。** §0.1 的 F8 复核表（L34–L37）四行全部核对通过。

### 1.2 构建目录与 deliver 同字节 —— ✅ 可独立复验

```powershell
Get-ChildItem webapp\build\outputs\apk -Recurse -Filter *.apk | %{ "{0}`t{1}`t{2}" -f $_.FullName,$_.Length,(Get-FileHash $_.FullName -Algorithm MD5).Hash.ToLower() }
```
```
webapp-debug.apk     5275479  16c6cf8b7330e5d02879f30333800ad9
webapp-release.apk  3975389  76ca064b7de9246ee446f765a9198b37
```
✅ 两份都与 `deliver/` 逐字节相同（体积 + md5 双证）。**§0.1 L28 的"拿构建目录那份也安全"成立。**

### 1.3 签名 `CN=Android Debug` —— ✅ 可独立复验

```powershell
& "$sdk\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs research\deliver\opencode-shell-web-release-nor8.apk
```
```
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: e86b70066546bd0bdc2e6cb4d970704bc48b1a244af59c664564f030cca0510a
```
✅ 文档 L19 写的 SHA-256 `e86b7006…0510a` **逐位吻合**。**且 release 变体只有 v2 签名、v1/v3 均 false**——文档没说这一层（见 §5.3 建议）。

### 1.4 包内 `assets/www/oc-host.js` 与源码 md5 一致 —— ✅ 可独立复验

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[System.IO.Compression.ZipFile]::OpenRead("$PWD\research\deliver\opencode-shell-web-release-nor8.apk")
$e=$zip.Entries | ? { $_.FullName -eq 'assets/www/oc-host.js' }
[System.IO.Compression.ZipFileExtensions]::ExtractToFile($e,$out,$true)
```
| 位置 | 体积 | md5 |
|---|---|---|
| APK 内 `assets/www/oc-host.js` | 16,939 B | `2b6ee8db66451f659737f29dcfc96f45` |
| 源码 `webapp/src/main/assets/www/oc-host.js` | 16,939 B | `2b6ee8db66451f659737f29dcfc96f45` |

✅ **字节级完全一致。** 顺带证实 §0.1 末注（L55）说的"上一版把 `bf542769…`（13,804 B）误列进 APK 作废清单是分类错误"——磁盘上 `bf542769…` 确实是另一份更小的资产（存在于 `research/audit/{preflight_unpack,rel_v5_unpack_0405}` 的旧包解包目录），**不是任何 APK 的 md5**。这条自我更正**经得起复验**。

### 1.5 "已作废的旧 md5 确实都不等于当前值" —— ✅ 可独立复验

文档 L47–L53 列出 7 组作废 md5（`9ab8ee30`代 / R2 / V5 / F3 / F4 / F5-f5 / F5规范件）。逐条与当前值 `76ca064b…`、`16c6cf8b…` 比对：**7 组 14 个值全部 ≠ 当前值**，无一条被误列。✅

### 1.6 `research/deliver/` 无多余重名/后缀副本 —— ✅ 可独立复验

```powershell
Get-ChildItem research\deliver -Force -Recurse
```
**目录下恰好 4 个文件，全是 `.apk`，全部在 §0.1 表内登记。** 无 `-f5` 残留、无 `.apk.1`/`.bak`/副本：
```powershell
Get-ChildItem -Recurse -Filter "*f5*.apk"   # → 空
```
✅ 文档 L21 说"`…-f5.apk` 已删除"**属实**；§6.2 ⑫ 的命名纪律**执行到位**。

### 1.7 "一个前端字节都不打包" —— ❌ **声明与包内事实冲突（可独立复验 = 被推翻）**

文档 L27 原话：「web 壳 release 3.98 MB ⇒ 小 7.4 倍（**一个前端字节都不打包**）」。

```powershell
$z.Entries | ? { $_.FullName -like 'assets/www/*' } | select FullName,Length,CompressedLength
```
```
assets/www/index.html      17621 B (压缩 6857)
assets/www/oc-host.js      16939 B (压缩 6038)
assets/www/oc-perf.js       3307 B (压缩 1524)
assets/www/oc-preflight.js  2934 B (压缩 1556)
→ count=4  sumUncompressed=40801  sumCompressed=15975
```

**❌ 交付包内确实带着 40,801 B（未压缩）的 HTML/JS 前端资产。** 文档的本意应是"**不打包官方前端（上游 SPA）字节**"，但字面写成了"一个前端字节都不打包"——**这是一条会被外人用一条 `unzip` 推翻的声明**，且它恰好出现在 §0.1 头牌表里。

> **改写建议**：「**不打包官方前端（上游 SPA）的任何字节**；壳自带的 4 个 facade 资产（`index.html` / `oc-host.js` / `oc-perf.js` / `oc-preflight.js`）共 **40,801 B 未压缩 / 15,975 B 压缩**仍在包内。」——改后既保住"小 7.4 倍"的事实，又经得起 `unzip -l`。

### 1.8 "小 7.4 倍" 算术 —— ✅ 可独立复验

`29,393,986 / 3,975,389 = 7.394` ⇒ "小 7.4 倍" 成立（原生 release vs web release，同为 release，可比）。

---

## 2. 闸门（声明 2）

### 2.1 APP 677 suites / 6136 tests / 0 failures —— ✅ 可独立复验

```powershell
# 目录：app/build/test-results/testDebugUnitTest/TEST-*.xml（812 个 XML 含 testReleaseUnitTest）
foreach($f in Get-ChildItem app\build\test-results\testDebugUnitTest -Filter "TEST-*.xml"){
  [xml]$x=Get-Content $f.FullName; $s++; $t+=[int]$x.testsuite.tests; $fa+=[int]$x.testsuite.failures; $e+=[int]$x.testsuite.errors }
```
```
APP testDebugUnitTest suites=677 tests=6136 failures=0 errors=0 skipped=0
```
✅ **与文档逐位一致。** 测试结果 XML **未被清掉**，可完整复算。

### 2.2 WEBAPP 16 suites / 147 tests / 0 failures —— ✅ 可独立复验

⚠️ 注意：**`webapp/build/test-results` 根目录下无 XML**，实际在 `testDebugUnitTest/` 子目录里（这点文档没写，第三方按 §2 的闸门命令找会先扑空）。逐 suite 复算：

| Suite | tests | fail | err |
|---|---|---|---|
| `CameraPermissionHostTest` | 9 | 0 | 0 |
| `ConnectRobustnessTest` | 19 | 0 | 0 |
| `DebugEvalChannelTest` | 2 | 0 | 0 |
| `FallbackPageTouchTargetTest` | 2 | 0 | 0 |
| `host.ImeOcclusionTest` | 8 | 0 | 0 |
| `host.NotificationClickPlanTest` | 7 | 0 | 0 |
| `host.NotificationSessionRouteTest` | 7 | 0 | 0 |
| `host.PowerPolicyTest` | 11 | 0 | 0 |
| `host.RoundActivityTrackerTest` | 16 | 0 | 0 |
| `host.RoundStreamSignalReleaseTest` | 13 | 0 | 0 |
| `InputPathHotspotTest` | 6 | 0 | 0 |
| `KeepScreenOnWiringTest` | 14 | 0 | 0 |
| `NotificationPolicyBridgeTest` | 9 | 0 | 0 |
| `PowerWiringTest` | 10 | 0 | 0 |
| `SetupDialogTouchTargetTest` | 8 | 0 | 0 |
| `WebViewPreflightTest` | 6 | 0 | 0 |
| **合计** | **147** | **0** | **0** |

✅ **16 suites / 147 tests / 0 failures，与文档逐位一致。** `ConnectRobustnessTest` = 19 也与 §3.1 回归说法（13→19）吻合。

### 2.3 "用例数只增不减"（128→141→147 / 6136） —— 🟡 部分可复验

XML 里能看到的**当前**值（147 / 6136）✅ 复验通过；但**历史值 141 / 128 已无磁盘证据**（无旧 XML、无旧 APK、`.gitignore` 含 `*.apk`）⇒ 只能听信历史报告。**"只增不减"是过程性断言，静态快照无法证伪。**

---

## 3. 权限与清单（声明 3）—— 全部读包，非读源码

### 3.1 权限全表 —— ✅ 可独立复验

```powershell
& "$bt\aapt.exe" dump badging research\deliver\opencode-shell-web-release-nor8.apk
```
**release 包声明的权限（全表，4 条）：**
| 权限 | 敏感度 | 文档是否说明 |
|---|---|---|
| `android.permission.POST_NOTIFICATIONS` | 中（弹通知） | ❌ **全文 0 次提及** |
| `android.permission.INTERNET` | 网络 | 隐含 |
| `android.permission.CAMERA` | **敏感** | ✅ §3.4 明确 |
| `…web.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | 无（AGP 自生成的自定义权限） | ❌ 未提及（无害） |

✅ **`CAMERA` 确实在包内**（声明成立）。
⚠️ **但"没有多出未在文档中说明的敏感权限"这句只对了一半**：`POST_NOTIFICATIONS` 是一条真实的运行时权限（Android 13+ 需用户授权），**文档全文一次都没提**（`Select-String POST_NOTIFICATIONS` → 0 命中）。它不敏感，但它会让一个"我已列全权限"的人**漏掉一条**。
> **改写建议**：§3.4 相机那节加一行「**包内权限共 4 条**：`INTERNET` / `CAMERA` / `POST_NOTIFICATIONS`（通知渠道用）/ AGP 自生成的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（无运行时风险）」。

### 3.2 `uses-feature camera required="false"` —— ✅ 可独立复验

```
  uses-feature-not-required: name='android.hardware.camera'
  uses-feature: name='android.hardware.faketouch'
  uses-implied-feature: name='android.hardware.faketouch' reason='default feature for all apps'
```
✅ **声明成立**：camera 特性被声明为**非必需**（`aapt` 用 `uses-feature-not-required` 表示 `required="false"`），且 **CAMERA 是包内唯一敏感权限**。

### 3.3 debug 包对照 —— ✅ 可独立复验

`opencode-shell-web.apk` 权限与 release **完全相同**，额外带 `application-debuggable`。✅ 两条包权限面一致，无差异。

### 3.4 包名统一 `…web.debug` —— ✅ 可独立复验

`aapt dump badging` → `package: name='com.xiufeigo.opencodeshell.web.debug' versionName='0.1.0-webhost'`，**release 变体也用 debug 包名**，与 §5③ 的"必须换包名"说法一致。

---

## 4. 修复确实在包里（声明 4）—— 全部读包

方法：解出 `classes.dex`（8,800,468 B）+ `classes2.dex`（960,316 B）→ `dexdump -d` 全量反汇编 + 原始字节串检索。
⚠️ **`classes3/4/5.dex` 在本包中 ABSENT**（与 §0.2 裂缝⑤ 提到的"当时没有 classes5.dex"不同，本包只有 2 个 dex）。

### 4.1 回合常亮 `RoundActivityTracker` —— ✅ 可独立复验

| 符号 | `dexdump -d` 命中（classes.dex） |
|---|---|
| `RoundActivityTracker` | 247 |
| `applyKeepScreenOn` | 16 |
| `roundActive` | 3 |
| `SSE_SCAN_WINDOW_CHARS` | 1 |
| `D1 applyKeepScreenOn`（日志串） | 1 |

✅ F4 的 `SSE_SCAN_WINDOW_CHARS` 与 D1 的日志串**都在包里**。

### 4.2 `WebViewPreflight` + `MIN_MAJOR 105` —— ✅ 可独立复验（含**常量值本身**）

`dexdump -d` 静态字段块原文：
```
Class descriptor : 'Lcom/xiufeigo/opencodeshell/web/host/WebViewPreflight;'
Static fields -
 #1 name:'LOAD_STALL_TIMEOUT_MS' type:'J' access:0x0019 (PUBLIC STATIC FINAL) value: 12000
 #2 name:'MIN_MAJOR'            type:'I' access:0x0019 (PUBLIC STATIC FINAL) value: 105
 #3 name:'WORST_OBSERVED_CONTENT_MS' type:'J' access:0x0019 value: 3063
```
✅ **不是只搜到名字，是搜到 `value: 105` 这个实值。** 文档 §0.3 的"阈值 105 / 12 s"两条都在包里（`12000 ms`）。这是本次审计里**最扎实的一条**。

### 4.3 通知点击修复相关符号 —— ✅ 可独立复验

| 符号 | 命中 |
|---|---|
| `NotifierHost` | 6 |
| `oc_from_notification`（`EXTRA_FROM_NOTIFICATION`） | 1 |
| `oc_session_id`（`EXTRA_SESSION_ID`） | 1 |
| `oc_turns`（`CHANNEL_TURNS`） | 1 |
| `opened from notification, session=`（修复后日志） | 1 |
| `onOpenSession`（页面侧回跳） | 1 |

✅ 通知点击 → 回会话的**完整链路符号**（extra 名 + 渠道 + 日志 + JS 桥）**全在 release 包内**。

### 4.4 F5/F6 错误页与探测 —— ✅ 可独立复验（**附一处口径提醒**）

| 符号 | classes.dex 命中 |
|---|---|
| `BackendProbe` | 386 |
| `ConnectErrorPage` | 168 |
| `writeFriendlyPage` | 1 |
| `buildProbeRequest`（F6 唯一请求构造点） | 2 |
| `F5 probe unreachable` | 1 |
| `F5 probe AUTH FAILED code=` | 1 |
| `HINT_AUTH` | 1 |
| `passLen=` | 2 |
| `authHeaderSent=` | 2 |
| `MIN_TOUCH_TARGET_CSS_PX` | 1 |
| `no-store` | 2 |
| `<已隐藏>`（redact 占位） | 2 |

✅ **F6 的 `buildProbeRequest` 确实在包里**（§0.1 L57 的"排除交付旧包"论据成立）。

⚠️ **口径提醒（不算推翻，但会坑到复验人）**：文档 §4.3 的门禁串 `F5 probe OK code=200, loading official UI` **在 dex 里搜整串 = 0 命中**。原因是它在代码里是**两段拼接的运行时模板**：
```
dex 原文相邻串：…F5 probe AUTH FAILED code=..F5 probe OK code=..F5 probe before load path=/api/info..
dex 原文相邻串：…loading anyway.., loading official UI..
```
第三方拿这条整串去 grep APK 会得到 0 命中，**极易误判成"修复不在包内"**——正是文档 §6.2 ⑭ 警告过的那类坑。
> **改写建议**：§4.3 门禁表加一句「该日志是**运行时模板拼接**，静态检索请搜 `F5 probe OK code=`（命中）+ `, loading official UI`（命中）两段，不要搜整串。」

### 4.5 陷阱自陈：`oc_debug_eval` 在 release 包里 —— ✅ 可独立复验（文档自证诚实）

| 串 | classes.dex 命中 |
|---|---|
| `oc_debug_eval` | **1（确实在包里）** |
| `webview_devtools_remote` | 0 |
| `webViewMajor` | 1 |

✅ 文档 §3.4 的自陈「**陷阱**：dex 里能搜到 `oc_debug_eval` 字符串常量，**容易误判成"通道可用"**」**经得起复验**——串确实在，通道确实不可用（release 编译期关闭）。这份自我警觉是文档最可信的部分之一。

### 4.6 manifest 只注册 MAIN/LAUNCHER —— ✅ 可独立复验

```powershell
& "$bt\aapt.exe" dump xmltree research\deliver\opencode-shell-web-release-nor8.apk AndroidManifest.xml
```
```
E: activity (line=68)
A: android:name="android.intent.action.MAIN"
A: android:name="android.intent.category.LAUNCHER"
```
✅ **无 deep link / 无 VIEW intent-filter**，§3.4 第 3 条退路（"没有 deep link"）**成立**。源码侧 `STATE_URL` 也确认只从 `savedInstanceState` 读（`WebHostActivity.kt:335`）、无 intent extra 入口。

---

## 5. 文档自洽（声明 5）

### 5.1 每个 md5 是否与磁盘一致 —— ✅ 可独立复验

文档全文 32-hex 串共 20 处，逐个核过：涉及**磁盘现存文件**的 6 个 md5（deliver 4 份 + build outputs 2 份）**全部逐位一致**；其余为历史代次/已删除件，磁盘无对象可核（但**全部被明确标注为作废代次**，未被当作现行值使用）。

### 5.2 裂缝①「三个 md5 在 §2.1 里两栏都不在」 —— ❌ **被推翻（2/3 对，1/3 错）**

```powershell
Select-String -Path research\web\DELIVER_REBUILD_R2.md -Pattern <md5>   # 逐个
```
| md5 | R2 台账命中 |
|---|---|
| `b063148df53a1364bf5c42ce6bdca0d3`（V5 复验包） | **0** ✅ 确未登记 |
| `bc7f30180775485ce887dc74e4489c72`（V6 复验包） | **0** ✅ 确未登记 |
| `627f8ed8a38aaa8caa24a39c6fe1678d0`（V8 复验包） | **1，且落在 §2.1 之内** ❌ |

R2 文件章节位置：`### 2.1` 在 **L88**，`### 2.2` 在 **L118**；`627f8ed8` 出现在 **L96** ⇒ **确定在 §2.1 的现行表内**，原文：
> `> - F5 / 规范 release（554feb4）：release 627f8ed8a38aaa8caa24a39c6fe1678d0，3,975,357 B`

❌ **文档 L62 原话「这三个 md5 在 §2.1 里两栏都不在」对 V8 那一条是错的。** 这条裂缝是文档用来论证"当前交付包 `76ca064b…` 未单独复跑过三段"的核心依据——**依据本身被高估了 1/3**，但**结论方向仍然成立**（V5/V6 两包确实未登记，常亮三段与错误态三态确实不是在 `76ca064b…` 上验的）。
> **改写建议**：「§2.1 现行表**已登记** F5 规范件 `627f8ed8…`（L96）、R2 件、F3 件、F6 件四代；**未登记**的是 V5 复验的 `b063148d…` 与 V6 复验的 `bc7f3018…` **两代**——而 §3.5「常亮三段 PASS」中的 V5/V6 两轮正是跑在这两个未登记包上。」

### 5.3 §0.1「✅ 推荐对外分发」 vs §5③「对外分发前必须换包名+换证」 —— 🟡 部分自洽（已自陈，但仍在头牌表里）

文档 L378 **自己已经打了补丁**：「在改名/换证定调前，§0.1 那行"推荐对外分发"只对内部灰度成立」。**属于"知道但没改干净"**——头牌表里仍是一个孤零零的 ✅。第三方只截 §0.1 一张表转述时，会得到"可以对外分发"的错误结论，而**实际用的是 Android Debug 证书 + `.debug` 包名**（§1.3 已实测）。
> **改写建议**：§0.1 表格该行的 ✅ 直接改成 ⚠️「**仅内部灰度**（debug 证书 + `.debug` 包名，对外分发前必须换证改包名，见 §5③）」。

### 5.4 §0.1 与 §1 的体积口径 —— ✅ 可独立复验（无矛盾）

§0.1 L103 与 §1 装配体积表：原生 debug 44,198,253 / 原生 release 29,393,986 / **原生 release+R8 17,809,425** / web debug 5,275,479 / web release 3,975,389。
✅ 17,809,425 B 经 `research/_scratch_v152/app-release-R8.apk` 实测 = **17,809,425**，逐位吻合。web release+R8 "未产出" 亦实测确认（`webapp/build/outputs` 下**无任何 R8 产物**）。

### 5.5 文档点名的字节数 —— ✅ 可独立复验（4/4 全中）

| 文档声明 | 磁盘实测 | 判定 |
|---|---|---|
| `battery_real_device.ps1` = 40,542 B | 40,542 B | ✅ |
| `BATTERY_HOWTO.md` = 21,138 B | 21,138 B | ✅ |
| `camera_route.ps1` = 12,490 B | 12,490 B | ✅ |
| `out_v11/tablet1366x768.png` = 83,303 B | 83,303 B | ✅ |
| R8 风险清单 = **12 项** | §5 表格恰好 12 行（#1–#12） | ✅ |

**这份文档的体积自报纪律相当可靠**——4 个随机挑的字节数全中，说明它没有普遍性的"随手编数字"。

### 5.6 "未闭环"三条是否真的都还没做 —— ✅ 可独立复验（3/3 确认未做，文档诚实）

| # | 未闭环项 | 独立取证 | 判定 |
|---|---|---|---|
| ① | **耗电 mAh** | 全仓 `research/perf/*.md` 检索 `mAh` → 命中全部是 `0 mAh` 的**模拟器读数**与 HOWTO 里的**方法论说明**，**无任何编造的 mAh 结论值** | ✅ 确实没做，且没造假 |
| ② | **R8 版未产出** | `webapp/build/outputs/apk/` 下仅 `debug/` 与 `release/`，**无 R8 产物**（`research/_scratch_v152` 里的 R8 包是**原生壳**的 17.8 MB，与 §1 的"原生 release+R8"对得上，不是 web 壳） | ✅ 确实没做 |
| ③ | **release 身份** | 包名 `com.xiufeigo.opencodeshell.web.debug`（带 `.debug`）+ 证书 `CN=Android Debug` | ✅ 确实没做 |

**§5 的"未闭环"清单是本次审计里诚信度最高的部分之一：3 条全部经独立取证确认为真，一条没被虚报为已完成。**

---

## 6. 量化数字与口径（声明 6）—— **本仓历史高发问题的点名处**

### 6.1 §4.3 形态与切换点 —— ✅ 口径做得最好的一节

§4.3 抬头有**硬口径块**（L272）：`md5 76ca064b…` + `3,975,389 B` + `emulator-6230` + `AVD OC_Deliver_R2` + `2026-10-05` + `已鉴权`，并显式写「**本节不与 V9 / F4 / P5 / B1 的任何数字同轴**」。**每格 4 条前置断言 + 9/9 门禁**。✅ **这一节没有混轴问题，是全文的标杆写法。**

### 6.2 §4.1 输入延迟 —— 🟡 **未标"同包"，且底层包已作废**（**点名**）

§4.1 抬头只写了「**同一台设备、同一几何、每层 n=240**」——**没有标 APK 是哪一份**。追到源报告 `research/perf/LATENCY_FLOOR_B1.md`：

| 口径项 | B1 报告实际值 | 交接文档 §4.1 是否标注 |
|---|---|---|
| web APK md5 | **`8DCFFC572AD469F472F61594F12EA990`** | ❌ **未标** |
| native APK md5 | `BD21FF21…`（= `deliver/opencode-shell-debug.apk` ✅ 现存） | ❌ 未标 |
| 设备 | `emulator-5854` / AVD `OC_Perf_Test` | ❌ 未标 |
| 样本 | 表格 240（一句话摘要写"每层 n=120"，是**两轮各 120 合并**） | 🟡 写了 240 但未说"两轮合并" |

⚠️ **`8DCFFC57…` 正是 §0.1 L47 自己列为"已作废"的 debug 包（8,636,901 B）**。也就是说：
> **§4.1 的"剩余差距 = 使用官方 UI 的票价，不是壳侧缺陷"这个强结论，是建立在一份**已被本文件判定作废的包**上的，而 §4.1 全文没有告诉读者这一点。**

§4.1 是全文**唯一一处把强结论建立在作废包上、且未标口径**的量化节。与 §4.3 的标杆写法形成刺眼对比。
> **改写建议**：§4.1 抬头补口径块——「**本节数据取自 B1 轮：设备 `emulator-5854`，web 包 `8DCFFC57…`（已作废代次，8,636,901 B），native 包 `BD21FF21…`；样本为两轮各 120 合并后的 n=240。结论方向可信，但数字**不是**取自当前交付包 `76ca064b…`**。」

### 6.3 §4.2 首屏 1,676 ms —— 🟡 **未标包，且源报告用的是已作废的 R2 件**（**点名**）

§0.3 与 §4.2 都把「5 次冷启中位数 **1,676 ms**」当现行结论，**只说"UI 可用标记口径"，不标包/设备**。追源 `research/web/GAP_CLOSE_G2.md`：
- G2 抬头：「设备 serial **`emulator-5910`**」（≠ §4.3 的 `emulator-6230`）
- G2 的 dex 比对节抬头：`RELEASE (opencode-shell-web-release-nor8.apk, 3961217 B, md5 7b578ceec3d7e35e2fffa4da67fe2d3b)` ⇒ **是 §0.1 已列作废的 R2 件**

⚠️ **同一个文件名 `opencode-shell-web-release-nor8.apk` 对应过至少两个不同字节体**（`7b578cee…` 3,961,217 B 已作废 vs `76ca064b…` 3,975,389 B 现行）——**这正是最容易混轴的陷阱**。文档 §4.2 把 1,676 ms 当作"交付包首屏"陈述，**而它不是**。
> **改写建议**：§4.2 首屏行加口径：「1,676 ms 取自 `emulator-5910` + **R2 件 `7b578cee…`（已作废）**，**非**当前交付包 `76ca064b…` 的首屏；如需交付包首屏，**须重测**。」

### 6.4 §4.2 帧时 —— ✅ 文档自带降级声明

「Web 28–31 ms vs 原生 17/26 ms」+「**87% 是模拟器地板**；跨轮/跨机**只有方向可信**」——✅ **主动降级口径，点名表扬**。这是处理混轴的正确姿势。

### 6.5 §4.4 作废台账 —— ✅ 撤回纪律到位

V9 那批数字（`uiAvailableMs≈10.7–11.0s`、`7,252 ms`、「第二切换点在 820–1366dp」）**显式作废 + 给原因 + 指明现在以什么为准**，并明确「**两者不同轮不同包，不相减、不混轴**」。✅ 全文在"撤回"这件事上做得比"标注"好。

### 6.6 "上游两周 526 提交" —— ✅ 可独立复验

```powershell
git -C _upstream rev-list --count 702a73d..origin/v2   # → 526
```
✅ **526 精确吻合。**

---

## 7. ❌ 无法复验清单（含改写建议）

| # | 声明 | 为什么无法复验 | 改写建议 |
|---|---|---|---|
| **U1** | §0.3 错误态三态 / §3.5 常亮三段「✅ PASS」 | 全部依赖**真机 logcat 与 `dumpsys` 输出**，本审计**禁止碰设备**；且这些结论**明确只在 `bc7f3018…`/`627f8ed8…` 上验过**（文档自己承认）。静态磁盘上**无任何一条 logcat 原文**可查 | 文档已自陈"需在 `76ca064b…` 上复跑"——**保持自陈，但把 §0.3 表格的 ✅ 改成 🟡 并加"（验于 `bc7f3018…`/`627f8ed8…`，非交付包）"**。这正是裂缝①的结论，**§0.3 表格却仍写 ✅**——两处口径打架 |
| **U2** | §0.1 声称「F8 **本轮亲手重跑** `Get-FileHash`」 | 结果**可复验且已复验通过**（1.1–1.2 全中），但"**是谁在什么时候跑的**"是**过程性事实**，静态仓库无法证明 | 无需改写，**结果已经站得住**。但"亲手重跑"这种措辞对审计者无价值，可弱化为「F8 复核（2026-10-05，结论已由 A5 独立复算确认）」 |
| **U3** | §3.1「V8 轮撞上的：设备侧直连 200 / App 侧 401」等 F6 根因叙述 | 根因分析本身**已由 §3.2 的 `buildProbeRequest` 符号在包内**间接佐证 ✅，但"设备侧直连返回 200"这一**运行期观测**无法离线复验 | 保留。**但把"根因"与"观测"分栏**：根因（可静态复验）与观测（需设备）不要写在同一格里 |
| **U4** | §4.3 全部 9 格形态 / IME 重叠 0 px | 依赖 `emulator-6230` 的截图与 `dumpsys window` 原始行；`out_v11/tablet1366x768.png` **在磁盘上（83,303 B ✅）**，但**像素结论与 IME 帧行**不可离线复算 | 保留脚本 `_v11_sweep.ps1`（9,622 B，**在磁盘**）作为复验入口，并在文档里**补上该脚本路径**——目前 §4.3 只引 `FORMFACTOR_V11.md`，第三方不知道去哪跑 |

---

## 8. 🔴 发现的自相矛盾（点名）

| # | 矛盾 | 证据 | 严重度 |
|---|---|---|---|
| **C1** | **§0.1「一个前端字节都不打包」 vs 包内 40,801 B 前端资产** | `unzip` 实测 4 个 `assets/www/*` 文件，sumUncompressed=**40,801** | 🔴 **高**（头牌表、一条命令推翻） |
| **C2** | **§0.2 裂缝①「三个 md5 两栏都不在」 vs `627f8ed8…` 确在 §2.1 L96** | `Select-String` 命中 1 次，落在 §2.1（L88–L118）区间内 | 🔴 **高**（用来论证核心裂缝，依据被高估 1/3） |
| **C3** | **`origin/v2` 三处互斥说法** | ① L87：「`origin/v2 = 0a46301`」；② L194/L504：「`origin/v2` **在本 clone 里不存在**，`git rev-parse --verify origin/v2` → `fatal`」；③ L397：「审计一律改用 git 对象读 `origin/v2`」 | 🔴 **高** |
| **C4** | **§0.3 表内「回合常亮三段 ✅ PASS」 vs §0.2 裂缝① + §3.5 第 3 条「`76ca064b…` 未单独验」** | §0.3 L79 写 ✅（仅括注两包名），但同文档三处说交付包未验 | 🟡 中（**同一页自相矛盾**） |
| **C5** | **§0.1 表「✅ 推荐对外分发」 vs §5③「对外分发前必须换包名+换证」** | 文档 L378 已自陈，但头牌表 ✅ 未改 | 🟡 中 |
| **C6** | **§4.2 首屏 1,676 ms 当作交付包结论，未标包** | G2 抬头 = `emulator-5910` + 已作废 R2 件 `7b578cee…` | 🟡 中（**混轴**） |
| **C7** | **§4.1 输入延迟未标包，底层为已作废 `8DCFFC57…`** | B1 L41 抬头 | 🟡 中（**混轴**） |
| **C8** | **B1 报告自身：一句话摘要「每层 n=120」 vs 同文件表格 240** | `LATENCY_FLOOR_B1.md` 表头 vs 摘要段（实为两轮合并） | 🟢 低（源报告内部，文档已采 240） |

### C3 详解（最严重）

```powershell
git rev-parse --verify origin/v2                    # 主仓 → fatal: Needed a single revision  ← 文档②对
git -C _upstream rev-parse --verify origin/v2       # → ecfa884ff82e... ← 文档②错（_upstream 里存在）
git -C _upstream cat-file -t 0a46301                # → fatal: Not a valid object name  ← 文档①错
```
**同一个文档里 `origin/v2` 被说了三句，至少两句是错的**：`0a46301` **不是本仓任何有效对象**（真实 `origin/v2` = `ecfa884`）；而"`origin/v2` 不存在"只在**主仓**成立，在 **`_upstream` 仓里明确存在**。
> **改写建议**（一步到位）：「`origin/v2` 在**主仓**不存在（`fatal: Needed a single revision`），但 **`_upstream/` 是独立 clone，其中 `origin/v2 = ecfa884` 存在**；本文所有上游行号均取自 `_upstream` 的 `origin/v2`（`ecfa884`）。⚠️ 早期报告里写的 `0a46301` **在本机不是有效对象**，请勿引用。」

---

## 9. 混轴比较的点名汇总

| 位置 | 混轴形态 | 严重度 |
|---|---|---|
| **§4.1 输入延迟** | 强结论（"票价在官方 UI，不在壳侧"）建立在**已作废包 `8DCFFC57…`** 上，且抬头**只标"同机同几何"不标"同包"**——与 §6.1⑧ 自己立的规矩（"必须同轮、同参数、同设备指纹"）**自相矛盾** | 🔴 **本仓最典型的混轴复发** |
| **§4.2 首屏 1,676 ms** | 未标包/设备，源报告用**已作废 R2 件 + `emulator-5910`**；文档却把它当"交付包首屏"在 §0.3 与 §4.2 两处陈述 | 🔴 高 |
| **文件名陷阱** | `opencode-shell-web-release-nor8.apk` **这个文件名对应过 ≥2 个不同字节体**（`7b578cee…` 3,961,217 B 已作废 / `76ca064b…` 3,975,389 B 现行）。**引用文件名而不引用 md5，就是混轴的邀请** | 🔴 高（系统性风险） |
| **§4.3 形态 9 格** | ✅ 口径块钉死 md5+设备+日期+鉴权，显式声明"不与 V9/F4/P5/B1 同轴" | ✅ 正面样板 |
| **§4.2 帧时** | ✅ 主动降级"只有方向可信" | ✅ 正面样板 |
| **§4.4 作废台账** | ✅ 显式撤回 + 不相减 | ✅ 正面样板 |

**一句话**：**这份文档的"作废/撤回"纪律是一流的（V9 整批撤回、8,636,901 B 口径裂缝、裂缝④⑤ 主动自曝），但"作废"执行得干净的同时，§4.1/§4.2 两条现行量化节自己却踩了同一个坑——用已作废的包出数、且不标包。这是全文最讽刺也最危险的地方。**

---

## 10. 🎯 最容易被外人挑穿的三条

### 第一名：「一个前端字节都不打包」（§0.1 L27）—— **一条 `unzip` 秒杀**
> 交付包里躺着 **40,801 B** 的 `assets/www/{index.html, oc-host.js, oc-perf.js, oc-preflight.js}`。
> **复现**：`unzip -l opencode-shell-web-release-nor8.apk 'assets/www/*'`（Windows：`System.IO.Compression.ZipFile`）。
> **杀伤点**：它出现在**§0.1 头牌表**，是全文第一眼的位置；一个懂点 APK 的人 10 秒就能推翻，并据此怀疑整张表。
> **改法**：改成「**不打包官方前端（上游 SPA）的任何字节**；壳自带 4 个 facade 资产共 40,801 B 未压缩 / 15,975 B 压缩仍在包内」。

### 第二名：裂缝①「三个 md5 在 §2.1 里两栏都不在」（§0.2 L62）—— **一条 `Select-String` 秒杀**
> `627f8ed8a38aaa8caa24a39c6fe1678d0` **就在 `DELIVER_REBUILD_R2.md` L96**，落在 §2.1（L88–L118）**现行表内**。
> **杀伤点**：这条裂缝是文档**主动上报、用来要求 Lead 复跑三段**的核心论据。**上报者自己的论据被查出 1/3 不实**——这会连带削弱"三段结论不在交付包上"这个**本身正确**的结论的可信度。
> **改法**：把"三个"改成"**两个**（`b063148d…` / `bc7f3018…`）"，并说明 `627f8ed8…` 已登记。**结论方向不变，但依据从此站得住。**

### 第三名：「`origin/v2 = 0a46301`」（§0.3 L87）—— **一条 `git cat-file` 秒杀，且同文档另有互斥说法**
> `0a46301` **不是本机任何有效对象**（`fatal: Not a valid object name`）；真实 `origin/v2` = `ecfa884`。
> 同时 §3.3/§8 又说「`origin/v2` **在本 clone 里不存在**」——而它在 **`_upstream` 里明确存在**（那里本来就是独立 clone）。
> **杀伤点**：**同一文档对同一个 ref 给了两个互斥的存在性判断 + 一个不存在的 hash**。审阅者一旦发现，会连带怀疑 §3.3 那整条"配对页是上游行为"的行号取证（因为它的口径声明正是建立在"`origin/v2` 不存在"这句话上）。
> **改法**：见 §8-C3 详情——区分**主仓 / `_upstream` 两个仓**，并把 `0a46301` 标为**无效、勿引用**。

---

## 11. 总体结论

**这份文档经得起大部分机械复核，但有三处"头牌级"表述会在 10 秒内被推翻，且其中两处（第一、二名）出在文档主动上报问题的那一节——这比单纯写错更伤。**

**强的部分（应予保留，不要在返工时误删）**：
- **闸门数字 100% 复算通过**（677/6136/0 与 16/147/0，逐位吻合，XML 未被清）
- **交付物 4 份 md5 + 体积 100% 复算通过**，构建目录与 deliver 同字节
- **修复确实在包里——且是实值级证据**：`MIN_MAJOR` 搜到 `value: 105` 而非仅类名
- **未闭环 3 条全部独立取证为真**，一条没虚报
- **字节数自报 4/4 命中**、**526 提交精确吻合**、**§5 R8 风险清单恰好 12 项**
- **`origin/v2 = ecfa884` 的 526 提交数**、**§4.3 口径块**、**§4.4 作废台账**是全文标杆

**必须改的（按性价比排序）**：
1. 「一个前端字节都不打包」→ 改口径（10 秒可推翻的头牌错误）
2. 裂缝①「三个」→「两个」（自身论据不实）
3. `origin/v2` 三处说法统一（两个仓 + 无效 hash）
4. §4.1 / §4.2 补"同包"口径块，并注明底层是**已作废包**
5. §0.3 两处 ✅ 降级为 🟡 + 标注"验于哪两包"（消除 C4 页内矛盾）
6. §3.4 补权限全表（`POST_NOTIFICATIONS` 目前全文 0 提及）
7. §4.3 门禁串加"这是运行时模板，别搜整串"提醒 + 补 `_v11_sweep.ps1` 复验入口

**最深的那个问题不是某个数字错了，而是**：**文档已经建立了"作废/撤回/标口径"的完整方法论（§6.1 ⑧⑪、§4.4、裂缝④⑤），却在 §4.1/§4.2 这两条最该守规矩的现行量化节上，自己没守。** 建议返工时把 §4.3 抬头那种口径块**作为模板，强制套用到 §4.1/§4.2**。

---

> **审计纪律自证**：本轮 0 次设备操作 · 0 次模拟器 · 0 次 gradle · 0 次生产代码改动 · 0 次他人报告改动 · `git status --porcelain` = 0 行 · 唯一写入 = `research/audit/ADVERSARIAL_AUDIT_A5.md`。
> **复验者提示**：本报告所有命令均可在无设备、无 gradle 的条件下原样重跑；工具链为 `build-tools/36.0.0` 的 `aapt.exe` / `apksigner.bat` / `dexdump.exe` + PowerShell `Get-FileHash` + `System.IO.Compression.ZipFile`。
