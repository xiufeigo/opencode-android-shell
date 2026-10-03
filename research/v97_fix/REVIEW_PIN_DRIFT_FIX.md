# REVIEW_PIN_DRIFT_FIX — v97 面板修复搬走候选构造后，pin 失配的修复

日期：2026-10-02
范围：**仅测试文件** `app/src/test/java/com/xiufeigo/opencodeshell/ReviewVcsReprobeTest.kt`
生产源码改动：**0**（`ChatViewModel.kt` mtime 19:55:38、`ReviewVcsProbePlan.kt` mtime 19:48:21，均早于本次会话唯一的写入 20:17:24）

---

## 1. 根因：哪次重构搬走了什么

同一轮 v97 `Review` 面板修复（N6-②）把「宿主取 ref 的候选构造」从 `ChatViewModel` 抽成了纯函数对象
`ui/session/review/ReviewVcsProbePlan.kt`，动机写在 `ReviewVcsProbePlan.kt:8-12`：

> Split out of `ChatViewModel.reviewVcsDiff` so the rule is a pure function the JVM tests can pin
> without an Android runtime, and so the two halves of the rule — *which refs are still on the table*
> and *may a failure retire them* — cannot drift apart.

搬运结果（`ChatViewModel.kt:5429-5434`，重构**后**的形状）：

```kotlin
private fun reviewVcsCandidates(): List<String?> =
    ReviewVcsProbePlan.candidates(
        directory = directory,
        remembered = directory?.let { reviewVcsRefs[it] },
        probed = directory != null && directory in reviewVcsProbed,
    )
```

旧 pin 断言的 `candidates.contains("ReviewVcsReprobe.refs(dir)")` 是在钉「**宿主自己**按顺序拼
`ReviewVcsReprobe.refs(dir)` / `reviewVcsRefs[dir]` / `reviewVcsProbed` 三段」这段内联代码。
`ReviewVcsReprobe.refs(dir)` 随规则一起搬到了 `ReviewVcsProbePlan.kt:52`（`return ReviewVcsReprobe.refs(dir)`），
所以旧断言必然失配——**这是 pin 正确地报告了重构，不是产品行为退化**。

### 1.1 额外发现：失配的是 **两行**，不是一行

任务简报说「186/187 行仍匹配，只有 185 行失配」。实测**不成立**。按旧 pin 的切片语义
（`substringAfter("private fun reviewVcsCandidates(").substringBefore("private fun rememberReviewVcsRef(")`）
在当前源码上模拟：

| 行 | 断言 | 实际 `contains` | 简报说法 |
|---|---|---|---|
| 185 | `ReviewVcsReprobe.refs(dir)` | **False** | 失配 ✓ |
| 186 | `reviewVcsRefs[dir]` | **False** ← 同样失配 | 「仍匹配」✗ |
| 187 | `reviewVcsProbed` | True | 仍匹配 ✓ |

186 之所以没出现在闸门报告里，纯粹是因为 185 先抛 `AssertionError`，同一方法里的后续断言
根本没被执行到（`assertTrue` 快速失败）。源码里 `reviewVcsRefs[dir]` 也被重构改成了
`reviewVcsRefs[it]`（map 键从字面 `dir` 换成 `let` 里的 `it`，语义等价）。

**这正是先做切片模拟、再动断言的价值**：只按简报改 185 行，闸门会立刻在 186 行以新的
AssertionError 位置再次失败，白跑一轮。

---

## 2. 改动 diff（逐行）

只改一个方法 `theHostFetchChainWalksTheReProbeCandidates`（旧 174-188 行 → 新 174-215 行）。
**176-182 行原样保留**（`info` / `diffRead` 两个宿主读取点的 `reviewVcsCandidates()` 调用与
`answered` / `list.isNotEmpty()` 判定，语义未变，仍在钉「两个宿主读取都走候选链」）。

```diff
@@ -183,6 +183,33 @@ class ReviewVcsReprobeTest {
+        // v97 fix: the host no longer *builds* the ref list. `reviewVcsCandidates`
+        // hands the rule the three inputs it needs and delegates, so the rule has
+        // exactly one owner (`ReviewVcsProbePlan`) and the two halves of it —
+        // which refs are still on the table, and may a failure retire them —
+        // cannot drift apart inside this 5400-line file.
         val candidates = viewModel.substringAfter("private fun reviewVcsCandidates(")
             .substringBefore("private fun rememberReviewVcsRef(")
-        assertTrue(candidates.contains("ReviewVcsReprobe.refs(dir)"))
-        assertTrue(candidates.contains("reviewVcsRefs[dir]"))
-        assertTrue(candidates.contains("reviewVcsProbed"))
+        assertTrue(candidates.contains("ReviewVcsProbePlan.candidates("))
+        // The remembered-ref map, keyed by the tracked session's own directory …
+        assertTrue(candidates.contains("remembered = directory?.let { reviewVcsRefs[it] }"))
+        // … and the permanent-probe latch, which may only narrow the list to the
+        // canonical ref (a transient one must not — `ReviewVcsProbePlanTest`).
+        assertTrue(candidates.contains("probed = directory != null && directory in reviewVcsProbed"))
+        // The delegation is the pin: the host must not re-inline the ref list
+        // behind the plan's back, which is the drift that broke this test.
+        assertFalse(candidates.contains("ReviewVcsReprobe.refs("))
+        // The fresh-graph source moved with the rule, so pin it where it now
+        // lives — and the guards, in precedence order. Reordering them would let
+        // a probed latch outrank a ref that already answered, and re-filtering
+        // the returned list would break the per-candidate walk.
+        val plan = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewVcsProbePlan.kt")
+        val planCandidates = plan.substringAfter("fun candidates(")
+            .substringBefore("fun shouldMarkProbed(")
+        val unscoped = "if (dir.isEmpty()) return listOf(null)"
+        val remembered = "remembered?.takeIf { it.isNotBlank() }?.let { return listOf(it) }"
+        val retired = "if (probed) return listOf(dir)"
+        val freshGraph = "return ReviewVcsReprobe.refs(dir)"
+        listOf(unscoped, remembered, retired, freshGraph).forEach { guard ->
+            assertTrue("missing candidate guard: $guard", planCandidates.contains(guard))
+        }
+        val precedence = listOf(unscoped, remembered, retired, freshGraph).map { planCandidates.indexOf(it) }
+        assertEquals(precedence.sorted(), precedence)
     }
```

净变化：断言 **3 条 → 10 条**（3 条正 pin + 1 条负 pin + 4 条 guard 存在性 + 1 条 guard 优先级 + 1 条等价物替换）。
无 `@Ignore`、无删除、无 `assertTrue` → 更弱检查的替换。

---

## 3. 为什么不算降低强度（逐条）

| # | 旧断言 | 新断言 | 强度对比 |
|---|---|---|---|
| 1 | `contains("ReviewVcsReprobe.refs(dir)")` @宿主 | `contains("return ReviewVcsReprobe.refs(dir)")` @`ReviewVcsProbePlan.candidates` **+** `assertFalse(host.contains("ReviewVcsReprobe.refs("))` | **更强**。旧版只要求「某处出现 refs(dir)」；新版同时钉住 (a) 唯一所有者是 `candidates()`，返回值原样透传（`return`，不是过滤/重排），(b) 宿主**不得**再自行内联候选构造。负向断言精确锁死本次漂移的形状。 |
| 2 | `contains("reviewVcsRefs[dir]")` | `contains("remembered = directory?.let { reviewVcsRefs[it] }")` | **更强**。旧版只看到一个 map 下标；新版钉住 ①参数名 `remembered` ②键来源是**被跟踪会话的** `directory`（不是别的变量）③整体绑定不被拆散。语义等价但覆盖面更大。 |
| 3 | `contains("reviewVcsProbed")` | `contains("probed = directory != null && directory in reviewVcsProbed")` | **显著更强**。旧版是裸符号名，文件里任何位置出现即通过；新版要求它是 `probed` 形参的值，且带 null 守卫。 |
| 4 | （无） | 4 条 guard 逐条存在性断言，带失败信息 `missing candidate guard: …` | **新增**。钉住「unscoped → remembered → probed → refs」四级形状的每一级。 |
| 5 | （无） | `assertEquals(precedence.sorted(), precedence)` | **新增，且是本次任务点名要求的「顺序语义」**。断言 4 个 guard 的源码位置严格递增。实测索引 `129, 176, 250, 289`。任何重排（例如把 `if (probed)` 提到 `remembered` 之前）都会让「已答过的 ref 被 probed 闩锁盖掉」这个回归无法静默通过。 |
| 6 | （无） | 176-182 行原样保留 | 未削弱。宿主两个读取点（`reviewVcsInfo` / `reviewVcsDiff`）仍必须走 `reviewVcsCandidates()`。 |

**去重语义**没有被新 pin 漏掉：`ReviewVcsReprobe.refs` 自身「去重 + 顺序」的语义由同文件的
`reProbeAddsAFreshGraphVariantOfTheSameDirectory`（`ReviewVcsReprobeTest:37-55`）和
`ReviewVcsProbePlanTest`（行为级）钉住；本次新增的 `return ReviewVcsReprobe.refs(dir)`
（`return` 而非 `refs(dir).map{...}` / `.distinct()` / `.sorted()`）保证**调用点不二次加工**，
两层合起来覆盖了「逐候选顺序 / 去重」的完整链路。

**没有为了让测试变绿而放宽任何东西**：旧断言所指向的语义（「宿主必须能走到
`ReviewVcsReprobe.refs` 这个候选来源」）被完整保留，只是跟着重构搬到了新文件，并额外加了
负向 + 优先级两层约束。

---

## 4. 测试证据

### 4.1 改前预验证（避免浪费一轮 gradle）

在改断言前，用 PowerShell 复刻旧 pin 的切片语义逐条跑了一遍，得到 §1.1 的真实失配矩阵
（185 False / **186 False** / 187 True）。改完后再复刻新断言，6 类检查全 True：

```
1 ReviewVcsProbePlan.candidates(     : True
2 remembered = ...reviewVcsRefs[it]  : True
3 probed = ...reviewVcsProbed        : True
4 NOT ReviewVcsReprobe.refs( in host : True
5 guards present (all >= 0)         : True
   indices                           : 129, 176, 250, 289
6 precedence ascending               : True
```

### 4.2 闸门命令（原文）

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ReviewVcsReprobeTest" --tests "com.xiufeigo.opencodeshell.ReviewVcsProbePlanTest" --tests "com.xiufeigo.opencodeshell.ReviewSearchV97FixTest" --tests "com.xiufeigo.opencodeshell.ReviewModelsTest" --tests "com.xiufeigo.opencodeshell.ReviewWave5Test" --tests "com.xiufeigo.opencodeshell.ChatStoreReviewEventTest" --tests "com.xiufeigo.opencodeshell.OpenCodeClientReviewTest" --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest"
```

### 4.3 结果（原文）

```
BUILD SUCCESSFUL in 3s
52 actionable tasks: 2 executed, 50 up-to-date
```

`GRADLE_EXIT=0`。`2 executed` = `:app:compileDebugUnitTestKotlin` + `:app:testDebugUnitTest`
（新改的测试文件确实被重新编译并重跑，不是 UP-TO-DATE 蒙过去的）。
原始完整输出留档：`research/v97_fix/_gradle_run.log`。

### 4.4 每类用例数（取自 `app/build/test-results/testDebugUnitTest/TEST-*.xml`）

| 测试类 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| ReviewVcsReprobeTest | 12 | 0 | 0 | 0 |
| ReviewVcsProbePlanTest | 8 | 0 | 0 | 0 |
| ReviewSearchV97FixTest | 12 | 0 | 0 | 0 |
| ReviewModelsTest | 12 | 0 | 0 | 0 |
| ReviewWave5Test | 30 | 0 | 0 | 0 |
| ChatStoreReviewEventTest | 5 | 0 | 0 | 0 |
| OpenCodeClientReviewTest | 4 | 0 | 0 | 0 |
| StringsLocaleParityTest | 3 | 0 | 0 | 0 |
| **合计** | **86** | **0** | **0** | **0** |

`ReviewVcsReprobeTest` 12 条全通过，含本次改写的 `theHostFetchChainWalksTheReProbeCandidates`。
未跑 assembleDebug / clean / 全量测试 / 装机，未改任何生产源码。
gradle 锁 `.agent-gradle-lock` 按协议：等待 → `Set-Content "fix-pin"` → 跑完 `rm` 释放（已确认 LOCK RELEASED）。

---

## 5. 同类漂移排查（全测试目录）

对 `app/src/test/java/com/xiufeigo/opencodeshell/` 全量 grep
`ReviewVcsReprobe|ReviewVcsProbePlan|reviewVcsCandidates|reviewVcsProbed|reviewVcsRefs|markReviewVcsProbed|forgetReviewVcsProbe|refs\(dir\)`，
命中文件仅 3 个 + 1 个旁证：

| 文件 | 是否漂移 | 判定依据 |
|---|---|---|
| `ReviewVcsReprobeTest.kt` | **是** | 185/186 已修（见 §2） |
| `ReviewVcsProbePlanTest.kt` | 否 | 行为级测试，直接调 `ReviewVcsProbePlan.candidates/shouldMarkProbed/failureOf` 新对象；引用的 `ReviewVcsReprobe.refs` 仍存在于 `ReviewVcsReprobe.kt`（未搬走），只 KDoc 提到旧名 `reviewVcsCandidates` 属叙述性描述，不断言源码形状 |
| `ReviewReprobeActionTest.kt` | 否 | 钉的是 `forgetReviewVcsProbe`，仍在 `ChatViewModel.kt:5515-5518`，`reviewVcsRefs.remove(dir)` / `reviewVcsProbed.remove(dir)` 两个字面量均原样存在 |
| `DirectoryPickerFreshProbeTest.kt` | 否 | grep `reviewVcs\|refs(\|Probed\|candidates` **零命中**，与本次重构无关 |
| `SessionContextPanelFreshnessTest.kt` | 否 | 仅注释里提 `reviewReprobeLocation` 路由名，非断言 |

**其余一律未动。** 与「全量闸门只有 1 个失败」的实测一致：除本条外无其它 pin 因该重构失效。

---

## 6. 未闭环 / 遗留

1. **简报的第 186 行判断被证伪**（见 §1.1）。若上游还有别的「已核对」结论建立在
   「186/187 仍匹配」之上，值得复查一次。本轮已按实测修掉。
2. **本次只跑了 8 类白名单测试**，未跑 `:app:testDebugUnitTest` 全量。全量仍可能存在
   其它非本次重构导致的历史失败（简报称改动前全量为 1 失败，即本条）。
3. **新断言对源文件字面量敏感**（守卫字符串、`ReviewVcsProbePlan.candidates(` 括号形态）。
   这是既有测试风格（`thePanelGatesTheCtaOnTheProbedVerdict` 同样钉字面量），换取的是
   纯文本 pin 能在 JVM 上跑、无需 Android runtime。若日后 `candidates` 内部改写为等价但不同
   字面量（例如用 `when` 替代早返回），这条 pin 会红——**那是设计取舍：宁可红也不要静默漂移**。
   `ReviewVcsProbePlanTest` 提供了行为级兜底，两层互补。
4. `research/v97_fix/_gradle_run.log` 为本轮 gradle 原始输出留档，如不需要可删。
5. 未做装机验证 / 手工 UI 回归（本任务纪律禁止 assembleDebug 与装机）。
