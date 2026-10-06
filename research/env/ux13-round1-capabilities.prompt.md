# 轮次：UX13-P1 —— 能力复验①（当前 UX12 包）：相机扫码配对 / 通知点击回会话 / 文件选·存

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：方案 A 已落地（页面来源 `http://127.0.0.1:4173`）。现在要复验三项历史在旧架构验过、但**在本批包上未验**的能力，目标是拿到当前包上的事实证据（红或绿都要）。**只验证，不修代码。**

**先建报告 `research/web/UX13_P1_CAPABILITIES.md`，每步写盘；每步单条命令 + 立即读输出；真实点击，注入只读页面状态并标注。**

## 环境（已核好）
- 包 `research/deliver/opencode-shell-web.apk`（debug，md5 BF688F8A033EDE65C3D4F8C5E8D1E930），包名 `com.xiufeigo.opencodeshell.web.debug`。
- 后端 `127.0.0.1:4095`（opencode/opencode，v2.0.24）**已在跑，绝对不许起停**。
- AVD `Medium_Phone_API_37.0`，端口 **6790**（先查占用）；`adb reverse tcp:4095 tcp:4095`（收尾 remove）。
- 注入通道 `am start --es oc_debug_eval "b64:..."`，结果在 logcat `OcWebHost/Eval`。
- 纪律：只关自建 emulator-6790；不 kill-server；不改 `webapp/**`/`app/**` 任何字节；不碰 gradle。
- 先验包内 oc-host.js md5 并记录（装包前 Get-FileHash）。

## 步骤

### 0. 起机装包
起 emulator（Start-Process 脱离会话）→ boot → reverse → install → `pm clear` → `am start` → 等 5 秒。记录 WebView 版本（`dumpsys webviewupdate`）与 UA。

### 1. 连服务器并进会话（后续步骤的前提）
真实点击 Add server 填 `http://127.0.0.1:4095` + `opencode`，等 ≥14 秒提交 ⇒ 绿点；**回 Home 真实点击 4095 行切上下文** ⇒ 列表渲染。进入任一既有会话（如 `v2 smoke test`）。截图存证。

### 2. 相机扫码配对（重点，怀疑当前包被拒）
真实点击：Home → 抽屉 → Settings → Server 段 → **Add server** → 对话框里找 **Scan QR code** 按钮（上游 `dialog.tsx` 的 web 平台入口）并真实点击。
- 观察：对话框是否切到相机视图（`PairingScanner`）。
- logcat 抓 `OcWebHost`（onPermissionRequest 相关日志）与 chromium camera 相关行（`cr_Media`/`Camera`）。
- 注入读页面：`document.querySelector('video')` 是否存在、readyState、以及 `navigator.permissions.query({name:'camera'})` 状态（b64）。
- 判定：
  - ✅ 通过 = 相机视图出现且 getUserMedia 拿到流（video readyState≥2，或日志无拒绝）；
  - ❌ 被拒 = logcat 见 onPermissionRequest 命中但 **未 grant**（可信 origin 判定失败）/ 页面报 NotAllowedError 等。
- 截图前后对比。把判定与原始输出写进报告。

### 3. 通知点击回会话
- 在会话里发一条消息（`reply with the single word OK`），**立刻按 KEYCODE_HOME 把 App 切后台**；
- 等助手回复（10–25 秒）：`adb shell dumpsys notification` 查找含 OpenCode/会话文本的通知（logcat `NotifierHost`/`OcNotify` 如有）；截图通知栏（`adb shell cmd statusbar collapse`? 用 `dumpsys notification --noredact` 文本即可 + 必要时下拉通知栏截图）；
- **真实点击该通知**（`dumpsys notification` 拿 Intent/或按通知行 bounds 点）；
- 判定：点击后 App 回到**对应会话页**（topActivity 是 WebHostActivity，且页面停在会话上下文而非 Home）。截图 + logcat。

### 4. 文件选 / 存（N-4 可达面探查）
- **选**：回会话 composer，真实点击「+」（附件）→ 预期触发 Android 文件选择器（`onShowFileChooser`）→ `dumpsys activity top` 证明跳到系统选择器 → 真实选一张图片/文件 → 回到 App 且附件出现在 composer。逐步截图。
- **存**：探查可达的保存入口（会话内文件视图/Download/导出按钮等，真实点击能点到的都试）；若 UI 上存在下载/保存按钮，点击后观察宿主 `launchSave` 是否触发（logcat `OcWebHost` download/save 相关 + 下载目录 `/sdcard/Download` 是否出现文件）。**若本轮找不到任何可达的保存入口，如实写"未找到可达路径"，不要硬凑。**

### 5. 收尾复核
emu kill 6790；端口释放；reverse --remove；后端 pid 未变 + /api/info 200；报告末尾列 serial（应只有 emulator-6790）。

## 汇报（最后一行）
相机 通过/被拒（关键日志一行）· 通知点击回会话 通过/失败 · 文件选 通过/失败 · 文件存 通过/未找到入口 · 报告路径 · 碰过的 serial。
