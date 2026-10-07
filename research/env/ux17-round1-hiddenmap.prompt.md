# 轮次：UX17-P1 —— 幻影清除 v2（注册表 hidden-map，替代 DOM 隐藏）+ T6 通知回归定位修复

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
**每步一条命令 + 立即读输出；结论当场写进 `research/web/UX17_P1_HIDDENMAP.md`。**

## 背景（已钉死，直接采信）

- UX16-P3 冒烟：**写 `hidden['http://127.0.0.1:4173']=true` 进注册表**（`localStorage['opencode.global.dat:server']` 的 `hidden` 对象）后，builtin 从可见列表消失、Home 上下文**自动聚焦到用户服务器**（4095）、应用立刻可用——比 DOM 隐藏干净（上游机制、全界面一次生效、白送自动切上下文）。`opencode.activeServer` 是误诊（bundle 里是内存路由方法，非存储键）。
- 既有 DOM 隐藏例程（`data-oc-phantom`+XPath+MutationObserver）因此**整体退役**——删除该例程及相关绊线条款，换成 hidden-map 写入。
- 保留 P2 定下的首启语义：**注册表键不存在（=用户还没加过服务器）时不做任何事**（builtin 行可见=官方 onboarding：Edit 可改成自己的服务器）；注册表一旦存在 ⇒ 写入 `hidden[location.origin 去 scheme? 用完整 origin 字符串]=true`（键格式以注册表里既有的 key 形态为准——先读 bundle 里 `isHidden`/`hidden` 的消费代码或实测确认键是 URL 字符串还是派生 key；P6 冒烟轮写的键是完整 URL 字符串且生效 ⇒ 用它）。
- T6（后台通知）在批 #10 回归：全程走空闲 `pagehide` 分支（`P7 onPause` 日志 0 次），但 T4 常亮 PASS（桥通路活着）⇒ 断点待定位。

## 任务

### 1. `oc-host.js` 改造（只许动这一处产品代码）
- **删除**幻影 DOM 隐藏例程（XPath/lastCompact/observer/CSS 全删）与其注释。
- **新增** `seedPhantomHidden()`：
  ```js
  function seedPhantomHidden() {
    try {
      var raw = localStorage.getItem("opencode.global.dat:server");
      if (!raw) return; // 首启：builtin 可见 = 官方 onboarding（P2 语义）
      var reg = JSON.parse(raw);
      if (!reg || typeof reg !== "object") return;
      if (!reg.hidden || typeof reg.hidden !== "object") reg.hidden = {};
      var origin = location.origin; // http://127.0.0.1:4173
      if (reg.hidden[origin]) return;
      reg.hidden[origin] = true;
      localStorage.setItem("opencode.global.dat:server", JSON.stringify(reg));
    } catch (e) {}
  }
  ```
  在既有的 DOMContentLoaded/load 启动链里调用（选择点读现有代码，注释写明时机理由：必须在官方 hydration 读注册表**之前**生效 ⇒ 若 hydration 早于你的调用点，就改为**每轮传感器扫描时幂等执行**——以设备实测为准，写清选择）。
- 幂等、容错（JSON 损坏不动）、不碰 list/其它键。

### 2. 绊线改造 `PhantomServerHideTest.kt`
- 删除 DOM 方案断言；新增：
  1. `seedWritesHiddenMapEntryForOwnOrigin`：oc-host.js 含 `opencode.global.dat:server` + `hidden` + `location.origin` 写入逻辑；
  2. `seedSkipsWhenRegistryAbsent`：键不存在时早退（首启 builtin 可见语义保留）；
  3. `seedIsIdempotentAndNonDestructive`：幂等（先查 `reg.hidden[origin]`）且不写 list；
  4. `domRoutineRemoved`：不再含 `data-oc-phantom` / `XPathResult` / `MutationObserver`（防回潮）。
- 反向验证：分别撤 1/2/4 ⇒ 真红 ⇒ 恢复全绿。抄原文。

### 3. T6 定位与修复（先定位，证据不够就停）
设备复现 T6 时**全程 logcat**（`oc-pow|setKeepScreenOn|P7 onPause|D1 applyKeepScreenOn|ignored`），逐步：
1. 发消息那一刻应有 `oc-pow keepScreenOn -> true`（传感器乐观置位）；
2. 桥应收到 `setKeepScreenOn(true)`——若出现 `ignored: not in foreground` ⇒ 前台判定字段在某种时序下为 false ⇒ 读 `foreground` 字段维护点修复；
3. HOME 时应出现 `P7 onPause: round active`；
4. 若 1 就没出现 ⇒ 传感器 POST 检测失效（fetch 包装被谁覆盖了？读 oc-host.js 传感器安装点）。
定位后最小修复 + 对应绊线；**修不了就如实记录证据链，不许猜修**。

### 4. 测试 + 重建 + 落盘
锁协议（JAVA_HOME=Microsoft JDK21；内联 ANDROID_HOME；长编译 Start-Process 脱离+轮询）。`:webapp:testDebugUnitTest` 全绿；assemble 三件套；覆盖 `research/deliver/`（**规范名三份**）。包内自查：ui=1613、**oc-host.js md5 会变（登记新值）**、凭据扫描 0 命中。

### 5. 设备复验（emulator-6790 你自建；后端 4095 pid 21136 在跑**不许起停**；reverse 4095）
1. pm clear 冷启 ⇒ 4173 builtin 行**可见**（首启语义）；
2. Add 4095+opencode（等≥14s，绿点）⇒ **不点任何切换器**：判定 ① 4173 从设置导航/Home/下拉**全部消失**；② Home 上下文**自动**落在 4095（副标题/列表直接渲染，**无 Loading 死锁**）；
3. T6 后台通知 E2E：发消息→HOME→25s→dumpsys 通知在→下拉截图→**真点回会话**；
4. 快速冒烟：终端 echo、发消息回复、常亮 Δ、空闲后台断 SSE/回前台重建；
5. 收尾：emu kill、reverse --remove、后端 pid 21136 未变+200、列 serial（只能 emulator-6790）。

## 🔴 红线
不改 `ui/`、不改 Kotlin（除非第 3 步定位到 Kotlin 缺陷且修法明确）、不改 `app/**`；不 commit；不碰他人设备；失败停下写报告。

## 汇报（最后一行）
hidden-map 改点+调用时机选择 · T6 断点与修复（或证据链）· 用例数/反向验证 · 三份产物 体积/md5 + 新 oc-host.js md5 · 设备判定（首启可见/自动切换/通知E2E/冒烟四项）· 锁已释放 · 报告路径。
