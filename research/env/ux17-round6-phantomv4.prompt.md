# 轮次：UX17-P6 —— 批 #15 幻影 v4 设备复验（自锁修复是本轮判死项）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：批 #15 = 幻影隐藏 v4（v2 条件化+XPath **+ 切换器排除规则**）。v2 的致命缺陷=把 Home 服务器切换器（`button[data-component=home-projects-dropdown]`，文本=当前服务器名）也藏了导致自锁；v4 用 `row.closest('[data-component=home-projects-dropdown]')` 排除。**只验证不改代码。报告 `research/web/UX17_P6_PHANTOM_V4.md`。**

## 环境
- 包 `research/deliver/opencode-shell-web.apk`（批 #15：16,832,748 / md5 `4865A530…`，装前核对）。
- 后端 `127.0.0.1:4095`（opencode/opencode，**pid 21136**）在跑**不许起停**；`adb reverse tcp:4095 tcp:4095`（收尾 remove）。
- AVD `Medium_Phone_API_37.0`，端口 **6790**（你自建自关；**emulator-5554/5680 禁止写**）。
- 导航：Add 路径见 UX17-P4/P5 报告（抽屉是通栏 `(532,121)`；提交前 BACK 收 IME）。

## 步骤
### 0. install -r → pm clear → am start
### 1. 首启：4173 行可见（闸门关）→ 注入读 `localStorage['opencode.global.dat:server']`=null。
### 2. Add 4095+opencode（等≥14s，绿点）→ **不点切换器**，立刻判定**判死项**：
- ① Home 切换器按钮 `button[data-component=home-projects-dropdown]`：**display≠none 且尺寸>0、文本可见**（注入读 computed style + rect；v2 时它 display:none/0×0）；
- ② 真实 `tap` 切换器（约 540,263）→ 下拉弹出且**只含 4095 一行**（4173 行被藏）；
- ③ 点 4095 → 页头副标题变 4095、6 会话渲染；
- ④ 设置页段下拉：4173 项已消失、只剩 4095；
- ⑤ 切回 4173（下拉里应已无 4173…若下拉只剩 4095 则验证"4173 已从列表面移除"，在设置 Server 页确认 4173 不可达即可）。
### 3. 回归四项：终端 echo · 发消息得回复 · 空闲后台断 SSE/回前台 · 主题 dark。
### 4. 收尾（emu kill 自建、reverse --remove、后端 pid 21136 未变+200、列 serial）

## 汇报（最后一行）
首启可见✅/null✅ · 判死五项 ①切换器可见 ②下拉只4095 ③切换渲染 ④设置页消失 ⑤4173全表面移除 · 回归四项 P/F · 报告路径 · 碰过的 serial。
