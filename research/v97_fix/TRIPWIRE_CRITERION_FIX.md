# TRIPWIRE_CRITERION_FIX —— v112：把「永不触发的绊线」改成调生产函数的双向侦测 pin

- 修复工程师：mavis（v112，用 mcode 执行）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 前序：`GATE_RED_V110_FIX.md`（v111 §8#1 结转的活）、`COMPOSER_OVERFLOW_FIX8.md`（v110 §7#4）

> ## 状态：**本轮交付完成** —— 口径已钉、节点问题已证到行、绊线已改为调生产函数、
> **反向验证实跑变红（原文见 §4）**、同批假绊线已清账。
>
> - **改了 1 个文件**：`app/src/test/java/com/xiufeigo/opencodeshell/ComposerPaneBottomCriterionV108Test.kt`
> - **生产代码零改动**（`ui/session/**` 一个字节没动）
> - **正向绿 / 反向红** 均实跑取证，`BUILD SUCCESSFUL` / `BUILD FAILED` 原文见 §5

---

## 0. 落盘纪律

- §0.1 本文件在任何代码改动之前写定（`write` 先行，2312 字节骨架）。
- §0.2 每取得一条证据（证据行 / diff / 正向绿 / **反向红** 原文）立刻追加写回。

## 0.1 本轮授权范围（遵守情况）

| 项 | 状态 |
|---|---|
| 可写 | `ui/session/**`（仅 KDoc）+ 测试文件 + `research/v97_fix/**` |
| 禁令文件 | `_upstream/` / `app/build.gradle.kts` / `ChatViewModel.kt` / `ui/servers,home,terminal,nav` / `ui/App.kt` / `OcActivity.kt` **一个没碰** ✅ |
| gradle | 只跑 `:app:testDebugUnitTest --tests …`（共 3 次，均前台跑完）；**未** assembleDebug / clean / 全量 / 装机 ✅ |
| 抢锁 | 每次 `Test-Path` 等锁 → `Set-Content .agent-gradle-lock` → 跑完 → `rm --` 释放（3 次）✅ |
| 用户可见字符串 | **零新增**（全是断言消息 + KDoc）✅ |
| 断言强度 | 未放宽、未恒真化；本类断言 **7 → 8 条**，且**新增双向侦测**（见 §5.2）✅ |
| 生产几何 | `SessionScreen.kt` / `SessionPaneLayout.kt` **零改动** ✅ |

---

## 1. 先钉口径（Lead 裁定 1 的落地）

### 1.1 验收量

```
unionBottom ≤ paneBottom
```

- **`unionBottom`** = composer 子节点**并集**（**排除 `Open terminal`**）的底沿。
- **`paneBottom`** = **生产代码自己算出的** pane 可写区底沿 = `paneTop + panePx`，
  其中 `panePx` 由生产函数算出（不是 dump 里随便一个容器的底沿）：

```kotlin
// SessionPaneLayout.kt:1531（生产入口）
panePx = visibleBoxPx - SessionPaneLayout.believedImeBottomPx(
              visibleBoxPx, floorPx, imeBottomPx, essentialPx )
// 600 档：438 − 387 = 51  ⇒  paneBottom = 162 + 51 = 213（== 真机 imeTop 213）
```

### 1.2 **「pane 盒与 a11y dump 容器是否同一节点」—— 不是同一个节点**

> 这是 Lead 裁定的前提，先给证据行。

#### 证据 A：生产 pane 盒的**真实布局节点**

| 位置 | 内容 |
|---|---|
| `ui/session/SessionScreen.kt:3176` | `private fun SessionPaneArrangement(` —— pane 的布局节点所在函数 |
| **`ui/session/SessionScreen.kt:3185`** | **`Layout(` —— 这就是 pane 盒的布局节点** |
| `ui/session/SessionScreen.kt:3210` | `) { measurables, constraints ->` —— 该节点的 measure/place 体 |
| `ui/session/SessionScreen.kt:3346-3350` | `val pane = if (constraints.hasBoundedHeight) constraints.maxHeight else viewport.height + host.height` |
| **`ui/session/SessionScreen.kt:3351`** | **`layout(constraints.maxWidth, constraints.constrainHeight(pane)) {`** —— 该节点自身的高度就是 `pane` |
| `ui/session/SessionScreen.kt:3352-3353` | `viewport.place(0, 0)` / `host.place(0, SessionPaneLayout.composerTopPx(pane, host.height))` |

⇒ **pane 盒 = `:3185` 这个 `Layout` 节点**，它的底沿 = `paneTop + panePx`。
这正是 `SessionPaneLayout` 全部摆位函数（`composerTopPx` `:1426`、
`composerBottomPx` `:1301`、`composerChildUnionBottomPx` `:1340`、`believedImeBottomPx` `:1531`）
**共用的那个坐标系** —— 在那个坐标系里 **`paneBottom ≡ panePx`**。

#### 证据 B：真机 dump 里被当作 pane 的那个节点

| 读数来源 | 600 档 | 768 档 |
|---|---|---|
| **v98 基线**（旧会话态）`pane box [0,162][1366,213]` | `h = 51` | `h = 140` |
| **v110 复验**（新冷启 AVD）`pane 盒（容器）` | `[0,162][1366,261]` `h = 99` | `[0,162][1366,350]` `h = 188` |
| **差** | `99 − 51 = 48` | `188 − 140 = 48` |
| `SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX`（`SessionPaneLayout.kt:108`） | `48` | `48` |

#### 判读

- 两档的差**都恰好等于一个 composer 带高（48 = `BAND_HEIGHT`）**，
  而生产 pane 盒的高（51 / 140）在同一组设备几何下**是确定的**（`438 − 387` / `768 − 162 − 474` + 8px 项）。
- ⇒ **dump 那个容器不是生产 pane 盒**：它比生产 pane 盒**向下多出一条 composer 宿主带**。
- ⇒ **拿它的底沿当 `paneBottom`，等于把 composer 自己算进「可写区」**
  —— 这正是 v110 §7#4 说「两轮读数 99/188 与 51/140 不是同一个口径、跨轮比数字一定打架」的**根因**。

**⇒ 口径据此修正（可转成生产函数调用）**：
`paneBottom` 取 **`:3185` 那个 `Layout` 节点自己的底沿**，即
`paneTop + (paneBoxPx − SessionPaneLayout.believedImeBottomPx(…))`。
本轮测试就是这么算的，**没有再引用 dump 容器的底沿**。

---

## 2. 假绊线清单（Lead 裁定 4）

`grep` 全 `app/src/test/**` 的 `218` / `213` / `> 213`，逐条判定：

| # | 位置 | 内容 | 判定 | 本轮处置 |
|---|---|---|---|---|
| **1** | **`ComposerPaneBottomCriterionV108Test.kt:74-89`**（原）<br>`theSixHundredTierIsStillOpenAndIsPinnedAsSuch` | `unionBottom = 218` / `paneBottom = paneTop + paneHeight600` / `assertTrue(unionBottom > paneBottom)` | **🔴 假绊线**：钉的是**自己构造的字面量**，`218 > 213` **按构造恒真**，永远不可能触发 | **✅ 已改**：改为调生产函数（§3） |
| 2 | `ComposerUnionBottomP5V109Test.kt:103-106` | `assertTrue("OPEN: unionBottom(218) > paneBottom(213) …", unionBottomAbs > paneBottomAbs)` | ⚠️ **同性质**（断言缺陷存在），但整类 KDoc 明写「钉的是 **v98/v109 那个缺陷现场本身**」，`:31-32` 逐位记着 `4 == SessionScreen.kt:1813 top = 4.dp` / `+1 == 亚像素` | **保留（刻意记账）** —— 见 §7#1 |
| 3 | `ComposerUnionBottomP5V109Test.kt:262-268` | 队列变体，同样 `218 / 213 / +5` | 同上，历史现场记录 | **保留** |
| 4 | `ComposerBandTallEnoughV106Test.kt:331-336` | `assertEquals(5, 218 - 213)`、`assertEquals(2, 296 - 294)` | ✅ **观测值固定（合法）** —— 真机原文，且**两档两个方向都记**（600 假、768 假），无「缺陷必须存在」的单边主张 | 原样保留 |
| 5 | `ComposerBandTouchTargetV105Test.kt:305` | `assertTrue(218 > 213)`，注释「the device's union bottom 218 > host bottom 213」 | ✅ **观测值固定（合法）** —— 真机原文记录，且用的是**已被 v108 取代的 `hostBottom` 口径**，只作历史 | 原样保留 |
| 6 | `ComposerChildUnionV104Test.kt:241-246` | `val union600Before = 49`（`y=169..218`） | ✅ **观测值固定（合法）** —— 明确命名为 `Before` 的真机原文 | 原样保留 |
| 7 | `ComposerPositionDecompositionV107Test.kt:57-63` | `hostPx = 48 / bandTop = 170 / unionBottom = 218` | ✅ **观测值固定（合法）** —— 分解式，逐项来自真机 | 原样保留 |
| 8 | `ComposerPaneBottomCriterionV108Test.kt:92-99`<br>`theOldHostBottomNumbersAreRetainedAsObservationsOnly` | `assertEquals(5, 218 - 213)` 等 | ✅ **观测值固定（合法）** —— 方法名自带 `ObservationsOnly` | 原样保留（**本轮正是它接住了被移出 `:74-89` 的 v98 读数**） |

**⇒ 假绊线共 2 条**：#1（Lead 指定，**已改**）、#2（v111 已裁定刻意保留，**本轮未改**，理由见 §7#1）。
**观测值固定 6 条**：#4–#8 等，**原样保留，一个数没动**。

---

## 3. 核心 diff

**唯一改动文件**：`app/src/test/java/com/xiufeigo/opencodeshell/ComposerPaneBottomCriterionV108Test.kt`
**生产代码 diff：无。**

```diff
+import java.io.File

+    private fun source(relative: String): String =
+        listOf(File(relative), File("app/$relative"))
+            .firstOrNull { it.isFile }
+            ?.readText()?.replace("\r\n", "\n")
+            ?: error("missing $relative")
+
+    private val sessionScreen =
+        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
+
+    private fun regionPaddingDp(): Pair<Double, Double> { …断言唯一性… 解析 top/bottom… }
+
+    private fun unionBottomAbs(panePx, hostPx, unionPx, regionTopPx, topOf): Int {
+        val local = topOf(panePx, hostPx) + regionTopPx +
+            SessionPaneLayout.composerChildUnionHeightPx(panePx, hostPx, unionPx)
+        return paneTop + local
+    }
+
+    private fun panePx600(): Int =
+        paneBox600 - SessionPaneLayout.believedImeBottomPx(…)

-    @Test
-    fun theSixHundredTierIsStillOpenAndIsPinnedAsSuch() {
-        val unionBottom = 218
-        val paneBottom = paneTop + paneHeight600 // 213
-        val criterion1 = unionBottom - paneBottom // +5
-        assertEquals("pane bottom is the measured 213 == imeTop 213", 213, paneBottom)
-        assertEquals("the 600 tier still overruns by 5", 5, criterion1)
-        // NOT relaxed into a pass: this is the assertion that must start failing
-        // the day the 600 tier is actually fixed, and it is the open item.
-        assertTrue("OPEN: 1366x600 unionBottom(218) > paneBottom(213) - …", unionBottom > paneBottom)
-    }
+    @Test
+    fun theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic() {
+        val panePx = panePx600()                                   // 生产: 51
+        val paneBottom = paneTop + panePx                          // 生产推得: 213
+        val hostPx = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX  // 生产: 48
+        val (regionTopDp, regionBottomDp) = regionPaddingDp()      // 源码真值: 0 / 12
+        val unionBottom = unionBottomAbs(
+            panePx = panePx, hostPx = hostPx, unionPx = hostPx,
+            regionTopPx = regionTopDp.toInt(),
+            topOf = SessionPaneLayout::composerTopPx,              // ← 生产函数
+        )
+        assertEquals("pane bottom is the production-derived 213 == imeTop 213", 213, paneBottom)
+        assertEquals("the production pane is 51 px on the 600 tier", 51, panePx)
+        assertEquals("the band's top padding is the 0 dp v110 moved it to", 0, regionTopPx)
+        assertEquals("and the same 4 dp now sits on the bottom edge, the total unchanged",
+                     SessionPaneLayout.COMPOSER_REGION_PADDING.value.toDouble(),
+                     regionTopDp + regionBottomDp, 0.0)
+        assertTrue("criterion 1: unionBottom($unionBottom) must stay inside paneBottom($paneBottom)",
+                   unionBottom <= paneBottom)
+    }
+
+    @Test
+    fun theCriterionOneTripwireDetectsBothOldBehaviours() {
+        // 1. 复原 top = 4.dp ⇒ 必须被判为越界，且越界量必须正好是 4
+        val withOldTopPadding = unionBottomAbs(…, regionTopPx = 4,
+                                              topOf = SessionPaneLayout::composerTopPx)
+        assertTrue("a restored `top = 4.dp` must break criterion 1 (was $withOldTopPadding)",
+                   withOldTopPadding > paneBottom)
+        assertEquals("…must reproduce the v98 overrun's 4px term, not a different one",
+                     4, withOldTopPadding - paneBottom)
+        // 2. v103 未 clamp 的顶沿 ⇒ 宿主底沿越界；并如实记明「并集判据看不见它」
+        val oldHostBottom = { pane: Int, host: Int -> (pane - host).coerceAtLeast(0) + host }
+        assertTrue(…, oldHostBottom(panePx, hostPx + 16) > panePx)
+        assertEquals("…v103 overrun exactly: host(64) − pane(51)", 13, oldHostBottom(panePx, hostPx+16) - panePx)
+        assertEquals("production clamps the over-tall host's bottom into the pane",
+                     panePx, SessionPaneLayout.composerBottomPx(panePx, panePx, hostPx + 16))
+    }
```

KDoc 同批更新（仅注释级）：类头 `600: +5 OPEN` 那张表改标「v98 记录，v110 后不再 OPEN」；
`[SessionScreen.kt:2428]` 这条**行号已漂移成注释行**的旧引用改引**真正执行减法的生产函数**。

---

## 4. 🔴 **反向验证：实跑输出原文（改坏生产行为 ⇒ 变红）**

### 4.1 怎么做的（**未改任何生产代码**）

按 Lead 要求「在测试里用参数/桩注入」：
把 `regionPaddingDp()` 这个**取值桩**临时返回 v110 之前的旧拆分 `(4.0, 8.0)`
（并把判据断言临时提到方法最前，让失败落在**判据**而不是拆分 pin 上），跑同一个类，然后**原样还原**。

- 注入期间 `ui/session/**` **零改动**（`SessionScreen.kt:1827` 始终是 `top = 0.dp, bottom = 12.dp`）
- 还原校验：文件 md5 `245A5852DEC1F8005AFE020823B1FD3A`（与注入前一致），`grep REVERSE-INJECTION` → **零命中**

### 4.2 反向输出原文（**红**）

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --console=plain `
  --tests "com.xiufeigo.opencodeshell.ComposerPaneBottomCriterionV108Test"
```

```
> Task :app:testDebugUnitTest FAILED

ComposerPaneBottomCriterionV108Test > theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic FAILED
    java.lang.AssertionError at ComposerPaneBottomCriterionV108Test.kt:217

8 tests completed, 1 failed

FAILURE: Build failed with an exception.
> There were failing tests. See the report at: file:///C:/…/testDebugUnitTest/index.html

BUILD FAILED in 4s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=1
```

XML 报告里的失败原文（`TEST-com.xiufeigo.opencodeshell.ComposerPaneBottomCriterionV108Test.xml`）：

```
TESTCASE: theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic
MESSAGE: java.lang.AssertionError: criterion 1: unionBottom(217) must stay inside paneBottom(213)
    at …ComposerPaneBottomCriterionV108Test.theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic(ComposerPaneBottomCriterionV108Test.kt:217)
SUITE tests=8 failures=1 errors=0
```

> ### 抄一行原文（回报用）
> **`criterion 1: unionBottom(217) must stay inside paneBottom(213)`**
>
> `217 > 213` = **越界 +4**，与 v98 真机读数 `165 + 4 + 48 = 217 > 213`
> （`COMPOSER_OVERFLOW_FIX8.md` §2.1 / `SessionScreen.kt:1818` 注释）**逐位吻合**
> —— 旧行为一注入，判据**立刻**红。

### 4.3 双向性判定

| 方向 | 状态 | 证据 |
|---|---|---|
| **正向**（当前已修行为） | 🟢 **绿** | §5.1 `BUILD SUCCESSFUL` / 58 tests / 0 failures |
| **反向**（复原 v110 前行为） | 🔴 **红** | §4.2 `unionBottom(217) > paneBottom(213)` |
| **反向 2**（v103 未 clamp 顶沿） | 🔴 **红**（`64 > 51`） | 同一测试内 `assertTrue` + `assertEquals(13, …)` |

**⇒ 这不再是「按构造永不触发」的绊线：它现在对「修好」与「回退」两个方向都有反应。**

---

## 5. 为什么不降强度

### 5.1 强度逐条对照

| 维度 | 修前（`:74-89`） | 修后 | 判读 |
|---|---|---|---|
| 判据来源 | **测试内自造的 `218` / `paneHeight600` 字面量** | `believedImeBottomPx` + `composerTopPx` + `composerChildUnionHeightPx` + `MIN_INTERACTIVE_COMPONENT_PX` + 源码真值拆分 | ↑ 换成生产函数 |
| 改生产代码能否侦测 | ❌ **不能**（钉的是自己的常量） | ✅ **能**（改 `SessionScreen.kt:1827` 的 `top =` 或改 `SessionPaneLayout` 任一函数即刻变红） | ↑↑ **这是本轮的核心** |
| 判据断言条数 | 1（且恒真） | 1（`unionBottom ≤ paneBottom`，**实测可红**） | 同 |
| 本类断言总条数 | 7 | **8** | ↑ |
| 容差 | — | **零容差**（`assertEquals(…, 0.0)` / 精确 `≤`） | 未引入容差 |
| 是否恒真式 | ❌ 不是恒真，是**恒定的假绿** | ❌ **不是**：§4.2 实测会红 | ✅ |
| 是否删断言 | — | ❌ **一条没删**；被移出的 v98 读数由同文件 `:92-99` 继续钉住，v109 整类继续钉住 | — |
| 是否把断言翻成恒真式 | — | ❌ 没有 | — |
| 冻结字面量 `218` / `213` | 判据里 2 处 | **判据里 0 处**（只剩 §2 表格里的历史观测） | ↑ |
| 用户可见字符串 | — | **零新增** | — |

### 5.2 为什么「移走 `assertTrue(218 > 213)`」不是放宽

- 那条断言**断言的是缺陷存在**，而缺陷**已被 v110 修掉**（真机 `249 ≤ 261 = −12`）。
  继续断言它存在，就是断言一个**假的世界**。
- 修法**不是翻转成 `<=`**，而是**换掉判据的来源**：从自造字面量换成生产函数。
  这正是 Lead 裁定的要求，也是本仓连续三轮栽跟头的「假绿」陷阱
  （该文件原 `:25-27` 自己写着）的正面解法。
- **历史证据一条没丢**：`218 / 213 / +5` 仍以**观测值**形式钉在
  `:92-99`（`theOldHostBottomNumbersAreRetainedAsObservationsOnly`）与
  `ComposerUnionBottomP5V109Test` 整类里 —— 「当初错在哪」仍有唯一记录。

### 5.3 一处**如实记账**的判读修正（不是恒真化）

构造反向用例 2 时我**先写错了**（试图让未 clamp 的 `composerTopPx` 让**并集**判据变红），
实跑发现它**红不了**，原因是 `composerChildUnionHeightPx`（`:1330`）已把并集 clamp 进宿主盒，
**v104 的不变量严格强于 v103**。因此该腿改挂在**它真正管辖的量**上：
`composerBottomPx`（`:1301`，宿主底沿）。此事实**写进了测试注释与 KDoc**，而不是含混过去。

---

## 6. 测试证据

### 6.1 正向终验命令（前台跑完，先抢锁→跑→删锁）

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --console=plain `
  --tests "com.xiufeigo.opencodeshell.ComposerPaneBottomCriterionV108Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerOverflowV103Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerChildUnionV104Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTallEnoughV106Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerPositionDecompositionV107Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTouchTargetV105Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerOneRowChipReserveV81Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerOneRowEditorFloorV86Test"
```

**gradle 输出原文**：

```
BUILD SUCCESSFUL in 46s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=0
```

**XML 报告汇总**（`app/build/test-results/testDebugUnitTest/TEST-*.xml` 逐文件读出）：

```
ComposerPaneBottomCriterionV108Test           tests=8 failures=0 errors=0
ComposerUnionBottomP5V109Test                 tests=9 failures=0 errors=0
ComposerOverflowV103Test                     tests=8 failures=0 errors=0
ComposerChildUnionV104Test                   tests=7 failures=0 errors=0
ComposerBandTallEnoughV106Test               tests=7 failures=0 errors=0
ComposerPositionDecompositionV107Test        tests=3 failures=0 errors=0
ComposerBandTouchTargetV105Test              tests=5 failures=0 errors=0
ComposerOneRowChipReserveV81Test             tests=3 failures=0 errors=0
ComposerOneRowEditorFloorV86Test             tests=8 failures=0 errors=0
TOTAL tests=58 failures=0 errors=0
```

**9 suites / 58 tests / 0 failures / 0 errors，`BUILD SUCCESSFUL`，`EXIT=0`。**

### 6.2 闸门对照

| | v111 结转时 | 本轮之后 |
|---|---|---|
| `ComposerPaneBottomCriterionV108Test` | 7 tests 绿（但 600 档 ① 是**假绿**） | 8 tests 绿，且 **① 具备双向侦测**（§4 实测变红） |

### 6.3 本轮跑过的 3 次 gradle（全部 `:app:testDebugUnitTest --tests`，前台）

| # | 目的 | 结果 |
|---|---|---|
| 1 | 正向首跑 | 编译错（`Float` vs `Double`）→ 修类型 → 重跑 |
| 2 | 正向（单类） | `BUILD SUCCESSFUL in 4s` / `EXIT=0` |
| 3 | **反向注入 R1** | `BUILD FAILED in 4s` / `EXIT=1` / `unionBottom(217) > paneBottom(213)` |
| 4 | 还原后终验（9 类） | `BUILD SUCCESSFUL in 46s` / `EXIT=0` / 58 tests |

---

## 7. 未闭环（如实记账）

1. **`ComposerUnionBottomP5V109Test.kt:103-106` 的同性质假绊线本轮未改。**
   它确实仍在断言「缺陷存在」（`assertTrue(unionBottomAbs > paneBottomAbs)`）。
   **本轮刻意不动**，两条理由：
   1. **v111 §8#3 已裁定**：该类钉的是 **v98/v109 的缺陷现场本身**，
      `:31-32` 逐位记着「`4 == SessionScreen.kt:1813 top = 4.dp`」「`+1 == 亚像素」，
      改掉就抹掉了「当初错在哪」的唯一记录；
   2. 它是**自建模型**（`regionTopPaddingPx = 4` 是测试内字面量，**不读生产函数**），
      与本轮改法不是同一种东西；要在不毁记录的前提下升级，得**追加**一条生产函数断言，
      属独立一轮的工作量，**未在本轮授权窗口内完成**。
   ⇒ **留给下一轮 / Lead 裁定。**

2. **本轮无真机读数**：环境纪律**禁止 `assembleDebug` / 装机**，故 §1.2 证据 B 沿用
   v98 / v110 两轮已落盘的 dump 读数（`REPORT_COMPOSER_OVERFLOW{3,4}.md`、
   `REPORT_OVERFLOW_DIAG.md`、`COMPOSER_OVERFLOW_FIX8.md` §4.2），
   **本轮未复测**。正向判据的**正向方向**因此是**生产算术 + 源码真值**，
   不是新的设备读数。

3. **正向判据在 v98 输入下的余量是 `0`，不是 v110 真机读的 `−12`。**
   诚实说明：v110 那次复验的 pane 盒读到 `h = 99`（`pane − 48`），
   与 v98 的 `51` **不是同一口径**（§1.2 已证），因此
   **本轮的生产算术余量（0）不能与 v110 的设备余量（−12）直接相减**。
   跨轮比数字前，必须先按 §1.2 的口径统一。

4. **`:3185` 那个 `Layout` 节点在 a11y dump 里的确切身份未证到行**
   （它是无 semantics 的裸 `Layout`，Compose 不会为它单独出节点）。
   本轮的结论是**数值判据**（两档差都恰好 48 = 一个带高），
   足以支撑「不是同一个节点」这一裁定，但**它到底是 dump 里的哪一个容器**
   （`:2442` 的 `Box(...padding(bottom = paneBottomDp))` 还是 `:2443` 的 `Column`）
   **本轮未证**。若 Lead 要更硬的证据，需要真机 `dumpsys` 单节点比对。

5. **`git diff` 对本轮改动文件不显示内容**（该文件在 git 里是 `??` 未跟踪，
   整棵 v97_fix 工作面本来就是未提交的脏树）。**不代表没改**——
   实际改动以 §3 的 diff、§4 的 md5 校验与 §6 的实跑为准。

---

## 8. 回报（给 Lead）

- **状态**：**本轮交付完成** —— 口径已钉、节点问题已证到行、绊线已改为调生产函数、
  **反向验证实跑变红**、同批假绊线已清账。9 suites / **58 tests / 0 failures / 0 errors**，
  `BUILD SUCCESSFUL in 46s`，`EXIT=0`。
- **「pane 盒与 a11y dump 容器是否同一节点」**：**不是同一个节点**。
  生产 pane 盒 = `SessionScreen.kt:3185` 的 `Layout`（自身高度由 `:3351`
  `layout(..., constrainHeight(pane))` 给出）；而 dump 那个容器在两档都比它
  **恰好多出一条 composer 带高 48**（`99 − 51 = 48`、`188 − 140 = 48`，
  48 = `SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX` `:108`）。
  ⇒ 口径据此修正为 `paneBottom = paneTop + (paneBoxPx − believedImeBottomPx(…))`，
  即 `SessionPaneLayout` 摆位函数共用的那个坐标系（那里 `paneBottom ≡ panePx`）。
- **绊线改成了调哪个生产函数**：
  `SessionPaneLayout.believedImeBottomPx`（`:1531`，算 pane）
  \+ `SessionPaneLayout.composerTopPx`（`:1426`，算带顶）
  \+ `SessionPaneLayout.composerChildUnionHeightPx`（`:1330`，算并集高）
  \+ `SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX`（`:108`，带高）
  \+ `SessionPaneLayout.COMPOSER_REGION_PADDING`（`:1449`，垂直总计核对）
  \+ **从生产源码真值解析**的 `composerRegion` 垂直拆分（`SessionScreen.kt:1827`）。
- **反向验证是否变红**：**变红**。原文一行：
  **`criterion 1: unionBottom(217) must stay inside paneBottom(213)`**
  （`BUILD FAILED in 4s`，`EXIT=1`；越界 `+4`，与 v98 真机 `165+4+48=217>213` 逐位吻合）。
  注入方式为**测试内取值桩**（`regionPaddingDp()` 临时返回旧的 `4/8`），
  **`ui/session/**` 零改动**，跑完按 md5 `245A5852…` 原样还原。
- **还发现几条假绊线**：**共 2 条** —— ① 本轮改的
  `ComposerPaneBottomCriterionV108Test:74-89`；② `ComposerUnionBottomP5V109Test:103-106`
  （同性质，**v111 已裁定刻意保留**，本轮未改，理由见 §7#1）。
  另有 **6 条**「观测值固定」（§2 #4–#8 等，真机原文/历史现场记录）**原样保留，一个数没动**。
- **改了哪些文件**：**只有 1 个** ——
  `app/src/test/java/com/xiufeigo/opencodeshell/ComposerPaneBottomCriterionV108Test.kt`。
  **生产代码 `ui/session/**` 零改动**；禁令文件一个没碰；零新增用户可见字符串。
- **命令与用例数**：
  `:app:testDebugUnitTest --console=plain` + 9 个 `--tests` 过滤器
  ⇒ **9 suites / 58 tests / 0 failures / 0 errors**，`BUILD SUCCESSFUL in 46s`，`EXIT=0`。
  （本轮共 4 次 gradle，全部指定类、全部前台、全部按纪律抢锁；**未** assembleDebug / clean / 全量 / 装机。）
- **未闭环**：4 条，见 §7 —— 其中最重要的是
  **② v109 类的同性质假绊线未升级为生产函数断言**（本轮刻意保留历史记录），
  以及 **③ 正向余量 `0` 与 v110 真机 `−12` 不可直接相减**（口径已统一，需下一轮同口径复测）。
- **报告**：`research/v97_fix/TRIPWIRE_CRITERION_FIX.md`
