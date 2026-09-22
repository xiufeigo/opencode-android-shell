# Route C 团队共享规格（Wave 4）

> 所有 teammate 必读。冲突时以本文为准；本文没写的以 `_upstream/packages/**` 源码为准。
> 上游 = `_upstream/packages/{app,session-ui,ui,theme,schema}`（v2，sparse checkout）。
> 本 App 包名 `com.xiufeigo.opencodeshell`，源码根 `app/src/main/java/com/xiufeigo/opencodeshell/`。
> 明细审计证据见 `research/audit_session_ui.md`、`audit_settings_palette.md`、`audit_routes.md`、`audit_api_events.md`、`audit_1to1_verdict.md`。

## 0. 铁律

1. **只做加法**：不要重写整文件；用 `edit` 精准替换。他人文件一律不碰。
2. **构建门**：提交前必须 `.\gradlew.bat :app:testDebugUnitTest` 绿（JAVA_HOME=`C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1`）。
3. **字符串**：只追加，用你的前缀（见各任务）；三个 locale 同步：`res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`。遇 `FS_STALE_VERSION` 就重读再改。
4. **不新建第三方依赖**，不加 gradle 依赖。
5. **不改** `_upstream/**`（只读参考）。
6. 不 git commit。
7. 汇报必须含：改了哪些文件、每条验收怎么自测的、剩余不确定项。

## 1. 已落地的共享契约（Lead 已在主干提交，直接用）

```kotlin
// ChatStore.UiPart —— 新增字段
val rawJson: String? = null              // 仅对下列类型保留原始 payload 文本
companion object { val RAW_JSON_TYPES = setOf(
    "subtask","step-start","step-finish","snapshot","patch","agent","retry") }

// ChatStore.Event —— 新增事件
data class PartRemoved(val messageID: String, val partID: String) : Event
// reduce: 删除该 part；若该消息因此变空则整条移除

// ChatStore.sessionIdOf —— 已修正为「先 sessionID，再 info.id，再 id」
// ChatViewModel.refreshSnapshotThrottled() —— idle 结算时兜底拉一次快照

// OpenCodeClient.Providers.Model —— 新增字段
val limitContext: Int? = null             // `limit.context`
val limitOutput: Int? = null              // `limit.output`

// ChatViewModel —— 新增公开流
val contextLimit: StateFlow<Int?>         // 当前选中模型的上下文窗口；null = 未知
```

## 2. 写作用域（互斥，越界即事故）

| 角色 | 独占文件 |
|---|---|
| data-layer | `ChatStore.kt`、`ChatViewModel.kt`、`OpenCodeClient.kt`、`app/src/test/**`（其相关测试） |
| session-fidelity | `ui/session/timeline/**`、`ui/session/header/**`、`ui/session/composer/**`、`res/drawable/ic_oc_dots.xml` |
| mobile-form | `ui/session/SessionScreen.kt`、`ui/session/drawer/**`、`ui/session/review/**` |
| shell-wiring | `ui/App.kt`、`ui/palette/**`、`ui/settings/**`、`SettingsStore.kt` |
| Lead | `research/**`、集成、最终验收 |

需要别人文件里的改动 → 发消息给对应 teammate 或 Lead，**不要自己改**。

## 3. 上游规格锚点（实现时逐条对照）

- 工具卡折叠体：`_upstream/packages/ui/src/components/collapsible.css:1-14`（`background: transparent; border: none`）+ `_upstream/packages/session-ui/src/components/basic-tool.css:314-318`（内容 `padding-inline-start: 12px` 导轨）
- 分组行文案：`_upstream/packages/session-ui/src/tools/tool-renderer.tsx:535-558`（`Used {count} {names}`）
- 默认折叠策略 preset：`_upstream/packages/app/src/settings/model.tsx:248` + `_upstream/packages/session-ui/src/timeline/detail.ts:41-49`（`compact`）
- 提交按钮：`_upstream/packages/ui/src/actions/submit.css:6-34`
- dock 底色：`_upstream/packages/ui/src/components/dock-surface.css:2-3`（`surface-raised-stronger` + 1px `border-base`）
- 移动端抽屉：`_upstream/packages/app/src/shell/mobile-drawer.css:1-60`（底部 sheet：16px 上圆角 + 28px 拖拽柄 + scrim）
- 手机会话视图：`_upstream/packages/app/src/session/screen.tsx:191-242,303-317`（四个整宽 tab；桌面侧栏 `isDesktop()` 门控，宽度 240/200/480 可拖）
- 上下文用量环：`_upstream/packages/app/src/session/timeline/session-context-usage.tsx:40,93-108,131-155`（取 `limit.context`；点击开面板；tooltip Cost/%/Tokens）
- 消息元信息：`_upstream/packages/session-ui/src/message/message-content.tsx:311-354`（用户 meta+复制+revert）、`511-554`（助手 Agent·Model·时长·Interrupted+复制）
- Working 行 / Jump to latest：`_upstream/packages/app/src/session/timeline/message-timeline.tsx:376-391`、`virtualizer.tsx:646-673`
- 横向 `⋯` 图标：`_upstream/packages/ui/src/icons/icon/icon.tsx:139-142`（三点 x=2.5/7.5/12.5）
- diff 渲染：`_upstream/packages/session-ui/src/pierre/index.ts:25-34,222-224`（13px/24px mono、只染背景）
- 会话行长按菜单：`_upstream/packages/app/src/home/sessions/view.tsx:30,597-610,694-701`（500ms）
- 命令注册表与副作用：`_upstream/packages/app/src/session/commands/use-session-commands.tsx:248-463`、`_upstream/packages/app/src/palette.ts:66-80`
- 设置页结构：`_upstream/packages/app/src/settings/pages.ts:5-18`、`settings/surface.tsx:9-40`、`settings/shell.tsx:70-180`

## 4. 验收总纲

- 单测：新增/修改逻辑必须有 JVM 可测的纯逻辑测试；全套 537+ 绿。
- 设备：Lead 负责两台模拟器（`emulator-5554` 平板 2560x1600 / `emulator-5556` 折叠 2076x2152）实测；teammate 可用 adb 自测但要说明命令。
- **自测约定**：`adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`；`adb reverse tcp:4096 tcp:4096`（每次装完都要重加）；`am force-stop` 后 `monkey -p com.xiufeigo.opencodeshell 1` 重启；截图 `screencap -p /sdcard/x.png` + `pull`。
- server：`http://127.0.0.1:4096`，opencode 1.18.31，无鉴权。
- 测试会话：`ses_f41f7138cffeB43nHxQAyNE1uf`（android-e2e，System32，有 tool 调用）、`ses_f4194e02affeVil2RX2q2si1tC`（DRAFT__OK，`C:\sensorsdata\main\program\chenjieyan_test`，有 markdown/todo）。
- 折叠屏是唯一能验证「移动端形态」的设备（竖屏单栏）。
