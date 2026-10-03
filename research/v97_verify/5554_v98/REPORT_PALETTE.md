# REPORT_PALETTE — 命令面板死区 · 第三轮（先排除 ANR，再判死区）

> 设备：emulator-5554 / **Pixel_9a**（全屏手机形态）
> 轮次定位：第 1 轮（5562）窗口化桌面 → 结论不成立；第 2 轮（5558）全屏手机 → 反复 ANR，无法区分"死区吞点击"与"app 已死"→ 未判定。
> 本轮第一要务：**先用正向对照证明 app 响应正常**，再做死区判定。
> 方法论红线：第 2 轮反例 —— **"dump 签名不变 / dump 取不到"不能单独作为"存在死区"的证据**（ANR 同样如此）。故 B 段必须三路交叉（截图像素差分 / logcat 输入处理 / gfxinfo 帧数）。
>
> ## 结论一句话：**BLOCKED — app 在冷启动阶段就 ANR（failed to complete startup），A2 正向对照无法通过，B 段（死区判定）按规定不予执行。**
> **本轮既不能判定"有死区"，也不能判定"无死区"。命令面板死区仍未闭环。**

---

## 0. 环境读数

| 项 | 读数 |
|---|---|
| 设备 | `emulator-5554`（`adb devices` 在线） |
| AVD 名 | **Pixel_9a**（`emu avd name` 核实 ✅ 与交办一致） |
| 形态 | 全屏手机（`wm size`=1080x2424，`wm density`=420）✅ |
| 包名 | `com.xiufeigo.opencodeshell`（实测，**非**交办的 `com.sensorsdata.*`） |
| Launcher Activity | `com.xiufeigo.opencodeshell/.OcActivity`（`cmd package resolve-activity`） |
| 后端 | http://127.0.0.1:4095 Basic `opencode:opencode`（本轮未成功建连，app 未到可用态） |
| `reverse --list` | `host-15 tcp:4095 tcp:4095` / `host-15 tcp:2222 tcp:22` —— **已挂载，无需重挂** ✅ |
| 宿主并行模拟器 | 8 台在线（5554/5556/5558/5560/5562/5564/5600/5602） |
| 设备时钟 | `Fri Oct 2 16:29:06 GMT 2026` ≡ 宿主 `2026-10-03 00:29 +0800` —— **同一时刻，无时钟偏移**（故下文 logcat 16:xx = 宿主 00:xx） |
| `lastUpdateTime` | ⚠️ **未能读出**。`dumpsys package` 的 `lastUpdateTime` / `versionName` / `UpdateTime` 三种 pattern 全部 grep 落空（设备 I/O 已劣化，见 §0.2）。**无法确认本机是否 Lead 23:20 闸门构建** —— 按交办要求，此项**记为未核实**，不作"就是该版本"的假设。 |
| **出首帧耗时** | ❌ **未产出**。`am start -W`（t0=设备 16:23:02）**300s 内从未返回**，app 未 `Displayed`、未 `Fully drawn`，因此不存在"出首帧耗时"读数。 |

### 0.1 冷启动实测

```
00:23:02.424(宿主)  am force-stop com.xiufeigo.opencodeshell   ✅
00:23:02.424(宿主)  logcat -c                                  ✅ 清基线
00:23:02.424(宿主)  am start -W -n com.xiufeigo.opencodeshell/.OcActivity
                     → 仅回 "Starting: Intent {...}"，随后 300s 静默
00:28:xx(宿主)      → 主动 cancel 该探测（cold start >5min，与交办预警一致）
```

- 进程确实起来了：`ps -A` → `u0_a230 3971 ... com.xiufeigo.opencodeshell`
- 但**进程活着 ≠ app 可用**：该进程在 ANR 后仍长期存活（终态 `R`），却从未完成启动、从未出首帧。

### 0.2 设备 I/O 通道劣化（影响取证能力，必须记录）

| 探测 | 结果 |
|---|---|
| `screencap -p`（第 1 次） | **180s 超时**（任务判 failed） |
| `screencap -p`（第 2 次） | **耗时 244581ms（≈244s）** 才返回；`adb pull` 报 `failed to copy ... Transport endpoint is not connected`；落盘文件 **LEN=0（0 字节，非有效 PNG）** |
| `screencap -p`（第 3 次） | `Error opening file: /sdcard/p3.png (Permission denied)` + `Saving image failed` |
| `uiautomator dump` | 反复挂起；终局一次耗时 **210806ms（≈211s）** 才回 `UI hierchary dumped to: /sdcard/dd.xml`，但随后 `adb pull` 报 `failed to stat remote object '/sdcard/dd.xml': Permission denied` → **无有效 XML 落地** |
| `dumpsys activity processes` | 返回**空**（无法据此判断 ANR） |
| `dumpsys activity lastanr` | 返回 `<no ANR has occurred since boot>` —— 与 logcat 明确的 ANR 记录**矛盾**，属"ANR 对话框/trace 已过期"（见 §1.3），**不可据此否认 ANR** |
| `cat /data/anr/*` | `Permission denied`（全部 5 个最近 trace 文件） |
| `dumpsys dropbox --print` | 耗时 **31891ms**，grep `anr`/`opencodeshell` 均**无命中** |

> **关键影响**：交办要求 B 段用「①截图像素差分 / ②logcat 输入事件 / ③gfxinfo 帧数」三路交叉。但在设备 I/O 已劣化的情况下，**①截图像素差分在物理上无法完成**（screencap 244s 且传输失败）。这本身就是即便 A2 通过、B 段也难以按三路交叉严格执行的原因。

---

## 1. A 段 —— 前置：排除 ANR

### A1 冷启动与出首帧耗时

- 进程拉起：**成功**（pid 3971）。
- 出首帧：**未达成**。`am start -W` 300s 未返回，无 `Displayed` / `Fully drawn` 事件。
- **出首帧耗时 = 无法测得（无首帧）。**

### A2 正向对照 —— ❌ **未通过（app 不响应）**

按交办规则，正向对照必须在死区测试之前通过。**本轮无法通过**，故 B 段不予执行。

#### A2.1 ANR 硬证据（logcat，本 app 自身）

```
10-02 16:32:26.302  677  3974 E ActivityManager: ANR in com.xiufeigo.opencodeshell
10-02 16:32:26.302  677  3974 E ActivityManager: Reason: Process ProcessRecord{408458c 3971:com.xiufeigo.opencodeshell/u0a230} failed to complete startup
10-02 16:32:31.916  677  3974 D ActivityManager: Completed ANR of com.xiufeigo.opencodeshell in 93713ms, latency 1598988ms (expired, only dump ANR app)
10-02 16:32:38.701  677   699 D WindowManager: setClientSurface Surface(name=VRI-Application Not Responding: com.xiufeigo.opencodeshell#2745)
10-02 16:32:38.950  677   706 D CoreBackPreview: Window{e95ba2a u0 Application Not Responding: com.xiufeigo.opencodeshell}
```

**这是本 app 自身的 ANR，无歧义。** "Application Not Responding" 窗口亦由 system_server 实际置出。

#### A2.2 同期其它包也 ANR —— 说明是**环境级**过载，不止本 app

```
16:28:11  ANR in com.google.android.apps.wellbeing          (SystemJobService, waited 84537ms)
16:30:57  ANR in com.google.android.permissioncontroller    (PermissionControllerServiceImpl, waited 79020ms)
16:33:46  ANR in com.google.android.providers.media.module (SystemJobService, waited 62478ms)
16:34:34  ANR in com.google.android.gms.persistent          (failed to complete startup)
```

四个**与本项目无关的** Google/系统包在同 10 分钟内相继 ANR，waited 时长 62s–85s。

#### A2.3 主线程在等什么（**堆栈/时间线头部** —— 定位 ANR 真因的关键）

`/data/anr/*` 与 `lastanr` 均不可读，以下为 `libdebuggerd_client` 取栈期间 logcat 暴露的**主线程真实工作内容**：

```
10-02 16:30:43.628  677  4048 I ActivityManager: Collecting stacks for pid 3971 into temporary file temp_anr_2583...txt
10-02 16:31:10.712  3971 3971 W o.opencodeshell: Verification of void androidx.lifecycle.ProcessLifecycleOwner$Api29Impl.registerActivityLifecycleCallbacks(...) took 220.607ms (63.46 bytecodes/s)
10-02 16:31:12.393  3971 3971 W o.opencodeshell: Verification of void androidx.activity.OnBackPressedDispatcher$1.<init>(...) took 123.717ms (56.58 bytecodes/s)
10-02 16:31:13.155  3971 3971 W o.opencodeshell: Verification of ...onBackCancelled() took 179.689ms (33.39 bytecodes/s)
10-02 16:31:40.811  677  4048 I system_server: libdebuggerd_client: started dumping process 3971
10-02 16:31:59.639  677  3974 E ActivityManager: Aborting stack trace dump (currently copying primary pid3971); deadline exceeded.
10-02 16:32:04.683  677  4048 I system_server: libdebuggerd_client: done dumping process 3971
10-02 16:32:05.146  677  3974 E ActivityManager: Aborted stack trace dump (current primary pid=3971); deadline exceeded.
10-02 16:32:26.084  3971 3971 W o.opencodeshell: Verification of android.content.Context androidx.core.content.ContextCompat.createDeviceProtectedStorageContext(...) took 113.167ms (53.02 bytecodes/s)
10-02 16:32:35.038  677   699 W Looper: Slow dispatch took 3971ms  app=system_server group=TOP_APP h=ActivityManagerService$UiHandler c=ErrorDialogController$$ExternalSyntheticLambda5
10-02 16:33:01.955  3971 3971 W o.opencodeshell: Verification of androidx.lifecycle.ReportFragment$Companion.get(...) took 168.525ms (142.41 bytecodes/s)
10-02 16:33:02.375  3971 3971 W o.opencodeshell: Verification of ...injectIfNeededIn(...) took 408.361ms (122.44 bytecodes/s)
10-02 16:35:43.742  3971 3971 W o.opencodeshell: Verification of ...AppCompatViewInflater.createAutoCompleteTextView(...) took 132.895ms (45.15 bytecodes/s)
10-02 16:35:44.103  3971 3971 W o.opencodeshell: Verification of ...createButton(...) took 273.169ms (21.96 bytecodes/s)
10-02 16:35:45.058  3971 3971 W o.opencodeshell: Verification of ...createEditText(...) took 260.026ms (23.07 bytecodes/s)
10-02 16:35:46.664  3971 3971 W o.opencodeshell: Verification of void androidx.activity.EdgeToEdgeApi29.<init>() took 147.234ms (27.17 bytecodes/s)
10-02 16:35:48.342  3971 3971 W o.opencodeshell: Verification of ...OcActivity$onCreate$1.$r8$lambda...(NetworkRecoveryWatch) took 139.093ms (35.95 bytecodes/s)
```

**主线程在等什么 —— 定性结论：**
主线程并非阻塞在某个锁/IO，而是**一直在做 ART 类校验（dex verification），且单步耗时被拉长到 100–400ms、字节码速率低至 19–142 bytecodes/s**（正常冷启应数千 bytecodes/s）。即：**CPU 被抢占到近乎无算力，app 的冷启动工作被无限拉长，`ActivityThread` 报 `failed to complete startup`**。

旁证：
```
16:32:26.302  17% 3971/com.xiufeigo.opencodeshell: 0.7% user + 16% kernel / faults: 5573 minor 2 major
16:30:57.684  15% 3971/com.xiufeigo.opencodeshell: 0% user + 15% kernel / faults: 12 minor
```
**user time 0.7%、kernel time 16%** —— 时间几乎全耗在内核态（内存/IO 抖动），而非 app 自身逻辑。
```
/proc/loadavg: 20.80 24.15 24.94 27/2101 6225      ← 宿主 8 台模拟器严重过载
system_server: Weak pointer dereference blocked for 3382 milliseconds / 2970ms / 3146ms ...
Choreographer: Skipped 123 frames! / 59 frames! / 58 frames!
Looper: Slow dispatch took 3971ms (system_server 自己也在慢)
```

> **判定：本次 ANR 的根因是宿主/设备资源过载（load 20+、system_server 自身阻塞 3s+、全局 Skipped frames），而非本 app 的业务代码死锁。** 但对"本 app 是否响应输入"这个问题而言，其后果与卡死等价 —— **app 未完成启动，无法响应任何输入**。

### A3 A 段小结

- app 进程：拉起 ✅；完成启动 ❌；出首帧 ❌；响应输入 ❌（未到可交互态）。
- 本 app ANR 次数：logcat 全量 grep `ANR in com.xiufeigo.opencodeshell` = **1 次**（16:32:26），`Reason: failed to complete startup`。
- **A2 判定：FAIL → 依交办规则，整体判 BLOCKED。**

---

## 2. B 段 —— 命令面板死区判定

### ❌ 未执行（按规定终止）

依交办步骤 **B5**：「若任一步 app 进入 ANR → **立即停手**，按 A2 的方式收集 ANR 证据并判 **BLOCKED**（**不要**把 ANR 记成死区）」。

本轮在 **A 段即已命中 ANR**（比 B5 触发点更早），因此：

| B 步骤 | 状态 | 原因 |
|---|---|---|
| B1 `input keycombination 113 39`（Ctrl+K）开面板 | **未执行** | A2 未通过；且 app 未出首帧，无任何可交互入口 |
| B2 点底栏标签条真实 bounds 中心 | **未执行** | 无 dump 可解析 bounds（uiautomator dump 反复挂起）；无有效截图 |
| B2 三路交叉（①像素差分 ②logcat 输入处理 ③gfxinfo 帧数） | **未执行** | app 未响应，先验即不成立 |
| B3 阳性对照：点面板外遮罩区 | **未执行** | 面板从未打开 |
| B4 面板内正路径：点一条命令 | **未执行** | 同上 |

### 2.1 明确不做的三个"偷懒结论"（对应交办的方法论红线）

1. ❌ **不能**说"dump 签名不变 → 存在死区" —— 本轮 dump 压根没取到；且 ANR 同样导致取不到 dump。第 2 轮反例依然成立。
2. ❌ **不能**说"点底栏无反应 → 存在死区" —— app 处于 **ANR / 未完成启动** 态，此时任何点击都必然无反应，**无法与死区吞点击区分**。这正是第 2 轮"未判定"的原因，本轮 5554 完全复现。
3. ❌ **不能**说"截图像素差分无变化" —— 本轮 screencap **244s 且传输失败（0 字节）**，像素差分**根本没做成功**，不存在"无变化"这一观测。

---

## 3. 收尾

| 项 | 结果 |
|---|---|
| `/sdcard` 残留 | ✅ **最终 `ls` 检查无命中**（`p_now.png` / `d_now.xml` / `p2.png` / `p3.png` / `dd.xml` 均无）。⚠️ 需诚实记录：最后 `rm /sdcard/dd.xml` 报了 `Transport endpoint is not connected`（传输端点故障，§0.2 同一故障），**删除指令未成功返回**；但随后 `ls` 未列出任何本轮文件，故判定无残留。若设备 sdcard 后续可访问，建议复查一次。 |
| 纪律遵守 | ✅ 未跑 gradle；未装机；未杀进程（未执行任何 kill，仅按需 force-stop）；仅操作 5554；未碰 127.0.0.1:5555；未按 BACK(4)；展示层未用 ESC(111)；截图仅 `screencap -p` + `adb pull`（未用 exec-out） |
| 自建会话前缀 | 本轮未创建任何会话（未到可交互态，无从建立 `E2ECHK5554pd*` 会话） |
| `am force-stop` 后可再启动 | ⚠️ **未验证**（见下） |

### 3.1 遗留 / 未闭环

1. **【BLOCKED 主因】app 冷启动 ANR（`failed to complete startup`），命令面板死区未判定。** 三轮均未闭环。
2. **【环境根因】宿主 8 台模拟器过载**（`loadavg 20.80/24.15/24.94`），system_server 自身阻塞 3s+、全局 Skipped 123 frames、4 个 Google 系统包同期 ANR。**建议先减载到 ≤2 台再复测**，否则任何 ANR 都会被环境噪声淹没，Appium/WDA 一类外部观测同样拿不到稳定读数。
3. **【取证能力受限】设备 I/O 通道劣化**：screencap 244s + pull 传输失败（0 字节）、`uiautomator dump` 挂起、`dumpsys activity processes` 返回空、`/data/anr/*` Permission denied。**三路交叉中的「截图像素差分」在当前设备上物理不可执行**，需先修复 adb 传输或降低宿主负载。
4. **【陷阱记录】`dumpsys activity lastanr` 返回 `<no ANR has occurred since boot>`，但 logcat 明确记录了本 app 的 ANR** —— 前者因 ANR trace 已过期（`expired, only dump ANR app`）而不可信。**切勿用 `lastanr` 否认 ANR。**
5. **【误归属陷阱】同期 `ANR in com.google.android.apps.wellbeing` / `permissioncontroller` / `media.module` / `gms.persistent` 均非本 app** —— 归因时必须按包名精确匹配，不可只看 "ANR in"。
6. **【未核实】本机是否 Lead 23:20 闸门构建**：`lastUpdateTime` 读不出，**不作版本假设**。
7. **【形态差异】交办包名 `com.sensorsdata.*` 与实测 `com.xiufeigo.opencodeshell` 不一致**，本轮按实测包执行；如需与 5564 `REPORT_PERF.md` 严格对齐，请确认哪个是预期包名。

---

## 4. 复测前置条件（建议下一轮先做）

1. 宿主降到 **≤2 台模拟器**，`loadavg` 降到 <5。
2. 确认 `adb -s emulator-5554 get-state` 稳定、`adb pull` 往返 <2s。
3. 先跑 **A2 冒烟**：冷启后 `uiautomator dump` 能在 5s 内落地、且截到底栏"设置"→ 点它 → dump 签名变化 —— 这一条过了，才允许进入 B 段。
4. 只有 A2 通过后，才执行 B2 的三路交叉（截图像素差分 / logcat 输入处理 / gfxinfo 帧数）。
