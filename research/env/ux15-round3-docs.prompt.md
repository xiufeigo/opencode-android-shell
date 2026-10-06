# 轮次：UX15-Docs —— 自我回归文档同步（批 #9 / UX15 段 / 5554 事件记录）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
本轮**只改文档**（HANDOFF_WEB_SHELL.md；新建 UX15 自我回归总结），不碰 `webapp/**`、`app/**`，不 commit，不碰设备/后端/gradle。

## 事实（已核实，直接采信）

- `research/web/UX15_P1_SELFREGRESS.md`：自我回归发现 3 项——①幻影扫描性能隐患（全页 textContent 扫描→流式期间卡帧风险）②hasFocus 前台语义被硬编码 true ③首启空态（隐藏 builtin 后无限 Loading 无引导）。修复①（XPath 直接文本匹配+lastCompact 兜底，设备复验当场抓到"行只剩红点名字消失"回归并修）②（后台 false/前台委托原生，设备确认委托生效）；③确认为缺陷转 P2。237 用例全绿。**事件**：该轮误用他人设备 `emulator-5554`（ owner's AVD）装包测试（6790 当时不在线、4095 夹具已被前轮回收尾关闭）——协调方发现后**已立即从 5554 卸载本包恢复原状**（该包原本未安装）；该轮流式帧时因此未测。
- `research/web/UX15_P2_CONDITIONAL_HIDE.md`：③的修复——幻影隐藏改**条件化**：`localStorage['opencode.global.dat:server']` 键存在（=用户已添加/编辑过服务器，P5 证键惰性创建于首次 Add/Edit）才隐藏；首启 builtin 行可见（官方 onboarding：Edit 该行可改成自己的服务器，设备实证红点变绿点）；加服务器后 4173 行消失（双向验证）。239 用例全绿、反向验证 1 红。遗留：流式帧时仍未测到（App 侧上下文切换前提未达成，证据链留报告 §6）；`emulator-5554` 全程零触碰。
- 批 #9 产物（Get-FileHash 实测）：debug 16,830,822 `90E6A6D7…` / nor8 14,312,666 `24D05E1D…` / r8 12,032,512 `D192DD54…`（完整 md5 你实测登记）。**新 oc-host.js md5 = `659BED67B949F1A4466AD3EF3D2D1E06`**。踩线更正：gradle 任务名是 `assembleRelease`（无 assembleReleaseNor8，"nor8" 只是产物文件名）。

## 任务
1. `HANDOFF_WEB_SHELL.md`：顶部状态区追加 **UX15 段落**（三项自查发现+两项修复+首启空态语义变化：首启现在显示 builtin 红点行=官方 onboarding 入口，加自服后 4173 消失；5554 误用事件+已恢复+教训=轮次启动前必须核对自建实例与夹具存活）；§0.1 切批 #9（作废声明更新为"UX14 批及以下全部作废"）；oc-host.js 口径更新；§5.1 归类表挂行（幻影条目=已修复（条件化隐藏）；流式帧时=环境受限未测）。
2. 新建 `research/web/UX15_SELFREGRESS_SUMMARY.md`：自查方法（改动面×风险面矩阵）、逐项结论与证据、5554 事件完整时间线与恢复证据、遗留清单。
3. 自查：不 commit；`git status` 只应出现文档条目。

## 汇报（最后一行）
状态 + 改动文件 + 批 #9 表与实测一致性 + 是否动过 webapp/app（否）。
