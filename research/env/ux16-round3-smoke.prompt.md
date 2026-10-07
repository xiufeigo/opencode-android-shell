# 轮次：UX16-P3 —— 批 #10 最终冒烟（8 项核心回归，精确导航版）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：批 #10 把 WebView 初始化链抽成了 `setupWebViewAndLoad()`（声称语义零改动，崩溃自愈已 CDP 实证）。按最终门纪律，**核心 8 项必须在批 #10 字节体上重跑**。上一门 6 项 BLOCKED 是导航失败（误进 Preferences 页/拿错下拉），本轮给你**逐轮验证过的精确路径**。**只验证不改代码。报告 `research/web/UX16_P3_BATCH10_SMOKE.md`。**

## 环境
- 包 `research/deliver/opencode-shell-web.apk`（批 #10，装前 Get-FileHash：预期 16,830,822 / md5 `609D0F37…`）。
- 后端 `127.0.0.1:4095`（opencode/opencode，**pid 21136**）在跑**不许起停**；`adb reverse tcp:4095 tcp:4095`（收尾 remove）。
- AVD `Medium_Phone_API_37.0`，端口 **6790**（你自建自关；**emulator-5554 禁止任何写操作**）。
- 注入 `am start --es oc_debug_eval "b64:..."`（不能 async）；大 JSON 走 CDP（forward 9333）。
- ⚠️ 导航精确路径（前 8 轮实证，直接用）：
  1. **Add server**：Settings 页**头部无标签 "+"**（设备坐标约 680,215——先 uiautomator/截图校准，不在抽屉里）；或 Home 抽屉 → Settings → 段选择器 `Preferences ▾` → `Server` 段 → 段内 `+ Add server`（两个入口都行，卡住就换另一个）。
  2. **切服务器上下文**：Home 顶部 `All projects ▾` 右侧的 **chevron（约 992,262）** → 弹出的**服务器列表**（不是项目列表）→ 点 `127.0.0.1:4095` 行。若该下拉只有项目：改用 Home 抽屉 → 设置 → Server 段选择器里的逐服务器条目。
  3. 进会话：列表点会话行（如 `v2 smoke test`）。

## 步骤
0. 起机装包（install -r → pm clear → am start）→ **Add 4095+opencode（等≥14s）⇒ 绿点** → 切上下文 ⇒ 列表渲染。截图。
1. **首启语义**（重放一次 pm clear 冷启）：4173 红点行可见；Add 4095 成功后 4173 行消失。
2. **终端可用**：进会话 → 终端 → 提示符 → 敲 `echo ux16p3gateok` → 回显。
3. **发消息有回复**：`reply with the single word OK` → 等回复。
4. **回合常亮**：flagSet=true→false + Δ。
5. **空闲后台断 SSE**：HOME ⇒ pagehide+CLOSE；回前台 ⇒ pageshow+OPEN 200。
6. **后台回合通知**：发消息→立刻 HOME→25s→dumpsys 有通知→下拉截图→**真点回会话**。
7. **主题**：dark/light 各读 dataset.colorScheme + 截图。
8. **键盘不裁切**：会话屏弹软键盘，composer 底边 ≤ vv.height，截图。
9. 收尾（同前纪律：emu kill 自建、reverse --remove、后端 pid 21136 未变+200、列 serial）。

## 汇报（最后一行）
8 项 逐项 P/F（一句话证据）· 报告路径 · 碰过的 serial。
