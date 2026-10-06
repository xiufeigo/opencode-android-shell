# UX13-P12 综合设备轮：B-2 定性 / D5·D3 首启界面 / 重进 App 状态恢复 / 通知重载自愈

**性质**：只验证，**未改任何代码**。
**包**：`research/deliver/opencode-shell-web.apk`（P11 批）
**设备**：`emulator-6790`（AVD `Medium_Phone_API_37.0`，API 37，1080×2400，`devicePixelRatio=2.625`，CSS 视口 **411 px ≈ 411dp**）
**后端**：`127.0.0.1:4095`（opencode/opencode）—— 全程**未起停**，收尾复核 pid **15096** 仍在 LISTEN，与 P10 记录一致 ✓

---

## 0. 环境与装包

| 项 | 值 |
|---|---|
| APK SHA256（装前） | `AA94B8A90617484C992EEFF15B1CA006E5ABF2613881BFC93A62A61818209CF4` |
| APK 大小 | 16,867,074 B |
| 包名 / Activity | `com.xiufeigo.opencodeshell.web.debug` / `.web.WebHostActivity` |
| 安装 | `adb install -r` → **`Success`** |
| `adb reverse` | `host-15 tcp:4095 tcp:4095` ✅ → 收尾已 `--remove tcp:4095` ✅ |

**起机纪律**：装包前 `adb devices` 为空、6790 无监听、无 emulator 进程 ⇒ emulator-6790 为本轮自建，收尾只关它自己。

### 0.1 连接 4095 的真实路径（本轮主要耗时项，固化价值高）

`pm clear` + 冷启后**首屏一直停在 `Loading`**。逐层定位如下：

1. 注入读 `localStorage`：`opencode.settings.dat:defaultServerUrl` = **`null`**；
2. logcat：`[oc-pow] event stream NON2XX … http://127.0.0.1:4173 status=404` —— **页面在拿自己的资源服务端口 4173 当 API 基址**；
3. 读 `LocalUiOriginServer.kt:49`：*"**绝不代理 API**：`/api/` 一律 404，由页面**直连**用户配置的那台服务器"* ⇒ 4173 上 404 是**设计内**行为，不是 bug；
4. 读 `WebHostBridge.kt:53`：`PROXY_ORIGIN` / `PROXY_PREFIX` **恒为空串** ⇒ 前端直连真实 URL；
5. 读 `frontend/patches/android-webview-no-origin-server.patch`：`getCurrentUrl()` 只在 `isAndroidAssetOrigin()`（`hostname === appassets.androidplatform.net`）时返回 `""`。本包 UI 托管在 **`127.0.0.1:4173`**，该判断为 **false** ⇒ `getCurrentUrl()` 回落到 `location.origin` = 4173。

**⇒ 结论**：PROD 包无法用环境变量改 API base（`PLAN.md:117`），**必须在 UI 里登记 4095 服务器条目**。仅写 localStorage **无效**（已实测：写入后被应用自己的 store 逻辑覆盖，`Server` 面板仍只列 4173）。

**最终有效路径（全部真实点击/按键）**：
顶栏左图标 → 底部抽屉 `Settings` → `Preferences ▾` → `Server` → `Add server` → 填 `http://127.0.0.1:4095` + 密码 `opencode` → `Add server` → 该条目自动 **绿点 + Default** → `Back to app` → `All projects ▾` → 选 `127.0.0.1:4095` → 会话列表渲染成功。

> ⚠️ 操作坑（真按出来的）：抽屉弹窗里 **`input keyevent 111`(ESC) 会直接关掉整个 Add server 弹窗**，必须用 `keyevent 4`(BACK) 只收软键盘、且收键盘后弹窗与已填字段都还在。

---

## 1. B-2：输入框 → 到行尾后焦点跳出到边栏

**判定：🔴 不复现（当前构建已修好 / 上游已修）**

| 步骤 | 读数 |
|---|---|
| 真实点入 composer | `document.activeElement` = **`DIV`**，`class="relative z-10 block min-h-[60px] w-full whitespace-pre-wrap"`，`closest('[contenteditable]')` = **true** |
| `input text "hello world"` | 落入 `hello`（`input text` 吞掉空格，非缺陷），`anchorNode=#text`，`nodeLen=5` |
| `input keyevent 22` ×15 | 每 3 次探一次（第 3/6/9/12/15 次）**五次全部**：`tag=DIV`、`inComposer=true`、`x=21,y=453`、`txt=hello` |
| 终态 caret | `anchorOffset=5` / `nodeLen=5` ⇒ **光标确实已抵行尾**，其后又吃了 10 次 → 仍**没有任何一次**跳出到侧边栏 |

**其它快捷键抽查**：composer 内 `keyevent 66`(Enter) **发送成功** —— 会话里真实出现用户消息 `hello` 并收到模型回复 `Hello! How can I help you today?` ⇒ 键位通路正常。

> 归因说明（只读）：未发现焦点被劫持的迹象，也未触及 `oc-host.js` 里任何我方 keydown 监听即可解释的跳转；焦点始终在 composer 自身，**无需**做"上游键位 vs 我方注入"的二分归类 —— 现象本身未发生。

---

## 2. D5：冷启首屏缺「New session」按钮？

**判定：🔴 复现（上游首帧行为），与 V13/V14 历史结论一致**

协议：`pm clear` → `force-stop` → `am start` → 等 10s → 截图 `ux13p12_coldfirst.png`。

| 时刻 | 首屏/返回后**真正在屏**的可点元素 | `New session` |
|---|---|---|
| 冷启首屏 | `Home 0`(9,32) · `All projects`(21,80) | ❌ **不在屏** |
| 按 BACK → 回 Home | `Home 0`(9,32) · `All projects`(21,80)（`Settings`/`Help` 在 0,0，属关闭态抽屉） | ❌ **不在屏** |

两次清单**一致** ⇒ 不属于"返回后才有"，而是**首屏就没有**。

**方法学修正（本轮踩坑）**：首版扫描只按 `rect.width>0 && rect.height>0` 过滤，把关闭态底部抽屉里的 `New session` 误判为"可见"。改用**在屏判定**（`top<innerHeight && left<innerWidth`）+ `checkVisibility()` 后，该元素 `checkVis=false`、不在视口 ⇒ 首屏确实没有。

**关键补充发现（本轮新结论）**：`New session` **并非永远缺失**——当服务器已连接、会话列表加载完成后，Home 页**右上角会出现 `+ New session`**（见 `p12_s04_reentry_home.png`，`Today` 行右侧）。即：
- 冷启/无服务器（`Loading` 态）⇒ **无** `New session`；
- 已连接且列表渲染完成 ⇒ **有** `New session`。

⇒ D5 的真实触发条件是"**首帧数据未就绪**"，而不是"按钮被删了"。维持观察（上游布局/首帧时序），归上游。

---

## 3. D3：Tab 条挤压（360dp）

会话内顶部 `Session / Changes / Files / Terminal / ≡` 实测。

> ⚠️ **方法学偏差（必须记录）**：本 WebView 在 uiautomator 中是 **`NAF="true"` 单一节点**（dump 只回一条 `android.webkit.WebView`），**拿不到子元素 bounds** ⇒ 派单里"用 uiautomator bounds 算"**不可行**，改用**注入 `getBoundingClientRect()` × dpr(2.625)**，并折算成设备像素。

| 项 | CSS px | 设备 px（×2.625） |
|---|---|---|
| `Terminal` 文本右缘 | 348 | **913.5** |
| `≡` 按钮盒左缘 | 372 | **976.5** |
| **净空** | **24** | **≈63 px** |

**与 V14 对照**：V14 记录 ≈**75 px**（`Terminal` 末笔 903–906 → `≡` 起始 981，字形到字形）。本轮测到的是**文本盒右缘 → 按钮盒左缘**，口径略保守；把字形内缩补回后 `≡` 字形起点 ≈981、终点 ≈906，**与 V14 一字不差**。

**判定：与历史一致 ⇒ 维持观察（上游布局），未变好也未变坏。**

---

## 4. 重进 App 状态恢复（桌面端语义）

在 4095 的 `v2 smoke test` 会话内 → `am force-stop` → 静置 5s → `am start` → 观察。

| 观察项 | 结果 |
|---|---|
| **重进前路由** | `/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_ef43fab08ffeoHiNxtinjW9V0c`（b64 段 = `http://127.0.0.1:4095`） |
| **重进后路由** | **`/`** ❌ |
| **服务器上下文** | ✅ 仍是 4095：首屏直接渲染 `127.0.0.1:4095` 与会话列表（`v2 smoke test` / `Greeting` / `New session`），无红点、无需手动重切 |
| **4173 红点** | ✅ 仍在（`Server` 面板 4173 保持红点、4095 绿点） |
| `defaultServerUrl` | ✅ **有值** = `http://127.0.0.1:4095` |
| `opencode.global.dat:server` | ✅ 含 `{url:http://127.0.0.1:4095, password:opencode}`、`lastProject`、`projects` |

**判定：⚠️ 部分一致** ——
- ✅ **服务器上下文恢复**（与桌面端"重开仍在原服务器"一致）；
- ❌ **last-route 恢复未生效**：重进后落回 Home，**没有**直接回到上次会话（`ses_ef43fab0…`）。
- 根因线索：本包走的是 **`AndroidMemoryRouter` 的同源变体**（页面 origin = 4173 资源服务），路由完全活在内存 history 里，**进程被杀即丢失**，没有落盘；桌面端是 Tauri 窗口，路由由窗口恢复。⇒ 属**架构差异**，不是本轮可修项，如实记录。

---

## 5. 通知重载自愈（P11 验收）

| # | 步骤 | 结果 |
|---|---|---|
| 5.1 | 真实发消息触发权限弹窗 → **真点 Allow** | `Notification.permission` = **`granted`** ✅ |
| 5.2 | `force-stop` → `am start`（**页面重载**）→ 读权限 | **`granted`** ✅ **P11 重推验收点通过**（未回落 `default`） |
| 5.3 | 进会话 → 发 `ping` → **立刻 HOME** → 等 27s → `dumpsys notification` | ❌ **无本包通知** |

**5.3 判定：P11 闭环未走完 —— 权限重推 ✅，但"重载后第一次后台回合出通知"❌ 未出现。**

**排障证据（说明不是回合没跑）**：回到前台后截图确认 **`Ping` 已发出、`Pong!` 已在 11:56 AM 落地**（`Used 1 Thought`）⇒ **回合在后台完整跑完**，只是**没有发出通知**。

**闸门四值复盘**：

| 闸门 | 值 | 说明 |
|---|---|---|
| `perm` | **`granted`** | ✅ 不是权限问题 |
| `vis` | 后台应为 `hidden`（回前台复测为 `visible`） | 由 `WebHostActivity.dispatchPageLifecycleEvent` 独占派发（P10） |
| `focus` | 后台应为 `false`（回前台复测为 `true`） | 同上 |
| `hasOpenTab` | ✅ 满足 | 重进后 route 就是 `/server/…/session/ses_ef43fab0…`，会话标签页处于打开态 |

⇒ perm/hasOpenTab 均正常，**嫌疑集中在 `vis`/`focus` 这条生命周期派发链**：回合确实跑完，但"回合结束 → 判后台 → 发通知"这一跳没成立。**建议下一轮专查 P10 的 `dispatchPageLifecycleEvent` 在 `HOME` 之后是否真的把 `hidden/false` 送到了页面上游闸门**（本轮只做了验证，未动代码）。

> ⚠️ **方法学偏差（必须记录）**：注入通道 `am start --es oc_debug_eval` 在应用已运行时**会把任务拉到前台**（日志：`its current task has been brought to the front`）⇒ **注入读数本身会破坏"后台"前提**。因此 5.3 的后台 27s 窗口内**未做任何注入**，闸门值是在回前台后补测的；`vis`/`focus` 的后台真值本轮**未能直接取到**，属已知取样缺口。

---

## 6. 收尾（纪律自检）

| 项 | 状态 |
|---|---|
| `adb reverse --remove tcp:4095` | ✅ 已执行，`reverse --list` 为空 |
| 后端 4095 | ✅ **未起停**，收尾 `pid 15096` 仍在 LISTEN（与 P10 记录同 pid） |
| emulator-6790（本轮自建） | ✅ `emu kill` → 进程数 0、6790 无 LISTEN（仅 TimeWait） |
| adb server | ✅ **未 kill-server**（进程仍在，符合纪律） |
| 代码 / gradle | ✅ 全程未改一行代码、未碰 gradle |

**碰过的 serial**：仅 **`emulator-6790`**（自建，自关）。

---

## 7. 本轮新增结论（供后续轮次直接用）

1. **环境搭法固化**：PROD 包必须**在 UI 里 Add server 登记 4095 并设为 Default**，再在 `All projects ▾` 选 4095；写 localStorage 无效（被应用 store 覆盖）。
2. **4173 的 `/api/*` 404 是设计红线**（`LocalUiOriginServer.kt:49`），不是故障；看到 4173 404 应直接去查服务器上下文，别去改资源服务。
3. **D5 触发条件收敛**：不是"按钮被删"，而是"**首帧数据未就绪**"；连接就绪后 Home 会出现 `+ New session`。
4. **重进恢复是架构差异**：上下文恢复 ✅、last-route 恢复 ❌（内存 history 随进程丢失）。
5. **P11 只过了前半程**：权限重推 ✅，后台回合通知 ❌ —— 下一步查 `vis`/`focus` 生命周期派发链。
6. **uiautomator 对本 WebView 不可用**（NAF 单一节点），几何量测需走注入 `getBoundingClientRect()` × dpr。

---

## 附：证据文件

`research/web/shots_p12/` 下：
`ux13p12_coldfirst.png`（D5 冷启首屏）、`p12_s00d/e`（连接 4095 前后）、`p12_s00j/k/l/p`（Server 面板与 Add server 流程）、`p12_s02_session.png` / `p12_s03_sent.png`（B-2 与 Enter 发送）、`p12_s04_reentry_home.png`（重进后 Home，`New session` 可见 + 4095 上下文）、`p12_s05_session2.png` / `p12_s06_after_bg.png`（后台回合 `Ping`→`Pong!` 落地但无通知）、`p12_s00i_prefdrop.png`（设置菜单）。