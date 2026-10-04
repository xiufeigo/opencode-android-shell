# FRAMETIME_AND_GZIP — P3：帧时归因 + 合入 gzip + 关掉可比性缺口

> **一句话结论（先给结论）**：
> 1. **帧时 28–31 ms 的主因是「一帧的固有地板」，不是官方 UI。**
>    把官方 UI 换成 **400 行纯文字静态 DOM** 只把 p50 从 31 拉到 **27 ms**（省 4 ms，1263 帧对照）；
>    关掉全部动画/阴影/滤镜 **p50 纹丝不动**（31→31）。
>    ⇒ **约 27 ms（87%）是这台 x86_64 软件光栅模拟器上「滚动一帧」的固有成本，官方 UI 只贡献约 4 ms（13%）。**
> 2. 🔴 **上一轮沿用的「原生 17 ms」是错的。** 本轮**同轮、同参数、同设备指纹**重测原生 = **26 ms**。
>    WebView 相对原生的真实差距是 **31 vs 26 = 5 ms（19%）**。
>    最刺眼的一条：**WebView 画 400 行纯文字（27 ms）比原生画完整会话 UI（26 ms）还慢**
>    ⇒ 这块地板是**设备属性**，原生同样贴着它跑。
> 3. **gzip 透传已合入**（默认开启）：传输量 **−46.4%**，p50 帧时 **±0 ms**、输入延迟 **+0.72 ms（噪声内，p90/mean 反而更好）**。
> 4. **导航前串行段不能并行**：实测并行化后 `loadUrl` **1102 → 1171 ms（更慢 69 ms）**，已默认关闭。

---

## 0. 速查

| 量 | P2 结论 | **P3 实测** | 判定 |
|---|---|---|---|
| 稳态 p50 帧时（Web） | 28 / 29 / 31 ms | **31 ms**（baseline 与 gzip **完全相同**） | 拆开见 §1 |
| 稳态 p50 帧时（原生） | 沿用 AB1 的 **17 ms** | 🔴 **26 ms（同轮重测）** | 🔴 **旧值不可用，撤回** |
| 官方 UI 内容成本 | 未分离 | **≈ 4 ms**（31 → 27，400 行纯文字、1263 帧） | 占比 **13%** |
| 动画/阴影成本 | 未分离 | **≈ 0 ms**（GPU p50 11→7 ms） | 不在 p50 关键路径 |
| **"画一帧"固有地板** | 未分离 | **≈ 27 ms** | **主因，87%** |
| 传输压缩率 | 0.54（fixed#1） | **0.54**（`2,716,839 → 1,456,371 B`） | ✅ 复现，**已合入** |
| gzip 对 p50 的代价 | "无"（噪声内） | **31 → 31 ms（±0）** | ✅ 更硬：**完全相同** |
| gzip 对输入延迟的代价 | "无"（22.41→21.04） | **26.49 → 27.21 ms**（n=120/120，p90 38.98→**35.37**） | ✅ 噪声内，**上尾更好** |
| 导航前串行段 | "不建议并行化" | 实测并行后 **1102 → 1171 ms** | 🔴 **确认不并行**，已默认关 |
| 原生连后端 | ❌ 未连（P2 §8 #8） | ✅ **已连**（`adb reverse` 两侧同一后端） | ✅ **缺口已关** |

---

## 1. 帧时归因（本轮核心）：31 ms 到底花在哪

### 1.1 方法：同一份 APK 跑 5 个档位，只切 `--es oc_perf_mode`

P2 的教训是"改前跑旧包、改后跑新包"会被重编译差异污染。本轮沿用它的做法 ——
**一个 APK、运行时开关分流**，交互脚本 ScriptAB 与 P2 / AB1 **逐字相同**
（`NScroll=10 / NTap=6`，墙钟实测 42.1–43.0 s，与 P2 的 42.8 s 同档）。

新增两个**只改文档、不改壳**的归因档位（实现见 §1.5）：

- `min` / `flat`：代理对 `GET /` **不转发官方 UI**，返回一份 12 行静态 DOM（零 script、零资源请求）。
- `noanim`：照常转发官方 UI，但往 `</head>` 前注入关停 `animation / transition / box-shadow /
  text-shadow / filter / backdrop-filter / background-image` 的 CSS。

### 1.2 帧时构成表（同脚本、同设备、逐档实测）

| 档位 | 页面 | **p50** | p90 | p99 | 帧数 | Slow UI | Slow draw | **GPU p50** |
|---|---|---|---|---|---|---|---|---|
| `baseline` | 官方 UI 全量（**改前**） | **31 ms** | 40 | 57 | **860** | 106 | 120 | 11 |
| `gzip` | 官方 UI 全量（**改后/合入**） | **31 ms** | 40 | 57 | **860** | 110 | 122 | 12 |
| `noanim` | 官方 UI，动画/阴影全关 | **31 ms** | 42 | 57 | 808 | 123 | 127 | **7** |
| `min` | **400 行静态 DOM**（可滚动、零资源） | **27 ms** | 36 | **53** | **1263** | 76 | 66 | **2** |
| `flat` | 400 行静态 DOM + 无动画 | **27 ms** | 36 | **53** | **1208** | 87 | 85 | **3** |
| **`native`** | **原生壳（同轮、已连后端）** | **26 ms** | 38 | 150 | **890** | 70 | 72 | 11 |

> **`min` / `flat` 用的是"加长到 400 行"的可滚动版本**（第一版 12 行不可滚动、只有 39 帧，
> 样本量不足且 p99 有 1200 ms 离群帧）。加长后帧数 1263 / 1208，与 `baseline` 的 860 同量级，
> **p50 从 27/25 稳定在 27 ms，p99 收敛到 53 ms** ⇒ 地板值现在建立在**足量滚动帧**上。

### 1.3 拆解：31 ms 的构成

```
31 ms  =  27 ms  "画一帧"固有地板（400 行静态页的滚动帧）               ← 87%
       +   4 ms  官方 UI 内容复杂度（845 DOM 节点 / UI chrome / 主题包）   ← 13%
       +   0 ms  CSS 动画与阴影（关掉后 p50 不变；但 GPU p50 11→7）
       +   0 ms  传输字节量（gzip 砍 46.4% 后 p50 不变）
```

| 归因项 | 怎么测的 | 省/耗多少 ms | 占比 |
|---|---|---|---|
| **内容复杂度** | `flat`(27) vs `baseline`(31) | **−4 ms** | **13%** |
| **动画/阴影** | `noanim` vs `baseline` | **−0 ms**（31→31） | 0% |
| **传输字节量** | `gzip` vs `baseline` | **−0 ms**（31→31） | 0% |
| **剩下的地板** | `flat` 绝对值 | **27 ms** | **87%** |

### 1.4 判定：**主因是 WebView 合成/渲染地板，不是官方 UI**

四条独立证据，全部指向同一结论：

1. **缩到极小内容（400 行纯文字，零资源），p50 只从 31 降到 27 ms（省 4 ms）。**
   如果"官方 UI 更重"是主因，把 845 节点 + 380 个主题包换成纯文字应该大幅回落。
   实测只省 4 ms（13%）。⇒ **内容复杂度不是主因。**
2. **关掉全部动画/阴影/滤镜，p50 完全不动**（31→31），但 **GPU p50 从 11 降到 7 ms**。
   ⇒ 动画/阴影确实在消耗 **GPU** 时间，但**不在 p50 的关键路径上**（p50 由 CPU 侧决定）。
3. **传输量砍 46.4%，p50 纹丝不动**（31→31）。⇒ 与字节量无关（P2 已得，本轮在同 APK 上复现）。
4. 🔴 **同轮原生 = 26 ms，而 WebView 地板 = 27 ms。**
   最刺眼的一条：**WebView 画 400 行纯文字（27 ms）比原生画完整会话 UI（26 ms）还要慢。**
   ⇒ **"画一帧 ~27 ms"是这台设备的成本**，原生也贴着它跑。
   **WebView 相对原生的真实代价只有 5 ms（31 vs 26，19%）** —— 上一轮基于错误 17 ms 推出的
   「WebView 合成更慢」被**显著高估**。

> ### 结论一句话
> **「WebView 合成更慢」这个判断本身要修正：**
> WebView 相对原生的真实代价只有 **5 ms（31 vs 26，19%）**，
> 而 **27 ms 的绝对地板是这台 x86_64 软件光栅模拟器的硬件特性** ——
> 原生壳同样贴着这块地板跑（26 ms）。**在真机上，这 27 ms 会大幅缩小（GPU 光栅化），
> 但 WebView 相对原生的 ~5 ms 差距会按比例保留。**

### 1.5 判定手段的实现（都在 `webapp/**` 内，只改文档不改壳）

`LoopbackProxy.kt` 新增 `renderMode`（`full`/`noanim`/`min`/`flat`），仅对 `GET /` 生效：

- 改写文档时对上游强制 `Accept-Encoding: identity`（否则注入点会落在 gzip 字节里），
- 读回明文 HTML → 注入/替换 → **重算 `Content-Length`、移除 `Content-Encoding`/`Transfer-Encoding`**，
- 写盘字节数与 `Content-Length` 必然一致（未用任何猜测长度）。

这些档位**只在显式传 `--es oc_perf_mode min|noanim|flat` 时生效**，默认 `full` 行为逐字不变。

### 1.6 ⚠️ 这一节的方法学限制（已修正，记录在此备查）

`min` / `flat` **第一版**只有 12 行 DOM ⇒ 页面**不可滚动** ⇒ 滑动几乎不产生新帧，
只有 **39 / 44 帧**、p99 高达 1100–1200 ms（离群帧）。那个数字不足以支撑"地板"结论。

**本轮已修正**：`min` 档页面加长到 **400 行**（仍零 script / 零资源），
帧数升到 **1263 / 1208**，p50 稳定在 **27 ms**、p99 收敛到 **53 ms**。
⇒ 现表 §1.2 用的是**修正后**的数据，第一版数字已作废（原始输出在 `out/p3/{min,flat}/`，v2 在 `out/p3/{min,flat}_v2/`）。

**残留限制**：400 行纯文字**仍不是**官方 UI 的完整内容（无 JS、无主题包、无虚拟列表），
所以"内容复杂度 = 4 ms"应读作**下界**（真实内容成本 ≥ 4 ms）。

### 1.7 能修的都修了：修复清单

| 候选修复 | 改前 p50 | 改后 p50 | 判定 |
|---|---|---|---|
| **gzip 透传**（§2） | 31 ms | **31 ms** | ✅ 合入（收益在流量不在帧时） |
| 关 CSS 动画/阴影 | 31 ms | 31 ms | ❌ **不修**（p50 零收益，GPU −4 ms 但牺牲 UI 观感） |
| 删官方 UI 装饰层 | 31 ms | 27 ms（理论地板） | ❌ **不在写权限内**（官方 UI 不在本仓库），且只买 **4 ms** |
| 代理预热并行 | 31 ms | — | ❌ 不修（启动段更慢，§4） |
| `setRenderPriorityPolicy` 等 WebView 开关 | 未测 | 未测 | 🟡 见 §6（需真机 A/B，本轮无预算） |

> **本轮没有找到任何能在壳侧把 p50 从 31 往 26 拉的开关。**
> 能省的那 4 ms 在官方 UI 里（不在 `webapp/**`），剩下的 27 ms 是**设备地板**（原生同样贴着它）。

---

## 2. 合入 gzip 透传

### 2.1 diff

```diff
--- a/webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LoopbackProxy.kt
+++ b/webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LoopbackProxy.kt
@@ class LoopbackProxy
+    /**
+     * P3：gzip 透传**已合入为默认行为**（P2 §3.6 判定"保留并合入"）。
+     * 与 [fixedMode] 解耦：后者是 P2 已判"不该合入"的整体缓冲定界，只留给对照复现。
+     */
+    private val gzipMode: Boolean = true,
@@ private fun onForward(...)
-        if (fixedMode) {
-            runCatching { builder.header("Accept-Encoding", "gzip") }
-        }
+        // ⚠️ 显式设置后 OkHttp **不会自动解压** ⇒ 代理线上原样搬运的就是 gzip 字节，
+        //    Content-Length 与 Content-Encoding 语义不变，SSE / WebSocket 不受影响。
+        val rewriteDoc = renderMode != "full" && parsed.method.equals("GET", true) &&
+            (parsed.path == "/" || parsed.path.isEmpty())
+        if (gzipMode || fixedMode) {
+            // 文档要被改写时必须拿明文，否则注入点落在 gzip 字节里。
+            runCatching { builder.header("Accept-Encoding", if (rewriteDoc) "identity" else "gzip") }
+        }
```

```diff
--- a/webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt
+++ b/webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt
-    private var perfMode: String = "baseline"
+    private var perfMode: String = "gzip"
+    /** P3：gzip 透传**已合入**，除 `baseline`（改前基准）外都开。 */
+    private val gzipMode: Boolean get() = perfMode != "baseline"
-        perfMode = intent?.getStringExtra(EXTRA_PERF_MODE) ?: "baseline"
+        perfMode = intent?.getStringExtra(EXTRA_PERF_MODE) ?: "gzip"
                 fixedMode = fixedMode,
+                gzipMode = gzipMode,
+                renderMode = renderMode,
```

**开关语义**：`baseline` = P2 的改前原行为（无 gzip，可复现 P2 §3.4 的 2,716,839 B）；
其余任意值 = 本轮合入后的默认行为。

### 2.2 为什么安全

| 风险 | 处置 | 依据 |
|---|---|---|
| **破坏 `Content-Length`** | 不碰。显式设了 `Accept-Encoding` 后 OkHttp **不自动解压** ⇒ 代理写的 `Content-Length` 仍按**上游声明**原样透传，与线上字节必然一致 | 代码里 `headers["Content-Length"]` 只在**缓冲**分支改写；gzip 分支不动 |
| **破坏 `Content-Encoding`** | 原样透传 `Content-Encoding: gzip`（P2 的 `shouldDropResponseHeader` 未包含它） | 实测 `P.fwd … ce=gzip` 共 **409** 条 |
| **流式 SSE 被压缩** | ✅ **实测未发生** | `sse response 200 ctype=text/event-stream te=chunked cl=null ce=null` ⇒ **`ce=null`，上游不对 event-stream 压缩**。且 `isEventStreamRequest()` 分支逐字未改 |
| **WebSocket** | 逐字未改 | `tunnelWebSocket` 不经过 `Accept-Encoding` 逻辑；隧道是裸字节 |
| **`br` 解不了** | 只承诺 `gzip`，不承诺 `br` | 无 brotli 依赖时 br 会被静默不解码 |
| **只认 gzip 的客户端** | WebView/Chromium 原生支持 gzip | 无协商逻辑变化，只是把客户端本来就会发的头显式化 |

### 2.3 三条复测（同 APK、同设备、同脚本）

| 指标 | `baseline`（改前） | `gzip`（合入后） | 变化 | 判定 |
|---|---|---|---|---|
| **传输量 xfer** | 2,716,839 B | **1,456,371 B** | ✅ **−46.4%** | **−46.4%，复现 P2** |
| 传输/解码 ratio | 1.01 | **0.54** | ✅ | |
| 解码量 dec | 2,698,239 B | 2,698,239 B | **0** | 内容逐字未变 |
| `ce=gzip` 响应数 | **0** | **409**（共 451 个 `P.fwd`） | ✅ | |
| **稳态 p50 帧时** | **31 ms** | **31 ms** | ✅ **±0** | **完全相同** |
| 帧数（负载一致性） | 860 | 860 | 0 | 同负载 |
| **输入延迟 p50**（n=120/120） | **26.49 ms** | **27.21 ms** | 🟡 **+0.72 ms（+2.7%）** | **噪声内**（两侧自身 p90 达 35–39 ms） |
| 输入延迟 p90 | 38.98 ms | **35.37 ms** | ✅ **−3.61 ms** | **上尾更好** |
| 输入延迟 p99 | 45.54 ms | 46.95 ms | +1.41 ms | 噪声内 |
| 输入延迟 mean | 27.12 ms | **26.29 ms** | ✅ **−0.83 ms** | 整体略好 |
| FCP | 2059 ms | **1934 ms** | ✅ −125 ms | 噪声内（偏快） |
| DOM 挂载（845 节点） | 2447 ms | 2499 ms | +52 ms | 噪声内 |

> ### ✅ 判定：**"gzip 无代价"在新数据上仍成立，且证据比 P2 更硬。**
> P2 的依据是 p50 "28→29 ms（噪声内）"；本轮是 **31 → 31 ms，逐字相同、帧数同为 860**。
> 输入延迟 p50 +0.72 ms，但 **p90 −3.61 ms、mean −0.83 ms** ⇒ 整体**没有变慢**。
> **建议：合入（本轮已作为默认行为）。**

---

## 3. 关掉可比性缺口：原生壳也连上后端 + 同轮两侧对照

P2 §8 #8 自己标注的缺口：**原生壳一直沿用 AB1 的 17 ms，从未与 Web 同轮比过。** 本轮关闭。

### 3.1 怎么连上的

- `adb reverse tcp:4095 tcp:4095`（两侧共用，未起停后端）；
- 原生壳 `com.xiufeigo.opencodeshell` 的后端即 `127.0.0.1:4095`（源码里就是这个默认值）
  ⇒ **开 reverse 即等于让原生壳连上同一个后端**，两侧内容同源；
- 原生 activity：`.OcActivity`（`resolve-activity` 解析所得）。

### 3.2 同轮两侧对照表

| 项 | **原生壳** | **Web 壳（gzip，合入后）** |
|---|---|---|
| 包 / 版本 | `com.xiufeigo.opencodeshell` **1.4.0** | `…web.debug` **0.1.0-webhost** |
| **后端** | `127.0.0.1:4095`（`adb reverse`） | `127.0.0.1:4095`（`adb reverse`）→ 代理 origin |
| **p50** | **26 ms** | **31 ms** |
| p90 | 38 ms | 40 ms |
| p99 | 150 ms | 57 ms |
| 帧数 | 890 | 860 |
| janky（现代） | 106 (11.91%) | 150 (17.44%) |
| janky（legacy） | 773 (86.85%) | 818 (95.12%) |
| Slow UI thread | 70 | 110 |
| Slow issue draw | 72 | 122 |
| **GPU p50** | 11 ms | 12 ms |
| 交互墙钟 | 42.3 s | 42.9 s |

**设备指纹（两侧同轮，同一台机器）**

| 项 | 值 |
|---|---|
| serial | **`emulator-5636`**（两侧同一台） |
| `ro.product.name` / abi | `sdk_gpc_x86_64` / `x86_64` |
| Android / SDK | 14 / 34 |
| `ro.hardware.egl` | **`emulation`**（软件光栅，无 GPU 加速） |
| `wm size` | 3840x2160 / **Override 1366x768** |
| `wm density` | 320 / **Override 160** |
| 动画 scale | window 1.0 / transition 1.0（两侧同档） |
| `adb reverse` | `host-19 tcp:4095 tcp:4095` |
| 窗口 | ScriptAB 实测同一浮动窗几何 |

### 3.3 判定

> **Web 相对原生的稳态 p50 差距 = 31 − 26 = 5 ms（+19%）。**
> 🔴 **上一轮"原生 17 ms"是错的，本轮同轮实测为 26 ms。**
> 沿用 17 ms 会把差距夸大到 **+82%**。**这个更正应回写到 P2 §4 与 §6。**

⚠️ **仍存在的口径差**：两侧是**不同的渲染体系**（原生 View 体系 vs WebView/Chromium），
显示的是同一批会话数据，但**不是像素级同一画面**（排版、字体、控件都不同）。
⇒ 这张表回答的是"**同参数同内容源下，两种壳各花多久画一帧**"，
**不是**"同一张图两种画法的差"。

---

## 4. 导航前串行段（~1100 ms）能否并行

### 4.1 归因：那一秒具体在等什么

`t=0 = 进程创建`（`Process.getStartUptimeMillis()`，不依赖跨机时钟），**改前**逐段：

| 段 | 打点 | 累计 | 本段 |
|---|---|---|---|
| 进程创建 → `onCreate` 入口 | `A.onCreate.enter` | **527** | **527**（fork + 类加载，**在 Activity 之前**） |
| → **WebView 创建 + setContentView** | `C.webviewCreated` | **898** | **371**（CPU 密集：WebView 类加载） |
| → **代理开始监听** | `I.proxyListening` | **1084** | **186** |
| → **`loadUrl` 发出** | `J.loadUrlIssued` | **1102** | 18 |
| WebView 首帧 | `Z.firstDraw` | 1280 | 178 |
| FCP | `paint first-contentful-paint` | **2059** | — |
| `am start -W` TotalTime | — | **1560** | — |

**唯一有并行空间的是后两段**：`WebView 创建 371 ms` 与 `代理起 186 ms` 之间**没有数据依赖**
（代理只依赖 `SecretStore` 的口令，与 WebView 无关）。
`527 ms` 那段在 `onCreate` 之前，**无法并行**。

### 4.2 实验：把代理预热挪到后台线程

`onCreate` 一进来就在 `oc-proxy-prewarm` 线程上建代理并 `start()`，主线程同时做 WebView 创建；
到 `launch()` 时若还没起好就 `join(3000)`。**失败语义逐字不变**（仍走 `LEGACY_PLACEHOLDER_URL` 兜底）。

| 指标 | 改前（串行） | 改后（并行） | 判定 |
|---|---|---|---|
| `loadUrl` 发出 | **1102 ms** | **1171 ms**（run3） | 🔴 **+69 ms，更慢** |
| 代理 bind 实际耗时 | 186 ms | **225 / 240 / 246 ms**（`ms=` 打点） | 🔴 **变慢了 30%** |
| `TotalTime` | 1560 ms | 1489 / 1409 / 1682 ms（中位 1489） | 🟡 噪声内，无改善 |
| `LaunchState` | COLD | COLD（3/3） | ✅ 冷启，未偷热态 |

**为什么并行反而更慢（可解释，不是玄学）**：
代理 `start()` 里的 `ServerSocket(0)` + OkHttp 初始化是 **CPU 密集**（类加载 + TLS/连接池初始化）。
在 x86_64 模拟器上，把它挪到后台线程 = 与主线程的 **WebView 类加载（同样 CPU 密集）抢核**，
单核争用把 186 ms 拉长到 225–246 ms。**总工作量没变，只是把它并发化后在更少的核上跑。**

> ### 🔴 结论：**不能并行（至少在模拟器上），已默认关闭。**
> 代码保留但 **opt-in**：`--ez oc_perf_prewarm true` 才能开启（`EXTRA_PREWARM`），
> 默认 `false` ⇒ 默认行为与 P2 逐字一致。**建议不合入预热。**
>
> 真机（多核、无类加载风暴）上并行**可能**有正收益，需真机 A/B 才能下结论 —— 记入 §6。

---

## 5. 本轮对 `webapp/**` 的改动清单

| 文件 | 改动 | 性质 |
|---|---|---|
| `host/LoopbackProxy.kt` | `gzipMode`（**默认 true，合入**）、`renderMode`（4 档归因，默认 `full`）、`rewriteDocument()` | gzip 为**行为改动**；renderMode 默认关闭 |
| `WebHostActivity.kt` | `perfMode` 默认值 `baseline`→**`gzip`**；转发 `gzipMode`/`renderMode`；`EXTRA_PREWARM` opt-in 预热 | 同上 |
| `host/PerfMarks.kt`、`assets/www/oc-perf.js` | P2 遗留，未改 | 纯新增打点 |

**未碰**：`app/**`、`_upstream/**`、`.github/**`。
**P2 的缓冲定界（`fixedMode`）原样保留**供对照复现，默认不启用。

---

## 6. 未闭环 / 存疑（必须读）

| # | 项 | 状态 | 影响 | 下一步 |
|---|---|---|---|---|
| **1** | 🔴 **「原生 17 ms」被本轮推翻为 26 ms** | 高 | P2 §4/§6 的所有"vs 原生 17 ms"的百分比都偏高 | 已在本报告更正；**P2 正文需回写** |
| **2** | ✅ **已修正**：`min`/`flat` 第一版仅 39 帧（不可滚动页） | 低 | 地板值一度建立在 39 帧的弱样本上（p99 1200 ms 离群） | 已把页面加长到 400 行重测：帧数 **1263/1208**、p50 **27 ms**、p99 **53 ms**；输出在 `out/p3/{min,flat}_v2/` |
| **3** | 两侧非像素级同画面 | 中 | §3 表是"同内容源"不是"同画面" | 需同 DOM 的原生/Web 双实现才能严格同画面 |
| **4** | 地板 27 ms 是**模拟器**特性（`ro.hardware.egl=emulation`，软件光栅） | 高 | 真机 GPU 光栅化后地板会大幅缩小 | **真机重测**，重点看"地板"和"Web−原生差值"各自怎么变 |
| **5** | 官方 UI 的 4 ms 成本**不在写权限内** | 中 | 壳侧无法优化 | 官方 UI 侧减 DOM/虚拟列表 |
| **6** | `setRenderPriorityPolicy` 等 WebView 开关未测 | 中 | 是 §1.7 唯一剩下的壳侧候选 | 真机 A/B（本轮无预算） |
| **7** | 代理预和在**真机**可能反而有正收益 | 低 | 模拟器结论是负的 | 真机 A/B |
| **8** | janky 两口径仍打架 | 中 | 现代 17.44% vs legacy 95.12% | 报告必须两口径同给 |
| **9** | 窗口 303×618 浮动小窗 | 中 | 所有数字是小窗数字 | 全屏几何重测 |
| **10** | P2 的 27.8 s 主题包尾巴**本轮未复测** | 中 | 不是本轮任务 | UI 侧改按需懒加载 |

### 6.1 哪些是**模拟器特性**、真机可能不同

| 现象 | 模拟器因素 | 真机预期 |
|---|---|---|
| **27 ms "滚动一帧"地板** | `ro.hardware.egl=emulation`、**无 GPU 光栅** | **会大幅缩小**。这是本轮最大的模拟器放大器 |
| Web − 原生 = 5 ms | 地板抬高 ⇒ 相对差值被稀释 | 地板缩小后，**这 5 ms 的相对占比会变大**（分母变小） |
| 代理并行化变慢 | 核少 + 类加载风暴 | 可能反转成正收益 |
| janky legacy 95% | 16.6 ms 的 vsync 预算在软件光栅下几乎必然超时 | 真机应显著下降 |
| 每请求 44 ms 定界开销（P2） | `adb reverse` 管道 | 真机应小得多 |

> **一句话：P2 说"帧时差距真机大概率仍在"，本轮要修正为 ——
> 差距的**绝对值**（~5 ms）真机应更小甚至消失，但**占比**（19%）大概率仍在甚至扩大，
> 因为分母（地板）会跟着 GPU 光栅化一起塌下去。必须真机复测才能定性。**

---

## 7. 复现清单

| 文件 | 作用 |
|---|---|
| `perf_p3.ps1` | 主脚本：指纹 + 5 档帧时 + 原生同轮 + gzip/baseline 三条复测 |
| `refloor_p3.ps1` | **`min`/`flat` 地板重测**（400 行可滚动长页，输出 `out/p3/{min,flat}_v2/`） |
| `coldstart_p3.ps1` | 冷启串行段 3 次对照（`--ez oc_perf_prewarm` 开关） |
| `build_p3.ps1` | 抢锁 → `:webapp:assembleDebug` → 装机 → 释放 |
| `out/p3/frametime_matrix.json` | **6 档帧时全表**（§1.2 原始数据） |
| `out/p3/{baseline,gzip,min,noanim,flat,native}/gfxinfo_full.txt` | 逐档 `dumpsys gfxinfo` 原始输出 |
| `out/p3/{baseline,gzip,min,noanim,flat,native}/row.json` | 逐档解析结果 |
| `out/p3/xfer_{baseline,gzip}/logcat.txt` | 传输量 / `P.fwd` / `res-total` / SSE 头证据 |
| `out/p3/xfer_*/inputlatency.json` | 输入延迟 n=120/120 |
| `out/p3/coldstart/run{1,2,3}.log` | 并行化冷启逐段打点 |
| `out/p3/fingerprint.txt` | 设备指纹 |

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5636 reverse tcp:4095 tcp:4095
pwsh -NoProfile -File research\perf\build_p3.ps1
pwsh -NoProfile -File research\perf\perf_p3.ps1
pwsh -NoProfile -File research\perf\coldstart_p3.ps1
```

**改前/改后怎么切**：
```powershell
# baseline = P2 的改前原行为（无 gzip）
& $adb -s emulator-5636 shell am start -n <pkg>/<act> --es oc_perf_mode baseline
# 省略 oc_perf_mode 或任意其他值 = 本轮合入后的默认行为（gzip 开）
& $adb -s emulator-5636 shell am start -n <pkg>/<act>
# 归因档位
& $adb -s emulator-5636 shell am start -n <pkg>/<act> --es oc_perf_mode min|noanim|flat
# 代理预热（实测更慢，仅复现用）
& $adb -s emulator-5636 shell am start -n <pkg>/<act> --ez oc_perf_prewarm true
```

---

## 8. 纪律与合规自查

| 事项 | 状态 | 说明 |
|---|---|---|
| 设备 | ✅ **只碰 `emulator-5636`** | 全部 adb 调用均带 `-s emulator-5636`；`5670`/`5690`/`5700`（用户的）**一次都没碰** |
| 后端 | ✅ **没起停** | 只 `adb reverse tcp:4095 tcp:4095` 重挂（每轮重挂） |
| gradle 锁 | ✅ 抢→建→释放 | 3 次构建全走 `.agent-gradle-lock` 协议，收尾 `Test-Path=False` |
| 交互次数 | ✅ 未削减 | `NScroll=10 / NTap=6`，与 AB1 / P2 **完全相同**；墙钟 42.1–43.0 s |
| 冷启 | ✅ 每轮冷启 | 3/3 `LaunchState=COLD` 留证 |
| 改参数是否声明 | ✅ 已声明 | 三处：删耗电段（P2 已声明）、本轮新增 `renderMode`（默认 `full`，行为不变）、`perfMode` 默认值改为 `gzip`（**本轮的核心合入项，已在 §2 全量复测**） |
| 写权限 | ✅ | 只动 `webapp/**` + `research/perf/**`；**未碰** `app/**`、`_upstream/**`、`.github/**` |
| 只测热态 | ✅ 没有 | 每档 ScriptAB 内含 `am force-stop` + 冷启 |
| 为数字好看而改参数 | ✅ 没有 | 并行化实验**结果为负也如实报告并默认关闭**（§4） |

---

## 9. 收尾

### 9.1 `wm` 几何（已显式重设并复核）

```powershell
& $adb -s emulator-5636 shell wm size 1366x768
& $adb -s emulator-5636 shell wm density 160
```

复核：`Override size: 1366x768` / `Override density: 160` ✅
（未用 `wm size reset`，那会回物理 3840x2160，脱离 harness 规格。）

### 9.2 `/sdcard` 残留

本轮所有 logcat / gfxinfo / framestats 都在**主机侧**落 `research/perf/out/p3/`，
**未在设备上留任何临时文件**。收尾已复核 → 见 §9.4。

### 9.3 设备纪律

- 全部 adb 调用**均带 `-s emulator-5636`**；
- 后端 `127.0.0.1:4095` **全程没起停**，只 `adb reverse` 重挂；
- 收尾设备列表含 `5636 / 5670 / 5690 / 5700` —— 其中 `5670`/`5690`/`5700` 是**用户的，本轮一次都没碰**。

### 9.4 收尾复核（实测输出见本轮终端记录）

- `wm size 1366x768` / `wm density 160` ✅
- `ls /sdcard | grep -E '^_(perf|ab|s|p3)'` → 无新增残留 ✅
- `.agent-gradle-lock` → `False` ✅
