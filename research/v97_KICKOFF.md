# v97 KICKOFF — 新会话开场白（复制粘贴即可）

> 为什么需要新会话：`subagent/model-selection-policy` 是**每个会话在创建时一次性记入会话事件流的持久决定**
> （harness 源码 `recordSubagentModelSelection()`：`if (subagentModelSelectionPolicy(...) !== void 0) return;`
> 且投影 `apply` 里 `if (policy !== null ...) return policy` —— **永不更新**）。
> 本会话（`session-7fd8879d…`，17:01 创建）冻结的是 **17:11 之前**的白名单，所以 minimax-cn 两条永远进不来；
> `cordis.patch.yml` 17:11:30 才加上 minimax，重启 Harness 只能影响「之后新建的会话」。

---

## 复制下面这段给新会话

```
继续 v97 轮次。工作目录 C:\sensorsdata\main\program\opencode-android-shell。

先按顺序整篇读这三个文件，再动手：
1) research/v97_RESUME.md   ← 上一轮（被打断的会话）的完整状态：闸门结果、APK、5 台模拟器映射、F14a/F14b 进展、P0 候选、任务板、待办队列
2) research/v97_team_brief.md ← 团队公共事实源：环境、硬规则、纪律、踩坑库
3) research/HANDOFF_PROMPT.md ← 更早的交接（F8-F14 来龙去脉、测试员踩坑库）

目标（长期）：把工程推进到与上游 https://github.com/anomalyco/opencode/tree/v2 功能一致、界面一致、接入一致，
并对照 https://opencode.ai/v2/docs/api 补齐 API 面；完整测试通过、所有功能真机可用为止。
只接 V2 后端（opencode serve 2.0.18 @ http://127.0.0.1:4095，Basic opencode:opencode，健康检查只用 GET /api/info）。

模型口径（用户硬要求，本会话已确认可用）：
- **所有子 agent / teammate 一律 `minimax-cn/MiniMax-M3.1-Flash-Preview`**。
- **测试/侦察类**：推理强度 default。
- **修复类**：推理强度 max（该模型支持 low/medium/high/xhigh，用最高档）。
- 派单时通过 subagent 的 provider/model 字段显式指定；teammate 无法指定模型，所以**优先用 subagent**。
- 例外：`spawn_teammate` 造的 teammate 继承父路由；如果必须用 teammate，就只让它做编排/盯梢，不承担模型口径敏感的工作。

第一轮按 v97_RESUME.md §5「重启后待办队列」执行：验证 minimax 路由 → 重跑全量闸门（先清 .agent-gradle-lock）
→ 重新装机 5 台（先 emu avd name 核 serial↔AVD）→ v97 真机终验（Pixel_9a 现成脚本）→ 编辑项目禁用面板定性
→ AgentChip /agent 重弹修复 → 收口其余扫测 → 更新 HANDOFF.md。

先启动一个 agent team 并行推进，并自己盯梢（子 agent 有失败率，失败就重派）。
```

---

## 备注
- 旧会话里已经落盘的东西**全都能复用**：`research/v97_verify/**`（含 Pixel_9a 的 12 个可重放脚本）、`research/v97_audit/**`、`research/v97_gate_lead.log`。
- 旧会话的 goal（`goal-365d3f66-…`）绑定在旧会话上；新会话里可以让 Lead 用 `create_goal` 重新建一个同目标 goal。
