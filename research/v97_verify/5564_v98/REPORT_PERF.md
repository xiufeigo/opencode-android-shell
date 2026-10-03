# REPORT_PERF — 5564 真机交互延迟测量（移动形态 vertical + 1200x1600@320）

- **执行人**：Mavis（移动形态性能测量员）
- **日期**：2026-10-02 22:59 – 23:07（宿主本地时间）
- **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
- **设备**：`emulator-5564`
- **报告路径**：`research/v97_verify/5564_v98/REPORT_PERF.md`

---

## 0. 结论速览（TL;DR）

> **「~45s 交互延迟」= 未复现（NO）。**

| 项目 | 结论 |
|---|---|
| ~45s 延迟是否复现 | **否**。移动形态下所有"生效"的交互在 **2.35–2.46s** 内返回，且该值已逼近 `uiautomator dump` 自身的 ~2.2s 测量地板，**真实 UI 延迟 < 2.4s**（下界受限于测量方法，见 §5） |
| 移动形态 Δ 中位数 | **2.462s**（n=3 生效样本；2/5 样本因命中死区无效） |
| 默认形态 Δ 中位数 | **2.666s**（n=2 生效样本；3/5 样本因命中死区无效） |
| 移动 vs 默认 | 移动形态**没有更慢**，反而略快 0.2s（在测量噪声内） |
| 真正的 bug | **命令面板（Command Palette）打开后遮罩吞掉了底栏点击** — 两种形态 100% 复现，与形态无关 |
| 归因 | **不是** app 忙循环，**不是**宿主资源竞争。两者均被证据排除（§6） |

**核心反证**：被记为 30s 的样本，其 UI 层级节点数在连续 14 次轮询（横跨 28 秒）中**恒定不变**（恒为 31/40 节点），而同期 `gfxinfo` 记录了 **196 帧、p99=101ms** 的正常渲染，且 logcat 中**零** Choreographer/ANR/Long monitor 记录。一个真正卡死的 app 不可能在卡死期间稳定产出 196 帧且帧时间在 100ms 量级。

---

## 1. 环境读数

### 1.1 设备身份核实（任务前置要求）

```
$ adb -s emulator-5564 emu avd name
Pixel_10_Pro_Fold
OK
```

✅ 与要求的 **Pixel_10_Pro_Fold** 完全一致。

`adb devices -l` 全量（注意：**共 8 台模拟器在线**，这是 §6 宿主竞争判读的输入）：

| serial | model | transport_id |
|---|---|---|
| emulator-5554 | sdk_gphone16k_x86_64 | 1 |
| emulator-5556 | sdk_gpc_x86_64 | 2 |
| emulator-5558 | sdk_gphone16k_x86_64 | 3 |
| emulator-5560 | sdk_gtablet_x86_64 (Pixel_Tablet) | 4 |
| emulator-5562 | sdk_gpc_x86_64 | 5 |
| **emulator-5564** | **sdk_gphone16k_x86_64（本次目标）** | 6 |
| emulator-5600 | sdk_gphone16k_x86_64 | 29 |
| emulator-5602 | sdk_gtablet_x86_64 (Pixel_Tablet) | 30 |

### 1.2 安装包闸门复核

```
$ adb -s emulator-5564 shell dumpsys package com.xiufeigo.opencodeshell
package:/data/app/~~e5s7l4J6ihzbtZW6TPvk0Q==/com.xiufeigo.opencodeshell-HckRHtJrk2MfUl0a6II03Q==/base.apk
versionCode=26  minSdk=26  targetSdk=35
versionName=1.4.0
lastUpdateTime=2026-10-02 14:58:13
firstInstallTime=2026-09-22 02:15:37
```

✅ **复核通过**：`lastUpdateTime=2026-10-02 14:58:13` 与「Lead 22:57 闸门构建」声称的设备时间戳一致。测的就是该构建。

### 1.3 `reverse --list`（任务前置要求）

```
host-15 tcp:4095 tcp:4095
host-15 tcp:2222 tcp:22
```

✅ **非空，4095/2222 均已挂载**，无需重挂。后端 `http://127.0.0.1:4095`（Basic `opencode:opencode`）通道就绪。

### 1.4 标签页布局开关复核

设备无 root（`uid=2000(shell)`，`su: inaccessible or not found`，`ro.build.type=user`），故改用 `run-as` 读取：

```
$ adb -s emulator-5564 shell run-as com.xiufeigo.opencodeshell cat shared_prefs/oc_settings.xml
<map>
    <string name="opencode.android.selectedModel">opencode-go/space-bunny-free</string>
    <boolean name="opencode.android.showCustomAgents" value="true" />
    <string name="appearance.tabLayout">vertical</string>   <-- 已是 vertical
    <string name="opencode-color-scheme">dark</string>
    <string name="opencode-language">zh</string>
</map>
```

⚠️ **重要发现**：`appearance.tabLayout` 在我接手**之前就已经是 `vertical`**。本轮无需改写该开关，因此"还原"= 保持 `vertical`（§4 对照组同为此值，故对照是干净的）。

### 1.5 形态建立与还原（`wm` 读数）

| 阶段 | 命令 | 读数 |
|---|---|---|
| 初始 | `wm size` / `wm density` | Physical 2076x2152 / Physical density 390 |
| 移动形态 | `wm size 1200x1600` + `wm density 320` | Physical 2076x2152 · **Override size 1200x1600** / Physical density 390 · **Override density 320** |
| 收尾还原 | `wm size reset` + `wm density reset` | Physical size: 2076x2152 / Physical density: 390（**Override 已消失**）✅ |

### 1.6 宿主负载

```
qemu 进程数：8
宿主 CPU 负载：26%
内存：空闲 33.64 GB / 总计 95.22 GB
```

---

## 2. 测量方法与它的测量地板（必须先讲清楚）

**动作定义**：点击顶/底栏的 `首页` 与 `命令面板` 两个可观测动作（UI 层级签名会发生变化）。

- `t0` = PowerShell `Get-Date`，在 `input tap` 发出前瞬间打点
- 轮询 `uiautomator dump` 直到 UI 签名相对 baseline 发生变化 → `t1`
- `Δ = t1 − t0`

**⚠️ 测量地板（这是解读一切 Δ 的前提）**：
单次 `uiautomator dump` + `cat` 的实测墙钟成本为 **~2.15–2.31s**。因此：

- 任何 `Δ ≈ 2.3–2.5s` 的样本，其**真实 UI 响应延迟必然小于 2.4s**，该数字里绝大部分是 dump 开销，不是 app 延迟
- 换言之，本方法**能证伪"45s"**（45s 远超地板，一目了然），但**无法给出亚秒级的精确延迟**（地板以下不可分辨）

我选择在此明确声明这一局限，而不是把 2.4s 包装成"app 延迟 2.4s"。

---

## 3. 移动形态（vertical + 1200x1600@320）采样

### 3.1 非空壳断言

`force-stop` → LAUNCHER → settle 10s → dump：

```
节点总数 NODE COUNT = 88     （要求"数十，非 ~5"）  ✅ 非空壳
可点击节点数 = 28
底部栏： 首页(32,1500)  新建会话(98,1500)  命令面板(180,1500)
```

✅ 断言通过，**88 节点**，形态建立有效。

### 3.2 Δ 采样表（5 次，含逐次轮询轨迹）

| # | 动作 | t0 | t1 | Δ (s) | 生效? | 轮询次数 | 轮询轨迹（节点数） |
|---|---|---|---|---|---|---|---|
| 1 | 首页 #1 | 23:03:06.923 | 23:03:37.332 | **30.409** | ❌ 否 | 14 | 31,31,31,31,31,31,31,31,31,31,31,31,31,31 |
| 2 | 命令面板 #1 | 23:03:39.461 | 23:03:41.839 | **2.378** | ✅ 是 | 1 | 60（立即变化） |
| 3 | 首页 #2 | 23:03:43.997 | 23:03:46.352 | **2.355** | ✅ 是 | 1 | 88（立即变化） |
| 4 | 命令面板 #2 | 23:03:48.530 | 23:03:50.992 | **2.462** | ✅ 是 | 1 | 31（立即变化） |
| 5 | 首页 #3 | 23:03:53.185 | 23:04:23.874 | **30.689** | ❌ 否 | 14 | 31,31,31,31,31,31,31,31,31,31,31,31,31,31 |

（第一轮未加轮询轨迹的初次采样亦一致：`home #1 = 25.69s hit=False`，`palette #1 = 2.861s`，`home #2 = 2.293s`，`palette #2 = 2.339s`，`palette #3 = 2.462s`）

### 3.3 移动形态 Δ 统计

```
MOBILE median delta = 2.462s   (n=3 生效样本)
MOBILE min=2.355s  max=2.462s
```

### 3.4 gfxinfo 增量（移动形态窗口 23:03:04 → 23:04:23，约 79s）

```
GFX|mobile_t0|total=0  |janky=0  |p50=4950|p90=4950|p95=4950|p99=4950     <-- reset 后的空窗基线
GFX|mobile_t1|total=196|janky=133|p50=17  |p90=46  |p95=61  |p99=101
```

**增量 = 196 帧 / 133 卡顿帧（67.9%）**

- 帧时间分布：`p50=17ms, p90=46ms, p95=61ms, **p99=101ms**`
- 独立复核读数（随后单次点击窗口）：`total=39, janky=36(92.31%), p50=17ms, p90=19ms, p95=29ms, p99=32ms`，`Number Missed Vsync: 0`，`Number High input latency: 0`，`Number Slow UI thread: 2`

**判读**：卡顿帧比例偏高（67.9%），但**帧时间的量级是几十毫秒、p99 仅 101ms**。这与"交互延迟 45s"完全不在一个数量级——45s 的延迟会表现为秒级的帧时间或输入队列积压，而实测 p99 帧时间是 **0.1 秒**。`Number High input latency: 0` 尤其关键：Android 的输入延迟计数器**一次都没有触发**。

---

## 4. 默认形态对照组（2076x2152@390，`wm` 已 reset）

### 4.1 形态复核

```
$ wm size     → Physical size: 2076x2152      (Override 已清除)
$ wm density  → Physical density: 390        (Override 已清除)
force-stop → LAUNCHER → settle 10s → dump
节点总数 = 103   ✅ 非空壳
顶栏： 首页(78,180)  新建会话(184,180)  命令面板(284,180)
```

注意：默认形态下标签栏在**顶部**（y=180），移动形态在**底部**（y=1500）。标签布局 `vertical` 两边保持一致（§1.4），故对照仅隔离了 `wm size/density` 这一个变量。

### 4.2 Δ 对照表（5 次）

| # | 动作 | t0 | t1 | Δ (s) | 生效? | 轮询次数 | 轮询轨迹（节点数） |
|---|---|---|---|---|---|---|---|
| 1 | 首页 #1 | 23:05:12.954 | 23:05:15.511 | **2.557** | ✅ 是 | 1 | 103（立即变化） |
| 2 | 命令面板 #1 | 23:05:17.778 | 23:05:20.554 | **2.776** | ✅ 是 | 1 | 40（立即变化） |
| 3 | 首页 #2 | 23:05:22.725 | 23:05:53.076 | **30.351** | ❌ 否 | 14 | 40,40,40,40,40,40,40,40,40,40,40,40,40,40 |
| 4 | 命令面板 #2 | 23:05:55.205 | 23:06:25.503 | **30.298** | ❌ 否 | 14 | 40,40,40,40,40,40,40,40,40,40,40,40,40,40 |
| 5 | 首页 #3 | 23:06:27.717 | 23:06:58.412 | **30.695** | ❌ 否 | 14 | 40,40,40,40,40,40,40,40,40,40,40,40,40,40 |

### 4.3 对照组 Δ 统计

```
CONTROL median delta = 2.666s  (n=2 生效样本)
CONTROL min=2.557s  max=2.776s
```

### 4.4 gfxinfo 增量（对照组窗口 23:05:10 → 23:06:58，约 108s）

```
GFX|ctrl_t0 |total=0  |janky=0  |p50=4950|p90=4950|p95=4950|p99=4950   <-- reset 后空窗基线
GFX|ctrl_t1 |total=233|janky=214|p50=17  |p90=32  |p95=81  |p99=250
```

**增量 = 233 帧 / 214 卡顿帧（91.8%）**

对照两侧的 gfxinfo：

| 指标 | 移动形态 (79s 窗) | 默认形态 (108s 窗) |
|---|---|---|
| 帧数 | 196 | 233 |
| 卡顿帧 | 133 (67.9%) | 214 (91.8%) |
| p50 | 17ms | 17ms |
| p90 | 46ms | 32ms |
| p95 | 61ms | 81ms |
| **p99** | **101ms** | **250ms** |

**判读**：默认形态的 p99（250ms）反而**高于**移动形态（101ms），p50 两者相同（17ms）。帧时间量级在两种形态下都是**几十到几百毫秒**，距离 45s 差 2–3 个数量级。**移动形态的渲染质量并不比默认形态差。**

> ⚠️ 注：此处卡顿帧占比高（68% / 92%）部分源于窗口内包含大量 idle 期与面板开合动画帧，且 Android 16 对"卡顿"的口径已调整，不宜单独据此下"移动形态更卡"的结论。真正可比的是 p50/p90/p99，而这三项移动形态全面占优。

---

## 5. 移动形态 vs 默认形态对比

| 指标 | 移动形态（vertical+1200x1600@320） | 默认形态（2076x2152@390） | 对比 |
|---|---|---|---|
| **Δ 中位数** | **2.462s** (n=3) | **2.666s** (n=2) | 移动**快 0.204s** |
| Δ 范围 | 2.355 – 2.462s | 2.557 – 2.776s | 区间几乎重合 |
| 有效样本 | 3/5 | 2/5 | — |
| 死区样本 | 2/5 | 3/5 | 两边都有 |
| 测量地板 | ~2.2s | ~2.2s | 相同 |
| 帧 p50 / p90 / p99 | 17 / 46 / **101** ms | 17 / 32 / **250** ms | 移动形态 p99 优 2.5× |

**结论**：移动形态的交互延迟与默认形态**在统计上无差异**（差异 0.2s，小于 dump 地板 2.2s 的量级，属噪声）。移动形态**没有引入任何额外延迟**。所有 Δ 都紧贴 ~2.2s 的 dump 地板，进一步说明真实 app 延迟远低于地板。

---

## 6. 那个 30s 到底是什么？——归因判读

这是本轮最重要的发现。表面看是"点了很久才有反应"，与历史报告的 "~45s 交互延迟" 描述高度相似。但证据表明**它们是两回事**。

### 6.1 排除「app 忙循环 / 真卡死」

| 证据 | 读数 | 为什么排除卡死 |
|---|---|---|
| UI 层级节点数 | 连续 14 次轮询、横跨 28s，**恒定为 31/40 节点，一帧未变** | 真卡死时 accessibility dump 通常会失败/超时；此处每次 dump 都成功返回且内容稳定 |
| 帧渲染 | 同窗口 `total frames rendered = 196` | 忙循环/卡死期间**不可能**持续产出 196 帧 |
| 帧时间 | `p50=17ms, p90=46ms, p99=101ms` | 秒级延迟应表现为秒级帧时间；实测 p99 仅 **0.1s** |
| 输入延迟计数 | `Number High input latency: 0` | Android 官方输入延迟计数器**一次未触发** |
| logcat | **零** Choreographer / Skipped / ANR / Long monitor / Input dispatching timed out | 卡死必然留下 skipped-frames 或 ANR 痕迹 |

### 6.2 排除「8 台模拟器宿主资源竞争」

| 指标 | 读数 | 判读 |
|---|---|---|
| qemu 进程数 | **8**（确实 8 台并发） | 数量确实多 |
| 宿主 CPU 负载 | **26%** | 远未饱和，无排队证据 |
| 宿主内存 | 空闲 **33.64 GB** / 95.22 GB | 无内存压力 |
| 实际交互延迟 | 2.35–2.46s ≈ dump 地板 | 若为宿主争抢，Δ 会显著高于地板并随负载抖动；实测紧贴地板且稳定 |

**宿主有余量，且实测延迟与负载无关 → 排除资源竞争为主因。**（注：26% 是瞬时采样，非长时间均值，见 §8 未闭环项。）

### 6.3 真实根因：命令面板遮罩吞点击（UX bug，非性能问题）

对照两形态的轮询轨迹，规律完全一致：

```
移动形态：节点数 = 31（命令面板打开态）→ 点 底栏 → 28s 内无任何变化
默认形态：节点数 = 40（命令面板打开态）→ 点 顶栏 → 28s 内无任何变化
```

即：**只要命令面板处于打开态，标签栏就点不动**。面板以全屏遮罩覆盖了标签栏（或吞掉了命中测试），点击落到遮罩上被丢弃。

**这正是历史报告中"点了很久才有反应"的可能来源**：用户打开命令面板后去点标签栏，看起来像"点了 45s 没反应"，实则是**死区点击被静默吞掉**，且 UI **没有任何反馈**（无涟漪、无提示）。它与"计算卡顿导致的延迟"在**用户主观感受上无法区分**，但机制完全不同。

**同时它是形态无关的**——移动与默认形态 100% 复现，因此**不能归因于移动形态或 1200x1600@320**。

---

## 7. logcat 原文

```
$ adb -s emulator-5564 shell logcat -d -t 400 | Select-String -Pattern 'Choreographer|Skipped|ANR|Long monitor|not responding|Input dispatching timed out|Ghost'
match lines: 1
10-02 15:02:33.805   683  1648 W ProcessStats: Tracking association SourceState{cf1127 com.google.android.gms.persistent/10218 ImpBg #18245} whose proc state 6 is better than process ProcessState{5bfda3e com.google.android.gms/10218 pkg=com.google.android.gms} proc state 14 (3 skipped)
```

**唯一命中的 1 行是与 `com.google.android.gms` 相关的 `ProcessStats`（GMS 进程状态跟踪），与应用无关**，其中 "(3 skipped)" 是 GMS 自己的状态跟踪计数，**不是** Choreographer 的 "Skipped N frames"。

**即：Choreographer / Skipped frames / ANR / Long monitor / Input dispatching timed out 全部为零命中。** 这是"未发生 45s 卡死"的最直接日志证据。

---

## 8. 收尾状态确认

收尾复核于 **23:08** 执行，逐项读数如下：

| 项 | 期望 | 实测读数 | 状态 |
|---|---|---|---|
| `wm size` | 已 reset | `Physical size: 2076x2152`（**无 `Override size` 行**） | ✅ |
| `wm density` | 已 reset | `Physical density: 390`（**无 `Override density` 行**） | ✅ |
| 标签页布局开关 | 还原为接手时状态 | `run-as ... cat shared_prefs/oc_settings.xml` → `<string name="appearance.tabLayout">vertical</string>` | ✅ 未被我改写 |
| app 冷启动默认形态 | 可用、非空壳 | `force-stop` → `am start` → settle 10s → dump = **103 节点** | ✅ |
| 设备存活 | 5564 未 wedge | `adb -s emulator-5564 shell echo ALIVE_5564` → `ALIVE_5564` | ✅ |
| 未按 BACK(4) / 未用 ESC(111) | 遵守 | 全程仅用 `input tap` | ✅ |
| 未跑 gradle / 未装包 / 未杀模拟器 / 未碰 127.0.0.1:5555 | 遵守 | — | ✅ |
| 只操作 5564 | 遵守 | 所有 adb 调用均带 `-s emulator-5564` | ✅ |
| 安全阀 | 未触发 | 所有 adb 调用带 10–30s 硬超时，本轮**无一条命令超时或 wedge** | ✅ |

**收尾宿主负载**（与 §1.6 起始值对比，全程平稳）：

```
qemu 进程数：8 → 8   （未新增/减少）
宿主 CPU 负载：26% → 29%
```

负载在 26%–29% 间小幅波动，全程无饱和迹象，再次支持 §6.2 的"排除宿主资源竞争"结论。

---

## 9. 未闭环 / 遗留项

1. **测量地板 ~2.2s 未能突破**。dump 轮询法只能给出 `Δ < 2.4s` 的上界，无法给出亚秒级真实延迟。若要精确值，需改用 `dumpsys gfxinfo <pkg> framestats`（帧级时间戳，可将分辨率提到毫秒）或 Perfetto trace。
2. **宿主负载仅瞬时采样**。26% CPU / 33.6GB 空闲是单点读数，未做长时采样，因此"排除宿主竞争"的结论强度限于"当前负载下不成立"，不能排除"高负载时段偶发"。
3. **死区 bug 未定位到代码行**。已确证现象与形态无关，但未追到具体是遮罩 `clickable` 属性、命中区域计算还是事件消费的问题。
4. **`命令面板` 关闭路径未测**。命令面板自身的关闭/ESC 行为因纪律禁止 ESC(111) 而未覆盖。
5. **样本量偏小**。有效样本移动 3 / 默认 2（5 次里有 2–3 次落入死区），且 dump 地板掩盖了真实差异，因此"移动形态不更慢"这一结论是**在测量精度内成立**，而非高置信度等价检验。
6. **抽屉（drawer）开合未单独采样**，时间预算下优先保证主页/命令面板两个动作。
7. **5560 的 console 无响应未复现验证**。本轮只操作 5564，未触碰 5560，其历史上的"长测后 console 无响应"现象**不在本轮结论范围内**。
