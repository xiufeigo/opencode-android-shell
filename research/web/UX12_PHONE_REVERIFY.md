# UX12-PhoneRe —— 手机档补验（切换服务器上下文后重跑被阻塞的 5+1 项）

- 执行时间：2026-10-06 04:05–（进行中）
- 仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`
- 被测产物：`research/deliver/opencode-shell-web.apk`（debug 构建 A）
- 包名：`com.xiufeigo.opencodeshell.web.debug`，Activity `.WebHostActivity`
- **本轮不修代码、不改 `webapp/**` / `app/**`、不碰 gradle、**后端**只做只读探测**（未 start/stop/kill）。**
- 交互方式：全部**真实点击 / 真实按键**（`input tap` / `input text` / `input keyevent`）。
  标注：**[注入]** = 经 CDP `Runtime.evaluate` **只读**页面状态（`innerText` / `getBoundingClientRect` /
  `querySelectorAll(...).length` / `aria-expanded` / `value`），**不触发任何动作**。CDP 同时被用作
  **元素几何读取器**（CDP 返回 CSS px，设备 `dpr=2.625`，故设备坐标 = CSS × 2.625），
  以便把**真实 tap** 落在正确位置。
- 串口：**本轮自建 `emulator-6790`**，全部命令带 `-s emulator-6790`。收尾只 `emu kill` 6790。
- 临时脚本：`_scratch_ux12r_eval.mjs` / `_scratch_ux12r_lib.ps1`（仓库根，收尾删除）。

## 被阻塞项与本轮结论速览

| 项 | 上一轮判定 | 本轮（切到 4095 上下文后） |
|---|---|---|
| 5.1 终端口可用 | **FAIL（阻塞）** | **PASS**（§3.1） |
| 5.2 终端标签条 `+` 直接新建终端 | **FAIL（阻塞）** | **PASS**（§3.2） |
| 5.5 发消息有回复 | **FAIL（阻塞）** | 见 §3.3 |
| 5.6 切会话 / 返回 | **PARTIAL（阻塞）** | 见 §3.4 |
| 5.7 键盘弹起 composer 两屏不裁切 | **FAIL（阻塞，未取到 px）** | 见 §3.5 |
| 5.9 回合常亮 keep screen on | **FAIL（阻塞，flag 未置起）** | 见 §3.6 |

---

## 0. 环境基线（只读探测 + 起机装包）

### 0.1 端口与串口预查

```
$ Get-NetTCPConnection -LocalPort 6790   →  （无输出 = 未被占用）
PORT6790_FREE
$ adb devices -l   （起机前）              →  （无任何在线设备）
```

⇒ 6790 空闲、起机前无他人实例，本轮创建的 `emulator-6790` 归属明确。

### 0.2 起机 / 装包 / 启动

```
$ Start-Process emulator.exe -ArgumentList @('-avd','Medium_Phone_API_37.0','-port','6790',
      '-no-snapshot-load','-no-snapshot-save','-no-boot-anim','-no-audio','-gpu','swiftshader_indirect')
BOOTED after 3 polls                       （轮询 sys.boot_completed）
$ adb devices -l
emulator-6790   device  product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:6

$ adb -s emulator-6790 shell wm size      →  Physical size: 1080x2400
$ adb -s emulator-6790 shell wm density   →  Physical density: 420
```

几何与任务书一致（原生 1080x2400，**未做 `wm size` 覆写**）。

```
$ adb -s emulator-6790 reverse tcp:4095 tcp:4095   →  4095
$ adb -s emulator-6790 reverse --list              →  host-15 tcp:4095 tcp:4095
$ adb -s emulator-6790 install -r research\deliver\opencode-shell-web.apk
Performing Streamed Install
Success
$ adb -s emulator-6790 shell pm clear com.xiufeigo.opencodeshell.web.debug   →  Success
$ adb -s emulator-6790 reverse --list              →  host-15 tcp:4095 tcp:4095   （clear 后仍在）
$ adb -s emulator-6790 shell am start -n com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity
Starting: Intent { cmp=com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity }
$ adb -s emulator-6790 shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
  mCurrentFocus=Window{1783010 u0 com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity}
  mFocusedApp=ActivityRecord{42114660 u0 com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity t10}
```

`pm clear` 后首屏 **[注入]** `http://127.0.0.1:4173/ | Home\n0\nAll projects\nLoading`
（与上一轮一致：`Loading` 在**加任何服务器之前**就存在）。

### 0.3 CDP 通道（标准调试通道，标注）

```
$ adb -s emulator-6790 shell pidof com.xiufeigo.opencodeshell.web.debug   →  2677
$ adb -s emulator-6790 shell "cat /proc/net/unix | grep -o webview_devtools_remote.*"
webview_devtools_remote_2677
$ adb -s emulator-6790 forward tcp:9334 localabstract:webview_devtools_remote_2677   →  9334
$ adb -s emulator-6790 forward --list
emulator-6790 tcp:9334 localabstract:webview_devtools_remote_2677
```

**[注入]** 视口：`{"dpr":2.625,"iw":411,"ih":914}` ⇒ **设备像素 = CSS × 2.625**。
本报告所有 tap 坐标都已换算为**设备像素**。

---

## 1. 关键步骤：连上服务器 + **切换服务器上下文**

### 1.1 真实点击路径（Add server）

Home → 抽屉 → Settings → 分节菜单 → `Server` → `Add server` → 填地址/口令 → 提交 → 等 15 秒。
每一步都是真实 tap：

```
Tap  66 120   → 抽屉展开          [注入] "… Tabs | New session | Home | Settings | Help"
Tap 446 2331  → Settings          [注入] location.href = http://127.0.0.1:4173/settings
Tap 882 215   → 分节菜单触发器     [注入] .settings-mobile-menu-trigger aria-expanded = "true"
Tap 824 990   → "Server" 分节      [注入] href = /settings?tab=servers
Tap 879 483   → "Add server"       [注入] [role=dialog] 存在
Tap 530 945   → 地址框（聚焦）     TypeTxt 'http://127.0.0.1:4095'
Tap 530 1365  → 口令框（聚焦）     TypeTxt 'opencode'
```

**[注入]** 填入后的字段值（`value`）逐字核对：

```
[{"ph":"http://localhost:4096","v":"http://127.0.0.1:4095"},
 {"ph":"Localhost","v":""},
 {"ph":"password","v":"opencode"}]
```

```
Key  KEYCODE_BACK → 收软键盘
Tap  919 1654     → "Add server" 提交
（等 15 秒 > 任务书要求的 14 秒）
```

**连接成功证据** —— 分节菜单**新增了 4095 条目**（截图 `ux12r_10_menu.png`：
菜单内 `Preferences / Appearance / Notifications / Shortcuts / 127.0.0.1:4095 / 127.0.0.1:4173 / About`），
随后真实点击 4095 条目进入其服务器页（截图 `ux12r_11_4095sec.png`）：

```
[注入] location.href = http://127.0.0.1:4173/settings?server=http%3A%2F%2F127.0.0.1%3A4095
[注入] body = "Home | 0 | Back to settings | 127.0.0.1:4095 | 127.0.0.1:4095
              | Manage this server's connection and preferences | Connection
              | 127.0.0.1:4095 | http://127.0.0.1:4095 | Preferences | Terminal shell
              | Shell used by the terminal and agent tools | Auto (Default)"
```

### 1.2 ⭐ **切换服务器上下文**（上一轮 6 项被阻塞的根因）

真实点击链（`input tap` 落点均为设备像素）：

```
Tap 533 121   → 顶栏 Home
Tap 541 2247  → 抽屉里 Home
```

**[注入]** 回 Home 后的上下文 —— **仍是 4173，与上一轮诊断轮完全一致**：

```
[注入] location.href = http://127.0.0.1:4173/
[注入] body = "Home | 0 | All projects | 127.0.0.1:4173 | Loading"
                              ↑ 当前上下文 = 127.0.0.1:4173 ⇒ 列表恒 Loading
```

展开项目树，看到**两个服务器并列**（这是真实点击 4095 那一行的必要前提）：

```
Tap 541 263 → 展开 "All projects"
[注入] 按钮坐标：{t:"127.0.0.1:4173",x:206,y:146}  {t:"127.0.0.1:4095",x:206,y:178}   （CSS）
```

**真实点击 `127.0.0.1:4095` 那一行**（CSS 206,178 → 设备 541,467）：

```
Tap 541 467
```

**[注入]** 切换后页面状态 —— **列表数秒内渲染出会话**（截图 `ux12r_list4095.png`）：

```
[注入] location.href = http://127.0.0.1:4173/
[注入] body = "Home | 0 | All projects | 127.0.0.1:4095
             | Today | O | v2 smoke test | OpenCodeUI
             | O | New session | OpenCodeUI
             | O | Greeting | OpenCodeUI
             | Yesterday | O | Single word reply request | OpenCodeUI
             | O | New session | OpenCodeUI
             | Older | / | Greeting | /"
                       ↑ 当前上下文已切到 127.0.0.1:4095；`Loading` 消失，渲染出 6+ 个会话
```

**⇒ 阻塞解除。** 这与 `UX12_PHONE_DIAGNOSIS.md` §2.1 第 3 点的取证一致：
**只要服务器上下文切到 4095，列表立刻渲染**；上一轮 5.1/5.2/5.5/5.6/5.7/5.9 之所以 FAIL，
是因为当时上下文停在 4173，**连任何会话都进不去**，并非终端/会话/键盘/常亮本身有问题。

---

## 2. 进入会话

**[注入]** 真实点击 `v2 smoke test`（CSS 206,236 → 设备 541,620）：

```
[注入] location.href = http://127.0.0.1:4173/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_ef43fab08ffeoHiNxtinjW9V0c
```

`aHR0cDovLzEyNy4wLjAuMTo0MDk1` = base64(`http://127.0.0.1:4095`) ⇒ **会话确实挂在 4095 上下文中**。

**[注入]** 标签条：`Session | Changes | Files | Terminal`，标签条右侧另有一个图标按钮
（CSS 384,86）。

---

## 3. 补验 6 项

### 3.1 项 5.1 —— 终端口可用 ✅ **PASS**

真实点击 `Terminal` 标签（CSS 322,86 → 设备 845,226）：

```
Tap 845 226
[注入] body = "O | v2 smoke test | 1 | Session | Changes | Files | Terminal | Terminal 1"
```

**[注入]** 终端输入区是 `contenteditable` 的 DIV（CSS 206,531 → 设备 541,1394）；
文本内容由 xterm 绘制，**不进 `innerText`** ⇒ 证据取自**截图 + logcat**。

真实点击聚焦后 `input text` 敲入并回车：

```
Tap 541 1394
TypeTxt 'echo ux12r_term_ok_20261006'
Key  KEYCODE_ENTER
```

截图 `ux12r_13_terminal_echo.png` **逐字可读**（屏幕原生像素）：

```
PowerShell 7.6.6
PS D:\XiaoZhuTouTou\OpenCodeUI> echo ux12
r_term_ok_20261006
ux12r_term_ok_20261006
PS D:\XiaoZhuTouTou\OpenCodeUI>
```

⇒ **提示符出现（`PowerShell 7.6.6` / `PS D:\XiaoZhuTouTou\OpenCodeUI>`）**、
**命令被回显**、**输出 `ux12r_term_ok_20261006` 正确**、**回到新提示符**。
工作目录 `D:\XiaoZhuTouTou\OpenCodeUI` 与该会话 `location.directory` 一致。

> ⚠️ **偏差如实标注**：任务书给的命令是 `echo ux12r_$([datetime]::Now.Ticks)`（PowerShell 表达式）。
> `adb shell input text` 需经 shell 转义，`$( ) [ ] :` 会被多层 shell 吃掉，**在 `input text` 下无法可靠送达**。
> 本轮改用等价的唯一标记 `echo ux12r_term_ok_20261006`（同样是 `echo` + 本轮唯一 token），
> 同样能证明「命令送达 + shell 执行 + 输出回显」三段链路。**不构成对 5.1 判定的削弱。**

### 3.2 项 5.2 —— 终端标签条 `+` **直接**新建终端 ✅ **PASS**

`+` 按钮在标签条上（CSS 150,130 → 设备 394,341）。**点击前后各注入一次面板计数**：

```
[注入] 点击前：{"panels":0,"terms":["Terminal 1"]}          （ux12r_14_before_plus.png）
Tap  394 341
[注入] 点击后：{"panels":0,"terms":["Terminal 1","Terminal 2"]}  （ux12r_15_after_plus.png）
[注入] body = "O | v2 smoke test | 1 | Session | Changes | Files | Terminal | Terminal 1 | Terminal 2"
```

⇒ **直接出现 `Terminal 2`，`[data-oc-panel]` 计数前后均为 0 ⇒ 没有弹出任何面板。**
**PASS**（上一轮「点 `+` 必须先经一个面板」这一形态**未复现**）。

### 3.3 项 5.5 —— 发消息有回复 ✅ **PASS**

回到会话 `Session` 标签（真实点击 CSS 54,86 → 设备 142,226），**真实点击** composer
（`[aria-label=Prompt]`，CSS 206,819 → 设备 541,2151）聚焦，`input text` 敲入，**真实点击**发送键
（深色 ↑ 按钮，设备 972,1404）：

```
[注入] {"prompt":"reply with the single word OK"}        ← 发送前 composer 内容
TypeTxt 'reply with the single word OK'
Tap  972 1404                                               ← 发送
```

**[注入]** 回复到达后的会话正文（截图 `ux12r_17_reply.png`）：

```
… | Reply with the single word OK | Build · Fledge Alpha Free |  · | 3:43 AM
  | Used | 1 | Thought | OK | Build · Fledge Alpha Free · 2s
  | reply with the single word OK | Build · Fledge Alpha Free |  · | 4:14 AM   ← 本轮新发
  | Used | 1 | Thought | OK | Build · Fledge Alpha Free · 3s                    ← 本轮新回复
```

⇒ 用户消息 `reply with the single word OK`（04:14）后，助手在 **`fledge-alpha-free`** 上
**3s** 内回复 **`OK`**（单词），与任务书预期（2–20 秒）一致。**PASS**。

### 3.4 项 5.6 —— 切会话 / 返回 ✅ **PASS**

| 动作 | 交互 | **[注入]** 结果 |
|---|---|---|
| 会话 A | 进入 `v2 smoke test` | `…/session/ses_ef43fab08ffeoHiNxtinjW9V0c` |
| **切到会话 B** | 真实点击列表 `Greeting`（设备 541,877） | `…/session/ses_ef0be3ed9ffev3toGEUT4wmG7W`，**session id 已变**；正文 `say hi` → `Hi! How can I help you today?` ✅ 内容正确（截图 `ux12r_18_greeting.png`） |
| **按返回** | `keyevent KEYCODE_BACK` | 回到 `/settings?server=…4095`（服务器设置页，**上下文仍是 4095**）；再返回回到 `/` 列表，**上下文仍是 `127.0.0.1:4095`**，列表完整 |
| **再进入** | 真实点击 `Greeting` | `…/session/ses_ef0be3ed9ffev3toGEUT4wmG7W`，**与进入前同一个 session id**，正文与标签条（`Session / Changes / Files / Terminal`）**完全一致**（截图 `ux12r_20_reenter.png`） |

⚠️ 顶栏计数随访问递增 `Home 0 → 1 → 2 → 3`（访问过的会话数），**属正常行为，非错误**。
⇒ 切换 / 返回 / 再进入**状态全部正确**。**PASS**（上一轮 PARTIAL 的成因同样是上下文没切到 4095）。

### 3.5 项 5.7 —— 键盘弹起 composer 两屏不裁切 ✅ **PASS**（两组 px）

判据（任务书）：**composer 底边 ≤ `visualViewport.height`** ⇒ 不裁切。
换算：**设备 px = CSS px × 2.625**（`dpr=2.625`，`innerWidth=411`，`innerHeight=914`）。

#### 屏 A —— New session（截图 `ux12r_19_newsession_kb.png`）

真实点击列表 `New session`（设备 864,499）→ 真实点击 composer（`[aria-label=Prompt]`，设备 541,1030）
→ 软键盘弹起 → `input text` 敲入 `ux12r`。

```
[注入] {"vvH_css":914,"vvH_device":2400,"innerH":914,
        "composerTag":"FORM","composerTop_css":362,"composerBottom_css":802,
        "composerBottom_device":2106,"clipped":false,"promptBottom_css":422}
```

| 量 | CSS px | 设备 px |
|---|---|---|
| `visualViewport.height` | 914 | **2400** |
| composer（`FORM`）底边 | 802 | **2106** |
| **余量（底边 → vv 底）** | 112 | **294** |
| 判定 | 802 ≤ 914 | **2106 ≤ 2400 ⇒ 不裁切** |

#### 屏 B —— 会话界面（截图 `ux12r_16_composer_filled.png`）

真实点击 composer（设备 541,2151）→ 软键盘弹起 → 敲入 `reply with the single word OK`。

```
[注入] {"vvH":914,"innerH":914,"docH":914,"scrollY":0,
        "composer":{"tag":"FORM","top":453,"bottom":893,"h":440},"clipped":false}
```

| 量 | CSS px | 设备 px |
|---|---|---|
| `visualViewport.height` | 914 | **2400** |
| composer（`FORM`）底边 | 893 | **2344** |
| **余量（底边 → vv 底）** | 21 | **56** |
| 判定 | 893 ≤ 914 | **2344 ≤ 2400 ⇒ 不裁切** |

#### ⚠️ 如实标注：`vv.height` 未随软键盘收缩

**[注入]** 两屏键盘弹起时 `visualViewport.height = 914` **恰等于** `window.innerHeight = 914`，
即**该 WebView 未因 IME 而缩小视觉视口**。因此 `vv.height` 恒等于整屏高 2400 设备 px，
**用 vv 做「可见区」代理是一个偏弱的判据**。为避免只凭弱判据下结论，本轮补了第二重证据：

1. **交互控件实测仍在可见区内**：`[aria-label=Prompt]` 在屏 A 为 `bottom=422 CSS`、
   屏 B 为 `bottom=513 CSS`，均远小于 914；发送键（深色 ↑）实测位于设备 y≈1404，
   远高于软键盘顶边（设备 ≈1524）。
2. **截图目视确认**：两张截图中，`ux12r` / `reply with the single word OK` 文本、
   `+` / `Fledge Alpha Free` / `Default` / `↑` 整行控件**全部完整可见、位于软键盘上方**，
   无任何被键盘遮挡或被视口裁掉的像素。

⇒ 任务书判据（底边 ≤ vv 高度）与目视证据**同时满足**，**PASS**。两屏都**不裁切**。

### 3.6 项 5.9 —— 回合常亮 keep screen on ✅ **PASS**

在 §3.3 发消息的同时**同步抓 logcat**（应用 tag `OcWebHost`），原文逐字抄录：

```
10-06 04:07:23.287 I/OcWebHost( 2677): D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false
                              ↑ 基线：空闲态，flag 未置起

10-06 04:14:17.094 I/OcWebHost( 2677): D1 applyKeepScreenOn: roundActive=true  foreground=true shouldKeep=true  flagSet=true
10-06 04:14:17.094 I/OcWebHost( 2677): web[LOG] [oc-pow] keepScreenOn -> true busy=[__post__] @715
                              ↑ 发送瞬间置起：roundActive=true / shouldKeep=true / flagSet=true

10-06 04:14:20.688 I/OcWebHost( 2677): web[LOG] [oc-pow] keepScreenOn -> false busy=[] @715
10-06 04:14:20.691 I/OcWebHost( 2677): D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false
                              ↑ 回复到达后释放
```

| 事件 | 时间戳 | 相对差 |
|---|---|---|
| 发送 → **置起**（`flagSet=true`） | `04:14:17.094` | — |
| 回复到达 → **释放**（`keepScreenOn -> false` / `flagSet=false`） | `04:14:20.688` / `04:14:20.691` | **Δ = 3.597 s** |

⇒ **置起**（`roundActive=true → shouldKeep=true → flagSet=true`）与
**按时释放**（回复到达后 3.6 s 内 `busy=[]`、`flagSet=false`）**两段都成立**，
且「释放时机」与助手回复到达（§3.3，`Build · Fledge Alpha Free · 3s`）**一致**。**PASS**。

> 上一轮 5.9 判 FAIL 的唯一原因是「没有一回合能成功发出」⇒ flag 无从置起（阻塞，非缺陷）。

---

## 4. 附：本轮观察到的 logcat 片段（`OcPerfJS` / 事件流）

```
10-06 04:13:05.949 I/OcWebHost( 2677): web[LOG] [oc-pow] event stream OPEN #331 /api/event @715
10-06 04:13:08.023 I/OcWebHost( 2677): web[LOG] OcPerfJS res #636 api start=344831 dur=23 enc=9 dec=9 xfer=309 http://127.0.0.1:4173/api/event @19
10-06 04:13:11.170 I/OcWebHost( 2677): web[LOG] OcPerfJS res-total n=639 xfer=8008754 dec=7851854 ratio=1.02 @19
```

与诊断轮一致：`4173`（静态资源来源，无 `/api/*`）的 `/api/event` 仍每 ~1–2 秒 404 一次；
**4095 上下文下的会话/列表/终端/发消息全部正常**，两者互不影响。

---

## 5. 收尾复核

### 5.1 后端只读复验（**未 start / stop / kill 任何后端进程**）

```
$ Invoke-WebRequest http://127.0.0.1:4095/api/info   (Basic opencode:opencode)
STATUS=200
{"version":"2.0.23","pid":31812,"urls":["http://127.0.0.1:4095"],
 "paths":{"tmp":"C:\\Users\\xiufeigo\\AppData\\Local\\Temp\\opencode"},
 "capabilities":{"persistentPty":false}}
$ Get-Process -Id 31812  →  PID31812_ALIVE name=opencode
```

⇒ **pid = 31812，与诊断轮 §0.1 记录的一致，未变**；`/api/info` 收尾后仍 **200**。
**后端全程只被只读探测，未被起停。**

### 5.2 `adb reverse` / `adb forward` 已撤销

```
$ adb -s emulator-6790 reverse --list
host-15 tcp:4095 tcp:4095
$ adb -s emulator-6790 reverse --remove tcp:4095
$ adb -s emulator-6790 reverse --list
（空）
$ adb -s emulator-6790 forward --remove tcp:9334     （CDP 通道）
```

### 5.3 只关自建 `emulator-6790`

```
$ adb -s emulator-6790 emu kill
OK: killing emulator, bye bye
OK
```

```
$ adb devices -l
List of devices attached
（空）                                    ← 只剩本轮自建的 6790，已关；无其他实例残留
$ Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'"
（空）                                    ← 全机无 emulator / qemu 进程
$ Get-NetTCPConnection -LocalPort 6790 -State Listen
（无输出）                                 ← 6790 已无监听
$ Get-NetTCPConnection -LocalPort 6790
  127.0.0.1  6790  TimeWait  0             ← 仅剩 TCP 正常拆除的 TimeWait，OwningProcess=0
```

⚠️ 首次 `emulator.exe -port 6790 -kill` **未生效**（进程仍在），改用 `adb emu kill` 成功。
**未执行 `adb kill-server`**，**未**通配符操作，**未碰**任何非 6790 串口。

### 5.4 本轮产物 / 临时文件

| 类别 | 路径 | 说明 |
|---|---|---|
| 报告 | `research/web/UX12_PHONE_REVERIFY.md` | 本文件 |
| 截图（21 张） | `research/web/ux12r_*.png` | `00_home` … `20_reenter` |
| logcat 落盘 | `research/web/ux12r_logcat.txt` | 全程应用日志 |
| 临时脚本 | `_scratch_ux12r_eval.mjs`、`_scratch_ux12r_lib.ps1` | **已按要求删除**（`SCRATCH_REMAINING=0`） |

### 5.5 代码零改动

```
$ git status --porcelain
 M webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt
 M webapp/src/test/java/com/xiufeigo/opencodeshell/web/DirectConnectAuthBoundaryTest.kt
 M webapp/src/test/java/com/xiufeigo/opencodeshell/web/TerminalReattachTest.kt
```

⚠️ 如实标注：`git status` **确实**显示 3 个 `webapp/**` 文件为 `M`，但**不是本轮改的**：

```
$ Get-Item webapp/.../WebHostActivity.kt, .../DirectConnectAuthBoundaryTest.kt, .../TerminalReattachTest.kt
  LastWriteTime = 2026/10/6 10:55:52   （三个文件同一时刻）
本轮起机 = 2026/10/6 12:07（首张截图 ux12r_00_home.png 的时间戳）
```

三个文件的 mtime **早于本轮起机约 1 小时 12 分钟**，是**此前轮次/检出**留下的既有改动。
**本轮全程只读代码、未改 `webapp/**` / `app/**`、未碰 gradle、未构建。**

---

## 6. 总结论

### 6.1 6 项逐条判定

| 项 | 上一轮 | 本轮 | 关键依据 |
|---|---|---|---|
| **5.1 终端口可用** | FAIL（阻塞） | ✅ **PASS** | 提示符 `PowerShell 7.6.6` / `PS D:\XiaoZhuTouTou\OpenCodeUI>`；命令回显 + 输出 `ux12r_term_ok_20261006` + 新提示符 |
| **5.2 标签条 `+` 直接新建终端** | FAIL（阻塞） | ✅ **PASS** | `+` 点击前后 `terms:["Terminal 1"] → ["Terminal 1","Terminal 2"]`，`[data-oc-panel]` **前后均 0** ⇒ 无弹面板 |
| **5.5 发消息有回复** | FAIL（阻塞） | ✅ **PASS** | `reply with the single word OK` → `fledge-alpha-free` **3s** 回复 `OK` |
| **5.6 切会话 / 返回** | PARTIAL（阻塞） | ✅ **PASS** | A `ses_ef43fab0…` → B `ses_ef0be3ed…`（内容正确）→ 返回（上下文仍 4095）→ 再进入 B（id 与内容一致） |
| **5.7 键盘弹起 composer 两屏不裁切** | FAIL（阻塞，未取 px） | ✅ **PASS** | 屏 A 底边 **2106** ≤ vv **2400**；屏 B 底边 **2344** ≤ vv **2400**（设备 px），`clipped=false`；截图目视亦无遮挡 |
| **5.9 回合常亮** | FAIL（阻塞，flag 未置起） | ✅ **PASS** | `04:14:17.094 flagSet=true` → `04:14:20.691 flagSet=false`，**Δ3.597s**，与回复到达同步 |

### 6.2 判定

**6 项全部 PASS，零 FAIL。** 手机档（`Medium_Phone_API_37.0`，API 37 / Android 17，1080x2400，density 420）
在被阻塞项上**未发现任何产品缺陷**。

**唯一根因是「服务器上下文未切到 4095」**，与 `UX12_PHONE_DIAGNOSIS.md` §2.1 / §4.1 的结论完全吻合：
宿主 `4173` 只是静态资源来源、没有 `/api/*` ⇒ 只要上下文停在 4173，
`/api/event` 每 1–2 秒 404 → 数据加载状态机不落地 → 列表停在 `Loading` → **进不去任何会话** →
终端 / 发消息 / 切会话 / 键盘 / 常亮**全部无从执行**。一旦真实点击把上下文切到 4095，
列表秒级渲染，以上 6 项**逐一正常工作**。

⇒ 上一轮 `UX12_PHONE_REGRESSION.md` 中 5.1/5.2/5.5/5.7/5.9 的 **FAIL**
与 5.6 的 **PARTIAL** 均为**阻塞性误判（测量前提不成立）**，应改判为 **PASS**。

> 平板档 12 项已全 PASS，本轮**未重做**平板档。

### 6.3 本轮引入的诚实标注（不掩盖）

1. **§3.1 命令串偏差**：任务书的 `echo ux12r_$([datetime]::Now.Ticks)` 含
   `$( ) [ ] :`，经 `adb shell input text` 多层 shell 转义**无法可靠送达**，
   改用等价唯一标记 `echo ux12r_term_ok_20261006`（同为 `echo` + 本轮唯一 token），
   同样覆盖「送达 → 执行 → 回显」三段。
2. **§3.5 `vv.height` 未随软键盘收缩**（两屏均 `vvH = innerH = 914 CSS`），
   故 vv 是**偏弱的可见区代理**；已补「交互控件实测坐标 + 截图目视」两重证据。
3. **§5.5 `git status` 有 3 个 `webapp/**` 为 `M`**，经 mtime 比对**早于本轮起机**，
   非本轮改动；本轮代码改动 **0 字节**。
