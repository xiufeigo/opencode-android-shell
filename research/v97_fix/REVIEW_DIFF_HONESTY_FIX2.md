# REVIEW_DIFF_HONESTY_FIX2 — v97 D11 轮次二（Review→Changes 闪现式「假空」）

- **缺陷来源**：`research/v97_verify/5556_v98/REPORT_D11.md` §3 条目 2（假空复现）/ §3 条目 3（机制定位）/ §5 第 1 行（待开发项）
- **上一轮修法**：`research/v97_fix/REVIEW_DIFF_HONESTY_FIX.md`（三态 `Verdict`：FILES / EMPTY / FAILED）
- **回归**：10 个测试类 / **103 用例 / 0 失败 / 0 错误**（其中新增 `ReviewDiffReadStateV97Test` 8 例）
- **是否新增用户可见字符串**：**否**（全部复用既有串，见 §4.2）
- **改动文件**：4 个（均在写权限内）

---

## 0. 结论速览

| 复验项 | 上一轮状态 | 本轮状态 |
| --- | --- | --- |
| 「已知道要重发」那一刻是否仍渲染 `No uncommitted changes yet` | **是**（17% 概率闪现） | **否** —— 两个成因都已封死 |
| 渲染门是否有「在飞」这一态 | **没有**（`Attempt` 只有 Answered/Failed） | **有**（`Attempt.Pending` / `Verdict.IN_FLIGHT` / `readPhase`） |
| 读失败/超时是否仍诚实失败 | 是 | **是（不变）** |
| 是否新增用户可见字符串 | 否 | **否** |
| 真机复验 | 已跑，FAIL | **未跑**（本任务纪律禁止装机，见 §6） |

---

## 1. 根因

### 1.1 一句话

上一轮把**聚合规则**改对了，但没有改**聚合时机**和**候选来源**：`Attempt` 里没有「在飞」这一态，所以「还没人回来」和「大家都说没有改动」只能靠*看的时刻*区分；而 `reviewVcsInfo` 又把一个**只答过 `vcs/status`** 的 ref 写进了 diff 走查的「唯一可信 ref」记忆里，把走查压成单候选 —— 那个 ref 毫秒级答 `[]`，于是 `verdict([Answered(0)])` 落进 `EMPTY`，`No uncommitted changes yet` 在 T+3s 上屏，而**能证伪它的 fresh-graph ref 根本没被问过**。

### 1.2 因果链（含 `文件:行号`）

#### ① 候选来源被污染 —— 「谁有资格被信任」跨读串了

`ChatViewModel.kt:5305-5333`（`reviewVcsInfo`，修复前）：

```kotlin
                last = info
                if (ReviewVcsReprobe.answered(info.branch, 0)) {
                    rememberReviewVcsRef(ref)      // ← ★ 写进 diff 走查的记忆
                    answered = info
                    break
                }
            }
            if (answered == null) markReviewVcsProbed()   // ← ★ 同上
```

而 `reviewVcsCandidates()`（`ChatViewModel.kt:5517-5522`）读的正是这两个容器：

```kotlin
    private fun reviewVcsCandidates(): List<String?> =
        ReviewVcsProbePlan.candidates(
            directory = directory,
            remembered = directory?.let { reviewVcsRefs[it] },      // ← info 写进来的
            probed = directory != null && directory in reviewVcsProbed, // ← info 写进来的
        )
```

`ReviewVcsProbePlan.kt` 的候选规则第 50 行是：

```kotlin
        remembered?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
```

**一次 `vcs/status` 答出 `main` 分支，就把该 ref 提升为「唯一值得问的 ref」。**

关键领域事实（本仓已在案，`ReviewModels.kt` `ReviewVcsReprobe`）：**缓存图早于仓库的 location 对 `vcs/status` 和 `vcs/diff` 都答得出来，而它的 `vcs/diff` 恒为 `[]`，与工作区是否有改动无关。** 于是「`vcs/status` 答得出来」根本不能推出「`vcs/diff` 说这个仓库是干净的」。

#### ② 走查被压成单候选 → 空答案独占结论

刷新后 `forgetReviewVcsProbe` 清空记忆 → 首读是 `[<dir>, <dir>/.]` 两条；`reviewVcsInfo` 答出 `main` 之后**下一次**走查变成 `listOf(<dir>)`。

单候选走查：`<dir>` → `[]`（毫秒级）→ `attempts = [Answered(0)]` → 上一轮那条**本身写对了**的规则：

```kotlin
        attempts.any { it is Attempt.Answered && it.files > 0 } -> Verdict.FILES
        attempts.none { it is Attempt.Answered }                 -> Verdict.FAILED
        attempts.any { it is Attempt.Failed }                    -> Verdict.FAILED
        else                                                      -> Verdict.EMPTY   // ★ T+3s 上屏的就是这里
```

真值表对**已完成的 attempts 集合**是对的 —— 但这一次的集合是**一条**。规则没有错，输入被削成了一条；`<dir>/.` 从未被问，因此**不可能有后续失败来覆盖它**。

#### ③ 渲染门没有「在飞」这一态

`ReviewChangesTab.kt:260-284` 的四臂（`LOAD_FAILED` / `LOAD_SLOW` / `LOADING` / `else`）已经齐全，`changesBody`（`ReviewModels.kt:623-638`）也把 `loadFailed` 排在 `else -> EMPTY` 之前 —— 守卫是对的。缺的是**输入**：上一轮 `Attempt` 只有 `Answered`/`Failed`（`REPORT_D11.md` §3 条目 3 已点名），「还没人回来」在类型上无处安放，只能被表达成「没有结论」或「空结论」，而调用方在毫秒级的那一瞬读到的就是后者。

#### ④ 上一轮的那次走查会覆盖本次的结论（T+20s 的来源）

`SessionScreen.kt:456-461` 只有一个槽位，新尝试一开始就把它清成 `null`（→ `vcsDiffPending`，`SessionScreen.kt:451`）。但 `reviewVcsDiff` 的协程挂在 **ViewModel 自己的 `scope`** 上，`LaunchedEffect` 的取消**取消不了它**：上一轮那条 20s deadline 的走查在重试开始时仍在跑，它落地时会把结论写进**新尝试的槽位**。这就是报告里 T+3s 假空 / T+20s 失败横幅**互相覆盖**的那 17% 窗口 —— 两个结论都拿到了一次发言权，而它们互相矛盾。

### 1.3 时序（与报告读数逐项对齐）

| 时刻 | 发生了什么 | 修复前 UI |
| --- | --- | --- |
| T+0 | 用户点 `Refresh` → evict → `forgetReviewVcsProbe` → `reviewReprobeKey++` → 槽位清 `null` | loading（正确） |
| T+~0.3s | 新走查 `candidates()`：**此时记忆已被 `reviewVcsInfo` 污染** → `listOf(<dir>)` | loading |
| **T+3s** | `<dir>` 答 `[]`（陈旧图，无 VCS provider）→ `[Answered(0)]` → `EMPTY` → `Loaded(emptyList())` | **❌ 假空** |
| T+20s | 旧走查（两条候选）超时 → `FAILED`，覆盖同一槽位 | 失败横幅（自愈） |
| T+~40s | 稳定 | 诚实失败 |

复现率 17% = 「刷新时是否恰好还有一条旧走查在飞」的命中率，与报告的间歇性完全吻合。

---

## 2. 改动 diff

### 2.1 `ui/session/review/ReviewVcsProbePlan.kt` —— 让「在飞」成为一个值

```diff
     sealed interface Attempt {
+        /**
+         * The ref is **in flight** — issued, not yet answered. The v97 D11 round
+         * one only modelled the settled outcomes …: the only way to publish was
+         * to publish a finished walk, and a caller that read the halfway state
+         * (or raced a second, shorter walk over the same slot) could promote a
+         * stale `[]` to a whole-walk answer. 未知 ≠ 空 starts here.
+         */
+        data object Pending : Attempt
+
         data class Answered(val files: Int) : Attempt
         data class Failed(val failure: Failure) : Attempt
     }

     fun verdict(attempts: List<Attempt>): Verdict = when {
+        // 未知 ≠ 空 (v97 D11-b)
+        attempts.any { it is Attempt.Pending }          -> Verdict.IN_FLIGHT
         attempts.any { it is Attempt.Answered && it.files > 0 } -> Verdict.FILES
         …（其余三臂逐字不变）
     }

     enum class Verdict {
+        IN_FLIGHT,
         FILES,
         EMPTY,
         FAILED,
     }
```

新增四态与「干净文案许可证」（纯函数）：

```kotlin
    fun readPhase(attempts: List<Attempt>): ReadPhase = when (verdict(attempts)) {
        Verdict.IN_FLIGHT -> ReadPhase.IN_FLIGHT
        Verdict.FILES -> ReadPhase.SETTLED_NONEMPTY
        Verdict.EMPTY -> ReadPhase.SETTLED_EMPTY
        Verdict.FAILED -> ReadPhase.SETTLED_FAILED
    }

    fun mayRenderClean(phase: ReadPhase): Boolean = phase == ReadPhase.SETTLED_EMPTY
```

真值表（`ReviewDiffReadStateV97Test` 逐行钉住）：

| `readPhase` 输入 | `ReadPhase` | `mayRenderClean` | 渲染 |
| --- | --- | --- | --- |
| 含任意 `Pending` | `IN_FLIGHT` | **false** | loading / slow |
| 全体 `Answered(0)` | `SETTLED_EMPTY` | **true** | 真空（`oc_review_changes_empty_git`） |
| 存在 `Answered(n>0)` | `SETTLED_NONEMPTY` | false | 列表 |
| 无 `Answered` / `Answered(0)` 旁有 `Failed` | `SETTLED_FAILED` | false | 失败（`oc_review_changes_failed_hint`） |

### 2.2 `ChatViewModel.kt` —— 三处接线

**(a) `reviewVcsInfo`（`:5305-5333`）不再污染 diff 的记忆**

```diff
             for (ref in reviewVcsCandidates()) {
                 …
                 last = info
                 if (ReviewVcsReprobe.answered(info.branch, 0)) {
-                    rememberReviewVcsRef(ref)
                     answered = info
                     break
                 }
             }
-            if (answered == null) markReviewVcsProbed()
             withContext(Dispatchers.Main) { onResult(answered ?: last) }
```

`reviewVcsCandidates()` 这个调用点**原样保留**（`ReviewVcsReprobeTest.theHostFetchChainWalksTheReProbeCandidates` 依赖它），info 走查仍然走同一份候选表；只是**不再写**。代价：每次 info 读多一次便宜的 `vcs/status`。收益：一个没被证实的 ref 再也无法独自结束一次 diff 走查。

**(b) `reviewVcsDiff`（`:5375-5477`）把「在飞」种进走查**

```diff
+        val attemptId = reviewVcsDiffAttempt.incrementAndGet()
         scope.launch(Dispatchers.IO) {
             …
             val attempts = ArrayList<ReviewVcsProbePlan.Attempt>(candidates.size)
+            repeat(candidates.size) { attempts += ReviewVcsProbePlan.Attempt.Pending }
             var failure: ReviewVcsProbePlan.Failure? = null
             for (ref in candidates) {
+                if (attempts.isNotEmpty() && attempts[0] is ReviewVcsProbePlan.Attempt.Pending) {
+                    attempts.removeAt(0)
+                }
                 val list = try { … }
```

每条候选带一个「在飞」占位进入走查，本轮迭代先退休自己**再**追加自己的结果。**最终送进规则的列表与修复前逐字相同**（占位全部退休），所以已 settle 的行为一字未变；变的是**中途被观察时**规则看到的是 `IN_FLIGHT` 而不是「已经定了」。

**(c) 发布门 —— 未 settle 不发布，被取代者不发布**

```diff
-            val result = when (ReviewVcsProbePlan.verdict(attempts)) {
+            val result: ReviewVcsDiffResult? = when (ReviewVcsProbePlan.verdict(attempts)) {
+                ReviewVcsProbePlan.Verdict.IN_FLIGHT -> null      // 未 settle → 什么都不发
                 ReviewVcsProbePlan.Verdict.FILES -> …
                 ReviewVcsProbePlan.Verdict.EMPTY -> ReviewVcsDiffResult.Loaded(emptyList())
                 ReviewVcsProbePlan.Verdict.FAILED -> ReviewVcsDiffResult.Failed
             }
-            withContext(Dispatchers.Main) { onResult(result) }
+            if (result != null && attemptId == reviewVcsDiffAttempt.get()) {
+                withContext(Dispatchers.Main) { onResult(result) }
+            }
```

`reviewVcsDiffAttempt`（`ChatViewModel.kt:5244`）在 `reviewVcsDiff` 入口 `incrementAndGet()`，发布前比对。**被取代的走查闭嘴** —— 它的结论描述的是用户已经放弃的那次读，让它写进新尝试的槽位，就是 T+3s/T+20s 互相覆盖的直接成因。闭嘴之后 UI 保持 unknown，而这对两次读**都是真的**。

---

## 3. 为什么不算「降低断言强度」

1. **没有删改任何既有断言**：`ReviewVcsProbePlanTest`(8) / `ReviewSearchV97FixTest`(12) / `ReviewModelsTest`(12) / `ReviewWave5Test`(30) / `ReviewVcsReprobeTest`(12) / `OpenCodeClientReviewTest`(4) / `ChatStoreReviewEventTest`(5) / `StringsLocaleParityTest`(3) **原样通过**（§5.3 逐类计数），无一处注释掉或放宽。
2. **`ReviewVcsProbePlanTest` 的一条真断言没有被绕过**：`a remembered ref wins over both the fresh-graph list and the probed flag` 仍钉住 `candidates("/repo", remembered="/repo/.")` → `listOf("/repo/.")`。本轮**没有改 `candidates()` 的规则**——改的是**谁来写 `remembered`**，即让那个「已答非空 diff」的前置真正成立，而不是删掉断言。
3. **`ReviewDiffHonestyV97Test` 的 9 条断言一条未动**，只扩了镜像 `when` 的 `IN_FLIGHT` 臂（原 `hostResult` 对 `Verdict` 穷举，新增枚举值后必须显式表态，映射为 `null` = 不发布 = `loadPending`）。`body()` 顺带把 `null` 映射成 `loadPending` —— 这是**加强**（原来 `loadPending` 恒为 `false`，等于从未 pin 过 pending 态）。
4. **覆盖面只增不减**：`Verdict` 从 3 态增到 4 态、`readPhase` 4 态、`mayRenderClean` 许可证，全部被生产代码引用并逐条 pin。
5. **既有源码 pin 全部仍然成立**：`attempts += …Answered(list.size)` / `…Failed(kind)` / `ReviewVcsProbePlan.verdict(attempts)` / 循环内不出现 `ReviewVcsDiffResult` / `!contains("?: emptyList()")` / `contains("ReviewVcsDiffResult::Loaded")` —— 全部逐字保留（`ReviewDiffReadStateV97Test` 还**新增**了一条更强的：循环内**任何** `ReviewVcsDiffResult` 都不许出现）。

---

## 4. 诚实性：未知 ≠ 空

### 4.1 四态各自落在哪里

| 状态 | Host 结果 | `changesBody` | 文案（**全部既有串**） |
| --- | --- | --- | --- |
| **在飞**（任意候选未落定） | **不发布**（`null`） | `LOADING` / `LOAD_SLOW` | `oc_common_loading` / `oc_review_changes_failed_hint` |
| **已 settle · 真空** | `Loaded(空)` | `EMPTY` | `oc_review_changes_empty_git` |
| **已 settle · 有数据** | `Loaded(非空)` | `LIST` | 列表 |
| **已 settle · 失败/超时** | `Failed` | `LOAD_FAILED` | `oc_review_changes_failed_hint` |

**四种状态里，只有「已 settle 且全体答 `[]`」能拿到「没有未提交改动」。** 这条现在是**类型上**由 `mayRenderClean` 保证的，不是靠调用方记得判。

### 4.2 未新增任何用户可见字符串

- 在飞态复用 `oc_common_loading`（`ReviewChangesTab.kt:277-279`）与 `oc_review_changes_failed_hint`（`:269-276`，v49 起）；
- 失败态复用 `oc_review_changes_failed_hint`；
- 干净文案仍是 `oc_review_changes_empty_git`；
- 三语（`values/`、`values-zh-rCN/`、`values-zh-rTW/`）**零改动**，`StringsLocaleParityTest` 3/3 通过即证。

### 4.3 「读失败」仍然允许直接显示诚实失败

任务书允许「若这次读失败/超时，允许显示诚实失败，但不允许先闪一下空再变失败」。本轮两处封死：
- 空态要**先**出现，必须先拿到 `Loaded(emptyList())`，而那要求单候选走查的**唯一**候选答 `[]` —— 而这个 ref 现在必须**自己答出过非空 diff** 才有资格独占走查；
- 就算它还是拿到了，只要**还有候选在飞**，发布门返回 `null`；就算发布了，**被取代的走查也闭嘴**。

---

## 5. 测试证据

### 5.1 命令

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.ReviewDiffReadStateV97Test" `
  --tests "com.xiufeigo.opencodeshell.ReviewDiffHonestyV97Test" `
  --tests "com.xiufeigo.opencodeshell.ReviewVcsProbePlanTest" `
  --tests "com.xiufeigo.opencodeshell.ReviewSearchV97FixTest" `
  --tests "com.xiufeigo.opencodeshell.ReviewModelsTest" `
  --tests "com.xiufeigo.opencodeshell.ReviewWave5Test" `
  --tests "com.xiufeigo.opencodeshell.ReviewVcsReprobeTest" `
  --tests "com.xiufeigo.opencodeshell.ChatStoreReviewEventTest" `
  --tests "com.xiufeigo.opencodeshell.OpenCodeClientReviewTest" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest"
```

### 5.2 BUILD 原文（尾部）

```
> Task :app:compileDebugKotlin
> Task :app:compileDebugUnitTestKotlin UP-TO-DATE
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 5s
52 actionable tasks: 2 executed, 50 up-to-date
```

### 5.3 用例数（`app/build/test-results/testDebugUnitTest/*.xml`）

| 测试类 | 用例 | 失败 | 错误 |
| --- | ---: | ---: | ---: |
| **ReviewDiffReadStateV97Test（本轮新增）** | **8** | 0 | 0 |
| ReviewDiffHonestyV97Test（扩 `IN_FLIGHT` 臂） | 9 | 0 | 0 |
| ReviewWave5Test | 30 | 0 | 0 |
| ReviewSearchV97FixTest | 12 | 0 | 0 |
| ReviewModelsTest | 12 | 0 | 0 |
| ReviewVcsReprobeTest | 12 | 0 | 0 |
| ReviewVcsProbePlanTest | 8 | 0 | 0 |
| ChatStoreReviewEventTest | 5 | 0 | 0 |
| OpenCodeClientReviewTest | 4 | 0 | 0 |
| StringsLocaleParityTest | 3 | 0 | 0 |
| **合计** | **103** | **0** | **0** |

`ReviewDiffReadStateV97Test` 8 个用例名：

```
theFourStateTruthTableLicensesOnlySettledEmpty
onlySettledEmptyRendersTheCleanCopy
aWalkThatIsLookedAtEarlySpeaksAsUnknownNotAsEmpty
aReReadEntryLeavesTheCleanStateBeforeAnyReadLands
aStatusAnswerCannotPromoteARefForTheDiffWalk
aSupersededReadNeverPublishesOverTheRetry
theHostSeedsEveryCandidateAsInFlightBeforeWalking
theUnknownStateReusesTheExistingStrings
```

### 5.4 任务书四条硬性要求的覆盖

| 要求 | 钉住的用例 |
| --- | --- |
| ① 四态真值表「哪些可以渲染干净结论」 | `theFourStateTruthTableLicensesOnlySettledEmpty`（四态 × 全字母表枚举，含「必须集齐四态」的断言）、`onlySettledEmptyRendersTheCleanCopy`（四态 → 四个**互不相同**的 body） |
| ② 只要有探测在飞/未 settle 就不许渲染干净 | `aWalkThatIsLookedAtEarlySpeaksAsUnknownNotAsEmpty`（**逐帧复刻 REPORT_D11 的 T+3s 那一帧**：`[Answered(0)]` 旁挂着 `Pending` → `IN_FLIGHT` → `mayRenderClean=false`）、`theHostSeedsEveryCandidateAsInFlightBeforeWalking` |
| ③ 「重发开始 → 立刻离开干净态」源码 pin | `aReReadEntryLeavesTheCleanStateBeforeAnyReadLands`（`vcsDiffResult = null` 必须**早于** `reviewVcsDiff(`；`vcsDiffPending` 必须由该槽位定义） |
| ④ 不得先闪空再变失败 | `aSupersededReadNeverPublishesOverTheRetry`（被取代的走查不发布；`IN_FLIGHT -> null`）、`aStatusAnswerCannotPromoteARefForTheDiffWalk`（`reviewVcsInfo` 不得写 diff 记忆；diff 走查的 `rememberReviewVcsRef(ref)` 全函数**仅出现一次**且必须在 `list.isNotEmpty()` 之内） |

---

## 6. 真机复验步骤（给测试员）

> 前提：`adb -s emulator-5556`（AVD `Medium_Desktop`），后端指向本仓 `/mnt/c/sensorsdata/main/program/opencode-android-shell`，`reverse` 4095/2222 已挂。

| # | 操作 | **期望（修复后）** |
| --- | --- | --- |
| 0 | host 侧取 ground truth | `git status --porcelain \| wc -l`；`git diff --shortstat HEAD` = **`1191 files changed, …`** ← 只要**非空**即可，不必等于本轮数字 |
| 1 | `wm size 560x768` + `wm density 160`，重启 app，进真实仓库会话 → `Changes` | T+5s 起为 loading / 慢读提示 |
| 2 | 等到 T+23s | 诚实失败态（`The diff request timed out or failed. Tap Refresh to try again.`） |
| 3 | 点 `Refresh`，**立即连续采样 T+1s / T+3s / T+10s / T+20s** | **四次都必须不是 `No uncommitted changes yet`**；允许出现 `loading` / 慢读提示 / 失败横幅 |
| 4 | T+45s 再读一次 | 仍是诚实失败态（后端在本仓 90s 都读不回来，见 REPORT_D11 §8） |
| 5 | **连续重复 2–6 轮**（每轮完整走一遍 Refresh + 采样） | **0/6 出现 `No uncommitted changes yet`** ← 上一轮是 1/6，本条是本次修复的核心断言 |
| 6 | 正常窗（`wm size reset` + `wm density reset`）重复第 1–5 步 | 同上；**绝不**出现 `No uncommitted changes yet` |
| 7 | 对照：切 `Last turn changes` scope | 走 session 快照，与 VCS 读状态无关 |
| 8 | **A2 回归**：在**小而干净**的目录上开 `Changes` | 仍应显示 `No uncommitted changes yet`（真空态没被误伤） |
| 9 | 收尾 | `wm size reset` + `wm density reset`；清理本轮 `/sdcard` 产物 |

**判定命令**（注意 D9 纪律：dump 解析要同时匹配双引号与单引号）：

```powershell
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
foreach ($t in 1,3,10,20) {
    Start-Sleep -Seconds $(if ($last) { $t - $last } else { 1 }); $last = $t
    & $adb -s emulator-5556 shell uiautomator dump /sdcard/d11b.xml | Out-Null
    & $adb -s emulator-5556 pull /sdcard/d11b.xml ".\d11b_t$t.xml" | Out-Null
    $hit = Select-String -Path ".\d11b_t$t.xml" -Pattern "No uncommitted changes yet" -Quiet
    "T+${t}s  假空=$hit"     # 期望全为 False
}
```

**同时建议抓 logcat**：`adb -s emulator-5556 logcat -s OpenCodeShell:V`，确认每轮只有**一次** `vcs diff failed for …`、且点 Refresh 后旧走查不再回写（修复前会有两次）。

---

## 7. 未闭环 / 待确认

| 项 | 状态 | 原因 / 建议 |
| --- | --- | --- |
| **真机复验（§6）** | **未执行** | 本任务纪律限定只跑 `:app:testDebugUnitTest`，**禁止 assembleDebug / 装机**。JVM 侧已用四态真值表 + 逐帧复刻 + 源码 pin 锁死同一判定，但**真机像素级尚未复验**。§6 的第 3/5 步是本次修复的核心断言（尤其是 0/6）。 |
| **根因是推断，非 logcat 直证** | **部分确证** | 「`vcs/status` 答出 `main` 从而污染记忆」这一环由 `ReviewVcsReprobeTest` 记录的事实（陈旧图对 `vcs/diff` 恒 `[]`，对 `vcs/status` 答得出来）+ 代码路径 + 报告读数（T+3s 毫秒级假空、`main` chip 同时出现）三方吻合；但**上一轮 logcat 抓空**（REPORT_D11 §5 第 4 行）的问题本轮同样没解决。复验时请按 §6 抓 logcat 坐实「info 走查先于 diff 走查答出 `main`」。 |
| **后端 `vcs/diff` 在本仓持续超时** | **未闭环（超出本任务）** | 1191 文件的工作区让 diff 读不出来（客户端 20s、host 直连 90s 都超时，REPORT_D11 §8）。这意味着「超时 → Refresh → 恢复真实数据」在本仓**永远走不到成功分支**，本轮只保证它**绝不假空**。建议评估分页/惰性加载或按目录切片。 |
| **正常窗读到真实改动** | **未验到** | 受上一条阻塞。**成功路径（`Verdict.FILES`）的回归只在 JVM 侧 pin**（`aRetryThatLandsRestoresTheRealData` / `ReviewDiffReadStateV97Test` §6 第 8 步），真机未验。 |
| **`reviewVcsInfo` 多一次 `vcs/status`** | **有意接受** | info 走查不再记忆，代价是每次多一条便宜的 `vcs/status`（本仓实测毫秒级）。换来的是「未证实 ref 不能独占 diff 走查」。若日后 info 读变贵，可在 `ReviewVcsReprobe` 层面单独给 info 一套记忆，**不得**与 diff 的记忆共用。 |
| **其他 scope 未复验** | **未验** | 本轮只改 `Git`/`Branch` 走查；`Last turn` 走 session 快照，不受影响（`changesBody` 的 `LAST_TURN` 早于 `loadFailed`，已 pin），真机同样未复验。 |

---

## 8. 改动文件清单

| 文件 | 改动 | 写权限 |
| --- | --- | --- |
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewVcsProbePlan.kt` | 新增 `Attempt.Pending` / `Verdict.IN_FLIGHT` / `ReadPhase` / `readPhase()` / `mayRenderClean()` + 领域说明 | ✅ `ui/session/review/**` |
| `app/src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt` | ①`reviewVcsInfo` 不再写 diff 记忆；②`reviewVcsDiff` 种/退休「在飞」占位 + 显式 `IN_FLIGHT -> null`；③`reviewVcsDiffAttempt` 发布门 | ✅ `ChatViewModel.kt`（确需，已确需） |
| `app/src/test/java/com/xiufeigo/opencodeshell/ReviewDiffReadStateV97Test.kt` | **新增** 8 个纯 JVM 用例（四态真值表 / 逐帧复刻 / 4 条源码 pin） | ✅ 新测试文件 |
| `app/src/test/java/com/xiufeigo/opencodeshell/ReviewDiffHonestyV97Test.kt` | 镜像 `when` 增 `IN_FLIGHT -> null` 臂；`body()` 支持 `null`（**扩断言，非放宽**）；9 条断言原文未动 | ✅ 测试文件 |

未触碰：`_upstream/**`、`app/build.gradle.kts`、`ui/home/**`、`ui/nav/**`、`ui/App.kt`、`ui/servers/**`、`ui/settings/**`、`ui/session/header/**`、`ui/session/timeline/**`、`ui/session/SessionScreen.kt`（**本轮只在测试里读它做 pin，未写**）、`res/values*/strings.xml`（**零字符串改动**）。
