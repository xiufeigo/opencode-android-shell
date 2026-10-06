# 轮次：UX12-Tablet —— 平板档全量回归（方案 A 自带官方前端 + 本机 http 来源）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
**先建报告、每步写盘；每步单条命令 + 立即读输出；交互全部真实点击/真实按键，注入只用于读页面状态（`oc_debug_eval`），逐条标注。**

## 环境与硬事实（都已核好，直接用）

- **被测包**：`research/deliver/opencode-shell-web.apk`（debug 构建，新「方案 A」产物）。安装前 `Get-FileHash -Algorithm MD5` 记录（应等于 UX12-Build 报告登记的 debug 包 md5）。
- 包名：`com.xiufeigo.opencodeshell.web.debug`；启动 Activity：`com.xiufeigo.opencodeshell.web.WebHostActivity`。
- **后端**：`127.0.0.1:4095`，Basic `opencode`/`opencode`，**已在跑，绝对不许起停**；只允许 `Invoke-WebRequest` 只读探测。设备侧访问靠 `adb reverse tcp:4095 tcp:4095`（收尾 `reverse --remove`）。
- adb：`$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`；emulator：`$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe`。
- AVD：`Medium_Tablet`；端口：**6792**（先查占用，被占换 6794/6796…偶数≥6790）。几何用原生 `1280x800`（先 `wm size` 记录实测值；密度原生不动，800dp 宽 = 平板档）。
- **注入读数通道**：`adb -s emulator-6792 shell am start --es oc_debug_eval "b64:<base64(utf8 js)>" ...WebHostActivity`，结果在 logcat `OcWebHost/Eval`。复杂 JS 必须 b64。
- logcat 关键字：`OcWebHost`、`OcLocalUi`、`OcPerfJS`、`D1 applyKeepScreenOn`/`clearKeepScreenOn`。
- 截图：`adb -s emulator-6792 exec-out screencap -p > research\web\ux12t_<名>.png`。
- **纪律**：谁起谁关；只关自己起的 `emulator-6792`；**不许** `adb kill-server`/`-a`/通配符；不碰其他实例；不碰 gradle；不改 `webapp/**`、`app/**` 任何字节；不起停后端。
- ⚠️ 手机档回归轮（UX12-Phone，端口 6790）可能与你**并行**运行：它只读后端与共享 DB，与你无冲突；你只用 `-s emulator-6792`。

## 第 0 步：建报告 + 起机 + 装包

报告 `research/web/UX12_TABLET_REGRESSION.md`。
1. 记录 APK md5/体积。
2. 查 6792 占用 → 起 emulator → 等 online → `wm size` 记录 → `adb reverse tcp:4095 tcp:4095`。
3. `install -r` → `pm clear` → `am start`。

## 第 1 步：页面实际来源

同手机档：注入 `JSON.stringify({href,protocol,origin,docURL,title})`；预期 `http://127.0.0.1:4173/`。logcat 抓 `OcLocalUi`/`OcWebHost` 原文；截图官方 Home `ux12t_home.png`；`OcPerfJS` 流量来源证据。

## 第 2 步：上游判据实测

同手机档：三条 isMixedContent 用例 verbatim 输出。

## 第 3 步：官方「Add server」同页加 http

真实点击：Home → 抽屉 → Settings → Server → Add server → 填 `http://127.0.0.1:4095` + 口令 `opencode` → **等 ≥12 秒**（健康检查 10 秒一轮）→ 提交。
- 预期：对话框关闭、Server 段绿点、`Terminal shell: powershell` 读回。截图 `ux12t_server_green.png`。
- 连接证据：`OcPerfJS` 里 `http://127.0.0.1:4095/api/...` 流量原文。
- 全程无红字：innerText 抓取存证 + `ux12t_add_http.png`。

## 第 4 步：同页再加 https（等价构造，如实标注）

同手机档：填 `https://127.0.0.1:4095`，预期无红字、只有通用错误；截图 `ux12t_add_https.png`；报告显著标注「等价构造」+ 判据依据。

## 第 5 步：全量回归（逐条证据；平板特有项重点验）

进入已连服务器的 `oc-fixture-4095` 项目：
1. **终端可用**：终端标签 → 提示符 → `input text` 敲命令有回显。截图+日志。
2. **终端标签条「+」直接新建终端**：点 `+` → 直接 `Terminal 2`，不弹面板。截图前后。
3. **审查条「+」仍弹我们面板（平板回归重点）**：平板档会话页右侧/边栏有审查（review）面板，其标签条 `+` 被我们的注入拦截 ⇒ 弹出「打开 / 打开终端 / 打开文件」面板。真实点击该 `+` → 预期面板出现。截图 `ux12t_panel.png`。
4. **面板「打开终端」有效**：点面板「打开终端」→ 终端面板出现且 Terminal 1 自动创建。截图+日志。
5. **面板「打开文件」有效**：再点审查条 `+` → 面板「打开文件」→ 预期上游 Open file 标签页/文件浏览器出现（**不得**跳 Android SAF 文件选择器；topActivity 仍是自家 WebHostActivity，`dumpsys activity top` 存证）。截图。
6. **发消息有回复**：会话里发 `reply with the single word OK` → 等助手回复（`fledge-alpha-free`）。截图。
7. **切会话 / 返回**：建第二个会话互切 + 返回 Home 再进入。
8. **键盘弹起 composer 不裁切**：平板档软键盘（New session 屏 + 会话屏各量一次）：composer 底边 ≤ visualViewport.height。截图+px。
9. **主题浅/深/跟随**：Settings 切 light/dark/system → `dataset.colorScheme` 读数 + 两张截图 + 宿主底色跟随。
10. **回合常亮**：发消息驱动 → `flagSet=true` 日志；回复到达后释放日志 + 时差。
11. **后台断 SSE / 回前台重建**：KEYCODE_HOME 切后台 → SSE 断开日志 → 回前台 → 重连日志 + 页面可用。抄时间戳。
12. **官方设置里能加服务器并连上**：引用第 3 步。

## 第 6 步：收尾复核（抄进报告）

1. `adb devices`（列非自建实例并声明未碰）。
2. `emu kill` → `Get-NetTCPConnection -LocalPort 6792` 确认释放。
3. `adb reverse --remove tcp:4095`。
4. 后端未起停证据（4095 Listen + /api/info 200）。
5. 报告末尾列：碰过的 serial、是否碰过非自建实例（否）。

## 🔴 红线

同手机档：不改 `webapp/**`/`app/**`、不碰 gradle、不起停后端、只关自建实例。失败停下写报告。

## 汇报（最后一行）

状态 + 页面协议/origin 原文 + http 添加成功证据 + https 等价构造结论 + 红字是否消失 + 12 条回归逐条 PASS/FAIL（重点：审查条 + 面板两项）+ 收尾复核 + 报告路径 + 碰过的 serial。
