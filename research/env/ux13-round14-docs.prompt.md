# 轮次：UX13-Docs —— 最终文档同步（HANDOFF / BUG_QUEUE / 收敛报告）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
本轮**只改文档**（HANDOFF_WEB_SHELL.md、research/web/BUG_QUEUE.md、新建收敛报告），不碰 `webapp/**`、`app/**` 代码字节，不 commit，不碰设备/后端/gradle。

## 已发生事实（全部已有报告，直接采信）

UX13 系列轮次（报告都在 `research/web/`）：
- `UX13_P1_CAPABILITIES.md`：能力复验——相机被拒（根因=可信 origin 无 4173）、通知断链、文件选通、存无入口。
- `UX13_P2_NOTIFY_DIAG.md` / `UX13_P6_NOTIFY_ROOTCAUSE.md`：通知死锁链逐环定位。
- `UX13_P3_CAMERA_FIX.md`：相机可信 origin 加入 4173（反向验证 2 红）；产物批 #2。
- `UX13_P4_CLEARTEXT_GLOBAL.md`：**产品负责人拍板选项 C**——全局明文放行（manifest usesCleartextTraffic=true + network_security_config base-config=true，tripwire 3 条），与官方桌面端同形态；代价=公网 http 口令明文（已知情）。产物批 #3。
- `UX13_P5_VERIFY.md`：相机转绿（GRANTED+真实出画）；通知断点定位。
- `UX13_P7_NOTIFY_FIX.md`：回合感知后台保活（blur 不断流/空闲 pagehide 照旧/回合结束补派收口）+ 首次回合主动申请 POST_NOTIFICATIONS；212 用例。
- `UX13_P9_NOTIFY_PERMISSION_FIX.md`：shim `Notification.permission` 静态属性授权后永不更新 → 修（requestPermission 回写 + oc:notifyPerm 宿主推送）；直接 `new Notification` 投递链路端到端验证通过；219 用例。
- `UX13_P10_BGSTATE_SHIM.md`：bundle 原文钉死上游闸门 `!(granted) || (visible&&hasFocus)` → 后台读数 shim（visibilityState/hasFocus 只改读数零事件派发）；**后台通知端到端首成**（点击回会话通）；222 用例。
- `UX13_P11_PERM_REPUSH.md`：页面每次加载完成按宿主真值重推权限（onPageFinished）；223 用例。
- `UX13_P12_PARITY.md`：B-2 不复现 ✅；D5 复现但收敛为"首帧数据未就绪"（上游固有）；D3 与 V14 一致（上游固有）；重进恢复=上下文+defaultServerUrl ✅、last-route 未恢复（AndroidMemoryRouter 内存 history 架构差异）。
- `UX13_P13_COLD_BG_NOTIFY.md`：`__ocSetBackground(true)` 在 `webView.onPause()` 之后导致 2768ms 竞态 → 纯顺序修复（11ms 翻 hidden）；**冷启后后台通知复验 出** ✅；226 用例。

最终产物批 #5（Get-FileHash 实测值以磁盘为准，三份都在 `research/deliver/`）：
- debug 16,867,075 B（P13 报 SHA256 50F4A668…，md5 以你实测为准）
- nor8 14,306,922 B（SHA256 883AACDC…）
- r8 12,026,852 B（SHA256 594BA305…）
oc-host.js 最终 md5 = `232E916B5B16E99A6D94C3C4561A9B8A`（P10 起稳定）。

## 任务

### 1. HANDOFF_WEB_SHELL.md
- 顶部状态区追加 **UX13 段落**：选项 C 拍板（日期、决策人、代价声明）；相机修复；通知全链路修复（保活/权限/shim/竞态四段）；产物批 #5 表（md5 用你 `Get-FileHash` 实测）；UX12 批及以下全部作废声明更新；`oc-host.js` 最终 md5 口径更新。
- §5 未闭环清单更新：B-2 标"不复现（UX13-P12）"；D5/D3 标"上游固有/维持观察（UX13-P12）"；重进恢复标"部分一致（架构差异，UX13-P12）"；N-4 文件存/取标"选通✅/存无入口（UX13-P1）"；相机扫码标"已修复并复验（UX13-P3/P5）"。
- 新增一节「UX13 收敛结论」：功能/界面对桌面端的差距清单最终归类表（已修复项/上游固有/架构差异/环境受限/用户排除项=启动本机服务）。

### 2. research/web/BUG_QUEUE.md
- B-2 状态：✅ 不复现（UX13-P12，焦点全程在 composer）。
- B-6 保持不变（已修复）。

### 3. 新建 `research/web/UX13_CONVERGENCE.md`（循环收敛报告，给产品负责人）
- 循环目标与范围（用户排除项：启动本机服务）；逐项结论表（每项：结论/证据报告/归类）；
- 红线自检汇总（凭据扫描各轮 0 命中；ui/ 字节未动）；
- 最终产物批 #5 表；
- 遗留事项（诚实清单）：① 你的公网 http 服务器（8.138.191.15:4094 当前 502，修好网关+确认是 19.15 还是 191.15 后，用最新包直接测——选项 C 已放行明文）；② 耗电 mAh 与首屏中位数需真机；③ D5/D3 上游固有维持观察；④ last-route 恢复属架构差异；⑤ 文件"保存"入口上游 UI 未找到可达路径。

### 4. 自查
`git status --porcelain` 只应出现文档 + 既有 webapp 改动；不 commit。

## 汇报（最后一行）
状态 + 改动的文件 + UX13_CONVERGENCE.md 路径 + 是否动过 webapp/app（应为否）。
