# 轮次：UX12-Docs —— 同步 HANDOFF_WEB_SHELL.md / BUG_QUEUE.md，并出最终交接摘要

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
本轮只做**文档**（HANDOFF_WEB_SHELL.md、BUG_QUEUE.md、新建 UX12 摘要），**不改 `app/**`、`webapp/**` 任何字节，不碰 gradle/设备/后端**。每步写盘。

## 已发生的事实（全部已核实，直接采信；细节见指定报告）

1. **方案 A 已落地**（UX12-Build，`research/web/UX12_PLAN_A_BUILD.md`）：
   - 官方前端自带：`webapp/src/main/assets/www/ui/` 1613 个文件 / 33.58 MiB（从在跑官方服务端 v2.0.23 的 PWA precache 清单完整取回，字节级校验；本机无 `_upstream`，已在报告中如实说明来源差异）。
   - 本机 http 静态来源端点 `LocalUiOriginServer`（4173，只绑回环、只服务 assets、`/api` 404），`WebHostActivity` 改从 `http://127.0.0.1:4173` 启动页面。
   - 单测：webapp 194/194 全绿（含新增 LocalUiOriginServerTest 10 条）；反向验证撤回真红 3 条。app 6136 用例中 57 条**本机预置环境失败**（缺 gitignore 的设备工件 XML 与 `_upstream` 工作副本；app/** 零改动，与本轮无关）。
   - 三份新产物（`research/deliver/`，Get-FileHash 实测）：
     | 产物 | 体积 | md5 |
     |---|---|---|
     | opencode-shell-web.apk（debug） | 16,796,038 | BF688F8A033EDE65C3D4F8C5E8D1E930 |
     | opencode-shell-web-release-nor8.apk | 14,304,138 | B3F9D303BF557AB5CB02EED7D61D09F0 |
     | opencode-shell-web-release-r8.apk | 12,024,280 | F3D3911AA597122657C81072E51EDEAC |
   - 包内自查：三份 assets/www/ui/* 均 1613 条、oa 均 1590 条、包内 oc-host.js md5 == 源文件（MATCH=True）。
   - 旧体积：5,437,979 / 3,549,507 / 1,268,829（debug/nor8/r8）。
2. **手机档回归**（`research/web/UX12_PHONE_REGRESSION.md` + `research/web/UX12_PHONE_REVERIFY.md` + `research/web/UX12_PHONE_DIAGNOSIS.md`）：
   - 页面 `http://127.0.0.1:4173/`、`protocol=http:` 原文读数；http 服务器同页添加成功（绿点 + API 流量）；红字消失（全程 hasRed:false）；https 走**等价构造**（无受信 https 后端，判据实测 + 设备实测双重支撑，已显著标注）。
   - 12 项回归全 PASS（首轮 7 项 + 补验 6 项；5.4 面板项手机档 N/A 为设计使然）。诊断轮结论：跨源请求全程 200，零 CORS 错误；先前 FAIL 系服务器上下文未切换的测量前提问题，非产品缺陷。
3. **平板档回归**（`research/web/UX12_TABLET_REGRESSION.md`）：12/12 全 PASS（含审查条「+」弹面板、面板打开终端/打开文件、终端、主题、常亮、SSE 等）。键盘项有 caveat（该 AVD WebView 视口不随 IME 收缩，改以不裁切判据验证）。

## 任务

### 1. HANDOFF_WEB_SHELL.md 更新（在文件顶部"本轮状态"区按既有格式追加 UX12 条目）
- 新增 UX12 段落：方案 A 落地（自带官方前端 + 本机 http 来源）；三份新 md5/体积表（**宣告 UX8 批全部作废**）；包内 oc-host.js 自查（注意：本批磁盘 CRLF md5=8A3B3E93…，LF 归一化=b5057679…，与 UX8 批相同字节内容）。
- §0.1「当前可交付」顶表更新为 UX12 批三行。
- 文末「附录：B-6 …未实施的方案 A」：标题改为「已实施的方案 A（UX12）」，保留历史（UX11 曾回退的记录），新增 UX12 实施摘要：资源来源（服务端 dist 取回 1613 文件，等价于构建产物，因本机无 _upstream）、1613 条目、体积对比、双档回归报告路径、https 等价构造声明、app 57 条环境失败说明。
- §0 一句话现状与 §10（B-6）补一句：B-6 已由 UX12 修复。
- 所有引用体积/md5 的地方凡与"当前交付包"相关的旧值（UX8 批）标注作废。

### 2. BUG_QUEUE.md 的 B-6 状态更新
- 状态改为「**已修复（UX12，2026-10-06）**」，附一行修复摘要与四个报告路径（BUILD/PHONE/PHONE_REVERIFY/DIAGNOSIS/TABLET）。
- 不要删历史结论。

### 3. 新建 `research/web/UX12_PLAN_A_FULL.md`（最终摘要，给产品负责人）
包含：一句话结论；验收逐条对照表（7 条验收各自证据与报告路径）；三份产物表；新旧体积对比；红线自检（凭据扫描 0 命中命令+输出、不代理 API 结构证据、无自定义页）；环境偏差清单（本机与原任务书差异：后端夹具 opencode/opencode@4095 由协调方按任务口径重建、无 _upstream 故资源取自在跑服务端 dist、JBR=Java25 改用 Microsoft JDK21、新 debug keystore、app 57 条本机预置失败、手机 AVD 为 API 37/WebView145）；已知 caveat（https 等价构造、平板键盘 caveat、手机 5.4 N/A）；收尾复核结果（协调方负责部分：自建实例全关、端口释放、后端未起停、锁已释放）。

### 4. 自查
- `git status --porcelain`：只应出现文档文件 + 之前轮次留下的 webapp 改动。
- 你不 commit。

## 汇报（最后一行）

状态 + 改了哪几个文件 + UX12_PLAN_A_FULL.md 路径 + 是否动过 webapp/app（应为否）。
