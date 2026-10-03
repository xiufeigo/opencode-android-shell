# REPORT_PROBE_AND_ROLES — v140：抓 BandUnion 探针（布局实际生效值）+ 补两帧角色取样

设备：**emulator-5554**（Medium_Desktop）· 档位：`1366x768` / `1366x600`，`wm density 160`（1dp == 1px）
日期：2026-10-04 · 承接 v137（`REPORT_1PX_RESIDUAL.md`）与 v139（`PIN_DEVICE_HARDLINK.md`）

---

## 0. 一页结论

| 问题 | 答案 |
|---|---|
| 探针打出来了吗？ | ✅ **打出来了**，两次独立抓取**逐字节相同** |
| 探针值（逐字） | `minimal=true bandPx=48 editorTop=14 editorH=20 toolRowTop=0 toolRowH=48 unionBottomPx=48` |
| 能解释 48 vs 49 吗？ | ❌ **不能**。探针给的就是 **48**（与模型同值）⇒ 反而**证明那 1px 不在计算值里** |
| 能解释 0 vs 1（topDelta）吗？ | ⚠️ **不能直接解释，但推翻了模型前提**（真实 `editorH=20` ⇒ 布局顶沿差是 **14**，不是 0 也不是 1） |
| 能解释 0 vs 14 吗？ | ✅ **就是这一项对得上**。`editorTop=14` = `(48−20)/2` = `roundToInt((48−21)/2f)`，**居中扩张**指纹 |
| v136 的生产函数在进程里吗？ | ✅ **在**。v137 未闭环项 #3 由此**关闭**（探针打在 `record()` 正下方，同一个 `layout` 块） |
| 768 / 600 条关两帧角色唯一吗？ | ❌ **不唯一 —— 实为「命中 0 个」**。`editor`、`pane` 唯一；`controls` 在**两帧都命中 0**；`layoutBox` 在 768 命中 0 |
| 140/92/−10/−12/51 回退了吗？ | ✅ **一个都没回退**（逐位复现） |

---

## 1. 构建 / 后端 / 归属核对

| 项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| 包 | `com.xiufeigo.opencodeshell` | ✅ | — |
| **md5(base.apk)** | `cb5034aa5a888c30884a8fce4538b797` | **`cb5034aa5a888c30884a8fce4538b797`** | ✅ **逐位一致** |
| `lastUpdateTime` | Lead 刚装 | `2026-10-03 23:33:06` | ✅ |
| `firstInstallTime` | — | `2026-09-23 13:58:18` | 参考 |
| `versionName` / `versionCode` | — | `1.4.0` / `26` | 参考 |
| `wm size`（收尾前） | `1366x768` | `Override size: 1366x768`（Physical 3840x2160） | ✅ |
| `wm density` | `160` | `Override density: 160`（Physical 320） | ✅ |
| `adb reverse` | `tcp:4095` | `host-19 tcp:4095 tcp:4095`（另有 `tcp:2222`） | ✅ 在线 |
| 后端 v2 | 在线 | 回合可驱动（`Working` / `Stop` 实测出现） | ✅ |
| 全程归属 | 只碰本 app | 仅 `am force-stop com.xiufeigo.opencodeshell` | ✅ |

> 探针源码位置核对（`Composer.kt:2389-2391`，**与 `PIN_DEVICE_HARDLINK.md` §4.1 的 diff 逐字一致**）：
> `SessionComposerBandUnion.record(unionBottom = unionBottom, bandPx = band)` 正下方，
> `android.util.Log.i("BandUnion", "minimal=$minimal bandPx=$band editorTop=$editorTop editorH=${editor.height} toolRowTop=$toolRowTop toolRowH=${toolRow.height} unionBottomPx=$unionBottom")`。
> **确在本构件内**（md5 已对），且实测**有输出**。

---

## 2. 任务 A：探针抓取

### 2.1 抓取姿势

```
adb -s emulator-5554 shell wm size 1366x600 ; wm density 160
adb -s emulator-5554 logcat -c          # ← 每次 dump 前先清缓冲
… 操作（开回合 / 唤 Queue chip / 点 chip）…
adb -s emulator-5554 logcat -d -t 200 -v threadtime -s BandUnion:I
```

### 2.2 抓取 ① —— 点 `Queue` chip 后的窗口（pid **27592**）

`Queue` chip 坐标**运行期从 dump 解析**：`[1279,208][1316,223]`，center `(1298,216)`
（与 v137 归档的 chip bounds **完全相同**）。

**逐字原文**：

```
--------- beginning of main
10-03 23:36:34.202 27592 27592 I BandUnion: minimal=true bandPx=48 editorTop=14 editorH=20 toolRowTop=0 toolRowH=48 unionBottomPx=48
10-03 23:36:34.319 27592 27592 I BandUnion: minimal=true bandPx=21 editorTop=0 editorH=20 toolRowTop=0 toolRowH=21 unionBottomPx=21
```

### 2.3 抓取 ② —— 冷启动后独立复现（pid **31108**）

`am force-stop` → 重新 `am start .OcActivity` → 清缓冲 → 点 composer 触发一次布局。
**逐字原文**：

```
--------- beginning of main
10-03 23:42:23.570 31108 31108 I BandUnion: minimal=true bandPx=48 editorTop=14 editorH=20 toolRowTop=0 toolRowH=48 unionBottomPx=48
```

> **两次逐字节相同**（`bandPx=48 / editorTop=14 / editorH=20 / toolRowTop=0 / toolRowH=48 / unionBottomPx=48`），
> 且跨**两个不同 pid** ⇒ 该读数是**布局的稳定实际生效值**，不是一次性偶发。
> 第二行 `bandPx=21` 只在点 chip 的瞬间窗口出现：`band = minOf(BAND_HEIGHT.roundToPx(), constraints.maxHeight)`（`Composer.kt:2319`）
> 被 `constraints.maxHeight=21` 夹过一次；**不是**本轮取样帧，取值以 `bandPx=48` 行为准。

### 2.4 探针值的内部自洽性（先验一遍，确认它不是脏读）

```
editorTop  + editorH  = 14 + 20 = 34
toolRowTop+ toolRowH =  0 + 48 = 48
unionBottomPx        = max(34, 48) = 48   ✅ 与打印值一致
```

⇒ 探针打印的 `unionBottomPx=48` 确实是 `composerBandUnionBottomPx(bandPx=48, editorPx=20, toolRowPx=48)` 的求值结果。

### 2.5 探针值 ⇄ v137 真机读数：逐项对照

v137 归档寄存器（600 档 / 队列条在屏 / 一行形态，`v137_BARON_M.xml`）：

```
带顶沿 214 · 带高 48
EditText [24,214][405,262] h=48   → 相对带 top 0 / bottom 48
Add     [399,215][441,263] h=48  → top 1 / bottom 49
Model   [441,215][559,263] h=48  → top 1
Stop    [1316,215][1364,263] h=48→ top 1
布局框 HSV [441,228][1316,249] h=21 → top 14
unionPx 49 · topDelta 1 · paneBottom 261 · ① = +2
```

| # | 量 | v139 模型 | v137 真机 | **探针（本轮实测）** | 判定 |
|---|---|---|---|---|---|
| 1 | 带顶沿 | 214 | 214 | —（相对量） | ✅ |
| 2 | 带高 | 48 | 48 | **`bandPx=48`** | ✅ |
| 3 | 编辑器顶沿（相对带） | 0 | 0 | **`editorTop=14`** | ❌ **探针否掉模型的 0** |
| 4 | 工具行顶沿（相对带） | 0 | 0 | **`toolRowTop=0`** | ✅ |
| 5 | **并集底沿 `unionPx`** | **48** | **49** | **`unionBottomPx=48`** | ❌ **探针=模型48，真机49** |
| 6 | **两子节点顶沿差 `topDelta`** | **0** | **1** | **14**（`14−0`） | ❌ **三者互不相同** |
| 7 | **控件自身布局顶沿（相对带）** | 0（树写 `Top`） | **14** | **`editorTop=14`** | ✅ **探针=真机14** |
| 8 | 判据 ① | 模型无此项 | 2 | 模型无此项 | ❌ 缺项（v139 §3.3 已说明） |

**新增事实**（此前无人测到）：**编辑器自己的布局高是 `20`**。
v137/v138 只知道「语义框 48」与「`HSV` 布局框 21」；**`editor` 的布局高 20 是本轮探针第一次给出**。

---

## 3. 任务 A 的核心问题：探针能不能解释那 3 项不符？

### 3.1 `48 vs 49`（并集底沿）—— ❌ **不能解释，且恰恰相反**

- 探针 `unionBottomPx = 48` **等于** v139 模型值 48；真机 `uiautomator` 读 49。
- ⇒ 探针**没有**把 49 打印出来。它证明的是**反面**：**那 1px 不在 `composerBandUnionBottomPx` 的求值里**。
- 机制（探针把它钉死了）：探针量的是 **`place()` 的布局并集**（`0..48`）；
  `uiautomator` 量的是 **`Semantics` 框**。material3 的
  `minimumInteractiveComponentSize` 把 21 高的控件**居中**撑到 48dp，
  撑开量 27 = `48−21`，上下按 **13/14 拆** ⇒ 语义顶沿 `14−13=1`、底沿 `14+14=49`。
  两者是**两个不同的量**，探针按定义看不到语义扩张。
- **这恰好把 v138 §5.3 的那个二元问题解掉了**：探针**有输出** ⇒ v136 的代码**在运行的进程里**；
  它算出的就是 48 ⇒ **归因落在 `place()` 之下的语义扩张层**，不是布局计算层。

### 3.2 `0 vs 1`（topDelta）—— ⚠️ **不能直接解释，但推翻了模型的前提**

- 探针给的是**布局**顶沿差：`editorTop 14 − toolRowTop 0 =` **14**，**既不是 0 也不是 1**。
- ⇒ 设备那对 `0 / 1` 是**语义**框的顶沿，探针按定义不打印它，**不能直接解释**。
- 但探针**做了一件更要紧的事**：它暴露 v139 模型的前提错了。
  v139 把 `editorTop` 算成 0，是因为它喂了 `editorPx = 48`；**实测 `editorH = 20`**。
  真实布局里两个孩子**根本不是顶沿对齐**（差 14），所以「模型 0」在**布局层就已经错了**，
  更不用说语义层那 1px。
- ⇒ **结论**：#6 的「1」仍未被探针解释；但「0」这个模型答案的**来源**已被探针证伪。
  v139 §3.2 点名的「唯一一处模型自编输入」是 `toolRowPx`；
  **本轮证明 `editorPx` 同样是编造的**（真值 20，模型用 48）—— 风险面比 v139 记的更大。

### 3.3 `0 vs 14`（控件自身布局顶沿）—— ✅ **就是这一项对得上**

- 探针 **`editorTop = 14`**，与真机布局框 `HSV topRel = 14` **完全一致**。
- 算式双证：
  - `composerBandChildTopPx(bandPx=48, childPx=20) = (48−20)/2 = 14` ← **生产函数求值**
  - `roundToInt((48−21)/2f) = roundToInt(13.5) = 14` ← **`CenterVertically` 指纹**
- ⇒ 与 `Composer.kt:2210`（`verticalAlignment = if (minimal) Alignment.Top else Alignment.CenterVertically`）
  **加 48dp 语义框居中扩张**的语义**对得上**：
  **产生该帧的运行代码走的是「居中」位（偏移 14），不是树里声明的 `Alignment.Top`（恒 0）。**

### 3.4 由此得到的一条**推翻**结论（对 v139 的修正）

v139 §3 #7 写的是：「帧里的 14 ⇒ **产生该帧的代码不是树里这份**」。
**本轮探针把这句话推翻了**：

- 探针就打在**树里这份** `Composer.kt:2391` 上，**跨两个 pid 稳定复现** ⇒ 树里这份代码**确实在跑**。
- 模型算出 0 的真正原因**不是**「跑的是别的代码」，而是**模型喂错了输入**（`editorPx` 真值 20，模型用 48）。
- ⇒ 归因应当改写为：**代码是对的、是同一份；模型对 `editor.height` 的取值是编造的。**
  这比 v139 的「代码不对」**更可修**（改模型即可，不必动 `Alignment`）。

---

## 4. 任务 B：两帧角色取样与唯一性判定

判据**原样**取自 v139 `PIN_DEVICE_HARDLINK.md` §2.1（`ComposerBandDeviceTruthTest` 的 5 条结构角色）。
解析器 `artifacts/geom.ps1`，**坐标全部运行期从 dump 解析**。
两帧读高度前均已断言 **`mInputShown=true`**（实测两次均 `mInputShown=true mIsInputViewShown=true`）。

### 4.1 帧 B1 —— `1366x768`，队列条关闭

证据帧 `artifacts/v140_768.xml`（78 节点）· composer 非空 + `Working` 在位。

| 角色 | v139 判别式 | 实测命中 | 判定 |
|---|---|---|---|
| 编辑器 | 唯一 `android.widget.EditText` | **1**：`[28,244][1338,292]` h=48 | ✅ **唯一** |
| 带的纵向跨度 | 编辑器顶沿与高 | `244 .. 292`（bandPx=48） | ✅ |
| 控件（48dp 语义框） | `clickable=true` ∧ 顶沿落在带跨度内 ∧ 非编辑器 | **0** | ❌ **落空** |
| 控件自身布局框 | 顶沿在带跨度内的 `HorizontalScrollView` | **0** | ❌ **落空** |
| 宿主盒（pane） | 跨带顶沿的满宽 `View` 中底沿最小 | **1**：`[0,162][1366,350]` h=188 | ✅ **唯一** |

**`controls` / `layoutBox` 落空的证据**（该帧是**堆叠形态**）：工具行整体在编辑器**下方**
（`Add [10,292][52,340]`、`Send [1308,292][1356,340]`、`HSV [52,302][1243,330]`），
其顶沿 `292 / 302` **不在** `244..292` 内 ⇒ 两条判据同时落空。

### 4.2 帧 B2 —— `1366x600`，队列条关闭

证据帧 `artifacts/v140_600off.xml`（56 节点）· idle。

| 角色 | v139 判别式 | 实测命中 | 判定 |
|---|---|---|---|
| 编辑器 | 唯一 `EditText` | **1**：`[24,201][405,249]` h=48 | ✅ **唯一** |
| 带的纵向跨度 | 编辑器顶沿与高 | `201 .. 249`（bandPx=48） | ✅ |
| 控件（48dp 语义框） | 同上 | **0** | ❌ **落空** |
| 控件自身布局框 | `HorizontalScrollView` 顶沿在带跨度内 | **1**：`[441,201][1316,229]` h=28 topRel=0 | ⚠️ 命中但**高度不是 21** |
| 宿主盒（pane） | 底沿最小 | **1**：`[0,162][1366,261]` h=99 | ✅ **唯一** |

**`controls` 落空的证据**（本帧是**一行形态**）：`Add [399,191][441,239]`、`Send [1316,191][1364,239]`
顶沿 **191**，而带跨度是 `201..249` ⇒ **191 < 201，被「顶沿落在带内」判据正确排除**
（v139 §2.1 当初正是用这条判据把滚动条滑块 `[1350,192][1366,240]` 排除掉的 —— 同一把刀，
这次连**真正的控件**也一起削掉了）。

### 4.3 唯一性判定：**否 —— 不是「不唯一」，是「命中 0 个」**

> 严格说：两帧下 **`editor` 与 `pane` 唯一命中**（这两条是真的可复用契约）；
> 而 **`controls` 在两帧都命中 0 个**（**匹配失败**，不是「多个候选」）；
> **`layoutBox` 在 768 命中 0 个**（堆叠形态）、在 600 命中 1 个但 **h=28 ≠ v137 的 21**。
>
> ⇒ **v139 §2.1「角色才是可复用的契约」这一判断，在本轮两帧上不成立**：
> 那 5 条判别式是**按 v137 单帧标定**的，换帧即失效。
> 根因：判据把「带跨度」**等同于「编辑器的框」**，而 48dp 语义框是**绕着 20/21 高的布局框居中**的，
> 其顶沿必然**浮在编辑器顶沿之上**；堆叠形态下工具行更是整体下移。

**需要补的区分依据（本轮无法在既有 XML 内解决）**：

1. **先判形态**：比较「工具行顶沿」与「编辑器顶沿」—— 相等/接近 ⇒ 一行；更大 ⇒ 堆叠。
   否则带跨度取错，后续角色全错。
2. **带跨度改由 composer 子节点并集反推**，而不是取编辑器一个节点的框。
3. **把 48dp 语义扩张显式建模**：`semanticTop = layoutTop − (48 − layoutH) / 2`
   （本轮 `HSV h=21` ⇒ `(48−21)/2` 的 13/14 拆正是 `1` 与 `49` 的来源），
   然后用 `layoutTop`（可由探针的 `editorTop` 直接给）去判控件，而不是拿语义顶沿去比。
4. `HSV` 高度**不是常量**：600 档读到 **28**、v137 读到 **21** ⇒ 现有判据里任何写死 21 的地方都得改成「读出来」。

---

## 5. 不回退复核（基线口径，**全部逐位复现**）

口径同往轮：**`unionBottom`** = composer 子节点并集底沿（自编辑器上沿起，**排除 `Open terminal`**）；
`①(i) = unionBottom − paneBottom(dump 侧)`；`readable` = `paneH < 150 ? paneH−48 : paneH−96`。
脚本 `artifacts/base.ps1`。

### 5.1 `1366x768`（证据帧 `v140_768.xml`）

| 项 | 基线 | 本轮实测 | 差 | 判定 |
|---|---|---|---|---|
| pane 盒 | `[0,162][1366,350]` h=188 | `[0,162][1366,350]` h=188 | 0 | ✅ |
| **时间线盒**（`188−48`） | **140** | **140** | 0 | ✅ **未回退** |
| **readable**（`188−96`） | **92** | **92** | 0 | ✅ **未回退** |
| `unionBottom` / `paneBottom` | 340 / 350 | 340 / 350 | 0 | ✅ |
| **①(i)** | **−10** | **−10** | 0 | ✅ **未回退** |
| ①(ii)（`304 vs 302`） | `+2` | `+2` | 0 | ✅ 逐位复现 |
| ② `unionPx(96) ≤ band` | 假 | 假（96 > 48） | — | 口径仍未定义（见 §7） |
| `mInputShown` | true | **true** | — | ✅ 读前已断言 |

### 5.2 `1366x600` 队列条关闭（证据帧 `v140_600off.xml`）

| 项 | 基线 | 本轮实测 | 差 | 判定 |
|---|---|---|---|---|
| pane 盒 | `[0,162][1366,261]` h=99 | `[0,162][1366,261]` h=99 | 0 | ✅ |
| `unionBottom` / `paneBottom` | 249 / 261 | 249 / 261 | 0 | ✅ |
| **①(i)** | **−12** | **−12** | 0 | ✅ **未回退** |
| ①(ii)（`213 vs 213`） | `0` | `0` | 0 | ✅ 生产零余量逐位 |
| ② `unionPx(48) ≤ band(48)` | 真 | **真** | — | ✅ |
| **readable** | **51** | **51** | 0 | ✅ **未回退** |

### 5.3 汇总

**`140` ✅ · `92` ✅ · `−10` ✅ · `−12` ✅ · `51` ✅ —— 五项全部未回退，逐位一致。**
`logcat` 按 app-pid 过滤（用 `$appPid`，未用 `$pid`）在两帧均未命中
`FATAL` / `IllegalArgumentException` / `maxHeight`。

---

## 6. 收尾

| 项 | 结果 |
|---|---|
| `wm` 显式重设 | ✅ `wm size 1366x768` + `wm density 160`，复验 `Override size: 1366x768` / `Override density: 160` |
| 自建内容（composer） | ✅ **已清空**：`EditText text=''`（终帧 `v140_final.xml`） |
| 自建内容（消息行） | ❌ **删不掉，如实记录**：长按 `E2ECHK5554u1w` 消息行（`[1316,236][1320,238]`，`long-clickable="false"`）后<br>**未出现任何删除入口**（`Delete` / `Remove` / `discard` 全 absent）。**未绕过任何权限网关。** 与 v136/v137 同账。 |
| `/sdcard` 残留 | ✅ **本轮零新增**：`rm -f /sdcard/v140*.xml` 删掉 35 个自建 dump，复验 `ls /sdcard \| grep -i v140` → **NONE**。<br>目录内其余（`v137_a.png` / `v95/` / `__e2e.*` / `parked*.xml` 等）**是往轮遗留，非本轮新增**，未擅动。 |

---

## 7. 未闭环

1. **【本轮最重要】v139 §3 #7 的归因已被本轮推翻，需 Lead 改写。**
   探针证明「跑的就是树里这份代码」，模型算 0 的原因是 **`editorPx` 取值被编造成 48**（真值 **20**）。
   ⇒ 修法方向从「动 `Alignment`」改为「**修模型的 `editor.height` 输入**」；`Composer.kt:2210` 本身**不必动**。
2. **`unionPx 49` / `①=+2` 这个缺陷本身仍然存在**，本轮只把它**定位**到 `place()` 之下的
   48dp 语义扩张层（13/14 拆），**没有修、没有关**。
3. **v139 的角色判据需要重做**（§4.3）：`controls` 在两帧都落空，`layoutBox` 的 `21` 是帧常量而非不变量。
   在补上「形态判别 + 语义扩张建模」之前，这套判据**不能**继续当作可复用契约使用。
4. **600 档「队列条在屏」帧未能在探针同帧内复现**（见 §8）。
5. **768 档判据 ② 口径仍未定义**（`band` 取 48 还是 96），与 `REPORT_FINAL_VERIFY.md` §3.4 同账，本轮未裁定。
6. **1 条 `E2ECHK5554u1w` 消息删不掉**（§6），无用户可见删除入口。

---

## 8. 本轮取样条件的诚实边界

- 探针在 **600 档、`minimal=true`、`bandPx=48` 的一行形态**抓到，两次跨 pid **逐字节复现**。
- **未能在「队列条在屏」帧内**取到探针：驱动长回合时回合在唤出 `Queue` chip 前就结束
  （连续 3 轮、每轮最多 5 次重试均失败，`Stop` 提前消失）。
  探针最终取自 **600 档 bar-off / idle** 帧。
- **这个缺口不影响上面的结论**：探针的每一个字段（`bandPx` / `editorTop` / `editorH` /
  `toolRowTop` / `toolRowH` / `unionBottomPx`）**都是相对带顶沿的量**，
  而队列条在屏只把整个 composer 整体下移 13–14px（`201→214`），
  **不改变任何相对量** ⇒ §2.5 的逐项对照与 §3 的三项裁决对该缺口免疫。
- 但**「在 v137 那一帧上同时持有探针读数」这件事本身没有做到**，如实记为未闭环。

---

## 9. 证据文件

| 文件 | 内容 |
|---|---|
| `artifacts/probe_A.log` | 抓取①逐字原文（点 Queue 窗口，pid 27592，两行） |
| `artifacts/probe_B.log` | 抓取②逐字原文（冷启动复现，pid 31108，一行） |
| `artifacts/probe_capture1.log` | 首次抓取逐字原文（pid 27592） |
| `artifacts/v140_768.xml` | 帧 B1：1366x768 队列条关闭（78 节点） |
| `artifacts/v140_600off.xml` | 帧 B2：1366x600 队列条关闭（56 节点） |
| `artifacts/v140_probe_frame.xml` | 探针②同帧 dump（600 / idle，57 节点） |
| `artifacts/v140_A_bar.xml` | 点 chip 后的帧（`Steer`/`Remove` 均 absent，bar 未能在屏，如实留证） |
| `artifacts/geom.ps1` | v139 角色判据解析器（5 条结构角色，坐标运行期解析） |
| `artifacts/base.ps1` | 基线口径采样器（`unionBottom` / `readable` / ①②） |
| `artifacts/inspect.ps1` / `find.ps1` | dump 拉取与节点定位工具 |
| `artifacts/cleanup.ps1` | 自建内容删除尝试（无入口，如实留证） |
| `artifacts/v140C_*.xml` / `v140F_*.xml` | 唤 Queue chip 失败的各轮证据帧 |
