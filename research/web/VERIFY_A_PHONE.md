# 验证 A（手机档独立验证）报告

- **角色**：验证 A —— 手机档独立验证，**只读**（不改 `webapp/**`/`app/**`，不重建 `research/deliver/**`）
- **执行方式**：mcode（minimax）
- **开始**：2026-10-06 00:16（Asia/Shanghai）
- **自建实例**：`OC_UX6_Phone` 起在 **端口 6710** ⇒ serial `emulator-6710`（仅此一台）
- **后端**：`127.0.0.1:4095`（用户 `opencode` / 口令 `opencode`）——**全程未起停**，只做 `adb reverse`

> 本报告为**独立取证**：每条结论均由本人动手获得，不引用 UX7/UX8 报告的结论作为事实。
> 凡引用他人报告处，均显式标注"（引自 UXx，非本人取证）"。

---

## 0. 结论速览（实时更新，最终以 §9 为准）

| 项 | 状态 | 备注 |
|---|---|---|
| 交付包 md5 vs 文档 | 见 §1 | |
| 设备端 base.apk md5 = 交付包 | 见 §1 | |
| B-1 终端条 `+` 直开终端 | 待测 | |
| B-3 手机终端可用 | 待测 | |
| B-4 右上角 ≡ 为上游菜单 | 待测 | |
| B-5 IME composer（两屏） | 待测 | |
| UX8 常亮置起/释放 | 待测 | |
| UX8 SSE 后台断/前台重建 | 待测 | |
| 回归项 | 待测 | |

---

## 1. 交付包身份核验（✅ 三方一致）

| 环节 | 值 |
|---|---|
| 交付包文件 | `research/deliver/opencode-shell-web.apk`，5,437,979 B，时间戳 2026-10-05 23:45:24 |
| **交付包 md5（本人 `Get-FileHash` 实算）** | `e54752db94e2528d54f0774b8f7b9646` |
| `HANDOFF_WEB_SHELL.md:35` / `:87` 记录值 | `e54752db94e2528d54f0774b8f7b9646` |
| **设备端 `base.apk` md5（`adb shell md5sum` 实跑）** | `e54752db94e2528d54f0774b8f7b9646` |

⇒ **交付包 md5 与文档一致；设备端 base.apk md5 = 交付包 md5。**
包名 `com.xiufeigo.opencodeshell.web.debug`（不是 `com.sensorsdata.*`，装包前按 `pm list packages` 实查得出）。

⚠️ **一处需点名的观察（不影响本次结论）**：`research/deliver/` 目录内另有
`opencode-shell-web-release-nor8.apk` 与 `opencode-shell-web-release-r8.apk` 两个文件的
**时间戳是 2026-10-06 00:09:05，比 `opencode-shell-web.apk`（23:45）晚约 24 分钟**。
两者与 HANDOFF §6 表中所记 md5 我未逐一核对（本次只验 debug 包）。**本报告不据此断言交付不一致**，
仅如实记录"同目录三个产物时间戳不一致"这一事实，交由产品负责人/后续轮次判断是否为预期（分批重建所致）。

---

## 3. B-5 手机档 composer 裁切（两屏 × 键盘弹/收）

### 3.1 新建 session 界面（✅ PASS）

**真实点击**：`input tap` 点 "New session" ⇒ 该屏**系统软键盘真实自动弹起**（截图 `03_newsession.png`，
可见 Gboard 全键盘 + 候选条 + 工具栏，**非注入**）。
探针用 `am start --es oc_debug_eval`（**程序注入，仅读取几何、不改状态**）。

| 状态 | `--oc-ime-bottom` | `innerHeight` | composer form | padding-bottom | 判定 |
|---|---|---|---|---|---|
| **键盘弹起** | `336.38px` | 1018 | top 418 / **bot 858** | `336.38px` | 文本框 `[418,478]`、模型行、发送钮**全部落在键盘上方** ⇒ 未裁切 |
| **键盘收起**（真实 `KEYCODE_BACK`） | `0.00px` | 1018 | top 418 / **bot 522** | `0px` | **自动恢复原状**（与产品负责人"收起就恢复"一致） |

**量化断言（本人算）**：
- 键盘顶边 = `innerHeight − --oc-ime-bottom` = `1018 − 336.38` = **681.6 CSS px**；
- composer **可见内容**下沿（发送钮所在模型行）≈ **520 CSS px**（截图实测换算），
  ⇒ `520 < 681.6`，**内容在键盘上方 161.6 px 处，未被裁切、也未被顶进状态栏**。
- 布局视口**不随 IME 变化**：`innerHeight=1018` vs `visualViewport.height=1018.29`（差 0.29），
  证实边到边（`setDecorFitsSystemWindows(false)`）+ 靠 `--oc-ime-bottom` 抬升，**不是"页面被顶起"**。
- dpr 实测 `2.625`；336.38 CSS px × 2.625 = **883.0 device px**（与 IME frame 高一致）。

---

### 3.2 会话界面（✅ PASS）

**真实点击**：切到 `Session` 标签 ⇒ **真实点击** composer（`input tap 577 2441`）⇒ 系统 Gboard 真实弹起
（截图 `16_session_ime_up.png`：软键盘 + 候选条 + 工具栏全在，**非注入**）。

| 状态 | `--oc-ime-bottom` | composer/form | padding-bottom | 判定 |
|---|---|---|---|---|
| **键盘弹起** | `336.38px` | `[557, 997]` | `336.38px` | 内容区 = `997 − 336.38` = **660.6**，**完整落在键盘上方** ⇒ 未裁切 |
| **键盘收起**（真实 BACK） | `0.00px` | `[893, 997]` | `0px` | **自动恢复原状** |

**量化断言**：键盘顶边 = `1018 − 336.38` = **681.6**；composer 内容下沿 = `997 − 336.38` = **660.6**；
⇒ `660.6 < 681.6`，**余量 21.0 px**，输入行 / `+` / 模型行 / 发送钮**四项全可见**（截图逐项可见）。

### 3.3 B-5 合计结论

**两屏 × 弹/收 = 4 个场景全部 PASS**，产品负责人报告的"输入框底部掉下去"**在交付包上不复现**。
机制与 UX7 报告口径一致（`--oc-ime-bottom` 垫高），但**读数是本人本次实采**。
⚠️ 口径提醒：`innerHeight=1018` 全程不随键盘变化，`visualViewport.height=1018.29`（仅差 0.29）——
说明**布局视口不缩**、靠变量抬升内容，**不是"页面被顶起"**。

---

## 4. B-3 手机档终端（✅ PASS，原报告"空白+无输入"不复现）

**操作链（全真手）**：`input tap` 点 `Terminal` 标签（实测坐标 `@[312,98,101x35]` CSS → 设备 `(951,303)`）
→ 点终端区聚焦（**真实软键盘随之弹起**）→ `input text`（**注入文本**）→ `input keyevent 66`（**注入回车**）。

| 证据 | 读数 |
|---|---|
| 容器非 0 高 | `#terminal-panel` 存在，rect `[134, 1009]`（**875 CSS px 高**），宽 440 |
| xterm canvas 在渲染 | canvas `[198, 984, 394×786]`（**非 0 宽高**） |
| **有提示符** | 截图 `13_terminal_typed.png`：`Windows PowerShell / Copyright (C) Microsoft Corporation` + `PS C:\sensorsdata\main\program\opencode-android-shell>` |
| **能敲命令** | `echo` 关键字**语法高亮**（黄色）⇒ 输入确实进了终端仿真器 |
| **有回显** | 命令下方输出 `VA-TERM-OK`，并回到新提示符 + 光标 |

⇒ **B-3 PASS**。（PTY 链路亦见 logcat：`/api/pty/pty_10ce2fae…` + `connect-token` 200。）

---

## 5. B-1 终端标签条 `+`（✅ PASS）

**操作**：会话 → `Terminal` 标签 → 终端条已显示 `Terminal 1 ×  Terminal 2 ×  +`
→ **真实点击** 终端条 `+`（设备 `(700,419)`）。

| 检查 | 结果 |
|---|---|
| 弹出我们的「打开/打开终端/打开文件」面板？ | **没有**。截图 `14_plus_tap.png` 直接变成 `Terminal 1 / Terminal 2 / **Terminal 3** / +` |
| logcat 拦截记录 | `oc-plus-chooser` / `intercep` 过滤 **0 命中** |
| 上游行为 | 新 PTY 被创建：`16:26:05.045 /api/pty/pty_10ce2fae…` → `16:26:05.050 …/connect-token` |
| 新终端是否可用 | **是**，`Terminal 3` 自动激活且有活提示符 |

⇒ **B-1 PASS**。

---

## 6. B-4 右上角 ≡（✅ PASS；且"无右侧栏/无左滑"是上游本来的样子）

**真实点击** `More options`（`aria-label` 实测 = **More options**，CSS `@[419,104,24x24]` → 设备 `(1131,304)`）。

| 检查 | 结果 |
|---|---|
| 弹出的是哪个面板 | 截图 `15_hamburger.png`：**上游菜单** = `Usage` / `Session details` |
| 是我们的「打开…」面板吗 | **不是**；logcat 拦截过滤 **0 命中** |

**关于"左滑打不开右侧栏 / 手机档没有右侧栏"**：
- 设备实测**无右侧栏**：`#review-panel` 在手机档**不存在**（UX7 §1.2 指出其挂载条件为
  `isDesktop() && params.id`，`min-width: 768px`；本档 CSS 宽 457.14dp < 768dp）。
- ⇒ 手机档的对应物就是这条 **`Session / Changes / Files / Terminal` 视图标签条**（本人实测存在）。
- 按规格"**不要把它当缺陷**"：本轮**不判 FAIL**，记为"上游手机档形态"。

---

## 7. UX8-① 回合常亮（flag 真被置起 ✅ / 释放 ✅）

**真实点击**发送按钮（设备 `(1064,1310)`）；`screen_off_timeout` 由本人注入设为 **15000 ms**。

**置起（三段证据）**（logcat 原文，设备本地时戳）：
```
16:21:27.448  [oc-pow] keepScreenOn -> true busy=[__post__]
16:21:27.449  [oc-pow] round POST /api/session/ses_ef32146acffeKr1xp3umbQ9iee/prompt
16:21:27.451  D1 applyKeepScreenOn: roundActive=true foreground=true shouldKeep=true flagSet=true   ← flag 真被置起
16:21:28.182  [oc-pow] round start session.execution.started
```
⇒ 不是"没看到它挂着"，而是 **`flagSet=true` 由宿主自己打出来**（POST 后 **2 ms**）。

**释放（时间读数）**：
```
16:21:34.503  [oc-pow] round end session.execution.succeeded
16:21:34.504  [oc-pow] keepScreenOn -> false busy=[]
16:21:34.560  D1 applyKeepScreenOn: roundActive=false ... flagSet=false
```
⇒ **释放 Δ = 1 ms（POST 层） / 57 ms（宿主 flag）**，回合总时长 6.32 s（`28.182 → 34.503`）。
**无"只做一半"、无 4 分钟钉亮**；释放后 `16:21:50.464` 屏幕正常熄（`mWakefulness` 由 Awake → **Dozing**），
恰好是 15 s 超时后自然入睡，**说明释放确实生效**。

**Round B（长回合 24.4 s，跨越 15 s 超时点）—— 物理常亮 + 按时释放**：

发送（**真实点击** Send 按钮 `aria-label="Send"`，CSS `@[405,627,24x24]` → 设备 `(1095,1677)`）于 `00:29:20.086`，
`screen_off_timeout=15000` 由本人注入设定。

| 检查 | 读数 |
|---|---|
| **置起** | `16:29:21.498 D1 applyKeepScreenOn: roundActive=true foreground=true shouldKeep=true **flagSet=true**`（POST 后 0 ms） |
| 回合开始 | `16:29:22.213 [oc-pow] round start session.execution.started` |
| **物理常亮（跨越超时点）** | **t+20 s（> 15 s 超时）`mWakefulness=Awake`**，截图 `19_hold_past_timeout.png` 屏幕实亮、回合仍在跑 ⇒ **屏幕确实被钉住** |
| **回合结束** | `16:29:46.635 [oc-pow] round end session.execution.succeeded`（回合总时长 **24.42 s**） |
| **释放** | `16:29:46.635 keepScreenOn -> false busy=[]`（**Δ = 0 ms**）→ `16:29:46.723 flagSet=false`（**Δ = 88 ms**） |
| **释放后能睡** | `16:30:02.411` 屏幕自然熄（释放后 ~15.7 s，与 15 s 超时吻合），截图 `20_after_release.png` |

⇒ **UX8-① PASS（三层证据齐全）**：① `flagSet=true` 宿主日志；② 跨越超时的物理常亮；③ 回合结束后 0–88 ms 释放、
且 15 s 后确实能睡 ⇒ **不是"只做一半"、无 4 分钟钉亮**。

⚠️ Round A（6.32 s）因**短于 15 s 超时**，只能证明置起与释放；Round B 才补上"跨超时物理常亮"这一层。

---

## 8. UX8-② 后台断 SSE / 回前台重建（✅ PASS，配对读数）

| 动作 | 注入方式 | logcat 原文 | 延迟 |
|---|---|---|---|
| 切后台 | **注入** `input keyevent 3`（HOME） | `16:27:38.125 P6 dispatch synthetic pagehide` → `16:27:38.146 [oc-pow] event stream CLOSE #7 cancel` | **21 ms** |
| 回前台 | **注入** `am start` | `16:27:47.204 P6 dispatch synthetic pageshow` → `16:27:47.848 [oc-pow] event stream OPEN #8 /api/event` | **644 ms** |

**后台实证**：`dumpsys window mCurrentFocus` = `com.google.android.apps.nexuslauncher.NexusLauncherActivity`
⇒ 切后台时确已离开本应用，**流是被真实掐断**（`cancel`），不是"没断开只是没看见"。
回前台后 `#7 → #8` 序号递进、重新 `GET /api/event`，**连接确实重建**。

---

## 2. 环境与几何

- **自建实例**：`OC_UX6_Phone` 启动于 **端口 6710** ⇒ serial **`emulator-6710`**（本轮唯一自建实例）。
  `adb install -r -d` → `Success`（0.8 s）。
- **开机**：`sys.boot_completed=1`，耗时 34.8 s。
- **手机档几何（`wm size` / `wm density` 实测确认）**：
  - `Override size: 1200x2670`（Physical 1080x2400 被覆盖）
  - `Density: 420`
  - ⇒ **dpr = 420/160 = 2.625**；**CSS 逻辑尺寸 = 1200/2.625 × 2670/2.625 = 457.14dp × 1017.14dp**
  - 与产品负责人报告环境（1200×2670）同款几何。
- **后端**：`adb -s emulator-6710 reverse tcp:4095 tcp:4095` ⇒ `reverse --list` 显示 `4095`（**仅建反向通道，未起停后端**）。
