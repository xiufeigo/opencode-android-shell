# 轮次：UX13-P7 —— 后台回合保活 SSE + 通知权限主动申请（宿主修复轮，含重建）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
**每步一条命令 + 立即读输出；结论当场写进 `research/web/UX13_P7_NOTIFY_FIX.md`。**

## 根因（P6 已钉死，直接采信）

后台通知死锁链：`onPause` 合成 `pagehide`（`WebHostActivity.kt:708`）→ 上游 pageLifecycle `abort()` 掐断 4095 SSE → 后台期间后端发的 `session.execution.succeeded`（ HOME 后 ~2s 才到）必丢 → 上游通知闸门（只挂终态帧）从不评估 → 上游从不调 `Notification.requestPermission()` → 宿主 `POST_NOTIFICATIONS` 申请 launcher 从不触发 → 一条通知都没有。前台链路健康（succeeded Δ2.1s 到达）。

## 第 0 步：读 bundle 定设计事实（只读，30 分钟内完成）

在 `webapp/src/main/assets/www/ui/oa/` 的 JS 里 grep（只读，不改这些文件）：
1. `hasFocus`、`pagehide`、`pageshow`、`visibilitychange`、`visibilityState`：弄清**上游 coordinator 的 hasFocus 闸门与 pageLifecycle 到底吃什么事件/状态**——关键设计问题：后台时若不派 pagehide，只派 `blur`/`visibilitychange`，上游的"未聚焦"语义会不会翻转？pageLifecycle 状态机是只认 pagehide/pageshow 还是也认 visibilitychange？
2. `server.connected`：P6 记上游 SSE 要求首帧；记录即可。
3. 上游 notify 闸门调用 `Notification.requestPermission` 的精确条件（permission 值各分支）。
把原文片段抄进报告，并据此刻下**事件映射表**：后台(回合进行中)应派发什么、后台(空闲)应派发什么、回前台应派发什么。

## 第 1 步：宿主修复（只许改下面列出的位置）

### 1a. `WebHostActivity.kt`（onPause 断流改回合感知）
当前：`onPause` 无条件 `dispatchPageLifecycleEvent("pagehide")`。
改为：查询回合活跃状态（先读 `RoundActivityTracker`/`PowerPolicy` 现有 API，优先复用原生跟踪器；若原生侧只有 90s 兜底无实时 busy，就从桥的 keepScreenOn 状态或现有 flag 取——设计选择写明理由）：
- **回合进行中**（用户刚发消息等回复）后台：不派 pagehide；改派第 0 步事件映射表确定的"未聚焦但不断流"事件（如 `window.dispatchEvent(new Event('blur'))` 和/或 visibilitychange）。
- **空闲**后台：维持原 pagehide 不变。
- 回合在后台结束时（传感器经桥回报/原生跟踪器转空闲）：补派 pagehide 断 SSE（省电收口）。
- `onResume` 对称：若此前是"保活路径"，回前台补派对应聚焦事件（focus/visibilitychange/pageshow 按映射表）。
注意：UX8 的既有语义（空闲后台断流）不许变坏——平板/手机已验收项。

### 1b. 通知权限主动申请（打破死锁）
选点：**首次观测到回合开始（`RoundActivityTracker` 进入 busy / 桥收到 setKeepScreenOn(true)）且前台**时，若 `POST_NOTIFICATIONS` 未 granted，触发既有权限 launcher（复用 `WebHostActivity.kt:238/542` 附近的既有申请链，别新造）。上游真正通知到达时闸门里的 requestPermission 路径保持不变。
（设计理由：权限弹窗出现在用户刚发消息的上下文，比启动即弹更合理；且打破"闸门从不评估"的死锁。）

### 1c. `oc-host.js` 传感器日志增强（消灭 P5/P6 的误读面）
`event stream OPEN/CLOSE` 的 path 前补 **origin 与 resp.status**（tee 处拿得到 response 就记）；`EVENT_PATH_RE` 命中但 status 非 2xx 时记一行 `event stream NON2XX #id origin status`。注入件允许改（本轮授权），但**行为不变**：仍是纯透明 tee，一个字节不截留。

### 1d. 绊线（新增/更新测试）
1. `Ux8KeepAwakeSseTripwireTest` 或新增 `BackgroundRoundKeepaliveTest`：断言 1a 的回合感知分支存在（源码级：pagehide 不再无条件派发、存在 blur/visibilitychange 分支、复用 RoundActivityTracker）；断言 1b 的 busy 触发权限申请存在。
2. 若 `Ux8KeepAwakeSseTripwireTest` 有"无条件 pagehide"的旧断言，更新为新不变量（历史理由写注释）。
3. 反向验证：备份→回退 `WebHostActivity.kt`（`git show bc87e72:...`）与 `oc-host.js`（git 对象）→ 新测试真红→恢复→全绿。抄原文。

### 1e. 测试 + 重建 + 落盘
- 锁协议跑 `:webapp:testDebugUnitTest`（预期 198+N 全绿；失败数如实）。
- `.\gradlew.bat --console=plain :webapp:assembleDebug :webapp:assembleRelease :webapp:assembleReleaseR8`；复制三份到 `research/deliver/` 覆盖。
- 包内自查三份：ui=1613、oa=1590、包内 oc-host.js md5==**新源文件** md5（本轮 oc-host.js 变了，登记新值）。凭据扫描（改动文件）0 命中。

## 🔴 红线
不改 `ui/` 官方资源任何字节（第 0 步只读）；不 git commit；不起停 4095 后端；不改 `app/**`；失败停下写报告。

## 汇报（最后一行）
事件映射表结论（一句话）· 改动文件清单 · 用例数/反向验证红条 · 三份新产物 体积/md5 + 新 oc-host.js md5 · 锁已释放 · 报告路径。
