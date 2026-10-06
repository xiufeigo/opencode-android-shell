# 轮次：UX14-Docs —— 文档同步（批 #6 / UX14 段 / N-1 关闭 / 收尾）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
本轮**只改文档**，不碰 `webapp/**`、`app/**` 代码，不 commit，不碰设备/后端/gradle。

## 事实（已核实，直接采信）

1. `research/web/UX14_P1_RENDER_RECOVERY.md`：`onRenderProcessGone` 已接线（60s 节流：首崩 reload、连崩 showDiagnostic(RENDER_CRASH)、return true 消费）；230 用例全绿、反向验证 4 红；**历史更正**：旧文案说"编译期解析不到"是**类名写错**（真名 `RenderProcessGoneDetail`）；产物批 #6：debug 16,800,362 `A71B0F45D796B257B96780EE7AA1C42D` / nor8 14,307,626 `BC3E9027819CF4B7CBB8B37CC0EDAF48` / r8 12,027,472 `B6A346F6E9BE56F2280AECF9B73CE26B`；oc-host.js md5 保持 `232E916B5B16E99A6D94C3C4561A9B8A`；dex 内搜到新增日志串（含 R8）。**诚实缺口**：reload 自愈未做设备触发验证（60s 节流保证失败也不会成风暴）。
2. `research/web/UX14_P2_ROUTE_AND_BOOT.md`：last-route 归属 **B = 上游固有（standalone 闸门）**——写侧 `rL` 与读侧 `nL()` 同受 `tL()`(display-mode:standalone) 短路，WebView 恒 false（三重实测）；路由本身在白名单内、若写键则能恢复；**不改 matchMedia**（那会激活未知 PWA 路径，留产品裁决）。新架构冷启首屏 n=5：**中位 2,344 ms**（2897/2344/2321/2306/2347；kind 4×raf+1×dcl，report() 单次闸门口径）；批 #6 启动冒烟 PASS。

## 任务

### 1. `HANDOFF_WEB_SHELL.md`
- 顶部状态区追加 **UX14 段落**：render 自愈接线（含"旧文案类名写错"的历史更正——这句要写，防后人再被误导）；产物批 #6 表（md5 用你 `Get-FileHash` 实测复核）；UX13 批及以下作废声明更新。
- §5 未闭环清单：第 11 条（onRenderProcessGone）→ **已闭环（UX14-P1）**，注明"reload 路径未设备触发验证、60s 节流兜底"；新增首屏基线行：新架构批 #6 冷启中位 2,344 ms（n=5，本机，不与旧 1,676ms 相减——口径与构件都不同）；重进恢复那条更新：last-route = 上游 standalone 闸门（UX14-P2，归属 B，附"matchMedia 方案留产品裁决"一句）。
- §0.1 表切批 #6。

### 2. `research/web/BUG_QUEUE.md`
- 执行区「执行顺序」表若仍在引用旧状态可不动；N-1（通知门控）如在该文件有对应条目，标"已被 UX13 通知链工作取代（上游闸门逐字执行）"——如该文件没有 N-1 条目就不加，只改 HANDOFF 的 §5 对应行。

### 3. `HANDOFF_WEB_SHELL.md` §5.1 收敛归类表更新：把本轮两项挂进表格（render 自愈=已修复；last-route=上游固有）。

### 4. 自查：`git status --porcelain` 只应出现文档条目；不 commit。

## 汇报（最后一行）
状态 + 改动文件 + 是否动过 webapp/app（否）+ HANDOFF 里批 #6 表是否与你实测 md5 一致。
