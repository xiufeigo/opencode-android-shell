# 轮次：UX13-P9 —— 通知权限状态闭环修复（shim 静态 permission 永不更新）+ 设备复验

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
**每步一条命令 + 立即读输出；结论当场写进 `research/web/UX13_P9_NOTIFY_PERMISSION_FIX.md`。**

## 根因（P8 钉死，直接采信）

P8 实测：POST_NOTIFICATIONS 弹窗✅、后台 blur 保活✅、后台 `round end succeeded`✅——但通知仍 0 条。卡点收敛到我们自己 shim 的缺陷：
- `oc-host.js` 的 `ShimNotification.permission` 硬编码 `"default"`，**Android 侧授权后没有任何路径把它更新成 `"granted"`**；
- `requestPermission()` 只在被**调用**时才查宿主真值（has===1 ⇒ 返回 "granted"）——若上游闸门直接读 `Notification.permission` 属性（先读属性、只有 "default" 才调 requestPermission），就永远卡在 "default"：授权弹窗早已点过，通知永远不放行。
- （P8 探针里 `oc is not defined` 是探针自身作用域看不到 IIFE 闭包的**观测假象**，不是 shim 运行时错误——别再沿这条走。）

## 修法（两处，都在我们的注入件/宿主，红线干净）

### 1. `webapp/src/main/assets/www/oc-host.js`
a) `ShimNotification.requestPermission` 里，查完宿主真值后**同步回写静态属性**：
```js
var has = oc.hasNotificationPermission();
ShimNotification.permission = has === 1 ? "granted" : (has < 0 ? "denied" : "default");
```
（原有返回逻辑不变：has===1 ⇒ resolve("granted")；has<0 ⇒ resolve("denied")；否则触发宿主申请并 resolve("default")。）
b) 在 shim 安装处（`window.Notification = ShimNotification` 附近）新增宿主推送入口的页面侧实现：监听自定义事件 `oc:notifyPerm`（detail = "granted"/"denied"），收到即更新 `ShimNotification.permission`。注释写明：宿主在权限 launcher 回调里经 `evaluateJavascript` 派发该事件。

### 2. `WebHostActivity.kt`（权限 launcher 回调处，P8 报 238/542 行附近）
`POST_NOTIFICATIONS` 申请结果回调里：granted ⇒ `webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('oc:notifyPerm',{detail:'granted'}))", null)`；denied 同理 detail:'denied'。**先读现有回调代码，只加这两行派发**，别的不动。

### 3. 绊线
- 更新/新增 `NotificationShimTest`（或并入既有 bridge 测试文件，二选一）：断言① requestPermission 会回写 `ShimNotification.permission`；② 存在 `oc:notifyPerm` 事件监听与宿主 dispatchEvent 派发（两边源码级）。
- 反向验证：备份→`git show bc87e72:webapp/src/main/assets/www/oc-host.js > webapp/src/main/assets/www/oc-host.js`（Kotlin 同理）→新测试真红→恢复→全绿。抄原文。

### 4. 测试+重建+落盘
- 锁协议（JAVA_HOME=`C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`，内联 ANDROID_HOME；长编译用 Start-Process 脱离会话+轮询 test-results，避免会话回收杀掉 gradle）。
- `:webapp:testDebugUnitTest` 全绿（预期 212+N）；`assembleDebug/Release/ReleaseR8`；复制三份覆盖 `research/deliver/`。
- 包内自查：ui=1613/oa=1590；**新** oc-host.js md5 登记（变了）；凭据扫描 0 命中。

### 5. 设备复验（同轮做，只验通知路径，紧凑执行）
- 起 emulator-6790（先查占用）→ reverse → **install -r（别学 P8 漏装）** → pm clear → am start。
- 连服务器+切上下文+进会话（可达路径见 UX13-P5）；发消息 → 权限弹窗真点允许。
- 第二回合：发消息 → 立刻 HOME → 等 25s。
- 判定（抄原文）：`dumpsys notification` 出现本包通知（oc_turns，mLastNotificationUpdateTimeMs>0）+ 下拉通知栏截图 + `__ocAndroid.postMessage` 桥探测（注入读其存在性与类型，确认 N.postMessage 在）；点击通知回会话。
- 若仍无通知：注入包装 Notification/requestPermission 计数（区分上游是"没读属性"还是"没调构造"），把数据带回复盘，**不许再猜**。
- 收尾：emu kill、reverse --remove、后端 pid 15096 未变+200、列 serial。

## 🔴 红线
不改 `ui/` 官方资源；不改 `app/**`；不 git commit；不碰凭据/代理；失败停下写报告。

## 汇报（最后一行）
shim 修复点 · 用例数/反向验证红条 · 三份产物 体积/md5 + 新 oc-host.js md5 · 设备判定（通知 出/未出 + 若未出 上游卡点计数）· 锁已释放 · 报告路径。
