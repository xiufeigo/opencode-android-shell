# v160 真机诊断 — IME 阶梯的触发定因：Gboard 自收行 vs ADJUST_RESIZE 反馈环

- **执行**：mavis（mcode），**v160 · 真机诊断工程师**
- **日期**：2026-10-04
- **设备**：`emulator-5554`（AVD Medium_Desktop，物理 3840x2160，override 1366x768/160）
- **包名**：`com.xiufeigo.opencodeshell`（md5 `b30290a6b67fc6cd2804aa5a2b4c17d6`，**本轮未重装**，已复核逐位一致）
- **上游证据**：`research/v97_verify/5554_md/REPORT_MONOTONE_FORM2.md`（v157）、`research/v97_fix/MONOTONE_JUDGE_FIX.md`（v158）
- **并发纪律**：全程**未跑 gradle、未装机、未让 app 处理任何测试**；只做真机只读诊断 + 写 `research/**`
- **后端**：v2 已停（4095），本轮**不依赖后端**，未启任何服务

---

## 0. 结论速览

> ## 判定：**(a) Gboard 自己收行** —— 不是 (b)，不是混合
>
> **排除 (b) 的那一条证据**：app 窗口的 `Frames` 在每一级都等于**整屏高**、**从未被 ime 顶边裁短**
> （H=599 时 `frame=[0,0][1366,599]`，ime 顶边在 338），`insetsChanged=false`
> ⇒ **app→ime 的反馈通路根本不存在**，(b) 在结构上无法成立。

| 判据 | 结果 |
|---|---|
| 600 / 599 / 598 的 ime 高度 | **339 / 261 / 261** |
| ime 高度是 Gboard 自身布局的结果吗 | ✅ 是（`imeH = gContent + 48`，gContent 为 dp 常量 378/291/213） |
| Gboard 的高度预算来自哪 | ✅ **屏幕高 − 导航条**（logcat 原文 `update screenHeightWithoutNaviBar to: 551`） |
| app 窗口被 ime 裁短过吗 | ❌ **从未**（每一级都是整屏高，`insetsChanged=false`） |
| 一次 wm 改动产生几轮往返 | ✅ **1 轮**（`Using new display size` ×1，`InsetsController` ×1） |
| 收敛过程是否振荡 | ❌ **不振荡**（一次瞬态 → 一次到位；重复评估幂等同决策） |
| 600→599 方向是否支持反馈环 | ❌ **方向相反**：app 可见区 **+77**，ime 却 **−78** |
| 密度对照（同一 H 改 density） | ✅ **塌缩随密度开关**：density 120 时 620/600/599 **全部 332，塌缩不发生** |

**对 v158 的追认**：v158 写的「纯 Gboard 行为，应用零参与」当时是**推测**，本轮把它变成了**有证据的结论**。
但本轮同时发现 **426/339 这一阶存在迟滞（路径依赖）**，v158 的 `measuredImePx` 阈值在该阶**不稳健** —— 见 §6。

---

## 1. 曲线：ime 高度 vs 窗口高度

> 取数（v157 已记录的坑，本轮沿用）：`dumpsys window`（**不是** `dumpsys window insets`，那不是合法子命令）
> 里的 `InsetsSource id=3 type=ime frame=` 原始行。imeH = frame.bottom − frame.top。
> 每级取 `dumpsys window` + `dumpsys input_method` 各一份原文，表中所有值都是原文抄录，无二次加工。

### 1.1 主曲线（density 160，自 768 逐级下降）

| H | ime 原始行（原文） | imeH | contentTopInsets | Gboard content | mImeHeight | app 可见区 = ime 顶 |
|---:|---|---:|---:|---:|---:|---:|
| 768 | `frame=[0,342][1366,768]` | 426 | 342 | **378** | 378 | 342 |
| 700 | `frame=[0,274][1366,700]` | 426 | 274 | 378 | 378 | 274 |
| 660 | `frame=[0,234][1366,660]` | 426 | 234 | 378 | 378 | 234 |
| 640 | `frame=[0,214][1366,640]` | 426 | 214 | 378 | 378 | 214 |
| 620 | `frame=[0,194][1366,620]` | 426 | 194 | 378 | 378 | 194 |
| 610 | `frame=[0,184][1366,610]` | 426 | 184 | 378 | 378 | 184 |
| 606 | `frame=[0,180][1366,606]` | 426 | 180 | 378 | 378 | 180 |
| 605 | `frame=[0,179][1366,605]` | 426 | 179 | 378 | 378 | 179 |
| 604 | `frame=[0,178][1366,604]` | 426 | 178 | 378 | 378 | 178 |
| 603 | `frame=[0,177][1366,603]` | 426 | 177 | 378 | 378 | 177 |
| 602 | `frame=[0,176][1366,602]` | 426 | 176 | 378 | 378 | 176 |
| 601 | `frame=[0,175][1366,601]` | 426 | 175 | 378 | 378 | 175 |
| **600** | `frame=[0,261][1366,600]` | **339** | 261 | **291** | 291 | 261 |
| **599** | `frame=[0,338][1366,599]` | **261** | 338 | **213** | 213 | 338 |
| **598** | `frame=[0,337][1366,598]` | **261** | 337 | 213 | 213 | 337 |
| 597 | `frame=[0,336][1366,597]` | 261 | 336 | 213 | 213 | 336 |
| 596 | `frame=[0,335][1366,596]` | 261 | 335 | 213 | 213 | 335 |
| 595 | `frame=[0,334][1366,595]` | 261 | 334 | 213 | 213 | 334 |
| 590 | `frame=[0,329][1366,590]` | 261 | 329 | 213 | 213 | 329 |
| 585 | `frame=[0,324][1366,585]` | 261 | 324 | 213 | 213 | 324 |
| 580 | `frame=[0,319][1366,580]` | 261 | 319 | 213 | 213 | 319 |
| 570 | `frame=[0,309][1366,570]` | 261 | 309 | 213 | 213 | 309 |
| 560 | `frame=[0,299][1366,560]` | 261 | 299 | 213 | 213 | 299 |

### 1.2 恒等式（每级都验过）

```
imeH  =  Gboard content  +  48        （48 = 导航条，Gboard 自己画，`mImeDrawsImeNavBar=true`）
Gboard content = touchableRegion.bottom − contentTopInsets
imeH  =  H − contentTopInsets         （ime frame 永远贴显示底边）
```

三级台阶对应的 **Gboard content 是三个 dp 常量**：`378dp / 291dp / 213dp`。
`dumpsys window` 的 `mImeHeight` 同步为 `378 / 291 / 213`，`mInsetsHint` 同步为 `426 / 339 / 261`。
**换句话说：`426/339/261` 不是系统裁出来的尺寸，而是 Gboard 选的三种键盘形态。**

### 1.3 与 v157 的两处差异（如实记录，不掩盖）

| 项 | v157 记录 | 本轮实测 | 说明 |
|---|---|---|---|
| 339 阶的起点 | `604..600` 均为 339 | **604/603/602/601 均为 426**，339 从 **600** 才开始 | 426/339 这一阶**有迟滞**（§3.2），v157 的 604 起点不可复现 |
| H=599 的 frame 顶边 | 写作 `334` | 原文为 **`338`** | v157 自己的 `261` 是对的（`599−338=261`），但它抄的顶边 `334` 与自己的 261 不自洽；本轮原文 `338` 与 `mInsetsHint bottom=261` 完全自洽 |

---

## 2. 可判定观测 A：键盘形态证据（`setKeyboardViewShown` 原文）

任务要的「候选栏/工具行是否出现」的痕迹，**在 logcat 里抓到了原文**（`GoogleInputMethodService` 标签）：

### 2.1 600 → 599（塌缩方向）

```
10-04 06:43:56.059  3162  3162 W GoogleInputMethodService: GoogleInputMethodService.onConfigurationChanged():1557 onConfigurationChanged() : NewConfig = {... sw599dp w1366dp h551dp 160dpi ...}
10-04 06:43:56.062  3162  3162 I GoogleInputMethodService: GoogleInputMethodService.onConfigurationChanged():1575 changedMask : 20000c00
10-04 06:43:56.243  3162  3162 I GoogleInputMethodService: GoogleInputMethodService.lambda$setDecorViewApplyWindowInsetsListener$10():1155 update screenHeightWithoutNaviBar to: 551
10-04 06:43:56.244  3162  3162 I GoogleInputMethodService: GoogleInputMethodService.lambda$setDecorViewApplyWindowInsetsListener$10():1161 update max height when insets changes
10-04 06:43:56.273  3162  3162 I GoogleInputMethodService: GoogleInputMethodService.setKeyboardViewShown():3483 setKeyboardViewShown() : type = HEADER, shownType = SHOW_OPTIONAL
10-04 06:43:56.275  3162  3162 I GoogleInputMethodService: GoogleInputMethodService.setKeyboardViewShown():3530 setKeyboardViewShown() : shouldShow = true
10-04 06:43:56.276  3162  3162 I GoogleInputMethodService: GoogleInputMethodService.setKeyboardViewShown():3483 setKeyboardViewShown() : type = BODY, shownType = SHOW_MANDATORY
10-04 06:43:56.276  3162  3162 I GoogleInputMethodService: GoogleInputMethodService.setKeyboardViewShown():3530 setKeyboardViewShown() : shouldShow = true
10-04 06:43:56.276  3162  3162 I GoogleInputMethodService: GoogleInputMethodService.setKeyboardViewShown():3483 setKeyboardViewShown() : type = FLOATING_CANDIDATES, shownType = HIDE
10-04 06:43:56.276  3162  3162 I GoogleInputMethodService: GoogleInputMethodService.setKeyboardViewShown():3530 setKeyboardViewShown() : shouldShow = false
   …（同 4 行在 06:43:56.415 原样重演一次，决策完全相同）
```

**关键原文**：`update screenHeightWithoutNaviBar to: 551` —— Gboard 自己声明它的高度预算是
**「屏幕高 − 导航条」= 599 − 48 = 551**，随后 `update max height when insets changes`。
**这个数不是 app 窗口的高度**（H=599 时 app 可见区只有 338），也不是 ime 顶边。**它是屏幕尺寸算出来的。**

### 2.2 599 → 600（回升方向）

```
10-04 06:44:27.989  545  6031 I WindowManager: Using new display size: 1366x600
10-04 06:44:28.124  3162  3162 I GoogleInputMethodService: ...:1155 update screenHeightWithoutNaviBar to: 552
10-04 06:44:28.215  3162  3162 I GoogleInputMethodService: setKeyboardViewShown() : type = HEADER, shownType = SHOW_OPTIONAL
10-04 06:44:28.215  3162  3162 I GoogleInputMethodService: setKeyboardViewShown() : shouldShow = true
10-04 06:44:28.217  3162  3162 I GoogleInputMethodService: setKeyboardViewShown() : type = BODY, shownType = SHOW_MANDATORY
10-04 06:44:28.217  3162  3162 I GoogleInputMethodService: setKeyboardViewShown() : shouldShow = true
10-04 06:44:28.217  3162  3162 I GoogleInputMethodService: setKeyboardViewShown() : type = FLOATING_CANDIDATES, shownType = HIDE
10-04 06:44:28.217  3162  3162 I GoogleInputMethodService: setKeyboardViewShown() : shouldShow = false
   …（同 4 行在 06:44:28.382 原样重演一次，决策完全相同）
```

### 2.3 这条证据的**诚实读法**（含一个否定结果）

- ✅ **形态决策确实发生在 Gboard 进程内**（`setKeyboardViewShown` 是 Gboard 自己的方法，pid 3162），
  决策输入是 `screenHeightWithoutNaviBar`（屏幕派生）。**这一条支持 (a)。**
- ❌ **`FLOATING_CANDIDATES` 在 600 和 599 两级都是 `HIDE` / `shouldShow=false`，完全相同。**
  ⇒ **339 → 261 这一跳不是"收起候选栏"造成的。**
  从 `Gboard content 291dp → 213dp`（−78dp，正好一行的量）看，塌的是**键区本体的一行**，
  而不是建议条。**任务假设的「候选栏出现/消失」这条痕迹在本设备上不成立**，我据实报告而不是硬套。

---

## 3. 可判定观测 B：反馈环证据

### 3.1 收敛形态（多次轮询，专找振荡）

每次 `wm size` 改动后连打 6 次 ime frame（间隔 400ms）：

| 改动 | 轮询序列 | distinct | 终值 |
|---|---|---|---|
| → H=620（自 560 上行） | `321 → 339 → 339 → 339 → 339 → 339` | 321, 339 | 339 |
| → H=600 | `319 → 339 → 339 → 339 → 339 → 339` | 319, 339 | 339 |
| → H=599 | `338 → 261 → 261 → 261 → 261 → 261` | 338, 261 | 261 |
| → H=598 | `260 → 261 → 261 → 261 → 261 → 261` | 260, 261 | 261 |

**形态：一次瞬态 → 一次到位 → 稳定。没有任何来回摆动。**
反馈环的签名是**反复成对**（resize→insets→resize→insets…直到不动），本轮**一次都没有出现**。

### 3.2 路径依赖（迟滞）—— 一个真实的否定/肯定混合结果

| 路径 | 结果 |
|---|---|
| A：768 → 599（直落） | 261 |
| B：768 → 600 → 599 | 261 |
| C：599 → 600 → 599 | 261 |
| D：599 → 600（回升） | **339** |
| **H=620 自 768 下降** | **426** |
| **H=620 自 560/599 上升** | **339** |

- **600/599 这一阶无迟滞**（A/B/C/D 四条路径互证，四种来路结果全一致）。
- **426/339 这一阶有迟滞**：同一 H=620，从上往下是 426，从下往上是 339。
  ⇒ 该阶是 **Gboard 自身的状态记忆**（它不会在"刚够"时立刻把键盘升回全高），
  **不是 app 的反馈记忆** —— 因为 app 窗口在两种来路下的 frame 都是整屏高（§4），**没有任何可记忆的差异**。

### 3.3 一次 wm 改动 = 一轮往返

`600 → 599` 单次改动后的 logcat 全文统计：

```
Using new display size        : 1 次
InsetsController 事件         : 1 次   （原文：06:43:56.376 D InsetsController: show(ime(), fromIme=true)）
onConfigurationChanged        : 1 次   （changedMask : 20000c00）
setKeyboardViewShown 决策块   : 2 次，且两次决策逐字相同（幂等收敛）
```

`599 → 600` 单次改动：`Using new display size` **1 次**、`InsetsController` **1 次**。
**没有任何「成对重复」**。若存在 ADJUST_RESIZE 反馈环，这里必然出现多轮 config/insets 往复。

---

## 4. 可判定观测 C：app 窗口几何（判 ADJUST_RESIZE 是否在裁窗口）

`dumpsys window windows` 里本 app 的 `WindowState` 原文：

```
  Window #11 Window{53b223a u0 com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.OcActivity}:
    mAttrs={(0,0)(fillxfill) sim={adjust=resize} layoutInDisplayCutoutMode=always ty=BASE_APPLICATION wanim=0x10302fe
      fl=LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
      pfl=NO_MOVE_ANIMATION FORCE_DRAW_STATUS_BAR_BACKGROUND USE_BLAST APPEARANCE_CONTROLLED FIT_INSETS_CONTROLLED
      vsysui=LAYOUT_STABLE LIGHT_STATUS_BAR LIGHT_NAVIGATION_BAR ...}
```

`mAttrs` 里**同时**有 `sim={adjust=resize}` **和** `pfl=... FIT_INSETS_CONTROLLED`。
后者意味着窗口走 edge-to-edge：**insets 派发给 app，由 app 自己排版，系统不裁窗口。**

逐级实测 `Frames` 行（**本报告排除 (b) 的核心**）：

| H | ime 顶边 | app 窗口 `frame` | `insetsChanged` |
|---:|---:|---|---|
| 768 | 342 | `frame=[0,0][1366,768]` ← **整屏高** | false |
| 600 | 261 | `frame=[0,0][1366,600]` ← **整屏高** | false |
| 599 | 338 | `frame=[0,0][1366,599]` ← **整屏高** | false |

**app 窗口在 ime 顶边是 261 / 338 的情况下，frame 依然是整屏高，一像素都没被裁短。**
⇒ **ime 高度变化 ⇒ app 窗口几何变化** 这条因果链**不存在**，(b) 所需的「窗口缩放 ⇒ insets 变化 ⇒ 重算」**无源**。

### 4.1 方向检验（第二重独立否证）

| 量 | H=600 | H=599 | 变化 |
|---|---:|---:|---:|
| app 可见区（= ime 顶边） | 261 | 338 | **+77** |
| ime 高度 | 339 | 261 | **−78** |

**窗口矮了 1px，app 的可用区反而大了 77px，ime 却塌了 78px。**
若 ime 是被 app 窗口挤小的，可用区变大时它应当**变大**。**方向与 (b) 相反。**

---

## 5. 对照实验

### 5.1 密度对照：同一 H，改 `wm density`

| density | H | ime 原始行 | imeH | Gboard content | 该 H 的可用 dp（`screenHeightWithoutNaviBar`） |
|---:|---:|---|---:|---:|---:|
| 160 | 620 | `frame=[0,281][1366,620]` | 339 | 291 px = 291 dp | 572 dp |
| 160 | 600 | `frame=[0,261][1366,600]` | 339 | 291 px = 291 dp | 552 dp |
| 160 | 599 | `frame=[0,338][1366,599]` | **261** | 213 px = 213 dp | 551 dp |
| **120** | 620 | `frame=[0,288][1366,620]` | **332** | 284 px = **378.7 dp** | 763 dp |
| **120** | 600 | `frame=[0,268][1366,600]` | **332** | 284 px = 378.7 dp | 736 dp |
| **120** | 599 | `frame=[0,267][1366,599]` | **332** | 284 px = 378.7 dp | 735 dp |
| 160（复测） | 620 | `frame=[0,281][1366,620]` | 339 | 291 px | 572 dp |
| 160（复测） | 600 | `frame=[0,261][1366,600]` | 339 | 291 px | 552 dp |
| 160（复测） | 599 | `frame=[0,338][1366,599]` | 261 | 213 px | 551 dp |

**两条结论：**

1. **Gboard 的三种形态是 dp 常量**：在 160dpi 下 content = 378/291/**213 px**，
   在 120dpi 下 content = 284 px = **378.7 dp**。378 × 0.75 = 283.5 ≈ 284 —— **完全对上**。
   ⇒ Gboard 是在**自己的一套 dp 尺寸**里挑，不是被像素高度裁的。
2. **`600 → 599` 的塌缩在 density 120 下根本不发生**（620/600/599 全是 332，content 恒为 378dp 全高键盘）。
   密度 120 时可用 dp 有 735–763，远宽裕，Gboard 一直保持全高；
   density 160 时可用 dp 只有 551–572，它才被迫降到 291dp / 213dp。
   ⇒ **触发条件是 Gboard 自己那份「屏幕高 − 导航条」的 dp 预算**，与 app 无关。

> 任务原话是「若 ime 只随窗口高变而**不随密度**变，更支持 (a)」。实测是**反过来的**：
> ime **既随窗口高变、也随密度变**，而且随密度变的方式恰好是 **dp 常量 × 密度**。
> 这个结果**比原假设更强地支持 (a)** —— 因为它证明 ime 高度由「dp 常量表 × 密度 × 可用 dp」决定，
> 三者全部是 Gboard 侧/屏幕侧的量，**没有一项是 app 侧**。

### 5.2 IME 侧对照：app 可见区 vs Gboard 预算

| H | app 可见区 | Gboard 预算（logcat 原文） | 差 |
|---:|---:|---:|---:|
| 600 | 261 | `update screenHeightWithoutNaviBar to: 552` | Gboard 比 app **多 291 px** |
| 599 | 338 | `update screenHeightWithoutNaviBar to: 551` | Gboard 比 app **多 213 px** |

Gboard 眼里的"可用高度"始终等于「屏幕高 − 48」，**从不等于 app 的可见区**。
（顺带印证：这两个差值恰好就是 291 / 213 —— 即 Gboard 自己的 content 高度。）

---

## 6. 判定与证据链

### 6.1 判定：**(a) Gboard 自己收行**

不是 (b)，也不是混合。

### 6.2 证据链（每一步都注明它排除了什么）

| # | 证据 | 排除了什么 |
|---:|---|---|
| 1 | app 窗口 `frame` 每级都是**整屏高**、从未被 ime 顶边裁短、`insetsChanged=false`（§4） | **直接排除 (b)**：app→ime 的反馈通路不存在，环无法闭合 |
| 2 | 600→599 时 app 可见区 **+77** 而 ime **−78**，方向相反（§4.1） | 排除「ime 被 app 窗口挤小」的方向性解释 |
| 3 | 一次 wm 改动 ⇒ `Using new display size` ×1、`InsetsController` ×1（§3.3） | 排除「成对重复的 resize/insets 往复」 |
| 4 | 6 次轮询全部「一次瞬态 → 一次到位」，无摆动（§3.1） | 排除「反复迭代收敛」的反馈环形态 |
| 5 | Gboard 原文 `update screenHeightWithoutNaviBar to: 551`（§2.1） | 排除「ime 高度由 app 窗口反推」——它的输入是**屏幕尺寸** |
| 6 | 形态是 dp 常量 378/291/213，density 120 下 content = 284px = 378.7dp（§5.1） | 排除「系统按像素裁剪」；证明是 Gboard 选形态 |
| 7 | density 120 时 620/600/599 **全部不塌缩**（§5.1） | 排除「只要窗口变矮就必然塌」的纯高度解释；证明触发量是 **dp 预算** |
| 8 | 600/599 阶四条路径（A/B/C/D）结果全一致（§3.2） | 排除「反馈记忆」在这一阶存在 |

### 6.3 迟滞的正确归属（**部分混合，但不是 (b)**）

H=620 存在真实迟滞：自上而下 426、自下而上 339。
但它**归 Gboard，不归 app** —— 依据：

- Gboard 的 `setKeyboardViewShown` / `update max height` 都在 Gboard 进程内用自己的状态重算（§2.1）；
- 两种来路下 app 窗口 frame 都是整屏高，**app 侧不存在任何差异可供"记忆"**（§4）。

所以准确表述是：**形态归因是纯 (a)；但 (a) 自身带内部状态记忆，在 426/339 这一阶有迟滞。**
这**不是** (b)，因为记忆在 Gboard 进程里，不在 app↔系统这条通路上。

---

## 7. 收尾

| 项 | 状态 |
|---|---|
| `wm size` | `Override size: 1366x768` ✅ 已 `wm size reset` 后显式重设并复核 |
| `wm density` | `Override density: 160` ✅ 已 `wm density reset` 后显式重设并复核 |
| 复原后 ime frame | `frame=[0,342][1366,768]`（imeH 回到 426）✅ |
| 复原后 app frame | `frame=[0,0][1366,768]` `insetsChanged=false` ✅ |
| `show_ime_with_hard_keyboard` | **改动前读到 `1`；本轮全程未修改**（未用 `settings put`），现仍为 **`1`** ✅ |
| APK md5 | `b30290a6b67fc6cd2804aa5a2b4c17d6`，与 `research/v97_verify/install_all.txt` 逐位一致 ⇒ **未重装** ✅ |
| gradle / 装机 / app 跑测试 | **全程零次** ✅ |
| 后端 4095 | **未启动、未连接** ✅ |
| `/sdcard` 残留 | 顶层 **45 项，与本轮开工前基线逐项一致，零新增** ✅（本轮所有 dumpsys 输出都只落在**主机** `$env:TEMP` 与 `research/**`） |
| 旧轮次遗留 | `B1_*` / `T0_*` / `R1_*` / `W1_*` / `a1.xml` 等为 v155–v157 遗留，**未擅自删除** |

---

## 8. 未闭环 / 建议下一轮

1. **v158 的 `measuredImePx` 在 426/339 这一阶不稳健。** 该阶有迟滞（§3.2）：
   `window >= 605 -> 426` 只在「自上而下到达」时成立；自下而上到达时 620 就是 339。
   建议 v158 的用例 4 把该阶改成**显式承认迟滞**（或只用 `599/600` 这条无迟滞的阶做断言），
   否则该断言在特定到达路径下会翻红。**本轮未改任何源码/测试**（职责为诊断）。

2. **仍未取到「Gboard 到底按什么阈值在 291dp 与 213dp 之间切换」的内部判据。**
   已定位触发输入为 `screenHeightWithoutNaviBar`（551 vs 552 dp），
   但**阈值比较式**在 Gboard 闭源代码里，只读手段到不了。
   若 Lead 要求钉死，需要 Gboard 侧调试或换一台非 Gboard 的 IME 做交叉验证。

3. **塌掉的那一行的身份未直接证实。** 由 `291dp → 213dp`（−78dp）推断是键区一行，
   但 `FLOATING_CANDIDATES` 两级都是 HIDE（§2.3），**没有直接的"某行消失"原文**。
   可行的只读补测：dump IME 窗口自身的 surface / 用 `dumpsys gfxinfo` 或截屏比对键区行数。

4. **本次判定绑定 Gboard LatinIME + 模拟器。** 真实设备上不同 IME / 不同
   `screenHeightWithoutNaviBar` 口径可能给出不同阶梯，**不可外推到全部设备**。

5. **本轮全程未让 app 处理任何测试**，故未复验 v157 的形态表（ONEROW/STACKED）。
   形态仍应以 `room` 为判据（v158 裁定），本轮**不动**该裁定 —— 本轮只解决了
   "ime 阶梯为什么发生"，**没有**触碰"形态为什么回翻"。

---

## 附：产物与脚本

全部落盘于 `research/v97_verify/5554_md/`：

| 文件 | 内容 |
|---|---|
| `REPORT_IME_STEP_CAUSE.md` | 本文件 |
| `v160_sweep.ps1` | §1 主曲线：23 级稠密扫描，每级取 `dumpsys window` + `dumpsys input_method` 原文 |
| `_v160_curve_live.log` | 主曲线逐级原始输出（含每级 `type=ime frame` / `mImeHeight` / `mInsetsHint` / `contentTopInsets` / `touchableRegion` / IME `Configuration`） |
| `v160_exp.ps1` | §3.1 收敛轮询 + §3.2 路径依赖 + §5.1 密度对照 |
| `_v160_exp_live.log` | 上述三组实验原始输出 |
| `v160_exp45b.ps1` | §4 app 窗口 Frames / §2 logcat 形态证据 |
| `_v160_exp45b_live.log` | 上述实验原始输出 |
| `v160_exp45.ps1` | **已废弃**：app 窗口正则漏了 `Window #11` 前缀、logcat `-T` 未给设备 shell 补引号，被 `v160_exp45b.ps1` 取代（保留以留痕） |
