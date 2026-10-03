# 会话 header 内联标题编辑器 ——「离开即丢弃」修复（v97 N3）

- 轮次：v97 fix / 修复轮
- 诊断输入：`research/v97_fix/TITLE_EDITOR_CANCEL_DIAG.md`（只诊断轮）
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 上游参照：`_upstream/packages/app/src/session/timeline/message-timeline.tsx`
- 纪律：本轮**未**动 `DialogLayerPolicy` / `ModalEscape` / `ocPopoverDismiss`，**未**新增用户可见字符串，**未**降低任何既有 pin 强度

---

## 结论摘要

| 诊断条目 | 本轮处置 | 结论 |
|---|---|---|
| **#3 离开页面把草稿提交**（★ 真缺口） | **已修** | 离开/组合销毁 → 丢弃草稿，`onRename` 不再被调用 |
| #4 没有丢弃出口 | **部分** | ESC 出口本就在（`SessionHeader.kt:564-567`），本轮**未**新增 UI 入口（理由见 §5） |
| #5 ESC 两段式 | **未动** | 属证据不足且涉及全局 ESC 契约，超出本轮授权 |
| #6 失焦 = 提交 | **刻意保留** | 这是上游语义（`message-timeline.tsx:485`），只把「**由销毁引发的**失焦」改道，不是把失焦提交关掉 |

**一句话**：离开会话现在**丢弃**草稿而不是提交（1:1 复刻上游 `props.active === false` 守卫），回车提交这条正路径原样保留，会话内的点外失焦提交也原样保留。

---

## 1. 根因复核（以盘上代码为准，行号已漂移处已标注）

诊断报告的行号经复核**全部命中**，无需修正。

| 锚点 | 诊断报告 | 盘上实际 | 复核 |
|---|---|---|---|
| `editing` 局部 state | `SessionHeader.kt:201-205` | `SessionHeader.kt:201-205` | ✅ 一致 |
| `InlineTitleInput` 编辑态分支 / `onRename` 提交出口 | `:311-320` | `:311-320` | ✅ 一致 |
| 失焦提交 `onFocusChanged` | `:552-555` | `:552-555` | ✅ 一致 |
| ESC 取消分支 | `:556-571` | `:556-571` | ✅ 一致 |
| 无条件 `keyboard.show()` | `:573-576` | `:573-576` | ✅ 一致 |
| 销毁时**无守卫**（只有 `onTitleEditing(false)`） | `:205` | `SessionHeader.kt:205` `DisposableEffect(Unit) { onDispose { onTitleEditing(false) } }` | ✅ 一致 |
| `InlineTitleEdit` 的 `settled` 闩锁 | `InlineTitleEdit.kt:71-75` | `InlineTitleEdit.kt:71-75`（修前） | ✅ 一致 |
| **上游离开即丢弃** | `message-timeline.tsx:285-289` | `message-timeline.tsx:285-289` 逐字一致 | ✅ 一致 |

上游原文（复核时重读，未漂移）：

```tsx
// _upstream/packages/app/src/session/timeline/message-timeline.tsx:285-289
createEffect(() => {
  if (props.active !== false) return
  setSummary(false)
  setTitle({ draft: "", editing: false, menuOpen: false, pendingRename: false })
})
```

### 因果链（复述，供复核对照）

```
导航离开
  → SessionScreen 离开组合
  → 焦点字段被 detach，FocusTargetNode.onDetach 强制清焦（refreshFocusEvents = true）
  → onFocusChanged 收到 isFocused == false
  → SessionHeader.kt:554 commit()
  → SessionHeader.kt:314-317 onCommit → if (renamed != title) onRename(renamed)
  → SessionScreen renameSession(...) 写服务端
```

关键点：本壳**没有**上游那样的 `active === false` 守卫，销毁引发的失焦于是**顶替**了一个并不存在的守卫，把提交写进了服务器。`SessionTitleManualGuardV78Test.kt:41-58` 的 `manualTitles` 粘性会把这一次误提交永久固化——这解释了 `E2ECHK5562-01` 变成 `Queued` 之后标题锁死。

---

## 2. 改动（最小，2 个文件 + 1 个新测试）

修法选了诊断报告**方案 C(b) 的 Android 拼写**：Compose 没有可读的 `active` prop，但**销毁本身就是那个事件**，且它和 detach 落在同一个 `applyChanges` pass 里——早于失焦回调被派发。决策落在纯类 `InlineTitleEdit` 里，因此 `SessionHeader` 的决策管线**一行未改**，所有既有 pin 的字面量断言原样存活。

### 2.1 `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/header/InlineTitleEdit.kt`

新增 `leaving` 状态与 `leave()` 出口；`commit()` 增一道守卫。

```diff
     /** True once a commit or a cancel ran. See the class KDoc. */
     var settled: Boolean = false
         private set
 
-    /** Enter, the IME's `Done` action, or a blur. */
+    /**
+     * True once [leave] ran — the editor's composition is being torn down because
+     * the session is no longer the one on screen. See [leave].
+     */
+    var leaving: Boolean = false
+        private set
+
+    /**
+     * **Navigating away discards the draft** (v97 N3, …).
+     * …（KDoc 引用 message-timeline.tsx:285-289 与 E2ECHK5562-01 事故）
+     */
+    fun leave(): Decision {
+        leaving = true
+        return cancel()
+    }
+
+    /**
+     * Enter, the IME's `Done` action, or a blur.
+     *
+     * Not a blur *caused by leaving*: [leave] already latched, and this keeps that
+     * promise independently of the [settled] latch so the discard is a property of
+     * the state machine and not of a dispatch order.
+     */
     fun commit(draft: String): Decision {
+        if (leaving) return cancel()
         if (settled) return Decision(close = false)
         settled = true
         val trimmed = draft.trim()
         return if (trimmed.isEmpty()) Decision(close = true) else Decision(close = true, save = trimmed)
     }
```

### 2.2 `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt`

在 `InlineTitleInput` 尾部加一条销毁钩子（`SessionHeader.kt:578-590`，原文件末尾 `LaunchedEffect(Unit) { … }` 之后）：

```diff
     LaunchedEffect(Unit) {
         focusRequester.requestFocus()
         keyboard?.show()
     }
+    // v97 N3 …：leaving the screen used to **commit** the draft …
+    DisposableEffect(Unit) {
+        onDispose { edit.leave() }
+    }
 }
```

**刻意不调 `onCancel()`**：编辑器此刻已在销毁路上，此时写 `editing` 没有任何意义；`onDispose` 只上闩锁。

### 2.3 明确**没有**改的东西

| 文件/契约 | 为什么没动 |
|---|---|
| `SessionHeader.kt:552-555` `onFocusChanged` | 上游 `onBlur` 就是提交（`message-timeline.tsx:485`）；`SessionTitleInlineEditTest.escapeCancels` 逐字 pin 住这行 |
| `SessionHeader.kt:314-317` `onCommit` 提交出口 | 保留回车这条正路径；`onRename` 只在真提交时可达 |
| `SessionScreen.kt` `blurTitleEditorOnOutsideTap` | 会话内点外失焦=提交，是上游语义 |
| `DialogLayerPolicy` / `ModalEscape` / `ocPopoverDismiss` | 全局 ESC 契约，超出授权 |
| `ui/nav/**`、`ui/home/**`、`App.kt` | 禁写区 |

---

## 3. 测试证据

### 3.1 命令

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.SessionTitleDiscardV97Test" `
  --tests "com.xiufeigo.opencodeshell.SessionTitleInlineEditTest" `
  --tests "com.xiufeigo.opencodeshell.SessionTitleManualGuardV78Test" `
  --tests "com.xiufeigo.opencodeshell.EscapeOnPhoneV61Test" `
  --tests "com.xiufeigo.opencodeshell.OcPopoverDismissTest" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest"
```

### 3.2 结果原文

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 3s
52 actionable tasks: 2 executed, 50 up-to-date
```

### 3.3 用例数（`app/build/test-results/testDebugUnitTest/*.xml`）

| 测试类 | tests | failures | errors | skipped |
|---|---|---|---|---|
| **SessionTitleDiscardV97Test（本轮新增）** | **12** | 0 | 0 | 0 |
| SessionTitleInlineEditTest | 10 | 0 | 0 | 0 |
| SessionTitleManualGuardV78Test | 6 | 0 | 0 | 0 |
| EscapeOnPhoneV61Test | 7 | 0 | 0 | 0 |
| OcPopoverDismissTest | 20 | 0 | 0 | 0 |
| StringsLocaleParityTest | 3 | 0 | 0 | 0 |
| **合计** | **58** | **0** | **0** | **0** |

### 3.4 新增 pin 覆盖的任务书三项

`app/src/test/java/com/xiufeigo/opencodeshell/SessionTitleDiscardV97Test.kt`（纯 JVM，无 compose/robolectric）。决策是**行为** pin，接线是**源码** pin——与 `SessionTitleInlineEditTest` 同一拆分。

**① 导航/失焦 → 草稿丢弃、`onRename` 未被调用**
- `leavingTheScreenDiscardsTheDraftWithoutRenaming()` — 复刻完整因果链：聚焦 → 输入 `Queued` → 离开组合 → 随后抵达的 detach 失焦；断言 `renames` 为空
- `leaveIsADiscardAndNotARename()` — `leave()` 返回 `close=true`/`save=null`，`leaving`+`settled` 皆真
- `theLeaveDiscardIsIndependentOfTheSettledLatch()` — 状态机性质：离开后 `commit` 永不 save（即使 `settled` 被移除也成立）
- `aBlankDraftIsStillNeverSubmittedOnTheWayOut()` — 空白草稿走「离开」也不产生空标题（护住 `InlineTitleEdit.kt:63-68`）
- `aTapAwayInsideTheSessionStillCommits()` — **反向护栏**：会话内的点外失焦仍提交，防止把编辑器改成打不出字的死字段

**② 回车 → 提交且调用一次**
- `enterStillCommitsAndCommitsOnlyOnce()` — 提交后 `renames == ["Queued"]`；随后的 detach 失焦与销毁**不追加**第二次（v31/v32 契约）
- `escapeStillCancelsAndEnterStillCommits()` — 两条路径分别验证

**③ 源码 pin：离开路径不得再走提交出口**
- `theEditorLatchesTheDiscardWhenItsCompositionIsTornDown()` — `onDispose { edit.leave() }` 存在；必须是 `DisposableEffect`（同一 `applyChanges` pass）而非协程；钩子体内不得出现 `onCommit` / `onCancel` / 写 `value`
- `theTeardownHookLandsBeforeTheDetachBlurCanBeServed()` — 把整个修复所依赖的**时序假设本身** pin 住，并反向断言不得写成 `LaunchedEffect(Unit) { edit.leave() }`
- `theBlurAndTheCommitExitAreUntouched()` — 失焦提交行、commit 决策行、Enter/Escape 分支逐字未变
- `theHeaderStillPublishesAndDropsItsEditingStateAroundTheTeardown()` — `onTitleEditing` 发布/丢弃语义未被本轮 dispose 挪用
- `theModelDocumentsTheUpstreamGuardItMirrors()` — KDoc 必须继续指名 `message-timeline.tsx:285-289`

### 3.5 既有 pin 零改动

**没有修改任何既有测试文件。** 全部原样存活（见 3.3 计数）。关键是修法刻意落在纯类里：`SessionTitleInlineEditTest` 对 `onFocusChanged` 那一行、`val decision = edit.commit(value.text)`、决策分派那一行都是**字面量**断言，若改动 `SessionHeader.kt` 的决策管线必然变红——本轮通过把决策放进 `InlineTitleEdit.commit()` 避开了全部三处。

诊断报告 §「会牵动的既有 pin」预判方案 C 会碰 `SessionTitleInlineEditTest.kt:71`（`enterAndBlurStillCommit`）与 `:142`（`anOutsideTapBlursTheEditor`）。**该预判未成立**：本实现只把「由销毁引发的失焦」改道，会话内失焦仍走提交，两条 pin 的语义一字未动。

### 3.6 一次自我纠错（留档）

首轮跑出 `1 failed / 57 completed`：我自己写的 `theLeaveDiscardSurvivesEitherDispatchOrder()` 断言「失焦严格早于 dispose 钩子抵达时也丢弃」。**该断言是错的**——那个顺序下 `leaving` 尚未置位，草稿仍会提交；是我的测试自己证伪了我的说法。该顺序在 Compose 中不会发生（dispose 在 `applyChanges` 内、detach 失焦在刷新后的 focus event 派发里），因此正确处置是**收回这条不成立的断言**，并把「时序假设」本身提为独立 pin（`theTeardownHookLandsBeforeTheDetachBlurCanBeServed`）。收回的是一条**假**断言，不是任何真实护栏；首轮的 12 → 仍 12 条，用例集整体变强。

---

## 4. 真机复验步骤（测试员）

前置：会话有名字；先 `adb shell uiautomator dump` 记下 header 标题节点，作为「原名」基准。

**R1 —— ★ 离开不得提交（本次修复的主断言）**
1. 打开会话 → 点 header 标题进入编辑态。
2. 输入 `DISCCHK-01`（**不要回车**，不要点外部）。
3. 点底部 tab 回 Home（或系统返回到 Home）。
4. 断言 A：**header 标题仍是原名**，`DISCCHK-01` 不出现在会话标题上。
5. 断言 B（服务端）：`GET /api/session` 里该会话的 `title` 字段**未变**。
6. 重新进入该会话 → 断言 C：标题**仍是原名**，编辑器**未**自动打开。

**R2 —— 回车正路径必须没被改坏**
1. 点标题进入编辑态，输入 `DISCCHK-02`。
2. `input keyevent 66`（Enter）。
3. 断言：编辑框关闭，标题变为 `DISCCHK-02`，tab 名同步；`GET /api/session` 已落库。
4. 断言：**只落库一次**（重复刷新会话，标题稳定，不出现二次回写）。

**R3 —— 会话内点外仍提交（反向护栏，防过度修复）**
1. 点标题进入编辑态，输入 `DISCCHK-03`。
2. 点消息列表空白处。
3. 断言：编辑框关闭且**标题被改成 `DISCCHK-03`**（提交）。
   → 这是上游 `onBlur → save` 语义，**不是 bug**；若此处变成不提交，说明修过头了。

**R4 —— ESC 出口仍在（回归）**
1. 点标题 → 输入 `DISCCHK-04` → `input keyevent 111`（ESC；IME 在时第一次只收键盘）。
2. 再按一次 ESC。
3. 断言：编辑框关闭且**标题未变**（丢弃）。
   → 若编辑框仍在，按诊断报告 §未闭环 1 的 A2 第 3 步记录 `dumpsys input_method | grep mInputShown`，属**另一个**问题（ESC 两段式），本轮未动。

**R5 —— 空白草稿护栏**
1. 点标题 → 全选删除（留空）→ 回车。
2. 断言：编辑器关闭，标题**仍是原名**（空白 = 取消，不产生空标题）。

---

## 5. 未闭环 / 本轮刻意未做

1. **ESC 两段式（诊断 #5）——未做，需单独授权。** 属 IME 优先消费 ESC 的系统行为，报告已自证属「证据不足」；修它必须碰 `DialogLayerPolicy` / `ModalEscape`，是全局契约（`EscapeOnPhoneV61Test` 7 条 + `OcPopoverDismissTest` 20 条全在其下）。报告的方案 A 建议用 `DialogLayerPolicy.collapseImeOnEscape` 复用而非自造，是正确方向，但那是独立一轮的活。
2. **没有新增可见的「取消」按钮（诊断方案 B）——本轮刻意不做，两条硬理由：**
   - **需要新字符串**：`res/values/strings.xml` 里 grep `oc_cancel` **零命中**，方案 B 的 `oc_cancel_rename` 会违反「不许新增用户可见字符串」。
   - **会引入更严重的 bug**：`HeaderIconButton` 走 `Modifier.clickable`，Compose 会让它**可聚焦**，点击时先夺焦 → 触发 `onFocusChanged(false)` → `commit()` 草稿**先被提交**，等 `onClick` 跑到时闩锁已锁死、`cancel()` 成了空操作。点「取消」反而提交——与目标相反。要做必须同时引入 `focusProperties { canFocus = false }`（牺牲键盘/旋钮可达性）或 `pointerInput` 抢占，与「最小改动」冲突。**ESC 出口本就在（`SessionHeader.kt:564-567`），已足够。**
3. **诊断 §顺带建议未采纳**：在 `onCommit` 里额外要求 `renamed != renameSeed`（报告 §方案 C 末段）。本轮不动——它会改变既有提交语义（种子相同的改名将不再落库），属独立议题，且需要单独立 pin。
4. **纯 JVM 的固有上限**：Compose 组合行为无法从 JVM 驱动。「dispose 先于 detach 失焦派发」是 Compose 的实现时序（v32 已在真机验过同一机制），本轮把它 pin 成源码断言 + 完整 KDoc 依据，但**最终仍需 R1 的真机判定**。
5. **未跑真机**（本轮纪律禁止 adb/装机），故 §4 全部为待执行步骤。
6. **真机上「误提交粘性」的连带修复未做**：已误改过的会话（如 `E2ECHK5562-01`）在 `manualTitles` 里仍是人工名，代码修复不会追溯回滚。R1 第 4 步需换一个**从未被误提交过**的会话，否则测不出本轮修复。

---

## 6. 文件清单

| 文件 | 变更 |
|---|---|
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/header/InlineTitleEdit.kt` | +`leaving` / +`leave()` / `commit()` 增 `leaving` 守卫（+58 行） |
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt` | `InlineTitleInput` 尾部 +`DisposableEffect { onDispose { edit.leave() } }`（+13 行） |
| `app/src/test/java/com/xiufeigo/opencodeshell/SessionTitleDiscardV97Test.kt` | **新增**，12 条 pin |
| 既有测试文件 | **零改动** |
