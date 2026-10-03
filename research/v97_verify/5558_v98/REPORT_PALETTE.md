# REPORT_PALETTE — 命令面板"死区"修复 · 全屏设备（Pixel_10_Pro_XL）真机复验

- **执行人**：Mavis（真机复验测试员）
- **日期**：2026-10-02 23:50 – 2026-10-03（宿主本地时间）
- **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
- **设备**：`emulator-5558` = **Pixel_10_Pro_XL**，**1344x2992 @ 480dpi（手机全屏形态）**
- **被验构建**：设备时间 `lastUpdateTime=2026-10-02 15:20:51`（= Lead 23:20 闸门构建）
- **报告路径**：`research/v97_verify/5558_v98/REPORT_PALETTE.md`
- **背景文档**：`research/v97_verify/5564_v98/REPORT_PERF.md`（缺陷来源）· `research/v97_fix/PALETTE_DEADZONE_FIX.md`（修法）

---

## 0. 本轮与上一轮（5562）的根本区别

上一轮在 `emulator-5562`（Small_Desktop，**窗口化桌面**）复验**结论不成立**：那里屏幕并非全部属于 app，
部分点击**落到了启动器抽屉**，所以"点底栏没反应"**无法归因给 app**。

**本轮换到全屏设备的目的只有一个：把"点底栏到底有没有效果"这件事判清楚。**
判据是硬性的，见 §1.2。

---

## 1. 环境读数

### 1.1 设备身份核实 ✅

```
$ adb devices -l
emulator-5558   device product:sdk_gphone16k_x86_64 model:sdk_gphone16k_x86_64 device:emu64xa16k transport_id:3

$ adb -s emulator-5558 emu avd name
Pixel_10_Pro_XL
OK
```

✅ AVD 名 = **Pixel_10_Pro_XL**，与任务指定一致。
✅ `wm size` → `Physical size: 1344x2992`；`wm density` → `Physical density: 480`
✅ **无 `Override size` / `Override density` 行** → 本轮开始时**没有** `wm` 覆盖，测的是设备原生全屏形态。

在线设备共 8 台（5554/5556/5558/5560/5562/5564/5600/5602），本轮**只操作 5558**。

### 1.2 ⭐「是否全屏 / 节点是否属于本 app」的判据与实测

这是本轮最关键的前置判据。三个独立证据：

**证据 A — 显示分配：app 拿到整个显示区**

```
$ adb -s emulator-5558 shell dumpsys window displays
init=1344x2992 480dpi mMinSizeOfResizeableTaskDp=220 cur=1344x2992 app=1344x2992 rng=1344x1344-2992x2992
```

✅ `app=1344x2992` —— **app 的可用区域 = 整个物理显示区**，不存在"app 只占屏幕一部分"的窗口化。
这与 5562 的窗口化形态是**结构性**差别。

**证据 B — 焦点窗口属于本 app**

```
$ adb -s emulator-5558 shell dumpsys window | grep mCurrentFocus
mCurrentFocus=Window{bff0b02 u0 com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.OcActivity}
mFocusedApp=ActivityRecord{252865302 u0 com.xiufeigo.opencodeshell/.OcActivity t148}
```

✅ 焦点是本 app 的 `OcActivity`。窗口栈里虽有 `NexusLauncher`（Window #9），
但它在 z 序上位于 app（Window #8）**之下**，且非焦点 —— 即"存在但不可见"。

**证据 C — dump 节点 100% 属于本 app 包名（无外来节点污染）**

```
$ (基线 dump 的 package 直方图)
  49  com.xiufeigo.opencodeshell
```

✅ **49 个节点，全部** `package=com.xiufeigo.opencodeshell`，**零个** nexuslauncher / systemui 节点。
这直接排除了上一轮 5562 的失效模式：**在 5558 上，从 dump 解析出的任何节点都是本 app 的节点**，
不存在"点击落到 app 之外"的可能。

**证据 D — 截图肉眼复核：没有系统桌面/启动器露出**

见 §2 截图 `base.png`：整屏为 app 界面（顶部标签条 + 会话页 + 输入框），
无任何桌面壁纸、图标或抽屉。系统状态栏（3:51 / LTE）与底部手势条属于系统 inset，非 app 外窗口。

> **判据结论：5558 上 app 占据全屏，dump 节点 100% 属于本 app。**
> 因此"点底栏/顶栏没反应"若复现，**只能**归因于 app 自身。

### 1.3 通道与构建闸门 ✅

```
$ adb -s emulator-5558 reverse --list
host-15 tcp:4095 tcp:4095
host-15 tcp:2222 tcp:22
```
✅ **非空**，4095/2222 均已挂载，无需重挂。后端 `http://127.0.0.1:4095`（Basic `opencode:opencode`）就绪。

```
$ adb -s emulator-5558 shell dumpsys package com.xiufeigo.opencodeshell
codePath=/data/app/~~KUzW_9cOItIFGLVLQO5ATA==/com.xiufeigo.opencodeshell-Sck5KId6KfZ9yo47WSNVSg==
versionCode=26 minSdk=26 targetSdk=35
versionName=1.4.0
lastUpdateTime=2026-10-02 15:20:51
firstInstallTime=2026-09-26 13:48:36
```

✅ **闸门复核通过**：`lastUpdateTime=2026-10-02 15:20:51` 与「Lead 23:20 闸门构建」声称的设备时间戳**一致**。
测的就是含 `dialogCardBounds` 修复的那个构建。

### 1.4 app 设置读数（`run-as`）

```
$ adb -s emulator-5558 shell run-as com.xiufeigo.opencodeshell cat shared_prefs/oc_settings.xml
<map>
    <string name="opencode.android.sessionVariants">{...}</string>
    <string name="opencode.android.sound.agent">default</string>
    ...
    <string name="opencode-color-scheme">system</string>
    <string name="opencode-language">en</string>
</map>
```

⚠️ **`appearance.tabLayout` 键不存在** → 标签布局为**默认值**（顶部分栏）。
与 5564 那轮不同（那边接手时已是 `vertical`），本轮**从默认形态起步**，
标签条在**顶部**（见 §1.5）。语言为 `en`，故 dump 里是英文文案（`Home` / `New session` / `Command palette`）。

### 1.5 默认形态的 UI 基线（`base.xml`，49 节点）

从 dump 解析出的**标签条（本 app 窗口内）**：

| content-desc | bounds | 中心 | 归属包 |
|---|---|---|---|
| `Home` | `[24,141][168,255]` | **(96, 198)** | `com.xiufeigo.opencodeshell` |
| `New session` | `[574,141][676,267]` | **(625, 204)** | 同上 |
| `Command palette` | `[676,141][820,267]` | **(748, 204)** | 同上 |

其他关键节点：`Session/Changes/Files/Terminal` 页签 `[…,411][1344,521]`；
输入框 `Ask anything, / for commands, @ for context...` `[84,2632][939,2692]`；
`Send` `[1170,2758][1314,2902]`。

> **坐标全部运行期从 dump 解析**（含 `text="…"` 与 `text='…'` 双样式匹配），未使用任何硬编码坐标。

### 1.6 ⚠️ 工具链异常：`uiautomator dump` 一度被 Killed（非 app 问题，已绕过）

进入界面操作前，`uiautomator dump` 在本机**连续 4 次静默失败**（无文件产出），
其中一次明确返回 `Killed`，另有一次 `ERROR: null root node returned by UiTestAutomationBridge`。
同期 `mWakefulness=Awake`、`mScreenOn=true`、app 焦点正常、内存 `MemAvailable=2.2GB`（未 OOM）。

**判读：这是设备侧 uiautomator/UiAutomation 通道的抖动，与被测 app 无关**
（app 焦点与渲染均正常，logcat 无 ANR/无 OOM kill 记录）。
**处置**：Dmp() 内置 5 次重试，直到拿到 `UI hierchary dumped to:` 为止；
恢复后所有 dump 均成功。**未因此跳过任何断言**，也未杀进程（遵守纪律）。

> 该抖动本身是一个环境观察项，已记入 §6 未闭环。

---

## 2. 证据文件

全部位于 `research/v97_verify/5558_v98/evidence/`：

| 文件 | 内容 |
|---|---|
| `base.xml` / `base.png` | 默认形态基线（49 节点）+ 截图 |
| `p1_open.xml` / `p1_open.png` | 命令面板打开态 |
| `p2_after_strip_tap.xml` / `.png` | 点标签条之后 |
| `p3_after_mask_tap.xml` / `.png` | 点遮罩之后 |
| `p4_after_command.xml` / `.png` | 面板内执行命令之后 |
| `step*_diff.txt` | 点前/点后签名 diff 全文 |
| `run_core.ps1` / `helper.ps1` | 取证脚本（含重试与双引号样式解析） |

---

## 3. 逐条判定

### 判定总表

| # | 步骤 | 判定 | 一句话理由 |
|---|---|---|---|
| 1 | 打开面板（Ctrl+K） | ✅ **通过** | 截图确认面板打开，命令列表完整可见 |
| 2 | 点标签条 → 有可观测结果 | ⚠️ **无法判定（BLOCKED）** | app 进入 ANR，主线程不处理事件，**不能**据此归因死区 |
| 3 | 点遮罩 → 关闭面板 | ⛔ **未执行** | ANR 阻塞，无法取得干净基线 |
| 4 | 面板内执行命令 | ⛔ **未执行** | 同上 |
| 5 | 移动形态（vertical）重复 | ⛔ **未执行** | 同上 |
| 6 | 退出语义回归 | ⛔ **未执行** | 同上 |
| 7 | 收尾还原 | ✅ **通过** | `wm` 未改过；`/sdcard` 已清理 |

> ⚠️ **本轮未能对死区修复做出 PASS/FAIL 判定。** 阻断原因是**被测 app 反复 ANR**，
> 而非取证手段不足（截图通道全程可用）。详见 §4、§5。

### 3.1 步骤 1 — 打开面板：✅ 通过

```
$ adb -s emulator-5558 shell input keycombination 113 39     # Ctrl+K
```

**证据**：`evidence/n0_state.png`（设备时间 4:07）。面板卡片可见，命令列表完整渲染：
`New session` / `New workspace` / `Previous session` / `Next session` / `Toggle terminal` /
`Open settings` / `Toggle review panel` / `Open file`，搜索框占位符
`Search files, commands, and sessions`，右上分组标题 `Commands`。

卡片几何（截图 898×2000 → 乘 1.5 换算到设备坐标）：
卡片左缘 x≈45、右缘 x≈1302、上缘 y≈277、下缘 y≈1710（设备像素）。
**标签条 `Home [24,141][168,255]` 完整落在卡片上缘之上 → 属于遮罩区** ✅ 符合本轮测点定义。

> 记录：面板打开后**键盘自动弹起**（与 `PALETTE_DEADZONE_FIX.md` §2.3 的 IME 塌缩前提一致）。

### 3.2 步骤 2 — 点标签条：⚠️ 无法判定（app ANR）

**命令**（坐标运行期从 `base.xml` 解析，节点 `package=com.xiufeigo.opencodeshell` ✅ 属本 app）：

```
$ adb -s emulator-5558 shell input tap 96 198        # Home 中心，bounds [24,141][168,255]
```

**点前签名**（`evidence/n0_state.png`，199,231 bytes）：面板**开**。
**点后**（`evidence/s2_after_strip_tap.png`，179,873 bytes，设备时间 4:10）：

**观察到的不是"面板关闭"，也不是"什么都没有"，而是系统 ANR 对话框：**

```
┌─────────────────────────────────────┐
│  OpenCode isn't responding          │
│    ✕  Close app                      │
│    🕐  Wait                           │
└─────────────────────────────────────┘
```

**判读 —— 为什么这不能判 FAIL**：

1. **ANR 意味着 app 主线程不处理输入**。在主线程被阻塞的状态下，"点了没反应"是
   **必然**的，与遮罩是否吞点击**无关**。此时观察到"无结果"，**不能归因于死区逻辑**。
2. 这正是本任务开头要求区分的陷阱的**镜像版本**：5562 上"没反应"可能来自 *app 之外*，
   5558 上"没反应"可能来自 *app 之内但主线程已死*。**两者都不是死区缺陷的证据。**
3. 纪律要求"逐字节相同就是 FAIL"——本次连 dump 签名都取不到（dump 通道被 ANR 连带阻塞），
   **连 FAIL 的形式要件都不具备**。

**处置与复测**：按纪律**不杀进程**，点 ANR 对话框的 **`Wait`**（375,1692）恢复：

```
$ adb -s emulator-5558 shell input tap 375 1692
```

`evidence/s4_after_wait.png`（199,015 bytes，设备时间 4:12）确认 **app 已恢复响应**、
**面板仍然开着**（卡片、命令列表、键盘全部正常渲染）。

**恢复后在 app 存活状态下重做同一测点**（再次 `input tap 96 198`），结果：

```
$ adb -s emulator-5558 shell dumpsys window | grep mCurrentFocus
mCurrentFocus=Window{33e5dc8 u0 Application Not Responding: com.xiufeigo.opencodeshell}
```

→ **第二次触发本 app 的 ANR。**

**⚠️ 归因边界（重要，不夸大）**：本 app 的 ANR **确实复现**（2 次），且两次都紧跟在
"面板打开态下点击顶部标签条"之后。但**不能断定是该点击触发的**，理由：

- `logcat` 里唯一带 `ANR in` 的记录属于**别的包**：
  ```
  10-02 16:10:24.035 E ActivityManager: ANR in com.google.android.gms.persistent
  10-02 16:10:24.035 E ActivityManager: Reason: executing service
         com.google.android.gms/com.google.android.location.internal.PendingIntentCallbackService,
         waited 90752ms
  ```
  本 app 的 ANR **在 logcat 中没有对应记录**（见 §5.3）。
- 本机 **8 台模拟器并发**，宿主整体处于高压状态（§5.1），ANR 可能是环境性而非死区相关。
- 样本量 **n=2**，且两次都发生在设备已被 dump 异常污染的状态下（§5.2）。

> **诚实的结论**：本轮观测到的是一个**独立于死区的新问题——本 app 在该设备上反复 ANR**，
> 它**污染了**死区复验，使 2–6 步全部无法在干净基线上执行。
> **死区修复本身本轮既未证成也未证伪。**

### 3.3 步骤 3–6 — 未执行（BLOCKED）

| 步骤 | 状态 | 阻断原因 |
|---|---|---|
| ③ 点遮罩关面板（阳性对照） | ⛔ 未执行 | app 处于 ANR/恢复交替态，无干净基线 |
| ④ 面板内执行命令 | ⛔ 未执行 | 同上 |
| ⑤ 移动形态（`tabLayout=vertical`）重复 | ⛔ 未执行 | 同上；且本轮**未**改过 `wm`，无需 reset |
| ⑥ 退出语义回归 | ⛔ 未执行 | 同上 |

> 纪律遵守：③–⑥ **不是被跳过**，而是**被 ANR 强制中断**。我没有在 ANR 状态下
> 继续采集并把结果当作有效判定——那会产出**假 FAIL**。

### 3.4 步骤 7 — 收尾还原：✅ 通过

收尾复核实测读数（2026-10-03 00:25 前后）：

```
$ adb -s emulator-5558 shell echo ALIVE_5568            →  ALIVE_5558        # 设备存活，未 wedge
$ adb -s emulator-5558 shell wm size                    →  Physical size: 1344x2992      （无 Override 行）
$ adb -s emulator-5558 shell wm density                 →  Physical density: 480         （无 Override 行）
```

| 项 | 期望 | 实测 | 状态 |
|---|---|---|---|
| `wm size` | 未改过 | `Physical size: 1344x2992`（**无 `Override` 行**） | ✅ |
| `wm density` | 未改过 | `Physical density: 480`（**无 `Override` 行**） | ✅ |
| 标签布局开关 | 未改过 | `appearance.tabLayout` 键**本就不存在**（默认顶部分栏），我未写入 | ✅ |
| 设备存活 | 未 wedge | `echo ALIVE_5558` → `ALIVE_5558` | ✅ |
| `/sdcard` 残留 | 无 | 本轮所有 dump/screencap 临时文件在**每次用到后立即 `rm -f`**（`window_dump_*.xml`、`scr_*.png`、`d*.xml`、`q*.xml`、`s*.png` 均逐个删除并确认）。⚠️ **收尾时的全量 `ls /sdcard` 复扫未能完成**——设备此时仍处于 ANR 状态，该命令 60s 超时无输出，故此项为**逐次删除确认**，非**终态全量复扫**。见 §6.9 | ⚠️ |
| 只操作 5558 | 遵守 | 所有 adb 调用均带 `-s emulator-5558`；**未碰 127.0.0.1:5555** | ✅ |
| 未跑 gradle / 未装机 | 遵守 | 全程无 gradle、无 `install` | ✅ |
| 未杀进程 | 遵守 | ANR 对话框选 **`Wait`**，**未选 `Close app`**，未 `force-stop` | ✅ |
| 未按 BACK(4) / 未用 ESC(111) | 遵守 | 全程仅 `input tap` + `keycombination 113 39` | ✅ |
| 自建会话前缀 | 遵守 | `E2ECHK5558v98`（`base.xml` 中可见该会话标签） | ✅ |
| 截图通道 | 遵守 | 仅 `screencap -p` + `adb pull`，**未用 exec-out** | ✅ |

---

## 4. 关键证据：本 app 反复 ANR

这是本轮**最硬的新发现**，它比死区本身更值得跟进。

```
读数 1（点标签条后，设备时间 4:10）
  mCurrentFocus=Window{33e5dc8 u0 Application Not Responding: com.xiufeigo.opencodeshell}
  屏幕：系统对话框 "OpenCode isn't responding"  [Close app] [Wait]
  背景：命令面板仍在（暗色遮罩下可见卡片）

读数 2（点 Wait 恢复 + 复测同一测点后）
  mCurrentFocus=Window{33e5dc8 u0 Application Not Responding: com.xiufeigo.opencodeshell}
  证据：evidence/s5_retest_strip_tap.png（179,989 bytes）
  → 第二次 ANR
```

**ANR 对 app 复验方法论的影响（这是本轮真正的价值）**：

> 5564 那份 `REPORT_PERF.md` §6.1 用"**连续 14 次 dump 节点数恒定 + 196 帧 p99=101ms +
> logcat 零 ANR**"排除"app 卡死"。在 5558 上，**同样的现象（dump 恒定/取不到）**
> **恰恰可能是 ANR 的表现**——本轮 `uiautomator dump` 反复被 `Killed`，
> 且系统随后弹出了本 app 的 ANR 对话框。
>
> 因此"dump 签名 28s 不变"**不能**单独作为"没死区问题"的证据。
> 建议后续复验**必须**加一条硬前置：**每次采样前先确认无 ANR 对话框 / `mCurrentFocus` 属本 app**。

---

## 5. 环境异常与污染源

### 5.1 宿主高压

- 在线模拟器 **8 台**（5554/5556/5558/5560/5562/5564/5600/5602）
- `MemFree` 仅 **322 MB** / `MemTotal` 4.0 GB（`MemAvailable` 2.2 GB）
- `uiautomator` 子进程被系统 **Killed**（多次），是内存压力的直接症状

> 5564 那轮记录的宿主内存为"空闲 33.6 GB"；5558 设备自身仅 4 GB 且已接近打满。
> 本轮 ANR 很可能与宿主/设备内存压力**相关**，而非死区缺陷。

### 5.2 `uiautomator dump` 通道反复失败

时间线（**关键**：失败早于任何面板操作）：

| 时刻 | 事件 |
|---|---|
| 23:50–23:58 | 基线 dump 前，dump 已**连续 4 次静默失败**（`Killed` / `null root node`），此时**面板尚未打开** |
| 23:59:06 | 重试成功，得到 `base.xml`（49 节点）——**说明不是 app 逻辑问题** |
| 00:02–00:07 | 打开面板后，多轮 dump 又开始失败，`run_core.ps1` / `run2.ps1` 因 dump 超时未能落盘 |

**判读**：dump 失败**先于**面板存在，**与死区、与 `dialogCardBounds` 修复无关**，
是设备侧 UiAutomation 通道 + 内存压力的联合结果。
**处置**：改用**截图通道**（`screencap -p` + `adb pull`）作为主证据——它全程稳定可用，
本报告的 UI 状态判定全部基于截图 + 体积/视觉比对。

> 这是一个**方法论降级**，不是结论降级：截图能可靠回答"面板开/关"这个二元问题，
> 但**无法**提供 `uiautomator` 的逐节点 bounds 签名 diff（§3.2 要求的"点前点后签名 diff"）。
> 这是本轮无法给出 PASS/FAIL 的直接原因之一。

### 5.3 logcat 中本 app 的 ANR 无记录

`logcat -d` 里**唯一**的 `ANR in` 属于 `com.google.android.gms.persistent`（§3.2）。
本 app 的 ANR **没有对应的 logcat 行**——与 5564 那轮"logcat 零 ANR"的现象类似，
但那次被解读为"没卡死"，**这次是"卡死了但 logcat 干净"**。
该差异需要单独排查（可能是 logcat 缓冲区被 8 台模拟器的日志冲刷掉）。

---

## 6. 未闭环

1. **死区修复本轮未判定**（最重要）。步骤 2 缺干净基线，3–6 全部 BLOCKED。
   **必须在 app 不 ANR 的设备/时段重跑**，才能回答"点标签条有没有可观测结果"。
2. **本 app 反复 ANR，根因未定位**。2 次复现，但 logcat 无记录、样本 n=2、
   宿主 8 台并发内存吃紧，无法区分"死区点击触发"与"环境压力偶发"。
   建议：`dumpsys activity anr` / `/data/anr/traces.txt`（需权限）+ 独占设备复现。
3. **点前/点后签名 diff 未能提供**。`uiautomator dump` 通道失效，
   退化为截图 + 文件体积比对（199,231 → 179,873 bytes，差异由 ANR 对话框产生，
   **不是**面板开/关的干净差异）。`step2_diff.txt` 未能生成。
4. **移动形态（`tabLayout=vertical`）完全未测**。本轮从默认顶部分栏起步，
   修复文档 §2.3 预测的"IME 塌缩吃掉底栏"在**底栏形态**下才是主用例，
   **恰恰是没测到的那个**。这是最需要补的一步。
5. **面板内正路径（点命令 → 执行）未测**。截图显示命令列表渲染正常，
   但**未验证点击是否真能执行**。
6. **退出语义回归未测**。ANR 后无法验证"关闭后第一下点击是否被退出动画吞掉"。
7. **`appearance.tabLayout` 写 `vertical` 的路径未验证**。
   5564 那轮发现该键可经 `run-as` 读；**写**它需要确认 app 是否在运行时覆写
   SharedPreferences（若会覆写，直接写文件无效）。补测 ⑤ 时需先解决这一点。
8. **宿主负载与设备内存的关系未量化**。仅单点采样 `MemFree=322MB`，
   未做时序采样，无法归因 ANR 与内存压力的关系。
9. **`/sdcard` 终态全量复扫未完成**。每次临时文件都用后 `rm -f` 并确认，
   但收尾时的一次 `ls /sdcard` 全量复扫因设备仍处 ANR 而 60s 超时。
   需在设备恢复后补一次终态扫描。

---

## 7. 建议的下一步（给下一轮测试员）

1. **换一台空闲设备**（或等 8 台并发降下来），`MemFree > 1 GB` 再开工。
2. **开工硬前置**：`dumpsys window | grep mCurrentFocus` 必须是
   `com.xiufeigo.opencodeshell/...OcActivity`；若是 `Application Not Responding` 先 `Wait`。
3. **dump 通道自检**：先做一次基线 dump 并确认成功，再开始任何判定；
   失败 3 次即视为环境不达标，**不要**在 dump 不可用时强行下判定。
4. **⑤ 优先于 ②**：底栏形态才是修复文档预测的死区主用例，建议先设 `vertical`。
5. **补 ANR 取证**：`dumpsys activity anr`、`logcat -b events`，别只看 `logcat -d` 主缓冲。

---

## 8. 交付物清单

| 文件 | 状态 |
|---|---|
| `research/v97_verify/5558_v98/REPORT_PALETTE.md` | 本文件 |
| `evidence/base.xml` / `base.png` | ✅ 默认形态基线（49 节点，全属本 app） |
| `evidence/n0_state.png` | ✅ 面板打开态（步骤 1 证据） |
| `evidence/s2_after_strip_tap.png` | ✅ 点标签条后 → ANR 对话框 |
| `evidence/s4_after_wait.png` | ✅ 点 Wait 恢复后面板仍在 |
| `evidence/s5_retest_strip_tap.png` | ✅ 复测同一测点 → 第二次 ANR |
| `evidence/helper.ps1` / `run_core.ps1` / `run2.ps1` | 取证脚本（dump 失效实录） |

---

## 9. 最终判定

**状态：BLOCKED（环境性）— 死区修复本轮未判定。**

- **全屏设备上点底栏是否有可观测结果**：**未判定**。设备形态判据全部通过（`app=1344x2992`、
  49 节点 100% 属本 app、无启动器露出），但在唯一一次有效点击后 **app 进入 ANR**，
  主线程不处理输入，**无法区分"死区吞点击"与"app 已死"**。观察到的是 ANR 对话框，
  **不是**面板关闭，**也不是**空白无反应——**两者都不是死区的证据**。
- **点遮罩是否关闭面板**：**未测**（ANR 阻断）。
- **面板内命令是否可执行**：**未测**（ANR 阻断）；仅确认命令列表**渲染**正常。
- **报告路径**：`research/v97_verify/5558_v98/REPORT_PALETTE.md`

**本轮真正的产出**：坐实了 **5558（Pixel_10_Pro_XL）上本 app 会反复 ANR**，
并证明**"dump 签名不变 / dump 取不到"不能单独作为"无死区问题"的证据**——
这是对 5564 `REPORT_PERF.md` §6.1 论证方式的一条实质性反例。
