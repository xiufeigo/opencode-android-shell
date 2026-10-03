# COMPOSER_OVERFLOW_FIX6 —— v108：口径改 `paneBottom`；**「IME 高算错 8px」已证伪**

- 修复工程师：mavis（v108）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 前序：`COMPOSER_OVERFLOW_FIX5.md`（v107b）、`COMPOSER_OVERFLOW_FIX4.md`（v106）
- 权威读数：`research/v97_verify/5556_v98/REPORT_COMPOSER_OVERFLOW4.md`
  （`wm size 1366x768` / `wm density 160` ⇒ **1dp == 1px**；`mInsetsHint bottom=474 / 387`）
- Lead 两条裁定（本轮均已照做）：
  - **裁定 1**：① 的验收量由 `unionBottom ≤ hostBottom` 改为 **`unionBottom ≤ paneBottom`**
    （`paneBottom` = pane 容器底沿 = 可用区底 = `imeTop` 那一侧）。
    `hostBottom` 那组数**保留为观测值**（§4.3），**不再当判据**。
  - **裁定 2**：主攻「v106 按 IME 高 466 算、真机 474，差 8px」。

> ## 状态：**🟡 裁定 1 已落地（768 按新口径 ✅ −6，零生产代码改动）；
> ## 裁定 2 —— 「8px 是主因」已证伪（可算式复核），600 档 `+5` 的真身仍未定位**
> ## **768 档按新口径无需任何改动即通过**（`296 ≤ 302`，富余 6px）——
> ## 因为那 8px 全部落在**判据之外**（pane 自己越的那 8），并集根本不沾它。

---

## 0. 落盘记录

- §0.1 本文件在**任何代码改动之前**写定。
- §0.2 本轮**零生产代码改动**（`ui/session/**` 未动一字），只新增 1 个测试类 + 本文件。

---

## 1. §1 裁定 1 落地：① 改判 `paneBottom`

### 1.1 两条判据的现行定义

| | 量 | 定义（Lead 裁定 1） |
|---|---|---|
| ① | `unionBottom ≤ paneBottom` | `paneBottom` = **pane 容器的底沿** = 可用区底 = `imeTop` 那一侧 |
| ② | `unionPx ≤ band` | 并集（排除 `Open terminal`）高 ≤ 带高 |

### 1.2 两档按新口径的判定（真机字面量，原文照抄 `REPORT_COMPOSER_OVERFLOW4.md` §2.1/§2.2）

| 档 | 并集 | `unionBottom` | pane | `paneBottom` | ①（新） | ② | 旧的 ①（`hostBottom=imeTop`）**仅观测** |
|---|---|---|---|---|---|---|---|
| 768 | `[10,208][1356,296]` | **296** | `[0,162][1366,302]` h=140 | **302** | `296−302 = ` **−6 ✅** | `48≤48 = 0 ✅` | `296−294 = +2`（观测） |
| 600 | `[24,169][1364,218]` | **218** | `[0,162][1366,213]` h=51 | **213** | `218−213 = ` **+5 ❌** | `48≤48 = 0 ✅` | `+5`（同值） |

**⇒ 768 档在新口径下已经通过，且一个字节都没改。** 这不是本轮的功劳，
而是**旧口径把一个「pane 自己越界」的问题记在了「并集越界」的账上**。

### 1.3 为什么旧口径在 768 档数学无解（v107b §5 #2 的正式结案）

```
pane 顶 162 + 高 140 ⇒ pane 底 302        （v103 已 pin 的 top+host ≡ pane）
imeTop              = 294                  （InsetsSource type=ime frame=[0,294][1366,768]）
pane 自己越过 imeTop = 302 − 294 = +8
旧判据 unionBottom ≤ imeTop ⇒ 296 ≤ 294    ⇒ 要并集比它自己的宿主盒再高 8px 让出来
                                            ⇒ 与「pane 高必须 140」直接冲突 ⇒ 无解
新判据 unionBottom ≤ paneBottom ⇒ 296 ≤ 302 ⇒ 无冲突 ⇒ −6 ✅
```

**并集（296）本来就在 pane（302）里面** —— 旧口径要求它越过 pane 去够键盘，
那是**越过了两层**（先越过 pane，再越过 imeTop），而真正的缺陷只发生在 pane 这一层。

---

## 2. §2 裁定 2 主攻结果：**「IME 高算错 8px」已证伪**

### 2.1 问 1：466 这个数从哪来？——**它不在生产代码里**

`466` 在 `app/src/main/**` 下的**全部**命中（`grep '\b466\b'`）：

| 位置 | 是什么 |
|---|---|
| `ui/terminal/TerminalPanel.kt:891` | 注释里的上游引用 `terminal.tsx:466-474` |
| `ui/session/composer/AttachmentPolicy.kt:168` | 注释里的上游引用 `interaction.ts:462-466` |
| `ui/session/timeline/ToolRenderers.kt:1210` | 注释里的上游引用 `message-content.tsx:466-470` |
| `assets/opencode-app/assets/sprite-*.svg` | SVG path 数据里的坐标 |

**生产代码里没有任何一处假设 IME 高 = 466。** 466 **只**活在
`app/src/test/**` 的模型里（`ComposerOverflowV103Test` / `ComposerChildUnionV104Test` /
`ComposerBandTouchTargetV105Test` / `ComposerBandTallEnoughV106Test` /
`ComposerPositionDecompositionV107Test` 五处 `imeHeights = mapOf(768 to 466, ...)`）。

⇒ **v107b §1.3 写下的「pane 是按一个比真机小 8px 的 IME 高度算出来的」这句话，
在生产侧是**不成立的**：那是**测试模型的输入**，被误读成了生产代码的假设。**

### 2.2 问 2：真机 474 从哪来？生产侧唯一的 IME 读数点

**`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt:2350-2358`（原文）**

```kotlin
val believedImeBottomPx = SessionPaneLayout.believedImeBottomPx(
    visibleBoxPx = paneBoxPx,
    floorPx = paneFloorPx,
    imeBottomPx = WindowInsets.ime.getBottom(density),      // ← 唯一的 IME 读数
    essentialPx = with(density) { SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.roundToPx() },
)
val paneBottomDp = with(density) {
    maxOf(paneFloorPx, believedImeBottomPx).coerceAtLeast(0).toDp()
}
```

- **口径**：`WindowInsets.ime.getBottom(density)` —— Compose 1.7.6 的
  `AndroidWindowInsets.getBottom` ⇒ 就是平台的 `WindowInsetsCompat.getInsets(IME).bottom`。
  **这一帧现读，不是 state、不是缓存、不是旧帧值**（`SessionScreen.kt:2314` 的
  `BoxWithConstraints` 体每帧重算，`:2370` `bodyHeightDp` 由它派生）。
- **真机复核命令**（复验员可直接跑）：

  ```powershell
  adb shell dumpsys window windows | Select-String 'type=ime' -Context 0,2
  #   权威原文（REPORT_COMPOSER_OVERFLOW4.md §2.1）:
  #   InsetsSource id=3 type=ime frame=[0,294][1366,768]   mInsetsHint bottom=474
  # 600 档:
  #   InsetsSource id=3 type=ime frame=[0,213][1366,600]   mInsetsHint bottom=387
  # 校验: 768 - 474 = 294 = imeTop   ✅ 逐位对上
  ```
- ⇒ **生产侧读到的就是 474，和真机一致。生产没有算错。**

### 2.3 问 3（关键）：**8px 怎么变成 5px 越界？——它做不到。算式如下**

生产侧对 pane 高度只有一条链（`SessionScreen.kt:2428`
`Box(modifier = Modifier.fillMaxSize().padding(bottom = paneBottomDp))`）：

```
paneHeight = paneBoxPx − paneBottomDp
          = paneBoxPx − maxOf(floorPx, believedImeBottomPx)
```

**600 档**（`paneBoxPx = 600 − 162 = 438`，`imeBottomPx = 387`）：

```
believedImeBottomPx(438, 0, 387, 284):
    守卫 visibleBoxPx(438) < essentialPx*2(284*2=568)  ⇒  命中，提前返回
    ⇒ 返回 imeBottomPx 原值 = 387
paneBottomDp = max(0, 387) = 387
paneHeight   = 438 − 387 = 51        ← 与真机实测 pane h=51 逐位相同 ✅
```

**⇒ 600 档的 IME 通路贡献的误差 = 0（一个像素都没有）。**
所以 600 档那 5px **在数学上不可能**是 8px 变来的 —— 它压根没参与。

**768 档**（`paneBoxPx = 768 − 162 = 606`，`imeBottomPx = 474`）：

```
believedImeBottomPx(606, 0, 474, 284):
    守卫 606 < 568 ? 否 ⇒ 继续
    claimedPx = 474 − 0 = 474
    roomPx    = 606 − 284 = 322
    474 > 322 ⇒ 夹取 ⇒ 返回 roomPx + floorPx = 322
paneBottomDp = 322
paneHeight   = 606 − 322 = 284 = PANE_ESSENTIAL_HEIGHT
```

**⇒ 466 既不等于 474（不夹取），也不等于 322（夹取）。
`believedImeBottomPx` 在任何 `floorPx ≥ 0` 下都产不出 466**
（已写成断言 `fourSixSixIsNotAValueTheProductionImePathCanProduce`）。
pane 实际高 140 也**不等于** 284 ⇒ **`[0,162][1366,302] h=140` 是 pane 内部的
「时间线盒」，不是 `:2428` 那个 `padding(bottom=)` 出来的 pane 盒**；
这一点本轮**未证到行**，如实记在 §7。

### 2.4 证伪结论（可逐条复核）

| 断言 | 复核方式 | 结论 |
|---|---|---|
| 生产代码不含 466 | `grep '\b466\b' app/src/main/**` | ✅ 零处（只有上游 `.tsx` 行号引用与 SVG 坐标） |
| 生产 IME 读数是平台原值 | `SessionScreen.kt:2353` 原文 | ✅ 就是 474，不是 466 |
| 600 档 IME 通路零误差 | `438 − 387 = 51` = 真机 pane | ✅ 逐位相同 |
| 466 不可能是夹取产物 | `believedImeBottomPx` 三个返回分支 | ✅ 只能是 474 或 322 |
| **8px 是 600 档 +5 的主因** | 2.3 的算式 | ❌ **证伪** |

**⇒ 裁定 2 的主攻目标已被排除。8px 是 768 档独有的、且只影响 pane 自身高度的项；
而 600 档的 `+5` 由一个 600 档独有的、在 composer 子树内部的 `+5` 位移承担。**

### 2.5 那 8px 本身还剩什么？（如实记账，**本轮未改**）

8px 依然是真的用户可见缺陷：**pane 底 302 比 imeTop 294 低 8px**，
即时间线最下面 8px 藏在键盘底下。但要修它必须动 pane 的高（140 → 132），
而 Lead 要求 **「768 不回退：时间线盒 140、`readable` 92」**，
且 `QueueTimelineFloorV101Test` / v103–v107 全部 pin 着。
⇒ **按 Lead 裁定 1（① 改 paneBottom），这 8px 落到判据之外，本轮不动。**
这不是「修好了」，是**判据换了口径**；记在 §7 未闭环。

---

## 3. §3 600 档 `+5` 的真正下一嫌疑（按嫌疑排序，均**未**盲改）

| 排名 | 嫌疑 | 位置（`文件:行号`） | 为什么它能造出「向下的 +5」 |
|---|---|---|---|
| **1** | **宿主盒与带之间的那层 modifier 链把带下移了 5px** | `SessionScreen.kt:3174-3194`（host `Box(contentAlignment = BottomCenter, .heightIn(max = 56))`）→ `composer()` 传下来的 `composerRegion` | 600 档宿主盒实测 48 高、底沿 213；带实测 48 高、顶沿 170 ⇒ `bandTop − hostTop = 170−165 = +5`。**高度项已是 0（② 通过）**，所以这 5px **只能**来自「带相对宿主盒的起点」 |
| **2** | 宿主盒 `BottomCenter` 居中取整 | `SessionScreen.kt:3175` | 盒 51 / 子 41 一类奇偶差会取 1–2px，量级偏小，但**方向可向下** |
| **3** | 编辑器按带居中的亚像素取整 | `Composer.kt:2293` `editorTop = (band - editor.height).coerceAtLeast(0) / 2` | 实测 `EditText` 顶沿 **169** 比带顶 170 **高 1px** ⇒ 这一项**向上**，**减轻**越界、不制造越界 ⇒ **排名最后** |
| 4 | 非 composer 节点混入并集 | — | **已排除**：并集排除 `Open terminal` 后，底沿由 ⊞/模型 chip/Send（`[399,170][441,218]`）决定，全是 composer 自己的控件 |

**⇒ 头号嫌疑是 #1：`SessionScreen.kt:3191-3193` 传给 `composer()` 的那条
`composerRegion` / `.heightIn(max = composerCeiling)` 链，在「带」之上多垫了 5px。**
这与 v107b §5 的结论一致（收容点算式给出 `unionBottom = 213`，真机 218 ⇒ 那 5px
**必然**产生在 `SessionScreen.kt:3339` 以下的 composer 子树内部）。

---

## 4. §4 测试证据

### 4.1 命令

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --console=plain `
  --tests "com.xiufeigo.opencodeshell.ComposerPaneBottomCriterionV108Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerPositionDecompositionV107Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerOverflowV103Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerChildUnionV104Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTouchTargetV105Test" `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineShrinkCrashV102Test" `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineFloorV101Test"
```

（只跑 7 个类，**没有**一次跑十几个；`assembleDebug` / `clean` / 装机一律未做。）

### 4.2 结果（gradle 输出原文）

```
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 1m 15s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=0
LOCK_RELEASED=True
```

| 类 | tests | failures | errors |
|---|---|---|---|
| `ComposerPaneBottomCriterionV108Test`（本轮新增） | **7** | 0 | 0 |
| `ComposerPositionDecompositionV107Test` | 3 | 0 | 0 |
| `ComposerOverflowV103Test` | 8 | 0 | 0 |
| `ComposerChildUnionV104Test` | 7 | 0 | 0 |
| `ComposerBandTouchTargetV105Test` | 5 | 0 | 0 |
| `QueueTimelineShrinkCrashV102Test` | 10 | 0 | 0 |
| `QueueTimelineFloorV101Test` | 5 | 0 | 0 |
| **合计** | **45** | **0** | **0** |

锁：`LOCK_ACQUIRED`（等 0s）→ `BUILD SUCCESSFUL` → `EXIT=0` → `LOCK_RELEASED=True`。
**只跑任务书点名的 5 个类 + 上一轮的 fixture 类 + 本轮新类 = 7 个，没有一次跑十几个。**

### 4.3 观测值（旧口径 `hostBottom`，**保留但不作判据**）

| 档 | `unionBottom` | `hostBottom`(=imeTop) | 旧 ① | **新 ①（paneBottom）** | ② |
|---|---|---|---|---|---|
| 768 | 296 | 294 | +2（观测） | **−6 ✅** | 0 ✅ |
| 600 | 218 | 213 | +5（观测） | **+5 ❌（开口）** | 0 ✅ |

已写成断言 `theOldHostBottomNumbersAreRetainedAsObservationsOnly`：
它**不断言任何通过**，只保证旧数字可复现，**不放宽**任何既有判定。

### 4.4 新增 pin 覆盖什么（以及它**不**覆盖什么）

`ComposerPaneBottomCriterionV108Test`（**7** 例）：

| 用例 | 钉的东西 |
|---|---|
| `theSevenSixEightTierPassesTheNewCriterionWithSixPxToSpare` | 新口径下 768 = **−6**，`unionBottom ≤ paneBottom` 成立 |
| `theSixHundredTierIsStillOpenAndIsPinnedAsSuch` | 600 档 `+5` **作为开口钉住**（断言它**仍然失败**）——修好的那天它会红 |
| `theOldHostBottomNumbersAreRetainedAsObservationsOnly` | 旧口径 `+2 / +5` 仅作观测留档 |
| `criterionTwoUnionPxAtMostBandHoldsInBothTiers` | ② `unionPx ≤ band` 两档 = 0（**v106 成果不回退**） |
| `theSixHundredTierImePathContributesExactlyZeroError` | 调**真实生产函数** `believedImeBottomPx(438,·,387,284) = 387`，推出 `438−387 = 51` = 真机 pane |
| `fourSixSixIsNotAValueTheProductionImePathCanProduce` | 466 **不可达**（只能是 474 或 322） |
| `theEightPxIsAThreeHundredSixtyEightOnlyTermAndIsZeroOnTheSixHundredTier` | 8px 是 768 独有项，600 档恒为 0 |

> **后三条是本轮唯一有分量的 pin：它们调的是 `ui/session/` 里的生产函数**
> （`SessionPaneLayout.believedImeBottomPx`），不是自建模型。
> **但它们钉的是「诊断」，不是「修复」** —— 它们不声称 ① 已修好。

### 4.5 diff

```diff
# 生产代码：本轮 **0 处改动**（ui/session/** 未动一个字）

+ app/src/test/java/com/xiufeigo/opencodeshell/ComposerPaneBottomCriterionV108Test.kt  (新增, 7 用例)
+ research/v97_fix/COMPOSER_OVERFLOW_FIX6.md                                            (本文件)
```

**未碰**：`_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers/**`、
`ui/home/**`、`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`。
**未新增任何用户可见字符串。** **未放宽任何既有断言。**

---

## 5. §5 为什么不降强度

| 约束 | 本轮状态 |
|---|---|
| `MIN_TOUCH_TARGET` / 48dp 触摸靶 | **一个像素没动**（未改 `Composer.kt`） |
| `BAND_HEIGHT` / `TOOL_ROW_HEIGHT` | **一个字没改** |
| v103–v107 全部 pin | **一条没放宽**（7 个类全绿，见 §4.4） |
| 768 档 | 时间线盒 **140**、`readable` **92** —— 未动 |
| 600 档 `readable` | **51**（§FIX4 让位声明）—— 未动 |
| 缩窗往返 | 未改 `QueueTimelineShrinkCrashV102Test` 的任何断言 |

**代价**：600 档 `+5` **仍未修好**。如实记在 §7，**不假装修好了**。

---

## 6. §6 真机复验步骤（给复验员；本轮未装机、未跑真机）

前置：`adb shell wm size 1366x768; adb shell wm density 160`（**显式重设，不用 reset**），
键盘升起（`mInputShown=true`），坐标从 `adb shell uiautomator dump --compressed` 解析
（沿用 `parse_5556.ps1`），`paneBottom` 取「时间线盒/pane 盒」的**容器底沿**。

| 步 | 动作 | 期望判据（新口径） |
|---|---|---|
| **1** | **768 + IME**，量 pane 容器底沿 + ⊞/模型 chip/Send（**排除 `Open terminal`**） | ① `unionBottom ≤ paneBottom`（基线 `296 ≤ 302` = −6）**须成立**；② `unionPx ≤ band`（=0）**不得回退** |
| **2** | **600 + IME**，同上 | ① `unionBottom ≤ paneBottom`（基线 `218 vs 213` = **+5 ❌ 开口**）；② = 0 |
| 3 | 768 关 IME | ① 仍成立；时间线盒/pane 仍 **140**、`readable` 仍 **92**（≥64） |
| 4 | 768 排队 0 / 2 / 11 条 | pane 恒 **140**；① 三格都成立 |
| 5 | 缩窗往返 2 轮（768→600→768→600→768） | `pidof` 不变、无 `FATAL`/`IllegalArgumentException`、同档几何**逐位相同**（无振荡） |
| 6 | 队列条 | `Steer` h=43、**`×` h 仍 48**、三者 `clickable=true` |
| 7 | **IME 口径核对**（本轮新增，直接验 §2.2） | `dumpsys window windows` 的 `type=ime frame` 与 `mInsetsHint bottom`；须 `768 − 474 = 294`、`600 − 387 = 213` |

### 6.1 一行定位探针（**只在步 2 失败时上**；已落成可复现指令）

在 `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt`
的 `layout(...)`（**`:2294`**，即 `minimal` 一行形态那一支）里临时加打印：

```kotlin
// Composer.kt:2294  layout(constraints.maxWidth, constraints.constrainHeight(band)) {
Log.i("OCPROBE", "band=$band editorTop=$editorTop editor.h=${editor.height} toolRow.h=${toolRow.height}")
```

在 `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt:3337-3339`
同时加：

```kotlin
Log.i("OCPROBE", "pane=$pane host.h=${host.height} top=${SessionPaneLayout.composerTopPx(pane, host.height)}")
```

复验步骤：

1. `.\gradlew.bat :app:installDebug`（**仅复验员执行**；本轮纪律禁止装机，故未跑）
2. `adb logcat -c`；进会话页，`adb shell input tap` 聚焦输入框把键盘顶起
3. `adb shell wm size 1366x600; adb shell wm density 160`（**先切档再收键盘**，等 1s 稳态）
4. `adb logcat -d -s OCPROBE` 抓 `OCPROBE` 行
5. **判读表**：

| 观察 | 结论 |
|---|---|
| `band=48 editorTop=0 toolRow.h=48` **且** 宿主 `top=165` | 带自身无位移 ⇒ **+5 在 `Composer` 子树之外的 modifier 链**（嫌疑 #1，`SessionScreen.kt:3191-3193`） |
| `band=48` 但宿主 `top=170` | +5 来自**宿主盒摆位**（嫌疑 #1 的上一级，`SessionScreen.kt:3339`） |
| `band=53`（> 48） | `constraints.maxHeight` 给到了 53 ⇒ +5 是**带被撑高**（嫌疑表外，`SessionScreen.kt:3295-3301` 的 ceiling） |
| `toolRow.h=53` | 48dp 触摸靶在 `minimumInteractiveComponentSize` 下涨到了 53 ⇒ **嫌疑 #3 被激活**，转向 `Composer.kt:2204-2218` |

6. 复验完把两行 `Log.i` 删掉（**本轮不留在生产代码里**）。

---

## 7. §7 未闭环（如实记账）

1. **600 档 `+5` 未修好（本轮的诚实结论）**。「IME 高 8px」已证伪（§2.4，五条可复核断言），
   头号嫌疑收敛到 `SessionScreen.kt:3191-3193` 的 `composerRegion` modifier 链，
   **但没有真机读数，本轮不改** —— 盲改就是第五次假绿。
   探针已落成 §6.1 的可复现步骤。

2. **768 档 pane 仍比 imeTop 低 8px（302 > 294）**，用户可见（时间线最下 8px 藏在键盘下）。
   按裁定 1 改判 `paneBottom` 后它**落在判据之外**；真要修须动 140/92 两个硬 pin。
   **交 Lead。**（这正是 v107b §5 #2 的路线 C，本轮的结论是：它**不是** IME 读数错，
   而是 `paneBoxPx − believedImeBottomPx` 这条链的产物，且 466 与之无关。）

3. **`[0,162][1366,302] h=140` 是「时间线盒」还是「`:2428` 的 pane 盒」未证到行。**
   按 §2.3 的算式，`:2428` 那个盒在 768 应高 **284**（= `PANE_ESSENTIAL_HEIGHT`），
   与实测 140 不符 ⇒ **a11y dump 的那个节点不是它**。这条链本轮**没追到底**。

4. **`paneBoxPx` 在两档的真实值未独立复测。** §2.3 用 `window − 162` 反推
   （假设 `safeDrawing` top inset = 162px），未经 `dumpsys` 直接确认。

5. **768 的 `readable` 92 仍是推导值**（`140 − 48`），非 a11y 直读（沿用 v105/v106 的同一限制）。

6. `/sdcard` 仍有往轮残留（本轮未装机，未产生新文件；**不越权清理他人轮次产物**）。
