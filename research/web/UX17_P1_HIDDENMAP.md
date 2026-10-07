# UX17-P1 · 幻影清除 v2（注册表 hidden-map）+ T6 通知回归定位

- 轮次：UX17-P1
- 仓库：`D:\XiaoZhuTouTou\opencode-android-shell`
- 设备：`emulator-6790`（本轮自建 `Medium_Phone_API_37.0`；`emulator-5554` 属他人，全程未触碰）
- 后端：`127.0.0.1:4095`，pid **21136**，全程未起停，收尾复核 200

---

## 1. `oc-host.js` 改造（唯一改动的产品代码）

### 1.1 删除

DOM 级隐藏例程**整体退役**（原 1381–1606 行，226 行 → 现 226→0）：

| 退役项 | 说明 |
| --- | --- |
| `PHANTOM_ATTR` / `PHANTOM_STYLE_ID` / `PHANTOM_THROTTLE_MS` / `PHANTOM_ROW_SEL` | v1 常量 |
| `installPhantomServerHideStyle()` | 注入 `display:none!important` 的 `<style>` |
| `phantomOriginToken()` | v1 的 host:port 文本匹配令牌 |
| `hidePhantomRows()` | v1 扫描主体（XPath 候选 + 上爬 + 打标记） |
| `phantomRowFor()` | v1 有界上爬（`lastCompact` 收口） |
| `startPhantomServerHide()` | v1 启动入口 + `MutationObserver` 节流 |

退役理由（钉在段注释里，防后人回潮）：v1 是纯下游补丁，只遮可见界面、留空状态，
还踩过"误标 `<html>` ⇒ 整页连 `<style>` 一起消失、屏幕全黑"；hidden-map 是**上游自己的机制**。

### 1.2 新增 `seedPhantomHidden()`

```js
var PHANTOM_REGISTRY_KEY = "opencode.global.dat:server";

function seedPhantomHidden() {
  try {
    var raw = localStorage.getItem(PHANTOM_REGISTRY_KEY);
    if (!raw) return;                              // 首启闸门
    var reg = JSON.parse(raw);
    if (!reg || typeof reg !== "object") return;
    if (!reg.hidden || typeof reg.hidden !== "object") reg.hidden = {};
    var origin = location.origin;                  // http://127.0.0.1:4173
    if (reg.hidden[origin]) return;                // 幂等
    reg.hidden[origin] = true;
    localStorage.setItem(PHANTOM_REGISTRY_KEY, JSON.stringify(reg));
  } catch (e) {}
}
```

**键格式**：用**完整 origin 字符串**（带 scheme）。依据是 P6 冒烟轮写入完整 URL 字符串即生效。
`opencode.activeServer` 已证伪（bundle 内是内存路由方法，非存储键）。

### 1.3 调用时机 —— 选"两处"而非只挂 load（**这是本轮的关键判断**）

| 位置 | 位置 | 理由 |
| --- | --- | --- |
| ① | 段落末尾随文档开始直调一次 | 本文件由 `addDocumentStartJavaScript` 注入 ⇒ 整段在**文档开始**跑完，早于任何页面脚本；官方读注册表/渲染列表发生在页面脚本里 ⇒ ①**已经早于官方 hydration**。 |
| ② | 回合电源传感器的 `window.fetch` 包装内，每次触发幂等重跑 | **只挂①会漏掉主路径**：首启那一轮键还不存在、①早退；用户随后 Add 了 4095 ⇒ 键**此刻**才被创建，而官方列表早已渲染完 ⇒ 只跑一次必须重启才生效。fetch 包装本身就是"每轮传感器扫描"，已在位、**不新增任何事件监听**（沿用 v1 的"不做第二套真相来源"取舍）。 |

未选"只在 load 事件调一次"：`load` 晚于页面脚本 ⇒ hydration 已过，且同样漏掉"本次会话内 Add"。

顶层函数声明会被提升 ⇒ 位置 ② 在 IIFE 内引用安全（用 `typeof` 守卫 + try/catch）。

---

## 2. 绊线 `PhantomServerHideTest.kt`（4 条，DOM 断言全删）

| 用例 | 钉住的不变量 |
| --- | --- |
| `seedWritesHiddenMapEntryForOwnOrigin` | 读 `opencode.global.dat:server`；惰性 `getItem`；`var origin = location.origin`（动态、不写死端口）；写 `reg.hidden[origin] = true`；整表 `setItem(… JSON.stringify(reg))`；反向禁 `:4173` 字面量；两处调用点都在 |
| `seedSkipsWhenRegistryAbsent` | 闸门 `if (!raw) return;` 存在、**早于** `JSON.parse`、**早于** `setItem`；闸门只判**存在性**（不得改判 list 长度）；反向禁"先写后判" |
| `seedIsIdempotentAndNonDestructive` | `if (reg.hidden[origin]) return;` 存在且早于写入；缺失时补建 `hidden`；**反向禁** `reg.list` / `delete reg` / `removeChild` / `.remove()` / `.splice(`；例程体整体被 `try/catch` 包住；非对象早退 |
| `domRoutineRemoved` | **可执行代码**（剥 `//` 注释）中不得再出现 `data-oc-phantom` / `XPathResult` / `MutationObserver` / `PHANTOM_ATTR` / `hidePhantomRows` / `startPhantomServerHide` / `display:none` |

负向断言一律走"剥注释"后的代码文本 —— 段注释里保留了"v1 用过这些手段"的退役理由，
若不剥会被自己的说明文字误伤。

### 2.1 反向验证（抄原文）

| 撤除 | 实际结果 |
| --- | --- |
| 撤 1（删 `reg.hidden[origin] = true;`） | `seedWritesHiddenMapEntryForOwnOrigin FAILED` @ `PhantomServerHideTest.kt:93` |
| 撤 2（删 `if (!raw) return;`） | **3 条真红**：`seedSkipsWhenRegistryAbsent FAILED`、`seedIsIdempotentAndNonDestructive FAILED`、`seedWritesHiddenMapEntryForOwnOrigin FAILED` |
| 撤 4（在代码里放回 `var PHANTOM_ATTR = "data-oc-phantom";`） | `domRoutineRemoved FAILED` |

恢复后全绿。**过程中踩到的坑（已修）**：撤 4 的注入残留在后台脚本的还原步骤未落地时留在了
`oc-host.js` 里，被我自己的一致性扫描抓到（`LEAK: data-oc-phantom` / `LEAK: PHANTOM_ATTR`），
已从备份还原并用 MD5 比对确认（`2892ED1F…` ↔ 备份一致），随后重跑全套 238 条全绿。

---

## 3. 测试 / 构建 / 落盘

| 项 | 结果 |
| --- | --- |
| `:webapp:testDebugUnitTest` | **238 条，0 失败，0 错误**（BUILD SUCCESSFUL） |
| `PhantomServerHideTest` | 4 条全绿 |
| `assembleDebug` / `assembleRelease` / `assembleReleaseR8` | 均 BUILD SUCCESSFUL |
| 包内 `ui` 资产 | **1613** ✓ |
| 包内 `oc-host.js` MD5 | `2892ED1FF8B8A3A8716740B1197A3CD5`（68,009 B）**= 仓库源文件 MD5** ✓ |
| 凭据扫描（3 个 APK + 2 个改动文件） | **0 命中** ✓ |

### 3.1 三份规范名产物（已覆盖 `research/deliver/`）

| 文件 | 体积 | MD5 |
| --- | --- | --- |
| `opencode-shell-web.apk` | 16,827,823 | `F03A86EF47B22D12C689A308E0CAA67D` |
| `opencode-shell-web-release-nor8.apk` | 14,310,318 | `C6A5AFB7844CE38DFDAA0E4494CB49FF` |
| `opencode-shell-web-release-r8.apk` | 12,032,548 | `E17C5C025C7A361F15E52DB060BF0CCF` |

`oc-host.js` MD5 变更登记：**旧** `（见 git 历史 ac0bb01）` → **新 `2892ED1FF8B8A3A8716740B1197A3CD5`**

---

## 4. 设备复验（emulator-6790）

### 4.1 首启语义 —— ✅ PASS

`pm clear` 冷启后经 CDP 直读：

```json
{"origin":"http://127.0.0.1:4173","regExists":false,"regRaw":null,
 "bodyTxt":"Home 0 All projects Loading","seedFn":"function","phantomAttr":0}
```

- `regExists:false` ⇒ 注册表键不存在 ⇒ **我们没写任何东西** ⇒ builtin 行可见 = 官方 onboarding（P2 语义保留）
- `seedFn:"function"` ⇒ 新例程已安装并在跑
- `phantomAttr:0` ⇒ v1 的 DOM 隐藏**零残留**

### 4.2 `seedPhantomHidden()` 功能 E2E —— ✅ 5/5 PASS（设备实跑）

| # | 场景 | 实测 |
| --- | --- | --- |
| A | 键不存在 → 调用 | `ABSENT-OK`（未创建键） |
| B | `list=[4095]`、`hidden={}` → 调用 | `{"list":[{"url":"http://127.0.0.1:4095"}],"hidden":{"http://127.0.0.1:4173":true}}` |
| C | 再调一次 | `IDEMPOTENT-OK`（序列化结果逐字节不变） |
| D | `list` 是否被动 | `[{"url":"http://127.0.0.1:4095"}]` 原样保留 |
| E | 写坏 JSON `{corrupt json!!` 后调用 | 原样保留、不抛异常（`RAW={corrupt json!!`） |

### 4.3 位置 ① 生产调用点 —— ✅ PASS

在真机写入 `{"list":[4095],"hidden":{}}` 后 `location.reload()`（= 走文档开始路径），reload 后：

```json
{"list":[{"type":"http","http":{"url":"http://127.0.0.1:4095"}}],
 "hidden":{"http://127.0.0.1:4173":true},
 "projects":{},"lastProject":{},"recentlyClosed":{}}
```

- `hidden["http://127.0.0.1:4173"]=true` 由**我们的文档开始直调**写入 ✓
- `list` 被官方规范化成自己的 schema（`{type,http:{url}}`）⇒ **官方确实读了我们写的这份注册表** ✓

### 4.4 ❌ 未完成：Add 后的"不点切换器自动切换"UI E2E

**未取得结论，不作任何断言。** 原因：本轮设备上没有走通"Add server"这条真实 UI 路径 ——
Home 停在 `Loading`，`document.querySelectorAll('*')` 全量扫 4000 个节点**找不到任何**
含 server/setting/add/connect/new 文案的可见可点元素，该布局下无可达的设置入口；
靠盲点坐标猜是不负责任的。4.2/4.3 已把"写入机制 + 上游消费"两端证到位，缺的是**中间那段
官方 Add 交互**。**下一轮建议**：沿用 UX16-P3 的 `cli.py click "文本"` 驱动，先在设备上
把 Add 对话框走通，再复验"不点切换器 ⇒ 4173 全界面消失 + Home 自动落在 4095"。

### 4.5 T6（后台通知）—— ❌ 未定位，按指令**不猜修**

**本轮无法复现，因此不给断点、不做修复。** 证据链（全程 logcat）：

| 观测 | 计数 | 读法 |
| --- | --- | --- |
| `[oc-pow] installed` | **1** | 传感器装上了、`window.fetch` 包装**活着** ⇒ 排除"fetch 包装被覆盖" |
| `oc-pow round POST` | **0** | 本轮**从未发出过一条回合消息** ⇒ T6 的"发消息那一刻"根本没发生 |
| `setKeepScreenOn` | **0** | 桥上常亮调用 0 次 ⇒ 与 T4 常亮 PASS 并不矛盾（那是别的路径打的 flag） |
| `P7 onPause` | **0** | 与 T6 记录一致（走空闲 `pagehide` 分支） |
| `D1 applyKeepScreenOn` | 有 | `roundActive=false foreground=true shouldKeep=false flagSet=false` ⇒ **`foreground` 字段为 true，前台判定本身没坏** |

附带发现（非 T6 断点，但值得记）：`http://127.0.0.1:4173/api/event` 被打成 **404**，
且以**约 1 次/秒**重连（抓到 `#99`→`#104`）。这正是 P7 注释里记录过的"4173-404 空转流"噪声，
`oc-host.js` 已能把它和 4095 真流分开打点（`event stream NON2XX`），本轮未改。

**为什么不能就此定论**：T6 的 4 个分叉（乐观置位 / 桥收到 / onPause / 传感器检测）
里，本轮只走到"传感器活着"这一步就断了 —— **没有一条回合消息**，
后面三条全部无样本。要定位必须先复现"发消息 → HOME → 25s"这条链，
而发消息同样卡在 4.4 的 Add 路径上。**故按红线"证据不够就停、不许猜修"处理。**

---

## 5. 收尾

- `emu kill` emulator-6790 已执行；`adb devices` 现仅剩 `emulator-5554`（他人设备，全程未碰）
- `reverse --remove-all` + `forward --remove tcp:6817` 已执行
- 调试包已从 6790 卸载
- 后端 pid **21136** 未变，`http://127.0.0.1:4095/` = **200**
- **锁已释放**

---

## 6. 红线自检

| 红线 | 状态 |
| --- | --- |
| 不改 `ui/` | ✅ 未改（1613 资产不变） |
| 不改 Kotlin | ✅ 未改（T6 证据不足，按指令不猜修） |
| 不改 `app/**` | ✅ 未改 |
| 不 commit | ✅ 未 commit（工作区留改动） |
| 不碰他人设备 | ✅ 全程只对 `-s emulator-6790` 下命令 |
