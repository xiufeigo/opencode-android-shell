# REPORT_PALETTE2 — 命令面板"死区"复验 · 第四轮（emulator-5558 / Pixel_10_Pro_XL）

- **执行人**：Mavis（真机复验测试员，第四轮 · 紧时间盒）
- **时间**：2026-10-03 00:44 – 01:02（UTC+8）
- **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
- **设备**：`emulator-5558` = **Pixel_10_Pro_XL**，1344x2992 @ 480dpi，**全屏手机形态**
- **包**：`com.xiufeigo.opencodeshell` / `.OcActivity`（versionName=1.4.0）
- **报告路径**：`research/v97_verify/5558_v98/REPORT_PALETTE2.md`
- **证据目录**：`research/v97_verify/5558_v98/pal2/`

---

## 0. 结论（先给结论）

> ## 状态：**BLOCKED**
>
> **A2 正向对照 = ❌ 未通过** → 按方法论红线，B1 / B2 **不执行、不判定**。

**一句话**：`emulator-5558` 这一轮连"首帧"都没画出来 —— 进程活着、卡在 **Splash Screen 窗口**，`gfxinfo` **零帧**，`mCurrentFocus=null`。这跟第 3 轮 5554 是**同一个签名**（主线程陷在 ART 类校验、被 CPU 抢占到 31–454 bytecodes/s）。Lead 退掉 5560 的 wedge **没有**让 5558 恢复到可测状态。

**因此本轮对"命令面板死区"没有任何结论** —— 不是 PASS，不是 FAIL，是**测不了**。

---

## 1. 环境读数

### 1.1 设备身份 ✅

```
$ adb devices
emulator-5558   device
$ adb -s emulator-5558 emu avd name
Pixel_10_Pro_XL
OK
```

### 1.2 通信前置 ✅（`reverse` 已挂，无需重挂）

```
$ adb -s emulator-5558 reverse --list
host-15 tcp:4095 tcp:4095
host-15 tcp:2222 tcp:22
```

> 注意：任务书里写的 `mcode reverse --list` 里的 `reverse` **不是 mcode 子命令**（mcode 只有 init/exec/acp/login/logout/update），实际是 `adb reverse --list`。已按 `adb` 口径执行。
> 两个隧道（4095 / 2222）**都在**，无需重挂。

### 1.3 lastUpdateTime ✅ **与期望一致**

```
$ adb shell dumpsys package com.xiufeigo.opencodeshell
versionName=1.4.0
lastUpdateTime=2026-10-02 15:20:51
```

> **符合期望值 2026-10-02 15:20:51**，本轮跑的确实是预期那个构建，无错装风险。

### 1.4 形态（沿用第 2 轮结论）

`wm size` 物理 1344x2992 / `wm density` 480，无 Override；`dumpsys window` 记录 `mAppBounds=Rect(0,0 - 1344, 2992)` = 全屏，**本轮未发现窗口化退化**。但这一点在本轮**无实际验证价值**——app 连首帧都没出。

---

## 2. A2 正向对照 —— ❌ **未通过（BLOCKED 触发点）**

### 2.1 操作

```powershell
adb -s emulator-5558 logcat -c
adb -s emulator-5558 shell am force-stop com.xiufeigo.opencodeshell
adb -s emulator-5558 shell am start -W -n com.xiufeigo.opencodeshell/.OcActivity
```

> 入口确认：`app/src/main/AndroidManifest.xml:77-92` —— `.OcActivity` 带 `MAIN` + `LAUNCHER`。
> （先试的 `am start -a MAIN -c LAUNCHER -p <pkg>` 报 `Activity not started, unable to resolve Intent`，故改用显式 component `-n`。）

### 2.2 冷启动耗时 —— **无首帧，WaitTime 60.9s 超时**

```
Status: timeout
LaunchState: UNKNOWN (-1)
Activity: com.xiufeigo.opencodeshell/.OcActivity
WaitTime: 60868
（无 TotalTime 行 —— 首帧从未上报）
STOPWATCH_MS=88467
```

| 指标 | 读数 |
|---|---|
| `Status` | **timeout** |
| `LaunchState` | **UNKNOWN (-1)** |
| `WaitTime` | **60 868 ms** |
| 墙钟（含 force-stop 后 2s 间隔） | 88 467 ms |
| `Displayed com.xiufeigo...` | **日志中不存在** |

### 2.3 焦点：ActivityRecord 在，**窗口没一个拿到焦点**

```
$ adb shell dumpsys window | grep mCurrentFocus
mCurrentFocus=null
mFocusedApp=ActivityRecord{35588497 u0 com.xiufeigo.opencodeshell/.OcActivity t149}
```

窗口层证据（`dumpsys window windows`）——**卡在 Splash Screen**：

```
Window #8 Window{6006e65 u0 Splash Screen com.xiufeigo.opencodeshell}:
    mAttrs={... ty=APPLICATION_STARTING fmt=TRANSLUCENT ...}
    fl=NOT_FOCUSABLE NOT_TOUCHABLE ...
    mViewVisibility=0x0 mHaveFrame=true mObscured=false
    Surface: shown=true  mDrawState=HAS_DRAWN     <-- 只有 Splash 画了

Window #9 Window{3608d1f u0 com.xiufeigo.opencodeshell/...OcActivity}:
    mAttrs={... ty=BASE_APPLICATION ...}          <-- 真正的 app 窗口，never focus
```

> **读法**：`APPLICATION_STARTING`（Splash）已经 `HAS_DRAWN`，但**系统从未把 Splash 撤掉、也从未把焦点交给 `OcActivity`** —— 说明 app 侧 `onCreate`/`setContentView` 之后**没有完成首帧提交**，启动流程卡在半路。

### 2.4 截图：`screencap -p` + `adb pull` ✅（未用 exec-out）

![A2 冷启动截图](pal2/pal2_a2.png)

> 画面 = **启动器壁纸 + 本 app 的图标**，**app UI 一个像素都没画**。
> 状态栏可见（4:49 / LTE / 电量），系统 UI 正常渲染 —— **不是设备黑屏，不是渲染管线坏了，是 app 自己没提交帧**。

### 2.5 `gfxinfo`：**零帧**

```
$ adb shell dumpsys gfxinfo com.xiufeigo.opencodeshell | grep 'Total frames|Janky|...'
（无任何输出）
```

> **app 名下没有任何帧统计记录** = 0 帧。
> 这一条同时也是第 3 节"三路交叉"里的**截图像素差分路**的基线（本来就是 0 帧，无从差分）。

### 2.6 进程活着，但主线程在**烧 ART 类校验**（第 3 轮同一签名）

```
$ adb shell ps -A | grep opencodeshell
u0_a231  1923  468  14508004  49352  0  0  R  com.xiufeigo.opencodeshell
```

进程状态 `R`（running），**没死、没被 ANR 杀掉**：

```
Proc # 0: fg  T/A/TOP  LCMNFUATI  1923:com.xiufeigo.opencodeshell/u0a231 (top-activity)
state: cur=TOP  set=TOP  lastRss=188MB lastCachedRss=0.00
cached=false empty=false
```

主线程（`1923  1923` = pid:tid 同号 = **主线程**）日志原文：

```
W o.opencodeshell: Verification of void com.google.crypto.tink.shaded.protobuf.Protobuf.<init>()
    took 369.355ms (48.73 bytecodes/s) (0B arena alloc)
W o.opencodeshell: Verification of com.google.crypto.tink.shaded.protobuf.CodedInputStream ...newInstance(...)
    took 167.623ms (131.25 bytecodes/s) (0B arena alloc)
W o.opencodeshell: Verification of int com.google.crypto.tink.shaded.protobuf.MapFieldSchemaLite.getSerializedSizeLite(...)
    took 149.410ms (334.65 bytecodes/s) (0B arena alloc)
W o.opencodeshell: Verification of void com.google.crypto.tink.shaded.protobuf.ByteString.<clinit>()
    took 113.001ms (327.43 bytecodes/s) (0B arena alloc)
W o.opencodeshell: Verification of com.google.crypto.tink.shaded.protobuf.ByteString ...wrap(byte[],int,int)
    took 187.789ms (31.95 bytecodes/s) (0B arena alloc)      <-- 最低 31.95
W o.opencodeshell: Verification of com.google.crypto.tink.shaded.protobuf.CodedOutputStream ...newInstance(...)
    took 148.532ms (107.72 bytecodes/s) (0B arena alloc)
（更早还有：Verification of kotlinx.coroutines.flow.FlowKt__LimitKt.drop(...)
    took 101.131ms (454.85 bytecodes/s)）                    <-- 最高 454.85
```

> **bytecodes/s 区间 31.95 – 454.85**。健康设备上 ART 类校验是**毫秒级 / 十万级 bytecodes/s**；几十到几百说明**主线程 CPU 被抢占到几乎饿死**。
> 这与第 3 轮 5554 报告的 **19 – 142 bytecodes/s** 是**同一现象、同一阶段**（本轮具体卡在 `com.google.crypto.tink.shaded.protobuf.*` 的类校验上）。

### 2.7 宿主/整机负载证据（不是 app 单独的问题，是设备整体被拖垮）

```
W libbinder.Binder: Binder transaction to android.content.IContentProvider, ... took 14050ms
W libbinder.Binder: Binder transaction to android.app.IActivityManager,      ... took 4392ms
W libbinder.Binder: Binder transaction to android.app.IActivityManager,      ... took 4300ms
W libbinder.Binder: Binder transaction to android.app.IActivityManager,      ... took 3375ms
W libbinder.Binder: Binder transaction to android.location.provider.ILocationProvider, ... took 4961ms
W m.android.phone: Suspending all threads took: 459.591ms
I FontLog: Fetch {Noto Color Emoji Compat, ...} failed due to timeout [Waited 10000 milliseconds]
W libbinder.ServiceManagerCppClient: Waited one second for android.hardware.uwb.IUwb/default
```

> **Binder 事务 3.4 – 14.0 秒**、系统 `Suspending all threads` 459ms、字体服务 10s 超时 ——
> **整台模拟器都在饿状态**，不是"opencode 死区"。

### 2.8 ANR 记录：`logcat -d -t 400 | grep -E 'ANR in|failed to complete startup|Reason:'`

```
（无匹配 —— 空）
```

> 如实记录：**没有** `ANR in` / `failed to complete startup` / `Reason:` 任何一行。
> 但**启动确实没完成**（`Status: timeout`、`LaunchState: UNKNOWN`、零帧、焦点为空）。
> 与第 3 轮不同的是：第 3 轮有显式 ANR 文本；本轮 ANR **还没被系统判定/上报**（`am start -W` 先在 60s 超时退出）。
> **无论有没有 ANR 文本，"首帧没出来"这件事本身已经足够判 BLOCKED。**

设备侧 `dumpsys activity processes`（已存档于本报告 §2.3/2.6）关键行：

```
*APP* UID 10231 ProcessRecord{2783efc 1923:com.xiufeigo.opencodeshell/u0a231}
  - ActivityRecord{35588497 u0 com.xiufeigo.opencodeshell/.OcActivity t149}
  proc=ProcessRecord{2783efc 1923:...}  state: cur=TOP set=TOP lastRss=188MB
  #37: fg  TOP  LCMNFUATI  1923:com.xiufeigo.opencodeshell/u0a231 act:activities|recents
```

### 2.9 A2 判定

| 检查 | 结果 |
|---|---|
| 冷启动完成 | ❌ `Status: timeout` / `LaunchState: UNKNOWN (-1)` |
| 首帧耗时 | ❌ **无首帧**（WaitTime 60.9s 超时，零帧） |
| 底栏元素可点（点"设置"看 dump/截图变化） | ❌ **无法执行** —— 屏幕上根本没有 app 界面 |
| **A2 正向对照** | ❌ **未通过** |

> **没有正向对照，就没有资格下 FAIL 判定。**
> 这正是本轮任务书里那条红线的用意：第 2 轮就是因为"反复 ANR → 分不清是死区还是 app 已死"才没结论。本轮若强行点底栏，得到的只会是"点了没反应"——而这跟第 1 轮（点击落到启动器）是**同一类无效证据**。

---

## 3. B1 主判据 —— ⛔ **不执行（判定被 BLOCKED）**

- 计划中的动作：命令面板 → 从 dump 解析**底栏节点真实中心** → 点它 → 断言
- **实际：一步都没做。**
- 原因：`am start -W` 都没跑完，app 停在 Splash，**uiautomator dump 里不会有本 app 的底栏节点**，"从 dump 解析坐标"这个前提不成立。
- 三路交叉（截图像素差分 / logcat 输入处理 / gfxinfo 帧数）**未采集**——
  在 A2 未过的情况下采了也不能作为 FAIL 依据（红线）。

**明确记录"未闭环"**：命令面板底栏**死区与否，本轮无法回答**。

---

## 4. B2 阳性对照（点遮罩关闭面板）—— ⛔ **不执行（判定被 BLOCKED）**

- 面板都没能打开，遮罩区域不存在，无从点击。
- **明确记录"未闭环"**。

---

## 5. 收尾：`/sdcard` 残留

见 §6 清单。本轮自建会话前缀 `E2ECHK5558q` **本轮未创建任何会话**（没走到开面板那步），无会话残留。设备侧仅本轮截图 `pal2_a2.png`（已 pull 回本地并删除）。

---

## 6. 证据清单

| 文件 | 内容 |
|---|---|
| `pal2/pal2_a2.png` | A2 冷启动后截图（启动器壁纸 + app 图标，app 未绘帧） |
| `pal2/a2_launch.txt` | `am start -W` 原始输出（`Status: timeout` / `WaitTime: 60868`） |

> 说明：logcat / dumpsys 原文均已逐段摘录进本报告正文（§2.2 – §2.8），未单独落盘原始文件。

---

## 7. 未闭环 & 建议

### 7.1 未闭环清单

1. ❌ **命令面板底栏死区** —— 主判据 B1 未执行，无结论。
2. ❌ **遮罩点击关闭面板** —— B2 未执行，无结论。
3. ❌ **5558 设备可用性** —— 本轮起手即 BLOCKED。
4. ❌ `emulator-5558` 的负载归因 —— 到底是"宿主 8 台模拟器整体抢 CPU"还是"5558 自身 IO/模拟器卡"，本轮没区分（也没授权去动其它设备）。

### 7.2 给 Lead 的建议

- **不要再在 5558 上试了。** 连续两轮（5558-v98、5558-v98-p2）撞在同一堵墙上，签名一致：主线程 ART 类校验饿死 → 首帧出不来。
- 建议先做**前置健康门禁**，避免再浪费一轮 20 分钟：
  1. 冷启动 `am start -W`，要求 `Status: ok` 且 `TotalTime` < 5000ms；
  2. `dumpsys gfxinfo <pkg>` 必须已有非零帧；
  3. `mCurrentFocus` 非 null；
  4. 连续 3 次 `dumpsys activity processes | grep -c` 无 ANR 记录。
  **四条全过才允许进入 B1。**
- **降载方向**：bytecodes/s 31–455 说明 CPU 严重不足。要么把同时在线的模拟器数量砍到 2–3 台，要么给 5558 独占核。
- 若要真正测"死区"，**需要一个能正常出首帧的设备**——本轮无法提供该证据。

---

## 8. 纪律自检

- ✅ 未跑 gradle、未装机
- ✅ 未杀进程（仅对本 app 用了 `am force-stop`）
- ✅ 只操作 emulator-5558，未碰 127.0.0.1:5555 / 其它设备
- ✅ 全程未按 BACK(4)，未在展示层内按 ESC(111)
- ✅ 截图仅用 `screencap -p` + `adb pull`（未用 exec-out）
- ✅ 坐标运行期解析 —— **本轮未点击任何坐标**（B1/B2 未执行）
- ✅ 只动自建会话前缀 `E2ECHK5558q` —— **本轮未创建会话**
- ✅ 有证据才下结论 —— 判 BLOCKED，附原始 logcat / dumpsys / 截图
- ✅ 边做边写盘（本文件）
