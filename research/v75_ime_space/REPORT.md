# v75-IME：尾随空白"提交"丢失 — 根因 / 修复 / 证据（5554 真机，2026-09-30）

对象：`com.xiufeigo.opencodeshell`，桶=**输入处理层**（ComposerImeCommit / HalfWidthText / setText / submit 边界）。
**未触碰** `SessionScreen`/`Composer` 的 slot 结构（movableContentOf 那一路归另一桶）。

---

## 0. 结论（一句话）

- **报障现象（分段 `window ` + `test` → 落盘 `windowtest`，1/5）在 5554 上可复现，但复现条件是「以空白结尾的分段用*字面*尾随空格注入」——`adb shell input text 'window '` 的尾随空格在**进 app 之前**就被参数通道吃掉了**：同一个命令在**系统 Settings 的普通 EditText** 里同样得到 `windowtest`（app 完全不参与），而把空格写成 `input` 命令自己的转义 `%s`（`input text 'window%s'`）则空格完整落进字段，并且**跨分段边界 10/10 不丢**（uiautomator dump 逐步验证字段里确实带尾随空格）。
- **composer 输入链是逐字节直通**（源码级证明 + 设备实测）：IME 交出的批次文本 → `adopt` 从不改 `.text` → `setText` 只做 `adopt` + chip 删除规则（无 token 时恒 null）→ `ComposerDrafts.set` 原样存储。所以「app 丢了 IME 交付的空白」在这条链上不可能发生。
- **app 侧确实存在一处「把 IME 已提交的尾随空白丢掉」**，而且正好是报障标题里的**提交边界**：`Composer.submit()` 原来发 `HalfWidthText.normalize(value.text).trim()`。上游不是这样（`request.ts:128` 发 `input.text` 原文，`trim()` 只做空串闸门 `submit.ts:200-203`）。**已修**（本轮唯一行为改动）。
- 测试脚手架规则（下轮复验必守）：**任何必须完整落地的文本，要么用 `%s` 表达空格、要么逐字符 KEYCODE、要么对 uiautomator dump 做校验后重打**（`research/adbui.ps1` 的 `Send-UiText` 本来就是这么做的）；**不要用字面尾随空格**。

---

## 1. 设备证据（emulator-5554，Medium_Desktop 1344x2992@480，Gboard 英文 QWERTY，`show_ime_with_hard_keyboard=1`）

读字段用 `uiautomator dump`（Compose 的 `EditableText` 语义就是字段值，尾随空格在 XML 属性里可见）。
`read_field` = `uiautomator dump /sdcard/c.xml` 后取 `class="android.widget.EditText"` 的 `text="…"`。

### 1.1 报障流程（会话页 composer，`x x` 会话，空输入框起）

段间 sleep 1.5，每段后 dump：

| 段 | 字段内容 |
|---|---|
| `input text 'hello%s'` | `text="hello "` |
| `input text 'short%s'` | `text="hello short "` |
| `input text 'window%s'` | `text="hello short window "` |
| `input text 'test'` | `text="hello short window test"` |

**5 轮全同（20 个「以空白结尾的批次」全部完整）**；另外一轮在 `hello ` 之后按 ESC 收键盘（composer 形态翻转）再点回输入框继续打，字段仍是 `hello ` → `hello short ` → … → `hello short window test`。⇒ 分段边界不丢，`%s` 路径下 0 丢失。

### 1.2 报障同症状的复现（关键：**不是 app**）

在**同一个 composer**里：

| 注入 | 字段内容 |
|---|---|
| `input text 'window '`（字面尾随空格）+ `input text 'test'` | **`text="windowtest"`** ← 报障原样 |
| `input text 'window%s'` + `input text 'test'` | `text="window test"` |
| `input keyevent 62`（KEYCODE_SPACE）作独立命令，`x`/`y`/`ab`/`cd` 分段 | `text="x y ab cd"` |
| 无 sleep 连打（`hello%s`+`short%s`+`window%s`+`test` 一条接一条） | `text="hello short window test"` |
| 单字母+空格分段 ×5 轮（`x%s`/`y%s`/`z%s`/`end`） | 5/5 `text="x y z end"` |

**对照组：系统 Settings 的普通 Android EditText**（`com.android.settings` 搜索框，**app 不参与**）：

| 注入 | 字段内容 |
|---|---|
| `input text 'window '` + `input text 'test'` | **`text="windowtest"`** |
| `input text 'window%s'` + `input text 'test'` | `text="window test"` |

⇒ 字面尾随空格在**注入参数通道**（adb/`input` 侧）就没了，与 app、与 Compose、与 IME 都无关；`%s`（`input` 命令自身的空格转义）完好。这也解释了报障里「段内空格正常 / 21 字符逐字符完整 / 只在少数轮次出问题」：**只有以字面尾随空格结尾的那一次注入会丢**（另外 4 轮正常 ⇒ 那几轮用的是 `%s`）。

---

## 2. app 侧审计（为什么「composer 链丢尾随空白」不成立）

对着本 build 编译的库源码（`foundation-android:1.7.6` / `ui-android:1.7.6`，见 §4）逐段核过：

1. `HalfWidthText.normalize` / `toAscii` / `normalizeName` 都是**严格 1:1**：只把 `／`→`/`、`＠`→`@`、`U+3000`→`' '`（名称规则是整段 `U+FF01..U+FF5E`），**从不删字符**，无 `trim/trimEnd`。
2. `ComposerImeCommit.adopt` 只折 range（selection/composition 夹回 `[0, text.length]`，丢掉 collapsed-at-0 的组合区），**恒定保留 `input.text`**；非法值构造出的新 `TextFieldValue` 文本仍然一模一样。app 全仓**没有任何地方设置 composition**（grep `composition` 只有 recomposition 语义的注释）。
3. `setText`（每次 `onValueChange`）= `adopt(next)` → `MentionTokens.snapImeDeletion(...)`（**只在 chip 上、且只在「恰好删掉一个字符」时**返回删除；插入批次恒 null）→ `value = snapped` → `ComposerDrafts.set(draftKey, snapped.text)`。⇒ 打字期间 app 对文本是**直通**。
4. 框架侧也不会「改写后才交给 app」：IME 批次经 `TextFieldDelegate.onEditCommand` → `EditProcessor.apply(ops)` → `session.updateState(null,newValue)` → `onValueChange(newValue)`；下一次 recomposition 的 `EditProcessor.reset(value, session)` 见到 `mBufferState == value`（文本/选区/组合区都没变）即 **early return，不触发 `restartInput()`**。硬件字符走 `TextFieldKeyInput` 的 `List<EditCommand>.apply()`，同样由 `mBufferState` 自洽更新。
5. **唯一真的会丢尾随空白的地方**：`submit()` 的 `.trim()`（§3）。

---

## 3. 修复（本轮唯一行为改动）

`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt` — `fun submit()`：

```kotlin
val outgoing = HalfWidthText.normalize(value.text)   // 交给 payload 的就是编辑器原文（1:1 折叠）
val trimmed = outgoing.trim()                        // 只做空串闸门 + /name / alias / /btw 形状解析
if (!ComposerSubmit.canSubmit(trimmed, attachments.size, shellMode)) return
```

- 发给 payload / `onSend` / history 的仍然是 `outgoing`（历史内部本来就会 trim，上游 `entry.ts:40` 同款），
  `/name`、alias、`/btw`、`canSubmit` 仍然读 `trimmed` —— 与改动前**逐字节同值**，只有「出站文本」从 trim 后变成原文。
- 上游锚点：`composer/request.ts:128` `...(input.text.trim() ? [input.text] : [])`（发送的是 `input.text` 原文，`trim()` 只判空）、`composer/submit.ts:200-203`。`composer/editor/editor.tsx:389` 的 `parseComposerEditor` 不 trim（text part 的 `content` 就是 buffer 原文）。
- 影响面：`Shift+Enter` 换行、尾随空格、`U+3000` 折成的空格，都按用户所见的原文落盘；空串闸门/命令路由不变（`isNotBlank()` 语义）。

**未做（有意，写明理由）**：
- 未把编辑器 `KeyboardOptions` 的 `autoCorrectEnabled` 关掉：上游 normal 模式是 `autocorrect="on"`/`spellcheck` 开（`editor.tsx:186-188`，规格表 `research/02_ui_screens.md:1030-1031`），关掉即为偏离；而且 shell 模式改键盘选项会经 `CoreTextField` 的 `DisposableEffect(imeOptions)` 触发 `restartInput()` —— 正在报障的「IME 重启窗口」上再添一次重启，不划算。**留待产品裁决**（若裁决关，建议只关 shell 模式，与规格表一致，并记入 HANDOFF 偏离表）。
- 未动 `ComposerDrafts.appendLine` 的 `trimEnd()`（review 行追加路径，去掉会在追加行前留空行）与 `PromptHistory.add` 的 trim（上游同款）。

---

## 4. 文件 / 测试 / 闸门

**改动文件**
| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt` | `submit()`：`outgoing` 原文 + `trimmed` 闸门/解析；KDoc 增 v75 段（唯一行为改动） |
| `app/src/test/java/com/xiufeigo/opencodeshell/ComposerWhitespaceBatchV75Test.kt` | **新增** 9 例 |
| `app/src/test/.../Wave5HalfWidthInputTest.kt` | 过时钉更新（旧钉钉的正是 `normalize(value.text).trim()`）：改钉「折叠 1:1 + 闸门 trim、payload 不 trim」 |
| `app/src/test/.../ComposerSlashArgumentsTest.kt` | 过时钉：`canSubmit(outgoing,…)`→`canSubmit(trimmed,…)`、`btwQuestion(outgoing)`→`btwQuestion(trimmed)` |
| `app/src/test/.../ComposerBtwRoutingTest.kt` | 同上 3 处（`btwQuestion(trimmed)`，含多行字面量） |
| `app/src/test/.../Wave24bComposerWiringTest.kt` | `invocation(outgoing, known)`→`invocation(trimmed, known)` |

**新增 9 例**（`ComposerWhitespaceBatchV75Test`）
1. `theEditingFoldNeverDropsATrailingWhitespaceCharacter`（10 个以空白结尾的批次：长度与尾部都保留；`／`/`＠`/`U+3000` 才重写；无改写时**引用相等**=不额外重组）
2. `theComparisonAndNameFoldsAreLengthPreservingToo`（`toAscii`/`normalizeName` 同样 1:1，`U+3000`→`' '`）
3. `aWhitespaceTerminatedBatchReachesTheEditorTextIntact`（4 种批次形状：普通提交 / 组合区盖住尾随空格 / collapsed-at-0 组合区 / 越界 range；外加「只提交一个空格」→ `.text` 全部逐字节保留）
4. `anAlreadyLegalBatchIsNotRebuilt`（合法值 `assertSame`，不产生回写/重启）
5. `theChipRuleNeverTouchesAnInsertionBatch`
6. `theOnlyTextRemovingRuleIsTheChipItself`（唯一会删文本的规则=chip 及其分隔空格，`Deletion(6,15)`；离开 chip 恒 null）
7. `theSubmittedTextKeepsTheCommittedWhitespace`（submit 源码钉：payload/onSend/history 都是 `outgoing`；且**断言旧的字面量已不存在**）
8. `theGateAndTheParsersStillReadTheTrimmedShape`
9. `thePayloadModelCarriesTheWhitespaceBatch`（`ComposerSubmission.build(text=…)` 到 VM 前不归一化）

**闸门结果**
- `bash .agent-run-tests.sh ComposerWhitespaceBatchV75Test` → `GRADLE_EXIT=0`（9/9）
- `bash .agent-run-tests.sh ComposerSubmitTest ComposerSlashArgumentsTest ComposerBtwRoutingTest Wave24bComposerWiringTest Wave5HalfWidthInputTest ComposerImeCommitTest ComposerCaretAdoptV63Test ComposerM1_5Test Wave5ComposerWiringTest MentionTokensAtomicDeleteV68Test` → `GRADLE_EXIT=0`（10 套件，含全部被我改钉的套件）
- `./gradlew :app:compileDebugKotlin` → exit 0
- 无新增用户可见串（`submit()` 只处理文本，不动 string 资源）⇒ 三语无待补项。（全量 552 suites 基线归主 agent）
- 注：本轮出现过一次 `:app:compileDebugUnitTestKotlin` 编译失败，随即自愈——同一工作树上有另一桶在改 `SessionScreen/Composer` 的 slot 结构，属其半成品状态，与本改动无关。

---

## 5. 下轮真机复验清单（给测试）

1. **不要用字面尾随空格**：`adb shell input text 'window '` 在**任何**字段都会丢（§1.2 已证）；用 `input text 'window%s'`、或 `input keyevent 62`、或 `research/adbui.ps1` 的校验式输入。
2. 报告式复验（5 轮，段间 1.5s，每段后 dump 校验）：
   `input text 'hello%s'` → `'short%s'` → `'window%s'` → `'test'`，期望字段逐步为 `hello ` / `hello short ` / `hello short window ` / `hello short window test`（尾随空格在 dump 属性里可见）。
3. 提交边界复验：输入 `hello `（尾随空格）→ 发送 → 会话里/服务端 `POST /api/session/:id/prompt` 的 `text` 应为 `hello `（**本轮修的就是这一处**）。
4. 若仍想追 1/5 那条：**只在中文（拼音）Gboard 布局下**重复第 2 步；本仓已有测量表明拼音布局会重写批量注入的文本（`research/adbui.ps1:55-62`：`opencode`→`open code`、`http://127.0.0.1:4095`→`htt//1..7.001:495`；`research/wave5_composer.md:482`；HANDOFF §42/§64）。那是 IME 侧，不是 app；复验时请顺带记下当时的 Gboard 布局（英文/拼音）。

## 6. 仍未闭环（不阻塞）

- 用户报障那一次（`%s` 路径下的 1/5）在本轮设备状态（英文 Gboard）**无法复现**：0/6 批次丢失、field 级 dump 全部保留。剩余唯一可解释机制=拼音/组合 IME 对批量注入键的重写（见 §5.4），需在中文布局下再取一次证据才能定案。
- 规格 `research/02_ui_screens.md:1030-1031` 的 **shell 模式**键盘行（`autocapitalize=off` / `autocorrect=off` / `spellcheck=false`）未实现（normal 模式的上游值反而已合规）：见 §3「未做」的理由与代价。
- `ComposerImeCommit` 残余风险（本文件 2200 行附近的既有记录）：app 侧**主动**写回（粘贴/插入/accept/清空/历史回填）会触发 `restartInput()`，那是 IME 重启窗口；本轮未新增任何主动写回。
