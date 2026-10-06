# 轮次：UX14-P3 —— 首启预置默认服务器（选项 A：builtin 条目从 4173 变为真实回环 4095）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
**每步一条命令 + 立即读输出；结论当场写进 `research/web/UX14_P3_DEFAULT_SERVER_SEED.md`。**

## 背景与机制（已钉死，直接采信）

- 官方 web 前端把 `defaultServerUrl（localStorage['opencode.settings.dat:defaultServerUrl']）?? location.origin` 播种为 **builtin 服务器**；builtin 不可移除只可隐藏；全局 sdk 事件流 base = 默认服务器 ?? location.origin（⇒ 不设默认时 4173 每秒空转重连）。
- 产品负责人已拍板**选项 A**：首启把默认服务器预置为 `http://127.0.0.1:4095`（= 宿主常量 `SecretStore.DEFAULT_BACKEND_URL`，老架构开发回环默认值，**不含凭据**）。
- ⚠️ 关键时序：必须抢在**官方 JS 播种之前**写入，否则注册表已把 4173 落库。`addDocumentStartJavaScript` 在文档开始、页面任何脚本之前执行（oc-host.js 已走此通道）⇒ 用它。

## 修法（宿主侧，两处）

### 1. `WebHostActivity.kt`
新增一段 `WebViewCompat.addDocumentStartJavaScript(...)`（放既有 oc-host.js 注入旁），脚本内容**用 Kotlin 字符串模板内联 `SecretStore.DEFAULT_BACKEND_URL`**（禁止写死字面量——单一事实源）：
```js
try {
  if (!localStorage.getItem("opencode.settings.dat:defaultServerUrl")) {
    localStorage.setItem("opencode.settings.dat:defaultServerUrl", "<DEFAULT_BACKEND_URL>");
  }
} catch (e) {}
```
- 只在键**不存在**时写（幂等；用户后来设的默认永不被覆盖）。
- 注释写明：选项 A 决策（产品负责人 2026-10-06）、机制（builtin 播种源）、时序理由（必须早于官方 JS）、以及"不碰凭据/不代理"红线声明。

### 2. 绊线 `DefaultServerSeedTripwireTest.kt`（源码级）
1. `seedUsesHostConstant`：注入脚本引用 `SecretStore.DEFAULT_BACKEND_URL` 且**不含** `http://127.0.0.1:4095` 字面量；
2. `seedRunsBeforePageScripts`：走 `addDocumentStartJavaScript`；
3. `seedIsIdempotentAndGuarded`：含键存在性判断（`getItem`+非才 `setItem`）；
4. `seedDoesNotTouchCredentials`：脚本不含 password/authorization 字样。
反向验证：外科撤除新增注入 ⇒ 新测试全红 ⇒ 恢复全绿。抄原文。

### 3. 测试 + 重建 + 落盘
锁协议（JAVA_HOME=`C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`；内联 ANDROID_HOME；长编译 Start-Process 脱离会话+轮询 test-results）。`:webapp:testDebugUnitTest` 全绿（预期 230+N）；assemble 三件套；覆盖 `research/deliver/`。包内自查：ui=1613、**oc-host.js md5 必须保持 `232E916B5B16E99A6D94C3C4561A9B8A`**（本轮不改注入件；变了=误改，停下报告）、凭据扫描 0 命中。

### 4. 设备复验（同轮，紧凑）
- emulator-6790（查占用）→ reverse → **install -r** → pm clear → am start。
- 判定（注入只读，全抄原文）：
  1. `localStorage.getItem('opencode.settings.dat:defaultServerUrl')` = `http://127.0.0.1:4095`；
  2. 服务器列表里 builtin 条目是 **`127.0.0.1:4095`**（不再是 4173）；4173 不再出现；
  3. 20 秒内 logcat 不再出现 `http://127.0.0.1:4173/api/event` 的每秒重连（OcPerfJS 计数=0 或显著停摆）；
  4. 加 4095+opencode（等≥14s）连上后：服务器列表有 4095 绿点；把你的…（本机夹具即 4095）⇒ 上下文切换正常、列表渲染正常。
- 收尾：emu kill、reverse --remove、后端 pid（现值，先查再记）未变+200、列 serial。

## 🔴 红线
不改 `ui/`、不改 `app/**`、不改 oc-host.js、不 commit、不碰凭据/代理；失败停下写报告。

## 汇报（最后一行）
改点 · 用例数/反向验证红条 · 三份产物 体积/md5 + oc-host.js md5 是否保持 · 设备四项判定（种子值/builtin条目/4173空转停止/连接正常）· 锁已释放 · 报告路径。
