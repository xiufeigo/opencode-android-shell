# UX15-P1 · 自我回归修复：幻影扫描性能化 + hasFocus 前台语义还原 + 首启空态确认

> 执行 agent 自回归轮次报告 · 2026-10-06
> 仓库根 `D:\XiaoZhuTouTou\opencode-android-shell` · 未 commit

---

## 0. 结论速览

| 项 | 结果 |
|---|---|
| 改点 1 · 幻影扫描 XPath 化 | ✅ 完成（**且设备复验当场抓出并修掉一个由它引入的回归**） |
| 改点 2 · hasFocus 委托原生 | ✅ 完成，设备实测确认包内委托生效 |
| 用例数 | **237 / 237 绿**（基线 234 + 新增 3），failures=0 |
| 反向验证 | ✅ 分离 2 次 + 合并 1 次，对应测试真红 |
| 三份产物 | ✅ 重建并覆盖 `research/deliver/`，包内自查 3/3 通过 |
| 凭据扫描 | ✅ 改动行 **0 命中** |
| 首启空态 | ⚠️ **有歧义**（见 §5.2，未改代码，如实记录） |
| 幻影仍隐藏 | ✅ 整块消失，设备实测 |
| 流式帧时 | ❌ **未测**（4095 夹具不在，见 §5.5） |
| 设备 | ⚠️ 实机为 `emulator-5554`，**非** 指定的 `emulator-6790`（见 §5.0） |

---

## 1. 改点 1 · 幻影隐藏例程：XPath 直接文本匹配

### 1.1 问题

原实现逐个读全页元素的 `textContent`：

```js
all = document.querySelectorAll("*");
...
txt = el.textContent || "";
if (txt.indexOf(token) < 0) continue;
```

`textContent` 是 O(子树) 的串接读取，把全页元素轮一遍 = **O(整页文本量)**；流式输出期间
DOM 高频变动，250ms 节流窗口内反复全量扫描 ⇒ 中低端机掉帧风险。

### 1.2 改法

候选取元素改由浏览器原生实现提供：

```js
var lit = token.replace(/"/g, "&quot;");
hits = document.evaluate(
  "//*[text()[contains(., \"" + lit + "\")]]",
  document,
  null,
  XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,
  null,
);
```

- 目标串仍**动态取自 `location.origin`**（去 scheme），**零硬编码**；`phantomOriginToken()` 未改。
- 引号转义：目标串只含 `host:port`（`.` `:` `-` 数字），与双引号无冲突；仍加 `&quot;` 兜底。
- 快照迭代 `hits.snapshotItem(i)`，迭代期 DOM 变动不影响候选集。
- **天然免疫 P6 祖先污染**：只有*直接*文本命中的元素才进入上爬，祖先 `textContent` 含串
  但直接文本不含的，根本不会成为候选。

既有防护**全部保留**：有界上爬（`phantomRowFor`）、紧凑上界（`candMax`）、不标根
（`documentElement`/`body`，并加 `head`）、排除 `input`/`textarea`、250ms 节流、幂等、
`data-oc-phantom` + `display:none!important` **只藏不删**。

### 1.3 🔴 设备复验当场抓出的回归（本轮最有价值的产出）

首次装机复验时，Server 页出现**只剩红点 + "…"、服务器名字消失**的行 —— 幻影行被**部分**隐藏。

CDP 注入读到的真实 DOM 链：

```
bdi.settings-servers-name     ← XPath 命中（直接文本 = 页面 origin）
  div.settings-servers-copy
    div.settings-servers-lead
      div.settings-servers-row
        div
          section.settings-section.settings-server-connection   ← 旧扫描在这里收口
            div.settings-tab-body…  (文本 126 > cap 124，上爬在此终止)
```

根因：这条链上**没有任何祖先匹配 `PHANTOM_ROW_SEL`**（全是 `div`/`section`），于是
`phantomRowFor` 的"回落标记命中元素自身"只把那个 `<bdi>` 藏了。

旧的全量扫描之所以能整块隐藏，是因为**祖先自身 `textContent` 同样含目标串**，
所以 `section` 本身也是候选、并在"父级文本超上限"处收口被标记。XPath 只给直接文本命中，
这条收口语义就丢了。

**修法**（保留 XPath 性能收益，只补回收口语义）：

```js
var lastCompact = null;
while (node && node !== document.body && node !== document.documentElement) {
  if (node === document.head) break;
  if ((node.textContent || "").trim().length > cap) break;
  lastCompact = node;                    // 仍紧凑 ⇒ 当前最外层合法收口点
  if (node.matches && node.matches(PHANTOM_ROW_SEL)) return node;
  node = node.parentElement;
}
return lastCompact || el;                // 找不到行选择器匹配 ⇒ 标记最外层紧凑祖先
```

有界上爬、紧凑上界、不标根全部原样保留；改动只在"回落用谁"。修后实测被标记的是
`SECTION.settings-section`（= 旧行为），整块消失。

---

## 2. 改点 2 · 后台 shim：`hasFocus` 还原前台原生语义

原实现把前台硬编码成 true：

```js
document.hasFocus = function () { return !ocBackgrounded; };
```

只要宿主认为在前台，系统弹窗（权限/选择器）遮挡、多窗口/分屏、系统面板拉起这些
**页面确实没有焦点**的时刻，上游闸门仍读到 `hasFocus() === true` ⇒ 与原生 WebView 语义偏差。

改为：

```js
var ocOrigHasFocus = document.hasFocus.bind(document);
document.hasFocus = function () {
  return ocBackgrounded ? false : ocOrigHasFocus();
};
```

只叠加宿主**确知**的那一个事实（"我进了后台"），其余一律委托原生。
`visibilityState` 覆盖**保持原样**（原生前台本就恒 `visible`，WebView 后台不发
`visibilitychange`，覆盖前后取值一致，不引入偏差）—— 注释已写明。

**行为影响（须记录）**：上游闸门是
`if (permission !== 'granted' || (visibilityState === 'visible' && hasFocus())) return;`
即"可见且聚焦"时**不**通知。现在前台读数是真实值，页面确实失焦（如系统弹窗遮挡）时
会放行通知 —— 这与桌面浏览器一致，是本改动的目的。真机前台有焦点时 `hasFocus()` 为 true，
闸门行为与改前相同。

---

## 3. 绊线更新

### 3.1 `PhantomServerHideTest.kt`（4 → 5 条，其余四条逐字未动）

新增 `candidateScanUsesXpathDirectTextMatchInsteadOfFullPageTextScan`：

- 正向：`document.evaluate` / `XPathResult.ORDERED_NODE_SNAPSHOT_TYPE` / 直接文本谓词形态 /
  `hits.snapshotItem(i)` / 双引号实体转义。
- 反向：不得再出现 `querySelectorAll("*")` 全页枚举、不得再出现 `indexOf(token)` 命中判定。

⚠️ 负向断言走**去注释视图**（新增 `routineNoComments`）：源码注释里允许留"原实现是全量
`textContent` 扫描"的动机说明，否则会被自己的说明文字误伤（首次运行即踩到）。
正向断言仍走原始文本 —— XPath 表达式本身就以 `//` 开头。

⚠️ 另踩一个坑：Kotlin 块注释**可嵌套**，在 KDoc 里写字面量 `//*` 会开启嵌套注释导致
"Unclosed comment"。已改写为不含 `/*` 的表述。

新增 `rowFallbackHidesOutermostCompactAncestorNotJustTheTextNode`（§1.3 回归的绊线）：
钉住 `lastCompact = node`、`return lastCompact || el`、紧凑上界 break、不得越过根/head。

### 3.2 `NotificationShimTest.kt`（12 条）

新增 `hasFocusOverrideDelegatesToNativeWhenForegrounded`：

- 必须先 `var ocOrigHasFocus = document.hasFocus.bind(document);`
- 覆盖体必须是 `return ocBackgrounded ? false : ocOrigHasFocus();`
- 反向：`return !ocBackgrounded;` 不得复活
- 顺序：取原生引用（`grab`）必须早于覆盖点（`cover`），否则委托的是 shim 自己

另把 `hostJsDefinesBackgroundStateShim` 里已过时的断言文案（"必须被覆盖为 `!ocBackgrounded`"）
改为指向新用例，断言本身未动。

### 3.3 反向验证（外科撤除 → 真红 → 恢复全绿）

| 轮次 | 撤除内容 | 结果 |
|---|---|---|
| 1 | 仅撤 XPath 化 | `PhantomServerHideTest > candidateScanUsesXpathDirectTextMatchInsteadOfFullPageTextScan FAILED` — 5 tests completed, **1 failed** |
| 2 | 仅撤 hasFocus 委托 | `NotificationShimTest > hasFocusOverrideDelegatesToNativeWhenForegrounded FAILED` — 12 tests completed, **1 failed** |
| 3 | 两处**合并**撤除 | 上述两条**同时**红 — 17 tests completed, **2 failed** |

红条原文（第 1 轮，XML `message`）：

```
java.lang.AssertionError: 候选取元素必须用 document.evaluate 走 XPath —— 全页 textContent 扫描在流式输出期间是掉帧源（O(整页文本量) × 节流窗口）
```

恢复后全绿。另注：把 `<html>` 标记事故（P6 血泪坑）那几条断言在改后仍全绿，未被误伤。

### 3.4 补跑一轮反向验证（因 §1.3 修复而新增的用例）

`rowFallbackHidesOutermostCompactAncestorNotJustTheTextNode` 上线后，把 `phantomRowFor`
的 `return lastCompact || el;` 外科改回 `return el;`：

```
PhantomServerHideTest > rowFallbackHidesOutermostCompactAncestorNotJustTheTextNode FAILED
6 tests completed, 1 failed
```

⇒ 真红。恢复 `return lastCompact || el;` 后 **237 / 237 绿**，
`oc-host.js` md5 回到 `CC449D89D219423DDA379C89D7F37CCD`（= 三份包内登记值，源与包一致）。

---

## 4. 构建与产物

### 4.1 锁协议

`JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`（Microsoft JDK 21）、
内联 `ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`（`local.properties` 为空）。
长编译按协议 `Start-Process` 脱离 + 轮询 `build.log` / `test-results`。
gradle 进程 pid 40696，**已正常结束**（`BUILD SUCCESSFUL`），无残留构建进程。

### 4.2 测试

`:webapp:testDebugUnitTest` → **237 tests / 0 failures / 0 skipped**（30 个测试类）
= 基线 234 + 新增 3。

### 4.3 三份产物（`research/deliver/`，规范名）

| 文件 | 字节 | MD5 |
|---|---|---|
| `opencode-shell-web.apk`（debug） | 16,829,744 | `36129EBE90C545A88C80C0AC97F9B6F9` |
| `opencode-shell-web-release-nor8.apk` | 14,311,638 | `93BABF17714C9057798E59AB5B809918` |
| `opencode-shell-web-release-r8.apk` | 12,031,480 | `1D04DE27B77A4BB71C139845272AC60E` |

### 4.4 包内自查（3/3 通过）

| APK | `assets/www/ui` 条目 | `ui/oa` | 包内 `oc-host.js` md5 | 含 XPath |
|---|---|---|---|---|
| debug | **1613** | 1590 | 与源一致 | ✅ |
| release-nor8 | **1613** | 1590 | 与源一致 | ✅ |
| release-r8 | **1613** | 1590 | 与源一致 | ✅ |

**新 `oc-host.js` md5 = `CC449D89D219423DDA379C89D7F37CCD`**（三份包内均等于源文件）。

### 4.5 凭据扫描

对 `git diff` 的**新增行**（26 行）扫
`api_key|secret|token[:=]|password|passwd|bearer|ANTHROPIC|OPENAI|sk-|ghp_|AIza|AKIA`
⇒ **0 命中**。新增行里出现的 `token` 均为变量名（`var lit = token.replace(...)`），
指页面 origin 字符串，非凭据。

---

## 5. 设备复验

### 5.0 ⚠️ 环境与任务前提不符（如实记录）

| 任务前提 | 实际 | 处置 |
|---|---|---|
| 设备 `emulator-6790` | **不存在**；本机 AVD 只有 `dshr_tab` / `Medium_Phone_API_37.0` / `Medium_Tablet`，在线设备为 **`emulator-5554`**（Pixel Tablet，API 35） | 在唯一在线设备 `emulator-5554` 上复验，并在结论中标注 |
| 后端夹具 pid 21092 在跑、4095 有监听 | **pid 21092 不存在**（`tasklist` 无匹配），**4095 无任何监听** | 按"不许起停"**未启动**任何 4095 后端 ⇒ §5.4/§5.5 无法执行 |
| `reverse 4095` + 收尾 remove | 从未建立 | 收尾确认 `reverse --list` 为空 |

另：该设备上 `opencodeshell` 原本**未安装**；WebView 调试已开启
（`/proc/net/unix` 有 `webview_devtools_remote_<pid>`），故用 CDP 注入读取 DOM 事实。

### 5.1 安装

`adb install -r opencode-shell-web.apk` → `Success`；`pm clear` → `Success`；
`am start -n com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity`
→ 聚焦正常（`mCurrentFocus` = WebHostActivity，pid 8299）。

### 5.2 首启空态判定：**有歧义**（未改代码）

注入读（首启，未加任何服务器）：

```json
{ "pathname": "/", "origin": "http://127.0.0.1:4173",
  "hasFocus": false, "visState": "visible", "ocDelegates": true,
  "visibleServerRows": 0,
  "bodyTextHead": "Projects\nAdd project\nSettings\nHelp\nLoading" }
```

- `location.pathname` = `/`（Home）
- 页面可见服务器行数 = **0** ✅
- 副标题文本：**Home 无副标题**；正文只有侧栏四项 + `Loading`

**判定：有歧义。** 证据：

1. 未配置任何服务器时，Home 主区**无限期停在 `Loading` + 3 条骨架屏**；
   间隔 30s 复测（`shots/02-first-launch-after-30s.png`，与首帧**字节数完全相同** 47821）
   ⇒ 不是"加载中"，是**永远不会完成的加载态**。
2. 文案 `Loading` 表达的是"正在取数据"，与真实状态（没有数据源）矛盾 ⇒ 误导性空态。
3. 主区**没有任何**"去添加服务器"的引导或按钮；添加入口只有左上角一个**无文字的 `+` 图标**，
   首启用户无从判断该点它。
4. 侧栏无"服务器"概念露出，用户也无法从 Home 推断出"要先在 设置 → Server 加服务器"。

（结论记录即可，本轮不改代码；修正属于上游 UI 文案/空态逻辑，宿主注入层不宜代劳。）

### 5.3 幻影仍隐藏：✅（且是先红后绿）

- 修复前（仅 XPath 化版本）：Server 页 Connection 区**残留一个红点 + "…" 的空行**，名字消失 ⇒ ❌
- 修复后（+ `lastCompact` 收口）：注入读

```json
{ "marked": ["SECTION.settings-section"],
  "visibleRows": 0, "visibleConnectionSection": 0,
  "bodyText": "…Server\nManage server connections\nAdd server\nPreferences\nTerminal shell…" }
```

整块 Connection 区消失，页面文本**不含** 4173 字样 ⇒ ✅ 与 P6 设备验证过的形态一致。

### 5.4 ❌ 流式期间帧时：**未测**（夹具缺失）

"进会话发一条长回复、流式渲染 15s、`dumpsys gfxinfo framestats` 取中位/90 分位"需要
4095 后端有连接可发消息。本机 4095 无监听且按令不许起停 ⇒ **本项未执行，无数据**，
不做任何推测性数值填报。

### 5.5 ❌ 加 4095 + opencode：未执行（同上，前置条件缺失）

绿点、列表只含 4095、切上下文/进会话均**未验证**。

### 5.6 收尾

| 项 | 结果 |
|---|---|
| `adb forward --remove tcp:9222` | ✅ 已移除；`forward --list` 为空 |
| `reverse --list` | ✅ 为空（本轮未建立 4095 reverse） |
| 后端 pid 21092 | ⚠️ **不存在**（任务开始前即已不在），本轮未起停任何 4095 进程 |
| `adb devices` | `emulator-5554  device` |
| 模拟器 | **未 kill** —— 它是本轮开始前就在运行的既有设备、并非本轮所起；杀掉会破坏他人/后续轮次状态，故保留并在此说明 |
| 应用状态 | 保留新包（`com.xiufeigo.opencodeshell.web.debug`），停留在 Server 页 |

---

## 6. 红线核对

- `ui/` **零改动**（`git status --porcelain -- webapp/src/main/assets/www/ui/` 恒空）。
- `app/**` **零改动**。
- 生产 Kotlin **零改动**；只改了两个 `.kt` **单测**绊线（任务书 §2 明确要求）。
- **未 commit**（工作区改动待人复核）。
- 本轮 `git diff --stat`：**3 文件 / +227 / −21**
  （`oc-host.js` +88/−?、`NotificationShimTest.kt` +45/−?、`PhantomServerHideTest.kt` +115/−?）。
  `git status` 仅这 3 个 M + 本报告 1 个未跟踪文件。

---

## 7. 留待下一轮

1. 🔴 **流式帧时与 4095 全链路未验证** —— 需先恢复 4095 夹具与目标设备 `emulator-6790`。
2. 🟡 **首启空态歧义**（§5.2）：`Loading` 永不完成 + 无添加服务器引导。
   建议向上游反馈文案/空态判定；`+` 图标建议补可访问名称。
3. 🟡 上述 `lastCompact` 收口等价于"最外层紧凑祖先"，跨布局是否总等于"行"，
   建议在**手机档**（本次是平板档）再复验一次。
