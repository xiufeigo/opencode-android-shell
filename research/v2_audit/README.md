# v2 审计汇总(Wave 0 出口)— 优先级施工单

来源:`A1-design-system.md`(41 条)、`A2-home-shell.md`(30 条)、`A3-session-view.md`(46 条)、
`A4-settings-palette-terminal-i18n.md`(30 条 + i18n key-diff)、`A5-protocol-events.md`(协议映射)。
日期:2026-09-21。基准:`_upstream` v2 分支(`@opencode/app` 2.0.11)。

## P0(阻断观感/功能 parity,先做)

| # | 差异 | 报告 | 施工位置 |
|---|------|------|----------|
| P0-1 | dark `bg-contrast` 取错(#242424→#5c5c5c grey-700) | A1 | `ui/theme/OcTheme.kt` |
| P0-2 | 代码高亮双 scheme 系统性错位(抄了 legacy `--syntax-*`,oc-2 应指向 v2 ramp) | A1 | `OcTheme.kt` / markdown 渲染 |
| P0-3 | light `overlayScrim` 过深、`textAccentHover`/`textCodeAccent`/dark `stateInfoBg` 错值 | A1 | `OcTheme.kt` |
| P0-4 | `OcColors` 缺 8 个语义字段(text-contrast/code-path、icon-faint/contrast/accent-hover、border-inverse 等) | A1 | `OcTheme.kt` |
| P0-5 | dark elevation 太淡且丢 0.5px ring 层;5 组 elevation 缺失 | A1 | `OcShadows.kt`/`OcTheme.kt` |
| P0-6 | Markdown 表格完全不支持(退化成段落) | A3 | `ui/session/markdown/MarkdownParser.kt` |
| P0-7 | Queue/steer 整套缺失(排队面板、delivery 语义、Steer/Queue 切换) | A3 | composer + 协议层(v2 inbox) |
| P0-8 | @mention 是纯文本假提及,结构化上下文(file/agent/skill)未随提交发送 | A3 | `composer/Mention*.kt` + 提交链路 |
| P0-9 | 代码块零语法高亮(上游 Shiki 流式高亮) | A3 | `markdown/MarkdownRenderer.kt` |
| P0-10 | 手机底部条只显示当前会话,无全标签抽屉 | A2 | `ui/nav/BottomTabStrip.kt` |
| P0-11 | Providers 页无 Connect/Disconnect(credential/integration 未接) | A4 | `ui/settings/SettingsPanels.kt` |
| P0-12 | Models 页无 visibility Switch(该页存在目的) | A4 | `SettingsPanels.kt` |
| P0-13 | Shortcuts 录制只存不生效(`globalChord` 未接线),表仅 10 行 | A4 | `ui/App.kt` 全局键绑定 + Settings |
| P0-14 | Palette 缺 ~20 个 session 高频命令(undo/redo/compact/fork/export 等) | A4 | `ui/palette/*` |
| P0-15 | 协议探针过期:v2 信号应为 `GET /api/info`(2.0.11 上 `/api/health` 404) | A5 | `v2/V2Capability.kt` |
| P0-16 | COVERAGE 表大面积过期(2.0.11 已含 config/project/vcs/mcp/PATCH/DELETE session) | A5 | `v2/V2Capability.kt` + `research/v2_coverage.md` |
| P0-17 | v2 端点形状错误:permission 回复字段 `decision`、revert-clear 是 `DELETE …/revert`、fork 字段 `before`、prompt 扁平 body | A5 | `v2/V2Api.kt` |
| P0-18 | question 体系在 v2 消失,载体变为 form(`form.created/replied/cancelled` + `/api/form`) | A5 | 协议层 + question dock |
| P0-19 | per-session SSE 不存在;替代 `GET /api/experimental/session/{id}/log?after&follow=true` | A5 | `v2/V2Api.kt` |

## P1(可见差异,第二批)

- **A1**:shimmer 默认值/公式不符;toast/dialog/popover 动效值无出处或错误;reduced-motion 只覆盖 16/27+ 条;presence 动效系未移植;缺 agent/avatar/markdown token 组。
- **A2**:无 DraftTab 模型(多草稿并存+持久化);Home 会话行无上下文菜单;新会话缺 workspace 选择器与 summary 浮层;`+`/Home 忽略上下文;Home 搜索无快捷键且只滤截断 64 行;声音/系统通知/未读三件套缺失。
- **A3**:edit 假 diff(无 hunk);ToolErrorCard 未移植;shell 无 ANSI strip/实时跟随/copy;subagent/task 卡片降级;compaction 单行静态;单条 revert、review 行级评论、WebSearch dock、子会话跳转缺失。
- **A4**:Palette archived 不过滤/无 ID 直查;General 缺 followUpBehavior/reasoningMode 等;MCP/worktree 只读壳;Terminal 状态不持久化;i18n 缺 602 keys、stale 69、Kotlin 引用死键(`command_session_share*`)、zh 不同步。

## P2(打磨,收尾批)

- avatar/illustration/gradient token、letter-spacing、LoaderV2/Typewriter 等微组件、Help 占位、worktree 删除全流程等。

## 关键结论(影响架构)

1. **v2 serve 无 v1 端点**,协议模式硬二选一(A5)。v2 事件流 = 全局 `/api/event` + per-session `/api/experimental/session/{id}/log`。
2. **v2 transcript 映射干净**:assistant.content 三态 text/reasoning/tool 与 `UiPart` 一一对应,`*.started/delta/ended` 正好对 `PartUpdated/PartDelta`(A5-8)。
3. **i18n 需重采**:`scripts/extract-i18n.mjs` + `gen_i18n.ps1` 对 v2 前端产物重跑,替换 `research/_i18n/*.json` 来源(A4-9)。
4. **queue/steer(P0-7)与 form(P0-18)依赖 Wave 1 协议层**,排在协议层之后施工。
