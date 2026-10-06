# 轮次：UX14-P6 —— 幻影服务器条目清除（宿主注入层 DOM 级隐藏）+ 重建 + 设备复验

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
**每步一条命令 + 立即读输出；结论当场写进 `research/web/UX14_P6_PHANTOM_HIDE.md`。**

## 机制事实（P4/P5 已钉死，直接采信）

- 服务器注册表 = `localStorage['opencode.global.dat:server']`，首次 Add 成功才惰性创建；`{"list":[…],"hidden":{},…}`。
- `127.0.0.1:4173` 条目 = 上游 **builtin**：来自 `location.origin`（`currentServerUrl`），**不在 list 里** ⇒ 上游 `canRemove` 永不成立、无任何 hide 入口（`hidden` 容器存在但上游不写）⇒ 上游侧永远删不掉。
- 产品负责人明确："这条不应该存在"（2026-10-06 拍板要它从界面消失）。
- ⚠️ 选项 A（预置 defaultServerUrl=4095）**已放弃**：幻影换成 4095 地址也一样删不掉，治标不治本；只在本报告里记录放弃理由。

## 修法（只改 `webapp/src/main/assets/www/oc-host.js`，我们的注入件）

在文件里新增一段「幻影服务器行隐藏」例程（注释写清机制事实与产品决策）：
1. 目标 URL = `location.origin`（即 `http://127.0.0.1:4173`，动态取，不写死）。
2. 注入一条 CSS：`[data-oc-phantom="1"]{display:none!important}`。
3. `hidePhantomRows()`：扫所有元素，**文本内容精确包含目标 URL 字符串**（`127.0.0.1:4173`）的元素，取其最近的行容器祖先（`closest('a,li,[role="button"],[data-slot]')`，若无则自身），打 `data-oc-phantom="1"`；跳过 `input/textarea`（不碰输入框）。
4. `MutationObserver(document.body,{subtree:true,childList:true})` 监听 SPA 重渲染，节流（如 250ms）重复执行；幂等（已标记跳过）。
5. 只在 `document.readyState==='complete'` 后启动，避免阻断首屏。
6. 保守约束：只隐藏、不删除、不改文本——上游数据模型不动，避免破坏 `visible[0]` 焦点逻辑（首启 Home 仍是 4173 上下文 Loading——这是既有行为，不在本轮范围；用户加了自己服务器后切换即可）。

## 绊线
新增 `PhantomServerHideTest.kt`（源码级，读 oc-host.js）：
1. 例程存在且目标为动态 `location.origin`（不得硬编码 `127.0.0.1:4173` 字面量）；
2. 使用 `data-oc-phantom` 标记 + CSS 隐藏，**不删除节点**（不得出现 `.remove()`/`removeChild` 作用在行容器上——允许注释里出现）；
3. MutationObserver 节流存在；
4. 排除 `input/textarea`。
反向验证：外科撤除例程 ⇒ 4 条真红 ⇒ 恢复全绿。抄原文。

## 测试 + 重建 + 落盘
锁协议（JAVA_HOME=Microsoft JDK21；内联 ANDROID_HOME；长编译 Start-Process 脱离会话+轮询 test-results）。`:webapp:testDebugUnitTest` 全绿（预期 230+4=234）；assemble 三件套；覆盖 `research/deliver/`。包内自查：ui=1613；**新 oc-host.js md5 登记（本轮该变）**；dex 内搜不到幻影串的硬编码（`127.0.0.1:4173` 在 classes dex 应 0 命中——例程在 JS 不在 dex，顺带证明没写死）；凭据扫描 0 命中。

## 设备复验（同轮）
- emulator-6790（查占用）→ reverse → **install -r** → pm clear → am start。
- 判定（截图+注入读数）：
  1. 设置左侧导航**无** 127.0.0.1:4173 服务器项；
  2. 首启 Home 上下文切换器/服务器分组里**无** 4173 行；
  3. Add server 对话框正常（占位符/输入不受影响）；
  4. 加 4095+opencode（等≥14s）⇒ 绿点，列表只有 4095；切上下文、进会话正常；
  5. 4173 空转仍在（1Hz 404，桌面端同款行为）如实记录；把 4095 设为默认后空转应停止（记录验证）；
  6. 控制台无 MutationObserver 相关报错（logcat grep `OcWebHost|chromium.*console` 抽查）。
- 收尾：emu kill、reverse --remove、后端 pid 21092 未变+200、列 serial。

## 🔴 红线
不改 `ui/`、不改 Kotlin、不改 `app/**`；不 commit；不碰凭据/代理；失败停下写报告。

## 汇报（最后一行）
例程要点 · 用例数/反向验证红条 · 三份产物 体积/md5 + 新 oc-host.js md5 · 设备六项判定 · 锁已释放 · 报告路径。
