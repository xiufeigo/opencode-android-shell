# TRIPWIRE_HARDENING.md —— v115 收尾两件

> 任务：① 防 R5/R6「旁侧生产断言被删 ⇒ 静默退化」；② 按「以 opencode 官方为准」
> 给行菜单 parity 落地**上游锚定 pin**。
> 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
> 写权限：仅测试文件 + `research/v97_fix/**`。

---

## 0. 先纠一处 Lead 的定位错误（重要）

Lead 任务书里说「读 v114 改过的两个类（`ComposerPositionDecompositionV107Test.kt`、
`ComposerBandTouchTargetV105Test.kt`）里 R5/R6 的构造」。

**实测不是。** v114（`TRIPWIRE_CRITERION_FIX3.md` §5.1/§5.2）与源码一致地显示：

| 记录 | 真实位置 | 所在方法 | 同方法里的生产调用（侦测力来源） |
|---|---|---|---|
| **R5** | `ComposerBandTallEnoughV106Test.kt:338-341`、`:343` | `theFivePixelOverflowIsReproducedOnTheDeviceReadingsAndGoneOnTheNewBand()` (`:322`) | `Frame(600, 387, bandHeightPx = BAND_AFTER_PX)` (`:347`) → `SessionPaneLayout`；同类 pin `SessionPaneLayout.viewportHeightPx` |
| **R6** | `ComposerUnionBottomP5V109Test.kt:151-154`、及 600 侧那条 | `theBottomPaddingAbsorbsTheOverrunOnSevenSixEightButNotOnSixHundred()` (`:183`) | `SessionPaneLayout.composerTopPx(paneHeight768, 94)` (`:197`) |

Lead 点名的 V107 / V105 是 v114 改的 **R8**（V107）与 `V105:305`，
**不是** R5/R6 的宿主。v114 §9#4 自己也建议「下轮在 **V106/V109** 各补一条跨类活判据」，
与实测一致。⇒ 本轮 pin 按**真实位置（V106 / V109）**落地，Lead 的两个类名不采用。

---

## 1. 任务 A：现状分析

### 1.1 R5 —— 记录性 fixture 在哪、靠什么"绑"着

`app/src/test/java/com/xiufeigo/opencodeshell/ComposerBandTallEnoughV106Test.kt`

| 角色 | 位置 | 原文 |
|---|---|---|
| **宿主方法** | `:322-323` | `@Test` / `fun theFivePixelOverflowIsReproducedOnTheDeviceReadingsAndGoneOnTheNewBand()` |
| **记录性 fixture 断言** | `:338-341` | `assertEquals("真机 600 档 ①：unionBottom 218 > hostBottom(imeTop) 213 ⇒ +5", 5, 218 - 213,)` |
| **记录性 fixture 断言** | `:343` | `assertEquals("真机 768 档 ①：296 > 294 ⇒ +2", 2, 296 - 294)` |
| **旁侧生产断言** | `:347` → `:348-358` | `val after600 = Frame(600, 387, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)`，随后 `assertEquals("600+IME pane", 51, after600.pane)` 等 |
| v114 裁定注释 | `:331-337` | 「单独看零侦测力；但本方法的「修后」半段就调生产…」 |

**绑定的机制 = 同一个方法体内相邻**。`:338-341` 的记录断言与 `:347` 起的生产断言
之间**没有任何语法或结构性约束**，只有 `fun … {` 的一对花括号。

### 1.2 一个必须说清的技术细节：`Frame(` 不等于生产

R5 的生产腿**不是**直接调 `SessionPaneLayout`，而是调本文件的私有类 `Frame`：

- `V106:122` `private class Frame(`
- `V106:141` `val hostPx: Int = SessionPaneLayout.composerHostHeightPx(`
- `V106:146` `val hostTop: Int = SessionPaneLayout.composerTopPx(pane = pane, composer = hostPx)`
- `V106:153` `val touchTarget: Dp = SessionPaneLayout.composerBandTouchTargetDp(`

而 `:348-358` 的断言里出现的是 `after600.pane` / `after600.hostPx` —— **局部量名，不是 `Frame(`**。

⇒ **纯文本校验器若只认 `SessionPaneLayout`，会把 R5 整个方法判成"零生产断言"而在健康源码上误报。**
这直接决定了校验器必须做一步**局部量数据流绑定**（§2.2），并额外加一条
`frameIsAFacadeOverTheProductionLayout` 把 `Frame → SessionPaneLayout` 这座桥本身钉住
（否则有人把 `Frame` 改成纯字面量计算，R5 的 pin 会在一个虚构上继续绿）。

### 1.3 R6 —— 记录性 fixture 在哪

`app/src/test/java/com/xiufeigo/opencodeshell/ComposerUnionBottomP5V109Test.kt`

| 角色 | 位置 | 原文 |
|---|---|---|
| **宿主方法** | `:183-184` | `@Test` / `fun theBottomPaddingAbsorbsTheOverrunOnSevenSixEightButNotOnSixHundred()` |
| **记录性 fixture 断言** | `:190-193` | `assertTrue("v98 re-enactment: 6 < 8, so the bottom padding alone yields it and the top padding is untouched", over768 < regionBottomPaddingPx,)` |
| **记录性 fixture 断言** | `:211-214` | `assertTrue("v98 re-enactment: 12 > 8, the bottom padding runs out, so the top padding is what gets consumed", over600 > regionBottomPaddingPx,)` |
| **旁侧生产断言** | `:197` → `:198-199` | `val hostTop768 = SessionPaneLayout.composerTopPx(paneHeight768, 94)` / `assertEquals("768 host top is pane-relative 46", 46, hostTop768)` |
| v114 裁定注释 | `:173-181` | 「下面那两条 …**保留**… 但 Lead 的前提是「fixture 旁边必须有一条调生产函数的断言」」 |

R6 的生产腿是**直接调生产**（`SessionPaneLayout.composerTopPx`），不需要局部量绑定那一步。
但注意：v114 裁定注释里那句 `SessionPaneLayout.composerTopPx(paneHeight768, 94)`
在 **`:173-181`，位于 `fun` 签名之上、方法体之外**。R5 的同类注释则在方法体**之内**（`:331-337`）。
这个差别会影响违规文案的"锐度"（§2.4）。

---

## 2. 任务 A：校验器设计

文件：`app/src/test/java/com/xiufeigo/opencodeshell/ComposerTripwireIntegrityV115Test.kt`
（纯函数体 `internal object TripwireValidator`，输入＝源码文本，输出＝`List<Violation>`）

### 2.1 为什么要"掩码"这一步（load-bearing）

`maskNonCode(src)`：把**所有注释与字符串字面量的内容**替换成空格，**保持长度与换行不变**。
理由：注释里出现生产函数名**不构成活判据**。R5 的 KDoc `:332` 原文就写着
`Frame(600, 387, …)` —— 如果按裸文本 grep，删掉真断言之后这条注释会替它顶岗，
校验器就会对已退化的源码报"通过"。掩码让"删了断言"与"只删了上面那行注释"成为**可判定**的两件事。

### 2.2 断言分类：两步（直接 + 局部量绑定）

1. `boundProductionLocals(maskedBody, symbols)` —— 扫 `val NAME = …`，
   取**语句**右值（含跨行，跟踪 `()[]{}` 深度到顶格换行为止），
   若右值含 `spec.productionSymbols` 任一项，则 `NAME` 记为"生产绑定局部量"。
2. `isLiveAssertion(call, symbols, bound)` —— 断言调用文本里
   **直接含**生产符号，**或**引用了任一生产绑定局部量。

于是 `:348` 的 `assertEquals(…, after600.pane)` 经 `after600` 判为**活断言**，
`:340` 的 `assertEquals(…, 5, 218 - 213)` 两个操作数都是字面量、无绑定局部量 ⇒ **记录性断言**。

### 2.3 四类违规（逐条对应任务书的三项要求）

| kind | 触发条件 | 对应任务书要求 |
|---|---|---|
| `fixture-method-missing` | `fun <fixtureMethod>(` 在**代码**里找不到 | 「关键 fixture 的**方法名**仍在」 |
| `fixture-method-disabled` | 方法前一行窗口内出现 `@Ignore` / `@Disabled` | 「这些生产断言**没有被 `@Ignore`**」 |
| `live-assertion-only-in-comments` | 有记录性断言、代码内 0 活断言，但**注释里**还有生产符号 | 「**没有被…注释掉**」＋「必须同时存在」 |
| `no-live-assertion` | 有记录性断言、代码内 0 活断言、注释里也没有 | 「必须同时存在」 |
| `lineage-marker-lost` | `lineageMarkers` 任一片段在源码中消失 | 「**注释沿革**仍在」 |

### 2.4 两种退化文案的由来（如实记账）

- **R5** 命中 `live-assertion-only-in-comments`：v114 裁定注释在方法体**内**，
  删掉生产腿后 KDoc 仍在"宣传"一个已不存在的调用 —— 这是**更锐利**的诊断。
- **R6** 命中 `no-live-assertion`：R6 的那条宣传性注释在方法体**外**，
  掩码后方法体内既无活断言也无宣传文字 ⇒ 只能报较朴素的退化。

⇒ 因此测试断言的是**退化类别集合** `degradationKinds = {live-assertion-only-in-comments, no-live-assertion}`，
再**分别**钉住 R5 今日落在锐利档、R6 今日落在朴素档。这样"注释被顺手编辑"不会误伤这条 pin，
而"活断言被删"仍然一定被拒。

### 2.5 `lineageMarkers`（防后人顺手删记录）

| spec | 钉住的片段 |
|---|---|
| R5 | `v114 裁定：**保留**（记账轨）` / `真机 600 档 ①：unionBottom 218 > hostBottom(imeTop) 213 ⇒ +5` / `真机 768 档 ①：296 > 294 ⇒ +2` / `REPORT_COMPOSER_OVERFLOW3.md` |
| R6 | `v114 裁定（R6）` / `v98 re-enactment: 6 < 8, so the bottom padding alone yields it and the top padding is untouched` / `v98 re-enactment: 12 > 8, the bottom padding runs out, so the top padding is what gets consumed` |

---

## 3. 任务 A：反向验证输出原文（**本轮核心证据**）

反向验证做了**两层**，两层都实跑、都有原文。

### 3.1 第一层：校验器单元测试（变异源码字符串喂进纯函数）

`ComposerTripwireIntegrityV115Test` 共 9 个用例，其中 6 个是变异用例：

| 变异 | 期望 kind | 实测 |
|---|---|---|
| 删掉 R5 旁侧生产断言 | `live-assertion-only-in-comments` | ✅ 命中 |
| 删掉 R6 旁侧生产断言 | `no-live-assertion` | ✅ 命中 |
| 改掉 fixture 方法名 | `fixture-method-missing` | ✅ 命中 |
| 给 fixture 方法加 `@Ignore` | `fixture-method-disabled` | ✅ 命中 |
| 删掉记录读数本身 | `lineage-marker-lost` | ✅ 命中 |
| 把生产断言**注释掉** | `live-assertion-only-in-comments` | ✅ 命中 |
| 正常源码（正向） | `[]` | ✅ 通过 |

变异方式 `dropLiveAssertions()`：**只删方法体内含生产符号的代码行，注释行保留**
（在掩码后的行上做过滤）—— 这正是"顺手清理生产腿、留下记录腿"的真实手笔。

### 3.2 第二层：真实文件级注入（本轮实跑，原文如下）

把 `ComposerUnionBottomP5V109Test.kt` 的 `:201-208`（即
`val hostTop768 = SessionPaneLayout.composerTopPx(paneHeight768, 94)` 及其下游 3 条断言）
整段注释掉，**然后只跑校验器所在的 suite**：

命令：

```
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ComposerTripwireIntegrityV115Test"
```

结果原文（`app/build/test-results/testDebugUnitTest/TEST-com.xiufeigo.opencodeshell.ComposerTripwireIntegrityV115Test.xml`）：

```
ComposerTripwireIntegrityV115Test > theRealTripwiresAreClean FAILED
ComposerTripwireIntegrityV115Test > deletingTheLiveAssertionsBesideR6IsReported FAILED
ComposerTripwireIntegrityV115Test > theRealTripwiresMixRecordOnlyAndLiveAssertions FAILED
9 tests completed, 3 failed
BUILD FAILED
EXIT=1
```

**决定性那一行原文**（`theRealTripwiresAreClean` 的 AssertionError message，逐字）：

> `R6 must carry a live assertion beside its record-only ones — violations: [R6/live-assertion-only-in-comments: theBottomPaddingAbsorbsTheOverrunOnSevenSixEightButNotOnSixHundred() still has 7 record-only assertion(s) that can never go red, and SessionPaneLayout. survives only in its comments — the live assertion that used to lend it detection power has been deleted or commented out]`

**抄一行最短的**：

> `R6/live-assertion-only-in-comments: theBottomPaddingAbsorbsTheOverrunOnSevenSixEightButNotOnSixHundred() still has 7 record-only assertion(s) that can never go red, and SessionPaneLayout. survives only in its comments`

另外两条红灯**如实记账**（不是漏网，是注入的连带效应）：

- `theRealTripwiresMixRecordOnlyAndLiveAssertions` → `R6 has live assertions doing the protecting`
  （守恒性对照用例：注入后确实 0 条活断言，**证明该用例不是恒真**。）
- `deletingTheLiveAssertionsBesideR6IsReported` → `the mutation must actually delete something`
  （生产行已被注释掉，变异器无行可删 —— 该用例的"变异必须真的删掉东西"守卫按设计触发。）

### 3.3 还原与终验

`ComposerUnionBottomP5V109Test.kt` 已从备份**逐字还原**，并校验：

```
L201=        val hostTop768 = SessionPaneLayout.composerTopPx(paneHeight768, 94)
injection_left=0
```

---

## 4. 任务 A：注释 diff

**只加注释，零断言语义改动**（两个文件都在允许写的"测试文件"范围内）。

### `ComposerBandTallEnoughV106Test.kt`（紧随 `:337` 之后）

```diff
         // `ComposerPositionDecompositionV107Test` / `ComposerUnionBottomP5V109Test`，
         // 这里的 5 与 2 **只作记录，不再被当判据引用**。
+        // v115：下面那几条「修后」断言（`Frame(...)` → `SessionPaneLayout`）**不可删** ——
+        // 它们是 R5 唯一的侦测力来源，删掉会让上面这些记录断言静默退化成纯装饰；
+        // 已有结构 pin（`ComposerTripwireIntegrityV115Test`，含反向验证）守住这条不变量，
+        // 推导见 `research/v97_fix/TRIPWIRE_HARDENING.md` §1-§3。
```

### `ComposerUnionBottomP5V109Test.kt`（紧随 `:181` 之后）

```diff
     // 标成 `v98 re-enactment:`，防止再被当成开放项。**一个数没动、没删、没放宽。**
+    // v115：本方法里的 `SessionPaneLayout.composerTopPx(paneHeight768, 94)`（及其下游断言）
+    // **不可删** —— 它是 R6 那两条记录断言唯一的侦测力来源，删掉它们会静默退化成纯装饰；
+    // 已有结构 pin（`ComposerTripwireIntegrityV115Test`，含反向验证）守住这条不变量，
+    // 推导见 `research/v97_fix/TRIPWIRE_HARDENING.md` §1-§3。
```

**没有新增任何用户可见字符串**（这两个是测试源文件，无 `R.string`）。

---

## 5. 任务 B：上游证据行（我独立复核，**确认 Lead 全部行号与原文**）

工作目录：`C:\sensorsdata\main\program\opencode-android-shell\_upstream`

### 5.1 `packages/app/src/servers/ssh/menu.tsx`

| Lead 说法 | 实测行号 | 原文 |
|---|---|---|
| `:29` 分组标签 | **✅ `:29`** | `<Menu.GroupLabel>{language.t("ssh.server.menu.label")}</Menu.GroupLabel>` |
| `:31` Connect（`disabled={ssh.pending(props.id)}`） | **✅ `:31`** | `<Menu.Item disabled={ssh.pending(props.id)} onSelect={() => ssh.connect(item().config)}>` |
| `:35` / `:40` `Set as default` / `Remove default` 由 `available()` 与 `key()` **双向**门控 | **✅ `:35` / `:40`** | `:35` `<Show when={props.domain.defaults.available() && props.domain.defaults.key() !== key()}>`<br>`:40` `<Show when={props.domain.defaults.available() && props.domain.defaults.key() === key()}>` |
| `:45` 分隔线 | **✅ `:45`** | `<Menu.Separator />` |
| `:46` 删除项 | **✅ `:46`** | `<Menu.Item onSelect={() => void props.domain.connection.remove(key())}>` |

`:35` 与 `:40` 互斥（`!==` vs `===`）—— Lead 的"双向门控"表述准确。

### 5.2 决定性一行 `packages/app/src/servers/registry/controller.ts:45`

```ts
    available: createMemo(() => !!platform.getDefaultServer && !!platform.setDefaultServer),
```

**逐字命中**。是 `&&` 合取（不是 `||`），与 Lead 描述一致。

### 5.3 补一条 Lead 没提、但让论证闭合的证据：两个 hook 在接口上**是可选的**

`packages/app/src/runtime/platform/platform.tsx`

| 行号 | 原文 |
|---|---|
| `:83` | `getDefaultServer?(): Promise<ServerConnection.Key \| null>` |
| `:86` | `setDefaultServer?(url: ServerConnection.Key \| null): Promise<void> \| void` |

**问号在接口上**。若它们是必选成员，任何平台都被迫实现，`available()` 就不可能为 `false`，
整条论证会塌。

### 5.4 全仓扫描：实现者只有 web 与 desktop，**无 native/mobile**

对 `_upstream/packages/**` 全量扫描 `getDefaultServer:` 的定义点，命中仅两处：

| 行号 | 原文（截断） |
|---|---|
| `packages/app/src/runtime/platform/web.ts:41` | `getDefaultServer: async () => {` |
| `packages/app/src/runtime/platform/web.ts:45` | `setDefaultServer: writeDefaultServerUrl,` |
| `packages/desktop/src/renderer/platform/index.ts:68` | `getDefaultServer: async () => {` |
| `packages/desktop/src/renderer/platform/index.ts:73` | `setDefaultServer: async (url) => {` |

（其余 `getDefaultServer` 出现处均为**消费方**：`controller.ts:25,36,85`、
`desktop-app.tsx:65`、IPC 层 `server-settings.ts` / `app.ts:36-37` / `api.ts:98-99`，
以及接口声明 `platform.tsx:83`。）

---

## 6. 任务 B：pin 设计 + 对 Lead 判定的确认/纠正

### 6.1 结论：**确认**，零纠正

> **上游语义：平台没有这两个 hook 时 `available() === false`，那两项不渲染
> ⇒ Android 壳"不渲染"就是官方行为，B 级不该做。**

我逐行复核后**完全同意**，并补强两点：
1. Lead 未提的 `platform.tsx:83,86` 可选声明（§5.3）—— 这是论证的**类型学前提**，已补进 pin。
2. Lead 未做的全仓扫描（§5.4）—— 已补进 pin（`onlyWebAndDesktopImplementTheDefaultServerHooks`），
   断言实现者集合**恰好**是 `{app/src/runtime/platform, desktop/src/renderer/platform}`。
   这样"将来新增一个 native 适配器并顺手实现了这两个 hook"会**主动变红**，
   而那正是应该重新打开这条 parity 讨论的时刻 —— 也就是这个 pin 存在的意义。

### 6.2 上游侧 pin（`SshRowMenuUpstreamParityV115Test`）

**读文件机制照抄现有类**（不另造）：路径解析沿用
`ProjectParityV97Test.kt:400-404` 的多候选式

```kotlin
listOf(File("_upstream/$relative"), File("../_upstream/$relative"), File("app/../_upstream/$relative"))
```

（实测单测工作目录为 `C:\sensorsdata\main\program\opencode-android-shell\app\.`，
故第二个候选命中；壳侧源码沿用 `SshRowMenuParityV99Test.kt:48-52` 的 `app/` + 相对双候选。）

| 用例 | 钉住什么 |
|---|---|
| `theDefaultsAvailabilityGateRequiresBothPlatformHooks` | 从 `controller.ts` **按形状**（`contains("available:")`）而非按行号定位定义行，断言其含 `platform.getDefaultServer`、`platform.setDefaultServer`，且是 `&&` 合取。按形状定位 ⇒ 上游挪行不误伤，真删 hook 必红。 |
| `theTwoHooksAreOptionalOnThePlatformInterface` | `platform.tsx` 里 `getDefaultServer?(` / `setDefaultServer?(` 存在且为 `Promise<…>` |
| `onlyWebAndDesktopImplementTheDefaultServerHooks` | 扫 `_upstream/packages/**/platform/**` 中含 `getDefaultServer:` 的文件，断言集合恰为 web + desktop |
| `bothDefaultEntriesAreGatedOnAvailabilityAndAreMutuallyExclusive` | `menu.tsx` 的两条 `<Show when={defaults.available() && …}>` 各恰好 1 处，且 `!==` / `===` 互斥；两个 label key 仍在 |
| `theGroupStructureTheShellRendersIsUpstreamsOwn` | `menu.tsx` 的 GroupLabel / Separator / Connect(pending) / remove 行的**相对次序** `label < separator < remove` |

### 6.3 本壳侧 pin（沿用 v99 口径，不重复造）

| 用例 | 钉住什么 |
|---|---|
| `theShellRendersTheGroupWithoutEitherDefaultEntry` | 对**全量** `SshStage`（经 `SshStage.entries`，不手列）：`sshRowMenuEntries(stage)` 首项为 `GroupLabel`、含 `Separator` 与 `Delete`、**不含** `SetDefault` / `RemoveDefault` |
| `theComposablesTakeTheUpstreamAbsentBranchByOmission` | ① `sshRowMenuEntries(stage)` 调用点存在；② 全壳**不存在** `defaultsAvailable = true`（否则就是在 `available()===false` 的平台上渲染那两项）；③ 两个 slot 仍被建模（保住上游次序的 pin）；④ 合成函数真的画了 `SshRowMenuGroupLabel()` 与 `HorizontalDivider(` |

### 6.4 源码里"为什么不渲染是官方行为"的注释

写在 `SshRowMenuUpstreamParityV115Test` 的类头 KDoc 里（§文件顶部），
引用 `controller.ts:45` + `platform.tsx:83,86` + `web.ts:41,45` / `desktop/src/renderer/platform/index.ts:68,73`。
**测试文件，非用户可见字符串。**

---

## 7. 测试证据

抢锁：`Set-Content .agent-gradle-lock "v115-tripwire-hardening"` → 前台跑完 → 立刻
`rm -- "C:\sensorsdata\main\program\opencode-android-shell\.agent-gradle-lock"`
（**绝对路径字面量**；`LOCK_RELEASED=True`）。
`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`。
**未** assembleDebug / clean / 全量 / 装机。

### 7.1 终验（4 个类，含本轮改过的两个宿主类）

```
.\gradlew.bat :app:testDebugUnitTest \
  --tests "com.xiufeigo.opencodeshell.ComposerTripwireIntegrityV115Test" \
  --tests "com.xiufeigo.opencodeshell.SshRowMenuUpstreamParityV115Test" \
  --tests "com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test" \
  --tests "com.xiufeigo.opencodeshell.ComposerBandTallEnoughV106Test"
```

原文：

```
BUILD SUCCESSFUL in 3s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=0
```

| 类 | tests | failures | errors |
|---|---|---|---|
| `ComposerTripwireIntegrityV115Test` | 9 | 0 | 0 |
| `SshRowMenuUpstreamParityV115Test` | 7 | 0 | 0 |
| `ComposerUnionBottomP5V109Test`（宿主） | 11 | 0 | 0 |
| `ComposerBandTallEnoughV106Test`（宿主） | 7 | 0 | 0 |
| **合计** | **34** | **0** | **0** |

⇒ **断言一条没放宽**：宿主两类的用例数（V109 11、V106 7）与 v114 报告的规模一致，
本轮对它们**只加注释**，故全绿。

### 7.2 中途四次红（如实记账，全部已修）

| 轮次 | 现象 | 根因 | 处置 |
|---|---|---|---|
| 1 | 编译错 `SshRowMenuUpstreamParityV115Test.kt:77,81,88` | `assertTrue(…, x != null)` **不做** smart cast | 改用 `?: error(...)`（返回 `Nothing` 才收窄类型） |
| 2 | 7 个 SshRowMenu 用例全挂在 `:52` | 我的路径候选**漏了 `_upstream/` 前缀**（`File("../$relative")` 而非 `File("../_upstream/$relative")`），三个候选全落空 | 补前缀；实测 cwd = `…\opencode-android-shell\app\.` |
| 3 | 首次跑挂住 235s CPU、4 分钟无输出 | `walkTopDown()` **无剪枝**扫 `_upstream`（含 `node_modules`） | 换成剪枝 BFS（跳过 `node_modules`/`dist`/`.git` 等，深度上限 8） |
| 4 | 剪枝后仍 `found []` | `child.parentFile.path.contains("/platform/")` —— **目录 `path` 不带尾部分隔符，永远不匹配** | 改为 `child.parentFile?.name == "platform"` |

第 3、4 条必须记下来：它们各自单独都会让"上游锚定"退化成一句空话
（扫不到 ⇒ 断言集合恒空 ⇒ `onlyWebAndDesktop...` **恒绿**）。
这是"绿灯不等于有效"的典型，值得下轮写进通用 checklist。

---

## 8. 未闭环（如实记账）

1. **校验器是文本级的，不是编译器级的。** 它靠"生产符号名单"判定什么是活断言。
   若将来有人给 V109 的 fixture 方法加一条**新的**生产调用、同时删掉旧的
   `composerTopPx` 那条，这条 pin 仍会绿 —— 它守的是"**数量 ≥ 1**"，不是"**那一条**还在"。
   要钉死具体某一条，需把它们提升为具名断言；这超出本轮"只加 pin、不改断言语义"的边界。
2. **`Frame` 那座桥是桥，不是恒等。** `frameIsAFacadeOverTheProductionLayout` 只钉了
   `composerHostHeightPx` / `composerTopPx` / `composerBandTouchTargetDp` 三个调用仍在。
   若有人把 `Frame` 的 `pane` / `bandPx` 改成纯字面量，R5 的"活断言"会**名义上**活着、
   实质上不调生产，这条 pin 不会红。
3. **R6 的退化文案比 R5 钝**（`no-live-assertion` vs `live-assertion-only-in-comments`），
   根因是那段宣传性注释在方法签名**之上**（`V109:177`）而 R5 的在方法体**之内**
   （`V106:331-337`）。本轮**没有移动那段注释**（会动到 v114 的记账位置），如实记账如上。
4. **`onlyWebAndDesktopImplementTheDefaultServerHooks` 的扫描深度上限 8 层。**
   实测 858 个目录、命中恰为两个适配器。若上游将来把 `platform/` 放到 8 层更深，
   该用例会误报"实现者为空" —— 属**响亮失败**而非静默放过，可接受。
5. **V109 方法名仍带 `Open`**（`theSixHundredTierIsOpenOnEveryQueueDepthAndPinnedAsSuch`）——
   v114 §9#6 结转，本轮未处理（改名会动 `--tests` 过滤与历史报告引用）。
6. **本轮无真机读数**（环境纪律禁止装机）。任务 A 是纯源码结构、任务 B 是上游文本真值，
   两者都不需要设备。

---

## 9. 回报（给 Lead）

- **状态**：**两件都落地并实跑验证**。任务 A 的结构 pin + 校验器**反向验证变红**
  （真实文件级注入）；任务 B 的上游锚定 pin **确认 Lead 判定、零纠正**。
- **「A 的校验器反向验证是否报违规」**：**报违规**。抄一行原文：
  > `R6/live-assertion-only-in-comments: theBottomPaddingAbsorbsTheOverrunOnSevenSixEightButNotOnSixHundred() still has 7 record-only assertion(s) that can never go red, and SessionPaneLayout. survives only in its comments`
- **「B 上游证据行号 + 是否确认」**：
  `controller.ts:45`（决定性）、`platform.tsx:83,86`（可选声明，Lead 未提、我补的）、
  `menu.tsx:29/31/35/40/45/46`、`web.ts:41,45`、`desktop/src/renderer/platform/index.ts:68,73`。
  **全部逐字复核命中，Lead 的行号与结论零纠正。**
- **Lead 的一处定位错误已纠正**：R5/R6 在 **V106 / V109**，不在任务书点名的 V107 / V105
  （那两个是 v114 改 R8 与 `V105:305` 的地方）。见 §0。
- **改了哪些文件**（全在允许范围内）：
  - 新增 `app/src/test/java/com/xiufeigo/opencodeshell/ComposerTripwireIntegrityV115Test.kt`
  - 新增 `app/src/test/java/com/xiufeigo/opencodeshell/SshRowMenuUpstreamParityV115Test.kt`
  - **只加注释** `ComposerBandTallEnoughV106Test.kt`、`ComposerUnionBottomP5V109Test.kt`
  - 本报告 + 3 个 log + 1 个还原备份（均在 `research/v97_fix/**`）
  - **生产代码零改动**（任务 B 即使发现分歧也只记录、不改生产）
- **命令与用例数**：见 §7 —— **4 类 / 34 tests / 0 failures / 0 errors**，
  `BUILD SUCCESSFUL in 3s`，`EXIT=0`；反向注入轮 `9 tests completed, 3 failed`，
  `BUILD FAILED`，`EXIT=1`。
- **报告路径**：`research/v97_fix/TRIPWIRE_HARDENING.md`
