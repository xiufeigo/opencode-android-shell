# 轮次：UX17-P2 —— 终验：hidden-map 端到端 + T6 长回合验证 + 完整冒烟（逐坐标剧本）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：批 #11（hidden-map）机制已证到位（官方消费我们写的注册表），差端到端；T6 疑为**测试时序**（短回合+HOME 偏晚），需长回合证伪。⚠️ 上轮导航失败的根因是"扫 DOM 找入口"——**抽屉默认关闭，入口不在 DOM 里；必须真实点击**。本轮给你逐坐标剧本，照做即可。**只验证不改代码。报告 `research/web/UX17_P2_FINAL_VERIFY.md`。**

## 环境
- 包 `research/deliver/opencode-shell-web.apk`（批 #11：debug 16,827,823 / md5 `F03A86EF…`；装前 Get-FileHash 核对）。
- 后端 `127.0.0.1:4095`（opencode/opencode，**pid 21136**）在跑**不许起停**；`adb reverse tcp:4095 tcp:4095`（收尾 remove）。
- AVD `Medium_Phone_API_37.0`，端口 **6790**（你自建自关；**emulator-5554 禁止任何写操作**）。
- 逐坐标剧本（来自 P1/P2/UX16-P3 成功轮，若某步点击后 3s 无响应，先截图再微调 ±30px 重试一次，仍不行才换备选路径）：
  1. **打开抽屉**：Home 左上 hamburger `(67,120)`。
  2. **Settings**：抽屉里 `Settings` 行（约 `(179,2324)`）。
  3. **段选择器**：Settings 页顶部 `Preferences ▾`（约 `(868,216)`）→ 弹层选 `Server`。
  4. **Add server**：Server 段内 `+ Add server`（约 `(906,482)`；**备选**：Settings 页头部无标签 `+`（约 `(680,215)`））。→ 对话框 `Server address` 输入 `http://127.0.0.1:4095`、`Password` 输入 `opencode` → **等 ≥14 秒** → 提交按钮（约 `(907,1652)`；若被键盘遮挡先 `input keyevent 4`(BACK) 收键盘再点）。
  5. 全程截图；每步失败先截图留证再微调。

## 步骤
### 0. 起机装包（install -r → pm clear → am start → 5s）
**首启判定**：设置导航/Home 里 4173 builtin 行**可见**（hidden-map 不应在首启写任何键）→ 注入读 `localStorage.getItem('opencode.global.dat:server')` 应为 null。

### 1. 按剧本 Add 4095 ⇒ 绿点后**不要点任何切换器**，判定：
- ① 4173 从设置导航/Home/下拉**全部消失**；
- ② Home 上下文**自动**落在 4095（副标题/会话列表直接渲染，**无 Loading 死锁**）——这就是 hidden-map 的端到端验收；
- ③ `localStorage['opencode.global.dat:server']` 的 `hidden` 含 `http://127.0.0.1:4173`。

### 2. T6 长回合验证（核心）
1. 进会话（列表点行）；**先抓权限**：发任意短消息 → 若弹 POST_NOTIFICATIONS 弹窗则真点允许；若无弹窗（可能已被上轮 grant 过）→ `pm grant android.permission.POST_NOTIFICATIONS`；
2. 发长回合消息：`Count from 1 to 500, comma separated, think step by step`（**发送后立刻** `input keyevent KEYCODE_HOME`，间隔 <1.5s）；
3. 全程 logcat（`oc-pow|P7 onPause|setKeepScreenOn|D1 applyKeepScreenOn|event stream`）：判定：
   - onPause 走 `P7 onPause: round active -> keep SSE alive`（**不是** pagehide）；
   - 后台窗口出现 `round end session.execution.succeeded`；
   - `dumpsys notification` 本包通知出现 + 下拉栏截图 + **真实点击回会话**；
4. 若仍全走 pagehide：抄三条日志原文回来（不猜）。

### 3. 冒烟四项（回归确认）
终端 echo 回显 · 空闲后台断 SSE/回前台（HOME 前确保无回合）· 主题 dark 一次 · 软键盘 composer 底边 ≤ vv.height。

### 4. 收尾（emu kill 自建、reverse --remove、后端 pid 21136 未变+200、列 serial）

## 汇报（最后一行）
首启 null✅ · ①消失✅ ②自动切换✅ ③hidden键✅ · T6（保活分支/后台succeeded/通知/点击回会话 四格）· 冒烟四项 P/F · 报告路径 · 碰过的 serial。
