# 架构治理进度

## 运行框架

目标：在保留现有功能、数据兼容性和对外契约的前提下，把仓库收敛到唯一权威来源、标准实现、深模块、高内聚低耦合、局部变更和可执行架构约束。

本轮方式：高 rigor、渐进式。每个单元独立验证并独立提交。Android 发布身份、上游 UI 行为、服务器协议和用户本地数据是一旦破坏就很难恢复的回滚边界，因此这些边界不作为重构实验对象。

本轮的完成谓词：

- 每个接受的重构单元都有 before/after 证据。
- Android 单测、终端测试、服务端浏览器测试和相关浏览器验证保持绿色。
- 被替代实现已从源码与测试中删除，而不是与实现并存。
- 至少一项已治理规则被脚本或 CI 固化。
- 决策日志、任务池和进度文件同步更新。

## 基线验证

已在隔离 worktree `opencode-android-shell-arch-governance`、分支 `arch/governance-2026-11-28` 上建立基线。

| 验证 | 结果 |
| --- | --- |
| `node --test frontend/embedded-ui/*.test.mjs frontend/android/*.test.mjs` | 10/10 通过 |
| `cd frontend/terminal && npm ci && npm test` | 14/14 通过 |
| `cd frontend/terminal && npm run build` | 通过；`oc-terminal.js` 无 diff |
| `cd plugins/server-browser && npm ci && npm test` | 6/6 通过 |
| `cd plugins/server-browser && npm run typecheck && npm run build` | 通过 |
| `JAVA_HOME=<Android Studio JBR 21> ./gradlew :webapp:testDebugUnitTest --no-daemon` | 通过 |

完整官方 UI 构建基线已使用 pin 要求的 Bun 1.4.4 运行：`node frontend/android/build.mjs` 通过，生成 `webapp/src/main/assets/www/ui/` 后无 tracked diff、无未跟踪资产。

## 当前轮次

### Round 1：删除不可达的 Ghostty 终端兼容实现

状态：完成，待提交。

识别证据：删除前 `oc-host.js` 固定 `window.__ocTerminalEngine = "xterm"`；`installTerminalFontScale()` 与 `installTerminalTouchScroll()` 都在第一行因该值返回。生产触摸、字号、字符网格和滚动行为已经由 `frontend/terminal/terminal.js` 承担。

闭环内容：

- 删除 canvas 字体缩放补丁、触摸转 wheel 补丁和只为它们存在的 `TerminalFontScaleTest` / `TerminalTouchScrollTest`。
- 保留终端字号设置项及其存储键契约；列数估算改为直接使用浏览器原生 `measureText`。
- 完整 SPA 验证脚本的检查改为 `host-terminal-boundary`，明确宿主 facade 不得再长出第二套终端实现。
- 新增 `scripts/check-architecture.mjs`，并接入 CI，防止这些死实现复活。

验证：

- `node scripts/check-architecture.mjs` 通过。
- `node --check webapp/src/main/assets/www/oc-host.js` 通过。
- `node --test frontend/embedded-ui/*.test.mjs frontend/android/*.test.mjs` 通过。
- `cd frontend/terminal && npm test` 通过。
- `./gradlew :webapp:testDebugUnitTest --no-daemon --quiet` 通过。
- `VERIFY_TERMINAL_MODE=boot VERIFY_TERMINAL_FILTER=host-terminal-boundary node scripts/verify-android-terminal-release.mjs` 手机与平板均通过；报告中 `engine=xterm` 且 legacy host touch patch 为 `false`。

备注：一次 `VERIFY_TERMINAL_FILTER=drag` 的预改基线运行在终端打开等待处未进入 drag 检查，因此没有作为行为证据使用；本轮删除依赖静态不可达证明、xterm 单元测试和打包 SPA boot 边界验证。

## 待确认事项

暂无需要产品决策的事项。若后续涉及上游补丁策略、存储键迁移或对外插件 RPC 变更，会先记录在这里，不擅自做破坏性迁移。
