# E5：官方 UI 在手机宽度下的形态（emulator-5636，逻辑视口 = 手机尺寸）

- **执行日期**：2026-10-04
- **设备**：`emulator-5636`（平板模拟器，Android 14 / SDK 34）
- **被测物**：`research/deliver/opencode-shell-web.apk` → `com.xiufeigo.opencodeshell.web.debug`，`WebHostActivity`
- **后端**：`http://127.0.0.1:4095`（`adb reverse tcp:4095 tcp:4095`），口令 `opencode`（已存在于设备，未重新输入）

> ## ⚠️ 全局声明：**这是"几何等价"，不是"真机验证"**
> 本轮**没有**在真机上跑过任何一行。下面所有窄屏结论都来自**在 5636 上覆写几何**得到的等价测量，
> 并且其中 ①③④ 的**布局视口**是 CDP `Emulation.setDeviceMetricsOverride` 造的（见 §3 阻塞与绕法）。
> 报告里任何数字都**不得**被转述为"已在真机验证"。

---

## 1. 改前 / 改后 / 改回 几何原始输出

### 1.1 改前（本轮开始时的基准几何）

```
$ adb -s emulator-5636 shell wm size
Physical size: 3840x2160
Override size: 1366x768
$ adb -s emulator-5636 shell wm density
Physical density: 320
Override density: 160
```

### 1.2 改成手机几何

```
$ adb -s emulator-5636 shell wm size 1080x2400
$ adb -s emulator-5636 shell wm density 420

$ adb -s emulator-5636 shell wm size
Physical size: 3840x2160
Override size: 1080x2400
$ adb -s emulator-5636 shell wm density
Physical density: 320
Override density: 420

$ adb -s emulator-5636 shell dumpsys window displays
init=3840x2160 320dpi mMinSizeOfResizeableTaskDp=220 base=1080x2400 420dpi cur=1080x2400 app=1080x2274 rng=1080x954-2400x2274
```

**JS 侧独立复核**（真机同构性）：

```json
{"screenW":412,"screenH":915,"devicePixelRatio":2.625}
```

⇒ `1080 / 2.625 = 411.4dp`，`2400 / 2.625 = 914.3dp`。
与真机 `1080x2400 @420 ⇒ 411 x 914 dp` **一致**（CSS 取整为 412/915）。

### 1.3 改回并复核（**收尾硬性要求**）

```
$ adb -s emulator-5636 shell wm size 1366x768
$ adb -s emulator-5636 shell wm density 160
$ adb -s emulator-5636 shell wm size
Physical size: 3840x2160
Override size: 1366x768
$ adb -s emulator-5636 shell wm density
Physical density: 320
Override density: 160
```

✅ **已改回 `1366x768` / `160`，复核输出见上。**

---

## 2. 环境准备

```
$ adb -s emulator-5636 install -r -d research/deliver/opencode-shell-web.apk
Performing Streamed Install
Success

$ adb -s emulator-5636 reverse tcp:4095 tcp:4095
$ adb -s emulator-5636 reverse --list
host-19 tcp:4095 tcp:4095
```

首启凭据已存在，**未**触发 `showSetupDialog`：

```
I OcWebHost: facade registered as document-start script
I OcWebHost: home=http://127.0.0.1:43371/ backend=http://127.0.0.1:4095 passwordPresent=true
```

`passwordPresent=true` ⇒ 口令已落 EncryptedSharedPreferences，回环代理正常起（每次端口随机：36351 / 37505 / 34529 / 43371）。

---

## 3. 渲染判据与证据

### 3.1 a11y 树：**WebView 内容不进 a11y 树**（判据 A 失效）

```
$ adb -s emulator-5636 shell uiautomator dump /sdcard/ui_5636.xml
UI hierchary dumped to: /sdcard/ui_5636.xml
/sdcard/ui_5636.xml: 1 file pulled, 0 skipped. 0.6 MB/s (2255 bytes in 0.003s)
```

`xml_bytes=2255`，**`<node>` 计数 = 6**。Chromium 的可访问性节点未暴露到 uiautomator，
因此 **无法**用 uiautomator bounds 做触摸靶交叉验证（见 §9 未闭环 ③-附注）。

### 3.2 注入 JS 回传（判据 B，成立）

> 指定的 `--es oc_debug_eval` 通道**在本构建上是失效的**（`consumeDebugEval` 开头 `if (!BuildConfig.DEBUG) return`，
> 该 web 包虽名为 `.web.debug`，但 `BuildConfig.DEBUG` 编译期为 false，日志里查不到任何 `OcWebHost/Eval` 行）。
> 改用 **WebView DevTools(CDP)**：该 APK `flags=[ DEBUGGABLE ... ]` ⇒ WebView 自动开
> `webview_devtools_remote_<pid>` unix socket，`adb forward` 后走 CDP。
> 辅助脚本：`research/env/cdp_run.ps1`（支持任意 CDP 方法 + `Runtime.evaluate`）。

命中判据的原始回传：

```json
{"title":"OpenCode",
 "origin":"http://127.0.0.1:43371",
 "href":"http://127.0.0.1:43371/",
 "nodeCount":909,
 "metaViewport":"width=device-width, initial-scale=1, interactive-widget=resizes-content, viewport-fit=cover",
 "text200":"Home\n0\nAll projects\nToday\nO\nCounting from 1 to 4000, one number per line\nopencode-android-shell\nYesterday\nD\nFriendly greeting\nDSH ..."}
```

官方 UI 的前端资产确实在经回环代理加载（`logcat -s OcWebHost/Proxy`）：

```
I OcWebHost/Proxy: GET http://127.0.0.1:4095/_assets/nord-Cb4Vim4T.js
I OcWebHost/Proxy: GET http://127.0.0.1:4095/_assets/nushell-DHYfO66s.js
I OcWebHost/Proxy: GET http://127.0.0.1:4095/_assets/catppuccin-macchiato-8K6UbA2V.js
I OcWebHost/Proxy: GET http://127.0.0.1:4095/api/info
```

⇒ **官方 UI 已渲染**（判据 B：title/origin/DOM 节点数/可见文本 全部命中，且是真实上游 SPA 而非占位页）。

### 3.3 截图（5636 上能拿）

- `research/env/e5_phone_5636.png`（1080x2400，1277503 字节）
- 画面如实反映 §4 的阻塞：UI 卡片（"Counting from 1 to 4000, o…"）确实渲染出来了，
  **但被关在右上角一个被屏幕右缘裁掉的 freeform 窗口里**。

### 3.4 ★ 关键阻塞：窗口被卡在 freeform 模式（以及绕法）

进场后发现应用任务**始终在 freeform 模式**，与显示几何无关：

```
Task{fb27508 #121 ... mode=freeform translucent=false sz=1}
  mBounds=Rect(532, 27 - 835, 645)      # = 303 x 618 px
  mWindowingMode=freeform  mDisplayWindowingMode=freeform
```

这一度让 `window.innerWidth` 报 **303**（而非显示的 1366），差点被误读成"官方 UI 在 303dp 下是什么样"。

已尝试并**全部失败**的解法（都记在这里，避免下一路重复踩）：

| 尝试 | 结果 |
|---|---|
| `am force-stop` + 重新 `am start` | 仍 freeform |
| `settings put global enable_freeform_support 0` | 需 WM 重载，无效 |
| `pkill -f com.android.systemui` | `Operation not permitted` |
| `am force-stop com.android.systemui` | SystemUI 起来了（pid 变 756），但仍 freeform |
| `am start --windowingMode 1` | 仍 freeform |
| `am task resize 124 0,0,1366,768` | `NumberFormatException`（逗号/空格/`x` 三种分隔都试过，全被当成单个数字） |
| **重启 5636**（两次） | freeform 边界从磁盘恢复，**仍然 freeform** |
| 查桌面模式开关 | 无 `enable_desktop_mode`；`org.chromium.home.pc` 是**唯一 HOME**（ChromeOS 式桌面 shell + taskbar），它强制 freeform |

另外发现一个**与布局无关但会伪装成"没渲染"的坑**：设备当时**锁屏**（`isKeyguardShowing=true`），
`mCurrentFocus` 停在 `NotificationShade`，应用窗口 `Frames: ... ` 根本没布局 ⇒ `innerWidth=0`、`deClientW=0`。
`wm dismiss-keyguard` 之后才真正量到数。**下一路如果看到 `innerW=0`，先查 keyguard，不要以为页面挂了。**

**最终绕法（分两层，两层都是几何等价）**：

1. **显示层**：`wm size 1080x2400` + `wm density 420` ⇒ 显示逻辑 `411 x 914 dp`
   （JS 侧 `screen.width=412` / `dpr=2.625` 复核通过）；
2. **页面布局视口层**：因为 freeform 窗口宽度被 WM 钉死在 220dp（`wm density` 改它只会改高度，不会改宽度），
   改用 CDP 把**页面的布局视口**精确压到真机逻辑尺寸：

```
Emulation.setDeviceMetricsOverride {width:411, height:914, deviceScaleFactor:2.625, mobile:true}
→ {"innerW":411,"innerH":914,"dpr":2.625,"screenW":411,"screenH":915}   # 精确命中
```

⚠️ 因为 `meta viewport = width=device-width, initial-scale=1`，**本 WebView 里 1 CSS px === 1 dp**。
所以下面所有 CSS 像素读数**直接就是 dp**，不需要再除 density —— 这也让 §6 的触摸靶读数
天然不受本仓"uiautomator bounds 在 ~820↔1366 跳变"那个坑影响（本轮没用 uiautomator 取 bounds）。

---

## 4. 四个问题的逐条结论（含原始读数）

### ① 侧栏在窄屏的形态 → **整块从 DOM 里消失**（既不是收起、也不是抽屉、也不是挤在一起）

宽屏（首页 @1366x768）侧栏**存在**：

```json
{"tag":"aside","role":null,"cls":"mt-6 flex min-h-0 min-w-0 flex-col gap-4 overflow-h",
 "x":167,"y":101,"w":280,"h":658,
 "display":"flex","visibility":"visible","opacity":"1",
 "position":"sticky","transform":"none","vis":true}
```

窄屏（411x914）同一个首页上，**侧栏连节点都不存在**：

```json
{"innerW":411,"innerH":914,"aside":0,"nav":0,"roleNav":0}
```

`aside`/`nav`/`[role=navigation]` **计数全为 0**（不是 `display:none`、不是 `visibility:hidden`、不是移到视口外 ——
`probe_sidebar.js` 的 `overflowElements` / `offscreen` 扫描均为空）。
页面结构退化成 `header` + `main`：

```json
header  h-9  → x=1 y=1 w=409 h=36
main    flex-1 overflow-x-hidden → x=1 y=37 w=409 h=876
```

侧栏的内容被**摊平成主列**：

| 元素 | 位置/尺寸 @411 |
|---|---|
| `button[aria="Projects"]`（"All projects"） | `x=21 y=57 w=369 h=40` |
| `section[aria="Recent sessions"]` | `x=21 y=85 w=369 h=2539` |
| `div[aria="scrollable content"]` | `x=9 y=85 w=393 h=820` |

**并已排除"抽屉"**：页面上唯一的头部按钮是 `button[aria="Tabs"]`（387x28），对它 `click()` 后
DOM/可见文本**逐字未变**（`before` 与 `after` 的 `innerText` 相同，`offscreen=[]`），它不是抽屉开关。
即：**411dp 下没有侧栏，也没有能把它叫出来的抽屉入口**。

> 补充（容易误判的一点）：`aside` **只在首页**有。session 内页无论 1366 还是 411，`asideCount=0`。
> 所以"侧栏在窄屏消失"的准确表述是：**首页侧栏在 411dp 下被整体移除**；session 内页本来就没有侧栏。

### ② 键盘弹出后 composer 是否可见 / 被遮挡 → **未闭环（本轮没能测成）**

严格按要求**先断言再读数**，两次尝试都**没通过断言**，因此**没有**任何 `type=ime` 行可抄：

```
$ adb -s emulator-5636 shell dumpsys input_method | grep -E "mInputShown|mServedInputConnection="
  mInputShown=false
  mServedInputConnection=null
  mIsInputViewShown=true mStatusIcon=0

$ adb -s emulator-5636 shell dumpsys window insets | grep ime
(无任何输出 —— 根本不存在 type=ime 那一行)
```

已做的准备（都成功）：IME 存在并已选中

```
$ adb -s emulator-5636 shell ime list -a -s
com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
$ adb -s emulator-5636 shell ime set ...LatinIME
Input method ...: already enabled for user #0
Input method ...: selected for user #0
```

composer 本身定位到了（`contenteditable`，在真实 220x236dp 窗口内）：

```json
{"tag":"div","aria":"Prompt","x":21,"y":111,"w":178,"h":60}
```

**为什么测不成**：freeform 窗口只有 **236dp 高**，且处于桌面 shell 的 freeform 会话里，IME 不会为该窗口呈现
（`mIsInputViewShown=true` 但 `mInputShown=false`、`mServedInputConnection=null` 说明根本没有 input connection 被服务）。
⇒ **没有 ime frame 行，也就没有"composer bounds vs ime 上沿"的可比数据。** 详见 §9。

### ③ 关键控件触摸靶是否 ≥48dp → **不达标，但这是通用密度问题、不是窄屏回归**

**session 内页（两侧同屏对比，最干净的配对）**：

| 逻辑宽度 | 真实可交互元素 | **< 48dp 的个数** | 达标率 |
|---|---|---|---|
| 411 x 914 | 20 | **19** | 1/20 |
| 1366 x 768 | 23 | **22** | 1/23 |

411dp 下 `<48dp` 的原始读数（节选，完整见脚本输出）：

```json
{"button","aria":"Tabs",            "x":9,  "y":9,   "w":387,"h":28}
{"button",null,"txt":"Session",      "x":9,  "y":45,  "w":89, "h":35}
{"button",null,"txt":"Changes",      "x":98, "y":45,  "w":89, "h":35}
{"button",null,"txt":"Files",        "x":188,"y":45,  "w":89, "h":35}
{"button",null,"txt":"Terminal",     "x":277,"y":45,  "w":89, "h":35}
{"button","aria":"More options",    "x":372,"y":51,  "w":24, "h":24}
{"button","aria":"Jump to latest",  "x":193,"y":740, "w":26, "h":22}
{"button","aria":"Revert message",  "x":328,"y":36,  "w":24, "h":24}
{"button","aria":"Copy message",    "x":362,"y":36,  "w":24, "h":24}
{"button","aria":"Copy response",   "x":25, "y":145, "w":24, "h":24}
{"button","aria":"Add images and files","x":29,"y":857,"w":28,"h":28}
{"button",null,"txt":"MiMo-V2.6-Pro","x":61,"y":857, "w":161,"h":28}
{"button","aria":"Send",            "x":358,"y":859, "w":24, "h":24}
```

1366dp 下同一批按钮的尺寸：`24x24`、`28x28`、`36x28`、`20x20`、`26x22` —— **同样的亚 48dp 值**。

**首页 411dp**（96 个命中，其中 **46 个是 `w<=2 && h<=2` 的 1x1 代理 `span[role=button]`**，不是真实触摸靶）：

```json
{"degenerateProxies":46,"realInteractive":50,"realBelow48":3}
{"button","aria":"Tabs",            "x":9,  "y":9,  "w":387,"h":28}   # 28dp
{"button","aria":"Projects",        "x":21, "y":57, "w":369,"h":40}   # 40dp
{"input", "aria":"Search sessions", "x":57, "y":115,"w":325,"h":24}   # 24dp
```

首页的会话条目本身是合规的：`{"button","txt":"O Counting from 1 ","x":21,"y":189,"w":369,"h":48}` ⇒ **恰好 48dp**。

**结论**：**不达标**。但 19/20 与 22/23 的对比说明——**这是官方 UI 的一贯图标按钮密度问题（普遍 24–28dp），
不是手机窄屏引入的**。真正由窄屏"新增"的亚 48dp 控件是首页那 3 个（Tabs 28dp / Projects 40dp / Search 24dp）。

### ④ 有无横向溢出 → **无（两侧都没有）**

| 逻辑宽度 | `documentElement.scrollWidth` | `clientWidth` | 差值 | 越界元素 |
|---|---|---|---|---|
| **411 x 914** | **411** | **411** | **0** | `[]` |
| 1366 x 768 | 1366 | 1366 | 0 | `[]` |

```json
// @411
{"deScrollW":411,"deClientW":411,"deScrollH":914,"deClientH":914,
 "bodyScrollW":411,"bodyClientW":411,"horizOverflow":false,"deltaPx":0,"overflowElements":[]}
```

同时 `main` 自带 `overflow-x-hidden`（`"ov":"hidden auto"`），即使内容超宽也会被裁掉而不产生横向滚动条 ——
即"无横向溢出"在 411dp 有一部分来自裁剪而非自适应，**这一层不能当作"排版已适配"的证据**。

---

## 5. 与平板几何的并列对比（两侧均为本轮重新取数）

| 问题 | 1366 x 768 @160 | 411 x 914 @420 | 判读 |
|---|---|---|---|
| 布局视口 | `innerW=1366 innerH=768 dpr=1` | `innerW=411 innerH=914 dpr=2.625` | 均精确命中目标 |
| ① 侧栏 | 首页**有** `aside` 280x658 @x=167，sticky/visible | **0 个** aside/nav/role=navigation（节点不存在） | **整体移除**，非收起/抽屉/挤压 |
| ① 抽屉入口 | — | 头部唯一按钮 `Tabs` 387x28，click 后 DOM 零变化 | **没有**可唤出侧栏的抽屉 |
| ② IME/composer | 未测 | **未测成**（`mInputShown=false`，无 `type=ime` 行） | **未闭环** |
| ③ 触摸靶（session 内页） | 23 个真实元素 / **22 个 <48dp** | 20 个真实元素 / **19 个 <48dp** | 两侧同病，非窄屏回归 |
| ③ 触摸靶（首页） | — | 50 个真实元素 / **3 个 <48dp**（Tabs 28 / Projects 40 / Search 24） | 窄屏新增的少数几个 |
| ③ 会话条目按钮 | — | 369x48 ⇒ **恰好 48dp** | 达标 |
| ④ 横向溢出 | `1366 / 1366`，差 0 | `411 / 411`，差 0 | 两侧均无 |

---

## 6. 设备指纹

```
ro.product.name      = sdk_gpc_x86_64
ro.build.version.sdk = 34
ro.serialno          = EMULATOR36X6X11X0
uptime               = 11:50:02 up 6 min,  0 users,  load average: 0.51, 1.19, 0.76
包 com.xiufeigo.opencodeshell.web.debug:
  versionName    = 0.1.0-webhost
  lastUpdateTime = 2026-10-04 11:35:26     (本轮 install -r 的结果)
adb reverse     = host-19 tcp:4095 tcp:4095
```

### 我碰过哪些 serial

**只有 `emulator-5636`。** 全部 adb 命令都带 `-s emulator-5636`（唯二例外：开局和收尾各一次 `adb devices`
纯只读列举，以及 `adb -s emulator-5636 devices` 式列举）。`emulator-5730` / `emulator-5802`（E4 的手机模拟器）、
以及收尾时新出现的 `emulator-5570` / `emulator-5572` **一次都没有被下发过任何命令**。
未起停过后端（`127.0.0.1:4095` 全程由 Lead 托管，我只做 `adb reverse`）。

### 我对设备做过的、收尾已还原的改动

| 改动 | 原值 | 现值 | 状态 |
|---|---|---|---|
| `wm size` override | `1366x768` | `1080x2400` → 已改回 `1366x768` | ✅ 已还原并复核 |
| `wm density` override | `160` | `420` → 已改回 `160` | ✅ 已还原并复核 |
| `settings put global enable_freeform_support` | `1` | `0` → 已改回 `1` | ✅ 已还原（实测该开关在本机**无效**，freeform 由桌面 shell 决定） |
| `wm dismiss-keyguard` | 锁屏 | 已解锁 | 未还原（解锁是可逆的瞬时态；不建议再锁回去影响其他轮次） |
| 重启 | — | 重启 2 次 | 无法还原，也没必要 |

**辅助脚本**（都写在 `research/env/`，可复用）：
`cdp_run.ps1`（CDP 任意方法 + JS 求值）、`probe_geometry.js`、`probe_sidebar.js`、`probe_tree.js`、
`probe_drawer.js`、`probe_touch.js`、`probe_touch2.js`、`probe_5636.ps1`（已废弃：`oc_debug_eval` 通道失效）。

---

## 7. 未闭环 / 已知局限

1. **② 键盘遮挡完全没测成**（本轮唯一实质缺口）。`mInputShown=false` 且设备**根本不存在** `type=ime` inset 行，
   所以没有 ime 上沿、没有 composer-vs-ime 比较。根因是 freeform 窗口仅 236dp 高 + 桌面 shell 会话。
   → 需要一台**能全屏 + 能弹 IME** 的设备（真机，或一个非桌面 shell 的模拟器）才能闭环。
2. **①③④ 的布局视口靠 CDP `Emulation.setDeviceMetricsOverride` 造**（§3.4）。
   它精确命中 `411x914`，但它是**页面级视口模拟**，与"一个真实的 1080x2400 窗口"仍有差别：
   系统栏/状态栏的 inset、窗口装饰、真实 IME 都不在模拟范围内。**不能据此回答任何涉及系统 inset 的问题。**
3. **不是真机验证**。所有窄屏数字都是几何等价产物。
4. **a11y 树里没有 WebView 内容**（6 个 node），所以触摸靶只能用 CSS 像素读，
   **没有** uiautomator bounds 的独立交叉验证。好在 `width=device-width` 使 CSS px ≡ dp，两边本应同值。
5. **配对不完全对称**：411dp 的首页读数与 1366dp 的首页读数是同屏；触摸靶那张表用的是 **session 内页**两侧配对
   （都取自同一 session）。首页触摸靶只测了 411dp 一侧，1366dp 首页未取。
6. **"无横向溢出"存疑**：`main` 有 `overflow-x-hidden`，溢出被裁掉也算 0 差值。**不能据此说"排版已适配窄屏"。**
7. `wm density` 调不动 freeform 窗口的**宽度**（只会改高度），这是"必须上 CDP 绕法"的直接原因，
   留给下一路省时间。

---

## 8. 一句话回答本轮问题

**官方 UI 在 411dp 逻辑宽度下：侧栏整个从 DOM 消失（且没有抽屉入口能叫回来）、没有任何横向溢出、
但触摸靶只有首页 3 个 / session 内页 19 个不达 48dp（后者在平板宽度下同样不达标，属通用密度问题）；
键盘遮挡本轮未测成。**
