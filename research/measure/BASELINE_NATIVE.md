# BASELINE_NATIVE — 原生壳性能基线（可复现量化尺子）

> 目的：为「**原生壳 vs 未来 Web 壳**」提供一条**可复现的对照尺子**。
> 本文件记录四个量的**命令 + 原始输出 + 数值**，并说明每一项的**测得部分**与
> **测不到部分**。所有数字均来自同一台设备、同一份脚本、同一轮次。
> 数字**不是**现场编造：每条都能用本文件里的命令原样重跑出来。

---

## 0. 测量环境（先看这一节，否则数字无法对齐）

### 0.1 ⚠️ 设备替换：`emulator-5554` 已死，本基线在 `emulator-5604` 上测得

任务书指定设备 `emulator-5554`，但它在本次会话开始时**已不存在**：

```
$ adb devices -l
List of devices attached
emulator-5604   device  product:sdk_gphone16k_x86_64  ...
emulator-5612   device  product:sdk_gtablet_x86_64     ...
emulator-5620   device  (offline)
emulator-5628   device  product:sdk_gtablet_x86_64     ...

$ adb -s emulator-5554 shell echo hello
adb.exe: device 'emulator-5554' not found
$ adb connect localhost:5554
cannot connect to 127.0.0.1:5554: 由于目标计算机积极拒绝，无法连接。 (10061)

$ emulator -list-avds | Select-String Medium_Desktop
Medium_Desktop        # AVD 还在，但没有在跑
```

只有 **emulator-5604** 装了被测包，另外两台没装、一台 offline：

| 设备 | 屏幕 | density | ABI | 被测包 |
|---|---|---|---|---|
| **emulator-5604（采用）** | 1080x2424 | 420 | x86_64 | ✅ 已安装 |
| emulator-5612 | 1080x2340 (override) | 440 | x86_64 | ❌ 未安装 |
| emulator-5620 | — | — | — | ❌ offline |
| emulator-5628 | 1080x2400 | 280 | x86_64 | ❌ 未安装 |

**这是本基线最大的未闭环项**：原始标定设备（`emulator-5554` / AVD `Medium_Desktop`）
已不可用，因此**下面所有数字都不能直接和 5554 的历史数字对齐**。它们是 5604 的基线。

### 0.2 显示几何：统一压到项目标准 harness `1366x768 / 160`

本仓库历史截图语料（`research/em5554_shots`、`vm5554_shots` …）全部是
1366x768 / density 160。为让 Web 壳将来能对齐同一套语料，本次把 5604 强制成同规格：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5604 shell wm size 1366x768
& $adb -s emulator-5604 shell wm density 160

# 原始输出
$ & $adb -s emulator-5604 shell wm size
Physical size: 1080x2424
Override size: 1366x768
$ & $adb -s emulator-5604 shell wm density
Physical density: 420
Override density: 160
```

> 收尾已 `wm size reset` / `wm density reset` 复位回物理值，见 §7。

### 0.3 其余环境事实

| 项 | 值 | 取证命令 |
|---|---|---|
| 包名 | `com.xiufeigo.opencodeshell` | `pm list packages` |
| 入口 | `.OcActivity` | — |
| 已装版本 | 1.4.0（md5 `b30290a6b67fc6cd2804aa5a2b4c17d6`，见 `research/v97_verify/install_all.txt`） | `install_all.txt` |
| 渲染后端 | `Pipeline=Skia (OpenGL)` | `dumpsys gfxinfo` 尾行 |
| 系统 | Android 17 (API 17 分支), release=17 | `getprop ro.build.version.release` |
| 架构 | x86_64 模拟器 | `getprop ro.product.cpu.abi` |
| IME | `com.google.android.inputmethod.latin/...LatinIME` | `settings get secure default_input_method` |
| 后端 v2 | **已停**（按任务书要求未自行启动） | — |

### 0.4 一个必须先讲清楚的前提：无后端 ⇒ 会话内容是错误态

```powershell
& $adb -s emulator-5604 shell uiautomator dump /sdcard/_d.xml
# 抽出的 text 节点（节选）
Projects|N|nonexist_v84|N|nonexist_v82|X|x|O|opencode-android-shell
|Settings|Help|Search sessions|Couldn't load sessions|Retry
```

后端停掉后，首页会话列表停在 **"Couldn't load sessions / Retry"**，
而且 4 行就能塞进视口，**滑动不会产生位移**。这一点直接决定了
「滚动」类测量在本基线里的性质（见 §1.4）。

---

## 1. 量一：卡顿 / 流畅（gfxinfo）

### 1.1 命令

```powershell
# 脚本化交互（固定，详见 research/measure/common.ps1 的 ScriptA）
dumpsys gfxinfo com.xiufeigo.opencodeshell reset
#   -> 冷启动
#   -> New Session 工作区列表滑动 x8 上 / x8 下
#   -> 打开/关闭 UI 往返 x3（New Session / Plugins / Settings）
#   -> 首页列表滑动手势 x5 上 / x5 下
#   -> 命令面板打开/关闭
dumpsys gfxinfo com.xiufeigo.opencodeshell
```

一键复现：

```powershell
pwsh -NoProfile -File research\measure\measure_1_gfxinfo.ps1
# 完整原始输出：research/measure/out/gfxinfo_full.txt
# 控制台日志：   research/measure/out_measure1.log
```

### 1.2 原始输出（节选，逐字）

```
** Graphics info for pid 25194 [com.xiufeigo.opencodeshell] (user 0) **

Stats since: 1894111036600ns
Total frames rendered: 137
Janky frames: 59 (43.07%)
Janky frames (legacy): 103 (75.18%)
50th percentile: 30ms
90th percentile: 750ms
95th percentile: 1050ms
99th percentile: 4150ms
Number Missed Vsync: 30
Number High input latency: 147
Number Slow UI thread: 54
Number Slow bitmap uploads: 1
Number Slow issue draw commands: 48
Number Frame deadline missed: 59
Number Frame deadline missed (legacy): 57
...
50th gpu percentile: 11ms
...
Pipeline=Skia (OpenGL)
```

### 1.3 数值表

| 指标 | 数值 |
|---|---|
| **总帧数** | **137** |
| **janky 帧** | **59** |
| **janky 帧率** | **43.07%**（legacy 口径 75.18%） |
| **p50 帧时** | **30 ms** |
| **p90 帧时** | **750 ms** |
| **p95 帧时** | **1050 ms** |
| **p99 帧时** | **4150 ms** |
| Missed Vsync | 30 |
| High input latency | 147 |
| Slow UI thread | 54 |
| Slow issue draw commands | 48 |
| Slow bitmap uploads | 1 |
| Frame deadline missed | 59 |
| GPU p50 | 11 ms |
| 交互墙钟时长 | 56.5 s |

### 1.4 对这组数字的诚实解读（不要只看 43%）

1. **43.07% 是"小样本 + 含启动/切页长帧"的口径。** 137 帧里 p99 冲到 4150 ms，
   直方图里明确有 `4150ms=1`、`4350ms=1`、`750ms=4`、`1050ms=3`。
   这些是**冷启动首绘 + 切页动画**的长尾，不是"稳定滚动掉帧"。
2. **稳态帧时其实不差。** 直方图 10–22 ms 区间密集（`10ms=4 11ms=6 12ms=6 13ms=3
   14ms=1 15ms=3 16ms=4 17ms=5 18ms=7 19ms=3 20ms=6 21ms=7`），
   p50=30ms 是**被长尾帧拉高**的；真正的主体在 ~1 vsync 附近。
3. **GPU 侧很健康**（GPU p50 = 11 ms，GPU 直方图 7–9 ms 最多），
   卡顿主要来自 **UI thread / issue draw commands**（54 / 48），
   即**主线程上的 Compose 重组 + 布局**，不是 GPU 填充。
4. **必须标注的偏差**：`Number High input latency = 147 > 137 总帧数`，
   该计数器在现代 Android 上对单帧可多次累加，**不能**当作帧数读；
   跨版本对比时只看趋势，不看绝对值。
5. **"滚动"这一项在本环境是退化的**：首页列表因无后端只有 4 行、滑动无位移，
   真正产生帧的是**切页往返**（见 §1.1 的 ScriptA）。
   所以本基线的"流畅"≈ **切页/启动流畅度**，不是"长列表滚动流畅度"。

### 1.5 一个必须记录的踩坑：`Skipped: process is frozen`

第一次跑 `measure_1` 时得到的是空统计：

```
** Graphics info for pid 22863 [com.xiufeigo.opencodeshell] (user 0) **
  Skipped: process is frozen
Total frames rendered            = N/A
Janky frames                     = N/A
```

**原因**：交互脚本最后一步按 BACK 退到了桌面，App 进程被 cached/frozen，
`dumpsys gfxinfo` 对冻结进程直接跳过取数。
**修复**：脚本里加了 `EnsureForeground()`，每次 `BACK` 之后立刻
`am start -n com.xiufeigo.opencodeshell/.OcActivity` 把进程拉回前台，
并在取数前校验 `pidof` 非空。**任何未来复现者都会踩这个坑，请先看这条。**

---

## 2. 量二：首帧时间（`am start -W`）

### 2.1 命令

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"; $D='emulator-5604'
1..3 | ForEach-Object {
  & $adb -s $D shell am force-stop com.xiufeigo.opencodeshell
  Start-Sleep -Seconds 2
  & $adb -s $D shell am start -W -n com.xiufeigo.opencodeshell/.OcActivity
  Start-Sleep -Seconds 2
}
```

一键复现：`pwsh -NoProfile -File research\measure\measure_2_firstframe.ps1`

### 2.2 原始输出（三次，逐字；原始文件 `out/amstart_1..3.txt`）

**第 1 次**
```
Starting: Intent { cmp=com.xiufeigo.opencodeshell/.OcActivity }
Status: ok
LaunchState: COLD
Activity: com.xiufeigo.opencodeshell/.OcActivity
TotalTime: 4122
WaitTime: 4125
Complete
```

**第 2 次**
```
Starting: Intent { cmp=com.xiufeigo.opencodeshell/.OcActivity }
Status: ok
LaunchState: COLD
Activity: com.xiufeigo.opencodeshell/.OcActivity
TotalTime: 3799
WaitTime: 3801
Complete
```

**第 3 次**
```
Starting: Intent { cmp=com.xiufeigo.opencodeshell/.OcActivity }
Status: ok
LaunchState: COLD
Activity: com.xiufeigo.opencodeshell/.OcActivity
TotalTime: 4500
WaitTime: 4504
Complete
```

### 2.3 数值表

| 轮次 | LaunchState | TotalTime (ms) | WaitTime (ms) |
|---|---|---|---|
| 1 | COLD | **4122** | 4125 |
| 2 | COLD | **3799** | 3801 |
| 3 | COLD | **4500** | 4504 |

| 统计量 | TotalTime |
|---|---|
| **三条原值** | **4122 / 3799 / 4500 ms** |
| min | 3799 ms |
| max | 4500 ms |
| **均值** | **4140 ms** |
| 极差 | 701 ms（相对均值 ±8.5%） |

### 2.4 解读与限制

- 三次**全部是 `LaunchState: COLD`**，即都是真正的冷启动（进程被 force-stop 后重建），
  口径干净、无 warm-start 混入。
- **这是 x86_64 模拟器、无 JIT 预热、无后端连接**的数。它衡量的是
  "Activity 创建 → 首帧上屏"这一段，含进程 fork、Application/Compose 初始化、
  首屏组合。**不含**任何网络/后端/模型时间。
- `TotalTime` 与 `WaitTime` 几乎相同（差 2–4 ms），说明系统没有在 launch 窗口内
  额外等待输入或空闲；`TotalTime` 即为 launch→首帧的支配值。
- ⚠️ 该值**含模拟器调度噪声**（3.8 s–4.5 s，波动 18%）。跨轮次对比建议 **≥5 次取中位数**，
  本次按任务书只跑 3 次。

## 3. 量三：输入 -> 上屏延迟（代理量）

> ### ⚠️ 先把话说死
> **这不是真实的端到端输入延迟，只是基线代理。**
> 理由：无后端 ⇒ 没有"发消息 -> 首个 token 上屏"这条真实链路可测；
> 并发纪律禁止 gradle 装机 ⇒ 无法给 app 插 Perfetto/打点。
> 下面测的是**渲染半段**（平台自己记录的 输入处理 -> 帧完成/上屏），
> **不含**用户反应时间、不含网络、不含 LLM。

### 3.1 命令

```powershell
$adb = "$env:LOCALAPSDATA\Android\Sdk\platform-tools\adb.exe"; $D='emulator-5604'
& $adb -s $D shell dumpsys gfxinfo com.xiufeigo.opencodeshell reset
# 12 次输入驱动的点击（New Session / Plugins 交替），每次都产生可见界面变化
& $adb -s $D shell dumpsys gfxinfo com.xiufeigo.opencodeshell framestats
```

一键复现：`pwsh -NoProfile -File research\measure\measure_3_inputlatency.ps1`

> ⚠️ 实际产出的数据文件是 `out/framestats_probe.txt`（原始）+ `out/framestats_probe_parsed.csv`（解析）。
> `out/framestats_raw.txt` 是那次失败的 12 点击跑留下的**空文件**（设备中途被杀，见 §6 缺口 #9）。

### 3.2 本机 Android 的 framestats 列（这一步也踩了坑）

本机是**新版 schema**，Android<=13 的 `OldestInputEvent` / `NewestInputEvent`
**已经不存在**：

```
Flags,FrameTimelineVsyncId,IntendedVsync,Vsync,InputEventId,HandleInputStart,
AnimationStart,PerformTraversalsStart,DrawStart,FrameDeadline,FrameStartTime,
FrameInterval,WorkloadTarget,AnimationTime,AnimationCounter,SyncQueued,SyncStart,
IssueDrawCommandsStart,SwapBuffers,FrameCompleted,DequeueBufferDuration,
QueueBufferDuration,GpuCompleted,SwapBuffersCompleted,DisplayPresentTime,
CommandSubmissionCompleted,
```

因此本基线改用等价列：

| 含义 | 用的列 | 说明 |
|---|---|---|
| App 开始处理该帧的输入 | `HandleInputStart` | 替代旧的 `NewestInputEvent` |
| 帧渲染完成 | `FrameCompleted` | 主口径终点 |
| 缓冲交换完成（最接近"上屏"） | `SwapBuffersCompleted` | 上屏代理 |
| 硬件呈现时间 | `DisplayPresentTime` | **本机恒为 0，不可用** |
| 刷新间隔 | `FrameInterval` | 实测 16666666 ns |

### 3.3 数值（见下方 §3.5 表格）

**采样来源（重要）**：`emulator-5604` 在正式 12 次点击跑之前被并发 lane 的模拟器churn
杀掉了（详见 §6 缺口 #9）。下表取自**设备死前最后一次成功采集**的 framestats
探针（2 次点击，pid 32568，原始文件 `out/framestats_probe.txt`，
解析结果 `out/framestats_probe_parsed.csv`）。**样本量小（6 帧），
只能当数量级参考，不能当稳定基线。**

原始输出（PROFILEDATA 头 + 6 行，节选逐字）：

```
Flags,FrameTimelineVsyncId,IntendedVsync,Vsync,InputEventId,HandleInputStart,
AnimationStart,PerformTraversalsStart,DrawStart,FrameDeadline,FrameStartTime,
FrameInterval,WorkloadTarget,AnimationTime,AnimationCounter,SyncQueued,SyncStart,
IssueDrawCommandsStart,SwapBuffers,FrameCompleted,DequeueBufferDuration,
QueueBufferDuration,GpuCompleted,SwapBuffersCompleted,DisplayPresentTime,
CommandSubmissionCompleted,
1,299433,2425628473360,2425645140026,0,2425652237300,...,16666666,...,2426196559400,29600,565400,2426196559400,2426193065500,0,2426079927200,
0,299463,2425661806692,2426211806670,0,2426223540700,...,16666666,...,2426479750400,250800,527800,2426479373400,2426479750400,0,2426409906200,
```

解析结果（全 6 帧）：

```
intended_vsync   FrameInterval  i2f_ms  i2swap_ms  i2present_ms  frame_ms
2425628473360    16666666       544.32  540.83     (0)           568.09
2425661806692    16666666       256.21  256.21     (0)           817.94
2426228473336    16666666        94.25   94.25     (0)           272.98
2426411806662    16666666        85.45   59.01     (0)           169.03
2430228473176    16666666        84.63   84.63     (0)            86.34
2430245139842    16666666        89.56   89.56     (0)            94.65
```

### 3.4 数值表

| 指标 | n | min | p50 | max | mean |
|---|---|---|---|---|---|
| **HandleInputStart → FrameCompleted**（主口径） | 6 | **84.63 ms** | **89.56 ms** | **544.32 ms** | 192.40 ms |
| HandleInputStart → SwapBuffersCompleted（上屏代理） | 6 | 59.01 ms | ~89.56 ms | 540.83 ms | — |
| IntendedVsync → FrameCompleted（整帧时） | 6 | 86.34 ms | 169.03 ms | 817.94 ms | — |
| **HandleInputStart → DisplayPresentTime** | **0** | — | — | — | **该字段全为 0，不可用** |
| 显示刷新间隔 FrameInterval | — | 16666666 ns = **16.67 ms → 60 Hz** | | | |

**可用的基线数：输入处理 → 帧完成，p50 ≈ 90 ms（n=6，量级参考）。**

### 3.5 解读

- **p50 约 90 ms ≈ 5.4 个 vsync**（60 Hz）。这对 Compose 壳来说不算差，
  但**样本只有 6 帧且含 2 帧冷启长尾（544 / 256 ms）**，离散度极大，
  **不足以定基线**。正式基线必须重跑（≥30 帧）。
- **`DisplayPresentTime` 恒为 0**：本模拟器/构建不填硬件呈现时间戳，
  所以"真正上屏"这一刻**测不到**，只能用 `SwapBuffersCompleted` 代替。
- **输入事件本身的 adb 注入开销是 26–35 ms**（`input tap` 命令往返），
  这部分**不包含**在上表的 90 ms 里（framestats 记录的是 App 内部时间戳），
  但它意味着**任何用 `adb shell input` 做的延迟测量都有几十 ms 的注入抖动**。

### 3.6 明确「测不到」的部分

| 想测的 | 能不能测 | 原因 |
|---|---|---|
| 端到端"按键 -> 首个 token 上屏" | ❌ **测不到** | 后端 v2 已停；且并发纪律禁止起服务/装机，无法加打点 |
| 真实用户感知延迟 | ❌ **测不到** | 代理量不含用户反应时间，且 `DisplayPresentTime` 恒 0 |
| IME 弹出 / 键盘按键到界面变化 | ⚠️ 仅部分 | 可用同一 framestats 口径测键盘弹出的帧，但**本次未纳入**（见 §6 未闭环） |
| 渲染半段 输入处理 -> 帧完成 | ✅ 测到了 | 见 §3.5 |

---

## 4. 量四：耗电（batterystats）

### 4.1 命令

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"; $D='emulator-5604'
& $adb -s $D shell input keyevent KEYCODE_WAKEUP
& $adb -s $D shell wm dismiss-keyguard
& $adb -s $D shell am force-stop com.xiufeigo.opencodeshell
& $adb -s $D shell am start    -n com.xiufeigo.opencodeshell/.OcActivity

& $adb -s $D shell dumpsys batterystats --reset        # 清零统计
# 固定窗口：循环执行 SCRIPT-A，直到墙钟 >= 300s
& $adb -s $D shell am force-stop com.xiufeigo.opencodeshell   # 结束交互，让耗电可归因
& $adb -s $D shell dumpsys batterystats com.xiufeigo.opencodeshell
```

一键复现：`pwsh -NoProfile -File research\measure\measure_4_battery.ps1`
（原始 dump：`research/measure/out/batterystats_full.txt`）

### 4.2 原始输出（节选，逐字）

```
Statistics since last charge:
  System starts: 0, currently on battery: false          <-- 关键
  Estimated battery capacity: 3000 mAh
  Time on battery: 0ms (0.0%) realtime, 0ms (--%) uptime
  Total run time: 5m 40s 59ms realtime, 5m 40s 59ms uptime
  Discharge: 0 mAh                                        <-- 关键
  Screen off discharge: 0 mAh
  Screen doze discharge: 0 mAh
  Screen on discharge: 0 mAh
  Start clock time: 2026-10-04-08-36-44
  Screen on: 0ms (--%) 0x, Interactive: 0ms (--%)

    Amount discharged (lower bound): 0
    Amount discharged (upper bound): 0
    Capacity: 3000, Computed drain: 0, actual drain: 0
  UID u0a227: 0.000418 bg: 0.000121 (1m 37s 238ms) fgs: 0.000297 (4m 1s 276ms)
  CPU scaling:  policy0: 1 2 policy1: 1 2 policy2: 1 2 policy3: 1 2
  CPU power brackets; cluster/freq in MHz(avg current in mA):
  x86_cpu_energy_attribution enabled: true
Total cpu time reads: 0

### Wake lock / CPU wakeup（节选）
  u0a230:
    Wake lock *launch* realtime
      -9s492ms: u0a227 [BFGS],
      -3m40s179ms: u0a227 [BTOP],
```

窗口执行记录（`out/battery_rounds.json`）：

```
  round 1: 0.0s   -> 56.4s
  round 2: 56.4s  -> 112.0s
  round 3: 112.0s -> 167.8s
  round 4: 167.8s -> 223.3s
  round 5: 223.3s -> 278.9s
  round 6: 278.9s -> 334.8s
### window actual = 334.8s over 6 rounds
```

### 4.3 数值表

| 指标 | 数值 | 状态 |
|---|---|---|
| **mAh（Discharge）** | **0 mAh** | ❌ **测不到**（原因见 4.4） |
| Screen on / off / doze discharge | 0 / 0 / 0 mAh | ❌ 同上 |
| Computed drain / actual drain | 0 / 0 | ❌ 同上 |
| `currently on battery` | **false** | ❌ 模拟器未接电池模型 |
| Time on battery | 0 ms | ❌ 同上 |
| 实测窗口墙钟 | **334.8 s（6 轮 SCRIPT-A）** | ✅ 可用 |
| Total run time | 5m 40s 59ms | ✅ 可用 |
| App UID CPU 占用率 | **0.000418 ≈ 0.0418 %** | ✅ **可用的耗电代理** |
| 其中 前台 | fgs: 0.000297（累计 4m 1s 276ms） | ✅ |
| 其中 后台 | bg: 0.000121（累计 1m 37s 238ms） | ✅ |
| CPU 唤醒 | u0a227 [BFGS] 9.492 s / [BTOP] 3m40s | ⚠️ 全局计数，含交互注入 |
| WakeLock（app 相关） | 仅 `*launch*`（u0a230） | ✅ **无长持 wakelock** |
| 各电源轨 uAms | **无输出**（`CPU power brackets` 为空） | ❌ 测不到 |

### 4.4 为什么 mAh 测不到（不是我没测，是这台模拟器不产生这个数）

三个互相印证的原因：

1. `currently on battery: false` —— 该 AVD 没开电池仿真，电源 HAL 按"插电"处理，
   所以所有 `Discharge` 字段恒为 `0`，`Time on battery` 恒为 `0ms`。
2. `CPU power brackets; cluster/freq in MHz(avg current in mA):` **后面是空的** ——
   x86_64 模拟器没有真实 PMIC / 电流采样，没有 rail 电流，
   因此**各电源轨 uAms 一条都没有**（脚本正则匹配 0 条）。
3. `Total cpu time reads: 0` + `Capacity: 3000, Computed drain: 0, actual drain: 0`
   —— 容量是标称常量 3000 mAh，drain 由 rail 电流积分得出，
   rail 为空 ⇒ 积分为 0。

**结论：本环境下"耗电 mAh / 各电源轨"两项硬性测不到。**
任何声称在此环境拿到 mAh 的数字都应当被怀疑。

### 4.5 在此环境下**真正可用**的耗电量法（建议 Web 壳用同一套）

既然 mAh 不可得，就把**可复现的相对量**定成尺子：

| 建议基线量 | 本次原生壳基线 | 为什么可靠 |
|---|---|---|
| 固定 300s 窗口内 **UID CPU 占用率** | **0.0418 %**（fgs 0.0297% + bg 0.0121%） | 直由 batterystats 给出，与 rail 无关 |
| 前台/后台累计时长 | fgs 4m1s / bg 1m37s | 可复算，能验证脚本负载是否一致 |
| 长持 wakelock 数量与时长 | **0 个长持**（仅 `*launch*`） | 出现长持 wakelock 才是真的漏电 |
| 窗口墙钟 | 334.8 s / 6 轮 | 负载一致性的自检 |

**Web 壳对比时**：用同样的 300s 窗口、同样 6 轮 SCRIPT-A，比 `UID CPU 占用率`
与 `wakelock 总时长`。若 Web 壳 CPU 占用率显著高于 0.0418%，
或出现 app 级长持 wakelock，即为"更费电"的量化证据。

---

## 5. 这些数将来怎么和 Web 壳对比

### 5.1 对比的四条铁律（同设备 / 同脚本 / 同项目 / 同轮次）

1. **同设备**：必须同为 `emulator-5604`（本文件的所有数字都出自它）。
   ⚠️ 指定的 `emulator-5554` 已死，**不要拿 5554 的历史数字来比**。
2. **同几何**：测量前先 `wm size 1366x768` + `wm density 160`，结束 `wm size reset`。
   几何不同 → 帧数与 p90/p99 完全不可比（Compose 组合数会变）。
3. **同脚本**：直接复用 `research/measure/common.ps1` 的 `ScriptA`，
   以及 `measure_1..4_*.ps1`。**不要重写交互**，否则负载不同，数字无意义。
4. **同轮次**：同一天、同一台机器、没有别的模拟器在跑（本次同时有 4 个模拟器在跑，
   5612/5620/5628 会抢 CPU/内存，是本基线噪声的主要来源之一）。

### 5.2 对比表（把 Web 壳的数填在右列）

| 量 | 命令 | 原生壳基线（本次） | Web 壳 | 判读 |
|---|---|---|---|---|
| 总帧数 | `gfxinfo` | **137** | 待测 | 帧数应同量级 |
| janky 帧率 | `gfxinfo` | **43.07%**（legacy 75.18%） | 待测 | **主对比项** |
| p50 帧时 | `gfxinfo` | **30 ms** | 待测 | 稳态流畅度 |
| p90 帧时 | `gfxinfo` | **750 ms** | 待测 | 长尾 |
| p95 帧时 | `gfxinfo` | **1050 ms** | 待测 | 长尾 |
| p99 帧时 | `gfxinfo` | **4150 ms** | 待测 | 最长尾（冷启动首绘） |
| Missed Vsync | `gfxinfo` | **30** | 待测 | |
| Slow UI thread | `gfxinfo` | **54** | 待测 | 主线程压力 |
| Slow issue draw cmds | `gfxinfo` | **48** | 待测 | |
| GPU p50 | `gfxinfo` | **11 ms** | 待测 | GPU 填充压力 |
| 首帧 TotalTime x3 | `am start -W` | **4122 / 3799 / 4500 ms**（均值 4140） | 待测 | **建议各跑 >=5 次取中位数** |
| 输入->帧完成 p50/p90 | `gfxinfo framestats` | 见 §3.5 | 待测 | 渲染半段 |
| 耗电 mAh | `batterystats` | **0 mAh = 测不到** | 同样测不到 | 不要拿它当对比项 |
| UID CPU 占用率 | `batterystats` | **0.0418 %** | 待测 | **耗电的替代对比项** |
| 长持 wakelock | `batterystats` | **0 个** | 待测 | 出现即漏电 |

### 5.3 怎么跑一轮 Web 壳对比

```powershell
# 1) 装 Web 壳（这一条超出本次并发纪律，需在 v163 完工后单独执行）
# 2) 测前把几何压到 harness 规格
$adb -s emulator-5604 shell wm size 1366x768
$adb -s emulator-5604 shell wm density 160
# 3) 用同一批脚本、只换包名
$env:MEASURE_DEV = 'emulator-5604'
# 编辑 common.ps1 里的 $Pkg 为 Web 壳包名
pwsh -NoProfile -File research\measure\measure_1_gfxinfo.ps1
pwsh -NoProfile -File research\measure\measure_2_firstframe.ps1
pwsh -NoProfile -File research\measure\measure_3_inputlatency.ps1
pwsh -NoProfile -File research\measure\measure_4_battery.ps1
# 4) 测后复原几何
$adb -s emulator-5604 shell wm size reset
$adb -s emulator-5604 shell wm density reset
```

### 5.4 判读注意事项（避免误读）

- **不要用 janky% 单点判胜负**：本基线 43.07% 里 p99 就有 4150 ms 的冷启动首绘长尾。
  建议同时看 **p50 + p90**，并把"冷启动帧"与"交互帧"分开统计。
- **一定要看 `Pipeline=` 行**：原生壳是 `Pipeline=Skia (OpenGL)`。
  WebView 壳的 HWUI 路径不同，GPU 侧数字天然不同源，**跨 pipeline 比 GPU 百分位无意义**。
- **首帧 4.1 s 是模拟器数**：不要拿它跟真机 Web 壳比。模拟器上 WebView 首启通常更慢，
  两者都在模拟器上才有可比性。
- **mAh 一栏两边都是"测不到"**：把它从对比表里划掉，改比 `UID CPU 占用率` 与 wakelock。

---

## 6. 未闭环 / 已知缺口

| # | 缺口 | 影响 | 下一步 |
|---|---|---|---|
| 1 | **指定设备 `emulator-5554` 已死**，改用 `emulator-5604` | **高**：历史 5554 数字与本基线不可比 | 重建 `Medium_Desktop` AVD 并复测，或正式把基线迁到 5604 |
| 2 | **耗电 mAh / 电源轨测不到**（模拟器无电池模型、无 rail 电流） | 中 | 上真机；或给 AVD 开电池仿真 + rail 注入后重测 |
| 3 | **端到端输入延迟测不到**（无后端 + 禁止装机） | 中 | 后端起来后，用同样脚本重跑并接入 framestats 口径 |
| 4 | **"长列表滚动"未真正覆盖**：首页无后端只有 4 行、滑动无位移 | 中 | 后端起来后注入 >50 条会话再测滚动 p90/p99 |
| 5 | **IME 弹出/键盘输入延迟未纳入** | 低 | 复用 §5 framestats 口径，加 `input tap` 到输入框 + `input text` 即可 |
| 6 | 首帧只跑 3 次，波动达 18% | 低 | 正式对比时各跑 >=5 次取中位数 |
| 7 | 同机同时跑着 4 个模拟器，是噪声源 | 低 | 正式基线时独占机器 |
| 8 | `Number High input latency=147 > 137 总帧` 的语义未逐版本核实 | 低 | 只作趋势参考，勿当帧数 |
| 9 | **测量中途 `emulator-5604` 被并发 lane 的模拟器 churn 杀掉**，12 次点击版输入延迟跑失败 | **高**：§5 只剩 6 帧小样本 | 设备稳定后重跑 `measure_3_inputlatency.ps1` |
| 10 | 采集期间同机模拟器反复增删（设备号从 5604/5612/5620/5628 变成 5620/5628/5636/5650） | 中 | 独占机器，或锁定单设备 |


---

## 7. 收尾清理 & 复现清单

### 7.1 `wm` 几何

| 项 | 状态 |
|---|---|
| 测量期间在 `emulator-5604` 设过 `wm size 1366x768` + `wm density 160` | 已设 |
| 计划复位到 `1366x768/160`（保持 harness 规格） | 无法执行 |
| 实际结果 | **`emulator-5604` 已被杀掉**（§6 缺口 #9），override 随进程一并消失，**当前无残留** |
| 是否动过别人的设备 | **没有**。`emulator-5636` 恰好自带 `Override size: 1366x768`，属另一 lane，**未触碰** |

> 复查命令：
> ```powershell
> $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
> & $adb devices                        # 确认 5604 已不在列表
> & $adb -s emulator-5604 shell wm size # device not found => 无残留
> ```

### 7.2 `/sdcard` 残留

本次只在 dump UI 时临时写过 3 个文件，**每次都已当场删除**：

| 文件 | 用途 | 处置 |
|---|---|---|
| `/sdcard/_m0.xml` | 首次 UI dump（定位可点击坐标） | 已 `adb shell rm` |
| `/sdcard/_d.xml` | 滑动/切页对比 dump（循环内） | 已 `adb shell rm` |
| `/sdcard/_n.xml` | New Session 页面 dump | 已 `adb shell rm` |

复查（4 台在世设备全部为空）：

```
$ adb -s <dev> shell "ls /sdcard/_m0.xml /sdcard/_d.xml /sdcard/_n.xml 2>/dev/null"
  emulator-5620: ''
  emulator-5628: ''
  emulator-5636: ''
  emulator-5650: ''
```

**无新增残留。** 本任务不产截图，未往 `/sdcard` 写任何图片。

### 7.3 reset 的影响（都是只读侧统计，符合任务书允许范围）

| reset | 影响面 | 结论 |
|---|---|---|
| `dumpsys gfxinfo <pkg> reset` | 只清该进程帧统计计数，不改持久状态、不改 App 数据 | 允许 |
| `dumpsys batterystats --reset` | 清全局电量/唤醒统计计数，不影响 App 与系统行为 | 允许 |
| `wm size/density` | 改显示 override | 已随设备消失，无残留 |
| 装机 / gradle | **一律没做** | 合规 |

### 7.4 复现清单（本目录文件）

| 文件 | 作用 |
|---|---|
| `common.ps1` | 共享 harness：设备/包名/坐标常量、`ScriptA` 固定交互、`EnsureForeground` |
| `measure_1_gfxinfo.ps1` | 量一：gfxinfo 卡顿/百分位 |
| `measure_2_firstframe.ps1` | 量二：`am start -W` 首帧 x3 |
| `measure_3_inputlatency.ps1` | 量三：framestats 输入→帧完成代理 |
| `measure_4_battery.ps1` | 量四：300s 固定窗口 batterystats |
| `BASELINE_NATIVE.md` | 本报告 |
| `out/gfxinfo_full.txt` | 量一原始 dump |
| `out/amstart_1..3.txt` | 量二原始输出 |
| `out/framestats_probe.txt` + `framestats_probe_parsed.csv` | 量三原始 + 解析 |
| `out/batterystats_full.txt` | 量四原始 dump |
| `out/battery_rounds.json` | 5 分钟窗口轮次时间线 |
| `out/firstframe.json` | 量二结构化结果 |
| `out_measure1..4.log` | 四个脚本的控制台日志 |

一条龙复现（换包名即可测 Web 壳）：

```powershell
$env:MEASURE_DEV = 'emulator-5604'
# 先按 5.3 把几何压到 1366x768 / 160
pwsh -NoProfile -File research\measure\measure_1_gfxinfo.ps1
pwsh -NoProfile -File research\measure\measure_2_firstframe.ps1
pwsh -NoProfile -File research\measure\measure_3_inputlatency.ps1
pwsh -NoProfile -File research\measure\measure_4_battery.ps1
# 收工复原几何
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5604 shell wm size reset
& $adb -s emulator-5604 shell wm density reset
```

---

## 8. 一页速查（原生壳基线）

| 量 | 数字 | 可用性 |
|---|---|---|
| **janky 帧率** | **43.07 %**（59/137；legacy 75.18%） | 主基线 |
| 帧时 p50 / p90 / p95 / p99 | **30 / 750 / 1050 / 4150 ms** | 可用 |
| **首帧 TotalTime x3** | **4122 / 3799 / 4500 ms**（均值 4140，全 COLD） | 可用 |
| 输入→帧完成 p50 | **约 90 ms**（n=6，小样本，量级参考） | 需重测 |
| **耗电 mAh** | **0 mAh = 测不到**（模拟器无电池模型/无 rail 电流） | 不可用 |
| 耗电替代量：UID CPU | **0.0418 %**（fgs 0.0297% + bg 0.0121%） | 可用 |
| 长持 wakelock | **0 个** | 可用 |
| 显示 | 1366x768 / density 160 / 60 Hz | 可用 |


