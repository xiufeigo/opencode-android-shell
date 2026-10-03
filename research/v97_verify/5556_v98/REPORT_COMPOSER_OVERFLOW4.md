# REPORT_COMPOSER_OVERFLOW4 —— v106 方案 A 真机复验（emulator-5556）

- 复验人：mavis（真机复验测试员）
- 日期：2026-10-03
- 设备：**emulator-5556**（Medium_Desktop，1366x768@160dpi）
- 被验构建：**v106 方案 A**（`COMPOSER_OVERFLOW_FIX4.md`）
- 权威口径：`REPORT_OVERFLOW_DIAG.md` §5.2 —— **仅两条**：`unionBottom ≤ hostBottom`、`unionPx ≤ band`
- 基线（v105，`REPORT_COMPOSER_OVERFLOW3.md`）：768 = **+2 / +4**；600 = **+5 / +4**

> ## 状态：❌ **FAIL**（两档中 600 档两条**都**未同时成立；768 档两条皆假）
> ## **② `unionPx ≤ band` 在 600 档已被 v106 修好**（+4 → **0**，取等）
> ## **① `unionBottom ≤ hostBottom` 两档都原封不动**（768 +2、600 +5）
> ## **子节点高两档都是 48**（不是 44）⇒ 不触发方案 A 的已知边界，**hostPx = 48** 两档一致
> ## **768 时间线盒 140 / readable 92 —— 两者都未变，v106 的"不变"声明属实** ✅

---

## 1. 构建核对与设备归属

### 1.1 MD5 —— **三处逐位一致**（本地 / 设备侧 / 拉回本地）

| 来源 | MD5 |
|---|---|
| 仓库 `app/build/outputs/apk/debug/app-debug.apk` | `87b27b42e0346e835886b366c3e43f3a` |
| 设备 `md5sum /data/app/~~kLCIwHMYNfMSIGd61ZkWaQ==/com.xiufeigo.opencodeshell-.../base.apk` | `87b27b42e0346e835886b366c3e43f3a` |
| 从设备 `adb pull` 回来再算 | `87b27b42e0346e835886b366c3e43f3a` |

**⇒ 与指定 md5 `87b27b42e0346e835886b366c3e43f3a` 逐位一致 ✅（不是 BLOCKED）**

- `lastUpdateTime = 2026-10-03 12:06:23` ✅ 与指定值逐位一致
- `versionName=1.4.0` / `versionCode=26` / `firstInstallTime=2026-09-23 13:58:18`
- **未装机、未跑 gradle**（纪律遵守）；本轮只做只读测量 + `wm` 覆写。

### 1.2 源码侧确认 v106 确实在库里（排除"装了旧包"这一可能）

| 锚点 | 实测 |
|---|---|
| `Composer.kt:2655` | `private val BAND_HEIGHT: Dp = SessionPaneLayout.composerBandHeightDp(TOOL_ROW_HEIGHT)` |
| `Composer.kt:2261` | `val band = minOf(BAND_HEIGHT.roundToPx(), constraints.maxHeight)` |
| `Composer.kt:2145` | 一行臂 `Modifier.fillMaxWidth().height(BAND_HEIGHT)` |
| `Composer.kt:2150` | 堆叠臂 `Modifier.fillMaxWidth().height(TOOL_ROW_HEIGHT)` **逐字未动** |
| `SessionPaneLayout.kt` | `CHROME_MIN_HEIGHT=44` / `TIMELINE_MIN_CONTENT=64` / `COMPOSER_MINIMAL_HEIGHT=56` / `COMPOSER_COMPACT_HEIGHT=96` |

### 1.3 设备归属

- 全程 `mCurrentFocus=Window{ca836d0 u0 com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.OcActivity}`
- app pid **3253**（从启动到收尾**从未变化** ⇒ 无崩溃、无静默重启）
- 每一步交互前均复查 `mCurrentFocus`；唯一一次出现非本 app 窗口是启动前的
  日历 OOBE（`com.google.android.calendar/…WhatsNewFullScreen`），在**任何交互之前**就切换掉了
- 收尾弹层归属见 §6

---

## 2. 主判据：两档逐条判定

**坐标全部运行期从 `uiautomator dump --compressed` 解析**（`parse_5556.ps1`，同时匹配
`text="…"`/`text='…'`，切档后重新解析）。**读高度前均已断言 `mInputShown=true`。**

**形态判定（关键）**：`composerOneRow = isShortWindow || composerMinimal(body)`，
`composerMinimal(body) = body < 56+44+64 = 164 dp`（`SessionPaneLayout.kt:1008,1039`）。

| 档 | 形态 | 判据 | 形态证据 |
|---|---|---|---|
| 1366x768 | **堆叠**（stacked） | body ≈ 250 dp > 164 | EditText **整宽** `[28,208][1338,256]`，工具行在其**下方** |
| 1366x600 | **一行**（one-row） | body ≈ 169 dp < 164 | EditText **收窄** `[24,169][405,217]`，与工具行**并排** |

> ⚠️ **这一点决定了 v106 能被验证到什么程度**：`band`（`BAND_HEIGHT` = 48）**只被一行形态消费**
> （`COMPOSER_OVERFLOW_FIX4.md` §1.5 自陈："堆叠形态…**根本不读 `band`**"）。
> 因此 **768 档根本没有 v106 的那条带来量**，v106 的改动在 768 上**结构上不可见**。

### 2.1 1366x768（IME 474px，`imeTop = 294`）

`mInputShown = true` ✅
权威 `imeTop`：`InsetsSource id=3 type=ime frame=[0,294][1366,768]`，`mInsetsHint bottom=474`
⇒ `768 − 474 = 294`。**与 v105 读到的 294 逐位相同。**

| 项 | 值 |
|---|---|
| 宿主盒底 `hostBottom`（= `imeTop`，Lead 裁定口径） | **294** |
| `hostPx` | **48** |
| 时间线盒 / pane | `[0,162][1366,302]` **h=140** |
| 形态 | 堆叠 ⇒ `band` 不被读；堆叠行仍 `TOOL_ROW_HEIGHT` = **44** |
| `EditText` | `[28,208][1338,256]` **h=48** |
| `Add` | `[10,248][52,296]` **h=48** |
| 模型 chip | `[52,248][215,296]` **h=48** |
| Send | `[1308,248][1356,296]` **h=48** |
| `Open terminal`（**排除**） | `[1320,192][1366,240]` h=48 |
| 并集 bounds（排除 Open terminal） | **`[10,208][1356,296]`**（外接全高 88） |
| `unionBottom` | **296** |
| `unionPx = max(editor, toolRow)` | **48** |

| 判据 | 算式 | 结果 |
|---|---|---|
| ① `unionBottom ≤ hostBottom` | 296 − 294 = **+2** | ❌ **FALSE** |
| ② `unionPx ≤ band` | 48 − 44 = **+4** | ❌ **FALSE** |

**→ 768 档 FAIL。** 越界节点：工具行 `Add`/模型 chip/`Send` 底沿 **296 > 宿主底 294（+2）**。
② 的 +4 由 `EditText h=48` 驱动，且**与 v105 逐位相同**。

### 2.2 1366x600（IME 387px，`imeTop = 213`）

`mInputShown = true` ✅
`type=ime frame=[0,213][1366,600]`，`mInsetsHint bottom=387` ⇒ **213**。与 v105 逐位相同。

| 项 | 值 |
|---|---|
| 宿主盒底 `hostBottom`（= `imeTop`） | **213** |
| `hostPx` | **48** |
| 时间线盒 / pane | `[0,162][1366,213]` **h=51** |
| 形态 | **一行** ⇒ `band = BAND_HEIGHT = 48`**（v106 生效点）** |
| `EditText` | `[24,169][405,217]` **h=48** |
| `Add` | `[399,170][441,218]` **h=48** |
| 模型 chip | `[441,170][604,218]` **h=48** |
| Send | `[1316,170][1364,218]` **h=48** |
| `Open terminal`（**排除**） | `[1320,184][1366,232]` h=48 |
| 并集 bounds（排除 Open terminal） | **`[24,169][1364,218]`**（外接全高 49） |
| `unionBottom` | **218** |
| `unionPx` | **48** |

| 判据 | 算式 | 结果 | v105 基线 |
|---|---|---|---|
| ① `unionBottom ≤ hostBottom` | 218 − 213 = **+5** | ❌ **FALSE** | +5（**未变**） |
| ② `unionPx ≤ band` | 48 − 48 = **0** | ✅ **TRUE** | +4（**已修好**） |

**→ 600 档 FAIL（① 假）。** 越界节点：工具行底沿 **218 > 宿主底 213（+5）**，与 v105 同一个 +5。

### 2.3 两档汇总（含子节点高与 hostPx）

| 档 | 形态 | 宿主盒底 | `hostPx` | band | **子节点高** | 并集(排除 Open terminal) | ① `unionBottom−hostBottom` | ② `unionPx−band` | 判定 |
|---|---|---|---|---|---|---|---|---|---|
| 768 | 堆叠 | 294 | **48** | 44 | **48** | `[10,208][1356,296]` | **+2** ❌ | **+4** ❌ | **FAIL** |
| 600 | 一行 | 213 | **48** | **48** | **48** | `[24,169][1364,218]` | **+5** ❌ | **0** ✅ | **FAIL** |

**子节点高 = 48（两档）⇒ 不是 44，方案 A 那条 `minOf(48, maxHeight)` 收窄分支本轮未被触发。**
按要求把 `hostPx` 一并给出：**两档 `hostPx` 均为 48**（`band = min(48, maxHeight) = 48`，
`maxHeight` 未收窄它）。**768 档 44 那个数是堆叠行的 `TOOL_ROW_HEIGHT`，不是被收窄的 band。**

---

## 3. v106 声明的核对：哪些兑现，哪些没有

| v106 声明（`COMPOSER_OVERFLOW_FIX4.md`） | 真机结果 |
|---|---|
| 带本身提到 48dp | ✅ 源码在库里；**但 768 是堆叠形态，`band` 不被读 ⇒ 768 档无 48 带可量** |
| 子节点仍应是 48（一个像素没压） | ✅ **两档都是 48** |
| 越界 4px 从源头消失（`unionPx ≤ band`） | ✅ **在 600 档兑现：+4 → 0（取等）**；768 档因不读 `band` 而不适用 |
| 768 `readable` 仍应 92 | ✅ **实测/推导 92，未变** |
| 保守口径 88 亦 ≥64 | ✅ 88 ≥ 64 成立（且实测就是 92，不需要退到保守口径） |
| `unionBottom ≤ hostBottom` 取等 | ❌ **未兑现**：768 仍 +2，600 仍 +5 |

---

## 4. 768 回归：时间线盒与 readable

| 项 | 基线 | v106 声称 | **本轮实测** | 结论 |
|---|---|---|---|---|
| 768 时间线盒 / pane | **140** | 140 不变 | `[0,162][1366,302]` **h=140** | ✅ **未变** |
| 768 `readable` | **92** | 92 不变 | **92**（`= pane 140 − hostPx 48`） | ✅ **未变，≥64（余 28px）** |
| 保守口径 | 88 | ≥64 | 88 ≥ 64（未被需要） | ✅ |
| 600 pane / readable | 51 | 51 不变 | `[0,162][1366,213]` **h=51** | ✅ 未变 |
| 队列态 pane | 140 | 140 恒 | `[0,162][1366,302]` **h=140** | ✅ |
| 队列态 composer `EditText` | h=34 | — | `[28,224][1338,258]` **h=34** | ✅ 与 v105 逐位相同 |

> **`readable` 的性质（如实）**：768 的 92 **仍是推导值**（`140 − 48`），与 v105 一样，
> 扁平 a11y 树没有把 92 暴露成独立节点。600 的 51 是**直读**的独立节点。
> **没有因为 band +4dp 而发生任何变化** —— 符合 v106 声明。

---

## 5. 缩窗往返 2 轮（768→600→768→600→768）

每档切 `wm size` + `wm density` 后**重新 dump、重新解析坐标**。

| 检查点 | `pidof` | `mCurrentFocus` | `mInputShown` | `ime` frame | `logcat`(按 app pid 3253 过滤 `FATAL\|IllegalArgumentException\|maxHeight`) |
|---|---|---|---|---|---|
| R1 → 768 | **3253** | 本 app ✅ | true | `[0,294]` | **CLEAN** |
| R1 → 600 | **3253** | 本 app ✅ | true | `[0,213]` | **CLEAN** |
| R2 → 768 | **3253** | 本 app ✅ | true | `[0,294]` | **CLEAN** |
| R2 → 600 | **3253** | 本 app ✅ | true | `[0,213]` | **CLEAN** |
| R2 → 768（终态） | **3253** | 本 app ✅ | true | `[0,294]` | **CLEAN** |

- **pid 全程 3253** ⇒ 无崩溃、无 Activity 重建
- `FATAL` / `IllegalArgumentException` / `maxHeight` **全部为空** ✅
  （上一轮那个 `maxHeight` 命中来自 Gboard `SoftKeyboardView`；本轮**按 app pid 过滤后为空**，证实了这一点）
- **几何确定性**：两轮往返后，同档的 `pane` / `EditText` / 工具行 bounds **逐位相同**
  （768 三次采集全等；600 三次采集全等）⇒ **无 hysteresis / 无 v57 式振荡** ✅

---

## 6. 队列几何（768）

条件核验：**回合在跑**（运行条 `Build · space-bunny-free` / `Working` ✅）
**+ composer 非空** ✅ ⇒ 点 `Queue` chip（`[1240,248]` 附近，实点 (1282,272)）**排队成功**，
队列条弹出。

| 节点 | bounds | W | **H** | `clickable` | 基线 |
|---|---|---|---|---|---|
| **Steer** | `[1263,181][1302,224]` | 39 | **43** | **true** | 43 |
| **×**（移除） | `[1302,180][1350,228]` | 48 | **48** | **true** | 48 |
| 队列行（可点） | `[22,180][1257,228]` | 1235 | 48 | **true** | — |

- **`Steer` h = 43** ✅ 与基线一致
- **`×` h = 48** ✅ 与基线一致 —— **任务提示"band 变 48 后 `×` 的 h 可能变化"，据实报：`×` 的 h 没有变化，仍 48**
- **全 clickable** ✅（Steer / × / 队列行 均 `clickable=true`）
- 队列态下 pane 仍 **140**、`EditText` **h=34**，与 v105 逐位相同

---

## 7. 收尾

| 项 | 结果 |
|---|---|
| `wm` 显式重设 | ✅ `wm size 1366x768` + `wm density 160`；复核 `Override size: 1366x768` / `Override density: 160`（**未用 `wm size reset`**，override 未被清空） |
| 先 `Stop` | ✅ 先停掉运行中的回合（Stop 按钮 `[1338,192][1366,240]`，实点 (1352,216)） |
| 长按 ≥1200ms | ✅ 对会话列表行 `[479,266][1199,314]`（`long-clickable=true`）长按 **1500ms** |
| 弹层归属验明 | ✅ `dumpsys window windows`：`Window{9de6ee9 u0 Pop-Up Window}` → `mOwnerUid=10189`、`package=com.xiufeigo.opencodeshell`、`mSession=…3253`（= 本 app pid）**属本 app**；`mFocusedApp=…/OcActivity` 佐证 |
| 再点 | ✅ 菜单 `Delete…` `[479,475][609,523]` → 确认框 `Delete session?`（`Cancel`/`Delete`）—— 此时**唯一 app 窗口仍是 `OcActivity`（`mOwnerUid=10189`）**，归属依然验明 → 点 `Delete` `[1258,416][1298,436]` |
| 会话已清理 | ✅ 复查列表 dump：**`E2ECHK5556u1j` 不再出现** |
| `/sdcard` 本轮残留 | ✅ **0**：对本轮 37 个文件名逐个 `ls` 复查，输出为空 |
| 全程未按 BACK(4) / 未用 ESC(111) | ✅ |
| 未跑 gradle / 未装机 / 未杀进程 | ✅ |

---

## 8. 未闭环 / 需要 Lead 裁定

1. **① 在两档都仍是假，v106 没有触及它。**
   v106 只动了 `band`（`unionPx ≤ band`）这条，600 档确实修好了（+4→0）。
   但 `unionBottom ≤ hostBottom` 的 +2（768）/ +5（600）与 v105 **逐位相同**。
   600 档的成因可直接读出：pane `[162,213]` 高 **51**，而带被放在 `[170,218]`
   —— **比 pane 顶低 8px，48 + 8 = 56 > 51 ⇒ 溢出 5px**。
   换句话说 `hostPx` 虽已是 48，但**带相对 pane 的定位（8px 偏移）没有随 44→48 调整**。
   这是 v105 就存在、v106 未覆盖的**第二个独立缺口**。

2. **768 档在结构上无法验证 v106。**
   768 走**堆叠**形态，而 `band` 只被一行形态消费（v106 文档 §1.5 自陈）。
   所以 768 档的 `band` 仍是堆叠行的 `TOOL_ROW_HEIGHT=44`，② 的 +4 在 768 **必然依旧**。
   本报告在 768 仍按 v105 同口径取 `band=44` 以保持可比；**若 Lead 认为 768 不该记 ② 的 FAIL，
   那是口径问题，需要显式裁定"band 在堆叠形态取什么值"**——现有两档数据无法回答。

3. **`hostBottom` 口径的歧义（768 档特有，必须交 Lead）。**
   Lead 裁定"pane 底沿若报则用 `imeTop`"，v105 基线也用 `imeTop`。但 768 档
   **`imeTop=294` 与 `pane` 底沿 `302` 并不相等**（600 档两者都是 213，无歧义）：
   - 按 `imeTop`（本轮与 v105 同口径）⇒ ① = 296−294 = **+2** ❌
   - 按 `pane` 底沿（v103 的 `hostBottom ≡ pane` 恒等式）⇒ ① = 296−302 = **−6** ✅
   本轮**保持 v105 同口径（imeTop）以便与基线逐位对比**，不擅自换口径。
   600 档不受此影响（两口径同值，① 仍为 +5 ❌）。

4. **`readable` 在 768 仍是推导值**（`140−48`），非 a11y 直接读数（沿用 v105 的同一限制）。

5. **`/sdcard` 存在大量往轮残留**（`v43_*`/`v51_*`/`v52_*`/`v53_*`/`tB_*`/`tb_*`/`fs_*`/`med_*`/`b2_*`/`cp_*`/`r1_*`/`r2_*`/`d1*`–`d61*` 等上千个文件）。
   **不是本轮产物，本轮未删除**（不越权清理他人轮次产物）。本轮自己的 37 个文件已确认清零。

6. **方法学瑕疵（如实记录）**：首次做 768 的 logcat 扫描时用了 PowerShell 自动变量
   `$pid`（值 56084，是 pwsh 自身进程号，不是 app pid），那次扫描**无效**。
   随即改用 `$appPid` 重新取到真实 pid **3253** 并重扫，**结果为空**。
   本报告 §5 表格里的 CLEAN 全部来自修正后的扫描。

7. **未做的事**：未验证 ≥11 条深队列下的队列条几何（任务只要求 1 条队列态）；
   未在 600 档做队列测量；未跑 `am force-stop`（全程无崩溃，不需要）。

---

## 9. 复现材料

| 文件 | 内容 |
|---|---|
| `parse_5556.ps1` | dump 解析器（同时匹配 `text="…"`/`text='…'`，按 `<node` 边界切分，适配 `--compressed` 单行树） |
| `cmp_5556.ps1` | 缩窗往返的逐档几何比对 |
| `m768_a.xml` / `m768_a.png` | 768 主判据（堆叠形态） |
| `m600_a.xml` / `m600_a.png` | 600 主判据（一行形态） |
| `r1_768/600`, `r2_768/600`, `r2f_768` | 往返 2 轮的 5 个检查点 |
| `q_i.xml` / `q_i.png` | 队列条（Steer / ×） |
| `c_6.xml`, `c_7.xml` | 上下文菜单 / 删除确认框（归属验明用） |
