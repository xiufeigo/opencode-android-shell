> # ⛔ 本文件的「断点 588」结论已作废 —— 正文一字未改，仅加此横幅
>
> **作废范围**：本文件正文里关于**「断点 588」**的结论（以及由它推出的
> 「回翻点在 588」「`room` 在 588 处为 0」）。**正文其余部分一字未改**，
> 保留原文以便追溯 —— 那些格点数据本身仍有效，失效的是**断点的位置**。
>
> **实测依据**：`research/v97_verify/5554_md/REPORT_MONOTONE_FORM2.md`（v157，
> emulator-5554，`dumpsys window` 的 `InsetsSource id=3 type=ime frame=[..][1366,H]`
> 逐级读数）：
>
> - **IME 随窗口塌缩：426 → 339 → 261**，不是常数 —— 本文件推导时假定 `ime ≡ 426`；
> - **塌缩点 `600 → 599`**（单个 dp，掉 78px），`room` 反而**变大 77px**
>   （`H=600 → room=99`；`H=599 → room=176`）；
> - `588 ± 3` 逐 dp 全部同形态（room 325..328，STACKED），**邻域无任何跳变**；
>   真机上 588 处的 ime 是 **261**，`room = 165`，不是本文件算出的 0。
>
> **为什么 588 是伪断点**：`588 = ime(426) + 162` 这个等式只在
> **键盘恒为 426** 时成立。v157 证伪了这个前提。缺陷本身与 `>= 0.dp` 的修法
> 仍然成立，**失效的只是「588 是断点」这个断言**。
>
> **判据裁定（Lead）**：单调性的自变量是 **`room`**（composer 真正被递给谓词的
> 那个量），**窗口高 `H` 不得作判据** —— 因为 ime 会自己塌缩，
> `H → room` 不是应用能控制的函数。本文件那三列 ime 各自钉死一个 ime，
> 故**列内 `H` 轴与 `room` 轴同序**，其逐格结论不受影响（分工已写进
> `SessionPaneLayout.composerCompact` 的 KDoc，v158）。
>
> 本横幅由 **v159** 添加（`research/v97_fix/RENDER_THRESHOLD_ROOTCAUSE.md`）。

# REVERSE_JUMP_FIX2 — v156：把单调性网格扫到真机区间（620→560 逐 dp），找出**第二个**非单调来源

- 执行：mavis（mcode），**v156 · 修复工程师**
- 日期：2026-10-04
- 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
- 上游证据：`research/v97_verify/5554_md/REPORT_MONOTONE_FORM.md`（v155，真机 FAIL，3/3 复现）
- 承接：`research/v97_fix/REVERSE_JUMP_FIX.md`（v154，`SessionPaneLayout.kt:1009→:1036` `> 0.dp` → `>= 0.dp`）

> **锁约定**（沿用 `PIN_DEVICE_HARDLINK.md:6-7`）：本报告文件即锁，**先写盘后动手**。
> Gradle 锁 `.agent-gradle-lock`：`Test-Path` 确认不存在 → 写锁（`LOCK_ACQUIRED`）
> → 全部 gradle 前台/后台跑完 → 释放见 §11。
>
> **授权遵守**：全程只跑 `:app:testDebugUnitTest --tests "<类名>"`（末轮加 `--rerun-tasks` 强制
> 真实执行，同一命令形式）；**未** `assembleDebug` / `clean` / 全量 / **装机**。
> `JAVA_HOME` = Android Studio JBR。
>
> **行尾**：全程只用编辑/写入工具，**未**用 `git checkout` / `restore` / 任何 git 回退改行尾。
>
> **未新增任何用户可见字符串**：生产改动 = 一个运算符 + KDoc 开发者注释。

---

## 0. 结论速览

> ## ✅ 第二个非单调来源已定位并修：断点在 **window 588**
>
> - **第二个来源**：`SessionPaneLayout.kt:751` `composerCompact` 的 `available > 0.dp`
>   —— 与 v154 修的 `composerMinimal`（`:1036`）**同一个缺陷的另一个副本**，
>   阈值是 **224**（`116+44+64`）而不是 164。**修法同样是一个字符**：`> 0.dp` → `>= 0.dp`。
> - **真机断点的精确位置**：`window = 588`（`box = 426 = ime` 时 `body` 归 0）。
>   v155 采的 `600` 与 `580` **正好跨过它**（600 在上、580 在下），
>   所以这一对必然读出两种形态，而任何更粗的网格都跨不过 588。
> - **v154 网格漏掉它的原因（主因不是采样步长）**：v154 的模型把形态算成
>   `composerOneRow = windowIsShort ∨ composerMinimal(body)`，**只用了两个形态谓词里的一个**；
>   `SessionScreen.kt:1866` 的第二个形态输入 `compact = composerCompact(paneHeight)`
>   **根本不在模型里**。⇒ `composerCompact` 的下界不连续对 v154 的网格是**结构性不可见**，
>   不是「恰好没采到那个 dp」。
> - **逐格单调性**：`620→560` **61 格逐格扫描**（× `ime ∈ {426, 387, 474}` × `floor ∈ {0, 48}`
>   × `short ∈ {假, 真}` = 12 列 × 61 = 732 格，**360 个相邻对**）⇒ **违规格 0 条**。
> - **反向验证**：只撤回 `:751` 那一个字符 ⇒ **5 例真红**（原文见 §6）。
> - **回归**：**108 类 / 991 例 / 0 失败 / 0 错误 / 0 跳过**（`BUILD SUCCESSFUL in 1m 45s`，
>   末轮 `--rerun-tasks` 真实执行）。
> - **基线与红线**：768 的 **140/92**、600 的 **51**、两档 ①(i) **−10/−12**、**48dp 靶**、
>   v77 那条 P0 pin、`quarantine/` 分工 —— **全部未动**（§7）。
>
> ⚠️ **需要 Lead 签字的一条**：`SessionPaneShortWindowV55Test:245` 是**第 4 处极性翻转**
>   （v154 已签字 3 处之外新增的一处）。它是**加强**不是放宽，但按纪律如实上报，
>   回退只需 1 行 —— 见 §7.3 与 §10 的裁定问题。

---

## 1. 方法学：**v154 的网格为什么会漏掉 600→580 这一段**

这一节是本轮最重要的产出。**结论分三层，主因不是采样步长。**

### 1.1 主因：**模型漏项**（`composerCompact` 根本不在 v154 的网格里）

形态决策在 v154 报告 §1.1 里被写成一行：

```
bodyOneRow = composerOneRow(isShortWindow, bodyHeightDp)      <- v154 §1.1 的全部
```

而 v154 测试类 `ReverseJumpMonotoneV154Test` 的 `oneRow()` 也确实只调这一个函数（`:95-96`）：

```kotlin
private fun oneRow(box: Int, floor: Int, ime: Int, short: Boolean = false): Boolean =
    SessionPaneLayout.composerOneRow(short, bodyDp(box, floor, ime))
```

**但生产代码把 composer 的形态交给的是两个入参，不是一个：**

```kotlin
SessionScreen.kt:1870   minimal = minimalComposer                    // ← v154 扫的就是这个
SessionScreen.kt:1866   compact = SessionPaneLayout.composerCompact(paneHeight)   // ← v154 完全没扫
```

⇒ **v154 的网格是在一个「只有一个形态谓词」的模型上做的。**
`composerCompact` 的 `> 0.dp` 不连续，在那个模型里**没有可表示的位置** ——
这不是「采样点选差了」，是**自变量集缺了一个成员**。**任何步长都不会让 v154 看见它。**

### 1.2 次因：**相邻对分析跑在 `ime` 轴上，从未跑过 `box` 轴**

v154 的三条单调性断言全部沿 `ime` 递增方向取相邻对（`box`、`floor` 固定）：

| v154 断言 | 相邻对方向 |
|---|---|
| `theFormNeverFlipsBackFromOneRowAsTheKeyboardGrows` | **沿 `ime`**，`box` 固定 |
| `theBodyNeverGrowsAsTheKeyboardGrows` | **沿 `ime`**，`box` 固定 |
| `thePredicateIsMonotoneNonIncreasingInTheRoomItself` | 直接扫 `composerMinimal(0..400 dp)`，**单谓词** |

⇒ **「窗口变小 ⇒ 形态不得变贵」这条真机实际走的路（`box` 递减方向）从来没被断言过。**
v155 驱动的恰恰是这条路：`wm size 1366x<H>`，`H` 逐级变小。

### 1.3 三因：**断点确实不在 v154 的 `box` 网格上**

`box` 轴 11 点：`{284, 285, 438, 480, 567, 568, 569, 606, 700, 800, 1000}`。
真机断点是 `box = 426`（= `ime`）——**不在表内**。

- 落在表内最近的点是 `438`（`body = 12`，`compact = true`）和 `480`（`body = 54`，`compact = true`），
  **两格都还没翻转**，且方向与真实断点相反（更高的 `box`）。
- 表里 `body = 0` 的点只有 `284` / `285`（`ime = 426` 时），但它们**远在 560–620 窗口区间之外**，
  不与真实区间相邻 ⇒ 沿 `ime` 轴取相邻对时，永远不会把它们和 588 附近的格配成一对。

**⇒ 三条合起来，v155 §5.6 的建议是对的但不够：**
> 「建议 Lead 下一步：在 v154 网格里补 `box=599/598/…/570` 的逐 dp 扫描」

**只补采样点仍然会漏** —— 因为 `composerCompact` 不在模型里。**必须同时把第二个形态谓词
接进模型，并把相邻对方向换成 `box` 递减。** 本轮两者都做了（§2、§5）。

### 1.4 附带一条：v154 的修法让这个缺陷**从隐形变成显形**

修之前，两个谓词在 `available = 0` 上**恰好同意**（都答「要完整形态」），
所以「形态不单调」和「谓词互相矛盾」这两件事在 0 上是同一件事，看不出矛盾。

v154 把 `composerMinimal(0)` 改成 `true` 之后：

| 房 | `composerMinimal` | `composerCompact` | 状态 |
|---|---|---|---|
| 修前 `0 dp` | `false`（完整） | `false`（完整） | 同意（都错） |
| **修后 `0 dp`** | **`true`（一行）** | **`false`（完整）** | **互相矛盾** |

⇒ v154 的那一字符是**对的、该留的**，但它暴露出 composer 一直拿着**两个**形态谓词，
而只有**一个**被修过。**这不是 v154 的缺陷，是 v154 的证据链没走到第二个谓词。**
本轮用 `theTwoFormPredicatesNeverContradict` 把这条焊死。

---

## 2. 真机断点在网格上的**精确位置**

### 2.1 算式（1 dp == 1 px，`density = 1f`）

```
paneBoxPx    = window - 162                                        SessionScreen.kt:2361
              （162 = 768-606 = 600-438，两个实测档一致；被 theChromeAboveThePaneIsTheSameOnBothTiers 钉死）

believedIme  = believedImeBottomPx(box, floor, ime, essential)     SessionScreen.kt:2364
             = ime                        （因为 box ≤ 620-162 = 458 < 2*284 = 568，早退）
                                                                SessionPaneLayout.kt:1609
paneBottomDp = max(floor, believedIme).coerceAtLeast(0)           SessionScreen.kt:2370
             = 426                        （ime = 426，floor ≤ 48）
bodyHeightDp = (box - 426).coerceAtLeast(0)                        SessionScreen.kt:2384
minimal      = composerOneRow(short, body) = (0 >= 0) && (0 < 164) = true      :1036
compact      = composerCompact(body)    = (0 >  0) && …           = FALSE     :751  ← 缺陷
```

`body == 0 ⟺ box == 426 ⟺ window == 426 + 162 =` **`588`**

### 2.2 断点两侧逐格（`ime = 426`, `floor = 48`，修后原文）

```
V156 window=586 box=424 -> body=0, minimal=true, compact=true
V156 window=587 box=425 -> body=0, minimal=true, compact=true
V156 window=588 box=426 -> body=0, minimal=true, compact=true     <-- 断点：room 归 0
V156 window=589 box=427 -> body=1, minimal=true, compact=true
V156 window=600 box=438 -> body=12, minimal=true, compact=true
```

**修前同一段（`_v156_violations.txt` 修前原文）**：

```
V156 REVERSE-JUMP ime=426 floor=0  window 588->589: compact false->true (body 0 -> 1)
V156 REVERSE-JUMP ime=426 floor=48 window 588->589: compact false->true (body 0 -> 1)
```

### 2.3 与 v155 真机读数的逐位对照

| v155 实测 | v155 形态 | 本轮模型（`ime=426`, `floor=48`） | 修前 `compact` | 修后 `compact` |
|---|---|---|---|---|
| `1366x600` | **ONEROW**（`ET.w=381`） | `window=600 → body=12` | `true` ✅ | `true` ✅ |
| `1366x580` | **STACKED**（`ET.w=1310`） | `window=580 → body=0` | **`false`** ⛔ 与实测一致 | `true` ✅ |
| `1366x560` | **STACKED** | `window=560 → body=0` | **`false`** ⛔ 与实测一致 | `true` ✅ |

**⇒ 模型在修前**逐位重现了 v155 的真机回翻**（600 一行 / 580、560 堆叠）**，
修后三档全部保持一行。**断点 588 落在 600 与 580 之间，正是 v155 采的那一对跨过的地方。**

### 2.4 交叉验证（`ime = 387` / `474`）

| `ime` | 断点算式 `ime+162` | 是否落在 620→560 区间 | 修前 | 修后 |
|---|---|---|---|---|
| 426 | **588** | **是** | 在 588 失去 compact | 全区间 compact ✅ |
| 387 | 549 | 否（低于 560） | 全区间 compact ✅ | 全区间 compact ✅ |
| 474 | **636** | **是**（高于 620） | **在 620（区间最高点）就已失去 compact** ⛔ | 全区间 compact ✅ |

`ime = 474` 这一列特别有说服力：它证明**同一个缺陷在真机区间的两端都会发作**，
`588` 只是 `ime = 426` 这一个取值下的位置。**缺陷的位置跟着 `ime` 走
（`window = ime + 162`），所以「固定网格」永远只能撞上其中一条。**

---

## 3. 第二个非单调来源：行级归因

### 3.1 位置与原文

**`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt:749-751`（修前）**

```kotlin
749     /** Whether a pane of this height can only afford the compact composer. */
750     fun composerCompact(available: Dp): Boolean =
751         available > 0.dp && available < COMPOSER_MIN_HEIGHT + CHROME_MIN_HEIGHT + TIMELINE_MIN_CONTENT
```

阈值 = `116 + 44 + 64 =` **224**（对比 `composerMinimal` 的 `56 + 44 + 64 =` 164）。

### 3.2 它为什么是**同一个**缺陷的第二个副本

`> 0.dp` 这个合取项**不是在问本文档标题里的那个问题**。本文档问的是
「*这个房放得下完整 composer 吗？*」⇒ `available < 224` 就够了。
`> 0.dp` 回答的是**另一个问题**（「*这里到底还有没有房？*」），并用「没有，所以别选形态」作答。

形态只有两个取值 ⇒ **「不选形态」等价于「选更贵的那个」**。
⇒ **最放不下 compact 形态的那个房（0 dp），被告知它连 compact 都不需要。**

这与 v154 在 `:1009` 处的归因**逐字同构**（`REVERSE_JUMP_FIX.md` §1.2），
v154 的 KDoc 也已把 `[composerCompact]` 列为同族谓词（`SessionPaneLayout.kt:1004`）——
**只是当时没有把它接进网格**。

### 3.3 违规格的三条独立读数（修前，`_v156_violations.txt` / `_v156_contradictions.txt` 原文）

| 读数 | 修前原文 |
|---|---|
| **真值集不是首段** | `composerCompact re-entered its truth set at [1]: a smaller room may only ever take a smaller form, so the truth set must be an initial segment (1 staircase edges, expected exactly 1 at 224)` |
| **两个形态谓词互相矛盾** | `on 1 of the 401 rooms in 0..400 dp the two form predicates contradict each other (minimal=true yet compact=false) — the pane demands the one-row form and grants the full composer at the same time; every room is in _v156_contradictions.txt` |
| **网格反向跳变** | `the composer asked for a MORE expensive form on 2 of the 360 adjacent window pairs in the 620->560 sweep; every one is in _v156_violations.txt — a shorter window may only keep or shrink the form, never grow it` |

第三条的 2 条违规格逐格（修前原文）：

```
V156 REVERSE-JUMP ime=426 floor=0  window 588->589: compact false->true (body 0 -> 1)
V156 REVERSE-JUMP ime=426 floor=48 window 588->589: compact false->true (body 0 -> 1)
```

`body` 侧**修前就已单调**（`V156 body-growth violations in 620->560 = 0 (adjacent pairs = 360)`）
⇒ **这一轮的缺陷纯粹在形态谓词，不在房**。

### 3.4 红线全部未碰（明确声明）

| 红线 | 是否受影响 | 理由 |
|---|---|---|
| 768 的 **140 / 92** | ✅ 未动 | `composerCompact` 不参与 768 档读数；`SessionPaneShortWindowV55Test` 全 14 例绿 |
| 600 的 **51** | ✅ 未动 | 600 档 `body = 12`，`composerCompact(12)` 修前修后同为 `true` |
| 两档 ①(i) **−10 / −12** | ✅ 未动 | 取决于 `unionBottom`/`paneBottom`，不读 `compact` |
| **48dp 靶** | ✅ 未动 | `MIN_INTERACTIVE_COMPONENT_PX` 等常量未改 |
| **v77 那条 P0 pin** | ✅ 未动 | `SessionPaneImeBeliefV77Test` 在 108 类回归内全绿 |
| `quarantine/` 分工 | ✅ 未动 | 未触碰该目录 |
| v154 已签字的 3 处极性 | ✅ **保持现状** | `SessionPaneShortWindowV57Test:92` / `ComposerShortWindowV74Test:87` / `SelfProofEquivalenceV153Test:550-555` 一字未动 |
| `:1582` 早退阈值 | ✅ 未动 | 钳位必须继续在 `438` 上失效（v154 §1.3 的理由仍成立） |
| `SessionScreen.kt` 任何一行 | ✅ 未动 | v77 P0 pin 钉死 `:2384` 的 `coerceAtLeast(0.dp)` 与实参字面量 `bodyHeightDp,` |

---

## 4. diff

### 4.1 生产改动：**一个运算符 + KDoc**（`ui/session/**`，形态判定相关，授权内）

`git diff -U0 -- app/src/main` **只剩 2 个 hunk**：

```diff
@@ -749 +749,37 @@ internal object SessionPaneLayout {
+    /** … v156: the lower guard is `>= 0.dp`, not `> 0.dp`, for the same reason as
+     *  [composerMinimal] — this is the *second* copy of that one character, and
+     *  the one v154's grid could not see. …（37 行 KDoc，含 588 断点算式与
+     *  「v155 采的 600 和 580 正好跨过它」的原因说明）
@@ -751 +787 @@ internal object SessionPaneLayout {
-        available > 0.dp && available < COMPOSER_MIN_HEIGHT + CHROME_MIN_HEIGHT + TIMELINE_MIN_CONTENT
+        available >= 0.dp && available < COMPOSER_MIN_HEIGHT + CHROME_MIN_HEIGHT + TIMELINE_MIN_CONTENT
```

`git diff --stat -- app/src` ⇒ **2 files changed, 67 insertions(+), 4 deletions(-)**
（生产 1 个文件 + 测试 1 个文件；新增测试文件为 untracked，不计入 diff）。

**行尾核对**（v154 §5.3 记过的那个坑，本轮复现并已修回）：
首轮编辑把生产文件里**唯一 1 枚 CR** 规范化掉了（`WORK CR=0`），
产生了一个与修法无关的 `-}` / `+}` 伪 hunk。
已按 v154 的同一口径**逐字节补回**（`WriteAllText` 定位 `TIMELINE_MIN_CONTENT\n}\n` →
`\n}\r\n`，唯一命中）：

```
WORK bytes=104197  CR=1  lastByte=7D(})     <- 与 HEAD 同形
git diff -U0 -- app/src/main                <- 只剩上述 2 个 hunk
```

**全程未用 `git checkout` / `git restore` / 任何 git 回退。**

### 4.2 测试

| 文件 | 性质 |
|---|---|
| `app/src/test/.../ReverseJumpGridV156Test.kt` | **新增** · 7 例（620→560 逐 dp 网格 + 两条谓词 pin + 算式 pin） |
| `app/src/test/.../SessionPaneShortWindowV55Test.kt` | 改 1 处断言极性（§7.3，**第 4 处，需签字**） |

---

## 5. `620→560` 逐格单调性表（**61 格全覆盖，不是抽查**）

### 5.1 网格规模

| 轴 | 取值 | 规模 |
|---|---|---|
| **窗口**（真机 `wm size 1366x<H>` 驱动轴） | `620, 619, …, 560` **逐 dp** | **61** |
| `ime`（交叉） | `426`（真机实测）、`387`、`474` | 3 |
| `floor` | `0`、`48` | 2 |
| `short`（`isShortWindow`） | 假、真 | 2 |

**12 列 × 61 = 732 格**，沿窗口轴的**相邻对 = 6 × 60 = 360 个**（每对覆盖 2 列）。

### 5.2 逐格结果（主列：`ime=426`, `floor=48`，**修后**原文摘录，61 格逐格）

```
窗口  box  body  minimal  compact            窗口  box  body  minimal  compact
560   398   0    true     true                591   429   3    true     true
561   399   0    true     true                592   430   4    true     true
562   400   0    true     true                593   431   5    true     true
563   401   0    true     true                594   432   6    true     true
564   402   0    true     true                595   433   7    true     true
565   403   0    true     true                596   434   8    true     true
566   404   0    true     true                597   435   9    true     true
567   405   0    true     true                598   436  10    true     true
568   406   0    true     true                599   437  11    true     true
569   407   0    true     true                600   438  12    true     true   <- v155 实测 ONEROW
570   408   0    true     true                601   439  13    true     true
571   409   0    true     true                602   440  14    true     true
572   410   0    true     true                603   441  15    true     true
573   411   0    true     true                604   442  16    true     true
574   412   0    true     true                605   443  17    true     true
575   413   0    true     true                606   444  18    true     true   <- v154 768 档 pane
576   414   0    true     true                607   445  19    true     true
577   415   0    true     true                608   446  20    true     true
578   416   0    true     true                609   447  21    true     true
579   417   0    true     true                610   448  22    true     true
580   418   0    true     true                611   449  23    true     true   <- v155 实测 STACKED(缺陷)
581   419   0    true     true                612   450  24    true     true
582   420   0    true     true                613   451  25    true     true
583   421   0    true     true                614   452  26    true     true
584   422   0    true     true                615   453  27    true     true
585   423   0    true     true                616   454  28    true     true
586   424   0    true     true                617   455  29    true     true
587   425   0    true     true                618   456  30    true     true
588   426   0    true     true   <-- 断点       619   457  31    true     true
589   427   1    true     true                620   458  32    true     true
590   428   2    true     true
```

**形态列全程 `true`（一行），无一次回翻；`body` 全程单调不增。**

### 5.3 三条判据的计数（全部落盘）

```
V156 REVERSE-JUMP violations = 0                                        (相邻对 360)
V156 body-growth violations in 620->560 = 0                             (相邻对 360)
V156 rooms in 0..400 dp where minimal=true and compact=false = 0         (401 个房)
```

### 5.4 修后新 pin 逐条（`ReverseJumpGridV156Test`，7 例）

| # | 用例 | 断言的内容 |
|---|---|---|
| 1 | `theFormNeverAsksForMoreAsTheWindowGetsShorter` | 620→560 逐 dp × 12 列，形态不得变贵；违规格逐格写入 `_v156_violations.txt` |
| 2 | `theFirstWindowThatLosesTheCompactFormIsTheOneWhereTheRoomIsGone` | 断点定位：失去 compact 的最大窗口必须 `≥ ime + 162`（即只能在「房为 0」时失去） |
| 3 | `theBodyNeverGrowsAsTheWindowGetsShorter` | 房随窗口变小不得变大；违规格写入 `_v156_body_growth.txt` |
| 4 | `theTwoFormPredicatesNeverContradict` | 0..400 dp 内 `!compact(room) && minimal(room)` 必须为空（v154 修法的直接后果） |
| 5 | `theCompactPredicateIsMonotoneNonIncreasingInTheRoomItself` | 真值集必须是首段；阶梯边恰好 1 条；`223` compact / `224` 不 compact |
| 6 | `theChromeAboveThePaneIsTheSameOnBothTiers` | `768-606 = 600-438 = 162`，`2*essential = 568`（防魔法数漂移） |
| 7 | `theRoomRunsOutAtTheKeyboardClaimPlusTheChromeAboveThePane` | 断点算式本身：600 档 `body > 0`、580 档 `body == 0`、断点 `= ime + 162 = 588` |

---

## 6. 反向验证（**真跑抄原文**）

口径 = **只把 `SessionPaneLayout.kt:787` 的 `available >= 0.dp` 撤回成 `available > 0.dp`**，
测试与生产其余一字不动。

```
> Task :app:testDebugUnitTest FAILED

ReverseJumpGridV156Test > theFormNeverAsksForMoreAsTheWindowGetsShorter FAILED
ReverseJumpGridV156Test > theFirstWindowThatLosesTheCompactFormIsTheOneWhereTheRoomIsGone FAILED
ReverseJumpGridV156Test > theTwoFormPredicatesNeverContradict FAILED
ReverseJumpGridV156Test > theCompactPredicateIsMonotoneNonIncreasingInTheRoomItself FAILED
SessionPaneShortWindowV55Test > only a pane that cannot carry the full composer compacts it FAILED
21 tests completed, 5 failed

BUILD FAILED in 4s
EXIT=1
```

失败断言原文（`TEST-*.xml`）：

```
java.lang.AssertionError: the composer asked for a MORE expensive form on 2 of the 360 adjacent
window pairs in the 620->560 sweep; every one is in _v156_violations.txt — a shorter window may
only keep or shrink the form, never grow it

java.lang.AssertionError: ime=474: the compact form must survive every window in 620..560. It may
only be lost at a window of 0, i.e. ime+chrome = 636, which is outside the swept range; the
compact form was already lost at window 620, 16 dp too early

java.lang.AssertionError: on 1 of the 401 rooms in 0..400 dp the two form predicates contradict
each other (minimal=true yet compact=false) — the pane demands the one-row form and grants the
full composer at the same time; every room is in _v156_contradictions.txt

java.lang.AssertionError: composerCompact re-entered its truth set at [1]: a smaller room may only
ever take a smaller form, so the truth set must be an initial segment (1 staircase edges,
expected exactly 1 at 224)
```

**4 条新 pin 全红 + 1 条既有 pin 红** ⇒ 修法不是碰巧。随后已恢复为 `>= 0.dp` 并重跑至全绿。

---

## 7. 为什么不降强度

### 7.1 生产侧

| 维度 | 处置 |
|---|---|
| 阶梯本身 | **未动**。`composerCompact(223) = true`、`composerCompact(224) = false`（真机仍靠 `SessionPaneLayout.kt:751` 的算式，新增 pin 第 5 条逐条钉死） |
| `> 0.dp` 与 `>= 0.dp` 的差别 | **只有 `available == 0` 这一个输入点**。`SessionPaneShortWindowV55Test:241-244` 的 4 条阶梯断言（`measuredPane` / `196.2` / `250` / `700`）全部 `> 0`，**取值逐字不变**，实测全绿 |
| v154 的 `:1036` | **一个字节未动** |
| `:1582` 早退、`SessionScreen.kt` 任何一行 | **未动**（§3.4） |
| 用户可见字符串 | **未新增**（KDoc 是开发者注释，运算符不产出文案） |

### 7.2 测试侧

- **只新增 pin，不改既有断言的结构**；7 条新 pin 全部是**逐格/逐 dp 的结构化性质**，
  覆盖面严格大于「一个单点值」。
- 唯一触及的既有断言是 §7.3。

### 7.3 ⚠️ 需要 Lead 签字：**第 4 处极性翻转**（`SessionPaneShortWindowV55Test:245`）

**修前原文**（`SessionPaneShortWindowV55Test:245`）：

```kotlin
assertTrue(!SessionPaneLayout.composerCompact(0.dp))
```

**修后**（同位置）：

```kotlin
// v156: this line used to be `assertTrue(!composerCompact(0.dp))` — the same
// lower-bound guard v154 removed from `composerMinimal`, in the *second* form
// predicate (`SessionPaneLayout.kt:751`, threshold 224 instead of 164). It sat
// here as a trailing lower-bound guard, not on the 223/224 staircase above, and
// it pinned the defect itself: the room that can least afford even the compact
// composer was told it did not need compacting. …
assertTrue(
    "a room of 0 dp can least of all carry the full composer, so it must still " +
        "take the compact one — the truth set must be the initial segment {0 .. 223}",
    SessionPaneLayout.composerCompact(0.dp),
)
for (room in 0..223) {
    assertTrue("room $room dp is below the 224 dp staircase, so it must compact",
        SessionPaneLayout.composerCompact(room.dp))
}
assertTrue("the staircase is untouched: 224 dp and above carries the full composer",
    !SessionPaneLayout.composerCompact(224.dp))
```

| 判据 | 说明 |
|---|---|
| **不是放宽** | 旧：否定式的**一个**单点值。新：肯定式的**整个首段性质** —— `0..223` 全部 compact（**224 格**）+ `224` 不 compact。覆盖面从 1 个输入扩大到 225 个 |
| **不是删** | 断言仍在（同一位置、同一事实），并被加强 |
| **不在阶梯上** | 阶梯是 `223 ↔ 224`，由 `:241-244` 钉着，那 4 条**一字未动、逐例全绿**。`:245` 是阶梯组之后单列的下界守卫 —— 与 v154 翻转的 `SessionPaneShortWindowV57Test:92` 位置同构 |
| **与 v154 已签字的 3 处关系** | 那 3 处（`V57Test:92` / `V74Test:87` / `SelfProofEquivalenceV153Test:550-555`）**保持现状、一字未动**。本处是**新增的第 4 处** |
| **回退成本** | **1 行**：`SessionPaneLayout.kt:787` 改回 `> 0.dp` + `V55Test` 恢复 `assertTrue(!composerCompact(0.dp))`（并删掉那个 `for` 循环与末尾 `224` 断言） |

> **我的判断**：这处翻转与 v154 的三处**同构且同向**（都是把「否定的单点值」改成
> 「肯定的结构化性质」，都是下界守卫、都不在阶梯上），因此按同一先例处理并**在此显式上报**。
> 若你不接受，回退是 1 行，**不需要重做本轮任何其它工作**。

---

## 8. 测试证据

### 8.1 授权与命令

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.<类名>" --console=plain
```

**全程只跑这一种命令形式**（末轮加 `--rerun-tasks` 强制真实执行）。
**未** `assembleDebug` / `clean` / 全量 / **装机**。

### 8.2 修前 RED（`_v156_prefix_red.log`）

```
ReverseJumpGridV156Test > theFormNeverAsksForMoreAsTheWindowGetsShorter FAILED
ReverseJumpGridV156Test > theFirstWindowThatLosesTheCompactFormIsTheOneWhereTheRoomIsGone FAILED
ReverseJumpGridV156Test > theTwoFormPredicatesNeverContradict FAILED
ReverseJumpGridV156Test > theCompactPredicateIsMonotoneNonIncreasingInTheRoomItself FAILED
7 tests completed, 4 failed
BUILD FAILED in 3s
EXIT=1
```

### 8.3 修后 GREEN（`_v156_green1.log`）

```
> Task :app:testDebugUnitTest FAILED          <- 这一刻 V55:245 仍红（见 §8.4 的处置时序）
SessionPaneShortWindowV55Test > only a pane that cannot carry the full composer compacts it FAILED
55 tests completed, 1 failed
```

翻 `V55Test:245` 之后重跑：

```
BUILD SUCCESSFUL in 3s
EXIT=0
```

### 8.4 回归：**108 类 / 991 例 / 0 失败**（受影响全部类）

| 项 | 值 |
|---|---|
| 圈定口径 | 凡引用 `SessionPaneLayout` 或源码扫描 `SessionPaneLayout.kt` / `SessionScreen.kt` 的测试类 |
| 圈定命令 | `Get-ChildItem -Recurse -Filter *.kt \| Select-String "SessionPaneLayout\|SessionScreen\.kt"` |
| 类数 | **108** |
| 用例数 | **991** |
| 失败 / 错误 / 跳过 | **0 / 0 / 0** |
| 用例数是否减少 | **否**（新增 `ReverseJumpGridV156Test` 7 例；`V55Test` 仍 **14** 例，未减） |
| 关键 pin 覆盖 | `SessionPaneImeBeliefV77Test`（v77 P0 链）、`SessionPaneShortWindowV51Test`、`ComposerShortWindowV74Test`、`SessionPaneShortWindowV57Test`、`ReverseJumpMonotoneV154Test`、`SelfProofEquivalenceV153Test`、`PrecisionT1And768bV148Test`（768 的 140/92）、`ComposerPaneBottomCriterionV108Test`、`UsabilityCellsV144FixTest` / `V146SendOcclusionFixTest`（48dp 靶）全部在内且全绿 |

**末轮原文（`--rerun-tasks`，任务真实执行而非 UP-TO-DATE）**：

```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 1m 45s
EXIT=0
REGRESSION(final): classes=108 tests=991 failures=0 errors=0 skipped=0
```

> **记账**：第一次跑完 108 类后（`BUILD SUCCESSFUL in 54s`）我又补了那一枚 CR（§4.1），
> 重跑时 gradle 判 `UP-TO-DATE`（CR 落在 KDoc 注释内，字节码不变），
> 那次的 XML 是上一轮的结果，**不能当最终态证据**。因此末轮用 `--rerun-tasks`
> 强制真实执行，上面这段是强制重跑后的原文。**如实记账，不拿 UP-TO-DATE 充数。**

### 8.5 落盘证据

| 文件 | 内容 |
|---|---|
| `research/v97_fix/_v156_violations.txt` | 修前 2 条违规格原文 + **61 格逐格表**（修后）+ 断点算式 |
| `research/v97_fix/_v156_body_growth.txt` | `body-growth violations = 0 (adjacent pairs = 360)` |
| `research/v97_fix/_v156_contradictions.txt` | `rooms where minimal=true and compact=false = 0` |
| `research/v97_fix/_v156_prefix_red.log` / `_v156_green1.log` / `_v156_reverse.log` / `_v156_regression.log` | 四轮 gradle 原文 |
| `research/v97_fix/_v156_affected.txt` | 108 个受影响类清单 |

---

## 9. 真机复验步骤（交给 Lead：`600→580` 必须不再回翻）

**前置**（沿用 v155 的口径）：`emulator-5554` · `wm size 1366x768` / `wm density 160`
（1 dp == 1 px）· 焦点在本 app（`mCurrentFocus=…com.xiufeigo.opencodeshell/.OcActivity`）·
composer 全程有内容 · 读高度前一律 `AssertIme` 硬断言 `mInputShown`。
**本轮未装机**（`assembleDebug` 在授权外），所以下轮需先由有打包权限的轮次出 APK。

| # | 动作 | 期望（v156 后） | v155 实测（缺陷） |
|---|---|---|---|
| 1 | 记录 `768` 档 `paneH` / `timelineBox` / `readable` / ①(i) | **`140` / `92` / `−10`** | 同（PASS） |
| 2 | 记录 `600` 档同上 | **`51` / `−12`** | 同（PASS） |
| 3 | `wm size 1366x620` → dump | **ONEROW**（`ET.w` 窄 ≈ 375~381） | ONEROW ✅ |
| 4 | `wm size 1366x600` → dump | **ONEROW**（`ET.w ≈ 381`） | ONEROW ✅ |
| 5 | **`wm size 1366x588` → dump** | **ONEROW**（`ET.w` 仍窄）——**这是本轮修法的断点，必测** | 未测过 |
| 6 | **`wm size 1366x580` → dump** | **ONEROW**（`ET.w` 仍 ≈ 381，**不再变 1310**） | ⛔ **STACKED（1310）** |
| 7 | `wm size 1366x560` → dump | **ONEROW** | ⛔ STACKED（1310） |
| 8 | **双向扫描**：从 `768` 逐级降到 `560`，再逐级升回 `768` | 形态全程 **ONEROW**（降的方向不再翻） | 降的方向在 588 翻 |
| 9 | 复核 §2 六条判据（`600` 与 `768` 两档） | **6/6 PASS**（含 ③ `2304 > 0`、④ 两靶 48 高、⑥ 双 `clickable`） | 6/6 PASS |
| 10 | 复核 chip 高度 | 不再被压到 `31` / `11`（形态不换 ⇒ 48dp 靶完整） | `48 → 31 → 11` |
| 11 | 收尾 | `wm size 1366x768` / `wm density 160` **显式重设并复核**；清空自建草稿；`logcat -t 5000` 扫 `FATAL\|IllegalArgumentException` = 0；`appPid` 全程未变 | — |

**取证判据（沿用 v155 的 F1/F2 双判据，避开 v155 §1.0 自曝的 `+2` 容差度量 bug）**：

- **F1**：`EditText` 的 `bounds` 宽 ⇒ 窄（≈381）= 一行；满宽（1310）= 堆叠。
- **F2**：`Send.y1 < EditText.y2` ⇒ 同行；`Send.y1 >= EditText.y2` ⇒ 换行。
- 两者在第 5/6/7 步**必须同时**判 `ONEROW`，且第 6 步（580）的 `Send` 顶边必须
  **在输入框的 y 带内**（`580` 档实测为 `Send [1316,191][1364,239]` 那一形态）。
- **不要**用 v155 §5.3 那个会命中窗口根 `ComposeView` 的满宽容器 selector 取 `paneH`。

---

## 10. 未闭环 / 需要裁定

### 10.1 ⚠️ **需要 Lead 裁定：第 4 处极性翻转是否接受**

`SessionPaneShortWindowV55Test:245`（§7.3）。我的判断是与 v154 已签字的三处**同构同向**，
故按同一先例处理并显式上报。**回退 = 1 行**（生产 `:787` 改回 `> 0.dp` +
`V55Test` 恢复原单行断言并删掉新增的 `for` 循环与 `224` 断言）。
若不接受，我按裁定改回，**本轮其它工作（网格、归因、反向验证、回归）全部保留有效**。

### 10.2 v155 §5.1 的证据缺口**本轮仍未闭环**：`ime` 高度从未被直接量到

`dumpsys input_method` / `dumpsys window windows` 在本机都给不出可解析的 `mCurFrame` / `mFrame`。
本轮**仍未量到键盘高度**，断点 `588` 是**按 `ime = 426` 这个题面给定值算出来的**。

⇒ **「Gboard 自身高度随窗口变化」这个混淆仍未排除**：若真机上 `580` 档的键盘比 `600` 档
**更矮**（`ime` 变小），则本轮算出的断点位置会整体上移。
**建议下轮用 `adb shell settings put secure show_ime_with_hard_keyboard 1` 固定键盘高度后重跑 §9**，
或 `dumpsys input_method | grep -i frame` 原样抄全文。

### 10.3 本轮模型的两个已声明近似

1. **`paneHeight`（`SessionScreen.kt:2116` 的 `onSizeChanged` 实测房）用 `bodyHeightDp` 代入。**
   两者是同一个房，但一个是布局实测、一个是解析值。**被钉的缺陷在谓词里**，
   所以 `theCompactPredicateIsMonotoneNonIncreasingInTheRoomItself`（0..400 dp 全域）
   与 `theTwoFormPredicatesNeverContradict` **对任一读数都成立**，不受此近似影响；
   只有 §2 的**断点数值**依赖它。已在测试文件 KDoc 里声明。
2. **`chromeAbovePanePx = 162` 来自两个实测档（`768-606 = 600-438`）的推断**，
   非直接测量。已由 `theChromeAboveThePaneIsTheSameOnBothTiers` 钉死一致性，
   但若 v155 §5.3 的 `620` 档 selector 缺陷（`paneH=620` 假数）意味着第三个档不一致，
   则 `588` 会平移。**§9 第 5 步专门测 588 正是为了直接证伪/证实这一条。**

### 10.4 v155 §5.2 的历史自建内容仍未清理

`E2ECHK5554u1*` 共 28 节点（`u1s×8`、`u1t×6`、`u1v×2`、`u1v134×11`、`u1w×1`）属历史轮次自建，
本轮**未触碰设备、未装机、未清理**，仍需在 app UI 内逐个删除或由 Lead 用受支持入口处理。

### 10.5 像素判据（③）仍只覆盖静帧

沿用 v155 §5.5 的记账，本轮未做逐帧/滚动过程中的连续采样。

### 10.6 判据本身的一个诚实的边界

`theFormNeverAsksForMoreAsTheWindowGetsShorter` 用 `compact` 判定「形态变贵」，
是因为 `compact` 是「这个房只够 compact 吗」的**直接**谓词。
真实渲染由 `compact` + `minimal` **两个**入参共同决定（`SessionScreen.kt:1866` / `:1870`），
本轮以 `theTwoFormPredicatesNeverContradict`（两者不得矛盾）**补上了这一层**，
所以两个入参都被覆盖。**但 `Composer` 内部若还有第三处形态分支，本轮网格没有建模**
—— 本轮**不声称**已穷尽形态决策链，只声称覆盖了 `SessionScreen.kt` 交给 composer 的两个入参。

---

## 11. 收尾

| 项 | 结果 |
|---|---|
| Gradle 锁 `.agent-gradle-lock` | 取锁时 `Test-Path` 确认不存在 → 写入（`LOCK_ACQUIRED`）→ 4 轮跑完 → **已释放**（§12） |
| 生产文件行尾 | `WORK bytes=104197 CR=1 lastByte=7D`，与 HEAD 同形；`git diff -U0` 只剩 2 个 hunk |
| git 回退/checkout | **全程零次**（`core.autocrlf=false`，只用编辑/写入工具） |
| 装机 | **未装机**（`assembleDebug` 在授权外） ⇒ §9 需有打包权限的轮次执行 |
| 改动文件清单 | `ui/session/SessionPaneLayout.kt`（1 运算符 + KDoc）、`SessionPaneShortWindowV55Test.kt`（1 处断言 + 注释）、`ReverseJumpGridV156Test.kt`（新增 7 例）、本报告 |
| 未触碰 | `_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers\|home\|terminal\|nav/**`、`ui/App.kt`、`OcActivity.kt`、`quarantine/`、`SessionScreen.kt` |

---

## 12. 状态

> **✅ 第二个非单调来源已定位（`SessionPaneLayout.kt:751`，算式见 §3.1）、已修（一个字符）、
> 逐格证明（620→560 全 61 格 × 12 列，违规格 0）、反向验证真红（5 例）、回归 108 类 / 991 例 / 0 失败。**
>
> **真机断点 = `window 588`**（`box = 426 = ime` 时 `body` 归 0），**修前在 588 精确复现了
> v155 的 `600` 一行 / `580` 堆叠回翻**。
>
> ⚠️ **两件挂在你这里**：① 第 4 处极性翻转的签字（§10.1，1 行可回退）；
> ② §9 的真机复验需先出 APK，且 v155 §5.1「没量到键盘高度」的证据缺口仍未闭环（§10.2）。
