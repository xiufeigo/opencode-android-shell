# 真机耗电测量：一页说明 + 可比性判据 + 省电现状盘点

> **本轮（T1）没有任何真机耗电数字。** 我们手上没有真机，所以本轮**只交付工具与判据，不交付结果**。
> 下面出现的一切"阈值""噪声"都是**标注过的估计**，不是测量值。任何声称本轮测出了 mAh 的说法都应被怀疑。
>
> 关联背景（都已存在，不用重跑）：
> - `research/measure/BASELINE_NATIVE.md:417-424` —— 模拟器 `currently on battery: false`、`Discharge: 0 mAh`
> - `research/measure/AB_NATIVE_VS_WEB.md:277` —— 原生/web 两边 `Discharge` 都是 `0 mAh`
> - `research/perf/out_p4/web/battery_probe.txt` —— web 壳电池探测，四项全空
>
> 这就是"耗电只能真机测"的由来。本文件把这件阻塞的事变成一条命令。

---

## 0. 最短路径：插上手机，跑这一行

> 本节是 T2 补的**操作入口**。下面 §1–§8 的推导、判定阈值 X、省电盘点**一字未改**。
> 自检原始输出见 `research/perf/BATTERY_READY_T2.md`。

### 0.1 先拿串号

```powershell
adb devices -l        # 第一列就是串号；emulator-* 开头的机器测不了耗电（没有电池模型）
```

### 0.2 复制即用（一条命令）

```powershell
# web 壳（-Tag web）
pwsh -NoProfile -File research\perf\battery_real_device.ps1 -Serial <把上面第一列的串号填这里> -Package com.xiufeigo.opencodeshell.web.debug -Tag web -Minutes 10

# 原生壳（-Tag native）—— 同机、同亮度、同网络、同时长、同交互
pwsh -NoProfile -File research\perf\battery_real_device.ps1 -Serial <同一个串号> -Package com.xiufeigo.opencodeshell -Tag native -Minutes 10
```

**两轮都跑完，脚本自动出对比表：`research/perf/out_battery/COMPARE.md`。**

> 💡 建议先加 `-DryRun` 跑一次体检（**不测耗电、不改设备、几秒钟**），确认这台机能测再开始：
> `pwsh -NoProfile -File research\perf\battery_real_device.ps1 -DryRun -Serial <串号> -Package com.xiufeigo.opencodeshell.web.debug`
> 干跑报 **exit 0 = 能测**；报 2/3/4 = 照它给的提示先修前置（见 §0.5）。

### 0.3 前置条件清单（**两轮必须完全一致**，否则结果不可比）

| # | 条件 | 怎么做 | 为什么 |
|:--:|---|---|---|
| 1 | **USB 调试已开且已授权** | 设置 → 关于手机 → 连点版本号 → 开发者选项 → **USB 调试**；弹窗点「允许」 | 没授权时状态是 `unauthorized`，脚本 exit 2 |
| 2 | **数据线，不是只充电线** | 用能传文件的线。很多充电线只有电源针脚 | 只充电线 ⇒ adb 看不到设备，exit 2 |
| 3 | **不能插着充电器** | **拔掉充电线，只留数据线**，等 1 分钟再跑 | 插电时测到的是充电电流不是耗电，脚本 exit 4 |
| 4 | **屏幕亮度固定为手动** | 设置里关掉自动亮度，固定同一个值（`-SetBrightness 120` 可让脚本代设，结束自动复原） | 亮度差 20 档就足以淹没我们要测的差异（§5.2） |
| 5 | **音量固定、静音** | 同一档，两轮一致 | 声音会驱动功放 |
| 6 | **网络固定** | 同一 WiFi 或同一基站/信号格；**两轮之间不要跑别的 App** | 射频活跃时间是 web 壳的决定性指标（§5.1） |
| 7 | **电量充到同一档** | 两轮起始电量尽量相同，建议都从 **80% 上下**起 | 起始电量不同 ⇒ 内阻/电量百分比基数不同 |
| 8 | **不插 SIM 卡也行，但要一致** | 两轮同样处理 | — |
| 9 | **App 提前装好并登录 Backend** | App 内配好**同一个** backend 地址 | 脚本只记录地址，不代你登录（§1 参数表） |

**测多久**：

- **单轮 10 分钟**（`-Minutes 10`）。依据：§5.3 的最低要求是 2 组、先看方向约 20 分钟；
  §6 量程约束指出 rail 电流量化步长折算到整窗 mAh 约 1–3%，**窗口越短这项误差占比越大**；
  §5.1 又指出电量下降 % 在 5 分钟窗口里常常只有 0–1%，**分辨率不够**。10 分钟是"误差够小 + 不至于太久"的折中。
- **两轮之间让手机静置 2–3 分钟**，回到同一温度（§5.3、§5.2 第 6 条：电池温度差 >3 °C 就要重跑）。
- 全程成本：两条命令 ≈ 25 分钟（含预热与静置）。
- 只想要个方向 → 各跑 1 轮；要报百分比 → 每组重复 2 次取中位数（§5.3 第 3–5 行）。

### 0.4 会产出什么

产物目录：**`research/perf/out_battery/<tag>/`**（`native/` 与 `web/` 各一份），另有 `COMPARE.md`。

| 产出 | 说明 |
|---|---|
| `out_battery/<tag>/reading.md` | **单轮 Markdown 表，直接贴给我** |
| `out_battery/<tag>/reading.json` | 机器可读，`COMPARE.md` 由它生成 |
| `out_battery/COMPARE.md` | 两轮对比 + **可比性判定表**（7 项，红项即作废） |
| `out_battery/<tag>/rail_samples.csv` | rail 电流逐点采样（**主指标 mAh 由此积分**） |
| `out_battery/<tag>/batterystats_start/end.txt` | 起止 batterystats（Discharge / UID CPU / WakeLock / 射频 / Wakeup） |
| 其余（cpuinfo / power / netstats / fingerprint / battery_*/ restore_check） | 辅助，含义见 §3 |

**关键字段怎么读**（详见 §5.1）：

| 字段 | 意义 |
|---|---|
| `rails.integrated_mah` | **主指标**：`current_now` 积分出的 mAh，最接近"真耗电" |
| `battery.discharge_mah` | 主指标：系统自算的 Discharge（since last unplug 段） |
| `uid.cpu_ms` | 次指标：UID CPU 时间，**稳定可复现**，但**是代理量不是耗电** |
| `wakelock.count` / `held_ms` | 次指标：**出现非零长持锁 = 漏电信号** |
| `u.mobile_radio_active_ms` / `wifi_running_ms` | 次指标：对 WebView 应用是**决定性**的一项 |
| `battery.battery_level_start→end` | 辅：整数台阶，**分辨率不够**，只作交叉验证 |
| `valid` / `invalid_reason` | **false ⇒ 本轮作废**，不会拿 0 顶替 |

**什么算通过 / 不通过**：

- ✅ **通过**：`COMPARE.md` 的可比性判定表**全绿** + 两轮 `valid` 都为 true。
- ✅ **可报"明显更费电"**：差异 **≥ X = 20%**（§6 推导，X 标注为估计，未放宽）。
- ⚠️ **"在测量噪声内，不构成证据"**：差异 **< X**。**这不等于"两者一样"**（§6 判定规则第 1 行）。
- ❌ **不通过 / 作废**：判定表有红项（亮度不同、网络不同、窗口时长差 >30 s、交互脚本不一致、温差 >3 °C）。
- ❌ **不报百分比**：只有 n=1 时不许报百分比，只报绝对值。
- 🔴 **一律作废**：`valid=false`、或 `mAh` 读不出来。**`0 mAh` 不是"很省电"，是"没测到"**（§4 五道闸）。

### 0.5 跑失败了怎么报给我

**把 `research/perf/out_battery/` 整个目录打包贴回来即可**（`reading.md` / `COMPARE.md` 在最外层）。
如果脚本中途 exit 了、没跑完，把**它打印的那段报错原文**也贴上，并告诉我：

- **哪个文件**（比如 `out_battery/native/reading.json`），或直接说"两轮都 exit 2，一个文件都没有"；
- `exit` 码是多少（**0 成功 / 2 设备没连上或状态不对 / 3 App 没装或 Activity 解析不出 / 4 无电池模型（模拟器、插电）/ 5 其它测量问题 / 6 参数没给全**）；
- 设备型号 + Android 版本；
- 出问题那一步手机屏幕上的状态（有没有弹授权框、有没有插着充电器、亮度是不是自动）。

**最省事的一句话模板**：

> 串号 `XXXX`，`adb devices` 里状态是 `device`。web 轮 exit 0（`out_battery/web/reading.md` 已附），
> native 轮 exit 4，报错原文：「设备插着电（dumpsys battery plugged=2）」。我拔了充电线再跑就好。

---

## 1. 怎么跑（在真机上）

脚本：`research/perf/battery_real_device.ps1`。**纯只读测量，不改设备设置。**

### A) 把脚本拷到那台 Windows 机器上跑（推荐）

```powershell
# 第 1 轮：原生壳
pwsh -NoProfile -File .\battery_real_device.ps1 `
  -Serial R5CT30XXXXX `
  -Package com.xiufeigo.opencodeshell `
  -Activity com.xiufeigo.opencodeshell.OcActivity `
  -Minutes 10 -Tag native

# 第 2 轮：web 壳（同机 / 同亮度 / 同网络 / 同时长 / 同交互）
pwsh -NoProfile -File .\battery_real_device.ps1 `
  -Serial R5CT30XXXXX `
  -Package com.xiufeigo.opencodeshell.web.debug `
  -Activity com.xiufeigo.opencodeshell.web.WebHostActivity `
  -Minutes 10 -Tag web
```

第 2 轮跑完，脚本**自动**生成对比表：`research/perf/out_battery/COMPARE.md`。

### B) 自己机器上跑，USB 连着

```powershell
adb devices                              # 先确认真实串号
pwsh -NoProfile -File research\perf\battery_real_device.ps1 -Serial <上面那个> -Tag native
```

### C) 手机上直接跑（Termux，无 PC）

脚本是 pwsh，手机上跑不了。**照抄下面 6 条命令**，顺序不变，含义与脚本一致：

```bash
adb shell dumpsys batterystats --reset
adb shell am force-stop <pkg>; adb shell am start -n <pkg>/<act>
adb shell dumpsys batterystats --charged <pkg>          # 起
#   ... 跑与脚本完全相同的固定交互，撑满 -Minutes ...
adb shell dumpsys batterystats --charged <pkg>          # 止
adb shell dumpsys power | grep -A20 'Wake Locks'        # 补 WakeLock
adb shell "cat /sys/class/power_supply/battery/current_now"   # mA·h 靠它
```

### 参数

| 参数 | 必填 | 默认 | 说明 |
|---|:--:|---|---|
| `-Serial` | ✅ | — | 真机串号。`emulator-*` **默认直接拒绝**（见 §4） |
| `-Package` | ✅ | — | 原生 `com.xiufeigo.opencodeshell` / web `com.xiufeigo.opencodeshell.web.debug` |
| `-Activity` | | 自动解析 | 省略时用 `cmd package resolve-activity` 推 |
| `-Minutes` | | `5` | 测量窗口。**建议 10**，≥5 才有意义 |
| `-Backend` | | — | 如 `http://192.168.1.10:4096`。**只记录 + 从本机做可达性校验，不改设备** |
| `-Password` | | — | 只记 `password_supplied=true`，**永不落盘、永不进日志** |
| `-SetBrightness` | | 不改 | 传 `0..255` 才会改亮度，**结束必定复原并复核**（证据见 `restore_check.txt`） |
| `-Tag` | | `run` | 用 `native` / `web` 才能自动出对比表 |
| `-WarmupSec` | | `25` | 预热，**不计入**窗口 |
| `-NScroll/-NTap/-NSwitch` | | `10/6/5` | 固定交互的次数（两组必须一致） |
| `-LoopInteraction` | | 开 | 固定交互循环填满整个窗口；关掉则跑一轮后静置 |

**App 里的 Backend 请提前在 App 内配好同一个地址。** 脚本不代你登录、不代你改 App 配置 —— 它只保证两轮记录的是同一个地址（`reading.json` 的 `backend` 字段，可比对）。

---

## 2. 固定交互脚本（两组必须逐字相同）

这是可比性的核心。**一个 cycle**（约 45 s）：

| # | 动作 | 坐标（窗口相对） | 时长 / 间隔 |
|---|---|---|---|
| ① | 上滑 ×10 | (0.50, 0.80) → (0.50, 0.20) | 250 ms / 间隔 250 ms |
| ② | 下滑 ×10 | (0.50, 0.20) → (0.50, 0.80) | 250 ms / 间隔 250 ms |
| ③ | 抽屉进出 ×5 | (0.15,0.50)→(0.15,0.85)，再 (0.85,0.50)→(0.85,0.15) | 250 ms / 间隔 350 ms |
| ④ | 点开-返回 ×6 | 点 (0.50, 0.45) → 等 800 ms → `KEYCODE_BACK` → 等 700 ms → 重新 `am start` → 等 900 ms | 同左 |

坐标按 `dumpsys window` 的**真实窗口矩形**换算（脚本 `GetWinFrame`），**不写死 1080×2400**。

流程顺序：`--reset` → 预热 25 s → 取窗口起点读数 → 循环固定交互填满窗口 → 取终点读数。

---

## 3. 要给我什么

回传 `research/perf/out_battery/<tag>/` **整个目录**即可（`reading.md` 是可直接粘的表）。里面包含：

| 文件 | 内容 |
|---|---|
| `reading.md` | **单轮 Markdown 表（直接贴）** |
| `reading.json` | 机器可读，`COMPARE.md` 由它生成 |
| `batterystats_start/end.txt` | 起点/终点 batterystats（**含 UID CPU / WakeLock / 射频 / Wakeup**） |
| `batterystats_end_global.txt` | 全局段（`Alarms:` / `Wakeup alarms:` 块） |
| `power_wakelocks.txt` | `dumpsys power` 全量，含 WakeLock 明细 |
| `netstats.txt` | `dumpsys netstats`（网络字节 best-effort 解析） |
| `rail_samples.csv` | **rail 电流逐点采样**（mA·h 由此积分） |
| `pre_/post_power_supply.txt` | sysfs `current_now` / `voltage_now` / `capacity` |
| `fingerprint.txt` | 机型 / Android 版本 / SDK / build 指纹 |
| `battery_start/end.txt` | 电量%、温度、健康度、插电状态 |
| `restore_check.txt` | **设备设置复原证据** |

**外加三句话**（脚本读不到、只有人能给的）：

1. **设备型号 + Android 版本**（`fingerprint.txt` 已有，但请口头确认，便于写报告）
2. **屏幕亮度设成了多少**（0–255），以及**是否自动亮度**
3. **是否插电**（必须**否**；插电时脚本会 exit 4 拒绝跑）

---

## 4. 没有电池模型时会怎么报错（**绝不静默给 0**）

`0 mAh` 是本项目历史上最危险的一个数字 —— 它会被直接误读成"很省电"。脚本对此有**五道闸**：

| 闸 | 触发条件 | 行为 |
|---|---|---|
| 1 | 串号是 `emulator-*` | **exit 4**，直接拒绝跑（`-AllowEmulator` 可放行，但下一闸仍会拦） |
| 2 | `dumpsys battery` 报 `plugged != 0` | **exit 4**，说明"插电时测的是充电电流不是耗电，拔掉充电线只留数据线" |
| 3 | `currently on battery: false` | **exit 4**，说明"模拟器 AVD 按插电处理，Discharge 恒为 0、Time on battery 恒为 0ms，Battery capacity 是标称常量不是测量值" |
| 4 | 窗口内电量无下降 **且** `Discharge=0` **且** rail 电流不可读 | `valid=false` + `invalid_reason`，对比表标"❌ 不能比" |
| 5 | 任何单项解析不到 | 显示 `N/A(未解析)`，**不填 0** |

**已实测验证**（本轮真跑过的两条，不涉及任何设备改动）：

```
-Serial emulator-5554          → exit 4「串号 'emulator-5554' 是模拟器 / 无电池模型、无 rail 电流」
-Serial SMOKE-NOT-A-DEVICE     → exit 2「不在 adb devices 列表里」
```

> ⚠ 顺带修掉一个真 bug：最初用 `$state -notmatch 'device'` 判在线，而 `SMOKE-NOT-A-DEVICE` **含子串 "DEVICE"**（`-notmatch` 大小写不敏感），不存在的串号被误判成"在线"。现在改成按 `adb devices` **整行**匹配 `<serial>\s+device\s*$`。

**收尾行为**：默认**不改任何设备设置**（只做 `batterystats --reset` 清统计）。只有传了 `-SetBrightness` 才改，且结束必定复原 + 回读复核，写进 `restore_check.txt`。**不执行 `adb kill-server`、不 force-stop 别的 App、不杀任何模拟器、不卸载、不改开发者选项。**

---

## 5. 怎么解读

### 5.1 可比 / 不可比

| 数字 | 可比？ | 说明 |
|---|:--:|---|
| **rail 积分 mAh**（`current_now` 积分） | ✅ **主指标** | 与 rail 电流直接挂钩，最接近"真耗电"。前提：sysfs 可读 |
| **Discharge mAh**（batterystats） | ✅ 主指标 | 系统自算，但含估算模型；取 "since last unplug" 段 |
| **电量下降 %** | ⚠ 辅 | 整数台阶，5 min 窗口常常 0% 或 1%，**分辨率不够**，只作交叉验证 |
| UID CPU 时间 (ms) | ✅ 次指标 | 稳定、可复现，适合当"第一根体温计" |
| WakeLock 数 / 持有时长 | ✅ 次指标 | **出现非零长持锁 = 漏电信号** |
| Wakeup alarms 次数 / 时长 | ✅ 次指标 | 直接对应"把 CPU 唤醒了多少次" |
| 射频活跃 / WiFi running (ms) | ✅ 次指标 | 对 WebView 应用是**决定性**的一项 |
| 网络 rx / tx 字节 | ⚠ 辅 | `dumpsys netstats` 格式各版本不同，可能 `N/A`；原样已落盘 |
| Estimated power use | ⚠ 弱 | 相对值，跨设备/跨会话不可比，只看同机相对 |
| 电池温度 | ⚠ **可比性前置** | 温差 >3 °C 就要重跑（锂电内阻随温度变化） |
| 屏幕亮度 / 是否常亮 | ⚠ **可比性前置** | 不记录就没有可比性 |

### 5.2 不可比的四种情况（一票否决）

> **不同亮度 / 不同网络 / 不同时长 / 不同交互 ⇒ 不可比。**
> 尤其注意：屏幕本身是耗电大户，**亮度差 20 档就足以淹没我们要测的那个差异**。

必须满足：

1. **同机同条件跑两遍**（原生 / web），而不是各用一台设备
2. 屏幕亮度**相同**，且**固定为手动**（`mode=0`；`mode=1` 或 `-1` ⇒ 不可比）
3. 网络条件相同（同一 WiFi / 同一基站），**两轮之间不要跑别的 App**
4. 窗口时长相同（脚本容差 ±30 s）
5. 交互脚本参数相同（`NScroll/NTap/NSwitch`）
6. 电池温度差 ≤3 °C
7. 两轮 `valid` 都为 true

`COMPARE.md` 会把上面 7 条做成一张**判定表**。**那一列没全绿之前，任何百分比差异都不成立。**

### 5.3 要跑几组

最低要求 **2 组**（`native` + `web`）。
但 2 组**只能得到"有没有差别"的弱信号**。建议：

| 轮次 | 目的 | 成本 |
|---|---|---|
| 1–2 | `native` ×1 + `web` ×1，**先看方向** | ~20 min |
| 3–5 | `native` ×2 + `web` ×2，取中位数 | ~40 min |
| 6+ | 有争议时再补 | — |

**每组之间让手机静置 2–3 分钟回到同一温度。**

---

## 6. 判定阈值 X

### 建议：**web 比原生高 20% 以上，才算"明显更费电"**（`X = 20%`）

### 这个 X 是怎么来的 —— **是估计，不是测量结果**

**先说清楚我们手上有什么：** 唯一一条真实测过的锚点是
`research/measure/BASELINE_NATIVE.md:467`（同文 `:496`）：**UID CPU 占用率 0.0418%**
（fgs 0.0297% + bg 0.0121%），窗口 300 s。

**但那是模拟器、且是单次**，所以：

- **真机上的 run-to-run 噪声水平 = 未核对。** 我们一个真机重复都没有。
- 因此 X **不能**由数据拟合，只能给一个**保守先验**，再用 2σ 规则兜底。

**推导（三步）**：

1. **量程约束**：rail 电流 `current_now` 的量化步长通常 100 µA–1 mA。5 min 窗口内
   典型应用电流量级下，单点量化误差折算到整窗 mAh 约 **1–3%**。这不是主项。
2. **主噪声项不是仪器，是环境**：电池温度漂移、射频信号强度波动、SoC 频率档位、
   后台 App 干扰。工程经验上，**同条件同机重复测 UID CPU 的变异系数（CV）常在 10–15%**
   —— ⚠ 这一句是**经验估计，我们没有本项目的实测 CV**。
3. **2σ 规则 + 下限**：`X = max(20%, 2 × CV_repeat)`。在 CV 未知时，
   取 `2 × 10% = 20%` 作为下限，并**同时把 20% 当作地板**。

**为什么不给更小的 X**：宁可漏报也不要误报。20% 的漏报代价是"多优化一轮"；
5% 的误报代价是"结论不可信、以后没人信我们的测量"。

**为什么不给更大的 X**：可比较差异往往就在 10–30% 区间。X=50% 会让这个工具失去意义。

### 拿到 3 次重复后，**必须重算 X**

```powershell
# 用 native 自己的 3 次重复算 CV（不要用 web 的，因为差异本身要判显著性）
$r = 1..3 | ForEach-Object { (Get-Content "out_battery/native/rep$_/reading.json" -Raw | ConvertFrom-Json).uid.cpu_ms }
$cv = ($r | Measure-Object -Average).Average
$sd  = [math]::Sqrt((($r | ForEach-Object { ($_ - $cv) * ($_ - $cv) } | Measure-Object -Sum).Sum) / ($r.Count - 1))
"CV = $([math]::Round($sd / $cv * 100, 1))%  ⇒  X = $([math]::Max(20, 2 * $sd / $cv * 100))%"
```

**判定规则**：

| 条件 | 结论 |
|---|---|
| 差异 < X | **"在测量噪声内，不构成证据"**（不是"两者一样"） |
| 差异 ≥ X，且 `COMPARE.md` 判定表全绿 | **"web 明显更费电"** |
| 差异 ≥ X，但判定表有红项 | **作废，先修可比性** |
| 只有 n=1 | **不许报百分比**，只报绝对值 |

---

## 7. 省电现状盘点（只读核查，逐条带行号）

范围：`webapp/**`。**只写方案，不改代码。** 跨壳对照引用 `app/**` 时会明确标注。

### 7.1 后台是否继续渲染？是否停 JS？

| 项 | 现状 | 行号 |
|---|---|---|
| `onPause` | **只调 `webView.onPause()`**，无 `pauseTimers()`、无 `stopLoading()`、无通知页面 | `webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt:756-759` |
| `onResume` | `webView.onResume()` + `insets.refresh()` | 同上 `:761-767` |
| `pauseTimers()` | **全仓未调用**（grep 无命中） | 未核对到调用点 |
| `visibilitychange` 监听 | **webapp 内无**（仅 `app/.../ServerConfigSyncJs.kt:38` 命中，属原生壳） | — |

- **现状**：`WebView.onPause()` 停的是**渲染与动画时钟**，**不停 JS 定时器**。本项目 web 侧恰好没有常驻 JS 定时器（见 7.4），所以当前 JS 泄漏风险低；但一旦页面引入轮询，`onPause` 拦不住。
- **风险**：**中**。渲染停了，但网络与代理线程没停（见 7.2），那才是主要漏电口。
- **第一刀**：不是加 `pauseTimers()`（收益小、副作用大），而是 **7.2 的后台断连**。

### 7.2 后台是否维持 SSE / 轮询？—— **头号风险**

| 项 | 现状 | 行号 |
|---|---|---|
| SSE 识别 | `Accept` 含 `text/event-stream` **或** path 以 `/event` 结尾 | `webapp/.../host/LoopbackProxy.kt:510-515` |
| SSE 转发 | 标记 `streaming=true`，**不缓冲**、删 `Content-Length`、强制 `Connection: close` ⇒ **每条 SSE 都是一条独占长连接** | 同上 `:296`、`:327`、`:341-344` |
| SSE 读超时 | 注释：上游 `/event` 每 10 s 心跳，35 s 静默判半开主动断开让页面重连 | 同上 `:129-133` |
| 客户端 socket | **`soTimeout = 0`（无限阻塞读）** ⇒ 后台不会被读超时掐断 | 同上 `:173` |
| 代理生命周期 | `ServerSocket` + `newCachedThreadPool`（守护线程 `oc-web-proxy`）在 `start()` 后**常驻** | 同上 `:137-155` |
| 唯一关闭点 | `close()`：关 ServerSocket + 遍历 `activeClients` 关 socket + `shutdownNow()` | 同上 `:161-169` |
| **谁调 close** | **只有 `onDestroy()`** | `WebHostActivity.kt:769-779`（`:776` `proxy?.close()`） |
| **`onPause` 做什么** | **什么都不关** | `WebHostActivity.kt:756-759` |

- **现状**：**App 退到后台后，回环代理不关、SSE 长连接不断、每 10 s 的上游心跳继续唤醒射频。**
  代理的 `activeClients` 里那条 SSE socket（`soTimeout=0`）会一直挂着，直到进程被杀。
- **风险**：**高，且是本清单第一优先级。** 屏幕完全灭掉之后仍在耗电，且**与屏幕亮度无关** ——
  也就是说它会**直接污染"同亮度"条件下的对比**，让 web 壳凭空多吃一份。
- **第一刀（最该砍的一条）**：在 `WebHostActivity.onPause()` 里**按需断开 SSE**
  （`:756-759` 附近），`onResume()` 重连。做法上可复用已有的半开检测语义
  （`LoopbackProxy.kt:129-133` 那套 35 s 静默判定的反向操作），或复用 `close()` 的
  `activeClients` 遍历逻辑（`:165`）做一个"只关流式连接、不关 ServerSocket"的变体。
  **注意**：不要直接调 `proxy?.close()`，那会把 `port` 置空导致 `onResume` 恢复失败。
  配套要在页面侧加 `visibilitychange` 处理（webapp 目前没有）。

### 7.3 屏幕常亮

| 项 | 现状 | 行号 |
|---|---|---|
| 桥方法 | `setKeepScreenOn(on)`：`true` → `addFlags(FLAG_KEEP_SCREEN_ON)`；`false` → `clearFlags` | `webapp/.../host/WebHostBridge.kt:156-167`（`:162` add / `:164` clear） |
| 设计意图 | 注释："回合进行中传 true 置 `FLAG_KEEP_SCREEN_ON`，结束传 false 清掉" | 同上 `:157` |
| **调用者** | **全仓 grep `setKeepScreenOn` 只命中定义处 `:159`**；`webapp/**`、`app/**/*.kt`、`app/**/*.js`、`app/src/main/assets/opencode-app`（内置页面产物）**均无调用** | — |

- **现状**：**当前根本不会置常亮** —— 这段是**死代码**。
  ⚠ 但这**不等于**没有风险：一旦页面接上调用，就出现**忘关路径** ——
  `onPause()`（`WebHostActivity.kt:756-759`）里**没有任何防御性 `clearFlags`**。
  若回合中途页面崩溃 / 被杀 / 用户切走而没发 `false`，flag 会一直留到 Activity 销毁。
- **风险**：**当前无**；**接上之后 = 高**（常亮是耗电大户，且是"看起来很合理"的那类 bug）。
- **第一刀（接上时一起做）**：在 `onPause()` / `onDestroy()` 里**无条件 `clearFlags` 兜底**，
  放在 `WebHostActivity.kt:756-759` 与 `:769-779`。加之前先在真机上量一量
  `FLAG_KEEP_SCREEN_ON present = yes` 的那组 vs 不常亮那组的 mAh 差 —— 那是它值不值得留的直接证据。

### 7.4 定时器 / 心跳

| 位置 | 内容 | 周期 | 是否常驻 | 行号 |
|---|---|---|---|---|
| `oc-perf.js` | DOM 规模采样 | **500 ms** | ❌ **第 40 次 `clearInterval` 自停**（最长 20 s），且只 `console.log` | `webapp/src/main/assets/www/oc-perf.js:63-72`（清理在 `:71`） |
| `index.html` | 自检页通知按钮 | — | ❌ 一次性 `setTimeout(800)`，在 onclick 里 | `webapp/src/main/assets/www/index.html:281-288`（`:283`） |
| `WebHostActivity` | 首屏看门狗 | 一次性 | ❌ `postDelayed` 一次，首次内容回报即 `removeCallbacks` | `webapp/.../WebHostActivity.kt:146-153`、`:197-201`、`:209` |
| `WebHostActivity` | 打开会话 / 恢复滚动 | — | ❌ `postDelayed(400)` 一次性 | 同上 `:679`、`:688` |
| `webapp/**` 内 `ScheduledExecutorService` / `Timer` / 周期性 `postDelayed` | **无** | — | — | grep 确认 |

- **现状**：**web 壳自己几乎没有定时器漏电**。唯一常驻过的 `oc-perf.js` 500 ms 采样在 20 s 后自停，属 DEBUG 探针、release 无害。
- **风险**：**低**。真正的耗电不在 JS 定时器，在 7.2 的 SSE。
- **第一刀**：**不用砍**。真机测出偏高时不要在这里找原因（这正是"先量再砍"的意义）。
- ⚠ **跨壳对照（影响 A/B 公平性，不是 web 壳的问题）**：
  `app/src/main/java/com/xiufeigo/opencodeshell/ServerConfigSyncJs.kt:32-41` 里，
  重试耗尽后落到 **`setInterval(read, 5000)`**（`:36`）⇒ **原生壳有 5 s 永久轮询，web 壳没有**；
  另有 `visibilitychange` 回前台补读（`:38-40`）。
  **含义：真机 A/B 里原生壳可能在射频侧更费电。** 测出"web 更费电"前，
  请先确认这个 5 s 轮询没有把结论带偏。

### 7.5 "第一刀砍哪"清单（按优先级）

| 序 | 砍什么 | 位置 | 预期收益 | 风险 |
|:--:|---|---|---|---|
| **1** | **后台断 SSE / 关流式连接** | `WebHostActivity.kt:756-759`（新增）+ `LoopbackProxy.kt:161-169` 的"只关流式"变体 | **最大**：直接掐掉 10 s 心跳的射频唤醒，且**与屏幕亮度无关**，最可能解释"web 偏高" | 中：要配 `visibilitychange` 重连；别直接 `proxy?.close()`（会丢 port） |
| 2 | 交叉验证 native 的 5 s 轮询 | `app/.../ServerConfigSyncJs.kt:36` | 确认 A/B 结论没被它带偏 | 低（但要动原生壳，超出本轮范围） |
| 3 | 常亮接上时加 `clearFlags` 兜底 | `WebHostActivity.kt:756-759` / `:769-779` | 常亮是耗电大户 | 低（纯兜底） |
| 4 | （真机测出偏高再考虑）`onPause` 停渲染之外再加 `stopLoading` | `WebHostActivity.kt:758` | 小 | 中（可能打断首屏） |

**明确不要砍**：`oc-perf.js:63` 的 500 ms 采样（20 s 自停）、看门狗、两个 `postDelayed(400)`。

---

## 8. 一句话总结

> **我们还没有真机耗电数字，本轮也不产出。** 我们产出的是：一条命令
> （`battery_real_device.ps1`）、一套可比性铁律（同机/同亮度/同网络/同时长/同交互）、
> 一个保守阈值（**X = 20%，明确标注为估计，来源是 UID CPU 噪声的 2σ 保守取值**），
> 和一份带行号的省电现状盘点（**头号：后台 SSE 不断，`LoopbackProxy.kt:137-155` 常驻 +
> `WebHostActivity.kt:776` 只在 onDestroy 关闭**）。
>
> 拿一台真机、跑两条命令，就能把"省电"从形容词变成数字。
