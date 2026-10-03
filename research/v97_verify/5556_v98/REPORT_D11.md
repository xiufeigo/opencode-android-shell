# REPORT_D11 — 真机复验 D11：Review→Changes 超时后不得给出「假空」结论

- 执行 Agent：真机复验测试员（D11 专项）
- 设备：`emulator-5556`，AVD 名 **Medium_Desktop** ✅
- 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
- 缺陷来源：`research/v97_verify/5556_v98/REPORT_SWEEPC2.md` §3 **D11（高）**
- 修复依据：`research/v97_fix/REVIEW_DIFF_HONESTY_FIX.md`（三态 `Verdict`：FILES / EMPTY / **FAILED**）
- 自建会话：**`E2ECHK5556d11`** = `ses_f02fa34b8ffeG3KL7QQ4v2811P`（本轮新建，落在真实仓库项目内）
- 纪律：不跑 gradle / 不装机 / 不杀进程 / 只动 5556；未按 BACK(4)；弹层内未按 ESC(111)；截图仅 `screencap -p` + `adb pull`；`wm size` 必配 `wm density` 且**已 reset**；坐标全部运行期从 dump 解析；解析 dump 同时匹配 `text="…"` 与 `text='…'`（D9 纪律）；未改任何代码。

---

## 0. 结论速览

| 复验项 | 判定 |
|---|---|
| **超时后点 `Refresh` 是否仍显示「假空」** | **是 —— 复现成功**（6 轮里 1 轮复现，但复现轮的截图+双引号/单引号双向解析双重坐实） |
| **正常窗能否读到真实改动** | **否 —— 读不到，但也绝不假空**：正常窗下 45s 全程为诚实失败态 |
| **终态是否会自愈** | **会**：假空态在 ~T+13s 被失败横幅覆盖，~T+20s 起稳定为诚实失败态 |
| 整体 | **修复未闭环** —— 假空从「永久残留」降级为「短暂闪现」，但**仍真实存在** |

---

## 1. 环境读数

| 项 | 读数 | 判定 |
|---|---|---|
| 设备在线 | `emulator-5556 device`（同机 5554/5558/5560/5562/5564/5590 全程未触碰） | ✅ |
| AVD 名 | `emu avd name` = **Medium_Desktop** | ✅ 符合任务书 |
| 屏幕（基线） | `Physical 3840x2160` / **`Override 1366x768`** | 记录为基线 |
| 密度（基线） | `Physical 320` / **`Override 160`** | 记录为基线 |
| APK 闸门构建 | `versionName=1.4.0` `versionCode=26` **`lastUpdateTime=2026-10-02 14:27:45`** | ✅ 与任务书「22:27 闸门构建」一致 |
| reverse 端口 | `host-19 tcp:4095 tcp:4095` / `host-19 tcp:2222 tcp:22` | ✅ 非空，**无需重挂**（上一轮「开局空」告警未复现） |
| 后端 | `http://127.0.0.1:4095` Basic `opencode:opencode` | ✅ |
| 真实仓库项目 | `ae54078c0e09bf8e7cbeefd35db19aaa6b064e46`，`canonical` = `/mnt/c/sensorsdata/main/program/opencode-android-shell` | ✅ 由 `GET /api/project` 解析得到 |

> `GET /api/project` 首次返回的 `worktree` 字段全为空，需读 `canonical` 才是真实路径 —— 记为方法论注记。

---

## 2. Ground truth（host 侧，只读，未改任何代码）

任务书给的标准命令指向 `/home/xiufeigo`，但该路径是**另一个仓库**（读数 `82` / `1 file changed`），与本仓无关。按任务书「或用仓库所在路径的等价命令」，改用本仓实际路径：

```powershell
wsl -d Arch -e bash -lc "cd /mnt/c/sensorsdata/main/program/opencode-android-shell && echo '--- status count ---' && git status --porcelain | wc -l && echo '--- shortstat HEAD ---' && git diff --shortstat HEAD && echo '--- branch ---' && git rev-parse --abbrev-ref HEAD"
```

**原文输出**：

```
--- status count ---
4005
--- shortstat HEAD ---
 1191 files changed, 109218 insertions(+), 60354 deletions(-)
--- branch ---
main
```

> 说明：上一轮报告的 `3019` / `207 files changed` 与本轮 `4005` / `1191 files changed` 不同，是因为两轮之间本仓工作区**又发生了变化**（同仓多 agent 并行作业）。两轮**「存在海量未提交改动」这一事实均成立**，D11 判定不依赖具体数字，只依赖「真实有大量未提交改动」。分支 `main` 与 UI 上出现的 `main` ref chip 一致。

**ground truth 结论：工作区真实存在 1191 个文件、10.9 万行插入的未提交改动。任何 `No uncommitted changes yet`（无未提交改动）在本仓都是错误显示。**

---

## 3. 复验逐条判定

### 条目 1：窄窗超时态 — ✅ PASS（超时态文案诚实）

| 步骤 | 命令 / 读数 |
|---|---|
| 施加窄窗 | `wm size 560x768` + `wm density 160` → 复核 `Override size: 560x768` / `Override density: 160` |
| 重启 app | `am start -n com.xiufeigo.opencodeshell/.OcActivity`（`.OcActivity` 为真实 launcher，沿用 SWEEPC2 的 D7 结论） |
| 进会话 | Home 点 `E2ECHK5556d11`（运行期解析 center `116,361`）→ dump 确认窄窗 tab 式布局 `Session/Changes/Files/Terminal` |
| 点 `Changes` | 运行期解析 center `195,180` |
| **T+6s 超时态** | **`The diff request timed out or failed. Tap Refresh to try again.`** bounds `[92,458][469,474]` |

- 判定 **PASS**：超时态**诚实**呈现，与修复文档 §4.1 的 `LOAD_SLOW` 预期一致。
- 证据：`d11_a_home.png/.xml`、`d11_b_session.xml`、`d11_c_t6.png/.xml`

### 条目 2：点 `Refresh` 后的假空 — ❌ **FAIL（D11 未闭环，假空复现）**

**首轮（cycle 1）实测序列** —— 这是本轮的核心发现：

| 时刻 | UI 文案（dump 原文） | 判定 |
|---|---|---|
| Refresh 前 T+6s | `The diff request timed out or failed. Tap Refresh to try again.` | 诚实 |
| **点 Refresh 后 T+3s** | **`No uncommitted changes yet`** | ❌ **假空** |
| **点 Refresh 后 T+10s** | **`No uncommitted changes yet`** | ❌ **假空（截图双证）** |
| T+20s | `The diff request timed out or failed. Tap Refresh to try again.` | 已自愈 |
| T+38s | `The diff request timed out or failed. Tap Refresh to try again.` | 稳定诚实 |

- **假空文案原文**（dump 精确读数）：`No uncommitted changes yet`（`ui/…/res/values/strings.xml:571` = `oc_review_changes_empty_git`）
- **同时 `main` ref chip 出现**、`Branch changes` 仍为灰态，与 SWEEPC2 描述的假空形态逐项吻合。
- **证据（双证）**：
  - 截图 `d11_d_ref_now.png`（T+3s，肉眼可见 `No uncommitted changes yet` 居中大字）与 `d11_d_ref10.png`（T+10s，同一文案）——**两帧独立截图**均拍到假空。
  - dump `d11_d_ref_now.xml` 的节点文本集合（双引号 + 单引号双向解析）：`Branch changes, Changes, E2ECHK5556d11, Files, Git changes, Last turn changes, main, No uncommitted changes yet, Refresh, Session, Terminal`。
  - **状态指纹可区分**：假空帧 `17,544` B vs 失败帧 `18,928` B，两者是**不同的 UI 状态**，非 dump 抖动。
- **与 ground truth 直接冲突**：真实 `1191 files changed`，UI 却称「无未提交改动」。

**复现率（cycle 2–6，共 6 轮，每轮 1–2s 粒度连续采样 28–32s）**：

| 轮次 | 采样序列（秒=状态） | 假空 |
|---|---|---|
| 1 | 3=假空 → 20=失败 → 38=失败 | **✅ 复现** |
| 2 | 4=loading → 9=失败 → …→ 32=失败 | 否 |
| 3 | 2=loading → 9=失败 → …→ 32=失败 | 否 |
| 4 | 2=loading → 9=失败 → …→ 28=失败 | 否 |
| 5 | 2=loading → 9=失败 → …→ 28=失败 | 否 |
| 6 | 2=loading → 9=失败 → …→ 28=失败 | 否 |

- **复现率 1/6 ≈ 17%**，属**间歇性闪现**（flash-of-false-empty），不是稳定残留。
- 证据：`d11_timeline.csv`、`d11_timeline2.csv`、`d11_cycles456.csv`、`d11_c{4,5,6}_t*.xml`、`d11_p*.xml`

### 条目 3：假空的机制定位（源码级确证）

修复文档 §2.1 的纯规则（`ReviewVcsProbePlan.kt:136-148`）本轮已逐行复核：

```kotlin
fun verdict(attempts: List<Attempt>): Verdict = when {
    attempts.any { it is Attempt.Answered && it.files > 0 } -> Verdict.FILES
    attempts.none { it is Attempt.Answered }                 -> Verdict.FAILED
    attempts.any { it is Attempt.Failed }                    -> Verdict.FAILED   // ← D11
    else                                                      -> Verdict.EMPTY
}
```

真值表对**已完成的 attempts 集合**是正确的：`[Answered(0), Failed(T)]` → `FAILED`，文档真值表第 7–9 行也钉住了这一点。

**但真机上仍闪现假空，根因是「聚合时机」而非「规则」**：

1. 候选 1（陈旧 location，无 VCS provider）**毫秒级**返回 `[]` → 此刻 `attempts = [Answered(0)]`；
2. 候选 2（新鲜 graph，真实 1191 文件树）**仍在途**，20s deadline 未到 → **尚未被记为 `Failed`**；
3. 在候选 2 落定**之前**，`verdict([Answered(0)])` 走到 `else -> EMPTY` → `changesBody` 的 `else -> EMPTY` → 渲染 `No uncommitted changes yet`；
4. ~13–20s 后候选 2 超时被记为 `Failed`，`verdict` 翻成 `FAILED` → 失败横幅覆盖假空。

**结论：规则本身写对了，但缺少「仍在途（pending）」这一维度。** 走查尚未结束时，**部分候选的 `[]` 被当成全体结论**渲染 —— 这正是 D11 的原始形态（`[]` 来自「什么都不知道」的 ref），只是被 20s 的失败态**事后遮住**了。修复文档 §2.2 只在**循环结束后**才做三态 `when`，**没有让「未完成」参与判定**。

- 源码锚：`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewVcsProbePlan.kt:86-92`（`Attempt` 只有 `Answered`/`Failed`，**无 `Pending`**）、`:136-148`（`verdict`）、`ui/session/review/ReviewModels.kt:623-638`（`changesBody` 的 `else -> EMPTY`）
- 后端交叉验证：`GET /api/vcs/diff?mode=working&location[directory]=/mnt/c/…/opencode-android-shell` 本轮**同样挂起超时**（见 §5），证明候选 2 的超时是**真实的后端慢**，而非客户端误判。

### 条目 4：正常窗对照（正路径未被修坏）— ✅ PASS（诚实失败，绝不假空）

| 步骤 | 读数 |
|---|---|
| 恢复窗口 | `wm size reset` + `wm density reset` → `Physical 3840x2160` / `Physical density: 320` |
| 进会话 | Home → `E2ECHK5556d11`（运行期解析 center `281,269`） |
| 开 Review 面板 | 正常窗下为**桌面停靠式**，入口 `Toggle review panel` center `3792,276`（运行期解析） |
| 初始态 | `The diff request timed out or failed. Tap Refresh to try again.` bounds `[3436,817][3792,910]` |
| Refresh 后 T+5/15/30/45s | **4 次采样全部为诚实失败态**，`[CLEAN-COPY? NO]` |

- 判定 **PASS（诚实性维度）**：正常窗下**从未**出现假空。
- **但「能否读到真实改动」= 否**：45s 内后端 `vcs/diff` 始终未返回，UI **未列出任何真实文件**。这是**后端在本仓这个超大工作区（1191 文件）上太慢**导致的，不是本次修复引入的回归 —— 修复文档 §7 第 4 步允许「列表出现 207 个文件（恢复成功）」或「仍是失败态（诚实失败）」**二选一都算通过**，本轮落在「诚实失败」这一支。
- 证据：`d11_e_norm_home.png`、`d11_g_panel.png/.xml`、`d11_norm_t{5,15,30,45}.xml`

---

## 4. 命令清单（全部实跑）

```powershell
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5556 emu avd name                                        # Medium_Desktop
& $adb -s emulator-5556 reverse --list                                      # 4095/2222 已挂
& $adb -s emulator-5556 shell dumpsys package com.xiufeigo.opencodeshell | Select-String lastUpdateTime
wsl -d Arch -e bash -lc "cd /mnt/c/sensorsdata/main/program/opencode-android-shell && git status --porcelain | wc -l && git diff --shortstat HEAD"
& $adb -s emulator-5556 shell wm size 560x768 ; & $adb -s emulator-5556 shell wm density 160
& $adb -s emulator-5556 shell am start -n com.xiufeigo.opencodeshell/.OcActivity
& $adb -s emulator-5556 shell input tap <x> <y>                             # 坐标全部运行期从 dump 解析
& $adb -s emulator-5556 shell screencap -p /sdcard/<n>.png ; & $adb -s emulator-5556 pull …
& $adb -s emulator-5556 shell uiautomator dump /sdcard/d11_dump.xml ; & $adb -s emulator-5556 pull …
& $adb -s emulator-5556 shell wm size reset ; & $adb -s emulator-5556 shell wm density reset
```

---

## 5. 未闭环 / 待开发确认

| 项 | 状态 | 原因 / 建议 |
|---|---|---|
| **假空闪现的精确修法** | **待开发** | 建议给 `Attempt` 增加 `Pending`（或让 `verdict` 接收「是否还有候选未落定」），**走查未结束前不得渲染 `EMPTY`**；候选 2 在途时应保持 `LOADING`/`LOAD_SLOW` 而非 `EMPTY`。这与修复文档 §2.1 已有的「`[]` 从来不是干净的证据」原则同源，只是**要把它从「事后遮住」变成「事前拦住」**。 |
| **后端 `vcs/diff` 在本仓持续超时** | **未闭环（超出本任务）** | 1191 文件的超大工作区让 diff 读极慢（客户端 20s deadline 必然先到）。修复文档 §8 已把它记为 open item；本轮实测**窄窗/正常窗均复现**，是「恢复真实数据」无法达成的**上游瓶颈**。建议开发侧评估分页/惰性加载或按目录切片，否则 A2「超时→Refresh→恢复真实数据」在本仓永远走不到成功分支。 |
| **正常窗读到真实改动** | **未验到** | 受上一条阻塞（同仓 1191 文件 diff 读不回来）。判定为**非本次修复引入的回归**，但也意味着「正路径未被修坏」只在**诚实性维度**得到验证，**未在成功路径上验证**。 |
| **logcat 证据缺失** | **未取到** | 后台 `logcat -s OpenCodeShell:V` 抓取进程产出 0 字节（tag 未匹配或启动即退），未能拿到 `vcs diff failed for <ref>` 的原文行。修复文档 §8 建议的「确认 canonical ref 确为 `[]`、`<dir>/.` 确为超时」**本轮未直接确证**，机制推断依据的是**代码 + 时序 + 后端 90s 超时**三方吻合。 |
| **假空闪现是否影响其他 scope** | **未验** | 本轮只验了 `Git changes`（默认 scope）。`Last turn changes` 走 session 快照，按 `changesBody` 的分支顺序不受 VCS 失败影响（文档 §7 第 5 步已 pin），**本轮未在真机上复验**。 |

---

## 6. 终态（设备设置 / 自建对象）

**设备设置（仅 emulator-5556）**

| 设置 | 初值 | 峰值 | 终值 | 状态 |
|---|---|---|---|---|
| `wm size` | `Override 1366x768` | `Override 560x768` | `Physical 3840x2160`（**已 reset**） | ✅ 按任务书 reset |
| `wm density` | `Override 160` | `Override 160` | `Physical 320`（**已 reset**） | ✅ 按任务书 reset |
| `reverse` | 4095/2222 | 未变 | 4095/2222 | ✅ 复核通过 |

> **与上一轮的差异说明**：SWEEPC2 §4 曾把「基线 override 1366x768/160」显式还原回去；本轮任务书明确要求「`wm size reset` + `wm density reset`」，故按任务书执行 `reset`，终态为物理值。两者都满足「验完 reset」，但**终态读数与上一轮不同**，如实记录。

- 未执行：`pm clear`、`pm grant`、杀进程、`am force-stop`、`input keyevent 4`(BACK)、`input keyevent 111`(ESC)、**未点任何 Delete 文案**
- 未触碰：127.0.0.1:5555、同机其余 7 台模拟器
- 布局复核：reset 后 dump 根节点 bounds = **3840x2160** ✅（见 `d11_e_norm_home.xml`）
- `/sdcard` 残留：本轮所有 dump/截图均写入 `/sdcard/d11_dump.xml` 与 `/sdcard/d11_*.png`，见 §7

**自建对象（按纪律保留，不清理以免破坏共享后端）**

- 会话：`E2ECHK5556d11` = `ses_f02fa34b8ffeG3KL7QQ4v2811P`（本轮经 `POST /api/session` 新建，落在真实仓库项目 `ae54078c…` 内）
- 本地证据：`research/v97_verify/5556_v98/` 下 `d11_*.png` / `d11_*.xml` / `d11_timeline*.csv` / `d11_cycles456.csv` + 助手 `d11_helper.ps1` / `d11_timeline.ps1` / `d11_probe2.ps1` / `d11_cycles.ps1`

---

## 7. `/sdcard` 残留检查（收尾复核）

```powershell
& $adb -s emulator-5556 shell "ls /sdcard/ | grep -i d11 || echo 'CLEAN: no d11 residue'"
```

- **清理前**：本轮产出 9 个文件 —— `d11_a_home.png`、`d11_c_t6.png`、`d11_d_ref10.png`、`d11_d_ref_now.png`、`d11_d_ref_p20.png`、`d11_d_ref_p30.png`、`d11_e_norm_home.png`、`d11_g_panel.png`、`d11_dump.xml`。
- **清理命令**：`rm -f` 逐个精确删除上述文件（**未使用通配递归删除**，只针对本轮自建的确切文件名）。
- **清理后读数**：仅剩 `d11.xml`（`2026-09-24 12:59`，**早于本轮**，为历史遗留，非本轮产物；按「不越权清理他人产物」纪律**保留不动**）。
- **判定**：**✅ 本轮 `/sdcard` 产物已全部清除，无本轮残留。**
- 注：`/sdcard` 上另有大量 `c2_*` / `tb_*` / `v5*_*` 等历史文件，属**同机其他轮次 agent 的产物**，本轮**未触碰**（不越权清理）。

---

## 8. 后端 ground truth 交叉验证

```powershell
$u = "http://127.0.0.1:4095/api/vcs/diff?mode=working&location%5Bdirectory%5D=%2Fmnt%2Fc%2Fsensorsdata%2Fmain%2Fprogram%2Fopencode-android-shell"
Invoke-RestMethod -Uri $u -Headers @{Authorization="Basic ..."} -TimeoutSec 90
```

**原文输出**：

```
backend vcs/diff FAILED after 90s : The request was canceled due to the configured HttpClient.Timeout of 90 seconds elapsing.
```

- **判定**：后端 `vcs/diff` 在 **90s** 内**仍未返回**，与上一轮 SWEEPC2 的 **60.2s 超时**一致（本轮放宽到 90s 仍然超时，说明**不是客户端 20s deadline 太短**的问题，而是**后端在这个 1191 文件的工作区上确实读不出来**）。
- **意义**：这坐实了「新鲜 ref 超时」是**真实的后端慢**，而不是客户端误判 —— 正是它让「候选 2 长期在途」，进而撑开了 §3 条目 3 的假空闪现窗口。
- 同时也解释了条目 4 中「正常窗读不到真实改动」：**后端根本读不出来，UI 只能诚实报失败**。
