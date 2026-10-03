# 真机复验报告 — 标题热区修复 + 时间线搜索首次真机验证（emulator-5554 / Pixel_9a）

- 测试员：真机复验测试员（v98）
- 日期：2026-10-02
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 输入修复报告：`research/v97_fix/SESSION_SEARCH_FIX.md`
- 被测对象：已装 Lead 19:01 闸门构建
- 自有会话（后端创建，共享后端仅动此两条）：
  - `ses_f03b658beffea2AB3xaQ2wiC3W` | `E2ECHK5554v98-search-01`
  - `ses_f03b4c860ffe4MwPYzI0oUul3i` | `E2ECHK5554v98-b`

---

## 0. 环境读数

| 项 | 实测值 | 命令 |
|---|---|---|
| 设备 serial | `emulator-5554`（`model:sdk_gphone16k_x86_64`） | `adb devices -l` |
| AVD 名 | **`Pixel_9a`** ✅ 与要求一致 | `adb -s emulator-5554 emu avd name` |
| 屏幕 | **1080x2424**，`Physical density: 420` | `adb shell wm size` / `wm density` |
| 安装版本 | `versionName=1.4.0`，**`lastUpdateTime=2026-10-02 11:02:36`**，`firstInstallTime=2026-09-27 04:29:58` | `adb shell dumpsys package com.xiufeigo.opencodeshell` |
| reverse 端口 | `host-15 tcp:4095 tcp:4095`、`host-15 tcp:2222 tcp:22` ✅ | `adb reverse --list` |
| 后端健康 | `{"version":"2.0.18","pid":260,"urls":["http://127.0.0.1:4095"],"paths":{"tmp":"/tmp/opencode"}}` | `GET /api/info` |
| launcher | `am start -n com.xiufeigo.opencodeshell/.OcActivity` → `mCurrentFocus=…OcActivity` ✅ | — |

设备确认：serial↔AVD 关系为本轮实测（`emu avd name` 返回 `Pixel_9a`），未使用 127.0.0.1:5555，未跑 gradle、未装机、未杀进程。

### 坐标纪律

本轮所有点击坐标**全部运行期从 uiautomator dump 解析**，无一处凭印象。另有一步像素级取证（`System.Drawing` 扫描 header 行墨迹像素）用于区分「点击区边界」与「文字墨迹边界」——因为 dump 的 node bounds 在 `widthIn(max=…)` 与 `width(…)` 两种布局下**可能相同**（标题够长时都会被夹到 `titleWidth`），只看 bounds 会误判。

---

## 1. 判定汇总

| 编号 | 检查项 | 判定 | 关键读数 |
|---|---|---|---|
| 1① | 点标题**文字本身** → 内联编辑器打开 | ✅ **PASS** | 点 (394,287) → EditText `[258,227][822,348]` `focused="true"` |
| 1② | 点标题右侧**空白区** → 编辑器**不**打开 | ✅ **PASS** | 点 (600,287) → header 无 EditText，标题仍为纯文本 |
| 1③ | 点 🔍 → 搜索条出现 | ✅ **PASS** | 点 (691,287) → 搜索条 `[300,228][970,348]`，含 `Find…` 与 ✕ |
| 2 | 搜索能重算（`pathlib` → `the` → `pathlib`） | ✅ **PASS** | `pathlib`→`1/3`；`the`→**`1/2`**（重算）；改回 `pathlib`→`1/3`（一致） |
| 3 | 退出搜索复位 | ✅ **PASS** | 点 ✕ `(1025,288)` → 搜索条/✕/`1/N` 计数全清，`Search session`/`Ask a side question`/`Context usage` 全部回来 |
| 4 | 标题无污染（curl 原文） | ✅ **PASS** | `E2ECHK5554v98-b` / `E2ECHK5554v98-search-01` 逐字未变，无 `pathlib|zeta|Find` 污染 |

---

## 2. 热区修复（1①/1②/1③）逐条

### 2.0 先确立一个反直觉的读数

进入**短标题**会话 `E2ECHK5554v98-b`（14 字符），header dump 实测：

```
v98_004_short_open.xml
  [Button] [106,227][221,348]  desc='Open sessions'
  [Button] [221,227][567,348]  text='E2ECHK5554v98-b'   ← 宽 346 px
  [Button] [639,227][744,348]  desc='Search session'    ← 宽 105 px
  [Button] [744,227][849,348]  desc='Ask a side question'
  [View]   [849,227][954,348]  desc='Context usage'
  [Button] [954,227][1080,348] desc='More options'
```

标题 node 宽 **346 px**，其右缘 567 与 🔍 左缘 639 之间存在 **72 px 空白带**。
在修复前（`.width(metrics.titleWidth)`），该 node 应为 `[221,227][639,348]` = **418 px** 满行，即 567..639 这 72 px 属于标题热区。

像素级取证（`v98_004_short_open.png` header 行扫描，阈值 60）：
- 标题文字**墨迹**实际 x = **240..549**（宽 310 px）
- 墨迹右缘 549 → node 右缘 567 = 18 px，即 `padding(horizontal = 6.dp)`（6dp@420dpi ≈ 15.75px）✅ 与源码 padding 一致
- 墨迹左缘 240 → node 左缘 221 = 19 px，同为 padding ✅

> **结论**：node 宽 346 ≈ 文字 310 + 左右 padding 2×19，**不是**满行 418。即 `.widthIn(max = …)` 已生效，点击区已回到「文字本身」（对齐上游 `w-fit`）。
> 补充对照：长标题会话 `E2ECHK5554v98-search-01`（22 字符）的 node 为 `[221,227][639,348]` = 418px 满行——这**不**说明未修复，而是该标题墨迹本身已达上限、被 `widthIn(max=)` 正确夹住（这正是 v49 BUG-1 窄窗省略号行为）。**故用短标题会话作为热区判定基准。**

### 2.1 1① 点标题文字本身 → 编辑器打开（正路径未坏）

- 取证：`v98_004_short_open.png` 墨迹中心 = (394, 287)
- 命令：`adb -s emulator-5554 shell input tap 394 287`
- 证据：`v98_005_tap_title_text.xml`

```
[EditText] bounds="[258,227][822,348]" focusable="true" focused="true" clickable="true"
```

✅ **PASS**。内联标题编辑器打开，EditText 获得焦点。
退出方式：`input keyevent 111` ×2（第一次收键盘、第二次 cancel），**未提交改名**。
退出后 `v98_006_esc_exit_editor.xml` 复核：header EditText 计数 0，标题 node 回到 `[221,227][567,348]`，`Search session` 回来 → 复位正常。

### 2.2 1② 点标题右侧空白区 → 编辑器不打开（修复核心）

- 选点 (600, 287)：**在**旧热区 `[221,227][639,348]` 内（修复前必开编辑器），**不在**新标题 node `[221,227][567,348]` 内，**不在**文字墨迹 240..549 内，**不在** 🔍 node `[639,227][744,348]` 内
- 命令：`adb -s emulator-5554 shell input tap 600 287`
- 证据：`v98_007_tap_blank.xml` / `v98_007_tap_blank.png`

```
header editor EditText(258,227) present after blank tap: 0
title node still plain text [221,227][567,348]        : 1
Search session still present                           : 1
```

✅ **PASS**。修复前此处必打开标题编辑器；现已无响应。

### 2.3 1③ 点 🔍 → 搜索条出现

- 选点 (691, 287) = 🔍 node `[639,227][744,348]` 中心（运行期从 dump 解析）
- 命令：`adb -s emulator-5554 shell input tap 691 287`
- 证据：`v98_008_search_open.xml` / `v98_008_search_open.png`

```
搜索条 node   : [300,228][970,348]   （EditText，hint='Find…'）
✕ 关闭按钮   : 存在（content-desc 含 'close' 的节点 3 个）
Session summary : 0   ← 搜索态隐藏
Context usage   : 0   ← 搜索态隐藏
More options    : 1   ← 仍在
标题 node      : 被搜索条整段替换（`return@Row`）
```

✅ **PASS**。搜索条出现、含 ✕ 关闭按钮、输入框为搜索栏样式（高度 120px 满行内的独立条，非 48px 标题编辑器）。
📌 **如实记录**：`Session summary` / `Context usage` 按本仓既定取舍在搜索态被隐藏（`SessionHeader.kt` 的 `if (searching) { …; return@Row }`），修复报告 §3 已将其列为「有意偏离、不改」，此处仅确认行为与文档一致，**不判为缺陷**。
📌 判别要点：标题编辑器与搜索条形态完全不同——编辑器是 `[258,227][822,348]` 满行 EditText 且**无** ✕；搜索条是 `[300,228][970,348]` 带 `Find…` hint 且**有** ✕。二者不会混淆。

---

## 3. 搜索真的能用（首次真机渲染层验证）

> 上一轮的「搜索结果冻结」经复核是坐标打偏打开了标题编辑器，**时间线搜索的渲染层从未在真机跑过**。本节是它的首次真机验证。

### 3.0 先造出可被搜的内容

`POST /api/session/:id/message` 在本后端（2.0.18）返回 404（`/api/session/:id/prompt` 返回 400 缺 model 参数），故改为**走 App 自己的 composer**，这也更贴近真实用户路径：

1. `input tap 540 2156`（**运行期从 `v98_011_composer.xml` 解析的当前 composer 位置 `[74,2093][1006,2219]` 中心**；首次按 dump 里的 `[74,1273][1006,1399]` 点空，是键盘弹起后布局上移所致——这正是「凭印象点」的典型坑）
2. 分段输入 `pathlib` / `the` / `zeta`（每段 ≤8 字符，段间 sleep）
3. `input tap 990 1462`（Send，`[927,1399][1053,1525]` 中心，来自 `v98_012_composer_typed.xml`）

发送后 `v98_013_sent.xml` 确认用户消息已入时间线：
```
text='pathlib the zeta' | bounds=[734,522][1006,575]
```
模型（`gpt-6.1-sol`）的回复也落在时间线里，同样含 `pathlib` 与 `the`——这直接决定了下面的命中数。

### 3.1 搜索条输入框位置（运行期解析）

`v98_014_searchbar.xml`：
```
搜索 EditText : [300,228][970,348]
Close search : [970,228][1080,348]   ← ✕ 关闭按钮（① 已断言其存在）
```

### 3.2 三次查询的读数

每一步都从 dump 里重新读出「查询词 + 命中计数」两枚 node：

| 步骤 | 命令 | 查询词 node | 命中计数 node | 判定 |
|---|---|---|---|---|
| a | 逐字符输入 `p a t h l i b` | `pathlib` \| `[300,227][799,348]` | **`1/3`** \| `[826,273][844,308]` | 首次出结果 |
| b | `keyevent 67` ×7 删除后输入 `t h e` | `the` \| `[300,227][799,348]` | **`1/2`** \| `[826,273][844,308]` | ✅ **重算**（3→2，非冻结） |
| c | `keyevent 67` ×3 删除后输入 `p a t h l i b` | `pathlib` \| `[300,227][799,348]` | **`1/3`** \| `[826,273][844,308]` | ✅ **与 a 一致** |

- 证据：`v98_015_search_pathlib.xml/.png`、`v98_016_search_the.xml/.png`、`v98_017_search_pathlib2.xml/.png`
- 命中数解释：`pathlib` 出现在**用户消息**与**模型回复**两处，加 App 自身的 header/组合层匹配共 3；`the` 只匹配 2。计数随查询词变化，**不是首次查询的冻结值**。

✅ **PASS（2 的三项断言全部成立）**：能出结果与命中数 → 改词后**结果重算**（`1/3`→`1/2`）→ 改回后**结果一致**（`1/3`）。

### 3.3 搜索条与标题编辑器的形态区分（防再误判）

| | 标题编辑器 | 搜索条 | 关闭按钮 |
|---|---|---|---|
| bounds | `[258,227][822,348]` | `[300,228][970,348]` | 编辑器**无**；搜索条有 `Close search` |
| 提示 | 无 | `Find…` | — |
| 命中计数 | 无 | `1/N` 形式的 node | — |

修复报告 §3.5 判别表里用的是 5562 那台 1366px 机器的高度读数（48px）。本轮 1080px 设备上两者**高度**区分度较低（都是满行级），**真正的区分锚点是「有没有 ✕ / 有没有 `Find…` / 有没有 `1/N` 计数」**——本轮全部按锚点判定，未依赖高度。

---

## 4. 退出搜索复位（复验项 3）

- 命令：`input tap 1025 288`（= `Close search` node `[970,228][1080,348]` 中心，运行期解析）
- 证据：`v98_018_search_closed.xml` / `v98_018_search_closed.png`

```
search bar EditText(300,228) gone : 0   ← 搜索条消失
Close search btn gone             : 0   ← ✕ 随搜索条一起消失
hit counter gone                  : 0   ← 过滤清空（无残留 1/N）
title back [221,227][567,348]     : 1   ← 标题 node 复位，bounds 与关闭前逐字相同
Search session back               : 1   ← 🔍 回来
Ask a side question back          : 1   ← 回来
Context usage back                : 1   ← 回来（搜索态隐藏的那个）
More options back                 : 2
```

header 全量复原（与 §2.2 修复后基线逐字一致）：
```
Back [1,227][106,348] | Open sessions [106,227][221,348] | E2ECHK5554v98-b [221,227][567,348]
Search session [639,227][744,348] | Ask a side question [744,227][849,348]
Context usage [849,227][954,348]  | More options [954,227][1080,348]
```

✅ **PASS**。过滤清空、搜索条消失、header 入口全部回来。**未按 BACK、未用 ESC 强退**。

---

## 5. 不产生数据损坏（复验项 4）

全程**未使用搜索框改标题**：搜索框只用于查询，标题只被 1① 打开过内联编辑器并用 ESC 两次 cancel（未 commit、未 Enter）。

派单给定命令**原样**执行，输出如下：

```powershell
$h=@{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('opencode:opencode'))}; (Invoke-RestMethod -Uri 'http://127.0.0.1:4095/api/session' -Headers $h) | Where-Object { $_.title -like 'E2ECHK5554v98*' } | ForEach-Object { $_.id + ' | ' + $_.title }
```

**curl 原文输出：**

```
（空 —— 无输出行）
```

⚠️ **这个「空」是查询写法与该端点返回结构不匹配，不是「查无此会话」**。实测证据：

```
GET /api/session 的返回顶层形状 = PSCustomObject，属性 = data, cursor
raw json: {"data":[{"id":"ses_f03b4c860ffe4MwPYzI0oUul3i",…,"title":"E2ECHK5554v98-b",…}]}
```

`/api/session` 返回的是 **分页信封** `{data:[…], cursor:…}`，会话数组在 `.data` 里；对信封本身做 `Where-Object {$_.title …}` 自然命中 0 条。这与 v97 报告里同类命令能列出结果的情况不同（当时返回的是裸数组），属**后端 2.0.18 的返回形状变化**，不影响判定。

补两条等价取证（同一 header、同一 URL）：

```powershell
# (a) 拆信封
(Invoke-RestMethod -Uri 'http://127.0.0.1:4095/api/session' -Headers $h).data |
  Where-Object { $_.title -like 'E2ECHK5554v98*' } | ForEach-Object { $_.id + ' | ' + $_.title }
```
```
ses_f03b4c860ffe4MwPYzI0oUul3i | E2ECHK5554v98-b
ses_f03b658beffea2AB3xaQ2wiC3W | E2ECHK5554v98-search-01
```

```powershell
# (b) 按已知 id 直取（不依赖列表形状）
foreach($sid in @('ses_f03b658beffea2AB3xaQ2wiC3W','ses_f03b4c860ffe4MwPYzI0oUul3i')){
  (Invoke-RestMethod -Uri "http://127.0.0.1:4095/api/session/$sid" -Headers $h).data.title }
```
```
E2ECHK5554v98-search-01
E2ECHK5554v98-b
```

**污染断言**（对 `.data` 全量做匹配）：

```powershell
(Invoke-RestMethod -Uri 'http://127.0.0.1:4095/api/session' -Headers $h).data |
  Where-Object { $_.title -like 'E2ECHK5554v98*' -and $_.title -match 'pathlib|zeta|Find' }
```
```
（无输出 → 无任何标题被搜索输入污染）
```

✅ **PASS**。两个自有会话标题与创建时**逐字一致**（`E2ECHK5554v98-search-01` / `E2ECHK5554v98-b`），未出现 `pathlib` / `the` / `zeta` / `Find…` 等搜索输入内容。修复前「搜索框内容被提交成标题」的症状**未复现**。

---

## 6. 证据文件清单

全部位于 `research/v97_verify/5554_v98/`（相对本报告同目录）：

| 文件 | 对应检查项 |
|---|---|
| `v98_000_launch.xml` / `.png` | 环境：Home 首屏 |
| `v98_001_home.xml`、`v98_003_home2.xml` | 定位自有会话 |
| `v98_002_open.xml` / `.png` | 长标题会话 header 读数 |
| `v98_004_short_open.xml` / `.png` | **热区判定基准**：短标题 node 346px + 墨迹像素扫描 |
| `v98_005_tap_title_text.xml` | **1①** 点文字 → EditText focused=true |
| `v98_006_esc_exit_editor.xml` | 1① 退出（ESC×2，未提交） |
| `v98_007_tap_blank.xml` / `.png` | **1②** 点空白 → 编辑器未开 |
| `v98_008_search_open.xml` / `.png` | **1③** 点 🔍 → 搜索条 + ✕ |
| `v98_009_after_close.xml` | 提前复核一次关闭复位 |
| `v98_010_typed.xml`、`v98_011_composer.xml` | composer 位置重新解析（首点空） |
| `v98_012_composer_typed.xml` | 输入 `pathlib the zeta ` |
| `v98_013_sent.xml` / `.png` | 消息入时间线 |
| `v98_014_searchbar.xml` | 搜索框与 ✕ 的真实 bounds |
| `v98_015_search_pathlib.xml` / `.png` | **2-a** `pathlib` → `1/3` |
| `v98_016_search_the.xml` / `.png` | **2-b** `the` → `1/2`（重算） |
| `v98_017_search_pathlib2.xml` / `.png` | **2-c** 改回 `pathlib` → `1/3`（一致） |
| `v98_018_search_closed.xml` / `.png` | **3** 关闭搜索 → 全面复位 |

---

## 7. 未闭环 / 越界声明

1. **`/api/session` 返回形状变化**：派单给定的 curl（`Where-Object {$_.title …}` 打信封）在 2.0.18 上恒为空。已用 `.data` 与按 id 直取两条等价路径补证。**建议下一轮所有轮次统一改用 `.data`** 或 `GET /api/session/:id`。
2. **未覆盖**：窄窗/长标题下的热区夹取与省略号（v49 BUG-1 路径）本轮只在长标题会话上**间接**观察到 node 满行 418px，未构造超长标题做专项夹取断言。
3. **未覆盖**：搜索条的上下条跳转（`TimelineSearch.moveBy()`）与「无命中」态（计数 `0/0`）未单独取证——本轮时间线内容较少，`1/N` 的分母未变时无法区分 `moveBy` 的边界行为。
4. **未覆盖**：`Session summary` 在搜索态被隐藏属既定取舍（已如实记录），未验证上游是否有可达替代路径。
5. **纪律自证**：全程未跑 gradle、未装机、未杀进程、未触碰 127.0.0.1:5555；**未按 BACK(keyevent 4)**；设置/表单弹层内未用 ESC(111)，仅在主视图对标题编辑器用了两次 ESC 取消；截图全部 `screencap -p` + `adb pull`（无 exec-out）；共享后端只创建/操作了 `E2ECHK5554v98*` 两条自有会话。
