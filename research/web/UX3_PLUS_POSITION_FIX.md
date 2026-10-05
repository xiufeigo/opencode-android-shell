# UX3 — 把「打开终端 / 打开文件」选择面板从顶栏「+」挪到右侧栏标签条「审查」旁「+」

> 状态：**完成**（2026-10-05，设备 emulator-6550 / AVD `OC_Deliver_R2`，本轮自建已关）
> 任务来源：产品负责人 2506×1673 截图批注 —— ❌ 错位置 x≈504,y≈103（顶栏「会话」右的 `+`，UX1/UX2 挂这里）；
> ✅ 对位置 x≈1288,y≈195（右侧栏标签条「审查」右边的小 `+`，同一行还有面板切换图标）。

## 1. 两个「+」的选择器与定位方法（**DOM 结构定位，无像素硬编码**）

定位方法：真机（emulator-6550，2560×1600 / dpr2）上通过宿主调试通道
`am start ... --es oc_debug_eval "<js>"`（`WebHostActivity` 的 DEBUG-only 注入通道，结果打 logcat
`OcWebHost/Eval`）跑探针：① `document.elementFromPoint` 在截图坐标处反查祖先链；
② `document.querySelectorAll("[role=tablist]")` 枚举标签条及其全部按钮。
探针脚本与原始输出：`research/web/ux3_probe.js` `ux3_probe2.js` `ux3_probe4.js`、`research/web/ux3_probe_logcat.txt`。

### ❌ 顶栏「+」（错误旧挂点，本轮摘除）

- **DOM 证据**（探针原文）：`elementFromPoint(295,46)` → `<button aria-label="New session">`，
  祖先链 `button < div < div < header < div < div`（rect 281,32,28,28 CSS px）。
- **选择器原文**：`<header> 内 aria-label="New session" 的 IconButton`。
  上游 `_upstream/packages/app/src/shell/titlebar/titlebar.tsx:621-629`
  （`icon={<Icon name="plus" />} onClick={openNewTab} aria-label={language.t("command.session.new")}`）。
- 它**不在任何 `[role=tablist]` 内**（顶栏那条标签带不是 tablist）⇒ 新 finder 结构上命不中。

### ✅ 右侧栏「+」（正确挂点，本轮挂载）

- **DOM 证据**（探针原文，审查面板打开后）：
  - 右侧栏有**两条**非 header 的 `[role=tablist]`：
    - 条0（审查条）：`[Toggle file tree(面板切换图标)@开头] [role=tab]「Files Changed 8」 「Open file」@尾部`；
      `elementFromPoint` 命中尾部按钮 = `aria-label="Open file"` 的 svg 按钮 —— **即产品负责人指的「+」**
      （其设备坐标 (1308..1336,160..188) 与负责人标注的 (1288,195) 吻合）。
    - 条1（终端条）：`[role=tab]×N（Terminal 1..N） "Close terminal"×N "New terminal"@尾部`。
  - 手机档（1080×2400 / dpr 2.625）探针：条0 变为移动端视图标签（Session/Changes/Files/Terminal，无 svg 按钮），
    条1 同上（尾部 `aria-label="New terminal"`）。
- **选择器原文**（`oc-host.js` `findPlusButton()`）：
  > 所有 `[role=tablist]` 中第一个 `!list.closest("header")` 且可见的 list，
  > 取其内 `button,[role=button]` 里：不是 `[role=tab]`、不在 `[role=tabpanel]` 内、含 `<svg>`、
  > `aria-label` 不以 `close|dismiss|关闭|關閉` 开头的可见元素中 **DOM 顺序的最后一个**。
  - 「取最后一个」的依据：「+」在 tablist **尾部** sticky 容器，面板切换图标在**开头** sticky 位，
    close × 排在各自 tab 之后、「+」之前。⚠️ 中途实测发现"取第一个"会错抓 `Toggle file tree`
    （面板切换图标），已纠正为取最后一个（手机档探针 `ux3_probe4.js` 证实）。
  - 终端触发器 `findTerminalTrigger()`：非 header tablist 内 `aria-label` 命中 `/terminal|终端/i`
    且非 close 前缀的按钮（部署版 = `New terminal`）。

## 2. 顶栏摘除 + 原行为恢复证据

- **上游原行为**（源码证据）：`_upstream/.../shell/titlebar/titlebar.tsx:271` `openNewTab` ——
  在会话路由下 `tabs.newDraft(...)` **直接新建会话草稿标签页**（:274-296 有 pending 复用分支）。
- **摘除方式**：新 `findPlusButton` 只查非 header 的 tablist ⇒ 顶栏「+」**零拦截**，上游 handler 原样执行。
- **设备证据（平板档，真点）**：`research/web/ux3_t_tablet_top_plus.png` —— 真点顶栏「+」后
  **顶栏标签带出现新「Session」草稿标签**（openNewTab 行为），**我们的面板没有弹出**。
- 手机档无顶栏「+」（移动端头部只有标题），无此按钮可验，如实标注 n/a。

## 3. 右侧栏挂载 diff + 原功能取舍

- **diff**：`git diff webapp/src/main/assets/www/oc-host.js`（+152/−39 行内只有此一个生产文件；
  全仓 Kotlin **零改动**，`git diff --stat` 仅 `oc-host.js` + `HANDOFF_WEB_SHELL.md`）。
  关键改动：finder 换成 tablist 结构判据；新增 `scanTablistButtons(list,pickLast)` /
  `findTerminalTrigger()`；`runTerminal()` 改为 **passThrough**（把合成 click 还给上游按钮，
  `onClick` 里 `if (passThrough) return` 放行）。
- **上游右侧栏「+」原功能**（两条都查实）：
  - 部署版实测（注入绕过拦截直点，`ux3_probe3.js` + `ux3_t_upstream_side_plus.png`）：
    终端条「+」`aria-label="New terminal"` ⇒ **新建终端标签**（Terminal 4 出现）；
    审查条「+」`aria-label="Open file"` ⇒ 打开应用内文件树。
  - v2 源码 `_upstream/.../session/files/session-side-panel.tsx:458-533`：同一结构位是
    「Add tab」Menu.Trigger（菜单 = 打开文件 :503-516 + 浏览器 :517-530）。
- **取舍（原功能并进面板当选项，依据：不能从 WebView 外部调到 Solid 内部 handler，
  重写必丢；passThrough 点击上游按钮自己则上游升级也不丢）**：
  - 「打开终端」= passThrough 点击 `findTerminalTrigger()` 找到的按钮 ⇒ 新建终端功能**逐字保留**；
  - 「打开文件」= 宿主原生文件选择器 `WebHostBridge.pickFile`（UX1 定义的可验证真动作），
    承接"Open file"同一意图（应用内文件树依赖工作区 fs，宿主内由原生选择器替代，且实测真弹系统选择器）。

## 4. 绊线 + 反向验证

- 绊线：`webapp/src/test/.../PlusPositionTripwireTest.kt`（5 条），静态扫描 `oc-host.js`：
  ① 旧文案判据正则字面量 `/新建会话|新会话|new session/i` 不得回归；
  ② finder 必须限定 `[role=tablist]` 且显式 `closest("header")` 排除 + `CLOSE_PREFIX` 排除 close ×；
  ③ 「打开终端」必须 `passThrough=true` 且有 `if (passThrough) return` 放行。
- 反向验证（**撤回接线时断言必须真红**）：把 `git show HEAD:...oc-host.js`（UX1 顶栏接线）喂同一扫描器：
  `oldLabelCriteria=True tablistScoped=False headerGuard=False passThrough=False` ——
  三条断言**全部会真红**；另有两个故意写坏的样本（`redTopBarWiring` / `redNoHeaderGuard`）由
  `scanner flags ... as red` 两条用例守着（防假绿绊线）。新接线扫描全绿，单测 5/5 通过。

## 5. 设备证据（全部为**真实点击** `adb shell input tap`；仅 §3 上游原功能那步注明为注入）

| 档 | 步骤 | 截图 |
|---|---|---|
| 平板 | 真点右侧栏审查条「+」⇒ 面板弹出，同屏可见「Files Changed 8」审查条 | `research/web/ux3_t_tablet_side_plus_panel.png` |
| 平板 | 面板点「打开终端」⇒ **Terminal 5** 出现（上游新建终端功能保留） | `research/web/ux3_t_tablet_terminal_created.png` |
| 平板 | 真点**顶栏**「+」⇒ 出现上游「Session」草稿标签、**面板未弹**（位置摘净） | `research/web/ux3_t_tablet_top_plus.png` |
| 手机 | 真点终端条「+」（先滑动标签条露出）⇒ 面板弹出 | `research/web/ux3_p_phone_side_plus_panel.png` |
| 手机 | 面板点「打开文件」⇒ **系统原生文件选择器**弹出 | `research/web/ux3_p_phone_terminal_created.png` |
| 手机 | 面板点「打开终端」⇒ **Terminal 6** 出现 | `research/web/ux3_p_phone_terminal6.png` |

注入步骤声明：§3 上游原功能取证（`ux3_probe3.js` 改 tablist role 绕过自家拦截后 `btn.click()`，
截图 `ux3_t_upstream_side_plus.png`）是**注入**，其余所有 "tap" 均为真实点击。

## 6. 三条回归（均好）

1. **未配置灰态 + 「去设置服务器」入口（首启不弹配置框）**：`pm clear` 后首启直进灰态页
   （全部条目「不可用」徽标 + 「去设置服务器」按钮，无配置框弹出）——`research/web/ux3_p_grey_state.png`；
   点进入配置对话框（URL 预填 4095、用户名固定 opencode）`ux3_p_setup_dialog.png`；口令 opencode
   保存并连接后首页完整加载 `ux3_p_reconfigured.png`。单测 `NotConfiguredShellTest` 16/16 绿。
2. **口令为空 ⇒ 绝不启动代理（F21）**：本轮 Kotlin 零改动（`git diff --stat` 只有 oc-host.js + 文档）；
   `EmptyPasswordLaunchGateTest` 16/16 绿。
3. **终端被杀后续接同一会话（UX2）**：`am force-stop` 后重开，Terminal 1..6 全部还在、
   Terminal 6 会话内容（PowerShell 提示符/光标）原样恢复 —— `research/web/ux3_p_reattach.png`；
   `TerminalReattachTest` 11/11 绿。

## 7. 单测

`.\gradlew.bat --console=plain :webapp:testDebugUnitTest :app:testDebugUnitTest` ⇒ **BUILD SUCCESSFUL，0 失败**。
计数（test-results XML 汇总）：**webapp 207（+5 新增绊线），app 6136，合计 6343，0 失败 0 跳过**。
（UX3 前 webapp 为 202 ⇒ 用例数只增不减；未删/放宽任何既有断言。）

## 8. 三份新产物（`research/deliver/`）

| 产物 | 体积 | md5（当前有效） | 签名 |
|---|---|---|---|
| `opencode-shell-web.apk`（debug） | 5,437,748 B | `5d3bcc15362acd113dea924ccb3da4fb` | CN=Android Debug，证书 SHA-256 `e86b70066546bd0bdc2e6cb4d970704bc48b1a244af59c664564f030cca0510a` |
| `opencode-shell-web-release-nor8.apk` | 3,992,324 B | `f47e281c994b572784ba83d2fe511684` | 同上（既有 sharedDebug signingConfig） |
| `opencode-shell-web-release-r8.apk`（releaseR8 变体） | 1,447,376 B | `4917af7293f2e15a3459d0945ae5476a` | 同上 |

**包内含源自查**：三份 APK 解包 `assets/www/oc-host.js` 的 md5 均为 `a3ce11503f31cf03d6ee58c182c82cad`，
与工作区 `webapp/src/main/assets/www/oc-host.js` **逐位一致**（3/3 MATCH=True，原始输出见会话记录
`ux3_hashes.txt` 与 §自查命令）。

## 9. 交接文档同步

**已同步 `HANDOFF_WEB_SHELL.md` §0.1**：顶表三行更新为新体积/md5 并标注"UX3 重建值"；
新增"UX3 复核"块（含包内含源自查值），并**显式作废** UX2 批 `d6015bf2…`/`28ee9ffc…`/`69bbe05f…`、
F19/F18 批 `03d7888f…`/`5227c2ac…`/`9f511c5a…` 及 F16 批更早全部旧值。

## 10. 收尾复核输出（逐字）

```
OC_Deliver_R2 process: GONE          （按进程名 qemu/emulator 过滤复核；曾出现的 "STILL RUNNING" 是
                                      我 pwsh 命令行里自带 AVD 名造成的自匹配误报）
port 6550: FREE
adb devices: no emulator-6550        （现存 4 台均为负责人自建 DSH_T97/T99，未碰）
gradle lock released                 （rm -- .agent-gradle-lock）
```
补充：`emulator-5706`（T95_Phone）在收尾前已自行从 adb 设备列表消失——进程表里没有它的
qemu/emulator 进程，且本轮所有 kill 只按 `OC_Deliver_R2` 命令行精确匹配（PIDs 48776/51132），
**未碰过它**，其消失时序与本机操作无关（如实标注，供 Lead 知悉）。

## 11. 本轮碰过的 serial + 是否碰过非自建实例

- 碰过：**emulator-6550**（AVD `OC_Deliver_R2`，本轮自建，`-port 6550`，已关）。
- 未碰任何非自建实例：负责人的 emulator-5706/5730/5732/5900/5902 无 tap/install/kill；
  仅 `adb devices` 只读观察。后端 127.0.0.1:4095 未起停，仅 `adb reverse tcp:4095`。

## 12. 未闭环项

- 手机档无顶栏「+」，「顶栏摘除」负向证据只有平板档（结构判据在手机档天然不命中，逻辑等价）。
- 「打开文件」用宿主原生选择器承接上游应用内文件树（取舍见 §3），若产品要回应用内文件树需另立项。
- `git stash@{0}`（ux3-wip-plus-position-unverified）仍在 stash 列表，未动——本报告结论以本轮独立核实为准。
