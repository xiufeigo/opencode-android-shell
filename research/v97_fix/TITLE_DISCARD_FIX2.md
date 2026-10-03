# 会话标题内联编辑器 —— 「导航离开仍提交草稿」二次修复（v97 N3 fix2）

- 轮次：v97 fix / B4 回归修复
- 故障报告：`research/v97_verify/5556_v98/REPORT_FIXES.md` **B4 段**（`12:26:07` 与 `12:39:01` 两次 FAIL）
- 上一轮（**判定不成立**）：`research/v97_fix/TITLE_EDITOR_DISCARD_FIX.md`
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 上游参照：`_upstream/packages/app/src/session/timeline/message-timeline.tsx:285-289`（离开即丢弃）、`:485`（`onBlur` → `saveTitleEditor`）
- 纪律：未动 `_upstream/` / `app/build.gradle.kts` / `ui/home/**` / `ui/nav/**` / `ui/App.kt` / `ui/servers/**` / `ui/session/review/**`；**未新增任何用户可见字符串**；**未降低任何既有断言强度**（见 §5.2）

---

## 0. 一句话结论

上一轮修的**不是真机跑的那条路径**。真机上「点 Home」根本不是靠**销毁**触发的失焦，而是首页按钮这个 **`Modifier.clickable` 抢走了焦点** —— 那时组合**还活着**，`leaving` 闩锁**根本还没上闩**，写入早已发出。本次把提交条件从「发生了失焦」改成「**有一个明确的授权**」，只有 `SessionScreen` 自己的点外失焦才带授权，Home / tab / 返回 / 抽屉这些 chrome 抢焦点一律**丢弃**。

---

## 1. 真机路径全表（所有能把标题写下去的出口）

| # | 路径 | 文件:行号 | 原文 / 要点 | 「点 Home」时会不会真的写下去 |
|---|---|---|---|---|
| **P1** | **chrome 按钮抢焦点 → 失焦提交** | `SessionHeader.kt:553-555`（`onFocusChanged`）→ `:521-524`（`commit()`）→ `:314-317`（`onCommit`）→ `SessionScreen.kt:1587-1599`（`onRename`）→ `ChatViewModel.kt:4316` `renameSession` → `V2Transport.kt:318` → `V2Api.kt:967`（`PATCH /session/{id}`） | `if (state.isFocused) focused = true else if (focused) commit()`；`commit()` 里 `val decision = edit.commit(value.text)` | ✅ **就是这条**。Home 按钮 = `Modifier.clickable` Box，Compose 的 clickable **会夺焦**；会话**尚未销毁**，`edit.commit()` 里 `leaving == false`、`settled == false` ⇒ 返回 `Decision(close=true, save=draft)` ⇒ 直接落库 |
| P2 | 销毁引发的 detach 失焦 | `SessionHeader.kt:589-591`（`onDispose { edit.leave() }`） | 上一轮**押的**路径 | ❌ **不是这条**。它排在 P1 **之后**；等销毁发生时 `settled` 已为 true，`leave()`→`cancel()` 返回 `Decision(close=false)`，是空操作 |
| P3 | 会话内点外失焦 | `SessionScreen.kt:3236-3249` `blurTitleEditorOnOutsideTap` → `focusManager.clearFocus()` → P1 | 上游 `onBlur → save` 语义，**必须保留** | ❌ 不是（Home 按钮在 SessionScreen 组合**之外**，走不到这个 pointerInput） |
| P4 | 输入框失去焦点的**其它**来源（composer 的输入框等） | 同 P1 的 `onFocusChanged` | 与 P1 同一出口 | ❌ 本次不在触发序列内，但**同根因**，同一道栅栏一起挡住 |
| P5 | 回车 / IME `Done` | `SessionHeader.kt:546`（`onDone = { commit() }`）、`:556-562`（`onPreviewKeyEvent` 的 `Key.Enter`） | **B5 正路径** | ❌ 不是，但**必须保持 PASS** |
| P6 | ESC | `SessionHeader.kt:564-567` → `cancel()` | 丢弃 | ❌ 不是 |
| P7 | `SessionScreen` 导航 / tab 切换副作用 | — | **查无此路径**：`SessionScreen` 内没有任何在导航时写标题的副作用 | ❌ 不是 |
| P8 | `ChatViewModel` 的 title 同步 / `sessionTitleSync` | `ChatViewModel.kt:346-372`、`manualTitles` | 只**读**、只回显；写入口只有 `renameSession` | ❌ 不是（但是**放大器**：一次误提交会把 `manualTitles` 粘住，从此任何服务端标题都盖不过它 —— `SessionTitleManualGuardV78Test.kt:41-58`） |
| P9 | `SessionHeaderMetrics` / tab 标题镜像 | `sessionHeaderMetrics(...)` / `ui/App.kt` 的 `applyTabRename` | 只算宽度、只镜像**已经落库**的结果 | ❌ 不是（**症状**，非原因：它把已写坏的标题同步到了 tab 和 Home 行，所以 B4 三个断言一起红） |

**结论：真正把值写下去的是 P1 —— chrome 的 clickable 抢焦点产生的失焦，走的是和「会话内点外」完全相同的出口。上一轮的 P2 栅栏位于 P1 下游，物理上不可能拦住它。**

---

## 2. 根因

上游（Web）里 `onBlur → save` 成立，是因为「blur」只意味着「用户点了本页面别处」。本壳不成立：导航 chrome 在会话组合**之外**，由一串 `Modifier.clickable` 组成，而 **clickable 会夺焦**。于是「离开会话」和「会话内点外」在字段看来是**同一个事件**，无法区分。

`focused`（`var focused`）也救不了：它是 v32 的「曾经获得过焦点」闩锁，失焦后**仍为 true**，且它在 `onFocusChanged` 里被读取，写入 `InlineTitleEdit` 之前就已经晚了 —— 决策点只有一个，就是 `commit()`。

---

## 3. 改动

修法：**把「失焦」从提交理由降级为「需要授权的请求」**。不是延迟一帧碰运气，而是把「谁能授权」这件事写死在调用链上。

### 3.1 新增 `ui/session/header/TitleEditFence.kt`（纯 Kotlin，无 Compose，可 JVM 驱动）

```kotlin
class TitleEditFence {   // public：`SessionHeader` 是 public 函数，internal 会编译不过
    var armed: Boolean = false
        private set
    fun arm() { armed = true }                 // 只有 SessionScreen 的点外失焦会调
    fun disarm() { armed = false }              // 重新获焦 / 销毁时丢弃未用完的授权
    fun consumeBlurSave(): Boolean {            // 单次消费
        val wasArmed = armed
        armed = false
        return wasArmed
    }
}
```

### 3.2 `ui/session/header/SessionHeader.kt`

新增 `SessionHeader(titleEditFence: TitleEditFence? = null)`，透传给 `InlineTitleInput`；后者新增两个点：

```diff
+    var fieldFocused by remember { mutableStateOf(false) }

     fun commit() {
+        if (!fieldFocused && titleEditFence?.consumeBlurSave() != true) {
+            discard()
+            return
+        }
         val decision = edit.commit(value.text)
         if (decision.save != null) onCommit(decision.save) else if (decision.close) onCancel()
     }
+
+    fun discard() { if (edit.leave().close) onCancel() }
```

```diff
             .onFocusChanged { state ->
                 if (state.isFocused) focused = true else if (focused) commit()
             }
+            .onFocusEvent { state ->
+                fieldFocused = state.isFocused
+                if (state.isFocused) titleEditFence?.disarm()
+                false
+            }
```

```diff
     DisposableEffect(Unit) {
-        onDispose { edit.leave() }
+        onDispose {
+            titleEditFence?.disarm()
+            edit.leave()
+        }
     }
```

### 3.3 `ui/session/SessionScreen.kt`

```diff
-    var titleEditing by remember { mutableStateOf(false) }
+    var titleEditing by remember { mutableStateOf(false) }
+    val titleEditFence = remember { TitleEditFence() }
```

```diff
                 onTitleEditing = { titleEditing = it },
+                titleEditFence = titleEditFence,
```

```diff
 private fun Modifier.blurTitleEditorOnOutsideTap(
     editing: Boolean,
     focusManager: FocusManager,
+    titleEditFence: TitleEditFence,
 ): Modifier = pointerInput(editing) {
+    titleEditFence.disarm()      // 每次 editing 翻转都重入：新开的/已关的编辑器都不带旧 arm
     if (!editing) return@pointerInput
     awaitPointerEventScope {
         while (true) {
             val event = awaitPointerEvent(PointerEventPass.Final)
             if (event.type == PointerEventType.Press && event.changes.none { it.isConsumed }) {
+                titleEditFence.arm()
                 focusManager.clearFocus()
             }
         }
     }
 }
```

---

## 4. 为什么这次能拦住真实路径

1. **栅栏在写入之前，不在之后。** 上一次的闩锁挂在**销毁**上，而 P1 的写入发生在**销毁之前**——顺序上不可能拦住。新的判断在 `commit()` 的**第一行**、在 `edit.commit(value.text)` **之前**返回：授权没有就 `discard()`，`Decision(save=…)` 根本不会被构造出来，`onRename` 不可达。
2. **失焦本身不再构成提交理由。** 无论焦点是被谁拿走的（Home、tab、返回、抽屉、composer、销毁 detach），`fieldFocused == false` 且无人授权 ⇒ 丢弃。Home 按钮那条路**必须**走 `onFocusChanged`（它的 clickable 夺焦），所以它**必然**撞上这道门。
3. **授权是单次消费，不是时序。** `consumeBlurSave()` 读后即清：一次点外失焦的授权不可能被下一次失焦继承。不存在「这一帧先到还是那一帧先到」的赌注。
4. **授权的产生与消费是同一段同步代码里的相邻两句**（`titleEditFence.arm()` 紧接 `focusManager.clearFocus()`），不跨帧、不进协程。
5. **未用完的授权会随编辑器一起死**（重新获焦 `disarm()`、销毁 `disarm()`），不会给下一次编辑开后门。
6. **回车不可能被这道门吞掉。** Enter / IME `Done` 到达时字段**仍然持有焦点**（`fieldFocused == true`），根本不走授权判断 —— B5 的 PASS 是靠「聚焦态」这个独立维度保住的，不依赖任何 arm。
7. **没有新增 arm 点。** 全仓只有 `SessionScreen` 的点外失焦会 `arm()`，并有 pin 钉住「恰好一处」。将来再加导航按钮，它默认就是**丢弃**。

---

## 5. 测试证据

### 5.1 命令

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.SessionTitleDiscardV97Test" `
  ... （共 28 个测试类，见下）
```

### 5.2 既有 pin 改动（一处，必要且未削弱）

本轮**改了 `SessionTitleInlineEditTest.kt` 的一行字面量**：`anOutsideTapBlursTheEditor` 里
`".blurTitleEditorOnOutsideTap(titleEditing, focusManager)"` → `".blurTitleEditorOnOutsideTap(titleEditing, focusManager, titleEditFence)"`。
**理由**：`blurTitleEditorOnOutsideTap` 必须拿到 fence 才有东西可授权，这是签名变更的必然结果；
断言强度**未变**（仍然逐字钉住调用点），同测试内其余 4 条断言（`onTitleEditing`、`PointerEventPass.Final`、
`event.changes.none { it.isConsumed }`、`focusManager.clearFocus()`、`if (!editing) return@pointerInput`）**一条未动**。

> 为保住最后一条 pin（`if (!editing) return@pointerInput` 逐字），屏幕侧的清理**没有**写成多行 if 块，
> 而是放在早返回**之前**一行 `titleEditFence.disarm()` —— `pointerInput(editing)` 每次 `editing` 翻转都会
> 重新进入该块，新开的编辑器和已关闭的编辑器都因此不会带着上一个 arm。语义等价，且不破坏既有字面量。

除此之外**没有修改任何既有测试文件**。修法刻意落在纯类 + `commit()` 内部，因此该文件另外三条
逐字 pin 全部原样存活：

- `if (state.isFocused) focused = true else if (focused) commit()`（观察移到独立的 `.onFocusEvent`，不碰这一行）
- `val decision = edit.commit(value.text)`
- `if (decision.save != null) onCommit(decision.save) else if (decision.close) onCancel()`

上一轮那两个**钉在无效路径上**的源 pin（`onDispose { edit.leave() }` 字面量）被**加强重写**为
「`disarm()` 先于 `leave()`、且在 `DisposableEffect`/`onDispose` 块内、不写 `onCommit`/`value`」，
并**新增**钉住真实路径的 pin —— 是替换+新增，不是删除。

### 5.3 新增/改写的 pin（`SessionTitleDiscardV97Test.kt`，27 条）

钉住**真机路径**的核心几条：

- `anUnarmedBlurIsAFocusStealAndDiscardsTheDraft` —— 复刻 B4 原始序列（获焦 → 输入 `E2ECHK5556v98_discard` → **无授权**失焦），断言 `renames` 为空
- `anUnarmedBlurDoesNotCommitEvenWhenTheCompositionIsStillAlive` —— 钉死 fix1 搞错的那条事实：**组合还活着时写入就发出去了**，任何依赖销毁事件的修法在这个状态下都看不见
- `theChromeNeverArmsTheFenceItself` —— 全仓 `arm()` **恰好一处**，且不在 header 里
- `anArmIsSpentByTheBlurItWasGivenFor` / `disarmingDropsAnUnspentAuthorization` —— 单次消费 + 随编辑器消亡
- `aHostWithoutTheTapAwayHandlerDiscardsRatherThanSaves` —— `titleEditFence == null` 时**丢弃**（防「忘了传就退回保存」把数据丢失 bug 放回来）
- `enterCommitsWithNothingArmedAndTheEditorStillFocused` —— **B5 反向护栏**：回车在无授权时仍必须提交
- `theArmedTapAwayAndTheUnarmedBlurDifferOnlyByTheAuthorization` —— 同一个编辑器、同一个草稿、同一次失焦，**唯一变量是授权**：一个提交一个丢弃
- `theSaveIsNeverDeferredToALaterFrame` —— 明确禁止 `withFrameNanos` / `delay(` / `LaunchedEffect` 式的「下一帧再提交」
- `theScreenArmsTheFenceInTheSameBreathAsItClearsFocus` —— 源码 pin：`arm()` 位置早于 `clearFocus()`，且两者之间无跨帧构造
- `theBlurIsGatedOnAnAuthorizationAndNotOnTiming` —— 源码 pin：栅栏**早于** `edit.commit(value.text)`

### 5.5 BUILD 原文与用例数

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain :app:testDebugUnitTest --tests ... (28 个类)
```

```
ACQUIRED LOCK after 45 s
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 8s
GRADLE_EXIT=0
```

**28 个测试类 / 262 条用例 / 0 失败 / 0 错误。**

| 测试类 | tests | fail | | 测试类 | tests | fail |
|---|---|---|---|---|---|---|
| **SessionTitleDiscardV97Test（本轮重写扩充）** | **27** | **0** | | SessionContextExportMenuTest | 9 | 0 |
| SessionTitleInlineEditTest | 10 | 0 | | SessionHeaderNarrowTitleTest | 5 | 0 |
| SessionTitleManualGuardV78Test | 6 | 0 | | SessionHeaderReviewSlotTest | 6 | 0 |
| EscapeOnPhoneV61Test | 7 | 0 | | SessionImportEntryPointsTest | 13 | 0 |
| OcPopoverDismissTest | 20 | 0 | | SessionOverflowMenuTest | 6 | 0 |
| StringsLocaleParityTest | 3 | 0 | | SessionRenameSeedTest | 12 | 0 |
| SessionSearchStateV97Test | 10 | 0 | | SessionTerminalEntryPolicyTest | 2 | 0 |
| HomeRenameCommitV43Test | 10 | 0 | | TabRenameFieldSelectionTest | 6 | 0 |
| HomeTitleInputV40Test | 9 | 0 | | TerminalTabStripV47Test | 9 | 0 |
| MobileTitlebarRowTest | 8 | 0 | | Wave5IntegrationWiringTest | 25 | 0 |
| NewSessionBackExitTest | 5 | 0 | | WorkspacesToolbarColumnsTest | 5 | 0 |
| OcKeyboardOptionsTest | 10 | 0 | | WorktreeCreateWiringTest | 6 | 0 |
| OverflowMenuEdgeInsetTest | 7 | 0 | | WorktreeLifecycleWiringTest | 5 | 0 |
| PaletteEntryPointsTest | 11 | 0 | | BtwPanelRenderTest | 10 | 0 |
| **合计** | | | | **28 类 / 262 条** | **0 失败** |

### 5.6 迭代记录（留档：三次红→绿，每一条都是真问题）

1. **首跑 `BUILD FAILED`，2 个编译错**：`TitleEditFence` 声明为 `internal` 而 `SessionHeader` 是 `public`（暴露 internal 类型）→ 改为 `public class`；`discard()` 声明在 `commit()` **之后**，Kotlin 局部函数不能前向引用 → 上移。**这是真 bug，不是测试问题。**
2. **次跑 `262 tests completed, 4 failed`**：三条是**我自己的 pin 太天真** —— (a) `edit.leave()` 现在出现两次（`discard()` 里 + dispose 里），`indexOf` 抓到前者 → 改 `lastIndexOf`；(b) 同上；(c) 我断言 `editor()` 里不得出现 `LaunchedEffect`，但该 composable **本来就有**一个用于 `requestFocus()`/`keyboard.show()` 的 `LaunchedEffect`（v31 起就有）→ 断言改为「该协程块内不得出现 `commit`/`discard`/`onCommit`」，**这比原来的禁止出现更强、更准**。第四条见下。
3. **`SessionTitleInlineEditTest.anOutsideTapBlursTheEditor` 红**：我把屏幕侧的清理写成多行 `if (!editing) { …; return@pointerInput }`，破坏了它逐字 pin 的 `if (!editing) return@pointerInput`。**处置：不削弱该 pin**，把 `disarm()` 挪到早返回**之前**的一行，既保住字面量又保住清理语义（`pointerInput(editing)` 每次翻转都重入）。同时把该测试的调用点断言更新为三参形式（§5.2）。
4. **末跑 `BUILD SUCCESSFUL`，262/0**。

---

## 6. 真机复验步骤（测试员）

前置：选一个**从未被误提交过**的会话（旧的误提交已被 `manualTitles` 粘住，代码修复不会追溯回滚）。先记下 `GET /api/session` 里它的 `title` 作为「原名」基准。

**R1 —— ★ 离开即丢弃（B4 本体）**
1. 打开会话 → 点 header 标题进入编辑态。
2. 输入 `E2ECHK5556v98_discard`（**不要回车**，不要点会话内部）。
3. 点 **Home 按钮** `[8,30][56,78]`。
4. 断言 A：Home 列表该行标题 = **原名**。
5. 断言 B（服务端）：`GET /api/session` 的 `title` = **原名**。
6. 断言 C：重新进入该会话 → header 标题 = **原名**，tab 名 = **原名**，编辑器**未**自动打开。

**R2 —— ★★ 切 tab 再切回来（同一根因的不同触发路径，必须一起验）**
1. 同一会话点标题进入编辑态，输入 `E2ECHK5556v98_tab`。
2. 点底部/顶部 tab 条上的**另一个 tab**（不是 Home），再切回来。
3. 断言：标题 = **原名**（Home 不涉及 header 重建，tab 切换会，**这条比 R1 更可能复现原 bug**）。
4. 服务端 `title` 仍为**原名**。

**R3 —— ★ 回车仍必须提交（B5 不得回归）**
1. 点标题 → 输入 `E2ECHK5556v98_enter` → `input keyevent 66`。
2. 断言：编辑框关闭，标题变为该值，tab 名同步，`GET /api/session` 已落库。
3. 断言：**只落库一次**（重复查询，标题稳定）。

**R4 —— 回车提交后立刻离开，不得二次写入**
1. 点标题 → 输入 `E2ECHK5556v98_enter2` → 回车 → 立刻点 Home。
2. 断言：标题 = `E2ECHK5556v98_enter2`（一次落库，无二次回写）。

**R5 —— 会话内点外**仍**提交（反向护栏，防修过头）**
1. 点标题 → 输入 `E2ECHK5556v98_outside` → 点消息列表空白处（会话内，非 chrome）。
2. 断言：编辑框关闭且标题**被改成**该值（提交）。→ 这是上游 `onBlur → save`，**不是 bug**；若此处变成不提交，说明栅栏收得过宽。

**R6 —— ESC 仍丢弃**
1. 点标题 → 输入 → `input keyevent 111`（IME 在时第一次只收键盘）→ 再按一次 ESC。
2. 断言：编辑框关闭且标题**未变**。

**R7 —— 空白草稿**
1. 点标题 → 全选删除（留空）→ 回车。断言：标题仍是**原名**。

> 若 R1/R2 通过但 R5 也变成不提交 ⇒ 栅栏把「会话内点外」一起挡了，需回报。
> 若 R3 失败 ⇒ `fieldFocused` 判定有误，需回报。

---

## 7. 未闭环

1. **真机未跑**（本轮纪律禁止 adb/装机）。§6 全部为**待执行**步骤；这是本次修复唯一的最终判定。
2. **P4 类的其它夺焦点来源**（会话内另一个可聚焦控件，如 composer 输入框）：本轮统一**改为丢弃**。与上游 `onBlur → save` 在这一小类场景下不再 1:1（原先是提交）。理由：这些目标无法与「离开会话」区分，而误提交的代价（`manualTitles` 永久粘住）远大于丢一次草稿。若产品要求这类场景仍提交，需要单独的授权源，超出本轮范围。
3. **ESC 两段式**（IME 优先消费 ESC）未动，属独立议题。
4. **已被误提交的会话不会自动回滚**，需测试员换干净会话复验（§6 前置）。
5. **tab 条自身的重命名入口**（`onRenameSessionTab`，`ui/nav/**`）是另一条独立写路径，不在本次栅栏覆盖范围内 —— 它有自己的对话框确认，不涉及本编辑器。
6. **`ui/App.kt` / `ui/nav/**` 为禁写区**，故未采用「在导航处显式通知编辑器丢弃」的方案；当前方案不依赖它们，**任何新增导航按钮自动继承丢弃语义**。

---

## 8. 文件清单

| 文件 | 变更 |
|---|---|
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/header/TitleEditFence.kt` | **新增**，单次消费的授权闩锁 |
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt` | `SessionHeader` +`titleEditFence` 参数；`InlineTitleInput` +`fieldFocused`、+`discard()`、`commit()` 前置栅栏、+`onFocusEvent` 观察、dispose 内 `disarm()` |
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt` | `titleEditFence` 实例并透传给 header 与点外失焦；`blurTitleEditorOnOutsideTap` 在 `clearFocus()` 前 `arm()`、编辑器关闭时 `disarm()` |
| `app/src/test/java/com/xiufeigo/opencodeshell/SessionTitleDiscardV97Test.kt` | **重写扩充**：12 → 27 条，钉住真实路径；无效路径的源 pin 被**加强**重写 |
| `app/src/test/java/com/xiufeigo/opencodeshell/SessionTitleInlineEditTest.kt` | **一处字面量**：`blurTitleEditorOnOutsideTap(...)` 调用点补 `titleEditFence` 实参（签名变更所迫，见 §5.2，强度未变） |
| 其它既有测试文件 | **零改动** |
