# UX15-P2 · 幻影隐藏条件化（有用户服务器才隐藏）+ 流式帧时补测

> 执行 agent 轮次报告 · 2026-10-06/07
> 仓库根 `D:\XiaoZhuTouTou\opencode-android-shell` · **未 commit**

---

## 0. 结论速览

| 项 | 结果 |
|---|---|
| 条件化改点 | ✅ 完成（`oc-host.js` 幻影例程，**只改这一处例程**） |
| 用例数 | **239 / 239 绿**（基线 237 + 新增 2），failures=0，classes=30 |
| 反向验证 | ✅ 外科撤除条件 ⇒ 新断言真红（8 completed / **1 failed**） |
| 三份产物 | ✅ 重建并覆盖 `research/deliver/`，包内自查 3/3 通过 |
| 新 `oc-host.js` md5 | **`659BED67B949F1A4466AD3EF3D2D1E06`**（三份包内均等于源） |
| 凭据扫描 | ✅ 新增 217 行 **0 命中** |
| **首启 4173 行可见** | ✅ **PASS**（设备实测 + 截图） |
| **加服务器后 4173 消失** | ✅ **PASS**（设备实测 + 截图） |
| 官方 onboarding 路径（Edit → 4095） | ✅ **PASS**（红点 → **绿点**） |
| 流式帧时 | ❌ **未测**（前置条件不具备，见 §6，无任何推测性数值） |
| 设备 | `emulator-6790`（本轮自建）；`emulator-5554` **全程未触碰** |
| 锁 | ✅ 已释放（无残留 gradle/emulator 进程） |

---

## 1. 改点 · 隐藏条件化

### 1.1 问题（P1 自查发现，直接采信）

P1 把扫描换成 XPath 后性能修好，却暴露**首启空态缺陷**：全新安装（未加任何服务器）时
builtin 行被隐藏 ⇒ Home 无限期停在 Loading 骨架屏、无"添加服务器"引导、入口只剩一个
无文字的 `+`。而首启时那条红点行恰是**唯一可操作的上下文**（P8 实证：Edit 它就能改成
自己的服务器 = 官方自带 onboarding 路径）。与产品意图"加了自己的服务器后 4173 不该还在"
直接冲突。

### 1.2 改法

闸门 = **服务器注册表键 `opencode.global.dat:server` 的存在性**。机制依据是 P5 已钉死的事实：
该键**首次 Add 成功后才惰性创建** ⇒ 键不存在 ⟺ 用户还没有任何自己的服务器。

新增（`oc-host.js` 例程段）：

```js
var PHANTOM_REGISTRY_KEY = "opencode.global.dat:server";

function hasUserServerRegistry() {
  try {
    return !!(window.localStorage && window.localStorage.getItem(PHANTOM_REGISTRY_KEY));
  } catch (e) {
    return false;          // localStorage 不可用 ⇒ 退回首启可见，绝不误隐藏
  }
}
```

`hidePhantomRows()` **入口早退**：

```js
function hidePhantomRows() {
  if (!hasUserServerRegistry()) return 0;   // 🔴 有用户服务器才隐藏
  var token = phantomOriginToken();
  if (!token) return 0;
  ...
}
```

要点：

- **惰性读取**：每次扫描读一次，当次生效（不做一次性缓存）。若缓存成结论，首启判 false 之后
  再加服务器就再也放行不了。
- **失败偏向可见**：`localStorage` 不可用（隐私模式/被拒）时返回 `false` ⇒ 不隐藏。这是更保守、
  也更不会误伤的一侧。
- **入口早退而非循环内 continue**：键不存在时连 `document.evaluate` 都不启动 ⇒ 首启每轮
  **零**扫描开销（P1 的性能收益在首启也完全保留）。

### 1.3 注释里写明的两个取舍

1. **移除全部自己服务器后 4173 不复活**（按任务要求明确记录）：此时注册表 `list` 为空但
   **键仍在** ⇒ 闸门仍放行 ⇒ 仍隐藏。理由：符合"删掉的不该自己回来"的直觉，也避免
   "清空列表 ⇒ 幻影立刻冒出来"这种更困惑的行为。
2. **加服务器后即时生效不需要额外事件**（按任务要求明确记录）：扫描本来就由
   `MutationObserver` 周期触发；用户添加服务器必然引起 DOM 变更 ⇒ 自然触发下一轮扫描，
   此时闸门已放行。**不额外注册事件监听** —— 加了反而是第二套真相来源。

### 1.4 既有防护全部保留（逐条核对未动）

XPath 直接文本匹配 / `lastCompact` 兜底 / 有界上爬 / 紧凑上界 `candMax` / 不标根
（`documentElement`+`body`+`head`）/ 排除 `input`·`textarea` / 250ms 节流 / 幂等 /
`data-oc-phantom` + `display:none!important` **只藏不删** / 目标动态取自 `location.origin`（零硬编码端口）。

---

## 2. 绊线 `PhantomServerHideTest.kt`（6 → 8 条）

原有 6 条**逐字未动**，新增 2 条：

| 用例 | 钉住什么 |
|---|---|
| `hideIsGatedOnUserServerRegistryKeyPresence` | ① 例程引用 `opencode.global.dat:server`；② 用 `localStorage.getItem(` + `PHANTOM_REGISTRY_KEY` **惰性**读存在性（不得缓存成一次性结论）；③ 存在 `function hasUserServerRegistry`；④ 该函数内 `catch` 必须 `return false`（正则跨行匹配） |
| `doesNotHideWhenUserServerRegistryKeyAbsent` | ① 闸门字面量 `if (!hasUserServerRegistry()) return 0;` 存在；② 在 `hidePhantomRows` 函数体**内**；③ **早于** `document.evaluate`；④ 反向：闸门不得落在 `for (var i = 0; i < m; i++)` 之后 |

第 ④ 条反向断言是刻意加的：闸门若写成"先扫后判"，首启虽然没隐藏，但每轮仍白付一次全文档
XPath 扫描 —— 那是 P1 刚修掉的性能问题，不能从一个门里漏回来。

### 2.1 反向验证（外科撤除 → 真红 → 恢复全绿）

把 `if (!hasUserServerRegistry()) return 0;` 改名为 `hasUserServerRegistryXXX`（等价于撤除条件）：

```
> Task :webapp:testDebugUnitTest FAILED
PhantomServerHideTest > doesNotHideWhenUserServerRegistryKeyAbsent FAILED
8 tests completed, 1 failed
BUILD FAILED
```

红条原文（XML `message`）：

```
java.lang.AssertionError: hidePhantomRows 入口必须有 hasUserServerRegistry 闸门：
注册表键不存在（首启，未加过任何服务器）时必须**完全不动**，
否则首启用户失去唯一可操作的 onboarding 入口（Edit 4173 行改地址）
```

（控制台为 GBK 码页，报告按 UTF-8 原文转录。）恢复后 **239 / 239 绿**，
`oc-host.js` md5 回到 `659BED67B949F1A4466AD3EF3D2D1E06`。

注：`hideIsGatedOnUserServerRegistryKeyPresence` 在本轮反向验证中**保持绿** —— 因为我只撤了
`hidePhantomRows` 里的调用点，`hasUserServerRegistry` 函数与键常量仍在。这是预期行为：
两条用例职责分离（一条钉"闸门信号来源"，一条钉"闸门包住隐藏动作"），撤除动作只应打红后者。

---

## 3. 构建与产物

### 3.1 锁协议

`JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`（Microsoft JDK 21）、
内联 `ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`。长编译按协议 `Start-Process` 脱离 + 轮询
`build.log` / `test-results`。

> ⚠️ **踩线记录（留给下一轮）**：任务书写 assemble 三件套，但 gradle 任务名是
> **`:webapp:assembleRelease`**，**没有** `assembleReleaseNor8`（首次执行 1s 即
> `BUILD FAILED: Cannot locate tasks that match ':webapp:assembleReleaseNor8'`）。
> "nor8" 只是**产物文件名**，不是任务名。正确三件套：
> `:webapp:assembleDebug` / `:webapp:assembleRelease` / `:webapp:assembleReleaseR8`。
> 另：`Remove-Item -Recurse` 清理 test-results 会被权限网关拦（不可恢复删除），无需清理 ——
> gradle 本就覆写。

### 3.2 测试

`:webapp:testDebugUnitTest` → **239 tests / 0 failures / 0 skipped**（30 个测试类）
= P1 基线 237 + 本轮新增 2。

### 3.3 三份产物（`research/deliver/`，规范名）

| 文件 | 字节 | MD5 |
|---|---|---|
| `opencode-shell-web.apk`（debug） | 16,830,822 | `90E6A6D7B1049D91C6A53D5772EFE9A7` |
| `opencode-shell-web-release-nor8.apk` | 14,312,666 | `24D05E1D80EC768368EE0F98F56B45AC` |
| `opencode-shell-web-release-r8.apk` | 12,032,512 | `D192DD54C4BFA3F51F9636C842B0C480` |

（字节数与 P1 三份均不同 ⇒ 确为新包，非陈旧产物。）

### 3.4 包内自查（3/3 通过）

| APK | `assets/www/ui` 条目 | `ui/oa` | 包内 `oc-host.js` md5 | 等于源 | 含闸门 |
|---|---|---|---|---|---|
| debug | **1613** | 1590 | `659BED67B949F1A4466AD3EF3D2D1E06` | ✅ | ✅ |
| release-nor8 | **1613** | 1590 | `659BED67B949F1A4466AD3EF3D2D1E06` | ✅ | ✅ |
| release-r8 | **1613** | 1590 | `659BED67B949F1A4466AD3EF3D2D1E06` | ✅ | ✅ |

`ui=1613` 口径说明：1613 个 zip 条目里**有 1 个是 0 字节文件**
（`assets/www/ui/oa/__vite-browser-external-2447137e-BvRk9kiK.js`），
故"非空文件"口径是 1612、`ui/oa` 非空是 1589 —— 与 P1 报告的 1613/1590 同口径（按条目数）。

### 3.5 凭据扫描

对 `git diff` 的新增行（`oc-host.js` + `PhantomServerHideTest.kt`，共 **217 行**）扫
`api_key|secret|token[:=]|password|passwd|bearer|ANTHROPIC|OPENAI|sk-|ghp_|AIza|AKIA`
（忽略大小写）⇒ **0 命中**。

---

## 4. 设备复验 · 环境

| 项 | 值 |
|---|---|
| 目标设备 | **`emulator-6790`（本轮自建）** —— AVD `Medium_Phone_API_37.0`，`-port 6790 -no-snapshot-load` |
| 规格 | API 37，1080×2400，WebView DPR 2.625 |
| 选档理由 | P1 建议在**手机档**复验（P1 是平板档）；且 `Medium_Phone_API_37.0` 与他人设备所用 AVD 不同档，避免干扰 |
| 4095 夹具 | **pid 41832 全程在跑、4095 始终 LISTENING**，本轮**未起未停**（符合"不许起停"） |
| reverse | `adb -s emulator-6790 reverse tcp:4095 tcp:4095`（收尾已 `--remove`） |
| CDP | `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`，注入读 DOM 事实（收尾已移除） |
| 他人设备 | `emulator-5554`（Pixel Tablet，API 35）**全程未安装、未启动、未截图、未 CDP** |

装机：`install -r` → `Success`；`pm clear` → `Success`；
`am start -n com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity`。
包为 `research/deliver/opencode-shell-web.apk`（debug）。

---

## 5. 设备复验 · 三个判定

### 5.1 首启（未加任何服务器）：4173 行**可见** ✅

CDP 注入读（Home）：

```json
{ "pathname": "/", "origin": "http://127.0.0.1:4173",
  "registryKeyPresent": false, "ocPhantomHideStarted": true,
  "markedCount": 0, "bodyHas4173": false }
```

设置 → Server 页：

```json
{ "registryKeyPresent": false, "markedCount": 0,
  "visibleRows": ["127.0.0.1:4173 / http://127.0.0.1:4173"],
  "bodyText": "...Server\nManage server connections\nAdd server\nConnection\n127.0.0.1:4173\nhttp://127.0.0.1:4173\nPreferences..." }
```

- `markedCount = 0` ⇒ 闸门生效，**一个节点都没被标记/隐藏**（P1 缺陷已修）。
- 截图 `02-first-launch-servers-4173-visible.png`：**红点 + `127.0.0.1:4173` + `http://127.0.0.1:4173` + `…` 菜单 + `Add server` 入口全部在位**。
- 行菜单只有 `Edit` / `Set as default`，**没有 Remove** —— 与 P4/P5「builtin 不可删」一致。

> 首启 Home 仍是 `Home 0 / Loading`（P1 §5.2 的空态问题依旧存在），但**与本轮无关**：
> 本轮只负责把那条唯一可操作的行还回去，这一条已达成。

### 5.2 官方 onboarding 路径（Edit 4173 → 4095）：**红点 → 绿点** ✅

`Edit` 对话框预填 `http://127.0.0.1:4173`（证明它就是 builtin）；改为
`http://127.0.0.1:4095` + 口令 `opencode` → Save → 等 15s。

```json
{ "registryKeyPresent": true, "registryLen": 150, "visibleRows": ["127.0.0.1:4095 / http://127.0.0.1:4095"] }
```

截图 `06-after-edit-4095.png`：**绿点** `127.0.0.1:4095` / `http://127.0.0.1:4095`。
下拉切换器里也变成了 `127.0.0.1:4095`。

⇒ **P8「Edit 它就能改成自己的服务器」这条官方 onboarding 路径在条件化之后依然通。**

副产物（有价值的事实）：**Edit 也会创建注册表键**（`registryKeyPresent` 由 `false` 翻 `true`，
`registryLen=150`）。即闸门信号"用户已有自己的服务器"对 **Add 与 Edit 两条路径都成立**，
符合预期（Edit 到自有地址后，4173 确实已不该再占位）。

### 5.3 加新服务器后：4173 幻影行**消失** ✅

为与 §5.2 的 Edit 步骤解耦（Edit 后该行已非 4173，判定会失真），**`pm clear` 重来一次**，
首启直接走 `Add server`：地址 `http://localhost:4095` + 口令 `opencode` → Add server → 等 16s。

```json
{ "registryKeyPresent": true, "registryLen": 150, "markedCount": 1, "visibleRows": [] }
```

全文档扫描"直接文本含 4173"的元素（唯一判据）：

```json
{ "count": 1,
  "items": [{ "tag": "SPAN", "cls": "inline-flex items-center gap-2",
              "text": "127.0.0.1:4173", "display": "inline-flex",
              "rects": 0, "insideMarked": true }],
  "registryPresent": true }
```

- `rects: 0` ⇒ **零个 client rect，即完全不可见**；
- `insideMarked: true` ⇒ 它落在某个 `data-oc-phantom="1"` 祖先内 ⇒ 正是本例程标记的。

下拉切换器截图 `13-dropdown-after-add.png`：原本的 `Server`(4173) 分组**已消失**，
只剩新加的 **`localhost:4095`**。

⇒ **判定以「URL=4173 的行不存在」为准：通过。**

---

## 6. 流式帧时：**未测**（前置条件不具备，如实记录）

按计划要做的：进会话发 `Count from 1 to 200 comma separated` → 15s 流式窗口内
`dumpsys gfxinfo <pkg> framestats` 采帧 → 报中位/90 分位/掉帧率。

**阻塞点（实测证据链）**：加完 `localhost:4095` 之后，App 的**当前服务器上下文仍是 builtin
4173**（本轮没有切换默认服务器，而 Home 页又恒停在 Loading ⇒ 设置入口不可达）：

| 尝试 | 结果 |
|---|---|
| Home 页找 `New session` / composer | `textarea`/`contenteditable` 均不存在；`bodyText` = `Home\n0\nLoading` |
| 展开侧栏后点 `New session` | 点得中，但 15s 内轮询 composer 10 次**始终不出现**，`path` 恒为 `/` |
| 强行 `location.href = http://127.0.0.1:4173/settings` | 返回该 URL 字符串但 **SPA 未重渲染**，`bodyText` 仍是 `Home\n0\nLoading` |
| `am force-stop` + `am start` 重启（注册表已含 localhost:4095） | 仍然 `Home\n0\nLoading` |
| 需切默认服务器（设置 → localhost:4095 → 行菜单 `Set as default`） | 不可达：Home 楔死在 Loading，侧栏与设置入口均不渲染（`byText("Settings")` 返回 `null`） |

⇒ **进不去会话 ⇒ 无流式输出 ⇒ 无法采帧。** 本项**不执行、不填报任何数值**。
这与 P1 §5.4「夹具缺失」是**不同**的阻塞原因：P1 是后端不在，本轮后端 pid 41832 全程在跑、
`reverse` 已建、**Add server 实测成功**（下拉出现 `localhost:4095`），是**App 侧当前上下文
不可切换**。

**留下的可执行线索（留给下一轮，不在本轮扩范围）**：只要在 **Home 楔死之前**先完成
`Set as default → localhost:4095`，Home 即应从 4095 取数、composer 出现，流式帧时即可测。
本轮是 `pm clear` 后先 Add 再想切上下文，顺序反了。

---

## 7. 收尾

| 项 | 结果 |
|---|---|
| `adb -s emulator-6790 reverse --remove tcp:4095` | ✅ 已移除；`reverse --list` 为**空** |
| `adb forward --remove tcp:9222` | ✅ 已移除；`forward --list` 为**空** |
| 模拟器 | ✅ `adb emu kill` → `OK: killing emulator, bye bye` |
| 后端 pid 41832 | ✅ **仍是 41832 且 4095 仍 LISTENING**（`opencode.exe`，未起未停） |
| `adb devices` | 仅 **`emulator-5554  device`** —— 本轮自建的 6790 已消失；`emulator-5554` 是他人设备，**本轮全程零触碰** |
| gradle 进程 | ✅ 正常结束（`BUILD SUCCESSFUL`），无残留 |
| 残留构建锁 | ✅ 已释放 |
| 应用状态 | `com.xiufeigo.opencodeshell.web.debug` 保留在 6790 上（设备已 kill，随之销毁） |
| 截图 | `research/deliver/ux15p2_shots/`（13 张），探针脚本 `research/tmp/p_*.js` + `cdp.ps1` |

---

## 8. 红线核对

- `ui/` **零改动**（`git status --porcelain -- webapp/src/main/assets/www/ui/` 恒空）。
- `app/**` **零改动**。
- **生产 Kotlin 零改动**；只改了 `PhantomServerHideTest.kt` 这一个 `.kt` **单测**绊线。
- 产品代码只改了 `webapp/src/main/assets/www/oc-host.js` 的**幻影例程段**（含其注释）。
- **未 commit**（工作区改动待人复核）。
- `emulator-5554`（他人设备）：**未安装、未 pm clear、未启动、未截图、未 CDP 注入**。
- 4095 后端：**未起未停未重启**。

---

## 9. 留待下一轮

1. 🔴 **流式帧时仍无数据**（§6）：需在 Home 楔死前先 `Set as default → localhost:4095`。
   这是 P1 之后第二次因「App 当前上下文不可切换」而卡住，**建议单独立项**：
   宿主是否应在 `pm clear`/首启且已有用户服务器时，把默认上下文指向注册表里的服务器，
   而不是恒指向 builtin `location.origin`。这与本轮的隐藏条件化是**同一个根因的两面**。
2. 🟡 首启 Home 空态（P1 §5.2）：`Loading` 永不完成 + 无添加服务器引导 + `+` 无可访问名称。
   本轮已把 4173 行还回首启，但 Home 正文仍无引导文案。
3. 🟡 本轮设备为**手机档**（P1 为平板档）—— P1 §7.3「`lastCompact` 收口跨布局」的
   复验请求，本轮已在手机档覆盖（Connection 区整块消失，未复现「只剩红点」）。
4. 🟢 可选：把 `Add server` 对话框里 `input` 被宿主跳过的那条双保险（排除 `input`/`textarea`）
   在**真正有输入焦点**时再验一次 —— 本轮 CDP 注入改值时验证到该保护不误伤（见 §5.3 失败重试过程）。
