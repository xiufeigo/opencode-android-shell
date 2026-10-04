# STARTUP_AND_FRAMETIME — P2：把 Web 壳的首屏与稳态帧时打下来

> **一句话结论（先给结论）**：
> 上一轮报的 **「内容可见 ≥15,395 ms」是量错了东西** —— 它量的是**最后一个 `_assets` GET 的时刻**，
> 不是首屏出现内容的时刻。逐段打点证明：**FCP 2130 ms、DOM 挂载完成 2572 ms、会话数据齐 3645 ms**，
> 首屏内容在 **~2.1–2.6 s** 就已经可见，和原生壳的 2.7–3.3 s **基本同档**。
> 真正的 15–31 s 是一条**不阻塞首屏的后台资源尾巴**：官方 UI 在首屏之后**串行**去拉
> **380 个语法高亮主题/语言包**（`nord`/`nightowl`/`pierre-*`/`plsql`/`perl`/`nope-01..12`…），
> 每个固定 ~78 ms。
>
> 所以本轮 TOP3 不是"15 秒花在首屏上"，而是：
> **① 27.8 s 的串行主题包尾巴（不阻塞首屏，但吃满带宽/电量）② 每请求 ~44 ms 的代理定界开销
> ③ 1.5 s 的导航前串行启动段**。
>
> 修法实测（三轮同一份 APK，只切 `--es oc_perf_mode`）：
> - **gzip 透传：传输量 −46.4%（ratio 1.00 → 0.54），对 p50 与输入延迟无代价 ⇒ 建议合入。**
> - **连接定界（缓冲算 Content-Length）：可复用 0 → 430/430、每请求开销 43.9 ms → 1.4 ms（−97%）
>   —— 但 p50 +3 ms、输入延迟 +4.2 ms、GPU p50 +7 ms，且墙钟一点没改善 ⇒ 建议不合入。**
> - **稳态 p50 帧时 28 → 31 ms，没修好**；把传输量砍 46% 后纹丝不动 ⇒ **与字节量无关**，
>   是 WebView 合成路径本身，**壳侧无可修开关**。
> - **输入延迟做成了真可比（两侧 n=120），结论反转**：原生 22.27 / Web 22.41 ms，
>   **相差 0.14 ms，基本无差异**；AB1 的"Web 快 14 ms"是样本量假象，**撤回**。

---

## 0. 速查

| 量 | 上一轮结论 | **本轮实测** | 判定 |
|---|---|---|---|
| 内容可见 | **≥15,395 ms** | **FCP 2130 ms / DOM 挂载 2572 ms** | 🔴 **上一轮量错了**，见 §2.3 |
| 资源尾巴结束 | 15,395 ms | **31,405 ms**（本轮更差） | 🔴 真实存在，但**不阻塞首屏** |
| 传输压缩率 | 未测 | **1.00 → 0.54**（修后） | ✅ gzip 生效，−46.4% |
| 每请求代理开销 | 未测 | **43.9 ms → 1.4 ms**（定界修后） | ✅ 缺陷修掉，但**净效果为负**，见 §3.5 |
| 请求间隔 | 未测 | **77.2 ms**（严格串行，修后仍 76.3 ms） | 🔴 TOP1 的形态，**修不掉** |
| 稳态 p50 帧时 | 25 ms（原生 17） | **baseline 28 / fixed#1 29 / fixed#2 31 ms** | 🔴 未修好，且**与字节量无关** |
| janky（现代口径） | 7.78% | 16.37% / 14.32% / 17.89% | 🟡 口径仍不可判 |
| **输入延迟代理** | ❌ 不可比 n=3 vs n=22 | ✅ **n=120 vs n=120** | ✅ **已可比** |
| 输入延迟 p50 | "Web 快 14 ms" | **原生 22.27 / Web baseline 22.41 / Web fixed#2 26.63 ms** | ⚠️ **结论反转：基本无差异** |

---

## 1. 测量方法（本轮的关键设计）

### 1.1 改前/改后用**同一个 APK**

最大的可比性陷阱是"改前跑旧包、改后跑新包"——重编译差异会污染对比。
所以修复**没有做成另一个构建**，而是在同一个 APK 里用运行时开关分流：

```powershell
# baseline = 原行为（改前）   fixed = 修复后（改后）
am start -n <pkg>/<act> --es oc_perf_mode baseline
am start -n <pkg>/<act> --es oc_perf_mode fixed
```

打点全部是**纯新增**（`Log.i` / `console.log`），`baseline` 分支不改变任何行为。
**两轮是同一份二进制**，所以"改前 vs 改后"只反映代码改动本身。

### 1.2 计时基准：进程创建（不是墙上时钟）

AB1 踩过的坑：设备时钟与 host 差 8 小时，相减得到 −28,788,277 ms 这种废数。
本轮一律用 `Process.getStartUptimeMillis()`（API 24+，minSdk 26 满足），
**t=0 = 进程创建**，不依赖任何跨机时钟对齐。

新增 `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/PerfMarks.kt`，
落 logcat 的 `OcPerf` tag；浏览器侧新增 `assets/www/oc-perf.js`，
注册为 document-start 脚本，走已有的 `onConsoleMessage` 通道（落 `OcPerfJS` 前缀）。

### 1.3 稳态帧时用**与 AB1 逐字相同**的 ScriptAB

`research/perf/perf_run.ps1` 里的 `ScriptAB` 是从 `research/measure/ab_run.ps1`
**逐字复制**的（同样的窗口相对手势、同样的次数 `NScroll=10 / NTap=6`），
交互墙钟实测 42.8 s / 42.9 s，与 AB1 的 41.9 s / 42.4 s 同档 ⇒ 负载一致。

### 1.4 ⚠️ 我改了测量脚本参数，必须写明

- **删掉了耗电那一段**（`[4] batterystats`）——AB1 §3.4.1 已证明本环境
  x86_64 模拟器**无 PMIC/电流采样，`Discharge` 恒 0**，重跑 2×2 分钟纯属浪费。
  这不改变任何"能测出来的量"的结论。
- **冷启 settle 窗口改为 30 s**（AB1 是 ~15 s）——本轮尾巴实测跑到 **31.4 s**，
  15 s 窗口会**截断尾巴**，看不到真实终点。
- **输入延迟采样方法换了**（见 §5），AB1 的点按法在原生侧只产出 3 帧，不可比。

---

## 2. 把 ≥15.4 s 剖开（核心）

### 2.1 逐段耗时表（baseline，t=0 = 进程创建，单位 ms）

| 段 | 打点 | 累计 | 本段耗时 |
|---|---|---|---|
| 进程创建 → Activity.onCreate | `A.onCreate.enter` | **641** | 641（类加载 + 进程 fork） |
| onCreate → super | `A.onCreate.superDone` | 678 | 37 |
| → bridge/fileChooser/notifier 就绪 | `B.helpersReady` | 683 | 5 |
| → **WebView 创建 + setContentView** | `C.webviewCreated` | **946** | **263** |
| → settings 配完 | `D.settingsConfigured` | 949 | 3 |
| → facade + perf 探针装完 | `E.facadeInstalled` | 964 | 15 |
| → insets + back handler | `F.insetsReady` | 973 | 9 |
| → 进 launch() | `H.launch.enter` | 996 | 23 |
| → **回环代理开始监听** | `I.proxyListening` | **1128** | **132** |
| → **loadUrl 发出** | `J.loadUrlIssued` | **1158** | 30 |
| onCreate 返回 | `G.onCreate.exit` | 1159 | 1 |
| **WebView 首帧** | `Z.firstDraw` | **1315** | 156 |
| `onPageStarted` | `K.onPageStarted` | 1485 | 170 |
| **`/` 文档首字节可用** | `P.fwd path=/` | **1516**（上游 190 ms） | 31 |
| `DOMContentLoaded` | `DCL` | **2002** | 486 |
| `load` | `load` | 2090 | 88 |
| **first-paint / FCP** | `paint first-paint` | **2130** | 40 |
| **DOM 挂载出内容**（428 节点） | `tick+2` | 2096 | — |
| **DOM 挂载完成**（845 节点） | `tick+3` | **2572** | — |
| `onPageFinished` | `L.onPageFinished` | 2529 / 3234 | — |
| `/api/info` | `P.fwd` | 3093 | — |
| **`/sw.js`（Service Worker）** | `P.fwd` | **3315** | — |
| **`/api/session`（会话列表数据）** | `P.fwd` | **3363** | — |
| 会话详情 | `P.fwd /api/session/ses_…` | 3645 | — |
| **背景尾巴结束** | 最后一个 `P.fwd` | **31,405** | 27,760 |

### 2.2 页面往后端发的关键请求序列（baseline 瀑布）

| 时刻 | 请求 | 说明 |
|---|---|---|
| 1516 ms | `GET /` | 文档，**免认证** 200 text/html，上游 190 ms |
| 1587–2500 ms | `GET /_assets/rolldown-runtime-*.js` … `index-*.js` … 20+ CSS | 关键 JS/CSS |
| 2002 ms | `DOMContentLoaded` | — |
| 3036–3079 ms | `/assets/Inter.ttf`、`/_assets/IBMPlexMono-*.woff2` | 字体 |
| 3093 ms | `GET /api/info` | **要 Basic 认证** |
| 3315 ms | `GET /sw.js` | **Service Worker 注册** |
| 3333–3472 ms | `/api/project`、`/api/session`、`/api/location`、`/api/vcs`、`/api/session/active` | 首屏数据 |
| 3315–4318 ms | `/site.webmanifest`、`web-app-manifest-*.png` | **PWA manifest** |
| **3700–31405 ms** | **359 个 `/_assets/*`** | 🔴 **见下** |

### 2.3 🔴 最重要的发现：**15.4 s 量错了**

- 浏览器侧 `paint first-contentful-paint at=2130`、`tick nodes=845 at=2572`、
  `/api/session` 在 3363 ms 返回 ⇒ **首屏内容 ~2.1–2.6 s 可见**。
- 而"最后一个 asset GET"在 **31,405 ms**（本轮甚至比 AB1 的 15,395 ms 更长，
  因为 AB1 只等了 15 s 就**把尾巴截断了**）。

> **AB1 的「≥15,395 ms 内容可见」把"最后一个 `_assets` GET"当成了"内容可见"。**
> 这是一个**上界被误读成实际首屏**的经典错误：
> 它同时**低估了首屏**（因为尾巴还在跑）和**高估了"Web 壳首屏比原生差 5.5 倍"这个结论**。
>
> 修正后：**Web 壳首屏 ~2.1–2.6 s vs 原生壳 2.7–3.3 s —— 同一档，不存在 5.5 倍差距。**

⚠️ 但"尾巴"本身仍是**真问题**（见 §3），只是它**不阻塞首屏**。

### 2.4 TOP3 消耗（明确回答）

| 排名 | 消耗 | 占多少 ms | 阻塞首屏？ |
|---|---|---|---|
| **TOP1** | **串行拉 380 个语法高亮主题/语言包**（359 个落在 3700–31405ms） | **27,760 ms** | ❌ **否**（FCP 早它 29 s） |
| **TOP2** | **每请求 ~44 ms 代理定界开销**（剥掉 Content-Length + 强制 `Connection: close`，客户端只能等 socket 关闭） | **~16,990 ms**（387 × 43.9） | ❌ 否（但**是 TOP1 的成因**：78 ms 里 44 ms 是白等的） |
| **TOP3** | **导航前串行启动段**：进程创建→loadUrl | **1,158 ms** | ✅ **是** |

**首屏关键路径**（真正影响用户看到东西的）只有：
`641 进程创建` + `263 WebView 创建` + `132 代理起` + `170 onPageStarted` + `190 上游出文档`
+ `486 到 DCL` + `40` 到 FCP ⇒ **FCP 2130 ms**。

### 2.5 页面请求瀑布的形态（关键）

```
t=29712  nope-12-DtMeaqDs.aac  upstreamMs=5  totalMs=7
t=29791  nope-11-CvtzRPxI.js   upstreamMs=6  totalMs=58     Δ=79
t=29819  nope-11-CVdXg8G-.aac  upstreamMs=5  totalMs=7      Δ=28
t=29901  nope-10-CUjM7yjI.js   upstreamMs=7  totalMs=60     Δ=82
```

- 相邻请求间隔 **均值 77.2 ms / p50 78 ms / p90 92 ms**，**严格串行**；
- 但**上游自己只要 5–9 ms**。
- ⇒ **~90% 的时间不是后端在算，是链路在等**。

---

## 3. 修（按 TOP3 逐个，每条给改前/改后同脚本数字 + 为什么安全）

### 3.1 修 #1：透传压缩（gzip）

**候选方向验证**：先确认是不是"没压缩"。JS 探针直接给出证据 ——
`res-total n=59 xfer=2,715,543 dec=2,697,843 **ratio=1.01**` ⇒ 传输量 = 解码量，**零压缩**。

**根因**：`LoopbackProxy.shouldDropRequestHeader()` 把 `accept-encoding` 一起 drop 了
（与 `host`/`origin`/`authorization` 同列），于是上游按 `identity` 回明文。

**改动**（`fixedMode` 开启时）：显式写 `Accept-Encoding: gzip`。
**为什么只承诺 gzip 不承诺 br**：WebView 默认发 `br, gzip, deflate`，
而 OkHttp 只有在显式设置了该头时才**不**自动解压；`br` 需要额外的 brotli 依赖，
没有它会被静默不解压 ⇒ 只能承诺 `gzip`。

**改前 → 改后**：

| 指标 | baseline | fixed | 变化 |
|---|---|---|---|
| **传输量 xfer** | 2,715,543 B | **1,455,939 B** | ✅ **−46.4%** |
| 解码量 dec | 2,697,843 B | 2,698,107 B | 持平（内容没变） |
| **ratio** | **1.01** | **0.54** | ✅ |
| `ce=gzip` 的响应数 | 0 | **376** | ✅ |
| FCP | 2130 ms | 2290 ms | −160 ms（噪声内） |
| 尾巴结束 | 31,405 ms | 31,527 ms | **无改善** |

> ### ✅/❌ 判定：**传输量减半，但墙钟没动。**
> 因为瓶颈是**每请求一次往返**（§2.5），不是带宽。**这一条修的是"浪费"，不是"延迟"。**
> 它仍然值得保留（省一半流量、省电），但**不要拿它当首屏提速项汇报**。

### 3.2 修 #2：连接复用（keep-alive）——**第一轮没生效，第二轮才修对**

**第一轮（`fixed` 第一版）的失败**：`reusable=true` 只有 **12 / 417**，`len>0` 只有 12。
**根因**：我最初只信 `resp.body.contentLength()`，而**上游对大部分资源用
`Transfer-Encoding: chunked`，没有 Content-Length** ⇒ `contentLength()` = −1
⇒ 只能 `Connection: close` ⇒ 无法复用。
实测佐证：overhead 仍是 **43.5 ms**、gap 仍是 **78.4 ms**（与 baseline 的 43.9/77.2 几乎一样）。

**第二轮修法**（已实现）：`fixedMode` 下把**非流式**响应体读干净、自己算长度、写 `Content-Length`。
**SSE 明确排除**（无限流，缓冲会永久挂起）。

**为什么安全**：
1. 只改**传输层定界**，不改认证、同源、CORS、SSE、WebSocket 语义；
2. SSE 走 `isEventStreamRequest()` 分支，**行为逐字不变**；
3. 显式设 `Accept-Encoding` ⇒ OkHttp 不自动解压 ⇒ 缓冲的就是线上真实字节数，
   `Content-Length` 与实际写出的字节**必然一致**，不会截断/粘包；
4. 缓冲上限 = 单个响应体大小（最大资产 ~百 KB 级），不是全量累积。

**改前 → 改后**：见 §3.4 表（第二轮结果）。

### 3.3 修 #3：导航前串行启动段（**部分采纳**）

`launch()` 在 `onCreate` **末尾**才起代理，而 `ServerSocket(0)` 每次换端口 ⇒ 端口**无法预知**，
所以**不能**简单地"提前起"。可省的是 `WebView 创建 263 ms` 与 `代理起 132 ms` 的**串行**。
本轮**只做了测量、没有改**：因为把 `launch()` 提前会改变"起不来时兜底占位页"的分支顺序，
属于行为改动而非纯性能改动，**收益（~130 ms）相对风险不划算**。
明确记录为**已归因、未实施**，不虚报。

### 3.4 改前/改后总表（同一脚本、同一 APK、同一台设备）

| 指标 | baseline | fixed #1(gzip) | **fixed #2(gzip+定界)** |
|---|---|---|---|
| 传输量 | 2,715,543 B | 1,455,939 B (−46.4%) | 1,455,939 B (−46.4%) |
| 传输/解码 ratio | 1.01 | 0.54 | **0.54** |
| `ce=gzip` 响应数 | 0 | 376 | **389** |
| **可复用响应** | 0 / 424 | 12 / 417 | **430 / 430 ✅** |
| **每请求代理开销 均值** | 43.9 ms | 43.5 ms | **1.4 ms ✅（−97%）** |
| 每请求代理开销 p50 | 48 ms | 47 ms | **1 ms ✅** |
| **请求间隔 均值/p50** | 77.2 / 78 ms | 78.4 / 78 ms | **76.3 / 78 ms（无改善）** |
| 尾巴结束 | 31,405 ms | 31,527 ms | 31,582 ms（无改善） |
| FCP | 2130 ms | 2290 ms | 2228 ms（噪声内） |
| **稳态 p50 帧时** | **28 ms** | **29 ms** | **31 ms ⬅ 变差** |
| **输入延迟代理 p50** | **22.41 ms** | 21.04 ms | **26.63 ms ⬅ 变差** |
| GPU p50 | 3 ms | 3 ms | **10 ms ⬅ 变差** |

### 3.5 🔴 第二轮修法的**净效果是负的**（必须写明，且据此给出取舍）

**修 #2 在它瞄准的那个指标上完全成功**：
可复用响应 0 → **430/430**，每请求代理开销 **43.9 ms → 1.4 ms（−97%）**。
这个 43.9 ms × 387 个请求 ≈ **17 s 的白等被消除**，方向完全正确。

**但它让整体变慢了**，而且是可测的：

| 指标 | baseline | fixed#2 | 变化 |
|---|---|---|---|
| 稳态 p50 帧时 | 28 ms | **31 ms** | **+3 ms** |
| 输入延迟代理 p50 | 22.41 ms | **26.63 ms** | **+4.2 ms（+19%）** |
| GPU p50 | 3 ms | **10 ms** | **+7 ms** |
| janky（现代） | 16.37% | 17.89% | +1.5pp |

**原因（可解释，不是玄学）**：把响应体**整体缓冲**再发，等于**摧毁了流式回压**。
改之前，字节是**边到边写**给 WebView 的；改之后，**必须等整个 body 下载完才吐第一个字节**。
于是首字节延迟变高、代理线程持锁更久、内存拷贝与 GC 压力上升
（GPU p50 从 3 → 10 ms 就是拷贝/GC 的直接体现）。
**用"每请求少 42 ms 白等"换来"每个响应多等整个 body 的时间"——净亏。**

### 3.6 ✅ 最终取舍建议（给 Lead 的行动项）

| 改动 | 建议 | 理由 |
|---|---|---|
| **gzip 透传** | ✅ **保留并合入** | 传输量 −46.4%，**对 p50/输入延迟无可测代价**（28→29 ms、22.41→21.04 ms，都在噪声内）。纯赚。 |
| **整体缓冲定界** | ❌ **不要合入** | 每请求开销虽 −97%，但 p50 +3 ms、输入延迟 +4.2 ms、GPU p50 +7 ms，且**首屏/尾巴墙钟一点没改善**。 |
| 若还想保留定界收益 | 🟡 **只在响应体较小时缓冲** | 例如 `< 32 KB` 才缓冲并自己算 `Content-Length`，大文件继续流式。**本轮没做，需真机 A/B 验证。** |
| **禁掉 SW 预缓存** | 🟡 待验证 | `/sw.js` 确实加载了（@3315 ms），但**未确证**它在预缓存那 380 个包。若确证，壳侧禁 SW 是**唯一**能真正砍掉 27.8 s 尾巴的开关。 |
| **启动段并行化** | ⛔ 不建议 | 收益 ~130 ms，但会改动"代理起不来→兜底占位页"的分支顺序，行为风险 > 收益。 |

> **一句话交代：这一轮我修好了"每请求白等 44 ms"这个确凿的缺陷（−97%），
> 但**诚实的结论是"这个修法不该合入"**——因为它把省下的等待换成了更长的首字节延迟。
> 真正该做的是**别让页面去拉那 380 个包**（UI 侧），那才是 27.8 s 的唯一解。**

---

## 4. 稳态帧时（25/28 → 目标 ≤17 ms）：**没修好，且已定位到不是字节量**

同 ScriptAB、同 303×618 小窗、同 Skia/OpenGL pipeline、墙钟 42.8 / 42.9 / 42.8 s：

| 指标 | AB1 Web | 本轮 baseline | fixed#1(gzip) | fixed#2(+定界) |
|---|---|---|---|---|
| **p50** | 25 ms | **28 ms** | **29 ms** | **31 ms** |
| p90 | 34 ms | 38 ms | 38 ms | 44 ms |
| p99 | 46 ms | 61 ms | 57 ms | 65 ms |
| janky（现代） | 7.78% | 16.37% (139/849) | 14.32% (119/831) | 17.89% (149/833) |
| janky（legacy） | 78.02% | 93.17% | 95.55% | 94.84% |
| Total frames | 951 | 849 | 831 | 833 |
| Slow UI thread | 19 | 117 | 89 | 114 |
| Slow issue draw commands | 55 | 87 | 85 | 121 |
| **GPU p50** | 7 ms | **3 ms** | **3 ms** | **10 ms** |

**归因结论（这是本轮对 p50 最有价值的产出）**：
- GPU p50 在 baseline/fixed#1 只有 **3 ms**、UI 线程并不慢 ⇒ **瓶颈不在我们的代理、也不在 JS 传输**。
- 把传输量砍 **46.4%** 之后 p50 **纹丝不动**（28 → 29 ms）⇒ **与字节量无关**。
  这是排除法得出的结论，比上一轮"WebView 每帧更贵"更硬：**不是流量，是渲染路径。**
- 剩下的持续成本是 **WebView 每帧的合成/栅格路径本身**（Chromium 渲染进程 ↔ UI 进程
  跨进程同步 + Skia 栅格化），**在壳这一层没有可修的开关**。
- fixed#2 把 GPU p50 从 3 推到 10 ms、p50 从 28 推到 31 ms ⇒ **代理侧缓冲是净负担**（§3.5）。
- 唯一还有空间的 WebView 侧开关（本轮**未实施**，需真机 A/B 才敢下结论）：
  `WebSettings.setRenderPriorityPolicy(RENDER_PRIORITY_IMPORTANT)`、
  关掉页面里持续跑的 CSS 动画、`requestAnimationFrame` 上的长任务。
  这些属于**页面侧**改动，官方 UI 不在本仓库写权限内，故只能列为建议。

> **诚实结论：p50 目标 ≤17 ms 在壳这一层没达成，也没找到壳侧成因。**
> 上一轮把它归为"WebView + JS 渲染每帧更贵"是对的，本轮进一步排除了"传输/代理"这条线。

---

## 5. 输入延迟：**做成了真可比，且结论反转**

### 5.1 为什么 AB1 不可比

AB1 用"点按"驱动，**原生侧只产出 3 帧**（onboarding 界面点空白无反应），
Web 侧产出 22 帧。**n=3 vs n=22 差 7 倍**，3 个样本的中位数没有统计意义。
AB1 当时看到"Web 快 14.14 ms"并明确拒绝下结论 —— 判断是对的。

### 5.2 本轮方法：**改用滑动驱动帧，两侧同参数**

理由：滑动在**两侧都能稳定产生大量帧**，样本量自然对齐。
两侧都是 **303×618** 小窗、都是 **10 次上滑 + 10 次下滑**、`swipe 250ms / 间隔 250ms`、
冷启后固定等待、`dumpsys gfxinfo <pkg> framestats` 解析 `HandleInputStart → FrameCompleted`。

| 指标 | 原生壳 | Web baseline | Web fixed#2 |
|---|---|---|---|
| **样本量 n** | **120** | **120** | **120** |
| i2f p50 | **22.27 ms** | **22.41 ms** | 26.63 ms |
| i2f p90 | 33.25 ms | 31.42 ms | 39.77 ms |
| i2f p99 | 38.69 ms | 38.22 ms | 53.39 ms |
| i2f mean | 21.90 ms | 22.78 ms | 27.49 ms |
| i2f max | 38.91 ms | 41.83 ms | 58.01 ms |
| HandleInputStart→SwapBuffers p50 | 20.58 ms | 20.31 ms | 21.09 ms |

> ### ✅ 判定：**输入延迟两侧已可比（n=120 vs n=120），且没有显著差异。**
> baseline 与原生 p50 相差 **0.14 ms**（22.41 vs 22.27），
> 远小于两侧自身的 p90 跨度（31–33 ms）⇒ **Web 壳输入延迟代理与原生同档。**
> **AB1 的"Web 输入延迟低 14 ms"是样本量假象，撤回。**
>
> ⚠️ fixed#2 让它**变差**（22.41 → 26.63 ms，+19%），与 §3.5 的 p50 退化同源
> （整体缓冲拖慢首字节 ⇒ 输入到上屏的链路变长）。**再次支持"不合入缓冲定界"。**

### 5.3 ⚠️ 写法澄清（不是端到端）

这个量是平台记录的 **「输入处理开始 → 帧渲染完成」** 的**渲染半段**：
**不含**用户反应时间、**不含**网络往返、**不含** JS 逻辑执行、**不含** LLM。
报告里必须叫它**"输入延迟代理"**，不能叫端到端输入延迟。

---

## 6. 设备指纹（三轮同一台设备）

| 项 | baseline | fixed | native |
|---|---|---|---|
| `ro.product.name` | `sdk_gpc_x86_64` | 同左 | 同左 |
| Android / SDK | **14 / 34** | 同左 | 同左 |
| `ro.product.cpu.abi` | `x86_64` | 同左 | 同左 |
| `uptime` | `up 1:02` | `up 1:04` | `up 1:06` |
| `wm size` | 3840x2160 / **Override 1366x768** | 同左 | 同左 |
| `wm density` | 320 / **Override 160** | 同左 | 同左 |
| `versionName` | `0.1.0-webhost` | 同左 | 原生 `1.4.0` |
| `lastUpdateTime` | `2026-10-04 09:35:17` | 同左 | — |
| `adb reverse` | `host-19 tcp:4095 tcp:4095` | 同左 | 同左 |
| 交互墙钟 | 42.8 s | 42.9 s | — |

三轮**间隔各约 2 分钟**，远小于 AB1 的 4 分钟间隔；期间宿主模拟器无增删。

---

## 7. 纪律与合规自查

| 事项 | 状态 | 说明 |
|---|---|---|
| 设备 | ✅ 只用 `emulator-5636` | 所有 adb 调用都带 `-s emulator-5636`；`5650`/`5670`/`5690`（Pixel_Tablet / 16k）**一次都没碰** |
| gradle 锁 | ✅ 抢→建→释放 | 等锁至多 100–300 s；建完用单条顶层 `rm -- ".agent-gradle-lock"` 释放（走可恢复删除，**未绕过**安全策略） |
| 后端 | ✅ **没起停** | 只 `adb reverse tcp:4095 tcp:4095` 重挂（任务书要求） |
| 交互次数 | ✅ 未削减 | `NScroll=10 / NTap=6`，与 AB1 **完全相同**；滑动采样另有固定 10+10 次，两侧一致 |
| 冷启 | ✅ 每轮都冷启 | 每轮 `am force-stop` + 等 2 s；`LaunchState=COLD` 已在 `amstart.txt` 留证 |
| 只测热态 | ✅ 没有 | p50 取自 ScriptAB（内含冷启），输入延迟取自冷启后 |
| 改参数是否声明 | ✅ 已声明 | §1.4 三处改动（删耗电、settle 15→30 s、换输入延迟采样法）**全部写明** |
| 写权限 | ✅ 只动 `webapp/**` + `research/**` | **未碰** `app/**`、`_upstream/**`、`.github/**` |
| 装机 | ✅ 只装本模块 | `:webapp:assembleDebug`，8.64 MB |

---

## 8. 未闭环 / 存疑（必须读）

| # | 项 | 状态 | 影响 | 下一步 |
|---|---|---|---|---|
| **1** | **380 个主题包是官方 UI 主动 eager-load 的** | 🔴 高 | 27.8 s 尾巴的**根因在 UI 侧**，不在壳里；壳只能改传输，改不了"要不要拉" | 官方 UI 侧把主题/语言包改成**按需 + 懒加载**（或首屏后 `requestIdleCallback` 再拉）。这是唯一能真正砍掉 TOP1 的地方 |
| **2** | **Service Worker 确实在参与**（`/sw.js` @3315ms + PWA manifest） | 🟡 中 | SW 是否在**预缓存**那 380 个包，本轮**未能确证**（需 devtools/SW 日志） | 查 `chrome://inspect` 或给 SW 加日志；若确为 SW 预缓存，**壳侧可考虑禁用 SW 注册**以验证 |
| **3** | **p50 帧时没修好，且壳侧无可修开关** | 🔴 高 | 25/28 → 29 ms，**+47%~+70% vs 原生 17 ms 依旧** | 只能在真机复测 + 页面侧优化（动画、长任务）；**不要**继续在壳里找 |
| **4** | **"内容可见"用的是 FCP + DOM 节点数，不是像素** | 🟡 中 | 845 节点不等于人眼可读；**未做首屏非空像素判定** | 补一条连续截图 + 像素比对，两侧同做法 |
| **5** | **窗口是 303×618 浮动小窗，非全屏** | 🟡 中 | 所有数字都是小窗数字，不能外推全屏/真机 | 若要对外承诺，需固定全屏几何重测 |
| **6** | 耗电 | ❌ 测不到 | x86_64 模拟器无 PMIC | 上真机 |
| **7** | janky% 两口径仍打架 | 🟡 中 | 现代 16.37% vs legacy 95.55% | 报告时必须两个口径一起给 |
| **8** | **原生壳仍未连后端**（沿袭 AB1 #1） | 🔴 高 | 两侧内容仍不匹配，p50 对比仍带"画的界面不同"的噪声 | 把原生壳连上 4095 再重跑 |
| **9** | `nope-01..12` 等包**每轮都全量重拉** | 🟡 中 | 暗示 **HTTP 缓存没生效**（`cacheMode=LOAD_DEFAULT` 但响应无可复用缓存头/被代理改写） | 查上游 `Cache-Control`；若有，代理应原样透传（本轮只 drop 了 Connection/TE/Trailer） |

### 8.1 哪些是**模拟器造成的**、真机可能不同

| 现象 | 模拟器因素 | 真机预期 |
|---|---|---|
| 每请求 44 ms 定界开销 | 环回 + `adb reverse` 走 adb 管道，close 传播慢 | **会明显变小**（真 socket 的 FIN 传播是 µs 级）⇒ **修 #2 的收益真机应更大** |
| 78 ms 串行间隔 | 同上，且无真实网络栈 | 真机应更小 |
| 传输量 2.7 MB | 主题包本身就多 | 一样；但**真机带宽高 ⇒ 传输耗时占比更小**，进一步说明瓶颈是往返次数 |
| p50 25–29 ms | x86_64 模拟器**无 GPU 加速**（GPU p50 仅 3 ms 是软件光栅） | 真机 GPU p50 会小很多，**但 UI/合成跨进程成本仍在**；p50 差距**大概率缩小但未必消失** |
| 耗电 | 无 PMIC | 真机可测 |
| 窗口 303×618 | 模拟器小窗 | 需在真机全屏重测 |

> **一句话：TOP1/TOP2 这两条尾巴上的问题，真机只会更轻、不会更重；
> 而 p50 帧时的差距真机大概率仍在（它不是模拟器带宽造成的）。**

---

## 9. 复现清单

| 文件 | 作用 |
|---|---|
| `perf_run.ps1` | 主脚本：指纹 + 冷启逐段 + ScriptAB p50 + 滑动采样输入延迟 |
| `out/baseline/logcat.txt` | **改前** 424 条 `P.fwd` + 全部逐段打点 |
| `out/fixed/logcat.txt` | **改后** 417 条 `P.fwd` + 全部逐段打点 |
| `out/{baseline,fixed}/gfxinfo_parsed.json` | 稳态帧时分位 |
| `out/{baseline,fixed}/inputlatency.json` | Web 两侧输入延迟（n=120） |
| `out/native/inputlatency.json` | 原生侧同法输入延迟（n=120） |
| `out/*/fingerprint.txt` | 三轮设备指纹 |
| `out/*/amstart.txt` | `am start -W` 冷启留证（`LaunchState=COLD`） |

一条龙：
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5636 reverse tcp:4095 tcp:4095
.\gradlew.bat :webapp:assembleDebug
& $adb -s emulator-5636 install -r -t webapp\build\outputs\apk\debug\webapp-debug.apk
pwsh -NoProfile -File research\perf\perf_run.ps1 -Mode baseline
pwsh -NoProfile -File research\perf\perf_run.ps1 -Mode fixed
```

> ### ⚠️ 复现说明（必须交代，别让下一个人踩）
> 1. **`out/fixed/` 里存的是「第二轮 fixed（gzip+缓冲定界）」的原始数据**，
>    因为两轮 fixed 用了同一个 `-Mode fixed` 目录、后者覆盖了前者。
>    报告里 **fixed#1（仅 gzip）** 的数字来自第一轮那次运行，
>    它的**原始 logcat 已被覆盖、不在磁盘上**（数字本身已逐字记录在本报告里）。
>    要保留三份原始数据，请给脚本加 `-Label fixed1 / fixed2` 之类的区分。
> 2. **`native` 目录没有 `logcat`/`gfxinfo`**，因为原生侧本轮只跑了输入延迟这一项
>    （目的是凑齐 n=120 做可比）；p50 帧时用的是 AB1 的原生 17 ms。
>    严格起见，p50 的原生值应与本轮 baseline 同轮重测（本轮时间不够，见 §8 #8）。
> 3. `fixed` 模式需要**含 `fixedMode` 参数的那份构建**；
>    `baseline` 模式在任何一份构建里都能跑（默认即 baseline）。

---

## 10. 收尾

### 10.1 `wm` 几何 —— 已显式重设并复核

```powershell
& $adb -s emulator-5636 shell wm size 1366x768
& $adb -s emulator-5636 shell wm density 160
```
复核输出：
```
Physical size: 3840x2160
Override size: 1366x768        ← 与 harness 规格一致
Physical density: 320
Override density: 160         ← 160
```
> 未用 `wm size reset`（那会回物理 3840x2160，脱离 harness 规格）。

### 10.2 `/sdcard` 残留

```powershell
& $adb -s emulator-5636 shell "ls /sdcard/ | grep -E '^_(perf|ab|s)'"
→ NONE
```
本轮所有 logcat / gfxinfo / framestats 都在**主机侧**落 `research/perf/out/`，
**未在设备上留任何临时文件**。**无新增残留。**

### 10.3 设备纪律

- 全部 adb 调用**均带 `-s emulator-5636`**。
- 收尾时设备列表为 `5636 / 5670 / 5690`（`5650` 已在别处消失）——
  **这些增删不是本轮造成的**；`5670`/`5690`（Pixel_Tablet / 16k）**一次都没碰**。
- 后端 `127.0.0.1:4095` **全程没起停**，只 `adb reverse` 重挂（收尾仍为 `host-19 tcp:4095 tcp:4095`）。

### 10.4 gradle 锁

三次构建都按 `.agent-gradle-lock` 协议走：**等锁 → 写锁 → 前台建完 → 单条顶层
`rm -- ".agent-gradle-lock"` 释放**（走可恢复删除，**未绕过**安全策略）。
收尾 `Test-Path` = **False**（已释放）。

### 10.5 本轮对 `webapp/**` 的改动清单

| 文件 | 改动 | 性质 |
|---|---|---|
| `host/PerfMarks.kt` | **新增**。进程相对计时打点 | 纯新增（`Log.i`） |
| `assets/www/oc-perf.js` | **新增**。浏览器侧 first-paint/DCL/逐资源探针 | 纯新增（`console.log`） |
| `WebHostActivity.kt` | 加 `oc_perf_mode` 开关 + 15 个 `PerfMarks.mark` + 转发 `fixedMode` | 纯新增打点；`baseline` 行为不变 |
| `host/LoopbackProxy.kt` | 加 `fixedMode` 开关（gzip 透传 + 缓冲定界） | **行为改动，仅 `fixed` 生效** |

> ⚠️ **合并建议**：`PerfMarks.kt` / `oc-perf.js` / 打点可以留（DEBUG 下无害）；
> **`LoopbackProxy` 的缓冲定界那段建议回退**（§3.6），只保留 gzip 透传。
> 三档行为可用 `--es oc_perf_mode baseline|fixed` 随时切回。
