# 轮次：UX13-P11 —— 通知权限"页面重载即重推"收口（小修复 + 重建）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
**每步一条命令 + 立即读输出；结论当场写进 `research/web/UX13_P11_PERM_REPUSH.md`。**

## 背景（P10 遗留，直接采信）

P10 已端到端打通后台通知，但留下一个缺口：页面**重载**后（如点通知拉起 Activity 重建页面），shim 重装、`Notification.permission` 回落 `"default"`；上游闸门读属性 !==granted ⇒ 直接 return（闸门自身**不会**调 requestPermission——bundle 原文里那个 `await requestPermission` 分支只在"曾经申请过"的内部状态下走）。⇒ 重载后通知静默失效，直到下一次宿主主动推。修法：**每次页面加载完成时，宿主把当前真值重推给页面**。

## 修法（两处小改 + 绊线 + 重建，不做设备复验）

### 1. `WebHostActivity.kt`
找到页面就绪的既有回调通道（insets 投递/`oc:ready` 消费处，先读代码），加一行：
`evaluateJs("window.dispatchEvent(new CustomEvent('oc:notifyPerm',{detail:'" + (if (通知已授权) "granted" else "denied") + "'}))")`
——即复用 P9 的 `oc:notifyPerm` 事件，在每次页面加载完成时按宿主当前真值推一次。若既有通道不方便，也可在 `onPageFinished` 里推（读代码选点，写清理由）。权限真值从既有 `NotifierHost`/compat checker 读（P9 已用过）。

### 2. `oc-host.js`
无需改（P9 的监听器已在）。**确认监听器在重载后自动重装即可**（addDocumentStartJavaScript 每载必跑——在报告里引用注入机制原文说明）。

### 3. 绊线
`NotificationShimTest` 追加 1 用例：WebHostActivity 在页面就绪路径存在对 `oc:notifyPerm` 的重推（源码级断言）。反向验证：外科撤除该新增 ⇒ 真红 ⇒ 恢复全绿。

### 4. 测试 + 重建 + 落盘
锁协议（JAVA_HOME=Microsoft JDK21；长编译 Start-Process 脱离+轮询 test-results）。`:webapp:testDebugUnitTest` 全绿（预期 222+1=223）；assemble 三件套；覆盖 `research/deliver/`。包内自查：ui=1613、新 oc-host.js md5（本轮未改 oc-host.js，应与 P10 的 `232e916b…` 一致——若不一致说明被误改，停下报告）、凭据扫描 0 命中。

## 🔴 红线
不改 `ui/`；不改 `app/**`；不 commit；不碰凭据/代理；不起停 4095 后端；本轮不起模拟器（设备复验并入 P12）；失败停下写报告。

## 汇报（最后一行）
改点 · 用例数/反向验证 · 三份产物 体积/md5 + oc-host.js md5 是否保持 232e916b… · 锁已释放 · 报告路径。
