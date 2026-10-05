# T2 · 真机耗电测量**准备与自检**：把最后一步做成"插上手机跑一行"

> **硬期限 2026-10-07。目标④"耗电"目前唯一没闭环的地方。**
>
> **本轮没有任何耗电数字。** 模拟器无电池模型、无 rail 电流（`BATTERY_HOWTO.md` §4），
> 而真机只能由产品负责人出手。**所以本轮交付的是"把这一步做到最省事 + 自检过"。**
>
> **本轮红线（已逐条守住）**：
> - **未碰任何设备**：本机 `adb devices -l` 全程为空，`emulator/qemu` 进程数 = 0，
>   **本轮没有连接、启动、停止任何真机或模拟器**。唯一碰 adb 的动作是 `adb devices`（纯枚举）。
> - **未起停后端**：4095/4096 在开轮前就已在 `LISTEN`，本轮**全程未动**。
> - **未编任何 mAh 数字**；**未把代理量写成耗电**；**未放宽任何阈值**（X 仍是 20%，
>   推导一字未改，见 `BATTERY_HOWTO.md` §6）。
> - 未碰 `app/**`、`webapp/src/**`、`_upstream/**`、`.github/**`、`research/deliver/**`、别人的报告。
>   写入仅限 `research/perf/**`。

---

## 0. 一句话结论

> **脚本已通过 11/11 项离线自检（语法 0 错、依赖已逐项核对、干跑可用、三种错误路径全是人话），
> 并且自检过程真抓出了一个 bug 并已修掉。**
> 现在只差"插上手机跑两行"。产品负责人要做的事写在 §7，**三行以内**。

| 自检项 | 结论 |
|---|:--:|
| 语法自检 | ✅ `ParseErrors.Count = 0`（解析器 AST 校验，19 参数 / 16 函数全列出） |
| 依赖自检 | ✅ adb 1.0.41 已定位到具体路径；6 个 dumpsys 模块逐个核对；开关清单已核 |
| 干跑 `-DryRun` | ✅ **本轮新加**（原先没有）；无设备下 exit 2 给清晰提示，健康设备 exit 0 |
| 缺参数 | ✅ exit 6，中文用法 + **当场打印该机真实 `adb devices` 输出** |
| 串号不对 | ✅ exit 2，「不在 adb devices 列表里」+ 四种可能原因 |
| 设备未授权 | ✅ exit 2，「当前状态是 'unauthorized'」+ 三种状态各自的含义 |
| **合计** | **11/11 PASS，套件退出码 0** |

复现命令（一行）：

```powershell
pwsh -NoProfile -File research\perf\selfcheck\run_selfcheck.ps1    # 退出码 0 = 全过
```

原始输出目录：`research/perf/out_t2_selfcheck/`（UTF-8，可直接贴）。

---

## 1. 语法自检 —— 原始输出

用 PowerShell 解析器做**静态 AST 校验**（不执行脚本）：

```powershell
$t=$null;$e=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile(
        'research\perf\battery_real_device.ps1',[ref]$t,[ref]$e)
$e.Count
```

摘自 `out_t2_selfcheck/00_syntax.txt`：

```
=== S1 · PowerShell 解析器静态语法校验 ===
解析入口 : [System.Management.Automation.Language.Parser]::ParseFile()
文件     : ...\research\perf\battery_real_device.ps1
大小     : 50448 bytes / 740 行
PowerShell: 7.6.6 (Core)

Tokens 数量        : 6067
String 字面量数量  : 1261
函数定义数         : 16
exit 语句点        : 3

----- ParseErrors -----
ParseErrors.Count  : 0
(none)  =>  语法无错
```

- **16 个函数**：`Say, Die, A, Dump, Grab, NA, GetWinFrame, WPt, RTap, RSwipe, OneCycle,
  PickUnplugBlock, Num, CountBlock, Fmt, CmpRow`。
- **19 个参数**（`Mandatory=False` 全部，因为本轮**故意去掉了 `[Parameter(Mandatory)]`**，改为人话校验，见 §4.1）：

```
  Serial             String               Mandatory=False 默认=（无）
  Package            String               Mandatory=False 默认=（无）
  DryRun             SwitchParameter      Mandatory=False 默认=（无）
  AdbPath            String               Mandatory=False 默认=''
  Minutes            Int32                Mandatory=False 默认=5
  SetBrightness      Int32                Mandatory=False 默认=-1
  AllowEmulator      SwitchParameter      Mandatory=False 默认=（无）
  ...（共 19 个，完整见 00_syntax.txt）
```

**⚠ 顺带修掉自己报告里的一个假阴性**（不修就会写错事实）：
`$ast.ParamBlock.Parameters -contains 'DryRun'` 曾把 `-DryRun` 报成"❌ 没有"。
原因是 `ParameterAst.Name` 是 **`StringConstantExpressionAst` 对象不是字符串**，
拿 AST 对象跟字符串比永远为 `False`。已改为 `ForEach-Object { $_.Name.VariablePath.UserPath }`
再比对。现在 `01_dependencies.txt` 里 `-DryRun : ✅ 已加`。

---

## 2. 依赖自检 —— 原始输出

摘自 `out_t2_selfcheck/01_dependencies.txt`。脚本用到的东西逐个"有没有、在哪"：

### ① `adb`（唯一外部可执行依赖）

```
脚本里的查找顺序（见 battery_real_device.ps1）：
  a) -AdbPath 显式指定（给了就不回退）
  b) %LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
  c) %ANDROID_HOME%\platform-tools\adb.exe
  d) adb.exe（靠 PATH）
本机实测：
  True   C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe
  False  \platform-tools\adb.exe            ← ANDROID_HOME 未设置 ⇒ 展开成空路径
  Get-Command adb (PATH) = <不在 PATH>      ← 候选 d 也必为 False
=> 实际命中 = C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe
adb 版本 = Android Debug Bridge version 1.0.41 | Version 37.0.0-14910828
           | Installed as C:\Users\...\adb.exe | Running on Windows 10.0.28000
```

> ⚠ **本机 adb 不在 PATH**，命中靠候选 b。**别人那台机器若也没有**，脚本会 exit 2 并说明
> 三种装法（`01_dependencies.txt` 原文）。

### ② `adb shell` 侧的 dumpsys 子服务（脚本调用的每一个）

| 模块 | 本机可��？ | 作用 |
|---|:--:|---|
| `battery` | 不可达（无设备） | 电量/插电状态，exit 4 第二道闸 |
| `batterystats` | 不可达（无设备） | **主指标来源**：Discharge / UID CPU / WakeLock / 射频 / Wakeup alarms |
| `power` | 不可达（无设备） | WakeLock 明细 + `mWakefulness` |
| `window` | 不可达（无设备） | `FLAG_KEEP_SCREEN_ON` + 窗口矩形（坐标按真实矩形换算） |
| `cpuinfo` | 不可达（无设备） | UID CPU 时间（**代理量**） |
| `netstats` | 不可达（无设备） | 网络 rx/tx（best-effort，允许 `N/A`） |
| `package` | 不可达（无设备） | `appId/userId` 反查 + `resolve-activity` |

**如实说明**：本机零设备，上表只能验证"命令名存在"，**字段级可用性无法在此验证** ——
这正是本轮加 `-DryRun` 的理由：让真机上的人 3 秒钟确认一次。

### ③ sysfs rail 电流（主指标来源）

- 读法：`for d in /sys/class/power_supply/*; do ... current_now ...`
- **是否需要 root：多数机型需要。本机无设备，无法在此判定。**
- 脚本已降级：读不到 rail **不致命、不退出、不填 0**，改用 batterystats 的 `Discharge` 段，
  并在 `reading.json` 标 `N/A(sysfs-current_now-unreadable)`。

### ④ 开关清单

```
-DryRun   : ✅ 已加（本轮 T2 新增）
-AdbPath  : ✅ 已加（本轮 T2 新增）
-AllowEmulator : ✅ 原有（默认拒绝 emulator-*）
-SetBrightness : ✅ 原有（>=0 才改，结束复原+复核）
-LoopInteraction : ✅ 原有
-DeviceOnly  : ❌ 不存在
   （本轮加的不是它，是等价的 -AdbPath + 按 `adb devices` 整行匹配串号；
     脚本从不碰模拟器，也不需要 -DeviceOnly）
-Backend / -Password : ✅ 原有（只记录；口令永不落盘）
```

### ⑤ 设备与模拟器现状（本轮不碰，仅记录）

```
adb devices -l 原文：
  | List of devices attached          ← 空
emulator/qemu 进程数 = 0
```

---

## 3. 干跑 `-DryRun`

**原先没有干跑能力 ⇒ 本轮新加。**

### 为什么这样设计（关键：干跑与真跑**共用同一段前置代码**，不是两套逻辑）

干跑分支放在 §0 前置硬检查**之后**。含义是：

- 干跑报 **exit 0** ⇒ serial / 授权 / 装包 / Activity / 插电 / 电池模型**全部已过**
  ⇒ 真跑**不会**被设备相关的闸拦下。
- 干跑被某道闸拦下 ⇒ **退出码就是真跑会拿到的那个码**（2/3/4/6）。

干跑**不做**：不起 App、不 `force-stop`、不 `batterystats --reset`、不采样 rail、不开窗口、不写 `reading.json`。
干跑**写**：`out_battery/dryrun_<tag>/DRYRUN.md`（**独立目录，绝不与正式测量目录混用**，
避免半份产物混进 `COMPARE.md`）。

### 3.1 无设备时（真 adb，零设备）—— exit 2，不崩

`out_t2_selfcheck/01_dryrun_no_device.txt`：

```
命令     : pwsh -NoProfile -File battery_real_device.ps1 -DryRun -Serial SELFCHECK-NO-DEVICE -Package com.xiufeigo.opencodeshell.web.debug -Tag selfcheck
实际退出码: 2    期望: 2    => PASS
[09:43:xx] === [0/6] 前置检查 ===

=========== 测量失败（exit 2）===========
  问题：设备 'SELFCHECK-NO-DEVICE' 不在 adb devices 列表里
  原因：没插数据线 / 没开 USB 调试 / 屏幕没解锁没授权 / 串号写错。执行 adb devices 看真实串号再重跑。本脚本不会去 kill-server、也不会动列表里的其它设备。
  本次**没有产出任何耗电数字**（不会用 0 顶替）。
===========================================
```

### 3.2 设备健康时（夹具伪设备）—— exit 0

`out_t2_selfcheck/02_dryrun_stub_ok.txt`：

```
实际退出码: 0    期望: 0    => PASS
[09:43:11] === [0/6] 前置检查 ===
[09:43:11]   设备在线：R5CT30ZZZZZ（adb devices: R5CT30ZZZZZ      device product:SM_R5CT30 ...）
[09:43:11]   被测对象：com.xiufeigo.opencodeshell.web.debug / .../WebHostActivity
[09:43:11]   插电状态：未插电（level=80, temp=300）
[09:43:11]   电池模型：OK（currently on battery = true）
[09:43:11] === [1/1] 干跑体检结论 ===

=========== 干跑通过：这台机器能测（exit 0）===========
| 体检项 | 结果 | 说明 |
| adb 可用 | ✅ | ... |
| 设备在线 | ✅ | R5CT30ZZZZZ |
| App 已安装 | ✅ | com.xiufeigo.opencodeshell.web.debug |
| Activity 可解析 | ✅ | .../WebHostActivity |
| 未插电 | ✅ | dumpsys battery plugged=0 |
| 有电池模型 | ✅ | currently on battery = true（模拟器会在此 exit 4） |
| 电量 / 电池温度 | — | level=80% / 30°C |
| rail 电流可读 | ✅ | 能积分出主指标 mAh |
| 屏幕亮度 | — | brightness=120（0-255，-1=自动）/ mode=0（0=手动，1=自动） |
| 是否常亮 | — | keepScreenOn=no |
| 唤醒状态 | — | Awake |

> 真正开始测量（会 force-stop App、清 batterystats 统计、跑 10 分钟）时，把 -DryRun 去掉即可。
```

### 3.3 可比性提醒只警告、不拦下 —— exit 0

`out_t2_selfcheck/03_dryrun_stub_warn.txt`：夹具设成**自动亮度 + 常亮**，
干跑仍 exit 0，并在"⚠ 不影响跑、但影响可比性"一节给出：
「屏幕亮度是**自动**（brightness=-1, mode=1）⇒ 两轮之间可能自动变亮 ⇒ 不可比」。

---

## 4. 边界自检：三条错误路径是否都是人话

### 4.1 缺参数 —— exit 6

**改动说明**：原先 `-Serial/-Package` 挂了 `[Parameter(Mandatory)]`，PowerShell 的自动报错是
英文的 `Missing an argument for parameter 'Serial'` —— 对"插上手机跑一行"的人**毫无用处**。
**本轮去掉 Mandatory，改为自己校验并打印人话**。新增 **exit 6 = 参数没给全**。

`out_t2_selfcheck/05_err_missing_args.txt`（摘）：

```
实际退出码: 6    期望: 6    => PASS

=========== 跑不起来（exit 6）===========
  缺参数：-Serial（真机串号）、-Package（App 包名）

最短路径（插上手机就能跑的那一行）：
  adb devices -l
  pwsh -NoProfile -File research\perf\battery_real_device.ps1 -Serial <上面第一列的串号> -Package com.xiufeigo.opencodeshell.web.debug -Tag web -Minutes 10

参数：
  -Serial    必填。真机串号 = adb devices -l 的第一列（emulator-* 开头的机器测不了耗电）
  -Package   必填。原生壳 com.xiufeigo.opencodeshell / web 壳 com.xiufeigo.opencodeshell.web.debug
  -Tag       输出子目录名。原生轮用 native、web 轮用 web，两轮都跑完自动出 out_battery\COMPARE.md
  ...（完整参数表）
退出码：0 成功 / 2 设备没连上或状态不对 / 3 App 没装或 Activity 解析不出来 /
        4 无电池模型（模拟器、插电）/ 5 其它测量问题 / 6 参数没给全
  --- 你这台机器上 adb devices 的真实输出（照第一列填 -Serial）---
  （空）—— 一台设备都没连上。三件事挨个查：
    1) 用的是**数据线**不是只充电的线；
    2) 手机上『设置 → 关于手机 → 连点版本号 → 开发者选项 → USB 调试』打开；
    3) 屏幕上弹出『允许 USB 调试』时点『允许』（不点就一直是 unauthorized）。
===========================================
```

> **关键设计**：缺参数时不只给用法，还**当场打印这台机器真实的 `adb devices` 输出** ——
> 用户不用再去猜 `-Serial` 该填什么。

### 4.2 设备序列号不对 —— exit 2

`out_t2_selfcheck/06_err_wrong_serial.txt`：

```
命令     : ... -DryRun -Serial T2-SMOKE-NOT-A-DEVICE -Package com.xiufeigo.opencodeshell.web.debug -Tag selfcheck
实际退出码: 2    期望: 2    => PASS
=========== 测量失败（exit 2）===========
  问题：设备 'T2-SMOKE-NOT-A-DEVICE' 不在 adb devices 列表里
  原因：没插数据线 / 没开 USB 调试 / 屏幕没解锁没授权 / 串号写错。执行 adb devices 看真实串号再重跑。本脚本不会去 kill-server、也不会动列表里的其它设备。
  本次**没有产出任何耗电数字**（不会用 0 顶替）。
```

（沿用 T1 的整行匹配修法，见 §5.2 —— 那个 bug 没有复发。）

### 4.3 设备未授权 —— exit 2

`out_t2_selfcheck/07_err_unauthorized.txt`（夹具把设备列表状态设为 `unauthorized`）：

```
实际退出码: 2    期望: 2    => PASS
=========== 测量失败（exit 2）===========
  问题：设备 'R5CT30ZZZZZ' 当前状态是 'unauthorized'，不是 device
  原因：offline = 数据线接触不良或手机休眠；unauthorized = 屏幕上那个授权框还没点；no permissions = 驱动问题。本脚本不会替你重启 adb 服务。
  本次**没有产出任何耗电数字**（不会用 0 顶替）。
```

### 4.4 附带：另外两道闸也验过了

| 场景 | 退出码 | 原文（摘） |
|---|:--:|---|
| 串号是 `emulator-5554` | 4 | 「串号 'emulator-5554' 是模拟器」/「模拟器没有电池模型、没有 rail 电流，Discharge 恒为 0 mAh」 |
| 设备插着电 `plugged=2` | 4 | 「设备插着电（dumpsys battery plugged=2）」/「测出来的是充电电流不是耗电。**拔掉充电线、只留数据线**」 |

**五道闸（`BATTERY_HOWTO.md` §4）现已全部有可复现的用例覆盖。**

---

## 5. 本轮真抓到的 bug（如实记录）

### 5.1 🔴 真脚本 bug：设备状态判定用了**行尾锚定**，长格式会误判在线真机

`research/perf/selfcheck/adb_stub.ps1`（自检夹具）喂进一条 `adb devices -l` 的**长格式**行：

```
R5CT30ZZZZZ      device product:SM_R5CT30 model:SM_R5CT30 device:ZZ1 transport_id:1
```

脚本随即**误报**：

```
问题：设备 'R5CT30ZZZZZ' 当前状态是 '... transport_id:1'，不是 device
```

**原因**：原判定是 `if ($devRow -notmatch "\sdevice\s*$")` —— `$` 把 `device` **锚在行尾**。
这只在 `adb devices`（短格式：serial + 状态，只有两列）下**碰巧**成立；
一旦这行带 `product:/model:/transport_id:` 等列，**在线的真机会被当成"不是 device"**，
后面装包/电池模型检查一条都进不去。

**已修**（`battery_real_device.ps1:196-208`）：改为取整行的**第二个空白分隔 token**。

```powershell
$devTokens = @($devRow.Trim() -split '\s+')
$devState = if ($devTokens.Count -ge 2) { $devTokens[1] } else { '<该行只有一个字段，状态无法解析>' }
if ($devState -ne 'device') { Die 2 ... }
```

**回归**：短格式（`serial<TAB>device`）与长格式（`-l`）**各跑一个用例，都过** ——
用例 4 / 用例 6。这不是推演，是实跑。

### 5.2 T1 的老 bug 未复发

T1 §4 记过：早先用 `$state -notmatch 'device'` 子串匹配，而 `SMOKE-NOT-A-DEVICE` **含子串
"DEVICE"** ⇒ 不存在的串号被误判成"在线"。本轮用 `T2-SMOKE-NOT-A-DEVICE` 复测，
仍在第一步就被 exit 2 拦下（§4.2），**误判没有复发**。

### 5.3 自检夹具/驱动自己踩的 3 个坑（也如实记）

1. **`switch -Regex` 是子串匹配**：`'dumpsys battery'` 排在 `'dumpsys batterystats'` 前面，
   导致 `currently on battery` 取到**空值**。曾一度看起来像"脚本的闸坏了"。已调整顺序并写进注释。
2. **双引号里的反引号是转义符**：模板里写 `` `research/... `` 里的 `` `r `` 被当成
   **回车符**，把 `research` 劈成两行。已去掉反引号。
3. **`$Args` 是 PowerShell 自动变量**：自检驱动里把形参命名为 `$Args`，
   所有参数被静默吃掉（**每个用例都在传空参数**）。改名为 `$PsArgs`。
   —— 这个坑很值得记：它让 8 个用例**全部假失败**却看不出原因。

---

## 6. 最短路径 / 前置条件 / 产出与判定

**完整版已写进 `BATTERY_HOWTO.md` 新增的 §0「最短路径」**（§1–§8 原有推导一字未改）。要点：

### 最短命令

```powershell
adb devices -l     # 拿串号（第一列；emulator-* 开头测不了耗电）

# 建议先花 3 秒确认这台机能测（不测耗电、不改设备）
pwsh -NoProfile -File research\perf\battery_real_device.ps1 -DryRun -Serial <串号> -Package com.xiufeigo.opencodeshell.web.debug

# web 轮
pwsh -NoProfile -File research\perf\battery_real_device.ps1 -Serial <串号> -Package com.xiufeigo.opencodeshell.web.debug -Tag web -Minutes 10
# 原生轮（同机同亮度同网络同时长同交互）
pwsh -NoProfile -File research\perf\battery_real_device.ps1 -Serial <串号> -Package com.xiufeigo.opencodeshell -Tag native -Minutes 10
```

两轮跑完自动出 **`research/perf/out_battery/COMPARE.md`**。

### 前置条件（两轮必须完全一致）

USB 调试已开**且已授权** · **数据线**（不是只充电线）· **拔掉充电线** ·
亮度**固定手动** · 音量固定 · 同一 WiFi/信号 · 起始电量同一档（建议 80% 上下）·
App 内配好**同一个** backend。

**测多久**：单轮 **10 分钟**，两轮之间**静置 2–3 分钟**回温。
依据：§5.3 最低 2 组约 20 分钟；§6 指出 rail 量化误差折算到整窗约 1–3%、**窗口越短占比越大**；
§5.1 指出电量下降 % 在 5 分钟窗口常常只有 0–1%、分辨率不够。两条命令合计约 25 分钟。

### 产出与判定

产物 `research/perf/out_battery/<tag>/`（`reading.md` / `reading.json` / `rail_samples.csv` /
`batterystats_*.txt` …）+ `COMPARE.md`。

- ✅ **通过**：`COMPARE.md` 可比性判定表**全绿** + 两轮 `valid=true`。
- ✅ **"明显更费电"**：差异 **≥ X = 20%**（§6，标注为估计，**未放宽**）。
- ⚠ **"在测量噪声内，不构成证据"**：差异 **< X**（**不等于"两者一样"**）。
- ❌ **作废**：判定表有红项 / `valid=false`。
- ❌ **不许报百分比**：n=1 时只报绝对值。
- 🔴 **`0 mAh` 不是"很省电"，是"没测到"**（§4 五道闸，本轮已逐闸验证）。

### 失败怎么报回来

**把 `research/perf/out_battery/` 整个目录贴回来即可**（`reading.md`/`COMPARE.md` 在最外层）；
中途失败则贴**报错原文** + **exit 码** + 设备型号/Android 版本 + 手机屏幕当时的状态。

---

## 7. 产品负责人需要做什么（三行以内）

> 1. 手机上打开「开发者选项 → USB 调试」，用**数据线**插到一台 Windows 或装了 adb 的 Mac 上，**拔掉充电线**，亮度调成**手动固定**、起始电量放在 **80% 上下**。
> 2. 先跑一行 `...battery_real_device.ps1 -DryRun -Serial <串号> -Package com.xiufeigo.opencodeshell.web.debug`，看到 **"干跑通过：这台机器能测"** 就说明环境没问题。
> 3. 把 `-DryRun` 换成 `-Tag web -Minutes 10` 跑一轮，再把 `-Package` 换成 `com.xiufeigo.opencodeshell -Tag native -Minutes 10` 跑一轮，然后把 **`research/perf/out_battery/` 整个目录**贴回来。

---

## 8. 文件清单

| 文件 | 性质 |
|---|---|
| `research/perf/battery_real_device.ps1` | 改 · 加 `-DryRun` / `-AdbPath`；缺参数改人话校验（新增 exit 6）；**修设备状态判定 bug** |
| `research/perf/BATTERY_HOWTO.md` | 改 · **仅在顶部新增 §0「最短路径」**，§1–§8 推导未动 |
| `research/perf/BATTERY_READY_T2.md` | 新增 · 本文件 |
| `research/perf/selfcheck/run_selfcheck.ps1` | 新增 · 离线自检驱动（11 用例，退出码 0 = 全过） |
| `research/perf/selfcheck/adb_stub.ps1` | 新增 · **自检夹具**（假 adb，**不联机、不产生任何读数**） |
| `research/perf/out_t2_selfcheck/**` | 原始输出（`00_syntax.txt`、`01_dependencies.txt`、`01`–`09` 各用例、`99_summary.txt`） |

> ⚠ **夹具的性质**：`adb_stub.ps1` 只把**前置检查分支**喂上可控输入，
> **不产出任何耗电数字、不是 AVD、不占端口、不连网络**。
> 由它产生的 `DRYRUN.md` **不得**当作测量结果引用。
