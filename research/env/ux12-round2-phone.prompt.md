# 轮次：UX12-Phone —— 手机档全量回归（方案 A 自带官方前端 + 本机 http 来源）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
**先建报告、每步写盘；每步单条命令 + 立即读输出；交互全部真实点击/真实按键（`input tap`/`input text`/`input keyevent`），注入只用于读页面状态（`oc_debug_eval`），并逐条标注哪步是注入。**

## 环境与硬事实（都已核好，直接用）

- **被测包**：`research/deliver/opencode-shell-web.apk`（debug 构建，新「方案 A」产物）。安装前先 `Get-FileHash -Algorithm MD5` 记录。
- 包名：`com.xiufeigo.opencodeshell.web.debug`；启动 Activity：`com.xiufeigo.opencodeshell.web.WebHostActivity`。
- **后端**：`127.0.0.1:4095`，Basic 认证 `opencode`/`opencode`，**已在跑，绝对不许起停**；你对它只能用 `Invoke-WebRequest` 只读探测（需要认证时用 Basic 头）。设备侧访问靠 `adb reverse tcp:4095 tcp:4095`（任务允许，收尾 `reverse --remove`）。
- adb：`$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`；emulator：`$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe`。
- AVD：`Medium_Phone_API_37.0`；端口：**6790**（先 `Get-NetTCPConnection -LocalPort 6790` 查占用，被占就换 6792/6794…偶数≥6790）。
- 几何：启动后 `adb -s emulator-6790 shell wm size 1200x2670` + `wm density 420`。
- **注入读数通道**（宿主 DEBUG-only）：`adb -s emulator-6790 shell am start --es oc_debug_eval "b64:<base64(utf8 js)>" com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity`，结果在 logcat `OcWebHost/Eval`（`adb logcat -s OcWebHost:V`）。简单表达式可不带 b64 前缀。**复杂 JS 必须 b64**（adb 会拆含空格/`+`/括号的参数）。
- logcat 关键字：`OcWebHost`（home= / Eval）、`OcLocalUi`（本机来源端点）、`OcPerfJS`（页面请求）、`D1 applyKeepScreenOn` / `clearKeepScreenOn`（常亮 flag）、`OcWebHost: dispatchLifecycle`（pagehide/pageshow，如存在）。
- 截图：`adb -s emulator-6790 exec-out screencap -p > research\web\ux12p_<名>.png`，每张 ≤100 KB 检查非全黑（文件太小要警惕）。
- 软键盘输入用 `input text`，点按用 `uiautomator dump` 找 bounds 后 `input tap`。
- **纪律**：谁起谁关；只关自己起的 `emulator-6790`（`adb -s emulator-6790 emu kill`）；**不许** `adb kill-server`/`-a`/通配符；`adb devices` 里别人的实例一概不碰；不许动 gradle（构建已另有产物）；不许改 `webapp/**`、`app/**` 任何字节；不许起停后端。

## 第 0 步：建报告 + 起机 + 装包

报告 `research/web/UX12_PHONE_REGRESSION.md`，每步追加。
1. 记录 APK md5/体积。
2. 查 6790 占用 → 起 emulator（后台 job 或 `Start-Process`，日志落盘）→ 等 `adb devices` 出现 → `wm size/density` → `adb reverse tcp:4095 tcp:4095`。
3. `install -r` 安装 → `pm clear` 清数据（全新首启）→ `am start` 启动。

## 第 1 步：页面实际来源（验收 #3 前一半）

启动后等 5 秒，注入读数（b64）：
`JSON.stringify({href:location.href,protocol:location.protocol,origin:location.origin,docURL:document.URL,title:document.title})`
- 预期：`http://127.0.0.1:4173/`、`http:`。逐项抄 logcat 原文。
- logcat 抓 `OcLocalUi`（listening 行）与 `OcWebHost`（home= 行）原文。
- 截图官方 Home（含 All projects 等）= `ux12p_home.png`。
- **本机来源服务证据**：logcat `OcPerfJS` 若干条 URL 均为 `http://127.0.0.1:4173/...`；同时确认页面侧对 `http://127.0.0.1:4095` 的 API 请求开始出现在 `OcPerfJS`（官方 client 自动探测默认服务器，会被端点 404 拒答——这正是红线 3 生效的证据，照实记录）。

## 第 2 步：上游判据在真实页面里跑（红字机制闭合）

注入（b64）把上游 `_upstream/packages/app/src/servers/connect/browser.ts:3-12` 的 `isMixedContent` 原样跑一遍：
- `isMixedContent(location.href, "http://8.138.19.15:4094")` → 预期 **false**
- `isMixedContent("https://example.com/", "http://8.138.19.15:4094")` → 预期 **true**（旧形态复现）
- `isMixedContent(location.href, "https://8.138.19.15:4094")` → 预期 **false**
verbatim 抄输出。含义写进报告：页面 http ⇒ 对 http 目标永不触发混合内容红字。

## 第 3 步：官方「Add server」同页加 http（验收 #1 主证据）

真实点击路径（每步截图）：Home → 抽屉（≡）→ Settings → 顶部段选择器 → **Server** → **Add server**。
1. 地址填 `http://127.0.0.1:4095`、口令 `opencode`。**⚠️ UX11 教训：官方健康检查异步、约 10 秒一轮** —— 填好后等 ≥12 秒再提交（期间可注入读 `document.body.innerText.includes('Could not connect')` 观察状态翻转）。
2. 提交后预期：对话框关闭、段选择器出现 `127.0.0.1:4095`；进 Server 段显示**绿点（已连接）**。截图 `ux12p_server_green.png`。
3. 连接成功证据（全部抄原文）：logcat `OcPerfJS` 出现 `http://127.0.0.1:4095/api/provider`、`/api/model`、`/api/session/active` 等；Server 段里 `Terminal shell: powershell` 之类从真实后端读回的内容截图。
4. **全程红字检查**：从打开对话框到添加成功，截对话框图 `ux12p_add_http.png`；断言页面文本里**不存在**「无法从 HTTPS 页面连接此 HTTP 服务器 / Could not connect to this HTTP server from an HTTPS page」（可注入 `document.body.innerText` 全量抓取存证）。

## 第 4 步：同页再加 https（验收 #1 另一半；**等价构造，如实标注**）

本环境没有证书受信的 https 后端（与 UX10 §4.1 同款限制），按「等价构造」取证并**显著标注**：
1. 再次 Add server，地址填 `https://127.0.0.1:4095`（后端实为 http，TLS 必然失败）。
2. 断言：**没有红字**（同上 innerText 抓取）；提交后（等 12 秒）出现的是**通用错误** `Could not connect to server`。截图 `ux12p_add_https.png`。
3. 证据链 = 第 2 步判据（`isMixedContent(http页面, https目标)=false`，机制上 https 目标在 http 页面永不被混合内容规则拦截）+ 本条实测（对话框正常走完健康检查路径、只报真实网络原因）。
4. 报告里写明：「https 成功」未端到端验（无受信 https 后端），以等价构造替代，判据与设备实测双重支撑。

## 第 5 步：全量回归（验收 #4，逐条证据）

先进入第 3 步连上的服务器里的一个项目（All projects 里选 `oc-fixture-4095` 项目），然后逐项：

1. **终端可用**：点 **终端** 标签 → 等提示符 → `input text "echo ux12p_$([datetime]::Now.Ticks)"` + Enter → 终端出现命令与回显。截图 + logcat（PTY/SSE）证据。
2. **终端标签条「+」直接新建终端**：点终端条右侧 `+` → 预期直接出现 `终端 2`/`Terminal 2`，**不弹任何面板**。截图前后。并注入确认全局没有我们的面板 DOM（`document.querySelector('[data-oc-panel]')==null` 或按 oc-host.js 实际面板标记）。
3. **审查条「+」（手机档应无面板）**：手机档上游无审查条（UX7 结论）；注入扫描确认 `aside#review-panel` 不存在 ⇒ 手机档全页面零拦截符合预期。记录。
4. **面板「打开文件/打开终端」**：手机档不适用（无面板），写 N/A + UX7 已验依据。
5. **发消息有回复**：回会话 → 点 composer → `input text "reply with the single word OK"` → 发送 → 等助手消息（内置 `fledge-alpha-free` 模型，通常 5–20 秒）→ 截图回复。若超时未回复，把 logcat 与 `OcPerfJS` 流量写进报告并判失败。
6. **切会话 / 返回**：建第二个会话（New session）→ 在两个会话间切换 → 按返回键回 Home → 再进入，状态正确。截图。
7. **键盘弹起 composer 两屏不裁切**：
   - 屏 A（New session）：点输入框弹软键盘 → 注入量 `composer` 底边与 `visualViewport.height`：底边 ≤ visualViewport 高度（裁切判据）→ 截图 → 收键盘恢复。
   - 屏 B（会话界面）：同上量一遍。
   - 两次都记录具体 px 值。
8. **主题浅/深/跟随**：Settings → Appearance/主题 → 分别切 light / dark / system → 每次注入读 `document.documentElement.dataset.colorScheme` + 截图（`ux12p_theme_light/dark.png`）；宿主窗口底色跟随（截图可见）。
9. **回合常亮**：发一条消息（驱动回合）→ logcat 抓 `D1 applyKeepScreenOn`/`flagSet=true`；回合结束（收到回复后）抓释放日志 + 时间差；断言 flag 真被置起且按时释放。
10. **后台断 SSE / 回前台重建**：`input keyevent KEYCODE_HOME` 把 App 切后台 → logcat 观察 SSE 断开（pagehide / event 流中断，参考 UX8 指标 20–106ms 级）→ `am start` 或 recent 键回前台 → 观察重连（92–245ms 级）+ 页面仍可用。抄 logcat 原文与时间戳。
11. **官方设置里能加服务器并连上**：即第 3 步（引用即可，不重复操作）。
12. **（收尾顺验）返回**：多次返回键最终回 Home 不卡死、不白屏。

## 第 6 步：收尾复核（原始输出抄进报告）

1. `adb devices`（确认只关了自建；列当时在线的非自建实例并声明未碰）。
2. `adb -s emulator-6790 emu kill` → `Get-NetTCPConnection -LocalPort 6790` 确认释放。
3. `adb reverse --remove tcp:4095`。
4. 后端未起停证据：`Get-NetTCPConnection -LocalPort 4095 -State Listen` 仍在 + 只读探测 `/api/info` 200。
5. 报告末尾列：碰过的 serial、是否碰过非自建实例（应为否）。

## 🔴 红线

- 不改 `webapp/**`、`app/**` 任何字节；不碰 gradle 锁；不起停后端；不碰非自建实例。
- 失败就停下写报告，不硬凑。

## 汇报（最后一行）

状态 + 页面实际协议/origin（原文）+ http 添加成功证据（绿点+API 流量）+ https 等价构造结论 + 红字是否消失 + 12 条回归逐条 PASS/FAIL + 收尾复核（端口释放/后端未起停/只关自建）+ 报告路径 + 碰过的 serial。
