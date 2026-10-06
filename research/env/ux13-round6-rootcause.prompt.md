# 轮次：UX13-P6 —— 通知根因二分诊断（前台帧送达 × 后台断流 × 全局流归属 × 权限申请链）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：`research/web/UX13_P5_VERIFY.md` 已定位：① 相机已绿；② 通知断在「回合完成帧 `session.execution.succeeded` 未到达客户端」；③ 页面事件流 6/6 直连 4095、`started` 到、`succeeded` 在后台窗口丢失（疑与 UX8 合成 pagehide 断 SSE 的设计冲突）；④ 有一条 **4173 全局 sdk 流**每秒空转重连（OcPerfJS 91 次）；⑤ `POST_NOTIFICATIONS` 从未申请（Android granted=false）。
**本轮只读诊断（不改代码），把以下四个问题一次钉死，为修复轮提供设计依据。**

**先建报告 `research/web/UX13_P6_NOTIFY_ROOTCAUSE.md`，每步写盘；单条命令 + 立即读输出；真实点击；注入仅只读打点并标注。**

## 环境
- 包 `research/deliver/opencode-shell-web.apk`（P4 最终批，装前先 Get-FileHash 记录）。
- 后端 `127.0.0.1:4095`（opencode/opencode，v2.0.24）已在跑，**不许起停**。
- AVD `Medium_Phone_API_37.0`，端口 **6790**（先查占用）；`adb reverse tcp:4095 tcp:4095`（收尾 remove）。
- 注入 `am start --es oc_debug_eval "b64:..."`；logcat grep `oc-pow|OcPerfJS|camera permission|Notifier|notification`。
- 可达路径同 UX13-P5（简写）。
- 纪律：只关自建；不 kill-server；不改代码；不碰 gradle。

## 步骤

### 0. 起机装包（同前，pm clear → am start → 5s）
首屏后注入 fetch 打点（同 P5：记录含 `/api/event` 的完整 URL+method+时间）。

### 1. 【问题A】前台回合：succeeded 帧到底到不到？（全程不切后台）
1. 连服务器+切上下文+进会话（同前）。
2. 会话内真实发消息 `reply with the single word OK`，**保持前台**等回复完全渲染（≥20s）。
3. logcat grep 抄原文：`round start` / `round end` / `event stream OPEN/CLOSE`（带时刻与 path）。
4. 回读 `__evtFetches`（4095 流的次数/时间分布）。
5. 判定 A1：前台 `round end session.execution.succeeded` 出现了吗？
   - 有 ⇒ 前台链路健康，问题纯在后台断流设计 ⇒ 记录时间差（start→end）。
   - 无 ⇒ 4095 这条流不承载执行帧或中途断 ⇒ 看 OPEN/CLOSE 时刻与该流存活窗口。

### 2. 【问题B】4173 全局流是什么、 carrying 什么
1. logcat grep `OcPerfJS res` 里 `4173/api/event` 的响应状态（404？）与频率。
2. 注入只读打点：**包装 EventSource**（P2 已证页面主事件流不走 EventSource——本轮再确认一次）+ 读 `window.__ocAndroid` 上有没有 sdk/事件相关句柄；另注入：
   `JSON.stringify({perfEntries:(performance.getEntriesByType('resource')||[]).filter(function(e){return e.name.indexOf('/api/event')>=0}).map(function(e){return {n:e.name.slice(0,80),d:Math.round(e.duration)}}).slice(0,30)})`
   （performance entries 能看到页面侧所有 /api/event 资源的真实 URL 与耗时分布——这是比 fetch 打点更全的观测面。）
3. 判定 B1：4173 全局流的发起方（谁在读它）、与 4095 流的分工（哪个流出现过 execution 帧）。注意 oc-pow 的 `round start` 帧是从哪条流 tee 到的（对照该流 OPEN 的时刻窗口）。

### 3. 【问题C】后台断流的确切机制
1. 复现 P5 场景（发消息→立刻 HOME→25s→回前台），全程 logcat。
2. 抓 `dispatchLifecycle`/`pagehide`/`pageshow`/oc-pow 相关宿主日志（UX8 的合成派发应有日志或可从行为推断）。
3. 判定 C1：HOME 后 4095 流是「宿主合成 pagehide 驱动上游主动关」还是「WebView 网络被冻结」？回前台后 UI 的 OK 是从哪次 REST 拉取补上的（OcPerfJS res 里找 GET session/messages 类请求）。
4. 顺带验证：后台期间后端确实发出了 succeeded（从你收到的 logcat/宿主日志侧面印证即可，或跳过并引用 P5 的 idle outcome 证据）。

### 4. 【问题D】权限申请链
1. 检查宿主何时会调 `requestNotificationPermission`（只读代码：`WebHostActivity`/`NotifierHost`/`oc-host.js` 的 shim requestPermission 路径；不改）。
2. 判定 D1：链路里哪一环负责触发首次 POST_NOTIFICATIONS 申请、上游事件不来时它会不会永远不被触发；若前台回合（问题A有 succeeded）后 `perm` 是否仍停在 default（说明上游从未走到 requestPermission）。

### 5. 收尾（同前纪律）

## 汇报（最后一行）
A1 前台 succeeded 有/无 · B1 4173 流归属一句话 · C1 后台断流机制一句话 · D1 权限触发点 · 修复面建议（宿主可修/上游固有）· 报告路径 · 碰过的 serial。
