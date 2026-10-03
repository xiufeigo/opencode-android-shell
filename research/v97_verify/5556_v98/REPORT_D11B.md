# REPORT_D11B — 真机复验 D11-b：Review→Changes **不得有任何「闪现式假空」**

- 执行 Agent：真机复验测试员（D11-b 专项，第四版）
- 设备：`emulator-5556`，AVD 名 **Medium_Desktop** ✅
- 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
- 缺陷链路：`5556_v98/REPORT_SWEEPC2.md` D11（假空）→ `REVIEW_DIFF_HONESTY_FIX.md`（三态 Verdict，**仍闪现**）→ `5556_v98/REPORT_D11.md`（真机复现 **6 轮 1 次 ≈ 17%**）→ **`v97_fix/REVIEW_DIFF_HONESTY_FIX2.md`（本轮待验：候选来源修正 + `Attempt.Pending` / `Verdict.IN_FLIGHT` + 发布门）**
- 复核基准：`5556_v98/REPORT_D11.md`（ground truth 命令与采样方法）、`REVIEW_DIFF_HONESTY_FIX2.md` §1.3 时序表 / §6 判定表
- 自建会话：**`E2ECHK5556d11b`** = `ses_f02de6d9dffeP19XhCsN2gFyk7`（本轮新建，落在真实仓库项目 `ae54078c0e09bf8e7cbeefd35db19aaa6b064e46` 内）
- 纪律：不跑 gradle / 不装机 / 不杀进程 / 只动 5556；未按 BACK(4)；弹层内未按 ESC(111)；截图仅 `screencap -p` + `adb pull`（**未用 exec-out**）；`wm size` 必配 `wm density` 且**已 reset**；只动前缀 `E2ECHK5556d11b` 的自建会话；坐标**全部运行期从 dump 解析**；dump 解析**同时匹配 `text="…"` 与 `text='…'`**；未改任何代码。

---

## 0. 结论速览

| 复验项 | 判定 |
|---|---|
| 环境 / 闸门构建 | ✅ AVD `Medium_Desktop`；`lastUpdateTime=2026-10-02 14:58:07`，与 Lead 22:57 闸门构建一致 |
| host ground truth | **1191 files changed** —— 真实存在海量未提交改动 |
| 窄窗超时基线 | ✅ 诚实失败态（`The diff request timed out or failed.`） |
| **三轮 × 5 点 = 15 次采样** | **✅ 假空 0 / 15（0%）** |
| 正常窗对照（5 点） | **✅ 假空 0 / 5**，全程诚实失败态 |
| **整体** | **✅ PASS —— `REVIEW_DIFF_HONESTY_FIX2.md` 在真机上闭环，D11「闪现式假空」未复现** |

> **与上一轮的直接对比**：`REPORT_D11.md` 6 轮采样中 **1 轮复现假空（≈17%）**；本轮 **3 轮 × 5 点 = 15 次采样 + 正常窗 5 次 = 20 次采样，假空 0 次**。
> 统计口径提示：15 次采样若按上一轮 17% 概率独立发生，期望命中约 2.5 次；**观察到 0 次**，修复方向与真机表现一致（但样本量仍有限，见 §7）。

---

## 1. 环境读数

| 项 | 读数 | 判定 |
|---|---|---|
| AVD 名 | `adb -s emulator-5556 emu avd name` → **`Medium_Desktop`** | ✅ 符合任务书 |
| `reverse --list` | `host-19 tcp:4095 tcp:4095` / `host-19 tcp:2222 tcp:22` | ✅ **非空，无需重挂** |
| APK 闸门构建 | `versionName=1.4.0` `versionCode=26` **`lastUpdateTime=2026-10-02 14:58:07`** | ✅ 与任务书「22:57 闸门构建」一致 |
| 屏幕（基线） | `Physical size: 3840x2160`（**无 override**） | 记录为基线 |
| 密度（基线） | `Physical density: 320`（**无 override**） | 记录为基线 |
| 后端 | `http://127.0.0.1:4095` Basic `opencode:opencode` | ✅ |
| 真实仓库项目 | `ae54078c0e09bf8e7cbeefd35db19aaa6b064e46`，`canonical` = `/mnt/c/sensorsdata/main/program/opencode-android-shell` | ✅ 由 `GET /api/project` 解析 |
| 自建会话 | `POST /api/session` → `ses_f02de6d9dffeP19XhCsN2gFyk7`，title `E2ECHK5556d11b` | ✅ 本轮新建 |

> 注：`GET /api/project` 首次返回的 `worktree` 字段全为空，需读 `canonical` 才是真实路径（沿用 `REPORT_D11.md` §1 方法论注记）。
> 注：本轮开局基线为**纯物理值**（无 override），说明上一轮已正确 reset；`REPORT_D11.md` §1 记录的 `Override 1366x768/160` 是更早一轮的遗留 override。

---

## 2. Ground truth（host 侧，只读，未改任何代码）

任务书标准命令指向 `/home/xiufeigo`（另一个仓库），沿用 `REPORT_D11.md` §2 的判定，改用**本仓实际路径**的等价命令：

```powershell
wsl -d Arch -e bash -lc "cd /mnt/c/sensorsdata/main/program/opencode-android-shell && echo '--- status count ---' && git status --porcelain | wc -l && echo '--- shortstat HEAD ---' && git diff --shortstat HEAD && echo '--- branch ---' && git rev-parse --abbrev-ref HEAD"
```

**原文输出**：

```
--- status count ---
4006
--- shortstat HEAD ---
 1191 files changed, 109284 insertions(+), 60348 deletions(-)
--- branch ---
main
```

> 与上一轮 `REPORT_D11.md` §2 的 `4005` / `1191 files changed, 109218 insertions(+), 60354 deletions(-)` 相比：`status` 计数 +1（多 agent 并行作业），**`files changed` 仍为 1191**，insertions/deletions 各有几十行漂移。D11 判定**不依赖具体数字**，只依赖「真实存在海量未提交改动」这一事实 —— 本轮该事实**再次成立**。

**ground truth 结论：工作区真实存在 1191 个文件、10.9 万行插入的未提交改动。任何 `No uncommitted changes yet`（无未提交改动）在本仓都是错误显示。**

---

## 3. 复验方法（对齐 `REVIEW_DIFF_HONESTY_FIX2.md` §6 判定表）

| # | 操作 | 本轮实际 | 期望（修复后） | 判定 |
| --- | --- | --- | --- | --- |
| 0 | host ground truth | §2 | 非空 | ✅ |
| 1 | `wm size 560x768` + `wm density 160` → 重启 app → 进自建会话 → `Changes` | §4.0 | loading / 慢读提示 | ✅ |
| 2 | 等超时（失败横幅） | §4.0 | 诚实失败态 | ✅ |
| 3 | **点 `Refresh`，采样 T+1s / T+3s / T+10s / T+20s / T+40s** | §4.1–4.3 | **五次都不得出现 `No uncommitted changes yet`** | ✅ |
| 4 | **重复 3 轮**（上一轮假空仅 ~17% 概率，单轮不足） | 3 轮 × 5 点 = **15 次采样** | 任一轮出现假空即 FAIL | ✅ |
| 5 | 正常窗（`wm size reset` + `wm density reset`）对照 | §5 | 不得宣称「没有未提交改动」 | ✅ |
| 6 | 收尾 reset 复核 + `/sdcard` 清理 | §6 | — | ✅ |

**采样纪律（关键）**：

- 每个时间点**同时**做 `screencap -p` + `uiautomator dump`，两者落盘为**独立证据文件**（26 png + 38 xml）。
- dump 解析**同时匹配双引号与单引号** `text=` 形态（Compose 可能用单引号）。
- 采样**不止步于单张截图**：每轮 5 个时间点连续采样，最长追到 T+40s，覆盖上一轮假空出现的 T+3s 窗口及其后自愈区。
- 判定脚本对**字面量** `No uncommitted changes yet` 另做一次跨全部 dump 的原始扫描（§4.4），不依赖分类器。

---

## 4. 三轮 × 五点采样（**核心证据**）

### 4.0 窄窗准备与超时基线

| 步骤 | 读数 |
|---|---|
| 施加窄窗 | `wm size 560x768` + `wm density 160` → 复核 `Override size: 560x768` / `Override density: 160` ✅ |
| 重启 app | `am start -n com.xiufeigo.opencodeshell/.OcActivity` |
| 布局复核 | dump 根节点 bounds = **`[0,0][560,768]`** ✅（窄窗生效） |
| 进自建会话 | Home → `Open sessions`(62,133) → 滚动找到 `E2ECHK5556d11b`（运行期解析 center `240,366`）→ 点入 |
| 确认会话 | 标题栏 `E2ECHK5556d11b`，会话列表未见他人会话被改动 |
| 点 `Changes` | 运行期解析 center `195,180` |
| **T+6s** | **`The diff request timed out or failed. Tap Refresh to try again.`** |
| **T+28s** | 同上（稳定诚实失败态） |

- 判定 **PASS**：超时态诚实呈现，与 `REVIEW_DIFF_HONESTY_FIX.md` §4.1 的 `LOAD_SLOW` 预期一致。
- 证据：`d11b_g_t6.png/.xml`、`d11b_h_t28.png/.xml`
- 窄窗下 `Refresh` 按钮 center = **`522,242`**（三轮均运行期解析，**未硬编码坐标**）。

### 4.1 采样表总览

**判定规则**：`FALSE_EMPTY` = 出现 `No uncommitted changes yet`（**本仓必为假空**，与 ground truth 1191 files changed 直接冲突）→ **FAIL**；`LOADING` / `FAILED` / 真实 diff → **PASS**。

| 轮 | 时刻（计划/实际） | **正文文案原文（dump 逐字）** | 状态判定 | 证据文件（dump + 截图） | 单格判定 |
| --- | --- | --- | --- | --- | --- |
| **C1** | T+1s / 1s | `Loading` | `LOADING` | `d11b_c1_t1.xml` + `.png` | ✅ PASS |
| **C1** | T+3s / 5s | `The diff request timed out or failed. Tap Refresh to try again.` | `FAILED` | `d11b_c1_t3.xml` + `.png` | ✅ PASS |
| **C1** | T+10s / 10s | `The diff request timed out or failed. Tap Refresh to try again.` | `FAILED` | `d11b_c1_t10.xml` + `.png` | ✅ PASS |
| **C1** | T+20s / 20s | `The diff request timed out or failed. Tap Refresh to try again.` | `FAILED` | `d11b_c1_t20.xml` + `.png` | ✅ PASS |
| **C1** | T+40s / 41s | `The diff request timed out or failed. Tap Refresh to try again.` | `FAILED` | `d11b_c1_t40.xml` + `.png` | ✅ PASS |
| **C2** | T+1s / 1s | `Loading` | `LOADING` | `d11b_c2_t1.xml` + `.png` | ✅ PASS |
| **C2** | T+3s / 4s | `Loading` | `LOADING` | `d11b_c2_t3.xml` + `.png` | ✅ PASS |
| **C2** | T+10s / 11s | `The diff request timed out or failed. Tap Refresh to try again.` | `FAILED` | `d11b_c2_t10.xml` + `.png` | ✅ PASS |
| **C2** | T+20s / 20s | `The diff request timed out or failed. Tap Refresh to try again.` | `FAILED` | `d11b_c2_t20.xml` + `.png` | ✅ PASS |
| **C2** | T+40s / 41s | `The diff request timed out or failed. Tap Refresh to try again.` | `FAILED` | `d11b_c2_t40.xml` + `.png` | ✅ PASS |
| **C3** | T+1s / 1s | `Loading` | `LOADING` | `d11b_c3_t1.xml` + `.png` | ✅ PASS |
| **C3** | T+3s / 4s | `Loading` | `LOADING` | `d11b_c3_t3.xml` + `.png` | ✅ PASS |
| **C3** | T+10s / 10s | `The diff request timed out or failed. Tap Refresh to try again.` | `FAILED` | `d11b_c3_t10.xml` + `.png` | ✅ PASS |
| **C3** | T+20s / 20s | `The diff request timed out or failed. Tap Refresh to try again.` | `FAILED` | `d11b_c3_t20.xml` + `.png` | ✅ PASS |
| **C3** | T+40s / 41s | `The diff request timed out or failed. Tap Refresh to try again.` | `FAILED` | `d11b_c3_t40.xml` + `.png` | ✅ PASS |

> **实际时刻**由 `Stopwatch` 记录（dump + `screencap` + `adb pull` 本身耗时约 1–2s，故 T+3s 实际落在 4–5s）。这个偏差方向**只会让采样更晚**、不会更早，因此**不会漏掉 T+3s 的假空窗口**；T+1s 一格的实测延迟 <1s，对最早期窗口的覆盖是有效的。

### 4.2 关键发现：T+1s / T+3s 一律停在 `Loading`（不再闪现空态）

| 轮 | T+1s | T+3s | 上一轮同一位置的读数 |
| --- | --- | --- | --- |
| C1 | `Loading` | `FAILED` | — |
| C2 | `Loading` | **`Loading`** | — |
| C3 | `Loading` | **`Loading`** | — |
| **`REPORT_D11.md` cycle 1** | — | **`No uncommitted changes yet`（假空）** | ❌ |

- 上一轮假空出现在 **T+3s**（`d11_d_ref_now`）与 **T+10s**（`d11_d_ref10`）。本轮对应位置**全部为 `Loading` 或诚实 `FAILED`**。
- 形态上完全吻合 `REVIEW_DIFF_HONESTY_FIX2.md` §4.1 的四态设计：**「在飞」态现在真的会显示为 loading**，而不是被 `verdict` 提前提升为 `EMPTY`。
- 状态指纹可区分（证明是**不同的 UI 状态**，非 dump 抖动）：
  - `Loading` 帧 = **17,526 B**
  - 失败帧（T+3s/T+10s）= **17,581 B**
  - 失败帧（T+20s/T+40s）= **18,929 B**
  - （上一轮假空帧为 17,544 B，与本轮任何一帧都**不重合**）

### 4.3 三轮判定汇总

| 轮次 | T+1s | T+3s | T+10s | T+20s | T+40s | **假空次数** |
| --- | --- | --- | --- | --- | --- | ---: |
| C1 | LOADING | FAILED | FAILED | FAILED | FAILED | **0** |
| C2 | LOADING | LOADING | FAILED | FAILED | FAILED | **0** |
| C3 | LOADING | LOADING | FAILED | FAILED | FAILED | **0** |
| | | | | | **合计** | **0 / 15** |

- 对比上一轮 `REPORT_D11.md`：6 轮采样 **1 轮复现**（cycle 1，T+3s 与 T+10s 两次读数均为假空）。
- 本轮 **15 次采样全部非假空**。

### 4.4 字面量原始扫描（独立于分类器的二次确认）

```powershell
Select-String -Path ".\d11b_c*.xml" -Pattern "No uncommitted changes yet" -SimpleMatch
```

**输出**：

```
CLEAN: 0 hits in d11b_c*.xml
```

- 判定 **✅ PASS**：三轮共 15 个 dump 文件中，**字面量 `No uncommitted changes yet` 出现 0 次**。
- 该扫描直接对 XML 原文做 `Select-String`，**不经过任何状态分类逻辑**，排除了「分类器把假空误判成别的」这一可能。
- 同时 `oc_review_changes_empty_git`（= `No uncommitted changes yet`，`strings.xml:571`）对应的**真空态在本仓从未出现**。

---

## 5. 正常窗对照（正路径不能坏）

| 步骤 | 读数 |
|---|---|
| 恢复窗口 | `wm size reset` + `wm density reset` → **`Physical size: 3840x2160`** / **`Physical density: 320`** ✅ |
| 布局复核 | dump 根节点 bounds = **`[0,0][3840,2160]`** ✅（reset 生效） |
| 进自建会话 | `Open sessions`(128,268) → 滚动找到 `E2ECHK5556d11b`（运行期解析 center `232,373`）→ 点入 |
| 开 Review 面板 | 正常窗下为**桌面停靠式**，入口 `Toggle review panel`（运行期解析 center `3792,276`） |
| 面板初始态（T+5s） | `The diff request timed out or failed. Tap Refresh to try again.` |

**点 `Refresh` 后的连续采样（center `3700,266`，运行期解析）**：

| 时刻（计划/实际） | **正文文案原文** | 状态 | 证据 | 判定 |
| --- | --- | --- | --- | --- |
| T+1s / 1s | `The diff request timed out or failed. Tap Refresh to try again.` | `FAILED` | `d11b_norm_t1.xml/.png` | ✅ PASS |
| T+3s / 4s | 同上 | `FAILED` | `d11b_norm_t3.xml/.png` | ✅ PASS |
| T+10s / 10s | 同上 | `FAILED` | `d11b_norm_t10.xml/.png` | ✅ PASS |
| T+20s / 20s | 同上 | `FAILED` | `d11b_norm_t20.xml/.png` | ✅ PASS |
| T+40s / 41s | 同上 | `FAILED` | `d11b_norm_t40.xml/.png` | ✅ PASS |

- T+40s 完整正文节点集合（双引号 + 单引号双向解析）：
  ```
  E2ECHK5556d11 | E2ECHK5556d11b | Build anything | Ask anything, / for commands, @ for context... | GPT-6.1 Sol
  | Review | main | Refresh | Git changes | Branch changes | Changes | Context | Files
  | Couldn't load changes | The diff request timed out or failed. Tap Refresh to try again.
  ```
- **判定 PASS（诚实性维度）**：正常窗下 **5 次采样全部为诚实失败态**，**从未**出现 `No uncommitted changes yet`。
- **「能否读到真实改动」= 否**：40s 内后端 `vcs/diff` 仍未返回，UI 未列出任何真实文件。这是**后端在本仓 1191 文件的超大工作区上太慢**导致，**非本次修复引入的回归**（`REVIEW_DIFF_HONESTY_FIX.md` §7 第 4 步允许「列表出现文件」或「仍是失败态」二选一，本轮落在诚实失败这一支）。
- 证据：`d11b_i_norm_home.png`、`d11b_k_norm_session.xml`、`d11b_m_norm_t5.png/.xml`、`d11b_norm_t{1,3,10,20,40}.xml`

---

## 6. 收尾（设备设置复核 / `/sdcard` 残留 / 自建对象）

### 6.1 设备设置

| 设置 | 初值 | 峰值 | 终值 | 状态 |
|---|---|---|---|---|
| `wm size` | `Physical 3840x2160` | `Override 560x768` | **`Physical 3840x2160`（已 reset）** | ✅ |
| `wm density` | `Physical 320` | `Override 160` | **`Physical 320`（已 reset）** | ✅ |
| `reverse` | 4095/2222 | 未变 | `host-19 tcp:4095` / `host-19 tcp:2222` | ✅ 复核通过 |

- 未执行：`pm clear`、`pm grant`、杀进程、`am force-stop`、`input keyevent 4`(BACK)、`input keyevent 111`(ESC)、**未点任何 Delete 文案**
- 未触碰：127.0.0.1:5555、同机其余模拟器
- `reverse` 全程未重挂（开局非空）

### 6.2 `/sdcard` 残留检查

- **清理前**：本轮产出 **64 个** `d11b_*` 文件（dump + 截图）。
- **清理方式**：`rm -f` 逐个精确传入 `ls /sdcard/ | grep -i d11b` 返回的确切路径（**未使用通配递归删除**）。
- **清理后读数**：**`CLEAN: no d11b residue`** ✅
- `/sdcard` 上另有 `d11.xml`、`c2_*`、`tb_*`、`v5*_*` 等历史文件，属**同机其他轮次 agent 的产物**，本轮**未触碰**（不越权清理）。

### 6.3 自建对象

- 会话：**`E2ECHK5556d11b`** = `ses_f02de6d9dffeP19XhCsN2gFyk7`（本轮经 `POST /api/session` 新建，落在真实仓库项目 `ae54078c…` 内）
  - 按纪律**保留**，不清理以免破坏共享后端
- 本地证据（`research/v97_verify/5556_v98/`）：
  - `d11b_*.png` **26 个**（截图）、`d11b_*.xml` **38 个**（dump）
  - `d11b_c1/2/3.json` + `d11b_c1/2/3.csv`（每轮结构化采样读数）、`d11b_norm.json`
  - 助手脚本 `d11b_helper.ps1`（dump/shot/双引号单引号解析/分类）、`d11b_cycle.ps1`（单轮 5 点采样）

---

## 7. 未闭环 / 待开发确认

| 项 | 状态 | 原因 / 建议 |
| --- | --- | --- |
| **后端 `vcs/diff` 在本仓持续超时** | **未闭环（超出本任务）** | 1191 文件的超大工作区让 diff 读极慢（客户端 20s deadline 必然先到）。`REPORT_D11.md` §8 实测后端 **90s** 仍未返回，本轮窄窗/正常窗均复现。**建议开发侧评估分页/惰性加载或按目录切片**，否则「超时→Refresh→恢复真实数据」在本仓永远走不到成功分支。这是 A2 的**上游瓶颈**，不是本次修复的缺陷。 |
| **正常窗读到真实改动（成功路径）** | **未验到** | 受上一条阻塞。判定为**非本次修复引入的回归**（正常窗从未假空），但**正路径的成功分支仍未在真机上验证过**。 |
| **样本量与统计效力** | **说明，非缺陷** | 上一轮假空概率约 17%。本轮 15 次（窄窗）+ 5 次（正常窗）采样**均未命中**。若假空仍是低概率间歇行为，20 次 0 命中**不能形式化排除**其存在，只能说「本次复验窗口内未复现」。修复文档 §6 建议的 2–6 轮是下限，本轮做到 3 轮 × 5 点（粒度更细）；**若需更强证据，建议在修复后端分页后再跑一轮**（一旦后端能在秒级返回真实 diff，间歇性窗口会被显著压缩甚至消失）。 |
| **logcat 内部证据缺失** | **未取到** | 未启动 `logcat -s OpenCodeShell:V` 抓包（`REPORT_D11.md` §5 亦缺）。因此「点 Refresh 后**被取代的旧走查不再回写**」（`REVIEW_DIFF_HONESTY_FIX2.md` §2.2c 的 `attemptId` 守卫）与「**每轮只有一次** `vcs diff failed for …`」这两条**内部断言未直接确证**。本轮结论依据是**外部可观测行为**（UI 文案随时间变化）——该行为正是这两条守卫的**唯一用户可见后果**，故判定成立，但内部机制未取证。 |
| **A2 回归：干净小目录应仍显示真空** | **未验** | `REVIEW_DIFF_HONESTY_FIX2.md` §6 第 8 步建议「在小而干净目录上开 Changes，应仍显示 `No uncommitted changes yet`」，本轮任务书未要求，**未执行**。这是修复**没有误伤真空态**的反向验证；`ReviewDiffReadStateV97Test` 的 8 个用例已在单元层覆盖（`SETTLED_EMPTY → mayRenderClean=true`），但**真机上未验**。 |
| **`Last turn changes` scope** | **未验** | 本轮只验了默认 `Git changes` scope。`Last turn changes` 走 session 快照，按 `changesBody` 的分支顺序不受 VCS 失败影响（`REVIEW_DIFF_HONESTY_FIX.md` §7 第 5 步已 pin），本轮未在真机上复验。 |

---

## 8. 命令清单（全部实跑）

```powershell
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5556 emu avd name                                          # Medium_Desktop
& $adb -s emulator-5556 reverse --list                                        # 4095/2222 已挂，非空
& $adb -s emulator-5556 shell wm size ; & $adb -s emulator-5556 shell wm density
& $adb -s emulator-5556 shell dumpsys package com.xiufeigo.opencodeshell | Select-String lastUpdateTime
wsl -d Arch -e bash -lc "cd /mnt/c/sensorsdata/main/program/opencode-android-shell && git status --porcelain | wc -l && git diff --shortstat HEAD"
Invoke-RestMethod http://127.0.0.1:4095/api/project                          # 真实仓库项目 ae54078c…
Invoke-RestMethod -Method Post http://127.0.0.1:4095/api/session             # 自建 E2ECHK5556d11b
& $adb -s emulator-5556 shell wm size 560x768 ; & $adb -s emulator-5556 shell wm density 160
& $adb -s emulator-5556 shell am start -n com.xiufeigo.opencodeshell/.OcActivity
& $adb -s emulator-5556 shell input tap <x> <y>                               # 坐标全部运行期从 dump 解析
& $adb -s emulator-5556 shell screencap -p /sdcard/<n>.png ; & $adb -s emulator-5556 pull …
& $adb -s emulator-5556 shell uiautomator dump /sdcard/<n>.xml ; & $adb -s emulator-5556 pull …
.\d11b_cycle.ps1 -Cycle 1 | -Cycle 2 | -Cycle 3                               # 每轮 5 点采样
Select-String -Path ".\d11b_c*.xml" -Pattern "No uncommitted changes yet" -SimpleMatch   # 字面量扫描：0 hits
& $adb -s emulator-5556 shell wm size reset ; & $adb -s emulator-5556 shell wm density reset
```

---

## 9. 最终判定

> **状态：PASS —— `REVIEW_DIFF_HONESTY_FIX2.md` 在真机（emulator-5556 / Medium_Desktop）上通过复验，D11「闪现式假空」未复现。**
>
> - **三轮共 15 次采样里是否出现过假空：否，0 次。**（另加正常窗 5 次采样，同样 0 次，合计 20 次 0 命中）
> - **正常窗对照结论：** 5 次采样**全部为诚实失败态**（`Couldn't load changes` + `The diff request timed out or failed. Tap Refresh to try again.`），**从未宣称「没有未提交改动」**；正路径的诚实性未被修坏，但受后端 diff 超时阻塞，**成功路径仍未验到**。
> - **残留未闭环项**（不影响本条判定）：后端 `vcs/diff` 在本仓 1191 文件工作区上持续超时（A2 上游瓶颈）；logcat 内部证据未取；A2 反向回归（干净小目录仍应显示真空）与 `Last turn changes` scope 本轮未验。
