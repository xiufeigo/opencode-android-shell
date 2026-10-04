# NARROW_BOTTOMBAR_E7 — 窄屏「底部视图栏」到底渲染了没有？

- **执行日期**：2026-10-04（硬期限 2026-10-07 之前 ✅）
- **本轮实例**：`emulator-5824`（AVD `OC_Narrow_Test`，端口 5824，E6 自建，本轮沿用）
- **被测物**：`com.xiufeigo.opencodeshell.web.debug` / `WebHostActivity`（E6 装的 web 壳）
- **后端**：`http://127.0.0.1:4095`（Lead 托管，本轮**只做** `adb reverse tcp:4095 tcp:4095`，**未启停**）
- **本轮性质**：**只验证，不改代码**。未改 `app/**`、`webapp/**`、`_upstream/**`、`.github/**`。

---

## 一句话结论

# ✅ 判定：**与官方一致**（不是一致性缺陷）

**窄屏的视图切换栏渲染出来了 —— `data-slot="session-mobile-view-navigation"` 计数 = 1**，
4 个 tab（`Session` / `Changes` / `Files` / `Terminal`）+ 一个 `aria-label="More options"` 汉堡按钮全部在 DOM 里、可见、未被裁剪。
**⇒ E6 的结论①需要更正**：窄屏不是"什么都没有"。

**但有两处必须说清的更正/限定：**

1. **它渲染在顶部，不在底部。** 实测 `box = [9, 45, 394, 36]`，距视口底 **834 px**。W2 说的"底部视图栏"来自 `origin/v2`(`ecfa884`) 源码；**被服务的这份产物把它放在会话列的第 2 位（顶部）**。组件**渲染**是确定的；"位置在顶"是**这份产物自己的布局**，不是我们的宿主搬的。
2. **E6 没看到它的真正原因：E6 探的是首页。** 底栏的门控是 `session.identity.sessionKey()` —— **必须先进入一个会话**。首页（`/`，无 session id）实测 `count=0`，这是**符合上游设计**的，不算缺陷。

---

## 1. 实例健康读数（原始）

### 1.1 起始：本轮开工时 `emulator-5824` 已消失

```
$ adb devices -l
emulator-5604          device  product:sdk_gtablet_x86_64 model:Pixel_Tablet     ← 别人的，不碰
emulator-5636          device  ...                                                ← Lead 的，不碰
emulator-5730          device  ...
emulator-5802          device  ...
emulator-5824          device  product:sdk_gtablet_x86_64 model:Pixel_Tablet     ← 本轮目标

$ adb -s emulator-5824 shell getprop sys.boot_completed   -> 1
$ adb -s emulator-5824 shell pidof system_server           -> 580   @t0
$ adb -s emulator-5824 shell pidof system_server           -> 580   @t0+8s   稳定=True
$ adb -s emulator-5824 shell wm size      -> Physical 2560x1600 / Override 1080x2400
$ adb -s emulator-5824 shell wm density   -> Physical 320     / Override 420
$ adb -s emulator-5824 reverse --list     -> host-16 tcp:4095 tcp:4095
```

### 1.2 ⚠️ 实例在取证途中**静默退出 1 次**（本轮）

在下发 `am force-stop` + `am start`（准备平板 A/B）时，**5824 突然从 adb 消失**：

```
adb.exe: device 'emulator-5824' not found      ← 连续 5 条命令全部如此
```

`qemu` 进程随之消失、5824 端口释放（`Get-NetTCPConnection` 无监听）。
**与 E6 §7.1 记录的失败模式同型**（无 SIGABRT、无 tombstone、emulator 日志无崩溃行）。
本轮**共重新拉起 2 次**（第二次用于收尾恢复几何，见 §6）。

### 1.3 重新拉起后的健康读数（run2，最终状态）

```
PORT 5824 FREE - launching            20:56:14
[0s] state=error: device offline
[20s] state=device boot=1            BOOT_COMPLETED  (20s)
pidof system_server -> 577 / 577     stable=True (8s 间隔)
wm size    -> Physical 2560x1600  Override 1080x2400
wm density -> Physical 320       Override 420
dumpsys window displays
  Display: mDisplayId=0 (organized)
    init=2560x1600 320dpi mMinSizeOfResizeableTaskDp=220 base=1080x2400 420dpi cur=1080x2400 app=1080x2400
dumpsys activity activities
  * Task{... type=home ... mode=fullscreen translucent=false}
    mGlobalConfig={... sw411dp w411dp h914dp 420dpi ... mWindowingMode=fullscreen
      winConfig={ mBounds=Rect(0, 0 - 1080, 2400) mAppBounds=Rect(0, 0 - 1080, 2400) ...}}
Current WebView package: (com.google.android.webview, 124.0.6367.219)
reverse --list -> host-16 tcp:4095 tcp:4095
```

**收尾复读（run2 冷启后）**：`pidof system_server -> 576 / 576 stable=True` ✅

> ✅ **窗口模式 = `fullscreen`，不是 freeform**；系统自认 `w411dp h914dp 420dpi` —— 与 E6 同款真实全屏手机窗口。
> ✅ 全程**无 CDP 视口伪造**（未用 `Emulation.setDeviceMetricsOverride`）。

---

## 2. 渲染判据与证据（先说清"怎么算渲染出来"）

### 2.1 判据定义（结构化，不靠截图）

判「窄屏视图栏渲染出来了」= 以下**同时**成立：

| # | 判据 | 本轮实测 |
|---|---|---|
| J1 | DOM 里存在上游窄屏导航槽 `[data-slot="session-mobile-view-navigation"]` | **count = 1** ✅ |
| J2 | 其计算样式**未被隐藏**：`display≠none` / `visibility≠hidden` / `opacity≠0` | `display:flex, visibility:visible, opacity:1` ✅ |
| J3 | 有**非零尺寸**且**与视口有交集**（没被移到视口外、没被容器裁没） | `394×36 @ (9,45)`，距底 834px ✅ |
| J4 | 4 个 tab + 溢出按钮**逐个可枚举**（`data-slot=tabs-v2-trigger`） | 4 tab + 1 汉堡 ✅ |
| J5 | 断点前提成立：`matchMedia("(min-width:768px)").matches === false` | `false`（`max-width:767px` = `true`）✅ |
| J6 | 同一页面上**桌面侧栏确实不在**（`aside` 计数 0），证明这是窄屏分支而非两样并存 | `aside=0, aside#review-panel=0` ✅ |

### 2.2 前置：先确认"被服务的那份产物里确实有这个组件"

W2 只在**源码**和**落后 526 个提交的 dist** 上验过。本轮直接查 **Lead 后端 4095 实际下发的 bundle**（避免"版本落差"这个变量）：

```
$ curl -s http://127.0.0.1:4095/            -> HTTP 200, 6147 bytes
   <script type="module" crossorigin src="/_assets/index-Dadcbstg.js"></script>
   页面 root: <div id="root" class="flex flex-col h-dvh bg-v2-background-bg-deep p-px">
```

从 `index-Dadcbstg.js` 抽出 66 个 chunk 名，下载会话相关 chunk 后检索：

```
session-mobile-view-navigation   FOUND x1  in _assets/route-DvGOfv9b.js
min-width: 768px                FOUND x4  in _assets/route-DvGOfv9b.js
min-width: 768px（带空格变体）    FOUND x2  in _assets/route-DvGOfv9b.js
mobile-panel                    FOUND x3  in _assets/route-DvGOfv9b.js
review-panel                    FOUND x3  in _assets/route-DvGOfv9b.js
max-width: 767px                FOUND x4  in _assets/index-Dadcbstg.js / shell-DpX5RpQ-.js / file-DP41xs5-.js
```

bundle 里该组件的模板（原文摘录）：

```js
var MO = o(`<div class="relative flex shrink-0 items-center before:pointer-events-none before:absolute before:inset-x-0 before:bottom-0 before:h-px before:bg-v2-border-border-base ..." data-slot=session-mobile-view-navigation>`)
// tab 取值是硬编码四项：
U(B,{each:[`session`,`changes`,`files`,`terminal`], children: n => U(yi.Trigger,{value:n, ...})})
// 汉堡：
U(Z.Trigger,{as:Qt, get icon(){return U(H,{name:`menu`})}, variant:`ghost-muted`, class:`mx-1.5 shrink-0`,
             get "aria-label"(){return t.t(`common.moreOptions`)}})
```

> ✅ **被服务的产物里，窄屏栏是"硬编码 4 tab + 汉堡"，与面板是否声明 `mobile` 视图无关** ——
> 所以"窄屏下它没出现"**不能**用"会话没挂载任何带 mobile 视图的面板"解释（W2 §十-1 的这个猜测可以排除）。
> ⚠️ 注意 `MO` 的 class 里**没有任何定位到底部的样式**（只有一条 `before:bottom-0` 的 1px 分隔线伪元素），
> 说明**"放底部"从来不是这个组件自己的 CSS 职责**，位置由父级 flex 列决定。

---

## 3. ★ 底部区域元素枚举（原始结果）

### 3.1 首页 `/`（`innerH=915`，底部带 = CSS `[795, 915]`）—— 底栏**不在**，这是设计

```json
{
 "url": "http://127.0.0.1:40341/", "innerW": 412, "innerH": 915, "dpr": 2.625,
 "visualViewportH": 915, "scrollH": 915,
 "mq_desktop_768": false, "mq_mobile_767": true,
 "domTotal": 845, "visibleTotal": 355,
 "slot_session-mobile-view-navigation": { "count": 0, "nodes": [] },
 "slot_session-mobile-view-tabs":     { "count": 0, "nodes": [] },
 "slot_session-review-toggle":        { "count": 0, "nodes": [] },
 "aside":                              { "count": 0, "nodes": [] },
 "nav":                                { "count": 0, "nodes": [] },
 "bottomBandCount": 26
}
```

底部 120px 带里可见的东西**全是会话列表行**（按面积排序，前 6 条）：

| tag | data-slot | text | box [x,y,w,h] | position |
|---|---|---|---|---|
| div | `home-session-labels`(容器) | — | — | — |
| div | — | `D DSH… DSH-Remote` | `[21, 797, 370, 48]` | relative |
| **button** | — | `D DSH… DSH-Remote` | `[21, 797, 370, 48]` | static |
| div | — | `D DSH… DSH-Desktop` | `[21, 846, 370, 48]` | relative |
| **button** | — | `D DSH… DSH-Desktop` | `[21, 846, 370, 48]` | static |
| div | — | `F … foundation-contracts` | `[21, 895, 370, 48]` | relative |
| **button** | — | `F … foundation-contracts` | `[21, 895, 370, 48]` | static |

> ⇒ **首页底部带里没有任何 tab 条 / dock / 导航条**，只有会话行（48dp 高，达标）。
> ⇒ **E6 就是在这类页面上测的**（E6 报告里的 `headerButtons:[{aria:"Tabs", w:388,h:28}]` 正是首页顶部），
> **所以 E6 说"侧栏整体消失且什么都没有"在首页上是对的，但不足以代表窄屏整体。**

### 3.2 ★ 会话内页 `/server/…/session/ses_fd584a…`（`innerH=915`）—— 底栏**在**

```json
{
 "url": "http://127.0.0.1:40341/server/aHR0cDovLzEyNy4wLjAuMTo0MDM0MQ/session/ses_fd584a256ffe9eUzDLNmF5nFEd",
 "innerW": 412, "innerH": 915, "dpr": 2.625, "visualViewportH": 915,
 "mq_desktop_768": false, "mq_mobile_767": true,
 "domTotal": 578,
 "COUNTS": {
  "session-mobile-view-navigation": 1,     ← ★ 命中
  "session-mobile-view-tabs":     1,     ← ★ 命中
  "session-review-toggle":        0,
  "mobile-panel":                 0,
  "session-timeline-scroll":      1,
  "aside":                        0,     ← 桌面侧栏确实不在
  "aside#review-panel":           0,
  "nav":                          0
 },
 "MOBILE_NAV": {
  "box": [9, 45, 394, 36],
  "display": "flex", "visibility": "visible", "opacity": "1",
  "position": "relative", "zIndex": "auto",
  "text": "Session Changes Files Terminal"
 }
}
```

**它的子树逐个枚举**（J4）：

| # | tag | data-slot | aria-label | aria-selected | text | box [x,y,w,h] |
|---|---|---|---|---|---|---|
| 1 | button | `tabs-v2-trigger` | — | **`true`** | `Session` | `[9, 45, 90, 35]` |
| 2 | button | `tabs-v2-trigger` | — | `false` | `Changes` | `[99, 45, 90, 35]` |
| 3 | button | `tabs-v2-trigger` | — | `false` | `Files` | `[188, 45, 90, 35]` |
| 4 | button | `tabs-v2-trigger` | — | `false` | `Terminal` | `[278, 45, 90, 35]` |
| 5 | button | — | **`More options`** | — | （图标） | `[373, 51, 24, 24]` |

> ✅ `Session` 处于选中态（`aria-selected="true"`），说明这是一个**活的可交互 tab 列表**，不是静态残留。
> ✅ 汉堡按钮 `aria-label="More options"` = 上游 `common.moreOptions` —— **就是 W2 §4.3 说的"溢出菜单，抽屉在里面"的那个入口**。

**位置证据 —— 它在会话列的第 2 位（顶部），不是底部**：

```json
{
 "navFound": true,
 "innerH": 915, "innerW": 412,
 "MOBILE_NAV": { "topEdge": 45, "bottomEdge": 81, "distanceToViewportBottom": 834 },
 "parentChildCount": 4,
 "siblings": [
  { "i": 0, "isNav": false, "slot": "session-dropzone-blur", "box": [9, 45, 394, 861] },
  { "i": 1, "isNav": true,  "slot": "session-mobile-view-navigation", "box": [9, 45, 394, 36],
    "text": "Session Changes Files Terminal" },
  { "i": 2, "isNav": false, "slot": null, "box": [9, 81, 394, 709], "text": "…已修复…" },   ← 消息时间线
  { "i": 3, "isNav": false, "slot": null, "box": [9, 790, 394, 116], "text": "Ask anything, / for commands, …" }  ← composer
 ]
}
```

祖先链（说明它被谁定位）：

```
div[data-slot=session-mobile-view-navigation]  [9,45,394,36]  display:flex  flexDir:row  position:relative
└ div.relative.flex.min-h-0.flex-1.flex-col.overflow-hidden.rounded-[10px]  [9,45,394,861]  flexDir:column
  └ div[data-slot=session-chat-panel]  .@container.relative.z-10.min-w-0.shrink-0.flex.flex-col.min-h-0.h-full.flex-1  [9,45,394,861]
    └ div.relative.flex-1.min-h-0.flex.flex-col.**md:flex-row**.gap-2   [9,45,394,861]
      └ div.flex-1.min-h-0.flex.flex-col.gap-2.px-2.pb-[var(--shell-bottom-inset,8px)]…  [1,37,410,877]
        └ …  div.flex.size-full.flex-col  →  div#root.flex.flex-col.h-dvh  [0,0,412,915]
```

**这是上游自己的 `flex-col` 会话列**：第 1 个子元素是 `session-dropzone-blur`（一个 `position:absolute` 覆盖层，不占位），
所以**第一个真正占位的子元素就是窄屏栏** ⇒ 它必然落在列顶。

> ⚠️ **诚实标注**：W2 读的是 `origin/v2`(`ecfa884`=v2.0.22) 源码，把 `MobileViewTabs` 称作"底部视图栏"；
> **被服务的这份产物把它放在列顶**。"组件渲染"是确定的；"位置在顶"是这份产物（及/或其版本）的布局事实。
> 我**没有**去核对 `origin/v2` 的 `screen.tsx:282-296` 在 DOM 顺序上是否与本产物一致（**未取证**，见 §9）。

### 3.3 会话内页底部 140px 带枚举（**底部是 composer，不是导航条**）

```json
"bottomBandCount": 32
```
按面积排序，前 12 条（全宽 ≥ 80% 的用 `BOTTOM_BAND_WIDE` 同步给出，**同样只有这 12 个**）：

| tag | data-slot | aria | text | box [x,y,w,h] | pos | z |
|---|---|---|---|---|---|---|
| div | — | — | `Ask anything, / for commands, @ for cont…` | `[9, 790, 394, 116]` | static | auto |
| div | — | — | 同上 | `[9, 790, 394, 104]` | static | auto |
| div | — | — | 同上 | `[21, 790, 370, 104]` | relative | **70** |
| div | — | — | 同上 | `[21, 790, 370, 104]` | relative | auto |
| div | — | — | 同上 | `[21, 790, 370, 104]` | relative | **10** |
| div | — | — | 同上 | `[21, 790, 370, 104]` | static | auto |
| div | — | — | 同上 | `[21, 790, 370, 104]` | relative | auto |
| **form** | — | — | 同上 | `[21, 790, 370, 104]` | relative | auto |
| div | — | — | 同上 | `[21, 790, 370, 60]` | relative | auto |
| div | — | `scrollable content`(role=region) | 同上 | `[21, 790, 370, 60]` | static | auto |
| **div** | — | **`Prompt`**(role=textbox) | （空） | `[21, 790, 370, 60]` | relative | **10** |
| div | — | — | `MiMo-V2.6-Pro` | `[21, 790, 370, 44]` | static | auto |

> ⇒ **底部 140px 带里没有任何 tab 条 / dock / 导航条**，只有 composer（`role=textbox` aria=`Prompt`，模型选择 `MiMo-V2.6-Pro`）。
> 这与 §3.2 一致：导航条**不在底部**。
> （对照：E6 §5② 在首页/会话页量到的 composer 是 `[790,850]`，本轮 `[790,906]` —— 因为本轮会话页多了 45~81 那条栏，composer 被挤得更靠下。这是布局差异，**非缺陷**。）

### 3.4 全页"像 dock 的槽位"扫描（`dock|bottom|nav|tab` × 宽>100 高>20）

```json
"dockLikeSlots": [
 {"slot":"mobile-tabs-trigger",            "box":[9,9,388,28]},     ← 顶部标题栏里的 tab 切换（首页的）
 {"slot":"session-mobile-view-navigation",  "box":[9,45,394,36]},    ← ★ 窄屏视图栏（会话页）
 {"slot":"session-mobile-view-tabs",       "box":[9,45,358,36]},
 {"slot":"tabs-v2-list",                   "box":[9,45,358,36]},
 {"slot":"mobile-tabs-drawer",             "box":[12,951,388,157]},  ← y=951 > 915，**视口外、关闭态**
 {"slot":"mobile-tabs-drawer-list",        "box":[12,951,388,44]},
 {"slot":"vertical-tabs",                  "box":[12,951,388,44]},
 {"slot":"vertical-tabs-scroll",           "box":[12,951,388,44]},
 {"slot":"titlebar-tab-item",              "box":[12,951,388,44]},
 {"slot":"tab-link",                       "box":[18,951,376,44]}
]
```

> `mobile-tabs-drawer` 族在 `y=951`，**已在视口（915）之外** ⇒ 未打开的抽屉，不构成遮挡。
> 全页**没有任何** `data-slot` 含 `dock` 的元素。

### 3.5 可复现性：在**重新拉起的第二个实例**上复测，读数**逐字相同**

```
run2（pid 576，冷启后重新点进行会话）:
  "session-mobile-view-navigation": 1
  "MOBILE_NAV": { "box": [9,45,394,36], "display":"flex", "visibility":"visible", "opacity":"1" }
  children: Session[9,45,90,35] sel=true / Changes[99,45,90,35] / Files[188,45,90,35]
            / Terminal[278,45,90,35] / More options[373,51,24,24]
  domTotal: 578   (run1 也是 578)
  bottomBandCount: 32  (run1 也是 32)
```

⇒ **两次独立冷启 + 两次独立进入会话，结论一致**，非一次性偶发。

---

## 4. 顶部区域枚举（有没有打开侧栏的入口？）

### 4.1 会话内页顶部 120px 带（`headerButtonsTop120` 里的可交互项 + 关键结构）

```json
{ "header": { "tag":"header", "slot":"titlebar-v2", "box":[1,1,410,36], "text":"Home 0" },
  "button":{ "tag":"button","slot":"mobile-tabs-trigger","aria":"Tabs","box":[9,9,388,28],"text":"Home 0" },
  "span":  { "slot":"mobile-tab-title","text":"Home","box":[41,15,332,16] },
  "span":  { "box":[49,69,314,16],"text":"All projects" } }
```

**会话页的顶部区域枚举结果**（关键计数）：

| 选择器 | count | 结论 |
|---|---|---|
| `[data-slot="session-review-toggle"]`（W2 §6.1 的顶栏侧栏开关） | **0** | ❌ 窄屏无此入口（**与上游门控一致**） |
| `[data-slot="session-mobile-view-navigation"]` | **1** | ✅ 顶栏**下方** 9px 处就是窄屏栏 |
| `[data-slot="mobile-panel"]`（抽屉内容） | **0** | 抽屉未打开 |

### 4.2 判定

> **顶部没有任何"打开桌面侧栏"的入口**（`session-review-toggle` = 0，与 W2 §6.1 的源码门控 `<Show when={isDesktop() && …}>` 完全吻合）。
> 顶部唯一的按钮是 `mobile-tabs-trigger`（`aria="Tabs"`，388×28）—— 那是**标题栏的 tab/项目切换器**，不是侧栏入口。
> 窄屏的"侧栏替代路径"就是 §3.2 那条栏：**Session / Changes / Files / Terminal + More options**。

> ✅ **E6 结论①的后半句"没有抽屉"也需要限定**：**抽屉的入口在，而且可见**（`More options` 汉堡，`[373,51,24,24]`）。
> ⚠️ **但我没有点开它** —— 因此"抽屉点开后是否正常渲染 `data-slot=mobile-panel`"**本轮未取证**（见 §9-1）。

---

## 5. 截图

| 截图 | 路径 | 字节 | 内容（已肉眼确认） |
|---|---|---|---|
| **窄屏 412×915 会话内页（本轮核心证据）** | `research/env/e7_session_narrow_5824.png` | **380666** | 顶部标题栏下**一条 `Session | Changes | Files | Terminal | ☰`**，中部消息时间线，底部 composer（`MiMo-V2.6-Pro` + 发送键） |
| 平板 1366×768 会话内页 | `research/env/e7_session_tablet_5824.png` | 19394 | ⚠️ **页面未加载完**（只有标题栏 + 会话名 + 空 body），见 §7 |

> `screencap` 本轮**两次都成功**（E4 时期必崩的问题已不复现）。

---

## 6. 与平板几何对比（**做了，但平板侧只测到断点，没测到完整布局**）

### 6.1 平板侧读数（`wm size 1366x768` + `wm density 160`）

```json
{
 "innerW": 1366, "innerH": 768, "dpr": 1, "visualViewportH": 768,
 "mq_desktop_768": true,          ← 1366 >= 768，桌面分支
 "mq_mobile_767": false,
 "domTotal": 395,                 ← ⚠️ 偏小
 "COUNTS": {
  "session-mobile-view-navigation": 0,     ← ★ 桌面下窄屏栏正确地不出现
  "session-mobile-view-tabs":     0,
  "session-review-toggle":        0,
  "session-timeline-scroll":      0,
  "aside":                        0,     ← ⚠️ 侧栏也没出现（页面未加载完，见下）
  "textarea": 0, "[contenteditable]": 0
 },
 "MOBILE_NAV": null, "MOBILE_NAV_SUBTREE": null,
 "BOTTOM_BAND_140": [], "bottomBandCount": 0, "BOTTOM_BAND_WIDE": []
}
```

### 6.2 平板侧数据可信度：⚠️ **打折，如实声明**

截图 `e7_session_tablet_5824.png`（19 KB）显示**只有标题栏 + 会话名 + 空 body**，
与 `domTotal=395`、`[contenteditable]=0`、`bottomBandCount=0` 一致 ⇒ **平板侧页面在采样时还没渲染完**，
紧接着实例就静默退出了（§1.2）。

⇒ **平板侧只有一条读数是有效的**：`mq_desktop_768 = true` 且 `session-mobile-view-navigation = 0`，
即**上游 768px 断点在该几何下翻转、窄屏栏正确地不再渲染**。
⚠️ **平板侧的 `aside=0` 不可用作"平板也没有侧栏"的证据**（E6 在平板**首页**量到 `asideCount=1`；
本轮是在**会话页**且页面未加载完）。

### 6.3 两侧并列（**仅"断点"这一行是两侧都有完整数据的**）

| 项 | **1366 × 768 @160**（平板） | **412 × 915 @420**（手机，本轮核心） |
|---|---|---|
| `innerW × innerH` | `1366 × 768`，`dpr=1` | `412 × 915`，`dpr=2.625` |
| `matchMedia("(min-width:768px)")` | **`true`** | **`false`** |
| `matchMedia("(max-width:767px)")` | `false` | **`true`** |
| `session-mobile-view-navigation` | **0** ✅（桌面不该有） | **1** ✅（窄屏该有） |
| 页面加载完整度 | ❌ 未加载完 | ✅ 完整（`domTotal=578`，时间线+composer 齐） |
| `aside` | ⚠️ 0（不可信） | 0（可信：桌面侧栏在窄屏被门控掉） |

### 6.4 `wm` 收尾（**硬要求：改过必须改回并复核**）

```
# 切平板
$ adb -s emulator-5824 shell wm size 1366x768
$ adb -s emulator-5824 shell wm density 160
$ adb -s emulator-5824 shell wm size      -> Override size: 1366x768
$ adb -s emulator-5824 shell wm density   -> Override density: 160
# 平板取证途中实例静默退出 ⇒ 随后的 restore 命令报 not found

# 重新拉起（20:56:14 / 21:00:31 两次）后复核：
$ adb -s emulator-5824 shell wm size      -> Physical size: 2560x1600  /  Override size: 1080x2400
$ adb -s emulator-5824 shell wm density   -> Physical density: 320     /  Override density: 420
$ adb -s emulator-5824 shell "dumpsys window displays | head -3"
    init=2560x1600 320dpi ... base=1080x2400 420dpi cur=1080x2400 app=1080x2400
# 恢复后再次实跑验证：
  412x915 dpr=2.625 mq768=false        ← 窄屏视口正常
  session-mobile-view-navigation = 1   ← 结论在恢复后依然成立
```

✅ **已改回手机几何并复核**（`Override 1080x2400 / 420`，`app=1080x2400`，视口 `412×915`，`mq768=false`）。

---

## 7. 二选一判定

# ✅ 判定：**底部栏确实渲染了 ⇒ 与官方一致，不是缺陷**

### 7.1 结论①的原始证据（一句话）

```
session 内页 @412×915：
  [data-slot=session-mobile-view-navigation] count = 1
  box = [9, 45, 394, 36]，display:flex，visibility:visible，opacity:1，position:relative
  子节点：Session(sel=true) / Changes / Files / Terminal  各 90×35  +  More options 24×24
```

### 7.2 依据链

1. **产物侧**：Lead 后端 4095 实际下发的 `route-DvGOfv9b.js` 里，`session-mobile-view-navigation` 与
   `min-width: 768px` 门控**同时存在**（§2.2）⇒ **不是版本落差问题**，组件就在被服务的代码里。
2. **断点侧**：`matchMedia("(min-width:768px)") = false`（412 < 768）⇒ 上游自己的窄屏分支被激活。
3. **运行时侧**：`count=1` + 样式未被隐藏 + 有非零尺寸且在视口内 + 4 tab 逐个可枚举 + 选中态正确（§3.2）。
4. **对照侧**：平板几何下同一组件 `count=0`（§6.1）⇒ **它是响应式切换的，不是静态残留**。
5. **宿主侧**：`webapp` 没有任何把该元素移位/隐藏的代码 —— W2 §5.2 已对 `oc-host.js` 检索
   `display:none`/`aside`/`review-panel`/`session-mobile`/`matchMedia`/`innerWidth` **零命中**；
   本轮实测该元素 `position:relative`、`zIndex:auto`、`order:0`，**是父级 flex 列的第 1 个占位子元素**（§3.2）
   ⇒ 位置由上游自己的列顺序决定，**不是我们的 WebView 容器裁掉或遮挡的**。
6. **可复现**：两次独立冷启、两次独立进入会话，**读数逐字相同**（§3.5）。

### 7.3 ⇒ 需要更正的两处 E6 结论

| E6 原话 | 更正 |
|---|---|
| ①"侧栏在 412dp 下**整体从 DOM 消失**（**无抽屉入口**）" | **前半句对**（`aside=0` 成立）；**后半句要更正**：窄屏**有**替代导航（`Session/Changes/Files/Terminal` + `More options` 汉堡），**抽屉入口在**。 |
| ①隐含"窄屏**什么都没有**" | **不成立**：进入会话后顶部就有一条 394×36 的视图切换栏。**E6 没看到，是因为它探的是首页**，而底栏门控是 `session.identity.sessionKey()`（§3.1 首页 `count=0` 是设计，不是缺陷）。 |

### 7.4 如果要继续追，唯一还开着的一致性问号

> **"位置在顶"是否符合官方预期？** 本轮**未取证** `origin/v2`(`ecfa884`) 在 DOM 顺序上是否也把它放列顶。
> - 若 `origin/v2` 也放列顶 ⇒ 完全一致，无需任何动作。
> - 若 `origin/v2` 放列底而这份产物放列顶 ⇒ 那是**产物版本差异**，仍**不是我们宿主的问题**（宿主零布局代码），修法也不在我们这边。
> - **无论哪种，`webapp/**` 都不需要改一行。**

### 7.5 附带发现（与 E6 §5③ 触摸靶相关，值得回给 E6）

会话内页窄屏下的实测靶面：

| 控件 | 尺寸 (dp) | 判定（≥48dp） |
|---|---|---|
| `session-mobile-view-tabs` 整条 | 394 × **36** | ⚠️ 36dp |
| 单个 tab（Session/Changes/Files/Terminal） | 90 × **35** | ⚠️ 35dp |
| `More options` 汉堡 | 24 × **24** | ⚠️ 24dp |
| composer 输入区 `role=textbox` | 370 × 60 | ✅ |

⇒ **E6 §5③ 的"真实元素 <48dp 计数"是在首页上做的**；会话内页还多出这 5 个 <48dp 的窄屏导航元素，
E6 那张表应补这一行。（**只报告，未改任何代码。**）

---

## 8. 占位页是不是死代码？

### 8.1 `assets/www/index.html` 仍然是 W1 占位页 —— **确认未变**

```
$ Get-ChildItem webapp\src\main\assets\www
Name         Length
----         ------
index.html   9477        ← 仍是占位页
oc-host.js  12205
oc-perf.js   3307

$ (Get-Item webapp\src\main\assets\www\index.html).Length  -> 9477
index.html 第 5 行：<title>OpenCode Web Host — W1 登记表</title>
```

（`lang="zh-CN"`、`:root{--oc-ime-bottom:0px; --oc-window-height:0px; …}` 那一套自检变量，
确实是 W1 诊断页，**不是** opencode 应用本体。）

### 8.2 它在**路线 A（直连服务端 UI）下已经是死代码** —— 依据

**(a) 静态：它只有一个可达点，在代理起不来的兜底分支里**

`WebHostActivity.kt` 里 `LEGACY_PLACEHOLDER_URL`（= `https://appassets.androidplatform.net/assets/www/index.html`）
只出现 **2 处**，其中**唯一一处 `loadUrl`** 在 `launch()` 的失败兜底：

```kotlin
// WebHostActivity.kt:242-246
}.getOrElse {
    Log.e(TAG, "cannot start loopback proxy, falling back to placeholder", it)
    webView.loadUrl(LEGACY_PLACEHOLDER_URL)     // ← 唯一的加载点
    return
}
```

正常路径走的是 `WebHostActivity.kt:249-256`：

```kotlin
val origin = p.publicOrigin()
val url = if (restored != null && restored.startsWith(origin)) restored else "$origin/"
webView.loadUrl(url)                            // ← 实际加载的是代理 origin
```

（`HOME_URL` 虽返回同一个常量，但只用在 `WebHostActivity.kt:611` 的
`outState.putString(STATE_URL, webView.url ?: HOME_URL)` —— 仅当 `webView.url` 为 null 时的兜底默认值。）

**(b) 运行时：兜底分支本轮从未触发，且实际加载的是官方 UI**

```
$ adb -s emulator-5824 logcat -d | Select-String "falling back to placeholder"   -> 0 次
$ adb -s emulator-5824 logcat -d | Select-String "OcWebHost: home="  (最后一条)
  I OcWebHost: home=http://127.0.0.1:33185/ backend=http://127.0.0.1:4095 passwordPresent=true
# CDP 读回：
  document.title = "OpenCode"   @  http://127.0.0.1:33185     ← 官方 UI，不是 "OpenCode Web Host — W1 登记表"
  location.origin = http://127.0.0.1:33185                   ← 代理 origin，不是 appassets.androidplatform.net
```

> ✅ **判定：占位页在路线 A 下已是死代码**（仅"代理起不来"这一条降级路径还会用到它）。
> ⚠️ **它仍然被打进 APK**（`assets/www/` 三个文件都在），所以"死代码"指**运行时不可达**，不是"没被打包"。
> **本轮只核实，未删除、未修改。**

---

## 9. 未闭环 / 已知局限

| # | 项 | 状态 | 原因 |
|---|---|---|---|
| 1 | **抽屉本身是否点开正常** | ⚠️ **未取证** | 只确认了入口（`More options` 24×24 可见）。未 tap，故 `data-slot="mobile-panel"` 是否渲染**没测** |
| 2 | 平板侧完整布局对比 | ⚠️ **只测到断点** | 平板几何下页面未渲染完 + 实例随即静默退出（§6.2）。`mq768=true` / 窄屏栏 `count=0` 有效，其余无效 |
| 3 | 实例稳定性 | ⚠️ 本轮退出 1 次 | 与 E6 §7.1 同型（无 SIGABRT、无 tombstone）。**未取到根因，不宣称** |
| 4 | "位置在顶"是否符合 `origin/v2` | ⚠️ **未取证** | 未比对 `origin/v2:packages/app/src/session/screen.tsx:282-296` 的 DOM 顺序（§7.4） |
| 5 | 被服务产物的确切上游 commit | ⚠️ 未取 | 后端由 Lead 托管，我不启停；`4095` 无版本端点（`/api/global`、`/api/app` 均 401）。只能从 bundle 特征判断为 v2 线 |
| 6 | 会话内页窄屏下 `review.toggle` 键盘命令 | ⏭️ 未测 | 沿用 W2 §6.2 结论，未复测 |
| 7 | **不是真机** | ⚠️ 声明 | 模拟器上的真实全屏窗口（`mode=fullscreen`），但仍非真机 |
| 8 | CDP 输出的中文乱码 | ⚠️ 读数须知 | `Runtime.evaluate` 的 JSON 经 **GBK 控制台**打印，中文变乱码（如 `DSH����`）。**页面本身正常** —— 截图 `e7_session_narrow_5824.png` 里中文完好。结构化字段（tag/slot/box/aria/count）**不受影响** |

---

## 10. 我碰过哪些 serial

**下发过任何命令的 serial 只有一个：**

| serial | 归属 | 本轮对它做过什么 |
|---|---|---|
| **`emulator-5824`** | **自建（`OC_Narrow_Test`，E6 创建，本轮沿用）** | `reverse`、`wm size`/`wm density` 覆写（1080x2400@420 → 1366x768@160 → **改回 1080x2400@420**）、`am force-stop`、`am start`、`input tap`、`screencap`+`pull`、`pidof`、`getprop`、`dumpsys`、`logcat`、`reverse --list`、CDP `adb forward tcp:9222`；**`emu kill` 0 次**；**emulator 进程自行退出 1 次，由我在 5824 端口上重新拉起 2 次**（20:56:14、21:00:31） |

**零触碰清单（本轮未下发过任何命令）：**

```
emulator-5604   ← 作业中途新出现的第三方实例，非我所建，未碰
emulator-5636   ← Lead 的平板机，明确不碰
emulator-5730 / emulator-5802 / emulator-5570 / emulator-5572
```

* **未下载任何镜像**（只用既有 `system-images` 与既有 AVD）。
* **未对 `127.0.0.1:4095` 做任何启停** —— 全程只有 `adb reverse tcp:4095 tcp:4095`，后端由 Lead 托管。
* **未做** `adb kill-server` / `adb reconnect` / `adb -a` 等扰动全宿主机连接的操作。
* 另：只在本机 `127.0.0.1:4095` 上做过 **`curl` 只读**探测（未起停任何本地服务）；临时下载的 bundle 存在 `%TEMP%\oc_e7_assets`，**未删除**（本地安全策略禁止 CLI 永久删除），不在仓库内。

### 10.1 ⚠️ `git status` 里有一处**不是我改的**改动（如实声明）

```
$ git status --porcelain
 M research/env/Medium_Desktop_B_boot.log.out      ← 不是本轮产生的
?? research/env/NARROW_BOTTOMBAR_E7.md            ← 本轮
?? research/env/probe_e7.js                       ← 本轮
?? research/env/probe_e7_session.js               ← 本轮
?? research/env/probe_e7_dompos.js                ← 本轮
?? research/env/probe_e7_links.js                 ← 本轮
?? research/env/logs/e7_oc_narrow_boot.log.err    ← 本轮
?? research/env/logs/e7_oc_narrow_boot_run2.log.err  ← 本轮
```

`Medium_Desktop_B_boot.log.out` 是 **`Medium_Desktop_B` 这个 AVD 的启动日志**（diff 内容全是
`INFO | Ignore IPv6 address: …`），`LastWriteTime = 2026-10-04 20:54:48`。
**本轮我只拉起过 `OC_Narrow_Test`（5824），从未启动 `Medium_Desktop_B`，也从未写这个文件** ——
判断是**同机并行的其它工程师/Lead 的实例**在写这个被 git 跟踪的路径。
**本轮未触碰、未还原它**（还原会破坏别人的现场）。

⇒ 本轮**没有**碰 `app/**`、`webapp/**`、`_upstream/**`、`.github/**`（`git status` 可证：这些路径无任何条目）。

---

## 11. 本轮产出物

| 类别 | 路径 |
|---|---|
| **报告** | `research/env/NARROW_BOTTOMBAR_E7.md`（本文件） |
| 探针 JS | `research/env/probe_e7.js`（首页+通用底部/顶部带枚举）、`probe_e7_session.js`（会话页紧凑探针）、`probe_e7_links.js`（会话链接枚举）、`probe_e7_dompos.js`（DOM 位置/祖先链/兄弟顺序/dock 槽位扫描） |
| 截图 | `research/env/e7_session_narrow_5824.png`（**核心证据**）、`research/env/e7_session_tablet_5824.png`（平板侧，页面未加载完） |
| 启动日志 | `research/env/logs/e7_oc_narrow_boot.log`（20:56 那次）、`e7_oc_narrow_boot_run2.log`（21:00 那次） |

> 复用工具：`research/env/cdp_run_e6.ps1`（`-Serial` 默认已是 `emulator-5824`，**本轮未改动它**）。
> ⚠️ 踩坑记录：该脚本**必须用 `pwsh` 跑** —— `powershell.exe`（5.1）会把脚本里 UTF-8 中文注释按 GBK 解码，
> 直接 **ParserError**（`UnexpectedToken ')'`）。用 `pwsh -NoProfile -File ...` 正常。
