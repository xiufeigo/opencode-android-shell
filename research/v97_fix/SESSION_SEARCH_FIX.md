# v97 修复报告 — 会话时间线搜索三连缺陷（session-e8091491-5562b sweepB §3.5）

- 修复工程师：session-e8091491-5562b-fix-search
- 日期：2026-10-02（host 20:0x）
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 输入报告：`research/v97_verify/5562_sweepB/REPORT_v97b.md`（整篇已读）
- 结论一句话：**三条高危共享的不是同一簇 app 状态，而是一次坐标打偏的点击 —— `input tap 902 133` 落在会话标题按钮上而不是 🔍 搜索入口；真正的 app 缺陷是那个标题命中区把整行剩余宽度全吞了（1034×48 px 隐形热区），让「点偏 = 改名 + 回车落库」成为可能。已按上游 `w-fit` 修掉，并把两条提交出口的分离与退出复位钉成 JVM pin。**

---

## 1. 根因

### 1.0 先把「共享状态」假设否掉

派单假设：header 的搜索态与「重命名/标题编辑」态**抢同一个状态或同一个输入框宿主**。

逐条核对源码后，这个假设**不成立**——两者是两个不同的 composable、两个不同的状态持有者、两个不同的提交出口：

| | 时间线搜索 | 标题编辑 / 重命名 |
|---|---|---|
| 宿主 composable | `TimelineSearchBar`（`ui/session/header/TimelineSearchBar.kt:111`） | `InlineTitleInput`（`ui/session/header/SessionHeader.kt:512`） |
| 状态 | `object TimelineSearch` 进程级控制器（`ui/session/timeline/TimelineSearch.kt:56`） | `SessionHeader` 的局部 `var editing by remember { mutableStateOf(false) }`（`SessionHeader.kt:201`） |
| 提交出口 | `TimelineSearch.moveBy()` / `close()` / `setQuery()` | `onCommit → onRename(renamed)`（`SessionHeader.kt:314-317`）→ `chatViewModel.renameSession(...)`（`SessionScreen.kt:1587-1597`）→ `PATCH /session/:id` 写 title |

`TimelineSearchBar.kt` 全文**不出现** `onRename` / `InlineTitleEdit` / `renameSession` / `onCommit` / `editing` 任何一个标识符（已用源码 pin 固化，见 §4）。搜索输入在结构上无法走到重命名出口。

### 1.1 真因：`input tap 902 133` 命中的是**标题**，不是 🔍

测试台的动作是 `input tap 902 133`（`REPORT_v97b.md:103`），报告读作「点 🔍 `Search session`」。

在 `emulator-5562`（1366×768@160）的会话头里，节点的实际 x 区间是（uiautomator dump 原样）：

```
v97b_002_session01.xml / v97b_027_scrollup.xml
  [Button] [0,110][40,158]       desc='Back'
  [Button] [40,110][84,158]      desc='Open sessions'
  [Button] [84,110][1118,158]    text='E2ECHK5562-01'   ← 标题，x=902 落在这里面
  [Button] [1118,110][1158,158]  desc='Search session' ← 🔍 入口，中心 x=1138
  [Button] [1158,110][1198,158]  desc='Ask a side question'
  [Button] [1198,110][1238,158]  desc='Session summary'
  [View]   [1238,110][1278,158]  desc='Context usage'
  [Button] [1278,110][1326,158]  desc='More options'
```

x=902 距离 🔍 有 **236 px**，落在标题按钮内部 216 px 处。标题的 `onClick` 是：

`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt:350`
```kotlin
                    .clickable(role = Role.Button) { editing = true }
```

→ 打开的是**内联标题编辑器**，不是搜索。

### 1.2 三条症状逐条对上「标题编辑器」

**(a) 「输入框仍是编辑态、🔍 图标不回来」** —— 编辑态下这两个入口按设计就让位：

`SessionHeader.kt:363` / `:369`（改前 `:336` / `:344`）
```kotlin
        if (canSearch && !editing) {
            TimelineSearchEntry(modifier = Modifier.size(metrics.iconButtonSize))
        }
        ...
        if (!editing) SessionBtwEntry(modifier = Modifier.size(metrics.iconButtonSize))
```

dump 也证实了这一点：编辑态下 `Session summary` / `Context usage` / `More options` **仍在**（这三者不受 `editing` 门控），而 🔍 与 `Ask a side question` 消失 —— 与源码逐字一致。

**(b) 「输入 `Queued` 回车把标题改成 `Queued`（数据损坏）」** —— 编辑器的 Enter 就是提交：

`SessionHeader.kt:550-554`（`InlineTitleInput` 的 `onPreviewKeyEvent`）
```kotlin
                    Key.Enter, Key.NumPadEnter -> {
                        commit()
                        true
                    }
```
→ `onCommit` → `SessionHeader.kt:316` `if (renamed != title) onRename(renamed)` → `SessionScreen.kt:1589` `chatViewModel.renameSession(...)` → 后端 `title` 被改写。

**这与上游完全一致**，不是 Android 引入的行为 —— `_upstream/packages/app/src/session/timeline/message-timeline.tsx:472-485`：
```tsx
                        onKeyDown={(event) => {
                          event.stopPropagation()
                          if (event.isComposing || event.keyCode === 229) return
                          if (event.key === "Enter") {
                            event.preventDefault()
                            void saveTitleEditor()
                            return
                          }
                          if (event.key === "Escape") {
                            event.preventDefault()
                            closeTitleEditor()
                          }
                        }}
                        onBlur={() => void saveTitleEditor()}
```
`message-timeline.tsx:280-283`：
```tsx
  const saveTitleEditor = async () => {
    if (!title.editing || props.pending.rename()) return
    if (await props.action.rename(title.draft)) setTitle("editing", false)
  }
```

**(c) 「搜索结果永远冻结在首次查询」** —— 根本没有搜索发生过。dump 里的 `EditText` 是标题编辑器，不是搜索条，三个判据同时成立：

| 判据 | 标题编辑器（dump 实测） | 搜索条（源码应有） |
|---|---|---|
| 高度 | `[98,111][1188,159]` = **48 px**，占满 header 行高 | `BAR_HEIGHT = 28.dp`（`TimelineSearchBar.kt:266`） |
| ✕ 关闭按钮 | dump 中**没有** | `TimelineSearchBar.kt:228-233` 永远渲染一个 `oc_search_close` |
| 同一行的其它按钮 | `Session summary` / `Context usage` / `More options` **都在** | `SessionHeader.kt:304-309` `if (searching) { TimelineSearchBar(...); return@Row }` 会把这一整段连同 ring/⋮ 一起截掉 |

「时间线收敛为唯一命中」其实是**滚动位置**（045 的时间线底边 y=350，046 变成 y=720 —— 键盘被 ESC 收起后窗口回填），不是过滤。

**(d) 「ESC 只清过滤、不关输入框」** —— 这是本仓库**写明的策略**，不是缺陷。`input keyevent 111` 在 IME 可见时是「收键盘」动作：

`ui/overlay/DialogLayerPolicy.kt:187-197`
```kotlin
fun collapseImeOnEscape(
    event: KeyEvent,
    imeVisible: Boolean,
    collapsedByEscape: Boolean = false,
    hideIme: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown || event.key != Key.Escape) return false
    if (!DialogEscape.consumesToCollapseIme(imeVisible) || collapsedByEscape) return false
    hideIme()
    return true
}
```
`ui/overlay/ModalEscape.kt:28-29` 的文件头把这条分工写得很清楚：「**IME 在时** ESC 只收键盘（`input keyevent 111` 是测试台的收键盘动作，把它当关闭会误伤正在输入的重命名框）」。045→046 的时间线高度差 370 px 就是这一次收键盘。**第二次** ESC 才会到达编辑器的 `onPreviewKeyEvent` 走 `cancel()`。测试员只按了一次。

**(e) 「卡死、跨导航不恢复」「More-options → Rename 是 no-op」** —— 报告正文与自己引用的证据文件矛盾：

```
v97b_054_open_renamed.xml（回 Home 后重开会话）
  [Button] [84,110][1118,158]    text='Queued'
  [Button] [1118,110][1158,158]  desc='Search session'      ← 回来了
  [Button] [1158,110][1198,158]  desc='Ask a side question' ← 回来了

v97b_058_rename_dlg.xml（⋮ → Rename 之后）
  [EditText] [98,111][1188,159] text='Queued'  focusable=true focused=true
```
`editing` 是 `SessionHeader` 的 `remember`（`:201`），header 离开组合即复位 —— 证据 054 就是复位后的样子。而 ⋮ → Rename **是生效的**：`SessionOverflowMenu` 的 `onRename = { editing = true }`（`:373`）打开了内联编辑器并预填当前标题 `Queued`，测试员把「行内编辑器」误读成「没有弹层」。

### 1.3 真正的 app 缺陷：标题命中区吞掉了整行

上面解释了「为什么会点偏」，但点偏之所以**后果严重**，是因为那个标题的点击区是整个剩余行宽：

`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt:321`（改前）
```kotlin
                modifier = Modifier
                    .width(metrics.titleWidth)
                    .clip(OcTheme.shapes.md)
                    .clickable(role = Role.Button) { editing = true }
```

`metrics.titleWidth` 是**尾部图标簇没用掉的全部宽度**（`SessionHeader.kt:1077-1080` 的 `available = windowWidth - size * icons`）。在 1366 px 窗口上它就是 dump 里的 `[84,110][1118,158]` —— **1034 × 48 px 的隐形热区，背后只有 90 px 的字**，而且 🔍 入口就贴在它的右边缘 40 px 处。整条因果链：

```
标题 .width(剩余行宽)  →  1034×48 隐形热区
      ↓
测试台 tap 902,133 落在热区内（距 🔍 236 px）
      ↓
.editing = true  →  内联标题编辑器（48 dp 高、无背景提示）
      ↓
输入 Queued + Enter  →  commit()  →  onRename  →  renameSession
      ↓
后端 title: E2ECHK5562-01 → Queued        ← 报告里的「数据损坏」
```

上游不是这样 —— `_upstream/.../message-timeline.tsx:449-455`：
```tsx
                        <h1
                          data-slot="session-title-child"
                          class="truncate text-[13px] font-[530] leading-4 tracking-[-0.04px] text-v2-text-text-base w-fit rounded-[6px] px-1 py-1 hover:bg-v2-overlay-simple-overlay-hover"
                          onClick={openTitleEditor}
                        >
```
`w-fit` —— 命中区就是文字本身。Android 端把它撑成了 `flex-1`，这是一处**上游有、本地没有**的偏离，也正是本次唯一需要改码的缺陷。

---

## 2. 改动清单

只动 1 个源文件 + 新增 1 个测试文件。**未新增任何用户可见字符串**（`StringsLocaleParityTest` 通过）。

### 2.1 `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt`

```diff
--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt
@@ -208,8 +208,17 @@ fun SessionHeader(
     // not a parameter: the header is rendered outside the timeline on the mobile
     // form and inside its sticky slot on the desktop one).
     val searching = TimelineSearch.open
     val canSearch = TimelineSearch.available
+    // v97 (`research/v97_verify/5562_sweepB/REPORT_v97b.md` §3.5, Q4–Q10): the
+    // 🔍 / 「Ask a side question」 entries exist **only** outside the two editing
+    // states, and the row early-returns above while the search bar is up, so
+    // "these two nodes are gone" is exactly the predicate below being false.
+    // It was read off the wire as a permanently stuck header; the device dumps
+    // say the state was the **inline title editor** (see
+    // `research/v97_fix/SESSION_SEARCH_FIX.md`). The predicate is named and pure
+    // so the exit-path reset is pinned on the JVM instead of on a device.
+    val trailingActionsVisible = sessionHeaderActionsVisible(searching, editing)
     // W6: the session-action rows (撤销 / 重做 / 分叉 / 移动到…). The host is
     // published by `ui/App.kt`; without it (a preview, the legacy chat tab) the
     // four rows are simply absent and the menu keeps its three M2/A8 entries.
     // Both reads happen before the Row so the search/rename early-outs below
@@ -316,10 +325,28 @@ fun SessionHeader(
                 fontSize = 13.sp,
                 fontWeight = FontWeight(530),
                 maxLines = 1,
                 overflow = TextOverflow.Ellipsis,
+                // v97 HIGH-1 (data loss), the *actual* defect behind the
+                // "the search box renamed my session" report. The clickable box
+                // is this Text's own bounds, so sizing it with `width(...)` made
+                // it swallow **every** pixel the trailing cluster did not claim:
+                // on the 1366 px / 160 dpi window of `emulator-5562` that is
+                // `[84,110][1118,158]` — 1034 x 48 px of invisible hit area
+                // behind a 90 px string (`v97b_002_session01.xml`), and the
+                // 🔍 `Search session` entry is the 40 px at its right edge
+                // (`[1118,110][1158,158]`). A tap anywhere in that band landed on
+                // the **rename editor**, and Enter there is `saveTitleEditor` —
+                // so a mis-aimed tap rewrote the session title on the server.
+                //
+                // Upstream's title is `w-fit` (`message-timeline.tsx:451`:
+                // `truncate … w-fit rounded-[6px] px-1 py-1 hover:bg-…`), i.e.
+                // the hit area is the text. `widthIn(max = …)` restores that and
+                // keeps the v49 narrow-window clamp: [metrics] still caps the box
+                // at the leftover width, and a title longer than the cap ellipsises
+                // inside it exactly as before.
                 modifier = Modifier
-                    .width(metrics.titleWidth)
+                    .widthIn(max = metrics.titleWidth)
                     .clip(OcTheme.shapes.md)
                     .clickable(role = Role.Button) { editing = true }
                     .padding(horizontal = 6.dp, vertical = 4.dp),
             )
@@ -332,17 +359,17 @@ fun SessionHeader(

         // A2 entry: same slot the open bar takes, i.e. in front of the ring
         // (`message-timeline.tsx:544-548`). Upstream reaches the search through
         // `mod+f` only; a touch shell needs a visible button.
-        if (canSearch && !editing) {
+        if (canSearch && trailingActionsVisible) {
             TimelineSearchEntry(modifier = Modifier.size(metrics.iconButtonSize))
         }

         // `/btw` side-question entry (`ui/session/btw`): upstream opens the panel
         // through the hidden `/btw` slash command (`session/btw/model.ts`
         // `command.register`), which no composer route reaches here yet — the
         // button is the entry until it does. Renders nothing without a host.
-        if (!editing) SessionBtwEntry(modifier = Modifier.size(metrics.iconButtonSize))
+        if (trailingActionsVisible) SessionBtwEntry(modifier = Modifier.size(metrics.iconButtonSize))

@@ -1009,9 +1036,11 @@ internal fun sessionOverflowMenuOffset(
 private const val DISABLED_ALPHA = 0.5f

 /**
  * The v49 BUG-1 row budget: the touch-target size the header's icon buttons take
- * at this window width, and the width the title is measured at.
+ * at this window width, and the **ceiling** for the title's box. Since v97 the
+ * title is laid out with `widthIn(max = titleWidth)` — upstream's `w-fit` — so
+ * this is the clamp the title ellipsises inside, not a width it is stretched to.
  */
 internal data class SessionHeaderMetrics(
     val iconButtonSize: Dp,
     val titleWidth: Dp,
@@ -1069,4 +1098,25 @@ private val HEADER_ICON_SIZES = listOf(40.dp, 32.dp, 24.dp)
  * not the title — the title is how the user knows which session is open.
  */
 private val SESSION_TITLE_MIN_WIDTH = 64.dp

+/**
+ * Whether the header's **trailing action entries** — 🔍 `Search session` and
+ * 「Ask a side question」 — are on the row.
+ *
+ * They step aside for the two editing states and nothing else:
+ *
+ * - [editing] — the inline title editor has the title slot (`v32`/`InlineTitleEdit`),
+ *   and `trailingIconCount` already budgets the two entries out of the row so the
+ *   editor gets their width;
+ * - [searching] — the bar *replaces* the title **and** the ring / ⋮ cluster
+ *   (`return@Row` above), so the entries are unreachable rather than merely
+ *   crowded. Stated here so the exit path is a checkable predicate: closing the
+ *   search must put both back, and the v97 sweep read their absence as a
+ *   permanently wedged header (`research/v97_verify/5562_sweepB/REPORT_v97b.md`
+ *   Q4–Q6) when the state that had removed them was the *rename editor*, which
+ *   resets on its own.
+ *
+ * Pure so `SessionSearchStateV97Test` can pin the reset on the JVM.
+ */
+internal fun sessionHeaderActionsVisible(searching: Boolean, editing: Boolean): Boolean =
+    !searching && !editing
```

**为什么这样改是安全的（逐条）**

1. `.width(x)` → `.widthIn(max = x)`：Compose 里 `Text` 的固有宽度就是文字宽度，`widthIn(max=)` 只加一道上限。
   - 短标题：盒子 = 文字宽（原为 1034 px）→ **热区回到文字**。
   - 长标题 / 窄窗：盒子被 `titleWidth` 夹住，`maxLines = 1` + `overflow = Ellipsis` 照旧省略号 → **v49 BUG-1 的窄窗行为逐字不变**。
   - 其后的 `Spacer(Modifier.weight(1f))`（`:357`）本来就吃掉了剩余宽度，所以尾部图标簇的位置**完全不变**。
2. `trailingActionsVisible` 是纯提取：`searching == true` 时 `:304-309` 已经 `return@Row`，两处 gate 在该状态下不可达，所以 `!searching && !editing` 与原来的 `!editing` **行为等价**，零回归面。
3. `trailingIconCount` 的算式**一字未动**，`SessionHeaderNarrowTitleTest` / `SessionHeaderReviewSlotTest` 的纯函数断言原样通过。

### 2.2 `app/src/test/java/com/xiufeigo/opencodeshell/SessionSearchStateV97Test.kt`（新增，10 个用例）

覆盖派单要求的四组断言，见 §4。

---

## 3. 明确「不改」的三条

| 报告条目 | 判定 | 不改的理由（证据） |
|---|---|---|
| **高危 1** 搜索输入被提交成标题 | **不是 app bug**（本机行为与上游逐字一致），但**它暴露的真缺陷（超大标题热区）已修** | 上游 `message-timeline.tsx:472-485` 的 `onKeyDown Enter → saveTitleEditor` + `:485` `onBlur → saveTitleEditor` 就是这个契约；Android 侧 `SessionHeader.kt:550-554` 一致。触发它的是坐标打偏的点击，不是搜索态。 |
| **高危 2** 搜索结果冻结 | **不是 app bug**（搜索从未被打开） | `MessageTimeline.kt:312-314` 的 `remember(messages, searchQuery, searchLive)` 以 query 为 key，每次击键重算；`TimelineSearchBar.kt:148` 的 `onValueChange → TimelineSearch.setQuery(it)` 是唯一入口。另见 `TimelineSearchTest`（18 例）+ `TimelineSearchResetTest`（6 例）既有覆盖。 |
| **高危 3** 搜索态卡死 | **不是 app bug** | 报告引用的 `v97b_054_open_renamed.xml` 自己就显示 `Search session` / `Ask a side question` 已恢复；`editing` 是 `remember`（`SessionHeader.kt:201`），header 离开组合即复位。搜索态本身也永远有 ✕（`TimelineSearchBar.kt:228-233`）可退，不会无出口。 |

**同时明确不改的两处「有意偏离」**（避免下轮再被当 bug 提出来）：

- `if (searching) { …; return@Row }`（`SessionHeader.kt:304-309`）会在搜索态隐藏 ring / ⋮ / summary。上游是把搜索条放在**尾部簇**里、与 `SessionContextUsage` 并列（`message-timeline.tsx:546-548`：`shrink-0 flex items-center gap-2` → `{props.search}` + `<SessionContextUsage/>`），所以上游不隐藏。这是本仓库为手机窄窗**写明的**取舍（KDoc：at phone width upstream's 200 px field does not fit next to them），改动会动到窄窗布局且我无法真机复验 → 列入 §6 未闭环。
- ESC 的「先收键盘、再取消」两段式（`DialogLayerPolicy.kt:187-197` / `ModalEscape.kt:28-29`）：全 app 一致、且被 `EscapeOnPhoneV61Test` / `DialogEscapeCollapseMemoryTest` 等既有 pin 覆盖。改它属于全局键盘策略变更，超出本轮范围 → 不改。

---

## 4. 测试证据

### 4.1 命令与结果

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.SessionSearchStateV97Test" `
  --tests "com.xiufeigo.opencodeshell.TimelineSearchTest" `
  --tests "com.xiufeigo.opencodeshell.TimelineSearchResetTest" `
  --tests "com.xiufeigo.opencodeshell.TimelineRowsTest" `
  --tests "com.xiufeigo.opencodeshell.TimelineFidelityTest" `
  --tests "com.xiufeigo.opencodeshell.MessageNavRailPolicyTest" `
  --tests "com.xiufeigo.opencodeshell.HomeNavTest" `
  --tests "com.xiufeigo.opencodeshell.ChatStoreTest" `
  --tests "com.xiufeigo.opencodeshell.SessionActionsTest" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest" `
  --tests "com.xiufeigo.opencodeshell.SessionHeaderNarrowTitleTest" `
  --tests "com.xiufeigo.opencodeshell.SessionHeaderReviewSlotTest" `
  --tests "com.xiufeigo.opencodeshell.SessionTitleInlineEditTest" `
  --tests "com.xiufeigo.opencodeshell.SessionRenameSeedTest" `
  --tests "com.xiufeigo.opencodeshell.SessionOverflowMenuTest" `
  --tests "com.xiufeigo.opencodeshell.BtwPanelRenderTest" `
  --tests "com.xiufeigo.opencodeshell.OverflowMenuEdgeInsetTest" `
  --tests "com.xiufeigo.opencodeshell.PaletteEntryPointsTest" `
  --tests "com.xiufeigo.opencodeshell.HomeTitleInputV40Test" `
  --tests "com.xiufeigo.opencodeshell.OcKeyboardOptionsTest"
```

```
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 5s
52 actionable tasks: 2 executed, 50 up-to-date
```

第二轮（把其余读 `SessionHeader.kt` 的源码 pin 补齐）：

```powershell
.\gradlew.bat --console=plain :app:testDebugUnitTest `
  --tests "…NewSessionBackExitTest" --tests "…OcPopoverDismissTest" `
  --tests "…SessionContextExportMenuTest" --tests "…SessionImportEntryPointsTest" `
  --tests "…WorktreeLifecycleWiringTest" --tests "…WorktreeCreateWiringTest" `
  --tests "…Wave5IntegrationWiringTest" --tests "…SessionTitleChainV69Test" `
  --tests "…TimelineFollowTest" --tests "…SessionTitleManualGuardV78Test"
```
```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 4s
52 actionable tasks: 1 executed, 51 up-to-date
```

> 注：第二轮实际分 3 次执行（`SessionTitleManualGuardTest` 类名不存在，正确类名是 `SessionTitleManualGuardV78Test`），三次均 BUILD SUCCESSFUL，锁均在跑完立刻释放。

### 4.2 用例统计（`app/build/test-results/testDebugUnitTest/*.xml`）

**30 个类 / 345 个用例 / failures=0 / errors=0**

| 类 | 用例 | 类 | 用例 |
|---|---:|---|---:|
| **SessionSearchStateV97Test（新）** | **10** | SessionOverflowMenuTest | 6 |
| TimelineSearchTest | 18 | SessionRenameSeedTest | 12 |
| TimelineSearchResetTest | 6 | BtwPanelRenderTest | 10 |
| TimelineRowsTest | 23 | OverflowMenuEdgeInsetTest | 7 |
| TimelineFidelityTest | 40 | PaletteEntryPointsTest | 11 |
| MessageNavRailPolicyTest | 8 | HomeTitleInputV40Test | 9 |
| HomeNavTest | 8 | OcKeyboardOptionsTest | 10 |
| ChatStoreTest | 12 | NewSessionBackExitTest | 5 |
| SessionActionsTest | 12 | OcPopoverDismissTest | 20 |
| StringsLocaleParityTest | 3 | SessionContextExportMenuTest | 9 |
| SessionHeaderNarrowTitleTest | 5 | SessionImportEntryPointsTest | 13 |
| SessionHeaderReviewSlotTest | 6 | WorktreeCreateWiringTest | 6 |
| SessionTitleInlineEditTest | 10 | WorktreeLifecycleWiringTest | 5 |
| SessionTitleChainV69Test | 17 | Wave5IntegrationWiringTest | 25 |
| SessionTitleManualGuardV78Test | 6 | TimelineFollowTest | 13 |

> `compileDebugUnitTestKotlin` 成功本身就是「main 源码已按新代码编译」的证据 —— 新测试引用了本次新增的 `internal fun sessionHeaderActionsVisible`，若 main 未重编，测试编译会 unresolved reference 而失败。

### 4.3 新用例逐条对应派单要求

| 派单要求 | 用例 | 断言要点 |
|---|---|---|
| 搜索提交**不触碰**标题/重命名出口 | `the search bar has no rename outlet at all` | `TimelineSearchBar.kt` 不含 `onRename` / `InlineTitleEdit` / `renameSession` / `onCommit` / `editing`；只含 `setQuery` / `close` 两个出口 |
| 同上（语义） | `only the title editor can reach onRename, and only from the editing branch` | `onRename(renamed)` 只在 `InlineTitleInput` 的 `onCommit` 分支；显示分支不含 `onRename`；空白草稿是 cancel（`save == null`） |
| 同上（未开启的搜索是惰性的） | `a search that was never opened leaves the rename path untouched` | 未 `open()` 时 `isLive=false` / `query=""` / `count=0`；`if (searching)` 分支不成立 ⇒ bar 根本不组合 |
| 查询变化 → 结果重算 | `three different queries over one transcript give three different results` | 同一 transcript 上 `pathlib`→m1、`OpenCode`→m2、`Queued`→m3，三组 partID 互不相同；回到首个 query 结果逐字相同；无命中 query 返回空 |
| 同上（接线，不是纯函数） | `the timeline recomputes its hits on every query change` | `MessageTimeline.kt` 读 `TimelineSearch.query` 且 `remember(messages, searchQuery, searchLive)`；空 needle ⇒ `isLive=false` |
| 退出搜索 → 状态复位 | `closing the search resets the filter, the cursor and the count` | `close()` 后 `open=false` / `isLive=false` / `query=""` / `count=0` / `active=0` / `Highlight.None` |
| 同上（导航离开） | `leaving the timeline resets the search and the availability gate` | `reset()` 后 `available=false` / `open=false` / `query=""` / `count=0`；重进会话是干净的 bar |
| header 动作恢复的**谓词** | `the header action entries come back the moment the search closes` | `sessionHeaderActionsVisible(searching, editing)` 三态全钉；composable 读的是这个谓词而非字面量 |
| 源码 pin：不再有「共用提交出口」的形状 | 同上 + `the title hit area is the text, not the whole leftover row` | 见下 |
| 源码 pin：修复本体 | `the title hit area is the text, not the whole leftover row` | 标题分支含 `.widthIn(max = metrics.titleWidth)`、**不含** `.width(metrics.titleWidth)`；clickable 仍在标题上；v49 省略号契约未动 |
| 源码 pin：入口仍有自己的槽位 | `the search entry keeps its own reserved slot…` | `trailingIconCount` 仍给 🔍 留 1 个固定槽；`TimelineSearchEntry(modifier = Modifier.size(...))` 未被改动 |

---

## 5. 真机复验步骤（给 Lead / 测试员）

**前置**：先按 `uiautomator dump` 读出本轮窗口里 🔍 的真实 bounds 再点 —— 上一轮三条高危全部源于「凭印象点 902,133」。参考值（本轮 1366×768@160）：`Search session` = `[1118,110][1158,158]`，中心 **(1138, 134)**；标题 = `[84,110][1118,158]`。

### 5.1 修复本体（本次唯一改码项）

| # | 步骤 | 断言 |
|---|---|---|
| F1 | 进任意会话，`uiautomator dump` | `Button text='<标题>'` 的 bounds **宽度 ≈ 文字宽度 + 12 px**（不再横跨到 1118）。用长标题会话再验一次：长于 1078 px 时仍省略号截断，不把 `Context usage` / `More options` 挤出屏幕 |
| F2 | dump 后量 `Search session` bounds | 仍为 40×48，位于 `[1118,110][1158,158]`，`clickable=true` —— 尾部簇位置未动 |
| F3 | 点标题**正中** | 编辑器打开、预填标题、整行全选 —— 行为与改前一致 |
| F4 | 点 x=200（标题左侧空白，原热区） | **不应**打开编辑器（改前会）。这是本次修复的判定点 |
| F5 | 窄窗复验：把窗口拉到 640×1500@420dpi | 标题仍 ≥ 64 dp 且带省略号；6 个尾部按钮仍都在（v49 BUG-1 不回归） |
| F6 | 后端 `GET /api/session` | 本轮结束前后，会话 title **未被任何误点改写** |

### 5.2 高危 1（原「搜索输入被提交成标题」）—— 复验真正的搜索路径

| # | 步骤 | 断言 |
|---|---|---|
| A1 | 进会话 → 点 **x=1138, y=134**（🔍，不是标题） | header 出现 **28 dp 高**的搜索条：`EditText bounds=[…][…]` 高度 **28**（不是 48），其右侧有 `Close search` / `Next match` / `Previous match` 按钮；`Session summary` / `Context usage` / `More options` **消失**（`return@Row`） |
| A2 | 输入 `pathlib` | 时间线出现命中高亮；条上计数 `1/N` |
| A3 | 退格×7 → `the`；再退格×4 → `OpenCode`；再退格×9 → `Queued` | **每一次**命中集合与计数都随 query 变化（A2 的「冻结」是误判，应看不到） |
| A4 | 输入任意词 → `keyevent 66` | 只移动命中游标（`1/N` 变 `2/N`），**不改标题** |
| A5 | 点条上 ✕（`Close search`） | 条消失、标题回来、`Search session` / `Ask a side question` 回来、命中高亮全清 |
| A6 | 后端 `GET /api/session` | 会话 title **原样未变**（这才是高危 1 的正确断言口径） |

### 5.3 高危 2（原「结果冻结」）

| # | 步骤 | 断言 |
|---|---|---|
| B1 | 🔍 → `pathlib` | 唯一命中 `from pathlib import Path` |
| B2 | 退格×7 → `the` | 结果集**换成**含 "the" 的多条，计数变化 |
| B3 | 退格×4 → `OpenCode` | 命中 H1 `OpenCode Android Shell` |
| B4 | 退格×9 → `Queued` | 命中用户气泡 `Queued second message while streaming.` |
| B5 | 回到 `pathlib` | 结果逐字等于 B1（不是 B4 的残留） |

### 5.4 高危 3（原「搜索态卡死」）

| # | 步骤 | 断言 |
|---|---|---|
| C1 | 🔍 → 输入 `OpenCode` → `keyevent 111`（ESC） | **第一次** ESC 只收键盘：时间线视口变高，条仍在（`DialogEscape` 策略，见 §3） |
| C2 | 再按一次 `keyevent 111` | 编辑器/条**关闭**，`Search session` / `Ask a side question` 回来，`Context usage` / `More options` 仍在，**无残留命中高亮** |
| C3 | 同上，但用条上的 ✕ 关闭 | 与 C2 相同的复位（两条退出路径同一出口） |
| C4 | 开着搜索 → 回 Home → 重开会话 | header 完全正常（🔍/btw/summary/ring/⋮ 全在），**无任何搜索残留** |
| C5 | 开着搜索 → `input tap` 时间线正文 | 命中高亮仍在、条仍在、无崩溃；再按 ✕ 正常复位 |
| C6 | 搜索开着时点标题 | 标题被搜索条替换（`return@Row`），点不到；✕ 可退 |
| C7 | ⋮ → Rename（**不在**搜索态时） | 内联编辑器打开并预填当前标题、`focused=true`（对照 `v97b_058_rename_dlg.xml` 的正确读法） |

---

## 6. 未闭环 / 未验到

1. **搜索功能本身从未被真机跑过**（本轮之前）。`TimelineSearch` 的纯逻辑与 `MessageTimeline` 的接线都有 JVM pin，但**渲染层**（28 dp 条、命中高亮、reveal 滚动、`1/N` 计数、✕/↑/↓ 按钮）没有任何真机证据。§5.2–§5.4 是给下一轮测试员的正式复验清单。
2. **搜索态隐藏 ring / ⋮ / summary**（`SessionHeader.kt:304-309` 的 `return@Row`）是写明的取舍，但上游（`message-timeline.tsx:546-548`）不隐藏。本轮**未改** —— 改了会动窄窗布局且我无法真机复验，收益/风险不划算。留给 Lead 决策是否单开一轮。
3. **ESC 两段式**（先收键盘再取消）是全 app 一致的既有策略，本轮**未改**。若 Lead 认为「标题编辑器应该一次 ESC 就取消」，那是一次全局键盘策略变更，需要同时改 `collapseImeOnEscape` / `ocModalEscape` / `ocPopoverDismiss` 三处并重跑 `EscapeOnPhoneV61Test` 等既有 pin —— 不应夹在本轮里做。
4. **未装机、未跑 adb**（按纪律）。所有结论来自既有 dump 证据 + 源码 + JVM pin。
5. **`SessionScreen.kt:3222` 一带的 tap-away**（`onTitleEditing` 武装的外部点击层）本次未逐行核；设备证据 054 显示离开会话后状态能复位，故不阻塞。
6. 后端 `title` 被 `Queued` 覆盖的那条会话（`ses_f040bf529ffeTya7uOPV8Cjuaa`）**未回滚** —— 回滚需要写操作，不在本轮授权内；`E2ECHK5562-01b`（Q8 改名后的值）也保持原样。

---

## 7. 纪律自检

- gradle：仅 `:app:testDebugUnitTest` + 精确 `--tests`；每次都先 `Set-Content .agent-gradle-lock "fix-search"`、跑完立刻 `Remove-Item`（已确认 `lock released cleanly`）；全程 `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`。
- 未跑 `assembleDebug` / `clean` / 全量测试 / 装机 / adb。
- 未碰：`_upstream/`（只读）、`app/build.gradle.kts`、`data/HomeProjectStore.kt`、`ui/home/**`、`ui/session/composer/AgentChip.kt`。
- 未新增用户可见字符串（`StringsLocaleParityTest` 3 例通过）。
- 未降低任何既有断言强度（既有测试只增不改，全部原样通过）。
