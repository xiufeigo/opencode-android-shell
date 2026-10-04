# USABILITY_CELLS_RULING — v143：按"以官方为准"裁定可用性逐项 + 处置 ③⑤④

状态：**已落地 · 主闸门绿（18 类 / 132 例 / 0 失败）· 生产代码零改动 · 锁已释放**
日期：2026-10-04 · 承接 v142（`LEDGER_REWRITE_AND_USABILITY.md`）、v141（`SEMANTIC_INFLATION_RULING.md`）

> 锁约定沿用 `PIN_DEVICE_HARDLINK.md:6-7`（仓库无独立 `*.lock`，**本报告文件即锁**）。
> 抢锁时刻 = 本文件首次写入（写入前 `.agent-gradle-lock` 不存在，`git status -- app/src/main` = 空）。
> Gradle 锁：写 `.agent-gradle-lock` → **前台**跑完全部测试 → 用**绝对路径字面量**释放。
> 本轮释放时 `Remove-Item` 被本地硬安全策略拦截（禁止永久删除），
> 未绕过该拦截，改用单条顶层 `rm -- "C:\sensorsdata\main\program\opencode-android-shell\.agent-gradle-lock"`，
> 运行时路由至受信任的可恢复删除启动器，输出逐字：
> `mavis-trash: moved to trash: 'C:\sensorsdata\main\program\opencode-android-shell\.agent-gradle-lock'`
>
> 授权与纪律：只跑 `:app:testDebugUnitTest --tests "<类名>"`；未 `assembleDebug` / `clean` / 全量 / 装机；
> 未杀进程；未新增用户可见字符串；用例数**只增不减**；未用 `git checkout` / `git restore` 改行尾（`autocrlf=false`）。
> **生产代码零改动**（`git status --porcelain -- app/src/main` = **0 行**，跑完复核仍为 0 行）。

---

## 0. 一页结论

| 问题 | 答案 |
|---|---|
| **官方是否规定横向尺寸下限？** | **没有。官方根本没有横向尺寸规则。** `Add` 是 `28×28` 图标按钮、`Send` 是 `size-7` = `28×28`、工具行是 `h-11` = `44` —— **全是 48 的三分之一到三分之二**。48dp 是 Material3 的 `minimumInteractiveComponentSize`（**Android** 语义），**不是上游要求** |
| **② 的定性** | **偏离 Android 规范，但忠实于上游**（且比上游更宽）。`Add` 42 > 上游 28、`Send` 48 > 上游 28。**不是上游回归，也不是本轮回归** |
| **③ 的处置** | **判定 + 不修**。6px 是 381px 宽编辑器的 **1.6%**、且在最右缘，判定为**无害**；修它必须重切带宽分配 = 动 48dp 靶（红线） |
| **⑤ 的处置** | **判定为真缺陷 + 不修**。**推翻了 v142 的"静态不可判定"**：残余 140px² 全部是碎片，**最宽的窄边只有 2px** ⇒ 手指够不着，**静态可判定**。修它 = 动浮动按钮垂直锚点 = 与 ④ 同一个旋钮，同样动 48dp 靶 |
| **④ 的 2px** | **记在既有 allowance 框架内，不新开一笔、不扩 allowance**。算式：`overflow = expansionDown − (paneBottom − toolRowBottom) = 14 − 12 = 2`。**它不是 dump 侧准入间隙**，是宿主盒容纳性事实 |
| 有没有断言被放宽 | **0 条**。新增 5 条，全绿 |
| 生产代码 diff | **无**。需要动的地方写成了方案等裁定 |
| 用例数 | 127（v142）→ **132**，**净增 5，0 减少** |
| 测试 | 18 类全跑，`BUILD SUCCESSFUL in 44s`，**132 例 / 0 失败** |

---

## 1. 任务 ①：先核上游（唯一标尺）

### 1.1 结论：**上游没有规定横向尺寸下限**

在 `_upstream/`（opencode `2.0.11`）里，composer 动作按钮的尺寸**全部是写死的字面量**，
逐条原文如下（文件:行号 + 原文）：

| # | 位置 | 原文 | 推出的尺寸 |
|---|---|---|---|
| **1** | `_upstream/packages/ui/src/actions/icon-button/icon-button.css:41-44` | `[data-component="icon-button-v2"][data-size="large"] {`<br>`  width: 28px;`<br>`  height: 28px;`<br>`}` | **`large` = 28×28** |
| **2** | 同上 `:36-39` | `[data-component="icon-button-v2"][data-size="normal"] {`<br>`  width: 24px;`<br>`  height: 24px;`<br>`}` | `normal` = 24×24 |
| **3** | 同上 `:30-34` | `[data-component="icon-button-v2"][data-size="small"] {`<br>`  width: 20px;`<br>`  height: 20px;`<br>`  border-radius: 4px;`<br>`}` | `small` = 20×20 |
| **4** | `_upstream/packages/app/src/composer/editor/editor.tsx:637-647` | `<Menu.Trigger`<br>`  as={IconButton}`<br>`  data-action="composer-attach"`<br>`  type="button"`<br>`  icon={<Icon name="plus" />}`<br>`  variant="ghost-muted"`<br>`  size="large"`<br>`  …` | **`Add` = `size="large"` ⇒ 28×28**，纯图标（`plus`），**无文字** |
| **5** | `_upstream/packages/app/src/composer/editor/editor.tsx:882-889` | `<IconButton`<br>`  data-action="composer-submit"`<br>`  type="button"`<br>`  …`<br>`  variant="submit"`<br>`  class="size-7 rounded-md p-[6px]"` | **`Send` = `size-7` = 28×28**（`size-7` = 1.75rem = 28px），纯图标（`arrow-up` / `stop`） |
| **6** | `_upstream/packages/app/src/composer/editor/editor.tsx:250` | `<div class="flex h-11 items-center px-2">` | **工具行 = `h-11` = 44px**，不是 48 |

**逐条回答 Lead 的那个问句**：

> **官方是否规定了横向尺寸下限？**

**否。上游连"最小 48dp"的通用语义都没有落到宽度上**，只有三档固定尺寸 20 / 24 / 28，
**实际宽度由布局与图标档位决定**（`Add` 是窄图标 28、`Send` 是图标按钮 28）。
工具行 44（`h-11`）也说明：**上游的 48dp 概念根本不存在**。

在本仓 `_upstream/packages/**` 全量搜索 `touch-target|touchTarget|min-width: 44|min-width: 48|min-height: 44|min-height: 48`，
命中项**全部无关**（`stats` 应用、`titlebar.css` 的移动抽屉 tab `min-height: 44px`、
`session-turn.css` 的 `height: 44px`、markdown 的 `min-width: 480px`）——
**composer 动作按钮上没有一条触摸靶下限规则**。

⇒ **48dp 是本壳引入的 Material3 `minimumInteractiveComponentSize` 语义，属 Android 平台规则，
不是上游要求。** 这条是本轮全部定性的地基。

### 1.2 逐项对照本壳：宽度

| 项 | 上游 | 本壳（dump 实测） | 是否一致 |
|---|---|---|---|
| `Add` 宽 | **28**（`size="large"`，`icon-button.css:41-44`） | **42**（三形态恒定） | **不一致，但本壳更宽（42 > 28）** |
| `Send`/`Stop` 宽 | **28**（`size-7`，`editor.tsx:889`） | **48** | **不一致，本壳更宽（48 > 28）** |
| `Open terminal` 宽 | 上游无对应物（本壳自有的浮动按钮） | 16 / 46 / 46 / 46 | **不适用（无上游对应）** |
| 工具行**布局**高 | **44**（`h-11`，`editor.tsx:250`） | **44**（`Composer.kt:2715` `TOOL_ROW_HEIGHT = 44.dp`，注释逐字写 `/** \`h-11\` tool row. */`） | ✅ **完全一致** |
| `Add` 按钮本体 | 28×28 | `Composer.kt:2493-2496` `.size(28.dp)` | ✅ **完全一致** |
| `Send` 按钮本体 | 28×28 | `Composer.kt:2763` `SUBMIT_SIZE = 28.dp`，注释逐字 `/** \`size-7\` submit button. */` | ✅ **完全一致** |

**关键读法**：本壳的 42 / 48 **不是**本壳自己发明的宽度。
它是**上游 28px 图标按钮**在 Android 上被 Material3 的
`minimumInteractiveComponentSize` 撑到 48 高之后留下的宽度
（28 + 左右各 7px 触摸扩张 = 42）。**按钮本体与上游逐字一致（都是 28），
差异全部来自平台语义，不是来自对上游的改动。**

### 1.3 ② 的定性（按 Lead 的两种情形二选一）

**不构成「本壳偏离上游」⇒ 按「偏离规范但忠实上游」记账。**

依据（行号见 §1.1）：
- `_upstream/packages/ui/src/actions/icon-button/icon-button.css:41-44` —— 上游 `large` = **28×28**
- `_upstream/packages/app/src/composer/editor/editor.tsx:889` —— 上游 `Send` = **`size-7` = 28×28**
- `_upstream/packages/app/src/composer/editor/editor.tsx:250` —— 上游工具行 = **`h-11` = 44px**
- `app/src/main/.../Composer.kt:2496`、`:2763`、`:2715` —— 本壳三处**逐字复制**了 28 / 28 / 44

**⇒ 按「偏离规范但忠实上游」记账，注明依据行号如上。**

并且要写清一个比"忠实"更强的事实：**本壳在每一个动作上都比上游更宽**
（`Add` 42 > 28、`Send` 48 > 28）。所以 ② 既**不是上游回归**，也**不是 v136→v142 任何一轮的回归**，
它是 **Android 平台语义在本壳落地后的必然形状**。

**这一条同时收编了 v142 §7-2 的未闭环项**（"② 处置权不在本轮"）：
处置权现在**不在"要不要改宽度"**，而在"要不要为了满足 48dp 而在 Android 上加宽/加高"——
那是**产品取舍**（图标变大 vs 信息密度），**不是缺陷**。记为**声明**，附触发条件（§5.2 T6）。

---

## 2. ③ 与 ⑤ 的严重度判定（先判定，后处置）

### 2.1 ③「6px 压编辑器」——**判定：无害，不修**

**用已有 dump 给出的可判定结论**（`UsabilityCellsRulingV143Test.theSixPixelSliverIsOnePointSixPercent…`）：

| 帧 | 编辑器 | 重叠 | 占编辑器面积 |
|---|---|---|---|
| `v137_BARON_M` | 381×48 = 18288px² | 6×47 = 282px² | **1.5%** |
| `v140_600off` | 381×48 | 6×38 = 228px² | **1.2%** |
| `v140_A_bar` | 381×48 | 6×38 = 228px² | **1.2%** |

**"是否真的够不着 / 会误触"的可判定结论**：
- **够不着？否。** 6px 是竖直细条，贴在编辑器**最右缘**。编辑器 undisputed 部分
  = `405 − 399 = 375px` 宽。要点编辑器，手指往左挪 6px 即可，**不存在够不着**。
- **会误触？否（静态可判定）。** 细条落在编辑器右端，那里**没有文字、没有人会去点**；
  即便点到，落点是编辑器自己的靶（编辑器右端 = 光标区），行为仍是"聚焦输入框"，**不是破坏性误触**。
- 且该 6px **是触摸扩张的产物**（28→42 的那 14px 里有 6px 落进编辑器），
  把它消掉意味着**收掉 Material3 的触摸扩张** = **主动把靶改小**，
  那是拿可用性换几何整洁，**净值为负**。

**⇒ 严重度：无害（cosmetic）。处置：不修，钉成判定。**

### 2.2 ⑤「94% 遮挡」——**判定：真缺陷**（并**推翻** v142 的"静态不可判定"）

v142 §7-4 写的是"静态不可判定，需真机 tap 复验"。
**v143 找到了让它可判定的判据：残余的形状。**

**先定 z 序**（这是 v142 没做的一步）。`uiautomator` 按 view 顺序输出兄弟节点，
**索引靠后者绘制在上、优先收到 tap**。`v140_600off.xml` 实测：

```
idx=46   desc=Open terminal    bounds=[1320,192][1366,240]
idx=49   desc=Add              bounds=[399,191][441,239]
idx=54   desc=Send             bounds=[1316,191][1364,239]
```

`Send` 索引 54 > `Open terminal` 索引 46 ⇒ **`Send` 在上，遮挡是真的会挡住点击。**
（该断言已钉进测试：`assertEquals(true, send.index > terminal.index)`。）

**再看残余**（`Open terminal` 46×48 = 2208px²，被 `Send` 遮 44×47 = 2068px²，剩 **140px²**）。
140px² 分解成**互不相交**的碎片（测试断言 `sum == 2208 − 2068` 已钉住）：

| 碎片 | 尺寸 | 窄边 |
|---|---|---|
| 右侧竖条 | 2×48 | **2** |
| 下侧横条 | 44×1 | **1** |

**⇒ 没有任何碎片的窄边超过 2px。**
成人指尖接触斑约 8–10mm，在 160dpi 下 ≈ **40–50px**。
**2px 的目标任何手指都按不中 ⇒ `Open terminal` 在一行形态下功能性死亡。**

**⇒ 严重度：真缺陷（阻断级：功能不可达）。静态可判定。**

**这修正了 v142 的一个判断，不是放松它**：v142 说"要判需真机 tap 复验"，
本轮用**残余窄边 ≤ 3px**（已钉成断言，未来帧若留下 ≥16px 碎片就变红、强制重新人工裁定）
把"够不够得着"从主观问题变成了**可比较的几何判据**。

### 2.3 处置：③⑤ **都不修**，理由与方案

**为什么不修（先说红线在哪）**：

- ③ 的唯一修法 = **收掉 Material3 触摸扩张**或**重切带宽分配** ⇒ 直接**动 48dp 靶** = 红线。
- ⑤ 的唯一修法 = **移动浮动 `Open terminal` 的垂直锚点** ⇒
  这**就是** ④ 的 2px 需要的**同一个旋钮**（v142 §4.4 已写的方案），
  且它**上下移动一个 48dp 靶**，在 600 档还要复核是否与队列条相碰 ⇒ 同样是红线。

**⇒ 两者都改 48dp 靶 ⇒ 按 Lead 指示「若改动会动到 48dp 靶或时间线账目，就别改，只写方案让我裁定」。**

**钉成显式声明 + 触发条件**（已写进测试，5 条各占一个方法）：

| 编号 | 结论 | 钉法 | 会先在哪里变红 |
|---|---|---|---|
| **③** | **无害**，1.2–1.5%，最右缘 | `theSixPixelSliverIsOnePointSixPercent…`，钉住 `overlap` 字面量 + **面积占比 ≤ 2%** | 占比越过 2% 即红（那时它才开始值得修） |
| **⑤** | **真缺陷**，残余最宽窄边 **2px** ⇒ 功能不可达 | `theFloatingTerminalIsUnreachable…`，钉住 z 序 `send.index > terminal.index`、遮挡 2068、残余和 == 140、**窄边 ≤ 3px** | 窄边 ≥ 16px 即红，强制重新裁定 |
| **⑤ 的健康侧** | 堆叠形态是唯一没坏的 | `theStackedFormIsTheOnlyFormWhere…`，钉住 0 遮挡 + `terminal.bottom <= send.top` | 堆叠形态若也遮挡，说明塌成一行不再是唯一代价 |

**写明"为何不修"**（逐字）：
> ③ 修它必须**收掉 Material3 的触摸扩张**或**重切带宽分配**，
> 两者都**动 48dp 靶**，且前者是**拿可用性换几何整洁，净值为负**。
> ⑤ 修它必须**移动浮动按钮的垂直锚点**，
> 而那个锚点**正是 ④ 的 2px 要用的同一个旋钮**，移动它**动 48dp 靶**，
> 且 600 档（可用高度仅 600 − 261 = 339px）需复核是否与队列条相碰。
> **⇒ 本轮零实施，方案见 §6.1，等 Lead 裁定。**

---

## 3. ④ 的 2px：记在既有 allowance 框架内

### 3.1 算式（全部读自帧，测试已钉住每一步）

在「条在屏 + 一行」帧 `v137_BARON_M.xml` 上：

```
paneBottom    = 261                     ← 宿主 pane 底沿
toolRowBottom = 249                     ← 工具行**布局**框底沿（h = 21）
expansion     = 48 − 21 = 27            ← **奇数**
expansionUp   = ⌊27/2⌋ = 13
expansionDown = 27 − 13 = 14
semanticBottom= 249 + 14 = 263           ← 与 actions 实际最大底沿 263 一致

headroom      = 261 − 249 = 12           ← 布局框到 pane 底沿的余量

overflow      = expansionDown − headroom
              = 14 − 12
              = 2                       ← 就是那 2px
```

### 3.2 它与 `allowance = 1` 的关系：**同源不同量 ⇒ 不并入 allowance**

| | **allowance = 1**（v142 框架） | **④ 的 2px** |
|---|---|---|
| 问的问题 | dump 侧的**读数**允许落在生产侧的哪一侧、差多远 | 扩张把语义框推出**宿主盒**多远 |
| 公式 | `(innerLayoutBottom − productionUnionBottom) + expansionDown` | `expansionDown − (paneBottom − toolRowBottom)` |
| 方向 | **只能向下**（`dump ≥ production`） | 无方向（是越界量） |
| 上界 | `≤ expansionDown`（不得超过它来源的扩张量） | 无上界概念 |
| 共同来源项 | **`expansionDown`** | **`expansionDown`** |

**⇒ 同源（都出自 `expansionDown`），但是两个不同的量。**
allowance 是**「dump 相对 production 的准入余量」**；
④ 是**「语义框相对宿主盒的容纳余量」**。
把它们并成一笔，等于让 dump 侧可以再往下浮 2px —— **那才是放宽断言**。

### 3.3 **不扩 allowance** 的理由（为什么这是"事实记账"而不是"放宽断言"）

1. **allowance 的上界是 `expansionDown` = 14**。
   若把 2px 并进去，准入区间会变成 `[0, 16]`，**上界被撑破** ⇒ 违反 v142 自己钉的上界。
2. **方向不同**。allowance 有硬方向（只能向下，因为语义 ⊋ 布局 ⇒ `dump ≥ production`）。
   ④ 没有方向，2px 是**固定越界**，拿它当 allowance 等于给双向松弛开口子。
3. **④ 的 2px 可以被完整算式解释**（§3.1，14 − 12），
   **它不是任何人给的容差**。容差是"允许少 1px"，2px 是"扩张 14px 撞上 12px 余量"。
   把前者记成后者，等于**把一个精确的算术事实伪装成一条宽松规则**。
4. **测试侧无法"消掉"它而不越界**。唯一手段是改包含判据 `unionBottom <= paneBottom`
   为 `<= paneBottom + 2` —— **这就是放宽断言，明令禁止**。

**⇒ 结论：④ 按"同一 `expansionDown` 派生出的新事实"记账，挂在既有 allowance 框架之下，
不新开一笔，不扩 allowance。**

### 3.4 附带发现：**2px 里有 1px 是不可约的**

```
若拆分取 ⌊exp/2⌋ = 13/13（偶拆分）：overflow = 13 − 12 = 1
```

**⇒ 即使 material3 改成偶数拆分，仍越界 1px。**
所以 2px 里**只有 1px** 来自奇数余数（v142 的 T1 触发器），
**另 1px 在不动生产的前提下不可约**。这条已钉进测试
（`assertEquals(1, (27 / 2) - headroom)`）——
它把"改 material3 的拆分方向能解决问题"这个**想当然的假设直接否掉**。

---

## 4. 测试证据

### 4.1 新增类

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.UsabilityCellsRulingV143Test"
```

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 3s
52 actionable tasks: 2 executed, 50 up-to-date
```

`tests=5 skipped=0 failures=0 errors=0`，5 条用例名（确认**真跑**、非跳过）：

```
theWidthsAreOffSpecButWiderThanUpstreamWhichHasNoHorizontalSizeRule
theFloatingTerminalIsUnreachableInEveryOneRowFormBecauseTheResidualIsSlivers
theStackedFormIsTheOnlyFormWhereTheFloatingTerminalSurvives
theTwoPixelOverflowIsDerivedFromTheSameExpansionDownAndNeedsNoNewAllowance
theSixPixelSliverIsOnePointSixPercentOfTheEditorsWidthAndSitsAtItsFarEdge
```

### 4.2 受影响全部类 → 0 失败（18 个类）

```
:app:testDebugUnitTest --tests "…UsabilityCellsRulingV143Test" `
  --tests "…ComposerBandA11yReachabilityTest" `
  --tests "…ComposerBandDeviceTruthTest" `
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

BUILD SUCCESSFUL in 44s
52 actionable tasks: 1 executed, 51 up-to-date
```

| 类 | 用例 | 失败 | 错误 |
|---|---|---|---|
| **`UsabilityCellsRulingV143Test`（本轮新增）** | **5** | 0 | 0 |
| `ComposerBandA11yReachabilityTest` | 5 | 0 | 0 |
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
| **合计** | **132**（v142 为 127，**净增 5，0 减少**） | **0** | **0** |

### 4.3 反向验证：**本轮无生产改动，故无注入对象；改为验证测试自身可红**

本轮 `app/src/main/**` **零改动**（`git status --porcelain -- app/src/main` 跑前跑后均 0 行），
所以 v141/v142 那种"注入生产改动 ⇒ 变红"的反向验证**不适用**。
**但本轮真的出了一盏红**，且它证明了这批新断言**不是恒真式**：

**过程红灯 1（`_v143_cells.log` 第一次跑，1 红）**：

```
> Task :app:testDebugUnitTest FAILED

UsabilityCellsRulingV143Test > theFloatingTerminalIsUnreachableInEveryOneRowFormBecauseTheResidualIsSlivers FAILED
    java.lang.AssertionError at UsabilityCellsRulingV143Test.kt:213

5 tests completed, 1 failed

BUILD FAILED in 3s
52 actionable tasks: 2 executed, 50 up-to-date
```

失败消息逐字（从结果 XML 读出）：

```
java.lang.AssertionError: v143 CELL 5 (v140_600off.xml): the widest surviving sliver is 48 px.
The defect is only decidable — and only un-tappable — while that stays at or below a few px.
If a future frame leaves a >=16 px sliver, this assertion goes RED and the verdict must be
re-adjudicated by hand.
```

**这盏红抓到的是我自己新写的 `minus()` 的一个真 bug**：
它把碎片的**宽度**当成了碎片大小，于是把一条 `44×1` 的下侧横条报成"48 px 宽"，
**红灯理由是错的**（几何结论 2px 没错，是度量方式错了）。
修法是改用碎片的**窄边** `min(w, h)`，并把上下两条裁到 `o` 的跨度内以免重复计角：

```kotlin
// 修后（窄边 = min(w,h)）
fun narrowest(o: P): Int {
    val sl = minus(o)
    return if (sl.isEmpty()) minOf(w, h) else sl.maxOf { minOf(it.first, it.second) }
}
```

并**追加**一条断言把分解本身钉死（这加强了约束，不是放松）：

```kotlin
assertEquals("…the disjoint slivers must add up exactly to the uncovered area…",
    terminal.area - covered, residualArea)   // 2208 − 2068 = 140
```

**诚实披露**：这说明 §2.2 的 2px 结论**第一版算错过一次**（是度量错，不是结论错），
由这盏红抓出。**过程红灯保留在 `_v143_cells.log`，未抹。**

### 4.4 为什么不降强度

| 维度 | v142 | **v143** | 方向 |
|---|---|---|---|
| 用例数 | 127 | **132** | ↑（+5） |
| 放宽的断言 | 0 | **0 条** | = |
| 删除的断言 | 0 | **0 条** | = |
| 改动的既有断言 | — | **0 条**（v142 的 5 个类**一个字节未动**） | = |
| **被裁定的项** | 5 项各记"不达标"，未定性归属 | **5 项全部定性与处置**：①达标 / ②**偏离规范·忠实上游**（含依据行号）/ ③**判定无害** / ④**派生·不扩 allowance** / ⑤**真缺陷·可判定** | ↑ |
| **新钉住的上游事实** | 0 | **3 条字面量**（`Add` 28 / `Send` 28 / 工具行 44，逐行号） | ↑ |
| **新引入的可判定判据** | 0 | **3 个**：残余窄边 ≤ 3px、碎片和 == 140、③ 面积占比 ≤ 2% | ↑ |
| **新排除的假设** | — | 「改 material3 奇数拆分能解 2px」**被否**（偶拆分仍越 1px） | ↑ |
| 修掉的 v142 判断 | — | v142 §7-4「⑤ 静态不可判定」**被 z 序 + 残余窄边证伪为可判定** | ↑ |
| 生产代码 | 零改动 | **零改动** | = |

**关于"撤下/修正"的自曝**：本轮**没有撤下任何断言**，也**没有改松任何断言**。
唯一被**修正**的是我自己新写的 `minus()` 辅助函数的**度量方式**（宽度 → 窄边），
修正方向是**更严**（`residualArea` 相等性断言是新加的）。

---

## 5. 未闭环

1. **⑤ 的真机复验仍未做。** 本轮把它从"静态不可判定"推进到"静态可判定"（z 序 + 残余窄边），
   但"**实际点下去是谁收到**"仍未在真机验证 —— 需要 `adb` tap 复验，**本轮未授权装机**。
2. **④ 的 2px 仍未在设备上闭环**，parity 仍欠（沿用 v142 §7-1）。quarantine 保持红是对的。
3. **② 的产品取舍未裁定**：要不要为了满足 Android 48dp 而加宽/加高动作按钮（见 §6.2）。
4. **③/⑤ 的生产修法未裁定**（见 §6.1）——**本轮零实施**。
5. **`Open terminal` 在一行形态下是否本就该被让开**，取决于产品是否认为"开终端"是高频操作；
   本轮**无上游对应物可比**（上游没有这个浮动按钮）⇒ 只能由产品裁定。
6. **T1 的拆分依据仍是"观测吻合"而非源码证明**（沿用 v142 §7-5），本轮未闭环；
   但 §3.4 已证明**即使证明成立也解不掉那 1px**，所以这条的优先级下降。
7. **768 档判据 ② 口径仍未定义**（沿用 v142 §7-6）。
8. **未装机、未 `assembleDebug`、未做真机复验** —— 本轮证据 = JVM 测试 + 归档 dump + 上游源码。

### 5.2 新增触发条件（在 v142 T1–T4 之上叠加，各占一个测试方法）

| 编号 | 触发条件 | 会先在哪里变红 | 后果 |
|---|---|---|---|
| **T5** | ③ 的 6px 细条占编辑器面积**越过 2%** | `theSixPixelSliverIsOnePointSixPercent…` | ③ 从"无害"升级为"值得修"，需重新裁定 |
| **T6** | 任何动作的宽度**不再大于上游的 28px** | `theWidthsAreOffSpecButWiderThanUpstream…` | 「忠实上游」的定性**失效**，② 变成真的上游偏离 |
| **T7** | ⑤ 残余碎片出现**窄边 ≥ 16px** 的可命中区域 | `theFloatingTerminalIsUnreachable…` | 「功能不可达」的结论失效，需人工重裁 |
| **T8** | `Open terminal` 与 `Send`/`Stop` 的 XML 索引次序**反转** | 同 T7（`send.index > terminal.index` 断言） | 遮挡的归因从"`Send` 挡住"变成"只是几何重叠" |
| **T9** | **堆叠**形态也开始出现遮挡 | `theStackedFormIsTheOnlyFormWhere…` | 「塌成一行是唯一代价」的判断失效 |
| **T10** | ④ 的 `headroom`（`paneBottom − toolRowBottom`）变化 | `theTwoPixelOverflowIsDerivedFrom…` | 2px 的算式与结论一起作废，需重推 |

---

## 6. 改了哪些文件 / 方案（未实施）

| 文件 | 性质 | 说明 |
|---|---|---|
| `app/src/test/java/com/xiufeigo/opencodeshell/UsabilityCellsRulingV143Test.kt` | **测试（新增 · 闸门内 · 绿）** | ②③④⑤ 的裁定与 pin：上游 3 条字面量、③ 无害判定、⑤ 真缺陷 + z 序 + 残余窄边、④ 的 2px 算式。**5 例** |
| `research/v97_fix/USABILITY_CELLS_RULING.md` | **报告（新增）** | 本文件（先写盘抢锁 → 定稿释放） |
| `research/v97_fix/_v143_cells.log` | 日志 | 新类 BUILD SUCCESSFUL（**含第一次跑的真实红灯**） |
| `research/v97_fix/_v143_regression.log` | 日志 | 18 类回归，BUILD SUCCESSFUL |

**未触碰**：`app/src/main/**`（**零改动**，`git status --porcelain -- app/src/main` = **0 行**，跑前跑后各核一次）、
`app/build.gradle.kts`、**v142 的 5 个测试类一个字节未动**、quarantine 目录、v139 的 `Log.i` 探针（保留）。

### 6.1 ③⑤ 的最小修方案（**未实施，等裁定**）

> **方案 A（同时解 ⑤ 与 ④，推荐）**：把浮动 `Open terminal` 的垂直锚点
> 从 `bandBottom` 上移 **2px**（`bandBottom − 2`），使它与派生语义框产生 **≥2px** 的稳定分离。
> **代价**（必须一并复核）：
> 1. 它**移动了一个 48dp 靶**（违反本轮红线，故未实施）；
> 2. 600 档可用高度仅 `600 − 261 = 339px`，上移 2px 需复核是否与队列条相碰；
> 3. 它**同时改写 ④ 的算式**（`headroom` 变 14 ⇒ `overflow = 14 − 14 = 0`），
>    届时 ④ 这笔账要**重推**，不是自动清零；
> 4. 需复核 768 档（堆叠形态当前 0% 遮挡，上移后仍须为 0）。
>
> **方案 B（只解 ⑤，不动锚点）**：让 `Open terminal` 在**一行形态**下沉到工具行**下方**
> （与堆叠形态对齐），即**一行形态下也走堆叠形态的几何**。
> **代价**：一行形态的 composer 视觉高度增加，且需重验 ④。
>
> **方案 C（不修，改设计）**：一行形态下**移除/改放**浮动 `Open terminal`，
> 改由顶栏或侧栏进入终端。**代价**：交互路径变化，属产品裁定。

### 6.2 ② 的方案（**未实施，等裁定**）

> **选项 1（推荐，零改动）**：维持 42/48，**记为"偏离 Android 规范、忠实上游"**（本轮已如此记账），
> 因为上游 28/28 证明"窄图标按钮"是上游自己的设计，48dp 是 Android 追加的语义。
> **选项 2**：把 `Add` 显式加宽到 ≥48（`Modifier.size(28.dp)` → 加横向 padding），
> 代价是窄屏 600 档信息密度下降，且**这是为了满足规范而牺牲上游一致性**。
> **选项 3**：把 `Add` 的图标从 `plus` 换成更大命中区的包裹（不改变视觉宽度），
> 即在 42px 的盒内画 28px 的图标 + 7px 死区 —— **本壳已经是这个形状**（实测 42 = 28 + 14）。

**行尾**：全程只用编辑 / 写入工具，未用 `git checkout` / `git restore`（`autocrlf=false`）。
**锁**：`.agent-gradle-lock` 写锁 → **前台**跑完两轮 → 绝对路径字面量释放
（`Remove-Item` 被硬安全策略拦截，**未绕过**，改用顶层 `rm --` 由运行时路由至可恢复删除启动器）。
