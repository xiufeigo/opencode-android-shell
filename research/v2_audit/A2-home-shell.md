# AUDIT A2 — HOME + APP SHELL 导航 Delta 报告

> 范围：HOME 屏、新会话草稿流、App Shell（tabs/titlebar/drawers/notifications/routes）。
> 只记录破坏视觉/功能/交互 parity 的 delta；已在代码注释中明确记载的平台适配（窄屏底部条、settings 对话框化、单 server、无拖拽排序、头像占位、子串搜索等）不在表中，见文末"已 at parity"小节。
> v2 路径均相对于 `_upstream/packages/app/src/`；Native 路径为 `app/src/main/java/com/xiufeigo/opencodeshell/`。

## Delta 表

| # | Delta | v2 source (path:line) | Native file | Priority (P0/P1/P2) |
|---|-------|----------------------|-------------|---------------------|
| 1 | 手机底部条只显示**当前**会话：v2 移动端抽屉列出**全部标签**＋Home＋Settings＋Help 行，可切换任意标签；native `BottomTabStrip` 只渲染 `activeTab`，手机上看不到/切不到其他已开会话（只能靠 palette stepping 绕行） | `shell/titlebar/titlebar.tsx:463-592`（mobile drawer 全列表） | `ui/nav/BottomTabStrip.kt:56-104`；`ui/App.kt:790-812`（只传 `activeTab`） | **P0** |
| 2 | 无 `DraftTab` 模型：v2 每次 `+` 都开一个**新草稿标签**（多草稿并存、每个草稿独立 composer/目录/分支/MCP 状态）；native 新会话是**单例路由**（`launchSingleTop`），在草稿页再按 `+` 无效果，切走即丢内存状态 | `shell/tabs/tabs.tsx:235-248`（`newDraft`）、`:265-282`（`promoteDraft`）、`:286-359`（`prepareSession` 失败回滚） | `ui/App.kt:169,416-418`（`Route.NEW_SESSION`）；`ui/home/NewSessionScreen.kt:83-101`（`remember` 内存态） | **P1** |
| 3 | 草稿不持久化：v2 草稿 composer 按 draftID 持久化（`Persist.draft`），进程重启/切标签回来可恢复；native `picked/attachments/extraDirectories` 全是 `remember`，路由弹出或进程死亡即丢 | `shell/tabs/tabs.tsx:114-119`（`removeDraftPersisted` 反证其存在）、`composer/persistence` | `ui/home/NewSessionScreen.kt:83-101` | **P1** |
| 4 | Home 会话行**无上下文菜单**：v2 右键/长按（500ms）/`ContextMenu` 键/`Shift+F10` 出菜单＝重命名（行内编辑器，Enter 保存/Esc 取消/IME 保护）＋导出…＋删除…；native 只有单击打开，Home 上改名/导出/删除三项功能**不可达**（rename API 存在但 Home 未用，删除仅 session 屏内有） | `home/sessions/view.tsx:443-730`（行）、`:481-513`（rename）、`:694-701`（delete）、`:476-479`（右键）、`:597-621`（长按） | `ui/home/HomeSessionsList.kt:419-461`（纯 `clickable` 行） | **P1** |
| 5 | 新会话**工作区选择器缺失**：v2 草稿 composer 下方有 `main / 新建 worktree / 已有 worktree` 选择器＋分支搜索（50 条）＋`lastUsed` 记忆＋`mod+alt+l` 循环；native 只有项目下拉，无分支/无 worktree 概念 | `new-session/workspace/controller.ts:46-253`、`new-session/workspace/selector.tsx:11-372`、`new-session/commands.tsx:48-54` | `ui/home/NewSessionScreen.kt:203-209,248-388`（仅 project 菜单） | **P1** |
| 6 | 新会话 **summary 浮层＋MCP 控制缺失**：v2 右上有 summary popover（项目卡＋git 行＋server/MCP 面板，发送前 `prepare` 校验 MCP 状态）；native 无该入口，MCP 选择/预检整条链路不存在 | `new-session/view.tsx:91-109`（`SummaryPopover`）、`new-session/summary.tsx:11-64`、`new-session/mcp.ts:11-115` | `ui/home/NewSessionScreen.kt`（无对应代码） | **P1** |
| 7 | `+` 与 Home 按钮**忽略路由上下文**：v2 `+` 在 session 路由继承当前 model 开草稿、在 home/draft 用 `newSessionProject()`（选中＞最近＞首个）；Home 已在前台时按 Home 按钮**跳回最近标签**（`toggleHome`）；native `+` 永远裸跳路由，Home 按钮永远回 HOME（已在 Home 时是空操作） | `shell/titlebar/titlebar.tsx:271-322`（`openNewTab`）、`shell/tabs/tabs.tsx:485-497`（`toggleHome`） | `ui/App.kt:416-418`（`onNewSession`）、`:545-548,795-798`（`onHome`） | **P1** |
| 8 | `mod+f` 在 Home 被抢占：v2 在 Home 注册 `mod+f` = 聚焦**Home 搜索框**；native 全局 `SESSION_SEARCH` 无条件打开**时间线搜索**（Home 上无时间线，等于错误动作），且 Home 搜索框无任何聚焦快捷键 | `home/sessions/search.ts:65-73`（`home.sessions.search.focus`） | `ui/App.kt:914-917`（`GlobalChord.SESSION_SEARCH → TimelineSearch.open()`） | **P1** |
| 9 | 搜索只覆盖已渲染的 64 行：v2 搜索源是**未截断**的 `searchRecords`（`allRecords`）＋支持粘贴 sessionID 直达（`looksLikeSessionID` → `lookup`，含 `lookingUp` 加载态）；native 先 `take(64)` 再过滤，64 名开外的会话搜不到，无 ID 直达 | `home/sessions/controller.tsx:254`（`searchRecords: allRecords`）、`home/sessions/search.ts:86-101` | `ui/home/HomeScreen.kt:148-159` → `ui/home/HomeSessionsList.kt:96-98` | **P1** |
| 10 | 通知**声音从不播放**：v2 turn 完成/出错且对应 tab 开着时按 `settings.sounds.*` 播放；native `soundAgent/soundPermissions/soundErrors` 三项设置**有存储无消费**（无 MediaPlayer/SoundPool），属死设置 | `shell/notifications/notification.tsx:231-233,258-260`、`shell/notifications/sound.ts:89-102` | `ChatViewModel.kt:1814-1843`（`notifyFor` 只进 `AppNotifier`）；`SettingsStore.kt:149-151` | **P1** |
| 11 | 无**系统级通知**：v2 失焦时走 OS 通知（可点击跳回会话，跨窗口去重＋焦点抑制）；native 只有应用内横幅，后台/切后台后无任何提醒 | `shell/notifications/notification.tsx:243-249,273-279`、`shell/notifications/coordinator.ts:56-70` | `notify/AppNotifier.kt`（纯内存 feed）；`ui/App.kt:1400-1445`（应用内横幅） | **P1** |
| 12 | 进入会话**不清未读**：`markNotificationsViewed` 有定义**零调用方**，`viewed` 只在 raise 瞬间判定；v2 打开会话即 `markViewed`（行/列未读数清零），native 外会话横幅常驻到手动清除 | `shell/notifications/notification.tsx:313-363` | `ChatViewModel.kt:232`（仅定义）；`ui/session/SessionScreen.kt`（无调用） | **P1** |
| 13 | 悬浮新建按钮缺文案：v2 是带 `+ "New session"` 文案的 `ghost-muted` 按钮（`h-7 px-2`，定位在首个分组头同行）；native 是无文案纯图标按钮（28dp 盒），视觉层级与可读性都弱一档 | `home/sessions/view.tsx:105-118` | `ui/home/HomeSessionsList.kt:158-162,621-638` | **P1** |
| 14 | 标签**不持久化**：v2 全部 tabs（含顺序、draft、pane）落盘，重启恢复；native `openSessions` 是 run-scoped 内存列表，`LastRouteStore` 只恢复**单个**路由，重启后标签全丢、Home 行"已打开"标记全灭 | `shell/tabs/tabs.tsx:72-78`（`persisted(Persist.window("tabs"))`） | `ui/nav/LastRouteStore.kt`；`ui/App.kt:314`（`remember openSessions`） | **P1** |
| 15 | 无 `tab.close (mod+w)` / `tab.reopenClosed (mod+shift+t)`：v2 有 25 深度的 closed 栈＋重开；native chords 表无此两项、无 closed 存储，键盘用户关错即丢（且关标签只能点 ✕） | `shell/titlebar/titlebar.tsx:397-413`、`shell/tabs/closed.ts:6-29`、`shell/tabs/tabs.tsx:370-390` | `ui/App.kt:974-984`（`GLOBAL_CHORD_COMMAND_IDS` 无此两项） | **P1** |
| 16 | Help 行是"后续版本"占位：v2 Help 外链 `desktop-feedback`；native 点 Help 只显示 4s 提示文案，无动作 | `home/projects/controller.tsx:179-182` | `ui/home/HomeUtilityNav.kt:56-83` | **P1** |
| 17 | 搜索结果无键盘操作/无障碍语义：v2 是 `role=listbox`，`↑↓` 移动、`Enter` 打开、`Esc` 关闭＋失焦、中键/ctrl 点击后台打开、悬停高亮跟随；native 结果行仅可点，无焦点导航、无 `Esc`、无 active 项语义 | `home/sessions/view.tsx:313-347,368-419`、`home/sessions/search.ts:123-135` | `ui/home/HomeSessionsList.kt:164-179,255-297` | P2 |
| 18 | Older 分组兜底标题缺失：v2 当 today+yesterday 为空时 Older 分组标题显示 "Recent sessions"；native 恒为 "Older" | `home/sessions/controller.tsx:342-345` | `ui/home/HomeSessionsList.kt:393-416`（`oc_home_group_older` 恒定） | P2 |
| 19 | 底部条未命名标签 fallback 文案错误：v2 全标签 fallback 统一为 session 标签（"Session"）；native `SessionTab` 用 `oc_new_session`（"新建会话"），桌面条已用 `displayTitle` 纠正、底部条没纠正 | `shell/titlebar/tab-title.ts:4-6` | `ui/nav/BottomTabStrip.kt:144-145`（应为 `oc_tab_session`） | P2 |
| 20 | 空状态与 v2 有出入：描述文案 `maxLines=1` 截断（v2 居中换行）＋间距为 8+20dp（v2 `gap-4`+`mb-1`）；另无项目时 v2 会**隐藏** ✎ 与空态按钮（`canCreateSession`），native 常显（点后靠 draft 页默认目录兜底） | `home/sessions/view.tsx:105-118,136-143,762-790`、`home/sessions/controller.tsx:258` | `ui/home/HomeSessionsList.kt:541-574`；`ui/App.kt` 未传 `canCreate` | P2 |
| 21 | 项目下拉未选项文案：v2 显示 "All projects" 且下拉内有 "All" 行可清 scope；native 显示 section 标题 "Projects"，清 scope 靠"再点已选项"（代码注释有记，但文案 delta 仍在） | `home/projects/view.tsx:97-110,195-205` | `ui/home/HomeProjectsList.kt:222-227`（缺 `oc_home_projects_all`） | P2 |
| 22 | 时间排序缺回退与平局决胜：v2 按 `updated ?? created`＋id tiebreak；native `updatedAt` 只读 `time.updated`（缺则 0 → 沉底进 Older），且无 tiebreak | `shell/layout/helpers.ts:13-17` | `OpenCodeClient.kt:257`（`optLong("updated", 0L)`）；`ui/home/HomeSessions.kt:94-97` | P2 |
| 23 | 列表配额策略不同：v2 按目录 fair-share 保留（每目录 LIMIT＋recent-window 桶）＋5000/页分页＋前 2 条预 sync；native 单次全量拉取＋全局 `take(64)`，多项目时冷门项目行可能被挤出（与 #9 同源，渲染侧） | `home/sessions/index.ts:14-72`、`home/sessions/controller.tsx:95-107` | `ui/home/HomeScreen.kt:117-135` | P2 |
| 24 | 项目行 ⋮ 菜单整组缺失：导入会话（JSON）、编辑项目、清除通知（依赖未读索引，native 无）三项无入口；仅 ✎（保留）与 ✕（关闭上浮为常显，属已文档适配）可达；"在文件管理器中显示"为桌面专属可豁免 | `home/projects/view.tsx:733-765`、`home/projects/controller.tsx:107-150` | `ui/home/HomeProjectsList.kt:349-399` | P2 |
| 25 | 草稿路由语义差异：v2 为 `/new-session?draftId=`（未知 draftId 回 `/`，支持 `?prompt=` 预填，有 workspace/provider tips 与不兼容 server 面板）；native 为 `?directory=`，无 draftId 守卫、无 `prompt` 参数、无 tips | `new-session/route.tsx:17-29,53-66`、`new-session/screen.tsx:72-80`、`new-session/view.tsx:168-269` | `ui/App.kt:169,190-192,605-633` | P2 |
| 26 | 无应用内 Toast 系统：v2 `showToast` 具 variant/icon/action/自动消失（改名/导出/建会话/MCP 预检失败都走它）；native 错误反馈是散装 `android.widget.Toast`＋draft 页 `SessionErrorCard`，样式/位置/可操作性都不对齐 | `shell/notifications/toast.tsx:13-26` | `notify/` 无对应物；各处 `Toast.makeText` | P2 |
| 27 | 桌面键盘导航键整组缺失：历史前进/后退（`mod+[`/`mod+]`）、标签循环（`ctrl+tab` 系）、数字定位（`mod+1..9`）、中键关标签、标签双击重命名——native chords 表与 tab 组件均无 | `shell/titlebar/titlebar.tsx:131-146`、`shell/titlebar/tab-strip.tsx:251-275,412-428`、`shell/titlebar/tab-nav.tsx:152-165,201-209` | `ui/App.kt:962-999`；`ui/nav/DesktopTabStrip.kt` | P2 |
| 28 | 通知 feed 约束语义差异：v2 上限 500＋30 天 TTL＋按**事件 id**精确去重；native 上限 50、无 TTL、按"同会话同 kind 5s 窗口"粗去重（突发多事件会吞通知） | `shell/notifications/notification.tsx:61-69`、`shell/notifications/coordinator.ts:46-54` | `notify/AppNotifier.kt:70-76,153-158` | P2 |
| 29 | 平板区间（768–1024dp）布局分叉：v2 该区间是"堆叠全侧栏＋底部实用导航行"；native 以 1024dp 一刀切，该区间走手机 dropdown（侧栏不可见、需点开） | `home/route.tsx:27-59`（767 断点＋`md/lg` 行） | `ui/home/HomeScreen.kt:250,313-358`（`LG_BREAKPOINT = 1024.dp`） | P2 |
| 30 | 无应用内更新 pill（桌面端标题栏更新入口；Android 走应用商店可豁免，列此仅防遗忘） | `shell/titlebar/titlebar.tsx:99-113,736-784` | 无对应代码 | P2 |

## 已 at parity（抽查确认，无需跟进）

- **外壳卡片几何**：`m-2` 8dp 边距＋`bg-base`＋`rounded-10`＋raised 阴影＋内容 `max-w-1080` 居中；双列 `280dp＋32dp gap`；桌面 `pt-12/48dp`、侧栏 `mt-14+pt-52px=108dp` 均对齐（`HomeScreen.kt:243-264,365-381`、`HomeProjectsList.kt:488`）。
- **分组桶语义**：Today/Yesterday/Older 按本地历日、空组不渲染、未知时间进 Older（`HomeSessions.kt:131-155` ≈ `controller.tsx:329-351`）；64 行上限、骨架 4 行、行高 `h-10`/圆角 6、`pr-10` 预留归档槽（归档上游 `SHOW_HOME_SESSION_ARCHIVE=false`，两侧一致禁用）。
- **搜索框 closed 态**：`h-9`/圆角 6/`layer-02@60%`、scoped placeholder 用 basename、scope 下隐藏行级项目名（`showProjectName`），三处与 `search.ts:45-53`、`controller.tsx:256` 一致。
- **项目列数据源**：以 client-local 已打开 store 为准、`GET /project` 仅做 enrich、最近关闭栈真实可用——与 `home/model.ts` 一致；toggle 选择/再点清除 scope 亦一致。
- **Tab 关闭数学**：`SessionTabs.close` == `nextTabAfterClose`（右优先→左→Home），`displayTitle` 含 fallback 正则，均有单测锚点（`SessionTabs.kt:20-76`）。
- **桌面顶条几何**：`h-9` 36dp、`bg-deep`、`gap-6dp`、Home 图标＋可横滑标签＋`+` 顺序、Home 在前台呈 pressed（`DesktopTabStrip.kt` ≈ `titlebar.tsx:441-458`）。
- **Dialog 栈语义**：`show` 替换整栈（`dialog.tsx:140-149`）已对齐；palette 在 Home 无文件分类（`PaletteHostKind.HOME`）；settings 保留路由 deep-link 入口再弹层（已文档适配）。
- **首屏门**：无 server 时整屏 `ConnectServerScreen`（`routes.tsx:79`）；归档会话客户端过滤；`pathKey/matchKey` 大小写/分隔符归一；行级"已打开"标记 `h-3 w-0.5` 位置；横幅抑制已读（`viewed`）条目。
- **草稿提交主干**：先选项目→`POST /session?directory=`→首条 prompt 即发→`popUpTo(NEW_SESSION, inclusive)` 回会话（Back 落 Home），与 `promoteDraft` 原子替换等价（worktree/create 回滚分支除外，见 #5）。
