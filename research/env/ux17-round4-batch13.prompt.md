# 轮次：UX17-P4 —— 批 #13 安全基线复验（无幻影隐藏：重点验证"加服务器不自锁"）+ 快速冒烟

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：批 #13 把"幻影 4173 隐藏"**整体下线**（v2 会把 Home 服务器切换器一起藏掉造成自锁——批 #12 实证的致命缺陷；v3 hidden-map 上游不消费）。当前基线 = 无隐藏（4173 行始终可见、可切）+ 全部通知/后台/崩溃自愈修复。**只验证不改代码。报告 `research/web/UX17_P4_BATCH13.md`。**

## 环境
- 包 `research/deliver/opencode-shell-web.apk`（批 #13：16,827,044 / md5 `097DA173…`，装前核对）。
- 后端 `127.0.0.1:4095`（opencode/opencode，**pid 21136**）在跑**不许起停**；`adb reverse tcp:4095 tcp:4095`（收尾 remove）。
- AVD `Medium_Phone_API_37.0`，端口 **6790**（你自建自关；**emulator-5554 禁止任何写操作**）。
- 导航剧本（卡住先截图再 ±30px 重试）：抽屉 `(67,120)` → `Settings (179,2324)` → 段选择器 `(868,216)` 选 `Server` → `+ Add server (906,482)`（备选页头 `+ (680,215)`）→ 填 `http://127.0.0.1:4095`+`opencode` → **等≥14s** → 提交 `(907,1652)`。

## 步骤
### 0. install -r → pm clear → am start → 5s
### 1. 核心判死项：Add 4095 后**不自锁**
1. 按剧本 Add ⇒ 绿点；
2. **不点任何切换**，先截图 Home；
3. 判定（关键）：Home 顶部的**服务器切换器按钮可见且可点**（注入读其 display/尺寸 ≠ none/0，或真实点击能弹出下拉）——批 #12 的缺陷就是它被藏；
4. 用它（或抽屉→Settings→Server 段 4095 条目）**切到 4095** ⇒ 列表渲染（6 会话）。
### 2. 快速冒烟（4 项）
- 终端 echo 回显；
- 发消息得回复；
- 空闲后台断 SSE/回前台重建（logcat pagehide/pageshow）；
- 主题 dark 一次（dataset.colorScheme）。
### 3. T6 长回合（最后一验）
进会话 → 确保 POST_NOTIFICATIONS granted（弹窗点允许或 pm grant）→ 发 `Count from 1 to 500 comma separated` → **立刻 HOME(<1.5s)** → 25s：`P7 onPause: round active` 日志 + `dumpsys notification` 有本包通知 + 下拉截图 + 真点回会话。
### 4. 收尾（emu kill 自建、reverse --remove、后端 pid 21136 未变+200、列 serial）

## 汇报（最后一行）
不自锁判定（切换器可见✅/可切✅）· 冒烟四项 P/F · T6 四格 · 报告路径 · 碰过的 serial。
