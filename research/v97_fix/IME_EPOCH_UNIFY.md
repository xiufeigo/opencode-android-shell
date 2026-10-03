# IME_EPOCH_UNIFY —— v125：统一 IME 口径分叉（新 `426` vs v110 旧 epoch `474`）

- 修复工程师：mavis（v125，用 mcode 执行）
- 日期：2026-10-04
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 前序：`CRITERION_MODEL_FIX.md`（v124，把模型 `imeBottomPx` 修正到真机口径 **426**）、`PANE_ENTITY_FIX.md`（v123）
- 授权边界：**只碰测试文件**（2 个 `app/src/test/**`）+ `research/**`（本报告）。
  **本轮生产代码净改动 = 零**（`git status --porcelain -- app/src/main ui _upstream app/build.gradle.kts` = **0 条**）。
  **未** assembleDebug、**未** clean、**未**装机。
- 断言纪律：**不降、只增或保持**。**0 条被放宽、0 条被删除、1 条新增（1 个测试方法 + 5 条断言）、3 条按实测口径重述。**
- **行尾**：两个被改测试文件的 `CR` 字节数实测 **= 0**（全 LF）。
  **本轮没有用过** `git checkout` / `git restore` / `git add --renormalize` 改任何文件行尾（v123 的教训）。
- **本轮没有新增任何用户可见字符串**（改的全是 `app/src/test/**`）。

---

## 0. 状态（先给结论）

| 项 | 结论 |
|---|---|
| 判据性 `474` | **2 个类**（`V108` / `V103`）⇒ 本轮**已统一到 426** |
| 记录性 `474` | **3 处**（V107 散文 / V88·V89·V92 的 `474.5f`）⇒ **合法保留**，已加 epoch 注释 |
| 无关项（非 IME 值） | **2 处**（V102 窗口高度采样 / V73 上游源码行号）⇒ 保留 |
| 生产代码 | **零改动** |
| pin 有没有被放宽 | ✅ **没有**。重述 3 条、**新增** 5 条断言 + 1 个测试方法 |
| 反向验证 | ✅ **3 次注入、3 次全红**（§5，含 2 次「改回 474」+ 1 次「改错取侧」） |
| 回归 | ✅ **108/108 绿**，13 个类，**0 失败、0 错误、0 减少**（V103 8→9） |
| 最终口径表还有分叉吗 | ✅ **没有**（见 §6；唯一「第三方」是 settings 路径的 `474.5f` **dp**，属**另一个 IME 状态**，不是本分叉） |

> ⚠️ **一条必须先说的诚实更正**：v124 §6 第 2 条把 `V103 / V106 / V107 / V108` 一并列为「仍带 474 旧 epoch」。
> 本轮逐个打开核对后，这个清单**不准确**：
> `V107` **根本没有 `474` 这个值**（只有一句散文），`V106` 全树**没有 `474` 也没有 `426`**。
> 真正的判据性遗留只有 **V108 与 V103 两个类**。

---

## 1. 真机 insets 原文（唯一权威，第 3 次终验 §4，**逐字未改**）

```
InsetsSource id=3 type=ime frame=[0,342][1366,768] visibleFrame=[0,342][1366,768] visible=true
InsetsSource id=324c0001 type=navigationBars frame=[0,720][1366,768] visible=true
ROTATION_0={nonDecorInsets=[0,0][0,48], configInsets=[0,0][0,48], nonDecorFrame=[0,0][1366,720], configFrame=[0,0][1366,720]}
mDisplayFrame=Rect(0, 0 - 1366, 768)
```

⇒ `imeTop = 342` ⇒ **`imeBottomPx = 768 − 342 = 426`**（**不是** 474）
⇒ `navBarTop = 720` ⇒ **`floorPx = 768 − 720 = 48`**
⇒ **`474 − 426 = 48` = 恰好一个导航条**

**哪一套与真机 insets 一致：只有 `426`。** `474` 反推出 `imeTop = 294`，
那是 **v110 那一轮**的读数，与当前 build 的 `frame=[0,342][1366,768]` 不符。
两者的差 `48` 恰好是一个导航条 ⇒ 旧口径**系统性多算了一个导航条**。

---

## 2. 两套口径对照表（`文件:行号` + 原文）

### 2.1 判据性使用（`474` 喂进生产算式 ⇒ **必须改**）

| 位置 | 原文 | 喂给谁 |
|---|---|---|
| `ComposerPaneBottomCriterionV108Test.kt:79` | `private val imeBottom768 = 474 // window - imeTop(294)` | `believedImeBottomPx(...)`（`:358`）+ `room768`（`:380`） |
| `ComposerOverflowV103Test.kt:172` | `val imeHeights = listOf(387, 474)` | `Frame.pane = windowPx − CHROME_PX − imeBottomPx`（`:105`）⇒ 三维真值表**每一格** |

两处都**用「底 inset」这一侧**（`window − imeTop`），方向没错，**错的是那个减数**。

### 2.2 已是新口径（v124 已改，本轮核对无误）

| 位置 | 原文 |
|---|---|
| `ComposerDivergenceLocateV119Test.kt:49` | `private val imeBottom768 = 426` |
| `ComposerDivergenceLocate2V120Test.kt:44` | `private val imeBottom768 = 426` |
| `ComposerCriterionParityV118Test.kt:74` | `private val imeBottom768 = 426` |

### 2.3 记录性（历史读数，**合法保留**）

| 位置 | 原文 | 为什么是记录性 |
|---|---|---|
| `ComposerPositionDecompositionV107Test.kt:45` | `` * (`timelineShare(768, 466, ...)` modelled 466, the device measured **474**). `` | **纯散文**，类里**没有 `474` 这个值**；它真正的判据输入是 `imeBottom600 = 387`（600 档，`:121`），**不在 768 档口径上**。它 pin 的是 v106/v107 时代的**诊断史**。 |
| `ServersFormImeFloorV88Test.kt:70` | `private val imeFrameDp = 474.5f` | `474.5f` 是 **dp 浮点**、**带半像素**，来源 `research/v87_verify/5556/` 的 `29_edit_kbdown` **状态**，是 settings/servers 表单的独立 IME 状态读数。整数 `474` 不可能是它四舍五入的结果。 |
| `ServersFormImeFloorDownV89Test.kt:327` | `val upImeDp = 474.5f` | 同上（`research/v88_verify/5556/`）。 |
| `SettingsModalImeBandV92Test.kt:52` | `private val upImeDp = 474.5f` | 同上；**且同一个类的 `:43` 已经用 `imeDp = 426f`**——两个数是**两个状态**，不是矛盾。 |

### 2.4 无关项（**根本不是 IME 值**）

| 位置 | 原文 | 性质 |
|---|---|---|
| `QueueTimelineShrinkCrashV102Test.kt:181` | `val maxHeights = listOf(0, 1, 12, 32, 51, 63, 64, 65, 100, 138, 140, 474, 600, 768, 1440)` | `474` 是**窗口高度**扫描的一个探针点，与 IME 无关。 |
| `TerminalFocusRoutingV73Test.kt:57` | `` // branch (`terminal/terminal.tsx:466-474`) `` | 上游 **TS 源码行号**。 |

---

## 3. 判定性 vs 记录性分类（任务要求的明确回答）

> **判据性（必须改成 426 口径）= 2 个类：`ComposerPaneBottomCriterionV108Test`、`ComposerOverflowV103Test`。**
> **记录性（合法保留）= 3 处：`ComposerPositionDecompositionV107Test`（散文）、`ServersFormImeFloorV88Test` / `ServersFormImeFloorDownV89Test` / `SettingsModalImeBandV92Test`（`474.5f` dp 另一状态）。**
> **无关 = 2 处：`QueueTimelineShrinkCrashV102Test`（窗口高度）、`TerminalFocusRoutingV73Test`（源码行号）。**

判别标准（不是"看起来像"）：**该 `474` 的值或其派生物是否进入断言的比较式**。
进比较式 ⇒ 判据性；只在注释/散文里，或它本身不是 IME 量 ⇒ 记录性/无关。

---

## 4. 统一动作与算式（每条都给"为什么新值对"）

### 4.1 `V108` — `imeBottom768: 474 → 426`

**依据**：`imeBottom = 768 − imeTop(342) = 426`。旧值 `474` 反推 `imeTop = 294`（v110 读数）。

**派生 ①：`room768 = 606 − 426 = 180`（原 `606 − 474 = 132`）**
⇒ `paneHeight768 − room768 = 140 − 180 = **−40**`（原 `140 − 132 = 8`）。
**768 档不是"差 8px"，而是"富余 40px"。**
并且 **`8 = 48 − 40`** —— 那个幻影 8 恰好等于「一个导航条 − 40」，
**这就把 v124 §1.3 的结论在 V108 这一族里独立地又证了一遍**。

**派生 ②：钳位测试 `fourSixSixIsNotAValueTheProductionImePathCanProduce` 不变**
按生产 `SessionPaneLayout.kt:1531-1544` 逐位算：
`claimed = 426 − 0 = 426`，`room = 606 − 284 = 322`，`426 > 322` ⇒ 返回 `322`。
`474` 时同样是 `474 > 322` ⇒ 也返回 `322`。
⇒ **两个值都触发同一分支、产出同一个 322**，所以这条断言**结构上无法区分 426 与 474**（§6 诚实记账第 1 条）。
本轮只把它**消息里的 `474` 改成 `426`**，断言逻辑一字未动。

**派生 ③：类头 §2 的 `606 − 474 = 132` 链**改为 `606 − 426 = 180`，
并加一段 v125 更正块，指明该节**结论**（600 档零误差）**不受影响**——它是在 600 档推出的，那里什么都没动。

### 4.2 `V103` — `imeHeights: listOf(387, 474) → listOf(387, 426)`

**依据**：同上。且本类用的是**未钳位**读数 `pane = window − CHROME_PX − imeBottomPx`，
`CHROME_PX = 162`（`REPORT_U1E.md` §4.3）⇒ `pane(768) = 768 − 162 − 426 = **180**`。

⭐ **交叉验证（这是本条最强的理由）**：换源后 V103 的 `pane(768) = 180`，
与 `ComposerDivergenceLocate2V120Test` 的 `paneClampOff768 = 180`
（v124 已按实测改成的那条）**逐位一致**。
**两个类用不同写法、不同时代、互不引用，却收敛到同一个数** ⇒ `426` 为真、`474` 为旧，
这是**独立于注释**的算术证据。
反证：`474` 会给出 `132`，而全树没有任何一条实测口径指向 `132`。

**600 档（387）与单元格数（27）一字未动。**

### 4.3 新增：`V103.theSevenSixEightImeInputIsTheMeasuredFourTwoSix…`（+1 测试方法 / +5 断言）

**为什么必须新增**：V103 原有的三维真值表是**纯相对**断言
（`bottom ≤ pane`、`pane == bottom`…），
所以把 768 档 IME 从 `474` 换成 `426` 时，**它一条都不会红**——每格仍自洽，只是整体平移。
**换句话说：没有这条新 pin，474 与 426 在这个类里根本无法区分。**（这正是本任务要消灭的"互相证明对方错"。）

为了让这条 pin **不是自证的**，我把 `imeHeights` 从测试局部变量**提到类级**，
使三维真值表与新 pin 读**同一份**字面量（否则新 pin 只是复述了一个副本）。
5 条新断言：①含 `426` ②**不含** `474` ③`pane(768) = 180` ④富余 `40` ⑤幻影 8 的来路 `48 − 40` 与退役输入复现，外加 600 档 `51` 不动。

### 4.4 为什么**不是放宽**（逐条）

| 重述 | 为什么不叫放宽 |
|---|---|
| `V108:384` `assertEquals(…, 8, 140 − room768)` → `assertEquals(…, −40, …)` | 原值 `8` 的**前提已被真机 insets 原文否掉**（`474` 反推 `imeTop 294` 不符）。新断言**逐位更紧**：它同时钉住"富余 40"**和**"幻影 8 = 48 − 40"**和**"退役输入逐位复现 8"**——原断言只钉了孤零零一个 8。 |
| `V103` 三维真值表 `474 → 426` | **一条断言都没改**，只换了输入值；且同时**新增**一条专门判别它的 pin。 |
| `V108:367` 消息 `raw 474` → `raw 426` | **只改消息文本**，断言表达式 `believed == imeBottom768 \|\| believed == paneBox768 − essentialPx()` **一字未动**（其中 `imeBottom768` 随常量走）。 |
| `V108` 测试改名 `theEightPxIsA…` → `theImeShortfallTermIsA…` | 旧名里的 `8` 是退役读数的产物。它 pin 的**结构主张**（"该项只存在于 768、在 600 上恒为 0"）**完全保留**，断言一条未删。 |
| 记录性 3 处 | **一字未改**，只加 epoch 注释。 |

⇒ **净结果：0 条被松、0 条被删、1 个测试方法 + 5 条断言新增、3 条按实测重述。**

---

## 5. 反向验证（**3 次注入、真跑抄原文**）

### 5.1 注入 ①：V103 的 IME 输入改回 `474` ⇒ **红**

```
> Task :app:testDebugUnitTest FAILED
ComposerOverflowV103Test > theSevenSixEightImeInputIsTheMeasuredFourTwoSixAndNotTheRetiredV110FourSevenFour FAILED
9 tests completed, 1 failed
BUILD FAILED in 3s
```
```
java.lang.AssertionError: the 768 IME input must be the measured 426, was [387, 474]
```

### 5.2 注入 ②：V108 的 `imeBottom768` 改回 `474` ⇒ **红**

```
> Task :app:testDebugUnitTest FAILED
ComposerPaneBottomCriterionV108Test > theImeShortfallTermIsAThreeHundredSixtyEightOnlyTermAndIsZeroOnTheSixHundredTier FAILED
8 tests completed, 1 failed
BUILD FAILED in 3s
```
```
java.lang.AssertionError: 768 has 40px to spare, not a shortfall: 140 − (606 − 426) = −40 expected:<-40> but was:<8>
```

### 5.3 注入 ③：**改错取侧**（`imeBottom768 = 342`，直接拿 `imeTop` 当底 inset）⇒ **红**

```
> Task :app:testDebugUnitTest FAILED
ComposerPaneBottomCriterionV108Test > theImeShortfallTermIsAThreeHundredSixtyEightOnlyTermAndIsZeroOnTheSixHundredTier FAILED
8 tests completed, 1 failed
BUILD FAILED in 3s
```
```
java.lang.AssertionError: 768 has 40px to spare, not a shortfall: 140 − (606 − 426) = −40 expected:<-40> but was:<-124>
```
逐位：`606 − 342 = 264`，`140 − 264 = −124`。⇒ **取侧错也会当场变红。**

### 5.4 恢复实测值 ⇒ 108/108 绿（§7）

---

## 6. 最终口径表（全测试树 `grep '\b474\b|\b426\b'` 逐个归类）

| 类 | 值 | 角色 / 性质 | 是否已统一 |
|---|---|---|---|
| `ComposerDivergenceLocateV119Test` | **426** | 判据（768 档 IME 底 inset） | ✅ v124 |
| `ComposerDivergenceLocate2V120Test` | **426** | 判据 | ✅ v124 |
| `ComposerCriterionParityV118Test` | **426** | 判据 | ✅ v124 |
| `ComposerPaneBottomCriterionV108Test` | **426**（原 474） | 判据 | ✅ **本轮** |
| `ComposerOverflowV103Test` | **426**（原 474） | 判据 | ✅ **本轮** |
| `ComposerPositionDecompositionV107Test` | 474 仅**散文**；判据用 387（600 档） | 记录 | ✅ 保留（无判据性 474） |
| `ServersFormImeFloorV88Test` | `474.5f` **dp** | 记录（v83 `29_edit_kbdown` @5556） | ✅ 保留 |
| `ServersFormImeFloorDownV89Test` | `474.5f` **dp** | 记录 | ✅ 保留 |
| `SettingsModalImeBandV92Test` | `474.5f` dp（`upImeDp`）+ `426f`（`imeDp`） | 记录 + 判据各一（两状态） | ✅ 保留 |
| `ServersFormImeBandV83Test` | `426f` | 判据（settings 路径，已是实测口径） | ✅ |
| `SettingsDialogImeInsetV82Test` / `SettingsImeDownGeometryV97Test` / `SettingsShortWindowImeV84Test` | `426f` | 判据（IME 底 inset，settings 路径） | ✅ 早已统一 |
| `QueueTimelineShrinkCrashV102Test` | 474 作**窗口高度**探针 | 无关 | ✅ 保留 |
| `TerminalFocusRoutingV73Test` | `terminal.tsx:466-474` | 无关（源码行号） | ✅ 保留 |
| `SessionPaneChromeFloorV79` / `SessionPaneImeBandsV81` / `SessionPaneImeBandV84` / `SessionPaneShortWindowV78` / `ComposerEditorSlotV79` / `ComposerEditorFormV80` | 426 作**窗口高度 dp** | 无关（**与 IME 无关的同号巧合**） | ✅ 保留 |

⭐ **必须说清的一件事**：树里的 `426` 承担**三种互不相干的角色**——
① 768 档 **IME 底 inset**（本任务的真口径）；② **窗口高度** dp（V78–V84 的 480 档相关量）；
③ settings 路径的 **IME 底 inset dp**。
②与①**数值相同纯属巧合**，把它算进"已统一"会是错的记账，故单列。

⇒ **结论：判据性 768 档 IME 口径全树已是 `426`，分叉已消除。**
剩下的 `474` 全部是「记录性历史读数」或「与 IME 无关」，**没有一处再是判据**。

---

## 7. 测试证据

**环境**：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`
**锁**：`Test-Path .agent-gradle-lock` = 不存在 → 写 `v125-ime-epoch-unify`
→ **前台**跑完全部运行 → **绝对路径字面量**删除。**未** assembleDebug、**未** clean、**未**装机。

### 7.1 命令

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.ComposerPaneBottomCriterionV108Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerOverflowV103Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerPositionDecompositionV107Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerDivergenceLocateV119Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerDivergenceLocate2V120Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerCriterionParityV118Test" `
  --tests "com.xiufeigo.opencodeshell.SessionPaneImeBeliefV77Test" `
  --tests "com.xiufeigo.opencodeshell.ServersFormImeFloorV88Test" `
  --tests "com.xiufeigo.opencodeshell.ServersFormImeFloorDownV89Test" `
  --tests "com.xiufeigo.opencodeshell.ServersFormImeBandV83Test" `
  --tests "com.xiufeigo.opencodeshell.SettingsModalImeBandV92Test" `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineShrinkCrashV102Test" `
  --tests "com.xiufeigo.opencodeshell.TerminalFocusRoutingV73Test"
```

**原文**：
```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 4s
52 actionable tasks: 2 executed, 50 up-to-date
```

### 7.2 用例数（`app/build/test-results/testDebugUnitTest/*.xml` 实测）

| 类 | 用例数 | failures | errors | 备注 |
|---|---|---|---|---|
| `ComposerPaneBottomCriterionV108Test` | **8** | 0 | 0 | 用例数不变（+3 断言、改名 1） |
| `ComposerOverflowV103Test` | **9** | 0 | 0 | 原 8，**新增 §pin 1** ⇒ **+1** |
| `ComposerPositionDecompositionV107Test` | 4 | 0 | 0 | **一字未改** |
| `ComposerDivergenceLocateV119Test` | 8 | 0 | 0 | **一字未改**（v124 成果仍全绿） |
| `ComposerDivergenceLocate2V120Test` | 6 | 0 | 0 | **一字未改** |
| `ComposerCriterionParityV118Test` | 8 | 0 | 0 | **一字未改** |
| `SessionPaneImeBeliefV77Test` | 11 | 0 | 0 | **一字未改**（v77 P0 仍全绿） |
| `ServersFormImeFloorV88Test` | 9 | 0 | 0 | 记录性，**未改，仍绿** |
| `ServersFormImeFloorDownV89Test` | 9 | 0 | 0 | 记录性，**未改，仍绿** |
| `SettingsModalImeBandV92Test` | 9 | 0 | 0 | 记录性，**未改，仍绿** |
| `ServersFormImeBandV83Test` | 7 | 0 | 0 | **未改，仍绿** |
| `QueueTimelineShrinkCrashV102Test` | 10 | 0 | 0 | 无关项，**未改，仍绿** |
| `TerminalFocusRoutingV73Test` | 10 | 0 | 0 | 无关项，**未改，仍绿** |
| **合计** | **108** | **0** | **0** | **用例数只增不减（V103 8→9）** |

### 7.3 范围与行尾

```
git status --porcelain  →  2 个 app/src/test/**  +  .agent-gradle-lock（已删）+  research/v97_fix/IME_EPOCH_UNIFY.md
git status --porcelain -- app/src/main ui _upstream app/build.gradle.kts  →  0 条
CR 字节数：V108=0  V103=0   （全 LF）
```

---

## 8. 为什么不降强度

1. **没有放宽任何断言**：0 条被松、0 条被删；重述 3 条的前提都被真机 insets 原文否证；**新增** 1 个测试方法 + 5 条断言。
2. **没有把失败洗成绿**：本轮**零生产改动**，不存在需要改 pin 掩盖的回归。
3. **新增的 pin 比删掉的更值**：V103 原来的三维真值表**结构上无法区分 426 与 474**
   （§5.1 证明改回 474 时它**不红**，只有新 pin 红）——这说明"统一"若只改字面量，**下次照样会漂回去**。
4. **v77 点名的 pin 一条没碰** ⇒ `SessionPaneImeBeliefV77Test` 11/11 全绿。

---

## 9. 未闭环（如实记账）

1. ⭐ **`V108.fourSixSixIsNotAValueTheProductionImePathCanProduce` 结构上无法区分 426 与 474**
   （§4.1 派生 ②：两者都触发钳位、都返回 322）。本轮只改了它的消息文本。
   要让它成为真正的判据，需要它断言**实测的 180**（未钳位 pane）或钉 `claimed = 426 − floor`；
   **这会改变它的性质**（它原本 pin 的是"466 不可达"），**超出本轮授权的"统一 IME 口径"**，留给下一轮。
2. ⭐ **`floorPx` 的分叉仍在**（**本轮明确未动**）：V119/V120/V118 用 `floorPx = 48`（v124 按实测修正），
   而 **V108 / V103 仍用 `floorPx = 0`**。这是**另一条轴**（nav bar，不是 IME），
   本轮任务书只授权统一 **IME 口径**，故**未越界**。⚠️ 它同样是"两套口径并存"，建议下一轮单独处理。
3. **settings 路径的 `474.5f` dp 没有对 insets 原文复核**：它是 `29_edit_kbdown` **状态**的读数
   （`research/v87_verify/5556/`），**不是**当前 `type=ime frame=[0,342][1366,768]` 那个状态。
   本轮无该状态的 insets 原文可核 ⇒ **按记录性保留，不臆造新值**。
4. **V120 §2.2 的「开放账目」pin 仍过期**（v124 §6.1 结转，本轮未改）：它要求 `DIVERGENCE_LOCATE2.md` 仍写着「−8px 未闭环」，
   而该文档本轮**不在授权内**。
5. **A（去 chrome）未落地**（v123 结转的主项）：本轮证据（V108 富余 40 / V103 交叉收敛到 180）
   **没有改变**「去 chrome 会让钳位整个不开」这条判断（v124 §2.4）⇒ **仍不可动**。
6. **本轮未装机、未跑真机**（授权禁止）⇒ 设备基线（`readable` 92/51、时间线盒 140、−10 / −12）**未复验**。
   §4 全部算式基于第 3 次终验 insets 原文（**逐字未改**）。
7. **交接文档未追加**：v124 记有 `research/HANDOFF.md` / `research/v98_RESUME.md` 的 v124 一节；
   本轮**未**追加 v125 一节（`research/**` 虽在授权内，但为避免与主线交接文档并发写，本轮只交本报告）。
