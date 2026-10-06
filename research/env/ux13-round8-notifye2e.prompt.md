# 轮次：UX13-P8 —— 通知修复端到端复验（新包：权限弹窗→后台通知→点击回会话→空闲断流回归）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：`UX13_P7_NOTIFY_FIX.md` 已实施宿主修复——回合进行中后台只派 `blur`（SSE 保活）、空闲后台照旧 `pagehide` 断流、回前台对称、首次回合开始主动申请 POST_NOTIFICATIONS。本轮在设备上端到端复验。**只验证，不改代码。**
**先建报告 `research/web/UX13_P8_NOTIFY_E2E.md`，每步写盘；单条命令 + 立即读输出；真实点击；注入只读打点并标注。**

## 环境
- 包 `research/deliver/opencode-shell-web.apk`（P7 批，装前 Get-FileHash 记录，预期 md5 `debb8c57c5a8fdc1e1e7fd3c29fb5797`）。
- 后端 `127.0.0.1:4095`（opencode/opencode，v2.0.24）已在跑，**不许起停**。
- AVD `Medium_Phone_API_37.0`，端口 **6790**（先查占用）；`adb reverse tcp:4095 tcp:4095`（收尾 remove）。
- 注入 `am start --es oc_debug_eval "b64:..."`（不能 async）；logcat grep `oc-pow|OcPerfJS|notification|permission|pagehide|blur`。
- 可达路径同 UX13-P5（简写：加服务器=Settings 头部 `+`；切上下文=Home chevron；进会话点行）。
- 纪律：只关自建 emulator-6790；不 kill-server；不改代码；不碰 gradle。

## 步骤

### 0. 起机装包（pm clear → am start → 5s）

### 1. 权限弹窗（预期：首次回合开始触发 POST_NOTIFICATIONS 申请）
1. 连服务器+切上下文+进会话。
2. 真实发消息 `reply with the single word OK`（发送瞬间就触发申请）。
3. 观察：系统权限弹窗是否出现（截图 `ux13p8_perm_dialog.png`）；`adb shell dumpsys notification` 前先看 `pm dump com.xiufeigo.opencodeshell.web.debug | grep POST_NOTIFICATIONS` 授权态从 denied→granted。
4. **真实点击"允许"**。若弹窗未出现，如实记录并查 logcat（这轮的核心验收点之一）。

### 2. 后台通知端到端（核心）
1. 再发一条消息 → **立刻 HOME** → 等 25–30s（期间不要碰设备）。
2. 证据（全部抄原文）：
   - logcat：`blur` 已派（宿主日志）、**不再派 pagehide**、4095 流存活（`event stream OPEN` 无中途 CLOSE）；
   - `[oc-pow] round end session.execution.succeeded` **在后台窗口出现**（修复的判定性证据）；
   - `dumpsys notification` 出现本包通知（渠道 `oc_turns`，含标题/正文）；
   - 下拉通知栏截图（`ux13p8_notif_shade.png`）。
3. **真实点击该通知** → 判定：回到**对应会话页**（topActivity=WebHostActivity，页面是会话非 Home）。截图。

### 3. 空闲断流回归（不许变坏）
1. 回到 Home（不在任何回合中），静置 10s 确认空闲。
2. KEYCODE_HOME 切后台 → 15s → 回前台。
3. 判定：空闲后台仍派 `pagehide`、SSE 断开（OPEN/CLOSE 日志）；回前台 pageshow 重连、列表/会话可正常加载。
4. 常亮回归（顺手）：发消息置 `flagSet=true`，等回复释放（复用 UX12 口径，Δ 秒级）。

### 4. 通知静音语义（如实记录即可）
后台窗口若通知未出现，查上游闸门各值（perm/vis/focus/hasOpenTab）并判定卡点——是预期语义（如会话 tab 未开）还是缺陷。

### 5. 收尾（同前纪律：emu kill、reverse --remove、后端 pid 未变+200、列 serial）

## 汇报（最后一行）
权限弹窗 出现/未出现 · 后台 round end succeeded 到/未到 · 通知 发出/未发出 · 点击回会话 通/不通 · 空闲断流回归 PASS/FAIL · 报告路径 · 碰过的 serial。
