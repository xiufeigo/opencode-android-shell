# 设置弹层 IME-down 几何定性（5556 实测 580/95 vs HANDOFF 记的 600/84）

- 任务：v97 派单「疑似回归」——`research/v97_verify/5556_v82/REPORT.md` §S3 实测键盘**落下**态卡片
  **高 580 / 顶边 94**（像素白色面 95..671），而 `research/HANDOFF.md:71` 记的 v82 历史值是
  **高 600 / 顶边 84**；`research/v97_verify/5554/V82_FIVE_CRITERIA.md:42-44` 把它记成「疑似
  `windowHeightDp` 取扣掉桌面 shell 条后的高度（672）」，要求核实是否 v82 引入。
- 设备：`emulator-5556` = Medium_Desktop，`wm size` = 1366x768，`wm density` = 160 → **1 px = 1 dp**。
- 结论一句话：**口径问题（同一台设备的两轮之间底部系统条从 48 px 变成 96 px），不是 v82 引入的回归；
  生产代码零改动。**

---

## 结论

**判定 = 记录口径问题（环境形态差异），非真回归。**

三条互相独立的证据：

1. **代码恒等**：`imeBottomDp = 0` 时，`settingsDialogCardHeightDp(w, 0)` 与 v82 之前 git `HEAD` 里的
   `maxOf(min(w − 92, 600), 240)` **对任意 w 逐值相等**，且修饰符链在键盘落下时逐字相同
   （新增的 `.then(if (imeUp) … else Modifier)` 此时是恒等 `Modifier`）。⇒ v82 **不可能**改变键盘
   落下态的卡片几何。今天把同一份旧代码放到同一台设备上，同样会得到 580/94。
2. **来源没被换过**：调用点一直传 `LocalConfiguration.current.screenHeightDp`，不是
   `BoxWithConstraints.maxHeight`、不是 `LocalView`/`WindowMetrics`/`rootView`、也不是任何
   「扣掉 shell 条」的自算高度（全仓没有任何 96 的扣减）。
3. **差值有物理来源**：同一台 5556、同一 `wm size/density` 读数下，v82 那轮的底部系统条实测
   **y=720..767（48 px）**，本轮实测 **y=672..767（96 px）**。`Configuration.screenHeightDp` 随之
   720 → 672，而 `min(w − 92, 600)` 的 600 上限只在 `w ≥ 692` 时兜得住 ⇒ 720 得 600、672 得 580。
   HANDOFF 记的 600/84 是「系统条 48 px 那种形态」的读数，不是设计常量。

---

## 公式与推导

### Q1 IME-down 态卡片高的准确公式与输入来源

**纯函数** — `app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsComponents.kt:800-807`：

```kotlin
internal fun settingsDialogCardHeightDp(
    windowHeightDp: Float,
    imeBottomDp: Float = 0f,
): Float {
    val visible = windowHeightDp - imeBottomDp.coerceAtLeast(0f)
    return minOf(windowHeightDp - SETTINGS_CARD_GUTTER_DP, SETTINGS_CARD_MAX_HEIGHT_DP, visible)
        .coerceAtLeast(SETTINGS_CARD_MIN_HEIGHT_DP)
}
```

同文件常量：

- `SettingsComponents.kt:768` `internal const val SETTINGS_CARD_GUTTER_DP = 92f`（上游 `100vh − 92` 的 gutter）
- `SettingsComponents.kt:771` `internal const val SETTINGS_CARD_MAX_HEIGHT_DP = 600f`（上游 600px 上限）
- `SettingsComponents.kt:774` `internal const val SETTINGS_CARD_MIN_HEIGHT_DP = 240f`（地板）

**输入来源（唯一调用点）** — `app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsScreen.kt:347-357`：

```kotlin
private fun BoxWithCappedSize(modifier: Modifier, content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val width = minOf(config.screenWidthDp.dp - 32.dp, 980.dp)
    val height = settingsDialogCardHeightDp(
        windowHeightDp = config.screenHeightDp.toFloat(),
        imeBottomDp = imeBottom.value,
    ).dp
    val imeUp = imeBottom > 0.dp
```

- `windowHeightDp` 的来源 = **`LocalConfiguration.current.screenHeightDp`**（`:349` + `:354`），
  **不是** `BoxWithConstraints` 的 `maxHeight`，**不是**减去 shell 条后的自算高度。
- 宽度同源：`:352` `minOf(config.screenWidthDp.dp − 32.dp, 980.dp)`（同一份 `config`）。
- 卡片由 `ui/App.kt:3151-3152` 以 `dialog.show(DialogSize.X_LARGE) { SettingsDialogContent(...) }` 挂载，
  `modifier` 取默认值 `Modifier` ⇒ 盒子不受外部高度约束，高度只由上面这条链决定。
- 居中宿主：`ui/overlay/DialogHost.kt:418-443`，`Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center)`
  ⇒ **卡片在整窗（768）里居中**，不是在自己算出来的高度里居中。这是后面 10 px 位移的来源。

**修复前（v82 及以前）该分支的取值** — 找到了，git `HEAD`（`a5322cf`，v47→v82 的工作树都还没提交，
`SettingsScreen.kt:189-200`）：

```kotlin
private fun BoxWithCappedSize(modifier: Modifier, content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val width = minOf(config.screenWidthDp.dp - 32.dp, 980.dp)
    val height = minOf(config.screenHeightDp.dp - 92.dp, 600.dp)
    Box(
        modifier
            .width(width)
            .height(maxOf(height, 240.dp))
            .background(OcTheme.colors.bgBase, RoundedCornerShape(8.dp))
            .border(0.5.dp, OcTheme.colors.borderBase, RoundedCornerShape(8.dp)),
    ) { content() }
}
```

**恒等式**（本轮新增用例 `imeDownIsThePreV82ExpressionForEveryWindow` 把它抄进测试做 w = 0..1600 全扫）：

```
旧： maxOf( min(w − 92, 600), 240 )
新： maxOf( min(w − 92, 600, w − ime), 240 )，ime = 0
    因为 w − 92 ≤ w，第三项永不更小 ⇒ min(w − 92, 600, w) = min(w − 92, 600)
⇒ 两者对任意 w 逐值相等。
```

修饰符链同样逐字相同：旧 `.width(width).height(...).background(...).border(...)`；
新 `.width(width).then(if (imeUp) … else Modifier).height(height).background(...).border(...)`，
键盘落下时 `imeUp = false` ⇒ 插入的是空 `Modifier`。**v82 在键盘落下态没有留下任何痕迹。**

### Q2 580/94 相对 600/84：是本次 v82 引入，还是记录口径问题？

**推导（每个数都代进去）**

| 步 | v82 那轮（`research/v82_verify/5556/40_set.png`） | 本轮（`research/v97_verify/5556_v82/V82_S3_01_settings_kbd_down.png`） |
|---|---|---|
| 整窗高 | 768 px @160dpi = **768 dp**（两轮 `wm size`/`wm density` 读数一致） | 同 |
| 卡片盒（像素探针） | 边框 `y=84 (54,59,69)`、卡面 `y=85…682`、边框 `y=683`、`y=684` 变 scrim `(12,15,19)` ⇒ 盒 = **[84, 684) = 600 dp** | a11y 节点 `[193,94][1173,674]` ⇒ 盒 = **[94, 674) = 580 dp**；像素白色面 **95..671**（`y=672` 起被系统条盖住） |
| 居中校验 | 384 − 600/2 = **84** ✓ | 384 − 580/2 = **94** ✓（像素读到 95 是 0.5 dp 边框圆角落在第 95 行） |
| 底部系统条 | `(44,44,44)` 自 **y=720** 起 ⇒ **48 px** | `(44,44,44)` 自 **y=672** 起（x=200）⇒ **96 px**；同轮 a11y 节点 `[0,672][1366,768]`（w=1366,h=96、**depth 2**，是 app 的 DecorView 子节点的**兄弟**，即另一个系统窗口）独立互证 —— 它是**平台 shell 的窗口**，不是本仓画出来的任何东西（全仓无 96 的扣减） |
| ⇒ `Configuration.screenHeightDp` | 768 − 48 = **720** | 768 − 96 = **672** |
| 代入公式 | `min(720 − 92, 600) = min(628, 600) =` **600** | `min(672 − 92, 600) = min(580, 600) =` **580** |
| 顶边 | `(768 − 600)/2 = 84` | `(768 − 580)/2 = 94` |

- **阈值代数**：`min(w − 92, 600)` 在 `w ≥ 692` 时恒为 600（gutter 算出来的值被上限吃掉），
  在 `w ≤ 691` 时 = `w − 92`。720 在阈上、672 在阈下，**差的 20 dp 恰好 = 692 − 672**。
- **顶边位移 = 高度差的一半**（居中于整窗）：`(600 − 580)/2 = 10` px；`94 − 84 = 10` ✓。
- **`screenHeightDp = 672` 的唯一性**：实测高 580 有两支解 —— (a) `w = 672, ime = 0`；
  (b) `w = 768, ime = 188`。(b) 被三条读数排除：该态 `dumpsys input_method` 为 `mInputShown=false`、
  像素列上无键盘、且卡片盒**严格居中于 768**（中心 384 = 768/2）——只有走无 IME 分支
  （`.then(if (imeUp) … else Modifier)` = 空）才会在整窗里居中；一旦 `imeBottom > 0`，
  顶边会被 `imePadding` 抬到窗口顶端（本轮 IME 抬起态实测外盒 `[193,9][1173,759]` 即该分支的签名）。
  ⇒ **(a) 成立**，`screenHeightDp = 672` 是被两条独立读数（高度 + 居中）锁死的唯一解。

**结论**：580/94 与 600/84 之差 **100% 由设备形态变化解释**（底部系统条 48 px → 96 px，
`Configuration.screenHeightDp` 720 → 672），**不是 v82 的代码改动**。HANDOFF 记的 600/84 本身没错
（像素复算逐位吻合），但它是**某一种 shell 形态下的读数**，被后续轮次当成了常量。

**「v47/v48 居中几何逐 px 不变」这条承诺成立吗？** 成立。承诺的语义是「键盘落下时 IME 链条不存在」，
而这在代码层逐字为真（`else Modifier`），纯函数在 `ime = 0` 时与旧表达式恒等（已用全窗扫描钉死）。
它从来没有承诺「这个数字与设备形态无关」——数字是 `screenHeightDp` 的函数，设备形态变了它当然会变。

---

## 上游对照

上游（`_upstream/`，只读）`packages/ui/src/overlays/dialog/dialog.css:176-179`：

```css
&[data-size="x-large"] [data-slot="dialog-container"] {
  width: min(calc(100vw - 32px), 980px);
  height: min(calc(100vh - 92px), 600px);
}
```

本壳弹层正是 `DialogSize.X_LARGE`（`ui/App.kt:3151`），宽高公式与上游逐项同源。

- 上游的 `100vh` 是**浏览器视口** = 整个窗口（CSS 视口不扣系统条，webview 在浏览器里铺满窗口）。
  **上游在这个窗口里应当是 `min(768 − 92, 600) = 600`**，顶边 84。
- 本壳用 `LocalConfiguration.screenHeightDp` 代理 `100vh`，而它是**显示高减去系统条**
  （本轮 672 vs 整窗 768；uiautomator 根节点 `[0,0][1366,768]` 就是那个 768 的整窗）。
  ⇒ **上游同款应当 600，本壳给 580，短 20 dp**。
- 但这个「短」**与 v82 无关**：v47/v48 以来高度就是这样算的（git `HEAD` 里同一份
  `config.screenHeightDp`），v82 只把「键盘态要不要让位」做进了同一个表达式。
  v82 那轮之所以显示 600，只是因为当时系统条只有 48 px、`min(628, 600)` 恰好被上限吃掉。
- **不建议**为了让「对齐上游 600」而把高度来源换成整窗：那样卡片盒会变成 `[84, 684)`，
  **下沿 12 px 落进 96 px 的系统条底下**（今天的 580 只压住 2 px），等于把 v82 修掉的
  「内容被键盘/系统 UI 盖死」换个形式再犯一次；而且同一个 `screenHeightDp` 代理被全仓约 10 处共用
  （`SshDialog.kt:294`、`ServersPanel.kt:672`、`ShellChromeBudget`、`OcSelect`、`paletteShell`、
  `DirectoryPickerDialog.kt:586` …），单改设置卡会让一级卡与二级表单各算各的。
  **这条作为遗留项交 Lead 裁决，本轮不动。**

---

## 改动

**生产代码：零改动。**（`git status` 可见本轮只多了一个测试文件）

- 新增：`app/src/test/java/com/xiufeigo/opencodeshell/SettingsImeDownGeometryV97Test.kt`（6 例）
- 未触碰：`ui/settings/**` 的任何生产文件、`_upstream/**`、`app/build.gradle.kts`、`ui/home/**`、
  `data/HomeProjectStore.kt`、`ui/session/**`；未新增任何用户可见字符串；未降低任何既有断言。

v82 当时的改动（`git diff` HEAD → 工作树，节选，仅作**定性证据**）：

```diff
--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsScreen.kt   (HEAD a5322cf)
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsScreen.kt   (working tree)
     val width = minOf(config.screenWidthDp.dp - 32.dp, 980.dp)
-    val height = minOf(config.screenHeightDp.dp - 92.dp, 600.dp)
+    val height = settingsDialogCardHeightDp(
+        windowHeightDp = config.screenHeightDp.toFloat(),
+        imeBottomDp = imeBottom.value,
+    ).dp
+    val imeUp = imeBottom > 0.dp
     Box(
         modifier
             .width(width)
-            .height(maxOf(height, 240.dp))
+            .then(
+                if (imeUp) {
+                    Modifier
+                        .imePadding()
+                        .statusBarsPadding()
+                        .padding(top = desktopCaptionReserve())
+                } else {
+                    Modifier
+                },
+            )
+            .height(height)
             .background(OcTheme.colors.bgBase, RoundedCornerShape(8.dp))
```

复现命令：`git diff -U1 -- app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsScreen.kt`
与 `git show HEAD:app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsScreen.kt`。

**新增用例覆盖**（`SettingsImeDownGeometryV97Test`）：

| 用例 | 钉住什么 |
|---|---|
| `imeDownIsThePreV82ExpressionForEveryWindow` | 把 git `HEAD` 的旧表达式抄进测试，对 `w = 0..1600` **全扫**，证明 IME-down 是恒等重构（不是靠注释自证） |
| `imeDownAtTheUpstreamCapAndAtTheMeasuredWindow` | `(768,0)=600`、`(720,0)=600`（v82 那轮形态）、**`(672,0)=580`（本轮实测）**、阈值 `692/691/372`、短窗 308、地板 240 |
| `theKeyboardStateStillTakesTheBandAboveTheKeyboard` | **`(768,284)=484`**、`(768,484)=284`（本轮键盘上沿 284）、`(768,426)=342`（v82 测试员 5558 的数）、`(768,200)=568`、`(768,700)=240`；且断言 `ime=484 < ime=0` 的返回值（IME-up 不得塌回 IME-down） |
| `theCallSiteHandsTheHelperTheConfigurationWindowHeight` | 源码 pin：传的是 `config.screenHeightDp.toFloat()`；**禁** `BoxWithConstraints` / `constraints.maxHeight` / `LocalView` / `WindowMetrics` / `rootView`（即「不许悄悄换成第二个窗口量」）；宽度同源 |
| `theImeDownChainAddsNoPadding` | 源码 pin：`.then(if (imeUp) …)` 的 else 分支是裸 `Modifier`（不得含 padding）；仍用固定 `.height(height)` 而非 `heightIn`（KDoc 契约）；IME 链在 `.height` 之外 |
| `theServersDialogsReadTheSameWindowHeightSoTheCardIsNotTheOddOneOut` | 同域二级弹层（`.heightIn(max = band)` 那一族）读同一个 `config.screenHeightDp.toFloat()`，钉住「一处改要三处改」 |

---

## 测试证据

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.SettingsImeDownGeometryV97Test" --tests "com.xiufeigo.opencodeshell.SettingsDialogImeInsetV82Test" --tests "com.xiufeigo.opencodeshell.SettingsStructureTest" --tests "com.xiufeigo.opencodeshell.Wave23aSettingsPaletteTerminalTest" --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest"
```

输出尾部（原文）：

```
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:compileDebugUnitTestKotlin UP-TO-DATE
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 4s
52 actionable tasks: 1 executed, 51 up-to-date
```

`app/build/test-results/testDebugUnitTest/*.xml`（同一时刻 18:32:49）：

| 测试类 | tests | failures | errors | skipped |
|---|---|---|---|---|
| `SettingsImeDownGeometryV97Test`（新增） | 6 | 0 | 0 | 0 |
| `SettingsDialogImeInsetV82Test` | 6 | 0 | 0 | 0 |
| `SettingsStructureTest` | 30 | 0 | 0 | 0 |
| `Wave23aSettingsPaletteTerminalTest` | 25 | 0 | 0 | 0 |
| `StringsLocaleParityTest` | 3 | 0 | 0 | 0 |
| **合计** | **70** | **0** | **0** | **0** |

抢锁记录：首次跑被 `.agent-gradle-lock`（持有者 `fix-route`）挡住 → 等 30 s 轮询重试，
18:32:46 拿到锁（`Set-Content .agent-gradle-lock "fix-settings-geom"`），跑完 18:32:51
`Remove-Item` 释放。
（另：18:31 第一次跑时 `:app:compileDebugKotlin` 因**别的 agent** 正在改的
`ui/session/header/SessionHeader.kt:220` 未解析引用而 FAILED；等对方改完（18:32:25 落盘）后重跑即绿。
本任务未触碰 `ui/session/**`。）

像素取证复现命令（都在仓内，只读）：

```powershell
# v82 那轮截图：卡片上下边框 + 底部系统条上沿（x=1160）
python research\v97_verify\5556_v82\_kb_edge.py research\v82_verify\5556\40_set.png 1160 670 767
#   y=683 (54,59,69) 边框 / y=684 (12,15,19) scrim / y=720 (44,44,44) 系统条起 ⇒ 盒 [84,684)=600，系统条 48px
python research\v97_verify\5556_v82\_kb_edge.py research\v82_verify\5556\40_set.png 1160 60 90
#   y=84 (54,59,69) 边框 / y=85 起卡面 ⇒ 顶边 84

# 本轮截图：系统条上沿（x=200）
python research\v97_verify\5556_v82\_kb_edge.py research\v97_verify\5556_v82\V82_S3_01_settings_kbd_down.png 200 660 767
#   y=660 (250,250,250) 卡面 / y=672 (44,44,44) 系统条起 ⇒ 系统条 96px，卡面止于 671
# 本轮卡片盒（a11y）：只列宽 >= 900 的容器
python research\v97_verify\5556_v82\E2ECHK5556_geom.py research\v97_verify\5556_v82\ui_V82_S3_01_settings_kbd_down.xml 900
#   [0,0][1366,768] ComposeView（整窗） / [193,94][1173,674] w=980 h=580（卡片）/ [0,672][1366,768] w=1366 h=96（系统条）
```

---

## 真机复验步骤（1366x768@160 上量卡片高与顶边，期望值写死）

前置：`adb devices` 见 `emulator-5556`；**设置弹层内禁用 ESC(111)**，收键盘用 `keyevent 61`(TAB)。

```powershell
# 0) 环境 + 整窗（期望 wm size 1366x768、wm density 160）
adb -s emulator-5556 shell wm size
adb -s emulator-5556 shell wm density
# 直接读平台报的窗口/系统条框（本轮若有 insetsState，应看到 navigationBars frame=[0,672][1366,768]）
adb -s emulator-5556 shell dumpsys window windows | Select-String "navigationBars|insetsState"

# 1) 键盘落下态
adb -s emulator-5556 shell input keyevent 61                     # TAB 收键盘
adb -s emulator-5556 shell input keycombination 113 55            # Ctrl+, 打开设置
adb -s emulator-5556 shell uiautomator dump /sdcard/s3a.xml
adb -s emulator-5556 pull /sdcard/s3a.xml .\s3a_kbd_down.xml
adb -s emulator-5556 shell screencap -p /sdcard/s3a.png
adb -s emulator-5556 pull /sdcard/s3a.png .\s3a_kbd_down.png
python research\v97_verify\5556_v82\E2ECHK5556_geom.py .\s3a_kbd_down.xml 900   # 卡片容器 bounds
python research\v97_verify\5556_v82\_kb_edge.py .\s3a_kbd_down.png 200 660 767
```

**期望值（当前 96 px 系统条形态，写死）**

| 读数 | 期望 |
|---|---|
| 整窗（ComposeView 根节点） | `[0,0][1366,768]` |
| 底部系统条上沿 | `y = 672`（高 96） |
| 卡片 a11y 盒 | `[193,94][1173,674]`，**w=980、h=580、顶边 94** |
| 卡片像素白色面 | `y=95..671`（`y=672` 起被系统条盖） |
| 卡片中心 | `94 + 580/2 = 384`（= 整窗中心，证明走的是无 IME 分支） |
| 公式 | `min(672 − 92, 600) = 580`；若系统条变 48 px 则 `min(720 − 92, 600) = 600`、顶边 84 |

**反向对照实验（把口径钉死的关键一步）**：在同一台 5556 上让系统条回到 48 px 形态
（`adb shell wm size 1366x816` 后再截图对照，或换一台 phone 形态 AVD 重测同一 build），
键盘落下态**应当重新得到高 600 / 顶边 84**。若仍得 580，才说明还有别的变量，值得回头查。

**IME 抬起态不得被破坏（本轮只读、不改码，回归基线）**

```powershell
adb -s emulator-5556 shell input tap 313 171          # 点搜索框抬键盘
adb -s emulator-5556 shell uiautomator dump /sdcard/s3b.xml ; adb -s emulator-5556 pull /sdcard/s3b.xml .\s3b_ime_up.xml
adb -s emulator-5556 shell screencap -p /sdcard/s3b.png ; adb -s emulator-5556 pull /sdcard/s3b.png .\s3b_ime_up.png
```

| 读数 | 期望（与本轮实测一致） |
|---|---|
| 键盘上沿 | `y = 284`（两轮独立测得，本机稳定；HANDOFF v87 记的 342 是**另一台设备**，不可跨设备比） |
| 卡片外盒（含 imePadding） | `[193,9][1173,759]`（h=750） |
| 卡片绘制 | 顶边 `46`、底边 `283`、高 `≈238..240`（= 240 dp 地板） |
| 卡片底 vs 键盘上沿 | `283 < 284` ⇒ 重叠 **0 px** |
| 两个 ScrollView | `[205,154][421,273]` h=119 / `[434,94][1173,285]` h=191 |

---

## IME-down 与 IME-up 共用同一高度来源，会不会互相牵连？

**会牵连，而且牵连点有两个，分开看：**

1. **同一个 `windowHeightDp` 输入（设计如此，但两侧的敏感度不同）**
   - IME-down：`h = min(w − 92, 600, w)`。对本轮 `w = 672`，600 上限**不再兜底**，于是
     `w` 每掉 1 dp，卡片就矮 1 dp、顶边下移 0.5 px —— 所以设备形态一变它立刻可见。
   - IME-up：`h = min(w − 92, 600, w − ime)`，本轮 `ime = 484` ⇒ `w − ime = 188 < 240` ⇒
     **240 地板兜底**，高度与 `w` 无关（本轮换任何 `w ≥ 724` 的形态都还是 240）。
   - 也就是说：**IME-down 对 `windowHeightDp` 敏感、IME-up 在本机被地板保护**。若将来有人把
     `windowHeightDp` 换成整窗高（768），IME-down 会从 580 跳到 600（并把 12 px 压进系统条），
     而 IME-up 仍是 240 —— 两态会被**同一个改动**推向不同方向，这就是「互相牵连」的具体形态。
2. **同一个 `windowHeightDp` 与不同基准的 IME 混算（既有 wart，本轮不改）**
   - `WindowInsets.ime.getBottom()` 是**相对整窗底边**的（768 − 284 = 484），
     而函数把它从 `screenHeightDp`（672）里减，得到 `188`，而真正「键盘以上的带」是 `284`。
     本轮因为 240 地板兜住，**没有可见后果**；但若某台机器是「96 px 系统条 + 200 px 键盘」，
     `672 − 200 = 472` 会被 600 上限截成 472，而真实可视带只有 `768 − 200 = 568` —— 卡会**矮 96 dp**。
   - 修它需要把「窗口量」和「IME 量」统一到同一基准（要么都整窗、要么都用 `screenHeightDp`），
     会同时改动 IME-up 几何，**超出本任务授权**（任务书明令「不得破坏 IME-up 态」），登记为遗留项。

---

## 未闭环 / 未验到

1. **`screenHeightDp = 672` 是反解出来的**，不是设备上直接读到的。依据是「实测卡高 580 + 卡盒严格居中
   384」两条读数锁死唯一解（见 Q2 推导末尾）。若要一锤定音，请复验时补一条
   `adb -s emulator-5556 shell dumpsys window windows` 看 `navigationBars` 框，或临时打一行
   `Log.d("oc", "cfg=${resources.configuration.screenHeightDp}")`（**不建议提交**）。
2. **系统条 48 px → 96 px 的成因未查**（AVD 镜像更新？桌面 shell 形态切换？两轮 `wm size`/`wm density`
   读数一致，所以不是 `wm size` override 变了）。这不影响定性，但影响「以后还能不能拿 600/84 当基线」。
3. **「100vh 代理口径」未修**：`LocalConfiguration.screenHeightDp` ≠ 视口高度，在有系统条的窗口里
   系统性偏小（= 系统条高度），同源调用点约 10 处（含 `ui/servers/**`，超出本任务写域）。
   修它要 Lead 裁决，且必须一次改齐，且会改动 IME-up 几何。**本轮明确不做。**
4. **真机未上机复验**：本任务纪律禁止 `assembleDebug`/装机，本轮所有真机数字都来自**已有证据文件**
   （`research/v97_verify/5556_v82/` 与 `research/v82_verify/5556/`）的复算，未新装机复测。
   上文「真机复验步骤」的期望值即由此写死。
5. **判据 3「键盘抬起后卡片顶边 y 不变（≤12px）」的分歧**已由 Lead 裁决作废
   （`research/v97_verify/5554/V82_FIVE_CRITERIA.md:36-41`）。本任务只闭环「580/94 vs 600/84」这一条，
   未再触碰 IME-up 的形状选择（上移+收缩）。
