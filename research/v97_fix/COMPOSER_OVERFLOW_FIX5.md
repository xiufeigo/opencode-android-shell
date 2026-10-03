# COMPOSER_OVERFLOW_FIX5 —— band 与并集已等值（48=48），剩下的是**位置**问题

- 修复工程师：mavis（v107b）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 权威口径：`research/v97_verify/5556_v98/REPORT_COMPOSER_OVERFLOW4.md`
  - ① `unionBottom ≤ hostBottom`，`hostBottom` = **`imeTop`**（pane 底沿若报则用 `imeTop`）
  - ② `unionPx ≤ band`（**② 在 600 档已修好 = 0，本轮必须不回退**）
  - `union` **排除 `Open terminal`**；旧判据 `ceiling=0`、"根盒 600" 作废
- 纪律：只跑 `:app:testDebugUnitTest --tests "<类名>"`；未装机、未跑真机

> ## 状态：**🟡 根因已证，① 未修好（零生产代码改动，如实记账）**
> ## **① 可精确分解成两项**：`① = (bandTop − hostBoxTop) + (unionPx − hostPx)`
> ## **高度项在两档都恒为 0**（v106 已关）⇒ **① 现在 100% 是"位置"项**（已写成 pin）
> ## **两档的"位置"责任点不同**：600 = composer 子树内 5px 位移；768 = pane 底 302 比 imeTop 294 高 8px
> ## **收容点算出来是 0 越界**（`SessionScreen.kt:3339` 推出 `unionBottom = 213`），真机却 218
> ⇒ **v107 死前说的"the single containment point"对 600 档不成立**；本轮把它证伪了
> ## 768 档在「pane 必须 140 / readable 必须 92」两个硬 pin 下**数学上无解**，交 Lead 裁定（§5 #2）

---

## 0. 先落盘（本文件已在改代码之前写定）

本节是**第一手证据**，任何一步中断都不丢。

---

## 1. §1 根因：**+2 与 +5 来自两个不同的责任点**（不是同一个）

### 1.0 先排除一个共同误解：两档的**子节点高与 hostPx 都是 48**

`REPORT_COMPOSER_OVERFLOW4.md` §2.3：两档 `子节点高 = 48`、`hostPx = 48`。
⇒ **v106 的方案 A（band 44→48）只可能动 ②，不可能动 ①** —— 这一点已被真机逐位证实
（600 档 ② +4→0，① 纹丝不动）。**"高"这一维已经打平，剩下 100% 是"位置"。** 任务书判断正确。

### 1.1 收容点（v107 死前说的"the single containment point"）

**找到了，在 `ui/session/SessionScreen.kt`，不在 `Composer.kt`：**

| 位置 | 原文 | 作用 |
|---|---|---|
| `SessionScreen.kt:3337-3340` | `layout(constraints.maxWidth, constraints.constrainHeight(pane)) {`<br>`viewport.place(0, 0)`<br>`host.place(0, SessionPaneLayout.composerTopPx(pane, host.height))` | **宿主盒唯一的摆位处** |
| `SessionPaneLayout.kt:1426-1427` | `fun composerTopPx(pane: Int, composer: Int): Int =`<br>`pane.coerceAtLeast(0) - composerHostHeightPx(panePx = pane, ceilingPx = composer)` | 算 `top` |
| `SessionPaneLayout.kt:1290-1291` | `fun composerHostHeightPx(panePx: Int, ceilingPx: Int): Int =`<br>`ceilingPx.coerceAtMost(panePx.coerceAtLeast(0)).coerceAtLeast(0)` | v103 的"唯一收容点" |
| `SessionScreen.kt:3332-3336` | `val pane = if (constraints.hasBoundedHeight) constraints.maxHeight else viewport.height + host.height` | **pane 自己的高度从哪来** |

**由这三行可以逐字推出一个恒等式**（有界分支）：

```
hostBottom = top + host.height
          = (pane − min(host.height, pane)) + host.height
          = pane                      当 host.height ≤ pane
          = host.height               当 host.height > pane   ← 越界形态
```

⇒ **v103 的 `top + host ≡ pane` 是一条关于"宿主盒"的恒等式，它对"并集"什么也没说。**
这正是 v104 早就写下的判断（`SessionPaneLayout.kt:1325-1328`）。**本轮的真机数据再次证明它。**

### 1.2 **+5（600 档）＝ 宿主盒把内容**下移 5px**，并集从宿主盒底沿穿出去**

真机读数（`REPORT_COMPOSER_OVERFLOW4.md` §2.2，原文照抄）：

| 项 | 值 |
|---|---|
| pane | `[0,162][1366,213]` **h=51** |
| `imeTop` | **213**（`type=ime frame=[0,213][1366,600]`，`mInsetsHint bottom=387`） |
| 一行形态 ⇒ host 盒 | `top = 162 + 51 − 48 = 165`，`hostBottom = 165 + 48 = ` **`213`** |
| 带（band，被 v106 修好的那一条） | 实测 **`[170,218]`** |
| `EditText` | `[24,169][405,217]` |
| `Add` / 模型 chip / `Send` | `[399,170][441,218]` 等，底沿均 **218** |
| 并集（排除 Open terminal） | `[24,169][1364,218]` ⇒ `unionBottom = ` **218** |

**算式（+5）**：

```
①  = unionBottom − hostBottom
   = 218 − 213
   = 218 − (165 + 48)          ← 宿主盒底沿 = pane 底沿 = imeTop，三者同值，无口径歧义
   = 218 − (bandTop + 48)
   = 218 − (170 + 48)
   = (bandTop − hostTop) = 170 − 165
   = +5                          ❌
```

**化简后 600 档只有一句话：带被放在 `hostTop + 5`，而它高 48，于是底沿 = `hostBottom + 5`。**

- 换句话说 **不是"子节点比宿主盒高"**（v105 的诊断：那才该是 `unionPx > hostPx`，即 ②）。
  ② 在 600 档**已经是 0**（`48 = 48`），证明**子节点并没有比盒子高**。
- 剩下的 5px 只能来自**盒子内部的一个向下位移**：`bandTop − hostTop = +5`。
- `Editor` 的 a11y 顶沿 **169** 比带的 170 还高 1px（居中亚像素取整），
  **那 1px 是向上���，不是向下** ⇒ 5px 全部由 `bandTop − hostTop = +5` 承担。

### 1.3 **+2（768 档）＝ 并集其实在 pane 里面，是**pane 自己**比 imeTop 高 8px**

真机读数（§2.1，原文照抄）：

| 项 | 值 |
|---|---|
| pane / 时间线盒 | `[0,162][1366,302]` **h=140** |
| `imeTop` | **294**（`type=ime frame=[0,294][1366,768]`，`mInsetsHint bottom=474`） |
| `EditText` | `[28,208][1338,256]` h=48 |
| `Add` / 模型 chip / `Send` | `[10,248][52,296]` 等，底沿均 **296** |
| 并集（排除 Open terminal） | `[10,208][1356,296]` ⇒ `unionBottom = ` **296** |
| `Open terminal`（**排除**） | `[1320,192][1366,240]` |

**算式（+2）**，分两步，每一步都可独立复核：

```
(a) 并集 vs 宿主盒：  unionBottom 296  <  pane 底沿 302        ⇒ 富余 6px
(b) 但宿主盒底沿 vs imeTop： 302 − 294 = +8                  ⇒ pane 自己越界 8px

① = unionBottom − hostBottom
   = 296 − 294
   = 296 − (302 − 8)
   = (296 − 302) + 8
   = (−6) + 8
   = +2                             ❌
```

⇒ **768 档的 +2 = `pane 越界 8px` − `并集在 pane 内的富余 6px`。**

**这里必须把话说死，否则下一轮又会去错的地方找：**

- 768 档 **没有**"带向下位移"这个故障：并集底沿 296 **在 pane 底沿 302 之内**（富余 6）。
  §1.2 那条 `bandTop − hostTop = +5` 的机制**在 768 档读数上查不到**（堆叠形态，
  `Composer.kt:2311-2326` 那一支，结构不同）。
- 768 档的真问题是 **pane 高 140、pane 顶 162 ⇒ 底 302，而 ime 顶只有 294**：
  **ime 之上只剩 `294 − 162 = 132` 的地方，pane 却要 140 —— 装不下，多要 8px。**
- 这 8px 与 v106 记的账**逐位吻合**：`COMPOSER_OVERFLOW_FIX4.md` §3.2 的
  「768 Dp 预算线 `timelineShare(768, 466, 1, 0)`」用的是 **IME = 466**，
  而**真机 IME = 474**（`mInsetsHint bottom=474`）⇒ **466 + 8 = 474 ⇒ 差正好 8px**。
  即：**pane 是按一个比真机小 8px 的 IME 高度算出来的，于是自己长到了 ime 底下 8px。**

### 1.4 **为什么两档差不同（子高与 hostPx 都是 48，答案在这里）**

因为**两档越界的责任点根本不是同一个**，`48` 这个共同事实只排除了"高度"这一类原因，
没有排除"位置"这一类原因里的两种：

| | 600 档（`imeTop 213`） | 768 档（`imeTop 294`） |
|---|---|---|
| 形态 | **一行**（`Composer.kt:2268-2310` 那一支） | **堆叠**（`Composer.kt:2311-2326` 那一支） |
| 越界的主体 | **子节点并集** | **pane 本身** |
| 谁越界 | 并集 218 **越过宿主盒底 213** | pane 底 302 **越过 imeTop 294** |
| 责任点 | composer 盒子**内部**的一个向下位移 | pane 的**高度约束**（`SessionScreen.kt:3332`） |
| 与 48 的关系 | 无关：并集 48 = 宿主 48（② = 0 已经证明） | 无关：pane 140 与子节点 48 是两个量 |
| 差值 | `bandTop − hostTop = 5` | `paneBottom − imeTop = 8`，再被并集 6px 富余抵掉 6 ⇒ `2` |
| 修好它需要动 | composer 的摆放 | **pane 的高**（或 `hostBottom` 的口径） |

**⇒ 一句话：子高 48 与 hostPx 48 两档相同，恰恰说明"高度"已不是问题；
剩下两个 5px / 8px 全是"位置"，而这两个"位置"分属两个不同的责任点，
所以 v106 那种"改一个带高"的单点手术（方案 A/B）结构上碰不到 ①。**

---

### 1.5 四候选逐条排除（`文件:行号` + 原文）

| # | 候选 | 结论 | 证据 |
|---|---|---|---|
| ① | 宿主盒用**上一帧/旧值** | **部分成立，但不是 ① 的成因** | `SessionScreen.kt:3339` `host.place(0, SessionPaneLayout.composerTopPx(pane, host.height))` —— `host.height` 是**这一帧实测**的（`SessionScreen.kt:3312-3317` 刚 `measure` 完），不是 state，**没有旧值**。`SessionScreen.kt:3332-3333` 有界分支 `pane = constraints.maxHeight` 也是**这一帧的**。**但**：768 档的 pane 高 140 来自**调用方给的约束**，而那个约束是按 **IME=466** 算的（`COMPOSER_OVERFLOW_FIX4.md` §3.2 `timelineShare(768,466,1,0)`），真机 IME=474 ⇒ **约束本身比真机小 8px**。这是**上游算错**，不是"上一帧"。 |
| ② | `offset`/负 padding/`Arrangement.Bottom`/二次位移 | **600 档成立（方向已证），768 档排除** | 600：`bandTop 170 − hostTop 165 = +5` 只能由盒内一次**向下**位移产生。候选行：`SessionScreen.kt:3175` `contentAlignment = Alignment.BottomCenter`（**只对齐底、不会向下甩**，子节点比盒高时只会向上溢出 ⇒ 需另一行配合）；`SessionScreen.kt:3191` `.let { if (shared) it.heightIn(max = composerCeiling) else it.fillMaxSize() }`（**`.heightIn(max=)` 不设 min ⇒ 盒可被量成 0**）。**768 档排除**：并集底 296 **在** pane 底 302 **之内**（富余 6），盒内位移在那里是**负**的。 |
| ③ | 盒基线与子节点对齐基准不一致 | **部分成立（那 1px 已在账上）** | `Composer.kt:2293` `val editorTop = (band - editor.height).coerceAtLeast(0) / 2` 与 `Composer.kt:2298-2299` `editor.place(0, editorTop)` / `toolRow.place(0, 0)` —— 编辑器按**带**居中、工具行按**带顶**对齐。600 档 `Editor` a11y 顶沿 **169** 比带顶 170 还高 1px = 这条居中的亚像素取整。**但那 1px 是向上的**，它**减轻**越界、不制造越界 ⇒ 不是 +5。 |
| ④ | 另有**非 composer 节点**被算进宿主盒或并集 | **排除** | 并集口径**已排除 `Open terminal`**（`REPORT_COMPOSER_OVERFLOW4.md` §2.1：`[1320,192][1366,240]` 不计入）。排除后 600 档并集 = `[24,169][1364,218]`、768 档 = `[10,208][1356,296]`，**两档并集底沿都由 ⊞/模型 chip/Send 决定**（`[399,170][441,218]` / `[10,248][52,296]`），全是 composer 自己的控件。**没有第三方节点。** |

---

## 2. §2 方案：**本轮不改生产代码 —— 改了就必须是猜的，而猜出来的是第四次假绿**

### 2.1 已证（写进测试，可复核）

**① 可以精确分解成两个互相独立的项**，两个项都能用真机字面量复核：

```
criterion1 = unionBottom − hostBottom
           = (bandTop − hostBoxTop) + (unionPx − hostPx)
             \____ 位置项 ____/        \____ 高度项 ____/
```

- **高度项（`unionPx − hostPx`）在两档都恒等于 0** —— v106 已经把它关掉了（② = 0）。
- ⇒ **① 现在 100% 是位置项。** 这就是"band 与并集已等值 48=48，问题不在高而在位置"的**可证版本**。

### 2.2 未证（因此不动作）

**位置项由哪一行产生，我没有证到行。** 已知的只有它在两档的**数值**
（600：`+5`；768：并集 −6 与 pane +8 相抵得 `+2`）。

**为什么不猜**（本仓已经为此付过三次代价，见 `COMPOSER_OVERFLOW_FIX4.md` §1.3）：

| 轮 | pin 的量 | 验收的量 | 结果 |
|---|---|---|---|
| v103 | **宿主盒**的 `top + host ≡ pane` | **子节点并集**底沿 | 假绿 |
| v104/v105 | 两个**纯函数**的返回值 | 设备上**子组合实测** | 假绿 |
| v106（本仓自评） | 纯函数 `composerBandHeightDp` | 设备实测 `unionPx` | 600 档 ② 过、① 纹丝不动 |

⇒ **在真机不可用的前提下改 `SessionScreen.kt:3339` 或 `Composer.kt:2293`，
只能再加一个"钉模型的 pin"，而那个 pin 必然自洽、必然绿、必然不描述设备会怎么画。**
本轮**不重复这个动作**。改为：把可证的分解钉成测试（§3），把**每档一条**的下一手探针交出去（§5）。

### 2.3 为什么不降强度

- **`MIN_TOUCH_TARGET` 48dp 一个像素没动**；`BAND_HEIGHT` / `TOOL_ROW_HEIGHT` 本轮**一个字没改**。
- **v103–v106 的 pin 一条没放宽**；本轮是**零生产代码改动**（只新增 1 个测试类）。
- 换来的代价是"① 还没修好" —— **如实记在 §5，不假装修好了。**

### 2.4 diff

```diff
# 生产代码：本轮 **0 处改动**（ui/session/** 未动一字）

+ app/src/test/java/com/xiufeigo/opencodeshell/ComposerPositionDecompositionV107Test.kt  (新增, 3 用例)
+ research/v97_fix/COMPOSER_OVERFLOW_FIX5.md                                       (本文件)
```

**未碰**：`_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers/**`、
`ui/home/**`、`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`。
**未新增任何用户可见字符串。**

---

## 3. §3 回归：**33 例全绿**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --console=plain `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTouchTargetV105Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerChildUnionV104Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerOverflowV103Test" `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineShrinkCrashV102Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerPositionDecompositionV107Test"
```

```
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 1m 18s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=0
```

| 类 | tests | failures | errors |
|---|---|---|---|
| `ComposerBandTouchTargetV105Test` | 5 | 0 | 0 |
| `ComposerChildUnionV104Test` | 7 | 0 | 0 |
| `ComposerOverflowV103Test` | 8 | 0 | 0 |
| `QueueTimelineShrinkCrashV102Test` | 10 | 0 | 0 |
| `ComposerPositionDecompositionV107Test`（新增） | 3 | 0 | 0 |
| **合计** | **33** | **0** | **0** |

锁：`LOCK_ACQUIRED` → `BUILD SUCCESSFUL` → `LOCK_RELEASED=True`（`rm --` 走 mavis-trash）。
**只跑了任务书点名的 4 个类 + 1 个新类**，没有一次跑十几个。

### 3.1 新增 pin 覆盖什么（以及它**不**覆盖什么）

| 用例 | 钉的东西 |
|---|---|
| `criterionOneSplitsExactlyIntoPositionPlusHeight` | 600 档：位置项 `+5` + 高度项 `0` **逐位复现**真机 `+5`；并断言高度项**仍为 0**（② 不回退） |
| `theSevenSixEightTierIsChargedToThePaneNotToTheComposer` | 768 档：`−6 + 8 + 0 = +2` 逐位复现；断言 `ime 之上只剩 132 而 pane 要 140` |
| `bothTiersShareChildHeightFortyEightSoHeightCannotExplainTheDelta` | 两档子高/hostPx 都是 48 **却**给出 5 与 2 两个不同值 ⇒ 差**不可能**是高度造成的 |

> **这三条钉的是"诊断"，不是"修复"。** 它们把真机字面量
> （`162/51/213/165/170/218` 与 `162/140/302/294/296`）作为**数据**固定下来，
> 下一次改布局时**一旦把真机行为改坏，这三条立刻红**。它**不**声称 ① 已修好。

---

## 4. §4 真机复验步骤（下一轮用；本轮未装机、未跑真机）

前置：`wm size` / `wm density` 显式设置（`1366x768`/`160`、`1366x600`/`160`），
`mInputShown=true`，坐标从 `uiautomator dump --compressed` 解析（沿用 `parse_5556.ps1`）。

| 步 | 动作 | 期望（本轮**未达成**，是下一轮的目标） |
|---|---|---|
| 1 | 768 + IME，dump，量 pane / ⊞ / 模型 chip / Send（**排除 `Open terminal`**） | ① `unionBottom ≤ imeTop 294`（现 `296−294=+2`）；② `unionPx ≤ band`（本轮**不得**从 48 退回去） |
| 2 | 600 + IME，同上 | ① `unionBottom ≤ imeTop 213`（现 `218−213=+5`）；② `unionPx ≤ 48`（现 0） |
| 3 | 768 关 IME | ① 仍成立；时间线盒 / pane 仍 **140**、`readable` 仍 **92**（≥64） |
| 4 | 768 排队 0 / 2 / 11 条 | pane 恒 **140**；① 三格都成立 |
| 5 | 缩窗往返 2 轮（768→600→768→600→768） | `pidof` 不变、无 `FATAL`/`IllegalArgumentException`、同档几何**逐位相同**（无振荡） |
| 6 | 队列条 | `Steer` h=43、**`×` h 仍 48**、三者 `clickable=true` |

**② 在两档都必须保持（v106 的成果不得回退）；① 是本轮未修好的那一条。**

---

## 5. §5 未闭环

1. **① 未修好（本轮的诚实结论）**。位置项 `+5`（600）由**哪一行**产生，本轮**没证到行**。
   在真机不可用时改它 = 猜 = 第四次假绿。**已证的是分解式（§2.1），已钉的是分解式。**

2. **768 档的 +2 与两条硬 pin 冲突，需要 Lead 裁定**。它要成立必须同时满足：
   * `unionBottom ≤ imeTop 294`，而
   * pane **140**、pane 顶 **162** ⇒ pane 底 **302**（宿主盒按 `composerTopPx` 底对齐，底沿**恒** = 302）。
   **除非 composer 不再与 pane 底对齐（留 8px 空档）**，否则 ① 在 768 **数学上无解**。
   而"留 8px 空档"会直接打破 v103 的 `top + host ≡ pane`（`ComposerOverflowV103Test` 已 pin，不许放宽）。
   ⇒ **三条路，请裁定一条**：
   * **A**：`hostBottom` 口径改回 `pane` 底沿（`REPORT_COMPOSER_OVERFLOW4.md` §8.3 提过：按 pane 底 ① = `296−302 = −6` ✅）；
   * **B**：pane 高 140 → 132（**会动 140/92 两个 pin**，且 600 档 `pane 51` 也可能被动）；
   * **C**：修上游的 IME 高度来源（v106 模型按 466 算、真机 474，差 8），让 pane 本来就不该是 140。

3. **pane 高度的上游来源没查到底**。已知 `pane = constraints.maxHeight`（`SessionScreen.kt:3333`），
   但**谁给出这个 140**（`composerCeiling` / `timelineShare(768, 466, 1, 0)` 那条链）**本轮未追**。
   这是 §5 #2 的 **C 路线**的入口。

4. **600 档那 5px 的行未定位 —— 但本轮已把搜索范围砍到"确定在 composer 子树内"**。

   **本轮新查到的事实**（`SessionScreen.kt`）：
   - `:2090-2096` `val composerCeiling = (paneHeight - SessionPaneLayout.TIMELINE_MIN_CONTENT).coerceAtLeast(if (minimalComposer) COMPOSER_MINIMAL_HEIGHT else COMPOSER_COMPACT_HEIGHT)`
     ⇒ 600 档 `minimalComposer = true`、`paneHeight = 51`、`TIMELINE_MIN_CONTENT = 64`
     ⇒ `composerCeiling = (51 − 64).coerceAtLeast(56) = ` **`56`**
   - `:2078-2081` `sharedComposer = SessionPaneLayout.composerSharesViewport(paneHeight, composerHeight)`
   - `:3174-3194` host `Box(contentAlignment = Alignment.BottomCenter, .heightIn(max = 56))`
   - `:3267-3270` `floorPx = safeViewportFloorPx(maxOf(declaredFloorPx, measuredFloorPx), availablePx = 51)`
     ⇒ 600 档 `floorPx = 51`（显式让位区）⇒ `:3298-3301` `composerHostCeilingPx(51, 51) = (51−51).coerceAtLeast(0) = 0`
     ⇒ `:3295` `hostHeightPx = 0`

   **把这五个数代进摆位（`:3339`）**：

   ```
   host.height = 0
   top        = composerTopPx(pane = 51, composer = 0) = 51 − 0 = 51
   host 盒     = 绝对坐标 [162+51−0, 162+51] = [213, 213]  （0 高的盒）
   contentAlignment = BottomCenter ⇒ 48 高的 composer 挂在盒底之上：
                 y = 0 − 48 = −48  ⇒ 绝对 [165, 213]
   ⇒ **按收容点自己的算式，unionBottom 应当 = 213，越界 = 0。**
   ```

   **真机读数是 218。⇒ 收容点的算式解释不了那 5px。**

   ⇒ **本轮可断言（不是猜测）**：600 档的 `+5` **必然产生在 `SessionScreen.kt:3339`
   以下的 composer 子树内部**（composer 卡片里的某个 `WindowInsets` 消费者 / 垂直 padding / 自绘盒），
   **不在**「pane 减法 / 宿主盒高度 / 摆位」这一层。v107 死前说的
   *"the single containment point"* 对 600 档**不成立** —— 收容点算出来是 0 越界。

   **下一手探针（一条就够，二选一）**：
   * **A（推荐，成本最低）**：在 `Composer.kt` 那个 `Layout` 的 `layout(...) {}` 里临时打印
     `constraints.maxHeight / band / editorTop / editor.height / toolRow.height`。
     **期望看到 `band = 48` 且 `toolRow.place(0, 0)`** ⇒ 卡片自身没位移，
     5px 就来自**卡片外层的 modifier 链**（`Composer` 的入参 `modifier` 上挂了 insets/padding）。
     再往上一层看 `SessionScreen.kt:3193` `composer()` 传下来的那个 `composerRegion`。
   * **B**：真机侧直接定位 —— `uiautomator dump` 里量 `Open terminal`（`[1320,184][1366,232]`）
     与 ⊞（`[399,170][441,218]`）**顶沿差 14、底沿差 14** ⇒ 两者被**同一个**位移量搬走了 5px，
     说明位移点在 **⊞ 之上**（卡片级），不在 ⊞ 自身。


5. **本轮没做的**：未跑真机、未装机、未跑其余回归类（预算所限，只跑任务书点名的 4 + 1）；
   未追 §5 #3 的上游链；未验证 ≥11 条深队列的真机几何。

