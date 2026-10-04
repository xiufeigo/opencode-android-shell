# AB_NATIVE_VS_WEB — 原生壳 vs Web 壳（路线 A）同设备量化对照

> **一句话结论**：在 `emulator-5636` 上、**同一份 `ab_run.ps1`**、同一轮次、同一参数下跑完四个量。
> Web 壳（`com.xiufeigo.opencodeshell.web.debug`）在 **p50 帧时** 上稳定更差（**+8ms / +47%**），
> 而 `am start -W` 首帧"更快"（1434 vs 2728ms）**是假象**——实测那一帧是**全空白窗口**，
> Web 壳真实内容可见要 **≥15.4 秒**，原生壳 **~2.7–3.3 秒**，**差 ~5.5 倍**。
> **耗电两边都测不到；输入延迟两边样本量悬殊（n=3 vs n=22）不可比。**

---

## 0. 速查：四量对照

| 量 | 原生壳 | Web 壳 | 差（Web−原生） | 判定 |
|---|---|---|---|---|
| **冷启首帧 `am start -W`**（x3，全 COLD） | 2696 / 2791 / 2697 ms，均值 **2728** | 1553 / 1419 / 1329 ms，均值 **1434** | **−1294 ms（−47%，Web 更快）** | ⚠️ **假象**：Web 该帧是**空白窗**（见 §3.1.1） |
| **真实内容可见**（内容就绪下界） | ~2696–3292 ms（`-W` 返回时**已有内容**） | **≥15395 ms** | **≥+12100 ms（≥5.5×）** | 🔴 **Web 最差的量** |
| **janky 帧率** | 94 / 859 = **10.94%** | 74 / 951 = **7.78%** | **−3.16pp（Web 更好）** | ⚠️ 口径矛盾，见 §3.2.3 |
| janky（legacy 口径） | **40.75%** | **78.02%** | **+37.3pp（Web 更差）** | ⚠️ 与现代口径**方向相反** |
| **p50 帧时** | **17 ms** | **25 ms** | **+8 ms（+47%）** | 🔴 **稳态渲染，Web 更差（最干净的信号）** |
| p90 帧时 | 29 ms | 34 ms | +5 ms | Web 更差 |
| p95 帧时 | 34 ms | 34 ms | 0 ms | 持平 |
| p99 帧时 | 150 ms | 46 ms | −104 ms | Web 更好（原生有冷启长尾） |
| GPU p50 | 11 ms | 7 ms | −4 ms | Web 更好 |
| Slow UI thread | 53 | 19 | −34 | Web 更好 |
| Slow issue draw commands | 44 | 55 | +11 | Web 更差 |
| **输入延迟代理**（`HandleInputStart→FrameCompleted`） | n=**3**，p50 **30.76 ms** | n=**22**，p50 **16.62 ms** | −14.14 ms | ❌ **不可比**（样本量差 7 倍） |
| **耗电 mAh** | **0 mAh（测不到）** | **0 mAh（测不到）** | — | ❌ 两边都测不到 |
| 耗电替代量：App CPU | **0 ms** | **0 ms** | — | ❌ 两边都测不到 |

---

## 1. 测量方法

### 1.1 同一份脚本，只换包名

两边跑的是**同一个文件** `research/measure/ab_run.ps1`，**没有重写交互**。
差异只靠环境变量注入：

| 环境变量 | 原生壳 | Web 壳 |
|---|---|---|
| `MEASURE_PKG` | `com.xiufeigo.opencodeshell` | `com.xiufeigo.opencodeshell.web.debug` |
| `MEASURE_ACT` | `.OcActivity` | `com.xiufeigo.opencodeshell.web.WebHostActivity` |
| `MEASURE_LABEL` | `native` | `web` |
| `MEASURE_DEV` | `emulator-5636` | `emulator-5636`（**同一台**） |
| `MEASURE_NSCROLL` | 10 | 10（同参数） |
| `MEASURE_NTAP` | 6 | 6（同参数） |
| `MEASURE_BATWIN` | 120 s | 120 s（同参数） |

```powershell
# 原生壳
$env:MEASURE_DEV='emulator-5636'; $env:MEASURE_PKG='com.xiufeigo.opencodeshell'
$env:MEASURE_ACT='.OcActivity'; $env:MEASURE_LABEL='native'
pwsh -NoProfile -File research\measure\ab_run.ps1
# Web 壳（只换包名/Activity/标签）
$env:MEASURE_PKG='com.xiufeigo.opencodeshell.web.debug'
$env:MEASURE_ACT='com.xiufeigo.opencodeshell.web.WebHostActivity'; $env:MEASURE_LABEL='web'
pwsh -NoProfile -File research\measure\ab_run.ps1
# 归因专用：内容就绪时刻
pwsh -NoProfile -File research\measure\ab_contentready.ps1
```

### 1.2 Activity 名不同（任务书提示已证实）

```powershell
& $adb -s emulator-5636 shell "dumpsys package com.xiufeigo.opencodeshell | grep -A2 'android.intent.action.MAIN'"
```
```
      android.intent.action.MAIN:
        43ac4dc com.xiufeigo.opencodeshell/.OcActivity filter 32909e5
```
```powershell
& $adb -s emulator-5636 shell "dumpsys package com.xiufeigo.opencodeshell.web.debug | grep -A2 'android.intent.action.MAIN'"
```
```
      android.intent.action.MAIN:
        1266a71 com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity filter 62ff056
```
→ 原生 `.OcActivity`；Web `com.xiufeigo.opencodeshell.web.WebHostActivity`（**全限定**，非简写）。

### 1.3 为什么用「窗口相对」手势（不能沿用 `common.ps1` 的绝对坐标）

`common.ps1` 的 `$C` 坐标是在**全屏**下量原生壳控件的。本轮 5636 上**两个 app 都跑在浮动小窗**里：

| app | `dumpsys window` 原始 frame | 尺寸 |
|---|---|---|
| 原生壳 | `parent=[617,75][920,693] display=[617,75][920,693] frame=[617,75][920,693]` | **303 × 618** |
| Web 壳 | `L=532 T=27 R=835 B=645` | **303 × 618** |

**两个窗口尺寸完全相同（303×618）**，这是本轮公平性的关键。
因此 `ab_run.ps1` 每次都用 `dumpsys window` 读**该 app 自己的 frame**，
再在窗口内按**相同比例**做手势 —— 手势次数 / 相对距离 / 时长两边完全一致：

1. `force-stop` → `am start` → 等 3s
2. 窗口内垂直上滑 ×10 + 下滑 ×10
3. 窗口内点按 (0.5, 0.45) ×6，每次点按后 `BACK` + 立即 `am start` 拉回前台
   （不拉回前台进程会被 frozen，`dumpsys gfxinfo` 回 `Skipped: process is frozen`）
4. 窗口内水平滑 ×5 + ×5
5. 末尾点按 (0.5, 0.5)

实际交互墙钟：**原生 41.9 s / Web 42.4 s**（负载一致性自检，通过）。

### 1.4 合规声明

| 事项 | 状态 | 原因 |
|---|---|---|
| **gradle / 装机** | **一律没做** | 两包**已装**，测量**不需要**编译。因此**没有去抢 `.agent-gradle-lock`**（另一 lane 持有，`Test-Path` = True，收尾时仍为 True，**我全程未碰**） |
| 后端起停 | 没做 | `127.0.0.1:4095` 由 Lead 持有；本轮只 `adb reverse` 重挂 |
| `emulator-5642` / `5650` / 任何非 5636 | **一次都没碰** | 所有 adb 调用都带 `-s emulator-5636` |
| `wm` 改过？ | **我没主动改**，但收尾发现被别处改成 1366x420，**已显式重设回 1366x768/160 并复核**（见 §6） |

---

## 2. 设备指纹（两轮实测，原始文件 `out_ab/{native,web}/fingerprint.txt`）

| 项 | 原生壳轮次 | Web 壳轮次 |
|---|---|---|
| `ro.product.name` | `sdk_gpc_x86_64` | `sdk_gpc_x86_64`（**同一台**） |
| `ro.build.version.release` / `sdk` | **14** / 34 | **14** / 34 |
| `ro.product.cpu.abi` | `x86_64` | `x86_64` |
| `uptime` | `09:14:11 up 41 min` | `09:18:13 up 45 min` |
| `wm size` | Physical 3840x2160 / **Override 1366x768** | 同左 |
| `wm density` | Physical 320 / **Override 160** | 同左 |
| `versionName` | `1.4.0`（versionCode 26） | `0.1.0-webhost`（versionCode 1） |
| `lastUpdateTime` | `2026-10-04 08:58:27` | `2026-10-04 09:11:04` |
| `adb reverse` | `host-19 tcp:4095 tcp:4095` | 同左 |

> ⚠️ 两轮**相隔 4 分钟**（uptime 41 → 45 min），**不是严格同一时刻**，
> 且期间宿主上模拟器在增删（收尾时 `5642` 已消失、新增 `5670`）——**这是噪声源**。

---

## 3. 四个量的原始输出

### 3.1 量一：冷启首帧 `am start -W` ×3

**原生壳**（`out_ab/native/amstart_{1,2,3}.txt`，逐字）
```
--- run 1 ---            --- run 2 ---            --- run 3 ---
Status: ok               Status: ok               Status: ok
LaunchState: COLD        LaunchState: COLD        LaunchState: COLD
Activity: com.xiufeigo.opencodeshell/.OcActivity  (三次相同)
TotalTime: 2696          TotalTime: 2791          TotalTime: 2697
WaitTime: 2699           WaitTime: 2794           WaitTime: 2703
```
→ **2696 / 2791 / 2697**，min 2696，max 2791，**均值 2728 ms**，**三次全 COLD**（口径干净）。

**Web 壳**（`out_ab/web/amstart_{1,2,3}.txt`）
```
run 1: COLD TotalTime=1553 WaitTime=1555
run 2: COLD TotalTime=1419 WaitTime=1422
run 3: COLD TotalTime=1329 WaitTime=1333
TotalTime  min=1329 max=1553 mean=1,434 median=1553
```
→ **1553 / 1419 / 1329**，**均值 1434 ms**，**三次全 COLD**。

> 表面上 **Web 快 1294 ms（−47%）**。**但这个结论是错的**，见下。

#### 3.1.1 🔴 `am start -W` 对 WebView 壳**量错了东西**（决定性证据）

**证据 A：`-W` 返回那一瞬，Web 窗口是全空白。**
`am start -W` 返回 `TotalTime: 1790` 后**立刻**截图（`out_ab/timing_web_0000.png`）：
窗口只有标题栏（返回/最小化/关闭三个按钮），**内容区一片空白，没有任何 UI**。

同一时刻原生壳（`-W` 返回 `TotalTime: 3292`，`out_ab/timing_nat_0000.png`）：
**已经渲染出真实内容**——`opencode` / `Connect to a server` /
`Enter your server address and password to get started.`

**证据 B：Web 壳自己的日志显示资源还在持续加载，远晚于 `-W` 返回。**
```powershell
pwsh -NoProfile -File research\measure\ab_contentready.ps1
```
```
[17:23:02] device T0 = 09:23:01.106
[17:23:02.886] am start -W TotalTime = 1330 ms  (host wall 1,385 ms)
[17:23:17] logcat lines matching 'OcWebHost' = 252
--- first 3 ---
09:23:01.xxx I/OcWebHost: facade registered as document-start script
09:23:01.xxx I/OcWebHost/Proxy: listening on http://127.0.0.1:45487 -> http://127.0.0.1:4095
09:23:01.xxx I/OcWebHost: home=http://127.0.0.1:45487/ backend=http://127.0.0.1:4095 passwordPresent=true
--- last 5 ---
09:23:16.xxx I/OcWebHost/Proxy: GET http://127.0.0.1:4095/_assets/svelte-Bj3Hmc9F.js
09:23:16.xxx I/OcWebHost/Proxy: GET http://127.0.0.1:4095/_api/info
device T0 = 09:23:01.106  =>  content-ready LOWER BOUND = 15,395 ms
```
> ⚠️ 设备时钟（09:23）与 host 时钟（17:23）**差 8 小时**。第一版脚本用 host 时刻相减，
> 算出 −28,788,277 ms 这种废数；**已改为用设备自己的 `date` 取 T0** 后才得到 15,395 ms。
> 这是本轮踩到的坑，任何复现者都会踩。

**结论**：`am start -W` 的 1434 ms 量的是 **WebView 空窗首帧**，
**不含** 252 个 JS/CSS asset 的下载执行、**不含** Svelte 挂载、**不含**会话数据渲染。
**真实内容可见下界 = 15,395 ms**，对比原生壳 **2696–3292 ms**：

> ### 🔴 Web 壳最差的量就是这一项：**≥15.4 s vs ~2.7–3.3 s，差约 5.5 倍（+12.1 s 以上）**。

### 3.2 量二：卡顿 / 分位 `dumpsys gfxinfo`

两边都 `reset` → 跑 `SCRIPT-AB` → `dumpsys`（原始 `out_ab/{native,web}/gfxinfo_full.txt`）：

```
  指标                                原生壳            Web 壳
  Total frames rendered               859               951
  Janky frames                        94 (10.94%)       74 (7.78%)
  Janky frames (legacy)               350 (40.75%)     742 (78.02%)
  50th percentile                     17ms              25ms
  90th percentile                     29ms              34ms
  95th percentile                     34ms              34ms
  99th percentile                     150ms             46ms
  Number Missed Vsync                 6                 1
  Number High input latency           1064              1556
  Number Slow UI thread               53                19
  Number Slow bitmap uploads          0                 1
  Number Slow issue draw commands     44                55
  Number Frame deadline missed        94                74
  50th gpu percentile                 11ms              7ms
  Pipeline=Skia (OpenGL)              Skia (OpenGL)     Skia (OpenGL)
  interaction_wall_s                  41.9              42.4
```

#### 3.2.1 最干净的信号：**p50 帧时 Web 差 8ms（+47%）**

窗口尺寸相同（303×618）、手势脚本相同、墙钟相近（41.9 vs 42.4 s）、
pipeline 相同（都是 Skia/OpenGL）——**稳态 p50 帧时 17 → 25 ms 是可比且可信的**。
这是"WebView + JS 渲染每帧更贵"的**最直接证据**。

#### 3.2.2 p99 方向相反：Web 46ms vs 原生 150ms

原生 p99 冲到 150 ms，Web 只有 46 ms。原因是**原生含冷启动首绘长尾**
（`ScriptAB` 第 1 步就是冷启），而 Web 的冷启长尾**发生在 `-W` 返回之后的 JS 加载期**，
那段时间**不在 `gfxinfo` 统计窗口的显眼位置**。
→ 换句话说，**Web 的"p99 更好"有一部分是统计口径把它的冷启成本藏起来了**。

#### 3.2.3 ⚠️ janky% 两个口径**方向相反**（不要单点判胜负）

- 现代口径：Web **7.78% < 10.94%**（Web 更好）
- legacy 口径：Web **78.02% ≫ 40.75%**（Web 差很多）

两个口径打架，说明**这一项在本轮不可判胜负**。
且如 §4.2 所述，两边**画面内容根本不同**，帧的构成也不同。

### 3.3 量三：输入延迟**代理量**

> ### ⚠️ 先把话说死
> **这不是端到端输入延迟。** 它是**平台自己记录的「输入处理开始 → 帧渲染完成」**这段**渲染半段**，
> **不含**用户反应时间、**不含**网络、**不含** LLM、**不含** JS 逻辑执行。
> 两边**同一做法**：`dumpsys gfxinfo <pkg> reset` → 冷启 → 窗口内点按 ×12
> （每次 `BACK` + `am start` 拉回前台）→ `dumpsys gfxinfo <pkg> framestats`。

本机 framestats 为 **24 列新版 schema**，用等价列：
`HandleInputStart`（≈ 旧 `NewestInputEvent`）→ `FrameCompleted`（主口径终点）；
`SwapBuffersCompleted` 作上屏代理；`DisplayPresentTime` 仍不可用。

| 指标 | 原生壳 | Web 壳 |
|---|---|---|
| 解析帧数 n | **3** | **22** |
| i2f p50 | 30.76 ms | 16.62 ms |
| i2f p90 | 145.89 ms | 83.32 ms |
| i2f max | 145.89 ms | 140.4 ms |
| i2f mean | 63.28 ms | 31.99 ms |
| HandleInputStart→SwapBuffersCompleted p50 | 22.7 ms | 15.1 ms |
| IntendedVsync→FrameCompleted p50 | 159.61 ms | 21.63 ms |

> ### ❌ **这一项不可比，本轮不下结论。**
> 理由：**n=3 vs n=22，样本量差 7 倍**。原生壳只产出 3 帧，
> 因为在未连接的 onboarding 界面上点按 + `BACK` 大多**没有产生新帧**
> （点空白处无响应、`BACK` 直接退到桌面）。**3 个样本的中位数没有统计意义。**
> 数字上 Web 更好看，但**不能据此说 Web 输入延迟更低**。

### 3.4 量四：耗电

两边**同做法**：`batterystats --reset` → 循环 `SCRIPT-AB` 直到墙钟 ≥120 s → `force-stop` → `dumpsys batterystats <pkg>`。

| 指标 | 原生壳 | Web 壳 |
|---|---|---|
| 实测窗口 | **127.3 s / 3 轮** | **128.1 s / 3 轮**（负载一致 ✅） |
| `currently on battery` | `true` | `true` |
| Estimated capacity | 1000 mAh | 1000 mAh |
| **`Discharge`** | **0 mAh** | **0 mAh** |
| Screen on/off/doze discharge | 0 / 0 / 0 mAh | 0 / 0 / 0 mAh |
| Computed drain / actual drain | 0 / 0 | 0 / 0 |
| `Total cpu time reads` | **0** | **0** |
| `CPU power brackets; cluster/freq...` | **（空）** | **（空）** |
| App 归因 CPU | `CPU: 0ms usr + 0ms krn` | `CPU: 0ms usr + 0ms krn` |
| `Attribution stats: Total` | **0** | **0** |
| 唤醒来源 | 仅 `*launch*` | `*alarm*` / `NetworkStats` / `*telephony-radio*` |

#### 3.4.1 ❌ **耗电测不到（两边都是），原因三条互相印证**

1. **`Discharge` 恒为 0 mAh** + `Computed drain: 0` —— 无 rail 电流可积分。
2. **`CPU power brackets` 后面是空的** + **`Total cpu time reads: 0`** ——
   x86_64 模拟器**没有真实 PMIC / 电流采样**，各电源轨一条都没有。
3. **App 归因 CPU = 0ms** —— 连 BASELINE_NATIVE.md 用过的那个
   「UID CPU 占用率」替代量，本轮**两边也都是 0**，**该替代量同样失效**。
   （与旧基线的 0.0418% 不可比：那是另一台设备、另一种 batterystats 输出格式。）

> **结论：本环境下"耗电 mAh"和"App CPU 占用率"两项，两边都硬性测不到。**
> 任何声称在此环境拿到 mAh 或 per-app CPU% 的数字都应当被怀疑。
> 唯一可用的相对量是**唤醒来源**：原生只有 `*launch*`（瞬时），
> Web 多出 `*alarm*` / `NetworkStats` / `*telephony-radio*`
> ——但这些**多与它的回环代理进程/网络栈有关，不等于更耗电**，样本也不足以定论。

---

## 4. 判定与归因

### 4.1 Web 壳在哪些量上更差、差多少

| 排序 | 量 | 差多少 | 可信度 |
|---|---|---|---|
| 🔴 **1** | **真实内容可见时间** | **≥+12100 ms（≥15.4s vs ~2.7–3.3s，5.5×）** | **高**（截图 + 252 条日志双重证据） |
| 🔴 **2** | **p50 帧时** | **+8 ms（+47%）** | **高**（同窗口/同脚本/同 pipeline 的稳态量） |
| 🟡 3 | p90 帧时 | +5 ms | 中 |
| 🟡 4 | janky（legacy 口径） | +37.3pp | 低（与现代口径打架） |
| 🟡 5 | Slow issue draw commands | +11 | 中 |
| — | p95 帧时 | 0 | 持平 |
| 🟢 | p99 帧时 / GPU p50 / Slow UI thread / 现代口径 janky% | Web 更好 | 但 p99 有口径藏成本问题，见 §3.2.2 |

### 4.2 「官方 UI 更重」 vs 「WebView 更慢」——分得清 / 分不清

| 量 | 归因 | 依据 |
|---|---|---|
| **内容可见 15.4s** | ✅ **分得清：主要是"WebView + JS 冷启"，不是"UI 更重"** | 15.4 s 里绝大部分是 **252 个 `_assets/*.js|css` 串行下载执行 + Svelte 挂载**。原生壳**根本没有 JS 运行时**。这段成本是**壳形态本身**带来的，与"UI 好不好看"无关 |
| **p50 帧时 +8ms** | ✅ **分得清：WebView 渲染更贵** | 同为 303×618 小窗、同手势、同 Skia/OpenGL pipeline，稳态每帧多花 8 ms。这 8 ms 出现在**已有内容**的稳态滚动里，**不能**用"UI 更重"解释 |
| **janky%** | ❌ **分不清** | ① 两个口径方向相反；② **两边画面内容根本不同**：Web 显示**官方 UI 完整会话列表**（Today/Yesterday/Older 分组、6+ 条会话、图标），原生显示**只有 5 个节点的 onboarding 表单**。帧的构成完全不同，**无法把"UI 更重"从"WebView 更慢"里剥出来** |
| **总帧数 859 vs 951** | ❌ **分不清** | 同上，内容不同 ⇒ 滚动/切页产生的帧数本就不同 |
| **p99 46 vs 150** | ❌ **分不清** | 原生含冷启首绘长尾，Web 冷启长尾在 `-W` 之后。两边**长尾来源不同** |
| **输入延迟** | ❌ **测不准** | n=3 vs n=22 |
| **耗电** | ❌ **测不到** | 见 §3.4.1 |

### 4.3 ⚠️ 本轮**最大**的未闭环：两边内容不匹配

```
原生壳（未连接后端）实际显示：          Web 壳（已连 4095）实际显示：
  Connect to a server                    opencode / Home
  Enter your server address and…           All projects ▾
  Server address  [输入框]                 Search sessions
  Password        [输入框]                  Today   → Counting from 1 to 4000…(6+ 条会话)
                                          Yesterday → Friendly greeting
                                          Older   → android-e2e / DRAFT_OK / …
uiautomator 可见节点：5 个               uiautomator 可见节点：**0 个**（见 §4.4）
```

原生壳停在 **未连接的 onboarding**，Web 壳是**已连接、已渲染完整官方 UI**。
**这是不对等的**：Web 承担了「连接 + 拉会话 + 渲染列表」的全部成本，
原生只画了一张表单。**§0 表里 Web 那些"更好"的项（janky%、p99、GPU p50）
有相当一部分可能来自"它画的界面本来就更简单"，而不是"WebView 更快"。**

→ **要做真正的路线 A 代价判定，必须先把原生壳也连上 `127.0.0.1:4095` 再重跑一轮。**
（本次未闭环的原因见 §7 #1。）

### 4.4 附带发现：WebView 内容**不进** accessibility 树

```powershell
& $adb -s emulator-5636 shell uiautomator dump /sdcard/_ab_web.xml
```
→ 文件只有 2255 字节，**解析出 0 个 text/content-desc 节点**（原生壳同命令能解出 9+ 个）。

**后果**：Web 壳**无法用 uiautomator 做语义化控件定位**，
自动化/无障碍测试对 Web 壳**不可用**，只能用坐标或图像。
这也意味着**任何基于 uiautomator 的回归脚本对 Web 壳天然失效**——
是路线 A 落地时的一个**实打实的工程成本**，不只是性能数字。

---

## 5. 收尾

### 5.1 `wm` 几何 —— 发现漂移并**已显式重设 + 复核**

⚠️ 收尾复核时发现 override 高度**已被别处改成 420**（本轮**我没主动改** `wm`）：

```
收尾前复核：  Override size: 1366x420     ← 漂移
```

**已按任务书显式重设回 harness 规格并复核**：

```powershell
& $adb -s emulator-5636 shell wm size 1366x768
& $adb -s emulator-5636 shell wm density 160
```
```
复核输出：
Physical size: 3840x2160
Override size: 1366x768        ← 已恢复
Physical density: 320
Override density: 160         ← 160
```
> 注：**没有**用 `wm size reset`（那会回到物理 3840x2160，脱离 harness 规格）。
> 采用**显式设为 1366x768/160**，与 BASELINE_NATIVE.md §0.2 的 harness 规格一致。

### 5.2 `/sdcard` 残留

| 文件 | 处置 |
|---|---|
| `/sdcard/_ab_*.xml`（多个 uiautomator dump） | 每次 dump 后当场 `rm` |
| `/sdcard/_s.png`（时序截图） | 每次 pull 后当场 `rm` |
| `/sdcard/_ab_p.png` | 已 `rm` |
| **`/sdcard/_ab_p.xml`** | ⚠️ 收尾复核时发现**残留**，**已删除** |

复核：
```
ls: /sdcard/_ab_*: No such file or directory
ls: /sdcard/_s.png: No such file or directory
```
**无新增残留。** 截图全部 pull 到 `research/measure/out_ab/` 仓库内，未留在设备上。

### 5.3 reset 的影响面（只读侧统计，符合任务书允许范围）

| reset | 影响 | 结论 |
|---|---|---|
| `dumpsys gfxinfo <pkg> reset` | 只清该进程帧统计计数 | 允许 |
| `dumpsys batterystats --reset` | 清全局电量/唤醒计数 | 允许 |
| `adb reverse tcp:4095 tcp:4095` | 重挂回环映射（任务书要求每轮重挂） | 允许；收尾仍在 `host-19 tcp:4095 tcp:4095` |
| `pm grant POST_NOTIFICATIONS` | 授予两个 app 通知权限，**以消除权限弹窗对冷启的干扰** | 见 §7 #4 |
| 装机 / gradle | **一律没做** | 合规；`.agent-gradle-lock` 全程未被触碰（收尾仍 `True`） |

### 5.4 设备纪律

- 全部 adb 调用**均带 `-s emulator-5636`**。
- `emulator-5642` / `5650` **一次都没碰**。收尾时设备列表已变成
  `5636 / 5650 / 5670`（`5642` 消失、`5670` 新增）——**这是别的 lane 的 churn，不是本轮造成的**。

---

## 6. 未闭环 / 不可比项

| # | 项 | 状态 | 影响 | 下一步 |
|---|---|---|---|---|
| **1** | **原生壳未连接后端**，两边内容不匹配 | 🔴 **高** | Web 的 janky%/p99/GPU"更好"无法与原生对比；**§4.3** | 把原生壳连上 `127.0.0.1:4095`，用同一脚本重跑一轮，**这是路线 A 判定的必需项** |
| **2** | **内容可见时间只是「下界」** | 🟡 中 | 15,395 ms 是「最后一次 asset GET」时刻，**真正的首屏可交互可能更晚** | 改为「首屏出现非空像素」检测（连续截图/像素比对），两边同做法 |
| **3** | **输入延迟 n=3 vs n=22** | 🔴 高 | 该量**不可比**，本轮**不下结论** | 原生侧改用**能产生帧的交互**（如反复切页/滚列表）并把样本拉到 ≥30 帧 |
| **4** | 耗电 mAh / per-app CPU | 🔴 高 | **两边都测不到** | 上真机；或给 AVD 开电池仿真 + rail 注入 |
| **5** | janky% 两口径方向相反 | 🟡 中 | 该项**不可判胜负** | 增样本 + 统一口径；报告时**必须两个口径一起给** |
| **6** | 两轮相隔 4 分钟，其间模拟器增删（`5642` 消失、`5670` 新增） | 🟡 中 | 宿主 CPU 争抢 → 噪声源 | 独占机器后重跑；或两轮**交替**（ABABAB）而非先后 |
| **7** | 窗口是浮动小窗 303×618，**非全屏** | 🟡 中 | 所有数字都是"小窗"数字，**不能**外推到全屏/真机 | 若要对外承诺，需在固定全屏几何下重测 |
| **8** | `DisplayPresentTime` 恒为 0；`frame_interval_ms` 解析出 0 | 🟢 低 | 真"上屏"时刻仍测不到 | 只能继续用 `SwapBuffersCompleted` 作代理 |
| **9** | 旧的 `BASELINE_NATIVE.md` 与本轮**不可比** | 🟢 低 | 旧基线在**已消失的 `emulator-5604`**、Android 17 分支、绝对坐标脚本、43.07% janky；本轮是 `5636`、Android 14、窗口相对脚本、10.94% janky | **不要**把旧的 43.07% / 4140ms 当作本轮原生壳基线 |

---

## 7. 复现清单

| 文件 | 作用 |
|---|---|
| `ab_run.ps1` | **本轮主脚本**：指纹 + 四量，两边跑同一个文件 |
| `ab_contentready.ps1` | 归因专用：内容就绪时刻（设备时钟对齐版） |
| `out_ab/native/fingerprint.txt` | 原生壳设备指纹原始 |
| `out_ab/web/fingerprint.txt` | Web 壳设备指纹原始 |
| `out_ab/{native,web}/amstart_{1,2,3}.txt` | 量一原始输出 |
| `out_ab/{native,web}/gfxinfo_full.txt` | 量二原始 dump |
| `out_ab/{native,web}/gfxinfo_parsed.json` | 量二解析 |
| `out_ab/{native,web}/framestats_raw.txt` | 量三原始 |
| `out_ab/{native,web}/inputlatency.json` | 量三解析 |
| `out_ab/{native,web}/batterystats_full.txt` | 量四原始 dump |
| `out_ab/web/contentready.json` | 内容就绪下界（15,395 ms） |
| `out_ab/{native,web}/contentready_logcat.txt` | 252 条 OcWebHost 日志原始 |
| `out_ab/timing_web_0000.png` | 🔴 **`-W` 返回瞬间 Web 窗口全空白** |
| `out_ab/timing_nat_0000.png` | 同一时刻原生壳**已有内容** |
| `out_ab/{native,web}_run.log` | 两轮控制台日志 |
| `out_ab/timing_*.png` | 0/1.5/5/12 s 时序截图 |

一条龙复现：
```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5636 reverse tcp:4095 tcp:4095
$env:MEASURE_DEV='emulator-5636'
$env:MEASURE_PKG='com.xiufeigo.opencodeshell'; $env:MEASURE_ACT='.OcActivity'
$env:MEASURE_LABEL='native'; pwsh -NoProfile -File research\measure\ab_run.ps1
$env:MEASURE_PKG='com.xiufeigo.opencodeshell.web.debug'
$env:MEASURE_ACT='com.xiufeigo.opencodeshell.web.WebHostActivity'
$env:MEASURE_LABEL='web'; $env:MEASURE_TAG='OcWebHost'
pwsh -NoProfile -File research\measure\ab_contentready.ps1
pwsh -NoProfile -File research\measure\ab_run.ps1
```
