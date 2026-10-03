# P0 缩窗崩溃复验报告 — 往返缩窗不得崩（v102 `safeViewportFloorPx` 夹取修复）

- 测试员：mavis（P0 缩窗崩溃真机复验，唯一验收人）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 被测修复：`research/v97_fix/CRASH_SHRINK_FIX.md`（v102）
- 崩溃现场：`research/v97_verify/5556_v98/REPORT_U1E.md` §6
- 设备：**emulator-5556** / `Medium_Desktop` / 1366x768@160dpi / SDK 34
- **未改任何代码，未跑 gradle，未装机，未杀进程。**

> ## 状态：**主判据（往返缩窗不崩）✅ PASS**｜构建核对 ✅｜设备归属 ✅｜收尾 ✅（`wm` 已复核、会话未删）
>
> | 判据 | 结论 | 一句话证据 |
> |---|---|---|
> | **构建核对** | ✅ **逐位命中** | md5 `d9e13ca3b238247ec0577927750e2a61` / `lastUpdateTime 2026-10-03 09:23:04` |
> | **设备归属** | ✅ **全程本 app** | 15 档 `mCurrentFocus` 无一例外 `OcActivity` |
> | **⭐ 主判据：往返缩窗不崩** | ✅ **PASS** | **3 轮 × 5 档 = 15 档，pid 恒 `24697`、`FATAL EXCEPTION` / `IllegalArgumentException` / `maxHeight` **零命中**、崩溃计数恒 `0`** |
> | **1366x600 界面可用性 + 四数** | ✅ **通过** | `51 / 队列条无 / overlap 0 / readable 51`；**34 节点、21 可点击、composer + 3 滚动容器在，不是空壳** |
> | **1366x768 时间线盒** | ✅ **无回退** | `[0,162][1366,302]` = **140**，与 `U1D` / `U1E` 逐位相同 |
> | **1366x768 队列两格** | ⛔ **未取到** | 队列条本轮未渲染（`'queued'` 命中 **0**）⇒ `92 / Steer 43 / × 48` **不作判定**（§7.2） |
> | **收尾 `wm`** | ✅ **已复核** | `Override size: 1366x768` / `Override density: 160` |
> | **收尾 `/sdcard`** | ✅ **CLEAN** | 本轮 21 个残留已删，复扫无命中 |
> | **收尾 会话清理** | ⛔ **未完成** | 删除菜单存活窗口 < 一次 dump 耗时，且焦点漂到 `Pop-Up Window`（§8.1） |
>
> **⇒ 对派单主问题的直接回答：`maxHeight(12) must be >= minHeight(64)` 在真机上不再复现，
> 往返缩窗 3 轮全程零崩溃。**

---

## 1. 构建核对（先于一切操作）—— ✅ 逐位命中

| 项 | 派单要求 | 设备实测 | 判定 |
|---|---|---|---|
| `base.apk` md5 | `d9e13ca3b238247ec0577927750e2a61` | **`d9e13ca3b238247ec0577927750e2a61`** | ✅ **逐位一致** |
| `lastUpdateTime`（设备时） | `2026-10-03 09:23:04` | **`2026-10-03 09:23:04`** | ✅ **逐位一致** |
| APK 路径 | — | `/data/app/~~Td_ZKTnbJukV56I7p1iFsw==/com.xiufeigo.opencodeshell-ELsoEYUxRW12t-f9jW1Ttg==/base.apk` | — |
| `versionCode` / `versionName` | — | `26` / `1.4.0` | — |
| 设备时 | — | `Sat Oct  3 09:24:31 GMT 2026` | 与 `lastUpdateTime` 自洽 |
| AVD / SDK | Medium_Desktop / 34 | **`Medium_Desktop`** / **34** | ✅ |

md5 取自设备侧 `md5sum`（非本地重算），路径取自 `pm path`。

> **判定：✅ 构建核对通过，未 BLOCKED。** 本轮全部读数归属被测 v102 构建。

---

## 2. 设备归属核对

| 时点 | `mCurrentFocus` | `pidof` | 结论 |
|---|---|---|---|
| **入测（原始状态）** | `org.chromium.home.pc/.HostActivity` | **EMPTY** | ⚠️ **承 `U1E` §2 / `U1D` §8.1 的 BLOCKED 遗留**（焦点在 launcher、进程不在） |
| `am start` 本 app 后 | `…/OcActivity` | **`24697`** | ✅ 本 app |
| 往返 3 轮共 **15 个读数档** | `…/OcActivity`（**无一例外**） | `24697` | ✅ 焦点始终在本 app |
| `1366x600` 界面可用性档 | `…/OcActivity` | `24697` | ✅ |

> **判定：✅ 设备归属全程通过。** 唯一一次非本 app 窗口是入测时的 launcher 遗留；
> 按纪律**未在 launcher 上做任何点击**（未按 BACK、未按 ESC、未点任何 launcher 元素），
> 仅用 `am start` **本 app** 恢复（纪律允许）。

---

## 3. 环境读数

| 项 | 读数 |
|---|---|
| `adb devices` | `emulator-5556` 在线（共 8 台，**本轮只碰 5556**；**全程未碰 127.0.0.1:5555**） |
| `wm size`（入测原值） | `Physical 3840x2160` / **`Override 1366x768`** |
| `wm density`（入测原值） | `Physical 320` / **`Override 160`** |
| `show_ime_with_hard_keyboard` | **`1`** ✅ 已先核（IME 可用硬键盘唤起） |
| 证据目录 | `research/v97_verify/5556_v98/p0/` |

---

## 4. 基线（`logcat -c` 之后）

| 项 | 读数 |
|---|---|
| `adb logcat -c` | ✅ **已执行**（`am start` 之后、任何 `wm` 操作之前） |
| `pidof com.xiufeigo.opencodeshell` | **`24697`** |
| `dumpsys activity processes` 崩溃计数 | **`crashes since then: 0`** / **`last crashed = none`**（**该进程块内无 `last crashed` 行 ⇒ 本次开机以来从未崩溃**） |
| 基线 logcat 命中 | **CLEAN（0 hits）** |

---

## 5. 往返缩窗 ×3 轮（**主判据**）

方法（每轮）：`wm size 1366x768` + `wm density 160` → `wm size 1366x600` + `wm density 160` → 回 `1366x768`。
**每一档**取四读数：pid / `mCurrentFocus` / `logcat -d` 命中 / `dumpsys` 崩溃计数。
`logcat` 检索键：`FATAL EXCEPTION|IllegalArgumentException|maxHeight`（并**追加** `Force finishing activity` / `WIN DEATH` / `has died` 三个伴随键）。

### 5.1 第 1 轮

| 档 | `pidof` | `mCurrentFocus` | logcat 命中 | 崩溃计数 |
|---|---|---|---|---|
| A `1366x768`（稳） | **`24697`** | ✅ `…OcActivity` | **CLEAN (0 hits)** | `0` / `none` |
| B `1366x600` t+0.8s（**瞬态**） | **`24697`** | ✅ `…OcActivity` | **CLEAN (0 hits)** | `0` / `none` |
| B2 `1366x600`（稳） | **`24697`** | ✅ `…OcActivity` | **CLEAN (0 hits)** | `0` / `none` |
| C `1366x768` t+0.8s（**瞬态**） | **`24697`** | ✅ `…OcActivity` | **CLEAN (0 hits)** | `0` / `none` |
| C2 `1366x768`（稳） | **`24697`** | ✅ `…OcActivity` | **CLEAN (0 hits)** | `0` / `none` |

### 5.2 第 2 轮

| 档 | `pidof` | `mCurrentFocus` | logcat 命中 | 崩溃计数 |
|---|---|---|---|---|
| A `1366x768`（稳） | **`24697`** | ✅ `…OcActivity` | **CLEAN (0 hits)** | `0` / `none` |
| B `1366x600` t+0.8s（**瞬态**） | **`24697`** | ✅ `…OcActivity` | **CLEAN (0 hits)** | `0` / `none` |
| B2 `1366x600`（稳） | **`24697`** | ✅ `…OcActivity` | **CLEAN (0 hits)** | `0` / `none` |
| C `1366x768` t+0.8s（**瞬态**） | **`24697`** | ✅ `…OcActivity` | **CLEAN (0 hits)** | `0` / `none` |
| C2 `1366x768`（稳） | **`24697`** | ✅ `…OcActivity` | **CLEAN (0 hits)** | `0` / `none` |

### 5.3 第 3 轮

| 档 | `pidof` | `mCurrentFocus` | logcat 命中 | 崩溃计数 |
|---|---|---|---|---|
| A `1366x768`（稳） | **`24697`** | ✅ `…OcActivity` | **CLEAN (0 hits)** | `0` / `none` |
| B `1366x600` t+0.8s（**瞬态**） | **`24697`** | ✅ `…OcActivity` | **CLEAN (0 hits)** | `0` / `none` |
| B2 `1366x600`（稳） | **`24697`** | ✅ `…OcActivity` | **CLEAN (0 hits)** | `0` / `none` |
| C `1366x768` t+0.8s（**瞬态**） | **`24697`** | ✅ `…OcActivity` | **CLEAN (0 hits)** | `0` / `none` |
| C2 `1366x768`（稳） | **`24697`** | ✅ `…OcActivity` | **CLEAN (0 hits)** | `0` / `none` |

### 5.4 与 `REPORT_U1E` §6.2 崩溃现场的逐项对照

| 崩溃特征（U1E，v101） | 本轮（v102） |
|---|---|
| `FATAL EXCEPTION: main` | **未出现** |
| `IllegalArgumentException: maxHeight(12) must be >= minHeight(64)` | **未出现** |
| `at …SessionScreen.kt:3250` | **未出现** |
| `Force finishing activity com.xiufeigo.opencodeshell/.OcActivity` | **未出现** |
| `WIN DEATH: Window{… OcActivity}` | **未出现** |
| `Process … (pid 21080) has died` | **未出现**（pid 恒 `24697`） |
| `last crashed +40s… / crashes since then: 1` | **未出现**（`crashes since then = 0`，`last crashed = none`） |

> **判定：✅ 主判据 PASS。**
> 崩溃是**同一 build 上第 1 次缩窗即崩**的 P0（U1E §6.1 轮次 #1，pid `21080` 消失）。
> 本轮 **3 轮 × 5 档 = 15 档，pid 恒 `24697`（同 pid，非重启）**，
> **logcat 六键全零命中**（三主键 + 三伴随键），**`dumpsys` 崩溃计数恒 `0`、无 `last crashed` 行**。
> ⇒ **`maxHeight(12) must be >= minHeight(64)` 在真机上不再复现。**

> **⚠️ 诚实的证据边界（如实标注）**：本轮 15 档读数是**在 `wm size` 命令返回后 0.8s / 3s 取的**，
> 属**稳态与准稳态采样**。`wm size` 本身是**同步屏障**（命令返回即配置已生效），
> 但崩溃若发生在**命令返回之前**的过渡期动画内，理论上存在被采样间隔漏掉的可能。
> 缓解证据：① 三条**独立来源**（`pidof` 恒定、`logcat` 全零、`dumpsys` 计数恒 0）同时为负；
> ② 进程若崩溃，`pidof` 必为空、`Force finishing` / `WIN DEATH` / `has died` 必入 logcat，
> **这四个键本轮全部零命中**，覆盖的是整个 logcat 缓冲区而非采样瞬间。
> ⇒ 采样间隔不足的残余风险**很低**，但**不宣称"已证明过渡期每一帧都无异常"**。

---

## 6. 1366x600 界面可用性 + 四数 —— ✅ 界面真的渲染，不是空壳

### 6.1 读数前置断言

| 项 | 读数 |
|---|---|
| `mInputShown` | **`true`** ✅ **已断言并记录**（`dumpsys input_method`） |
| IME `InsetsSource.mFrame` | **`[0,213][1366,600]`** `visible=true` |
| `mCurrentFocus` | `…OcActivity` ✅ |
| `pidof` | `24697`（与基线同 pid） |
| dump 方式 | `uiautomator dump --compressed`（按纪律优先 `--compressed`），`text="…"` 由 XML 解析器读取（同时兼容 `text='…'`） |
| 坐标 | **运行期从 dump 解析**（无硬编码坐标） |

### 6.2 四数（`1366x600`）

| 项 | 读数 | 与 `REPORT_U1E` §5.1 对照 |
|---|---|---|
| **时间线盒** | **`[0,162][1366,213]`** ⇒ 盒高 **51** | ✅ 逐位相同（U1E：`0,162-1366,213` = 51） |
| **队列条** | **不存在**（树中 0 个 `Steer` / 0 个 `×`） | ✅ 逐位相同（U1E 同为「不存在」） |
| **overlap** | **0** | ✅ 逐位相同 |
| **`readable`** | **51** | ✅ 逐位相同 |

### 6.3 界面可用性断言（**关键：不是空壳**）

| 指标 | 读数 | 判定 |
|---|---|---|
| 节点总数 | **34** | ✅ 正常（U1E 同量级） |
| `clickable="true"` 节点 | **21** | ✅ 可交互面完整 |
| `EditText` | **1** | ✅ composer 在（`[24,169][405,217]` h=48） |
| `ScrollView` | **3** | ✅ 时间线滚动容器在 |
| 时间线容器 | 存在，`[0,162][1366,213]` | ✅ |
| 时间线内文本 | `Interrupted` `[629,162][698,172]` | ✅ 有真实内容 |
| 头部按钮 | `Home` / `Close tab` / `New session` / `Command palette` 全在 | ✅ |
| 会话栏按钮 | `Back` / `Open sessions` / `Search session` / `Ask a side question` / `Session summary` / `Context usage` / `More options` / `Toggle review panel` 全在 | ✅ |
| 模型行 | `Space Bunny Free` | ✅ |
| 截图 | `p0/E2ECHK5556u1f_g600_1.png`（`screencap -p` + `adb pull`） | ✅ |

> **判定：✅ 界面真的渲染 —— 时间线容器存在、34 个节点、21 个可点击、composer + 3 个滚动容器 + 完整头/会话栏，
> 不是空壳。**
>
> **⇒ 四数与 U1E（v101 崩溃前）逐位相同（`51 / 不存在 / 0 / 51`）**，
> **夹取 `safeViewportFloorPx` 在 600 窗的稳态帧上是恒等**（`declared = 51 = maxHeight` ⇒ `min(51,51) = 51`），
> 与 `CRASH_SHRINK_FIX.md` §4 表格第 2 行（`1366x600 稳态 → 恒等`）**预测一致**。
> **⇒ 未引入新的几何退化。**

> **⚠️ 承 `U1E` §5.3 的已知偏离（本轮未新增、未修复）**：600 档 composer 宿主仍为
> `EditText [24,169][405,217]` / `Send` / `Add`（h=48），底沿 `217/218` 仍比 pane 底沿 `213` 低 **4–5px**，
> 公式预测的 `hostCeiling = 0` 仍未在设备上约束住绘制。
> `CRASH_SHRINK_FIX.md` §6 已定性为**不同根因、本轮不改**，且其 §6 表格第 3 行明确指出
> 600 稳态帧夹取**恒等** ⇒ **本改动在数值上不可能影响它**。本轮读数与该分析**吻合**，作为未闭环项承继。

## 7. 1366x768 两格回归（不得回退）—— ⚠️ **部分：队列两格未取到，时间线格已对齐**

### 7.1 读数前置断言

| 项 | 读数 |
|---|---|
| `mInputShown` | **`true`** ✅ 已断言并记录 |
| `mCurrentFocus` | `…OcActivity` ✅ |
| `pidof` | `24697`（全程同 pid） |
| `logcat` | **CLEAN (0 hits)** |
| 崩溃计数 | `crashes_since_then=0` / `last_crashed=none` |

### 7.2 格 ①（队列 ≥11 条）—— ⛔ **未取到队列**

**按纪律先点 Queue chip / 造队列的过程（如实记录）**：

1. `New session` → `Create session` 建出自建会话（初始标题 `Untitled`）。
2. 首发 `E2ECHK5556u1f+shrinkshrink+Q01` ⇒ 回合启动（`Working` 在树、`Send` 变 `Stop`）。
3. 回合运行中用 `input text` + `KEYCODE_ENTER` 连续灌入 `Q02…Q24`（**composer 坐标运行期从 dump 的 `EditText` 解析** = `683,232`，无硬编码）。
4. **结果：消息确实进了时间线** —— dump 中可见 `Q01`…`Q24` 的条目（`Q01` 出现在时间线与会话栏两处），
   节点总数从 34 涨到 **61**。
5. **但队列条（`Steer` / `×` / `N queued` 标签）始终未渲染**：
   - `REPORT_U1E` §7.1 记录的队列条形态是 `198..246` 带 `Steer` h=43 / `×` h=48 且带 `12 queued` 计数标签；
   - 本轮在 `x≥1200, y∈[190,260]` 区域反复扫描，**始终只扫到 `Open terminal` / `Send` / `Stop`**，
     **0 个 `Steer`、0 个 `×`、0 个含 `queued` 的节点**；
   - 对 dump 原文做正则统计：**`'queued' 命中数 = 0`**。
6. 曾尝试关闭右侧 review panel 覆盖区（`Toggle review panel`）后重扫，仍无队列条。
7. ⇒ **本 build 在本轮操作路径下未能造出「≥11 条队列」这一格状态。**

> **⚠️ 如实标注：本轮未能复现 U1D/U1E 的队列格，因此 ① 格（≥11 条）的
> `92px / Steer h=43 / × h=48 全 clickable` 三个读数本轮「未取到」，不能声称已对齐。**

### 7.3 格 ②（队列 2–5 条）—— ⛔ **同样未取到**（同 §7.2 原因）

队列条既未渲染，2–5 条深度档自然也无法构造。**不重复同一失败尝试**（有度）。

### 7.4 ✅ 实际取到的 1366x768 读数（**时间线格，与 U1D/U1E 对齐**）

| 项 | 本轮（v102） | `U1E` §7（v101） | `U1D`（v100） | 判定 |
|---|---|---|---|---|
| `mInputShown` | `true` | `true` | — | ✅ |
| **时间线盒** | **`[0,162][1366,302]`** | `162..302` | `162..302` | ✅ **逐位相同** |
| **时间线盒高** | **140** | **140** | **140** | ✅ **逐位相同** |
| 队列条 | 未渲染（`Steer`/`×` 均 0 个） | `198..246` | `198..246` | ⛔ 本轮无队列 |
| **overlap** | **0**（无队列条 ⇒ 无重叠） | **48** | **48** | ⚠️ **因队列条缺席而不同** |
| **`readable`** | **140**（= 盒高 − 0） | **92** | **92** | ⚠️ **同上** |
| `Steer` / `×` | 0 个 / 0 个 | 43 / 48，均 `clickable=true` | 43 / 48 | ⛔ 本轮无队列 |
| 节点总数 | 61（含 24 条自建消息） | — | — | ✅ 界面在正常渲染 |
| 崩溃 | `CLEAN` / 计数 0 | — | — | ✅ |

> ### ⇒ 对「不得回退」的判定
>
> **判据拆成两半，如实给出：**
>
> | 子判据 | 结论 | 依据 |
> |---|---|---|
> | **时间线盒 140px 未回退** | ✅ **通过** | `[0,162][1366,302]`，与 `U1D` / `U1E` **逐位相同** |
> | **队列格的 `readable=92` / `Steer 43` / `× 48`** | ⛔ **本轮未取到，不作判定** | 队列条在本轮未能渲染（§7.2 七步全记录） |
> | **崩溃（主判据）** | ✅ **通过** | 15 档全清 |
>
> **不能把 `readable = 140` 说成「回退到 140」** ——
> 本轮 `readable = 140` 的成因是**队列条根本没画**（`overlap = 0`），
> 与「队列条画了但 readable 变 140」是**两件不同的事**，前者不含任何夹取回归信息。
>
> **机制层面的一致性检查（可作旁证，非实锤）**：
> `CRASH_SHRINK_FIX.md` §4 的论证是「夹取**只可能**改变 `declared > maxHeight` 的帧，
> 而那正是崩溃的定义 ⇒ 在所有不会崩的帧上夹取是**恒等**」。
> 本轮 1366x768 的**全部**读数（盒高 140、盒位置 `162..302`）与修前逐位相同，
> **与「768 侧夹取恒等」这一预测一致**。
> 但这只是**与预测相符**，**不构成对队列格读数的复现证明**。

---

## 8. 收尾

| 项 | 结果 |
|---|---|
| `wm size reset` + `wm density reset` | ✅ 已执行（一度回到 `Physical 3840x2160` / `Physical 320`） |
| **复核回 1366x768 / 160** | ✅ **`Override size: 1366x768` / `Override density: 160`**（与入测原值**逐位相同**） |
| `mCurrentFocus`（收尾） | ⚠️ `Window{fb0c4ce u0 Pop-Up Window}` —— **非本 app 窗口** ⇒ 按纪律**停手**（见 §8.2） |
| `pidof`（收尾） | ✅ **`24697`**（存活，与基线同 pid，全程未重启） |
| logcat（收尾） | ✅ **CLEAN (0 hits)** |
| 崩溃计数（收尾） | ✅ **`crashes_since_then=0` / `last_crashed=none`** |
| `/sdcard` 残留 | ✅ **CLEAN**（本轮 `s0..s20.png` 共 21 个已 `rm -f`；复扫 `E2ECHK5556u1f` / `s[0-9]` / `q_` / `cxy` / `tmpxml` **全部无命中**） |
| `wm` 切换轮数 | **3 轮**（= 上限，未超） |
| 自建会话清理 | ⛔ **未完成**（见 §8.1） |
| 代码改动 | **无**（只读复验；未跑 gradle、未装机、未杀进程、未 `force-stop`） |
| 证据目录 | `research/v97_verify/5556_v98/p0/` |

> **注**：`/sdcard` 上仍有 `b2_130_s1.xml` / `c2_070_changes12s.*` / `v56_20_list_s1.png` 等文件，
> 经查为**更早轮次（非本轮）**留下的，**不在本轮授权清理范围**，故未动（如实列出）。

### 8.1 ⛔ 自建会话未删（承 `U1D` §9.2 / `U1E` §8.1 的未闭环项）

目标：① 本轮自建会话 `E2ECHK5556u1f+shrinkshrink+Q01`；
② 上一轮遗留、标题被改写的 `Request for a 100-part long-form guide`；
③ 更早遗留 `Extreme guide to assigning numbers to each paragraph for easier tracking`。

经过（如实）：

1. 先点 `Stop` **停掉回合**（`Stop` → `Send` 确认回合结束）—— 按纪律这是解除删除禁用的**必要前置**。
2. 开 `Open sessions` 抽屉，在自建会话行（运行期解析得 `[24,179][203,196]`）上
   用 `input motionevent DOWN/UP` 间隔 **1300ms** 长按 ⇒ **菜单成功弹出**，树中同时出现
   `Rename`（`clk=false`）与 **`Delete`（`clk=false`）**。
3. **但菜单在下一次 dump 时已消失**（`uiautomator dump` 期间菜单自动收起）：
   复扫时 `Delete` 节点**已不在树中** ⇒ **本轮未能在菜单存活窗口内点下 `Delete`**。
4. 改为「长按 → 立即在同一次流程内 dump + 按解析出的 `Delete` bounds 点击」，
   结果该次 dump 里**根本没出现 `Delete` 节点**（`DELETE NOT IN TREE`）。
5. 再重试时 `mCurrentFocus` 已变为 **`Window{fb0c4ce u0 Pop-Up Window}`（非本 app 窗口）**
   ⇒ **按纪律立即停手**，未在未知弹层上做任何点击。

> **⇒ 三个会话（含本轮自建）本轮均未删除。**
> **诚实归因**：U1E §8.1 记录的「长按菜单间歇不触发 / 抽屉卡死 / 删除按钮 `enabled=false`」在本轮**部分复现**
> （菜单能弹出但**存活窗口极短**，撑不过一次 `uiautomator dump`），并**新增一条**：
> **菜单弹出后焦点会漂到 `Pop-Up Window`，按纪律必须停手 ⇒ 与「必须在菜单存活期内点 Delete」形成死锁。**
> **建议 Lead**：这条不是测试方法问题，而是**可测性缺口** ——
> 需要一个不依赖「菜单存活窗口 > dump 耗时」的删除入口（如长按后立即盲点固定坐标、或 `adb shell` 侧的会话管理），
> 否则每一轮都会卡在同一处。

### 8.2 收尾停手点

`mCurrentFocus = Window{fb0c4ce u0 Pop-Up Window}` **不是本 app 的 `OcActivity`**。
按纪律「非本 app 窗口 → 停手、写盘、BLOCKED（对该步骤）」：
**已在该状态下停止一切交互**，仅保留只读读数（`pidof` / `logcat` / `dumpsys` / `wm`）用于收尾取证。
**⇒ §8.1 的会话删除在该状态下无法继续，标记为未闭环（非 BLOCKED 全轮，主判据已独立完成）。**

---

## 9. 未闭环

1. **⛔ 队列两格（≥11 条 / 2–5 条）本轮未取到**（§7.2 / §7.3）。
   队列条在本轮所有造队列路径下均未渲染（`'queued'` 命中 0），
   ⇒ `readable = 92` / `Steer h=43` / `× h=48` 三个读数**未复现、未判定**。
   **时间线盒 140px 已对齐，队列格留待下一轮。**
2. **⛔ 三个会话未删**（§8.1），含本轮自建会话。
   新增可测性缺口：**删除菜单存活窗口 < 一次 `uiautomator dump` 耗时，且焦点会漂到 `Pop-Up Window`。**
3. **⚠️ 1366x600 composer 宿主 `hostPaid ≠ 0` 且越 pane 底沿 4–5px**（§6 末）—— 承 `U1E` §5.3 / §9.3，
   `CRASH_SHRINK_FIX.md` §6 已定性为**不同根因、本轮不改**，本轮读数与该定性**吻合**（夹取在 600 稳态恒等）。
4. **⚠️ 采样粒度**：15 档读数取自 `wm size` 返回后 0.8s / 3s；`wm size` 是同步屏障，
   但**不宣称已证明过渡期每一帧都无异常**（缓解证据见 §5.4 末尾）。
5. **更小窗（`640` / `480`）未测**（承 `U1E` §9.8）。
6. **分屏 / 旋转 / IME 开关**等其他「先塌到亚 64」的路径未测（承 `U1E` §9.8 的同类未闭环）。
7. **崩溃是否曾被 v100 侥幸掩盖**仍未实锤（承 `U1E` §9.2 / `CRASH_SHRINK_FIX.md` §1.6，本轮未回测 v100）。

---

## 10. 回报

**状态：✅ PASS（主判据：往返缩窗 3 轮零崩溃）**｜构建核对 ✅ 逐位命中｜设备归属 ✅ 全程本 app

- **构建 md5 是否一致**：**✅ 一致** —— `d9e13ca3b238247ec0577927750e2a61` 与 `lastUpdateTime 2026-10-03 09:23:04`
  **均逐位命中**，未 BLOCKED。
- **往返 3 轮是否有崩溃**：**✅ 无崩溃** —— 3 轮 × 5 档 = **15 档**，
  `logcat` 的 `FATAL EXCEPTION` / `IllegalArgumentException` / `maxHeight`（并追加 `Force finishing activity` /
  `WIN DEATH` / `has died`）**六键全部零命中**；`dumpsys activity processes` 崩溃计数**恒 `0`**、**无 `last crashed` 行**；
  `pidof` **恒 `24697`**（与基线同 pid，全程未重启）。
- **1366x600 界面是否正常渲染 + readable 多少**：**✅ 正常渲染，不是空壳**（34 节点 / 21 可点击 /
  `EditText` 在 / 3 个 `ScrollView` 在 / 完整头与会话栏），**`readable = 51`**；
  四数 `时间线盒 51 / 队列条 不存在 / overlap 0 / readable 51`，与 `U1E` 逐位相同。
- **1366x768 两格是否回退**：**时间线盒格 ✅ 无回退**（`[0,162][1366,302]` = **140**，与 `U1D` / `U1E` 逐位相同）；
  **⛔ 队列两格未取到，不作判定** —— 本轮所有造队列路径下队列条均未渲染（`'queued'` 命中 **0**），
  `92 / Steer 43 / × 48` **未复现**。**不能把本轮 `readable = 140` 说成回退** —— 它的成因是队列条**根本没画**（`overlap = 0`）。
- **收尾**：`wm size reset` + `wm density reset` 已执行并**复核回 `1366x768` / `160`**；
  `/sdcard` 本轮残留已清 **CLEAN**；**自建会话未删**（删除菜单存活窗口短于一次 dump 耗时，
  且焦点漂到 `Pop-Up Window`，按纪律停手）。
- **报告路径**：`research/v97_verify/5556_v98/REPORT_P0_SHRINK.md`
  （证据：`research/v97_verify/5556_v98/p0/`）
