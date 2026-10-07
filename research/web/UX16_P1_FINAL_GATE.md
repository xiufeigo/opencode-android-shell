# UX16-P1 批 #9 收尾门 — 流式帧时 / 渲染崩溃自愈 / 核心回归冒烟

- 日期：2026-10-07
- 仓库：`D:\XiaoZhuTouTou\opencode-android-shell`
- 批次：#9（UX12–UX15：幻影条件化隐藏 / 通知四段 / 相机 / render 自愈 / 选项C）
- 原则：**只验证，不改代码**。全程未改任何源码 / gradle / 未构建。
- 注入标注：本轮仅用 **只读** 探针（`oc_debug_eval` 读 DOM、`Runtime.evaluate` 读状态）。
  **未注入任何写操作、未点击任何注入按钮**；诊断页按钮按要求「只记录、不强求」。

---

## 结论摘要（一句话）

**遗留项 3（渲染崩溃自愈）真触发并两次独立复现：接线 PASS、节流 PASS，
但「崩溃 → 自动重载 → 恢复可用」这条端到端路径在本镜像上实际 FAIL（reload 后渲染进程再崩，
落诊断页）—— 节流的成功掩盖了自愈的失败，不要按「已验证通过」收口。**
**遗留项 2（流式帧时）因「Add-server UI 不可达 + 指定测量法在本镜像取不到帧」双重阻塞，未取到数。**
8 项冒烟 **1 项 PASS（主题）、1 项 PARTIAL（首启语义）、6 项 BLOCKED**。

---

## 0. 环境基线 ✅

| 项 | 值 |
|---|---|
| APK | `research/deliver/opencode-shell-web.apk` |
| Size | **16,830,822** bytes —— 与预期一致 ✅ |
| MD5 | **`90E6A6D7B1049D91C6A53D5772EFE9A7`** —— 预期 `90E6A6D7…` ✅ |
| SHA256 | `B1900F6BF7D78A466729019D10D9E6DA24B2A6E43F7FA668E862BE0BDD3B68CE` |
| 包名 | `com.xiufeigo.opencodeshell.web.debug` |
| 后端 | `127.0.0.1:4095`（opencode/opencode, v2.0.24, **pid 21136**）— 全程未启停 ✅ |
| 自建设备 | `emulator-6790`（AVD `Medium_Phone_API_37.0`, API 37, 1080×2400）— **我起我关 ✅ 已关** |
| 他人设备 | `emulator-5554`（Pixel_Tablet）— **全程零写操作 ✅** |
| adb | `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` |

后端存活确认（收尾复测）：`pid21136 ALIVE name=opencode start=10/07/2026 08:16:25`，
`GET / → HTTP 200`，`GET /app → HTTP 200`。

### 踩到的第一个坑：组件名 ≠ applicationId

`am start -n com.xiufeigo.opencodeshell.web.debug/.WebHostActivity` → `Error type 3 / Activity class does not exist`。
真实组件在另一个命名空间：

```
com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity
```

---

## 步骤 3 — 渲染进程崩溃自愈（真触发）✅ **本轮唯一完整闭环的遗留项**

### 3.1 触发方式：先按要求 `kill -9`，被拒 → 改用协议级真崩溃

按要求先找渲染进程并 kill，**被系统拒绝**（如实抄录）：

```
$ adb -s emulator-6790 shell "ps -A -o PID,NAME | grep -Ei 'webview|sandbox|opencodeshell'"
  1048 webview_zygote
  3697 com.xiufeigo.opencodeshell.web.debug
  3727 com.google.android.webview:sandboxed_process0:org.chromium.content.app.SandboxedProcessService0:0
  3814 com.google.android.webview:webview_service

$ adb -s emulator-6790 shell kill -9 3727
/system/bin/sh: kill: 3727: Operation not permitted

$ adb -s emulator-6790 root
adbd cannot run as root in production builds
```

渲染进程**确实独立存在**（并未并入主进程），所以本项**可触发**、不是「该镜像不可触发」。
本 AVD 是 **production 镜像（无 root）**，故 `kill -9` 这条路走不通；改用
**CDP `Page.crash`** —— 它是协议级的渲染进程真崩溃，与被 kill 等价：

| 项 | 值 |
|---|---|
| 主进程 pid | `3697` |
| 渲染进程 pid | **`3727`** `…SandboxedProcessService0:0` |
| CDP socket | `@webview_devtools_remote_3697`（`adb forward tcp:9333` 成功） |
| WebView | Chrome/145.0.7632.218，V8 14.5.201.18 |
| 崩溃前页面 | `http://127.0.0.1:4173/`（官方 Home，前台正常态） |

触发回执：

```
crashResp={"method":"Inspector.detached","params":{"reason":"Render process gone."}}
```

### 3.2 logcat 原文（全部照抄，未加工）

```
10-07 00:23:35.767  3697  3697 W OcWebHost: UX14 render process gone didCrash=true priorityAtExit=2
10-07 00:23:35.767  3697  3697 I OcWebHost: UX14 render crash; reloading once (window=60000ms)
10-07 00:23:42.321  3697  3697 W OcWebHost: UX14 render process gone didCrash=true priorityAtExit=2
10-07 00:23:42.321  3697  3697 W OcWebHost: UX14 render crash loop count=2 within=60000ms
10-07 00:23:42.322  3697  3697 W OcWebHost: G1 showing diagnostic page reason=rendercrash detail=didCrash=true crashes=2 windowMs=60000
10-07 00:23:42.551  3697  3697 I OcWebHost: web[LOG] OcPerfJS doc-start href=https://appassets.androidplatform.net/assets//www/index.html?r=rendercrash&m=145&v=145.0.7632.218&min=105&t=12000&d=didCrash%3Dtrue%20crashes%3D2%20windowMs%3D60000 navStart=224
```

证据文件：`research/tmp/ux16p1/ux14-crash-evidence.txt`（931 行）、截图 `08-crash1.png`。

### 3.3 判读

- ✅ **自愈 PASS**：`onRenderProcessGone` 被宿主消费（`return true` ⇒ WebView 不再默认 finish 掉 Activity），
  走 `webView.reload()` 分支，日志 `UX14 render crash; reloading once (window=60000ms)`。
- ✅ **节流 PASS**：60 s 窗口内第 2 次崩溃 → `render crash loop count=2 within=60000ms` →
  `showDiagnostic(RENDER_CRASH)`，诊断页 URL 带 `?r=rendercrash&m=145&v=145.0.7632.218&min=105&t=12000&d=…crashes%3D2…`。
  **没有**无限 reload / 重启风暴 —— 这正是 `WebHostActivity.kt:145-152` 设计要防的那件事，实测成立。

### 3.4 ⚠️ 两个必须记录的偏差 / 缺陷

**① 「reload 后恢复到可用」这条没有干净走完 —— 且已用第二次独立复现排除掉「是我 CDP 造成的」**

第 2 次崩溃**不是本轮手动发的**，而是 `reload()` 之后 **6.5 s 自行又崩**
（`00:23:35.767` → `00:23:42.321`，中间页面根本没起来）。

为排除「是我的 CDP websocket 一直挂着才把渲染进程拖死的」这个替代解释，
**做了第 2 次独立复现**：冷启 App（pid 2979 / 渲染进程 3186）→ 发**恰好一次** `Page.crash`
→ **立刻 dispose 掉 websocket**（此后不再连 CDP）→ 静置观察 20 s。**行为逐字复现**：

```
crash issued @ 08:34:01
10-07 00:34:00.802  2979  2979 W OcWebHost: UX14 render process gone didCrash=true priorityAtExit=2
10-07 00:34:00.803  2979  2979 I OcWebHost: UX14 render crash; reloading once (window=60000ms)
10-07 00:34:07.361  2979  2979 W OcWebHost: UX14 render process gone didCrash=true priorityAtExit=2
10-07 00:34:07.361  2979  2979 I OcWebHost: UX14 render crash loop count=2 within=60000ms
10-07 00:34:07.369  2979  2979 W OcWebHost: G1 showing diagnostic page reason=rendercrash detail=didCrash=true crashes=2 windowMs=60000
```

（两次的间隔分别是 **6.55 s** 与 **6.56 s**；截图 `18-crash1-t20.png` 与 `08-crash1.png` 画面一致。）

⇒ **这是 reload 自身的性质，不是注入造成的**：在本镜像上，`webView.reload()` 之后渲染进程会再次崩溃，
随后被节流挡下。也就是说：

- ✅ 「崩溃被消费」✅「重载被触发」✅「节流落诊断页」—— 三个分支全部真实触发并有 logcat 佐证；
- ❌ **但「崩溃 → 自动重载 → 页面恢复到可用」这条端到端自愈路径，在本镜像上实际是失败的**，
  最终落点是诊断页，不是可用页面。

这与 `WebHostActivity.kt:1141-1145` 自陈的已知边界完全吻合 ——
平台口径是「渲染进程消失后该 WebView 实例不应再被复用」，而本实现刻意只做**低成本 reload 自愈**。
**节流机制救场成功（没进重启风暴），但它掩盖了「reload 自愈其实没生效」这一事实。**
⇒ 给 UX14 的准确评价是：**接线正确、节流有效，但「自动恢复可用」这一产品承诺在本镜像上未达成。**

**② 诊断页中文全部渲染成 `?`（可读性缺陷，两次复现一致）**

截图 `08-crash1.png` 肉眼可见：标题「无法连接 OpenCode 服务」、正文「本页…回环代理起不来时的兜底页」、
以及 ORIGIN / WEBVIEWASSETLOADER / USER AGENT / HOSTINFO() / INSETS / 主题 各行后面的中文，
**无一例外全是 `?`**（还有 `?b>`、`?/DIV>` 这类残片）。该兜底资产的字符集 / `<meta charset>` 声明有问题。
**不影响**自愈判定（诊断页确实加载、确实带对参数），但**兜底页的可读性是坏的** —— 崩溃时用户看到的是一片 `?`。

（诊断页内可见 3 个按钮：重试 / 注册 back handler / 申请权限并发一条通知 —— 按要求**只记录、不强求**其可用性。）

---

## 步骤 2 — 流式帧时 ❌ **未取到数（双重阻塞）**

### 2.1 阻塞一：指定的测量法在本镜像取不到帧

任务书指定 `adb shell dumpsys gfxinfo com.xiufeigo.opencodeshell.web.debug framestats`。实际执行（已先 `reset` 清零，
并做了 12 次真实滑动制造 DOM churn，再采帧）：

```
Total frames rendered: 0
Janky frames: 0 (0.00%)
50th percentile: 4950ms
90th percentile: 4950ms
HISTOGRAM: 5ms=0 6ms=0 … 4950ms=0     ← 全 0
```

**`Total frames rendered: 0`。** 原因清楚：WebView 内容由 **sandboxed 渲染进程（pid 3727）** 产出，
不进宿主 App 的 HWUI 帧流水线，所以宿主包名下的 gfxinfo 恒为 0 帧。
这不是「帧很好」，是**测不到**。

改用 SurfaceFlinger 抓该 Activity 真实合成层，同样空：

```
$ dumpsys SurfaceFlinger --latency "…WebHostActivity#135"
16666666            ← 只回刷新周期（60 Hz），一行帧记录都没有
```

⇒ **两条路都拿不到 WebView 的帧时。本轮无法给出中位/90 分位/>32ms 占比。**
（证据：`research/tmp/ux16p1/gfxinfo-framestats.txt`、`sf-latency.txt`）

### 2.2 阻塞二：无法进入会话 ⇒ 无法产生流式渲染

帧时要测的必须是「流式渲染期间」。本轮进不去会话（见步骤 1 的前置阻塞），
所以即便测量法可用，也没有可测的流式负载。**两件事都堵着。**

### 2.3 判读

**步骤 2 = 未验证（NOT RUN）。不是 PASS，也不是 FAIL —— 是「取不到数」。**
⇒ 幻影 XPath 化之后的性能实证**本轮没有拿到**。留给下轮的前提：
① 打通 Add-server → 进会话；② 换用能看见渲染进程帧的量法
（例如对 `com.google.android.webview` 侧采集、或用 CDP `Performance`/`Tracing` 域抓真实帧）。

---

## 步骤 0 / 1 — 起机与核心回归冒烟

### 步骤 0 ✅（部分）

- 自建 AVD 起在 6790，API 37，`adb reverse tcp:4095 tcp:4095`（`host-15`）。
- `pm clear` + 冷启 ⇒ 官方 Home 渲染，页面 origin = `http://127.0.0.1:4173/`。
- eval 探针通道确认可用：logcat tag 是 **`OcWebHost/Eval`**（不是 `UXEVAL`），需 `--es oc_debug_eval "b64:<base64>"`。

### 前置阻塞：**Add-server UI 在本构建不可达**（把 7 项冒烟一起堵死的唯一原因）

后端全程是好的（`/ → 200`、`/app → 200`），`adb reverse` 也建好了，但**没法把 App 指向它**。逐条排查记录：

| 找法 | 结果 |
|---|---|
| Settings 页全文 | `document.body.innerText` 仅 **1047 字符**，只有 Preferences / General / Advanced / Timeline —— **`/server/i` 零命中** |
| Home chevron | 展开是「All projects / Add project」—— **项目选择器，不是服务器切换器** |
| Home 头部 `0` 徽标 | 点开是底部抽屉 Tabs/New session/Home/Settings/Help，`0` 只是会话数 |
| `localStorage` | 仅 3 键（notification / updater / command.catalog），**无服务器配置**（服务器存宿主 `EncryptedSharedPreferences`） |
| 候选路由 `/servers` | `Something went wrong — An error occurred while loading the application. Version: 2.0.22` |
| 候选路由 `/settings/servers`、`/settings/servers/` | 空白页（`len=0`），**路由不存在** |
| 「New session」 | 点下去毫无反应，URL 仍停在 `/`，`textarea` 数 = 0 |

源码侧佐证：服务器管理在 `frontend/patches/android-webview-z-server-select.patch`
（`components/dialog-select-server.tsx` + `settings-v2/servers.tsx`），
但 **`/settings` 实际渲染的那套 UI 里根本没有 Servers 段** ——
而 `HANDOFF_WEB_SHELL.md` 早就自陈过同类结论：「唯一会渲染扫码入口的 connect screen 是 *fallback* ⇒ 真机 UI 上到不了」。
本轮在 `Medium_Phone_API_37.0` 上**复现了这条 fallback 不可达**。

Home 全程卡在 `Loading`（4 个可点元素：`mobile-tabs-new-session` / `-home` / `-settings` / `-help`），
**没有出现任何服务器行**。

### 1. 首启语义 — ⚠️ PARTIAL（与任务书预期不符，如实记录）

- ✅ `pm clear` 冷启后页面确实渲染在 `4173`，Home 可见（截图 `01-fresh-launch.png`）。
- ❌ **「4173 红点行可见（设置导航 + Home）」未观测到**：Home 上始终没有任何服务器行，更没有红点；
  「All projects」展开也只有项目项。
- ❌ 后半段「Add 4095 成功后 4173 行消失、只剩 4095」**无法执行**（Add UI 不可达）。

⇒ 判 **PARTIAL / 未闭环**。不能因为「Home 渲染出来了」就算这条 PASS。

### 2. 终端可用 — ⛔ BLOCKED（进不去会话，无终端标签）

### 3. 发消息有回复 — ⛔ BLOCKED（同上）

### 4. 回合常亮 flagSet — ⛔ BLOCKED（无回合可跑）

### 5. 后台断 SSE / 回前台重建 — ⛔ BLOCKED（同上）

### 6. 后台回合通知 + 真实点击回会话 — ⛔ BLOCKED（同上）

### 7. 主题 — ✅ **PASS**

系统夜览模式切换，`documentElement.dataset.colorScheme` 实测跟随（唯一不依赖后端的冒烟项）：

```
$ adb shell cmd uimode night yes
DARK  colorScheme=dark      ← 截图 13-theme-dark.png
$ adb shell cmd uimode night no
LIGHT colorScheme=light     ← 截图 14-theme-light.png
```

### 8. 键盘不裁切 — ⛔ BLOCKED（进不去会话）

---

## 步骤 4 — 收尾 ✅

```
am force-stop com.xiufeigo.opencodeshell.web.debug      ← 应用已停
adb forward --remove tcp:9333                            ← forward 列表已空
adb reverse --remove tcp:4095                            ← reverse 列表已空
adb -s emulator-6790 emu kill
  OK: killing emulator, bye bye

$ adb devices -l
emulator-5554   device product:sdk_gtablet_x86_64 model=Pixel_Tablet transport_id:17   ← 只剩他人设备
```

- ✅ **自建 `emulator-6790` 已关**（谁起谁关）
- ✅ `reverse` / `forward` 已清空
- ✅ **未 kill adb server**
- ✅ 后端 **pid 21136 未受影响**，`/` 与 `/app` 均 **200**
- ✅ **`emulator-5554`（他人设备）全程零写操作**，未安装任何东西

---

## 遗留项状态汇总

| 遗留项 | 状态 | 依据 |
|---|---|---|
| UX14 渲染崩溃自愈 | 🟡 **接线 PASS + 节流 PASS；「自动恢复可用」FAIL**（两次独立复现，CDP 已排除） | logcat 原文 ×2 + 诊断页 URL ×2 + 截图 ×2 |
| 流式帧时 | ❌ **未取到数**（gfxinfo 恒 0 帧 + SF latency 空 + 进不去会话） | `gfxinfo-framestats.txt` / `sf-latency.txt` |
| 诊断页编码 | 🐛 **新发现缺陷**：中文全渲染成 `?`（两次复现一致） | 截图 `08-crash1.png` / `18-crash1-t20.png` |

### 对 UX14 的最终判词（务必转达）

**不要按「自愈已验证通过」收口。** 精确说法是：

1. `onRenderProcessGone` **接线正确** —— 事件被消费（`return true`），WebView 不再默认 finish 掉 Activity；
2. **节流有效** —— 60 s 窗口内第 2 次崩溃落诊断页，**没有**重启风暴（这是该设计最想防的事，成立）；
3. **但「崩溃 → 自动重载 → 页面恢复可用」这条产品承诺在本镜像上不成立** ——
   `reload()` 后渲染进程会再次崩溃（间隔稳定在 **6.5~6.6 s**），最终落点是**诊断页**而不是可用页面。

**节流的成功掩盖了自愈的失败**：因为第二次崩溃总会被节流接住，从日志表面看「一切正常（进了诊断页）」，
但用户实际拿到的是崩溃兜底页而非自动恢复的会话。
这与 `WebHostActivity.kt:1141-1145` 自陈的已知边界一致（平台口径要求新建 WebView 实例而非复用）。
⇒ **建议下轮要么按平台口径新建实例，要么把「reload 自愈」的产品口径改成「崩溃 → 诊断页」，别让节流兜底冒充自愈。**

## 建议下轮前置（本轮卡死的根因）

1. **先解决 Add-server 不可达**（唯一卡点，连带 7 项冒烟 + 帧时）。当前 `/settings` 渲染的 UI 没有 Servers 段，
   `/servers` 路由 404、connect screen 是 fallback 到不了 —— 需确认 batch #9 的服务器切换入口在哪个版本落地，
   或给出可达路径（深链 / 宿主侧预置）。
2. **换帧时量法**：`dumpsys gfxinfo <宿主包>` 对 WebView 恒 0 帧，不可用于本项目。
   建议改用 CDP `Performance`/`Tracing` 域或对渲染进程侧采集。
3. **顺带修诊断页编码**（中文全 `?`）—— 崩溃时用户看到的是一片问号。

---

## 纪律自查

| 项 | 状态 |
|---|---|
| 只验证、不改代码 | ✅ 未改任何源码 / gradle，未构建 |
| 谁起谁关 | ✅ `emulator-6790` 自建自关 |
| 不 kill-server | ✅ 未执行 `adb kill-server` |
| 后端不许启停 | ✅ pid 21136 未动 |
| 不碰他人设备 | ✅ `emulator-5554` 零写操作 |
| 注入只读 + 标注 | ✅ 仅 `Runtime.evaluate` 读 DOM / `oc_debug_eval` 读 DOM；未点任何注入按钮 |
| 不编造 | ✅ 取不到数就写「取不到数」；偏差/缺陷全部照抄记录 |

---

## 产物清单（`research/tmp/ux16p1/`）

| 文件 | 内容 |
|---|---|
| `h.ps1` / `cdp.ps1` | 本轮辅助脚本（只读探针 / CDP 客户端） |
| `01-fresh-launch.png` | pm clear 冷启首屏 |
| `08-crash1.png` | **诊断页**（可见中文全变 `?`） |
| `13-theme-dark.png` / `14-theme-light.png` | 主题 dark / light |
| `ux14-crash-evidence.txt` | UX14 崩溃 logcat 全文（931 行） |
| `gfxinfo-framestats.txt` | 帧时采集结果（0 帧，取证用） |
| `sf-latency.txt` | SurfaceFlinger latency 结果（无帧行，取证用） |
| `00-launch.png` ~ `15-frames.png` | 各步截图 |