# UX7 — 手机档四连修（B-3 终端空白 / B-1 终端条+误拦 / B-4 左滑右侧栏 / B-5 IME 裁切）+ Temp·无Git 行号级回答

状态：进行中（骨架，逐步回填）
开始：2026-10-05 21:59（Asia/Shanghai）
架构前提：无代理·官方直连（见 UX6_OFFICIAL_ENTRY.md）。所有验证均在直连架构上进行。

## 计划
1. 现状代码通读（oc-host.js / ImeOcclusion.kt / InsetsHost.kt / PhoneFormDefect.kt）
2. 上游 origin/v2 查证（终端条/审查条/+ 区分、手机档右侧栏入口、composer 键盘跟随、Temp/无Git）
3. 设备对照实验：平板（回归）vs 手机（主攻）
4. 修复 + 绊线 + 反向验证（每条）
5. 回归测试 → 三份产物 → HANDOFF 同步 → 收尾

## 日志（每步写盘）
- 21:59 建报告骨架；读取 BUG_QUEUE.md / UX6_OFFICIAL_ENTRY.md / webapp 源清单。
