# REPORT_IDLE_AND_600BAR — v134 真机：idle 肯定性观测 + 600 档「队列条在屏」形态

- **设备**：emulator-5554（Medium_Desktop）
- **包**：`com.xiufeigo.opencodeshell`（Activity `.OcActivity`）
- **执行日期**：2026-10-04
- **仓库根**：`C:\sensorsdata\main\program\opencode-android-shell`
- **证据目录**：`research/v97_verify/5554_md/v134/`
- **自建内容前缀**：`E2ECHK5554u1v134`

---

## 0. 构建 / 后端 / 归属核对

| 项 | 期望 | 实测 | 判定 |
|---|---|---|---|
| 设备在线 | `emulator-5554` | `emulator-5554 device product:sdk_gpc_x86_64` | ✅ |
| 屏幕 Override | `1366x768` | `Override size: 1366x768`（Physical 3840x2160） | ✅ |
| 密度 Override | `160` | `Override density: 160`（Physical 320） | ✅ |
| **构建 md5** | `60875a0424d8be497218f9602b90905e` | `60875A0424D8BE497218F9602B90905E` | ✅ **逐位一致（32 位）** |
| 主机 `lastUpdateTime` | 记录 | `2026/10/4 5:21:14`（`app/build/outputs/apk/debug/app-debug.apk`，44198253 B） | 记录 |
| 设备端 APK md5 | 与构建同 | `60875a0424d8be497218f9602b90905e` | ✅ 逐位一致 |
| 设备端 `lastUpdateTime` | 记录 | `2026-10-03 21:21:35`（设备 UTC，对应主机 5:21:35） | 记录 |
| 版本 | — | `versionName=1.4.0` / `versionCode=26` | 记录 |
| 后端 v2 端口 | `4095` | `reverse --list` → `host-19 tcp:4095 tcp:4095` | ✅ |
| 认证态 | 已认证或可重填 | **启动即已认证**（composer + `Send` 同屏，无登录页） | ✅ **未动凭据** |

设备端 APK 路径：`/data/app/~~eAaSwW9vQOEYmealKi4RFA==/com.xiufeigo.opencodeshell-0Rg2dnz3BFU5F3VXmU48mQ==/base.apk`

**纪律遵守**：全程未 gradle / 未装机 / 未杀进程（`am force-stop` 亦未使用）；未按 `BACK(4)`；未按 `ESC(111)`；坐标全部运行期从 dump 解析；截图走 `exec-out screencap -p` 落本地；读高度前均先断言 `mInputShown`；dump 走 `exec-out uiautomator dump /dev/tty` 落本地（**不写 `/sdcard`**）。

### 0.1 本轮取证的读数口径加固（重要，防重犯 v130 翻车）

第一版 helper 用 `$script:` 作用域，点源后失效，导致 **24 帧 dump 变成 2 字节空文件**；而空文件让 `DescCount` 恒返回 0，于是产生了一串**假的 `stop=0 working=0`「真 idle」读数**。

这正是 v130 栽的同一类坑（读数口径错误）。处置：

1. 工具链重写为绝对路径 + 常量 serial（`tk.ps1`）；
2. `DumpUI` 加**空帧硬拦截**：节点数 `< 20` 即重试（最多 4 次），仍失败则 `throw`；
3. 上述 24 帧已**全部作废**，不参与任何判定。

⇒ 下文所有帧均为**非空且已校验**的帧（本报告引用的帧节点数 66–84）。

---

## 1. 任务 A：真 idle 的肯定性观测

### 1.1 队列条复现姿势（本轮复验通过）

长回合「count from 1 to 900000…」→ 回合运行中（composer 显 `content-desc="Stop"`）→ IME 开 + composer 有内容 ⇒ chip 行出现 **`Queue`** ⇒ 点它 ⇒ 队列条出现。

⚠️ **两个标签形态与往轮不同，实测确认**（都会导致找不到坐标）：

| 控件 | 属性 | 说明 |
|---|---|---|
| `Queue` chip | **`text="Queue"`** | **不是 `content-desc`**（`[1271,309][1308,324]`） |
| `Send` / `Stop` 主按钮 | **`content-desc`**（`android.view.View`，`text=""`） | **不是 `text`** |
| 删除按钮 | `content-desc="Remove"` | 与往轮一致 |

### 1.2 判 idle 的修正判据 —— **`content-desc="Stop"` 消失**（dump 前后对照）

| 阶段 | 证据帧 | 节点数 | `Stop`(`content-desc`) | `Working` | composer | 队列条 | parked 行首行动作 |
|---|---|---|---|---|---|---|---|
| **前（运行中）** | `e06_pre_stop.xml` | 70 | **1（在场）** `[1308,292][1356,340]` | 1 | `''` 空 | 开，1 行 | **`Steer`** |
| 前（复验 2） | `g06` / `e07_pre_stop_tap.xml` | 70 | **1（在场）** | 1 | `''` 空 | 开，1 行 | **`Steer`** |
| 点 `Stop` 后 | `e08_p01…p11` | 72–73 | **1 仍在**（面板被外部点击关闭，回合未停） | 1 | `''` 空 | 已关 | — |
| 回合终止后 | `f01_after.xml` | 72 | **0（消失）** | **0（消失）** | `''` 空 | 关 | 无行可读 |
| 点 `Stop` 后（复验 2） | `g09_p*` | — | **0（消失）** ⇒ 记 `>>> IDLE (Stop desc GONE)` | 0 | `''` 空 | 关 | 无行可读 |

**判定：idle 以 `content-desc="Stop"` 消失确证 ✅。**
且与 v133 §8 步骤 3 的三条判据**同时满足**：页签 `Working` 消失 **且** 主按钮 `content-desc` 不再是 `Stop` **且** composer `text=''`。

> 补充一条口径要点（v133 pin `QueueRowActionsPinTest.theComposerButtonIsTheRunStateOracleTheLabelMustAgreeWith`）：`running=true` **且 composer 非空**时按钮**本就不是 Stop**。本轮实测到该形态（`c04_followup`：`stop=0 work=1 composer='E2ECHK5554u1v134 parkbeta'`）——所以**「Stop 不在场」不能单独证 idle**，必须与 `Working` + composer 空合看。本报告即按此三条合判。

### 1.3 idle 后首行动作标签 = `Send`？—— **未观测到（不可达），不判 FAIL**

**读数原文（idle 帧 `f01_after.xml`，72 节点，已校验）**：
```
stop=0  work=0  composer=''
queue panel: Steer=0  Send=0  Remove=0
（帧内唯一 E2ECHK 命中是 composer 自动补全建议列表 [1316,197][1320,238]，
  非队列条 —— 3px 行高、x=1316..1320 窄按钮，可与队列行明确区分）
```

**首行标签：无行可读。** 既未读到 `Send`，也未读到 `Steer`。

**前置三项已逐一排除（按任务要求，判「不可达」而非 FAIL）**：

| 前置 | 是否成立 | 证据 |
|---|---|---|
| 真 idle？ | ✅ 成立 | `Stop` 消失 + `Working` 消失 + composer 空（§1.2） |
| composer 真为空？ | ✅ 成立 | `EditText text=''`（`f01_after.xml`） |
| 该行是否被裁剪？ | ✅ **不是裁剪** | 队列条**整体不存在**（无容器、无 `Steer`、无 `Remove`），非「行在但被裁」 |
| 是否「读数口径」问题？ | ✅ **已排除** | 空帧已硬拦截；② 属性名已逐控件实测确认（§1.1）；`Stop` 判据用 `content-desc`；composer 空独立复核 |

**不可达的根因（源码 + 设备双证）**：

1. **点 `Stop` 必然落在队列条之外** ⇒ 面板被「外部点击」关闭。设备侧：点 `Stop` 后 `e08_p01` 起 `nRows=0`（面板与行同时消失），而回合仍在跑（`stop=1 work=1` 持续 11 帧）。
2. **idle 时无法再打开队列条**：chip 的显示条件是
   > `ComposerDelivery.kt:56` — `if (!running || !hasContent) return null`
   
   即 **`running=false` ⇒ chip 恒隐藏**。设备侧复证：idle 帧 `QueueChip=0`。
3. ⇒ 「打开面板」与「真 idle」在本形态下**互斥**，parked 首行在 idle 时刻**结构性不可见**。

**结论**：这是**可测性/形态限制**，不是「标签错」的证据，**不判 FAIL**（v130 那种「前提不成立却判缺陷」的翻车不重演）。但它同时暴露一个**需要产品裁定的问题**：

> 上游语义明确要求 parked 行在 stopped 态留存并翻成 `Send`（`queue-panel.tsx:89-92` 注释 + `:175` 三元假分支）。而本壳实测：**`Stop` 会连带关闭队列条并使 parked 行不可见**，`Stop` 期间回合并未被中断（`stop=1 work=1` 持续），行最终随回合结束被消耗。
> ⇒ 「`Stop` 后 parked 队列是否应当留存」需立项确认（见 §4 未闭环 1）。

### 1.4 反向复核（`Steer` 侧）—— 已确证

| 状态 | 证据帧 | 首行动作 |
|---|---|---|
| `running=true` + 1 parked 行 | `e06_pre_stop.xml` | **`Steer`** `[1263,183][1311,231]` `Button clickable=true` |
| 同上（复验 2，`parkdelta`） | `g05_panelopen` | **`Steer`** `[1263,183][1311,231]` |
| 同上（复验 3，`parkgamma`） | `e05_queueopen` | **`Steer`** `[1263,183][1311,231]` |

⇒ `running=true ⇒ Steer`（上游 `queue-panel.tsx:175` 真分支）**已确证**；`running=false ⇒ Send` 侧**本形态不可达**（§1.3）。

---

## 2. 任务 B：600 档「队列条在屏」形态的 ① 读数

**统一口径**（复刻 `research/v97_verify/5554_md/geom.ps1`，并与 `evidence_600.txt` / `evidence_768.txt` 逐位对齐）：

- `unionBottom` = composer 子节点底边并集（自编辑器上沿起，**排除 `Open terminal`**）
- `paneBottom(i)` = dump 侧宿主盒底边；`paneBottom(ii)` = 生产口径 `paneBottom(i) - 48`
- `①(i)` = `unionBottom - paneBottom(i)`；`①(ii)` 按 `CRITERION_PARITY` 换算（`unionBottom - 48 + 12`）
- `②` = `unionPx <= band(48)`；`timeline box = paneH - 48`；`readable = paneH - 96 / paneH - 48`
- **读高度前已断言 `mInputShown`**（两形态均 `mInputShown=True`）

### 2.1 600 档两形态对照（`1366x600` / `density 160`）

| 指标 | **A. 条关闭**（本轮实测） | **B. 条在屏**（本轮实测） | v128 600 基线（条关闭） |
|---|---|---|---|
| editor | `[24,201][399,249]` h=48 | `[24,214][405,262]` h=48 | `[24,201][399,249]` h=48 |
| 宿主盒 hostBox | `[0,162][1366,261]` h=99 | `[0,162][1366,261]` h=99 | `[0,162][1366,261]` h=99 |
| 并集（排除 `Open terminal`） | `[24,200][1364,249]` | `[16,214][1364,263]` | `[24,201][1364,249]` |
| `unionPx` | 49 | 49 | 48 |
| `unionBottom` | **249** | **263** | **249** |
| `band` | 48 | 48 | 48 |
| `paneBottom(i)` | **261** | **261** | **261** |
| `paneBottom(ii)` | **213** | **213** | **213** |
| **① 差值 (i)** | **−12** | **+2** | **−12** |
| ① 差值 (ii)（换算后） | 0（`213 vs 213`） | **+14**（`227 vs 213`） | 0 |
| `② unionPx<=band` | `49<=48` = **False** | `49<=48` = **False** | `48<=48` = **True** |
| `mInputShown` | **True** | **True** | True |
| `readable` | 51 | 51 | 51 |
| 时间线盒 | 51 | 51 | 51 |
| 屏上队列动作 | 无 | **`Steer` `[1263,177][1311,225]` h=48 w=48**<br>**`Remove` `[1302,176][1350,224]` h=48 w=48** | 无 |

证据帧：A=`h600_BARON2.xml`，B=`k600_MEASURE_BARON.xml`；截图 `h600_05_panelon.png` / `k600_MEASURE_BARON.png`。

### 2.2 差异来源（据实说明，**不判「回退」**）

**差异 = `unionBottom` 249 → 263，即 composer 被下推 14px；`①(i)` 随之 −12 → +2（同样 +14）。**

- 形态 A 与 v128 基线**逐位吻合**（`unionBottom=249`、`paneBottom=261`、`①=−12`、`paneH=99`、`readable=51`）⇒ 关闭形态无漂移。
- 形态 B 的 14px 下推**发生在宿主盒之下、composer 自身之内**：宿主盒 `[0,162][1366,261]` **未移动**（两形态完全相同），只有 editor 从 `[24,201]` 移到 `[24,214]`（+13px）及其底边 249→263。
- 这与题面预期（composer 被下推 ~14px）及 `ComposerQueuePanel.kt:70-95` 记录的行为**方向与量级一致** ⇒ **判为设计行为（条在屏时占位），非几何回退**。
- ② 在两形态均为 False，与形态无关：`unionPx=49` 而基线 `48`。**这 1px 已定位为口径敏感项**，非布局漂移：
  - 形态 A：composer 内残留一枚**已折叠的自动补全建议节点** `[1316,200][1320,201]`（h=1），把 `y1` 从 201 拉到 200；
  - 形态 B：`EditText [214,262]` 与按钮组 `[215,263]` 存在 1px 纵向错位。
  - 同一编辑器在 768 档 `unionPx=48`、②=True ⇒ 反证 768 未漂移。

### 2.3 768 档复核：**未回退**（条关闭形态）

| 指标 | v128 768 基线 | 本轮实测 | 判定 |
|---|---|---|---|
| editor | `[28,244][1338,292]` | `[28,244][1338,292]` | ✅ 逐位一致 |
| 宿主盒 | `[0,162][1366,350]` h=188 | `[0,162][1366,350]` h=188 | ✅ |
| `paneBottom(i)` / `(ii)` | 350 / 302 | **350 / 302** | ✅ |
| `unionBottom` | 340 | **340** | ✅ |
| **① 差值 (i)** | **−10** | **−10** | ✅ |
| **时间线盒** | **140** | **140** | ✅ **不漂** |
| **`readable`** | **92** | **92** | ✅ **不漂** |
| `unionPx` | 96 | 96 | ✅ |
| `mInputShown` | True | True | ✅ |

证据帧 `m768_MEASURE.xml`（78 节点）。

> 口径备注：本轮 768 回合**运行中**，时间线有一枚 `Copy response` `[1066,330][1114,350]` 正好落进 `geom.ps1` 的 `y2 <= et.y2+60` 窗口，会把 `unionBottom` 虚抬到 350。上表按**基线同款 composer 子节点口径**（8 个节点，`[10,244][1356,340]`）取值 ⇒ 与基线逐位一致。`timeline box / readable` 为宿主盒派生值，不受此影响。

**⇒ 768 档 `140 / 92` 均未漂。**

---

## 3. 收尾

| 项 | 动作 | 结果 |
|---|---|---|
| 屏幕/密度 | `wm size 1366x768` + `wm density 160`（显式重设） | ✅ 复核 `Override size: 1366x768` / `Override density: 160` |
| 回合 | 回合自然终止；复核 `stop=0 work=0` | ✅ 真 idle |
| composer | 已清空（`text=''`） | ✅ 无自建草稿残留 |
| 队列条 | `Steer=0 Send=0 Remove=0` | ✅ 已关闭、无行 |
| 自建时间线消息 | 1 条可见：`parkeps600b` `[16,162][1312,182]`；**长按 `(664,172)` 1400ms（≥1200ms）** | ❌ **未唤起删除弹层** |
| 弹层归属核验 | `dumpsys window | grep mCurrentFocus` | `Window{91dbf u0 com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.OcActivity}` / `mFocusedApp=…/.OcActivity t141` —— **无外来弹层，归属可确认** |
| `/sdcard` | `ls -1 /sdcard/` | ✅ **无新增残留**（58 项均为往轮遗留，命名无一命中 `u1v134` / `v134` / 本轮帧名） |

**长按详情**：长按后帧 `r01_afterlong.xml`（84 节点）仅出现**常驻**的 `Copy message` `[1276,412][1316,460]`、`Copy response` `[4,559][52,607]` 等内联按钮，**无 `Delete` / `Remove` / `Edit` 任何菜单项** ⇒ 与 v130 §4 同一形态（长按未唤起删除弹层）。**未绕过任何权限网关**，删除记为未闭环。

**为什么 `/sdcard` 本轮零新增**：本轮所有取证改用 `exec-out uiautomator dump /dev/tty` 与 `exec-out screencap -p > <本地文件>`，**全程不向 `/sdcard` 写任何文件**（往轮的 44 项残留即源于 `dump /sdcard/xxx.xml` 的旧做法）。清单留存 `_sdcard_list.txt`。

---

## 4. 未闭环

1. **parked 首行 `Send` 的肯定性观测不可达**（任务 A 核心）。根因双证：① `Stop` 落点必在队列条外 ⇒ 面板被外部点击关闭；② `ComposerDelivery.kt:56` `!running ⇒ chip 隐藏` ⇒ idle 时无法重开面板。二者使「面板开」与「真 idle」互斥。
   **需产品裁定**：上游 `queue-panel.tsx:89-92`/`:175` 要求 stopped 态 parked 行留存并翻 `Send`；本壳 `Stop` 未中断回合（`stop=1 work=1` 持续 11 帧）且行随回合结束被消耗。**建议立项确认 `Stop` 语义（是否应中断 + 保留 parked 队列）**，并给出可达的取证入口后复测。
2. **自建时间线消息无法删除**：长按不唤起删除弹层（同 v130）。需确认删除入口是否存在于其他形态。
3. **`/sdcard` 往轮 58 项残留**非本轮自建，按「只动自建」未触碰。
4. **② `unionPx` 1px 口径敏感项**（49 vs 48）：源于已折叠自动补全节点 / EditText 与按钮 1px 错位，768 档为 48。建议在口径里显式排除 `h<=1` 的补全节点以消除噪声。
5. **编辑贷款 `queueEditOpen`、拖拽重排「不支持」**为往轮遗留项，不在本轮范围。

---

## 5. 判定汇总

| 任务 | 判定 | 一句话 |
|---|---|---|
| **A. idle 肯定性观测** | **idle 判据 ✅ PASS / 首行 `Send` ⛔ 不可达（不判 FAIL）** | 以 `content-desc="Stop"` 消失（+`Working` 消失 + composer 空）确证真 idle，三条帧均已校验非空；idle 时队列面板结构性不可见（`Stop` 关面板 + `!running` 隐藏 chip），首行无从读起，已排除「裁剪」与「读数口径」两类误判 |
| **B. 600 档条在屏 ①** | **✅ 已取数，差异判为设计行为** | 条在屏 `unionBottom=263` / `①=+2`；条关闭 `unionBottom=249` / `①=−12`（与 v128 逐位吻合）；差 **+14px**，宿主盒 `[0,162][1366,261]` 未移动，与 `ComposerQueuePanel.kt:70-95` 记录的设计行为一致 |
| **B. 768 不回退** | **✅ 不漂** | 时间线盒 **140** / `readable` **92**，editor、宿主盒、`paneBottom`、`unionBottom=340`、`①=−10` 全部逐位一致 |
| **C. 收尾** | **部分完成** | `wm` 已显式重设 1366x768/160 ✅；`/sdcard` **零新增** ✅；自建消息**删不掉**（长按无删除弹层，未绕过网关）❌ |

---

## 6. 证据文件索引（`research/v97_verify/5554_md/v134/`）

| 文件 | 用途 |
|---|---|
| `tk.ps1` | 工具链（绝对 serial + **空帧硬拦截**） |
| `geom.ps1` / `geom2.ps1` / `geom3.ps1` / `geom_orig.ps1` | 几何测量（逐轮修正口径，全部留档） |
| `parse.ps1` / `a.ps1` / `helper.ps1` | 前序轮工具（含被弃用的 `$script:` 版，即 §0.1 事故源） |
| `taskA.ps1` / `taskA2.ps1` / `run*.ps1` | 任务 A 各次链路脚本 |
| `taskB1.ps1` / `taskB2.ps1` | 600 档切档 + 队列条在屏 |
| `taskC1.ps1`…`taskC4.ps1` | 收尾（wm 重设 / 停回合 / 清 composer / 长按 / 归属核验） |
| `e06_pre_stop.xml` | **任务 A 前帧**：`Stop` 在场 + 1 parked 行 + `Steer` |
| `e07_pre_stop_tap.xml` | 前帧复验（`parkgamma`） |
| `e05_queueopen.xml` / `g05_panelopen` | 前帧复验（`parkbeta` / `parkdelta`） |
| `e08_p01…p11.xml` | 点 `Stop` 后：`Stop` 仍在场、面板已关（11 帧） |
| `f01_after.xml` | **idle 帧**：`Stop` 消失 + `Working` 消失 + composer 空（72 节点） |
| `g09_p*.xml` | 复验 idle 帧（`>>> IDLE (Stop desc GONE)`） |
| `c07_poll02…24.xml` | **作废**（2 字节空帧，§0.1） |
| `h600_BARON2.xml` | 600 条**关闭**形态测量帧 |
| `k600_MEASURE_BARON.xml` / `h600_05_panelon.png` / `k600_MEASURE_BARON.png` | 600 条**在屏**形态测量帧 + 截图 |
| `m768_MEASURE.xml` | 768 条关闭形态测量帧 |
| `d01_state.png` / `n03_after_stop.png` | 状态截图 |
| `r01_afterlong.xml` / `r01_afterlong.png` | 长按后帧（仅常驻 Copy 按钮，无删除菜单） |
| `_A_taskA.log` / `_A2.log` / `_B.log` | 过程日志 |
| `_sdcard_list.txt` / `_p_windows.txt` / `_r_windows.txt` | `/sdcard` 清单 / 窗口归属取证 |
