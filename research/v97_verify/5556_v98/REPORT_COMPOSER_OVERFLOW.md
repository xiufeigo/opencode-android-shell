# composer 越界修复 真机复验报告（v103） + P0 缩窗不崩回归

- 测试员：mavis（v103 真机复验）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 被测修复：`research/v97_fix/COMPOSER_OVERFLOW_FIX.md`（v103 composer 越 pane 底沿修复）
- 参照：`research/v97_verify/5556_v98/REPORT_U1E.md`（现场读数）、`REPORT_U1D.md`（队列两格基线）、`REPORT_P0_SHRINK.md`（P0 基线）
- 设备：**emulator-5556** / `Medium_Desktop` / 1366x768@160 / SDK 34
- **未改任何代码。** 未跑 gradle、未装机、未杀进程（仅用 `am start` 起本 app）。

> ## 状态：**❌ 主判据 FAIL（1366x600 越 pane 底沿 +5px）｜ 回归① ✅ PASS ｜ 回归② ⛔ 未取到（不判回退）｜ 收尾 ✅**
>
> | 判据 | 结论 |
> |---|---|
> | **构建核对** | ✅ **md5 `5159d6f4…` + `lastUpdateTime 2026-10-03 09:48:54` 逐位命中**，未 BLOCKED |
> | **主判据（composer 不越界）** | ❌ **FAIL** —— `1366x600`：`composerBottom 218 − paneBottom 213 = `**`+5`**`（>0，越界）；`1366x768` = `−6`（合格） |
> | **`ceiling=0 ⇒ 宿主高=0`** | ❌ **不成立** —— `ceiling=0`，实测宿主高仍 **48**、composer 节点计数 **3**（修档预期 0 / 0） |
> | **`readable`** | 768 = **92** ✅（与 `U1D`/`U1E` 逐位一致）；600 = **3** ❌（判据要求 51） |
> | **回归①（缩窗不崩）** | ✅ **PASS** —— 往返 2 轮 / 4 次 `wm` 切换，pid `27885` 恒定零丢失、`FATAL`/`IllegalArgumentException` 零命中、`dumpsys` 崩溃计数零增长 |
> | **回归②（队列两格）** | ⛔ **未取到** —— 队列条**在任何入口下都没渲染**，**按纪律不据此判回退**（详见 §6） |
> | **收尾** | ✅ `wm` 已 reset 后**复核回 `1366x768`/`160`**、`/sdcard` 无新增残留、**自建会话已删除** |
>
> **一句话**：**修复在真机上没有生效** —— `1366x600` 的读数与**修前**（`U1E` §5.3）**逐位一字不差**；
> 但**上一轮的 P0 缩窗崩溃确实仍然修好了**（回归① 零崩溃）。

---

## 1. 构建核对（先于一切）—— ✅ 逐位命中

| 项 | 派单要求 | 设备实测 | 判定 |
|---|---|---|---|
| `base.apk` md5 | `5159d6f4f05bc6a0155a21ba3ea7c17d` | **`5159d6f4f05bc6a0155a21ba3ea7c17d`** | ✅ **逐位一致** |
| `lastUpdateTime`（设备时） | `2026-10-03 09:48:54` | **`2026-10-03 09:48:54`** | ✅ **逐位一致** |
| APK 路径 | — | `/data/app/~~dR59lCxn3NvSVYgD5LxLHQ==/com.xiufeigo.opencodeshell-at_7OhhdAsvCE-uZt1WSJQ==/base.apk` | — |
| `versionCode` / `versionName` | — | `26` / `1.4.0` | — |
| 设备时 | — | `Sat Oct 3 09:50:15 GMT 2026` | 与 `lastUpdateTime` 自洽 |
| AVD / SDK | Medium_Desktop / 34 | **`Medium_Desktop`** / **34** | ✅ |

> **判定：✅ 构建核对通过，未 BLOCKED。** 本轮全部读数归属被测 v103 构建。

---

## 2. 设备归属核对

| 时点 | `mCurrentFocus` | `pidof` | 结论 |
|---|---|---|---|
| 入测（原始状态） | `org.chromium.home.pc/.HostActivity` | **EMPTY** | ⚠️ 承 `U1E` §2 的已知遗留（焦点在 launcher、进程不在） |
| `am start` 本 app 后（~14s 落焦） | `…/OcActivity` | `27885` | ✅ 本 app |
| 每次交互前 `Assert-Focus` 硬闸 | `…/OcActivity` | — | ✅ 见下 |
| 收尾 | `…/OcActivity` | — | ✅ |

> 起活只用 `am start` **本 app**（纪律允许）。**未在 launcher 上做任何点击**（未按 BACK、未按 ESC、未点任何 launcher 元素）。

---

## 3. 环境读数（入测原值）

| 项 | 读数 |
|---|---|
| `adb devices` | 7 台在线，**本轮只碰 5556**；全程未碰 127.0.0.1:5555 |
| `wm size` | `Physical 3840x2160` / **`Override 1366x768`** |
| `wm density` | `Physical 320` / **`Override 160`** |
| `show_ime_with_hard_keyboard` | **`1`** ✅（先核，IME 可用硬键盘唤起） |
| 证据目录 | `research/v97_verify/5556_v98/u1g/` |

### 3.1 读数口径（本轮明确写死）

- **pane** = a11y 树里**顶沿在 header 之下（y1>100）、满宽（w>1000）的第一个 View**
  （1366x768 实测 = `[0,162][1366,302]`，与 `U1E` §4.3 的 `pane 顶 y=162` 逐位一致）。
- **composer 宿主** = a11y 树里**没有独立节点**，故取 composer 子树并集
  `union(EditText, Add, Send)`；`composerBottom` = 该并集底沿。
- **`hostPaid`（= 修复文档里的「宿主高」）** = **`EditText` 的高度**（修档真值表 `宿主高 = 48` 即此值）。
- **`readable`** = `pane 高 − hostPaid`（与 `U1E`/`U1D` 的 768→92 / 600→51 口径一致）。
- **`mInputShown`** 取自 **`dumpsys input_method`**（`dumpsys window` 里**没有**该字段，本轮实测如此）；
  **每组高度读数前先断言并记录**。

---

## 4. 主判据：composer 不越界 —— ❌ **FAIL（1366x600 越 pane 底沿 5px）**

### 4.1 两档读数（每档连读 3 帧，逐字相同）

| 档 | 帧 | `mInputShown` | IME `mFrame` | **`composer` 宿主 bounds** | **`pane` bounds** | **`composerBottom − paneBottom`** | 判定 |
|---|---|---|---|---|---|---|---|
| **1366x768** | 1 | **true** | `[0,294][1366,768]` | 并集 `y=208..296`（`EditText [28,208][1338,256]` / `Add [10,248][52,296]` / `Send [1308,248][1356,296]`） | **`[0,162][1366,302]`** h=**140** | **296 − 302 = `−6`** | ✅ **≤0** |
| **1366x768** | 2 | **true** | `[0,294][1366,768]` | 同上 | 同上 | **`−6`** | ✅ |
| **1366x600** | 1 | **true** | `[0,213][1366,600]` | 并集 `y=169..218`（`EditText [24,169][405,217]` / `Add [399,170][441,218]` / `Send [1316,170][1364,218]`） | **`[0,162][1366,213]`** h=**51** | **218 − 213 = `+5`** | ❌ **>0 越界** |
| **1366x600** | 2 | **true** | `[0,213][1366,600]` | 同上 | 同上 | **`+5`** | ❌ |
| **1366x600** | 3 | **true** | `[0,213][1366,600]` | 同上 | 同上 | **`+5`** | ❌ |

> ### ❌ **判定：任一档 `composerBottom > paneBottom` ⇒ FAIL。本轮 `1366x600` 差值 = `+5` ⇒ 主判据 FAIL。**

**两个 bounds 原文（判据要求抄录）**：

```
1366x600 + IME:
  composer 宿主并集 : y=169..218   （EditText [24,169][405,217] h=48 / Add [399,170][441,218] h=48 / Send [1316,170][1364,218] h=48）
  pane              : [0,162][1366,213]  h=51
  composerBottom(218) - paneBottom(213) = +5     ← 越界 5px

1366x768 + IME:
  composer 宿主并集 : y=208..296   （EditText [28,208][1338,256] h=48 / Add [10,248][52,296] h=48 / Send [1308,248][1356,296] h=48）
  pane              : [0,162][1366,302]  h=140
  composerBottom(296) - paneBottom(302) = -6     ← 未越界
```

### 4.2 宿主高 与 ceiling 的关系 —— ❌ **`ceiling = 0` 时宿主高仍为 48，不是 0**

`1366x600` + IME 帧的逐步求值（`pane` / `floorPx` 均取本帧实测）：

| # | 量 | 算式 | 值 | 来源 |
|---|---|---|---|---|
| 1 | `windowPx` | `wm size` | **600** | 实测 |
| 2 | `pane` | `213 − 162` | **51** | **实测 `[0,162][1366,213]`** |
| 3 | `believedImeBottomPx` | `600 − 213`（`mFrame` 顶沿） | **387** | 实测 `mFrame=[0,213][1366,600]` |
| 4 | `declaredFloorPx` | `min(64, pane=51)`（`pane < 64` 分支） | **51** | 修档 §1 第 5 行 |
| 5 | `measuredFloorPx` | `pane(51) >= floor(51)` | **51** | 修档 §1 第 6 行 |
| 6 | `floorPx` | `maxOf(51,51)` | **51** | 修档 §1 第 7 行 |
| 7 | **`ceiling`** | `composerHostCeilingPx(pane=51, floorPx=51)` = `(51−51).coerceAtLeast(0)` | **0** | 修档 §1 第 8 行 |
| 8 | **宿主可高** | `composerHostHeightPx(panePx=51, ceilingPx=0)` = `0.coerceAtMost(51).coerceAtLeast(0)` | **0** | 修档 §1 第 9 行 |
| 9 | **宿主高（`hostPaid`）** | `min(composerWants, 宿主可高=0)` **应为 0** | **48** ❌ | **实测 `EditText [24,169][405,217] h=48`** |
| 10 | a11y 中 composer 节点数 | `ceiling=0 ⇒ 整条不画 ⇒ 计数 0` | **3** ❌ | **实测 `EditText/Add/Send` 各 1** |

> **⇒ 判据「`ceiling = 0` 时宿主高必须为 0」不成立：实测宿主高 48、composer 节点计数 3。**
> 修档 §3.3 的推论「`hostHeightPx == 0 ⇒ host == 0`，**上限为 0 时宿主高必为 0，已由构造保证**」
> **在真机上未兑现**。

### 4.3 `readable`

| 档 | `pane` 高 | `hostPaid` | `readable` = `pane − hostPaid` | 对照基线 | 判定 |
|---|---|---|---|---|---|
| **1366x768** | 140 | 48 | **92** | `U1E`/`U1D` 768 档 = **92** | ✅ **逐位一致** |
| **1366x600** | 51 | 48 | **3** | 派单要求 600 档 = **51** | ❌ **退化为 3** |

> `readable` 在 600 档应为 **51**（`ceiling=0` ⇒ `hostPaid=0` ⇒ `51 − 0 = 51`）。
> 实测 `hostPaid=48` ⇒ 读数 **3**，**未达判据**，与 §4.2 同一根因。
> 768 档 `readable=92` 与 `P0_SHRINK` 的「时间线盒 140px 无回退」一致。

### 4.4 与修前现场读数（`REPORT_U1E.md` §5.3）的逐位比对 —— **一字不差**

| 读数 | `U1E` §5.3（v101，**修前**） | 本轮（v103，**修后**） | 判定 |
|---|---|---|---|
| `EditText`（600+IME） | `y=169..217` h=**48** | `[24,169][405,217]` h=**48** | **逐位相同** |
| `Send`（600+IME） | `y=170..218` h=**48** | `[1316,170][1364,218]` h=**48** | **逐位相同** |
| `Add`（600+IME） | `y=170..218` h=**48** | `[399,170][441,218]` h=**48** | **逐位相同** |
| `pane` | `0,162-1366,213` = **51** | `[0,162][1366,213]` = **51** | **逐位相同** |
| 越 pane 底沿 | **4（ET）/ 5（按钮）** | **4（ET 217−213）/ 5（按钮 218−213）** | **逐位相同** |
| IME `mFrame` | `[0,213][1366,600]` | `[0,213][1366,600]` | **逐位相同** |

> ### ⇒ **真机行为与「修前」现场读数完全一致，未见任何可观测变化。**
>
> **这对 `COMPOSER_OVERFLOW_FIX.md` §2.3 那个「无法从源码坐实」的未闭环项给出了答案**：
> 修档 §2.3 写「600 稳态帧究竟走有界支还是无界支**本轮无法从源码坐实**」。
> **本轮真机读数证实：修复预期的「`ceiling=0 ⇒ 宿主被收成 0`」在该帧**没有发生**，
> 宿主仍是 48px。**无论真机走有界支还是无界支，修档 §3.1/§3.2 声称的统一收口
> （`composerHostHeightPx`）在该帧都未约束住绘制。**

---

## 5. 回归①：缩窗不崩 —— ✅ **PASS（往返 2 轮，零崩溃）**

### 5.1 四读数（每档查 `pidof` / `mCurrentFocus` / `logcat`）

| 轮次 | 档位 | `wm size` | `pidof` | `mCurrentFocus` | `logcat` 真实崩溃/异常 | 判定 |
|---|---|---|---|---|---|---|
| **#1** | 768→**600**（缩） | `Override 1366x600` | **`27885`** | `…/OcActivity` ✅ | **0** | ✅ 存活 |
| **#1** | 600→**768**（放） | `Override 1366x768` | **`27885`** | `…/OcActivity` ✅ | **0** | ✅ 存活 |
| **#2** | 768→**600**（缩） | `Override 1366x600` | **`27885`** | `…/OcActivity` ✅ | **0** | ✅ 存活 |
| **#2** | 600→**768**（放） | `Override 1366x768` | **`27885`** | **`27885`** | **0** | ✅ 存活 |

> **pid 全程恒为 `27885`，四档零变化、零重启、零丢失。** 焦点**四次全部**是本 app `OcActivity`，无一例外。
> 每档都配了 `wm size` + `wm density 160`（纪律）。

### 5.2 `logcat` 过滤结果 —— ⚠️ 派单的字面过滤器**非空**，但**全部是 IME 噪声**

派单指定的 `logcat -d | Select-String 'FATAL EXCEPTION|IllegalArgumentException|maxHeight'`：

| 时点 | 字面过滤器命中 | 其中 **本 app 崩溃/异常** | 其中 **IME 噪声** |
|---|---|---|---|
| baseline(600) | 11 | **0** | 11 |
| R1→768 | 12 | **0** | 12 |
| R1→600 | 13 | **0** | 13 |
| R1→768(back) | 14 | **0** | 14 |
| R2→600 | 15 | **0** | 15 |
| R2→768 | 16 | **0** | 16 |

**噪声归属已坐实 —— 属输入法进程，不是本 app**：

```
10-03 09:57:12.521  3629  3629 I SoftKeyboardView: SoftKeyboardView.setMaxHeight():731 Set max keyboard height:315.
10-03 09:57:19.016  3629  3629 I SoftKeyboardView: SoftKeyboardView.setMaxHeight():731 Set max keyboard height:483.
our pid = 27885            ← 与噪声 pid 3629 不同
```

- 噪声行是 **`I` 级、`SoftKeyboardView` 标签**，`pid 3629 ≠ 本 app 27885`。
- 它只是**命中了 `maxHeight` 这个子串**，**不含** `FATAL EXCEPTION`、**不含** `IllegalArgumentException`。
- 逐档 `FATAL EXCEPTION` / `IllegalArgumentException` 计数**恒为 0**。

**独立第二来源 —— `dumpsys activity processes`**：

```
（grep opencodeshell | grep crashed）→ 无输出
```

> `U1E` §6.2 里 `maxHeight(12) must be >= minHeight(64)` 那条 P0 签名**一条都没有复现**；
> `crashes since then` 计数**不增**（该 app 本轮从未崩溃）。
>
> ### ⇒ **回归① PASS：往返 2 轮、4 次 `wm` 切换，进程零丢失、`FATAL`/`IllegalArgumentException` 零命中、`dumpsys` 崩溃计数零增长。**
> **`P0` 缩窗崩溃（`CRASH_SHRINK_FIX.md` / v102）在真机上仍然保持修复态，无回退。**

---

## 6. 回归②：队列两格 —— ⛔ **未取到（队列条在任何入口下都没渲染）**

> ### **判定：⛔ 未闭环，**按派单纪律不据此判回退**。**
> 派单原文：「若确实拿不到（长回合/流式构造失败），写清你试了什么，**不要**据此判回退。」
> **队列条从未渲染 ⇒ `readable 92` / `Steer 43` / `× 48` 三格读数一个都取不到 ⇒ 不构成回退证据。**

### 6.1 先证明队列条**真的没**渲染（派单要求的举证顺序）

| # | 证据 | 读数 | 判读 |
|---|---|---|---|
| ① | `grep 'content-desc="(Steer|Remove\|Clear\|Queue)[^"]*"'` 全树 | **No matches found** | `Steer` / `×` / `Clear` / `Queue` **一个都不在 a11y 树里** |
| ② | 队列条应占的**满宽区域**（`U1E` 基线 `198..246`，h=48） | 该区域只有 `Open terminal`(`192..240`)、`EditText`(`208..256`) 等常规节点，**无满宽队列条** | 队列条**不存在** |
| ③ | `pane` 高度 | 仍为 **`[0,162][1366,302]` h=140** | 与「无队列」态一致（`U1E` 有队列时 768 档亦为 140，但会多出 `198..246` 的满宽队列条）——**本轮该条缺失** |

### 6.2 我试了什么（逐条，含失败）

| # | 尝试 | 结果 |
|---|---|---|
| ① | **造长回合**：在自建会话发一条长 prompt（`…write a very detailed 100-part long-form guide…`） | ✅ **回合成功起跑**：tab 标签出现 `Working`，发送键变 **`Stop`**（`[1308,248][1356,296]`）⇒ **回合确为运行中** |
| ② | **连打 12 条** follow-up（`queued-message-01` … `queued-msg-12`），每条「点 composer → `input text` → 点 `Send`」 | ✅ 12 条**全部送达**（`MessageNavRail` 右侧导轨逐条列出，见 §6.3）——**但没有一条进入队列条** |
| ②b | 发现「发送键在运行中会变成 `Stop`、只有 composer **有文字**时 `Send` 才回来」 | 已据此调整：每条先填字再点 `Send`(`1332,272`)，12 条全部成功提交 |
| ③ | **再补 2 条突发**（`burst-a` / `burst-b`）并**立即 dump** | 同样进时间线，队列条仍不出现 |
| ④ | **滚 composer chip 行找 `Queue` chip**（`input swipe 1200 272 → 300 272`） | chip 行**只有 `Model`（`Space Bunny Free`）一个 chip**，滚后仍只有它 —— **`Queue` chip 不存在** |
| ⑤ | **开 `More options` 找队列入口** | 弹层 10 项：`Rename` / `Export session` / `Import session` / `Delete` / `Undo` / `Fork session` / `Move to…` / `New worktree` / `Command palette` / `Toggle debug bar` —— **无队列项**（弹层归属已核实为**本 app**：`package=com.xiufeigo.opencodeshell`, `mOwnerUid=10189`, `Session{… 27885}`） |
| ⑥ | 观察回合是否结束（回合不结束才有「运行中排队」语义） | 提交全程 `Stop` **始终在**、`Working` **始终在** ⇒ **回合确实一直在跑**，不是回合已死导致改走立即发送 |

### 6.3 决定性反证：15 条消息**全部落进时间线**，不是队列

`MessageNavRail`（右侧导轨）逐条列出的 15 项：

```
E2ECHK5556u1g queue cell probe: write a very det… | queued-message-01 | queued-msg-02 | queued-msg-03 |
queued-msg-04 | queued-msg-05 | queued-msg-06 | queued-msg-07 | queued-msg-08 | queued-msg-09 |
queued-msg-10 | queued-msg-11 | queued-msg-12 | burst-a | burst-b
```

且 `burst-a` / `queued-msg-12` 等**在时间线盒内有实体节点**
（如 `TextView [1195,166][1300,186]`、`View [162,194][1183,1312]`）⇒ **消息被即时并入会话，而非排进队列**。

### 6.4 与 `U1D`/`U1E` 基线的差距（如实记录，不作判罚）

| 项 | `U1E`（v101） | 本轮（v103） |
|---|---|---|
| 造队列的手法 | 12 条 follow-up，**成功进队列** | 15 条 follow-up，**全部进时间线** |
| 队列计数标签 | `12 queued @ 24,192-78,198` | **无** |
| 队列条 bounds | `198..246`（满宽，h=48） | **不存在** |
| 队列视口 | `18,206-1348,238` h=32 | **不存在** |
| `Steer` / `×` | `1263,199-1302,242` h=43 / `1302,198-1350,246` h=48 | **不存在（0 个）** |
| `readable` | 92 | 取不到（无队列格） |

> **⇒ 「v103 把 follow-up 从队列改成即时发送」是一个与「队列条消失」同时出现的可疑变化，
> 但本轮只测了 v103、且队列条本身取不到，**无法排除「我的构造手法与上轮不同」这一可能**
> （上轮的确切手势/时序未逐字复刻）。**
> **按派单纪律：此处只登记为未闭环，交 Lead 裁定；不判回退。**

---

## 7. 收尾 —— ✅ 全部完成

| 项 | 结果 |
|---|---|
| `wm size reset` + `wm density reset` | ✅ **已执行**（派单要求）。执行后读数：`Physical size: 3840x2160` / `Physical density: 320`（Override 消失） |
| **复核回 `1366x768`/`160`** | ✅ **已复核**。按入测原值（Override `1366x768` / Override `160`）恢复后读数：<br>`Physical size: 3840x2160` / **`Override size: 1366x768`**；`Physical density: 320` / **`Override density: 160`** |
| `/sdcard` 残留 | ✅ **无新增**。`ls /sdcard/ \| grep u1g_` → **无输出**；`grep -i u1g` → **无输出**。每次 pull 后即 `rm -f` 远端临时文件（dump/截图都不留在设备上） |
| `mCurrentFocus`（收尾） | ✅ `com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.OcActivity` |
| `pidof`（收尾） | ✅ **`27885`**（与入测同一个 pid，全程未重启） |
| 收尾 `logcat` 复扫 | ✅ `raw=18` 行命中、**真实崩溃/异常 `= 0`**（18 行全部是 `SoftKeyboardView` IME 噪声） |
| **`wm` 切换轮数** | **回归① 2 轮（4 次切换）** + 主判据 1 次缩窗 = **共 5 次**（未超纪律上限） |
| **自建会话清理** | ✅ **已删除成功**（见 §7.1） |
| 代码改动 | **无**（只读复验） |
| 证据目录 | `research/v97_verify/5556_v98/u1g/`（**25 个 dump XML + 2 张截图** + `helper.ps1`） |

### 7.1 ✅ 自建会话已删除 —— **顺带解掉了历史遗留的死锁**

`U1E` §8.1 与 `U1D` §9.2 记的是「长按菜单间歇不触发 / 确认弹窗 `Delete` 按钮 `enabled=false`」的**死锁**。
派单说「删除菜单存活期短于一次 dump 的死锁已登记，不要为删它耗预算；删不掉就写清」—— **本轮删掉了**：

1. 会话名：`Queue cell probe for E2ECHK5556u1g: detailed 100-word write-up`（**带本轮前缀** `E2ECHK5556u1g` ✅）。
2. **先点 `Stop` 停掉回合**（`Stop` → `Send` 按钮回归，确认回合结束）。
3. 抽屉里长按该行（`input swipe 136 187 136 187 1200`，1200ms）→ 菜单出现（`Rename` / `Delete`）。
4. 点 `Delete` → 确认弹窗「Delete session?」，**两个按钮 `enabled="true"`**（不再是 `U1E` 记的 `enabled=false`）。
5. 点 `Delete`(`1286,453`) → **复检：全树已无任何含 `u1g` 的节点** ⇒ 删除成功。

> **⇒ 「停掉回合不解除删除禁用」这条历史结论本轮未复现** —— 停掉回合后删除正常可用。
> 建议 Lead 把 `U1E` §8.1 第 5 条从「未闭环」降级（至少降为「本轮可删，长按手势需 ≥1200ms」）。
> ⚠️ 我**未触碰**任何非本轮会话（`E2ECHK5556u1f…`、`Explore …`、`Build …` 等一律未动）。

### 7.2 ⚠️ 纪律自陈（一处偏离，如实记录）

- 在 `New session` 屏设置标题时，我**无条件按了一次 `ESC`(111)**，当时**并未确认处于弹层内**。
  事后复检：该屏布局、标题、项目路径**均未受影响**（标题仍为 `New session`，`Create session` 正常）。
- **此后全程未再按 `ESC`**；两个弹层（`More options` 弹层、删除确认弹窗）均以**点遮罩 / 点确认按钮**关闭。
- 其余纪律全部遵守：未跑 gradle、未装机、未杀进程（仅 `am start` 起本 app）、只碰 5556、
  未碰 127.0.0.1:5555、未按 BACK(4)、坐标运行期从 dump 解析、dump 双引号/单引号都匹配、
  优先 `--compressed`、每组高度读数前断言并记录 `mInputShown`、边做边写盘。

---

## 8. 未闭环

1. **🔴 P0（本轮最重）：修复在真机 600 档没有生效。** `composerBottom 218 > paneBottom 213`（`+5`），
   `ceiling=0` 而宿主高仍 `48`，composer 节点计数 `3`。
   **与 `U1E` §5.3 的修前读数逐位一字不差** ⇒ 修档 §3.1/§3.2 声称的「`composerHostHeightPx` 唯一收口」
   在真机上**未约束住绘制**。**建议**：先确认 `SessionScreen.kt` 的 `SessionPaneArrangement` measure
   是否真的走了 `composerHostHeightPx`（本轮**未读源码**，只读复验，未做任何代码改动）。
2. **🔴 修档 §2.3 的未闭环项现在有了答案，但答案是坏消息。** 该节写「600 稳态帧究竟走有界支还是无界支
   本轮无法从源码坐实」，并称「已把**两支都钉死**，让『真机走哪一支』不再影响结论」。
   **本轮真机证实：两支钉死并没有让结论成立** —— 无论走哪支，`ceiling=0 ⇒ 宿主=0` 都没发生。
   ⇒ **修档 §3.3 的构造性证明（`top + host ≡ pane`）与真机现象直接冲突**：
   证明的前提是「宿主尊重 `maxHeight`（Compose 契约）」，**真机上宿主画了 48px 而上限是 0
   ⇒ 该前提在该帧不成立**。**这是最值得 Lead 先看的一条：要么 Compose 契约被绕过，
   要么 a11y 树的 48px 来自不受该 `maxHeight` 约束的另一层（例如 `fillMaxSize()` 的 sibling）。**
3. **🟠 `readable` 600 档退化为 3**（判据要求 51），与 §8.1 同一根因。
4. **🟠 回归② 队列条完全取不到**（§6）。全树无 `Steer`/`×`/`Queue`/计数标签，chip 行只有 `Model`，
   `More options` 无队列项，15 条 follow-up 全部进时间线而非队列。
   **本轮不判回退**，但「v103 把 follow-up 改成即时发送」与「队列条消失」同时出现，**建议 Lead 单独查一次**。
5. **🟡 越界量 4 vs 5 的亚像素差仍未定位**（承修档未闭环 3）：本轮复现同样形状
   （`EditText` 217−213=**4**、`Add`/`Send` 218−213=**5**），**一字不差**。
6. **🟡 `U1E` §9.1 的 P0 缩窗崩溃**：本轮回归① **零复现**、pid 恒定 ⇒ **仍然保持修复态**，但
   **本轮只测了「768↔600」这一个高度对**；`U1E` §9.8 指出的 `640`/`480` 更极端窗
   与**放窗方向**的极端组合**仍未测**。
7. **🟡 本轮只测 v103 一个 build**：无法区分「修复没写进去」与「修复写了但无效」。未回测 v100/v101/v102。
8. **🟢 顺带闭环**：`U1E` §8.1 / `U1D` §9.2 的「会话删不掉」死锁**本轮已解**（§7.1），
   建议把那两条从未闭环降级。

---

## 9. 回报

**状态：❌ 主判据 FAIL（600 档越界 +5px、`ceiling=0` 宿主仍 48）｜ 回归① ✅ PASS ｜ 回归② ⛔ 未取到（不判回退）｜ 收尾 ✅（含会话已删）**

- **构建 md5 是否一致**：✅ **一致**，逐位命中。`base.apk` md5 **`5159d6f4f05bc6a0155a21ba3ea7c17d`**，
  `lastUpdateTime` **`2026-10-03 09:48:54`** ⇒ **未 BLOCKED**。
- **两档 `composerBottom − paneBottom`**：
  - **1366x768 = `−6`**（`296 − 302`）→ **合格**（≤0）
  - **1366x600 = `+5`**（`218 − 213`）→ **❌ 越界**，**主判据 FAIL**
- **ceiling=0 时宿主高是否为 0**：❌ **不是**。`600` 档 `pane=51`、`floorPx=51` ⇒ `ceiling=0`、
  「宿主可高」=`0`，但实测 `hostPaid=48`、`EditText [24,169][405,217]`、a11y composer 节点计数 **3**。
- **往返 2 轮是否仍不崩**：✅ **仍不崩**。768→600→768 ×2 轮共 4 次 `wm` 切换，
  **pid 恒为 `27885` 零丢失**、焦点 4/4 是本 app、`FATAL EXCEPTION`/`IllegalArgumentException` **零命中**、
  `dumpsys activity processes` 崩溃计数**零增长**。（字面 `maxHeight` 过滤器有 16 行命中，
  **全部是输入法进程 pid 3629 的 `SoftKeyboardView` `I` 级噪声**，非本 app。）
- **队列两格读数**：⛔ **未取到**。全树 `Steer`/`×`/`Queue`/计数标签**全部不存在**（grep 零命中），
  chip 行只有 `Model`，`More options` 无队列项；15 条 follow-up 在 `Stop` 持续在（回合运行中）的情况下
  **全部落进时间线**而非队列。**按纪律不据此判回退。**
- **报告路径**：`research/v97_verify/5556_v98/REPORT_COMPOSER_OVERFLOW.md`
  （证据：`research/v97_verify/5556_v98/u1g/`，25 XML + 2 PNG）
