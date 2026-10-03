# FLOOR_AXIS_UNIFY —— v126：统一 `floorPx` 轴分叉（`48` 导航条 vs `0` 退役 epoch）

- 修复工程师：mavis（v126，用 mcode 执行）
- 日期：2026-10-04
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 前序：`IME_EPOCH_UNIFY.md`（v125，**其 §9.2 自陈本项未闭环**）、`CRITERION_MODEL_FIX.md`（v124）、`PANE_ENTITY_FIX.md`（v123）
- 授权边界：**只碰测试文件**（4 个 `app/src/test/**`）+ `research/**`（本报告）。
  **本轮生产代码净改动 = 零**（`git status --porcelain -- app/src/main ui _upstream app/build.gradle.kts` = **0 条**）。
  **未** assembleDebug、**未** clean、**未**装机。
- 断言纪律：**0 条被放宽、0 条被删除**；**新增 1 个测试方法（+7 条断言）**、1 条断言**由二选一析取收紧为精确相等**、5 处输入按实测重述。
- **行尾**：4 个被改测试文件 `CR` 字节数实测 **= 0**（全 LF）。
  **本轮没有用过** `git checkout` / `git restore` / `git add --renormalize` 改任何文件行尾（v123 的教训）。
- **本轮没有新增任何用户可见字符串**（改的全是 `app/src/test/**`）。

---

## 0. 状态（先给结论）

| 项 | 结论 |
|---|---|
| 与真机自洽的一套 | **`48`（导航条底 inset）**，唯一一套 |
| 判据性 `floorPx = 0` | **6 处 / 4 个类**（`V108`×3、`V107`、`V105`、`V109`）⇒ **本轮全部统一到 48** |
| 判据性 `floorPx = 48` | **3 个类**（`V118`/`V119`/`V120`）⇒ 核对无误，**未动** |
| 记录性/无关 `floorPx = 0` | **4 处**（`V77` × 4，**合成输入**，见 §3.2）⇒ 合法保留 |
| 生产代码 | **零改动** |
| pin 有没有被放宽 | ✅ **没有**。1 条析取**收紧为相等**、**新增** 7 条断言 + 1 个测试方法 |
| 反向验证 | ✅ **2 次注入、2 次全红**（§5，含 768 档与 600 档**各一次**） |
| 回归 | ✅ **86/86 绿**，11 个类，**0 失败、0 错误**（`V108` 8→9） |
| 最终口径表还有分叉吗 | ✅ **没有**（见 §6） |

> ⚠️ **对 v125 §9.2 的一处必须先说的诚实更正**：v125 写的是「**V108 / V103** 仍用 `floorPx = 0`」。
> 本轮逐个打开核对后，**`ComposerOverflowV103Test` 根本没有任何 `floorPx = 0`**——
> 它的 `floorPx` 来自 `timelineViewportFloorPx(...)` → `safeViewportFloorPx(...)`，
> 取值是 **51（600 档）/ 64（768 档 `BAND_PX`）**，那是**时间线带地板**，**与导航条毫无关系**（§6.1 单列）。
> 真正的 `0` 阵营是 **`V108`（3 处）、`V107`、`V105`、`V109`（各 1 处）**，
> 其中 **`V105`、`V109` 两处 v125 完全没有提到**。

---

## 1. 真机 insets 原文（唯一权威，第 3 次终验 §4，**逐字未改**）

`research/v97_verify/5554_md/REPORT_FINAL_VERIFY.md:357-362`

```
InsetsSource id=3 type=ime frame=[0,342][1366,768] visibleFrame=[0,342][1366,768] visible=true flags= insetsRoundedCornerFrame=false
InsetsSource id=324c0001 type=navigationBars frame=[0,720][1366,768] visible=true flags= insetsRoundedCornerFrame=false
ROTATION_0={nonDecorInsets=[0,0][0,48], configInsets=[0,0][0,48], nonDecorFrame=[0,0][1366,720], configFrame=[0,0][1366,720]}
mDisplayFrame=Rect(0, 0 - 1366, 768)
```
600 档同批原始行（`:369`）：
```
ROTATION_0={nonDecorInsets=[0,0][0,48], configInsets=[0,0][0,48], nonDecorFrame=[0,0][1366,552], configFrame=[0,0][1366,552]}
```

⇒ **`floorPx`（768 档）= `768 − navBarTop(720) = 48`**
⇒ **`floorPx`（600 档）= `600 − nonDecorBottom(552) = 48`**
⇒ `nonDecorInsets=[0,0][0,48]` 在**两个档位上各写了一遍**这个 48，互为佐证。

**为什么只有 `48` 与真机自洽（三条独立理由）**：
1. **算式**：导航条原文 `frame=[0,720][1366,768]`，高 `768 − 720 = 48`。`0` 不对应任何一条原文。
2. **生产同形**：`SessionScreen.kt:2363` 是
   `val paneFloorPx = if (titlebarBottom && !desktop) 0 else navBottomPx`，
   `:2364-2369` 把它当 `floorPx` 递给 `believedImeBottomPx`。
   **`navBottomPx` 就是 48**——生产在真机上递的就是 48，测试写 0 意味着**模型与生产当场不一致**。
   （这两条正则 pin 已存在于 `ComposerDivergenceLocate2V120Test:352-361` 与 `SessionPaneImeBeliefV77Test:330,346`，本轮直接复用，未重写。）
3. **两套口径在真机上给出不同的 pane**：见 §4.1——`0` 报 322、`48` 报 370，**不可能同时对**。

---

## 2. 两套口径对照表（`文件:行号` + 原文）

### 2.1 判据性 `0` 阵营（喂进生产算式 ⇒ **本轮已改**）

| # | 位置 | 原文（改前） | 喂给谁 |
|---|---|---|---|
| 1 | `ComposerPaneBottomCriterionV108Test.kt:175` | `floorPx = 0,`（`panePx600()`） | `believedImeBottomPx(:1531)` |
| 2 | `ComposerPaneBottomCriterionV108Test.kt:363` | `floorPx = 0,` | 同上（600 档） |
| 3 | `ComposerPaneBottomCriterionV108Test.kt:379` | `floorPx = 0,` | 同上（**768 档——唯一 load-bearing 的一处**） |
| 4 | `ComposerPositionDecompositionV107Test.kt:126` | `floorPx = 0,` | 同上（600 档） |
| 5 | `ComposerBandTouchTargetV105Test.kt:382` | `floorPx = 0,` | 同上（600 档） |
| 6 | `ComposerUnionBottomP5V109Test.kt:308` | `floorPx = 0,` | 同上（600 档） |

### 2.2 判据性 `48` 阵营（**已按实测，核对无误，未动**）

| 位置 | 原文 | 备注 |
|---|---|---|
| `ComposerCriterionParityV118Test.kt:76,78` | `private val navFloor768 = 48` / `navFloor600 = 48` | 注释即写「生产 `SessionScreen.kt:2362` 读的就是它」 |
| `ComposerDivergenceLocateV119Test.kt:51,58` | `private val navFloor768 = 48` / `navFloor600 = 48` | 注释即写「同一段 insets 原文」 |
| `ComposerDivergenceLocate2V120Test.kt:46` | `private val navFloor768 = 48` | 同上 |

### 2.3 记录性 / 无关（本轮**一字未改**）

| 位置 | 原文 | 为什么合法保留 |
|---|---|---|
| `SessionPaneImeBeliefV77Test.kt:122` | `floorPx = 0,`（配 `essentialPx = 0`、`visibleBoxPx = 4000`） | **退化调用者** pin：`SessionPaneLayout.kt:1538` 的 `essentialPx <= 0` 分支在读 `floorPx` 之前返回。测的是"零 essential 不做任何扣除"，**不是**任何设备的导航条读数。 |
| `SessionPaneImeBeliefV77Test.kt:207,218` | `floorPx = 0,`（配 `atGate - 1` / `atGate`） | **纯代数** pin：在 `PANE_ESSENTIAL_SPARE_ROOM` 闸门两侧刻画钳位形状，box 是 `essential * 2` 造出来的**合成值**，不对应任何 `dumpsys` 行。 |
| `SessionPaneImeBeliefV77Test.kt:240` | `floorPx = 0,`（`for (ratio in 0..100) for (owed in 0..box step 13)`） | **性质扫描**：在合成 (box, owed) 网格上证明 `believed <= owed`。`0` 是"无导航条"这一**被测前提**，换成 48 会把性质扫描变成另一个命题。 |
| `ComposerOverflowV103Test.kt`（全类） | `floorPx` = 51 / 64 | ⚠️ **同号不同义的兄弟项**：来自 `timelineViewportFloorPx` / `safeViewportFloorPx`，是**时间线带地板**，**与导航条无关**。**它从来没有参与 `floorPx` 的这次分叉**（§6.1 单列）。 |
| `ComposerChildUnionV104Test` / `ComposerBandTallEnoughV106Test` | `BAND_PX = 64` / `floor = 51` | 同上，时间线带地板，**另一条轴**。 |

---

## 3. 判定性 vs 记录性分类（任务要求的明确回答）

**判别标准（不是"看起来像"）**：**这个 `floorPx` 的值是否作为实参进入 `believedImeBottomPx(...)` 这个生产函数**。
是 ⇒ 判据性（它就是"生产会拿到的那个地板"）；否 ⇒ 记录性/无关。

> **判据性（必须统一）= 6 处 / 4 个类：`V108`（3 处）、`V107`、`V105`、`V109`。**
> **记录性（合法保留）= 4 处：`SessionPaneImeBeliefV77Test` 的 4 个合成/退化调用点。**
> **另一条轴（不算入本分叉）= `V103` / `V104` / `V106` 的 `floorPx`（时间线带地板 51/64）。**

---

## 4. 统一动作与算式（每条都给"为什么新值对"）

### 4.1 `V108` 768 档——**本轮唯一真正改变结论的一处**

**依据**：`floorPx = 768 − 720 = 48`（导航条原文）。
**为什么它 load-bearing**：`believedImeBottomPx` 把 `floorPx` **用了两次**——

```
SessionPaneLayout.kt:1540   val claimedPx = (imeBottomPx - floorPx.coerceAtLeast(0)).coerceAtLeast(0)
SessionPaneLayout.kt:1543   return roomPx + floorPx.coerceAtLeast(0)
```

⇒ `floorPx` **落在返回值上**，不是只落在比较里。
所以 `0` **从来不是"无害占位"**：

| | `floorPx = 0`（退役） | `floorPx = 48`（真机） |
|---|---|---|
| `claimed` | `426 − 0 = 426` | `426 − 48 = 378` |
| `room = 606 − 284` | `322` | `322` |
| 比较 | `426 > 322` ⇒ 钳位开 | `378 > 322` ⇒ 钳位开 |
| **返回** | `322 + 0 = **322**` | `322 + 48 = **370**` |
| 生产 pane | `606 − 322 = 284` | `606 − 370 = **236**` |

⇒ **两套口径对同一台设备给出两个不同的 pane（284 vs 236），不可能同时对。**

⭐ **交叉验证（最强的一条理由）**：`48` 算出的 `370` / `236`，
与 `ComposerDivergenceLocateV119Test:163-166` 早已按实测 pin 的
`believed = room + floor = 322 + 48 = 370`、`panePx = 606 − 370 = 236 = 284 − 48`
**逐位一致**，也与 `ComposerCriterionParityV118Test:524-530` 的
「`panePx = 606 − 370 = 236`、多算 **96**」一致。
**两个类用不同写法、不同时代、互不引用，却收敛到同一个数 ⇒ `48` 为真、`0` 为旧。**

**重述：`fourSixSixIsNotAValueTheProductionImePathCanProduce`（由析取收紧为相等）**

改前（v125 留下的）：
```kotlin
assertTrue("clamp output must be raw 426 or clamped 322, was $believed",
    believed == imeBottom768 || believed == paneBox768 - essentialPx())   // 二选一
```
改后：
```kotlin
assertEquals("the clamp returns room + floor: 322 + 48 = 370, exactly one reachable value",
    paneBox768 - essentialPx() + navFloor768, believed)                   // 精确相等
assertEquals("…which is digit-exact with the sibling class's 370, not the retired 322", 370, believed)
assertNotEquals("and the retired floorPx = 0 reading is what produced 322 — it is gone",
    paneBox768 - essentialPx(), believed)                                 // 新增：钉死 322 不再出现
assertNotEquals("466 is unreachable from believedImeBottomPx", 466, believed)  // 原样保留
```

**为什么这不是放宽，而是变紧**：二选一析取**允许两个值**（`426` 或 `322`），
精确相等**只允许一个值**（`370`）。可满足的取值集合从 2 个缩到 1 个 ⇒ **严格更严**。
且 v125 §9.1 自己记账说这条断言「**结构上无法区分**」——本轮**在同一个洞上把它补死了**：
现在它同时钉住 `claimed`、钉住 `room + floor`、钉住与兄弟类的逐位一致、并显式排除退役值 `322`。
**旧断言的"466 不可达"这一结构主张一字未动、仍在。**

### 4.2 `V108`/`V107`/`V105`/`V109` 的 600 档（3 处 + 3 处）——**结果不变，但理由是构造性的**

**算式**：`visibleBoxPx = 438`，`essentialPx = 284`，`PANE_ESSENTIAL_SPARE_ROOM = 2`
⇒ `438 < 284 * 2 = 568` ⇒ `SessionPaneLayout.kt:1539`
`if (visibleBoxPx < essentialPx * PANE_ESSENTIAL_SPARE_ROOM) return imeBottomPx`
**在读到 `floorPx` 之前就返回** ⇒ `believed = 387` 逐字不变 ⇒ `pane = 438 − 387 = 51` 逐字不变。

**为什么仍然必须改**：`0` 是**退役 epoch 的读数**（v110 那一轮窗口没扣导航条），
它与 `474` **同源**——`474 − 426 = 48` 正是那个被多算的导航条（v125 §1 已证）。
留着 `0` 就等于在同一个模型里**一半用真机、一半用旧 epoch**，这正是本轮要消灭的分叉。
而且这些调用点**是判据性的**（值真的进了生产函数），按 §3 的判别标准就不能留。

**为什么结果没变不是"侥幸"**：本轮**把这条短路本身钉成断言**
（新增 pin 里的 `438 < 284 * SPARE_ROOM` 与 600 档 `at600 == 387`），
以后守卫一旦移位就会当场变红，而不是靠"这个调用点恰好没报错"来蒙混。

### 4.3 新增 pin：`theNavigationBarFloorIsReadOnTheSevenSixtyEightTierAndTheSixHundredTierIsInertToIt`（+1 测试方法 / +7 断言）

**为什么必须新增**：`V108` 那条 768 断言改前是**二选一**，`0` 与 `48` 都能让它绿
（`0 → 322` 落在析取的第二个分支里）。**没有新 pin，光改字面量证明不了任何事。**
新 pin 逐条把 §4.1 的算式和 §4.2 的短路都写死，并且**双向断言**（`48` 与 `0` 互为反证）：
① `426 − 48 = 378`（地板确实从 IME 声称里被扣）
② `322 + 48 = 370`（返回值确实是 room + floor）
③ `assertNotEquals(370, 322)`（两条轴**不可互换**——退役值给不出 370）
④ `284 − 48 = 236`（构造性恒等 `pane = essentialPx − floorPx`）
⑤ `606 − 370 = 236`（同一件事的两种写法互证）
⑥ `navFloor768 == navFloor600 == 48`（两档是同一个导航条）
⑦ 600 档 `at600 == 387` **且** `438 < 284 * SPARE_ROOM`（短路是构造性的，不是巧合）

**调用生产函数/常量、不造假绊线**：`essentialPx()` 读
`SessionPaneLayout.PANE_ESSENTIAL_HEIGHT`，`PANE_ESSENTIAL_SPARE_ROOM` 读生产常量，
`believedImeBottomPx` 调生产函数；断言里**没有**任何"两侧都是字面量"的对照
（`assertNotEquals(370, 322)` 的两侧是**两条互斥的轴**，不是同一量的两个写法）。

### 4.4 为什么**不是放宽**（逐条）

| 重述 | 为什么不叫放宽 |
|---|---|
| `V108` 768 断言 `believed == 426 \|\| believed == 322` → `assertEquals(..., 370)` | **取值集合从 2 个缩到 1 个**，严格更严。且额外新增 `assertNotEquals(322)` 显式排除退役值。`assertNotEquals(466)` 原样保留。 |
| `V108`/`V107`/`V105`/`V109` 的 600 档 `0 → 48` | **一条断言都没改**，只换输入；结果逐字不变（`51` 仍是 `51`），并**新增** 2 条断言把"为什么不变"钉成构造性事实。 |
| 新增 `theNavigationBarFloorIsRead…` | **纯增**，1 个方法 + 7 条断言。 |
| `V77` 4 处 `floorPx = 0` | **一字未改**，仅在报告里分类为合成输入。 |

⇒ **净结果：0 条被松、0 条被删；1 条析取收紧为相等、1 个测试方法 + 7 条断言新增、6 处输入重述。**

---

## 5. 反向验证（**2 次注入、真跑抄原文**）

### 5.1 注入 ①：V108 的 `navFloor768` 改回 `0` ⇒ **红，2 条**

```
> Task :app:testDebugUnitTest FAILED
ComposerPaneBottomCriterionV108Test > fourSixSixIsNotAValueTheProductionImePathCanProduce FAILED
    java.lang.AssertionError at ComposerPaneBottomCriterionV108Test.kt:438
ComposerPaneBottomCriterionV108Test > theNavigationBarFloorIsReadOnTheSevenSixtyEightTierAndTheSixHundredTierIsInertToI
t FAILED
    java.lang.AssertionError at ComposerPaneBottomCriterionV108Test.kt:484
9 tests completed, 2 failed
BUILD FAILED in 3s
```
断言原文（逐字）：
```
java.lang.AssertionError: …which is digit-exact with the sibling class's 370, not the retired 322 expected:<370> but was:<322>
java.lang.AssertionError: the 768 and 600 navigation bars are the same 48 expected:<0> but was:<48>
```

### 5.2 注入 ②：V108 的 `navFloor600` 改回 `0`（**只动 600 档**）⇒ **红，1 条**

```
> Task :app:testDebugUnitTest FAILED
ComposerPaneBottomCriterionV108Test > theNavigationBarFloorIsReadOnTheSevenSixtyEightTierAndTheSixHundredTierIsInertToI
t FAILED
9 tests completed, 1 failed
BUILD FAILED in 3s
```
断言原文（逐字）：
```
java.lang.AssertionError: the 768 and 600 navigation bars are the same 48 expected:<48> but was:<0>
```

⭐ **这两次注入的分工**：①证明 **768 档的值本身**被钉住（`0` 交不出 `370`）；
②证明**两档是同一个导航条**这条**跨档一致性**也被钉住——
否则"600 档随便填什么都行、反正短路了"就会成为下一个人钻的空子。

### 5.3 恢复实测值 ⇒ 86/86 绿（§7）

---

## 6. 最终口径表（全测试树 `grep 'floorPx = 0[,)]'` 逐个归类）

### 6.1 `floorPx` 这一个名字在树里承担的**两条互不相干的轴**

| 轴 | 取值 | 来自哪个生产函数 | 涉及类 | 与本分叉 |
|---|---|---|---|---|
| **A. 导航条底 inset** | **48** | `SessionScreen.kt:2363` 的 `navBottomPx` ⇒ `believedImeBottomPx(floorPx=…)` | `V118`/`V119`/`V120`（原本就对）＋ `V108`/`V107`/`V105`/`V109`（**本轮统一**） | ✅ **本轮统一完成** |
| **B. 时间线带地板** | **51 / 64** | `timelineViewportFloorPx` → `safeViewportFloorPx` | `V103` / `V104` / `V106` | ⚠️ **另一条轴，从未参与本分叉** |

⚠️ **同名不同义，必须分开记账**：`floorPx` 这个形参在 `V103` 家族里指的是
"时间线至少留多少像素"，在 `V118` 家族里指的是"导航条有多高"。
**把 `V103` 算进"已统一"会是错的记账**——它一个字都没改，也不需要改。

### 6.2 导航条轴（轴 A）最终表

| 类 | 档 | 值 | 性质 | 是否已统一 | 若未统一为何合法 |
|---|---|---|---|---|---|
| `ComposerDivergenceLocateV119Test` | 768/600 | **48** | 判据（导航条底 inset） | ✅ v124 起就对 | — |
| `ComposerDivergenceLocate2V120Test` | 768 | **48** | 判据 | ✅ v124 起就对 | — |
| `ComposerCriterionParityV118Test` | 768/600 | **48** | 判据 | ✅ v124 起就对 | — |
| `ComposerPaneBottomCriterionV108Test` | 768 | **48**（原 `0`） | 判据，**load-bearing** | ✅ **本轮** | — |
| `ComposerPaneBottomCriterionV108Test` | 600 | **48**（原 `0`） | 判据（短路，不变） | ✅ **本轮** | — |
| `ComposerPositionDecompositionV107Test` | 600 | **48**（原 `0`） | 判据（短路，不变） | ✅ **本轮** | — |
| `ComposerBandTouchTargetV105Test` | 600 | **48**（原 `0`） | 判据（短路，不变） | ✅ **本轮** | — |
| `ComposerUnionBottomP5V109Test` | 600 | **48**（原 `0`） | 判据（短路，不变） | ✅ **本轮** | — |
| `SessionPaneImeBeliefV77Test` ×4 | — | `0` | **合成/退化输入** | ✅ 保留 | 退化调用者（`essentialPx=0`）、闸门代数、性质扫描——`0` 是**被测前提**"无导航条"，不是设备读数（§2.3） |
| `ComposerOverflowV103Test` | — | 51 / 64 | **轴 B**（时间线带地板） | ✅ 保留 | **另一条轴**，本轮不改（§6.1） |
| `ComposerChildUnionV104Test` / `ComposerBandTallEnoughV106Test` | — | 64 / 51 | **轴 B** | ✅ 保留 | 同上 |

⇒ **结论：导航条轴上全树判据性 `floorPx` 已是 `48`，分叉已消除。**
剩下的 `0` 全部是合成输入，**没有一处再是设备读数**。

### 6.3 ⭐ `48` 在树里承担的**多种互不相干的角色**（照 v125 的做法分开记账）

`48` 同时是本仓库的**导航条高**、**触摸靶/dp 最小交互尺寸**、**composer 卡高**和**band 高**。
**把它们混进"已统一"会是巧合记账**，故单列：

| 角色 | 出现处 | 说明 |
|---|---|---|
| **导航条底 inset（本轮的真口径）** | `V118`/`V119`/`V120`/`V108`/`V107`/`V105`/`V109` 的 `navFloor*` | `768 − 720` / `600 − 552` |
| **触摸靶 / 最小交互尺寸 dp** | `SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX`（`V105:379` 注释 `// 48, :108`）、`V108:223` `hostPx` | 真机 `Add`/`Send` 节点 `48×48`，**与 insets 无关** |
| **composer 卡片固有高** | `V104` 的 `CARD_PX = 48`（真机 `EditText h=48`）、`V106:482` `minOf(48, hostHeight)` | 一行卡的固有高，**与导航条无关** |
| **band 高** | `V108:348-349` `val band = 48; val unionPx = 48` | criterion 2 的 band，**与导航条无关** |

⇒ 这四类 `48` **数值相同纯属同一台设备的巧合**（该机恰好 48dp 导航条 / 48dp 触摸靶）。
**只有第一类是本轮统一的"口径"**，其余三类**本轮一字未动，也不该动**。

---

## 7. 测试证据

**环境**：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`
**锁**：`Test-Path .agent-gradle-lock` = 不存在 → 写 `v126-floor-axis-unify`
→ **前台**跑完全部运行 → **绝对路径字面量**删除。**未** assembleDebug、**未** clean、**未**装机。

### 7.1 命令（最终回归，11 个类）

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.ComposerPaneBottomCriterionV108Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerPositionDecompositionV107Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTouchTargetV105Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerCriterionParityV118Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerDivergenceLocateV119Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerDivergenceLocate2V120Test" `
  --tests "com.xiufeigo.opencodeshell.SessionPaneImeBeliefV77Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerOverflowV103Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerChildUnionV104Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTallEnoughV106Test"
```

**原文**：
```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 45s
```

### 7.2 用例数（`app/build/test-results/testDebugUnitTest/*.xml` 实测）

| 类 | 用例数 | failures | errors | 本轮改动 |
|---|---|---|---|---|
| `ComposerPaneBottomCriterionV108Test` | **9** | 0 | 0 | **8 → 9（+1 新 pin）**；1 条断言收紧 |
| `ComposerPositionDecompositionV107Test` | 4 | 0 | 0 | 输入 1 处（`:126`） |
| `ComposerBandTouchTargetV105Test` | 6 | 0 | 0 | 输入 1 处（`:382`） |
| `ComposerUnionBottomP5V109Test` | 11 | 0 | 0 | 输入 1 处（`:308`） |
| `ComposerCriterionParityV118Test` | 8 | 0 | 0 | **一字未改** |
| `ComposerDivergenceLocateV119Test` | 8 | 0 | 0 | **一字未改** |
| `ComposerDivergenceLocate2V120Test` | 6 | 0 | 0 | **一字未改** |
| `SessionPaneImeBeliefV77Test` | 11 | 0 | 0 | **一字未改**（v77 P0 仍全绿） |
| `ComposerOverflowV103Test` | 9 | 0 | 0 | **一字未改**（轴 B，见 §6.1） |
| `ComposerChildUnionV104Test` | 7 | 0 | 0 | **一字未改**（轴 B） |
| `ComposerBandTallEnoughV106Test` | 7 | 0 | 0 | **一字未改**（轴 B） |
| **合计** | **86** | **0** | **0** | **用例数只增不减（V108 8→9）** |

### 7.3 范围与行尾

```
git status --porcelain
 M app/src/test/java/com/xiufeigo/opencodeshell/ComposerBandTouchTargetV105Test.kt
 M app/src/test/java/com/xiufeigo/opencodeshell/ComposerPaneBottomCriterionV108Test.kt
 M app/src/test/java/com/xiufeigo/opencodeshell/ComposerPositionDecompositionV107Test.kt
 M app/src/test/java/com/xiufeigo/opencodeshell/ComposerUnionBottomP5V109Test.kt
?? .agent-gradle-lock   (已删)

git status --porcelain -- app/src/main ui _upstream app/build.gradle.kts  →  0 条
CR 字节数：V108=0  V107=0  V105=0  V109=0   （全 LF）
```

---

## 8. 为什么不降强度

1. **没有放宽任何断言**：0 条被松、0 条被删。唯一被"改写"的那条
   `believed == 426 || believed == 322` → `assertEquals(…, 370)`，
   **可满足的取值集合从 2 个缩到 1 个**，是收紧而非放松。
2. **没有把失败洗成绿**：本轮**零生产改动**，不存在需要改 pin 掩盖的回归。
3. **新增的 pin 比删掉的更值**：改前 768 档断言是二选一析取，`0` 也能让它绿
   （注入① 里它**确实**因为新 pin 才红）——**光改字面量证明不了口径统一**。
   新 pin 双向断言 `48` 与 `0`，两个方向都红。
4. **把"结果没变"从巧合升级为构造性事实**：600 档三处输入从 `0` 换成 `48` 后结果逐字不变，
   本轮**新增断言**把短路条件 `438 < 284 * 2` 钉死，守卫移位即红。
5. **v77 点名的 P0 pin 一条没碰** ⇒ `SessionPaneImeBeliefV77Test` 11/11 全绿。

---

## 9. 未闭环（如实记账）

1. ⭐ **对 v125 §9.2 清单的更正已成事实**（见 §0）：`V103` **不在** `floorPx = 0` 阵营里，
   它那条 `floorPx` 是**时间线带地板**（51/64），与导航条无关。
   真正的 `0` 阵营比 v125 记的多两个类（`V105`、`V109`），已在本轮一并处理。
2. ⭐ **"768 档多算 96" 这个残差没有因为本轮而改变**。本轮把 `V108` 的模型输入修成实测，
   于是 `V108` 自己也得出 `pane = 236`、设备 `140` ⇒ **多算 96**——
   与 `V118`/`V119` **完全一致**（这是好结果：三族类首次完全收敛到同一个残差）。
   但**残差本身仍未消除**，误差源是 **chrome 二次计入 + v120 的 60**，不是 `floorPx`。
   详见 `CRITERION_MODEL_FIX.md` §1（`96 = 84 + 60 − 48`）。
3. **A（去 chrome）仍不可动**（v123 结转）：`floorPx` 修成实测后，
   钳位**依旧会开**（`claimed 378 > room 322`）⇒ 结论未变 ⇒ 仍不可动。
4. **`V120 §2.2` 的「开放账目」pin 仍过期**（v124 结转，本轮未改）：
   它要求 `DIVERGENCE_LOCATE2.md` 仍写着「−8px 未闭环」，而该文档本轮不在授权内。
5. **settings 路径的 `474.5f` dp 未复核**（v125 §9.3 结转）：属 `29_edit_kbdown` **状态**的读数，
   不是当前 `type=ime frame=[0,342][1366,768]` 那个状态 ⇒ **不臆造新值**。
6. **本轮未装机、未跑真机**（授权禁止）⇒ 设备基线（`readable` 92/51、时间线盒 140、−10 / −12）**未复验**。
   §4 全部算式基于第 3 次终验 insets 原文（**逐字未改**）。
7. **交接文档未追加**：v124/v125 都记有 `research/HANDOFF.md` / `research/v98_RESUME.md` 的对应一节；
   本轮**未**追加 v126 一节（为避免与主线交接文档并发写，本轮只交本报告）。
