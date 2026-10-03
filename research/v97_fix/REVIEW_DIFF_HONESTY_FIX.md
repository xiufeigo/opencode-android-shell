# REVIEW_DIFF_HONESTY_FIX — v97 D11（高）Review→Changes 超时后 Refresh 恢复成「假空」

- **缺陷来源**：`research/v97_verify/5556_v98/REPORT_SWEEPC2.md` §3 **D11**（另含 D10 结论）
- **修复范围**：D11（主）+ D10（结论：**非缺陷**，见 §5）
- **回归**：10 个测试类 / **116 用例 / 0 失败 / 0 错误**
- **是否新增用户可见字符串**：**否**（全部复用既有串，见 §4.3）

---

## 1. D11 根因

### 1.1 一句话

`ChatViewModel.reviewVcsDiff` 把「候选 ref 走了一圈」的结论当成了「**最后一个有返回的 ref 的返回**」：陈旧 location 的 `[]` 毫秒级返回被留下，而唯一能证伪它的新鲜图 ref 超时了 —— 那个超时只被记进 `failure`（仅用于 probed 记账），**从未参与最终判定**。于是 `Loaded(emptyList())` → `changesBody` 的 `else -> EMPTY` → `No uncommitted changes yet`。

### 1.2 因果链（含 `文件:行号`）

**① 缺陷行（修复前的原文）** — `ChatViewModel.kt:5364-5369`（`reviewVcsDiff` 循环内）：

```kotlin
                } ?: continue
                diffs = list          // ← 任何候选的列表都无条件覆盖前一个
                if (list.isNotEmpty()) {
                    rememberReviewVcsRef(ref)
                    answered = true
                    break
                }
            }
```

`diffs = list` 对**空列表同样执行**。因此「先到的空」会留在 `diffs` 里，后到的失败只走 `?: continue`，**不覆盖、不作废、不告警**。

**② 超时被丢弃** — `ChatViewModel.kt:5358-5363`：

```kotlin
                } catch (e: Exception) {
                    Log.w(TAG, "vcs diff failed for ${ref ?: directory}: ${e.message}")
                    val kind = ReviewVcsProbePlan.failureOf(reviewVcsFailureCode(e))
                    if (failure == null || kind == ReviewVcsProbePlan.Failure.PERMANENT) failure = kind
                    null
                } ?: continue
```

`failure` 的**唯一**消费者是下面这行 probed 记账（`ChatViewModel.kt:5380-5383`）：

```kotlin
            val settled = diffs != null || answered          // ← 只要拿到过任何列表（含空）就 settled
            if (!settled && ReviewVcsProbePlan.shouldMarkProbed(...)) { markReviewVcsProbed() }
```

**最终判定** — `ChatViewModel.kt:5386-5388`（修复前）：

```kotlin
            val result = diffs
                ?.let(ReviewVcsDiffResult::Loaded)          // ← 空列表在这里被洗成 Loaded
                ?: ReviewVcsDiffResult.Failed
```

`diffs` 非 null（是空列表）→ **`Loaded(emptyList())`**，失败分支永远走不到。

**③ 把「没有数据」渲染成「没有未提交改动」的那一行** — `ReviewModels.kt:623-638`（`ReviewScopes.changesBody`）：

```kotlin
    fun changesBody(
        scope: ReviewChangeScope, noGit: Boolean, loadFailed: Boolean,
        loadPending: Boolean, hasDiffs: Boolean, loadStalled: Boolean = false,
    ): ChangesBody = when {
        hasDiffs -> ChangesBody.LIST
        noGit -> ChangesBody.NO_GIT
        scope == ReviewChangeScope.LAST_TURN -> ChangesBody.EMPTY
        loadFailed -> ChangesBody.LOAD_FAILED
        loadStalled -> ChangesBody.LOAD_SLOW
        loadPending -> ChangesBody.LOADING
        else -> ChangesBody.EMPTY          // ← ★ 「无未提交改动」就是从这里出来的
    }
```

`loadFailed` 由 `SessionScreen.kt:450` `val vcsDiffFailed = vcsDiffResult is ReviewVcsDiffResult.Failed` 提供；上游给了 `Loaded(空)`，所以 `loadFailed = false` → 落到 `else -> EMPTY` → `ReviewChangesTab.kt:253` 渲染 `oc_review_changes_empty_git` = **`No uncommitted changes yet`**（`res/values/strings.xml:571`）。

### 1.3 现场读数（与报告完全吻合）

| 候选 ref | 返回 | 耗时 |
| --- | --- | --- |
| `<dir>`（陈旧 location graph，无 VCS provider） | `[]` | **毫秒级** |
| `<dir>/.`（新鲜 graph，真实 207 文件树） | **20 s 客户端 deadline 超时** | 20 s |

`1 × 瞬时 [] + 1 × 20 s deadline` ≈ **T+23 s** —— 报告里「点 Refresh 后 T+23s 横幅消失、出现 `main` chip、显示 `No uncommitted changes yet`」的时间戳逐项对上。

### 1.4 「Refresh 到底发没发」—— 发了

报告未闭环表最后一行担心「Refresh 可能没重发」。**排除**：Refresh 的 `reprobeReviewVcs` → `reviewReprobeLocation` → `reloadAfterEvict`（`SessionScreen.kt:997-1017`）会 `reviewReprobeKey++`，而 `LaunchedEffect(activeSession.id, reviewOpen, mobileReview, summaryOpen, vcsRevision, currentDirectory, …)` 以该 key 为键重跑全部 review 读。**D11 的读确实重发了**（`main` chip 出现本身就是重发的证据，且 `reviewVcsDiff` 的新结果把 `LOAD_FAILED` 换成了 `EMPTY`）。问题**不在重发，在聚合**。

### 1.5 为什么 N6-b 的失败/截断标记没盖住这条路径

N6-b 的诚实化全部作用在**渲染层**，输入是一个 `loadFailed: Boolean`：

- `ReviewScopes.changesBody`（`ReviewModels.kt:623`）
- `ReviewScopes.changesSummaryPartial`（`ReviewModels.kt:598`）
- `LoadFailedEmpty` / `oc_review_changes_failed_hint`（`ReviewChangesTab.kt:261-264`）

三层守卫**都写对了**，但它们的**输入被上游伪造了**：VM 返回 `Loaded(空)` ⇒ `loadFailed = false` ⇒ 所有守卫按设计放行 ⇒ 干净文案。它们是「裁判规则正确、比分被上游改写」。所以要修的是**聚合层**（`reviewVcsDiff`），而不是再加固渲染层。

### 1.6 关键领域事实（修复所依据）

一个**没有 VCS provider 的 location 对 `vcs/diff` 恒返回 `[]`**，与工作区是否有改动无关 —— 这条已由本仓 `ReviewVcsReprobe` 的实测记录在案（`ReviewModels.kt:700-706`）：

> `GET /api/vcs/diff` and `/api/vcs/status` answer `[]`

也就是说：**`[]` 在多候选走查里从来不是「干净」的证据**。只有当**所有**候选都回答了、且都回答 `[]` 时，`[]` 才是真相。

---

## 2. 改动

改动共 3 个文件（均在本任务写权限内）。

### 2.1 `ui/session/review/ReviewVcsProbePlan.kt`（新增纯函数规则）

新增 `Attempt` / `Verdict` / `verdict(...)`：

```kotlin
    sealed interface Attempt {
        data class Answered(val files: Int) : Attempt
        data class Failed(val failure: Failure) : Attempt
    }

    fun verdict(attempts: List<Attempt>): Verdict = when {
        attempts.any { it is Attempt.Answered && it.files > 0 } -> Verdict.FILES
        attempts.none { it is Attempt.Answered }                 -> Verdict.FAILED
        attempts.any { it is Attempt.Failed }                    -> Verdict.FAILED   // ★ D11
        else                                                      -> Verdict.EMPTY
    }

    enum class Verdict { FILES, EMPTY, FAILED }
```

真值表（`ReviewDiffHonestyV97Test` 已逐行钉住）：

| 输入（按候选顺序） | 旧行为 | 新行为 |
| --- | --- | --- |
| `[]` | `Failed` | `FAILED` |
| `[Answered(0), Answered(0)]` | `Loaded(空)` → 文案「无未提交改动」 | `EMPTY` → **不变**（真空） |
| `[Answered(3)]` | `Loaded(3)` | `FILES` → **不变**（A2） |
| `[Answered(0), Answered(3)]` | `Loaded(3)` | `FILES` → **不变** |
| `[Answered(3), Failed(T)]` | `Loaded(3)` | `FILES` → **不变** |
| `[Failed(T)]` | `Failed` | `FAILED` → **不变** |
| **`[Answered(0), Failed(T)]`** | **`Loaded(空)` → 「无未提交改动」** | **`FAILED` → 失败态** ★ |
| **`[Failed(T), Answered(0)]`** | **`Loaded(空)`** | **`FAILED`（与顺序无关）** ★ |
| **`[Answered(0), Answered(0), Failed(T)]`** | **`Loaded(空)`** | **`FAILED`** ★ |

### 2.2 `ChatViewModel.kt`（`reviewVcsDiff` 接线）

- 循环前 `val candidates = reviewVcsCandidates()`（避免二次求值），`val attempts = ArrayList<ReviewVcsProbePlan.Attempt>(candidates.size)`；
- 每次**成功**都记 `attempts += Attempt.Answered(list.size)`，每次**抛异常**都记 `attempts += Attempt.Failed(kind)` —— 关键是把原来的 `} ?: continue` 拆成「先记录、再 `if (list == null) continue`」，使失败**不再是一句 `continue`**，而是走查的**一等结果**；
- `settled` 改由 `attempts.any { it is Attempt.Answered }` 求值（语义与原 `diffs != null || answered` 完全等价）；
- 末尾三态 `when`：

```kotlin
            val result = when (ReviewVcsProbePlan.verdict(attempts)) {
                ReviewVcsProbePlan.Verdict.FILES ->
                    diffs?.let(ReviewVcsDiffResult::Loaded) ?: ReviewVcsDiffResult.Failed
                ReviewVcsProbePlan.Verdict.EMPTY -> ReviewVcsDiffResult.Loaded(emptyList())
                ReviewVcsProbePlan.Verdict.FAILED -> ReviewVcsDiffResult.Failed
            }
```

### 2.3 `app/src/test/java/.../ReviewDiffHonestyV97Test.kt`（新增 9 用例）

---

## 3. 为什么不算「降低断言强度」

1. **没有删改任何既有断言**：`ReviewChangesLoadStateTest`(21) / `ReviewSearchV97FixTest`(12) / `ReviewVcsProbePlanTest`(8) / `ReviewVcsReprobeTest`(12) / `ReviewWave5Test`(30) / `ReviewModelsTest`(12) / `OpenCodeClientReviewTest`(4) / `ChatStoreReviewEventTest`(5) / `StringsLocaleParityTest`(3) **原样通过**，无一处注释掉或放宽。
2. **既有源码 pin 全部仍然成立**，且我**新增**了更严的 pin：
   - `theHostDiffReadAnswerIsAFailureOrALoadedListNeverABareNull` 断言 `reviewVcsDiff` 内**不出现** `?: emptyList()` —— 新代码写的是 `Verdict.EMPTY -> ReviewVcsDiffResult.Loaded(emptyList())`（无 `?:`），该断言继续通过，且语义更明确（空只在 `EMPTY` 分支产生）。
   - 既有 `assertTrue(diffRead.contains("ReviewVcsDiffResult::Loaded"))` 仍通过（`FILES` 分支保留 `diffs?.let(ReviewVcsDiffResult::Loaded)`）。
   - **新增** `theHostAggregatesThroughThePureRuleNotThroughTheLastAnswer`：断言循环体内**完全不出现** `ReviewVcsDiffResult` —— 候选的列表**不可能**在走查结束前被提升为最终答案，从结构上封死 D11 形态。
   - **新增** `theCleanCopysPreconditionIncludesASuccessfulRead`：断言 `changesBody` 中 `loadFailed -> LOAD_FAILED` 的分支位置**早于** `else -> EMPTY`，且 `LAST_TURN` 早于两者 —— 干净文案的前置**必须**包含「读取成功」。
3. **覆盖面只增不减**：新枚举 `Verdict` 的 3 个分支在生产代码里全部被显式处理，测试逐个 pin。

## 4. 失败/超时/截断三态的诚实性

### 4.1 三种情况现在分别落在哪里

| 情况 | Host 结果 | `changesBody` | 文案（**全部既有串**） |
| --- | --- | --- | --- |
| **超时/失败**（含 D11 混合走查） | `ReviewVcsDiffResult.Failed` | `LOAD_FAILED` | `oc_review_changes_failed_hint`（含 `Tap Refresh to try again` + 重试按钮） |
| **仍在途** < 5 s | `null` | `LOADING` | `oc_common_loading` |
| **仍在途** ≥ 5 s | `null` + `loadStalled` | `LOAD_SLOW` | `oc_review_changes_failed_hint`（明确点名读没回来，并指向「刷新」） |
| **截断 / 回退** | `Loaded(非空)` | `LIST` | 列表照常显示，但 `changesSummaryPartial` **抑制** `+N -N`，不许把别的来源的总数挂在本 scope 的 chip 下 |
| **真空**（所有候选都答 `[]`） | `Loaded(空)` | `EMPTY` | `oc_review_changes_empty_git` = 「No uncommitted changes yet」 |

**四种不诚实/不可读状态，现在没有一种能拿到「干净」结论。**

### 4.2 「这次读到了空」vs「这次读失败」可区分

- `Loaded(空)` 只在 `Verdict.EMPTY`（全体候选都答 `[]`）产生；
- `Failed` 在 `[Answered(0), Failed]`、`[Failed]`、`[]` 产生；
- 二者在 `ReviewDiffHonestyV97Test.onlyASuccessfullyReadEmptyScopeRendersTheCleanCopy` 里被断言**渲染出不同的 body**（`EMPTY` vs `LOAD_FAILED`），且**对象不相等**。

### 4.3 未新增任何用户可见字符串

- 失败态复用 `oc_review_changes_failed_hint`（`ReviewChangesTab.kt:261/274` 已在用）；
- 慢读态本就复用同一串（`ReviewChangesTab.kt:269-276`，v49 起）；
- 干净文案仍是 `oc_review_changes_empty_git`（`strings.xml:571`）。
- 三语（`values/`、`values-zh-rCN/`、`values-zh-rTW/`）**零改动**，`StringsLocaleParityTest` 3/3 通过即证。

---

## 5. D10 结论：**非缺陷，是验证方法的漏检**（未改代码）

报告 D10 记「三个复制按钮点击后无任何可见反馈」。逐行核对源码，**三处都已实现与侧问面板同款的 2000 ms ✓ 反馈，且都复用既有串与既有 drawable**：

| 按钮 | 实现位置 | 图标切换 | 文案切换 | 复位 |
| --- | --- | --- | --- | --- |
| `Copy code` | `ui/markdown/MarkdownRenderer.kt:335-344, 396-418` | `ic_oc_copy` → `ic_oc_check`（:418） | `oc_md_copied`（:398，= "Copied"） | `COPIED_RESET_MS = 2000L`（:683） |
| `Copy response` / `Copy message` | `ui/session/timeline/MessageMetaRow.kt:176-214` | `ic_oc_copy` → `ic_oc_check`（:208） | `oc_meta_copied`（:184/:188，= "Copied"） | `COPIED_RESET_MS = 2_000L`（:229） |
| （对照）侧问面板 | `ui/session/timeline/ToolRenderers.kt:454-458, 570` | `ic_oc_check`（:570） | `oc_meta_copied`（:454） | 2000 ms（:555） |

**为什么报告读不到**：证据是 `c2_011_dismissed.xml` vs `c2_012_copyresp.xml` 的**文本节点差集为空**。而该反馈的两条载体里——

1. **图标切换是 drawable 变化，不是文本节点**，`uiautomator dump` 的 `text=` 差集在原理上看不见；
2. **文案切换只活 2000 ms**（`contentDescription` 由 `copyLabel` 挂在 clickable Box 上，`MessageMetaRow.kt:188/204`），而报告的两份 dump 之间夹着「粘贴回贴 + 读回校验」的一整轮人工操作，必然已过窗口。

这与报告已自记的 **D9（`uiautomator dump` 解析陷阱）** 同源：**方法论漏检，不是产品缺陷**。故 **D10 不改代码**。

**若后续仍要更强反馈的建议方案（均不需要新串）**：把 `COPIED_RESET_MS` 提到 2000 ms 以上是可选项，但更好的做法是**沿用本仓既有的 Toast 通路**（`oc_review_refresh_done` / `oc_copy_details_copied` 一类已有串，且 `SessionScreen.reloadAfterEvict` 已在用 `Toast`）——不过 2000 ms 的 ✓ 已与上游 `message-content.tsx:522-527`（`setTimeout(() => setCopied(false), 2000)`）逐字对齐，**加 Toast 反而偏离上游**，故不建议。**建议只改验证方法**：在 2 s 窗口内 dump，并同时 diff `content-desc` 与 `bounds`。

---

## 6. 测试证据

### 6.1 命令

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.ReviewDiffHonestyV97Test" `
  --tests "com.xiufeigo.opencodeshell.ReviewVcsProbePlanTest" `
  --tests "com.xiufeigo.opencodeshell.ReviewSearchV97FixTest" `
  --tests "com.xiufeigo.opencodeshell.ReviewModelsTest" `
  --tests "com.xiufeigo.opencodeshell.ReviewWave5Test" `
  --tests "com.xiufeigo.opencodeshell.ReviewVcsReprobeTest" `
  --tests "com.xiufeigo.opencodeshell.OpenCodeClientReviewTest" `
  --tests "com.xiufeigo.opencodeshell.ChatStoreReviewEventTest" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest" `
  --tests "com.xiufeigo.opencodeshell.ReviewChangesLoadStateTest"
```

### 6.2 BUILD 原文（尾部）

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 9s
52 actionable tasks: 1 executed, 51 up-to-date
```

### 6.3 用例数（`app/build/test-results/testDebugUnitTest/*.xml`）

| 测试类 | 用例 | 失败 | 错误 |
| --- | ---: | ---: | ---: |
| **ReviewDiffHonestyV97Test（新增）** | **9** | 0 | 0 |
| ReviewChangesLoadStateTest（含 v10/A2 钉子） | 21 | 0 | 0 |
| ReviewWave5Test | 30 | 0 | 0 |
| ReviewSearchV97FixTest | 12 | 0 | 0 |
| ReviewModelsTest | 12 | 0 | 0 |
| ReviewVcsReprobeTest | 12 | 0 | 0 |
| ReviewVcsProbePlanTest | 8 | 0 | 0 |
| ChatStoreReviewEventTest | 5 | 0 | 0 |
| OpenCodeClientReviewTest | 4 | 0 | 0 |
| StringsLocaleParityTest | 3 | 0 | 0 |
| **合计** | **116** | **0** | **0** |

`ReviewDiffHonestyV97Test` 9 个用例名：

```
theVerdictTableIsExhaustiveAndNeverGuessesClean
anOutStandingReadNeverRendersTheCleanCopy
aTruncatedOrFallbackReadNeverClaimsTheScopesOwnTotals
anEmptyReadAndAFailedReadAreNeverTheSameVerdict
onlyASuccessfullyReadEmptyScopeRendersTheCleanCopy
theFailureBodyReusesTheExistingString
theHostAggregatesThroughThePureRuleNotThroughTheLastAnswer
theCleanCopysPreconditionIncludesASuccessfulRead
aRetryThatLandsRestoresTheRealData
```

### 6.4 三条硬性要求的覆盖

| 任务书要求 | 钉住的用例 |
| --- | --- |
| ① 失败/超时态**不得**产出「空即干净」 | `theVerdictTableIsExhaustiveAndNeverGuessesClean`（`[Answered(0), Failed]` → `FAILED`）、`theHostAggregatesThroughThePureRuleNotThroughTheLastAnswer`（循环内无 `ReviewVcsDiffResult`） |
| ② 读到空 vs 读失败产出**不同**状态（纯函数三态真值表） | `theVerdictTableIsExhaustiveAndNeverGuessesClean`（完整 8 行真值表）、`anEmptyReadAndAFailedReadAreNeverTheSameVerdict`（对 4×4 输入字母表全枚举）、`onlyASuccessfullyReadEmptyScopeRendersTheCleanCopy`（`EMPTY` vs `LOAD_FAILED`，并断言对象不等） |
| ③ 源码 pin：渲染「无未提交改动」前置必须含「读取成功」 | `theCleanCopysPreconditionIncludesASuccessfulRead`（`loadFailed -> LOAD_FAILED` 分支位置早于 `else -> EMPTY`）、`theFailureBodyReusesTheExistingString`（失败体复用既有串、干净串为既有串） |

---

## 7. 真机复验步骤

> 前提：模拟器 5556（`adb -s emulator-5556`），窄窗 `wm size 560x768` + `wm density 160`，后端指向本仓目录 `/mnt/c/sensorsdata/main/program/opencode-android-shell`。

| # | 操作 | **期望（修复后）** |
| --- | --- | --- |
| 0 | host 侧取 ground truth | `git status --porcelain \| wc -l` = **3019**；`git diff --shortstat HEAD` = **`207 files changed, 58099 insertions(+), 9251 deletions(-)`** |
| 1 | 打开真实仓库会话 `E2ECHK5556v98_recheck2` → `Changes`（Git scope） | T+5 s：`The diff request timed out…Tap Refresh to try again.`（`LOAD_SLOW`） |
| 2 | 等到 T+23 s | **仍为失败态**（`LOAD_FAILED`，含重试按钮）。**不得**出现 `No uncommitted changes yet` ← **本条即 D11 断言** |
| 3 | 点 `Refresh` | 出现 `main` ref chip；读重发（`reviewReprobeKey++`） |
| 4 | T+23 s 再读正文 | **二选一，且都算通过**：<br>① 列表出现 207 个文件 → 恢复成功（A2 不破坏）<br>② 仍是失败态/慢读态 → **诚实失败**（D11 已修）<br>**唯独不允许**：`No uncommitted changes yet` |
| 5 | 对照：切 `Last-turn` scope | 走 session 快照，与 VCS 失败态无关（`changesBody` 的 `LAST_TURN` 早于 `loadFailed`），已 pin |
| 6 | 回归 A2 | 复跑 `ReviewChangesLoadStateTest`（21 例）全绿；真机上「超时 → Refresh → 恢复真实数据」路径未被削弱（`Verdict.FILES` 分支不受任何新规则影响） |

**判定命令**（UI 断言，注意 D9：同时匹配双引号与单引号）：

```powershell
adb -s emulator-5556 shell uiautomator dump /sdcard/d11.xml | Out-Null
adb -s emulator-5556 pull /sdcard/d11.xml .\d11.xml | Out-Null
Select-String -Path .\d11.xml -Pattern "No uncommitted changes yet|没有未提交|timed out|Tap Refresh"
# 修复后期望：只出现 timeout/Refresh 一侧，**不得**出现 "No uncommitted changes yet"
```

---

## 8. 未闭环

| 项 | 状态 | 原因 / 建议 |
| --- | --- | --- |
| **真机复验（§7）** | **未执行** | 本任务纪律限定只跑 `:app:testDebugUnitTest`，**禁止 assembleDebug / 装机**。需由持有真机/装机权限的 agent 按 §7 跑一遍并回填 `c2_082` 同构证据。JVM 侧已用纯函数真值表 + 源码 pin 锁死同一判定，但**真机像素级尚未复验**。 |
| **「后端 60.2 s 未返回」与 D11 的因果确证** | **部分确证** | 时序（1×瞬时 `[]` + 1×20 s deadline ≈ T+23 s）与读数逐项吻合，且旧代码的缺陷形态与之完全对应；但**未抓到该次请求的 `location[directory]` 实际返回值**（logcat 未留存）。建议复验时同时抓 `logcat -s OpenCodeShell:V`，确认 canonical ref 确为 `[]`、`<dir>/.` 确为超时。 |
| **`Refresh` 是否需要「读失败」时禁用/降级** | **未做（有意）** | 失败态已提供「重试本次读」（`onRetryDiff`）与「刷新」（`onRefresh`）两条语义不同的出口，二者不冲突，现状可用。 |
| **D10 复验** | **结论已定（非缺陷）** | 若要落锤，只需在 2 s 窗口内 dump 并 diff `content-desc` + `bounds`（见 §5）。**不需要改代码。** |
| **`mode=working` 的服务端截断** | **未闭环（超出本轮）** | 截断只会产生**非空**列表 ⇒ `LIST`，本就不会渲染「干净」，故不属于 D11；`changesSummaryPartial` 已抑制其 `+N -N`。但「部分列表本身是否需要可见的不完整标记」仍是开放项（现有策略见 `ReviewModels.kt:592-596` 的取舍说明）。 |

---

## 9. 改动文件清单

| 文件 | 改动 | 写权限 |
| --- | --- | --- |
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewVcsProbePlan.kt` | 新增 `Attempt` / `Verdict` / `verdict()` 纯规则 + 完整领域说明 | ✅ `ui/session/review/**` |
| `app/src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt` | `reviewVcsDiff` 走查接线：记录每个候选结果 + 三态 `when` | ✅ `ChatViewModel.kt`（确需，已确需） |
| `app/src/test/java/com/xiufeigo/opencodeshell/ReviewDiffHonestyV97Test.kt` | 新增 9 个纯 JVM 用例 | ✅ 新测试文件 |

未触碰：`_upstream/**`、`app/build.gradle.kts`、`ui/home/**`、`ui/nav/**`、`ui/App.kt`、`ui/servers/**`、`ui/settings/**`、`ui/session/header/**`、`res/values*/strings.xml`（**零字符串改动**）。
