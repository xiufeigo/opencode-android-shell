# D8 真机复验 · persistent session terminal 输入回显（emulator-5562 / Small_Desktop）

- 执行 Agent：真机复验测试员（D8）
- 设备：**`emulator-5562`**，AVD 名 **Small_Desktop** ✅
- 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
- 缺陷来源：`research/v97_verify/5556_v98/REPORT_SWEEPC2.md` §D8
- 修复依据：`research/v97_fix/TERMINAL_INPUT_FIX.md`（`PersistentPtyClient.restoreScreen()` session 分支）
- 自建会话前缀：**`E2ECHK5562d8`**
- 被测构建：Lead 22:27 闸门构建，`lastUpdateTime=2026-10-02 14:27:50`
- 纪律：不跑 gradle / 不装机 / 不杀进程 / 只动 5562；未点任何 Delete；弹层内未按 ESC(111)；**未按 BACK(4)**；截图仅 `screencap -p` + `adb pull`；空格用 `keyevent 62`、≤8 字符分段；坐标全部运行期从 dump 解析；共享后端只动 `E2ECHK5562d8*`

> **总体判定：PASS。D8 已修复 —— persistent session terminal 输入回显恢复正常，对照路径未被破坏。**
> 本轮 3 条判据全绿：对照 ✅ / persistent 3/3 轮 ✅ / 切 tab 后再输入 ✅。

---

## 1. 环境读数

| 项 | 读数 | 判定 |
|---|---|---|
| 设备在线 | `adb devices` → `emulator-5562 device`（同机 5554/5556/5558/5560/5564/5590/5600/5602 **全程未触碰**；`127.0.0.1:5555` 未接触） | ✅ |
| AVD 名 | `emu avd name` = **Small_Desktop** | ✅ 符合要求 |
| 屏幕 | `Physical size: 1366x768`（**无 override**） | ✅ 符合要求 |
| 密度 | `Physical density: 160` | ✅ 符合要求 |
| APK 闸门构建 | `versionName=1.4.0` `versionCode=26` **`lastUpdateTime=2026-10-02 14:27:50`** | ✅ 与任务书 22:27 闸门一致 |
| `reverse --list` | 开局即有：`host-19 tcp:4095 tcp:4095` / `host-19 tcp:2222 tcp:22` | ✅ 非空，**无需重挂** |
| 软键盘 | `show_ime_with_hard_keyboard` = **1** | ✅ 符合要求，终态未改 |
| 后端 | `GET http://127.0.0.1:4095/api/session` → **200** | ✅ |
| 启动 Activity | `cmd package resolve-activity` → **`com.xiufeigo.opencodeshell/.OcActivity`** | ✅ 与 D7 一致（`.MainActivity` 未导出） |

**未修改任何设备设置**（`wm size` / `wm density` / `show_ime_with_hard_keyboard` 全程未动，无 overlay 峰值）。

### 1.1 方法论改进（沿用并强化）

- `d8_cap.ps1` 带 **dump 重试助手**（`<=500` 字节自动重跑，最多 3 次）：本轮 **24 次捕获全部首跑成功**（`tries=1`），未复现 0 字节问题。
- `d8_nodes.ps1` **同时匹配 `text="…"` 与 `text='…'`**（D9 陷阱）—— 本轮 `Running · bash · pid 24207` 就是靠这一条读到的。
- 终端正文**不进 a11y 树**（终端体节点 `class="android.view.View"`，无文本子节点），**dump 字节数在输入前后完全不变**（20,804 B → 20,804 B → 22,808 B 全程恒定，变化仅来自面板展开/收起）。→ **回显断言只能靠截图像素读数**，本报告所有终端文本均从 PNG 逐字读出。

---

## 2. 逐条判定

### 条目 1（对照 / 回归护栏）：桌面停靠临时终端 — **PASS** ✅

| 步骤 | 命令 / 坐标（运行期从 dump 解析） | 读数 |
|---|---|---|
| 打开终端面板 | `input tap 1343 216`（`Open terminal` `[1320,192][1366,240]`） | 面板展开，含既有 `Terminal 11`（**非我建，未动**） |
| 新建临时终端 | `input tap 1344 474`（`New terminal` `[1322,450][1366,498]`） | 新增 **`Terminal 12`** tab（我建） |
| 聚焦终端体 | `input tap 400 560` | 块状光标 |
| 输入 | `input text echo` → `keyevent 62` → `input text E2ECHK5562d8` → `keyevent 66` | 见下方终端正文 |

**终端正文原文**（`d8_011_tempecho.png`）：

```
[xiufeigo@HomeMINISFORUM opencode-android-shell]$ echo E2ECHK5562d8
E2ECHK5562d8
[xiufeigo@HomeMINISFORUM opencode-android-shell]$ █
```

- **判定 PASS**：`PtyClient` 路径回显 + 输出均正常，**修复未破坏既有 PASS 路径**。

---

### 条目 2（主判据）：persistent session terminal — **PASS** ✅（3/3 轮）

| 步骤 | 命令 / 坐标 | 读数 |
|---|---|---|
| 打开 `Session terminals` 面板 | `input tap 1262 474`（`[1242,450][1282,498]`） | 空态 **`No session terminals`** `[14,552][145,568]` —— **干净基线** |
| 新建会话终端 | `input tap 1342 522`（`New session terminal` `[1318,498][1366,546]`） | 新增 tab **`Terminal`**（**无编号**，与临时 `Terminal N` 命名不同，`[396,456][574,492]`） |
| 第 1 轮 | `input text echo` → `62` → `E2ECHK5562d8r1` → `66` | **回显 ✅**（`d8_014_sesstecho_r1.png`） |
| 第 2 轮 | `…E2ECHK5562d8r2` → `66` | **回显 ✅** |
| 第 3 轮 | `…E2ECHK5562d8r3` → `66` | **回显 ✅**（`d8_015_sesstecho_r23.png`） |

**终端正文原文**（`d8_015_sesstecho_r23.png`，连打 3 轮后）：

```
[xiufeigo@HomeMINISFORUM opencode-android-shell]$ echo E2ECHK5562d8r1
E2ECHK5562d8r1
[xiufeigo@HomeMINISFORUM opencode-android-shell]$ echo E2ECHK5562d8r2
E2ECHK5562d8r2
[xiufeigo@HomeMINISFORUM opencode-android-shell]$ echo E2ECHK5562d8r3
E2ECHK5562d8r3
[xiufeigo@HomeMINISFORUM opencode-android-shell]$
```

- **判定 PASS**：与 D8 原始症状（「连打 3 轮零回显」）**完全相反**。3/3 轮，**非偶发**。
- **强判定（排除本地回显）**：`Session terminals` 列表读数（`d8_018_sesstermlist.xml`）：

  ```
  T[xiufeigo@HomeMINISFORUM:/mnt/c/sensorsdata/main/program/opencode-android-shell] B[[14,554][570,570]]
  T[Running · bash · pid 24207]                                              B[[14,572][157,586]]
  ```

  → 键盘确实抵达了**服务端真实 PTY 进程 `pid 24207`**（而非终端视图本地重绘）。

---

### 条目 3（切 tab 后再输入） — **PASS** ✅

双向切换，两条路径各输入一次：

| 步骤 | 命令 | 读数 |
|---|---|---|
| 切到**临时** `Terminal 12` | `input tap 307 474`（`[218,456][396,492]`）+ `400 560` | 输入 `E2ECHK5562d8t12` → **回显 ✅** |
| 切回**persistent** `Terminal` | `input tap 485 474`（`[396,456][574,492]`）+ `400 560` | 输入 `E2ECHK5562d8sw1` → **回显 ✅** |

**persistent 终端切回后的终端正文原文**（`d8_017_switch_sess.png`）：

```
[xiufeigo@HomeMINISFORUM opencode-android-shell]$ echo E2ECHK5562d8r1
E2ECHK5562d8r1
[xiufeigo@HomeMINISFORUM opencode-android-shell]$ echo E2ECHK5562d8r2
E2ECHK5562d8r2
[xiufeigo@HomeMINISFORUM opencode-android-shell]$ echo E2ECHK5562d8r3
E2ECHK5562d8r3
[xiufeigo@HomeMINISFORUM opencode-android-shell]$ echo E2ECHK5562d8sw1
E2ECHK5562d8sw1
[xiufeigo@HomeMINISFORUM opencode-android-shell]$
```

**临时终端切过去后的终端正文原文**（`d8_016_switch_temp.png`）：

```
[xiufeigo@HomeMINISFORUM opencode-android-shell]$ echo E2ECHK5562d8
E2ECHK5562d8
[xiufeigo@HomeMINISFORUM opencode-android-shell]$ echo E2ECHK5562d8t12
E2ECHK5562d8t12
[xiufeigo@HomeMINISFORUM opencode-android-shell]$ █
```

- **判定 PASS**，且附带证明 **`runtime.io` 未串**：切到临时终端时它只有自己的 2 行，切回 persistent 时它只有自己的 5 行 —— 两次输入各自落在正确终端内，无交叉污染。

---

### 条目 4（反例取证） — **不适用（未触发）** ✅

主判据已 PASS，无需进入「卡在哪一环」的取证分支。故本轮**未采集** `focused` 属性 / `dumpsys input_method` 的 `mInputShown` / `logcat` 的 `PtyClient`·`Terminal` 关键字。

- 附带读数：persistent 终端输入后 dump 中**唯一** `focused="true"` 的是会话标题 `EditText`（`[98,111][1188,159]`，重命名残留焦点），终端体本身不暴露 `focused` 属性 —— 印证 D8 报告「终端体是否 focused 无法用 XML 断言」，不影响本轮 PASS 判定。

---

## 3. 终态与清理

### 3.1 设备设置（仅 emulator-5562）

| 设置 | 初值 | 终值 | 状态 |
|---|---|---|---|
| `wm size` | `Physical size: 1366x768` | 同左（**全程未动**） | ✅ 无需还原 |
| `wm density` | `Physical density: 160` | 同左（**全程未动**） | ✅ 无需还原 |
| `show_ime_with_hard_keyboard` | `1` | `1` | ✅ 原样 |
| `reverse` | `4095` + `2222` | 同左 | ✅ 原样 |

未执行：`pm clear`、`pm grant`、`am force-stop`、杀进程、**BACK(4)**、**ESC(111)**、未点任何 Delete。

### 3.2 自建对象清理

| 对象 | 处置 |
|---|---|
| 临时终端 `Terminal 12` | ✅ 已关（`input tap 331 474`） |
| persistent 会话终端 tab | ✅ 已关（`input tap 331 474`） |
| 会话 `E2ECHK5562d8` tab | ✅ 已关（`input tap 317 54`） |
| 终端面板 | ✅ 已收起 |
| 既有 `Terminal 11` | ✅ **未触碰**（非我建） |
| 既有会话 `E2ECHK5562d-01` | ✅ **未触碰**（非我建） |

**最终 dump（`d8_025_final.xml`）** 仅剩 `E2ECHK5562d-01`，无任何我建对象残留。

### 3.3 `/sdcard` 残留 — **无** ✅

```
ls /sdcard/ | grep -c '^d8_'          -> 0
find /sdcard -name 'd8_*'    | wc -l  -> 0
find /sdcard -name '*5562d8*' | wc -l  -> 0
```

（`/sdcard` 根目录与 `/sdcard/Download` 存在**其他 agent 的**历史产物 `v97b_*` / `E2ECHK5556-*` / `E2ECHK5562-01b*` 等，**非本轮产物，未清理**。）

---

## 4. 新发现（未改任何代码）

### D8-N1（低）`Shut down persistent terminals` 点击**无可观测效果**（疑似未接线）
- **现象**：关闭 persistent 会话终端 tab 后，`Session terminals` 列表仍显示 `Running · bash · pid 24207`。点击红色 `Shut down persistent terminals` **3 次**（`d8_021` / `d8_024`，其中一次已按 dump 里 `clickable="true"` 的整行祖先 `[0,677][1366,725]` 精确落点），列表**始终无变化**，PTY 仍在运行。
- **证据**：`d8_021_shutdown.xml`、`d8_022_shutdown2.xml`、`d8_024_shutdown3.xml` 三份 dump 的 `pid 24207` 行逐字相同。
- **影响**：persistent 终端**关不掉 tab ≠ 关掉进程**，用户以为已结束，实际 PTY 常驻；无确认弹窗、无任何反馈。**不属本次修复范围**（`TERMINAL_INPUT_FIX.md` 未触碰该入口），建议单独立项。
- **诚实标注**：本条**未能**排除「操作已生效但列表为陈旧缓存」—— 我没有对 daemon 做 API 侧独立读数来交叉验证 `pid 24207` 是否仍存活。判定为「UI 无可观测效果」，非「服务端确未关闭」。

### D8-N2（低，方法论）`Session terminals` 列表行**可点击且会重新打开终端**
- 点列表行（`[0,544][1366,596]`，`class="android.widget.Button"`）并非选中，而是**以新 tab 重新打开**该 persistent 终端（本轮 `pid 24207` 被重开为 `Terminal 14`）。
- 排查「关不掉」时容易因此误判为「新建了一个新终端」。`Shut down persistent terminals` 与列表行**分处不同区域**，但视觉上同为红色/行式元素，易混淆。
- 证据：`d8_022_shutdown2.xml`（多出 `Terminal 14` tab）→ `d8_023_reopenpanel.xml`（关闭后复原）。

---

## 5. 证据清单

`research/v97_verify/5562_v98/`（本轮前缀 `d8_`，与上轮 `d_0*` 无冲突）

| 文件 | 内容 |
|---|---|
| `d8_001_launch` / `d8_002_newsess` / `d8_003_created` | 启动、新建会话 |
| `d8_004_menu` / `d8_005_renamefield` / `d8_006_renamed` / `d8_007_renameok` | 重命名为 `E2ECHK5562d8` |
| `d8_008_termpanel` / `d8_009_newtemp` | 终端面板、`Terminal 12` |
| **`d8_011_tempecho.png`** | **对照：临时终端回显 ✅** |
| `d8_012_sesstermpanel` | `Session terminals` 空态基线 |
| `d8_013_newsessterm` | persistent 会话终端创建 |
| **`d8_014_sesstecho_r1.png`** | **persistent 第 1 轮回显 ✅** |
| **`d8_015_sesstecho_r23.png`** | **persistent 3/3 轮回显 ✅** |
| `d8_018_sesstermlist.xml` | `Running · bash · pid 24207` 强判定 |
| **`d8_016_switch_temp.png`** | 切到临时终端仍有回显 ✅ |
| **`d8_017_switch_sess.png`** | 切回 persistent 仍有回显 ✅ |
| `d8_019_closedt12` / `d8_020_closedsess` / `d8_023_reopenpanel` / `d8_025_final` | 清理过程与终态 |
| `d8_021_shutdown` / `d8_024_shutdown3` / `d8_022_shutdown2` | D8-N1 / D8-N2 取证 |
| `_tools/d8_cap.ps1` · `d8_nodes.ps1` · `d8_type.ps1` | 本轮助手（dump 重试 / 双引号解析 / ≤8 分段输入） |

---

## 6. 未闭环

| 项 | 状态 | 原因 |
|---|---|---|
| 条目 4（可选）**持久性**：杀进程重进同一会话后 session terminal 能否恢复屏幕**并可输入** | **未做** | 纪律**禁止杀进程**（`不杀进程`）。此项需由允许杀进程的复验轮次执行；本次 `TERMINAL_INPUT_FIX.md` §5 ④ 标注为「可选」，未纳入必做 |
| `pid 24207` 服务端是否**真的**存活 | **未独立验证** | 只做了 UI 侧读数（列表行未消失）。若需坐实，应查 daemon 侧 PTY 列表 API（见 D8-N1 诚实标注） |
| `dumpsys input_method` 的 `mInputShown` / `logcat` 关键字取证 | **未采集** | 主判据 PASS，不进入反例取证分支（条目 4） |
| D8-N1 `Shut down persistent terminals` 根因归属 | **待开发确认** | 需看该入口的 onClick 接线（`ui/terminal/**`）才能定位到具体行 |
