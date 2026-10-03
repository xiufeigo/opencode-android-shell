# REPORT_1PX_RESIDUAL — v137：真机复测两处 1px（emulator-5554，600 / 768 档）

状态：**闭环于「不落声明」分支** —— 判据 ① 真机实测 **仍 = +2**（非 v136 预测的 `+1`），
并集高真机实测 **仍 = 49**（未回 48）⇒ 按 Lead 裁定 **第三条** 走：**不落声明**，
如实记「**另有一处 1px 未归因**」，并把 **v136 的机制归因标为待重做**。· 2026-10-04

> 纪律：未跑 gradle、未 `assembleDebug`、未装机、未杀进程（仅本 app 的 `am force-stop` 于启动脚本内）；
> 未按 BACK(4)、未在弹层内发 ESC(111)；只动自建内容（前缀 `E2ECHK5554u1v`）；
> 坐标运行期从 dump 解析；每帧读高度前断言 `mInputShown`；`logcat` 一律带 `-t` 且按 app-pid 过滤。

---

## 0. 构建 / 后端 / 归属核对

| 项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| APK md5 | `9142f0eb68dee9d607aa0ab8bfadc0a2` | `9142f0eb68dee9d607aa0ab8bfadc0a2` | ✅ **逐位一致** |
| `lastUpdateTime` | `2026-10-03 22:54:49` | `2026-10-03 22:54:49` | ✅ |
| `versionName` / `versionCode` | — | `1.4.0` / `26` | — |
| 设备 | `emulator-5554`（Medium_Desktop） | `emulator-5554` · x86_64 · SDK 34 | ✅ |
| 显示 Override | `1366x768` / `160` | `Override size: 1366x768` · `Override density: 160`（物理 3840x2160 / 320） | ✅ |
| 后端 v2 | 4095 在线 | `GET http://127.0.0.1:4095` → **HTTP 200**（OpenCode 页面） | ✅ |
| 反向端口 | `reverse tcp:4095` | `adb reverse --list` → `tcp:4095`（另有 `tcp:2222`） | ✅ |
| 归属 | v136 并集对齐修复应在机构建内 | 源码在位（`Composer.kt:2210` `Alignment.Top`、`SessionPaneLayout.kt:1370/1383` 两个生产函数、`Composer.kt:2362-2389` 统一摆位） | ⚠️ **源码在位，但真机读数未变**（见 §2） |

`adb` 不在 PATH，走 `C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe`（`local.properties` 的 `sdk.dir` 指向同一 SDK）。

**本轮复测形态构造**（`v137_baron.ps1`）：清空 composer → 输入
`E2ECHK5554u1v count from 1 to 30000, one number per line` → `Send` → 回车进入运行态 →
composer 留 `E2ECHK5554u1v rowV first` → chip 行出现 `Queue`（实测 `[1279,208][1316,223]`）→ 点它。
到位判据：**`Queued E2ECHK5554u1v rowV first` + `Steer` 在屏 + 回合 `Working`（`Stop` 在位）**，
截图 `artifacts/v137_BARON.png` 逐项可见。

---

## 1. A 复测表

### 1.1 600 档「队列条在屏」形态（核心）

原始帧：`research/v97_verify/5554_md/artifacts/v137_BARON_M.xml`（截图同名 `.png`）
`wm density 160` ⇒ **1dp == 1px**。

| 量 | bounds | top | h | bottom |
|---|---|---|---|---|
| **宿主盒（pane）** | `[0,162][1366,261]` | 162 | **99** | **261** |
| 队列条行 | （`Steer` `[1263,177][1311,225]`，h=48，clk=true） | 177 | 32 | 225 |
| **EditText** | `[24,214][405,262]` | **214** | **48** | **262** |
| `Add` 按钮 | `[399,215][441,263]` | **215** | **48** | **263** |
| `Model` chip | `[441,215][559,263]` | 215 | 48 | 263 |
| `Stop` 按钮 | `[1316,215][1364,263]` | 215 | 48 | 263 |

| 判据 | 值 |
|---|---|
| **band** | **48** |
| **并集（排除 `Open terminal`）** | `[24,214][1364,263]` |
| **unionPx** | **49** ← **未回 48**（v136 声称已修并已钉） |
| **paneBottom** | **261** |
| **① = unionBottom − paneBottom** | **+2** ← **仍 +2**（v136 模型预测 `+1`） |
| 顶沿对齐 | `EditText.top 214` vs 按钮组 `.top 215` ⇒ **topDelta = −1，仍差 1px** |
| ② `unionPx ≤ band` | **False**（49 > 48） |
| **mInputShown** | **True**（读前已断言） |
| **readable** | **51**（`paneH 99 < 150` ⇒ `paneH − 48`） |
| timeline box | 51 |

**逐位对照 v136 的模型预测**：

```
v136 预测：EditText.top == 按钮组.top（顶沿对齐已修）  ⇒  并集 48  ⇒  ① = +1
v137 真机：EditText.top 214 ≠ 按钮组.top 215          ⇒  并集 49  ⇒  ① = +2
⇒ v136 §7.3 成立：「① 仍是 +2 ⇒ 归因需重做」的分支命中。
```

**同时命中 v136 §7.3 的两个触发条件**：
1. 顶沿**仍差 1px**（`214` vs `215`），并集**仍 49**；
2. 而 `theOneRowFormAlignsControlsToTheTopEdgeAndTheStackedFormDoesNot`
   **仍会绿**（它咬的是源码文本，`Alignment.Top` 分支确实在 `Composer.kt:2210`）。

**⇒ 源码 pin 绿、真机读数红 ⇒ 二者必然有一个前提不成立。** 这是本轮最重要的发现，
也是**不能**把 v136 的机制归因当成已验证的原因。

### 1.2 不回退四项（全部 PASS）

| 形态 | 期望 | 实测 | 结论 |
|---|---|---|---|
| 768 档 **时间线盒** | **140** | `paneH 188 − 48 = ` **140** | ✅ |
| 768 档 **readable** | **92** | `188 − 96 = ` **92** | ✅ |
| 768 档 条关 **①** | **−10** | `unionBottom 340 − paneBottom 350 = ` **−10** | ✅ |
| 600 档 条关 **①** | **−12** | `unionBottom 249 − paneBottom 261 = ` **−12** | ✅ |
| 600 档 条关 **readable** | **51** | `paneH 99 − 48 = ` **51** | ✅ |
| 缩窗往返 **2 轮** | 不崩 / 读数稳定 | RT1_600 / RT1_768 / RT2_600 / RT2_768 **四腿逐位同上表** | ✅ |
| `logcat`（app-pid `25247`，带 `-t`） | `FATAL｜IllegalArgumentException｜maxHeight` 为空 | 复测两轮共 2 次采样，**均为空**（`app lines` 409 / 3） | ✅ |

- 768 档条关帧：`pane [0,162][1366,350] h=188`；600 档条关帧：`pane [0,162][1366,261] h=99`。
- 600 档条关 `unionPx = 48`（并集**无** 49）；768 档条关 `unionPx = 96`。
- 缩窗往返后**显式重设**回 `wm size 1366x768` / `wm density 160` 并复查
  （`Override size: 1366x768` · `Override density: 160`）。
- 触摸靶复核：600 条在屏帧 `Add` / `Model` / `Stop` 与队列条 `Steer` 实测 **h=48 且 `clickable=true`**，
  **48dp 靶一像素未动**。

---

## 2. 真机帧与 v136 归档帧的**逐位重合**（关键异常）

把 v136 §1.0 引用的归档帧（`v134/k600_MEASURE_BARON.xml`）与本轮真机帧并排：

| 节点 | v135/v136 归档帧 | v137 真机帧 | 差 |
|---|---|---|---|
| 宿主盒 | `[0,162][1366,261]` h=99 | `[0,162][1366,261]` h=99 | **0** |
| `EditText` | `[24,214][405,262]` h=48 | `[24,214][405,262]` h=48 | **0** |
| `Add` | `[399,215][441,263]` h=48 | `[399,215][441,263]` h=48 | **0** |
| `Model` chip | `[441,215][559,263]` h=48 | `[441,215][559,263]` h=48 | **0** |
| `Stop` | `[1316,215][1364,263]` h=48 | `[1316,215][1364,263]` h=48 | **0** |
| `unionPx` | 49 | 49 | **0** |
| `unionBottom` | 263 | 263 | **0** |
| `paneBottom` | 261 | 261 | **0** |
| **①** | **+2** | **+2** | **0** |

**九个量逐位相同，零差异。** 而所装构建的 md5 是 Lead 声明「含 v136 并集对齐修复」的那一版，
源码里 `Alignment.Top` 与两个生产函数也都在位。

**⇒ 两种可能，本轮无法区分（如实并列，不选边）**：

1. **装机归属问题** —— 这一帧走的是**未含修复**的代码路径或旧构件（md5 相同但该形态的
   实际渲染点不在被改的那处 `Row` 上，即 `minimal` 在本形态为 `false`，走的是
   `Alignment.CenterVertically` 那一支：源码在位 ≠ 本形态生效）。
2. **真源不在 v136 指的那一行** —— 1px 来自 `Composer.kt:2210` 之外的另一处摆位/测量
   （例如 `toolControls` 内部 `HorizontalScrollView` 自身 `CenterVertically`，见帧内
   `[441,228][1316,249] h=21`），v136 归因行**指错**。

要区分，需要在真机上对 `minimal` 分支取一次实际生效值（或用修复构建重装后复测同一形态）。
**本轮授权内无「装机」**，故**留账**，不臆断。

---

## 3. B 结论与落地

### 3.1 依据 Lead 裁定选分支

Lead 裁定的前提是「那 1px 是两条被 pin 住的产品不变量互相挤压」，
给出三选一。**实测落在第三条**：

```
① = unionBottom − paneBottom = 263 − 261 = +2
```

> 规则原文：**「若 ① 仍 = +2 ⇒ 不落声明，如实写"另有一处 1px 未归因"，
> 并把 v136 的机制归因标为待重做。」**

**⇒ 执行结果：不落声明（无 `POSITIONAL_1PX_DECLARED.md`、无新 pin、无任何测试改动）。**

理由不是保守，是**证据不支持**：`① = +1` 的归因依赖「并集对齐那 1px 已被修掉」，
而真机逐位证明**它没被修掉**（`unionPx` 仍 49）。在这种前提下写
「位置性残差 = 1px + 算式 `push 13 > padding 12`」，等于把一个**尚未被拆开的 2px**
硬写成 1px 并宣告接受 —— 那不是声明残差，是**用一个错误的算式给缺陷盖章**。
**不许用「设计行为」四字代替算式**这条纪律在这里恰好指向同一件事：算式本身不成立时，
连算式都不该有。

### 3.2 落地清单

| 项 | 动作 |
|---|---|
| 显式声明 `条在屏 ① = 1px 位置性残差` | ❌ **不落**（① 实测 +2，声明的前提不成立） |
| `research/v97_fix/POSITIONAL_1PX_DECLARED.md` | ❌ **不写**（无测试改动 ⇒ 按任务书无需该文件） |
| 新增 pin | ❌ **不新增**（无可声明的量可钉） |
| 放宽/删除既有断言 | ❌ **零改动**（v103–v136 全部 pin 逐字未动，见 §3.4） |
| 48dp 触摸靶 | ❌ **未动**（真机复核 `h=48` 全 clickable） |
| 用例数 | ➖ **未减少**（本轮 0 增 0 删） |
| **v136 机制归因** | ⚠️ **标为「待重做」** —— 见 §3.3 |

### 3.3 v136 机制归因的处置：**待重做**

v136 `POSITIONAL_1PX_FIX.md` §1.1 把并集对齐那 1px 归因为
`roundToInt((48 − 47)/2f) = 1` 的半像素进位，修法是 `Alignment.Top`（`Composer.kt:2210`）
＋ `composerBandChildTopPx` 统一摆位（`Composer.kt:2362-2389`）。

**真机证伪该归因已被应用**（`unionPx` 仍 49、`topDelta` 仍 −1，而源码三处全在位）。
故 v136 §7.3 的条件成立，**该机制归因自本轮起标记为「待重做」**：

- ❌ **不再**把「① = +2 = 1（位置性越界）+ 1（并集对齐）」当作**已验证**的等式；
- ✅ **仍成立**的部分：位置性越界那 1px 的算式本身（`SessionScreen.kt:1827` 的
  `bottom = 12.dp` vs 队列条下推 `push 13`，`13 − 12 = 1`）在**归档帧与本轮真机帧上
  逐位一致**（`249 = 261 − 12`；`214 = 201 + 13`），可继续引用；
- ⚠️ **待重做**的部分：并集对齐那 1px 的**机制与修法**。

v136 §7.3 自己写的判据，本轮原样兑现：*「若真机复验发现 `①` 仍是 `+2`，则说明这 1px
另有来源，归因需重做。」* —— 归档帧 `+2` 那条回归触发器
（`theArchivedFrameStillReadsPlusTwoSoTheNextDeviceRunIsTriggered`）**本轮被真机再次点亮**，
它仍然是有效的触发器，**未放宽**。

### 3.4 硬约束合规

- **未放宽/删除任何既有断言**：本轮**未触碰任何测试文件**（`git status` 仅新增
  `research/v97_verify/5554_md/v137_*.ps1` 三个驱动脚本）。v103–v136 共 43 条逐字照旧。
- **48dp 触摸靶不动**：无生产代码改动；真机实测 `Add` / `Model` / `Stop` / `Steer` 均 `h=48`、`clickable=true`。
- **用例数不减少**：0 增 0 减。
- **未跑 gradle / 未装机 / 未杀进程**（仅本 app `am force-stop`，在 `connect3.ps1` 内）。
- **行尾**：全程只用编辑/写入工具，未用 `git checkout` / `git restore`。

---

## 4. 收尾

- 显示已**显式重设**回 `wm size 1366x768` / `wm density 160`，复查
  `Override size: 1366x768` · `Override density: 160`。
- app 仍在前台连接态（`reverse tcp:4095` 在位），设备可交还。
- 产物（全部在 `research/v97_verify/5554_md/`）：
  `v137_baron.ps1` / `v137_regress.ps1` / `v137_regress2.ps1`（驱动脚本）、
  `artifacts/v137_BARON_M.xml` + `.png`（600 条在屏核心帧）、
  `artifacts/v137_BARON.png`（形态到位截图）、
  `artifacts/A2_600_BAROFF.*` / `A2_768_BAROFF.*` / `A2_rt*_*.*`（不回退各帧与截图）、
  `artifacts/A2_logcat_apppid.log`（app-pid 过滤日志）。
- 未新增用户可见字符串；未动 `_upstream/`、`build.gradle.kts`、`ChatViewModel.kt`、
  `ui/servers/**`、`ui/home/**`、`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`。

---

## 5. 未闭环

1. **并集对齐那 1px 的真实来源** —— v136 的 `Alignment.Top` 归因被真机证伪「未生效」，
   但**真源未定位**。两种候选（装机/形态归属 vs 归因行指错）本轮无法区分（§2）。
   **需要**：`minimal` 在本形态的实际生效值，或用修复构建重装后复测同一形态。
2. **判据 ① 在条在屏形态下仍不成立**（+2）—— 归因待重做前，**不具备**落声明的条件。
3. **v136 的两个生产函数 `composerBandChildTopPx` / `composerBandUnionBottomPx`
   在真机上是否被调用过，未证实**（源码在位 ≠ 本形态走过该分支）。
4. **48dp 触摸靶与两条产品不变量的挤压关系**（Lead 裁定所依据的关系）**本轮未复核** ——
   裁定的前提是「两条不变量互相挤压」，而该前提依赖已被证伪的 2px 拆分；裁定**继续有效**，
   但**适用对象**要等归因重做后才能确定。
5. 缩窗往返已过 2 轮（`600↔768`），**未**覆盖 768 以外的中间尺寸与 `density` 变化。
