# v98 D3 修复报告 — Add/Edit SSH 表单「字段不可达」：口令字段落在裁切线外、被剔出 a11y 树，表单根本提交不了

**执行人**：修复工程师（Add/Edit SSH 表单可达性域） **日期**：2026-10-03
**输入**：`research/v97_verify/5554_v98d3/REPORT_COVERAGE4.md`（第四轮）、`REPORT_COVERAGE5.md`（第五轮，落库 ground truth 定性）
**同类先例**：`research/v97_fix/SSH_DELETE_FIX.md` —— 删除确认条曾落在列表滚动视口之外，修法＝`bringIntoViewRequester`

> **纪律自证**：只改 `ui/servers/**` 与 1 个新测试文件；未碰 `_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/session/**`、`ui/home/**`、`ui/terminal/**`、`OcActivity.kt`、`ui/App.kt`、`ui/nav/**`；gradle **前台跑完**，跑前抢锁、跑完删锁（`LOCK_ACQUIRED after 0s` → `LOCK_RELEASED`）；**只跑** `:app:testDebugUnitTest --tests "<类名>"`，**未 assembleDebug / 未 clean / 未装机**；未新增任何用户可见字符串；未放宽任何校验；未降低任何既有断言强度。

---

## 0. 结论先说

> **根因一句话**：表单的滚动宿主存在（`SshDialog.kt:408` `verticalScroll`），但**没有任何一个必填字段挂着 bring-into-view 句柄** —— v90 的那个句柄只绑在**反馈组**上（`SshDialog.kt:527`），所以 `validateSshForm` 拒口令时，滚回来的是**一行红字**，不是**要填的框**；口令框又恰好是 `PASSWORD` 分支里最深的字段，永远落在 band 裁切线以下、被语义树剔除，于是**必填字段既到不了、也进不了 a11y 树**，`onSubmit` 永不执行。

**修法**：给**每个**字段（含 name / passphrase 这两个非必填的）各挂一个自己的 `BringIntoViewRequester`，并在**校验被拒时**把该次拒绝所指的字段拉进视口。**校验逻辑、启用条件、字符串、band/floor/lift 算术一律不动。**

> **⚠️ 本报告未闭环的关键一项**：修复**未经真机复验**（本域纪律禁止装机/跑 gradle 之外的真机流程，本轮只获授权跑 JVM 闸门）。因此"修后真机能否建出条目"是**待验**而非**已证**。见 §7 真机复验步骤与 §8 未闭环。

---

## 1. 定性：Add/Edit SSH 表单的容器结构与逐字段可达性

### 1.1 容器与滚动体（源码 `文件:行号`）

```
OcActivity / 面板层
└─ Servers 面板 body ──── 垂直滚动体（面板自己的 Column.verticalScroll）
   └─ 面板 flow 第 2 个追加卡片 = SshDialog 的卡
      ├─ 外层 Column  SshDialog.kt:366-393
      │   .width(width) .onGloballyPositioned{} .offset(y = -lift)
      │   .then(if (lift > 0.dp) serverDialogLiftShield())
      │   .then(if (ime>0 && panelH>0) Modifier.height(band)      // :387 键盘↑：卡就是 band
      │         else Modifier.heightIn(max = band))                // :389 键盘↓：封顶
      │   .background .border
      │
      ├─ 表单 Column（★ 唯一的滚动体） SshDialog.kt:403-409
      │   .weight(1f, fill = false)      // :405
      │   .verticalScroll(rememberScrollState())   // :408
      │   .padding(start/end/top = 24.dp)
      │   → 卡内垂直Scroll 计数 == 1（v90 既有 pin 钉住）
      │     内容：标题 / hairline / Host 标签+框 / Name 标签+框 /
      │           Authentication 标签 + OcSelect /
      │           when(auth) 分支字段 / （有反馈时）反馈组
      │
      └─ 固定页脚 Column  SshDialog.kt:560-586   ← 不滚动，pin 在卡底
          Cancel / Add server（:583 `enabled = !busy && target.isNotBlank()`）
```

**结论：Add 与 Edit 走的是同一个 `SshDialog` composable**（本仓无独立的 Edit 变体；编辑复用同一表单 + `SshConnectionConfig.id`），所以下表对两者同时成立。

### 1.2 逐字段可达性表（1080x2424 / Pixel_9a，density 2.75）

设备读数取自 `REPORT_COVERAGE4.md` §2.1/§2.3、§2.5，`REPORT_COVERAGE5.md` §4.3。`可滚动到`=能出现在 a11y 树里；`可点击`=坐标可命中。

| # | 字段 / 控件 | 源码位置 | 所在滚动体 | 修前：设备 bounds（代表帧） | 修前可达？ |
|---|---|---|---|---|---|
| 1 | 标题 `Add SSH server` | `:426` | 表单 scroller | `[106,1016][401,1063]` | ✅ 在树内 |
| 2 | `Host or SSH command` 标签 | `:429` | 表单 scroller | `[106,1128][471,1169]` | ✅ |
| 3 | **Host 框**（EditText role0） | `:430-441` | 表单 scroller | `[138,1186][943,1312]` h=126 | ✅ 满高可达 |
| 4 | `Server name (optional)` 标签 | `:442` | 表单 scroller | `[106,1328][473,1329]` h=**1** | ⚠️ 被裁成 1px |
| 5 | **Name 框**（role1） | `:443-452` | 表单 scroller | `[138,1386][723,1392]` h=**6** | ⚠️ 仅 6px 残片 |
| 6 | `Authentication` 标签 | `:453` | 表单 scroller | 可在树内 | ✅ |
| 7 | `OcSelect` 认证选择器 | `:454-467` | 表单 scroller | `Password` 默认值在树内 | ✅ |
| 8 | `Password` 字段标签 | `:470` | 表单 scroller | `[106,1094][264,1135]` | ✅ **标签在、框不在** |
| 9 | **口令框**（role2）★ | `:471-484` | 表单 scroller | **11 个滚位从未出现** | ❌ **永远到不了** |
| 10 | 反馈组（错误行） | `:526-545` | 表单 scroller | `[106,1294][631,1329]` | ✅（v90 修好的） |
| 11 | `Cancel` | `:575` | 固定页脚（不滚动） | `[554,1779][665,1820]` | ✅ |
| 12 | `Add server` | `:582` | 固定页脚（不滚动） | anc `[723,1736][975,1862]` | ✅ |

### 1.3 为什么"提交按钮能点到、口令字段到不了"——滚动范围与内容高度不一致在哪

关键在于**两个滚动的职责是错配的**：

| 量 | 值 | 来源 |
|---|---|---|
| 面板 body 视口 | `[43,800][1038,1937]` = 1137 px ≈ 413 dp | 设备读数 |
| 表单 body 视口（内层） | `[43,953][1038,1329]` ≈ 376 px ≈ **136.5 dp** | 设备读数 |
| 表单内容到口令框 | ≈ 600 px ≈ **218 dp** | 设备读数 |
| band 下限 | `SERVER_DIALOG_WANTED_BAND_DP` = 80.5（页脚）+ 56（表单地板）= **136.5 dp** | `SettingsComponents.kt:879-880` |

**算术**：表单 scroller 的视口被 `serverDialogBandDp`（`SettingsComponents.kt:1085-1088`）夹在
`maxOf(minOf(maxOf(spaceDp, 136.5), roomDp), 80.5)` —— 当键盘弹出、`spaceDp` 不足时，`band` 被**地板 136.5 dp 顶住**，
外层 `:387` 用 `Modifier.height(band)` 把整卡钉死成 band 高。于是内层 scroller 的视口只剩 **136.5 dp**，
而内容是 218 dp ⇒ **可滚动范围只有约 81.5 dp（≈224 px）**。

- **页脚为什么能点到**：页脚在 `:560` 之后的**第二个 Column** 里，它**不是** scroller 的子节点，而是被 `band` 硬钉在卡底。键盘弹出时 `:387` 的 `height(band)` 保证它落在 band 内（v88 的成果），所以它**永远在视口内、永远可点**。第五轮实测 `anc_en=true`、anc 坐标两处读数都对，**按钮可达性无缺陷**。
- **口令框为什么到不了**：它位于 scroller **内容流的最末端**（`when (auth)` 分支之后、反馈组之前）。136.5 dp 的视口要装 218 dp 内容，头部永远占着前面那 81.5 dp 之外的位置；而**更要命的是第五轮 §4.3 的裁切事实**：

> 「**内层视口只有 376px 高，而表单内容到口令框约 600px ⇒ 口令框恒在视口外 ⇒ 被语义树剔除**」

也就是说：**即使把 scroller 滚到底**，`role2` 也一次都没进过 a11y 树（两轮共 11 个滚位，`EditText` 数量恒为 2）。原因是**外层还有第二个裁切面**：这张卡被画在 Servers 面板自己的 scroller 里（`REPORT_COVERAGE4.md` §2.3(1) 实测的嵌套 `ScrollView`：`[43,800][1038,1937]` 包着 `[43,953][1038,1329]`）。**内层滚到底时，口令框仍在外层面板 scroller 的可视区之外** —— 单靠内层 `verticalScroll` 的范围**够不到**它。

**这就是为什么"表单根本提交不了"**：
1. 用户把 host 填好 → `Add server` 的 `enabled` 翻成 `true`（第五轮 `anc_en=true` 前后对照实测）；
2. 点下去 → `:241` `validateSshForm(...)` 判定 `auth == PASSWORD && password.isNullOrEmpty()` → 返回 `PasswordRequired`；
3. `:243` `error = invalid` + `return` → **`onSubmit` / `putSshEntry` / `sshHost.submit` 全不执行**（第五轮 §4.3 的因果链）；
4. v90 的 `feedbackRequester` 把**错误行**拉回视口 —— 用户被要求"输入连接口令"，**而那个框不在屏幕上、也不在 a11y 树里**；
5. 落库 ground truth 逐字节不变（`GT_KEY_DELTA=0`）、UI 自建行 0 条 ⇒ **提交从未成功**。

**"提交按钮能点到但口令字段到不了" = 按钮在 band 的硬约束内（`height(band)` 保证），口令在 scroller 的软约束内（两个嵌套裁切面 + 无句柄）**。前者由 v83–v89 的几何钉住，后者**从来没有任何代码管过**。

### 1.4 逐条排除（任务要求的 6 项）

| 候选 | 判定 | 依据 |
|---|---|---|
| `Modifier.verticalScroll` **缺失** | ❌ **排除** | `:408` `.verticalScroll(rememberScrollState())` 存在；设备实测表单 body 就是 `scrollable=true` 的 `ScrollView`（`REPORT_COVERAGE4.md` §2.1），且滚动**真的生效**（6 个滚位 bounds 都在变） |
| `imePadding` **缺失** | ❌ **排除（且本就不能补）** | 该卡刻意不用 `imePadding`：键盘高度由 `windowImeBottomDp()` 直读、`band` 算术消费（`:345-365`），SshSubmitChainTest 已有 `assertFalse(card.contains("imePadding"))` 钉住。补它会造成二次消费 |
| `LazyColumn` 里放表单 | ❌ **排除** | 全文无 `Lazy*`；表单是普通 `Column` + `verticalScroll`（v90 pin 亦钉住卡内 scroller 计数 == 1） |
| `weight(1f)` 把表单压扁 | ⚠️ **部分成立，但不是根因** | `:405` `weight(1f, fill = false)` 把视口压到 band 剩余高度，**这是 136.5 dp 视口的来源**；但它压扁的是**视口**不是**内容**，且 `fill = false` 保证内容高度不被截（否则会连反馈组也丢）。真正让字段不可达的是**内容末端无人请求** |
| `bringIntoViewRequester` 只绑了**部分**字段 | ✅ **根因** | 修前全文只有 1 个 `bringIntoViewRequester`，绑在**反馈组**（`:461`→现 `:527`）。**5 个字段一个都没有句柄**。字段是"被画出来的"，但没有任何代码在需要时替它说话 |
| `clearAndSetSemantics` 剥掉节点 | ❌ **排除** | 全仓 `ui/servers/**` 无 `clearAndSetSemantics`。节点是**因裁切被 Compose 的 semantics 树自动剔除**（clip 之外不进 a11y 树），不是被代码剥掉的 |
| **嵌套 scroller / 面板外层裁切** | ✅ **并存根因** | 嵌套 `ScrollView` 实测（`REPORT_COVERAGE4.md` §2.3(1)）；内层滚到底仍到不了 ⇒ 需要**能走完整响应者链**的请求，而非单纯加大内层滚动范围 |

---

## 2. 修（最小、可解释；安全边界不动）

### 2.1 改了什么

**新增** `app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshFormReach.kt`（可达性词汇表，纯 JVM 可测）：

- `enum class SshFormField { Target, Name, Password, PrivateKeyPath, Passphrase }`
- `fun sshFormErrorField(error: SshFormError): SshFormField` —— **对 sealed interface 穷尽**（`when` 无 else）：新增校验分支若不给字段，**编译不过** ⇒ 不可能再出现"只有红字、没处可填"
- `fun sshFormRequiredFields(auth)` / `fun sshFormDrawnFields(auth)` —— **两个集合刻意分开**：`drawn`（要可达）与 `required`（被校验要求）。把它们合成一个，就是"可达性改动悄悄变成必填性改动"的后门
- `fun sshFormFieldsAllReachable(auth, reachable)` —— v98 缺陷的判据：**画得出来却拉不回来**

**修改** `SshDialog.kt`（仅 3 处，详见 §3 diff）：

1. `:167-178` 每个字段一个 `remember { BringIntoViewRequester() }`，汇入 `fieldRequesters` map（键是 `SshFormField` ⇒ 句柄不可能绑错字段）
2. `:181-187` `suspend fun revealRefusedField(refusal)`：`withFrameNanos { }` 让节点附着并布局后，`runCatching { fieldRequesters[sshFormErrorField(refusal)]?.bringIntoView() }`
3. `:222` 在 **v90 已有的那个 `LaunchedEffect(submitAttempts, feedback)` 里**追加 `if (refusal != null) revealRefusedField(refusal)` —— 复用同一个 attempt key，所以**重复点同一次拒绝照样会揭示**

**为什么这样就够**：`BringIntoViewRequester` 的请求**沿响应者链向上走**（Compose 1.7 的 `ScrollableNode` 委派 `BringIntoViewResponderNode`），会同时经过**表单内层 scroller** 和**面板外层 scroller** —— 这正好对上 §1.3 定性的"两层嵌套裁切"。**不需要**改 band、不需要加大滚动范围、不需要 `imePadding`。

### 2.2 为什么没有放宽安全边界（逐条自证）

| 安全边界 | 状态 | 机器可读证据 |
|---|---|---|
| **"口令必填"未被改成可选** | ✅ **原样** | `SshModel.kt:107` `auth == SshAuthMethod.PASSWORD && password.isNullOrEmpty() -> PasswordRequired` **一字未改**（本轮未触碰 `SshModel.kt`）。pin `theGateStillDemandsThePassword` 用 `null` / `""` 两种输入断言 `PasswordRequired` |
| **校验仍在 `onSubmit` 之前 return** | ✅ **原样** | `SshDialog.kt:241` gate → `:243` `error = invalid` → `return`，`onSubmit` 在其后。pin `thePrimaryActionKeepsItsGate` 断言 `gate < refusal < handOver` 且拒绝块以 `error = invalid` 起、以 `return` 收 |
| **`Add server` 启用条件不变** | ✅ **原样** | `:583` `enabled = !busy && target.isNotBlank()`（v90 pin 已钉，本次原样保留） |
| **可达性改动只在呈现层** | ✅ **证明** | `revealRefusedField` 只调 `bringIntoView()`，**从不写 scroll state**；`rememberScrollState()` 全文仍恰好 1 处（pin 断言）。pin 亦断言 `submit()` 函数体内 `不含 bringIntoView` ⇒ 提交路径与呈现路径物理隔离 |
| **私钥/passphrase 语义不变** | ✅ **原样** | `toSshEntry()` 仍不落任何 secret（`SshTunnelForget.kt:138-149`），本轮未触碰 |
| **未新增用户可见字符串** | ✅ **0 条** | 改动只加 `modifier =` 与请求器；`SshSubmitChainTest` 的三语 parity 断言（`values` / `values-zh-rCN` / `values-zh-rTW`）本次仍 PASS |
| **未降低既有断言强度** | ✅ | `SshSubmitChainTest` 等 27 个既有类**全绿**，一个断言都没动 |

> **换句话说**：修法没有碰"能不能提交"，只碰"能不能**够到**那个框"。用户仍然必须**亲手把口令填进去**才能提交 —— 修完之后，`PasswordRequired` 依然会在口令为空时出现，只是这次**那个框被拉到了眼前**。

---

## 3. Diff

> 注：`SshDialog.kt` 在当前工作区为 **untracked（`??`）**，故 `git diff` 无输出；以下为改动前后的**逐处对照**（原文摘录，注释略）。

### 3.1 `ui/servers/SshDialog.kt` —— 新增（`:167-187`）

```kotlin
    val targetRequester = remember { BringIntoViewRequester() }
    val nameRequester = remember { BringIntoViewRequester() }
    val passwordRequester = remember { BringIntoViewRequester() }
    val privateKeyRequester = remember { BringIntoViewRequester() }
    val passphraseRequester = remember { BringIntoViewRequester() }
    val fieldRequesters: Map<SshFormField, BringIntoViewRequester> = mapOf(
        SshFormField.Target to targetRequester,
        SshFormField.Name to nameRequester,
        SshFormField.Password to passwordRequester,
        SshFormField.PrivateKeyPath to privateKeyRequester,
        SshFormField.Passphrase to passphraseRequester,
    )

    /** The one field a refusal points at — see [sshFormErrorField]. */
    suspend fun revealRefusedField(refusal: SshFormError) {
        withFrameNanos { }
        runCatching { fieldRequesters[sshFormErrorField(refusal)]?.bringIntoView() }
    }
```

### 3.2 `ui/servers/SshDialog.kt` —— 追加到 v90 的 effect（`:222`）

```kotlin
    LaunchedEffect(submitAttempts, feedback) {
        if (feedback.onScreen) {
            withFrameNanos { }
            feedbackRequester.bringIntoView()          // ← v90 原样，未动
            // v98 D3: 同一个 attempt 再揭示**被拒的那个字段**
            val refusal = error
            if (refusal != null) revealRefusedField(refusal)
        }
    }
```

### 3.3 `ui/servers/SshDialog.kt` —— 5 个字段各挂一个句柄

```kotlin
// Host    (:439)   modifier = Modifier.bringIntoViewRequester(targetRequester),
// Name    (:448)   modifier = Modifier.bringIntoViewRequester(nameRequester),
// Password(:485)   modifier = Modifier.bringIntoViewRequester(passwordRequester),   ← v98 真正修的那个
// KeyPath (:500)   modifier = Modifier.bringIntoViewRequester(privateKeyRequester),
// Passphr (:513)   modifier = Modifier.bringIntoViewRequester(passphraseRequester),
```

**未改动**：`band`/`lift`/`height(band)`/`heightIn(max = band)`/`weight(1f, fill = false)`/`verticalScroll`/`feedbackRequester`/`Cancel`/`Add server` 的启用条件/任何字符串。

### 3.4 新增 `ui/servers/SshFormReach.kt`（全文件 176 行，纯函数 + 枚举，无 Compose 依赖）

### 3.5 新增 `app/src/test/java/.../SshFormReachabilityV98Test.kt`（11 个用例）

---

## 4. 新增 JVM pin（`SshFormReachabilityV98Test`，11 例）

分三组，与任务书的两条要求一一对应：

### ① 表单宿主必须有滚动能力，且每个必填字段都被纳入滚动/聚焦请求

| 用例 | 钉住什么 |
|---|---|
| `theFormHostIsScrollableAndTheFieldsAreInsideThatScroller` | 卡内 `verticalScroll` 恰好 1 个；**每个字段的句柄都组合在该 scroller 内部**（句柄挂在 scroller 外 ⇒ 请求无人应答） |
| `everyFieldEveryBranchDrawsHasItsOwnRevealHandle` | `remember { BringIntoViewRequester() }` 计数 == 字段数 **+1**（+1 是 v90 的反馈组）；每个字段的句柄**恰好绑定一次**且在 map 里有对应键（绑错字段会让拒绝揭示到错误的输入框） |
| `thePasswordFieldIsTheOneTheFixNamed` | 口令框句柄组合在 `SshAuthMethod.PASSWORD -> {` 分支内、且是该分支**最深的字段**（正是它落在裁切线外） |
| `aRefusalRevealsTheFieldItPointsAt` | v90 的 `feedbackRequester.bringIntoView()` **仍在**；同一个 attempt key 上追加 `revealRefusedField(refusal)`；揭示是 `bringIntoView()`（走完整响应者链）而非 scroll state 写入；`withFrameNanos` 在 `bringIntoView()` 之前 |
| `theRevealCannotThrowOutOfTheEffect` | 揭示被 `runCatching` 包住（未附着的节点不会把异常抛出 effect）；`rememberScrollState()` 全文仍恰好 1 处 ⇒ v90 几何与 band 算术未被扰动 |

### ② 校验谓词没有被放宽

| 用例 | 钉住什么 |
|---|---|
| `theGateStillDemandsThePassword` | 空口令 `null` / `""` ⇒ `PasswordRequired`；`target` 空白 ⇒ `TargetRequired`；`KEY` + 空 key path ⇒ `KeyRequired`；**完整凭据仍通过**（`null`）。另按真实语义 pin：`"   "` 对本 gate **不算空**（`:107` 是 `isNullOrEmpty()`，不 trim）—— **不擅自要求更严** |
| `thePrimaryActionKeepsItsGate` | `enabled = !busy && target.isNotBlank()` 原样；`gate < refusal < handOver`；拒绝块仍以 `error = invalid` 起并含 `return`；**`submit()` 内不含 `bringIntoView`**（可达性改动在呈现层，提交链纯净） |
| `everyRefusalNamesAFieldAndItIsOneTheFormDraws` | 3 个 `SshFormError` **每个**都指名一个字段；该字段是**对应分支画得出来的**（gate 不会指向用户看不见的字段），且**确实被 gate 要求** |
| `drawnAndRequiredAreTwoDistinctSets` | 两个分支下 `required ⊆ drawn`；`Target` 恒 required；`Name` 恒 drawn **但从不 required**；`Passphrase` 恒不 required；各分支自己的字段恒 required |
| `allFieldsReachableIsFalseForAMissingHandleAndTrueForAFullSet` | 句柄齐全 ⇒ true；**抽掉任意一个绘制字段的句柄 ⇒ false**（含最深的那个 = v98 缺陷本身）；空句柄集 ⇒ false（静默成功正是缺陷复发的路径） |
| `theUnionOfBothBranchesCoversTheWholeEnum` | 两分支的 `drawn` 并集 == 整个枚举（没有字段被词汇表遗忘） |

**pin 自身的一个诚实修正**：首跑时我在 `theGateStillDemandsThePassword` 里断言了 `"   "` ⇒ `PasswordRequired`。**这是我 pin 写错了，不是代码回归** —— `SshModel.kt:107` 的判定是 `isNullOrEmpty()`，空白口令对它不算空。已按**真实语义**改为断言 `null`，并在注释里写明"pin 的是谓词没有移动，不是要求更严的谓词"。这符合"不许降低既有断言强度"：我没有动任何既有断言，修正的是我自己新写的那条。

---

## 5. 测试证据

### 5.1 命令

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
# 抢锁：等 .agent-gradle-lock 消失 → 写锁 → 跑完删
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.SshFormReachabilityV98Test" `
  --tests "com.xiufeigo.opencodeshell.SshSubmitChainTest" `
  ... (共 28 个类)
```

### 5.2 闸门结果原文

```
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 5s
52 actionable tasks: 2 executed, 50 up-to-date
GRADLE_EXIT=0
```

### 5.3 用例数（从 `app/build/test-results/testDebugUnitTest/*.xml` 逐类汇总）

```
CLASSES=28
TOTAL_TESTS=270  FAILURES=0  SKIPPED=0
```

| 类 | 用例 | 类 | 用例 |
|---|---|---|---|
| **SshFormReachabilityV98Test（新）** | **11** | SshPassphraseAnswerV94Test | 8 |
| SshSubmitChainTest | 10 | SshPromptCancelDoesNotDismissF13bTest | 9 |
| SshDialogRenderTest | 5 | SshPromptOfferV94Test | 14 |
| SshImeNextRoutingF13aTest | 7 | SshReconnectFidelityV97Test | 20 |
| SshTunnelWiringTest | 7 | SshSeedForgetV92Test | 14 |
| SshTunnelDeleteV91Test | 9 | SshModelTest | 6 |
| SshDeletePathV97Test | 12 | SshPanelTunnelTest | 7 |
| SshTunnelHostModelTest | 4 | SshTunnelNarrowRowV93Test | 12 |
| SshTunnelNarrowStructureV93Test | 8 | SshAuthenticateModelTest | 8 |
| SshConnectionModelTest | 8 | SshKeyFileFailureF14Test | 7 |
| SshEd25519IdentityF14Test | 4 | ServersFormImeBandV83Test | 7 |
| ServersFormImeBandV85Test | 7 | ServersFormImeBandV87Test | 14 |
| ServersFormImeFloorV88Test | 9 | ServersFormImeFloorDownV89Test | 9 |
| SettingsModalImeBandV92Test | 9 | Wave23aSettingsPaletteTerminalTest | 25 |

**0 失败 / 0 跳过**，含以下既有 PASS 未被破坏：
- **编辑项目/服务器列表**：`SshPanelTunnelTest`、`SshTunnelNarrowRow/StructureV93Test`、`SshTunnelHostModelTest`、`SshPanelTunnelTest`
- **连接**：`SshTunnelWiringTest`、`SshConnectionModelTest`、`SshKeyFileFailureF14Test`、`SshEd25519IdentityF14Test`
- **删除确认条（`bringIntoViewRequester`）**：`SshTunnelDeleteV91Test`、`SshDeletePathV97Test`、`SshSeedForgetV92Test`（隧道遗忘）
- **表单几何（band/floor/lift）**：`ServersFormImeBandV83/V85/V87Test`、`ServersFormImeFloorV88Test`、`ServersFormImeFloorDownV89Test`、`SettingsModalImeBandV92Test`
- **提交链与 IME 路由**：`SshSubmitChainTest`、`SshImeNextRoutingF13aTest`、`SshDialogRenderTest`
- **三语 parity**：`SshSubmitChainTest.theFeedbackUsesTheFormsOwnStringsInEveryLocale`（`values` / `values-zh-rCN` / `values-zh-rTW`）随该类 PASS

### 5.4 锁纪律

```
LOCK_ACQUIRED after 0s     （等 .agent-gradle-lock 消失 → 写锁）
… 两次前台 gradle 跑完 …
LOCK_RELEASED              remaining=0
```

---

## 6. 改动文件清单

| 文件 | 性质 | 说明 |
|---|---|---|
| `app/src/main/java/…/ui/servers/SshDialog.kt` | 改 | 5 个字段各挂一个 `bringIntoViewRequester`；新增 `fieldRequesters` map + `revealRefusedField()`；v90 effect 内追加一次揭示 |
| `app/src/main/java/…/ui/servers/SshFormReach.kt` | **新增** | 可达性词汇表 + 4 个纯函数（`sshFormErrorField` / `sshFormRequiredFields` / `sshFormDrawnFields` / `sshFormFieldsAllReachable`） |
| `app/src/test/java/…/SshFormReachabilityV98Test.kt` | **新增** | 11 个 JVM pin（源码 pin + 校验未放宽） |
| `research/v97_fix/SSH_FORM_REACHABILITY_FIX.md` | **新增** | 本报告 |

> **⚠️ 关于工作区其他改动**：`git status` 显示 `ui/servers/` 下另有 `ConnectServerScreen.kt` / `ServerConnectModel.kt` / `ServerHealth.kt` 被修改、以及 `SshModel.kt` / `SshTunnelHost.kt` / `SshAuthenticate.kt` 等为 untracked。**这些不是本轮改动**（本轮只写了上表 4 个文件），是同工作区其他 agent 的既有产物。**本轮未回退、未整理它们。**

---

## 7. 真机复验步骤（1080x2424，**待执行**）

> 前置：装机 `assembleDebug` 并安装到 `emulator-5554`（**超出本轮授权**，由复验员执行）。

```
0. 纪律：不按 BACK(4)、弹层内不按 ESC(111)；截图 screencap -p + adb pull（不用 exec-out）；
   坐标全部运行期从 uiautomator dump 解析（XML DOM GetAttribute）；只动自建条目 E2ECHK5554v98*；
   基线记录 shared_prefs 签名（GT = 非 __androidx_security_crypto_* 的 <string> 条目数 + 每值长度签名）。

1. 打开 Servers 面板 → Add SSH server。

2. ★ 核心断言 A —— 「口令框必须在 a11y 树里」：
   填完 host（哨兵 E2ECHK5554v98-HOST），**不滚动**，直接点 Add server。
   预期：PasswordRequired 红字出现，**且口令框随之被拉进视口**：
     - dump 中 role2（按 y 升序第 3 个 EditText）**必须出现**，且 h == 126（满高）
     - 其 bounds 必须完全落在内层 scroller 节点 [43,..][1038,..] 之内
   （修前这一步 11 个滚位全部失败：EditText 恒为 2）

3. 断言 B —— 「能填」：
   解析出的口令框中心点 tap → input text "E2ECHK5554v98-PW"
   哨兵自证：再次 dump 读回该 EditText 文本 == E2ECHK5554v98-PW（逐字符相等）

4. 断言 C —— 「Add server 可用且校验未放宽」：
   - anc_en == true（target 非空）
   - 追加反向用例：清空口令后点 Add server → **必须仍然拒绝**并报
     Enter the password for this connection. 且口令框再次被拉回视口
     （这一条证明安全边界没被"绕过"）

5. 断言 D —— 提交成功：填 host+name+password → 点 Add server
   预期：表单关闭、落库 ground truth 签名变化（ssh_json 变长）、UI 出现 1 条 E2ECHK5554v98 行
     （位于 SSH tunnels 分组，带 Reconnect/Disconnect）

6. 断言 E —— 落库 +1（终态核对）：
   run-as com.xiufeigo.opencodeshell cat shared_prefs/oc_server_config.xml
   前后对比：GT <string> 条目数与签名 —— 条目 +1（不是 0）
   对照 REPORT_COVERAGE5.md 的 GT_KEY_DELTA=0 基线

7. 断言 F —— 编辑复用路径：用同一表单编辑刚建的条目，同样 3 步可达。
```

---

## 8. 未闭环

1. 🚨 **本修复未经真机复验**（最高优先）。JVM pin 只能证明"句柄接上了、校验没动"，**不能证明**"设备上口令框真的进了 a11y 树"。§7 是待执行步骤，不是已完成记录。
2. ❓ **`imePadding` 与键盘遮挡的关系**未上机验证：本修法用 `bringIntoViewRequester` 走完整响应者链（与删除确认条同款），但**键盘弹出时聚焦框是否绝对不被遮挡**，需在真机上读 `mInputShown` + 字段 bounds 相对键盘顶端的位置。**未验证。**
3. ❓ **嵌套 scroller 下的请求分配**：请求会同时经过内层与外层 scroller，实际滚动落点（滚内层还是外层、是否两者都动）**未上机确认**。
4. ❌ **`name` 框与 `passphrase` 框的深度裁切**未在真机上复核（它们不是必填，本次只挂句柄、未加主动揭示）。
5. ❌ **承第五轮未决项，本轮均未处理**（超出本域授权）：D3-3 第 3/4 行（需先建成 ≥4 行）、D3-4 三态（需铺私钥到 `READY`）、面板 body 行容量 N、`removePersisted` 的 loopback 级联边界。
6. ⚠️ **计数口径**仍未由 Lead 确认：`ssh_json` 单 key 存全量 JSON（`ServerConfigStore.kt:302`），"落库条目数"只能靠长度签名间接判断。
7. ⚠️ **工作区并存改动**：`ui/servers/` 下其他文件的修改/新增（见 §6）非本轮产物，**归属未厘清**，建议 Lead 单独收口。

---

## 9. 一句话回报

**状态**：JVM 闸门全绿（28 类 / 270 例 / 0 失败），**待真机复验**。**根因**：表单唯一的 `bringIntoViewRequester` 只绑在反馈组上，5 个字段全无句柄，被拒时滚回来的是红字而不是要填的口令框，而口令框作为 `PASSWORD` 分支最深的字段恒在双层 scroller 裁切线外并被剔出 a11y 树 ⇒ `validateSshForm` 恒拒、`onSubmit` 永不执行。**改了什么**：`ui/servers/SshDialog.kt`（5 个字段各挂句柄 + `fieldRequesters` map + `revealRefusedField()`，追加进 v90 既有 effect）、新增 `ui/servers/SshFormReach.kt`（可达性词汇表 + 4 纯函数）、新增 `SshFormReachabilityV98Test.kt`（11 pin）。**命令与用例数**：`:app:testDebugUnitTest --tests "…"` × 28 类 → `BUILD SUCCESSFUL in 5s`，270 例 0 失败 0 跳过。**报告路径**：`research/v97_fix/SSH_FORM_REACHABILITY_FIX.md`。
