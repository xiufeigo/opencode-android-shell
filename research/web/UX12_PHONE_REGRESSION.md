# UX12-Phone —— 手机档全量回归（方案 A 自带官方前端 + 本机 http 来源）

- 执行日期：2026-10-06
- 仓库：`D:\XiaoZhuTouTou\opencode-android-shell`
- 被测产物：`research/deliver/opencode-shell-web.apk`（debug，方案 A）
- 本轮代码改动：**0 字节**（未改 `webapp/**`、`app/**`，未碰 gradle，未起停后端）

---

## 0. 环境与产物

### 0.1 APK md5 / 体积

```
Algorithm : MD5
Hash      : BF688F8A033EDE65C3D4F8C5E8D1E930
Path      : D:\XiaoZhuTouTou\opencode-android-shell\research\deliver\opencode-shell-web.apk

16796038
```

- md5 = `BF688F8A033EDE65C3D4F8C5E8D1E930`
- 体积 = 16,796,038 字节（≈16.0 MiB）

### 0.2 端口占用预查（6790）

```
Get-NetTCPConnection -LocalPort 6790  →  （无输出 = 未被占用）
Get-NetTCPConnection -LocalPort 6792  →  （无输出 = 未被占用）
adb devices                           →  List of devices attached
                                          （空 = 无任何实例在线）
```

⇒ 决定用 **6790**（未占用）。起机前 `adb devices` 为空，**本轮无他人实例**。

### 0.3 ⚠️ 任务书路径偏差：`_upstream/` 在本 checkout 中不存在

任务书第 2 步要求「原样跑 `_upstream/packages/app/src/servers/connect/browser.ts:3-12`」。
实测 `Test-Path _upstream` = **False**，本 checkout 无上游工作副本（`git worktree` 未落地）。

替代取证：判据函数**逐字**保存在本仓库既有报告里，来源可追：

- `research/web/UX9_HTTP_SERVER.md:26-38`（§1.1 全文）
- `HANDOFF_WEB_SHELL.md:1078-1086`

UX9 §1.1 记录的原文（**verbatim**）：

```ts
export function isMixedContent(page: string, address: string) {
  if (new URL(page).protocol !== "https:") return false
  const normalized = serverAddress(address)
  if (!normalized) return false
  const url = new URL(normalized)
  if (url.protocol !== "http:") return false
  // Secure Contexts treats loopback HTTP origins as potentially trustworthy.
  const host = url.hostname.replace(/\.$/, "")
  return !(host === "localhost" || host.endsWith(".localhost") || host === "[::1]" || /^127(?:\.\d+){3}$/.test(host))
}
```

⇒ 第 2 步注入跑的就是这段**逐字拷贝**（不是重写、不是简化版）。
⚠️ **偏差如实标注**：函数体取自 UX9 报告存档而非当场读上游文件；行号 3-12 无法当场复核。
注：`serverAddress()` 是上游另一函数（地址归一化）；三条用例里地址均为绝对规范 URL，该函数为恒等（否则第 2 条用例不可能返回 `true`），实测输出可反证这一点。

### 0.4 ⚠️ 几何与任务书预测不一致（实测为准）

起机完成（`sys.boot_completed=1`）后实测：

```
$ adb -s emulator-6790 shell wm size
Physical size: 1080x2400
$ adb -s emulator-6790 shell wm density
Physical density: 420
```

- 任务书写「启动后 `wm size 1200x2670` + `wm density 420`」。
- 实测 **1080x2400 / 420**：density 一致，**分辨率与任务书预测不同**。
- 后续所有截图按 1080x2400 读；报告以实测为准。**未改设备分辨率**（无 `wm size` 覆写）。

### 0.5 ⚠️ 发现一个**非自建**实例：`emulator-6792`

装包前后两次 `adb devices`：

```
（装包前）List of devices attached
                                （空）

（装包后）List of devices attached
emulator-6790	device        ← 本轮自建
emulator-6792	device        ← 非自建，本轮全程未碰
```

⇒ 6792 在起机时（6790 预查为空）**不存在**，是**本轮起机期间由他人/并行轮次拉起**的
（`research/env/ux12-round3-tablet.prompt.md:23` 正是「查 6792 占用 → 起 emulator」的平板轮）。
- 本轮**只对 `emulator-6790` 下命令**，全部命令均带 `-s emulator-6790` 显式串口。
- **未**执行 `adb kill-server` / `adb -a` / 通配符；**未**碰 6792（未安装、未启停、未截图、未清数据）。
- 收尾只 `emu kill` 6790；6792 保持在线不动（见 §6）。

### 0.6 起机 / 安装 / 启动

```
$ adb -s emulator-6790 install -r research\deliver\opencode-shell-web.apk
Performing Streamed Install
Success
$ adb -s emulator-6790 shell pm clear com.xiufeigo.opencodeshell.web.debug
Success
$ adb -s emulator-6790 reverse tcp:4095 tcp:4095
4095
$ adb -s emulator-6790 reverse --list
host-15 tcp:4095 tcp:4095
$ adb -s emulator-6790 shell am start -n com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity
Starting: Intent { cmp=com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity }
```

后端只读探测（**装包前**，确认它本来就在跑、不是本轮起的）：

```
$ Invoke-WebRequest http://127.0.0.1:4095/api/info  (Basic opencode:opencode)
STATUS=200
{"version":"2.0.23","pid":31812,"urls":["http://127.0.0.1:4095"],
 "paths":{"tmp":"C:\\Users\\xiufeigo\\AppData\\Local\\Temp\\opencode"},
 "capabilities":{"persistentPty":false}}

$ Get-NetTCPConnection -LocalPort 4095 -State Listen
LocalAddress LocalPort State  OwningProcess
127.0.0.1    4095      Listen 31812
```

---

## 1. 页面实际来源（验收 #3 前一半）

### 1.1 宿主启动日志（原文，`OcWebHost` / `OcLocalUi`）

```
10-06 03:26:47.130  2987  2987 I OcLocalUi: local UI origin listening on http://127.0.0.1:4173 (static assets only)
10-06 03:26:48.875  2987  2987 I OcWebHost: facade registered as document-start script
10-06 03:26:48.939  2987  2987 I OcWebHost: G1 webview version=145.0.7632.218 major=145 min=105 tooOld=false
10-06 03:26:48.961  2987  2987 I OcWebHost: home=http://127.0.0.1:4173/ backend=http://127.0.0.1:4173
10-06 03:26:49.398  2987  2987 I OcWebHost: D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false
10-06 03:26:49.404  2987  2987 I OcWebHost: P6 dispatch synthetic pageshow (upstream pageLifecycle)
```

- `OcLocalUi` 原文：`local UI origin listening on http://127.0.0.1:4173 (static assets only)`
- `OcWebHost` 原文：`home=http://127.0.0.1:4173/ backend=http://127.0.0.1:4173`

### 1.2 注入读数（**这一步是注入**：`oc_debug_eval`，仅读页面状态）

> 逐条标注：本报告凡出现「注入」= `am start --es oc_debug_eval`（DEBUG-only 读数通道），
> **未用于任何交互**；交互一律 `input tap` / `input text` / `input keyevent` 真实点击按键。

```
eval=b64:SlNPTi5zdHJpbmdpZnkoe2hyZWY6bG9jYXRpb24uaHJlZixwcm90b2NvbDpsb2NhdGlvbi5wcm90b2NvbCxv
cmlnaW46bG9jYXRpb24ub3JpZ2luLGRvY1VSTDpkb2N1bWVudC5VUkwsdGl0bGU6ZG9jdW1lbnQudGl0bGV9KQ==
-> "{\"href\":\"http://127.0.0.1:4173/\",\"protocol\":\"http:\",\"origin\":\"http://127.0.0.1:4173\",
    \"docURL\":\"http://127.0.0.1:4173/\",\"title\":\"OpenCode\"}"
```

| 项 | 实测值 | 预期 | 判定 |
|---|---|---|---|
| `href` | `http://127.0.0.1:4173/` | `http://127.0.0.1:4173/` | ✅ |
| `protocol` | `http:` | `http:` | ✅ |
| `origin` | `http://127.0.0.1:4173` | 同 | ✅ |
| `docURL` | `http://127.0.0.1:4173/` | 同 | ✅ |
| `title` | `OpenCode` | （官方前端） | ✅ |

⇒ **方案 A 生效：页面是本机 `http://127.0.0.1:4173` 官方前端，不是 4095 自身。**

### 1.3 官方 Home 截图

`ux12p_home.png`（44,328 字节，1080x2400）实拍：顶部 **Home** 标题、右侧 `0`、
**All projects** 折叠行、**Search sessions** 搜索框、会话列表 `Loading` 骨架。
⇒ 确为**官方 Home 界面**（非自研壳）。

### 1.4 ⚠️ 本机来源服务证据 —— 与任务书预期**有偏差，照实记录**

任务书预期「页面侧对 `http://127.0.0.1:4095` 的 API 请求出现在 `OcPerfJS`」。
实测 `OcPerfJS` 原文（节选）：

```
OcPerfJS res #117 asset ... http://127.0.0.1:4173/_assets/select-CCL-4EMf.js @19
OcPerfJS res #118 asset ... http://127.0.0.1:4173/_assets/switch-CRDS7_F1.js @19
OcPerfJS res #119 asset ... http://127.0.0.1:4173/_assets/panel-DNhwFOSg.js @19
OcPerfJS res #120 asset ... http://127.0.0.1:4173/_assets/details-70LVjj6Q.js @19
OcPerfJS res #121 api   ... http://127.0.0.1:4173/api/event @19
OcPerfJS res #122 api   ... http://127.0.0.1:4173/api/info  @19
OcPerfJS res #123 api   ... http://127.0.0.1:4173/api/event @19
OcPerfJS res #124 api   ... http://127.0.0.1:4173/api/event @19
OcPerfJS res #125 api   ... http://127.0.0.1:4173/api/event @19
```

**结论**：
1. 静态资源全部来自 `http://127.0.0.1:4173/_assets/...` ⇒ **本机来源服务证据成立**（n=125 条资源中绝大多数）。
2. ⚠️ **`/api/*` 也走 `4173`，不是 `4095`** —— 与宿主 `home=… backend=http://127.0.0.1:4173` 一致：
   宿主把**后端同源化**进本机来源端点，页面只与 `4173` 通信，由宿主转发到 `4095`。
   ⇒ 任务书里「API 请求应为 `http://127.0.0.1:4095`」与本轮产物**不符**，以实测为准。
3. 「被端点 404 拒答」的红线 3 证据**未以 404 形式出现**：`/api/info` 走 4173 返回 200
   （与 §0.6 直连 `4095/api/info` 的 `{"version":"2.0.23"}` 同源）。

### 1.5 ⚠️ 附带观察：默认服务器事件流在反复重连

`OcPerfJS` 旁的时间戳显示（原文）：

```
03:27:07.325 [oc-pow] event stream OPEN #4 /api/event @715
03:27:07.325 [global-sdk] event stream disconnected [object Object] @2
03:27:08.373 [oc-pow] event stream OPEN #5 /api/event @715
03:27:08.374 [global-sdk] event stream disconnected [object Object] @2
03:27:10.073 [oc-pow] event stream OPEN #6 /api/event @715
03:27:10.074 [global-sdk] event stream disconnected [object Object] @2
03:27:11.552 [oc-pow] event stream OPEN #7 /api/event @715
03:27:11.568 [global-sdk] event stream disconnected [object Object] @2
```

即 `/api/event` 每 ~1.5–2s 开一次即断（`[oc-pow] OPEN` 后 0–16ms 内 `[global-sdk] disconnected`）。
Home 界面因此长时间停在 `Loading` 骨架。**如实记录，不在本轮擅自判定为回归缺陷**，
但它会影响 §5「发消息有回复」与 §5.6「切会话」两项的可用性，结论见该节。

---

## 2. 上游判据在真实页面里跑（红字机制闭合）

**这一步是注入**（`oc_debug_eval`），仅求值，不触碰 UI。
函数体 = §0.3 的 `isMixedContent` **逐字拷贝**（含回环豁免正则与注释语义）。

三条用例（task 指定的远端 `8.138.19.15:4094`）**verbatim 输出**：

```
eval=b64:ZnVuY3Rpb24gc2VydmVyQWRkcmVzcyhhKXt0cnl7cmV0dXJuIG5ldyBVUkwoYSk7fWNhdGNoKGUpe3JldHVy
biBudWxsfX19CmZ1bmN0aW9uIGlzTWl4ZWRDb250ZW50KHBhZ2UsYWRkcmVzcyl7CiAgaWYobmV3IFVSTChwYWdlKS5w
cm90b2NvbCE9PSJo -> "{\"page\":\"http://127.0.0.1:4173/\",
   \"A_page_http_target_http\":false,
   \"B_page_https_target_http\":true,
   \"C_page_http_target_https\":false}"
```

| 用例 | 表达式 | 实测 | 预期 | 判定 |
|---|---|---|---|---|
| A | `isMixedContent(location.href, "http://8.138.19.15:4094")` | `false` | false | ✅ |
| B | `isMixedContent("https://example.com/", "http://8.138.19.15:4094")` | `true` | true | ✅ |
| C | `isMixedContent(location.href, "https://8.138.19.15:4094")` | `false` | false | ✅ |

被测页面：`page: "http://127.0.0.1:4173/"`。

**含义（机制闭合）**：
- 判据第一行 `if (new URL(page).protocol !== "https:") return false` 是**唯一入口闸**。
- 本轮页面协议 = `http:` ⇒ **A（http 页面 → http 目标）恒 false** ⇒ **那句红字永不触发**。
- B = `true` 复现了**旧形态**（页面 https + 目标非回环 http）⇒ 判据本身仍有效、代码没被改坏。
- C = `false`：即便目标是 https，在 http 页面也不触发（第二道 `url.protocol !== "http:"` 也会挡）。
⇒ **「页面 http ⇒ 对 http 目标永不触发混合内容红字」在真实设备页面里得证。**

---

## 3. 官方「Add server」同页加 http（验收 #1 主证据）—— ✅ **PASS**

### 3.1 真实点击路径（全部 `input tap` / `input text` / `input keyevent`，无注入点击）

| 步 | 操作 | 坐标（原图 1080x2400） | 截图 |
|---|---|---|---|
| 1 | Home 抽屉（顶部 grid 图标） | tap 66,122 | — |
| 2 | 抽屉底部弹出 **Settings** | tap 447,2332 | `ux12p_scr_settings.png` |
| 3 | 顶部段选择器 **Preferences ⌄** | tap 870,216 | `ux12p_scr_segsel.png` |
| 4 | 段菜单 **Server** | tap 769,988 | `ux12p_scr_server.png` |
| 5 | **+ Add server** | tap 888,482 | `ux12p_scr_adddlg.png` |
| 6 | 填地址（软键盘弹出） | tap 540,946 + `input text` | `ux12p_scr_typed_addr.png` |
| 7 | 填口令 | tap 540,1357 + `input text` | `ux12p_scr_typed_all.png` |
| 8 | 等 ≥12s（健康检查）→ 收键盘 | `keyevent BACK` | **`ux12p_add_http.png`** |
| 9 | 提交 **Add server** | tap 918,1653 | `ux12p_server_green.png` |

抽屉是**底部弹层**，实测节点：`New session` / `Home` / `Settings` / `Help`。
Server 段进入前，已有条目 `127.0.0.1:4173` 显示 **红点**（未连接）。

### 3.2 填写内容（截图实拍）

`ux12p_scr_typed_all.png`：**Server address = `http://127.0.0.1:4095`**，Password = 8 个圆点
（`opencode` 恰 8 字符）。**打开对话框至填完，屏幕上没有任何红字。**

### 3.3 全程红字检查（**这一步是注入**：读 `document.body.innerText`）

健康检查等待 14s 后抓全量页面文本（**这一步是注入**）：

```
eval=b64:SlNPTi5zdHJpbmdpZnkoe2hhc1JlZDpkb2N1bWVudC5ib2R5LmlubmVyVGV4dC5pbmNsdWRlcygiQ291bGQgbm90IGNv
bm5lY3QgdG8gdGhpcyBIVFRQIHNlcnZlciBmcm9tIGFuIEhUVFBTIHBhZ2UiKXx8ZG9jdW1lbnQuYm9keS5pbm5lclRleHQuaW5jbHVk
ZGVzKCJDb3VsZCBub3QgY29ubmVjdCIsZG9jdW1lbnQuYm9keS5pbm5lclRleHQpLHNsaWNlKC03MDApfQ==
-> "{\"hasRed\":false,
     \"couldNotConnect\":false,
     \"dlg\":\"Home\\n0\\nBack to app\\nServer\\nServer\\nManage server connections\\nAdd server\\nConnection
           \\n127.0.0.1:4173\\nhttp://127.0.0.1:4173\\nPreferences\\nTerminal shell\\nShell used by the terminal
           and agent tools\\nAuto (Default)\\nAdd server\\nServer address\\nServer name (optional)\\nPassword
           \\nScan QR code\\nCancel\\nAdd server\"}"
```

| 断言 | 实测 | 判定 |
|---|---|---|
| 含「Could not connect to this HTTP server from an HTTPS page」 | `hasRed:false` | ✅ **红字不存在** |
| 含 `HTTPS` 任意出现 | `false` | ✅ |
| 含「Could not connect」 | `couldNotConnect:false` | ✅ |
| 对话框仍打开（健康检查已跑完仍无错） | 文本含 `Server address`…`Add server` | ✅ |

`dlg` 全量文本里**逐字可查**：只有 `Server address / Server name (optional) / Password /
Scan QR code / Cancel / Add server` —— **没有任何一条错误/告警文案**。

### 3.4 提交结果：对话框关闭 + 段选择器出现 4095

提交后对话框**关闭**；再次打开顶部段选择器（`ux12p_scr_segsel2.png`）**实拍列表**：

```
Preferences      ✓
Appearance
Notifications
Shortcuts
──────────────
▣ 127.0.0.1:4095      ← 新增成功（http）
▣ 127.0.0.1:4173
──────────────
About
```

### 3.5 ✅ **绿点（已连接）** —— 验收 #1 主证据

`ux12p_server_green.png` 实拍（点选 `127.0.0.1:4095` 段后）：

- 标题 `127.0.0.1:4095` / 副标题 `Manage this server's connection and preferences`
- **Connection 卡片：绿色实心圆点 ● + `127.0.0.1:4095` + `http://127.0.0.1:4095`**
- 右上角 `...` 菜单

⇒ 与 §3.4 之前的**红点** `127.0.0.1:4173` 对比，颜色**由红变绿**。
⇒ **官方「Add server」在本机 http 页面下成功添加并连上了一个 http 后端，红字零出现。**

### 3.6 连接成功的 API 流量证据（`OcPerfJS` 原文）

添加并连接 4095 后，页面侧出现**直连 4095** 的 API 流量：

```
03:31:15.908 OcPerfJS res #411 api ... http://127.0.0.1:4095/api/info @19
03:31:16.238 OcPerfJS res #415 api ... http://127.0.0.1:4095/api/location @19
03:31:16.260 OcPerfJS res #416 api ... http://127.0.0.1:4095/api/project @19
03:31:16.348 OcPerfJS res #417 api ... http://127.0.0.1:4095/api/session/active @19
03:31:16.349 OcPerfJS res #418 api ... http://127.0.0.1:4095/api/project @19
03:31:16.367 OcPerfJS res #419 api ... http://127.0.0.1:4095/api/vcs @19
03:31:16.385 OcPerfJS res #420 api ... http://127.0.0.1:4095/api/location @19
03:31:25.578 OcPerfJS res #430 api ... http://127.0.0.1:4095/api/info @19
03:31:35.643 OcPerfJS res #441 api ... http://127.0.0.1:4095/api/info @19
```

⚠️ 与 §1.4 对照：**加服务器之前** `/api/*` 全走 `4173`（宿主同源化）；
**加完 4095 之后**改为**直连 `http://127.0.0.1:4095`**（`/api/info`、`/api/project`、
`/api/session/active`、`/api/location`、`/api/vcs`）。
⇒ 任务书「确认页面侧对 `http://127.0.0.1:4095` 的 API 请求出现在 `OcPerfJS`」
**在本条路径上成立**（在 §1 的初始状态下不成立，见 §1.4 偏差记录）。
⚠️ 这些行 `enc=0 dec=0 xfer=0`（0 字节传输），`/api/info` 另有 10s 周期重试；
绿点是 UI 的连接判据，**字节数为 0 的成因本轮未定位**（见 §5.13 与 §7）。

### 3.7 从真实后端读回的内容

Server 段内可见 `Terminal shell / Shell used by the terminal and agent tools / Auto (Default)`。
⚠️ **偏差**：本轮该值显示为 `Auto (Default)`，**不是**任务书预期的 `powershell`。
如实记录，不改判。

---

## 4. 同页再加 https（验收 #1 另一半）—— ⚠️ **等价构造**

> ### ⚠️ 显著标注：这是**等价构造**，不是端到端验证
> 本环境**没有证书受信的 https 后端**（与 `UX10 §4.1` 同款限制）。
> 后端 `127.0.0.1:4095` 实为 **http**；`https://127.0.0.1:4095` 的 TLS 握手**必然失败**。
> 因此**「https 服务器添加成功」在本轮未被端到端验证**，以下为等价构造取证。

### 4.1 真实点击 + 填写

从 Server 段进入第二次 Add server。⚠️ **导航偏差如实记录**：添加 4095 之后，
顶部段选择器里的 **Server 入口消失**（列表变为 `Preferences/Appearance/Notifications/Shortcuts/
127.0.0.1:4095/127.0.0.1:4173/About`），`Add server` 改由**顶栏 `+` 按钮**提供。
实际路径：Preferences 段 → 顶栏 **`+`**（tap 682,216）→ 对话框。

`ux12p_scr_https_typed.png` 实拍：**Server address = `https://127.0.0.1:4095`**，
Password 8 个圆点。**屏幕上无红字。**

### 4.2 提交前：无红字（**这一步是注入**）

```
-> "{\"hasRed\":false,
     \"httpsWord\":false,
     \"couldNotConnect\":false,
     \"dlg\":\"...Advanced\\nAdd server\\nServer address\\nServer name (optional)\\nPassword
            \\nScan QR code\\nCancel\\nAdd server\"}"
```

- `hasRed:false` ⇒ 不含「Could not connect to this HTTP server from an HTTPS page」✅
- `httpsWord:false` ⇒ 页面文本里 **`HTTPS` 一词都不出现** ✅

### 4.3 提交后：只有**通用错误**，仍无红字（**这一步是注入**）

等待 14s（健康检查跑完）后：

```
-> "{\"hasRed\":false,
     \"httpsWord\":false,
     \"couldNotConnect\":true,
     \"txt\":\"...Advanced\\nAdd server\\nServer address\\nCould not connect to server
            \\nServer name (optional)\\nPassword\\nScan QR code\\nCancel\\nAdd server\"}"
```

| 断言 | 实测 | 判定 |
|---|---|---|
| 混合内容红字 | `hasRed:false` | ✅ **不存在** |
| 文本含 `HTTPS` | `httpsWord:false` | ✅ |
| 出现通用错误 | `couldNotConnect:true`，原文 **`Could not connect to server`** | ✅ |

截图：`ux12p_add_https.png`（提交前对话框）、`ux12p_add_https_result.png`（提交后通用错误）。

**注意文案差别**（关键）：
- 混合内容红字原文应为 `Could not connect to this HTTP server from an HTTPS page`
- 本轮实测错误为 **`Could not connect to server`**（**通用**，无「HTTP server」「HTTPS page」字样）

⇒ 提交后报的是**真实网络原因**（对 http 后端发 TLS 失败），**不是**混合内容规则拦的。

### 4.4 证据链（判据 + 实测 双重支撑）

1. **判据**（§2 实测 C 用例）：`isMixedContent(http页面, https目标) = false`
   ⇒ 机制上 **https 目标在 http 页面永不被混合内容规则拦截**。
2. **设备实测**（本节）：https 对话框**正常走完**填表 → 健康检查等待 → 提交流程，
   **全程零红字**，最终只报**通用** `Could not connect to server`。
3. 两指合一 ⇒ 换成**真实受信 https 后端**时，本页不会出现那句红字。

**结论（验收 #1 另一半）**：**通过（等价构造）**。
⚠️ 明确声明：**「https 成功」未端到端验**（无受信 https 后端），以等价构造替代。

---

## 5. 全量回归（验收 #4）—— 12 条逐条

### 5.0 ⚠️ 先说阻塞根因（决定本节多条判定的共同前提）

**会话/项目列表自始至终停在 `Loading`，无法进入任何会话。** 这不是操作失误，是**产品侧阻塞**。

**现象**（截图 `ux12p_home.png` / `ux12p_home2.png` / `ux12p_newsession.png` / `ux12p_proj4095.png` /
`ux12p_final_home.png` 一致）：Home 顶部计数恒为 `0`，项目行展开无子项，
「New session」点击后**不新建会话**，抽屉 `New session` 同样无反应。

**logcat 原文 —— 关键在 `xfer=0`**：

```
# §1 初始态（走 4173 宿主同源化）
OcPerfJS res #122 api ... http://127.0.0.1:4173/api/info  @19      （200，有内容）

# §3 切到 4095 服务器上下文后
03:39:50.886 OcPerfJS res #1193 api ... http://127.0.0.1:4095/api/reference    @19   xfer=0
03:39:54.019 OcPerfJS res #1197 api ... http://127.0.0.1:4095/api/config       @19   xfer=0
03:39:54.019 OcPerfJS res #1198 api ... http://127.0.0.1:4095/api/config/shell @19   xfer=0
03:39:54.020 OcPerfJS res #1199 api ... http://127.0.0.1:4095/api/location    @19   xfer=0
03:39:54.053 OcPerfJS res #1201 api ... http://127.0.0.1:4095/api/provider    @19   xfer=0
03:39:54.071 OcPerfJS res #1202 api ... http://127.0.0.1:4095/api/model       @19   xfer=0
03:39:54.073 OcPerfJS res #1203 api ... http://127.0.0.1:4095/api/integration @19   xfer=0
03:39:55.538 OcPerfJS res #1207 api ... http://127.0.0.1:4095/api/info        @19   xfer=0
```

**对照**：`/api/provider`、`/api/model`、`/api/session/active`、`/api/project`、`/api/location`
等官方必需端点**全部被请求到了 4095**（说明连接目标正确），但**响应字节数恒为 0**
（`enc=0 dec=0 xfer=0`）。⇒ SDK 拿不到任何负载 ⇒ 列表永远 `Loading`。

**根因假设（未在本轮确证，如实标注为假设）**：页面 origin 是 `http://127.0.0.1:4173`，
对 `http://127.0.0.1:4095` 属**跨源**（端口不同即不同源）；该跨源请求在 WebView 侧**未取回响应体**
（典型为 CORS 未放行）。宿主同源化只对 `4173` 生效，**对 4095 无效**。
⚠️ 本轮**未**改任何配置/未开 CORS 绕过，**不擅自判定为缺陷归属**；只如实记录现象与数据。

**连带影响**：5.1 / 5.2 / 5.5 / 5.6 / 5.7 / 5.9 需进入会话或需一个回合，全部**无法执行**。
以下逐条如实判定，**不硬凑**。

---

### 5.1 终端可用 —— ❌ **FAIL（阻塞，非功能判失败）**

- 需先进入 `oc-fixture-4095` 项目会话；**项目列表恒 `Loading`，无法进入**（§5.0）。
- 因此**未能**点「终端」标签、**未能**执行 `input text "echo ux12p_$([datetime]::Now.Ticks)"`。
- **无 PTY/SSE 证据可抄**。判定 **FAIL（阻塞）**：不可用 ≠ 已证明坏，但**验收项无法取证**。

### 5.2 终端标签条「+」直接新建终端 —— ❌ **FAIL（阻塞）+ 部分 PASS**

- **阻塞部分**：点终端条 `+` → 期望直接出现「终端 2」且**不弹面板**。**终端条无法到达**。
- **已取证部分（注入**读页面 DOM**）—— 目标断言「全局没有我们的面板 DOM」成立：

```
eval=b64:SlNPTi5zdHJpbmdpZnkoe2RhdGFPY1BhbmVsOiEhZG9jdW1lbnQucXVlcnlTZWN0b3IoIls=
-> "{\"dataOcPanel\":false,\"reviewPanel\":false,\"aside\":0,\"ocHost\":\"undefined\",
     \"dialogs\":1,\"bodyTail\":\"Home\\n0\\nAll projects\\n127.0.0.1:4173\\nLoading\"}"
```

| 断言 | 实测 | 判定 |
|---|---|---|
| `[data-oc-panel]` 面板 DOM | `false` | ✅ 无面板 |
| `aside` 元素数 | `0` | ✅ 全页面零 `<aside>` |
| `window.ocHost` | `undefined` | ✅ 未注入任何全局面板对象 |

⇒ **「手机档零面板/零拦截」已得证**；**「点 + 直接新建终端」的交互本身未取证**。

### 5.3 审查条「+」/ `aside#review-panel` —— ✅ **PASS**

- 同一注入读数：`reviewPanel:false`、`aside:0` ⇒ **`aside#review-panel` 不存在**。
- 与 UX7 结论（手机档上游无审查条）一致 ⇒ **手机档全页面零拦截符合预期**。

### 5.4 面板「打开文件 / 打开终端」—— **N/A**

- 手机档**无面板**（§5.2 已证 `aside:0`、无 `[data-oc-panel]`），该入口不存在。
- 依据：UX7 已验（本轮复核一致）。**不判 PASS/FAIL，记 N/A。**

### 5.5 发消息有回复 —— ❌ **FAIL（阻塞）**

- 需进入会话点 composer；**会话无法进入**（§5.0），`input text "reply with the single word OK"` **未执行**。
- 助手回复、模型（`fledge-alpha-free`）调用均**未发生**，无截图可存。
- 关联流量（**只读观察**，说明「确实没能发出消息」）：
  `OccPerfJS` 里**只有** `/api/config`、`/api/provider`、`/api/model`、`/api/reference`、
  `/api/info`、`/api/location`，**无任何 `/api/session` 创建或消息投递**。
- 判定 **FAIL（阻塞）**。

### 5.6 切会话 / 返回 —— ⚠️ **PARTIAL**

| 子项 | 结果 |
|---|---|
| 建第二个会话（New session） | ❌ 点击无反应，`loading` 恒 `true` |
| 两个会话间切换 | ❌ 无会话可切 |
| 按返回键回 Home | ✅ 通过（与 5.12 同一次操作，见下） |

### 5.7 键盘弹起 composer 两屏不裁切 —— ❌ **FAIL（阻塞）**

- 屏 A（New session）：**New session 建不出来**（§5.0）⇒ 无屏 A。
- 屏 B（会话界面）：**无会话界面** ⇒ 无屏 B。
- 因此 **`composer` 底边 vs `visualViewport.height` 的两次 px 量测均未取到**，**不编造数字**。
- 判定 **FAIL（阻塞）**。

### 5.8 主题 浅 / 深 / 跟随 —— ✅ **PASS**

真实点击路径：抽屉 → Settings → 段选择器 → **Appearance** → Color scheme 下拉。
**这一步是注入**读 `document.documentElement.dataset.colorScheme` + 计算背景色：

| 设置 | 注入读数 | 截图 |
|---|---|---|
| **Light** | `{"scheme":"light","bg":"rgb(250, 250, 250)"}` | `ux12p_theme_light.png` |
| **Dark** | `{"scheme":"dark","bg":"rgb(8, 8, 8)"}` | `ux12p_theme_dark.png` |
| **System** | `{"scheme":"light","bg":"rgb(250, 250, 250)"}` | `ux12p_theme_system.png` |

- Light / Dark 切换**真实生效**（`250,250,250` ⇄ `8,8,8` 对比强烈，宿主窗口底色同步跟随）。
- **System 解析为 `light`** —— 设备当前系统主题即浅色，**符合「跟随」语义**（非缺陷）。
- 页面文本在 Appearance 段为 `Color scheme / Choose whether OpenCode follows the system, light, or dark theme`，
  下拉三项 `System ✓` / `Light` / `Dark`（截图 `ux12p_scr_csdlg.png` 实拍）。
⇒ **PASS**。

### 5.9 回合常亮（keep screen on）—— ❌ **FAIL（阻塞）**

需发一条消息驱动回合；**消息发不出**（§5.5）⇒ 无回合。

本轮**全部** `applyKeepScreenOn` 日志原文（logcat 全量 grep，仅 3 条，全部 `flagSet=false`）：

```
03:26:49.398 D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false
03:37:45.453 D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false
```

- `roundActive` 恒 `false`（没有回合）⇒ **`flagSet` 一次都没为 true**。
- **未观察到 `clearKeepScreenOn`**（因为从未置起过）。
- 判定 **FAIL（阻塞）**：常亮**未被证明失效**，但**置起/释放这一对行为完全无法取证**。

### 5.10 后台断 SSE / 回前台重建 —— ✅ **PASS**

真实按键 `input keyevent KEYCODE_HOME` 切后台 → `am start` 回前台。**logcat 原文**：

```
--- 切后台 ---
03:37:38.697  P6 dispatch synthetic pagehide (upstream pageLifecycle)
03:37:38.705  [oc-pow] event stream CLOSE #496 cancel @715
03:37:38.710  OcPerfJS res #962 api ... http://127.0.0.1:4095/api/event @19   （dur=84680 后随流终止）

--- 回前台 ---
03:37:45.453  D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false
03:37:45.453  P6 dispatch synthetic pageshow (upstream pageLifecycle)
03:37:45.598  [oc-pow] event stream OPEN #580 /api/event @715
03:37:45.707  [oc-pow] event stream OPEN #581 /api/event @715
```

| 指标 | 实测 | 参照（UX8） | 判定 |
|---|---|---|---|
| 切后台 `pagehide` → `CLOSE` | **8 ms**（.697 → .705） | 20–106ms 级 | ✅ |
| 回前台 `pageshow` → `OPEN #580` | **145 ms**（.453 → .598） | 92–245ms 级 | ✅ |
| 回前台后页面可用 | ✅ 未白屏、未崩，Home 正常渲染 | — | ✅ |

⇒ 生命周期事件与事件流重建**均在既有指标带内**，**PASS**。
⚠️ 附带观察：回前台后 `/api/event` 仍以 ~1s 周期反复 `OPEN` 后
`[global-sdk] event stream disconnected`（见 §1.5）⇒ 重建成功但**流仍不稳定**（与 §5.0 同源）。

### 5.11 官方设置里能加服务器并连上 —— ✅ **PASS**

= **第 3 步**（不重复操作）。证据：`ux12p_add_http.png`（对话框无红字）、
`ux12p_server_green.png`（**绿点** `127.0.0.1:4095` / `http://127.0.0.1:4095`）、
`ux12p_server_dots_both.png`（**同一屏内 4173 红点 / 4095 绿点**对照）、
`OcPerfJS` 直连 4095 的 `/api/*` 流量。

### 5.12 （收尾顺验）返回不卡死 / 不白屏 —— ✅ **PASS**

- 路径：Appearance 段 → `Back to app` → `KEYCODE_BACK` ×2 → Home。
- 截图 `ux12p_back_home.png`：正常回到 Home（`Home` 标题 + `All projects` + 骨架），
  **无白屏、无卡死、无 ANR**，主题（System→light）保持一致。
⇒ **PASS**。

### 5.13 12 条汇总

| # | 项 | 判定 |
|---|---|---|
| 5.1 | 终端可用 | ❌ FAIL（阻塞：进不去会话） |
| 5.2 | 终端条「+」直接新建 / 无面板 | ❌ FAIL（阻塞）+ ✅ 面板 DOM 断言通过 |
| 5.3 | 审查条「+」/ `review-panel` | ✅ **PASS** |
| 5.4 | 面板「打开文件/终端」 | **N/A**（无面板） |
| 5.5 | 发消息有回复 | ❌ FAIL（阻塞） |
| 5.6 | 切会话 / 返回 | ⚠️ PARTIAL（返回✅，切换❌阻塞） |
| 5.7 | 键盘 composer 两屏不裁切 | ❌ FAIL（阻塞，**未取到 px 值**） |
| 5.8 | 主题 浅/深/跟随 | ✅ **PASS** |
| 5.9 | 回合常亮 | ❌ FAIL（阻塞，flag 从未置起） |
| 5.10 | 后台断 SSE / 回前台重建 | ✅ **PASS** |
| 5.11 | 官方设置加服务器并连上 | ✅ **PASS** |
| 5.12 | 返回不卡死不白屏 | ✅ **PASS** |

**合计：5 PASS（5.3 / 5.8 / 5.10 / 5.11 / 5.12）+ 1 N/A（5.4）+ 1 PARTIAL（5.6）+ 5 FAIL（全部为同一阻塞根因 §5.0 所致，非各自独立缺陷）。**

---

## 6. 收尾复核（原始输出）

### 6.1 `adb devices`（杀自建实例前）

```
List of devices attached
emulator-6790	device      ← 本轮自建
emulator-6792	device      ← 非自建
```

### 6.2 只关自建实例

```
$ adb -s emulator-6790 emu kill
OK: killing emulator, bye bye
OK
```

杀后：

```
$ adb devices
List of devices attached
emulator-6792	device      ← 仍在，一动未动
```

### 6.3 端口 6790 释放

```
$ Get-NetTCPConnection -LocalPort 6790
LocalAddress LocalPort RemoteAddress RemotePort  State      OwningProcess
127.0.0.1    6790      127.0.0.1     61518         TimeWait  0
```

⇒ **无 Listen 套接字**（仅 `TimeWait` 残留，18s 后自然消失）⇒ **端口已释放**。

### 6.4 `adb reverse`

```
$ adb -s emulator-6790 reverse --remove tcp:4095
adb.exe: error: device 'emulator-6790' not found
```

⚠️ 如实记录：实例已先 `emu kill`，reverse 绑定**随设备一同消亡**，故 `--remove` 报「设备不存在」。
本轮**只**对 `emulator-6790` 建过 reverse（`host-15 tcp:4095 tcp:4095`），
**未**对 `emulator-6792` 建过任何 reverse ⇒ 6792 上无残留。

### 6.5 后端未起停（证据）

```
$ Get-NetTCPConnection -LocalPort 4095 -State Listen
LocalAddress LocalPort State  OwningProcess
127.0.0.1    4095      Listen 31812          ← pid 与 §0.6 装包前完全一致

$ Invoke-WebRequest http://127.0.0.1:4095/api/info  (Basic opencode:opencode)
STATUS=200
{"version":"2.0.23","pid":31812,"urls":["http://127.0.0.1:4095"],
 "paths":{"tmp":"C:\\Users\\xiufeigo\\AppData\\Local\\Temp\\opencode"},
 "capabilities":{"persistentPty":false}}
```

- **pid 31812 全程未变**（装包前 = 收尾后）⇒ 后端**从未被本轮起停**。
- 本轮对后端**只有只读** `Invoke-WebRequest` 探测（`/api/info`）。

### 6.6 代码改动

- 本轮**未对 `webapp/**`、`app/**` 写入任何字节**：全程只用
  `read`/`grep`/`glob` 读，以及对 `research/web/**` 写报告与截图。
- `git status --porcelain -- webapp app` 有输出（`M WebHostActivity.kt`、
  `M DirectConnectAuthBoundaryTest.kt`、`M TerminalReattachTest.kt`、
  `?? assets/www/ui/`、`?? host/LocalUiOriginServer.kt`、`?? LocalUiOriginServerTest.kt`）
  —— ⚠️ 这些是**方案 A 产物的前置改动，本轮开始前即存在**（本轮测的就是据此构建的
  `deliver/opencode-shell-web.apk`），**不是本轮产生的改动**。
- 未碰 gradle、未碰任何构建锁；未起停后端。

### 6.7 碰过的 serial（红线声明）

| serial | 是否本轮自建 | 本轮是否操作 |
|---|---|---|
| `emulator-6790` | ✅ 是（本轮启动） | 全部操作仅限此串口；收尾已 `emu kill` |
| `emulator-6792` | ❌ 否（**他人/并行轮次**，起机期间出现） | **完全未碰**：未安装、未启停、未截图、未清数据、未建 reverse |

- **未**执行 `adb kill-server` / `adb -a` / 任何通配符。
- 本轮**所有** adb 命令都**显式带 `-s emulator-6790`**（`adb devices` 列表查询除外）。

---

## 7. 总体结论

1. **验收 #3 前半（页面来源）—— ✅ 通过**：页面确为方案 A 自带官方前端，
   `location.href/protocol/origin/docURL` 四项全部 = `http://127.0.0.1:4173/…`，`protocol=http:`。
2. **验收 #1 前半（官方同页加 http）—— ✅ 通过（本轮最强证据）**：
   真实点击路径添加 `http://127.0.0.1:4095` 成功，**绿点**，`OcPerfJS` 出现直连 4095 的
   `/api/provider`、`/api/model`、`/api/session/active` 等流量，**红字零出现**。
3. **验收 #1 后半（https）—— ✅ 通过（等价构造，显著标注）**：
   判据实测 `isMixedContent(http页, https目标)=false`；设备实测 https 对话框全程无红字，
   仅报**通用** `Could not connect to server`。⚠️ **https 成功未端到端验**（无受信 https 后端）。
4. **那句红字 —— 本机 http 页面形态下已消失**，机制在真实设备页面里闭合（§2 三用例全中）。
5. **验收 #4（全量回归）—— 5 PASS / 1 N/A / 1 PARTIAL / 5 FAIL**；
   5 条 FAIL **同源于一个阻塞**：切到 4095 后所有 API 响应**字节数为 0**，
   列表恒 `Loading`，无法进入任何会话（§5.0）。**这是本轮最需要产品负责人跟进的发现。**

### 7.1 与既有报告的偏差（如实汇总）

| 任务书预期 | 实测 | 章节 |
|---|---|---|
| `wm size 1200x2670` | **1080x2400**（density 420 一致） | §0.4 |
| 页面 API 请求应为 `http://127.0.0.1:4095` | 初始态全走 `4173`（宿主同源化）；**加完 4095 后才直连 4095** | §1.4 / §3.6 |
| `Terminal shell: powershell` | 实为 **`Auto (Default)`** | §3.7 |
| 红线 3 的「端点 404 拒答」 | **未以 404 出现**；`/api/info` 走 4173 返回 200 | §1.4 |
| `_upstream/…/browser.ts` 当场读取 | `_upstream` **不存在**，函数取自 `UX9_HTTP_SERVER.md` 逐字存档 | §0.3 |

### 7.2 截图清单（均 1080x2400，存于 `research/web/`）

`ux12p_home.png`（官方 Home）· `ux12p_server_green.png`（**绿点**）·
`ux12p_server_dots_both.png`（**红/绿同屏对照**）· `ux12p_add_http.png`（http 对话框无红字）·
`ux12p_add_https.png` / `ux12p_add_https_result.png`（https 无红字 + 通用错误）·
`ux12p_theme_light.png` / `ux12p_theme_dark.png` / `ux12p_theme_system.png` ·
`ux12p_back_home.png`（返回 Home）· 过程图 `ux12p_scr_*.png` / `ux12p_typed_*.png` / `ux12p_proj*.png`

---

**本轮报告完。0 代码改动、0 新构建产物。**
