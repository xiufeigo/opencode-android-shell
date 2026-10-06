---

## 1. 阶段一：非侵入复现（只读打点，未改一行代码）

### 1.0 环缓冲打点的**选择**（派单要求二选一，此处记录）

派单给的 b64 脚本以 `window.__ocBg` 为真值源。**本轮实测该字段不存在**：

```json
{"setBg":"function","ocBg":"undefined","vsWrapped":true,"perm":"granted"}
```

读 `oc-host.js:605-619`，P10 shim 把真值放在**闭包变量** `ocBackgrounded` 里，
`window.__ocSetBackground(v)` 是唯一写入口，**从不在 window 上挂 `__ocBg`**。
⇒ 派单原脚本的 `getter` 读 `window.__ocBg` 会恒读到 `undefined` ⇒ 永远返回
`'visible'`/`true`，**打点不仅无效，还会伪造出"闸门没生效"的假象**。

**本轮选择：只包一层计数**（派单给的第二选项）。不重定义真值、不碰 P10 shim 的闭包，
而是**包装 shim 已经装好的 getter / hasFocus**，把每次读数追加进 `window.__gateLog`：

```js
window.__gateLog=[];
var d=Object.getOwnPropertyDescriptor(document,'visibilityState'); var g=d&&d.get;
if(g) Object.defineProperty(document,'visibilityState',{configurable:true,get:function(){
  var v=g.call(document); window.__gateLog.push(['vs',v,Date.now()]); return v;}});
var hf=document.hasFocus.bind(document);
document.hasFocus=function(){var f=hf(); window.__gateLog.push(['hf',f,Date.now()]); return f;};
```

包装可行性的硬证据 = `vsWrapped:true` ⇒ 冷启后 shim 的 getter **确实在位**。
全程只读打点、不碰后台窗口、不改真值源、不派任何事件。

> 注入通道 `am start --es oc_debug_eval`（`WebHostActivity.kt:92`）在应用已运行时会
> 把任务拉到前台（`its current task has been brought to the front`），所以**臂环缓冲与
> 读回都放在后台窗口之外**，后台 30 s 窗口内零注入——这正好补上了 P12 §5 记的取样缺口。

### 1.1 冷启重进后��关键读数

| 读数 | 值 | 含义 |
|---|---|---|
| `typeof window.__ocSetBackground` | **`function`** | P10 shim 冷启后在位 ✅ |
| `typeof window.__ocBg` | `undefined` | 闭包真值，未挂 window（见 §1.0） |
| `Notification.permission` | **`granted`** | P11 权限重推再次验收通过 ✅ |
| `location.pathname` | `/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_ef43fab08ffeoHiNxtinjW9V0c` | b64 段 = `http://127.0.0.1:4095`，**会话标签页打开态** ⇒ hasOpenTab ✅ |

### 1.2 后台 30 s 窗口的完整时序（logcat 原文）

| 时刻 | 事件 |
|---|---|
| 12:09:47.954 | `[oc-pow] keepScreenOn -> true busy=[__post__]`（POST 乐观占位） |
| 12:09:48.087 | `[oc-pow] round start session.execution.started` ⇒ **`onRoundStart` 冷启后正常触发** |
| 12:09:49.454 | **`P7 onPause: round active -> keep SSE alive (blur only, no pagehide)`** |
| 12:09:49.454 | `P6 dispatch synthetic blur` |
| 12:09:50.215 | ping 发出后继续 `[oc-pow] event stream OPEN #105 …4095` |
| **12:09:51.239** | **`[oc-pow] round end session.execution.succeeded`** ← 终帧在后台**到达** ✅ |
| 12:09:51.241 | `[oc-pow] keepScreenOn -> false busy=[]` |
| 12:09:51.458 | `P7 background round ended -> dispatch pagehide (release SSE)` |
| 12:09:51.480 | `[oc-pow] event stream CLOSE #81 http://127.0.0.1:4095 cancel`（**在终帧之后**，收口正确） |
| 12:10:19.567 | `P6 dispatch synthetic pageshow`（回前台重建流） |

### 1.3 环缓冲读数（`Date.now()` 换算到设备钟）

以 `P6 dispatch synthetic pageshow @12:10:19.567` 与 `vs visible @1791288619717` 对齐，
得 `epoch → 设备钟` 的线性映射（自洽校验：`589461→12:09:49.461`、
`589222-589461=2761ms` 与墙钟差 2.761 s 一致）。

| epoch | 读数 | 设备钟 | 解读 |
|---|---|---|---|
| 1791288565541 | `vs=visible` / `hf=true` ×12 | 12:09:25.542 | 冷启后前台基线 |
| 1791288589461 | `vs=visible` | 12:09:49.461 | — |
| 1791288589472 | **`hf=false`** | **12:09:49.472** | HOME 后 **18 ms** 即失焦 |
| 1791288592222 | `hf=false` ×2 → **`vs=hidden`** | **12:09:52.222** | **比 onPause 迟 2.768 s** |
| 1791288619717 | `vs=visible` / `hf=true` | 12:10:19.717 | 回前台 |

### 1.4 通知读数（`dumpsys notification --noredact`）

```
NotificationRecord(0x0f3286ef: pkg=com.xiufeigo.opencodeshell.web.debug
                   id=-927306448 channel=oc_turns importance=3 flags=AUTO_CANCEL)
            when=1791288592232/1791288592232
                android.title=String (Response ready)
                android.text=String (v2 smoke test)
```

`when=1791288592232` = **12:09:52.232**，即 **HOME 之后 2.8 s**、在 `vs` 翻 `hidden`
（12:09:52.222）之后 **10 ms** 投出；**早于 12:10:19 回前台** ⇒ 确定是后台窗口内投递。

### 1.5 三格判定表

| 格子 | 结论 |
|---|---|
| **onPause 分支** | ✅ **走了回合保活分支**（`round active -> keep SSE alive`，无 pagehide）；`onRoundStart` 冷启后正常触发 |
| **succeeded 送达** | ✅ **终帧在后台送达**（12:09:51.239），SSE 在其后才被 `pagehide` 收口 |
| **闸门读数** | ⚠️ **值是对的，但"对得太晚"**：`vs` 直到 **12:09:52.222** 才变 `hidden`，比 onPause 迟 **2.768 s**；终帧比"闸门可开"早了 **0.98 s** |

**⇒ P12 §5 的"丢通知"本轮未复现：通知出了。** 但这不是"没问题"——本轮是**险胜**，
详见 §2。

---

## 2. 根因：宿主侧顺序竞态（P13 定位）

### 2.1 闸门的真实判据（读上游 bundle 得到，不是推测）

在 bundle `build-CA4aPxfP.js` 实读到页面上层的 focus 管理器原文：

```js
isFocused(){ return typeof this.#e==`boolean` ? this.#e
                 : globalThis.document?.visibilityState!==`hidden` }
```

⇒ **通知闸门真正依赖的是 `visibilityState`，不是 `document.hasFocus()`**。
`hasFocus` 只影响显式 `setFocused` 的分支；没有显式值时，**`visibilityState` 是兜底真值**。

（本轮实测也印证了这点：`succeeded` 落在 12:09:51.239 时 `hf` 已经是 `false`、
`vs` 还是 `visible`；闸门**并没有**在终帧那一刻判定，而是等到 12:09:52.222
`vs` 翻 `hidden` 之后的 10 ms 才投出通知——**闸门的开关时刻是由 `vs` 决定的**。）

### 2.2 为什么 `vs` 会迟 2.768 s

`WebHostActivity.onPause()` 修前是这样一个顺序：

```kotlin
if (::webView.isInitialized) webView.onPause()                                   // ← 先
…
evaluateJs("window.__ocSetBackground && window.__ocSetBackground(true)")         // ← 后
```

`evaluateJs`（P10 读数落地点）被写在 **`webView.onPause()` 之后**。WebView 一旦被
`onPause()` 暂停，JS 线程即被节流，随后这条 `evaluateJavascript` 不会立刻执行
⇒ 页面的 `visibilityState` 在 **2.768 s** 内仍如实报告 `"visible"`
⇒ 页面上层 `isFocused()` 也就**误判为"仍在前台"**。

### 2.3 竞态窗口

```
onPause ─────────────── succeeded ──────► [ vs 翻 hidden ] ──► 通知
12:09:49.454          12:09:51.239      12:09:52.222      12:09:52.232
                      ↑ 终帧比"闸门可开"早 0.98 s
```

- **本轮**：闸门重评估恰好落在 `vs` 翻 `hidden` 之后 10 ms ⇒ 险胜，通知出了。
- **P12 §5**：若同一竞态里闸门重评估落在那 2.768 s **之内**（`vs` 仍 `visible`），
  `isFocused()` 为 true ⇒ 闸门 return ⇒ **终帧落地了、通知没有**，
  与 P12 观察到的"Pong 落地但无通知、perm granted、hasOpenTab 满足"**逐条吻合**。

⇒ 这是一个**纯顺序、纯宿主侧、可测**的缺陷，不是上游 bug，也不需要改任何前端资源。

### 2.4 修法（最小 diff，只调换顺序）

把 P10 读数落地点从 `onPause()` 末尾**移到 `webView.onPause()` 之前**：

```diff
+ evaluateJs("window.__ocSetBackground && window.__ocSetBackground(true)")
  if (::webView.isInitialized) webView.onPause()
- evaluateJs("window.__ocSetBackground && window.__ocSetBackground(true)")
```

**语义零变化**：读数仍然只由 `__ocSetBackground` 改，**仍然不派发任何事件**
（派 `visibilitychange` 会触发上游 resync → abort，掐死保活路径的 SSE，P6/P7 已证的死锁链）。
修后 `vs` 应在 onPause 后**数十毫秒内**就变 `hidden`，2.768 s 的竞态窗口被消除。

补绊线：`webapp/src/test/java/com/xiufeigo/opencodeshell/web/Ux13P13BackgroundGateOrderTripwireTest.kt`
——顺序是纯文本性质、无法在 JVM 里跑起来，用**源码顺序断言**当绊线，
另加两条：① `__ocSetBackground(true)` 恰好出现一次（防调序时留下重复调用）；
② `onPause()` 里不得出现 `visibilitychange`（防 P6/P7 死锁链复活）。

> ⚠️ 绊线自身踩的一个坑（已修，记录以免后人重蹈）：这三���断言最初直接对**源码原文**
> 做 `indexOf`/`contains`，结果被 `onPause()` **旁边的设计注释**骗了——P6/P7/P10/P13 的
> 说明文字里恰好写着 `webView.onPause()`、`visibilitychange`、`__ocSetBackground(true)`
> 这些字面量，于是"顺序断言"读到了散文而不是语句，两条用例当场变红。
> 修法 = 断言前先 `stripComments()`（剥 `//` 与 `/* */`，并跳过字符串字面量），
> 只对**可执行代码**断言。绊线的"防回退"能力不因此减弱，反而更强。

---

## 3. 构建与产物

协议：Microsoft JDK **21.0.12.101-hotspot**（`jdk-21.0.12.101-hotspot`）、内联
`ANDROID_HOME`、脱离会话 `Start-Process` + 轮询，`--no-daemon`。

| 项 | 结果 |
|---|---|
| `:webapp:testDebugUnitTest` | ✅ **全绿**：28 个 suite / **226 用例**，failures+errors = **0**，skipped = 0 |
| 新增绊线 `Ux13P13BackgroundGateOrderTripwireTest` | ✅ **3/3 通过** |
| `:webapp:assembleDebug` / `assembleRelease` / `assembleReleaseR8` | ✅ `BUILD SUCCESSFUL in 1m 1s`（88 tasks） |
| 包内自查 `ui/` 文件数 | **1613** ✅ |
| `oc-host.js` md5（登记基线） | `232E916B5B16E99A6D94C3C4561A9B8A` |

产物已覆盖 `research/deliver/`（SHA256）：

| 文件 | 大小 | SHA256 |
|---|---|---|
| `opencode-shell-web.apk`（debug） | 16,867,075 B | `50F4A668E86A3734F07BA10EE55678E6AF4E59D84165035EF5C2D6C6C92DDE3C` |
| `opencode-shell-web-release-nor8.apk` | 14,306,922 B | `883AACDCADF9089BAED02AC772D1383E1273AC8ACBA6489D93CB9DAE50F8C177` |
| `opencode-shell-web-release-r8.apk` | 12,026,852 B | `594BA3056624E6965DF991B95237EB5543C17CDA15EDFAB41850CDB6E189C33C` |

> 修前 debug 包基线（P12/P13 起跑时实测）：`AA94B8A90617484C992EEFF15B1CA006E5ABF2613881BFC93A62A61818209CF4`，
> 大小 16,867,074 B ⇒ 本次产物**确已改变**（+1 B / 哈希变），与"只调换顺序"一致。
---

## 4. 同轮设备复验（装修复包，走同一套流程）

复验同样走"冷启重进 -> 进会话 -> 发 `ping` -> 立刻 HOME -> 30s"，闸门打点与时钟对齐口径与 §1 完全一致。

### 4.1 复验环境（中途踩的坑，已记录）

首轮复验**作废**：脚本在后台窗口中途被取消，应用仍停在后台 => 随后的点击全部落到
桌面 / Google 搜索上（读回 `perm:"denied"`、`path:"/"`）。随后 32 连发 `keyevent 67`
把应用打成 ANR（`OpenCode Web isn't responding`）。处置：`adb reboot` 重启模拟器清干净，
**每步加读回校验**后重跑。

另发现一个可复现的 UI 坑：`adb shell input text "http://127.0.0.1:4095"`
**偶发把首字母大写成 `Http://`**，导致 Add server 校验不过、条目根本没进去
（表现为 Connection 里只剩 4173）。=> 复验时先注入读回 `input.value` 确认大小写再提交。

### 4.2 复验前置读数（全部注入校验通过）

| 项 | 值 |
|---|---|
| 服务器登记 | `{"url":"http://127.0.0.1:4095","password":"opencode"}` OK |
| 冷启重进路由 | `/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_ef43fab08ffeoHiNxtinjW9V0c` OK |
| `Notification.permission` | **`granted`** OK（真点 Allow 取得） |
| `typeof __ocSetBackground` / `vsWrapped` | `function` / **`true`** OK |

### 4.3 复验时序（logcat 原文）

| 时刻 | 事件 |
|---|---|
| 12:31:12.004 | `[oc-pow] round start session.execution.started` |
| **12:31:13.596** | **`P7 onPause: round active -> keep SSE alive (blur only, no pagehide)`** |
| **12:31:13.607** | **闸门 `vs` -> `hidden`、`hf` -> `false`（晚 11 ms）** <- 修复生效 |
| **12:31:19.772** | **`round end session.execution.succeeded`** |
| 12:31:21.604 | `P7 background round ended -> dispatch pagehide`（终帧之后才收口 OK） |
| 12:31:43.500 | `P6 dispatch synthetic pageshow`（回前台） |

### 4.4 闸门 vs 终帧的先后（**这就是修复的判据**）

| | 修前（§1.5） | **修后（§4.3）** |
|---|---|---|
| onPause -> `vs` 翻 `hidden` | **2 768 ms** | **11 ms** |
| 终帧 `succeeded` 相对"闸门可开" | **早 0.98 s（险胜）** | **晚 6.17 s（稳赢）** |
| 通知 `when` | 12:09:52.232 | **12:31:20.462** |
| 终帧 -> 通知 | 0.99 s（闸门仅早 10 ms 才开） | **0.69 s** |
| 通知是否出现 | 出 | **出** |

复验通知读数（`dumpsys notification --noredact`）：

```
NotificationRecord(0x065cf9e8: pkg=com.xiufeigo.opencodeshell.web.debug
                   id=-927306448 channel=oc_turns importance=3 flags=AUTO_CANCEL)
            when=1791289880462/1791289880462
                android.title=String (Response ready)
                android.text=String (v2 smoke test)
```

`when` 落在 **12:31:20.462** = HOME 后 **6.9 s**、回前台（12:31:43.5）**之前** => 后台窗口内投递 OK

### 4.5 复验三格判定表（修复包）

| 格子 | 结论 |
|---|---|
| **onPause 分支** | OK 回合保活分支（`round active -> keep SSE alive`，无 pagehide） |
| **succeeded 送达** | OK 后台送达 12:31:19.772；`pagehide` 收口在其后 1.8 s |
| **闸门读数** | OK **11 ms 即翻 `hidden`**（修前 2 768 ms）—— 竞态窗口已消除 |

### 4.6 复验的诚实边界

复验证明了**竞态已被消除**（2 768 ms -> 11 ms，量化差异明确）。
但要如实说明：**P12 §5 那个"丢通知"现象在修前的本轮实测里并未复现过**，
所以"修后必然不再丢"是推断、不是直接观测。
被直接观测到的是：修前闸门在 onPause 之后 2.8 s 内仍向页面上层**谎报 `visible`**，
这是与"终帧落地却无通知"唯一自洽的宿主侧成因；修复把这段谎报压到 11 ms。
---

## 5. 收尾（纪律自检）

| 项 | 状态 |
|---|---|
| `adb reverse --remove tcp:4095` | 已执行，`reverse --list` 为空 |
| 后端 4095 | **未起停**；收尾 `health=200`、pid **15096** 仍在 LISTENING（与 P10/P12 记录同 pid） |
| `emulator-6790`（本轮自建） | `emu kill` -> 该 serial 进程数 **0**、6790 无 LISTENING（仅 TIME_WAIT） |
| 设备纪律 | 收尾时出现 `emulator-5554`（`-avd dshr_tab` / Pixel_Tablet）—— **非本轮自建，全程未碰** |
| adb server | **未 kill-server**（进程仍在） |
| 代码改动范围 | 仅 `WebHostActivity.onPause()` 一处**顺序调换** + 新增 1 个绊线测试文件 |

**碰过的 serial：仅 `emulator-6790`**（自建，自关）。

---

## 6. 本轮结论（供后续轮次直接用）

1. **`window.__ocBg` 不存在**，P10 真值在 `oc-host.js` 的闭包 `ocBackgrounded`。
   任何以 `window.__ocBg` 为真值源的注入脚本都会恒读 `undefined`，
   不仅无效、还会**伪造出"闸门没生效"的假象**。打点请**包装 shim 已装好的 getter**。
2. **通知闸门的真判据是 `visibilityState`，不是 `document.hasFocus()`**
   （bundle `build-CA4aPxfP.js` 的 `isFocused()` 兜底分支原文）。只盯着 `hasFocus()`
   会得出错误结论 —— P12 的"四值复盘"就差点归错因。
3. **onPause 里 `__ocSetBackground(true)` 必须排在 `webView.onPause()` 之前**。
   排后面会被 WebView 暂停节流，实测迟 **2 768 ms**（修后 **11 ms**）。
   这是 P12 §5 "终帧落地却无通知"唯一自洽的宿主侧成因。
4. **复现手法固化**：`oc_debug_eval` 注入会把任务拉到前台，**后台窗口内必须零注入**；
   臂/读回都放在窗口外。`Date.now()` 读数可用 `P6 dispatch synthetic pageshow`
   与一条 `vs=visible` 对齐换算回设备钟（线性、自洽）。
5. **UI 坑两条**：`adb shell input text` 会偶发把 URL 首字母大写成 `Http://`
   （须读回 `input.value` 校验）；连发 `keyevent` 会把 WebView 打成 ANR。
6. `pm clear` 之后**服务器登记不会自动恢复**，PROD 包每次冷启都要在 UI 里重新
   `Add server` 4095 并设 Default（P12 §7.1 结论本轮再次验证）。