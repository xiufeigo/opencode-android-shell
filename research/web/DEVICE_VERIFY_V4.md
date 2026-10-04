# 设备侧验证 V4 — 当前交付包复验 / N-4 文件选存上传 / N-3 PTY

- **执行人**：设备侧验证工程师（V4 轮）
- **时间**：2026-10-05 03:12 – 03:5x
- **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
- **被测包**：`research/deliver/opencode-shell-web-release-nor8.apk`
- **后端**：`127.0.0.1:4095`（Lead 起好，**全程未起停**）
- **硬期限**：2026-10-07 ✅ 本轮在此之前完成

---

## 0. 我碰过哪些 serial

| serial | AVD | 端口 | 我起的？ | 结局 |
|---|---|---|---|---|
| `emulator-5990` | `OC_Deliver_R2` | 5990 | ✅ 是 | **静默退出**（见 §5.1），中途掉线 |
| `emulator-6000` | `OC_Deliver_R2` | 6000 | ✅ 是 | 本轮收尾 `emu kill` 关闭 |

**只有这两个，没有别的。**

- 开轮时 `adb devices` **为空**、`emulator/qemu` 进程数 **0**、端口 5990/6000/6010 全 FREE
  ⇒ **本轮不存在任何可被波及的用户设备**（无 `MCRemote_*` / `DSH_*` / `T7x_*` 在线）。
- 未执行 `adb kill-server`；未用 `-a` / 通配符；只按 **serial** 关。
- 镜像：两台都是 `android-35;google_apis_playstore_tablet` + `pixel_tablet`（复用已验证 AVD `OC_Deliver_R2`，
  `config.ini` 原文 `image.sysdir.1 = system-images\android-35\google_apis_playstore_tablet\x86_64\`、
  `tag.ids = google_apis_playstore,tablet`、`hw.device.name = pixel_tablet`）。
- 几何：`wm size 1080x2400` + `wm density 420`（收尾已 reset）。

---

## 1. 当前交付包的设备复验

### 1.1 包身份（`aapt2 dump badging` 原文）

```
package: name='com.xiufeigo.opencodeshell.web.debug' versionCode='1' versionName='0.1.0-webhost'
  platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
minSdkVersion:'26'
targetSdkVersion:'35'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='com.xiufeigo.opencodeshell.web.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
application-label:'OpenCode Web'
```

- 大小 / MD5 复核（与派单一致）：
  ```
  3962969
  188E019B81436D43DFD4639B08DA6AE5
  ```
  ✅ **3,962,969 B**、md5 **`188e019b81436d43dfd4639b08da6ae5`** —— 逐字匹配。
- 启动 Activity：`aapt2 dump xmltree` 原文
  `A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.xiufeigo.opencodeshell.web.WebHostActivity"`

### 1.2 签名（`apksigner verify --print-certs -v` 原文）

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
```

> 签名是 **Android Debug 证书（v2 only）**。这是**当前构建配置的既定事实**，不是本轮新发现的问题
> （`webapp/build.gradle.kts` 里 `release` buildType 显式复用 `sharedDebug` 签名，注释里写明
> "同一个 debug keystore 签、applicationIdSuffix=.debug … 未被安装过是安装包与分发配置的区别"）。
> **但必须点名**：这份包**不适合对外分发**，指纹 `e86b7006…` 是全网公开的 Android 调试证书。

### 1.3 安装 + 播种口令

```
$ adb -s emulator-5990 install -r -d .../opencode-shell-web-release-nor8.apk
Performing Streamed Install
Success
$ adb -s emulator-6000 install -r -d .../opencode-shell-web-release-nor8.apk
Performing Streamed Install
Success
```

口令**播种状态（重要，如实说明）**：本轮**没有**重新播种。启动日志显示口令**已存在**：

```
10-04 19:18:03.354 I/OcWebHost( 2703): home=http://127.0.0.1:34029/ backend=http://127.0.0.1:4095 passwordPresent=true
```

即 `SecretStore`（`EncryptedSharedPreferences`）里已有口令 —— 来源是 **P6 轮（`emulator-5940`）在同一个
AVD 数据分区里写入的**。因此本轮**没有**走过原生设置对话框（`showSetupDialog` 未被触发，
启动直接 `launch(backend)`）。**这条链路本身本轮未复验**，见 §5.3。

### 1.4 前置身份检查 —— 确认是官方 UI

**方法 A：CDP 不可用，如实记录。** `cat /proc/net/unix | grep webview_devtools` → **无输出**（release 包未开
WebView 调试）。`EXTRA_DEBUG_EVAL` 宿主侧注入钩子**被门禁挡住**（见 §2.4）。改用可访问性树 + 截图。

**方法 B：uiautomator 可访问性树**（`adb pull` 原始文件，42,241 B）：

```
NODE_COUNT = 128
'OpenCode'   [android.webkit.WebView]        <- document.title
'Tabs'       [android.widget.Button]
'Projects'   [android.widget.Button]
'Recent sessions' [android.view.View]
'Today'      [android.widget.TextView]
'O Counting from 1 to 4000, one number per line opencode-android-shell' [android.widget.Button]
'Yesterday'  [android.widget.TextView]
'D Friendly greeting DSH 临时会话' [android.widget.Button]
'Older'      [android.widget.TextView]
'S android-e2e System32' [android.widget.Button]
... 共 128 个节点
```

- `document.title` = **`OpenCode`** ✅
- DOM 节点数 = **128** ✅
- 可见文本 = **真实后端会话列表**（`Counting from 1 to 4000…` / `DSH 临时会话` / `System32` / `DSH-Desktop` …）✅

**方法 C：截图** `_v4_ui.png`，**236,350 B**（高熵 ⇒ 非白屏）。肉眼确认：Home 标题、`All projects`、
`Search sessions`、`Today/Yesterday/Older` 分组、真实会话条目、底部 `MiMo-V2.6-Pro` 模型 chip。

**排除兜底页**：`assets/www/index.html` / diagnostic 页特征未出现（无红字诊断页），代理侧全部取回的是
`/_assets/index-*.js`、`/_assets/desktop-native-*.js` 等官方前端产物。

### 1.5 三条证据

#### ① 官方 UI 渲染 —— **PASS**

见 §1.4（`title=OpenCode` / 128 节点 / 真实会话列表 / 236 KB 截图）。并且交互可用：点会话能进会话页、
四个 tab（Session / Changes / Files / Terminal）都在、能发消息并拿到模型回复（见 §2.3）。

#### ② WebView 预检放行 —— **PASS**（logcat 原文）

```
10-04 19:13:46.200 I/OcWebHost( 3097): G1 webview version=124.0.6367.219 major=124 min=105 tooOld=false
10-04 19:13:46.212 I/OcPerf  ( 3097): I.proxyListening t=322ms port=43087 mode=gzip
10-04 19:13:46.212 I/OcPerf  ( 3097): J.g1.preflightOk t=323ms major=124 unknown=false
10-04 19:13:46.227 I/OcPerf  ( 3097): J.loadUrlIssued t=337ms url=http://127.0.0.1:43087/
```

第二台（冷启动复现）：

```
10-04 19:23:11.926 I/OcPerf  ( 3775): J.g1.preflightOk t=336ms major=124 unknown=false
10-04 19:23:11.947 I/OcPerf  ( 3775): J.loadUrlIssued t=357ms url=http://127.0.0.1:38911/
```

⇒ `J.g1.preflightOk` 出现、`major=124 ≥ min=105`、`unknown=false`、`tooOld=false`，
并且**紧接着**发出了 `J.loadUrlIssued` —— 即走了 `LoadOfficialUi` 分支而不是 `ShowDiagnostic`
（对比被拦分支的标记是 `J.g1.blockedOldWebview`，**未出现**）。**预检确实放行。**

#### ③ 省电策略在位 —— **PASS（有据，但缺正向对照，见下）**

**代码侧定位**（web 壳，不碰 `:app`）：

| 位置 | 内容 |
|---|---|
| `webapp/.../web/host/PowerPolicy.kt:38` | `const val FLAG_KEEP_SCREEN_ON_BIT: Int = 0x00000080` |
| `webapp/.../web/host/PowerPolicy.kt:41` | `hasKeepScreenOn(flags)` 位判定 |
| `webapp/.../web/host/PowerPolicy.kt:65-70` | `applyKeepScreenOn(flags, foreground, roundActive)` |
| `webapp/.../web/WebHostActivity.kt:771-794` | `onPause`：先 `foreground=false`，再**无条件**清常亮 + 掐流式连接 |
| `webapp/.../web/WebHostActivity.kt:815-817` | `clearKeepScreenOnOnBackground()` → `window.clearFlags(FLAG_KEEP_SCREEN_ON_BIT)` |
| `webapp/.../web/host/WebHostBridge.kt:166-178` | 桥 `setKeepScreenOn(on)`，置位前过 `PowerPolicy.mustClearKeepScreenOn` 前台校验 |

**设备侧原文**：

```
# 触发离开前台（KEYCODE_HOME）
10-04 19:15:16.982 I/OcPerf  ( 3097): P6.onPause t=91093ms prunedStreaming=1

# onPause 之后，dumpsys window 全量检索
KEEP_SCREEN_ON occurrences after background = 0
(全机 dumpsys window 的 keepScreenOn|KEEP_SCREEN_ON 命中数 = 0)

# dumpsys power
  mWakefulness=Awake
```

⇒ **`onPause` 这条清理路径确实执行了**（`P6.onPause prunedStreaming=1`），执行后
**全机没有任何窗口持有 `KEEP_SCREEN_ON`**，我们的窗口也不例外。**策略在位。**

⚠️ **两条必须点名的局限（不粉饰）**：

1. **没有正向对照。** 我**无法**演示"先置位再被清掉"，因为 `WebHostBridge.setKeepScreenOn` 是**死代码**
   —— `PowerPolicy.kt:23` 自己写着"§7.3：`WebHostBridge.kt:156-167` 的 `setKeepScreenOn` 是**死代码**（无调用者）"，
   我在 `webapp/src` 全量检索 `setKeepScreenOn`，**只有定义处和单元测试，没有任何生产调用者**；
   上游前端也没有 `__ocAndroid` 引用（§2.4）。所以 flag **从来没被置起来过**，
   "清掉"这一动作在真机上**观测不到状态翻转**，只能证明"没被持有 + 清理路径跑过"。
2. **`mHoldingDisplaySuspendBlocker` 不能当证据。** 它在**前台和后台都是 `true`、不翻转**
   （模拟器自身/adb 侧持有着），所以它**与本条无关**，我没有把它算进 PASS 的依据。

**结论**：省电策略代码在包内、清理路径在真机上跑过、常亮确实没被持有 —— 判 **PASS**，
但强度是"**无残留 + 路径已执行**"，**不是**"观测到置位→清除的状态翻转"。

### 1.6 首屏（UI 可用标记口径，**不是**最后一个 `_assets` GET）

**方法**：冷启动 `am force-stop` → `logcat -c` → `am start -W` → 按 `OcPerf` 标记取时。

```
$ adb shell am start -W -n com.xiufeigo.opencodeshell.web.debug/...WebHostActivity
TotalTime: 593
WaitTime: 595

10-04 19:23:11.694 I/OcPerf  ( 3775): A.onCreate.enter    t=105ms  mode=gzip
10-04 19:23:11.702 I/OcPerf  ( 3775): A.onCreate.superDone t=113ms  mode=gzip
10-04 19:23:11.926 I/OcPerf  ( 3775): I.proxyListening    t=336ms  port=38911 mode=gzip
10-04 19:23:11.926 I/OcPerf  ( 3775): J.g1.preflightOk    t=336ms  major=124 unknown=false
10-04 19:23:11.947 I/OcPerf  ( 3775): J.loadUrlIssued     t=357ms  url=http://127.0.0.1:38911/
10-04 19:23:11.947 I/OcPerf  ( 3775): G.onCreate.exit     t=358ms
10-04 19:23:12.017 I/OcPerf  ( 3775): Z.firstDraw         t=427ms
10-04 19:23:12.620 I/OcWebHost( 3775): web[LOG] ocFirstContent kind=raf at=693 @24
10-04 19:23:12.622 I/OcPerf  ( 3775): M.g1.firstContent   t=1033ms kind=raf
```

| 口径 | 数值 |
|---|---|
| **UI 可用标记 `M.g1.firstContent`（首屏，本轮口径）** | **1033 ms** |
| WebView 首帧 `Z.firstDraw` | 427 ms |
| 页面首次内容绘制 `ocFirstContent kind=raf at=693` | 693 ms（页内时基） |
| `am start -W` TotalTime | 593 ms |
| 墙钟（含 adb 往返开销，poll 到标记） | 1496 ms |

**采用 `M.g1.firstContent = 1033 ms` 作为首屏。** 理由：它是 `onFirstContent`（真实内容进 WebView）
打点的 app 内时基，**不是**最后一个 `_assets` GET —— 后者在这份包上是 20+ 秒量级（`/_assets` 懒加载分片仍在跑，
最大见到 `P.fwd t=21332ms path=/_assets/org-Bn1_MUNF.js`），拿它当首屏会严重高估。

---

## 2. N-4 文件选存上传

### 2.0 实现定位（web 壳，文件:行号）

| 位置 | 作用 |
|---|---|
| `webapp/.../web/host/FileChooserHost.kt:126-136` | `launchOpen(intent, callback)` —— 供 `onShowFileChooser` 调 |
| `webapp/.../web/host/FileChooserHost.kt:138-153` | `launchSave(...)` → `ACTION_CREATE_DOCUMENT` |
| `webapp/.../web/host/FileChooserHost.kt:161-191` | `readInline(uri)` —— 读 ≤1 MiB inline 成 base64 回页面 |
| `webapp/.../web/host/FileChooserHost.kt:36` | `MAX_INLINE_BYTES = 1L shl 20`（1 MiB 上限） |
| `webapp/.../web/WebHostActivity.kt:711-730` | `launchOpenFile(accept, multiple)`：`ACTION_OPEN_DOCUMENT`/`ACTION_GET_CONTENT` |
| `webapp/.../web/WebHostActivity.kt:733-737` | `readPickedInline()` —— 桥 `readPickedFile()` 消费待读 Uri |
| `webapp/.../web/WebHostActivity.kt:749-754` | `launchSave(name, mime, base64)` → 桥 `saveContent` |
| `webapp/.../web/WebHostActivity.kt:920-928` | `onShowFileChooser` 回填 `FileChooserParams.acceptTypes` |

**上游怎么触发的（关键）**：`_upstream/packages/app/src/composer/editor/editor.tsx:106-108`

```
ref={(element) => props.controller.setFileInput(element)}
type="file"
multiple
```

⇒ 上游用的是**标准 `<input type="file">`**，WebView 把它**原生**路由到 `onShowFileChooser`，
**不需要**我们的桥、也**不需要**任何注入。这条路径是**自然可达的**。

### 2.1 第 1 步 · 选择文件（`onShowFileChooser` → SAF）—— **PASS**

**方法**：进会话 → composer 左下 `+`（`Add images and files`）→ 菜单里点 `Images and files`。

```
# 菜单弹出后的可及性树（关键项）
'Add images and files' [android.widget.Button] [73,2249][152,2328] click=true
'MiMo-V2.6-Pro'        [android.widget.Button] [157,2249][585,2328] click=true
'Send'                 [android.widget.Button] [939,2254][1008,2323] click=true
# 点 + 后弹出的菜单（截图 _v4_n4_saf.png）
Images and files (Ctrl+U) / Commands (/) / Context (@) / Shell command (!)
```

**SAF 真的起来了（原文）**：

```
=== current focus ===
mCurrentFocus=Window{a5b8d95 u0 com.google.android.documentsui/com.android.documentsui.picker.PickActivity}
```

选择器可及性树原文（`Recent` / 过滤器 / 抽屉）：

```
'Images'    [android.widget.Button] [64,221][319,347]
'Audio'     [android.widget.Button] [341,221][571,347]
'Videos'    [android.widget.Button] [593,221][840,347]
'Documents' [android.widget.Button] [862,221][1080,347]
'BROWSE FILES IN OTHER APPS' / 'Drive' / 'Media picker' / 'Photos'
'Recent files' [android.view.TextView] [63,658][890,816]
```

**造测试文件**（带唯一标记，便于后面断言内容真的到了后端）：

```
$ adb push v4_n4_probe.txt /sdcard/Download/v4_n4_probe.txt
C:\...\v4_n4_probe.txt: 1 file pushed
$ adb shell ls -l /sdcard/Download/
-rw-rw---- 1 u0_a189 media_rw 38 2026-10-04 19:19 v4_n4_probe.txt
```

文件内容 = `V4_N4_FILE_PROBE_9c1a7b_CONTENT_LINE_1`（38 B，无换行）。
媒体扫描后它在选择器里可见（原文）：

```
'v4_n4_probe.txt' [189,855][486,912]
'7:19?PM, 38 B, TXT document' [189,923][609,966]
```

⇒ **SAF 选择器出现 = PASS。**

**断言：页面是否真的拿到文件（不是只弹了窗）= PASS。**
选完回到会话页，composer 出现附件 chip（截图 `_v4_n4_picked.png`，肉眼可见）：

```
┌────────────────┐
│ v4_n4_probe.txt│   <- 文件名，来自 SAF 回传的 content:// Uri
│ TXT            │   <- 类型
└────────────────┘
```

**文件内容没被截断**（38 B 远小于 1 MiB 上限，`readInline` 正常路径）。

**取消后不崩 = PASS。** 再次开选择器 → 按 `KEYCODE_BACK`：

```
=== focus: SAF should be open ===
mCurrentFocus=Window{a5b8d95 u0 com.google.android.documentsui/com.android.documentsui.picker.PickActivity}
=== press BACK to cancel ===
=== focus after cancel ===
mCurrentFocus=Window{6cf5940 u0 com.xiufeigo.opencodeshell.web.debug/...WebHostActivity}
=== process alive? ===
2703
```

进程 pid 不变、焦点回到宿主 Activity、**无 `FATAL` / `AndroidRuntime` 崩溃**
（logcat 里的 `E/ACS.ResponseHandler` 来自 **pid 1363**，不是我们的 pid 2703，与本应用无关）。
代码侧对应 `FileChooserHost.kt:80-83`（`resultCode != RESULT_OK` → `callback(emptyList())`）
与 `WebHostActivity.kt:723-725`（→ `emitFilePicked({cancelled:true})`）。

### 2.2 第 3 步先做了 · 上传（页面把文件内容发走后端）—— **PASS（硬证据）**

**方法**：保留附件 chip，composer 里输入 `V4UPLOADPROBE` → 点 `Send`。
（先做上传再做保存，因为上传的断言最硬，优先保住。）

**代理转发原文**：

```
10-04 19:21:00.804 I/OcWebHost/Proxy( 2703): POST http://127.0.0.1:4095/api/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y/agent
10-04 19:21:00.916 I/OcWebHost/Proxy( 2703): POST http://127.0.0.1:4095/api/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y/model
10-04 19:21:01.057 I/OcWebHost/Proxy( 2703): POST http://127.0.0.1:4095/api/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y/prompt
10-04 19:21:01.097 I/OcWebHost/Proxy( 2703): GET  http://127.0.0.1:4095/api/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y/message/msg_1085cc97f001dtLX6imBW5jXft
10-04 19:21:01.109 I/OcPerf  ( 2703): P.fwd t=178618ms path=/api/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y/message/msg_10 code=200 upstreamMs=11 totalMs=11
```

**决定性断言 —— 直接查后端（不经页面）**，session 消息列表原文片段：

```
$ GET /api/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y/message   (HTTP 200, bodylen=35124)

MARKER V4_N4_FILE_PROBE_9c1a7b : FOUND
FILENAME v4_n4_probe.txt      : FOUND
TYPED  V4UPLOADPROBE           : FOUND
```

后端里 assistant 消息的**原文**（截取）：

```json
{"id":"msg_1085cf1d5001y42UmA5iKmY3k5","type":"assistant","agent":"build",
 "content":[
  {"type":"reasoning","text":"The file contains: `V4_N4_FILE_PROBE_9c1a7b_CONTENT_LINE_1`. The user's message is \"V4UPLOADPROBE\" with attached file. ..."},
  {"type":"text","text":"`V4_N4_FILE_PROBE_9c1a7b_CONTENT_LINE_1`"}],
 "cost":0,"tokens":{"input":213,"output":22,"reasoning":71}}
```

⇒ **模型端逐字读到了我塞进 SAF 的那个 38 B 文件的内容**。
链路完整闭环：**SD 文件 → SAF `content://` → `FileChooserHost.readInline`（base64）→ 页面 →
`POST /api/session/{id}/prompt` → 后端 → 模型**。**上传 = PASS。**

> 备注：后端消息里 part 类型不是 `"type":"file"`（`file part type : NOT FOUND`），
> 官方前端是把内容作为文本 part 随 prompt 送的。这**不影响**"文件内容确实发走后端"这个结论
> （marker FOUND 就是证据），但**说明它不是二进制附件通道** —— 顺带印证
> `FileChooserHost.kt:27-30` 注释里写明的 1 MiB inline 限制与后续"改走原生代理"的建议方向。

### 2.3 第 2 步 · 保存 / 下载 —— **测不到（NOT REACHABLE），如实记录**

**判定：测不到。不是"应该可以"，也不是 FAIL。**

**卡在哪**：`saveContent` 这条路**在本轮的官方 UI 上没有任何触发点**。
`WebHostActivity.kt:749-754` 的 `launchSave` 只能由**页面**调 `saveContent` 桥触发，
而**页面从不调它**。

**已试过的办法（4 种，全部失败）**：

| # | 办法 | 结果 |
|---|---|---|
| 1 | composer `+` 菜单 | 菜单只有 4 项：`Images and files (Ctrl+U)` / `Commands (/)` / `Context (@)` / `Shell command (!)`。**无保存/下载项** |
| 2 | 会话页 `Files` tab | 是**文件浏览器**（`All files` / `Open files` / 目录树 `_scratch_root` `_upstream` `.git` …），**无下载/导出按钮** |
| 3 | `More options` 菜单（☰） | 点击后无菜单项弹出，dump 前后一致 |
| 4 | 点开一个文件看详情 | 只有文件名列表，**无 download / save / export 控件**（唯一命中 `save` 的是一个叫 `find_save_btn.py` 的**文件名**，不是按钮） |

**宿主侧注入：被门禁挡住（这是本步测不到的技术根因）**

- 想直接 `evaluateJavascript` 调 `window.__ocAndroid.saveContent(...)`，需要 release 包的
  `EXTRA_DEBUG_EVAL` 钩子。该钩子被 `WebHostActivity.kt:699` 的 **`if (!BuildConfig.DEBUG) return`** 挡住。
- 实证：release buildType **没有** `debuggable`，
  `aapt2 dump xmltree` 的 manifest 里**没有** `android:debuggable`，
  设备上 `dumpsys package` 原文 `flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]`（**无 `DEBUGGABLE`**）。
- 且 `/proc/net/unix` **无 `webview_devtools` socket** ⇒ 连 CDP 都连不上。
- ⇒ **没有合法的宿主侧注入通道**去触发 `saveContent`。

**上游是否依赖它**：`_upstream/packages` 全量检索 `__ocAndroid|ocAndroid` → **0 命中**；
检索 `saveContent` → **0 命中**（只命中 install 脚本/READMEE 的无关 "download"）。
⇒ **上游前端既不调我们的桥、也没有下载 UI**。

**结论**：`saveContent` / `ACTION_CREATE_DOCUMENT` 代码**确实在包里**（`FileChooserHost.kt:138-153`
+ `WebHostActivity.kt:749-754`），但**在官方 UI 上不可达**。判 **测不到**，
**不能**判 PASS，也**不能**判 FAIL（代码没被执行过，FAIL 无依据）。
**这正是 A4 说的"在包里但从未设备验证"的另一半 —— 验证结果是"上游没接这条路"。**

### 2.4 N-4 三步小结

| 步骤 | 判定 | 关键依据 |
|---|---|---|
| **选择文件**（`onShowFileChooser` → SAF） | **PASS** | `DocumentsUI PickActivity` 取得焦点；选到 `v4_n4_probe.txt (38 B)`；**页面 composer 出现 chip `v4_n4_probe.txt / TXT`**；**取消后不崩**（pid 2703 存活、无 FATAL） |
| **保存 / 下载** | **测不到** | 官方 UI 4 条路径全无触发点；`saveContent` 上游从不调；宿主注入被 `!BuildConfig.DEBUG` 门禁 + 无 devtools socket 双重挡住 |
| **上传**（内容发走后端） | **PASS** | `POST /api/session/{id}/prompt` 200；直查后端 marker `V4_N4_FILE_PROBE_9c1a7b` **FOUND**，模型逐字复述了文件内容 |

---

## 3. N-3 PTY 通道 —— 判定：**可用**

### 3.1 实现定位（web 壳里到底有没有、由谁提供）

| 问题 | 答案 | 依据 |
|---|---|---|
| **是否存在** | **存在**，但**不是终端实现**，是一条**WebSocket 字节隧道** | `webapp/.../web/host/LoopbackProxy.kt:516` 注释原文：「WebSocket 隧道（PTY 终端）。抄 legacy `tunnelWebSocket`，逐字握手 + 双向 copy。」 |
| **函数体** | `tunnelWebSocket(client, alreadyBuffered, output, parsed, target)` | `LoopbackProxy.kt:517-565`；改写握手行 `:540`、补 `Authorization: Basic` `:542-549`、剥掉 `host/origin/referer/authorization` 逐字透传其余头 `:550-554`、`oc-ws-up` / `oc-ws-down` 两个 daemon 线程双向 `copyStreaming` `:558-560` |
| **升级判定** | `isWebSocketUpgrade(parsed)` | `LoopbackProxy.kt:619` |
| **由谁提供 PTY** | **后端提供，宿主只当管道** | 隧道目标取自 `target`（`upstreamBase` = `http://127.0.0.1:4095`）；web 壳里**没有** PTY 服务端、没有 fork/exec、没有 termios。**PTY 活在 4095 后面** |
| **上游是否依赖** | **是，依赖且是主功能** | 官方 UI 会话页有 **`Terminal` tab**，可访问性树原文 `'Terminal' [android.view.View] [727,115][966,212] click=true` |

> 澄清一个容易混的点：`app/src/main/java/com/xiufeigo/opencodeshell/ui/terminal/share/PersistentPtyClient.kt`
> 和 `:terminal-emulator` / `:terminal-view`（vendored termux VT kernel）是**原生壳 `:app`** 的 A/B 另一成员，
> **不在**这个 web 壳里，与本判定无关。

### 3.2 实测（能测，且测了）

**方法**：进会话 → 点 `Terminal` tab → 在终端里敲探针命令。

**隧道起来了（logcat 原文）**：

```
10-04 19:16:19.634 I/OcWebHost/Proxy( 3097): websocket tunnel -> 127.0.0.1:4095 /api/pty/pty_108587e65001462fLXGwgR45uN/connect
```

⇒ 页面发起了 **WebSocket 升级**到 **`/api/pty/{ptyId}/connect`**，宿主隧道把它**接到了 4095**。
注意这条日志的 tag 是 `OcWebHost/Proxy`（即 `LoopbackProxy.kt:532` 那句 `Log.i(TAG, "websocket tunnel -> ...")`），
**证明走的是我们自己的隧道代码，而不是别的路径**。

**终端 UI 渲染（可访问性树原文）**：

```
'Terminal'        [android.view.View]      [21,210][1060,2380]
'Terminal 1'      [android.view.View]      [73,241][246,317]   click=true
'Close terminal'  [android.widget.Button]  [265,252][320,307]  click=true
'New terminal'    [android.widget.Button]  [359,246][425,312]  click=true
''                [android.widget.EditText] [21,346][1060,2380] click=true   <- xterm 隐藏输入域
```

**双向真的通（截图 `_v4_term_after.png` 肉眼证据）**：

```
Windows PowerShell
Copyright (C) Microsoft Corporation. All rights reserved.

PS C:\sensorsdata\main\program\opencode-android-shell> echo
请为以下参数提供值：
InputObject[0]: |
```

读法（逐条）：

1. 落地的是**后端宿主的真实 PowerShell**（`C:\sensorsdata\...\opencode-android-shell` 就是后端跑的那个仓库）
   ⇒ **下行**（PTY → 隧道 → WebView → 屏幕）通；
2. 我用 `adb shell input text` 敲的 `echo` **出现在 PTY 的输入回显里** ⇒ **上行**（屏幕 → WebView → 隧道 → PTY）通；
3. PowerShell 对无参 `echo` 回了**交互式提示** `请为以下参数提供值：InputObject[0]:`
   ⇒ 不是回放、不是静态页，**shell 真的在等输入**。

（`echo` 后面的空格与参数没进去，是 `adb input text` 对空格的转义问题，不影响上述三条判定。）

### 3.3 N-3 判定

> ## **可用**
>
> **依据**：
> 1. 实现存在且是宿主侧代码 —— `LoopbackProxy.kt:516-565`，PTY 由**后端**提供，宿主只做 WebSocket 双向字节隧道；
> 2. 上游**确实依赖** —— 官方 UI 有 `Terminal` tab，且它必须经 `/api/pty/{id}/connect`；
> 3. 真机**实测双向通** —— 隧道日志指向 `/api/pty/pty_108587e65001462fLXGwgR45uN/connect`，
>    终端里出现后端真实 PowerShell 提示符，我键入的命令被 shell 接收并产生交互式响应，
>    `Terminal 1` / `Close terminal` / `New terminal` 三个控件都在。

**未覆盖的细节（如实标注）**：本轮只验了**单条既有 PTY 会话**的连通与交互；
`New terminal`（新建第二个 PTY）与 `Close terminal`（关闭、连带隧道回收）**没有逐一点过**。
按"没验证不写成通过"的要求，这两项**不计入本次判定**。

---

## 4. 收尾复核输出（原文）

**谁起谁关**：本轮起 2 台（5990、6000），关 1 台（6000）；5990 自行静默退出（§5.1），已确认无残留。

```
=== PRE-KILL identity check (AVD name must be mine) ===
pid=42748 avd=OC_Deliver_R2 port=6000 MINE=True
pid=41612 avd=OC_Deliver_R2 port=6000 MINE=True

=== reset geometry overrides ===
$ adb -s emulator-6000 shell wm size reset
$ adb -s emulator-6000 shell wm density reset
--- verify reset ---
Physical size: 2560x1600
Physical density: 320                       <- 已无 Override 行

=== KILL my instance (serial-scoped only) ===
$ adb -s emulator-6000 emu kill
OK: killing emulator, bye bye
OK

=== VERIFY 1: no OC_* emulator/qemu processes ===
emulator/qemu process count = 2             <- 拆卸瞬时辅助进程
OC_* residual = 0
=== VERIFY 2: ports 5990 / 6000 not listening ===
port 5990 LISTEN = 0
port 6000 LISTEN = 0

=== VERIFY 3: adb devices ===
List of devices attached                        <- 空

=== VERIFY 4: backend 4095 untouched ===
LocalAddress LocalPort OwningProcess
------------ --------- -------------
127.0.0.1         4095          3748            <- 与开轮前同一 pid，全程未起停
```

25 秒后复查（确认辅助进程已自行退出）：

```
=== recheck: emulator/qemu processes ===
emulator/qemu process count = 0
OC_* residual = 0
port 5990 LISTEN = 0
port 6000 LISTEN = 0
=== adb devices ===
List of devices attached
=== gradle lock ===
False
```

- **故意保留：无。**（无任何 AVD/实例被故意留在后台）
- 未执行 `adb kill-server`；未用 `-a` / 通配符；只按 serial 关。
- 用户 AVD 定义（`MCRemote_*` / `DSH_*` / `T7x_*` 等）**一个都没碰** —— 本轮它们也没连在本机 adb 上。
- 第一次 VERIFY 里的 `process count = 2` 是 `emu kill` 自拉的**瞬时拆卸辅助进程**，
  按 `HOUSEKEEPING.md` 规则 2（判据 `-avd (OC_*|Medium_Desktop_B)`）**不属于我们的 AVD**，故未再 kill；
  25 s 后自然归零，已复查为 0。

---

## 5. 未闭环 / 风险（如实列出）

### 5.1 模拟器静默退出 1 次（`emulator-5990`）

现象：正在会话页准备找 composer 附件按钮时，`adb` 报 `device 'emulator-5990' not found`，
`adb devices` 空、`emulator/qemu` 进程数 0、端口 5990 无监听 —— 整个实例**无声消失**。

- 模式与历史轮一致（`HOUSEKEEPING.md` §F4 / R2 / G2 轮都记过同一现象），
  共同特征是**几何高度 2400 > 物理面板高度 1600**。
- **本轮不代其它轮次下因果结论**，只如实记录本轮这一次。
- 影响：发生在 N-4 开始前，**N-3 与任务 1 的证据在那之前已全部取完**；
  N-4 全部改在 `emulator-6000` 上重做并完成。
- 规避：串行跑、同一时刻只有一个 adb 客户端（沿用 G2 轮经验）。

### 5.2 保存/下载（`saveContent`）**未闭环**

见 §2.3。代码在包内、官方 UI 不可达、上游不调。**要闭环需要 Lead 决策**（本轮未改任何代码）：
- 若认为该能力必须有 ⇒ 需要一条可注入通道（release 包的 debug 钩子 / devtools），或上游加下载 UI；
- 若认为不需要 ⇒ 建议在 A4 台账上把这一项从"待验"改成"上游不需要"，避免每轮重复撞墙。

### 5.3 口令播种链路本轮**未复验**

`passwordPresent=true` 来自 **P6 轮**在同一 AVD 数据分区写入的 `EncryptedSharedPreferences`。
本轮**没有**走过 `showSetupDialog` 原生对话框（`WebHostActivity.kt:335-338` 那条分支）。
⇒ "首次安装 → 输口令 → 起代理"这条**冷启动首装路径本轮未被覆盖**。
（`upload` 能成功也间接说明口令有效 —— 代理转发时补的 Basic 认证被后端接受了。）

### 5.4 省电策略缺正向对照

见 §1.5 局限 2。当前只能证明"没被持有 + 清理路径跑过"，
**无法**观测"置位 → 清除"的状态翻转，因为 `setKeepScreenOn` 无生产调用者（死代码）。
若产品要求强证据，需要页面真的发起一次 `setKeepScreenOn(true)` 再切后台 —— 同样卡在 §2.3 的注入门禁。

### 5.5 release 包签名是 Android Debug 证书

见 §1.2。`CN=Android Debug` / SHA-256 `e86b7006…` / 仅 v2 方案。
**能装、能跑、官方 UI 正常**，但**不应作为对外分发的最终包**。

### 5.6 未碰过的目录

`app/**`、`webapp/src/**`、`_upstream/**`、`.github/**` **全程只读、未修改**
（本轮对这些路径只做了 `grep` / `read` 取证）。
**没有发现"必须改代码才能修"的问题需要停下报告** —— N-4 保存步的不可达是**产品范围问题**（上游没这功能），
不是 bug，故按 §2.3 记录为"测不到"并交 Lead 决策，未动代码。

---

## 6. 证据文件清单（本轮产出，均在 `research/web/`）

| 文件 | 内容 |
|---|---|
| `_v4_ui.png` | 官方 UI 首屏（236,350 B）—— 身份检查 |
| `_v4_n4_saf.png` / `_v4_n4_saf2.png` | composer `+` 菜单 / SAF 选择器已起 |
| `_v4_n4pick.xml` / `_v4_n4drawer.xml` / `_v4_n4dr2.xml` | SAF 选择器可访问性树（`v4_n4_probe.txt` 可见） |
| `_v4_n4_picked.png` | **选完后页面真的拿到文件**（chip `v4_n4_probe.txt / TXT`） |
| `_v4_n4_typed2.png` | 附件 chip + 文字同框 |
| `_v4_n4_sent.png` | 发送后 |
| `_v4_term_before.png` / `_v4_term_after.png` | **PTY 终端 + 敲入命令后的 PowerShell 交互响应** |
| `_v4_n4b.xml` / `_v4_sess1.xml` / `_v4_n4files.xml` / `_v4_n4file.xml` / `_v4_n4more.xml` | 会话页 / Files tab / More options 的可访问性树（找保存入口的全部尝试） |
| `_v4_boot.log` / `_v4_boot2.log` | 模拟器启动日志 |

---

## 7. 一句话结论

- **当前 release 包能装上并渲染官方 UI** —— 能。md5/签名/安装/身份检查全过，官方会话列表真实渲染且可交互。
- **WebView 预检放行** —— 是，`J.g1.preflightOk t=336ms major=124 unknown=false`（WebView 124.0.6367.219 ≥ 105）。
- **省电策略在位** —— 是，`onPause` 清理路径实跑（`P6.onPause prunedStreaming=1`），事后全机 `KEEP_SCREEN_ON` 命中数 0；但**无正向对照**（该桥是死代码）。
- **首屏** —— `M.g1.firstContent` **1033 ms**（`Z.firstDraw` 427 ms；`am start -W` 593 ms）。
- **N-4** —— 选择文件 **PASS**（含取消不崩）／保存下载 **测不到**（上游不可达 + release 无注入通道）／上传 **PASS**（后端 marker FOUND，模型逐字复述文件内容）。
- **N-3 PTY** —— **可用**（后端提供，宿主 WebSocket 隧道；`/api/pty/{id}/connect` 实测双向通）。
