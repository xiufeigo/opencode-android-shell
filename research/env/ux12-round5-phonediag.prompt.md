# 轮次：UX12-PhoneDiag —— 手机档「列表恒 Loading」阻塞根因诊断（CDP 取证）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：`research/web/UX12_PHONE_REGRESSION.md` §5.0 记录了一个阻塞——手机档（Medium_Phone_API_37.0，1080x2400）连上 `http://127.0.0.1:4095` 后（绿点已出现、Server 段能读回 `Terminal shell` 行），**项目/会话列表恒 `Loading` 无法进入任何会话**，`OcPerfJS` 里 4095 的响应用 `enc=0 dec=0 xfer=0` 记录。平板档（Medium_Tablet）同 APK 同后端**全部 12 项 PASS** ⇒ 怀疑手机档环境（WebView 版本？）或某竞态，需定位。**你的任务是拿到决定性网络证据并给出结论，不修代码。**

**先建报告 `research/web/UX12_PHONE_DIAGNOSIS.md`，每步写盘。每步单条命令 + 立即读输出。交互真实点击；注入只用于读页面状态；CDP 是标准调试通道（标注每步）。**

## 环境（已核好）

- 包：`research/deliver/opencode-shell-web.apk`（md5 BF688F8A033EDE65C3D4F8C5E8D1E930）；包名 `com.xiufeigo.opencodeshell.web.debug`；Activity `.WebHostActivity`。
- 后端 `127.0.0.1:4095`（Basic opencode/opencode）**已在跑，不许起停**；只读 `Invoke-WebRequest`。
- adb/emulator 路径同前。AVD `Medium_Phone_API_37.0`，端口 **6790**（先查占用）。几何原生（记录 `wm size` 实测）。
- `adb reverse tcp:4095 tcp:4095`（收尾 remove）。
- 纪律：只关自建 emulator-6790；不 kill-server；不改 `webapp/**`/`app/**`；不碰 gradle。

## 步骤

### 0. 起机装包
起 emulator（Start-Process 脱离会话，日志落盘）→ boot → reverse → install → pm clear → am start。等首屏 5 秒。

### 1. 记录 WebView 版本（关键对照项）
`adb shell dumpsys webviewupdate`（Current WebView package）+ 注入读 `navigator.userAgent`（b64 oc_debug_eval）。平板对照值本轮记录在案：平板 WebView 正常。把两个值写进报告。

### 2. 复现：加服务器并连上
真实点击 Home→抽屉→Settings→Server→Add server，填 `http://127.0.0.1:4095` + `opencode`，**等 ≥14 秒**提交；确认绿点。

### 3. CDP 抓包（决定性证据）
1. `adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>`（pid 用 `dumpsys activity top` 或 ps 找 com.xiufeigo.opencodeshell.web.debug 的进程 pid；先 `curl http://127.0.0.1:9333/json` 确认页面 target 的 url 是 4173）。
2. 用 Node 脚本（repo 根下临时 `_scratch_diag.mjs`，跑完删掉）连 CDP WebSocket：
   - `Network.enable`；记录 `requestWillBeSent` / `responseReceived` / `loadingFinished` / `loadingFailed`；
   - 同时 `Runtime.enable` 收集 console 报错（CORS 报错会打在 console）。
   - 抓包运行 90 秒，期间在页面上真实点击：Home（触发列表请求）、等 20 秒、抽屉里点项目行展开。
3. 从抓包数据里抽出全部 `url 含 127.0.0.1:4095` 的请求，逐条列出：`method, url, status, mimeType, encodedDataLength(response), 失败原因(若有)`。**特别看 `/api/project`、`/api/session`、`/api/event`：**
   - 若 status=200 且 body 正常 ⇒ 「xfer=0 是 OcPerfJS 记录假象」，阻塞另有原因（看 console 有没有 JS 异常）；
   - 若 CORS error / status=(blocked) ⇒ 抓 console 里的 CORS 报错原文；
   - 若 status=401/403 ⇒ 认证路径问题；
   - 若根本没有发出 `/api/project` 请求 ⇒ 客户端状态机问题（看 console 异常）。
4. 也把 console 里所有 error/warning 抄进报告。

### 4. 结论
给出明确判定（三选一或补充）：
A. **环境问题**（WebView 版本缺陷/AVD 怪癖）——给出 UA 与证据链，并说明平板为何不受影响；
B. **真实产品缺陷**（跨源化在特定 WebView 上的回归）——给出最小复现与网络级证据；
C. **非问题**（观察/等待不足或假象）——给出解释。
不修代码、不放宽任何东西。

### 5. 收尾
emu kill 6790；reverse --remove；后端未起停证据（pid + /api/info 200）；报告末尾列 serial。删掉临时脚本。

## 汇报（最后一行）

结论（A/B/C + 一句话）+ WebView 手机 vs 平板版本 + /api/project 等关键请求的 status/bytes 原文 + console 关键报错 + 报告路径 + 碰过的 serial。
