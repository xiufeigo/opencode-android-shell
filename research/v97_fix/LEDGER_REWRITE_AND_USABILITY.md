# LEDGER_REWRITE_AND_USABILITY — v142：重写 quarantine 错账 + 补可用性一侧

状态：**已落地 · 主闸门绿（127 例 / 0 失败）· 反向验证已红 · 锁已释放**
日期：2026-10-04 · 承接 v141（`SEMANTIC_INFLATION_RULING.md`）、v138（`UNION_1PX_NOT_EFFECTIVE.md`）、v140（`REPORT_PROBE_AND_ROLES.md`）

> 锁约定沿用 `PIN_DEVICE_HARDLINK.md:6-7`（仓库无独立 `*.lock`，**本报告文件即锁**）。
> 抢锁时刻 = 本文件首次写入（写入前 `Test-Path .agent-gradle-lock` = `False`，`git status -- app/src/main` = 空）；
> 本文件定稿 = 释放锁。`.agent-gradle-lock` 同样按协议：写锁 → **前台**跑完 → 绝对路径字面量删除。
>
> 授权与纪律：只跑 `:app:testDebugUnitTest --tests "<类名>"`；未 `assembleDebug` / `clean` / 全量 / 装机；
> 未杀进程；未新增用户可见字符串；用例数**只增不减**；未用 `git checkout` / `git restore` 改行尾（`autocrlf=false`）。
> **生产代码零改动**（`git status -- app/src/main` = 0 行），v139 的 `Log.i` 探针保留。

---

## 0. 一页结论

| 问题 | 答案 |
|---|---|
| 那笔错账怎么重写的？ | 撤下 v139 的 `PARITY 3/3`（「树 ≠ 帧」），换成**新框架**：**生产求值** `unionBottomPx = max(editorBottom, toolRowBottom)` 为准（探针直读 **48**），**dump 侧按 allowance 准入**，allowance = `(innerLayoutBottom − productionUnionBottom) + expansionDown` = `(249−262)+14` = **1**，**只能向下**、**上界 = expansionDown** |
| 保留诚实红灯了吗？ | ✅ **保留，而且是更诚实的那盏**：红灯从「错因」（跑的不是这份代码）换成「**对因**」（语义 ⊋ 布局，**在设备上仍未闭环**）。类**仍然不编译、仍然红**，4 条用例中 1 红 3 绿 |
| 反向验证变红了吗？ | ✅ **变红**：注入「拆分方向反转」⇒ 由 **1 红 → 4 红**，且其中一条是**指名道姓**的 `v142 TRIGGER (the split changed)`，`expected:<13> but was:<14>`（逐字见 §3） |
| 48dp 靶可达性逐项结论 | **5 项里 3 项不达标**。①高 ≥48：**4/4 形态全过**。②宽 ≥48：**`Add` 三形态均 42px、`Open terminal` 16/46px ⇒ 全不过**。③互不重叠：**堆叠形态全过；一行形态 `Add` 压进编辑器 48dp 靶 6px 宽 × 38–47px 高 ⇒ 不过**。④落在可写区：**三形态过；唯「条在屏+一行」并集底沿 263 > pane 261，越界 2px ⇒ 不过**。⑤不被遮挡：**堆叠形态过；一行形态 `Open terminal` 被 `Send`/`Stop` 覆盖 44×47 = 94% ⇒ 不过** |
| 1px 临界点：消除还是声明？ | **声明（选项 B）+ 钉成显式断言**。测试侧唯一的「消除」就是给包含判据加 ±1px 容差 = **放宽断言**（禁），且会把 `Open terminal` 误纳为 composer 动作；生产侧消除属设计裁定，**本轮只写方案不实施**。触发条件四条见 §4.3 |
| 生产代码动了吗？ | **零改动。** 需要动的那一处已写成方案待裁定（§4.4） |
| 用例数 | 122（v141）→ **127**，**净增 5，0 减少**；quarantine 侧 3 → **4** |

---

## 1. 任务 A：错账的新旧对照

### 1.1 旧的 `PARITY 3/3` 说了什么（v139 原文）

```kotlin
assertEquals(
    "PARITY 3/3 — the inner layout's top the code in this tree would produce must " +
        "equal the one the device measured. It does not: the tree says " +
        "Alignment.Top (which returns 0 for every child, so bandTop $bandTop), " +
        "the v137 frame measured $bandTop + 14 = ${innerLayout.top}. " +
        "⇒ the v136 change was not what rendered the frame. …",
    deviceInnerTopPx,   // 14
    0,
)
```

它主张的是**「产生该帧的不是树里这份代码」**。这条被 v140 的探针**直接推翻**：

```
10-03 23:36:34.202 27592 27592 I BandUnion: minimal=true bandPx=48 editorTop=14 editorH=20 toolRowTop=0 toolRowH=48 unionBottomPx=48
10-03 23:42:23.570 31108 31108 I BandUnion: minimal=true bandPx=48 editorTop=14 editorH=20 toolRowTop=0 toolRowH=48 unionBottomPx=48
```

探针**就打在树里这份** `Composer.kt:2391` 的 `layout` 块里（与 `SessionComposerBandUnion.record(…)` 相邻），**跨两个 pid 逐字节复现**。
那个 14 不是「别人的偏移」，**是这棵树自己的函数在自己的真实输入上的求值**：
`composerBandChildTopPx(48, 20) = (48−20)/2 = 14`。

⇒ **旧账的比较对象本身是错的**：它拿 `Alignment.Top` 的**偏移 0** 去对一个**居中偏移 14**，
两者根本不是同一个量，然后据此推出「构建不对」。**这是一次错账，不是一条待修的绿账。**

### 1.2 新的框架

| | 旧（v139） | **新（v142）** |
|---|---|---|
| 权威侧 | 无（两侧互比） | **生产求值**：`unionBottomPx = max(editorTop+editorH, toolRowTop+toolRowH) = max(14+20, 0+48) = 48`，**探针直读** |
| dump 侧 | 直接要求 `dump == model`（49 vs 48，无准入规则） | **按 allowance 准入**，而 allowance **从帧里推导**，不是字面量 |
| allowance 定义 | — | `allowance = (innerLayoutBottom − productionUnionBottomAbs) + expansionDown` = `(249−262) + 14` = **1** |
| 方向 | — | **只能向下**（语义 ⊇ 布局 ⇒ `dump ≥ production`） |
| 上界 | — | `0 ≤ gap ≤ expansionDown`（不得超过它来源的扩张量） |
| 第 3 条断言的主题 | 「树 ≠ 帧」——**已证伪的主张** | 「生产求值就是 48，且 = `max(…)`，且 = 树里生产函数在探针输入上的求值」 |
| 红的原因 | 错因（构建归属） | **对因**：语义并集仍越出宿主盒 2px，`model == device` **尚不成立** |
| 收编命令 | 有 | **有，且更新为「绿了才收编」**（见下） |

### 1.3 收编命令（写在 quarantine 文件头部，逐字）

```powershell
Copy-Item research\v97_fix\quarantine\ModelVsDeviceMismatchTest.kt `
         app\src\test\java\com\xiufeigo\opencodeshell\ModelVsDeviceMismatchTest.kt
```

**这次收编语义变了**，头部已写明：v139 的版本是「修好 `Composer.kt:2210` 后收编」；
v142 的版本是 **「收编的那一刻它就该是绿的 —— 不绿就说明 parity 还没闭环」**。
要退回隔离状态就删掉副本，**不要改断言**。

### 1.4 分工不变

- **证据半进闸门**：`ComposerBandDeviceTruthTest`（11 例，绿）—— 解析同一份 XML、读同一份探针、
  钉住 allowance 的公式/方向/上界。它在**闸门里**。
- **判定半留隔离**：`research/v97_fix/quarantine/ModelVsDeviceMismatchTest.kt`（4 例，**1 红**，**不编译**）。
  它在**闸门外**，且**本轮仍不编译**（`app/build.gradle.kts` 不可写，`@Ignore` 被禁）。

---

## 2. 任务 A 的一半：红灯长什么样

拷进源集真跑（`research/v97_fix/_v142_quarantine_red.log`）：

```
ModelVsDeviceMismatchTest > theAdmissionIsProvisionalAndTheParityIsStillOwed FAILED
    java.lang.AssertionError at ModelVsDeviceMismatchTest.kt:416

4 tests completed, 1 failed

BUILD FAILED in 3s
52 actionable tasks: 2 executed, 50 up-to-date
```

**1 红 3 绿**：红的那一条是 `theAdmissionIsProvisionalAndItsParityIsStillOwed`，它断言
`deviceUnionBottomAbs − (bandTop + probeUnionBottomPx) == 0`，实测 **1**，消息里写明了
「准入是**临时**的：dump 的语义并集仍越过宿主盒底沿 **2px**（263 vs 261），
『生产无缺陷』是**几何**命题，不是可用性结论」。

**与旧账的红的区别**：旧账红在「我以为跑的是别的代码」（假），新账红在「语义框确实比布局框大，
而这 2px 越界还没在设备上解决」（真）。**同样一盏灯，换了个诚实的光源。**

---

## 3. 反向验证：注入改动 ⇒ 变红（逐字）

**注入**：把扩张拆分方向反过来（`expansionUp = expansion − expansion/2`，即 `13↑/14↓` → `14↑/13↓`），
只改**源集里那份临时副本**，quarantine 原件未动（事后核对：`expansionUp: Int get() = expansion / 2` 仍在）。

命令：

```
:app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ModelVsDeviceMismatchTest"
```

结果（`research/v97_fix/_v142_reverse.log`，**4 红**，比注入前的 1 红多 3 条）：

```
4 tests completed, 4 failed

BUILD FAILED in 3s
52 actionable tasks: 2 executed, 50 up-to-date
```

逐条（从结果 XML 读出，**逐字**）：

```
FAILED: theProductionEvaluationIsTheAdjudicatedValueAndItIsStillFortyEight
   java.util.NoSuchElementException
FAILED: theAdmissionIsProvisionalAndTheParityIsStillOwed
   java.util.NoSuchElementException
FAILED: theDumpSideIsAdmittedByTheTouchTargetAllowanceDownwardAndOnlyDownward
   java.lang.AssertionError: v142 TRIGGER (the split changed): 27 px splits 13 up / 14 down —
   the ODD remainder lands on the BOTTOM edge, which is the entire reason the dump's gap is on
   the bottom. If material3 ever splits it 14/13 the allowance becomes 0 and the dump would read
   equal to production; if it splits evenly the gap is 0 too. Neither is admissible without
   re-deriving this class. expected:<13> but was:<14>
FAILED: theDeviceReadingsAreTheArchivedFrameAndNotAStaleCopy
   java.lang.AssertionError: v137 device: three control bounds in the derived semantics box
   expected:<3> but was:<0>
```

**⇒ allowance 承重，判据不是恒真式。** 拆分方向一翻，allowance 就不再是 1，
`theDumpSide…` 这条**指名道姓地**红了，角色解析同时塌到 0 个控件。

**诚实披露**：其中两条以 `NoSuchElementException` 而非 `AssertionError` 失败 —— 因为翻转拆分
同时也把派生语义框推走了，导致角色解析找不到节点。**这是真实的失败形态，不是断言写得更好看**；
能干净指认触发条件的是 `theDumpSide…` 那条。

**触发器清单（逐条指名，各占一个测试方法，日志里能分辨谁坏了）**：

| 触发条件 | 变红的方法 |
|---|---|
| allowance 变大 / 变小 / 不再是那个式子 | `theDumpSideIsAdmittedByTheTouchTargetAllowance…` |
| 方向反转（dump 读到比 production **小**） | `theDumpSide…`（`direction flipped`） |
| 超出上界（gap > `expansionDown`） | `theDumpSide…`（`upper bound broken`） |
| 生产求值不再是 48 | `theProductionEvaluation…`（`production evaluation moved`） |
| 探针不再自洽（`max(…) ≠ unionBottomPx`） | `theProductionEvaluation…` |
| 帧被重取 | `theDeviceReadingsAreTheArchivedFrame…`（66 节点 / 214 / 262 / 3 控件 …） |

---

## 4. 任务 B：补可用性一侧（几何 ≠ 可用性）

### 4.1 方法（可判定，静态，全程不装设备）

新增 `app/src/test/java/com/xiufeigo/opencodeshell/ComposerBandA11yReachabilityTest.kt`（5 例，绿）。
输入是**已有真机 dump**（`wm density 160` ⇒ **1dp == 1px**），角色用 v141 的**布局锚点**判据定位，
再从布局框**推导**语义框，然后**逐项判定**：

| # | 项 | 达标线 | 怎么判 |
|---|---|---|---|
| ① | 语义框**高** | ≥ 48 | `h == editor.h`（编辑器是满带高子节点） |
| ② | 语义框**宽** | ≥ 48 | `w >= 48` |
| ③ | 与同 composer 其它动作**互不重叠** | 无重叠 | 面积交 > 0 即不达标 |
| ④ | **落在可写区**（宿主 pane）内 | `unionBottom ≤ paneBottom` | pane = 含带顶沿的**最紧**满宽 `View` |
| ⑤ | **不被外来 48dp 靶遮挡** | 无重叠 | 外来靶（不在 `actions` 里的 48 高 clickable）与本动作求交 |

**取样帧**（4 帧 / 2 形态，全部是归档的真机 dump，非本轮新采）：

| 帧 | 形态 | 带 | 宿主盒底沿 | 工具行**布局**框 | 扩张 |
|---|---|---|---|---|---|
| `v137_BARON_M.xml` | 一行 · **条在屏** | `[24,214][405,262]` | 261 | `[441,228][1316,249]` h=21 | 27 = **13↑/14↓**（奇） |
| `v140_600off.xml` | 一行 · 条关 | `[24,201][405,249]` | 261 | `[441,201][1316,229]` h=28 | 20 = **10↑/10↓**（偶） |
| `v140_A_bar.xml` | 一行 · 条在屏（另一次跑） | `[24,201][405,249]` | 261 | 同上 | 20（偶） |
| `v140_768.xml` | **堆叠** | `[28,244][1338,292]` | 350 | `[52,302][1243,330]` h=28 | 20（偶） |

### 4.2 逐项表（结论）

**composer 自己的动作**（v141 判据解析出的 `actions`）：

| 形态 / 帧 | 动作 | 语义框 | 尺寸 | ①高≥48 | ②宽≥48 | ③不重叠 | ④在可写区 | ⑤不被遮挡 |
|---|---|---|---|---|---|---|---|---|
| 一行·条在屏 `v137` | `Add` | `[399,215][441,263]` | 42×48 | ✅ | ❌ **42** | ❌ **压编辑器 6×47** | ❌ **底沿 263 > 261，越 2px** | ✅ |
| 同上 | chip（无名 View） | `[441,215][559,263]` | 118×48 | ✅ | ✅ | ✅ | ❌ 同上 | ✅ |
| 同上 | `Stop` | `[1316,215][1364,263]` | 48×48 | ✅ | ✅ | ✅ | ❌ 同上 | ✅ |
| 一行·条关 `600off` | `Add` | `[399,191][441,239]` | 42×48 | ✅ | ❌ **42** | ❌ **压编辑器 6×38** | ✅ 239≤261 | ✅ |
| 同上 | chip | `[441,191][559,239]` | 118×48 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 同上 | `Send` | `[1316,191][1364,239]` | 48×48 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 一行·`A_bar` | `Add` / chip / `Stop` | `[399,191]…` `[441,191]…` `[1316,191][1364,239]` | 42×48 / 118×48 / 48×48 | ✅ | ❌ **42** | ❌ **压编辑器 6×38** | ✅ | ✅ |
| **堆叠** `768` | `Add` | `[10,292][52,340]` | 42×48 | ✅ | ❌ **42** | ✅ **全形态唯一干净** | ✅ 340≤350 | ✅ |
| 同上 | chip | `[52,292][170,340]` | 118×48 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 同上 | 无名 View | `[1247,292][1308,340]` | 61×48 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 同上 | `Send` | `[1308,292][1356,340]` | 48×48 | ✅ | ✅ | ✅ | ✅ | ✅ |

**外来 48dp 靶（`Open terminal` 浮动按钮）** —— ⑤ 单独判：

| 帧 | 语义框 | 尺寸 | ②宽≥48 | ⑤遮挡 | 遮挡率 |
|---|---|---|---|---|---|
| `v137_BARON_M` | `[1350,192][1366,240]` | **16×48** | ❌ **16** | 被 `Stop` 遮 14×25 = 350 | **45.6%** |
| `v140_600off` | `[1320,192][1366,240]` | 46×48 | ❌ **46** | 被 `Send` 遮 44×47 = 2068 | **93.7%** |
| `v140_A_bar` | `[1320,192][1366,240]` | 46×48 | ❌ **46** | 被 `Stop` 遮 44×47 = 2068 | **93.7%** |
| `v140_768`（堆叠） | `[1320,192][1366,240]` | 46×48 | ❌ **46** | 与 `Send` **垂直不相交** = 0 | ✅ **0%** |

**逐项总结（就是 Lead 要的那句「是否都达标」）**：

| 项 | 达标情况 |
|---|---|
| ① 高 ≥ 48dp | ✅ **全部达标**（所有动作、所有形态都是 48） |
| ② 宽 ≥ 48dp | ❌ **全部不达标**：`Add` 三形态均 **42**；`Open terminal` **16 / 46 / 46 / 46**。**没有一个形态的 `Add` 达到 48 宽** |
| ③ 互不重叠 | 堆叠 ✅；**一行形态 ❌**：`Add` 的语义框压进编辑器自己的 48dp 靶 **6px 宽 × 38–47px 高**（`editor.right − add.left = 405 − 399`）。同一动作集内部（`Add｜chip｜send`）倒是互不相交 |
| ④ 落在可写区 | 三形态 ✅；**唯「条在屏 + 一行」❌**：并集底沿 **263 > pane 261，越界 2px** |
| ⑤ 不被遮挡 | 堆叠 ✅；**一行形态 ❌**：浮动 `Open terminal` 被 `Send`/`Stop` 覆盖 **94%**（v137 帧 45.6%） |

**另外记一笔（不在五项内，但属同一类）**：`v140_600off` 的 `Copy response [4,158][52,206]`
与编辑器 `[24,201][405,249]` 交叠 **28×5 = 140px²** —— 消息级动作压着输入框下沿。

### 4.3 「差 1px」到底会造成什么

**先定性**：那 1px **不是**可达性缺陷，**也不是**命中缺陷。v141 §4.4-1 点名的临界项是
`Open terminal [1320,192][1366,240]` 底沿 240 与派生语义框底沿 `semBot = 229+10 = 239` 差 1px，
它只是 **`Open terminal` 会不会被算进「composer 自己的动作」这一条判据的分界线**。
换句话说：**1px 是分类器的锋刃，不是用户的 bug。**

**但它锋刃后面那个东西是真 bug**，v141 钉下了 1px 却没点名它：一旦这 1px 翻面
（`Open terminal` 被纳入 `actions`），就会暴露 —— `Open terminal` 的语义框 46×48
有 **44×47 = 94%** 压在 `Send` 上（`v137` 帧是 16px 宽、45.6% 压在 `Stop` 上）。
**1px 分类边界和 94% 遮挡是同一个几何事实的两面。**

### 4.4 处置：**声明（选项 B）+ 钉成显式断言**

**为什么不选「消除」**：

- **测试侧消除 = 放宽断言。** 唯一的技术手段是给包含判据加 ±1px 容差
  （`it.bottom <= semanticBottom + 1`）。这正是本轮硬约束禁止的**放宽**，
  而且它会**把 `Open terminal` 误纳为 composer 动作** —— 把一个测试脆弱点换成一个**错误分类**，
  净值是负的。
- **生产侧消除 = 设计裁定，本轮不实施。** 若要让这 1px 变成 ≥2px 的真余量，
  需要动浮动按钮的垂直锚点或 composer 行的内边距，**属设计裁定**，按 Lead 指示**只写方案**：

> **方案（待裁定，未实施）**：把 `Open terminal` 的浮动锚点从 `bandBottom` 改为
> `bandBottom − 2`，使它与派生语义框产生 **2px** 的稳定分离。代价：浮动按钮上移 2px，
> 在 600 档（可用高度仅 600 − 261 = 339px）需复核是否与队列条相碰。
> **本轮零实施**，`app/src/main/**` 未动一个字节。

**处置本身**：已作为**显式声明**写进 `ComposerBandA11yReachabilityTest`
（`theOnePixelCriticalPointIsPinnedAsADeclaredFragilityWithItsTrigger`，钉住
`terminal.bottom − semanticBottom == 1`、`expansion == 20`、`expansion % 2 == 0`、
`v137 帧 expansion % 2 == 1`），并写明**四条触发条件**：

| # | 触发条件 | 会先在哪里变红 | 后果 |
|---|---|---|---|
| **T1** | material3 改变**奇数扩张**的拆分方向（v137 的 27 现在是 13↑/14↓，依据是 `⌊exp/2⌋`；Compose 的 `roundToInt(13.5)=14` **观测吻合、非源码证明**） | `ComposerBandDeviceTruthTest.theDumpSideDeviates…` 与本类同时红 | allowance 变 **0** ⇒ dump 读到 48 ⇒ **quarantine 的 owed parity 会转绿 —— 但那是语义框缩小了，不是缺陷修好了**，不可当好消息 |
| **T2** | 浮动 `Open terminal` 垂直移动 **1px** | `theRolesResolveOnTheOneRowFrameWhereV139sRuleHitZero`（它断言 `none { desc == "Open terminal" }`） | 1px 分界翻转，`Open terminal` 被误纳为 composer 动作 |
| **T3** | 工具行布局高 28 → **29**（扩张变奇数） | 同 T1 | 拆分歧义在 600 档**也会**变活 |
| **T4** | 任何一条 —— **都在像素移动之前**先红 | 上述三类断言 | 触发器**已装**，不靠人眼发现 |

**这 1px 已被消掉的那部分**：v141 说它是「唯一临界项」——v142 补上了**它背后那 94% 遮挡**，
所以这条脆弱点不再是一个孤立的谜：**它现在有名字、有量化、有触发条件。**

---

## 5. 为什么不降强度

| 维度 | v139（quarantine 侧） | **v142** | 方向 |
|---|---|---|---|
| 用例数 | 3 | **4** | ↑（+1） |
| 放宽的断言 | — | **0 条** | — |
| 删除的断言 | — | **0 条**（见下） | — |
| **撤下的主张** | `PARITY 3/3`「树 ≠ 帧」 | **1 条，已被探针证伪**；其**方法位被改用**为「生产求值 == 48 == `max(…)` == 树里函数求值」，**不是被丢弃** | 按 Lead 指令改写错账 |
| 1px 的钉法 | 无 allowance 概念 | **派生式**：`(innerLayoutBottom − productionUnionBottom) + expansionDown` + **方向** + **上界** | ↑ 约束集合严格变大 |
| 红灯的原因 | 错因（构建归属） | **对因**（语义越界 2px 未在设备闭环） | ↑ |
| 触发器 | 隐含在 3 条断言里 | **6 条，逐条指名，各占一个方法** | ↑ |
| 收编语义 | 「修好 `:2210` 后收编」 | **「收编时它就该是绿的；不绿 = parity 未闭环」** | ↑ |
| 分工 | 证据半进闸门 / 判定半隔离 | **不变** | = |

**关于「撤下 1 条」的自曝**：本轮**确实**撤下了 v139 `PARITY 3/3` 的**断言主体**。
它是**按 Lead 指令**撤的（它断言一个已被 v140 探针推翻的主张），
而**方法位、触发器、诚实红灯全部保留并加强**，用例数 3 → 4。
**没有任何一条为真且有效的断言被删掉或改松。**

新增 `ComposerBandA11yReachabilityTest` 是**纯新增**：
0 条既有断言被动，5 条全绿，钉的是**帧里读出来的字面量**，
`Add` 的 42px、越界的 2px、遮挡的 44×47/350 **都以字面量钉住**，
所以「它全绿」**不等于**「它达标」—— 恰恰相反，**它全绿是因为它把不达标这件事钉死了。**

---

## 6. 测试证据

### 6.1 命令与原文

环境：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`；抢锁 → 前台跑完 → 删锁。

**新增 a11y 类**：

```
:app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ComposerBandA11yReachabilityTest"
```
```
BUILD SUCCESSFUL in 3s
52 actionable tasks: 2 executed, 50 up-to-date
```

**quarantine 拷进源集（诚实红灯）**：

```
4 tests completed, 1 failed
BUILD FAILED in 3s
```

**反向验证（注入拆分方向反转）**：

```
4 tests completed, 4 failed
BUILD FAILED in 3s
```

**受影响全部类 → 0 失败**（17 个类）：

```
:app:testDebugUnitTest --tests "…ComposerBandDeviceTruthTest" `
                       --tests "…ComposerBandA11yReachabilityTest" `
                       --tests "…ComposerBandUnionV136Test" `
                       --tests "…ComposerBandTallEnoughV106Test" `
                       --tests "…ComposerBandTouchTargetV105Test" `
                       --tests "…ComposerBarOnScreenCriterionV135Test" `
                       --tests "…ComposerPaneBottomCriterionV108Test" `
                       --tests "…ComposerUnifiedCriterionV129Test" `
                       --tests "…ComposerChildUnionV104Test" `
                       --tests "…ComposerUnionBottomP5V109Test" `
                       --tests "…ComposerPositionDecompositionV107Test" `
                       --tests "…ComposerCriterionParityV118Test" `
                       --tests "…ComposerOverflowV103Test" `
                       --tests "…ComposerOneRowChipReserveV81Test" `
                       --tests "…ComposerOneRowEditorFloorV86Test" `
                       --tests "…ComposerDivergenceLocateV119Test" `
                       --tests "…ComposerDivergenceLocate2V120Test"
```
```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 45s
52 actionable tasks: 2 executed, 50 up-to-date
```

### 6.2 用例数（`app/build/test-results/testDebugUnitTest/*.xml` 逐类读出）

| 类 | 用例 | 失败 | 错误 |
|---|---|---|---|
| **`ComposerBandA11yReachabilityTest`（本轮新增）** | **5** | 0 | 0 |
| `ComposerBandDeviceTruthTest` | 11 | 0 | 0 |
| `ComposerBandUnionV136Test` | 8 | 0 | 0 |
| `ComposerBandTallEnoughV106Test` | 7 | 0 | 0 |
| `ComposerBandTouchTargetV105Test` | 6 | 0 | 0 |
| `ComposerBarOnScreenCriterionV135Test` | 7 | 0 | 0 |
| `ComposerPaneBottomCriterionV108Test` | 9 | 0 | 0 |
| `ComposerUnifiedCriterionV129Test` | 6 | 0 | 0 |
| `ComposerChildUnionV104Test` | 7 | 0 | 0 |
| `ComposerUnionBottomP5V109Test` | 11 | 0 | 0 |
| `ComposerPositionDecompositionV107Test` | 4 | 0 | 0 |
| `ComposerCriterionParityV118Test` | 12 | 0 | 0 |
| `ComposerOverflowV103Test` | 9 | 0 | 0 |
| `ComposerOneRowChipReserveV81Test` | 3 | 0 | 0 |
| `ComposerOneRowEditorFloorV86Test` | 8 | 0 | 0 |
| `ComposerDivergenceLocateV119Test` | 8 | 0 | 0 |
| `ComposerDivergenceLocate2V120Test` | 6 | 0 | 0 |
| **合计** | **127**（v141 为 122，**净增 5，0 减少**） | **0** | **0** |

新增 5 条用例名（确认**真跑**、非跳过）：

```
theAddActionIsFortyTwoPixelsWideInEveryFormAndThatIsUnderTheFortyEightDpMinimum
everyActionIsFortyEightHighAndOnlyTheBarOnFormOverflowsTheHostPane
theStackedFormIsDisjointButTheOneRowFormsOverlapTheEditorBySixPixels
theFloatingTerminalButtonIsOccludedBySendOrStopInEveryOneRowForm
theOnePixelCriticalPointIsPinnedAsADeclaredFragilityWithItsTrigger
```

**quarantine 侧**（隔离态，4 例 / 1 红 / 3 绿）：

```
theProductionEvaluationIsTheAdjudicatedValueAndItIsStillFortyEight                  PASS
theDumpSideIsAdmittedByTheTouchTargetAllowanceDownwardAndOnlyDownward              PASS
theDeviceReadingsAreTheArchivedFrameAndNotAStaleCopy                                PASS
theAdmissionIsProvisionalAndTheParityIsStillOwed                                   FAIL ← 诚实红灯
```

---

## 7. 未闭环

1. **【最重要】parity 仍未在设备上闭环。** 语义并集比布局并集大 1px（allowance）、
   且越过宿主盒 2px。quarantine 保持红是对的，但**本轮未上真机**，
   「改后行为」在设备上**未**验证过。
2. **② 宽 ≥ 48dp 全线不达标，处置权不在本轮。** `Add` 三形态恒 42px、`Open terminal` 16/46px。
   要达标需动按钮内边距或浮动按钮锚点 —— **属设计裁定，等 Lead**（§4.4 已给方案，未实施）。
3. **④ 的 2px 越界：几何已判，裁切未判。** `uiautomator` dump 里**没有** `clipChildren`，
   所以「这 2px 是被裁掉（靶真高 46）还是只是滚出视野」**静态不可判定**。
   要判需真机：`adb shell dumpsys window` / 截图对比，**本轮未授权装机**。
4. **⑤ 的 94% 遮挡未复验。** 静态几何已判，但「实际点下去是谁收到」取决于 view 命中顺序，
   **静态不可判定**，需真机 tap 复验。
5. **T1 的拆分依据仍是「观测吻合」而非源码证明**（`⌊exp/2⌋` vs `roundToInt(13.5)=14`），
   与 v141 §4.4-4 同一缺口，本轮**未闭环**。
6. **768 档判据 ② 口径仍未定义**（该帧 `unionPx = 96` vs `band = 48`）—— v141 遗留，未动。
7. **「队列条在屏」帧仍缺探针同帧读数**（v140 §8 遗留）。allowance 在 600 档 bar-off 上验算；
   v137（bar-on）的 allowance **用帧读数推导**，探针是另一帧的。
8. **未装机、未 `assembleDebug`、未做真机复验** —— 本轮证据 = JVM 测试 + 归档 dump + 归档探针日志。
9. **1 条 `E2ECHK5554u1w` 消息删不掉**（v140 §6 遗留），无用户可见删除入口。

---

## 8. 改了哪些文件

| 文件 | 性质 | 说明 |
|---|---|---|
| `research/v97_fix/quarantine/ModelVsDeviceMismatchTest.kt` | **quarantine（不编译）** | 撤下 `PARITY 3/3` 错账 → 新框架：生产求值为准 + allowance 准入（公式/方向/上界）；**保留诚实红灯**（`theAdmissionIsProvisional…` 恒红）；6 条指名触发器；头部**收编命令**更新为「绿了才收编」；3 → **4** 例 |
| `app/src/test/java/com/xiufeigo/opencodeshell/ComposerBandA11yReachabilityTest.kt` | **测试（新增 · 闸门内 · 绿）** | 48dp 靶 **a11y 可达性 5 项逐项表**（高/宽/重叠/可写区/遮挡）× 4 帧 2 形态，全部数从真机 dump **解析**；1px 临界点**声明 + 4 条触发条件**。**5 例** |
| `research/v97_verify/5554_md/artifacts/geom_v142.ps1` | **证据脚本（新增）** | a11y 可达性采样器：clickable 全表、48dp 判定、两两重叠面积、pane 定位 |
| `research/v97_fix/LEDGER_REWRITE_AND_USABILITY.md` | **报告（新增）** | 本文件（先写盘抢锁 → 定稿释放） |
| `research/v97_fix/_v142_geom.log` | 日志 | 采样原文 |
| `research/v97_fix/_v142_a11y.log` | 日志 | a11y 类 BUILD SUCCESSFUL |
| `research/v97_fix/_v142_quarantine_red.log` | 日志 | quarantine 拷入源集，4 例 / 1 红 |
| `research/v97_fix/_v142_reverse.log` | 日志 | 反向验证，4 例 / 4 红 |
| `research/v97_fix/_v142_regression.log` | 日志 | 17 类回归，BUILD SUCCESSFUL |

**未触碰**：`app/src/main/**`（**零改动**，`git status -- app/src/main` = **0 行**）、
`app/build.gradle.kts`、`ComposerBandDeviceTruthTest.kt`、`ComposerBandUnionV136Test.kt`、
v139 的 `Log.i` 探针（**保留**）、`SEMANTIC_INFLATION_RULING.md`、`UNION_1PX_NOT_EFFECTIVE.md`。

**过程里两次真实红灯，未抹**：
1. `_v142_a11y.log` 第一次跑：`NoSuchElementException` ——
   `v140_A_bar.xml` 的尾部动作标签是 `Stop` 不是 `Send`，我按 `Send` 查会 `single` 失败。
   改为 `desc == "Send" || desc == "Stop"`（同一个槽位的两种态）后转绿。
2. `_v142_quarantine_red.log` 第一次跑：`compileDebugUnitTestKotlin FAILED` ——
   `paneBottom` 的 `by lazy` 块返回了 `FrameNode?` 而非 `Int`，改为返回 `.bottom` 后转红。

**行尾**：全程只用编辑 / 写入工具，未用 `git checkout` / `git restore`（`autocrlf=false`）。
**隔离态恢复**：反向验证的临时副本用 `rm --`（可恢复删除）移出源集，
quarantine 原件事后核对**逐字未动**。
