# UX7 — 手机档四连修（B-3 终端空白 / B-1 终端条+误拦 / B-4 左滑右侧栏 / B-5 IME 裁切）+ Temp·无Git 行号级回答

状态：**完成**（2026-10-05 23:2x Asia/Shanghai 收工）
开始：2026-10-05 22:34（Asia/Shanghai）｜ 架构前提：无代理·官方直连（UX6）
设备：**手机 = emulator-6652**（OC_UX6_Phone，wm size 1200×2670 / density 420，即产品负责人同款几何）
**平板 = emulator-6654**（OC_Deliver_R2，2560×1600，回归）
后端：127.0.0.1:4095（全程未起停；adb reverse tcp:4095 双机已建）

> 证据标注通则：**真实点击/真实手势/真实软键盘** = `input tap` 触达页面控件、系统 IME；
> **程序注入** = `input text`（文本）、`input keyevent`（按键）、`--es oc_debug_eval`（JS 探针，DEBUG 构建专用通道）。

---

## 0. 结论速览

| Bug | 结论 | 修法 |
|---|---|---|
| **B-1** 终端条「+」被误拦 | ✅ 复现 → 已修（手机点 `+` 直出 Terminal 2，两档验证） | oc-host.js 拦截锚定 `aside#review-panel`，手机档零拦截 |
| **B-4** 左滑/右上角按钮 | ✅ 结论：上游手机档**无右侧栏也无左滑手势**；「右上角按钮」= `≡` More options，**当前构建未被拦截**（上游菜单正常）；负责人截图里的"右上角"实为 B-1 的终端条「+」 | 与 B-1 同根因，同一修复覆盖 |
| **B-3** 手机终端空白+无输入 | ✅ 当前构建**不复现**（浅色+深色、两档、PTY 全链路量化验证正常） | 无需修（官方直连架构下 PTY 链路正常；报告留完整对照证据） |
| **B-5** 键盘弹起 composer 裁切 | ✅ 当前构建**不复现**（两屏量化验证：IME 起 padding-bottom=336.38px、收归 0 自动恢复） | 无需改（沿用 `--oc-ime-bottom` 抬升机制；撤回机制 ⇒ 既有绊线真红） |
| 顺带：Temp / 无 Git | ✅ 行号级答案 + 设备点击截图 | —（纯说明） |

---

## 1. B-1（最高优先之一）：终端标签条「+」被误拦 ⇒ 已修

### 1.1 复现（修复前，基线包 = UX6 交付件 `fba7db16…`，**真实点击**）

手机档进会话 → Terminal 标签 → 点终端条 `+`（390,418）⇒ **弹出我们的「打开/打开终端/打开文件」面板**
（截图 `ux7_phone_plus_tap.png`；logcat 原文 `[oc-plus-chooser] side-tablist plus intercepted; chooser shown @76`）。

### 1.2 根因（选择器抓宽了，行号级）

旧 `findPlusButton()`（改前 oc-host.js）：**扫"页面上第一个不在 `<header>` 内的 `[role=tablist]`"**，
取其尾部按钮。而：

- **审查条（我们真正要拦的「+」）是桌面限定**：`session-side-panel.tsx:257`
  `<Show when={isDesktop() && !!params.id}>` —— **手机档（<768px）根本不挂载**；
- 于是手机档 DOM 里第一个非 header tablist = **终端标签条**（`terminal/panel.tsx:305` Tabs.List），
  其尾部按钮 = **`+`（aria-label="New terminal"，`panel.tsx:330-335`，`onClick=terminal.new()`）** ⇒ 被抓。
- 手机档视图标签条（SessionMobileViewTabs，`review/view.tsx:44-64`）虽也在 DOM，但其按钮全是
  `[role=tab]`；唯一例外 `≡` 菜单触发器是 `<Tabs>` 的**兄弟节点**（`view.tsx:66-102` 的 `<Menu>` 在
  Tabs 外），不在 tablist 内 ⇒ 旧扫描跳过它命中终端条。

**两条 tablist 的 DOM 区分（平板实测 DOM dump，b64 注入）**：

```
审查条+: aside#review-panel（session-side-panel.tsx:259）→ [role=tablist]（:317）
         尾部 sticky 容器（:458）IconButton aria-label="Open file"（:475-482；有浏览器能力时
         是 "Add tab" Menu，:490-500）
终端条+: #terminal-panel → [role=tablist]（panel.tsx:305）尾部 IconButton
         aria-label="New terminal"（:330-335，terminal.new()）
```

### 1.3 修法（oc-host.js，收窄拦截）

- 新增 `findReviewTablist()`：**只取 `document.getElementById("review-panel")`**（宽度 <2px 视为收起⇒不拦）
  内的 `[role=tablist]`；
- `findPlusButton()` 只从该 tablist 用原 `scanTablistButtons(list, pickLast=true)` 找「+」；
- **手机档无 `#review-panel` ⇒ findPlusButton() 恒 null ⇒ onClick 早退放行 ⇒ 手机档全页面零拦截**
  （B-1 修法要求第 3 条"手机档不该有任何拦截"落地）。
- 「找到后干什么」一字未动（UX5 的 passThrough 双路线原样保留）。

### 1.4 修复后证据

| 场景 | 操作 | 结果 | 证据 |
|---|---|---|---|
| 手机档 | **真实点击**终端条 `+` | **直接出现 Terminal 2**（激活、活 PowerShell 提示符），**不弹面板**；logcat 零拦截记录 | `ux7_phone_plus_after.png` |
| 手机档 | 全页面排查 | 无任何位置弹我们的面板（`≡` = 上游菜单 Usage/Session details，见 B-4） | `ux7_phone_hamburger_tap.png` |
| 平板档 | **真实点击**审查条 `+`（精确中心 1767,247） | **仍弹我们的面板**（回归通过）；logcat `side-tablist plus intercepted; chooser shown @757` | `ux7_tablet_review_plus3.png` |
| 平板档 | 面板「打开终端」 | passThrough 点上游「New terminal」：`upstream New terminal re-clicked via passThrough (panel already open)` ⇒ Terminal 2 建立 | `ux7_tablet_panel_terminal.png` + logcat |
| 平板档 | **真实点击**终端条 `+`（精确中心 1596,911） | **直接新建终端**（Terminal 1 重建激活），零拦截 | `ux7_tablet_terminal_plus2.png` |
| 平板档 | 面板配色 | 浅色主题下跟随上游 token（`--surface-raised-base` 等） | `ux7_tablet_review_plus3.png` |

### 1.5 绊线 + 反向验证（真红）

- 新增 `Ux7PhoneSweepTripwireTest`（5 用例）：锚定审查条 / 终端条永不拦截 / UX5 透传路线保留 +
  两个坏样本（旧全页扫描、错锚 `#terminal-panel`）扫描器报红。
- **撤回实测**：`git show HEAD:…oc-host.js`（UX6 旧版）覆盖回源码 ⇒ 跑 UX7 绊线
  ⇒ **`terminal strip plus must never be intercepted` + `plus interception anchored to review panel only`
  两条真红**（5 tests, 2 failed）。修复版恢复后全绿。

---

## 2. B-4：手机档左滑打不开右侧栏；右上角按钮行为不对

### 2.1 上游手机档的正规形态（行号级）

- **上游手机档没有"右侧栏"这个概念**：`SessionSidePanel`（审查/文件面板）桌面限定
  （`session-side-panel.tsx:257` `isDesktop() && params.id`；`isDesktop = createMediaQuery("(min-width: 768px)")` `:87`）。
  手机档的对应物 = 会话页顶部 **Session / Changes / Files / Terminal 视图标签条**
  （`SessionMobileViewTabs`，`review/view.tsx:23-117`，`data-slot="session-mobile-view-navigation" :42`）。
- **上游没有任何"左滑打开侧栏"手势**：origin/v2 `packages/app/src` 全量 grep `swipe/Swipe` = 0 命中
  （仅 dnd-kit 排序约束，非打开手势）；`MobileDrawer`（`mobile-drawer.tsx`，@corvu/drawer `side="bottom"`）
  只在**顶栏标签抽屉**（`titlebar.tsx:463-592`）与 Session details（`review/view.tsx:105-112`）使用，
  均无 swipe-to-open。
- **手机档右上角那个按钮 = `≡`**（`view.tsx:74-85`，aria-label=common.moreOptions）：上游语义 =
  打开菜单（**Usage** / **Session details**，`view.tsx:94-99`）。

### 2.2 设备实测（手机档，修复前基线与修复后一致）

- **真实点击 `≡`**（1130,303）⇒ 弹出**上游自己的菜单**（Usage / Session details）
  （`ux7_phone_hamburger_tap.png`）——**不是**我们的面板，当前构建未被拦截。
- 结论：产品负责人 B-4 截图里"右上角点开是我们面板"的那个按钮，就是 **B-1 的终端条「+」**
  （位于终端面板**右上角**）——与 B-1 完全同根因；B-1 修复后该路径已归上游。
- **左滑**：上游本身无此手势（代码证据如上）；我们的注入层也**没有任何触摸/手势拦截**
  （`InputPathHotspotTest` 守着"注入层无逐键监听、touch dispatch 无 JS"）。**不修，修了就等于自造手势。**

### 2.3 平板档回归

右侧栏/审查条 `+`/终端条行为全部正常（见 §1.4 表格后四行）。

---

## 3. B-3（最高优先）：手机终端一片空白 + 打字没反应 ⇒ 当前构建不复现

### 3.1 对照实验（bug 队列要求"先做平板对照"）

| 档 | 现象（修复前基线包 = UX6 交付件） |
|---|---|
| 手机 1200×2670 | **正常**：Terminal 1 出现、PowerShell 提示符渲染、`echo`（注入文本）有回显（PowerShell 中文参数提示） |
| 平板 2560×1600 | **正常**：底部终端面板 Terminal 1 活提示符（`ux7_tablet_session.png`） |

⇒ 若按产品负责人当时所见为"空白+无反应"，**该现象在当前官方直连构建上两档均不出现**。

### 3.2 逐项排查（bug 队列清单）

1. **PTY 是否连上** ✅：logcat 原文 `OcPerfJS res #121 api … /api/pty/pty_10c82b3a…/connect-token`（200，27ms）
   → WS `/api/pty/{id}/connect?…ticket=…`（`packages/client/src/solid/pty.ts:23-38`：先认证换 ticket，
   WS 走 ticket 不带 Authorization——**官方直连下同源页面+Basic client，链路完整**）。
2. **xterm 是否在渲染** ✅：logcat 有 `[ghostty-vt]` 渲染告警（= 终端仿真器在跑）；
   容器高度非 0（终端区占屏实测 ~1300 device px）。
3. **输入是否到达** ✅：软键盘（真实弹起）输入 `echo` 有语法高亮 + Enter 后 PowerShell 回显
   （`ux7_phone_terminal_typed.png`）。
4. **修复后再验**：新构建上 Terminal 1/2 两个标签、活提示符（`ux7_phone_plus_after.png`）；
   **深色主题**（产品负责人原报告环境）单独再验：终端正常（`ux7_phone_terminal_dark.png`）；
   release-nor8 包冒烟：终端正常（`ux7_phone_release_nor8_smoke.png`）。

### 3.3 口径说明（为什么不"修"）

B-3 发现于 2026-10-05 20:5x，早于 UX6 交付；当时构建的 PTY 来源/认证路径与现构建不同
（bug 队列自己标注"本轮正在改去掉回环代理、官方直连，来源/认证路径变了 ⇒ 重点看"）。
**当前官方直连构建上 PTY 链路（connect-token→WS→xterm→输入回显）全链路实测正常，无可修对象**；
若负责人在他的安装包上仍见复现，请核对其包 md5（应为本报告 §7 的 UX7 值）。

---

## 4. B-5：软键盘弹起 composer 底部被裁切（两屏）⇒ 当前构建不复现

### 4.1 实测（手机 1200×2670，**真实软键盘**：点输入框系统 IME 弹起）

| 屏 | 状态 | 量化读数（b64 注入探针） | 截图 |
|---|---|---|---|
| 会话界面 | IME 起 | innerHeight=1018；`--oc-ime-bottom=336.38px`；composer form `padding-bottom=336.38px`，内容区完整落在键盘上方 | `ux7_phone_session_ime_after.png` |
| 会话界面 | IME 收 | `--oc-ime-bottom=0.00px`，composer 回到视口底部 [893,997]，**自动恢复** | 同探针回读 |
| 新建 session | IME 起 | form [418,858] pb=336.38px，输入行+模型行+发送钮完整可见 | `ux7_phone_newsession_ime_after.png` |
| 新建 session | IME 收 | 布局恢复（产品负责人关键线索"收起自动恢复" ✅ 复验一致） | — |

### 4.2 根因口径（bug 队列四条要求逐条回答）

1. **IME 配置**：`windowSoftInputMode="adjustResize"` 声明在位；`InsetsHost.kt:72`
   `setDecorFitsSystemWindows(window, false)`（边到边）⇒ IME 不改变 layout；宿主按 insets 投递
   `--oc-ime-bottom`（CSS px，实测 336.38 = 883 device px ÷ dpr 2.625，与 IME frame 高一致）。
2. **上游 composer 跟随方式**：上游 SPA **不消费** `visualViewport`/insets（visualViewport.height 实测
   = innerHeight = 1018，不随 IME 变）；跟随由**我们的注入层**做：`installImeLiftStyle()`
   给 `form:has(textarea/contenteditable)` 垫 `padding-bottom:var(--oc-ime-bottom)`（oc-host.js:184-205）。
3. **不是"页面整体被顶起"**：布局视口不缩（边到边），是 form 垫高把 composer 内容上抬。
4. **与旧"IME 内容级重叠 0 px"验证的关系**：那次（IME1/G3 轮）验的正是这套抬升机制的**改后**形态
   （手机 412×915 与平板 1366×768 双档重叠=0）；本轮以负责人同款 1200×2670 重新量化，机制行为一致。
   负责人 21:56/22:01 的报告应取自更早构建或个别状态；**当前交付构建两屏均不裁切**。

### 4.3 绊线（既有）+ 反向验证（真红）

机制守护 = `ImeOcclusionTest`（8 用例，含 `facadeConsumesImeBottomForComposer` 资产契约）。
**撤回实测**：把 oc-host.js 里 `var(--oc-ime-bottom,0px)` 全部替换为 `0px`（撤回变量消费）⇒
`facadeConsumesImeBottomForComposer` **真红**（8 tests, 1 failed）。恢复后全绿。
本轮 B-5 **未改代码**（无可修对象），故无新增绊线。

---

## 5. 顺带（产品负责人问，行号级）：新 session 底部 `Temp` 与 `无 Git`

设备环境：新 session（draft）屏，项目 = opencode-android-shell（git 仓库）。

### 5.1 `Temp`（T 图标 + 下拉）= 运行位置选择器（PromptWorkspaceSelector 的 Menu.Trigger）

- 实现：`_upstream/packages/app/src/new-session/workspace/selector.tsx:107-132`（Menu.Trigger）；
  **label 规则 `:68-73`**：`selected()==="main"` → "Local"；`value==="create"` → 新建 worktree；
  **否则显示 `getFilename(props.value)`**——即**当前选中 worktree 的目录名**。
  产品负责人看到的 "Temp" = 他那个工作区目录的名字（临时会话目录），图标 = `outline-worktree`
  （`:44-48`，main 时是显示器 `monitor`）。
- **点下去发生什么**（菜单 `:134-252`）："Run session in" 分组——Local repository（主检出）/
  New worktree（新建 git worktree，可选基于分支 `:273-369`）/ 已有 worktree 列表。
- **影响会话的什么**：决定**新会话的工作目录/分支**——主检出还是独立 git worktree（独立工作区，
  不污染主仓库）；选择写入 draftTab 的 worktree（`new-session/screen.tsx:33-36`）。
- **设备实测（真实点击）**：点本机的 "Local ⌄"（同一控件）⇒ 菜单弹出
  "Run session in / 🖥 Local repository ✓ / ＋ New worktree"（`ux7_phone_worktree_menu.png`）。

### 5.2 `无 Git`（显示器图标）= PromptGitStatus 徽章（纯展示，不可点）

- 实现：同文件 `:374-410`：`noGit` 时 label = `language.t("session.new.git.none")`
  （部署版 i18n 实测值为 "No Git"，zh 即"无 Git"；部署 chunk 原文
  `"session.new.git.none":`No Git``），icon = `monitor`（显示器，`:384-388`）。
- **功能**：紧跟选择器显示**所选工作目录的 VCS 状态**——有 git 显示分支名（本机显示 "main" 丸，
  `ux7_phone_terminal_echo_after.png`）；无 git 仓库显示"无 Git"。
- **点下去会发生什么**：**什么也不会**——渲染的是 `<div>`（`:400`）不是按钮，无点击语义。
  （产品上"点一次"对它不适用；这就是行号级答案的一部分。）

---

## 6. 真实 vs 注入标注汇总

- **真实点击**：终端条 `+`（手机/平板各多次）、审查条 `+`、`≡` 菜单、面板「打开终端」、
  worktree 下拉、会话列表/标签导航、输入框聚焦（系统 IME 随之真实弹起）。
- **真实软键盘**：B-5 全部 IME 起/收场景（点输入框触发系统键盘）；终端内打字（`input text` 注入文本 +
  `input keyevent 66` 回车，IME 真实在场）。
- **程序注入**：`input text`/`input keyevent`（文本与按键本体）、`--es oc_debug_eval b64:…`
  （DOM/几何探针、主题切换 localStorage）、`__ocPlusChooser.open()`（一次平板面板机制验证，
  真点复核紧随其后）。

---

## 7. 交付物（research/deliver/，UX7 批）

| 产物 | 体积 | md5 | 签名 |
|---|---|---|---|
| `opencode-shell-web.apk`（debug） | 5,437,979 B | `fa6133480c4816c98aafb7d6ac6f9173` | CN=Android Debug（SHA-256 `e86b7006…0510a`） |
| `opencode-shell-web-release-nor8.apk` | 3,545,875 B | `3a9a1ced56673589f34a075ceb06718b` | 同上 |
| `opencode-shell-web-release-r8.apk` | 1,265,633 B | `8d5910bab108dd5e50825467a9788ebd` | 同上 |

- **包内含源自查**：三份 APK 内 `assets/www/oc-host.js` md5 = `6e1e7354fc670a5ce87c990d43c743ec`
  = `webapp/src/main/assets/www/oc-host.js` 源文件 md5（3/3 MATCH=True）。
- **release-nor8 设备冒烟**（手机，暗色主题）：官方 UI 正常、终端渲染 + 活 PTY（`ux7_phone_release_nor8_smoke.png`）。
- **HANDOFF_WEB_SHELL.md 已同步**：§0.1 顶表三行换 UX7 值、新增"UX7 复核"块、UX6 块降级为存档、
  UX6 三个 md5（`525b6847…`/`a854824f…`/`fba7db16…`/资产 `784e5d27…`）**显式作废**。
  **"我已同步交接文档"** ✅（2026-10-05 23:1x）。

## 8. 回归用例数

`:webapp:testDebugUnitTest` + `:app:testDebugUnitTest` ⇒ **webapp 171 / app 6136，0 失败**。
（webapp 166→171：新增 `Ux7PhoneSweepTripwireTest` 5 条；**未删/未放宽任何既有断言**。）

## 9. 收尾复核

- 自建模拟器已关：emulator-6652（OC_UX6_Phone）、emulator-6654（OC_Deliver_R2）
  ⇒ `emu kill` 均回 `OK: killing emulator, bye bye` / `OK`；收尾 `adb devices` 只剩
  他方实例（emulator-5614/5620/5622/5704/5706/5710/5712/5742，非自建，全程未碰）⇒ **自建残留 0**。
- 端口 6652/6654 已释放（Get-NetTCPConnection 复核无监听）。
- 后端 127.0.0.1:4095 全程未起停（收尾实测 HTTP 200 = 在跑）。
- 碰过的 serial：**emulator-6652、emulator-6654**（均自建/可复用实例，已关）。
  **未碰过任何 DSH_*/T*/MCRemote 实例**（现场在跑的他方实例 5610/5612/5704/5706 等全程未触碰）。
- gradle 锁：已按协议获取/释放（收尾 `.agent-gradle-lock` 不存在，复核 True）。

## 10. 未闭环

1. **B-3/B-5 是"当前构建不复现"结论**——若产品负责人在其自有安装包上仍复现，需核对其包 md5 是否为本轮 UX7 值。
2. **"无 Git"徽章未拿到非 git 工作区的设备渲染截图**（本机项目全是 git 仓库；代码级证据 + 同控件分支态
   渲染截图已给，且该元素是 div 不可点）。
3. D1 回合常亮 / 后台断 SSE 仍待 UX8（按 BUG_QUEUE 执行顺序属下一轮，本轮未动）。
