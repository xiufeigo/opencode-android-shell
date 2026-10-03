# BAR_ON_SCREEN_CRITERION — 600 档「队列条在屏」形态下判据 ① 的 `+2` 裁定（v135）

状态：**已裁定并落地（诊断 (b) 方向；生产修复不在本段授权内，已量化交接）** · 2026-10-04

## 0. 任务与边界

- 裁定对象：600 档「队列条在屏」形态下，判据 ①（`unionBottom ≤ paneBottom`）实测为 **`+2`**（`unionBottom 263 > paneBottom 261`）；条关闭形态为 **−12**。差 **14px**。
- 已知来源解释：队列条按设计吃掉时间线 14px（`ComposerQueuePanel.kt:70-95`，真机证据 `research/v97_verify/5554_md/v134/k600_MEASURE_BARON.png`）。
- Lead 立场：14px 的**来源**解释清楚 **不等于** `+2` 可接受；① 的定义是 `unionBottom ≤ paneBottom`（≤0），`+2` 是正值。
- 纪律：未使用「设计行为」代替算式；未放宽/删除任何既有断言；未造假绊线。

**一句话结论：这 2px 是真的越界，不是口径取错。`paneBottom` 在两形态下是同一个量（宿主盒逐位相同），Lead 口径 (a) 的前提「条在屏时 paneBottom 本该是另一个量」被真机证据直接否证。**

---

## 1. 2px 定性（行号 + 算式）

### 1.1 `paneBottom`（`261`）是什么 —— `文件:行号` + 原文

`paneBottom` 判据量的生产定义在 `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt:1333-1346`：

```
/**
 * v104 P0: 子节点并集的**底沿** px —— 真机复验的判据量。
 *
 * `top + min(union, host) <= top + host <= pane` 是**构造性恒等式**，
 * 因此「实测 `composerBottom <= paneBottom`」在**并集**这一维上恒成立，
 * 不再依赖宿主盒自己守规矩。
 */
fun composerChildUnionBottomPx(panePx: Int, hostPx: Int, unionPx: Int): Int = ...
```

`paneBottom` 是**宿主盒（可用区）的底沿**。真机上它由 a11y 帧里的宿主盒节点给出：两帧**逐位相同**的 `[0,162][1366,261] h=99`。

### 1.2 `unionBottom`（`263`）是什么

composer **子节点并集**的底沿 = 子节点 `y2` 的最大值。真机 `k600_MEASURE_BARON.xml`：

| 子节点 | bounds | y2 |
|---|---|---|
| `EditText` | `[24,214][405,262]` | **262** |
| `Add` 按钮 | `[399,215][441,263]` | 263 |
| 工具按钮组 | `[441,215][559,263]` | 263 |
| `Stop` 按钮 | `[1316,215][1364,263]` | **263** |

`unionBottom = max(262, 263) = 263`。

### 1.3 关键否证：`paneBottom` **不是**形态相关的量

两帧（`h600_BARON2.xml` 条关闭 / `k600_MEASURE_BARON.xml` 条在屏）的宿主盒：

```
条关闭： [0,162][1366,261]  h=99   paneBottom = 261
条在屏： [0,162][1366,261]  h=99   paneBottom = 261   ← 逐位相同，未移动
```

**队列条在屏时 pane 并没有长高。** 所以「条在屏时 `paneBottom` 本该取另一个量」在真机上**没有依据** —— 只有一个宿主盒，`261` 在两形态下都是对的。口径 (a) 的前提被否证。

### 1.4 算式：`+2` 的完整分解

密度 `wm density 160` ⇒ **1dp == 1px**（`ComposerPaneBottomCriterionV108Test.kt:48` 记录）。区域底部 padding：`SessionScreen.kt:1827` 原文

```kotlin
.padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 12.dp),
```

对应 `SessionPaneLayout.kt:1449` `val COMPOSER_REGION_PADDING: Dp = 12.dp`。

```
【条关闭形态】h600_BARON2.xml
  宿主盒 bottom            = 261
  editor [24,201][399,249] → 卡片 bottom = 249
  ① = 249 − 261 = −12      ← 恰好等于区域的 12 px 底部 padding，零越界
     249 = 261 − 12        ← 卡片贴在宿主盒底沿，下方是 12 px 呼吸区

【条在屏形态】k600_MEASURE_BARON.xml
  宿主盒 bottom            = 261   （与上完全相同）
  卡片 top  201 → 214      = +13   ← 队列条的下推量
  卡片 bottom 249 → 262    = +13   ← 整卡平移，不是长高
  区域 padding 吸收        = 12
  真实越界  262 − 261      = +1    ← editor 自身底沿越出宿主盒
  并集对齐  263 − 262      = +1    ← 工具行按钮比 EditText 低 1 px
  ① = 263 − 261 = +2 = 1 (真实越界) + 1 (并集对齐)
```

**结论：`+2 = 1px 真实越界 + 1px 并集对齐。** 那 1px 并集对齐就是判据 ② 已记录的同一处 1 px 错位（`EditText [214,262]` vs 按钮组 `[215,263]`，`unionPx 49` vs 基线 `48`）。

### 1.5 越界的形状：**位置性**，不是尺寸性

并集**高** `263 − 214 = 49`，宿主盒**高** `99` —— `49 ≤ 99`，尺寸上装得下。越界是**位置性**的：队列条把卡片顶沿放到 `214`（而非宿主盒顶沿 `162`），于是

```
214 + 49 = 263  >  261      ← 越界 2px
```

### 1.6 模型在这一维是「瞎」的 —— 假绿陷阱

`composerChildUnionHeightPx`（`SessionPaneLayout.kt:1330-1331`）用 `coerceAtMost` 把并集收进宿主：

```kotlin
fun composerChildUnionHeightPx(panePx: Int, hostPx: Int, unionPx: Int): Int =
    unionPx.coerceAtMost(composerHostHeightPx(panePx = panePx, ceilingPx = hostPx))
```

而 `composerTopPx(pane, host) = pane − composerHostHeightPx(...)`（`:1426-1427`）**完全不含队列条下推这一项**。⇒ 生产模型对「条在屏」形态**没有任何项**，其残差**结构上恒 ≤ 0**。

**因此：拿模型去「验证」这个 `+2`，必然得到一个证明不了任何事的绿。** 只有真机帧能判。已用 pin 钉死（§2）。

---

## 2. 裁定

### 2.1 裁定：**(b) 方向 —— 是真的越界**

- **不是口径错。** `paneBottom = 261` 在两形态下都对（宿主盒逐位相同），口径 (a)「显式定义另一个 paneBottom」**没有真机依据**，采纳它等于用改写定义的方式消掉一个真实读数 —— 本轮明确拒绝。
- **也不是 14px 的锅。** 14px 里有 12px 被区域底部 padding 正常吸收（`−12 → +2` 的算式见 §1.4），**超出吸收能力的是 1px**。
- 判据 ① 的定义**不变、强度不降**：`unionBottom ≤ paneBottom`（≤0）在本形态下**依然不成立**，本报告不宣布它成立。

### 2.2 为什么生产修复不在本段落地（不是回避，是授权与代价的实账）

要消掉这 1px，必须让队列条少占 1px，或让卡片不被压低。两个杠杆都不可用：

| 杠杆 | 位置 | 为什么不能动 |
|---|---|---|
| 队列条预算 `queueBarCeiling(...)` | 调用点 `SessionScreen.kt:1893` | **`SessionScreen.kt` 在本段禁改清单里**。改 `queueBarCeiling` 的公式会同时改变条的高度，直接冲击真机已验的 **「队列几何 48/48/全 clickable」**（整行 48px 触摸靶）与 768 档 `①=−10` PASS。 |
| 在 composer 内夹紧 bar+card | `composer/Composer.kt` | 允许改，但 1px 只能从**条的整行视口**（破坏 48/48 clickable）或**卡片 band**（破坏 `floorPx 48` 与 48px 控件靶）里出 —— **用一个已钉死的保证换另一个**，且本段**禁止装机/上真机**，无法验证它没有回归 768 的 `−10` 与 48/48。 |

**这正是本仓三轮踩过的假绿坑**（`COMPOSER_OVERFLOW_FIX4.md` §1.3）：换一个未经真机验证的布局改动来消掉一个读数，会拿 3 条已过的真机 PASS 换 1 条新的账面绿。**不划算，故不做。**

### 2.3 落地：把 `+2` 从「口径缺口」变成「已记账残差」（不改语义、不降强度）

新增 pin：`app/src/test/java/com/xiufeigo/opencodeshell/ComposerBarOnScreenCriterionV135Test.kt`（**7 用例，全绿**）。它做四件事：

1. **`theRegionBottomPaddingIsTwelveDpInBothProductionAndSource`** —— 从 `SessionScreen.kt:1827` **解析**区域 padding，并与 `SessionPaneLayout.COMPOSER_REGION_PADDING` 对账（12dp）。pin 咬生产源，不是自我复述。
2. **`thePaneHostBoxIsIdenticalInBothFormsSoPaneBottomIsNotFormDependent`** —— 钉死两形态宿主盒相同，从证据侧否证口径 (a)。
3. **`theClosedFormCriterionIsExactlyTheRegionsBottomPaddingAndNoOverflow`** —— 钉死关闭形态 `① = −12 = 区域 padding`，即「关闭形态本来就没越界」。
4. **`theBarOnFormResidualIsOnePixelOfRealExitAndOneOfAlignment`** —— 钉死 `+2 = 1 + 1` 的分解算式。
5. **`theModelCannotExpressTheResidualSoOnlyTheDeviceFrameCan`** —— 钉死模型的假绿陷阱：模型残差结构上恒 ≤0，**不能**用它验证本读数。
6. **`theQueueBarBudgetTermsArePinnedAtTheValuesTheAccountRestsOn`** —— 钉死修复将来要动的三个预算项（`QUEUE_ROW_HEIGHT 32` / `QUEUE_BAR_CHROME 18` / `QUEUE_BAR_MIN_HEIGHT 50`）。

**它冻结的是账，不是判决**：`+2` 在本形态下**仍然不满足判据 ①**，pin 里没有任何一处断言 `+2` 可接受。

### 2.4 交给 Lead / 用户的决策项（量化后移交）

| 项 | 需要的授权 | 代价 | 收益 |
|---|---|---|---|
| 修这 1px（真 (b)） | 放开 `SessionScreen.kt:1893` **或** 允许装机 | 需重测 48/48 clickable、768 `①=−10`、600 两形态 | 600 档条在屏 `①` 由 `+2` 归 `0` |
| 维持记账 | 无 | 0 | 残差已被算式钉死、可追 |

**已批准偏差同类**：与账上已有的 **96 残差**、`floorPx 48` 同一处置方式 —— 记账钉死，不静默放过。

---

## 3. 反向验证（注入 ⇒ 必须红）

注入：把生产常量 `SessionPaneLayout.kt:1449` 的 `COMPOSER_REGION_PADDING` 由 `12.dp` 改为 `8.dp`（模拟「区域 padding 被改小 ⇒ 吸收能力下降 ⇒ 残差变大」），跑同一测试类。

命令：

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ComposerBarOnScreenCriterionV135Test"
```

**结果原文（`_v135_reverse.log`）：**

```
ComposerBarOnScreenCriterionV135Test > theClosedFormCriterionIsExactlyTheRegionsBottomPaddingAndNoOverflow FAILED

ComposerBarOnScreenCriterionV135Test > theRegionBottomPaddingIsTwelveDpInBothProductionAndSource FAILED
    java.lang.AssertionError at ComposerBarOnScreenCriterionV135Test.kt:114

ComposerBarOnScreenCriterionV135Test > theBarOnFormResidualIsOnePixelOfRealExitAndOneOfAlignment FAILED
    java.lang.AssertionError at ComposerBarOnScreenCriterionV135Test.kt:195

7 tests completed, 3 failed

FAILURE: Build failed with an exception.
BUILD FAILED in 4s
52 actionable tasks: 4 executed, 48 up-to-date
```

**7 条中 3 条变红** —— pin 确实咬住生产量，不是自我复述的假绿。

**回滚核验**：已从 `research/v97_fix/_V135_backup_SessionPaneLayout.kt` 还原，
`md5 = C00A1E9CC72FE92DACF5730CC2D383BC`（与注入前一致），`git diff --stat` 对该文件**为空**。

---

## 4. 为什么不降强度

1. **判据 ① 的定义一字未改**：`unionBottom ≤ paneBottom`（≤0）。本形态下它**依然不成立**，本报告不宣布它成立。
2. **没有放宽也没有删除任何既有断言**；`ComposerPaneBottomCriterionV108Test` 等 6 条既有 pin 未被触碰。
3. **没有把 `+2` 改写成「可接受」**：新 pin 断言的是 `+2 = 1 + 1` 的**分解**，不是 `+2 ≤ 0`。
4. **没有用「设计行为」代替算式**：`−12 + 13 − 12 + 1 = +2` 全程给出数值与行号。
5. **没有采纳口径 (a)**：因为证据（宿主盒逐位相同）不支持它；改定义来消读数就是降强度。

---

## 5. 测试证据

命令（`JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`）：

```
.\gradlew :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ComposerBarOnScreenCriterionV135Test"
```

**原文（`_v135_final.log` 尾部）：**

```
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 3s
52 actionable tasks: 2 executed, 50 up-to-date
```

- **用例数：7**，全绿（`ComposerBarOnScreenCriterionV135Test`）。
- 中途一次真实红灯已记录在案：`_v135_run2.log` —— 我最初把 `coerceAtMost` 的语义写反（当成「并集高 = 宿主高」），`theModelCannotExpressTheResidualSoOnlyTheDeviceFrameCan` 在 `:234` 报 `AssertionError`，`7 tests completed, 1 failed`。据此改正为正确语义（并集高 49 ≤ 宿主高 99 ⇒ 越界是**位置性**的），并同步订正了类注释里的错误论断。**这处自曝保留在此，不抹。**
- 合规：只跑 `--tests "<类名>"`；未 `assembleDebug`、未 `clean`、未全量、未装机。

---

## 6. 未闭环

1. **这 1px 的生产修复未做** —— 授权外（`SessionScreen.kt:1893` 禁改）+ 无真机验证手段。已在 §2.4 量化移交。
2. **本 pin 钉的是真机读数常量，不是活的布局计算** —— 若 pane/IME/条行数变化，`+2` 的分解需重测（与既有 v108 体系的同一取舍：字面量是刻意的，模型不得自造「前值」）。
3. **768 档判据 ① 的 `+2`** 仍是独立的需用户决策项，本轮**未触碰**。
4. **600 档「条关闭」形态本轮未取新读数**（v134 队列有残留行、裁剪行进不了 a11y 树，UI 上无法清空到关闭态）—— 本报告的关闭形态数据取自 v134 已存档帧 `h600_BARON2.xml`，非本轮新测。
5. `SessionScreen.kt:2385-2389` 的自证循环、44 项 `/sdcard` 权限网关、4096 残留 PID 12668 等环境/授权项见 `HANDOFF.md` 收尾节，不在本报告范围。
