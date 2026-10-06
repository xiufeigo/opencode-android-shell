# 轮次：UX15-P1 —— 自我回归修复：幻影扫描性能化 + hasFocus 前台语义还原 + 首启空态确认

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
**每步一条命令 + 立即读输出；结论当场写进 `research/web/UX15_P1_SELFREGRESS.md`。**

## 背景（自我回归审查发现，直接采信）

1. 🔴 **幻影隐藏例程扫描成本**：现实现遍历全页元素的 `textContent.includes(...)`——textContent 读取 O(子树)，流式输出期间 DOM 高频变更 ⇒ 中低端机卡帧风险。改用 **XPath 直接文本匹配**：`document.evaluate('//*[text()[contains(., "<ORIGIN_NO_SCHEME>")]]', document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null)` 取"直接文本节点含目标串"的元素（浏览器原生优化；且天然防 P6 的祖先污染——只有直接文本命中的元素才会进入上爬）。再沿用 P6 的"有界上爬 + 紧凑上界 + 不标根 + 排除 input/textarea"。ORIGIN 动态取自 `location.origin` 去 scheme，**不做任何硬编码**；注意 XPath 字符串里的引号转义（目标串含 `:` `/` `.`，无双引号冲突时用双引号包）。
2. 🟡 **后台状态 shim 前台语义**：现 `document.hasFocus = function(){ return !ocBackgrounded; }` 把前台硬编码 true。改为：
   ```js
   var ocOrigHasFocus = document.hasFocus.bind(document);
   document.hasFocus = function () { return ocBackgrounded ? false : ocOrigHasFocus(); };
   ```
   visibilityState 覆盖保持原样（原生前台本就恒 visible，WebView 语义一致；注释写明这一点）。

## 任务

### 1. `webapp/src/main/assets/www/oc-host.js` 两处修改（其它一律不动）
- 幻影例程换 XPath 直接文本匹配 + 保留全部既有防护（有界上爬/紧凑上界/不标根/排除输入/节流/幂等/CSS data-oc-phantom 只藏不删）。
- hasFocus 改"后台 false、前台委托原函数"；注释说明动机（还原前台原生语义，防系统弹窗/多窗口场景偏差）。
- 凭据扫描（改动段）0 命中。

### 2. 绊线更新 `PhantomServerHideTest.kt` + `NotificationShimTest.kt`
- Hide 测试：断言使用 `document.evaluate`/`XPathResult`（不得再出现全量 `textContent.includes` 扫描模式——注意既有的 P6 断言别误伤，读现有测试再改）；其余三条保持。
- Shim 测试：新增断言 hasFocus 覆盖**委托原函数**（源码含 `ocOrigHasFocus` 或等值委托证据）且后台分支返回 false。
- 反向验证：外科撤除两处修改（分别与合并）⇒ 对应测试真红 ⇒ 恢复全绿。抄原文。

### 3. 测试 + 重建 + 落盘
锁协议（JAVA_HOME=Microsoft JDK21；内联 ANDROID_HOME；长编译 Start-Process 脱离+轮询 test-results）。`:webapp:testDebugUnitTest` 全绿（预期 234+N）；assemble 三件套；覆盖 `research/deliver/`（**规范名三份**：`opencode-shell-web.apk` / `opencode-shell-web-release-nor8.apk` / `opencode-shell-web-release-r8.apk`——注意 nor8 文件名，别写成 release.apk）。包内自查：ui=1613、新 oc-host.js md5 登记、凭据扫描 0 命中。

### 4. 设备复验（emulator-6790；后端 4095 夹具已在跑 pid 21092，**不许起停**；reverse 4095，收尾 remove）
1. 安装新包（install -r → pm clear → am start）。
2. **首启空态**：未加任何服务器时 Home 的状态截图+注入读（location.pathname、页面可见服务器行数=0？、副标题文本）——判定"空态可理解/有歧义"，如实记录，不改代码。
3. **幻影仍隐藏**：设置导航/Home 无 4173 行 ✅。
4. **流式期间性能抽查**：进会话发一条长回复（如 "Count from 1 to 200, comma separated"），流式渲染 15s 期间 `dumpsys gfxinfo <pkg> framestats` 取帧耗时（中位/90 分位），与**改动前**没法同轮比——改为绝对判读：90 分位帧时 < 32ms 即无掉帧，记录数值。顺带观察 logcat 无 observer 报错。
5. 加 4095+opencode（等≥14s）⇒ 绿点、列表只有 4095、切上下文/进会话正常。
6. 收尾：emu kill、reverse --remove、后端 pid 21092 未变+200、列 serial。

## 🔴 红线
不改 `ui/`、不改 Kotlin、不改 `app/**`；不 commit；失败停下写报告。

## 汇报（最后一行）
两处改点 · 用例数/反向验证红条 · 三份产物 体积/md5 + 新 oc-host.js md5 · 首启空态判定 · 流式帧时中位/90分位 · 幻影隐藏仍成立 · 锁已释放 · 报告路径。
