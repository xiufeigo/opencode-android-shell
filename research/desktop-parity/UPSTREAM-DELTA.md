# 官方源码增量对比：2.0.26 pin 与官方最新

核对日期：2026-10-15。

| | 版本 | 提交 | 日期 |
| --- | --- | --- | --- |
| 本仓库基准（[upstream.json](../../frontend/android/upstream.json)） | 2.0.26 | `ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4` | 10-09 |
| 官方最新正式 tag | 2.0.29 | `042d0832f5f3a73eaa039a58a7d565776f6d4290` | 10-14 |
| 官方 dev 分支 | 未发布 | `4397ee44d038a5f17bf6ac161431673d93e563b5` | 10-15 |

对比方式是**按树对比**，不是按提交列表：该仓库 dev 频繁 rebase，同一改动会以多个提交重复出现（`--first-parent` 三天内有 1314 个提交），提交标题不能当作变更清单。基准到 v2.0.29 共 1540 个提交，dev 再领先 37 个。

临时检出（blobless，只用于本次审计，已被 `.gitignore` 的 `_scratch_root/` 覆盖，不进入提交、不影响构建检出）：

- `_scratch_root/desktop-parity/upstream-audit`：完整历史，用于 diff。
- `_scratch_root/desktop-parity/patch-probe`：放 v2.0.29 的补丁文件副本，用于验证现有补丁能否应用。

## 规模

按包统计（pin → v2.0.29）：

| 包 | 文件 | 行数 | 是否进入 APK |
| --- | --- | --- | --- |
| `packages/app` | 93 | +4572 −1313 | 是（共享 UI） |
| `packages/ui` | 15 | +755 −609 | 是 |
| `packages/gui-extensions` | 51 | +1586 −768 | 部分（终端、文件、审阅等） |
| `packages/session-ui` | 31 | +1566 −2880 | 是 |
| `packages/client` | 15 | +1721 −1422 | 是（生成类型） |
| `packages/schema` | 34 | +146 −63 | 是 |
| `packages/protocol` | 39 | +1446 −689 | 是（生成客户端） |
| `packages/desktop` | 23 | +470 −301 | 否（Electron 宿主） |
| `packages/console`、`packages/stats`、`packages/core`、`packages/server` | 423 / 87 / 180 / 52 | — | 否（服务端与站点） |

只算 `*/src` 的发布相关源码：175 个文件、+5907 −5433。

## 面向桌面与共享 UI 的变化

### 1. 未读与通知改为服务端状态（本轮最大结构变化）

客户端不再自己维护通知列表。[notification.tsx](https://github.com/anomalyco/opencode/blob/042d0832f5f3a73eaa039a58a7d565776f6d4290/packages/app/src/shell/notifications/notification.tsx) 从 +165/−263 重写：

- 未读 = 该会话最新 `time.idle` 晚于 `time.viewed`，两者都由服务端下发；客户端用 `session.view` 回报自己看到的空闲时间点。
- 旧的本地 `notification` 持久化列表变成一次性迁移源：连接 5 秒后分页遍历根会话，把“服务端显示未读、本地显示已读”的会话回报给服务端，然后 `removePersisted` 删除。
- 失败用 250 ms → 5 s 退避重试。新增 `test-browser/server-notification-state.test.ts`。

`session.view` 事件与 `time.viewed` 字段在本仓库 pin（2.0.26）的生成类型里已经存在（`packages/client/src/promise/generated/types.ts` 的 `type: "session.viewed"`、`time: { ...; viewed?: number }`），因此这不是协议新增，而是官方开始使用既有能力。服务端版本低于 2.0.26 时未读状态没有来源。

### 2. 提供方多账号与 OpenCode Console 登录

新增 [account-actions.ts](https://github.com/anomalyco/opencode/blob/042d0832f5f3a73eaa039a58a7d565776f6d4290/packages/app/src/settings/providers/account-actions.ts)（+103）与 `account-name.tsx`（+69），`settings/providers/providers.tsx` 重写 +135/−142，连接对话框 `providers/connect/dialog.tsx` +265/−82。行为：同一提供方多账号的添加、切换、重命名、删除，连接过程中的列表保持不闪动，以及 OpenCode Console 登录/服务账号 API key 连接。

### 3. 每项目自动批准权限

[auto-approve.ts](https://github.com/anomalyco/opencode/blob/042d0832f5f3a73eaa039a58a7d565776f6d4290/packages/app/src/session/requests/auto-approve.ts) 从“全局开关”改为客户端本地设置的“服务器默认 + 项目覆盖”（`projectSettingsKey(scope, projectID)`、`permissions.autoApprovesOnServer`）。会话未加载时先 `session.get` 拿到 `projectID` 再判定。新增文案 6 条。

### 4. 提问面板重做

`session/requests/session-question-dock.tsx` +184/−191，`session-ui` 新增 `question-prompt.css`(439 行)，`ui` 新增 `ui.question.custom.answerPlaceholder`（“Type your own answer…”) 与 `session.question.title`。

### 5. 文件链接与内联代码识别

新增 `gui-extensions/src/file/resolve-link.ts`（+138）与测试、`session-ui/src/components/markdown-inline-code-kind.ts`（+1916/−28）。解析 `:line`、`:line:col`、`start-end`、`#Lstart-Lend`，把相对路径解析为唯一文件或候选列表。桌面侧新增 `ui.sessionTimeline.notice.variantSwitched / variantDefault` 文案。

### 6. 设计系统与视觉

`ui` 包的 `button.css`(+260/−243)、`text-input.css`(+185/−180)、`icon-button.css`(+125/−117)、`submit.css`、`badge` 组件与 `overlays/dialog/dialog.tsx`、`context/dialog.tsx` 都改过；`package.json` 新增导出 `./styles/tokens/*`。标签栏 `shell/titlebar/tab-nav.css`(+119/−10)、`tab-nav.tsx`、`tab-popover.*`、`titlebar.css` 改动，未读点移入标签容器。

### 7. 扩展宿主与运行时

`runtime/extension/{host,render,side-region,mounted-session}` 改动很小（约 30 行）：侧栏区域关闭时把焦点从内部移出、`localFileExists` 用 `untrack` 读取。`runtime/persistence/schema.ts` 把 `withInitial` 的解码从 `onExcessProperty: "preserve"` 改为普通解码，并新增 `legacy()`（`Schema.StructWithRest`）——旧结构读取形状必须显式开放，否则多余字段被丢弃。这直接影响 Android 沿用同一存储命名空间升级时的迁移语义。`runtime/server/global-sync/child-store.ts` 有会话存储逐出相关调整（新增 `child-store-eviction.test.ts`）。

### 8. 浏览器扩展与配对（我们已移除的模块）

官方 `gui-extensions/src/browser/*` 仍在推进：`model.ts`/`panel.tsx` 增加关闭标签（含空白页）、视图适配/全屏、`usage.toggle`、`browser.preview` 工具接线；`pairing/*` 从本地路由改为 `pairing/remote.ts`——配对设备通过服务端 HTTPS 隧道从任意位置访问本机 OpenCode（“Pair a device / Remote access”）。这两块是 2.0.14 按用户要求从本项目移除的能力，升级时继续保持移除。

### 9. 桌面专属（不移植）

`packages/desktop` 的 Electron 构建配置、`ipc-rpc` 的 `effect/unstable/*` → `effect/*` 导入迁移、CLI 暂存目录改为按版本+架构（Windows ARM64 与 x64 共用 userData）；`packages/console` 收缩（移除 `console-core`、`console-mail`、`@openauthjs/openauth`）；`packages/stats`、`services` 与 Android 无关。

### 10. 依赖与工程

- Bun 要求不变：根 `packageManager` 仍为 `bun@1.4.2`，与 `upstream.json` 的 `bun` 字段一致。
- `effect` 由 `4.0.0-rc.112` 升到 `4.0.1`（catalog 与各包 peer 同步）。
- 根 `workspaces.packages` 新增 `packages/plugins/*`；`packages/console/core`、`console/mail` 被删。
- 本仓库构建没有新增 workspace 依赖：`packages/app`、`ui`、`gui-extensions` 的 workspace 依赖未变，`build.mjs` 的 `workspaces` 清单无需扩项（需在升级时以实际构建确认）。

## 对 Android 移植的影响

### 补丁兼容：机械成本低

现有 [upstream.patch](../../frontend/android/upstream.patch) 涉及 28 个文件（按路径去重），逐个核对上游 pin → v2.0.29 后只有 2 个有改动：

- `packages/app/src/home/sessions/controller.tsx`
- `packages/app/src/settings/settings.css`

把 v2.0.29 的这两个文件放进探针目录后 `git apply --check` 通过（退出码 0）；把其中任一个替换成无关内容再检查则失败（退出码 1），说明检查方法本身有效，不是空跑。也就是这两个文件虽然有改动，但改动区域与我们的补丁上下文不重叠。其余 26 个文件上游未变。下列目录在 pin → v2.0.29 之间**零 diff**，平台层补丁不需要跟随上游：

- `packages/app/src/runtime/platform/**`（`platform.tsx` 接口未变）
- `packages/gui-extensions/src/terminal/**`
- `packages/ui/src/components/resize-handle.*`

### 需要在本轮升级中复核的接入点

| 接入点 | 现状 | 升级后要做的事 |
| --- | --- | --- |
| 系统通知 shim `webapp/src/main/assets/www/oc-host.js` | 注释与判断依据直接引用官方 `notification.tsx:227,240`（`hasOpenTab` 才通知） | 官方改写了同一文件，行号与局部行为失效；复核“只投递、策略留在官方”的边界是否仍成立 |
| 未读徽标 | 改由服务端 `time.viewed` 驱动 | 服务端 ≥2.0.26 即可用；旧服务端无法同步未读，需要在验收里如实记录 |
| 中文文案 | `zh.ts`/`zht.ts` 在 2.0.29 未更新（已核实：新增 key 在 zh 中为 0 命中） | 重跑 `node scripts/extract-i18n.mjs`（默认读 `_upstream/...`，需指向本次检出），缺失的中文需要自行补或接受英文回退 |
| 持久化迁移 | `withInitial` 不再 `preserve` 多余字段，新增 `legacy()` 开放结构 | 同一存储命名空间升级后首次读取旧数据，确认没有字段丢失（尤其通知旧列表迁移） |
| 我们移除/替换的模块 | 官方 `browser/*`、`pairing/*` 仍在改动 | `sources.mjs` 的删除清单与 `builtins.typecheck.ts` 断言重新核对；类型检查会先暴露 |
| 扩展宿主 | `runtime/extension/*` 改动小（约 30 行）、`side-region` 焦点处理变化 | 我们的服务端浏览器面板挂在这个宿主上，随回归一起验证 |

### 不需要跟进的改动

桌面 CLI 分架构暂存、Electron 打包配置、`console`/`stats` 站点、配对隧道。它们是 Desktop 或服务端能力，不应被当成 Android 缺口。

## 升级建议

结论：官方最近一周（2.0.27–2.0.29，dev 再多 37 个提交）在共享 UI 上确实有真实变化，值得升；但都是产品迭代（多账号、Console 登录、项目级权限、提问面板、设计刷新、文件链接识别），没有修复 Android 侧已知缺陷，不构成紧急项。

若决定升级，按 [frontend/android/README.md](../../frontend/android/README.md) 的流程：

1. `upstream.json` 更新 `commit` / `version`（Bun 保持 1.4.2，已核对官方未升）。
2. 复核补丁：本轮的 2 个漂移文件按需调整上下文；确认 `sources.mjs` 的删除清单仍覆盖官方新增文件。
3. `node frontend/android/build.mjs`（含 app 与 gui-extensions 的类型检查、单测、Vite 构建），再 `:webapp:testDebugUnitTest :webapp:assembleRelease`。
4. 重跑 i18n 提取并核对中文本回合与缺失 key。
5. 手机/平板设备回归：通知与未读、设置各页（连接、提供方、项目选项）、提问面板、审阅与文件链接、终端。
6. 检查升级后的存储读取（旧通知列表迁移、设置未丢失）。

工作量判断：机械改动小（补丁几乎不动）；主要是验证与文案补全，属于一次常规上游升级。

## 对比阶段的未验证项

下列未验证项只属于上文的“对比”阶段；确认升级后已在“升级执行记录”中补齐构建与回归。

- 对比阶段没有在 2.0.29 上真正构建：拉取依赖与跑 Bun 需要网络，当时只做了树对比与补丁可应用性验证。
- dev HEAD 领先 v2.0.29 的 37 个提交没有逐项核对。
- 官方 browser/pairing 的改动没有在 Android 上实测（本项目已按 2.0.14 的要求移除这两块）。
- 对比用的临时检出位于被忽略的 `_scratch_root/`，不属于提交内容；需要复现时按上文重新克隆即可。

## 升级执行记录（2026-10-15）

已按上述步骤升级到 v2.0.29，`upstream.json` 的 `commit`/`version` 已更新，Bun 仍为 1.4.2。

构建与打包（全部本机实跑）：

- 源码构建：补丁无修改直接应用；`bun install --frozen-lockfile` 通过（lockfile 未被重写）；app 与 gui-extensions 的 `tsconfig.android.json` 类型检查通过；单测 route+polyfills **9/9**、终端 session-model **4/4**；Vite 生产构建成功，生成 **1625** 个 UI 文件（含中文覆盖词典 chunk；2.0.26 为 1621）。
- Android：`frontend/terminal` 测试 **17/17**，`node build.mjs` 重建 `oc-terminal.js`（与旧产物标识符集合一致，仅有压缩差异）；`:webapp:testDebugUnitTest` **245/245**（失败/错误/跳过均为 0）；`:webapp:assembleRelease` 成功，APK 版本递增到 **2.0.19 / 47**（versionCode 47、versionName 2.0.19），产物 **57,577,019 字节**（SHA-256 `dee3fdc1d9eddde2c6d9acb67c1521bf8dda66aa6e853fb6ff9c5b4256407a0c`），`apksigner verify --print-certs` 的证书 SHA-256 为 `760dc05fb4d1a125ed82cf9bff8eee622c7244d44b6ddc82a4c34e0b37b820e6`，与 CI/历史 Release 一致；包内 `assets/www/ui/opencode-build.json` 为 2.0.29，`oc-terminal.js` 与工作区逐字节一致。
- 插件不随本轮递增：`plugins/server-browser` 自身无新功能，保持 `version 0.1.0` 与 2.0.26 的 `@opencode/plugin` / `@opencode/schema` 编译与已验证范围，不扩大已验证边界。值得注意的是上游 2.0.29 给插件 SDK 新增了 `permission.assert`（评估会话权限规则，需要审批时向用户弹窗并等待回复）- 这正是插件 README 记录的 2.0.26 缺失能力；要采用它需先把插件升到 2.0.29 SDK 并在 2.0.29 服务上重新验收，属后续单独一轮。
- i18n：`scripts/extract-i18n.mjs` 按 2.0.29 源码重跑（en 1262 / zh 1106 / zht 1106）。上游缺口共 **156** 条（2.0.29 新增 27 + 既有 129）已全部由本项目的覆盖词典补齐（见下文）。

回归（打包 SPA + 测试后端 + Chromium）：

| 脚本 | 结果 |
| --- | --- |
| `verify-android-navigation.mjs` | 43 PASS / 0 FAIL |
| `verify-android-back.mjs` | 13 PASS / 0 FAIL |
| `verify-android-file-picker.mjs` | 4 PASS / 0 FAIL |
| `verify-android-resize.mjs` | 10 PASS / 0 FAIL |
| `verify-android-terminal-release.mjs` | 53 PASS / 0 FAIL（含本轮新增的两条 `drag-keeps-focus`） |

上表是首轮升级后的数字；补齐中文与递增版本号后，用最终产物又整跑了一遒：导航 43、返回 13、文件选择 4、拖拽尺寸 10、终端发布 53，均为 0 失败，且 i18n 探针 5/5。

## 回归中发现并修掉的三处问题

**1. 终端拖动抢焦点与脚本约定冲突（两条 `drag-focus-separation`）**

工作区里带着未提交的终端改动：`frontend/terminal/terminal.js` 在 pointerdown 时无条件 `textarea.focus()`。那会让任何一次拖动都抢焦点（滑一下历史就弹键盘），与脚本钉住的“tap focuses, drag does not”相反。A/B 已归因：用 HEAD 的终端产物跑该检查为 PASS，换回工作区产物为 FAIL。

修法：pointerdown 不再抢焦点，只记住手势开始时焦点是否已在终端内；拖动结束时仅当它本来就有焦点才恢复，点击仍照旧在释放时聚焦。这样“拖动不弹键盘”和“拖动不关掉已打开的键盘”同时成立。新增 `drag-keeps-focus` 检查钉住后半条（旧代码在桌面 Chromium 下也能通过，所以它防的是回归，不是当时的差异证据）。

**2. `phone/history-restore` 偶发失败**

原重开路径在 `page.reload()` 之后只等“SPA 重新着色”就去驱动重开控件；手机布局的终端藏在 “more” 溢出里，恢复尚未落地时三次尝试都会落空（失败详情里 `reopenErrors` 为空，说明是“控件没找到”而不是抛错）。修法：先等应用恢复出自己的终端页签或表面（30 s），再重开，并给重试真正的时间间隔；失败时把 engine/instances/tabs/path/请求数/控制台错误一并写入报告，避免以后再出现无法诊断的 “could not reopen”。修复后单跑 **6/6** 通过（修前为手机 2/5 失败）。

**3. 中文文案缺口**

新增 `frontend/android/i18n-zh.ts`、`i18n-zht.ts`（构建时落到 `packages/app/src/runtime/i18n/{zh,zht}-android.ts`），包裹官方词典并只补官方没翻译的 key；`upstream.patch` 把 `packages/app/src/runtime/i18n/language.tsx` 的 zh/zht 加载项改为引用包装模块。覆盖词典现为 **156** 条：2.0.29 新增的 27 条 + 该版本下官方仍缺的 129 条（连接与配对流程、第三方声明、运行计数、扩展状态、会话位置等），已与官方缺口逐键比对：无遗漏、无多余、无重复，中/繁两套键集合一致。也就是说在 2.0.29 基准下，中文界面不再有因官方缺口而回退英文的条目。

验证（一次性探针，未入库）：打包 UI 在 `locale=zh-CN` 下启动 → `html lang="zh-Hans"`、官方中文词典生效、`zh-android-*.js` chunk 被请求且内容含新文案、设置「偏好设置」页渲染出 `无需询问即可批准权限请求…`、关于页渲染出第二批的 `第三方声明`，**5/5 通过**。构建产物中 `oa/zh-android-BFW0jLh1.js`（45,837 字节）与 `oa/zht-android-Bu1khAA2.js` 已进 APK。

后续维护：`scripts/extract-i18n.mjs` 仍只读官方源码，用来跟踪下一次上游升级后的新缺口；`research/_i18n_v2/_pending_*.json` 是早前轮次的快照（455 条里 156 条在当前 `en` 中已不存在），已不再作为来源，要补译应从新的 `_i18n_v2/{en,zh}.json` 差集出发。

## 设备端验证（真实 APK + 系统 IME）

用 debug 包（debuggable ⇒ WebView 自动开 `webview_devtools_remote_*`，无需改代码；`:webapp` 不调 `setWebContentsDebuggingEnabled`，release 包仍无 CDP）在 `T91_Phone` 模拟器（`emulator-5570`，pixel_8a / xhdpi，无窗口）上跑 `scripts/verify-android-terminal-device.mjs`：

```sh
adb -s emulator-5570 forward tcp:9226 localabstract:webview_devtools_remote_<pid>
ANDROID_SERIAL=emulator-5570 DEVICE_CDP=http://127.0.0.1:9226 node scripts/verify-android-terminal-device.mjs
```

结果 **22 PASS / 0 FAIL**。与本轮焦点规则直接相关的几条：

- `older-drag-does-not-open-ime` / `newer-drag-does-not-open-ime`：真实 adb 触摸拖动滚动历史后 `inputFocused=false`、IME `visible=0`——拖动不弹键盘在真机上成立（原先 pointerdown 无条件聚焦的写法在设备套件下也会红）。
- `keyboard-{1,2,3}-opened|terminal-above-ime|height-restored|short-output-bottom`：系统 IME 三次开合，终端始终在 IME 之上，收起后几何完全恢复。
- `touch-drag-older-history` / `newer-history`、`history-drag-height-stable`、`short-output-bottom-anchor`、`production-terminal-mounted`、`real-native-bridge`。

补齐中文并递增到 2.0.19 后用**最终 debug 包**又跑一次，同样 **22 PASS / 0 FAIL**。

报告：`research/tmp/verify-android-terminal-device/2026-10-15T12-46-08-735Z-2_0_29-focus/report.json` 与 `…/2026-10-15T13-13-07-677Z-2_0_19-final/report.json`（均未入库）。设备套件没有“拖动保持已打开的键盘”这一条，那一半仍只有 SPA 的 `drag-keeps-focus` 覆盖。模拟器已 `emu kill` 并按单 serial 清理（forward 已摘、无残留进程/监听）。

环境备注（不属于仓库改动）：本机直连 github.com 被断，构建检出通过 `ghproxy.net` 代理获取同提交对象，并用直连克隆的树哈希 `6561fbef…` 校对；Bun 1.4.2 从 npmmirror 取得；`subst` 盘符会让 bun 把工作区写成绝对路径从而导致 `--frozen-lockfile` 报 “lockfile had changes”，必须用真实路径构建。

待决定项：服务端浏览器插件是否在后续轮次跟进 2.0.29 的插件 SDK（本轮按“无新功能不递增”处理，已记在上文）；APK 递增已完成（2.0.19 / 47）。

## 升级执行记录（2.1.2，2026-10-22）

已升级到 v2.1.2（`a517687fde98a7329ada3ae644a01a9fc6c4e99e`），`upstream.json` 的 commit/version 已更新，Bun 仍为 1.4.2。

- 源码构建（本机实跑）：`upstream.patch` 的 29 个文件对 v2.1.2 `git apply --check` 通过；29 个补丁目标中只有 `gui-extensions/src/terminal/panel.tsx` 上游有改动（标签栏 52→49px，改动区与补丁上下文不重叠）；`runtime/platform/**`、`gui-extensions/src/sdk/**` 零 diff。`bun install --frozen-lockfile` 通过；app 单测 route+polyfills **9/9**、终端 session-model **4/4**；app 与 gui-extensions 的 `tsconfig.android.json` 类型检查通过；Vite 生产构建生成 **1621** 个 UI 文件（2.0.29 为 1625）。
- Android：`:webapp:testDebugUnitTest` **245/245**（失败/错误/跳过 0）；`:webapp:assembleRelease` 成功，APK **2.0.21 / 49**，**57,584,944 字节**，SHA-256 `e8e61064be92b90f2168c636123758d10cac2be716a288199612da0d11bfc9c1`；`apksigner verify --print-certs` 证书 SHA-256 `760dc05f…`，与 CI/历史 Release 一致；包内 `assets/www/ui/opencode-build.json` 为 2.1.2。
- i18n：`extract-i18n.mjs` 按 2.1.2 源码重跑（en **1275** / zh 1106 / zht 1106）；新增 13 条已由 `i18n-zh.ts` / `i18n-zht.ts` 补齐（156 → **169**）。
- 回归（打包 SPA + 测试后端 + Chromium）：深浅色 **7/7**、导航 **43/43**、返回 **13/13**、文件选择 **4/4**、拖拽尺寸 **10/10**、终端发布 **52 pass / 0 fail**（4 条 info 为环境限制：oc-host 终端补丁在 xterm 引擎下按设计不安装、平板字号探针需真机）。
- 回归适配：2.1.2 不再用 `SessionTransfer.Data` 在客户端预校验导入文件，改为把任意 JSON 交给服务端校验；`verify-android-navigation.mjs` 的“无效会话文件”用例改用**畸形 JSON**，仍钉住“客户端解析失败、不发导入请求”。
- 已知依赖：2.1.2 的免费额度 UI 需要服务端 ≥2.1.2 产出 `provider.free-limit`（旧服务端静默不生效）；“每回合变更文件”不依赖新服务端。
- 环境备注：github.com 直连仍不可用，构建检出 origin 指向 `ghproxy.net` 镜像；Bun 1.4.2 从 npmmirror 取得（`_scratch_root/tools/bun-1.4.2/`）。构造期间不在仓库内持久化凭据。

## 升级执行记录（2.1.4，2026-10-23）

已升级到官方 tag v2.1.4（`68bc26b7da1990ce10ffecfbc1e09eb13aa5d824`）。2.1.3 把根 `packageManager` 升到 `bun@1.4.4`，本仓库 `upstream.json` 与 CI 的 Bun 一并跟上。APK 版本为 **2.0.25 / 53**。

上游 2.1.2 → 2.1.4 在共享 UI 上的主要变化：Pierre Highlights 替换代码块/差异高亮；桌面与 Web 会话显示 MCP 征询表单（`session-elicitation-dock`）；堆快照命令只在 Electron `ctx.desktop` 下注册。现有补丁目标里上游只改了 `desktop-menu.ts`（文档链接改为 `/v2/docs`，扩展菜单改为原地插入）和 `runtime/i18n/en.ts`（新增 `session.elicitation.selectPlaceholder`），上下文不重叠，`upstream.patch` 与 `sidebar.patch` 无需改 hunk。

- 源码构建：`bun install --frozen-lockfile` 通过。app 单测改为带 `packages/ui/test/solid-jsx.ts` 预加载（Bun 1.4.4 下 TSX 否则找不到 `jsxDEV`），route+polyfills **9/9**、终端 session-model **4/4**。类型检查改为 `bun check --no-script -b -p tsconfig.android.json`，因为上游 `typecheck` 脚本不再把多余参数当作 tsconfig。app 与 gui-extensions 均无类型错误。Vite 生产构建生成 **1722** 个 UI 文件（2.1.2 为 1621）。
- Android：`:webapp:testDebugUnitTest` **245/245**（失败/错误/跳过 0）。`:webapp:assembleRelease` 编过资源合并与 dex，停在 `:webapp:validateSigningRelease`：本机没有 CI 那把密钥（默认路径 `~/.android/debug.keystore` 不存在，用户指定的 `C:/sensorsdata/main/packages/debug.keystore` 也不在这台 Linux 构建机上）。没有自行生成或改用另一把密钥。签名与 GitHub Release 交给打 tag 后的既有 `package.yml`（证书 SHA-256 `760dc05f…`）。
- i18n：按未打补丁的 2.1.4 源码重跑 `extract-i18n.mjs`（en **1276** / zh 1106 / zht 1106）。官方缺口 **170** 条，覆盖词典全部补上；其中本轮新增的是 `session.elicitation.selectPlaceholder`。侧栏 8 条英文是本仓库补丁写入 `en.ts` 的，不计入上游缺口。
- 插件：`plugins/server-browser` 仍使用 `@opencode/plugin` / `@opencode/schema` `^2.1.2`，本轮没有改插件实现。
- 服务端依赖：MCP 表单需要服务端发出 `session.form`；免费额度仍要服务端 ≥2.1.2 的 `provider.free-limit`。
