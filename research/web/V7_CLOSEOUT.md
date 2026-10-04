# V7 · 设备侧收尾（相机扫码固化 / N-5 错误态 / N-7 剪贴板）

- **轮次**：V7（设备侧收尾工程师，用 mcode 执行）
- **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
- **设备**：`emulator-6110`（本轮自建，端口 **6110**，偶数且 ≥6110；启动前 6110/6112/6114 无占用、全机 emulator/qemu 进程数 = 0）
- **镜像 / 几何**：`android-35;google_apis_playstore_tablet` + `pixel_tablet` 几何 `1080x2400@420`
- **被测物**：`research/deliver/opencode-shell-web-release-nor8.apk`（3,968,477 B）
  - 本机 md5 `bc7f30180775485ce887dc74e4489c72`
  - **设备端 `base.apk` md5 `bc7f30180775485ce887dc74e4489c72`**（本轮重新核对，与本机逐位一致）
  - applicationId = **`com.xiufeigo.opencodeshell.web.debug`**
- **后端**：`127.0.0.1:4095` 由 Lead 起好，本轮**全程未起停**（开轮与收尾均 `LISTEN pid=3748`，同一 pid），只做 `adb reverse`

## 0. 一页结论

| # | 项 | 判定 | 一句话 |
|---|---|---|---|
| 1 | **相机扫码 `getUserMedia` live 流** | **❌ 未测到（BLOCKED，非"应该可以"）** | 找到并固化了可复现路径的**每一段**，但**最后一段卡死**：本 release 包**没有** F3 用的 `oc_debug_eval` 注入通道（`BuildConfig.DEBUG=false`）、也**没有** CDP，唯一能调 `getUserMedia` 的页面（connect screen）是**空 registry 才渲染的 layout fallback**，而本部署 registry 恒非空 ⇒ 真机 UI 上**到不了**扫码入口 |
| 2 | **N-5 后端不可达** | **❌ FAIL** | 不是纯白也不是卡住，但给的是**裸 JSON 技术错误** `{"error":"unexpected end of stream on http://127.0.0.1:4095/…"}`，无任何用户文案、无重试按钮、且**泄露内部 URL** |
| 3 | **N-5 口令错** | **❌ FAIL** | 代理层持续 `401 auth rejected`，但**页面永远停在 "Loading" 骨架屏**，用户**看不到任何认证失败提示**、也没有"重试/重新输口令"入口 |
| 4 | **N-5 WebView 过旧** | **引用，不重跑** | G1/V3 已验：WebView 83 ⇒ 诊断页带真实版本号。证据见 `research/web/WEBVIEW_PREFLIGHT.md`、`PREFLIGHT_IN_PACKAGE.md` |
| 5 | **N-7 剪贴板** | **✅ PASS** | 真实走通**复制→粘贴**闭环：`CTRL+A`→`KEYCODE_COPY`→清空→`KEYCODE_PASTE`，哨兵串 `OC_CLIP_98765` 原样回填 |

---

## 1. 相机扫码：固化成可复现路径

### 1.1 先找出 F3 当时是怎么到达那个页面的（要求 1）—— 结论：**F3 根本没到达那个页面**

F3 自己的报告就写明了（`research/web/FIX_F3.md`）：

- `FIX_F3.md:195` —— 取流探针"走 **`oc_debug_eval` 调试注入通道**，仅 DEBUG 构建"；
- `FIX_F3.md:222` —— **"未验的一步：没有真的走到 `PairingScanner` 组件去扫一个配对二维码……本轮验的是取流这一层"**；
- `FIX_F3.md:379` —— 未闭环项 3 原文："扫码解码层（真扫一个配对 QR）……走到 `PairingScanner` 需上游 UI 进 `servers/connect` 并出示特定配对二维码，**未构造该场景**"。

探针脚本在 `research/env/f3/camera_probe.js:4-34`，它**自建 `<video>` 挂到 `document.body`**，不是在扫码组件里。

> 所以任务描述里"F3 拿到过 live 后置流"是对的，但**"F3 走到了配对扫码页"不成立**——这正是 V6 和我复现不出来的根因。

### 1.2 把它写成脚本（要求 2）

已写：`research/env/camera_route.ps1`（12,490 B）。它把整条路径拆成 **7 个带断言的 STEP**，可重复执行、无需人工点：

| STEP | 内容 | 断言方式 |
|---|---|---|
| 0 | 端口偶数 ≥6110 + 占用预检 + 基线进程数 | 端口奇偶/占用直接 throw |
| 1 | 起机并等 `sys.boot_completed=1` | 300 s 超时 |
| 2 | 几何覆盖 `1080x2400@420` | 回读必须出现 `Override size/density` |
| 3 | 装包 + **设备端 md5 核对** + CAMERA 授权 | md5 不符即 throw |
| 4 | `adb reverse` + 播种口令（原生对话框：口令框 `(540,1128)`、保存并连接 `(854,1399)`） | 断言 logcat 出现 `credentials stored` |
| 5 | **走到配对扫码入口** | 探测 Tabs/右上/左上三处，UI 里必须出现 `Scan QR code` |
| 6 | **`oc_debug_eval` 通道可用性**（F3 的取流手段） | 断言 `OcWebHost/Eval` 出现 `eval=`，并交叉验证 `run-as` |
| 7 | `getUserMedia` live 流 | 取到流才能 PASS |

收尾按 HOUSEKEEPING：`wm size/density reset` → 回读无 `Override` 行 → 按 **AVD 名 + 端口** 确认归属 → `emu kill` → 复核残留/端口/`adb devices`。**不执行 `adb kill-server`、不用 `-a`/通配符。**

### 1.3 卡在哪一步 + 与 F3 的差异（要求 4）

**卡在 STEP 5 → STEP 7。** 本轮真实执行记录（见 §4 原始输出）：

1. **STEP 5 失败：UI 上到不了扫码入口。** 反复探测 `Tabs`(532,60)、右上(1020,60)、左上(72,60)，`uiautomator` 全树里**从未出现** `Scan QR code`。

   代码层根因（本轮代码考古，证据齐全）：
   - 扫码入口是 **`ConnectServerScreen`**，而它**不是路由、是 layout fallback**：
     `_upstream/packages/app/src/shell/routes/routes.tsx:79`
     `<Show when={servers.list.length > 0} fallback={<ConnectServerScreen />}>`
     声明路由只有 `/`、`/settings`、`/server/:key/session/:id`、`/new-session` ⇒ **`/servers/connect` 这种路径根本不存在**。
   - 按钮本体在 `dialog.tsx:162`（`<Show when={props.mode === "add" && platform.platform === "web"}>`）与 `dialog.tsx:176`（`server.connect.scan` = "Scan QR code"），由 Settings 的 **"Add server"** 触发（`shell.tsx:214-217,224,271`）。**要先进 Settings**，而 release 包**没有跳 URL 的钩子**（见下）。
   - **实测 registry 恒非空**：`pm clear`（= 清空 WebView localStorage）冷启动后**仍然直接落到 Home 会话列表**，从未渲染 connect screen ⇒ 说明本部署下 registry 每轮都被填充，fallback 分支是死支。

2. **STEP 6 失败：F3 的注入通道在本包上不存在。**
   - `WebHostActivity.kt:725` `if (!BuildConfig.DEBUG) return` ⇒ `oc_debug_eval` 只在 DEBUG 生效。
   - 交叉验证（本轮两条独立证据）：
     - 冷启动带 `--es oc_debug_eval '…'` 后，全量 logcat（2,104 行）里 **`OcWebHost/Eval` 零命中**；
     - `adb shell run-as com.xiufeigo.opencodeshell.web.debug` → **`package not debuggable`**。
   - ⚠️ **陷阱提示**：这个包虽然 applicationId 带 `.debug` 后缀，**但它不是 DEBUG 构建**。dex 里能搜到 `oc_debug_eval` 字符串常量（`classes.dex`），容易误判成"通道可用"——实际被 `BuildConfig.DEBUG` 挡掉了。

3. **也没有 CDP 这条退路。** 全仓 `WebHost.setWebContentsDebuggingEnabled` **零调用**（`webapp/src/main/java/…` grep 无命中）⇒ 无 `webview_devtools_remote_*` socket，无法注入 JS、无法 `Page.navigate` 到任何路由。V6 §2.1 的这个判断本轮复核**成立**。

4. **也没有 deep link。** manifest 只注册 `MAIN/LAUNCHER`（`webapp/src/main/AndroidManifest.xml:58-61`）；`STATE_URL="oc_web_url"`（`WebHostActivity.kt:69`）**只写不读**（写于 `:792`，`WebHostActivity.kt:391` 只从 `savedInstanceState` 取，**没有任何 intent extra 能指定 URL**）；`loadUrl` 的 origin 恒为 `LoopbackProxy.publicOrigin()`（`WebHostActivity.kt:390-392,415`）。

**两轮输入/命令/页面状态对比**

| 维度 | F3（`emulator-6050`） | V7（本轮 `emulator-6110`） |
|---|---|---|
| 包 | DEBUG 构建（`oc_debug_eval` 生效） | 同名 md5 的 **release** 包，`BuildConfig.DEBUG=false` |
| 到达扫码页 | **否**（F3 自述未验该步） | **否**（本轮实测三处探测均未见） |
| 取流手段 | `oc_debug_eval` 注入 `camera_probe.js` | **该手段在本包失效**；无 CDP、无 deep link |
| 页面状态 | 真实加载上游 SPA 的宿主页，注入后 `state=live` | 同一 SPA（Home 会话列表），但**无任何页面会调 `getUserMedia`** |
| 结论 | 取流**层**成立 | 取流**层无法复现**（不是"应该可以"） |

> **因此要求 3（连跑 2 次都取到流）无法满足——不是没跑，是这条路径在 release 包上不存在。** 本轮**没有**拿 F3 的旧数顶替结论。

### 1.3b 补验 Settings 入口的一次尝试（**未完成，如实记录**）

代码层看，`/settings` 是**真实声明的路由**，其 "Add server" 会打开带 "Scan QR code" 的 `DialogServer`（`dialog.tsx:162,176`）——**这是本包上理论唯一可达扫码入口的路**。因此本轮又起了第二次 `emulator-6110` 去试它：

- 起机 ✅、装包 ✅、播种口令并连接成功 ✅（`research/env/v7/v7c_connected.png`，241,782 B）；
- 随后尝试**左边缘抽屉手势**去找 Settings 入口；
- ⚠️ 但中途出现「**进程仍在**（`pid=41308 avd=OC_Deliver_R2 port=6110 MINE=True`）**而 `adb devices` 已空**」的不一致状态 ⇒ 符合 F4/E7 已记录的**两个 adb 客户端抢 adb** 形态（本轮当时确有 1 个后台任务在并发发 adb 命令，本轮**自身违反了"同一时刻只开一个 adb 客户端"的规避手段**，如实记录）；停掉该后台任务后约 1 分钟内**进程也自行消失**。

**因此：**
- `Tabs`(532,60) / 右上(1020,60) / 左上(72,60) 三处已实测**均无** Settings 入口 ⇒ **无可见的 navigate 到 `/settings` 的导航**；
- **左边缘抽屉 → Settings → Add server → "Scan QR code" 这条唯一理论路径，本轮「未测到」而不是「已排除」** —— 探测在设备掉线时中断，**不能声称它不存在**。这是本项**最大的残留不确定性**。
- 设备侧后果：见 §4 收尾复核（该实例自行消失，本轮**未对其发出任何 kill**）。

### 1.4 要真正跑通，需要其一（本轮无权限/无范围，未做）

- 出一个 **DEBUG 构建**用于验收（`oc_debug_eval` 即刻可用）；**或**
- 给 release 加一个**受控**的非 DEBUG 注入/深链钩子（读 `oc_web_url` extra + `setWebContentsDebuggingEnabled`）；**或**
- 让 registry 可空（不自动播种服务器），使 connect screen 的 fallback 真正渲染，再用真机 UI 点 "Scan QR code"。

### 1.5 F3 结论是否仍可信

**取流那一层（F3 的 `gUM=OK label=camera2 0, facing back state=live`）本身没有被推翻**，本轮也**没有**反证。成立与否取决于它是真在 DEBUG 构建上跑的。本轮**只**能说：**在交付用的 release 包上，这条证据不可复现**。

---

## 2. N-5 错误态兜底（三类）

### 2.1 后端不可达 —— ❌ **FAIL**

**方法**：`adb reverse tcp:4095 tcp:4099`（派单允许的"指向错端口"；`--remove` 被本机安全策略拦下，**未绕过**），口令已播种（`credentials stored`），冷启动宿主。

**原始输出**（logcat）：
```
I OcWebHost/Proxy: listening on http://127.0.0.1:34599 -> http://127.0.0.1:4095
I OcWebHost: home=http://127.0.0.1:34599/ backend=http://127.0.0.1:4095 passwordPresent=true
E OcWebHost/Proxy: upstream GET http://127.0.0.1:4095/ failed: unexpected end of stream on http://127.0.0.1:4095/...
E OcWebHost/Proxy: upstream GET http://127.0.0.1:4095/favicon.ico failed: unexpected end of stream on http://127.0.0.1:4095/...
```
**画面**：`research/env/v7/v7_n5_noreverse2.png` —— 整个页面只有一行裸 JSON：
`{"error":"unexpected end of stream on http://127.0.0.1:4095/…"}`

**判定 FAIL**，理由：确实**不是纯白、也不是原样卡住**（有内容、会刷新），但**不构成"可理解的错误页/兜底页"**：
1. 给的是**代理内部异常原文**，不是任何面向用户的话；
2. **没有重试按钮**、没有"检查后端地址/重新连接"引导 ⇒ 用户无法自救；
3. **泄露内部 URL 与端口**（`127.0.0.1:4095`）。

**另一条分支（无口令时）**：`pm clear` + 后端不可达 ⇒ 宿主弹**原生**对话框 `配置 opencode 后端`（`v7_n5_noreverse.png` 之前的一次 dump，标签见 `v7_n5a_labels.txt`）。**这一层是合格兜底**（用户能看懂、能输口令、能"先不配置"）。但**已播种口令的正常用户**走不到这一层 ⇒ 落在上面的裸 JSON。

**恢复配置**：`adb reverse tcp:4095 tcp:4095` → `reverse --list` 回读 `host-16 tcp:4095 tcp:4095` ✅ 已恢复（截图 `v7_reconnect.png`，logcat `credentials stored backend=http://127.0.0.1:4095 passwordPresent=true`）。

### 2.2 口令错 —— ❌ **FAIL**

**方法**：`pm clear` → 原生对话框输入 **`wrongpass123`**（12 位）→ 点"保存并连接"。

**原始输出**（logcat，代理层确实识别为认证失败）：
```
W OcWebHost/Proxy: auth rejected: GET http://127.0.0.1:4095/api/event -> 401; user=opencode passLen=12 authHeaderSent=true
W OcWebHost/Proxy: auth rejected: GET http://127.0.0.1:4095/api/info  -> 401; user=opencode passLen=12 authHeaderSent=true
I OcWebHost/Proxy: sse response 401 ctype=null te=chunked cl=null ce=null
（401 持续每 ~1s 重复）
```
**画面**：`research/env/v7/v7_n5_wrongpw.png` —— **停留在 Home 的 "Loading" 骨架屏 indefinitely**；`uiautomator` 树仅 2,248 B、无任何错误文案节点（`v7_n5c_labels.txt` 为空）。

**判定 FAIL**：本仓串 **`oc_connect_error_auth` 在页面上一处都没出现**（且它根本不在 dex 里 —— 它是上游 SPA 的 i18n key，页面没渲染出来）。用户面对的是**永不结束的加载骨架**，既无"认证失败"提示，也**没有重试/重新输入口令的入口**（口令框已被 `pm clear` 后的一次性对话框消费掉）。**这是三类里最糟的一种：看起来像"在加载"，实则永久失败。**

**恢复配置**：`pm clear` → 重新输入 `opencode` → `credentials stored backend=http://127.0.0.1:4095 passwordPresent=true` ✅ 已恢复。

### 2.3 WebView 过旧 —— **引用 G1/V3，本轮不重跑**

- 结论出处：`research/web/WEBVIEW_PREFLIGHT.md`、`research/web/PREFLIGHT_IN_PACKAGE.md`（G1 轮）、`FIX_F4_KEEPSCREEN.md`（V3 轮）。
- 内容：WebView 版本 **83**（低于门槛）⇒ 宿主改显示**诊断页并带真实版本号**，而不是白屏。
- 本轮**未重跑**（派单明确"只需引用，不必重跑"）。⚠️ 该结论本轮**未经独立复核**，按规矩不重复背书。

### 2.4 N-5 小结

**三类里两类 FAIL，且都出在"代理层已知道真相、页面层没把真相告诉用户"这一处断层**：代理日志里有 `auth rejected…401`、有 `upstream … failed`，但**没有一条走到用户眼前**。二者共同的可修方向是把代理已知的失败原因透传给页面并配一个重试入口。

---

## 3. N-7 剪贴板 —— ✅ **PASS**

**为什么不用 CLI**：`adb shell cmd clipboard` 在本镜像上**无实现**（原始输出 `No shell command implementation.`），`service list` 里虽有 `clipboard: [android.content.IClipboard]` 但无可用 CLI 通道 ⇒ 改走**真实 UI 路径**（这反而更贴近产品真实使用）。

**方法（在 SPA 的 `Search sessions` 输入框上，全真实按键）**：
1. `input keycombination 113 29`（CTRL+A）→ `keyevent 67`（DEL）清空 → `input text "OC_CLIP_98765"`；
2. `keycombination 113 29`（全选）→ `keyevent 278`（**KEYCODE_COPY**）⇒ 复制；
3. `keycombination 113 29` + `keyevent 67` ⇒ 清空输入框；
4. `keyevent 279`（**KEYCODE_PASTE**）⇒ 粘贴。

**关键证据（排除"其实是重新打字"的假阳性）**：
- 清空后：`v7_c3_cleared.png` —— 输入框显示占位符 **"Search sessions"**（**确认为空**）；
- 粘贴后：`v7_c4_pasted.png` —— 输入框内出现 **`OC_CLIP_98765`**（带光标）。

⇒ **复制与粘贴两端都通**，且系统剪贴板在 WebView 内**跨"清空"存活**，说明走的是真剪贴板而非 WebView 内部缓存。

**判定 PASS**。覆盖：页面输入框粘贴 ✅、选中复制 ✅。
**未覆盖（如实记录）**：命令面板/聊天消息区的复制（该区域需进会话且选中文本，本轮时间预算内未做）；`cmd clipboard` 不可用 ⇒ **无法从 CLI 侧独立读取剪贴板内容做二次交叉验证**，本条 PASS 仅基于 UI 端到端闭环。

---

## 4. 收尾复核输出（原文）

```
=== emu kill (serial-scoped, no -a, no wildcard) ===
OK: killing emulator, bye bye
OK
=== VERIFY 1: our AVD residual processes ===
OC_*/Medium_Desktop_B residual count = 0
=== VERIFY 2: any emulator/qemu at all (teardown helpers?) ===
emulator/qemu total process count = 0
=== VERIFY 3: port 6110/6112/6114 LISTEN ===
LISTEN count on 6110/6112/6114 = 0
=== VERIFY 4: adb devices ===
List of devices attached
=== VERIFY 5: gradle lock ===
.agent-gradle-lock exists = False
=== VERIFY 6: backend 4095 untouched (never started/stopped by me) ===
LocalPort OwningProcess
---------  ------------
      4095          3748
```

`wm` 复原复核（杀前）：
```
--- verify no Override lines remain ---
Physical size: 2560x1600
Physical density: 320
```

杀前归属确认（按 AVD 名 + 端口，规则 2 判据）：
```
ProcessId Name         avd           port MINE
------- ----         ---           ---- ----
    26268 emulator.exe OC_Deliver_R2 6110 True
```

- **故意保留：无。**
- 未执行 `adb kill-server`、未用 `-a`/通配符，只按 serial 关。
- 开轮时 `adb devices` 为空、全机 emulator/qemu 进程数 0、6110/6112/6114 全 FREE ⇒ **不存在可被波及的用户设备**（无 `MCRemote_*` / `DSH_*` / `T7x_*` 在场）。
- `adb reverse --remove tcp:4095` 曾被本机安全策略拦下（判为 delete-like）⇒ **未绕过**，改用派单允许的"指向错端口"完成 N-5-1，事后用 `adb reverse tcp:4095 tcp:4095` 覆盖恢复；实例关闭后该 reverse 随之消失。
- ⚠️ **第二次自建同端口 6110**（为补验 Settings 入口，见 §1.3b）：该实例曾一度「进程在、adb 掉线」，停掉并发 adb 客户端后**进程自行消失**。**本轮对它从未发出过任何 kill**，故未执行 `emu kill`；`wm reset`/reverse 复原随实例一并消失。**未绕过任何安全策略**。这条**本轮自身违反了"同一时刻只开一个 adb 客户端"的既有规避手段**（后台任务与前台检查并发发 adb），如实记录。
- ⚠️ 本轮**主**实例未出现 F4/R2 那种静默退出，全程存活到收尾；**第二次**实例出现了"进程仍在但 adb 掉线"的异常（见上）。

**第二次自建实例消失后的最终复核**：
```
=== adb devices ===
List of devices attached
=== emulator/qemu procs ===
count=0
=== port 6110 LISTEN ===
LISTEN=0
```

## 5. 我碰过哪些 serial

**只有 `emulator-6110`**（本轮自建、端口 6110、AVD `OC_Deliver_R2`，`-camera-back virtualscene`；同一 serial 起了两次，均属本轮）。无其它 serial 被本轮触碰。

## 6. 未闭环

1. **相机 `getUserMedia` live 流（核心项）** —— release 包上不可复现，根因与三条退路见 §1.3/§1.4。**需产品负责人决定**出 DEBUG 验收包还是加受控钩子。
2. 🔴 **「左边缘抽屉 → Settings → Add server → Scan QR code」这条唯一理论可达路径「未测到」而非「已排除」**（§1.3b）—— 探测因设备掉线中断。**下一轮必须串行跑（同一时刻只有一个 adb 客户端）**重试这条；这是本项最大的残留不确定性。
2. **F3 的取流结论在 DEBUG 包上是否仍成立** —— 本轮没有 DEBUG 包，**未复核**（不代 F3 下结论）。
3. **扫码解码层**（真扫一个配对 QR）—— 仍然未验，且在入口可达之前**无从验起**。
4. **N-5 两处 FAIL 的修复** —— 均需 `app/**`（本轮无写权限）。
5. **N-7 命令面板/消息区复制** —— 本轮未覆盖。
6. **N-5 WebView 过旧** —— 仅引用 G1/V3，本轮未独立复核。
7. **`-skin pixel_tablet` 不是合法 skin 名** —— 首次起机报 `ERROR | unknown skin name 'pixel_tablet'`；`pixel_tablet` 是**几何/设备档位**而非 skin 目录，几何应用方式见 `camera_route.ps1` STEP 2（`wm` 覆盖）。建议写进环境交接以免下一轮重踩。

## 7. 本轮产物

| 文件 | 说明 |
|---|---|
| `research/env/camera_route.ps1` | 相机可复现路径脚本（7 步断言 + 收尾） |
| `research/web/V7_CLOSEOUT.md` | 本报告 |
| `research/env/v7/*.png` | 全部原始截图（口令、连接、空口令兜底、后端不可达裸 JSON、错口令 Loading、剪贴板 4 连拍等） |
| `research/env/v7/*.xml` / `*_labels.txt` / `*_parsed.json` | `uiautomator` 原始 dump 与解析结果 |
| `research/env/v7/v7_emu_6110.log` | 模拟器启动日志 |
