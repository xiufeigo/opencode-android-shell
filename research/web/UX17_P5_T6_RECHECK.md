# UX17-P5 —— 批 #13 T6 定向复验（强制完整日志捕获）

**执行**：UX17-P5 轮　**日期**：2026-10-07　**性质**：**只验证不改代码**（未修改任何源码/构建）
**包**：`research/deliver/opencode-shell-web.apk` = **16,827,044 B** / md5 **`097DA1731F4F4D4FA0A59C78F25D1554`**（装前装后各核对一次，**一致**）
**后端**：`127.0.0.1:4095` pid **21136**，全程**未起停**

## 结论（一句话）

**T6 = 4/4 PASS**（A/B/C/D 全过）。上轮 P4 的 2/4 是**测试时序假阴性**，不是产品缺陷：**P4 的 Send→HOME 仅 76 ms，回合尚未真正开始**；本轮强制"先确认 round 已起再 HOME"（**1501 ms**）后，四格全部转绿。

---

## 0. 环境

| 项 | 值 |
|---|---|
| 设备 | **`emulator-5680`** = `sdk_gphone64_x86_64`，1080×2400 @420dpi，SDK 37（**上轮遗留实例，全程未 kill**） |
| 禁写设备 | `emulator-5554` —— **`adb devices` 只读取，未写入** |
| 包 / activity | `com.xiufeigo.opencodeshell.web.debug` / `com.xiufeigo.opencodeshell.web.WebHostActivity` |
| reverse | `adb -s emulator-5680 reverse tcp:4095 tcp:4095`（收尾已 remove） |
| 注入通道 | **CDP over WebView devtools**：`forward tcp:9222 localabstract:webview_devtools_remote_<pid>`（收尾已 remove） |
| logcat | `adb logcat -v threadtime` 后台进程 **pid 42104**，**10:56:31 起持续落盘**，覆盖装机→导航→发消息→HOME→后台 45 s→回前台整个窗口（≈15 min，远超 ≥90 s 要求） |
| 落盘 | `research/web/out_p5t6/logcat_full.txt`（**4,750,286 B** 收尾实测；`BG45` 标记处 4,359,096 B） |

> 设备时间 = 宿主时间 − 8 h（宿主 11:09 ⇔ 设备 03:09）。

### 0.1 导航偏移（相对 P4 的**新增**实证）

| 项 | P4 记录 | 本轮实证 |
|---|---|---|
| 抽屉按钮 | 设备 `(532,121)` | ✅ 一致 |
| Projects 下拉 | 设备 `(540,263)` | ✅ = CSS `(206,100)` × **dpr 2.625**；WebView 视口 `411×914`，`dpr=2.625` → **CSS px 需 ×2.625 才是设备 px** |
| Submit 收 IME | `keyevent 4` | 本轮 CDP 提交，无需 |

---

## 1. 步骤 0：装机 / 冷启 / Add 4095 / 切 4095

| 阶段 | 结果 | 证据 |
|---|---|---|
| `push` + `pm install -r` | ✅ `Success` | 远端 `ls -l` = 16,827,044 B；`lastUpdateTime=2026-10-07 02:57:08` |
| `pm clear` | ✅ `Success` | |
| `am start` | ✅ 首启 pid 11269 | 冷启内存告警 `NullBinder … TransactionTooLargeException` 为系统侧噪声 |
| Add 4095 | ✅ | CDP 填 `http://127.0.0.1:4095` / `opencode` / `opencode`，**等 16 s ≥14 s** 后生效 |
| 落盘校验 | ✅ | `opencode.global.dat:server` = `{"list":[{"type":"http","http":{"url":"http://127.0.0.1:4095","password":"opencode"},"displayName":"opencode"}]}` |
| POST_NOTIFICATIONS | ✅ | `pm grant` + `dumpsys package` → `granted=true, flags=[USER_SENSITIVE_WHEN_GRANTED\|USER_SENSITIVE_WHEN_DENIED]`（**未依赖弹窗**） |
| 后端可达 | ✅ | 页面内 `fetch /api/info` → **200**，`version 2.0.24`，`pid 21136` |

### 1.1 ⚠️ **"切 4095" 步骤在 fresh `pm clear` 后走不通（与 P4 不同，重要）**

`pm clear` 后服务端表**只有 4095**，但 Home 顶栏仍绑定**本机 4173 资源源**，`All projects` 恒显 `127.0.0.1:4173` 且卡 `Loading`：

```
body: Home0All projects127.0.0.1:4173Loading
[oc-pow] event stream OPEN #24 http://127.0.0.1:4173/api/event status=404
```

复现的 4 条失败路径（P4 未记录）：

| 尝试 | 结果 |
|---|---|
| `location.href='/server/<b64>/'` | ❌ `Something went wrong … An error occurred while loading the application. Version: 2.0.22` |
| `history.pushState` + `popstate` 同路径 | ❌ 同上（未整页刷新也一样崩） |
| `location.href='/server/<b64>/<projectId>'` | ❌ 同上 |
| Projects 下拉（真实 `input tap` 541,262，`aria-expanded=true` 已开） | ❌ 菜单仅 `["127.0.0.1:4173\|opencode"]`，**4095 不在其中** |

**本轮绕过**（不改代码）：在页面内用已认证 `fetch` 于 4095 上 `POST /api/session`（`200`）拿到 `ses_eebaa2f6fffeBH8aBrsP0mnnv3`，再以 **P4 成功过的路由形状**进入：

```
/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_eebaa2f6fffeBH8aBrsP0mnnv3
→ 渲染成功：OSession1SessionChangesFilesTerminalAsk anything…Exo Free
→ composer: div[contenteditable=true]，Send 按钮在位
```

> **差异归因**：4095 侧当时 **12 个 project 全部 0 会话**（`GET /api/session` 全局 `n=0`），故 P4 当年能列出的 6 个会话已不存在。**无会话 ⇒ 下拉无 4095 条目 ⇒ 无法切换**，与 hidden-map / v3 无关。此项属 **UI 可达性缺陷**（fresh 安装 + 新建服务端后，用户无路径切到该服务端），**建议单开 bug**，本轮不判 T6 失败。

---

## 2. 步骤 2：发消息前全量 logcat + 发消息 + 立即 HOME

logcat 早于装机启动（pid 42104），发消息前打标 `MARK PRE-SEND bytes=4185690 devtime=03:09:35`。

| 动作 | 时刻 | 数值 |
|---|---|---|
| 输入 | 11:09:37 | `Count from 1 to 200 comma separated`（len=35） |
| **Send 点击** | 11:09:39.312 | `SENT disabled=false` |
| **确认 round 已起** | 11:09:40.735 | `roundStarted = True`，elapsed **1412 ms** |
| **HOME** | 11:09:40.810 | **Send→HOME = 1501 ms**（P4 为 **76 ms**） |
| 后台驻留 | — | **45 s**（t+5…t+45 每 5 s 轮询一次 `dumpsys notification`） |

> 关键改进：**HOME 前先在 logcat 里确认 `round POST` 已出现**，确保 `onPause` 发生时确有活跃回合。

---

## 3. 四格判定（每格附 logcat 原文行）

### A 传感器乐观置位 —— ✅ **PASS**

```
10-07 03:09:40.082 12970 12970 I OcWebHost: web[LOG] [oc-pow] keepScreenOn -> true busy=[__post__] @791
```

配套原生侧（先于 JS 1 ms 置位）：

```
10-07 03:09:40.077 12970 12970 I OcWebHost: D1 applyKeepScreenOn: roundActive=true foreground=true shouldKeep=true flagSet=true
```

### B onPause 保活分支 —— ✅ **PASS**（取到期望分支，未误伤前台判定）

```
10-07 03:09:40.463 12970 12970 I OcWebHost: P7 onPause: round active -> keep SSE alive (blur only, no pagehide)
10-07 03:09:40.463 12970 12970 I OcWebHost: P6 dispatch synthetic blur (upstream pageLifecycle)
```

- ✅ 未出现 `setKeepScreenOn(true) ignored: not in foreground`
- ✅ `P6` 只发 **blur**、**不发 pagehide** → SSE 按设计保活

### C 后台终帧 —— ✅ **PASS**（`succeeded`）

```
10-07 03:09:40.328 12970 12970 I OcWebHost: web[LOG] [oc-pow] round start session.execution.started @791
10-07 03:09:46.856 12970 12970 I OcWebHost: web[LOG] [oc-pow] round end session.execution.succeeded @791
10-07 03:09:46.857 12970 12970 I OcWebHost: web[LOG] [oc-pow] keepScreenOn -> false busy=[] @791
10-07 03:09:48.478 12970 12970 I OcWebHost: P7 background round ended -> dispatch pagehide (release SSE)
```

回合**完全在后台跑完**：`started 03:09:40.328` → `succeeded 03:09:46.856`，**耗时 6.53 s**，全程 `foreground=false`（HOME 于 `03:09:40.5`）。终帧到达后正确释放：`keepScreenOn -> false busy=[]` → `P7 … dispatch pagehide (release SSE)`。

### D 通知 —— ✅ **PASS**（三证齐全）

**① `dumpsys notification` 本包记录**（t+5 s 首现 1 条，t+10…t+45 稳定 13 行命中）：

```
NotificationRecord(0x0de3c6be: pkg=com.xiufeigo.opencodeshell.web.debug user=UserHandle{0}
  id=1575063443 tag=null importance=3
  key=0|com.xiufeigo.opencodeshell.web.debug|1575063443|null|10235
  : Notification(channel=oc_turns shortcut=null contentView=null vibrate=null sound=null
    defaults=0 flags=AUTO_CANCEL color=0x00000000 category=status vis=PRIVATE))
  contentIntent=PendingIntent{57db31f: … com.xiufeigo.opencodeshell.web.debug startActivity
    (allowlist: 36e72eb:+30s0ms/0/NOTIFICATION_SERVICE/NotificationManagerService)}
  AppSettings: com.xiufeigo.opencodeshell.web.debug (10235) importance=DEFAULT userSet=false
```

**② 下拉截图**（`p5t6_shots/35-shade.png`）与 `uiautomator` 原文：

```
text="OpenCode Web"  bounds=[231,609][451,652]
text="Response ready" bounds=[231,667][996,718]
text="Counting 1 to 200" bounds=[231,739][996,790]
text="1 minute ago" bounds=[482,609][542,652]
```

（余下 3 条为系统通知：Set a screen lock / Physical keyboards / Virtual SD card —— **本包 1 条，无重复**）

**③ 真点回会话**：真实 `input tap 600 700`

```
fg before: topResumedActivity=…nexuslauncher/.NexusLauncherActivity
fg after : topResumedActivity=…com.xiufeigo.opencodeshell.web.debug/…WebHostActivity
url: /server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_eebaa2f6fffeBH8aBrsP0mnnv3
body: OCounting 1 to 2001SessionChangesFilesTerminal…Count from 1 to 200 comma separated
       Build…3:09 AM1, 2, 3, 4, … 200
notif after tap: 0        ← AUTO_CANCEL 生效，通知正常消失
```

通知**精确回跳到发起回合的那个会话**，且助手回复全文已在屏（`p5t6_shots/36-returned.png`）。

---

## 4. 步骤 4 补充取证

- **A 存在**，故无需注入 `window.__ocRoundPowerSensor.installed` 只读打点。
- **B 存在**，按要求抄录 onPause 前后原文（节选，`logcat_full.txt` 全文行 29575–29601）：

```
10-07 03:09:40.447  1154  1154 D TFOManager: playPanelCloseAnimation() duration=200 …isHomeButtonTap=true
10-07 03:09:40.449   985  1025 D WindowManagerShell: onActivityRestartAttempt: topActivity=ComponentInfo{…nexuslauncher…}, wasVisible=false
10-07 03:09:40.451  1154  1154 D StatsLog: LAUNCHER_ONRESUME
10-07 03:09:40.457   715  1091 V WindowManager: Continue transition id=57 …
10-07 03:09:40.463 12970 12970 I OcWebHost: P7 onPause: round active -> keep SSE alive (blur only, no pagehide)
10-07 03:09:40.463 12970 12970 I OcWebHost: P6 dispatch synthetic blur (upstream pageLifecycle)
10-07 03:09:40.465  1154  1205 D QuickstepModelDelegate: notifyAppTargetEvent action=1 …
```

可见 **HOME 事件（`isHomeButtonTap=true` → `LAUNCHER_ONRESUME`）与 `onPause` 同帧（同一毫秒 40.463）**，`onPause` 内 `roundActive` 已为 true，故走保活分支而非释放分支。

---

## 5. P4（2/4）→ 本轮（4/4）**变量归因**

| 维度 | P4 批 #13 | 本轮 P5 | 判定 |
|---|---|---|---|
| Send→HOME 延迟 | **76 ms** | **1501 ms** | ★ **主因** |
| HOME 时 `roundActive` | **false**（`applyKeepScreenOn: roundActive=false`） | **true**（`roundActive=true foreground=true`） | ★ 由此直接分叉 |
| 发消息前是否确认 round 已起 | ❌ 未确认 | ✅ 轮询 logcat 确认（1412 ms） | ★ 方法差异 |
| 消息 | `Count from 1 to 500`（后台 45 s 不足以跑完，实测 `Build 4m 0s`） | `Count from 1 to 200`（**6.53 s 跑完**） | 次因（决定 C/D 能否在窗口内落地） |
| 传感器 | Kotlin 同一支 | 同 | **排除** |
| 通知权限 | `granted=true`（未走弹窗） | `granted=true`（未走弹窗） | **排除** |
| 后端 / 包 | 4095 pid 21136 / md5 097DA173 | 同 | **排除** |

> 批 #11 四格全过、批 #13 两格失败的"变量不明"，**已定位为测试时序（76 ms 过早 HOME）+ 任务量（500 跑不完 45 s）**，非 Kotlin 传感器支路差异。P4 报告已自行提出同一怀疑（"Send 与 HOME 间隔仅 76 ms，回合可能尚未真正开始"），本轮以受控实验证实。

---

## 6. 收尾

| 项 | 结果 |
|---|---|
| `adb -s emulator-5680 reverse --remove-all` | ✅ `reverse --list` 回读为**空串** |
| `adb -s emulator-5680 forward --remove-all` | ✅ `forward --list` 回读为**空串**（9222 已清） |
| logcat 采集进程 pid 42104 | ✅ 已终止（`Get-Process` 回读为空） |
| 设备 `/data/local/tmp/oc.apk` | ✅ 已删除（`ls` 回读 `No such file or directory`） |
| 后端 pid 21136 | ✅ **未动**：`Get-Process` 同 pid，`StartTime=2026-10-07 08:16:25`；`netstat` `127.0.0.1:4095 LISTENING 21136`；`/api/info` 回 **401 Unauthorized**（未带 auth 的正常应答，即服务在线） |
| AVD | **未 kill** —— `emulator`/`qemu` 进程 34408、36248、20212、29920 **全部仍在**（`emulator-5680` 为上轮遗留实例；本轮未自建 6790，**规避 AVD 锁**）；`emulator-5554` 未写入 |
| 碰过的 serial | **`emulator-5680`**（唯一）。`adb devices -l` 回读两台：`emulator-5554`（Pixel_Tablet，仅读取）、`emulator-5680`（本轮全程使用） |

---

## 7. 汇总

| 格 | 判定 | logcat 原文 / 实证 |
|---|---|---|
| **A** 传感器乐观置位 | ✅ PASS | `10-07 03:09:40.082 … [oc-pow] keepScreenOn -> true busy=[__post__] @791` |
| **B** onPause 保活分支 | ✅ PASS | `10-07 03:09:40.463 … P7 onPause: round active -> keep SSE alive (blur only, no pagehide)` |
| **C** 后台终帧 | ✅ PASS（**succeeded**） | `10-07 03:09:46.856 … [oc-pow] round end session.execution.succeeded @791` |
| **D** 通知 | ✅ PASS | `dumpsys` 1 条 `channel=oc_turns` + 下拉 `OpenCode Web/Response ready/Counting 1 to 200` + 真点回原会话 |

**T6 = 4/4 PASS。** 无失败格，故无失败断点。

**遗留（不属 T6，建议单开）**：`pm clear` + 新建服务端后，若该服务端**无任何会话**，UI **无路径切换到它**（Projects 下拉不含 4095，`/server/<b64>/` 整段路由崩 `Something went wrong`）——用户侧不可达缺陷。

**报告路径**：`research/web/UX17_P5_T6_RECHECK.md`
**原始日志**：`research/web/out_p5t6/logcat_full.txt`
**证据截图**：`research/web/p5t6_shots/`（`32-typed` / `33-home` / `34-bg45` / `35-shade` / `36-returned`）
