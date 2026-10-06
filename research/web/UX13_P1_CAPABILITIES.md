# UX13-P1 能力复验①（当前 UX12 包）

- 日期：2026-10-06
- 包：`research/deliver/opencode-shell-web.apk`
  - MD5：`BF688F8A033EDE65C3D4F8C5E8D1E930`（16796038 字节，debug）
  - 包名：`com.xiufeigo.opencodeshell.web.debug`
- 包内 `assets/www/oc-host.js`：57236 字节，**MD5 `8A3B3E93EBF61BEC347067D4E4074A69`**
  （与 `webapp/src/main/assets/www/oc-host.js` 同尺寸 57236）
- 后端：`127.0.0.1:4095`（opencode/opencode v2.0.24），**已在跑，本轮不启停**
  - 起始 OwningProcess = **15096**
- AVD：`Medium_Phone_API_37.0`，端口 **6790**（起机前 6790 无占用）
- SDK：`C:\Users\xiufeigo\AppData\Local\Android\Sdk`

> 纪律：只关自建 emulator-6790；不 kill-server；不改 `webapp/**`/`app/**`；不碰 gradle。本轮**只验证，不改代码**。

---

## 步骤 0：起机装包 ✅

- 起机：`emulator -avd Medium_Phone_API_37.0 -port 6790 -no-snapshot-load -no-boot-anim`（Start-Process 脱离会话，pid 18308）
  - 起机前 6790 端口无占用；`sys.boot_completed=1` @ 16:26:01
  - `ro.build.version.release=17`，`ro.build.version.sdk=37`
- `adb devices` → 仅 `emulator-6790  device`
- `adb reverse tcp:4095 tcp:4095` → 成功（`host-15 tcp:4095 tcp:4095`）
- `adb install -r -t` → **Success**
- `pm clear com.xiufeigo.opencodeshell.web.debug` → Success
- **WebView 版本**：`com.google.android.webview, 145.0.7632.218`（versionCode 763221809, targetSdk 36）
- `am start` → `topResumedActivity = com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity`
- **UA（注入只读）**：
  ```
  Mozilla/5.0 (Linux; Android 17; sdk_gphone64_x86_64 Build/CE2A.260420.019; wv) AppleWebKit/537.36
  (KHTML, like Gecko) Version/4.0 Chrome/145.0.7632.218 Mobile Safari/537.36 OpenCodeWebHost/0.1 (W1-skeleton)
  ```
- **注入通道验证可用**：`OcWebHost/Eval: eval=b64:... -> "{\"ua\":...,\"href\":\"http://127.0.0.1:4173/\",\"ver\":\"complete\"}"`
  - 页面来源确认 `http://127.0.0.1:4173`（方案 A 生效）
- 截图：`_f13_shots/00_launch.png`

> 注：`logcat -s OcWebHost:E` 抓不到 Eval（级别为 I），须用 `OcWebHost/Eval` 标签或全量 grep。

---

## 步骤 1：连服务器并进会话 ⚠️（连服务器 ✅ / 进会话未完成）

**1a. 添加服务器 ✅（绿点）**

真实点击路径（全部 `input tap` 真实手势）：
1. Home 顶栏左菜单 (67,120) → 抽屉
2. 抽屉 `Settings` (179,2324)
3. 段选择器 `Preferences ▾` (868,216) → 选 `Server`
4. `Add server` (902,482) → 对话框
5. `Server address` 真实键入 `http://127.0.0.1:4095`
6. `Password` 真实键入 `opencode`
7. 提交键 `Add server` (907,1652)，等 **16 秒**
8. 段选择器复看 → 列表出现 `127.0.0.1:4095` 与 `127.0.0.1:4173` 两项

- **绿点确认**（`_f13_shots/12_srv4095.png`）：`127.0.0.1:4095` 行前为**绿点**，`http://127.0.0.1:4095`
- 副证：`127.0.0.1:4173` 行为**红点**（对照）

**1b. 回 Home 切 4095 上下文 ✅（首轮找错入口，后已达成）**

- 首轮在 Home 找不到 4095 行：底部抽屉只有 `New session / Home / Settings / Help`；
  段菜单在有服务器后由 `Server` 变为逐服务器条目。此时上下文固定在 `All projects / 127.0.0.1:4173`。
- **正确入口是 Home 页项目行右侧的 chevron**（设备 **992,262**）——点开才是服务器切换列表：
  - `🔴 127.0.0.1:4173`
  - `🟢 127.0.0.1:4095`
- 真实点击 `127.0.0.1:4095` 行（设备 **306,467**）后切换成功：
  上下文标题变为 `All projects / 127.0.0.1:4095`，搜索框变为
  `Search sessions in 127.0.0.1:4095`，**会话列表正常渲染**
  （`v2 smoke test` / `New session` / `Greeting` / `Single word reply request` …）
  截图 `_f13_shots/28_chevron.png`、`_f13_shots/29_ctx4095.png`
- 进入 `v2 smoke test` 会话（设备 **248,598**）→ 会话页加载完成，
  可见 `Session / Changes / Files / Terminal` 四个 Tab 与 composer
  截图 `_f13_shots/30_session.png`

- ⚠️ 由此修正首轮判断：**Home 页是能切 4095 的，只是入口藏在项目行 chevron 里，不是顶层的「4095 行」**。
  首轮"未找到"是入口找错，不是能力缺失。

---

## 步骤 2：相机扫码配对 ❌ **被拒（红）**

**入口发现（真实点击）**

- `Add server` 对话框只在 **Settings 页头部无标签 "+" 按钮**（CSS 259,82 → 设备 **680,215**）可稳定打开
  - 注：`Server` 段在有服务器后从段菜单消失，逐服务器段的 `...` 菜单只有 `Server(禁用) / Edit / Set as default`
  - **`Edit server` 对话框没有 `Scan QR code` 按钮**（只有 Cancel/Save）——该按钮仅存在于 Add server 对话框
- 对话框内 `Scan QR code` 按钮（设备 **539,1510**）真实点击 →

**观察结果**

- ✅ **相机视图出现了**：对话框切换到 `PairingScanner`——显示
  "Point your camera at the QR code shown by `opencode pair`" + 取景框
- ❌ **但拿不到流**：`video` 元素存在，readyState=0（远低于通过标准 ≥2），显示灰色占位图 + 播放图标
- ❌ 页面红字报错：**"Could not open the camera. Allow camera access or enter your connection details manually."**
  （截图 `_f13_shots/23_after_scan.png`；对比前图 `_f13_shots/22_before_scan.png`）

**关键日志（OcWebHost，onPermissionRequest 相关）**

```
OcWebHost: camera permission DENIED origin=http://127.0.0.1:4173/ resources=[android.webkit.resource.VIDEO_CAPTURE]   (×6)
OcWebHost: web[WARNING] The camera stream is only accessible if the page is transferred via https.
```

**注入只读页面状态（b64 通道）**

```json
{"hasVideo":true,"readyState":0,"paused":true,"videoW":0,"videoH":0,
 "isSecure":true,"origin":"http://127.0.0.1:4173"}
```
`navigator.permissions.query({name:'camera'}).state` → **`"prompt"`**

**判定：❌ 被拒**

- 命中判据「onPermissionRequest 命中但**未 grant**（可信 origin 判定失败）」完全吻合：
  宿主的 `onPermissionRequest` 回调**确实被触发**并打了日志，但结果是 **DENIED**，从未 `request.grant()`。
- 根因：页面来源是 **`http://127.0.0.1:4173`（明文 http，非 https）**。方案 A 把页面托管在 http 上，
  宿主可信 origin 判定不通过，相机资源被直接拒。
- 值得注意的矛盾：`window.isSecureContext === true`（127.0.0.1 属 potentially trustworthy origin），
  但**宿主层判定与浏览器 isSecureContext 不一致**——这是宿主实现问题，不是页面侧问题。
- 补充：scanner 资源本身加载正常（`/_assets/scanner-DDd1Cm7l.js` / `scanner-1tlSXhE2.css` 均 200），
  说明失败点唯一且明确，就在相机权限授予环节。

---

## 步骤 3：通知点击回会话 ❌ **失败（红）——根本没有通知产生**

**前置修复（环境前置，非改码）**

- 首次尝试时 `dumpsys package` 显示：
  `android.permission.POST_NOTIFICATIONS: granted=false`，
  且 `dumpsys notification` 中 `AppSettings: com.xiufeigo.opencodeshell.web.debug importance=NONE`
  → 首装未授权，通知不可能出现。已用 `pm grant` 授权（`granted=true`）后重测。
- 通知渠道本身**存在**：`NotificationChannel{mId='oc_turns', mName=回合完成, mImportance=3}`

**测试过程（权限已授权后，干净重试）**

- 会话内 composer 真实键入 `reply the single word OK` → 真实点击发送
- 发送后 **600 ms 内**按 `KEYCODE_HOME` 切后台
- 回复窗口内每 4 秒轮询一次，共 8 次 / 32 秒：

| 时刻 | 本包通知数 | topResumedActivity |
|---|---|---|
| t+4s | 0 | NexusLauncher |
| t+8s | 0 | NexusLauncher |
| t+12s | 0 | NexusLauncher |
| t+16s | 0 | NexusLauncher |
| t+20s | 0 | NexusLauncher |
| t+24s | 0 | NexusLauncher |
| t+28s | 0 | NexusLauncher |
| t+32s | 0 | NexusLauncher |

- **消息确实发出且确实有回复**：回 App 后 `_f13_shots/35_verify.png` 可见三条
  `reply the single word OK` → `Used 1 Thought` → `OK`（时间戳 8:36 / 8:37 / 8:39）
- 全量 `dumpsys notification` 的 `Notification List` 中**只有 systemui 的一条 DSK 通知**，
  `pkg=com.xiufeigo` 一条都没有
- logcat 中**没有任何** `NotifierHost` / `OcNotify` / `oc_turns` 相关行

**判定：❌ 失败**

- 判定链断在第一环：**App 处于后台、助手已回，但没有发出任何通知**，
  因此"点击通知回到对应会话页"这一行为**无从触发、无法验证**。
- 这不是"点击后没回到会话"，而是"通知压根不存在"。属于宿主通知链路未接通。
- 附注：App 在后台仍持续轮询后端（logcat 可见 `http://127.0.0.1:4095/api/info` 每 10 秒一次），
  说明 App 本身活着、前后台切换正常，失败点精确定位在「回合完成 → 发通知」这一步。

---

## 步骤 4：文件选 / 文件存

### 4a. 文件选（onShowFileChooser）✅ **通过（绿）**

真实点击路径（全部真实手势）：
1. 会话 composer 左下 `+` (114,2316) → 弹出菜单
   `Images and files (Ctrl+U) / Commands (/) / Context (@) / Shell command (!)`
2. 点 **`Images and files`** (230,1958)
3. **系统文件选择器弹出**（硬证据）：
   ```
   topResumedActivity=ActivityRecord{... com.google.android.documentsui/
       com.android.documentsui.picker.PickActivity t11}
   ```
   WindowManager 同步记录 `TO_BACK ... WebHostActivity` 的转场 → 确实从 WebView 跳到系统选择器
4. 准备素材：`adb push research/web/_f13_shots/00_launch.png /sdcard/Download/f13_test.png`（44532 字节）
5. 选择器内真实点击：抽屉 (74,126) → `Downloads` (246,580) → `f13_test.png` (300,900)

**结果**

- ✅ 选完自动回到 `com.xiufeigo.opencodeshell.web.WebHostActivity`
- ✅ **附件缩略图已出现在 composer 中**（带 `×` 移除按钮）
  —— 截图 `_f13_shots/41_attached.png`
- 判定：`onShowFileChooser` → DocumentsUI → 结果回传 WebView **整条链路通**

### 4b. 文件存（launchSave）⚠️ **未找到可达路径**

已真实点击探查过的位置（均无保存/下载入口）：

| 探查点 | 真实点击 | 结果 |
|---|---|---|
| 会话 `Files` 段 | (611,223) | 文件树（All files / 项目目录列表），注入扫描 `btns:[]`、`hasDownloadWord:false` |
| 文件查看器 | `CHANGELOG.md` (248,2296) | 打开 Preview/Source 查看器，**无 Save/Download 按钮** |
| 查看器 `More options` (1008,226) | 展开菜单 | 只有 **Usage** / **Session details** 两项 |
| 消息行复制图标 | — | 仅为复制文本，非保存文件 |

- `/sdcard/Download` 全程只有我 push 的 `f13_test.png` 一个文件，**没有任何新文件落盘**
- logcat 中无 `download` / `launchSave` / `setDownloadListener` 相关行

**结论：未找到可达的保存入口。** 不硬凑，如实记录。
需要说明的是：这既可能是 UI 层面确实没做导出功能，也可能是做在未覆盖的路径上；
本轮 UI 面已穷举上述四处，均未触达任何触发 `launchSave` 的控件。

---

## 步骤 5：收尾复核 ✅

| 复核项 | 结果 |
|---|---|
| `adb emu kill` | `OK: killing emulator, bye bye` |
| `adb devices` | **空**（无任何 serial） |
| 端口 6790 | **NO LISTENER — 已释放**（kill 后仅短暂 TimeWait） |
| emulator / qemu 进程 | 15 秒后复查 → **no emulator processes** |
| `adb reverse --remove tcp:4095` | 已执行；`reverse --list` 清空 |
| 后端 4095 OwningProcess | **15096 → 15096（未变）** |
| 后端 `/api/info` | **HTTP 200** `{"version":"2.0.24","pid":15096,...}` |
| 未 kill-server | ✅ 全程只用了 `adb emu kill`，未动 adb server |
| 未改 `webapp/**` / `app/**` | ✅ 本轮只读页面状态、只做真实点击 |
| 未碰 gradle | ✅ |

**碰过的 serial（应只有 emulator-6790）**

```
List of devices attached
emulator-6790	device
```

全程仅 `emulator-6790` 一个自建模拟器，收尾后设备列表为空。

---

# 总表

| # | 能力 | 判定 | 关键证据 |
|---|---|---|---|
| 1a | Add server 连 4095 | ✅ 绿 | 4095 行绿点；4173 行为红点对照 |
| 1b | Home 切 4095 上下文进会话 | ✅ 绿 | 项目行 chevron → 4095 行；会话列表渲染；进入 `v2 smoke test` |
| 2 | **相机扫码配对** | ❌ **被拒（红）** | `camera permission DENIED origin=http://127.0.0.1:4173/ resources=[VIDEO_CAPTURE]` |
| 3 | **通知点击回会话** | ❌ **失败（红）** | 后台 32 秒轮询本包通知数恒为 0，回复已到达 |
| 4a | 文件选（onShowFileChooser） | ✅ 绿 | 跳 DocumentsUI `PickActivity`；选完回 App，附件缩略图进 composer |
| 4b | 文件存（launchSave） | ⚠️ 未找到入口 | Files 段/文件查看器/More options 四处均无保存控件；Download 无新文件 |

## 三项重点小结

1. **相机：被拒，且原因明确可定位。** 相机视图正常渲染（PairingScanner 起来了），
   但宿主 `onPermissionRequest` 命中后**直接 DENIED、从未 grant**，根因是页面走明文
   `http://127.0.0.1:4173`、宿主可信 origin 判定不通过。
   特别值得注意：`window.isSecureContext === true`（127.0.0.1 属 potentially trustworthy），
   **宿主判定与浏览器判定不一致**——这是宿主实现层的问题，不是页面侧能修的。

2. **通知：链路根本没通。** 授权 POST_NOTIFICATIONS、渠道 `oc_turns` 也在，
   App 确实切到后台、助手确实回复了（页面里能看到 `OK`），
   但 **一条通知都没发出来**。所以"点击通知回会话"无法触发验证——
   卡点在「回合完成 → 发通知」这一环，不在点击跳转那一环。

3. **文件选通了，文件存没找到入口。** 选文件整条链路（WebView → DocumentsUI → 回传 → composer 附件）
   完全正常。保存方面，Files 文件树、文件查看器、More options 菜单三处都真实点过，
   **没有任何能触发 `launchSave` 的控件**，如实记为"未找到可达路径"，不硬凑。

## 本轮新增的可达性知识（下轮可直接复用）

- `Add server` 对话框的稳定入口 = **Settings 页头部无标签 `+`**（设备 680,215），
  不是 `Server` 段（加了服务器后该段就从菜单消失了）
- `Scan QR code` 按钮**只在 Add server 对话框**，`Edit server` 对话框里没有
- Home 切服务器 = **项目行右侧 chevron**(992,262) → 服务器列表 → 点行(306,467)
- composer `+` 菜单里 `Images and files` 才是走文件选择器的入口
- 注入通道注意两点：
  1. logcat 里 Eval 标签级别是 **I**，`logcat -s OcWebHost:E` 抓不到，要用 `OcWebHost/Eval` 或全量 grep
  2. 注入代码**不能用 async**，返回值会被序列化成 `{}`；权限状态要 Promise 回调后二次注入读回
