# GATE_RED_V110_FIX —— v111：闸门红了 —— v110 的 `top=4.dp → 0.dp` 打破两个既有 pin

- 修复工程师：mavis（v111，用 mcode 执行）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 前序：`COMPOSER_OVERFLOW_FIX8.md`（v110，本轮的起手状态）

> ## 状态：**进行中**（闸门红 → 判定 → 修 → 全绿，证据逐条追加写回）

---

## 0. 落盘纪律

- §0.1 本文件在任何代码改动之前写定。
- §0.2 每取得一条证据（失败断言原文 / diff / 测试输出）立刻追加写回。

## 0.1 本轮授权范围

- **可写**：`ui/session/**` + 测试文件 + `research/v97_fix/**`。
- **不许碰**：`_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers/**`、
  `ui/home/**`、`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`。
- **gradle 只允许** `:app:testDebugUnitTest --tests "<类名>"`；**禁止** assembleDebug / clean / 全量 / 装机。
- **不许新增用户可见字符串**；**不许放宽/删除断言**，不许改成恒真式。

---

## 1. 起手状态（Lead 全量闸门，权威）

```
ComposerOneRowChipReserveV81Test > the 240 dp window keeps the chip at its floor   FAILED
ComposerOneRowEditorFloorV86Test > the editor's floor is exactly what the narrowest window can pay   FAILED
5948 tests completed, 2 failed        BUILD FAILED
```

**655 suites / 5948 tests / 2 failures**（v110 之后）。**树当前是红的。**

---

## 2. 精确定位（静态先行，两条断言已找到同一行字面量）

两个失败用例**各自的第一条断言是同一段源码文本的 `.contains(...)` 镜像 pin**：

| 失败类 | 用例 | 断言位置 | 被 pin 的字面量 |
|---|---|---|---|
| `ComposerOneRowChipReserveV81Test` | `the 240 dp window keeps the chip at its floor` | `app/src/test/java/com/xiufeigo/opencodeshell/ComposerOneRowChipReserveV81Test.kt:109-112` | `.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)` |
| `ComposerOneRowEditorFloorV86Test` | `the editor's floor is exactly what the narrowest window can pay` | `app/src/test/java/com/xiufeigo/opencodeshell/ComposerOneRowEditorFloorV86Test.kt:205-208` | 同一字面量 |

两处断言**原文逐字相同**（`ComposerOneRowEditorFloorV86Test.kt:207` 与
`ComposerOneRowChipReserveV81Test.kt:111`）：

```kotlin
assertTrue(
    source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        .contains(".padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)"),
)
```

### 2.1 生产侧现状（v110 改后，`SessionScreen.kt:1807-1824` 原文）

```kotlin
val composerRegion: @Composable () -> Unit = {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgBase)
            .onSizeChanged { composerHeightPx = it.height }
            // v110 P0: the region's 12 dp of vertical decoration lives **below**
            // the band, never above it. …
            .padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 12.dp),
```

（`.padding(...)` 的实参行紧随该注释块之后；注释见 `SessionScreen.kt:1813-1824`。）

### 2.2 生产侧常量未动（`SessionPaneLayout.kt:1439-1440` 原文）

```kotlin
/** `SessionScreen.composerRegion`'s own vertical padding (4 dp top + 8 dp bottom). */
val COMPOSER_REGION_PADDING: Dp = 12.dp
```

> ⚠️ 该 KDoc 仍写着旧的「4 dp top + 8 dp bottom」——**这是 v110 留下的陈旧文档**，
> 与本轮 pin 同一性质（编码旧假设），本轮一并更新（见 §5）。

**⇒ 静态结论**：两个 pin 约束的是
`composerRegion` 的**垂直拆分**（`4 + 8`），而它们各自那条算式真正用到的只有
**水平 `12 + 12`** 与**垂直总计 12**。

---

## 3. 失败断言原文（真跑出来的，不是推测）

### 3.1 命令与 gradle 原文

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --console=plain `
  --tests "com.xiufeigo.opencodeshell.ComposerOneRowChipReserveV81Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerOneRowEditorFloorV86Test"
```

```
> Task :app:testDebugUnitTest

ComposerOneRowChipReserveV81Test > the 240 dp window keeps the chip at its floor FAILED
    java.lang.AssertionError at ComposerOneRowChipReserveV81Test.kt:109

ComposerOneRowEditorFloorV86Test > the editor's floor is exactly what the narrowest window can pay FAILED
    java.lang.AssertionError at ComposerOneRowEditorFloorV86Test.kt:205

11 tests completed, 2 failed

FAILURE: 52 actionable tasks: 1 executed, 51 up-to-date
BUILD FAILED in 5s
EXIT=1
```

### 3.2 XML 报告里的失败栈（`app/build/test-results/testDebugUnitTest/`）

`TEST-com.xiufeigo.opencodeshell.ComposerOneRowChipReserveV81Test.xml`：

```xml
<testcase name="the 240 dp window keeps the chip at its floor" … >
  <failure message="java.lang.AssertionError" type="java.lang.AssertionError">java.lang.AssertionError
	at org.junit.Assert.fail(Assert.java:87)
	at org.junit.Assert.assertTrue(Assert.java:42)
	at org.junit.Assert.assertTrue(Assert.java:53)          ← 无 message 的单参重载
	at com.xiufeigo.opencodeshell.ComposerOneRowChipReserveV81Test.the 240 dp window keeps the chip at its floor(ComposerOneRowChipReserveV81Test.kt:109)
```

`TEST-com.xiufeigo.opencodeshell.ComposerOneRowEditorFloorV86Test.xml`：同样三行，末行为
`…ComposerOneRowEditorFloorV86Test.kt:205`。

### 3.3 「期望值 / 实际值」——**这一格必须诚实说明它不是数字比较**

- 命中的是 `org.junit.Assert.assertTrue(Assert.java:53)`，即**单参 `assertTrue(boolean)`**
  重载（两参带 message 的重载在 `:42` 只是被它调用）。
- 所以失败消息是**裸的 `java.lang.AssertionError`，没有 `expected:<…> but was:<…>`**。
- 本轮这两个 pin 的**期望/实际**只能这样陈述：

| | 期望 | 实际 |
|---|---|---|
| `V81Test:109` | `SessionScreen.kt` 源码**包含**字面量 `.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)` | 不包含（v110 已改为 `top = 0.dp, bottom = 12.dp`） |
| `V86Test:205` | 同上，同一字面量 | 同上 |

**⇒ 这两条失败在「形状」上就是源码文本镜像 pin 失配，不是任何几何数值越界。**
两个类里**所有数值断言**（`editorShare == 64`、`controlsShare == 88`、
`240 = 64+88+64+12+12` …）**位于该文本断言之后、因而本轮未被执行到**
——这是文本断言排在测试方法**第一条**的直接后果。
**不据此宣称「数值断言已通过」**；它们由 §6 修后全绿那一跑**逐条实际执行**来兑现（见 §6.2）。

---

## 4. 判定：**(a)** —— 新几何是对的，pin 编码的是旧假设

> **二选一，不和稀泥。本轮选 (a)。**

### 4.1 两个 pin 的**意图**（各自文件:行号 + 原文）

#### 意图 1 —— `ComposerOneRowChipReserveV81Test.kt:104-112`

```kotlin
@Test
fun `the 240 dp window keeps the chip at its floor`() {
    // 720x1280 @480dpi: window 240, region takes 12 + 12 (see
    // `composerRegion`'s padding), fixed row children `⊞` 28 + gaps 8 +
    // Send 28 — the worst window the form runs on.
    assertTrue(
        source("…/ui/session/SessionScreen.kt")
            .contains(".padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)"),
    )
    val card = 240f - 24f          // ← 这个 24 就是它要的
```

- **它约束的量**：`composerRegion` 的**水平**侧向 padding（`start + end`），
  因为下一行 `card = 240f - 24f` 里那个 `24` 就是从它推出来的。
- 注释自己写的是「region takes **12 + 12**」——**说的是水平**。
- **该量在 `top = 0.dp` 之后应该变成多少**：
  `start = 12.dp, end = 12.dp` **一位不变** ⇒ `card = 240 − 12 − 12 = 216` **一位不变**。
- 垂直 `4 / 8` **在该测试体内一次都没被读取**（全文无 `top` / `bottom` 的算式）。

#### 意图 2 —— `ComposerOneRowEditorFloorV86Test.kt:198-221`

```kotlin
@Test
fun `the editor's floor is exactly what the narrowest window can pay`() {
    // 240 dp (the 480 dpi window) − the region's 12 + 12 dp of padding − 64 (the
    // row's fixed children) − 88 (the chip's floor) = 64: the floor is not a taste
    // number, it is what the narrowest window the one-row form runs on can pay.
    // (`composerRegion`'s own **horizontal** padding, the same pair the v81 suite
    // pins: `padding(start = 12.dp, end = 12.dp, …)`.)
    assertTrue(
        source("…/ui/session/SessionScreen.kt")
            .contains(".padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)"),
    )
```

- **它约束的量**：同上，**水平 `12 + 12`**，外加**垂直总计 12**（下面那条算式里的 `12.dp + 12.dp`）。
- **决定性证据**：作者在注释里把这条 pin **自己写成带省略号的形式**：
  `padding(start = 12.dp, end = 12.dp, …)`
  —— **那个 `…` 就是作者对垂直部分的刻意省略**。
  也就是说：**作者的本意是水平对，断言写成完整字面量属于「多钉了一截」**。
- **该量在 `top = 0.dp` 之后应该变成多少**：
  - 水平：`12 + 12 = 24` **不变** ⇒ `card = 240 − 24 = 216` **不变**；
  - 垂直总计：`0 + 12 = 12` **不变** ⇒ `240 = 64 + 88 + 64 + 12 + 12` **不变**。

### 4.2 算式（为什么「应该变成」的新值就是「没变」）

```
【V81 / V86 真正消费的量】
card(240) = window(240) − start(12) − end(12) = 240 − 24 = 216 dp      ← 水平，未动
vertical total = top + bottom = 0 + 12 = 12 dp = COMPOSER_REGION_PADDING  ← 总计，未动

【两个 pin 咬住、但算式不消费的量】
垂直拆分 (top, bottom)：4 + 8  →  0 + 12                            ← 只有它变了
```

**所以：唯一变了的量，恰好是两条算式都不读的那一个。**
这就是「pin 编码旧假设」的严格含义——**不是断言变松了，是被约束的量本身换了，而新的那个值才是对的**。

### 4.3 为什么新几何是对的（三条独立理由）

1. **几何恒等式（不是口味问题）**：`SessionScreen.kt:1813-1824` 的注释与真机探针读数
   （v110 §3.2/§3.3）共同证明：宿主盒高度**恰好 = `BAND_HEIGHT`(48)**，
   带被摆在**宿主盒上沿**，因此**任何 top padding 都是纯溢出**：
   `unionBottom = hostTop + top + unionPx`，而 `hostTop + hostPx = paneBottom` 且 `unionPx = hostPx`
   ⇒ `top > 0` ⟺ `unionBottom > paneBottom`，**逐位成立**。
   600 档实测：`165 + 4 + 48 = 217 > 213`（越界 `+5`）→ 归零后 `249 ≤ 261 = −12`（富余 12）。
2. **改的是「搬到下沿」不是「删掉」**：`4 + 8 → 0 + 12`，**总计 12 逐位不变**
   ⇒ `COMPOSER_REGION_PADDING = 12.dp` 那笔账（`chromeImeBand` / `PANE_ESSENTIAL_HEIGHT` /
   `composerCeiling` / `COMPOSER_MINIMAL_HEIGHT` …）读到的数**完全相同**
   ⇒ §7 的三个 IME 无关量（`51` / `140` / `92`）**必然不变**（不是「大概不变」）。
3. **(b) 被证伪**：若真是「新几何破坏了窄窗可用性」，那么失败应当落在
   **数值**断言上（`editorShare == 64` / `controlsShare == 88` / `240 = 64+88+64+12+12`），
   而实际失败是**源码文本 `.contains()` 失配**；
   且这两个失败类的算式**全部是水平方向**的（card 宽度、weight 拆分），
   **垂直拆分无论取 `4/8` 还是 `0/12` 都进不了它们的式子**。
   更直接：**`0 + 12` 与 `4 + 8` 的 `card` 都是 216**，
   窄窗那一档的 chip floor / editor floor **一个像素都没动**。

> **结论：(a)。生产代码一行不改，只把两个 pin 从「旧垂直拆分」更新到「新垂直拆分」，
> 并把它们原本隐含承担的「水平 12+12 / 垂直总计 12」显式加成数字断言。**

---

## 5. 修（判定 (a) 的落地）

### 5.1 diff —— **生产代码几何零改动，只更新 2 条 pin 的锚 + 1 处陈旧 KDoc**

```diff
# app/src/test/java/com/xiufeigo/opencodeshell/ComposerOneRowChipReserveV81Test.kt  (109-112)
-        assertTrue(
-            source("…/ui/session/SessionScreen.kt")
-                .contains(".padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)"),
-        )
+        val region = source("…/ui/session/SessionScreen.kt")
+        assertTrue(
+            region.contains(
+                ".padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 12.dp)",
+            ),
+        )
+        assertTrue(
+            "the region's side padding is still 12 + 12",
+            region.contains("start = 12.dp, end = 12.dp"),
+        )
+        assertEquals(
+            "and its vertical total is still the 12 dp the pane budget sums",
+            SessionPaneLayout.COMPOSER_REGION_PADDING.value,
+            0f + 12f,
+            0.0f,
+        )

# app/src/test/java/com/xiufeigo/opencodeshell/ComposerOneRowEditorFloorV86Test.kt  (205-208)
-        assertTrue(
-            source("…/ui/session/SessionScreen.kt")
-                .contains(".padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)"),
-        )
+        val region = source("…/ui/session/SessionScreen.kt")
+        assertTrue(region.contains(".padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 12.dp)"))
+        assertTrue("… side padding is still 12 + 12 — the 24 the equation subtracts", …)
+        assertEquals("… vertical total is still the 12 dp the 240 dp equation adds back", …)

# app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt  (1439)
-    /** `SessionScreen.composerRegion`'s own vertical padding (4 dp top + 8 dp bottom). */
+    /** … own vertical padding **in total** (v110: `0 dp top + 12 dp bottom`; before that
+     *  `4 dp top + 8 dp bottom`).  The **total** is the only part anything downstream
+     *  reads … the *split* is free to change and did … */
     val COMPOSER_REGION_PADDING: Dp = 12.dp
```

**改到的文件（全部 3 个）**：
1. `app/src/test/java/com/xiufeigo/opencodeshell/ComposerOneRowChipReserveV81Test.kt`
2. `app/src/test/java/com/xiufeigo/opencodeshell/ComposerOneRowEditorFloorV86Test.kt`
3. `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt`（**仅 KDoc 注释**，
   `val COMPOSER_REGION_PADDING: Dp = 12.dp` 的**值一个字符没动**）

**未碰**：`SessionScreen.kt`（v110 的 `top = 0.dp, bottom = 12.dp` 保持原样，本轮**零改动**）、
`Composer.kt`、`_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers/**`、
`ui/home/**`、`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`。
**零新增用户可见字符串**（全是断言消息与 KDoc）。

### 5.2 为什么不降强度（逐条对照）

| 维度 | 修前 | 修后 | 判读 |
|---|---|---|---|
| 断言**条数**（每个类） | 1 | **3** | ↑ 变多 |
| 垂直拆分的粒度 | 精确字面量 `4 / 8` | 精确字面量 `0 / 12` | **同粒度，未放松** |
| 水平 `12 + 12` | 仅隐含在字面量里 | **独立显式断言** | ↑ 由隐含变显式 |
| 垂直总计 = 12 | 仅隐含在字面量里 | **与 `COMPOSER_REGION_PADDING` 显式对齐断言** | ↑ 由隐含变显式 |
| 容差 | 字面量 `.contains`（零容差） | 字面量 `.contains`（**仍是零容差**） | **未引入容差** |
| 是否恒真 | ❌ 否 | ❌ **仍否**：`region` 是真读源文件，源码一改就红 | 未改成恒真 |
| 是否 `assertNotNull` 化 | ❌ | ❌ 否 | 未退化 |
| 是否删断言 | — | ❌ **一条没删，只加** | — |

> **关键点**：把 pin 从 `4/8` 换成 `0/12` **不是**放宽。
> 原 pin 约束的是「垂直拆分必须恰好是 `4` 和 `8`」；新 pin 约束的是
> 「垂直拆分必须恰好是 `0` 和 `12`」——**同样一个字面量、同样零容差、同样会因源码改动而红**。
> 被约束的**对象**换了（因为 v110 合法地换了它），**约束的力度一个字节没松**，
> 并且额外补上了两条**原来根本没有的数字断言**。

---

## 6. 测试证据

### 6.1 修后命令（一次前台跑完，**先抢锁→跑→删锁**）

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
Set-Content -Path .agent-gradle-lock -Value "v111-mavis-verify"
.\gradlew.bat :app:testDebugUnitTest --console=plain `
  --tests "com.xiufeigo.opencodeshell.ComposerOneRowChipReserveV81Test" `
  … （共 32 个 --tests 过滤器，见 §6.3 全清单）
Remove-Item → 用可恢复删除 `rm -- ".agent-gradle-lock"`
```

### 6.2 gradle 输出原文（末尾）

```
> Task :app:compileDebugKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 1m 12s
52 actionable tasks: 5 executed, 47 up-to-date
EXIT=0
```

**XML 报告汇总**（`app/build/test-results/testDebugUnitTest/TEST-*.xml` 逐文件累加）：

```
suites=32  tests=248  failures=0  errors=0
ComposerOneRowChipReserveV81Test : tests=3  failures=0  errors=0  skipped=0
ComposerOneRowEditorFloorV86Test  : tests=8  failures=0  errors=0  skipped=0
```

> **§3.3 那个欠账在此兑现**：修前文本断言排在测试方法**第一条**、先失败，
> 导致其后的**数值断言从未被执行**。修后该文本断言通过，
> **V81 的 `editorShare == 64` / `controlsShare == 88` / `48f` 文本带 /
> `labelChars ≥ 6` / `caps ≥ 5`，V86 的 `216.dp` / `240 = 64+88+64+12+12` /
> `64 / 16 / 48 / 88` 各常量 / `47.17` 修前对照 / `104.83` 让出量 / `16.83` 增量
> —— 全部实际执行并通过**。
> **闸门从 2 failed 回到 0 failed。**

### 6.3 实际跑的类名全清单（32 个，全部在这次 `--tests` 里）

**两个原红类**：`ComposerOneRowChipReserveV81Test`、`ComposerOneRowEditorFloorV86Test`

**composer v103–v110 家族**：`ComposerOverflowV103Test`、`ComposerChildUnionV104Test`、
`ComposerBandTouchTargetV105Test`、`ComposerBandTallEnoughV106Test`、
`ComposerPositionDecompositionV107Test`、`ComposerPaneBottomCriterionV108Test`、
`ComposerUnionBottomP5V109Test`

**composer 相邻**：`ComposerEditorFormV80Test`、`ComposerEditorSlotV79Test`、`ComposerModelChipV75Test`

**queue 家族**：`QueueTimelineSpaceV99Test`、`QueueTimelineFloorV100Test`、
`QueueTimelineFloorV101Test`、`QueueTimelineShrinkCrashV102Test`、`QueueRowActionsPinTest`、
`QueuedLabelTest`

**pane 家族（每一个都把 `COMPOSER_REGION_PADDING` 加进预算）**：
`SessionPaneShortWindowV51Test`、`SessionPaneShortWindowV54Test`、
`SessionPaneShortWindowV55Test`、`SessionPaneShortWindowV57Test`、
`SessionPaneImeBeliefV77Test`、`SessionPaneShortWindowV78Test`、
`SessionPaneChromeFloorV79Test`、`SessionPaneImeBandsV81Test`、`SessionPaneImeBandV84Test`、
`SessionPaneArrangementV76Test`、`SessionPaneEmptyStateFitV86Test`

**ModelChip 家族**：`ModelChipLabelWidthTest`、`ModelChipCompactTest`、`ModelChipProviderBadgeTest`

---

## 7. 交叉证据：三个 IME 无关量**逐位不变**

> **诚实前置**：本轮环境纪律**禁止 `assembleDebug` / 装机**，因此**没有真机读数**。
> 任务书允许「真机 **/ pin** 读数」，故此处全部以 **pin 读数**给出，
> 每条都带 `文件:行号`，且**都在上面那 248 条全绿里**。

### 7.1 三个量的 pin 读数

| 量 | 档 | 读数 | pin 位置 | 本轮结果 |
|---|---|---|---|---|
| `readable` | 600 | **`51`** | `ComposerBandTallEnoughV106Test.kt:494`（`theThreeCellBudgetPreviewKeepsThe768Floor`）<br>`ComposerBandTouchTargetV105Test.kt:327-329` | ✅ 绿 |
| 时间线盒 / `pane` | 768 | **`140`** | `ComposerBandTallEnoughV106Test.kt:483`（`assertEquals("768+IME pane", 140, pane768)`）<br>`ComposerUnionBottomP5V109Test.kt:59,82` | ✅ 绿 |
| `readable` | 768 | **`92`** | `ComposerBandTallEnoughV106Test.kt:484`（`assertEquals("768+IME readable … 逐字不变", 92, readable768)`）<br>`ComposerBandTallEnoughV105Test.kt:326` 注释 | ✅ 绿 |

`ComposerBandTallEnoughV106Test.kt:481-500` 三格逐条：

```kotlin
// ---- 1366x768 + IME：pane 140，readable 92（≥ 64 ✅，**未变**）----
val (pane768, readable768) = readable(768, 466)
assertEquals("768+IME pane", 140, pane768)
assertEquals("768+IME readable — 修前 92，方案 A 后逐字不变", 92, readable768)
assertTrue("768 的 readable 必须仍 ≥ 64 地板（实际 $readable768）", readable768 >= 64)
// ---- 1366x600 + IME：pane 51，readable 51（显式让位区，**未变**）----
val (pane600, readable600) = readable(600, 387)
assertEquals("600+IME pane", 51, pane600)
assertEquals("600+IME readable — 方案 A 未动（显式让位区）", 51, readable600)
assertEquals("600+IME 绝对底沿 = 162+51 = imeTop", 213, CHROME_PX + readable600)
```

### 7.2 为什么本轮改动**在结构上不可能**让它们动

这三个量全部由**一条链**决定，而本轮**只改了测试文件里的两处断言 + 一处 KDoc**：

```
readable / pane 盒高
  ← SessionPaneLayout.viewportHeightPx / timelineViewportFloorPx / safeViewportFloorPx
  ← pane = window − CHROME_PX − ime            （与 region padding 无关）
  ← composer 扣减 = min(card, ceiling)，card 固有高 = 48 px（BAND_HEIGHT）
  ← ceiling / chromeImeBand / PANE_ESSENTIAL_HEIGHT … 全部只读 COMPOSER_REGION_PADDING 的**值** 12.dp
```

**`COMPOSER_REGION_PADDING` 的值本轮一个字符没动**（`SessionPaneLayout.kt:1440` 仍是 `12.dp`），
且 v110 保证了**垂直总计仍是 12**（`4 + 8 → 0 + 12`）。
⇒ **所有把 `COMPOSER_REGION_PADDING` 加进 pane 预算的项读到的数完全相同**
⇒ `51 / 140 / 92` **不是「大概不变」，是恒等**。

### 7.3 600 档 ①：为什么**不会回退**

- v110 已验：`unionBottom 249 ≤ paneBottom 261` ⇒ **−12**（修前 `+5` 越界）。
- 本轮**对 `SessionScreen.kt` 零改动**（`git diff` 确认，见 §9），
  `top = 0.dp, bottom = 12.dp` 原样保留 ⇒ **v110 那 4px 的归零效果一字未动**。
- 恒等式（v110 §3.3 探针 `toolRow.place(0,0)`、`toolRow.h = 48 = band` 钉死）：
  `unionBottom = hostTop + top + unionPx = hostTop + 0 + 48`，
  而 `hostBottom = hostTop + hostPx = hostTop + 48`
  ⇒ `unionBottom = hostBottom ≤ paneBottom` **恒成立**，**与任何测试改动无关**。
- 判据 ② `unionPx ≤ band`：`48 ≤ 48 = 0`
  （pin：`ComposerPaneBottomCriterionV108Test.kt:104-110` `criterionTwoUnionPxAtMostBandHoldsInBothTiers`）✅ 绿。

> **⇒ 600 档 ① 仍成立，且本轮不可能让它回退：本轮没有改任何生产几何。**

---

## 8. 未闭环（如实记账）

1. **🔴 最重要：`ComposerPaneBottomCriterionV108Test` 的 600 档 ① 判据是「冻结字面量」，
   它现在是「陈旧但绿」，**既不能证明 ① 已修好，也不能侦测 ① 回退**。
   - `ComposerPaneBottomCriterionV108Test.kt:75-88`（`theSixHundredTierIsStillOpenAndIsPinnedAsSuch`）：
     ```kotlin
     val unionBottom = 218                                  // 冻结字面量
     val paneBottom = paneTop + paneHeight600              // 213
     val criterion1 = unionBottom - paneBottom             // +5
     assertEquals("the 600 tier still overruns by 5", 5, criterion1)
     // NOT relaxed into a pass: this is the assertion that must start failing
     // the day the 600 tier is actually fixed, and it is the open item.
     assertTrue("OPEN: 1366x600 unionBottom(218) > paneBottom(213) …", unionBottom > paneBottom)
     ```
   - 作者的注释把它设计成「600 档修好那天就该开始失败的绊线」，
     **但它钉的是字面量 `218` / `213`，不是生产函数** ⇒ **按构造永远不会触发**。
   - v110 已把 600 档修成 `249 ≤ 261 = −12`（真机），**这条绊线记录的那个缺陷已经不存在了**，
     而它仍然断言「缺陷存在」。
   - **本轮我没有动它**，三条理由：
     1. 它**是绿的**，不属于本轮要修的红；
     2. 要正确更新它，必须先定**口径**——v110 §7 #4 明确把
        「`SessionScreen.kt:2428` 的 pane 盒与 a11y dump 里那个 `[0,162][1366,N]` 容器
        是不是同一个节点」列为**两轮未证到行**，并写「**建议 Lead 裁定时统一口径**」。
        两轮读数 99/188 与 51/140 不是同一口径，**在 Lead 裁定前写任何新字面量都是编造**；
     3. 把 `assertTrue(unionBottom > paneBottom)` 翻转成 `<=`，在没有新实测读数背书时，
        正是本仓连续三轮栽跟头的「假绿」陷阱（该文件 `:25-27` 自己写着这条）。
   - **给 Lead 的具体建议**：等口径裁定后，把这条绊线从字面量改成**调用生产函数**
       （`SessionPaneLayout.viewportHeightPx` / `composerBottomPx`），
       让它真正具备「修好就红、回退就红」的能力，而不是钉死一个数。

2. **本轮无真机读数**：环境纪律禁止 `assembleDebug` / 装机，
   故 §7 三个量以 **pin 读数**交付（任务书允许「真机 **/pin** 读数」）。
   **v110 的真机读数（`51` / `140` / `92` / `249 ≤ 261`）本轮没有复测**，
   依据是「本轮对生产几何零改动」这一**结构性论证**（§7.2），而非新的设备读数。

3. **`ComposerUnionBottomP5V109Test` 保留未动（刻意的记账）**：
   它的 `regionTopPaddingPx = 4` / `regionBottomPaddingPx = 8`
   （`ComposerUnionBottomP5V109Test.kt:63-64`）是**测试内自建字面量，不读源文件**，
   整类钉的是 **v98/v109 那个缺陷现场本身**（`218 > 213`、`+5 = 4 + 1`）。
   它现在是绿的，且**正因如此不能改** —— 改掉就抹掉了「当初错在哪」的唯一记录。
   其 KDoc 提到的 `SessionScreen.kt:1813` 行号已因 v110 注释块下移（现 `:1827`），
   **本轮未追改**（属注释级陈旧，不影响任何断言；追改需连带解释 v110 注释块，
   收益低于风险，留给下一轮与 §8#1 的口径裁定一起处理）。

4. **`SessionPaneLayout.kt` 的 KDoc 行号位移**：因本轮把该 KDoc 从 1 行展开为 10 行，
   `COMPOSER_REGION_PADDING` 从 `:1440` 移到 **`:1449`**（**值仍是 `12.dp`**，已回读确认）。
   全仓无任何测试按行号读这个常量（`grep` 全部命中都是**符号**引用），
   故此位移**不影响任何 pin**。

---

## 9. 纪律自查

| 项 | 状态 |
|---|---|
| 写权限 | 只碰 3 个文件（2 个测试 + `ui/session/SessionPaneLayout.kt` 的 KDoc）✅ |
| 生产几何 | `SessionScreen.kt` **零改动**；回读 `:1827` 仍为 `top = 0.dp, bottom = 12.dp` ✅ |
| `COMPOSER_REGION_PADDING` 的**值** | `12.dp` **一个字符没动**（现 `:1449`）✅ |
| 禁令文件 | `_upstream/` / `build.gradle.kts` / `ChatViewModel.kt` / `ui/servers|home|terminal|nav` / `App.kt` / `OcActivity.kt` **一个没碰** ✅ |
| gradle | 只跑 `:app:testDebugUnitTest --tests …`（两次，均为指定类）；**未** assembleDebug / clean / 全量 / 装机 ✅ |
| 抢锁 | `Test-Path` 确认无锁 → `Set-Content .agent-gradle-lock` → 跑完 → `rm --` 释放（两次）✅ |
| 用户可见字符串 | **零新增**（改动全是断言消息 + KDoc 注释）✅ |
| 断言强度 | 未删、未放宽、未恒真化；每个类的断言 **1 → 3 条** ✅ |
| 报告落盘 | 先写盘再动手，每条证据追加写回 ✅ |

> **git 说明（诚实记账）**：本轮改到的 3 个文件在 git 里都是 **`??` 未跟踪**状态
> （整棵 v97_fix 的工作面本来就是未提交的脏树，`git status` 可见大量 `M`/`??`），
> 因此 `git diff` 对它们**不显示任何内容**——这**不代表本轮没改**，
> 实际改动以上文 §5.1 的 diff 与回读结果为准（`:1827` / `:1449` 均已回读确认）。

---

## 10. 回报（给 Lead）

- **状态**：**闸门已回绿** —— 2 failed → **0 failed**（32 suites / 248 tests / 0 errors，EXIT=0）。
- **判定**：**(a)** —— 理由一句话：两条 pin 约束的是**垂直拆分**，而它们各自那条算式
  （`240 − 24 − 64 − 88 = 64`、`card = 240 − 24`）**只消费水平 `12 + 12` 与垂直总计 12**，
  两者在 `top = 0.dp` 之后**一位都没变**；变的那个量（`4/8 → 0/12`）**恰好是算式不读的那一个**。
- **两个 pin 怎么处理**：**从「旧垂直拆分」更新到「新垂直拆分」，粒度不变**——
  仍然是**零容差的精确字面量 `.contains()`**（`top = 4.dp, bottom = 8.dp` → `top = 0.dp, bottom = 12.dp`）；
  并**各加 2 条显式数字断言**（水平 `12 + 12`、垂直总计 `= COMPOSER_REGION_PADDING`），
  把原先隐含在字面量里的两个数**提成独立 pin** ⇒ 每类断言 **1 → 3 条**，**只增不减、只严不松**。
- **600 档 ①**：**仍成立**（v110 已验 `249 ≤ 261 = −12`）。
  本轮**对生产几何零改动**（`SessionScreen.kt` 回读确认 `:1827` 原样），
  故那 4px 的归零效果一字未动，**结构上不可能回退**。
- **改了哪些文件**：
  `ComposerOneRowChipReserveV81Test.kt`、`ComposerOneRowEditorFloorV86Test.kt`、
  `ui/session/SessionPaneLayout.kt`（**仅 KDoc**，常量值未动）。
- **命令与用例数**：`:app:testDebugUnitTest` + 32 个 `--tests` → **248 tests / 0 failures / 0 errors**，
  `BUILD SUCCESSFUL in 1m 12s`，`EXIT=0`（类名全清单见 §6.3）。
- **未闭环**：**`ComposerPaneBottomCriterionV108Test` 的 600 档 ① 绊线是冻结字面量
  （`218 > 213`），按构造永远不会触发** —— 现在陈旧但绿，
  本轮**刻意未动**（口径未由 Lead 裁定，写新字面量即编造）。详见 §8#1 与建议。
- **报告**：`research/v97_fix/GATE_RED_V110_FIX.md`
