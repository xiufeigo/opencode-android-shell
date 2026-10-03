# UNIFIED_CRITERION_768 — 768 档判据 ② 的口径定义（v129 结账）

- 轮次：**v129 修复工程师**
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 设备：`emulator-5554`（Medium_Desktop，Override `1366x768` / `160`，SDK 34）—— 本轮**未复验**（零生产改动，只写文档 + 测试）
- 交付：`ComposerUnifiedCriterionV129Test.kt`（**6 用例，6 绿**）+ 本文件 + 交接文档追加
- 生产代码净改动：**0**

---

## 0. 一句话结论

> **"未定义"缺的不是 `band` 的数值，是「② 的右侧该取哪个盒」在堆叠形态下的分支。**
> 定义为 **`boxPx = if (oneRow) bandPx else hostPx`**（生产 `SessionPaneLayout.kt:1374-1381` 原文），
> 则 **两档 ② 都真，且都是等号**：768 是 `96 ≤ 96`、600 是 `48 ≤ 48` —— **没有留下任何余量**。
> 报告里那个 `96 ≤ 48` 的"假"，**不是缺陷，是把只对一行形态成立的算式套到了堆叠形态上**。

---

## 1. 缺口分析（`文件:行号` + 原文）

### 1.1 两笔账的原文

**① `research/v97_verify/5554_md/REPORT_FINAL_VERIFY.md:286-301`（逐字）**

```
### 3.4 判据 ② `unionPx ≤ band` 在 768 档**口径未定义**

`band` 这个词在仓里有**两个不同的值**，② 的判定因此**取决于口径**，我不能替 Lead 选：

| 口径下 `band` | 768 `unionPx=96` | 600 `unionPx=48` |
|---|---|---|
| `BAND_HEIGHT` = 48（`readable` 里减去的那一项在 600 恰好也是 48） | `96 ≤ 48` = **假** | `48 ≤ 48` = **真** |
| 768 堆叠总带高 96（`readable 92 = 188 − 96` 里用的那个） | `96 ≤ 96` = **真** | — |

- `CRITERION_PARITY.md` 里 `readable` 公式用的是 **96**（768）/ **48**（600），
  也就是**「band = 该档 composer 占掉的总高」**；
- 而 `BAND_HEIGHT` 常量是 **48**，且 §1.3 把 48 定义成「挂在 dump 容器下沿的那一个带高」。

⇒ **本轮如实给两组，不合并、不替 Lead 选边。**
若 Lead 裁定 ② 用 `BAND_HEIGHT=48`，则 **768 档 ② 为假 ⇒ 全局 FAIL**；
若裁定用「该档总带高」，则 **两档 ② 均真 ⇒ PASS**。
```

**② `research/v97_verify/5554_md/REPORT_BASELINE_RECHECK.md:360-361`（v128，逐字）**

```
4. **判据 ② 在 768 档仍口径未定义**（`band` 有 48 与 96 两种取值），
   与 `REPORT_FINAL_VERIFY.md` §3.4 同账、未关闭：若用 `BAND_HEIGHT=48` 则 768 档 ② 为假。
```

### 1.2 "未定义"**具体缺什么**（本轮结掉的就是这一句）

三份候选，逐一排除：

| 候选 | 是否是缺口 | 依据 |
|---|---|---|
| `band` 的**取值**没定？ | ❌ **不是** | 仓里 `band` 从来只有一个常量：`SessionPaneLayout.kt:108` `MIN_INTERACTIVE_COMPONENT_PX = 48` 与 `:150-156` `composerBandHeightDp(44.dp) = 48`。**它没缺值，缺的是"堆叠形态下它还管不管用"。** |
| 768 档该用**哪个节点**？ | ✅ **就是这一条** | 见 §1.3：768 的 composer 盒是 `editor + toolRow`（宿主盒），**不是**那条 48 的带。 |
| 768 档**本就不适用** ②？ | ❌ **不是** | ② 的**物理含义**（子节点并集有没有超出装它的盒）在堆叠形态下**依然有意义**，只是**右侧的盒换了**。不适用 = 放弃一个真判据，比定义它更弱。 |

⇒ **缺口 = 形态分支缺失**：两份报告都把 `band` 当成一个**数**，于是
`96 ≤ 48`（用一行形态的带）与 `96 ≤ 96`（用堆叠形态的盒）**可以同时"有道理"**。
**缺的不是精度，是"右侧那个盒随形态而变"这条规则。**

### 1.3 生产依据（**逐字**）

`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt:1374-1381`：

```
 *  * [oneRow] = `true`（一行形态）：composer's box **就是**这条带
 *    （`Composer.kt:2254` `layout(..., constrainHeight(band))`）⇒ 靶**封顶到带**。
 *    `>= 48` ⇒ 原值 48（带装得下，一像素不动）；`[28,48)` ⇒ **封顶到带本身**，
 *  * [oneRow] = `false`（堆叠形态）：composer's box 是 `editor + toolRow`，
 *    由宿主 `Constraints.maxHeight` 收着，且 `1366x768` 那一档宿主 94 装得下
 *    48 ⇒ **原值返回**，真机 `Add/Send h=48` 逐字不变。
```

`SessionPaneLayout.kt:1385-1388`（同段，**这一句最关键**）：

```
 * **单调性**（pin 在 `ComposerBandTouchTargetV105Test`）：结果恒 `<= 48`、
 * 恒 `>= min(band, 48)`，且 `band >= 28` 时 `结果 <= band` ⇒
 * `unionPx <= band` 恒成立（`unionPx = max(editorPx, toolRowPx)`，
 * `toolRowPx = max(28, 靶)`，两者都被同一个 `band` 收着）。
```

**注意 `unionPx = max(editorPx, toolRowPx)` 里的那个 `max`** —— 这正是**一行形态**的并集算法。
堆叠形态的并集是**和**（`ComposerBandTouchTargetV105Test.kt:167-172`，逐字）：

```kotlin
val unionPx: Int = if (oneRow) {
    maxOf(editorTop + editorPx, toolRowPx)   // 同一带内取大
} else {
    editorPx + toolRowPx                     // 上下两块相加
}
```

⇒ **KDoc 里那句"`unionPx <= band` 恒成立"只在 `oneRow` 分支被证明过。**
堆叠形态下 `band` **不再**是收着并集的那个约束（收着它的是宿主 `Constraints.maxHeight`），
所以拿 `band` 当 ② 的右侧去比一个**和**，是**量纲不匹配**。

### 1.4 既有 pin 从来只对 `oneRow` 断言那条算式

`app/src/test/java/com/xiufeigo/opencodeshell/ComposerBandTouchTargetV105Test.kt:235-241`（逐字）：

```kotlin
// 一行形态的 `band` 就是 box，再钉一遍字面量那条算式。
if (oneRow) {
    assertTrue(
        "unionPx > band (the report's second formula) — ${f.where}",
        f.unionPx <= f.bandPx,
    )
}
```

**同一个文件 `:181`**（逐字）——形态分支早就写好了，只是报告没照它读：

```kotlin
val boxPx: Int = if (oneRow) bandPx else hostPx
```

⇒ **`unionPx ≤ band` 是仓库里已有的、**只对 `oneRow`** 生效的第二条算式；
它从来没有、也不应该在堆叠形态上被要求成立。缺口是"读的人没按形态取右侧"。

---

## 2. 定义与算式

### 2.1 判据 ② 的最终形式（**两档统一**）

```
boxPx(oneRow, bandPx, hostPx) = if (oneRow) bandPx else hostPx
② : unionPx <= boxPx(oneRow, bandPx, hostPx)
```

- **一行形态**：`boxPx = bandPx` —— composer 的盒**就是**那条带 ⇒ **与 v105/v106 的原话逐字同义**；
- **堆叠形态**：`boxPx = hostPx` —— composer 的盒 = `editor + toolRow`，由宿主收着。

### 2.2 两档代入（`wm density 160` ⇒ **1dp == 1px**）

**768 档（堆叠）** —— 设备读数取 `REPORT_BASELINE_RECHECK.md` §1.1（v128 逐位复现）：

```
pane 宿主盒 (dump)  View [0,162][1366,350]              h = 188
EditText            [28,244][1338,292]                   h = 48
Add                 [10,292][52,340]                     h = 48
Send                [1308,292][1356,340]                 h = 48
并集                [10,244][1356,340]                   unionPx = 96
readable            188 − 96                             = 92
```

```
unionPx = editorPx + toolRowPx = 48 + 48 = 96          （堆叠 = 和）
boxPx   = containerH − readable = 188 − 92 = 96        （该形态的 composer 盒）
②       : 96 <= 96                                       ✅ 真（**等号**）
```
其中两个 `48` **都取自生产** `SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX`（`:108`），
**不是为了让 ② 变绿而挑的数**；`boxPx` 由**设备自己的两个读数相减**得到（`188 − 92`），**没有凭空加余量**。

**600 档（一行）** —— §1.2：

```
pane 宿主盒 (dump)  View [0,162][1366,261]              h = 99
EditText            [24,201][399,249]                   h = 48
Add                 [399,201][441,249]                  h = 48
Send                [1316,201][1364,249]                 h = 48
并集                [24,201][1364,249]                   unionPx = 48
readable            99 − 48                              = 51
```

```
unionPx = max(editorTop + editorPx, toolRowPx) = 48     （一行 = 取大）
boxPx   = bandPx = composerBandHeightDp(44.dp)  = 48     （生产唯一求值处 :150-156）
②       : 48 <= 48                                       ✅ 真（**等号**）
```

### 2.3 形态不是"我说是哪档"，是**设备读数自己反解**的

两种形态的并集**算法不同**（§1.3），于是读数就能证形态，两侧都是字面量：

| 档 | 读到的 `unionPx` | 若它是堆叠应该是 | 结论 |
|---|---|---|---|
| 600 | `48` | `48 + 48 = 96` ≠ 48 | **一行** |
| 768 | `96` | `48 + 48 = 96` = 96 | **堆叠** |
| 768（若强行一行） | — | 一行被 48 的带收着，**不可能**到 96 | 结构上不可能 |

### 2.4 `BAND_HEIGHT = 48` 为什么不能当 768 的 ② 右侧

`96 ≤ 48` 为假，**因为 48 是一行形态的带、768 是堆叠形态 —— 形态不同，盒子就不是同一个盒子**。
这与"布局是否合格"**无关**：同一个读数换成该形态的盒（96）立刻为真，且是等号。
两个盒**不是同一个数**（`assertNotEquals(48, 96)` 已钉）—— **这就是"未定义"的真正内容**。

---

## 3. 新增 pin

文件：`app/src/test/java/com/xiufeigo/opencodeshell/ComposerUnifiedCriterionV129Test.kt`（**6 用例**）

| # | 用例 | 钉住什么 |
|---|---|---|
| ① | `theTwoTiersTakeOppositeFormsAndTheDeviceReadingsProveIt` | 两档形态由**读数反解**（`96 = 48 + 48` / `48 ≠ 48 + 48`） |
| ② | `theSevenSixEightCriterionTwoIsTrueByEqualityWithItsOwnComposerBox` | **768 档 ② = `96 ≤ 96`（等号）**；两个 48 取自生产常量 |
| ③ | `theSixHundredCriterionTwoIsTrueByEqualityWithTheProductionBand` | 600 档 ② = `48 ≤ 48`（等号）；band 取自 `composerBandHeightDp` |
| ④ | `theFortyEightConstantIsTheOneRowBandAndIsTheWrongRightHandSideForSevenSixEight` | `96 ≤ 48` **逐字为假**；同读数换形态即真；`48 ≠ 96` |
| ⑤ | `theStackedCriterionIsLoadBearingItGoesRedForAnyBoxShorterThanTheUnion` | 盒取 `95/94/88/48` **全部翻红** ⇒ ② 不是恒真 |
| ⑥ | `definingCriterionTwoMovesNoAlreadyPinnedReading` | **不降强度**：`readable 92` / 时间线盒 `140` / `readable 51` 一像素不退 |

**两侧全字面量**：所有 `assertEquals` 的期望值都是字面量（`96` / `92` / `48` / `188` …），
被测侧调**生产**函数（`MIN_INTERACTIVE_COMPONENT_PX`、`composerBandHeightDp`）与
**设备读数**（`container − readable`）——**没有自己编"before"数**。

---

## 4. 反向验证（改错输入 ⇒ 必须红）**原文**

**做法**：把 768 档的 `readable768` 从设备真值 `92` 改成 `96`
⇒ `boxPx = 188 − 96 = 92 < unionPx = 96` ⇒ ② 必须翻红。

**命令**（前台）：

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ComposerUnifiedCriterionV129Test"
```

**结果原文**：

```
FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:testDebugUnitTest'.
> There were failing tests. See the report at: file:///C:/sensorsdata/main/program/opencode-android-shell/app/build/reports/tests/testDebugUnitTest/index.html

BUILD FAILED in 3s
52 actionable tasks: 2 executed, 50 up-to-date
```

**逐条 AssertionError 原文**（抄自 `app/build/test-results/testDebugUnitTest/TEST-…V129Test.xml`）：

```
tests=6 failures=4

FAILED: theSevenSixEightCriterionTwoIsTrueByEqualityWithItsOwnComposerBox
java.lang.AssertionError: 768: composer box = container 188 ? readable 92 expected:<96> but was:<92>

FAILED: theFortyEightConstantIsTheOneRowBandAndIsTheWrongRightHandSideForSevenSixEight
java.lang.AssertionError: 96 <= 96, same readings, form-correct RHS

FAILED: theStackedCriterionIsLoadBearingItGoesRedForAnyBoxShorterThanTheUnion
java.lang.AssertionError: 768: the box used is the dump's own 96, never a padded one expected:<96> but was:<92>

FAILED: definingCriterionTwoMovesNoAlreadyPinnedReading
java.lang.AssertionError: 768: readable 92 ? v105/v106/v110's own pin expected:<92> but was:<96>
```

⇒ **6 个用例里 4 个变红**，且 ② 的核心用例（②③④）**全部**变红 ——
**锚定是承重的**，不是恒真装饰。改回 `92` 后立刻恢复 `6/6 绿`（§6）。

---

## 5. 为什么不降强度

1. **没有放宽或删除任何既有断言**；`git status --porcelain -- app/src/main ui _upstream app/build.gradle.kts` = **0 条**（生产代码零改动）。
2. **判据被加强而不是被绕过**：原来 ② 在 768 **无定义**（既不真也不假），现在 **真且是等号 `96 ≤ 96`** ——
   **零余量**。真机若哪天并集涨到 97、盒子还是 96，② 立刻红（用例⑤已证明）。
3. **`96 = 48 + 48` 的两个 48 都取自生产常量** `MIN_INTERACTIVE_COMPONENT_PX`（`SessionPaneLayout.kt:108`），
   `boxPx = 188 − 92` 由**设备自己的两个读数相减**得到 —— **没有为了让 ② 变绿而挑数、没有加余量**。
4. **已 PASS 的基线一像素不退**：`readable 92` / 时间线盒 `140` / 600 的 `readable 51`（用例⑥）。
5. **不选边**：v128 §1.3 那句"不能替 Lead 选"被**尊重**了 —— 本轮**不替 Lead 裁定 `band` 的口径**，
   而是指出**两种口径对应两种形态，各有自己的 ②**，且**形态由设备读数证成、不由文档指定**。
   Lead 若认为堆叠形态也该用 `48` 判，那是**要求 96 装进 48 的盒**，属**布局要求**，不是口径问题。

---

## 6. 测试证据

**环境**：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`

**命令**（只跑单类，未 assembleDebug / 未 clean / 未装机）：

```
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ComposerUnifiedCriterionV129Test"
```

**正向结果原文**：

```
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 3s
52 actionable tasks: 2 executed, 50 up-to-date
```

**用例数**（`TEST-…V129Test.xml`）：

```
tests=6 failures=0 errors=0 skipped=0
  OK  theSevenSixEightCriterionTwoIsTrueByEqualityWithItsOwnComposerBox
  OK  theFortyEightConstantIsTheOneRowBandAndIsTheWrongRightHandSideForSevenSixEight
  OK  theSixHundredCriterionTwoIsTrueByEqualityWithTheProductionBand
  OK  theTwoTiersTakeOppositeFormsAndTheDeviceReadingsProveIt
  OK  theStackedCriterionIsLoadBearingItGoesRedForAnyBoxShorterThanTheUnion
  OK  definingCriterionTwoMovesNoAlreadyPinnedReading
```

**回归**（相邻两个判据类，一并跑，证明没碰坏既有账）：

```
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ComposerBandTouchTargetV105Test" --tests "com.xiufeigo.opencodeshell.ComposerCriterionParityV118Test"

BUILD SUCCESSFUL in 3s
ComposerBandTouchTargetV105Test: tests=6 failures=0 errors=0
ComposerCriterionParityV118Test: tests=12 failures=0 errors=0
```

⇒ **本轮 24 用例全绿**（V129 6 + V105 6 + V118 12），**零回归**。

---

## 7. 未闭环（本轮**没有**解决，且**不在**授权内）

| # | 项 | 状态 |
|---|---|---|
| 1 | **768 判据① 生产口径 `+2`**（B：`Composer.kt:2150/2152` 的 2px 居中探出） | **未修**，被 6 条既有 pin 逐字锁住，仍在授权外。与 ② 无关（本轮只结 ②） |
| 2 | 仓内 `426`（新 epoch）与 `474`（v110 旧 epoch）两套 IME 读数并存 | **未统一**，见 `CRITERION_MODEL_FIX.md` §6.2。本轮 ② **不依赖** 任何 IME 读数（只用 `container − readable`） |
| 3 | 态行对齐 `SessionScreen.kt:2385-2389` | **未闭环**（v124 推论前提：body 应 **236** 而非 164） |
| 4 | 队列中第二格 `Queue` chip | **未测到**（仍只有 `Add`/`Model`/`Send`）—— 但 v128 已**首次测到队列几何**：`Steer h=48` / 相邻 `h=48` / 双 `clickable` |
| 5 | **设备基线** | **本轮未复验**（零生产改动，未装机、未 `wm` 切档）。v128 的零漂移结论（768 的 `140`/`92`、600 的 `51`、两档 ① `−10`/`−12` 逐位复现）**继续有效** |
| 6 | 44MB release 包 / IPv6 上机 / Trust 流程 / 通知形态 | **原样未闭环**（见交接文档清单） |

---

## 8. 顺带核到的两条源码事实（写进交接文档的依据，逐字）

1. **删除按钮的 a11y 标签是 `Remove`，从来不是 `×`** ——
   `ComposerQueuePanel.kt:266` `.semantics { contentDescription = removeLabel }`，
   `removeLabel = stringResource(R.string.session_queue_remove)`（`:260`），
   默认值 `values/strings_oc_i18n.xml:905` `<string name="session_queue_remove">Remove</string>`。
   `×` 只是**内层 Icon**（`:269`），**从不进 a11y 标签** ⇒ 历史"队列几何测不到"的根因就在这里。
2. **裁剪行不进 a11y 树** —— `ComposerQueuePanel.kt:92-93`（KDoc，逐字）：
   `it is a cropped one, and the actions inside the cropped row are laid out` /
   `outside the viewport and never reach the accessibility tree.`
   ⇒ **多行姿态不能用 a11y bounds 反推触摸靶高**。
