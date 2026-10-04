# INPUT_PATH_P5 —— P5：补同机帧时 + 攻「输入延迟 web 差 6.68 ms / p90 82.55 ms」

> - **执行日期**：2026-10-04（硬期限 2026-10-07 之前 ✅）
> - **目的**：(1) 补 P4 缺的那一格 —— **同机 web 帧时**；(2) 对 P4 唯一有数据的短板
>   （**同机输入延迟 web 35.70 / 原生 29.02，p90 82.55 / 37.26**）做**归因 + 逐条验证优化**。
> - **设备**：自建 `OC_Perf_Test`，serial **`emulator-5842`**（首启曾用 `emulator-5840`，见 §8）
>   —— Android 15 / API 35、Pixel Tablet、**WebView 124.0.6367.219**、几何 **1080×2400@420**（与 P4 同）
> - **被测物**：web 壳 `com.xiufeigo.opencodeshell.web.debug` / `WebHostActivity`；对照原生壳 `com.xiufeigo.opencodeshell` / `OcActivity`
> - **后端**：Lead 的 `127.0.0.1:4095`（本轮 **0 次启停**，只 `adb reverse tcp:4095`）
> - **交付 APK**：`research/deliver/opencode-shell-web.apk`，md5 `8dcffc572ad469f472f61594f12ea990`（与 P4 同一份）

---

## ✅ 一句话结论

**输入延迟的短板不是"我们的代码在输入路径上做了重活"，而是 WebView 渲染管线的固有代价。**
同机复测输入延迟 **web p50 32.00 / p90 58.76 ms vs 原生 p50 28.14 / p90 32.84 ms（n=120 两侧）**，
差距由 P4 的 **6.68 ms 收窄到 3.86 ms**、p90 由 45.29 ms 收窄到 25.92 ms；
但 **CDP 归因证明宿主侧无可归因项**：`longtask n=0`、输入处理器 **p90 仅 0.1 ms**、
20 次输入共 **98 条 DOM 变更记录**（不是"每次按键全量重渲染"）、
JS 侧 `pointerdown → 下一帧` **p50 6.1 / p90 14.1 ms**。
`PerformanceEventTiming.duration` 高达 p50 40 / p90 104 ms，而**其中处理器耗时 ≈ 0**
⇒ **时间全部花在"等下一帧"上，不在 JS 里** ⇒ 这是 `WebView ⇄ renderer ⇄ 合成器 ⇄ GPU` 的平台管线成本。

---

## 0. 速查

| 量 | P4（`emulator-5830`） | **P5（`emulator-5842`，同机两侧）** | 判定 |
|---|---|---|---|
| **前置检查（官方 UI）** | 无门禁（P4 踩过对话框坑） | ✅ **PASS**（三条独立证据，§1） | 本轮数字有效 |
| **输入延迟 p50 web** | 35.70 | **32.00**（n=120） | 🟡 变好，但**换机**，不作结论 |
| **输入延迟 p50 原生** | 29.02 | **28.14**（n=120） | 🟢 几乎复现（P4 29.02） |
| **web−原生 p50 差** | **6.68** | **3.86** | 🟡 差距收窄 |
| **输入延迟 p90 web** | 82.55 | **58.76** | 🟡 长尾明显收窄 |
| **输入延迟 p90 原生** | 37.26 | **32.84** | 🟢 |
| **web−原生 p90 差** | 45.29 | **25.92** | 🟡 仍存在，**归因见 §3** |
| **帧时 p50 原生** | 22 ms | **22 ms**（1030 帧） | 🟢 **复现** |
| **帧时 p50 web** | ❌ 4 次全缺 | **见 §2.2** | — |
| janky（现代）原生 | 12.51% | **13.79%（142/1030）** | 🟢 同档 |
| **longtask（输入窗口内）** | 未测 | **0 次 / 0 ms** | ✅ **排除"长任务"** |
| **输入处理器耗时 p90** | 未测 | **0.1 ms**（max 5.6） | ✅ **排除"同步重活"** |
| **`pointerdown → 下一帧`** | 未测 | **p50 6.1 / p90 14.1 ms** | ✅ JS 侧很快 |
| **新增绊线** | 无 | **`InputPathHotspotTest` 6/6 绿 + 反向验证实测变红** | ✅ `webapp` 全量 **26/26 绿**（20→26） |

---

## 1. ✅ 前置检查：确认跑的是**官方 UI**（P4 的教训，硬化成门禁）

> P4 自曝：新 AVD 若 `SecretStore` 为空 ⇒ 弹口令对话框且**永不 `loadUrl`** ⇒
> **四量全测成对话框，却"看起来跑完了"**。P5 把"确认是官方 UI"做成**可自动断言的门禁**，
> **未过门禁的数字一律作废**。

脚本：`research/perf/p5_precheck.ps1`（三条独立证据，缺一不可）
结果落盘：`out_p5/precheck_web.json`

| # | 判据 | 实测 | 结论 |
|---|---|---|---|
| **A** | logcat `passwordPresent=true` **且** **没有** `no password in SecretStore, asking` | `passwordPresent=true`；`asking=False` | ✅ |
| **B** | logcat 里 `OcPerfJS` 出现 **>0** 次（P4 测对话框时 = **0** 次） | **116 次**，页面标记 4 个 | ✅ |
| **C** | CDP 取 `document.title` / 可见文本 / 节点数 / 口令输入框数 | `title="OpenCode"`、**910 节点**、`input[type=password]` = **0**、无"保存并连接"按钮、可见文本是真实项目/会话列表 | ✅ |

```
# target: title=OpenCode url=http://127.0.0.1:38487/
{"title":"OpenCode","body":"Home\n0\nAll projects\nToday\nO\nCounting from 1 to 4000, one number per line\n
opencode-android-shell\nYesterday\nD\nFriendly greeting\n…","nodes":910,"passwordInputs":0,
"dialogSaveBtn":false,"looseKeywordHit":"H"}
"VERDICT_OFFICIAL_UI": true
```

**`OC_Perf_Test` 的数据盘持久 ⇒ P4 播的种仍在**（`out_p5/credcheck_5842.log`）：
```
14:16:24.168 I OcWebHost: home=http://127.0.0.1:44215/ backend=http://127.0.0.1:4095 passwordPresent=true
```
⇒ 每一块测量前都跑了同一门禁（`p5_all.ps1` 的 `CredCheck`），**本报告所有数字都过了门禁**。

> 🧹 **顺手修的两个工具 bug（都在 `research/`，可复现价值 > 本轮数字）**：
> 1. `research/env/cdp_run_e6.ps1:20` 有 `[string]$Serial = 'emulator-5824'` **把 `-Serial` 参数覆盖回硬编码值**
>    ⇒ 任何人传 `-Serial` 都会被静默忽略、命令打到**别人的/不存在的设备**上。已删该行。
> 2. `p5_all.ps1` 的 `Say` 原用 `Write-Output`，把日志行写进了**调用者的返回值** ⇒
>    `if (Func ...)` 收到非空数组**恒为真** ⇒ **一次失败被误判成成功**（本轮真的发生过一次）。
>    已改 `Write-Host`（不进 success stream）。
>    这条也顺手写进了 §6 的绊线方法论：**驱动脚本的"成功判定"本身要能被反向验证**。

---

## 2. 补同机帧时（P4 缺的那一格）

口径与 `ab_run.ps1` / `perf_run.ps1` / `chunk.ps1` **逐字相同**：
`dumpsys gfxinfo <pkg> reset` → **ScriptAB 全文**（`NScroll=10` / `NTap=6`、窗口相对手势）→ 读 p50/p90/p95/p99 + 分项。
**没有**为了保住数据削减交互次数、跳冷启或改参数。

### 2.1 原生壳（测到了）

`out_p5/native/gfxinfo_parsed.json`

| 指标 | AB1 原生 | P4 原生 | **P5 原生** |
|---|---|---|---|
| Total frames | 859 | 1063 | **1030** |
| **p50** | 17 ms | 22 ms | **22 ms** ✅ 复现 P4 |
| p90 | 29 ms | 44 ms | **46 ms** |
| p95 | 34 ms | 65 ms | **73 ms** |
| p99 | 150 ms | 500 ms | **200 ms** |
| janky（现代） | 10.94% | 12.51% | **13.79%（142/1030）** |
| janky（legacy） | 40.75% | 79.87% | **78.74%（811/1030）** |
| Slow UI thread | 53 | 89 | **105** |
| Slow issue draw commands | 44 | 99 | **91** |
| Missed Vsync | — | 43 | **49** |
| GPU p50 | 11 ms | 6 ms | **7 ms** |
| GPU p90 | — | 21 ms | **22 ms** |
| High input latency | — | 1754 | **1524** |
| Slow bitmap uploads | — | 1 | **4** |
| 交互墙钟 | 41.9 s | 45.1 s | **42.5 s** |

> ✅ **P4 的原生 22 ms 被本轮独立复现**（22 ms ↔ 22 ms），说明这一格是稳的。
> ⚠️ 仍**不能**与 AB1（API 34 小窗 303×618）比 —— 不同机。

### 2.2 Web 壳 —— **本轮仍测不到（如实记录，未拿旧数顶替）**

**P4 4 次 + P5 3 次 = 累计 7 次，同一格都没测到。** P5 的 3 次逐字记录：

| 尝试 | 时刻 | 现象 |
|---|---|---|
| 1 | 22:15:26→22:15:57 | `credential: passwordPresent=True` ✅ → ScriptAB 跑完（wall **31.2 s**）→ `gfxinfo EMPTY (device died?)` |
| 2 | 22:20:05→22:20:49 | boot ✅ → ScriptAB 跑完（wall **41.3 s**）→ `gfxinfo EMPTY`；抗杀轮询**一个快照都没落盘** |
| 3 | 22:24:42→22:25:27 | boot ✅ → ScriptAB 跑完（wall **41.0 s**）→ `gfxinfo EMPTY`；独立进程轮询同样 0 快照 |

> 按纪律：**不拿 P2/AB1 的 25 / 28 / 29 / 31 ms 顶替本轮数字**，也不估算。
> ⇒ **「web 侧同机帧时」这一格，本轮仍然空白。**

#### 🔴 但 P5 查出一件 P4 没查的事：**web / native 的失败率完全不对称**

同一台设备、同一套脚本、同一段交互：

| 侧 | ScriptAB 尝试次数 | 成功 | 失败 |
|---|---|---|---|
| **原生** | 2（P4 1 次 + P5 1 次） | **2** ✅ | 0 |
| **web** | 6（P4 4 次 + P5 2 次完整 + 本轮抗杀重试） | **0** | **6** ❌ |

P4 把全部失败归给"别的 lane 批量 kill 设备"（环境问题）。**P5 补了三条反证**：

1. **无 OOM / 无 lmkd / 无 ANR / 无崩溃栈** —— 逐个扫了本轮全部 8 份 emulator 启动日志
   （`research/perf/_p5_emu_*.log|err`），`lowmemorykiller|lmkd|Out of memory|CRASH|ANR|Received signal` **零命中**。
2. **宿主内存充裕** —— `TotalVisibleMemoryMB = 97,504`，`FreePhysicalMemoryMB = 64,574`（**空闲 64 GB**）。
3. **日志尾部是 `Boot completed in 18770 ms` 后直接消失**，既没有正常关机签名、也没有崩溃栈
   ⇒ 进程是**被外部从外面杀掉**的（没机会写日志），**不是**被系统 OOM 杀的，也不是自己崩的。

> **诚实的表述**：证据**支持**"外部 kill"（无 OOM、内存充裕、无崩溃栈、无正常关机日志），
> **但"为什么只有 web 侧被打断、原生侧两次都活下来"这一点，本轮解释不了。**
> 两种可能都还开着：(a) 纯属时间巧合，web 的尝试次数本来就更多；
> (b) web 壳的持续 CPU/内存负载（44 MB 资产 + gzip 透传 + 380 个主题包尾巴）
> 让这台模拟器**更容易被同一个外部清扫命中**。
> **本轮不选边**，列为未闭环项 #1 的子问题，**建议下一轮在宿主清空其他模拟器后单独重跑 web/ft**。

---

## 3. 攻输入延迟：归因（原始读数）

### 3.1 复现并收窄了 P4 的短板

| 分位 | 原生 p50/p90/p95/p99/max | web p50/p90/p95/p99/max | web−原生 |
|---|---|---|---|
| **n** | 120 | 120 | — |
| p50 | **28.14** | **32.00** | **+3.86** |
| p90 | **32.84** | **58.76** | **+25.92** |
| p95 | 33.10 | 63.26 | +30.16 |
| p99 | 34.18 | 71.79 | +37.61 |
| mean | 28.17 | 32.71 | +4.54 |
| max | **34.40** | **80.11** | +45.71 |
| `HandleInputStart→SwapBuffers` p50 | 27.44 | 28.07 | **+0.63** |

> 🔴 **两个关键对照**：
> 1. **原生侧几乎复现 P4**（28.14 vs 29.02，差 0.88 ms）⇒ 平台/量本身是稳的。
> 2. **`HandleInputStart→SwapBuffers` 的 p50 两侧只差 0.63 ms（27.44 vs 28.07）**，
>    而 **`HandleInputStart→FrameCompleted` 差 3.86 ms、p90 差 25.92 ms**。
>    ⇒ **差距出在 `SwapBuffers` 之后的那一段**（提交/合成/上屏），
>    **不是**"事件处理到发起绘制"那段。这是本轮最硬的一条归因。

### 3.2 CDP 归因：时间**不在 JS 里**

采集器 `out_p5/p5_attr_install.js`（**纯被动监听**：全 `passive`、不 `preventDefault`、不改页面行为），
交互窗口 **15.8 s**、20 次 `pointerdown`（10 上滑 + 10 下滑，与输入延迟口径逐字相同），
读数 `out_p5/attr_read.txt`：

| 观测量 | 读数 | **说明什么** |
|---|---|---|
| **longtask** | **n = 0，total = 0 ms，max = 0** | ✅ **"长任务导致"这条被证伪** |
| 事件 `processingEnd−processingStart`（**JS 处理器真实耗时**） | **p50 = 0.0 / p90 = 0.1 / max = 5.6 ms** | ✅ **"输入处理里有同步重活"被证伪** |
| 事件 `processingStart−startTime`（硬件→JS 排队） | p50 3.5 / p90 10.7 / max 39.5 ms | 排队小，**不是**输入管线堵塞 |
| 事件 `duration`（处理开始→下一帧画完，INP 口径） | **p50 = 40 / p90 = 104 / p99 = 552 / max 552 ms** | 🟡 **大，但处理器只占 ~0** |
| **`pointerdown` → 下一帧（JS 侧）** | **p50 6.1 / p90 14.1 / p95 14.6 / max 16.0 ms** | ✅ JS 里按键到出帧**很快** |
| rAF 帧间隔 | p50 **16.7** / p90 16.8 / p99 33.4 / max 483.3 ms；>16.7ms 的 468/856 帧，**>50ms 仅 4 帧** | 主体 60fps 稳定，**偶发**长帧 |
| **DOM 变更（MutationObserver，含 attribute）** | **总 98 条 / 15.8 s ≈ 6.2 条每秒** | ✅ **"每次按键全量重渲染"被证伪**（20 次输入对 98 条记录） |
| 环境 | `dpr 2.625`、`412×915` CSS px、910 节点、`hardwareConcurrency=4`、`deviceMemory=4` | 模拟器 4 核；1080×2400 ÷ 2.625 ≈ 412 ✓ |

#### 归因时间线（一句话版）

```
硬件时间戳 ──3.5ms(p50)──► JS 处理器开始 ──0.0ms(p50)──► 处理完 ──6.1ms(p50)──► 下一帧 rAF ──► 屏幕
  └──────────── event.duration = 40ms(p50) / 104ms(p90)，其中 JS 只占 0.0~0.1ms ────────────┘
                    ↑ 时间不在 JS；Android 侧从 SwapBuffers 到 FrameCompleted 还有 3.86ms(p50)/25.92ms(p90)
```

> ### 🔴 结论：**6.68 / 82.55 ms 花在 WebView 的"合成/上屏"管线上，不在 JS，也不在我们的注入层。**
> 判据：① longtask = 0；② 处理器 p90 = 0.1 ms；③ 每次输入只对应 ~5 条 DOM 变更；
> ④ JS 侧按键到出帧 p50 6.1 ms；⑤ 平台侧 `SwapBuffers` 之后才是差距所在（0.63 → 3.86 ms，p90 1.5 → 25.92 ms）。

### 3.3 宿主侧逐条排查（代码级，**不是**猜）

| 候选 | 排查方法 | 结论 |
|---|---|---|
| **宿主每次输入都过 CDP/JS 注入？** | `grep evaluateJavascript` 于 `WebHostActivity.kt` + `oc-host.js` | ❌ **不存在**。web 壳的 `evaluateJavascript` 只有 3 处：`onPageStarted` 注入 facade、**BACK 键**、debug 通道。**输入分发入口一处都没有**。原生壳（对照）同样只在页面生命周期 + BACK 键调用。 |
| **注入层每次按键都跑自己的代码？** | `oc-host.js` 全文只有 **2 个 `document.addEventListener`**：`DOMContentLoaded`（`once`）与 **`focusin` 委托**。**无 `keydown`/`keypress`/`keyup`/`input`** | ❌ **不存在**。`focusin` 是**焦点生命周期**事件（每次输入只 1 次），且实测每次只做 `closest`+`matches`+1 次 `setAttribute`（IME1 的垫高兜底） |
| **每次输入覆盖输入分发？** | `WebHostActivity.kt` 未 `override` `onTouchEvent`/`dispatchTouchEvent`/`onKeyDown` | ❌ **不存在** |
| **长任务？** | `PerformanceObserver longtask` | ❌ **0 次** |
| **受控输入全量重渲染？** | MutationObserver 计数 | ❌ **98 条 / 20 次输入** |
| **WebView 设置项？** | `configureSettings()` | 见 §4，**只做对照，没有默认开** |

---

## 4. 判定：哪些能修、哪些是上游/平台固有代价

| # | 候选优化 | 判据结果 | **判定** |
|---|---|---|---|
| 1 | **切分/延后长任务** | longtask **n=0** | ❌ **无对象可优化**（不是长任务问题） |
| 2 | **减少每次按键的同步重活** | 处理器 p90 **0.1 ms**、每次输入 **~5 条** DOM 变更 | ❌ **无可优化的同步重活** |
| 3 | **移除宿主每次输入的 CDP/JS 注入** | web 壳输入路径**零** `evaluateJavascript` | ❌ **本来就没有**，无从移除 |
| 4 | **宿主注入层逐键监听** | `oc-host.js` **零**逐键监听 | ❌ **本来就没有** |
| 5 | **WebView 设置项**（如 `setLayerType(LAYER_TYPE_HARDWARE)` 之类） | 本轮**只做代码级对照、未默认开** | 🟡 **留作下一轮 A/B**，**不在本轮擅自改交付态** |

### 🟠 哪些是"官方 UI / 平台的固有代价"（**因此不改上游**）

1. **`event.duration` 的大头是"等下一帧"，不是 JS。** 上游官方 SPA 的受控输入 + 重渲染架构
   （`pointerdown` → 状态更新 → React 提交 → 合成）**每次输入都要走一遍**。
   **不改上游的理由**：上游代码在 `_upstream/**`（本轮**不许碰**）；
   且这是**架构性**的（受控输入），不是 bug —— 改它等于重写上游输入模型，
   收益/风险与"壳"这个交付物的定位不匹配。
2. **平台侧 `SwapBuffers` 之后的合成/上屏差**（p50 +0.63 → 差 3.86 ms；p90 1.5 → 差 25.92 ms）
   是 **WebView ⇄ renderer ⇄ 合成器 ⇄ GPU** 的管线成本。
   **不改的理由**：这是 WebView 平台行为，**壳侧没有开关能把它消掉**；
   能调的只有 §4-5 那类 WebView 设置，且必须做 A/B 才能证明不引入新代价。
3. **模拟器地板**：P3 已定性"帧时 87% 是模拟器地板"（`-gpu swiftshader_indirect` 软件渲染、4 核）。
   **p90 长尾**在模拟器上会被放大，**不能**据此对真机下结论。

> **所以本轮对输入延迟的诚实结论是：web 壳相对原生的那 3.86 ms(p50) / 25.92 ms(p90)
> 差距，在"我们能改的宿主/注入层"里找不到归因项；能找到的都在上游输入架构与 WebView 渲染管线。**

---

## 5. 绊线（tripwire）

### 5.1 可自动断言的：静态绊线（**已加，用例数只增不减，未删/放宽任何断言**）

**新增** `webapp/src/test/java/com/xiufeigo/opencodeshell/web/InputPathHotspotTest.kt`（**6 个用例**）
钉住 §3.3 的三条结论，防止**将来**有人不小心在输入路径上添重活：

| # | 断言 | 钉住什么 |
|---|---|---|
| 1 | `host injection has no per-keystroke listeners` | `oc-host.js` **不得**注册 `keydown`/`keypress`/`keyup`/`beforeinput`/`input` |
| 2 | `web host does not run javascript from input dispatch entrypoints` | `onTouchEvent`/`dispatchTouchEvent`/`onKeyDown`/`onKeyUp`/`onGenericMotionEvent` 函数体内**不得**有 `evaluateJavascript` |
| 3 | `input dispatch is not overridden at all in web host` | web 壳**不得** `override` 输入分发入口（覆写即热路径多一层） |
| 4 | `scanner flags injected-bad-source - per keystroke` | **反向验证**：故意写坏的 JS（含 `addEventListener("keydown")`）**必须**被抓到，且**只**报 `keydown` 这一条 |
| 5 | `scanner flags injected-bad-source - js in touch dispatch` | **反向验证**：故意写坏的 Kotlin（`onTouchEvent` 里 `evaluateJavascript`）**必须**被抓到 |
| 6 | `scanner does not flag legitimate focus lifecycle listener` | **反向验证（防误报）**：IME1 的 `focusin` 委托**不得**被误判成逐键热路径 |

**反向验证必须变红 —— 已实测，两层都做了：**

**（a）内建反向验证（用例 4/5/6）** —— 用例 4/5 把**故意写坏的源码**喂给同一个扫描器，
断言"扫描器必须报错"；用例 6 断言合法的 `focusin` **不得**被误报。
好处是反向验证**本身就是用例**，不污染被测资产。

**（b）真·反向验证（改真实文件，本轮已实际执行）** —— 往**真实的** `oc-host.js` 尾部注入一行
`document.addEventListener('keydown', function(e){ heavyWork(e); });`，重跑：

```
> Task :webapp:testDebugUnitTest FAILED
  InputPathHotspotTest > host injection has no per-keystroke listeners FAILED
  6 tests completed, 1 failed
--- 结果: BUILD FAILED (绊线正确变红 ✅)
=== 还原真实文件 ===
--- 还原后重跑 ---
--- 结果: BUILD SUCCESSFUL (恢复绿 ✅)
```

还原后 `git status --short -- webapp/src/main/assets/www/oc-host.js` **输出为空**
⇒ 文件与 HEAD **逐字节一致**，探针无残留。

**实跑结果（`:webapp:testDebugUnitTest` → `BUILD SUCCESSFUL`）**：

| 用例 | 结果 |
|---|---|
| `host injection has no per-keystroke listeners` | ✅ PASS |
| `web host does not run javascript from input dispatch entrypoints` | ✅ PASS |
| `input dispatch is not overridden at all in web host` | ✅ PASS |
| `scanner flags injected-bad-source - per keystroke` | ✅ PASS（反向验证） |
| `scanner flags injected-bad-source - js in touch dispatch` | ✅ PASS（反向验证） |
| `scanner does not flag legitimate focus lifecycle listener` | ✅ PASS（防误报） |

**回归（`webapp` 全量）**：`TOTAL tests=26 failures=0 errors=0 across 5 classes`
（`DebugEvalChannelTest` / `FallbackPageTouchTargetTest` / `ImeOcclusionTest` /
`InputPathHotspotTest` / `SetupDialogTouchTargetTest`）
⇒ **用例数只增（原 20 → 现 26），未删、未放宽任何既有断言。**

> ✅ **锁纪律**：`.agent-gradle-lock` 先查（不存在）→ 写 → 前台跑完 → **已释放**
> （复核 `Test-Path .agent-gradle-lock` = `False`）。全程未绕过安全策略。

### 5.2 只能设备实测的：**设备侧判据** + 可复现脚本

"有没有长任务 / 每次输入重不重渲染"必须在真机/模拟器上量。可复现脚本已交付：

```powershell
# 1) 装采集器（纯被动监听，不改页面行为）
pwsh -NoProfile -File research\env\cdp_run_e6.ps1 -Serial <serial> -JsFile research\perf\p5_attr_install.js
# 2) 跑与输入延迟逐字相同的手势（10 上滑 + 10 下滑，250ms/间隔 250ms）
#    —— 见 p5_all.ps1 的 CDP ATTRIBUTION 段
# 3) 取读数
pwsh -NoProfile -File research\env\cdp_run_e6.ps1 -Serial <serial> -JsFile research\perf\p5_attr_read.js
```

**设备侧判据（本轮基线，可当回归门限）**：

| 判据 | 本轮实测 | 建议门限 | 若超了说明 |
|---|---|---|---|
| 交互窗口内 **longtask 次数** | **0** | **> 0 即回归** | 输入路径被同步重活堵住 |
| 输入处理器耗时 **p90** | **0.1 ms** | **> 8 ms 即回归** | 上游/注入层在输入里做重活 |
| 每次输入的 **DOM 变更条数** | 98/20 ≈ **~5** | **> 50/输入 即回归** | 变成"每次按键全量重渲染" |
| `pointerdown → 下一帧` **p50** | **6.1 ms** | **> 16 ms（即掉一帧）即回归** | 输入到出帧不再在 1 帧内 |

---

## 6. 方法论记一条（本轮真踩的）

**驱动脚本的"成功判定"自己也要能被反向验证。** 本轮 `p5_all.ps1` 的 `Say()` 用 `Write-Output`，
把日志行写进了 `Chunk()` 的返回值 ⇒ `if (Chunk ...)` 收到非空数组**恒为真**
⇒ **一次 `gfxinfo EMPTY`（设备被杀）被打印成 `== web/ft OK ==`**。
若不是我又去核对落盘 JSON，这个假成功会一路写进报告。
⇒ 已改 `Write-Host`，并把"失败必须能被看见"写进本节。
（同源教训还有 `cdp_run_e6.ps1` 的硬编码 `$Serial` 覆盖参数。）

---

## 7. 未闭环

| # | 项 | 状态 | 说明 / 下一步 |
|---|---|---|---|
| 1 | 🔴 **Web 壳同机帧时** | **本轮仍测不到**（P4 4 次 + P5 3 次 = **累计 7 次**） | 见 §2.2。已排除 OOM/崩溃（**无 lmkd/ANR/崩溃栈**，宿主**空闲 64 GB**）。**下一步：在宿主清空其他模拟器后单独重跑 web/ft**；并顺带回答"为什么只有 web 侧被打断"这个不对称问题 |
| 1b | 🟡 **web / native 失败率不对称** | **未解释** | 原生 2/2 成功、web 0/6 成功。两种可能仍开着：(a) 巧合（web 尝试次数本就多）；(b) web 壳的持续 CPU/内存负载让这台模拟器**更易被同一外部清扫命中**。本轮**不选边** |
| 2 | ✅ **绊线** | **已闭环** | `InputPathHotspotTest` 6/6 PASS + **真·反向验证实测变红**（改坏真实文件 → 1 failed → 还原 → 绿）；`webapp` 全量 **26/26 绿**（用例 20→26，只增不减） |
| 3 | 🟡 **WebView 设置项 A/B** | 未做 | §4-5：本轮只做代码级对照，**未默认开**任何设置项。若要继续，需按 §5.2 做前后对照 |
| 4 | 🟡 **输入延迟绝对值跨轮可比性** | 分不清 | P4 `emulator-5830` vs P5 `emulator-5842`，**不同机**。原生侧几乎复现（28.14 vs 29.02）支持"量本身稳"，但 web 侧 32.00 vs 35.70 的差**不能**归因到代码 |
| 5 | 🟡 **宿主多 lane 并发起停模拟器** | 🔴 环境问题（沿袭 P4 #4） | 本轮 web 侧交互期间设备被外部 kill **6 次**。**建议派单时按 AVD 名/端口互斥** |
| 6 | 🟡 **`cdp_run_e6.ps1` 硬编码 `$Serial`** | ✅ **本轮已修** | 该 bug 会让任何人传 `-Serial` 都被静默忽略、命令打到**别人的/不存在的设备**上。建议全仓扫一遍有没有第二处 |
| 7 | 耗电 | ❌ 测不到 | 沿袭 P4：模拟器无 PMIC，需真机 |
| 8 | 380 个主题包尾巴 | 🟡 沿袭 P2 #1 | 本轮未涉及 |

---

## 8. 收尾

见文末「收尾复核输出」小节（实际执行输出逐字贴在下方）。

### 8.1 我碰过的 serial

| serial | AVD | 是不是我起的 | 我对它做了什么 |
|---|---|---|---|
| `emulator-5840` | `OC_Perf_Test` | ✅ **是**（本轮首启） | 起 → 几何 1080×2400@420 → 装 2 个 APK → `adb reverse` → 播种校验 → **前置门禁 PASS** → **web 输入延迟**（p50 32.00 / p90 58.76）→ 之后被外部 kill |
| `emulator-5842` | `OC_Perf_Test` | ✅ **是**（同一 AVD，本轮重启后沿用） | 起（自愈重启多次）→ 几何 → `adb reverse` → 凭据门禁 → **原生输入延迟**（p50 28.14 / p90 32.84）→ **原生帧时**（p50 22 ms / 1030 帧）→ **CDP 归因**（longtask 0 / 处理器 p90 0.1 ms / 输入→下一帧 p50 6.1 ms）→ web 帧时 3 次尝试 → 收尾关掉 |
| `emulator-5640` | **`T77_Phone`**（**非 `OC_*`，用户自己的**） | ❌ 否 | **零操作**（只在 `adb devices` 里出现过名字；收尾复核时确认它 PID 未变、仍在） |

**故意保留的实例：无。**

**禁止项自查**：

| 规矩 | 结果 | 证据 |
|---|---|---|
| 谁起谁关 | ✅ | 见 §8.2 复核输出 |
| 进程 0 残留 | ✅ | 残留本轮 AVD 进程数 = **0**；`qemu-system-x86_64` 进程数 = **0** |
| 端口已释放 | ✅ | 端口 **5840** 与 **5842** 的 `Get-NetTCPConnection -State Listen` **均为空** |
| 只关自己起的（按 AVD 名） | ✅ | 关停前基线里只有 `T77_Phone`（**非 `OC_*`**），收尾后**它仍在、PID 未变（58028）** |
| 用户 AVD 禁止 kill | ✅ | `MCRemote_*` / `DSH_*` / `T7x_*` 本轮**一次都没起过、一次都没碰过** |
| 禁 `adb kill-server` | ✅ | 全程未执行（`adb devices` 仍正常服务 `emulator-5640`） |
| 禁 `-a` / 通配符杀进程 | ✅ | 只按 serial（`emulator-5840/5842`）与 AVD 名（`OC_Perf_Test`）定位 |
| 不许起停后端 | ✅ | `127.0.0.1:4095` **0 次启停**，全程只 `adb reverse tcp:4095` |
| 抢锁纪律 | ✅ | `.agent-gradle-lock` 先查→写→前台跑完→**已释放**（复核 `False`） |

> 说明：收尾时本轮 AVD 进程**已不存在**（是测量期间被外部 kill 掉的），
> 所以 `[B]` 段如实打印"本轮 AVD 无残留进程（可能已被外部 kill）"，
> 随后对两个已知 serial 各做一次**幂等** `emu kill`（对已消失的设备无副作用）。
> **这不是"跳过关停"** —— 关停后的三条复核（进程 0 残留 / 端口已释放 / adb devices 无本轮 serial）全部通过。

### 8.2 收尾复核输出（`out_p5/_closeout.txt`，逐字）

```
[22:26:15] === [A] 关停前基线：当前全部 emulator 进程（只列，不动别人的） ===
  pid=58028  name=emulator.exe           avd='T77_Phone' port='5640'  <== 别人的/来源不明，未碰
[22:26:17] === 端口 5840 关停前监听数 = 0 ===
[22:26:17] === 端口 5842 关停前监听数 = 0 ===
[22:26:17] === [B] 关闭本轮自建实例（只按 AVD 名 + serial） ===
[22:26:17]   本轮 AVD 无残留进程（可能已被外部 kill）
[22:26:30] === [C] 复核 1：进程（本轮 AVD 应 0 残留） ===
  残留本轮 AVD 进程数 = 0
  pid=58028  name=emulator.exe           avd='T77_Phone' port='5640'  <== 别人的，未碰
  qemu-system-x86_64 进程数 = 0   (这才是 CPU 幽灵占用的判据)
[22:26:31] === [D] 复核 2：端口 5840 5842 监听（应为空） ===
  Get-NetTCPConnection -LocalPort 5840 -State Listen => <空>，端口已释放
  Get-NetTCPConnection -LocalPort 5842 -State Listen => <空>，端口已释放
[22:26:32] === [E] 复核 3：adb devices（不应再有本轮 serial；未执行 adb kill-server） ===
  List of devices attached
  emulator-5640	device
   （全程未执行 adb kill-server；未使用 -a / 通配符；非 OC_* 实例一次都没碰）
[22:26:32] === CLOSEOUT DONE ===
```

### 8.3 `wm` 复原

本轮**改过** `wm size` / `wm density`（1080×2400 / 420）。
**处理方式**：与 P4 相同 —— 本轮**自建**的 AVD `OC_Perf_Test` 整台在收尾已关掉，
其 override 几何随实例一起消失，**不留残留状态**；
用户自己的 AVD（`T77_Phone` / `MCRemote_*` / `DSH_*` / `T7x_*`）**一个都没碰过 `wm`**。

---

## 9. 本轮产出物

| 文件 | 内容 |
|---|---|
| `research/perf/INPUT_PATH_P5.md` | 本报告 |
| `research/perf/p5_setup.ps1` | 前置：等 boot → 几何 → 装包 → `adb reverse` → 播种口令 |
| `research/perf/p5_precheck.ps1` | **前置门禁**（A/B/C 三条独立证据；不过则数字作废） |
| `research/perf/p5_all.ps1` | 主驱动：自愈重启 + 凭据门禁 + 补缺的格子 + CDP 归因 |
| `research/perf/p5_chunk.ps1` | `chunk.ps1` 的**去睡眠窗口**版（ScriptAB 逐字未改） |
| `research/perf/p5_ft_resilient.ps1` | 帧时**抗杀**采集（交互中并行轮询 `gfxinfo`，保留全部快照） |
| `research/perf/p5_snap_poller.ps1` | 独立**进程**版 `gfxinfo` 轮询（`Start-Job` 版本实测 0 快照，故改为独立进程） |
| `research/perf/p5_close.ps1` | **收尾**（只按 AVD 名 + serial 关；进程/端口/adb 三重复核） |
| `research/perf/p5_attr_install.js` | 输入路径采集器（纯被动：`longtask`/`event` timing/rAF 间隔/输入→下一帧/MutationObserver） |
| `research/perf/p5_attr_read.js` | 采集读数 + 分位 |
| `webapp/src/test/java/.../InputPathHotspotTest.kt` | **新增绊线**（6 用例，含 3 条反向验证） |
| `research/env/cdp_run_e6.ps1` | **修 bug**：删掉覆盖 `-Serial` 的硬编码行 |
| `research/perf/out_p5/**` | 全部原始读数（`precheck_web.json` / `inputlatency.json` ×2 / `gfxinfo_parsed.json` / `attr_read.txt` / `_all.log`） |
