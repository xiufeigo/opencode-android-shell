# 会话 header 内联标题编辑器 ——「取消路径」取证诊断（v97 N3）

- 轮次：v97 fix / 诊断轮（**只诊断，不改代码**）
- 现象来源：`research/v97_verify/5562_sweepB/REPORT_v97b.md` §N3
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell` @ `a5322cf`
- 上游参照：`_upstream/packages/app/src/session/timeline/message-timeline.tsx`
- 本壳实现：`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt`

---

## 结论摘要

| # | 现象 | 判定 | 一句话依据 |
|---|---|---|---|
| 1 | 编辑态期间 🔍 / Ask a side question 消失 | **与上游一致（本壳有意设计）** | `SessionHeader.kt:1121-1122` `sessionHeaderActionsVisible(searching, editing) = !searching && !editing`；上游编辑器只占标题槽位，而上游**本就没有 🔍 按钮**（只有 `mod+f`，见 `SessionHeader.kt:362`），隐藏是壳自己的取舍 |
| 2 | `More options → Rename` 变 no-op | **与上游一致（真机感知为坏）** | `SessionHeader.kt:399-408` ⋮ 菜单在 `if (editing)` 分支**之外**，编辑态仍可点；但点它会夺走焦点 → `onFocusChanged` 失焦 → `commit()` → 已提交并关闭，用户再点 Rename 只是重开同一个已提交的标题，**看起来像 no-op** |
| 3 | **把文本留在框里离开（回 Home）→ 标题被提交** | **★ 真缺口（数据丢失，与上游相反）** | 上游 `message-timeline.tsx:285-289` 在 `props.active === false` 时**丢弃**草稿；本壳没有对应守卫，组合被销毁时字段失焦 → `SessionHeader.kt:553-554` `commit()` → 走 `onRename` 写服务端 |
| 4 | **没有任何「丢弃草稿」的可用出口** | **★ 真缺口** | 上游 ESC = `closeTitleEditor()`（丢弃，`message-timeline.tsx:480-483`）。本壳 ESC 分支**代码在**（`SessionHeader.kt:564-567`），但被 IME 拦在应用之外 |
| 5 | ESC 两段式（第一次只收键盘，第二次才轮到编辑器） | **部分真缺口 + 部分证据不足** | IME 优先消费 ESC 是系统行为（壳自己的 `DialogLayerPolicy.kt:180-197` 就把它写成了规则）；但**第二次** ESC 为什么仍退不出，静态读码**无法解释**——见 §6 |
| 6 | 失焦 = 提交 | **与上游一致** | 上游 `message-timeline.tsx:485` `onBlur={() => void saveTitleEditor()}` 明确就是失焦提交；本壳 `SessionHeader.kt:553-554` 逐字对齐 |

**一句话**：本壳的标题编辑器**没有「丢弃草稿」的可达出口**（上游有 ESC），且**离开页面时把草稿提交而不是丢弃**（上游相反）——这两条是真缺口；🔍 消失、Rename 像 no-op、失焦提交都是上游同款或本壳有意设计。

---

## 上游语义（文件:行号 + 原文）

全部来自 `_upstream/packages/app/src/session/timeline/message-timeline.tsx`。

### 状态机

`message-timeline.tsx:243-249`
```tsx
const [title, setTitle] = createStore({
  draft: "",
  editing: false,
  menuOpen: false,
  pendingRename: false,
})
let titleRef: HTMLInputElement | undefined
```

`message-timeline.tsx:265-283` —— **三个出口，语义各不相同**：
```tsx
const openTitleEditor = () => {
  if (!sessionID() || parentID()) return
  setTitle({ editing: true, draft: titleLabel() ?? "" })
  requestAnimationFrame(() => {
    if (!titleRef) return
    titleRef.focus()
    titleRef.select()
  })
}

const closeTitleEditor = () => {
  if (props.pending.rename()) return
  setTitle("editing", false)          // ← 只关，不写
}

const saveTitleEditor = async () => {
  if (!title.editing || props.pending.rename()) return
  if (await props.action.rename(title.draft)) setTitle("editing", false)
}
```

### 关键点 1：**ESC = 取消（丢弃草稿），不提交**

`message-timeline.tsx:471-486` —— 编辑器本体：
```tsx
onInput={(event) => setTitle("draft", event.currentTarget.value)}
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
    closeTitleEditor()        // ← 取消，丢弃 draft
  }
}}
onBlur={() => void saveTitleEditor()}   // ← 失焦 **提交**
```

要点：
- `event.stopPropagation()`（`:473`）—— 编辑器**自己吞掉 ESC**，不往全局冒泡。
- **没有取消按钮**。上游的「取消」**只有 ESC 这一个入口**。
- `onBlur` → save：**上游确实是「失焦即提交」**。

### 关键点 2：**离开会话 = 丢弃，不提交**

`message-timeline.tsx:285-289`
```tsx
createEffect(() => {
  if (props.active !== false) return
  setSummary(false)
  setTitle({ draft: "", editing: false, menuOpen: false, pendingRename: false })
})
```
`props.active === false`（会话被切走/不可见）→ 编辑器直接关、草稿清空，**不调用 `rename`**。

`message-timeline.tsx:251-263` 同样：会话 key 变化时整体重置（含 `editing: false`），也是丢弃。

**这两条是本壳缺失的、且是与上游相反的语义。**

### 关键点 3：上游编辑态下 ⋮ 菜单**依然存在**

`message-timeline.tsx:489-525` —— `SessionOverflowMenu`（`⋮`）在标题 `Show` **之外**，不受 `title.editing` 影响；`Rename` 走 `pendingRename` + `onCloseAutoFocus`（`:510-515`）重新打开编辑器：
```tsx
onCloseAutoFocus={(event) => {
  if (!title.pendingRename) return
  event.preventDefault()
  setTitle("pendingRename", false)
  openTitleEditor()
}}
```

---

## 本壳实现路径全表（进/出编辑态的每条路径）

### 状态与三个出口

`SessionHeader.kt:201-205`
```kotlin
var editing by remember { mutableStateOf(false) }
LaunchedEffect(editing) { onTitleEditing(editing) }
DisposableEffect(Unit) { onDispose { onTitleEditing(false) } }
```

`SessionHeader.kt:311-320` —— 编辑态分支：
```kotlin
if (editing) {
    InlineTitleInput(
        initial = renameSeed ?: title,
        onCommit = { renamed ->
            editing = false
            if (renamed != title) onRename(renamed)
        },
        onCancel = { editing = false },
        modifier = Modifier.weight(1f),
    )
} else { ... }
```

### 进编辑态（2 条）

| 路径 | 位置 | 说明 |
|---|---|---|
| **点标题** | `SessionHeader.kt:350` `.clickable(role = Role.Button) { editing = true }` | 上游 `message-timeline.tsx:452` `onClick={openTitleEditor}` 同款 |
| **⋮ → Rename** | `SessionHeader.kt:399-400` `onRename = { editing = true }` | 上游 `:517-525` 同款 |

### 出编辑态（全表）

| # | 路径 | 触发 | 语义 | 位置 |
|---|---|---|---|---|
| A | `onCommit` | Enter 键事件 / IME `Done` / **失焦** | **提交** `onRename` | `SessionHeader.kt:314-317`；键 `559-562`；IME `546`；失焦 `553-554` |
| B | `onCancel` | ESC 键事件 | **丢弃** | `SessionHeader.kt:318`；键 `564-567` |
| C | `onFocusChanged` 失焦 | 点空白/点任何能夺焦的东西 | **提交** | `SessionHeader.kt:553-554` |
| D | `blurTitleEditorOnOutsideTap` | 未被消费的按下 | `clearFocus()` → 走 C → **提交** | `SessionScreen.kt:2763` + `3236-3249` |
| E | ⋮ 菜单打开 | 点 ⋮ 夺焦 | 走 C → **提交并关闭** | `SessionHeader.kt:399`（在 editing 分支外） |
| F | **组合销毁（回 Home / 重开 tab）** | 导航离开 | 字段失焦 → 走 C → **提交** | 无守卫，见下 |

### 为什么 ESC / 失焦 / 导航都退不出「不提交」的形态

**ESC（B）代码是存在的**（`SessionHeader.kt:556-571`）：
```kotlin
.onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (event.key) {
        Key.Enter, Key.NumPadEnter -> { commit(); true }
        Key.Escape -> { cancel(); true }      // ← 取消分支在
        else -> false
    }
}
```
`cancel()`（`:526-528`）走 `InlineTitleEdit.cancel()`（`InlineTitleEdit.kt:71-75`），`settled` 闩锁保证后续失焦不写。

**但它到不了。** ESC 链路上游（本壳自己的代码已把规则写死）：

1. `InlineTitleInput` 打开时**无条件拉键盘** —— `SessionHeader.kt:573-576`：
   ```kotlin
   LaunchedEffect(Unit) {
       focusRequester.requestFocus()
       keyboard?.show()          // ← 键盘必起
   }
   ```
2. IME 在时，ESC 被输入法在**应用之外**消费（只收键盘）。这正是壳自己的规则：`DialogLayerPolicy.kt:180-197`
   ```kotlin
   fun collapseImeOnEscape(...): Boolean {
       if (event.type != KeyEventType.KeyDown || event.key != Key.Escape) return false
       if (!DialogEscape.consumesToCollapseIme(imeVisible) || collapsedByEscape) return false
       hideIme()
       return true               // ← 吃掉，不往下传
   }
   ```
   `ModalEscape.kt:28-29` 的注释亦然：「**IME 在时** ESC 只收键盘」。
3. 键盘落下后，ESC 才可能进到 Compose 的 preview 链。preview 是**自根向焦点**分发的（`OcPopoverDismiss.kt:42-43`：「`onPreviewKeyEvent` 是**自根向焦点**分发的，根节点永远先看到 ESC」）。链上唯一可能吞掉它的祖先是 `GlobalKeybindFocus`（`App.kt:2119-2203`）：
   - `chord = globalChord(event, keybinds)` 对裸 ESC 返回 `null`；
   - `rootChordYieldsToTextInput` 首行即 `if (chord == null || !textInputActive) return false`（`App.kt:2588`）→ 不让位判断直接放行；
   - `when (chord)` 里 `null -> false`（`App.kt:2196`）→ **根不消费 ESC**。
   - `DialogHost` 的那条 preview（`DialogHost.kt:355-371`）是**逐层**挂的（该 Box 引用 `entry.closing`/`fullContentPx`），没有弹层开时不在这棵树里，也挡不住。

   → **静态读码：第二次 ESC 应该能落到字段并取消。** 这一点与真机现象冲突，见 §6（未闭环）。

### 为什么「失焦会提交」

`SessionHeader.kt:552-555`
```kotlin
.focusRequester(focusRequester)
.onFocusChanged { state ->
    if (state.isFocused) focused = true else if (focused) commit()
}
```
这是**照抄上游** `message-timeline.tsx:485` 的 `onBlur={() => void saveTitleEditor()}`。上游失焦即提交，本壳同款。

**但本壳比上游多了一条上游没有的失焦来源**：`SessionScreen.kt:3236-3249`
```kotlin
private fun Modifier.blurTitleEditorOnOutsideTap(editing: Boolean, focusManager: FocusManager) =
    pointerInput(editing) {
        if (!editing) return@pointerInput
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.type == PointerEventType.Press && event.changes.none { it.isConsumed }) {
                    focusManager.clearFocus()      // ← 强制失焦 → commit()
                }
            }
        }
    }
```
装在 `SessionScreen.kt:2763` 的根 `Box` 上。**「清空输入框」退不出，正是这条**：`clearFocus()` → `onFocusChanged(false)` → `commit()`。

### ★ 为什么「留文本离开 → 标题被提交」（与上游相反的真缺口）

上游有守卫：`message-timeline.tsx:285-289` 在 `props.active === false` 时**丢弃**。
本壳**没有任何对应守卫**：`editing` 是 `SessionHeader.kt:201` 的局部 state，回 Home 时 `SessionScreen` 整个离开组合 → `DisposableEffect` 只做 `onTitleEditing(false)`（`:205`），**既不 commit 也不 cancel**；但字段在 detach 前先失焦 → `:553-554` `commit()` → `:314-317` `onRename(renamed)` → 写服务端。

**而且这个误提交是「粘」的**：`SessionTitleManualGuardV78Test.kt:56-58` 断言 `renameSession must file the server-echoed spelling` → `manualTitles[id] = result.title`。一次误提交会把该会话永久标记为「人工命名」，之后服务器的自动标题再也赢不了（`SessionTitleManualGuardV78Test.kt:41-44`）。这解释了 `E2ECHK5562-01` → `Queued` 之后标题就锁死了。

---

## 真机可复验的断言清单（给测试员）

前置：会话有名字（避免空 seed），窗口任意。每条先记录 `adb shell uiautomator dump` 的 header 节点。

**A1 —— 编辑态入口与出口形状**
1. 打开会话 → 点 header 标题。
2. 断言：`h1` 显示文本消失，编辑框出现且**整串全选**（`SessionHeader.kt:516` `TextFieldValue(initial, edit.entrySelection)`）。
3. 断言：🔍 / `Ask a side question` 节点消失；**⋮ 仍在**。
4. `input keyevent 66`（Enter）→ 断言：编辑框消失，标题变为输入串，tab 名同步。
   → 出口 A 成立（与上游一致）。

**A2 —— ESC 两段式（本轮核心）**
1. 重新点标题进入编辑态，输入 `ESCCHK-01`（**不要回车**）。
2. `input keyevent 111` 第一次 → 断言：键盘收起，**编辑框仍在**（这一下被 IME 吃掉，符合预期）。
3. `input keyevent 111` 第二次 → **本轮要判的**：
   - 若编辑框消失且标题**未变** → 取消路径正常，N3 的「ESC 退不出」是**旧包**现象，按 §5 方案 A 收口即可；
   - 若编辑框仍在 → 记下**键盘是否真的收起**（`dumpsys input_method | grep mInputShown`），这是区分「IME 循环」和「事件被吞」的关键证据。
4. 连按第 3、4 次 ESC，断言退出次数 = 1（不得出现「ESC 只会轮流收键盘」的死循环）。

**A3 —— 点空白（失焦提交）**
1. 编辑态输入 `ESCCHK-02`，点消息列表空白处。
2. 断言：编辑框关闭，**标题被改成 `ESCCHK-02`**（提交）。
   → 出口 C/D 成立，**与上游一致**（`message-timeline.tsx:485`）。不要当 bug 报。

**A4 —— ★ 离开页面是否误提交（最高优先级）**
1. 编辑态输入 `ESCCHK-03`（**不要回车**）。
2. 点底部 tab 回 Home（或用系统返回到 Home）。
3. 断言：**标题必须仍是原名，`ESCCHK-03` 不应出现在任何地方**（`GET /api/session` 里查）。
   - 若标题变成 `ESCCHK-03` → **确认数据丢失真缺口**（§4 方案 C）。
4. 再打开该会话 → 断言：自动标题/学习标题**还能**被采纳（这条验 §3 的 `manualTitles` 粘性）。

**A5 —— ⋮ → Rename 是不是真的 no-op**
1. 编辑态输入 `ESCCHK-04`，点 ⋮ → Rename。
2. 断言：菜单打开的瞬间编辑框**已因失焦提交并关闭**（可从标题是否已变看出），Rename 再把它打开、seed 已是 `ESCCHK-04`。
   → 说明它不是 no-op，是「提交后重开」，**与上游失焦提交同款**，但感知极差。

**A6 —— 回归护栏**
- 修完后重跑 A4 + A2；并确认「空白草稿 + 提交」仍不产生空标题（`InlineTitleEdit.kt:63-68` 空白 = cancel）。

---

## 修法建议（只写方案，不改代码）

> 三条独立可上，按性价比排序。**A 是最小修复，B/C 是语义修复。**

### 方案 A（最小）：把「已收过一次键盘」的记忆借给标题编辑器 —— 修 ESC 退不出

**问题**：编辑器一打开就无条件 `keyboard.show()`（`SessionHeader.kt:573-576`），而 IME 优先吃 ESC，编辑器没有 `DialogHost` 那样的 `collapsedByEscape` 闩锁（对比 `DialogHost.kt:364-371`）。

**改法（最小）**：在 `InlineTitleInput` 里记一个 `var imeCollapsed by remember { mutableStateOf(false) }`，`onPreviewKeyEvent` 的 Escape 分支改成两段：

```kotlin
Key.Escape -> {
    if (imeVisible() && !imeCollapsed) { imeCollapsed = true; keyboard?.hide(); true }
    else { cancel(); true }
}
```

- **复用现成件**：`DialogLayerPolicy.kt:187-197` 的 `collapseImeOnEscape(event, imeVisible, collapsedByEscape, hideIme)` 签名**完全够用**，把 `collapsedByEscape = imeCollapsed` 传进去即可，**不需要新写策略**。
- **为什么这样最省**：把「两段式」从**被 IME 决定的被动**变成**自己声明的一次性动作**，第二次 ESC 必定落到 `cancel()`。
- **风险点**：`ModalEscapePolicy` / `DialogEscape` 的 IME 语义必须与这里**保持同源**（别复制一份常量），否则会重演 v61 那个「ESC 死循环」（`EscapeOnPhoneV61Test.kt:115-125`）。

### 方案 B：给编辑器补一个**可见的取消入口**（不依赖键盘）

上游**没有**取消按钮，所以这不算偏离上游，是**纯增益**、且能同时解决「IME 吃 ESC」和「没有丢弃出口」两件事。

**改法**：`SessionHeader.kt:311-320` 的 `if (editing)` 分支里，`InlineTitleInput` 右侧加一个图标按钮（复用 `HeaderIconButton`，`:379-385`）→ `onCancel`；可访问名 `oc_cancel_rename`。

- 成本极低、零键盘依赖、点得到。
- 与方案 A 叠加后：硬件 ESC 和触屏都有丢弃路径。

### 方案 C（语义修复，**强烈建议**）：把「导航离开」从提交改成丢弃

这是**唯一与上游行为相反**的条目（上游 `message-timeline.tsx:285-289` 丢弃），也是 `E2ECHK5562-01 → Queued` 的直接成因。

**改法（三选一，推荐 b）**：
- **(a)** 在 `SessionHeader.kt:205` 的 `onDispose` 里，于销毁前先 `settle` 为 cancel —— 但 `editing` 的 `onCommit/onCancel` 在 dispose 时改 state 已无意义，**不推荐**。
- **(b) 推荐**：把「是否允许失焦提交」做成显式开关。给 `InlineTitleInput` 加 `commitOnBlur: Boolean`，由 `SessionHeader` 传一个**生命周期**信号（例如「本会话是否还是前台会话」）。导航离开时该信号已为 false → 失焦走 `cancel()` 而非 `commit()`，**1:1 复刻上游 `props.active === false` 丢弃**。
- **(c)** 更小：在 `SessionScreen.kt:3236-3249` 的 `blurTitleEditorOnOutsideTap` 旁边加一条「离开组合前先 cancel」的 `LaunchedEffect`/`DisposableEffect`，用 `InlineTitleEdit.settled` 闩锁保证后续失焦不写。

**顺带建议**（不是本轮缺陷，但同一块代码）：`SessionHeader.kt:314-316` 的 `if (renamed != title)` 只挡了「与当前显示名相同」，挡不住「与 `renameSeed` 相同的空提交」。误提交一旦发生就会被 `manualTitles` 永久固化，建议同时在 `onCommit` 里要求 `renamed` 与 `renameSeed` 不同（`App.kt:336-345` 的 `sessionRenameSeed` 正好是那个真值来源）。

### 会牵动的既有 pin（grep 结果）

| 文件:行 | pin 内容 | 受哪个方案影响 |
|---|---|---|
| `SessionTitleInlineEditTest.kt:132` | `escapeCancels()` | A、B |
| `SessionTitleInlineEditTest.kt:118` | `enterCommitsAsBothAKeyEventAndAnImeAction()` | A |
| `SessionTitleInlineEditTest.kt:58` | `escapeCancelsAndSwallowsTheBlurThatClosingTheEditorProduces()` | A、B、C |
| `SessionTitleInlineEditTest.kt:71` | `enterAndBlurStillCommit()` | **C 会碰这条**（`commitOnBlur=false` 只在「离开」时生效，**不能**把失焦提交整体关掉，否则这条 pin 的语义要重写） |
| `SessionTitleInlineEditTest.kt:81` | `aBlankDraftIsNeverSubmitted()` | A/B/C 均不碰 |
| `SessionTitleInlineEditTest.kt:89` | `enteringTheEditorSelectsTheWholeTitle()` | B（新按钮不得抢焦点，否则这条会挂） |
| `SessionTitleInlineEditTest.kt:142` | `anOutsideTapBlursTheEditor()` | **C 直接冲突**：方案 C(b) 若把 `commitOnBlur` 做成全局关闭，这条会红 |
| `SessionTitleInlineEditTest.kt:162` / `:164-168` | `theHeaderPublishesItsEditingState()`（`onTitleEditing` 接线） | B（新增按钮要保持发布语义） |
| `SessionTitleInlineEditTest.kt:172` | `theCommitStillReachesTheViewModelRename()` | B、C |
| `EscapeOnPhoneV61Test.kt:116` | `a collapsed keyboard does not trap escape in a loop on a phone` | **A 的直接对照 pin** —— A 修的正是同款循环，**它必须继续绿** |
| `EscapeOnPhoneV61Test.kt:128` | `the desktop shape still closes in one Escape` | A（桌面 IME 不在时不能变成两段！） |
| `EscapeOnPhoneV61Test.kt:146` | `the layer owns its collapsed-keyboard memory...` | A（新增的 `imeCollapsed` 应与该模式同构） |
| `ModalEscapeTest.kt:39` / `:71` | `escape only collapses the keyboard while the ime is up`、`the ime split matches the dialog host` | A（IME 语义必须与 `DialogEscape` 同源，**不可**自造） |
| `ModalEscapeTest.kt:32` | `escape closes the dialog when the keyboard is down` | A |
| `SessionTitleManualGuardV78Test.kt:56-58` | `manualTitles[id] = result.title` 接线 pin | **C**（误提交消失后该 pin 仍在，但 A4 的真机语义由它背书） |
| `SessionTitleManualGuardV78Test.kt:41` | `aLateAutoTitleThatDisagreesWithTheManualRenameIsDropped` | C（正是「粘性」被验证的地方） |

**建议的落地顺序**：A → B（两者都不改「失焦提交」语义，pin 冲突面最小）→ C（需要先决定 `commitOnBlur` 的门控粒度，会碰 `SessionTitleInlineEditTest.kt:71` 与 `:142`）。

---

## 未闭环 / 未验到

1. **【最重要】第二次 ESC 为什么退不出，静态读码解释不了。**
   我逐段走完了 preview 链：根 `GlobalKeybindFocus` 对裸 ESC 返回 `false`（`App.kt:2196` 的 `null -> false` + `:2588` 的 `chord == null` 放行），`DialogHost` 那条是逐层挂的（无弹层时不在树里），字段自己的 `Key.Escape -> cancel(); true` 在最内层。**结论：第二次 ESC 应当能取消。**
   两种未排除的可能：
   - **(i) 真机装的是旧包**，当前树里的 Escape 分支（`SessionHeader.kt:564-567`）与 `InlineTitleEdit` 闩锁是 v32 才有的（见 `:497-502` 的 KDoc：「v32 BUG-1/BUG-2：…Escape's cancel was overtaken by the blur」，说明 **v32 之前 ESC 确实是被 blur 覆盖掉的**），若实测包早于 v32，现象就完全吻合。
   - **(ii) IME 死循环**：编辑器无条件 `keyboard.show()`（`:575`），若第二次 `keyevent 111` 又把键盘拉回来（`EscapeOnPhoneV61Test.kt:25-28` 记录的正是命令面板上的同款陷阱），ESC 就会永远只在「收键盘」里打转 —— 因为标题编辑器**没有** `DialogHost` 那样的 `collapsedByEscape` 记忆。
   - **判别方法**：报告 §6 的 **A2**，尤其是第 3 步里 `dumpsys input_method | grep mInputShown` 的读数。**未跑真机（本轮纪律禁止 adb/装机），此项保持开放。**

2. **未验证「点外部」在上游的精确语义。** 上游 `onBlur` 提交是明确的，但「点到编辑器外面」在 Solid 里是否必然触发 blur、以及与 `event.stopPropagation()` 的交互，本轮**只读了源码，没有跑上游**。

3. **未验证 `home/sessions/view.tsx` 的第二个标题编辑器。** `InlineTitleEdit.kt:41-43` 的 KDoc 引用了 `home/sessions/view.tsx:481-489` 的 `input.focus(); input.select()`，说明**上游还有第二处标题编辑**。Home 侧那个编辑器是否有独立的 ESC 规则，本轮**未取证**（搜索关键词：`home/sessions/view.tsx`、`InlineInput`）。若 Home 侧也有同样问题，方案 A/B 需要成对落地。

4. **未验证 `ocPopoverDismiss`（`OcPopoverDismiss.kt`）与本问题的关系。** 该文件 `:37-43` 记录了「窗口里没有焦点归属时 `onPreviewKeyEvent` 压根不会被调用」这一 Compose 行为。我确认了编辑器**有**焦点（`:552-554` + `:574`），所以它不挡；但**没有验证**「编辑态下若 `GlobalKeybindFocus` 的 root 抢焦（`App.kt:2107-2111` 的 `rootClaimsFocus`）会不会把焦点链打断」——这是 v81 那类抢焦问题的可能变体，**未闭环**。

5. **未读上一轮报告全文。** `REPORT_v97b.md` 为 GBK 编码，我只按 `Select-String` 取到了 N3 段的行号与片段（`:224` N3 标题、`:230` 现象、`:143` Q8 提交 PASS、`:244` 刷新 toast）。N3 段内**测试员当时按了几次 ESC、用的什么 keyevent** 没有逐字读到，因此上面第 1 条的两个假设无法用报告原文进一步收窄。

6. **`git log` 只有一条提交**（`a5322cf feat: native 1:1 OpenCode frontend…`），**无法做版本考古**去确认实测包与当前树的先后关系。方案 A(i) 需要构建历史或 APK 版本号才能证伪。
