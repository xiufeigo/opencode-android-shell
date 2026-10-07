# 轮次：UX17-P5 —— 批 #13 T6 定向复验（强制完整日志捕获）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：批 #13 T6 曾 2/4（无上保活日志、无通知），但同 Kotlin+传感器的批 #11 四格全过，变量不明。本轮只做 T6，**强制捕获全部关键日志**（上次缺 oc-pow 与 P7 的原文）。**只验证不改代码。报告 `research/web/UX17_P5_T6_RECHECK.md`。**

## 环境
- 包 `research/deliver/opencode-shell-web.apk`（批 #13，装前核对 md5 `097DA173…`）。
- 后端 `127.0.0.1:4095`（opencode/opencode，**pid 21136**）在跑**不许起停**；`adb reverse tcp:4095 tcp:4095`（收尾 remove）。
- 设备：上轮用的 `emulator-5680`（你**可以**继续用它在跑实例，或自建 6790——注意 AVD 锁；**emulator-5554 禁止写**）。
- 导航（上轮已实证）：Add/切上下文路径见 UX17_P4 报告；抽屉按钮是通栏 `(532,121)`（`(67,120)` 会跳到别的 App）；提交前 `keyevent 4` 收 IME。

## 步骤
### 0. install -r → pm clear → am start → Add 4095（等≥14s）→ 切 4095 → 进会话
### 1. 权限：pm grant 或弹窗允许 POST_NOTIFICATIONS
### 2. **发消息前先起全量 logcat**（`adb logcat -v threadtime` 后台 job 落盘，覆盖整个测试窗口 ≥90s）
发 `Count from 1 to 200 comma separated` → **立刻 HOME**（<1.5s）→ 45s 后回前台。
### 3. 判定（每条都要 logcat 原文行）：
| 格 | 判定依据 |
|---|---|
| A 传感器乐观置位 | logcat 含 `[oc-pow] keepScreenOn -> true busy=[__post__]` |
| B onPause 保活分支 | 含 `P7 onPause: round active -> keep SSE alive`（若无，找 `setKeepScreenOn(true) ignored: not in foreground`——有这行就证明前台判定误伤） |
| C 后台终帧 | 含 `[oc-pow] round end session.execution.succeeded`（succeeded/failed/interrupted 任一算到达，注明哪个） |
| D 通知 | `dumpsys notification` 本包记录 + 下拉截图 + 真点回会话 |
### 4. 若 A 缺：注入读 `window.__ocRoundPowerSensor.installed` 与包装是否存在（只读打点）；若 A 有 B 无：把 onPause 前后 20 行 logcat 原文完整抄进报告。
### 5. 收尾（emu 自建自关；用 5680 则**不 kill**；reverse/forward remove；后端 pid 21136 未变+200；列 serial）

## 汇报（最后一行）
A/B/C/D 四格（每格一行 logcat 原文或"缺失"）· 若失败的最小断点 · 报告路径 · 碰过的 serial。
