# V12 · 交付包设备复验（错误态 + 常亮）— 在**当前交付包**上重做

> **为什么有这一轮**：A5 审计发现「错误态/常亮」的设备证据取自已作废构建
> （`bc7f3018…` / `627f8ed8…`），而交付包是 `76ca064b…`。
> 本轮**只**认 `research/deliver/opencode-shell-web-release-nor8.apk`。
> **未**使用 `bc7f3018…` / `627f8ed8…` / `b063148d…` 的任何结论。

- 轮次日期：2026-10-05（硬期限 2026-10-07 前）
- 设备：`emulator-6250`（AVD `OC_Deliver_R2`，**本轮自建 → 本轮关**）
- 原始产物目录：`research/web/out_v12/`

---

## 0. 装前 md5 复核（先复核再装）

```
$ Get-FileHash research/deliver/opencode-shell-web-release-nor8.apk -Algorithm MD5
Algorithm : MD5
Hash      : 76CA064B7DE9246EE446F765A9198B37
size      : 3975389
```

**装后设备端复核**（逐位一致）：

```
$ adb -s emulator-6250 shell pm path com.xiufeigo.opencodeshell.web.debug
/data/app/~~4wQGy4ecHhy5w0F2PSi1og==/com.xiufeigo.opencodeshell.web.debug-LkV-Ih_5Xb2mwkKujPHLmA==/base.apk

$ adb -s emulator-6250 shell md5sum /data/app/~~.../base.apk
76ca064b7de9246ee446f765a9198b37   /data/app/~~.../base.apk

LOCAL_MD5:  76ca064b7de9246ee446f765a9198b37
```

**判定：✅ PASS** — 装机字节 = 派单指定交付包，非旧构建。

---

## 1. 身份与首屏

### 1.1 设备指纹

```
SERIAL      = emulator-6250
AVD         = OC_Deliver_R2（镜像 android-35;google_apis_playstore_tablet + pixel_tablet 档位）
fingerprint = google/sdk_gtablet_x86_64/emu64xa:15/AE3A.240806.046.T1/13135149:user/dev-keys
model       = Pixel Tablet   Android 15   SDK 35   ABI x86_64
WebView     = 124.0.6367.219
几何         = wm size 1080x1600 + wm density 420
              （Override size: 1080x1600 | Override density: 420）
              ⚠ 未传 -skin（pixel_tablet 不是合法 skin 名，沿用 V7/F5 教训）
```

### 1.2 前置断言：**已鉴权 + 数据已到**（不是骨架屏）

> CDP 在本构建上**结构性不可用**，见 §1.3。改用宿主自报的内容信号 + 真实 API 落地 + 截图三重取证。

```
$ adb -s emulator-6250 shell logcat -d -s OcPerf OcPerfJS OcWebHost OcWebHost/Proxy
OcWebHost: credentials stored backend=http://127.0.0.1:4095 passwordPresent=true
OcWebHost: F5 probe OK code=200, loading official UI          ← 带凭据探测 200
OcPerf   : J.g1.preflightOk t=15264ms major=124 unknown=false
OcPerf   : J.loadUrlIssued t=15266ms url=http://127.0.0.1:43751/
OcPerf   : M.g1.firstContent t=… kind=dcl at=null             ← 内容信号（非骨架屏判据）
OcWebHost: web[LOG] ocFirstContent kind=dcl at=2123
OcWebHost: web[LOG] OcPerfJS res-total n=71 xfer=2983533 dec=7092092 ratio=0.42
```

**真实 API 已到**（18 个唯一 GET，含真实会话 ID）：

```
GET http://127.0.0.1:4095/api/info
GET http://127.0.0.1:4095/api/session?limit=5000&order=desc&parentID=null
GET http://127.0.0.1:4095/api/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y
GET http://127.0.0.1:4095/api/session/ses_f408ec4a0ffeKjpcGKG4kfGEBR
GET http://127.0.0.1:4095/api/project | /api/location | /api/vcs | /api/model
GET http://127.0.0.1:4095/api/provider | /api/skill | /api/agent | /api/command …
```

**截图肉眼判定（`E3_official.png` / `A_correct_ui.png`）**：官方 opencode UI，
`Home` / `All projects` / `Search sessions`，真实会话列表（`Counting from 1 to 4000…`、
`Friendly greeting`、`android-e2e`、`DRAFT__OK`、`New session`…）— **有内容，非骨架屏**。

**判定：✅ PASS**（截图 + 71 个资源落地 + 2 个真实 session id 三重互证）

### 1.3 CDP 为何不可用（**结构性**，非环境问题）

```
$ adb -s emulator-6250 shell cat /proc/net/unix | grep -E 'devtools|webview'
DEVTOOLS_SOCKETS=            ← 空
```

源码根因：`webapp/` 模块**从不**调用 `WebView.setWebContentsDebuggingEnabled`；
只有 `app/MainActivity.kt:466` 调用，且被 `BuildConfig.DEBUG` 门控
（`app/src/main/.../MainActivity.kt:466`）。`--es oc_debug_eval` 调试注入通道同样被
`WebHostActivity.kt:842  if (!BuildConfig.DEBUG) return` 关掉（release 构建）。

⇒ **本交付包上 `title` / 节点数 / 可见文本 这三个 CDP 口径测不到**，
已按派单「测不到就写测不到 + 原因」如实记录。替代口径见 §1.2。
（V8 轮已独立记录同一结论，本轮再次实测确认。）

### 1.4 首屏 ≥3 次（UI 可用标记 = `M.g1.firstContent`）

| 轮次 | firstContent | preflightOk | blockedOldWebview |
|---|---|---|---|
| RUN1 | **664 ms** | True | 0 |
| RUN2 | **667 ms** | True | 0 |
| RUN3 | **680 ms** | True | 0 |

- **中位数 = 667 ms**
- **极值：min 664 ms / max 680 ms**（极差 16 ms，n=3）
- 截图：`run1.png` / `run2.png` / `run3.png`

> 口径说明：三个数字是**已播种口令后的**应用内冷启（`am force-stop` → `am start`）。
> 若从「装完包、首次弹口令框」起算，则口令输入人工耗时另计（未纳入该口径）。
> ⚠ 脚本里 `FIRSTSCREEN_ALL_MS=664,667,680` 同屏打出的 `MEDIAN=680` 是我脚本
> 下标写错导致的**标签错误**，正确中位数为 **667**（三者居中值），以本行为准。

---

## 2. 错误态①后端不可达（错端口法）

**制造不可达**（派单指定：改错端口，**不用** `reverse --remove`）：

```
$ adb -s emulator-6250 reverse tcp:4095 tcp:4099
$ adb -s emulator-6250 reverse --list
host-16 tcp:4095 tcp:4099
```

**宿主判定走对分支**：

```
OcWebHost: F5 probe before load path=/api/info
OcWebHost: F5 probe unreachable reason=IOException, loading anyway
OcWebHost/Proxy: listening on http://127.0.0.1:41587 -> http://127.0.0.1:4095
OcWebHost/Proxy: upstream GET http://127.0.0.1:4095/ failed: unexpected end of stream
```

**服务出去的错误页 HTML 原文**（`curl http://127.0.0.1:41587/`，851 B，
`E1b_served.html`）—— 去掉标签后的可见文本：

```
连不上服务器
请检查服务器地址是否正确、设备是否连上了服务器所在的网络，然后重试。
重试
```

按钮样式含 `button{width:100%;min-height:48px;height:48px;…}` ⇒ **≥48dp 重试按钮在位**。

**断言（可见文本不含内部 URL/端口）**：

| 断言 | 结果 |
|---|---|
| 可见文本含 `127.0.0.1:4095` | **False** ✅ |
| 可见文本含端口号 `4095` | **False** ✅ |
| 有友好文案 | ✅（`连不上服务器` + `请检查服务器地址…`） |
| 有可点重试 | ✅（`重试`） |

截图：`E1b_unreachable.png` / `E1_unreachable.png`

**判定：✅ PASS**

> 取证方式说明：错误页是 WebView 内的 HTML，`uiautomator` **读不到**其文本
> （实测 `E1_UIAUTOMATOR_texts_present=False`）。因此改用
> **同一回环代理端口 `curl` 取回浏览器实际渲染的那份 HTML**再断言，
> 比截图更硬（逐字可比对）。产物 `E1b_served.html`。

---

## 3. 错误态②口令错

播种错误口令 `wrongpass`：

```
OcWebHost: credentials stored backend=http://127.0.0.1:4095 passwordPresent=true
OcWebHost: F5 probe AUTH FAILED code=401; showing retryable dialog
E2_probeOK=False        (expect False) ✅
```

**logcat 原文**（`log_E2.txt` / `log_E2b.txt`）：
`W OcWebHost: F5 probe AUTH FAILED code=401; showing retryable dialog`

**对话框可见文本**（`uiautomator` 逐字，`ui_E2_wrongpw.xml`）：

```
登录没通过
服务器拒绝了登录：用户名或口令不对。请确认后重试，或重新输入口令。
返回            继续加载            重试
```

- **明确「用户名或口令不对」** ✅（原文逐字命中）
- **可重试** ✅（`重试` 按钮，bounds `[810,862][978,988]`，可点）
- **不回显口令** ✅（`E2_ECHOES_PASSWORD=False`，全文无 `wrongpass`）
- 截图：`E2_wrongpw.png`（肉眼可见同一文案）

**换回对口令验证能正常进官方 UI** ✅：
另一条独立用例（§4 E3）用 `opencode` 播种后 → `F5 probe OK code=200` →
`M.g1.firstContent` 触发 → 官方 UI 截图 `E3_official.png`（真实会话列表）。

**判定：✅ PASS**

> ⚠️ 两条命令的 `-match '用户名或口令不对'` 曾在脚本里打出 `False`，
> 那是 **PowerShell 控制台编码**把多字节字打坏了，**不是**页面缺文案。
> 已用 `[IO.File]::ReadAllText(...,UTF8)` 复核，节点原文为
> `服务器拒绝了登录：用户名或口令不对。请确认后重试，或重新输入口令。`，截图同样可见。

---

## 4. 错误态③正确口令 ⇒ 直接进官方 UI，不弹错误对话框

```
OcWebHost: F5 probe OK code=200, loading official UI
OcPerf   : H.launch.enter t=15262ms backend=http://127.0.0.1:4095 mode=gzip
OcPerf   : I.proxyListening t=15264ms port=43751
OcPerf   : J.g1.preflightOk t=15264ms major=124 unknown=false
OcPerf   : M.g1.firstContent t=…  kind=dcl
E3_AUTH_FAILED       = 0
E3_HAS_AUTH_DIALOG   = False
E3_HAS_UNREACH       = False
E3_API_GETS_UNIQUE   = 10
E3_SESSION_IDS       = ses_efc722bf9ffeWaRdoFTwbmYj1Y, ses_f408ec4a0ffeKjpcGKG4kfGEBR
```

**`HAS_AUTH_DIALOG` 类断言**（`ui_E3_official.xml` + 截图）：

| 断言 | 结果 |
|---|---|
| `HAS_AUTH_DIALOG`（`用户名或口令不对`） | **False** ✅ |
| `HAS_UNREACH_DIALOG`（`连不上`/`检查服务器地址`） | **False** ✅ |
| `AUTH FAILED` 计数 | **0** ✅ |
| 官方 UI 真实内容 | ✅ `E3_official.png` |

**判定：✅ PASS**

---

## 5. 回合常亮三段（`dumpsys window` 原文，带时间戳）

驱动方式：**用真实回合起止**驱动宿主自己的 `RoundActivityTracker`
（代理看见「POST 消息」= 回合开始；回合静默超时 = 回合结束），
而不是伪造 flag。

| 段 | 墙钟 | `window_KEEP_SCREEN_ON_hits` | `flagSet(0x80)` | 判定 |
|---|---|---|---|---|
| PHASE0 基线 | `2026-10-05 09:00:44` | **0** | false | — |
| PHASE1 置起 | `2026-10-05 09:00:49` | **1** | **true** | ✅ 置起成立 |
| PHASE2 回合结束释放 | `2026-10-05 09:02:29` | **0** | false | ✅ 释放成立 |
| PHASE3 **+95 s** | `2026-10-05 09:04:04` | **0** | false | ✅ 未复挂 |

**PHASE1 置起的 `dumpsys window windows` 原文**：

```
Window #9 Window{41544a5 u0 com.xiufeigo.opencodeshell.web.debug/
       com.xiufeigo.opencodeshell.web.WebHostActivity}:
  mAttrs={(0,0)(fillxfill) sim={adjust=resize} layoutInDisplayCutoutMode=always
          ty=BASE_APPLICATION wanim=0x1030309
          fl=KEEP_SCREEN_ON LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH
             HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
          pfl=NO_MOVE_ANIMATION EDGE_TO_EDGE_ENFORCED …}
  Frames: parent=[0,0][1080,1600] display=[0,0][1080,1600] frame=[0,0][1080,1600]
```

**PHASE0 / PHASE2 / PHASE3 同一窗口的 `fl=` 里 `KEEP_SCREEN_ON` 消失**
（hits 0/0/0，窗口仍在 `Window #9`）。

**`dumpsys power` 原文**（四段一致，摘 `Power Manager State` 段）：

```
Power Manager State:
Settings power_manager_constants: no_cached_wake_locks=true
mDirty=0x0 | mWakefulness=Awake | mWakefulnessChanging=false | mIsPowered=false
mPlugType=0 | mBatteryLevel=100
```

`dumpsys power` 不枚举 `KEEP_SCREEN_ON`（它只管 wakelock；`FLAG_KEEP_SCREEN_ON`
是 window flag），故以 `dumpsys window windows` 的 `fl=` 为准 —— 两份原文都已抄录。

**判定：✅ PASS** — 置起 → 回合结束释放 → **+95 s 仍 `flagSet=false`**，三段全成立。

---

## 6. IME 重叠量

**读前断言 `mInputShown=true`** ✅（在设备端同一个 shell 调用里「点一下 + 读」，
避免 `input tap` 把键盘 toggle 掉后再读的竞态）：

```
$ adb -s emulator-6250 shell "st=$(dumpsys input_method | grep -o 'mInputShown=[a-z]*' | head -1); \
   echo PRE=$st; if [ \"$st\" != 'mInputShown=true' ]; then input tap 540 1450; sleep 2; fi; \
   st2=$(...); echo POST=$st2; …"
PRE=mInputShown=false
POST=mInputShown=true
CAPTURED_OK
```

**`type=ime` 原始行**（`dumpsys window windows`，`tr '|' '\n'` 后逐行）：

```
Window #8 Window{3ceccc7 u0 InputMethod}:
  Frames: parent=[0,63][1080,1600] display=[0,63][1080,1600] frame=[0,63][1080,1600]
          last=[0,63][1080,1600] insetsChanged=false

Window #9 Window{41544a5 u0 com.xiufeigo.opencodeshell.web.debug/…WebHostActivity}:
  Frames: parent=[0,0][1080,1600] display=[0,0][1080,1600] frame=[0,0][1080,1600]
          last=[0,0][1080,1600] insetsChanged=false
```

**IME 实际占位**（`dumpsys window windows` 的 insets 原文）：

```
mGivenContentInsets=[0,1411][0,0] mGivenVisibleInsets=[0,1411][0,0]
```

⇒ 键盘顶边 `y=1411`，键盘高度 `1600-1411 = 189 px`。

**重叠量计算（窗口矩形口径）**：

```
appBottom = 1600
imeTop    = 1411
overlap   = 1600 - 1411 = 189 px
```

**判定：⚠️ 未达派单预期的 0** —— 实测窗口级重叠 **189 px**，不是 0。

如实说明：这台设备上宿主窗口是
`sim={adjust=resize}` + `pfl=FIT_INSETS_CONTROLLED` + `EDGE_TO_EDGE_ENFORCED`，
**窗口本身保持全高 1600**（不因键盘 resize），键盘靠 **insets 内边距**避让，
所以「窗口矩形 ∩ 键盘」必然等于键盘高度 189 px。
**派单要的「预期 0」是内容级口径**（网页可视内容是否被键盘盖住），
而内容级判定需要 CDP/JS 量 `document.body` 的可视高度 —— 本构建 CDP 不可用（§1.3），
**该口径本轮测不到**。

- 已成立：**前置断言 `mInputShown=true` ✅**、`type=ime frame=` 原始行 ✅
- 未闭环：**内容级重叠量 = 0**（无 CDP ⇒ 测不到）
- 窗口级重叠量 = **189 px**（如实报出，不粉饰成 0）

截图：`ime_shown.png`（207,313 B，键盘已弹起）
产物：`ime_input_method.txt` / `ime_window_dump.txt`

> ⚠️ 另记：早先一版脚本用「第一个含包的 `Window{}` 块」取 frame，
> 抓成了 `ScreenDecorOverlayBottom`（两窗口 frame 相同 ⇒ 报出 `overlap=95px`），
> **那个 95 px 是解析假象，已作废**，以本节 189 px 为准。

---

## 7. 预检放行

```
OcPerf: J.g1.preflightOk t=17384ms major=124 unknown=false
OcWebHost: G1 webview version=124.0.6367.219 major=124 min=105 tooOld=false
```

| 断言 | 结果 |
|---|---|
| `J.g1.preflightOk` | ✅ 命中（每次启动都有，共 5+ 次） |
| `blockedOldWebview` 计数 | **0** ✅ |

**判定：✅ PASS** — 预检放行，无旧 WebView 拦截。

---

## 8. 收尾复核输出（housekeeping）

```
[09:07:05] BEFORE emulator/qemu procs = 2
[09:07:05] MINE pid=… avd=OC_Deliver_R2 port=6250   ×2（MINE_COUNT=2，父子进程对）
[09:07:06] BACKEND_4095: LISTEN pid=3748
[09:07:06] WM_SIZE_RESET / WM_DENSITY_RESET
[09:07:06] WM_SIZE_AFTER:    Physical size: 2560x1600          ← 已无 Override
[09:07:06] WM_DENSITY_AFTER: Physical density: 320            ← 已无 Override
[09:07:06] EMU_KILL: OK: killing emulator, bye bye  OK
[09:07:31] VERIFY adb devices: List of devices attached        （空）
[09:07:31] VERIFY emulator/qemu procs = 0
[09:07:31] VERIFY OC_*/Medium_Desktop_B residual = 0
[09:07:31] VERIFY port 6250: FREE
[09:07:31] VERIFY port 6252: FREE
[09:07:31] VERIFY port 6254: FREE
[09:07:32] VERIFY 4095 backend still: LISTEN pid=3748           ← 与开轮同一 pid，未起停
[09:07:32] LOCK .agent-gradle-lock exists = False
```

- **未执行** `adb kill-server`、**未用** `-a` / 通配符，**只按 serial `emulator-6250` 关**。
- **故意保留：无。**
- 用户自己的 `MCRemote_*` / `DSH_*` / `T7x_*` 与来源不明实例**全程未碰**。
  开轮时 `adb devices` 为空、全机 emulator/qemu 进程数 0 ⇒ **不存在可被波及的用户设备**。
- 后端 `4095` 全程**未起停**（开轮前与收尾均 `LISTEN pid=3748`，同一 pid），只做 `adb reverse`。

### 我碰过哪些 serial

| serial | AVD | 端口 | 起 | 关 |
|---|---|---|---|---|
| `emulator-6250` | `OC_Deliver_R2` | 6250 | 本轮自建 | 本轮 `emu kill` ✅ |

**本轮只碰过 `emulator-6250` 一个 serial。**

---

## 9. 未闭环 / 测不到的（如实列出）

1. **CDP 全套口径测不到**（`title` / 节点数 / WebView 可见文本）——
   根因：`webapp/` 从不调 `setWebContentsDebuggingEnabled`，`app/` 的调用被
   `BuildConfig.DEBUG` 门控，release 构建无 devtools socket（实测
   `DEVTOOLS_SOCKETS=` 空）。替代口径见 §1.2。
2. **IME 内容级重叠量 = 0 未闭环** —— 需 CDP 量 `document.body` 可视高度，
   本构建不可用。窗口级重叠 189 px 已如实报出（§6）。
3. **首屏口径不含「装完包首次弹口令框」的人工输入耗时**（§1.4 已标注口径）。
4. 常亮 PHASE1 的回合起点由**直接 POST 会话消息端点**驱动（`POST /api/session/<id>/message`，
   返回 404，说明该端点未被后端接受），**但 `RoundActivityTracker` 在
   「拒绝判定之前」就已置起回合**（`LoopbackProxy.kt:322-328` 注释明示此设计），
   故置起链路真实；**未**通过官方 UI 亲手按发送键驱动（时间不够）。
5. 早期一版播种脚本按 `text="保存并连接"` 找按钮**失败**（`uiautomator` 会把多字节
   截断成 `保存并连?`），改按 **bounds**（底部按钮行最右可点节点）才稳定命中。
   写进交接，避免下轮重踩。
6. `reverses` 的「后端不可达」用的是**错端口**（`tcp:4095 → tcp:4099`），
   **未**用 `reverse --remove`；事后 `reverse tcp:4095 tcp:4095` 覆盖恢复。

---

## 10. 判定汇总

| # | 项 | 判定 |
|---|---|---|
| 0 | 装前/装后 md5 = `76ca064b7de9246ee446f765a9198b37` | ✅ |
| 1 | 身份 + 首屏（已鉴权、数据已到、非骨架屏） | ✅ |
| 2 | 错误态①后端不可达（可见文本不含 `127.0.0.1:4095`/端口） | ✅ |
| 3 | 错误态②口令错（「用户名或口令不对」+ 可重试 + 不回显） | ✅ |
| 4 | 错误态③正确口令（直接进官方 UI，无对话框） | ✅ |
| 5 | 常亮三段（置起 → 释放 → +95 s 仍 false） | ✅ |
| 6 | IME 重叠量 | ⚠️ 前置断言 PASS；窗口级 189 px，**内容级 0 未闭环** |
| 7 | 预检放行（`preflightOk`，无 `blockedOldWebview`） | ✅ |
| — | 收尾（`wm` reset / 关实例 / 复核） | ✅ |
