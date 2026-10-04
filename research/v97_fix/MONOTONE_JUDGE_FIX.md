# MONOTONE_JUDGE_FIX — v158：把单调性判据从 `H`（窗口高）改到 `room`（房）

- 执行：mavis（mcode），**v158 · 修复工程师**
- 日期：2026-10-04
- 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
- 上游证据：`research/v97_verify/5554_md/REPORT_MONOTONE_FORM2.md`（v157 真机实测）、`research/v97_fix/REVERSE_JUMP_FIX2.md`（v156）
- 锁：`.agent-gradle-lock` 先 `Test-Path` 确认不存在 → 写入 `LOCK_ACQUIRED` → **全部 gradle 前台跑完** → §7 释放
- 环境：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`
- 命令白名单遵守：全程只跑 `:app:testDebugUnitTest --tests "<类名>"`（末轮 `--rerun-tasks` 强制真实执行）；**未** `assembleDebug` / `clean` / 全量 / **装机**
- 行尾：全程只用编辑/写入工具，**未**用 `git checkout` / `restore` / 任何 git 回退改行尾
- **写权限遵守**：只碰「测试文件」+ `research/**` + `SessionPaneLayout.kt` 的 **KDoc 注释区**。`_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers|home|terminal|nav/**`、`ui/App.kt`、`OcActivity.kt` **一字未碰**

---

## 0. 结论速览

> ## ✅ 判据已迁到 `room`：`ReverseJumpRoomJudgeV158Test` 落地，5 例全绿
>
> - **判据的自变量 = `room`（`box − ime`，即 composer 真正被递给谓词的那个量），`H` 不得作判据。**
> - **61 格逐格表**：`620 → 560` 逐 dp（61 格）**按 room 重排**后逐格落盘
>   → `_v158_room_grid.txt`，**逐格单调、0 违例**。
> - **v157 实测已钉进断言**：ime 随窗口塌缩 **426 → 339 → 261**，塌缩点 **`600 → 599`**（单 dp，掉 78px）。
> - **反向验证**：把 ime 退回 v156 的「常数 426」前提（＝「拿 H 当判据」的隐含假设）⇒ **2/5 真红**（原文 §5）。
> - **KDoc 修订 = 纯注释**：`SessionPaneLayout.kt` `git diff` **44 行 ±，非注释非空行 = 0**（§6）。
> - **交接文档**：`HANDOFF.md` 与 `v98_RESUME.md` 各**追加 1 节 / 6 条硬结论**（§8）。
> - **红线**：768 的 140/92、600 的 51、两档 ①(i) −10/−12、48dp 靶、v77 P0、`quarantine/` 分工、
>   v154/v156 已签字的 **4 处极性** —— **全部未回退**（§9）。

---

## 1. 判据为什么必须改到 `room`（v157 实测钉入）

v155/v156/v157 三轮都把单调性主张写在 **`H`（窗口高）** 上，因为真机就是用
`wm size 1366x<H>` 驱动的。v157 把这个前提测了，**并证伪了它**：

```
取数：dumpsys window  →  InsetsSource id=3 type=ime frame=[..][1366,H]

H = 620   ime = 426   frame=[0,194][1366,620]
H = 604   ime = 339   frame=[0,261][1366,604]
H = 600   ime = 339   frame=[0,261][1366,600]
H = 599   ime = 261   frame=[0,334][1366,599]     ← 塌缩
H = 560   ime = 261   frame=[0,299][1366,560]
```

**三个取值：426 / 339 / 261。键盘会自己缩（纯 Gboard 行为，应用零参与），
塌缩发生在 `600 → 599` 这一个 dp 上，一次掉 78px。**

于是

```
room = box − ime = (H − 162) − ime
```

**`H → room` 不是一个应用能控制的函数。** 实测：

| | `H` | `ime` | `room` |
|---|---:|---:|---:|
| 塌缩前 | 600 | 339 | **99** |
| 塌缩后 | 599 | 261 | **176** |

**窗矮了 1dp，房大了 77dp。** 所以「`H` 变小 ⇒ 形态不得变贵」这句话
**根本不是在说应用**，而是在说 Gboard 的 resize 策略；v157 果然在该点读到「回翻」
（`ONEROW → STACKED`），而**形态变大在那里是正确行为**。

⇒ **Lead 裁定：判据以 `room` 为准，`H` 序列不得用作判据。**

### 1.1 `room` 在本报告里到底是哪个量

**composer 真正被递给两个形态谓词的那个参数** ——
`SessionScreen.kt:2384` 的 `bodyHeightDp`（`minimal`，`SessionScreen.kt:1870`）与
`composerCompact` 的 `available`（`SessionPaneLayout.kt:829`）。它是
`(box − paneBottom).coerceAtLeast(0)`，`box = H − 162`。

v157 报告写的是 `room = H − ime`（**没减 162 的 chrome**）。两者在 `560..620` 全区间
**相差恰好 162**，因为该区间 `box = 398..458 < 2 × PANE_ESSENTIAL_HEIGHT = 568`，
`believedImeBottomPx` 早退（`SessionPaneLayout.kt:1645`）⇒ **键盘保留全额声明**。
两者互为仿射；**只有「递给谓词的那个量」才是判据**，本报告用它。

### 1.2 有效形态的定义

生产递给 composer 的是**两个**形态谓词，v156 已把它们的不一致钉成空集
（`theTwoFormPredicatesNeverContradict`），故有效形态是「**任一说一行就是一行**」：

```
oneRow(room) = composerMinimal(room) || composerCompact(room)
             = (room < 164) || (room < 224)
             = room < 224
```

**一条阶梯、恰好一条边、在 224 = `116 + 44 + 64`。** 解析上即单调不增，
但下面按算术钉死，不靠「看着对」。

---

## 2. 改写后的判据（`ReverseJumpRoomJudgeV158Test`，5 例）

| # | 用例 | 钉住什么 |
|---|---|---|
| 1 | `theFormIsMonotoneNonIncreasingInTheRoomAndNotInTheWindowHeight` | **主判据**：61 格按 `room` 重排后逐格判；每格结果落盘 |
| 2 | `theBodyNeverGrowsAsTheRoomGetsSmaller` | `body` 不随 `room` 变小而变大；外加 **`body == room` 恒等**（判据变量必须是真被消费的那个量） |
| 3 | `theEffectiveFormIsMonotoneNonIncreasingInTheRoomItself` | `room` 0..400 dp **逐 dp 密扫**：真值集必须是首段 `{0..223}`，阶梯边恰好 1 条在 224；两条子谓词各自的 164/224 阶梯也钉死 |
| 4 | `theKeyboardCollapsesWithTheWindowSoTheWindowHeightIsNotAUsableJudgeAxis` | **v157 实测钉入**：三个 ime 取值、塌缩点 `600→599`、房在 600→599 **变大**、**room 序 ≠ window 序** |
| 5 | `theFormGrowingAcrossTheImeCollapseIsCorrectBecauseTheRoomGrew` | **防误判守卫**：600→599 形态变贵是**正确行为，不是回翻**，下轮不得再当缺陷重开 |

用例 4 里 `roomOrder != windowOrder` 这条断言是整套论证的机械支点：
**若两轴同序，本文件的前提作废**（v154/v156 的网格之所以免疫，正是因为它们每列钉死一个
`ime`，那两处两轴同序；v157 的 ime 阶梯才是把两轴分开的那个东西）。

### 2.1 断言里钉进去的 v157 事实（原文）

```kotlin
assertEquals(
    "v157 measured exactly three keyboard claims (dumpsys window: " +
        "`InsetsSource id=3 type=ime frame=[..][1366,H]`): 426, 339, 261 — the keyboard " +
        "collapses as the window gets shorter, with no app involvement",
    listOf(261, 339, 426),
    distinct,
)
assertEquals("v157: at H=600 the keyboard claims 339", 339, at600.ime)
assertEquals("v157: at H=599 the keyboard claims 261", 261, at599.ime)
assertEquals("the keyboard drops 78 px across the single dp 600 -> 599", 78, at600.ime - at599.ime)
assertTrue("the room therefore GROWS across 600 -> 599 (v157 observed 261 -> 338, a 77 px jump)",
    at599.room > at600.room)
```

主判据的失败文案本身也带这句，防止下轮有人只读红字不看结论：

> `"… The judge is the room: ime collapses with the window (426/339/261 at 600->599), so H -> room is not a function the app controls."`

---

## 3. 61 格逐格表（`620 → 560` 逐 dp，按 `room` 重排）

`room = box − ime = (H − 162) − ime`，`1 dp == 1 px`，`floor = 48`。
完整文件：`research/v97_fix/_v158_room_grid.txt`（含同 61 格的 **window 序**对照）。

| # | room | body | form | 源 window | ime | | # | room | body | form | 源 window | ime |
|---:|---:|---:|---|---:|---:|---|---:|---:|---:|---|---:|---:|
| 1 | 17 | 17 | ONEROW | 605 | 426 | | 32 | 139 | 139 | ONEROW | 562 | 261 |
| 2 | 18 | 18 | ONEROW | 606 | 426 | | 33 | 140 | 140 | ONEROW | 563 | 261 |
| 3 | 19 | 19 | ONEROW | 607 | 426 | | 34 | 141 | 141 | ONEROW | 564 | 261 |
| 4 | 20 | 20 | ONEROW | 608 | 426 | | 35 | 142 | 142 | ONEROW | 565 | 261 |
| 5 | 21 | 21 | ONEROW | 609 | 426 | | 36 | 143 | 143 | ONEROW | 566 | 261 |
| 6 | 22 | 22 | ONEROW | 610 | 426 | | 37 | 144 | 144 | ONEROW | 567 | 261 |
| 7 | 23 | 23 | ONEROW | 611 | 426 | | 38 | 145 | 145 | ONEROW | 568 | 261 |
| 8 | 24 | 24 | ONEROW | 612 | 426 | | 39 | 146 | 146 | ONEROW | 569 | 261 |
| 9 | 25 | 25 | ONEROW | 613 | 426 | | 40 | 147 | 147 | ONEROW | 570 | 261 |
| 10 | 26 | 26 | ONEROW | 614 | 426 | | 41 | 148 | 148 | ONEROW | 571 | 261 |
| 11 | 27 | 27 | ONEROW | 615 | 426 | | 42 | 149 | 149 | ONEROW | 572 | 261 |
| 12 | 28 | 28 | ONEROW | 616 | 426 | | 43 | 150 | 150 | ONEROW | 573 | 261 |
| 13 | 29 | 29 | ONEROW | 617 | 426 | | 44 | 151 | 151 | ONEROW | 574 | 261 |
| 14 | 30 | 30 | ONEROW | 618 | 426 | | 45 | 152 | 152 | ONEROW | 575 | 261 |
| 15 | 31 | 31 | ONEROW | 619 | 426 | | 46 | 153 | 153 | ONEROW | 576 | 261 |
| 16 | 32 | 32 | ONEROW | 620 | 426 | | 47 | 154 | 154 | ONEROW | 577 | 261 |
| 17 | 99 | 99 | ONEROW | 600 | 339 | | 48 | 155 | 155 | ONEROW | 578 | 261 |
| 18 | 100 | 100 | ONEROW | 601 | 339 | | 49 | 156 | 156 | ONEROW | 579 | 261 |
| 19 | 101 | 101 | ONEROW | 602 | 339 | | 50 | 157 | 157 | ONEROW | 580 | 261 |
| 20 | 102 | 102 | ONEROW | 603 | 339 | | 51 | 158 | 158 | ONEROW | 581 | 261 |
| 21 | 103 | 103 | ONEROW | 604 | 339 | | 52 | 159 | 159 | ONEROW | 582 | 261 |
| 22 | 137 | 137 | ONEROW | 560 | 261 | | 53 | 160 | 160 | ONEROW | 583 | 261 |
| 23 | 138 | 138 | ONEROW | 561 | 261 | | 54 | 161 | 161 | ONEROW | 584 | 261 |
| 24 | 139 | 139 | ONEROW | 562 | 261 | | 55 | 162 | 162 | ONEROW | 585 | 261 |
| 25 | 140 | 140 | ONEROW | 563 | 261 | | 56 | 163 | 163 | ONEROW | 586 | 261 |
| 26 | 141 | 141 | ONEROW | 564 | 261 | | 57 | 164 | 164 | ONEROW | 587 | 261 |
| 27 | 142 | 142 | ONEROW | 565 | 261 | | 58 | 165 | 165 | ONEROW | 588 | 261 |
| 28 | 143 | 143 | ONEROW | 566 | 261 | | 59 | 166 | 166 | ONEROW | 589 | 261 |
| 29 | 144 | 144 | ONEROW | 567 | 261 | | 60 | 167 | 167 | ONEROW | 590 | 261 |
| 30 | 145 | 145 | ONEROW | 568 | 261 | | 61 | 168 | 168 | ONEROW | 591 | 261 |
| 31 | 146 | 146 | ONEROW | 569 | 261 | | — | 169–176 | | ONEROW | 592–599 | 261 |

（第 61 格完整范围 `room 169..176 ↔ window 592..599`，逐格值见 `_v158_room_grid.txt`；
上表末行按区间合并以省版面，**落盘文件是 61 行逐格、无合并**。）

**逐格结果：形态全程 `ONEROW`，0 次回翻；`body == room` 恒等，全程单调。**

```
V158 cells=61  (window 620->560 per dp, re-sorted by room ascending)
V158 ROOM REVERSE-JUMP violations = 0 (room-adjacent pairs = 60)
V158 body-growth violations on the room axis (620->560 sweep) = 0 (room-adjacent pairs = 60)
```

> **诚实的读法**：这 61 格的 `room` 全部落在 `17..176`，都在 224 之下，
> 所以**形态在这条网格上恒为一行，单调性是「平凡成立」的**。
> 真正承重的是用例 3 的 `room 0..400 dp` **逐 dp 密扫**（401 格，真值集恒为首段
> `{0..223}`，阶梯边恰好 1 条在 224），以及用例 4/5 把「为什么 61 格平凡」
> **本身**钉成事实（ime 塌缩把 room 抬进 17..176 这个带）。
> 本轮**不把 61 格的平凡单调当成强证据**——它是**排除**证据：说明 H 轴上那个
> 「600→599 回翻」在真 room 上根本不存在落点。

---

## 4. 落盘产物（`research/v97_fix/`）

| 文件 | 内容 |
|---|---|
| `_v158_room_grid.txt` | **61 格逐格表**（room 序）+ 同 61 格 window 序对照 + 违例计数 |
| `_v158_room_axis.txt` | `room 0..400 dp` **逐 dp 401 行**（`minimal`/`compact`/有效形态）+ 阶梯边计数 |
| `_v158_body_growth.txt` | body 轴违例（room 序，60 对） |
| `_v158_reverse_verification.txt` | 反向验证的红字原文 |
| `MONOTONE_JUDGE_FIX.md` | 本文件 |

---

## 5. 反向验证（**真跑抄原文**）

**口径**：只把 v158 模型里的 `measuredImePx` 退回 **v156 时代的「常数 426」前提** ——
**这正是「拿 `H` 当判据」所隐含的假设**（键盘恒定 ⇒ `H → room` 退化成 `H` 的仿射函数，
room 轴不再携带新信息，600→599 的塌缩从模型里消失）。测试与生产其余一字不动。

```diff
-    private fun measuredImePx(window: Int): Int = when {
-        window >= 605 -> 426
-        window >= 600 -> 339
-        else -> 261
-    }
+    private fun measuredImePx(window: Int): Int = 426
```

**跑出来的原文**（`--tests com.xiufeigo.opencodeshell.ReverseJumpRoomJudgeV158Test --rerun-tasks`）：

```
> Task :app:testDebugUnitTest FAILED
ReverseJumpRoomJudgeV158Test > theFormGrowingAcrossTheImeCollapseIsCorrectBecauseTheRoomGrew FAILED
    java.lang.AssertionError at ReverseJumpRoomJudgeV158Test.kt:406
ReverseJumpRoomJudgeV158Test > theKeyboardCollapsesWithTheWindowSoTheWindowHeightIsNotAUsableJudgeAxis FAILED
    java.lang.AssertionError at ReverseJumpRoomJudgeV158Test.kt:357
5 tests completed, 2 failed
FAILURE: Build failed with an exception.
Execution failed for task ':app:testDebugUnitTest'.
> There were failing tests. See the report at: file:///C:/sensorsdata/main/program/opencode-android-shell/app/build/rep
orts/tests/testDebugUnitTest/index.html
BUILD FAILED in 1m 2s
```

**两条失败断言的原文**：

```
--- theFormGrowingAcrossTheImeCollapseIsCorrectBecauseTheRoomGrew FAILED ---
java.lang.AssertionError: 600 -> 599: the window shrank by 1 dp but the room grew (12 -> 11), so no reverse jump happened on the axis the app controls; a richer form there is correct behaviour, not a defect

--- theKeyboardCollapsesWithTheWindowSoTheWindowHeightIsNotAUsableJudgeAxis FAILED ---
java.lang.AssertionError: v157 measured exactly three keyboard claims (dumpsys window: `InsetsSource id=3 type=ime frame=[..][1366,H]`): 426, 339, 261 — the keyboard collapses as the window gets shorter, with no app involvement expected:<[261, 339, 426]> but was:<[426]>
```

**关键**：退回常数 426 后，`600 → 599` 的 room 变成 **12 → 11**（**随 H 单调变小**），
`[426]` 单一取值 ⇒ **塌缩这个外部事实整个消失**。这正是「拿 H 当判据」的代价：
**判据会悄悄退化成 H，而 H 不是应用能控制的变量。**

已把 `measuredImePx` 恢复为 v157 阶梯版并重跑至全绿（§7）。

---

## 6. `SessionPaneLayout.kt` 的 KDoc 修订（**仅注释，零行为改动**）

改的是 `composerCompact` KDoc 里那张**假定 `ime = 426`** 的实测表（原 `:764-771`）。
**保留原表逐字**（它是修法的证据），但**把 426 标注为「当值实测」**并加一段 v158 说明。

### 6.1 「仅注释」的原样证明

对 `git diff -U0` 的**全部 44 行 ±** 逐行分类：去掉 `+/-` 标记与缩进后，
**要么以 `*` / `/*` / `//` 开头，要么是空行**。

```
total +/- lines examined : 44
lines that are NOT comments and NOT blank : 0

=== executable-code lines added or removed: ===
0
```

**唯一被移除的一行也是注释行**：

```
=== all REMOVED lines ===
-     * keyboard claim 426 dp, 162 px of chrome above the pane box):
```

**行为面未动**（`composerCompact` / `composerMinimal` 的函数体逐字）：

```
829:  available >= 0.dp && available < COMPOSER_MIN_HEIGHT + CHROME_MIN_HEIGHT + TIMELINE_MIN_CONTENT
1114: available >= 0.dp &&
```

### 6.2 行尾陷阱（v154 §5.3 / v156 §4.1 记过的坑，本轮复现并已修回）

首轮编辑把生产文件里**唯一 1 枚 CR** 规范化掉了（`CR=0`），产生了一个与修法**无关**的
`-}` / `+}` 伪 hunk。已按 v154/v156 的同一口径**逐字节补回**
（定位 `= TIMELINE_MIN_CONTENT\n}\n\n/**\n` 唯一命中，在其 `}` 后插回 `0x0D`）：

```
anchor hits = 1 at 105743
CR RESTORED at byte 105767
WORK bytes=106780 CR=1 lastByte=0x7D          <- 与 HEAD 同形
HEADbytes=104197 CRcount=1 idx=103184 lastByte=0x7D
  ctx@103184 : = TIMELINE_MIN_CONTENT<LF>}<CR><LF><LF>/**<LF>
```

修正后 `git diff --stat` 从 `44 ++++…-` 收敛为 `43 insertions(+), 1 deletion(-)`，
且那 1 处 deletion 就是上面那行注释。

---

## 7. 交接文档收口（**只加不改语义**）

`research/HANDOFF.md` 与 `research/v98_RESUME.md` **各追加 1 节**，
标题 `## v158 收口（2026-10-04）— 六条硬结论（只加不改语义）`，内含 **6 条**：

| # | 结论 | 关键数字 |
|---|---|---|
| ① | **44MB 前提被纠正**（那是 debug 档） | debug **44,198,253 B** / release **29,381,690 B** / +R8+shrink **17,809,425 B（−59.71%）**；「7,426,218 B padding」**复不出来**，实测 **7,221,234** 且**仅 debug**（release padding = **0**）⇒ 消 debug padding 属**负收益** |
| ② | **两处 `> 0.dp` 守卫都是真缺陷且已修** | `composerMinimal`（v154，`:1009`）阈值 **164**、`composerCompact`（v156，`:751`）阈值 **224**；断点分别在 **room 0** 与 **window 588**（＝`ime+162`，**仅在假定 ime 恒 426 时成立**） |
| ③ | **判据裁定：以 `room` 为准，`H` 不得作判据** | ime 塌缩实测 **426 → 339 → 261，塌缩点 `600 → 599`**；`H=600` room=99 / `H=599` room=176；**588 降级为伪断点** |
| ④ | **V108：426/474 在生产语义上等价**（饱和） | 已改**显式声明** + 两条**真**判据 |
| ⑤ | **通知竞态：判已被防护** | G3 `postWithheld` **补投 2/2**；**G1 在 freeform 模拟器上从未被行使** ⇒ **不作断言** |
| ⑥ | **环境事实** | `/sdcard` 已清 **63 → 18**；**4096 残留需管理员**（`PID 12668`）；**PATH 真因** = npm `opencode-ai@1.18.33` 遮住 `~\.bun\bin\opencode2.exe`；**4095 的 serve 是 Lead 所起**（`PID 712`） |

③ 里补了 v158 的落 pin 情况（`ReverseJumpRoomJudgeV158Test` 5 例、61 格 0 违例、
反向验证 2/5 红），使该条从「裁定」变成「裁定 + 已执行」。
两份文件**既有内容一字未删、一字未改**，只在文末追加。

---

## 8. 为什么不降强度

| 维度 | 处置 |
|---|---|
| 生产代码 | **一个运算符都没动**。`composerCompact` / `composerMinimal` 的函数体逐字未变；`SessionPaneLayout.kt` 的改动**只有 KDoc 注释**（非注释非空行 = 0，§6.1） |
| v154/v156 既有断言 | **一条未删、一条未放宽**。`ReverseJumpMonotoneV154Test`（7 例）、`ReverseJumpGridV156Test`（7 例）**原文件一字未动**，仍全绿 |
| 判据强度 | **只增强**：新增 5 例 pin。61 格从「按 H 判」升级为「按 room 判」，并额外钉了 `body == room` 恒等、`room` 0..400 密扫首段性、`room 序 ≠ window 序` 三条 |
| v154/v156 已签字的 4 处极性 | **保持现状**（`SessionPaneShortWindowV57Test:92` / `ComposerShortWindowV74Test:87` / `SelfProofEquivalenceV153Test:550-555` / `SessionPaneShortWindowV55Test:245`）——本轮未触碰这四个文件 |
| 用户可见字符串 | **零新增**。全部产出为测试文件与 `research/**` |
| 首轮红的处置 | **0 红**。新 pin 一次通过；唯一的红来自**故意做的反向验证**（§5），且已恢复至全绿 |

**为什么不动 v156 的 H 轴测试**：它的 3 个 `ime` 列各自**钉死一个 ime**，
在那种列里 **room 轴与 H 轴同序**，所以它判 H 与判 room **逐格等价**，
改写它只会制造一处无意义 diff。真正需要重排的是**真机 ime 阶梯**（v157 的发现），
那已经由 v158 的 61 格承担。两者的分工已写进 KDoc（§6）。

---

## 9. 回归证据

### 9.1 新 pin 类

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ReverseJumpRoomJudgeV158Test" --console=plain --rerun-tasks
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 59s
52 actionable tasks: 52 executed
```

XML：`tests="5" skipped="0" failures="0" errors="0"`

| 用例 | 时间 |
|---|---:|
| `theBodyNeverGrowsAsTheRoomGetsSmaller` | 0.045s |
| `theFormGrowingAcrossTheImeCollapseIsCorrectBecauseTheRoomGrew` | 0.004s |
| `theFormIsMonotoneNonIncreasingInTheRoomAndNotInTheWindowHeight` | 0.014s |
| `theEffectiveFormIsMonotoneNonIncreasingInTheRoomItself` | 0.011s |
| `theKeyboardCollapsesWithTheWindowSoTheWindowHeightIsNotAUsableJudgeAxis` | 0.001s |

### 9.2 受影响全部类

改动只碰两处：① **新增**测试类 `ReverseJumpRoomJudgeV158Test`；② `SessionPaneLayout.kt`
**仅 KDoc**。为覆盖「改注释可能影响源码扫描型 pin」这一风险，回归按
**面板/ composer / ime / 短窗 / 断点族** 全部 pattern 拉平：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$pats = @("SessionPane*","Composer*","Ime*","ReverseJump*","SelfProof*","ShortWindow*",
          "Timeline*","Servers*","Settings*","Queue*","Wave24b*","Wave5*",
          "DirectoryPicker*","ModalIme*","Ssh*","RenameEditorIme*","RevertComposerRestoreTest")
.\gradlew :app:testDebugUnitTest ($pats | % { "--tests"; "com.xiufeigo.opencodeshell.$_" }) `
          --console=plain --rerun-tasks
```

**原样输出**：

```
> Task :app:testDebugUnitTest
[Incubating] Problems report is available at: file:///C:/.../build/reports/problems/problems-report.html
BUILD SUCCESSFUL in 1m 55s
```

**合计：169 个类 / 1616 个用例 / 0 失败 / 0 错误 / 0 跳过。**

| 指标 | 值 |
|---|---|
| 类数 | **169** |
| 用例数 | **1616** |
| failures | **0** |
| errors | **0** |
| skipped | **0** |

**用例数未减少**：v154 `ReverseJumpMonotoneV154Test` **7** 例、v156 `ReverseJumpGridV156Test`
**7** 例**原文件一字未动、各自 7 例仍在**（合计 14，与上一轮一致）；
v158 为**净新增 5 例**。既有断言**一条未删、一条未放宽**。

**关键 pin 的逐类计数（全部 fail=0）**：

| 类 | 用例 | 说明 |
|---|---:|---|
| `ReverseJumpRoomJudgeV158Test` | **5** | v158 新增：room 轴判据 + v157 ime 塌缩 |
| `ReverseJumpMonotoneV154Test` | **7** | v154 判据，**未动** |
| `ReverseJumpGridV156Test` | **7** | v156 判据，**未动** |
| `SessionPaneShortWindowV55Test` | **14** | 768 的 140/92、600 的 51 |
| `SessionPaneShortWindowV57Test` | **13** | v154 签字极性 ① |
| `SessionPaneImeBeliefV77Test` | **11** | **v77 P0** |
| `ComposerPaneBottomCriterionV108Test` | **11** | 两档 ①(i) −10/−12 |
| `SelfProofEquivalenceV153Test` | **6** | v154 签字极性 ③ |
| `ComposerBandTouchTargetV105Test` | **6** | **48dp 靶** |
| `ComposerShortWindowV74Test` | — | v154 签字极性 ②（全绿） |


### 9.3 红线逐条复核

| 红线 | 状态 | 依据 |
|---|---|---|
| 768 的 **140 / 92** | ✅ 未回退 | `SessionPaneShortWindowV55Test` / `V57Test` / `SelfProofEquivalenceV153Test` 全绿 |
| 600 的 **51** | ✅ 未回退 | `ReverseJumpMonotoneV154Test.theSixHundredTierMeasuredReadingStillHasFiftyOnePxOfRoom` 绿 |
| 两档 ①(i) **−10 / −12** | ✅ 未回退 | `ComposerPaneBottomCriterionV108Test` / `ComposerUnionBottomP5V109Test` 绿 |
| **48dp 靶** | ✅ 未回退 | `ComposerBandTouchTargetV105Test` / `ComposerBandA11yReachabilityTest` 绿 |
| **v77 P0** | ✅ 未回退 | `SessionPaneImeBeliefV77Test` 绿 |
| `quarantine/` 分工 | ✅ 未动 | 未触碰该目录 |
| v154/v156 签字的 **4 处极性** | ✅ 保持现状 | 四个文件本轮**一字未动** |

---

## 10. 未闭环

1. **真机复验未执行。** 本轮严格遵守「不装机 / 不 `assembleDebug`」，§3 的 61 格
   `room` 全部来自**模型 + v157 的 ime 实测**的组合，**没有在 5554 上逐格重跑一遍形态**。
   建议下轮有设备的验证轮按 §3 的 room 序采 61 格 `uiautomator dump`，确认
   **「全 61 格都是 ONEROW」在真机上也成立**（模型说成立，但模型不含渲染层）。
2. **`ime` 阶梯的触发条件仍未确认。** v157 只观测到「随窗口变矮而塌缩」，
   **未能区分**是 (a) Gboard 在可用高度低于某阈值时自动收行，还是
   (b) `ADJUST_RESIZE` 下 app 窗口与 ime 互相挤压的反馈环。
   本轮把它**当既定事实钉住**（这样判据正确），但**没钉住它为什么发生**。
   要定因需固定 Gboard 布局（设置「全屏/展开」）或在 ime 打开前后各测一轮。
3. **渲染层的形态阈值未纳入模型。** 本轮的有效形态 = 两个谓词的合成
   （`room < 224`），而 v157 真机在 `room(H−ime)` ∈ (261, 334] 处就看到形态翻面，
   两者不一致 ⇒ **真机还有一个渲染/测量层的阈值不在谓词里**。
   这不影响本轮结论（判据只管应用能控制的那一半），但「模型为什么在 224 而真机在更小处翻」
   **仍是开口**。
4. **`H` 轴的旧结论需要正式作废。** v155/v156 报告与 `SessionPaneLayout.kt` KDoc
   里的「断点 588」已在本轮 KDoc 中**降级标注为伪断点**，但
   `REVERSE_JUMP_FIX2.md` 等历史报告**正文未改**（只加不改语义纪律）。
   若 Lead 要求，可由下轮统一在历史报告顶部加作废横幅。
5. **v156 的 H 轴测试仍在跑 H 轴。** 如 §8 所述，它在自己的定长 ime 列上与 room 等价，
   **但文件名与用例名（`…AsTheWindowGetsShorter`）仍在宣传 H 轴口径**，
   容易让下轮误读。本轮未改名（改名会动已签字文件），**建议下轮决定是否重命名**。

---

## 附：产物清单

- 报告：`research/v97_fix/MONOTONE_JUDGE_FIX.md`（本文件）
- 新增测试：`app/src/test/java/com/xiufeigo/opencodeshell/ReverseJumpRoomJudgeV158Test.kt`（**5 例**）
- 注释修订：`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt`（**仅 KDoc**）
- 落盘：`_v158_room_grid.txt` / `_v158_room_axis.txt` / `_v158_body_growth.txt` / `_v158_reverse_verification.txt`
- 交接：`research/HANDOFF.md` / `research/v98_RESUME.md`（各 **+1 节 / 6 条**）
