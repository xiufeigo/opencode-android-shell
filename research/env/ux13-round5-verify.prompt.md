# 轮次：UX13-P5 —— 新包复验：相机扫码（应转绿）+ 通知链路决定性诊断

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：刚完成两轮修复重建（相机可信 origin 加入 4173；产品负责人拍板全局明文放行=选项 C）。被测包 = `research/deliver/opencode-shell-web.apk`（**先 Get-FileHash 记录，装这个包**）。
**先建报告 `research/web/UX13_P5_VERIFY.md`，每步写盘；单条命令 + 立即读输出；真实点击；注入仅只读打点并标注。**

## 环境
- 后端 `127.0.0.1:4095`（opencode/opencode，v2.0.24）已在跑，**不许起停**。
- AVD `Medium_Phone_API_37.0`，端口 **6790**（先查占用）；`adb reverse tcp:4095 tcp:4095`（收尾 remove）。
- 注入 `am start --es oc_debug_eval "b64:..."`；logcat `OcWebHost/Eval`（级别 I，grep 用）；**注入不能 async**。
- 可达路径（复用 UX13-P1/P2 结论）：加服务器 = Settings 页头部无标签 `+`；Scan QR code 只在 Add server 对话框；切上下文 = Home 项目行右侧 chevron → 服务器列表点 4095 行；进会话点行。
- 纪律：只关自建 emulator-6790；不 kill-server；不改任何代码；不碰 gradle。

## 步骤

### 0. 起机装包
emulator（Start-Process 脱离）→ boot → reverse → install（新包）→ `pm clear` → am start → 5s。**首屏后立刻注入 fetch 打点（关键，先于一切操作）**：
b64 包装：
```
var F=window.fetch; window.__evtFetches=[]; window.fetch=function(u,o){ try{ var s=String(u&&u.url!==undefined?u.url:u); if(s.indexOf('/api/event')>=0) window.__evtFetches.push({u:s,m:o&&o.method||'GET',t:Date.now()}); }catch(e){} return F.apply(this,arguments); }; "ok"
```
（若上游用 EventSource 而非 fetch，则此打点为空——回读时一并验证 `window.__esWrapped`，并补一条 EventSource 包装打点：同 UX13-P2。）

### 1. 连服务器 + 切上下文 + 进会话
同前（简写）。

### 2. 相机扫码复验（应转绿）
真实点击 Add server 对话框 → **Scan QR code**。
- logcat 抓 `camera permission`：`GRANTED origin=http://127.0.0.1:4173` 出现、DENIED 归零；
- 页面：video readyState（注入读）、有无 "Could not open the camera" 报错；
- 判定：通过 = GRANTED 且无 DENIED 且无页面报错（模拟器无真摄像头，流可能黑屏/无画面，**只要权限授予+无报错即算通过**；如实记录流状态）。
- 截图。

### 3. 通知链路诊断（决定性）
1. 包装 Notification（注意透传静态属性 permission/requestPermission，别犯 P2 的错）：
```
var N=window.Notification; window.__notifCalls=[]; var W=function(t,o){ window.__notifCalls.push({t:String(t),b:o&&String(o.body)}); return new N(t,o); }; W.permission=N.permission; W.requestPermission=N.requestPermission; window.Notification=W; "ok"
```
2. 在会话里发消息 `reply with the single word OK` → 立刻 KEYCODE_HOME → 等 25s → 回前台；
3. 回读打点 b64：
   - `JSON.stringify({evtFetches:window.__evtFetches, notifCalls:window.__notifCalls})`
   - `JSON.stringify({vis:document.visibilityState, focus:document.hasFocus(), perm:(window.Notification&&window.Notification.permission)})`
4. logcat 全量 grep：`logcat -d | grep -E "oc-pow|camera permission|Notifier|OcNotify"` 存盘，重点：
   - `event stream OPEN/CLOSE` 的**频率与时刻**（是否仍 ~1s 一次重连风暴）；
   - `round start session.execution.started` / `round end session.execution.succeeded` **是否成对出现**（succeeded 是否终于到达）；
   - 与 `__evtFetches` 的 URL 对照：事件流到底连 4173 还是 4095。
5. `dumpsys notification` 查 oc_turns 渠道有无新通知。
6. 判定：
   - 若 `__evtFetches` 全指向 4173 ⇒ 客户端事件流 URL 构造用了 location.origin（上游 web 平台行为）⇒ 归因上游/产品级，如实记录；
   - 若指向 4095 且 round end 出现、notifCalls 有 ⇒ 通知链路通，找上游闸门为何不放行（perm/vis/focus/hasOpenTab 值逐条列出）；
   - 若指向 4095 但 round end 缺失 ⇒ 流被重连风暴截断，量化风暴间隔。

### 4. 收尾
emu kill 6790；reverse --remove；后端 pid 未变 + /api/info 200；列 serial。

## 汇报（最后一行）
相机 绿/红（GRANTED? DENIED 计数）· 通知断点定位（URL 指向/round end 有无/闸门值）· 报告路径 · 碰过的 serial。
