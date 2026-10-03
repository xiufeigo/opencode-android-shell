# GATE v98c — 全量闸门结果

- **执行时间**：2026-10-02 20:12:21 → 20:14:19（约 1m 58s）
- **结论**：**BUILD FAILED**（1 个测试失败，assembleDebug 未执行）
- **执行者模式**：只跑闸门、只报告；未修改任何源码 / 测试 / 构建文件

---

## 1. 命令原文

工作目录＝仓库根 `C:\sensorsdata\main\program\opencode-android-shell`

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --console=plain cleanTestDebugUnitTest testDebugUnitTest assembleDebug 2>&1 | Tee-Object -FilePath research\v97_verify\gate_v98c.log
```

完整日志：`research\v97_verify\gate_v98c.log`

---

## 2. Gradle 结尾 10 行（log 原文）

```
89 actionable tasks: 2 executed, 87 up-to-date
BUILD FAILED in 1m 44s
```

（`Tee-Object` 落盘的最后一段原文，尾部自此结束：）

```

* What went wrong:
Execution failed for task ':app:testDebugUnitTest'.
> There were failing tests. See the report at: file:///C:/sensorsdata/main/program/opencode-android-shell/app/build/reports/tests/testDebugUnitTest/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 1m 44s
89 actionable tasks: 2 executed, 87 up-to-date
```

Gradle 自身汇总行：`5685 tests completed, 1 failed`
失败任务：`:app:testDebugUnitTest FAILED`
`assembleDebug` 因前置测试失败**未执行**（APK 未重新产出，见第 4 节）。

---

## 3. XML 统计（`app\build\test-results\testDebugUnitTest\TEST-*.xml` 逐文件求和）

| 指标 | 本轮 (v98c) | 基线 (19:38) | 差值 | 判定 |
|---|---|---|---|---|
| suites（文件数） | **627** | 622 | **+5** | ✅ 只增不减 |
| tests | **5685** | 5615 | **+70** | ✅ 只增不减 |
| failures | **1** | 0 | **+1** | ❌ |
| errors | **0** | 0 | 0 | ✅ |
| skipped | **0** | 0 | 0 | ✅ |

suites +5 / tests +70，与「此后又落 4 组修复、每组带新测试类」的方向一致（类数与用例数确实增长）。
**但 4 组修复中至少有一组引入了 1 个失败断言**，闸门因此不通过。

---

## 4. APK 三元组

`app\build\outputs\apk\debug\app-debug.apk`

| 项 | 值 |
|---|---|
| bytes | **41,422,492** |
| LastWriteTime | **2026-10-02 19:38:26** |
| MD5 | **482C5D3EC3CDBE08D19886E5FD6EB58B** |

> ⚠️ **该 APK 是 19:38 基线那一轮的旧产物，不是本轮产物。**
> `LastWriteTime` 19:38:26 与本轮执行窗口（20:12–20:14）不符，可证 `assembleDebug` 从未运行
> ——`:app:testDebugUnitTest` 先失败，Gradle 短路后未继续到 `assembleDebug`。
> 因此 **本轮不存在有效 APK**，上表仅作残留物记录，不能当作 v98c 的构建产物。

---

## 5. 失败详情（原样，未修复）

- **suite**：`com.xiufeigo.opencodeshell.ReviewVcsReprobeTest`
- **test**：`theHostFetchChainWalksTheReProbeCandidates`
- **位置**：`app/src/test/java/com/xiufeigo/opencodeshell/ReviewVcsReprobeTest.kt:185`
- **异常**：`java.lang.AssertionError`（`org.junit.Assert.assertTrue`）
- **XML**：`app/build/test-results/testDebugUnitTest/TEST-com.xiufeigo.opencodeshell.ReviewVcsReprobeTest.xml`

失败断言（测试第 185 行）：

```kotlin
assertTrue(candidates.contains("ReviewVcsReprobe.refs(dir)"))
```

`candidates` 取自 `ChatViewModel.kt` 中
`"private fun reviewVcsCandidates(" … "private fun rememberReviewVcsRef("` 之间的源码切片。

### 只读定位结论（未做任何修改）

这是一个**源码 pin 断言过期**，不是运行时行为回归：

`ChatViewModel.kt:5429-5434` 现在把候选构造委派给了 `ReviewVcsProbePlan`：

```kotlin
private fun reviewVcsCandidates(): List<String?> =
    ReviewVcsProbePlan.candidates(
        directory = directory,
        remembered = directory?.let { reviewVcsRefs[it] },
        probed = directory != null && directory in reviewVcsProbed,
    )
```

- `ReviewVcsProbePlan.refs(dir)` / 候选枚举已从 `ChatViewModel` 内部**移出**到 `ReviewVcsProbePlan`；
- `reviewVcsRefs[dir]`、`reviewVcsProbed` 两个 pin 字符串在 `ChatViewModel.kt:5432-5433` 仍然存在（第 186、187 行断言可过）；
- 只有第 185 行的 `ReviewVcsReprobe.refs(dir)` 字面量 pin 失配。

即：`ReviewVcsProbePlan` 重构（SSH 重连保真那组修复）落地后，**该测试的源码 pin 未同步更新**。
按硬约束，**本轮不修**，只报告。

---

## 6. 闸门判定

**不通过（BLOCKED）**，两个独立原因：

1. `:app:testDebugUnitTest` 1 个断言失败 —— `ReviewVcsReprobeTest.theHostFetchChainWalksTheReProbeCandidates`（过期源码 pin）。
2. 因此 `assembleDebug` 未执行，本轮**无有效 APK 产出**。

后续建议（**未执行**）：修正 `ReviewVcsReprobeTest.kt:185` 的 pin，使其与 `ReviewVcsProbePlan.candidates(...)` 的实际委派结构一致，然后重跑同一闸门命令。

---

## 7. 硬约束遵守声明

- 本轮仅写入 `research\v97_verify\GATE_v98c.md` 与 `research\v97_verify\gate_v98c.log`。
- 未修改任何源码 / 测试 / 构建文件；未安装软件；未触碰 adb / 模拟器 / `_upstream/`。
- 除闸门本身外未运行其他 gradle 任务。
- 所有对 `ChatViewModel.kt` / `ReviewVcsReprobeTest.kt` 的访问均为只读。
