# 轮次：UX17-P3 —— 批 #12 组合复验（幻影 v2 + 崩溃自愈Kotlin + T6 长回合）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：批 #12 = DOM 隐藏 v2（回退自 ac0bb01）+ WebView 重建式自愈 Kotlin（UX16-P2 未提交部分）。两者各自实证过，本轮验**组合**。**只验证不改代码。报告 `research/web/UX17_P3_BATCH12.md`。**

## 环境
- 包 `research/deliver/opencode-shell-web.apk`（批 #12：16,830,627 / md5 `FE2A8AA8…`，装前核对）。
- 后端 `127.0.0.1:4095`（opencode/opencode，**pid 21136**）在跑**不许起停**；`adb reverse tcp:4095 tcp:4095`（收尾 remove）。
- AVD `Medium_Phone_API_37.0`，端口 **6790**（你自建自关；**emulator-5554 禁止任何写操作**）。
- 导航剧本（坐标来自成功轮，卡住先截图再 ±30px 重试）：抽屉 `(67,120)` → `Settings (179,2324)` → 段选择器 `(868,216)` 选 `Server` → `+ Add server (906,482)`（备选：页头无标签 `+ (680,215)`）→ 填 `http://127.0.0.1:4095`+`opencode` → **等≥14s** → 提交 `(907,1652)`（键盘挡就先 BACK 收键盘）。
- 注入 `am start --es oc_debug_eval "b64:..."`。

## 步骤
### 0. install -r → pm clear → am start
首启：4173 builtin 行**可见**（设置导航+Home）→ 注入读 `localStorage['opencode.global.dat:server']`=null。

### 1. 按剧本 Add 4095 ⇒ 绿点 ⇒ **不点切换器**，判定：
- 4173 行从设置导航/Home 消失（DOM v2 条件隐藏生效）；
- 手动切上下文（Home 服务器下拉点 4095 行；找不到下拉就用抽屉→Settings→Server 段里的 4095 条目）⇒ 列表渲染。

### 2. T6 长回合（组合验证）
进会话 → `pm grant` 或弹窗点允许 POST_NOTIFICATIONS → 发 `Count from 1 to 500 comma separated` → **立刻 HOME（<1.5s）** → 25s：判定 `P7 onPause: round active` 日志在、`round end succeeded` 在后台窗口、`dumpsys notification` 有本包通知、下拉截图、**真点回会话**。

### 3. 崩溃自愈组合快验（可选，时间够就做）
CDP `Page.crash` 一次 ⇒ 观察重建分支日志 + 页面恢复可用 ⇒ PASS（节流与中文在 UX16-P2 已证，不重复）。

### 4. 收尾（emu kill 自建、reverse/forward remove、后端 pid 21136 未变+200、列 serial）

## 汇报（最后一行）
首启可见✅/null✅ · Add后4173消失✅/手动切换✅ · T6四格（保活/succeeded/通知/点击） · 崩溃组合（若做）· 报告路径 · 碰过的 serial。
