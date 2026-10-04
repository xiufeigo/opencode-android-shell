# v149 — androidx 那层的源码证据（已补齐） + 锚点耦合改造的裁定

> 轮次：v149（修复工程师）。前置：v148 的 `PRECISION_T1_AND_768B.md` §6.1 第 1 条
> 把"androidx 内部如何分配撑开量"显式声明为**仓外边界**，并注明"需授权拉取 sources artifact"。
> 本轮拿到了那份授权，**边界已解除**——并且**原文推翻了 v148 的一处措辞**。
>
> **写盘：本报告先于任何结论落盘。** 生产代码本轮**未动一个字节**（任务 B 只写方案）。
> 授权范围：只读拉取 sources artifact 到 `research/v97_fix/_androidx_src/`；写 `research/**`。

---

## 0. 一页结论

| # | 问题 | 裁定 |
|---|---|---|
| A1 | 48dp 靶到底由谁撑开 | **不是 `androidx.compose.foundation`**，是 **`androidx.compose.material3`** 的 `MinimumInteractiveModifierNode`（`InteractiveComponentSize.kt`） |
| A2 | 官方扩张是**居中对称**吗 | **是**，`(width - placeable.width) / 2f` 再 `roundToInt()`，**两侧对称** |
| A3 | 奇数余数落在哪一侧 | **落在尾侧**（`roundToInt` 半值进位 ⇒ 起点多 1px），**不是**对称各半 |
| A4 | 与本仓 `:1371`「向零截断⇒余数落底沿」是否一致 | **不一致 —— 方向相反**。见 §2.3，这是本轮最关键的一条 |
| B1 | 官方是派生还是独立锚点 | **独立锚点**（`screen.tsx:340` `absolute end-3 top-0`，与 composer 零引用） |
| B2 | 裁定 | **终点即"显式声明与上游一致，不改"**（第一档），并附反向 pin |
| B3 | `END_DP 64` 该绑哪个量 | **该绑 `Send` 的实测 trailing inset**（算式见 §4.3），**不是** `TOOL_ROW_HEIGHT`/`band` |
| C | 回归 | 本轮**零测试改动**，`UsabilityCellsV146SendOcclusionFixTest` 仍绿（§5） |

---

## 1. 任务 A — androidx 那层的源码证据

### 1.1 拉取结果（成功）

Lead 授权的只读拉取**成功**，三个 artifact 全部拿到：

| Artifact | 版本依据 | 来源 URL | 本地路径 | 字节 |
|---|---|---|---|---|
| `androidx.compose.material3:material3` | **`1.3.1`**（依赖解析实测） | `https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/1.3.1/material3-1.3.1-sources.jar` | `_androidx_src/material3-1.3.1-sources.jar` | 452 882 |
| `androidx.compose.foundation:foundation` | `1.7.6` | `https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation/1.7.6/foundation-1.7.6-sources.jar` | `_androidx_src/foundation-1.7.6-sources.jar` | 774 176 |
| `androidx.compose.foundation:foundation-layout` | `1.7.6` | `https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-layout/1.7.6/foundation-layout-1.7.6-sources.jar` | `_androidx_src/foundation-layout-1.7.6-sources.jar` | 83 210 |

三个 jar 均已**解压到同名 `-sources/` 目录**，可直接按 `文件:行号` 复核。

**版本取的是本仓实际使用版本，不是猜的**：

* `app/build.gradle.kts:147` 声明 `platform("androidx.compose:compose-bom:2024.12.01")`；
* `app/build.gradle.kts:152` 只写 `implementation("androidx.compose.material3:material3")`，版本由 BOM 决定；
* 实际解析结果取自 `research/v97_fix/deps_debugRuntimeClasspath.log`：
  `androidx.compose.material3:material3:1.3.1 (c)` / `-> 1.3.1`、
  `androidx.compose.foundation:foundation:1.7.6`。
* ⚠️ 缓存里同时存在 `1.10.2`（来自**未被本仓引用**的 `compose-bom:2026.01.01`）与 `1.7.6`。
  **本轮用的是 1.7.6 / 1.3.1**，因为那才是 `debugRuntimeClasspath` 里解析出来的那一档。

### 1.2 **订正：撑开靶的不是 foundation，是 material3**

这是本轮第一处必须订正 v148 措辞的地方。

v148 §6.1 写的是"真正把 48dp 靶撑开的那段代码在 `androidx.compose.foundation` 里"。
**这句话的包名是错的。** 实证：

1. `foundation-1.7.6-sources` 与 `foundation-layout-1.7.6-sources` 全树 grep
   `minimumInteractiveComponentSize` / `MinimumInteractiveModifier` / `InteractiveComponentSize`
   —— **0 命中**；
2. 对应 AAR 的 `classes.jar`（`foundation-release.aar` 2167 条、`foundation-layout-release.aar` 397 条）
   按类名与常量池字节各扫一遍 —— **0 命中**；
3. `material3-1.3.1-sources` —— **8 处命中**（`Checkbox.kt` / `IconButton.kt` /
   `RadioButton.kt` / `Slider.kt` / `Surface.kt` / `Switch.kt` / `TimePicker.kt` /
   `InteractiveComponentSize.kt`），而 `InteractiveComponentSize.kt` 是**唯一定义处**。

本仓代码其实**早就写对了**：`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt:32`
import 的是 `androidx.compose.material3.LocalMinimumInteractiveComponentSize` —— 是 **material3**。
只有 v148 报告的行文把它记成了 foundation。**结论方向不变（48dp 确由 material3 强制），归属层订正。**

### 1.3 源码原文（逐字，带行号）

文件：`research/v97_fix/_androidx_src/material3-1.3.1-sources/commonMain/androidx/compose/material3/InteractiveComponentSize.kt`

**（a）地板值 48dp —— `:139-142`**

```kotlin
139  val LocalMinimumInteractiveComponentSize: ProvidableCompositionLocal<Dp> =
140      staticCompositionLocalOf {
141          48.dp
142      }
```

**（b）如何把框扩到 48dp —— `:87-104`**

```kotlin
 87          val width =
 88              if (enforcement) {
 89                  maxOf(placeable.width, sizePx)
 90              } else {
 91                  placeable.width
 92              }
 93          val height =
 94              if (enforcement) {
 95                  maxOf(placeable.height, sizePx)
 96              } else {
 97                  placeable.height
 98              }
 99
100          return layout(width, height) {
101              val centerX = ((width - placeable.width) / 2f).roundToInt()
102              val centerY = ((height - placeable.height) / 2f).roundToInt()
103              placeable.place(centerX, centerY)
104          }
```

配套的三处（`sizePx` 与 `enforcement` 的门槛）：

```kotlin
 81          val size = currentValueOf(LocalMinimumInteractiveComponentSize).coerceAtLeast(0.dp)
 82          val placeable = measurable.measure(constraints)
 83          val enforcement = isAttached && (size.isSpecified && size > 0.dp)
 85          val sizePx = if (size.isSpecified) size.roundToPx() else 0
```

入口与元素：`:53` `fun Modifier.minimumInteractiveComponentSize(): Modifier = this then MinimumInteractiveModifier`；
节点类 `:75-76` `internal class MinimumInteractiveModifierNode : Modifier.Node(), CompositionLocalConsumerModifierNode, LayoutModifierNode`。

### 1.4 扩张方向的证明

**先答三个子问：**

1. **如何扩张到 48dp** —— 不是"加 padding"，而是**在 `measure` 里把布局盒撑大**：
   `width = maxOf(placeable.width, sizePx)`、`height = maxOf(placeable.height, sizePx)`，
   `sizePx = 48.dp.roundToPx()`。只在**不足**的一维放大（`maxOf`），已达标的一维**一像素不动**。
   `sizePx` 由 `roundToPx()` 从 `Dp` 换算 ⇒ **以 px 精度结算**。

2. **是否居中对称** —— **是**。`:101-102` 把**多出来的量除以 2**：
   `centerX = ((width - placeable.width) / 2f).roundToInt()`，
   同一个 `centerX` 作为**起点偏移**放置，两侧补白都来自 `width - placeable.width`
   ⇒ **几何上就是居中对称**。
   ⚠️ 但它是 `/2f`（**float 除法**）**再** `roundToInt()`——**两段式**，
   这正是与本仓截断式差异的来源（§1.5）。

3. **奇数余数落在哪一侧** —— `placeable.place(centerX, centerY)`（`:103`）用**同一个** `centerX`
   作为**起点（左/上）偏移**放置，于是尾侧余数 = `e − centerX`，`e = width − placeable.width`。
   令 `e` 为奇数（`e = 5`）：
   * `centerX = (5/2f).roundToInt() = 2.5 → 3`
     （Kotlin `roundToInt` 对 `.5` **向上进位**，不是截断）
   * 起点侧得 **3**，尾侧得 `5 − 3 = 2`
   ⇒ **多出的那 1px 落在起点侧（左/上）**，尾侧少 1px。
   即：**官方是"先算起点偏移并进位 ⇒ 起点侧 `ceil(e/2)`、尾侧 `floor(e/2)`"**。

**一句话：官方是"居中 + 半值进位（多出的 1px 落**起点侧**）"，不是"向零截断 + 余数落尾侧"。**

### 1.5 与本仓 `:1371` 是否一致 —— **不一致，方向相反**（关键）

本仓的生产实现 `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt:1370-1371`：

```kotlin
1370      fun composerBandChildTopPx(bandPx: Int, childPx: Int): Int =
1371          (bandPx - childPx.coerceAtLeast(0)).coerceAtLeast(0) / 2
```

`Int / Int` ⇒ **向零截断**。同一个奇数余数 `e = 5`：

| | 表达式 | `e = 5` 时 | 起点侧（左/上） | 尾侧（右/下） |
|---|---|---|---|---|
| **官方 material3** `:101` | `(e / 2f).roundToInt()` | `2.5 → 3`（**进位**） | **3** = `ceil(e/2)` | **2** = `floor(e/2)` |
| **本仓** `:1371` | `e / 2`（Int，**截断**） | **2** | **2** = `floor(e/2)` | **3** = `ceil(e/2)` |

**⇒ 两者在奇数余数上不等价，且多出的那 1px 落在相反的一侧：官方给起点侧，本仓给尾侧。**
本仓的起点偏移因此比官方**低 1px**（`2` vs `3`），即本仓把子节点画得比官方**偏下 1px**。

**这正好解释 v148 §1.4 记的那个 `unionPx 49 vs 基线 48`。** v136 让两个子节点"各自居中到同一个整数位"，
本仓这处居中是**截断**居中、material3 那处是**进位**居中；同一奇数余数下本仓起点比官方低 1px，
底沿也就低 1px ⇒ 真机读到的 `49 = 48 + 1`。

**给 Lead 的实话（三条分开说）：**
* ✅ 「方向 = 居中」**成立**——官方 `:101-102` 与本仓 `:1371` 都是居中，不是钉顶沿；
* ❌ 「官方也是向零截断、余数落尾侧」**不成立**——官方是 `/2f` + `roundToInt`，
  对半值**进位**，多出的 1px 落**起点侧**，与本仓截断的落点**相反**；
* ⚠️ 因此本仓 `:1371` 与官方 `:101` **在奇数余数上不等价**（起点侧差 1px、方向相反），
  且本仓**偏下 1px**——这正是 `49 vs 48` 那个 1px 的机制来源。
  v148 只记了"本仓截断"这一半，现在补上"官方进位"这一半，两边合起来才对得上真机读数。

**本轮不改生产代码**（授权只允许测试 + `research/**`）。这条等价性差异是**待裁定项**，见 §6。

### 1.6 已补进 `PRECISION_T1_AND_768B.md`

§6.1 的边界声明已按要求**追加订正节**（不改原结论、不删原文）：见该文件末尾
`§7 v149 订正：androidx 那一层的源码原文（已补齐）`。

---

## 2. 任务 B — 「从 composer 派生 chip」的锚点耦合改造评估（只写方案）

### 2.1 上游定位：官方是**独立锚点**，与 composer 零引用

**上游 web（`packages/app`，SolidJS + Tailwind v4）里那个"浮动控件"就是 chip 的直系对应物。**
逐字原文（`research/../_upstream/packages/app/src/session/screen.tsx`）：

```tsx
336:        <div ref={screen.panel.ref} class="relative flex-1 min-h-0 flex flex-col md:flex-row gap-2">
337:          {/* Keep the control outside panel animations; the terminal's 52px header includes a 1px divider. */}
338:          <Show when={isDesktop() && messagesReady() && session.identity.params.id}>
339:            <div
340:              class="absolute end-3 top-0 z-30 flex items-center"
341:              classList={{ "h-[51px]": sideTerminalVisible(), "h-12": !sideTerminalVisible() }}
342:              data-slot="session-review-toggle"
343:              onPointerDown={hideTimelineScrollbar}
344:              onClick={hideTimelineScrollbar}
345:            >
346:              <SessionReviewToggle />
347:            </div>
348:          </Show>
```

**读法（Tailwind 尺度已核对 `_upstream/packages/ui/src/styles/tailwind/index.css:26` `--spacing: 0.25rem` ⇒ 1 unit = 4px）：**

* `absolute end-3 top-0` ⇒ **尾部内缩 12px、钉顶沿**，锚在 `:336` 那个 `relative` 容器上；
* 三个硬编码常量（`end-3` / `h-12` / `h-[51px]`），**全文件对 composer 零引用**；
* `:337` 的注释明说它**故意放在面板动画之外**——即它与布局是**解耦**的。

**composer 自己的尾部内缩也是独立字面量**（`_upstream/packages/app/src/session/composer/session-composer-region.tsx`）：

```tsx
28:      class="w-full shrink-0 flex flex-col justify-center items-center pb-3 pointer-events-none bg-v2-background-bg-base"
32:          "w-full px-3 pointer-events-auto": true,
33:          "md:max-w-[1000px] md:mx-auto": controller.centered(),
```

**上游唯一量 composer 的地方，是垂直方向、且从不读水平位置**
（`_upstream/packages/app/src/session/requests/session-question-dock.tsx`）：

```tsx
156:    const dock = root.closest('[data-component="session-composer-dock"]')
159:    const dockBottom = dock.getBoundingClientRect().bottom
161:    const gap = 8
162:    const max = Math.max(240, Math.floor(dockBottom - top - gap - below))
```

⇒ **上游有"从 composer 派生垂直尺寸"的先例，但没有任何水平派生的先例。**

**顺带一条**：上游**根本没有 terminal-entry chip**。`>_` 是本壳的发明；
上游 `terminal.toggle` 只是命令（palette / desktop menu / keybind），不渲染按钮：

```tsx
// _upstream/packages/app/src/session/commands/use-session-commands.tsx:355
      id: "terminal.toggle",
// _upstream/packages/app/src/shell/commands/desktop-menu.ts:192
        command: "terminal.toggle",
// _upstream/packages/app/src/session/terminal/terminal.tsx:21
const TOGGLE_TERMINAL_ID = "terminal.toggle"
```

**另一条有价值的对照**（上游 tool row，即本壳 `TOOL_ROW_HEIGHT` 的来源）：

```tsx
// _upstream/packages/app/src/composer/editor/editor.tsx
250:        <div class="flex h-11 items-center px-2">
274:            data-slot="composer-controls"
883:        data-action="composer-submit"
889:        class="size-7 rounded-md p-[6px]"
```

`h-11` = **44px** = 本壳 `TOOL_ROW_HEIGHT = 44.dp`（`Composer.kt:2715`）；
`px-2` = **8px** = 本壳工具行 `.padding(horizontal = 8.dp)`；
`size-7` = **28px** = 本壳 chip / Send 的 **28dp** 本体。
⇒ **垂直与本体尺寸本壳已与上游逐位对齐；只有水平锚点没对齐。**

### 2.2 裁定：**第一档 —— 与上游一致，不需要改成派生**

> **显式声明：本壳与上游一致，`END_DP` 应保持独立锚点，不改成从 composer 派生。**

依据（行号）：

| 判据 | 上游出处 | 本壳对应 |
|---|---|---|
| chip 用**自己的尾部内缩常量**，不读 composer | `screen.tsx:340` `absolute end-3 top-0` | `SessionScreen.kt:3644` `val END_DP: Dp = 64.dp` |
| chip 与面板动画**故意解耦** | `screen.tsx:337` 注释 | `SessionScreen.kt:3612-3614` KDoc（锚到 timeline pane） |
| composer 的内缩也是**独立字面量** | `session-composer-region.tsx:32` `px-3` | 工具行 `horizontal = 8.dp` |
| 从 composer 派生**只有垂直先例** | `session-question-dock.tsx:159-162`（只读 `bottom`） | 垂直已对齐 |

**"派生"改造因此被判定为：无上游依据、且会引入上游刻意避免的耦合。**
（v144 的根因正是两锚点无关；把它们绑一起，会把"下一处分歧"藏起来，而不是防住它——
`UsabilityCellsV146SendOcclusionFixTest.kt:508-514` 已经把这条写成断言了。）

### 2.3 最小改造方案（**不实施**，仅备查）

若 Lead 将来仍要派生，最小 diff 草案是：

```diff
--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt
-                                    end = SessionTerminalEntryPolicy.END_DP,
+                                    end = SessionTerminalEntryPolicy.endDp(paneWidthPx, density),
```

* **影响面**：`SessionScreen.kt:2254-2265` 一处 modifier；`SessionTerminalEntryPolicy` 加一个求值函数；
  `SessionTerminalEntryPolicyTest.theChipKeepsItsTrailingInset` 需从常量断言改为**关系断言**。
* **必须新 pin**：`END_DP` 不再是常量 ⇒ 任何"它等于 64"的断言全部失效，需改为
  "`endDp(1366px, 1x) == 64.dp`" 这类**带输入的关系**。
* **反向验证要点**：① `UsabilityCellsV146SendOcclusionFixTest` **有一条断言会主动变红**——
  `:508-514` 断言 `Composer.kt` **不含** `END_DP`（耦合被明令禁止），派生方案必须先撤这条；
  ② 派生后 `overlapArea == 0` 变成**恒真**（自己算自己），criterion ① 失去判别力，
  需另找外部真值（真机帧）兜底。
* **风险**（高）：把"两个独立锚点恰好不重叠"换成"一个公式自证不重叠"，
  **丧失 v146 建立的回归触发器**——几何再动时不再变红。**本轮不实施。**

### 2.4 若上游无此结构（**不适用**，但记录）

严格说：上游**没有** terminal-entry chip 这个结构（§2.1 末）。
"浮动控件 = 独立锚点"这条**成立**；"chip 本身在上游存在"这条**不成立**。
按 Lead 给的第三档要求补一句基于产品一致性的建议：
本壳的 `>_` 既然是自造控件，就**没有上游像素值可抄**（上游 `end-3 = 12px` 是**另一个控件**的内缩，
且上游的 12px 既不是本壳原来的 8dp、也不是现在的 64dp）。
**产品一致性建议**：保持 64dp 的**几何自洽**（有 4px 间隙），
用 §3 的算式把它的**来源写清楚**即可，不必追求与上游某个数字对齐。**风险**：无上游基线 ⇒
未来上游若新增同类控件，无参照物可校准——但这属于"自造控件"的固有代价，不构成改动理由。

---

## 3. 任务 B 之问三：`END_DP = 64` 应与什么派生勾连？

### 3.1 先排除三个候选

| 候选 | 是否应绑 | 理由 |
|---|---|---|
| `TOOL_ROW_HEIGHT = 44.dp`（`Composer.kt:2715`） | ❌ **不该** | 它是**垂直**量（工具行高）。`END_DP` 是**水平**尾部内缩。绑它=把垂直常量搬去解释水平内缩，**量纲错配**，且会把 v146 刻意保持的"只动水平锚点"重新耦回垂直预算 |
| `band`（一行带高） | ❌ **不该** | 同上，`bandPx` 是带内可用高，v136 已用它做**居中**判定；再让它决定水平内缩等于把两件事绑死，正是 v144 根因的镜像 |
| 靶宽 48dp（`MIN_INTERACTIVE_COMPONENT_PX`，`SessionPaneLayout.kt:108`） | ⚠️ **只能当被除数，不能当基线** | 48 是**被避让的对象**，不是**避让的起点**。`END_DP` 必须在 48dp 之外再留一段间距 |

### 3.2 该绑的量：`Send` 的实测 trailing inset

**64 的真实含义是"chip 的 48dp 靶的尾沿，与 `Send` 的 48dp 靶的头沿之间的间隙"。**
用 v146 自己的几何模型（`UsabilityCellsV146SendOcclusionFixTest.kt:145-161`）逐位复算：

```
expand        = (MIN_INTERACTIVE_COMPONENT_PX - CONTROL_FLOOR_PX) / 2
              = (48 - 28) / 2 = 10                     ← 靶每侧撑开 10px
bodyRight     = paneRight - END_DP
unclippedRight= bodyRight + expand
chip.right     = min(unclippedRight, screenRight)
```

在 600 档（一行形态，`paneRight == screenRight == 1366`，`Send = [1316,215][1364,263]`）：

| `END_DP` | `unclippedRight` | chip 盒 | 与 `Send` 关系 |
|---|---|---|---|
| 8 | 1366 + 10 = 1376 → 被屏边裁到 1366 | `[1328,192][1366,240]`（46px 宽） | **重叠 44×47 = 2068 px²** |
| 56 | 1366 − 56 + 10 = 1320 | `[1272,192][1320,240]` | 尾沿 1320 > `Send` 头沿 1316 ⇒ **仍重叠 4px 宽** |
| **60** | **1316** | `[1268,192][1316,240]` | **恰好相切**（overlap 0 只因等号，无间隙） |
| **64** | **1312** | **`[1264,192][1312,240]`** | **1312 < 1316 ⇒ 间隙 4px** ✅ |

⇒ 算式（这就是该绑的量）：

```
END_DP = (paneRight − Send.left) + expand + gap
       = (1366 − 1316) + 10 + 4
       = 50 + 10 + 4
       = 64 dp
```

**应绑 `Send` 的实测 trailing inset（`Send.left = 1316`，来自归档真机帧
`B_CELL5_600_ONEROW.xml`），不是 `TOOL_ROW_HEIGHT`、不是 `band`。**
其中 `expand = (48 − 28)/2` 是**靶的撑开量**，`gap = 4` 是**设计选择的余量**，
`(paneRight − Send.left) = 50` 是**composer 自己的尾部几何**。

⚠️ **诚实提示**：`64` 里**真正随 pane 宽度变的是 `(paneRight − Send.left)`**，
而 `Send.left` 目前是**归档帧的实测值**、不是生产代码里可求值的量。
所以"派生"若要成立，**第一步是把 `Send` 的 trailing inset 提升为一个生产常量**，
而不是直接把 `END_DP` 绑到 `TOOL_ROW_HEIGHT` 之类**量纲不符**的量上。
**本轮不动生产代码**，这是留给 Lead 的裁定项（§6）。

---

## 4. 任务 C — 回归

**本轮零测试改动**（`app/src/test/**` 未修改任何文件），因此无"受影响类"需要重跑；
按纪律仍跑了与本轮两条结论直接相关的既有类，确认树仍绿：

| 类 | 用例数 | 结果 |
|---|---|---|
| `com.xiufeigo.opencodeshell.UsabilityCellsV146SendOcclusionFixTest` | **8** | **0 失败** ✅ |

日志：`research/v97_fix/_v149_regression.log`（`BUILD SUCCESSFUL`）。
XML 复核：`app/build/test-results/testDebugUnitTest/TEST-…V146SendOcclusionFixTest.xml`
（`tests=8 skipped=0 failures=0 errors=0`，mtime 本轮）。
⚠️ gradle 该任务报 `1 executed, 51 up-to-date`——**本轮未改任何测试/生产文件**，
故 `:app:compileDebugUnitTestKotlin` 等为 `UP-TO-DATE` 是预期；**`:app:testDebugUnitTest` 确实执行**
（XML mtime 为本轮时刻，而非旧文件）。
**用例数未减少**（本轮未删未改任何用例；无放宽/删除断言）。

---

## 5. 改了哪些文件

| 文件 | 性质 | 说明 |
|---|---|---|
| `research/v97_fix/ANDROIDX_EVIDENCE_AND_ANCHORS.md` | **新增** | 本报告 |
| `research/v97_fix/PRECISION_T1_AND_768B.md` | **追加** | 末尾加 `§7 v149 订正`（订正节，不改原结论） |
| `research/v97_fix/_androidx_src/**` | **新增（只读拉取）** | 3 个 sources jar + 3 个解压目录 |
| `research/v97_fix/_v149_regression.log` | **新增** | 回归日志 |

**生产代码（`app/src/main/**`）：零改动。** 测试代码（`app/src/test/**`）：零改动。
本轮全程只用编辑工具写文件，**未用 git checkout/reset/autocrlf 碰过任何行尾**。

---

## 6. 未闭环 / 需 Lead 裁定

1. **§1.5 的等价性差异（本轮最重的一条）**：本仓 `SessionPaneLayout.kt:1371` 是
   **向零截断**居中，官方 `InteractiveComponentSize.kt:101` 是 **`/2f` + `roundToInt`**（半值进位）居中。
   二者在**奇数余数上差 1px（起点侧）**，`unionPx 49 vs 48` 很可能就是这条。
   **本轮授权不含生产代码，未改。** 请裁定：(a) 把 `:1371` 对齐官方进位语义（会动一个既有真值表），
   还是 (b) 保留截断并在 KDoc 注明与官方的差异、接受 1px 偏差？
   倾向 (b)：`:1371` 是 v136 为"两个子节点同位"设计的**确定性**函数，官方进位会引入
   `e=1` 时起点 +1 的漂移，可能打破 v136 钉的"同位"判据。

2. **v148 §6.1 的包名需正名**：48dp 靶由 **material3** 强制，不是 `androidx.compose.foundation`
   （§1.2）。已在本轮追加节订正，**原结论未改**。

3. **`END_DP` 的派生前提**（§3.2）：要让 64 真正"派生"，得先把 `Send` 的 trailing inset
   （`1316`）提升为生产常量。本轮**未做**（生产代码禁改）。是否授权？

4. **未复验项（沿用 v146，未动）**：CELL ⑤ 的遮挡面积仍需真机 tap 复验；criterion ③
   `chipVisiblePixelCount > 0` 是**推导值**，非真机截图读数（`CELL5_FIX.md` §9）。
