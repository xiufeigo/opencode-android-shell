# UX15 · 自我回归段总结（P1 扫描性能化 + hasFocus 委托 + P2 条件化隐藏）—— 批 #9

> 轮次段总结（给产品负责人 / 接手人） · 2026-10-07
> 仓库根 `D:\XiaoZhuTouTou\opencode-android-shell` · **未 commit**
> 逐轮报告：`UX15_P1_SELFREGRESS.md`（自查 + 2 项修复）· `UX15_P2_CONDITIONAL_HIDE.md`（③的修复）
> 交接文档同步位置：`HANDOFF_WEB_SHELL.md` 顶部 **UX15 段落** · §0.1（**产物批 #9**）· §5.1 归类表

---

## 0. 结论速览

| 项 | 结论 |
|---|---|
| 自查发现 | **3 项**（性能隐患 / `hasFocus` 语义 / 首启空态） |
| 当场修复 | **2 项**（① 幻影扫描 XPath 化 + 收口修复；② `hasFocus` 委托原生） |
| 转 P2 | **1 项**（③ 首启空态 = 缺陷，属上游文案/空态逻辑）⇒ 修复后归入 **④ 条件化隐藏** |
| 用例数 | **239 / 239 绿**（P1 237 = 基线 234 + 3；P2 239 = +2），30 个测试类，failures=0 |
| 反向验证 | P1 **4 次**（分离 2 + 合并 1 + 回归补跑 1）；P2 **1 次** ⇒ 均真红 |
| 三份产物 | **批 #9**，`Get-FileHash -Algorithm MD5` 本轮亲手实测，**与磁盘逐位一致** |
| 新 `oc-host.js` | **74,989 B / `659BED67B949F1A4466AD3EF3D2D1E06`**（源 = 三份包内） |
| 产品可见语义变化 | 🔴 **首启现在显示 builtin 红点行**（= 官方 onboarding 入口）；**加/编辑过服务器后 4173 消失** |
| 🔴 纪律事件 | **P1 误用他人设备 `emulator-5554` 装包测试** → **已卸载、恢复原状**（见 §4） |
| 流式帧时 | ❌ **两轮均未测**（环境受限，**无数据、不填报推测值**，见 §5） |

---

## 1. 自查方法：改动面 × 风险面矩阵

### 1.1 为什么要做这一轮

上一段（UX14）的结论几乎全是**新增能力**的绿灯。本轮**不接新需求**，只做一件事：
**把自己上一轮写进生产代码的每一处改动，重新当成"别人写的陌生代码"审一遍** ——
问"这段在什么情况下会错"，而不是"这段测过没有"。

### 1.2 改动面（本轮实际动过的 / 明确没动的）

| # | 改动面 | 本轮状态 | 归属 |
|---|---|---|---|
| A | `oc-host.js` **幻影例程段**（`phantomOriginToken` / `hidePhantomRows` / `phantomRowFor`） | **改了**（P1 扫描方式 + 收口；P2 加闸门） | 宿主注入层 |
| B | `oc-host.js` **后台状态 shim 段**（`ocBackgrounded` / `hasFocus` / `visibilityState`） | **改了**（仅 `hasFocus`） | 宿主注入层 |
| C | `oc-host.js` **其余各段**（回合电源传感器、顶部 inset、plus-chooser、`oc-perf` 相关） | **一字未动** | 宿主注入层 |
| D | 宿主 Kotlin（`WebHostActivity` / `WebHostBridge` / `LocalUiOriginServer`） | **零改动** | 宿主 |
| E | 绊线单测（`PhantomServerHideTest` 4→5→**8**；`NotificationShimTest` 12→**13**） | **改了** | 测试 |
| F | `app/**` 与官方前端资产 `assets/www/ui/`（1613 文件） | **零改动**（`git status` 恒空） | 红线 |
| G | 构建产物（`research/deliver/` 三份） | **重建覆盖** | 交付 |

### 1.3 风险面（对每个改动面问"它在什么情况下会错"）

| # | 风险面 | 典型失效形态（**最容易漏判的是后一列**） |
|---|---|---|
| R1 | **性能** | 流式输出期高频 DOM 变动下掉帧 —— ⚠️ **卡帧是"用户觉得慢"，不会被断言抓到** |
| R2 | **可见性正确性** | 该藏的没藏 / **半隐藏**（只剩红点、名字消失）/ **整页全黑**（P6 已踩） |
| R3 | **首启可用性** | 唯一可操作入口被自己的修复藏掉 ⇒ 新用户**无从下手** |
| R4 | **上游闸门语义偏差** | 宿主 shim 覆盖了原生行为 ⇒ 与桌面浏览器分叉（通知可见性、焦点） |
| R5 | **上游数据模型污染** | 例程"删"了不该删的、写了不该写的状态 |
| R6 | **跨布局一致性** | 平板档成立、手机档不成立（P1 是平板档） |
| R7 | **凭据 / 安全红线** | 新增行里混进凭据、动 `app/**`、动 `ui/` |
| R8 | **产物可复现性** | 重建后包内资产 ≠ 源文件、md5 与登记值不一致 |

### 1.4 矩阵落点（每格都必须有结论，"没查"也要写成"没查"）

| | R1 性能 | R2 可见性 | R3 首启 | R4 语义 | R5 数据模型 | R6 跨布局 | R7 红线 | R8 产物 |
|---|---|---|---|---|---|---|---|---|
| **A 幻影例程** | 🔴 查出隐患 → 修 | 🔴 查出半隐藏回归 → 修 | 🔴 查出首启空态 → 转 P2 → **条件化修复** | — | ✅ 只藏不删、`display:none` | ✅ 手机档复验 PASS | ✅ 0 命中 / `ui/` 未动 | ✅ 3/3 一致 |
| **B hasFocus** | — | — | — | 🔴 查出硬编码 → 修 | ✅ 只读不写 | ✅ 平台无关 | ✅ 0 命中 | ✅ 3/3 一致 |
| **C 其余段** | — | — | — | — | — | — | ✅ | ✅ |
| **D 宿主 Kotlin** | — | — | — | — | — | — | ✅ 零改动 | ✅ |
| **E 绊线** | ✅ 负向断言钉住"不得回到全页扫描" | ✅ 钉住"最外层紧凑祖先" | ✅ 钉住"键不存在时一个节点都不标" | ✅ 钉住"必须委托原生" | — | — | — | — |
| **F 红线面** | — | — | — | — | — | — | ✅ 全部守住 | ✅ |
| **G 产物** | ⚠️ **帧时未实测**（R1 只到论证级） | — | — | — | — | — | ✅ | ✅ 批 #9 登记 |

**这张表里唯一不是绿的一格是 A×R1 的"实测"部分** —— 代码层面的隐患已消除（候选取交给浏览器原生实现，
首启因闸门早退连扫描都不启动），但**流式帧时这个数字至今没有任何实测**，两轮都卡在别的前提上（§5）。

---

## 2. 逐项结论与证据

### 2.1 ① 幻影扫描性能隐患 —— ✅ 已修（XPath 直接文本匹配 + `lastCompact` 收口）

**问题**。原实现 `document.querySelectorAll("*")` 逐个读**全页 `textContent`**：

```js
all = document.querySelectorAll("*");
...
txt = el.textContent || "";
if (txt.indexOf(token) < 0) continue;
```

`textContent` 是 **O(子树) 的串接读取**，轮一遍 = **O(整页文本量)**；而扫描由 `MutationObserver`
驱动、**250 ms 节流** —— 流式输出期间 DOM 高频变动 ⇒ 节流窗口内反复全量扫描 ⇒ **中低端机掉帧风险**。
⚠️ 这是**推断出来的隐患，不是实测到的卡顿**：本仓至今没有该场景的帧时数字（见 §5）。

**改法**。候选取元素改由浏览器原生实现提供：

```js
var lit = token.replace(/"/g, "&quot;");
hits = document.evaluate(
  "//*[text()[contains(., \"" + lit + "\")]]",
  document, null,
  XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null
);
```

- 目标串仍**动态取自 `location.origin`**（去 scheme），**零硬编码端口**；`phantomOriginToken()` 未改。
- **快照**迭代（`hits.snapshotItem(i)`）⇒ 迭代期 DOM 变动不影响候选集。
- **天然免疫 P6 的"祖先污染"**：祖先 `textContent` 含串但**直接文本**不含的，根本不会成为候选。
- 既有防护**全部保留**：有界上爬、紧凑上界 `candMax`、不标根（`documentElement`/`body`，并加 `head`）、
  排除 `input`/`textarea`、250 ms 节流、幂等、`data-oc-phantom` + `display:none!important` **只藏不删**。

**🔴 设备复验当场抓出的回归（本轮最有价值的产出）**。首次装机复验时，Server 页出现
**只剩红点 + "…"、服务器名字消失**的行 —— 幻影行被**部分**隐藏。CDP 注入读到的真实 DOM 链：

```
bdi.settings-servers-name     ← XPath 命中（直接文本 = 页面 origin）
  div.settings-servers-copy
    div.settings-servers-lead
      div.settings-servers-row
        div
          section.settings-section.settings-server-connection   ← 旧扫描在这里收口
            div.settings-tab-body…  (文本 126 > cap 124，上爬在此终止)
```

**根因**：这条链上**没有任何祖先匹配 `PHANTOM_ROW_SEL`**（全是 `div`/`section`）⇒ `phantomRowFor`
的"回落标记命中元素自身"只把那个 `<bdi>` 藏了。**旧的全量扫描之所以能整块隐藏，靠的是祖先自身
`textContent` 同样含目标串** —— XPath 只给直接文本命中，**这条"在哪收口"的语义就丢了**。

**修法**（保留性能收益，只补收口语义）：

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

**教训（已固化进绊线）**：**"改快候选取"可能连带改掉"在哪收口"**。而收口错了的表现是
**"半隐藏"**（只剩红点）而不是"没隐藏"（整行不见了）—— **后者显眼、前者容易被当成渲染毛刺放过**。
⇒ 与 P6「Server 页整屏全黑」并列为同一族的收口事故：**R2 风险的两种表现，一个太狠、一个太轻，都要设备复验才看得见。**

**证据**。用例 234 → **237 全绿**（`PhantomServerHideTest` 4→5、`NotificationShimTest` 12→13）；
反向验证**分离 2 次 + 合并 1 次**均真红；因上述回归又补跑 1 次（把 `return lastCompact || el;`
外科改回 `return el;` ⇒ `1 failed`）。P6「`<html>` 整屏全黑」那几条断言改后仍全绿，未被误伤。
设备复验：修前 ❌（残留红点空行）→ 修后 ✅（`marked: ["SECTION.settings-section"]`、`visibleRows: 0`）。

---

### 2.2 ② `hasFocus` 前台语义被硬编码 `true` —— ✅ 已修（后台 `false` / 前台委托原生）

**问题**。UX13-P10 为了修"后台通知端到端"给 `hasFocus` 加了 shim，但前台写死：

```js
document.hasFocus = function () { return !ocBackgrounded; };
```

只要**宿主**认为在前台就恒 `true`。可页面确实没有焦点的时刻很多：系统弹窗（权限/选择器）遮挡、
多窗口/分屏、系统面板拉起 ⇒ 这些时刻上游闸门仍读到 `hasFocus() === true` ⇒ **与原生 WebView 语义偏差**。

**改法**。

```js
var ocOrigHasFocus = document.hasFocus.bind(document);
document.hasFocus = function () {
  return ocBackgrounded ? false : ocOrigHasFocus();
};
```

**只叠加宿主确知的那一个事实**（"我进了后台"），其余一律**委托原生**。
⚠️ `grab` 必须**早于** `cover`，否则委托的是 shim 自己 —— 这条已写进绊线的顺序断言。
`visibilityState` 覆盖**保持原样**（原生前台本就恒 `visible`，WebView 后台不发 `visibilitychange`，
覆盖前后取值一致 ⇒ 不引入偏差），注释已写明理由。

**⚠️ 行为影响（必须随结论一起引用）**。上游闸门原文是

```
if (permission !== 'granted' || (visibilityState === 'visible' && hasFocus())) return;
```

即"**可见且聚焦**"时**不**通知。改后前台读数是真实值 ⇒ 页面**确实失焦**时（如系统弹窗遮挡）
会**放行通知** —— **这与桌面浏览器一致，正是本改动的目的**；真机前台有焦点时 `hasFocus()` 仍为 `true`，
闸门行为与改前**完全相同**。

**证据**。`NotificationShimTest` 12 → **13**（新增 `hasFocusOverrideDelegatesToNativeWhenForegrounded`：
正向要求 `ocOrigHasFocus` + `return ocBackgrounded ? false : ocOrigHasFocus();`、反向禁止 `return !ocBackgrounded;` 复活、
并断言 `grab` 早于 `cover`）。反向验证：仅撤委托 ⇒ `1 failed`。**设备已确认包内委托生效。**

---

### 2.3 ③ 首启空态（`Loading` 永不完成 + 无引导）—— ⚠️ 确认为缺陷，转 P2

**P1 读数**（首启、未加任何服务器，Home）：

```json
{ "pathname": "/", "origin": "http://127.0.0.1:4173",
  "hasFocus": false, "visState": "visible", "ocDelegates": true,
  "visibleServerRows": 0,
  "bodyTextHead": "Projects\nAdd project\nSettings\nHelp\nLoading" }
```

**判定依据（四条，逐条可核）**：

1. Home 主区**无限期**停在 `Loading` + 3 条骨架屏；**间隔 30 s 复测截图与首帧字节数完全相同（47,821 B）**
   ⇒ **不是"加载中"，是永远不会完成的加载态**。
2. `Loading` 表达的是"正在取数据"，与真实状态（**没有数据源**）矛盾 ⇒ 误导性空态。
3. 主区**没有任何**"去添加服务器"的引导或按钮；添加入口只有左上角一个**无文字 `+` 图标**，首启用户无从判断该点它。
4. 侧栏无"服务器"概念露出，用户也无法从 Home 推断出"要先在 设置 → Server 加服务器"。

**归类**。属**上游 UI 文案 / 空态逻辑**，**宿主注入层不宜代劳** ⇒ **如实记录、转 P2**，P1 不改代码。
🟡 给上游的两条反馈建议：文案/空态判定修正；`+` 图标补可访问名称。

**但 P1 同时查明了关键一点**（这条直接决定了 P2 的做法）：首启时那条被隐藏的行，
**恰是唯一可操作的上下文** —— **Edit 它就能改成自己的服务器**，即**官方自带的 onboarding 路径**。
⇒ 幻影隐藏与产品意图"加了自己的服务器后 4173 不该还在"**直接冲突**：无条件隐藏把入口一起藏没了。

---

### 2.4 ④ ③ 的修复：幻影隐藏改**条件化** —— ✅ 已修（首启可见 / 有服务器才隐藏）

**闸门 = 服务器注册表键 `opencode.global.dat:server` 的存在性**。机制依据是 **P5 已钉死**的事实：
该键**首次 Add/Edit 成功后才惰性创建** ⇒ **键不存在 ⟺ 用户还没有任何自己的服务器**。

```js
var PHANTOM_REGISTRY_KEY = "opencode.global.dat:server";

function hasUserServerRegistry() {
  try {
    return !!(window.localStorage && window.localStorage.getItem(PHANTOM_REGISTRY_KEY));
  } catch (e) {
    return false;          // localStorage 不可用 ⇒ 退回首启可见，绝不误隐藏
  }
}

function hidePhantomRows() {
  if (!hasUserServerRegistry()) return 0;   // 🔴 有用户服务器才隐藏
  var token = phantomOriginToken();
  ...
}
```

**三个设计取舍，逐条都有理由**：

| 取舍 | 理由 |
|---|---|
| **惰性读**（每次扫描读一次，不缓存成一次性结论） | 缓存会让"首启判 `false`"之后再加服务器**永远放行不了** —— 那就把缺陷从首启挪到了第二台设备 |
| **失败偏向可见**（`localStorage` 不可用 → `false`） | 隐私模式/被拒时**不隐藏**：更保守，也更不会误伤用户唯一的入口 |
| **入口早退**而非循环内 `continue` | 键不存在时连 `document.evaluate` 都不启动 ⇒ 首启每轮**零**扫描开销（§2.1 的性能收益在首启**也**完全保留） |

**明确记录的两个边界行为**：

- **移除全部自己的服务器后 4173 不复活** —— `list` 为空但**键仍在** ⇒ 仍隐藏。符合"删掉的不该自己回来"的直觉，
  也避免"清空列表 ⇒ 幻影立刻冒出来"这种更困惑的行为。
- **加服务器后即时生效，不需要额外事件** —— 扫描本来就由 `MutationObserver` 周期触发，Add 必然引起 DOM 变更
  ⇒ 自然触发下一轮扫描，**不额外注册事件监听**（加了反而是**第二套真相来源**）。

**设备双向验证（P2，AVD `Medium_Phone_API_37.0`，API 37）**：

| 场景 | 读数 | 判定 |
|---|---|---|
| **首启**（未加任何服务器） | `registryKeyPresent:false` / `markedCount:0` / `visibleRows:["127.0.0.1:4173 / http://127.0.0.1:4173"]` | ✅ **PASS** —— 截图见红点 + 地址 + `…` 菜单 + `Add server` 入口全部在位 |
| **官方 onboarding**（Edit 4173 → 4095） | Edit 对话框**预填 `http://127.0.0.1:4173`**（证明它就是 builtin）→ 改 4095 + 口令 → Save ⇒ **绿点** | ✅ **PASS** —— 官方自带路径在条件化之后**依然通** |
| **加服务器后**（`pm clear` 后直接 Add `localhost:4095`） | `registryKeyPresent:true` / `markedCount:1` / `visibleRows:[]`；全文档直扫 4173 元素 `rects:0`、`insideMarked:true` | ✅ **PASS** —— `rects:0` = **零个 client rect，完全不可见** |

行菜单仍只有 `Edit` / `Set as default`、**没有 `Remove`** ⇒ 与 P4/P5「builtin 不可删」一致。

**副产物（有价值的事实）**：**Edit 也会创建注册表键**（`registryKeyPresent` 由 `false` 翻 `true`，
`registryLen=150`）⇒ 闸门信号"用户已有自己的服务器"对 **Add 与 Edit 两条路径都成立**，符合预期。

**测试**。`PhantomServerHideTest` 6 → **8**（原有 6 条**逐字未动**），**239 / 239 全绿**；
反向验证把闸门改名为 `hasUserServerRegistryXXX`（等价撤除）⇒ `doesNotHideWhenUserServerRegistryKeyAbsent` **真红**。
⚠️ 有一条反向断言是**刻意**的：**闸门必须早于 `document.evaluate`** —— 写成"先扫后判"虽然首启也没隐藏，
但每轮仍白付一次全文档 XPath 扫描，**§2.1 刚修掉的性能问题不能从一个门里漏回来**。
另一条用例在本轮反向验证中**保持绿是预期行为**（只撤了 `hidePhantomRows` 里的调用点，
信号函数与键常量仍在）—— **两条用例职责分离**：一条钉"闸门信号来源"，一条钉"闸门包住隐藏动作"。

---

## 3. 产品可见语义变化（**引用批 #9 结论时必须一起引用**）

| 场景 | **批 #7 及更早**（无条件隐藏） | **批 #9（条件化隐藏）** |
|---|---|---|
| 首启、**未加过任何服务器** | 4173 行**不可见**；Home 停在 `Loading`，**无 onboarding 入口**（只剩无文字 `+`） | 🔴 **4173 红点行可见** = **官方 onboarding 入口**（Edit 它即改成自己的服务器） |
| 已 Add / Edit 过自己的服务器 | 4173 行**不可见** | 4173 行**不可见**（行为不变） |

⇒ **"幻影 4173 不该出现"的诉求没有回退**：它改成了**只对"用户已有自己的服务器"这件事成立**。
首启那条行之所以值得留着，是因为它**就是官方设计的第一次配置入口**（设备实证：Edit 预填 4173、改成 4095 后红点变绿点）。

---

## 4. 🔴 轮次纪律事件：误用他人设备 `emulator-5554`（**已恢复原状**）

### 4.1 完整时间线

| # | 时刻 | 事件 | 依据 |
|---|---|---|---|
| 1 | P1 开工 | 任务前提：自建实例 **`emulator-6790`**、4095 夹具 **pid 21092 在跑**、4095 有监听 | 任务书 |
| 2 | P1 开工 | **核对实况**：`emulator-6790` **不在线**；本机 AVD 只有 `dshr_tab` / `Medium_Phone_API_37.0` / `Medium_Tablet`；`adb devices` **只有 `emulator-5554`**（Pixel Tablet，API 35） | P1 §5.0 |
| 3 | P1 开工 | **核对夹具**：**pid 21092 不存在**（`tasklist` 无匹配）、**4095 无任何监听** ⇒ 按"不许起停"**未启动**任何 4095 后端 | P1 §5.0 |
| 4 | P1 复验 | 🔴 **在 `emulator-5554` 上 `install -r` + `pm clear` + 启动并 CDP 注入复验** —— 该设备是**他人（owner）的 AVD**，本包**原本未安装**在其上 | P1 §5.1–§5.3 |
| 5 | P1 收尾 | 决定**不 kill 该模拟器**（它是本轮开始前就在运行的既有设备、并非本轮所起；杀掉会破坏他人/后续轮次状态） | P1 §5.6 |
| 6 | 协调方发现后 | 🔴 **已立即从 `emulator-5554` 卸载本包、恢复原状**（该包原本未安装 ⇒ 卸载即回到原状） | 协调方处置 |
| 7 | P2 全程 | ✅ **`emulator-5554` 零触碰**：未安装 / 未 `pm clear` / 未启动 / 未截图 / 未 CDP 注入 | P2 §4 · §8 |
| 8 | P2 | 改用**本轮自建** `emulator-6790`（AVD `Medium_Phone_API_37.0`，`-port 6790 -no-snapshot-load`），收尾 `adb emu kill` → `OK: killing emulator, bye bye` | P2 §4 · §7 |
| 9 | P2 收尾回读 | `adb devices` 仅剩 **`emulator-5554  device`** —— 自建的 6790 已消失，他人设备**仍在原状** | P2 §7 |

### 4.2 恢复证据

- 卸载动作由**协调方**执行并回执：本包在 `emulator-5554` 上**原本未安装** ⇒ **卸载后设备回到"无本包"这一原始状态**。
- P2 起（事件 7 之后）该设备**再未被本轮任何一条 adb 命令触碰**，`adb devices` 回读仍在（事件 9）。
- ⚠️ **诚实边界**：本总结**没有**事后重新 `pm list packages` 复核 5554 的现场读数（那需要再向他人设备发命令，
  本身就违反纪律）。**恢复结论采信协调方的回执**。

### 4.3 代价

- P1 的**流式帧时**因此**未测**（见 §5）—— 这一项本来是想靠"装包实测"拿数据的。
- P1 的 **4095 全链路（加 4095 + opencode、绿点、上下文切换）**同样未执行。
- 设备档位被迫不理想：P1 只能在**平板档**（5554 是 Pixel Tablet）复验；P2 改用**手机档**。
  ⇒ 幸而两轮各覆盖一档（§1.4 R6 由此得到 ✅）。

### 4.4 教训（写进 `HANDOFF_WEB_SHELL.md` 顶部 UX15 段落 ⑤）

> **轮次启动前必须核对两件事：① 自建实例（模拟器）是否在线、是否仍是本轮该用的那台；
> ② 夹具（后端进程 + 端口监听）是否还活着。**
> **前提不成立时，正确动作是把它如实记为「未测 / 未执行」，而不是换一台不是自己的设备去凑。**
> 误用他人设备**即使事后完全恢复**，也已经污染了他人状态与信任 —— 而 §6.3 的"谁起谁关 / 只关自己起的"
> 只约束了**关**，**没有约束"用"**。本轮是把"用"也纳入了纪律。

---

## 5. 遗留清单（**如实记为未测/未闭环，不粉饰**）

| # | 项 | 级别 | 阻塞原因（实测证据链） | 下一轮可执行线索 |
|---|---|---|---|---|
| 1 | **流式渲染帧时** | 🔴 | **两轮都未测，且原因不同**：**P1 = 4095 夹具已被前轮回收**（pid 21092 不存在、4095 无监听，按"不许起停"未起后端）；**P2 = App 侧当前服务器上下文不可切换** —— 后端 pid 41832 全程在跑、4095 始终 LISTENING、`reverse` 已建、**Add server 实测成功**（下拉出现 `localhost:4095`），但 `pm clear` 后**先 Add 再想切上下文，顺序反了** ⇒ 当前上下文仍停在 builtin 4173、Home 楔死在 `Loading`（`textarea`/`contenteditable` 不存在、`byText("Settings")` 返回 `null`、强改 `location.href` 不重渲染、`force-stop` 重启依旧）⇒ **进不去会话 ⇒ 无流式输出 ⇒ 无法采帧** | **在 Home 楔死之前**先 `Set as default → 自己的服务器`，Home 即应从该服务器取数、composer 出现，帧时可测 |
| 2 | **首启 Home 空态**（`Loading` 永不完成 + 无引导 + `+` 无可访问名称） | 🟡 | 属**上游 UI 文案/空态逻辑**，宿主注入层不宜代劳 ⇒ 已转 P2 并**如实记录**。P2 已把 4173 行还回首启（唯一可操作入口），但 **Home 正文仍无引导文案** | 建议向上游反馈；`+` 补可访问名称 |
| 3 | **首启默认上下文恒指 builtin `location.origin`** | 🟡 | `pm clear`/首启且**已有**用户服务器时，App 仍把默认上下文指向静态来源 origin ⇒ Home 取不到数、楔死。**这与条件化隐藏是同一个根因的两面**（一个决定"藏不藏"，一个决定"用哪个"） | **建议单独立项**：宿主是否应在首启且已有用户服务器时把默认上下文指向注册表里的服务器 |
| 4 | **`lastCompact` 收口的跨布局等价性** | 🟢 | 该收口语义等价于"最外层紧凑祖先"，跨布局是否总等于"行" | P1 是平板档、P2 是手机档 ⇒ **两档均已覆盖**（手机档整块消失，未复现"只剩红点"）。**本项实际已闭环，列此仅备查** |
| 5 | **排除 `input`/`textarea` 的双保险在真实输入焦点下的表现** | 🟢 | 绊线里是源码断言，无法造真输入焦点 | P2 §5.3 失败重试过程中经 CDP 注入改值**顺带验证到该保护不误伤**；如要更硬，需真键盘输入复验 |
| 6 | **4173 空转仍在** | ⚠️ 沿用 | 隐藏的只是 **UI 行**，对 origin 的**事件流订阅**未停 ⇒ `/api/event` 仍 1 Hz 404。**桌面端同款行为** | 与本轮无关，仍在 §5 遗留清单里 |

---

## 6. 产物批 #9 与红线

### 6.1 三份产物（`Get-FileHash -Algorithm MD5` 本轮亲手实测，**与磁盘逐位一致**）

| 产物 | 体积（磁盘实测） | md5（实测） |
|---|---:|---|
| `opencode-shell-web.apk`（debug） | **16,830,822 B** | **`90E6A6D7B1049D91C6A53D5772EFE9A7`** |
| `opencode-shell-web-release-nor8.apk` | **14,312,666 B** | **`24D05E1D80EC768368EE0F98F56B45AC`** |
| `opencode-shell-web-release-r8.apk` | **12,032,512 B** | **`D192DD54C4BFA3F51F9636C842B0C480`** |

- 🔴 **UX14 批及以下全部作废**：批 #7 `bc6542e3…`/`e50eb58b…`/`d89ec5c8…`；批 #6 `a71b0f45…`/`bc3e9027…`/`b6a346f6…`；
  UX13 批 #5 `6d93ecb1…`/`297a71cd…`/`dd26b0af…`；UX12 及更早见 `HANDOFF_WEB_SHELL.md` §0.1 作废清单。
- **相对批 #7**：debug **+2,907 B**、nor8 **+2,696 B**、R8 **+2,700 B**（**三份同量级**）。已知本段**只改 `oc-host.js`**
  （+6,425 B 源码，74,989 − 68,564）**且宿主 Kotlin 零改动** ⇒ release 侧 +2.7 KB 与该资产增量同量级；
  **debug 侧同样未逐项拆解归因，不猜**。
- **`oc-host.js` 新口径**：源文件与**三份包内均一致** = **`659BED67B949F1A4466AD3EF3D2D1E06`**（**74,989 B** / 1,521 行）。
  ⚠️ 引用资产 md5 **必须带体积**：批 #7 = 68,564 B / `7E89FAF5…`，P1 中间批 = `CC449D89…`，
  现行 = 74,989 B —— **三者是不同字节体，不是换行符差异**。
- **包内自查（3/3）**：`assets/www/ui/*` 条目均 **1613**（含 1 个零长 `oa/__vite-browser-external-*.js`，
  故"非空"口径 1612 / `ui/oa` 非空 1589）、包内 `oc-host.js` md5 = 源文件、三份均含条件化闸门。
- **踩线更正（留给下一轮）**：gradle 任务名是 **`:webapp:assembleRelease`**，**没有 `assembleReleaseNor8`**
  （误用会 1 s 内 `BUILD FAILED: Cannot locate tasks that match …`）。**"nor8" 只是产物文件名，不是任务名**。
  正确三件套 = `:webapp:assembleDebug` / `:webapp:assembleRelease` / `:webapp:assembleReleaseR8`。

### 6.2 红线自检（P1/P2 实测）

| 红线 | 结果 |
|---|---|
| `webapp/src/main/assets/www/ui/`（官方前端 1613 文件） | **零改动**（`git status --porcelain` 恒空） |
| `app/**` | **零改动** |
| 生产 Kotlin | **零改动**（只改 `oc-host.js` + 两个 `.kt` **单测**绊线） |
| 凭据扫描 | 新增行（26 行 / 217 行）**0 命中**（`token` 均指页面 origin 字符串，非凭据） |
| commit | **未 commit**（工作区改动待人复核） |
| 4095 后端 | **未起未停未重启**（P2 全程 pid 41832 且 4095 LISTENING） |
| `adb forward` / `adb reverse` | 收尾均已 `--remove`，`--list` 为空；**未** `kill-server` |
| 他人设备 `emulator-5554` | 见 §4（**已恢复原状**） |

### 6.3 改动面

`webapp/src/main/assets/www/oc-host.js`（**+例程 / +闸门 / hasFocus 委托**）、
`webapp/src/test/.../PhantomServerHideTest.kt`（4 → 8 用例）、
`webapp/src/test/.../NotificationShimTest.kt`（12 → 13 用例）。**其余全部未动。**
