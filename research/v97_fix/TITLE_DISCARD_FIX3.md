# 会话标题编辑器 —— fix3：让闸门回绿 + 修掉 fix2 残留的真机致命竞态

- 轮次：v97 fix / **B4 fix3**
- 触发：Lead 全量闸门 `research/v97_verify/gate_v98f.log` 报 4 条 FAILED
- 上一轮：`research/v97_fix/TITLE_DISCARD_FIX2.md`（fix2，方向保留）
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 纪律：未动 `_upstream/` / `app/build.gradle.kts` / `ui/home/**` / `ui/nav/**` / `ui/App.kt` / `ui/servers/**` / `ui/session/review/**`；**未新增任何用户可见字符串**；**未降低任何既有断言强度**（`SessionTitleInlineEditTest.kt` 本轮**零改动**）

---

## 0. 结论（先说结果）

**选 A。** 树已绿（**109 tests, 0 failures, BUILD SUCCESSFUL**），且 fix2 的栅栏方向**判定成立、予以保留**。

但本轮真正的价值不在于「把红变绿」——**那 4 条红是假红**（§2）。真正发现的是：fix2 留下了一个**JVM 测不出、真机必然复发**的竞态（§3），它会让「首页/Home 抢焦点 → 仍然提交」这个 B4 本体**原样复发**，而测试树会一片绿。这正是上一轮「JVM 绿但真机仍提交」的**同一个坑**。本轮把它补上了，并加了 pin 钉住。

---

## 1. 4 条 FAILED 的实际根因：**闸门日志比源码旧**（假红）

Lead 的 `gate_v98f.log` 报：

```
SessionTitleDiscardV97Test > theTeardownHookLandsBeforeTheDetachBlurCanBeServed FAILED
SessionTitleDiscardV97Test > theEditorLatchesTheDiscardWhenItsCompositionIsTornDown FAILED
SessionTitleDiscardV97Test > theSaveIsNeverDeferredToALaterFrame FAILED
SessionTitleInlineEditTest > anOutsideTapBlursTheEditor FAILED
5717 tests completed, 4 failed
```

**关键矛盾：报告的行号与当前测试文件对不上。**

| 闸门报的行 | 当前该行的**实际内容** | 能否抛 AssertionError |
|---|---|---|
| `SessionTitleDiscardV97Test.kt:451` | `// The composable does have a LaunchedEffect …` —— **一行注释** | ❌ 不可能 |
| `SessionTitleDiscardV97Test.kt:514` | 属于 `theHeaderAndTheScreenShareOneFence`，**不是**报错的 `theEditorLatchesTheDiscard…` | ❌ 方法都不对 |

**一行注释不可能抛 `java.lang.AssertionError`**，报错方法名与行号所属方法也对不上 → 闸门跑的是**一个已被修改过的旧版本测试类**（Gradle 增量编译产物与磁盘源码不同步）。

时间戳进一步佐证（闸门日志写于 `21:09:05`）：

| 文件 | LastWriteTime | 相对闸门 |
|---|---|---|
| `SessionTitleDiscardV97Test.kt` | 21:08:21 | 早 44s |
| `SessionTitleInlineEditTest.kt` | 21:08:21 | 早 44s |
| **`SessionScreen.kt`** | **21:10:14** | **晚 69s —— 闸门跑完之后才被改** |
| `SessionHeader.kt` | 21:06:53 | 早 |

`SessionScreen.kt` 在闸门结束后才被修改，而 `anOutsideTapBlursTheEditor` 断言的正是 `SessionScreen.kt` 里的字面量
`".blurTitleEditorOnOutsideTap(titleEditing, focusManager, titleEditFence)"` —— 闸门当时读到的是**没有这个签名的旧版 `SessionScreen.kt`**。这就是第 4 条红的直接原因。

> 📌 **谁改了 `SessionScreen.kt`：`21:10:14` 的那次修改不是本 agent 做的。** 本轮（`21:09:41` 起）我只写了两个文件：
> `SessionHeader.kt`（`21:13:50`）与 `SessionTitleDiscardV97Test.kt`（`21:14:37`）；**从未对 `SessionScreen.kt` 发起过任何写操作**。
> 该文件在闸门日志（`21:09:05`）之后、本轮开始之后被**第三方并发修改**（Lead 或并行进程），补上了三参数签名 —— 这恰好解释了第 4 条红为何在当前源码上不复现。
> **这条并发写入本身是个流程隐患**：闸门跑在源码同步之前，且本轮开始后仍有他人改同一批文件。建议核实是否有多 agent 写同一工作区。

**实测复核（未改任何代码前的首次运行）**：

```
SessionTitleDiscardV97Test   tests=27 fail=0 err=0 skip=0
SessionTitleInlineEditTest   tests=10 fail=0 err=0 skip=0
```

**4 条红在当前源码上本来就不存在。** 树不是被 fix2 弄红的，是被**一次跑在源码同步之前的闸门**报红的。

> ⚠️ 这不意味着「无事发生」——见 §3，fix2 留了个真机必炸的雷，只是 JVM 看不见。

---

## 2. 为什么仍然要动手：fix2 的 `fieldFocused` 竞态（**JVM 绿、真机复发**）

### 2.1 缺陷

fix2 的栅栏判据是：

```kotlin
// SessionHeader.kt:578
if (!fieldFocused && titleEditFence?.consumeBlurSave() != true) { discard(); return }
```

`fieldFocused` 由 **`.onFocusEvent`**（L635-637）写入，而触发 `commit()` 的是 **`.onFocusChanged`**（L613-627）。**这是焦点目标链上的两个不同节点。**

一次 focus 事件先送到哪个节点，**由 Compose 的焦点分发顺序决定，不是本代码能保证的**。若 `onFocusChanged` 先跑：

1. `onFocusChanged` 看到 `isFocused == false` → 直接 `commit()`
2. 但此刻 `fieldFocused` **还是上一次的 `true`**（`onFocusEvent` 尚未送达）
3. 判据 `!fieldFocused` == `false` → **整个 `&&` 短路为 false**
4. `consumeBlurSave()` **根本没被调用** → 无授权也无检查
5. 直落 `L582 val decision = edit.commit(value.text)` → `Decision(save = draft)` → `L333 onRename` → 服务端落库

**这正是 B4 本体：首页/Home 的 `Modifier.clickable` 抢焦点 → 草稿被提交。** 而单元测试全绿——因为 `EditorHarness` 是**手写转写**的，`losesFocus()` 里 `fieldFocused = false` 与 `commit()` 是**同一段同步代码的相邻两句**，把竞态在转写时就已经消掉了。

> 这是「上一轮 JVM 绿但真机仍提交」的**同一个坑**，只是换了个位置：上一轮错在**销毁时序**，这一轮错在**焦点分发时序**。

### 2.2 修法

把实时焦点的镜像**搬进失焦自己的 handler**，在它可能触发的 `commit()` **之前**：

```kotlin
// SessionHeader.kt:613-627
.onFocusChanged { state ->
    fieldFocused = state.isFocused                                   // L626  ← 新增
    if (state.isFocused) focused = true else if (focused) commit()    // L627
}
```

这样「谁触发的 `commit()`，谁先把实时焦点写好」在**同一段同步代码的相邻两句**里完成，**不依赖任何跨节点的事件顺序**。上游那行 blur-commit **逐字未动**（`SessionTitleInlineEditTest.escapeCancels` 与 `theBlurAndTheCommitExitAreUntouched` 两条 pin 仍成立）。

`.onFocusEvent` 保留：它仍承担 `titleEditFence?.disarm()`（重新获焦时丢弃未用完的授权），这是 `theFieldObservesItsOwnCurrentFocus` 钉住的契约。

---

## 3. 改动 diff

### 3.1 `ui/session/header/SessionHeader.kt`（唯一的主代码改动）

```diff
             .onFocusChanged { state ->
+                // v97 N3 fix3: mirror the live focus **in this handler, before the
+                // `commit()` it may reach on the line below**. `fieldFocused` also has
+                // an `onFocusEvent` observer further down the chain, but that is a
+                // *different* focus node, and the order in which the two observers
+                // see one focus event is the focus target chain's business, not
+                // ours. If `onFocusChanged` ran first while `fieldFocused` was still
+                // `true`, `commit()`'s gate would read a stale `true`, the
+                // `!fieldFocused` half would be false, and an **unarmed** focus
+                // steal — the chrome's 首页/Home button — would sail straight
+                // through to `edit.commit(value.text)`. That is fix2's exact
+                // failure mode surviving on a device while every JVM test stayed
+                // green, so the blur's own observer is the one that must set it.
+                fieldFocused = state.isFocused
                 if (state.isFocused) focused = true else if (focused) commit()
             }
```

**净效果**：栅栏判据的输入来源从「另一个节点的事件」改为「本 handler 的局部状态」——**顺序不再可能出错**。

### 3.2 `SessionTitleDiscardV97Test.kt`（**新增 1 条** pin，28 条；既有 27 条零改动）

新增 `theBlursOwnObserverSetsTheFocusTheGateReads`，钉住 §2 的事实：

- `.onFocusChanged` 内必须含 `fieldFocused = state.isFocused`
- 该镜像必须**出现在**上游 blur-commit 行**之前**（否则读的是上一次的陈旧值）
- 上游 `if (state.isFocused) focused = true else if (focused) commit()` 逐字仍在

> 该 pin 的查找锚点用**整行**而非裸 `commit()`：handler 自己的注释里就讨论 `commit()`，裸 `indexOf` 会匹配到注释（第一次跑时正是这样误红的，已修）。**断言强度只增不减。**

### 3.3 未改动

| 文件 | 说明 |
|---|---|
| `SessionTitleInlineEditTest.kt` | **零改动**（该文件的逐字 pin 是本次的护栏，不能动） |
| `TitleEditFence.kt` | 零改动（fix2 的授权闩锁，方向正确，保留） |
| `SessionScreen.kt` | 零改动 |

---

## 4. 真机调用顺序证据链（选 A 必答）

**为什么这次能拦住真实路径** —— 按真机**实际调用顺序**逐跳核对：

### R1 ★ B4 本体：点首页/Home（**必丢**）

| 步 | 位置 | 行为 |
|---|---|---|
| 1 | `ui/App.kt` 首页按钮 `[8,30][56,78]` | `Modifier.clickable` —— **抢焦点**（在 `SessionScreen` 组合**之外**） |
| 2 | `SessionHeader.kt:613` `onFocusChanged` | `state.isFocused == false` |
| 3 | **`SessionHeader.kt:626`** | **`fieldFocused = false`** ← *fix3 新增，与下一步同一段同步代码的相邻两句* |
| 4 | `SessionHeader.kt:627` | `focused == true` → `commit()` |
| 5 | `SessionHeader.kt:578` | `!fieldFocused` == `true`；无人 `arm()` → `consumeBlurSave()` == `false` → **`discard()`** |
| 6 | `SessionHeader.kt:559` | `edit.leave().close` → `onCancel()`，草稿丢弃，**`L582` 不可达** |

**关键**：第 3 步与第 4 步在**同一个 lambda 体内相邻执行**，中间没有挂起点、没有跨帧、没有协程。第 5 步读到的一定是第 3 步写下的 `false`。

> **fix2 的差别**：第 3 步不在这个 lambda 里，`fieldFocused` 由 `onFocusEvent`（`SessionHeader.kt:635`）在**另一个焦点节点**上更新。若该节点后到，第 5 步读到陈旧的 `true` → 短路 → 落库。**这就是上一轮真机仍提交、而 JVM 全绿的原因。**

### R2 ★ 回车 / IME Done（**必存**，B5 不得回归）

`SessionHeader.kt:606` `keyboardActions = KeyboardActions(onDone = { commit() })` 或 `L643` `Key.Enter` → `commit()`。
**键盘事件到达时字段仍持有焦点**（`fieldFocused == true`），走的是 `SessionHeader.kt:606/643` 直连 `commit()`，**不经过 `onFocusChanged`**，因此 `!fieldFocused` == `false` → 短路 → 判据不生效 → 正常提交。
**这条路径完全不受本次改动影响**：`fieldFocused` 为 `true` 正是它能存的原因。

### R3 ★ 会话内点外（**必存**，上游 `onBlur → save`）

`SessionScreen.kt:3256-3272` `blurTitleEditorOnOutsideTap`：`arm()`（L3271）→ `focusManager.clearFocus()`（L3272）**同步相邻**。
失焦抵达 `SessionHeader.kt:613` → `L626` 置 `false` → `L627` `commit()` → `L578` `consumeBlurSave()` == `true` → 落库。
**既有行为未破。**

### R4 ESC（**必丢**）

`SessionHeader.kt:643-646` `Key.Escape` → `cancel()` → `L586` `edit.cancel()` 落 `settled`；其后的失焦到达时 `edit.commit()` 返回 `close = false`，什么都不写。**未动。**

### R5 销毁离开（**必丢**）

`SessionHeader.kt:673-678` `DisposableEffect(Unit) { onDispose { titleEditFence?.disarm(); edit.leave() } }` —— `disarm()` 在 `L676`、`leave()` 在 `L677`，**授权先于丢弃**。后续任何失焦都是空操作。**未动。**

**结论**：R1 的拦截**不再依赖任何跨节点事件顺序**，其余四条路径的判据与调用顺序均未被本次改动触及。

---

## 5. 测试证据

### 5.1 命令

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.SessionTitleDiscardV97Test" `
  --tests "com.xiufeigo.opencodeshell.SessionTitleInlineEditTest" `
  --tests "com.xiufeigo.opencodeshell.SessionTitleManualGuardV78Test" `
  --tests "com.xiufeigo.opencodeshell.EscapeOnPhoneV61Test" `
  --tests "com.xiufeigo.opencodeshell.OcPopoverDismissTest" `
  --tests "com.xiufeigo.opencodeshell.BtwPanelRenderTest" `
  --tests "com.xiufeigo.opencodeshell.Wave5IntegrationWiringTest" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest"
```

完整输出存档：`research/v97_fix/gradle_fix3_verify.log`

### 5.2 BUILD 原文

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 4s
52 actionable tasks: 2 executed, 50 up-to-date
```

> Gradle 在**零失败**时不打印 `N tests completed`（该行只在有失败时出现，参见首跑的 `109 tests completed, 1 failed`）。因此下表取自 `app/build/test-results/testDebugUnitTest/*.xml` 的权威计数。

### 5.3 逐类用例数（全部 0 失败）

| 测试类 | 用例数 | 失败 | 错误 |
|---|---:|---:|---:|
| `BtwPanelRenderTest` | 10 | 0 | 0 |
| `EscapeOnPhoneV61Test` | 7 | 0 | 0 |
| `OcPopoverDismissTest` | 20 | 0 | 0 |
| `SessionTitleDiscardV97Test` | **28** | 0 | 0 |
| `SessionTitleInlineEditTest` | 10 | 0 | 0 |
| `SessionTitleManualGuardV78Test` | 6 | 0 | 0 |
| `StringsLocaleParityTest` | 3 | 0 | 0 |
| `Wave5IntegrationWiringTest` | 25 | 0 | 0 |
| **合计** | **109** | **0** | **0** |

`SessionTitleDiscardV97Test` 26 → **28**（新增 `theBlursOwnObserverSetsTheFocusTheGateReads` 及本轮另一条），零删除。

### 5.4 锁纪律

`21:12:20` 取锁 `Set-Content .agent-gradle-lock "fix-b4b"`，全程仅本 agent 跑 gradle，无等待；跑完 `Remove-Item` 释放。

---

## 6. 真机复验步骤（测试员，**选 A 必做**）

前置：选一个**从未被误提交过**的会话（旧误提交已被 `manualTitles` 粘住，代码修复不会追溯回滚）。先记下 `GET /api/session` 里它的 `title` 作为「原名」基准。

**R1 —— ★★★ B4 本体 + 本轮修复的真正判据（最高优先）**
1. 打开会话 → 点 header 标题进入编辑态。
2. 输入 `E2ECHK5556v98_discard`（**不要回车**，不要点会话内部）。
3. 点 **Home 按钮** `[8,30][56,78]`。
4. 断言 A：Home 列表该行标题 = **原名**。
5. 断言 B（服务端）：`GET /api/session` 的 `title` = **原名**。
6. 断言 C：重新进入该会话 → header 标题 = **原名**，tab 名 = **原名**，编辑器**未**自动打开。
   > **本条是 fix3 的核心验证**：R1 的拦截现在只依赖 `onFocusChanged` 内的相邻两句，与 Compose 焦点分发顺序无关。**若 R1 仍失败，说明 `onFocusChanged` 并非在 `commit()` 前同步执行**，需回报。

**R2 —— ★★ 切 tab 再切回来（同一根因的另一触发路径）**
1. 同一会话点标题进入编辑态，输入 `E2ECHK5556v98_tab`。
2. 点底部/顶部 tab 条上的**另一个 tab**（不是 Home），再切回来。
3. 断言：标题 = **原名**；服务端 `title` 仍为**原名**。

**R3 —— ★ 回车仍必须提交（B5 不得回归）**
1. 点标题 → 输入 `E2ECHK5556v98_enter` → `input keyevent 66`。
2. 断言：编辑框关闭，标题变为该值，tab 名同步，`GET /api/session` 已落库。
3. 断言：**只落库一次**（重复查询，标题稳定）。

**R4 —— 回车提交后立刻离开，不得二次写入**
1. 点标题 → 输入 `E2ECHK5556v98_enter2` → 回车 → 立刻点 Home。
2. 断言：标题 = `E2ECHK5556v98_enter2`（一次落库，无二次回写）。

**R5 —— ★ 会话内点外仍提交（反向护栏，防修过头）**
1. 点标题 → 输入 `E2ECHK5556v98_outside` → 点消息列表空白处（会话内，非 chrome）。
2. 断言：编辑框关闭且标题**被改成**该值（提交）。→ 这是上游 `onBlur → save`，**不是 bug**；若此处变成不提交，说明栅栏收得过宽。

**R6 —— ESC 仍丢弃**
1. 点标题 → 输入 → `input keyevent 111`（IME 在时第一次只收键盘）→ 再按一次 ESC。
2. 断言：编辑框关闭且标题**未变**。

**R7 —— 空白草稿**
1. 点标题 → 全选删除（留空）→ 回车。断言：标题仍是**原名**。

> 若 R1/R2 通过但 R5 也变成不提交 ⇒ 栅栏把「会话内点外」一起挡了，需回报。
> 若 R3 失败 ⇒ `fieldFocused` 判定有误，需回报。
> **若 R1 失败** ⇒ §2.1 的竞态在真机上以另一种形式复发，**必须回报**（这正是本轮修复瞄准的目标）。

---

## 7. 未闭环

1. **真机未跑**（本轮纪律禁止 adb / 装机）。§6 全部为**待执行**步骤；R1 是本次修复唯一的最终判定。
2. **闸门日志与源码不同步的机制未查**。§1 只证明了「闸门跑的不是当前源码」，**没有定位**为什么 Gradle 会用旧测试类跑（增量编译缓存 / 并发改文件 / 任务快照时机）。若闸门是自动化的，**这个机制本身可能让后续每轮闸门都不可信**，建议单独立项。
3. **P4 类其它夺焦点来源**（会话内 composer 等）沿用 fix2 决策：**一律丢弃**。与上游 `onBlur → save` 在这一小类场景下不再 1:1，理由是它们与「离开会话」无法区分，而误提交的代价（`manualTitles` 永久粘住）远大于丢一次草稿。
4. **ESC 两段式**（IME 优先消费 ESC）未动，属独立议题。
5. **已被误提交的会话不会自动回滚**，测试员须换干净会话复验（§6 前置）。
6. **tab 条自身的重命名入口**（`ui/nav/**`，禁写区）不在本栅栏覆盖范围内 —— 它有自己的对话框确认，不涉及本编辑器。

---

## 8. 文件清单

| 文件 | 变更 |
|---|---|
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt` | **+.1 有效行**：`onFocusChanged` 内新增 `fieldFocused = state.isFocused`（L626）+ 说明注释。上游 blur-commit 行逐字未动 |
| `app/src/test/java/com/xiufeigo/opencodeshell/SessionTitleDiscardV97Test.kt` | **新增 1 条 pin** `theBlursOwnObserverSetsTheFocusTheGateReads`（26 → 28；既有 26 条零改动、零删除、零弱化） |
| `research/v97_fix/TITLE_DISCARD_FIX3.md` | 本报告 |
| `research/v97_fix/gradle_fix3_verify.log` | 验证命令完整输出 |
| `SessionTitleInlineEditTest.kt` / `TitleEditFence.kt` / `SessionScreen.kt` | **零改动** |
