# 验证 A（手机档独立验证）报告

- **角色**：验证 A —— 手机档独立验证，**只读**（未改 `webapp/**`/`app/**`，未重建 `research/deliver/**`）
- **执行方式**：mcode（minimax）
- **开始**：2026-10-06 00:16 · **收尾**：2026-10-06 00:45（Asia/Shanghai）
- **自建实例**：`OC_UX6_Phone` 起在 **端口 6710** ⇒ serial **`emulator-6710`**（本轮唯一自建实例，已关）
- **后端**：`127.0.0.1:4095`（`opencode`/`opencode`）——**全程未起停**，只建 `adb reverse`

> **本报告为独立取证**：每条结论均由本人动手获得。凡引用他人报告处均显式标注"（引自 UXx，非本人取证）"。
> 凡标 **注入** 者为程序注入；凡标 **真实** 者为 `input tap` / 系统 IME / 系统按键。

---

## 0. 结论速览

| 项 | 状态 | 一句话 |
|---|---|---|
| 交付包 md5 vs 文档 | **✅ 一致** | `e54752db…` 三方一致 |
| 设备端 base.apk md5 = 交付包 | **✅ 相等** | 装后 + 重启后各核一次，都相等 |
| **B-1** 终端条 `+` 直开终端 | **✅ PASS** | 直出 `Terminal 3`，零拦截日志 |
| **B-3** 手机终端可用 | **✅ PASS** | 活提示符 + 敲命令 + 回显 `VA-TERM-OK` |
| **B-4** 右上角 ≡ | **✅ PASS** | 上游菜单 `Usage`/`Session details`，非我们的面板 |
| **B-4** 手机无右侧栏/无左滑 | **✅ 上游形态** | 按规格**不判缺陷** |
| **B-5** 新建 session 屏（弹/收） | **✅ PASS** | 未裁切，收起自动恢复 |
| **B-5** 会话屏（弹/收） | **✅ PASS** | 未裁切，余量 21.0 px |
| **UX8 常亮置起** | **✅ PASS** | `flagSet=true` + 跨 15 s 超时物理常亮 |
| **UX8 回合后释放** | **✅ PASS** | Δ = 0–88 ms，释放后 15 s 能睡 |
| **UX8 SSE 后台断** | **✅ PASS** | pagehide→CLOSE **21 ms** |
| **UX8 SSE 前台重建** | **✅ PASS** | pageshow→OPEN **644 ms** |
| 回归：发消息收回复 | **✅ 正常** | 收到 `1..20` |
| 回归：切会话 | **✅ 正常** | |
| 回归：返回/后退 | **✅ 正常** | |
| 回归：键盘弹收 | **✅ 正常** | |
| 回归：主题 | **⚠️ 部分** | 见 §9.3 |
| 回归：官方设置里配服务器 | **⚠️ 测不到** | 见 §9.5 |
| 平板档 | **— 未做** | 本轮只做手机（由验证 B 负责） |

---

## 1. 交付包身份核验（✅ 三方一致）

| 环节 | 值 |
|---|---|
| 交付包文件 | `research/deliver/opencode-shell-web.apk`，**5,437,979 B**，时间戳 2026-10-05 23:45:24 |
| **交付包 md5（本人 `Get-FileHash` 实算）** | `e54752db94e2528d54f0774b8f7b9646` |
| `HANDOFF_WEB_SHELL.md:35` / `:87` 记录值 | `e54752db94e2528d54f0774b8f7b9646` |
| **设备端 `base.apk` md5（`adb shell md5sum`）** | `e54752db94e2528d54f0774b8f7b9646` |

⇒ **交付包 md5 与文档一致；设备端 base.apk md5 = 交付包 md5。**
包名 `com.xiufeigo.opencodeshell.web.debug`（**非** `com.sensorsdata.*`，由 `pm list packages` 实查得出）。
**二次核验**：模拟器中途消失后重启重装态未变，`base.apk` md5 **再次**核对仍为 `e54752db…`。

⚠️ **点名（不影响本轮结论）**：`research/deliver/` 内 `opencode-shell-web-release-nor8.apk` 与
`opencode-shell-web-release-r8.apk` 的时间戳是 **2026-10-06 00:09:05**，比 `opencode-shell-web.apk`（23:45）**晚 24 分钟**。
本轮只验 debug 包，**未逐一核对另两份 md5**，故**不断言交付不一致**；仅如实记录"同目录三产物时间戳不齐"，
建议后续轮次确认是否为分批重建所致。

---

## 2. 环境与几何

- **自建实例**：`OC_UX6_Phone` @ **端口 6710** ⇒ **`emulator-6710`**（唯一自建）。
  首次开机 34.8 s；`adb install -r -d` → `Success`（0.8 s）。
- **手机档几何（`wm size` / `wm density` 实测）**：
  - `Override size: **1200x2670**`（Physical 1080x2400 被覆盖）
  - `Density: **420**` ⇒ **dpr = 420/160 = 2.625**
  - **CSS 逻辑尺寸 = 1200/2.625 × 2670/2.625 = 457.14dp × 1017.14dp**
  - 与产品负责人报告环境（1200×2670）**同款几何**。
- **后端**：`adb reverse tcp:4095 tcp:4095` ⇒ `reverse --list` 显示 `4095`。**仅建通道，未起停后端。**
- **注入项**：`settings put system screen_off_timeout`（常亮测试用）、`cmd uimode night`（主题用）。

---

## 3. B-5 手机档 composer 裁切（两屏 × 键盘弹/收）—— **全 PASS**

### 3.1 新建 session 界面 ✅

**真实**：`input tap` 点 "New session" ⇒ **系统 Gboard 真实弹起**（截图 `03_newsession.png`，键盘 + 候选条 + 工具栏全在）。
探针用 `am start --es oc_debug_eval`（**注入，仅读几何、不改状态**）。

| 状态 | `--oc-ime-bottom` | `innerHeight` | composer form | padding-bottom |
|---|---|---|---|---|
| **键盘弹起** | `336.38px` | 1018 | `[418, 858]` | `336.38px` |
| **键盘收起**（真实 BACK） | `0.00px` | 1018 | `[418, **522**]` | `0px` |

**断言**：键盘顶边 = `1018 − 336.38` = **681.6 CSS px**；文本框 `[418,478]`、模型行、发送钮
（截图实测 ≈ 520 CSS px）**全在 681.6 之上** ⇒ 未裁切、未顶进状态栏。收起后**自动恢复**（与产品负责人线索一致）。

### 3.2 会话界面 ✅

**真实**：切 `Session` 标签 ⇒ **真实点击** composer（设备 `(577,2441)`）⇒ 真实 Gboard 弹起（截图 `16_session_ime_up.png`）。

| 状态 | `--oc-ime-bottom` | composer/form | padding-bottom |
|---|---|---|---|
| **键盘弹起** | `336.38px` | `[557, 997]` | `336.38px` |
| **键盘收起**（真实 BACK） | `0.00px` | `[893, 997]` | `0px` |

**断言**：内容下沿 = `997 − 336.38` = **660.6**；键盘顶边 **681.6** ⇒ **余量 21.0 px**，
输入行 / `+` / 模型行 / 发送钮**四项全可见**（截图逐项可见）。

### 3.3 口径

`innerHeight=1018` 全程**不随键盘变化**，`visualViewport.height=1018.29`（差 0.29）⇒
**布局视口不缩**，靠 `--oc-ime-bottom` 垫高抬升内容，**不是"页面被顶起"**。336.38 CSS px × 2.625 = **883.0 device px**。

⇒ **B-5 两屏 × 弹/收 = 4 场景全 PASS**；产品负责人报告的"输入框底部掉下去"在交付包上**不复现**。
（机制口径与 UX7 一致，但**读数是本人本次实采**。）

---

## 4. B-3 手机档终端 —— **PASS**

**操作链**：**真实** `input tap` 点 `Terminal` 标签（实测 `@[312,98,101x35]` CSS → 设备 `(951,303)`）
→ **真实点击**终端区聚焦（**真实软键盘随之弹起**）→ **注入** `input text` → **注入** `input keyevent 66`。

| 证据 | 读数 |
|---|---|
| 容器非 0 高 | `#terminal-panel` 存在，rect `[134,1009]`（**875 CSS px**），宽 440 |
| xterm 在渲染 | canvas `[198,984]` **394×786**（非 0） |
| **有提示符** | `Windows PowerShell / Copyright (C) Microsoft Corporation` + `PS C:\sensorsdata\main\program\opencode-android-shell>` |
| **能敲命令** | `echo` 关键字**语法高亮**（黄色） |
| **有回显** | 输出 `VA-TERM-OK`，并回到新提示符 + 光标 |

截图 `13_terminal_typed.png`。⇒ **B-3 PASS**。PTY 链路 logcat：`/api/pty/pty_10ce2fae…` + `connect-token` 200。

---

## 5. B-1 终端标签条 `+` —— **PASS**

**真实点击**终端条 `+`（设备 `(700,419)`）；点击前终端条为 `Terminal 1 ×  Terminal 2 ×  +`。

| 检查 | 结果 |
|---|---|
| 弹我们的「打开/打开终端/打开文件」面板？ | **没有** —— 直接变 `Terminal 1 / Terminal 2 / **Terminal 3** / +`（截图 `14_plus_tap.png`） |
| logcat 拦截 | `oc-plus-chooser` / `intercep` 过滤 **0 命中** |
| 上游行为 | 新 PTY：`16:26:05.045 /api/pty/pty_10ce2fae…` → `.050 …/connect-token` |
| 新终端可用 | **是**，`Terminal 3` 自动激活 + 活提示符 |

⇒ **B-1 PASS**。

---

## 6. B-4 右上角 ≡ —— **PASS**

**真实点击** `More options`（`aria-label` 实测 = **More options**，`@[419,104,24x24]` → 设备 `(1131,304)`）。

| 检查 | 结果 |
|---|---|
| 弹出哪个面板 | **上游菜单** = `Usage` / `Session details`（截图 `15_hamburger.png`） |
| 是我们的面板吗 | **不是**；拦截日志 **0 命中** |

**关于"手机档没有右侧栏 / 左滑打不开"**（按规格**不判缺陷**）：
- 设备实测手机档**无 `#review-panel`**；手机档 CSS 宽 **457.14dp < 768dp**（上游 `isDesktop()` 阈值，引自 UX7 §1.2）。
- 手机档的对应物 = **`Session / Changes / Files / Terminal` 视图标签条**（本人实测存在：`@[9,98]`/`[110,98]`/`[211,98]`/`[312,98]`）。
⇒ 记为"**上游手机档形态**"，非缺陷。

---

## 7. UX8-① 回合常亮（置起 ✅ / 释放 ✅）

**Round A（6.32 s，短于 15 s 超时）** —— **真实点击**发送键（设备 `(1064,1310)`）：

```
16:21:27.448  [oc-pow] keepScreenOn -> true busy=[__post__]
16:21:27.449  [oc-pow] round POST /api/session/ses_ef32146acffeKr1xp3umbQ9iee/prompt
16:21:27.451  D1 applyKeepScreenOn: roundActive=true foreground=true shouldKeep=true flagSet=true   ← flag 真被置起
16:21:28.182  [oc-pow] round start session.execution.started
16:21:34.503  [oc-pow] round end session.execution.succeeded
16:21:34.504  [oc-pow] keepScreenOn -> false busy=[]
16:21:34.560  D1 applyKeepScreenOn: roundActive=false ... flagSet=false
```
释放 Δ = **1 ms / 57 ms**；释放后 `16:21:50.464` 屏幕自然熄（`mWakefulness → Dozing`）。

**Round B（24.42 s，跨越 15 s 超时）** —— **真实点击** `Send` 按钮（`aria-label="Send"`，`@[405,627,24x24]` → 设备 `(1095,1677)`），
发送于 `00:29:20.086`，`screen_off_timeout=15000`（**注入**设定）：

| 检查 | 读数 |
|---|---|
| **置起** | `16:29:21.498 … shouldKeep=true **flagSet=true**`（POST 后 0 ms） |
| 回合开始 | `16:29:22.213 round start session.execution.started` |
| **物理常亮（跨超时点）** | **t+20 s（> 15 s 超时）`mWakefulness=Awake`**，截图 `19_hold_past_timeout.png` 屏幕实亮、回合仍在跑 |
| 回合结束 | `16:29:46.635 round end session.execution.succeeded`（总 **24.42 s**） |
| **释放** | `.635 keepScreenOn -> false`（**Δ=0 ms**）→ `.723 flagSet=false`（**Δ=88 ms**） |
| **释放后能睡** | `16:30:02.411` 自然熄（释放后 ~15.7 s，与 15 s 超时吻合），截图 `20_after_release.png` |

⇒ **UX8-① PASS（三层证据齐全）**：① `flagSet=true` 宿主日志（非"没看到它挂着"）；
② 跨越超时的**物理常亮**；③ 结束后 0–88 ms 释放且 15 s 后确实能睡 ⇒ **非"只做一半"、无 4 分钟钉亮**。

---

## 8. UX8-② 后台断 SSE / 回前台重建 —— **PASS**

| 动作 | 注入 | logcat 原文 | 延迟 |
|---|---|---|---|
| 切后台 | **注入** `input keyevent 3` | `16:27:38.125 P6 dispatch synthetic pagehide` → `16:27:38.146 [oc-pow] event stream CLOSE #7 cancel` | **21 ms** |
| 回前台 | **注入** `am start` | `16:27:47.204 P6 dispatch synthetic pageshow` → `16:27:47.848 [oc-pow] event stream OPEN #8 /api/event` | **644 ms** |

**后台实证**：`dumpsys window mCurrentFocus` = `com.google.android.apps.nexuslauncher.NexusLauncherActivity`
⇒ 切后台时确已离开本应用，流是**真被掐断**（`cancel`），不是"没断开只是没看见"。
回前台 `#7 → #8` 序号递进、重新 `GET /api/event` ⇒ **连接确实重建**。

---

## 9. 回归项（有没有把别的用法改坏）

### 9.1 发消息能收到回复 ✅
**真实点击**发送键发 `count from 1 to 20` ⇒ 回复 `1, 2, 3, …, 20`，
`Used 1 Thought` / `Build · MiMo-V2.6-Pro · 7s`（截图 `16_session_ime_up.png`）。长回合 `count from 1 to 500`
亦正常结束（`session.execution.succeeded`）。

### 9.2 切会话 ✅
**真实点击**另一会话行（设备 `(360,1495)`）⇒ 打开 `Count from 1 to 999, one number per line`，
顶部标签与 `Terminal 1` 正常渲染（截图 `22_switch_session.png`）。

### 9.3 返回/后退 ✅
会话内 **真实** `KEYCODE_BACK` ×2 ⇒ 回到会话列表（截图 `21_back_twice.png`），
`topResumedActivity` 仍为 `WebHostActivity`（**未误退出 App、未崩溃**）。

### 9.4 键盘弹收 ✅
两屏弹/收均正常，且收起后 `--oc-ime-bottom` 归 0 自动恢复（见 §3）。

### 9.5 主题 ⚠️ **部分测到**（详见 §10 问题 2）
- **注入** `cmd uimode night no/yes` 切系统深浅：应用前景色 `rgb(0,0,0)` ↔ `rgb(255,255,255)` 随之变化，
  截图 `25_theme_light.png` / `26_theme_dark.png`。
- **但**：系统切到**浅色**时应用**仍显示深色**，`data-theme` 恒为 `oc-2` 未变。
- 推测为**该 AVD 上持久化过显式主题偏好**（应用数据跨轮次保留，`install -r` 不清数据），
  故**不判缺陷**；但**"跟随系统"未能取证**，应用内主题控件本轮**未打开**（见 §10 问题 2）。

### 9.6 官方「设置 → Server → Add server」⚠️ **测不到**（详见 §10 问题 1）
- **已证实的部分**：应用**确实连着 `127.0.0.1:4095` 并正常工作**——
  会话链接形如 `/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/...`
  （`aHR0cDovLzEyNy4wLjAuMTo0MDk1` base64 解码 = **`http://127.0.0.1:4095`**），且 logcat 有持续 200 流量。
- **测不到的部分**：没能点进 `Server` 段做"Add server"新增操作。`/settings` 页正文无 "server" 字样；
  DOM 里**存在** `Server <BUTTON>`（与 `Projects/Worktrees/Providers/Models/Extensions` 同组），
  但它藏在右上角 **`Preferences ⌄` 分段选择器**里，而**该下拉在手机档真实点击多次不展开**。
- ⇒ **如实记为"测不到 + 原因"**，不推测、不编。

### 9.7 平板档
本轮**只做手机**，按分工平板由**验证 B** 负责。**未触碰任何平板实例**，故无平板结论。
（顺带未见任何手机档异常外溢。）

---

## 10. 发现的问题清单（含严重度）

> 本轮为**只读验证**，**只记录、不改代码**。

| # | 问题 | 严重度 | 证据 | 建议 |
|---|---|---|---|---|
| **1** | **手机档 `/settings` 右上角分段选择器（`Preferences ⌄`）真实点击不展开** ⇒ 导致 `Server` 段（含 **Add server**）在手机档**无法进入** | **中**（服务器新增/切换是官方能力入口；已连上的服务器不受影响，但**新配/换服受阻**） | 设备 `(995,215)` 与 chevron `(1077,215)` 各点 3 次均无展开（截图 `32/33/34`）；DOM 中 `Server <BUTTON>` 存在但不可见；`document.body.innerText` 无 "server" | 人工复核：**换平板档点同一控件**是否可展开。若平板可、手机不可 ⇒ 手机档分段选择器需修；若两端都不可 ⇒ 属上游既有形态，不计缺陷。**建议单独排一轮定位**（本人因模拟器两次消失 + 时限未能完成对照） |
| **2** | **系统切浅色时应用仍显示深色**（`data-theme` 恒 `oc-2`） | **低—中**（无法确认是"跟随系统"失效还是持久化偏好） | 截图 `25_theme_light.png`（系统 light + 应用 dark） | 清空应用数据/新装后重测"跟随系统"；若仍不跟随 ⇒ 查 `oc-2` 主题判定 |
| **3** | **模拟器实例被外部杀死 2 次**（`emulator-6710` 从 `adb devices` 消失；同时段 `5614`/`5742` 等**非我方**实例也消失） | **低**（环境问题，非产品缺陷；但**确实打断了验证进度**） | `adb devices` 前后对比；两次均报 `device 'emulator-6710' not found` | 属多轮次并发共用宿主机的资源/清理竞争；建议各轮次**只用自建 serial 精确 kill**（本轮已如此执行） |
| **4** | `research/deliver/` 内两份 release 包时间戳（00:09）比 debug 包（23:45）**晚 24 分钟** | **低**（未证实为缺陷） | `Get-ChildItem research\deliver` | 请后续轮次/负责人确认是否分批重建所致，并复核那两份的 md5 与 HANDOFF §6 表是否一致 |

**未发现的问题（明确记录）**：B-1/B-3/B-4/B-5 两屏/UX8 两条，**本轮均未发现缺陷**。

---

## 11. 收尾复核（实跑抄录）

```
=== 只关自建实例 ===
$ adb -s emulator-6710 emu kill
OK: killing emulator, bye bye
OK

=== 1. 自建残留 ===                       0   （adb devices 中无 6710）
=== 2. 端口 6710 已释放 ===                0   （Get-NetTCPConnection -State Listen 无 6710）
=== 3. 后端未起停（收尾实测在跑）===
    无凭据 GET /api/info -> 401 Unauthorized  （说明服务在跑、在要求认证）
    带 opencode:opencode  -> HTTP 200
    4095 监听中                               1
=== 4. gradle 锁 ===                       .agent-gradle-lock 不存在 = False（本轮从未获取）
=== 5. 只读合规 ===
    research/deliver/opencode-shell-web.apk  5,437,979 B  2026/10/5 23:45:24  （时间戳未变）
    git status --porcelain webapp app 改动数 = 0
```

**碰过的 serial**：`emulator-6710`（**自建，OC_UX6_Phone，端口 6710，已关**）。
**未碰过任何非自建实例** —— `DSH_*` / `T*` / `Pixel_*` / `MCRemote_*` / `Medium_Desktop_B` **零接触**；
**未关过 `OC_Deliver_R2` / `OC_UX6_Phone` 的他方实例**（我用的是自建 serial `emulator-6710`，
只对**这一个 serial** 执行 `emu kill`）。
**未使用** `adb kill-server` / `-a` / 通配符杀进程。
**未起停后端**（只 `adb reverse tcp:4095 tcp:4095`）。
**端口挑选**：≥6710 的偶数 ⇒ **6710**（起前已查 6700–6760 占用；6732/6733 被他方占用，已避开）。

**收尾 `adb devices` 全量**：`5620 / 5622 / 5704 / 5706 / 5710 / 5712 / 5760`（**全部非本轮启动、未碰**）。

---

## 12. 真实 vs 注入 标注汇总

- **真实**：`input tap` 全部控件点击（New session、Terminal 标签、终端区、终端条 `+`、≡ More options、
  会话行、composer、Send、设置抽屉/设置页）；**系统软键盘**真实弹起（全部 IME 场景）；
  **系统返回键** `KEYCODE_BACK`。
- **注入**：`input text`（文本）、`input keyevent 66/3`（回车 / HOME）、
  `am start --es oc_debug_eval`（**只读几何/DOM 探针**）、`settings put system screen_off_timeout`、
  `cmd uimode night`（主题）、`am force-stop`（仅对本轮自建应用实例）、`am start`（回前台）。

## 13. 证据文件（`research/web/verify_a/`）

| 文件 | 内容 |
|---|---|
| `03_newsession.png` / `04_newsession_ime_down.png` | 新建 session 屏：键盘弹起 / 收起 |
| `05_typed.png` | 键盘在场时输入文字，composer 完整 |
| `07_reply_count20.png` | 回复 `1..20`（**本条为熄屏空图，已由 `16` 替代作证**） |
| `13_terminal_typed.png` | **B-3**：终端提示符 + `echo` 回显 |
| `14_plus_tap.png` | **B-1**：`Terminal 3` 直开，无面板 |
| `15_hamburger.png` | **B-4**：上游 `Usage` / `Session details` |
| `16_session_ime_up.png` / `17_session_ime_down.png` | 会话屏：键盘弹起 / 收起（含回复 `1..20`） |
| `18_after_foreground.png` | 回前台重建 SSE 后 |
| `19_hold_past_timeout.png` | **UX8**：跨 15 s 超时仍物理常亮 |
| `20_after_release.png` | **UX8**：释放后 |
| `21_back_twice.png` | 后退回会话列表 |
| `22_switch_session.png` | 切会话 |
| `25_theme_light.png` / `26_theme_dark.png` | 系统浅/深（应用均为深色，见 §10 问题 2） |
| `31/32/33/34_*.png` | 设置页与分段选择器不展开（§10 问题 1） |
