# UX17-P4 — 批 #13 安全基线复验（无幻影隐藏：重点验证「加服务器不自锁」+ 快速冒烟 + T6）

**轮次**：UX17-P4 · **日期**：2026-10-07 · **性质**：只验证，不改代码
**被验包**：`research/deliver/opencode-shell-web.apk` = **16,827,044 B** / md5 **`097DA1731F4F4D4FA0A59C78F25D1554`**（装前核对，与任务书一致）
**被验基线**：批 #13 = **「幻影 4173 隐藏」整体下线**（v2 会连 Home 服务器切换器一起藏掉造成自锁；v3 hidden-map 上游不消费）
  + 通知/后台/崩溃自愈修复

---

## 0. 环境与 serial

| 项 | 值 |
|---|---|
| 实际使用设备 | **`emulator-5680`** = `Medium_Phone_API_37.0`（1080×2400 @420dpi，SDK 37）— **非 6790，见 §0.1** |
| 禁写设备 | `emulator-5554`（Pixel_Tablet）— **本轮零写操作**（仅 `adb devices` 读取） |
| 后端 | `127.0.0.1:4095`，pid **21136**，全程未起停（收尾复验 200） |
| reverse | `adb -s emulator-5680 reverse tcp:4095 tcp:4095`（收尾已 `--remove-all`，复验空） |
| 包名 | `com.xiufeigo.opencodeshell.web.debug`，activity `com.xiufeigo.opencodeshell.web.WebHostActivity` |
| 注入通道 | **CDP over WebView devtools**：`adb forward tcp:9222 localabstract:webview_devtools_remote_8779` → `Runtime.evaluate`（收尾已 remove） |

### 0.1 ⚠️ 端口偏差说明（环境事实，非缺陷）

任务书指派「AVD `Medium_Phone_API_37.0`，端口 **6790**，你自建自关」。
实测：本会话开始前（10:24:03，早于本会话 10:31），**该 AVD 已以 `emulator-5680` 在跑**；我按 `-port 6790` 自建的实例因 **AVD 锁冲突**启动即退出（进程未存活、6790 从未监听）。

处置：改用**已在运行的同一个 AVD** `emulator-5680`（`adb emu avd name` = `Medium_Phone_API_37.0` 实锤，机型/分辨率/SDK 与剧本坐标系完全吻合：剧本 `Settings (179,2324)` 命中）。因为它**不是本轮自建**，收尾**未 kill**，保持原状交还。

### 0.2 剧本坐标偏差（真实 UI 修正）

| 剧本坐标 | 实测 | 说明 |
|---|---|---|
| 抽屉 `(67,120)` | `(532,121)` | `(67,120)` 落在页头**最左图标**上，会**跳到另一个 App `top.d1studio.dshremote`**（DSH Remote 深链）。真实「Tabs」抽屉按钮是**整条通栏** `x=9..396, y=32..60 CSS`（设备 `(532,121)`） |
| `Settings (179,2324)` | `(179,2324)` ✅ | 抽屉底部 sheet 内命中 |
| 段选择器 `(868,216)` 选 `Server` | `(882,215)` → 下拉 `Server` `(768,988)` | 是 `Preferences ⌄` 下拉；`Server` 为下拉项 |
| `+ Add server (906,482)` | `(879,484)` | 命中 |
| 提交 `(907,1652)` | `(919,1653)` | 命中（需先 `keyevent 4` 收 IME，否则软键盘遮挡） |

---

## 1. 步骤 0：install -r → pm clear → am start → 5s — ✅ PASS

`install -r` Success；`pm clear` Success；`am start` pid **8779**，焦点 `com.xiufeigo.opencodeshell.web.debug/.WebHostActivity`。
首屏一度全黑，4s 后正常渲染（WebView 冷启 131+ API 资源）。首启 Home = `Home | 0 | Loading`，**此时无任何服务器，故 Home 无切换器条目属正常**。

---

## 2. 核心判死项：Add 4095 后**不自锁** — ✅ **PASS（批 #12 致命缺陷已消除）**

### 2.1 Add 成功 — ✅

| 判定 | 结果 | 证据 |
|---|---|---|
| 表单填写正确 | ✅ | `input[placeholder=http://localhost:4096].value` = `http://127.0.0.1:4095`；`input[type=password].value` = `opencode` |
| ≥14s 等待后提交 | ✅ | 等待 15s、收 IME、点击 `(919,1653)` |
| 后端打通 | ✅ | logcat：`OcPerfJS res #445 … http://127.0.0.1:4095/api/info` |

### 2.2 🔑 关键判定：**切换器可见且可点（不自锁）** — ✅

Add 完成后**不点任何切换**，先回 Home 截图，再查 DOM。

**注意**：Home 页头**没有**独立的「服务器切换器」按钮。真实结构是：

```html
<button aria-haspopup="dialog" aria-expanded="false" data-component="home-projects-dropdown"
        aria-label="Projects" class="flex h-10 w-full …">
  <svg …folder…>
  <span><bdi>All projects</bdi><bdi class="…opacity-70">127.0.0.1:4173</bdi></span>
</button>
```

该按钮即**上下文（服务器）切换入口**，真实点击后弹出服务器列表：

```
┌ All projects  127.0.0.1:4173            ⌄
├ 🔴 127.0.0.1:4173   ⋯  📁+     ← 当前，红点=未连接
├ 🟢 127.0.0.1:4095   ⋯  📁+     ← 新增，绿点=已连通
```

| 判据（任务书二选一） | 结果 | 证据 |
|---|---|---|
| 注入读 `display`/尺寸 ≠ `none`/0 | ✅ | `display=flex`、`visibility=visible`、`opacity=1`、`offsetParent≠null`、设备尺寸 **1017×74**（`x1=9` 不在 0 位），**无 `data-oc-phantom` 属性** |
| 真实点击弹出下拉 | ✅ | 真实 `input tap 540 263` → `aria-expanded` 由 `false`→`true`，两行服务器同时可见（证据 `batch13_shots/01-server-switcher-visible.png`） |
| 与批 #12 对比 | ✅ **已修** | 批 #12 该按钮被注入样式 `[data-oc-phantom="1"]{display:none!important}` 置为 `display:none` / `0×0`；本轮全文档扫描 `[data-oc-phantom]` **count = 0** |

> 附带：设置页段选择器下拉里也**同时列出 `127.0.0.1:4095` 与 `127.0.0.1:4173` 两个可切条目**（证据 `batch13_shots/05-settings-menu-both-servers.png`），第二条切换路径同样畅通。

### 2.3 切到 4095 ⇒ 列表渲染 — ✅

真实点击 `127.0.0.1:4095` 行（`y=467`）后：

| 判定 | 结果 | 证据 |
|---|---|---|
| 当前服务器切换 | ✅ | 页头副标题由 `127.0.0.1:4173` 变为 `127.0.0.1:4095` |
| 路由带 base64 服务器 | ✅ | `/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/…`（= `http://127.0.0.1:4095`） |
| **6 会话渲染** | ✅ | Today `v2 smoke test` / Yesterday `Greeting`、`Greeting`、`New session` / Older `Single word reply request`、`New session` = **6** |
| Loading 消失 | ✅ | 首启那个永久 `Loading` 未复现 |

**⇒ 「加服务器不自锁」判死项通过。**

---

## 3. 快速冒烟（4 项）— **4/4 PASS**

| # | 项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 终端 echo 回显 | ✅ **PASS** | Terminal 标签页起**真 PowerShell 7.6.6**（`PS D:\XiaoZhuTouTou\OpenCodeUI>`）；`echo` 被回显并给出 `InputObject:echo` → `echo`；`Write-Output UX17P4_TERM_OK` 正常执行。命令↔回显链路通 |
| 2 | 发消息得回复 | ✅ **PASS** | 会话 `Single word reply request` 内发 `say+hi` ⇒ 20s 内时间线出现 **`hi`**，署名 `Build · Exo Free · 4s` |
| 3 | 空闲后台断 SSE / 回前台重建 | ✅ **PASS** | HOME：`OcWebHost: P6 dispatch synthetic pagehide (upstream pageLifecycle)` + `[oc-pow] event stream CLOSE #220 http://127.0.0.1:4095 cancel` + `VRI[WebHostActivity] visibilityChanged true→false`；回前台：`P6 dispatch synthetic pageshow` + 4095 流量恢复（`api/skill`、`api/form`、`api/worktree/refresh`、`api/info`），路由与服务器未丢失 |
| 4 | 主题 dark 一次 | ✅ **PASS** | 设置 → Appearance → Color scheme：`System` → 真实点击 `Dark` ⇒ `documentElement.dataset.colorScheme = **"dark"`**、`dataset.theme="oc-2"`、`style.backgroundColor=rgb(8,8,8)`、`localStorage["opencode-color-scheme"]="dark"` |

---

## 4. T6 长回合 — **2/4 FAIL**（长回合本身健康；通知/P7 通道缺失）

前置：`POST_NOTIFICATIONS` 已 `pm grant`（`dumpsys package` → `granted=true`）。**未出现权限弹窗**（因已 grant）。

时序：填入**精确**原文 `Count from 1 to 500 comma separated`（已注入回读校验）→ 点 Send → **间隔 76 ms** 按 HOME（远小于 1.5s 要求）。

| 格 | 判据 | 结果 | 证据 |
|---|---|---|---|
| ① | `P7 onPause: round active` 日志 | ❌ **FAIL** | 全量 logcat 中 `OcWebHost` 的生命周期标记**只有 `P6`**（`pagehide` / `pageshow`）——**本构建不存在 `P7` 标记**。相关的是 `OcWebHost: D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false`，且**未出现 `roundActive=true` 的后台态**。`round POST /api/session/…/prompt` 正常发出 |
| ② | `dumpsys notification` 有本包通知 | ❌ **FAIL** | 6s / 16s / 30s 三次轮询，本包 `NotificationRecord` 计数**恒为 0**。仅有 `AppSettings: com.xiufeigo.opencodeshell.web.debug (10235) importance=DEFAULT userSet=false`（注册行），**无实际通知实例** |
| ③ | 下拉截图 | ✅ **PASS（截图已取，证实无本包通知）** | 证据 `batch13_shots/06-shade-no-app-notification.png`：下拉仅 4 条系统项（Security & privacy、Silent、Physical keyboards、Virtual SD card），**无 OpenCode 通知**；`dumpsys notification` 全局仅 3 条（android×2 + systemui×1） |
| ④ | 真点回会话 | ✅ **PASS** | 收 shade → `keyevent 187` 打开最近任务 → 真实 `input tap 540 900` 命中 OpenCode 卡片 ⇒ 焦点回到 `WebHostActivity`，路由仍为 `/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_ef4e669…`，时间线两条 count 消息均完整保留，**状态无损**（证据 `batch13_shots/07-returned-to-session.png`） |

### 4.1 长回合**功能**本身健康（补偿性观察）

尽管通知未出，**后台长回合执行是成功的**：第一次长回合（文本因我方 `%stofrom` 笔误成 `Count tofrom …`）在应用全程处于后台时**跑完并回填**，
时间线出现完整 `…,497,498,499,500`，署名 **`Build · Exo Free · 4m 0s`**；第二次（精确原文）亦正常进入生成。
⇒ **「后台断 SSE / 切走导致回合被掐断 / 状态丢失」类问题本轮未复现**；失败面**仅限通知投递与 `P7` 埋点本身**。

---

## 5. 收尾复验 — ✅

| 项 | 结果 |
|---|---|
| `adb -s emulator-5680 reverse --remove-all` | ✅ 复验 `reverse --list` 为**空** |
| `adb -s emulator-5680 forward --remove-all` | ✅ 复验 `forward --list` 为**空**（4173 / 9222 均已撤） |
| 后端 pid 21136 | ✅ **未变**、`Get-Process` 存活、`netstat` 仍 `127.0.0.1:4095 LISTENING 21136`、`HTTP 200` |
| 自建 AVD kill | ➖ **不适用**：6790 实例因 AVD 锁从未存活；`emulator-5680` **非本轮自建**（10:24 早于本会话），故**未 kill**，原样交还 |
| 碰过的 serial | **`emulator-5680`**（写）；`emulator-5554`（**仅 `adb devices` 读取，零写**） |

---

## 6. 结论

- **核心判死项（加服务器不自锁）：✅ 通过。** 批 #12 的 v2 过度匹配隐藏已随批 #13 整体下线而消失；Home 的上下文切换入口 `home-projects-dropdown` 恢复 `display:flex` / 1017×74 / 无 `data-oc-phantom`，真实点击可列出并切到 4095，6 会话正常渲染。**v2 的「隐藏 4173 行」方案不可再上**（会自锁且上游不消费 hidden-map）；如未来要隐藏 4173，应在**不触碰 `home-projects-dropdown`** 的前提下另寻入口。
- **快速冒烟 4/4 通过**：终端真 PowerShell 回显、发消息得回复、后台 pagehide+断 SSE / 回前台 pageshow+重建、主题 dark（`dataset.colorScheme=dark`）。
- **T6 2/4**：**长回合后台执行与状态保全健康**，但 **`P7 onPause: round active` 埋点在本构建不存在**，且**后台长回合未投递任何本包通知**（`POST_NOTIFICATIONS` 已 granted，下拉与 `dumpsys` 双重证实）。若 T6 的通知是发布门槛，则这是一个**真实未修复缺口**；需确认该能力是「本批未包含」还是「回归丢失」。

**报告路径**：`research/web/UX17_P4_BATCH13.md`
**证据截图**：`research/batch13_shots/`（7 张：`01` 切换器可见 / `02` 4095 六会话 / `03` 终端回显 / `04` dark 主题 / `05` 设置双服务器条目 / `06` 下拉无本包通知 / `07` 点回会话）
