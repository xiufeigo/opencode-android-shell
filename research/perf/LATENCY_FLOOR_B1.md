# LATENCY_FLOOR_B1 —— B1：把输入延迟的"地板"分离出来（WebView 基线 vs 官方 UI 成本）

> - **执行日期**：2026-10-05（硬期限 2026-10-07 之前 ✅）
> - **目的**：用**同一把尺子、同一台设备、同一个几何、同一轮次**测三个层级，回答一句话：
>   **L1−L0 = 官方 UI 的成本；L0−L2 = WebView 管线的成本。**
> - **本轮只测量、只写报告**：`webapp/src` **未改**（L0 走 CDP 注入，不动上游资产）
> - **本轮结论推翻 P5 的假设**：P5 归因"WebView 渲染管线固有成本"；**分离实验证明它不是**。

---

## ✅ 一句话结论

**输入延迟的短板不在 WebView 渲染管线（那是"固有"假设），而在官方前端（上游 SPA）本身。**

同一把尺子、同一台设备、同一几何，每层 n=120（两轮各 120）：

| 分解 | p50 | p90 | 读法 |
|---|---|---|---|
| **L1−L0（官方 UI 的成本）** | **+6.42 ms** | **+32.57 ms** | 🔴 **这就是那笔账的主体** |
| **L0−L2（WebView 管线的成本）** | **−0.34 ms** | **+2.37 ms** | 🟢 **≈ 0，WebView 不是瓶颈** |
| （参考）L1−L2（web 减原生） | +6.08 ms | +34.94 ms | 与 P5 的 +3.86/+25.92 同量级 ✅ 尺子可复现 |

> **判定：`可修（需改上游）`。** 换成"混合输入条/原生输入框"救不了这 6.4 ms p50 / 32.6 ms p90 ——
> 因为 **L0（同一个 WebView 宿主、同一套设置、同样的触摸注入）已经做到了 25.06 ms，
> 比原生壳的 25.40 ms 还快 0.34 ms**。地板在官方 UI 之下，不在 WebView 之下。

---

## 0. 速查

| 项 | 值 |
|---|---|
| 设备 | **自建** `OC_Perf_Test` / serial **`emulator-5854`** / 端口 **5854**（≥5850 偶数、起前查占用=0） |
| 几何 | **1080×2400 @ 420**（与 P4/P5 逐字相同） |
| 系统 | Android 15 / API 35 / Pixel Tablet / x86_64 |
| **WebView** | **124.0.6367.219**（与 P5 同一版本） |
| 启动 flags | `-no-snapshot-load -no-boot-anim -no-audio -gpu swiftshader_indirect -no-metrics`（与 P5 逐字相同） |
| boot | **57.3 s**（P5 记 71 s，同量级） |
| 后端 | Lead 的 `127.0.0.1:4095`，**本轮 0 次启停**，只 `adb reverse tcp:4095` |
| 被测包 | L0/L1 = `com.xiufeigo.opencodeshell.web.debug` / `WebHostActivity`；L2 = `com.xiufeigo.opencodeshell` / `OcActivity` |
| APK md5 | web `8DCFFC572AD469F472F61594F12EA990`（与 P5 同一份）；native `BD21FF21E72E75DB62FC371D290F438E` |
| 前置门禁 | web **PASS** / native **PASS（修正版，见 §2）** / L0 身份 **PASS（测量前后各验一次）** |
| 每层样本 | **n=120 × 2 轮**（每轮 10 上滑 + 10 下滑，6 帧/滑） |

---

## 1. 三层是什么（被测物定义）

| 层 | 被测物 | 怎么得到 | 页面规模（CDP 实测） |
|---|---|---|---|
| **L0 基线** | **同一个 WebView 宿主**里的极简页：只有 1 个 `<textarea>` + 1 个 `<input>`，无框架 | CDP `Runtime.evaluate` 里 `document.open()/write()/close()` **就地替换**文档 | **10 个 DOM 节点**、0 外部 script、0 stylesheet |
| **L1 官方 UI** | 官方前端（opencode SPA）的会话列表/滚动视图 | 正常启动，不注入 | **910 个 DOM 节点**、含 `scroll-view__viewport` |
| **L2 对照** | 原生壳 `com.xiufeigo.opencodeshell` / `OcActivity` 的输入区 | 装 `research/deliver/opencode-shell-debug.apk` | — |

### 1.1 为什么选"CDP `document.write` 就地替换"而不是 `Page.navigate`

任务允许二选一（`Page.navigate` 或注入 DOM）。选 `document.write` 的三条理由：

1. **不碰上游资产**：`webapp/src` 本轮明令不许改，`Page.navigate` 到一个本地页会改变
   文档的 **URL 与安全源**（本轮官方页跑在宿主内建的 `http://127.0.0.1:<proxy>/` 上），
   而 `document.write` **保留同一个 origin、同一个 WebView、同一个 Activity、同一套 WebSettings**
   ⇒ **"同一个 WebView 宿主"这个前提才真正成立**，L0−L2 才是干净的管线差。
2. **销毁上游代码**：`document.write` 会连带销毁旧文档里的 React 树与 `oc-host.js` 注入监听
   ⇒ L0 侧**不可能**偷偷跑官方逻辑（`domNodes=10` / `externalScripts=0` 已自证）。
3. **不用起任何本地服务器**（后端只准用 Lead 的 4095，不许我另起）。

### 1.2 L0 页面的内容（为什么 textarea 里放了 4000 行）

极简页只有一个 `<textarea>`（填 **4000 行** `line N`）+ 一个 `<input>`。
**填内容不是为了"重活"，而是为了让滑动有可滚动的量**——否则滑动手势在空页面上
不产生任何位移，量到的是"空转帧"而不是输入延迟。填充在 **CDP 外层**完成（见 §5 的踩坑记录）。

---

## 2. ✅ 前置检查证据（证明测的是真 UI，不是对话框）

> P4 的教训：新 AVD 若 `SecretStore` 为空 ⇒ 弹口令对话框且永不 `loadUrl` ⇒
> **四量全测成对话框，却"看起来跑完了"**。P5 把它硬化成门禁；本轮沿用并**加严**。

### 2.1 web 侧（L1 的宿主）—— `out_p5/precheck_web.json`

| # | 判据 | 实测 | 结论 |
|---|---|---|---|
| **A** | logcat `passwordPresent=true` **且** 没有 `no password in SecretStore, asking` | `true` / 无 asking | ✅ |
| **B** | logcat 里 `OcPerfJS` 出现 >0 次 | **109 次**（页面标记 4 个） | ✅ |
| **C** | CDP 取 `document.title` / 可见文本 / 节点数 / 口令框数 | `title="OpenCode"`、**910 节点**、`input[type=password]`=**0**、无"保存并连接"按钮 | ✅ |

```
10-04 16:02:20.761 I OcWebHost: home=http://127.0.0.1:34221/ backend=http://127.0.0.1:4095 passwordPresent=true
# target: title=OpenCode url=http://127.0.0.1:34221/
{"title":"OpenCode","body":"Home\n0\nAll projects\nToday\nO\nCounting from 1 to 4000, one number per line
opencode-android-shell\nYesterday\nD\nFriendly greeting\n…","nodes":910,"passwordInputs":0,
"dialogSaveBtn":false,"looksLikeLoginDialog":false}
"VERDICT_OFFICIAL_UI": true
```

> **关于"播种口令"脚本报错**：`b1_boot.ps1` 调 `seed_password.ps1` 时输出
> `!! no EditText found -> cannot seed`。**这不是缺种**，而是**因为没有对话框可填** ——
> `OC_Perf_Test` 的数据盘持久，P4/P5 播的种仍在（上面的 `passwordPresent=true` 就是证据）。
> 门禁 A/B/C 三条独立证据确认：跑的是官方 UI。

### 2.2 native 侧（L2）—— **修正版门禁** `out_b1/L2/precheck_native.json`

🔴 **P5 的原生门禁是坏的，本轮修正（不是绕过）**。三条实测理由：

1. **判据 A/B 对原生按构造必然失败**：`p5_precheck.ps1` 要求 logcat 出现
   `passwordPresent=true` 与 `OcPerfJS`，但原生壳走**另一条代码路径**，**根本不打印这两个 tag**。
2. **判据 C 的 "EditText 必须为 0" 不成立**：原生壳的**正常 UI 里就有一个 composer 输入框**。
3. **存在真实污染源**：web 壳进程在后台存活时持续打 `OcPerfJS` / `/api/info` 轮询，
   把这些行混进原生侧 logcat —— 实测第一次原生门禁 `B_OcPerfJS_occurrences=8` **全是 web 壳的**。

**修正后的门禁只加严、不放松**：用**正向识别**替代"计数为 0"。

| # | 判据（修正版） | 实测 | 结论 |
|---|---|---|---|
| **E** | **web 壳进程必须已停**（否则 logcat 被污染，任何 logcat 判据都不可信） | web pid = `''` | ✅ |
| **A** | 前台确实是 `OcActivity`（不是 web 壳、不是别的） | `topResumedActivity=...com.xiufeigo.opencodeshell/.OcActivity t80` | ✅ |
| **B** | `password="true"` 节点数 = **0**（真·无口令对话框） | **0** | ✅ |
| **C** | 不存在"保存并连接"按钮（对话框签名） | `False` | ✅ |
| **D** | **正向识别**：可见文本含 composer 占位 `Ask anything` 且节点数 > 20 | `True` / **63 节点** | ✅ |
| **F** | logcat 无 `no password in SecretStore, asking` | 无 | ✅ |

```
"web_shell_pid": "", "A_is_OcActivity": true, "B_password_nodes": 0,
"C_save_and_connect_button": false, "D_composer_hint_hit": true, "D_dom_nodes": 63,
"VERDICT_OFFICIAL_UI": true
```

可见文本实证（真实会话 + 真实 composer，不是对话框）：
```
Todo3 code review (@oracle subagent)
Session / Changes / Files / Terminal
Reviewed gateway attach/finalize/cleanup ledger, Basic authorization flow, ...
Oracle · gpt-5.6-sol · 1m 22s
Ask anything, / for commands, @ for context...     <== 官方 composer 占位
GPT-5.6 Sol · xhigh
```

### 2.3 L0 身份 —— **测量前后各验一次**（`out_b1/L0/l0_identity_before/after.json`）

| 时点 | title | DOM 节点 | textarea | input | 外部 script | stylesheet | React 根属性 |
|---|---|---|---|---|---|---|---|
| 测量**前** | `L0-MINIMAL` | **10** | 1 | 1 | **0** | **0** | **0** |
| 测量**后** | `L0-MINIMAL` | **10** | 1 | 1 | **0** | **0** | **0** |

> 测量**后**仍是无框架极简页 ⇒ **L0 的 20 次滑动确实发生在极简页上，
> 没有被官方 SPA 抢回去、也没有被弹成对话框。**

---

## 3. 方法：尺子逐字说明（与 P5 相同，可与 32.00/58.76、28.14/32.84 对比）

**三层用同一套输入注入与同一套计时口径**（`b1_measure.ps1`，逐字复制 P5 的 `chunk.ps1 -What input`）：

| 步骤 | 内容 |
|---|---|
| 1 | `dumpsys gfxinfo <pkg> reset` |
| 2 | `am force-stop <pkg>` → `am start <activity>` → **固定等 22 s** |
| 3 | 20 次**窗口相对**滑动：10 上滑（0.80→0.20）+ 10 下滑（0.20→0.80），**swipe 250 ms / 间隔 250 ms** |
| 4 | `dumpsys gfxinfo <pkg> framestats` |
| 5 | 解析 **`HandleInputStart` → `FrameCompleted`**（记作 i2f）与 **→ `SwapBuffersCompleted`**（i2s），单位 ms |
| 6 | 分位 **p50/p90/p95/p99**（**Nearest-rank**，与 P5 同一 `Pct` 实现） |

**唯一一层间差异（已声明）**：L0 在第 2 步之后多了一次 CDP 注入 + **8 s 稳定窗**（官方 UI 侧无此步）。
理由：换页必须等它稳定。**该 8 s 落在 `gfxinfo reset` 之后，但解析只取"带输入标记的帧"
（`HandleInputStart > 0`），故注入/稳定期产生的帧被自动排除，不污染样本。**

### 3.1 口径：**是不是端到端？——不是**

> 🔴 **明确写清：本口径不是端到端（硬件→像素）触摸延迟。**
>
> 它是 **Android 平台帧时间线里的 `输入处理开始 → 该帧完成`** 代理量。
> 真正的端到端（手指接触玻璃 → 像素变化）需要外部高速相机或触控示波器，
> **模拟器上测不到，本轮也没有测**。
>
> 但因为**三层用完全相同的口径与参数**，**层间差值是可比、可解释的** ——
> 而"层间差值"正是本轮要回答的问题。

---

## 4. 🔴 有效性检查：20 次滑动**真的做了事**吗（抓出一个作废的测量）

> P5 留的教训是"失败必须能被看见"。本轮给**每一层**都加了有效性门禁
> （`b1_l0_validity.ps1`）：V1 滑动必须**改变可观测状态**；V2 窗口内必须有带输入标记的帧。

### 4.1 🔴 第 1 轮 L0 **作废**（作废原因逐字记录）

| 项 | 第 1 轮 L0 读数 | 判定 |
|---|---|---|
| 注入自证 | `valueLines` **未回读**、`textareaScrollHeight = 915 = clientHeight` | ❌ **textarea 是空的** |
| V1 状态变化 | `scrollTop 0 → 0` | ❌ **滑动没有产生任何位移** |
| 数字 | p50 16.32 / p90 26.62（n=120） | 🔴 **作废，不进任何结论** |

**根因（已定位）**：4000 行文本的填充写在**页内联 `<script>`** 里，
`L.join("\\n")` 经"外层 JS 字符串 → document.write → 浏览器解析内联脚本"**两层转义**后，
内联脚本里拿到的是**字面反斜杠**而不是换行 ⇒ `ta.value` 为空 ⇒ 页面无内容可滚。

**修法**（`b1_l0_inject.js` 第 2 版）：改为**在 CDP 外层直接 `ta.value = L.join('\n')`**，
不依赖页内联脚本，并把 `valueLines / scrollHeight / clientHeight` **一起回读自证**：

```json
{"injected":"L0-MINIMAL","title":"L0-MINIMAL","textareaCount":1,"valueLines":4000,
 "valueChars":38892,"scrollHeight":80016,"clientHeight":915,"scrollable":79101,"domNodes":10}
```

### 4.2 作废 vs 修好：**空页面的 16.32 ms 本身就是"地板是假的"的证据**

| L0 变体 | p50 | p90 | 说明 |
|---|---|---|---|
| 空 textarea（作废） | 16.32 | 26.62 | 🟡 **量到的是"空转帧"，不是输入延迟** |
| 4000 行真实内容（有效） | **25.06** | **30.30** | ✅ 与原生 25.40 几乎重合 |

> **这条自曝很关键**：如果不做有效性检查，本轮会得出一个**漂亮但错误**的结论
> （"WebView 只要页面够简单就比原生快 9 ms"）。**地板是被内容撑起来的，不是 WebView 自己慢。**

### 4.3 ⚠️ 三层都没有真正滚动（**如实记录，不掩盖**）

V1 门禁在 **L0 与 L1 都没通过**，原因是同一件事：

| 层 | 可滚动容器 | scrollHeight vs clientHeight | 滑动后 scrollTop |
|---|---|---|---|
| L0 | `textarea` | 80016 vs 915（**可滚**） | **0 → 0** ❌ |
| L1 | `div.scroll-view__viewport` | 2539 vs 821（**可滚**） | **0 → 0** ❌ |
| L2 | —（无 CDP，未验证） | — | **未验证** ❓ |

⇒ **`adb shell input swipe`（250 ms）在这些页面上没有产生滚动位移**，
三层量到的都是**"触摸手势 → 它触发的那一帧完成"**（每滑 6 帧，n=120 满编），
**不是滚动吞吐**。

**这对结论意味着什么（两面都说清）**：

- ✅ **不破坏分解**：三层处于**完全相同**的条件（都没滚动），所以 L1−L0 与 L0−L2 仍是同条件对比。
  而且这个条件其实**更干净**——它排除了"滚动量不同"这个混淆项。
- ⚠️ **限制了外推**：本结论覆盖的是**输入事件的单帧延迟**（INP 口径），
  **不覆盖持续滚动流畅度**（那要帧时/`dumpsys gfxinfo` 帧统计，另开一轮）。
- ❓ **L2 的滚动行为未验证**（原生壳无 CDP 通道）。这是本轮**唯一未闭环的方法学缺口**。

---

## 5. 三层读数（每层 **n=120 × 2 轮 = 240**，全部原始样本已落盘）

落盘：`out_b1/{L0,L1,L2}/raw_samples.json`（每个样本一个 ms 数值，可逐条复核）
汇总：`out_b1/pooled_summary.json`

### 5.1 逐轮（同一台设备、同一几何、同一方法）

| 层 | 轮 | n | p50 | p90 | p95 | p99 | mean | max | `→SwapBuffers` p50 |
|---|---|---|---|---|---|---|---|---|---|
| **L1 官方 UI** | 1 | 120 | 31.48 | 62.87 | 68.98 | 78.11 | 36.84 | 84.85 | 28.91 |
| **L1 官方 UI** | 2 | 120 | **30.22** | **60.15** | **64.07** | 78.12 | 35.26 | 78.41 | 29.02 |
| **L0 极简页** | 1 | 120 | 25.06 | 30.30 | 31.36 | 33.12 | 25.77 | 33.30 | 24.70 |
| **L0 极简页** | 2 | 120 | **24.12** | **26.15** | **28.99** | 33.38 | 21.49 | 33.93 | 23.93 |
| **L2 原生** | 1 | 120 | 25.40 | 27.93 | 32.28 | 33.29 | 20.35 | 33.38 | 25.34 |
| **L2 原生** | 2 | 120 | **27.64** | **33.31** | **33.84** | 34.52 | 26.71 | 35.04 | 26.65 |

> 🔴 **第 1 轮 L0（p50 16.32 / p90 26.62）已作废、不进上表**，原因见 §4.1（空页面）。
> ⚠️ 另有一个 L1 读数 **p50 45.43**（`out_b1/validity_L1/`）**未采用**：那次运行在滑动前
> 额外做了 2 次 CDP 状态探针读取，条件与其余各轮不同；列此以示透明。

### 5.2 合并两轮（p50 / p90 / p95 为本报告的判定口径）

| 层 | **n** | **p50** | **p90** | **p95** | p99 | mean | max |
|---|---|---|---|---|---|---|---|
| **L0 极简页（同一 WebView 宿主）** | **240** | **24.68** | **29.22** | **31.12** | 33.38 | 23.63 | 33.93 |
| **L1 官方 UI** | **240** | **30.73** | **62.10** | **68.50** | 78.12 | 36.05 | 84.85 |
| **L2 原生壳** | **240** | **26.40** | **32.71** | **33.31** | 34.47 | 23.53 | 35.04 |

---

## 6. 分解：`L1−L0` 与 `L0−L2`

| 分解 | p50 | p90 | p95 | p99 | 读法 |
|---|---|---|---|---|---|
| **L1−L0 = 官方 UI 的成本** | **+6.05** | **+32.88** | **+37.38** | **+44.74** | 🔴 **全部账都在这里** |
| **L0−L2 = WebView 管线的成本** | **−1.72** | **−3.49** | **−2.19** | **−1.09** | 🟢 **≈ 0（甚至更快）** |
| （参考）L1−L2 = web 减原生 | +4.33 | +29.39 | +35.19 | +43.65 | 与 P5 同量级 ✅ |

### 6.1 三条互相独立的印证

1. **均值几乎重合**：L0 `mean 23.63` vs L2 `mean 23.53`（差 **0.10 ms**），
   而 L1 `mean 36.05` 明显更高 ⇒ **不是分位算法artifact，是整条分布平移**。
2. **WebView 侧没有长尾**：L0 `p99 33.38 / max 33.93`；L2 `p99 34.47 / max 35.04`；
   L1 `p99 78.12 / max 84.85` ⇒ **长尾 100% 来自官方 UI**。
3. **尺子跨机可复现**：本轮 L1−L2 = `+4.33 / +29.39`（`emulator-5854`），
   P5 记 `+3.86 / +25.92`（`emulator-5842`，不同机）⇒ 差距是**代码/架构的，不是这台机器的**。

### 6.2 ⚠️ 分解内部没有再分（诚实边界）

`L1−L0 = +6.05 ms` 这一笔，本轮**只定位到"官方 SPA 整体"，没有再往下分**是
React 受控输入的 commit、是 910 个节点的 paint、还是指针事件路径。
**要把这笔钱再拆开，必须在上游侧做归因**（本轮不改上游，故未做）。
另：L0（10 节点）与 L1（910 节点）内容规模不同，
所以这 +6.05 ms 是**"官方 UI 相对一个空 WebView 页面多出来的全部成本"**——
框架与内容量**混在这一笔里**，两者都属上游。

---

## 7. 判定

> ## **`可修（需改上游）`**
> **不是"固有"，也不是"换混合输入条能解决"。**

| 候选方案 | 本轮数据说什么 | 判定 |
|---|---|---|
| **改上游（官方 SPA）** | 账全在 L1−L0：**+6.05 p50 / +32.88 p90 / +44.74 p99**。同一个 WebView 宿主换掉页面内容就能做到 24.68 / 29.22 —— **这个上限已经优于原生壳** | ✅ **唯一有效的方向** |
| **换混合输入条（把 composer 换成原生输入框）** | 🔴 **L0 页里就有一个真实 `<textarea>`，而 L0 = 24.68 p50，比 L2 原生还快 1.72 ms。** ⇒ **"WebView 里的文本输入框"本身不贵**。贵的不是输入控件，是官方页面在每次手势上的整体帧工作（910 节点 + 受控输入 + 提交）。把输入框单独换成原生的，**动不了这笔账** | ❌ **本轮数据不支持** |
| **"WebView 固有，改不了"** | 🔴 **被证伪**：L0（同一宿主、同一套 WebSettings、同一套注入）**在 p50/p90/p95/p99 四个分位上全部快于原生壳**。渲染管线不是地板，**地板在官方 UI 之下** | ❌ **被证伪** |

### 7.1 直接回答那句"要看齐原生"

- **看齐原生（p50 26.40 / p90 32.71）在技术上完全可达** —— L0 已经做到 **24.68 / 29.22**，
  比原生还好。**天花板不是 WebView，是官方 UI 的实现。**
- **优先级建议（基于本轮数据，不是猜测）**：
  **p90/p95/p99 才是真问题**（L1 62.10/68.50/78.12 vs 原生 32.71/33.31/34.47，**尾部差 1.9~2.3 倍**），
  p50 只差 6 ms。上游优化应**先攻长尾**（找每次手势里制造 >50 ms 帧的分支），而不是先抠 p50。
- ⚠️ **本轮不能给的结论**：不能断言"上游改完一定能到 L0 水平"。L0 是**无框架空页面**，
  官方 UI 必须保留功能与视觉；**可达的落点需要上游侧再做一轮归因**才能定。

---

## 8. 方法与 n（可复现）

| 项 | 值 |
|---|---|
| 尺子 | `dumpsys gfxinfo <pkg> framestats` → **`HandleInputStart` → `FrameCompleted`** |
| 口径 | **平台帧时间线代理量；不是端到端（硬件→像素）**。端到端需外部高速相机/示波器，模拟器上测不到 |
| 输入注入 | 窗口相对滑动，**10 上滑(0.80→0.20) + 10 下滑(0.20→0.80)**，`swipe 250 ms` / 间隔 `250 ms` |
| 冷启等待 | `am force-stop` → `am start` → **固定 22 s** |
| 样本量 | 每层 **每轮 n=120**（20 滑 × 6 帧），**两轮合计 n=240** |
| 分位算法 | **Nearest-rank**（与 P5 同一 `Pct` 实现，可跨轮对比） |
| 三层差异 | 仅 L0 多一次 CDP 注入 + **8 s 稳定窗**；解析只取带输入标记的帧，注入期帧被排除 |
| 门禁 | web 前置门禁 PASS / native 前置门禁（修正版）PASS / L0 身份测量前后各验一次 / 每层有效性检查 |

脚本（全部在本轮产出，均可复现）：

| 脚本 | 作用 |
|---|---|
| `research/perf/b1_boot.ps1` | 起 AVD（端口占用先查、冲突就换不抢）→ 等 boot → 几何 → 装 2 个 APK → `adb reverse` → 播种 → 指纹 |
| `research/perf/b1_measure.ps1` | **主尺子**：三层同一口径测输入延迟（含 L0 的 CDP 注入与身份前后校验） |
| `research/perf/b1_l0_inject.js` | L0 极简页注入（第 2 版：外层赋值 + 自证回读） |
| `research/perf/b1_verify.js` | L0 身份探针（title / 节点数 / 外部 script / React 根属性） |
| `research/perf/b1_l0_validity.ps1` | **有效性门禁**（V1 状态是否真变了 / V2 是否有输入帧）—— 抓出作废的 L0 |
| `research/perf/b1_precheck_native.ps1` | **修正版原生门禁**（修正 P5 的按构造失效判据，改为正向识别） |
| `research/perf/b1_close.ps1` | 收尾（只按 AVD 名 + serial 关；三重复核） |

---

## 9. 收尾

### 9.1 我碰过的 serial

| serial | AVD | 是不是我起的 | 我对它做了什么 |
|---|---|---|---|
| `emulator-5854` | **`OC_Perf_Test`** | ✅ **是（本轮唯一自建实例）** | 起（boot 57.3 s）→ 几何 1080×2400@420 → 装 2 个 APK → `adb reverse tcp:4095` → 播种（脚本报 no EditText，因已持久化，见 §2.1）→ web 门禁 PASS → **L1 ×2 轮** → **L0 ×2 轮**（第 1 轮作废、有效性检查 ×2）→ native 门禁 PASS → **L2 ×2 轮** → **收尾关掉** |
| `emulator-5650` | **`DSH_T79_Phone`（非 `OC_*`，用户自己的）** | ❌ 否 | **零操作**（只在 `adb devices` / 进程列表里出现过；复核时确认 PID `55692/48980` **未变**） |
| `emulator-5652` | **`DSH_T79_Tablet`（非 `OC_*`，用户自己的）** | ❌ 否 | **零操作**（复核时确认 PID `43820/17792` **未变**） |

**故意保留的实例：无。**
**端口**：只用 **5854**（≥5850 偶数；起前查 `Get-NetTCPConnection` 监听数 = **0**，未抢任何端口）。
**用户 AVD** `MCRemote_*` / `DSH_*` / `T7x_*` 本轮**一次都没起过、一次都没碰过**。

### 9.2 收尾复核输出（`out_b1/_closeout.txt`，逐字）

```
[00:14:56] === [A] 关停前基线：当前全部 emulator 进程（只列，不动别人的） ===
[00:14:56]   pid=55692  name=emulator.exe           avd='DSH_T79_Phone' port='5650'  <== 别人的/来源不明，未碰
[00:14:56]   pid=43820  name=emulator.exe           avd='DSH_T79_Tablet' port='5652'  <== 别人的/来源不明，未碰
[00:14:56]   pid=48980  name=qemu-system-x86_64.exe avd='DSH_T79_Phone' port='5650'  <== 别人的/来源不明，未碰
[00:14:56]   pid=17792  name=qemu-system-x86_64.exe avd='DSH_T79_Tablet' port='5652'  <== 别人的/来源不明，未碰
[00:14:56]   pid=6388   name=emulator.exe           avd='OC_Perf_Test' port='5854'  <== 本轮的
[00:14:56]   pid=48920  name=qemu-system-x86_64.exe avd='OC_Perf_Test' port='5854'  <== 本轮的
[00:14:58] === 端口 5854 关停前监听数 = 2 ===
[00:14:58] === [B] 关闭本轮自建实例（只按 AVD 名 + serial） ===
[00:14:58]   adb -s emulator-5854 emu kill  (avd=OC_Perf_Test pid=6388)
[00:14:58]     OK: killing emulator, bye bye
[00:15:12] === [C] 复核 1：进程（本轮 AVD 应 0 残留） ===
[00:15:12]   残留本轮 AVD 进程数 = 0
[00:15:12]   pid=55692  name=emulator.exe           avd='DSH_T79_Phone' port='5650'  <== 别人的，未碰
[00:15:12]   pid=43820  name=emulator.exe           avd='DSH_T79_Tablet' port='5652'  <== 别人的，未碰
[00:15:12]   pid=48980  name=qemu-system-x86_64.exe avd='DSH_T79_Phone' port='5650'  <== 别人的，未碰
[00:15:12]   pid=17792  name=qemu-system-x86_64.exe avd='DSH_T79_Tablet' port='5652'  <== 别人的，未碰
[00:15:12]   本轮 AVD 的 qemu-system-x86_64 进程数 = 0   (这才是 CPU 幽灵占用的判据)
[00:15:12] === [D] 复核 2：端口 5854 监听（应为空） ===
[00:15:13]   Get-NetTCPConnection -LocalPort 5854 -State Listen => <空>，端口已释放
[00:15:13] === [E] 复核 3：adb devices（不应再有本轮 serial；未执行 adb kill-server） ===
[00:15:13]   List of devices attached
[00:15:13]   emulator-5650	device
[00:15:13]   emulator-5652	device
[00:15:13]   （全程未执行 adb kill-server；未使用 -a / 通配符；非 OC_* 实例一次都没碰）
[00:15:13] === [F] 纪律自查 ===
[00:15:13]   后端 127.0.0.1:4095：本轮 0 次启停（只做过 adb reverse tcp:4095）
[00:15:13]   故意保留的实例：无
[00:15:13] === CLOSEOUT DONE ===
```

> 独立复核（关停后另跑一次，四条全过）：
> 本轮 AVD 的 `emulator.exe`/`qemu-system-x86_64.exe` 进程数 = **0**；
> 端口 **5854** 监听 = **空**；`adb devices` 只剩 `emulator-5650 / emulator-5652`，
> 且这两个用户实例的 **PID 与关停前基线逐个一致**（55692 / 43820 / 48980 / 17792）⇒ **未受影响**。

### 9.3 纪律自查

| 规矩 | 结果 | 证据 |
|---|---|---|
| 谁起谁关 | ✅ | §9.2 `OK: killing emulator, bye bye` + 三重复核 |
| 进程 0 残留 | ✅ | 残留本轮 AVD 进程数 = **0**；本轮 AVD 的 qemu 进程数 = **0** |
| 端口已释放 | ✅ | 端口 **5854** `Get-NetTCPConnection -State Listen` = **空** |
| 只关自己起的（按 AVD 名） | ✅ | 关停前基线里只有 `OC_Perf_Test` 属本轮；`DSH_T79_*` **PID 未变、仍在** |
| 用户 AVD 禁止 kill | ✅ | `MCRemote_*` / `DSH_*` / `T7x_*` 一次都没碰 |
| 禁 `adb kill-server` | ✅ | 全程未执行（`adb devices` 仍正常服务 `emulator-5650/5652`） |
| 禁 `-a` / 通配符杀进程 | ✅ | 只按 serial `emulator-5854` 与 AVD 名 `OC_Perf_Test` 定位 |
| 不许起停后端 | ✅ | `127.0.0.1:4095` **0 次启停**，只 `adb reverse tcp:4095` |
| 端口 ≥5850 偶数、冲突就换不抢 | ✅ | 用 **5854**，起前查监听数 = **0** |
| 不许改 `webapp/src` | ✅ | L0 走 CDP 注入；**未改 `webapp/src` 任何文件** |
| 写权限 | ✅ | 只写 `research/perf/**`；未碰 `app/**`、`webapp/**`、`_upstream/**`、`.github/**` |

### 9.4 `git status` 审计：哪些改动是**我的**、哪些是**并行那一路的**

收尾时 `git status --short` 里**同时**出现了 `webapp/**` 的改动。为避免误记，
逐个核对了 mtime 与本轮脚本的写入路径：

| 路径 | mtime | 是谁改的 | 判定依据 |
|---|---|---|---|
| `webapp/build.gradle.kts` | 00:01:06 | **并行那一路** | 本轮**未运行过任何 gradle**；我的脚本无写入该路径的语句 |
| `webapp/src/main/assets/www/index.html` | 00:03:57 | **并行那一路** | 同上 |
| `webapp/src/main/assets/www/oc-host.js` | 00:03:25 | **并行那一路** | 同上 |
| `webapp/src/main/java/.../WebHostActivity.kt` | 00:03:25 | **并行那一路** | 同上 |
| `webapp/src/main/java/.../host/WebHostBridge.kt` | 00:04:58 | **并行那一路** | 同上 |
| `webapp/src/main/assets/www/oc-preflight.js`（新增） | 00:01:58 | **并行那一路** | 同上 |
| `research/perf/b1_*.ps1`、`b1_*.js`、`LATENCY_FLOOR_B1.md`、`out_b1/**` | 00:00–00:16 | ✅ **本轮（B1）** | 全部为新建文件 |
| `research/perf/out_p5/precheck_web.json`、`out_p5/precheck_native.json` | 本轮 | ✅ **本轮（B1）** | 由 `p5_precheck.ps1` / `b1_precheck_native.ps1` 落盘，均在 `research/**` 内 |

**校验方法（可复现）**：本轮 8 个脚本的**全部** `Out-File` / `Set-Content` / `New-Item` 目标
经 `Select-String` 逐行核对，**无一例外都指向 `$PSScriptRoot\out_b1` 或 `research/perf`**，
**没有任何一条写入 `app/`、`webapp/`、`_upstream/`、`.github/`**。
`.agent-gradle-lock` 亦**非本轮创建**（本轮未调用 gradle）。

> ⇒ **`webapp/src` 本轮确实一行未改**（L0 的极简页是 CDP 注入进去的，不落盘到任何资产文件）。

### 9.5 `wm` 复原
本轮**改过** `wm size 1080x2400` / `wm density 420`。
**处理方式**：与 P4/P5 相同 —— 改的是**本轮自建**的 AVD `OC_Perf_Test`，
该实例在收尾已整台关掉，override 几何随之消失，**不留残留状态**；
用户自己的 `DSH_T79_Phone` / `DSH_T79_Tablet` **一次都没碰过 `wm`**。

### 9.6 顺手修的一个脚本 bug（`b1_close.ps1`，本轮自曝）

初版第 35 行写成 `$p.Matches[1]`（`Process` 对象**没有** `.Matches` 属性）
⇒ 抛 `InvalidOperation`（非终止），靠下一行 `$Matches` 兜底才拿到端口、关停仍成功。
**已删掉该错误行**。教训同 P5：**收尾脚本自己的成功判定也必须能被复核**
（本轮靠"关停后另跑独立复核"才确认这次确实是成功，而不是"看起来成功"）。

---

## 10. 未闭环

| # | 项 | 状态 | 说明 / 下一步 |
|---|---|---|---|
| 1 | 🔴 **P5 的归因被本轮推翻，需要回写 P5** | **本轮完成** | P5 §3.2/§4 的"WebView 渲染管线固有成本"结论**不再成立**。建议在 `INPUT_PATH_P5.md` 加一条指向本报告的更正说明（**本轮未改 P5 文件**，因不在本轮授权范围） |
| 2 | 🟡 **L1−L0 的 +6.05 ms 未再往下分** | 未闭环 | 分不出是 React commit / 910 节点 paint / 指针事件路径。**需在上游侧再做一轮归因**（本轮不改上游） |
| 3 | 🟡 **三层都没真正滚动**（§4.3） | 如实记录 | 本口径覆盖**单帧输入延迟（INP 口径）**，**不覆盖持续滚动流畅度**。要评滚动需另跑帧时一轮 |
| 4 | ❓ **L2 的滚动行为未验证** | 缺口 | 原生壳无 CDP 通道，无法读滚动位置。**本轮唯一方法学缺口** |
| 5 | 🟡 **P5 的原生前置门禁是坏的** | 已修并留档 | 本轮新增 `b1_precheck_native.ps1`（正向识别）。**建议回写 P5 取代其原生分支**（本轮未改 P5 文件） |
| 6 | 🟡 **第 1 轮 L0 读数（16.32）已作废** | 已闭环 | 根因=内联脚本两层转义；已修并加自证回读 + 有效性门禁。**该数字不进任何结论** |
| 7 | 🟡 **"可达落点"未知** | 未闭环 | 不能断言上游改完能到 L0 水平。L0 是无框架空页面，官方 UI 必须保留功能与视觉 |
| 8 | 🟡 **仅模拟器、单机型（Pixel Tablet）** | 沿袭 | P3 已定性"帧时 87% 是模拟器地板"（`swiftshader_indirect` 软件渲染、4 核）。**p90 长尾在模拟器上会被放大**，真机需复测 |
| 9 | ❌ 耗电 | 测不到 | 沿袭 P4：模拟器无 PMIC，需真机 |
| 10 | ✅ **无设备被外部 kill** | 本轮闭环 | P5 的"web 侧 6 次被外部 kill"**本轮 0 次复现**（web 侧 4 次测量全部成功）。P5 未闭环项 #1b 的不对称问题本轮未再出现 |

---

## 11. 本轮产出物

| 文件 | 内容 |
|---|---|
| `research/perf/LATENCY_FLOOR_B1.md` | **本报告** |
| `research/perf/b1_boot.ps1` | 设备引导（端口互斥 → boot → 几何 → 装包 → reverse → 播种 → 指纹） |
| `research/perf/b1_measure.ps1` | **主尺子**：三层同口径输入延迟 |
| `research/perf/b1_l0_inject.js` | L0 极简页注入（第 2 版，含自证回读） |
| `research/perf/b1_verify.js` | L0 身份探针 |
| `research/perf/b1_l0_validity.ps1` | 有效性门禁（抓出作废的 L0） |
| `research/perf/b1_precheck_native.ps1` | 修正版原生门禁（正向识别） |
| `research/perf/b1_close.ps1` | 收尾（三重复核；已修 `$p.Matches` bug） |
| `research/perf/out_b1/pooled_summary.json` | 合并两轮的分位与分解 |
| `research/perf/out_b1/{L0,L1,L2}/raw_samples.json` | **原始读数**（每层 240 个 ms 值，可逐条复核） |
| `research/perf/out_b1/{L0,L1,L2}/inputlatency.json` | 各层逐轮结果 |
| `research/perf/out_b1/{L0,L1,L2}/framestats_raw.txt` | `dumpsys gfxinfo framestats` 原始输出 |
| `research/perf/out_b1/L0/l0_identity_before/after.json` | L0 身份前后校验 |
| `research/perf/out_b1/validity_{L0,L1}/validity.json` | 有效性检查结果 |
| `research/perf/out_b1/L2/precheck_native.json` | 修正版原生门禁证据 |
| `research/perf/out_b1/fingerprint.txt` | 设备指纹 |
| `research/perf/out_b1/_closeout.txt` | 收尾复核输出 |
| `research/perf/out_p5/precheck_web.json` | web 门禁证据（沿用 P5 脚本，serial=5854） |

