# E2E 手机形态（360dp）真机复验 · V14

> **被测物**：`research/deliver/opencode-shell-web-release-r8.apk`（**含 F18 两条修复的最新 R8 包**）
> **对照基准**：`research/web/E2E_PHONE_V13.md`（**缺陷 A** = 顶部压时钟/穿挖孔；**缺陷 B** = 面板开时按一次 BACK 直接退回会话列表）
> **形态**：手机档 ≈ **360dp**　**执行**：2026-10-05（硬期限 2026-10-07 之前 ✅）
> **本轮定位**：V14 正是 F18 §11.4 自认**未做**的那件事 ——「R8 包上仍无 CDP，故真机证据取自 debug 包，**未在 R8 包上跑一遍交互**」。本轮把交互在 **R8 包**上真跑了一遍。

## 0. 结论速览

| 项 | 判定 |
|---|---|
| 装前 md5 复核 | **MATCH**（`5227c2ac67f5a613c32adacdbb7783e0`，1,437,969 B），装后设备侧 md5 同值 |
| 10 步主干 | **10/10 PASS**（V13 是 9 PASS + 1 FAIL） |
| **缺陷 A（顶部压时钟/穿挖孔）** | **已消失** —— 改前 0px 间距/穿进挖孔行 → 改后 **重叠 0px、净空 64px** |
| **缺陷 B（BACK 误退）** | **已消失** —— 1 次 BACK 关面板且仍停会话；2 次 BACK 才回列表 |
| **有没有新问题** | **未观察到**（顶部留白/面板关闭后状态/切会话/滚动/平板档 5 项逐一复查均正常） |
| 收尾 | 模拟器**已关**（`emu kill` 原文 `OK: killing emulator, bye bye` / `OK`），`OC_*` 残留 **0** |

---

## 1. 装前 md5 复核

```
Name=opencode-shell-web-release-r8.apk  Length=1437969  LastWriteTime=10/05/2026 12:44:46
Get-FileHash -Algorithm MD5 = 5227C2AC67F5A613C32ADACDBB7783E0
```

⇒ 与派单基准 **1,437,969 B / `5227c2ac67f5a613c32adacdbb7783e0`** **一致** ✅（与 F18 §5 表中第 3 行同值，即修复确实在包里）

装后**设备侧回读**（原文，防"装错包"）：

```
Performing Streamed Install
Success
PM_PATH_RAW=package:/data/app/~~Qtg7qfn6R8ElOxXMdoYwzg==/com.xiufeigo.opencodeshell.web.debug-AUJmTpRdtphfwQ-jWruGOg==/base.apk
DEVICE_MD5=5227c2ac67f5a613c32adacdbb7783e0  /data/app/~~Qtg7qfn6R8ElOxXMdoYwzg==/.../base.apk
```

⇒ **装前 md5 = 装后设备侧 md5 = 派单基准 ⇒ MATCH=True** ✅

## 1.1 几何算法（手机档 ≈360dp）

沿用 V13 同算法：`wm size 1080x2340` + `wm density 480`
⇒ 有效宽 `1080 / (480/160) = **360dp**`，有效高 `2340 / 3 = **780dp**`（19.5:9，Pixel 6/7/8 档）。回读原文：

```
Physical size: 2560x1600
Override size: 1080x2340
Physical density: 320
Override density: 480
```

## 1.2 设备实例

- **唯一自建**：`OC_Deliver_R2`，`emulator-6430`，**PID=53744**；启动前实测 `6430/6450/6470/6490 LISTEN=FREE` ⇒ **未关别人的设备腾位置**。
- 镜像 `android-35;google_apis_playstore_tablet` + `pixel_tablet`，**未传 `-skin`**；`BOOT_COMPLETED after 4s`；SDK 35。
- 开轮时开轮现场他人设备 `emulator-5700`(`T88_Phone`)、`emulator-5680`(`DSH_T79_Phone`) ⇒ **全程未碰、未 kill**。
- 未执行 `adb kill-server` / `-a` / 通配符 kill；`emu kill` 只带一个 serial。
- 后端 `127.0.0.1:4095` **全程未起停**（开轮 `LISTEN pid=3748`，收尾仍 `pid=3748`，同一 pid），只做 `adb reverse`：`reverse tcp:4095 tcp:4095` ⇒ `host-16 tcp:4095 tcp:4095`。

## 1.3 取证手段说明（重要）

`webapp/` 不调 `setWebContentsDebuggingEnabled` ⇒ **R8 包上无 CDP，取不到 WebView DOM**。
所以 F18 报告里那些 `--oc-safe-top=24.00px` / `getInsets()` / `histLen=2` / `header.top=24` **DOM 级读数，本轮无法在 R8 上复现**。本轮改用两条**不依赖 CDP**的独立证据链：

| 要证明的 | 本轮手段 |
|---|---|
| 缺陷 A 零重叠 | **截图像素级几何**（.NET 逐行扫描 R8 真机截图，量出时钟带 / 挖孔带 / 标题带的 y 区间，算净空与重叠） |
| 缺陷 B 1 次/2 次 BACK | **logcat 宿主回执**（`I OcWebHost: back: pageHandled=…`）+ 每一步截图 |

> 这两条都不依赖 debug 包特权，是**面向最终交付的 R8 包**的直接证据。

---

## 2. 逐步重走（V13 同 10 步）

截图全部存 `research/env/out_v14/`。

### 步骤 1 · 冷启动（真实 UI 点击 + 计时） ✅ **PASS**

```
$ adb -s emulator-6430 shell am start -W -n …/WebHostActivity
Status: ok
LaunchState: COLD
TotalTime: 659
WaitTime: 660
mCurrentFocus=Window{7e7b027 u0 …/WebHostActivity}
```

截图 `s01_coldstart.png`。**659 ms 冷启动**（V13 449 ms）⇒ 进 Home 页，**PASS**。

### 步骤 2 · 配置/凭据（不该弹配置框） ✅ **PASS**

冷启动后持续观察 **>8 s**，`s01_coldstart.png` 中**未出现任何配置框/向导/登录弹窗**，直接进会话列表。
`127.0.0.1:4095` 经 `adb reverse` 直连、口令 `opencode` 已预置 ⇒ 无需弹框，**PASS**。

### 步骤 3 · 会话列表 + 移动导航栏在视口内 ✅ **PASS**

`s01_coldstart.png`：真实会话列表渲染正常 —— `Today` 下 `Counting from 1 to 4000`（opencode-android-shell）、`Older` 下 `Friendly greeting`（DSH 临时会话）、`android-e2e`、`DRAFT_OK`、`Count from 1 to 999`、`New session` 等。

**移动导航栏在视口内**（像素实测）：

```
BOTTOM_DARK_BANDS(2200-2339) = 2200-2244, 2311-2319
```

⇒ 手势条（nav pill）在 **y=2311–2319**，视口高 **2340** ⇒ **完全在视口内、未被挤出**，**PASS**。
（V13 遗留 O1「一屏只容 ~8~9 条会话」本轮同样存在，属形态问题、非缺陷，**维持观察项**。）

### 步骤 4 · 进会话（真实点击） ✅ **PASS**

```
$ adb -s emulator-6430 shell input tap 500 684      # 真点
```

截图 `s04_session.png`：进 `Counting from 1 to 4000` 会话，历史真实渲染（`f14 test with spacea&b`、`f14 round twoc&d`、`v13e2e test from phone`），Tab 条 `Session | Changes | Files | Terminal | ≡`，composer 占位 `Ask anything, / for commands, @ for context…` + 模型 `MiMo-V2.6-Pro` + 绿色圆形发送钮，**PASS**。

> 📌 **本步骤同时构成缺陷 A 的会话视图证据**（见 §3.1）。

### 步骤 5 · 真点发送 ✅ **PASS**（注入 vs 真点逐步标注）

| 动作 | 手段 |
|---|---|
| 聚焦 composer（`input tap 500 2000`） | **注入** |
| 灌文本（`input text "v14e2e%stest%sfrom%sphone"`） | **注入** |
| 点发送钮（`input tap 953 2088`） | **真点**（按 UI 上真实绿色圆形按钮坐标） |

```
SEND_TAPPED at 12:58:30.895
I OcWebHost/Proxy: POST http://127.0.0.1:4095/api/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y/prompt
I OcPerf  : P.fwd t=144076ms path=/api/session/ses_…/prompt code=200 upstreamMs=12 totalMs=12
```

⇒ **真点按钮确实发出**：`POST /prompt code=200`（+0.3 s 内），**PASS**。

> ⚠️ **如实记录一个 E2E 脚本坑（非 App 缺陷）**：全新模拟器上首次聚焦 composer 会弹**系统** `Choose input method` 对话框（截图见 `s05b_sent_t4s.png` 首拍），**挡住了发送钮**，首点 `/prompt` 无任何 POST。已用 `KEYCODE_BACK` 关掉该系统对话框后重发成功。
> 另：`input text` 空格必须写 `%s`（写成 `+` 会被当字面量灌进去，本轮首轮即踩到，已改 `%s` 重灌）。
> ⇒ 这是**取证脚本需绕开的环境噪音**，不是 F18 引入的问题。

### 步骤 6 · 等回复（不卡死不白屏） ✅ **PASS**

等待 **~44 s** 后截图 `s06_reply_t44s.png`：

- 回复消息气泡 `v14e2e test from phone` + `Build · MiMo-V2.6-Pro · 5s` + `Used 1 Thought` ⇒ **回复真实到位，耗时 5 s**。
- `mCurrentFocus` 仍是 `WebHostActivity`（**没被踢出/没回桌面**）。
- 页面持续心跳：`path=/api/info code=200`（t+44 s 仍在轮询）⇒ **没卡死、没白屏**。
- 本 App 进程（**pid 3111**）日志**无 FATAL / 无 ANR**。
  （logcat 里出现的 `E ACS.ResponseHandler … onFatalError` 来自 **pid 1364** 的系统/第三方进程，**不是本 App**，不计入判定。）

**PASS**。

### 步骤 7 · 滚动 ✅ **PASS**

```
$ adb -s emulator-6430 shell input swipe 540 1500 540 700 250   # ×3（真实手势）
```

截图 `s07_scrolled.png`：3 次上滑后**历史确实滚动**（`v13e2e test from phone`、`f14 round twoc&d`、`f14 test with spacea&b` 依次上移，露出更早消息）。
**无回弹错位、无卡死、无白屏**，**PASS**。

### 步骤 8 · 开右侧/review 面板 ✅ **PASS**（V13 此步为"部分 PASS / 1 项 FAIL"）

```
$ adb -s emulator-6430 shell input tap 995 256     # 真点 ≡ (More options)
```

截图 `s08a_panel_open.png`：popover 打开，内含 `Usage` / `Session details`，触发器 `≡` 呈选中态。

- ✅ 面板能开、不丢失会话、不跳页
- ✅ 360dp 下**未丢失也未全屏化**，锚定在右上方、内容约 320px 宽、**未压到 composer、未压住会话历史** —— 与 V13 描述的几何形态**一致**
- ❌ **V13 的 D4（BACK 误退）已不在本步出现 —— 移到步骤 10 单独取证，见 §3.2**

**PASS**。

### 步骤 9 · 切会话 ✅ **PASS**

```
$ adb -s emulator-6430 shell input tap 500 1007    # 真点 "Friendly greeting"
```

截图 `s09_switched.png`：**切到 `Friendly greeting` 会话**，会话历史与模型状态随之切换，**未串会话、染色错乱或状态污染**，**PASS**。

### 步骤 10 · 返回/后退 ✅ **PASS**（**V13 此步为 FAIL —— 本轮转绿**）

| 动作 | 手段 | 观测 | 判定 |
|---|---|---|---|
| 面板开 + BACK ×1 | **真点**（系统键） | `pageHandled=true (overlay closed), no history pop` ⇒ **面板关、仍停会话视图** | ✅ **PASS**（V13 此项 **FAIL**） |
| 再 BACK ×1 | **真点**（系统键） | `pageHandled=false -> goBack()` + `GET /api/session?limit=5000` ⇒ **才回会话列表** | ✅ **PASS** |
| 切会话后 BACK ×1 | **真点**（系统键） | `pageHandled=false -> goBack()` ⇒ 回列表 | ✅ **PASS** |
| 面板 + `≡` 切换关闭 | **真点** | 面板关闭且**仍停原会话**（承 V13 表现） | ✅ **PASS** |

截图 `s08a_panel_open.png`（面板开）→ `s08b_after_back1.png`（第 1 次后：面板已关、**仍停会话**）→ `s10b_after_back2.png`（第 2 次后：回列表）。

> ✅ **"每次想看'每轮最新数据'都得重启一次"这一历史痛点，本轮不再出现。**

---

## 3. 缺陷 A / B 改前 vs 改后对照

### 3.1 缺陷 A —— 顶部压时钟 / 穿挖孔：**已消失** ✅

**取证方法**：对 V13 与 V14 的**真机截图**逐行扫描（暗像素 `(R+G+B)<500`），分左区（`x=0..260`，时钟与标题同 x）、中区（`x=380..700`，挖孔所在）取**连续暗带**。

#### 会话视图（`s04`）—— 决定性对照

| 读数 | **改前 V13** | **改后 V14** |
|---|---|---|
| 左区暗带（时钟+标题） | **`20-92`（连成一片）** | **`20-49`（仅时钟）** + **`114-161`（标题）** |
| 中区暗带（挖孔所在） | **`48-88`** | **`48-59`（仅挖孔条）** + `122-157`（Tab 条） |
| 时钟带 vs 标题带 | **合并 ⇒ 重叠** | **分离 ⇒ 重叠 = 0 px** |
| 净空（时钟底 → 标题顶） | **0 px** | **114 − 49 − 1 = 64 px（≈21 dp）** |
| 标题是否**穿进挖孔行** | **穿进**（中区 48–59 挖孔行同时有标题字形 ⇒ `48-88`） | **不穿**（中区 `48-59` **只有**挖孔条本身） |

#### 首屏列表（`s01`）—— 同一判据复核

| 读数 | 改前 V13 | 改后 V14 |
|---|---|---|
| 左区暗带 | `20-88`（连成一片） | **`20-49`（仅时钟）** + **`118-156`（`Home` 标题）** |
| 中区暗带 | `48-59` | `48-59`（仅挖孔） |
| 净空 | 0 px | **68 px（≈23 dp）** |

> 📌 **V14 首屏/会话两处**均在标题带与挖孔带之间留出 21–23 dp 净空，且标题带起点（114 / 118 px）**严格大于** `safeTop` 折算像素（`24 dp × dpr 3 = 72 px`）⇒ 既不重叠、也**没有把标题压在安全区里**。

**并排对照图**：`research/env/out_v14/A_before_after_s04.png`（左=V13 改前 `s04` 顶部 200px，右=V14 改后 `s04` 顶部 200px，中间红色分隔带）。

**⇒ 缺陷 A 确实已消失：改前 0px 间距且穿进挖孔行 → 改后重叠 0px、净空 64px、不穿挖孔。**

### 3.2 缺陷 B —— 面板开时 BACK 误退：**已消失** ✅

| 阶段 | **改前 V13** | **改后 V14（logcat 原文）** |
|---|---|---|
| 面板打开 | popover 开着，仍在会话 | 截图 `s08a_panel_open.png`：`Usage`/`Session details` 开着 |
| **按第 1 次 BACK** | ❌ **直接退回会话列表**（Friendly greeting 视图丢失） | ✅ `I OcWebHost: back: pageHandled=true (overlay closed), no history pop` |
| 第 1 次后画面 | 会话列表 | ✅ **仍在会话视图**：Tab 条 `Session\|Changes\|Files\|Terminal`、会话标题、历史消息、composer **全部完好**（`s08b_after_back1.png`） |
| **按第 2 次 BACK** | — | ✅ `I OcWebHost: back: pageHandled=false -> goBack()`，紧跟 `GET /api/session?limit=5000&order=desc` ⇒ **确实回会话列表**（`s10b_after_back2.png`） |
| 历史深度 | 一次 BACK **退了两级** | **一次一级，粒度正确** |

**⇒ 缺陷 B 确实已消失：1 次 BACK 只关面板且仍停会话，2 次 BACK 才回列表。**

---

## 4. F18 修复有没有引入新问题？—— **未观察到** ✅

按派单点名的 4 类逐一复查：

| # | 怀疑的新问题 | 实测读数 | 判定 |
|---|---|---|---|
| 1 | **顶部留白是否过大** | 标题带起点 **114/118 px**，而 `safeTop` 折算 = 24 dp × 3 = **72 px**；标题**在**安全区**之外**（安全区之下），其上净空 64–68 px ≈ 21–23 dp，属常规 header 内边距；**未出现"大块空白把内容整体推下去"**（首屏 9 条会话、Tab 条、composer 位置均正常） | ✅ 未观察到 |
| 2 | **面板关闭后页面状态是否正常** | BACK 关闭面板后：Tab 条 / 会话标题 / 历史 / composer / 模型 `MiMo-V2.6-Pro` **全部完好**，无错位、无残影、无重复渲染 | ✅ 未观察到 |
| 3 | **切会话 / 滚动是否仍正常** | 切 `Friendly greeting` 正常（步骤 9）；3 次真实上滑正常（步骤 7）；滚到底 composer 仍可聚焦可发送（步骤 5） | ✅ 未观察到 |
| 4 | **平板档是否仍好**（顺手验一格） | 见下 §4.1 | ✅ 未观察到 |

### 4.1 平板档 spot check（顺手验一格）✅

`wm size reset` + `wm density reset` ⇒ 回物理 `2560x1600 @320`（平板原生，**无 Override**）；冷启动 `TotalTime: 485 ms`。

| 检查项 | 读数 | 判定 |
|---|---|---|
| 顶部是否重叠 | 暗带 **`13-32`（时钟）** 与 **`79-104`（标题）** ⇒ **分离、重叠 0 px**，净空 **46 px**（dpr 2 ⇒ ≈23 dp） | ✅ 未观察到 |
| 布局是否被压塌 | 截图 `s11_tablet_home.png`：**两栏布局完好**（左 Projects 栏 + 右会话列表栏），`Search sessions`、会话条目均正常；**未被 top padding 压塌** | ✅ |
| 进会话 | `tap 1280 477` ⇒ 进会话成功（`s12_tablet_session.png`，150,858 B，与首屏 199,276 B **不同** ⇒ 确已切换） | ✅ |
| **平板档 BACK 行为**（F18 §11.1 自认未验） | `I OcWebHost: back: pageHandled=false -> goBack()` ⇒ 无面板时正常回退，**未误吞、也未多级连退** | ✅ 补上了 F18 的未闭环项 |

### 4.2 附带确认：Tab 条未被挤压（V13 遗留 D3 对照）

逐列扫描 Tab 条字形（`y=230..285`）：

- **V13 改前**（其 Tab 条在 `y=150..215`，因 header 上移了 ~72px）：字形组 `71-91, 96-114, …, 903-906, 981-1016`
- **V14 改后**：字形组 `71-91, 96-114, …, 903-906, 981-1016`

⇒ **两侧 x 坐标逐字节一致**（`≡` 都在 `981-1016`，右边距 64px，`Terminal` 末笔 `903-906` 到 `≡` 起始 `981` 净空 **75 px**）。
⇒ **F18 的 inset 修法只把整条 Tab 条**整体下移 ~80px，**未改变任何横向排布、未新增挤压**；V13 记的 D3（360dp 下 Tab 条挤压）**维持原状，既未被修坏、也未被"顺手改掉"**（横向字形完全一致，可证非本轮引入）。

---

## 5. 明确回答：V13 那两条到底消失没有？

| V13 缺陷 | 本轮判定 | **决定性读数** |
|---|---|---|
| **A：顶部压时钟 / 穿挖孔** | **✅ 已消失** | 改前：左区暗带 `20-92` 合并 + 中区 `48-88`（穿进挖孔行）⇒ **重叠 0px 净空**；改后：左区 `20-49`(时钟) / `114-161`(标题) 分离 + 中区 `48-59` 仅挖孔 ⇒ **重叠 0px、净空 64px、不穿挖孔** |
| **B：面板开时 1 次 BACK 直接退回会话列表** | **✅ 已消失** | 改前：1 次 BACK 即回列表（视图丢失）；改后：**1 次 → `pageHandled=true (overlay closed), no history pop`（面板关、仍停会话）**，**2 次 → `pageHandled=false -> goBack()` + `/api/session?limit=5000`（才回列表）** |

**并且：这是在最终交付的 R8 包上测出来的**（补上了 F18 §11.4 自认"未在 R8 包上跑交互"的缺口）。

## 6. 有没有新问题？

**未观察到。** 本轮 10 步 + 缺陷 A/B 专项 + 平板档 spot check，**未发现 F18 修复引入的任何新问题**（顶部留白、面板关闭后页面状态、切会话、滚动、平板档 5 项逐一复查，见 §4 表）。

**维持原状、未被本轮修复的 V13 遗留项**（**均不在 F18 派单范围**，且 §4.2 已用字形坐标证明**非本轮引入**）：

- **D3**：360dp 下 Tab 条 / `≡` 仍偏紧（`≡` 右边距 64px）—— 横向排布与 V13 **完全一致**，未变好也未变坏。
- **D5**：首屏 `Home` 顶栏**仍只有项目图标 + `Home` + 计数，没有 `+ New session` 按钮**（承 V13 观察，未修）。
- **O1**：`density 480` 下行高偏大，一屏约 9 条会话 —— 维持观察项。

**如实记录的取证噪音（脚本坑，非 App 缺陷）**：全新模拟器首次聚焦 composer 会弹**系统** `Choose input method` 对话框并**挡住发送钮**（首点 `/prompt` 无 POST）；`input text` 空格须写 `%s`。两者均已在步骤 5 标注。

---

## 7. 收尾复核输出（原文）

```
# 1. 撤销 override（按 serial）
$ adb -s emulator-6430 shell wm size reset ; wm density reset
Physical size: 2560x1600
Physical density: 320              ← 无 Override 行

# 2. 摘 reverse
$ adb -s emulator-6430 reverse --list        →  host-16 tcp:4095 tcp:4095
$ adb -s emulator-6430 reverse --remove tcp:4095
REVERSE_AFTER=                             ← 空

# 3. 杀前确认是自己起的
PID=52244 NAME=emulator.exe AVD=T88_Phone    PORT=5700 MINE=False   ← 别人的，未碰
PID=53744 NAME=emulator.exe AVD=OC_Deliver_R2 PORT=6430 MINE=True    ← 我的

# 4. 只按 serial 关
$ adb -s emulator-6430 emu kill
OK: killing emulator, bye bye
OK

# 5. 复核
process count = 1
PID=52244 NAME=emulator.exe AVD=T88_Phone PORT=5700 MINE=False      ← 别人的实例，未碰
OC_*/Medium_Desktop_B residual = 0
PORT 6430 LISTEN=FREE
PORT 6432 LISTEN=FREE
PORT 6434 LISTEN=FREE
$ adb devices
List of devices attached
emulator-5700	device                    ← 别人的 T88_Phone，未碰
.agent-gradle-lock exists = False
4095 LISTEN pid=3748                      ← 与开轮前同一 pid，后端全程未起停
```

**⇒ 模拟器已关 ✅；`OC_*` 残留 0 ✅；6430/6432/6434 全 FREE ✅；后端 4095 未起停（同一 pid=3748）✅。**
**未执行** `adb kill-server`、**未用** `-a`、**未用**通配符 kill；`emu kill` **只带一个 serial** `emulator-6430`。

## 8. 碰过的 serial

- **唯一自建**：`emulator-6430`（`avd=OC_Deliver_R2`，`pid=53744`）——
  本轮全部 install / tap / keyevent / screencap / logcat / wm / reverse / emu kill **只针对它**。
- **只读旁观、未执行任何写操作**：`emulator-5700`(`T88_Phone`，开轮与收尾均在列)、
  `emulator-5680`(`DSH_T79_Phone`，开轮时在列)。

## 9. 是否碰过非自建实例

**否。** 未对 `MCRemote_*` / `DSH_*` / `T7x_*` / `Pixel_Tablet` / `T88_Phone` 执行任何
kill / install / tap / wm / reverse 写操作；未执行 `adb kill-server`；未用 `-a`；未用通配符 kill。

## 10. 未闭环

1. **R8 包无 CDP ⇒ DOM 级读数无法复现**：`--oc-safe-top` / `__ocAndroid.getInsets()` / `header.top` / `histLen` 这些 F18 引用的读数，本轮**只能**用像素几何 + logcat 间接证明。结论一致但**取证路径不同**，未在 R8 上复现 DOM 读数本身。
2. **未在真挖孔设备上验证**：几何覆盖下 `displayCutout` 恒为 0，真实挖孔机上 `safeTop` 会更大（承 F18 §11.2 未闭环项）。
3. **V13 的 D3 / D5 未修**（不在 F18 派单范围），本轮只做了"未被改坏"的回归证明（§4.2）。
4. **平板档只 spot check**（顶部 inset + 进会话 + BACK 一次 + 两栏布局），**未走完**平板全链路（IME 遮挡、`Changes/Files/Terminal` 页、发送全流程）。
5. **未测**横屏 / 折叠屏 / 深色模式。
6. **首屏 `New session` 缺失（D5）**与**一屏 9 条（O1）**维持原状观察。
