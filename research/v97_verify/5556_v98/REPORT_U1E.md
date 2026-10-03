# U1-e 真机复验报告 — `pane` 真值钉死 + 方案 B 成立性 + 768 两格回归 + 连续 `wm` 切换进程存活性

- 测试员：mavis（U1-e 真机复验）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 被测修复：`research/v97_fix/QUEUE_TIMELINE_SPACE_FIX4.md`（**v101**：地板显式两分支 + `floorPx = maxOf(declared, measured)`）
- 上一轮：`research/v97_verify/5556_v98/REPORT_U1D.md`（v100；`readable = 51` 实测格）
- 设备：**emulator-5556** / `Medium_Desktop` / 1366x768@160 / SDK 34
- **未改任何代码。**

> ## 状态：**①✅ PINNED（P0 缺陷发现）/ ②⚠️ 基本一致（一处偏离）/ ③✅ 无回退 / ④❌ 是 app 崩溃（P0）/ 收尾⛔ 会话未删**
>
> | 格 | 结论 |
> |---|---|
> | **① `pane` 真值** | ✅ **钉死为 `51`**（不是 138）。真机 IME `mFrame` = `[0,213][1366,600]` ⇒ 真 IME 高 **387**，「≈300」分支被证伪 |
> | **② 1366x600 四数 + 方案 B** | ⚠️ `时间线 51 / 队列条 0 / overlap 0 / readable 51` **与公式逐位一致**；但 **composer 宿主 ≠ 0**（公式预测 0），实测 48px 且越 pane 底沿 4–5px |
> | **③ 1366x768 两格** | ✅ **无回退** — 12 条与 4 条**双双 `readable = 92`**，`Steer` h=43 / `×` h=48，与 `U1D` 逐字相同 |
> | **④ 连续 `wm` 切换** | ❌ **结论②：是 app 崩溃**（`FATAL` + `dumpsys` 崩溃计数），**非**正常重启；且**只在「缩窗」方向复现** |
> | **收尾（删会话）** | ⛔ **未完成** — 长按菜单间歇不触发（详见 §7） |

---

## 1. 构建核对（先于一切操作）—— ✅ 逐位命中

| 项 | 派单要求 | 设备实测 | 判定 |
|---|---|---|---|
| `base.apk` md5 | `62369878f4a1d7b5a8cc09c81344e88c` | **`62369878f4a1d7b5a8cc09c81344e88c`** | ✅ **逐位一致** |
| `lastUpdateTime`（设备时） | `2026-10-03 08:47:13` | **`2026-10-03 08:47:13`** | ✅ **逐位一致** |
| APK 路径 | — | `/data/app/~~w5koDeZFxGq4lT6rmYgunQ==/com.xiufeigo.opencodeshell-glAtV8qSowfdsepswPyT-Q==/base.apk` | — |
| `versionCode` / `versionName` | — | `26` / `1.4.0` | — |
| 设备时 | — | `Sat Oct 3 08:48:51 GMT 2026` | 与 `lastUpdateTime` 自洽 |
| AVD / SDK | Medium_Desktop / 34 | **`Medium_Desktop`** / **34** | ✅ |

> **判定：✅ 构建核对通过，未 BLOCKED。** 本轮全部读数归属被测 v101 构建。

---

## 2. 设备归属核对

| 时点 | `mCurrentFocus` | `pidof` | 结论 |
|---|---|---|---|
| **入测（原始状态）** | `org.chromium.home.pc/.HostActivity` | **EMPTY** | ⚠️ **承上一轮 `U1D` §8.1 的 BLOCKED 遗留**（焦点在 launcher、进程不在） |
| `am start` 本 app 后 | `…/OcActivity` | `21080` | ✅ 本 app |
| 每一轮交互前（`Assert-Focus` 硬闸） | `…/OcActivity` | — | ✅ 无一例外放行 |
| `wm` 切换 #1 后（缩窗） | `org.chromium.home.pc/.HostActivity` | **EMPTY** | ❌ 进程消失（见 §6） |
| `am start` 本 app 后 | `…/OcActivity` | `21622` | ✅ |
| `wm` 切换 #2 后（放窗） | `…/OcActivity` | `21622` | ✅ 存活 |
| **收尾** | `…/OcActivity` | `21622` | ✅ |

> **判定：测试全程焦点始终在本 app。** 唯一一次非本 app 窗口出现在 `wm` 缩窗导致进程崩溃后 ——
> 按纪律**未在 launcher 上做任何点击**（未按 BACK、未按 ESC、未点任何 launcher 元素），
> 仅用 `am start` **本 app** 恢复（纪律允许）。

---

## 3. 环境读数

| 项 | 读数 |
|---|---|
| `adb devices` | `emulator-5556` 在线（另 6 台，**本轮只碰 5556**；全程未碰 127.0.0.1:5555） |
| `wm size`（入测原值） | `Physical 3840x2160` / **`Override 1366x768`** |
| `wm density`（入测原值） | `Physical 320` / **`Override 160`** |
| `show_ime_with_hard_keyboard` | **`1`** ✅ 已先核（IME 可用硬键盘唤起） |
| 证据目录 | `research/v97_verify/5556_v98/u1e/`（88 个 dump XML、3 张截图、1 份 crashlog） |

### 3.1 `mInputShown` 断言（**每组高度读数前记录**）

| 读数组 | `mInputShown` | 采信 |
|---|---|---|
| 1366x600 · 3 帧 | **`true`** | ✅ 采信 |
| 1366x768 · 12 条 · 3 帧 | **`true`** | ✅ 采信 |
| 1366x768 · 4 条 · 3 帧 | **`true`** | ✅ 采信 |

---

## 4. ① `pane` 真值 —— 钉死为 **51**（本轮最关键）

### 4.1 用的「钥匙读法」

**`dumpsys window` 里的 IME `InsetsSource.mFrame`**（系统侧真值，与 app 的信念值相互独立）：

```
$ adb -s emulator-5556 shell dumpsys window | grep 'InsetsSource id=.*type=ime'
InsetsSource id=3 type=ime frame=[0,213][1366,600]
                  visibleFrame=[0,213][1366,600] visible=true flags=
```

> 同一读法在 `1366x768` 上交叉验证：`frame=[0,294][1366,768]`，
> 而 app 侧 composer 底沿实测 `y=296`（≈ 294 + 2dp 圆角）——
> **说明 app 的 IME 信念值在本设备上是准确的**，`mFrame` 可以直接当作 `believedImeBottomPx` 的真值代理。

### 4.2 两分支判据的裁决

`FIX4.md` §6.1 第 5 步给的两分支：**若 `pane 底沿`与 600 的差 ≈ 387 ⇒ §1.2 自洽解被证实；若明显更小（≈300）⇒ v101 有残留问题。**

| 读数 | 设备实测 | 判据分支 |
|---|---|---|
| IME `mFrame` | `[0,213][1366,600]` | — |
| **真实 IME 顶沿** | **`y = 213`** | — |
| **真实 IME 高度** | **`600 − 213 = 387`** | — |
| **`believedImeBottomPx`** | **`387`**（与真实值**逐位相等**） | ⇒ **命中「≈387」分支** |
| **「≈300」假设** | **被证伪**（真值是 387，不是 300） | ❌ |

### 4.3 算式（逐位自洽）

```
pane 顶 y = 162   （header 底沿，uiautomator 实测：header 按钮底 158，内容盒顶 162）
pane 底 y = 213   （= 真实 IME 顶沿，mFrame 实测）
⇒ pane = 213 − 162 = 51

交叉校验：162 + 51 + 387 = 600  ✔ 逐位自洽
```

### 4.4 ⇒ 明确回答

> ## **`pane` 是 `51`，不是 138。地板**不该**照付 64。**
>
> - `pane = 51 < 64` ⇒ `timelineViewportFloor(pane) = min(64, pane) = 51`（**声明让位**），**不是** 64。
> - **`64` 在 `1366x600` 这一格物理不可达**：`pane` 全部只有 51px，让位让到极限即 `readable = pane = 51`。
> - 真实 IME 就是 **387px**（占 600px 窗的 **64.5%**），**app 没有过量认领 IME** ——
>   这同时否掉了 `FIX4.md` §7.2「真正的 A 杠杆在改 IME 信念链」的前提：
>   **信念是准的，没有可回收的 87px**，`pane = 138` 那条路在真机上**不存在**。
> - ⇒ **`FIX4.md` §1.2 的自洽解被真机读数证实**：方案 B（显式让位 + pin 公式）的**前提成立**。

---

## 5. ② `1366x600` 四数表 + 方案 B 一致性

### 5.1 四数表（`mInputShown=true`，连读 3 帧，逐字相同）

| # | 帧 | `mInputShown` | 时间线盒 bounds | 盒高 | 队列条 bounds | **overlap** | **`readable`** | `Steer` | `×` |
|---|---|---|---|---|---|---|---|---|---|
| 1 | B1 | **true** | `0,162-1366,213` | **51** | **不存在** | **0** | **51** | **不在树（0 个）** | **不在树（0 个）** |
| 2 | B2 | **true** | `0,162-1366,213` | **51** | **不存在** | **0** | **51** | **不在树（0 个）** | **不在树（0 个）** |
| 3 | B3 | **true** | `0,162-1366,213` | **51** | **不存在** | **0** | **51** | **不在树（0 个）** | **不在树（0 个）** |

IME 侧同步读数（3 帧期间复核）：`mFrame=[0,213][1366,600]`（未漂移）。

### 5.2 与「声明的公式值」逐项对照 —— 方案 B 成立性

| 预算项 | 公式（`FIX4.md` §1.2，`pane=51`） | 设备实测 | 一致 |
|---|---|---|---|
| `pane` | `600 − 162 − 387` = **51** | **51** | ✅ |
| `floor` | `min(64, 51)` = **51** | 时间线盒 = 51 | ✅ |
| 队列条 `bar` | `51 − 64 − 56 < 50` ⇒ **0** | **不存在** | ✅ |
| `overlap` | bar=0 ⇒ **0** | **0** | ✅ |
| 时间线 `readable` | `max(51 − hostPaid, 51)` = **51** | **51** | ✅ |
| **`Steer` / `×`** | bar=0 ⇒ **整条不画 ⇒ 不可达** | **0 个 / 0 个** | ✅（**与设计一致**） |
| 宿主 `host` | `hostCeiling = 51 − 51 = 0` ⇒ **0** | ⚠️ **48（`EditText y=169..217`）** | ❌ **偏离** |

> **判定：方案 B 的「时间线 / 队列条 / overlap / readable」四数与 pin 公式**逐位一致**，
> 让位是**声明的公式值**（`floor = 51`），不是沉默的巧合。**
> **`Steer`/`×` 在该窗不可达** —— 但这是**声明让位的一部分**（`bar = 0` ⇒ 整条不画），
> 与 `FIX4.md` §4.2 的 pin「队列条要么整条不画，要么至少画出一整行 32px」**自洽**，**不是缺陷**。

### 5.3 ⚠️ 诚实标注：一处与公式**不一致**的读数

公式预测 `hostCeiling = pane − floor = 51 − 51 = 0` ⇒ composer 宿主应为 **0**。
**设备实测 composer 仍然被画出来了**：

```
EditText  y=169..217  (h=48)
Send      y=170..218  (h=48)
Add       y=170..218  (h=48)
```

⇒ **两点偏离，如实记录**：

1. **`hostPaid` 没有让到 0**（实测 ≈48，公式预测 0）；
2. **composer 底沿 `217/218` 比 pane 底沿 `213` 低 4–5px** —— 即**画到了 pane 之外、伸进了 IME 区域**。

> 这**不影响** `readable` 的判读（时间线盒仍是 51，四数仍与公式一致），
> 但它说明 **`composerHostCeilingPx` 的 `0` 并没有在设备上真正约束住 composer 的绘制**。
> 上一轮 `U1D` 未记录宿主读数，故**无法判断这是 v101 引入还是 v100 既有**。
> **⇒ 列为未闭环项 §8.4，请 Lead 定。**

---

## 6. ④ 连续 `wm` 切换的进程存活性 —— **结论②：是 app 崩溃**

### 6.1 复现经过（有度，共 2 轮 `wm` 切换，未超 3 轮上限）

| 轮次 | 操作 | 切换方向 | 切换前 pid | 切换后 pid | 结果 |
|---|---|---|---|---|---|
| — | 入测 | — | **EMPTY** | — | 承 `U1D` §8.1 遗留，进程已不在 |
| — | `am start` 本 app | — | EMPTY | `21080` | ✅ 起活 |
| **#1** | `wm size 1366x600` + `wm density 160` | **缩**（768→600） | `21080` | **EMPTY** | ❌ **崩溃** |
| — | `am start` 本 app | — | EMPTY | `21622` | ✅ 起活 |
| **#2** | `wm size 1366x768` + `wm density 160` | **放**（600→768） | `21622` | **`21622`** | ✅ **存活** |

> **⇒ 崩溃只发生在「缩窗」方向；「放窗」方向进程存活。**
> **⇒ 因此不需要第 3 轮即可定性**（已取得决定性证据），提前收手。

### 6.2 `logcat` 证据 —— **决定性**

```
10-03 08:53:12.878 21080 21080 E AndroidRuntime: FATAL EXCEPTION: main
10-03 08:53:12.878 21080 21080 E AndroidRuntime: Process: com.xiufeigo.opencodeshell, PID: 21080
10-03 08:53:12.878 21080 21080 E AndroidRuntime: java.lang.IllegalArgumentException: maxHeight(12) must be >= minHeight(64)
10-03 08:53:12.878 21080 21080 E AndroidRuntime: 	at androidx.compose.ui.unit.Constraints.copy(Constraints.kt:759)
10-03 08:53:12.878 21080 21080 E AndroidRuntime: 	at com.xiufeigo.opencodeshell.ui.session.SessionScreenKt$SessionPaneArrangement$2$1.measure-3p2s80s(SessionScreen.kt:3250)
...
10-03 08:53:12.990   560  1943 W ActivityTaskManager:   Force finishing activity com.xiufeigo.opencodeshell/.OcActivity
10-03 08:53:13.166   560  1529 I WindowManager: WIN DEATH: Window{6ea5a0a u0 …/OcActivity}
10-03 08:53:13.169   560   577 I ActivityManager: Process com.xiufeigo.opencodeshell (pid 21080) has died: fg  TOP
```

`dumpsys activity processes`（**独立第二来源**）：

```
Process com.xiufeigo.opencodeshell uid 10189: last crashed +40s383ms ago
Process com.xiufeigo.opencodeshell uid 10189: first crashed +40s383ms ago; crashes since then: 1
```

`/data/tombstones/`：**Permission denied**（未取得 native tombstone；但本崩溃是 **Java 层 `IllegalArgumentException`**，
本就不产生 native tombstone，两条证据链一致）。

> **崩溃类型：Compose `Constraints.copy` 抛 `IllegalArgumentException`（Java/Kotlin 异常），
> 非 native crash、非 ANR、非 LMK 杀进程。**

### 6.3 ⇒ 三选一结论：**② 是 app 崩溃**

| 选项 | 判定 | 依据 |
|---|---|---|
| ① 正常进程重启 | ❌ 排除 | 正常重启不会产生 `FATAL EXCEPTION`、不会 `Force finishing activity`、不会 `WIN DEATH`；`dumpsys` 明确记 `last crashed` / `crashes since then: 1` |
| **② app 崩溃** | ✅ **选中** | `FATAL EXCEPTION` + 完整 Java 栈 + `dumpsys` 崩溃计数 + `Force finishing`，四源一致 |
| ③ 证据不足 | ❌ 排除 | 异常类型、抛出点、进程号、系统侧崩溃计数全部拿到 |

### 6.4 与 `wm density` 变更的关系

- **相关，但更准确的说法是「缩窗过渡期的一次瞬态测量」**：
  崩溃发生在 `wm size 1366x600`（**改高度**）之后；`wm density` 在本例中同批执行，但
  **放窗方向（同样改了 density）不崩** ⇒ **决定变量是窗口高度变小，不是 density 本身**。
- **根因（按栈定位）**：`SessionScreen.kt:3250` 正是 `minHeight = floorPx,` 那一行。
  缩窗动画期间存在一帧 `constraints.maxHeight = 12` 的**瞬态**测量，
  而 v101 的 `floorPx = maxOf(declaredFloorPx, measuredFloorPx)` 此刻为 **64**，
  ⇒ `Constraints.copy(minHeight = 64, maxHeight = 12)` ⇒ **抛异常**。

> **⇒ 这是一个 P0 级缺陷：任何导致窗口变矮的 `wm size` 变更 / 分屏 / 旋转 / 显示尺寸变化，
> 都会让本 build 崩溃。**
> **`FIX4.md` §2.3(c) 声称这三行「与 v100 逐字相同」—— 但 §2.3(b) 新增的
> `maxOf(declaredFloorPx, measuredFloorPx)` 正是把 `floorPx` 顶到 64、
> 使 `minHeight` 永远不随瞬态 `maxHeight` 收缩的那一步。**
> 旁证：`U1D`（v100，md5 `e55f9808…`）在**同一个 768→600 缩窗**下**没有崩溃**，
> 且成功取到 `readable = 51`。⇒ **强烈指向 v101 引入的回归。**
> ⚠️ **但本轮只测了 v101，未回测 v100，故「回归」为高置信推断、非实锤。**

---

## 7. ③ `1366x768` 两格回归 —— ✅ **无回退**

### 7.1 格 ①：队列 ≥11 条（实测 **12 条**）

计数标签 `12 queued @ 24,192-78,198`；同期 `Stop` 仍在 ⇒ 回合运行中、队列未被排空。

| # | 帧 | `mInputShown` | 时间线盒 | 盒高 | 队列条 | **overlap** | **`readable`** | `Steer` | h | clk | `×`(Remove) | h | clk | 队列行/视口 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | m1 | **true** | `162..302` | **140** | `198..246` | **48** | **92** ✅ | `1263,199-1302,242` | **43** | **true** | `1302,198-1350,246` | **48** | **true** | 视口 `18,206-1348,238` h=**32** |
| 2 | m2 | **true** | `162..302` | **140** | `198..246` | **48** | **92** ✅ | 同上 | **43** | **true** | 同上 | **48** | **true** | 同上 |
| 3 | m3 | **true** | `162..302` | **140** | `198..246` | **48** | **92** ✅ | 同上 | **43** | **true** | 同上 | **48** | **true** | 同上 |

### 7.2 格 ②：队列 2–5 条（实测 **4 条**）

| # | 帧 | `mInputShown` | 时间线盒 | 盒高 | 队列条 | **overlap** | **`readable`** | `Steer` | h | clk | `×`(Remove) | h | clk | 队列视口 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | d1 | **true** | `162..302` | **140** | `198..246` | **48** | **92** ✅ | `1263,199-1302,242` | **43** | **true** | `1302,198-1350,246` | **48** | **true** | `18,206-1348,238` h=**32** |
| 2 | d2 | **true** | `162..302` | **140** | `198..246` | **48** | **92** ✅ | 同上 | **43** | **true** | 同上 | **48** | **true** | 同上 |
| 3 | d3 | **true** | `162..302` | **140** | `198..246` | **48** | **92** ✅ | 同上 | **43** | **true** | 同上 | **48** | **true** | 同上 |

### 7.3 与 `REPORT_U1D` 的逐项回退检查

| 读数 | `REPORT_U1D`（v100） | 本轮（v101） | 判定 |
|---|---|---|---|
| ≥11 格 `readable` | **92**（14 条） | **92**（12 条） | ✅ **无回退** |
| ≥11 格 时间线盒高 / overlap | 140 / 48 | **140 / 48** | ✅ 逐字相同 |
| ≥11 格 `Steer` / `×` | 43 / 48，`clickable=true` | **43 / 48**，`clickable=true` | ✅ 逐字相同 |
| 2–5 格 `readable` | 92（14 条档）/ 92（3 条档） | **92**（4 条） | ✅ **无回退** |
| 队列行高 / 视口 | 32 | **32** | ✅ |
| 深度 4 → 12 是否改变 `readable` | 深度 3→14 恒 92 | **深度 4→12 恒 92** | ✅ 复现「深度不改变 `readable`」 |

> **判定：✅ 两格均无回退，且与 `U1D` 逐字相同。**
> **⇒ v101 在 `pane ≥ 64`（768 窗）这一侧确实做到了 §4.1「地板一分没降」，零影响被证实。**
> **崩溃只发生在「缩窗过渡的瞬态测量」，稳定的 768 / 600 布局本身都正常。**

---

## 8. 收尾

| 项 | 结果 |
|---|---|
| `wm size` / `wm density` | ✅ **已复核 = 入测原值 `1366x768` / `160`**（本轮未 `reset` 到 Physical，因入测原值本就是 Override） |
| `/sdcard` 残留 | ✅ **CLEAN**（`ls /sdcard/ | grep u1e_` 无输出；每次 pull 后即 `rm -f`） |
| `mCurrentFocus`（收尾） | ✅ `…/OcActivity` |
| `pidof`（收尾） | ✅ `21622`（存活） |
| **`wm` 切换轮数** | **2 轮**（≤3 上限） |
| **自建会话清理** | ⛔ **未完成** |
| 代码改动 | **无**（只读复验） |
| 证据目录 | `research/v97_verify/5556_v98/u1e/`（88 XML + 3 PNG + `u1e_crashlog.txt` 772KB） |

### 8.1 ⛔ 会话未删的经过（如实记录）

1. 目标：① 本轮自建会话 `Request for a 100-part long-form guide`；② 上一轮遗留 `Extreme guide to assigning numbers to each paragraph for easier tracking`。
2. **第一次尝试**：长按 → 菜单出现（`Rename` / `Delete`）→ 点 `Delete` → **确认弹窗的 `Delete` 按钮 `enabled="false"`**（原树：`clickable="true" enabled="false"`）
   ⇒ **回合仍在运行，app 禁止删除**。
3. **处置**：点遮罩关弹窗 → 进入本会话 → 点 `Stop` **停掉回合**（`Stop` 由有变无，回合确认结束）。
4. **第二次尝试（长按，5 种手势 × 多轮）**：`input swipe` 同点 900ms / 1000ms / 1200ms / 2000ms、
   含 2px 漂移长按手势、以及 `input motionevent DOWN/UP` 间隔 1500ms
   ⇒ **菜单一律不再出现**（`Rename` / `Delete` 计数恒为 0），反复 8 次均如此。
5. **第三次尝试（改走会话内 `More options`）**：进入会话后点 `More options`，
   但**导航抽屉此后一直卡在打开态**（`Close navigation menu` 遮罩持续存在），
   点遮罩（`900,400` / `1100,600`）与点行**均无响应**，会话内菜单**始终没能打开**。
6. 期间会话列表还会重排（新增若干 `Explore …` 行、行位漂移），行位在多次尝试间不稳定。
7. ⇒ **未能在「回合已停」的状态下删除任何一个会话**，两个会话**均未删除**。
8. **未做的事**：未按 BACK(4)、未按 ESC(111)、未触碰任何非本轮/非授权会话
   （`E2ECHK5556r probe attachment check`、`Counting 1 to 40…`、`Slow counting from 1 to 40…`、`Untitled` 等**一律未动**）。

> **⇒ 承 `U1D` §9.2 的未闭环项仍未闭环**，且本轮新增一条：**停掉回合并不能解除删除禁用**。

---

## 9. 未闭环

1. **🔴 P0：缩窗崩溃**（§6）。`maxHeight(12) < minHeight(64)` ⇒ `IllegalArgumentException`
   @ `SessionScreen.kt:3250`。**建议**：把 `minHeight` 夹到 `min(floorPx, constraints.maxHeight)`，
   或在瞬态帧用 `declaredFloorPx`（不 `maxOf` 测量值）。**这是本轮最该先修的一条。**
2. **「v101 引入 vs v100 既有」未实锤**：仅测 v101；`U1D`（v100）无崩溃为**旁证**，未回测。
3. **`1366x600` 下 composer 宿主 `hostPaid ≠ 0`**（实测 48，公式预测 0）且**越 pane 底沿 4–5px**（§5.3）。
   `composerHostCeilingPx` 的 `0` 未在设备上真正约束住绘制。
4. **极端窗口下「队列深度对 `readable` 的影响」仍未验证**（承 `U1D` §9.3）：
   600px 窗里 `bar` 恒为 0，**队列条整条不画** ⇒ 深度维度在该窗**物理上不可达**。
5. **composer 3 行档（`card = 116`）** 仍未取到（承 `U1D` §9.5）。
6. **滚动后被裁行 `Steer`/`×` < 32** 的门槛口径待 Lead 明确（承 `U1D` §9.4）——本轮未复现该场景。
7. **自建会话 + `U1D` 遗留会话未删**（§8.1）。
8. **`640/480` 更极端窗未测**：真机已知「真实 IME 高 387 = 窗高 64.5%」，
   按公式窗高再降 14px 即 `pane < 64` 之外还可能触发 §6 的**放窗**方向问题（未测）。

---

## 10. 回报

**状态：①✅ PINNED（发现 P0）/ ②⚠️ 基本一致（一处偏离）/ ③✅ 无回退 / ④❌ app 崩溃（P0）/ 收尾⛔ 会话未删**

- **构建核对**：✅ md5 `62369878f4a1d7b5a8cc09c81344e88c` 与 `lastUpdateTime = 2026-10-03 08:47:13`
  **均逐位命中**，未 BLOCKED。
- **① `pane` 真值 = `51`（不是 138）；地板**不该**照付 64。**
  钥匙读法 = `dumpsys window` 的 IME `InsetsSource.mFrame`：**`[0,213][1366,600]`** ⇒ 真 IME 顶沿 **213**、
  真 IME 高 **387**，与 `believedImeBottomPx` **逐位相等** ⇒ 「≈300 / pane=138」分支**被证伪**，
  `pane = 213 − 162 = 51 < 64` ⇒ **声明让位**，`64` 在该窗**物理不可达**，且 app **没有**过量认领 IME。
- **② `1366x600` 四数 = 时间线 51 / 队列条 0（不存在）/ overlap 0 / `readable` 51**，3 帧 `mInputShown=true` 逐字相同，
  **与方案 B 的 pin 公式逐位一致**（`floor = min(64,51) = 51`、`bar = 0`、`timeline = max(51−0, 51) = 51`）。
  **`Steer`/`×` 不可达（各 0 个）—— 但这是 `bar = 0` 整条不画的声明让位，与设计自洽，不是缺陷。**
  ⚠️ **一处偏离**：composer 宿主实测 **48px**（公式预测 0）且**越 pane 底沿 4–5px**。
- **③ `1366x768` 两格均无回退**：12 条与 4 条**双双 `readable = 92`**（时间线 140 / 队列条 48 / overlap 48），
  `Steer` h=**43**、`×` h=**48**，均 `clickable=true`，队列视口 h=**32** —— 与 `REPORT_U1D` **逐字相同**。
- **④ 进程消失 = ② 是 app 崩溃**（不是正常重启）：`FATAL EXCEPTION: java.lang.IllegalArgumentException:
  maxHeight(12) must be >= minHeight(64)` @ `SessionScreen.kt:3250`（`minHeight = floorPx`），
  另有 `Force finishing activity` / `WIN DEATH` / `has died: fg TOP` 与
  `dumpsys activity processes` 的 `last crashed … crashes since then: 1` 四源互证。
  **第 1 次 `wm` 切换（768→600 缩窗）即崩溃；第 2 次（600→768 放窗）存活** ⇒ 与「变矮」相关，非 density 本身。
- **收尾**：`wm` 已复核 `1366x768`/`160`（入测原值）、`/sdcard` CLEAN、焦点在 `…OcActivity`、pid `21622` 存活；
  ⛔ **两个会话均未删除**（停掉回合后长按菜单不再出现）。
- **报告路径**：`research\v97_verify\5556_v98\REPORT_U1E.md`
