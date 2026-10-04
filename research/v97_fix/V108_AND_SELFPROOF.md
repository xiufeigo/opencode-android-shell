# V108_AND_SELFPROOF — v150：V108 pin 的结构性弱点 + 自证循环取证

状态：**任务 A 已落地 · 任务 B 只取证（零生产改动）** · 日期：2026-10-04
承接：v123（`PANE_ENTITY_FIX.md`）· v125/v126（IME epoch 统一）· v97_fix/V108

> 锁约定沿用 `PIN_DEVICE_HARDLINK.md:6-7`（仓库无独立 `*.lock`，**本报告文件即锁**）。
> 抢锁时刻 = 本文件首次写入（10:2x，早于第一次 gradle）。
> Gradle 锁 `.agent-gradle-lock`：`.agent-gradle-lock` 不存在 → 写锁（`LOCK_ACQUIRED`）
> → 全部 gradle **前台**跑完 → 释放（`LOCK_RELEASED=True` 复核）。
>
> 授权纪律：只跑 `:app:testDebugUnitTest --tests "<类名>"`；
> **未** `assembleDebug` / `clean` / 全量 / 装机；`JAVA_HOME` = Android Studio JBR；
> 行尾全程只用编辑/写入工具（`core.autocrlf=false`，未用 `git checkout`/`restore`）。
> `git status --porcelain -- app/src/main` = **0 行**（本轮生产代码零改动）。

---

## 1. 任务 A：V108 那条 pin 的结构性弱点

### A.0 弱点复现（**本轮核心证据**）

只改一处：把 `fourSixSixIsNotAValueTheProductionImePathCanProduce` 里
`believedImeBottomPx(...)` 的 `imeBottomPx` 实参从字段 `imeBottom768`（426）
就地改成字面量 `474`，**其余一字不动**（同一个类里其它用例仍读 426）。

```
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 3s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=0
```

逐例结果（`app/build/test-results/.../TEST-...V108Test.xml`）：

```
tests=9 failures=0 errors=0 skipped=0
  theSevenSixEightTierPassesTheNewCriterionWithSixPxToSpare  (0.039s)
  criterionTwoUnionPxAtMostBandHoldsInBothTiers  (0.005s)
  theOldHostBottomNumbersAreRetainedAsObservationsOnly  (0.004s)
  theCriterionOneTripwireDetectsBothOldBehaviours  (0.016s)
  theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic  (0.015s)
  fourSixSixIsNotAValueTheProductionImePathCanProduce  (0.002s)   <-- 426 换成 474，仍然 PASS
  theSixHundredTierImePathContributesExactlyZeroError  (0.002s)
  theNavigationBarFloorIsReadOnTheSevenSixtyEightTierAndTheSixHundredTierIsInertToIt  (0.002s)
  theImeShortfallTermIsAThreeHundredSixtyEightOnlyTermAndIsZeroOnTheSixHundredTier  (0.002s)
```

**结论（定性）**：`426 → 474` 之后**该断言整体仍绿**。它只是消息文本换了，
**结构上不构成判据**。日志：`research/v97_fix/_v150_probe.log`。

> 这次变异是**故意**的、且已还原（`imeBottomPx = imeBottom768`）。生产代码全程未动。

### A.1 生产侧到底有没有区分度？——**没有，且是结构性的**

钳位实现（`SessionPaneLayout.kt:1574-1587`，逐行）：

```
1574  fun believedImeBottomPx(visibleBoxPx, floorPx, imeBottomPx, essentialPx): Int {
1580    if (imeBottomPx <= 0) return imeBottomPx
1581    if (visibleBoxPx <= 0 || essentialPx <= 0) return imeBottomPx
1582    if (visibleBoxPx < essentialPx * PANE_ESSENTIAL_SPARE_ROOM) return imeBottomPx   // 早退
1583    val claimedPx = (imeBottomPx - floorPx.coerceAtLeast(0)).coerceAtLeast(0)
1584    val roomPx = visibleBoxPx - essentialPx
1585    if (claimedPx <= roomPx) return imeBottomPx        // 恒等分支（单射）
1586    return roomPx + floorPx.coerceAtLeast(0)          // 饱和分支（常数）
1587  }
```

在 768 档（`paneBoxPx=606`、`essentialPx=284`、`floorPx=48`）：

| 读数 | claimed(1583) | room(1584) | 1585 分支 | 返回 | 推导自 |
|---|---|---|---|---|---|
| 300 | 252 | 322 | 走恒等 | **300** | `imeBottomPx` |
| 370 | 322 | 322 | 走恒等 | **370** | `imeBottomPx`（最后一个单射读数） |
| 371 | 323 | 322 | 走饱和 | **370** | `roomPx + floorPx` |
| **426**（实测） | 378 | 322 | 走饱和 | **370** | `roomPx + floorPx` |
| **474**（退役） | 426 | 322 | 走饱和 | **370** | `roomPx + floorPx` |
| 522 | 474 | 322 | 走饱和 | **370** | `roomPx + floorPx` |
| 768 | 720 | 322 | 走饱和 | **370** | `roomPx + floorPx` |

（本表不是推断，是 A.2 新判据 `theProductionImePathIsSaturating…` 真跑出来的断言值，
且与独立探针 `research/v97_fix/_v150_probe_selfproof.txt` 的 `606,*` 行逐行一致。）

**裁定：426 与 474 在生产语义上等价。** 原因是 1586 行是一个**饱和**（钳位到上界）
的返回：任何超过 `roomPx` 的 claim 都被压回同一个 `roomPx + floorPx`。
两个读数都深在饱和区（426 − 370 = 56，474 − 370 = 104），所以
**任何针对 `believedImeBottomPx` 返回值的断言都不可能区分它们**——
这不是这条测试写得不好，是**该函数在饱和区没有信息量**。

> 因此按任务书「若生产本身无区分度 ⇒ 不要硬造」的分支处置，**不硬造区分度**。
> 下游同样无区分度：`SessionScreen.kt:2370-2372` 的
> `paneBottomDp = maxOf(paneFloorPx, believedImeBottomPx)`、
> `:2384` 的 `bodyHeightDp = (maxHeight - paneBottomDp)`、
> `:2385-2388` 的 `composerOneRow(...)` —— 全链路对 426/474 输出完全相同
> （探针 `606,426 -> 370,236,false` 与 `606,474 -> 370,236,false` 逐字相同）。

### A.2 处置：**等价 ⇒ 改为显式声明 + 另补两条真判据**

**（1）原测试改为显式声明。** 原名
`fourSixSixIsNotAValueTheProductionImePathCanProduce` **改为**
`theRetiredReadingsAreRecordedAsTextOnlyAndTheProductionImePathCannotTellThemApart`。
> ⚠️ **改名需你确认**：这是本轮唯一的"删/改既有断言"面。
> 但**断言一条未松、一条未删**——v126 在该方法里的 4 条
> （`370` 两次、`assertNotEquals(322)`、`assertNotEquals(466)`）**逐字保留**，
> 只是上方补了"本方法不承担区分职责"的显式声明，并**新增**一条把
> 「等价」这个事实钉成断言：

```
assertEquals(
    "426 and 474 are the same value to production: the clamp saturates, " +
        "so this class's return-value assertions carry no reading-epoch duty",
    believedImeBottomPx(..., imeBottomPx = 426, ...),
    believedImeBottomPx(..., imeBottomPx = 474, ...),
)
```

这条的意义：把"A.0 的弱点"从报告里的一句**主张**变成**可回归的 pin**——
若哪天钳位不再饱和（有人让它对原始读数敏感），它会**变红**，提醒重新裁定。

**（2）新判据 #1 — 饱和边界**（`theProductionImePathIsSaturatingSoFourTwoSixAndFourSevenFourAreOneEquivalenceClass`）：
钉 `roomPx = 322`、边界 `roomPx + floorPx = 370`、恒等分支**确实可达**（300 → 300、370 → 370）、
饱和分支对 `371/426/474/522/768` 一律 370。它对"钳位语义"可判定：
`PANE_ESSENTIAL_HEIGHT` 或 `:1586` 的 `+ floorPx` 一改，**必红**。

**（3）新判据 #2 — 取值来源**（`theRawPlatformImeInsetIsTheOnlyImeSourceTheSessionPaneReads`）：
这是**生产里 426 与 474 真正不同**的唯一地方——不是"读到多少"，而是"读的是什么"。
钉 `SessionScreen.kt:2362`（floor 取平台 `navigationBars`）、`:2363`（仅 titlebarBottom 形态置 0）、
`:2367`（`imeBottomPx = WindowInsets.ime.getBottom(density)`，**原样**交给钳位），
且该原样读取**全文件恰好一次**。
> 判别式不是子串匹配：退役 epoch 的源码里原样读取是参数的**前缀**
> （`…getBottom(density) + navBottomPx`），子串测试会放它过关。
> 边界取 **Kotlin 表达式的结尾**（其后只能是 `,` 或 `)`），
> 所以带算术的参数会被判不合格。

### A.3 反向验证（**真跑抄原文**）

反向口径 = 把 `imeBottom768` 改回退役的 `474`：

```
> Task :app:testDebugUnitTest FAILED

BUILD FAILED in 3s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=1
tests=11 failures=2

FAILED: theProductionImePathIsSaturatingSoFourTwoSixAndFourSevenFourAreOneEquivalenceClass
   java.lang.AssertionError: so 426 and 474 are indistinguishable *to production*,
       by 48 = one nav bar expected:<48> but was:<0>

FAILED: theImeShortfallTermIsAThreeHundredSixtyEightOnlyTermAndIsZeroOnTheSixHundredTier
   java.lang.AssertionError: 768 has 40px to spare, not a shortfall:
       140 ? (606 ? 426) = ?40 expected:<-40> but was:<8>
```

（XML 里 `?` 是 `−` 的编码残迹，原文为 `140 − (606 − 426) = −40`。
第二例是 v125 既有的 shortfall 用例，它本来就能分辨——**新增判据 #1 也能分辨**，
这正是与 A.0 那条的区别。）

新判据 #2 的反向验证在**测试内 seam** 上完成、不碰生产文件：
把 `imeBottomPx = WindowInsets.ime.getBottom(density)` 替换成
`… + navBottomPx`（即 426+48=474 那个形状）后，同一条判据
`rawImeReads(...)` 从 `1` 变 `0` —— **判不合格**。

---

## 2. 任务 B：自证循环取证（**只取证，未改生产**）

对象：`SessionScreen.kt:2385-2389`。
承接 v123 的判定：**用钳位自己的输出去判形态**。

### B.1 数据流逐跳（`文件:行号`）

```
① SessionScreen.kt:2328   BoxWithConstraints(Modifier.fillMaxSize())      约束源
② SessionScreen.kt:2350-2353  .windowInsetsPadding(safeDrawing.only(Top+Horizontal))
                             —— 注意：IME 半边**不在**这里
③ SessionScreen.kt:2361   val paneBoxPx = with(density){ maxHeight.roundToPx() }
                             盒高（v77: 键盘已放回），**不含 IME**、**不含导航栏**
④ SessionScreen.kt:2362   val navBottomPx = WindowInsets.navigationBars.getBottom(density)
⑤ SessionScreen.kt:2363   val paneFloorPx = if (titlebarBottom && !desktop) 0 else navBottomPx
⑥ SessionScreen.kt:2364-2369  believedImeBottomPx = SessionPaneLayout.believedImeBottomPx(
                                 visibleBoxPx = paneBoxPx,      <-- ③ 的输出当输入
                                 floorPx = paneFloorPx, imeBottomPx = WindowInsets.ime.getBottom(density),
                                 essentialPx = PANE_ESSENTIAL_HEIGHT)
                             ★ 钳位在这里发生（SessionPaneLayout.kt:1574-1587）
⑦ SessionScreen.kt:2370-2372  val paneBottomDp = maxOf(paneFloorPx, believedImeBottomPx).toDp()
                             ★ ⑥ 的**输出**成为下一步的被减数
⑧ SessionScreen.kt:2384   val bodyHeightDp = (maxHeight - paneBottomDp).coerceAtLeast(0.dp)
                             ★ 用**③ 同一个** maxHeight 减掉**⑥/⑦ 的输出**
⑨ SessionScreen.kt:2385-2388 val bodyOneRow = SessionPaneLayout.composerOneRow(
                                 ShellChromeBudget.isShortWindow(windowHeightDp), bodyHeightDp)
                             -> SessionPaneLayout.kt:1039-1040 -> :1008-1010 composerMinimal(body)
⑩ SessionScreen.kt:2389   if (bodyOneRow != minimalComposer) minimalComposer = bodyOneRow   ← 写状态
⑪ SessionScreen.kt:941-943  var minimalComposer by remember(windowHeightDp){ mutableStateOf(...) }
⑫ SessionScreen.kt:1870   Composer(minimal = minimalComposer)        形态生效
⑬ SessionScreen.kt:1895-1898 queueBarCeiling(paneHeight, composerFloor = if (minimalComposer) 56 else 116)
⑭ SessionScreen.kt:2395-2408 chromeMaxHeight = remember(bodyHeightDp, titlebarBottom, composerHeight)
                             （composerHeight ← :949 ← :906 ← :1812 onSizeChanged）
```

**它确实是自证**：`⑥` 用 `③` 的 `maxHeight` 作输入，`⑧` 又用**同一个** `maxHeight`
减掉 `⑥` 的**输出**，`⑨` 再拿这个差去判形态。
**判形态所用的"可用房高"，是用被测钳位自己的输出去定义的。**
输出回合成为输入——这一跳是实打实的。

### B.2 风险：**有一处实测到的反向跳变；震荡没有证据**

用一次**一次性探针**（跑完已删，数据留档
`research/v97_fix/_v150_probe_selfproof.txt`）把 ①②③⑥⑦⑧⑨ 整条链
用**生产函数**原样复算，阈值 `minimalThreshold = 56+44+64 = 164`：

```
PROBE essentialPx=284 floor=48 minimalThreshold=164.0
PROBE box,ime -> believed,bodyHeightDp,oneRow
PROBE 606,333 -> 333,273,false      恒等分支
PROBE 606,371 -> 370,236,false      饱和分支
PROBE 606,426 -> 370,236,false      饱和分支   <-- 实测 768 档
PROBE 606,474 -> 370,236,false      饱和分支   <-- 退役 epoch，逐字相同
PROBE 700,426 -> 426,274,false      恒等分支（box 够大就不饱和）
PROBE 700,474 -> 464,236,false
PROBE 438,274 -> 274,164,false
PROBE 438,300 -> 300,138,true       <-- oneRow 变 TRUE
PROBE 438,426 -> 426,12,true        <-- oneRow 仍 TRUE，body 只剩 12
PROBE 438,474 -> 474,0,false        <-- oneRow 翻回 FALSE！body 归 0
PROBE 438,522 -> 522,0,false
```

**（a）自证在饱和区是"惰性"的——不是 bug，但是真异味。**
饱和区里 `bodyHeightDp ≡ essentialPx − floorPx = 236`，
在 box=568/606/700/1000 上**逐个相等**（表里全是 236）。
即：**钳位生效的整个区域里，它的输出对形态决策的贡献恒为 236**，
既与键盘高度无关，也与窗口大小无关。代码"看起来"是键盘在说话，
实际那里键盘根本没说话。这是自证最诚实的一句描述。

**（b）实测到的真风险：形态随键盘变高而"变回大形态"（反向跳变）。**
`438` 就是 600 档实测的 `paneBox600`。`SessionPaneLayout.kt:1582` 的早退
（`visibleBoxPx < essentialPx*2 = 568`）让钳位在 438 上**完全不生效**，
于是 `body = 438 − ime`，一路掉到 0。而 `composerMinimal`
（`SessionPaneLayout.kt:1008-1010`）的头一句是 `available > 0.dp`：
**body 归 0 时 `composerMinimal` 返回 false** ——于是形态从"一行"**翻回"完整"**。
实测两行直接对照：

```
PROBE 438,426 -> 426,12,true     键盘 426：body 12，一行形态
PROBE 438,474 -> 474,0,false     键盘 474：body 0，**完整形态**（需要 116dp）
```

**键盘更高 ⇒ 形态更大，而可用房高是 0** ——方向是反的。
600 档实测读数 `imeBottom600 = 387` 落在 `body = 51 > 0` 一侧（安全），
但离翻转点 `ime = 438` 只剩 **51 px**：任何让 600 档 IME 读数多报 51 px 的情况
（更高的输入法、联想条浮出、v110 那种"少扣一根导航栏"的错读）
都会让 body 掉到 0 并触发这个反向跳变。

**（c）震荡（逐帧来回）：没有证据，本轮也不主张。**
`③` 的 `maxHeight` 是 `BoxWithConstraints` 的**约束**（`:2328-2330` `fillMaxSize`），
IME 半边根本没进 `:2350-2353` 的 `safeDrawing`（只有 Top+Horizontal），
composer 本身是 `BottomCenter` 浮层（`:3195-3198`）。
所以**形态的高度不会回头改变 `maxHeight`**，③ 这条链在顶端是**断开**的，
没有闭合回路可以逐帧震荡。v74 已在 `:2376-2378` 写明这个意图，
本次取证**支持**该注释。

**（d）滞后一帧：存在，但不在钳位这条链上。**
真正晚一帧的是两个**测量量**：`composerHeightPx`（`:906` ← `:1812`）与
`paneHeightPx`（`:915` ← `:2116`）。它们经 `:951/:949` 喂给
`composerCompact(paneHeight)`（`:1866`）和 `queueBarCeiling`（`:1893`）——
**这两处形态/预算确实读的是晚一帧的测量值**。
生产自己在 `:3266-3274` 已记录过这一类问题（"两个数字来自两个时刻"、
`declaredFloorPx` 比测量晚一帧）并做了绕行。
本轮**只登记，不动**。

### B.3 方案（**不实施**）

**最小改法 = 让 B.2(a) 那条"惰性不变量"显式化，不改行为。**
因为饱和区里 `bodyHeightDp` 恒等于 `essentialPx − floorPx`，
把"减钳位输出"改写成"直接写这个不变量"，**是纯粹的等价变换**：

```diff
--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt
@@ -2384,7 +2384,16 @@
-        val bodyHeightDp = (maxHeight - paneBottomDp).coerceAtLeast(0.dp)
+        // v150: 形态判据不再用钳位自己的输出反推房高。
+        // 钳位生效时（SessionPaneLayout.kt:1586）返回 roomPx + floorPx，
+        // 于是 maxHeight − paneBottomDp ≡ essentialPx − floorPx —— 与 ime、与
+        // maxHeight 都无关，是一个常数。把它写出来，形态决策就不再"看起来"
+        // 是在听键盘说话。
+        val bodyHeightDp = if (believedImeBottomPx < with(density) { WindowInsets.ime.getBottom(density) }) {
+            (maxHeight - paneBottomDp).coerceAtLeast(0.dp)   // 钳位未生效：用真实余量
+        } else {
+            with(density) {                                   // 钳位生效：直接用不变量
+                (maxHeight - SessionPaneLayout.PANE_ESSENTIAL_HEIGHT + paneFloorPx)
+                    .coerceAtLeast(0.dp)
+            }
+        }
         val bodyOneRow = SessionPaneLayout.composerOneRow(
             ShellChromeBudget.isShortWindow(windowHeightDp),
             bodyHeightDp,
```

**影响面**：`bodyHeightDp` 的两个下游 —— `:2395` `chromeMaxHeight` 的 remember key、
`:2400/2405` `chromeMaxHeight`/`chromeMaxHeightForComposer`。形态 `bodyOneRow` 本身不变。

**要重述的 pin**：
- `SessionPaneImeBeliefV77Test`（钳位语义本身，未动，但需复跑）；
- 任何钉 `bodyHeightDp` 字面值的类（改前需 grep 全量对账）。

**反向验证要点**：新增一条**网格等价**测试——对
`(box, ime)` 网格逐点断言"新写法 == 旧写法"，在饱和区与恒等区都要覆盖。
只要有一条不等价，方案作废。

**为何不会触发 v77 的 P0**：v77 P0（`wm size/density` 复位后键盘 inset 陈旧、
836dp 把 body 压到 209dp、丢工具栏）的防线在 **钳位**（`:1574-1587`）本身，
不在本行。上面的改写是**恒等变换**（饱和区两边都等于 236），
陈旧 836dp 读数仍被钳到同一数值 ⇒ **丢工具栏的行为逐字不变**。
且新写法不新增对 `WindowInsets.ime` 的读取（只是把已在 `:2367` 读到的
`imeBottomPx` 存成局部变量复用），不增加 inset 订阅点。

**⚠️ 明确不包含**：B.2(b) 那个 `438` 反向跳变**不在本方案内**。
它属于 `composerMinimal` 的 `available > 0.dp` 守卫（`SessionPaneLayout.kt:1009`），
改它会动 v55/v57 一整条形态阶梯，**必须单独裁定 + 真机复验**，不能顺手带上。

**需要真机复验的步骤**（无真机不能定案）：
1. 改写后 1366x768 / 1366x600 两档的**工具栏与 tab 条必须在位**（v77 P0 回归）；
2. 键盘开/关过程中形态**不得反向跳**（现测到的 `438` 现象，600 档离翻转点仅 51px）；
3. 缩窗/分屏/旋转/IME 开关连打，确认**无新增一帧滞后**（B.2(d)）。

### B.4 「我裁定后可以怎么落地」清单

1. **裁定 A-1**：原测试改名是否接受（断言未松未删，只加声明）——**要你点头**。
2. **裁定 A-2**：是否接受"生产无区分度 ⇒ 该量不是判据"这一结论
   （若你认为应改生产让钳位保留读数信息，那是**改生产**，本轮授权外）。
3. **裁定 B-1**：是否要我实施 B.3 的等价改写（需你放行生产代码写权限）。
4. **裁定 B-2**：`438` 反向跳变单独立项，还是先只登记（我的建议：**单独立项**，
   它是真缺陷，且改 `available > 0.dp` 会牵动 v55/v57 阶梯）。
5. **裁定 B-3**：真机复验由谁跑（本轮**未装机**）。
6. 若 1–3 通过，下一轮：实施 + 跑网格等价测试 + 上述 3 项真机复验。

---

## 3. 测试证据

命令（全程前台，均在 `.agent-gradle-lock` 内）：

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ComposerPaneBottomCriterionV108Test" --console=plain
```

| # | 轮次 | 结果 | 日志 |
|---|---|---|---|
| 1 | **A.0 弱点复现**（实参改 474） | `BUILD SUCCESSFUL in 3s` · **9 例 / 0 失败** | `_v150_probe.log` |
| 2 | 新判据首跑 | `BUILD FAILED in 3s` · 11 例 / 1 失败（**我的正则写错**，见下） | — |
| 3 | 改正则后 | `BUILD FAILED in 4s` · 11 例 / 1 失败（**我的前瞻写错**，见下） | — |
| 4 | **新判据绿** | `BUILD SUCCESSFUL in 3s` · **11 例 / 0 失败 / 0 error / 0 skip** | `_v150_green.log` |
| 5 | **A.3 反向验证**（口径回 474） | `BUILD FAILED in 3s` · 11 例 / **2 失败** | `_v150_reverse.log` |
| 6 | 还原 426 | `BUILD SUCCESSFUL in 3s` · 11 例 / 0 失败 | `_v150_final.log` |
| 7 | B 自证探针（一次性，已删） | `BUILD SUCCESSFUL in 3s` · 1 例 · 数据落 `_v150_probe_selfproof.txt` | — |

**交付态（第 6 轮，最终）原文**：

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 3s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=0
FINAL: tests=11 failures=0 errors=0 skipped=0
```

**11 例逐条（全绿）**：

```
[OK] theRetiredReadingsAreRecordedAsTextOnlyAndTheProductionImePathCannotTellThemApart
[OK] theSevenSixEightTierPassesTheNewCriterionWithSixPxToSpare
[OK] criterionTwoUnionPxAtMostBandHoldsInBothTiers
[OK] theProductionImePathIsSaturatingSoFourTwoSixAndFourSevenFourAreOneEquivalenceClass
[OK] theOldHostBottomNumbersAreRetainedAsObservationsOnly
[OK] theCriterionOneTripwireDetectsBothOldBehaviours
[OK] theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic
[OK] theRawPlatformImeInsetIsTheOnlyImeSourceTheSessionPaneReads
[OK] theSixHundredTierImePathContributesExactlyZeroError
[OK] theNavigationBarFloorIsReadOnTheSevenSixtyEightTierAndTheSixHundredTierIsInertToIt
[OK] theImeShortfallTermIsAThreeHundredSixtyEightOnlyTermAndIsZeroOnTheSixHundredTier
```

**本轮 2 次红都是我自己写错的，诚实记录**（不是生产问题）：
- 第 2 轮：把 `fun believedImeBottomPx(` 写进了 `SessionScreen.kt` 的判据，
  但该文件只有**调用点**没有定义 → 断言误报。改为不带 `fun\s+`。
- 第 3 轮：`rawImeReads` 原先用 `(?![A-Za-z0-9_.(])`，但 `) + navBottomPx`
  紧跟的是**空格**不是运算符，前瞻被绕过 → 退役 epoch 被误判为合格。
  改为**表达式结尾**边界（其后只能是 `,` 或 `)`）。

**锁**：`.agent-gradle-lock` 写锁（`LOCK_ACQUIRED`）→ 7 轮全部**前台**跑完
→ 释放用**单条顶层** `rm -- "<绝对路径>"`（`Remove-Item` 在本机被硬安全策略
拦截，**未绕过**，交由运行时路由至可恢复删除启动器，输出逐字
`mavis-trash: moved to trash: '…\.agent-gradle-lock'`），释放后 `Test-Path` 复核 `False`。

**行尾**：全程只用编辑/写入工具；`core.autocrlf=false`；未用 `git checkout`/`restore`。
测试文件仍为 **LF**（`CRLF=0 LF-only=533`，本轮编辑后仍为纯 LF）。

---

## 4. 未闭环 / 需你裁定

| # | 事项 | 状态 |
|---|---|---|
| 1 | 原测试**改名**是否接受（断言未松未删） | **待裁定** |
| 2 | 「生产无区分度 ⇒ 该量不是判据」这一结论 | **待裁定** |
| 3 | B.3 等价改写是否实施（需生产写权限） | **待裁定**，本轮**未实施** |
| 4 | `438` 反向跳变（`available > 0.dp` 守卫） | **建议单独立项**，本轮只取证 |
| 5 | B.2(c) 震荡 | **无证据**，本轮**不主张**存在 |
| 6 | B.2(d) 测量量晚一帧（`:906`/`:915`） | 只登记，未动 |
| 7 | 真机复验 3 项 | **未做**（本轮禁装机） |
| 8 | 钉 `bodyHeightDp` 字面值的其它测试类对账 | **未做**（若裁定实施 B.3 才需要） |
| 9 | 新判据 #2 的源码 pin 随 `SessionScreen.kt` 漂移的风险 | 沿用本仓既有做法（`regionPaddingDp` 同样钉源码行），**已知**、**接受** |

## 5. 改动清单

| 文件 | 性质 |
|---|---|
| `app/src/test/java/com/xiufeigo/opencodeshell/ComposerPaneBottomCriterionV108Test.kt` | **测试（改）**：1 个方法改名 + 补显式声明；新增 2 个用例、1 个 helper。既有断言**未松未删** |
| `research/v97_fix/V108_AND_SELFPROOF.md` | 报告（本文件，先写盘抢锁） |
| `research/v97_fix/_v150_probe.log` | A.0 弱点复现日志 |
| `research/v97_fix/_v150_green.log` / `_v150_reverse.log` / `_v150_final.log` | 绿 / 反向红 / 终态日志 |
| `research/v97_fix/_v150_probe_selfproof.txt` | B 自证链实测数据（探针已删，数据留档） |
| `app/src/main/**` | **生产代码：0 改动**（`git status --porcelain -- app/src/main` = 0 行） |
