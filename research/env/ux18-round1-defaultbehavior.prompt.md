# 轮次：UX18-P1 —— 决定性实验：内置条目是否跟随 defaultServerUrl（产品负责人方案的验证）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：产品负责人的新方案——**预置默认服务器 = http://127.0.0.1:4096**，并观察到"设置默认服务器后默认服务器应成为内置条目、4173 随之消失"。该方案成立的前提是上游"内建行 = defaultServerUrl ?? location.origin"且各消费方（列表/切换器/事件流）都跟随。有一条例外线索（早期实测设默认后 4173 事件流空转仍在）需澄清。**只验证不改代码。报告 `research/web/UX18_P1_DEFAULT_BEHAVIOR.md`。**

## 环境
- 包 `research/deliver/opencode-shell-web.apk`（批 #15：16,832,748 / md5 `4865A530…`，装前核对）。
- 后端 `127.0.0.1:4095`（opencode/opencode，**pid 1804**）在跑**不许起停**；`adb reverse tcp:4095 tcp:4095`（收尾 remove）。
- AVD `Medium_Phone_API_37.0`，端口 **6790**（你自建自关；**5554/5680 禁写**）。若 AVD 被占：克隆新 AVD 启动（上轮做法），收尾清理。
- 注入 `am start --es oc_debug_eval "b64:..."`；大 JSON 走 CDP。
- 导航：抽屉通栏 `(532,121)` → Settings → 段选择器 → Server → Add（提交前 BACK 收 IME）。

## 步骤
### 0. install -r → pm clear → am start → 5s
**A0 基线**：记录 Home 切换器当前文本（应为 `127.0.0.1:4173`）、服务器列表行（4173 builtin）、`localStorage['opencode.settings.dat:defaultServerUrl']`（应为 null）、事件流 URL（logcat 里 /api/event 打向谁）。

### 1. Add 4095+opencode（等≥14s，绿点）→ 记录同上三样（列表应两行了）

### 2. **把 4095 设为默认**（真实 UI：设置 → Server 段 → 4095 条目的 "..." → Set as default）
然后 **force-stop → am start（冷启）**，记录：
- A1 内建/首行服务器 = 4173 还是 4095？
- A2 4173 行是否还在列表任何位置（下拉/设置/段菜单）？
- A3 切换器当前文本 = ?
- A4 `defaultServerUrl` 值；
- A5 事件流打向 4095 还是 4173（logcat 20 秒窗口）？

### 3. **预置默认实验（B）**：`pm clear` → 注入（document 早期即可，am start 前不行就用 CDP 尽早执行）：
`localStorage.setItem('opencode.settings.dat:defaultServerUrl','http://127.0.0.1:4096')` → reload（`location.reload()` 或 force-stop+start）→ 记录：
- B1 首行/内建服务器行 = 4096 吗（红/绿？4096 不可达应红）？
- B2 4173 出现了吗（任何地方）？
- B3 切换器文本 = 4096？
- B4 事件流打向 4096 还是 4173？
（注意：注入要在官方 JS 读该键**之前**写入才有效；若 reload 后读数仍是 4173 ⇒ 说明消费发生在 hydration 早于你的注入 ⇒ 如实记录"注入太晚"，并改用"am start 后立刻注入+再 reload"两轮法重试一次。）

### 4. 结论表（给产品负责人的答案）
| 问题 | 答案（是/否/部分+证据） |
|---|---|
| 内置条目跟随 defaultServerUrl？ | |
| 设默认后 4173 从内建行消失？ | |
| 事件流跟随 defaultServerUrl？ | |
| 方案（预置4096+设默认可删4173）是否成立？ | |

### 5. 收尾（emu kill 自建、reverse --remove、后端 pid 1804 未变+200、列 serial）

## 汇报（最后一行）
A0/A1-A5/B1-B4 各一句读数 · 四问结论表 · 方案成立与否+依据 · 报告路径 · 碰过的 serial。
