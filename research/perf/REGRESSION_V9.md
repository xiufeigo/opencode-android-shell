# REGRESSION_V9 —— 在**最新交付包**上重做「形态 + 性能」回归

- **执行日期**：2026-10-05 07:14 → 07:40（硬期限 2026-10-07 之前 ✅）
- **被测物（本轮唯一）**：`research/deliver/opencode-shell-web-release-nor8.apk`
  - **bytes = 3,975,389**（与任务书一致 ✅）
  - **md5 = `76CA064B7DE9246EE446F765A9198B37`**（与任务书一致 ✅）
  - `aapt2 dump badging`：`name='com.xiufeigo.opencodeshell.web.debug' versionName='0.1.0-webhost'`，`minSdk=26`，`targetSdk=35`，`launchable-activity='com.xiufeigo.opencodeshell.web.WebHostActivity'`
- **本轮实例**：`emulator-6190`（AVD **`OC_Deliver_R2`**，端口 **6190**，≥6190 偶数；起前查 `6190/6192/6194/6196/6198/6200/6202` **全部 FREE**）
- **镜像**：`android-35;google_apis_playstore_tablet` + 设备外形 `pixel_tablet`（**已装镜像，未下载**）；**未使用** `-skin pixel_tablet`
- **本轮性质**：只验证，不改代码。未碰 `app/**`、`webapp/src/**`、`_upstream/**`、`.github/**`、`research/deliver/**`
- **工具**：`research/perf/v9_boot.ps1` / `v9_sweep3.ps1` / `v9_perf.ps1` / `v9_close.ps1`；原始输出 `research/perf/out_v9/**`

---

## 0. 一句话结论

> **六个几何全部按 `wm size/density` 逐格实测完毕，官方 UI 的「外壳」（Home / All projects / Search sessions / Projects）在每一格都渲染出来了 —— 但这份包是 release 构建、无 CDP，且 F5 探针 401 让会话列表永远停在 `Loading` 骨架屏，因此「IME 重叠量」「触摸靶」「CDP 选择器计数」三类格子在本轮结构性地测不到。**
> **768dp 边界在运行时确实切换**（700dp 移动分支 / 820dp 桌面分支 / 1366dp 出现左栏），但 `review-toggle` 这一具体选择器**测不到**（无 CDP）。
> **首屏 5 次冷启：中位数 7252 ms，极值 7095 / 7334 ms**（口径含 harness 开销，见 §2.1）。
> **输入延迟代理量：测不到**（composer 不可达）。

### 🔴 本轮最重要的三个发现（都改变了回归结论）

| # | 发现 | 证据 | 影响 |
|---|---|---|---|
| **F1** | **这份交付包不可调试 ⇒ 无 CDP** | `dumpsys package` → `flags=0x0` / `flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]`；`aapt2 badging` **无 `application-debuggable`**；`/proc/net/unix` 中 `webview_devtools_remote` 计数 **= 0**（6/6 格） | 任务书要求的「CDP 读数（title/节点数/关键选择器）」在**这份包上无法取得**，不是脚本问题 |
| **F2** | **F5 探针 401 复现，每次启动都弹「登录没通过」** | `OcWebHost/Probe: probe /api/info -> 401; user=opencode passLen=8 authHeaderSent=true` + `F5 probe AUTH FAILED code=401; showing retryable dialog`（6/6 格 + 5/5 次冷启） | 官方 UI 必须**人工点「继续加载」**才能进入；会话列表因此停在 `Loading` 骨架屏，**composer 不可达** |
| **F3** | **设备端凭据本身是好的** | 宿主 `GET /api/info -u opencode:opencode` = **200**；设备端 `curl -u opencode:opencode` = **200**、不带 auth = **401** | 401 **不是**后端/口令问题，而是 App 侧请求与凭据不一致 —— 与 `HOUSEKEEPING.md` 里 V8/F6 轮记录的是**同一个未修的 bug** |

---

## 1. 形态回归（6 格全测，逐格原始输出）

**方法（每格完全相同）**：
1. `wm size` / `wm density` 覆盖 → 读回 `Physical/Override`；
2. 用 `dumpsys activity activities | grep -oE 'sw[0-9]+dp w[0-9]+dp h[0-9]+dp [0-9]+dpi'` 让**系统自认**宽度（不靠我们自己换算）；
3. `am force-stop` → `logcat -c` → `am start -W`；
4. `uiautomator dump` 找原生对话框 `text="继续加载"`，按其 `bounds` 中心点 `input tap`（**F2 的绕行**）；
5. 轮询 `logcat -d -s OcWebHost:*` 直到出现**首条 `OcPerfJS res #N ... /api/event`**（= UI 可用标记）；
6. 记录 CDP 可用性、截图、`dumpsys window | grep 'type=ime frame='`、尝试抬 IME。

> **全轮 0 次** `Emulation.setDeviceMetricsOverride` ⇒ 所有几何都是**真实窗口几何**，不是 CDP 视口伪造。

### 1.1 形态回归总表

| # | 几何 | 原始 `wm size` / `wm density` | 系统自认 | 官方 UI 渲染 | CDP 读数 | IME 原始行 | IME 重叠像素 | 触摸靶 | 判定 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 手机 **360dp** | `720x1280` / `320` | `sw360dp w360dp h640dp 320dpi nrml` | ✅ 渲染（Home + All projects + Search sessions + `Loading` 骨架 ×3） | ❌ **测不到**（socket=0） | `type=ime frame=[0,0][0,0] visible=false` | ❌ **测不到** | ❌ **测不到** | 🟡 **UI 渲染 PASS；CDP/IME/触摸靶 测不到** |
| 2 | 折叠 **442dp** | `884x2208` / `320` | `sw442dp w442dp h1104dp 320dpi nrml` | ✅ 渲染 | ❌ 测不到（socket=0） | `frame=[0,0][0,0] visible=false` | ❌ 测不到 | ❌ 测不到 | 🟡 同上 |
| 3 | 折叠 **708dp** | `1416x2208` / `320` | `sw708dp w708dp h1104dp 320dpi lrg` | ✅ 渲染 | ❌ 测不到（socket=0） | `frame=[0,0][0,0] visible=false` | ❌ 测不到 | ❌ 测不到 | 🟡 同上 |
| 4 | 边界 **700dp**（<768） | `1400x2208` / `320` | `sw700dp w700dp h1104dp 320dpi lrg` | ✅ 渲染，**移动分支** | ❌ 测不到（socket=0） | `frame=[0,0][0,0] visible=false` | ❌ 测不到 | ❌ 测不到 | 🟡 同上；分支 = 移动 |
| 5 | 边界 **820dp**（≥768） | `1640x2208` / `320` | `sw820dp w820dp h1104dp 320dpi xlrg` | ✅ 渲染，**桌面分支** | ❌ 测不到（socket=0） | `frame=[0,0][0,0] visible=false` | ❌ 测不到 | ❌ 测不到 | 🟡 同上；分支 = 桌面 |
| 6 | 平板 **1366×768** | `1366x768` / `160` | `sw768dp w1366dp h768dp 160dpi xlrg` | ✅ 渲染，**桌面分支 + 左栏** | ❌ 测不到（socket=0） | `frame=[0,0][0,0] visible=false` | ❌ 测不到 | ❌ 测不到 | 🟡 同上 |

**「IME 重叠量」全 6 格 = 测不到，一格都没有写成「通过」。** 原因见 §1.4。

### 1.2 逐格原始输出（摘自 `out_v9/sweep_raw.txt`，未加工）

```
[07:29:45] ######## CELL phone360dp target 360dp ########
  READBACK wm size: Physical size: 2560x1600
  READBACK wm size: Override size: 720x1280
  READBACK wm density: Physical density: 320
  SYSTEM CONFIG: sw360dp w360dp h640dp 320dpi nrml
  DISPLAY: init=2560x1600 320dpi ... base=720x1280 320dpi cur=720x1280 app=720x1280 rng=720x720-1280x1280
  am start -W: ... LaunchState: COLD ... TotalTime: 605 ... Complete
  DIALOG present: ... resource-id="android:id/button2" ... bounds="[358,687][518,783]" tap=(438,735)
  LOG 10-04 23:29:50.821 6821 6821 I OcWebHost: F5 probe before load path=/api/info
  LOG 10-04 23:29:50.843 6821 6821 W OcWebHost: F5 probe AUTH FAILED code=401; showing retryable dialog
  LOG 10-04 23:29:57.359 6821 6821 I OcWebHost: home=http://127.0.0.1:36307/ backend=http://127.0.0.1:4095 passwordPresent=true
  UI_AVAILABLE_MS=10857 AM_TOTAL_MS=605 PROXY_GET=0 OCRES=54 DIALOG_TAPS=(438,735)
  CDP_EVIDENCE devtoolsSocketCount=0 pkgFlags=flags=0x0 appPid=6821
  RENDER ... v9_phone360dp_render.png bytes=23971 size=720x1280 whitePct=99.2
  IME_RAW InsetsSource id=3 type=ime frame=[0,0][0,0] visible=false flags= sideHint=NONE boundingRects=null
  IME_METHOD mInputShown=false
  IME_METHOD fallbackInputConnection=... mServedView=null
  OVERLAP=NOT_COMPUTED imeShown=false imeTop=0
```
其余 5 格的同字段原始行：

| 格 | SYSTEM CONFIG | UI_AVAILABLE_MS | AM_TOTAL_MS | OCRES | devtoolsSocketCount | dialog tap |
|---|---|---|---|---|---|---|
| fold442dp | `sw442dp w442dp h1104dp 320dpi nrml` | 10745 | 486 | 54 | **0** | (597,1199) |
| fold708dp | `sw708dp w708dp h1104dp 320dpi lrg` | 10888 | 657 | 54 | **0** | (1010,1223) |
| bound700dp | `sw700dp w700dp h1104dp 320dpi lrg` | 10769 | 532 | 54 | **0** | (995,1191) |
| bound820dp | `sw820dp w820dp h1104dp 320dpi xlrg` | 10809 | 585 | 53 | **0** | (1146,1191) |
| tablet1366x768 | `sw768dp w1366dp h768dp 160dpi xlrg` | 10950 | 719 | 53 | **0** | (858,428) |

6 格的 IME 原始行**逐字相同**（IME 从未被服务给 WebView）：
```
InsetsSource id=3 type=ime frame=[0,0][0,0] visible=false flags= sideHint=NONE boundingRects=null
mInputShown=false
```

### 1.3 官方 UI 渲染判定（逐格依据）

每一格都命中**官方 UI 的身份三要素**，不是占位页、不是对话框：

1. **logcat 主机侧**：`home=http://127.0.0.1:<port>/ backend=http://127.0.0.1:4095 passwordPresent=true` ⇒ 宿主确实 `loadUrl` 了官方前端；
2. **logcat 页面侧**：`OcPerfJS res #N ... /api/event` 计数 **50–54** ⇒ 官方 SPA 活着并在轮询；
3. **截图**：见 §1.5，六格都能看到真实上游 UI 文字（`Home` / `All projects` / `Projects` / `Search sessions` / `Settings` / `Help`）。

> **「渲染正常」要加限定语**：渲染出来的是**外壳**；**数据区永远停在 `Loading` 骨架屏**（F2 所致）。本轮**没有任何一格**拿到真实会话列表或 composer。

### 1.4 为什么 IME 重叠量 / 触摸靶 / CDP 三类格子测不到（逐项原因）

| 测不到的量 | 原因（可复核） |
|---|---|
| **CDP 读数**（`document.title` / 节点数 / 选择器计数） | 包**不可调试**：`flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]`（无 `DEBUGGABLE`）⇒ WebView 不开 `webview_devtools_remote_<pid>` unix socket ⇒ 6/6 格 `cat /proc/net/unix \| grep -c webview_devtools_remote` = **0**。`cdp_run_e6.ps1` 因此返回 `ERR_NO_DEVTOOLS_SOCKET`（脚本无错，是通道不存在） |
| **IME 重叠量** | 需要「聚焦 composer → 让 IME 弹起 → 读 `type=ime frame` 顶边 → 读 composer 底边」。本轮 **IME 在 6/6 格都弹不起来**：会话列表停在 `Loading`，**没有可进入的会话**，composer 根本不在屏幕上；又**没有 CDP** 去定位 composer 坐标，只能盲试 3 个候选点（y=0.93/0.88/0.97），`mInputShown` 始终 `false`、`mServedView=null` |
| **触摸靶（<48dp 计数）** | 触摸靶需要读 DOM 元素盒（`getBoundingClientRect`）⇒ 同样依赖 CDP。截图级目视只能看出「有导航/有搜索框」，**量不出元素盒的 dp 尺寸**，故**不产出数字**（不拿截图冒充测量） |

### 1.5 截图实证（6 格，逐格有据）

| 格 | 截图 | 可见内容（官方 UI 文字） | 分支 |
|---|---|---|---|
| phone360dp | `out_v9/v9_phone360dp_render.png` | `Home` / `All projects` / `Search sessions` / `Loading` + 3 张骨架卡 | 移动 |
| fold442dp | `out_v9/v9_fold442dp_render.png` | 同上 | 移动 |
| fold708dp | `out_v9/v9_fold708dp_render.png` | 同上 | 移动 |
| bound700dp | `out_v9/v9_bound700dp_render.png` | `Home` / `All projects` / `Search sessions` / `Loading` | **移动分支** |
| bound820dp | `out_v9/v9_bound820dp_render.png` | **`+`（新建）** / **`Projects`** / **`Add project`** / `Search sessions` / `Loading` + **底部 `Settings` `Help`** | **桌面分支** |
| tablet1366x768 | `out_v9/v9_tablet1366x768_render.png` | **`+`** / **左栏：`Projects` `Add project` `Settings` `Help`** / 右区 `Search sessions` `Loading` | **桌面分支 + 左栏** |

### 1.6 ★ 边界 768dp 上下两档的实测表现

| 档 | 宽度 | 实测表现（截图级） | 结论 |
|---|---|---|---|
| **下档** | **700dp**（<768） | 顶栏是 **`Home`**（无 `+`）；首行是 **`All projects`**（可折叠，带 chevron）；**无底部 `Settings`/`Help`**；**无左栏** | **移动导航分支** ✅ 符合上游响应式预期 |
| **上档** | **820dp**（≥768） | 顶栏变成 **`+`**；首行变成 **`Projects`** + **`Add project`** + 右侧文件夹 `+`；**底部出现 `Settings` `Help`** | **桌面分支（导航栏形态切换）** ✅ 切换确实存在 |

**与任务书预期的差异（如实记录）**：任务书写「≥768dp = 导航栏消失、出现 `review-toggle`」。
本轮能确认的是**「<768 移动分支 / ≥768 桌面分支」这条切换确实存在**（700dp vs 820dp 差异明确）；
但 **`review-toggle` 这个具体选择器本轮测不到**（无 CDP，读不到 DOM），**不能**据此宣称「就是 `review-toggle`」。

**静态旁证（只证明 CSS 里有这条规则，不等于运行时验证）**：
从后端取回本轮实际渲染的 bundle（`http://127.0.0.1:4095/_assets/*.css`，`out_v9/served_*.css`）：

```
@media (width<=767px)      ← 移动分支
@media (width>=48rem)      ← 48rem = 768px，桌面分支
@media (width>=640px)  /  (width<=639px)  /  (width<=600px)
```
> `settings-gAAmvXBQ.css` 与 `runtime-ddCoIm8Y.css` 中确实存在 `width<=767px` 与 `width>=48rem`(=768px) 成对规则 ⇒ **768dp 断点在上游资产里存在**。
> `review-toggle` 字面串**在 13 个 CSS 文件与 33 个 JS 文件里都没有命中** ⇒ 它大概是运行期由 JS 计算出来的类名/属性，**静态 grep 无法证实**。

**★ 顺带闭环了 F4 的一个未测点（F4 §10 #4「768→1366 之间的第二切换边界」）**：
本轮实测 **820dp = 桌面分支但无左栏**（只有底部 `Settings/Help`），
**1366dp = 桌面分支且有左栏**（`Projects/Add project/Settings/Help` 在左侧竖排）
⇒ 第二切换点落在 **(820dp, 1366dp] 区间内**，本轮未二分定位。

---

## 2. 性能（同一台设备、同一几何、同一轮）

**几何**：`wm size 1080x2400` + `wm density 420` ⇒ 系统自认 `sw411dp w411dp h914dp 420dpi nrml long`（**411dp 宽**）。
选它是因为 P4/P5/B1 的性能历史用的就是 `1080×2400@420`，这样 §3 的「与旧数字的关系」才有意义。

### 2.1 ★ 首屏：≥5 次冷启

**口径（按任务书要求，不是最后一个 `_assets` GET）**：
**UI 可用标记 = logcat 里首条 `OcPerfJS res #N ... /api/event`**
—— 即官方 SPA 已经起来、事件流开始轮询的时刻（比「最后一个资源 GET 结束」晚，是功能可用而非资源到位）。
计时器**在 `am start` 之前启动**，因此**包含**本轮必须的「点掉 401 对话框」这一段真实用户动作。

| 次 | UI 可用（ms） | `am start -W` TotalTime | 是否弹 401 对话框 | proxy GET | OcPerfJS |
|---|---|---|---|---|---|
| 1 | **7334** | 706 | 是 | 63 | 51 |
| 2 | **7252** | 662 | 是 | 63 | 51 |
| 3 | **7095** | 515 | 是 | 53 | 50 |
| 4 | **7240** | 641 | 是 | 52 | 50 |
| 5 | **7266** | 662 | 是 | 53 | 50 |

> **样本 = `7095, 7240, 7252, 7266, 7334`（n=5）**
> **中位数 = 7252 ms**（Nearest-rank 取 `sorted[floor((n-1)/2)]`，与 P5/B1 的 `Pct` 同一实现）
> **极值：min = 7095 ms ／ max = 7334 ms**（全距仅 **239 ms**，离散度很小）
> **mean = 7237 ms**

**⚠️ 必须声明的口径缺陷（不能当"App 冷启动"直接用）**：
这个 7.1–7.3 s **不是纯 App 侧首屏时间**，它包含本轮 harness 的**固定开销**：
`am start` + **固定 `Start-Sleep 3s`** + `uiautomator dump`（≈2 s）+ `input tap` + 轮询间隔（400 ms）。
⇒ **7.25 s 是上界（含 harness 约 5–6 s 开销）**。
App 侧自报口径是 `am start -W TotalTime` = **515 / 662 / 706 / 641 / 662 ms**（中位数 **662 ms**）。
**两个口径都不是"最后一个 `_assets` GET"，本报告不把两者混为一谈。**

### 2.2 输入延迟代理量

**结果：❌ 测不到（NOT MEASURABLE）**，原始记录：

```
[07:35:02]   composer focus(IME shown)=false at
  FOCUS imeShown=false at=
  IME: fallbackInputConnection=... mDeactivateRequested=false mServedView=null
  IME: mInputShown=false
  IME: mServedView=android.webkit.WebView{c5312bf VFEDH.C.. .F...... 0,0-1080,2400 aid=1073741824}
[07:35:02]   !! composer not focused -> input latency NOT MEASURABLE
  INPUT_LATENCY=NOT_MEASURABLE reason=composer_never_focused_no_CDP
```

`out_v9/inputlatency.json` = `{"measured":false,"reason":"composer never focused; no CDP to locate it"}`
（`framestats_raw.txt` **未生成**，因为没注入任何按键 —— 不拿 0 当结果。）

**为什么测不到**：任务书要求的代理量是「注入一次按键 → 页面 composer 内容更新」的时间差，
**两个端点本轮都不存在**：
- 起点（composer）**不可聚焦** —— 6 个候选点盲试后 `mInputShown` 恒为 `false`（`mServedView=null`）；
- 终点（composer 内容更新）**读不到** —— 需要 CDP 读 DOM，无 CDP。

**与旧方法的可比性：不可比（写明）**。
`INPUT_PATH_P5.md` / `LATENCY_FLOOR_B1.md` 用的是
`dumpsys gfxinfo framestats` 的 **`HandleInputStart` → `FrameCompleted`（i2f）/ `→ SwapBuffersCompleted`（i2s）**，
注入的是**窗口相对滑动**（20 次 swipe），在**可进入的会话页**上跑。
本轮**既没有滑动对象（列表停在 Loading）也拿不到输入事件**，
⇒ **本轮没有任何 i2f/i2s 样本，与 P5 的 32.00/58.76、B1 的 L1 25 ms 不可比，禁止相减、禁止说"变快/变慢"。**
（本轮脚本 `v9_perf.ps1` 已实现同一套 framestats 解析口径备用，一旦 composer 可达即可直接复测。）

### 2.3 设备指纹

| 项 | 值 |
|---|---|
| serial | **`emulator-6190`**（AVD `OC_Deliver_R2`，端口 6190） |
| `ro.product.name` | **`sdk_gtablet_x86_64`** |
| `ro.product.model` | **`Pixel Tablet`** |
| `ro.build.version.sdk` / release | **35** / **Android 15** |
| `ro.product.cpu.abi` | `x86_64` |
| `wm size` | `Physical size: 2560x1600`（本轮全程 `wm size reset` 复原前，未留 Override） |
| `wm density` | `Physical density: 320` |
| uptime | `48.69 85.27`（取指纹时设备刚 boot 完 ≈ **0 h**） |
| WebView | `versionName=124.0.6367.219`（与 P5/B1 同一版本） |
| 后端 | `127.0.0.1:4095`（Lead 的，`LISTEN pid=3748`），**本轮 0 次启停**，只 `adb reverse tcp:4095 tcp:4095` |
| 被测包 | 3,975,389 B / md5 `76CA064B7DE9246EE446F765A9198B37` |

---

## 3. 与旧数字的关系（**不混轴**）

### 3.1 本轮数字的归属

> **§1 形态表、§2.1 首屏 5 次、§2.2 输入延迟，全部 = 「`opencode-shell-web-release-nor8.apk`（md5 `76CA064B…`）+ `emulator-6190` + 2026-10-05 07:14–07:40 这一轮」。**
> **本报告没有沿用任何旧数字。** 旧报告里的每个数字都是当轮实测，不在本报告任何结论里充当输入。

### 3.2 旧报告数字来自哪些构建

| 旧报告 | 旧数字来源的构建 | 该构建现在还在吗 |
|---|---|---|
| `research/env/FORMFACTORS_F4.md`（形态 360/442/708/768dp、IME 0px、触摸靶 3/50 与 16/18） | `research/deliver/opencode-shell-web.apk`，报告未记 md5 | ❌ **该文件已被换掉**：磁盘上同名文件现为 **5,275,479 B / md5 `16C6CF8B7330E5D02879F30333800AD9`** |
| `research/perf/INPUT_PATH_P5.md`（web p50 32.00 / p90 58.76 ms） | `opencode-shell-web.apk` **md5 `8dcffc572ad469f472f61594f12ea990`** | ❌ **不在了**（现磁盘同名文件是 `16C6CF8B…`） |
| `research/perf/LATENCY_FLOOR_B1.md`（L1/L0/L2 三层） | web `8DCFFC572AD469F472F61594F12EA990` + native `opencode-shell-debug.apk` md5 `BD21FF21E72E75DB62FC371D290F438E` | web ❌ 不在；native ✅ 仍在（`44,198,253 B`，md5 一致） |

> ⚠️ **P5/B1/F4 用的那个 web 包（`8dcffc…`）在本仓已不存在**（同名文件被换成 `16C6CF8B…`）。
> 所以旧数字**不仅构建不同，连那份字节都已被覆盖**。

### 3.3 ★ 差异归因：**先说"条件不同"，不说"变快/变慢"**

本轮与 F4/P5/B1 的差异**很大**，但**不能**解读为性能或形态劣化，**可查的差异项**如下（这些是条件，不是结论）：

| # | 差异项 | 旧轮（F4/P5/B1） | 本轮（V9） |
|---|---|---|---|
| 1 | **构建变体** | `web` **debug** 包（`8dcffc…`） | **`web-release-nor8` release 包**（`76CA064B…`） |
| 2 | **可调试性** | **debuggable ⇒ 有 CDP**（F4 能读 `title`/节点/选择器、能定位 composer） | **不可调试 ⇒ 无 CDP**（本轮 §1.4 全表测不到的直接原因） |
| 3 | **探针行为** | F4/B1 记录 `passwordPresent=true` 且**无** `no password in SecretStore, asking` ⇒ 首屏门禁 A/B/C **PASS** | 本轮 **F5 探针 401 ⇒ 必弹「登录没通过」**，需人工点「继续加载」；列表停在 `Loading` |
| 4 | **设备 / 实例** | F4 = `OC_FF_F4` / `emulator-5892`；P5 = `OC_Perf_Test` / `emulator-5842`；B1 = `emulator-5854` | **`OC_Deliver_R2` / `emulator-6190`**，uptime≈0 h 全新冷盘 |
| 5 | **后端侧** | 同为 Lead 的 `127.0.0.1:4095` | 同上，但**本轮设备端实测 `opencode:opencode` → 200 / 无 auth → 401**（旧轮没留这条对照） |
| 6 | **性能几何** | P4/P5/B1 = `1080×2400@420`（411dp） | 本轮 = `1080×2400@420`（**同几何** ⇒ 性能横向可比性最高的一对） |
| 7 | **首屏口径** | 旧轮用 `am start -W` / 各自门禁 | 本轮 = 首条 `/api/event` 轮询，**且含 harness 固定开销** |
| 8 | **输入延迟口径** | framestats i2f/i2s，滑动 20 次，会话页 | **无样本**（composer 不可达）⇒ **不可比** |

> **本轮能下的唯一性能结论**：
> 「在这份 release 包上，从 `am start` 到官方 SPA 事件流开始轮询（含一次必需的对话框点击与 harness 固定开销），
> 5 次冷启的**中位数 7252 ms、极值 7095–7334 ms**。」
> **本轮不能下的结论**：任何与 F4 的 0 px IME 重叠、3/50 触摸靶，或 P5/B1 的 32.00/58.76 / 25 ms 的**直接比较**。
> 原因不是"变快/变慢"，而是 **构建变体 + 可调试性 + 探针行为 + 设备** 四项**条件同时不同**（见上表）。

---

## 4. 收尾复核（逐字，`out_v9/_closeout.txt`）

```
# V9 closeout  2026-10-05T07:35:48
[A] 关停前基线：全部 emulator 进程（只列，不动别人的）
  pid=30140 name=emulator.exe avd='OC_Deliver_R2' port='6190' <== 本轮的
  pid=36024 name=qemu-system-x86_64.exe avd='OC_Deliver_R2' port='6190' <== 本轮的

=== 端口 6190 关停前监听数 = 2 ===
[B] wm 复原（reset）
  $(wm size reset)    =>
  $(wm density reset) =>
  复原后读回:
    Physical size: 2560x1600
    Physical density: 320

[C] 关闭本轮自建实例（只按 AVD 名 + serial，不带 -a、不用通配符）
  目标: pid=30140 name=emulator.exe avd='OC_Deliver_R2' port='6190' MINE=True
  目标: pid=36024 name=qemu-system-x86_64.exe avd='OC_Deliver_R2' port='6190' MINE=True
  adb -s emulator-6190 emu kill
  => OK: killing emulator, bye bye |  | OK
  （等待 qemu 退出 ≤30s…）
  [3s] 本轮 AVD 进程已归零

[D] 复核 1：进程
  残留本轮 AVD (OC_Deliver_R2) 进程数 = 0
  pid=57892 name=emulator.exe avd='?' port='?' <== 别人的/来源不明，未碰
  pid=59244 name=emulator.exe avd='?' port='?' <== 别人的/来源不明，未碰
  本轮 AVD 的 qemu-system-x86_64 进程数 = 0   (这才是 CPU 幽灵占用的判据)

[E] 复核 2：端口
  Get-NetTCPConnection -LocalPort 6190 -State Listen => <空>，端口已释放

[F] 复核 3：adb devices（未执行 adb kill-server）
  List of devices attached
  （全程未执行 adb kill-server；未使用 -a / 通配符）

[G] 纪律自查
  后端 127.0.0.1:4095：LISTEN pid=3748（本轮 0 次启停，只做过 adb reverse tcp:4095）
  故意保留的实例：无
  .agent-gradle-lock exists = False

=== CLOSEOUT DONE ===
```

**复核判读**：
- `wm size/density reset` 读回 **`Physical size: 2560x1600` / `Physical density: 320`，无 `Override` 残留** ✅
- 本轮 AVD 进程数 **0**、本轮 AVD 的 **qemu 进程数 0**（CPU 幽灵占用判据）✅
- 端口 **6190 监听 = 空**，已释放 ✅
- `adb devices` **空**（本轮 serial 已随实例消失）✅
- 后端 **4095 仍 `LISTEN pid=3748`（同一 pid）** ⇒ 未启停 ✅
- **⚠️ 须声明**：`[D]` 出现 `pid=57892` / `pid=59244` 两个 `emulator.exe`，其命令行里**没有** `-avd` / `-port`
  （故脚本记为 `avd='?' port='?'`），**是在我关掉本轮实例之后才出现的**，**不是本轮起的**，
  **本轮没有对它们执行任何操作**（未 `emu kill`、未按 PID 杀、未用通配符）。
  按 `HOUSEKEEPING.md`「来源不明实例禁止 kill」，**故意保留**。

---

## 5. 我碰过的 serial

| serial | AVD | 是不是我起的 | 我对它做了什么 |
|---|---|---|---|
| **`emulator-6190`** | **`OC_Deliver_R2`** | ✅ **是（本轮唯一自建实例）** | 起（boot 已完成）→ 装 `opencode-shell-web-release-nor8.apk` → `adb reverse tcp:4095` → 6 格形态回归（`wm size/density` 覆盖 720x1280 / 884x2208 / 1416x2208 / 1400x2208 / 1640x2208 / 1366x768@160）→ 5 次冷启（`1080x2400@420`）→ 输入延迟尝试（失败）→ `wm size/density reset` → `emu kill` |

**本轮 `adb devices` 全程只出现 `emulator-6190` 一个 serial。**
用户的 `MCRemote_*` / `DSH_*` / `T7x_*` 设备**本轮一次都没起过、一次都没碰过、一次都没出现在 `adb devices` 里**。
**故意保留的实例：无。** 端口：只用 **6190**。

---

## 6. 纪律自查

| 规矩 | 结果 | 证据 |
|---|---|---|
| 谁起谁关 | ✅ | §4 `OK: killing emulator, bye bye` + 三重复核（进程/端口/`adb devices`） |
| 只关自己起的 | ✅ | 关停前基线里 `OC_Deliver_R2` 才标 `<== 本轮的`；关停按 **AVD 名 + serial** 定位 |
| 用户 AVD 禁止 kill | ✅ | `MCRemote_*`/`DSH_*`/`T7x_*` 本轮**一次都没出现在 `adb devices`**，一次都没碰 |
| 来源不明实例禁止 kill | ✅ | §4 `[D]` 的 `pid=57892/59244`（`avd='?'`）**故意保留**，未动 |
| 禁 `adb kill-server` | ✅ | §4 `[F]` 声明全程未执行 |
| 禁 `-a` / 通配符 | ✅ | 只用 `adb -s emulator-6190 emu kill`，无通配符杀进程 |
| 不许起停后端 | ✅ | `4095` 前后同为 `LISTEN pid=3748`；只做过 `adb reverse tcp:4095 tcp:4095` |
| 端口 ≥6190 偶数、冲突就换 | ✅ | 用 **6190**；起前查 `6190/6192/6194/6196/6198/6200/6202` **全 FREE** |
| 只用已装镜像 | ✅ | `android-35;google_apis_playstore_tablet` + `pixel_tablet`，未下载任何镜像 |
| 不用 `-skin` | ✅ | 启动命令无 `-skin`（`pixel_tablet` 不是合法 skin 名），几何全部用 `wm size/density` 覆盖 |
| `wm` 复原 | ✅ | §4 `[B]` reset 后读回无 `Override` |
| 写权限 | ✅ | 只写 `research/perf/**`；未碰 `app/**`、`webapp/src/**`、`_upstream/**`、`.github/**`、`research/deliver/**` |
| 不省任何一格 | ✅ | §1.1 六格全在表内；测不到的格子明确写「测不到 + 原因」，无一处写成「通过」 |

---

## 7. 未闭环

| # | 项 | 状态 | 说明 / 下一步 |
|---|---|---|---|
| 1 | 🔴 **IME 重叠量 6/6 格全测不到** | **未闭环（结构性）** | 根因链：包不可调试（无 CDP → 定位不了 composer）**且** F5 401 让列表停在 `Loading`（没有可进入的会话 → composer 根本不在屏幕上）。**需要**：(a) 先修 F5 探针 401（§0-F2/F3），或 (b) 出一份**可调试**的同源构建，才能复刻 F4 的 0 px 口径 |
| 2 | 🔴 **触摸靶 6/6 格全测不到** | **未闭环（同上）** | 需要 DOM 元素盒 ⇒ 同样卡在无 CDP |
| 3 | 🔴 **CDP 读数（title/节点数/选择器）6/6 格全测不到** | **未闭环（结构性）** | 本包 `flags` 无 `DEBUGGABLE`。**不是脚本缺陷**（`cdp_run_e6.ps1` 返回 `ERR_NO_DEVTOOLS_SOCKET` 是通道不存在） |
| 4 | 🔴 **输入延迟代理量测不到** | **未闭环** | 同 #1。`v9_perf.ps1` 里 framestats i2f/i2s 解析已按 P5/B1 同一口径写好，等 composer 可达即可直接复测 |
| 5 | 🔴 **F5 探针 401 未定位** | **未闭环** | 已排除"后端/口令问题"：设备端 `opencode:opencode` → 200。`HOUSEKEEPING.md` V8/F6 轮记录过同一现象（`passLen=8 authHeaderSent=true` 仍 401），**本轮复现**，定位需改 `webapp/src`（**本轮无写权限，未动**） |
| 6 | 🟡 **`review-toggle` 未证实** | **部分闭环** | 768dp 切换（700 移动 / 820 桌面）已用截图证实；`review-toggle` 字面串在 13 CSS + 33 JS 里都 grep 不到，且无 CDP 读不到 DOM ⇒ 只能说"分支切换存在"，**不能**说"就是 review-toggle" |
| 7 | 🟡 **第二切换边界未二分** | **部分闭环** | 已把 F4 #4 的问题从"完全未知"推进到 **(820dp, 1366dp] 区间内**（820dp 无左栏 / 1366dp 有左栏），未继续二分 |
| 8 | 🟡 **首屏 7.25 s 含 harness 开销** | **已声明** | 纯 App 侧口径是 `am start -W TotalTime` 中位数 **662 ms**。若要干净的冷启动数字，需去掉 3 s 固定 sleep + uiautomator 轮询（但那样就抓不到对话框） |
| 9 | 🟡 **`PROXY_GET=0`（形态轮）/ 52–63（冷启轮）** | 如实记录 | 形态轮 `_assets` GET 计数为 0 是因为**前一轮已把 bundle 缓存进 WebView**（同一 AVD 未 `pm clear`）；冷启轮 52–63 是缓存失效后的重新拉取。**本报告的首屏口径不依赖这个计数** |
| 10 | 🟡 **仅模拟器、单机型** | 沿袭 | 沿用 B1 #8：模拟器软件渲染地板会放大长尾，真机需复测 |
| 11 | 🟡 **`whitePct>15 ⇒ 疑似对话框` 判据在本轮失效** | 已识别 | 官方 UI 是**浅色主题**（白底），该判据把正常 UI 误判为对话框（6/6 格 `dialogLikely=True`）。**权威判据是 logcat `home=…passwordPresent=true` + `OcPerfJS` 计数**，本报告用的是后者 |
| 12 | 🟡 **踩坑留档** | 已闭环 | ① 脚本用 `add content` 写日志时 PowerShell 按 GBK 解码 adb 的 UTF-8 中文，导致 `text="继续加载"` 正则**永不命中**（`dumpBytes=6877 nodes=18` 明明是对话框）⇒ 加 `[Console]::OutputEncoding=UTF8` 才修好；② 设备 `date +%s.%N` 返回值位数异常，不能当计时器 ⇒ 改用宿主 `Stopwatch`；③ `DevTime-$t0` 被 PowerShell 解析成命令名 `DevTime-$t0` |

---

## 8. 本轮产出物

| 文件 | 内容 |
|---|---|
| `research/perf/REGRESSION_V9.md` | **本报告** |
| `research/perf/v9_boot.ps1` | 引导：boot 等待 / 装包 / `adb reverse` / 指纹 |
| `research/perf/v9_sweep3.ps1` | **形态 6 格主脚本**（几何 → 对话框绕行 → UI 可用 → CDP 可用性 → 截图 → IME） |
| `research/perf/v9_perf.ps1` | **性能脚本**（几何 → 5 次冷启 → framestats i2f/i2s 备用口径） |
| `research/perf/v9_close.ps1` | 收尾（`wm reset` → 只按 AVD 名+serial 关 → 三重复核） |
| `research/perf/out_v9/sweep_raw.txt` | **6 格原始输出**（逐格 wm 读回 / SYSTEM CONFIG / 对话框 bounds / logcat / CDP / IME 原始行） |
| `research/perf/out_v9/perf_raw.txt` | 冷启 + 输入延迟原始输出 |
| `research/perf/out_v9/formfactor.json` | 6 格结构化结果 |
| `research/perf/out_v9/coldstart.json` | 5 次冷启结构化结果 |
| `research/perf/out_v9/inputlatency.json` | `{"measured":false, ...}` |
| `research/perf/out_v9/fingerprint.txt` | 设备指纹 |
| `research/perf/out_v9/v9_<cell>_render.png` | 6 格渲染截图 |
| `research/perf/out_v9/v9_<cell>_ime.png` | 6 格 IME 尝试截图 |
| `research/perf/out_v9/served_*.css` | 后端实际下发的 13 个 CSS（768dp 断点旁证） |
| `research/perf/out_v9/_closeout.txt` | 收尾复核输出 |
| `research/perf/out_v9/apkx/`（9.5 MB） | ⚠️ **副产物**：为确认「SPA bundle 不在 APK 内而在后端」而解包的 APK 拷贝（`assets/www/` 只有 `index.html` / `oc-host.js` / `oc-perf.js` / `oc-preflight.js`，**没有上游 SPA**）。可删，不影响任何结论 |
| `research/perf/_v9_emu6190.log[.err]` | 模拟器启动 stdout/stderr 重定向 |
