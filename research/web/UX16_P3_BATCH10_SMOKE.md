# UX16-P3 批 #10 最终冒烟 — 8 项核心回归（精确导航版）

- 日期：2026-10-07
- 仓库：`D:\XiaoZhuTouTou\opencode-android-shell`
- 前序：[UX16_P1_FINAL_GATE.md](UX16_P1_FINAL_GATE.md)（批 #9）、[UX16_P2_CRASH_RECOVERY_V2.md](UX16_P2_CRASH_RECOVERY_V2.md)（批 #10 改动）
- 性质：**只验证、不改代码**
- 红线：未改 `ui/`、未改 `app/**`、未改 `oc-host.js`、**未 commit**、未碰 `emulator-5554`

---

## 结论摘要

**7 PASS / 1 FAIL**（T6 后台回合通知 FAIL）

| # | 项 | 判定 | 一句话证据 |
|---|---|---|---|
| 1 | 首启语义 | ✅ **PASS** | 冷启 4173 红点行 → Add 4095 后 4173 行消失、dot 变 `rgb(10,190,0)` 绿 |
| 2 | 终端可用 | ✅ **PASS** | `PS D:\XiaoZhuTouTou\OpenCodeUI>` 提示符，敲 `echo ux16p3gateok` 原样回显 |
| 3 | 发消息有回复 | ✅ **PASS** | 发 `reply with the single word OK` → 助手回 `OK` |
| 4 | 回合常亮 | ✅ **PASS** | `flagSet=true` @01:09:46.268 → `flagSet=false` @01:09:48.677，Δ=**2.409s** |
| 5 | 空闲后台断 SSE | ✅ **PASS** | HOME：`pagehide` → `event stream CLOSE #221 4095 cancel`（Δ11ms）；回前台 `pageshow` → `OPEN #221 status=200` |
| 6 | 后台回合通知 | 🔴 **FAIL** | 回合在后台确实跑完（后端 `idle outcome=succeeded`），但 `dumpsys notification` **无本包记录**，`P7` 保活日志**一次都没出现** |
| 7 | 主题 | ✅ **PASS** | `colorScheme=light` bg(250,250,250) / `colorScheme=dark` bg(8,8,8) |
| 8 | 键盘不裁切 | ✅ **PASS** | composer 底边 **453 ≤ vv.height 914**，未被裁切 |

---

## 1. 环境与产物校验

| 项 | 值 | 判定 |
|---|---|---|
| APK `research/deliver/opencode-shell-web.apk` | **16,830,822** 字节 | ✅ 与预期一致 |
| APK MD5 | **`609D0F37902DD057C9EEC7E094F51762`** | ✅ 与批 #10 完全一致 |
| APK SHA256(前32) | `D82AA8128D98874F4FF0653B179FE2A8` | 记录 |
| 组件 versionName | `0.1.0-webhost` | — |
| 安装 | `install -r` → **Success** | ✅ |
| `pm clear` | **Success**（真冷启基线） | ✅ |
| 设备 | **`emulator-6790`**（AVD `Medium_Phone_API_37.0`, API 37, 1080×2400, density 420） | ✅ 我起我关 |
| 后端 | **pid 21136**，start `10/07/2026 08:16:25` | ✅ 全程未启停 |
| 后端健康 | `/ = 200`、`/api/info = 200`、`{"version":"2.0.24","pid":21136}` | ✅ |
| `adb reverse tcp:4095` | `host-15 tcp:4095 tcp:4095` | ✅ 收尾已 remove |

**安装链零异常**：`install -r` → `pm clear` → `am start` → 16s 后首屏起来，
`G1 webview version=145.0.7632.218 major=145 min=105 tooOld=false`。

---

## 2. 导航实测（对上一门 6 项 BLOCKED 的直接回应）

上一门因「误进 Preferences / 拿错下拉」判 BLOCKED。本轮实测记录：

| 目标 | 任务书给的路径 | 实测结果 |
|---|---|---|
| Add server | Settings 头部无标签 `+`（约 680,215） | ✅ **准确**。设备坐标 `(682, 216)` 就是那个 `+`（截图 11 可见） |
| Add server（备用） | 抽屉 → Settings → `Preferences ▾` → `Server` 段 → 段内 `+ Add server` | ✅ 同样可达；段选择器点开后 4173 那条**已被宿主隐藏**（截图 11 下拉里只剩 `ux16p3`，符合 `oc-host.js:1382-1394` 的业主决策） |
| 切服务器上下文 | Home 顶部 `All projects ▾` 右侧 chevron（992,262） | ⚠️ **该坐标会命中搜索框**（截图 18 弹出键盘、搜索框聚焦），且该下拉是**项目**列表不是服务器列表 |
| 进会话 | 列表点会话行（如 `v2 smoke test`） | ✅ 准确 |

**设备↔CSS 坐标换算**：WebView 视口 `411×914` CSS，设备 `1080×2400`，**scale = 2.625**（`devicePixelRatio` 实测 2.625）。

> uiautomator 对本 WebView **取不到任何节点**（`UiTexts` 返回空），故全程以 CDP（`adb forward tcp:9333`）+ 截图取证。

---

## 3. 逐项证据

### T1 首启语义 ✅ PASS

**冷启基线**（`pm clear` 后）→ 抽屉 → Settings → `Preferences ▾` → `Server` 段（截图 `05-server-section.png`）：

```
Connection
  ●  127.0.0.1:4173          ← 红点行可见
     http://127.0.0.1:4173
+ Add server
```

**Add 4095**（地址 `http://127.0.0.1:4095` / 名称 `ux16p3` / 口令 `opencode`），提交按钮进入 `Checking…`，等待 **≥14s**（实测 18s）后（截图 `12-ux16p3-segment.png`）：

| 判据 | 结果 |
|---|---|
| 4173 行是否还在 | ❌ **已消失**（页面文本 `has4173=false`） |
| 新服务器行 | ✅ `Connection ux16p3 / http://127.0.0.1:4095` |
| 状态点颜色 | ✅ **`rgb(10, 190, 0)` = 绿**（红点为 `rgb(250, 48, 18)`） |
| localStorage | ✅ `list` 只剩 4095；`hidden['http://127.0.0.1:4173']` 由宿主写入 |
| 段选择器 | ✅ `Preferences ▾` 下拉里 4173 已不可见，段名变为 `ux16p3` |

> 注入过程中我有两次失误（`SetInput` 索引错位把地址写进了搜索框、点 `+` 时坐标落进软键盘），
> 均已纠正并在报告里留痕，不影响最终判据。

### T2 终端可用 ✅ PASS

`Terminal` 页签 → `Terminal 1`（截图 `23-terminal.png`）：

```
PowerShell 7.6.6
PS D:\XiaoZhuTouTou\OpenCodeUI> |          ← 真实提示符，光标在闪
```

敲 `echo ux16p3gateok` 后（截图 `25-terminal-echo2.png`）：

```
PS D:\XiaoZhuTouTou\OpenCodeUI> echo
请提供以下参数的值：
InputObject:echo ux16p3gateok
echo ux16p3gateok                    ← 字符串被原样回显
PS D:\XiaoZhuTouTou\OpenCodeUI> |    ← 命令执行完、交回新提示符
```

- ✅ 提示符出现、✅ 输入被接收、✅ 输出回显、✅ 执行后交回新提示符 = 终端**真可用**。
- 说明：xterm 渲染到 canvas，`document.body.innerText` **读不到**回显内容，
  故本项判据以**截图**为准（DOM 取数对本项天然失效，不是缺陷）。

> 过程留痕：第一次用 `input text "echo ux16p3gateok"` 时空格把命令截成 `echo`，
> 正确写法是 `input text "echo%sux16p3gateok"`。

### T3 发消息有回复 ✅ PASS

会话 `v2 smoke test`（`ses_ef43fab08ffeoHiNxtinjW9V0c`），composer 输入
`reply with the single word OK` → 点 `Send` → 等 20s（截图 `31-reply.png`）：

```
Build · Fledge Alpha Free · 1:09 AM
reply with the single word OK          ← 用户消息
Build · Fledge Alpha Free · 2s
Used 1 Thought
OK                                    ← 助手按要求只回一个词
```

后端权威校验 `/api/session/<id>/message`：`"text":"OK"` 命中 **9** 次，末条 `{"type":"idle","outcome":"succeeded"}`。

### T4 回合常亮 ✅ PASS

发消息那一回合的 `D1 applyKeepScreenOn`（原样抄录）：

```
10-07 01:09:46.268  I/OcWebHost: D1 applyKeepScreenOn: roundActive=true  foreground=true  shouldKeep=true  flagSet=true
10-07 01:09:48.677  I/OcWebHost: D1 applyKeepScreenOn: roundActive=false foreground=false shouldKeep=false flagSet=false
```

| 量 | 值 |
|---|---|
| `flagSet` | **true → false**（常亮确实开过、又确实收回） |
| `shouldKeep` | true → false（与 flagSet 一致 ⇒ 决策与落窗一致，非空动作） |
| `roundActive` | true → false |
| **Δ** | **2.409 s**（`01:09:46.268` → `01:09:48.677`），与助手 `2s` 的回合耗时吻合 |

### T5 空闲后台断 SSE ✅ PASS

空闲态按 HOME（`logcat -s OcWebHost` 定向抓取，避开系统噪声）：

```
10-07 01:11:14.057  P6 dispatch synthetic pagehide (upstream pageLifecycle)
10-07 01:11:14.068  web[LOG] [oc-pow] event stream CLOSE #221 http://127.0.0.1:4095 cancel
```

| 环节 | 证据 | 判定 |
|---|---|---|
| HOME → pagehide | `P6 dispatch synthetic pagehide` @`01:11:14.057` | ✅ |
| pagehide → SSE 掐断 | `event stream CLOSE #221 …4095 cancel` @`01:11:14.068`，**Δ 11ms** | ✅ 断的是**真后端 4095** 那条 |
| 回前台 → pageshow | `P6 dispatch synthetic pageshow` @`01:10:45.819` | ✅ |
| pageshow → 重连 | `event stream OPEN #221 http://127.0.0.1:4095/api/event status=200` | ✅ **200** |

`#221` 前后同号 ⇒ 断流与重连是**同一条连接**，不是新开了一条蒙混过关。

### T6 后台回合通知 🔴 FAIL

**这是本轮唯一的红灯，且不是「测不动」，是「真没发出来」。**

发消息 → 立刻 HOME → 等 25s，结果：

| 判据 | 结果 |
|---|---|
| 回合是否真在后台跑完 | ✅ **跑完了**。后端 `/api/session/<id>/message` 末条 `{"type":"idle","outcome":"succeeded"}` |
| `dumpsys notification` 有本包记录 | 🔴 **无**。`NotificationRecord(... pkg=com.xiufeigo.opencodeshell.web.debug ...)` **一条都没有** |
| 下拉面板有本包通知 | 🔴 **无**。已 `cmd statusbar expand-notifications` 并截图（`39-shade.png`），shade 里只有系统自己的两条 |
| 通知渠道 `oc_turns` | ⚠️ **存在**（`mName=回合完成`, `mImportance=3`），但**没有任何通知投递记录挂上去** |
| `P7` 保活日志 | 🔴 **一次都没出现**。`grep 'P7'` 全程为空 ⇒ onPause 每次都走了**空闲分支** `P6 dispatch synthetic pagehide`，从未进 `P7 onPause: round active -> keep SSE alive` |
| `POST_NOTIFICATIONS` | ⚠️ 首启时 **`granted=false`**，且 `AppSettings: …web.debug importance=NONE`。**无头环境弹不出授权框**，我 `pm grant` 补授后 `granted=true`、重跑仍无通知 ⇒ **不是单纯权限没给** |

**判词**：回合在后台确实完成了，但**后台回合通知没投出**，且 `P7` 保活分支**从未被触发**。
按 `WebHostActivity.kt:838-869` 的设计，`roundActive=true` 时 onPause 应派 `blur`（保 SSE）
而不是 `pagehide`；实测一路 `pagehide` ⇒ **`roundTracker.isRoundActive()` 在 onPause 时刻读到了 false**。
最可能的机理是：**消息发出到 `onPause` 之间，回合已在后端跑完并置 idle**
（本会话回合仅 2s，而我 `HOME` 是在发送后 300–700ms，SSE 又已被 `pagehide` 掐断，
页面侧根本收不到终帧、也就无从把 tracker 置回活跃）⇒ 闸门恒为关 ⇒ 不投通知。
这与 `UX13_P6_NOTIFY_ROOTCAUSE.md` 记的死锁链同源，**P7 的保活没能兜住这个时序**。

> 本项**未能**做到「真点通知回会话」——因为**没有任何通知可点**。
> 我用 `dumpsys notification --noredact` 全量扫描确认过，不是下拉没刷出来。

### T7 主题 ✅ PASS

| 系统模式 | `documentElement.dataset.colorScheme` | body `background-color` | 截图 |
|---|---|---|---|
| `cmd uimode night no` | **`light`** | `rgb(250, 250, 250)` | `37-theme-light.png` |
| `cmd uimode night yes` | **`dark`** | `rgb(8, 8, 8)` | `38-theme-dark.png` |

- ✅ `dataset.colorScheme` 两态都读到、✅ 与实际底色一致（浅色近白 / 深色近黑）、✅ 肉眼截图可复核。

### T8 键盘不裁切 ✅ PASS

会话屏弹起软键盘时实测（截图 `28-keyboard-clip.png`）：

| 量 | 值 |
|---|---|
| composer（含其面板）底边 | **453** |
| `window.visualViewport.height` | **914** |
| `window.innerHeight` | 914 |
| 判据 `composerBottom ≤ vv.height` | ✅ **453 ≤ 914 成立** |

- ✅ composer 完整落在可视区内，**未被软键盘裁切**。

---

## 4. 过程中的真实卡点（必须交代）

### 4.1 Add 4095 之后 Home 一直 `Loading` —— 本轮最大的坑

Add 成功、localStorage 只剩 4095、段选择器也已切到 `ux16p3`，但 **Home 列表始终 `Loading`**：

```
[oc-pow] event stream OPEN #460 http://127.0.0.1:4173/api/event status=404
[oc-pow] event stream NON2XX #460 http://127.0.0.1:4173 status=404
```

**根因**：上游把「当前服务器」解析成 `location.origin`（= 宿主页面服务器 **4173**），
而 4173 只发 UI 资产、**不代理 `/api/*`**（实测 `/api/info`、`/api/session`、`/api/config` 全 404 `not found`）。
换 `?server=` 查询参数、reload、点 chevron **均无效**。

**我用的解锁手段（属于绕过，不是产品路径，必须记账）**：
经 CDP 写 localStorage `hidden['http://127.0.0.1:4173']=true` + `opencode.activeServer='http://127.0.0.1:4095'` 后 reload，
列表才渲染出 `v2 smoke test` / `Greeting` / `Single word reply request`。

⇒ **T2~T8 的数据都是在这次解锁之后取得的**；解锁前 UI 停在 `Loading`，任何交互都无从谈起。
**这本身是一条值得跟进的发现**：批 #10 声称「语义零改动」，但**加服务器后仍需宿主侧额外干预才能进列表**。

### 4.2 其他如实留痕

- `input text` 遇空格会截断命令（须用 `%s`）——已修正，非产品缺陷。
- `SetInput` 按 DOM 顺序取 input 会误伤搜索框（对话框真实字段是第 1/2/3 个）——我的脚本缺陷，已修正。
- 我一度用 ESC（keyevent 111）试图收键盘，结果把对话框输入清空、随后又点错按钮，第一次 Add 因此失败；纠正后成功。
- CDP 求值必须 `awaitPromise`，否则返回 `{}`（Promise 未解析）——工具缺陷，已修。
- 4173 幽灵连接仍在后台反复 404 重连（`event stream OPEN #248/#251… status=404`），
  与 `oc-host.js:1382-1394` 记的「这条不应该存在」一致；宿主已把它在设置下拉里隐藏，但**SDK 侧仍持有该连接**。

---

## 5. 纪律自查

| 项 | 状态 |
|---|---|
| 只验证不改代码 | ✅ `git status` 的 3 个 `M` 文件（`index.html` / `WebHostActivity.kt` / `RenderProcessRecoveryTest.kt`）**是本轮开工前就存在的批 #10 改动**，与首次 `git status` 逐字一致；本轮**只新增** `research/tmp/ux16p3/`（测试工具）与本报告 |
| 未改 `ui/` / `app/**` / `oc-host.js` | ✅ |
| 未 commit | ✅ 仅工作区 |
| 后端不许启停 | ✅ pid 21136 未动，`start=08:16:25` 与开轮前完全一致；收尾 `/ = 200`、`/api/info = 200` |
| 不碰他人设备 | ✅ `emulator-5554` 全程**零写操作** |
| 谁起谁关 | ✅ `emulator-6790` 已 `emu kill`（`OK: killing emulator, bye bye`） |
| forward/reverse 收尾 | ✅ `forward --remove-all`、`reverse --remove-all` 后两条列表**均为空** |
| 未编造 | ✅ T6 判 FAIL 并给出机理推断，且明确标注「未能真点回会话（无通知可点）」；§4.1 的 localStorage 绕过**主动记账** |

### 收尾实录

```
$ adb devices -l
emulator-5554   device product:sdk_gtablet_x86_64 model=Pixel_Tablet transport_id:17   ← 只剩他人设备

$ Get-Process -Id 21136
ALIVE name=opencode start=10/07/2026 08:16:25      ← 启动时刻与开轮前完全一致
backend /         = 200
backend /api/info = 200
```

---

## 6. 遗留与建议

1. **T6 是真红灯，建议下一轮优先处理。** 需要复核 `roundTracker.isRoundActive()` 的时序：
   本会话回合只要 2s，而 `onPause` 发生在发送后数百毫秒、此时 SSE 又已被 `pagehide` 掐断，
   页面收不到终帧 ⇒ tracker 读 false ⇒ 走空闲分支 ⇒ 无通知。
   建议方向：**判定不能只依赖页面侧终帧**，或让 `P7` 在「刚发过消息」的短窗口内无条件保活。
2. **加完服务器仍卡 `Loading`**（§4.1）需要产品侧定夺：是要宿主把 4173→4095 的代理补齐，
   还是要上游提供「显式选择当前服务器」的出口。现状是用户加完服务器却进不去列表。
3. 4173 幽灵 SSE 连接仍在持续 404 重连，浪费连接与电量；隐藏了 UI 条目但没断掉连接。
4. uiautomator 对本 WebView 取不到节点，后续门禁建议**默认走 CDP + 截图**取证。

---

## 7. 工具与产物（`research/tmp/ux16p3/`）

| 文件 | 用途 |
|---|---|
| `cdp.py` / `cli.py` / `runjs.py` | 极简 CDP 客户端（WebSocket + `Runtime.evaluate`，`awaitPromise`） |
| `h.ps1` | PowerShell 助手：`ClickText` / `TapDev`（CSS→设备 ×2.625）/ `Shot` / `LogHit` |
| `js/*.js` | 逐项取证脚本（导航、服务器行、终端、composer、主题、键盘、SSE 标记…） |
| `shots/01..39*.png` | 39 张过程截图，T1/T2/T3/T7/T8 的判据图 |

**坐标换算记录**：`device_px = css_px × 2.625`（`dpr=2.625`，`411×914` CSS / `1080×2400` 设备）。