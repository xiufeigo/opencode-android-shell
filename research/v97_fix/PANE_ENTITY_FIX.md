# PANE_ENTITY_FIX —— v123：用真机 insets 读数**判掉 −8px**，并核 A（768 档 pane 多算 144px）

- 修复工程师：mavis（v123，用 mcode 执行）
- 日期：2026-10-04
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 前序：`DIVERGENCE_LOCATE.md`（v119，A/B 定位到行）、`DIVERGENCE_LOCATE2.md`（v120，60 归因 + §2.3 的 −8px 读数清单）
- **新输入（Lead 第 3 次终验）**：`research/v97_verify/5554_md/REPORT_FINAL_VERIFY.md` 的
  **insets 原文（768 档）** —— §2.3 清单一直缺的最后一块拼图。
- 授权边界：只碰 `ui/session/**`（`SessionPaneLayout.kt` / `SessionScreen.kt`）+ 测试 + 本报告；
  **未**碰 `_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers/**`、`ui/home/**`、
  `ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`。
  **未** assembleDebug、**未** clean、**未**装机。
- 断言纪律：**不降、只增或保持**。v103–v120 全部 pin **一字未改**（本轮**零 pin 改动**）。
  **本轮没有新增任何用户可见字符串**。
- **本轮最终生产代码净改动 = 零**（试改已全部回退，见 §2）。

---

## 0. 状态（先给结论）

| 项 | 结论 |
|---|---|
| **−8px** | ✅ **判掉了** —— 不是 nav bar floor、不是取整，是**模型用了一个错的 `imeBottomPx`**。§2.3 五条判据逐条判完，① 判否、② 判否、③ 判否、④ 已静态证否、⑤ 落定 `false`。**实测口径下残差 = 0，−8px 不存在**。见 §1 |
| **A（144px）** | 🔴 **未落地 —— 照任务书改法会破 v77 P0，已回退**。把 `CHROME_FULL_HEIGHT` 从 `PANE_ESSENTIAL_HEIGHT` 去掉，当场击穿 v77「陈旧浮动 IME」防护：5554 实测那个 pane 会以 226 dp 照发，即**「丢工具栏/tab 条」复发**。试改 → **13 条红** → 全部回退 → **97/97 绿**。见 §2 |
| **前提修正** | ⚠️ **「768 多算 144px」这个数本身站不住**：它是按 `floorPx = 0`、`imeBottomPx = 474` 算的；insets 原文说 `floorPx = **48**`、`imeBottomPx = **426**`。代入实测输入，768 多算量是 **96，不是 144**（正好差一个导航条 48）。见 §3 |
| **600 档** | ✅ 试改全程**一个像素没动**：V103/V105/V106/V107/V108/V109/V118 在那 13 红里**全部保持绿** ⇒ v119 §4.1 担心的「600 被拖下水」在本次改法下**未发生**。回退后 13 类 **97/97 绿**。见 §4 |
| **pin 有没有被放宽** | ✅ **没有。零 pin 改动，一个断言都没动。** |
| **设备基线**（`readable` 92/51、时间线盒 140、缩窗往返）**未动**（零生产改动、未装机）。复验步骤见 §7 |

---

## 1. −8px 判定（**用 insets 原文判掉**）

### 1.1 先把 −8 的来路摆正

`DIVERGENCE_LOCATE2.md` §2.2 的算式：

```
模型：paneBottom = maxOf(floor, 474) = 474 ⇒ panePx = 606 − 474 = 132 ⇒ paneBottom = 162 + 132 = 294
设备：paneBottom = 302
差  ：302 − 294 = 8   ← 【这 8】
```

**这个算式里有一个从没被质疑过的输入：`474`。** `474` 反推出的键盘顶边 = `768 − 474 = 294`，
是**当时假设的**值。insets 原文直接否掉它：

```
InsetsSource id=3 type=ime frame=[0,342][1366,768] visibleFrame=[0,342][1366,768] visible=true
```

⇒ **键盘真实顶边 = 342**，`ime.getBottom()` = `768 − 342 = **426**`，**不是 474**。
**`474 − 426 = 48`** —— 而 §2.3 的 ① 恰好在问「nav bar 是不是 8」，实测 nav bar 是 **48**。
**两个数是同一个 48。** 这个 −8 是**一个 48 的差被一个错的 8 投影出来的残影**，不是真实缺口。

### 1.2 §2.3 五条判据**逐条判完**

| # | 判据（v120 §2.3 口径） | 实测 / 判定 | 结论 |
|---|---|---|---|
| **①** | `navigationBars` 底 inset **= 8** ⇒ §2.2 成立、`140 = 132 + 8` 逐位、−8 闭环；**≠ 8 ⇒ 否掉 §2.2，转 ②** | `type=navigationBars frame=[0,720][1366,768]` ⇒ 底 inset = `768 − 720 = **48**` | **判否**（48 ≠ 8）⇒ §2.2 领先假设**死亡**，`paneFloorPx` **不是 8** |
| **②** | `ime.getBottom()` 是否等于键盘实际顶边 | `frame=[0,342][1366,768]` 是**键盘窗自己的 frame** ⇒ `getBottom() = 426` **诚实**；模型那个 `474`（⇒ 顶边 294）不存在 | **判否**（「474 虚高 8」不成立） |
| **③** | `paneTop` 是否真为 162 | 第 3 次终验 dump：pane 容器 `[0,162][1366,350]` ⇒ `top = **162**` ✅ | **判否**（上沿正确）⇒ −8 若真存在必在盒**内部** |
| **④** | **取整方向** | `wm density 160` ⇒ 倍率 `1.0`；`SessionScreen.kt:2361` 的 `maxHeight.roundToPx()` 与 `:2371` 的 `.toDp()` 互为精确逆运算 | **已静态证否**（无需真机读数） |
| **⑤** | `titlebarBottom` 取值（决定 `paneFloorPx` 走 0 还是 `navBottomPx`） | `nonDecorInsets=[0,0][0,48]`、`nonDecorFrame=[0,0][1366,720]`；而生产实测 `paneBoxPx = **606** = 768 − 162`（**不是** `720 − 162 = 558`）⇒ app 自己的盒**一直量到窗口底**、nav bar 没被平台从盒里扣掉 | **落定 `titlebarBottom = false`**（`:2363` 走 `navBottomPx` 分支 ⇒ `paneFloorPx = 48`） |

**判定顺序**（v120 §2.3 末句）：① 命中即闭环，否则 ②→③。**① 判否 → ② 判否 → ③ 判否**；
**④ 早已证否**；**⑤ 落定**。⇒ **三条可能来源全否。**

### 1.3 判定：**不是取侧、不是取整，是「模型用了一个错的 imeBottomPx」**

```
768 档实测口径（wm density 160 ⇒ 1dp == 1px；坐标全部 insets / dump 原文）：

  ime 顶边     = 342        insets: type=ime frame=[0,342][1366,768]
  ime 底 inset = 768 − 342 = 426       （= WindowInsets.ime.getBottom 真值）
  nav bar 底   = 768 − 720 = 48        insets: type=navigationBars frame=[0,720][1366,768]
  nonDecor 底  = 720                   insets: nonDecorFrame=[0,0][1366,720]
  paneTop      = 162                   dump: pane 容器 [0,162][1366,350]
  paneBoxPx    = 768 − 162 = 606       SessionScreen.kt:2361（实测值）

  768 生产 pane 底（钳位不生效、如实相信键盘）= 162 + (606 − 426) = 162 + 180 = 342
  键盘真实顶边                                  = 342
  ⇒ 残差 = 342 − 342 = 0                        ← 【既没有 8，也没有 −8】
```

**一句话**：**−8px 是算式里错值的投影，不是几何缺口。**
`paneBottom = 294` 用的 `imeBottomPx = 474`；真机 **426** 把它推到 **342**，
而 **342 就是键盘自己的顶边** ⇒ **残差逐位为 0**。
`474 − 426 = 48` 与 nav bar 的 `48` 同值 ⇒ **那个 474 的来源正是「把 nav bar 那一带也算进键盘了」**。

⇒ **候选行 `SessionScreen.kt:2362-2363` 判为「不是根因」**：它确实读出 **48**，
但 48 **小于** 426，`maxOf(paneFloorPx, believedImeBottomPx)`（`:2370-2371`）**不改变结果**
（`48 < 426`）⇒ **它在这条账上一个像素都没付**。
600 档同理（`387 > 48`）。**⇒ 保留该行、语义正确、不改**（改它只会动 V120 用例 5 的结构 pin，零收益）。

### 1.4 设备侧还剩一个 8：它**不是** −8，是 +2 的另一面（B，不在本轮授权内）

```
pane 容器底 350
  = 工具行 44dp **盒**底 338 + 12 (COMPOSER_REGION_PADDING, :1449)   ⇒ 350 − 338 = 12 ✅
工具行 48dp 触摸靶（:108）在 44dp 盒里**居中**（Composer.kt:2152）
  ⇒ 靶底 340 比盒底 338 探出 2                                        ← v119 的 B
并集底 340 vs 容器底 350      ⇒ 350 − 340 = 10 = 12 − 2 ✅
判据① dump 侧 : 340 ≤ 350   ⇒ **−10**  ✅（第 3 次终验 §2.1）
判据① 生产口径 : (340−48+12)=304 vs (350−48)=302 ⇒ **+2** 越界
  ⇒ 304 − 302 = 2 = **那 2px 居中探出**
```

⇒ 设备侧这个 8 与 −8 **不是同一笔**：它是 `region 12 − 居中探出 2 = 10` 与 `ime 顶 342` 的差，
属 **B（`Composer.kt:2150/2152` 居中取整）**。B 的最小改动被 **6 条既有 pin 逐字锁住**
（`ComposerEditorFormV80Test.kt:148/154/261`、`V106Test:216-219`、`V104Test:495-496`、
`V105Test:480-481`、`V86Test:328`、`V75Test:141`），**超出本轮授权** ⇒ **只记账不修**。

---

## 2. 🔴 A 未落地：照任务书改法会**破 v77 P0**（试改 → 13 红 → 全量回退）

### 2.1 我按任务书做了什么

`SessionPaneLayout.kt:1467-1468` 去掉 `CHROME_FULL_HEIGHT`，并按「必须与 `:1539` 同改」把闸门
改成吃**满形态**，使阈值**逐位保持 568**（保 600 档短路）：

```kotlin
// 试改（已回退）
val PANE_ESSENTIAL_HEIGHT: Dp =
    TIMELINE_MIN_HEIGHT + COMPOSER_MIN_HEIGHT + COMPOSER_REGION_PADDING      // 284 → 200
val PANE_ESSENTIAL_FULL_HEIGHT: Dp = CHROME_FULL_HEIGHT + PANE_ESSENTIAL_HEIGHT  // 284

fun believedImeBottomPx(
    visibleBoxPx: Int, floorPx: Int, imeBottomPx: Int, essentialPx: Int,
    chromeFullPx: Int = CHROME_FULL_HEIGHT.value.toInt(),        // 新增参数（保持 568 门槛）
): Int {
    ...
    if (visibleBoxPx < (essentialPx + chromeFullPx.coerceAtLeast(0)) * PANE_ESSENTIAL_SPARE_ROOM) {
        return imeBottomPx
    }
    ...   // 闸门逐位保持 568；600 的 438 < 568 仍短路 ⇒ 600 一个像素不动
}
```

`SessionScreen.kt:2364-2370` 按密度显式传 `chromeFullPx`。
**600 档保护成功**（见 §4），**但 v77 塌了**。

### 2.2 v77 塌在哪：逐位算式（测试密度 2.625，由 `px(200) = 525` 反推确证）

v77 的对象是 `research/v73_verify/5554/61_session_tab.png`（1344x2992 @420dpi）
的**陈旧浮动 IME 窗**。测试输入（`SessionPaneImeBeliefV77Test.kt:136-148`）：

```
window = 2992 ; strip = 267 ; box = 2992 − 267 = 2725 ; nav = px(24) = 63 ; ime = 2992 − 797 = 2195
旧 essential = px(284) = 745 ;  新 essential = px(200) = 525 ;  chrome = px(84) = 220
闸门 旧 = 745×2 = 1490 ;  闸门 新 = (525+220)×2 = 1490      ← 逐位保持 ✅（这步设计是对的）
```

**旧（现状，防护生效）**：

```
room = 2725 − 745 = 1980 ; claimed = 2195 − 63 = 2132 ; 2132 > 1980 ⇒ 触发钳位
believed = 1980 + 63 = 2043 < 2195                              ✅ 被压住
键盘之上只剩 2725 − 2132 = 593 px = 226 dp  <  745             ⇒ 「塌了」被检出
```

**新（去掉 chrome 后，防护失效）**：

```
room = 2725 − 525 = 2200 ; claimed = 2132 ; 2132 ≤ 2200 ⇒ **不触发钳位**
believed = 2195（**原样相信**）                                  ❌ 防护没了
键盘之上剩 2725 − 2132 = 593 px = 226 dp ；  593 > 525 ⇒ 钳位自己的判据说「没塌」
```

⇒ **226 dp 正是 v77 实测的那个塌陷**——同一个测试自己就把它钉着
（`SessionPaneImeBeliefV77Test.kt:182-192`：`collapsedBody = 593 px = 226 dp` 时
`chromeMaxHeightForComposer(226dp, COMPOSER_COMPACT_HEIGHT) < CHROME_FULL_HEIGHT`，
即 **header 行被裁、view tab 条整条消失** = 原始的「丢工具栏/tab 条」）。

### 2.3 为什么这不是「fixture 过时」，而是**真的退化了**

`PANE_ESSENTIAL_HEIGHT` 在 `believedImeBottomPx` 里的角色**不是尺寸预算，是塌陷阈值**：
v77 那一挡存在的全部意义，就是「**不许把 pane 压到付不起自己 chrome 的高度以下**」。
要检出这件事，被比较的那个数**必须**含 chrome。
**把 chrome 抽走 = 把「pane 付不起 chrome」这件事变得不可见** ⇒ 防护必然失效。

⇒ **`PANE_ESSENTIAL_HEIGHT` 不能被当成 768 那个「多算 144」的可调旋钮。**
这两笔账（v77 要 chrome 在阈值里 / 768 说 chrome 被二次计入）**在同一个常数上直接对撞**，
**不是措辞问题，是同一个变量承担了两个互斥语义**。

### 2.4 试改的失败清单（真跑，13 红，**未改任何断言**）

```
97 tests completed, 13 failed
BUILD FAILED in 14s

V119  the seven six eight tier is the only measured one that trips the v99 clamp
        => 600's pane box (438) must stay under the gate (400) — so 600 is untouched
           expected:<284> but was:<200>            ← 该 pin 仍按「essential 即闸门」算
V119  the seven six eight production pane is pinned to the essential form and overruns the device by one hundred forty four
        => 768: production pane bottom 446 ... expected:<446> but was:<362>
V119  eighty four of the one forty four is the chrome band charged to a box that starts below it
        => 84 of the 144 is the chrome double count   expected:<84> but was:<0>
V119  the one forty four is load bearing it breaks for every essential but the production one
        => exactly one essential in [200,320] reproduces 144 — the production one
           expected:<1> but was:<0>
V119  both pins are pinned on production values so a fix has to break them loudly
        => expected:<284> but was:<200>
V120  the sixty is the stacked floor minus the one row floor digit exact
        => and that residual is the 60 v119 left unaccounted for  expected:<60> but was:<-24>
V120  the seven six eight tier is stacked only because the clamp fed the form decision its own output
        => the 116 in PANE_ESSENTIAL_HEIGHT is the floor of the form the clamp's own output selected
           expected:<116> but was:<32>
V120  removing the chrome alone cannot touch the sixty the body still clears the one row threshold
        => v119's proposal: essential drops from 284 to 200   expected:<200> but was:<116>
V118  theSevenSixEightProductionPaneDisagreesWithTheDeviceAndThatIsBooked
        => the v99 clamp fires on 768: panePx = 606 ? 322 = 284  expected:<284> but was:<200>
V77   the essential form is the sum of the bands the pane cannot lose
        => expected:<284.0.dp> but was:<200.0.dp>
V77   the capped pane can still show its chrome and a message row
        => expected:<284.0.dp> but was:<200.0.dp>
V77   the stale 836 dp frame is capped at the pane's essential form
        => box 2725, believed 2195          ← 【believed 未被压住 ⇒ 防护失效】
V77   the bound only fires on a box with room to spare
        => expected:<525> but was:<1049>
```

**分类**（**没有一条是 600 档退化**）：
- **P0 性质**（1 条）：`V77 the stale 836 dp frame is capped…` —— 真回归，**不可接受**。
- **语义随之改变的 pin**（11 条）：全部在钉「`PANE_ESSENTIAL_HEIGHT` 含 chrome / 768 多算 144」这个
  **已被 v123 判否的前提**。它们**不是**该被「重述成新值」的普通 pin，而是**跟着错误前提一起该退役的账**。
  ⇒ 按任务书「只加严或保持、**不许放宽**」，**我一条都没有改**。

### 2.5 为什么不硬着头皮改 pin

把 `V77 the stale 836 dp frame…` 改成接受「believed = 2195 不压」= **放宽一个 P0 安全断言**，
把 v77 的失效**写进断言里洗成绿的**。任务书写死了「**不许放宽任何断言**」、
且 v77 是 `SessionPaneImeBeliefV77Test` 这条**被点名的 pin**。⇒ **不做。**
⇒ **正确交付是：报告 + 证据 + 修正后的诊断，把落地与否交给裁定。**

### 2.6 回退

两个生产文件均**只含我的改动**（`SessionScreen.kt` diff = `+1` 行；`SessionPaneLayout.kt` = 我的 KDoc/常量/签名），
故用 `git checkout --` **整文件回退到 HEAD**，回退后 `git status --porcelain` 对这两个文件**为空**。
测试文件**全程一字未改**（`git status` 中无 `app/src/test/**` 条目）。
**最终生产代码净改动 = 0。**

---

## 3. ⚠️ 前提修正：「768 多算 144px」这个数按实测输入不成立

v119/v120/第 3 次终验的 446 / 144 全部按 `floorPx = 0` 算。代入 insets 原文（`paneFloorPx = 48`）：

```
768 生产（现状、未改代码）逐位：
  paneBoxPx = 606 = 768 − 162                        SessionScreen.kt:2361
  floorPx   = 48  = 768 − 720                        :2362-2363（titlebarBottom = false）
  imeBottom = 426 = 768 − 342                        insets 原文
  essential = 284 ; 闸门 = 284 × 2 = 568 ; 606 ≥ 568 ⇒ 钳位开    :1539
  room      = 606 − 284 = 322
  claimed   = 426 − 48 = 378 ; 378 > 322 ⇒ 钳位生效
  believed  = 322 + 48 = 370
  panePx    = 606 − 370 = 236
  paneBottom= 162 + 236 = **398**                    ← 现状真值（floor = 48）

设备口径（第 3 次终验 §2.1）：容器底 350 ⇒ 生产口径 paneBottom = 350 − 48 = **302**

⇒ 实测口径下的多算量 = 398 − 302 = **96**，**不是 144**
⇒ 而 144 = 446 − 302 里那个 446 用的正是 **floor = 0**
⇒ **144 虚高了恰好一个导航条 48 = 768 − 720**
```

**同时**：`imeBottomPx` 从 474 改成实测的 426 **对 144 没有任何影响** ——
它只进 `claimed` 与 `room` 的比较（`426 > 322` 与 `474 > 322` 同真），
钳位返回的是 `room + floor`，与 `imeBottomPx` 无关。⇒ **144 的误差源是 `floorPx = 0`，不是 ime。**

**对 v119 分解的影响**：`84 + 60 = 144` 这笔分解**在实测输入下不再闭合**（`84 + 60 = 144 ≠ 96`）。
⇒ **A 的成因分解需要按实测输入重做**，v119 §1.3 的 `84 + 60` **不能直接沿用**。
本轮**不编造**新的分解（§8 记为未闭环）。

---

## 4. 600 档：试改全程**一个像素没动**（v119 §4.1 的担心未发生）

v119 §4.1 警告：去掉 chrome 会让闸门掉到 `200 × 2 = 400`，而 600 的 `438 ≥ 400`
⇒ 钳位在 600 **误开**、600 崩。**本轮用「闸门吃满形态」精确避开了它**：

```
600：paneBoxPx = 438 ; essential(新) = 200 ; chrome = 84
闸门 = (200 + 84) × 2 = **568**   ← 与改前 284 × 2 = 568 **逐位相同**
600 的 438 < 568 ⇒ **短路** ⇒ believed = imeBottomPx 原样 = 387（与改前一模一样）
⇒ panePx = 438 − 387 = 51 ; paneBottom = 162 + 51 = 213 ; ① 生产口径 213/213 差 **0**
⇒ 600 档【一个像素不动】
```

**证据**：那 13 红里，**600 档的七个类全部保持绿** ——
`V103` `V105` `V106` `V107` `V108` `V109` `V118` **无一失败**。
（`V105Test:380`、`V107Test:124`、`V108Test:151/339/355`、`V109Test:306`、`V118Test:169/178`
全部走 `essentialPx()`，闸门 568 不变 ⇒ 短路 ⇒ 结果逐位不变。）

⇒ **结论**：**「闸门必须与 essential 同改」这条联动约束是对的**（保留），
且**可以在不碰 600 的前提下满足**。**卡住的不是联动，是 `:1467-1468` 本身**（§2.3）。

---

## 5. pin 清单：本轮**零改动**（并给出「为什么没改」）

| pin | 试改时的表现 | 本轮处置 | 为什么 |
|---|---|---|---|
| `SessionPaneImeBeliefV77Test:380`<br>`assertEquals(2, PANE_ESSENTIAL_SPARE_ROOM)` | **绿**（试改保留常量 2） | **一字未改** | 任务书点名要保的这条，试改也没碰它 |
| `SessionPaneImeBeliefV77Test`<br>`the essential form is the sum of the bands…` | 红（`284 → 200`） | **一字未改** | 它钉的正是「essential 含 chrome」；**要它变绿就得先证明 chrome 不该在阈值里**，而 §2.2/§2.3 证明**它必须在** ⇒ 前提错误，不改 |
| `SessionPaneImeBeliefV77Test`<br>`the stale 836 dp frame is capped…` | **红（P0 回归）** | **一字未改** | 改它 = **放宽 P0 安全断言** ⇒ 明令禁止 |
| `SessionPaneImeBeliefV77Test`<br>`the bound only fires on a box with room to spare` | 红（`525 vs 1049`） | **一字未改** | 纯 gate 表达式随 essential 变；**可安全重述**，但它只在「A 落地」这个前提下才有意义 ⇒ A 未落地，先不动 |
| `ComposerDivergenceLocateV119Test`（7 条中的 4 条红） | 红 | **一字未改** | 4 条全在钉「768 多算 144 / 84 是二次计入」这个**已被 §3 修正**的前提 |
| `ComposerDivergenceLocate2V120Test`（6 条中的 3 条红） | 红 | **一字未改** | 钉的是自证循环与 60，随 essential 联动；A 未落地，不动 |
| `ComposerCriterionParityV118Test`<br>`theSevenSixEightProductionPaneDisagrees…` | 红（`284 → 200`） | **一字未改** | 它**明确是「已记账的偏差」pin**（书名即 disagree-and-booked）⇒ 偏差一旦真被修掉，这条**本来就该退役**，不该被改写成新值 |
| `V103`/`V105`/`V106`/`V107`/`V108`/`V109` | **全绿** | **一字未改** | 600 档逐位不受影响，无需重述 |
| `SessionPaneChromeFloorV79Test` / `SessionPaneImeBandsV81Test` / `SessionPaneImeBandV84Test` | **全绿** | **一字未改** | 它们读 `CHROME_FULL_HEIGHT` 本身（未动） |
| `ComposerCallFingerprintV116Test` / `ComposerTripwireIntegrityV115Test` | 未受本轮改动触及 | **一字未改** | — |

⇒ **净结果：0 条 pin 被放宽，0 条被改写，0 条被删除。**

---

## 6. 测试证据

**环境**：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`
**锁**：`Test-Path .agent-gradle-lock` = 不存在 → 写 `v123-pane-entity-fix`
→ **前台**跑完全部运行 → **绝对路径字面量**删除。**未** assembleDebug、**未** clean、**未**装机。

### 6.1 回退后的回归（13 个受影响类，**这是最终状态**）

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.ComposerDivergenceLocateV119Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerDivergenceLocate2V120Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerCriterionParityV118Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerPaneBottomCriterionV108Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerPositionDecompositionV107Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTouchTargetV105Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTallEnoughV106Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerOverflowV103Test" `
  --tests "com.xiufeigo.opencodeshell.SessionPaneImeBeliefV77Test" `
  --tests "com.xiufeigo.opencodeshell.SessionPaneChromeFloorV79Test" `
  --tests "com.xiufeigo.opencodeshell.SessionPaneImeBandsV81Test" `
  --tests "com.xiufeigo.opencodeshell.SessionPaneImeBandV84Test"
```

**原文**：

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 8s
52 actionable tasks: 5 executed, 47 up-to-date
```

### 6.2 用例数（`app/build/test-results/testDebugUnitTest/*.xml` 实测）

| 类 | 用例数 | failures | errors |
|---|---|---|---|
| `SessionPaneImeBeliefV77Test` | **11** | 0 | 0 |
| `SessionPaneImeBandV84Test` | 11 | 0 | 0 |
| `ComposerUnionBottomP5V109Test` | 11 | 0 | 0 |
| `ComposerOverflowV103Test` | 8 | 0 | 0 |
| `ComposerPaneBottomCriterionV108Test` | 8 | 0 | 0 |
| `ComposerCriterionParityV118Test` | 8 | 0 | 0 |
| `ComposerDivergenceLocateV119Test` | 7 | 0 | 0 |
| `ComposerBandTallEnoughV106Test` | 7 | 0 | 0 |
| `ComposerBandTouchTargetV105Test` | 6 | 0 | 0 |
| `SessionPaneChromeFloorV79Test` | 6 | 0 | 0 |
| `ComposerDivergenceLocate2V120Test` | 6 | 0 | 0 |
| `ComposerPositionDecompositionV107Test` | 4 | 0 | 0 |
| `SessionPaneImeBandsV81Test` | 4 | 0 | 0 |
| **合计** | **97** | **0** | **0** |

⇒ **0 失败、0 错误；用例数与试改前一致（无减少）**。

### 6.3 试改那一跑（**如实记账，保留反面证据**）

`97 tests completed, 13 failed` / `BUILD FAILED in 14s` —— 逐条 AssertionError 原文见 **§2.4**。
**生产代码、pin 断言当时一个字都没改**（只改了 `:1467-1468`、`:1539` 与 `SessionScreen` 的传参）。

---

## 7. 真机复验步骤（**本轮零生产改动，故 5554 上仍是旧 build**）

> 诚实前提：**v123 没有改任何生产代码，也没有 assembleDebug、没有装机。**
> 所以下面这些步骤**现在跑等于复验第 3 次终验的旧 build**；它们的用途是
> ① 复核 §1 的 −8 判定（**这是本轮唯一能立刻兑现的真机证据**），② 作为下一次落地的验收清单。

### 7.1 复核 −8 判定（**建议本轮就做，只需 `dumpsys`，不需装机**）

在 5554（`1366x768` / `160`）会话页 + IME 弹起、`mInputShown=true` 的**同一帧**：

```powershell
adb -s emulator-5554 shell dumpsys window | Select-String -Pattern 'type=ime|type=navigationBars|nonDecorFrame|mDisplayFrame'
```

**判据**（照 §1.2）：

| 读到的行 | 判据 | 命中即 |
|---|---|---|
| `type=ime frame=[0,342][1366,768]` | ⇒ `imeBottomPx = 426` | ② 判否成立（不是 474） |
| `type=navigationBars frame=[0,720][1366,768]` | ⇒ nav bar = **48 ≠ 8** | ① 判否成立（−8 候选否掉） |
| `nonDecorFrame=[0,0][1366,720]` | ⇒ 与「盒量到窗口底（606）」并读 | ⑤ `titlebarBottom = false` |
| dump 里 pane 容器 `[0,162][1366,350]` | `top = 162` | ③ 判否成立 |

⇒ 四条全中即 **−8px 闭环判否**（残差 0），无需任何装机。

### 7.2 两档主判据（下一次落地后的验收）

```
600 档 (1366x600) : mInputShown=true ; 判据① unionBottom(249) ≤ paneBottom(261) ⇒ −12 ✅
                    ① 生产口径 213/213 ⇒ 差 0 ✅ ; readable = 51 ✅ ; 判据② unionPx(48) ≤ band(48) ✅
768 档 (1366x768) : 判据① unionBottom(340) ≤ paneBottom(350) ⇒ −10 ✅
                    readable = 92 ✅ ; 时间线盒 = 140 ✅
                    ⭐ **新增**：生产口径 paneBottom 应落在 **342**（= 实测 ime 顶边），
                       而不是现在的 398 —— 这是「A 真的修好了」的判据（越界 0）
```

### 7.3 缩窗往返

600 → 768 → 600 → 768 两轮，按 `$appPid` 过滤 logcat 扫 `FATAL|IllegalArgumentException|maxHeight`，
要求 `pidof` **全程不变**（未崩未重启）且扫描**零命中**。

---

## 8. 未闭环（如实记账）

1. **A 未落地**（主项）。`PANE_ESSENTIAL_HEIGHT` 承担了**两个互斥语义**：
   v77 要它**含 chrome** 当塌陷阈值；768 说它**不该含 chrome**（二次计入）。
   本轮证明了**前者是对的**（§2.2/§2.3 的逐位算式 + 真回归），
   ⇒ **144 必须换个地方修，不能动这个常数。**
2. **A 的成因分解需按实测输入重做**：v119 的 `84 + 60 = 144` 在 `floorPx = 48` 下**不闭合**
   （实测多算是 **96**）。本轮**不编造**新分解。
3. **B（`Composer.kt:2150/2152` 的 2px 居中探出）未修**：被 6 条既有 pin 逐字锁住，
   且它才是 768 判据① 生产口径 **+2** 的直接来源（§1.4）。**仍在授权外。**
4. **768 判据② 口径未定义**（第 3 次终验 §3.4）：`band` 取 `BAND_HEIGHT = 48` 还是
   「该档总带高 96」，**仍待 Lead 裁定**，本轮不动。
5. **`Queue` chip 几何仍未测到**（第 3 次终验 §3.5，5 次 dump 取证）⇒ **队列几何 pin 未动、未放宽**。
6. **本轮未装机、未跑真机**（授权禁止）⇒ §7.1 建议的 `dumpsys` 复核**尚未执行**，
   §1 的判定当前**基于 Lead 提供的 insets 原文**（已逐条落到算式，未改一字）。
