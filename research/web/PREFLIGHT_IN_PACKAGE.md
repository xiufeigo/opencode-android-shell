# N-6 核查报告：WebView 预检到底在不在交付的 APK 里？

> **轮次**：V3 裂缝核查（核实「预检未入包」是否成立）
> **日期**：2026-10-05 02:48 – 03:05 CST
> **结论一句话**：**裂缝不成立 —— 预检在包里，而且两条分支都在设备上被证到了。**
> `PARITY_LEDGER_A4.md` 的 N-6「release APK 内不存在该代码」是对**一个已被替换掉的旧产物**下的判断，不是当前交付物的状态。

---

## 0. 判定摘要

| 问题 | 答案 | 证据级别 |
|---|---|---|
| 预检在 release APK 里？ | **在** | dex 反汇编（`dexdump -f`）读到类/方法/常量真值 |
| 预检在 debug APK 里？ | **在** | 同上 |
| 诊断页资产在包里？ | **在，且与仓库 md5 逐字节相同** | 4/4 文件 md5 比对 |
| 阈值 105 / 超时 12000 在包里？ | **在，是真常量不是字符串** | `value : 105` / `value : 12000` |
| 预检在设备上被调用了吗？ | **是，两条分支都跑到** | logcat 原始输出 + 截图 |
| 需要重建吗？ | **不需要**（条件不成立） | 见 §3 |

---

## 1. 机械核实：**读包**，不是读源码

### 1.1 取的交付物（md5 与派单给定值一致）

```
research/deliver/opencode-shell-web-release-nor8.apk
  Length = 3961217   LastWriteTime = 2026/10/5 01:29:53
  MD5    = 7B578CEEC3D7E35E2FFFA4DA67FE2D3B      <- 与派单给定 7b578cee… 一致

research/deliver/opencode-shell-web.apk   (debug)
  Length = 5145604   LastWriteTime = 2026/10/5 01:30:43
  MD5    = B716CBEB4C66E55FCB0F497640AE5E0C      <- 与派单给定 b716cbeb… 一致
```

### 1.2 解包

用 `System.IO.Compression.ZipFile` 只抽 `classes*.dex` 与 `assets/www/**`（未落全套，够用且不污染仓库）：

```
research/audit/preflight_unpack/rel/classes.dex       8765764
research/audit/preflight_unpack/rel/classes2.dex       960316
research/audit/preflight_unpack/rel/assets/www/index.html      16000
research/audit/preflight_unpack/rel/assets/www/oc-host.js      13804
research/audit/preflight_unpack/rel/assets/www/oc-perf.js       3307
research/audit/preflight_unpack/rel/assets/www/oc-preflight.js  2934
research/audit/preflight_unpack/dbg/classes.dex      10745744
research/audit/preflight_unpack/dbg/classes2.dex       124680
research/audit/preflight_unpack/dbg/classes3.dex        56632
research/audit/preflight_unpack/dbg/classes4.dex       105356
research/audit/preflight_unpack/dbg/classes5.dex       834660
research/audit/preflight_unpack/dbg/assets/www/*  (同上 4 个文件)
```

### 1.3 检索命令（可复现）

```powershell
# (a) 从 dex 抽 ASCII 字符串，逐符号计数
function Get-DexStrings($files){ /* 逐字节 0x20-0x7e 累积，>=4 字符记一条，格式 "文件名|串" */ }
$rel=Get-DexStrings (Get-ChildItem research\audit\preflight_unpack\rel\*.dex | % FullName)
$dbg=Get-DexStrings (Get-ChildItem research\audit\preflight_unpack\dbg\*.dex | % FullName)

# (b) 符号计数
foreach($p in @('WebViewPreflight','parseMajor','preflight','MIN_MAJOR','LOAD_STALL_TIMEOUT_MS',
                 'isTooOld','stallVerdict','diagnosticUrl','loaderror','rendercrash','oc-preflight')){
  # 对 rel/dbg 两份分别 Select-String -SimpleMatch 计数
}

# (c) 取常量真值 + 类定义（比字符串检索更硬：直接读 dex 的 static field value）
$dd="$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\dexdump.exe"
& $dd -f research\audit\preflight_unpack\rel\classes.dex > rel_dexdump_f.txt
```

### 1.4 命中原文

**符号计数（RELEASE / DEBUG）：**

| 符号 | RELEASE | DEBUG |
|---|---|---|
| `WebViewPreflight` | **26** | 39 |
| `Lcom/xiufeigo/opencodeshell/web/host/WebViewPreflight;` | **1** | 2 |
| `parseMajor` | **1** | 5 |
| `isTooOld` | **1** | 2 |
| `stallVerdict` | **1** | 2 |
| `diagnosticUrl` | **3** | 8 |
| `MIN_MAJOR` | **1** | 1 |
| `LOAD_STALL_TIMEOUT_MS` | **1** | 1 |
| `rendercrash` / `loaderror` | 1 / 2 | 1 / 3 |
| `oc-preflight` | 2 | 2 |

**RELEASE dex 里命中原文（逐行）：**

```
classes.dex|?()Lcom/xiufeigo/opencodeshell/web/host/WebViewPreflight$Reason;
classes.dex|(Ljava/lang/Integer;Ljava/lang/String;Lcom/xiufeigo/opencodeshell/web/host/WebViewPreflight$Reason;ILjava/lang/Object;)Ljava/lang/String;
classes.dex|c(Ljava/lang/Integer;Z)Lcom/xiufeigo/opencodeshell/web/host/WebViewPreflight$Verdict$LoadOfficialUi;
classes.dex|6Lcom/xiufeigo/opencodeshell/web/host/WebViewPreflight;
classes.dex|parseMajor
classes.dex|isTooOld
classes.dex|stallVerdict
classes.dex|diagnosticUrl
classes.dex|diagnosticUrl$default
classes.dex|rendercrash
classes.dex|loadErrorVerdict
classes.dex|loaderror
classes.dex|9oc-preflight.js missing, first-content signal unavailable
classes.dex|www/oc-preflight.js
```

**类定义 + 常量真值**（`dexdump -f`，RELEASE `classes.dex`）—— 这一段是决定性的，它给出的是**编译后的常量值**，不是字符串：

```
Class descriptor  : 'Lcom/xiufeigo/opencodeshell/web/host/WebViewPreflight;'
Access flags      : 0x0011 (PUBLIC FINAL)
Superclass        : 'Ljava/lang/Object;'
Static fields     -
#1  name : 'LOAD_STALL_TIMEOUT_MS'   type : 'J'   access : 0x0019   value : 12000
#2  name : 'MIN_MAJOR'               type : 'I'   access : 0x0019   value : 105
#3  name : 'WORST_OBSERVED_CONTENT_MS' type : 'J' access : 0x0019  value : 3063
```

`source_file_idx : 26648 (WebViewPreflight.kt)` —— 类确实来自 `WebViewPreflight.kt`。
枚举 `WebViewPreflight$Reason` 的四个 wire 值（`version` / `loaderror` / `rendercrash` / `stalled`）在 dex 里齐全。

**调用点也在包里**（不是只有孤立的类）。RELEASE dex 的 `WebHostActivity` 侧方法名：

```
name : 'readWebViewVersion'            <- 读 WebView 版本
name : 'showDiagnostic'                <- 切诊断页
name : 'access$showDiagnostic'         <- companion 回调
name : 'installPreflightProbe'         <- 装 oc-preflight.js
name : 'onFirstContent' / 'onFirstContent$lambda$3' / 'onFirstContent$lambda$16'
name : 'retryFromDiagnosticPage'       <- 诊断页「重新加载」
type : '(Lcom/.../WebHostActivity$Companion;Lcom/.../WebViewPreflight$Reason;Ljava/lang/Integer;Ljava/lang/String;Ljava/lang/String;ILjava/lang/Object;)Ljava/lang/String;'
type : '(Lcom/.../WebHostActivity;Lcom/.../WebViewPreflight$Reason;Ljava/lang/String;)V'
```

G1 运行期日志串（RELEASE dex 内）：

```
G1 webview version=
G1 showing diagnostic page reason=
G1 watchdog fired: no first content in 12000ms      <- 字面量 12000 就在包里
G1 retry: re-running preflight + launch
IDOCUMENT_START_SCRIPT unsupported -> g1 probe falls back to onPageStarted
J.g1.preflightOk / J.g1.blockedOldWebview / M.g1.diagnostic / M.g1.firstContent
oc-preflight.js missing, first-content signal unavailable
```

### 1.5 唯一「确实没接线」的一项（诚实记录）

```
name : 'onRenderProcessGoneNotWired'
```

这**在包里**，但它是一个**空实现**（`private fun onRenderProcessGoneNotWired() = Unit`）。
即：`RENDER_CRASH` 这个 `Reason` 与 `rendercrash` 这个 wire 值都存在，但**没有真实调用点**。
⇒ 这是**「接线没写」**，不是「代码没进包」，两者性质不同。详见 §6。

---

## 2. assets 诊断/兜底页：与仓库逐字节相同

方法同 R2（比 md5）：

```
REL  index.html       APK=72C62DC5  REPO=72C62DC5  SAME
REL  oc-host.js       APK=BF542769  REPO=BF542769  SAME
REL  oc-perf.js       APK=11B30F25  REPO=11B30F25  SAME
REL  oc-preflight.js  APK=BD5A2DD8  REPO=BD5A2DD8  SAME
DBG  index.html       APK=72C62DC5  REPO=72C62DC5  SAME
DBG  oc-host.js       APK=BF542769  REPO=BF542769  SAME
DBG  oc-perf.js       APK=11B30F25  REPO=11B30F25  SAME
DBG  oc-preflight.js  APK=BD5A2DD8  REPO=BD5A2DD8  SAME
```

（仓库源：`webapp/src/main/assets/www/**`；完整 md5 例如 `oc-preflight.js = bd5a2dd8…`）
**8/8 SAME**。诊断页资产是当前源码版本，不是陈旧副本。

---

## 3. commit 与构建时点：N-6 是对**旧产物**下的判断

```
cd088b3 | 2026-10-05 00:40:07 | feat(web): a readable failure page, a signed release, ...
         ^ 首次加入 WebViewPreflight.kt 与 assets/www/oc-preflight.js（--diff-filter=A 确认）

release APK 构建于 2026-10-05 01:29:53   （晚于 cd088b3 约 50 分钟）
debug   APK 构建于 2026-10-05 01:30:43

9b4d7e8 | 2026-10-05 01:58:11 | build(web): rebuild the deliverables so they actually contain ...
         ^ R2 轮记录这次重建
```

`DELIVER_REBUILD_R2.md:120` 记录了替换关系：

| | 旧 | 新 |
|---|---|---|
| release | 3,948,209 B / `9ab8ee300027f9847c2b220bb26f2b98` | **3,961,217 B / `7b578cee…`**（+13,008 B，通知修复） |
| debug | 8,636,901 B / `8dcffc57…` | 5,145,604 B / `b716cbeb…` |

**关键**：`PARITY_LEDGER_A4.md` 的 N-6 写的是「**release APK（3,948,209 B）内不存在该代码**」。
3,948,209 B 正是**已被 01:29 那次重建替换掉的旧 release**。

⇒ **N-6 判定所依据的产物不是当前交付物。** 当前交付物（3,961,217 B / `7b578cee…`）**含**预检，已由 §1 读包与 §4 设备双证。

**「是哪一个 commit 之后没重建」这一问，前提不成立，因此没有这样的 commit。** 若硬要回答历史：预检自 `cd088b3`（00:40:07）入库后，**第一次构建就晚于它**，从没有出现过「预检入仓后没重建」的窗口。

### 3.1 构建后是否又有代码没进包？

构建（01:29）之后仓库有未提交改动：

```
 M webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt   | 53 ++++++
 M webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LoopbackProxy.kt | 101 ++++++
 M webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt | 14 +-
?? webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/PowerPolicy.kt   (新文件)
```

对 `WebHostActivity.kt` 的未提交 diff 做 `preflight|onRenderProcessGone|stallVerdict|diagnosticUrl` 检索：

```
命中行数 = 0
```

⇒ **预检相关代码在构建后没有再被改动**，交付物与 HEAD 的预检逻辑**无差异**。这批未提交改动是电源/代理/桥方向（`PowerPolicy.kt` 为新文件），与 N-6 无关。

---

## 4. 设备复验：两条分支都证到了

### 4.1 设备与环境

自建两台，端口 5940 已被他人占用故顺延：

| serial | AVD | 镜像 | 几何 | WebView | 用途 |
|---|---|---|---|---|---|
| `emulator-5960` | `OC_Preflight_V3` | `android-35;google_apis_playstore_tablet` + `pixel_tablet` | 1080×2400@420 | **124.0.6367.219** | 证**通过**分支 |
| `emulator-5980` | `OC_Preflight_WebView83` | `android-30;default` + `pixel_tablet` | 1080×2400@420 | **83.0.4103.120** | 证**拦截**分支 |

```
boot_completed = 1
wm size: Physical size: 2560x1600 / Override size: 1080x2400
wm density: Physical density: 320 / Override density: 420
webview: versionName=124.0.6367.219
reverse: host-18 tcp:4095 tcp:4095
backend 4095 LISTEN pid=3748    <- 全程只用 adb reverse，未起停后端
```

装的是**同一个 release 产物**（未重建）：

```
research/deliver/opencode-shell-web-release-nor8.apk
$ adb -s emulator-5960 install -r -d ...  ->  Performing Streamed Install / Success
$ adb -s emulator-5980 install -r -d ...  ->  Performing Streamed Install / Success
package: name='com.xiufeigo.opencodeshell.web.debug' versionName='0.1.0-webhost'
launchable-activity: name='com.xiufeigo.opencodeshell.web.WebHostActivity'
```

### 4.2 通过分支（WebView 124 ≥ 105）—— 原始 logcat

`emulator-5960`，`logcat --pid=4735` 原文：

```
10-04 18:56:33.874 I/OcPerf  ( 4735): E2.perfProbeRegistered t=302ms
10-04 18:56:33.875 I/OcPerf  ( 4735): E3.g1ProbeRegistered t=302ms
10-04 18:56:33.875 I/OcWebHost( 4735): G1 webview version=124.0.6367.219 major=124 min=105 tooOld=false
10-04 18:56:33.876 I/OcPerf  ( 4735): H.launch.enter t=304ms backend=http://127.0.0.1:4095 mode=gzip
10-04 18:56:33.888 I/OcWebHost/Proxy( 4735): listening on http://127.0.0.1:39931 -> http://127.0.0.1:4095
10-04 18:56:33.889 I/OcPerf  ( 4735): J.g1.preflightOk t=316ms major=124 unknown=false
10-04 18:56:33.905 I/OcPerf  ( 4735): J.loadUrlIssued t=333ms url=http://127.0.0.1:39931/
10-04 18:56:34.521 I/OcWebHost( 4735): web[LOG] ocFirstContent kind=raf at=631 @24
10-04 18:56:34.524 I/OcPerf  ( 4735): M.g1.firstContent t=952ms kind=raf at=null
10-04 18:56:34.739 I/OcPerf  ( 4735): L.onPageFinished t=1167ms url=http://127.0.0.1:39931/
```

看门狗状态（**未误触发**，即首屏内容信号真的解除��它）：

```
检索 "watchdog fired|G1 showing diagnostic|onRenderProcessGone" -> (none)
```

官方 UI 确实渲染（非白屏）：截图 **239,489 B**（高熵），且 logcat 里有 9 条 `/_assets/index-Dadcbstg.js`、`/_assets/desktop-native-*.js`、`/_assets/index-W_zVL-x7.css` 等经代理从 4095 取回。
检索 `assets/www/index.html|diagnostic` -> **0**（说明当前显示的是官方 UI，不是我们的兜底页）。

> ⚠️ **CDP 这一步没做成，如实记录**：`/proc/net/unix` 里**没有** `webview_devtools_remote`，
> 因为 WebView 调试开关是 `BuildConfig.DEBUG` 门控的，**release 包不开**。
> ⇒ release 上无法用 CDP 取 document title，我**没有**用它冒充身份确认；
> 改用「官方 UI 截图 + 官方资源清单 + 非兜底页」三条替代证据。

### 4.3 拦截分支（WebView 83 < 105）—— **这才是 N-6 真正要的那条路**

`emulator-5980`（`android-30`，`dumpsys webviewupdate` 实测 `Current WebView package (name, version): (com.android.webview, 83.0.4103.120)`），`logcat --pid=2962` 原文：

```
10-04 18:59:57.090 W/OcWebHost( 2962): DOCUMENT_START_SCRIPT unsupported -> g1 probe falls back to onPageStarted
10-04 18:59:57.091 I/OcWebHost( 2962): G1 webview version=83.0.4103.120 major=83 min=105 tooOld=true
10-04 18:59:57.103 W/OcWebHost( 2962): G1 showing diagnostic page reason=version detail=WebView 83.0.4103.120 < 105
10-04 18:59:57.103 I/OcPerf  ( 2962): M.g1.diagnostic t=254ms reason=version
10-04 18:59:57.118 I/OcPerf  ( 2962): J.g1.blockedOldWebview t=269ms major=83
10-04 18:59:57.431 I/OcPerf  ( 2962): L.onPageFinished t=582ms url=https://appassets.androidplatform.net/assets//www/index.html?r=version&m=83&v=83.0.4103.120&min=105&t=12000&d=WebView%2083.0.4103.120%20%3C%20105
```

这一条同时钉死了 4 件事：
`tooOld=true`（阈值判断在跑）→ `blockedOldWebview`（**没有**去加载官方 UI）→
`showing diagnostic page reason=version`（切了诊断页）→
URL 里带真实读数 **`m=83` / `min=105` / `t=12000`**（阈值与超时确实在包里参与拼参）。

**诊断页真的渲染了**（截图 492,130 B），页面上是：

```
这个 WebView 太旧，打不开 OpenCode
当前 WEBVIEW 版本号（真实读数）  83.0.4103.120（主版本 83）
本壳要求                         主版本 ≥ 105；首屏看门狗 12000 ms
技术细节（提 ISSUE 时贴这段）   WebView 83.0.4103.120 < 105
[ 重新加载 ]                     会重新检查 WebView 版本并重连
能否 FETCH 本地资源（同源 FETCH 测试）  YES — 同源 fetch ./oc-host.js = 12035 bytes
```

⇒ 顺带**关掉了 N-6 的第二条子指控「白屏诊断页未渲染」**：诊断页在本仓 APK 里渲染正常，且带真实读数。
（`K2.onReceivedError ... /favicon.ico code=-2 main=false` 是诊断页自己 favicon 的子资源失败，
`main=false` 恰好证明 `loadErrorVerdict` 的「只管主文档」设计按预期工作。）

---

## 5. 重建：**未执行（条件不成立）**

派单任务 2 的触发条件是「**若确不在包里** ⇒ 重建」。§1/§4 证明**在包里**，
故 **不重建、不换 md5、不改体积**。

当前交付物（保持不变，且**已确认含 WebView 预检**）：

| 产物 | 体积 | MD5 | 签名 |
|---|---|---|---|
| `opencode-shell-web-release-nor8.apk` | **3,961,217 B** | `7b578ceec3d7e35e2fffa4da67fe2d3b` | 共享 debug key 签名（指纹见 `DELIVER_REBUILD_R2.md:161/177`，本轮未改动故未重取） |
| `opencode-shell-web.apk`（debug） | **5,145,604 B** | `b716cbeb4c66e55fcb0f497640ae5e0c` | 同上 |

### 5.1 交接文档：建议改动（**我没有代改，见说明**）

`HANDOFF_WEB_SHELL.md` 在**仓库根目录**，而本轮写权限是 `research/web/**`、`research/audit/**`、`research/**`。
⇒ **我不动它**，只把可直接粘贴的一行放这里，由 Lead 采纳：

> 交付物小节中，`opencode-shell-web-release-nor8.apk` 一行改为：
> `3,961,217 B` / `7b578ceec3d7e35e2fffa4da67fe2d3b` / **已含 WebView 预检**（`WebViewPreflight` 阈值 105 / 看门狗 12000 ms，2026-10-05 读包 + 双分支设备复验）

同时建议把 `PARITY_LEDGER_A4.md` 的 N-6 状态从 `OPEN` 改判为
**「原判基于已被替换的旧 release（3,948,209 B / `9ab8ee30`），当前产物含预检，已双证」**。

---

## 6. 另外 6 条：是不是「裂缝级」？

判据（本轮沿用）：**裂缝级 = 属于「验证过的东西没进包 / 没上线」，即交付物与被验证对象不是同一个。**

| # | 事项 | 是不是裂缝级 | 性质与优先级 | 依据 |
|---|---|---|---|---|
| **N-1** | 通知投递策略（M13 静音门控、A6 补投） | **不是** | **P2 产品决策，非工程裂缝**。宿主桥已打进包、通知真进系统、点击回会话已用真手指卡片验证。剩下的是「有 tab 打开则静音」的产品取舍 + A6「立即放弃 vs 延迟复查」的**行为策略差异**（本仓选对齐上游 `if (document.hasFocus()) return`）。**代码在包里且已验证**，差的是「要不要换个策略」，不是「没进包」 | `BRIDGE_ACCEPTANCE.md:76-106`；`NOTIFICATION_CLICK_CLOSE.md:185-204` |
| **N-2** | 相机权限 | **不是（但有「明示」义务）** | **P2**。scoped grep `onPermissionRequest`/`RESOURCE_VIDEO_CAPTURE`/`CAMERA` 零命中，manifest 也无相机权限声明 ⇒ 这是**从未实现**，不是「实现了没进包」。`HostWebChromeClient` 本身在包里。需产品决定：接相机，还是明示「移动端不支持扫码/图库/手输」 | `WebHostActivity.kt:907`；`scanner.tsx:33` |
| **N-3** | 终端 PTY / WebSocket 通道（R17 通道半边、U24 `pty.exited` 链） | **不是**（但是**未测**） | **P1，但属「未测」不是「未进包」**。终端面板由官方 UI 提供，回环代理已跑通 ⇒ 能力在包内。缺的是**设备级验证**（`pty.exited` 恢复链、SSE 断连 soak）。性质上是「测得不够」，须与 N-6 严格区分：**不能说它有裂缝** | `ROUTE_A_WIRING.md:3,109-110,203-205` |
| **N-4** | 文件选/存/上传 | **不是（但它是最大的未测面）** | **P1，「未测」而非「未进包」**。`pickFile`/`saveContent`/`onShowFileChooser` **代码在包里**，但成功路径一次没在设备上跑过（只验过取消）。编辑器强依赖它 ⇒ **优先级最高的是补设备验证**，不是改代码 | `WebHostBridge.kt:95,109`；`BRIDGE_ACCEPTANCE.md:141-162,439-444` |
| **N-5** | 错误态兜底 / 上下文超限 | **不是** | **P3**。A7 的 UI 半边在路线 A 下消失；错误态语义属**接入判断**。官方 UI 自带 `session-error.tsx`。无需宿主兜底，只需确认直接跳转。**没有「已验证未进包」的成分** | `PARITY_GAP_V2BASELINE.md:156` |
| **N-7** | 剪贴板 | **不是** | **P3，且定性未完成**。`ClipboardManager` 零命中 ⇒ 官方 UI 走 WebView 的 `navigator.clipboard`；回环 `127.0.0.1` **是否算 secure context 未核对**。这是**待核对**（一个 1 分钟的实测定论），不是裂缝 | scoped grep 零命中 |

### 6.1 结论

**其它 6 条里没有一条属于 N-6 那种「验证过但没进包」的裂缝级。**
其中 **N-3 / N-4 是「在包里但没在设备上验过」**——这是本仓另一种真实风险（*未测* 而非 *未进包*），
建议 Lead 把这两条按**验证欠账**而非**交付物裂缝**来排期；**N-4 优先**（编辑器强依赖）。

### 6.2 本轮唯一确认的「接线没写」（不是入包问题）

`onRenderProcessGone` **确实未接线**，源码里就是空函数，dex 里方法名 `onRenderProcessGoneNotWired` 也在。
⇒ `RENDER_CRASH` 路径**不会**被触发。这条与 N-6 同源于 `cd088b3` 的诚实记录，
**但性质是「少实现了一条兜底」，不是「实现了没打进去」**。
按派单约束（不许碰 `webapp/src/**`），本轮**只记录、不动手**：

> 若要补：在 `HostWebViewClient` 加
> `override fun onRenderProcessGone(view: WebView, detail: WebViewRenderProcessDetail): Boolean`，
> 判 `detail.didCrash()` 走 `showDiagnostic(Reason.RENDER_CRASH, …)`。
> 需先解决源码注释里记的编译期解析失败（`Unresolved reference 'WebViewRenderProcessDetail'`），
> 根因未核对（疑与本模块 `compileSdk 35` 下的 android.jar 可见性有关）。**属改代码，另行派单。**

---

## 7. 收尾复核（原文照抄）

```
=== 1. 关闭前 reset 几何（自建两台） ===
  emulator-5960 size: Physical size: 2560x1600
  emulator-5960 density: Physical density: 320
  emulator-5980 size: Physical size: 2560x1600
  emulator-5980 density: Physical density: 320
        （已无 Override 行）

=== 2. 按 AVD 名确认归属（必须 MINE=True 才动） ===
  pid=20904 avd=OC_Preflight_V3        port=5960 MINE=True
  pid=44108 avd=OC_Preflight_WebView83 port=5980 MINE=True

=== 3. adb emu kill（只按 serial，不用 -a / 通配符） ===
OK: killing emulator, bye bye          <- emulator-5960
OK: killing emulator, bye bye          <- emulator-5980

=== 4. 首次复核：emulator/qemu 进程 ===
process count = 4
  pid=27672 name=emulator.exe avd=(no -avd)
  pid=57164 name=emulator.exe avd=(no -avd)
  pid=51060 name=emulator.exe avd=(no -avd)
  pid=14524 name=emulator.exe avd=(no -avd)
        ↑ 四个都**没有 -avd 参数** ⇒ 是 `emu kill` 自拉的瞬时拆卸辅助进程、**不是 AVD**。
          按 HOUSEKEEPING §一.2 判据（`-avd (OC_*|Medium_Desktop_B)`）**不属于我**，故未 kill。

=== 5. 首次复核：我的 AVD 残留 ===
  OC_Preflight_V3        residual = 0
  OC_Preflight_WebView83 residual = 0

=== 6. 首次复核：端口 ===
  port 5960 LISTEN = 0
  port 5980 LISTEN = 0

=== 7. 30s 后最终复核 ===
emulator/qemu process count = 0
  OC_Preflight_V3        residual = 0
  OC_Preflight_WebView83 residual = 0
  OC_Deliver_R2          residual = 0
  port 5960 LISTEN = 0
  port 5980 LISTEN = 0
  port 5940 LISTEN = 0
  adb devices -> List of devices attached        （空；**全程未执行 adb kill-server**）
  backend 4095 LISTEN pid=3748                   （**全程未起停后端**，收尾仍在监听）
```

⚠️ **一处如实记录、不代其它轮次下结论**：起手时 `emulator-5940`（`OC_Deliver_R2`，pid 27972/48996）**在运行**，
本轮收尾时它已不在进程表里、`port 5940 LISTEN = 0`。
本轮**只**对 `emulator-5960` 与 `emulator-5980` 两个 serial 发过 `emu kill`，**原理上无法波及 5940**；
消失原因**未从本轮日志得出因果**（可能是其它轮次自行收尾，也可能是自行退出），**如实记录**。
同理，起手时在列的用户设备（`DSH_*` / `MCRemote_*`）当时**并不在线**（`adb devices` 起初只有 5940），
本轮启动的两台也**全程未对它们发过任何命令**。

**我碰过的 serial（全部）**：`emulator-5960`（自建）、`emulator-5980`（自建）。
**起手时已存在、我全程未碰**：`emulator-5940`（`OC_Deliver_R2`，pid 27972/48996）——
它是 `OC_*` 但**不是本轮起的**，可能正被其它轮次使用，故按「谁起谁关」不动它。

**故意保留：无。**

---

## 8. 我碰过的文件 / 权限自证

新增（全部在授权目录内）：

```
research/audit/preflight_unpack/rel/classes.dex, classes2.dex, assets/www/*
research/audit/preflight_unpack/dbg/classes*.dex, assets/www/*
research/audit/preflight_unpack/rel_dex_strings.txt, dbg_dex_strings.txt
research/audit/preflight_unpack/rel_dexdump_f.txt
research/audit/preflight_unpack/boot5960.ps1, boot5980.ps1
research/audit/preflight_unpack/n_rows.txt
research/audit/preflight_unpack/out5960/{fingerprint.txt, logcat_*.txt, official_ui_release.png}
research/audit/preflight_unpack/out5980/{fingerprint.txt, logcat_wv83.txt, diagnostic_page_wv83.png}
research/web/PREFLIGHT_IN_PACKAGE.md      <- 本报告
```

新建 AVD（磁盘定义，供下轮复用/删除）：`OC_Preflight_V3`、`OC_Preflight_WebView83`（均属 `OC_*`，可按规矩删）。

**未碰**：`app/**`、`webapp/src/**`、`_upstream/**`、`.github/**`，以及仓库根的 `HANDOFF_WEB_SHELL.md` / `PARITY_LEDGER_A4.md`（改动建议见 §5.1，等 Lead 采纳）。
**后端 4095 未起停**；**未执行 `adb kill-server`**；**未用 `-a`/通配符**。

---

## 9. 未闭环 / 测不到的（如实列出）

1. **release 上无法 CDP**：`webview_devtools_remote` socket 不存在（`BuildConfig.DEBUG` 门控）。
   官方 UI 身份由**截图 + 官方资源清单 + 非兜底页**三条替代证据确认，**未取到 document title**。
2. **`onRenderProcessGone` 仍未接线**（§6.2）——属改代码，超出本轮权限，只给方案。
3. **12 s 停滞看门狗本身未被单独触发过**：本轮只证明了「有内容时被正确解除」
   （`M.g1.firstContent t=952ms`，且 `watchdog fired` 零命中），
   **没有**构造出「12 s 零内容」去证它会真的切诊断页。`STALLED` 分支**未在设备上正面证到**。
4. **`pty.exited` / `saveContent` / `onShowFileChooser`** 等仍属 N-3/N-4 的未测面，本轮**未碰**。
5. **N-7 的 secure context 判断未做**。
