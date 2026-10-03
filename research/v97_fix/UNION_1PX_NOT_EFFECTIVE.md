# UNION_1PX_NOT_EFFECTIVE — v138：v136 的「并集对齐 1px」为何真机不生效

状态：**闭环于「诊断」分支** —— 两处生产函数**可达**（在真渲染路径上被调用），但在 600 档
「队列条在屏 / 一行形态」里**可证是 no-op**；真正决定那 1px 的是 `Composer.kt:2210` 的
`verticalAlignment`，而 **v137 那一帧的算式指纹属于 `Alignment.CenterVertically` 分支**
（`roundToInt((48−21)/2f) = 14`），`Alignment.Top` 只能给 0 ⇒ **那一帧不是含 v136 的代码渲染的**。· 2026-10-04

> 锁约定沿用 `POSITIONAL_1PX_FIX.md:5`（仓库无独立 `*.lock`，本报告文件即锁）。
> 抢锁时刻 = 首次写入本文件（当时无同名文件、git 树 clean）；本文件定稿 = 释放锁。
>
> 纪律：只跑 `:app:testDebugUnitTest --tests "<类名>"`；未 `assembleDebug` / `clean` / 全量 / 装机；
> 未杀进程；未新增用户可见字符串；**未放宽或删除任何既有断言**；未用 `git checkout` / `git restore`
> 改行尾（`autocrlf=false`）。**生产代码零改动**（任务②未需要，§9 说明）。

---

## 0. 一页结论

| 问题 | 答案 |
|---|---|
| `composerBandChildTopPx` / `composerBandUnionBottomPx` 在本形态**可达**吗？ | **可达**，`minimal == true` 有硬证据（§2.2） |
| 那它们为什么没效果？ | **可证 no-op**：本形态两个子节点的实测高都**恰好等于** `band`，函数恒返回 `0`，与修前的硬编码 `toolRow.place(0, 0)` / `(band-editor)/2` **逐位相同**（§3） |
| 真正决定那 1px 的是哪段？ | `Composer.kt:2210` 的 `verticalAlignment`，**叠加** material3 强制的 48dp `minimumInteractiveComponentSize` 在**布局底沿之下**做的居中扩张（§4） |
| v136 为何无效？ | 三处改动里两处是死数（§3），唯一能动像素的是 `Alignment.Top`（`:2210`），而 v137 帧的算式指纹证明**跑那一帧的代码没有它**（§5） |
| pin 怎么降级的？ | 逐条**保留** + 类级「非真机真实」横幅 + 新增**真机读数寄存器**（断言「模型与真机仍差 1px」的绊线）（§6） |

**这条链路闭合了 v137 §5.1 的悬念**：v137 无法区分的两个候选里，**「归因行指错」被排除**
（`:2210` 确实是决定行），**「构件归属」成立** —— 但**不是**源码缺失（见 §5.3 的时间戳反查）。

---

## 1. 帧的原始读数（本轮从 XML 重出，未抄 v137 报告的表）

`research/v97_verify/5554_md/artifacts/v137_BARON_M.xml`（`wm density 160` ⇒ 1dp == 1px）。
我用脚本把 66 个节点全部导出后**逐位复算**，只看与本缺陷有关的：

| 节点 | class | bounds | h | 角色 |
|---|---|---|---|---|
| 宿主盒 pane | `View` | `[0,162][1366,261]` | 99 | `paneBottom = 261` |
| 队列条行 | `View` | `[18,184][1348,216]` | 32 | 条在屏形态 |
| 队列条 48dp 靶 | `View` | `[22,176][1257,224]` | 48 | `clickable=true` |
| `Steer` | `Button` | `[1263,177][1311,225]` | 48 | `clickable=true` |
| **编辑器** | `EditText` | `[24,214][405,262]` | **48** | 带的**布局**底沿 262 |
| `Add` | `Button` | `[399,215][441,263]` | **48** | **语义**底沿 263 |
| **控件横滚容器** | `HorizontalScrollView` | `[441,228][1316,249]` | **21** | **控件的布局**，21 高 |
| `Model` chip 外层 | `View` | `[441,215][559,263]` | 48 | `clickable=true`，**语义** |
| `Model` chip 内容 | `View` | `[441,228][559,249]` | **21** | chip 的**布局** |
| `Model` 文本 | `TextView` | `[467,231][537,247]` | 16 | |
| 编辑器占位文本 | `TextView` | `[24,228][315,248]` | 20 | 编辑器**内容**居中 |

**这张表里藏着全部答案**：21 高的**布局**（`228..249`）与 48 高的**语义**（`215..263`）
同时存在 ⇒ `uiautomator` 量的是**语义**框（`clickable` 节点的 bounds 含 48dp 触摸靶扩张），
而 21 高的布局才是 `Alignment` 摆放的那个孩子。**v136 改的那两行摆放的是 48 高的 Row 本身，
不是这个 21 高的孩子** —— 这是整件事的枢纽。

---

## 2. 调用链（逐跳 `文件:行号`）

### 2.1 逐跳

```
[布局入口]
 SessionScreen.kt:1870            minimal = minimalComposer                （composer 区域）
 SessionScreen.kt:2385-2389      bodyOneRow = SessionPaneLayout.composerOneRow(isShortWindow(win), bodyHeightDp)
                                  if (bodyOneRow != minimalComposer) minimalComposer = bodyOneRow
 SessionPaneLayout.kt:1039-1040  fun composerOneRow(w,b) = w || composerMinimal(b)
 SessionPaneLayout.kt:1008-1010  fun composerMinimal(avail) = avail in (0, COMPOSER_MINIMAL_HEIGHT + CHROME_MIN_HEIGHT + TIMELINE_MIN_CONTENT)
        ↓
 Composer.kt:274                 minimal: Boolean = false                  （Composer 形参）
 Composer.kt:2031-2032           ComposerArrangement(minimal = minimal, editor = { editorField(...) }, controls = { toolControls() })
 Composer.kt:2139-2144           private fun ComposerArrangement(minimal, editor, controls, modifier)
 Composer.kt:2179-2189           Row { modifier = if (minimal) Modifier.fillMaxWidth().height(BAND_HEIGHT) else … }
 Composer.kt:2210                verticalAlignment = if (minimal) Alignment.Top else Alignment.CenterVertically   ★ v136 改的「并集对齐」那一行
 Composer.kt:2261-2276           bandControls() = { controls() }（三个分支只换 local）
 Composer.kt:2070 / 1758-1843+   toolControls() 的每个孩子【直接发射进上面那个 Row】：AddMenuButton(1767) / Spacer(1785) /
                                Row{ weight(1f).horizontalScroll, Alignment.CenterVertically }(1790) / Spacer(1843) / Stop
 Composer.kt:2280-2417           Layout 的 measure policy
   :2319                         val band = minOf(BAND_HEIGHT.roundToPx(), constraints.maxHeight)
   :2323-2325                    toolRow = measurables[1].measure(maxHeight = band)
   :2326                         if (minimal) {                            ★ 本形态走这一支
   :2327-2334                      editor = measurables[0].measure(maxHeight = band)
   :2362-2365                      val editorTop  = composerBandChildTopPx(band, editor.height)   ★ v136 改的第二处
   :2366-2369                      val toolRowTop = composerBandChildTopPx(band, toolRow.height)   ★ v136 改的第二处
   :2370-2374                      val unionBottom = composerBandUnionBottomPx(band, editor, toolRow)  ★ v136 改的第三处
   :2375-2400                      layout { editor.place(0, editorTop); toolRow.place(0, toolRowTop)
                                          :2389 SessionComposerBandUnion.record(unionBottom, band) }
 Composer.kt:2745                BAND_HEIGHT = SessionPaneLayout.composerBandHeightDp(TOOL_ROW_HEIGHT = 44.dp)
 SessionPaneLayout.kt:150-156    composerBandHeightDp(toolRow, minInteractive) = maxOf(toolRow, minInteractive) = max(44,48) = 48.dp
 Composer.kt:2713                TOOL_ROW_HEIGHT = 44.dp
 Composer.kt:2758                MIN_TOUCH_TARGET = 48.dp
 SessionPaneLayout.kt:1370-1371  fun composerBandChildTopPx(b,c) = (b − c.coerceAtLeast(0)).coerceAtLeast(0) / 2   ★ 定义
 SessionPaneLayout.kt:1383-1389  fun composerBandUnionBottomPx(b,e,t) = maxOf(top(b,e)+e, top(b,t)+t)             ★ 定义
 SessionPaneLayout.kt:1842-1857  object SessionComposerBandUnion（只读点；**无任何日志**）
```

### 2.2 可达性判定：**可达**（不是死路径），且 `minimal == true` 有硬证据

v137 §5.3 的悬念是「`minimal` 在本形态是否真为 `false`」。**本轮从帧本身把它钉成 `true`**：

- `Composer.kt:2189` 的 `Modifier.height(BAND_HEIGHT)` **只存在于 `minimal` 分支**；
  堆叠形态是 `Composer.kt:2194` 的 `height(TOOL_ROW_HEIGHT)` = **44dp**。
- 帧里带高 **48**（`[214,262]` / `unionPx` 的基准），且编辑器与按钮**并排**
  （`EditText x∈[24,405]`、`Add x∈[399,441]`）⇒ **一行形态** ⇒ `minimal == true`。
- ⇒ `Composer.kt:2326` 的 `if (minimal)` 进入，**`:2362/2366/2370` 三行逐跳都在真实路径上**。

**否证尝试（两条都失败，故不选边）**：

| 否证假设 | 检查 | 结果 |
|---|---|---|
| 「`toolControls` 走了另一条 `Row`」 | 全仓 `horizontalScroll` 唯一命中 `Composer.kt:1794`；`ComposerArrangement(` 调用点唯一（`Composer.kt:2031`） | ❌ 不成立 |
| 「`:2210` 那一行没被编译进去」 | `Composer.kt` mtime `06:44:31` < `.class` `06:44:37` < `.dex` `06:54:26` < `app-debug.apk` `06:54:29` | ❌ 源码**在**构件里（详见 §5.3） |

---

## 3. 可达，但**可证是 no-op**：本形态两个子节点的实测高恒等于 `band`

这是 v136「改了却无效」的第一层原因，也是纯静态可证的部分。

```
工具行 toolRow 的高：  Composer.kt:2189 给那个 Row 挂 Modifier.height(BAND_HEIGHT)
                      ⇒ Modifier.height 把 min=max=48dp 传给自己的测量并对自己的结果收口
                      ⇒ Row 的布局高 = 48dp（与内容多高无关）
                      ⇒ 包它的 BoxWithConstraints 在 :2324 以 maxHeight = band 测量
                      ⇒ toolRow.height = 48 = band                       （band=48，见 :2319）
编辑器 editor 的高：   :2327-2334 以 maxHeight = band 测量，heightIn(min = if (minimal) 0.dp)(:2050-2057)
                      ⇒ 帧里 EditText h = 48 ⇒ editor.height = 48 = band
```

代入 v136 的新函数（`SessionPaneLayout.kt:1370-1371`，**整数除法**）：

```
composerBandChildTopPx(48, 48) = (48 − 48)/2 = 0        ← editorTop
composerBandChildTopPx(48, 48) = (48 − 48)/2 = 0        ← toolRowTop
composerBandUnionBottomPx(48, 48, 48) = max(0+48, 0+48) = 48
```

对照 v136 **修前**的代码（`POSITIONAL_1PX_FIX.md` §2.1(b) 的 diff 左侧）：

```
editorTop  = (band − editor.height).coerceAtLeast(0)/2 = (48 − 48)/2 = 0     ← 同
toolRowTop = 硬编码 0                                                            ← 同
unionPx    = maxOf(editor.height, toolRow.height) = 48                          ← 同
```

**⇒ 三行改动在本形态的输出与修前逐位相同。** 不是「被后写覆盖」，不是「被另一处 normalize 吃掉」，
而是**输入里 `childPx ≡ bandPx`，函数退化成常量**。v136 §2.1(b) 自己的注释其实已经写了
「带高 48、两个子节点都 48 高时两者同为 0，所以真机那 1px 不是这一行造成的」—— 这句话是对的，
问题在于**它同时把这三行算进了「已修 + 已钉」**（`POSITIONAL_1PX_FIX.md` §3 第 2/5 条 pin）。

**并且这产生了一个可测的矛盾**：模型说 `unionBottom = 48`（达标），真机说 `49`。
**模型比真机乐观 1px，而它唯一的求值点就是这两行。** 这条矛盾已写成绊线（§6 的寄存器用例）。

---

## 4. 真正决定那 1px 的代码

### 4.1 决定行：`Composer.kt:2210` 的 `verticalAlignment`

原文（当前源码）：

```kotlin
// Composer.kt:2210
verticalAlignment = if (minimal) Alignment.Top else Alignment.CenterVertically,
```

**算式**（逐位复算 v137 帧，全部对上）：

```
带顶沿 T = 214，带高 band = 48（:2319）

【控件的布局】Row 的孩子（Add / 横滚容器 / Stop / Model chip）实测高都是 21
   （帧内 HorizontalScrollView [441,228][1316,249] h=21、chip 内容 [441,228][559,249] h=21）
   居中偏移 = roundToInt((48 − 21)/2f) = roundToInt(13.5f) = 14          ← CenterVertically
   布局 y = 214 + 14 = 228      布局底沿 = 228 + 21 = 249                ✓ 帧内逐位吻合

【48dp 触摸靶】material3 强制的 minimumInteractiveComponentSize
   （本仓在 :32 import LocalMinimumInteractiveComponentSize，在 :2262-2276 读/覆盖它；
     SessionPaneLayout.kt:1396-1403 说明本仓没有任何 provides 48.dp ⇒ 48 由 material3 给）
   语义 bounds = 布局框向两侧撑到 48dp 并**居中**：
       extra = 48 − 21 = 27  →  上 13 / 下 14
       语义框 = [228 − 13, 249 + 14] = [215, 263]                        ✓ Add/Stop/Model 三个全中
   ⇒ 顶沿 215 = T + 1，就是 uiautomator 读到的那 1px

【编辑器】布局 48 高、editorTop = 0（§3）⇒ [214, 262]，48 ≥ 48 无需扩张
   ⇒ 语义框 = 布局框 = [214, 262]                                        ✓ EditText 逐位吻合

【合账】
   unionPx      = 263 − 214 = 49      （基线 48）        ← 缺陷本体
   topDelta     = 215 − 214 = +1
   criterion 1  = 263 − 261 = +2 = 1（并集对齐）+ 1（位置性越界：push 13 > padding 12）
```

**为什么 v136 的两行管不到它**：那两行摆的是 `toolRow`（**48 高的 Row 本身**，
`:2379-2380`），而这 1px 产生在**该 Row 的孩子内部**——21 高的布局 + 48dp 语义扩张。
`place()` 看不见自己孩子内部的语义扩张，**`composerBandChildTopPx` 也就没有任何项能表达它**。
这是「模型缺一项」而不是「模型算错」：v135 §1.6 说的结构性问题在这一层又出现了一次。

### 4.2 同一个 14 还有一个指纹，可用来判别是哪一支

帧里两个独立读数都等于 `T + 14`：
**（a）** 21 高的布局框在 `228 = T + 14`；
**（b）** 48 高的语义框顶沿在 `215 = T + 1`，而 `T + 1 = T + 14 − 13`（`extra/2` 的上半）。

`Alignment.Top.align(size, space)` 对每个孩子**恒返回 0**（`Alignment.Top` = `BiasAlignment(1f)`
的垂直 bias=0 分支），因此 **`Alignment.Top` 在物理上不可能产生 14**。
若 v136 的 `:2210` 生效，帧应当读成：横滚容器 `[441,214][1316,235]`、三个 48dp 语义框
`[·,201][·,249]`、`unionPx = max(262, 249) − 214 = 48`、`topDelta = 0`。
**帧不是这样。**

---

## 5. v136 为何无效（三层，逐层加码）

### 5.1 第一层：`composerBandChildTopPx` / `UnionBottomPx` 在本形态是死数（**已证**）

见 §3。`childPx ≡ bandPx` ⇒ 输出与修前逐位相同。**这两行即使装上了也不可能改变任何像素。**

### 5.2 第二层：唯一能改像素的那行（`Alignment.Top`）**不在那一帧的代码里**（**已证**）

`v137 §2` 并列了两个候选，本报告把两个都处理掉：

- **候选「归因行指错」⇒ 排除。** 决定 1px 的就是 `Composer.kt:2210`，v136 指对了**行**。
- **候选「构件归属」⇒ 成立，但形态与我最初的猜测不同。**

### 5.3 时间戳反查：**不是**「构建早于修复」——我最初猜错了，已自查推翻

我先看到 `app-debug.apk` 的 mtime `2026-10-04 06:54:29` 早于 v136 提交 `db2c991` 的
`06:54:30`（仅差 1 秒），一度判定「构件不含修复」。**这条推测是错的**：

```
Composer.kt        mtime = 2026-10-04 06:44:31   ← v136 改动的落盘时刻
*.class            mtime = 2026-10-04 06:44:37   ← 编译于改动之后
*.dex              mtime = 2026-10-04 06:54:26   ← dex 化于编译之后
app-debug.apk      mtime = 2026-10-04 06:54:29   ← 打包于 dex 之后
md5(app-debug.apk) = 9142F0EB68DEE9D607AA0AB8BFADC0A2   ← 与 v137 在真机上量的**逐位一致**
```

⇒ **这份 APK 确实含 v136 的源码**（提交发生在打包完成后 1 秒，只是提交动作晚了一步）。
这处自曝不抹。

**因此剩下的唯一解释是**：v137 那一帧**不是**由加载该构件的进程渲染的 —— 即
**装机/进程与构件的时间序**（安装到位前就起来的进程、或 dump 早于安装/重启），
而不是「源码没编进去」。这与 §4.2 的算式指纹**独立地**指向同一结论，两条证据互不依赖。

**诚实的边界**：静态分析能证明「那一帧的算式是 `CenterVertically` 分支的算式」，
**不能**证明「设备上的进程没装这份 APK」——那要一次带时间戳的装机复测（§8）。但两者的
**可执行结论相同**：**v136 的改动从未进入产生该帧的进程**，所以「v136 真机不生效」是事实，
而 v137 §3.3 把它标成「待重做」的机制归因**不需要再重做**——**归因是对的，错的是「已修已钉」**。

### 5.4 顺带发现（需裁定）：`Alignment.Top` 会把越界从底沿搬到**顶沿**

即使 `:2210` 真的生效，21 高的布局被贴到带顶沿 `214` 后，它的 48dp 语义框会变成
`[214 − 13, 214 + 21 + 14] = [201, 249]` ⇒ **触摸靶比带顶沿高出 13px**。
判据 ① 会达标（`unionPx = 48`），但**48dp 触摸靶越出带外 13px**（要么被宿主盒 `clip`，
要么压到上方队列条）。v136 只核了底沿，**没核这一侧**。这属于设计裁定，不在本轮授权内，
仅登记（§8-2）。

---

## 6. pin 降级 diff

**唯一改动的文件**：`app/src/test/java/com/xiufeigo/opencodeshell/ComposerBandUnionV136Test.kt`
**生产代码：零改动。** 未删除、未放宽、未改成恒真：既有 7 条断言**逐字未动**，
新增 1 条（用例数 7 → 8）。

```diff
--- a/app/src/test/java/com/xiufeigo/opencodeshell/ComposerBandUnionV136Test.kt
+++ b/app/src/test/java/com/xiufeigo/opencodeshell/ComposerBandUnionV136Test.kt
@@ imports @@
 import org.junit.Assert.assertEquals
+import org.junit.Assert.assertNotEquals
 import org.junit.Assert.assertTrue

@@ class KDoc 尾部追加（逐字保留原段，只在其后追加）@@
  * regression trigger** — if the real frame
  * moves, these numbers are the first thing that must be re-read.
+ *
+ * ---------------------------------------------------------------------------
+ * v138 REDOWNGRADE — **this class is a JVM model, NOT device truth.**
+ * ---------------------------------------------------------------------------
+ *  v136 claimed "the union-alignment 1 px is fixed" and pinned it here. **v137's
+ *  device run falsified the claim** (build md5 9142f0eb…, emulator-5554, 600 tier,
+ *  queue bar on screen): EditText [24,214][405,262] h=48; Add [399,215][441,263]
+ *  h=48; scroller [441,228][1316,249] h=21; unionPx 49; topDelta −1; criterion 1 +2.
+ *  ⇒ every assertion in this class describes a model the device does not exhibit.
+ *  They are kept — not relaxed, not deleted, not made trivially true.
+ *  v138's static finding: the two production functions ARE on the live path
+ *  (Composer.kt:2362/2366/2370 inside `if (minimal)` at :2326, and `minimal` is
+ *  provably true), yet they are **provable no-ops** here (childPx ≡ bandPx ≡ 48)
+ *  ⇒ the model answers 48 while the device answers 49. The 1 px is produced
+ *  **below** place(), by the material3 48 dp minimumInteractiveComponentSize
+ *  expanding a 21-high layout's *semantics* bounds — a term this model lacks.
+ *  The only line of the v136 diff that can move a pixel is
+ *  `verticalAlignment = if (minimal) Alignment.Top …` (Composer.kt:2210), and the
+ *  v137 frame carries the `Alignment.CenterVertically` fingerprint
+ *  (roundToInt((48−21)/2f) = 14; the scroller measured at 214+14 = 228).
+ *  `Alignment.Top` can only emit 0.

@@ class 末尾：新增第 8 条（原 7 条逐字未动）@@
+    @Test
+    fun theV137DeviceReadingsAreTheRegisterAndTheModelStillDisagreesWithThem() {
+        // ---- v137 真机读数，逐字来自 v137_BARON_M.xml，不得由模型重算 ----
+        val devPaneBottom = 261;  val devBandTop = 214;  val devBand = 48
+        val devEditorTop = 214;   val devEditorBottom = 262
+        val devControlTop = 215;  val devControlBottom = 263
+        val devScrollerTop = 228; val devScrollerHeight = 21
+
+        val devUnionBottom = maxOf(devEditorBottom, devControlBottom)
+        val devUnionPx    = devUnionBottom - devBandTop
+        val devTopDelta   = devControlTop - devEditorTop
+        val devCriterion1 = devUnionBottom - devPaneBottom
+
+        // ---- 寄存器：五条读数 ----
+        assertEquals("v137 device: the band top is the editor's top edge", devBandTop, devEditorTop)
+        assertEquals("v137 device: the 48 dp control bounds sit 1 px lower", 1, devTopDelta)
+        assertEquals("v137 device: unionPx is still 49, the 48 baseline is NOT met", 49, devUnionPx)
+        assertEquals("v137 device: union bottom 263 = max(262, 263)", 263, devUnionBottom)
+        assertEquals("v137 device: criterion 1 is still +2, not +1", 2, devCriterion1)
+        assertEquals("v137 device: the controls' own layout is 21 high inside a 48 band", 21, devScrollerHeight)
+
+        // ---- 指纹：这一帧是哪一支 alignment 画的 ----
+        // Alignment.CenterVertically 的偏移 = roundToInt((band − child)/2f)
+        val centredOffset = Math.round((devBand - devScrollerHeight) / 2.0).toInt()
+        assertEquals("the centring offset of a 21-high child in a 48 band", 14, centredOffset)
+        assertEquals("v137 device: the scroller's top IS bandTop + that centring offset",
+                     devScrollerTop, devBandTop + centredOffset)
+        assertNotEquals("`Alignment.Top` is not what rendered this frame: its offset is 0 " +
+                        "for every child, so the scroller would have measured 214, not 228",
+                        devBandTop + 0, devScrollerTop)
+
+        // ---- 绊线：模型与真机**仍然差 1** ----
+        val modelUnionBottom = composerBandUnionBottomPx(bandPx = devBand, editorPx = 48, toolRowPx = 48)
+        assertEquals("the model's answer for the arrangement's own (48, 48) pair", 48, modelUnionBottom)
+        assertEquals("and the model is STILL 1 px short of what the device reads — this is " +
+                     "the v138 redowngrade: the JVM pin is green while the frame reads 49. " +
+                     "When the device is fixed this must FAIL and force a re-read.",
+                     1, devUnionPx - modelUnionBottom)
+        assertEquals("the v136 'fix' is a provable no-op on this form: both children measure " +
+                     "exactly `band`, so the shared rule returns the same 0 the pre-v136 " +
+                     "hard-coded `toolRow.place(0, 0)` returned",
+                     0, composerBandChildTopPx(bandPx = devBand, childPx = devBand))
+    }
 }
```

**为什么这样降级**：

1. **不删** —— 删掉就把 v103–v136 的回归触发器一起删了，账会消失。
2. **不放宽** —— 既有 7 条断言（含 `theArchivedFrameStillReadsPlusTwo…` 的 `+2`）
   逐字未动；「真机已否认」这件事**写进类级文档**，让任何读者先读到它再读到绿。
3. **不恒真** —— 寄存器里的每个数都参与**派生一致性**断言（`devUnionPx`、`devTopDelta`、
   `devCriterion1`、`214 + 14 = 228`），改一个数而不改它的后果会红。
4. **新增绊线** —— 最后那条 `devUnionPx − modelUnionBottom == 1` 是**故意断言「两者不一致」**：
   真机修好的那天它必须**红**，逼人重读设备读数，而不是被改成一个恒过的 `assertEquals`。

---

## 7. 更强的防假绿方案（**只登记，等裁定，本轮未实施**）

| # | 方案 | 能挡住什么 | 代价 / 授权 |
|---|---|---|---|
| **A（我推荐）** | **让 pin 直接读设备读数文件**：在 `app/src/test/resources/` 放一份 `v137_BARON_M.xml`，或按现有 `source()` 辅助函数的相对路径惯例直接读 `research/v97_verify/5554_md/artifacts/v137_BARON_M.xml`；断言「模型答案 == 从 XML 解析出的真机答案」 | 正是本轮这种假绿：模型说 48、XML 说 49，JVM 立刻红。**这是唯一把真机读数纳入 JVM 循环的最小改动** | 只动测试；仓库已有「测试读生产源码文本」的先例，路径惯例现成 |
| **B** | **把 alignment 规则也变成生产函数**（像 `composerBandChildTopPx` 那样），让 pin 断言「一行形态对每个孩子高都返回 0」，而不是断言源码文本 | `:2210` 那条**源码文本** pin（`theOneRowFormAlignsControlsToTheTopEdge…`）是本次假绿的载体：它只证明「文本在位」 | **要动生产代码**（`Composer.kt` + `SessionPaneLayout.kt`），本轮未授权 |
| **C** | **给 `SessionComposerBandUnion.record()` 加一行 `Log.i`**（`SessionPaneLayout.kt:1853`），真机 logcat 直接读 `unionBottomPx / bandPx` | 区分「装机归属」vs「归因指错」——这就是 §5.3 缺的那条独立证据 | **1 行生产代码**，本轮未授权；但这是最便宜的决定性探针 |
| **D** | `@Ignore` + 写明原因 | 挡不住任何东西，还丢掉触发器 | **不建议**：会把「已登记的缺陷」变成「没人看的注释」，且用例数减少（违反硬约束） |
| **E** | 真机门禁：instrumented 断言 `SessionComposerBandUnion.unionBottomPx <= bandPx` | 真正闭环 | 需要装机授权，本轮无 |

**我的建议**：**A + C** 一起做（A 在测试侧、成本最低、直接把「模型 vs 真机」变红；
C 一行生产代码换来一条可复用的真机探针）。**B** 等 `:2210` 那条 pin 打算重做时再一起做。

---

## 8. 测试证据

**命令**（`JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`）：

```
.\gradlew :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ComposerBandUnionV136Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBarOnScreenCriterionV135Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerPaneBottomCriterionV108Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerChildUnionV104Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTouchTargetV105Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTallEnoughV106Test"
```

**原文（`_v138_run4.log` 尾部）**：

```
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 45s
52 actionable tasks: 2 executed, 50 up-to-date
```

**用例数（`app/build/test-results/testDebugUnitTest/*.xml` 逐类读出）**：

| 类 | 用例数 | 失败 | 错误 | 跳过 |
|---|---|---|---|---|
| `ComposerBandUnionV136Test`（**本轮 +1**） | **8** | 0 | 0 | 0 |
| `ComposerBarOnScreenCriterionV135Test` | 7 | 0 | 0 | 0 |
| `ComposerPaneBottomCriterionV108Test` | 9 | 0 | 0 | 0 |
| `ComposerChildUnionV104Test` | 7 | 0 | 0 | 0 |
| `ComposerBandTouchTargetV105Test` | 6 | 0 | 0 | 0 |
| `ComposerBandTallEnoughV106Test` | 7 | 0 | 0 | 0 |
| **合计** | **44**（v136 基线 43，**净增 1**） | **0** | 0 | 0 |

`ComposerBandUnionV136Test` 的 8 条用例名（从结果 XML 读出，确认新用例**真跑**、非跳过）：

```
eachBandChildIsPlacedByOneRuleAndNeverExceedsTheBand
theUnionBottomReturnsToTheBandBaselineForTheDevicePair
theUnionBottomIsBoundedByTheBandForEveryAdmissiblePair
theOneRowFormAlignsControlsToTheTopEdgeAndTheStackedFormDoesNot
bothBandChildrenArePlacedThroughTheSharedProductionFunction
theTouchTargetIsStillFortyEightDpOnAPixel
theArchivedFrameStillReadsPlusTwoSoTheNextDeviceRunIsTriggered
theV137DeviceReadingsAreTheRegisterAndTheModelStillDisagreesWithThem   ← 新增
```

**合规**：只跑 `--tests`；未 `assembleDebug` / `clean` / 全量 / 装机 / 杀进程；
未放宽或删除任何既有断言；用例数未减少；**生产代码零改动**。

**过程里两次真实红灯，未抹**：
1. `_v138_run1.log` / `_v138_run2.log` / `_v138_run3.log`：连续三次
   `compileDebugUnitTestKotlin FAILED` —— `kotlin.math.roundToInt` 全限定调用不被接受、
   `Int / Float` 的接收者被解析成 `Double`。最终改用无歧义的 `Math.round(… / 2.0).toInt()`。

---

## 9. 未闭环（含「需要什么真机读数 / 探针」）

1. **装机/进程的时间序仍未直接证实**（§5.3）。静态侧只能证「帧是 `CenterVertically` 算式」。
   **需要**：(a) 一次带时间戳的装机 + 重启 + 同形态重 dump（比对 md5 与 dump 时刻）；
   (b) **C 方案的探针** —— 给 `SessionComposerBandUnion.record()`（`SessionPaneLayout.kt:1853`）
   加一行 `Log.i("BandUnion", "unionBottomPx=… bandPx=…")`，logcat 直接读。
   有了 (b)，真机一眼就能区分「改动在位但读数不变」与「改动不在进程里」。
2. **`Alignment.Top` 的顶沿越界 13px 未裁定**（§5.4）。**需要**：把 48dp 触摸靶的越界方向
   写成一条判据（现在只有底沿判据 ①），否则修好底沿就会换来顶沿裁切。**属设计裁定，待 Lead。**
3. **第二个 1px（位置性越界）原样留账**：`push 13 > padding 12`（`SessionScreen.kt:1827` 与
   `queueBarCeiling`），本轮未动、归因不变。判据 ① 在本形态仍不成立（`+2`）。
4. **`SessionComposerBandUnion` 的只读点仍然只在 JVM 里可达**（`SessionPaneLayout.kt:1842`
   的 `@Volatile` 字段，无出口）—— 它宣称「让摆位回归对 JVM 可见」，但它**没有记进任何日志**，
   设备侧拿不到。这正是 §7-C 建议的那一行。
5. **缩窗往返 / 600 条关 `readable 51` / 768 档 `140·92`**：本轮只跑 JVM pin，**未上真机**。
6. **真机 21 高布局框与 48 高语义框成对的数据只有 600 档条在屏这一帧**。768 档、两档条关形态
   是否同源（同样靠 48dp 扩张造出 1px）**未取样**，不应假定。

---

## 10. 改了哪些文件

| 文件 | 性质 |
|---|---|
| `app/src/test/java/com/xiufeigo/opencodeshell/ComposerBandUnionV136Test.kt` | 测试：+1 import、类级 v138 降级横幅、**+1** 条真机读数寄存器用例（原 7 条断言逐字未动） |
| `research/v97_fix/UNION_1PX_NOT_EFFECTIVE.md` | 本报告（先写盘抢锁 → 定稿释放） |
| `research/v97_fix/_v138_run1.log` … `_v138_run4.log` | 运行日志（含 3 次真实红灯） |

**未触碰**：任何生产代码（`Composer.kt` / `SessionPaneLayout.kt` **零改动**）、
`_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers/**`、`ui/home/**`、
`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`。
**行尾**：全程只用编辑/写入工具，未用 `git checkout` / `git restore`。
