# UX4 主题缺陷修复报告

**日期**：2026-10-05 18:14 – 18:47 (+08:00)
**设备**：`emulator-6570`（AVD `OC_Deliver_R2`，自建，端口 6570/6571）· API 35 · WebView `124.0.6367.219` · 几何 `2506×1673 @ 240dpi`（与产品负责人截图同规格）
**被测包**：`research/deliver/opencode-shell-web.apk`（UX4 重建 debug 包，md5 `a7377e069f04d543d46c59386770ffc9`）

---

## 缺陷 1 —— 「+」选择面板深色突兀

### 根因（样式定义行号 + 原文）

面板代码内联在 `webapp/src/main/assets/www/oc-host.js` 的 `buildPanel()`（
`oc-plus-chooser.js` 已作废、无引用——该文件头注释有实测记录）。**改前**配色全部写死
成深色字面量（即产品负责人在浅色主题下看到的"黑底白字"）：

- `oc-host.js:755`（改前）面板容器：
  `"background:#18181b", "border:1px solid #2e2e35", "border-radius:10px",`
  `"box-shadow:0 10px 30px rgba(0,0,0,.55)", …, "color:#e8e8ea",`
- `oc-host.js:762`（改前）「打开」小标题：`"font-size:11px;color:#8b8b95;…"`
- `oc-host.js:777`（改前）菜单项按钮：`…, "background:transparent", "color:#e8e8ea",`

且**没有任何 hover 样式** —— 三种主题相关量（文字色 / hover 色 / 分隔线色）全部是
常量，与页面当前主题完全脱钩。

### 修法（复用上游主题 token，一致性优先）

改后（`oc-host.js:733-781`）新增 `installPlusChooserStyle()`：把面板配色收敛到一条
`<style id="oc-plus-chooser-style">` 里，**全部读上游写到 `:root` 的 CSS 变量**（
上游 `context.tsx` 的 `applyThemeCss` 负责写，变量值随主题与深浅色**实时**更新）：

| 面板部位 | 用的变量 | 依据（`_upstream`） |
|---|---|---|
| 面板底色 | `var(--surface-raised-base,#18181b)` | `packages/ui/src/theme/themes/oc-2.json` light `#F3F3F3` / dark `#232323` |
| 菜单项 hover 底 | `var(--surface-raised-base-hover,#2a2a30)` | 同上 light `#EDEDED` / dark `#282828` |
| 面板描边/分隔 | `var(--border-weaker-base,#2e2e35)` | 同上 light `#E8E8E8` / dark `#232323` |
| 菜单项文字 | `var(--text-strong,#e8e8ea)` | 同上 light `#171717` / dark `#EDEDED` |
| 「打开」小标题 | `var(--text-weak,#8b8b95)` | 同上 light `#8F8F8F` / dark `#707070` |
| 阴影 | 深 `.55` / 浅 `.18`，按 `:root[data-color-scheme=light]` 分流 | `data-color-scheme` 是上游 `applyThemeCss` 写的模式标记 |

- **依据**：token 名与值逐字来自 `_upstream/packages/ui/src/theme/themes/oc-2.json`
  （grep 实测，light/dark 两套值都在）；写变量进 `:root` 的代码是
  `_upstream/packages/ui/src/theme/context.tsx:144-155` 的 `applyThemeCss`。
- `var()` 的**兜底值 = 改前深色配色**：上游改版 token 缺失时面板退回深色仍可读，
  不会裸奔成透明/白字。
- 版式（定位/尺寸/圆角/字体）保留在 inline `cssText`（`oc-host.js:796-802`），
  颜色一律不进 inline —— 防回归由绊线钉住（见 §绊线）。
- **顺带检查**：文字色（强/弱两级 token）✓、hover 色（新增，此前没有）✓、
  描边/分隔线 ✓、阴影两模式分流 ✓ —— 浅色下无黑底白字、无看不清的灰字
  （截图 §设备证据）。

## 缺陷 2 —— 「跟随系统」不生效：逐项核查（5 条）

1. **Activity theme**：`AndroidManifest.xml:49` `android:theme="@style/Theme.OcWebHost"`，
   `themes.xml:8` `parent="Theme.AppCompat.DayNight.NoActionBar"` —— ✅ **是 DayNight 系**。
2. **`values-night/`**：改前**不存在**（glob 实测 0 文件），`colors.xml:3`（改前）
   `oc_host_bg` **恒为 `#FF080808`** —— DayNight 主题名存实亡：浅色模式下
   WebView 首帧背后也是深灰（闪深色），且主题资源没有任何 night 变体。❌ 已修。
3. **`android:configChanges`**：`AndroidManifest.xml:54` **包含 `uiMode`** ⇒ 系统切
   深/浅色时 **Activity 不重建**，配置直接投递给 `onConfigurationChanged` —— 而
   `WebHostActivity` 改前**没有这个 override**（grep 实测）：`uiMode` 翻转后
   `InsetsHost.publish` 不会因 scheme 变化重发（payload 里 `dark`/`scheme` 变了但
   没人触发复查），`--oc-scheme` / 系统栏明暗兜底**一直停留在旧值**。❌ 已修。
4. **WebView dark-mode 设置**：`configureSettings`（`WebHostActivity.kt:781-808`）
   **没有任何** `FORCE_DARK` / `setAlgorithmicDarkeningAllowed` 之类设置 ——
   方向错误地"锁死某个模式"的风险不存在；WebView 走默认行为（`prefers-color-scheme`
   跟随 app `uiMode`）。✅ 无需改。
5. **上游机制**（`_upstream` 实读，非猜测）：
   - 设置存在 localStorage key `opencode-color-scheme`，取值 `light|dark|system`，
     **默认 `system`**（`packages/ui/src/theme/context.tsx:14-19, 186`）；
   - `system` 模式的判定 = `window.matchMedia("(prefers-color-scheme: dark)")`
     （`context.tsx:128-131`）；切系统模式靠监听该 media query 的 **change 事件**
     （`context.tsx:265-270`）；首屏还有 `packages/app/public/oc-theme-preload.js:6`
     提前铺底色。**桌面浏览器好用正是因为它**。
   - **设备实测（改动前的旧包）**：系统实时切深/浅色（前台、后台、冷启动三种序列）
     `matchMedia` 均正确翻转且页面跟随（`data-color-scheme` 变、change 事件命中
     计数 +1、`window` 全局量存活证明非整页重载）—— 参考设备上**链路本身是通的**。

### 缺陷 2 结论与修法

- **确凿的宿主缺陷（已修）**：
  a. 缺 `values-night/` + `oc_host_bg` 恒深 ⇒ 新增
     `webapp/src/main/res/values-night/colors.xml`（夜 `#FF080808`），昼版
     `colors.xml:10` 改为 `#FFFAFAFA`（= 上游浅色页面底色 `#fafafa`，深浅都与
     页面首帧无缝）；launcher 图标底色保持恒深不动。
  b. `WebHostActivity.kt:1070-1077` 新增 `onConfigurationChanged`：
     `super`（AppCompat DayNight 标准处理）→ `pageReportedDark = null`（清页面
     旧上报快照，让系统栏明暗兜底重新读当前 `uiMode`）→ `insets.refresh()`
     （payload 里 `scheme` 已翻转，去重比较通过 ⇒ 页面 `applyInsets` 拿到新
     `--oc-scheme`，设备实测切换后该变量立刻变 `dark`/`light`）。
- **优先级**：上游机制（matchMedia change）为主（没动上游一个字节）；宿主只补
  DayNight 资源与 uiMode 投递后的状态重推。
- **PO 现象的说明**：参考设备（与其在跑实例同型号、同 API 35、同 WebView 124）
  上"跟随系统"在 前台/后台/冷启动 三种序列均实测生效；当 `opencode-color-scheme`
  被存成 `light`/`dark` 时页面按用户显式选择走（这是设计语义，设备复现截图
  `ux4_p2_stored_light.png`）。宿主侧真正坏掉的是上面 a/b 两条（窗口底色与
  uiMode 后的宿主状态），均已修复并用四组合截图证明。

## 设备证据（四组合，均 2506×1673 真机截图）

| | 系统=浅色 | 系统=深色 |
|---|---|---|
| **选择面板**（右侧栏「+」，真实点击 `input tap`） | `research/web/ux4_panel_light.png`（浅底深字） | `research/web/ux4_panel_dark.png`（深底浅字，实时切换后补拍） |
| **页面主体** | `research/web/ux4_page_light_live.png`（另 `ux4_v2_light_tabstrip.png`） | `research/web/ux4_page_dark.png` |

**注入 vs 真实点击的标注**：面板是宿主 `oc-host.js` 注入的 DOM；每次截图前的
「点右侧栏 `+`」是 `adb shell input tap` **真实点击**（坐标取自截图），面板出现
由注入层的点击拦截触发。系统深/浅切换是 `adb shell cmd uimode night yes/no`。

**「跟随系统」生效的量化证明**（debug eval 通道 + logcat 原文）：
- 系统实时切深色（app 开着、面板开着）：`{"mq":true,"cs":"dark","ocScheme":"dark","stored":"system"}`；
- 再实时切回浅色：`{"mq":false,"cs":"light","ocScheme":"light"}`；
- 切换前 arm 的 matchMedia change 计数器 `hits:1` 且 `window` 标记量存活
  ⇒ **change 事件真实触发、页面非重载跟随**；
- 冷启动（force-stop 后系统深色启动）：`{"mq":true,"cs":"dark","stored":null}`。
- **是否实时**：✅ **实时** —— 不切前后台、不重启 app，两个方向都即时生效；
  冷启动方向也正确（即"重启 app 后正确"天然满足）。

## 绊线与反向验证

新增 `webapp/src/test/java/com/xiufeigo/opencodeshell/web/Ux4ThemeTripwireTest.kt`
（6 用例），并在 `webapp/build.gradle.kts` 把 `values/colors.xml` +
`values-night/colors.xml` 显式声明为测试输入（防"资产改了但测试 UP-TO-DATE 假绿"）。

- **断言**：面板不得含 `background:#18181b` / `color:#e8e8ea` 硬编码；
  必须含 `var(--surface-raised-base,` / `--surface-raised-base-hover,` /
  `--border-weaker-base,` / `--text-strong,` / `--text-weak,`；
  昼版 `oc_host_bg` 必须 `#FFFAFAFA`、夜版必须 `#FF080808` 且文件存在；
  `WebHostActivity` 必须有 `onConfigurationChanged` + `insets.refresh()` +
  `pageReportedDark = null`。
- **反向验证（真红实测）**：把三处修法**逐字节撤回**（oc-host.js 面板段 /
  colors.xml 昼值 / WebHostActivity 的 override），跑该测试类 ⇒
  **5/6 用例真红**（`ux4_redvalidation.log`：tests=6 failures=5，红的正是
  面板底色/面板文字/上游 token/窗口底色/uiMode 处理五条；第六条是样本自检
  恒绿）。恢复修复版后 ⇒ BUILD SUCCESSFUL（`ux4_restore_green.log`）。

## 三条回归红线（本轮不许破坏）

本轮 diff 只碰：面板注入样式、颜色资源、Activity 的一个 config-change override
（`launch`/代理/口令/通知路径零改动）。

| 红线 | 守护 | 结果 |
|---|---|---|
| 未配置服务器灰态 + 「去设置服务器」 | `NotConfiguredShellTest`(16) + `FallbackPageTouchTargetTest`(2) | ✅ 0 失败（18:35 全量跑） |
| 口令为空绝不启动代理 | `EmptyPasswordLaunchGateTest`(16)（见 `research/web/FIX_F21_EMPTYPW.md`） | ✅ 0 失败（同上） |
| 终端杀 app 后续接同一会话 | `TerminalReattachTest`(11)（见 `research/web/UX2_TERMINAL_REATTACH.md`） | ✅ 0 失败（同上） |

## 三份新产物（`research/deliver/`，2026-10-05 18:39 重建）

| 产物 | 体积 | md5 | 签名 |
|---|---|---|---|
| `opencode-shell-web.apk`（debug） | 5,438,212 B | `a7377e069f04d543d46c59386770ffc9` | `CN=Android Debug`（apksigner verify --print-certs） |
| `opencode-shell-web-release-nor8.apk` | 3,993,156 B | `9fb248860e312b33e7bb3b31322be65d` | 同上 |
| `opencode-shell-web-release-r8.apk` | 1,448,564 B | `f3bee4f9dce86759f6a32d1e64ce3acd` | 同上 |

**包内含源自查**：三份 APK 内 `assets/www/oc-host.js` 的 md5 均为
`f2b4e33d28434d3b51ae3c14350e1189`，与
`webapp/src/main/assets/www/oc-host.js` 源文件**逐位一致**（ZipFile 解包实测，
三份 match=True）。

## 测试

- 命令：`.\gradlew.bat --console=plain :webapp:testDebugUnitTest :app:testDebugUnitTest`
  ⇒ **BUILD SUCCESSFUL，0 失败**（`:app` 另做了一次 cleanTest 全新跑：
  2m49s，6136 用例 0 失败）。
- 用例数：webapp **213**（新增 UX4 绊线 6 条 ⇒ 只增不减）；app **6136**（未动）。
- 未删/放宽任何既有断言；新增绊线含反向验证样本。

## 收尾

- **HANDOFF_WEB_SHELL.md 已同步**：§0.1 顶表三行 + 新增「UX4 复核」块
  （三份新 md5/体积/签名 + 包内含源自查 + UX3 及更早批次全部标作废）。
- **碰过的 serial**：`emulator-6570`（OC_Deliver_R2，自建自启，已 `emu kill` 关闭并复核：
  `adb devices` 空表、`qemu*` 进程 0、6570/6571 端口释放）。
  对产品负责人实例 `emulator-5730`/`emulator-5732`（DSH_T97_Tablet/Phone）
  **全程仅只读查询**（`dumpsys webviewupdate` / `getprop`），**未发任何会改它们
  状态的命令**（无 kill/swipe/install/键入）。
  ⚠️ 收尾观察：关闭自建 6570 的复核时刻，5730/5732 亦已不在 `adb devices` 中
  （端口处于 TIME_WAIT、无 qemu 进程残留）——它们在我那条**只指向 6570** 的
  `emu kill` 之前/之后的窗口内由产品负责人侧自行关闭；我发出的唯一 kill 目标是
  自建实例，未违反模拟器纪律（无 kill-server、无通配、未碰非自建实例状态）。
- **未闭环项**：① PO 侧"跟随系统不生效"的原始复现环境未能 100% 锁定（参考
  设备全序列实测是通的；最可能解释是本地存储的 scheme 非 system 或当时设备
  状态差异——修复后四组合 + 双向实时切换均有设备证据）；② R8 包的设备级
  🟡 证据（口令存储/错误态/常亮等）仍属旧字节体，本轮未在 R8 新包上重跑
  （非 R8/debug 的行为链路不受影响，按惯例引用旧证据需带旧 md5）。
