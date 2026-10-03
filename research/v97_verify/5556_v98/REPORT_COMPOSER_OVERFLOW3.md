# REPORT — COMPOSER OVERFLOW 真机复验 #3（v105）

- **设备**：emulator-5556（Medium_Desktop）｜1366x768@160dpi
- **包名**：`com.xiufeigo.opencodeshell`
- **执行人**：v105 真机复验（按 Lead 裁定"两条算式"）
- **判定口径来源**：`REPORT_OVERFLOW_DIAG.md` 结论①（**仅两条**：`unionBottom ≤ hostBottom`、`unionPx ≤ band`）
- **结论**：**FAIL**（两档均不成立；且属"真机仍报 EditText 48"的 §9 处置预案场景）

---

## 0. 构建 / 归属核对

| 项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| APK MD5 | `73eae681332b283a20e716d903781366` | `73EAE681332B283A20E716D903781366` | ✅ 逐位一致 |
| 设备 `lastUpdateTime` | `2026-10-03 11:32:40` | `2026-10-03 11:32:40` | ✅ 一致 |
| versionCode / versionName | — | 26 / 1.4.0 | — |
| APK 文件 mtime | — | 2026/10/3 19:32:28（=设备 11:32:28 UTC，+12s 安装） | ✅ 自洽 |

**归属核对**：每步交互前 `mCurrentFocus` 均为 `Window{… u0 com.xiufeigo.opencodeshell/…OcActivity}`；`mFocusedApp` 同为本 app。全程未出现他 app 窗口。**通过**。

- 说明：APK 本地文件 mtime 为 CST 19:32:28，设备 `lastUpdateTime` 为 11:32:40（设备时区 GMT，差 8h + 安装耗时 12s），两者自洽。

---

## 1. 主判据（两档）

**坐标系说明**：uiautomator 树在 Compose 下是**扁平化**的——composer 宿主盒没有独立 a11y 节点。按 Lead 裁定"pane 底沿改用 `imeTop` 断言"，本报告取 **hostBottom = imeTop**（app 内容区底沿），**band = 44px**（`TOOL_ROW_HEIGHT.roundToPx()`，`min(44, maxHeight)`）。
**`union` = composer 子节点并集，明确排除 `Open terminal` 节点**（规则要求）。
**`unionPx` = max(editorPx, toolRowPx)**（DIAG 口径；同时给出外接盒全高供对照）。

> 两档均已先 `wm size` + `wm density` 再**重新 dump**、**重新解析坐标**（切档坐标未沿用）。每档读高度前均断言 `mInputShown=true` 并记录。

### 1.1 档 A：1366x768

- `wm size 1366x768` / `wm density 160` → Override 生效
- IME：`mInputShown=true`，`mIsInputViewShown=true`；IME inset `bottom=474` → **imeTop = 768 − 474 = 294**
- composer 宿主盒（按 readable 口径，底沿=imeTop）：**[0,202][1366,294]，h=92**（与期望 readable 92 自洽；扁平树未把 92 作为独立节点暴露，768 档 readable 为推导值）
- **宿主盒底 hostBottom = 294**

**子节点（排除 `Open terminal`）：**

| 节点 | bounds | h | 备注 |
|---|---|---|---|
| `EditText` | `[28,208][1338,256]` | **48** | ✅ **读数=48，非 44** |
| `Add`(Button) | `[10,248][52,296]` | 48 | 工具行 |
| `Model`(View) | `[52,248][215,296]` | 48 | 工具行 |
| `Stop`(View) | `[1308,248][1356,296]` | 48 | 工具行 |
| ~~`Open terminal`~~ | `[1320,192][1366,240]` | 48 | **排除**（规则） |

- **并集 bounds（排除 Open terminal）**：`[10,208][1356,296]`（外接全高 88；max-row **unionPx = max(48,48) = 48**）
- **band = 44**

**两条判定：**

| 算式 | 计算 | 结果 |
|---|---|---|
| ① `unionBottom ≤ hostBottom` | 296 − 294 = **+2** | ❌ **FALSE** |
| ② `unionPx ≤ band` | 48 − 44 = **+4** | ❌ **FALSE** |

**→ 768 档 FAIL**（两条皆假）。越界节点：工具行 `Add`/`Model`/`Stop` 底沿 **296 > 宿主底 294（+2）**；`unionPx=48>band=44` 由 `EditText h=48` 驱动。

### 1.2 档 B：1366x600

- `wm size 1366x600` / `wm density 160`
- IME：`mInputShown=true`；IME inset `bottom=387` → **imeTop = 600 − 387 = 213**（与 v104 基线 imeTop=213 一致）
- composer 宿主盒：`[0,162][1366,213]`，**h=51**（readable=51，底沿=imeTop=213）
- **宿主盒底 hostBottom = 213**

**子节点（排除 `Open terminal`）：**

| 节点 | bounds | h | 备注 |
|---|---|---|---|
| `EditText` | `[24,169][405,217]` | **48** | ✅ **读数=48，非 44** |
| `Add`(Button) | `[399,170][441,218]` | 48 | 工具行 |
| `Model`(View) | `[441,170][604,218]` | 48 | 工具行 |
| `Stop`(View) | `[1316,170][1364,218]` | 48 | 工具行 |
| ~~`Open terminal`~~ | `[1320,184][1366,232]` | 48 | **排除**（规则） |

- **并集 bounds（排除 Open terminal）**：`[24,169][1364,218]`（外接全高 49；max-row **unionPx = 48**）
- **band = 44**

**两条判定：**

| 算式 | 计算 | 结果 |
|---|---|---|
| ① `unionBottom ≤ hostBottom` | 218 − 213 = **+5** | ❌ **FALSE** |
| ② `unionPx ≤ band` | 48 − 44 = **+4** | ❌ **FALSE** |

**→ 600 档 FAIL**（两条皆假）。**与 v104 基线逐位相同**（`unionBottom 218 > hostBottom 213`＝+5、`unionPx 48 > band 44`）。越界节点：工具行 `Add`/`Model`/`Stop` 底沿 **218 > 宿主底 213（+5）**。

### 1.3 主判据汇总表

| 档 | 宿主盒 bounds | 并集 bounds（排除 Open terminal） | band | unionBottom−hostBottom | unionPx−band | mInputShown | readable | 判定 |
|---|---|---|---|---|---|---|---|---|
| 1366x768 | `[0,202][1366,294]`(h92) | `[10,208][1356,296]` | 44 | **+2** ❌ | **+4** ❌ | true | 92(推导) | **FAIL** |
| 1366x600 | `[0,162][1366,213]`(h51) | `[24,169][1364,218]` | 44 | **+5** ❌ | **+4** ❌ | true | 51 | **FAIL** |

**任一档任一算式为假 ⇒ FAIL。两档均 FAIL ⇒ 总体 FAIL。**

### 1.4 「600 档是否 h=44」专项确认（v105 期望值）

- **实测 `EditText` h = 48**（`[24,169][405,217]`），**不是 44**。
- 工具行（`Add`/`Model`/`Stop`）亦为 **h=48**。
- **v105 报告 §7.5 的推导期望值「600 档 h=44」在真机上未达成。** 方案 B（`LocalMinimumInteractiveComponentSize` 受控覆盖最小触摸靶）**未把子节点压到 44**。

---

## 2. §9 处置预案对照（真机仍报 EditText 48 —— 成立）

**触发条件**：「真机仍报 `EditText` 48（即子组合忽略约束）」→ **已触发**（两档 `EditText` 均 48）。

**实际读数（取证）：**

| 档 | `EditText` 实测 | 工具行实测 | band | 结论 |
|---|---|---|---|---|
| 600 | `[24,169][405,217]` h=**48** | Add/Model/Stop h=48 | 44 | 子组合忽略 band 约束 |
| 768 | `[28,208][1338,256]` h=**48** | Add/Model/Stop h=48 | 44 | 子组合忽略 band 约束 |

**与 §9 预案的差异**：§9 预案预设 material3 强制的 48dp 最小触摸靶在真机上仍生效，需按预案取证并判 FAIL。**本次实测与预案一致**：material3 的 48dp 强制在两档均压过 band=44，`LocalMinimumInteractiveComponentSize` 覆盖**未生效**（`EditText` 与工具行仍 48）。据此 **判 FAIL**。

**根因推断**：方案 B 的受控覆盖未真正把子组合的最小触摸靶从 material3 默认 48dp 降到 44dp，或覆盖未覆盖到 `EditText`/工具行这条实际渲染路径。

---

## 3. 回归（三项）

### 3.1 回归①：缩窗往返 2 轮（768→600→768）— ✅ 不回退

序列：`768-R1 → 600-R1 → 768-R2 → 600-R2 → 768-final`（每档前 `wm size`）。

| 检查 | 结果 |
|---|---|
| `pidof` | 全程 **1856**（未重启；首轮 `wm` 切换前后的 pid 恒定） |
| `mCurrentFocus` | 每档均 `Window{… com.xiufeigo.opencodeshell/…OcActivity}` |
| `logcat FATAL|IllegalArgumentException|maxHeight` | **按 app pid 过滤 = 空** |

> 说明：整轮 `logcat` 命中的 `maxHeight` 行全部来自 **Gboard `SoftKeyboardView`（pid 3629，`com.google.android.inputmethod.latin`）**的 `setMaxHeight():731 Set max keyboard height:483/315`，**非本 app**。以 `--pid=1856` 过滤后为空。

**结论：✅ 不回退。**

### 3.2 回归②：时间线盒 140px / readable — ✅ 基本不回退（768 readable 为推导）

| 项 | 期望 | 实测 | 判定 |
|---|---|---|---|
| 768 时间线盒 | 140px | `[0,162][1366,302]` **h=140** | ✅ |
| 600 readable | 51 | `[0,162][1366,213]` **h=51**（独立节点） | ✅ |
| 768 readable | 92 | 扁平树未暴露独立 92 节点；按宿主盒/编辑区几何**推导 92**（宿主底=imeTop=294，宿主顶=294−92=202，与 `EditText 208..256` 内嵌自洽） | ⚠️ 推导值 |

> 768 readable 无法在扁平 a11y 树中作为单节点直接读出（600 档的 51 节点可直读）。**据实报告**：768 readable=92 为推导，与期望一致，但非直接测量。

### 3.3 回归③：队列两格（Queue chip / Steer / ×）— 几何 ✅，深度档 ⚠️ 部分未闭环

**Queue chip 状态机**：点 `Queue`（composer 非空）→ 翻成 `Steer`，队列行显示 `Queued`；每次再添加后 chip 回到 `Queue`。

**测得几何（1 条 queued 时）：**

| 节点 | bounds | 尺寸 | 期望 | 判定 |
|---|---|---|---|---|
| `Steer`(Button) | `[1263,181][1302,224]` | **h=43** clk=true | 43 | ✅ |
| `×`= `Remove`(Button) | `[1302,180][1350,228]` | **h=48** clk=true | 48 | ✅ |
| clickable | Steer/Remove 均 `clickable=true` | 全 clickable | 全 clickable | ✅ |
| readable | 队列态 composer `EditText[28,224][1338,258] h=34`；队列行 `[18,188][1348,220]` | 92 未单列 | 92 | ⚠️ 未单列 |

**深度档**：
- ① ≥11 条 —— **未成功构建**。Queue chip 每次添加后回落为 `Queue`，可见列表仅呈现队首项，**未能在本次时限内把队列堆到 ≥11 条**。
- ② 2–5 条 —— 添加后可达 2 条（`queue test line 1` + `queued item`），落在 2–5 区间，**几何（Steer 43 / × 48 / 全 clickable）成立**。

**结论：几何不回退 ✅；深度档 ①≥11 未闭环 ⚠️。**

---

## 4. 收尾

- **`wm` reset 复核**：已恢复 **Override size 1366x768 / Override density 160**（`wm size reset` 会清空 override 回到物理 3840x2160/320，故显式重设 `wm size 1366x768` + `wm density 160`）。复核通过。
- **自建会话清理**：会话 `Composer overflow verification v105` 已删除。
  - 流程：`Stop` 停回合 → `Open sessions` → **长按 1500ms（≥1200ms）** 本会话行 → 弹层 `Rename/Delete`。
  - **弹层归属已验**：`dumpsys window windows` 显示 `Window{be15095 … OcActivity}`，`mOwnerUid=10189`、`package=com.xiufeigo.opencodeshell`、`mParentWindow=…OcActivity`、`mLayoutAttached=true` → 属本 app，方点 `Delete`。
  - 二次确认弹层「Delete session?」→ `Delete` 后复核：v105 会话命中 **0**，删除生效。
  - 未触碰他轮会话（`E2ECHK5556u1h`/`u1f` 等）。
- **`/sdcard` 无残留**：已删本次产物 `w.xml / w2.xml / i06.png / i10.png / _q.xml`；复核剩余 `.xml/.png` 均为**他轮旧残留**（uid `u0_a183`、日期 09-24/10-02/10-03 凌晨），非本次产生。
- **未跑 gradle / 未装包 / 未杀进程**（仅对本 app 用过 UI 内 `Stop`；未按 BACK(4)、弹层内未用 ESC(111)）。

---

## 5. 总判定

| 判据 | 结果 |
|---|---|
| ① 768 `unionBottom−hostBottom` | **+2**（296 vs 294）❌ |
| ② 768 `unionPx−band` | **+4**（48 vs 44）❌ |
| ① 600 `unionBottom−hostBottom` | **+5**（218 vs 213）❌ |
| ② 600 `unionPx−band` | **+4**（48 vs 44）❌ |
| 600 档 `h=44`？ | **否（=48）** |
| 回归① 缩窗往返 | ✅ 不回退 |
| 回归② 140px / readable | ✅ 140 / 51；768 readable=92 为推导 |
| 回归③ 队列几何 | ✅ Steer43/×48/全clickable；深度①≥11 未闭环 |

> **任一档任一算式为假 ⇒ FAIL。两档四条全假 ⇒ 总判定 FAIL。**

**未闭环 / 待跟进：**
1. 方案 B（`LocalMinimumInteractiveComponentSize` 覆盖）**未把子组合压到 44dp**，真机仍报 `EditText`/工具行 48（§9 场景已取证）。需换真正能压过 material3 最小触摸靶的方案（或对该路径单独覆盖）。
2. 768 readable=92 为推导值，扁平 a11y 树未直接暴露该节点。
3. 回归③ 深度档 ①≥11 未在时限内构建；②2–5 几何成立。

---

_报告生成：v105 真机复验（emulator-5556，mcode/adb）_
