# 垂直标签页 → 项目树侧栏 + 可收起侧栏：最小改动方案

> 结论：**能做，而且不用动协议与服务端**。这是在固定上游版本 OpenCode **2.1.2（`a517687f`）** 上做的可编译原型，下面的示例图来自**真实构建产物 + mock v2 后端**的 Chromium 渲染，不是手绘稿。

相关产物：

- 补丁：[minimal-sidebar.patch](minimal-sidebar.patch)（6 个文件，`+537 / −18`，基于本仓库 Android 补丁之后的 checkout，见 3.5）
- 原型源码：[prototype/](prototype/)（侧栏组件 + 顶部工具栏）
- 复现工具：[tools/preview.mjs](tools/preview.mjs)（mock v2 后端 + 真实 SPA + Playwright 截图）
- 截图目录：[shots/](shots/)
    - 现状：[before-vertical-tabs.png](shots/before-vertical-tabs.png)
    - 项目树：[after-project-sidebar.png](shots/after-project-sidebar.png)
    - 展开第二个项目 + 悬停打开中的会话：[after-project-sidebar-expanded.png](shots/after-project-sidebar-expanded.png)
    - **收起左侧栏（不占宽度，工具栏浮在主区顶部）**：[after-sidebar-collapsed.png](shots/after-sidebar-collapsed.png)
    - 三态并排对比：[compare-before-after.png](shots/compare-before-after.png)

## 1. 两个需求与现状

1. **垂直标签页只有「打开的标签」**：项目与历史会话必须回主页找。
2. **左侧栏没有收起按钮**：Claude 顶部那组小图标（☰ 菜单 / 面板收起 / ← 后退 / → 前进）里，OpenCode 只有菜单、后退、前进的**命令**（`mod+[`、`mod+]`）和菜单组件，没有可见按钮，也没有“收起侧栏”。

链路只有三处：

| 位置 | 作用 |
| --- | --- |
| `settings/model.tsx` | `appearance.tabLayout: "horizontal" \| "vertical"` |
| `shell/shell.tsx` | 垂直时渲染 `<aside data-slot="vertical-tabs-sidebar">`（默认 260px，可拖 140–520px），并把 DOM 挂载点交给 Titlebar |
| `shell/titlebar/titlebar.tsx` | `Portal` 到挂载点：ChannelIndicator → 主页 → 新建/最近关闭 → `<TitlebarTabStrip orientation="vertical">` → 页脚；垂直时整个 header 被隐藏（`hideVerticalTitlebar`） |

`TitlebarTabStrip` 只读 `tabs.store`，所以「已打开」之外的会话不可见；header 被隐藏，所以菜单/后退/前进在垂直模式下也没有入口。

## 2. 方案

### 2.1 侧栏 = 项目 + 会话树

```
☰  ▯  ←  →              ← 顶部工具栏（菜单 / 收起 / 后退 / 前进）
主页
新建会话
─────────────
项目
⌄ opencode-android-shell  6
   给会话设置添加重置按钮      ← 当前会话高亮
   检查本地分支是否为最新版本   ← 打开中的会话悬停出现 ×
   新建 claude-UI 分支
   修复 Android 终端 resize …
   把垂直标签页改成项目树
   整理 OpenCode 上游补丁清单
› opencode-ui              3   ← 其余项目默认收起，点行/chevron 展开
› opencode-claudeUI        1
› deepseek-harness         1
› yiheda                   1
```

- **点项目行**：展开/收起；悬停行尾出现「新建会话」按钮（在该项目下开草稿）。
- **默认展开**：当前会话所在项目 + 含打开标签/草稿的项目；其余收起。用户点过后以用户状态为准（仅本次运行内，不落盘）。
- **点会话**：已打开 → 切换；未打开 → `addSessionTab` + 跳转（与主页点会话同一套动作）。
- **关闭**：只对「已打开」的会话显示 ×，走现有 `tabs.closeTab`。
- 草稿（未发送的新会话）挂在所属项目下；多服务器时增加服务器小标题；无项目时显示现有 `sidebar.empty.title`。
- 保留 `ctrl+tab / ctrl+shift+tab` 与 `mod+1…9`。

### 2.2 顶部工具栏 + 可收起

- 工具栏四格（顺序对齐 Claude）：`☰` 应用菜单（复用 `WindowsAppMenu`）、`▯` 收起/展开（新行为）、`←` 后退、`→` 前进（复用 `common.goBack/goForward` 命令）。
- 展开时工具栏在侧栏最顶部（主页之上）；**点 `▯` 后侧栏完全不渲染，不占任何宽度**。
- 收起后同一个工具栏**浮在主区顶部预留的 28px 条带**里（背景仍是 shell 底色，不压住会话卡片）；`▯` 变成「Show sidebar」，点一下恢复。
- 状态持久化在 `layout.verticalTabs.collapsed`（默认 false），重启后保持；只在 `tabLayout=vertical && !mobile` 时生效。
- 后退/前进按钮从命令注册的 `disabled` 派生（`backPath/forwardPath` 为空时置灰），不复制历史栈逻辑。
- 工具栏同时注册 `sidebar.collapse` 命令（命令面板可搜），不占用额外快捷键（`mod+b` 已被 `home.toggle` 使用）。

## 3. 改动清单（6 个文件）

### 3.1 新增 `shell/titlebar/project-session-sidebar.tsx`（418 行）

- **项目**：`useServerCtx(...).projects.list()`，与主页项目列同一来源。
- **会话**：`useQuery({ queryKey: ["home-sessions", server], queryFn: loadHomeSessionIndex(...) })` + `ctx.data.session.list()`，再 `mergeHomeSessionIndex` → `retainHomeSessions(…, HOME_SESSION_LIMIT, …)`；项目归属用 `homeProjectForSession`。**与主页共用 query key**，缓存和实时更新天然一致。
- **动作**：`tabs.addSessionTab / select / closeTab / newDraft`、`ctx.data.session.remember`、`ctx.projects.open / touch`。
- **UI**：`ProjectAvatar`、`SessionTabAvatar`、`Icon/IconButton/Tooltip` + 现有 `v2-*` token；无新增 CSS 文件。

### 3.2 新增 `shell/titlebar/vertical-sidebar-toolbar.tsx`（80 行）

`WindowsAppMenu` + 三个 `IconButton`（`layout-left`、`arrow-left`、`arrow-right`），读取 `layout.verticalTabs.collapsed`，点击调用 `toggleCollapsed`；后退/前进使用 `command.trigger("common.goBack"/"common.goForward")`，禁用态读取 `command.options`。

### 3.3 修改 `shell/state/layout.tsx`（+13）

- schema 增加 `verticalTabs: { collapsed: boolean }`（`Persistence.struct`），`initialLayout` 默认 `{ collapsed: false }`；旧布局数据靠现有的 `withInitial` 合并自动补齐。
- 暴露 `layout.verticalTabs.collapsed / setCollapsed / toggleCollapsed`。

### 3.4 修改 `shell/titlebar/titlebar.tsx`（+6 / −15）

- 垂直分支顶部插入 `<VerticalSidebarToolbar class="mb-1" />`。
- 把 `<TitlebarTabStrip orientation="vertical" …/>` 换成 `<ProjectSessionSidebar />`。
- 给 `common.goBack / common.goForward` 命令补 `disabled: !backPath(history)` / `!forwardPath(history)`（供工具栏置灰）。

### 3.5 修改 `shell/shell.tsx`（+18 / −3）与 `runtime/i18n/en.ts`（+2）

- shell：`verticalTabsCollapsed()`；`Show` 条件改为 `verticalTabs() && !verticalTabsCollapsed()`；主区在收起时预留 28px 顶部条带并渲染浮动工具栏（`main` 加 `relative`）。
- i18n：新增英文 `sidebar.collapse = "Hide sidebar"`、`sidebar.expand = "Show sidebar"`（中文界面落地时在 `frontend/android/i18n-zh.ts` 补两条即可）。

### 3.6 如何落进本仓库的构建

上游 UI 由 `frontend/android/build.mjs` 在临时 checkout 里还原 pin → 应用 `frontend/android/upstream.patch` → 拷贝 `frontend/android/*` 平台文件 → 构建。推荐：

1. 把 3 个新增/修改的 UI 文件放到 `frontend/android/`，在 `frontend/android/sources.mjs` 的 `files` 数组加拷贝项（与 `i18n-zh.ts`、`back.ts` 同机制）；
2. `upstream.patch` 只留 `titlebar.tsx`、`shell.tsx`、`layout.tsx`、`en.ts` 的小 hunk。

[ minimal-sidebar.patch ](minimal-sidebar.patch) 的基线是「2.1.2 + 本仓库 Android 补丁」之后的 checkout，所以可以直接跟现有 `upstream.patch` 叠加；`tools/make-patch.mjs` 会重建这个基线、应用补丁、逐字节比对全部 6 个文件后才算通过。

补丁统计：

```
packages/app/src/runtime/i18n/en.ts                              +2   −0
packages/app/src/shell/shell.tsx                                 +18  −3
packages/app/src/shell/state/layout.tsx                          +13  −0
packages/app/src/shell/titlebar/project-session-sidebar.tsx      +418 −0
packages/app/src/shell/titlebar/titlebar.tsx                     +6   −15
packages/app/src/shell/titlebar/vertical-sidebar-toolbar.tsx     +80  −0
```

## 4. 有意没做（留给后续迭代）

| 项 | 原因 / 建议 |
| --- | --- |
| 标签拖拽排序 | 项目树里“跨项目拖会话”语义不清晰；v1 关闭 |
| 标签右键菜单（重命名/预览/导出/删除） | 现状 `TabNavItem` 自带；v1 行是简化版。后续可换成 `TabNavItem` 复用 |
| 会话搜索/过滤、项目级未读汇总 | 侧栏窄，先保证看得到、点得到；搜索仍可用主页搜索 |
| 未加入项目的“孤儿目录”会话 | 不显示，避免侧栏被临时目录撑爆；后续可加“最近会话”兜底 |
| 展开状态持久化 | v1 仅内存；要落盘建议放 `layout`（和 `verticalTabs.collapsed` 一起），不动 `settings` schema |
| 收起时也保留项目树悬浮面板 | 用户明确要“不占位置”，v1 是整栏隐藏 + 顶部工具栏；如需要可加 hover 抽屉 |
| 中文文案 | 原型只加了英文 key；落地时在 `frontend/android/i18n-zh.ts` / `i18n-zht.ts` 补“收起侧栏 / 展开侧栏” |

## 5. 风险与回归面

- **影响范围**：只在 `tabLayout === "vertical"` 且非手机时替换侧栏内容和主区顶部条带；水平标签页、手机抽屉、设置页不受影响。
- **收起状态**：`layout.verticalTabs.collapsed` 经现有持久化迁移自动补默认值，旧数据不会解码失败；切回水平布局时不渲染侧栏，状态保留但无副作用。
- **数据一致性**：与主页共用 query key 与 store；`session.deleted` 由 `ctx.data.session.list()` 过滤，关闭标签仍走 `tabs.closeTab`。
- **性能**：每台服务器一个 `session.list` 查询（与主页相同，`staleTime: 30s`，每目录 64 条）；收起后侧栏组件不渲染，查询仍在缓存中，恢复时立即显示。
- **已知取舍**：打开中的极老会话若不在前 64 条，由 `mergeHomeSessionIndex` 的 store 合并兜底。

## 6. 已验证

- `bun run typecheck tsconfig.android.json`：通过（改完工具栏、布局、shell 后各跑一次）
- `bun run build`（android platform / prod channel / base `/`）：通过（仅上游既有 CSS/分包 warning）
- 真实构建产物 + mock v2 后端，Chromium 1280×770 @2x（2560×1540，light、zh）：
  - 顶部工具栏渲染，后退可用/前进置灰
  - 项目树默认展开当前项目，其余收起；点第二个项目可展开；悬停打开中的会话出现 ×
  - 点 `▯` 收起：侧栏消失、主区占满宽度、顶部浮动工具栏可再展开
  - 无 `pageerror`
- 补丁自检：在「2.1.2 + Android 补丁」基线上 `git apply --check` + 实际应用 + 逐字节比对 6 个文件，全部通过

## 7. 建议的验收（落地时）

1. 合入补丁 → `node frontend/android/build.mjs`（重打 `webapp/src/main/assets/www/ui`）。
2. `./gradlew :webapp:assembleDebug`，真机/模拟器把偏好设置改成「垂直」。
3. 手工路径：工具栏四格可用；收起后主区占满且工具栏浮在顶部、可再展开；展开状态重启后保持；切「水平」确认标签栏行为未变；手机宽度仍走抽屉。
4. 回归脚本：`node scripts/verify-android-navigation.mjs`、`node scripts/verify-android-back.mjs` 应保持通过。
5. 建议补 e2e keeper：`vertical tabs: collapse hides the sidebar and the floating toolbar restores it`。

## 8. 示例图怎么复现

`tools/preview.mjs` 起 mock v2 后端（多项目/多会话、SSE `server.connected`、VCS/worktree/扩展 RPC 的最小合法响应），用本地静态服务托管**真实构建产物**，注入 Android 原生桥 + `oc-host.js`，按生产持久化格式写 `localStorage`，然后 Playwright 截图；`--actions` 可以点收起按钮、展开项目、悬停会话后再截。

```bash
# 现状（仓库内已打包的 UI）
node research/sidebar-projects/tools/preview.mjs \
  --ui webapp/src/main/assets/www/ui \
  --out research/sidebar-projects/shots --name before

# 方案（补丁构建到 dist/sidebar-preview/ui-after 后）
node research/sidebar-projects/tools/preview.mjs \
  --ui dist/sidebar-preview/ui-after \
  --out research/sidebar-projects/shots --name after

# 收起状态
node research/sidebar-projects/tools/preview.mjs \
  --ui dist/sidebar-preview/ui-after --name after-sidebar-collapsed \
  --actions '[{"click":"[data-action=vertical-sidebar-collapse]"},{"wait":600},{"move":[700,500]}]'
```
