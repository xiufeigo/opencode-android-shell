# PIN_DEVICE_HARDLINK — v139：A（pin 读真机读数文件）+ C（`record()` 一行 `Log.i` 探针）

状态：**A + C 已落地；主闸门绿（51 例 / 0 失败）；"当前必然红"按 §6 的方案隔离，等 Lead 裁定**
· 2026-10-04

> 锁约定沿用 `UNION_1PX_NOT_EFFECTIVE.md:8-9`（仓库无独立 `*.lock`，本报告文件即锁）。
> 抢锁时刻 = 本文件首次写入（当时无同名文件，git 树 clean）；本文件定稿 = 释放锁。
>
> 授权：`UNION_1PX_NOT_EFFECTIVE.md`（v138）§7 —— 采纳 **A + C**；**B 暂缓**；**D 否**。
> 纪律：只跑 `:app:testDebugUnitTest --tests "<类名>"`；未 `assembleDebug` / `clean` / 全量 / 装机；
> 未杀进程；未新增用户可见字符串；**未放宽或删除任何断言**；用例数未减少；
> 未用 `git checkout` / `git restore` 改行尾（`autocrlf=false`）。

---

## 0. 一页结论

| 问题 | 答案 |
|---|---|
| A 怎么读真机读数？ | 直接**解析 v137 的 `uiautomator dump` XML**（`research/v97_verify/5554_md/artifacts/v137_BARON_M.xml`，66 节点，22892 字节），**按结构角色定位节点**，不按坐标、不按手抄数（§1–§2） |
| 逐项对照结果？ | **8 项里 5 项一致、3 项不一致**；不一致全部是「模型比真机乐观 1px」或「模型缺项」（§3） |
| 文件缺失怎么办？ | **红**。`red()` 抛 `AssertionError` 并写明原因；无 `assume`、无跳过、无默认值、无预览（§2.3） |
| C 的探针加在哪？ | `Composer.kt:2390`，`SessionComposerBandUnion.record(...)` 正下方，**1 行 `Log.i`**（`SessionPaneLayout.kt` 不可写，故取 Lead 授权的"等效位置"）（§4） |
| 抓取命令？ | `adb -s emulator-5554 logcat -d -t 200 -v threadtime -s BandUnion:I`（**带 `-t`**，无上限会挂死）（§4.2） |
| 当前红怎么处理？ | 拆成两半：**证据进闸门**（绿，51 例）、**判定隔离**（红，不编译）。需 Lead 裁定（§6） |
| 断言被放宽了吗？ | **零放宽**。`ComposerBandUnionV136Test` **一个字节未动**（§7） |

---

## 1. 真机读数文件与它的格式

### 1.1 文件

```
research/v97_verify/5554_md/artifacts/v137_BARON_M.xml
22892 字节 · mtime 2026-10-04 06:59:22 · 66 个 <node>
```

来源：v137 在 **emulator-5554** 上的 `uiautomator dump`，构件
`md5(app-debug.apk) = 9142F0EB68DEE9D607AA0AB8BFADC0A2`，
`wm size 1366x600` / `wm density 160` ⇒ **1dp == 1px**，
600 档、队列条在屏、一行形态。截图 `v137_BARON_M.png` 同目录同批。
（这份读数 v138 §1 已逐位复算过一遍；v139 的增量是**让 JVM 去读它**，不是重算它。）

### 1.2 格式

`uiautomator dump` 的标准输出，单行、无缩进、无换行的 `XmlPullSerializer` 产物：

```xml
<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="..." class="android.widget.FrameLayout"
        package="com.xiufeigo.opencodeshell" content-desc="" checkable="false"
        checked="false" clickable="false" enabled="true" focusable="false"
        focused="false" scrollable="false" long-clickable="false" password="false"
        selected="false" bounds="[0,0][1366,600]">
    <node …/>   <!-- 逐层嵌套，共 66 个 -->
  </node>
</hierarchy>
```

对本任务有用的属性只有 4 个：`class`、`clickable`、`bounds`、`resource-id`。
**每个 `<node …>` 的属性全是 `key="value"`，`bounds` 恒为 `[x0,y0][x1,y1]`。**

### 1.3 为什么值得单独写一个解析器

`bounds` 是唯一的几何来源，而 **21 高的布局框与 48 高的语义框成对存在**
（v138 §1）——`uiautomator` 量的是**语义**框（`clickable` 节点的 bounds 含
material3 的 48dp 触摸靶扩张），而 21 高的才是 `Alignment` 摆放的那个孩子。
这正是模型缺的那一项。**只读 XML 就能同时拿到两项**，手抄数做不到。
文件是**扁平属性集**，用 3 条 `Regex` 足够，**不需要 XML 库、不加依赖**。

---

## 2. 解析器（最小，纯测试侧）

位置：`ComposerBandDeviceTruthTest.kt:78-138`（隔离副本里也有一份完全自带的同款，见 §6.2）。

### 2.1 按**结构角色**定位，不用坐标

这是 A 的核心：**坐标是这个帧的偶然结果，角色才是可复用的契约**。换一个帧，解析器会报出新的真实角色。

| 角色 | 判别式 | 本帧命中 |
|---|---|---|
| 编辑器 | 唯一的 `android.widget.EditText` | `[24,214][405,262]` h=48 |
| **带的纵向跨度** | 编辑器自己的顶沿与高（编辑器是带里**满高**的孩子：`Composer.kt:2327-2334` 量它时 `maxHeight = band`） | `214 .. 262` |
| 控件（48dp **语义**框） | `clickable="true"` ∧ 顶沿落在带跨度内 ∧ 不是编辑器 | `Add [399,215][441,263]`、`Model [441,215][559,263]`、`Stop [1316,215][1364,263]` —— **恰好 3 个** |
| 控件自身的**布局**框 | 顶沿落在带跨度内的 `android.widget.HorizontalScrollView` | `[441,228][1316,249]` h=21 |
| 宿主盒（pane） | 跨越带顶沿的满宽 `android.view.View` 中，**底沿最小**（最紧的那层） | `[0,162][1366,261]` → `paneBottom 261` |

**带跨度用「顶沿落在带内」而不是「中点落在带内」**不是随手选的：本帧里滚动条滑块
`[1350,192][1366,240]`（`clickable=true`，h=48）的中点 216 落在 `214..262` 内，
会被中点判据**误纳**，并把 `controlTop` 拉到 192；按顶沿判据它（192 < 214）被正确排除。

### 2.2 一处**被测试抓出来的真错**（未抹）

`paneBottom` 最初写成"跨越带顶沿的满宽 `android.view.View`"，**第一次跑就红了**：

```
theParsedFrameReproducesTheArchivedDeviceRegisterVerbatim FAILED
java.lang.AssertionError: v139 hard link: expected exactly 1 device node for
[the host / pane box], found 3 — [FrameNode(cls=android.view.View, …, width=1366, top=0, bottom=600),
 FrameNode(cls=android.view.View, …, width=1366, top=0, bottom=600),
 FrameNode(cls=android.view.View, …, width=1366, top=162, bottom=261)]
```

两个窗口根 `[0,0][1366,600]` 的 class 也是 `android.view.View`（我此前的人工清点
把它们当成根节点过滤掉了，**没发现**）。改成"底沿最小者 + 平局即红"后唯一命中 261。
**这次红灯正是硬连接在起作用**：如果读数是手抄的，这个错会被抄的数永久掩盖。

### 2.3 反软着陆：缺失即红

```kotlin
private fun red(why: String): Nothing = throw AssertionError(why)
```

`read()` / `parseNodes()` / `one()` / `paneBottom` **全部**走 `red()`：
文件不在 → 红并写明"期望路径 + 试过哪三个相对路径 + 为什么这必须是失败而不是跳过"；
节点数 0 → 红；某角色 0 个或 ≥2 个 → 红并打印候选集；pane 底沿平局 → 红。

**没有 `Assume`、没有 `assumeTrue`、没有默认值、没有 `?: 0` 兜底、没有 `@Ignore`、没有"文件不存在就跳过"。**
（`red()` 写成返回 `Nothing` 而不是 `Assert.fail()`，因为 JUnit 的 `fail()` 在 Kotlin 里
返回 `Unit` 而非 `Nothing`，无法用在 `?:` 表达式位置。）

---

## 3. 模型 vs 真机：**逐项对照**（v139 从 XML 解析得到）

`model` = 生产函数 `SessionPaneLayout.composerBandChildTopPx` /
`composerBandUnionBottomPx`；`device` = 从 `v137_BARON_M.xml` 解析。
带的实测量由**编辑器自己的框**给出：`bandTop = 214`、`bandPx = 48`（与 `BAND_HEIGHT` 48dp 一致）。
`toolRowPx` 取 `bandPx`（见 §3.2 唯一一处设备不可观测的输入）。

| # | 量 | 模型 | 真机 | 一致？ |
|---|---|---|---|---|
| 1 | 带顶沿 | 214（构造成基准） | **214** | ✅ |
| 2 | 带高 | 48 | **48** | ✅ |
| 3 | 编辑器顶沿（相对带） | **0** | **0** | ✅ |
| 4 | 工具行顶沿（相对带） | **0** | **0** | ✅ |
| 5 | **合账底沿 `unionPx`** | **48** | **49** | ❌ **模型乐观 1px** |
| 6 | **两子节点顶沿差 `topDelta`** | **0** | **1** | ❌ **模型乐观 1px** |
| 7 | 控件自身布局顶沿（相对带） | 树里写 `Top` ⇒ **0** | **14** | ❌ **树 ≠ 帧** |
| 8 | 判据 ①（合账底沿 − 宿主底沿） | 模型无此项 | **2** | ❌ 模型缺项 |

**8 项中 5 项一致、3 项不一致。** 三条不一致都不是"抄错数"，是结构性缺口：

- **#5 / #6 的 1px**：`place()` 之下、material3 的
  `minimumInteractiveComponentSize` 把 21 高的**布局**框向两侧撑到 48dp 并**居中**
  ⇒ 语义框顶沿 `228−13 = 215`、底沿 `249+14 = 263`。
  `composerBandUnionBottomPx` 只有「可摆放的底沿」这一项，**没有"语义扩张"这一项**
  ——不是算错，是**缺项**（v135 §1.6 / v138 §4.1 的结构性问题，第 N 次出现）。
- **#7 的 0 vs 14**：`Composer.kt:2210` 现在写的是
  `verticalAlignment = if (minimal) Alignment.Top else Alignment.CenterVertically`，
  而 `Alignment.Top.align(size, space)` **对每个孩子恒返回 0**。
  帧里的 14 = `roundToInt((48−21)/2f)` = `CenterVertically` 的偏移。
  **⇒ 产生该帧的代码不是树里这份。** 这条把 v138 §5.3 那个诚实的边界
  （静态只能证"帧是居中分支的算式"）**升级成一条会红的断言**。

### 3.2 一处**模型自己编的输入**，已点名

`toolRow.height` **在帧里不存在**：工具行挂 `Modifier.height(BAND_HEIGHT)`
（`Composer.kt:2189`），**没有自己的语义节点**，`uiautomator` 永远不会打印它。
v138 §3 论证它被强制成恰好 `band`。**这是整个对照里唯一一处设备不可观测的输入**，
所以它没有藏起来，而是由
`theModelsOnlyUnsourcedInputIsTheBandItselfAndThatIsPinned` 单独钉住。

### 3.3 为什么第 8 项不参与判定

宿主盒底沿 261 是**宿主**的量，不经 `composerBandUnionBottomPx`；
`ComposerPaneBottomCriterionV108Test` 已经对它判过一次。放进本对照只会重复，
所以只在闸门类里作为**设备侧派生一致性**（`49 − 48`、`263 − 261`）复核，不进隔离红类。

---

## 4. C：真机探针（1 行生产代码，零行为变化）

### 4.1 位置与 diff

授权只允许改 `Composer.kt`（`SessionPaneLayout.kt` 不可写），Lead 允许"或 v138 指出的**等效位置**"。
`record()` 的**调用点** `Composer.kt:2389` 就在那个 `layout` 块里、**真机每次布局都经过**，
而且比 `record()` 本身更强：`record()` 只拿到 `unionBottom` / `band`，
**探针还能把 `editorTop` / `toolRowTop` / 两个孩子的实测量一起打出来** —— 正是判 #5/#6 要的。

```diff
--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt
@@ -2387,6 +2387,8 @@ private fun ComposerArrangement(
                 // so a placement regression is visible to the suite, not only to a
                 // device frame.
                 SessionComposerBandUnion.record(unionBottom = unionBottom, bandPx = band)
+                // v139 probe: behaviour-free; prints what THIS layout pass actually used (PIN_DEVICE_HARDLINK.md §3).
+                android.util.Log.i("BandUnion", "minimal=$minimal bandPx=$band editorTop=$editorTop editorH=${editor.height} toolRowTop=$toolRowTop toolRowH=${toolRow.height} unionBottomPx=$unionBottom")
                 // v104: the union (`unionPx`, the rectangle 真机 measures) is bounded
                 // by the band **by construction** — `maxHeight = band` on both
                 // children above is the Compose contract, and `editorTop` is a
```

- `git diff --stat`：**`1 file changed, 2 insertions(+)`** —— 1 行代码 + 1 行注释，**零删除、零修改**。
- **未加 import**：用全限定名 `android.util.Log.i`，就是为了守住"只加 1 行"。
- **零行为变化**：只调用 `Log.i`，不写任何 `@Volatile` 字段、不改任何 `place`、不改任何 `val`。
  唯一代价是每次布局多一次 I/O —— §4.2 的 `-t` 就是为它准备的。

### 4.2 抓取命令（**必须带 `-t`**）

```powershell
# ① 先清空，再让真机走到 600 档「队列条在屏 / 一行形态」，然后有界地捞：
adb -s emulator-5554 logcat -c
#   …触发：开队列条 / 缩到 600 档 / 打开会话，让 ComposerArrangement 的 minimal 分支布局一次…
adb -s emulator-5554 logcat -d -t 200 -v threadtime -s BandUnion:I

# ② 只想看 union 是否达标（一条就够）：
adb -s emulator-5554 logcat -d -t 50 -v threadtime -s BandUnion:I | Select-String "unionBottomPx"

# ③ 顺带看这份构件的 md5（§4.3 的时间序证据）：
adb -s emulator-5554 shell md5sum /data/app/*/com.xiufeigo.opencodeshell*/base.apk
```

> **无 `-t` 会挂死**：`logcat` 不带条数上限会一直跟随流，本模拟器上必然超时。
> `-d` = dump 后退出（不跟随）；`-t N` = 只取最后 N 条（**有界**）；`-s BandUnion:I` = 只留该 tag。

### 4.3 期望看到的字段

一行，tag 固定 `BandUnion`：

```
I/BandUnion: minimal=true bandPx=48 editorTop=0 editorH=48 toolRowTop=0 toolRowH=48 unionBottomPx=48
```

| 字段 | 期望（v136 生效 + 满高子节点） | 若读到别的，说明什么 |
|---|---|---|
| `minimal` | `true` | `false` ⇒ 这一帧根本不是一行形态，整份读数不可用 |
| `bandPx` | `48` | ≠48 ⇒ 档位/密度不对 |
| `editorTop` | `0` | ≠0 ⇒ 源码或数据被改过 |
| `editorH` / `toolRowH` | `48` / `48` | 不等 48 ⇒ v138 §3 的"退化到常量"前提不成立 |
| `toolRowTop` | `0` | ≠0 ⇒ `composerBandChildTopPx` 行为变了 |
| **`unionBottomPx`** | **`48`**（达标） | **`49` ⇒ 复现缺陷；`>48` ⇒ 比 v137 更差** |

**这张表一眼回答 v138 §5.3 那个悬案**：

- 探针**有输出** ⇒ v136 的代码**在运行的进程里**；若此时 `unionBottomPx=48` 但
  `uiautomator` 仍读 49，那 1px 就被**证明**产生在 `place()` 之下（语义扩张），
  **归因彻底闭环**，下一步只能动模型（补"语义扩张"项）或动 `Alignment`。
- 探针**无输出**（`logcat -s BandUnion` 空）⇒ **这份代码不在进程里** ——
  v138 §5.3 剩下的唯一解释当场坐实，与算式指纹**互不依赖**的第二条独立证据。

### 4.4 探针的位置被 pin 住了

`theBandUnionProbeIsInPlaceOnTheLivePlacementPath` 断言三件事：
`record(...)` 那一行仍在、tag 必须是 `"BandUnion"`、四个关键字段
（`bandPx=$band` / `editorTop=$editorTop` / `toolRowTop=$toolRowTop` / `unionBottomPx=$unionBottom`）
都在。**探针被人静默删掉时，闸门会红。**

---

## 5. A 的两半：证据进闸门，判定隔离

这是本轮的核心工程决定，先讲清楚**为什么必须拆**。

**问题**：A 要求 pin 断言"模型答案 == 真机答案"，而**这条断言现在就该红**（48 vs 49）；
同时 Lead 明确要求 `:app:testDebugUnitTest` **不能变红**，且不许 `@Ignore`、不许改
`app/build.gradle.kts`。这三条同时成立，**在同一个已编译的测试类里无解** ——
`@Ignore` 被否（丢触发器 + 减用例数），`assumeTrue` 会被报成 *skipped*（同样丢触发器），
而 `@Test` 一旦可执行就必然把 task 拖红。

**唯一同时满足三条的解**：把"会红的判定"放到**任何已编译源集之外**，
同时让"会红的证据"进闸门。拆成两半：

| | 文件 | 编译？ | 颜色 | 用例 |
|---|---|---|---|---|
| **证据半** | `app/src/test/java/…/ComposerBandDeviceTruthTest.kt` | 是（主闸门） | **绿** | 7 |
| **判定半** | `research/v97_fix/quarantine/ModelVsDeviceMismatchTest.kt` | **否** | **红（3/3）** | 4（不计入闸门） |

### 5.1 证据半（绿，7 例）做了什么

1. `theDeviceFrameIsPresentAndEveryRoleInTheClaimIsFoundInIt` —— 66 节点、band 48、
   控件恰好 3 个且都 48 高且共底沿、布局 21 高。
2. `theParsedFrameReproducesTheArchivedDeviceRegisterVerbatim` —— 解析结果逐位等于
   v138 手工寄存器（261/214/214/262/215/263/228/49/1/2）。
   **这同时证明了 `ComposerBandUnionV136Test` 里那十二个手抄数是"读数"而不是"发明"**。
3. `theModelIsOnePixelOptimisticAboutTheUnionBottomOnTheRealFrame` —— §3 的 #5：
   断言模型 48、设备 49，并**断言间距恰好是 1**。间距变 0 ⇒ 绿消失，红。
4. `theModelAlsoCallsTheControlsTopAlignedWhereTheDeviceReadsOnePixelLower` —— §3 的 #6。
5. `theModelHasNoTermForTheFourteenPixelInnerOffsetTheDeviceApplies` —— §3 的 #7
   的算式部分（14 = 扩张 27 = 48−21，居中拆 13/14）。
6. `theModelsOnlyUnsourcedInputIsTheBandItselfAndThatIsPinned` —— §3.2 唯一的编造输入。
   并明写"v105 的 `unionPx <= bandPx` 是**模型**性质，不是设备事实"（设备是 49 > 48）。
7. `theBandUnionProbeIsInPlaceOnTheLivePlacementPath` —— §4.4。

**关键**：即使判定半被隔离，**间距 `deviceUnionPx - modelUnionPx == 1` 仍在闸门里被断言**。
"模型比真机乐观 1px"这件事**不会因为隔离而失踪**；隔离的只是
"因此判失败"这个动作。

### 5.2 判定半（红，3 条 parity + 1 条证据）

三条断言的形状**只有一种**：`assertEquals("…设备读到的…", deviceValue, modelValue)` ——
**检测到不一致即失败**，无缺口、无兜底：

| 用例 | 比什么 | 现在 |
|---|---|---|
| `theDeviceReadingsAreTheArchivedFrameAndNotAStaleCopy` | XML 逐位等于归档寄存器 | ✅ 绿（先钉证据，比完再钉判定） |
| `theModelsUnionBottomMustEqualTheDevices` | 49 vs **48** | ❌ **红** |
| `theModelsChildTopDeltaMustEqualTheDevices` | 1 vs **0** | ❌ **红** |
| `theModelsInnerChildTopMustEqualTheDevices` | 14 vs **0**（树里的 `Top`） | ❌ **红** |

失败消息原文（`_v139_run_red.log` / 结果 XML）：

```
PARITY 1/3 — … 模型比真机乐观 1px, 待修 Composer.kt:2210 … expected:<49> but was:<48>
PARITY 2/3 — … 模型比真机乐观 1px, 待修 Composer.kt:2210 … expected:<1> but was:<0>
```

### 5.3 一处**我自己的错，已自查推翻**（未抹）

判定半的 PARITY 3/3 第一版，我拿"设备内层偏移 14"去比"模型的**居中**分支 14" ——
**结果它是绿的**。绿完之后我复核了算式，发现这个绿**没有信息量**：
14 与 `CenterVertically` 一致，只说明"这个偏移**看起来像**居中"，并不能区分
"哪一支渲染的帧" —— 而 v138 早就说过（§4.2）算式指纹只排除 `Alignment.Top`，不指认进程。

于是把它改成真正的第三条不一致：**树里声明的分支（`Top` ⇒ 0）vs 设备（14）**，
并顺手加一条 `assertTrue` 先确认源码仍写着那行 `minimal` 分支，
免得源码改了以后这条断言在测空气。现在 **3/3 红**。

---

## 6. 请 Lead 裁定：红放哪

**事实**：主闸门 `:app:testDebugUnitTest` **绿**（51 例 / 0 失败 / 0 跳过）；
判定半 **3 条红**，已实测（复制进源集 → 跑 → 红 → 撤掉，全程留痕）。

**待裁定的就一件事：判定半的归属。** 我给三个选项，各有代价：

| 选项 | 做法 | 好处 | 代价 |
|---|---|---|---|
| **甲（当前状态，我建议）** | 判定半留在 `research/v97_fix/quarantine/`，**不编译**；证据半进闸门并断言间距仍为 1 | 主闸门不被本任务拖红；证据与间距不丢；零 build 改动；Lead 随时可用一条 `Copy-Item` 收编 | 判定本身**不自动执行**（要人工记得收编） |
| 乙 | 收编进源集，接受主闸门红 | 判定**每跑必红**，触发器最强 | 与"不得让主闸门变红"直接冲突；会阻塞其它所有工作 |
| 丙 | 在 `build.gradle.kts` 里加 `excludeTestsMatching` / 独立 task | 判定仍在源集里 | **`build.gradle.kts` 不可写**（本轮授权外）；需 Lead 单独放权 |

**收编命令（选甲→丙的过渡，或将来修好后）**：

```powershell
Copy-Item research\v97_fix\quarantine\ModelVsDeviceMismatchTest.kt `
         app\src\test\java\com\xiufeigo\opencodeshell\ModelVsDeviceMismatchTest.kt
.\gradlew :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ModelVsDeviceMismatchTest"
```

判定半**自带解析器、不引用证据半的任何东西**，所以这是一次**单文件复制、零伴随编辑**，
也不会被证据半的改动连带弄坏。`Composer.kt:2210` 修好 + 新 dump 读出 48/0 之后，
收编即可直接变绿。

---

## 7. 为什么不降强度

| 诱惑 | 本轮处置 |
|---|---|
| 删掉 v138 的手抄寄存器，改成"读 XML"就算数 | **没删**。`ComposerBandUnionV136Test.kt` **一个字节未动**（8 例原样跑，`git status` 里它根本不在改动列表）。读取数是**新增**的硬连接，不是替换。 |
| 把 `assertEquals(1, devUnionPx - modelUnionBottom)` 改成 `assertTrue(devUnionPx >= modelUnionBottom)` | **没改**。`1` 仍是精确值，且现在这个 `1` 的**两侧都是从文件解析出来的**，比 v138 的手抄版更硬。 |
| `@Ignore` 掉会红的那条 | **D 否，未采纳**。它丢触发器、减用例数，正是五次假绿的成因之一。 |
| `assumeTrue(设备读数存在)` | 会被报成 *skipped*，效果同 `@Ignore`。**未用**；缺失一律 `AssertionError`。 |
| 用 `expectThrows` / `try-catch` 把红包起来 | **未用**。那会把"红"变成"绿且假装红"，比 `@Ignore` 更坏。 |
| 把探针塞进 `if (BuildConfig.DEBUG)` 之类 | 探针要的就是真机可读；加了条件反而读不到。全限定 `android.util.Log.i` 连 import 都不动。 |
| 断言被放宽 / 删除 | **零**。全仓仅 1 个生产文件被改，且是 `+2 / -0`。 |

---

## 8. 测试证据

### 8.1 命令

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.ComposerBandDeviceTruthTest" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandUnionV136Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBarOnScreenCriterionV135Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerPaneBottomCriterionV108Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerChildUnionV104Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTouchTargetV105Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTallEnoughV106Test"
```

### 8.2 原文（`research/v97_fix/_v139_run3.log` 尾部）

```
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 44s
52 actionable tasks: 2 executed, 50 up-to-date
```

### 8.3 用例数（`app/build/test-results/testDebugUnitTest/*.xml` 逐类读出）

| 类 | 用例数 | 失败 | 错误 | 跳过 | vs v138 |
|---|---|---|---|---|---|
| **`ComposerBandDeviceTruthTest`（本轮新增）** | **7** | 0 | 0 | 0 | **+7** |
| `ComposerBandUnionV136Test` | 8 | 0 | 0 | 0 | 8 → 8（逐字未动） |
| `ComposerBarOnScreenCriterionV135Test` | 7 | 0 | 0 | 0 | 7 → 7 |
| `ComposerPaneBottomCriterionV108Test` | 9 | 0 | 0 | 0 | 9 → 9 |
| `ComposerChildUnionV104Test` | 7 | 0 | 0 | 0 | 7 → 7 |
| `ComposerBandTouchTargetV105Test` | 6 | 0 | 0 | 0 | 6 → 6 |
| `ComposerBandTallEnoughV106Test` | 7 | 0 | 0 | 0 | 7 → 7 |
| **合计（进闸门）** | **51** | **0** | **0** | **0** | v138 基线 44 ⇒ **净增 7** |
| `ModelVsDeviceMismatchTest`（**隔离，不编译**） | 4 | **3** | 0 | 0 | 故意红 |

**新类 7 条用例名**（确认**真跑**、非跳过）：

```
theDeviceFrameIsPresentAndEveryRoleInTheClaimIsFoundInIt
theParsedFrameReproducesTheArchivedDeviceRegisterVerbatim
theModelIsOnePixelOptimisticAboutTheUnionBottomOnTheRealFrame
theModelAlsoCallsTheControlsTopAlignedWhereTheDeviceReadsOnePixelLower
theModelHasNoTermForTheFourteenPixelInnerOffsetTheDeviceApplies
theModelsOnlyUnsourcedInputIsTheBandItselfAndThatIsPinned
theBandUnionProbeIsInPlaceOnTheLivePlacementPath
```

### 8.4 判定半的实测红（`_v139_run_red.log`）

```
ModelVsDeviceMismatchTest > theModelsUnionBottomMustEqualTheDevices FAILED
    java.lang.AssertionError at ModelVsDeviceMismatchTest.kt:177
ModelVsDeviceMismatchTest > theModelsChildTopDeltaMustEqualTheDevices FAILED
    java.lang.AssertionError at ModelVsDeviceMismatchTest.kt:194
ModelVsDeviceMismatchTest > theModelsInnerChildTopMustEqualTheDevices FAILED
    java.lang.AssertionError at ModelVsDeviceMismatchTest.kt:235

4 tests completed, 3 failed
BUILD FAILED in 3s
```

跑完即撤掉副本（`Test-Path` 复核 = `False`），§8.2 的绿是撤掉之后重跑的结果。

### 8.5 过程里 3 次真实红灯，未抹

1. `_v139_run1.log` —— `paneBottom` 角色判据不唯一（§2.2），**被硬连接当场抓出来**。
2. `_v139_run_red.log`（第一版）—— 判定半 PARITY 3/3 **是绿的**（§5.3 的自查推翻）。
3. `_v139_run_red.log`（修正版）—— 3/3 红，即上表。

### 8.6 合规

只跑 `--tests`；未 `assembleDebug` / `clean` / 全量 / 装机 / 杀进程；
未新增用户可见字符串；**未放宽或删除任何断言**；用例数未减少（44 → 51）；
**生产代码仅 `Composer.kt` `+2 / -0`**；`_upstream/`（只读）、`app/build.gradle.kts`、
`ChatViewModel.kt`、`SessionPaneLayout.kt`、其余生产文件**零改动**；
行尾全程只用编辑/写入工具，`Composer.kt` 仍为 **3107 行裸 LF / 0 CRLF**，未用 git 回退改行尾。

---

## 9. 改了哪些文件

| 文件 | 性质 | 幅度 |
|---|---|---|
| `app/src/test/java/com/xiufeigo/opencodeshell/ComposerBandDeviceTruthTest.kt` | **测试（新增，进闸门）** | 新文件，7 例：XML 解析器 + 8 项对照 + 探针 pin |
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt` | **生产（唯一改动）** | **+2 / −0**（1 行 `Log.i` + 1 行注释），`@2390` |
| `research/v97_fix/quarantine/ModelVsDeviceMismatchTest.kt` | 测试（**隔离，不编译**） | 新文件，4 例（3 条故意红） |
| `research/v97_fix/PIN_DEVICE_HARDLINK.md` | 本报告 | 先写盘抢锁 → 定稿释放 |
| `research/v97_fix/_v139_run1.log` / `_v139_run2.log` / `_v139_run3.log` / `_v139_run_red.log` | 运行日志 | 含 3 次真实红灯 |

**未触碰**：`SessionPaneLayout.kt`（含 `record()` 本体 —— 故探针取其**调用点**这个等效位置）、
`app/build.gradle.kts`、`ChatViewModel.kt`、`_upstream/`、`ui/servers/**`、`ui/home/**`、
`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`、以及
`ComposerBandUnionV136Test.kt` 及其它既有 pin（**零改动**）。

---

## 10. 未闭环

1. **"当前必然红"的归属待 Lead 裁定**（§6 甲/乙/丙）。本轮按甲执行：主闸门绿、判定隔离。
2. **`toolRow.height` 设备不可观测**（§3.2）。帧里没有工具行自己的节点，
   对照只能喂 `bandPx`。要彻底消除这条编造输入，需要 §7-B
   （把 alignment 规则也提成生产函数）或一次能直接读布局的 instrumented 采集。
3. **判据 ① 的第二个 1px（位置性越界 `push 13 > padding 12`）原样留账**，
   归因不变（v138 §9-3）。本对照只覆盖并集那一项。
4. **`Alignment.Top` 的顶沿越界 13px 未裁定**（v138 §5.4）。即使 `:2210` 生效，
   48dp 触摸靶会变成 `[201, 249]`，高出带顶沿 13px。**这属设计裁定，不在本轮授权内**。
5. **探针未上真机**（§4）。本轮无装机授权，**`unionBottomPx` 的真机值尚未取过**。
   首次取数即需带时间戳的装机 + 重启 + 同形态重 dump，并把 `md5sum` 与 dump 时刻一起记下。
6. **只有 600 档条在屏这一帧**成对存在 21/48 两个框（v138 §9-6）。
   768 档、条关形态是否同源**未取样**，解析器的角色判据在那些帧上是否仍唯一命中**未验证**。
7. **解析器只认这一代 `uiautomator` 的扁平属性序**。换格式（带换行/缩进、带命名空间、
   Android 14+ 的 `compressed` 输出）需重新验证 —— 届时 7 条里第 1、2 条会**先红**，
   这是设计意图，不是回归。
