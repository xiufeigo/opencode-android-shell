# 轮次：UX14-P2 —— 设备轮：last-route 恢复归属查证 + 新架构首屏中位数 n=5

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：批 #6 产物刚重建（render 自愈接线）。本轮只验证不改代码：**①last-route 重进恢复的确切归属（上游固有 vs 可修）②新架构冷启首屏中位数 n=5**（旧首屏数字已随 UX12 批作废）。
**先建报告 `research/web/UX14_P2_ROUTE_AND_BOOT.md`，每步写盘；单条命令 + 立即读输出；真实点击；注入只读打点并标注。**

## 环境
- 包 `research/deliver/opencode-shell-web.apk`（批 #6，装前 Get-FileHash 记录）。
- 后端 `127.0.0.1:4095`（opencode/opencode，v2.0.24，pid 12748）已在跑**不许起停**；`adb reverse tcp:4095 tcp:4095`（收尾 remove）。
- AVD `Medium_Phone_API_37.0`，端口 **6790**（先查占用）。
- 注入 `am start --es oc_debug_eval "b64:..."`（不能 async）。
- 纪律：只关自建 emulator-6790；不 kill-server；不改代码；不碰 gradle。

## 步骤

### 0. 起机装包（install -r → pm clear → am start）→ 连 4095（UI 登记+Default，等≥14s）→ 切上下文

### 1. last-route 恢复归属（决定性诊断）
1. 进会话（某真实会话，如 v2 smoke test）；注入读：`location.pathname` 与 `localStorage.getItem('opencode.pwa.last-route')`（应在路由变化后被上游自己写入——上游 `rL()` 每次路由变化都记）。
2. `am force-stop` → 静置 5s → `am start` → 等 8s。
3. 注入读：`location.pathname`、`localStorage.getItem('opencode.pwa.last-route')`（还在吗？值是什么？）。
4. 归属判定（三选一，抄原文证据）：
   - **A 上游恢复了但没生效**（last-route 有值、pathname 仍 `/`）⇒ 读上游恢复代码可找到的闸门（如会话 tab 未开/服务器未连接时恢复被拒）⇒ 归"上游固有/架构差异"；
   - **B 上游压根没写**（last-route 为 null）⇒ 查 rL() 触发条件（可能仅在 PWA standalone 模式写——`tL()` 里有 standalone 判定！）⇒ 若如此，归"上游固有（web 非 standalone 不写 last-route）"；
   - **C 写了也被恢复逻辑拒绝** ⇒ 记录拒绝条件。
   报告里把上游 `tL()/nL()/rL()` 三个函数的 bundle 原文片段贴出（grep `opencode.pwa.last-route` 于 `ui/oa/index-*.js`），归属结论必须有原文支撑。

### 2. 首屏中位数 n=5（新架构，冷启）
1. 定义口径：冷启 = `am force-stop` + 3s 停顿 + `am start`；计时锚点 = logcat `M.g1.firstContent`（`kind=dcl`）的 `t=` 值（UX 口径沿用）。已鉴权状态（已连 4095，避免把添加服务器时间算进首屏——先保持已连状态，force-stop 不清数据）。
2. 连跑 5 次冷启，每次记录 `t=` 值，列成表，算中位数/极值。
3. 对照旧口径提示：旧 1,676 ms 属已作废 R2 件；本表是**批 #6 + 本机**的新基准，只作新架构记录，不与旧数相减。
4. 每次冷启后确认页面正常渲染（官方 Home 出现）——顺带作为批 #6 的启动冒烟（render 修复没把正常启动弄坏）。

### 3. 收尾（同前纪律）

## 汇报（最后一行）
last-route 归属 A/B/C + 一句话证据 · 首屏 5 次 t= 值 + 中位数 · 批 #6 启动冒烟 PASS/FAIL · 报告路径 · 碰过的 serial。
