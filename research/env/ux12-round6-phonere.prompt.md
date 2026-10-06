# 轮次：UX12-PhoneRe —— 手机档补验（切换服务器上下文后重跑被阻塞的 5+1 项）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：`research/web/UX12_PHONE_REGRESSION.md` 的 5.1/5.2/5.5/5.7/5.9 因「服务器上下文未切到 4095」被阻塞（`research/web/UX12_PHONE_DIAGNOSIS.md` 已证：切到 4095 后列表立刻渲染，跨源全程 200，零 CORS 错误）。**你只需补验这 6 项 + 收尾。** 平板档 12 项已全 PASS，无需重做。

**先建报告 `research/web/UX12_PHONE_REVERIFY.md`，每步写盘。每步单条命令 + 立即读输出。真实点击/真实按键；注入只读页面状态并标注；不许修代码。**

## 环境（已核好）

- 包 `research/deliver/opencode-shell-web.apk`（md5 BF688F8A033EDE65C3D4F8C5E8D1E930）；包名 `com.xiufeigo.opencodeshell.web.debug`。
- 后端 `127.0.0.1:4095`（opencode/opencode）已在跑，**不许起停**；只读探测。
- AVD `Medium_Phone_API_37.0`，端口 **6790**（先查占用）。原生几何 1080x2400。
- `adb reverse tcp:4095 tcp:4095`（收尾 remove）。只关自建 emulator-6790；不 kill-server；不碰 gradle；不改 `webapp/**`/`app/**`。

## 步骤

### 0. 起机装包
起 emulator（Start-Process 脱离）→ boot → reverse → install → pm clear → am start → 等 5 秒。

### 1. 连服务器 + **切换上下文（关键）**
真实点击 Add server 填 `http://127.0.0.1:4095` + `opencode`，等 ≥14 秒提交 ⇒ 绿点。
**然后必须切上下文**：在 Home（或抽屉的服务器列表）**真实点击 `127.0.0.1:4095` 那一行**，确认其变为当前上下文（激活态/高亮）。注入读页面状态存证（例如 `document.body.innerText` 里 4095 行的选中态，或 URL 变化）。
预期：项目/会话列表数秒内渲染出会话（参考诊断轮 6 个会话）。截图 `ux12r_list4095.png`。

### 2. 补验 5+1 项（逐项截图 + logcat 原文）

1. **终端可用**：进入一个会话（如 `v2 smoke test`）→ 点「终端」标签 → 等提示符 → `input text` 敲 `echo ux12r_$([datetime]::Now.Ticks)` + Enter → 看到命令与回显。截图 + `OcPerfJS`/PTY 日志。
2. **终端标签条「+」直接新建终端**：点终端条 `+` → 直接出现 `Terminal 2`/「终端 2」，**不弹面板**。前后截图；注入确认无 `[data-oc-panel]`。
3. **发消息有回复**：回会话 composer → `input text "reply with the single word OK"` → 发送 → 等助手回复（`fledge-alpha-free`，通常 2–20 秒）。截图回复。
4. **切会话 / 返回**：在两个会话间切换（真实点击）→ 按返回 → 再进入 → 状态正确。截图。
5. **键盘弹起 composer 两屏不裁切**：
   - 屏 A New session：点输入框弹软键盘 → 注入量 composer 底边 vs `visualViewport.height`（b64）→ 底边 ≤ vv 高度 ⇒ 不裁切；截图；收键盘。
   - 屏 B 会话界面：同样量一遍。记录两组 px。
6. **回合常亮**：第 3 步发消息时同步抓 logcat `D1 applyKeepScreenOn`/`flagSet=true`；回复到达后抓释放日志与时间差。断言置起且按时释放。

### 3. 收尾复核
emu kill 6790；端口释放；reverse --remove；后端 pid 未变 + /api/info 200；列 serial（应只有 emulator-6790）。

## 汇报（最后一行）

6 项逐条 PASS/FAIL + 关键 px/日志原文 + 收尾复核 + 报告路径 + 碰过的 serial。
