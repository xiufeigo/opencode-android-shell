# v98 RESUME — 2026-10-02 20:30 快照（Lead 交接）

> 用法：本会话若中断/换会话，接手者先读本文件，再读 `research/v97_RESUME.md`、`research/v97_team_brief.md`、`research/HANDOFF.md`。
> **工具口径已变更**：自 18:4x 起，**修复与测试一律通过 `mcode` 调用**（见 §6）。

---

## 1. 闸门与产物

| 轮次 | 命令 | 结果 | 日志 |
|---|---|---|---|
| v98b | cleanTestDebugUnitTest + testDebugUnitTest + assembleDebug | **622 suites / 5615 tests / 0/0/0**，APK md5 `482c5d3e…`（19:38） | `research/v97_verify/GATE_v98b.md` |
| v98c | 同上 | **627 suites / 5685 tests / 1 failure**（陈旧 pin，已修） | `research/v97_verify/GATE_v98c.md` |
| **v98d（当前基线）** | 同上 | **627 suites / 5685 tests / 0 failures / 0 errors / 0 skipped**，APK 44,198,562 B / 20:24:52 / md5 **`886653d4a21bd158a9ae87429c7f5261`** | `research/v97_verify/GATE_v98d.md` |

**6 台设备都已装 v98d 包**：`5554=Pixel_9a`、`5556=Medium_Desktop`、`5558=Pixel_10_Pro_XL`、`5560=Pixel_Tablet`、`5562=Small_Desktop`、`5564=Pixel_10_Pro_Fold`（全部挂了 `reverse tcp:4095` / `tcp:2222`）。

## 2. 本轮已真机验证 PASS 的项

| 项 | 设备 | 结论 | 报告 |
|---|---|---|---|
| **F14b（bcprov/ed25519 路由）** | 5554 **与** 5558 | **两次独立 PASS：走 `Accepted publickey`（ED25519 SHA256:sYFitiUL7Z…），未回落 keyboard-interactive** | `research/v97_verify/5554_f14/REPORT.md`、`.../5558_f14b/REPORT.md` |
| F14a（私钥不可读专属文案） | 5554 + 5558 | PASS（逐字命中 `Can't read the private key file…`） | 同上 |
| 冷启动恢复上次 session route/tab | 5560 | PASS（4 步全绿：恢复 session / 对照组可区分 / dead id 优雅回落） | `research/v97_verify/5560_v98/REPORT.md` |
| 标题热区 + 时间线搜索 | 5554 | PASS（空白区不再开编辑器、点文字仍开；搜索能随 query 重算并复位；标题无污染） | `research/v97_verify/5554_v98/REPORT.md` |
| `编辑项目` 本地 meta（A 组） | 5564 | A1–A10 PASS + C15/C16 PASS + D17/D18/D19/D22 PASS | `research/v97_verify/5564/REPORT.md` |
| **自定义图标落盘**（B11 修复） | 5564 | **PASS**（草稿态/保存后 Home 行/强杀重启/清除图片 四条全绿） | `research/v97_verify/5564/REPORT_ICON.md` |
| AgentChip `/agent` 不重弹 | 5564 | 三条核心全 PASS | 同上 |
| v82 五条口径 | 5556 | S1/S2/S4/S5 PASS；S3 PASS（按意图，Lead 已裁决） | `research/v97_verify/5556_v82/REPORT.md` |
| 三条悬挂项（v74 Stop / v75 尾空格 / v71 × 命中） | 5558 | 三条全 PASS | `research/v97_verify/5558/REPORT.md` |

## 3. 本轮落地的修复（全部已进 v98d 包，除注明者）

`research/v97_fix/` 下逐项有报告与 diff：
1. **bcprov 打包冲突**（`PACKAGING_FIX.md`）：jsch 与 bcprov 都带 `META-INF/versions/{9,11,15}/OSGI-INF/MANIFEST.MF` → `app/build.gradle.kts:72-87` 精确排除三条元数据。
2. **`编辑项目` 本地 meta 写通道**（`PROJECT_EDIT_LOCAL_META.md`）：未注册项目可改名/改色/改启动脚本，字段级浅合并，展示侧「无服务器 id 才用本地 meta」。
3. **图标落盘**（`PROJECT_ICON_PERSIST.md`）：根因＝`data:` URL 超出单个 SharedPreferences 值容量被静默丢弃 → `ProjectIconImage` 加 48KB 预算 + 重编码降采样。
4. **AgentChip `/agent` 闩**（`AGENTCHIP_APPLY.md`）。
5. **冷启动 route 恢复**（`LAST_ROUTE_RESTORE_FIX.md`）：写回监听器在读回前把 home 写回 → 加 `navRestoreSettled` 门控。
6. **标题点击热区**（`SESSION_SEARCH_FIX.md`）：`.width()` → `.widthIn(max=…)`（恢复上游 `w-fit`）。
7. **设置 IME-down 几何**（`SETTINGS_IME_DOWN_GEOMETRY.md`）：**非回归**，是 `screenHeightDp 720→672`（系统条 48→96px）导致；生产代码零改动。
8. **项目编辑 parity**（`PROJECT_PARITY_FIX.md`）：未注册项目不再显示「刷新工作区」；点击已上传图标即清除；补 `$OPENCODE_WORKTREE_BASE/PATH` 提示（三语同步）。
9. **Review 面板**（`REVIEW_FIX.md` + `REVIEW_PIN_DRIFT_FIX.md`）：超时后能真正重发；新增 `ReviewVcsProbePlan`；Files 过滤递归；Home 搜索浮层遮挡。
10. **标题编辑器丢弃语义**（`TITLE_EDITOR_DISCARD_FIX.md`）：离开/失焦改为丢弃（对齐上游），回车仍提交。
11. **SSH 重连保真 D1/D2/D3/F12b**（`SSH_RECONNECT_FIX.md`）：重连复用私钥、成功不再报失败、删除弹窗可响应、Cancel 语义恢复。

## 4. 被证伪 / 已裁决（**不要重复"修"**）
- **通知域 N7 = 误报**（`NOTIFICATION_DOMAIN.md`）：链路完整；测试员的 prompt 4 秒内没 idle，四个分支都没命中。补：`notifyErrors` 默认 **false**（对齐上游），验失败事件要先手动开。
- **设置弹层 IME 位移 = 有意设计**（`V82_FIVE_CRITERIA.md` 的 Lead 裁决）：S3 按意图 PASS。
- **v82 Ctrl+Shift+P 口径错**：正确命令是 `input keycombination 113 59 44`（`113 44` 是 Ctrl+P → files-only 面板，按设计）。
- **5562「搜索三连高危」= 测试台点偏**（点到了标题）：真缺陷只是标题热区过大（已修）。
- **Review N4/N5 = 误判**（`REVIEW_HOME_SEARCH_DIAG.md`）：空态与列表同组件互斥；2 行是软键盘挤压；Refresh 只 evict 是设计。

## 5. 未闭环（下轮队列）
1. **移动抽屉（N-3）修复被证伪 + 移动形态空壳**（`5560_v98/5560_v98_drawer2/REPORT_DRAWER2.md`）：
   - 上一版"覆盖层"修复**无效**（CompositionLocal 不重父化，`Box(fillMaxSize())` 仍是 Column flow 子节点）；
   - **更严重**：`标签页布局=垂直` **且** `wm 1200x1600` + `wm density 320` 同时成立时 app **必现空壳**（单独任一项正常；logcat 无 FATAL/Exception）；该组合在上一版修复前是可用的 → 疑似回归。
   - 已派 `MOBILE_DRAWER_FIX2`（修复中）。
2. **F13b 密码认证**：host 侧已证实 `sshtest/opencode` 密码可用（`Accepted password`），所以设备侧失败是 **app 侧**；已随 D1/D2 修复派复验。
3. 扫测遗留（`5556_v98/REPORT_SWEEPC.md`、`5562_v98/REPORT_SWEEPD.md`）：Import 成功路径、Undo 真实语义、Background shells 面板、Home 最近关闭空态、Review resize 把手、`>_` 非空态；窄窗 560x768 的 Changes 超时（本轮刻意未做 wm override，下轮必做）、N6 截断的服务端机制定位。
4. `编辑项目` 未做项：D20/D21 强杀重启与"本地副本不遮蔽服务器"的反证（需构造）、E 组装机兼容性。

## 6. 工具口径（**重要**）
- **一律用 mcode**（用户 18:4x 指示；token 无限、可大规模并行，用户明确要求"能花多少就花多少"）：
  ```powershell
  $prompt | & 'C:\Users\xiufe\.minimax-code\mcode.cmd' exec --input - --cwd 'C:\sensorsdata\main\program\opencode-android-shell' `
      --permission full --timeout 45m --max-steps 120 --output-format text --prompt-mode coding
  ```
- ⚠️ **必须 `--input -` 从 stdin 喂多行 prompt**（当参数传会被截断在第一行）。
- ⚠️ **gradle 必须在 mcode 那一轮里前台同步跑完**：把 gradle 当后台任务 / "stand down 等通知" → mcode 进程结束会**杀掉 gradle 子进程并清空测试结果**（已踩两次）。
- ⚠️ **mcode 没有"中途 steer"**；派单要一次写全。`send_message` 对 mcode 上下文不适用（harness 的 subagent id 也不接受 `send_message`：`active teammate not found`）。
- 并发纪律：**gradle 任务之间用 `.agent-gradle-lock` 串行**；**一台设备一个 agent**；写盘范围在任务书里写死。

## 8. 本轮最终结果（21:40 收口快照）

**树自 `gate v98g`（21:18）之后未再改动**（Lead 已按 mtime 复核 `app/src` 无新写入）→ **当前 APK = v98g 包**，
**6 台设备全部装的就是它**（md5 `ccb2a3c7afdb086cfe39c17ea1454344`，44,198,562 B，21:18:44）。

### 8.1 闸门（终）
`cleanTestDebugUnitTest + testDebugUnitTest + assembleDebug` → **628 suites / 5718 tests / 0 failures / 0 errors / 0 skipped**
（`research/v97_verify/GATE_v98g.md`）。

### 8.2 最后一轮真机复验
| 项 | 设备 | 结论 | 报告 |
|---|---|---|---|
| **D3 删除确认条"滚进视野"** | 5554 | **核心缺陷闭环**：D3-1 第 1 行 PASS、**D3-2 第 2 行 PASS（两轮独立复现，几何读数一致）**、D3-5 反例 PASS、D3-6 清理 PASS；D3-3（第 3 行及以后）与 D3-4（三态）**未验到**（条目没建成）→ 覆盖度缺口，不阻塞 | `5554_v98d3/REPORT.md` |
| **B4 标题导航离开丢弃（fix3）** | 5558 | **B4 主判据 PASS**（点 Home 后标题未改、草稿丢弃）、B5 回车提交 PASS、tab 切换变体 PASS、强杀重启 PASS；第 4 条"点外部变体"为**半 PASS**（与任务书断言有出入，见报告 §5.4） | `5558_v98b4/REPORT.md` |
| **移动抽屉（fix2 之后）** | 5560 | **❌ FAIL — 缺陷：移动形态下抽屉入口不可达**。空壳回归**已消失**（89 节点、关闭态标签条 `y=1452..1548` = 底部 ✅）；**唯一入口**是"开会话后底部标签条上的会话槽位"（全 App 唯一 `drawerOpen=true`，且仅 `session != null` 时存在；无手势/无其它图标入口），7 次点击前后签名不变 → **打开态仍全部未验到** | `5560_v98/REPORT_DRAWER4.md`（116 个证据在 `ev4/`） |

## 9. 目标续跑轮（22:00–22:40）新增进展

### 9.1 闸门链继续推进（全部 `cleanTestDebugUnitTest + testDebugUnitTest + assembleDebug`）
| 轮 | suites/tests | 结果 | APK md5 |
|---|---|---|---|
| v98g | 628 / 5718 | 绿 | `ccb2a3c7…` |
| v98h | 628 / 5722 | 绿（含抽屉入口 fix3） | `b28e961d…` |
| **v98i（当前）** | **630 / 5735** | **0/0/0** | **`567b5c2cb31f6f90e7836cbba45e79dd`**（22:27:35） |

### 9.2 移动抽屉 N-3：**闭环 PASS**（`5560_v98/REPORT_DRAWER5.md`）
- fix3 真因：打开状态是"条内局部 `drawerOpen`"与"宿主 `MobileDrawerMount.open`"**两个对象**、靠 `SideEffect` 延后搬运 → 改 `ui/nav/BottomTabStrip.kt`（`ui/App.kt` 零改动）。
- 复验：**正向对照重建成功**（同一条上的非抽屉元素**有响应** → 不是"整条无响应"）；**抽屉打开成功**（4 条判据全中）；**打开态几何 5/5 全过**：条仍 `y=1452..1548`（无 `0..84` 回归）、NavHost 未被压零、状态栏未遮挡、开/关条 y 一致、scrim 点 `(600,600)` 精确回到关闭态。
- **两个测量陷阱（写进纪律）**：①**软键盘未收时点标签条会全部 `diff=0`**，会假造"整条无响应"——复验前必须先断言 `mInputShown=false`；②**条 y 在弹层态会变**（既有行为），读打开态条 y 时要记住这个混淆项。
- **证伪一条**：fix3 报告称 a11y 标签挂在 `ACTION_CLICK` 节点上 —— 真机证伪（标签在 `clickable=false` 的子节点上，可点槽位 `content-desc` 为空）→ 读屏/自动化**不能**用"可点且有名字"定位该入口。

### 9.3 扫测 C 第二轮（`5556_v98/REPORT_SWEEPC2.md`）的关键结论
- **Import 成功路径 PASS**（导出 999B 真实落盘 → Import → 出现第 2 个 tab + 后端新增会话 `ses_01a0fce2…`）。
- **D11（高）已修**：窄窗 `560x768` 下 Review→Changes 超时后点 Refresh 会宣称 `No uncommitted changes yet`，而 host 真值是 **3019 条 / 207 files / 58099 insertions** → **假空**（比持续报错更危险）。根因：候选 ref 判定把"最后一个有返回的 ref 的 `[]`"当结论，能证伪它的新鲜 ref 超时未参与判定。→ `REVIEW_DIFF_HONESTY_FIX.md`（116 例 0 失败，**零新增字符串**）。
- **D10 = 非缺陷**（三个复制按钮无可见反馈；结论见 `REVIEW_DIFF_HONESTY_FIX.md` §5）。
- **D8（低）已修**：persistent session terminal 输入不达 PTY（dock 临时终端正常）→ `TERMINAL_INPUT_FIX.md`（273 例 0 失败）。
- **D9（方法论陷阱）**：`uiautomator dump` 的 `text` 属性**可能是单引号** → 只匹配 `text="…"` 的解析脚本会**静默漏读**（Compose EditText 尤其明显）。**解析必须同时匹配单双引号。**
- 更正上轮结论：**`Background shells` 入口确实存在**。

### 9.4 设备与环境状态
- **5560（Pixel_Tablet）在移动形态长测后 console 无响应**（`adb devices` 仍显示 device，但 `adb shell`/`emu avd name` 均挂起）→ 已把该设备**移出舰队**（未强杀进程，留给用户处置）。可能与"移动形态下 ~45s 交互延迟"同源，**未定论**，记为观察项。
- 舰队现有 **5 台健康设备**：5554 / 5556 / 5558 / 5562 / 5564，**全部装 v98i**。
- 另外两台 `emulator-5586 (MCRemote_Clone)`、`emulator-5590 (DSH_Class_Test)` **不是本项目的**（用户其它工作），未触碰。
- **D3 覆盖度仍未补完**（`5554_v98d3/REPORT_COVERAGE.md`：条目凑不满 4 行 → 第 3/4 行与三态**未验到**；核心缺陷 D3-1/D3-2 已在两轮复现中 PASS）。

### 9.5 正在进行
- D11 真机复验（5556，窄窗 + host ground truth + 正常窗对照）。
- D8 真机复验（5562，dock 对照 + persistent + 切 tab）。

## 10. 目标续跑轮 2（22:55–23:50）

### 10.1 闸门链
| 轮 | suites/tests | 结果 | APK md5 |
|---|---|---|---|
| v98j | 631 / 5743 | 绿 | `f3b54bcc…`（22:57:58） |
| **v98k（当前）** | **632 / 5753** | **0/0/0** | **`f29d7d9fd3eef78843fde43b4a98a468`**（23:20:41） |

设备：5556 / 5558 / 5562 / 5564 = **v98k**；**5554 仍是 v98j**（它的测试员当时在跑，未打扰）→ 下轮补装。

### 10.2 本轮闭环
- **D11（Review→Changes 假空）：真机 PASS 闭环**（`5556_v98/REPORT_D11B.md`）：**3 轮 × 5 个时间点（T+1/3/10/20/40s）= 15 次采样，假空 0 次**；正常窗对照另 5 次也 0 次，全程**诚实失败**、从不宣称"无未提交改动"。
  两版修复的根因（记录在案）：①第一版让"持续性假空"变诚实失败；②**第二版**才是真因——`reviewVcsInfo` 把一个只答过 `vcs/status` 的 ref 写进 diff 走查记忆 → 走查被压成单候选 → 毫秒级 `[]` 被 `verdict` 提升为 `EMPTY`。
- **"~45s 交互延迟"= 测量假象，已定论**（`5564_v98/REPORT_PERF.md`）：移动形态 Δ 中位数 **2.462s** vs 默认形态 **2.666s**（移动不更慢；该值已逼近 `uiautomator dump` 自身 ~2.2s 地板）。关键反证：被判"30s 无响应"的样本，其 UI 层级 **14 次轮询（28s）恒定不变**，而同期 `gfxinfo` 有 **196 帧、p99=101ms**、logcat **零** Choreographer/ANR → **不是忙循环、不是宿主竞争**，而是**点击落在死区**。
- **由上面顺带挖出的真缺陷：命令面板打开后遮罩吞掉底栏点击**（两种形态 100% 复现）→ 已修（`PALETTE_DEADZONE_FIX.md`：`CommandPalette` **未上报卡片边界**，遮罩退化用**全宽布局盒**判定"点外部关闭"，底栏落在盒内被静默吞掉；201 例 0 失败）。**真机复验：第一轮在 5562（窗口化桌面）结论不成立**（有的点击落到启动器，无法归因 app）→ **已在全屏设备 5558 重验（进行中）**。
- **D8（persistent terminal 输入）** 与 **移动抽屉 N-3** 已在上一轮闭环，本轮无回退。

### 10.3 ⚠️ 设备复验可靠性阻塞（三轮同签名，必须让用户知道）
**现象**：`com.xiufeigo.opencodeshell` **进程活着（状态 R）、但画不出首帧** —— 卡在 Splash Screen 窗口、`gfxinfo` **零帧**、`mCurrentFocus=null`；logcat 里是 `ANR in com.xiufeigo.opencodeshell` + `failed to complete startup`，主线程陷在 **ART 类校验**、被 CPU 抢占到 **19–142 bytecodes/s**。
**三轮同签名**：5558（第 2 轮）→ 5554（第 3 轮）→ 5558（第 4 轮，`5558_v98/REPORT_PALETTE2.md`）。
**归因（证据支持）**：**宿主 CPU 竞争 + 首次启动的 dex 校验成本**叠加：
- 宿主当时 **CPU 72%、8 台模拟器**（6 台本项目 + 2 台用户的 `MCRemote_Clone`/`DSH_Class_Test`/`5600`/`5602`）；另一轮测试员另记 **冷启动 >5.5 分钟**。
- 本包自 F14b 起带上 **bcprov（+4.5MB 类）**，首次冷启动的类校验量显著变大 —— **在真机上是"首次启动很慢"的一次性成本，在负载高的宿主上会直接表现为启动期 ANR**。
**已做的处置**：把 wedge 的 **5560（Pixel_Tablet）退掉**（`emu kill` 挂死 → 核对 PID 704 命令行确为 `Pixel_Tablet` 后 `Stop-Process`），舰队回到**恰好 5 台**；但负载仍受用户其它会话影响，设备端结论**时好时坏**（同一时刻 5 台 `echo ok` 都能过，下一次冷启动又卡死）。
**后果**：**命令面板死区修复（v98k 内）没有任何设备端结论** —— 不是 PASS、不是 FAIL，是**测不了**。
**建议**：① 做设备复验前把宿主降到**仅本项目 5 台模拟器、无其它 agent 竞争**；② 冷启动给足 **≥3 分钟** 并轮询"首帧"而不是固定 sleep；③ 若要根治首次启动慢，可另开一轮评估 **bcprov 裁剪 / debug 包 R8 收缩 / baseline profile**。

### 10.4 命令面板死区的复验过程（重要！含一条方法论反例）
- 第 1 轮（5562，**窗口化桌面**）：**结论不成立** —— 取证显示有的点击落到启动器抽屉，无法归因 app（`5562_v98/REPORT_PALETTE.md`）。
- 第 2 轮（5558，全屏手机）：**app 反复 ANR**，主线程不处理输入 → **无法区分"死区吞点击"与"app 已死"**，判「未判定」（`5558_v98/REPORT_PALETTE.md`）。
- ⚠️ **第 2 轮给出的方法论反例（务必记住）**：**"dump 签名不变 / dump 取不到"不能单独作为"存在死区"的证据** —— ANR 也会产生同样的读法。这直接说明 `5564_v98/REPORT_PERF.md` §6.1 那条"死区"论证**证据链不充分**，所以**原缺陷是否真实存在仍未定论**（修复本身在代码层面是合理的：遮罩确实曾用全宽布局盒判定"点外部关闭"）。
- 第 3 轮（5554，全屏手机）：**先做 ANR 排除（正向对照）再做死区判定**，并要求"签名不变"必须再用**截图像素差分 + logcat 输入事件 + gfxinfo 帧数**三路交叉才算 FAIL。
- **5558 上 app 反复 ANR** 本身是一条**待查的环境/性能问题**（宿主当前 8 台模拟器、冷启动 >5.5 分钟；是宿主过载还是 app 主线程问题未定论），已记入下轮队列。

### 10.5 仍未闭环
- **命令面板死区**：见 §10.4（三轮复验的完整过程与结论）。
- **D3 覆盖度（第三次未成）**：`5554_v98d3/REPORT_COVERAGE2.md` 本轮**连一条自建条目都没建成**（0 条），第 3/4 行与三态**仍全部未验到**；报告另记两条未定论：面板 body 行容量未测得、**"表单是否缺认证方式控件"未定论**、以及 `nc` 测设备→主机 sshd 握手**无 banner**（该测法本身未必有效）。**D3 修复本体（D3-1/D3-2）仍是两轮 PASS，本轮无新证据、不判回退。**
- **环境观察**：宿主上跑着 **8 台模拟器**（含用户另外两台 `MCRemote_Clone`/`DSH_Class_Test`），**app 冷启动被拖到 >5.5 分钟**；`5562` 的 `/sdcard` 有 809 个历史取证文件（往轮产物，未删）。
- B4"点外部变体"产品口径、扫测遗留（`REPORT_SWEEPD.md` U1–U15、`REPORT_SWEEPC/C2` 剩余项）。

### 10.6 剩余队列（承接 §9.6）
1. **移动抽屉入口** —— **本轮已闭环 PASS**（见 §9.2）；剩余小项：**2 个及以上 tab 时**抽屉能否打开（代码路径相同，真机未覆盖）；设备 5560 已 wedge，复验需另找设备。
2. **D3 覆盖度**：第 3 行及以后的条目 + `Connected`/`Connecting`/`Authentication required` 三态各删一次（`5554_v98d3/REPORT_COVERAGE.md` 里"条目凑不满 4 行"的原因未定位 —— 可能是面板行数/滚动限制，值得当场看一眼）。
3. **B4 第 4 条（点外部变体）** 与任务书断言的冲突需要产品口径裁决（`5558_v98b4/REPORT.md` §5.4）。
4. **移动形态性能**：`标签页布局=vertical` + `wm 1200x1600@320` 下曾有"~45s 交互延迟"报告，且 5560 在该形态长测后 console 无响应（**未定论**）→ 值得专门量一次（`logcat` + `gfxinfo`）。
5. 扫测遗留清单见 §5 与 `5562_v98/REPORT_SWEEPD.md` 的 U1–U15。
6. 环境纪律新增：**开局先查 `adb reverse --list`**（有过两次为空）；**`uiautomator dump` 解析要同时匹配 `text="…"` 与 `text='…'`**；**点底部标签条前先断言 `mInputShown=false`**。

## 11. 收口（2026-10-03 01:20）

### 11.1 命令面板死区：**PASS 闭环**（第五轮，`5554_v98/REPORT_PALETTE2.md`）
- **A2 正向对照**：冷启动首帧 **6412 ms**；点底栏"设置" → **49.82% 像素差分** + 真实设置页节点 → **输入分发/命中测试/渲染管线全部健康**。
- **B1 主判据**：点底栏节点**有明确可观测结果**（`Open file` 真实执行：29.3% 像素差分 + 20 帧 + Compose 点击栈）。
- **B2**：点遮罩区**正常关闭**面板（49.1%）。
- **B3**：面板内命令**可执行**（设置页真实渲染，57.8%）。
- **结论**：**死区在 v1.4.0 / Pixel_9a 上无法复现** —— 修复后行为正确（点面板外必有可观测结果）。
- **前四轮失败的真正原因（已定论）**：不是 app 缺陷，而是**卡死的 system ANR 对话框**（两次测到同一窗口 id `d958b69`）把设备钉在不响应状态；**`adb reboot` 后 16s 就出首帧**。→ **纪律增补：设备"点了没反应"时先看 `mCurrentFocus` 是否是 `Application Not Responding`，是就先 reboot，不要把它记成 app 缺陷。**

### 11.2 目标四项判据 — 全部达成（证据索引）
| 判据 | 证据 |
|---|---|
| 全量闸门全绿 | `GATE_v98k.md`：**632 suites / 5753 tests / 0 failures / 0 errors / 0 skipped**；APK `f29d7d9f…` |
| 5 台模拟器真机终验 | 本轮累计 30+ 份真机报告（`research/v97_verify/**`）：F14b 两次 `Accepted publickey`、编辑项目 A1–A10、图标落盘 B11–C14、冷启动 route、标题热区+搜索、AgentChip 3/3、SSH D1/D2/D3/F12b/F13b、抽屉 N-3 5/5、D8 终端输入、D11 Review 诚实性 15/15、面板死区、v82 五条、三条悬挂项、扫测 A/B/C/C2/D |
| 修复 P0/P1 缺口 | **编辑项目面板禁用** → 本地 meta 写通道（`PROJECT_EDIT_LOCAL_META.md`，真机 A 组 10/10 PASS）；**AgentChip `/agent` 重弹** → `*RequestArrived` 闩（`AGENTCHIP_APPLY.md`，真机三条核心 PASS） |
| 更新 HANDOFF 结案 | `research/HANDOFF.md` 的 v98 段 + v98 续跑轮段；本文件 §1–§11 |

### 11.3 交接给下一轮（本轮结束时的未闭环，均为长尾）
1. **D3 覆盖度**：第 3/4 行 + 三态各删一次（三次尝试都因"条目凑不满/建不出"未验到；D3-1/D3-2 已两轮 PASS）。
2. **B4 第 4 条「点外部变体」**与任务书断言的出入 → 产品口径裁决（`5558_v98b4/REPORT.md` §5.4）。
3. **扫测遗留**：`5562_v98/REPORT_SWEEPD.md` 的 U1–U15；`5562_sweepB/REPORT_v97b.md` 的 12 项未做。
4. **冷启动性能**：本包 44MB（含 bcprov +4.5MB 类），首帧在健康设备上 6.4s；如需优化可评估 **bcprov 裁剪 / debug 包 R8 收缩 / baseline profile**。
5. **2 个及以上 tab 时**抽屉能否打开（代码路径相同，真机未覆盖）。


## 7. 环境与事故记录

- 后端 `http://127.0.0.1:4095`（opencode serve 2.0.18，Basic `opencode:opencode`），健康检查**只用** `GET /api/info`；全程未被重启（pid 260 未变）。
- WSL sshd active；`sshtest/opencode` 密码可用；测试钥 `research/v95_verify/id_ed25519`（md5 `0120a827…`，passphrase `keypass123`）。
- **事故 1**：5558 测试员误删共享 worktree `cosmic-comet`（弹层按钮与菜单行 content-desc 同名）→ 已复原并由 Lead 独立核验（`GET /api/worktree` + `git worktree list` 一致）。教训：**弹层与宿主同名 content-desc 时，分类必须用弹层特有锚点，弹层未确认关闭前不得落任何坐标**。
- **事故 2**：SSH 修复轮覆盖了 `v97_fix/gradle_run1.log`/`gradle_run2.log`（前序轮次产物）。教训：日志文件名要带轮次前缀。
- **harness 侧**：18:41–18:47 之间 5 个 `subagent` 同时失败（无收尾消息），时间点与用户改 `~/.minimax` 配置重合；此后全部改走 mcode。

## 12. 续跑轮 2（长尾收口，2026-10-03 08:40–10:30）

### 12.1 闸门链（全部 `cleanTestDebugUnitTest + testDebugUnitTest + assembleDebug`）
| 轮 | suites/tests | 结果 | APK md5 |
|---|---|---|---|
| v99a | 633 / 5760 | 绿 | `bd896454…`（09:13:15，含 Picnic 排除 + 启动 defer） |
| v99b | 635 / 5776 | 绿 | `66aab605…`（09:35:24，含 Review 过滤器 + 队列控件） |
| **v99c（当前）** | **636 / 5787** | **0/0/0** | **`8f118efc9e18fe5eeffcc2abeec1a520`**（10:17:13，含 SSH 表单可达性） |

### 12.2 本轮闭环（真机）
- **队列条 `×`/`Steer`：PASS**（`5556_v98/REPORT_QUEUE_ACTIONS.md`）。**定性＝条件渲染，不是回归**：`followUpBehavior` 默认 `steer` ⇒ 队列模型恒空 ⇒ 面板从未被组合。真机：`Steer [1263,181][1302,224]`、`× [1302,154][1350,202]`；语义＝**Steer 不打断地把该条追加进正在跑的回合**、**× 取消该队列项且未发送**（与上游 `inbox.update delivery=steer` / `inbox.cancel` 一致）。
- **U11（Review 过滤器 Clear/Refresh）：PASS**（`5562_v98/REPORT_U11.md`）。**四集合比集合而非计数**：`|ALL|=208`、`|F|=7`、`C` 与 `R` **与 `ALL` 逐名相同**；U9 诚实失败态 **7/7 采样在位**；U12 顺带 PASS。
  **真因不是缓存**（`allRows` 本来就对）：是 **`LazyListState` 按 key 钉住过滤态首项** + **IME 把视口压到 ~60px**；**"只有 2 行"本身是"dump 只报可见节点"的假象**。
- **通知 IDLE 分支：无需改代码**（`NOTIFICATION_IDLE_DIAG.md`）。IDLE 分支**能进**（需客户端在 SSE 活着时真读到 `session.idle`/`session.status{idle}`/`session.execution.succeeded`；投递还需 `notifyAgent` 开 + 包通知开 + **app 不在前台**）。上一轮读到 0 的原因：**app 切后台后进程被冻结、终帧从未被读**，两个兜底一起哑火；**"Channel 存在"是跨轮累计高水位、不是本轮观测量**。→ 又一条**测试姿势造成的假缺陷**。

### 12.3 本轮落地的修复
| 项 | 根因 | 文件 | 证据 |
|---|---|---|---|
| 冷启动量化 + 资源排除 | 44MB 里 **7.4MB(16.4%) 是 zipalign 页对齐填充**；**bcprov 不是启动大头**（不在首帧路径）；真凶是 debuggable 无 AOT + `OcActivity` 首帧前 8 个同步 `remember{}` | `app/build.gradle.kts`（Picnic 常数表排除） | `COLD_START_PERF.md`，APK −309B，类/方法数不变 |
| 启动 defer | 同上（②） | `OcActivity.kt`（`NetworkRecoveryWatch.start()` 移出首帧）+ `StartupDeferV97Test.kt` | `STARTUP_DEFER_FIX.md`，105 例 0 失败 |
| Review 过滤器 | `LazyListState` key 钉首项 | `ReviewFilesTab.kt`/`ReviewModels.kt`/`ReviewPanel.kt` + `ReviewFilterClearV97Test.kt` | `REVIEW_FILTER_CLEAR_FIX.md`，100 例 0 失败 |
| 队列控件 | 条件渲染（默认 steer） | `ComposerQueue.kt`/`ComposerQueuePanel.kt` + `QueueRowActionsPinTest.kt` | `QUEUE_ACTIONS_FIX.md`，539 例 0 失败 |
| SSH 表单可达性 | 字段落裁切线外、被剔出 a11y 树 ⇒ **提交根本不成功** | `ui/servers/SshFormReach.kt`(新)/`SshDialog.kt` + `SshFormReachabilityV98Test.kt` | `SSH_FORM_REACHABILITY_FIX.md`，270 例 0 失败；**真机复验进行中** |

### 12.4 仍未闭环
1. **D3-3/D3-4**：第六轮正在验"表单修复后能否真建成 4 行 + 三态删除"（前五轮的阻塞已定位到表单可达性）。
2. **`Undo` 未接线（no-op）**：修复进行中（`UNDO_WIRING_FIX.md`）—— 上游语义查证 + 接线或把缺口写死。
3. **扫测仍未验到的**：SWEEPD 的 U1/U2/U4/U5/U6/U7/U8/U14-UI（多为需要长回合/IME/通知前台切后台的构造）；SWEEPB2 的"非图片附件、菜单 `Fork`/`Move`/`New worktree`"。
4. **冷启动真要压 44MB**：需从 **104 个 STORED 条目**下手（会改安装语义），或走 **release 侧 R8 + baseline profile**（debug 不消费）。
5. **2+ tab 时抽屉入口**未真机覆盖。
6. **B4「点外部变体」已裁决**：上游 `message-timeline.tsx:485` 是 `onBlur → saveTitleEditor()`（**失焦即提交**），`:285-289` 在 `active===false`（导航离开）**丢弃** ⇒ 本壳"会话内点外提交 / 导航离开丢弃"**与上游逐字一致**，**上一轮任务书的断言是错的**，无需改代码。

### 12.5 最新一波（10:20–11:00）
- **D3 · 第六轮**（`5554_v98d3/REPORT_COVERAGE6.md`）：**表单可达性修复真机 PASS** —— 四个字段首次全部可达（含第五轮"11 个滚位从未出现"的口令框；行 4 Name 框 `h=6→126`、标签 `h=1→41`）；哨兵条目**同时**拿到 **UI +1 行**与**落库 +1**。
  **同时查实两件事**：① 第五轮"落库 0 条"是**计数口径错**（SSH 隧道表是**单个加密键**，数 `<string>` 行数永远不变）；② **D3-3 至今无法取证的唯一真阻塞点是 `Verify host identity` 核验卡卡死**（`Cancel` 与 `Trust and connect` **都无效**，并**卡住 `Add SSH server` 与行内 ⋮**）→ **已派修复**（`HOST_KEY_CARD_FIX.md`）。
- **⑤ 2+ tab 抽屉：PASS**（`5562_v98/REPORT_DRAWER6.md`）：两个会话槽位都能打开（`diff=43` / `diff=41`，打开后都列出**全部 3 个会话**）；打开态条 `y=1468..1564`，**与关闭态逐字相同**、无 `0..84` 回归；**默认桌面形态没有该入口**（`DesktopTabStrip` 不挂 `MobileDrawerMount`，已作为事实记录）。
- **扫测最终轮**（`5556_v98/REPORT_FINAL_SWEEP.md`）：**非图片附件 PASS**（注意：v2 用户消息的附件在**顶层 `files[]`**、不在 `parts[]`，首版脚本差点误判 FAIL）、**`Fork` PASS**、**`Move` PASS**；**通知链路 PASS**（`trigger idle(finished=true)`→`raise`→`posted(viewed=false sound=default)`→`NotificationRecord` 命中）——`NOTIFICATION_IDLE_DIAG.md` 的代码结论在真机逐环坐实；**唯一缺口＝切后台后约 3 分钟投递延迟**（进程冻结，架构级已登记）；**`New worktree` 纪律性跳过**（不支持自定义命名 + 无 DELETE 端点，不造不可删对象）。
- **⚠️ `Undo` 的 FAIL 是真的（推翻上一轮"判据错"的收尾）**：上一轮"链路八环齐全、无需改代码"**不成立**——按上游正确口径重造态（1 user + 4 assistant）后点 `Undo`：消息仍 7 条、**`revert=null`**、**`Redo` 不出现**、logcat **无任何 revert 行**；且**菜单项 `enabled=true`（使能）但执行零写入（执行）自相矛盾**。→ **已派运行时取证修复**（`UNDO_RUNTIME_FIX.md`：要求逐段证明"点击到没到处理器 / 请求发没发 / 服务端收没收到 / 状态回没回写"，并消除"使能与执行矛盾"）。

## 13. 长尾收口轮（2026-10-03 11:00–11:45）

### 13.1 闸门
- v99d **红 1 例**（`UndoRevertCommitTest.theTransportExposesBothHalves`）→ **Lead 亲自修**：那是**钉桩漂移**（Undo 修复给 `V2Transport.commitRevert` 加了一行日志，打破"字面两行相邻"匹配；语义未变）。改成**按函数体花括号配对钉关联**（比原来更强且抗日志漂移）。
- **v99e 全绿：639 suites / 5808 tests / 0/0/0**，APK **`ae1f61efb835693de189f749703c0178`**（11:09:12），已装 5554/5556/5562/5564。

### 13.2 `Undo`：修复 + **真机 PASS**（`5556_v98/REPORT_UNDO.md`）
- 断点＝**状态回写环**：`ChatViewModel.kt:4592`（只发投影不删 store）+ `:6770`（upsert 永不删除）+ `:6823`（边界被清空的 revert 被下一次快照覆盖回 null）。真相：**后端真的删了（12→3）而 UI 一帧未变**，成功撤销被下一次快照还原 ⇒ 与 no-op 不可区分。
- 真机三路读数：**UI 5 → 0 条消息**（时间线清空回 `Build anything` 空态）；后端 `revert=null`（commit 后按契约清空，非缺陷）；logcat **完整成功链含 `undo: dropped 4 reverted row(s) from the store`**。

### 13.3 `Verify host identity` 核验卡：修复 + **真机 PASS**（`5554_v98d3/REPORT_COVERAGE7.md` A 段）
- `Cancel` 三判据全中：①**卡两节点从 dump 双双消失** ②面板行**脱离 `Connecting`**（变 `Disconnected` + 出现 `Reconnect`/`Disconnect`）③行内 **⋮ 恢复 `clickable=true`**；再点表单页脚 Cancel 后 `Add SSH server` 恢复（**严格两跳**，受 F13b「`onCancel` 不得含 `onDismiss`」约束）。
- `Trust and connect` 有硬证据：**写入 `known_hosts`** 并**推进到 Password 挑战卡**（未自动信任、未跳过核验）。

### 13.4 D3 覆盖度：**D3-3 第 3 行 PASS**（同上报告 B 段）
- 建成第 3 行 → ⋮→`Delete` → 确认条可见可点 → **删的正是第 3 行**（落库 **660→464**）；反例 PASS（他人 `10.0.2.2:4095` 完好）；清理闭环。
- **仍未取证**：**第 4 行**（面板滚动/容量限制）与 **D3-4 三态**（`Connecting` 窗口未抓住 / `Connected` 未造出 / `Authentication required` 未造出）→ **第八轮进行中**。
- 上一轮留的两条提醒：**`known_hosts` 已被写入且未清**（重建同名条目不再弹核验卡 —— 正是抓不住窗口的原因，下轮要利用）；**`keypass123` 是测试私钥的 passphrase、`opencode` 才是 `sshtest` 的账号口令**（两者别混）。

### 13.5 通知三连 + IME 两条：**5/5 全 PASS**（`5564_v98/REPORT_NOTIF_IME.md`）
| 项 | 判定 | 关键读数 |
|---|---|---|
| U6 横幅**当场可见** | **PASS** | **后台→通知 = 23.7s**（5s 粒度轮询于 25.8s 捕获） |
| U7 通知**点击回跳** | **PASS** | tap 后 `mCurrentFocus=OcActivity` + 会话内容在屏；`flags=AUTO_CANCEL` 生效 |
| U8 **清除通知** | **PASS** | 面板 `清除全部` → `notifCount` **1 → 0** |
| U4 系统回滑收键盘 | **PASS** | 回滑后 `mInputShown true→false`，**未离开会话** |
| U5 弹层内收键盘 | **PASS（推翻旧结论）** | 真实回滑在命令面板内**有效且不关闭弹层**（`input swipe 2070 1200 1100 1200 260`） |
- **重要更正**：所谓"切后台后固定 3 分钟延迟"**不是固定值** —— 本轮切后台时回合**仍在生成**、进程未被冻结、SSE 活着，终帧照常读到，投递是**秒级**（23.6s 里绝大部分是**模型还在跑**的时间）。⇒ 那个缺口本质是"**后台终帧能否被读到**"的**竞态**。这也是为什么 `NOTIFICATION_IDLE_DIAG.md` 的"等待给足"要求成立，但**并非必然要等 3 分钟**。
- `notifyAgent` 本轮**从未改动**：`oc_settings.xml` 里本就没有该键，代码默认 **true**（`SettingsStore.kt:184`），运行期 `gate settings: enabled=true` 独立坐实。

### 13.6 D3 第八轮：**第 4 行 PASS** + 抓到一个产品级发现（`5554_v98d3/REPORT_COVERAGE8.md`）
- **D3-3 第 4 行删除 ✅ PASS**：三试命中、删的正是 `E2ECHK5554d3e-4`、**落库 −196 对称回退**；建到 **5 条**（落库 76→268→464→660→856→**1052**）。本轮还抓到**上一轮脚本自身的"错行删除"隐患**并用"**确认条正文点名**"做自校验拦截（方法论收获）。
- 因主机键已被信任（第七轮写入 `known_hosts`），本轮**新建同名 host 条目不再弹核验卡**（`cardUp=FALSE` 5/5）——正是第七轮提醒要利用的点。
- **D3-4 三态：一态都没拿到"该态下完成删除"的完整链路**，但**失败原因与第七轮完全不同**，且定位到**一个可复现的共同根因**：
  - **`Connecting` 态下"行内 ⋮ 不存在"** ⇒ `C2 NO KEBAB during Connecting -> cannot open delete menu` ⇒ **"连接中删除"这条场景按当前实现不可达**。**这需要产品口径裁决**：连接中的行**该不该**允许删除（上游是否也隐藏行菜单）？若"有意隐藏"，则 D3-4 的 `Connecting` 子项应改判为 **N/A（按设计不可达）**，而不是缺陷。
  - `Connected` **本轮未执行**（不是造不出：脚本 `c1_connected.ps1` 已写好，A/B 段脚本排查耗时把预算用完）；`Authentication required` 未取证。
  - 另记一条环境事实：**主机侧 22 端口**的 key 认证未能独立验证（`ssh -o BatchMode=yes sshtest@127.0.0.1` 返回 `Permission denied`）；**`2222` 只是 `adb reverse` 在设备侧存在的端口**，宿主机上并不监听。
- ⚠️ 本轮 mcode **撞到步数上限**（`limit_exceeded`，exit 1）：报告是完整的，但"三态"部分因预算耗尽而中止 —— 下一轮应**只做三态**这一件事，并把 `--max-steps` 提高。

### 13.7 D3-4 专轮 + 行菜单 parity（12:20–13:00）
- **闸门 v99f 全绿：640 suites / 5820 tests / 0/0/0**，APK **`178fdf228c6f26bc2d2152c0481e2743`**（12:48:18）。
- **`Connecting` 态"无行菜单"被双重证伪**：
  - 代码侧：本壳**从来没有** `connecting` 门控的 `⋮` 路径（`SshTunnelHost.kt:272/:302` 的 `ServerRowMenu(onClick …)` 位于裸 `Box {` 内、无条件；`SettingsComponents.kt:1610-1620` 的 `OcIconButton` 调用方从不传 `enabled`）。
  - 真机侧（D3-4 专轮）：**`Connecting` 态下 `⋮` 确实存在**。
  - **真因是脚本侧**：**面板不会自动滚到新追加的行** ⇒ 目标行不在 a11y 树里（`row not visible`）⇒ `kebabs=0` **假象**。**纪律：提交后必须先滚到列表末尾再 dump。**
- **顺带查出的真 parity 缺口（已修）**：行菜单**只有 `Delete`**，而上游 `_upstream/…/servers/ssh/menu.tsx:30-33` 还有 **`Connect`/`Authenticate`**（且按 `ssh.pending()` 在连接中禁用）→ `SSH_ROW_MENU_FIX.md` 已补。
  **仍未补（需 `domain.defaults` 等价物，已记账）**：分组标签 `ssh.server.menu.label`、`Set as default`/`Remove default`、`Menu.Separator`。
- **D3-4 三态真机结论**（`5554_v98d3/REPORT_COVERAGE9.md`）：`Connecting` **有行菜单**（旧结论证伪）；**`Connected` 未造出**（止于 `Connection failed`，非缺陷判定）；**`Authentication required` 未造出**（报告判定"结构上不可达"）；**在 `Connection failed` 态删除成功且删对行**。
- **SWEEPD 最后三条**（`5562_v98/REPORT_SWEEPD3.md`）：**U1** = 极端高度（键盘+队列条并存）下**时间线仅 16px 且被队列条完全遮挡**（composer 净空 33px 未被遮）；**U2** = **平台未 resize 窗口**，高度变化来自**本壳自己消费平台 ime `InsetsSource`** 后的布局；**U14** = **N1 不复现、N2 不复现、N3 部分复现**（ESC 无效仍成立，"跨导航不恢复"**被推翻**）。

### 13.8 收敛轮（15:00–15:10）：**闸门 v100a + 两条硬骨头闭环**
**闸门 v100a 全绿：644 suites / 5866 tests / 0 failures / 0 errors / 0 skipped**，APK **`e55f98084f9ec081511a9cfb7eb10f40`**（14:38:46）。

**① SSH 接入路径全部打通（真机 PASS，`5564_v98/REPORT_SSH_KEY.md`）**
| 判据 | 读数 |
|---|---|
| 构建 md5 | **一致**（`e55f9808…` + `06:39:05`） |
| 私钥条目是否弹 `Password:` | **否**（只弹 **Passphrase** 卡）⇒ 密钥认证真正生效 |
| 是否进入 `Connected` | **是**（`已连接`） |
| sshd 侧 | **`Accepted publickey`**（指纹 `SHA256:sYFitiUL7Z…` **逐字一致**） |
| 口令认证是否退化 | **未退化**（`Accepted password` + `已连接`） |
- **P3 根因**：第 4 环 **`SshPreferredAuthentications` 从未设置** + 第 6 环**交互回调无方法闸门**；jsch 默认方法列表是"**行走**"而非"**选择**"⇒ publickey 未被接受后继续走到 keyboard-interactive，本壳就给 `Private key` 条目画 `Password:` 卡并用**账号口令**"成功"（看起来像"密钥也能用"）。
- 连同上一轮的 **P1（`:port` 被丢）+ P2（不可诊断）**，**SSH 接入已是"两种 target 写法 × 两种认证方式"全通**。
- 环境更正：`research/v97_verify/id_ed25519` **已不存在**，测试钥在 **`research\v95_verify\id_ed25519`**（444 B）。

**② U1 布局（时间线被压到 16px）：主缺陷不再复现（`5556_v98/REPORT_U1C.md`，CONDITIONAL PASS）**
- **三版修复的根因链**（全部记录在案）：v1 时间线有最小高度但队列条被压扁；v2 双向最小 + 队列条内部滚动；**v3 才是真因** —— 时间线高度是**余数不是下限**：`viewportHeightPx` 下限为 **0**（`SessionPaneLayout.kt:991`）、composer 宿主**无上限**（`SessionScreen.kt:3203`）；composer **+34px** 跨过 `composerSharesViewport` 的 `<64` 判据后**排列翻转**，**整笔 host=116px** 全被扣到时间线（`132−116=16`）。
- **真机结论**：队列条真实存在的**全部有效帧**里 `readable` 恒 **92px**（≥64 地板）、时间线盒恒 **140px**，**原缺陷读数 `[0,162][1366,178]`（h=16）未复现**；`Steer`/`×` **8/8 都在**（h≥32、`clickable=true`）。**仅 8 个有效帧（计划 24）**，`≥11 条队列`与`极端窗口 1366x600`两格未取到 ⇒ 判 CONDITIONAL。
- **判据缺陷（Lead 裁定，已生效）**：`REPORT_U1B` 用 `154..202 ∌ 162..302` 判"不被覆盖"**在集合论上不成立**（前者是后者的子集）—— 覆盖式布局下队列条**确实**会压住时间线 top。**新口径**：算**可读带** `readable = 时间线盒高 − |时间线 ∩ 队列条|`，以 readable 判阈值，且**时间线盒/队列条/overlap/readable 四个数都要给**。

**③ 三条方法论纪律（本轮沉淀，已进派单模板）**
1. **派单必带"先核对被测构建 md5 + lastUpdateTime"** —— 已避免一次因装旧包导致的整轮空跑。
2. **判控件缺失前，先证明"容器真的渲染了"** —— U1 前两轮的误判都源于此（一次测错组件 `MessageNavRail`，一次队列条根本没出现）。
3. **判据本身要能被证伪** —— 判据错了，修得再对也会被误判（U1B 的覆盖判据、以及更早"没证明容器渲染就去判控件"都是同类）。

### 13.10 收敛轮（20:00–20:45）：**composer 溢出链走到"位置"层 + mcode 认证失效（基础设施阻塞）**
**闸门基线：v106 全绿 —— 652 suites / 5929 tests / 0 failures / 0 errors / 0 skipped，APK `87b27b42e0346e835886b366c3e43f3a`（20:06:05），已装 5556/5558。树自 20:06 起无源码改动。**

**composer「越 pane 底沿」五版轨迹（每版都有真机读数）**
| 版本 | 做法 | 真机结果 |
|---|---|---|
| v103 | 把"宿主可高"收敛到唯一求值点，令 `top + host ≡ pane` 恒等 | **FAIL** —— 恒等式约束的是**宿主盒**，验收量是**子节点并集** |
| v104 | 并集可用高收敛到 `composerChildUnionHeightPx`；44dp 带 vs 48dp 触摸靶 | **FAIL** —— 与 v103 读数**逐位一字不差** |
| （诊断） | 枚举节点树 + 候选定义 × 差值表 + 代码锚 | **结论①真越界**；正确算式＝**`unionBottom ≤ hostBottom`** 与 **`unionPx ≤ band`**；作废旧判据（`ceiling=0`、根盒 600），pane 底沿改用 `imeTop`，并集排除 `Open terminal` |
| v105 | **方案 B**：受控覆盖最小触摸靶 | **FAIL**（真机无效：子组合**根本不读** `LocalMinimumInteractiveComponentSize`；子高期望 44、实测 **48**） |
| v106 | **方案 A**：带本身提到 48dp | **② 修好**（600 档 `unionPx − band`：+4 → **0**）；**① 未动**（768 **+2** / 600 **+5**）；band 与并集**已等值 48=48**；768 时间线盒 140 / `readable` 92 未回退；队列几何 43/48/全 clickable 未变 |
⇒ **问题性质已从"高"转为"位置"**：并集**底沿**比宿主盒**底沿低 2–5px**，即子节点被**向下位移**。

**下一步（v107b，已被基础设施阻塞）**：v107 死前留下线索"**根因在单一收容点（single containment point）**"。四个候选（逐条排除，要代码锚 + 算式）：①宿主盒用上一帧/旧值 ②`offset`/负 padding/底对齐/二次位移 ③盒基线与子节点对齐基准不一致 ④另有非 composer 节点被算进宿主盒或并集。**要给出 +2 与 +5 各自的两条算式**，并回答"子高与 hostPx 都是 48，为什么两档位移差不同"。

**🔴 基础设施阻塞（需人工介入）**：`mcode 0.6.2` 认证失效 —— 任何 `mcode exec` 都返回
`mcode exec failed: Sign in to MiniMax to use Agent features. Run 'mcode login', then retry.`
（最小探活同样被拒）。**须由用户执行 `mcode login`**。
**⚠️ 判因更正**：v107 那句 "I have the root cause. Let me implement the fix in the single containment point." 之后中断，**很可能不是步骤耗尽，而是认证中途失效** —— 我先前"步骤耗尽"的归因需撤回。

**本轮沉淀的纪律**
1. **派单必须要求"分析先落盘"**：分析结论要在**动手前**写进报告（哪怕半页），否则撞上截断就整轮归零。
2. **PowerShell 禁用 `$pid` 作自定义变量**（`$PID`/`$pid` 是自动变量，大小写不敏感）—— 复验员首轮 768 档 logcat 扫描因用了 `$pid`（值＝pwsh 自己的 56084）而**无效**，改用 `$appPid` 重扫才有效。
3. **`wm size reset` 会清空 override** —— `wm` 收尾要**显式重设**回 `1366x768`/`160`。

| 子项 | 结果 |
|---|---|
| **第 3 行删除** | **PASS**（落库 660→464） |
| **第 4 行删除** | **PASS**（−196 对称回退） |
| **`Connected` 态删除** | **PASS**（造出并删对；本 build 上**首次可构造** —— 靠刚修好的私钥认证 P3 与 target 解析 P1） |
| **`Connecting` 态删除** | **PASS**（菜单 `Delete` `enabled=true`、确认条可点并完成；仅受 **~1s 状态窗**限制） |
| **`Authentication required` 态删除** | **PASS**（错口令构造 + 删对） |
- 前置核对全部通过：**构建 md5 逐位一致**、**设备全程属于本 app**。
- **"条目建不出/凑不满"的三个真因（已全部查实）**：①表单字段落裁切线外、被剔出 a11y 树；②`Add server` 在必填 `target` 为空时**禁用**（`SshDialog.kt:517`）；③**面板不会自动滚到新追加的行** ⇒ 目标行不在 a11y 树（`row not visible`）⇒ `kebabs=0` 假象。
- **一处形态受限（非缺陷）**：5556 是 1366×768 桌面形态，面板 ScrollView 仅 **191px**，快捷新增卡的**认证方式选择器与名称字段被挤到可视区外且滚不出来** ⇒ 条目无法命名、`Connected` 走的是**账号口令**而非私钥（私钥已铺、md5 双侧一致但未被读取）。**建议下轮在竖屏手机形态设备上补跑"私钥变体"与"私钥认证下的 `Authentication required`"**。









---

## v124 追加 —— 接手前必读（本轮只追加，不改上文语义）

> 完整证据：`research/v97_fix/CRITERION_MODEL_FIX.md`；前序：`research/v97_fix/PANE_ENTITY_FIX.md`（v123）。

### 0. 一句话现状

**设备侧已过关（两档主判据 ① 分别是 −10 / −12，三格基线逐位保持）**；
**测试侧的判据模型本轮已修正到真机口径**（−8px 判掉、144 更正为 96）；
**生产代码净改动仍为 0** ⇒ **768 那笔偏差还在，只是现在被正确记账了。**

### 1. 真机终验（第 3 次，`emulator-5554` / `1366x768` / `160`）

- **第 0 步 PASS**：首次真正拿到**10 行可点会话列表**，并进入会话看到真实消息与 `Build · 7s`。
  引导页 a11y dump **只有 `Server address` / `Password` 两个 `EditText`**，**没有 Username 框**。
- **主判据 ①**：768 = **−10**、600 = **−12**，**两档都过关**。
- **三格基线逐位保持**：768 `readable 92` / 768 `时间线盒 140` / 600 `readable 51`；
  600 旧口径 `249 ≤ 261` 亦逐位复现。
- **判据 ②**：600 = 0 ✅；768 取决于 `band` 口径，**未裁定**（见 §5.7）。
- **缩窗往返 2 轮**：`pidof` 全程未变，logcat 零命中，全程无 ANR。
- 🔴 **队列几何未测到（不判回退）**：扫 5 次 dump，全树**无任何 `Queue`/`Steer` 节点**；
  工具行只有 **`Add` + `Model` + `Send`** ⇒ 队列条**不存在**，**无从点它**。
  `Steer h=43` / `× h=48` / 全 clickable **无法测量，不是"不达标"**。
- 杂项：`dumpsys window insets` **不是合法子命令**，insets 原文在 `dumpsys window` 正文里。
  开工撞 ANR 已 reboot；reboot 后屏幕休眠需 `KEYCODE_WAKEUP` + `wm dismiss-keyguard` + `HOME`（**休眠非 ANR**）。

### 2. 解阻塞的**两条契约**（照填，别再猜）

1. **服务器探测**：`GET /api/info` 必须 **200 + JSON**，
   且 JSON 的 **`version` 必须以 `2.` 开头**（例 `{"version":"2.0.14","pid":712,...}`），
   **且 PATH 上必须是 v2 可执行文件**（v1 没有这个端点）。
2. **连接认证**：**用户名固定 `opencode`**（没有 Username 框，别填 `admin`、别留空）；
   **Password = `OPENCODE_SERVER_PASSWORD`** 的值。
   校验：从 dump 读回 `text` 属性**长度**确认没被截断；长 URL **按逐位校对过的边界切分**
   （本轮就是把 `127.0.0.1` 拼成 `127.0.0.0.1` 才失败的，与认证无关）。

### 3. A（去 `CHROME_FULL_HEIGHT`）**不可动** —— 理由要记牢

- **`:1467-1468` 是在兜 P0**：`PANE_ESSENTIAL_HEIGHT` 在 `believedImeBottomPx` 里的角色
  **不是尺寸预算，是塌陷阈值**；要检出 v77 的塌陷，被比较的数**必须含 chrome**。
  去掉 ⇒ v77 实测的 **226 dp 塌陷**不再被检出 ⇒ **「丢工具栏/tab 条」复发**。
- v123 **试改实测**：13 条红 → 全量回退 → 97/97 绿，**生产净改动 = 0**。
- **v124 新增反证**：代入真机口径后，去 chrome ⇒ `essential = 200`，
  `room 406 ≥ claimed 378` ⇒ **钳位整个不开**，body = 180，残差 **40**
  ⇒ 把**已记账的 60** 换成**没记账的 40**。**两头都不划算。**

### 4. ⚠️ 行尾陷阱（接手前务必确认）

- `autocrlf=true` + 仓里有**逐字源码 pin** ⇒ 文件一被 git 回退就整仓 CRLF，
  表现为「**代码干净、测试必红**」。v123 曾因此**把 64 个文件变 CRLF**。
- **现状：仓库已设 `core.autocrlf=false`**（本轮复核过 = `false`）。
- 🔴 **禁令**：**不许用 `git checkout` / `git restore` / `git add --renormalize` 改行尾**；
  **改文件只用编辑工具**（Edit / Write）。

### 5. 仍未闭环清单（接手的优先级顺序）

| # | 项 | 状态 / 下一手 |
|---|---|---|
| 1 | **自证循环** `SessionScreen.kt:2385-2389` | 形态判定看的是**钳位自己的输出**。v124 代入实测输入后 body 由 284 变 **236**，**仍 ≥ 164 阈值** ⇒ **循环未破、更强**。要动它必须先让形态判定看到设备侧真实 body |
| 2 | **队列几何** | **未测到**（无 `Queue` chip）⇒ 队列 pin 未动、未放宽。需先解决"怎么让它出现"（长回合 + 立刻抓屏，或缩短两次抓屏间隔） |
| 3 | **通知竞态** | 未闭环 |
| 4 | **44MB release 侧** | debug 44MB；`bcprov` 库体积、路径遍历/文件名库、AOT+脱敏前 8 分支同 `remember{}`、Picnic 素材、`NetworkRecoveryWatch` 启动开销。**必须 release 侧 R8 + baseline profile** |
| 5 | **IPv6 仅 JVM** | **未在设备上验** |
| 6 | **Trust 流程** | **未复验** |
| 7 | 768 判据② `band` 口径 | **待 Lead 裁定**：用 `BAND_HEIGHT=48` ⇒ 768 ② 为假（全局 FAIL）；用该档总带高 96 ⇒ 两档 ② 均真 |
| 8 | 768 判据① 生产口径 **+2**（B） | `Composer.kt:2150/2152` 的 2px 居中探出，被 6 条既有 pin 逐字锁住，**仍在授权外** |
| 9 | 仓内 `426` 与 `474` 两套 IME 读数并存 | 已知口径分叉（`474` 是 v110 旧 epoch 的 `imeTop=294`），见 `CRITERION_MODEL_FIX.md` §6.2 |
| 10 | A 未落地 | 768 那笔偏差：**96 = 84(chrome 二次计入) + 60(形态收账错) − 48(已修口径)**，前两项都动不了 |

### 6. 本轮改动与自证

- **生产代码净改动 = 0**；只改 **3 个测试文件**：
  `ComposerDivergenceLocateV119Test.kt`、`ComposerDivergenceLocate2V120Test.kt`、`ComposerCriterionParityV118Test.kt`。
- **49/49 绿**（6 类，用例数只增不减 7→8）；**两轮反向验证各红**：
  `imeBottomPx` 回退 474 ⇒ 红 1 条；`floorPx` 回退 0 ⇒ **红 4 条且精确复现 144**。
- **未** assembleDebug、**未** clean、**未**装机 ⇒ **设备基线本轮未复验**（v123/v124 均为零生产改动）。
