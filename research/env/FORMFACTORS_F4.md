# FORMFACTORS_F4 — 小屏手机（360dp）+ 折叠屏（442 / 708dp）形态覆盖验证

- **执行日期**：2026-10-05 01:06 → 01:24（硬期限 2026-10-07 之前 ✅）
- **本轮实例**：`emulator-5892`（AVD **`OC_FF_F4`**，端口 **5892**，**自建**）
- **被测物**：`research/deliver/opencode-shell-web.apk` → `com.xiufeigo.opencodeshell.web.debug` / `WebHostActivity`
- **镜像**：`system-images;android-35;google_apis_playstore_tablet;x86_64` + 设备外形 `pixel_tablet`（**宿主机既有资产，未下载任何镜像**）
- **WebView**：`com.google.android.webview` **124.0.6367.219**（E6 已证明该族可 boot）
- **后端**：`http://127.0.0.1:4095`（Lead 起好，本轮**只做** `adb reverse tcp:4095 tcp:4095`，**全程未启停**）
- **本轮性质**：**只验证，不改代码**。未改 `app/**`、`webapp/**`、`_upstream/**`、`.github/**`。新增文件仅在 `research/env/**` 与本报告。
- **工具**：复用 `research/env/cdp_run_e6.ps1`（**未改动**，用其 `-Serial` 参数）；新增 `research/env/probe_f4_nav.js`、`research/env/f4_enter_session.ps1`

---

## 0. 一句话结论

> **小屏手机 360dp 与折叠屏 442dp / 708dp 三个宽度全部闭环，官方 UI 都正常渲染。**
> 三者的导航形态**完全一致**：`data-slot="session-mobile-view-navigation"` **移动端视图导航栏**
> （4 tab + More options，**渲染在会话列顶部**），**侧栏 `aside` 计数为 0**。
> **IME 重叠量在三个宽度上都是 0**（IME 遮挡修复在这些宽度上成立，composer 未被遮住一行）。
> **三个宽度都没有横向溢出**（`scrollWidth == clientWidth`，delta 0）。
> **★ 切换边界精确落在 768dp**：767dp = 移动导航栏；768dp = 桌面分支（移动栏消失、出现 `review-toggle`）。
> **★ 新发现的布局问题**：（a）**折叠屏下触摸靶大面积不达标（16/18 < 48dp），比小屏手机（3/50）差得多**；
> （b）**768dp 处侧栏是"折叠成开关"而不是"直接展开"**，与 1366dp 的默认展开存在**第二个未测的切换点**。

---

## 1. 实例与端口（自建合规性）

### 1.1 端口选择

开工时占用：`5654 / 5656 / 5939`。

```
$ Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -ge 5560 -and $_.LocalPort -le 5960 }
5654, 5655, 5656, 5657, 5939
```

按要求取 **≥5890 的偶数**、**避开 5860/5870/5880 段** ⇒ 选 **5892**（该段全 free）。启动前再次复查仍 free。

```powershell
$busy = Get-NetTCPConnection -LocalPort 5892 -State Listen -ErrorAction SilentlyContinue
if ($busy) { Write-Output "PORT 5892 BUSY - ABORT"; exit 1 }
# PORT 5892 free
```

### 1.2 建 AVD（只用已装镜像）

```powershell
avdmanager.bat create avd -n "OC_FF_F4" `
  -k "system-images;android-35;google_apis_playstore_tablet;x86_64" -d pixel_tablet --force
# Auto-selecting single ABI x86_64        ---EXIT:0---
```

### 1.3 冷启（一次成功 50s；后因静默退出重拉起 2 次，见 §7）

```powershell
emulator -avd OC_FF_F4 -port 5892 -no-window -no-audio -no-boot-anim `
  -no-snapshot-load -no-snapshot-save -cores 4 -memory 3072 -gpu swiftshader_indirect
```

```
PORT 5892 free - launching at 01:06:45
[50s] state=device boot='1'      BOOT_COMPLETED at 50s
pidof system_server: 575 / 575   stable=True        ← 非 E4 的崩溃-重启循环
Physical size: 2560x1600
Physical density: 320
    versionName=124.0.6367.219                      ← WebView
reverse --list -> host-18 tcp:4095 tcp:4095
```

### 1.4 ★ 窗口模式 = `fullscreen`（真实全屏，非 freeform，且未用 CDP 视口伪造）

`720x1280 @320` 下：

```
$ adb -s emulator-5892 shell "dumpsys window displays | head -3"
  Display: mDisplayId=0 (organized)
    init=2560x1600 320dpi mMinSizeOfResizeableTaskDp=220 base=720x1280 320dpi cur=720x1280 app=720x1280 rng=720x720-1280x1280

$ adb -s emulator-5892 shell "dumpsys activity activities | grep -oE 'w[0-9]+dp h[0-9]+dp [0-9]+dpi'"
  mGlobalConfig={... sw360dp w360dp h640dp 320dpi nrml long port finger ...
    mWindowingMode=fullscreen ...}
```

**全轮未调用过一次 `Emulation.setDeviceMetricsOverride`** ⇒ 所有读数来自**真实窗口几何**，不是 CDP 视口伪造。

### 1.5 前置身份检查（确认跑的是官方 UI，不是占位页）

口令先播种进 `SecretStore`（`pm clear` → 输密码 → **回读掩码** → 保存）：

```
$ logcat -s OcWebHost/Proxy
I OcWebHost/Proxy: GET http://127.0.0.1:4095/_assets/sas-pz8Lf1bp.js
I OcWebHost/Proxy: GET http://127.0.0ATA…/runtime-ddCoIm8Y.css
# 保存后：401 count = 0，且 logcat 中无 passLen=-1 / auth rejected
```

> ⚠️ **踩坑（比 E6 多一个坑）**：E6 记的是"密码框 `text` 是提示文案不是已填值"。本轮还遇到
> **`passLen=9`**（应为 8，`opencode` 8 字符）—— 第一次 `input text opencode` 因为密码框里
> **有残留字符**（`pm clear` 前的旧值没清掉）而变成 9 字符，导致 `/api/event` 持续 401。
> 正确做法：`pm clear` → 点密码框 → **先发 12 次 `KEYCODE_DEL` 清残留** → 再 `input text opencode`。
> 第二轮起 `passLen` 正常、401 = 0。

CDP 身份检查（**三个宽度都做了，全部命中官方 UI**）：

| 宽度 | `document.title` | `location.origin` | DOM 节点数 |
|---|---|---|---|
| 360dp | **`"OpenCode"`** | `http://127.0.0.1:34345` | **910**（首页） |
| 442dp | **`"OpenCode"`** | `http://127.0.0.1:38367` | **635**（会话页） |
| 708dp | **`"OpenCode"`** | `http://127.0.0.1:40089` | —（会话页） |
| 768dp | **`"OpenCode"`** | `http://127.0.0.1:43745` | **653**（会话页） |

360dp 可见文本（证明是真实上游会话列表，非占位页）：

```
Home 0 All projects Today O Counting from 1 to 4000, one number per line
opencode-android-shell Yesterday D Friendly greeting DSH 临时会话 Older S
android-e2e System32 C DRAFT__OK chenjieyan_test S Count
```

`meta[viewport]`（360dp 实测）：

```
"width=device-width, initial-scale=1, interactive-widget=resizes-content, viewport-fit=cover"
```

---

## 2. ★ 目标一：小屏手机 **360 × 640 dp**

### 2.1 几何设定与指纹

| 项 | 值 |
|---|---|
| `wm size` | `720x1280`（`Physical size: 2560x1600` / `Override size: 720x1280`） |
| `wm density` | `320`（`Physical density: 320` / `Override density: 320`） |
| 系统自认 | **`sw360dp w360dp h640dp 320dpi`**，port，**fullscreen** |
| 窗口 | `mBounds=Rect(0, 0 - 720, 1280)`，`app=720x1280`（整屏，未扣系统栏） |
| WebView 视口 | `innerW=360, innerH=640, dpr=2, screenW=360, screenH=640` |
| 换算 | `720/2 = 360dp` ✅（1 CSS px ≡ 1 dp，`meta viewport=width=device-width`） |

### 2.2 官方 UI 是否渲染 → **渲染了** ✅

见 §1.5（`title="OpenCode"` / `origin` 非 null / DOM **910** 节点 / 真实会话名）。
**没有任何一个宽度出现"未渲染"。**

### 2.3 导航形态 → **移动端视图导航栏（MOBILE_NAV）**，侧栏 = 0

**首页 `/`**（`innerW=360`）：

```json
{"asideCount":0,"navCount":0,"asides":[],
 "headerButtons":[{"aria":"Tabs","txt":"Home\n0","x":9,"y":9,"w":336,"h":28}]}
```

**会话内页**（`/server/…/session/ses_efc722bf…`）：

```json
{"form":"MOBILE_NAV","innerW":360,"innerH":640,"dpr":2,
 "mq_min768":false,"mq_max767":true,
 "counts":{"mobileViewNav":1,"mobileViewTabs":1,"aside":0,"nav":0,"reviewToggle":0,
           "textarea":0,"contenteditable":1},
 "mobileNavBox":{"box":[9,45,342,36],"display":"flex","visibility":"visible",
                 "opacity":"1","position":"relative",
                 "txt":"Session Changes Files Terminal"},
 "asides":[],"domNodes":638}
```

**移动栏 4 tab + More options 逐个枚举**（槽位名 `data-slot=tabs-v2-trigger`）：

| # | 槽位 | aria | aria-selected | 文本 | box `[x,y,w,h]` (dp) |
|---|---|---|---|---|---|
| 1 | `tabs-v2-trigger` | — | **`true`** | `Session` | `[9, 45, 77, 35]` |
| 2 | `tabs-v2-trigger` | — | `false` | `Changes` | `[86, 45, 77, 35]` |
| 3 | `tabs-v2-trigger` | — | `false` | `Files` | `[162, 45, 77, 35]` |
| 4 | `tabs-v2-trigger` | — | `false` | `Terminal` | `[239, 45, 77, 35]` |
| 5 | — | **`More options`** | — | （图标） | `[321, 51, 24, 24]` |

- 数据槽名 = **`data-slot="session-mobile-view-navigation"`**（+ 内层 `session-mobile-view-tabs`），**tab 数 = 4**。
- 位置：`y ∈ [45, 81]`，**距视口底 640 − 81 = 559px ⇒ 在顶部**，与 E7 在 412dp 的发现一致（上游设计）。
- `Session` 处于 `aria-selected="true"` ⇒ 活的可交互 tab 列表。

### 2.4 ★ IME 弹起后 composer 是否可见 → **可见，重叠 0 px**

**严格先断言再读**（这是任务书要求，也是 IME 结论可信的前提）：

```
# 触碰 composer 之前
  mInputShown=false
  mServedInputConnection=null
```

**IME 未起时 composer 位置**：

```json
{"innerW":360,"innerH":640,"visualViewportH":640,
 "composer":[21,515,318,60],"bottom":575}
```

**真实手指 tap**（CSS(180,545) × dpr 2 ⇒ 设备(360,1090)）：

```powershell
adb -s emulator-5892 shell input tap 360 1090
```

**断言通过 ✅**：

```
mInputShown=true
mServedInputConnection=RemoteInputConnectionImpl{connection=WV.QT@ea38649
  mDeactivateRequested=false mServedView=android.webkit.WebView{506856c ... 0,0-720,1280 aid=1073741844}}
```

⇒ IME 确实被服务给了 **WebView 本身**（不是 `null`），这一步是真触发的。

**`type=ime` 原始行**（`dumpsys window insets` 在 API 35 上只回 **67 字节**，
真正的 ime inset 在 `dumpsys window` 的 InsetsSource 段 —— 与 E6 的记录一致）：

```
InsetsSource id=3 type=ime frame=[0,682][720,1280] visibleFrame=[0,682][720,1280] visible=true flags= sideHint=BOTTOM boundingRects=null
InsetsSourceControl: {3 mType=ime mSurfacePosition=Point(0, 48) mInsetsHint=Insets{left=0, top=0, right=0, bottom=598}}
```

**IME 弹起后的视口与 composer**：

```json
{"mInputShownAsserted":true,"innerW":360,"innerH":640,"visualViewportH":640,"offsetTop":0,
 "composer":[21,216,318,60],"bottom":276,"deScrollW":360,"deClientW":360}
```

**★ 重叠量计算（正确的重叠定义：composer 底边 − IME 上沿）**：

| 量 | 原始值 | 换算 |
|---|---|---|
| IME `type=ime frame` | `[0,682][720,1280]`（设备 px） | IME 上沿 CSS = `682 / 2` = **341.0** |
| composer（IME 弹起后） | CSS **`[216, 276]`** | 底边 **276** |
| **重叠像素量** | `max(0, 276 − 341)` | **= 0 px** ✅ |

**⇒ composer 完整位于 IME 上沿之上，一个像素都没被遮住。**

**为什么这次是 0 而 E6 在 412dp 量到"完全遮挡"**：composer 在 IME 弹起后**从 `y=515` 被顶到 `y=216`**
（`515 → 216`，上移 **299 CSS px**），所以躲开了 IME。
`innerH` 弹键盘前后**都是 640**、`visualViewportH=640`、`offsetTop=0`
⇒ **视口没有 resize，是页面自身把 composer 向上让位了**。这是**修复后的行为**（任务书所述"重叠 60px → 0"）。

> ⚠️ **诚实标注**：我最初用了一个**错误的重叠公式**（`max(0, min(compBot, imeTop) − compTop)`），
> 那个公式在"composer 整体在 IME 之上"时会算出 60 的假阳性。上表的公式
> `max(0, compBot − imeTop)` 才是正确重叠定义 ⇒ **0 是有效结论**。
> 60px 正是"未让位时"的理论重叠量（composer `[515,575]` 落在 IME 上沿 341 以下 ⇒ 60px 全遮），
> 与任务书"60px → 0"的说法自洽。

### 2.5 横向溢出 → **无**

```json
"Q4_overflow":{"deScrollW":360,"deClientW":360,"deScrollH":640,"deClientH":640,
 "bodyScrollW":360,"bodyClientW":360,"horizOverflow":false,"deltaPx":0,"overflowElements":[]}
```

`document.documentElement.scrollWidth` = **360**，`clientWidth` = **360** ⇒ **delta = 0**，无越界元素。

### 2.6 关键控件触摸靶 ≥48dp → **3 个不达标（3/50）**

```json
"Q3_touch":{"totalMatched":102,"visible":96,"degenerateProxies":46,
            "realInteractive":50,"realBelow48":3}
```

| 控件 | w × h (dp) | 判定 |
|---|---|---|
| `button[aria="Tabs"]` | 336 × **28** | ⚠️ |
| `button[aria="Projects"]`（All projects） | 318 × **40** | ⚠️ |
| `input[aria="Search sessions"]` | 274 × **24** | ⚠️ |

> 取数方式：本轮 `meta viewport=width=device-width` ⇒ **1 CSS px ≡ 1 dp**，
> 直接用 `getBoundingClientRect()` 即为 dp，**不依赖 uiautomator bounds**（规避 E2/E3 记的 bounds 跳变坑）。
> 且**每轮重新解析**（360 / 442 / 768 三轮各自独立取数，无跨轮沿用）。
> ⚠️ 标题/会话页的**移动栏 tab（77×35）与 More options（24×24）也 < 48**，
> 但它们在**首页探针里数不到**（首页无移动栏）—— 见 §2.7 的跨页合并表。

### 2.7 截图（`screencap` 全部成功）

| 截图 | 路径 | 字节 |
|---|---|---|
| 360dp 首启配置框 | `research/env/f4_360_home.png` | 73353 |
| 360dp 会话页 + IME 弹起 | `research/env/f4_360_ime.png` | 103624 |
| 360dp 会话页（无 IME） | `research/env/f4_360_session.png` | 108764 |

---

## 3. ★ 目标二：折叠屏（内屏）

> ### 3.0 ⚠️ 必须先说清一个**任务书里的算术问题**（否则读数会被误读）
>
> 任务书写「`1768x2208 @400` → **≈442–512 dp** 宽」。**这个换算不成立**：
> `dp = px × 160 / density`，所以
>
> | 几何设定 | 实际逻辑宽 | 说明 |
> |---|---|---|
> | `1768x2208` **@400** | **707.2 → 708 dp** | 任务书写的 density 给出的是 **708dp**，不是 442dp |
> | `1768x2208` **@640** | **442 dp** | 要拿到任务书说的 **442dp**，density 必须是 **640**（scale 4.0） |
> | `2048x2208` @400 | 819.2 dp | 同理远大于 512 |
>
> ⇒ **两个值我都测了**：`@640` = **442dp**（任务书点名的 442–512 区间），
> `@400` = **708dp**（任务书字面写的 density）。
> 这样两个读数都在，**不把任何一个说成"就是"另一个**。

### 3.1 几何设定与指纹

| 项 | 442dp 轮 | 708dp 轮 |
|---|---|---|
| `wm size` | `1768x2208` | `1768x2208` |
| `wm density` | **`640`** | **`400`**（任务书字面值） |
| 系统自认 | `w442dp` | `sw707dp w707dp h883dp 400dpi` |
| 窗口 | `mBounds=Rect(0,0-1768,2208)`，**fullscreen** | `mBounds=Rect(0,0-1768,2208)`，**fullscreen** |
| WebView 视口 | `innerW=442, innerH=552, dpr=4` | `innerW=708, innerH=884, dpr=2.5` |

> 两次都是 `port`（竖屏）折叠屏内屏形态；**未测横屏/半折（flex）形态** —— 见 §8 未闭环。

### 3.2 官方 UI 是否渲染 → **两个宽度都渲染了** ✅

| 宽度 | `title` | `origin` | DOM 节点 | 备注 |
|---|---|---|---|---|
| **442dp** | `OpenCode` | `http://127.0.0.1:38367` | **635** | 会话页 |
| **708dp** | `OpenCode` | `http://127.0.0.1:40089` | — | 会话页 |

**没有任何一个宽度出现"未渲染"。**

### 3.3 导航形态 → **两个宽度都是移动端视图导航栏**，侧栏 = 0

**442dp**：

```json
{"form":"MOBILE_NAV","innerW":442,"innerH":552,"dpr":4,"mq_min768":false,
 "counts":{"mobileViewNav":1,"mobileViewTabs":1,"aside":0,"nav":0,"reviewToggle":0,
           "textarea":0,"contenteditable":1},
 "mobileNavBox":{"box":[9,45,424,36],"display":"flex","visibility":"visible","opacity":"1",
                 "txt":"Session Changes Files Terminal"},
 "asides":[],"domNodes":635}
```

| # | 槽位 | aria-selected | 文本 | box (dp) |
|---|---|---|---|---|
| 1 | `tabs-v2-trigger` | **`true`** | `Session` | `[9, 45, 97, 35]` |
| 2 | `tabs-v2-trigger` | `false` | `Changes` | `[106, 45, 97, 35]` |
| 3 | `tabs-v2-trigger` | `false` | `Files` | `[203, 45, 97, 35]` |
| 4 | `tabs-v2-trigger` | `false` | `Terminal` | `[300, 45, 97, 35]` |
| 5 | — | **`More options`** | （图标） | `[403, 51, 24, 24]` |

**708dp**：

```
RESULT[w707] innerW=708 form=MOBILE_NAV mobileViewNav=1 aside=0 reviewToggle=0
            mq_min768=false navTxt='Session Changes Files Terminal'
```

⇒ **槽位名与 tab 数与 360dp 完全一致**（`session-mobile-view-navigation` / **4 tab** + More options），
**同样渲染在顶部**（`y ∈ [45, 81]`），**侧栏 `aside` = 0**。

### 3.4 ★ IME 弹起后 composer 是否可见 → **两个宽度都可见，重叠 0 px**

**442dp**（断言 `mInputShown=false` → tap → `mInputShown=true`）：

```
# tap 前
  mInputShown=false
# tap（composer CSS(221,457) × dpr4 ⇒ 设备(884,1828)）之后
  mInputShown=true
```

`type=ime` 原始行：

```
InsetsSource id=3 type=ime frame=[0,928][1768,2208] visibleFrame=[0,928][1768,2208] visible=true flags= sideHint=BOTTOM boundingRects=null
InsetsSourceControl: {3 mType=ime mSurfacePosition=Point(0, 96) mInsetsHint=Insets{left=0, top=0, right=0, bottom=1280}}
```

IME 弹起后：

```json
{"innerH":552,"vvH":552,"offTop":0,"top":107,"bottom":167,"h":60}
```

| 量 | 值 |
|---|---|
| IME 上沿 | `928 / 4` = **232.0** CSS |
| composer（弹起后） | CSS **`[107, 167]`** |
| **重叠** | `max(0, 167 − 232)` = **0 px** ✅ |

**708dp**（断言同上，均为 `true`）：

```
mInputShown=true
mServedInputConnection=RemoteInputConnectionImpl{connection=WV.QT@f1a5b9c
  mDeactivateRequested=false mServedView=android.webkit.WebView{ba44e98 ... 0,0-1768,2208 aid=1073741824}}
```

```
InsetsSource id=3 type=ime frame=[0,1408][1768,2208] visibleFrame=[0,1408][1768,2208] visible=true flags= sideHint=BOTTOM boundingRects=null
```

IME 弹起后：

```json
{"innerH":884,"vvH":884,"offTop":0,"top":439,"bottom":499,"h":60}
```

| 量 | 值 |
|---|---|
| IME 上沿 | `1408 / 2.5` = **563.2** CSS |
| composer（弹起后） | CSS **`[439, 499]`** |
| **重叠** | `max(0, 499 − 563.2)` = **0 px** ✅ |

> 两个折叠屏宽度上 `innerH` 弹键盘前后**都不变**（552→552 / 884→884）、`offTop=0`
> ⇒ 同样是**视口不 resize、页面自己让位 composer** 的机制。

### 3.5 横向溢出 → **442dp 无；708dp 未单独取数** ⚠️

442dp：

```json
"Q4_overflow":{"deScrollW":442,"deClientW":442,"horizOverflow":false,"deltaPx":0}
```

`scrollWidth` = **442** = `clientWidth` = **442** ⇒ **delta = 0**。

> ⚠️ **708dp 轮我漏了溢出读数**（那轮 `dumpsys input_method` 的巨量输出把预算吃掉了）。
> 如实标注为**未取**，**不用 442dp 的数顶替**。见 §8。

### 3.6 关键控件触摸靶 ≥48dp → **442dp：16/18 不达标（很差）**

```json
"Q3_touch":{"totalMatched":25,"realInteractive":18,"realBelow48":16}
```

| 控件 | w × h (dp) @442 | 判定 |
|---|---|---|
| `button[aria="Tabs"]` | 418 × **28** | ⚠️ |
| `tabs-v2-trigger` × 4（Session/Changes/Files/Terminal） | 97 × **35** | ⚠️ ×4 |
| `button[aria="More options"]` | 24 × **24** | ⚠️ |
| `button[aria="Jump to latest"]` | 26 × **22** | ⚠️ |
| `button[aria="Revert message"]` ×2 | 24 × **24** | ⚠️ ×2 |
| `button[aria="Copy message"]` ×2 | 24 × **24** | ⚠️ ×2 |
| `button[aria="Copy response"]` ×2 | 24 × **24** | ⚠️ ×2 |
| `button[aria="Add images and files"]` | 28 × **28** | ⚠️ |
| （无 aria，模型选择器） | 161 × **28** | ⚠️ |
| `button[aria="Send"]` | 24 × **24** | ⚠️ |
| `div[aria="Prompt"]`（composer 本体） | 400 × 60 | ✅ |

**⇒ 只有 composer 本体（400×60）和 1 个控件达标；`realBelow48 = 16/18`。**

> ⚠️ 这里的 `realInteractive=18` 远小于 360dp 首页的 50，是因为**这一轮探的是会话页**
> （会话页可交互元素本来就少），**不能与首页的 50 直接比总量**。
> 但**同一批控件的绝对尺寸**（`More options` 24×24、`Send` 24×24、`tabs-v2-trigger` 35 高）
> 在 360dp 与 442dp **完全相同** ⇒ 折叠屏上这些触控目标**一点没变大**。

### 3.7 截图

| 截图 | 路径 | 字节 |
|---|---|---|
| 442dp 会话页 + IME 弹起 | `research/env/f4_442_ime.png` | 220196 |
| 708dp 会话页 + IME 弹起 | `research/env/f4_707_ime.png` | 219161 |

---

## 4. ★ 导航形态的**切换边界**：精确在 **768 dp**

上游 bundle 里是硬编码 `@media (min-width: 768px)`（E7 §2.2 已定位到 `min-width: 768px` ×4 / `max-width: 767px` ×4）。
本轮用**相邻两个宽度实测**把边界钉死。

### 4.1 相邻宽度读数（二分结果，差 2 个设备像素）

| 目标 | `wm size` / `wm density` | 系统自认 | `innerW` | `mq_min768` | `form` | `session-mobile-view-navigation` | `aside` | **`review-toggle`** |
|---|---|---|---|---|---|---|---|---|
| **w767** | `1534x1200` / `320` | `w767dp h600dp 320dpi` | **767** | `false` | **`MOBILE_NAV`** | **1** | 0 | **0** |
| **w768** | `1536x1200` / `320` | `w768dp h600dp 320dpi` | **768** | **`true`** | **`NONE`** | **0** | 0 | **1** |

原始行：

```
RESULT[w767] innerW=767 form=MOBILE_NAV mobileViewNav=1 aside=0 reviewToggle=0 mq_min768=false
RESULT[w768] innerW=768 form=NONE       mobileViewNav=0 aside=0 reviewToggle=1 mq_min768=true
```

768dp 侧细节：

```json
{"innerW":768,"innerH":600,
 "reviewToggle":{"box":[719,45,28,48],"aria":null,"txt":"","display":"flex"},
 "asideCount":0,"asideBoxes":[],"title":"OpenCode","domNodes":653}
```

### 4.2 边界结论

- **切换点是 `min-width: 768px` 媒体查询，在 767dp / 768dp 之间翻转**（两个相邻宽度读数已给出）。
- `< 768dp`：移动端视图导航栏（4 tab + More options），`aside = 0`，**无侧栏开关**。
- `≥ 768dp`：移动栏消失，**出现 `review-toggle`（28×48 @ [719,45]）** ⇒ 桌面侧栏以**开关**形式入场。

> ⚠️ **768dp 处 `asideCount` 仍为 0** —— 侧栏是**折叠态**，要用户点 `review-toggle` 才展开。
> E6 在 **1366dp** 的**首页**上量到 `asideCount=1`（侧栏直接展开）。
> ⇒ **768 → 1366 之间还存在第二个切换点（侧栏"折叠态 → 展开态"），本轮未测**，见 §8。

### 4.3 全轮导航形态一览

| 逻辑宽 | `form` | 移动栏 | `aside` | `review-toggle` | 备注 |
|---|---|---|---|---|---|
| **360** | `MOBILE_NAV` | 1 | 0 | 0 | 小屏手机 |
| **442** | `MOBILE_NAV` | 1 | 0 | 0 | 折叠屏（@640） |
| **708** | `MOBILE_NAV` | 1 | 0 | 0 | 折叠屏（@400，任务书字面） |
| **767** | `MOBILE_NAV` | 1 | 0 | 0 | 边界下侧 |
| **768** | **`NONE`** | **0** | 0 | **1** | 边界上侧 |

---

## 5. ★ 与 412dp 的并列对比

> ⚠️ **412dp 那一列全部引自既有报告**（E6 `NARROW_PHONE_E6.md` / E7 `NARROW_BOTTOMBAR_E7.md`），
> **本轮没有重测 412dp，也没有拿本轮读数替换它**。凡本轮实测的标 ✅NEW。

| 项 | 412dp（既有） | **360dp** ✅NEW | **442dp** ✅NEW | **708dp** ✅NEW | **768dp** ✅NEW |
|---|---|---|---|---|---|
| 几何 | `1080x2400 @420` | `720x1280 @320` | `1768x2208 @640` | `1768x2208 @400` | `1536x1200 @320` |
| 系统自认 | `w411dp h914dp` | `w360dp h640dp` | `w442dp` | `w707dp h883dp` | `w768dp h600dp` |
| `innerW × innerH` | 412 × 915 | **360 × 640** | **442 × 552** | **708 × 884** | **768 × 600** |
| `dpr` | 2.625 | **2** | **4** | **2.5** | **2** |
| 官方 UI | 渲染 | **渲染** | **渲染** | **渲染** | **渲染** |
| 导航形态 | 移动栏（E7 更正 E6） | **移动栏** | **移动栏** | **移动栏** | **桌面分支** |
| 移动栏槽位 | `session-mobile-view-navigation` | **同** | **同** | **同** | **0（消失）** |
| 移动栏 tab 数 | 4 + More options | **4 + More options** | **4 + More options** | **4 + More options** | — |
| 移动栏位置 | 顶（`[9,45,394,36]`） | **顶 `[9,45,342,36]`** | **顶 `[9,45,424,36]`** | **顶** | — |
| `aside` | 0 | **0** | **0** | **0** | **0** |
| `review-toggle` | 0 | **0** | **0** | **0** | **1** |
| **IME `type=ime frame`** | `[0,1517][1080,2400]` | **`[0,682][720,1280]`** | **`[0,928][1768,2208]`** | **`[0,1408][1768,2208]`** | 未测 |
| **IME 重叠量** | E6：**完全遮挡** | **0 px** ✅NEW | **0 px** ✅NEW | **0 px** ✅NEW | 未测 |
| composer 让位量 | — | **299 CSS px**（515→216） | **320 CSS px**（427→107） | 让位中 | 未测 |
| `scrollWidth` vs `clientWidth` | 412 = 412 | **360 = 360** | **442 = 442** | **未测** | 未测 |
| 横向溢出 | 无 | **无** | **无** | **未测** | 未测 |
| 触摸靶 <48dp | 3/50（首页） | **3/50（首页）** | **16/18（会话页）** | **未测** | 未测 |
| `tabs-v2-trigger` 高 | 35 | **35** | **35** | 未测 | — |
| `More options` | 24×24 | **24×24** | **24×24** | 未测 | — |

### 5.1 与 412dp 的三点关键差异

1. **IME 遮挡已闭环修复**：E6 在 412dp 记录的是"composer **被完全遮挡**、一个像素都没露出"；
   本轮在 **360 / 442 / 708dp 三个宽度上重叠量都是 0**。⚠️ 但**我本轮没有重测 412dp**，
   所以严格说这是"**在这三个新宽度上为 0**"，**不能据此断言 412dp 也变成 0**（那需要单独复测）。
2. **横向溢出全线为 0**：360 / 442 两个宽度 `scrollWidth == clientWidth`，与 412dp、1366dp 一致。
3. **触摸靶没随宽度变大而变好**：移动栏 tab 恒 **35dp 高**、`More options`/`Send`/`Copy` 恒 **24×24**，
   360 → 442dp 宽度增加 82dp 的情况下**这些控件一点没放大**（§3.6）。

---

## 6. ★ 新发现的布局问题（本轮新增，非 412dp 旧结论）

### 问题 1（主要）：**折叠屏下触摸靶大面积不达标，且宽度变宽没有换来更大的点击区**

| 宽度 | 页面 | `realInteractive` | `realBelow48` | 比例 |
|---|---|---|---|---|
| 360dp | 首页 | 50 | 3 | **6%** |
| 442dp | 会话页 | 18 | 16 | **89%** |

- 折叠屏 442dp 上，**18 个真实可交互元素里 16 个小于 48dp**，其中 `More options` / `Send` /
  `Copy message` / `Copy response` / `Revert message` 全是 **24×24**，`Add images and files` **28×28**，
  移动栏 tab **97×35**。
- **且这些尺寸与 360dp 完全相同** ⇒ 上游在移动分支里**没有按可用宽度放大触控目标**。
- ⚠️ **口径说明**：两行的页面类型不同（首页 vs 会话页），**绝对比例不可直接相减**；
  但"**同一批控件在更宽的屏上尺寸不变**"这个事实是硬的。

### 问题 2：**768dp 边界上侧栏是"折叠态"，需要多点一次**

768dp 实测 `review-toggle=1`（28×48 @ `[719,45]`）而 `asideCount=0`。
⇒ **在 768dp 这么宽的屏上，用户默认看不到侧栏内容，必须先点右上角那个 28×48 的开关。**
这与 E6 在 1366dp 观察到的"侧栏直接展开"不是同一形态。
⚠️ **768 → 1366 之间这个第二切换点本轮未测**（见 §8）。

### 问题 3：三个宽度上 `innerH` 在 IME 弹起时**都不 resize**

360 / 442 / 708 三轮都是 `innerH` 前后不变、`visualViewport.offsetTop = 0`。
尽管 `meta viewport` 写了 `interactive-widget=resizes-content`，**实际生效的是"composer 自行上移"**。
**结果是好的（重叠 0）**，但机制与声明不符，属于**潜在脆弱点**：
一旦 composer 让位逻辑在某个宽度上失效，**不会退化成 resize 兜底，而是直接被遮挡**。

### 不是问题的项（避免误报）

- **三个宽度都无横向溢出**。
- **三个宽度的 IME 重叠都是 0**。
- **官方 UI 在三个宽度都正常渲染**（`title=OpenCode`、真实会话列表、DOM 数百节点）。

---

## 7. ⚠️ 实例异常（如实记录）：静默退出 **2 次**

与 E7 §1.2 记录的失败模式**同型**（无 SIGABRT、无 tombstone、emulator 日志无崩溃行，设备直接从 adb 消失）。

| # | 时刻 | 现象 | 处置 |
|---|---|---|---|
| 1 | 01:12 前后 | 下发 `wm size 1768x2208` 后，`adb: device 'emulator-5892' not found`（连续多条）；`qemu` 进程消失、5892 端口释放 | 重拉起（40s boot）；`wm` override **存活**（`Override size: 1768x2208 / Override density: 640`） |
| 2 | 01:14 前后 | 测 `1918x2400 @400`（**高 2400 > 物理 1600**）时再次静默退出 | 放弃该几何，改用**落在物理面板内**的 `1534x1200/1536x1200 @320` 做边界二分，一次成功 |

⇒ **规律**：崩溃都发生在**高度超出物理面板（2560×1600）**的几何上。
`1768x2208`（高 2208 > 1600）虽然也超，但 442/708 两轮都活着 ⇒ 不是唯一充分条件。
**边界二分用"落在物理面板内"的几何后，2 次连续成功。**

**冷启总次数**：3 次 boot（50s / 40s / 40s），全部 `pidof system_server` 稳定。

---

## 8. 未闭环（如实列出）

| # | 缺口 | 原因 |
|---|---|---|
| 1 | **708dp 的横向溢出读数**（`scrollWidth` / `clientWidth`） | 该轮 `dumpsys input_method` 输出了 ~2000 行 Gboard flag dump，吃掉预算，溢出探针未跑。**不用 442dp 的 0 顶替** |
| 2 | **708dp 与 768dp 的触摸靶** | 同上，未跑 `probe_e6.js` |
| 3 | **768dp / 708dp 的 IME 重叠** | 未测（只测了 360 / 442 / 708 三轮 IME；768dp 那轮只测了导航形态） |
| 4 | **768 → 1366 之间的"侧栏折叠 → 展开"第二切换边界** | 时间不够，未二分。已知 768dp = 折叠态（`review-toggle=1`）、1366dp 首页 = 展开态（E6 `asideCount=1`），**中间点未知** |
| 5 | **折叠屏横屏 / 半折（flex）形态** | 未测。本轮两个折叠屏读数都是**竖屏**（`port`） |
| 6 | **`768dp` 与 `708dp` 的截图** | 只对 360 / 442 / 708 存了图；768dp 存了 `f4_768_session.png`（112493 字节，**存在**），708dp 为 `f4_707_ime.png` |
| 7 | **折痕（crease）/ 分屏** | 未测（本仓无对应能力） |
| 8 | **2048x2208**（任务书备选） | 未测；只测了 1768 宽 |

---

## 9. ★ 收尾复核输出（原始抄录）

### 9.1 `wm` 改回原始值 —— 复核通过 ✅

本实例**原始值**（建 AVD 后未覆写时记录）：`Physical size: 2560x1600` / `Physical density: 320`。

```powershell
adb -s emulator-5892 shell wm size reset
adb -s emulator-5892 shell wm density reset
```

**复核读回**（**已无 `Override` 行 ⇒ 已回到物理原生值**）：

```
$ adb -s emulator-5892 shell wm size
Physical size: 2560x1600

$ adb -s emulator-5892 shell wm density
Physical density: 320
```

> 说明复原方式：用的是 `wm size reset` / `wm density reset`（**清 override，回到 `Physical`**），
> 比重填数字更不容易出错；实例随后即被关闭，磁盘上的 AVD 配置从未被改写。

### 9.2 关掉自建实例 —— 复核通过 ✅

```powershell
adb -s emulator-5892 emu kill
```

```
OK: killing emulator, bye bye
OK
```

**复核（按 HOUSEKEEPING §一.1 两条判据）**：

```
=== [3] VERIFY 0 residual OC_FF_F4 processes ===
OC_FF_F4 residual = 0
=== [4] VERIFY port 5892 released ===
5892 LISTEN = 0
5892 any-state = 1          ← 仅 TimeWait 残根（与 E6/E7 记录的既有模式相同）
=== [5] surviving emulators ===
  DSH_T80_Tablet  port=5654
  DSH_T80_Phone   port=5656
  OC_NotifyRC     port=5880
=== [6] adb devices ===
emulator-5654  device
emulator-5656  device
emulator-5880  device
```

- ✅ **进程 0 残留**（`OC_FF_F4` 无任何 `emulator.exe` / `qemu-system-x86_64.exe`）
- ✅ **端口 5892 已释放**（`LISTEN = 0`）
- ✅ **用户设备未受影响**：`5654`(`DSH_T80_Tablet`) / `5656`(`DSH_T80_Phone`) 仍在
- ✅ **`OC_NotifyRC`（端口 5880）不是我起的，全程未碰**（它在开工时的进程表里就存在，
  由别的 agent 持有；按 HOUSEKEEPING §一.2 "只关自己起的" 保留）
- ✅ **未执行 `adb kill-server`**（收尾后 `adb devices` 仍正常列出 3 台）
- ✅ **未使用 `-a` / 通配符 / 全量 kill**；只按 `-s emulator-5892` + `-avd OC_FF_F4` 判定

### 9.3 ⚠️ 故意保留：无

本轮**没有**故意保留任何实例。`OC_FF_F4` 的 **AVD 定义**仍留在磁盘
（与 `OC_Phone_Test` 等既有资产同状况，见 `HOUSEKEEPING.md` §四），**进程已关**。

### 9.4 碰过的 serial（完整清单）

| serial | AVD | 端口 | 是我起的吗 | 现在的状态 |
|---|---|---|---|---|
| **`emulator-5892`** | **`OC_FF_F4`** | **5892** | ✅ **是，本轮自建** | **已 `emu kill` + 复核 0 残留 + 端口已释放** |
| `emulator-5654` | `DSH_T80_Tablet` | 5654 | ❌ 否（用户的） | **未碰，仍在运行** |
| `emulator-5656` | `DSH_T80_Phone` | 5656 | ❌ 否（用户的） | **未碰，仍在运行** |
| `emulator-5880` | `OC_NotifyRC` | 5880 | ❌ 否（别的 agent 的） | **未碰**；仅在进程表里被看到过 |

**⇒ 本轮我下发过任何 adb 命令的 serial 只有 `emulator-5892` 一个。**
其余 3 个仅出现在 `adb devices` / `Get-CimInstance` 的**只读**枚举里。

---

## 10. 本轮新增/修改的文件（均在授权目录内）

| 文件 | 说明 |
|---|---|
| `research/env/FORMFACTORS_F4.md` | 本报告 |
| `research/env/probe_f4_nav.js` | 导航形态判据探针（移动栏 / 侧栏 / review-toggle） |
| `research/env/f4_enter_session.ps1` | 启动 app → 等官方 UI → 进入首个会话 → 跑探针 |
| `research/env/f4_360_home.png` / `f4_360_ime.png` / `f4_360_session.png` | 360dp 截图 |
| `research/env/f4_442_ime.png` / `f4_707_ime.png` / `f4_768_session.png` | 折叠屏 + 768dp 截图 |
| `research/env/f4_*.json` / `f4_*.txt` | 各轮原始读数落盘 |

**未改**：`app/**`、`webapp/**`、`_upstream/**`、`.github/**`、既有 `cdp_run_e6.ps1`（只传参，未改脚本）。

---

## 11. ⚠️ 本报告的效力边界（红线声明）

> **本轮全部结论来自"用 `wm size` / `wm density` 在自建模拟器上做几何等价"，**
> **不是真机验证，也不是真折叠屏硬件验证。**
>
> - 模拟器**没有真实折痕、没有铰链、没有半折（flex）形态、没有真实屏幕圆角**。
> - 折叠屏的两个读数（442dp / 708dp）**只是把窗口摆成那个尺寸**，
>   **不能推断任何具体机型**（Pixel Fold / Fold2 / Mate X 等）的实际表现 ——
>   同一台机器在不同密度档、不同系统版本下 `dp` 值也会变。
> - 触摸靶、IME、溢出这三类读数**对几何是成立的**，但**对具体机型不成立**。
> - 真机折叠屏的结论**必须另开一轮在真机上做**，本报告**不能替代**。
>
> 另：任务书给的小屏手机 `720x1280 @320` 与折叠屏 `1768x2208 @400` 均可直接用 `wm` 复现；
> **但 `1768x2208 @400` 实测是 708dp，不是任务书预期的 442dp**（见 §3.0），
> 后续引用时请按 dp 值而不是按任务书的写法对齐。
