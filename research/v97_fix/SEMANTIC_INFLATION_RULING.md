# SEMANTIC_INFLATION_RULING — v141：按探针改写归因 · 重做角色判据 · 裁定 ① 在该形态的取侧

状态：**已落地 · 主闸门绿（122 例 / 0 失败）· 反向验证已红 · 锁已释放**
日期：2026-10-04 · 承接 v140（`REPORT_PROBE_AND_ROLES.md`）、v139（`PIN_DEVICE_HARDLINK.md`）、v136（`UNION_1PX_NOT_EFFECTIVE.md`）

> 锁约定沿用 `PIN_DEVICE_HARDLINK.md:6-7`（仓库无独立 `*.lock`，**本报告文件即锁**）。
> 抢锁时刻 = 本文件首次写入（写入前 `Test-Path .agent-gradle-lock` = `False`）；本文件定稿 = 释放锁。
> `.agent-gradle-lock` 同样按协议：写锁 → **前台**跑完 → 绝对路径字面量删除。
>
> 授权与纪律：只跑 `:app:testDebugUnitTest --tests "<类名>"`；未 `assembleDebug` / `clean` / 全量 / 装机；
> 未杀进程；未新增用户可见字符串；**未放宽或删除任何断言**；用例数**只增不减**（6 → 11）；
> 未用 `git checkout` / `git restore` 改行尾（`autocrlf=false`）。
> **生产代码零改动**（`git status -- app/src/main` = 空），v139 的 `Log.i` 探针保留。

---

## 0. 一页结论

| 问题 | 答案 |
|---|---|
| 生产代码有缺陷吗？ | ✅ **没有。** 探针求值 = **48**，**从未打出 49** ⇒ 那 1px **不在生产求值里** |
| v136/v138 的「生产多算 1px」 | **作废**（已在 `UNION_1PX_NOT_EFFECTIVE.md` §11 追加订正节，原结论未改） |
| 真源是什么？ | **语义框为满足 48dp 触摸靶而居中扩张**（material3 `minimumInteractiveComponentSize`，在 `place()` 之下） |
| v139 点名的「唯一编造输入」 | **错账**：v140 证明 `editorPx` **也**是编造的（真值 **20**，模型喂 **48**）。`toolRowPx` 也已由探针直读（48）⇒ 现**零手打模型输入** |
| ① 该形态取哪一侧？ | **取生产侧**（探针 `unionBottomPx = 48`）⇒ ① = **+1**；dump 侧 **+2**，准入 allowance = **1px，方向只能向下** |
| allowance 是放宽吗？ | **不是。** 是把「语义 ≠ 布局」**写成公式 + 方向 + 上界**，并已用反向验证证明它**承重** |
| 角色判据为何命中 0？ | v139 把「带跨度」**等同于编辑器的 48dp 语义框**；该框是**绕 20/21 高布局框居中**的 ⇒ 控件语义顶沿必然浮在其**上方**（一行）或**下方**（堆叠）⇒ 同一把刀两种形态都削空 |
| 修好后 8 项对照？ | **6 项逐位相符（v139 为 5 项）**；余 2 项的差值**已证明等于同一条 allowance**，从「不符」变为「已归因」 |
| 断言降强度了吗？ | **零放宽、零删除、零条数减少**；6 → 11 条，且新增反向承重证明 |

---

## 1. 归因改写：生产代码是对的

### 1.1 探针原文（逐字，跨两个 pid 复现）

```
10-03 23:36:34.202 27592 27592 I BandUnion: minimal=true bandPx=48 editorTop=14 editorH=20 toolRowTop=0 toolRowH=48 unionBottomPx=48
10-03 23:42:23.570 31108 31108 I BandUnion: minimal=true bandPx=48 editorTop=14 editorH=20 toolRowTop=0 toolRowH=48 unionBottomPx=48
```

自洽性校验（先验，排除脏读）：

```
max(editorTop + editorH, toolRowTop + toolRowH) = max(14 + 20, 0 + 48) = 48  ✅ 与打印值一致
```

⇒ `unionBottomPx` 确为 `composerBandUnionBottomPx(bandPx=48, editorPx=20, toolRowPx=48)` 的求值。

### 1.2 三条订正（已写进 `UNION_1PX_NOT_EFFECTIVE.md` §11）

| # | v136/v138 表述 | v141 裁定 |
|---|---|---|
| 1 | 「生产代码多算了 1px」／「v136 生产函数在设备上没生效」 | **作废**。生产求值 48，探针从未打出 49 |
| 2 | 「那 1px 是生产代码的缺陷」 | **作废**。真源是 **48dp 触摸靶的语义扩张**（27px，13 上/14 下） |
| 3 | 「0 vs 14 ⇒ 产生该帧的不是树里这份代码」 | **作废**。探针打在树里这份上、跨 pid 复现 ⇒ **跑的就是树里这份**；0 来自编造输入 `editorPx=48`。`Composer.kt:2210` **不必动** |

### 1.3 两个量不是同一个量

| 量 | 空间 | 定义 | 本帧 |
|---|---|---|---|
| **生产求值** | 布局 layout | `max(editorTop+editorH, toolRowTop+toolRowH)` | `unionPx = 48`（绝对 262） |
| **dump 读数** | 语义 semantics/a11y | 48dp 触摸靶框的并集 | `unionPx = 49`（绝对 263） |

`unionPx 49` 与 `① = +2` 这两个**观测依然成立**，但成因从「生产多算」改为「语义框比布局框大 27px、居中后下沿多吃 1px」。

---

## 2. ① 在该形态的取侧裁定 + allowance 算式

**裁定（Lead 已裁）：取生产侧。** 权威值 = 探针的 `unionBottomPx = 48`。

### 2.1 算式

```
布局底沿 (HSV [441,228][1316,249])        = 249
扩张量   = 48 − innerH(21)                 = 27
拆分     27 上 13 / 14 下                    （整数除法，奇数余数落到底沿）
语义底沿 = 249 + 14                         = 263   ⇒ dump unionPx = 49
生产并集底沿 = bandTop(214) + 48            = 262   ⇒ 生产 unionPx = 48
allowance = 263 − 262                       =  1   方向：**只能向下**（语义 ⊇ 布局）
```

判据 ①（`unionBottom − paneBottom`）：

```
生产侧（裁定取用）:  262 − 261 = +1      ← 权威值
dump 侧（准入偏差）:  263 − 261 = +2      ← = +1 + 同一个 1px allowance
```

`paneBottom = 261` 是**宿主**量，**不被触摸靶扩张**，故整条偏差原样穿过。

### 2.2 allowance 已显式写进 pin（`ComposerBandDeviceTruthTest`）

| 断言 | 内容 |
|---|---|
| `theDumpSideDeviatesFromProductionByExactlyTheTouchTargetExpansionDownward` | 扩张量 27、`13 上`、`14 下`、`249+14=263`、**allowance = 1**、判据 ① 生产侧 `+1`、dump 侧偏差 `= 1` |
| 方向断言 | `deviceUnionPx >= productionUnionPx`（语义 ⊇ 布局 ⇒ dump 只能读**更低**，永不更高） |
| 上界断言 | `unionInflationAllowance in 0..expansion`（准入偏差不得超过它来源的扩张量） |

> **这不是放宽断言。** v139 是 `assertEquals(1, dump − model)`（一个字面量）；
> v141 是「1 = `expansionDown(14)` 减去布局已覆盖的 13」**加上**方向与上界。
> 同一行断言的**约束集合严格变大**（原来只钉值，现在同时钉值、钉来源、钉方向、钉上界）。

---

## 3. 反向验证：allowance 改成 0 ⇒ 必须红

做法：临时新增一个**独立**测试方法（**不改动任何既有断言**来制造失败），强制 `allowance = 0`
——即要求 dump 侧并集底沿**等于**生产侧。

命令：

```
:app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ComposerBandDeviceTruthTest"
```

结果（**BUILD FAILED**，逐字）：

```
=== FAILED CASE: v141ReverseVerificationAllowanceZeroMustBeRed ===
java.lang.AssertionError: v141 REVERSE (allowance forced to 0): if the dump side were required to equal
the production union bottom with NO touch-target expansion admitted, the pin is RED — which is the
proof that the allowance is load-bearing and the pin is not a tautology. expected:<48> but was:<49>
--- type: java.lang.AssertionError ---

tests=12 failures=1 errors=0
BUILD FAILED in 3s
```

**⇒ allowance 承重，pin 不是恒真式。** 该临时方法**已在取证后删除**（删除后回归 11 例 / 0 失败）。
日志：`research/v97_fix/_v141_reverse.log`。

---

## 4. 任务 B：角色判据为何命中 0 + 修好后的 8 项对照

### 4.1 为什么命中 0（v140 实测根因 + dump 原文片段）

v139 的 `controls` 判据是：`clickable=true` ∧ **顶沿落在带跨度内** ∧ 非编辑器，
而「带跨度」= **编辑器自己的 48dp 语义框**。
**这个前提是错的**：编辑器那个 48dp 框**不是布局量**，它是绕 20/21 高**布局**框**居中**的触摸靶。

dump 原文片段（`v140_600off.xml`，一行形态）：

```
EditText [24,201][405,249] h=48      ← v139 的「带跨度」= 201..249
Add     [399,191][441,239] h=48     ← 191 < 201  ⇒ 被「顶沿落在带内」正确排除 ⇒ 命中 0
Send    [1316,191][1364,239] h=48   ← 同上
```

dump 原文片段（`v140_768.xml`，堆叠形态）：

```
EditText [28,244][1338,292] h=48     ← 带跨度 244..292
HSV     [52,302][1243,330] h=28     ← 302 > 292 ⇒ 整行落在带跨度外 ⇒ layoutBox 命中 0
Send    [1308,292][1356,340] h=48   ← 292 在边界，工具行整体下移
```

⇒ **同一把刀，两种形态，各削空一次。** v139 §2.1「角色才是可复用的契约」在此**不成立**：
那 5 条判据是**按 v137 单帧标定**的。

补充根因（v140 §4.3-4）：`HSV` 高度**不是常量**（v137 = 21、v140 600 = 28）⇒ 任何写死 21 的判据都是帧常量。

### 4.2 换掉的三条判据

| 角色 | v139（命中 0） | **v141（稳健）** | 为什么稳健 |
|---|---|---|---|
| 工具行**布局**框 | `HorizontalScrollView` ∧ 顶沿在带跨度内 | `HorizontalScrollView` ∧ (**顶沿 ≥ 编辑器底沿 − 1 且 ≤ 底沿 + 2×带高**) ∨ (**左沿 ≥ 编辑器右沿** ∧ 垂直中心距 ≤ 1 带高) | **形态无关**：堆叠走前半、一行走后半；**不含高度**，21/28 都能用 |
| **形态** | 无 | 工具行**布局**顶沿 − 编辑器顶沿 `< 带高/4` ⇒ 一行，否则堆叠 | 先判形态，后续角色才取值正确 |
| 控件（48dp 语义框） | `clickable` ∧ **顶沿在带跨度内** | `clickable` ∧ 高 = 48 ∧ **完全落在派生语义框内**（`top ≥ semTop ∧ bottom ≤ semBot`），其中 `semTop = 布局顶 − ⌊扩张/2⌋`、`semBot = 布局底 + (扩张 − ⌊扩张/2⌋)` | 锚在**布局**上，语义框**推导**出来 ⇒ 两种形态同一把刀 |
| 编辑器 / pane | 唯一 `EditText` / 满宽 `View` 中底沿最小 | **原样保留**（v140 实测两帧都唯一，本就正确） | — |

**它是真判别式，不是碰巧的过滤器** —— 同一轮运行会报告**排除了谁、差多少**：

```
v140_600off 排除:  Open terminal [1320,192][1366,240]  ← 底沿差 1px（唯一的临界项，见 §4.4）
                  Copy response [4,158][52,206]        ← 顶沿差 33
                  顶栏 Home/New session/… 12 个         ← 顶沿差 161
v140_768   排除:  Open terminal / Toggle review panel / 顶栏 15 个  ← 顶沿差 77..262
```

### 4.3 修好后的 8 项对照（v141 框架）

v139 得分：**5 符 / 3 不符**。v141：

| # | 量 | 模型/生产侧 | dump 侧 | 判定 | vs v139 |
|---|---|---|---|---|---|
| 1 | 带顶沿 | 214（基准） | **214** | ✅ 符 | 同 |
| 2 | 带高 | 探针 `bandPx=48` | **48** | ✅ 符 | 同 |
| 3 | 编辑器**布局**顶沿（相对带） | `composerBandChildTopPx(48,20) = 14` | 探针 `editorTop=14` | ✅ **符** | **翻转**（v139: 0 vs 0，但 0 建立在编造输入上） |
| 4 | 工具行顶沿（相对带） | `composerBandChildTopPx(48,48) = 0` | 探针 `toolRowTop=0` | ✅ 符 | 同 |
| 5 | **并集底沿 `unionPx`** | **探针 48（裁定权威）** | **49** | ⚠️ 差 **1 = allowance**（`14 下 − 13 上`） | 从「模型缺陷」→ **已归因的语义差** |
| 6 | 顶沿差 `topDelta` | 布局 **14** | 语义 **1** | ⚠️ **1 = 14 − 13(上扩)**，与 #5 **同源** | 从「模型乐观 1px」→ **已归因** |
| 7 | 控件自身**布局**顶沿（相对带） | **14**（树里函数自算 `(48−20)/2`） | **14** | ✅ **符** | **翻转**（v139: 0 vs 14「树 ≠ 帧」） |
| 8 | 判据 ① | 生产侧 **+1**（裁定取用） | **+2** | ⚠️ 差 **1 = 同一 allowance** | 从「模型缺项」→ **已归因** |

**汇总：6 项逐位相符（v139 为 5 项），3 项差值全部归因到同一条 allowance。**
**「不符」项从 3 降到 0 —— 但不是被抹掉，而是从「未解释的差异」变成「有公式、有方向、有上界的差异」。**
全部写进断言：`theEightItemComparisonUnderTheNewFramework`。

### 4.4 不稳定处（明确写出）

1. **600-off 帧的 1px 临界**：`Open terminal [1320,192][1366,240]` 底沿 240 与派生语义框底沿
   `semBot = 239` **只差 1px**。它是该帧唯一的临界排除项。若 material3 的上下拆分改成
   12/12，该节点会被误纳，`controls.size` 从 3 变 4 ⇒ `theRolesResolveOnTheOneRowFrame…` 会红。
   **这是真实的脆弱点，不是余量。**（`assertTrue(... desc == "Open terminal" ...)` 已单独钉住。）
2. **`HSV` 高度是帧常量**（21 / 28）⇒ 任何把它当常量的写法都会在换帧时失效；v141 只**读**不**写死**。
3. **768 堆叠形态 `controls` 命中 4 个**（比 600 的 3 个多一个 `View`）⇒ 断言按**帧**钉数量，
   没有跨帧统一常量，这是有意的。
4. **`semTop` 用了 `⌊扩张/2⌋`（向下取整）**：奇数扩张时余数落到底沿。Compose 的
   `roundToInt((48−21)/2f) = roundToInt(13.5) = 14` 是**四舍五入**；语义框的拆分由
   material3 的布局决定。两者在 `⌊13.5⌋=13` / `⌈13.5⌉=14` 上恰好一致，本轮**实测吻合**，
   但**依据是观测吻合，不是源码证明** —— 记为未闭环。

---

## 5. 为什么不降强度

| 维度 | v139 | v141 | 方向 |
|---|---|---|---|
| 用例数 | 6 | **11** | ↑（+5，0 减少） |
| 放宽的断言 | — | **0 条** | — |
| 删除的断言 | — | **0 条** | — |
| 1px 的钉法 | 字面量 `assertEquals(1, dump − model)` | **派生式**：值 + 来源公式 + 方向 + 上界 | ↑ 约束集合变大 |
| 模型输入 | 1 处编造（`editorPx=48`）+ 1 处推断（`toolRowPx`） | **0 处手打**（全部探针直读） | ↑ |
| 判据的跨帧性 | 按 v137 **单帧标定**，换帧即死 | 布局锚点，**两形态三帧实测通过** | ↑ |
| 承重性 | 未证 | **反向验证已红**（§3） | ↑ 新增 |
| 隔离分工 | 证据半进闸门 / 判定半隔离 | **不变** | = |

`ComposerBandUnionV136Test` **一个字节未动**（本轮未编辑该文件，回归 8 例 / 0 失败）。

---

## 6. 测试证据

### 6.1 命令与原文

环境：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`；抢锁 → 前台跑完 → 删锁。

单类（pin 主体）：

```
:app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ComposerBandDeviceTruthTest"
```
```
BUILD SUCCESSFUL in 3s
52 actionable tasks: 2 executed, 50 up-to-date
```

反向验证（allowance = 0）：

```
BUILD FAILED in 3s
52 actionable tasks: 2 executed, 50 up-to-date
12 tests, 1 failure —— 见 §3 逐字
```

### 6.2 受影响全部类 → 0 失败（16 个类，逐类单跑）

| 类 | 用例 | 失败 |
|---|---|---|
| `ComposerBandDeviceTruthTest` | 11 | 0 |
| `ComposerBandUnionV136Test` | 8 | 0 |
| `ComposerBandTallEnoughV106Test` | 7 | 0 |
| `ComposerBandTouchTargetV105Test` | 6 | 0 |
| `ComposerBarOnScreenCriterionV135Test` | 7 | 0 |
| `ComposerPaneBottomCriterionV108Test` | 9 | 0 |
| `ComposerUnifiedCriterionV129Test` | 6 | 0 |
| `ComposerChildUnionV104Test` | 7 | 0 |
| `ComposerUnionBottomP5V109Test` | 11 | 0 |
| `ComposerPositionDecompositionV107Test` | 4 | 0 |
| `ComposerCriterionParityV118Test` | 12 | 0 |
| `ComposerOverflowV103Test` | 9 | 0 |
| `ComposerOneRowChipReserveV81Test` | 3 | 0 |
| `ComposerOneRowEditorFloorV86Test` | 8 | 0 |
| `ComposerDivergenceLocateV119Test` | 8 | 0 |
| `ComposerDivergenceLocate2V120Test` | 6 | 0 |
| **合计** | **122** | **0** |

**用例数未减少**（`ComposerBandDeviceTruthTest` 6 → 11）。
**`quarantine/` 的分工不变**：证据半进闸门（`ComposerBandDeviceTruthTest`，绿）、
判定半隔离（`ModelVsDeviceMismatchTest`，红、不编译）。本轮**未动**该文件的编译状态。

---

## 7. 未闭环

1. **【最重要】`quarantine/ModelVsDeviceMismatchTest.kt` 里留着一笔错账。**
   其 `PARITY 3/3` 断言写的是「**树说 `Alignment.Top` ⇒ 0，设备说 14 ⇒ 产生该帧的不是树里这份代码**」。
   **v140 探针已推翻这半句**：跑的就是树里这份，14 是 `composerBandChildTopPx(48,20)` 的正常求值。
   该文件**仍红、仍未编译**，本轮按 Lead 的「分工不变」**未改它的判定语义** ——
   但它是**待裁定的错账**，不是待修的绿账。**需要 Lead 决定是否重写该类的 PARITY 3/3。**
2. **48dp 触摸靶的 a11y 可达性未评估。** 本轮只做几何归因。扩张后控件的实际点击热区、
   与相邻控件的重叠、语音朗读范围**均未验证**。「生产无缺陷」是**几何命题**，不是可用性结论。
3. **「队列条在屏」帧仍缺探针同帧读数**（v140 §8 遗留）。本轮的 allowance 算式在
   **600 档 bar-off / idle 帧**上验算；bar-on 只把整个 composer 整体下移 13–14px，
   **相对量不变**，故结论对该缺口免疫，但「同帧同时持有」这件事本身仍未做到。
4. **768 档判据 ② 口径仍未定义**（该帧 `unionPx = 96` vs `band = 48`）。
   本轮把 `96` 钉成断言以防被悄悄重定义，但**口径本身**（band 取 48 还是 96）**未裁定**。
5. **`⌊扩张/2⌋` 的依据是观测吻合，不是源码证明**（见 §4.4-4）。
6. **1px 临界脆弱点**（见 §4.4-1）未消除。
7. **未做真机复验**：本轮证据 = JVM 测试 + v140 已归档的探针日志。
   **未装机、未 `assembleDebug`** ⇒ 「改后行为」**未**在设备上验证过。
8. **1 条 `E2ECHK5554u1w` 消息删不掉**（v140 §6 遗留），无用户可见删除入口。

---

## 8. 改了哪些文件

| 文件 | 性质 | 说明 |
|---|---|---|
| `app/src/test/java/com/xiufeigo/opencodeshell/ComposerBandDeviceTruthTest.kt` | **测试** | 角色判据重写（4 条）+ 探针直读模型输入 + allowance 公式/方向/上界 + 两帧非标定帧验证 + 8 项对照。6 → **11** 条用例，**0 放宽 0 删除** |
| `research/v97_fix/UNION_1PX_NOT_EFFECTIVE.md` | **报告** | **追加 §11 订正节**（原 §1–§10 结论与数据**逐字未动**）：归因订正、3 项不符逐项重解、缺陷归属表 |
| `research/v97_fix/SEMANTIC_INFLATION_RULING.md` | **报告** | 本文件（先写盘抢锁 → 定稿释放） |
| `research/v97_verify/5554_md/artifacts/geom_v141.ps1` | **证据脚本** | v141 判据采样器（三帧两形态，含「排除了谁、差多少」） |
| `research/v97_fix/_v141_run1.log` / `_v141_reverse.log` | **日志** | 主运行 / 反向验证（红） |

**未触碰**：`app/src/main/**`（**零改动**，`git status -- app/src/main` = 空）、
`app/build.gradle.kts`、`quarantine/ModelVsDeviceMismatchTest.kt`、
`ComposerBandUnionV136Test.kt`、v139 的 `Log.i` 探针（**保留**）。
**行尾**：全程只用编辑/写入工具，未用 `git checkout` / `git restore`。
