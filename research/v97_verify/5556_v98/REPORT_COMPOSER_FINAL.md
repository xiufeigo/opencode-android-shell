# REPORT_COMPOSER_FINAL —— v98 收口终验：统一口径复测 + 队列几何补测

- 终验员：mavis（真机终验，用 mcode / adb 执行）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 口径来源：`research/v97_fix/TRIPWIRE_CRITERION_FIX.md`（Lead 裁定）
- 证据目录：`research/v97_verify/5556_v98/u1m/`

---

## 0. 总状态：**BLOCKED（主判据 / 队列几何未测）** ｜ 构建 ✅ ｜ gateway ✅ ｜ 缩窗往返 ✅ ｜ 收尾 ✅

| 任务 | 状态 | 一句话 |
|---|---|---|
| 前置 1 构建核对 | ✅ **PASS** | 装机后 md5 **逐位等于** `940476cd…` |
| 前置 2 设备归属 | ✅ **PASS** | `Medium_Desktop` @ `1366x768@160dpi`（串号 5554≠5556，见 §1.2） |
| 前置 3 后端 gateway | ✅ **PASS** | 主机 200 + 反向隧道**穿透实测**通过 |
| **1. 主判据（两档 ①/②）** | ⛔ **未测** | **拿不到会话 ⇒ 屏幕上没有 composer ⇒ 无法产生任何 ①/② 数值** |
| **2. 队列几何（43/48/clickable）** | ⛔ **未测** | 依赖"回合在跑"，同样卡在会话 |
| **3. 缩窗往返 2 轮** | ✅ **PASS** | 4 次切档 pid 不变、focus 始终本 app、`FATAL\|IllegalArgumentException\|maxHeight` **全空** |
| 4. 收尾 | ✅ **PASS** | `wm` 已复原、`/sdcard` 无残留、`reverse --list` 与进场一致、**未建任何会话** |

> **本轮最重要的两个发现（都不是 composer 的锅）**：
> 1. 🔴 **有外部进程在设备上对本 app 下 `am force-stop`**（`from pid 5390`），
>    先后杀掉 pid `4022`、`5306` ⇒ **共享设备被并发占用**。
> 2. 🔴 **app 连不上自己的后端**（`Not connected to a server`），会话创建必失败。
> 两者叠加 ⇒ 无法进入会话态，**composer 几何无从测量**。

---

## 1. 前置核对

### 1.1 前置 1：构建 —— ✅ PASS（中途曾 BLOCKED，Lead 重装后转为 PASS）

#### 阶段 A：22:5x 首次核对 —— 🔴 不通过（当时判 BLOCKED）

| | md5 | 大小 |
|---|---|---|
| 仓库 `app-debug.apk` | `940476CD6CCA62F541449A1F6AEA994E` | 44,198,253 |
| 设备 `base.apk`（当时） | `CA90E58722F34CB8D9F15901ECA36D02` | 44,198,253 |

- 设备 `dumpsys package` 真实值（**原文**）：
  `lastUpdateTime=2026-10-03 13:54:25` / `firstInstallTime=2026-09-23 13:58:18` /
  `versionName=1.4.0` / `versionCode=26` / `targetSdk=35` / `DEBUGGABLE HAS_CODE`。
- 逐条目比对（解压后 SHA-256）：**1933/1933 条目同名同序，唯一内容差异 = `classes7.dex`**
  （repo `1558224` vs inst `1558220`，**差 4 字节**）；`classes.dex`/`classes2.dex`/`classes3.dex`/
  `AndroidManifest.xml`/`resources.arsc` **全部逐位相同**。
- dex 符号扫描：两边的 `classes7.dex` **都**含
  `SessionPaneLayout` / `believedImeBottomPx` / `composerTopPx` / `composerChildUnionHeightPx`
  ⇒ **差异恰好落在被测几何代码所在的那个 dex 里**。
- ⇒ 当时判定「不是指定包」，已按指令 **BLOCKED 回报，未自行装机**。

#### 阶段 B：Lead 重装后复核 —— ✅ **PASS**

轮询发现 Lead 在期间重装。再次拉取核对：

```
RECHECK MD5 = 940476CD6CCA62F541449A1F6AEA994E
EXPECT      = 940476CD6CCA62F541449A1F6AEA994E     ✅ 逐位一致
lastUpdateTime=2026-10-03 14:46:48                 （已从 13:54 变为 14:46）
```

**⇒ 自 14:46:48 起，前置 1 成立，测试继续。**（两次 md5/时间均已抄进本报告。）

### 1.2 前置 2：设备归属 —— ✅ PASS（含一处串号偏差，已放行并记账）

- `emulator-5556` **本轮始终不存在**。Lead 约 22:5x 启动
  `qemu-system-x86_64.exe -avd Medium_Desktop -no-snapshot-save -no-boot-anim`
  —— **未带 `-port`**，故落在默认口 **`emulator-5554`**。
- 身份逐项核对：`emu avd name` = **`Medium_Desktop`** ✅；
  `wm size` = `Physical 3840x2160 / **Override 1366x768**` ✅；
  `wm density` = `Physical 320 / **Override 160**` ✅ —— **与简报指定设备完全一致**。
- ⇒ 判定为**端口分配差异，非设备差异**，放行；全程以 `emulator-5554` 操作。
- **`emulator-5590`（`DSH_Class_Test`/Pixel_Tablet）自始至终未交互。**
- **每步交互前均已确认 `mCurrentFocus` 属本 app**
  （实测多轮均为 `com.xiufeigo.opencodeshell/.OcActivity`）；唯一一次非本 app 焦点
  （`org.chromium.home.pc`）已按纪律**立即停手**并只做只读诊断（§1.4）。

### 1.3 前置 3：后端 gateway —— ✅ PASS（上一轮就是死在这条，本轮单独测掉）

1. `reverse --list` 进场即含 `tcp:4095 tcp:4095`（另有 `tcp:2222 tcp:22`），本轮**未增删**。
2. 主机侧 `GET /api/info` + Basic `opencode:opencode` → **`HOST HTTP=200`**
   （返回 OpenCode SPA 兜底 HTML，非 JSON —— 如实记账：它证明**进程在监听并接受请求**，
   但不是该端点的 API 语义证明）。
3. **隧道穿透实测**（设备无 `curl`/`wget`，`toybox nc` 静默，故做隔离实验把「nc 不出声」与
   「隧道坏了」分开）：主机临时监听 `4999` + 临时 `adb reverse tcp:4999` + 设备 `nc` ⇒
   **`TUNNEL_ACCEPTED=True`**，随后 `--remove` 撤销。
   ⇒ **`adb reverse` 转发器在这台新 AVD 上工作正常**；先前设备侧无输出是工具限制，非隧道故障。
4. 补充情报：主机 `opencode` 进程同时监听 **`127.0.0.1:4095`** 与 **`0.0.0.0:4096`**
   （后者可经 `10.0.2.2:4096` 到达，无需 reverse）。

### 1.4 🔴 阻碍测量的两个真因（**都不是 composer 修复的问题**）

#### 真因 A：app 连不上自己的后端 ⇒ 会话创建必失败

UI 原文（`uiautomator dump` 抄录）：

```
TextView 'Failed to create session'   [609,457][757,473]
TextView 'Not connected to a server'  [610,485][757,502]
```
首页会话区同样报 `Couldn't load sessions` + `Retry`。

排查结论（**含一处自我更正**）：
- 主机 gateway **活着**（§1.3：`GET /api/info` → 200），反向隧道**能穿透**（§1.3：`TUNNEL_ACCEPTED=True`）。
- 设备镜像**无 `curl`/`wget`**，设备侧 `/api/info` 无法用 HTTP 客户端复现
  （`toybox nc` 的 `exit` 码不可信：对空端口 `59999` 竟返回 `124`、对在听的 `4095` 返回 `0`
  ⇒ **控制组反转，该组测试作废、不采信**）。
- app 侧配置在 `shared_prefs/oc_server_config.xml`，内容为
  **androidx security crypto 密文**（`__androidx_security_crypto_encrypted_prefs_*`），无法直接读出。

#### 🔍 更深一层：端点其实**配对了**，但 app 仍判定不可达

经命令面板（`Open settings` → `Servers`）打开 Servers 面板，dump 原文：

```
TextView '127.0.0.1:4095'                            [514,253][599,269]
TextView 'opencode'                                   [514,271][567,285]
TextView 'Default'                                    [1033,262][1071,276]
View     desc='Server not reachable'                  [494,265][502,273]
View     desc='Server actions'  clickable=true        [1075,245][1123,293]
```

**⇒ app 配的端点正是 `127.0.0.1:4095`（正确）、且已是 `Default`，
但状态是 `Server not reachable`。**
（此更正很重要：先前"app 根本没配端点、UI 无处可改"的判断**不成立** ——
`Servers` 面板**存在且可改**（`Server actions` → `Edit` / `Set as default` / `Delete`），
只是本轮未能用它把连接救活。）

已尝试且**无效**的补救：
1. 首页 `Retry`（`Couldn't load sessions`）——无效。
2. 两次从项目条目 `New session` → `Create session` —— 均失败，报
   `Failed to create session` / `Not connected to a server`。
3. `Server actions` → **`Set as default`**（强制重连/重探测）—— 之后状态**仍是**
   `Server not reachable`，**无变化**。
4. app-pid 过滤的 logcat 中**完全没有任何** `connect/http/reach/401/403/timeout/refused` 输出
   ⇒ app 侧对这次不可达**既没有尝试痕迹也没有报错**，属静默失败。

**⇒ 结论：app → `http://127.0.0.1:4095` 这条链路上存在一处本轮无法定位的断点。**
在主机侧（200）与隧道侧（穿透 OK）都已证实的前提下，**最可能落在 app 的连通性探测本身
或它在模拟器上对 `127.0.0.1` 的解析/建连策略**上 —— 这需要工程侧查
`SettingsServerApi` / `SettingsServerBridge` / `ServerConfigStore` 的探测实现，
**不是终端侧能靠点按解决的**。

#### 🔬 再深一层：`Edit server` 里的地址**完全正确**（只读查看，未做任何修改）

`Server actions` → `Edit` 弹窗 dump 原文：

```
TextView 'Edit server'                              [458,365][535,384]
TextView 'Server address'                           [458,409][553,425]
EditText 'http://127.0.0.1:4095'   [470,432][878,480]   ← 地址、带 scheme，完全正确
TextView 'Server name (optional)'                   [458,485][600,501]
EditText '127.0.0.1:4095'          [470,508][878,556]
TextView 'Cancel' / TextView 'Save'                 [767,575]… [846,575]…
```

**本轮点的是 `Cancel`（788,583），未改任何值**；复核
`oc_server_config.xml` 仍为 **7 条**密文条目，与进场一致。

> ### ⇒ 这**推翻了上一轮"没装 gateway"的根因判断**
>
> | 环节 | 上一轮的结论 | 本轮实测 |
> |---|---|---|
> | 主机 gateway 在不在 | 缺（"新起的 AVD 没 gateway"） | ✅ **在**，`GET /api/info` = **200** |
> | 反向隧道通不通 | 缺 | ✅ **通**，`TUNNEL_ACCEPTED=True` 穿透实测 |
> | app 端点配没配对 | 未知 | ✅ **`http://127.0.0.1:4095`，完全正确** |
> | app 能不能连上 | 未知 | ❌ **`Server not reachable`，且 logcat 零痕迹** |
>
> **⇒ 缺的不是 gateway，是 app 自己那条探测/建连路径。**
> 这是**与上一轮完全不同的根因**，请勿再按"gateway 没起"排查。

#### 真因 B：设备上有外部进程对本 app 下 `am force-stop`（共享设备并发占用）

```
14:55:43.266  540 1621 I ActivityManager: Force stopping com.xiufeigo.opencodeshell
                                appid=10189 user=0: from pid 5390
14:55:43.290  357  357 I Zygote  : Process 5306 exited due to signal 9 (Killed)
```

- 另有 `Process 4022 exited due to signal 9`，`ActivityTaskManager: Force removing
  ActivityRecord{...OcActivity...}: app died, no saved state` ×2。
- 全部是 **signal 9（SIGKILL）**、**无本 app 的 `FATAL`、无 ANR、无 native tombstone**
  ⇒ **不是 app 崩溃，是被外部杀掉**。
- 排除误判：`logcat -b crash` 里那条
  `FATAL EXCEPTION: main / UiAutomationService ... already registered!` 属 **pid 4908**，
  那是 **`uiautomator dump` 自己的进程**，**与本 app 无关**。
- ⇒ **同一台 AVD 上有并发操作者**。仅此一条就足以让任何交互式验证中途夭折。

---

## 2. 主判据（1366x768 / 1366x600）—— ⛔ **未测，一项数值都没产生**

要求逐档给出 `composer 宿主盒 bounds / 子节点并集(排除 Open terminal) / band 高 / unionPx /
paneBottom(i)(ii) / unionBottom−paneBottom / unionPx−band / mInputShown / readable`。
**本轮全部为空 —— 不是"测了不通过"，是"屏幕上不存在 composer 可测"。**

原因链：`无法建会话`（真因 A）＋ `app 被反复 force-stop`（真因 B）⇒ 始终停在
`New session` / 项目列表页，从未进入带 composer 的会话页。

> **明确不采信的写法**：本轮**没有**用旧口径 `249 ≤ 261 = −12` 顶账，
> 也**没有**拿任何容器底沿充当 `paneBottom`。统一口径要求两组读数
> （(i) dump 侧容器 bounds、(ii) 按生产口径换算的 `paneBottom`，差 ≈48px 带高），
> **本轮两组都给不出**。
>
> **期望值（供下一轮对照，本轮未验证）**：`768: readable 92 / 时间线盒 140`；`600: readable 51`。

---

## 3. 补测：队列几何（基线 43 / 48 / 全 clickable）—— ⛔ **未测**

依赖链：**① 会话 —— 🔴 断**；② 后端在线 —— ✅ 已证（§1.3）；③ 回合在跑 —— 因 ① 未发生。
连 chip 行都没机会点（连 composer 都没有）。

**⇒ 本轮既不报「测到 43/48/全 clickable」，也不报「容器没渲染」。**
后者需要先在 chip 行、⋮ 菜单、全树 grep 上取证，而**连 composer 都没渲染出来**，
在此之前报"没渲染"是**无根据的推论**，不采信。

---

## 4. 缩窗往返 2 轮 —— ✅ **PASS**

每档记录 `pidof` / `mCurrentFocus` / `logcat --pid`（按 app-pid 过滤）的
`FATAL|IllegalArgumentException|maxHeight`：

| 步骤 | `wm size` | pid | `mCurrentFocus` | `FATAL\|IllegalArgument\|maxHeight` |
|---|---|---|---|---|
| 基线 | `1366x768` | `5542` | 本 app `OcActivity` | **EMPTY** |
| R1 →600 | `1366x600` | `5542` | 本 app `OcActivity` | **EMPTY** |
| R1 →768 | `1366x768` | `5542` | 本 app `OcActivity` | **EMPTY** |
| R2 →600 | `1366x600` | `5542` | 本 app `OcActivity` | **EMPTY** |
| R2 →768 | `1366x768` | `5542` | 本 app `OcActivity` | **EMPTY** |

- **pid 全程恒为 `5542`** ⇒ 缩窗**未杀死**进程。
- **焦点全程属本 app** ⇒ 未在非本 app 窗口下操作。
- **5 档全空** ⇒ 无 `FATAL`、无 `IllegalArgumentException`、**无 `maxHeight` 越界日志**。
- 期间系统日志里成批 `Process NNNN exited due to signal 9` 属**其它 pid**
  （如 `2553/2771/2528/…`），**不含 `5542`** ⇒ 与本 app 无关。

---

## 5. 收尾 —— ✅ 全部达标

| 项 | 状态 | 证据 |
|---|---|---|
| `wm` 显式重设回 1366x768/160 | ✅ | `Override size: 1366x768` / `Override density: 160`（**未用 `wm size reset`**，用显式 `wm size 1366x768` + `wm density 160`） |
| 自建会话清理 | ✅ **无需动作** | **本轮未成功创建任何会话**（前缀 `E2ECHK5556u1m` 零条）；app data 下无会话产物 |
| `/sdcard` 残留 | ✅ **本轮零残留** | `ls /sdcard | grep u1m` → `NO_u1m_RESIDUE`（所有 dump/screencap/探针文件均已 `rm`） |
| `reverse --list` 复核 | ✅ | 终态 = 进场态 2 条：`tcp:4095 tcp:4095`、`tcp:2222 tcp:22`；诊断用的 `tcp:4999` **已撤销** |
| gradle / 装机 / 杀进程 | ✅ **一次都没有** | 仅 `adb` 只读命令 + `adb pull`；唯一写操作是允许范围内的 `wm` 与临时 reverse（已复原） |
| `am force-stop` 本 app | ✅ 未执行 | 本轮**未**对本 app 下过 force-stop（观察到的 force-stop 来自设备上 `pid 5390`） |
| 另一台设备 / 5555 | ✅ 未交互 | `emulator-5590` 全程未碰；`127.0.0.1:5555` 未连接 |
| 主机临时文件 | ✅ 已清理 | 两次 44MB 装机包副本已删（第二次走可恢复删除 `mavis-trash`） |
| 上一轮遗留 | ⚠️ **未动** | 设备上有 `/sdcard/e2echk5556v98`（属**上一轮**前缀，非本轮产物）。**未擅自删除** —— 可能是其它报告引用的证据，请 Lead 裁定 |

---

## 6. 未闭环（给 Lead，按优先级）

1. 🔴 **先清掉共享设备并发占用**：查明设备上 **`pid 5390`** 是谁（哪个 agent / 哪条流水线），
   为何对本 app 下 `am force-stop`。**只要它还在，任何交互式验证都会中途被杀。**
2. 🔴 **查 app → `127.0.0.1:4095` 的连通性断点（工程侧，非终端侧）**。
   已证：主机 `GET /api/info` = **200**、反向隧道**穿透成功**、
   app 侧端点**就是 `127.0.0.1:4095` 且已是 `Default`**。
   但 app 持续判定 **`Server not reachable`**，且 app-pid logcat 里
   **没有任何** connect/http/reach/401/403/timeout/refused 记录 ⇒ **静默失败**。
   建议查 `SettingsServerApi` / `SettingsServerBridge` / `ServerConfigStore` 的探测实现，
   以及它在模拟器上对 `127.0.0.1` 的解析/建连策略。
   （`Servers` 面板**存在且可改**：`Open settings` → `Servers` → `Server actions` → `Edit`；
   本轮试过 `Set as default` 强制重连，**状态无变化**。凭据/端点不是缺，是"配对了却连不上"。）
3. 🔴 **重派主判据 + 队列几何**。本轮**没有**产生任何 ①/② 数值，
   既不能判 PASS 也不能判 FAIL —— **不要**把本轮当作"修复未生效"的证据。
4. ⚠️ **串号**：若要严格按 5556，请 `emulator -avd Medium_Desktop -port 5556 …`；
   否则请把简报里的 `emulator-5556` 改成 `emulator-5554`，避免下轮再对不上。
5. ⚠️ `/sdcard/e2echk5556v98` 遗留目录的归属与清理，请裁定。
6. ℹ️ `GET /api/info` 返回的是 SPA HTML 兜底页。若要以它作"后端可用"凭据，建议改打真实 API 端点。

---

## 7. 证据索引

| 证据 | 复现命令 / 位置 |
|---|---|
| 本地 md5 | `Get-FileHash app\build\outputs\apk\debug\app-debug.apk -Algorithm MD5` |
| 设备包 md5（阶段 A/B） | `adb shell pm path …` → `adb pull` → `Get-FileHash` |
| 逐条目比对 | `System.IO.Compression.ZipFile` 逐 entry 解压后 SHA-256 |
| dex 符号扫描 | 同上，ASCII 串表命中定位承载 `SessionPaneLayout` 的 `classes7.dex` |
| 设备身份 | `adb -s emulator-5554 emu avd name` / `wm size` / `wm density` |
| 串号成因 | `Get-CimInstance Win32_Process -Filter "Name like '%qemu%'"` |
| gateway 主机侧 | `Invoke-WebRequest http://127.0.0.1:4095/api/info`（Basic `opencode:opencode`） |
| gateway 隧道 | 主机 `TcpListener:4999` + 临时 `adb reverse` + 设备 `nc` → `TUNNEL_ACCEPTED=True` |
| 主机端口情报 | `Get-NetTCPConnection -State Listen`（`opencode` = `127.0.0.1:4095` + `0.0.0.0:4096`） |
| 外部 kill | `logcat` → `ActivityManager: Force stopping … from pid 5390` / `Zygote: Process 5306 exited due to signal 9` |
| 会话失败 UI | `u1m/home.xml`、`u1m/sess.xml`、`u1m/sess3.xml`（dump 原文抄进 §1.4） |
| 缩窗往返 | 本文件 §4 表（pid `5542` 全程恒定） |
| 报告 | 本文件 |
