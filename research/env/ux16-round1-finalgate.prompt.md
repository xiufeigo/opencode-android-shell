# 轮次：UX16-P1 —— 批 #9 收尾门：流式帧时 + 渲染崩溃自愈真触发 + 核心回归冒烟

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：批 #9 是 UX12–UX15 全部改动的当前交付（幻影条件化隐藏/通知四段/相机/render 自愈/选项C）。本轮把三个遗留项一次收掉并做最终门回归。**只验证，不改代码。**
**先建报告 `research/web/UX16_P1_FINAL_GATE.md`，每步写盘；单条命令 + 立即读输出；真实点击；注入只读打点并标注。**

## 环境
- 包 `research/deliver/opencode-shell-web.apk`（批 #9，装前 Get-FileHash 记录：预期 debug 16,830,822 / md5 90E6A6D7…）。
- 后端 `127.0.0.1:4095`（opencode/opencode，v2.0.24，**pid 21136**）已在跑**不许起停**；`adb reverse tcp:4095 tcp:4095`（收尾 remove）。
- AVD `Medium_Phone_API_37.0`，端口 **6790**（先查占用；**只许碰这台自建设备**，`emulator-5554` 是他人设备，禁止安装/任何写操作）。
- 注入 `am start --es oc_debug_eval "b64:..."`（不能 async）；大 JSON 走 CDP（forward 9333）。
- 纪律：谁起谁关（emulator-6790 你起你关）；不 kill-server；不改代码；不碰 gradle。

## 步骤

### 0. 起机装包（你自建 emulator-6790）→ pm clear → am start
连服务器 `http://127.0.0.1:4095`+opencode（UI Add，等≥14s）⇒ 绿点；**Home 切上下文到 4095**（chevron→点行）⇒ 列表渲染。全程截图。

### 1. 核心回归冒烟（批 #9 最终门，逐项 PASS/FAIL + 证据）
1. **首启语义**：重放 pm clear 冷启 ⇒ 4173 红点行可见（设置导航+Home）；Add 4095 成功后 4173 行消失、只剩 4095（截图双向）。
2. **终端可用**：进会话 → 终端标签 → 提示符 → `input text "echo ux16_gate_$([datetime]::Now.Ticks)"` 不行就用纯字母串 → 回显。
3. **发消息有回复**：composer 发 `reply with the single word OK` → 等回复（fledge-alpha-free）。
4. **回合常亮**：logcat 抓 flagSet=true → 回复后 flagSet=false + Δ。
5. **后台断 SSE/回前台重建（空闲态）**：HOME（空闲）⇒ pagehide + CLOSE 日志；回前台 ⇒ pageshow + OPEN status=200。
6. **后台回合通知**：发消息→立刻 HOME→等 25s→`dumpsys notification` 本包通知出现→下拉栏截图→**真实点击回会话**。
7. **主题**：Settings 切 dark/light 各截图一次（读 documentElement.dataset.colorScheme）。
8. **键盘不裁切**：会话屏弹软键盘，注入量 composer 底边 ≤ visualViewport.height。

### 2. 流式帧时（遗留项收口）
会话里发 `Count from 1 to 300, comma separated, no spaces` → 流式渲染期间（≥15s）`adb shell dumpsys gfxinfo com.xiufeigo.opencodeshell.web.debug framestats` 采帧（先 `gfxinfo ... reset` 清零）→ 报：总帧数、中位帧时、90 分位帧时、>32ms 帧占比。判读：90 分位 < 32ms ⇒ PASS。这是幻影 XPath 化后的性能实证（对照 P1 之前的 O(子树) 扫描风险）。

### 3. 渲染进程崩溃自愈（真触发，遗留项收口）
1. 页面在正常前台状态（4095 会话列表或 Home）；
2. 找渲染进程：`adb shell ps -A | grep -i webview`（或 `dumpsys activity lru` / `ps -A -o PID,NAME | findstr sandbox`）定位 WebView 的 sandboxed/render 进程 pid；
3. `adb shell kill -9 <renderPid>` → 观察（全部抄原文）：
   - logcat `UX14 render process gone`（含 didCrash 值）；
   - 页面行为：**自动重载**并恢复到可用（官方 Home 重新渲染）⇒ 自愈 PASS；
   - 60s 内**第二次 kill** ⇒ 应落诊断页（showDiagnostic）而非无限重载 ⇒ 节流 PASS；
   - 从诊断页能恢复正常（其重试按钮可用性——只记录，不强求）。
4. 若 kill 后无 onRenderProcessGone 日志（有的 WebView 版本把渲染进程并入主进程）：如实记录"该镜像不可触发"，降级结论为"代码路径绊线在位+逻辑评审通过"，**不许编**。

### 4. 收尾（同前纪律：emu kill 自建、reverse --remove、后端 pid 21136 未变+200、列 serial）

## 汇报（最后一行）
8 项冒烟 逐项 P/F · 帧时中位/90分位/>32ms占比 · 崩溃自愈（重载✅/节流✅/不可触发-原因）· 报告路径 · 碰过的 serial。
