# CELL5_FIX — v146：按可判定判据修 ⑤ —— `Send` 全灭 `Open terminal`

- 执行：mavis（mcode），**v146**
- 日期：2026-10-04
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 承接：`research/v97_verify/5554_md/REPORT_BASELINE_AND_CELL5.md`（v145 取证）、`research/v97_fix/CELL5_FIX_AND_DECLARATIONS.md`（v144 行级归因 + A/B/C/D 裁定请求）
- 设备：`emulator-5554`（**本轮未装机、未 `assembleDebug`** ⇒ 像素级复验留账，见 §9）

> **锁约定**沿用 `PIN_DEVICE_HARDLINK.md:6-7`：仓库无独立 `*.lock`，**本报告文件即锁**
> （抢锁时刻 = 本文件首次写入）。Gradle 锁 `.agent-gradle-lock` 单独写、**前台**跑完全部三轮、
> 用绝对路径字面量释放：`Remove-Item` **再次**被本地硬安全策略拦截（输出逐字：
> `Permission denied for bash: Local hard safety policy blocked bash: Remove-Item (PowerShell delete; no Recycle Bin from CLI). …`），
> **未绕过**，改用单条顶层 `rm -- "C:\…\.agent-gradle-lock"`，运行时路由至受信任的可恢复删除启动器，
> 输出逐字：`mavis-trash: moved to trash: 'C:\sensorsdata\main\program\opencode-android-shell\.agent-gradle-lock'`。
> 释放后 `Test-Path` = **False**。

> **一句话结论**：⑤ 的三个症状**全部来自同一行** —— `SessionScreen.kt:3615` 的
> `SessionTerminalEntryPolicy.END_DP = 8.dp`。
> 把这一个常量改成 **64.dp**（只动**水平**锚点，垂直/时间线/靶高全部零改动），
> **6 条判据逐条成立**，48dp 靶语义、时间线账目、768 遮挡**均未触碰**；
> 22 类 / **156 例 / 0 失败**；反向验证 **7 条真红**。

---

## 0. 一页结论

| 问题 | 答案 |
|---|---|
| **z 序错在哪一行** | `SessionScreen.kt:3187`（viewport `Box` **先**合成）vs `:3188-3208`（composer host `Box` **后**合成）⇒ composer 在上。**不是同一 `Box` 内堆叠**，是两个平级 `Box` 的先后 |
| **2px 窄边在哪一行** | `SessionScreen.kt:3615` `END_DP = 8.dp` 与 composer 自身右内缩**互不派生** ⇒ `1366 − 1364 = 2`；且 chip 触摸盒被屏幕右缘裁成 **46** 宽 |
| **可见 0 从哪来** | z 序（谁在上面）× 几何（两盒共处一矩形）**叠加**：chip 的 28dp 本体整个落在 `Send` 本体之下 |
| **改了什么** | **1 个常量** `8.dp → 64.dp` + 2 处**纯注释**订正（含一句被真机证伪的 KDoc） |
| **6 条判据** | **全部成立**（③ 的像素级部分留设备，见 §9） |
| **反向验证** | **变红**：17 例中 **7 失败**（v146 5 + v144 1 + policy 1） |
| **断言被放宽/删除** | **0 条**。改 2 处**期望基线值** `8f → 64f`（见 §7.3） |
| **用例数** | 139（v144 19 类）→ **156**（22 类），**净增 17，0 减少** |
| **未闭环** | 6 项，见 §10 |

---

## 1. 行级归因（`文件:行号` + 原文 + 算式）

### 1.0 母帧读数（v145 §2.1/§2.6 逐字，`v140_600off.xml`）

| 节点 | dump `index` | 文档序 | bounds | w×h | clickable | enabled |
|---|---|---|---|---|---|---|
| `Open terminal` | `15` | `46` | `[1320,192][1366,240]` | **46**×48 | `true` | `true` |
| `Send` | `19` | `55` | `[1316,191][1364,239]` | 48×48 | `true` | **`false`** |

### 1.1 z 序来源：**两个兄弟 `Box` 的合成先后**

**`SessionScreen.kt:3185-3209`**（`SessionPaneArrangement` 的 `Layout` content）：

```kotlin
Layout(
    content = {
        Box(Modifier.fillMaxSize().onSizeChanged(onViewportSize)) { viewport() }   // ← :3187 先合成
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier.fillMaxWidth()...                                    // ← :3188-3205 后合成
        ) {
            composer()                                                             // ← :3207
        }
    },
```

- chip 挂载点 **`SessionScreen.kt:2254-2264`**：

  ```kotlin
  if (desktop && onToggleTerminalDock != null) {
      TerminalEntryButton(
          onClick = onToggleTerminalDock,
          active = terminalDocked,
          modifier = Modifier
              .align(Alignment.TopEnd)                                             // :2259
              .padding(top = SessionTerminalEntryPolicy.TOP_DP,                   // :2261
                       end  = SessionTerminalEntryPolicy.END_DP),                 // :2262
      )
  }
  ```

- ⇒ **chip 是 `viewport()` 的孩子**（第一个 `Box` 内）；**`Send` 是 `composer()` 的孩子**（第二个 `Box` 内）。
- **不是同一 `Box` 内的兄弟堆叠，而是两个平级 `Box` 的先后**。Compose 里**后合成的兄弟绘制在上**，
  `uiautomator` 按 view 顺序输出 ⇒ `Send` 在上、吃点击。两条独立口径同向：**`index` 19 > 15**、**文档序 55 > 46**。
- **附带**：同函数 KDoc `:3166-3173` 曾把一句**被真机证伪**的前提写成事实 ——
  "The two **never share a pixel**"。v146 已订正该注释（**只改注释**）。

### 1.2 2px 残余窄边：**`END_DP` 与 composer 自身右内缩互不派生**（+ 右缘裁掉 2）

**`SessionScreen.kt:3611-3616`**（`SessionTerminalEntryPolicy` 原文）：

```kotlin
internal object SessionTerminalEntryPolicy {

    /** Below the 0–80 dp session header band (48 dp bar + sticky row). */
    val TOP_DP: Dp = 88.dp          // ← :3612

    /** Inset from the timeline pane's trailing edge. */
    val END_DP: Dp = 8.dp           // ← :3615   ★ ⑤ 的单一起源
}
```

**几何复原（与实测逐位吻合 ⇒ 模型不是编的）**：chip 视觉体 28dp，被 material3
`minimumInteractiveComponentSize(48)` 向两侧各扩 10；`padding(end = END_DP)` 落在**视觉体**外侧：

```
bodyRight = paneRight(1366) − END_DP(8)  = 1358
bodyLeft  = 1358 − 28                    = 1330
touchBox  = [1330−10 , 1358+10]          = [1320, 1368] → 被屏幕右缘裁到 1366
实测                                       = [1320,1366] = 46 宽      ✔ 逐位吻合
```

**残余分解**：

| 残余碎片 | 算式 | 值 | 窄边 |
|---|---|---|---|
| 右侧竖条 | `terminal.right − Send.right` = `1366 − 1364` | `2 × 48` | **2** |
| 下侧横条 | `terminal.bottom − Send.bottom` = `240 − 239` | `44 × 1` | 1 |
| 合计 | `2208 − 2068` | **140 px²** | — |

⇒ **2px = `END_DP(8)` 推出的 chip 右沿(1366) 与 composer 自己内缩推出的 `Send` 右沿(1364) 之差。**
两个锚点**谁也不是从谁导出**，所以必然分歧。

### 1.3 chip 可见像素 = 0：**z 序 × 几何 叠加**的结果

- chip 的 28dp 本体落在 `x 1326..1353`（v145 §2.4 像素扫描 `GREY 176/233`），中心 `(1339.5,215)`；
  `Send` a11y 矩形中心 `(1340,215)` —— **吻合到 0.5px** ⇒ 屏上那块灰是 **`Send`**，chip 本体在它**下面**。
- chip 唯一落在自身矩形内、又在 `Send` 矩形外的区域是 `x 1354..1365`，
  v145 实测 `y=205`/`y=215` **两行皆纯白 `R255`** ⇒ **可见 0**。
- **归因行**：z 序（§1.1）决定"谁在上面"、`END_DP`（§1.2）决定"两 48dp 盒是否共处一矩形"。
  **两者叠加**才产生"全灭"；缺任一条都不至于全灭（只改 z 序 → 缺陷转交给 `Send`，见 v144 §1.4）。
- **加重项**：盖住主入口的 `Send` 是 **`enabled="false"`** ⇒ **一个禁用的按钮压死了另一个可用按钮**。

---

## 2. 最小修

### 2.1 生产 diff（**1 个常量 + 2 处纯注释**）

`git diff --stat -- app/src/main` ⇒ `1 file changed, 37 insertions(+), 8 deletions(-)`，
**其中行为改动只有 1 行**：

```diff
--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt
@@ -3611,8 +3618,30 @@ internal object SessionTerminalEntryPolicy {
     /** Below the 0–80 dp session header band (see the KDoc). */
     val TOP_DP: Dp = 88.dp
 
-    /** Inset from the timeline pane's trailing edge. */
-    val END_DP: Dp = 8.dp
+    /** …v146 的完整推导… */
+    val END_DP: Dp = 64.dp
 }
```

第二处改动：`:3166-3173` 那句 "The two never share a pixel" 改成与实测一致的表述
（列出 `2068 px²` / `2 px` / `0 可见像素` / `enabled="false"`，并说明改由**水平**锚点分离）。
**注释改动，行为零变化**（`compileDebugKotlin` 前后一致）。

### 2.2 修后几何（同一模型，逐位算）

```
END_DP = 64
bodyRight = 1366 − 64 = 1302
bodyLeft  = 1302 − 28 = 1274
touchBox  = [1264, 1312]      ← 48 宽（**不再被右缘裁剪**）
Send      = [1316, 191][1364, 239]
intersection = [max(1264,1316), max(192,191)][min(1312,1364), min(240,239)]
            = [1316,192][1312,239] → 宽 = 1312 − 1316 = **−4 ≤ 0** ⇒ **不相交**
overlapArea = 0
横向余隙    = 1316 − 1312 = 4 px
```

**为什么是 64**：需要 `touchRight = paneRight + 12 − END_DP ≤ Send.left(1316)`
⇒ `END_DP ≥ 60`。**60 是精确最小值**（余隙 0，等号不算分离），**64 留 4px 余隙**，
是最小安全值；56 仍会交叠 4px。

### 2.3 三条红线逐条**未破**

| 红线 | 为什么这次**不碰** |
|---|---|
| **48dp 靶语义** | 靶**高** = 视觉体 28 + 上下各 10 = 48，来自 `minimumInteractiveComponentSize`，与 `END_DP` 无关。改的是**水平**内缩。`TOP_DP`(:3612) 一个字节未动。 |
| **时间线账目** | `140 / 92 / 51 / −10 / −12` 全是**竖向量**（pane 盒高、band、并集底沿）。`END_DP` 只改 **x**，不进入任何竖直预算 ⇒ 账目**逐位不变**（12 个 composer 基线类 108 例全绿间接守住，§7.2）。 |
| **768 档 0 遮挡** | 768 `Send = [1308,292][1356,340]`，chip `bottom=240 < 292` ⇒ **纵向分离**。移位后 chip = `[1264,192][1312,240]`，与 `Send` 的 x 跨度 `[1308,1356]` 横向**确有 4px 相交**，但**纵向仍不相交** ⇒ `overlapArea` 仍为 **0**（已钉成断言 ⑤）。 |

### 2.4 为什么不是别的旋钮

| 候选 | 否决理由 |
|---|---|
| 改 z 序（chip 置顶） | v144 §1.4 已证：缺陷转交给 `Send`（窄边 2→**4**px，更糟）。 |
| 改 `TOP_DP`（垂直） | 是 **④ 的 2px 要用的同一个旋钮**，且改的是**靶高/竖直位置** ⇒ 撞红线。 |
| 缩小任一靶 | 直接删 48dp 靶语义 ⇒ **最严重的放宽**，明令禁止。 |
| 一行形态取消 chip | 属产品裁定（v144 选项 C），不属工程最小修。 |

**本次只动水平锚点这一个独立旋钮** —— 它**既不是 ④ 的旋钮、也不进竖直账目**，
是 v144 §1.5 表里三个"有效但越权"项中**唯一不撞红线**的一项；
v146 的授权把 **chip 摆位**纳入可写范围，阻碍解除。

---

## 3. 补的 pin：6 条判据**逐条**钉住

新增 `app/src/test/java/com/xiufeigo/opencodeshell/UsabilityCellsV146SendOcclusionFixTest.kt`（**8 例**）。

**关键：pin 调的是"生产常量 + 几何模型函数"，不是两侧全字面量。**
`chipTouchBox()` 用 **live** 的 `SessionTerminalEntryPolicy.END_DP`、
`SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX(48)`、`SessionPaneLayout.CONTROL_FLOOR_PX(28)`
重算 chip 触摸盒，再与归档真机帧里的 `Send` 相交 ⇒ **判据每跑一次就被重新求值一次**。

| # | 判据 | 测试方法 | 修后实测（模型 × 真机帧） | 成立 |
|---|---|---|---|---|
| ① | `overlapArea == 0` | `criterion1OverlapAreaIsZero` | `0`（chip `[1264,192][1312,240]` × `Send` `[1316,191][1364,239]`，宽 `−4`） | ✅ |
| ② | `narrowestResidualPx ≥ 16` | `criterion2NarrowestResidualIsFingerSized` | `48`（无遮挡 ⇒ 整盒存活，`min(48,48)`）；且 chip 恢复 **48** 宽（v145 为 46） | ✅ |
| ③ | `chipVisiblePixelCount > 0` | `criterion3TheChipHasVisiblePixels` | 帧内可判定部分 = **2304**（无任何后绘 clickable 靶覆盖）> 0；**像素级部分留设备**（§9） | ✅\* |
| ④ | **两靶仍各 48 高** | `criterion4BothTargetsStay48DpTall` | chip `48`（由 live `MIN_INTERACTIVE_COMPONENT_PX` 推出）= `Send` 实测 `48`；`TOP_DP` 仍 `88` | ✅ |
| ⑤ | **768 档仍 0 遮挡** | `criterion5TheStackedFormStillHasZeroOverlap` | `0`（纵向分离：chip `bottom=240` < `Send` `top=292`） | ✅ |
| ⑥ | **两者 `clickable == true`** | `criterion6BothTargetsRemainClickable` | 600 与 768 两帧均 `true` / `true` | ✅ |

**另 2 例（非 6 条判据，但把"为什么这样修"钉死）**：

- `theGeometryModelReproducesTheMeasuredPreFixBoxAndPinsV145Readings`
  —— **先自证模型**：用**修复前**的 `8 dp` 驱动同一模型，必须**逐位复现**真机实测的
  `[1320,192][1366,240]`（**46 宽**）。模型复现得出来，才有资格用同一个模型断言修后几何。
- `theFixTouchesOnlyTheHorizontalAnchorAndItIsReadLive`
  —— 断言 `END_DP` **live** = 64、`TIMELINE_MIN_CONTENT` live = 64、
  `SessionScreen.kt:2262` 仍**实际使用**该常量（防止"声明了但没人读"），
  且 **`Composer.kt` 不得引用 `END_DP`**（保持两个锚点**独立**——
  正是这种不独立造成了 ⑤；若将来互相派生，缺陷就会以耦合形式重新塌陷）。

### 3.1 v145 真机读数（**2068 / 2 / 0**）已钉成回归触发器

同一个 `theGeometryModelReproduces…` 测试里逐条钉住，**任一改动即红**：

| 触发器 | 断言 | 值 | 触发含义 |
|---|---|---|---|
| **2068** | `measured.overlaps(send)` | `44 × 47` | 归档帧的遮挡面积变了 ⇒ 归因链要重推 |
| **2** | `measured.narrowest(send)` | `2` | 残余碎片**最窄**尺寸变了 |
| **2** | `measured.right − send.right` | `2` | 右窄边宽度变了 |
| 碎片形状 | `measured.minus(send)` | `[(2,48), (44,1)]` | 碎片**形状**变了 |
| 完备性 | `Σ(碎片) = area − covered` | `140` | 分解不再自洽 |
| **0** | 帧内未被后绘 clickable 靶覆盖的面积 = **140** px²，v145 实测那 140 px² **全是背景**（`x 1364..1366`、`y 239..240` 在 `y=205/215` 两行皆 `R255`）⇒ **140 px² 几何 / 0 px 芯片** | — | 可见性口径变了 |
| 禁用态 | `F(B_CELL5_600_ONEROW.xml).send.enabled` | `false` | "禁用按钮压死可用按钮"的严重性被下调 |

> **一处如实纠错**：本轮我第一版把"可见 0"写成"帧内可推导"，**跑出来是红的**
> （`expected:<0> but was:<140>`）—— 因为**几何残余 140 ≠ 像素 0**：
> 帧只能说"有 140 px² 没被 `Send` 盖住"，**说不出那里画的是什么**。
> 已改为**双口径分列**：几何 140（可判定）+ 像素 0（设备证据），不再拿 bounds 冒充像素。
> 另一处纠错：`enabled=false` 出自 v145 那一帧（composer 为空），
> `v140_600off.xml`（编辑器有字）里 `Send` 是 `enabled=true` ⇒ 触发器改钉 v145 的帧，两帧几何逐位相同。

---

## 4. 反向验证：撤回修法 ⇒ pin **真红**（原文）

**注入**（用编辑工具把常量改回，**未用 `git checkout` / `git restore`**）：

```diff
-    val END_DP: Dp = 64.dp
+    val END_DP: Dp = 8.dp
```

**命令**（3 类：v146 + v144 live 锚 + policy）：

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --tests "…UsabilityCellsV146SendOcclusionFixTest" `
  --tests "…SessionTerminalEntryPolicyTest" --tests "…UsabilityCellsV144FixTest"
```

**结果**（`research/v97_fix/_v146_reverse.log`）：

```
> Task :app:testDebugUnitTest FAILED

UsabilityCellsV146SendOcclusionFixTest > criterion1OverlapAreaIsZero FAILED
    java.lang.AssertionError at UsabilityCellsV146SendOcclusionFixTest.kt:196
UsabilityCellsV146SendOcclusionFixTest > theGeometryModelReproducesTheMeasuredPreFixBoxAndPinsV145Readings FAILED
    java.lang.AssertionError at UsabilityCellsV146SendOcclusionFixTest.kt:477
UsabilityCellsV146SendOcclusionFixTest > criterion2NarrowestResidualIsFingerSized FAILED
    java.lang.AssertionError at UsabilityCellsV146SendOcclusionFixTest.kt:222
UsabilityCellsV146SendOcclusionFixTest > criterion3TheChipHasVisiblePixels FAILED
    java.lang.AssertionError at UsabilityCellsV146SendOcclusionFixTest.kt:261

17 tests completed, 7 failed

BUILD FAILED in 5s
52 actionable tasks: 4 executed, 48 up-to-date
```

**7 条失败逐条（从结果 XML 读出，逐字）**：

| 类 | 用例 | 消息 |
|---|---|---|
| `UsabilityCellsV146SendOcclusionFixTest` | `criterion1OverlapAreaIsZero` | `…the chip rebuilt from the live anchor \`SessionTerminalEntryPolicy.END_DP = 8.0 dp\` is P(…, left=1320, top=192, right=1366, bottom=240, index=46), and \`Send\`…` |
| 同上 | `criterion2NarrowestResidualIsFingerSized` | `…the narrow dimension is \`min(w, h)\` = 46 px. v145 measured **2** px …` |
| 同上 | `criterion3TheChipHasVisiblePixels` | `…(2208 px2) and the later-drawn clickable targets covering it are…` |
| 同上 | `theFixTouchesOnlyTheHorizontalAnchorAndItIsReadLive` | `…64 dp is the smallest inset that separates the two 48 dp boxes by geometry…` |
| 同上 | `theGeometryModelReproduces…` | `…puts the chip at P(…, left=1320, top=192, right=1366, bottom=240) — no overlap with a \`Send\` at [1316,191][1364,239], against the 2068…` |
| `SessionTerminalEntryPolicyTest` | `theChipKeepsItsTrailingInset` | `java.lang.AssertionError: expected:<64.0> but was:<8.0>` |
| `UsabilityCellsV144FixTest` | `theProductionAnchorsBehindCells2Through5AreReadLiveAndAreWhatTheReportQuotes` | `v144: \`SessionTerminalEntryPolicy.END_DP\` (\`SessionScreen.kt:3615\`) is the chip's horizontal anchor…` |

⇒ **pin 硬接在生产常量与几何模型上**：常量一动，**6 条判据里 4 条 + 两条 live 锚同时变红**。
撤回后逐字改回 `64.dp`（编辑工具，非 git），再跑 §7.2 的 22 类回归 ⇒ 全绿。

---

## 5. 为什么不降强度

| 维度 | v144 | **v146** | 方向 |
|---|---|---|---|
| 用例数 | 139（19 类） | **156**（22 类） | ↑ **+17** |
| **被放宽的断言** | 0 | **0 条** | = |
| **被删除的断言** | 0 | **0 条** | = |
| 改动的既有断言 | — | **2 处期望基线值** `8f → 64f`（§7.3） | 仅基线随修法更新，**强度未降** |
| ⑤ 的定性 | 真缺陷 + 修法无解性已证 | **真缺陷已修** + 6 条判据逐条可判定 + 触发器钉死 | ↑ |
| 判据的可判定性 | 散文 + 阈值函数 | **每跑一次由 live 常量重算** | ↑ |
| 新排除的假设 | 「只改 z 序能修 ⑤」被证伪 | 「**只改 z 序不够、必须靠几何分离**」被**正面实现并钉住** | ↑ |
| 生产代码 | 零净改动 | **1 常量 + 2 注释**（靶高/账目/768 零改动） | ↑ |
| 「可见 0」的处理 | v145 读到 | v146 **双口径分列**（几何 140 / 像素 0），不再拿 bounds 冒充像素 | ↑ |

**自曝：本轮真出过 2 次红，全部是我自己新写的测试的 bug，已逐条记账**

| # | 轮次 | 失败 | 是什么 | 处理 |
|---|---|---|---|---|
| 1 | **编译红**（0 例执行） | `compileDebugUnitTestKotlin FAILED` · `No value passed for parameter 'index'` | 给 `chipTouchBox()` 加 `index` 形参后，修复前那处调用漏传 | 补 `index = measured.index` |
| 2 | **断言红**（2 条） | `criterion3TheChipHasVisiblePixels`、`theGeometryModelReproduces…(TRIGGER 0)` | ① `criterion3` 把**全屏背景 `View`** 当成"盖住 chip"（重建 chip 的 `index` 曾写成 0 ⇒ 全树都在它之上）；② **拿 bounds 推出像素数**：帧内几何残余 **140** ≠ 像素 **0** | ① 只认**后绘 clickable 靶**并修正 `index` ② 拆成几何 140（可判定）+ 像素 0（设备证据） |
| 3 | **断言红**（1 条） | `theGeometryModelReproduces…`（`TRIGGER 0` 的 `enabled`） | `enabled=false` 出自 v145 那一帧；`v140_600off.xml`（编辑器有字）里 `Send` 是 `enabled=true` | 触发器改钉 `B_CELL5_600_ONEROW.xml`，并在消息里说明两帧差异 |

> 这 3 次红都被**自己新写的 pin** 当场抓住，没有一条流到报告里。
> 其中第 2 轮尤其重要：它抓到的是**度量口径错误**（几何 ≠ 像素），
> 正是 v145 特意强调"两个口径不冲突、但必须分别断言"的那条纪律。

---

## 6. 改动文件清单

| 文件 | 性质 | 说明 |
|---|---|---|
| `app/src/main/…/ui/session/SessionScreen.kt` | **生产 · 行为改动 1 行** | `END_DP: 8.dp → 64.dp`（:3615） |
| 同上 `:3166-3173` / `:3621-3641` | **生产 · 纯注释** | 订正被证伪的 "never share a pixel"；新增 v146 推导 KDoc |
| `app/src/test/…/UsabilityCellsV146SendOcclusionFixTest.kt` | **测试 · 新增** | **8 例**（6 条判据 + 2 条元 pin） |
| `app/src/test/…/SessionTerminalEntryPolicyTest.kt` | **测试 · 改 1 处期望值** | `theChipKeepsItsTrailingInset` 期望 `8f → 64f`（**未删、未放宽**） |
| `app/src/test/…/UsabilityCellsV144FixTest.kt` | **测试 · 改 1 处期望值** | `theProductionAnchors…` 的 `END_DP` 期望 `8f → 64f`（其余 **6 例一字节未动**） |
| `research/v97_fix/CELL5_FIX.md` | 报告 | 本文件（先写盘抢锁 → 定稿释放） |
| `research/v97_fix/_v146_cells.log` / `_v146_reverse.log` / `_v146_regression.log` | 日志 | 新类绿 / 反向验证红 / 22 类回归绿 |

**未触碰**：`_upstream/**`（只读）、`app/build.gradle.kts`、`ChatViewModel.kt`、
`ui/servers|home|terminal|nav/**`、`ui/App.kt`、`OcActivity.kt`、
v142/v143 的 5 个既有测试类（**一个字节未动**）、`quarantine/`（**分工不变**）。

---

## 7. 测试证据

### 7.1 新类（8 例）

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.UsabilityCellsV146SendOcclusionFixTest"
```

```
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 3s
52 actionable tasks: 2 executed, 50 up-to-date
```

`tests=8 failures=0 errors=0 skipped=0`（**非跳过**）：

```
criterion1OverlapAreaIsZero
criterion2NarrowestResidualIsFingerSized
criterion3TheChipHasVisiblePixels
criterion4BothTargetsStay48DpTall
criterion5TheStackedFormStillHasZeroOverlap
criterion6BothTargetsRemainClickable
theFixTouchesOnlyTheHorizontalAnchorAndItIsReadLive
theGeometryModelReproducesTheMeasuredPreFixBoxAndPinsV145Readings
```

### 7.2 回归：受影响全部类（**22 个类 / 156 例 / 0 失败**）

命令（v144 的 19 类 + 新增 2 类：`SessionPaneArrangementV76Test`、`SessionTerminalEntryPolicyTest`）：

```
.\gradlew.bat :app:testDebugUnitTest --tests "…UsabilityCellsV146SendOcclusionFixTest" `
  --tests "…UsabilityCellsV144FixTest" --tests "…UsabilityCellsRulingV143Test" `
  --tests "…SessionTerminalEntryPolicyTest" --tests "…SessionPaneArrangementV76Test" `
  --tests "…ComposerBandA11yReachabilityTest" --tests "…ComposerBandDeviceTruthTest" `
  --tests "…ComposerBandUnionV136Test" --tests "…ComposerBandTallEnoughV106Test" `
  --tests "…ComposerBandTouchTargetV105Test" --tests "…ComposerBarOnScreenCriterionV135Test" `
  --tests "…ComposerPaneBottomCriterionV108Test" --tests "…ComposerUnifiedCriterionV129Test" `
  --tests "…ComposerChildUnionV104Test" --tests "…ComposerUnionBottomP5V109Test" `
  --tests "…ComposerPositionDecompositionV107Test" --tests "…ComposerCriterionParityV118Test" `
  --tests "…ComposerOverflowV103Test" --tests "…ComposerOneRowChipReserveV81Test" `
  --tests "…ComposerOneRowEditorFloorV86Test" --tests "…ComposerDivergenceLocateV119Test" `
  --tests "…ComposerDivergenceLocate2V120Test"
```

```
> Task :app:compileDebugKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 47s
52 actionable tasks: 4 executed, 48 up-to-date
```

| 类 | 用例 | 失败 | 错误 |
|---|---|---|---|
| **`UsabilityCellsV146SendOcclusionFixTest`（本轮新增）** | **8** | 0 | 0 |
| `UsabilityCellsV144FixTest` | 7 | 0 | 0 |
| `UsabilityCellsRulingV143Test` | 5 | 0 | 0 |
| `SessionTerminalEntryPolicyTest` | 2 | 0 | 0 |
| `SessionPaneArrangementV76Test` | 7 | 0 | 0 |
| `ComposerBandDeviceTruthTest` | 11 | 0 | 0 |
| `ComposerCriterionParityV118Test` | 12 | 0 | 0 |
| `ComposerUnionBottomP5V109Test` | 11 | 0 | 0 |
| `ComposerOverflowV103Test` | 9 | 0 | 0 |
| `ComposerPaneBottomCriterionV108Test` | 9 | 0 | 0 |
| `ComposerBandUnionV136Test` | 8 | 0 | 0 |
| `ComposerBandTallEnoughV106Test` | 7 | 0 | 0 |
| `ComposerBarOnScreenCriterionV135Test` | 7 | 0 | 0 |
| `ComposerChildUnionV104Test` | 7 | 0 | 0 |
| `ComposerOneRowEditorFloorV86Test` | 8 | 0 | 0 |
| `ComposerDivergenceLocateV119Test` | 8 | 0 | 0 |
| `ComposerBandA11yReachabilityTest` | 5 | 0 | 0 |
| `ComposerBandTouchTargetV105Test` | 6 | 0 | 0 |
| `ComposerUnifiedCriterionV129Test` | 6 | 0 | 0 |
| `ComposerDivergenceLocate2V120Test` | 6 | 0 | 0 |
| `ComposerPositionDecompositionV107Test` | 4 | 0 | 0 |
| `ComposerOneRowChipReserveV81Test` | 3 | 0 | 0 |
| **合计** | **156**（v144 为 139，**净增 17，0 减少**） | **0** | **0** |

> **基线不回退**：`140 / 92 / 51 / −10 / −12` 由上表 **12 个 composer 基线类**守住
> （其中 `ComposerBandDeviceTruthTest` / `ComposerUnionBottomP5V109Test` / `ComposerCriterionParityV118Test`
> 即 v142/v143 记这些数的类），全绿。
> **`quarantine/` 分工不变**（沿用 v142/v143/v144：④ 的 2px parity 仍欠，quarantine 保持红是对的）。

### 7.3 既有断言改动的诚实记账

| 类 · 用例 | 改动 | 为什么不是"放宽" |
|---|---|---|
| `SessionTerminalEntryPolicyTest.theChipKeepsItsTrailingInset` | 期望 `8f → 64f` | 断言**本身**（`assertEquals(64f, END_DP.value, 0.001f)`）一字未动，**仍读 live 生产常量**；变的只是**被钉住的基线值**，因为这次修复的**就是**这个基线。删掉它反而是放宽 |
| `UsabilityCellsV144FixTest.theProductionAnchors…` | 期望 `8f → 64f` | 同上；消息里写明"v146 把该锚点从 8 移到 64，由 v146 测试重新裁定" |

**其余 139 − 2 = 137 例既有断言与 5 个既有测试类一字节未动**；
**未放宽 0 条、未删除 0 条**。

---

## 8. 真机验收步骤（**严格照 6 条判据 + 基线不回退**）

> **本轮未授权装机**，故以下为**待执行**的验收清单，不是已完成的验收。
> 装机后逐条抄数；**任一条不成立即 FAIL，不得记 PASS**。

### 8.1 取证

```powershell
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # 与前轮一致
# 一行形态（600 档）
$adb -s emulator-5554 shell wm size 1366x600; $adb -s emulator-5554 shell wm density 160
$adb -s emulator-5554 shell uiautomator dump /sdcard/c5_600.xml
$adb -s emulator-5554 shell screencap -p /sdcard/c5_600.png
```

### 8.2 逐条判据（600 一行形态）

| # | 判据 | 期望 | 怎么判 |
|---|---|---|---|
| ① | `overlapArea == 0` | **0** | 两矩形**不相交**；`Send.left ≥ chip.right` |
| ② | `narrowestResidualPx ≥ 16` | **≥ 16** | 无遮挡时 = `min(w,h)` = 48；**且 chip 宽恢复 48（不再是 46）** |
| ③ | `chipVisiblePixelCount > 0` | **> 0** | **对原始截图逐列扫描**（v145 §2.4 口径）：chip 矩形内**既非背景白、也非 `Send` 本体灰**的像素数 > 0 |
| ④ | **两靶仍各 48 高** | **48 / 48** | **红线**；`term.h == send.h == 48` |
| ⑤ | **768 档仍 0 遮挡** | **0** | 切 `1366x768` 重 dump，重复 ① |
| ⑥ | 两者 `clickable == true` | **`true` / `true`** | 两个节点的 `clickable` 属性 |

### 8.3 基线不回退（两档都要抄）

| 基线 | 期望 | 档位 |
|---|---|---|
| 时间线盒 | **140** | 768 |
| `readable` | **92** | 768 |
| `readable` | **51** | 600 |
| ① (i) `unionBottom − paneBottom` | **−10** | 768 |
| ① (i) | **−12** | 600 |
| `Open terminal` y 跨度 | **`192..240`**（**必须不变**） | 两档 |
| `Send` 盒 | **不变**（`[1316,191][1364,239]` @600 / `[1308,292][1356,340]` @768） | 两档 |
| `logcat --pid` 扫 `FATAL\|IllegalArgumentException\|maxHeight` | **0** | 缩窗往返 2 轮 |
| `appPid` 往返前后 | **不变**（未触发重启） | 缩窗往返 2 轮 |

### 8.4 交互复验（v143 起一直欠，本轮**仍未做**）

1. `adb tap` chip 中心 ⇒ **确认真的开终端**（现在点得到、看得见了）。
2. `adb tap` `Send` 中心 ⇒ **确认没有被 chip 抢走**（防"z 序翻转"式假修）。
3. 空 composer 时 tap chip ⇒ 确认 **`Send` 的 `enabled=false` 不再压死它**（v145 §6-2 欠项）。
4. **视觉复核（本次修复的新风险）**：chip 移位后落在 `x 1264..1312`，
   **需确认它没有压住消息正文的关键内容**（6 条判据**不覆盖**这一项，见 §10-1）。
5. **docked review panel 形态复核**：chip 仍挂在 timeline pane 内，
   `end = 64` 要求 pane 宽 `≥ 64 + 48 = 112 dp`；需在 docked 形态下确认未越出 pane 左缘
   （v43 的不变量本轮**未在真机复验**）。

### 8.5 收尾

`wm size` / `wm density` **显式**重设回 `1366x768` / `160` 并复查；
清理本轮 `/sdcard` 新增（`c5_600.*` / `c5_768.*`）；**他轮产物一律留账不动**。

---

## 9. 未闭环（如实记账）

1. **判据 ③ 的像素级部分未在真机闭环** —— 本轮 pin 只能给出**几何**结论
   （无遮挡 ⇒ 2304 px² 全部可达），**"屏上真的看得见"是设备步骤**（§8.2 ③）。
   **装机授权到位后才能结。**
2. **chip 移位后是否压住消息正文，未复核** —— 6 条判据不覆盖"观感/正文可读性"，
   但 `end: 8 → 64` 确实把 chip 从右角往左挪了 56 dp，**这是本次修复引入的唯一新风险**，
   属**视觉取舍**，需 Lead/产品过目（§8.4-4）。
3. **docked review panel 形态未在真机复验** —— `end = 64` 对 pane 宽度有 `≥ 112 dp` 的下界要求；
   本轮只在 600/768 的**无面板**形态上验证。v43 的"chip 属 timeline pane"不变量
   **结构上仍成立**（`align(TopEnd)` + `padding(end)`），但真机未验。
4. **`140 / 92 / 51 / −10 / −12` 五个基线本轮**未重算**，由 22 类回归全绿**间接**守住
   （沿用 v144 §9.2 的同一口径说明）。真机复算随 §8.3 一并做。
5. **`④`（2px overflow）仍未在设备闭环**，parity 仍欠；**`quarantine/` 保持红是对的**。
6. **`②` 的产品取舍仍未裁定** —— 要不要为满足 Android 48dp 而加宽动作按钮
   （现状 42/48，维持"不得开单"声明）。
7. **未装机、未 `assembleDebug`、未 `clean`、未跑全量** —— 证据 = JVM 测试 + 归档真机 dump
   + 生产常量现读 + 几何模型自证。
8. **架构层面的未闭环**：chip 与 composer 的两个锚点**仍然互不派生**。
   本轮选择"保持独立 + 用 pin 盯住分歧"（`Composer.kt` 不得引用 `END_DP`），
   而**不是**"从 composer 派生 chip" —— 后者能一劳永逸消灭这类分歧，但会让
   `ui/session` 的 chip 依赖 `ui/session/composer` 的内部内缩值，属**跨组件耦合**，
   本轮授权不含该重构。**留给下一轮裁定。**

---

**本轮产出**：生产代码 **1 常量 + 2 注释**、测试 **新增 8 例 / 改 2 处期望值**、0 装机、0 `assembleDebug`、0 `clean`。
