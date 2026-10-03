# v98 D3 真机复验报告 — SSH 条目 ⋮→Delete「确认条滚进视野」修复

**执行人**：真机复验测试员（mcode）
**日期**：2026-10-02
**设备**：`emulator-5554` / **Pixel_9a**
**输入**：`research/v97_fix/SSH_DELETE_FIX.md`（根因与修法）
**被验构建**：Lead 21:18 闸门构建

---

## 0. 结论先说

> **D3 修复在真机上成立。核心缺陷（第 2 条及以后的行删不掉）已确认闭环。**
> 确认条在 arm 时**自己滚进了视野**，第 1 行 / 第 2 行都能弹确认、都能删掉。
> **第 2 行做了两轮独立复现，两轮几何读数完全一致。**

| # | 断言 | 判定 |
|---|---|---|
| D3-1 | 第 1 行 ⋮→Delete→确认条可见→行消失 | ✅ **PASS** |
| D3-2 | **第 2 行**（核心）⋮→Delete→确认条滚进视野→行消失 | ✅ **PASS**（2 轮独立复现） |
| D3-3 | 第 3 行及更后面 | ⚠️ **未验到**（条目未能建成，见 §5） |
| D3-4 | Connected / Connecting / Authentication required 三态 | ⚠️ **未验到**（见 §5） |
| D3-5 | 反例对照：点确认只删目标行，上方行不被误删 | ✅ **PASS** |
| D3-6 | 清理（条目 + `/sdcard`） | ✅ **PASS** |

**关键读数（两轮一致）**：
- 面板 body 滚动视口 **`[43,800][1038,1937]`** —— y ∈ **[800, 1937]**
- 第 2 行确认条 **y ∈ [1635, 1852]** —— **完全落在视口内**，两端各留 835px / 85px 余量
- arm 瞬间列表**自动上滚 356px**（修复前：确认条落在 y≈1965+、视口外）

---

## 1. 环境读数

```
$ adb -s emulator-5554 emu avd name
Pixel_9a                                    ← 与任务书一致 ✅

$ adb -s emulator-5554 shell "dumpsys package com.xiufeigo.opencodeshell | grep -E 'lastUpdateTime|versionName|versionCode'"
      versionCode=26 minSdk=26 targetSdk=35
      versionName=1.4.0
      lastUpdateTime=2026-10-02 13:18:57    ← Lead 21:18 闸门构建 ✅
      firstInstallTime=2026-09-27 04:29:58

$ adb -s emulator-5554 shell pm path com.xiufeigo.opencodeshell
package:/data/app/~~h02E7H6wTzXkx_VqPQJPGg==/com.xiufeigo.opencodeshell-QfmOFkwQ2qNgQdcvcv_gTA==/base.apk

$ adb -s emulator-5554 shell "wm size; wm density"
Physical size: 1080x2424
Physical density: 420

$ adb -s emulator-5554 reverse --list          ← 接手时为空（见下）
host-15 tcp:4095 tcp:4095
host-15 tcp:2222 tcp:2222

$ Invoke-WebRequest http://127.0.0.1:4095/    → HTTP 200
```

⚠️ **`reverse` 接手时是空的**（与任务书「已挂」不符）。本轮按任务书重挂 4095/2222 后再开始，
未触碰 5555。

**纪律遵守**：未跑 gradle、未装机、未杀模拟器进程、未碰 127.0.0.1:5555、
**全程未按 BACK(4)**、弹层内**未按 ESC(111)**、截图一律 `screencap -p` + `adb pull`、
所有坐标运行期从 dump 解析（`tap.ps1` 按文本重新解析 bounds，从不硬编码）。

---

## 2. 面板视口基线（D3-1/D3-2 全部以此为判据）

```
$ 从 05_servers.xml 取 scrollable 节点
scroll android.view.View       [64,707][1017,781]     ← 顶部 tab 条
scroll android.widget.ScrollView [43,800][1038,1937]  ← 面板 body 唯一滚动容器
```

**视口 = `[43,800][1038,1937]`，y ∈ [800, 1937]。**

与 `SSH_DELETE_FIX.md` §0 记录的 `[43,800][1038,1937]` **逐像素一致**，
说明复验跑在与诊断同一份构建/同一版式上，修复前后的对比成立。

---

## 3. 逐条判定

### 3.1 D3-2 第 2 行删除（核心）— ✅ PASS（两轮）

> 修复前该场景 **5/5 全败**（连确认条都看不见）。本轮**两轮独立复现均成功**。

#### 轮 A — 目标行 `E2ECHK5554v98-p`（上一轮遗留条目）

**① arm 前**：第 2 条 ⋮ 位于 `[780,1827][906,1953]` —— **正是修复文档记录的失效几何**
（y 1827-1953，已压在折叠线上）。

```
$ adb shell input tap 843 1890            # 第 2 行 ⋮（由 dump 解析）
$ adb shell input tap 667 1769            # 菜单 Delete [618,1743][717,1796]
```

**② arm 后（关键读数）** — 证据 `40_*.xml` 同源 dump `07_row2_afterDelete.xml`：

| 节点 | bounds |
|---|---|
| 列表自动上滚量 | **356px**（`E2ECHK5554v98-k` 1342→986；`E2ECHK5554v98-p` 1667→1311） |
| 确认条标题 `Delete SSH server?` | `[233,1635][546,1676]` |
| 确认条正文 `This removes "…" …` | `[233,1687][848,1757]` |
| 确认条 `Cancel` | `[501,1811][612,1852]` |
| 确认条 `Delete` | `[707,1811][811,1852]` |
| **确认条 y 区间** | **[1635, 1852]** |
| **视口 y 区间** | **[800, 1937]** |

**判定：`1635 ≥ 800` 且 `1852 ≤ 1937` → 确认条（含两个按钮）完全在视口内。✅**

a11y 树里能 dump 到 `Delete SSH server?`（不再是上一轮 `dialogNodes=0` 的情形），
且两个按钮都是可点实体节点。

**③ 确认后** — 证据 `08_row2_afterConfirm.xml`：

```
E2ECHK5554v98-p        → 树中消失          ← 行被删除 ✅
E2ECHK5554v98-k        → 仍在 [201,986][513,1027]  ← 上方行未被误删 ✅
Delete SSH server?     → 消失（确认条收起）
```

紧邻的下一行以 3px 残片 `[169,2000][544,2003]` 出现在视口下方——
与修复前「3px 残片」签名一致，但这次它属于**折叠线以下的正常内容**，
而确认条本身已滚进视野。

#### 轮 B — 目标行 `chk123`（本轮新建，清理对象）

完全独立复现，**几何读数与轮 A 逐像素一致**：

| 项 | 读数 |
|---|---|
| 第 2 行 ⋮ | `[780,1827][906,1953]`，center `843,1890` |
| 菜单 `Delete` | `[618,1743][717,1796]` |
| 确认条标题 | `[233,1635][546,1676]` |
| 确认条 `Cancel` | `[501,1811][612,1852]` |
| 确认条 `Delete` | `[707,1811][811,1852]` |
| **确认条 y 区间** | **[1635, 1852]** |
| **视口 bounds** | `[43,800][1038,1937]` |
| 确认后 | `chk123` 消失、`E2ECHK5554v98-k` 存活 ✅ |

证据：`40_r2_confirmbar.xml` / `40_r2_confirmbar.png` /
`40_r2_confirmbar_EVIDENCE.png` / `41_r2_deleted.xml` / `41_r2_deleted.png`

---

### 3.2 D3-1 第 1 行删除 — ✅ PASS

arm 前后无遮挡，行位于列表顶部。

| 项 | 读数 |
|---|---|
| 第 1 行 ⋮（`E2ECHK5554v98-k`） | `[780,1146][906,1272]`，center `843,1209` |
| 菜单 `Delete` | `[618,1304][717,1357]`，center `668,1330` |
| 确认条标题 `Delete SSH server?` | `[233,1310][546,1351]` |
| 确认条正文 | `[233,1362][848,1432]` |
| 确认条 `Cancel` | `[501,1486][612,1527]` |
| 确认条 `Delete` | `[707,1486][811,1527]` |
| **确认条 y 区间** | **[1310, 1527]** |
| **视口 bounds** | `[43,800][1038,1937]` |
| 确认后 | `E2ECHK5554v98-k` 从树中消失 ✅ |

**判定：`1310 ≥ 800` 且 `1527 ≤ 1937` → 完全在视口内。✅**

注意第 1 行 **arm 前后列表未发生任何滚动**：
`E2ECHK5554v98-k` 在 `42_r1_menu.xml` 与 `43_r1_bar.xml` 中**同为 `[201,986][513,1027]`**，
arm 时它已处在因上一轮（第 2 行删除）而上滚到的位置，
其确认条（y 1310-1527）**本来就完整落在视口内**，故
`bringIntoView()` 是**忠实的 no-op**。

> 这正是修法最需要担心的一处 —— 若 `bringIntoView` 在靠上位置**过度滚动**，
> 反而会把确认条顶出视野。实测**未发生**：既有 pin 担心的回归没有出现。

（易混淆点：`1342 → 986` 的 **356px** 位移属于**第 2 行那次删除**（§3.1），
不是第 1 行 arm 造成的。此处按 dump 逐帧核对过 `38_fresh / 41_r2_deleted / 42_r1_menu / 43_r1_bar`。）

证据：`42_r1_menu.xml` / `43_r1_bar.xml` / `43_r1_bar.png` / `44_r1_deleted.xml`

---

### 3.3 D3-5 反例对照 — ✅ PASS

「确认条出现时，它上面的行不应被误删」。

两轮第 2 行删除都同时充当了反例对照：

| 轮次 | 目标行（被删） | 上方行（应存活） | 结果 |
|---|---|---|---|
| A | `E2ECHK5554v98-p`（第 2 行） | `E2ECHK5554v98-k`（第 1 行） | 上方行存活 ✅ |
| B | `chk123`（第 2 行） | `E2ECHK5554v98-k`（第 1 行） | 上方行存活 ✅ |

**判定：点确认只删目标行，不误删上方行。✅**
这同时排除了「`bringIntoView` 引入第二个滚动容器、导致命中区错位」这一风险假设。

---

## 4. 证据文件（相对 `research/v97_verify/5554_v98d3/`）

| 文件 | 内容 |
|---|---|
| `00_launch.xml` / `01_settings.xml` | 启动态、进 Settings |
| `02_tabs.xml` … `04_tabs3.xml` | tab 条横向滚动，找到 `Servers` |
| `05_servers.xml` | **视口基线**：`ScrollView [43,800][1038,1937]` |
| `06_row2_menu.xml` | 第 2 行 ⋮ 菜单展开 |
| `07_row2_afterDelete.xml` | **轮 A 确认条几何（核心证据）** |
| `08_row2_afterConfirm.xml` | 轮 A 确认后：行消失 / 上方行存活 |
| `38_fresh.xml` | 清空重开后的 2 行基线 |
| `39_r2_menu.xml` | 第 2 行菜单（轮 B） |
| `40_r2_confirmbar.xml` / `.png` | **轮 B 确认条几何（核心证据）** |
| `40_r2_confirmbar_EVIDENCE.png` | 轮 B 确认条截图副本（留证） |
| `41_r2_deleted.xml` / `.png` | 轮 B 确认后：`chk123` 消失 |
| `42_r1_menu.xml` | 第 1 行菜单 |
| `43_r1_bar.xml` / `.png` | **第 1 行确认条几何** |
| `44_r1_deleted.xml` / `.png` | 第 1 行确认后：行消失 |
| `20_imehidden.png` … `31_final_rows.png` | 建条目过程取证（表单排版取证） |
| `pre_server_config.xml` / `post_server_config.xml` | 持久化存储前后快照 |
| `cap.ps1` / `tap.ps1` / `add.ps1` | 取证与坐标解析脚本（坐标一律运行期解析） |

---

## 5. 未验到 / 未闭环

### 5.1 ⚠️ D3-3 第 3 行 —— 未验到

**原因（工具性，非缺陷）**：未能建成第 3 条 SSH 条目。
`Add SSH server` 表单在自动化下不稳定：IME 显隐会改变表单高度，
`Add server` 按钮在 `[760,1383]`↔`[760,1779]` 之间跳动；
多次尝试出现文本串入搜索框（面板显示 `No matching settings`）。
最终只建成 1 条（`chk123`），加上上一轮遗留的 1 条（`E2ECHK5554v98-k`），
**列表最多只有 2 行**，故第 3 行无从触发。

**影响评估：低**。第 3 行与第 2 行在布局上是同一类位置（都在折叠线附近/之下），
本轮已证明 `bringIntoView` 是**按需**滚动、且能保证确认条完整入视；
第 2 行（最靠下的可达位置）通过即覆盖了该失效模式的边界。
**但严格意义上第 3 行未取得真机读数，不做 PASS 声明。**

### 5.2 ⚠️ D3-4 三种行态 —— 未验到

本轮跑完全部时间预算时，SSH 行只出现过 **`Disconnected`** 一种态。
`Connected` / `Connecting` / `Authentication required` 三态下的删除**未取得读数**。
`SSH_DELETE_FIX.md` §6-2 已说明 `Authentication required` 需另建错口令条目、
且 ⋮ 菜单无 `Edit` 无法改造 —— 造法成本高。

**但 JVM 侧已有 pin 覆盖**：`SshDeletePathV97Test.theOverflowEntryArmsTheRowInEveryStage`
（遍历 10 个 stage）、`theThreeReportedRowStatesAllArm`（READY/AUTHENTICATION/CONNECTING）。
**真机侧本项留空，不做 PASS 声明。**

### 5.3 未做的

- 「删除后等 30s + 离开再进，断言不复活」——本轮时间预算耗尽，未执行。
- 持久化 `ssh_json` 层面的 id 消失核对 —— 存储为 `androidx.security` 加密
  （`oc_server_config.xml` 全为 `ATA0KB…` 密文），**无法在设备侧明文核对**；
  只能以 a11y 树中行消失为准（已验）。

---

## 6. 结论与建议

1. **D3 修复判定通过。** 根因（确认条落在列表视口外）与修法
   （`bringIntoViewRequester` + `bringIntoView()` 复用面板已有 `verticalScroll`）
   在真机 Pixel_9a 上**均得到证实**。
2. **建议关闭 D3。** 核心失效模式（第 2 条及以后的行删不掉，修复前 5/5 全败）已两轮闭环。
3. **建议补做的两项**（均非本修复的回归风险，而是覆盖度缺口）：
   - 第 3 行及以上（需先解决建条目自动化）；
   - `Connected` / `Connecting` / `Authentication required` 三态下的删除。
   两者可与下一轮一并安排，**不建议因此阻塞 D3 收口**。
4. **顺带记录（非本任务范围，未处置）**：`Add SSH server` 表单在 IME 显隐时
   `Add server` 按钮位置跳动（`y=1383` ↔ `y=1779`），自动化难以稳定命中；
   人工使用无碍。若后续要做表单相关的自动化，建议单独立 issue。

---

## 7. 清理

| 项 | 结果 |
|---|---|
| 本轮新建条目 `chk123` | ✅ 已删除（证据 `41_r2_deleted.xml`） |
| 上一轮遗留 `E2ECHK5554v98-p` | ✅ 本轮删除（轮 A 即清理） |
| 上一轮遗留 `E2ECHK5554v98-k` | ✅ 本轮删除（第 1 行测试对象） |
| 最终 SSH 行数 | **0**（`44_r1_deleted.xml` 中无任何 E2ECHK/chk123 节点） |
| `/sdcard/d3`（本轮 dump/截图暂存） | ✅ `rm -rf` 后 `ls` 报 `No such file or directory` |
| `/sdcard` 残留 | 本轮**未新增**残留。`/sdcard` 上另有 `E2ECHK5556_V95C_*`、`v34_*`、`v38B_*`、`v43_*` 等，**属其他轮次产物，非本轮创建，按纪律未删除** |
| reverse 端口 | 保持 `4095` / `2222` 已挂 |
| 模拟器进程 | 未杀；本 app 仅在开场做过一次 `am force-stop` |
