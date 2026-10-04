# 会话右侧栏 · 三项一致性修复（v161 N1，照上游改）

- 执行：N1 修复工程师，2026-10-04
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`（分支 `main`，未提交）
- 依据审计：`research/audit/RIGHT_PANEL_GAP.md`（20 项：一致 9 / 不一致 7 / 缺失 3 / 自造 1）
- 死线：2026-10-07
- **当前状态：任务 2、任务 3 已完成且全绿；任务 1 改完且 163/164 绿，但触发 1 条 v146 源码 pin 变红 → 按任务书「若改动会破坏这些基线 ⇒ 停手，只写方案 + 裁定问题」停在此处，等 Lead 裁定（见 §1.4 与 §7）。**

---

## 0. 一句话结论

> **标签已从固定三标签收成上游的「审查 + 上下文」两标签（文件标签改为上游式的动态标签，`file.open` 契约完好、有测试证明）；上下文标签补上了上游同款使用率环；终端芯片已从右侧栏视觉带移除、入口归位到命令面板 `terminal.toggle`——但这一改动让 v146 那条「锚点必须被**实际应用**」的源码 pin 失效，数值基线（768 的 140/92、600 的 51、两档 −10/−12、48dp 靶）一条都没退，红的是那条 pin 本身。** 需要 Lead 裁定是否授权修订该 v146 pin。

---

## 1. 任务 1 ▸ 终端芯片归位（**改完，卡在一条 v146 pin**）

### 1.1 上游依据（已直读原文核对，非转述审计）

| 事实 | 上游行号 | 原文 |
|---|---|---|
| 终端面板**不在右侧栏内**，是独立面板 | `session/screen.tsx:473-500` | `data-slot="session-side-terminal-region"` —— 侧边堆叠区 |
| 终端面板底部通栏形态 | `session/screen.tsx:507-539` | `<TerminalPanel stacked={isDesktop()} ... />`（`:534-538`） |
| 侧栏标签行**只有** review + context 两个 `<Tabs.Trigger>` | `session/files/session-side-panel.tsx:333-343`、`:344-374` | `:333-343` = `value="review"`；`:344-374` = `value="context"`，**无 terminal tab** |
| 开终端靠**命令面板** | `shell/commands/command.tsx:18` | `const EDITABLE_KEYBIND_IDS = new Set(["terminal.toggle", "terminal.new", "file.attach", "browser.reload"])` |
| 命令面板确实收录 `terminal.toggle` | `shell/commands/palette.ts:43` | `"terminal.toggle",`（另 `shell/commands/desktop-menu.ts:192` 挂在桌面菜单上） |
| 中文命令文案 | `runtime/i18n/zh.ts:169` | `"command.terminal.toggle": "切换终端"` |

### 1.2 本壳改前

```kotlin
// app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt:2251-2265（改前）
// v43: the wide-screen terminal entry chip, owned by the timeline
// pane so a docked review panel (the row's other child) can
// never sit under it. See [onToggleTerminalDock].
if (desktop && onToggleTerminalDock != null) {
    TerminalEntryButton(
        onClick = onToggleTerminalDock,
        active = terminalDocked,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(
                top = SessionTerminalEntryPolicy.TOP_DP,
                end = SessionTerminalEntryPolicy.END_DP,
            ),
    )
}
```

组件本体在 `ui/terminal/TerminalPanel.kt:2030-2057`（28dp ghost 方块，`oc_terminal_open` 文案在 `:2039`）——**上游无对应物**，即审计差距表 #18「本壳自造」。

### 1.3 本壳改后（diff）

```diff
--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt
@@ SessionScreen.kt:2251（tab 带上方的挂载块）@@
-                    // v43: the wide-screen terminal entry chip, owned by the timeline
-                    // pane so a docked review panel (the row's other child) can
-                    // never sit under it. See [onToggleTerminalDock].
-                    if (desktop && onToggleTerminalDock != null) {
-                        TerminalEntryButton(
-                            onClick = onToggleTerminalDock,
-                            active = terminalDocked,
-                            modifier = Modifier
-                                .align(Alignment.TopEnd)
-                                .padding(
-                                    top = SessionTerminalEntryPolicy.TOP_DP,
-                                    end = SessionTerminalEntryPolicy.END_DP,
-                                ),
-                        )
-                    }
+                    // v161 parity: on a wide screen the terminal is opened the way
+                    // upstream opens it -- from the command palette's
+                    // `terminal.toggle` row (`shell/commands/command.tsx:18`,
+                    // registered in `ui/palette/AppCommands.kt`), and the panel
+                    // itself is the side / bottom dock (`screen.tsx:473-500` and
+                    // `:507-539`, wired by `TerminalDock` below). The 28 dp chip
+                    // that used to be mounted here had **no** upstream counterpart
+                    // (`session-side-panel.tsx:333-374` has only the review and
+                    // context triggers) and it landed in the side panel's tab band.
+                    // `SessionTerminalEntryPolicy` is deliberately left in place:
+                    // it is the pinned geometry this chip's 48 dp touch box was
+                    // measured against (`UsabilityCellsV146SendOcclusionFixTest`).
```

同时把 `SessionScreen.kt:266-274` 那段已过时的 KDoc（还写着「v43 把芯片挂进时间线面板」）改成 v161 说明。**`onToggleTerminalDock` 形参保留**（`ui/App.kt` 仍在传值，且它正是命令面板行与 `Ctrl+\`` 快捷键驱动的 dock 开关；`ui/App.kt` 本轮禁改）。

**刻意没动的**：
- `ui/terminal/TerminalPanel.kt:2030-2057` 的 `TerminalEntryButton` 组件本体**保留**（只是不再被挂载）。理由见 §5「为什么不降强度」。
- `SessionScreen.kt:3616` 的 `internal object SessionTerminalEntryPolicy`（`TOP_DP = 88.dp` / `END_DP = 64.dp`）**保留**，`UsabilityCellsV146SendOcclusionFixTest` 与 `SessionTerminalEntryPolicyTest` 仍按它建模。
- `TerminalDock` 的 SIDE / BOTTOM 双槽位**一行未动**（`SessionScreen.kt:2522` / `:2546`），停靠行为零变化。

### 1.4 基线是否回退：**数值基线全绿，红的是一条 v146 源码 pin**

最终回归原文（16 类 / 164 用例）：

```
> Task :app:testDebugUnitTest FAILED
UsabilityCellsV146SendOcclusionFixTest > theFixTouchesOnlyTheHorizontalAnchorAndItIsReadLive FAILED
164 tests completed, 1 failed
BUILD FAILED in 12s
```

红断言原文（从 `app/build/test-results/testDebugUnitTest/TEST-…UsabilityCellsV146SendOcclusionFixTest.xml` 取出）：

```
CASE: theFixTouchesOnlyTheHorizontalAnchorAndItIsReadLive
MSG: java.lang.AssertionError: v146: the anchor must still be *applied* as the chip's trailing padding
     (`SessionScreen.kt:2262`), not merely declared. If the modifier stopped using it, the geometry
     model above would be describing a value nothing reads.
```

**逐条核对任务书点名的基线，全部未退**：

| 基线 | 承载测试 | 状态 |
|---|---|---|
| 768 档 `140` / `92` px² 残片算术 | `UsabilityCellsV146SendOcclusionFixTest` 其余 7 条（含 768/600 两档模型） | ✅ 绿（只有上述 1 条红） |
| 600 档 `51` px | 同上 | ✅ 绿 |
| 两档 ①(i) `−10` / `−12` | `UsabilityCellsV144FixTest`（7/7 绿）+ v146 的 60/56 dp 反证 | ✅ 绿 |
| 48dp 触摸靶 | `SessionTerminalEntryPolicyTest`（`TOP_DP = 88f`、`END_DP = 64f`，2/2 绿） | ✅ 绿 |
| 停靠行为 | `TerminalPlacement.SIDE` / `.BOTTOM` 仍在 `SessionScreen.kt:2522` / `:2546` | ✅ 绿（`RightPanelParityTest.theTerminalEntryIsTheCommandPaletteToggle` 源码 pin） |

**因果关系已被双向证明**（反向验证 R1，见 §4）：把芯片挂回去，v146 那条 pin **立刻变绿**；拿掉芯片，它**立刻变红**。它 pin 的是「这个值必须被某个 modifier 读走」，而本次修复按上游把那个 modifier 整个删掉了——**在不放宽断言的前提下，二者不可兼得**。

### 1.5 停手点与裁定问题（请 Lead 裁决，见 §7 Q1）

我没有动 `UsabilityCellsV146SendOcclusionFixTest` 一个字（任务书明令「不许放宽/删除断言」，且它是 v146 那一波的 pin，不属本轮写盘范围）。

---

## 2. 任务 2 ▸ 标签三改二（**完成，全绿**）

### 2.1 上游依据

`session-side-panel.tsx:333-374` 是**固定两枚**触发器——`review`（`:333-343`）与 `context`（`:344-374`）；文件标签是 `<For each={panelTabs()}>`（`:375-386`）**动态**生成的，即「打开一个文件才有一个文件标签」。上游的 strip 从来不是三枚常驻。

### 2.2 改前 vs 改后

改前（`ReviewPanel.kt:339-357`）：三枚 `weight(1f)` 等权 `ReviewTabButton`（变更 / 上下文 / 文件）**恒显**。

改后——`ReviewPanel.kt:53-84` 新增纯函数，标签行改为由它驱动：

```kotlin
// app/src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewPanel.kt:77-84（新增）
internal fun visibleReviewTabs(openPath: String?): List<ReviewPanelTab> =
    if (openPath.isNullOrBlank()) {
        listOf(ReviewPanelTab.CHANGES, ReviewPanelTab.CONTEXT)
    } else {
        listOf(ReviewPanelTab.CHANGES, ReviewPanelTab.CONTEXT, ReviewPanelTab.FILES)
    }
```

```diff
--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewPanel.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewPanel.kt
@@ 标签行（ReviewPanel.kt:339-357 → :372-386）@@
             Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
-                ReviewTabButton(
-                    label = stringResource(R.string.oc_review_tab_changes),
-                    selected = tab == ReviewPanelTab.CHANGES,
-                    onClick = { onTabChange(ReviewPanelTab.CHANGES) },
-                    modifier = Modifier.weight(1f),
-                )
-                ReviewTabButton(
-                    label = stringResource(R.string.oc_review_tab_context),
-                    selected = tab == ReviewPanelTab.CONTEXT,
-                    onClick = { onTabChange(ReviewPanelTab.CONTEXT) },
-                    modifier = Modifier.weight(1f),
-                )
-                ReviewTabButton(
-                    label = stringResource(R.string.oc_review_tab_files),
-                    selected = tab == ReviewPanelTab.FILES,
-                    onClick = { onTabChange(ReviewPanelTab.FILES) },
-                    modifier = Modifier.weight(1f),
-                )
+                visibleReviewTabs(openPath).forEach { entry ->
+                    ReviewTabButton(
+                        label = stringResource(entry.labelRes()),
+                        selected = tab == entry,
+                        onClick = { onTabChange(entry) },
+                        leading = if (entry == ReviewPanelTab.CONTEXT) {
+                            { ContextTabUsageIndicator(contextSnapshot) }
+                        } else {
+                            null
+                        },
+                        modifier = Modifier.weight(1f),
+                    )
+                }
             }
```

`ReviewPanelTab` 枚举三个成员**一个没删**（`SessionScreen.kt:605`、`:1743` 的 `reviewTabName = ReviewPanelTab.FILES.name` 仍需要它），`ReviewPanelTab.FILES -> ReviewFilesTab(` 内容分支也原样保留。

### 2.3 hoisted tab 契约：**保住，有测试**

契约原文（`ReviewPanel.kt:151-155`）：`tab` 是 **hoisted** 状态，供 `ui/palette/AppCommands.kt` 的 `file.open` 从外部切到 Files。

**为什么不是「砍掉文件标签」**：砍掉它 `file.open` 就无处可落，契约当场破。所以按上游形态把它变成**动态标签**（`:375-386` 的 `<For each={panelTabs()}>` 等价物）——默认态两枚、开了文件才出现第三枚，契约反而比改前更贴近上游。

**证明该契约仍成立的测试**（`RightPanelParityTest`，9 条全绿）：

| 测试 | 证明了什么 |
|---|---|
| `theFileOpenContractStillReachesAFileTab` | `visibleReviewTabs("src/main/kotlin/Main.kt")` 含 `FILES`，顺序 `[CHANGES, CONTEXT, FILES]`；`""` / `"   "` 仍为 2 枚 |
| `theHoistedFileOpenWiringIsIntact` | 源码 pin：`SessionScreen.kt` 仍有 `ReviewPanelTab.FILES.name`、仍有 `var reviewTabName by rememberSaveable`；`ReviewPanel.kt` 的标签行仍由 `visibleReviewTabs(openPath)` 驱动、`ReviewPanelTab.FILES -> ReviewFilesTab(` 分支仍在、`onTabChange: (ReviewPanelTab) -> Unit` 仍在 |
| `theRingOpensTheContextTabNotTheDefaultReviewTab`（既有 `SessionContextRingTargetTest`） | 环点击仍切到 `ReviewPanelTab.CONTEXT.name`，**没有被本次改动影响**（3/3 绿） |

---

## 3. 任务 3 ▸ Context 标签补「使用率指示器」（**完成，全绿**）

### 3.1 上游依据

```tsx
// session/files/session-side-panel.tsx:369-372
<div class="flex items-center gap-2">
  <SessionContextUsage variant="indicator" />
  <div>{language.t("session.tab.context")}</div>
</div>
```

指示器画的是 `ctx()?.usage`（`session/session-context-tab.tsx:133`：`model?.limit.context ? Math.round((total / model.limit.context) * 100) : null`）——**与该标签页 stats 表里的 `Usage` 行同一个数**。

### 3.2 改前 vs 改后

改前（`ReviewPanel.kt:346-351`）：CONTEXT 只是纯文字 `ReviewTabButton(label = stringResource(R.string.oc_review_tab_context))`。

改后三处：

1. **`ContextUsage.kt` 末尾新增两个纯函数**（JVM 可测）：

```kotlin
// app/src/main/java/com/xiufeigo/opencodeshell/ui/session/header/ContextUsage.kt:145-171（新增）
fun indicatorPercent(usageCell: String?): Int? {
    val cell = usageCell?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val digits = cell.removeSuffix("%").trim()
    val percent = digits.toIntOrNull() ?: return null
    return percent.takeIf { it in 0..100 }
}

fun indicatorProgress(usageCell: String?): Float? = indicatorPercent(usageCell)?.let(::progress)
```

2. **`ReviewTabButton` 加 leading 槽位**（`ReviewPanel.kt:512-556`）：对应上游 `flex items-center gap-2` 的包裹层；`leading == null` 时走原来的单 `Text` 布局（`Box` 只补 padding，视觉零变化）。

3. **`ContextTabUsageIndicator`**（`ReviewPanel.kt:559-580`）：

```kotlin
@Composable
private fun ContextTabUsageIndicator(
    snapshot: SessionContextModel.Snapshot?,
    modifier: Modifier = Modifier,
) {
    val progress = ContextUsage.indicatorProgress(
        snapshot?.stats?.firstOrNull { it.kind == SessionContextModel.Kind.USAGE }?.value
    ) ?: return
    ContextRing(progress = progress, modifier = modifier)
}
```

**呈现与上游一致的三个点**：
- 画的是 `ContextRing`，即上游 `ProgressCircleV2` 的本壳移植（`size = 14`、`strokeWidth = 1.5`，`ContextRing.kt:60-77`），**不是另画一个**；
- 位置在标签**文字前面**、居中对齐、`spacedBy(8.dp)`＝上游 `gap-2`；
- `usage` 未知时**什么都不画**（回落到纯文字标签）——上游 `ctx()?.usage` 为 `null` 时同样不画（`:133`，`limit.context` 缺失即 `null`），而不是画一个空环。

**为什么不重算一遍比例、而是读回 `Usage` 单元格**：`Snapshot.Stat.value` 是已格式化文本；在这里重算等于**第二次**计算一个本面板并不拥有的载荷，还可能与一 tap 之隔的 `Usage` 行对不上（这正是 v17-23 踩过的环/页分裂坑，见 `ContextUsage.kt:120-131` 的 KDoc）。一个数、一个来源。

---

## 4. 反向验证（三项各自撤回 ⇒ 对应 pin 变红；真跑抄原文）

全部用**源码级临时回退**（不碰任何断言），跑完立即还原。

### R1 ▸ 任务 1 撤回（把芯片挂回 `SessionScreen.kt:2251`）

命令：

```
.\gradlew.bat --console=plain :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.RightPanelParityTest" --tests "com.xiufeigo.opencodeshell.UsabilityCellsV146SendOcclusionFixTest"
```

原文：

```
> Task :app:testDebugUnitTest FAILED
RightPanelParityTest > theSidePanelNoLongerMountsTheSelfInventedTerminalChip FAILED
    java.lang.AssertionError at RightPanelParityTest.kt:56
17 tests completed, 1 failed
BUILD FAILED in 9s
```

**附带结论**：芯片挂回去时 `UsabilityCellsV146SendOcclusionFixTest` 8/8 **全绿**。⇒ v146 那条 pin 与芯片同生共死，因果链闭合。

### R2 ▸ 任务 2 撤回（`visibleReviewTabs` 恒返回三枚 = 改前形态）

```
===== R2: task-2 reverted (always three tabs) =====
> Task :app:testDebugUnitTest FAILED
RightPanelParityTest > theFileOpenContractStillReachesAFileTab FAILED
RightPanelParityTest > theDefaultStripIsTheUpstreamPair FAILED
9 tests completed, 2 failed
```

### R3 ▸ 任务 3 撤回（标签行 `leading = null` = 改前形态）

```
===== R3: task-3 reverted (no usage indicator) =====
> Task :app:testDebugUnitTest FAILED
RightPanelParityTest > theContextTabRendersTheIndicator FAILED
9 tests completed, 1 failed
```

三项**各自**都能把对应 pin 打红，互不串味（R2 只红标签两条、R3 只红指示器一条）。

---

## 5. 为什么不降强度

| 诱惑 | 为什么不做 |
|---|---|
| 删/放宽 v146 那条红断言 | 明令禁止；且它是 v146 那一波的 pin，不在本轮写盘范围。故按任务书「停手 + 裁定问题」上报，而不是自己动手 |
| 删掉 `TerminalEntryButton` 组件本体 | 删了会连带把 `UsabilityCellsV146SendOcclusionFixTest` 的 KDoc 与 v43/v144/v146 三轮设备证据链割断。保留组件 = 保留可回退性与历史可审计性；上游无此物只说明**不该挂载**，不说明**不该存在** |
| 删掉 `SessionTerminalEntryPolicy`（现已无生产消费方） | 那是 46×48 设备实测框的锚点，`SessionTerminalEntryPolicyTest` + v146 的 px 算术全靠它。删常量会把一整轮设备回归变成"无法复算的数字" |
| 把「两标签」做成硬编码两枚、砍掉 FILES | 直接破 `file.open` hoisted 契约（任务书点名要保）。改为上游式动态标签，契约更完整 |
| 用新文案（如把「变更」改成「审查」） | 任务书禁止新增用户可见文案；且 `strings.xml:567-568` 标注这些 key 属对外冻结契约，不在本轮范围 |
| 动使用率分母去追上游 | 见 §6，是有意为之，只记录 |

---

## 6. 两处「不要改、只记录」

### 6.1 「上下文」文案 —— 已对齐，不动

- 上游 `runtime/i18n/zh.ts:717` → `"session.tab.context": "上下文"`
- 本壳 `res/values-zh-rCN/strings.xml:443` → `oc_review_tab_context` = 上下文

两侧逐字相同，**无需任何改动**。用户转述的「上文」疑为口语转写误差（审计 §1.3、§4 TOP-10 已记）。本轮 `RightPanelParityTest.noNewTabCopyWasIntroduced` 另外钉住「未新增任何 tab 文案」。

### 6.2 使用率分母 —— **本壳口径可能是有意为之，只记录**

| | 口径 | 行号 |
|---|---|---|
| 上游 | `total / model.limit.context` | `session-context-tab.tsx:133` |
| 本壳 | `min(limit.input, limit.context)` | `SessionContextModel.kt:80-81`（即 `ContextUsage.windowOf`） |

本壳选择**更小的 input 半**，`ContextUsage.kt:16-45` 的 KDoc 已给出实测依据：v20 目录 129 条中 **45 条** `input < context`；服务端 `SessionCompaction.required`（`_upstream/packages/core/src/session/compaction.ts:742-763`）就是从 `limit.input` 减 buffer 触发压缩的。以 `opencode-go/space-bunny-free`（`{context:1048576, input:524288, output:524288}`）为例，服务端真实上限是 **504,288**，若按上游 `limit.context` 显示，UI 会显示 59% 而服务端早已在压缩。**这可能是有意的保守口径，改动需产品确认，不在本轮范围。**

**若 Lead 决定改成上游口径，需要动这些**（未执行，仅列清单）：

1. `ui/session/header/ContextUsage.kt:66-77` `windowOf(limitContext, limitInput)` —— 去掉 `min`，只留 `limitContext`；
2. `ChatViewModel.contextLimitFrom`（`ContextUsage.kt:107-119` 的 KDoc 指向它）—— 它是环、header 百分比、`Context Limit`/`Usage` 两行、以及客户端**预压缩触发**的共同上游，去掉 min 会连带放松预压缩阈值（这是唯一有行为风险的一处）；
3. `SessionContextModel.kt:80-81` 的 `LIMIT` 行（影响 `strings.xml:490`「使用率」显示）；
4. 连带 pin：`ContextUsageTest`、`ContextWindowLimitTest`（16 条）、`ContextLimitSourcesTest`（12 条）、`ContextOverflowRecoveryTest`（19 条）、`SessionContextModelTest`（21 条）—— 这四类共 68 条大概率要按新口径**加强**（不是放宽）；
5. **必须重跑真机**：环的分母变化会让 768/600 两档设备截图上的百分比与 `140/92`、`51` 那批 px 算术的语境一起变，`research/v97_verify/5554_md/artifacts/*.xml` 的历史读数需要重新标注来源。

---

## 7. 待 Lead 裁定

### Q1（**阻塞任务 1 收尾**）：v146 那条「锚点必须被实际应用」pin 怎么处理？

`UsabilityCellsV146SendOcclusionFixTest.theFixTouchesOnlyTheHorizontalAnchorAndItIsReadLive` 断言 `SessionScreen.kt` 里存在 `end = SessionTerminalEntryPolicy.END_DP,`，原话是「If the modifier stopped using it, the geometry model above would be describing a value nothing reads.」。按上游把芯片删掉后，**没有任何 modifier 会再读这个锚点**，这条 pin 在不放宽的前提下不可能绿。

三个选项，请择一：

- **(A) 授权修订该 v146 pin**（我的建议）：把「锚点被应用」这一条**改为**「锚点常量与 `SessionTerminalEntryPolicyTest` 的两个值仍在、且 `RightPanelParityTest` 已证明停靠双槽位与面板入口完好」——即把 pin 的对象从「芯片 modifier」换成「dock 面板 + 面板命令入口」，**保留** 768/600 的 140/92、51、−10/−12 全部 px 算术断言不动（它们现在全绿，一行都不用改）。这需要动一个**非本轮写盘范围**的 v146 测试文件，故须显式授权。
- **(B) 任务 1 整体回退**：恢复 `SessionScreen.kt:2251-2265` 的芯片挂载，本轮只交付任务 2、任务 3（两者已全绿、与芯片无耦合）。代价：右侧栏那条自造芯片继续留在标签视觉带上（审计 TOP-1 未闭环）。
- **(C) 任务 1 推迟**到下一波，与「v146 证据链退役」一起处理；本轮代码留在工作区不提交，等裁定。

**未做**：我没有替你选，也没有碰那个测试文件。当前工作区状态 = 任务 1/2/3 全改、1 条红、**未提交**。

### Q2：术语对齐（不阻塞）

`oc_review_title`「评审」/ `oc_review_tab_changes`「变更」与上游 `session.tab.review`「审查」仍是两套说法（审计 TOP-6）。`strings.xml:567-568` 标注这些 key 属对外冻结契约，**改动需先确认契约归属方**。本轮按禁改文案处理，只登记。

---

## 8. 测试证据

### 8.1 命令与结果

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.RightPanelParityTest" `
  --tests "com.xiufeigo.opencodeshell.SessionContextRingTargetTest" `
  --tests "com.xiufeigo.opencodeshell.SessionTerminalEntryPolicyTest" `
  --tests "com.xiufeigo.opencodeshell.UsabilityCellsV144FixTest" `
  --tests "com.xiufeigo.opencodeshell.UsabilityCellsV146SendOcclusionFixTest" `
  --tests "com.xiufeigo.opencodeshell.ContextUsageTest" `
  --tests "com.xiufeigo.opencodeshell.ContextLimitSourcesTest" `
  --tests "com.xiufeigo.opencodeshell.ContextWindowLimitTest" `
  --tests "com.xiufeigo.opencodeshell.ContextOverflowRecoveryTest" `
  --tests "com.xiufeigo.opencodeshell.SessionContextPanelFreshnessTest" `
  --tests "com.xiufeigo.opencodeshell.MobileReviewScopeParityTest" `
  --tests "com.xiufeigo.opencodeshell.ReviewChangesLoadStateTest" `
  --tests "com.xiufeigo.opencodeshell.ReviewReprobeActionTest" `
  --tests "com.xiufeigo.opencodeshell.ReviewVcsReprobeTest" `
  --tests "com.xiufeigo.opencodeshell.SessionContextModelTest" `
  --tests "com.xiufeigo.opencodeshell.SessionContextRingTest"
```

最终回归原文（**不是** BUILD SUCCESSFUL，如实抄）：

```
===== FINAL REGRESSION: 16 classes =====
> Task :app:testDebugUnitTest FAILED
UsabilityCellsV146SendOcclusionFixTest > theFixTouchesOnlyTheHorizontalAnchorAndItIsReadLive FAILED
164 tests completed, 1 failed
BUILD FAILED in 12s
```

### 8.2 逐类用例数（`app/build/test-results/testDebugUnitTest/*.xml` 汇总）

| 类 | 用例 | 失败 |
|---|---:|---:|
| **RightPanelParityTest**（本轮新增） | 9 | 0 |
| SessionContextRingTargetTest | 3 | 0 |
| SessionTerminalEntryPolicyTest | 2 | 0 |
| UsabilityCellsV144FixTest | 7 | 0 |
| UsabilityCellsV146SendOcclusionFixTest | 8 | **1** |
| ContextUsageTest | 6 | 0 |
| ContextLimitSourcesTest | 12 | 0 |
| ContextWindowLimitTest | 16 | 0 |
| ContextOverflowRecoveryTest | 19 | 0 |
| SessionContextPanelFreshnessTest | 9 | 0 |
| MobileReviewScopeParityTest | 6 | 0 |
| ReviewChangesLoadStateTest | 21 | 0 |
| ReviewReprobeActionTest | 10 | 0 |
| ReviewVcsReprobeTest | 12 | 0 |
| SessionContextModelTest | 21 | 0 |
| SessionContextRingTest | 3 | 0 |
| **合计** | **164** | **1** |

- **用例数只增不减**：本轮只新增 1 个测试文件（9 条），**未删改任何既有测试**（`git status` 无 `app/src/test/**` 的 `M` 状态）。
- 唯一那 1 红**不是回归**，是任务 1 的预期后果（§1.4 + §7 Q1）；任务 2、任务 3 涉及的 156 条**全绿**。
- 跑法遵守：仅 `:app:testDebugUnitTest` + 精确 `--tests` 类名（无通配），**未** clean、**未**跑全量、**未**装机。

### 8.3 抢锁记录

`.agent-n` 检查为不存在 → 写入 `v161-n1-right-panel-parity` → 全部 5 次 gradle 调用**前台跑完** → 释放。四次反向验证与两次正式回归全程持锁。释放记录见 §10。

---

## 9. 真机复验步骤（交给 Lead；本轮**未装机**）

**前置**：先对 §7 Q1 给出裁定（决定任务 1 的代码去留）；下面按「任务 1 保留」的最坏情况写，若选 (B)/(C) 跳过第 1、5 步。

1. **宽屏右侧栏顶部不再有 `>_` 芯片**：进一个有 diff 的会话 → 停靠右侧栏 → 确认标签行**左起只有两枚**（变更 / 上下文），右上方**无** 28dp 终端方块。参照对比 `research/v43_verify/tE/v43_32_panel_wide.png`（那张里芯片正压在「文件」tab 上）。
2. **命令面板开终端**：`Ctrl+K` → 输入/选「切换终端」→ 终端面板出现；再执行一次收起。核对上游同文案（`zh.ts:169`）。
3. **停靠两形态**：切到「侧边」停靠 → 与右侧栏同栏堆叠；切到「底部」→ 底部通栏。两者高度均应 ≤ 视口 60%（`panel.tsx:75-76`），与改前完全一致。
4. **标签两态**：默认态 = 2 枚；用命令面板 `file.open`（`mod+p`）开一个文件 → 第 3 枚「文件」tab **出现并被选中** → 树里该文件高亮。关掉面板再开 → 回到 2 枚。
5. **上下文环**：切到「上下文」tab → 标签文字**左侧**出现 14dp 细环，扫角 = 该 tab `Usage` 行的百分比。**无用量数据时**（新会话 / 无模型 limit）应**只有纯文字、没有空环**。
6. **移动端不回退**：`MobileForm.Tab.TERMINAL` 入口仍在（`SessionScreen.kt:1755`），整屏 usage 视图仍正常。
7. **历史基线抽查**（若任务 1 保留）：`SessionScreen` 顶栏区 88dp 锚点、composer `Send` 盒 64dp 锚点均未动；建议重截 768 / 600 两档，确认与 `research/v97_verify/5554_md/artifacts/v140_768.xml`、`v140_600off.xml` 的差异**仅**是芯片消失（其它元素坐标应逐一不变）。

---

## 10. 未闭环

1. **§7 Q1 的裁定**（阻塞任务 1 收尾 / 阻塞全量闸门绿）。
2. **未提交**：本轮 3 个源码文件 + 1 个测试文件改动均在工作区，**未 commit**（等裁定；`settings.gradle.kts` 的改动**不是本轮做的**，疑似 W1 并发所留，未触碰）。
3. 全量闸门（`cleanTestDebugUnitTest`）**未跑**——按并发纪律只归主 agent。
4. 真机复验 §9 **未做**（本轮禁设备操作 / 禁装机）。
5. 审计中未纳入本轮的缺口（**不在本轮三项范围**）：TOP-4 context tab 可关闭、TOP-5 Open file 文件浏览器 tab、TOP-7 使用率分母（§6.2 只记录）、TOP-8 面板宽度动画、TOP-9 标签拖拽排序。
6. `_upstream/**` 全程只读；未新增任何用户可见字符串。

---

## 附：改动文件清单

| 文件 | 性质 | 内容 |
|---|---|---|
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt` | 改 | 移除 `:2251-2265` 芯片挂载块（换成 v161 说明注释）；`:266-274` KDoc 更新为 v161 |
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewPanel.kt` | 改 | 新增 `visibleReviewTabs` + `labelRes`；标签行改为动态列表 + leading 槽位；`ReviewTabButton` 加 `leading`；新增 `ContextTabUsageIndicator`；加 3 个 import |
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/header/ContextUsage.kt` | 改 | 新增 `indicatorPercent` / `indicatorProgress` 两个纯函数 |
| `app/src/test/java/com/xiufeigo/opencodeshell/RightPanelParityTest.kt` | **新增** | 9 条：任务 1 三条（含 dock 双槽位 + 几何 pin 留存）、任务 2 三条（含 hoisted 契约）、任务 3 三条（含无新文案） |

未触碰：`_upstream/**`（只读）、`app/build.gradle.kts`、`webapp/**`、`ChatViewModel.kt`、`ui/servers|home|terminal|nav/**`、`ui/App.kt`、`OcActivity.kt`、`ui/terminal/TerminalPanel.kt`、`res/**`、任何既有测试。
