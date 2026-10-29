# 垂直标签页 → 项目树侧栏 + 可收起侧栏：调研与原型

> 历史调研资料：当前产品已恢复官方侧栏，本目录的原型、补丁和验证脚本不参与构建。

OpenCode 把「标签页布局」设为 **垂直** 后，左侧栏只列已打开的标签；项目与历史会话只能回主页找。这里给出把它改成 **项目 + 会话树**（对齐 Claude / DeepSeek-harness 左侧栏）并为侧栏补上 **顶部小图标工具栏 + 一键收起** 的最小改动方案、可编译原型和真实渲染截图。

## 内容

| 文件 | 内容 |
| --- | --- |
| [PLAN-minimal-project-sidebar.md](PLAN-minimal-project-sidebar.md) | 方案正文：现状、设计、改动清单、风险、验收 |
| [minimal-sidebar.patch](minimal-sidebar.patch) | 补丁（6 个文件，`+537 / −18`，基线为本仓库 Android 补丁后的 checkout） |
| [prototype/](prototype/) | 新增组件的可读副本（项目树侧栏 + 顶部工具栏） |
| [tools/preview.mjs](tools/preview.mjs) | mock v2 后端 + 真实构建产物 + Playwright 截图 harness（支持 `--actions` 点按/悬停） |
| [tools/make-patch.mjs](tools/make-patch.mjs) | 重建基线、生成补丁并自检 |
| [shots/](shots/) | 现状 / 项目树 / 收起 / 交互 / 三态对比（2560×1540，light、zh） |

## 截图

- 现状（垂直标签页只有打开的会话）：[shots/before-vertical-tabs.png](shots/before-vertical-tabs.png)
- 项目树 + 顶部工具栏：[shots/after-project-sidebar.png](shots/after-project-sidebar.png)
- 展开第二个项目 + 悬停打开中的会话：[shots/after-project-sidebar-expanded.png](shots/after-project-sidebar-expanded.png)
- 收起左侧栏（不占宽度，工具栏浮在主区顶部）：[shots/after-sidebar-collapsed.png](shots/after-sidebar-collapsed.png)
- 三态并排：[shots/compare-before-after.png](shots/compare-before-after.png)

## 复现

前置：仓库内已有 Playwright（`_upstream/node_modules/.bun/playwright@1.59.1`）与 Chromium（`%LOCALAPPDATA%/ms-playwright/chromium-1234`）；截图 harness 只监听 `127.0.0.1` 临时端口。

```bash
# 1) 应用补丁并构建 Android 平台产物（日常构建走 frontend/android/build.mjs，
#    这里为截图单独构建到 dist/sidebar-preview/ui-after）
cd _scratch_root/desktop-parity/checkout
git apply ../../research/sidebar-projects/minimal-sidebar.patch
cd packages/app
VITE_OPENCODE_SERVER_MODE=none VITE_OPENCODE_PLATFORM=android OPENCODE_CHANNEL=prod \
  bun run build --base / --sourcemap false --outDir <repo>/dist/sidebar-preview/ui-after --emptyOutDir

# 2) 截图（--ui 指向任意构建产物；默认是仓库内已打包 UI）
node research/sidebar-projects/tools/preview.mjs \
  --ui dist/sidebar-preview/ui-after \
  --out research/sidebar-projects/shots --name after

# 3) 收起状态
node research/sidebar-projects/tools/preview.mjs \
  --ui dist/sidebar-preview/ui-after --name after-sidebar-collapsed \
  --actions '[{"click":"[data-action=vertical-sidebar-collapse]"},{"wait":600},{"move":[700,500]}]'
```

`tools/preview.mjs` 的关键点（也是这次摸底踩到的坑）：

- 客户端只有在 SSE 流的**第一个事件是 `server.connected`** 时才把连接标成 `connected`；否则所有 `enabled: connection.status()==="connected"` 的查询（包括主页会话索引）都不会发出。
- `/api/vcs`、`/api/worktree` 的响应 schema 与其它接口不同（`{location, data}` / 裸数组 / 204）；返回空 `{data:[]}` 会在前端触发与本功能无关的「请求失败」toast。
- `settings.appearance` 持久化在**无命名空间**的 `settings.v3` 键里；`layout`、`server`、`language` 才走 `opencode.global.dat:*`。
- Android 资产把 `/_assets/*` 映射到 `ui/oa/*`；预览服务两种目录都兼容。

## 面板独立框网格（方案 B）

右侧会话面板从「一个框 + tabs」改为「每个面板一个独立框」：顶栏四个按钮各自开关一个框，每列最多两个框、超出的开新列；列宽和框高都可拖动调节。摘要仍用顶栏 chip，详情仍是悬浮 popover。

- 默认不打开任何框，聊天区占满：[shots/grid-default.png](shots/grid-default.png)
- 开 1 个框：整列占满（[shots/grid-one-panel.png](shots/grid-one-panel.png)）
- 开 2 个框：分成两列、各占整列高度：[shots/grid-two-panels.png](shots/grid-two-panels.png)
- 开 4 个框：两列 2×2：[shots/grid-expanded.png](shots/grid-expanded.png)
- 收起左侧栏、网格保留：[shots/grid-collapsed.png](shots/grid-collapsed.png)
- 关掉浏览器后只剩三个框：[shots/grid-toggle-browser-off.png](shots/grid-toggle-browser-off.png)

分列规则：1 个 → 1 列；2 个 → 2 列；3–4 个 → 2 列（2+1 / 2+2，每列仍最多两个）；再多自动开新列。默认尺寸：列宽 280、框高均分，拖动列边/框间横线可调。

实现要点：`packages/app/src/session/panel-grid.tsx` + `panel-grid-state.ts`（open/列宽/行高，`localStorage` 持久化）、`screen.tsx`（`panelGridMode` 时用网格替换 tabs 侧栏与 dock，聊天区 `flex-1` 让出宽度）、`shell/titlebar/vertical-session-toolbar.tsx`（四个独立开关，按 provider 是否可用置灰）。默认不打开任何框，点顶栏按钮再开。

已知后续项（详见 [notes/grid-audit.md](notes/grid-audit.md)）：`review.toggle`/`terminal.toggle` 旧命令与快捷键尚未接到网格；列宽按列序号存储；状态持久化还没走 `Persisted`/`Persist.*`；标题栏右侧 slot 在缓存会话间是 last-writer-wins。

## 当前状态

- 原型已在临时 checkout 上通过 `bun run typecheck tsconfig.android.json` 与生产 `bun run build`；截图来自该构建产物。
- 预览 harness 的两个 mock 问题已修：`/api/vcs` 现在按契约返回 `{location, data:{branch:{current,default}}}`（之前和 `/api/vcs/status` 合并成 `{data:[]}`，导致 review 扩展启动崩溃、变更框缺失，见 [notes/review-preview-crash.md](notes/review-preview-crash.md)）；`drag` 动作已加入 harness，用于验证列宽/行高拖动。
- **尚未合入** `frontend/android/upstream.patch` / `frontend/android/sources.mjs`，`webapp` 内已打包 UI 未重新生成；落地步骤见方案 3.6、7 节。

## 2026-10-22 交互修复（性能 / 拖拽 / 草稿暂存）

用户实测反馈的三个问题及修复结果：

### 1. 同时开 2 个以上面板卡顿

- 原因：每个面板 provider（PTY、浏览器 frame、diff 树）在同一个 tick 内同步挂载；每次开关还会同步 `JSON.stringify` 写 `localStorage`；关闭面板直接销毁 DOM，重开时重新挂载。
- 修复（[panel-grid.tsx](../../_scratch_root/desktop-parity/checkout/packages/app/src/session/panel-grid.tsx)）：面板内容按 `requestAnimationFrame` 逐个挂载、先画骨架；`localStorage` 写入去抖 150ms；已经打开过的 frame 关闭后保留在 DOM（`display:none`），重开不再重建 provider。
- 数据（真实 serve + 1440×900）：逐开 1/2/3/4 个面板事件→首帧 28.8 / 39.3 / 36.6 / 36.5 ms；重开 review 由 2002 ms（单次 long task 1859 ms）降到约 100 ms、0 long task。

### 2. 拖拽不跟手 + 聊天边界不能拖

- 新增 [grid-splitter.tsx](../../_scratch_root/desktop-parity/checkout/packages/app/src/session/grid-splitter.tsx)：10px 命中带 + 2px 悬停高亮线、pointer capture、rAF 合并、松手才落盘。
- 列 / 行 / 聊天三个分隔条都已接入；聊天列可拖（默认 520px，范围 380–900），拖动方向与位移 1:1：拖 80px 实测列宽 +80px，聊天 -60px 实测 -60px，行 +60px 实测 +60px；拖动期间 0 long task。
- 截图：[shots/fixes-v3/](shots/fixes-v3/)（4 框、拖拽后状态、草稿根页面）。

### 3. 垂直模式没有 Home + 草稿暂存

- `/` 在垂直标签页模式下直接进入新会话草稿页（`/new-session?draftId=…`），品牌字标点击同样回到新草稿；水平布局的 Home 保持原样。
- 未发送的草稿展示在左上角「新建会话」下方（`[data-slot="draft-list"]`），不再塞进项目目录；标题取输入框文本，点 × 可关。
- 验证：垂直模式根页面 `home=false`、draftList=1、项目组内草稿=0；水平模式 Home 截图 [shots/drafts-v2/07-horizontal-home-unchanged.png](shots/drafts-v2/07-horizontal-home-unchanged.png)。
