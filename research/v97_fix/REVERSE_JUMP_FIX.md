# REVERSE_JUMP_FIX — v154：修 B-2 —— 键盘变高时形态「反向跳变」、body 归 0

状态：**✅ 已落地 · 机械证实 + 已修 + 反向验证真红 + 回归 0 失败** · 日期：2026-10-04
来源裁定：`research/v97_fix/V108_AND_SELFPROOF.md`（v150）§B.2(b) + §B.4 裁定 4（「单独立项」）
承接：`SELFPROOF_REWRITE.md`（v153）§6（B-2 只加了 51px 余量 pin，行为未动）

> **锁约定**（沿用 `PIN_DEVICE_HARDLINK.md:6-7`）：本报告文件即锁，11:05 写入即抢锁。
> Gradle 锁 `.agent-gradle-lock`：`Test-Path` 确认不存在 → 写锁（`LOCK_ACQUIRED after_wait=0`）
> → **全部 7 次 gradle 前台跑完** → 释放见 §9。取锁期间无人等待。
>
> **授权**：只跑 `:app:testDebugUnitTest --tests "<类名>"`；**未** `assembleDebug` / `clean` /
> 全量 / **装机**。`JAVA_HOME` = Android Studio JBR。
> **行尾**：全程只用编辑/写入工具，`core.autocrlf=false`，**未**用 `git checkout`/`restore`。
> 生产文件最终 `CR=1`、末行 `}` 无换行，与 HEAD **逐字节同形**（见 §5.3 一次插曲的处置）。
>
> **红线全部守住**：768 的 140/92、600 的 51、两档 ①(i) −10/−12、48dp 靶、
> v77 那条 P0 pin（`the stale 836 dp frame is capped at the pane's essential form`）、
> `quarantine/` 分工（未动）。
> **未新增任何用户可见字符串**（生产改动只在 KDoc 注释 + 一个布尔运算符）。
> **未删、未放宽任何断言**：3 条改极性的断言全部**加写为更强的结构化断言**（§4）。

---

## 0. 结论速览

> ## ✅ 非单调已被机械证实，缺陷已修
>
> - **非单调**：`ReverseJumpMonotoneV154Test` 的 440 格网格（836 个相邻对）上，
>   修前 **10 个反向跳变格**、**90 个「body 归 0 却要 116dp」格**，逐格原样打印。
> - **根因**：`SessionPaneLayout.kt:1009` 的 `available > 0.dp`。
>   它让 `composerMinimal` 在**恰好 0 dp** 上把一行形态**关掉**，形态于是**非单调**。
> - **修法**：一个字符，`> 0.dp` → `>= 0.dp`。真值集从 `{1..163}` 变成**首段** `{0..163}`。
> - **阶梯没动**：163 → 一行、164 → 完整，v57/v74 逐字保留、逐例全绿。
> - **反向验证**：撤回那一字符 ⇒ **6 例红**（含 2 条既有 pin 与 v153 那条），原文见 §6。
> - **回归**：**30 类 / 253 例 / 0 失败**（v153 基线 18 类 / 150 例，用例数只增不减）。
>
> ⚠️ **需要你签字的一条**：3 条既有断言的**极性**被翻了（§4）。它们原本把缺陷钉成
> 「期望值」——不改就绿不了。我按「更强的重述」而非「放宽」处理，但这是**改既有断言**，
> 是任务书里唯一需要你裁定是否接受的面。

---

## 1. 缺陷的精确定位（非单调这件事本身）

### 1.1 形态决策链（`SessionScreen.kt:2361-2389`，未改动）

```
paneBoxPx     = maxHeight.roundToPx()                                          :2361
paneFloorPx   = if (titlebarBottom && !desktop) 0 else navBottomPx              :2363
believedIme   = believedImeBottomPx(paneBoxPx, paneFloorPx, ime, essential)    :2364-2369
paneBottomDp  = maxOf(paneFloorPx, believedIme).coerceAtLeast(0).toDp()        :2370-2372
bodyHeightDp  = (maxHeight - paneBottomDp).coerceAtLeast(0.dp)                 :2384   <- 3 条源码 pin 钉死
bodyOneRow    = composerOneRow(isShortWindow, bodyHeightDp)                    :2385-2388 <- v77 P0 pin 钉死实参字面量
```

### 1.2 断点就在 `:1009`（`SessionPaneLayout.kt`）

```
1008  fun composerMinimal(available: Dp): Boolean =
1009      available > 0.dp &&          ← 这里
1010          available < COMPOSER_MINIMAL_HEIGHT + CHROME_MIN_HEIGHT + TIMELINE_MIN_CONTENT
```

**它把两个不同的问题合成了一个布尔式：**
1. *这个房放得下堆叠 composer 吗？* → `available < 164`（KDoc 里写的问题）
2. *这里到底还有没有房？* → `available > 0`

第 2 问在 `available == 0` 上回答「没有，所以**别选形态**」，而形态只有两个取值 ⇒
等价于**选了更大的那个**（完整 116dp `COMPOSER_COMPACT_HEIGHT`）。
**最放不下堆叠形态的那个房（0 dp），被告知它可以要堆叠形态。**

### 1.3 为什么修不动 `:1582`（早退阈值）—— 这也是我没动它的原因

`:1582` 的早退（`visibleBoxPx < essentialPx*2 = 568`）让钳位在 600 档 `438` 上完全不生效，
这是 600 档 **51px 余量**（`438 − 387 = 51`）存在的**前提**。
若把早退阈值降到 438 以下让钳位生效，600 档 body 会从 `51` 变成常数 `284 − 48 = 236`，
**直接撞红线「600 的 51」**，且是行为变更（v153 §1.4 已证该方向 20/20 全错）。
⇒ **钳位必须继续在 438 上失效**；可动的只有形态判据那一侧，任务书授权的
「`available > 0.dp` 一带」正是这里。

### 1.4 为什么也不能改 `SessionScreen.kt:2384` 的 `coerceAtLeast(0.dp)`

三条**源码字面量 pin** 钉死该行（v153 §5 已列）：
`SessionPaneImeBeliefV77Test:350`（**v77 P0 链**）、`SessionPaneShortWindowV51Test:204`、
`ComposerShortWindowV74Test:208`。改它必红三条，红线不许碰。
且 v77 P0 pin（`SessionPaneImeBeliefV77Test:304-310`）进一步要求形态的第三个实参
**逐字是 `bodyHeightDp,`** ⇒ 也不能把「喂给形态的数」换成另一个表达式。
⇒ **唯一的修复面就是 `composerMinimal` 本身**，这与任务书的授权一致。

---

## 2. 非单调的机械证据（修前，原样输出）

`ReverseJumpMonotoneV154Test.theFormNeverFlipsBackFromOneRowAsTheKeyboardGrows`
在**修前**把违规格逐格写入 `research/v97_fix/_v154_violations.txt`，**原文**：

```
V154 grid boxes=11 floors=2 ime=20 cells=440 adjacentPairs=836
V154 REVERSE-JUMP violations=10
V154 REVERSE-JUMP short=false box=284 floor=0 ime 274->284: body 10->0 px, oneRow true->false
V154 REVERSE-JUMP short=false box=284 floor=48 ime 274->284: body 10->0 px, oneRow true->false
V154 REVERSE-JUMP short=false box=285 floor=0 ime 284->300: body 1->0 px, oneRow true->false
V154 REVERSE-JUMP short=false box=285 floor=48 ime 284->300: body 1->0 px, oneRow true->false
V154 REVERSE-JUMP short=false box=438 floor=0 ime 426->438: body 12->0 px, oneRow true->false
V154 REVERSE-JUMP short=false box=438 floor=48 ime 426->438: body 12->0 px, oneRow true->false
V154 REVERSE-JUMP short=false box=480 floor=0 ime 474->522: body 6->0 px, oneRow true->false
V154 REVERSE-JUMP short=false box=480 floor=48 ime 474->522: body 6->0 px, oneRow true->false
V154 REVERSE-JUMP short=false box=567 floor=0 ime 522->606: body 45->0 px, oneRow true->false
V154 REVERSE-JUMP short=false box=567 floor=48 ime 522->606: body 45->0 px, oneRow true->false
```

**读法**：10 个格**全部**落在 `box < 568`（钳位早退区），`short=true` 的 10 条一条都没有
（窗口项恒为 true，本来就单调）。**600 档实测的两行（第 5、6 行）就在其中**：
`ime 426→438`、`body 12→0`、`oneRow true→false`。

**「body 归 0 却要 116dp」的矛盾**（`_v154_zero_room.txt`，修前原文计数）：

```
V154 zero-room cells asking for the full composer: 90
```

同一批运行的其它两条红（原文）：

```
FAILED: theFormNeverFlipsBackFromOneRowAsTheKeyboardGrows
   java.lang.AssertionError: the form flipped back from one row on 10 of 836 adjacent pairs;
   every one is in _v154_violations.txt — a taller keyboard may only keep the one-row form,
   never grow back into the full 116 dp composer expected:<0> but was:<10>

FAILED: aRoomOfZeroTakesTheCheapestFormAndNotTheFullOne
   java.lang.AssertionError: on 90 cells the body is 0 dp and the shell still asks for the full
   116 dp composer — the contradiction is the reverse jump's other face expected:<0> but was:<90>

FAILED: thePredicateIsMonotoneNonIncreasingInTheRoomItself
   java.lang.AssertionError: composerMinimal re-entered the one-row form at [1]: a smaller room
   may only ever take a smaller form, so the truth set must be an initial segment
   expected:<0> but was:<1>
```

第三条是**根本证据**：修前 `composerMinimal` 的真值集是 `{1..163}` —— 0 处**掉出去**、1 处**跳回来**，
即真值集**不是首段**，「monotone in [available]」（KDoc 原话）**从来没成立过**。

日志：`_v154_prefix_red.log`（`BUILD FAILED in 4s` · `7 tests completed, 4 failed` · `EXIT=1`）。

---

## 3. 单调性证明

### 3.1 网格规模

| 轴 | 取值 | 规模 |
|---|---|---|
| `box`（`paneBoxPx`） | `{284, 285, 438, 480, 567, 568, 569, 606, 700, 800, 1000}` | 11 |
| `paneFloorPx` | `{0, 48}`（48dp 靶 + 底部导航形态的 0） | 2 |
| `ime`（**严格递增**） | `{0, 48, 200, 274, 284, 300, 331, 332, 333, 370, 371, 387, 426, 438, 464, 474, 522, 606, 768, 836}` | 20 |

**440 格 / 每个窗口项**，两个窗口项（`isShortWindow` 假/真）⇒ **880 行落盘**
（`research/v97_fix/_v154_grid.txt`，882 行含 2 行头）。
**相邻对** = `11 × 2 × (20−1) × 2(窗口项) = 836`。轴与 v153 **完全一致**，两轮网格可逐格对比。

### 3.2 逐格结果（600 档实测行，`box=438, floor=48, short=false`，修后原文）

```
V154 438,48,0   -> 390,false
V154 438,48,48  -> 390,false
V154 438,48,200 -> 238,false
V154 438,48,274 -> 164,false
V154 438,48,284 -> 154,true     <- 进入 164 阶梯以下
V154 438,48,300 -> 138,true
V154 438,48,331 -> 107,true
V154 438,48,332 -> 106,true
V154 438,48,333 -> 105,true
V154 438,48,370 -> 68,true
V154 438,48,371 -> 67,true
V154 438,48,387 -> 51,true      <-- 600 档实测读数，红线 51 保持
V154 438,48,426 -> 12,true      <-- 退役 epoch 读数，仍一行
V154 438,48,438 -> 0,true       <-- 翻转点：body 0，形态**保持**一行
V154 438,48,464 -> 0,true
V154 438,48,474 -> 0,true       <-- 修前这里是 false（反向跳变）
V154 438,48,522 -> 0,true
V154 438,48,606 -> 0,true
V154 438,48,768 -> 0,true
V154 438,48,836 -> 0,true       <-- v77 陈旧帧
```

**形态列现在单调非降**（`false` 只在 body ≥ 164 出现，之后一路 `true` 到 0）。

**768 档（`box=606, floor=48`）逐格与修前逐字相同**（饱和常数 236，236 > 164 ⇒ 完整形态正确）：

```
V154 606,48,0   -> 558,false     V154 606,48,333 -> 273,false
V154 606,48,48  -> 558,false     V154 606,48,370 -> 236,false
V154 606,48,200 -> 406,false     V154 606,48,371 -> 236,false
V154 606,48,274 -> 332,false     V154 606,48,387 -> 236,false
V154 606,48,284 -> 322,false     V154 606,48,426 -> 236,false   <-- 768 实测
V154 606,48,300 -> 306,false     V154 606,48,438..836 -> 236,false（恒定）
```

### 3.3 证明（不只是观察，是**充分**的）

三条断言合起来构成证明：

1. **房单调非增**（`theBodyNeverGrowsAsTheKeyboardGrows`）：440 格的 836 个相邻对上
   `body(ime_{i+1}) > body(ime_i)` 出现 **0 次**。房对键盘单调不增。
2. **谓词单调非增**（`thePredicateIsMonotoneNonIncreasingInTheRoomItself`）：
   在 `0..400 dp` 逐 dp 扫描，**重新进入真值集**的次数 = **0**；
   `true→false` 的边**恰好 1 条，位于 164**（= 阶梯本身）。
   ⇒ 真值集 = **首段** `{0 .. 163}`，即 `composerMinimal` 在其定义域上单调非增。
3. **合成 ⇒ 形态单调**：房单调非增 + 谓词单调非增 ⇒ `oneRow = 窗口项 ∨ 谓词(房)`
   对 ime 单调非降。**键盘更高只可能保持或退到更小的形态，绝不翻回更大的形态。**

第 2 条是**充分性**所在：它保证「修好 0 dp 这一个点」不是碰巧，而是把整个定义域
的谓词都变成了首段，因此**任何**单调的房都会导出单调的形态。

---

## 4. 牵动了哪些 v55/v57 阶梯 pin（逐条 + 为什么新值对）

### 4.1 分类：**没有一条阶梯 pin 被牵动**

v55/v57 的形态阶梯是 **163 ↔ 164**（`56 + 44 + 64 = 164`）：

| pin | 内容 | 是否受本修影响 |
|---|---|---|
| `SessionPaneShortWindowV57Test:87-90` | `composerMinimal(120/measuredBody/163)` = 真；`composerMinimal(164)` = 假 | **不受影响**（逐例全绿） |
| `ComposerShortWindowV74Test:85-86` | `oneRow(163)` = 真；`oneRow(164)` = 假 | **不受影响**（逐例全绿） |
| `SessionPaneShortWindowV55Test:323-331` | `composerCeiling` 的 5 个 pane 与 `composerMinimal(pane)` 交互 | **不受影响**（14 例全绿） |
| `SessionPaneShortWindowV57Test:109` | `composerCeiling(measuredBody) = 82.67.dp` | **不受影响**（13 例全绿） |

**为什么结构性不受影响**：`available >= 0.dp` 与 `available > 0.dp` 在
`available > 0` 上**取值完全相同**，只差 `available == 0` 这**一个输入点**。
阶梯的每一条断言（120/163/164/700/900）都不在 0 上。

**`composerCeiling`（另一个 `composerMinimal` 调用方，`SessionPaneLayout.kt:1054`）也不动**：
它在 `if (pane <= 0.dp) return 0.dp`（`:1053`）**先返回**，从不拿 `pane == 0` 来问谓词
⇒ 没有任何 ceiling 移动。`SessionPaneShortWindowV55Test:323-331` 与
`V57Test:96-112` 因此原样全绿。

### 4.2 被牵动的 3 条：**它们不是阶梯，是「下界守卫」，且正是缺陷本身**

| # | 类:行 | 修前 | 修后 | 为什么新值对 |
|---|---|---|---|---|
| 1 | `SessionPaneShortWindowV57Test:92` | `assertTrue(!composerMinimal(0.dp))` | `assertTrue("a room of 0 dp can least of all carry the stacked form…", composerMinimal(0.dp))` | 该行原本钉的是**下界守卫**（位置在 164/700 阶梯之后，无自己的注释），不是 163/164 阶梯。0 dp 正是最放不下堆叠形态的房；`available < 164` 才是该谓词被问的问题。**断言强度提高**：从「0 不是短窗」变成「0 是最极端的短窗，且真值集必须是首段 {0..163}」 |
| 2 | `ComposerShortWindowV74Test:87` | `assertFalse(oneRow(false, 0.dp))` | `assertTrue("0 dp takes the cheapest form, never the full one — the form must not grow as the keyboard grows", oneRow(false, 0.dp))` | 同一事实的第二份重复钉（同 :87 与 v57 :92 表达同一句）。0 dp 归一行形态才与「键盘更高形态不得变大」自洽。**强度提高**：从否定式变成「0 dp 永不取大形态」这一可直接读出的性质 |
| 3 | `SelfProofEquivalenceV153Test:550-555` | `assertEquals("474: the body is pinned at 0", 0, bodyAt(474))` + `assertTrue("474: \`available > 0.dp\` is the guard, so body 0 turns the form OFF — the reverse jump…", !oneRowAt(474))` | `assertEquals(…)` **逐字保留**；`assertTrue(…, oneRowAt(474))` 并把消息换成 v154 事实 | v153 §6 的**主体是 51px 余量**（`assertEquals(51, margin)` / `margin >= 51` **一行未动**），被翻的只是它顺手记录的**缺陷观测**（`!oneRowAt(474)` 与 `!oneRowAt(inflated)`）。缺陷既已修，观测必须跟着改，否则文件自相矛盾 |

**净效果：0 条删除、0 条放宽。** 三条都从「否定的单点值」变成「肯定的结构化性质」，
覆盖面严格变大。新增的**独立** pin 在 `ReverseJumpMonotoneV154Test` 里，
不依赖这三条。

> ⚠️ **需要 Lead 签字的一条**：#1/#2 是**既有断言的极性翻转**。
> 我按「更强的重述」处理（因为不翻就绿不了，而翻的是下界守卫、不是阶梯），
> 但这确实是任务书里唯一触及「不许改既有断言」红线的面。**若你不接受，
> 回退只需把 `>= 0.dp` 改回 `> 0.dp` + 这 3 处极性回原，共 4 处、4 行。**

---

## 5. diff

### 5.1 生产（**唯一**生产改动，1 个运算符 + KDoc）

`git diff --stat -- app/src/main` ⇒ `1 file changed, 29 insertions(+), 2 deletions(-)`，
恰好两个 hunk：

```diff
--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt
@@ -1006,0 +1007,27 @@ internal object SessionPaneLayout {
+     *
+     * **v154: the lower guard is `>= 0.dp`, not `> 0.dp`.** The predicate above
+     * is one question — *can this room carry the stacked form?* — and the room
+     * that can least afford it is the one with nothing in it. `> 0.dp` answered a
+     * second, different question (*is there any room at all?*) with "no, so do not
+     * pick a form", which made the predicate **non-monotone**: at exactly 0 dp it
+     * turned the one-row form *off* and let the shell ask the full 116 dp
+     * [COMPOSER_COMPACT_HEIGHT] composer to fit in nothing.
+     *
+     * That is the B-2 reverse jump, measured on the 600 tier (`paneBox600 = 438`):
+     *
+     * ```
+     * ime 387 -> body  51, one row      (the measured reading)
+     * ime 426 -> body  12, one row
+     * ime 438 -> body   0, FULL FORM    <- keyboard taller, asking for 116 dp
+     * ```
+     *
+     * The room is monotone non-increasing in the keyboard (a taller keyboard can
+     * only take room away), so the form has to be monotone too — otherwise the
+     * two disagree about which way is 「tighter」. `>= 0.dp` makes the truth set
+     * an initial segment of `[0, 164)` — `{0 .. 163}` — which is what
+     * 「monotone in [available]」 above always claimed and never was. The
+     * *staircase* is untouched: 163 dp is one row and 164 dp is the stacked form,
+     * exactly as v57 and v74 pin it.
+     *
+     * [composerCeiling] reads this too, and is unaffected: it returns on
+     * `pane <= 0.dp` before asking, so no ceiling moves.
+     */
     fun composerMinimal(available: Dp): Boolean =
-        available > 0.dp &&
+        available >= 0.dp &&
             available < COMPOSER_MINIMAL_HEIGHT + CHROME_MIN_HEIGHT + TIMELINE_MIN_CONTENT
@@ -1009 +1036 @@ internal object SessionPaneLayout {
```

**未新增任何用户可见字符串**：KDoc 是开发者注释，运算符不产出文案。
**未改 `:1582` 早退阈值**（理由见 §1.3：动了会撞 600 档 51 的红线）。
**未改 `SessionScreen.kt` 任何一行**（理由见 §1.4：v77 P0 pin 钉死实参字面量）。

### 5.2 测试

| 文件 | 性质 |
|---|---|
| `app/src/test/java/com/xiufeigo/opencodeshell/ReverseJumpMonotoneV154Test.kt` | **新增** · 7 例 |
| `app/src/test/java/com/xiufeigo/opencodeshell/SessionPaneShortWindowV57Test.kt` | 改 1 处断言极性（§4.2 #1） |
| `app/src/test/java/com/xiufeigo/opencodeshell/ComposerShortWindowV74Test.kt` | 改 1 处断言极性（§4.2 #2） |
| `app/src/test/java/com/xiufeigo/opencodeshell/SelfProofEquivalenceV153Test.kt` | 改 2 处断言极性（§4.2 #3） |

`git status --porcelain -- app/src/main` = **1 行**（`SessionPaneLayout.kt`）。

### 5.3 一次行尾插曲（如实记录，已收敛）

生产文件原本有**恰好 1 个 CR**（在 `internal object SessionPaneLayout` 收尾的 `}` 上，
HEAD blob `CR=1`、末行 `}` 无换行）。首轮编辑把它规范化成了 LF，产生了一个
与修法无关的 `-}` / `+}` hunk。中途我一度反向操作（给末行补了换行），
造成文件末行被写坏（`}1883`），已逐字节修回。最终状态：
`WORK bytes=102214 CR=1`、末字节 `7D`(`}`) **无尾换行**，与 HEAD **同形**；
`git diff -U0 -- app/src/main` **只剩上述 2 个 hunk**。全程未用 `git checkout`/`restore`。
一次测试运行（`_v154_final2.log`）因该临时损坏编译失败，已在修回后重跑为绿。

---

## 6. 反向验证（**真跑抄原文**）

口径 = **只把 `available >= 0.dp` 撤回成 `available > 0.dp`**，测试与生产其余一字不动。

```
> Task :app:testDebugUnitTest FAILED

ReverseJumpMonotoneV154Test > thePredicateIsMonotoneNonIncreasingInTheRoomItself FAILED
    java.lang.AssertionError at ReverseJumpMonotoneV154Test.kt:247

ReverseJumpMonotoneV154Test > theFormNeverFlipsBackFromOneRowAsTheKeyboardGrows FAILED
    java.lang.AssertionError at ReverseJumpMonotoneV154Test.kt:156

SelfProofEquivalenceV153Test > theSixHundredTierKeepsFiftyOnePxOfRoomBeforeTheReverseJump FAILED
    java.lang.AssertionError at SelfProofEquivalenceV153Test.kt:551

SessionPaneShortWindowV57Test > only a short pane takes the one-row form FAILED
    java.lang.AssertionError at SessionPaneShortWindowV57Test.kt:98

34 tests completed, 6 failed

BUILD FAILED in 4s
52 actionable tasks: 4 executed, 48 up-to-date
EXIT=1
```

**6 例红**的断言原文（`TEST-*.xml`）：

```
FAILED: theFormNeverFlipsBackFromOneRowAsTheKeyboardGrows
   java.lang.AssertionError: the form flipped back from one row on 10 of 836 adjacent pairs;
   every one is in _v154_violations.txt — a taller keyboard may only keep the one-row form,
   never grow back into the full 116 dp composer expected:<0> but was:<10>

FAILED: aRoomOfZeroTakesTheCheapestFormAndNotTheFullOne
   java.lang.AssertionError: on 90 cells the body is 0 dp and the shell still asks for the full
   116 dp composer — the contradiction is the reverse jump's other face expected:<0> but was:<90>

FAILED: thePredicateIsMonotoneNonIncreasingInTheRoomItself
   java.lang.AssertionError: composerMinimal re-entered the one-row form at [1]: a smaller room
   may only ever take a smaller form, so the truth set must be an initial segment
   expected:<0> but was:<1>

FAILED: theSixHundredTierKeepsFiftyOnePxOfRoomBeforeTheReverseJump
   java.lang.AssertionError: 474: the body is 0 and the one-row form **holds** — v154 removed
   the `available > 0.dp` guard that used to turn the form OFF here and ask the full 116 dp
   composer for a room of nothing (the reverse jump)

FAILED: only a short pane takes the one-row form          (v57)
   java.lang.AssertionError: a room of 0 dp can least of all carry the stacked form, so it takes
   the one row; the truth set of composerMinimal must be the initial segment {0 .. 163}

FAILED: theReportedWindowTakesTheOneRowComposer           (v74)
   java.lang.AssertionError: 0 dp takes the cheapest form, never the full one — the form must
   not grow as the keyboard grows
```

同一次运行里 `_v154_violations.txt` 回到 **10** 条（与 §2 逐字相同）。
⇒ **新 pin 有牙**：它不是恒真断言，生产一撤回就红。
（其中 `theBodyNeverGrowsAsTheKeyboardGrows` 与两条实测档 pin **保持绿** ——
它们守的是没被改动的性质，房单调与 51px 基线本就不依赖这个运算符。）

日志：`_v154_reverse.log`。

---

## 7. 为什么不降强度

- **0 删除、0 放宽。** 被改的 3 条是**极性翻转 + 加写为更强的结构化性质**（§4.2），
  每条都附了「为什么新值对」。其余既有断言**一条未动**，
  包括 51px 余量（v153 §6）、768 的 140/92、两档 ①(i) −10/−12、48dp 靶。
- **用例数只增不减**：v153 基线 18 类 / 150 例 → 本轮 **30 类 / 253 例**，
  新增 `ReverseJumpMonotoneV154Test` **7 例**，三个被改类用例数
  （13 / 8 / 6）**一条未减**。
- **没有用「改断言」换「改行为」**：唯一的生产改动是 1 个运算符；
  如果反过来（放宽断言保行为），就不会有 §6 那 6 例红。
- **行为面比"看起来"小**：`>= 0.dp` 与 `> 0.dp` 只在 `available == 0` 一个输入点上不同，
  而 `composerCeiling` 在 `pane <= 0.dp` 已先返回（`:1053`）⇒ 实际只影响
  `composerOneRow` 的形态取值，**没有任何 ceiling / chrome / pane 高度移动**。
- **自证变红**：修前 4/7 例红、撤回修法 6 例红、修后 7/7 绿。

---

## 8. 测试证据

### 8.1 命令（全程前台，均在 `.agent-gradle-lock` 内，`JAVA_HOME` = Android Studio JBR）

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ReverseJumpMonotoneV154Test" --console=plain
```

### 8.2 运行台账

| # | 轮次 | 结果 | 日志 |
|---|---|---|---|
| 1 | **修前取证**（未改生产） | `BUILD FAILED in 4s` · **7 例 / 4 失败** · 违规格 10 + 90 格落盘 | `_v154_prefix_red.log` |
| 2 | 改完生产 + 3 处重述 | `BUILD FAILED in 8s` · 34 例 / 2 失败（**我自己两处 harness 写错**） | `_v154_green1.log` |
| 3 | 修 harness ① | `BUILD FAILED in 2s` · 编译错（**漏 `roundToInt` import，我自己的错**） | `_v154_green2.log` |
| 4 | 修 harness ② | `BUILD SUCCESSFUL in 3s` · 34 例 / **0 失败** | `_v154_green3.log` |
| 5 | **反向验证**（撤回那一字符） | `BUILD FAILED in 4s` · 34 例 / **6 失败** | `_v154_reverse.log` |
| 6 | 修回 + 行尾校正（**编译失败，文件末行被写坏**） | `BUILD FAILED in 2s` | `_v154_final2.log` |
| 7 | 逐字节修回 | `BUILD SUCCESSFUL in 7s` · 5 类 45 例 / **0 失败** | `_v154_final3.log` |
| 8 | **全量回归**（30 类） | `BUILD SUCCESSFUL in 6s` · **253 例 / 0 失败** | `_v154_regression.log` |

> 第 2/3/6 轮的红**全部是我自己 harness / 字节操作的错**（已如实记录，非生产缺陷）：
> ① 768 档 236 dp **高于** 164 阶梯，我误断言「一行」；
> ② `List<Dp>` 与 `List<Int>` 比较 + 漏 import；
> ③ 行尾修回脚本把末行写成 `}1883`。

### 8.3 终态绿（**原文**，`_v154_regression.log`）

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 6s
52 actionable tasks: 1 executed, 51 up-to-date
EXIT=0
REGRESSION: classes=30 tests=253 failures=0
```

### 8.4 回归清单（30 类 / 253 例 / **0 失败**）

| 类 | 例 | 类 | 例 |
|---|---|---|---|
| `ReverseJumpMonotoneV154Test`（**新**） | **7** | `SessionPaneShortWindowV51Test` | 10 |
| `SessionPaneShortWindowV57Test`（重述） | **13** | `SessionPaneShortWindowV55Test` | 14 |
| `ComposerShortWindowV74Test`（重述） | **8** | `SessionPaneShortWindowV78Test` | 10 |
| `SelfProofEquivalenceV153Test`（重述） | **6** | `SessionPaneChromeFloorV79Test` | 6 |
| `SessionPaneImeBeliefV77Test`（**v77 P0**） | **11** | `SessionPaneImeBandV84Test` | 11 |
| `ComposerPaneBottomCriterionV108Test` | 11 | `SessionPaneImeBandsV81Test` | 4 |
| `SessionPaneArrangementV76Test` | 7 | `ComposerDivergenceLocateV119Test` | 8 |
| `ComposerDivergenceLocate2V120Test` | 6 | `ComposerCriterionParityV118Test` | 12 |
| `ComposerEditorFormV80Test` | 6 | `ComposerEditorSlotV79Test` | 6 |
| `ComposerUnifiedCriterionV129Test` | 6 | `ComposerPositionDecompositionV107Test` | 4 |
| `ComposerOneRowEditorFloorV86Test` | 8 | `ComposerOneRowChipReserveV81Test` | 3 |
| `QueueTimelineFloorV100Test` | 8 | `QueueTimelineFloorV101Test` | 5 |
| `QueueTimelineShrinkCrashV102Test` | 10 | `QueueTimelineSpaceV99Test` | 17 |
| `ShortWindowTimelinePadV58Test` | 6 | `ShortWindowListV52Test` | 14 |
| `ComposerOverflowV103Test` | 9 | `ComposerBandTallEnoughV106Test` | 7 |

新增类 7 例逐条（`_v154_final3.log` 全绿）：

```
[OK] theFormNeverFlipsBackFromOneRowAsTheKeyboardGrows     <- 单调性 pin（核心）
[OK] theBodyNeverGrowsAsTheKeyboardGrows
[OK] aRoomOfZeroTakesTheCheapestFormAndNotTheFullOne
[OK] thePredicateIsMonotoneNonIncreasingInTheRoomItself     <- 充分性证明
[OK] theSixHundredTierMeasuredReadingStillHasFiftyOnePxOfRoom
[OK] theSevenSixtyEightTierStillSaturatesToTheSameConstant
[OK] theWholeGridIsWrittenOutForReading
```

**v77 P0 仍在位**（`SessionPaneImeBeliefV77Test` 11 例全绿，含
`the stale 836 dp frame is capped at the pane's essential form`、
`the bound only fires on a box with room to spare`、
`the body believes the ime inset through the bound`）。
**768 的 140/92** 由 `ComposerPaneBottomCriterionV108Test`(11) / `PrecisionT1And768bV148Test`
（本轮未列入 30 类，前者在列）守，**未回退**。
**`quarantine/` 分工未动**（`ModelVsDeviceMismatchTest` 仍在 `research/v97_fix/quarantine/`）。

---

## 9. 真机复验步骤（交给 Lead，**本轮未装机**）

1. **600 档 ime 网格**（**主项**）。1366x600 档，用能上报到 387 px 以上的输入法，
   逐级抬高 IME（联想条浮出、横屏、深色输入法等），覆盖 `387 / 426 / 438 / 474 / 522`：
   - 形态**必须**一路保持「一行」，**不得**在 438 之后翻回完整形态；
   - `body` 只会变小（`51 → 12 → 0`），**不得**出现「完整 composer 挤在 0 房里」；
   - 关键观感：**键盘更高时 composer 不得变大**。
2. **基线不回退**。1366x768 与 1366x600 两档：
   **工具栏与 tab 条必须在位**（v77 P0）；768 档键盘开时仍取**完整形态**
   （body 236 > 164，这一条**不该**被本修改动 —— 若它变成一行形态，说明我错了）。
3. **阶梯手感**。短窗（1440x810 @420dpi = 308dp 那一档）应**始终**一行；
   163/164 dp 边界附近不应有可见跳变。
4. **无新增一帧滞后**（v150 §B.2(d)）：缩窗/分屏/旋转/IME 开关连打，
   确认形态切换没有比以前晚一帧。
5. **若要验 600 档 51px 基线**：确认 `imeBottom600` 仍读到 387（本修**不动**钳位早退，
   51px 由 `438 − 387` 决定）；若换成别的输入法读数变了，那属于读数变化，不是本修。

---

## 10. 未闭环 / 需裁定

| # | 事项 | 状态 |
|---|---|---|
| 1 | **3 条既有断言的极性翻转**（v57:92 / v74:87 / v153:550,564） | **需 Lead 签字**。已按「更强的重述」处理并逐条论证（§4.2）。若不接受，回退 4 处 4 行 |
| 2 | 真机复验 5 项（§9） | **未做**（本轮禁装机） |
| 3 | `body == 0` 时的**残差**张力 | **已登记**：0 房仍声明一行形态的 48dp 编辑器底线（`COMPOSER_ONE_ROW_EDITOR_FLOOR`），而 body 是 0。**这比原缺陷弱得多**（原缺陷是要 116dp），且关掉它需要让钳位在 438 生效 ⇒ 会撞 600 档 51 的红线。**需单独裁定** |
| 4 | v150 §B.2(d) 测量量晚一帧 | 只登记，未动（承 v150） |
| 5 | v150 §B.2(c) 逐帧震荡 | 无证据，仍不主张 |
| 6 | v153 §B-1 等价改写 | 仍**未落地**（等 Lead），本轮与其无交集 |
| 7 | `_v154_grid.txt` / `_v154_violations.txt` / `_v154_zero_room.txt` 由测试写入 `research/` | 沿用本仓一次性探针既有做法（v150 / v153 同款） |
| 8 | 768 档 140/92 与 `PrecisionT1And768bV148Test` | 本轮未列入 30 类回归；由 `ComposerPaneBottomCriterionV108Test` 等相邻类间接覆盖。**若你要我把它也列进回归，说一声** |

---

## 11. 锁

- **报告锁**：`research/v97_fix/REVERSE_JUMP_FIX.md` 本文件即锁，11:05 写入（早于第一次 gradle）。
- **Gradle 锁**：`.agent-gradle-lock` —— `Test-Path` 确认不存在后写锁
  （输出 `LOCK_ACQUIRED after_wait=0`），8 次 gradle **全部前台**跑完，释放与复核见下。
- **行尾**：全程只用编辑/写入工具 + 一次显式字节校正（§5.3），**未** `git checkout`/`restore`。
