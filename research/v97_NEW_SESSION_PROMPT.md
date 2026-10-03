# 新会话开场白（把下面整段复制给新 agent / 新会话）

> 为什么换会话：`subagent/model-selection-policy` 是**会话创建时一次性冻结**的持久决定（harness 源码
> `recordSubagentModelSelection()` 只记一次、投影 `apply` 永不更新）。旧会话 `session-7fd8879d…` 创建于 **17:01**，
> 冻结的是 **17:11 加 minimax 之前**的白名单 → 旧会话永远用不了 minimax-cn，重启进程也没用。
> 新会话创建于白名单更新之后 → 冻结的名单**含 minimax-cn/MiniMax-M3.1-Flash-Preview**。

---

## 一、原始 goal prompt（用户原文）

```
请启动一个 agent team，请你自己启动测试，自己启动 bug fix，直到实现与 https://github.com/anomalyco/opencode/tree/v2 功能一致，界面一致，接入一致。完整测试通过，所有功能均能正常使用为止。我允许你启动无限量的子 agent / agent team，但请统一使用 'minimax-cn/MiniMax-M3.1-Flash-Preview' 这个模型。要求是测试的推理强度使用 default 就行，但修复需要推理强度为 max。C:\sensorsdata\main\program\opencode-android-shell\research\HANDOFF_PROMPT.md 是其他 agent 给你写的交接内容。我允许你启动无限量个子 agent，只要模型使用的 'minimax-cn/MiniMax-M3.1-Flash-Preview' 就行，因为这个模型我现在有无限量的 Token。
```

## 二、接手须知（2026-10-02 17:45 更新，比 HANDOFF_PROMPT.md 新）

### 第 0 步（必做，按顺序整篇读）
1. `research/v97_RESUME.md` — **上一轮最新状态**：闸门 612 suites / 5529 tests / 0/0/0 全绿、APK、5 台模拟器 serial↔AVD 映射、F14a/F14b 进展、P0 候选、任务板、待办队列、血泪提醒。
2. `research/v97_team_brief.md` — 团队公共事实源：环境、硬规则、Gradle 锁、测试员纪律、踩坑库。
3. `research/HANDOFF_PROMPT.md` — 更早的交接（F8–F14 来龙去脉、测试员踩坑库）。
   ⚠️ 其中「F14a/F14b 两路 worker 正在跑」**已过期**：F14a 已落地并入闸门；F14b 已完成根因判定（假设证伪）。
4. `research/HANDOFF.md` — 「有意偏离（终审表用，**不许改回**）」+ v91 终审结案批（已结案、别重开）。

### 模型口径（用户硬要求）
- **所有子 agent 一律 `minimax-cn/MiniMax-M3.1-Flash-Preview`**；
  **测试/侦察 = 推理强度 default**，**修复 = max**（该模型 effort 档：low / medium / high / xhigh，用最高档）。
- `subagent` 工具**可以**显式传 `provider` / `model` / `reasoning_effort` → 模型敏感的工作**都用 subagent**。
- `spawn_teammate` **不能**指定模型（继承父路由）→ teammate 只用于编排/盯梢，或干脆不用。
- 开工先 `list_subagent_models(provider="minimax-cn")` + 一个探针 subagent 确认路由可用。

### 立刻要做的（v97_RESUME.md §5 队列）
1. 清 `.agent-gradle-lock` 残留 → **重跑全量闸门**（`cleanTestDebugUnitTest` + `testDebugUnitTest` + `assembleDebug`），目标 ≥ 612 suites / ≥ 5529 tests / 0 failures / 0 errors / 0 skipped。
2. **重新装机 5 台**（先 `adb -s <serial> emu avd name` 核身份，serial 会重排）。
3. **v97 真机终验**（Pixel_9a）：用现成脚本 `research/v97_verify/5554/E2ECHK5554_*.ps1`（provision_key → F14a → F14b+F12a 先 `-RehearseOnly` → F13b → F12b → 清理）。
4. **`编辑项目` 面板全禁用**（5560 实测 FAIL）先对照上游定性：上游允许本地编辑 → 派修复；上游同款 → 记「有意偏离」结案。
5. **AgentChip `/agent` 重建即重弹**（`AgentChip.kt:143-145`，照 ModelChip 的 `modelOpenRequestArrived` 修法）派修复。
6. **收口扫测**（5560 报告未完；5556/5558/5562 三台被重启打断，需重派）+ 更新 `research/HANDOFF.md`。

### 环境（已就绪，别重搭）
- 5 台模拟器全在线：`5554=Pixel_9a`、`5556=Medium_Desktop`、`5558=Pixel_10_Pro_XL`、`5560=Pixel_Tablet`、`5562=Small_Desktop`，都已配 `reverse tcp:4095`/`tcp:2222`。
- 后端 `http://127.0.0.1:4095`（opencode serve 2.0.18，Basic `opencode:opencode`）；健康检查**只用** `GET /api/info`。
- 已装 APK 是 17:18 的旧包（**不含** F14b 的 bcprov + 路由修复）→ 必须先重跑闸门出新包再装机。
