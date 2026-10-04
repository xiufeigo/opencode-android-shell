# NARROW_PHONE_E6 — 手机形态闭环（E6：跑得稳的现代镜像 + 手机逻辑尺寸）

- **执行日期**：2026-10-04（硬期限 2026-10-07 之前 ✅）
- **本轮实例**：`emulator-5824`（AVD `OC_Narrow_Test`，端口 **5824**，**自建**）
- **被测物**：`research/deliver/opencode-shell-web.apk` → `com.xiufeigo.opencodeshell.web.debug` / `WebHostActivity`
- **后端**：`http://127.0.0.1:4095`（`adb reverse tcp:4095 tcp:4095`），口令 `opencode`（本轮新输入并保存）

> ## ✅ 一句话结论
> **手机形态这一半闭环了。** 用 **`android-35;google_apis_playstore_tablet`**（`pixel_tablet` 设备外形）
> 建了一台 `emulator-5824`，**71 秒 boot、`system_server` pid 稳定、官方 UI 真渲染、全屏非 freeform**，
> 并且 `innerWidth=412` 是**真实窗口自然得到的**（**不再需要 E5 的 CDP 视口伪造**）。
> 四个问题**全部拿到结论**：
> ① 侧栏在 412dp 下**整体从 DOM 消失**（无抽屉入口）；
> ② **composer 被 IME 完全遮挡**——但**平板几何下同样遮挡**，属官方 UI 通用缺陷、**非窄屏回归**
>    （E5 唯一没测成的项，本轮闭环）；
> ③ 触摸靶两侧都不达标，但**窄屏侧违规数反而更少**（3/50 vs 54/55），且**会话行在窄屏下由 40dp 变大到 48dp 达标**；
> ④ 两侧都无横向溢出。

---

## 1. 镜像选择与 boot 实测

### 1.1 为什么选这个镜像（沿用 E4 的硬根因 + E5 的 freeform 教训）

| 排除对象 | 排除理由（来自前情，不重踩） |
|---|---|
| `android-37.2;google_apis_playstore_ps16k` | E4 已定成硬根因：`/vendor/lib64/hw/mapper.ranchu.so` 断言 SIGABRT ⇒ 打死 surfaceflinger ⇒ `system_server` 崩溃循环 |
| `android-30;default` | E2 实测：WebView 11 解析不了现代 ESM ⇒ 官方 UI 纯白 |
| `android-34;android-desktop` | E5 的 `emulator-5636` 就是这个族（`sdk_gpc_x86_64` + ChromeOS 桌面 shell）⇒ **恒 freeform**，验不了真实全屏 |

**选定**：`system-images;android-35;google_apis_playstore_tablet;x86_64`，设备外形 `pixel_tablet`。

选择依据是**宿主机上的客观证据**（读 AVD 配置文件，未下发任何设备命令）：

```
--- Pixel_Tablet ---   hw.device.name=pixel_tablet   image.sysdir.1=system-images\android-35\google_apis_playstore_tablet\x86_64\
--- DSH_T63 ---        hw.device.name=pixel_tablet   image.sysdir.1=system-images\android-35\google_apis_playstore_tablet\x86_64\
--- T67_Tablet ---     hw.device.name=pixel_tablet   image.sysdir.1=system-images\android-35\google_apis_playstore_tablet\x86_64\
--- T72_Tablet ---     hw.device.name=pixel_tablet   image.sysdir.1=system-images\android-35\google_apis_playstore_tablet\x86_64\
--- MCRemote_Phone --- hw.device.name=pixel_8a       image.sysdir.1=system-images\android-37.2\...\ps16k\   ← E4 崩的那族
```

整个 `pixel_tablet` 外形族都落在 **API 35 平板镜像**上，而作业起点 `adb devices` 里的
`emulator-5572`（`product:sdk_gtablet_x86_64 model:Pixel_Tablet`）**正在稳定运行**
⇒ 该族是本机**唯一被证明能稳定跑现代 WebView 的镜像**。
**它同时避开 E4 的 ps16k 崩溃面和 E5 的 desktop freeform 面**（平板外形 ⇒ 无 ChromeOS 桌面 shell）。

> **未下载任何镜像**：`android-35` 目录是宿主机既有资产，`avdmanager create` 只做了本地引用
> （过程中打印的 `Fetch remote repository` 是**设备外形目录**的远程查询，与 E2 记录一致）。

### 1.2 建 AVD

```powershell
avdmanager create avd -n "OC_Narrow_Test" `
  -k "system-images;android-35;google_apis_playstore_tablet;x86_64" -d pixel_tablet --force
# Auto-selecting single ABI x86_64        ---EXIT:0---
```

| config.ini | 值 |
|---|---|
| `image.sysdir.1` | `system-images\android-35\google_apis_playstore_tablet\x86_64\` |
| `hw.device.name` | `pixel_tablet` |
| `hw.lcd.width` x `hw.lcd.height` | 2560 x 1600（平板原生） |
| `hw.lcd.density` | 320（平板原生） |
| `hw.ramSize` / `hw.cpu.ncore` | 2G / 4 |

### 1.3 端口

作业起点占用：`5570 / 5572 / 5636 / 5730 / 5802`。
逐个探测 `5820..5844` **全部偶数**（`Get-NetTCPConnection -State Listen`）⇒ **全 free**，
选 **5824**（≥5820 偶数，符合要求；未与任何人冲突）。
启动前再次复查仍 free。

### 1.4 冷启

```powershell
emulator -avd OC_Narrow_Test -port 5824 -no-window -no-audio -no-boot-anim `
  -no-snapshot-load -no-snapshot-save -cores 4 -memory 3072 -gpu swiftshader_indirect
```

```
PORT 5824 free - launching at 19:56:13
[   10s] state=error: device offline
[   30s] state=device boot_completed=''
[   61s] state=error: device offline        ← 早 boot 正常抖动
[   71s] state=device boot_completed='1'
BOOT_COMPLETED at 71s
```

模拟器自报：`INFO | Boot completed in 58898 ms`。

> **一次成功，0 次重试**（任务书允许 ≤2 次）。E4 在 ps16k 镜像上试了 6 次全崩；
> 本轮换到 35 平板镜像**首启即过**。

---

## 2. 实例健康诊断（原始读数）

### 2.1 `system_server` pid 稳定性（E4 崩溃循环探针）

```powershell
adb -s emulator-5824 shell pidof system_server   # 连读 4 次，每次间隔 8s
```

```
[0] pidof system_server = '574'  @19:57:45
[1] pidof system_server = '574'  @19:57:53
[2] pidof system_server = '574'  @19:58:01
[3] pidof system_server = '574'  @19:58:09
```

**pid 24 秒内零变化** ⇒ ❌ 不是 E4 的崩溃-重启循环（E4 是每 ~8s 变一次）。

### 2.2 服务与 surfaceflinger

```
service check activity : Service activity: found
service check window   : Service window: found
service check package  : Service package: found
service check input    : Service input: found

$ adb -s emulator-5824 shell cat /proc/uptime
111.15 141.44

$ adb -s emulator-5824 shell "dumpsys SurfaceFlinger | head -3"
Build configuration: [sf PRESENT_TIME_OFFSET=0 FORCE_HWC_FOR_RBG_TO_YUV=0 ...]
Display identification data:
```

（末行 `Failed to write while dumping service SurfaceFlinger: Broken pipe` 是 `head -3`
提前关管道导致的正常截断，**不是崩溃**。）

**logcat 全程无 `mapper.ranchu` 断言、无 `Fatal signal 6`** ⇒ E4 的硬根因在本镜像上**未复现**。

### 2.3 改前 / 改后几何（原始输出）

**改前（设备原生，未覆写）**：

```
$ adb -s emulator-5824 shell wm size
Physical size: 2560x1600
$ adb -s emulator-5824 shell wm density
Physical density: 320
```

**改成手机逻辑尺寸后**：

```
$ adb -s emulator-5824 shell wm size 1080x2400
$ adb -s emulator-5824 shell wm density 420
$ adb -s emulator-5824 shell wm size
Physical size: 2560x1600
Override size: 1080x2400
$ adb -s emulator-5824 shell wm density
Physical density: 320
Override density: 420
$ adb -s emulator-5824 shell "dumpsys window displays | head -4"
  Display: mDisplayId=0 (organized)
    init=2560x1600 320dpi mMinSizeOfResizeableTaskDp=220 base=1080x2400 420dpi cur=1080x2400 app=1080x2400 rng=1080x1080-2400x2400
```

`app=1080x2400`（应用可用区 = 整屏，**未扣除系统栏**）—— 这是与 E5 的 `app=1080x2274` 的关键差别。

---

## 3. ★ 窗口模式判定：**`fullscreen`，不是 freeform** ✅

```powershell
adb -s emulator-5824 shell "dumpsys activity activities | grep -E 'mode=|mWindowingMode'"
```

```
* Task{e934684 #8 type=standard A=10212:com.xiufeigo.opencodeshell.web.debug U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    mGlobalConfig={1.0 ?mcc0mnc [en_US] ldltr sw411dp w411dp h914dp 420dpi nrml long port finger qwerty/v/v -nav/h
      winConfig={ mBounds=Rect(0, 0 - 1080, 2400) mAppBounds=Rect(0, 0 - 1080, 2400) mMaxBounds=Rect(0, 0 - 1080, 2400)
      mDisplayRotation=ROTATION_0 mWindowingMode=fullscreen ...}}
    mOverrideConfig={... w411dp h914dp 420dpi ... mWindowingMode=fullscreen ...}
  CurrentConfiguration={... w411dp h914dp 420dpi ... mWindowingMode=fullscreen ...}
```

**判定（三条同时成立）**：

| 判据 | 读数 | 结论 |
|---|---|---|
| 任务模式 | `mode=fullscreen` | ✅ 非 freeform |
| 窗口模式 | `mWindowingMode=fullscreen` | ✅ 非 freeform |
| 配置 | `sw411dp w411dp h914dp 420dpi` | ✅ **系统自己认它是 411dp 宽的手机** |

**⇒ E5 §3.4 的 freeform 阻塞在本实例上完全不存在。** 应用任务拿到的是
`mBounds=Rect(0, 0 - 1080, 2400)` 的**整屏窗口**，与"真实全屏手机"同构。

> 这是本轮选镜像时最关键的一个判据：**平板外形 ≠ 桌面 shell**。
> E5 踩的坑是 `android-desktop` 镜像（`sdk_gpc`）自带 ChromeOS 桌面 shell 强制 freeform；
> 本轮的 `pixel_tablet` 外形**没有**那个 shell，所以 `wm size` 一改就真全屏。

---

## 4. ★ 官方 UI 是否渲染：**渲染了** ✅

### 4.1 判据 B（CDP 注入，命中）

> `research/env/cdp_run.ps1` 里 `$serial` 硬编码为 `emulator-5636`，
> 本轮克隆出 **`research/env/cdp_run_e6.ps1`**（新增 `-Serial` 参数，默认 `emulator-5824`），未改动 E5 原脚本。
> `--es oc_debug_eval` 通道在本构建上依旧失效（`BuildConfig.DEBUG=false`），故仍走 CDP。

```
# target: title=OpenCode url=http://127.0.0.1:44803/
```

| 判据项 | 原始回传 |
|---|---|
| `document.title` | **`"OpenCode"`** |
| `location.origin` | **`"http://127.0.0.1:44803"`** |
| `document.querySelectorAll('*').length` | **`909`** |
| `meta[viewport]` | `"width=device-width, initial-scale=1, interactive-widget=resizes-content, viewport-fit=cover"` |
| 可见文本前 200 字 | `Home 0 All projects Today O Counting from 1 to 4000, one number per line opencode-android-shell Yesterday D Friendly greeting DSH 临时会话 Older S android-e2e System32 C DRAFT__OK chenjieyan_test S Count ` |

**证据片段**（真实上游 SPA 会话列表，非占位页）：
`Counting from 1 to 4000, one number per line / opencode-android-shell`、`DRAFT__OK / chenjieyan_test`、
`android-e2e / System32`。

### 4.2 ★ 与 E5 最大的差别：布局视口是**自然**的，不是伪造的

```json
{"innerW":412,"innerH":915,"dpr":2.625,"screenW":412,"screenH":915,"docClientW":412,"docClientH":915}
```

E5 必须在 CDP 里下发 `Emulation.setDeviceMetricsOverride {width:411, height:914, deviceScaleFactor:2.625}`
才能拿到窄视口（因为 freeform 窗口被 WM 钉死在 220dp 宽）。
**本轮完全没用到任何视口覆写**——`1080x2400 @420` 的整屏窗口直接让 WebView 拿到
`412 x 915 @2.625`，与真机 `1080x2400 @420` 一致（`1080/2.625=411.4`，CSS 取整 412）。

⇒ **本轮四个问题的读数来自真实窗口，不是 CDP 几何等价。**

### 4.3 截图（`screencap` 本轮成功，E4 时期必崩）

| 截图 | 路径 | 字节 | 内容 |
|---|---|---|---|
| 官方 UI 首页（无键盘） | `research/env/e6_phone_5824.png` | **239703** | 真实会话列表，1080x2400 |
| session 内页 + IME 弹出 | `research/env/e6_ime_5824.png` | **245935** | 消息列表 + 全屏键盘，**composer 消失** |

两张图均已肉眼确认：首页渲染出真实会话名（`Counting from 1 to 4000…`、`DRAFT__OK`…），
**不是占位页**；IME 图中消息列表下方**直接是键盘，没有 composer**。

### 4.4 首启配置流（按 `WebHostActivity.kt` 的 `startProxyAndLoad()`）

首次连接时密码未入库，logcat 明锤（**这是正常的一步，随后补密码即消失**）：

```
I OcWebHost/Proxy: GET http://127.0.0.1:4095/_assets/soy-BBMyZ0-9.js
W OcWebHost/Proxy: no password in SecretStore -> /_assets/soy-BBMyZ0-9.js will 401 upstream
I OcWebHost/Proxy: auth rejected: GET http://127.0.0.1:4095/api/event -> 401; user=openc... passLen=-1 authHeaderSent=false
```

> **踩坑记录（值得下一轮注意）**：`uiautomator dump` 里密码框的 `text` 是**提示文案**
> （`…请输入用于固定会话的 opencode。`），**不是已填的值**。我第一次直接点「保存并连接」，
> 结果 `passLen=-1`、159 条 401。正确做法：`pm clear` 重来 → 点密码框 → `input text opencode` →
> **回读 dump 确认**（`text='????????'` 掩码）→ 再点保存。
> 保存成功后本轮 `will 401` 计数 = **0**。

---

## 5. 四个窄屏问题（逐条 + 原始读数）

### ① 侧栏形态 → **整块从 DOM 里消失**（既非收起、非抽屉、非挤压）

**窄屏 412×915（真实全屏窗口）**：

```json
{"asideCount":0,"navCount":0,"asides":[],
 "headerButtons":[{"aria":"Tabs","txt":"Home\n0","x":9,"y":9,"w":388,"h":28}]}
```

* `aside` / `nav` / `[role=navigation]` **计数全为 0** ⇒ **节点不存在**（不是 `display:none`、
  不是 `visibility:hidden`、不是移到视口外：`overflowElements:[]`、无越界元素）。
* 唯一头部按钮 `button[aria="Tabs"]` **388x28**，`Q4_overflow.overflowElements=[]`
  ⇒ 页面内无横向滚动把它藏在左边，**没有能唤出侧栏的抽屉入口**。

**平板 1366×768（同实例同轮）**：

```json
{"asideCount":1,"asides":[{"cls":"mt-6 flex min-h-0 min-w-0 flex-col gap-4 overflow-hidden",
  "x":167,"y":101,"w":280,"h":658,"display":"flex","visibility":"visible","opacity":"1",
  "position":"sticky","inViewport":true,"vis":true}]}
```

**⇒ 侧栏内容在 412dp 下被摊平成主列**（文本顺序 `Home 0 / All projects / Today …` 里
`Projects / Add project / Settings / Help` 这几项在 1366 是侧栏内容，在 412 已并入主列）。

> 与 E5 结论**一致**（E5 在 5636 上也测到 `asideCount=0`），但 E5 是 CDP 视口伪造，
> **本轮是在真实全屏窗口上复现**，证据等级更高。

### ② 键盘弹出后 composer 是否可见/被遮挡 → **被完全遮挡**（本轮新闭环，E5 未测成）

**严格先断言再读**：

```
# 触碰 composer 之前
  mInputShown=false
  mServedInputConnection=null

# composer 位置（IME 未起）
{"x":21,"y":790,"w":370,"h":60,"bottom":850,"innerH":915}
```

**真实手指 tap**（注意：设备像素 = CSS × dpr 2.625 ⇒ CSS(206,820) → 设备(541,2153)）：

```powershell
adb -s emulator-5824 shell input tap 541 2153
```

**断言通过 ✅**：

```
mInputShown=true
mServedInputConnection=RemoteInputConnectionImpl{connection=WV.QT@13b48 mDeactivateRequested=false
  mServedView=android.webkit.WebView{6089b52 ... 0,0-1080,2400 aid=1073741844}}
```

⇒ **IME 确实被服务给了 WebView 本身**（E5 时期是 `mServedInputConnection=null`，永远触发不了这一步）。

**`type=ime` 原始行**（API 35 上 `dumpsys window insets` 只回 67 字节，
真正的 ime inset 在 `dumpsys window` 的 InsetsSource 段）：

```
InsetsSource id=3 type=ime frame=[0,1517][1080,2400] visibleFrame=[0,1517][1080,2400] visible=true flags= sideHint=BOTTOM boundingRects=null
InsetsSourceControl: {3 mType=ime mSurfacePosition=Point(0, 63) mInsetsHint=Insets{left=0, top=0, right=0, bottom=883}}
imeLayeringTarget in display# 0 Window{baf69f2 u0 com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity}
imeInputTarget   in display# 0 Window{baf69f2 u0 com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity}
```

**IME 弹起后的视口与 composer**：

```json
{"innerW":412,"innerH":915,"screenH":915,"visualViewportH":915,"visualOffsetTop":0,
 "composer":{"x":21,"y":790,"w":370,"h":60,"bottom":850}}
```

**结论：composer 被完全遮挡。** 三条独立证据：

1. **算术**：IME 占 `y ∈ [1517, 2400]` 设备 px = CSS `[578.0, 915]`；
   composer 占 CSS `[790, 850]` ⇒ **整个 composer 落在 IME 带内，一个像素都没露出来**。
2. **页面没有 resize**：`innerH` 弹键盘前后**都是 915**，`visualViewportH=915`、`offsetTop=0`
   ⇒ 尽管 `meta viewport` 写了 `interactive-widget=resizes-content`，**实际没有缩放内容**。
3. **像素**：`e6_ime_5824.png` 里消息列表下方直接是键盘，**composer 不在画面上**。

> ⚠️ IME 高 **883 设备 px = 336.4 dp**，占 915dp 视口的 **36.8%**。
> 且 `mControl.mInitiallyVisible=false` + 有 `insets_animation` leash
> ⇒ 这是**动画进行中**的采样点；但 `visible=true` + 像素证据一致，遮挡结论成立。
>
> **对比 E2**：E2 在 android-30 上量到原生壳首启框**不被**遮挡（IME 顶 1124 vs 框底 844，差 280px）。
> 本轮是**官方 UI 的 composer**，结论相反 ⇒ **遮挡是官方 UI 的问题，不是原生壳的**。

**平板 1366×768 同实例同轮配对（用「New session」空会话，见 §7.1）**：

```
# 触碰 composer 之前
  mInputShown=false
  mServedInputConnection=null
# composer 位置（IME 未起，dpr=1 ⇒ CSS px == 设备 px）
{"innerW":1366,"innerH":768,"dpr":1,"composer":{"x":195,"y":643,"w":976,"h":60,"bottom":703}}
# 真实 tap (683,673) 之后
  mInputShown=true
  mServedInputConnection=RemoteInputConnectionImpl{connection=WV.QT@558160f ...
    mServedView=android.webkit.WebView{21c95f1 ... 0,0-1366,768 aid=1073741824}}

InsetsSource id=3 type=ime frame=[0,342][1366,768] visibleFrame=[0,342][1366,768] visible=true flags= sideHint=BOTTOM boundingRects=null
InsetsSourceControl: {3 mType=ime mSurfacePosition=Point(0, 24) mInsetsHint=Insets{left=0, top=0, right=0, bottom=426}}

# IME 弹起后
{"innerW":1366,"innerH":768,"dpr":1,"composer":{"x":195,"y":643,"w":976,"h":60,"bottom":703}}
```

**★ 关键修正：平板侧 composer 同样被完全遮挡。**
IME 占 `[342, 768]`（高 426 dp），composer 占 `[643, 703]` ⇒ 同样整个落在 IME 带内；
`innerH` 弹键盘前后也都是 **768**（同样不 resize）。

| | 手机 412×915 | 平板 1366×768 |
|---|---|---|
| IME `type=ime` 原始行 | `frame=[0,1517][1080,2400]` | `frame=[0,342][1366,768]` |
| IME 高度 | 883 设备 px = **336.4 dp** | 426 px = **426 dp** |
| IME 上沿（CSS） | 578.0 | 342 |
| composer（CSS） | `[790, 850]` | `[643, 703]` |
| composer 是否露出 | ❌ **0 px** | ❌ **0 px** |
| `innerH` 是否 resize | ❌ 915 → 915 | ❌ 768 → 768 |

⇒ **② 的结论是：composer 被 IME 完全遮挡，且这是官方 UI 的通用缺陷，
在平板几何下同样发生，**不是**手机窄屏引入的回归**。
（IME 占比：手机 336/915 = 36.8%，平板 426/768 = 55.5% —— 平板反而更严重。）

### ③ 关键控件触摸靶 ≥48dp → **两侧都不达标**（但主因不是窄屏）

> 说明：本轮 `meta viewport = width=device-width`，故 **1 CSS px ≡ 1 dp**，直接用
> `getBoundingClientRect()` 读数即为 dp，**不需要除 density**。
> 这同时**天然规避**了 E2/E3 记的「uiautomator bounds 在 ~820↔1366 跳变」那个坑
> ——本轮**不依赖 uiautomator bounds**，且**每一轮都重新取数**（下表两次读数分别来自
> 手机轮与平板轮两次独立 CDP 探测，无跨轮沿用）。

**首页 @ 412×915**：

| 控件 | w × h (dp) | 判定 |
|---|---|---|
| `button[aria="Tabs"]` | 388 × **28** | ⚠️ 28dp |
| `button[aria="Projects"]`（All projects） | 370 × **40** | ⚠️ 40dp |
| `input[aria="Search sessions"]` | 326 × **24** | ⚠️ 24dp |

```json
{"totalMatched":102,"visible":96,"degenerateProxies":46,"realInteractive":50,"realBelow48":3}
```

**首页 @ 1366×768（同实例同轮）**：

```json
{"totalMatched":103,"visible":101,"degenerateProxies":46,"realInteractive":55,"realBelow48":54}
```

| 控件 | w × h (dp) @1366 | 同一个控件 @412 |
|---|---|---|
| `button[aria="Home"]` | 36 × 28 | （并入 `Tabs` 388×28） |
| `button[aria="New session"]` | 28 × 28 | — |
| `button[aria="Add project"]` | 28 × 28 | — |
| 侧栏 `Add project` / `Settings` / `Help` | 268 × 28 | —（侧栏已消失） |
| `input[aria="Search sessions"]` | 676 × **24** | 326 × **24** |
| **会话行按钮** | 720 × **40** | 370 × **48** ✅ |
| `div[aria="scrollable content"]` | 280 × 28 | — |

**判读**：
* 严格按「≥48dp」：**两侧都不达标**（412 侧 3/50，1366 侧 54/55）。
* **但真正由窄屏"新增"的只有 3 个**（Tabs 28 / Projects 40 / Search 24）；
  1366 侧那 54 个里**绝大多数是同一批侧栏条目与会话行**（`h=40`），
  它们在 412 侧要么消失、要么变成 **48dp 反而达标**。
* **反直觉但重要的一点**：**窄屏下会话行从 40dp 变成 48dp（达标）**，
  说明官方 UI 对窄屏做了**加大点击区**的处理；不达标集中在**头部图标按钮（24–28dp）**，
  属**贯穿两种几何的通用密度问题**。
* `degenerateProxies=46`（两侧同值）是 `w<=2 && h<=2` 的 1x1 代理 `span[role=button]`，
  **不是真实触摸靶**，已排除。

### ④ 横向溢出 → **无**（两侧都没有）

| 逻辑尺寸 | `documentElement.scrollWidth` | `clientWidth` | 差值 | 越界元素 |
|---|---|---|---|---|
| **412 × 915** | **412** | **412** | **0** | `[]` |
| 1366 × 768 | 1366 | 1366 | 0 | `[]` |

```json
// @412
{"deScrollW":412,"deClientW":412,"deScrollH":915,"deClientH":915,
 "bodyScrollW":412,"bodyClientW":412,"horizOverflow":false,"deltaPx":0,"overflowElements":[]}
```

⚠️ 沿用 E5 的保留意见：官方 UI 的 `main` 自带 `overflow-x-hidden`，
**溢出被裁掉也算 0 差值** ⇒ 「无横向溢出」**不能**当作"排版已自适应"的证据。

---

## 6. 与平板几何并列对比（**两侧均为本轮重新取数**）

| 问题 | **1366 × 768 @160**（平板） | **412 × 915 @420**（手机） | 判读 |
|---|---|---|---|
| 窗口模式 | `mode=fullscreen` | `mode=fullscreen` | 两侧都是真实全屏窗口 |
| 布局视口来源 | 真实窗口 `innerW=1366 dpr=1` | 真实窗口 `innerW=412 dpr=2.625` | **两侧均无 CDP 视口伪造** |
| ① 侧栏 | `asideCount=1`，**280×658 @x=167**，sticky/visible/inViewport | **`asideCount=0`**（节点不存在） | **整体移除**，非收起/抽屉/挤压 |
| ① 抽屉入口 | 头部 `Home 36×28` + `New session 28×28` | 头部仅 `Tabs 388×28`，无唤出侧栏的入口 | **无** |
| ② IME 断言 | `mInputShown=false` → `true`，ime 帧 `[0,342][1366,768]` | `mInputShown=false` → **`true`**，ime 帧 `[0,1517][1080,2400]` | 两侧都成功弹出 IME 并服务给 WebView |
| ② composer 遮挡 | composer `[643,703]` ⊂ IME `[342,768]` ⇒ **完全遮挡** | composer `[790,850]` ⊂ IME `[578,915]` ⇒ **完全遮挡** | **两侧同病，不是窄屏回归** |
| ② `innerH` 是否 resize | ❌ 768 → 768 | ❌ 915 → 915 | 两侧都不 resize（尽管 meta 声明 `interactive-widget=resizes-content`） |
| ② IME 占比 | 426 / 768 = **55.5%** | 883 / 915 = **36.8%** | 平板侧占比更高 |
| ③ 触摸靶（首页） | 55 个真实元素 / **54 个 <48dp** | 50 个真实元素 / **3 个 <48dp** | 窄屏侧反而更少 |
| ③ 会话行按钮 | 720 × **40** | 370 × **48** | **窄屏下变大并达标** |
| ③ 头部图标 | 28dp | 28dp | 两侧同病（通用密度问题） |
| ④ 横向溢出 | `1366 / 1366`，差 0 | `412 / 412`，差 0 | 两侧均无 |

---

## 7. 未闭环 / 已知局限

| # | 项 | 状态 | 原因 |
|---|---|---|---|
| 1 | ② IME/composer 遮挡 | ✅ **已闭环（两侧）** | 见 §5 ②：**两侧 composer 都被完全遮挡** |
| 2 | 触摸靶**无 uiautomator bounds 交叉验证** | ⚠️ 打折 | WebView 内容不进 a11y 树（E5 已记录，本轮同样：dump 仅 2249 字节 / 6 node）。所幸 `width=device-width` 使 CSS px ≡ dp，两侧同值 |
| 3 | 平板侧 ② 用的是**空会话**不是大会话 | ⚠️ 配对不对称 | 见 §7.1：1366 几何下打开 4000 行大会话会打死模拟器。composer 本身是同一个组件，但所在会话内容不同 |
| 4 | 实例在加载超大历史会话时**静默退出** | ⚠️ 已定位到触发条件 | 见 §7.1 |
| 5 | 「无横向溢出」的证明力 | ⚠️ 打折 | `main` 有 `overflow-x-hidden`，裁剪也算 0 差值（同 E5） |
| 6 | **不是真机** | ⚠️ 声明 | 本轮是**模拟器上的真实全屏窗口**（已比 E5 的几何等价前进一大步），但仍非真机；IME 高度（336dp / 426dp）取决于模拟器的 **LatinIME**，真机可能不同 |
| 7 | `android-37.2 ps16k` 镜像 | ⏭️ 本轮未碰 | E4 已定死根因（`mapper.ranchu.so`），本轮不再重试 |
| 8 | `emulator-5730` / `emulator-5802`（E2/E4 遗留） | 未触碰 | 本轮无需要；E2 的 WebView 11、E4 的崩溃循环结论仍成立 |
| 9 | 官方 UI 内部页（非首页）的窄屏表现 | ⚠️ 部分 | 手机侧 session 内页只量了 composer 与 ②，①③④ 的完整配对做在首页上；大会话在手机侧可打开（run1 成功），在平板侧会打死实例（见 §7.1） |

### 7.1 实例为什么会死（诚实记录）

**现象**：本轮实例 `emulator-5824` **静默退出 2 次**，两次都发生在
**平板几何（1366×768）+ 点击进入会话** 的瞬间：

* **第 1 次**（run1，手机侧四问全部取完、切到平板几何、点会话）：`adb` 报 `device 'emulator-5824' not found`。
  诊断：qemu 进程消失、5824 端口释放、emulator 日志**最后一行仍是** `Boot completed in 58898 ms`、
  **无任何 SIGABRT / 崩溃行**。
* **第 2 次**（run2，32s 冷启后重放同样步骤）：**同一步、同样的静默退出**。

**第 3 次（run3）定位到触发条件并完成取证**：把同样的平板几何会话点击换成**「New session」空会话**（不加载
4000 行的大会话内容）⇒ **实例存活**（`alive=True`），随后**完整跑完了平板侧 ② 的全套取证**
（`mInputShown` 断言、`type=ime` 原始行、composer 前后读数，见 §5 ②），
并在收尾复读时**仍然存活**（`system_server` pid 580 稳定）。

⇒ **判读**：崩溃触发条件是 **1366 几何 + 渲染超大历史会话内容**，
**不是** E4 的 `mapper.ranchu.so` 断言（logcat 无该断言），更像
**宿主机资源压力下的内存耗尽**（作业期间宿主机同时有 5 台他人实例：5570/5572/5636/5730/5802）。
**未取到 tombstone / 无崩溃日志，因此不宣称根因。**

**实例已重新拉起并保持运行**（run3，`system_server` pid 稳定 `579`）。

---

## 8. 设备指纹（收尾实测）

```
ro.product.name          = sdk_gtablet_x86_64
ro.build.version.sdk     = 35
ro.build.version.release = 15
ro.serialno              = EMULATOR36X6X11X0
ro.hardware.egl          = emulation

# 窗口模式（收尾复核，仍是全屏）
* Task{815eb19 #14 type=standard A=10212:com.xiufeigo.opencodeshell.web.debug
       U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}

# wm（设备原生 + 本轮 override）
Physical size: 2560x1600        Override size: 1366x768
Physical density: 320           Override density: 160
    init=2560x1600 320dpi mMinSizeOfResizeableTaskDp=220 base=1366x768 160dpi cur=1366x768 app=1366x768 rng=768x768-1366x1366

# 稳定性（收尾复读）
system_server t0=580  t1=580  stable=True      # 8s 间隔
cat /proc/uptime    = 190.65 495.53

# 包 com.xiufeigo.opencodeshell.web.debug
versionName      = 0.1.0-webhost
lastUpdateTime   = 2026-10-04 11:58:18
firstInstallTime = 2026-10-04 11:58:18

# WebView（现代代际，这是 E2「WebView 11 解析不了 ESM」的关键对照）
Current WebView package (name, version): (com.google.android.webview, 124.0.6367.219)
  Valid package com.google.android.webview (versionName 124.0.6367.219, versionCode 636771938, targetSdkVersion 34)

# 端口转发
host-16 tcp:4095 tcp:4095
```

> **WebView 124.0.6367.219**（≈Chrome 124）vs E2 android-30 的 **WebView 11**（≈Chrome 87）
> ⇒ 这就是本轮官方 UI 能渲染、E2 渲染不了（`Uncaught SyntaxError: Unexpected token '='`）的分代原因。

---

## 9. 收尾确认

### 9.1 我碰过哪些 serial

**本轮下发过任何命令的 serial 只有一个，且是自建**：

| serial | 归属 | 本轮对它做过什么 |
|---|---|---|
| **`emulator-5824`** | **自建（`OC_Narrow_Test`，本轮创建）** | `install -r -d -g`（web 壳）、`reverse`、`wm size`/`wm density` 覆写、`pm clear`、`am start`、`am force-stop`、`input tap`、`screencap`、`uiautomator dump`、CDP `adb forward`、`logcat`、`dumpsys`、`getprop`、`pidof`、`input_method`/`window` dumps；**`emu kill` 0 次，进程 2 次自行退出后由我重新拉起** |

**零触碰清单**（未下发过任何命令）：

```
emulator-5636   （平板机，E5 用的那台，本轮明确不碰）
emulator-5570 / emulator-5572 / emulator-5730 / emulator-5802
```

* **未下载任何镜像**（只用宿主机既有 `system-images`）。
* **未对 `127.0.0.1:4095` 做任何启停** —— 全程只做 `adb reverse tcp:4095 tcp:4095`，
  后端由 Lead 托管。
* **主动放弃了** `adb kill-server` / `adb reconnect offline` / `adb -a` 等会扰动全宿主机连接的操作。

### 9.2 `wm` 现值（后续 A/B 直接可用）

| 项 | 原值（run1 首次覆写前） | 本轮用过的中间值 | **收尾现值（实测）** |
|---|---|---|---|
| `wm size` | 无 override（原生 `2560x1600`） | `1080x2400` → `1366x768` | `Physical size: 2560x1600` / **`Override size: 1366x768`** |
| `wm density` | 无 override（原生 `320`） | `420` → `160` | `Physical density: 320` / **`Override density: 160`** |

> **注**：`wm size` / `wm density` 的 override 由 Android 跨重启持久化到 userdata，
> run2 冷启后仍读回 `Override size: 1366x768 / Override density: 160`（已实测）。
> 交付实例停在**平板几何 1366×768 @160**，即与 E5 对比列同款，便于下一轮直接做 A/B。
> **要复现本轮手机列**，只需 `wm size 1080x2400` + `wm density 420`。

### 9.3 实例保持运行

**`emulator-5824`（`OC_Narrow_Test`）保持开机运行**，供后续 A/B 直接使用。
app 已安装、首启配置已保存（口令在 SecretStore，本轮 `will 401` 计数 = 0），
**冷启后无需再走首启配置框**。

### 9.4 本轮产出物

| 类别 | 路径 |
|---|---|
| 报告 | `research/env/NARROW_PHONE_E6.md`（本文件） |
| CDP 脚本（E5 原版加了 `-Serial`） | `research/env/cdp_run_e6.ps1` |
| 四合一探针 JS | `research/env/probe_e6.js` |
| 平板侧 Q2 取证脚本 | `research/env/logs/e6_tablet_q2.ps1` |
| 启动日志 | `research/env/logs/e6_oc_narrow_boot.log`（run1）、`..._boot_run2.log`（run2）、`..._boot_run3.log`（run3） |
| 首启配置框 dump | `research/env/e6_setup.xml` / `e6_b.xml` / `e6_c.xml` / `e6_d.xml` |
| 截图 | `research/env/e6_phone_5824.png`（官方 UI 首页）、`research/env/e6_ime_5824.png`（IME 遮挡 composer） |
