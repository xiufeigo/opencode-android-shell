# v98 专项扫测 D3 — U1 / U2 / U14 收口（emulator-5562 = Small_Desktop）

- 测试员：mavis（扫测 D3，三条收口轮）
- 日期：2026-10-03（host 12:24 起）
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 上游依据：`REPORT_SWEEPD2.md` §2 / §3.9 / §4.5 / §7.1（本轮三条正是其 §7.1 第 4、5 项与 U2 行）
- N 口径原始出处：`research/v97_verify/5562_sweepB/REPORT_v97b.md` §4「N1 / N2 / N3」三节
- 纪律：不跑 gradle / 不装机 / 不杀进程 / 不碰 127.0.0.1:5555；**全程未按 BACK(4)**；弹层内未按 ESC(111)（仅在主视图按过 ESC，属 N9 认可手法）；截图仅 `screencap -p` + `adb pull`（未用 exec-out）；输入 ≤8 字符分段、空格 = `keyevent 62`、未用 `%s`；dump **一律 `--compressed`** + `adb pull`（未用 exec-out）；`wm size/density` override **验完已 reset 并复核**；`show_ime_with_hard_keyboard` **全程只读未改**；**只动自建会话** `E2ECHK5562xD3U1`（收尾已删）；坐标运行期从 dump 解析；解析 dump **同时匹配** `text="…"` 与 `text='…'`。
- **未改任何代码。**

> **状态：DONE** — **U1 验到（FAIL）· U2 验到（有明确来源结论）· U14 三条全部验到（N1/N2 不复现 · N3 部分复现）**。
> **最高价值**：**U2 定案 —— 平台根本没有 resize 这个窗口**（`mDisplayFrame` 与 `Requested w=1366 h=768` 在键盘弹起/收起两侧逐字不变），高度变化是**本壳自己的布局**对平台 `ime` InsetsSource 的反应；这直接改写了 §3.2 「resize 来源」的提法。

---

## 1. 环境读数

| 项 | 读数 | 证据 |
|---|---|---|
| `adb devices` | `emulator-5562 device`（另有 5554/5556/5558/5564/5590/5600，**本轮只碰 5562**） | 本轮命令 |
| `adb -s emulator-5562 emu avd name` | **Small_Desktop** ✔ 与派单一致 | 本轮命令 |
| `wm size`（入测） | `Physical size: 1366x768`（**无 Override**） | 本轮首读 |
| `wm density`（入测） | `Physical density: 160`（**无 Override**） | 本轮首读 |
| SDK | `getprop ro.build.version.sdk` → **34** | 本轮命令 |
| `show_ime_with_hard_keyboard` | **`1`** ✔（入测核过，**全程未改**，收尾复核仍为 1） | `settings get secure` |
| reverse | `host-19 tcp:4095 tcp:4095`、`host-19 tcp:2222 tcp:22` ✔ | `adb reverse --list` |
| 后端 | `GET /api/info` → `UnauthorizedError`（**需鉴权，本轮未取数**） | 本轮命令 |
| `mCurrentFocus`（入测） | `org.chromium.home.pc/HostActivity`（**桌面**）→ `am start` 后切到 `OcActivity` | 本轮命令 |
| ANR | **全程无 ANR**，`mCurrentFocus` 健康 | 收尾复核 |
| 自建会话 | `E2ECHK5562xD3U1`（唯一新建，**收尾已 Delete 并复核消失**） | `d3_33_deleted` |

**坐标系（沿用上轮实测并本轮复核）**：导航栏恒占 `y=720..768`；应用内容区 `y=0..720`；**IME 弹起时键盘顶 `y=342`**（`d3_ins_imeON.txt` 的 `type=ime frame=[0,342][1366,768]`）。

**工具**：`_tools/d3_lib.ps1`（`Dump2`/`Shot`/`Find`/`AllNodes`/`ImeShown`；`Find`/`AllNodes` 双引号+单引号双匹配）。

> **方法论坑（给下一轮）**：本轮 PowerShell 里 `function Ty([string]$s)` 的参数名 **`$s` 与助手里的设备序列号变量 `$S` 大小写不敏感同名**，导致 adb 收到 `-s "<整句 prompt>"`，报 `device 'Write a long detailed…' not found`，**静默输空**。助手里凡带 `$S` 的脚本，函数参数**禁用 `s`/`S`**。另：`Dump` 返回 true 但文件 0 字节时（本轮遇 1 次 dump 桥接抖动）会污染解析，需按 **size > 500 字节**二次判定。

---

## 2. U1 —— 键盘 + 队列条并存的最极端高度：❌ **FAIL**

### 2.1 状态构造

自建会话 `E2ECHK5562xD3U1` → 发长回合 A（`Write a long detailed explanation of how Android window insets work, covering at least 30 numbered points.`）→ **流式中**发第二条 → 队列条出现 → 点 composer 唤出 IME（`mInputShown=true`）→ dump。

### 2.2 读数对照（**每一条读数前都断言了 `mInputShown=true`**）

| 状态 | 时间线容器（scrollable） | 队列条 | composer | 键盘顶 | 证据 |
|---|---|---|---|---|---|
| ① 流式中，**无**队列条 | `[0,162][1366,350]` **h=188** | — | `[28,248][1338,296]` | 342 | `d3_09_streaming` |
| ② 流式中 + **2 条队列** + composer 单行 | `[0,162][1366,350]` **h=188** | 行 `y=154..186` / `186..234` / `224..248` | `[28,261][1338,309]` | 342 | **`d3_11_queue`** + `d3_11_queue.png` |
| ③ 流式中 + **2 条队列** + composer **折成 2 行** | `[0,162][1366,178]` **h=16** | 行 `y=154..248` | `[28,261][1338,309]` | 342 | **`d3_14b`** / `d3_15_afterdeny` |
| ④ 极端窗口 `1366x600` + 密度 160 | `[0,162][1366,261]` **h=99** | 行 `y=174..188` / `218..266` | `[24,218][384,266]` | **261** | **`d3_17_narrow600`** + `d3_17_narrow600.png` |

③ 的时间线可见带只剩 `y=162..178` —— **16px**，且该带完全落在队列条 `y=154..248` 的覆盖范围内；`d3_14b` 里唯一的 `Working` 指示器落在 `y=344..346`（**在键盘顶 342 之下 2px，被键盘切掉**）。

### 2.3 三条断言逐条判定

| 断言 | 判定 | 依据 |
|---|---|---|
| 不出现「时间线被压到 0/负高度」 | ⚠️ **字面未破（16>0），实质已破** | ③ 号态时间线仅剩 **16px**，与队列条覆盖带完全重叠，**任何消息都不可读**。②→③ 的唯一变量是 composer 从 1 行折成 2 行（48px→96px），**时间线就被吃掉 172px** |
| 不出现「队列条遮挡时间线内容」 | ❌ **FAIL** | 队列条占 `y=154..248`，时间线容器仅 `[162,178]` ⇒ **队列条 100% 盖住时间线可视带**；截图 `d3_14_state.png` 可见 header 与队列条之间只剩一条发丝缝 |
| 不出现「composer 被键盘遮住」 | ✅ **PASS** | composer `[28,261][1338,309]`，键盘顶 **342** ⇒ **净空 33px**，composer 未被遮 |

### 2.4 结论

> **U1 = FAIL。** 在 **1366x768** 上，**流式 + 2 条队列 + IME** 三者并存时：
> - composer **未被键盘遮挡**（净空 33px）✔
> - 但**时间线被压到 16px 且被队列条完全覆盖** ✘ —— **「键盘+队列条并存」的最极端高度是可复现的真实缺陷，且它比上轮 N8 记录的 45–90px 更差**。
>
> **相对 N8 的新信息**：N8 记录的是「无队列条」时 45–90px；本轮量到**队列条才是主因**——同扇窗下 188px → 16px 的落差里，composer 只贡献 48px，**其余 124px 来自队列条本身**。也就是说：**队列条与时间线抢的是同一块垂直空间，且队列条优先**。
>
> ⚠️ **一处诚实标注**：在 override 前我另取到一帧 `d3_16_base768`，读数 `SCROLL [0,246][1366,434] h=188` + `EDIT [28,332][1338,380]`，与相邻两帧（`[0,162][1366,350]`）**不自洽**。该帧取自 IME 动画过渡期，**不作为判据**，已在表 ①②③④ 中剔除。

### 2.5 极端窗口轮（`wm size 1366x600` + `wm density 160`）

- 生效确认：`Physical size: 1366x768` / **`Override size: 1366x600`** / `Physical density: 160`
- 时间线 **99px**（比 768 高时的 16px **回升**）——因为键盘顶随窗口降到 **261**，composer 也同步上移
- ⚠️ **反直觉但成立**：**更矮的窗口反而给时间线更多像素**（99 > 16）。挤压的真正变量是**「窗口高度 − 键盘高 − 队列条高 − composer 高」的剩余**，不是窗口绝对高度。

### 2.6 收尾

```text
adb -s emulator-5562 shell wm size reset      → Physical size: 1366x768     （无 Override ✔）
adb -s emulator-5562 shell wm density reset   → Physical density: 160       （无 Override ✔）
```

---

## 3. U2 —— resize 的来源：平台 insets vs 本壳 `paneBottomPx`

### 3.1 两侧读数对照

| 读数项 | IME **弹起** | IME **收起** | 是否变化 |
|---|---|---|---|
| `mCurrentFocus` | `Window{885384c u0 …OcActivity}` | 同 | — |
| `mDisplayFrame` | **`Rect(0, 0 - 1366, 768)`** | **`Rect(0, 0 - 1366, 768)`** | ❌ **逐字不变** |
| app 窗口 `Requested` | **`w=1366 h=768`** | **`w=1366 h=768`** | ❌ **逐字不变** |
| app `mAttrs` | `{(0,0)(fillxfill) sim={adjust=resize …}}` | 同 | — |
| `type=ime` InsetsSource | **`frame=[0,342][1366,768] visible=true`** | **`frame=[0,0][0,0] visible=false`** | ✅ **变** |
| `ImeInsetsSourceProvider.mSourceFrame` | **`Rect(0, 342 - 1366, 768)`** | — | ✅ 变 |
| `mInsetsHint` | **`Insets{left=0, top=0, right=0, bottom=426}`** | — | ✅ 变 |
| `mImeShowing` | **`true`** | **`false`** | ✅ 变 |
| `type=navigationBars` | `frame=[0,720][1366,768]` | 同 | ❌ 不变 |

证据文件：`d3_ins_imeON.txt`（46,966 字节，全量 `dumpsys window` + `dumpsys window displays`）、`d3_ins_imeOFF.txt`。

**窗口尺寸 override 时的对照**（`wm size 1366x600`）：

| 读数项 | 1366x768 | 1366x600 override |
|---|---|---|
| `mDisplayFrame` | `Rect(0, 0 - 1366, 768)` | **`Rect(0, 0 - 1366, 600)`** ✅ 真变了 |
| `type=ime frame` | `[0,342][1366,768]` | **`[0,261][1366,600]`** ✅ 变 |

### 3.2 结论

> **U2 结论：这次高度变化既不是「平台 resize 窗口」，也不是单纯的「本壳 `paneBottomPx` 自己算」——是「平台发布 insets 数值 + 本壳自己做布局」的两段式。平台只供数，本壳负责把数变成盒子高度。**
>
> **决定性读数**：键盘弹起时 **`mDisplayFrame` 仍是 `Rect(0, 0 - 1366, 768)`、app 窗口 `Requested` 仍是 `w=1366 h=768`**，**逐字未变**。⇒ **平台根本没有对这个窗口做 resize**。窗口虽然声明了 `adjust=resize`，但因为 `fitSides={}` + `LAYOUT_IN_SCREEN`，**系统没有收窄它**。
>
> 平台真正做的只有一件事：**发布一个 `type=ime` 的 `InsetsSource`（`frame=[0,342][1366,768]`，`mInsetsHint.bottom=426`）**。**时间线从 188px 掉到 16px，是本壳 `SessionPaneLayout` / `paneBottomPx` 消费这个 ime inset 之后的自身布局结果**（`SessionScreen.kt:2316` `paneBoxPx = maxHeight.roundToPx()`）。
>
> ⇒ **`REPORT_SWEEPD2.md` §2 U2 行「resize 来源」的提法应改写**：本轮证据表明**不存在「平台 resize」这一支**。建议 Lead 把该条重新表述为「**insets 供数 vs 本壳布局**」，否则后续任何「等平台修」的预期都不成立。
>
> **唯一让 `mDisplayFrame` 真正变小的操作是 `wm size` override**（768→600），那是**显示帧本身的真实 resize**，与键盘 insets 是两条独立通路。

### 3.3 与 §4.5 / §3.2 代码侧线索的衔接

- §3.2 记的 `paneBoxPx = maxHeight.roundToPx()`（IME 已排除）**方向正确**，本轮读数为它提供了平台侧对照：**`maxHeight` 变小的原因不在平台 resize，而在 ime inset 进到了本壳的布局约束里**。
- 派单给的「app 侧可读调试读数」**本壳没有**（未找到任何暴露 `paneBottomPx` 的 debug 输出/日志），故按派单 fallback 用 `dumpsys window displays` 的 app frame —— 但**更管用的是 `Requested w/h` 与 `mDisplayFrame`**，二者已足以定案。

---

## 4. U14 —— 复验 N1 / N2 / N3

> 口径来源：`5562_sweepB/REPORT_v97b.md` §4。全部在自建会话 `E2ECHK5562xD3U1` 上走，**每一步都 dump 读数**。

### 4.1 N1 —【高危·数据损坏】时间线搜索输入框的内容被 PATCH 成会话标题

**原始口径**：走完搜索并清空输入框后，留在框里的任意文本在**离开会话**时被 PATCH 成新标题并落库。

| 步骤 | 命令 / 读数 | 结果 |
|---|---|---|
| 1 开搜索 | `tap 1138 134` | header 变文本框 `EDIT [114,110][1284,158]`，`Close search` 出现 |
| 2 塞哨兵词 | 清空后输 `Queued` | `SEARCHBOX t=Queued`；**会话标题此刻仍是** `E2ECHK5562xD3U1`（`d3_25_queued_sent`） |
| 3 离开会话 | `tap 64 134`(hamburger) → 关抽屉 → `tap 32 51`(Home) | 抽屉列表：`E2ECHK5562xD3U1`（`d3_26_home.png`）；**真 Home**（`Search sessions` 在 `[515,180][617,196]`）：`E2ECHK5562xD3U1` @ `[523,282][647,298]`（`d3_29_home2`） |
| 4 判定 | 比对 | **标题未被覆盖成 `Queued`** |

> **N1 判定：✅ 不复现（PASS）。** 搜索框里留着 `Queued` 离开会话后，会话标题在**抽屉列表**与**真 Home 列表**两处均**逐字保持** `E2ECHK5562xD3U1`。
>
> ⚠️ **未闭环**：后端 `GET /api/session` **本轮取不到数**（`UnauthorizedError`，需鉴权）。所以**「UI 标题未变」已确证，「落库 title 字段未变」未直连后端对账**。若要彻底销账需带鉴权取一次 `/api/session`。

### 4.2 N2 —【高危】时间线搜索结果冻结在首次查询，后续改词永不重算

**原始口径**：`Search session` 的过滤**只在第一次输入时算一次**，之后增删改查询词结果集都不变。

| 查询 | 期望命中 | 实测命中节点 | 证据 |
|---|---|---|---|
| ① `Queued` | 用户气泡 `Queued second message…` | `Queued second message while streaming for U1` @ `[1316,147][1364,192]` | `d3_22_q_queued` |
| ② 改词 → `insets` | 长回合提示词 | **`Write a long detailed explanation of how Android window insets work, covering at least 30 numbered points.`** @ `[588,166][1300,186]` | `d3_23_q_insets` |
| 搜索框自校验 | — | ②的框内确为 `insets`（`SEARCHBOX t=insets`） | 同上 |

> **N2 判定：✅ 不复现（PASS）。** **改词后结果集换了内容**（从 `Queued` 气泡换成 `insets` 提示词），搜索框内容也自校验为 `insets` ⇒ **过滤确实重算了**。这正是原报告「第二次搜必错」的反例。
>
> 🔶 **未覆盖**：只做了 **1 次改词**（`Queued`→`insets`）；上轮亦提示「三次及以上改词是否始终不重算」需更多轮。本轮会话内只有 2 条用户消息，**构造不出原报告用的 4 词矩阵**（`pathlib`/`the`/`OpenCode`/`Queued`）。

### 4.3 N3 —【高危】header 标题编辑态无取消路径，跨导航不恢复

**原始口径**：进标题/搜索编辑态后，header 的 🔍 与 ? 被移除且不会自己回来；ESC / 清空 / 回 Home 都不恢复。

| 检查点 | 读数 | 证据 |
|---|---|---|
| 进编辑态（设计行为） | 🔍/? 消失，出现 `Close search` | `d3_21_searchopen` |
| **主视图按 ESC 后** | 搜索框**仍在编辑态**、内容仍是 `insets` @ `[114,111][1212,159]`；`Close search` **仍在**；header 按钮仍**无 🔍/?** | **`d3_24_esc`** |
| 跨导航（回 Home）再重进 | 🔍 **回来了** `[1118,110][1158,158]`，? **也回来了** `[1158,110][1198,158]` | **`d3_30_reopen`** |

> **N3 判定：⚠️ 部分复现（部分 PASS）。**
> - **仍复现**：**ESC 不退出编辑态** —— 按 ESC 后过滤未清、输入框仍是编辑态、🔍/? **不回来**（`d3_24_esc`）。「没有取消路径」这一半成立。
> - **已不复现**：**「跨导航不恢复」不再成立** —— 回 Home 再重进会话，🔍 与 ? **两个入口都回来了**（`d3_30_reopen`）。原报告 v97b §4 N3 的「跨 Home 导航、跨关闭/重开 tab 都不恢复」**本轮被推翻**。
> - ⇒ **N3 应缩小为「编辑态下 ESC 无效、缺少显式取消」**，不应再按「跨导航不可恢复」定级。

### 4.4 对 §4.5「N2 与 N4 可能是同一根因」的判断：**部分反对**

> **我的判断：§4.5 的代码级推理仍成立，但「合并成一条根因」这个行动建议本轮缺乏可观测支撑，建议暂不合并。**
>
> **反对「现在就合并」的证据**：
> 1. **N2 本轮根本没有缺陷腿**。§4.5 论证的前提是「N2 = 过滤器只算一次」。本轮实测**搜索确实随改词重算**（`Queued`→`insets` 结果集换了内容）。**一个不复现的症状，没有可以和 N4 合并的第二个观测点** —— 合并后反而会掩盖 N4 需要单独定位。
> 2. 更关键：本轮**恰好在会逼出单例共享冲突的条件下**（同一会话内连续换词）**得到了正确结果**。若 `TimelineSearch` 的单例查询态真被 Home 分区与时间线互相踩，那么「时间线换词」至少应出现一次错算 —— **实测 0 次**。这**削弱**（不能证伪）单例假设作为本轮 N2 症状的解释力。
> 3. **N3 的残留缺陷是另一根因轴**。N3 剩下的问题是「编辑态的**退出/取消**」，属于**编辑态状态机**，与「查询结果缓存」无关。把它和 N2 归到「单例查询态」会把两类不同机制混在一起。
>
> **我保留的部分**：§4.5 指出的 `object TimelineSearch` 是**进程级单例**、`needle` 为 `mutableStateOf`（`TimelineSearch.kt:56`），**从架构上仍是真实风险**，值得单独立项查证；但**它现在是一个待验证的假设，不是一个已定位的根因**。
>
> **建议**：把 §4.5 从「N2/N4 同根因」降级为「**`TimelineSearch` 单例查询态：一个待查的架构风险**」，N2 先标 **不复现**，N4 继续单独按 U13 口径查。

---

## 5. 收尾

| 项 | 终态读数 | 结论 |
|---|---|---|
| `wm size` | `Physical size: 1366x768`（**无 Override**） | ✔ override **已 reset 并复核**（极端窗口轮结束后立即 reset，收尾再复核一次） |
| `wm density` | `Physical density: 160`（**无 Override**） | ✔ 同上 |
| `show_ime_with_hard_keyboard` | **`1`** | ✔ **全程只读未改**，收尾复核仍为 1 |
| 自建会话 | `E2ECHK5562xD3U1` → `⋯` → `Delete` → 确认弹层文案 `This deletes "E2ECHK5562xD3U1"…` → **已删** | ✔ 已删，`d3_33_deleted` 复核 `E2ECHK5562xD3U1` **已消失** |
| 他人会话 | **未点任何其他会话的 Delete**，只读过列表 | ✔ |
| `/sdcard` | 本轮产物 `d3.xml`/`d3p.png` 每次 `pull` 后即 `rm`；收尾 `ls /sdcard \| grep '^d3'` → **NONE** | ✔ 无本轮新增残留（`/sdcard` 上的 `v97b_*`/`tB_*`/`u1..u120.xml` 是**往轮**遗留，非本轮） |
| reverse | `host-19 tcp:4095`、`host-19 tcp:2222` | ✔ 未变 |
| 代码改动 | **无** | ✔ 本轮只读扫测 |
| 设备事件 | **无 reboot**、**无 ANR**、无 dump 桥接连续挂死（仅 1 次单帧 0 字节抖动，按 size>500 判别后重取成功） | ✔ |

---

## 6. 未闭环

| # | 项 | 状态 | 原因 / 下一步 |
|---|---|---|---|
| U1 | 时间线被压到 16px 的**归因** | **部分** | 现象与读数已确证（FAIL）。但「172px 落差里 48px 来自 composer、124px 来自队列条」是我从两帧差值**推算**的，**未做单变量对照**（只变队列条条数 / 只变 composer 行数各一组）。建议下一轮补 A/B：固定 IME，只加第 2、3 条队列，看时间线是否继续掉 |
| U1 | 16px 态的**用户可读性** | **未验** | ③ 号态下时间线内容实质不可见，**未截图留证「不可读」的完整时间线**（`d3_14_state.png` 被权限弹层污染，我用 `Deny` 关掉后未重截） |
| U2 | app 侧 `paneBottomPx` 实际数值 | **未验到** | 本壳**无可读的 insets/布局调试读数或日志**，按派单 fallback 走了 `dumpsys window displays`。要拿到 `paneBottomPx` 的运行期值，需先加一条 debug 输出（属改代码，超出本轮只读范围） |
| U2 | 队列条与时间线抢空间是否**有意的优先级** | **待产品确认** | 读数只说明「队列条优先、时间线让步」，**这是设计取舍还是缺陷需产品定** |
| N1 | 后端 `title` 字段直连对账 | **未验到** | `/api/session` 需鉴权（`UnauthorizedError`）。UI 侧已确证未变；**落库侧未直连** |
| N2 | ≥3 次连续改词 | **未验** | 本轮只做了 1 次改词；会话内容不足以构造 v97b 的 4 词矩阵 |
| N3 | 是否有**显式取消入口**（`Close search` 之外） | **未验** | 本轮只验了「ESC 无效」与「跨导航会恢复」；未穷举是否存在其他取消路径 |
| — | `TimelineSearch` 单例是否真被共享踩 | **假设，未证** | §4.5 的架构风险本轮**未直接反证也未证实**；建议单独立项 |

---

## 7. 一句话总结

**U1 验到并判 FAIL —— 在 1366x768 上「流式 + 2 条队列 + 软键盘」并存时，composer 本身没被键盘遮住（净空 33px），但时间线被从 188px 压到 16px 且被队列条完全覆盖**，极端窗口 `1366x600` 反而回升到 99px（说明变量是「窗口高 − 键盘 − 队列条 − composer」的剩余，不是窗口绝对高度）；**U2 定案 —— 平台压根没有 resize 这个窗口**（键盘弹起/收起两侧 `mDisplayFrame=Rect(0,0-1366,768)` 与 `Requested w=1366 h=768` 逐字不变），平台只发布 `type=ime` InsetsSource（`frame=[0,342][1366,768]`、`mInsetsHint.bottom=426`），**高度变化是本壳自身布局消费该 inset 的结果**（唯一让 `mDisplayFrame` 真变小的是 `wm size` override，属另一条通路）；**U14 三条全部验到：N1 不复现**（搜索框留 `Queued` 离开会话，抽屉与真 Home 列表标题均逐字保持 `E2ECHK5562xD3U1`）**、N2 不复现**（`Queued`→`insets` 改词后结果集换了内容，过滤确实重算）**、N3 部分复现**（ESC 仍不退出编辑态、🔍/? 不回来，但「跨导航不恢复」已被推翻 —— 重进会话两个入口都回来了）；**对 §4.5 给出部分反对**：N2 本轮无缺陷腿、单例共享在「连续换词」这一最该出错的条件下 0 次出错，N3 残留属编辑态状态机另一根因轴，**建议 §4.5 降级为「待查的架构风险」而非已定位根因，暂不与 N4 合并**。全程未改代码、未按 BACK、未动他人会话、wm/density 已 reset 复核、IME 开关未改、自建会话已删、`/sdcard` 无本轮残留。
