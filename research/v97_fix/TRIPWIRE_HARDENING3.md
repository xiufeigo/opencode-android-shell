# TRIPWIRE_HARDENING3.md —— v117：裁定落地 —— `:364` 的 `after768` 并入**必需**指纹集合

> 任务：补掉 v116 §7#2 自陈的最后一条缺口。
> 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
> 写权限：仅测试文件 + `research/v97_fix/**`。**生产代码零改动。**

---

## 0. 状态

| 步骤 | 状态 |
|---|---|
| 读 v116 报告、复核行号 | ✅ |
| 落地裁定（两档都进必需集合） | ✅ |
| 四条变异实跑 | ✅ 5 条（含 ④ 的两档各一次）全红 |
| 回归跑 | ✅ 53 tests / 0 failures / BUILD SUCCESSFUL |

---

## 1. 缺口与裁定

### 1.1 v116 的原话（如实引用，§7#2）

> **新发现的缺口：`after768` 那条 `Frame(768, 466, …)` 没有被单独 pin。**
> ……它目前只被"存在至少一条别的 Frame 调用"间接覆盖，不在指纹集合内 ——
> **同样的"加新的删旧的"手法对它仍然有效**。本轮未补，因为补它需要先判定
> 哪一条才是 R5 该依赖的那条（`:351` 还是 `:364`），属**判据归属**问题。

### 1.2 Lead 裁定

**两条都要进指纹集合（不得二选一）。**

| 档 | 源码行 | 角色 | 记录性读数 |
|---|---|---|---|
| **600 档** | `V106:351` | **缺陷现场 / 已修格**（"修好了"） | `:342-345` `5, 218 - 213`（+5） |
| **768 档** | `V106:364` | **不得回退格**（"没被弄坏"） | `:347` `2, 296 - 294`（+2） |

**理由（与 Lead 一致）**：composer 那次修复的契约**同时覆盖两档** ——
600 档是"修好了"、768 档是"没被弄坏"；**只钉一档就等于放掉另一半契约**，
这正是本仓反复踩的"名义活、实质死"。⇒ **两档的调用都必须是指纹集合的必需项，任一缺失即违规。**

### 1.3 我采纳并写进代码的判据归属结论

R5 的判据依赖 **`:351` ∪ `:364` 两条**（不是任选其一）。
两者**不可互相顶替**，由 `neitherTierSubstitutesForTheOther` 在**真实源码**上双向证明。

---

## 2. 裁定落地 diff

改动**只在** `app/src/test/java/com/xiufeigo/opencodeshell/ComposerCallFingerprintV116Test.kt`，
`git diff --stat` = `1 file changed, 304 insertions(+), 7 deletions(-)`。
**`ComposerBandTallEnoughV106Test.kt` 的 `git diff --numstat` 输出为空 ⇒ 零改动。**

### 2.1 必需指纹集合：`R5_CALLS` 从 1 条变 2 条

```diff
+    /** 600 档（`V106:351`）—— 缺陷现场 / 已修格：`unionBottom 218 > 213` 那档。 */
+    const val R5_CALL_600 = "600, 387, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX"
+
+    /** 768 档（`V106:364`）—— 不得回退格：`296 > 294` 那档。 */
+    const val R5_CALL_768 = "768, 466, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX"
+
     val R5_CALLS = listOf(
         CallFingerprint(
             scope = "fun $R5_METHOD",
             callee = "Frame",
             args = listOf("600", "387", "rows=0", "oneRow=true", "bandHeightPx=BAND_AFTER_PX"),
             minProductionArgs = 1,
         ),
+        // ← v117 新增：768 档同为**必需项**，与 600 档并列，不可互换
+        CallFingerprint(
+            scope = "fun $R5_METHOD",
+            callee = "Frame",
+            args = listOf("768", "466", "rows=0", "oneRow=true", "bandHeightPx=BAND_AFTER_PX"),
+            minProductionArgs = 1,
+        ),
     )
```

**判据本体一行未改**：仍是 v116 的**集合包含**
（`found.none { it == pin.args } ⇒ fingerprint-missing`）。
本轮**没有新增判据种类**，只把"必需集合"从 1 条补成 2 条 —— 这正是任务要求的落地形态。

### 2.2 为什么 `:372` 那条 `rows = rows` **不**进必需集合（防止误伤，也说明强度没降）

`V106:372` 的 `Frame(768, 466, rows = rows, …)` 是**队列 0/2/11 的参数化循环**，
不是某一档的定格读数。把它 pin 死会让"改队列档位列表"这种**无害**改动被迫改 pin。
判据强度**不因此下降**：两档的定格调用一旦被删，§4 的五条变异**全部立刻报红**，
其中变异③（加新删旧）特意保留了 `:372` 这条 `rows=rows` 调用在场 ——
**它救不了任何一条 pin**（见 §4.3 的 `found` 列表里 `768, 466, rows=rows` 赫然在列，判据照样红）。

### 2.3 新增用例（7 条，V116 用例数 12 → 19）

| 用例 | 作用 |
|---|---|
| `bothTiersAreRequiredAndAreDistinctFingerprints` | **裁定本身的结构断言**：恰好 2 条、互不相同、args 分别是 600/768 |
| `deletingTheSevenSixEightCallIsReported` | 删 768 ⇒ 恰好 1 条违规，且**指名 768** |
| `deletingBothTiersIsReportedOncePerTier` | 两档同删 ⇒ 恰好 2 条违规，一档一条 |
| `addingANewCallAndDeletingTheSevenSixEightOneIsStillReported` | 768 档"加新删旧"，且断言 600 调用**仍在场**（证明 v115 此刻是绿的） |
| `hardcodingTheSevenSixEightCallsArgumentIsReported` | 768 档实参换**数值等价字面量 48**，并断言 600 pin **仍通过**（红灯不是连带） |
| `neitherTierSubstitutesForTheOther` | **双向**：只留 600 ⇒ 768 pin 红；只留 768 ⇒ 600 pin 红 |
| `aCommentAdvertisingTheSevenSixEightCallDoesNotSatisfyThePin` | 注释里写 `Frame(768, 466, …)` 买不到指纹分 |

另有 v116 的两条旧用例被**收紧**（不放松）：`deletingThePinnedCallIsReported` 与
`addingANewCallAndDeletingTheOldOneIsStillReported` 现在断言 `violations.size == 1`
**且指名 600 档** —— 因为集合从 1 条变 2 条后，"恰好一条违规且是这一档"才是更强的断言。

### 2.4 恒真守卫（非自欺）

`everyPinIsActuallyFoundInTheRealSource`（v116 已有，**循环 `R5_CALLS`，自动覆盖 2 条**）
在 §4 每一次被污染的源码上都会红（见各轮 `FAILED_TEST everyPinIsActuallyFoundInTheRealSource`），
说明它确实在扫真实文件；正向时全绿。
`bothTiersAreRequiredAndAreDistinctFingerprints` 则保证 pin 集合不会悄悄退回"一档"。

---

## 3. 源码行号对照表

### 3.1 R5 宿主与两档必需调用（`ComposerBandTallEnoughV106Test.kt`）

| 角色 | 行号 | 内容 | 是否必需项 |
|---|---|---|---|
| R5 宿主方法 | `:322-323` | `@Test` / `fun theFivePixelOverflowIsReproducedOnTheDeviceReadingsAndGoneOnTheNewBand()` | — |
| 记录性 fixture（600 档 band/②） | `:326`、`:327-330` | `44, BAND_BEFORE_PX` / `4, TOUCH_TARGET_PX - BAND_BEFORE_PX` | ❌ 记录性 |
| 记账轨裁定注释 | `:331-341` | v114/v115 关于 `5, 218 - 213` 为何合法固定的推理 | ❌ 注释 |
| 记录性 fixture（600 档 ①） | `:342-345` | `"真机 600 档 ①…⇒ +5"`, `5, 218 - 213` | ❌ 记录性 |
| 记录性 fixture（768 档 ①） | `:347` | `"真机 768 档 ①：296 > 294 ⇒ +2"`, `2, 296 - 294` | ❌ 记录性 |
| 记录性 fixture（768 档 ②） | `:348` | `"真机 768 档 ②：48 > 44 ⇒ +4"` | ❌ 记录性 |
| **修后段起** | `:350` | `// ---- 修后：同一帧，band = 48，模型逐位 ----` | — |
| **必需调用 ① 600 档** | **`:351`** | `val after600 = Frame(600, 387, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)` | ✅ **必需** |
| 600 档活断言 | `:352-362` | `pane/hostPx/bandPx/unionPx/hostBottom/unionBottom` | ❌ 断言 |
| **必需调用 ② 768 档** | **`:364`** | `val after768 = Frame(768, 466, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)` | ✅ **必需（v117 新增）** |
| 768 档活断言 | `:365-368` | `pane/unionBottom/unionPx` + `unionBottom <= hostBottom` | ❌ 断言 |
| 队列参数化循环 | `:371-377`（调用在 `:372`） | `for (rows in listOf(0, 2, 11))` → `Frame(768, 466, rows = rows, …)` | ❌ 有意不 pin（§2.2） |

### 3.2 `Frame` 桥（两档共用的几何底座）

| 角色 | 行号 | 内容 |
|---|---|---|
| 类定义 | `:122-189` | `private class Frame(windowPx, imeBottomPx, rows, oneRow, bandHeightPx, paneOverride)` |
| `val pane` | `:135` | `(paneOverride ?: (windowPx - CHROME_PX - imeBottomPx)).coerceAtLeast(0)` |
| `val hostPx` | `:141-144` | `SessionPaneLayout.composerHostHeightPx(panePx = pane, ceilingPx = if (oneRow) bandHeightPx else STACKED_NATURAL_PX)` |
| `hostTop` | `:146` | `SessionPaneLayout.composerTopPx(pane = pane, composer = hostPx)` |
| `val bandPx` | `:150` | `minOf(bandHeightPx, hostPx)` |
| `val touchTarget` | `:153-156` | `SessionPaneLayout.composerBandTouchTargetDp(oneRow = oneRow, band = bandPx.dp)` |

### 3.3 常量（`minProductionArgs = 1` 为什么 load-bearing）

| 常量 | 行号 | 值 |
|---|---|---|
| `BAND_BEFORE_PX` | `:90` | 44 |
| `BAND_AFTER_PX` | `:93` | **48** |
| `TOUCH_TARGET_PX` | `:96` | 48 |
| `STACKED_NATURAL_PX` | `:105` | 94 |

**`BAND_AFTER_PX == 48`** ⇒ 把 `bandHeightPx = BAND_AFTER_PX` 换成 `bandHeightPx = 48`
是**数值完全等价**的变异，V106 自己**照样全绿**。两档的 `minProductionArgs = 1`
各自独立地承担这件事：把任何一档的 `BAND_AFTER_PX` 换成 `48`，那一档就变成五实参全字面量。

### 3.4 口径统一说明

| 项 | 旧记法 | 本轮统一 |
|---|---|---|
| R5 侦测力来源 | v115 报告 `:347`（与其正文 `:348-358` 自相矛盾）；v116 已按源码改为 `:351` | **R5 = `:351` + `:364`（两档）**；`:347` 是**记录性 fixture**（真机读数），**不是**判据依赖 |
| R6 侦测力来源 | `ComposerUnionBottomP5V109Test.kt:201` | 不变（`val hostTop768 = SessionPaneLayout.composerTopPx(paneHeight768, 94)`；`paneHeight768` 定义在 `:83`） |
| "必需调用"与"记录性 fixture" | v114 起混在同一方法里，靠注释说明 | 本轮在 `ComposerFingerprintSpecs` 的 KDoc 里**写清了两者的分界**，并给出上表的行号对照 |

---

## 4. 反向验证（真跑，输出原文）

**环境**：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`；
每轮都**真改真源码文件** `V106`，跑完立刻从 `research/v97_fix/_v117_backup/V106.bak` 还原并**核对 SHA256**。
`V106` 原始哈希 `291B3D95CAA7028FFF4D5D4E1330B1D7A47464E085263E80D124C47A5094E004`，
**五轮之后仍然逐位一致**（`matches_original=True` × 5）。

**每轮固定跑三个类**（V116 / v115 / V106），
目的是每次都同时打出**v115 与 V106 的成绩单** —— 它们若仍绿，才证明"只有新判据看得见"。

---

### 4.1 变异①：删掉 `:351`（600 档）

**注入**：删 `V106:351-362` 共 12 行（那条 `Frame(600, 387, …)` 及其全部下游断言）。
```
M1 after600_remaining=0 after768_remaining=1
```
**跑**：
```
ComposerCallFingerprintV116Test > theRealSourcesContainEveryPinnedCallFingerprint FAILED
ComposerCallFingerprintV116Test > everyPinIsActuallyFoundInTheRealSource FAILED
ComposerCallFingerprintV116Test > deletingThePinnedCallIsReported FAILED
ComposerCallFingerprintV116Test > deletingTheSevenSixEightCallIsReported FAILED
ComposerCallFingerprintV116Test > addingANewCallAndDeletingTheOldOneIsStillReported FAILED
ComposerCallFingerprintV116Test > addingANewCallAndDeletingTheSevenSixEightOneIsStillReported FAILED
ComposerCallFingerprintV116Test > hardcodingThePinnedCallArgumentIsReported FAILED
ComposerCallFingerprintV116Test > hardcodingTheSevenSixEightCallsArgumentIsReported FAILED
35 tests completed, 8 failed
BUILD FAILED in 3s
```
| 类 | tests | failures |
|---|---|---|
| `ComposerCallFingerprintV116Test` | 19 | **8** |
| `ComposerTripwireIntegrityV115Test` | 9 | **0** ← v115 绿 |
| `ComposerBandTallEnoughV106Test` | 7 | **0** ← V106 绿 |

**决定性一行原文**：
> `R5: [fun theFivePixelOverflowIsReproducedOnTheDeviceReadingsAndGoneOnTheNewBand { Frame(600, 387, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX) }/fingerprint-missing: no call in 'fun …' matches this exact fingerprint; found Frame at [768, 466, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX, 768, 466,`

抄最短一行：
> `Frame(600, 387, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX) }/fingerprint-missing: no call in 'fun …' matches this exact fingerprint`

**`found` 里赫然列着两条完好的 768 调用，仍然判红** —— 这就是"不数数"。

---

### 4.2 变异②：删掉 `:364`（768 档）—— **本轮的核心缺口**

**注入**：删 `V106:364-368` 共 5 行（那条 `Frame(768, 466, …)` 及其 4 条断言）。
```
M2 after600_remaining=1 after768_remaining=0
```
**跑**：
```
ComposerCallFingerprintV116Test > theRealSourcesContainEveryPinnedCallFingerprint FAILED
ComposerCallFingerprintV116Test > deletingTheSevenSixEightCallIsReported FAILED
ComposerCallFingerprintV116Test > everyPinIsActuallyFoundInTheRealSource FAILED
35 tests completed, 7 failed
BUILD FAILED in 3s
```
| 类 | tests | failures |
|---|---|---|
| `ComposerCallFingerprintV116Test` | 19 | **7** |
| `ComposerTripwireIntegrityV115Test` | 9 | **0** ← v115 绿 |
| `ComposerBandTallEnoughV106Test` | 7 | **0** ← V106 绿 |

**决定性一行原文**（这次指名的是 **768**）：
> `R5: [fun theFivePixelOverflowIsReproducedOnTheDeviceReadingsAndGoneOnTheNewBand { Frame(768, 466, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX) }/fingerprint-missing: no call in 'fun …' matches this exact fingerprint; found Frame at [600, 387, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX, 768, 466,`

抄最短一行：
> `Frame(768, 466, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX) }/fingerprint-missing: no call in 'fun …' matches this exact fingerprint`

**这是 v116 之前完全看不见的一格**：600 调用完好、`:372` 的 `rows=rows` 调用完好、
记录性 fixture 全在，**只有 768 这一档被删** ⇒ 判红。

---

### 4.3 变异③：加新的 + 删旧的（**两档同时**）

**注入**（`:351` 与 `:364` 的实参都改成不同值，并各加一条全新调用；`rows` 不参与被断言的几何，故 V106 仍绿）：
```diff
-        val after600 = Frame(600, 387, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)
+        val after600 = Frame(600, 387, rows = 2, oneRow = true, bandHeightPx = BAND_AFTER_PX)
+        @Suppress("unused") val decoy600 = Frame(900, 387, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)
-        val after768 = Frame(768, 466, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)
+        val after768 = Frame(768, 466, rows = 11, oneRow = true, bandHeightPx = BAND_AFTER_PX)
+        @Suppress("unused") val decoy768 = Frame(900, 466, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)
```

**跑**：
```
35 tests completed, 8 failed
BUILD FAILED in 3s
```
| 类 | tests | failures |
|---|---|---|
| `ComposerCallFingerprintV116Test` | 19 | **8** |
| `ComposerTripwireIntegrityV115Test` | 9 | **0** |
| `ComposerBandTallEnoughV106Test` | 7 | **0** |

**决定性一行原文**：
> `found Frame at [600, 387, rows=2, …, 900, 387, rows=0, …, 768, 466, rows=11, …, 900, 466, rows=0, …, 768, 466, rows=rows, …] — an added call does NOT substitute for the pinned one`

抄最短一行：
> `found Frame at [600, 387, rows=2, …, 900, 387, …, 768, 466, rows=11, …, 900, 466, …, 768, 466, rows=rows, …] — an added call does NOT substitute for the pinned one`

**两条新增的 `900` 诱饵就在列表里，`:372` 的 `rows=rows` 也在，仍然红。**

---

### 4.4 变异④：把实参换成字面量（**数值等价**那一类，两档各一次）

#### 4.4a 600 档 → `bandHeightPx = 48`

```diff
-        val after600 = Frame(600, 387, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)
+        val after600 = Frame(600, 387, rows = 0, oneRow = true, bandHeightPx = 48)
```
```
35 tests completed, 7 failed
BUILD FAILED in 3s
```
| 类 | tests | failures |
|---|---|---|
| `ComposerCallFingerprintV116Test` | 19 | **7** |
| `ComposerTripwireIntegrityV115Test` | 9 | **0** |
| `ComposerBandTallEnoughV106Test` | 7 | **0** ← **数值完全等价，V106 7 个用例照样全绿** |

**决定性一行原文**：
> `found Frame at [600, 387, rows=0, oneRow=true, bandHeightPx=48, 768, 466, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX, 768, 466, rows=rows, …]`

#### 4.4b 768 档 → `bandHeightPx = 48`（**v116 完全看不见的那一格**）

```diff
-        val after768 = Frame(768, 466, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)
+        val after768 = Frame(768, 466, rows = 0, oneRow = true, bandHeightPx = 48)
```
```
35 tests completed, 6 failed
BUILD FAILED in 3s
```
| 类 | tests | failures |
|---|---|---|
| `ComposerCallFingerprintV116Test` | 19 | **6** |
| `ComposerTripwireIntegrityV115Test` | 9 | **0** |
| `ComposerBandTallEnoughV106Test` | 7 | **0** ← **数值完全等价，V106 照样全绿** |

**决定性一行原文**（指名 **768**）：
> `R5: [… { Frame(768, 466, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX) }/fingerprint-missing: no call in 'fun …' matches this exact fingerprint; found Frame at [600, 387, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX, 768, 466, rows=0, oneRow=true, bandHeightPx=48, 768, 466, rows=rows, …]`

抄最短一行：
> `found Frame at […, 768, 466, rows=0, oneRow=true, bandHeightPx=48, 768, 466, rows=rows, …]`

**这一条是"名义活、实质死"的完整实证，且发生在 v116 未 pin 的那一档**：
`BAND_AFTER_PX` 就是 48，**几何一个像素都没变**，V106 的 7 个断言与 v115 的 9 个用例
**全部照样绿** —— 只有把 768 档写进必需集合之后，它才被抓住。

---

### 4.5 五轮汇总

| 轮 | 注入 | V116 | v115 | V106 | 构建 |
|---|---|---|---|---|---|
| 变异① | 删 `:351`（600 档） | **8 failed** / 19 | 0 / 9 | 0 / 7 | `BUILD FAILED in 3s` |
| 变异② | 删 `:364`（768 档） | **7 failed** / 19 | 0 / 9 | 0 / 7 | `BUILD FAILED in 3s` |
| 变异③ | 加新删旧（两档） | **8 failed** / 19 | 0 / 9 | 0 / 7 | `BUILD FAILED in 3s` |
| 变异④a | 600 档实参 → `48`（等价） | **7 failed** / 19 | 0 / 9 | 0 / 7 | `BUILD FAILED in 3s` |
| 变异④b | 768 档实参 → `48`（等价） | **6 failed** / 19 | 0 / 9 | 0 / 7 | `BUILD FAILED in 3s` |

**五条全部变红**；v115 与 V106 在**每一轮都保持绿色** ⇒ 红灯来自新判据本身，不是连带损伤。

### 4.6 还原校验

```
RESTORE[M1] matches_original=True
RESTORE[M2] matches_original=True
RESTORE[M3] matches_original=True
RESTORE[M4a] matches_original=True
RESTORE[M4b] matches_original=True
V106_hash_now=291B3D95CAA7028FFF4D5D4E1330B1D7A47464E085263E80D124C47A5094E004 matches_original=True
```
`git diff --numstat -- …/ComposerBandTallEnoughV106Test.kt` → **输出为空（0 行改动）**。

---

## 5. 为什么不降强度

1. **纯加法，判据本体一行未改。** `CallFingerprintValidator.validate` 仍是集合包含判据；
   本轮只把 `R5_CALLS` 从 1 条补到 2 条，**没有新增任何一种更宽松的检查**。
2. **v116 全部保留，且两条旧用例被收紧。** 集合变大后，
   `deletingThePinnedCallIsReported` / `addingANewCallAndDeletingTheOldOneIsStillReported`
   的断言从 `violations.isNotEmpty()` 升级为 `violations.size == 1` **且指名 600 档** —— 更严。
3. **v115 一行未动。** 五个用例、类结构、fixture pin 全部原样。
4. **没有任何断言被放宽，没有删任何用例。** V116 12 → 19（+7），
   V115 9 → 9，V109 11 → 11，**V106 7 → 7**（见 §6）。
5. **没有新增用户可见字符串。** 全部改动在 `src/test/**` 的注释与断言文案里，无 `R.string`。
6. **`:372` 故意不 pin 的理由已写进代码注释**（§2.2），且变异③证明它救不了任何 pin ——
   强度没有因此下降。
7. **裁定本身有结构守卫。** `bothTiersAreRequiredAndAreDistinctFingerprints`
   保证 `R5_CALLS` 不会悄悄退回"一档"，`neitherTierSubstitutesForTheOther`
   保证两条不会互相顶替。

---

## 6. 测试证据

抢锁：`Set-Content .agent-gradle-lock "v117-two-tier-fingerprint"` → **前台**跑完全部 7 次
（1 基线 + 5 变异 + 1 终验）→ **立刻**用绝对路径字面量删锁：
```
rm -- "C:\sensorsdata\main\program\opencode-android-shell\.agent-gradle-lock"
mavis-trash: moved to trash: '…\.agent-gradle-lock'
```
`LOCK_EXISTS=False`（已核实）。
`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`。
**未** assembleDebug / clean / 全量 / 装机。仅 `:app:testDebugUnitTest --tests "<类名>"`。

### 6.1 终验（5 类）

```
.\gradlew.bat :app:testDebugUnitTest \
  --tests "com.xiufeigo.opencodeshell.ComposerCallFingerprintV116Test" \
  --tests "com.xiufeigo.opencodeshell.ComposerTripwireIntegrityV115Test" \
  --tests "com.xiufeigo.opencodeshell.SshRowMenuUpstreamParityV115Test" \
  --tests "com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test" \
  --tests "com.xiufeigo.opencodeshell.ComposerBandTallEnoughV106Test"
```

**原文**：
```
BUILD SUCCESSFUL in 4s
52 actionable tasks: 2 executed, 50 up-to-date
GRADLE_EXIT=0
```

| 类 | tests | failures | errors | skipped | vs v116 基线 |
|---|---|---|---|---|---|
| `ComposerCallFingerprintV116Test`（本轮改动） | 19 | 0 | 0 | 0 | 12 → **19**（+7） |
| `ComposerTripwireIntegrityV115Test` | 9 | 0 | 0 | 0 | 9 → 9 |
| `SshRowMenuUpstreamParityV115Test` | 7 | 0 | 0 | 0 | 7 → 7 |
| `ComposerUnionBottomP5V109Test`（宿主） | 11 | 0 | 0 | 0 | 11 → 11 |
| `ComposerBandTallEnoughV106Test`（宿主） | **7** | 0 | 0 | 0 | 7 → **7（未减少）** |
| **合计** | **53** | **0** | **0** | **0** | 46 → 53 |

**V106 用例数 7 → 7，未减少**，且其源码 `git diff` 为 0 行 ——
没有靠删用例达标。

---

## 7. 未闭环（如实记账）

1. **指纹仍是文本级的。**（v116 §7#1 结转）`canonical` 把 `rows = 0` 与 `rows=0` 视为同一指纹。
   一次**无害重构**（改实参写法）会要求同步改 pin。这是刻意的取舍：要"那一条调用"可辨，
   就必须认它的写法。**本轮把范围从 1 条扩大到 2 条，同步成本翻倍** —— 已写进本节记账。
2. **`:372` 的 `rows = rows` 调用仍不在必需集合内**（§2.2 有理由）。
   它的存活目前只靠"循环体里还有别的 `Frame` 调用"间接保证。
   若将来有人把 `:371-377` 整段换成一个不含 `Frame` 的写法，**R5 的两档 pin 会同时报红**
   （因为 600/768 档在别处、循环段被改不影响它们）—— 所以这条残余风险的**暴露面是可控的**，
   但我**不宣称它已被 pin**。
3. **`minProductionArgs` 仍是人工赋值**（v116 §7#3 结转）：两档各设 1，
   依据是"今天只有 `BAND_AFTER_PX` 非字面量"，没有"自动算出应有值"的机制。
4. **R5 退化文案仍比 R6 锐利**（v115 §8#3 结转）：R5 的宣传性注释在方法体内，R6 的在签名之上。
   本轮**未移动**那段注释（会动 v114 记账位置）。
5. **本轮无真机读数**（环境纪律禁止装机）。五条变异都是纯源码结构判定，不需要设备。
6. **R6 侧未做对称加固。** v116 §7#2 建议"下轮按同一手法补 R6 侧的完整指纹集" ——
   本轮 Lead 的裁定只覆盖 R5 的两档，**R6 侧未扩范围**（克制，不自行扩大）。

---

## 8. 回报（给 Lead）

- **状态**：**裁定已落地并实跑验证**；五条变异**全部变红**；生产代码零改动。
- **「两档是否都进了必需指纹集合」**：**都进了**。`ComposerFingerprintSpecs.R5_CALLS` 现为
  `listOf(Frame(600, 387, …), Frame(768, 466, …))`，两条 `minProductionArgs = 1`，
  并由 `bothTiersAreRequiredAndAreDistinctFingerprints` + `neitherTierSubstitutesForTheOther` 双向守卫。
- **「四条变异是否都变红（各抄一行原文）」**：**都红**（实测 5 轮）。
  - ① 删 `:351` → `35 tests completed, 8 failed` / `BUILD FAILED`（v115 9/0、V106 7/0）
    `Frame(600, 387, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX) }/fingerprint-missing: no call in 'fun …' matches this exact fingerprint`
  - ② 删 `:364` → `35 tests completed, 7 failed` / `BUILD FAILED`（v115 9/0、V106 7/0）
    `Frame(768, 466, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX) }/fingerprint-missing: no call in 'fun …' matches this exact fingerprint`
  - ③ 加新删旧（两档）→ `35 tests completed, 8 failed` / `BUILD FAILED`
    `found Frame at [600, 387, rows=2, …, 900, 387, …, 768, 466, rows=11, …, 900, 466, …, 768, 466, rows=rows, …] — an added call does NOT substitute for the pinned one`
  - ④ 实参换字面量（数值等价，两档各一次）→ `7 failed` / `6 failed`，**V106 两次都 7/0 全绿**
    `found Frame at [600, 387, rows=0, oneRow=true, bandHeightPx=48, 768, 466, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX, …]`
    `found Frame at […, 768, 466, rows=0, oneRow=true, bandHeightPx=48, 768, 466, rows=rows, …]`
- **「V106 用例数有没有减少」**：**没有**。`7 → 7`，且 V106 源码 `git diff` 为 **0 行**。
- **改了哪些文件**（全在允许范围内）：
  - **唯一被修改的代码文件**：`app/src/test/java/com/xiufeigo/opencodeshell/ComposerCallFingerprintV116Test.kt`
    （`1 file changed, 304 insertions(+), 7 deletions(-)`；`R5_CALLS` 加一条指纹 + 7 个新用例 + 2 个旧用例收紧 + 文件头裁定说明）
  - **新增**：`research/v97_fix/TRIPWIRE_HARDENING3.md`（本报告）、
    `research/v97_fix/_v117_backup/`（`V106.bak` + `V106.sha256`，注入前备份，用于还原校验）
  - **未改**：生产代码（零）、`V106`（`git diff` 0 行）、`V109`、v115 类、`ui/**`、`OcActivity.kt`、`_upstream/`、`app/build.gradle.kts`
- **命令与用例数**（见 §6）：**5 类 / 53 tests / 0 failures / 0 errors / 0 skipped**，
  `BUILD SUCCESSFUL in 4s`，`GRADLE_EXIT=0`；
  五轮变异分别 `8 / 7 / 8 / 7 / 6` failed，全部 `BUILD FAILED in 3s`。
- **行号口径**：R5 = **`:351`（600 档）+ `:364`（768 档）**；`:347` 的 `2, 296 - 294`
  是**记录性 fixture**（真机读数），**不是**判据依赖；对照表见 §3。
- **报告路径**：`research/v97_fix/TRIPWIRE_HARDENING3.md`
