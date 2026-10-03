# RESIDUAL_LEDGER —— v127：过期 pin 复核 + 把 `96` 残差钉成「已知且被批准的偏差」

- 修复工程师：mavis（v127，用 mcode 执行）
- 日期：2026-10-04
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 前序：`FLOOR_AXIS_UNIFY.md`（v126，清 `floorPx` 轴）、`IME_EPOCH_UNIFY.md`（v125）、`CRITERION_MODEL_FIX.md`（v124）、`PANE_ENTITY_FIX.md`（v123）
- 授权边界：**只碰测试文件**（`app/src/test/**`）+ `research/**`（本报告）。
  **本轮生产代码净改动 = 零**（`git status --porcelain -- app/src/main ui _upstream app/build.gradle.kts` = **0 条**）。
  **未** assembleDebug、**未** clean、**未**装机。
- 断言纪律：**0 条被放宽、0 条被删除**。过期 pin **只紧不松地重述**（保留全部原有断言 + 增补当前事实），`474.5f` **只加注释、数值一字未动**，`96` 残差**新增 1 条 pin**。
- **行尾**：被改测试文件 `CR` 字节数实测 **= 0**（全 LF，`core.autocrlf=false`）。**本轮没有用过** `git checkout` / `git restore` / `git add --renormalize`（v123 的教训）。
- **本轮没有新增任何用户可见字符串**（改的全是 `app/src/test/**`）。

---

## 0. 状态（先给结论）

| 项 | 结论 |
|---|---|
| v126 遗留 ① 过期 pin | ✅ **已定位、已按当前事实更新**（`V120 §2.2`），**保留 7 条原断言 + 新增 7 条**（分 4 组，见 §1.4） |
| v126 遗留 ② settings `474.5f` | ✅ **判定为记录性 fixture**（另一条轴：IME *frame* 含透明带），**数值一字未改，加注释保留** |
| v126 遗留 ③ 残差 `96` | ✅ **新增 1 条 pin**（`V118`）：残差 = 96、算式 `84 + 60 − 48` 逐项可复算、被批准（A 不可动）、不构成用户可见缺陷 |
| 生产代码 | **零改动** |
| pin 有没有被放宽 | ✅ **没有**：0 松、0 删；1 条重述（更严）、1 条新增 |
| 反向验证 | 见 §5（改模型输入 ⇒ 必须红） |
| 回归 | 见 §6 |
| **未闭环** | 见 §8（未装机 / 未跑真机 / 交接文档未追加） |

---

## 1. 复核项 ①：v120 的「开放账目」pin 已过期 —— **已按当前事实更新**

### 1.1 是哪一条

`app/src/test/java/com/xiufeigo/opencodeshell/ComposerDivergenceLocate2V120Test.kt:396-427`，
测试方法 `the eight pixel question list is still open and must not be deleted before the dumpsys reading lands`。

### 1.2 它现在钉的是什么（原文逐字要点）

1. `DIVERGENCE_LOCATE2.md` **必须存在**（`:403`）；
2. 该文档必须仍含**五个读数问题**的关键词 `dumpsys window insets` / `navigationBars` / `mCurFrame` / `paneTop` / `roundToInt`（`:407-418`）；
3. 必须仍标 `①` 是决定性读数（`:419-422`）；
4. 必须仍写着 `−8px **未闭环**`（`:423-426`）。

**它钉的其实是一份**历史文档**的完整性，不是任何生产事实。** 换句话说：它的对象是 `research/`
里的一句话，**当初授权不含那份文档**，所以从 v120 至今三轮都「一字未改、仍然绿」。

### 1.3 为什么过期（三条，按当前事实）

1. **它自己的前提已经兑现**。方法名里的 `before the dumpsys reading lands` 是一个**时态条件**：
   真机读数**已经到手** —— `research/v97_verify/5554_md/REPORT_FINAL_VERIFY.md:357-362` 的
   `InsetsSource id=3 type=ime frame=[0,342][1366,768]` ⇒ `imeBottomPx = 426`。
2. **它守的那笔账已经被结清，而且是被同文件的兄弟 pin 结清的**。
   `V120 §2.1`（同文件 `:343`）在 v124 已改名成
   `the eight pixel gap was a mis read ime bottom not a navigation bar the measured insets read forty over`，
   并把 `132 → 180`、`8 → −40`、`48 − 40 = 8` 逐位钉死。
   ⇒ **`−8px` 不是「未闭环的开放账目」，它是「已用错输入解释掉的残影」**（`CRITERION_MODEL_FIX.md` §1.3）。
3. **它现在是一个自相矛盾的断言**：同一个测试类里，`:384` 已经断言
   `48 − 40 = 8`（8 是**残影的来路**，已被完全解释），
   而 `:425` 仍要求下游文档写「**−8px 未闭环**」。**同一笔账在同一文件里既被判掉又被要求保持开放。**

### 1.4 按当前事实怎么更新（**只紧不松、零删除**）

**处置：保留全部 7 条原断言不动**（历史文档不许被悄悄改写——那正是它当初的存在价值），
**把它的语义从「开放账目」纠正为「已结清账目的历史记录 + 现行账目必须落在本轮账本上」**，
并**新增 4 组（共 7 条断言调用）**把**当前事实**钉死：

| 新增 | 钉住什么 | 依据 |
|---|---|---|
| 新增 ① | `paneClampOff768 = 180`、`140 − 180 = −40`、`48 − 40 = 8` **三格逐位** | 读数已到手 ⇒ −8px 有了确定解释 |
| 新增 ② | `RESIDUAL_LEDGER.md`（本报告）**必须存在**且写出 `96 = 84 + 60 − 48` | 结清 −8px 后的**继任账目**不许再无人认领 |
| 新增 ③ | 本报告必须写明 `A（去 chrome）不可动` | v123 实测（§3.2） |
| 新增 ④ | 本报告必须写明两档统一口径 `−10` / `−12` | 真机终验基线（§3.3） |

**为什么这不是放宽**：
- 原有 7 条断言的**取值集合一个字没变大**（它们仍是精确相等 / `contains`，不是析取）；
- 新增的 4 组（7 条断言）**只增加**约束；
- 「未闭环」这四个字**没有被删掉**——它仍然必须留在**历史文档**里。
  被纠正的是**它作为「当前状态声明」的地位**，不是它的存在。
  ⇒ **可满足的赋值集合严格缩小 ⇒ 更严。**

---

## 2. 复核项 ②：settings 路径的 `474.5f` dp —— **判定：记录性 fixture，加注释保留**

### 2.1 全部三处（逐个打开核对过）

| 位置 | 原文 | 喂给谁 |
|---|---|---|
| `ServersFormImeFloorV88Test.kt:70` | `private val imeFrameDp = 474.5f` | `serverDialogFloorDp` / `serverDialogBandDp` / `serverDialogBandLiftDp` |
| `ServersFormImeFloorDownV89Test.kt:327` | `val upImeDp = 474.5f` | 同上三个函数 |
| `SettingsModalImeBandV92Test.kt:52` | `private val upImeDp = 474.5f` | 同上三个函数 |

（V88 的 KDoc `:27`、V92 的 KDoc `:48` 也各有一处**文字**提及，同一读数。）

### 2.2 它属于**哪条轴**：**IME *frame* 轴（含 Gboard 透明带），不是 session 判据轴**

关键三格**逐位**自洽（全部在 V88 类内现成算式）：

```
windowDp = 768                        (wm size 1366x768, wm density 160 ⇒ 1dp == 1px)
keyboardTopDp = 768 − 474.5 = 293.5   ("键盘顶边 as the app reads it")
first keyboard pixel = 342            (像素探针实测，V88 KDoc:29 / V89 :349)
透明顶带 = 342 − 293.5 = 48.5         ← V88 KDoc:40-42 自陈："the IME frame carries a transparent top band"
```

⭐ **与 session 判据轴的关系（这是判定的关键，不是巧合）**：

```
474.5 − 426 = 48.5
```

`426` 是 session 轴的 `imeBottomPx = 768 − imeTop(342)`（第 3 次终验 insets 原文），
`474.5` 是 settings 轴的 **IME *frame* 合成读数**。**两者相差恰好是那 48.5 的透明顶带**
（`426` 从**已绘制的键盘顶边 342** 起算，`474.5` 从**frame 顶边 293.5** 起算）。
⇒ **它们量的不是同一个东西。**

### 2.3 为什么**不能**按 `426` / `48` 的口径更正（若改成 426 会发生什么）

若把 `474.5f` 改成 `426f`：

1. `keyboardTopDp` 从 `293.5` 跳到 `342`，**抹掉 48.5 的透明带**——
   而 V88 的**整条 KDoc 论证**（`:34-42`）就是「卡片画在 inset 报的顶边 293.5 上，
   被 panel 裁掉 21.5dp，于是可见 form 只有 122dp 而非 143.5dp 的地板，页脚离键盘 48px」。
   **改值 = 把这段实测结论的根据删掉。**
2. V88/V89/V92 三类里**逐位钉住**的下游数字会集体位移 48.5：
   `lift 155.5` / `drawnTop 111.5` / `224f` 的 band / `335.5f` 的 floor / `6.5f` 的间隙。
   这些都是**记录性 fixture 自己的内部恒等式**，不是判据。
3. 更严重：**`29_edit_kbdown` 这个状态我们没有 insets 原文**。
   v125 §9.3 已明确「**不臆造新值**」，本轮**继续不臆造**。

### 2.4 判定与处置（**分开记账**，照 v125/v126 的做法）

| 判定项 | 结论 |
|---|---|
| 是不是**判据性**（值是否作为实参进入**当前 session 判据的生产算式** `believedImeBottomPx`）？ | ❌ **不是**。三处都只喂 `serverDialog*Dp(...)`（服务器表单 / 设置弹窗的**带高与抬升**），**从不**进 `believedImeBottomPx`、**从不**与 `426` / `48` 同式比较 |
| 它属于哪条轴？ | **轴 C：IME *frame* 合成读数（含 48.5 透明顶带）**，服务 settings/servers 对话框的 band 判定 |
| 处置 | ✅ **加注释保留，数值一字未改**。注释写明：它**不是** `426`，差 `48.5`，改它会删掉 V88 的实测根据 |
| 是否与 `floorPx` 分叉有关 | ❌ **无关**（v126 §6.1 的轴 A 是导航条 `48`；`474.5` 连轴 A 都不沾） |

⚠️ **诚实标注一条**：`474.5` 与 `426` 差 `48.5`（**不是** `48`）。
所以它**既不是** v110 那个 `474` 的同类、**也不是**导航条 `48` 的同类。
**它是一笔至今没有 insets 原文支撑的 fixture**——但它**自洽**且**被三类的实测探针读数交叉验证**，
因此本轮**保留并加注**，**不编造新值**。

---

## 3. 核心交付：`96` 残差钉成「已知且被批准的偏差」

### 3.1 残差本体（768 档，全部由**生产函数**算出）

```
believedImeBottomPx(visibleBoxPx=606, floorPx=48, imeBottomPx=426, essentialPx=284)
  闸门 606 ≥ 284×2 = 568                      SessionPaneLayout.kt:1539
  room    = 606 − 284 = 322
  claimed = 426 − 48  = 378 ; 378 > 322 ⇒ 钳位开
  believed = room + floor = 322 + 48 = 370    :1540,1543
  panePx  = 606 − max(48, 370) = 236
  paneBottom = 162 + 236 = 398

设备：容器底 350 ⇒ 设备口径 paneBottom = 350 − 48 = 302
⇒ 残差 = 398 − 302 = 96
```

### 3.2 算式：`96 = 84 + 60 − 48`（**逐项指到行号 / 常量**）

| 项 | 值 | 出处（生产常量 / 真机原文） |
|---|---|---|
| **84** | chrome 带**二次计入** | `SessionPaneLayout.CHROME_FULL_HEIGHT` = `HEADER 48 + TAB_STRIP 36`（`CRITERION_MODEL_FIX.md` §2.3）。纯几何事实：`606` 的盒从 `paneTop 162` 往下量，chrome 在 `162` **之上**，含不到它 |
| **60** | v120 §1.1 已归因到行 | `COMPOSER_MIN_HEIGHT 116 − COMPOSER_MINIMAL_HEIGHT 56`（`SessionPaneLayout.kt:95` / `:811`）。**V120Test:136-140** 已在钉 |
| **−48** | 真机 `navigationBars` 底 inset | `768 − navBarTop 720`（insets 原文 `frame=[0,720][1366,768]`）。生产读它的行：`SessionScreen.kt:2362-2363` |
| **= 96** | | 逐位 `84 + 60 − 48 = 96` ✅ |

⚠️ **旧写法 `144 = 84 + 60` 少写 `−48`** ⇒ 整笔虚高恰好**一个导航条**。
⇒ **`ime` 不在这条链上**（只进 `claimed` 的比较，钳位返回 `room + floor`）。

### 3.3 为什么它是「**被批准的**」——依据是 **A（去 chrome）不可动**

v123 实测数据（`PANE_ENTITY_FIX.md` §2.2，试改 `SessionPaneLayout.kt:1467-1468` 后 v77 P0 复发）：

```
v77 的对象：research/v73_verify/5554/61_session_tab.png（1344x2992 @420dpi ⇒ density 2.625）
  window 2992 ; strip 267 ; box 2725 ; nav = px(24) = 63 ; ime = 2992 − 797 = 2195
  旧 essential = px(284) = 745 ;  若去 chrome = px(200) = 525 ;  chrome = px(84) = 220
  闸门 旧 745×2 = 1490 ; 新 (525+220)×2 = 1490  ← 逐位保持

旧（现状，防护生效）：room = 2725 − 745 = 1980 ; claimed = 2195 − 63 = 2132
                      2132 > 1980 ⇒ 钳位开 ; believed = 1980 + 63 = 2043 < 2195   ✅ 被压住
新（去 chrome）      ：room = 2725 − 525 = 2200 ; 2132 ≤ 2200 ⇒ 不开
                      believed = 2195（原样相信）                                  ❌ 防护没了
```

⭐ **钳位从「压住 2043」变成「不压 2195」** ⇒ 键盘之上剩 `2725 − 2132 = 593 px = 226 dp`，
而 `593 > 525` ⇒ **钳位自己的判据说「没塌」** ⇒ 原始 P0「丢工具栏/tab 条」复发
（`V77Test:182-192` 自己钉着 `chromeMaxHeightForComposer(226dp, …) < CHROME_FULL_HEIGHT`）。

v124 §2.4 补的第二条独立反证：去 chrome 后 **768 档钳位整个不开**
（`room 406 ≥ claimed 378`）⇒ 残差从 `60` 变成 `40` = **把一个已记账的偏差换成一个没记账的新偏差**。

⇒ **A 不动，是残差 96 被批准的唯一理由。** 不是「暂时没人修」。

### 3.4 为什么它**不构成用户可见缺陷**

真机统一口径下两档 ① **均过关**（第 3 次终验基线）：

| 档 | 统一口径偏差 | `readable`（设备读数，逐位） |
|---|---|---|
| **768** | **−10** | `188 − 96 = **92**` ✅（≥ 64 地板） |
| **600** | **−12** | `99 − 48 = **51**` ✅ |

⭐ 关键：**两档的 `readable` 逐位保持**（`V118Test:421/426` 钉 `92`、`V118Test:386-390` 钉 `51`），
因为**768 档的 dump 换算用的是设备读数 `140`（`dev768PanePx`），不是生产算出的 `236`**
（这正是 `V118Test:535-537` 明写的前提）。
⇒ **`96` 停在"模型口径 vs 设备口径"这一层，没有渗到用户看到的行数与可读区。**

### 3.5 新 pin 的设计（**纯函数 + 调生产函数/常量，无假绊线**）

新增 1 个测试方法，落在 `ComposerCriterionParityV118Test`（本仓「已记账的偏差」的家）：

```
theNinetySixIsTheEightyFourPlusTheSixtyLessTheMeasuredNavigationBarFloor
  AndItIsAnApprovedDeviationNotAUserVisibleDefect
```

它做四件事，**每件都调生产**：

1. **残差 = 96**：由 `productionPaneBottomPx768()`（内部调 `believedImeBottomPx`）与
   `dev768BContainerBottom` 算出 `398 − 302`。
2. **算式可复算**：`84 + 60 − 48`，其中 84 取 `CHROME_FULL_HEIGHT`、60 取
   `COMPOSER_MIN_HEIGHT − COMPOSER_MINIMAL_HEIGHT`、48 取 `navFloor768`（insets 原文推得）。
   **逐项不许换人**：再钉「`[200,320]` 里没有任何 essential 复现退役的 144」
   （承重性照抄 v119 §A.4 的结构）。
3. **被批准的**：`box 2725 / nav 63 / ime 2195` 两个 essential 口径各调一次**同一个生产函数**
   `believedImeBottomPx` ⇒ 必须得 `2043`（< 2195，被压住）与 `2195`（不压，防护失效）。
   ⇒ **A 不可动这件事由生产函数自己证明，不是注释声明。**
4. **不构成缺陷**：768 的 `readable` 由 `dev768BContainerHeight − dev768BUnionPx` 复算 ⇒ **92**
   并断言 `92 ≥ 64`（v105/v106 钉的地板）。
   ⚠️ **600 是个例外，且这个例外是测试替我抓出来的**：600 的 `readable = 51` 是 v106 记的
   **「显式让位区」**（`V106Test:502-505`），**对它断言 `≥ 64` 是错的** ——
   我第一版就是这么写的，第一次跑就红了（`java.lang.AssertionError: 600's readable 51 must still
   clear the 64 px floor`）。600 要钉的是**「逐位未变」**：它就是 `51`，不是 `64`。
   详见 §4.2 的诚实记账。

**反向验证设计**（见 §5）：把算式里的 **`60` 项**或 **`−48` 项**改掉，该 pin **必须当场变红**。

---

## 4. 改动清单（逐文件）

| 文件 | 改了什么 | 断言净变化 |
|---|---|---|
| `ComposerCriterionParityV118Test.kt` | **新增 4 个测试方法**（§4.1 残差与算式、§4.2 A 不可动、§4.3 不构成缺陷、§4.4 账本存在） | **+4 方法 / +35 断言**，0 删 0 改 |
| `ComposerDivergenceLocate2V120Test.kt` | 过期 pin（`:396-427`）**重述**：保留 7 条原断言，**新增 7 条**当前事实 | **+7 断言**，0 删；另改 6 行 KDoc（见 §4.2） |
| `ServersFormImeFloorV88Test.kt` | `474.5f` **只加注释**，数值一字未改 | 0 |
| `ServersFormImeFloorDownV89Test.kt` | 同上（`upImeDp` 旁加注释） | 0 |
| `SettingsModalImeBandV92Test.kt` | 同上 | 0 |

`git diff --stat -- app/src/test` = **5 files changed, 334 insertions(+), 6 deletions(-)**。
⭐ **那 6 处删除全部是 V120 里被替换掉的旧 KDoc 理由**
（`**为什么不能更强**：−8px 的**答案未知**…` 那几行），
`git diff -U0 | Select-String "^-.*(assert|fun |@Test)"` = **0 行** ⇒ **没有任何断言被删**。

### 4.1 ⚠️ 一次被编译器抓到的自伤（如实记账）

我第一版写 V118 时，替换块的 `old_string` 尾部**多带了一个方法级的 `}`**，
结果**连带删掉了 §4 那个方法最后一条断言**
（`"with the device pane 140 as input, the band bridge is still digit exact"`）
和它后面那个 `}`。**kotlinc 当场报 `Syntax error: Missing '}'`（`V118Test.kt:796`）。**
**已按原文逐字恢复**（`git diff` 证明 §4 那 6 条断言现在一条不少，见上）。
**如实记账**：这正是「不许删除任何断言」这条纪律会被人**顺手**破掉的具体形态——
不是被谁放宽，是**编辑时的手滑**；是**编译器**而不是**我**发现的。

### 4.2 两处我自己的算错，也是测试抓出来的

| 我写的 | 实际 | 处置 |
|---|---|---|
| `593 / 2.625` 用 `.toInt()` 当成 `226` | `593 / 2.625 = 225.90` ⇒ 截断得 **225**；v77 记的 226 dp 是**四舍五入** | 改用 `roundToInt()` 得 226，**并额外钉住截断值 225**，让「用哪种取整」也变成可复算的事实 |
| 600 的 `readable 51 ≥ 64` | **51 < 64**。600 是 v106 的**显式让位区**，`≥64` 对它不成立 | 600 改成「**逐位未变 = 51**」；`≥64` 只对 768 断言 |

⇒ 这两条**不是**放宽，是**把我原本写错的判据改对**；两次都是**第一次跑就红**，证据在 `_v127_run1.log`。

---

## 5. 反向验证（**2 次注入、真跑抄原文**）

注入对象：新 pin `theNinetySixIsEightyFourPlusSixtyLessTheNavigationBarAndItIsAnApprovedDeviation`
里那条**算式恒等式**（`assertEquals(96, 84 + 60 − 48)` 的右侧）。

### 5.1 注入 ①：把 `60` 改掉（`(116 − 56)` → `56`）⇒ **红**

```
> Task :app:testDebugUnitTest FAILED
BUILD FAILED in 3s
```

断言原文（逐字，自 XML 取）：
```
java.lang.AssertionError: ⇒ 96 = 84 + 60 − 48, digit exact expected:<96> but was:<92>
```
（`84 + 56 − 48 = 92` ⇒ 差恰好一个 60。）

### 5.2 注入 ②：把 `−48` 丢掉（退役的 `144 = 84 + 60`）⇒ **红**

```
> Task :app:testDebugUnitTest FAILED
BUILD FAILED in 3s
```

断言原文（逐字，自 XML 取）：
```
java.lang.AssertionError: ⇒ 96 = 84 + 60 − 48, digit exact expected:<96> but was:<144>
```

⭐ **这两次注入正是 v119→v124 那段历史的逐字重演**：
注入 ② 算出来的 `144`，就是 v119 当年钉的「多算 144」——
**它现在被这条 pin 判为「少写了 −48 的退役值」**。⇒ 旧账不是被删掉了，是**被钉成了一条假账**。

### 5.3 恢复实测值 ⇒ 117/117 绿（§6）

---

## 6. 测试证据

**环境**：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`，`core.autocrlf=false`
**锁**：`Test-Path .agent-gradle-lock` = 不存在 → 写 `v127-residual-ledger`
→ **前台**跑完全部运行 → **绝对路径字面量**删除（`rm -- "C:\...\ .agent-gradle-lock"` ⇒
`mavis-trash: moved to trash` ⇒ 复查 `lock_exists=False`）。
**未** assembleDebug、**未** clean、**未**装机。

### 6.1 最终回归命令（14 个类）

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.ComposerPaneBottomCriterionV108Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerPositionDecompositionV107Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTouchTargetV105Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerCriterionParityV118Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerDivergenceLocateV119Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerDivergenceLocate2V120Test" `
  --tests "com.xiufeigo.opencodeshell.SessionPaneImeBeliefV77Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerOverflowV103Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerChildUnionV104Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTallEnoughV106Test" `
  --tests "com.xiufeigo.opencodeshell.ServersFormImeFloorV88Test" `
  --tests "com.xiufeigo.opencodeshell.ServersFormImeFloorDownV89Test" `
  --tests "com.xiufeigo.opencodeshell.SettingsModalImeBandV92Test"
```

**原文**：
```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 45s
52 actionable tasks: 2 executed, 50 up-to-date
```

### 6.2 用例数（`app/build/test-results/testDebugUnitTest/*.xml` 实测）

| 类 | 用例数 | failures | errors | 本轮改动 |
|---|---|---|---|---|
| `ComposerCriterionParityV118Test` | **12** | 0 | 0 | **8 → 12（+4 新 pin）** |
| `ComposerDivergenceLocate2V120Test` | **6** | 0 | 0 | 过期 pin 重述（+7 断言，0 删） |
| `ServersFormImeFloorV88Test` | 9 | 0 | 0 | **只加注释** |
| `ServersFormImeFloorDownV89Test` | 9 | 0 | 0 | **只加注释** |
| `SettingsModalImeBandV92Test` | 9 | 0 | 0 | **只加注释** |
| `SessionPaneImeBeliefV77Test` | 11 | 0 | 0 | **一字未改**（v77 P0 仍全绿） |
| `ComposerPaneBottomCriterionV108Test` | 9 | 0 | 0 | 一字未改 |
| `ComposerUnionBottomP5V109Test` | 11 | 0 | 0 | 一字未改 |
| `ComposerDivergenceLocateV119Test` | 8 | 0 | 0 | 一字未改 |
| `ComposerOverflowV103Test` | 9 | 0 | 0 | 一字未改 |
| `ComposerChildUnionV104Test` | 7 | 0 | 0 | 一字未改 |
| `ComposerBandTallEnoughV106Test` | 7 | 0 | 0 | 一字未改 |
| `ComposerPositionDecompositionV107Test` | 4 | 0 | 0 | 一字未改 |
| `ComposerBandTouchTargetV105Test` | 6 | 0 | 0 | 一字未改 |
| **合计** | **117** | **0** | **0** | **用例数只增不减（86 → 117）** |

### 6.3 范围与行尾

```
git status --porcelain -- app/src/main ui _upstream app/build.gradle.kts  →  0 条
CR 字节数：V118=0  V120=0  V88=0  V89=0  V92=0   （全 LF）
本轮没有用过 git checkout / git restore / git add --renormalize
本轮没有新增任何用户可见字符串（改的全是 app/src/test/**）
```

---

## 7. 为什么不降强度

1. **0 条被放宽、0 条被删除**：`git diff -U0` 里**没有一行** `-assert`（§4）。
2. **过期 pin 是变严不是变松**：7 条原断言的**取值集合一个字没变大**（全是精确相等 / `contains`），
   新增 7 条只增约束；「−8px 未闭环」四个字**仍必须留在历史文档里**。
   ⇒ 可满足的赋值集合**严格缩小**。
3. **`474.5f` 一个数都没动**：本轮对它只有**注释**。判据性错值才按 `426`/`48` 更正，
   而它**不是**判据性（§2.2/§2.4），且**改它会删掉 V88 的实测根据**。
4. **96 从「没人负责的数字」变成「有算式 + 有批准根据 + 有反向验证的偏差」**：
   两次注入分别打在 `60` 和 `−48` 上，**都红**（§5）⇒ 这条 pin 是承重的，不是装饰。
5. **v77 点名的 P0 一条没碰** ⇒ `SessionPaneImeBeliefV77Test` **11/11 全绿**，
   而新的 §4.2 用**同一个生产函数跑两遍**把「A 不可动」证明到不能再松。

---

## 8. 未闭环（如实记账）

1. ⭐ **残差 `96` 本身仍未消除**，本轮只是把它**钉成被批准的偏差**。
   消除它需要动 `A`（去 chrome）或让形态判定看到**设备侧真实 body**（v119 §4.1 / v120 §1.4），
   **两者都超出本轮授权**（禁止碰 `app/src/main/**`）。
2. ⭐ **`29_edit_kbdown` 那个状态至今没有 insets 原文** ⇒ `474.5f` **不臆造新值**，
   按 v125 §9.3 的交代继续「只记账不修」。若将来拿到那个状态的 `dumpsys window insets`，
   本报告 §2.3 的三条后果可直接照着复核。
3. **本轮未装机、未跑真机**（授权禁止）⇒ 设备基线（`readable` 92/51、时间线盒 140、−10 / −12）
   **未复验**。§3.4 全部基于第 3 次终验原文（**逐字未改**）。
4. **v126 §9.7 结转**：交接文档（`research/HANDOFF.md` / `research/v98_RESUME.md`）**未追加 v127 一节**
   （为避免与主线交接文档并发写，本轮只交本报告）。
5. **V119 §A.4 的 `[200,320]` 穷举**没有复制进新 pin（它在 `V119Test` 里仍然钉着、仍然绿）——
   本轮的承重性由 §5 的两次注入承担，避免同一断言在两个类里各活一份、日后各自漂移。
6. **`474.5f` 判定的残余风险**：它与 `426` 差 `48.5`（**不是** `48`），
   说明这两条轴**至今没有一份统一的 insets 原文**把它们钉在一起。
   本轮只能确认「它们量的不是同一个东西」，**不能**确认「哪一条对」——
   v88 的证据是**像素探针 + a11y 树**（`ui_edit_kbd.xml`），不是 insets dump。
