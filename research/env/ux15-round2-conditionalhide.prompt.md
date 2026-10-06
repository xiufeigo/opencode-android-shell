# 轮次：UX15-P2 —— 幻影隐藏改条件化（有用户服务器才隐藏）+ 流式帧时补测

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
**每步一条命令 + 立即读输出；结论当场写进 `research/web/UX15_P2_CONDITIONAL_HIDE.md`。**

## 背景（UX15-P1 自查发现，直接采信）

幻影隐藏改 XPath 后性能已修好，但暴露**首启空态缺陷**：全新安装（未加任何服务器）时 builtin 行被隐藏 ⇒ Home 无限期 Loading 骨架屏、无"添加服务器"引导、入口只剩无文字 "+"（截图证据在 UX15_P1 报告）。而首启时那条红点行恰是**唯一可操作的上下文**（P8 实证：Edit 它就能改成自己的服务器 = 官方自带 onboarding 路径）。
产品意图（负责人原话语义）：**加了自己的服务器后，4173 不该还在**——即"有用户服务器才隐藏"。

## 修法（只改 `webapp/src/main/assets/www/oc-host.js` 幻影例程）

把隐藏条件改为：**`localStorage.getItem('opencode.global.dat:server')` 存在（用户已添加过服务器）才执行隐藏**；不存在则完全不动（builtin 行照常显示，可 Edit/可设为默认）。
- 实现注意：注册表键在**首次 Add 成功后才创建**（P5 实证）⇒ 该条件是"是否有用户服务器"的可靠信号；用惰性读取（每次扫描时读，或监听后重读——选简单的：扫描函数开头读一次当次生效）。
- 用户**移除全部**自己服务器后（registry list 空但键仍在）⇒ 键仍在 ⇒ 仍隐藏 ⇒ 4173 不复活（符合"删掉的别再回来"直觉）；注释写明这个取舍。
- 既有防护全保留：XPath 直接文本匹配 / lastCompact 兜底 / 有界上爬 / 紧凑上界 / 不标根 / 排除 input/textarea / 250ms 节流 / 幂等 / CSS 只藏不删。
- 若当前已隐藏（首启未隐藏、后来用户加服务器的时刻）要能**即时生效**：扫描本来就被 MutationObserver 周期性触发，加服务器后 DOM 变更会自然触发下一轮扫描 ⇒ 无需额外事件。注释写明。

## 绊线 `PhantomServerHideTest.kt`
更新/新增断言：
1. 条件存在：例程以 `opencode.global.dat:server` 键存在性作为隐藏闸门（源码级）；
2. 键不存在时**不隐藏**（源码级：条件包裹隐藏调用）。
反向验证：外科撤除条件 ⇒ 新断言真红 ⇒ 恢复全绿。抄原文。

## 测试 + 重建 + 落盘
锁协议（JAVA_HOME=Microsoft JDK21；内联 ANDROID_HOME；长编译 Start-Process 脱离+轮询 test-results）。`:webapp:testDebugUnitTest` 全绿（预期 237+N）；assemble 三件套；覆盖 `research/deliver/`（**规范名三份**，nor8 别写错）。包内自查：ui=1613、新 oc-host.js md5 登记、凭据扫描 0 命中。

## 设备复验（emulator-6790 由你自建；**后端 4095 夹具已在跑 pid 41832，不许起停**；reverse 4095，收尾 remove）
1. install -r → **pm clear** → am start：
   - **首启：4173 行可见**（设置导航 + Home），Home 虽 Loading 但有红点行可点（截图）；
   - 对该行 **Edit** 把地址改成 `http://127.0.0.1:4095`+密码 opencode → 保存（等≥14s）⇒ 这行应变绿点（P8 已证此路径）——顺带验证官方 onboarding 路径仍通；
2. 再加一台新服务器（Settings `+` → Add server → `http://127.0.0.1:4095` 已被占，改用 `http://localhost:4095`+opencode，等≥14s）⇒ 添加成功瞬间/之后：**4173 幻影行消失**（若之前 Edit 过则幻影 URL 仍是 4173——注意区分：被 Edit 过的行已非 4173；本步判定以"URL=4173 的行不存在"为准）；
   ⚠️ 若 Edit 步骤与 Add 步骤逻辑打架（都指向 4095），以 Add 步骤为准、Edit 步骤只截图记录不强制判绿。
3. **流式帧时**：进会话发 `Count from 1 to 200 comma separated`，流式 15s 窗口 `dumpsys gfxinfo <pkg> framestats` 采帧，报中位与 90 分位帧时（ms）+ 掉帧率；绝对判读 90 分位 < 32ms 记 PASS。
4. 收尾：emu kill、reverse --remove、后端 pid 41832 未变+200、列 serial（**只能有 emulator-6790**；`emulator-5554` 是他人设备，**禁止安装/触碰**）。

## 🔴 红线
不改 `ui/`、不改 Kotlin、不改 `app/**`；不 commit；不碰他人设备；失败停下写报告。

## 汇报（最后一行）
条件化改点 · 用例数/反向验证 · 三份产物 体积/md5 + 新 oc-host.js md5 · 首启行可见✅/加服务器后消失✅ · 帧时中位/90分位 · 锁已释放 · 报告路径 · 碰过的 serial。
