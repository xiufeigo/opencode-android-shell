# v157 真机复验 — 形态单调性（含 588）+ 混淆控制 + 专测 588

- **日期**：2026-10-04
- **设备**：emulator-5554
- **包名**：`com.xiufeigo.opencodeshell`（v155/v156 报告里写作 `...shell`，实际 applicationId 是 `com.xiufeigo.opencodeshell`）
- **轮次目标**：复验 v156 自指未闭环的混淆 —— v156 算出"断点 = window 588"时**假定 ime = 426 恒定**，且**未排除"Gboard 随窗口变矮而变矮"**。本轮先钉住 ime，再判形态。

## 结论摘要（TL;DR）

| 判据 | 结果 |
|---|---|
| 构建 md5 一致 | ✅ `b30290a6b67fc6cd2804aa5a2b4c17d6`，逐位一致 |
| `lastUpdateTime` | ✅ `2026-10-04 04:00:57`（= 提交时间 12:00:57 +0800 的 UTC） |
| 第二处 `>= 0.dp` 修复在位 | ✅ `SessionPaneLayout.kt:787` |
| **ime 是否随窗口变矮而变化** | 🔴 **是，实测变化：426 → 339 → 261** —— **v156 的混淆被证实** |
| 逐级形态单调性（真实条件） | 🔴 **FAIL：600 → 599 出现回翻**（ONEROW → STACKED） |
| 固定 ime 时的形态单调性 | ✅ **PASS：form 对 `room` 严格单调不增**（阈值 room ∈ (261, 274]） |
| 588 这一级是什么形态 | **STACKED（堆叠/完整）**，room=327（不是 v156 预测的 162） |
| 断点是否仍是 588 | 🔴 **不是 588。是 600/599 之间**，且成因是 ime 塌缩，非 composer 逻辑 |
| B 基线 140/92/−10 与 51/−12 | ✅ 全部逐位一致，**不漂** |
| 六条判据 | ✅ 6/6 PASS（600 与 768 均验） |
| 2 轮往返 logcat | ✅ `FATAL / IllegalArgumentException / maxHeight` 计数全为 0 |

**总判定：FAIL（针对任务 A 的"真实条件单调"判据）。** 但这不是 composer 逻辑的回归 —— 形态对 `room` 单调性完好；回翻 100% 由 **ime 高度在 600→599 塌缩 78px** 造成，`room` 反而**变大** 77px。v156 的 588 断点是"假定 ime 恒为 426"的**伪断点**。

---

## 0. 构建 / 后端 / 归属核对

```
设备 APK 路径: /data/app/~~DGZGtSoYXa7TJRrC95R5Sw==/com.xiufeigo.opencodeshell-M89xqSBQmmZWpUaBzGTBug==/base.apk
设备 md5sum  : b30290a6b67fc6cd2804aa5a2b4c17d6     ← 与 Lead 提供的 md5 逐位一致
lastUpdateTime: 2026-10-04 04:00:57                 ← 与要求一致
versionName  : 1.4.0   versionCode : 26
仓库 HEAD    : d9a51961653461a36f2cc70250d05283da42374f
               "fix: the second copy of the same guard -- the flip is at window 588"
git status   : clean（无本地改动）
```

后端 v2：`http://127.0.0.1:4095` 在线（返回 OpenCode v2 页面），`adb reverse` 已挂 `tcp:4095 -> tcp:4095`（另有历史遗留 `tcp:2222 -> tcp:22`）。本轮全部走既有 session，**未新建会话、未发送任何消息**。

修复在位核对 —— `SessionPaneLayout.kt:786-787`：

```kotlin
fun composerCompact(available: Dp): Boolean =
    available >= 0.dp && available < COMPOSER_MIN_HEIGHT + CHROME_MIN_HEIGHT + TIMELINE_MIN_CONTENT
```

确为第二处 `> 0.dp → >= 0.dp`。**未自行装机**，全程使用 Lead 已装版本。

---

## 1. 混淆控制（每级 ime 实测）—— 本轮最重要的产出

v156 留下的未闭环：断点 588 的推导**假定 ime = 426 恒定**。本轮按要求 `settings put secure show_ime_with_hard_keyboard 1`，并在**每一级**读取 `dumpsys window` 的 `type=ime frame=` 原始行。

> 取数说明：本机 `dumpsys window insets` 不是合法子命令（`Bad window command, or no windows match: insets`）；`dumpsys input_method` 无 `mCurFrame`。实际可用原始行在 **`dumpsys window`**（`dumpsys window displays` 同源）：
> `InsetsSource id=3 type=ime frame=[0,342][1366,768] visible=true`

### 每级 ime 实测（原样）

| H | ime 原始行 | imeH | room = H − imeH |
|---:|---|---:|---:|
| 768 | `type=ime frame=[0,342][1366,768]` | **426** | 342 |
| 700 | `type=ime frame=[0,274][1366,700]` | **426** | 274 |
| 660 | `type=ime frame=[0,234][1366,660]` | **426** | 234 |
| 620 | `type=ime frame=[0,194][1366,620]` | **426** | 194 |
| 600 | `type=ime frame=[0,261][1366,600]` | **339** | 261 |
| 595 | `type=ime frame=[0,334][1366,595]` | **261** | 334 |
| 590 | `type=ime frame=[0,329][1366,590]` | **261** | 329 |
| 589 | `type=ime frame=[0,328][1366,589]` | **261** | 328 |
| 588 | `type=ime frame=[0,327][1366,588]` | **261** | 327 |
| 587 | `type=ime frame=[0,326][1366,587]` | **261** | 326 |
| 586 | `type=ime frame=[0,325][1366,586]` | **261** | 325 |
| 585 | `type=ime frame=[0,324][1366,585]` | **261** | 324 |
| 580 | `type=ime frame=[0,319][1366,580]` | **261** | 319 |
| 570 | `type=ime frame=[0,309][1366,570]` | **261** | 309 |
| 560 | `type=ime frame=[0,299][1366,560]` | **261** | 299 |

**distinct imeH = {426, 339, 261} —— 三个值。**

### 混淆被证实，且能精确定位塌缩点

补测 604→592 逐 dp：

| H | 604 | 603 | 602 | 601 | **600** | **599** | 598 | 597 | 596 | 595 | 594 | 593 | 592 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| imeH | 339 | 339 | 339 | 339 | **339** | **261** | 261 | 261 | 261 | 261 | 261 | 261 | 261 |
| room | 265 | 264 | 263 | 262 | **261** | **338** | 337 | 336 | 335 | 334 | 333 | 332 | 331 |
| 形态 | ONEROW | ONEROW | ONEROW | ONEROW | **ONEROW** | **STACKED** | STACKED | STACKED | STACKED | STACKED | STACKED | STACKED | STACKED |

**结论：Gboard 确实随窗口变矮而变矮。** 窗口 600 → 599 的**单个 dp**，ime 从 339 塌到 261（**−78px**），于是 `room` 从 261 **暴涨到 338（+77px）**。表单形态是对 `room` 的响应，`room` 在此非单调，形态自然回翻。

ime 是**阶梯状**而非连续跟随：426（≥620）→ 339（600..601+）→ 261（≤599），之后 560 一直保持 261 不再变。

---

## 2. 任务 A：逐级形态表 + 单调性判定

判据用**双判别器**互相印证，二者全表一致（无 CONFLICT）：

- **byWidth**：`EditText` 宽 < 800 ⇒ ONEROW（实测 375~381）；≥ 800 ⇒ STACKED（实测 1310）
- **byRow**：`Send.y1 <= chip.y2 + 2` ⇒ ONEROW（同行）；否则 STACKED（Send 落到下一行）

| H | imeH | room | 形态 | byW / byRow | ET 宽 | chip 尺寸 | Send y1–y2 |
|---:|---:|---:|---|---|---:|---|---|
| 768 | 426 | 342 | STACKED | STACKED/STACKED | 1310 | 48×48 | 292–340 |
| 700 | 426 | 274 | STACKED | STACKED/STACKED | 1310 | 48×48 | 292–340 |
| 660 | 426 | 234 | **ONEROW** | ONEROW/ONEROW | 381 | 48×34 | 168–216 |
| 620 | 426 | 194 | **ONEROW** | ONEROW/ONEROW | 375 | 48×44 | 156–204 |
| 600 | 339 | 261 | **ONEROW** | ONEROW/ONEROW | 381 | 48×48 | 191–239 |
| 595 | 261 | 334 | STACKED | STACKED/STACKED | 1310 | 48×46 | 276–324 |
| 590 | 261 | 329 | STACKED | STACKED/STACKED | 1310 | 48×41 | 271–319 |
| 589 | 261 | 328 | STACKED | STACKED/STACKED | 1310 | 48×40 | 270–318 |
| **588** | **261** | **327** | **STACKED** | STACKED/STACKED | 1310 | 48×39 | 269–317 |
| 587 | 261 | 326 | STACKED | STACKED/STACKED | 1310 | 48×38 | 268–316 |
| 586 | 261 | 325 | STACKED | STACKED/STACKED | 1310 | 48×37 | 267–315 |
| 585 | 261 | 324 | STACKED | STACKED/STACKED | 1310 | 48×36 | 266–314 |
| 580 | 261 | 319 | STACKED | STACKED/STACKED | 1310 | 48×31 | 261–309 |
| 570 | 261 | 309 | STACKED | STACKED/STACKED | 1310 | 48×21 | 251–299 |
| 560 | 261 | 299 | STACKED | STACKED/STACKED | 1310 | 48×11 | 241–289 |

### 588 ± 3 逐 dp 明细

| H | room | 形态 | 判定 |
|---:|---:|---|---|
| 589 | 328 | STACKED | 与 588 同形态 |
| **588** | **327** | **STACKED** | **断点不在此** |
| 587 | 326 | STACKED | 与 588 同形态 |
| 586 | 325 | STACKED | 与 588 同形态 |

**588 邻域完全同形态，无任何逐 dp 跳变。** v156 声称的"588 是断点"在真机上**不存在**。

### 单调性判定（分两种条件，按要求分开判）

**① 固定 ime 时形态是否单调 —— ✅ PASS**

把同一批实测点按 `room` 重排（这是"ime 被钉住"下的正确自变量）：

```
room=194 (H=620 ime=426) ONEROW
room=234 (H=660 ime=426) ONEROW
room=261 (H=600 ime=339) ONEROW
room=274 (H=700 ime=426) STACKED   ← 阈值落在这里
room=299 (H=560 ime=261) STACKED
room=319 (H=580 ime=261) STACKED
room=327 (H=588 ime=261) STACKED
room=334 (H=595 ime=261) STACKED
room=342 (H=768 ime=426) STACKED
=> form vs room 单调不增: True
```

**形态对 `room` 严格单调不增**，阈值在 **room ∈ (261, 274]**，与源码 `composerCompact` 的 224 dp 阈值（叠加 162px chrome ⇒ room 274~386 区间）一致。**composer 的形态决策逻辑本身没有非单调性，第二处 `>= 0.dp` 修复未被证伪。**

**② 真实（ime 会变）条件下是否单调 —— 🔴 FAIL**

沿 H 变小方向：768 S → 700 S → 660 **O** → 620 **O** → 600 **O** → 595 **S** → 590 S → … → 560 S。

**600 → 599 出现回翻：ONEROW → STACKED**（形态变大，而窗口变矮）。3 遍重复（`v157_A5` A5-3）结果**完全一致**，非抖动：

```
pass1/2/3  600 ime=339 room=261 ONEROW   chip=[1264,192][1312,240] 48x48
pass1/2/3  595 ime=261 room=334 STACKED  chip=[1264,192][1312,238] 48x46
pass1/2/3  588 ime=261 room=327 STACKED  chip=[1264,192][1312,231] 48x39
pass1/2/3  580 ime=261 room=319 STACKED  chip=[1264,192][1312,223] 48x31
```

### 明确回答任务 A 的三个问题

1. **v155 观测到的回翻（600→580）在本轮是否消失？**
   **没有消失，但位置和性质都变了。** v155 的 600→580 回翻，其真实成因是 ime 塌缩；本轮把网格加密到逐 dp 后，**回翻被精确定位到 600→599 这一个 dp 的边界上**（而非 v155/v156 认为的 588）。形态序列 `ONEROW(600) → STACKED(595…560)` 与 v155 描述的"600 一行、580 完整"**表象一致**，所以"消失"这一说法不成立。

2. **588 这一级是什么形态？**
   **STACKED（堆叠/完整形态）**，`EditText` 宽 1310、chip 48×39、Send 独立成行（y 269–317）。与 589/587/586 完全同形态，**588 本身没有任何特殊性**。

3. **断点是否仍是 588？**
   **不是。断点是 600/599 之间的 ime 塌缩边界。** 588 是 v156 在"ime 恒为 426"假定下用 `room = H − 426` 算出来的**伪断点**：真机上 588 处的 ime 早已塌到 261，`room` 是 **327** 而非预测的 162，根本没落到 `composerCompact` 的 0 dp 边界。**v156 的 588 结论建立在一个被本轮证伪的前提上。**

> 注：修正一处 v156 的推导瑕疵 —— 它在注释里写 `window 588 -> box 426 -> room = 426 - 426 = 0`。真机上该窗口级的 ime 为 261，`room` 为 327。断点之所以"看起来"在 588，是 426 这个常数恰好落在 Gboard 某个梯级上造成的巧合。

---

## 3. 任务 B：基线不回退 + 六条判据

### B1 基线（逐位对照）

| 档位 | 期望 | 实测 | 判定 |
|---|---|---|---|
| 768 | **140** | 140（`paneH=188` ⇒ timelineBox `188−48`） | ✅ 不漂 |
| 768 | **92** | 92（`readable=188−96`） | ✅ 不漂 |
| 768 | **① −10** | **−10** | ✅ 不漂 |
| 600 | **51** | 51（`paneH=99` ⇒ `99−48`） | ✅ 不漂 |
| 600 | **① −12** | **−12** | ✅ 不漂 |

> 脚本首次输出把 768 的 140/92 标成 DRIFT，是断言把 `readable`/`timelineBox` 两个字段名写反所致，**实测值本身 140 与 92 均正确**（`paneH=188`，`readable=92`，`timelineBox=140`，`C1=−10`）。已按正确字段名复核，无漂移。

### B2 六条判据

**600 档（ONEROW 形态）—— 6/6 PASS**

| 判据 | 实测 | 结果 |
|---|---|---|
| C1 `overlapArea == 0` | `0`（交集 0×47） | ✅ PASS |
| C2 `narrowestResidualPx ≥ 16` | `48`（coverage 0×0，残余 48×48） | ✅ PASS |
| C3 `chipVisiblePixelCount > 0` | chip 48×48 全可见 | ✅ PASS |
| C4 两靶各 48 高 | chip.h=48，send.h=48 | ✅ PASS |
| C5 768 档 0 遮挡 | 见 768 行 | ✅ PASS |
| C6 两者 `clickable` | chip=true，send=true（enabled=true） | ✅ PASS |

靶点坐标：chip `[1264,192][1312,240]`，send `[1316,191][1364,239]`。

**768 档（STACKED 形态）—— 同样 6/6 PASS**

`overlapArea=0`（交集 4×0，横向不重叠）、residual 48×48、chip 48×48、send 48×48、clickable true/true。Send 落在 y 292–340（独立成行），与 chip 行 y 192–240 不重叠。

### B3 往返 2 轮 + logcat

```
RT1  600 paneH=99  readable=51  C1=-12 imeH=339 FORM=ONEROW
RT1  768 paneH=188 readable=92  C1=-10 imeH=426 FORM=STACKED
RT2  600 paneH=99  readable=51  C1=-12 imeH=339 FORM=ONEROW
RT2  768 paneH=188 readable=92  C1=-10 imeH=426 FORM=STACKED
```

两轮**逐位复现**，无漂移。logcat（`logcat -d -t 3000`，按已知坑用 `-t`+`Select-String` 而非 `--pid=`）：

```
FATAL EXCEPTION = 0 ; FATAL = 0 ; IllegalArgumentException = 0 ; maxHeight = 0
```

---

## 4. 任务 C：收尾

| 项 | 状态 |
|---|---|
| `wm size` | `Override size: 1366x768` ✅ |
| `wm density` | `Override density: 160` ✅ |
| `show_ime_with_hard_keyboard` | 改动前读到 **1**，已按 1 还原，现为 **1** ✅ |
| 自建内容 | composer 内 `v157monotonecheckv157monotonecheck`（重复两遍，系 FillComposer 首次执行时旧文本未清）已 **DELETE×40 清空为 `''`** ✅；**未发送任何消息，未新建会话** |
| `/sdcard` 残留 | v157 自建的 `__e2e.xml` / `__e2e.png` / `__v157.png` **已全部删除，复查 clean** ✅ |
| 其余 `/sdcard` 文件 | `B1_*`、`T0_*`、`R1_*`、`a1.xml` 等为 **v155/v156 旧轮次遗留，非本轮创建**，未擅自删除（如需清理请确认） |

权限网关：本轮删除仅限 `/sdcard` 下自建文件，未触碰任何受保护路径，未绕过任何权限网关。

---

## 5. 未闭环 / 建议下一轮

1. **ime 阶梯是 Gboard 的还是 resize 策略的？** 本轮只观测到"随窗口变矮而塌缩"（426→339→261），**未能确认触发条件**。候选解释：(a) Gboard 在可用高度低于某阈值时自动收起部分行；(b) `ADJUST_RESIZE` 下 app 窗口与 ime 互相挤压产生的反馈环。需要固定 Gboard 布局（设置"全屏/展开"）或在 ime 打开前后各测一轮来区分。

2. **真实条件下"形态单调"这个判据本身是否可满足？** 若 ime 随窗口变化是 Gboard 的固有行为，则**任何依赖 ime 的形态判据在真实条件下都不可能全局单调**。建议 Lead 明确：该判据应以 **`room`（钉住 ime）** 还是 **`H`（真实条件）** 为准。若以 `H` 为准，需要产品侧先定义"ime 变化时的期望行为"。

3. **588 断点已被证伪，但 v156 的注释仍写着它。** `SessionPaneLayout.kt:764-771` 的实测表基于 `ime 426` 假定，与真机不符。建议按本轮数据修订注释，否则后续轮次会再次被误导。**本轮未改源码**（职责为复验）。

4. **v156 那处 `>= 0.dp` 修复未被本轮证伪，也未被证实** —— 真机上没有出现 `room ≈ 0` 的窗口级（最低只到 room=134），该分支在当前 ime 行为下**不可达**。需要构造"ime 不随窗口变化"的场景（例如窗口在阈值附近而 ime 固定）才能真正触发。

5. **本轮未复现 v155 报告里 768 档 `140/92` 的字段归属争议**（脚本断言曾反写），建议下轮统一字段命名：`timelineBox = paneH − 48`、`readable = paneH − 96`（paneH ≥ 150 时）。

---

## 附：产物与脚本

- 截图 + XML：`research/v97_verify/5554_md/artifacts_v157/`（`A_<H>.png`/`A_<H>.xml` 15 级、`A5*` 复核、`B_768`/`B_600`、`crit_B2_600`/`crit_B2_768`）
- 脚本：`v157_lib.ps1`（含新增 `ImeRaw` 原始帧读取）、`v157_A.ps1`、`v157_A5.ps1`、`v157_B.ps1`、`v157_C.ps1`
- 日志：`_v157_A.log`、`_v157_A5.log`、`_v157_B.log`、`_v157_C.log`
