# IME **内容级**重叠量补测（F10）— 交付包 `76ca064b…` 上闭环 V12 的缺口

> **为什么有这一轮**：V12 轮（`research/web/V12_DELIVERED_VERIFY.md` §6）在交付包上测到了
> **窗口级** IME inset **189 px**，但**内容级**（网页可视内容是否被键盘盖住）因**无 CDP** 判为
> **「测不到」**，留给下轮补。本轮就是补这一格。
>
> **⚠️ 窗口级 ≠ 内容级，两句话必须分开说**：
> - **窗口级** = 宿主**窗口矩形** ∩ 键盘矩形。本宿主是 `sim={adjust=resize}` +
>   `pfl=EDGE_TO_EDGE_ENFORCED` + `FIT_INSETS_CONTROLLED`，**窗口本身保持全高 1600 不缩**，
>   键盘靠 **insets 内边距**避让 ⇒ 窗口矩形 ∩ 键盘 **必然等于键盘高度**，**这个数非 0 是正常的，
>   不代表内容被盖**。
> - **内容级** = 浏览器**实际排版出来的内容**（composer 这个 DOM 节点的真实矩形）∩ 键盘**实际绘制带**。
>   **这才是派单问的「预期 0」**，本轮测得 **0 px**。

- 轮次日期：2026-10-05（硬期限 2026-10-07 前）
- 设备：`emulator-6270`（AVD `OC_Deliver_R2`，**本轮自建 → 本轮关**）
- 原始产物：`research/web/out_f10/`
- 指纹：`google/sdk_gtablet_x86_64/emu64xa:15` · Pixel Tablet · SDK 35 · WebView `124.0.6367.219` ·
  几何 `wm size 1080x1600` + `wm density 420`（**未传 `-skin`**，`pixel_tablet` 非合法 skin 名）

---

## 1. 被测物与 md5 双向复核

```
PREINSTALL_MD5=76ca064b7de9246ee446f765a9198b37  size=3975389
INSTALL: Performing Streamed Install | Success
PM_PATH=package:/data/app/~~vO5yaYccGlDwIZJBZDpvVQ==/com.xiufeigo.opencodeshell.web.debug-8iFCSHHViy2Rh6a5tQEyTw==/base.apk
POSTINSTALL_MD5_RAW=76ca064b7de9246ee446f765a9198b37 /data/app/~~vO5ya.../base.apk
MD5_MATCH_LOCAL_EQ_DEVICE=True (local=76ca064b7de9246ee446f765a9198b37 device=76ca064b7de9246ee446f765a9198b37)
```

**判定：✅ PASS** — 磁盘字节 = 装机字节 = 派单指定的当前交付包，未沿用任何旧构建。

---

## 2. 前置断言：已鉴权 + 数据已到（**真实会话列表，不是骨架屏**）

```
CRED_STORED=True  PASSWORD_PRESENT=True
PROBE_OK=True                      ← OcWebHost: F5 probe OK code=200, loading official UI
AUTH_FAILED_COUNT=0
API_GETS_UNIQUE=10
SESSION_IDS=2: ses_efc722bf9ffeWaRdoFTwbmYj1Y,ses_f408ec4a0ffeKjpcGKG4jfGEBR
LINE[J.g1.preflightOk]: ... G1 webview version=124.0.6367.219 major=124 min=105 tooOld=false
LINE[M.g1.firstContent]: ... kind=dcl
```

- 进入的会话：`Counting from 1 to 4000, one number per line`（`out_f10/home.png` 肉眼可见真实会话列表：
  `Friendly greeting` / `android-e2e` / `DRAFT__OK` / `New session`…）
- 会话详情页截图 `out_f10/sess.png` 可见真实消息体（`V6_KEEPAWAKE_ROUND` / `V8_KEEPAWAKE_PROBE`）与 composer 占位文案
  `Ask anything, / for commands, @ for context…`
- `M.g1.firstContent` 内容信号已触发，**不是骨架屏**。

**判定：✅ PASS**

---

## 3. CDP 可用性（先试，失败记原因）

```
APP_PID=<pid>
DEVTOOLS_SOCKETS_COUNT=0  raw=
DEVTOOLS_RAW=[]
```

**CDP 在本交付包上仍不可用（结构性，与 V8 / V12 两轮独立复现一致）**：
`webapp/` 模块**从不**调用 `WebView.setWebContentsDebuggingEnabled`；`app/src/main/java/com/xiufeigo/opencodeshell/MainActivity.kt:466`
虽调用但被 `BuildConfig.DEBUG` 门控；`--es oc_debug_eval` 注入通道在 `WebHostActivity.kt:842` 同样被 `if (!BuildConfig.DEBUG) return` 关掉。
⇒ release 交付包上**没有 devtools unix socket**，`document` / `getBoundingClientRect` 这条路走不通。

---

## 4. 聚焦 composer + 断言 `mInputShown`（同一个设备端 shell 调用内「点一下 + 读」）

避免 V12 记过的竞态：`input tap` 之后键盘可能又被 toggle 掉，所以**点与读在同一次 `adb shell` 里原子完成**。

```
PRE=mInputShown=false
POST=mInputShown=true
CAPTURED_OK
```

**判定：✅ PASS** — 聚焦后 `mInputShown=true` 成立，**在此之前不读任何 IME 数值**（E7 起的纪律）。

`uiautomator` dump 交叉确认焦点在 composer：
```
bounds="[52,1144][1029,1307]" class="android.widget.EditText" focusable=true focused=true
```

---

## 5. 键盘位置原始输出

### 5.1 IME 窗口块原文（`dumpsys window windows`）

```
Window #9 Window{7f1e0ce u0 InputMethod}:
  mAttrs={(0,0)(fillxfill) gr=BOTTOM CENTER_VERTICAL sim={adjust=pan} ty=INPUT_METHOD
          fmt=TRANSPARENT wanim=0x1030056 receive insets ignoring z-order
    fl=NOT_FOCUSABLE LAYOUT_IN_SCREEN SPLIT_TOUCH HARDWARE_ACCELERATED
    pfl=FIT_INSETS_CONTROLLED
    fitTypes=STATUS_BARS NAVIGATION_BARS
    fitSides=LEFT TOP RIGHT}
  Requested w=1080 h=1537 mLayoutSeq=96
  mIsImWindow=true mIsWallpaper=false mIsFloatingLayer=true
  mViewVisibility=0x0 mHaveFrame=true mObscured=false
  Frames: parent=[0,63][1080,1600] display=[0,63][1080,1600] frame=[0,63][1080,1600] last=[0,63][1080,1600] insetsChanged=false
  isOnScreen=true
  isVisible=true
  mGivenContentInsets=[0,1411][0,0] mGivenVisibleInsets=[0,1411][0,0]
  touchable region=SkRegion((0,1474,1080,1600))
```

键盘**实际占位**窗口行：

```
Frames: parent=[0,0][1080,1600] display=[0,0][1080,1600] frame=[0,1474][1080,1600] last=[0,1474][1080,1600] insetsChanged=false
```

**⇒ 键盘顶边 `y=1474`，键盘高度 `1600−1474 = 126 px`。**

> 说明：IME 窗口自身的 `frame=[0,63][1080,1600]` 是**输入法窗口的占位框**（`adjust=pan` + 全屏输入法），
> **不是**键盘的可见区域；键盘可见区域以 `frame=[0,1474][1080,1600]` 与 `touchable region` 为准。
> 另有一条 `mGivenContentInsets=[0,1411][0,0]`：这是 IME **给应用的下边距承诺**（应用被告知
> 「内容别越过 y=1411」），与键盘实际绘制线 1474 相差 63 px，**两者都不是重叠量**，下面分别算。

### 5.2 应用窗口原文（对照，说明为何窗口级必然非 0）

```
Window{... u0 com.xiufeigo.opencodeshell.web.debug/...WebHostActivity}:
  mAttrs={(0,0)(fillxfill) sim={adjust=resize forwardNavigation} layoutInDisplayCutoutMode=always
          ty=BASE_APPLICATION wanim=0x1030309
    pfl=NO_MOVE_ANIMATION EDGE_TO_EDGE_ENFORCED FORCE_DRAW_STATUS_BAR_BACKGROUND FIT_INSETS_CONTROLLED}
  Frames: parent=[0,0][1080,1600] display=[0,0][1080,1600] frame=[0,0][1080,1600] last=[0,0][1080,1600] insetsChanged=false
```

⇒ 窗口**全高 1600 不缩**，这就是 V12 那个「窗口级 189 px」的成因。

### 5.3 键盘顶边的**独立像素复核**（`screencap` 逐行扫描）

```
img=1080x1600
KEYBOARD_BAND_TOP=1474  (height=126)
pixel@bandTop=(0,0,0)   pixel@above=(247,247,247)
```

`dumpsys` 的 `frame=[0,1474][1080,1600]`、`touchable region=(0,1474,1080,1600)` 与
**截图像素扫描的 1474 三者完全一致** ⇒ 键盘顶边 `y=1474` 为实测，非推断。

---

## 6. composer 实际 bounds（内容级）

`uiautomator dump` **在本镜像上读到了 WebView 内的节点**（105 个节点，含无障碍矩形）——
这是本轮能绕开 CDP 的关键通道：

| 节点 | class | bounds（设备 px） | 说明 |
|---|---|---|---|
| **composer 输入框** | `android.widget.EditText` `focused=true` | **`[52,1144][1029,1307]`** | **本轮测量的主角** |
| 占位文案 | `android.widget.TextView` | `[52,1144][1029,1244]` | `Ask anything, / for commands, @ for context…` |
| composer 卡片容器 | `android.view.View` | `[52,1144][1029,1307]` | 输入区整块 |
| 工具行 · 加图片 | `android.widget.Button` | `[73,1325][152,1401]` | `Add images and files` |
| 工具行 · 模型 | `android.widget.Button` | `[157,1325][585,1401]` | `MiMo-V2.6-Pro` |
| 工具行容器（composer 内**最低**的节点） | `android.view.View` | `[157,1304][913,1422]` | 布局包裹层 |
| 工具行 · 发送 | `android.widget.Button` | `[939,1330][1008,1396]` | `Send` |

**composer 内容最低边 = `y=1422`**（工具行容器下沿，输入框本身下沿 `1307`）。

---

## 7. 重叠像素量计算

口径：`overlap = max(0, min(composerBottom, imeBottom) − max(composerTop, imeTop))`，
`imeTop=1474`、`imeBottom=1600`（键盘实际绘制带，§5 三重互证）。

### 7.1 内容级（**本轮要的那个数**）——取最保守的 composer 外沿

```
composerTop    = 1144
composerBottom = 1422      ← composer 内最低节点（工具行容器），宁可取大不取小
imeTop         = 1474      ← 键盘实际绘制带
imeBottom      = 1600

high = max(1144, 1474) = 1474
low  = min(1422, 1600) = 1422
overlap = max(0, 1422 − 1474) = 0 px
clearance = 1474 − 1422 = 52 px      ← composer 整条都在键盘上方
```

| 口径 | composer 底边 | 键盘顶边 | **重叠** | 余量 |
|---|---|---|---|---|
| composer **输入框**（`EditText`）vs 键盘绘制带 | 1307 | 1474 | **0 px** ✅ | 167 px |
| composer **整块**（含工具行，最保守）vs 键盘绘制带 | 1422 | 1474 | **0 px** ✅ | 52 px |
| composer **输入框** vs IME 承诺下边距 `mGivenContentInsets` | 1307 | 1411 | **0 px** ✅ | 104 px |

> 第三行口径最严苛：即便按 IME「承诺给应用的下边距 1411」来卡，输入框仍有 104 px 余量。
> （若把**工具行容器 1422** 去比 1411，会得到 11 px，但那是一个**空的布局包裹层**的下沿、
> 不是用户内容，且离键盘实际绘制线还有 52 px；截图里该区域是 composer 白底，无任何遮挡。）

**⇒ 内容级重叠量 = 0 px，符合派单预期。**

### 7.2 窗口级（V12 的 189 px，**不是内容级**）

```
appBottom = 1600（窗口全高，不因键盘 resize）
imeTop    = 1411（V12 用 mGivenContentInsets 口径）
overlap   = 1600 − 1411 = 189 px
```

**这 189 px 与本轮的 0 px 不矛盾**——两者量的不是同一个东西：

| | 量的是什么 | 值 | 有没有意义 |
|---|---|---|---|
| **窗口级** | 宿主**窗口矩形** ∩ 键盘矩形 | **189 px** | ❌ **不能**当「内容被盖」的答案；edge-to-edge 下必然 = 键盘高度 |
| **内容级** | 浏览器**实际排版内容**（composer 节点真实矩形）∩ 键盘**实际绘制带** | **0 px** ✅ | ✅ 这才是「内容有没有被键盘压住」 |

---

## 8. 判定

| # | 项 | 判定 |
|---|---|---|
| 1 | 装前/装后 md5 = `76ca064b7de9246ee446f765a9198b37` | ✅ |
| 2 | 前置断言：已鉴权 + 数据已到（10 个 API GET、2 个真实 session id、`firstContent`，**非骨架屏**） | ✅ |
| 3 | CDP | ❌ 不可用（结构性：`DEVTOOLS_SOCKETS=0`）——**如实记录，未用措辞假装做过** |
| 4 | 聚焦 composer → 断言 `mInputShown=true`（同一次 shell 内点+读） | ✅ `PRE=false → POST=true` |
| 5 | 键盘顶边 `y=1474`（`dumpsys` frame + touchable region + 截图像素扫描，三重互证） | ✅ |
| 6 | composer 内容级 bounds（`uiautomator` 真实节点矩形） | ✅ `[52,1144][1029,1307]`，`focused=true` |
| 7 | **IME 内容级重叠量** | ✅ **0 px**（余量 52–167 px） |
| 8 | 窗口级 189 px | ⚠️ 如实保留，**明确标注 ≠ 内容级** |

**⇒ V12 §9-2「IME 内容级重叠量 = 0 未闭环」这一条，在交付包 `76ca064b…` 上**已闭环为 0 px**。**

---

## 9. 方法学诚实性声明（读结论前请看）

1. **不是 CDP `getBoundingClientRect()`**。本轮走的是 **Android 无障碍节点树**（`uiautomator dump`）
   暴露的 WebView 节点矩形。**它是 WebView 已排版完成的真实布局矩形，不是估算**，但与 CDP 的
   `client rect` 在原理上不同源（a11y 矩形 vs CSS client rect）。对静态布局两者相等；
   若页面用了 CSS transform，两者可能差几 px。**如实标注此口径差异。**
2. **独立复核**：键盘侧不只靠 `dumpsys`，还用 `screencap` 逐行像素扫描独立测出 `1474`，
   与 `dumpsys` 两个来源一致；截图 `out_f10/ime_shown.png` 肉眼也可见键盘深色带起于 composer 之下。
3. **仍未做到的一件事**：`document.body` 的可视高度 / `visualViewport.height` 这类
   **页面级**度量仍测不到（同样因无 CDP）。本轮闭环的是 **composer 这个具体元素**的内容级重叠，
   范围比「整个可视视口」窄，如实说明。
4. 复现脚本：`research/web/_f10_boot.ps1` / `_f10_p1.ps1` / `_f10_closeout.ps1`；
   原始产物：`research/web/out_f10/`（`ime_shown.png`、`ime_uia.xml`、`ime_window_dump.txt`、`ime_input_method.txt`、`sess.png`、`home.png`）。

---

## 10. 收尾复核输出（housekeeping）

抄录自 `research/web/out_f10/closeout.log` 原文：

```
[09:21:47] === F10 closeout (who starts, who closes: OC_Deliver_R2 / emulator-6270 / port 6270) ===
[09:21:48] BEFORE emulator/qemu procs = 2
[09:21:48] MINE pid=37776 avd=OC_Deliver_R2 port=6270 MINE=True
[09:21:48] MINE pid=35508 avd=OC_Deliver_R2 port=6270 MINE=True
[09:21:48] MINE_COUNT=2
[09:21:48] BACKEND_4095: LISTEN pid=3748 (never started/stopped by me)
[09:21:48] REVERSE_BEFORE: host-16 tcp:4095 tcp:4095
[09:21:48] REVERSE_AFTER: (空)
[09:21:50] WM_SIZE_AFTER: Physical size: 2560x1600        ← 已无 Override
[09:21:51] WM_DENSITY_AFTER: Physical density: 320       ← 已无 Override
[09:21:51] EMU_KILL: OK: killing emulator, bye bye  OK
[09:22:16] VERIFY emulator/qemu procs = 0
[09:22:16] VERIFY OC_*/Medium_Desktop_B residual = 0
[09:22:16] VERIFY port 6270: FREE
[09:22:16] VERIFY port 6272: FREE
[09:22:16] VERIFY port 6274: FREE
[09:22:16] VERIFY adb devices: List of devices attached  （空）
[09:22:17] VERIFY 4095 backend still: LISTEN pid=3748    ← 与开轮同一 pid，未起停
[09:22:17] LOCK .agent-gradle-lock exists = False
```

- 杀前按 **AVD 名 + 端口**双条件确认归属（`MINE_COUNT=2`，父子进程对），**只按 serial `emulator-6270` 关**。
- `wm size reset` + `wm density reset` 后复核 **`Physical size: 2560x1600` / `Physical density: 320`**，**已无 Override 行**。
- `adb reverse tcp:4095` 已摘除（`REVERSE_AFTER` 空）。
- **未执行** `adb kill-server`、**未用** `-a` / 通配符。
- **故意保留：无。**
- 用户自己的 `MCRemote_*` / `DSH_*` / `T7x_*` 与来源不明实例**全程未碰**。
  开轮时 `adb devices` 为空、全机 emulator/qemu 进程数 0 ⇒ **不存在可被波及的用户设备**。
- 后端 `4095` 全程**未起停**（开轮前与收尾均 `LISTEN pid=3748`，同一 pid），只做 `adb reverse`。

### 我碰过哪些 serial

| serial | AVD | 端口 | 起 | 关 |
|---|---|---|---|---|
| `emulator-6270` | `OC_Deliver_R2` | 6270 | 本轮自建 | 本轮 `emu kill` ✅ |

**本轮只碰过 `emulator-6270` 一个 serial。**
