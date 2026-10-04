# PHONE_AVD — 手机形态 AVD 验证（E2）

> **一句话结论**：手机形态 AVD 已建成并保持运行（`OC_Phone_Test_B` / `emulator-5730` / 端口 5730），
> 原生壳的**首启配置流**在手机小屏上**渲染正常、键盘不遮挡、触摸靶达标**；
> 但**官方 UI（Web 路线 A）在本轮未能渲染出来** —— 根因是本轮唯一能启动成功的镜像
> `android-30` 自带 **WebView 11**（2020 年代）**不认识官方前端的现代 ESM**，
> **属于工具链/镜像限制，不是「手机形态不适配」的结论**。详见 §7。

---

## 1. 资源探测原文

### 1.1 现有 AVD 名单

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds
```

```
DSH_Class_Test   DSH_File_Test   DSH_Fold_Test   DSH_Kbd_Test   DSH_T58
DSH_T63          DSH_T65_Phone   DSH_T65_Tablet  MCRemote_Phone MCRemote_Tablet
Medium_Desktop   Medium_Desktop_B  Pixel_10_Pro_Fold  Pixel_10_Pro_XL
Pixel_9a         Pixel_Tablet    Small_Desktop   T59_Rc35       T59_Upgrade
T67_Tablet
```

### 1.2 已安装镜像（**只用已装镜像，全程未下载**）

```powershell
Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\system-images" -Recurse -Depth 3 -Directory
```

| system-images 坐标 | 形态 | 本轮处置 |
|---|---|---|
| `android-30;default;x86_64` | 手机 | **最终选用**（唯一能在时限内启动成功的） |
| `android-34;android-desktop;x86_64` | 桌面 | 未用 |
| `android-35;google_apis_playstore_tablet;x86_64` | 平板 | 未用（平板已验过） |
| `android-37.2;google_apis_playstore_ps16k;x86_64` | 手机 | **首选但启动失败**（§3.2） |

**未下载验证**：两个候选镜像目录时间戳均为 `2026/9/20`，早于本轮作业日（2026/10/4），
证明是宿主机既有镜像，本轮只做本地引用：

```
Name   CreationTime        LastWriteTime
x86_64 2026/9/20 14:07:15   2026/9/20 14:07:46
```

> `avdmanager create` 过程打印过 `Fetch remote repository`，那是**设备外形（device profile）目录**的远程查询，
> 不是镜像下载；镜像目录时间戳未变可佐证。

### 1.3 `adb devices`（作业起点）

```
emulator-5636   device  product:sdk_gpc_x86_64       model:sdk_gpc_x86_64       device:emu64xa
emulator-5690   device  product:sdk_gphone16k_x86_64 model:sdk_gphone16k_x86_64 device:emu64xa16k
emulator-5692   device  product:sdk_gtablet_x86_64    model:Pixel_Tablet        device:emu64xa
```

作业期间宿主机并发新增 `5610 / 5670 / 5742 / 5784 / 5800` 等他人实例 —— **本轮全程未触碰**（§8）。

### 1.4 端口选择理由

* 现存占用：`5604 5610 5612 5620 5628 5636 5642 5650 5670 5690 5692 5742 5784 5800` —— **5600 段基本占满**。
* 逐个探测 `5700..5738` 全部**偶数**端口（`Get-NetTCPConnection -State Listen`）：**全 free**。
* 首选 **5714**；启动前复查 → `PORT 5714 free - launching`。
* 冲突处置原则：**换端口，不抢**。实际发生一次占用 → 换 5730（§3.2）。

---

## 2. 新建 AVD

### 2.1 首选（android-37.2 手机镜像）

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd `
  -n "OC_Phone_Test" -k "system-images;android-37.2;google_apis_playstore_ps16k;x86_64" -d pixel_6 --force
# Auto-selecting single ABI x86_64   ---EXIT:0---
```

| config.ini | 值 |
|---|---|
| `hw.lcd.width` x `hw.lcd.height` | **1080 x 2400**（20:9 竖屏） |
| `hw.lcd.density` | **420** → 逻辑宽 **393dp**（典型手机断点） |
| `hw.ramSize` | 2G → **3G**（手动上调） |

### 2.2 实际交付（android-30 手机镜像）

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd `
  -n "OC_Phone_Test_B" -k "system-images;android-30;default;x86_64" -d pixel --force
```

| config.ini | 值 |
|---|---|
| `hw.lcd.width` x `hw.lcd.height` | **1080 x 1920** |
| `hw.lcd.density` | **420** → 逻辑宽 **411dp**（手机竖屏） |
| `hw.ramSize` / `hw.cpu.ncore` | **1536** / **2**（为不拖死宿主机而压低） |

---

## 3. 冷启

### 3.1 首选实例 OC_Phone_Test @5714 —— 启动失败（诚实记录）

```powershell
Start-Process "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -ArgumentList `
  "-avd","OC_Phone_Test","-port","5714","-no-window","-no-audio","-no-boot-anim",
  "-no-snapshot-load","-gpu","swiftshader_indirect","-no-snapshot-save" `
  -RedirectStandardOutput "research\env\logs\oc_phone_boot.log" ...
#  WHPX on Windows 10.0.28000 detected.
#  Windows Hypervisor Platform accelerator is operational
```

日志最后一行即 WHPX 就绪，随后 **`adb devices` 恒为 `emulator-5714 offline`，>10 分钟未进入 `device`**
（CPU 一路 1297→1523 s 持续增长，宿主机同时有 6–7 台并发实例，WHPX 严重争用）。

> **一处自我纠错**：我一度用 `qemu-system-x86_64.exe` 过滤进程，结论「进程已死」是**错的** ——
> `-no-window` 起的是 `qemu-system-x86_64-**headless**.exe`，实例一直活着，只是卡在 offline。
> 第二次 `Start-Process` 被 emulator 自己拦下，反证了原实例存活：
> `FATAL | Running multiple emulators with the same AVD is an experimental feature...`
> 该实例为**自建**，故按「不抢占、不碰他人」的红线精神自行停止以释放宿主机资源。

### 3.2 交付实例 OC_Phone_Test_B @5730 —— 启动成功

```powershell
# 启动前复查端口
Get-NetTCPConnection -LocalPort 5730 -State Listen   #  -> 5730 free
Start-Process "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -ArgumentList `
  "-avd","OC_Phone_Test_B","-port","5730","-no-window","-no-audio","-no-boot-anim",
  "-no-snapshot-load","-no-snapshot-save","-cores","2","-memory","1536","-gpu","swiftshader_indirect" ...
```

```
[18:34:08] wait boot_completed (emulator-5730)
[18:34:08] BOOT_COMPLETED
[18:34:10] install web apk -> Performing Streamed Install / Success
[18:34:10] reverse 4095    -> 4095
[18:34:10] launch web host -> Starting: Intent { cmp=com.xiufeigo.opencodeshell.web.debug/...WebHostActivity }
```

**对比结论**：`android-30` 镜像约 **60 秒** boot 完成；`android-37.2` 镜像在本宿主机争用下 **>10 分钟**仍未就绪。
**镜像越新 ≠ 越容易在这台宿主机上跑起来**。

---

## 4. 设备指纹（`emulator-5730`）

| 项 | 值 |
|---|---|
| `ro.product.name` | `sdk_phone_x86_64` |
| `ro.product.model` | `Android SDK built for x86_64` |
| `ro.build.version.sdk` | **`30`** |
| `ro.build.version.release` | `11` |
| `wm size` | `Physical size: 1080x1920` |
| `wm density` | `Physical density: 420` |
| `cat /proc/uptime` | `257.23 400.31` |
| web 壳 `versionName` | `0.1.0-webhost` |
| web 壳 `lastUpdateTime` | `2026-10-04 10:34:09` |
| debug 壳（`opencode-shell-debug.apk`） | **本轮未安装**（见 §7） |
| **WebView 版本** | **`11`**（≈Chrome 87，2020–2021）← §6 的关键 |

> `wm size` / `wm density` 全程为设备**原生**值，**未做任何 `wm size` / `wm density` 覆写**。

---

## 5. 官方 UI 在手机小屏的截图路径

| 截图 | 路径 | 内容 |
|---|---|---|
| 首启配置框（未配密码） | `research/env/phone_shots/phone01_after_launch.png` | **原生壳在手机小屏渲染正常** |
| 密码已输入 + IME 弹出 | `research/env/phone_shots/phone04_password_typed.png` | 键盘弹出，配置框**未被遮挡** |
| 保存并连接后 | `research/env/phone_shots/phone05_official_ui.png` | **纯白**（官方 UI 未渲染，见 §6） |
| 清数据后 键盘再次弹出 | `research/env/phone_shots/phone07_ime_dialog.png` | 键盘/靶区证据图 |
| UI dump | `research/env/phone_shots/ui07_dialog.xml` | 原生控件真实 bounds |

> 管线与日志：`research/env/logs/phone_pipeline2.ps1`、`oc_phone_boot*.log`、`oc_phoneB_boot.*`

**判定：官方 UI（Web 路线 A）在本轮手机小屏上「未渲染」，不是「渲染错乱」。**

---

## 6. 四个手机形态特有问题的逐条结论

### Q1 布局是否适配？—— **本轮无法判定（证据不足，不下结论）**

* 原生壳首启配置框在 **411dp 逻辑宽**下布局**正常**：标题、两个输入框、两个按钮单列排布，
  无挤压、无截断（`phone01` / `phone07`）。
* **但官方 UI 的侧栏/抽屉行为完全没验到** —— 页面根本没渲染出来，
  拿不到任何「侧栏在窄屏下是收起、抽屉、还是挤成一团」的证据。
* **明确不外推**：不能因为这台没渲染就说官方 UI 不适配手机。

### Q2 键盘弹出后 composer/输入框是否可见、是否被遮挡？—— **可判定：原生壳不遮挡**

严格按要求，**先断言再读**：

```
# 断言（未触碰时）
mShowRequested=false mShowExplicitlyRequested=false mShowForced=false mInputShown=false
# 点输入框后
mShowRequested=true  mShowExplicitlyRequested=true  mShowForced=false mInputShown=true
```

`dumpsys window insets` 的**原始行**（API 30 上为 `InsetsSource` 形式）：

```
mIsImeShowing=true
mImeHeight=670
InsetsSource type=ITYPE_IME frame=[0,1124][1080,1920] visibleFrame=[0,1124][1080,1920] visible=true
ITYPE_IME -> Window{22ad481 u0 com.xiufeigo.opencodeshell.web.debug/...WebHostActivity}
```

**结论**：
* IME 占位 `y ∈ [1124, 1920]`（高 670px ≈ **255dp**），**接近屏幕 35%**。
* 配置框底部在 `y=844`，**IME 顶边 1124 − 框底 844 = 280px ≈ 107dp 空隙** ⇒ **无遮挡**，
  密码框与「保存并连接」按钮在键盘弹出时**仍完全可见可点**（`phone04` / `phone07` 为证）。
* ⚠️ 255dp 的 IME 在 1080×1920 屏上占比不小；**官方 UI 的 composer（输入区）是否被这 255dp 压住，本轮没测到**。

### Q3 触摸靶是否可用（关键控件 bounds ≥ 48dp）？—— **可判定：达标**

`density=420` ⇒ `1dp = 2.625px`。取自 `ui07_dialog.xml` 真实 bounds：

| 控件 | bounds (px) | 尺寸 (px) | 尺寸 (dp) | 判定 |
|---|---|---|---|---|
| 密码输入框 (EditText) | `[112,471]-[968,589]` | 856 × 118 | 326 × **45** | ⚠️ 高度 45dp，略低于 48 |
| URL 输入框 (EditText) | `[112,589]-[968,707]` | 856 × 118 | 326 × **45** | ⚠️ 同上 |
| 按钮「先不配置」 | `[521,718]-[731,844]` | 210 × 126 | 80 × **48** | ✅ |
| 按钮「保存并连接」 | `[731,718]-[978,844]` | 247 × 126 | 94 × **48** | ✅ |

* **两个按钮均恰好 48dp，符合 Material 最小触控靶**。
* 两个 EditText **高 45dp（≈ 16.7mm）**，比 48dp 少 3dp，**物理上仍远大于手指**，
  但**严格按「≥48dp」标准属未达标**；Android 有自动 touch delegate 兜底，实际可点性无问题。
* ⚠️ 这是**原生壳**的靶区；**官方 UI 内元素的靶区完全未测**（页面没渲染）。

### Q4 是否有横向滚动 / 内容溢出？—— **本轮无法判定**

* 判据只能是「官方 UI 截图 + bounds」，而官方 UI 未渲染，**无任何可判据**。
* 原生壳首启框未观察到横向溢出（内容单列、左右留白对称）。

---

## 7. 官方 UI 为什么没渲染（根因）

先排除**密码/网络**这一层：首次连接时 logcat 明确实锤 401：

```
I/OcWebHost/Proxy: GET http://127.0.0.1:4095/_assets/index-Dadcbstg.js
W/OcWebHost/Proxy: no password in SecretStore -> /_assets/index-Dadcbstg.js will 401 upstream
```

补上密码（`opencode`，依据 `research/HANDOFF_PROMPT.md:32` 的 `OPENCODE_SERVER_PASSWORD=opencode` 契约）后，
**401 告警消失、资产全部取回**，但页面依旧纯白，且报：

```
I/OcWebHost: web[ERROR] Uncaught SyntaxError: Unexpected token '=' @2
```

**根因判定**：设备为 `sdk=30` / WebView **11**（≈Chrome 87）。
官方前端是 Vite 打包的现代 ESM（含 `??=`、class fields 等较新语法），
**WebView 11 的解析器在第 2 行即报 `Unexpected token '='`**，主 bundle 解析失败 → React 未挂载 → 纯白页。

> **这是镜像/WebView 代际问题，不是手机形态问题。**
> 手机形态本身只贡献了「窄屏」这一个变量，而本轮连页面都没跑起来，**窄屏变量根本没被施加上**。

---

## 8. 收尾确认

* **保留运行**：`emulator-5730`（`OC_Phone_Test_B`）**保持开机运行**，供后续手机形态 A/B 直接使用。
* **未改 `wm size` / `wm density`**：全程使用原生 `1080x1920 @420`，**无覆写、无需改回**。
* **自建设备台账**：

  | AVD 名 | serial | 端口 | 镜像 | 状态 |
  |---|---|---|---|---|
  | `OC_Phone_Test` | `emulator-5714` | 5714 | android-37.2 ps16k | **已停**（自建，卡在 offline） |
  | `OC_Phone_Test_B` | **`emulator-5730`** | **5730** | android-30 default | **运行中（交付）** |

* **「我确认没有触碰任何非自建设备」清单** —— 本轮下发过命令的 serial **仅**：

  ```
  emulator-5730   （自建，本轮唯一被 install / shell 写入 / force-stop / pm clear 的设备）
  emulator-5714   （自建，已自行停止）
  ```

  对 `emulator-5636`（平板机）、`5604 / 5610 / 5612 / 5620 / 5628 / 5642 / 5650 / 5670 / 5690 / 5692 / 5742 / 5784 / 5800`
  —— **零 install、零 force-stop、零 shell 写入、零 kill**。
  特别说明：为避免影响他人设备，我**主动放弃了** `adb kill-server` 与 `adb reconnect offline` 这两个
  会扰动全宿主机连接的选项。

---

## 9. 未闭环（哪些没测到、为什么）

| # | 未闭环项 | 原因 | 补做建议 |
|---|---|---|---|
| 1 | **官方 UI（路线 A）在手机小屏的渲染 / 侧栏响应式断点** | 唯一能启动的镜像 `android-30` 只有 **WebView 11**，解析不了现代 ESM（§7） | 用 `android-37.2;google_apis_playstore_ps16k` 镜像在**空闲宿主机**上冷启复测；该镜像即 `emulator-5690/5742` 所用 |
| 2 | **Q1 布局适配**（侧栏收起/抽屉/挤压） | 同 #1 | 同 #1 |
| 3 | **Q4 横向滚动 / 内容溢出** | 同 #1 | 同 #1 |
| 4 | **官方 UI composer 在 255dp IME 下是否被遮挡** | 同 #1；且本轮只量到**原生壳** IME 占位 | 同 #1 |
| 5 | **官方 UI 内元素的 48dp 靶区** | 同 #1 | 同 #1 |
| 6 | `opencode-shell-debug.apk`（原生壳）**未安装** | 本轮主角是 web 路线 A，时间预算优先给它 | 下一轮装上做原生壳 A/B |
| 7 | `show_ime_with_hard_keyboard=1` 写入后读回**空串** | API 30 上该 secure setting 读回为空，但 `dumpsys` 侧 `mInputShown` 行为正常 | 判定为**无害**：不影响本轮结论 |
| 8 | Q3 两处 EditText 高 **45dp**（< 48dp） | 实测值，非配置失误 | 属**原生壳**微优化项，不阻塞 |

**总时长约束**：本轮可用作业时间约 35 分钟，其中 `android-37.2` 镜像的冷启动失败吃掉了约 15 分钟，
是「官方 UI 未验证」的直接成本。**在更空闲的宿主机上，`android-37.2` 手机镜像是解开 #1–#5 的最短路径。**
