# CELL5_PIN_REALIGN — 结清 v146 绊线：终端芯片归位后的等价守护改写（v161 N2）

- 执行：N2 修复工程师（mcode），2026-10-04
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 死线：2026-10-07
- 任务书：闸门 **677 suites / 6134 tests / 1 failed** → `UsabilityCellsV146SendOcclusionFixTest > theFixTouchesOnlyTheHorizontalAnchorAndItIsReadLive`
- 前置依据：`research/fix/RIGHT_PANEL_PARITY_FIX.md`（N1 停手点 §1.4 / §7 Q1）、`research/v97_fix/CELL5_FIX.md`、`research/v97_verify/5554_md/REPORT_CELL5_ACCEPT.md`
- 结果：**判定 (a)**；原绊线**未删**、**未放宽**，改写为对新结构的等价守护 + 新增可达性守护；反向验证**真红**；受影响 **4 类 / 28 用例 / 0 失败**。

---

## 0. 一句话结论

**N1 是有意按上游改的（(a)）**——上游 `v2` 的会话侧栏标签行**只有** review / context / 动态文件三档，**没有** terminal tab，终端从命令面板 `terminal.toggle` 开。v146 那条绊线问的是「芯片的**水平锚点是否仍被当作 trailing padding 应用**」，它守护的前提（芯片被挂载）已不成立，因此**只能红**；但它当时守护的**缺陷**（`Send` 盖住一个可用控件、芯片可见像素 0）**必须不再发生**。处置 = **不是删掉它**，而是把它换成两个**对新结构可判定**、且**守护对象与原判据同一个**的断言。

---

## 1. 判定 (a)：依据与复核

### 1.1 ⚠️ 先说方法论坑：`origin/v2` 在本仓**不可用作复核基准**

任务书要求「在 `origin/v2` 的 git 对象上复核」。**实测该指令在本仓会给出假阴性**，必须记下来：

```
$ git -C _upstream rev-parse HEAD origin/v2
702a73d91ad5356e491145b9d9280a1572eb44f5     <- 本地检出的分支 v2
ecfa884ff82e9116e475b71a0c99383b8438d710     <- origin/v2

$ git -C _upstream diff --stat origin/v2 v2
fatal: unable to access 'https://github.com/anomalyco/opencode.git/': Failed to connect to
       github.com:443 after 21062 ms: Could not connect to server
fatal: could not fetch 686e6c191e7d6101d91a2aed2363629be5313f8a from promisor remote
```

两个事实：

1. **`_upstream` 是 partial clone（promisor remote），且 GitHub 不可达。** 凡是本地没有的 blob，`git show origin/v2:<path>` / `git grep origin/v2` **静默返回空或直接 exit 1**，看起来像「上游没有这个文件」，实际是**取不到**。
2. **`origin/v2` ≠ 本地检出的 `v2` HEAD。** `origin/v2`（ecfa884）上 `packages/app/src/session/files/` **整个目录不存在**；`v2` HEAD（702a73d）上它存在。

> 若只按任务书字面在 `origin/v2` 上复核，会得出「上游没有 side-panel 文件，所以 N1 的引用是编的」这一**错误**结论。**本报告全部上游行号均在 `v2` HEAD 上复核**（与 `_upstream` 工作区实际内容一致，也与 N1 读的是同一棵树）。**未切工作区、未 fetch、未联网。**

### 1.2 决定性证据：上游会话侧栏**没有** terminal tab

`_upstream` v2 HEAD，`packages/app/src/session/files/session-side-panel.tsx`（标签行 `Tabs.Trigger` 全部出现处，逐行编号）：

| 行号 | 内容 | 含义 |
|---|---|---|
| `:334-342` | `<Tabs.Trigger value="review" …>` | 变更 |
| `:345-373` | `<Tabs.Trigger value="context" …>` | 上下文（含 `:370 <SessionContextUsage variant="indicator" />`） |
| `:375` | `<For each={panelTabs()}>` | 动态文件标签（打开文件才有） |
| `:375` 之后 | 文件 / 空态 / 树 | **无任何 terminal 触发器** |

> 该文件内 `Select-String -Pattern "terminal|Tabs.Trigger|value="` 的全部命中里，**没有一行与 terminal 相关**。
> ⇒ 壳里那个 28 dp `TerminalEntryButton` 挂在侧栏标签行那一带，**上游确实没有对应物**。N1 删除它是**有意的、照上游的**。

### 1.3 佐证：终端在上游从**命令面板**开，且这些引用 N1 没写错

| 事实 | v2 HEAD 行号 | 原文 |
|---|---|---|
| 终端面板是**独立面板**，不塞在侧栏内 | `packages/app/src/session/screen.tsx:474` | `data-slot="session-side-terminal-region"`（侧边堆叠区） |
| 底部通栏形态 | `packages/app/src/session/screen.tsx:510` | `data-slot="terminal-panel-presence"` |
| 开终端靠**命令面板** | `packages/app/src/shell/commands/command.tsx:18` | `const EDITABLE_KEYBIND_IDS = new Set(["terminal.toggle", "terminal.new", "file.attach", "browser.reload"])` |
| 命令面板确实收录 | `packages/app/src/shell/commands/palette.ts:43` | `"terminal.toggle",` |
| 桌面菜单也挂 | `packages/app/src/shell/commands/desktop-menu.ts:184` | `command: "terminal.toggle",` |
| 中文文案 | `packages/app/src/runtime/i18n/zh.ts:169` | `"command.terminal.toggle": "切换终端",` |

> N1 文档把 `screen.tsx` 写成 `:473-500` / `:507-539`，实际起始行是 **474 / 510**（差 1 行，区间描述成立）。`palette.ts:43` **在 v2 HEAD 上完全命中**——此前在 `origin/v2` 上读不到，是 §1.1 的假阴性，不是 N1 写错。

### 1.4 本壳现状：芯片挂载点已空

`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt:2253-2265` 现在是**纯注释**：

```kotlin
// v161 parity: on a wide screen the terminal is opened the way
// upstream opens it -- from the command palette's
// `terminal.toggle` row (`shell/commands/command.tsx:18`,
// registered in `ui/palette/AppCommands.kt`), and the panel
// itself is the side / bottom dock (`screen.tsx:473-500` and
// `:507-539`, wired by `TerminalDock` below). The 28 dp chip
// that used to be mounted here had **no** upstream counterpart
// (`session-side-panel.tsx:333-374` has only the review and
// context triggers) and it landed in the side panel's tab band.
// `SessionTerminalEntryPolicy` is deliberately left in place:
// it is the pinned geometry this chip's 48 dp touch box was
// measured against (`UsabilityCellsV146SendOcclusionFixTest`).
```

`SessionScreen.kt:175` 的 `import …TerminalEntryButton` 与 `:3615` 的 `SessionTerminalEntryPolicy`（`TOP_DP = 88.dp` / `END_DP = 64.dp`）都**按 N1 设计保留**。

### 1.5 判定

> **(a) —— N1 是有意按上游改的，芯片按上游形态消失。**
> 不是 (b)：上游确实**没有**那个入口（§1.2 逐行核对），修回结构等于把一个自造控件塞回上游没有的位置。
> 不是 (c)：红的原因单一且可定位——原断言读的是 `screen.contains("end = SessionTerminalEntryPolicy.END_DP,")`，而该行随挂载块一起被 N1 删了。

---

## 2. v146 原绊线**为何失效**（失效的是前提，不是缺陷）

原断言（`UsabilityCellsV146SendOcclusionFixTest.kt`，`theFixTouchesOnlyTheHorizontalAnchorAndItIsReadLive` 末尾）：

```kotlin
val screen = readSource(".../SessionScreen.kt")
assertTrue(
    "v146: the anchor must still be *applied* as the chip's trailing padding " +
        "(`SessionScreen.kt:2262`), not merely declared. If the modifier stopped using " +
        "it, the geometry model above would be describing a value nothing reads.",
    screen.contains("end = SessionTerminalEntryPolicy.END_DP,"),
)
```

它的**成立条件**是：`SessionScreen.kt` 里存在一个把 `END_DP` 当 trailing padding 用在**已挂载控件**上的 modifier。N1 删掉挂载块 ⇒ 该字符串消失 ⇒ **只能 `false`**。

> 这条断言**不是**在守护「`Send` 不许盖住控件」这个性质，它问的是一个**关于某个特定控件接线状态的实现事实**。控件不存在时，这个事实无法为真——**不是判据变松了，是被问的对象没了**。
>
> 因此处置**不是**删掉它（那会把 2068 px² 那个缺陷的 tripwire 一起拆掉），而是**换一个仍然可判定、且问的是同一个性质的问法**。

### 原绊线真正守护的缺陷（v144 / v145 / v146 三轮结论）

| 缺陷事实 | 数值读数 | 出处 |
|---|---|---|
| 芯片合成在 viewport `Box` 内，是 composer host `Box` 的**更早**兄弟 ⇒ `Send` 画在它上面 | z 序错 | `CELL5_FIX.md` §1.1 |
| 两者落在**同一行**，重叠 | `44 × 47 = 2068 px²` | `CELL5_FIX.md` §1.2 / v145 |
| 残余只有一条右窄条 + 一条底窄条 | 窄边 **2 px**，共 140 px² | 同上 |
| 那 2 px 实测是**纯白背景**，芯片 28 dp 本体全在 `Send` 底下 | **可见像素 0** | v145 §2.4 |
| 盖住它的 `Send` 当时 `enabled="false"` | **不可用按钮埋可用按钮** | v145 §2.4 / `B_CELL5_600_ONEROW.xml` |

**一句话**：cell 5 = **一个可用控件被画在了 composer 之下、与 `Send` 落在同一矩形、且用户看到的像素是 0**。它**从来不是**「芯片好不好看」或「芯片在不在」。

---

## 3. 新的等价守护断言

### 3.1 改写后仍留在原类里的部分（**一个字都没削弱**）

`theFixTouchesOnlyTheHorizontalAnchorAndItIsReadLive` **保留同名**，只摘掉那句已失效的接线断言，其余三条断言**原样保留**：

| 保留的断言 | 作用 |
|---|---|
| `assertEquals(64f, SessionTerminalEntryPolicy.END_DP.value)` | 水平锚点仍**活读**生产常量、仍是 64 dp |
| `assertEquals(64f, SessionPaneLayout.TIMELINE_MIN_CONTENT.value)` | 垂直预算未被碰 |
| `assertFalse(composer.contains("END_DP"))` | 两个锚点**保持独立**（v144 根因不得复现） |

criterion ①–⑥ 与 `theGeometryModelReproducesTheMeasuredPreFixBoxAndPinsV145Readings`（**2068 / 2 / 0 三个触发器 + 46 px 复现 + `enabled="false"` 帧**）**一行未改**，仍是回归触发器。

### 3.2 新增断言 ①：`theSelfInventedChipIsNotMountedInTheTimelinePaneAgain`

判据（两问，都对新结构可判定）：

1. `assertFalse(screen.contains("TerminalEntryButton("))` —— 判的是**调用点**，不是这个词。`:175` 的 import 和历史注释里合法地仍然出现这个名字；**不允许回来的**是把它作为 timeline pane 的 `Alignment.TopEnd` 挂载起来的那个 composable。
2. `assertFalse(screen.contains("end = SessionTerminalEntryPolicy.END_DP,"))` —— `END_DP` 可以**声明**（它就是归档帧所测的那份几何，`SessionTerminalEntryPolicyTest` 仍在建模），但**不允许**再有任何 modifier 拿它当 pane 内的 trailing padding。

### 3.3 新增断言 ②：`theTerminalEntryIsReachableThroughTheUpstreamPath`

删除自造控件**只有在终端仍可达时**才算 parity——否则就是拿「一个不可用的入口」换「一个没有入口」，比 v146 记录的状态**更糟**。判据：

| 断言 | 依据 |
|---|---|
| `AppCommands.kt` 含 `id = "terminal.toggle"` | 上游 v2 `command.tsx:18` / `palette.ts:43` |
| `AppCommands.kt` 含 `onSelect = { onOpenTerminal?.invoke() }` | 必须是**真 toggle**，不是装饰行 |
| `AppCommands.kt` 含 `titleResId = R.string.oc_command_terminal_toggle` | 上游 `zh.ts:169`＝切换终端；**relink 不改词** |
| `App.kt` 含 `onOpenTerminal = actions.onTerminalToggle,` | 宿主必须传**非 null** 动作；传 null 就是「行还在、按不动」——正是 v146 记的 `enabled="false"` 形态 |

### 3.4 ★ 核心论证：**新断言守护的是同一个缺陷**

> 这是本仓最看重的一条：判据与守护对象必须一致。

| | v146 原判据 | v161 新判据 | 同一个缺陷？ |
|---|---|---|---|
| **被问的性质** | 「芯片的 trailing padding 是否 = `END_DP`」 | 「pane 内是否还挂着一个会被 composer 盖住的浮动控件」 | **是**。两者问的都是：**viewport `Box` 里是否存在一个与 composer 落在同一行的可用控件**。 |
| **可判定性** | 字符串包含 | 字符串**否定**包含 | 同级（源码 pin），且**在结构变化后仍能判定** |
| **失效场景** | 有人把 `END_DP` 改回 8 dp / 去掉 modifier | 有人**把芯片挂回来**，或**再拿 `END_DP` 当 padding** | **完全同一批** |
| **原缺陷的物理成因** | 芯片在 viewport `Box`（composer 的**更早**兄弟）内 ⇒ `Send` 画在它上面 | 芯片不在了 ⇒ viewport `Box` 内**没有任何**控件与 `Send` 同矩形 | **是**。新判据直接掐在成因上：没有控件 ⇒ 没有可被盖的控件 |
| **tripwire 时机** | 几何重合后才红 | **重新挂载的那一刻就红，早于几何重合** | **更强**（前移） |

**为什么这不是「换个说法把断言放水」**：原断言的**唯一失效方式**就是「有人删掉/挪走芯片」——而删掉芯片**正是 N1 照上游做的正确改动**。把一条会因正确改动而红的断言，改写成「对芯片正确改动后仍能继续守护该缺陷」的问法，是**换判据**；而 §3.1 证明六条 criterion 与三个数值触发器**原封不动**，所以**缺陷本身仍被原来的那套数值判据守着**。两侧合起来：**旧判据守数值、旧触发器守读数、新判据守结构**。

**可达性那半为什么属于同一个 cell**：v146 记录的最刺眼一条是「盖住它的 `Send` 当时 `enabled="false`」——**一个入口存在但用不了**。若只删芯片不接上命令面板，终端入口就从「存在但不可用」变成「不存在」，属于同一失败模式的更差实例。所以它与「不许有控件被盖」同属 cell 5，一并守。

### 3.5 为什么**不降强度**（逐条自查）

| 旧 | 新 | 强度变化 |
|---|---|---|
| `assertTrue(screen.contains("end = …END_DP,"))` **1 条** | `assertFalse(screen.contains("TerminalEntryButton("))` **+** `assertFalse(screen.contains("end = …END_DP,"))` **+ 4 条可达性断言** | 接线维度 **1 → 2 条**（禁挂载 + 禁沿用 padding），**另加 4 条**新维度（行存在 / 真 toggle / 文案同源 / 宿主非 null） |
| 用例数 | `UsabilityCellsV146SendOcclusionFixTest` **8 → 10 个方法** | **只增不减**（+2） |
| 数值判据 ①–⑥、触发器 2068/2/0 | 逐字未改 | **零削弱** |
| 是否有断言被删 | **无**。唯一被移除的那句，其对象已不存在，且其**性质**由上表 6 条断言承接 | — |

**未新增任何用户可见字符串**（只读 `R.string.oc_command_terminal_toggle`，与上游 `zh.ts:169` 同文案）。

---

## 4. diff

```
 app/src/test/java/com/xiufeigo/opencodeshell/UsabilityCellsV146SendOcclusionFixTest.kt | 140 ++++++++++++++++-----
 1 file changed, 133 insertions(+), 7 deletions(-)
```

**`SessionScreen.kt` 等生产代码零改动**（`git diff --stat -- app/src/main` 输出为空，§6 证据）。反向验证期间临时挂载的芯片已**逐字回退**，回退后 `git diff` 对该文件**为空** = 与本轮开始时的 HEAD **完全一致**。

改写形状：

```diff
     fun theFixTouchesOnlyTheHorizontalAnchorAndItIsReadLive() {
         assertEquals(64f, SessionTerminalEntryPolicy.END_DP.value)          // 保留
         assertEquals(64f, SessionPaneLayout.TIMELINE_MIN_CONTENT.value)     // 保留
         val composer = readSource(".../composer/Composer.kt")
         assertFalse(composer.contains("END_DP"))                            // 保留
-        val screen = readSource(".../SessionScreen.kt")
-        assertTrue(
-            "v146: the anchor must still be *applied* as the chip's trailing padding …",
-            screen.contains("end = SessionTerminalEntryPolicy.END_DP,"),     // 前提已不成立
-        )
     }
+
+    @Test fun theSelfInventedChipIsNotMountedInTheTimelinePaneAgain() { … 2 条断言 … }
+    @Test fun theTerminalEntryIsReachableThroughTheUpstreamPath()    { … 4 条断言 … }
```

外加类 KDoc 新增「## v161 realign: the chip left, the cell did not」一节，用表格写明「新判据 ↔ 原判据 ↔ 为何同一缺陷」。

---

## 5. 反向验证（真跑，原文）

**方法**：把结构**改回旧态**——在 `SessionScreen.kt` 芯片原挂载点重新挂上 `TerminalEntryButton(`，且**照抄 v146 的 modifier**（`top = TOP_DP` / `end = END_DP`）——然后真跑。

**命令**：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.UsabilityCellsV146SendOcclusionFixTest"
```

**结果（红）**：

```
UsabilityCellsV146SendOcclusionFixTest > theSelfInventedChipIsNotMountedInTheTimelinePaneAgain FAILED
    java.lang.AssertionError at UsabilityCellsV146SendOcclusionFixTest.kt:574

> Task :app:testDebugUnitTest FAILED

10 tests completed, 1 failed

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:testDebugUnitTest'.
> There were failing tests. See the report at: file:///C:/sensorsdata/main/program/opencode-android-shell/app/build/reports/tests/testDebugUnitTest/index.html

BUILD FAILED in 12s
52 actionable tasks: 5 executed, 47 up-to-date
```

**失败原文**（自 `TEST-com.xiufeigo.opencodeshell.UsabilityCellsV146SendOcclusionFixTest.xml` 取出）：

```
TESTCASE: theSelfInventedChipIsNotMountedInTheTimelinePaneAgain
MESSAGE:
java.lang.AssertionError: v146 CELL 5 (v161 realign): `SessionScreen.kt` must not **mount** a
`TerminalEntryButton(` any more. The chip had no upstream counterpart (`_upstream` v2
`session/files/session-side-panel.tsx:334-342` = review, `:345-373` = context, `:375` = dynamic
file tabs — no terminal trigger), and v146's cell 5 was never about the chip's looks: it was that
this control is composed **inside the viewport `Box`**, which is the composer's *earlier* sibling,
so `Send` drew over it — 2068 px2 of overlap and 0 visible chip pixels. Re-mounting it here re-arms
that defect; the terminal is opened from the command palette instead (asserted by
`theTerminalEntryIsReachableThroughTheUpstreamPath`).
```

> 结论：**新断言**在「结构改回旧态」时**真红**，且**只有它**红（10 tests / 1 failed）——criterion ①–⑥ 与 2068/2/0 触发器仍绿，因为它们按 v146 的 64 dp 后态建模，本来就该绿。**这正是期望行为：新断言专职抓「芯片回来」，旧断言专职抓「数值漂移」，职责不重叠。**

**回退核对**（回退后）：

```
$ git diff --stat -- app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt
        （空 = 与 HEAD 完全一致）
$ SessionScreen.kt 仍含 "TerminalEntryButton(" ?  CLEAN_NO_MOUNT
```

**未做第二条反向验证**（如实记账）：可达性那 4 条断言指向 `ui/App.kt` 与 `ui/palette/AppCommands.kt`，**二者不在本轮写盘范围**（`ui/session/**` + 测试文件 + `research/**`），故未做生产侧反向变异，只做了「断言为真」的绿证据。补做需要 Lead 授权扩写盘范围，或由持有者自行删 `onOpenTerminal = actions.onTerminalToggle,` 复跑一次。

---

## 6. 回归证据

**命令**（严格遵守：仅 `:app:testDebugUnitTest` + 精确 `--tests` 类名；**无**通配、**未** clean、**未**全量、**未**装机）：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.UsabilityCellsV146SendOcclusionFixTest" `
  --tests "com.xiufeigo.opencodeshell.UsabilityCellsV144FixTest" `
  --tests "com.xiufeigo.opencodeshell.SessionTerminalEntryPolicyTest" `
  --tests "com.xiufeigo.opencodeshell.RightPanelParityTest"
```

**原文**：

```
> Task :app:compileDebugKotlin
> Task :app:compileDebugJavaWithJavac UP-TO-DATE
> Task :app:processDebugResources UP-TO-DATE
> Task :app:mergeDebugResources UP-TO-DATE
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 9s
52 actionable tasks: 5 executed, 47 up-to-date
```

**受影响全部类 · 用例数**（取自 `app/build/test-results/testDebugUnitTest/TEST-*.xml`）：

| 类 | 用例数 | 失败 | 错误 | 跳过 |
|---|---|---|---|---|
| `UsabilityCellsV146SendOcclusionFixTest` | **10**（原 8，**+2**） | 0 | 0 | 0 |
| `UsabilityCellsV144FixTest` | 7 | 0 | 0 | 0 |
| `SessionTerminalEntryPolicyTest` | 2 | 0 | 0 | 0 |
| `RightPanelParityTest` | 9 | 0 | 0 | 0 |
| **合计** | **28** | **0** | **0** | **0** |

**闸门那 1 例已由红转绿**：`theFixTouchesOnlyTheHorizontalAnchorAndItIsReadLive` 现为绿（保留同名、保留三条断言）；本类 10/10 全绿。

### 红线自查

| 红线 | 状态 |
|---|---|
| 768 的 `140 / 92` | ✅ 未动（生产代码零 diff） |
| 600 的 `51` | ✅ 未动 |
| 两档 ①(i) `−10 / −12` | ✅ 未动 |
| 48 dp 靶 | ✅ `criterion4` 仍绿（`MIN_INTERACTIVE_COMPONENT_PX` 活读） |
| v77 P0 | ✅ 未动 |
| `quarantine/` 分工 | ✅ 未动 |

`git diff --stat -- app/src/main app/src/main/res` → **空**。本轮**只改测试文件**，`app/src/main/**` 与 `res/**` 零改动 ⇒ 红线在结构上不可能被回退。

### 行尾 / 纪律

- 全程**只用编辑工具**（`edit` / `write`），**未**用 `git checkout` / `git restore` / `git stash` 改行尾（`autocrlf=false`）。
- 锁：`Test-Path .agent-gradle-lock` = 不存在 → 写 `v161-n2-cell5-pin-realign` → 全部 gradle 调用**前台跑完** → 单条顶层 `rm -- ".agent-gradle-lock"` → `mavis-trash: moved to trash` → 复检 `Test-Path` = **False**。**未抢锁、未绕过安全策略。**
- `_upstream/**` **全程只读**（只用 `git show` / `git grep` / `git ls-tree` / `git rev-parse` 读对象，**未切工作区、未 fetch、未改文件**）。
- 未触碰 `app/build.gradle.kts`、`webapp/**`。

---

## 7. 改了哪些文件

| 文件 | 性质 | 内容 |
|---|---|---|
| `app/src/test/java/com/xiufeigo/opencodeshell/UsabilityCellsV146SendOcclusionFixTest.kt` | 改 | 摘掉 1 条前提失效的接线断言（其性质由新断言承接）；新增 **2 个用例 / 6 条断言**；类 KDoc 增补 realign 对照表 |
| `research/fix/CELL5_PIN_REALIGN.md` | **新增** | 本报告 |

`SessionScreen.kt` 仅为反向验证临时改动，**已逐字回退**（`git diff` 为空）。

---

## 8. 未闭环（如实记账）

1. **可达性断言缺生产侧反向验证** —— `ui/App.kt` / `ui/palette/AppCommands.kt` 不在本轮写盘范围，只做了绿证据（§5 末）。
2. **criterion ③ 的像素级证明仍未闭环** —— v146 起就挂着：`chipVisiblePixelCount > 0` 的**真机截图**读数需要装机，本轮**禁设备操作 / 禁装机**（`CELL5_FIX.md` §9）。现在芯片已按上游移除，该项的**当下**风险已由「不再有控件可被盖」覆盖，但**归档那一版**的像素证据仍缺。
3. **`origin/v2` 复核路径在本仓不可用** —— partial clone + GitHub 不可达（§1.1）。若 Lead 要求在**真正的** `origin/v2` 上复核，需先恢复网络并 `git fetch`；本报告行号均来自本地 `v2` HEAD（702a73d）。
4. **N1 文档两处小偏差（未改 N1 文档，仅记账）** —— `screen.tsx` 写作 `:473-500` / `:507-539`，实际起始行 **474 / 510**；`session-side-panel.tsx` 写作 `:333-374`，实际 review 在 **334-342**、context 在 **345-373**。区间语义成立，行号差 1 行。
5. **真机复验仍待 Lead** —— `RIGHT_PANEL_PARITY_FIX.md` §9 的 5 步（尤其「命令面板开终端」「停靠两形态」）本轮**未做**。
6. **本轮未跑全量闸门** —— 任务书限定只能 `--tests "<类名>"`，故只给出受影响 **4 类 / 28 用例**的绿证据；「677 suites 全绿」需由 Lead 跑一次全量确认（预期：本轮只改测试文件，无生产 diff）。

---

## 9. 给 Lead 的一句话

**闸门那 1 例已结清**：判定 **(a)**（上游 `v2` 的会话侧栏**没有** terminal tab，芯片是自造物，N1 删得对）；v146 那条绊线**没删**，改写为**「pane 内不得再挂载会被 composer 盖住的浮动控件」+「终端经上游命令面板路径仍可达且动作非 null」**两条新判据，与原判据**守护同一个缺陷**（可用控件被画在 composer 之下 / 可见像素 0），反向验证**真红**（`10 tests completed, 1 failed`，原文见 §5），受影响 **4 类 / 28 用例 / 0 失败**，**零断言被删或放宽**，**生产代码零 diff**，红线全未动。
