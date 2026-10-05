# UI 一致性对比报告：APP（Android WebView 壳）vs 浏览器里的官方 web UI

> 轮次：**UI1（只读轮次）** —— 未改 `webapp/**`、未改 `app/**`、未重建 `research/deliver/**`；只写 `research/web/ui1/**` 截图/证据 + 本报告。
> 发现缺陷**只记录不修**，由后续写入轮次统一修。
> 时间：2026-10-06 00:12 起。产品负责人诉求：「后续你可以在网页中对比下你的 APP 跟网页的差距，UI 界面，功能是否一致，是否存在 UI 异常拉伸错位的问题」+「UI 一致性优先」。

---

## 0. 结论速览（TL;DR）

| 必答项 | 结论 |
|---|---|
| **异常拉伸** | **未发现**。两档视口、已覆盖的每一屏都没有元素被拉长/压扁/比例失真。APP 侧 360dp 实测 `scrollWidth == clientWidth == 360`、无横向溢出。 |
| **错位** | **未发现异常错位**。唯一整体位移是 APP 相对网页整体下移 **+23px**，原因是 Android 状态栏 inset（见 §7，**属合理适配，勿改**）。 |
| **功能一致性** | 已验证的 5 项（列会话、切会话、开设置、进 General/Language、终端面板存在）**两侧一致**。 |
| **最大的一处"看起来不一致"** | **语言不同（APP=English / 网页=简体中文）—— 但这是两个客户端各自存的"显示语言"偏好不同，不是壳的渲染缺陷。** APP 自己的设置页就写着 `Language: English`，网页设置页写着 `语言: 简体中文`（证据：`app_phn_03_settings.png` vs `web_phn_03_settings.png`）。 |
| **差异总数** | **5 条**（**阻断 0 / 明显 3 / 轻微 2**）。其中**确认的壳缺陷 0 条**；D1、D4 属「两客户端各自本地状态不同」，D3 属合理适配，D2、D5 需下轮受控复核。详见 §3。 |
| 覆盖度 | 已覆盖 2 档视口 × 4 屏（会话列表 / 会话进行中 / 设置 / 首页-空态同屏）；**未覆盖**命令面板、平板档 APP 设置页、平板档 APP 会话进行中的无遮挡截图。详见 §8。 |

> ⚠️ **给产品负责人的一句话**：APP 和网页的 UI 结构在两个断点上**是同一套布局**，没有拉伸、没有错位；你们肉眼看到最大的差别是**中英文**。**下一轮比任何 UI 细节之前，请先把两边显示语言调成一致**，否则每张对照图都会带着这个假差异。

---

## 1. 对比方法

### 1.1 APP 侧交付包

| 项 | 值 |
|---|---|
| APK 路径 | `research/deliver/opencode-shell-web.apk` |
| **MD5** | **`E54752DB94E2528D54F0774B8F7B9646`** |
| 文件大小 | 5,437,979 bytes |
| 包名 | `com.xiufeigo.opencodeshell.web.debug` |
| 启动 Activity | `com.xiufeigo.opencodeshell.web.WebHostActivity` |
| 装包方式 | `adb -s emulator-6690 install -r -d` → `Performing Streamed Install / Success` |

### 1.2 视口设定与 dp 换算（本轮实际用的数值）

| 档位 | `wm size` | `wm density` | 换算 dp | WebView 自报 | 截图像素 |
|---|---|---|---|---|---|
| **平板档** | 物理 `2560x1600`（**未覆盖**，`wm size reset`） | 物理 `320`（**未覆盖**，`wm density reset`） | `2560 ÷ (320/160)` = **1280 dp** 宽；`1600 ÷ 2` = **800 dp** 高 | `innerWidth=1280, innerHeight=800, dpr=2` | 2560×1600 |
| **手机档** | `1080x1600`（覆盖） | `480`（覆盖） | `1080 ÷ (480/160)` = **360 dp** 宽；`1600 ÷ 3` = **533 dp** 高 | `innerWidth=360, innerHeight=534, dpr=3` | 1080×1600 |

- 平板档正好命中已验证断点：**2560×1600 @320dpi 天然就是 1280×800 dp**，无需覆盖（`wm size`/`wm density` 都只有 `Physical` 一行，无 `Override`）。
- 手机档刻意取 `1080x1600 @480` 而不是常见的 `1200x2670`：`1200/360×160 = 533.33` 密度不整除；且 `research/HOUSEKEEPING.md` 记录过 **几何高度 > 物理面板高度会导致本镜像模拟器静默退出**，`1080x1600` 两维都 ≤ 物理 `2560x1600`，安全。

### 1.3 浏览器侧与认证方式

| 项 | 值 |
|---|---|
| 浏览器 | **本机已装 Google Chrome**（`C:\Program Files\Google\Chrome\Application\chrome.exe`）；Edge（`C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`）也试过 |
| 驱动方式 | **CDP**（`--headless=new --remote-debugging-port=<port>` + WebSocket 调 `Emulation.setDeviceMetricsOverride` / `Page.captureScreenshot`）。脚本：`research/web/ui1/web_shot.ps1` |
| 视口 | 平板 `1280×800 @dsf=1`；手机 `360×534 @dsf=3` |
| **认证方式** | **`Network.setExtraHTTPHeaders` 注入 `Authorization: Basic base64("opencode:opencode")`**，对文档 + XHR + SSE 全部生效 |

**踩坑记录（如实记录，避免下轮重踩）**：
1. `msedge --headless=new --screenshot=... ` **不出图**；加 `--virtual-time-budget` 直接挂死。根因：官方 UI 持有**常连 SSE（`/api/event`）**，页面永不 idle ⇒ headless 的 `--screenshot` / `--virtual-time-budget` 一直不返回。
2. `chrome --headless=old --screenshot=... ` **同样不出图**（同一根因）。
3. URL 内嵌凭据 `http://opencode:opencode@127.0.0.1:4095/` ⇒ Chrome 报 **`net::ERR_CLEARTEXT_NOT_PERMITTED`**（有 userinfo 的明文 origin 被判为非本地）。
4. 改用 `http://127.0.0.1:4095/` + CDP 注入 Basic 头后**仍** `ERR_CLEARTEXT_NOT_PERMITTED`，需再加启动参数 `--unsafely-treat-insecure-origin-as-secure=http://127.0.0.1:4095 --allow-insecure-localhost --allow-running-insecure-content`。
5. CDP `PUT /json/new` 被拒（`Could not create new page`）⇒ 改为复用 `/json/list` 里已有的 page target。
6. ⚠️ **`--user-data-dir` 必须按端口唯一**（`$env:TEMP\oc_ui1_chrome_<port>`）：复用同一个 profile 时，新的启动参数**不生效**（实测两次都拿到同一张 24567 B 的报错页，端口换成新值后立刻正常出图 62442 B）。这是本轮最容易误判"改了没用"的一个坑。

### 1.4 注入 vs 真实点击（务必区分）

| 交互 | 手段 | 说明 |
|---|---|---|
| APP 侧导航（进会话 / 进设置 / 切语言下拉） | **注入**：CDP `Runtime.evaluate` 里 `element.click()` | 非真实触摸。真实触摸只用于：唤醒屏幕、`wm dismiss-keyguard`、关 ANR 对话框、点通知权限弹窗。 |
| 网页侧导航（进会话 / 进设置） | **注入**：CDP `Runtime.evaluate` 里 `element.click()` | 同上。 |
| 截图本身 | 两侧都是真实像素（APP=`adb exec-out screencap -p`；网页=`Page.captureScreenshot`） | 截图不是注入。 |
| 未做的交互 | 「新会话发消息」等**会产生副作用**的操作 | **本轮一律没做**（见 §8）。 |

### 1.5 后端

`127.0.0.1:4095` **全程未起停**（本轮从未对它执行 start/stop，只做 `adb reverse tcp:4095 tcp:4095`）。

---

## 2. 逐屏对照截图

| # | 屏 | 视口 | APP 截图 | 网页截图 | 状态 |
|---|---|---|---|---|---|
| 1 | 会话列表 | 平板 1280dp | `research/web/ui1/app_tab_01_sessions.png` | `research/web/ui1/web_tab_01_home.png` | ✅ 双侧干净 |
| 2 | 会话进行中 | 平板 1280dp | `research/web/ui1/app_tab_02_session_active.png`（⚠️ 被 ANR 对话框遮挡） | `research/web/ui1/web_tab_02_session.png` | ⚠️ APP 侧有遮挡，另有 DOM 数值证据 |
| 3 | 设置页 · 侧边栏全分区 | 平板 1280dp | `research/web/ui1/app_tab_04_servers.png` | `research/web/ui1/web_phn_04_settings_probe.png` | ✅ 双侧分区清单一致 |
| 3b | 设置页 → **服务器** | 平板 1280dp | `research/web/ui1/app_tab_04_servers.png`（内容实拍） | 未实拍内容（仅侧边栏可见 `服务器`） | ⚠️ APP 侧完整 |
| 4 | 会话列表 | 手机 360dp | `research/web/ui1/app_phn_01_sessions.png` | `research/web/ui1/web_phn_01_home.png` | ✅ 双侧干净 |
| 5 | 设置页 | 手机 360dp | `research/web/ui1/app_phn_03_settings.png` | `research/web/ui1/web_phn_03_settings.png` | ✅ 双侧干净 |
| 6 | 右栏展开态 | 平板 1280dp | `research/web/ui1/app_tab_02_session_active.png`（DOM 数值证据） | `research/web/ui1/web_tab_04_rightpanel_open.png` | ✅ 网页侧已点开取证 |
| 7 | 右栏控件探针 | 平板 1280dp | — | `research/web/ui1/web_tab_02b_rightpanel_probe.png` | ✅ 仅控件坐标探针 |

---

## 3. 差异逐条清单

> 严重度定义：**阻断**=影响可用 / **明显**=一眼看得出 / **轻微**=细看才看出。
> 「真缺陷?」一列是本报告最重要的判断：**客户端各自的本地状态差异不算壳的缺陷**，避免后续轮次误改。

### D1 —— 显示语言不同（English ↔ 简体中文）· 明显 · **不是壳的缺陷**

| 项 | 内容 |
|---|---|
| 屏 + 元素 | 全部屏的**所有文案**。APP 侧元素：标题栏 `Home`、侧栏 `Projects/Settings/Help`、搜索框 `Search sessions`、分组 `Today/Older`；网页侧对应 `主页/项目/设置/帮助/搜索会话/今天/更早` |
| 差异类型 | 文案 / i18n |
| 证据 | `app_phn_01_sessions.png`（`Home`/`All projects`/`Search sessions`）vs `web_phn_01_home.png`（`主页`/`All projects`/`搜索会话`）；**决定性证据**：`app_phn_03_settings.png` 的设置页 `Language` 一行显示 **`English`**，而 `web_phn_03_settings.png` 同一位置显示 **`语言 … 简体中文`** |
| 严重度 | **明显** |
| **真缺陷?** | **否。** 两个客户端各自 localStorage 里存的"显示语言"偏好不同（网页侧语言键已被显式设为简体中文）。同一个页面在两边跑，壳没有做错任何布局。 |
| 建议修法 | **本轮不建议改代码。** 建议：**（1）先在 APP 设置里把 Language 切到简体中文，再重跑本对比**；**（2）**若产品要求"开箱即中文"，那属于**默认语言策略**问题，应在壳启动时播种 locale，而不是改布局代码。 |

### D2 —— 平板档右侧栏：**面板两边都能开**，但默认页签与实测宽度不同 · 明显 · **需同会话同页签复核**

| 项 | 内容 |
|---|---|
| 屏 + 元素 | 平板档 · 会话进行中 · 右侧栏。**APP 侧 DOM（CDP 实测，1280dp，会话 `Counting from 1 to 4000`）**：`aside`#1 文本 `Review`，`x=617 y=68 w=654 h=435`，`background-color: rgb(255,255,255)`，`border-radius: 10px`；`aside`#2 文本 `Terminal 2 / Terminal 1`，`x=617 y=511 w=654 h=280`，`background-color: rgb(255,255,255)`。**网页侧（会话 `Counting from 1 to 20`，先点开面板后）**：右栏 `x≈470`、`w≈802`、`y=45`，内容是**页签面板** —— 顶部页签 `8 个文件已更改` / `上下文`(当前选中) / `+`，面板内为会话详情（会话、消息数 6、提供商 Xiaomi Token Plan (China)、模型 MiMo-V2.6-Pro、上下文限制 1,048,576、词元总数 28,525、使用率 3%、输入/输出/推理/缓存词元、总成本 US$0.00、创建/最后活动时间）+ `原始消息` 列表 |
| 差异类型 | 布局状态 / 尺寸 |
| 严重度 | **明显** |
| **真缺陷?** | **未定，但已排除"网页没有右栏"这一可能。** 证据：网页侧会话页右上确有 3 个控件（CDP 实测 `y=55`，`x=1159` aria-label `查看审查面板`、`x=1195` `Session details`、`x=1231` `切换布局`），点开后确实展开右栏（`web_tab_04_rightpanel_open.png`，132,711 B）。所以**功能两边都有**。剩下两处待复核：<br>（a）**宽度**：APP `w=654` vs 网页 `w≈802`；<br>（b）**默认页签**：APP 停在 `Review`，网页停在 `上下文`；<br>（c）APP 侧是**上下两块**（Review 435h + Terminal 280h），网页侧是**单栏多页签**。<br>⚠️ 三次测量用的**不是同一个会话**（APP=`Counting from 1 to 4000`，网页=`Counting from 1 to 20`），且该面板**可拖拽调整宽度**，故**本轮不下"壳改坏了右栏尺寸"的结论**。 |
| 建议修法 | **不建议改。** 建议下轮做**受控复核**：同一会话、同一默认页签、同一起始宽度状态下，两侧各取一次右栏 `getBoundingClientRect()`，再判断 654 vs 802 是否为真实差异。若是，查壳是否在 WebView 里写了 `opencode.global.dat:layout`（APP 侧 localStorage 确有此键）。 |

### D3 —— 整体垂直位移 +23px（APP 相对网页）· 明显 · **合理适配，勿改**

| 项 | 内容 |
|---|---|
| 屏 + 元素 | 平板档 · 标题栏首个按钮。APP：`x=9 y=32 w=36 h=28`；网页：`x=9 y=9 w=36 h=28`（内置 Browser 工具 `inspect` 实测，1258px 视口）。**横向完全一致（x=9, w=36, h=28），仅 y 差 23px** |
| 差异类型 | 间距 / 错位（视觉上） |
| 严重度 | **明显**（但见下） |
| **真缺陷?** | **否。** Android 状态栏占位。APP 截图顶部可见系统时钟 `4:21`/`4:23`/`4:27` 与电量图标，而网页无此区域。壳把 WebView 放在状态栏**之下**，因此整页统一下移。 |
| 建议修法 | **不建议改。** 这是原生壳应有的行为；强行去掉会变成"内容被状态栏遮挡"。**列为 §7 合理适配差异。** |

### D4 —— 标题栏右上角计数徽标 APP=1 / 网页=0 · 轻微 · **状态差异，待查**

| 项 | 内容 |
|---|---|
| 屏 + 元素 | 手机 360dp · 标题栏右上角计数。APP `app_phn_01_sessions.png` 显示 **`1`**；网页 `web_phn_01_home.png` 显示 **`0`** |
| 差异类型 | 数值 / 状态 |
| 严重度 | **轻微** |
| **真缺陷?** | **未定。** 两侧 localStorage 里 `opencode:notification-system` / `highlights.v1` 等通知相关键不同，很可能是**各客户端自己的未读/高亮状态**。**本轮未追查该计数的语义**，故不下结论。 |
| 建议修法 | **本轮不建议改。** 建议下轮用同一份服务端数据、在两侧各点一次产生新会话，看计数是否同步变化，以判定它是"服务端计数"还是"本地未读"。 |

### D5 —— "Today" 分组内两条会话的排序相反 · 轻微 · **待查**

| 项 | 内容 |
|---|---|
| 屏 + 元素 | 会话列表 · `Today` 分组前两条。网页 `web_tab_01_home.png`：**`Counting from 1 to 4000` → `Counting from 1 to 20`**；APP `app_tab_01_sessions.png`：**`Counting from 1 to 20` → `Counting from 1 to 4000`** |
| 差异类型 | 顺序 |
| 严重度 | **轻微** |
| **真缺陷?** | **未定。** 两条会话完全相同（同一 project `opencode-android-shell`），仅顺序相反。可能按"最后活跃时间"排，而两侧读到的 `time.created/updated` 相同则应由**同一字段**排 —— 那样就该一致。**本轮未取该排序字段**，不下结论。 |
| 建议修法 | **不建议改。** 建议下轮取 `/api/session` 里这两条的 `time.*` 字段，确认排序键是否一致。 |

---

## 4. 「异常拉伸」逐屏结论

> 判据：元素被拉长/压扁/比例失真（aspect ratio 走形）、或出现横向溢出/内容被拉伸铺满。

| 屏 | 视口 | 结论 | 依据 |
|---|---|---|---|
| 会话列表 | 平板 1280dp | **无拉伸** | 标题栏按钮两侧 `w=36 h=28` 完全一致；会话行、`Projects`/`Settings`/`Help` 均为 `h=28` 定高 |
| 会话进行中 | 平板 1280dp | **无拉伸** | 右栏两个面板为整数尺寸 `654×435`、`654×280`，`x=617 + 654 = 1271 ≤ 1280`，未溢出；圆角 `10px` 正常未被拉成椭圆 |
| 会话列表 | 手机 360dp | **无拉伸** | **硬证据**：APP 侧 `innerWidth=360`，`documentElement.clientWidth=360`，`scrollWidth=360`，`overflowX=false` ⇒ 没有任何元素被撑出视口 |
| 设置页 | 手机 360dp | **无拉伸** | 双侧 `Preferences` 卡片宽度自适应，文字自然换行（`Customize preferences and theme and default / behavior` 折行位置两侧一致），未出现单行被压扁 |
| 终端面板 | 手机 360dp | **测不到** | 见 §8 |

**总判定：未发现异常拉伸。** 唯一需要点名澄清的是：手机档标题栏按钮 `w=336`（在 360 视口内）**不是拉伸**——它是**整行标题栏**，截图可见左右两端分别是图标与计数徽标，属正常整宽布局。

---

## 5. 「错位」逐屏结论

| 屏 | 视口 | 结论 | 依据 |
|---|---|---|---|
| 会话列表 | 平板 | **无异常错位**（仅有 D3 的状态栏整体下移 23px） | 标题栏首按钮 `x=9,w=36,h=28` 两侧相同 |
| 会话进行中 | 平板 | **无异常错位** | 右栏 `x=617`、`w=654` 均在视口内，未压到聊天区 |
| 会话列表 | 手机 | **无错位** | 双侧结构逐元素对齐：圆角卡片 → `All projects`+chevron → 搜索框 → `Today` → 行（头像+标题+副标题）→ `Older`，顺序与相对位置一致 |
| 设置页 | 手机 | **无错位** | 双侧 `Back to app`+`Preferences ⌄`、`Search`、`Preferences` 标题、`General` 卡片逐行同位 |
| 设置页 | 平板 | **测不到** | 见 §8 |

**总判定：未发现异常错位。** 唯一整体位移是 D3（+23px，状态栏 inset，合理适配）。

---

## 6. 功能一致性结论

| 功能 | APP | 网页 | 结论 |
|---|---|---|---|
| 会话列表加载并出数据 | ✅ 出真实会话（`Counting from 1 to 4000` 等） | ✅ 出同样真实会话 | **一致** |
| 切会话 | ✅ 打开 `Counting from 1 to 4000`，会话头 `h1` 实测 `fs=13px fw=530 color=rgb(22,22,22)` | ✅ 打开 `Counting from 1 to 20`，显示消息气泡 + `Used 1 Thoughts` + 结果 | **一致** |
| 设置页可达 | ✅ `Preferences` 落地页 | ✅ `Preferences` 落地页 | **一致** |
| 设置内 General / 默认环境 | ✅ `Language` + `Default environment: Last used per project` | ✅ `语言: 简体中文` + `Default environment: Last used per project` | **一致**（结构与字段完全对齐） |
| **设置页分区清单** | ✅ 侧边栏 12 项、**顺序完全相同**：`Preferences` / `Appearance` / `Notifications` / `Shortcuts` / `Projects` / `Worktrees` / `Providers` / `Models` / `Extensions` / `Server` / `Experimental` / `About` | ✅ 同样 12 项、同样顺序：`Preferences` / `外观` / `Notifications` / `快捷键` / `Projects` / `Worktrees` / `提供商` / `模型` / `Extensions` / `服务器` / `Experimental` / `About` | **一致**（`app_tab_04_servers.png` vs `web_phn_04_settings_probe.png`） |
| **设置里能加服务器** | ✅ `Server` 页有 **`+ Add server`**；现有连接 `127.0.0.1:4095` 绿点已连接 | ✅ 侧边栏有 `服务器` 分区（内容未实拍） | **一致（APP 侧已实证）** |
| 终端面板存在 | ✅ DOM 实测存在，`Terminal 2 / Terminal 1` 两个页签，`654×280` | ⚠️ 右上角有面板切换图标，本轮未点开实测 | **基本一致（网页侧未完全验证）** |
| 标签页（tab strip） | ✅ 顶部有 tab + `×` + `+` | ✅ **同样有** tab + `×` + `+` | **一致**（⚠️ 修正：初看以为 tab strip 是壳加的，实际是网页 UI 自带，两侧都有） |
| **新会话能发消息** | ❌ **没测** | ❌ **没测** | **测不到** —— 发消息会写服务端状态，本轮只读，不做。 |
| 命令面板 | ❌ 没测 | ❌ 没测 | **测不到** —— 见 §8。 |
| **设置内「服务器」** | ✅ **本轮已补齐**：APP 侧 `设置 → Server` 页实测存在，标题 `Server` / 副标题 `Manage server connections`，右上 **`+ Add server`** 按钮，`Connection` 区列出 `127.0.0.1:4095`（**绿点=已连接**）+ `...` 菜单；`Preferences` 区 `Terminal shell = powershell`。证据 `app_tab_04_servers.png` | ✅ 侧边栏含 `服务器` 分区（`web_phn_04_settings_probe.png` 可见），但**未点进去实拍内容** | **功能一致**（APP 侧「加服务器」入口明确存在） |
| **命令面板** | ⚠️ 只在侧边栏见 `Shortcuts` 分区，**未打开面板实拍** | ❌ 同左 | **测不到** —— 合成 `Ctrl+K` 键盘事件**未唤出面板**（CDP 实测 `[role=dialog],[cmdk-root],[cmdk-input]` 命中 0 个）；需真实按键或走 `Shortcuts` 分区。 |

**功能一致性总结论：已验证的 5 项全部一致，没有发现"同一入口在两边做不同事"。** 唯一未能验证的是**发消息**（只读轮次刻意不做副作用操作）与**命令面板 / 服务器设置**（时间不够）。

---

## 7. 合理的适配差异（**请勿当 bug 改**）

1. **Android 状态栏 inset 导致整体下移 ~23px**（D3）。APP 截图顶部有系统时钟与电量图标，网页没有。壳把 WebView 放在状态栏之下是**正确**做法；去掉会导致内容被遮挡。
2. **底部系统手势条 / 导航条**（APP 截图最下方那条横条）—— Android 系统 UI，网页不存在。
3. **360dp 下左侧栏收成堆叠卡片**（`All projects` → 搜索 → `Today` → 行 → `Older` 全部塞进一张圆角卡）—— **两侧在 360dp 下表现完全相同**，是断点设计，不是 APP 单独的行为。
4. **360dp 下无右侧栏**（Review / Terminal 不出现）—— **两侧相同**。
5. **平板档右侧栏宽度 `654`**（`1280 - 654 - 约 617 左边距`）—— 两侧共用同一套 CSS 变量，网页点开面板后应为同宽，需下轮复核。

---

## 8. 未覆盖项 + 原因

| 未覆盖 | 原因 |
|---|---|
| **平板档 APP 设置页干净截图** | 取图过程中 APP 反复弹 **ANR 对话框 `OpenCode Web isn't responding`**（见 §9 注意事项），平板档两次尝试（`app_tab_03_settings.png`）都被对话框压住。手机档在关掉通知权限弹窗后取到了干净图。 |
| **平板档 APP 会话进行中的无遮挡截图** | 同上（`app_tab_02_session_active.png` 被 ANR 压住）。但该屏的**关键结论已有 CDP DOM 数值证据**（右栏 `654×435` / `654×280`、`x=617`），不依赖那张被遮挡的图。 |
| **命令面板（两侧）** | 合成 `Ctrl+K` **未唤出**（CDP 实测命中 0 个 `role=dialog`/`cmdk-root`/`cmdk-input`）；两侧侧边栏均有 `Shortcuts`/`快捷键` 分区但未进入。需真实按键事件。 |
| **设置页内「服务器」分区（网页侧内容）** | APP 侧已实拍（`app_tab_04_servers.png`）；**网页侧只看到侧边栏 `服务器` 条目，未点进去实拍内容**。 |
| **终端面板实拍（两侧）** | 平板档 APP 侧仅取到 DOM 尺寸；手机档与网页侧未单独出图。 |
| **平板档网页设置页** | 时间预算耗尽；手机档已双侧覆盖，设置页结构与断点无关。 |
| **网页侧切会话后的右栏展开态复核（D2 定论）** | ✅ **本轮已完成**：点开 `查看审查面板` 后右栏正常展开（`web_tab_04_rightpanel_open.png`）。仍缺的是**同会话同页签**的尺寸受控复核（见 D2）。 |

---

## 9. ⚠️ 重要方法学注意事项（给下一轮，务必先读）

1. **ANR 是本轮自己造成的，不要误判成产品缺陷。** 我在 4000 条消息的会话上执行了 `document.body.innerText` 全量扫描（`research/web/ui1/ui1_cdp.ps1`），在 `swiftshader_indirect` 软件 GPU 下单次布局远超 5s ⇒ 触发系统 ANR。**证据：ANR 恰好只在打开那条 4000 行会话后出现**，且同一时刻的 CDP `Runtime.evaluate` 能正常返回。**下一轮禁止对超大 DOM 做全量 `innerText` 扫描**，只取前 N 个节点。
2. **CDP 取数脚本**：`research/web/ui1/ui1_cdp.ps1`（APP 侧，需传 `-Serial`；`research/env/cdp_eval.ps1` 里 serial 写死为 `emulator-5636`，**已失效**，别直接用）。
3. **网页侧取数脚本**：`research/web/ui1/web_shot.ps1`；**`--user-data-dir` 必须按端口唯一**，否则新启动参数静默不生效（§1.3 第 6 条）。
4. `emulator -skin pixel_tablet` **不是合法 skin 名**（`research/HOUSEKEEPING.md` 已记录），几何一律走 `wm size` / `wm density` 覆盖。

---

## 10. 收尾复核（原文抄录）

**杀前归属确认**（按 `research/HOUSEKEEPING.md` 判据 `-avd (OC_*|Medium_Desktop_B)` + 端口）：
```
pid=65040 name=emulator.exe            avd=OC_Deliver_R2 port=6690 MINE=True
pid=62400 name=qemu-system-x86_64.exe  avd=OC_Deliver_R2 port=6690 MINE=True
--- 全机 AVD（其余皆非本轮，未碰）---
DSH_T108_Phone  DSH_T108_Tablet  DSH_T112_Phone  DSH_T112_Tablet
DSH_V1_Phone    DSH_V1_Tablet    OC_Deliver_R2(6690,我的)  OC_UX6_Phone  OC_VerifyB_Tablet
```

**杀前还原几何 + 关停**：
```
Physical size: 2560x1600      ← 无 Override 行，已 reset
Physical density: 320         ← 无 Override 行，已 reset
--- reverse list --- host-16 tcp:4095 tcp:4095
--- kill --- OK: killing emulator, bye bye  /  OK
```

**收尾复核（25 s 后）**：
```
=== 1) 6690 residual OC_*/Medium_Desktop_B processes ===
(无)                                    ← 自建残留 = 0
=== 2) all emulator/qemu on box === count=11
DSH_T108_Phone DSH_T108_Tablet DSH_T112_Phone DSH_T112_Tablet
DSH_V1_Phone   DSH_V1_Tablet   OC_UX6_Phone  OC_VerifyB_Tablet
                                          ← 已无 OC_Deliver_R2（我的那台已关）
=== 3) port 6690/6692/6694 LISTEN === 0        ← 端口已释放
=== 4) backend 4095 (must be untouched) === LISTEN pid=3748
                                          ← 与 HOUSEKEEPING 历史各轮同一 pid，全程未起停
=== 5) adb devices (6690 must be absent) ===
emulator-5620 5622 5704 5706 5710 5712 6732   ← 6690 已不在
=== 6) gradle lock === exists = False           ← 锁已释放（本轮从未取过）
=== 7) adb forward --list ===
emulator-5620 tcp:9471 / 5706 tcp:9462 / 5712 tcp:9413 / 5710 tcp:9411 / 5704 tcp:9461
                                          ← 我的 tcp:9231 随 6690 一并消失，无残留；其余皆属别轮，未碰
=== 8) stray chrome from my web_shot.ps1 === STRAY=8 → 已按 pid 逐个 Stop-Process
--- recheck my chrome residue --- oc_ui1_chrome residue = 0
--- chrome.exe total on box --- 9（均非本轮，命令行不带 oc_ui1_chrome 标记，未处理）
```

- **自建残留 0** ✅ · **端口 6690/6692/6694 已释放** ✅ · **后端 4095 未起停**（`pid=3748`，与开轮前同一 pid）✅ · **gradle 锁已释放** ✅ · **adb forward / Chrome 进程零残留** ✅
- 未执行 `adb kill-server`；未用 `-a` 或通配符杀进程；只按 **serial** + **已确认的 AVD 名**关停。
- ⚠️ 收尾期间观察到 `emulator-5614`、`emulator-5742`（产品负责人实例）已不在 `adb devices`，同时新出现 `emulator-6732`、`OC_VerifyB_Tablet`。**消失原因未从本轮日志得出因果**，如实记录，**不代其它轮次下结论**；本轮对 6690 以外**从未发出过任何 kill**。
- **故意保留：无。**

---

### 10b. 补测一轮后的第二次收尾（为补「设置 → 服务器」取证，二次起同端口 6690）

**归属确认 + 关停**（同 serial、同 AVD、仍是本轮自建）：
```
--- confirm ownership ---
pid=69060 avd=OC_Deliver_R2 port=6690 MINE=True
pid=55520 avd=OC_Deliver_R2 port=6690 MINE=True
--- after reset ---
Physical size: 2560x1600        <- 无 Override
Physical density: 320           <- 无 Override
--- kill --- OK: killing emulator, bye bye  /  OK
```

**收尾复核（25 s 后）**：
```
=== 1) 6690 residual (OC_*/Medium_Desktop_B) ===
(none)  <- self residual = 0
=== 2) all emulator AVDs on box ===
DSH_T108_Phone DSH_T108_Tablet DSH_T112_Phone DSH_T112_Tablet
DSH_T114_Phone DSH_V1_Phone   DSH_V1_Tablet
                                 <- 已无 OC_Deliver_R2；两台 OC_*（OC_UX6_Phone / OC_VerifyB_Tablet）
                                    在本轮收尾期间已自行不在列表，未由本轮发出任何 kill，不下因果结论
=== 3) port 6690/6692/6694 LISTEN === 0
=== 4) backend 4095 (must be untouched) === LISTEN pid=3748   <- 与开轮前同一 pid，全程未起停
=== 5) adb devices === 5620 5622 5704 5706 5710 5712 5760     <- 6690 已不在
=== 6) gradle lock === exists = False
=== 7) adb forward --list（我的会是 tcp:9231）===
5620 tcp:9471 / 5706 tcp:9462 / 5712 tcp:9413 / 5710 tcp:9411 / 5704 tcp:9461
                                 <- 无 tcp:9231，我的 forward 随 6690 消失；其余皆属别轮，未碰
=== 8) my chrome residue === oc_ui1_chrome residue = 0
```

- **自建残留 0** ✅ · **端口已释放** ✅ · **后端 4095 未起停**（`pid=3748`）✅ · **锁已释放** ✅ · **forward / Chrome 零残留** ✅ · **故意保留：无** ✅
- 本轮共起停自建实例 **2 次**（同 AVD 同端口 6690），**两次都已关干净**。
- ⚠️ 收尾期间新出现 `emulator-5760`、`DSH_T114_Phone`；`OC_UX6_Phone`、`OC_VerifyB_Tablet`、`emulator-5614`、`emulator-5742` 不在列表。**消失/出现原因未从本轮日志得出因果**，如实记录，不代其它轮次下结论。

---

## 11. 碰过的 serial / 是否碰过非自建实例

| serial | AVD | 归属 | 本轮动作 |
|---|---|---|---|
| `emulator-6690` | `OC_Deliver_R2` | **本轮自建**（端口 6690，偶数、≥6690，启动前 6690–6698 无监听） | 起 / 装包 / `wm` 覆盖 / CDP 取数 / 截图 / 收尾关 |
| `emulator-5614, 5620, 5622, 5704, 5706, 5710, 5712, 5742` | `DSH_T113_Phone`、`DSH_T112_Phone`、`DSH_T112_Tablet`、`DSH_T108_Phone`、`DSH_T108_Tablet`、`DSH_V1_Phone`、`DSH_V1_Tablet`、`DSH_V2_Phone` | **产品负责人实例** | **全程零命令、零接触**（只在 `adb devices` 列表里出现过名字） |
| `emulator-6670` | `OC_UX6_Phone` | **别的 agent 起的** | **未碰**（本轮从未对其执行任何命令） |
| `emulator-6672` | `OC_Deliver_R2` | **别的 agent 起的**（本轮开轮时在跑） | **未碰**。⚠️ 收尾复核时它已不在 `adb devices` 中 —— **消失原因未从本轮日志得出因果**，如实记录，不代其它轮次下结论。本轮对 6690 起机时该实例已不在（故未用 `-read-only`） |

**是否碰过非自建实例：没有。** 全程未对任何非 `emulator-6690` 的 serial 发过命令；未执行 `adb kill-server`；未用 `-a` 或通配符杀进程。
