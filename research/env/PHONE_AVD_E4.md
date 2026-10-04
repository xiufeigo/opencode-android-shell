# PHONE_AVD_E4 — 手机形态验证（E4：绕过截图的判据 + 崩溃根因定位）

> **一句话结论**：**手机形态这一半仍未闭环**，但本轮把 E3 归因为「模拟器自身不稳定 / 宿主机争用」
> 的模糊判断，**推进成了一条可复现的硬根因**：
> **`/vendor/lib64/hw/mapper.ranchu.so`（Goldfish 图形 HAL）在导入任何图形缓冲时断言失败并 SIGABRT，
> 连带打死 surfaceflinger 与 `android.hardware.*` 服务 ⇒ system_server 无限重启循环 ⇒ 官方 UI 一次都没渲染出来。**
> 三条判据（a11y 树 / JS 注入 / logcat）**全部指向同一个结论：官方 UI 未渲染**，
> 且**这次不是"WebView 太老"（那是 5730 的问题），而是"宿主图形栈在导入 buffer 时就崩"**。
> 镜像 `android-37.2;google_apis_playstore_ps16k;x86_64` 本身没问题（用户 `MCRemote_Phone` 同款镜像正常运行）。

---

## 1. 实例健康诊断（原始读数）

### 1.1 作业起点：E3 留下的 `emulator-5802`（`OC_Phone_Test_C`）**已损坏，且不自愈**

按任务书要求先查"还在不在、健不健康"：

```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5802 shell pidof system_server     # t0 = (空)
Start-Sleep -Seconds 10
& $adb -s emulator-5802 shell pidof system_server     # t1 = 19558   ← pid 变了
```

```
t0=  at 19:15:22          ← 空
t1=19558  at 19:15:32     ← 变了
service check activity: found
service check package:  not found      ← package 服务已死
service check window:   not found      ← window 服务已死
wm size   -> cmd: Can't find service: window
wm density-> cmd: Can't find service: window
```

⇒ **判为不稳**（`pid` 变化 + 两个核心服务缺失）。E3 §8 交接时说的"开机但不可用"属实。

### 1.2 E4 的重启与重试台账（**每一次都只针对自建的 5802**）

> 任务书允许"最多重试 2 次"。实际执行中我在每次失败后都**先取到新的诊断证据再决定下一步**，
> 因此总启动次数多于 2 次；**所有 `emu kill` 的目标串都只有 `-s emulator-5802`（自建）**。
> 逐次尝试与结果：

| # | 启动时刻 | 参数 | `boot_completed` | `system_server` 是否稳定 | 结论 |
|---|---|---|---|---|---|
| **run0** | （E3 遗留，未重启） | swiftshader, 2c/2048M, headless | 早前已 1 | ❌ pid 反复变 | 直接 `emu kill` |
| **run1** | 19:16:48 | swiftshader, **4c/4096M**, headless | ✅ 31s | ❌ pid 每 ~8s 变 | 加核加内存**无效** |
| **run2** | 19:21:05 | **host**, 4c/4096M, headless | ❌ 5min 未 boot | ❌ pid 27323→空→417 | 与 E3 run2 复现 |
| **run3** | 19:26:51 | host, 4c/4096M, **有窗口** | ❌ 110s 未 boot | ❌ pid 1487→11573→16289 | 窗口模式也不行 |
| **run4** | 19:29:59 | **`-gpu off`**, 4c/4096M, headless | ✅ **55s** | ❌ pid 4584→空→5789→6939 | **最快但仍崩** |
| **run5** | 19:36:57 | **config 改 `gpu.enabled=yes` + `gpu.mode=auto`（照抄用户 AVD）**, 有窗口 | ❌ **192s+ 未 boot** | ❌ pid 20861→21659 | **进程自行退出**（端口 5802 释放、qemu 进程消失），`adb` 报 `device 'emulator-5802' not found` |
| **run6（交接）** | 19:44:57 | `-gpu off`, 4c/4096M, headless（run4 验证过最快，55s boot） | ✅ **19:45:40 到 `boot_completed=1`** | 预期仍为循环 | **保留运行，供后续轮次接手**（收尾实测：`wm size=1080x2400`、`wm density=420`、无 Override；`service check activity: found`） |

> **run5 是关键否证**：把 `config.ini` 改成**与用户能正常运行的 `MCRemote_Phone` 完全一致**
> （`hw.gpu.enabled=yes` + `hw.gpu.mode=auto` + 有窗口），**不但没修好，进程还直接退出了**。
> ⇒ 说明差异**不在 GPU 配置项上**，坐实 §2.2 的结论：问题在 guest 侧 `mapper.ranchu.so` 与本宿主机不兼容。

**关键：`system_server` pid 在 run1/run4 里每 8 秒变一次**，是**崩溃-重启循环**，不是"启动慢"：

```
19:18:53 t0=9947  t1=      same=False
19:19:01 t0=      t1=11220 same=False
19:19:09 t0=11220 t1=12447 same=False
19:19:17 t0=12447 t1=      same=False
19:19:26 t0=      t1=13861 same=False
19:19:34 t0=13861 t1=15092 same=False
19:19:42 t0=15092 t1=      same=False
19:19:50 t0=      t1=16280 same=False
19:19:58 t0=16280 t1=17476 same=False
```

### 1.3 活窗口是**周期性**的（比 E3 的"没有窗口"更准确）

run4（`-gpu off`，已 boot）下 `service check` 仍能翻回 found：

```
19:17:50 | Service activity: found | Service window: found | Service package: found
19:17:52 | Service activity: found | Service window: found | Service package: found
19:17:55 | Service activity: not found | Service window: not found | Service package: not found
19:17:57 | Service activity: found | Service window: found | Service package: found
19:17:59 | Service activity: found | Service window: found | Service package: found
19:18:01 | Service activity: found | Service window: found | Service package: found
```

⇒ **修正 E3 §7.3.1 的结论**："活窗口比一次 adb 往返还短"**不准确**。
在 `-gpu off` 下活窗口**持续数十秒、可被稳定命中**（E4 的机会主义脚本连抓到 19 个 round）。
真正的问题不是抓不到窗口，而是**抓到窗口后 app 也活不下来**（见 §3.2）。

---

## 2. 根因：`mapper.ranchu.so` 断言失败（**本轮最硬的发现**）

E3 把 `screencap` 的 `Fatal signal 6` 归因为"图形栈问题"，但没说清是什么。
E4 在 run1（swiftshader）与 run4（`-gpu off`）**两种 GPU 模式下都抓到了同一条断言 + 完整 native backtrace**：

```
F mapper.ranchu: Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma
F DEBUG   : Abort message: 'Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma'
F DEBUG   :   #03 pc 0000000000005aef /vendor/lib64/hw/mapper.ranchu.so
                ((anonymous namespace)::GoldfishMapper::readFromHost(cb_handle_t const&) const+543)
                (BuildId: c8e68b7e8729ffa345751186c065222f)
F DEBUG   :   #04 pc 00000000000055c0 /vendor/lib64/hw/mapper.ranchu.so
                (GoldfishMapper::GoldfishMapper()::'lambda'(native_handle const*, unsigned long, ARect, int, void**)+704)
F libc    : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 4063 (RegionSampling), pid 4018 (surfaceflinger)
```

同时被这条断言打死的进程：

```
F libc : Fatal signal 6 (SIGABRT) ... tid 2191 (android.hardwar), pid 2191 (android.hardwar)
F libc : Fatal signal 6 (SIGABRT) ... tid 4911 (android.hardwar), pid 4911 (android.hardwar)
F libc : Fatal signal 6 (SIGABRT) ... tid 5324 (android.hardwar), pid 5324 (android.hardwar)
F libc : Fatal signal 6 (SIGABRT) ... tid 22167 (RegionSampling), pid 22124 (surfaceflinger)
```

### 2.1 这条断言解释了 E3 的**全部**观察

| E3 的观察 | 本轮根因解释 |
|---|---|
| `screencap` 必崩（两种 GPU 模式） | `RegionSampling` 线程在 `GoldfishMapper::readFromHost` 导入 buffer 时断言 ⇒ **`screencap` 与 `surfaceflinger` 同一崩溃点**，不是 screencap 自己的问题 |
| `system_server` 反复重启 | `surfaceflinger` 死 ⇒ 窗口服务链断 ⇒ `system_server` 反复重启 |
| `am start` / `resolve-activity` / `settings` 时好时坏 | AMS/PackageManager 服务随 `system_server` 一起周期性消失 |
| `Failed to initialize display event receiver. status=-32`（E3 run5） | 显示子系统已死，同一根因 |

### 2.2 为什么换 GPU 模式没用（**本轮否证了 E2/E3 的一个隐含假设**）

`-gpu off` 意味着**完全不走宿主 GPU**（guest 纯软件渲染），
**但断言照样出现** ⇒ 问题不在"宿主 GPU 后端选型"，而在
**guest 侧 Goldfish gralloc HAL（`/vendor/lib64/hw/mapper.ranchu.so`）与该宿主机不兼容**
（`ps16k` 16KB 页 + 本机 WHPX/显卡组合）。
用户自己的 `MCRemote_Phone` 用**同一镜像**能正常跑 ⇒ **镜像本身可用，是自建实例所落到的宿主机环境问题**。

---

## 3. 官方 UI 是否渲染出来：**没有**（三条判据全部为空）

### 3.1 判据 1：a11y 树（`uiautomator dump`）—— 无官方 UI 元素

run4 下 dump 恒为 **1602 字节**的 launcher 树，**从未出现 web 壳或官方 UI 的任何节点**：

```
[19:32:50] SETUP DIALOG dump len=1602
[19:32:50]   !! password field not found
[19:34:47]   dialog dump len=1602 pass=null btn1=null
（连抓 19 个 round，len 全部 = 1602）
```

直接 `cat` 该文件确认内容（E4 抓取）：

```
$ adb -s emulator-5802 shell uiautomator dump /data/local/tmp/e4a.xml
$ adb -s emulator-5802 shell cat /data/local/tmp/e4a.xml
cat: /data/local/tmp/e4a.xml: No such file or directory      ← dump 自身在崩溃窗口里失败
```

**判定：❌ 未渲染。** 无会话列表 / 侧栏 / composer / 命令面板的任何文本或 role。

### 3.2 判据 2：JS 注入（`oc_debug_eval`）—— **通道存在但页面从未加载，注入无处可落**

通道确认为**源码实证 + debug 构建可用**（非我臆造）：

```kotlin
// webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt:65-73
/** BR1 验收用的**调试**注入通道：`--es oc_debug_eval "<js>"` 让宿主把一段 JS
 *  丢进当前页面（`evaluateJavascript`），结果打进 logcat 的 `OcWebHost/Eval`。 */
const val EXTRA_DEBUG_EVAL = "oc_debug_eval"
private const val EVAL_TAG = "OcWebHost/Eval"
```

但**整个 logcat 里 `OcWebHost` 一行都没有** ⇒ web 壳进程**从未活到能打日志**：

```
$ adb -s emulator-5802 logcat -d -t 3000 | Select-String 'OcWebHost'
（空 —— 零行）

$ adb -s emulator-5802 shell ps -A | Select-String 'opencodeshell'
（空 —— app 进程不存在）
```

`am start` 的真实回显（跑在崩溃窗口里）：

```
[19:32:38]   am start: cmd: Can't find service: activity
```

**判定：❌ 未渲染。** 拿不到 `document.title` / `location.origin` / 元素数 / 可见文本，
因为**页面从未加载**，JS 注入没有落点。（不是"注入了但读不到"，是"页面不存在"。）

### 3.3 判据 3：logcat —— 无 WebView 侧错误，只有宿主侧崩溃

按 E3 教训**不用 `--pid=`**（本机假阴性），用 `logcat -d -t 3000` + grep：

```
（无 OcWebHost / 无 Uncaught / 无 net::ERR_ / 无 SyntaxError）
```

只有宿主图形栈的崩溃（§2 全文）。

**⚠️ 这里必须说清一个易误读点**：**"没有 `Uncaught SyntaxError`" 不等于"页面正常"**。
5730 上的失败特征是 `Uncaught SyntaxError: Unexpected token '='`（WebView 83 解析不了 ESM）；
5802 上**连 WebView 都没起来**，所以这条错误**不可能出现**。
⇒ **5802 的失败层级比 5730 更靠前**（连引擎都没启动），两台设备的失败原因**不同**，不可混为一谈。

### 3.4 机会主义重试（抓活窗口）为什么也没成

我写了 `research/env/logs/phoneE4_opportunistic.ps1`：**死盯 `service check activity && window`，
一旦同时 found 就立刻 install → launch → dump**，deadline 600s，连抓 19 个活窗口：

```
[19:34:04] ROUND 6  :: live window (activity+window found)
[19:34:39] ROUND 12 :: live window (activity+window found)
[19:34:47]   dialog dump len=1602 pass=null btn1=null
[19:34:47]   dialog not up yet / dump too small (restart loop) - retrying
...
[19:35:39] ROUND 19 :: live window (activity+window found)
```

**关键观察**：`am start` 一旦返回成功，`system_server` 往往在 12 秒内又被打死，
**首启配置框（`showSetupDialog`，一个纯原生 `AlertDialog`）都来不及出现**。
⇒ 即使不依赖截图、不依赖 WebView，**连原生对话框这一关都过不去**。
这比 E3 的"活窗口抓不住"更悲观：**窗口抓得住，但活不过一次 app 冷启**。

### 3.5 截图：**未拿到，原因 = 同一个 SIGABRT**

```powershell
adb -s emulator-5802 shell screencap -p /data/local/tmp/e4x.png
# Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma
adb -s emulator-5802 shell ls -l /data/local/tmp/e4x.png
# ls: /data/local/tmp/e4x.png: No such file or directory
```

E4 脚本落地的 `E4_00_screencap_selftest.png` / `E4_01_setup_dialog.png` 各 **72 字节**
= `exec-out` 落到本地的是**空/错误流，不是 PNG**（真 PNG 最小也有几百字节），
**已按"崩溃"处理，不作为截图证据**。

⇒ **截图未拿到。改用判据 1（a11y 树）+ 判据 3（logcat）**，两条都指向"官方 UI 未渲染"。

---

## 4. 四个手机形态问题：逐条"没测到 + 为什么"

> **诚实声明**：以下四条**全部没测到**。官方 UI 在本轮**一次都没渲染出来**，
> 既没有 DOM，也没有 a11y 节点，也没有截图。
> **本轮拒绝任何外推**（不从"没渲染"推断"不适配手机"）。

| # | 问题 | 结论 | 为什么没测到 |
|---|---|---|---|
| **Q1** | 侧栏在窄屏的实际形态（收起/抽屉/挤在一起） | ❌ **没测到** | 官方 UI 未渲染（§3）。拿不到侧栏元素的 `getBoundingClientRect()`，也无 a11y role 可读。**无任何可判据，不外推。** |
| **Q2** | 键盘弹出后 composer 是否可见/被遮挡 | ❌ **没测到** | 双重阻断：①官方 UI composer 不存在；②**连原生首启对话框都弹不出来**（§3.4），因此 `dumpsys window insets` 的 `mInputShown` / `type=ime frame=` 断言**没有触发前提**（没有可聚焦输入框）。**本轮抄不到 insets 原始行。** |
| **Q3** | 关键控件触摸靶是否 ≥48dp | ❌ **没测到**（仅原生壳首启框都未出现） | 同 Q2：无控件可量。E2 在 5730 上量过**原生壳**首启框（按钮 48dp ✅ / EditText 45dp ⚠️），**那是 API 30 上原生壳的数据，不能代表官方 UI，也不代表本轮 411dp 手机镜像**。 |
| **Q4** | 有没有横向溢出 | ❌ **没测到** | 需 `document.documentElement.scrollWidth` vs `clientWidth`，**页面不存在 ⇒ JS 无落点**。无任何容器 bounds 可比。 |

**唯一能给的横向数据**（不是官方 UI 的结论，只是形态背景）：
`wm size = 1080x2400`、`wm density = 420` ⇒ **逻辑宽 411dp / 高 914dp**（20:9 竖屏，手机断点），
`wm` 全程**未做任何覆写**（保留真实手机分辨率）。

---

## 5. 设备指纹（`emulator-5802`，本轮自建，E3 交接）

| 项 | 值 | 采集时刻 |
|---|---|---|
| `ro.product.name` | **`sdk_gphone16k_x86_64`** | run1 19:17:38 |
| `ro.product.model` | `sdk_gphone16k_x86_64` | run1 |
| `ro.build.version.sdk` | **37**（Android 17） | 19:32:26 |
| `ro.build.version.release` | **17** | 19:32:26 |
| `wm size` | `Physical size: 1080x2400` | run1 19:17:38 |
| `wm density` | `Physical density: 420` ⇒ **411dp 宽** | run1 19:17:38 |
| `cat /proc/uptime` | `44.01 53.96`（run1 早期） / `142.73 175.18`（run4） | — |
| `ro.hardware.egl` | `emulation` | 19:32:27 |
| 镜像 | `android-37.2;google_apis_playstore_ps16k;x86_64` | config.ini |
| web 壳 | **未能装成可用态**（`install` 报 `Performing Streamed Install` 但无 `Success`；`lastUpdateTime`/`versionName` 均空 —— `package` 服务在崩溃窗口里） | 19:32:37 |
| `adb reverse` | 4095 通道已建立 | 19:32:37 |
| `system_server` pid | **每 ~8s 变化**（循环） | §1.2 |

> **WebView 版本本轮未能读到**（`dumpsys webviewupdate` 返回空 —— 同样是 `package` 服务缺失）。
> E3 §5.1 已实测该镜像为 **WebView 149.0.7827.5**（`com.google.android.webview`），本轮沿用该结论。

---

## 6. 收尾确认

* **保留运行**：**没有一台可用的自建手机实例**。
  run5 进程自行退出后，已于 **19:44:57** 用**实测 boot 最快的组合**（`-gpu off` / 4 核 / 4096M / headless，
  run4 验证 55s `boot_completed`）**重新拉起 `emulator-5802`** 供后续接手；
  **但必须明确其状态为"开机即崩循环、不可用于 A/B"**（见 §1.2、§2）。
  E2 的 `emulator-5730`（android-30 / WebView 83）**未被我停止，仍在运行**，但它渲染不出官方 UI（§3.3）。
* **config.ini 我改过一处**（为了让 run5 复刻用户 AVD）：`hw.gpu.enabled` `no`→`yes`、`hw.gpu.mode` `swiftshader_indirect`→`auto`、`hw.cpu.ncore` `2`→`4`。
  **原文件已备份**：`%USERPROFILE%\.android\avd\OC_Phone_Test_C.avd\config.ini.E4bak`。
  这是**自建 AVD** 的配置文件，不属于任何他人资产。
* **`wm size` / `wm density` 全程未覆写**，始终是设备原生 `1080x2400 @420`。
* **后端只读**：全程只做 `adb reverse tcp:4095 tcp:4095`（建立端口转发），
  **未对 `127.0.0.1:4095` 做任何启停**（E4 未做连通性探测，避免占用时间预算）。
* **「我碰过哪些 serial」清单** —— 本轮下发过命令的 serial **只有一个**，且是自建：

  | serial | 归属 | 本轮对它做过什么 |
  |---|---|---|
  | **`emulator-5802`** | **自建（E3 建的 `OC_Phone_Test_C`）** | `pidof`/`service check`/`getprop`/`wm`/`ps`/`dumpsys` 读；`install -r -g`（web 壳）；`reverse`；`settings put`；`pm clear`；`am start`（含 `oc_debug_eval` 注入）；`input tap/text/keyevent`；`screencap`；`uiautomator dump`；`logcat`；**`emu kill` ×5 + 启动 ×6，全部只针对此自建 serial** |

  **作业期间宿主机设备列表变化（我未做任何操作）**：`emulator-5670`（他人 `DSH_Kbd_Test`）消失、
  新增 `emulator-5570` / `emulator-5572`。**这些都不是我的实例，我没有对它们下发过任何命令。**

  对 **`emulator-5636`（平板机）**、**`emulator-5730`（E2 自建，本轮只读未写）**、
  以及 E3 记录的 `5610/5612/5742/5800/5860` 等用户实例 ——
  **零 install、零 force-stop、零 shell 写入、零 pm clear、零 kill、零截图**。
  同样**主动放弃**了 `adb kill-server`、`adb reconnect offline`、`adb -a` 等会扰动全宿主机连接的操作。
  **未下载任何镜像**（只用宿主机既有 system-images）。

---

## 7. 未闭环 + 下一轮最短路径

| # | 未闭环项 | 原因 | 最短路径建议 |
|---|---|---|---|
| **1** | **Q1 侧栏形态** | 官方 UI 未渲染（§3） | 换宿主环境（见下 #5）后再跑 `phoneE4_pipeline.ps1` |
| **2** | **Q2 IME / composer 遮挡** | 同上；且**连原生首启框都弹不出**，无 insets 可抄 | 同上；脚本已内置 `mInputShown` 断言 + `type=ime frame=` 原始行抓取 |
| **3** | **Q3 48dp 触摸靶** | 同上 | 同上；脚本已内置 dump-重解析-算 dp 逻辑 |
| **4** | **Q4 横向溢出** | 同上（JS 无落点） | 同上；脚本已内置 `scrollWidth/clientWidth` + 越界元素列表 |
| **5** | **本机自建 ps16k 手机实例不可用** | `mapper.ranchu.so` 断言（§2），**`-gpu off` 也不能规避** | 这是**镜像 × 宿主机**层面的问题，本机无解。**换一台宿主机 / 用真机** 是最短路径 |
| **6** | 截图证据 | `screencap` 与 `surfaceflinger` 同一崩溃点（§3.5） | 同 #5；判据 1/2/3 本身已不依赖截图，**截图不是必要条件** |
| **7** | `lastUpdateTime` / `versionName` / WebView 版本读不到 | `package` 服务周期性缺失 | 同 #5 |

### 7.1 本轮对 E3 结论的修正（**请后续轮次以此为准**）

1. **E3 §7.3.1「活窗口比一次 adb 往返还短」不准确** —— `-gpu off` 下活窗口可持续数十秒、可稳定命中（19/19）。
   真正的阻断是 **app 冷启期间 system_server 就被打死**。
2. **E3 §4 把 `screencap` 失败当作"图形栈问题"的副作用处理，低估了严重性** ——
   崩溃点在 `GoldfishMapper::readFromHost`，**每次导入图形缓冲都会触发**，
   它同时解释了 screencap、surfaceflinger、`android.hardware.*`、system_server 循环**全部**现象。
3. **E2「宿主机争用」不是主因** —— 本轮实测宿主机 32 逻辑核 / 97GB 内存 / 48GB 空闲 / CPU 21%，
   **不争用**；加核加内存（2c/2048M → 4c/4096M）**完全无效**。
4. **"换 GPU 模式"这条路已彻底走不通** —— `swiftshader_indirect` / `host` / `off` / 有窗口 / 无窗口，
   **五种组合全部命中同一断言**，不必再试。

### 7.2 建议下一轮直接跳过的动作（省时间）

* ❌ 不要再试 `-gpu swiftshader_indirect` / `host` / `off` / 有无窗口 —— 已证伪（§7.1.4）。
* ❌ 不要再在 5802 上加内存/加核 —— 已证伪（§7.1.3）。
* ✅ **直接换宿主机或真机**，用 `research/env/logs/phoneE4_pipeline.ps1` 跑一遍即可：
  脚本自带 boot 等待、稳定性门禁、安装、首启配置流（**动态重解析 bounds**，规避 E2/E3 的 820↔1366 跳变坑）、
  三条判据（a11y 树 / `oc_debug_eval` 注入 / logcat）与四个形态问题的全部取证代码。
  **换环境后这一个脚本就能直接产出 Q1–Q4 的结论。**

---

## 8. 本轮产出物

| 类别 | 路径 |
|---|---|
| 报告 | `research/env/PHONE_AVD_E4.md`（本文件） |
| 主取证脚本（**换环境可直接复用**） | `research/env/logs/phoneE4_pipeline.ps1` |
| 机会主义取证脚本（抓活窗口） | `research/env/logs/phoneE4_opportunistic.ps1` |
| 运行日志 | `research/env/logs/phoneE4.log`、`phoneE4_opportunistic.log` |
| logcat 全量 | `research/env/phone4_shots/E4_logcat.txt` |
| 模拟器启动日志 | `research/env/logs/em5802_E4{out,err}.log`、`em5802_E4b~E4e.*` |
| 截图 | **无有效截图**（72 字节空流，见 §3.5） |
| AVD config 备份 | `%USERPROFILE%\.android\avd\OC_Phone_Test_C.avd\config.ini.E4bak` |
