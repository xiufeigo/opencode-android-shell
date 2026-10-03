# v99 运行时取证修复报告 — 会话 ⋮ 菜单 `Undo`「真机 no-op」

- 执行：运行时取证修复工程师（mcode）
- 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
- 设备：`emulator-5556`（AVD `Medium_Desktop`，`Override 1366x768 / 160`）
- 后端：`http://127.0.0.1:4095`，Basic `opencode:opencode`，会话 `ses_f0064b0f9ffeEO3CLhWCesCMz3`
- 触发依据：`REPORT_FINAL_SWEEP.md` 条目 6（真机 FAIL）与 `UNDO_WIRING_FIX.md`（静态判定"八环齐全"）互相矛盾
- 结论一句话：**`Undo` 不是 no-op——写入其实成功了（后端 12 条 → 3 条，用户消息连同 7 条回复被 `revert/commit` 真删）；断点在「状态回写」环：`commit` 的删除结果从未落进客户端 store，且下一次快照又把 revert 边界覆盖回服务器已清空的 `null`，于是一次成功的撤销在 UI 上与 no-op 完全无法区分。**

---

## 0. 裁决：两份报告各对一半

| 报告 | 主张 | 裁决 |
|---|---|---|
| `UNDO_WIRING_FIX.md` | 静态八环齐全，不存在 no-op，旧 FAIL 是判据错 | **「链路齐全」对**：点确实到达处理器、请求确实发出、服务器确实落库。但**「不存在 no-op」错**：真机上 UI 确实毫无变化 |
| `REPORT_FINAL_SWEEP.md` 条目 6 | `enabled=true` 但执行无任何写入 ⇒ no-op | **「使能判据」错**（读的是 TextView 标签节点，见 §1.a）；**「UI 无变化」对且可复现**；**「`revert` 应非空」错**（commit 之后服务器本就清空它）；**「后端 7 条不变」本轮未复现**（实测被截断，见 §1.c） |

**真正的缺陷**是第三种可能，两份报告都没列：写成功了，但**客户端从不把删除结果落到 store**，所以用户视角就是 no-op。

---

## 1. 四段取证（a–d）

### a) 点击是否到达处理器 —— ✅ 到达

**关键陷阱（先纠正判据本身）**：`REPORT_FINAL_SWEEP.md` 用的 `enabled=true` 读的是 **TextView 标签节点**：

```xml
<node class="android.widget.TextView" text="Undo" clickable="false" enabled="true" bounds="[1132,282][1166,294]" />
```

`enabled` 挂在 label 上，**与 `undoEnabled` 无关**。往上走两层才是可点击行：

```xml
ANCESTOR class=android.view.View clickable=true enabled=true bounds=[1122,266][1322,294]
```

⇒ 这次 `undoEnabled` 确实为真（Compose `clickable(enabled=false)` 会写 `disabled()` 语义），**「使能与执行矛盾」在本次复现中并不成立**——真正矛盾的是**后端已删、UI 未变**。

**到达证据**：点 `(1222,280)`（Undo 行中心）后，菜单项从 dump 中消失 ⇒ `onClick` 里的 `expanded = false` 执行了 ⇒ `actionsHost.start(UNDO)` → `undoSession{}` 被调用。

**logcat**（`adb logcat -c` 后点一次，`logcat -d -t 40` 全量）：**App 一行都没有**。这不能证明没执行——旧代码里 `undoSession` **只有失败才打日志**（`revert failed` / `revert commit failed`），成功路径本来就静默。这正是判读歧义的来源，已按任务要求补日志（§2、§3）。

> 客户端原有可观测点：`OcNotify` 的 `D` 级日志是通的（见 sweep 报告 §5 读数），故 `Log.d` 在本机可直接抓到。

### b) 请求是否发出 —— ✅ 发出（后端侧反证）

`stageRevert` / `commitRevert` 都 `try/catch` 吞异常返回 `null`/抛出，旧代码不留任何请求痕迹，所以**只能从服务器侧反证**。服务器确实收到了两次 `POST .../revert/*` —— 证据见 §1.c 的行数变化（该 API 之外没有任何别的东西会删消息）。已在传输层补日志（§3）使后续可直接读 URL/方法/应答。

### c) 服务端是否收到 / 是否报错 —— ✅ 收到且成功，**但 `revert` 被清空**

同会话、同一次点击的前后对比（`GET /api/session/:id/message?order=asc&limit=80`，响应体是 `{"data":[…]}`）：

**点前 —— 12 条：**

```
type=model-switched     id=msg_0ff9b4f7a001cKGEytQfYWZGSU
type=location-switched  id=msg_0ffa61d9a002SV1aH1M4WlptdB
type=idle               id=msg_0ffa61da3001M4yPPWlsofy4sX
type=user               id=msg_0ffa69b920010tFtWJFRPI7I9m     ← 撤销边界
type=assistant          id=msg_0ffa69be10019n6qSSbmZp0Ouj
type=assistant          id=msg_0ffa6adbe001Za2UOyp0H4kQz1
type=assistant          id=msg_0ffa8908d001xZT34s0t2l3jGx
type=assistant          id=msg_0ffa8ae2c0013TpIBhhxduSp9O
type=assistant          id=msg_0ffa9afb5001WXokOqSsFzr1O4
type=assistant          id=msg_0ffaa861f001BXg3d33vTHPsy6
type=assistant          id=msg_0ffaa940c001e6yrnimV5KWDNA
type=idle               id=msg_0ffaaad04001vaOFSKDskv5QRj
PRE revert=<空>
```

**点后 —— 3 条：**

```
type=model-switched     id=msg_0ff9b4f7a001cKGEytQfYWZGSU
type=location-switched  id=msg_0ffa61d9a002SV1aH1M4WlptdB
type=idle               id=msg_0ffa61da3001M4yPPWlsofy4sX
POST revert=<空>        ← 仍为空
```

⇒ **`user` 连同其后 7 条 assistant 被整段删除**，这正是上游 `revert/commit` 的契约（`core/src/session/revert.ts:78-84` → `projector.ts:731-767`）。

> **两条必须纠正的判据**：
> 1. **`revert` 在 commit 之后必然为 `null`**——服务器删完行就把边界一起清掉了。sweep 拿「`revert` 非空」当主判据，**这条判据按上游语义永远不成立**（本壳自己的 KDoc `ChatViewModel.kt:4520-4522` 早就写明了这件事）。
> 2. `REPORT_FINAL_SWEEP.md` 的「后端仍是 7 条、user 原样仍在」**本轮无法复现**：同一会话实测被截断。该条按证据记为**未复现**，不作为本次定位依据（见 §6）。

### d) 状态是否回写 —— ❌ **断点就在这里**

**UI 在后端已删之后一帧未变**：

```
=== visible texts after Undo ===
Slow counting from 1 to 40          ← 已被服务器删除的 assistant 气泡
E2ECHK5556r probe attachment check
Location changed
program
E2ECHK5556r attach probeCount slowly from 1 to 40. …   ← 已被服务器删除的 user 气泡
```

`am force-stop` + 冷启动后**依然**显示这两条已被删除的行（冷启会重新拉取，不可能来自内存），说明客户端把这条会话的时间线当成"已加载"直接复现，而不是重新对齐服务器。

**根因（三条客户端事实叠加，见 `ChatViewModel.kt`）**：

1. `undoSession` 只发投影（`_revertMessageID.value = boundary`），**从不删除 `_state` 里的行**；
2. `refreshSnapshot` 的快照是**按 upsert 合并**（`ChatViewModel.kt:6770` `applyEvent(ChatStore.Event.Snapshot(messages))`）——服务器不再返回的行**永远删不掉**；
3. 同一次读又把 `_revertMessageID.value = info.revertMessageID` 覆盖回服务器**刚清空的 `null`**（`ChatViewModel.kt:6823`）——于是连投影也不再隐藏尾巴。

⇒ 一次**成功**的撤销，被下一次快照原样还原成"什么都没发生"。这就是「点了没反应 / `Redo` 不出现 / `revert` 为 null」全部三个症状的同一个来源。

---

## 2. 断点（精确到行）

| 环 | 判定 | 位置 |
|---|---|---|
| 渲染 / 使能 | ✅ 正常 | `ui/session/header/SessionHeader.kt:865-872`（`enabled = actionsMenu.undoEnabled`） |
| 点击 → 宿主 | ✅ 到达 | `ui/session/actions/SessionActionsHost.kt:77-79` |
| ViewModel 派发 | ✅ 执行 | `ChatViewModel.kt:4536-4560` |
| 传输 | ✅ 发出 | `v2/V2Transport.kt` `stageRevert` / `commitRevert` |
| 服务端 | ✅ 落库并删除 | 后端 12 → 3 |
| **状态回写** | ❌ **断点** | `ChatViewModel.kt:4592`（只发投影、不删 store）+ `ChatViewModel.kt:6770`（upsert 永不删除）+ `ChatViewModel.kt:6823`（边界被清空的 `revert` 覆盖回 null） |

---

## 3. 改动（diff）

### 3.1 `ChatViewModel.kt` — 撤销段落（**修复本体**）

`undoSession`（`ChatViewModel.kt:4536-4600`）新增派发/结算两跳日志、四条静默 return 全部留痕，并在 commit 成功后把截断落到 store：

```diff
     fun undoSession(onResult: (Boolean) -> Unit = {}) {
         val transport = requireTransport()
         val id = sessionID
-        val target = SessionActionsModel.undoStep(
-            SessionActionsModel.userMessageIDs(_state.value.messages),
-            _revertMessageID.value,
-        )
+        val users = SessionActionsModel.userMessageIDs(_state.value.messages)
+        val target = SessionActionsModel.undoStep(users, _revertMessageID.value)
+        Log.d(
+            TAG,
+            "undo: dispatch session=$id users=${users.size} boundary=${_revertMessageID.value} " +
+                "target=$target transport=${transport != null}",
+        )
         if (transport == null || id.isNullOrEmpty()) {
+            Log.w(TAG, "undo: dropped — no transport or no session id")
             onResult(false)
             return
         }
         if (target == null) {
+            Log.w(TAG, "undo: dropped — undoStep found nothing to rewind")
             onResult(true)
             return
         }
@@
+            Log.d(
+                TAG,
+                "undo: settle session=$id target=$target stagedBoundary=$boundary committed=$committed",
+            )
             if (!committed) {
                 onResult(false)
                 return@launch
             }
             // Boundary writes are session-scoped: landing after a switch would
             // crop the new session's timeline through the revert projection.
             if (boundary != null && sessionID == id) _revertMessageID.value = boundary
+            // v99: the commit **deleted** those rows server-side and cleared
+            // `revert`, so the projection alone cannot carry the result.
+            if (boundary != null && sessionID == id) dropRevertedTail(boundary)
             applyRevertRestore(id, restore)
             onResult(true)
         }
     }
+
+    private fun dropRevertedTail(boundary: String) {
+        var dropped = 0
+        _state.update { current ->
+            val hidden = SessionActionsModel.hiddenMessageIDs(current.messages.map { it.id }, boundary)
+            if (hidden.isEmpty()) return@update current
+            val hiddenIDs = hidden.toSet()
+            dropped = hiddenIDs.size
+            current.copy(messages = current.messages.filterNot { it.id in hiddenIDs })
+        }
+        Log.d(TAG, "undo: dropped $dropped reverted row(s) from the store")
+    }
```

- 新增 `dropRevertedTail`：`ChatViewModel.kt:4627`（定义）/ `4596`（调用点）/ `4636`（日志）
- 判定复用共享纯函数 `SessionActionsModel.hiddenMessageIDs`，排序规则仍只有一份实现
- 幂等：服务器不再返回的行，下一次 upsert 快照无法复活
- 顺序保持"先边界后删行"，同一帧内投影与 store 一致
- 失败路径（`!committed`）**一个字节都没动**：仍不删行、不写边界，`SessionRaceGuardTest` 钉住的那行原样保留

### 3.2 `v2/V2Transport.kt` — 传输层可观测（**提交前不删**）

```diff
     override fun stageRevert(sessionID: String, messageID: String): String? = try {
-        api.revertStage(sessionID, messageID).messageID
-    } catch (_: Exception) {
+        val staged = api.revertStage(sessionID, messageID).messageID
+        android.util.Log.d("V2Transport", "revert: POST session/$sessionID/revert/stage {messageID=$messageID} -> messageID=$staged")
+        staged
+    } catch (e: Exception) {
+        android.util.Log.w("V2Transport", "revert: stage FAILED for $messageID: ${e.message}")
         null
     }
 
     override fun commitRevert(sessionID: String) {
+        android.util.Log.d("V2Transport", "revert: POST session/$sessionID/revert/commit")
         api.revertCommit(sessionID)
+        android.util.Log.d("V2Transport", "revert: commit 2xx (rows deleted, revert cleared server-side)")
     }
```

### 3.3 新增日志行清单（按任务要求列出，不删）

| TAG | 行 | 含义 |
|---|---|---|
| `ChatViewModel` | `undo: dispatch session=… users=… boundary=… target=… transport=…` | 点击到达派发，且带出决策输入 |
| `ChatViewModel` | `undo: dropped — no transport or no session id` | 静默 return ①（原本零输出） |
| `ChatViewModel` | `undo: dropped — undoStep found nothing to rewind` | 静默 return ②（原本零输出，sweep 落在这里） |
| `ChatViewModel` | `undo: settle … stagedBoundary=… committed=…` | stage/commit 结果 |
| `ChatViewModel` | `undo: dropped N reverted row(s) from the store` | 截断落进 store（本次修复的核心信号） |
| `V2Transport` | `revert: POST session/…/revert/stage {messageID=…} -> messageID=…` | 请求确实发出 + 边界应答 |
| `V2Transport` | `revert: stage FAILED for …` | 被吞掉的失败（原本静默 `null`） |
| `V2Transport` | `revert: POST session/…/revert/commit` / `… commit 2xx` | commit 请求与 2xx |

**抓取方法**：`adb -s emulator-5556 logcat -c` → 点 Undo → `adb -s emulator-5556 logcat -d -s ChatViewModel:D V2Transport:D *:S`
（两条 `undo: dropped —` 是 `W` 级，用 `-s ChatViewModel:W` 或 `*:V` 一并抓。）

---

## 4. 为什么不降强度

- **既有 0 删改**：`SessionActionsTest`（12）、`SessionUndoWiringTest`（8）、`SessionRaceGuardTest`（9）、`MessageRevertTest`（3）、`SessionRevertRestoreTest`（3）、`TimelineRowsTest`（23）、`TimelineFidelityTest`（40）、`ChatStoreTest`（12）、`StringsLocaleParityTest`（3) 一行未动，无任何 `assert` 被删改或放宽。
- **唯一动的生产段落**是 `undoSession` 内部，且**失败路径语义完全不变**：`if (!committed) { onResult(false); return@launch }` 仍在最前，失败依旧不删行、不写边界、不留幻影边界。
- **保留既有断言要求的写法**：`if (boundary != null && sessionID == id) _revertMessageID.value = boundary` 逐字保留（`SessionRaceGuardTest:96` 依赖它），修复在其后**追加**一步。
- **新断言只会更严**：`SessionUndoRuntimePinTest` 把"使能 ⇒ 可执行"钉成不变量（`anEnabledUndoRowAlwaysHasANonNullTarget`），并把"截断必须落 store 而非只落投影"钉成结构约束（`theCommittedTailIsDroppedFromTheStoreNotOnlyProjectedAway`）——比"点了没反应"细一档。
- **零新增用户可见字符串**：全部复用既有 `command_session_undo` / `common_requestFailed`，三语无需新增条目。
- 改动面严格限于授权目录：`ChatViewModel.kt`（仅 `undoSession` 撤销段落 + 新增私有 `dropRevertedTail`）、`v2/V2Transport.kt`（仅 `stageRevert`/`commitRevert`）、新测试文件。**未碰** `ChatStore.kt`（因此用 `_state.update` 而非新增 reducer 事件）、`ui/servers/**`、`ui/home/**`、`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`、`_upstream/**`、`app/build.gradle.kts`。

---

## 5. 测试证据

新类 `SessionUndoRuntimePinTest`（6 例）：

| 用例 | 钉住什么 |
|---|---|
| `anEnabledUndoRowAlwaysHasANonNullTarget` | ① **`enabled=true` ⇒ 点击必产生非空动作**（4 种 transcript × 4 种边界全枚举） |
| `aDisabledUndoRowIsExactlyTheOneWithNothingLeftToRewind` | ① 收敛方向：无可撤销时行自己变灰，"可点但无事可做"不可能出现 |
| `undoingCutsTheLastUserMessageAndEverythingAfterIt` | ② 状态转换：撤销 1 user + N 回复后，该轮整体离场 |
| `consecutiveUndosWalkTheBoundaryBackwardsOneTurnAtATime` | ② 状态转换：连撤逐轮回退，`Redo` 随之点亮 |
| `theCommittedTailIsDroppedFromTheStoreNotOnlyProjectedAway` | 结构：截断写进 `_state`，且既有边界写入行原样保留 |
| `everySilentExitOfUndoSessionIsLogged` | 可观测性：每个静默出口都有日志 |

命令（抢锁 → 写锁 → 前台跑完 → 删锁）：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.SessionUndoRuntimePinTest" `
  --tests "com.xiufeigo.opencodeshell.SessionUndoWiringTest" `
  --tests "com.xiufeigo.opencodeshell.SessionActionsTest" `
  --tests "com.xiufeigo.opencodeshell.SessionRevertRestoreTest" `
  --tests "com.xiufeigo.opencodeshell.SessionRaceGuardTest" `
  --tests "com.xiufeigo.opencodeshell.MessageRevertTest" `
  --tests "com.xiufeigo.opencodeshell.TimelineRowsTest" `
  --tests "com.xiufeigo.opencodeshell.TimelineFidelityTest" `
  --tests "com.xiufeigo.opencodeshell.ChatStoreTest" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest" `
  --console=plain
```

原文结尾：

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 22s
52 actionable tasks: 2 executed, 50 up-to-date
```

逐类用例数（`app/build/test-results/testDebugUnitTest/`）：

| 类 | 用例 | 失败 |
|---|---|---|
| `SessionUndoRuntimePinTest`（新） | 6 | 0 |
| `SessionUndoWiringTest` | 8 | 0 |
| `SessionActionsTest` | 12 | 0 |
| `SessionRaceGuardTest` | 9 | 0 |
| `MessageRevertTest` | 3 | 0 |
| `SessionRevertRestoreTest` | 3 | 0 |
| `TimelineRowsTest` | 23 | 0 |
| `TimelineFidelityTest` | 40 | 0 |
| `ChatStoreTest` | 12 | 0 |
| `StringsLocaleParityTest` | 3 | 0 |
| **合计** | **119** | **0**（0 错误 / 0 跳过） |

> 迭代记录：新类首跑**编译失败**（漏 `import ...ui.session.actions.SessionActionsModel`），补 import 后 6/6 全绿。生产代码全程只增不减，既有断言零改动。

---

## 6. 真机复验步骤

> 前置：**必须装上含本次修复的构建**（本轮纪律禁止装机，故 §1 的取证在旧构建上完成——而旧构建已经能证明"写入成功、UI 不回写"，这正是本次要修的那一处）。

1. **造可撤销态**（新会话）：`New session → Create session`，composer 输 `UNDO-PROBE-<随机串>` + `keyevent 66`，等 `idle`（后端 `GET /api/session/{id}/message?order=asc&limit=80` 出现 `type=idle` 为准）。
2. **记基线**：后端消息条数 `N`、最后一条 `type=user` 的 id `M`；`GET /api/session/{id}` 的 `revert`。
3. `adb logcat -c`，然后点 `⋮ → Undo`（坐标运行期从 `uiautomator dump` 解析 **可点击行**，不要用 label 坐标——label 节点 `clickable=false`，这正是旧判据踩的坑）。
4. **断言 logcat**（一次成功应有这 5 行）：
   ```
   D ChatViewModel: undo: dispatch session=ses_… users=1 boundary=null target=msg_… transport=true
   D V2Transport: revert: POST session/ses_…/revert/stage {messageID=msg_…} -> messageID=msg_…
   D V2Transport: revert: POST session/ses_…/revert/commit
   D V2Transport: revert: commit 2xx (rows deleted, revert cleared server-side)
   D ChatViewModel: undo: settle session=… stagedBoundary=msg_… committed=true
   D ChatViewModel: undo: dropped N reverted row(s) from the store     ← 修复生效的关键行
   ```
   若见 `undo: dropped — undoStep found nothing to rewind` ⇒ `_state` 没有用户消息（判读见下表）。
5. **断言后端**（ground truth）：`GET /message` 条数**必须减少**（本轮实测 12 → 3），`type=user` 及其后回复**不再返回**。
6. **断言 UI**（这次必须变）：被撤销轮次的 user 气泡与 assistant 回复**从时间线消失**；`am force-stop` + 冷启动后**仍然不出现**。
7. **不要断言 `revert` 非空**：`revert/commit` 之后服务器本就把它清空（§1.c 实测）。`Redo` 行按 v51 既有决策（`SessionActionsModel.showsRedo`）本就只在可用时出现，撤销后不出现是**既有行为**，不是缺陷。
8. **失败判读表**：
   | 观察 | 断点 |
   |---|---|
   | 无 `undo: dispatch` | 点击没到 `SessionActionsHost.start` —— 查是否 `undoEnabled=false`（行 `clickable=false`） |
   | `undo: dropped — no transport` | 传输层未就绪 |
   | `undo: dropped — undoStep found nothing` | `_state` 无 user 消息 ⇒ 查 `snapshotMessages` 是否把 user 行落进了 store |
   | `revert: stage FAILED …` | 服务端非 2xx（如 409 `SessionBusyError`，会话仍 running） |
   | 有 `commit 2xx` 但 UI 未变 | store 未回写（本轮修复针对的就是这一格） |
9. **顺带非回归**：`Fork` / `Import` / `Move`（本轮已 PASS）菜单项仍在且可用；改名提交语义不变；`Compact` / `New worktree` 不受影响。

---

## 7. 未闭环

| 项 | 状态 | 原因 |
|---|---|---|
| 修复后的真机复验 | **未做** | 本轮纪律禁止装机（`assembleDebug` / 装机均禁），只能跑 `:app:testDebugUnitTest`。§6 步骤已写成可直接执行的判据 |
| 新增 `Log.d` 的真机读数 | **未采** | 同上；`Log.d` 需新构建才能出现在 logcat。抓取命令已写进 §3.3 |
| `REPORT_FINAL_SWEEP.md` 的「后端仍是 7 条、user 原样仍在」 | **未复现** | 同会话实测后端被截断（12 → 3）。该条与本轮证据冲突，记为未复现；不作为定位依据，也未据此改任何代码 |
| 冷启动后仍显示已删除行 | **已解释、未单独修** | 本次 `dropRevertedTail` 修的是撤销后的 store 回写。冷启动路径复现的是同一条时间线却未重新对齐服务器，**疑似同源的第二处缺口**（快照 upsert 不会删除）——但它影响的不止撤销，超出本轮授权范围（`ChatStore.kt` 不在写权限内），留作下一轮独立取证 |
| `Redo` 行语义 | **按既有决策保持** | v51 `tE`：`showsRedo` 只在可用时显示。本次未新增反馈串、未改变该行为 |
| 快照 upsert 永不删除 | **未修（已记录）** | 根因在 `ChatStore.Event.Snapshot` 的合并语义，属 `ChatStore.kt`，本轮禁改。撤销面已用 store 主动删除绕开 |
