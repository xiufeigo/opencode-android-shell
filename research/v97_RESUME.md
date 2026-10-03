# v97 RESUME — 重启 Harness 后的接手说明（2026-10-02 17:40）

> 用法：Harness 重启后，在 GUI 里打开本会话（session `session-7fd8879d-9edb-481c-9e1c-2c5bc1fe31be`）说一句「继续」。
> 接手者：Lead。先读本文件 + `research/v97_team_brief.md` + `research/HANDOFF_PROMPT.md`。

---

## 0. 为什么重启

用户指令：所有子 agent 必须用 `minimax-cn/MiniMax-M3.1-Flash-Preview`（测试 default 强度 / 修复 max 强度）。

**实测被 harness 拒**：`LLM provider "minimax-cn" is not allowed for this Session` / `child LLM route "minimax-cn/MiniMax-M3.1-Flash-Preview" is not allowed for this Session`（探针两次）。

**根因**：`C:\Users\xiufe\.dsh\profiles\desktop\cordis.patch.yml` 的 `subagent-model-selection-settings.allowedModels` 里**确实有** minimax-cn 两条（第 449-452 行），但**该文件最后修改 17:11:30，而 Harness 进程 16:16:30 启动** —— 运行中的进程用的是旧白名单快照。重启即生效。

**重启后第一件事**：用 `list_subagent_models(provider="minimax-cn")` 与一个探针 subagent 验证 minimax 可用；可用则**全部路由切回 minimax-cn/MiniMax-M3.1-Flash-Preview**（测试 default、修复 max），并在报告里更新模型口径。

## 1. 重启前的状态（截至 17:40）

| 项 | 状态 |
|---|---|
| 统一闸门（Lead，17:18） | **GREEN**：612 suites / 5529 tests / 0 failures / 0 errors / 0 skipped；`:app:assembleDebug` SUCCESS |
| APK | `app/build/outputs/apk/debug/app-debug.apk` 17:18:51，36902739 B，versionName 1.4.0 |
| **闸门之后落的改动（尚未过全量闸门）** | ①`app/build.gradle.kts` 加 `org.bouncycastle:bcprov-jdk18on:1.78.1`；②`SshConnection.kt` 新增 `routeEd25519ThroughBouncyCastle()` 并在 `connect()` 里调用（F14b 产品代码半）；③`AgentChip.kt`/`Composer.kt` 焦点归还（残留批）；④新增 3 个测试类：`SshEd25519IdentityF14Test`（4 例）、`AgentChipFocusRestoreV97Test`（4 例）、`HomeProjectRowSeamV97Test`（2 例） |
| 局部验证 | SSH 家族 10 个测试类（含 F14 两个新类）17:37 跑过 **BUILD SUCCESSFUL**；残留批 6 个测试类 47 例 0 挂 |
| **下一步必须先做** | **重跑全量闸门**（`cleanTestDebugUnitTest` + `testDebugUnitTest` + `assembleDebug`），确认 tests/suites 只增不减（目标 ≥612/5529），然后**重新装机 5 台** |
| `.agent-gradle-lock` | 重启前已释放；若残留（被 kill 的 gradle 留下），先删掉再跑 |

## 2. 模拟器舰队（serial 会随启动顺序重排，**用前必须 `emu avd name` 核**）

17:20 实测映射（重启后可能变，但进程若不重启则不变）：

| serial | AVD | 形态 | 用途 |
|---|---|---|---|
| emulator-5554 | Pixel_9a | 1080x2424@420 | **v97 F14 终验**（SSH 域） |
| emulator-5556 | Medium_Desktop | 1366x768@160 | v82 设置弹层 IME 五条口径 |
| emulator-5558 | Pixel_10_Pro_XL | — | 悬挂项（v74 Stop / v75 尾空白 / v71 × 命中） |
| emulator-5560 | Pixel_Tablet | 2560x1600@320 | 全功能扫测 A（Home/导航/设置/命令面板） |
| emulator-5562 | Small_Desktop | 1366x768@160 | 全功能扫测 B（会话/composer/时间线/终端/通知） |

5 台都装过 17:18 的 APK、都配了 `reverse tcp:4095`/`tcp:2222`；桌面机设了 `show_ime_with_hard_keyboard=1`。
**注意：重启 Harness 不影响模拟器进程**（它们是 Start-Process 分离起的），但**APK 是旧的**（不含 F14b 的 bcprov+路由）——必须重新 `assembleDebug` + 装机，否则 F14b/F12a 口径必败。

后端：`http://127.0.0.1:4095`（`opencode serve 2.0.18`，WSL Arch），Basic `opencode:opencode`，健康检查只用 `GET /api/info`。

## 3. 本轮已完成的工作（证据都在盘上）

### F14a（SSH key 文件错误归因）— 已落地并并入闸门
`SshErrorCode.KEY_FILE` + `sshFailure()` 分支 + 三语文案 `ssh_error_key_file` + `SshKeyFileFailureF14Test`（7 例）。Lead 已复核：错误码 → `SshModel.messageFor` → `strings.xml` 三语齐全，链路完整。

### F14b（jsch ed25519 公钥不被 offer）— **假设证伪 + 真根因坐实 + 半修复**
- 「缺 `net.i2p.crypto:eddsa`」**证伪**：jsch-0.2.24.jar 内零 `net/i2p` 引用、POM 无依赖段。
- **真根因**：Android 上两条 ed25519 签名路径全灭 —— 默认 `ssh-ed25519` 指向 `com.jcraft.jsch.jce.SignatureEd25519`，其 jar 基线 entry 是 Java15+ 桩（D8 只打基线，桩字符串已在装机 APK `classes19.dex` 里取证），备胎 `bc.SignatureEd25519` 委托 bcprov 而 APK 无 BC → `KeyPairEdDSA.getSignature()` 吞异常返回 null → identity 被静默跳过 → 服务端回落 keyboard-interactive。
- **已做**：`app/build.gradle.kts` 加 `org.bouncycastle:bcprov-jdk18on:1.78.1`（离线缓存已有）；Lead 落产品代码 `SshConnection.routeEd25519ThroughBouncyCastle()`（`JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")` + `keypairgen.eddsa` → bc 版），在 `connect()` 里调用，once-per-process 守卫，避免污染 JVM 测试的静态 config 表（`SshEd25519IdentityF14Test` 第 4 例 pin 的就是 stock 默认值，仍绿）。
- **待办**：新 APK 装机后按 F14b+F12a 口径真机终验（KEY 条目 + app 私有目录 key + **passphrase 留空** → 弹卡输 `keypass123` → Connected + sshd `Accepted publickey`）。
- 报告：`research/v97_verify/f14b/REPORT.md`（含 javap/jar/APK dex 全部静态证据）。
- 体积代价：bcprov 混淆前进包约 +8MB。

### 残留批（v57 移交项）— DONE
- `AgentChip.kt` agent 弹层焦点归还（桶B 模式：closeReason 三路 + 只在真的关过一次后归还 + `imeVisibleAtOpen` 补键盘）；`Composer.kt` 调用点接 `onRestoreFocus`。
- `HomeProjectsList.kt` ROW_GAP 死缝：**已结构性消除**，本轮只补取证 pin。
- 报告：`research/v97_verify/residue/REPORT.md`；新增 `AgentChipFocusRestoreV97Test`(4) + `HomeProjectRowSeamV97Test`(2)。
- **未闭环新发现**：`AgentChip.kt:143-145` 的 `/agent` 打开请求仍是 v74 形状（`if (openRequest > 0) openMenu()`，重建即凭空重弹）—— 照 `ModelChip` 的 `modelOpenRequestArrived` 修法（v75 P2）。**下一轮派单**。

## 4. 本轮已拿到的真机发现（**部分为 FAIL，需派修复**）

### 4.1 P0 候选：`编辑项目` 面板全禁用（5560 Pixel_Tablet 实测）
- 现象：项目 ⋮ → `编辑项目` 打开后，面板内**两个 EditText 均 `enabled=false focusable=false`**，`保存` 亦禁用，4 种输入手法零字符落地；面板显示「该项目未在服务器注册」。
- 代码位置：`ui/home/HomeProjectEditDialog.kt:222` `val editable = state?.projectID != null && !saving`，`保存` 门 `:266` 同源。
- 判读（待确认）：**未在服务器注册的项目**（本地目录，projectID==null）整面板禁用 —— 这正是测试员报的 FAIL。需对照上游 `_upstream` 的 project-edit 是否允许本地改名/改图标（若上游允许 → 真缺口，P1）。
- 证据：`research/v97_verify/5560_sweepA/REPORT.md` 1.8/1.9 行 + `09_proj_edit.png` / `10d_editdlg.xml`。

### 4.2 其余 5560 扫测结论（已 PASS 的项见报告）
Home 项目列表/新建项目/目录选择器（`~` 非死区、两段确认）/删除确认与不复活/⋮ 菜单六项 全 PASS；铅笔图标语义=「在该项目新建会话」（澄清）。

### 4.3 Pixel_9a 准备段已全绿（测试脚本 12 个已就绪）
`research/v97_verify/5554/`：`E2ECHK5554_run.ps1`（公共库）+ `provision_key` / `f14a` / `f14b_f12a` / `f13b` / `f12b` / `v82five` 骨架。
- 测试钥已在 app 私有目录（md5 `0120a827…` 与 repo 一致）；阴性对照成立（`/sdcard/v95/id_ed25519` → Permission denied，F14a 情境可用）。
- **该测试员需要 Lead 提供**：task-2 的「v82 五条口径」原文（它没有 team_task 工具），填完 `TODO(TASK-2 TEXT)` 才能跑 S3/S5。原文在 `research/v97_team_brief.md` § 与 `HANDOFF.md` v82 段。

## 5. 重启后待办队列（按优先级）

1. **验证 minimax 路由可用**（`list_subagent_models` + 探针）；可用则本轮所有派单一律 `minimax-cn/MiniMax-M3.1-Flash-Preview`（测试 default、修复 max）。
2. **重跑全量闸门**（先清 `.agent-gradle-lock` 残留）→ 目标 ≥612 suites / ≥5529 tests / 0/0/0；然后 `assembleDebug`。
3. **重新装机 5 台**（先 `emu avd name` 核 serial↔AVD）。
4. **v97 真机终验**（Pixel_9a，跑现成脚本）：`provision_key` → F14a → F14b+F12a（先 `-RehearseOnly`）→ F13b → F12b → 清理。
5. **`编辑项目` 禁用面板**：先对照上游定性（上游允许本地编辑 → 派修复；上游同款 → 记有意偏离结案）。
6. **`AgentChip` `/agent` 重弹**：派 composer 域修复。
7. **收口其余在跑的扫测**（5560 扫测 A 报告未完；5556/5558/5562 三台在重启瞬间被我杀掉，需重派或在报告里标注中断）。
8. **更新 `research/HANDOFF.md` + `HANDOFF_PROMPT.md`**：F14a/F14b 销账、eddsa 结论（证伪）、bcprov 依赖、模型口径与白名单陷阱、new findings。

## 6. 任务板状态

| task | 主题 | 状态 |
|---|---|---|
| task-1 | F14b jsch ed25519 | 交付完成（subagent 无 team 工具，**Lead 需代 `complete`**） |
| task-2 | v97 真机终验（Pixel_9a） | 准备段 DONE，验证段待新 APK |
| task-3 | 上游 v2 对照缺口清单 | 进行中（`research/v97_audit/`，已落 `_openapi_snapshot.json`） |
| task-4 | 残留修复批（v57 移交项） | DONE（**Lead 需代 `complete`**） |
| task-5 | v82 上机复验（5556） | 被重启中断，需重派 |
| task-6 | 悬挂项复验（5558） | 被重启中断，需重派 |
| task-7 | 全功能扫测 A（5560） | 进行中（报告已写到 §1.12） |
| task-8 | 全功能扫测 B（5562） | 被重启中断，需重派 |

## 7. 血泪提醒（本轮新增）

- **子 agent 没有 `team_task_get/claim/update` 工具**，任务书里别写这些命令（会被误当 shell 命令，浪费步数甚至导致失败）。任务全文直接贴进 prompt。
- **子 agent 有失败率**：本轮 4 路里 3 路「failed before finish / no closing message」。对策：任务书写明「边做边把证据写盘」，并把交付物路径写死在任务书里；失败就重派。
- 用户要求 model 走 minimax-cn —— 白名单更新后**必须重启 Harness** 才生效（进程启动时间 vs `cordis.patch.yml` mtime）。
