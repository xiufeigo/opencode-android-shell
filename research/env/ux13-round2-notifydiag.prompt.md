# 轮次：UX13-P2 —— 通知链路定位诊断（只读，不改代码）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：`research/web/UX13_P1_CAPABILITIES.md` 步骤 3 实测：App 后台 + 助手回复 OK，但**一条通知都没发**。通知 shim 设计（`webapp/src/main/assets/www/oc-host.js:482-551`）：上游策略命中时 `new Notification(title,{body})` → shim → `oc.postMessage(title,body,sid)` → 宿主 `NotifierHost` 发 Android 通知。上游闸门（同文件注释）：web.ts `("Notification" in window)`、权限、`hasFocus()`、`hasOpenTab`。
**你的任务：注入只读打点，精确定位断点在哪一环。不改任何代码。**

**先建报告 `research/web/UX13_P2_NOTIFY_DIAG.md`，每步写盘；单条命令 + 立即读输出；真实点击/按键；注入仅做只读打点并标注。**

## 环境（已核好）
- 包 `research/deliver/opencode-shell-web.apk`（debug）；包名 `com.xiufeigo.opencodeshell.web.debug`。
- 后端 `127.0.0.1:4095`（opencode/opencode，v2.0.24）已在跑，**不许起停**。
- AVD `Medium_Phone_API_37.0`，端口 **6790**（先查占用）；`adb reverse tcp:4095 tcp:4095`（收尾 remove）。
- 注入 `am start --es oc_debug_eval "b64:..."`；logcat 标签 `OcWebHost/Eval`（级别 I，用 `logcat | grep` 或 `-s OcWebHost:V`）。**注入代码不能 async**。
- 可达路径（上轮实测结论，直接用）：加服务器 = Settings 页头部无标签 `+`（设备 680,215）→ Add server 对话框；切上下文 = Home 项目行右侧 chevron(992,262) → 服务器列表 → 点 4095 行；进会话后直接发消息。

## 步骤

### 0. 起机装包
emulator 起（Start-Process 脱离）→ boot → reverse → install → pm clear → am start → 等 5s。

### 1. 连服务器 + 切上下文 + 进会话（同 UX13-P1 路径，简写记录）

### 2. **后台前**注入打点（关键，全部 b64，一次一条）
1. 包装 EventSource：`var E=window.EventSource; window.__esLog=[]; window.__esInst=[]; window.EventSource=function(u,c){window.__esLog.push(String(u)); var i=new E(u,c); window.__esInst.push(i); return i;}; "wrapped"`
2. 包装 Notification：`var N=window.Notification; window.__notifCalls=[]; window.Notification=function(t,o){window.__notifCalls.push({t:String(t),b:o&&String(o.body)}); return new N(t,o);}; "wrapped"`
3. 包装 oc.postMessage：`var P=window.__ocAndroid.postMessage.bind(window.__ocAndroid); window.__pmCalls=[]; window.__ocAndroid.postMessage=function(t,b,s){window.__pmCalls.push({t:String(t),b:String(b),s:String(s)}); return P(t,b,s);}; "wrapped"`
4. 读基线：`JSON.stringify({vis:document.visibilityState,focus:document.hasFocus(),notifPerm:window.__ocAndroid.hasNotificationPermission()})`

### 3. 触发场景（真实操作）
1. 在会话里真实点 composer → `input text "reply with the single word OK"` → 发送；
2. **立刻** KEYCODE_HOME 切后台；
3. 等 25 秒（助手回复完成）；
4. 回前台（am start 原 Activity 或 recent）。

### 4. 回读打点（b64，逐条）
1. `JSON.stringify({esLog:window.__esLog, esStates:(window.__esInst||[]).map(function(i){return i.readyState}), notifCalls:window.__notifCalls, pmCalls:window.__pmCalls})`
2. `JSON.stringify({vis:document.visibilityState, focus:document.hasFocus(), hasNotif:("Notification" in window), shim:window.Notification&&window.Notification.name, perm:(window.Notification&&window.Notification.permission)})`
3. `adb shell dumpsys notification` 全量找 OpenCode/oc_turns（时间窗口内）。

### 5. 判定断点（四选一，给证据）
- **A 上游没调 Notification**（notifCalls 空）⇒ 上游闸门挡住：看 esStates/vis/focus/hasOpenTab 推断是哪个闸门（hasFocus? hasOpenTab? SSE 没建立导致 coordinator 没收到回合完成帧?）
- **B shim 调了但 oc.postMessage 没被调**（notifCalls 有、pmCalls 空）⇒ shim 内部异常
- **C postMessage 调了但宿主没发通知**（pmCalls 有、dumpsys 无）⇒ 宿主 NotifierHost 侧问题
- **D 通知发了但被系统挡**（dumpsys 有记录但不可见）
另：**SSE 是否连到 4095**（esLog 里应有 `http://127.0.0.1:4095/api/event`；若只有 4173 ⇒ 跨源 EventSource 是根因）。

### 6. 收尾
emu kill 6790；reverse --remove；后端 pid 未变 + /api/info 200；列 serial。

## 汇报（最后一行）
断点 A/B/C/D + 一句话依据（esLog/闸门关键值）+ SSE 是否到 4095 + 报告路径 + 碰过的 serial。
