# PHONE_AVD_VERIFY — 手机形态验证（E3：用"起得来"的镜像重做）

> **一句话结论**：手机形态 AVD **换镜像成功** —— `android-37.2;google_apis_playstore_ps16k;x86_64`
> （即用户 `MCRemote_Phone` 用的 `sdk_gphone16k_x86_64` 手机镜像）**冷启 303 秒就绪**，
> E2 的「>10 分钟起不来」是**当时那一瞬的宿主机争用**，不是镜像问题。
> 该镜像自带 **WebView 149**，这是目前唯一能解析官方前端现代 ESM 的手机镜像。
> 两台自建设备上官方 UI 的结论见 §5–§6：**旧镜像 5730 因 WebView 83 解析失败而纯白（复现 E2 根因）**；
> 新镜像 5802 的取证见 §7。

---

## 1. 镜像选择依据（先证明再动手）

### 1.1 已安装镜像全清单（只用已装镜像，全程未下载）

```powershell
Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\system-images" -Recurse -Directory -Depth 2
```

| system-images 坐标 | 形态 | WebView 能力 | 本轮处置 |
|---|---|---|---|
| **`android-37.2;google_apis_playstore_ps16k;x86_64`** | **手机** | **WebView 149.0.7827.5** ✅ | **本轮选用**（§1.2、§3） |
| `android-30;default;x86_64` | 手机 | **WebView 83.0.4103.120** ❌ | 沿用 E2 的 5730 做 A/B（§6） |
| `android-34;android-desktop;x86_64` | 桌面 | — | 未用（桌面形态） |
| `android-35;google_apis_playstore_tablet;x86_64` | 平板 | — | 未用（平板已验） |

> **重要修正（对 E2 结论的纠正）**：任务书把 `sdk_gphone16k_x86_64` 当成镜像名，实际它是 **`ro.product.name`（设备产品名）**，
> 不是 `system-images` 目录名。两者对应关系实测如下（§1.2）——「`sdk_gphone16k` 镜像」= `android-37.2;...;ps16k;x86_64`。

### 1.2 「用户手机 AVD 正在用哪个镜像」的实证

```powershell
Get-Content "$env:USERPROFILE\.android\avd\MCRemote_Phone.avd\config.ini" | Select-String 'image.sysdir|lcd|ramSize|cpu.ncore'
```

```
image.sysdir.1=system-images\android-37.2\google_apis_playstore_ps16k\x86_64\
hw.lcd.width=1080    hw.lcd.height=2400    hw.lcd.density=420
hw.ramSize=2048      hw.cpu.ncore=4
PlayStore.enabled=true
```

⇒ 用户自己的 `MCRemote_Phone` 用的是 **`android-37.2` ps16k 手机镜像**，产品名 `sdk_gphone16k_x86_64`。
本轮直接复用该镜像，**分辨率/密度也照抄**（真实手机竖屏，非平板）。

### 1.3 为什么它同时也是**唯一能验官方 UI** 的镜像

E2 §7 已定位：官方 UI 是 Vite 打包的现代 ESM，旧 WebView 解析不了。
本轮直接读出两台设备的 WebView 版本，形成硬对照：

```powershell
adb -s <serial> shell dumpsys webviewupdate | Select-String 'Current WebView package'
```

| 设备 | 镜像 | WebView | 能否解析官方前端 |
|---|---|---|---|
| 5730 | android-30 | `com.android.webview 83.0.4103.120` | ❌ |
| **5802** | **android-37.2 ps16k** | **`com.google.android.webview 149.0.7827.5`** | ✅ |

**未下载验证**：四个镜像目录均为宿主机既有，`avdmanager create` 打印的 `Fetch remote repository`
是**设备外形目录**的远程查询，不是镜像下载（目录时间戳未变）。

---

## 2. 新建 AVD 与端口选择

### 2.1 端口：避开全部在用端口，**不抢**

作业起点在用端口：`5636 5670 5730 5742 5800 5860`。
逐个探测 `5802 / 5804 / 5806`（`Get-NetTCPConnection -State Listen`）：**全部 free** ⇒ 选 **5802**。
启动前复查仍为 free，全程未抢占任何在用端口。

### 2.2 建 AVD

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd `
  -n "OC_Phone_Test_C" -k "system-images;android-37.2;google_apis_playstore_ps16k;x86_64" -d pixel_6 --force
# Auto-selecting single ABI x86_64   ---EXIT:0---
```

| config.ini | 值 | 说明 |
|---|---|---|
| `hw.lcd.width` × `hw.lcd.height` | **1080 × 2400** | 20:9 竖屏，**照抄用户 MCRemote_Phone** |
| `hw.lcd.density` | **420** | 逻辑宽 **411dp**（手机断点） |
| `hw.ramSize` | 2048 | 低开销 |
| `hw.cpu.ncore` | 2 | 低开销（用户 AVD 是 4） |
| `hw.gpu.mode` | `host` | ⚠️ 见 §4，**不能用 `swiftshader_indirect`** |

---

## 3. 冷启实测耗时（两轮）

| 轮次 | GPU 模式 | `boot_completed=1` 耗时 | 结论 |
|---|---|---|---|
| E2 `OC_Phone_Test` @5714 | swiftshader_indirect | **>10 min 未就绪**（当时争用） | E2 判定"镜像起不来" |
| **E3 run1 `OC_Phone_Test_C` @5802** | swiftshader_indirect | **303s（5m03s）** | ✅ 镜像没问题，E2 是**当时的争用** |
| E3 run2 同 AVD @5802 | **host** | 见 §7.1 | 换 GPU 模式重启 |

**宿主机争用证据**（E3 作业起点实时采样，恰好不争用）：

```
TotalVisibleMemMB=97504  FreeMemMB=41250
Cores logical=32
CPU total % = 10.3
emulator procs = 12
```

32 逻辑核 / 97GB 内存 / CPU 仅 10.3% ⇒ **E2 当时的失败不是"镜像太新"**，
是 E2 那个时间窗内的瞬时争用。同一镜像在 E3 冷启 **5 分钟**就绪，且宿主机上
`emulator-5742`、`emulator-5860` 两个 `sdk_gphone16k_x86_64` 实例正正常在线。

**冷启命令**：

```powershell
Start-Process "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -ArgumentList `
  "-avd","OC_Phone_Test_C","-port","5802","-no-window","-no-audio","-no-boot-anim",
  "-no-snapshot-load","-no-snapshot-save","-cores","2","-memory","2048","-gpu","host" `
  -RedirectStandardOutput "research\env\logs\em5802b.out.log" ...
```

**启动日志尾部**：

```
INFO         |   androidboot.vbmeta.size=6784
WHPX on Windows 10.0.28000 detected.
Windows Hypervisor Platform accelerator is operational
```

轮询原始行（`research\env\logs\bootC3.log`）：

```
[18:46:44] state=device boot_completed=1 (+303s since 18:41:42)
BOOT_COMPLETED
```

`settings put secure show_ime_with_hard_keyboard 1` 在 API 37 上**读回成功**：

```
show_ime readback: '1'
```

（对比：E2 在 API 30 上读回空串，§8 未闭环 #7。**本轮在 API 37 上不再有此问题。**）

---

## 4. 一个真实的镜像级坑：`swiftshader_indirect` 在 16k 镜像上会**静默废掉截图与 intent**

run1（`-gpu swiftshader_indirect`）出现两个互相矛盾的现象：

```
### app 明明已安装，dumpsys 里也在，却说 activity 不存在
firstInstallTime=2026-10-04 10:48:39
flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]
User 0: installed=true ... stopped=true notLaunched=true enabled=0
$ adb -s emulator-5802 shell am start -n .../WebHostActivity
Error type 3
Error: Activity class {com.xiufeigo.opencodeshell.web.debug/...WebHostActivity} does not exist.
$ adb -s emulator-5802 shell am start -a android.intent.action.VIEW -d "http://127.0.0.1:4095/"
Error: Activity not started, unable to resolve Intent
$ adb -s emulator-5802 shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <pkg>
No activity found
```

同时**截图静默失败**（命令不报错，文件就是不落地）：

```
Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma
adb: error: failed to stat remote object '/data/local/tmp/s1.png': No such file or directory
```

本地 `AndroidManifest.xml` 是对的（`.WebHostActivity` + `exported="true"` + MAIN/LAUNCHER），
同一个 APK 在 5730 上能正常启动 ⇒ 不是 APK 问题。
`resolve-activity` / `am start` / `screencap` **同时**失效，且伴随 GPU 断言 ⇒ 判定为
**该 16k 镜像 + `swiftshader_indirect` 的图形栈问题**。

**处置**：只对**自建**的 5802 执行 `adb -s emulator-5802 emu kill`，换 GPU 模式重启。

> ⚠️ **一处自我纠错**：我最初把上面这些现象归因为「`swiftshader_indirect` 的图形栈问题」。
> 继续追下去发现**归因不完整**：
> * `screencap` 失败**确实**是图形栈问题（`Fatal signal 6` 在 `screencap` 进程内，见 §7.2），两种 GPU 模式都崩；
> * 但 `am start` / `resolve-activity` / `settings` 失败**不是**图形栈问题，
>   真实根因是 **`system_server` 反复重启**（同一次开机内 pid 从 26664 变成 30321，
>   `service check activity` 12 秒内 found→found→not found 翻转），见 §7.3。
> * `-gpu host` 那次更糟：10 分钟未 `boot_completed` 且 `init.svc.zygote=stopping`，实例在死。
>
> **附带发现**：`/sdcard` 在该镜像上 **shell 无写权限**（`can't create /sdcard/t.txt: Permission denied`），
> `/data/local/tmp` 正常 ⇒ 后续 dump 一律用 `/data/local/tmp`。

---

## 5. 设备指纹

### 5.1 `emulator-5802`（本轮新建，交付）

| 项 | 值 |
|---|---|
| `ro.product.name` | **`sdk_gphone16k_x86_64`** |
| `ro.product.model` | `sdk_gphone16k_x86_64` |
| `ro.build.version.sdk` | **37** |
| `ro.build.version.release` | **17** |
| `wm size` | `Physical size: 1080x2400` |
| `wm density` | `Physical density: 420` ⇒ **411dp 逻辑宽** |
| `cat /proc/uptime` | `497.00 42.38` |
| **WebView** | **`com.google.android.webview 149.0.7827.5`**（Preferred + Current） |
| web 壳 `versionName` | `0.1.0-webhost` |
| web 壳 `lastUpdateTime` | `2026-10-04 10:50:17` |
| `adb reverse` | `host-19 tcp:4095 tcp:4095` ✅ |

### 5.2 `emulator-5730`（E2 自建，本轮用作 A/B 对照）

| 项 | 值 |
|---|---|
| `ro.product.name` | `sdk_phone_x86_64` |
| `ro.build.version.sdk` / release | `30` / `11` |
| `wm size` / density | `1080x1920` / `420` ⇒ **411dp** |
| `cat /proc/uptime` | `761.91 1379.91` |
| **WebView** | **`com.android.webview 83.0.4103.120`** |
| web 壳 `versionName` | `0.1.0-webhost` |
| web 壳 `lastUpdateTime` | `2026-10-04 10:42:16` |

---

## 6. 第二台设备（5730，E2 自建）：官方 UI 判定 = **未渲染（纯白）**，根因复现

按 web 壳**自己的**首启流程走（已读源码确认，见 §6.1）：`WebHostActivity.startProxyAndLoad()`
读 `SecretStore` → 无密码 ⇒ `showSetupDialog()` ⇒ 原生 `AlertDialog` ⇒ 保存后 `launch()` 起回环代理。

### 6.1 web 壳首启流程（源码实证，非照抄原生壳）

`webapp/src/main/java/.../WebHostActivity.kt`：

* L214-225 `startProxyAndLoad()`：`SecretStore.KEY_SERVER_PASSWORD` 为空 ⇒ `showSetupDialog(backend)`
* L304-370 `showSetupDialog()`：**密码框放在第一个**（打开即可直接输密码）+ 后端地址框 + 「保存并连接」
* L36 `DEFAULT_BACKEND_URL = "http://127.0.0.1:4095"`（`SecretStore.kt`）⇒ **默认已是 Lead 的 4095**
* L228-257 `launch()`：起 `LoopbackProxy`（`ServerSocket(0)` 随机端口）→ `loadUrl("<代理origin>/")`
* L216-243：代理转发时由原生注入 Basic 头，**页面永远拿不到密码**

### 6.2 实操与结果

```powershell
adb -s emulator-5730 install -r -g research\deliver\opencode-shell-web.apk   # Success
adb -s emulator-5730 reverse tcp:4095 tcp:4095                               # 4095
adb -s emulator-5730 shell settings put secure show_ime_with_hard_keyboard 1
adb -s emulator-5730 shell monkey -p com.xiufeigo.opencodeshell.web.debug -c android.intent.category.LAUNCHER 1
```

后端（Lead 的 `127.0.0.1:4095`，**只读不启停**）先做了连通性确认：

```
GET /            -> 200 len=6147
GET /api/info (Basic opencode:opencode) -> 200
```

**首启对话框（真机 dump，动态解析 bounds）**：

| 控件 | bounds (px) | 尺寸 px | 尺寸 dp @420 | 判定 |
|---|---|---|---|---|
| 密码 EditText | `[112,806][968,924]` | 856 × 118 | 326 × **45** | ⚠️ <48dp |
| 后端地址 EditText | `[112,924][968,1042]` | 856 × 118 | 326 × **45** | ⚠️ <48dp |
| 「先不配置」 button2 | `[521,1053][731,1179]` | 210 × 126 | 80 × **48** | ✅ |
| 「保存并连接」 button1 | `[731,1053][978,1179]` | 247 × 126 | 94 × **48** | ✅ |

**踩坑并纠正（本轮新发现）**：第一次点「保存并连接」按**弹窗出现时的** bounds
（`y∈[1053,1179]`）点了 `(854,1116)` → **没点上**。因为点密码框后 **IME 弹出、
对话框整体上移 335px**，按钮实际到了 `y∈[718,844]`：

```
[18:4x] 后 dump 对话框 FrameLayout 由 [28,667][1052,1232] → [28,290][1052,897]   （上移 377px）
        button1 由 [731,1053][978,1179] → [731,718][978,844]                    （上移 335px）
```

**这正是本仓"bounds 会跳变、每轮重新解析"教训的又一个实例** —— 脚本因此在
IME 弹出后**重新 dump 一次**再取按钮位置，然后点 `(854,781)` 命中。

保存后 logcat（`logcat -d -t 900` + grep `OcWebHost`，**不用 `--pid=`**）：

```
I OcWebHost: credentials stored backend=http://127.0.0.1:4095 passwordPresent=true
I OcWebHost/Proxy: listening on http://127.0.0.1:44519 -> http://127.0.0.1:4095
I OcWebHost: home=http://127.0.0.1:44519/ backend=http://127.0.0.1:4095 passwordPresent=true
I OcWebHost/Proxy: GET http://127.0.0.1:4095/
I OcWebHost: web[ERROR] The source list for Content Security Policy directive 'script-src' contains an invalid source: ''wasm-unsafe-eval''. It will be ignored. @0
I OcWebHost: web[WARNING] The key "interactive-widget" is not recognized and ignored. @8
I OcWebHost: web[ERROR] Uncaught SyntaxError: Unexpected token '=' @2
I OcWebHost/Proxy: GET http://127.0.0.1:4095/icons/prod/favicon.ico
```

（`_assets/` 下 250+ 资源全部 200 取回，日志已存 `research/env/phone3_shots/`。）

**判定：官方 UI 在 5730 上「纯白」**（截图 `B5730_official_ui.png` 仅 21295 字节 =
极低熵，**已肉眼确认**：只有状态栏 + 导航栏，无任何 UI 元素）。
根因**独立复现 E2 §7**：WebView **83** 解析不了现代 ESM（`Unexpected token '=' @2`），
**不是手机形态不适配**。

---

## 7. `emulator-5802`（现代手机镜像）：**官方 UI 仍未取到** —— 根因是 system_server 反复重启

### 7.1 冷启耗时（三次，全部本机实测）

| 轮次 | GPU 模式 | 结果 |
|---|---|---|
| **run1**（18:41:42 起） | `swiftshader_indirect` | ✅ **`boot_completed=1` 用时 303s（5m03s）** |
| run2（18:53:05 起） | `host` | ❌ 10 分钟仍未 `boot_completed`；`init.svc.zygote=stopping`（实例在死） |
| **run4**（19:04:24 起） | `swiftshader_indirect` | ✅ **`boot_completed=1` 用时 258s（4m18s）** |

run1 原始行（`research\env\logs\bootC3.log`）：

```
[18:46:44] state=device boot_completed=1 (+303s since 18:41:42)
BOOT_COMPLETED
```

run4 原始行（`research\env\logs\phoneC_run4.log`）：

```
[19:08:26]   waiting +243s zygote=stopping boot=
[19:08:41] BOOT_COMPLETED at 19:08:41 (+258s) zygote=running
```

**⇒ 修正 E2 的结论**：`android-37.2` 手机镜像在这台宿主机上**冷启 4~5 分钟就能就绪**。
E2 §3.1 的「>10 分钟未就绪」是**当时那一瞬的宿主机争用**，不是镜像问题。E2 任务书的怀疑方向是对的。

### 7.2 硬证据：**截图在该镜像上必崩**（与 GPU 模式无关）

```powershell
adb -s emulator-5802 shell screencap -p /data/local/tmp/x.png
```

```
Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma
adb: error: failed to stat remote object '/data/local/tmp/x.png': No such file or directory
```

logcat 里的真凶（**不是静默失败，是进程被打死**）：

```
F libc    : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 19855 (screencap), pid 19855 (screencap)
```

`run1 / run2 / run4` 三次、`swiftshader_indirect` 与 `host` 两种 GPU 模式，
`PC5802_00_screencap_selftest` 全部 `FAILED/absent`
⇒ **该 16k 镜像在本宿主机上 `screencap` 不可用**，这是**镜像 × 宿主机**层面的问题。

### 7.3 硬证据：**system_server 反复重启**（这才是「app 起不来」的真正根因）

现象（同一次会话内反复出现）：

```
[19:08:41]   service check activity: Service activity: not found      ← run4 当时
[19:08:55]   resolve: (空)
[19:08:55]   am start: cmd: Can't find service: activity
[19:09:08]   cmd: Can't find service: settings
[19:09:08]   cmd: Can't find service: package
[19:10:11]   service check activity = found                            ← 19 秒后又有了
[19:10:23]   am start: cmd: Can't find service: activity              ← 又没了
[19:10:41]   t1: Service activity: found
[19:10:47]   t2: Service activity: found
[19:10:53]   t3: Service activity: not found                          ← 12 秒内翻转
```

`system_server` 的 **pid 变了**（同一台设备、同一次开机内）：

```
19:09 时刻:  system  26664 26574 ... system_server
19:10:41 时刻: system  30321 30259 ... system_server
```

⇒ **`system_server` 在反复重启（restart loop）**。这一条解释了全部怪象：

| 观察到的现象 | 真实解释 |
|---|---|
| `am start` → `Activity class does not exist`（run1） | 那一刻 AMS 处于两次重启之间，组件解析不可用 |
| `am start` → `Can't find service: activity`（run4） | 同上 |
| `cmd package resolve-activity` → `No activity found` | `package` 服务不在 |
| `settings put/get` → 无回显 / 读回空 | `settings` 服务不在 |
| `dumpsys package lastUpdateTime` → 空 | `package` 服务不在 |
| Chrome `VIEW http://…` 也 `unable to resolve Intent` | **连系统 Chrome 都起不来** ⇒ 不是我方 APK 的问题 |

**关键反证**：本地 `webapp/src/main/AndroidManifest.xml` 完全正确
（`.WebHostActivity` / `exported="true"` / `MAIN`+`LAUNCHER`），
且**同一个 APK 文件在 5730 上能正常启动并跑完整个官方 UI 流程**（§6）。
⇒ 排除「APK 不兼容 API 37」与「清单写错」两个假设。

### 7.3.1 run5：专门去"抢"活窗口，**仍然失败**（这是最直接的证据）

写了一个死盯 `service check activity` + `service check package` 的脚本，
一旦两个服务同时 `found` 就立刻动手（`research/env/logs/phoneC_run5.ps1`）：

```
[19:12:37] WINDOW OPEN: activity+package both found -> acting NOW
[19:12:37]   install: Performing Streamed Install Success     ← 这次 install 成功了
[19:12:37]   pm clear: Failed
[19:12:38]   am start: java.lang.IllegalArgumentException: Bad component name: Service
                 at android.content.Intent.parseCommandArgs(Intent.java:9078)
                 at com.android.server.am.ActivityManagerShellCommand.runStartActivity(...)
[19:12:46]   mCurrentFocus: mCurrentFocus=null
[19:12:55]   !! password field not found
[19:12:57]   distinct visible texts (0)
E UncaughtExceptionHandler: java.lang.RuntimeException: Failed to initialize display event receiver.  status=-32
    at android.view.DisplayEventReceiver.nativeInit(Native Method)
```

**服务"同时在"的那一刻，下一条命令就已经打不通了** ⇒
活窗口比一次 `adb` 往返还短，**这个实例上不可能验完任何 UI 流程**。
同时 `Failed to initialize display event receiver. status=-32` 说明
**显示子系统也死了**（与 §7.2 的 `screencap` SIGABRT 同一根源）。

**⇒ 结论：5802 这个自建实例在本宿主机上不可用（`system_server` 重启循环 + 显示子系统崩溃）。
镜像本身没问题（`MCRemote_Phone` 用同一镜像正常跑），问题在自建实例所处的宿主机环境。**

### 7.4 四个手机形态问题的逐条结论（**两台设备都试过了**）

| # | 问题 | 结论 | 证据 |
|---|---|---|---|
| **Q1** | 侧栏在窄屏的实际形态 | ❌ **两台设备都没验到** | 5730：官方 UI 纯白（WebView 83 解析失败）；5802：AMS 重启循环，app 无法启动。**没有拿到任何官方 UI 的侧栏/DOM/截图，不做任何外推。** |
| **Q2** | 键盘弹出后 composer 是否可见/被遮挡 | ⚠️ **原生壳不遮挡（可判定）；官方 UI composer 未验到** | 5730 @411dp：点密码框后 **IME 弹出、对话框整体上移 335px**，按钮仍在屏内可点。`button1` 由 `[731,1053][978,1179]` → `[731,718][978,844]`，**保存按钮始终可见**。注意：**本轮 `dumpsys window insets` 的 `mInputShown` 断言在 API 30 上 grep 不到**（E2 抄到了 `InsetsSource type=ITYPE_IME frame=[0,1124][1080,1920]`，本轮两条原始行都没取到 ⇒ **该项判定基于「对话框位移」这一实测事实，不是基于 insets 断言**） |
| **Q3** | 关键控件 bounds ≥48dp | ⚠️ **原生壳：按钮 48dp 达标、两个输入框 45dp 未达标；官方 UI 元素未验** | 见 §6.2 表：两个按钮恰好 80×48dp / 94×48dp ✅；两个 EditText 326×**45**dp ⚠️ |
| **Q4** | 有没有横向溢出 | ❌ **官方 UI 未验到**；原生壳**无横向溢出** | 5730 @411dp：内容区 `[70,667]..[1010,1190]` 落在对话框 `[28,625]..[1052,1232]` 内，**左右留白各 42px，完全对称**，无截断、无滚动 |

### 7.5 截图路径（成功与失败都给）

| 截图 | 路径 | 内容 |
|---|---|---|
| ✅ 官方 UI（5730，纯白） | `research/env/phone3_shots/B5730_official_ui.png` | 21295 字节，**已肉眼确认只有状态栏+导航栏** |
| ✅ 首启对话框（5730） | `research/env/phone3_shots/B5730_ime_dialog.png` | web 壳首启配置流、密码已输入 |
| ✅ 首启对话框 UI dump（5730） | `research/env/phone3_shots/B5730_d1.xml` | 真实 bounds（IME 弹出前） |
| ✅ 首启对话框 UI dump（5730，IME 后） | `research/env/phone3_shots/B5730_d2.xml` | bounds 已跳变，对话框上移 335px |
| ❌ 官方 UI（5802） | **无文件** | `screencap` SIGABRT，见 §7.2 |
| ❌ 首启对话框（5802） | **无文件** | 同上；`PC5802_dialog1.xml` 只有 1980 字节的 launcher 树 |
| 原始日志 | `research/env/phone3_shots/PC5802_logcat.txt`（154 KB）、`research/env/logs/phoneC_run4.log` | |

---

## 8. 收尾确认

* **保留运行**：本轮自建的 `OC_Phone_Test_C` / `emulator-5802` / 端口 **5802** 保持运行（进程 21936，19:04:18 起）。
  ⚠️ **但必须说明：它的状态是"开机但不可用"** —— `system_server` 重启循环 + 显示子系统崩溃
  （收尾时 `wm size` 已返回 `Can't find service: window`）。
  **本轮真正可用的手机设备是 E2 的 `emulator-5730`**（自建、WebView 83、能跑完整个官方 UI 流程），
  它也保持运行。下一轮若要验官方 UI，**优先在 5730 上换 WebView（而不是在 5802 上重试）**。
* **`wm size` / `wm density` 全程未覆写**，收尾复核：

  ```
  emulator-5730 : Physical size: 1080x1920  |  Physical density: 420   （无 Override 行）
  ```

  5730 全程使用设备原生值，**无覆写、无需改回**。5802 未执行过任何 `wm` 写操作
  （最后一次能读到时为 `Physical size: 1080x2400 | Physical density: 420`，同样无 Override）。
* **「我碰过哪些 serial」清单** —— 本轮下发过命令的 serial **仅**两个，**都是自建**：

  | serial | 归属 | 本轮对它做过什么 |
  |---|---|---|
  | **`emulator-5802`** | **本轮自建（OC_Phone_Test_C）** | install / reverse / settings put / pm clear / am start / input / screencap / uiautomator dump / logcat / **`emu kill`（仅此一次，因 §4 图形栈问题重启用 host GPU）** |
  | **`emulator-5730`** | **E2 自建（OC_Phone_Test_B）** | install / reverse / settings put / force-stop / monkey / input / screencap / uiautomator dump / logcat |

  对 `emulator-5636`（平板机）、`5670 / 5742 / 5800 / 5860`（他人实例）——
  **零 install、零 force-stop、零 shell 写入、零 kill、零 pm clear**。
  同样**主动放弃**了 `adb kill-server`、`adb reconnect offline`、`adb -a` 这类会扰动全宿主机连接的操作。
  唯一一次设备级销毁操作 `emu kill` 的目标串是 `-s emulator-5802`（自建）。

---

## 9. 未闭环（哪些没测到、为什么、已试过哪几种办法）

| # | 未闭环项 | 原因 | 已试过的办法（全部真实执行过） |
|---|---|---|---|
| 1 | **官方 UI 在手机小屏的渲染 / 侧栏响应式断点（Q1）** | 5730 = WebView 83 解析不了现代 ESM；5802 = `system_server` 重启循环 | ①`android-30` 手机镜像（5730，WebView 83）走完 web 壳首启流程 → 纯白；②`android-37.2 ps16k` 手机镜像（5802，WebView 149）三次冷启 → app 无法启动 |
| 2 | **Q2 官方 UI composer 在 IME 下是否被遮挡** | 同 #1，官方 UI 从未渲染出来 | 同 #1 |
| 3 | **Q3 官方 UI 内元素的 48dp 靶区** | 同 #1 | 同 #1（另：5730 原生壳已测，见 §6.2） |
| 4 | **Q4 官方 UI 横向溢出** | 同 #1 | 同 #1（另：5730 原生壳无溢出，见 §7.4） |
| 5 | **5802 上的截图证据** | 该镜像 `screencap` 必崩（`Fatal signal 6`），两种 GPU 模式都一样 | ①`swiftshader_indirect` ×2 次；②`host` ×1 次；③`adb pull` / `exec-out` 均无文件 |
| 6 | **`dumpsys window insets` 的 `mInputShown` / `type=ime frame=` 原始行** | API 30 上本轮 grep 不到（E2 能抄到） | 用了 `Select-String 'mInputShown\|mShowRequested\|mIsImeShowing\|ITYPE_IME\|type=ime\|frame='` 六种模式；Q2 判定改用「对话框位移 335px」这一实测事实兜底 |
| 7 | `opencode-shell-debug.apk`（原生壳）未安装 | 本轮时间全部押在 web 路线 A | — |
| 8 | `show_ime_with_hard_keyboard=1` | API 30 读回空（与 E2 一致）；**API 37 上因 `settings` 服务随 `system_server` 重启而不稳定** | 已分别尝试 |

### 9.1 下一轮最短路径（按可行性排序）

1. **宿主机**：先确认 `system_server` 稳定。当前 7 台并发模拟器是主要嫌疑，但**本轮无 `system_server` 崩溃栈**（只看到 `AIBinder_linkToDeath` 警告），无法坐实争用。
2. **镜像**：本机已装的 4 个镜像里，**只有 `android-37.2 ps16k` 自带 WebView 149 能渲染官方 UI**。若它在本机始终 `system_server` 不稳，**唯一出路是装一个更新的手机镜像**（本轮被红线禁止下载）。
3. **变通**：若拿不到 WebView ≥ 120 的手机镜像，可考虑**用 `oc_debug_eval` + CDP 直接取官方 UI 的 DOM 尺寸**，在不依赖渲染的情况下回答 Q1/Q3/Q4（脚本已备：`research/env/logs/phoneC_run3.ps1` 里的 `oc_debug_eval` 段落；APK 是 debug 构建，通道可用，但需设备能启动 app）。
4. **纯真机**：`MCRemote_Phone`（用户自己的 AVD，用的就是这个镜像且**能正常跑**）是最省事的对照机 —— 它证明**该镜像本身是好的**，问题出在**本轮自建实例的宿主机环境**。
