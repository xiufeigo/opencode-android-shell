# CELL5_FIX_AND_DECLARATIONS — v144：⑤ 行级归因 + ②③④ 显式声明

状态：**已落地 · 19 类 / 139 例 / 0 失败 · 生产代码零改动 · 锁已释放**
日期：2026-10-04 · 承接 v143（`USABILITY_CELLS_RULING.md`）、v142（`LEDGER_REWRITE_AND_USABILITY.md`）

> 锁约定沿用 `PIN_DEVICE_HARDLINK.md:6-7`（仓库无独立 `*.lock`，**本报告文件即锁**）。
> 抢锁时刻 = 本文件首次写入（写入前 `.agent-gradle-lock` 不存在，`git status -- app/src/main` = 0 行）。
> Gradle 锁：写 `.agent-gradle-lock` → **前台**跑完全部测试 → 用**绝对路径字面量**释放。
> 本轮释放时 `Remove-Item` 再次被本地硬安全策略拦截（输出逐字：
> `Permission denied for bash: Local hard safety policy blocked bash: Remove-Item (PowerShell delete; no Recycle Bin from CLI). Use mavis-trash for recoverable removal; permanent Windows delete commands are not allowed.`），
> **未绕过**该拦截，改用单条顶层 `rm -- "C:\sensorsdata\main\program\opencode-android-shell\.agent-gradle-lock"`，
> 运行时路由至受信任的可恢复删除启动器，输出逐字：
> `mavis-trash: moved to trash: 'C:\sensorsdata\main\program\opencode-android-shell\.agent-gradle-lock'`
>
> 授权与纪律：只跑 `:app:testDebugUnitTest --tests "<类名>"`；未 `assembleDebug` / `clean` / 全量 / 装机；
> 未杀进程；未新增用户可见字符串；用例数**只增不减**；未用 `git checkout` / `git restore` 改行尾（`autocrlf=false`）。
> **生产代码零净改动**（反向验证的注入已逐字撤回，`git status --porcelain -- app/src/main` = **0 行**）。

---

## 0. 一页结论

| 问题 | 答案 |
|---|---|
| **⑤ 的 z 序错在哪一行** | `SessionScreen.kt:3187`（viewport Box **先**合成）vs `:3188-3208`（composer host Box **后**合成）⇒ **composer 在上**。KDoc `:3166-3173` 声称二者"never share a pixel"——**该前提被真机证伪** |
| **那 2px 窄边在哪一行** | 来自 `SessionTerminalEntryPolicy.END_DP = 8.dp`（`SessionScreen.kt:3615`）与 composer 自身右内缩**互不派生**。chip 触摸盒被屏幕右缘裁成 **46** 宽（`1320..1366`），`Send` 止于 **1364** ⇒ `1366 − 1364 = 2` |
| **⑤ 改了吗** | **没改**。已证明**任何** z 序单点改动都无解 ⇒ 唯一修法必须移动 48dp 靶 ⇒ 触发 Lead 停手条件，只交方案 + 裁定问题 |
| ②③④ 声明 | **3 条，全部带上游行号，且每次运行都从 `_upstream/` 现读** |
| 断言被放宽/删除 | **0 条**。新增 7 条，全绿 |
| 用例数 | 132（v143）→ **139**，**净增 7，0 减少** |
| 测试 | 19 类全跑，`BUILD SUCCESSFUL in 47s`，**139 例 / 0 失败** |

---

## 1. ⑤ 的行级归因（`文件:行号` + 原文 + 算式）

### 1.1 读数（`research/v97_verify/5554_md/artifacts/v140_600off.xml`，逐字实读）

```
idx=46   desc=Open terminal  bounds=[1320,192][1366,240]     46×48 = 2208 px²
idx=49   desc=Add            bounds=[399,191][441,239]      42×48
idx=54   desc=Send           bounds=[1316,191][1364,239]     48×48 = 2304 px²
```

### 1.2 z 序错在哪：**谁盖了谁**

**`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt:3185-3209`**（`SessionPaneArrangement` 的 `Layout`）：

```kotlin
Layout(
    content = {
        Box(Modifier.fillMaxSize().onSizeChanged(onViewportSize)) { viewport() }   // ← :3187 先合成
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier.fillMaxWidth()....let { ... }                      // ← :3188-3205
        ) {
            composer()                                                             // ← :3207
        }
    },
```

浮动 `Open terminal` 是 `viewport()` 的孩子（`:2254-2265`，`align(Alignment.TopEnd)`）；
composer 是**第二个** `Box` 的孩子。
**Compose 里后合成的兄弟节点绘制在上**，`uiautomator` 也按 view 顺序输出兄弟节点
⇒ **`Send` 盖住 `Open terminal`**，点击由 `Send` 收到（`idx 54 > idx 46`，已钉成断言）。

**同一函数的 KDoc `SessionScreen.kt:3166-3173` 把一个被证伪的前提写成了事实**：

> "the composer's host is now drawn *after* the whole viewport, so the wide-screen terminal
> entry chip … is under it rather than over it. **The two never share a pixel** — the chip
> sits in the top ~42 dp and the composer's ceiling keeps the top `TIMELINE_MIN_CONTENT`
> (64 dp) clear in every arrangement"

**"never share a pixel" 是假的**：chip 在 y `192..240`，composer 带在 y `191..239` ——
它们重叠 44×47。**⑤ 的根因就是这个 KDoc 声称的隔离并不成立。**

### 1.3 那残余 2px 窄边在哪：**不是取整产物，是两个独立锚点的横向分歧**

chip 的触摸盒**宽 46 而不是 48**：

```
28.dp 本体 + material3 minimumInteractiveComponentSize(48) = 48  →  被屏幕右缘裁掉 2
chip:   1320 .. 1366   (46 px)
Send:   1316 .. 1364   (48 px)
```

| 残余碎片 | 算式 | 尺寸 | 窄边 |
|---|---|---|---|
| **右侧竖条** | `terminal.right − Send.right` = `1366 − 1364` | **2×48** | **2** |
| 下侧横条 | `terminal.bottom − Send.bottom` = `240 − 239` | 44×1 | 1 |
| 合计 | `2208 − 2068` | **140 px²** | — |

`2 + 96 = …` 校验：`2×48 = 96`，`44×1 = 44`，`96 + 44 = 140` ✔ = `2208 − 2068` ✔

**这 2px 的来源是两个互不派生的锚点**：

| 锚点 | 位置 | 值 |
|---|---|---|
| chip 的水平锚点 | `SessionScreen.kt:3615` | `SessionTerminalEntryPolicy.END_DP: Dp = 8.dp` |
| composer 的右内缩 | `Composer.kt:2189` `Modifier.fillMaxWidth().height(BAND_HEIGHT)` 带内布局 | 由 band 自身内缩决定 |

**没有一个从另一个导出** ⇒ 帧上二者必然分歧 2px。
（`END_DP = 8.dp` 在 `:3615`，`TOP_DP = 88.dp` 在 `:3612`，均已作为**生产常量**在测试里现读。）

### 1.4 关键推论：**z 序单点改动无解**（这是不修的正式理由）

chip `46×48` 与 `Send` `48×48` **占同一个矩形**，且**同为 48dp 高**（material3 地板所致，无法靠"压缩矮的那个"分开）。

把 chip 画在上面（"便宜的修法"）会怎样？**把同一个缺陷转交给 `Send`——而 `Send` 是主操作**：

```
Send = 48×48 = 2304 px²
Send − chip 残余 = 左侧竖条 (1316..1320) 4×48 = 192  +  下侧 44×1 = 44  = 236 px²
Send 残余最宽窄边 = max(min(4,48), min(44,1)) = 4 px      ← 比 chip 的 2px 更糟
```

**⇒ 两个 48dp 靶无法共存于一个矩形；任何"只改 z 序"的方案都只是把死亡转移给主操作。**
该结论已写成**可执行断言**（`theOcclusionIsStillOpenBecauseNoZOrderOnlyFixExists`），
不是散文，所以"便宜的修法"无法被误采纳。

### 1.5 停手判定

| 修法 | 是否可行 | 撞哪条红线 |
|---|---|---|
| 改 z 序（chip 置顶） | ❌ 无效 | 把 2px 缺陷转成 `Send` 的 4px 缺陷（§1.4） |
| 改 `TOP_DP`（垂直锚点） | ⚠️ 有效但**越权** | 移动一个 48dp 靶；**且它是 ④ 的 2px 要用的同一个旋钮** |
| 改 `END_DP`（水平锚点） | ⚠️ 有效但**越权** | 移动一个 48dp 靶 |
| 给 chip 单独留一条泳道 | ⚠️ 有效但**越权** | 动时间线账目（`TIMELINE_MIN_CONTENT` 64dp 的保留区） |
| 缩小任一靶 | ❌ | 直接删掉 48dp 靶语义（最严重的放宽） |

**⇒ 四个"有效"方案全部移动 48dp 靶或动时间线账目 ⇒ 命中 Lead 指示的停手条件
「若修它必须动 48dp 靶语义或时间线账目 ⇒ 停手，只写方案 + 我的裁定问题，不要实施」。**
**本轮零实施。**

### 1.6 写权限的独立阻碍（即使红线不成立也改不了）

| 要改的东西 | 所在文件 | 是否在授权写范围 |
|---|---|---|
| chip 的**组件本体**（`.size(28.dp)` + `.clickable`） | `ui/terminal/TerminalPanel.kt:2030-2057` | ❌ `ui/terminal/**` 明令不许碰 |
| chip 的**摆位**（`align(TopEnd)` + 两个锚点） | `ui/session/SessionScreen.kt:2255-2264, 3609-3616` | ⚠️ 在 `ui/session/**` 内，但**不属"composer/工具行相关"** |
| composer 的 band 宽度分配 | `ui/session/composer/Composer.kt:2319` `band = minOf(BAND_HEIGHT.roundToPx(), constraints.maxHeight)` | ✅ 在范围内，但属**动 48dp 靶** |

**⇒ 三条路都要求越权。本轮按红线停手，并把方案写完整。**

---

## 2. 交 Lead 的裁定问题（只问一个）

> **⑤ 的四个"有效"方案全部要移动 48dp 靶或动时间线账目。**
> 请裁定走哪一条（我未实施任何一条）：
>
> | 选项 | 做法 | 代价 | 我的推荐 |
> |---|---|---|---|
> | **A** | `END_DP: 8.dp → 56.dp`（把 chip 水平移出 `Send` 的 x 跨度） | 移动 chip 的 48dp 靶；chip 左移到转录区上方，视觉上不再是"角标" | ✅ **推荐**：单点、只碰一个锚点、不动 band、不动账目 |
> | **B** | `TOP_DP` 上移，让 chip 整体高过 composer | **就是 ④ 的 2px 要用的同一个旋钮**，且 600 档可用高仅 `600 − 261 = 339px`，需复核与队列条相碰 | ❌ 一石二鸟但连带 ④ 需重推 |
> | **C** | 一行形态不给 chip 独立泳道（走方案 C：改由顶栏/侧栏进入终端） | 交互路径变化，属产品裁定 | 兜底 |
> | **D** | 接受 ⑤ 为已知缺陷，仅保 pin 守门 | 一行形态下"开终端"功能性死亡 | 若产品认为它低频，可接受 |
>
> **补充给 A 的风险**：chip 移到转录区上方后会压住消息正文；需复核 768 档（堆叠形态当前 0% 遮挡，移位后须仍为 0），
> 且需复核 docked review panel 宽度下的 `v43` 约束（`SessionScreen.kt:266-273`：chip 属 timeline pane 以避开 docked panel）。

---

## 3. 补的 pin：把"遮挡必须被消除"钉成可判定形式

`UsabilityCellsV144FixTest` 把 ⑤ 的验收标准写成**可判定函数**，而不是散文：

```kotlin
private fun occlusionCriterion(residualNarrowPx: Int): Boolean = residualNarrowPx >= TAPPABLE_PX  // 16
```

- **阈值 16px**，远低于成人指尖接触斑（8–10mm ≈ 160dpi 下 40–50px），
  目的是让**任何"非零就过"的窄边都通不过**。
- **双向可判定**（`theFixAcceptanceCriterionIsDecidableInBothDirections`）：
  46px（无遮挡）⇒ 通过；2px ⇒ 拒绝；15px ⇒ 拒绝。**证明判据不是恒真式。**
- **当前状态是"仍开放"**（`theOcclusionIsStillOpenBecauseNoZOrderOnlyFixExists`）：
  该测试是**绿的**，但它断言的是**缺陷仍在且仍不可免修**——即 `assertFalse(reachable(send))`。
  这是"钉住修法"的诚实形式：验收标准已写下、每次运行都求值，**今天的求值结果是"不达标"**。
  谁实施了获批的修法并翻转 `occlusionCriterion`，本测试会因 `assertFalse` 失败而**强制重新裁定**。

**保留 v142/v143 真机读数常量作为回归触发器**（全部钉住，一改即红）：

| 常量 | 值 | 触发含义 |
|---|---|---|
| `covered` | `44 × 47 = 2068` | 遮挡面积变了 ⇒ 归因变了 |
| `residual` | `[(2,48), (44,1)]` | 碎片**形状**变了 |
| `residualArea` | `140` | 与 `2208 − 2068` 相等性 |
| `terminal.w` | `46`（**不是 48**） | 边缘裁剪消失 ⇒ 两个锚点开始一致 |
| `send.index > terminal.index` | `54 > 46` | z 序反转（T8） |
| 堆叠形态 `v140_768` | 遮挡 `0` | 堆叠形态也开始遮挡（T9） |
| `TIMELINE_MIN_CONTENT` | `64f`（生产常量现读） | 预留区变化 ⇒ `:3166-3173` 的前提需重审 |

---

## 4. 反向验证：**真跑抄原文**

**注入的改动**（模拟"有人未经裁定就把锚点挪了"）：

```diff
--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt
@@ -3612,7 +3612,7 @@ internal object SessionTerminalEntryPolicy {
     /** Inset from the timeline pane's trailing edge. */
-    val END_DP: Dp = 8.dp
+    val END_DP: Dp = 14.dp
 }
```

**结果（`research/v97_fix/_v144_reverse.log`）**：

```
> Task :app:testDebugUnitTest FAILED

BUILD FAILED in 6s
52 actionable tasks: 4 executed, 48 up-to-date
```

**失败消息逐字**（从结果 XML 读出）：

```
tests=7 failures=1
=== theProductionAnchorsBehindCells2Through5AreReadLiveAndAreWhatTheReportQuotes
java.lang.AssertionError: v144: `SessionTerminalEntryPolicy.END_DP` (`SessionScreen.kt:3615`) is the chip's horizontal anchor — the one that disagrees with the composer's end inset by the **2 px** cell 5's residual is made of. It is INDEPENDENT of the composer's own padding; neither is derived from the other, which is why the frames disagree. expected:<8.0> but was:<14.0>
```

**⇒ pin 是硬接在生产常量上的，不是抄进测试的字面量；改动一落地就变红。**

**撤回**（逐字改回 `8.dp`，用编辑工具，**未用 `git checkout` / `git restore`**），
撤回后复核：

```
$ git status --porcelain -- app/src/main
lines=0
```

---

## 5. ②③④ 的显式声明（**全部带上游行号，且每次运行现读上游**）

声明不写死"上游是 28"，而是**每次运行去 `_upstream/` 读那几行**——
上游一旦 rebase 改尺寸，本轮声明**当场失效变红**，不会静默地继续成立。

### 5.1 DECLARATION ② — 偏离 Android 规范，但**忠实上游** ⇒ **不得据此开缺陷单**

| 位置 | 本轮运行读到���原文 | 推出 |
|---|---|---|
| `_upstream/packages/app/src/composer/editor/editor.tsx:250` | `<div class="flex h-11 items-center px-2">` | 工具行 = **44**，不是 48 |
| `_upstream/packages/app/src/composer/editor/editor.tsx:889` | `class="size-7 rounded-md p-[6px]"` | `Send` = `size-7` = **28×28** |
| `_upstream/packages/app/src/composer/editor/editor.tsx:644` | `size="large"` | `Add` 取 `large` 档 |
| `_upstream/packages/ui/src/actions/icon-button/icon-button.css:41-43` | `[data-component="icon-button-v2"][data-size="large"] {` / `  width: 28px;` / `  height: 28px;` | `large` = **28×28** |
| `Composer.kt:2715` | `private val TOOL_ROW_HEIGHT = 44.dp` | 本壳逐字复制 `h-11` |

本壳现值（`v140_600off.xml` 实测，已钉住）：**`Add` = 42**（28 + 左右各 7px 触摸扩张）、
**`Send` = 48**。

> **声明**：本壳在**每个**动作上都**比上游更宽**（42 > 28、48 > 28）。
> ⇒ ② **既不是上游回归，也不是任何一轮的回归**，它是 Material3 `minimumInteractiveComponentSize`
> 落到 Android 上的必然形状。**不得据 ② 开缺陷单。**
> **触发条件**：任何动作的宽度**不再大于 28** ⇒ "忠实上游"这半句失效，声明作废、需重裁。

### 5.2 DECLARATION ③ — 6px 压编辑器**判无害**

三条**可判定**判据（全部钉住，任一失效即需重裁）：

| # | 判据 | 实测 |
|---|---|---|
| 1 | 是**竖条**，不是横贯编辑器的带 | `editor.right − add.left` = **6** px |
| 2 | 面积占比 **≤ 2%** | 1.2%–1.5%（6×47=282 / 381×48=18288；6×38=228） |
| 3 | 编辑器保留 **≥ 375px** 无争议靶 | `381 − 6` = **375** |

> **声明**：手指要编辑器，往左挪 6px 即可 ⇒ **够不着 = 否**；
> 落点仍是编辑器自己的靶（光标区）⇒ **破坏性误触 = 否**。
> **该 6px 不构成可达性缺陷。**
> 且它**是 28→42 触摸扩张的产物**，消掉它 = **把靶改小**换几何整洁，**净值为负**。

### 5.3 DECLARATION ④ — 那 2px **与 allowance 同源**，`14 − 12 = 2`，**不新开一笔**

```
expansionDown  = 48 − toolRow.h(21) = 27，⌊27/2⌋ = 13 上 / 14 下
headroom       = paneBottom(261) − toolRowBottom(249) = 12
overflow       = expansionDown − headroom = 14 − 12 = 2
```

**同源关系被机器验证**（不是散文）：

- allowance = `Δ + expansionDown`（v142），`Δ = innerLayoutBottom − productionUnionBottom`
- v142 另钉上界 `allowance ≤ expansionDown` ⇒ `Δ ≤ 0`；dump 侧方向 `Δ ≥ 0` ⇒ **Δ = 0**
- ⇒ **allowance 的全部可取集合 = `{14}`**（测试里扫 `Δ ∈ [0,14]` 再按上界过滤得出，不是手写）
- **④ 的 2px ∉ {14}**；要让 allowance = 2 需 `Δ = −12 < 0` = **读数跑到生产之上**，
  正是 allowance 明令禁止的方向。

> **声明**：同源（都出自 `expansionDown`）但**不同量**。
> allowance 问「dump 读数允许落在生产哪一侧、差多远」；④ 问「扩张把语义框推出宿主盒多远」。
> **④ 记在既有 allowance 框架之下，不新开一笔、不扩 allowance**
> （并进去会把上界从 14 撑到 16 = **放宽断言**，明令禁止）。
> **附带**：偶拆分也解不掉——`13 − 12 = 1` ⇒ 2px 里 1px 不可约。

---

## 6. diff（生产代码：**零净改动**）

```
$ git status --porcelain -- app/src/main
lines=0
```

**本轮唯一新增文件**：

| 文件 | 性质 |
|---|---|
| `app/src/test/java/com/xiufeigo/opencodeshell/UsabilityCellsV144FixTest.kt` | **测试（新增 · 闸门内 · 绿）** · **7 例** |
| `research/v97_fix/CELL5_FIX_AND_DECLARATIONS.md` | 报告（本文件，先写盘抢锁 → 定稿释放） |
| `research/v97_fix/_v144_cells.log` | 日志：新类 BUILD SUCCESSFUL |
| `research/v97_fix/_v144_reverse.log` | 日志：反向验证 BUILD FAILED（真红灯） |
| `research/v97_fix/_v144_regression.log` | 日志：19 类回归 BUILD SUCCESSFUL |

**未触碰**：`_upstream/**`（只读）、`app/build.gradle.kts`、`ChatViewModel.kt`、
`ui/servers|home|terminal|nav/**`、`ui/App.kt`、`OcActivity.kt`、v142/v143 的 5 个既有测试类
（**一个字节未动**）、`quarantine/`（分工不变）。

**测试侧 diff 摘要**（新增类内部）：

- `F` 解析器与 `P.minus/narrowest` **从 v143 原样复用**（含其 `min(w,h)` 窄边修正）
- 6 条新断言方法 + 1 条 ⑤ 开放性断言
- 生产常量现读：`SessionTerminalEntryPolicy.TOP_DP` / `END_DP`、`SessionPaneLayout.TIMELINE_MIN_CONTENT`
- 上游文件现读：`upstreamLine(rel, line)` / `upstreamLines(rel, from, to)`

---

## 7. 为什么不降强度

| 维度 | v143 | **v144** | 方向 |
|---|---|---|---|
| 用例数 | 132 | **139** | ↑（+7） |
| **放宽的断言** | 0 | **0 条** | = |
| **删除的断言** | 0 | **0 条** | = |
| 改动的既有断言 | — | **0 条**（v142/v143 的 5 个类一个字节未动） | = |
| 断言被移除/替换 | — | **0 条** | = |
| ⑤ 的定性 | 真缺陷，但"只有散文" | **真缺陷 + 定到行 + 验收判据可判定 + 修法无解性已证** | ↑ |
| ②③④ 的载体 | 散文 + 字面量 pin | **可执行声明**，每次运行**现读 `_upstream/` 与生产常量** | ↑ |
| 新排除的假设 | — | 「只改 z 序就能修 ⑤」**被证伪**（缺陷转移给 `Send`，窄边 2→4px） | ↑ |
| 生产代码 | 零改动 | **零净改动**（注入已逐字撤回） | = |

**自曝**：本轮**没有撤下、没有删除、没有改松任何断言**。
过程中真出过 **2 次编译/运行红**：第 1 次是**编译红**（0 用例执行），
第 2、3 次是**断言红**（共 **4 条断言**失败），全部是**我自己新写的测试的 bug**，已逐条记录：

| # | 轮次 | 失败 | 是什么 | 处理 |
|---|---|---|---|---|
| 1 | 编译红 | `compileDebugUnitTestKotlin FAILED`（3 个 error） | `FRAME_600_OFF` 常量名写错（实际叫 `FRAME_BAR_OFF`）、`F.file` 字段漏声明、对 **lambda** 误用具名实参 | 机械修正 |
| 2 | 断言红 | `decl4` / `decl2` / `decl3`（3 条） | ① `decl4` 的同源论证写错：`allowanceFormula(260,246)` 实为 **28** 而非 14；② `decl2` 期望值带前导空格而 `upstreamLine` 已 `trim()`；③ `decl3` 窄边算成 `add.left − editor.left`（**375**）而非 `editor.right − add.left`（**6**） | ① 重写为"可取集合 = {14}"的机器验证 ② 改期望值 ③ 改度量 |
| 3 | 断言红 | `decl2`（1 条） | 同 ② 的残留 | 改期望值 |

**第 1、2 条的 ①③ 尤其重要**：它们是**几何算错**（一处把 allowance 算成 28、一处把 6px 算成 375px），
被自己的测试当场抓住，没有流到报告里。
（v143 也有同类自曝：§4.3 的 `minus()` 宽度/窄边度量错误。）

---

## 8. 测试证据

### 8.1 新类

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.UsabilityCellsV144FixTest"
```

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 3s
52 actionable tasks: 2 executed, 50 up-to-date
```

`tests=7 failures=0 errors=0 skipped=0`（**非跳过**），7 条用例名：

```
declaration4TheTwoPixelOverflowSharesItsSourceTermWithTheAllowanceAndOpensNoNewLine
theOcclusionIsStillOpenBecauseNoZOrderOnlyFixExists
declaration2IsOffSpecButWiderThanUpstreamWhichHasNoHorizontalSizeRuleSoDoNotFileIt
cell5IsTwoCoincident48DpTouchBoxesAndTheResidualSliverIsTheTwoPixelGapBetweenTheTwoAnchors
declaration3TheSixPixelSliverIsNotAReachabilityDefectAndHereAreTheCriteriaThatSaySo
theProductionAnchorsBehindCells2Through5AreReadLiveAndAreWhatTheReportQuotes
theFixAcceptanceCriterionIsDecidableInBothDirections
```

### 8.2 受影响全部类 → **0 失败**（19 个类）

```
.\gradlew.bat :app:testDebugUnitTest --tests "…UsabilityCellsV144FixTest" `
  --tests "…UsabilityCellsRulingV143Test" `
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

BUILD SUCCESSFUL in 47s
52 actionable tasks: 4 executed, 48 up-to-date
```

| 类 | 用例 | 失败 | 错误 |
|---|---|---|---|
| **`UsabilityCellsV144FixTest`（本轮新增）** | **7** | 0 | 0 |
| `UsabilityCellsRulingV143Test` | 5 | 0 | 0 |
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
| **合计** | **139**（v143 为 132，**净增 7，0 减少**） | **0** | **0** |

**`quarantine/` 分工不变**（沿用 v142/v143：④ 的 2px parity 仍欠，quarantine 保持红是对的）。

---

## 9. 真机复验步骤（本轮**未授权装机**，故未执行）

### 9.1 ⑤ 是否达标

1. 装机后进会话页，触发**一行形态**（窄档 / 键盘弹起 / 600 档），`adb shell uiautomator dump`。
2. 抽 `Open terminal` 与 `Send`/`Stop` 两个节点，**必须核三条**：
   - `terminal.width == 48`（**不再是 46** ⇒ 边缘裁剪消失，两个锚点已一致）
   - `terminal.bounds()` 与 `${send}.bounds()` **x 跨度不相交**（`overlap == 0`）
   - `send.index > terminal.index` 仍成立（z 序没被"修"成对主操作不利的方向）
3. tap `Open terminal` 中心，**确认真的开终端**（这是 v143 起就欠的真机项，**至今仍未做**）。
4. 反向：tap `Send` 中心，确认**没有被 chip 抢走**（防"z 序翻转"式假修）。
5. 堆叠形态（768 档）复跑一次，`overlap == 0` 必须仍成立（防修 ⑤ 时把堆叠形态弄坏）。

### 9.2 基线不回退

重跑 `ComposerBandDeviceTruthTest` 等基线类，确认以下读数**不回退**：

| 基线读数 | 期望 |
|---|---|
| ④ 残余 | `140`（`2×48 + 44×1`） |
| ⑤ 遮挡 | `2068`（`44×47`）——**注意：这是修复前的值；修复后此项应当归零，那才是达标** |
| ③ 重叠 | `282`（6×47，bar-ON）/ `228`（6×38） |
| `1366x600+IME` 并集 | `−10`（`union 169..218`（49）− host 44 = +5 等式仍成立） |
| `1366x768+IME` 并集 | `−12`（`union 208..296`（88）− host 94 = −6 等式仍成立） |

> **口径说明**：`140 / 92 / 51 / −10 / −12` 是 Lead 给的基线清单。
> 本轮从帧里**实测到**的是 `2068 / 140 / 282 / 228`（见 §1.1、§5.2、§5.3）；
> `92 / 51 / −10 / −12` 属 v142–v143 归档基线，**本轮未重算、未复核**，
> 它们由 §8.2 的 12 个 composer 基线类（合计 108 例）**全部为绿**间接守住。
> 这四个数字的真机复验留到 §9.1 的装机流程一并做。

---

## 10. 未闭环

1. **⑤ 仍未修**（本轮零实施）——四个"有效"方案全部移动 48dp 靶或动时间线账目，**等 Lead 裁定 §2 的 A/B/C/D**。
2. **写权限独立阻碍**：即便裁定通过，chip 组件在 `ui/terminal/TerminalPanel.kt`（禁改）、
   摆位在 `ui/session/SessionScreen.kt`（不属 composer/工具行），**都需要追加授权**。
3. **⑤ 的真机 tap 复验仍未做**（v143 起就欠）——`adb tap` 确认"实际点下去是谁收到"，本轮未授权装机。
4. **④ 的 2px 仍未在设备上闭环**，parity 仍欠；quarantine 保持红是对的。
5. **② 的产品取舍未裁定**：要不要为满足 Android 48dp 而加宽动作按钮（本轮维持 42/48，声明为不得开单）。
6. **`SessionScreen.kt:3166-3173` 的假前提仍在源码里**——"never share a pixel" 已被真机证伪。
   本轮未改那处 KDoc（它不属 composer/工具行），**但已在 pin 里把 `TIMELINE_MIN_CONTENT` 现读钉住**；
   建议下一轮随 ⑤ 的裁定一并订正该注释。
7. **`92 / 51 / −10 / −12` 四个基线本轮未重算**（见 §9.2 口径说明）。
8. **768 档判据 ② 口径仍未定义**（沿用 v142 §7-6）。
9. **未装机、未 `assembleDebug`、未做真机复验** —— 本轮证据 = JVM 测试 + 归档 dump + 上游源码现读 + 生产常量现读。

**行尾**：全程只用编辑 / 写入工具，未用 `git checkout` / `git restore`（`autocrlf=false`）。
**锁**：`.agent-gradle-lock` 写锁 → **前台**跑完两轮 → 绝对路径字面量释放
（`Remove-Item` 被硬安全策略拦截，**未绕过**，改用顶层 `rm --` 由运行时路由至可恢复删除启动器）。
