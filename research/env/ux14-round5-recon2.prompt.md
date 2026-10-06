# 轮次：UX14-P5 —— 补全侦察：成功添加 4095 后全量存储转储 + 两行菜单对比（只读）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：UX14-P4 因 4095 后端当时未在跑，"添加第二台服务器"失败，存储转储只拿到**单服务器首启态**（localStorage 仅 3 键、无注册表），结论不完整。现在 4095 夹具**已在跑**（opencode/opencode）。本轮把"添加成功后的状态"补齐。**只读，不改代码。报告 `research/web/UX14_P5_REGISTRY_RECON2.md`。**

## 环境
- 包 `research/deliver/opencode-shell-web.apk`（批 #6）；后端 `127.0.0.1:4095` 已在跑**不许起停**（pid 21092）；`adb reverse tcp:4095 tcp:4095`（收尾 remove）。
- AVD `Medium_Phone_API_37.0`，端口 **6790**（先查占用）。
- 大 JSON 用 **CDP**（forward 9333 + Runtime.evaluate），别用 oc_debug_eval（logcat 截断）。
- 纪律：只关自建 emulator-6790；不 kill-server；不改代码；不碰 gradle。

## 步骤

### 0. 起机（install -r → pm clear → am start → 5s）
基线转储（CDP Runtime.evaluate）：localStorage 全量（键+值截断 600 字符）、`indexedDB.databases()`、`caches.keys()`。照抄。

### 1. 添加 4095 成功（真实点击，可达路径见 UX13 各轮）
Settings 头部 `+` → Add server → `http://127.0.0.1:4095` + `opencode` → **等 ≥14 秒** → 提交 ⇒ 绿点。
**添加成功后立刻转储**（同一 CDP 会话）：localStorage 全量、IndexedDB（库/表/行数+样例行截断 600 字符）、caches。
**判定**：第二条服务器落在哪个介质、什么键、什么结构——抄原文样例。

### 2. 两行菜单对比（核心）
两个状态下分别打开服务器行"..."菜单并记录全部菜单项：
1. **4095 行**（非 builtin？）的菜单——有没有「移除/隐藏」？
2. **4173 行**的菜单（已知首启态只有 Edit/Set as default）——加完第二台后有没有变化？
（服务器行的入口：设置→Server 页左侧导航 / Home 上下文切换器——找到真实能点开两行菜单的面，逐步截图。）
判定：canRemove/canHide 的真实渲染条件（builtin? 非默认?）——用实测数据说，不猜。

### 3. （若 4095 行有「移除」）真实点一次移除 4095
记录：移除后存储转储变成什么样（defaultServerUrl 指向谁、列表剩谁）；然后重新加回 4095（等 14s）恢复两服务器态。
⇒ 这一步给出"上游移除语义"的完整实证，供 P6 设计"移除 4173 builtin"的宿主方案时对照。

### 4. 收尾（同前纪律）

## 汇报（最后一行）
第二条服务器存储介质+键+结构样例 · 4095 行菜单项列表 · 4173 行菜单项列表 · 移除实测语义（若做了）· 报告路径 · 碰过的 serial。
