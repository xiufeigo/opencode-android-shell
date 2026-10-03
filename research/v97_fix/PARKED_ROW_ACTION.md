# PARKED_ROW_ACTION — v133：parked 队列首行动作标签语义裁定

- **状态**：✅ **已裁定 · 已钉死 · 锁已释放**（本文件即锁/交付物，沿用 v131 `EDITING_QUEUED_LOCK.md` 的「本文件即锁，仓库无独立 `*.lock`」约定）
- **抢锁**：2026-10-04 05:43 起；前序锁 `EDITING_QUEUED_LOCK.md` 已自述「锁已释放」⇒ 抢锁时锁为空
- **待裁定**：`research/v97_verify/5554_md/REPORT_QUEUE_COVERAGE.md`（v130）格三 FAIL
- **仓库根**：`C:\sensorsdata\main\program\opencode-android-shell`

---

## 0. 结论先行（一句话）

**裁定 ①：上游语义确实是「parked ⇒ `Send`」，本壳的模型逐条一致、无缺陷；v130 判 FAIL 的依据是一次真机**读数错误**（把仍在运行的回合当成 idle），故该 FAIL 应作废 —— 本次未改任何生产代码，只补了一条把"读数口径"钉死的 pin。**

⚠️ 与题面给的 ① 有出入，须说清：题面 ① 的措辞是"**上游也保持 `Steer`**"，而上游并非如此（上游 parked 时就是 `Send`）。所以**上游语义侧站在 v130 一边**（v130 的**期望**是对的），错的只是 v130 **测到的东西**。故严格讲不是"期望写错"，而是"**前提测错 ⇒ 结论无效**"，落在"本壳无缺陷、不修"这一侧。

---

## 1. 上游语义（唯一标尺，含条件分支原文）

上游真实路径是 `_upstream/packages/app/`（**不是**注释里写的 `session/composer/queue.ts` 那种相对写法，但文件确实存在）：

**`_upstream/packages/app/src/session/composer/queue-panel.tsx:89-92`** —— "parked" 概念的定义（动作常显 + 行为解释）：

```tsx
  // While the turn is stopped the queue stays parked, so the first prompt
  // shows its actions without hover and its label reads Send: that is how a
  // parked queue resumes.
  const active = () => !props.queue.working() && props.index === 0
```

**`_upstream/packages/app/src/session/composer/queue-panel.tsx:159-178`** —— 动作标签的**完整条件分支**（`working()` 真 ⇒ Steer，假 ⇒ Send）：

```tsx
            <Show when={!editing()}>
              <Tooltip
                placement="top"
                inactive={!props.queue.working()}
                value={language.t("session.queue.steerTooltip")}
              >
                <Button
                  data-action="session-queue-steer"
                  type="button"
                  size="small"
                  variant="ghost-faint"
                  icon="arrow-up"
                  disabled={props.queue.busy()}
                  class="![font-weight:530]"
                  onClick={() => void props.queue.steer(props.id)}
                >
                  {props.queue.working() ? language.t("session.queue.steer") : language.t("session.queue.send")}
                </Button>
              </Tooltip>
            </Show>
```

**i18n 原文** —— `_upstream/packages/app/src/runtime/i18n/en.ts:774-776`：

```
  "session.queue.steer": "Steer",
  "session.queue.send": "Send",
  "session.queue.steerTooltip": "Send without interrupting",
```

**`working()` 的来源** —— `_upstream/packages/app/src/session/composer/queue.ts:31`（入参）、`:229`（`working: input.working` 原样透出）、`:240-242`（`SessionQueueView` 切片含 `working`）。

### 上游三选一回答

> **回合 idle/parked 时，首行动作应该是 `Steer` 还是 `Send`？**

**`Send`**。且**该行必须存在**（不是"不该有该行"）—— 上游 `queue-panel.tsx:23` 的 `<Show when={count() > 0}>` 只按行数显隐，idle 不隐藏面板；`active()` 反而在 idle 时给首行加"常显"待遇。

**与本任务相关的完整判定表（上游 `working()` × `editing()`）：**

| `working()` | `editing()` | 首行动作标签 | 依据 |
|---|---|---|---|
| true | false | `Steer` | `queue-panel.tsx:175` 三元真分支 |
| **false** | false | **`Send`** | `queue-panel.tsx:175` 三元假分支 + `:92` `active()` 注释 |
| true/false | **true** | **无该动作**（`<Show when={!editing()}>` 整块不渲染） | `queue-panel.tsx:159` |

---

## 2. 本壳逐条对照

| # | 上游 | 本壳 | 一致？ |
|---|---|---|---|
| 1 | `queue-panel.tsx:175` `working() ? steer : send` | `ComposerQueue.kt:237` `steerLabel = if (running) SteerLabel.STEER else SteerLabel.SEND` | ✅ 逐条同构 |
| 2 | `queue-panel.tsx:92` `!working() && index === 0` | `ComposerQueue.kt:185` `isParkedFirst(running, index) = !running && index == 0` | ✅ |
| 3 | `queue-panel.tsx:159` `<Show when={!editing()}>` 隐藏 steer 动作 | `ComposerQueue.kt:236` `if (editing) listOf(REMOVE) else listOf(STEER, REMOVE)` | ✅ |
| 4 | `en.ts:775` `"session.queue.send": "Send"` | `res/values/strings_oc_i18n.xml:907` `session_queue_send` = `Send`（zh-rCN `:907` = `发送`） | ✅ |
| 5 | `en.ts:774` `"session.queue.steer": "Steer"` | `strings_oc_i18n.xml:908` `session_queue_steer` = `Steer`（zh-rCN = `引导`） | ✅ |
| 6 | `working()` = 会话运行态 | `Composer.kt:439` `running = runStatus == ChatStore.RunStatus.RUNNING` → `Composer.kt:1410` `running = running` 传入面板 → `ComposerQueuePanel.kt:139-140` → `ComposerQueuePanel.kt:236` `rowActions(running, editing)` → `:244-247` 选 `R.string.session_queue_send` | ✅ 同一个 `running` 一路贯通，无旁路 |

**结论：本壳 6/6 与上游一致。模型层无缺陷，`rowActions(running=false) = SEND` 早于 v130 就在（`QueueRowActionsPinTest.kt:124-135` 已钉）。**

---

## 3. 裁定与证据：为什么 v130 的 FAIL 无效

v130 的判据链（`REPORT_QUEUE_COVERAGE.md` §3.4）是：*idle ⇒ 按模型应为 `Send`，实测恒为 `Steer` ⇒ FAIL*。
**前半句对，后半句的"idle"是错的。**

### 3.1 v130 的读数脚本口径就是坏的

`research/v97_verify/5554_md/catch2.ps1:63`：

```powershell
$st = if($raw -match 'text="Stop"'){'RUNNING'}else{'IDLE'}
```

本壳 composer 主按钮的 run 态**不是 `text` 属性，而是 `content-desc`**。把 v130 自己留下的证据帧解出来即见（`parked_idle.xml`）：

```
View      ''   'Stop'   [1308,292][1356,340]      ← content-desc="Stop"，text=""
View      ''   'Working' [230,46][246,62]          ← 会话页签仍显示 Working
```

⇒ `text="Stop"` **永远匹配不到**（不存在这样的节点）⇒ 12 次轮询**每一次都掉进 else 分支被打成 `IDLE`**。所谓"12 次稳定 idle 轮询"是脚本自证的空转，不是观测。

### 3.2 证据帧自证回合仍在运行

同一帧（`research/v97_verify/5554_md/evidence/parked_idle.xml`，节点级解析）关键行：

| 节点 | 值 | 含义 |
|---|---|---|
| `View` `[1308,292][1356,340]` | `content-desc="Stop"` | **run 态 = 运行中**（`ComposerSubmit.stopping(running, "", 0) == running`） |
| `View` `[230,46][246,62]` | `content-desc="Working"` | 页签状态 = **Working** |
| `EditText` `[28,244][1338,292]` | `text=''` `focused=true` | composer 为空（v130 这条读数是对的） |
| `Button` `[1263,183][1311,231]` | `text="Steer"` | 首行动作 |

`running=true` ⇒ 按上游 `queue-panel.tsx:175` 真分支，**`Steer` 正是上游自己的答案**。

`parked.xml` / `parked3.xml` / `pre_stop.xml` 三帧同样是 `content-desc="Stop"` + `Working`。**v130 全程没有一帧是真 idle。**

### 3.3 裁定

> **裁定 ①（本壳无缺陷，不修生产代码）。**
> 上游 parked ⇒ `Send`（`queue-panel.tsx:175` 假分支），本壳同构；v130 的 FAIL 由"读数口径错误"造成 —— 把 `content-desc="Stop"` 的运行中回合判成 idle。该 FAIL **作废**。
>
> 同时**不采纳**"上游也保持 Steer"这一措辞：上游确实会翻成 `Send`，v130 的**期望**是对的，只是**证据**不成立。两者都要说清，否则下一个读者会以为"上游就是 Steer"。

### 3.4 本次唯一的改动：把读数口径钉死（防重犯）

v130 这次错误的性质是"**判据可以被一个属性名写错而静默反转**"。故新增一条 pin（`QueueRowActionsPinTest.theComposerButtonIsTheRunStateOracleTheLabelMustAgreeWith`），钉的是**"按钮态 ⇔ 标签"必须同源一致**这个不变式，而不是复述结论：

- `ComposerSubmit.stopping(running, "", 0) == running`（按钮是 Stop **当且仅当**空 composer 且运行中）；
- 且此时 `rowActions(running).steerLabel` 必须与之相符（Stop ⇒ `Steer`）；
- 并钉住前置条件：composer 有文本/附件时按钮**不是** Stop（`running=true` + 文本 ⇒ 仍是发送键），所以"按钮不是 stop"也不能单独证 idle。

⇒ 今后任何把 run 态读错的 sweep，会在这里红，而不是带着一条假 FAIL 上报。

---

## 4. diff

**生产代码：0 改动**（`ui/session/**`、`app/**` 主源码均未触碰）。唯一改动是测试 pin：

```diff
--- a/app/src/test/java/com/xiufeigo/opencodeshell/QueueRowActionsPinTest.kt
+++ b/app/src/test/java/com/xiufeigo/opencodeshell/QueueRowActionsPinTest.kt
@@ imports @@
 import com.xiufeigo.opencodeshell.ui.session.composer.ComposerQueue
+import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSubmit
@@ 新增 @@
+    // ---------------------------------------- one run state, two readings (v133)
+
+    @Test
+    fun theComposerButtonIsTheRunStateOracleTheLabelMustAgreeWith() {
+        // v133 (`research/v97_fix/PARKED_ROW_ACTION.md`) …
+        for (running in listOf(true, false)) {
+            val buttonIsStop = ComposerSubmit.stopping(running = running, text = "", attachmentCount = 0)
+            assertEquals(
+                "the composer button is Stop exactly while a blank composer is running (running=$running)",
+                running,
+                buttonIsStop,
+            )
+            assertEquals(
+                "a Stop button proves the turn is running, so the row must read Steer (running=$running)",
+                if (running) ComposerQueue.SteerLabel.STEER else ComposerQueue.SteerLabel.SEND,
+                actions(running = running).steerLabel,
+            )
+        }
+        assertFalse(ComposerSubmit.stopping(running = true, text = "typed follow-up", attachmentCount = 0))
+        assertFalse(ComposerSubmit.stopping(running = true, text = "", attachmentCount = 1))
+    }
```

`git diff --stat`：**1 file changed, 43 insertions(+)，0 deletions(-)** —— 纯新增，未放宽/删除任何既有断言。

---

## 5. 反向验证原文（证明 pin 非空转）

把新 pin 的期望**故意倒置**（`STEER/SEND` 互换）后重跑：

```
Execution failed for task ':app:testDebugUnitTest'.
BUILD FAILED in 3s

tests=11 failures=1 errors=0
FAILED: theComposerButtonIsTheRunStateOracleTheLabelMustAgreeWith
java.lang.AssertionError: a Stop button proves the turn is running, so the row must read Steer (running=true) expected:<SEND> but was:<STEER>
```

随后**改回正确方向**并复跑 ⇒ `BUILD SUCCESSFUL`（见 §6）。⇒ pin 对"标签与按钮态不同源"确实会红。

---

## 6. 测试证据

命令（仅允许的单类白名单形式，禁 assemble/clean/全量/装机）：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.QueueRowActionsPinTest" `
  --tests "com.xiufeigo.opencodeshell.Wave24bComposerQueueTest" `
  --tests "com.xiufeigo.opencodeshell.QueueEditLoanExitV131Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerSubmitTest"
```

原文结尾：

```
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 3s
52 actionable tasks: 2 executed, 50 up-to-date
```

| 类 | 用例数 | 失败 | 错误 |
|---|---|---|---|
| `ComposerSubmitTest` | 11 | 0 | 0 |
| `QueueEditLoanExitV131Test` | 14 | 0 | 0 |
| `QueueRowActionsPinTest` | **11**（原 10 + 新增 1） | 0 | 0 |
| `Wave24bComposerQueueTest` | 23 | 0 | 0 |
| **合计** | **59** | **0** | **0** |

**用例数未减少**（`QueueRowActionsPinTest` 10 → 11）。日志：`research/v97_fix/_v133_regression.log` / `_v133_rev1.log` / `_v133_final.log`。

---

## 7. 为什么不降强度

- **未改任何断言**：`QueueRowActionsPinTest` 既有 10 个用例、`Wave24bComposerQueueTest` 23 个用例一字未动；diff 只有新增（+43/-0）。
- **未改生产代码**：`ComposerQueue.kt:237` 的 `if (running) STEER else SEND` 就是上游 `queue-panel.tsx:175` 的直译，**它是这次裁定的依据**，不是待修对象。改它反而会把"忠实"改成"背离上游"。
- **断言方向没有被"迁就"**：新 pin 断言的是"按钮 Stop ⇒ 标签 Steer"，正是 v130 误读的反面。它锁死的是同源一致性，不是为了迎合某个结论。
- **i18n 资源未新增、未改动**（`Send`/`Steer` 早已存在于 `strings_oc_i18n.xml:907-908`），未引入任何用户可见新字符串。
- **禁区零触碰**：`_upstream/`（只读）、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers/**`、`ui/home/**`、`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt` 均未修改；`git status` 仅 1 个测试文件 + 本报告 + 1 个取证脚本。
- **行尾纪律**：全程只用编辑工具（`edit`/`write`），未用 git checkout/回退改行尾。

---

## 8. 真机复验步骤（建议补做，闭合 §9.1）

本轮**未装机、未改生产代码**，故无强制真机复验项。但 v130 那条 FAIL 若要在真机上彻底结案（而不只是"读数作废"），应按**修正后的口径**重跑一次：

1. 前置：长回合 `count from 1 to N`（composer 有内容）→ 点 `Queue` chip 开队列条。
2. `Stop` 后**不要**用 `text="Stop"` 判态；改用
   `uiautomator dump` 后判 `content-desc="Stop"`（`View` 节点）与页签 `Working` 是否消失。
3. 真正的 idle 判据（三条同时满足）：页签 `Working` 消失 **且** 主按钮 `content-desc` 变为 `Send` **且** composer `text=''`。
4. 此时读首行动作标签：按上游**应当**为 `Send`（`queue-panel.tsx:175` 假分支）。
5. 反向复核：再起一回合 ⇒ 页签 `Working` 出现、按钮回 `content-desc="Stop"` ⇒ 首行应回落 `Steer`。
6. 关键纪律：**若第 3 步任一条不成立（尤其是按钮仍是 `Stop`），就不得判 idle** —— 这正是 v130 翻车的位置。

---

## 9. 未闭环

1. **未在真机上取得一帧真 idle 的 parked 证据**。本轮裁定基于"v130 证据帧自证仍在运行"这一**否定性**证明（该 FAIL 前提不成立），逻辑上已足以作废 FAIL；但"idle 时首行确实显示 `Send`"这一**肯定性**观测仍缺一帧真机对照（§8 步骤 3-5）。模型与上游同构已钉死，风险低。
2. **v130 报告本体未改**。`REPORT_QUEUE_COVERAGE.md` §3.4/§5 仍留着"格三 FAIL"字样。本轮按"先裁定、只出声明"的边界，未越权改写他轮报告正文（该文件在 `research/**` 可写范围内，但改写他人结论需其自身复核）。**建议下一轮在 v130 报告加一条作废批注指向本文件**，避免下游把 FAIL 当活缺陷跟进。
3. **`catch2.ps1` 的判态缺陷未修**（它在 `research/**` 可写范围内）。它是一次性取证脚本、非产品代码，且已被本报告记录；但若后续复用该脚本，仍会复现同一误判。
4. **v130 §4.2 遗留项不在本轮范围**：`queueEditOpen` 卡死（v131 已修）、拖拽重排"不支持"、时间线自建消息未清理、`/sdcard` 44 项残留。

---

## 10. 交付物索引

| 文件 | 用途 |
|---|---|
| `research/v97_fix/PARKED_ROW_ACTION.md` | 本报告（先写盘抢锁 → 定稿释放） |
| `research/v97_fix/_parked_probe.py` | 证据帧节点级解析脚本（还原 §3.2 的读数） |
| `app/src/test/java/com/xiufeigo/opencodeshell/QueueRowActionsPinTest.kt` | 新增 1 条 pin（+43/-0） |
| `research/v97_fix/_v133_regression.log` / `_v133_rev1.log` / `_v133_final.log` | 回归 / 反向验证 / 复绿 三次运行原文 |
