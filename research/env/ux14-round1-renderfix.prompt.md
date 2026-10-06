# 轮次：UX14-P1 —— 接线 onRenderProcessGone（渲染进程崩溃自愈）+ 重建

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
**每步一条命令 + 立即读输出；结论当场写进 `research/web/UX14_P1_RENDER_RECOVERY.md`。**

## 背景（HANDOFF §5 第 11 条未闭环项）

`WebViewPreflight.Reason` 枚举已有 `RENDER_CRASH`，但全模块**没有** `onRenderProcessGone` 实现（先 grep 证实）⇒ WebView 渲染进程崩溃（白屏死的唯一常见成因）当前无任何处理，用户只能杀进程重启。桌面端 Electron 渲染进程崩溃会自动恢复——这是功能一致性缺口。

## 修法（宿主侧，红线干净）

### 1. `WebHostActivity.kt`
在 WebViewClient（找到既有实现处）override：
```kotlin
override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean
```
行为：
1. **记日志**（TAG，含 `detail.didCrash()`/`rendererPriorityAtExit()`）。
2. **崩溃恢复**：`if (::webView.isInitialized)` → 带节流的**重载**：记录崩溃时刻序列，**60 秒内第 1 次崩溃 ⇒ `webView.reload()`**（页面从 4173 重新加载，会话/服务器状态都在 localStorage，恢复后与桌面端一致）；**60 秒内再次崩溃 ⇒ 不再 reload，落到既有 `showDiagnostic`**（把 `WebViewPreflight.Reason.RENDER_CRASH` 传进去——这正好把那个走不到的枚举分支接线；读既有 `showDiagnostic` 签名对齐）。
3. return **true**（消费事件，阻止 WebView 默认把整个 Activity finish 掉）。
4. 节流计数器用两个 Long/Int 字段即可（如 `lastRenderCrashAtMs`、`renderCrashCount`），注释写明防重启风暴的理由。
注意：只动 WebHostClient 相关位置 + 两个计数字段；不碰凭据/代理/注入件。

### 2. 绊线
新增 `RenderProcessRecoveryTest.kt`（源码级断言，模式同既有 tripwire）：
1. `onRenderProcessGoneIsOverriddenAndConsumed`：存在 override 且 `return true`（或等值消费证据）；
2. `firstCrashReloadsWithThrottle`：源码含 `reload()` 且含 60 秒节流窗口逻辑；
3. `crashLoopFallsBackToDiagnostic`：源码含再次崩溃走 `showDiagnostic` 且引用 `RENDER_CRASH`；
4. `defaultFinishIsPrevented`：注释/结构证明未调用 super（消费语义）。
反向验证：外科撤除 override ⇒ 新增测试全红 ⇒ 恢复全绿。抄原文。

### 3. 测试 + 重建 + 落盘
锁协议（JAVA_HOME=`C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`；内联 ANDROID_HOME；**长编译必须 Start-Process 脱离会话 + 轮询 test-results**，mcode 会话结束会回收前台子进程）。`:webapp:testDebugUnitTest` 全绿（预期 226+N）；assemble 三件套；覆盖 `research/deliver/`。包内自查：ui=1613、oc-host.js md5 保持 `232E916B5B16E99A6D94C3C4561A9B8A`（本轮不该变，变了说明误改，停下报告）；凭据扫描 0 命中。

## 🔴 红线
不改 `ui/`、不改 `app/**`、不改五个注入件；不 commit；4095 后端（pid 12748）只读不许起停；本轮不起模拟器；失败停下写报告。

## 汇报（最后一行）
改点 · 用例数/反向验证红条 · 三份产物 体积/md5 + oc-host.js md5 是否保持 · 锁已释放 · 报告路径。
