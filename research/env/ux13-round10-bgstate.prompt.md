# 轮次：UX13-P10 —— 通知闸门最后一段：后台"未聚焦"页面状态 shim + 端到端收口

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
**每步一条命令 + 立即读输出；结论当场写进 `research/web/UX13_P10_BGSTATE_SHIM.md`。**

## 根因（已从 bundle 原文钉死，直接采信）

`ui/oa/index-D2r3WEwY.js` 上游 notify 闸门原文：
```js
if ((…getPermission().catch(()=>'denied') : Notification.permission) !== 'granted'
    || document.visibilityState === 'visible' && document.hasFocus()) return;
let r = new Notification(...)
```
即：授权已 grarnted（P9 已修）后，还要求 **非（visible ∧ focused）** 才通知。而保活路径下 WebView 后台 `visibilityState` 恒 `'visible'`、`document.hasFocus()` 恒 `true`（合成 blur 事件不改变这两个**属性/方法**）⇒ 闸门永远 return。这就是"上游从未构造 Notification"的最终墙（P9 计数 ctor=0 与此完全吻合）。

## 修法（只许改这两处 + 绊线 + 重建 + 设备复验）

### 1. `webapp/src/main/assets/www/oc-host.js`（注入件，我们的文件）
在 Notification shim 附近新增"后台状态 shim"（注释写明上游闸门原文与动机）：
```js
var ocBackgrounded = false;
try {
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    get: function () { return ocBackgrounded ? "hidden" : "visible"; },
  });
  document.hasFocus = function () { return !ocBackgrounded; };
} catch (e) {}
window.__ocSetBackground = function (v) { ocBackgrounded = !!v; };
```
⚠️ 只覆盖**属性/方法**，**不派发** visibilitychange/blur 事件（P7 已证 visibilitychange 事件会触发上游 resync→abort，把保活的 SSE 掐死）。注释里把这条约束写死。

### 2. `WebHostActivity.kt`
在 onPause（既有 dispatch 逻辑处）与 onResume 里，各加一行对页面 setter 的调用（读现有代码挑位置；保活路径与空闲路径都设，无差别）：
- onPause：`webView.evaluateJavascript("window.__ocSetBackground && window.__ocSetBackground(true)", null)`
- onResume：`... (false) ...`
（evaluateJavascript 在 WebView 未初始化时用 runCatching 包住。）

### 3. 绊线 + 反向验证
- `NotificationShimTest` 追加用例：① oc-host.js 含 `__ocSetBackground` 定义与 `visibilityState` 的 defineProperty；② **不得**出现 `dispatchEvent(new Event("visibilitychange")` 或 `Event("blur"/"focus")` 之外的 visibilitychange 派发（防止未来"顺手修真"把 SSE 掐死）——断言不含 `visibilitychange` 的 dispatch；③ WebHostActivity 对 `__ocSetBackground(true/false)` 各有一处调用。
- 反向验证：外科撤除新增（别整文件回退，P9 已证跨轮回退编译不过）⇒ 新用例真红 ⇒ 恢复全绿。抄原文。

### 4. 测试 + 重建 + 落盘
锁协议（JAVA_HOME=Microsoft JDK21、内联 ANDROID_HOME、长编译 Start-Process 脱离+轮询）。`:webapp:testDebugUnitTest` 全绿（预期 219+N）；assemble 三件套；复制覆盖 `research/deliver/`。包内自查：ui=1613、oc-host.js 新 md5 登记；凭据扫描 0 命中。

### 5. 设备端到端收口（紧凑）
- emulator-6790（查占用）→ reverse → **install -r** → pm clear → am start。
- 连服务器+切上下文+进会话；发消息→弹权限窗→真点允许；再发消息→立刻 HOME→等 25s。
- 判定（抄原文）：`dumpsys notification` 本包通知出现（oc_turns，时间戳>0）+ 下拉通知栏截图 + **真实点击通知回会话**；上游计数（包装 Notification 构造与 requestPermission）ctor≥1。
- 回归顺手：回前台后页面正常（visibility 恢复 visible，composer 可输入）；空闲后台断流仍 PASS。
- 收尾：emu kill、reverse --remove、后端 pid 15096 未变、列 serial。

## 🔴 红线
不改 `ui/`；不改 `app/**`；不 commit；不碰凭据/代理；失败停下写报告。

## 汇报（最后一行）
shim 要点 · 用例数/反向验证红条 · 三份产物 体积/md5 + 新 oc-host.js md5 · 设备判定（通知 出/未出 + ctor 计数 + 点击回会话）· 锁已释放 · 报告路径。
