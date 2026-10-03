# CRITERION_MODEL_FIX —— v124：把模型里的 `imeBottomPx` 修正到**真机口径**，钉住 96

- 修复工程师：mavis（v124，用 mcode 执行）
- 日期：2026-10-04
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 前序：`PANE_ENTITY_FIX.md`（v123，判掉 −8px + 证明 A 不可动）、`research/v97_verify/5554_md/REPORT_FINAL_VERIFY.md`（第 3 次真机终验）
- 授权边界：**只碰测试文件**（3 个 `app/src/test/**`）+ `research/**`（本报告与两份交接文档）。
  **本轮生产代码净改动 = 零**（`git status --porcelain -- app/src/main ui _upstream app/build.gradle.kts` = **0 条**）。
  **未** assembleDebug、**未** clean、**未**装机。
- 断言纪律：**不降、只增或保持**。本轮把两条**前提已被真机读数否掉**的 pin 换成实测口径（详见 §2.5 的逐条说明），
  并**新增 1 条**（§A.2 的 §D）。**没有任何一条被放宽**——放宽的定义是「断言范围变松」，
  而本轮是把「钉在错值上的断言」改成「钉在实测值上的更紧断言」（见 §2.5 末段）。
- **行尾**：三个被改测试文件的 `CR` 字节数实测 **= 0**（全 LF）。
  **本轮没有用过 `git checkout` / `git restore` / `git add --renormalize` 去改任何文件的行尾**（v123 的教训）。
- **本轮没有新增任何用户可见字符串**（改的全是 `app/src/test/**`）。

---

## 0. 状态（先给结论）

| 项 | 结论 |
|---|---|
| **假 −8px** | ✅ **消失**。它从来不是几何缺口，是模型拿了一个**错的 `imeBottomPx`**。反向验证：把 `426` 改回 `474` ⇒ 断言当场变红 |
| **768 多算 144** | ⚠️ **按实测输入不成立，正确值是 96**。误差源是 `floorPx = 0`（**不是** ime），已反向证死 |
| **96 的算式** | **`96 = 84 + 60 − 48`**（chrome 二次计入 + v120 的 60 − 真机 nav bar floor），已钉成断言 |
| **A（去 chrome）** | 🔴 **仍然不可动**。本轮**新增一条独立证据**：代入实测输入后，去 chrome 会让**钳位整个不开**（`room 406 ≥ claimed 378`）⇒ 残差从 60 变 40，等于**把一个已记账的偏差换成一个没记账的新偏差** |
| **生产代码** | **零改动** |
| **pin 有没有被放宽** | ✅ **没有**。新增 1 条；替换 3 条（前提已否）；保持 44 条 |
| **测试** | ✅ **49/49 绿**（6 个类）；反向验证两轮**各红**（详见 §3） |
| **设备基线**（`readable` 92/51、时间线盒 140、−10 / −12） | **未动**（零生产改动、未装机） |

---

## 1. 现状：`imeBottomPx` 原来错在哪（`文件:行号`）

### 1.1 三个文件里各有一份**同源**的错误输入

| 位置（修正前） | 内容 | 来源 |
|---|---|---|
| `ComposerDivergenceLocateV119Test.kt:34` | `private val imeBottom768 = 474` | v110 dump 的 `mFrame=[0,294][1366,768]` ⇒ `768 − 294` |
| `ComposerDivergenceLocate2V120Test.kt:41` | `private val imeBottom768 = 474` | 同上（复制） |
| `ComposerCriterionParityV118Test.kt:70` | `private val imeBottom768 = 474` | 同上（复制） |

**三份注释都写着「键盘自称 474」**——它们把 **v110 那一轮的 IME 顶边 294** 当成了**真机恒定值**。

### 1.2 还有一个**同源**的错误：`floorPx` 被硬写成 `0`

`believedImeBottomPx(visibleBoxPx, floorPx, imeBottomPx, essentialPx)` 的 `floorPx` 参数，
在这三个文件里**全部**被写成 `0`（V119 的 `productionPanePx` 辅助函数 + 4 个调用点、
V120 的 2 个调用点、V118 的 2 个调用点），而生产 `SessionScreen.kt:2362-2363` 读的是
`WindowInsets.navigationBars` ⇒ **48**。

**⇒ 这两个错是耦合的，v119/v120/v118 从来没有分开怀疑过它们。**

### 1.3 为什么「假 −8px」由这个错产生

第 3 次终验 §4 的 insets 原文（**未改写**）：

```
InsetsSource id=3 type=ime frame=[0,342][1366,768] visibleFrame=[0,342][1366,768] visible=true
InsetsSource id=324c0001 type=navigationBars frame=[0,720][1366,768] visible=true
ROTATION_0={nonDecorInsets=[0,0][0,48], configInsets=[0,0][0,48], nonDecorFrame=[0,0][1366,720], configFrame=[0,0][1366,720]}
mDisplayFrame=Rect(0, 0 - 1366, 768)
```

⇒ `imeTop = 342` ⇒ **`imeBottomPx = 768 − 342 = 426`**（不是 474）；
`navBarTop = 720` ⇒ **`floorPx = 768 − 720 = 48`**（不是 0）。

```
474 − 426 = 48 = 一个导航条
```

**那个「说不通的 −8」就是一个 48 的差被错记成的残影**：
v120 §2.2 的算式 `140 − (606 − 474) = 140 − 132 = 8` 里，`132` 是拿退役的 474 算出来的。
换成实测 426：钳位关掉时生产读 `606 − 426 = 180`，对设备 140 是 `−40`，
而 **`48 − 40 = 8`** ⇒ **旧的 8 与新的 40 相差恰好一个导航条，是同一笔账的两面。**

### 1.4 顺带修正：模型还**漏了生产的一步**

生产 `SessionScreen.kt:2370-2371` 是 `maxOf(paneFloorPx, believedImeBottomPx)`；
三个模型都写成裸的 `paneBox − believed`，**不取 maxOf**。
本轮把辅助函数/调用点改成 `maxOf(floorPx, believed)` 逐字镜像生产。
（在 `floorPx = 0` 且 `imeBottomPx` 远大于 floor 时两者等价，所以这不是红的原因，但模型必须同形。）

---

## 2. 修正（按真机 insets 口径）

### 2.1 改了什么（**只改测试侧**）

| 文件 | 改法 |
|---|---|
| `ComposerDivergenceLocateV119Test.kt` | `imeBottomPx 474 → 426`；新增 `navFloor768 = 48` / `navFloor600 = 48`；辅助函数加 `maxOf`；`floorPx = 0 → navFloorPx`；§A.2/§A.3/§A.4 换成实测口径；**新增 §D**（钉住两个模型输入） |
| `ComposerDivergenceLocate2V120Test.kt` | `imeBottomPx 474 → 426`；新增 `navFloor768 = 48`；`paneClampOff768 132 → 180`；§1.2 / §1.4 / §2.1 按实测重述 |
| `ComposerCriterionParityV118Test.kt` | `imeBottomPx 474 → 426`；新增 `navFloor768/navFloor600 = 48`；`floorPx = 0 → navFloorPx` + `maxOf`；记账 pin `284/446` → `236/398` |

### 2.2 假 −8px 消失后的算式（**逐位**）

```
768 档实测口径（wm density 160 ⇒ 1dp == 1px）

  ime 顶边     = 342      insets: type=ime frame=[0,342][1366,768]
  ime 底 inset = 426      = 768 − 342          ← 修正（原 474）
  nav bar 底   = 48       insets: type=navigationBars frame=[0,720][1366,768]   ← 修正（原 0）
  nonDecor 底  = 720      insets: nonDecorFrame=[0,0][1366,720]
  paneTop      = 162      dump: pane 容器 [0,162][1366,350]
  paneBoxPx    = 606      = 768 − 162

  钳位关掉（相信键盘）：panePx = 606 − max(48, 426) = 180   设备 140 ⇒ 140 − 180 = −40
  钳位打开（现状生产） ：room = 606 − 284 = 322 ; claimed = 426 − 48 = 378 ; 378 > 322 ⇒ 开
                        believed = room + floor = 322 + 48 = 370
                        panePx = 606 − 370 = 236 = 284 − 48   ← 构造性恒等
                        paneBottom = 162 + 236 = 398          设备 302 ⇒ 多算 96
```

⇒ **既没有 8，也没有 −8。** 那个 −8 是一行错的 `imeBottomPx` 在算式里的投影。

### 2.3 ⭐ 96 的算式（**已钉成断言**）

```
96 = 84  (chrome 带被二次计入：CHROME_FULL_HEIGHT = HEADER 48 + TAB_STRIP 36)
    + 60  (v120 §1.1 归因到行：堆叠地板 COMPOSER_MIN_HEIGHT 116 − 一行地板 COMPOSER_MINIMAL_HEIGHT 56)
    − 48  (真机 navigationBars 底 inset = 768 − 720)
```

逐位：`84 + 60 − 48 = 96` ✅
等价二项写法：`96 = 84 + 12`，其中 `12 = 72 + 116 + 12 − 140 − 48`（60 被导航条吃掉 48 后的残项）。

**与 v119/v120 对齐关系**：v119 的 `84` **不变**（chrome 二次计入是纯几何事实：606px 的盒从 `paneTop`
往下量，chrome 在 `paneTop` 之上，含不到它）；v120 的 `60` **不变**（`116 − 56` 与 ime/floor 无关）。
**变的只有第三个词 −48**，而它正是 v119 漏掉的那个 nav bar。

**旧写法 `144 = 84 + 60` 少写了 `−48`，所以整笔虚高恰好一个导航条。**

**并且 ime 在这条链上不是误差源**（v123 §3 已推，本轮结构化钉住）：
`imeBottomPx` 只进 `claimed = ime − floor` 与 `room` 的**比较**（`426 > 322` 与 `474 > 322` 同真），
钳位返回的是 `room + floor`，**与 ime 无关** ⇒ **144 → 96 的误差源是 `floorPx = 0`。**
§A.4 用一条 pin 显式钉住「`floorPx = 48` 时没有任何 essential 能复现 144」。

### 2.4 A（去 chrome）：本轮新增一条**独立**的反证

代入实测输入后，去掉 `CHROME_FULL_HEIGHT` ⇒ `essential = 200`：

```
room    = 606 − 200 = 406 ;  claimed = 426 − 48 = 378 ;  378 ≤ 406 ⇒ 钳位整个不开
believed = 426（原样相信键盘）⇒ body = 606 − 426 = 180
残差 = 180 − 140 = 40   （原口径是 60）
```

⇒ **去掉 chrome 不仅破 v77 P0（v123 实测），还会让钳位整个失效**，
把一个**已记账**的偏差（60）换成一个**没记账**的新偏差（40）。**两头都不划算。**
`60` 的成因（`116 − 56`）与 clamp 行为无关 ⇒ **去 chrome 动不了 60 的成因**，这一格逐位不变。

### 2.5 为什么**不是放宽断言**（逐条）

| pin | 处置 | 为什么不叫放宽 |
|---|---|---|
| `…overruns the device by one hundred forty four`（V119 §A.2） | 值 `144 → 96`、paneBottom `446 → 398`，并**新增**两条（`236 = 284 − 48`、`144 − 96 = 48`） | 原值**已被真机 insets 原文否掉**（v123 §3）。新值是**实测口径的真值**，且比原断言**多钉了两个恒等式**。把已被否证的值留着 = 留一个假账 |
| `eighty four of the one forty four…`（V119 §A.3） | 改名 `ninety six is the eighty four plus the sixty less the measured navigation bar floor` | 84 与 60 **一个数都没改**，只补了 `−48` 这一项；并**新增**一条「用生产真项而非字面量 60」的读法 |
| `the one forty four is load bearing…`（V119 §A.4） | `144 → 96`，**新增**一条「`[200,320]` 里没有任何 essential 复现退役的 144」 | 原断言的**承重性完全保留**（仍要求「恰好一个」），且**变严**（多钉一个「复现 144 的个数 = 0」） |
| `the eight pixel gap is the navigation bar floor…`（V120 §2.1） | 改名 `…was a mis read ime bottom not a navigation bar…` | 它的名字与 4 条源码 pin **一字未动**；被换掉的是**被真机读数否掉的 8**。新值 `180 / −40 / 48 − 40 = 8` 把残影的来路钉死 |
| `theSevenSixEightProductionPaneDisagreesWithTheDeviceAndThatIsBooked`（V118 §4） | `284 → 236`、`446 → 398`、消息里的 `144 → 96` | 书名即「**已记账的偏差**」——**记的就是当前生产值**。生产值没变、变的只是**模型喂进去的输入**，记账必须跟着输入走 |
| `the eight pixel question list is still open…`（V120 §2.2） | **一字未改** | 它 pin 的是 `DIVERGENCE_LOCATE2.md` 这份**历史文档**的完整性。**本轮没有改那份文档**（不在授权内），所以该 pin 仍然成立、仍然绿。⚠️ **但它现在是一个「已过期的开放账目」**——真机读数已到手，见 §6 第 1 条 |
| **新增** V119 §D `the model inputs are the measured insets…` | 新增 | 纯增：把两个模型输入的字面量与来源锁死，防止模型漂回旧 epoch |
| V103 / V105 / V106 / V107 / V108 | **一字未改** | 它们 pin 的是 **600 档**读数与 **v106/v107 时代的诊断史**（`466 vs 474` 那个旧模型），不在本轮口径修正范围 |

⇒ **净结果：0 条被放宽，0 条被删除，1 条新增，3 条按实测口径重述。**

---

## 3. 反向验证（**真跑抄原文**）

### 3.1 把 `imeBottomPx` 改回旧值 `474` ⇒ **红**

```
> Task :app:testDebugUnitTest FAILED
ComposerDivergenceLocateV119Test > the model inputs are the measured insets and not the retired v110 readings FAILED
    java.lang.AssertionError at ComposerDivergenceLocateV119Test.kt:474

22 tests completed, 1 failed
BUILD FAILED in 3s
```

断言消息原文（自 XML 取）：

```
java.lang.AssertionError: 768 ime bottom inset = 768 − imeTop(342); the retired 474 implied an imeTop of 294
    expected:<426> but was:<474>
```

⇒ **新的 §D pin 立刻抓住 ime 侧的漂移。**

### 3.2 把 `floorPx` 改回 `0`（换个取侧）⇒ **红 4 条**，且**直接复现 144**

```
> Task :app:testDebugUnitTest FAILED
ComposerDivergenceLocateV119Test > the ninety six is load bearing it breaks for every essential but the production one FAILED
ComposerDivergenceLocateV119Test > the model inputs are the measured insets and not the retired v110 readings FAILED
ComposerDivergenceLocateV119Test > ninety six is the eighty four plus the sixty less the measured navigation bar floor FAILED
ComposerDivergenceLocateV119Test > the seven six eight production pane is pinned to the essential form less the measured floor and overruns the device by ninety six FAILED

22 tests completed, 4 failed
BUILD FAILED in 3s
```

断言消息原文（逐条，自 XML 取）：

```
--- the ninety six is load bearing it breaks for every essential but the production one
    java.lang.AssertionError: only the production essential may reproduce the 96px divergence
        expected:<284> but was:<236>

--- the model inputs are the measured insets and not the retired v110 readings
    java.lang.AssertionError: …and the retired 474 overshot the truth by exactly one navigation bar
        expected:<0> but was:<48>

--- ninety six is the eighty four plus the sixty less the measured navigation bar floor
    java.lang.AssertionError: the divergence is 96 on the measured inputs
        expected:<96> but was:<144>          ← 退回 floor=0 就精确复现 144

--- the seven six eight production pane is pinned to the essential form less the measured floor and overruns the device by ninety six
    java.lang.AssertionError: 768: the nav bar floor is 48, so the pane is 284 − 48 = 236
        expected:<236> but was:<284>
```

⇒ **144 的成因被反向钉死：`floorPx = 0` 一改回去，144 逐位回来。**
⇒ 而 3.1 证明 **ime 改成 474 并不会让 144 回来**（那次只红 1 条、全是 §D）——
**两条反向验证合起来，逐位证明「144 的误差源是 floor、不是 ime」。**

### 3.3 恢复实测值 ⇒ 49/49 绿（见 §4）

---

## 4. 测试证据

**环境**：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`
**锁**：`Test-Path .agent-gradle-lock` = 不存在 → 写 `v124-criterion-model-fix`
→ **前台**跑完全部运行 → **绝对路径字面量**删除。**未** assembleDebug、**未** clean、**未**装机。

### 4.1 命令与原文

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.ComposerDivergenceLocateV119Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerDivergenceLocate2V120Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerCriterionParityV118Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerPaneBottomCriterionV108Test" `
  --tests "com.xiufeigo.opencodeshell.SessionPaneImeBeliefV77Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerOverflowV103Test"
```

**原文**：

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 3s
52 actionable tasks: 2 executed, 50 up-to-date
```

### 4.2 用例数（`app/build/test-results/testDebugUnitTest/*.xml` 实测）

| 类 | 用例数 | failures | errors | 备注 |
|---|---|---|---|---|
| `ComposerDivergenceLocateV119Test` | **8** | 0 | 0 | 原 7 条，**新增 §D 1 条** |
| `ComposerDivergenceLocate2V120Test` | 6 | 0 | 0 | 用例数不变 |
| `ComposerCriterionParityV118Test` | 8 | 0 | 0 | 用例数不变 |
| `ComposerPaneBottomCriterionV108Test` | 8 | 0 | 0 | **一字未改** |
| `SessionPaneImeBeliefV77Test` | 11 | 0 | 0 | **一字未改**（v77 P0 仍全绿） |
| `ComposerOverflowV103Test` | 8 | 0 | 0 | **一字未改** |
| **合计** | **49** | **0** | **0** | |

⇒ **0 失败、0 错误；用例数只增不减（7 → 8），无减少。**

### 4.3 行尾与范围

```
git status --porcelain  →  3 个 app/src/test/**  +  .agent-gradle-lock（已删）
git status --porcelain -- app/src/main ui _upstream app/build.gradle.kts  →  0 条
CR 字节数：V119=0  V120=0  V118=0   （全 LF）
```

---

## 5. 为什么不降强度（本轮一次都没有降）

1. **没有放宽任何断言**：0 条被松、0 条被删、1 条新增、3 条按**实测**重述（§2.5 逐条给了理由）。
2. **没有把失败洗成绿**：v123 拒绝改 `V77 the stale 836 dp frame is capped…`（那才是真 P0 回归）；
   本轮**根本没有碰生产代码**，所以**不存在**需要用改 pin 来掩盖的回归。
3. **A 依然没落地**：任务书里「−8px 消失后是不是就能动 A 了」的隐含期待被本轮**否掉**了——
   实测输入代入后，去 chrome 会让**钳位整个不开**（§2.4），多一个没记账的 40 偏差。
   ⇒ **正确交付仍然是：报告 + 证据 + 修正后的诊断，落地与否交给裁定。**
4. **没有碰 v77 点名的 pin**（`PANE_ESSENTIAL_SPARE_ROOM`、essential 形态两条）⇒ **v77 全绿**。

---

## 6. 未闭环（如实记账）

1. **V120 §2.2 的「开放账目」pin 已过期**（本轮**未改**，见 §2.5）：
   它要求 `DIVERGENCE_LOCATE2.md` 仍写着「−8px 未闭环」。**真机读数已到手，那份文档该被改写**，
   但改它不在本轮授权内（只授权 `research/**` 的**追加**、且不得改语义）⇒ **留给下一轮**。
2. **V103 / V106 / V107 / V108 仍带着 474 这个旧 epoch 读数**（v110 那一轮 `imeTop = 294`）。
   本轮**未改**（它们 pin 的是 600 档与 v106/v107 时代诊断史）。
   ⇒ **仓内现在同时存在「426（新 epoch）」与「474（旧 epoch）」两套 IME 读数**，
   这是**已知的口径分叉**，不是遗漏。**建议下一轮统一**（要么全部迁到 426，要么在 474 旁注明 epoch）。
3. **A 未落地**（主项，v123 结转）：`PANE_ESSENTIAL_HEIGHT` 仍承担两个互斥语义。
   本轮新增证据（§2.4）使「去 chrome」这条路**更不划算**，不是更好。
4. **768 的「多算 96」仍未定位到可修的行**：96 里有 84 是 chrome 二次计入（真几何缺陷），
   60 是形态收账错（自证循环），48 是**模型口径**（本轮已修）——
   **前三者没有一个能在不动 v77 P0 的前提下被单独摘掉。**
5. **B（`Composer.kt:2150/2152` 的 2px 居中探出）未修**：仍被 6 条既有 pin 逐字锁住，
   且它才是 768 判据① 生产口径 **+2** 的直接来源（v123 §1.4）。**仍在授权外。**
6. **本轮未装机、未跑真机**（授权禁止）⇒ 设备基线（`readable` 92/51、时间线盒 140、−10 / −12）
   **未复验**。§2.2 的算式全部基于第 3 次终验的 insets 原文（**已逐字落到算式，未改一字**）。
7. **交接文档**：见 `research/HANDOFF.md` 与 `research/v98_RESUME.md` 末尾追加的 v124 一节。
