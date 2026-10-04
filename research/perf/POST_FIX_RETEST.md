# POST_FIX_RETEST —— P4：用「修好的 APK」重跑四量 + 判定 IME 修复的代价/收益

> - **执行日期**：2026-10-04（硬期限 2026-10-07 之前 ✅）
> - **目的**：把 AB1 / P2 的四量数字更新到**修复后的代码**上，并判定 IME 遮挡修复（IME1）对性能有无影响
> - **唯一设备**：`emulator-5830`（`OC_Perf_Test`，Android 15 / API 35，WebView 124.0.6367.219）
> - **被测物**：web 壳 `com.xiufeigo.opencodeshell.web.debug` / `WebHostActivity`；对照原生壳 `com.xiufeigo.opencodeshell` / `OcActivity`
> - **后端**：Lead 的 `127.0.0.1:4095`（本轮 **0 次启停**，只 `adb reverse`）
> - **交付 APK**：`research/deliver/opencode-shell-web.apk`，实测 md5 = `8dcffc572ad469f472f61594f12ea990`（任务书写的 `…12a990` 末段少一位，是笔误；前缀 `8dcffc572ad469f472f61594f12` 一致，同一文件）

---

## ✅ 一句话结论

**四量只拿到三量半，且 IME 修复"无可测代价"这一条是**靠机制推断 + 无异常**成立的，不是靠前后数字对比成立的**——因为本轮**换了一台设备（API 34 小窗 → API 35 平板 1080×2400@420）**，旧数与新数**不同机**，绝大多数"变好变坏"都**分不清是代码还是设备**。
唯一**同机可比**的量是 **web vs 原生的输入延迟**：本轮 **35.70 vs 29.02 ms（web 差 6.68 ms）**，而 P2/AB1 报的是"两侧 0.14 ms、基本无差异" ⇒ **这一条结论反转了，且它是同机对照，可信**。
**web 壳帧时本轮 4 次尝试全部因设备被外部 kill 而没测到**（不是没跑，是环境杀设备），按纪律**写"测不到"，不拿旧数顶替**。

---

## 0. 速查

| 量 | 旧数（AB1 / P2） | 本轮（修复后） | 判定 |
|---|---|---|---|
| **首屏 FCP** | P2 **2130 / 2290 / 2228 ms** | **2452 / 2856 ms**（2 次） | ⚠️ **分不清**：设备/OS/几何全变 |
| **DOM 挂载** | P2 **2572 ms / 845 节点** | **3063 ms / 845 节点** | ✅ **节点数逐个相同**（同一页面），时延差异归设备 |
| **传输比 ratio** | P2 fixed#1 **0.54**，xfer **1,455,939 B** | **0.54**，xfer **1,455,939 B** | ✅ **逐字节相同**，证明测的是 gzip 已合入的那份 |
| 资源尾巴 | P2 **31,405 / 31,527 / 31,582 ms** | **31,851 / 31,579 ms** | 🟡 同量级，**分不清** |
| **输入延迟 p50** | P2 原生 **22.27** / Web **22.41**（n=120） | **原生 29.02 / Web 35.70**（n=120） | 🔴 **同机对照：web 比原生差 6.68 ms**（旧数是 0.14） |
| 输入延迟 p90 | P2 原生 33.25 / Web 31.42 | **原生 37.26 / Web 82.55** | 🔴 web 长尾明显更差 |
| **帧时 p50** | AB1 原生 17 / Web 25；P2 Web 28~31 | **原生 22 ms**；**Web 测不到** | ❌ **web 侧缺数** |
| janky（现代） | P2 Web 14.32~17.89% | **原生 12.51%** | web 缺数 |
| **耗电** | 两边都测不到 | **测不到**（同因） | ❌ 需真机 |
| **IME 修复代价** | — | **三量上均无可测影响** | 🟡 **机制推断 + 无异常**（理由见 §5） |

---

## 1. 设备指纹与 boot 耗时

### 1.1 指纹（`out_p4/web/fingerprint.txt` 原始输出）

```
label=web pkg=com.xiufeigo.opencodeshell.web.debug act=com.xiufeigo.opencodeshell.web.WebHostActivity
no oc_perf_mode extra (APK default perfMode=gzip)
ro.product.name = sdk_gtablet_x86_64
ro.product.model = Pixel Tablet
ro.build.version.release = 15
ro.build.version.sdk = 35
ro.product.cpu.abi = x86_64
ro.build.version.incremental = 13135149
uptime:  13:41:25 up 2 min,  0 users,  load average: 1.74, 2.17, 0.96
proc_uptime: 171.19 479.60
wm size: Physical size: 2560x1600 / Override size: 1080x2400
wm density: Physical density: 320 / Override density: 420
webview:     versionName=124.0.6367.219
pkg:     versionName=0.1.0-webhost |     lastUpdateTime=2026-10-04 13:28:39
reverse: host-16 tcp:4095 tcp:4095
system_server_pid: 573
```

| 项 | 本轮（P4） | 旧轮（P2, `emulator-5636`） | 是否同机 |
|---|---|---|---|
| `ro.product.name` | `sdk_gtablet_x86_64` | `sdk_gpc_x86_64` | ❌ |
| Android / SDK | **15 / 35** | **14 / 34** | ❌ |
| 外形 | Pixel Tablet（`google_apis_playstore_tablet` 镜像） | 通用 | ❌ |
| `wm size` | 2560x1600 → **Override 1080x2400** | 3840x2160 → **Override 1366x768** | ❌ |
| `wm density` | 320 → **Override 420** | 320 → **Override 160** | ❌ |
| WebView | 124.0.6367.219 | — | — |
| `versionName` | web `0.1.0-webhost` / native `1.4.0` | 同左 | ✅ |

> 🔴 **这是本报告最重要的可比性警告**：P2 的全部数字来自 **API 34 + 1366×768@160 + 303×618 浮动小窗**；
> 本轮来自 **API 35 + 1080×2400@420 全屏**。渲染像素量差了一个数量级。
> 因此**"新数比旧数差多少"绝大部分不能归因到代码**。

### 1.2 boot 耗时（自建 AVD，`-port 5830`）

| 次 | 条件 | 墙钟 | 设备 `/proc/uptime` |
|---|---|---|---|
| 第 1 次 | 全新 AVD，冷 | **42.9 s** | 38.76 s |
| 第 2 次 | 同 AVD 重启（页缓存热） | **22.3 s** | — |
| 第 3 次（自愈重启） | | **27.5 s** | — |
| 第 4 次（自愈重启） | | **27.6 s** | — |
| E6 参考 | 别人那台 | 71 s | — |

> 启动参数（**每轮逐字相同**）：
> `-avd OC_Perf_Test -port 5830 -no-snapshot-load -no-boot-anim -no-audio -gpu swiftshader_indirect -no-metrics`
> 与 E6 的 71 s 差异来自 AVD 首次创建 + 页缓存冷热，**不作为性能结论**。

---

## 2. 🔴 本轮踩到的两个真问题（比四量数字本身更值得记）

### 2.1 【新 AVD 必须先播种口令，否则四量全在测"口令对话框"】

第一台装完 APK 直接开跑，结果：

```
10-04 13:30:06.713  4189 I OcPerf  : A.onCreate.enter t=585ms mode=gzip
...
10-04 13:30:07.039  4189 I OcWebHost: no password in SecretStore, asking
10-04 13:30:07.571  4189 I OcPerf  : Z.firstDraw t=1443ms
（再无任何 OcPerfJS / P.fwd / paint 打点，logcat 里 OcPerfJS 出现 0 次）
```

- `WebHostActivity.kt:220-224` 是**硬门控**：`SecretStore.KEY_SERVER_PASSWORD` 为空 ⇒ 弹原生 setup 对话框并 **`return`，永不 `loadUrl`**。
- 所以那一轮量的是**口令对话框**：FCP 无、DOM 无、代理一个请求都没发。
- 旧轮（`emulator-5636` / `OC_Narrow_Test`）是**复用设备，SecretStore 里已有口令**，所以这个前置条件从未暴露。
- 口令来源：后端启动契约 `OPENCODE_SERVER_PASSWORD=opencode`（`research/HANDOFF_PROMPT.md:32`），已**实测验证**：
  ```
  GET /api/info  Basic opencode:opencode => HTTP 200
  {"version":"2.0.14","pid":51160,"urls":["http://127.0.0.1:4095"],...}
  GET / 无认证 => HTTP 200（文档免认证，/api/* 才要）
  ```
- 播种脚本：`research/perf/seed_password.ps1`（web）/ `seed_native.ps1`（原生）。
  - ⚠️ 两个坑：① 弹 IME 后**整个对话框上移**，用打字前的旧坐标点"保存并连接"必然点空（web 侧已踩，改用打字后重新 dump 的坐标才对）；② PowerShell 控制台按 GBK 解 uiautomator 的 UTF-8，`保存并连接` 变乱码 ⇒ **不能按按钮 text 匹配**，取"最靠右的 Button"才稳。
  - 原生侧 Compose 的 `EditText` 在 uiautomator 里 bounds 全是 `[0,0][0,0]` ⇒ 必须看截图取真实坐标。
- **重启后凭据仍在**（AVD 数据盘持久）：`OcWebHost: home=http://127.0.0.1:46009/ … passwordPresent=true`。

### 2.2 🔴 设备在测量期间被**外部**反复 kill（4 次，全部不是本轮发起）

| 时刻 | 现象 |
|---|---|
| 21:37:44 | 第一次。正在跑 web 首屏，已拿到 FCP；帧时阶段 `device 'emulator-5830' not found` |
| 21:42:00 | 第二次。首屏拿到（FCP 2452）；帧时阶段又断 |
| 21:45:59 | 第三次。web 帧时跑完 ScriptAB（wall 41.3s）但 `dumpsys gfxinfo` 全项 N/A |
| 21:53 前 | 第四次。web 帧时仍断 |

证据（**排除本轮自身崩溃与本轮主动 kill**）：

```
$ Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'"
（OC_Perf_Test 的进程消失；boot log 正常收尾，无崩溃栈）
$ grep "Boot completed" _emulator_boot.log
INFO | Boot completed in 36525 ms      ← 正常启动行，之后无任何 error
$ adb devices        → 端口 5830 已释放
同一时段别的 lane 的实例也在换：
  21:26  5606=Pixel_Tablet / 5620=Android_SDK_built_for_x86_64
  21:44  5606=T77_Phone      / 5620=T78_Phone      / 新增 5622
```

⇒ 宿主机上有别的 lane 在**批量起停模拟器**，本轮实例被其清理扫掉。
应对：写了**自愈分块驱动** `p4_loop.ps1`（每个块前检查 `sys.boot_completed`，死了就重启再测；块结果**各自独立落盘**），
并把**短块（输入延迟 ~40 s）排在长块（帧时 ~50 s）前面**。
**没有**为了保住数据削减交互次数 / 跳冷启 / 改参数。

### 2.3 参数差异声明（硬要求：参数一变必须写明）

| 项 | `perf_run.ps1`（P2） | 本轮 | 说明 |
|---|---|---|---|
| `oc_perf_mode` extra | 传 `baseline`/`fixed` | **不传** | 交付 APK 的 `perfMode` 默认已是 `"gzip"`（`WebHostActivity.kt:92`，gzip 已合入）。**传 `fixed` 会重新打开 P2 判定"净效果为负"的整体缓冲定界，那不是交付态** ⇒ 本轮量的是**交付态默认行为**，旧数对照基线取 P2 的 **fixed#1(gzip)** |
| `$Pkg`/`$Act` | 写死 web | 参数化 | 本轮要跑原生壳做对照 |
| ScriptAB / NScroll / NTap / 手势窗口相对 | — | **逐字未改** | 保证与 AB1 / P2 可比 |
| 输入延迟采样法 | 滑动 10+10，swipe 250ms/间隔 250ms，冷启后等 22 s | **逐字未改** | 两侧同参数 |
| 冷启 | 每轮 `force-stop` + 2 s，`LaunchState=COLD` 留证 | **同** | `amstart.txt` 里 `LaunchState: COLD` |
| 首屏 settle | 30 s | 30 s（首屏两块）/ 帧时块不含首屏 | 帧时块砍掉首屏那段只为缩短设备占用窗口，**帧时/输入延迟的交互参数一个字没动** |
| 耗电段 | P2 已删（实测无 PMIC） | 改为**取证式探针** | 见 §6 |

---

## 3. 四量：新旧并列

### 3.1 量一：首屏（FCP / DOM 就绪）

**本轮显式解析 FCP 与 DOM 打点 —— 严守 P2 §2.3 的教训：绝不把"最后一个 `_assets` GET"当首屏**（该值单独记在 `LAST_asset_fwd_at_NOT_firstscreen` 字段里，名字里就写明"不是首屏"）。

`t=0 = Process.getStartUptimeMillis()`（进程创建，不依赖跨机时钟；AB1 的 −28,788,277 ms 废数教训）。

**冷启逐段（单位 ms，取第二次实例；第一次同表见 `out_p4/web/first_screen.json` 备份）**

| 段 | 打点 | 累计 | 本段 |
|---|---|---|---|
| 进程创建 → onCreate | `A.onCreate.enter` | 585（实例1）/ 660 | — |
| → WebView 创建 + setContentView | `C.webviewCreated` | 860 / 992 | ~275 / 332 |
| → insets + back handler | `F.insetsReady` | 894 / 1022 | 9 / 30 |
| → onCreate 返回 | `G.onCreate.exit` | 990 / 1130 | 1 |
| → 进 launch() | `H.launch.enter` | 1127 / **921** | 137 |
| → 代理开始监听 | `I.proxyListening` | 1299 / **1060** | 172 / 139 |
| → loadUrl 发出 | `J.loadUrlIssued` | 1312 / **1077** | 13 / 17 |
| WebView 首帧 | `Z.firstDraw` | 1491 / **1236** | 179 / 159 |
| onPageStarted | `K.onPageStarted` | 1682 / **1500** | 191 / 264 |
| **`/` 文档可用** | `P.fwd path=/` | 2092（上游 227 ms） | — |
| **DOMContentLoaded** | `DCL` | **1638** | — |
| **`load`** | `load` | **1745** | — |
| **FCP / first-paint** | `paint first-paint` | **2452** | — |
| DOM 挂载出内容 | `tick+2` | 1662（**428 节点**） | — |
| DOM 挂载完成 | `tick+3` | 2064（**843 节点**） | — |
| DOM 峰值 | `tick+n` | **3063（845 节点）** | — |
| onPageFinished | `L.onPageFinished` | 2550 / 4250 | — |
| **背景尾巴结束** | 最后一个 `P.fwd` | **31,579 / 31,851** | ~28 s |

**新旧并列**

| 指标 | P2 baseline | P2 fixed#1(gzip) | P2 fixed#2 | **本轮(修复后)** |
|---|---|---|---|---|
| **FCP** | 2130 | 2290 | 2228 | **2452 / 2856** |
| DOM 挂载完成 | 2572（845 节点） | — | — | **3063（845 节点）** ✅ 节点数相同 |
| DCL | 2002 | — | — | **1638** |
| `am start -W` TotalTime | — | — | — | **1758 / 1540**（`LaunchState=COLD`） |
| 传输量 xfer | 2,715,543（无压缩） | **1,455,939** | 1,455,939 | **1,455,939** ✅ 逐字节相同 |
| 解码量 dec | 2,697,843 | 2,698,107 | 2,698,107 | **2,698,107** ✅ |
| ratio | 1.01 | **0.54** | 0.54 | **0.54** ✅ |
| 尾巴结束 | 31,405 | 31,527 | 31,582 | **31,579 / 31,851** |
| 转发请求数 | 387 | — | — | **457 / 514** |

> ✅ **强交叉验证**：`xfer=1,455,939` 与 `dec=2,698,107` 与 P2 的 fixed#1 **逐字节相同**，
> DOM 节点数 **845 = 845** ⇒ 本轮测的**确实是同一份 gzip 已合入的代码、同一个页面**。
> 这也顺带确认了"不传 `oc_perf_mode`（默认 gzip）"这个选择是对的。

> ⚠️ **FCP 2452/2856 比 P2 的 2130/2290 高 160~730 ms —— 分不清是设备还是代码。**
> 支持"设备"的证据：API 34→35、**渲染面积 303×618 → 1080×2400（约 10 倍像素）**、density 160→420。
> 支持"代码"的证据：无。**所以写"分不清"，但不下"变慢"的结论。**

### 3.2 量二：输入延迟（**两侧 n=120，可比**）

- **口径（重要，不是端到端）**：平台记录的 **`HandleInputStart → FrameCompleted`**，即"输入处理开始 → 该帧渲染完成"的**渲染半段**。
  **不含**用户反应时间、**不含**网络往返、**不含** JS 逻辑执行、**不含** LLM。
  本报告全程称它**"输入延迟代理"**，不称端到端。
- 采样：冷启 → 固定等 22 s → 10 次上滑 + 10 次下滑（`swipe 250 ms` / 间隔 `250 ms`，窗口相对坐标）→ `dumpsys gfxinfo <pkg> framestats`。

| 指标 | P2 原生 | P2 Web baseline | P2 Web fixed#2 | **本轮 原生** | **本轮 Web（修复后）** |
|---|---|---|---|---|---|
| **样本量 n** | 120 | 120 | 120 | **120** ✅ | **120** ✅ |
| **i2f p50** | 22.27 | 22.41 | 26.63 | **29.02** | **35.70** |
| i2f p90 | 33.25 | 31.42 | 39.77 | **37.26** | **82.55** |
| i2f p95 | — | — | — | **41.85** | **97.20** |
| i2f p99 | 38.69 | 38.22 | 53.39 | **49.77** | **104.88** |
| i2f mean | 21.90 | 22.78 | 27.49 | **28.88** | **44.74** |
| i2f max | 38.91 | 41.83 | 58.01 | **56.31** | **117.86** |
| `HandleInputStart→SwapBuffers` p50 | 20.58 | 20.31 | 21.09 | **28.37** | **35.61** |
| 交互墙钟 | — | 42.9 s | — | **45.1 s** | 41.3 s |

> ### 🔴 唯一**同机可比**的结论（web vs 原生，同一台 `emulator-5830`、同一轮、同一负载）
> **Web 壳比原生壳差 6.68 ms（35.70 vs 29.02）**，而 P2/AB1 报的是"22.41 vs 22.27，**相差 0.14 ms，基本无差异**"。
> 且 p90 从"两侧都 31~33 ms"变成 **82.55 vs 37.26** ⇒ **web 侧长尾明显恶化**。
> 这个差值**不能**归因于 IME 修复（见 §5），更可能是 **WebView 渲染路径 + 本轮全屏 1080×2400 面积**。
> ⚠️ 但仍有一个**同机外**的噪声源：同期宿主上还有 2~3 台别的模拟器在起停（load average 0.78~3.07）。

### 3.3 量三：帧时

**原生壳（测到了）** —— `out_p4/native/gfxinfo_parsed.json`

| 指标 | AB1 原生 | **本轮 原生（修复后）** | 差 |
|---|---|---|---|
| Total frames | 859 | **1063** | — |
| **p50** | **17 ms** | **22 ms** | +5 |
| p90 | 29 ms | **44 ms** | +15 |
| p95 | 34 ms | **65 ms** | +31 |
| p99 | **150 ms** | **500 ms** | +350 |
| janky（现代） | 10.94% | **12.51%（133/1063）** | +1.57pp |
| janky（legacy） | 40.75% | **79.87%（849/1063）** | +39pp |
| Slow UI thread | 53 | **89** | +36 |
| Slow issue draw commands | 44 | **99** | +55 |
| Missed Vsync | — | **43** | — |
| **GPU p50** | 11 ms | **6 ms** | −5 |
| GPU p90 | — | **21 ms** | — |
| High input latency | — | **1754** | — |
| Slow bitmap uploads | — | **1** | — |
| 交互墙钟 | 41.9 s | **45.1 s** | +3.2 |

> ⚠️ 与 AB1 **不同机**（AB1 是 API 34 小窗 303×618；本轮 API 35 全屏 1080×2400）⇒ +5 ms 的 p50 **分不清**。
> 另外本轮原生壳**已连上后端**（P2 §8 #8 的未闭环项），内容比旧轮"多"，这本身也会改变绘制量。

**Web 壳（本轮测不到）**

| 尝试 | 时刻 | 结果 |
|---|---|---|
| 1 | 21:37:44 | 设备被外部 kill，gfxinfo 全 N/A |
| 2 | 21:42:00 | 同上 |
| 3 | 21:50:03 | ScriptAB 跑完（wall 41.3 s），`dumpsys gfxinfo` 全 N/A |
| 4 | 21:53 前 | 同上（自愈重启后仍断） |
| **5** | **21:54:50** | 自愈重启（boot 24.4 s、凭据 PRESENT）后**再试**，仍 `exit=4` 全 N/A |

> ### ❌ **Web 壳帧时：本轮测不到。**
> 原因**不是**没跑、**不是**脚本错：ScriptAB 每次都完整跑完（wall 41.3 s / 45.1 s 与旧轮 41.9~42.9 s 同档），
> 是**设备在最后 dump 前被别的 lane 杀掉**（证据见 §2.2）。
> 按纪律：**不拿 P2 的 25/28/29/31 ms 顶替本轮数字**，也不估算。
> ⇒ **「IME 修复对帧时有无影响」这一格，本轮没有本轮数据支撑**（见 §5.3）。

### 3.4 量四：耗电

**测不到，原因是环境性的：x86_64 模拟器没有真实 PMIC / 电流采样。** 下面是本轮**实机取证**输出（不是引旧结论）：

```
$ adb -s emulator-5830 shell dumpsys battery
Current Battery Service state:
  AC powered: false      USB powered: false    Wireless powered: false   Dock powered: false
  Max charging current: 0        Max charging voltage: 0        Charge counter: 10000
  status: 4      health: 2      present: true
  level: 100     scale: 100     voltage: 5000     temperature: 250   technology: Li-ion
                                ↑ 全部是**合成值**：level 恒 100、voltage 恒 5000、temp 恒 250(25.0°C)

$ adb -s emulator-5830 shell dumpsys batterystats | grep -i discharge
Discharge: 0 mAh                       ← 与 AB1 §3.4.1、P2 §8 #6 独立一致

$ adb -s emulator-5830 shell ls /sys/class/power_supply/
ac   battery
$ adb -s emulator-5830 shell ls /sys/class/power_supply/*/current_now
/sys/class/power_supply/battery/current_now     ← 节点在，但读数是合成的
$ adb -s emulator-5830 shell getprop ro.hardware     → ranchu
$ adb -s emulator-5830 shell getprop ro.kernel.qemu  → 1
```

> 结论：`battery` 服务返回的是**桩数据**（`Discharge` 恒 0、level/voltage/temperature 恒定、`ro.kernel.qemu=1`），
> **任何 mAh 口径都不可信** ⇒ **耗电量级"测不到"**。


**替代量（能给的都给了）**

| 替代量 | 原生 | Web | 说明 |
|---|---|---|---|
| CPU 累计占用 | AB1/P2 都是 0 ms（测不到） | 同 | `dumpsys batterystats` 无可用口径 |
| 传输量（唯一真实的"省电"代理） | — | **xfer 1,455,939 B / dec 2,698,107 B / ratio 0.54** | gzip 已合入 ⇒ 每轮首屏少传 ~46% |
| 首屏墙钟 | — | 2452 / 2856 ms | 与旧轮同量级 |
| 尾巴墙钟（非首屏） | — | 31,579 / 31,851 ms | 380 个主题包，实打实多耗 ~28 s CPU/网络 |

> **结论：耗电量级"测不到"。** 唯一可信的方向性说法是：
> **gzip 透传让每轮少传 1.26 MB（−46.4%），这是省流量/省电的纯收益；**
> 而 **28 s 的主题包尾巴仍在**（本轮 31.6 s vs P2 31.4~31.6 s，**没改善**），它才是真正的耗电大头。

---

## 4. 本轮新增的事实（对后续有用）

1. **新建 AVD 上两个壳都要先播种凭据**，否则硬门控会让四量全测成"口令对话框"，而且**表面上看起来"跑完了"**（有 FCP 的邻居 `Z.firstDraw`、有 `TotalTime`），极易误判。判别信号：`OcWebHost: no password in SecretStore, asking` + logcat 里 `OcPerfJS` 出现 **0 次**。
2. **IME 遮挡修复不影响"壳能否起"**：修复后首屏流水线（代理起 → loadUrl → DCL → load → FCP → 845 节点 DOM）与 P2 完全同构，无新增阻塞段。
3. **`ratio=0.54` / `xfer=1,455,939` 可以当"这份 APK 确实是 gzip 已合入版"的指纹**，比读版本号可靠。

---

## 5. IME 修复的代价/收益判定（给数字，不给感觉）

被测修复 = `webapp/src/main/assets/www/oc-host.js` 注入的
`installImeLiftStyle()` + `form:has(textarea){padding-bottom:var(--oc-ime-bottom,0px)}`
+ `:root{--oc-safe-bottom-eff:…}` + `document` 上 capture 阶段委托的 `focusin` 监听。

### 5.1 机制：修复在**三种测量条件下都是"空操作"**

IME 收起时宿主给的 `--oc-ime-bottom` 实测为 `0.00px`（IME1 §4.1 表：改前那一轮 `imeVar` 就是 `""`/`0.00px`）。
⇒ `padding-bottom: var(--oc-ime-bottom, 0px)` 计算值为 **0**，规则不产生任何盒模型变化。

而本轮四个量的测量条件是：

| 量 | 测量时 IME 状态 | 修复是否生效 |
|---|---|---|
| 首屏 FCP / DOM | **收起**（无输入焦点） | ❌ 空操作 |
| 输入延迟（滑动驱动） | **收起**（swipe 不唤起 IME） | ❌ 空操作 |
| 帧时（ScriptAB：滑动 + 点按空白） | **收起**（点按 (0.5,0.45) 落在消息区，不聚焦 composer） | ❌ 空操作 |

### 5.2 逐量判定

| 量 | 有无可测影响 | 数字依据 |
|---|---|---|
| **首屏** | **无异常；归因"分不清"，但无机制支持有影响** | FCP 2452/2856、DCL 1638、DOM 845 节点、xfer 1,455,939 / ratio 0.54 —— 全部与 P2 fixed#1 同构；DOM 节点数**逐个相同**。修复只在 `DOMContentLoaded` 时插 **1 个 `<style>`（~0.5 KB）+ 1 个 `:root` 变量 + 2 条选择器 + 1 个委托监听**，`DCL` 1638 ms 与 P2 的 2002 ms 相比**反而更快**（不同机，不作结论）。**≈0.5 KB CSS + 1 监听无法解释几百毫秒的 FCP 差** ⇒ 可信读法是"无可测影响"，但严格讲**分不清**（设备/OS/面积同时变了）。 |
| **输入延迟** | **确定无影响（空操作）** | 修复的 `padding-bottom` 计算值为 0；且本轮 Web 35.70 vs 原生 29.02 的 6.68 ms 差距，**原生壳根本没有这段 JS** ⇒ 若差距由修复引起，原生侧不可能同步出现同向偏移。**该差值应归 WebView 渲染路径 + 全屏面积，不是修复。** |
| **帧时** | **本轮 Web 侧没测到 ⇒ 无法判定** | 见 §3.3。原生 22 ms 不能替 Web 背书。机制上仍是空操作（ScriptAB 全程 IME 收起），且 `transition: padding-bottom .12s ease` 在值为 0 时不触发合成器额外工作 —— 但**没有本轮数字，不写成结论。** |
| **耗电** | **测不到 ⇒ 无法判定** | 见 §3.4。 |

### 5.3 判定汇总

> **IME 修复在首屏与输入延迟上"无可测代价"——依据是"该修复在这两个量的测量条件下是 padding:0 的空操作"，而不是"前后数字相同"。**
> **帧时与耗电两格本轮无法判定**（前者 Web 侧设备被外部 kill 4 次，后者模拟器无 PMIC）。
> **因此不能说"IME 修复对四量都无代价"**，只能说"**在能判的两格无代价，且有明确的机制解释**"。

### 5.4 收益（复述已验收的，不重复举证）
IME1 已实测 **手机 60→0 / 平板 60→0** 重叠像素、`form` 的 `padding-bottom` 实测 336.38 px 与 `--oc-ime-bottom` 逐像素相等、回归 16 用例 0 失败。本轮**未复测该判据**（本轮任务是性能，不是 IME 复现）。

---

## 6. 分不清的项（硬要求逐条列出）

| # | 项 | 为什么分不清 | 要怎样才能分清 |
|---|---|---|---|
| 1 | FCP 2452/2856 vs 旧 2130/2290 | **设备全变**：API 34→35、面积 303×618→1080×2400（~10×像素）、density 160→420 | **在 API 34 + 1366×768@160 + 303×618 上重跑同一 APK**，与 P2 逐项对齐 |
| 2 | 输入延迟绝对值 35.70 / 29.02 vs 旧 22.41 / 22.27 | 同上 | 同上 |
| 3 | 原生帧时 22 ms vs AB1 17 ms | 同上；且**本轮原生已连后端**（P2 #8 未闭环项），绘制量不同 | 用 P2 同一台机器、同一内容状态重跑 |
| 4 | **IME 修复对帧时的影响** | **本轮 Web 帧时没测到**（设备 4 次被外部 kill） | 换一台**独占**的模拟器重跑 web/ft |
| 5 | **IME 修复对耗电的影响** | x86_64 模拟器**无 PMIC**，`Discharge` 恒 0 | 上真机 |
| 6 | web 输入延迟比原生差 6.68 ms（P2 是 0.14 ms） | **这项是同机对照，本身成立**；但"为什么旧轮没差距"分不清：可能是全屏面积，也可能是宿主上 2~3 台别的模拟器在抢 CPU（load 0.78~3.07） | 宿主**清空其他模拟器**后同机重跑两侧 |
| 7 | 帧时 p50 目标 ≤17 ms 是否达成 | Web 侧缺数；原生侧本轮 22 ms 但不同机 | 同 #1 |
| 8 | 资源尾巴 31.6 s 是否"没改善" | 与 P2 31.4~31.6 s 同量级，但**不同机**；只能说"**未见改善**"，不能说"确认没改善" | 同 #1 |
| 9 | janky 两口径打架 | 本轮原生现代 12.51% vs legacy 79.87%，方向仍与旧轮相反 | 需两侧同机同内容对比 |
| 10 | 「内容可见」仍是 FCP + DOM 节点数，**不是像素** | 沿袭 P2 #4 | 补连续截图 + 像素比对 |
| 11 | 窗口从 303×618 小窗变成 1080×2400 **全屏** | 本轮是**有意**按任务书用手机几何，但**因此旧数不可外推** | 对外承诺前用统一几何重测 |

---

## 7. 收尾复核

### 7.1 `wm` 复原

本轮**改过** `wm size` / `wm density`（任务书要求的手机几何 1080×2400@420）。
**处理方式**：本轮**自建**的 AVD `OC_Perf_Test` 整台在收尾被关掉，
其 override 几何随实例一起消失，**不留残留状态**；
用户自己的 AVD（`MCRemote_*` / `DSH_*` / `T7x_*` 等）**一个都没碰**。

```
$ adb -s emulator-5830 shell wm size
Physical size: 2560x1600
Override size: 1080x2400          ← 本轮设的
$ adb -s emulator-5830 shell wm density
Physical density: 320
Override density: 420            ← 本轮设的
```

### 7.2 关掉自己起的模拟器（**谁起谁关**）

见文末「收尾复核输出」小节（实际执行输出贴在下方）。

- 启动的实例：**只有 1 个 AVD = `OC_Perf_Test`**，serial `emulator-5830`，端口 `5830`。
- 本轮**同一个 AVD / 同一个端口一共启动过 6 次**（首启 1 次 + 被外部 kill 后自愈重启 4 次 + 收尾取证 1 次）⇒ 收尾按 AVD 名 + serial 关一次即覆盖全部。
- **禁止项自查**：`adb kill-server` **未执行**；`-a` / 通配符杀进程 **未使用**；
  `emulator-5606` / `5620` / `5622`（**非本轮启动**，AVD 名 `T77_Phone` / `T78_Tablet` / `T78_Phone`）**一次都没碰**。

**实际收尾复核输出（`out_p4/_closeout.txt`，逐字）**

```
[21:56:00] === [A] 关停前基线：当前全部 emulator 进程 ===
  pid=49096 name=emulator.exe avd='T77_Phone'  port='5606'
  pid=41572 name=emulator.exe avd='T78_Tablet' port='5622'
  pid=45000 name=emulator.exe avd='T78_Phone'  port='5620'
[21:56:02] === 端口 5830 关停前监听数 = 0 ===
[21:56:02] === [B] 耗电可测性取证（临时起 OC_Perf_Test） ===
  EMU_PID=42216 (取证用，随后立即关掉)
  boot_for_probe=True

[21:56:31] === [C] 关停 OC_Perf_Test / emulator-5830 ===
  adb -s emulator-5830 emu kill -> OK: killing emulator, bye bye  OK
[21:56:39]   残留 OC_Perf_Test 进程数 = 0

[21:56:39] === [D] 复核 1：进程（应 0 残留 OC_Perf_Test）===
  pid=49096 name=emulator.exe avd='T77_Phone'  port='5606'  <== 别人的，未碰
  pid=41572 name=emulator.exe avd='T78_Tablet' port='5622'  <== 别人的，未碰
  pid=45000 name=emulator.exe avd='T78_Phone'  port='5620'  <== 别人的，未碰
  pid=54536 name=emulator.exe avd='' port=''  <== 别人的，未碰
  pid=34220 name=emulator.exe avd='' port=''  <== 别人的，未碰
[21:56:40] === [E] 复核 2：端口 5830 监听（应为空）===
  Get-NetTCPConnection -LocalPort 5830 -State Listen => <空>，端口已释放
[21:56:40] === [F] 复核 3：adb devices（不应再有 emulator-5830）===
    List of devices attached
    emulator-5606	device
    emulator-5620	device
    emulator-5622	device
  (未执行 adb kill-server；未使用 -a / 通配符；非 OC_* 实例一次都没碰)
[21:56:40] === CLOSEOUT DONE ===
```

**收尾自查（硬规矩逐条）**

| 规矩 | 结果 | 证据 |
|---|---|---|
| 谁起谁关 | ✅ | `adb -s emulator-5830 emu kill` → `OK: killing emulator, bye bye  OK` |
| **进程 0 残留** | ✅ | 残留 `OC_Perf_Test` 进程数 = **0**；`qemu-system-x86_64` 进程数 = **0**（这才是"CPU 幽灵占用"的判据） |
| **端口已释放** | ✅ | `Get-NetTCPConnection -LocalPort 5830 -State Listen` → **`<空>`**；全状态查询只剩一条 `TimeWait`(127.0.0.1:5830→127.0.0.1:56979)，是 TCP 正常挥手残留，**非监听** |
| 只关自己起的（按 AVD 名） | ✅ | 关闭前基线里的 `T77_Phone` / `T78_Tablet` / `T78_Phone` 三个**非 `OC_*`** 实例，收尾后**三个都还在**，PID 未变 |
| 用户自己的 AVD 禁止 kill | ✅ | `MCRemote_*` / `DSH_*` / `T7x_*` 本轮**一次都没起过、一次都没碰过** |
| 禁 `adb kill-server` | ✅ | 全程未执行（`adb devices` 仍正常服务 `5606/5620/5622`） |
| 禁 `-a` / 通配符杀进程 | ✅ | 只按 serial（`emulator-5830`）与 AVD 名（`OC_Perf_Test`）定位 |
| 故意保留声明 | ✅ | **无故意保留的实例**（本轮无跨轮续用需求） |

> 补充说明：复核里 `avd='' port=''` 的两个 `emulator.exe`（`... -kill 54644 -sleep 20`）经查是
> **别的 lane 正在用它自己 pid 54644 做 emu kill 的辅助进程，与本轮无关**，我没有动它。


---

## 8. 我碰过的 serial

| serial | AVD | 是不是我起的 | 我对它做了什么 |
|---|---|---|---|
| `emulator-5830` | `OC_Perf_Test`（本轮新建） | ✅ **是** | 建 AVD → 起 4 次 → 装 2 个 APK → `wm size/density` → `adb reverse` → 播种凭据 → 全部 adb 调用带 `-s emulator-5830` → **收尾关掉** |
| `emulator-5606` | `T77_Phone`（非 `OC_*`） | ❌ 否 | **零操作**（只在 `adb devices` 里出现过名字） |
| `emulator-5620` | `T78_Phone`（非 `OC_*`） | ❌ 否 | **零操作** |
| `emulator-5622` | 非 `OC_*`，来源不明 | ❌ 否 | **零操作** |

**故意保留的实例：无。**（本轮没有跨轮续用的需求）

---

## 9. 未闭环

| # | 项 | 状态 | 下一步 |
|---|---|---|---|
| 1 | **Web 壳帧时本轮缺数** | 🔴 高 | 换一台**独占**模拟器（同机独占，别与别的 lane 并发起停）重跑 `chunk.ps1 -Side web -What ft` |
| 2 | 耗电 | ❌ 测不到 | 上真机 |
| 3 | **新 AVD 播种口令未产品化** | 🔴 中 | 建议把 `seed_password.ps1` / `seed_native.ps1` 并入 `common.ps1` 式的测量前置流程，否则下一轮还会踩 |
| 4 | 宿主多 lane 并发起停模拟器 | 🔴 高（环境） | **建议派单时按 AVD 名/端口互斥**，否则测量会被无端打断（本轮 4 次） |
| 5 | 旧数仍来自 API 34 小窗 | 🟡 中 | 若要对外承诺，先在**统一几何**上重刷一遍 AB1/P2/P4 三组 |
| 6 | 380 个主题包尾巴 | 🟡 中（沿袭 P2 #1） | 仍需 UI 侧改按需/懒加载；本轮确认**壳侧改传输改不动它** |
| 7 | janky 两口径 | 🟡 中（沿袭 P2 #7） | 报告时两口径必须一起给 |

---

## 10. 本轮产出物

| 文件 | 内容 |
|---|---|
| `research/perf/POST_FIX_RETEST.md` | 本报告 |
| `research/perf/retest_run.ps1` | 四量主脚本（首屏 + 帧时 + 输入延迟 + 耗电探针），原生/web 双侧 |
| `research/perf/chunk.ps1` | 分块脚本（`-What input|ft`），每块独立落盘 |
| `research/perf/p4_loop.ps1` | 自愈驱动：设备被外部 kill 就重启重测 |
| `research/perf/ft_run.ps1` | 精简版（砍首屏段，缩短设备占用窗口） |
| `research/perf/seed_password.ps1` / `seed_native.ps1` | 新 AVD 凭据播种 |
| `research/perf/relaunch_p4.ps1` / `p4_final.ps1` | 重启/一体化（保留作取证） |
| `research/perf/out_p4/{web,native}/*.json` | 各量原始结果 |
| `research/perf/out_p4/*/logcat_marks.txt` | 冷启打点原始行 |
| `research/perf/out_p4/_loop_status.txt` | 自愈循环全过程日志（kill 时间线） |
