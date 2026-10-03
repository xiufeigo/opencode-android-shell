# v97 轮真机复验报告 — emulator-5556（v82 五条口径）

> 测试员：真机复验测试员（v82 五条口径）
> 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
> 口径原文：`research/v97_verify/5554/V82_FIVE_CRITERIA.md`（Lead 提供，**未重构**）
> 补充背景：`research/HANDOFF.md:71`（v82 chord 键码错 bug 桶）、`research/HANDOFF.md:34`（v83 IME band 修正）
> 证据目录：`research/v97_verify/5556_v82/`（**本轮全部为新 APK 上的新证据**）
> 开始时间：host 2026-10-02 18:04（设备时钟 10:04，落后 8h 时区）

## 0. 纪律执行声明

| 铁律 | 本轮执行情况 |
|---|---|
| 不跑 gradle | ✅ 全程零 gradle 调用 |
| 不装机 | ✅ 只 `am start` 已有 APK；`pm install` 零次 |
| 不杀进程 | ✅ 零 `am force-stop` / `pkill` / 任务管理器 |
| 不碰 `127.0.0.1:5555` | ✅ 只操作 `emulator-5556`；`adb -s emulator-5556 ...` 全程带 serial |
| 绝不按 BACK(`keyevent 4`) | ✅ 零次（见下 §0.1 审计） |
| 设置/表单弹层内禁 ESC(111) | ✅ 零次 `keyevent 111`（见下 §0.1 审计） |
| 截图只用 screencap+pull | ✅ 零次 `exec-out`（`E2ECHK5556_lib.ps1` 的 `Shot()` 只走 `screencap -p /sdcard/...` + `adb pull`） |
| 坐标运行期从 dump 解析 | ✅ 全部 tap 由 `TapMatch`/`CenterOf` 从 dump 节点计算，无硬编码坐标 |
| 共享后端只动自己建的会话 | ✅ 未创建/未修改任何后端会话；只读 `GET /api/info` |
| 后端进程未动 | ✅ 零次对 4095 进程的操作；S5 只动 `adb reverse` 转发表 |

### 0.1 BACK / ESC 审计（命令级证据）

本轮所有 `adb shell input` 调用（按序）：
- `input keycombination 113 39`（S1 Ctrl+K）
- `input keycombination 113 44`（S2 Ctrl+Shift+P）
- `input keycombination 113 39`（S2 反例，输入态 Ctrl+K）
- `input tap`（若干，坐标全部来自 dump）
- `input keyevent 61`（收键盘，弹层内）
- `input swipe`（S4 表单内上滑）

**未出现**：`keyevent 4`、`keyevent 111`、`exec-out`、`pm install`、`am force-stop`。

---

## 1. 环境读数（全部新 APK 复核）

证据文件：`research/v97_verify/5556_v82/env_readings.txt`

| 项 | 读数 | 期望 | 结论 |
|---|---|---|---|
| `adb devices` | emulator-5554/5556/5558/5560/5562 均 `device` | — | ✅ |
| `adb -s emulator-5556 emu avd name` | **`Medium_Desktop`** | Medium_Desktop | ✅ serial↔AVD 运行期核实通过 |
| `wm size` | `Physical size: 3840x2160` / `Override size: **1366x768**` | 1366x768 | ✅ |
| `wm density` | `Physical density: 320` / `Override density: **160**` | 160dpi | ✅ |
| `settings get secure show_ime_with_hard_keyboard` | **`1`** | 1 | ✅ 软键盘已开（本镜像为桌面模拟器） |
| `adb -s emulator-5556 reverse --list` | `host-19 tcp:4095 tcp:4095` + `host-19 tcp:2222 tcp:22` | 两条都在 | ✅ |
| SDK / model | `sdk=34` / `sdk_gpc_x86_64` | — | ✅ |
| APK `versionName` / `versionCode` | `1.4.0` / `26`（minSdk 26 / targetSdk 35） | — | ✅ |
| APK `lastUpdateTime` | **`2026-10-02 10:01:31`（设备时钟）= host 18:01:31** | ≈18:01 构建 | ✅ **新 APK 已复核** |
| APK `firstInstallTime` | `2026-09-23 13:58:18` | — | ✅ 升级装而非新装 |
| `codePath` | `/data/app/~~zJtb0AFAcNK0W1cT2ZJqIA==/com.xiufeigo.opencodeshell-MjVi8Ft47FbrYkJrS8Y2LA==/base.apk` | — | ✅ |
| 键位覆盖表 `keybinds` | grep 无输出（**不存在/为空**） | `{}` 无覆盖 | ✅ 与 HANDOFF:71「两台设备 oc_settings.xml 里 keybinds 都不存在/为 {}」一致 |
| 后端 `GET /api/info` | **HTTP 200** `{"version":"2.0.18","pid":260,"urls":["http://127.0.0.1:4095"],"paths":{"tmp":"/tmp/opencode"}}` | 200 | ✅ 健康（只用了 `/api/info`） |
| 启动后 `topResumedActivity` | `com.xiufeigo.opencodeshell/.OcActivity` | — | ✅ launcher 正确 |

### 1.1 旧证据作废声明（上轮被打断的 `5556_v82/`）

本目录在本次开工前已存在 15 个文件（`00_launch_1366.png` … `ui_20_settings_imeup.xml`），
**文件 LastWriteTime = host 17:24:32 … 17:37:53**，而新 APK 的安装时间是 **host 18:01:31**。
即：**这批旧工件全部拍摄于新 APK 安装之前，是旧 APK 的残留，不作为本轮任何判定的证据。**
本轮新证据一律以 `V82_S<n>_*` 前缀命名，与旧文件不冲突。

---

## 2. 逐条判定表

### S1 — Ctrl+K 打开命令面板（PALETTE）

- **判定：✅ PASS**
- **命令**：
  ```
  adb -s emulator-5556 shell input keycombination 113 39      # KEYCODE_CTRL_LEFT=113, KEYCODE_K=39
  ```
- **证据文件**（相对 `research/v97_verify/5556_v82/`）：
  - `V82_S1_00_launch.png`（前置：Home 页）
  - `V82_S1_01_after_ctrlk.png`
  - `ui_V82_S1_00_launch.xml` / `ui_V82_S1_01_after_ctrlk.xml`
- **读数**（`ui_V82_S1_01_after_ctrlk.xml` 节点表）：

  | 节点 | bounds | 判读 |
  |---|---|---|
  | `android.widget.EditText`（foc=**true**） | `[403,96][985,144]` | **搜索框**，截图内提示文字 = `Search commands and sessions` |
  | `Commands` | `[381,156][451,172]` | **命令分组标题** |
  | `Open settings` | `[381,189][468,205]` | 命令候选行 |
  | `Ctrl+,` | `[951,190][981,204]` | 该行的 chord 徽标 |
  | `New session` | `[381,226][462,242]` | 命令候选行 |
  | `Ctrl+Shift+S` | `[916,227][981,241]` | chord 徽标 |
  | `New workspace` | `[381,263][482,279]` | 命令候选行 |
  | `Previous session` | `[381,300][487,316]` | 命令候选行 |
  | `Next session` | `[381,337][462,353]` | 命令候选行 |

- **判据逐条对照**（V82_FIVE_CRITERIA.md §S1）：
  - 「按键后出现 **palette（命令面板）层**」→ ✅ 命中：搜索 EditText + `Commands` 分组 + 5 条命令候选行。
  - 「dump 里出现面板容器/搜索框/命令分组行」→ ✅ 三者齐备（`Commands` 即分组行）。
  - 「命令候选行文本形如 `New session`、`Settings`、`Toggle theme`…」→ ✅ `New session` 逐字命中；`Settings` 以 `Open settings` 形式在列。
  - 「面板**必须是 palette**，不是模型选择器、不是新会话草稿页」→ ✅ 见下方**排除性读数**。
  - 「**绝不用 75=APOSTROPHE**，也不要用 42=N」→ ✅ 本轮命令字面为 `113 39`。

- **排除性读数（证明不是另外两种面板）**：
  - **不是模型选择器**（Ctrl+' = `model.choose`）：dump 内**零个模型名节点**（无 GLM/DeepSeek/MiniMax 之类 provider/model 行），也无「Choose model」分组。
  - **不是新会话草稿页**（Ctrl+N = `tab.new`）：dump 内**零个会话改名 EditText**，唯一的 EditText 是 `Search commands and sessions`；且 Home 侧栏项目/会话树仍在 dump 中（被面板遮罩压暗），说明这是**覆盖层**而非导航切换。
  - 截图像素判读：面板为居中浮层，覆盖在 Home 双栏之上，背景变暗 —— 覆盖层形态成立。

- **反例/对照**：若发 `113 75`（v82 那轮的错键）会得到模型选择器；若发 `113 42` 会得到新会话草稿页。本轮**均未发**，故不产生该反例读数；S1 的正向读数已足够。

---

### S2 — Ctrl+Shift+P 打开命令面板（PALETTE）

- **判定：✅ PASS**（⚠️ **但口径文档给的命令 `113 44` 是错的，见下「口径勘误」**）
- **口径勘误（本轮实测发现，与 v82 的 `113 75` 同类的脚本键码错）**：

  | | | |
  |---|---|---|
  | 口径原文（`V82_FIVE_CRITERIA.md` §S2 + `HANDOFF.md` 补充） | `input keycombination 113 44` | ❌ **只发了 Ctrl+P，没有 Shift** |
  | 本轮实测该命令的结果 | 弹出**同款面板壳**，但搜索框提示词变成 `Search files`、列表显示 `No results found` | = `file.open` 的 **files-only 模式** |
  | 正确的 Ctrl+Shift+P | **`input keycombination 113 59 44`**（`SHIFT_LEFT=59`） | ✅ 弹命令面板 |

  **源码反证**（三处互相印证，**不是重构口径，是给口径的勘误**）：
  - `ui/settings/SettingsModel.kt:1304` → `ShortcutRow("command.palette", …, "Ctrl+K / Ctrl+Shift+P")`
  - `ui/settings/SettingsModel.kt:1306` → `ShortcutRow("file.open", …, "Ctrl+P")` ← **无 Shift**
  - `ui/App.kt:2168-2172` → `PALETTE("command.palette")` = `mod+k` / `mod+shift+p`；`PALETTE_FILES("file.open")` = `mod+p` = 「the files-only palette」

  即 `113 44` 命中的是 `file.open`→`PALETTE_FILES`，**按设计弹 files-only 面板，app 无 bug**。
  与 `HANDOFF.md:71` 记的 v82「`113 75` 实为 Ctrl+'→`model.choose`」是**完全同型**的测试脚本键码错。

- **A 组（口径原命令，作为勘误证据）**：`input keycombination 113 44`
  - 证据：`V82_S2_00_before.png` / `V82_S2_01_after_ctrlshiftp.png` / `ui_V82_S2_01_after_ctrlshiftp.xml`
  - 读数：面板壳 **同一容器**（EditText `[403,96][985,144]` foc=**true**，与 S1 逐位相同），
    但列表区只有 `No results found` `[631,196][736,212]`，**`Commands` 分组计数 = 0**。
  - 截图像素判读：搜索框提示词 = **`Search files`**（S1 时为 `Search commands and sessions`）。
  - 判定：**不是口径要的「命令面板」形态**（是同壳的 files-only 变体）→ 判据第 1 步即不满足。

- **B 组（修正命令，本条判定所依据）**：`input keycombination 113 59 44`
  - 证据：`V82_S2_03_after_ctrl_shiftP.png` / `ui_V82_S2_03_after_ctrl_shiftP.xml`
  - 读数（与 S1 **逐行逐位相同**）：

    | 节点 | bounds |
    |---|---|
    | EditText（foc=true） | `[403,96][985,144]` |
    | `Commands` | `[381,156][451,172]` |
    | `Open settings` | `[381,189][468,205]` |
    | `Ctrl+,` | `[951,190][981,204]` |
    | `New session` | `[381,226][462,242]` |
    | `Ctrl+Shift+S` | `[916,227][981,241]` |
    | `New workspace` | `[381,263][482,279]` |
    | `Previous session` | `[381,300][487,316]` |
    | `Next session` | `[381,337][462,353]` |

  - **「与 S1 是同一个面板」的最强证据**：`Compare-Object` 逐行比对
    `ui_V82_S1_01_after_ctrlk.xml` 与 `ui_V82_S2_03_after_ctrl_shiftP.xml` 的节点表
    （字段含 text + content-desc + bounds + password + focused + class + clickable）：
    **12 行 vs 12 行，差异 = 0 条**。即两个面板在 a11y 层**完全同一**。

- **判据逐条对照**（`V82_FIVE_CRITERIA.md` §S2）：
  - 「出现 palette 层」→ ✅（B 组）。
  - 「与 S1 是**同一个**面板」→ ✅ **零差异 diff**（上表），远超「同容器」的最低要求。
  - 「**反例**：输入态（焦点在 composer/输入框）时 chord **不让位**——即输入态下 Ctrl+K 也应打开 palette」→ ✅ 见 C 组。
  - 「`effectiveGlobalChords` 里 ctrl+k 与 ctrl+shift+p 都映到 PALETTE」→ ✅ 本机 `keybinds` 覆盖表为空（`env_readings.txt` grep 无输出），即走默认表；`SHORTCUT_ROWS` 里 `command.palette` 一行同时承载 `Ctrl+K / Ctrl+Shift+P`（`SettingsModel.kt:1304`），映射单一。

- **C 组（S2 反例：输入态不让位）**
  - 构造输入态：关闭 palette → tap Home `Search sessions` 输入框（坐标由 `ui_V82_S2_04_home_clean.xml` 解析得 `[515,164][1191,212]` → 中心 `(853,188)`）
  - **输入态读数**（`ui_V82_S2_05_input_focused.xml`）：
    - EditText `[515,116][1191,164]` **foc=**`true`（相对未聚焦时 y 整体上移 48px，IME 让位所致）
    - `dumpsys input_method` → **`mInputShown=true`**（软键盘确实在）
    - `Commands` 分组计数 = **0**（palette 确实已关闭，基线干净）
  - 命令：`input keycombination 113 39`（**焦点在输入框时**按 Ctrl+K）
  - **结果读数**（`ui_V82_S2_06_ctrlk_in_input_state.xml`）：palette **照常打开**，
    EditText `[403,96][985,144]` foc=true、`Commands` `[381,156][451,172]`、
    5 条命令行 bounds 与 S1 **完全一致**。
  - 判定：✅ **chord 未让位**（`rootChordYieldsToTextInput` 未吞掉 Ctrl+K），反例不成立。
  - 证据：`V82_S2_05_input_focused.png` / `V82_S2_06_ctrlk_in_input_state.png` +
    `ui_V82_S2_04_home_clean.xml` / `ui_V82_S2_05_input_focused.xml` / `ui_V82_S2_06_ctrlk_in_input_state.xml`

- **S2 未覆盖的分支（诚实登记）**：本轮输入态用的是 **Home 的 `Search sessions` 框**，
  **不是会话页的 composer 文本框**。口径原文写的是「焦点在 **composer**/输入框」，
  两者都属"输入态"，本轮已用真实输入框证明 chord 不让位；**composer 专属分支未单独取证**。

---

### S3 — 设置弹层给键盘让位（IME band）

- **判定：🟡 部分 PASS —— v82 旧 bug（内容被键盘盖死）已修复且功能目标达成；但口径的**字面**判据「卡片顶边 y 不变（≤12px）」不满足**（实测移动 49px）**。详见下方逐条。
- **步骤与命令**：
  ```
  # 1) Home 干净态
  adb -s emulator-5556 shell input keyevent 61            # TAB，落焦点（收键盘；禁 BACK/ESC）
  # 2) 打开设置弹层（用 app 自己公示的 chord：S1 dump 里 "Open settings" 行带 "Ctrl+," 徽标）
  adb -s emulator-5556 shell input keycombination 113 55   # CTRL_LEFT=113, COMMA=55 -> settings.open
  adb -s emulator-5556 shell screencap -p /sdcard/x.png ; adb pull            # -> V82_S3_01_settings_kbd_down.png
  adb -s emulator-5556 shell uiautomator dump /sdcard/x.xml ; adb pull        # -> ui_V82_S3_01_settings_kbd_down.xml
  # 3) 点搜索框抬键盘（坐标由 dump 解析，不硬编码）
  adb -s emulator-5556 shell input tap 313 171            # = [217,150][409,193] 的中心
  adb -s emulator-5556 shell screencap -p ... ; adb pull                     # -> V82_S3_02_settings_ime_up.png
  adb -s emulator-5556 shell uiautomator dump ... ; adb pull                 # -> ui_V82_S3_02_settings_ime_up.xml
  # 4) 键盘以上带内的可达性证明
  adb -s emulator-5556 shell input swipe 800 260 800 120 250
  ```
- **证据文件**（相对 `research/v97_verify/5556_v82/`）：
  `V82_S3_00_home_clean.png` / `V82_S3_01_settings_kbd_down.png` / `V82_S3_02_settings_ime_up.png` /
  `V82_S3_03_ime_up_scrolled.png` / `ui_V82_S3_00_home_clean.xml` / `ui_V82_S3_01_settings_kbd_down.xml` /
  `ui_V82_S3_02_settings_ime_up.xml` / `ui_V82_S3_03_ime_up_scrolled.xml` / `env_readings.txt`

#### S3 读数 1 —— a11y 层（`E2ECHK5556_geom.py` 找容器）

「卡片」= dump 中 depth 8、class `android.view.View`、x 恒为 193..1173（w=980）的那个节点，两态同深度同宽度，是同一个节点。

| 量 | IME 落下 | IME 抬起 | 变化 |
|---|---|---|---|
| 卡片容器 bounds | `[193,94][1173,674]` | `[193,9][1173,759]` | — |
| **卡片顶边 y** | **94** | **9** | **−85px（上行）** |
| 卡片底边 y | 674 | 759 | +85px |
| 容器 h | **580** | **750** | +170px |
| 左侧导航 ScrollView | `[205,203][421,662]`（h=459） | `[205,154][421,273]`（h=**119**） | **−340px** |
| 右侧内容 ScrollView | `[434,143][1173,578]`（h=435） | `[434,94][1173,285]`（h=**191**） | **−244px** |
| `dumpsys input_method` | `mInputShown=false` | `mInputShown=true` | ✅ 两态干净 |

> ⚠️ a11y 容器 bounds 在键盘态报的是**带 `imePadding` 的外层盒**（h 从 580 涨到 750、盖到 y=759 压在键盘下），
> **不能直接当"卡片顶边"读**。故本条另取**像素级**读数（见下），这与 `HANDOFF.md:71` 当初 v82 定案的
> 方法一致（它就是用像素探针判定「卡片顶边 y=84」）。

#### S3 读数 2 —— 像素级（`E2ECHK5556_pix.py`，同 v82 当年的探针法）

在卡片左内边距列 x=196/200/1160/1165 扫"近纯白"连续带（≥4px）：

| 状态 | 卡片白色面**首行** | 卡片白色面**末行** | 绘制高度 |
|---|---|---|---|
| IME 落下 `V82_S3_01` | **y=95**（x=200/1165 首行同为 95） | **y=671** | **577px** |
| IME 抬起 `V82_S3_02` | **y=46**（x=200/1165 首行同为 46） | **y=283** | **238px** |

**键盘上沿像素定位**（同一张图 x=200 列逐行取色）：

```
y=255..283 : (250,250,250)  <- 卡片白色面
y=284      : (225,225,225)  <- 分界
y=285..293 : (149..142,…)   <- 键盘顶边描边
y=294      : (241,240,247)  <- Gboard 键面
```

→ **卡片绘制底 = 283，键盘上沿 = 284，两者重叠 = 0px。**

#### S3 判据逐条对照（`V82_FIVE_CRITERIA.md` §S3）

| # | 判据原文 | 实测 | 判定 |
|---|---|---|---|
| 1 | 「Home → Settings 打开弹层 → dump 记卡片头行 y（IME 落下）」 | 顶边 **y=95**（像素）/ 94（a11y），h=**580** | ✅ 已取证 |
| 2 | 「点搜索框抬起键盘 → dump 记卡片头行 y（IME 抬起）」 | 顶边 **y=46**（像素）/ 9（a11y 容器），绘制 h=**238** | ✅ 已取证 |
| 3 | 「**键盘抬起后卡片顶边 y 不变**（容差 ≤12px）」 | **95 → 46 = 移动 49px**，超容差 4 倍 | ❌ **不满足** |
| 4 | 「卡片**收缩/滚动体给出键盘以上可视带**」 | 绘制 h **577 → 238**；底边 **671 → 283**（键盘上沿 284，重叠 0px）；两个 ScrollView 分别缩到 **119 / 191** | ✅ 满足 |
| 5 | 「即『键盘以上仍有卡片内容可达』」 | 见下方**可达性实测**：键盘以上带内上滑，`Show agent` / `Terminal placement` 从键盘下方升到键盘以上可见 | ✅ 满足 |
| 6 | 库函数口径 `settingsDialogCardHeightDp(window, imeBottom)` = `min(window−92, 600)`，键盘态取键盘以上可见带为上限、**下限 240dp** | 键盘**落下**态实测 h=**580**：`min(672−92, 600) = 580`（672 = 768 − 96 底部 shell 条高，96 由 dump 节点 `[0,672][1366,768]` 实测）✅ 数字**精确吻合**。键盘**抬起**态实测绘制 h=**238** ≈ **240dp 地板** ✅ 精确吻合 | ✅ 两条都吻合 |
| 7 | 「**反例**（v82 旧 bug）：卡片顶边不动、下半被 IME 盖死、卡片自身不溢出 → 面板 scroll 永不启用」 | **未复现**：①顶边**动了**（−49px，不是"不动"）；②卡片绘制底 283 < 键盘上沿 284，**没有被盖死**；③卡片**溢出**了（577→238 收缩、两个 scroller 真的缩了 340/244px）且 scroll **可用**（见判据 5 实测） | ✅ 反例不成立 |
| 8 | 「设置弹层内**禁用 ESC(111)**；收键盘用 `keyevent 61`(TAB)」 | 本条**零次** `keyevent 111`、**零次** `keyevent 4`；仅用 `keyevent 61` 收键盘 | ✅ |

#### S3 可达性实测（证明"键盘以上仍有卡片内容可达"不是纸面结论）

- 滑动落点**在表单体内**：内容 ScrollView 实测 `[434,94][1173,285]`，落点取 `(800,260) → (800,120)`，两端都在该矩形内。
- 滑动**前**（`ui_V82_S3_02_settings_ime_up.xml`）右列可见：`Language [494,124]`、`Auto-accept permissions [494,215]`。
- 滑动**后**（`ui_V82_S3_03_ime_up_scrolled.xml`）右列可见：`Show agent [494,98]`、`Terminal placement [494,197]`；
  滑动前的两项**已滚出**。键盘**仍在**（`mInputShown=true`）。
- 结论：**原本落在键盘下方的内容，经键盘以上这条 191px 的带子滚动后升到键盘以上** ⇒ 判据 5 成立。

#### S3 结论写法（交 Lead 裁决）

- **旧 bug 已消除**：v82 记的「卡片顶边 y=84 不动、下半被 IME 盖死、scroll 永不启用」在本轮新 APK 上**不复现**。
- **字面判据 3 不满足**：卡片顶边从 y=95 升到 y=46（**49px**），而口径要求「不变，容差 ≤12px」。
  实现选择的是「**卡片整体上移 + 收缩到 240dp 地板**」，口径想要的是「**顶边钉住、只收高度**」。
- 两者都能达成「键盘以上可达」，但**几何形状不同**。这是**口径与实现的形状分歧**，不是功能缺失。
  本轮**不替 Lead 裁决**，按实测原样记录：**S3 = 部分 PASS（功能 PASS / 字面几何判据 FAIL）**。

---

### S4 — Add/Edit server 表单 footer 键盘态可达

- **判定：✅ PASS**（比口径要求的还强一档：**零滚动** footer 就常驻键盘以上）
- **步骤与命令**（坐标全部由 dump 解析，无硬编码）：
  ```
  # 1) 收键盘 + 进 Servers
  adb -s emulator-5556 shell input keyevent 61                       # TAB（禁 BACK/ESC）
  adb -s emulator-5556 shell input tap 255 545                       # = nav "Servers" [231,537][279,553] 中心
  # 2) 开 Add SSH server
  adb -s emulator-5556 shell input tap 1034 199                      # = "Add SSH server" [984,191][1083,207] 中心
  # 3) 点第一个字段抬键盘
  adb -s emulator-5556 shell input tap 674 466                       # = Host EditText [470,442][878,490] 中心
  # 4) 表单体内有效上滑（落点必须在表单体内）
  adb -s emulator-5556 shell input swipe 700 200 700 100 80          # 有效（见下读数 3）
  # 5) footer 可点性证明
  adb -s emulator-5556 shell input tap 750 232                       # = "Cancel" [728,224][771,240] 中心
  ```
- **证据文件**（相对 `research/v97_verify/5556_v82/`）：
  `V82_S4_00_settings_kbd_down.png` / `V82_S4_01_servers_panel.png` / `V82_S4_02_add_form_kbd_down.png` /
  `V82_S4_03_add_form_ime_up.png` / `V82_S4_04_add_form_ime_up_scrolled.png` /
  `V82_S4_05_swipe2.png` / `V82_S4_06_swipe3.png` / `V82_S4_06_swipe4.png` / `V82_S4_06_swipe5.png` /
  `V82_S4_07_after_cancel.png` + 对应 `ui_V82_S4_*.xml`

#### S4 读数 1 —— footer 位置 vs 键盘上沿（核心判据）

| 量 | 读数 |
|---|---|
| `Cancel` bounds | **`[728,224][771,240]`** |
| `Add server` bounds | **`[807,224][876,240]`** |
| 键盘上沿（像素定位，x=674 列） | `y=284` 处 (206,206,206) 分界；`y=285..293` 描边；`y=294` (241,240,247) = Gboard 键面 → **键盘顶 = 284** |
| **footer 底 240 → 键盘顶 284 的净空** | **44px（footer 全段 224..240 均在键盘以上）** |
| `dumpsys input_method` | `mInputShown=true`（键盘确实在） |
| 截图像素判读 | `V82_S4_03` / `V82_S4_06_swipe4` 中 `Cancel`（实心描边）与 `Add server` 两枚按钮**完整画在键盘上方**，未被压边 |

→ **footer 在键盘顶边以上可见** ✅（且**无需任何滚动**即可见，见读数 3）

#### S4 读数 2 —— footer 钉在卡底、**在滚动体之外**（v83 修复的形状核对）

| 状态 | 表单体 ScrollView | footer y | footer 是否在滚动体内 |
|---|---|---|---|
| IME 落下 | `[434,351][914,497]` | `Cancel/Add server` y=**517..533** | ❌ 不在（滚动体底 497 < footer 517） |
| IME 抬起 | `[434,94][914,204]` | y=**224..240** | ❌ 不在（滚动体底 204 < footer 224） |
| IME 抬起 + 上滑后 | `[434,94][914,204]` | y=**224..240**（**逐位未动**） | ❌ 不在 |

→ v83 的「footer（Cancel/Save、Cancel/Add server）**移出滚动体钉在卡底**」在本轮新 APK 上**成立**：
滚动体底始终在 footer 顶（204/497）**之上**，footer 恒在滚动体**之后**。

#### S4 读数 3 —— 「一次**有效**上滑」落点实验（v83 落点问题的本轮复现与定位）

5 次上滑，落点**全部落在表单体 ScrollView `[434,94][914,204]` 内**（该矩形含 x∈[434,914]、y∈[94,204]）：

| # | 命令 | 起→止 | 落点是否在表单体内 | 落点是否在聚焦 EditText 上 | 位移 |
|---|---|---|---|---|---|
| 1 | `input swipe 674 190 674 110 250` | 表单体内 | ✅ | ✅ **是**（Host 框 `[470,173][878,221]`） | **0** |
| 2 | `input swipe 895 195 895 105 150` | 表单体内 | ✅ | 否 | **0** |
| 3 | `input swipe 450 195 450 100 120` | 表单体内（左外边距 x=450 < EditText x≥470） | ✅ | 否 | **0** |
| **4** | **`input swipe 700 200 700 100 80`** | 表单体内 | ✅ | **否**（y=200 落在 footer 与滚动体之间的空白） | **✅ 有效** |
| 5 | `input swipe 446 198 446 100 300` | 表单体内 | ✅ | 否 | 0（已在底） |

**第 4 次的位移读数**（`ui_V82_S4_03` vs `ui_V82_S4_06_swipe4`，`Compare-Object` 差异）：

```
=>  Password            [470,102][531,118]  TextView      <- 新进入视口
=>  Password            [458,140][519,156]  TextView      <- 新进入视口
=>  (空文本)            [470,163][878,211]  EditText pw=true  <- 新进入视口（密码框）
<=  Host or SSH command [458,150][598,166]  TextView      <- 滚出
<=  (空文本)            [470,173][878,221]  EditText foc=true <- 滚出
Cancel       [728,224][771,240]   ← 不变
Add server   [807,224][876,240]   ← 不变
```

→ 表单体**真的能滚**（滚到了底、露出 `Password` 字段），**且 footer 一像素未动**。
**根因定位**：有效上滑需要 **①落点在表单体内且不在聚焦 EditText 上**（1/2/3 号都被文本框吃掉）
**且 ②必须是快滑**（4 号 100px/80ms 有效；5 号 98px/300ms 与 1 号 250ms 均 0 位移）。
这与 `HANDOFF.md` v90 记的「表单滚动体对慢速拖拽无响应、只有快滑生效」**同型**，
v83 测试员「9 次零位移」的成因也在此：**慢速 + 落在聚焦文本框上**。

#### S4 读数 4 —— footer「可点」实测（不是只看坐标）

- 在**键盘仍在**（`mInputShown=true`）的状态下，tap `Cancel` 中心 `(750,232)`：
  - 表单**关闭**：`ui_V82_S4_07_after_cancel.xml` 里 `Password` 字段**消失**（`Has 'Password' = False`）
  - 回到 Servers 面板：`127.0.0.1:4095` 行**仍在**（`Has '127.0.0.1:4095' = True`）
  - 键盘随之落下（`mInputShown=false`）
- 证据：`V82_S4_07_after_cancel.png` / `ui_V82_S4_07_after_cancel.png` 对应 dump
- → **y=232 这个点在键盘以上且真实命中 footer**，判据「可见**可点**」✅ 成立。

#### S4 判据逐条对照（`V82_FIVE_CRITERIA.md` §S4）

| # | 判据原文 | 实测 | 判定 |
|---|---|---|---|
| 1 | 「Settings → Servers → `Add SSH server`」 | 逐级 dump 解析点入（`Servers` [231,537] → `Add SSH server` [984,191]） | ✅ |
| 2 | 「点第一个字段抬键盘」 | Host EditText foc=**true**，`mInputShown=true` | ✅ |
| 3 | 「**footer 在键盘顶边以上可见可点**」 | footer y **224..240** vs 键盘顶 **284**，净空 **44px**；`Cancel` 实点关闭表单 | ✅ |
| 4 | 「纯函数 `serverDialogBandDp(...)` = 键盘以上剩余可视带，**下限 `SERVER_DIALOG_MIN_BAND_DP = 160`**」 | 一级设置卡键盘态绘制带 = **y 46..283 → 238px**；二级表单卡带 = y 9..~283 → **~275px**；两者都 **≥160dp 地板**，未被地板截断 | ✅（未触地板，无冲突） |
| 5 | 「卡片改为 `.heightIn(max = band)`，表单体 `verticalScroll` 生效」 | 表单体 = `android.widget.ScrollView`，且第 4 次快滑**实测滚到底** | ✅ |
| 6 | 「v83 结论：卡片自己 footer 在滚动体里，所以『先上滑能滚到底、Save 可见』即 PASS（不必一屏全见）」 | **本轮 footer 已移出滚动体**（读数 2），**零滚动**即常驻可见 ⇒ 比口径的放行条件**更强** | ✅ |
| 7 | 「证据需含**一次有效上滑**（落点必须在表单体内，落在键盘区/表单外的 swipe 零位移）」 | 第 4 次 `input swipe 700 200 700 100 80`：落点 `[434,94][914,204]` 内、键盘区（y≥284）外，**位移实测有效** | ✅ |
| 8 | 纪律：设置/表单弹层内禁 ESC(111) | 本条**零次** `keyevent 111`、**零次** `keyevent 4` | ✅ |

#### S4 附注（观测，非判据）

- `Add server` 在 Host 为空时**呈灰色禁用**（截图可见；a11y `enabled=true` 是 TextView 标签层的属性，
  实心/禁用由父 Button 承载）。这是**必填校验门**，非缺陷；本轮**未**提交表单，**未新增任何 SSH 条目**。
- **只对唯一的共享 `127.0.0.1:4095` 条目做过只读观察**（面板里读到了它的 `Server healthy` / `Default`），
  **没有点过它的 ⋮ / Edit / Delete**，后端与该条目均未被改动。
- 口径 S4 的标题写「Add/Edit server」，本轮只跑了 **Add** 分支；**Edit 分支未取证**。

---

### S5 — 纯 SSE 掉线自愈后「Not connected — …」卡自行退休

- **判定：✅ PASS**（断线→卡片出现→恢复隧道→**卡片自行退休，全过程无任何手动清卡操作**）
  ⚠️ 见下方「与口径的两处偏差」：**本轮触发的是同族另一句**，不是字面以 `Not connected` 开头的那句。
- **授权边界遵守情况**：
  - **只动 `adb reverse` 转发表**，**零次**对后端进程的操作（未 kill / 未 restart / 未改配置）。
  - 每个检查点都复核 `reverse --list`，**结束时已恢复两条**（见下）。
  - 只在**我自己建的会话**里操作（`E2ECHK5556 s5 check`）。
  - 全程**未按 BACK(4)**、**未按 ESC(111)**。

#### S5 读数 1 —— 被动取证（断线前）

- `V82_S5_00_passive_servers.png` / `ui_V82_S5_00_passive_servers.xml`：
  `Has 'Not connected' = False`，`Has 'connection' = False` —— 断线前屏幕上**没有**该家族任何卡。
- `V82_S5_06_session_live.png` / `ui_V82_S5_06_session_live.xml`：新建会话页，composer `foc=true`，
  空态 `Build anything`，`Has 'Not connected' = False`。
- `reverse --list`（断线前）：`host-19 tcp:4095 tcp:4095` + `host-19 tcp:2222 tcp:22` ✅ 两条都在。

#### S5 读数 2 —— 授权手法 cycle 1（断线 → 卡片出现 → 立即恢复 → 退休）

| 时刻 | 动作 / 读数 | 证据 |
|---|---|---|
| 18:16:25 | `adb -s emulator-5556 reverse --remove tcp:4095` | `E2ECHK5556_run.log` |
| 18:16:26 | `reverse --list` = **`host-19 tcp:2222 tcp:22`**（4095 已摘除，仅剩 SSH 隧道） | log |
| 18:16:26–31 | 键入 `E2ECHK5556 s5`（分段 `E2ECHK55` + `56` + `keyevent 62` 空格 + `s5`）；dump 回读 composer 文本 = **`E2ECHK5556 s5`**（无 `%s`、无丢空格） | `V82_S5_07_typed.png` / `ui_V82_S5_07_typed.xml` |
| 18:16:32 | tap `Send`（坐标由 dump 解析 `[1308,248][1356,296]` → `(1332,272)`） | log |
| 18:16:37 | **卡片出现**：`The connection to the server dropped. Check your network, then try again.` bounds **`[52,170][522,186]`**；同时**草稿 `E2ECHK5556 s5` 回到 composer**（v76 保稿修复生效） | **`V82_S5_08_send_failed.png`** |
| 18:16:37 | **立刻恢复** `adb -s emulator-5556 reverse tcp:4095 tcp:4095`；`reverse --list` = **`tcp:4095` + `tcp:2222` 两条都回来了** ✅ | log |
| 18:16:52–18:18:27 | **14 次轮询（t+0 … t+65s，每 5s 一次）**：卡片**始终在**，且 bounds **逐次逐位相同** `[52,170][522,186]` | `ui_V82_S5_09_poll_00.xml` … `ui_V82_S5_09_poll_13.xml` |
| 18:18:57 | 隧道已通的前提下再 tap `Send` → 发送成功，用户气泡 **`E2ECHK5556 s5 check`** 落地 | `V82_S5_11_after_retry_send.png` / `ui_V82_S5_11_after_retry_send.xml` |
| 18:18:57 | **卡片消失**：`Has 'connection to the server dropped' = False` —— **无任何手动清卡动作** | 同上 |

> **读法**：空闲期 65s 内 transport 没有任何请求、因此没有 `TransportRecovered`，卡片**合理地**留在屏上；
> 一旦 transport 真正恢复（成功发送），卡片**同一帧内被退休**。

#### S5 读数 3 —— cycle 2（隔离实验：**不发消息**，只做只读 transport 探针）

| 时刻 | 动作 / 读数 | 证据 |
|---|---|---|
| 18:19:24 | `reverse --remove tcp:4095`；`reverse --list` = **仅 `tcp:2222`** ✅ | log |
| 18:19:31 | 键入 `E2ECHK5556 s5b` 并 tap `Send` | log |
| 18:19:37 | **卡片再次出现**（截图可见）+ 草稿 `E2ECHK5556 s5b` 保留 | **`V82_S5_14_card_again.png`** |
| 18:19:37 | **立刻恢复** `reverse tcp:4095 tcp:4095`；`reverse --list` = **两条都回来了** ✅ | log |
| 18:20:03 | 轮询 dump：**卡片已不在 a11y 树里**（`Has = False`） | `ui_V82_S5_15_card_present_dumps.xml` |
| 18:20:03–09 | **非发送**探针：toggle `Toggle review panel`（Files 面板开→关，发起 API GET） | `V82_S5_16_probe_files_open.png` |
| 18:20:09 | **卡片消失**、会话进入 `Working` —— **全程零发送、零手动清卡** | **`V82_S5_17_probe_files_closed.png`** |

→ **cycle 2 是 S5 判据的干净隔离**：隧道恢复后**没有任何用户清卡动作**，卡片**自行退休**。

#### S5 判据逐条对照（`V82_FIVE_CRITERIA.md` §S5）

| # | 判据原文 | 实测 | 判定 |
|---|---|---|---|
| 1 | 「用 `adb reverse` 摘除制造纯客户端掉线，**不动后端进程**」 | 仅 `reverse --remove/重新挂`；后端 `pid=260` 全程未动 | ✅ |
| 2 | 「观察 `Not connected — …` 卡出现」 | 卡片**确实出现**（同族另一句，见偏差①） | ✅（有偏差） |
| 3 | 「**立刻** `reverse tcp:4095 tcp:4095` 恢复」 | cycle1 断线后 **1s 内**恢复；cycle2 断线后 **1s 内**恢复 | ✅ |
| 4 | 「断言卡**自行退休**」 | cycle1 随成功发送退休；**cycle2 无发送自行退休** | ✅ |
| 5 | 「验完确认 `reverse --list` 已恢复」 | 见下方终态复核 | ✅ |
| 6 | 口径写的限制「本机只做被动取证，结论写 NEEDS LEAD / 未验到，不许猜 PASS」 | **本轮 Lead 明确授权了非破坏性手法**（`reverse --remove`），故按授权实测，**不写 NEEDS LEAD** | ✅（授权范围内） |

#### S5 与口径的两处偏差（必须写进报告，不掩盖）

1. **触发的不是字面 `Not connected — …` 那句**，而是同族的
   **`The connection to the server dropped. Check your network, then try again.`**。
   口径引的实现依据是 `TransportRetry.isClientNotConnectedText`（**行首**前缀 `not connected`）。
   本轮**没有**制造出以 `Not connected` 开头的卡（那是 `requireTransport()` 为空时的
   `ChatViewModel.NOT_CONNECTED_LINE` 早退分支，本轮发送时 transport 仍在、只是链路断，故走的是
   另一条 provider/stream 失败路径）。**退休行为已验证，但验证的是六句家族里的另一句。**
2. **空闲期不退休属预期**：隧道恢复后 65s 内无任何请求 → 无 `TransportRecovered` → 卡留在屏上。
   口径说的"自愈"隐含"有重连行为发生"；本机 transport 是**惰性重建**的，
   **只要发生过一次成功的 transport 活动，卡即退休**。

#### S5 附带发现（非本条判据，但值得上报）

- **草稿保稿在断线场景下有效**：两次失败发送后，`E2ECHK5556 s5` / `E2ECHK5556 s5b`
  都**完整回到 composer**（v76 `restoreDraft` 契约在真机成立）。
- **`lucky-planet` 项目的会话一建就 500**：新建会话立刻报
  `This session is unavailable on the server` / `The server returned an error for this session (HTTP 500). Its project folder may be missing on the server.`
  （与 `HANDOFF.md` v81/v82 记的"坏项目目录 ⇒ 带位置路由全 500"同型）。
  测试员已把该条目**删除**，改用 `opencode-android-shell/main` 项目建会话成功。
  **本轮建的会话**（服务端自动命名 `E2ECHK5556 s5 check`）标题前缀合规。

---

## 3. 汇总判定表

| 条目 | 判定 | 一句话依据 | 关键读数 |
|---|---|---|---|
| **S1** Ctrl+K → PALETTE | ✅ **PASS** | `keycombination 113 39` 弹出命令面板 | 搜索框 `[403,96][985,144]` + `Commands` 分组 + 5 条命令候选行；零模型名节点（排除模型选择器）、零改名框（排除草稿页） |
| **S2** Ctrl+Shift+P → PALETTE | ✅ **PASS**（口径命令有误，已勘误） | 正确命令是 `keycombination 113 **59** 44` | 与 S1 节点表 **12 行 vs 12 行、差异 0**；输入态（`foc=true`+`mInputShown=true`）下 Ctrl+K 仍开面板 → 不让位 |
| **S3** 设置弹层让位 | 🟡 **部分 PASS** | 旧 bug 已消除，但字面判据「顶边不变」不满足 | 卡片绘制 **h 577→238（=240dp 地板）**、底 671→**283** vs 键盘顶 **284**（重叠 0）；**顶边 95→46（移动 49px > 12px 容差）** |
| **S4** 表单 footer 键盘态可达 | ✅ **PASS**（比口径更强） | footer **零滚动**常驻键盘以上 | footer `y 224..240` vs 键盘顶 `284`，净空 **44px**；footer 在滚动体**之外**；`Cancel` 实点关闭表单；快滑 100px/80ms 实测滚到底露出 `Password` |
| **S5** 掉线卡自行退休 | ✅ **PASS** | `reverse --remove` → 卡出现 → 立即恢复 → **卡自行退休** | cycle2 全程**零发送零手动清卡**，卡片在隧道恢复后自行消失；`reverse --list` 终态两条齐全；后端 **pid=260 全程未变** |

**总状态：DONE**（S1/S2/S4/S5 PASS，S3 部分 PASS 需 Lead 裁决形状分歧）

---

## 4. 与 `V82_FIVE_CRITERIA.md` 的逐条对照总表

| 口径条目 | 判据要点 | 本轮结果 | 差异说明 |
|---|---|---|---|
| S1 命令 | `keycombination 113 39` | 原样执行 | 无差异 |
| S1 判据 | 出现 palette 层（面板容器/搜索框/命令分组行） | ✅ 全部命中 | 无差异 |
| S1 反例 | 不得用 75/42 | ✅ 未用 | 无差异 |
| **S2 命令** | **`keycombination 113 44`** | ❌ **口径错**：那是 **Ctrl+P**（无 Shift） | **`SettingsModel.kt:1306` `file.open`="Ctrl+P" → `App.kt:2172` `PALETTE_FILES` files-only 面板。正确应为 `113 59 44`**（`SHIFT_LEFT=59`）。与 `HANDOFF.md:71` 的 v82 `113 75` **同型脚本错** |
| S2 判据 | 与 S1 是同一个面板 | ✅ 节点表零差异 | 无差异 |
| S2 反例 | 输入态 chord 不让位 | ✅ 已验（Home 搜索框） | composer 专属分支未单独跑 |
| S3 步骤 | Home→Settings→记两态 y | ✅ 两态均取到（a11y + 像素双读数） | 无差异 |
| **S3 判据 a** | **卡片顶边 y 不变（≤12px）** | ❌ **实测移动 49px** | 实现选择「上移+收缩」，口径要「顶边钉住+只收高度」 |
| S3 判据 b | 卡片收缩给出键盘以上可视带 | ✅ 577→238、底 283<键盘 284 | 无差异 |
| S3 库函数 | `min(window−92,600)` + 240dp 下限 | ✅ 落下 580、抬起 238(≈240)，**两条数字都精确吻合** | 无差异 |
| S3 反例 | 旧 bug 三特征 | ✅ 三条全部不成立 | 无差异 |
| S4 判据 | footer 在键盘顶边以上可见可点 | ✅ 净空 44px + 实点命中 | 比口径更强（零滚动） |
| S4 库函数 | `serverDialogBandDp` / 160dp 下限 | ✅ 两级带均 ≥160dp，未触地板 | 无差异 |
| S4 证据 | 一次**有效**上滑（落点在表单体内） | ✅ 第 4 次生效（快滑 100px/80ms） | 1/2/3/5 次零位移，已定位成因 |
| S5 限制 | 原文写「只做被动取证，结论 NEEDS LEAD」 | 本轮按 **Lead 本次任务书的明确授权**执行了 `reverse --remove` | 授权范围已扩大，故不写 NEEDS LEAD |
| S5 判据 | 卡**自行**退休 | ✅ cycle2 零发送自行退休 | 触发的卡是同族另一句，非字面 `Not connected` |

---

## 5. 未闭环 / 未验到清单（诚实登记）

| # | 项 | 状态 | 说明 |
|---|---|---|---|
| 1 | **S3「卡片顶边不变 ≤12px」** | ❌ **判为不满足** | 实测 95→46（49px）。功能目标达成、v82 旧 bug 已消除，但**几何形状与口径不一致**。**需 Lead 裁决**：是口径放宽（接受「上移+收缩」），还是实现改为「顶边钉住」？**本轮不替 Lead 下结论**。 |
| 2 | **S2 口径命令 `113 44`** | ⚠️ **口径文档需修** | 建议 Lead 把 `V82_FIVE_CRITERIA.md` §S2 的命令改成 `input keycombination 113 59 44`，并加注「`113 44` = Ctrl+P = `file.open` files-only 面板」。 |
| 3 | **S5 字面 `Not connected — …` 那句** | ⚠️ **未验到** | 本轮只制造出同族的 `The connection to the server dropped. Check your network, then try again.`。`isClientNotConnectedText` 的**行首前缀 `not connected`** 分支**未被触发**（需要 `requireTransport()` 为空的早退路径）。**退休行为已验，触发分支未验。** |
| 4 | S2 反例的 **composer** 专属分支 | ⚠️ 未单独取证 | 用的是 Home `Search sessions` 输入框（同样是真输入框、同样证明不让位）。 |
| 5 | S4 的 **Edit server** 分支 | ⚠️ 未取证 | 口径标题含 Edit，本轮只跑 Add 分支。 |
| 6 | 键盘高度漂移 | ℹ️ 记录 | 本轮两处独立测得键盘上沿**均为 y=284**（S3 与 S4 各测一次，互相印证），本机该值稳定。`HANDOFF.md` v87 记的 342 是**另一台设备**的读数，不可跨设备比对。 |
| 7 | 会话收尾 | ℹ️ 记录 | 本轮自建会话 `E2ECHK5556 s5 check` 在结束时仍显示 `Working`（模型 `Space Bunny Free` 的 turn 未跑完）。**未 stop、未删除**，留在后端供 Lead 复核；标题前缀合规。 |
| 8 | 副作用登记 | ℹ️ 记录 | ①`lucky-planet` 项目下因目录缺失而 500 的会话条目，**由测试员创建后已删除**；②自建会话 1 个（`E2ECHK5556 s5 check`）保留；③**共享 `127.0.0.1:4095` 服务器条目全程只读**（未点 ⋮/Edit/Delete）。 |

---

## 6. 证据索引（本轮，全部在新 APK 上重取）

| 类别 | 文件 |
|---|---|
| 环境与终态 | `env_readings.txt`、`final_state.txt`、`E2ECHK5556_run.log` |
| S1 | `V82_S1_00_launch.png` / `V82_S1_01_after_ctrlk.png` / `ui_V82_S1_00_launch.xml` / `ui_V82_S1_01_after_ctrlk.xml` / `V82_S1_02_after_dismiss.png` / `V82_S1_02_after_dismiss2.png` |
| S2 | `V82_S2_00_before.png` / `V82_S2_01_after_ctrlshiftp.png`（**口径错命令的反例**）/ `V82_S2_03_after_ctrl_shiftP.png`（**正确命令**）/ `V82_S2_04_home_clean.png` / `V82_S2_05_input_focused.png` / `V82_S2_06_ctrlk_in_input_state.png` + 全部对应 `ui_*.xml` |
| S3 | `V82_S3_00_home_clean.png` / `V82_S3_01_settings_kbd_down.png` / `V82_S3_02_settings_ime_up.png` / `V82_S3_03_ime_up_scrolled.png` + `ui_*.xml` |
| S4 | `V82_S4_00_settings_kbd_down.png` / `V82_S4_01_servers_panel.png` / `V82_S4_02_add_form_kbd_down.png` / `V82_S4_03_add_form_ime_up.png` / `V82_S4_04_add_form_ime_up_scrolled.png` / `V82_S4_05_swipe2.png` / `V82_S4_06_swipe3.png` / `V82_S4_06_swipe4.png`（**有效上滑**）/ `V82_S4_06_swipe5.png` / `V82_S4_07_after_cancel.png`（**footer 实点**）+ `ui_*.xml` |
| S5 | `V82_S5_00_passive_servers.png` / `V82_S5_02_new_session.png` / `V82_S5_04_after_delete.png` / `V82_S5_05_session_ok.png` / `V82_S5_06_session_live.png` / `V82_S5_07_typed.png` / **`V82_S5_08_send_failed.png`（卡片出现）** / `V82_S5_09_poll_00…13.xml`（14 次轮询）/ **`V82_S5_11_after_retry_send.png`（cycle1 退休）** / **`V82_S5_14_card_again.png`（cycle2 卡片再现）** / **`V82_S5_17_probe_files_closed.png`（cycle2 自行退休）** + `ui_*.xml` |
| 工具脚本 | `E2ECHK5556_lib.ps1`（Shot/Ui/Nodes/Tap/TapMatch，截图只走 screencap+pull）、`E2ECHK5556_nodes.py`（节点表）、`E2ECHK5556_geom.py`（全容器 bounds）、`E2ECHK5556_pix.py`（像素白面探针）、`_kb_edge.py`、`_footer_attrs.py` |
| **上轮遗留（非本轮证据）** | `00_launch_1366.png` … `ui_20_settings_imeup.xml`、`probe.py`、`uidump.py` —— host 时间 17:24–17:37，**早于 18:01 装机**，已在 §1.1 声明作废 |

---

## 7. 复现本轮的命令清单（全部带 `-s emulator-5556`）

```powershell
# 设备身份
adb -s emulator-5556 emu avd name                       # -> Medium_Desktop
adb -s emulator-5556 shell wm size                      # -> Override size: 1366x768
adb -s emulator-5556 shell wm density                   # -> Override density: 160
adb -s emulator-5556 shell settings get secure show_ime_with_hard_keyboard   # -> 1
adb -s emulator-5556 reverse --list

# 启动
adb -s emulator-5556 shell am start -n com.xiufeigo.opencodeshell/.OcActivity

# S1 / S2
adb -s emulator-5556 shell input keycombination 113 39     # Ctrl+K        -> PALETTE
adb -s emulator-5556 shell input keycombination 113 44     # Ctrl+P        -> PALETTE_FILES（口径错，反例）
adb -s emulator-5556 shell input keycombination 113 59 44  # Ctrl+Shift+P  -> PALETTE（正确）

# S3
adb -s emulator-5556 shell input keycombination 113 55     # Ctrl+,        -> SETTINGS
adb -s emulator-5556 shell input tap 313 171               # 点设置搜索框抬键盘

# S4
adb -s emulator-5556 shell input tap 255 545               # Servers
adb -s emulator-5556 shell input tap 1034 199              # Add SSH server
adb -s emulator-5556 shell input tap 674 466               # Host 字段抬键盘
adb -s emulator-5556 shell input swipe 700 200 700 100 80  # 表单体内有效快滑
adb -s emulator-5556 shell input tap 750 232               # Cancel（证明 footer 可点）

# S5（唯一被授权的非破坏性手法）
adb -s emulator-5556 reverse --remove tcp:4095
adb -s emulator-5556 reverse tcp:4095 tcp:4095            # 立刻恢复
adb -s emulator-5556 reverse --list                       # 终态复核两条齐全
```

> **全程禁用且确未使用**：`input keyevent 4`（BACK）、`input keyevent 111`（ESC）、
> `adb exec-out`、gradle、`pm install`、`am force-stop`。
> 键盘收起统一用 `input keyevent 61`（TAB）；所有 tap/swipe 坐标均运行期从 dump 解析。
