# 轮次：UX14-Docs —— 文档同步（批 #7 / UX14 段 / 幻影条目清除记录）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
本轮**只改文档**（HANDOFF_WEB_SHELL.md；研究文件已有），不碰 `webapp/**`、`app/**`，不 commit，不碰设备/后端/gradle。

## 事实（已核实，直接采信）

- `research/web/UX14_P4_REGISTRY_RECON.md` / `UX14_P5_REGISTRY_RECON2.md`：服务器注册表机制完全厘清——`localStorage['opencode.global.dat:server']` 惰性创建；4173 条目=上游 builtin（location.origin 派生，**不在 list**）⇒ 上游 `canRemove` 永不成立、无 hide 入口（`hidden` 容器上游不写）；非 builtin 行（实测 4095）菜单有 Remove；口令明文存 localStorage（上游行为，如实记录）；移除实测语义（一击生效无确认、list 置空键保留、分区通知键成孤儿）。
- `research/web/UX14_P6_PHANTOM_HIDE.md`：产品负责人拍板"4173 不该存在"⇒ **宿主注入层 DOM 级隐藏例程**（目标动态取 `location.origin` 去 scheme；CSS `data-oc-phantom` 只藏不删；MutationObserver 250ms 节流；排除 input/textarea；设备复验当场抓到"Server 页整屏全黑"缺陷并两轮收紧修复——有界上爬+紧凑上界，固化进绊线）。**选项 A（预置 defaultServerUrl=4095）正式放弃**（幻影换地址治标不治本，记录在案）。234 用例全绿、反向验证 4 红。设备六项判定：设置导航/首启 Home/上下文切换器均无 4173 行；Add server 完好；加 4095 后列表只有 4095；**遗留**：4173 空转仍在（1Hz 404，桌面端同款）且"设为默认后停止"未成立（defaultServerUrl=4095 已写但全局流仍打 4173）——如实记为遗留观察。
- 产物批 #7（`Get-FileHash` 实测）：debug 16,827,915 `BC6542E399D8375B99DEE201B61767D9`；nor8 14,309,970 `E50EB58B7C84C7D6D81E26A198186D17`；r8 12,029,812 `D89EC5C8CE78207C7EF9216BB5D5B1E4`。**新 oc-host.js md5 = `7E89FAF5C1BFEFF47A37E04757CE6B0D`（68,564 B）**。包内 ui=1613。

## 任务
1. `HANDOFF_WEB_SHELL.md`：顶部状态区追加 **UX14 段落**（注册表机制结论 + 幻影清除决策与实现 + "Server 页全黑"缺陷修复留档 + 选项 A 放弃理由 + 空转遗留）；§0.1 切批 #7（**作废声明更新为"UX13 批及以下全部作废"**）；oc-host.js 口径更新为新 md5/体积；§5.1 归类表挂新行：幻影 4173 条目=**已修复（宿主隐藏）**、空转=上游固有/遗留观察。
2. 自查：`git status --porcelain` 只应出现 HANDOFF 一条 + UX14 各轮既有产物；不 commit。

## 汇报（最后一行）
状态 + 改动文件 + 批 #7 表与实测 md5 是否一致 + 是否动过 webapp/app（否）。
