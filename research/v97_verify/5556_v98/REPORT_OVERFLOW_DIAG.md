# composer 越界 +5px —— 诊断报告（钉死「验收量」到底是什么）

- 诊断工程师：mavis（本轮**只做诊断**）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 设备：**emulator-5556** / `Medium_Desktop` / 1366x768@160 / SDK 34
- 证据目录：`research/v97_verify/5556_v98/dg/`
- **未改任何代码、未跑 gradle、未装机、未杀进程**（仅 `am start` 起本 app）

> ## 状态：**未 BLOCKED（构建逐位命中）｜ 结论 = ① 真越界（+5 在 app 自己的语义下成立）**
> ## 附带坐实：v103 / v104 两次修复都没打中，是因为**两处"死代码"**（见 §C.4 / §C.5）

> | 项 | 结论 |
> |---|---|
> | 构建核对 | ✅ md5 `bd36719d4fc062322d525bd70635e681` + `lastUpdateTime 2026-10-03 10:35:14` **逐位命中** |
> | 设备归属 | ✅ 全程 `OcActivity`；`pidof=30041` **恒定未重启**（入测时焦点在 `com.google.android.calendar`，已记录并用 `am start` 本 app 取回） |
> | **"+5 是不是跨容器比较的产物"** | ❌ **不是**。app 自己的 pane 底沿（213）与宿主盒底沿（213）**与复验员取的那个 213 同值** ⇒ `+5` 在 app 语义下**照样成立** |
> | **复验员那个"pane"是什么** | ⚠️ 节点里装着消息节点（`10:51 AM` / `Revert message`）⇒ **它是时间线/视口盒，不是 pane**；数值巧合相等（见 §C.3） |
> | **app 语义下是否真越界** | ✅ **真越界 5px**：`unionBottom 218 > hostBottom 213 = paneBottom = imeTop` |
> | **越界的是谁** | `Add` / `Stop`(Send) / `Model` chip 三个 `clickable` 节点，各 `h=48`（48dp 最小触摸靶）**装在 44dp 的带里**，居中后底沿穿出 **4px**，加 1px 亚像素取整 = **5px** |

---

## 1. 环境读数

| 项 | 派单要求 | 设备实测 | 判定 |
|---|---|---|---|
| `base.apk` md5 | `bd36719d4fc062322d525bd70635e681` | **`bd36719d4fc062322d525bd70635e681`** | ✅ 逐位一致 |
| `lastUpdateTime`（设备时） | `2026-10-03 10:35:14` | **`2026-10-03 10:35:14`** | ✅ 逐位一致 |
| APK 路径 | — | `/data/app/~~z55Z9pslFKNhiC2pDiALug==/com.xiufeigo.opencodeshell-dlWDSP3kxw1xegx5OoOJfg==/base.apk` | — |
| `versionCode` / `versionName` | — | `26` / `1.4.0` | — |
| md5 取值方式 | — | **设备侧 `md5sum`**（不是本地重打包） | 归属被测构建 |
| `show_ime_with_hard_keyboard` | — | `1` | — |

> **md5 与 `lastUpdateTime` 双闸均逐位命中 ⇒ 未 BLOCKED，全部读数归属被测构建。**

### 1.1 设备归属（含一处必须记录的漂移）

| 时点 | `mCurrentFocus` | `pidof` | 结论 |
|---|---|---|---|
| 入测（原始） | **`com.google.android.calendar/…WhatsNewFullScreen`** | `30041`（本 app 进程在，但焦点在别的 app） | ⚠️ **非本 app 窗口**，已记录 |
| `am start` 本 app 后 | `…/OcActivity` | **`30041`** | ✅ 取回本 app |
| 每次交互前硬闸 | `…/OcActivity` | `30041` 恒定 | ✅ 全程本 app，**进程零重启** |
| 清理阶段的弹层 | `Window{af851a Pop-Up Window}` | 同上 | ✅ **已验归属本 app**：`package=com.xiufeigo.opencodeshell`、`mOwnerUid=10189`、`mParentWindow=…OcActivity`、`Session{… 30041}` ⇒ 是本 app 自己的 `APPLICATION_SUB_PANEL`，不是外来窗口 |

> 未按 BACK(4)；未在弹层内按 ESC(111)；未在 launcher/日历上做任何点击（只用 `am start` 拉起**本 app**）。

### 1.2 入测/收尾环境

| 项 | 值 |
|---|---|
| `wm size` | `Physical 3840x2160` / **`Override 1366x768`** |
| `wm density` | `Physical 320` / **`Override 160`** |
| 主判据档 | `wm size 1366x600` + `wm density 160`（纪律：两者必须配对） |
| `mInputShown` | **`true`**（每组高度读数前均断言，取自 `dumpsys input_method`） |
| IME `mFrame` | `[0,213][1366,600]` ⇒ **imeTop = 213**（600 档） |
| 帧稳定性 | 连读 2 帧（`s4.xml` / `f2.xml`）**逐字节相同** |

---

## 2. A —— 节点树完整枚举（`1366x600` + IME）

路径为 `R/0/0/k`（`R` = 根）。`click` = `clickable`。截图见 `dg/dg_600ime.png`。

| # | 路径 | class | text / content-desc | resource-id | bounds | click | 归属 |
|---|---|---|---|---|---|---|---|
| 1 | `R` | FrameLayout | — | — | `[0,0][1366,600]` | false | 系统根 |
| 2 | `R/0/0` | View | — | — | `[0,0][1366,600]` | false | 根容器 |
| 3 | `R/0/0/2` | View | — | — | `[0,0][1366,600]` | false | **app 内容根**（composer 的直接父） |
| 4 | `R/0/0/0` | Button | `Home` | — | `[8,30][56,78]` | true | 顶栏 |
| 5 | `R/0/0/1` | HorizontalScrollView | — | — | `[62,40][512,68]` | false | tab 条 |
| 6 | `R/0/0/1/0/1` | TextView | `Reverify Composer Overflow in E2ECHK5556u1h v104` | — | `[90,46][250,62]` | false | **他人会话，未动** |
| 7 | `R/0/0/1/1/1` | TextView | `Overflow diagnostic probe E2ECHK5556dg` | — | `[320,46][480,62]` | false | **本轮自建会话** |
| 8 | `R/0/0/4` | View | — | — | **`[0,162][1366,213]`** h=**51** | false | ⭐ **复验员叫它 "pane"** |
| 9 | `R/0/0/4/0` | TextView | `10:51 AM` | — | `[1232,163][1280,178]` | false | ⭐ **消息节点 ⇒ #8 是时间线** |
| 10 | `R/0/0/4/1` | Button | `Revert message` | — | `[1280,158][1314,182]` | true | ⭐ 消息悬浮操作 |
| 11 | `R/0/0/4/2` | Button | `Copy message` | — | `[1314,158][1362,182]` | true | ⭐ 消息悬浮操作 |
| 12 | `R/0/0/5..13` | Button/View | `Back` / `Open sessions` / 会话名 / `Search session` / `Ask a side question` / `Session summary` / `Context usage` / `More options` / `Toggle review panel` | — | `[0,110][1366,162]` 一带，h=48 | true | 二级工具条（**在 pane 之上**） |
| 13 | `R/0/0/14` | Button | **`Open terminal`** | — | `[1320,184][1366,232]` h=48 | true | ⚠️ **悬浮按钮，不是 composer 子节点** |
| 14 | `R/0/0/15` | **EditText** | —（hint `Ask anything…`） | — | **`[24,169][405,217]`** h=**48** | true | ⭐ composer 主体 |
| 15 | `R/0/0/15/0` | TextView | `Ask anything, / for commands, @ for context...` | — | `[24,183][315,203]` | false | hint |
| 16 | `R/0/0/15/1` | View | — | — | `[24,183][114,198]` | false | hint 内图标 |
| 17 | `R/0/0/16` | Button | **`Add`**（⊞） | — | **`[399,170][441,218]`** h=**48** | true | ⭐ composer 控件 |
| 18 | `R/0/0/17` | HorizontalScrollView | — | — | `[441,182][1316,205]` h=28 | false | chip 行 |
| 19 | `R/0/0/17/0` | View | — | — | **`[441,170][604,218]`** h=**48** | true | ⭐ `Model` chip 容器（clickable，48） |
| 20 | `R/0/0/17/0/0` | View | `Model` | — | `[441,182][604,205]` h=28 | false | chip 标签 |
| 21 | `R/0/0/17/0/1` | TextView | `Space Bunny Free` | — | `[467,186][582,202]` | false | chip 文本 |
| 22 | `R/0/0/18` | View | **`Stop`**（运行中，发送键变体） | — | **`[1316,170][1364,218]`** h=**48** | true | ⭐ composer 控件 |

> **`resource-id` 全树为空**（Compose 语义树不吐 id）—— 判据里"若有 resource-id"这一项对本 app 不适用，只能靠 `content-desc` / 层级 / bounds 认节点。

### 2.1 层级深度（关键结构）

```
R/0/0/2  ← app 内容根 [0,0][1366,600]
├── R/0/0/4   View [0,162][1366,213]   ← 复验员的 "pane"，**内含消息节点** ⇒ 时间线/视口
│   ├── 10:51 AM / Revert message / Copy message
├── R/0/0/14  Button Open terminal [1320,184][1366,232]   ← 悬浮按钮
├── R/0/0/15  EditText  [24,169][405,217]   ┐
├── R/0/0/16  Button Add [399,170][441,218]   ├ composer 三个子节点
├── R/0/0/17  chip 行                          │
└── R/0/0/18  Stop     [1316,170][1364,218]  ┘
```

> ⭐ **composer 三个节点与 #8 是兄弟，不是它的孩子。** 这一条决定了 §B 的全部候选口径。

### 2.2 `1366x768` + IME 对照（`mInputShown=true`）

| 节点 | bounds | h |
|---|---|---|
| 复验员"pane" | `[0,162][1366,302]` | 140 |
| `EditText` | y=208..256 | 48 |
| `Add` / `Stop` / `Model` chip | y=248..296 | 48 |
| chip 行 `HorizontalScrollView` | y=258..286 | 28 |
| `Open terminal` | y=192..240 | 48 |
| 并集 | y=208..296 | 88 |

---

## 3. B —— 候选定义 × 底沿 × 差值（`1366x600` + IME）

### 3.1 底沿候选（`paneBottom` 家族）

| 代号 | 定义 | 底沿 | 来源 |
|---|---|---|---|
| **P1** | 复验员取的"pane" = `R/0/0/4` | **213** | 实测 `[0,162][1366,213]` |
| **P2** | **app 自己的 pane 盒**（`SessionScreen.kt:3337` `layout(...)`） | **213** | `paneTop 162 + panePx 51` |
| **P3** | composer 的**直接父盒** = `R/0/0/2` | **600** | 实测 `[0,0][1366,600]` |
| **P4** | 根 Box / 根 FrameLayout | **600** | 实测 |
| **P5** | **composer 宿主盒底沿**（a11y 无此节点，由算式导出） | **213** | `hostTop 169 + hostPx 44` |
| **P6** | **IME `mFrame` 顶沿** | **213** | 实测 `mFrame=[0,213][1366,600]` |

> **P1 = P2 = P5 = P6 = 213**（四个候选同值）；**P3 = P4 = 600**。

### 3.2 并集候选（`unionBottom` 家族）

| 代号 | 定义 | 底沿 | 高 |
|---|---|---|---|
| **U1** | `union(EditText, Add, Send/Stop)`（复验员口径） | **218** | 49（y=169..218） |
| **U2** | `union(所有 composer 子节点)`（含 `Model` chip `[441,170][604,218]`、chip 行） | **218** | 49 |
| **U2′** | `union(U2 ∪ Open terminal)` | **232** | 63 ⚠️ 见 §3.4 |
| **U3** | `union(所有 clickable)` | **232** | 63 ⚠️ 见 §3.4 |

### 3.3 差值表 —— **+5 出现在哪些组合**

| union ＼ paneBottom | **P1** 213 | **P2** app pane 213 | **P5** 宿主盒 213 | **P6** imeTop 213 | **P3** 父盒 600 | **P4** 根 600 |
|---|---|---|---|---|---|---|
| **U1** = 218（复验员口径） | **+5** ❌ | **+5** ❌ | **+5** ❌ | **+5** ❌ | −382 ✅ | −382 ✅ |
| **U2** = 218（全部 composer 子节点） | **+5** ❌ | **+5** ❌ | **+5** ❌ | **+5** ❌ | −382 ✅ | −382 ✅ |
| **U2′** = 232（+`Open terminal`） | **+19** ❌⚠️ | **+19** ❌⚠️ | **+19** ❌⚠️ | **+19** ❌⚠️ | −368 ✅ | −368 ✅ |
| **U3** = 232（所有 clickable） | **+19** ❌⚠️ | **+19** ❌⚠️ | **+19** ❌⚠️ | **+19** ❌⚠️ | −368 ✅ | −368 ✅ |

**768 档对照**（U1 = 296）：

| union ＼ paneBottom | P1 302 | P2 302 | P5 302 | P6 294 | P3/P4 768 |
|---|---|---|---|---|---|
| **U1** = 296 | **−6** ✅ | **−6** ✅ | **−6** ✅ | **+2** ❌ | −472 ✅ |

### 3.4 明确回答

1. **哪些组合是 `+5`**：`U1`/`U2` × `P1`/`P2`/`P5`/`P6` —— 即 **任何"composer 子节点并集"对"app 自己任何一个语义盒子"**，全部 `+5`。
2. **哪些是 `0` 或负**：对 `P3`/`P4`（内容根 / 根 Box，底沿 600）恒为 **−382**；768 档对 pane 系恒为 **−6**。
3. **⚠️ `+19` 是真正的"跨容器混节点"假阳性**：`Open terminal`（`R/0/0/14`，`[1320,184][1366,232]`）是**悬浮在右下角的终端按钮**，是 `R/0/0/2` 的直接孩子、**与 composer 三兄弟并列**，**不属于 composer 布局**。把它算进 `union(所有 clickable)`（U3）或 `union(所有 composer 子节点)`（U2′）会得到 **`+19`** —— 那是**选节点规则写错**造出来的数，不是缺陷。
4. **✅ 关键回答：`+5` 不是只在跨容器比较时出现。** 因为 app 自己的 pane 底沿（**P2 = 213**）与宿主盒底沿（**P5 = 213**）**恰好都等于 213**。换成 app 语义，差值**仍然是 `+5`**。
   ⇒ **"+5 是口径不匹配的产物"这个假设，本轮被否证。**
5. 唯一真正的口径瑕疵是**命名**：复验员把**时间线/视口**节点（P1）叫成了 "pane"（§C.3）。数值上它这次等于 pane，但那是**巧合**（§C.3 解释了为什么巧合成立、以及什么时候巧合会失效）。

---

## 4. C —— 代码锚判定：哪个定义才是 app 自己的语义

### 4.1 app 自己怎么摆 pane / viewport / host

`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt:3332-3340`（`SessionPaneArrangement` 的 measure policy 收尾）：

```kotlin
val pane = if (constraints.hasBoundedHeight) {
    constraints.maxHeight
} else {
    viewport.height + host.height
}
layout(constraints.maxWidth, constraints.constrainHeight(pane)) {
    viewport.place(0, 0)
    host.place(0, SessionPaneLayout.composerTopPx(pane, host.height))
}
```

配套函数（`ui/session/SessionPaneLayout.kt`）：

| 锚 | 原文 | 行号 |
|---|---|---|
| `viewportHeightPx`（floating 形态） | `fun viewportHeightPx(shared: Boolean, pane: Int, composer: Int): Int =`<br>`    if (!shared) pane else (pane - composer).coerceAtLeast(0)` | **991-992** |
| `composerHostCeilingPx` | `fun composerHostCeilingPx(pane: Int, floorPx: Int): Int =`<br>`    (pane - floorPx).coerceAtLeast(0)` | **1043-1044** |
| `composerHostHeightPx` | `fun composerHostHeightPx(panePx: Int, ceilingPx: Int): Int =`<br>`    ceilingPx.coerceAtMost(panePx.coerceAtLeast(0)).coerceAtLeast(0)` | **1202-1203** |
| `composerTopPx` | `fun composerTopPx(pane: Int, composer: Int): Int =`<br>`    pane.coerceAtLeast(0) - composerHostHeightPx(panePx = pane, ceilingPx = composer)` | **1278-1279** |

> ⭐ **app 自己认为 composer 的可用底沿 = 宿主盒（host）的底沿，而宿主盒是底对齐在 pane 底沿上的**：
> ```
> hostBottom = paneTop + composerTopPx(pane, hostPx) + hostPx
>            = paneTop + (panePx − hostPx) + hostPx
>            = paneTop + panePx = paneBottom          ← v103 的恒等式，成立
> ```
> 代码注释 `SessionPaneLayout.kt:1210-1211` 原文：*"宿主落在 pane 之内时它恒**等于** `pane`（底对齐，不留缝也不越界）"*。

### 4.2 本帧的逐步求值（**用实测反推，不套用复验员的 floor 模型**）

| # | 量 | 算式 | 值 | 依据 |
|---|---|---|---|---|
| 1 | `paneTop` | 二级工具条底沿 | **162** | 实测 `R/0/0/5..13` 底沿 162 |
| 2 | `imeTopPx` | IME `mFrame` 顶沿 | **213** | 实测 `mFrame=[0,213][1366,600]` |
| 3 | `panePx` | `213 − 162` | **51** | 实测 `R/0/0/4` h=51 |
| 4 | `paneBottom` | `162 + 51` | **213** | = P1 = P2 = P5 = P6 |
| 5 | `hostPx` | `band = min(TOOL_ROW_HEIGHT.roundToPx()=44, constraints.maxHeight)` | **44** | `Composer.kt:2221` |
| 6 | `hostTop` | `162 + (51 − 44)` | **169** | ✅ **与实测 `EditText` 顶沿 169 逐位吻合** |
| 7 | `hostBottom` | `169 + 44` | **213** | = `paneBottom` ⇒ **v103 恒等式成立** |
| 8 | `band`（真正装东西的带） | `min(44, 51)` | **44** | `Composer.kt:2221` |
| 9 | `bandMaxHeight`（v104 闸门读的数） | `BoxWithConstraints` 的 `maxHeight` = 宿主传进来的 `maxHeight` | **51** | `Composer.kt:2134` |
| 10 | 编辑器高 | 实测 | **48** | `EditText [24,169][405,217]` |
| 11 | 工具行高 | 实测 | **48** | `Add [399,170][441,218]` |
| 12 | **并集高** | `max(48, 48)`（+1px 取整 ⇒ 49） | **49** | 实测 y=169..218 |
| 13 | **`unionBottom − hostBottom`** | `169 + 49 − 213` | **`+5`** | ❌ |

> **关键反推：`floorPx ≤ 3`，不是复验员算的 51。**
> 由第 9/11 步反推：`Add`/`Stop` 实测仍 `h=48` ⇒ `bandMaxHeight(=51) ≥ 48` 成立
> ⇒ `hostPx ≥ 48` ⇒ `ceilingPx ≥ 48` ⇒ `51 − floorPx ≥ 48` ⇒ **`floorPx ≤ 3`**。
> 复验员取 `floorPx = 51` ⇒ 推出 `ceiling = 0` ⇒ 要求"宿主高必须为 0" ——
> **这个判据的输入模型就错了至少 48px，它在真机上永远不可能成立**（详见 §C.6）。

### 4.3 复验员的 `paneBottom` 对应哪个盒子 —— **口径不匹配（命名层）**

`R/0/0/4` 的**孩子是消息节点**（`10:51 AM` / `Revert message` / `Copy message`）⇒ 它是**时间线/视口**，不是 pane。

**为什么数值仍然相等（巧合成立的原因）**：`SessionPaneLayout.kt:991-992`
```kotlin
fun viewportHeightPx(shared: Boolean, pane: Int, composer: Int): Int =
    if (!shared) pane else (pane - composer).coerceAtLeast(0)
```
注释原文（`:988-989`）：*"The **floating form** gives the viewport the **whole pane**（the composer is an opaque cover over it）"*。
本帧走 **floating 形态（`shared = false`）** ⇒ 视口高 = `pane` = 51 ⇒ **视口盒与 pane 盒重合** ⇒ 复验员取到的 213 **既是视口底沿也是 pane 底沿**。

> ⚠️ **这个巧合是形态相关的、脆弱的**：一旦走 `shared = true` 形态，视口变成 `pane − composer` 高，
> 其底沿将**不再等于** `paneBottom`（会高出 `composerPx`），复验员口径就会**真的**变成跨容器比较。
> 下一版验收**不能**依赖这个巧合（见 §5.2）。

### 4.4 并集里混进了不属于 composer 布局的节点 —— **有，是 `Open terminal`**

- 节点：`R/0/0/14`，`Button`，`content-desc="Open terminal"`，`[1320,184][1366,232]`，`clickable=true`，h=48。
- 归属：**不是 composer 的孩子**。它是 `R/0/0/2`（app 内容根）的直接孩子，与 composer 三个节点**并列**；
  且它是**浮在右下角的独立终端按钮**（截图里在 `Stop` 上方偏右），不属于 44dp 工具带。
- 后果：一旦采用 `union(所有 clickable)`（U3）或把 chip 行外的东西扫进来（U2′），
  底沿从 218 变成 **232**，差值从 `+5` 变成 **`+19`** —— **纯粹的选节点错误，不是新缺陷**。
- 另两个**容易混进来但本轮无害**的：`R/0/0/17` chip 行 `HorizontalScrollView`（`[441,182][1316,205]`，h=28，底沿 205 < 213）、`Model` chip 容器（`[441,170][604,218]`，h=48，**属于 composer**，底沿 218 与 U1 同）。

### 4.5 ⭐ `composerChildUnion*` —— **v104 的"唯一求值点"是死代码**

`SessionPaneLayout.kt:1242` / `:1252` 定义了：

```kotlin
fun composerChildUnionHeightPx(panePx: Int, hostPx: Int, unionPx: Int): Int =
    unionPx.coerceAtMost(composerHostHeightPx(panePx = panePx, ceilingPx = hostPx))

fun composerChildUnionBottomPx(panePx: Int, hostPx: Int, unionPx: Int): Int =
    composerTopPx(pane = panePx, composer = hostPx) +
        composerChildUnionHeightPx(panePx = panePx, hostPx = hostPx, unionPx = unionPx)
```

**全仓引用点**（`grep composerChildUnion`，逐条核对）：

| 出现位置 | 性质 |
|---|---|
| `SessionPaneLayout.kt:1242, 1252, 1254` | **定义** |
| `ComposerChildUnionV104Test.kt:158, 165, 304, 350, 368, 407-412, 417, 447` | **测试**（8 处 + 3 处源码文本断言） |
| `Composer.kt:2207, 2248` | **注释**（"见 `composerChildUnionHeightPx`（唯一求值处）"） |

> ### ⇒ **`main` 源码里没有任何一处 `measure`/`place` 调用它。**
> `SessionPaneArrangement`（`SessionScreen.kt:3295-3339`）的 measure policy 里，
> 写进 `Constraints` 的仍然是 `maxHeight = hostHeightPx`（`:3315`）、摆位仍然是 `composerTopPx(pane, host.height)`（`:3339`）。
> **它只被 JVM 单测调用过，在真机上不约束任何一次绘制** ——
> 这就是 v103→v104 读数**逐字节不变**的第一层原因。

### 4.6 ⭐ v104 的触摸靶闸门**读错了数** —— 第二层原因

`Composer.kt:2132-2134`：
```kotlin
// v104: the band's own height, read **outside** the `Row` below —
// `RowScope` has a `maxHeight` of its own that would shadow it.
val bandMaxHeight = maxHeight
```

`Composer.kt:2182`：
```kotlin
if (bandMaxHeight >= MIN_TOUCH_TARGET) {
    bandControls()
} else {
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
    ) { bandControls() }
}
```

`Composer.kt:2596`：`private val MIN_TOUCH_TARGET = 48.dp`

**问题**：变量名叫 `bandMaxHeight`、注释也写 "the band's own height"，但它取的是
**`BoxWithConstraints` 作用域的 `maxHeight`**，也就是**传进来的约束上限（= 宿主给的 `maxHeight` = 51）**，
**不是** `Composer.kt:2221` 真正用来摆东西的那个 `band`：

```kotlin
val band = minOf(TOOL_ROW_HEIGHT.roundToPx(), constraints.maxHeight)   // = min(44, 51) = 44
```

| 闸门写法 | 本帧代入 | 结果 |
|---|---|---|
| 现状 `bandMaxHeight >= 48` | `51 >= 48` | **TRUE ⇒ 走原路径，保留 48dp 触摸靶** ❌ |
| 应有 `band >= 48` | `44 >= 48` | **FALSE ⇒ 关掉触摸靶，控件回落 28dp** ✅ |

> ### ⇒ **闸门条件恒真 ⇒ v104 的"带装不下就关触摸靶"在真机上一次都没触发。**
> 这与复验员观察到的"`Add`/`Stop` 仍是 `h=48`，与 `Composer.kt:2138` 的 44dp 带冲突依旧"**完全吻合**。
> 两次修复读数逐位一字不差，就是这两条（死代码的并集求值 + 恒真的闸门）造成的。

### 4.7 附：v104 改动**尚未进入 git**

| 文件 | `git status` |
|---|---|
| `app/.../ui/session/composer/Composer.kt` | **` M`（已修改、未提交）** |
| `app/.../ui/session/SessionPaneLayout.kt` | **`??`（未跟踪）** |
| `git show HEAD:.../Composer.kt \| grep MIN_TOUCH_TARGET` | **无输出** ⇒ HEAD 里没有 v104 |

> ⇒ 本轮被测构建（md5 `bd36719d…`）与 **HEAD 不一致**，v104 只存在于工作区。
> 但**这不能当作"v104 没进包"的证据**：闸门恒真（§4.6）已经能独立解释"读数不变"。
> 修 v104 时请以 §4.6 为准，并**把这三个文件提交进 git**，否则下次复验仍然无法区分"没进包"和"没生效"。

---

## 5. D —— 结论

### 5.1 判定：**① 真越界**

**在 app 自己的语义下，composer 确实越界 5px。** 证据链：

1. app 的语义盒子：pane 底沿 = `paneTop + panePx` = `162 + 51` = **213**（`SessionScreen.kt:3337`、`SessionPaneLayout.kt:991-992/1202-1203/1278-1279`）；
   宿主盒底沿 ≡ pane 底沿 = **213**（v103 恒等式，**本帧成立**）。
2. 实测 composer 子节点并集 `y = 169..218` ⇒ `unionBottom = 218`。
3. `218 − 213 = +5` ⇒ **在 app 语义下越界 5px**。
4. `213` 同时是 **IME `mFrame` 顶沿** ⇒ 这 5px 落在**键盘遮挡区**内（几何上被 IME 压住）。

**越界的是哪个节点**（各 `+5`，无一例外）：

| 节点 | bounds | 高 | 越界量 |
|---|---|---|---|
| `Add`（⊞）`R/0/0/16` | `[399,170][441,218]` | 48 | **+5** |
| `Stop`/Send `R/0/0/18` | `[1316,170][1364,218]` | 48 | **+5** |
| `Model` chip 容器 `R/0/0/17/0` | `[441,170][604,218]` | 48 | **+5** |
| `EditText` `R/0/0/15` | `[24,169][405,217]` | 48 | **+4** |

**成因分解（与源码逐位对上）**：

```
+5 = 4 (48dp 最小触摸靶 − 44dp TOOL_ROW_HEIGHT)  +  1 (居中亚像素取整)
```

| 项 | 值 | 锚 |
|---|---|---|
| 工具行带高 | **44 dp** | `Composer.kt:2221` `val band = minOf(TOOL_ROW_HEIGHT.roundToPx(), constraints.maxHeight)`；`TOOL_ROW_HEIGHT` 定义见 `Composer.kt:2501`/`:2585` 区 |
| 控件最小触摸靶 | **48 dp** | `clickable` ⇒ material3 `minimumInteractiveComponentSize`（`Composer.kt:32` import、`:2186` 覆盖点） |
| 差额 | **4 px** | `48 − 44` |
| 居中取整 | **1 px** | `Composer.kt:2253` `editorTop = (band - editor.height).coerceAtLeast(0) / 2` + `Alignment.CenterVertically` |
| 合计 | **+5 px** | ✅ 与实测逐位吻合 |

**为什么 768 档合格**：768 档宿主盒 = 94（`composerTopPx(140, 94) = 46` → `208`，宿主 `208..302`），
并集 88 **装得下** ⇒ `296 − 302 = −6` ✅。**只有 600 档"宿主盒 44 装不下并集 49"这一格越界。**

### 5.2 ✅ 正确的验收口径（**算式级**，供更新派单模板）

**① 先把两个盒子分清楚（缺一不可）**

```
paneTop    := 二级工具条底沿                    （实测 162）
panePx     := constraints.maxHeight             （= paneBottom − paneTop）
paneBottom := paneTop + panePx                  （实测 600: 213 / 768: 302）
hostPx     := min(TOOL_ROW_HEIGHT.roundToPx(), panePx)        （600: 44 / 768: 94）
hostTop    := paneTop + (panePx − hostPx)        （600: 169 / 768: 208）
hostBottom := hostTop + hostPx                  （≡ paneBottom，构造性恒等）
band       := min(TOOL_ROW_HEIGHT.roundToPx(), constraints.maxHeight)   （600: 44 / 768: 51）
editorPx   := EditText 高          （600: 48 / 768: 48）
toolRowPx  := Add/Stop 高          （600: 48 / 768: 48）
unionPx    := max(editorPx, toolRowPx)   （600: 48 / 768: 48）
editorTop  := (band − editorPx).coerceAtLeast(0) / 2
unionBottom:= hostTop + max(editorTop + editorPx, toolRowPx)
```

**② 主判据（单容器口径 —— 只跟宿主盒比，不跟时间线比）**

```
✅ 判过：  unionBottom ≤ hostBottom        ⇔  unionBottom ≤ paneBottom
✅ 更紧：  unionPx ≤ band                  （本档 49 ≤ 44 = FALSE ⇒ 正是要修的那一维）
✅ 恒等式： hostTop + hostPx == paneBottom （v103；600 档实测 169+44=213 ✅）
```

**③ 选节点规则（避免 §3.4 的 `+19` 假阳性）**

```
composer 子节点 := 与时间线节点【互为兄弟】、且落在 [hostTop, hostBottom] 带内的
                   EditText + 该工具带上的 clickable 控件
                   ⇒ 600 档 = { EditText[24,169][405,217], Add[399,170][441,218],
                                Stop[1316,170][1364,218] }
⛔ 排除：Open terminal [1320,184][1366,232]  （悬浮按钮，非 composer 布局，混进来 ⇒ +19）
⛔ 排除：二级工具条（y=110..162）、tab 条、chip 行自身 HorizontalScrollView
```

**④ 断言 pane 底沿，不要依赖巧合**

```
⛔ 不要假设「时间线盒底沿 == paneBottom」—— 那是 floating 形态的巧合（SessionPaneLayout.kt:991-992）
✅ 改用：paneBottom := imeTopPx（dumpsys input_method 的 mFrame 顶沿），并【断言】
          paneBottom == paneTop + panePx     不成立就作废本帧
✅ 或：取【composer 节点的祖先】中 bounds == [0,162][W, paneBottom] 的那个 View；
       找不到就写「pane 无 a11y 节点，用 imeTop 导出并记录」
```

**⑤ 停用两条无效判据**

```
⛔ 「ceiling = 0 ⇒ 宿主高必须为 0」：本帧 floorPx ≤ 3（§4.2 第 9/11 步反推），不是 51，
   ceiling ≈ 48，宿主高 44/48 是【正确行为】。该判据的输入模型错 ≥48px，永久作废。
⛔ 「并集 vs 根 Box/内容根底沿(600)」：恒为 −382，恒过，测不出任何东西。
```

**⑥ 下一版派单应加的"根因门"（比几何门更早失败）**

```
✅ band >= MIN_TOUCH_TARGET 的闸门必须读 band，不能读 BoxWithConstraints 的 maxHeight
   （Composer.kt:2134 / :2182）—— 现状 51>=48 恒真，是 v104 失效的直接原因
✅ composerChildUnionHeightPx / composerChildUnionBottomPx 必须在 main 源码的 measure/place
   里被真正调用；只有测试调用的"唯一求值点"等于没有（SessionPaneLayout.kt:1242/1252）
✅ 源码 pin 增加：闸门读数与 band 同源；并集求值点在 main 中至少一处调用
```

---

## 6. 收尾 —— ✅ 全部完成

| 项 | 结果 |
|---|---|
| `wm size reset` + `wm density reset` | ✅ 已执行 |
| **复核回 `1366x768` / `160`** | ✅ `Physical size: 3840x2160` / **`Override size: 1366x768`**；`Physical density: 320` / **`Override density: 160`** |
| 自建会话清理 | ✅ **已删除并复检**：删除后全树 `E2ECHK5556dg` 出现次数 = **0** |
| —— 先 Stop 再删 | ✅ **已遵守**：发现回合运行中，先在正确档位坐标点 `Stop`（`1332,272`，768 档），`Working` 消失、`Send` 回归，**再**长按删除 |
| —— 长按时长 | ✅ `input swipe 136 187 136 187 1300`（**1300 ms ≥ 1200**） |
| —— 删除菜单可用性 | ✅ `Rename`/`Delete` 均 `enabled=true`（承 `U1E` 的历史死锁本轮未复现） |
| —— 确认弹层归属 | ✅ 删除确认弹层焦点为 `Pop-Up Window`，**已用 `dumpsys window windows` 验明属本 app**（`package=com.xiufeigo.opencodeshell` / `mOwnerUid=10189` / parent `OcActivity` / `Session{… 30041}`）后才有交互 |
| `/sdcard` 残留 | ✅ **本轮无残留**：每次 pull 后即 `rm -f` 远端临时文件；`ls /sdcard \| grep -i dg` → 无 `E2ECHK5556dg*` 任何文件<br>（`/sdcard/e2echk5556v98`、`tB_05_rightedge.png` 为**前几轮遗留、非本轮前缀**，**未擅自删除**） |
| `mCurrentFocus`（收尾） | ✅ `com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.OcActivity` |
| `pidof`（收尾） | ✅ **`30041`** —— 与入测同一个 pid，**全程零重启** |
| 纪律自陈 | ✅ 未跑 gradle、未装机、未杀进程、未按 BACK(4)、未在弹层内按 ESC(111)、只碰 5556、每组高度读数前断言 `mInputShown`、`wm size` 必配 `wm density`、坐标运行期从 dump 解析、边做边写盘 |
| 代码改动 | **无**（只读诊断） |
| 证据目录 | `research/v97_verify/5556_v98/dg/`（dump XML + `dg_600ime.png` 截图） |

> ⚠️ **一处偏离，如实记录**：`Stop` 按钮我先在 **600 档坐标** `(1340,194)` 连点两次未生效；
> 事后查明当时屏幕已被我切到 **768 档基线**取数，坐标不匹配。改用 768 档坐标 `(1332,272)` 后一次生效。
> **未在错误的窗口上造成任何副作用**（回合仍在运行，被正确停止），但记录在案。

---

## 7. 未闭环 / 交 Lead 裁定

1. **🔴 P0：v104 的两处改动都是"不生效"的，需 Lead 派修复（本轮只诊断，未改代码）**
   - `Composer.kt:2134/2182` 闸门读 `maxHeight(51)` 而非 `band(44)` ⇒ **恒真**，`Add/Stop` 的 48dp 触摸靶从未被关掉。
   - `SessionPaneLayout.kt:1242/1252` 的 `composerChildUnion*` **在 `main` 里零调用** ⇒ v103→v104 读数逐位不变。
   - 建议修法（**算式级**）：闸门改读 `band`；`unionBottom` 在 `SessionPaneArrangement` 的 `layout{}` 里按
     `composerChildUnionBottomPx(panePx, hostPx, unionPx)` 实际定位，使 `unionBottom ≤ hostBottom` 成为构造性恒等式。
2. **🟠 v103/v104 三个文件未提交 git**（`Composer.kt` = ` M`，`SessionPaneLayout.kt` = `??`；HEAD 无 `MIN_TOUCH_TARGET`）。
   **被测构建与 HEAD 不一致** ⇒ 下轮复验仍无法区分"没进包"与"没生效"。建议本轮修复一并提交。
3. **🟠 复验员"pane"＝时间线/视口**（§4.3）。本帧因 floating 形态（`SessionPaneLayout.kt:991-992`）数值巧合相等；
   **走 shared 形态即失效**。派单模板应改为"用 `imeTop` 导出并断言"，不要靠节点形状猜 pane。
4. **🟠 判据「`ceiling=0 ⇒ 宿主高=0`」应作废**（§4.2 第 9/11 步：本帧 `floorPx ≤ 3`，非 51）。
   该判据连续两轮（v103 §4.2 / v104 §3.3）被判 FAIL，但**它测的不是缺陷**。
5. **🟡 可见性未量化**：本轮确认 5px 落在 IME 顶沿之内（几何被压），但**未截图逐像素确认**是否有可见像素被裁。
   `dg/dg_600ime.png` 目视未见明显裁切（可见控件 `SUBMIT_SIZE = 28dp` 居中于 44dp 带内，`177..205` ⊂ `169..213`）。
   若 Lead 需要"这 5px 是否影响可用性"的结论，需补一轮带标注的像素级比对（本轮未做）。
6. **🟡 600 档之外未扫**：`shared` 形态（§4.3 巧合失效的那一档）本轮**未构造**，故 §5.2 ④ 的 `shared` 情形**未实测验证**。

---

## 8. 回报

状态：✅ **未 BLOCKED**（md5 `bd36719d…` + `lastUpdateTime 2026-10-03 10:35:14` 双闸逐位命中；`pidof 30041` 全程未重启；收尾 `wm` 已复核回 1366x768/160；自建会话已删且复检 0 残留；`/sdcard` 无本轮残留）｜诊断完成，**未改任何代码、未跑 gradle**

**结论：① 真越界** —— app 自己的语义下（pane 底沿 213 = 宿主盒底沿 213 = `imeTop` 213），composer 子节点并集 `unionBottom = 218` **越界 5px**；越界节点是 `Add` / `Stop` / `Model` chip 三个 `clickable`（各 `h=48`，48dp 最小触摸靶装在 44dp 带里，底沿穿出 4px）+ 1px 亚像素取整。

**「+5 是否只在跨容器比较时出现」：❌ 不是。** 换成 app 自己的 pane 底沿（P2=213）与宿主盒底沿（P5=213）**同样是 `+5`** —— 复验员取的那个 213 数值上恰好与 app 语义盒子重合。**真正的口径瑕疵是命名**：那个节点里装着 `10:51 AM` / `Revert message` 等消息节点，它是**时间线/视口**而非 pane；数值相等只是 floating 形态（`SessionPaneLayout.kt:991-992` 视口高=整 pane）的**巧合**。另有一个真实的选节点陷阱：`Open terminal`（`[1320,184][1366,232]`）**不属于 composer 布局**，混进 `union(所有 clickable)` 会造出 **`+19`** 的假阳性。

**「app 语义下 composer 是否真越界」：✅ 是，真越界 5px。**

**正确的验收算式**（详见 §5.2）：
```
paneBottom := paneTop + panePx                                  (600: 162+51 = 213)
hostPx     := min(TOOL_ROW_HEIGHT, panePx) = 44
hostBottom := paneTop + (panePx − hostPx) + hostPx ≡ paneBottom  (169+44 = 213 ✅ v103 恒等式成立)
unionPx    := max(EditText高, 工具行高) = 48；实测 unionBottom = hostTop + max(editorTop+editorPx, toolRowPx) = 218
✅ 判过：unionBottom ≤ hostBottom            （本档 218 > 213 ❌ 越界 5）
✅ 更紧：unionPx ≤ band                        （本档 48 > 44 ❌ ← 要修的那一维）
选节点：与时间线节点互为兄弟、且落在 [hostTop,hostBottom] 带内的 EditText + 该带 clickable 控件；
        ⛔ 排除 Open terminal[1320,184][1366,232]（混进来 ⇒ +19 假阳性）
        ⛔ 排除对根 Box/内容根底沿 600 的比较（恒 −382，恒过，测不出东西）
paneBottom 改用 imeTop（dumpsys input_method 的 mFrame 顶沿）并【断言】== paneTop+panePx，不靠节点形状猜
⛔ 作废「ceiling=0 ⇒ 宿主高=0」：本帧 floorPx ≤ 3（非 51），ceiling≈48，宿主 44/48 是正确行为
⛔ 作废「并集 vs 根盒 600」：恒过
根因门：闸门必须读 band(44) 而非 BoxWithConstraints 的 maxHeight(51) —— Composer.kt:2134/2182 现状恒真；
        composerChildUnion* 必须在 main 的 measure/place 里被真正调用（SessionPaneLayout.kt:1242/1252 现为零调用）
```

**根因（两次修复都打不中的原因）**：① `composerChildUnionHeightPx`/`BottomPx` **在 `main` 源码零调用**，只有 JVM 单测调用；② 触摸靶闸门 `bandMaxHeight >= 48` 取的是**传进来的 `maxHeight`(51)** 而非 `band`(44)，**恒真** ⇒ v104 的"关触摸靶"分支一次都没触发。二者共同解释了 v103/v104 读数**逐字节一字不差**。另：v104 三个文件**未提交 git**（HEAD 无 `MIN_TOUCH_TARGET`），建议随修复一并提交。

**报告路径**：`research/v97_verify/5556_v98/REPORT_OVERFLOW_DIAG.md`（证据：`research/v97_verify/5556_v98/dg/`）
