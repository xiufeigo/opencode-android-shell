# 轮次：UX17-Docs —— 最终文档同步（批 #13 / UX16+UX17 全段 / 幻影 saga 结论）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
本轮**只改文档**（HANDOFF_WEB_SHELL.md + 新建 UX16-17 总结），不碰 `webapp/**`、`app/**`，不 commit，不碰设备/后端/gradle。

## 事实（已核实，直接采信）

- `UX16_P1_FINAL_GATE.md`：批 #9 门——7/8 过；发现①render reload 后 6.5s 再崩=自愈端到端失败（节流掩盖）②诊断页中文全 `?`。
- `UX16_P2_CRASH_RECOVERY_V2.md`：自愈改 **WebView 重建式**（setupWebViewAndLoad 抽取，lateinit 可直接重赋值、零波及）；CDP `Page.crash` 实证：重建后零复发、节流落诊断页、中文修复（真因=index.html 被 GBK 往返损坏 156 处，按 HEAD~1 oracle 逐块恢复 85/30/0 猜）。242 全绿。**教训**：旧文案"编译期解析不到"是类名写错。
- `UX16_P3_BATCH10_SMOKE.md`：批 #10 冒烟 7/8；T6 "回归"实为**测试时序**（短回合+HOME 偏晚）。
- `UX17_P1_HIDDENMAP.md` / `UX17_P2_FINAL_VERIFY.md`：幻影隐藏 v3（注册表 hidden-map）端到端**失败**——写入正确但官方 UI 不消费（4173 仍在、不自动切）；T6 长回合四格全过（140ms HOME）；`hidden` 机制结论：4173 是活跃页面 origin，列表过滤不可行。
- `UX17_P3_BATCH12.md`：**v2 DOM 隐藏致命缺陷**——Home 服务器切换器（`home-projects-dropdown`）显示当前服务器名 `127.0.0.1:4173`，被 v2 一并隐藏 ⇒ 加服务器后**自锁死**（切不走、永久 Loading）。回溯解释多轮"导航灵异"。
- `UX17_P4_BATCH13.md`：批 #13（**幻影隐藏整体下线**=ac0bb01 减 phantom 例程）复验：不自锁 ✅、冒烟 4/4 ✅、T6 当轮 2/4（后再证为时序假阴性）。
- `UX17_P5_T6_RECHECK.md`：批 #13 T6 **4/4 PASS**（logcat 原文四行：oc-pow 乐观置位 / P7 保活 / succeeded / oc_turns 通知+点击回会话）；关键变量=Send→HOME 间隔（76ms 假阴 vs 1501ms 真阳，桥线程有百 ms 级时延）。**新用户可达 bug**：全新服务端（无会话）时 UI 无路径切过去（下拉只列 4173；直连路由崩 Something went wrong）——记为遗留 bug。
- 批 #13 产物（Get-FileHash 实测）：debug 16,827,044 `097DA1731F4F4D4FA0A59C78F25D1554` / nor8 14,309,726 `AA2E9D49A675295E6AE06A0C68B18059` / r8 12,029,248 `E0F516ECAEAD8A2747B0651C086E2659`；**oc-host.js md5 = `C058558C423CCF5622DA34419C26254A`**；单测 234/234。
- 幻影 saga 最终结论：**不 ship 任何隐藏**（v1 泛化风险/v2 自锁/v3 不消费，三种实现全实证失败）；正确解=上游级"活跃上下文重指"（或上游让 builtin 可移除），留档。

## 任务
1. `HANDOFF_WEB_SHELL.md`：顶部 UX16+UX17 段（上述全部，尤其幻影 saga 三实现三失败与最终基线决策）；§0.1 切批 #13（作废声明="UX12 批及以下全部作废"——批 #7/#9/#10/#11/#12 全部因隐藏方案下线或重建而作废，写清理由）；oc-host.js 口径 C058558C；§5.1 挂行：render 自愈=✅已修复（重建式，CDP 实证）；幻影 4173=🔺上游固有（隐藏三案全败，安全基线=始终可见）；无会话新服务器切换=🔴 新记用户可达 bug；T6=✅（含 76ms 时序边界说明）。
2. 新建 `research/web/UX16_UX17_SUMMARY.md`：两循环全轮时间线+逐项结论+遗留清单（phantom 需上游、无会话切换 bug、76ms 边界、耗电真机项等）。
3. 自查：不 commit；git status 只应出现文档 + 既有未提交 webapp 改动。

## 汇报（最后一行）
状态 + 改动文件 + 批 #13 表与实测一致性 + 是否动过 webapp/app（否）。
