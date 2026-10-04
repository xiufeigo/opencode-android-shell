<#
================================================================================
 battery_real_device.ps1 — OpenCode Android Shell 真机耗电测量（T1 交付件）

 为什么要这个脚本：
   耗电是我们四量里唯一"完全测不到"的一项。模拟器**无电池模型、无 rail 电流**
   （`dumpsys batterystats` 恒为 `currently on battery: false` + `Discharge: 0 mAh`），
   所以 P2/P4 两轮都只能写"测不到"。**我们手上没有真机 ⇒ 不能编数字。**
   这个脚本的唯一目的：**让产品负责人/任何人在一台真机上，一句话跑出可比较的读数。**

 ────────────────────────────────────────────────────────────────────────────
 【怎么跑 —— 3 种场景，任选其一】

 A) 脚本拷到那台 Windows 机器上跑（推荐，输出最全）
      # 原生壳
      pwsh -NoProfile -File .\battery_real_device.ps1 `
        -Serial R5CT30XXXXX -Package com.xiufeigo.opencodeshell `
        -Activity com.xiufeigo.opencodeshell.OcActivity `
        -Minutes 10 -Tag native
      # web 壳（**同机、同亮度、同网络、同交互、同时长**）
      pwsh -NoProfile -File .\battery_real_device.ps1 `
        -Serial R5CT30XXXXX -Package com.xiufeigo.opencodeshell.web.debug `
        -Activity com.xiufeigo.opencodeshell.web.WebHostActivity `
        -Minutes 10 -Tag web
      # 两轮都跑完后，脚本会自动出对比表：research/perf/out_battery/COMPARE.md

 B) 在自己机器上跑，设备用 USB 连着（同一台 PC 即可）
      adb devices           # 先确认 serial 在列表里
      pwsh -NoProfile -File research\perf\battery_real_device.ps1 -Serial <上面那个> -Tag native

 C) 手机上直接跑（Termux，无 PC）—— 逻辑一样，但本脚本是 pwsh，
    手机上请用 `bash` 手工执行等价 6 条命令，顺序照抄本文件 §"流程"：
      adb shell dumpsys batterystats --reset
      adb shell am force-stop <pkg>; adb shell am start -n <pkg>/<act>
      adb shell dumpsys batterystats --charged <pkg>   # 起
      # ...跑 -Minutes 指定的固定交互/静置...
      adb shell dumpsys batterystats --charged <pkg>   # 止
      adb shell "cat /sys/class/power_supply/battery/current_now"   # mA·h 靠它

 ────────────────────────────────────────────────────────────────────────────
 【硬性健壮性承诺 —— 这是 P2/P4 踩过的坑，本脚本专门防它】

   * 设备离线 / adb 没连上        → 明确报错退出（exit 2），**不产出任何数字**
   * App 没装 / Activity 起不来    → 明确报错退出（exit 3）
   * **无电池模型（模拟器/插电）** → 明确报错退出（exit 4），
     并写清"为什么"，**绝不静默给 0 mAh**。
     `0 mAh` 会被误读成"很省电" —— 那是本项目历史上最危险的一个数字。
   * 任何单项解析不到              → 显示 `N/A(未解析)`，**不填 0**
   * 串号像模拟器（emulator-*）    → 默认直接拒绝跑（-AllowEmulator 可放行，但仍会走 exit 4 判定）

 【副作用边界】
   * 允许：`dumpsys batterystats --reset`（只清统计计数，不改 App/系统行为）
   * 默认**不改任何设备设置**。只有显式传 `-SetBrightness <0..255>` 时才会改，
     脚本会先存原值、结束时**复原并复核**，把复原证据写进 restore_check.txt。
   * **不做** `adb kill-server`、**不** force-stop 别的 App、**不**杀任何模拟器、
     **不**卸载/清数据、**不**改开发者选项。
   * 口令 `-Password` 只用于"是否已配置"的记录标记，**永不落盘、永不进日志**。
#>
[CmdletBinding()]
param(
  # ---- 设备与被测对象（必填）-----------------------------------------------
  [Parameter(Mandatory)][string]$Serial,
  [Parameter(Mandatory)][string]$Package,
  [string]$Activity,                       # 省略则用 cmd package resolve-activity 自动解析

  # ---- 测量窗口 ------------------------------------------------------------
  [int]$Minutes = 5,                        # 测量窗口总时长（建议 >= 5，>= 10 更稳）
  [int]$WarmupSec = 25,                     # 预热：起 App 后等界面稳定，不计入窗口
  [double]$SampleSec = 5,                   # rail 电流采样间隔（秒）

  # ---- 可比性控制（脚本会记进 reading.json，用于判定两组是否可比）-----------
  [int]$SetBrightness = -1,                 # >=0 时才改亮度；改完必复原+复核
  [switch]$AllowEmulator,                   # 默认拒绝 emulator-* 串号
  [switch]$LoopInteraction,                 # 默认开：固定交互循环填满整个窗口
  [int]$NScroll = 10,                       # 固定交互：上滑次数（=下滑次数）
  [int]$NTap = 6,                           # 固定交互：点开-返回 次数
  [int]$NSwitch = 5,                        # 固定交互：抽屉/设置 进出次数
  [int]$SettleMs = 450,                     # 每次动作后的稳定等待

  # ---- 运行环境（只记录+校验，不改设备）------------------------------------
  [string]$Backend = '',                    # 例如 http://192.168.1.10:4096 —— 只用于记录与可达性校验
  [string]$Password = '',                   # 只记 password_supplied=true，永不落盘明文
  [string]$Tag = 'run',                     # 输出子目录名：建议用 native / web

  [string]$OutRoot = (Join-Path $PSScriptRoot 'out_battery')
)

$ErrorActionPreference = 'Continue'
$AdbCandidates = @(
  "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
  "$env:ANDROID_HOME\platform-tools\adb.exe",
  "adb.exe"
)
$Adb = $null
foreach ($c in $AdbCandidates) {
  if (Test-Path $c) { $Adb = $c; break }
  if ($c -eq 'adb.exe' -and (Get-Command adb -ErrorAction SilentlyContinue)) { $Adb = 'adb'; break }
}
if (-not $Adb) { Write-Error "FATAL 找不到 adb.exe。请装 Android platform-tools，或把 adb 加进 PATH。"; exit 2 }

$OutDir = Join-Path $OutRoot $Tag
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$Stamp = (Get-Date).ToString('yyyyMMdd_HHmmss')
$T0 = Get-Date
function Say { param($m) Write-Output ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }
function Die { param($code, $msg, $why) Write-Output ""; Write-Output "=========== 测量失败（exit $code）==========="; Write-Output "  问题：$msg"; if ($why) { Write-Output "  原因：$why" }; Write-Output "  本次**没有产出任何耗电数字**（不会用 0 顶替）。"; Write-Output "==========================================="; Write-Output ""; exit $code }
function A { & $Adb -s $Serial @args }
function Dump { param([string]$name, $lines) $p = Join-Path $OutDir "$name"; $lines | Out-File -Encoding utf8 $p; return $p }
function Grab { param($text, $key) if ($text -match ("(?m)^\s*" + [regex]::Escape($key) + ":\s*(.+?)\s*$")) { return $Matches[1].Trim() } return $null }
function NA { param($why) "N/A($why)" }

# =============================================================================
# 0. 前置硬检查：设备 / App / 电池模型 —— 任何一条不过就明确报错退出
# =============================================================================
Say "=== [0/6] 前置检查 ==="

if ($Serial -like 'emulator-*' -and -not $AllowEmulator) {
  Die 4 "串号 '$Serial' 是模拟器" "模拟器没有电池模型、没有 rail 电流，Discharge 恒为 0 mAh。跑它只会产出一个会被误读成'很省电'的 0。真机请用 -Serial <真机串号>。（确实要跑请加 -AllowEmulator，但仍会被下面的电池模型检查拦下）"
}
# 必须按 `adb devices` 的**整行**判定该串号处于 device 状态。
# ⚠ 踩过的坑：早先用 `$state -notmatch 'device'` 判断，而 "SMOKE-NOT-A-DEVICE" 里
#   含子串 "DEVICE" ⇒ 不存在的串号被误判成"在线"，一路走到装包检查才炸。
#   -notmatch 是大小写不敏感的子串匹配，不是整词匹配。
$devList = (A devices 2>&1) -join "`n"
$devRow = ($devList -split "`r?`n" | Where-Object { $_.Trim() -match ("^" + [regex]::Escape($Serial) + "\s") } | Select-Object -First 1)
if (-not $devRow) {
  Die 2 "设备 '$Serial' 不在 adb devices 列表里" "没插数据线 / 没开 USB 调试 / 屏幕没解锁没授权 / 串号写错。执行 adb devices 看真实串号再重跑。本脚本不会去 kill-server、也不会动列表里的其它设备。"
}
if ($devRow -notmatch "\sdevice\s*$") {
  Die 2 "设备 '$Serial' 当前状态是 '$($devRow.Trim())'，不是 device" "offline = 数据线接触不良或手机休眠；unauthorized = 屏幕上那个授权框还没点；no permissions = 驱动问题。本脚本不会替你重启 adb 服务。"
}
Say "  设备在线：$Serial（adb devices: $($devRow.Trim())）"

$pmList = (A shell pm list packages 2>$null) -join "`n"
if ($pmList -notmatch [regex]::Escape("package:$Package")) {
  Die 3 "包 '$Package' 未安装" "串号对了但 App 没装。检查包名拼写，或先安装 debug 包。注意 native 与 web 是两个不同的包名。"
}
$resolvedAct = $Activity
if (-not $resolvedAct) {
  $r = (A shell cmd package resolve-activity --brief $Package 2>$null) -join ' '
  if ($r -match '([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)') { $resolvedAct = $Matches[1] } else { Die 3 "无法解析 '$Package' 的启动 Activity" "请显式传 -Activity <pkg>/<act>。" }
}
Say "  被测对象：$Package / $resolvedAct"

# 电池模型判定（三条互证，缺一不可）
$bat = (A shell dumpsys battery 2>$null) -join "`n"
Dump 'pre_battery.txt' $bat
$plugged = if ($bat -match 'plugged:\s*(\d+)') { $Matches[1] } else { '?' }
$level0 = if ($bat -match 'level:\s*(\d+)') { [int]$Matches[1] } else { -1 }
$temp = if ($bat -match 'temperature:\s*(\d+)') { $Matches[1] } else { '?' }
if ($plugged -ne '0' -and $plugged -ne '?') {
  Die 4 "设备插着电（dumpsys battery plugged=$plugged）" "插电时电源 HAL 不走电池回路，Discharge/current_now 会是 0 或负值，测出来的是充电电流不是耗电。**拔掉充电线、只留数据线（或用无线 adb），等 1 分钟再跑。**"
}
Say "  插电状态：未插电（level=$level0, temp=$temp）"

$bs0 = (A shell dumpsys batterystats 2>$null) -join "`n"
$onBattery = Grab $bs0 'currently on battery'
if ($onBattery -eq 'false') {
  Die 4 "该机 currently on battery: false —— 无电池模型" "这一条是判定真机/模拟器的金标准：模拟器 AVD 的电源 HAL 按'插电'处理，所以所有 Discharge 字段恒为 0、Time on battery 恒为 0ms。Battery capacity 若显示 1000/3000 mAh，那是标称常量不是测量值。**必须换真机。**"
}
Say "  电池模型：OK（currently on battery = $onBattery）"

# rail 电流是否可读（读不到不算致命，但会降级并写进 reading.json）
$railRaw = (A shell 'for d in /sys/class/power_supply/*; do for f in current_now voltage_now capacity current; do [ -r "$d/$f" ] && echo "$(basename $d)/$f=$(cat $d/$f 2>/dev/null)"; done; done' 2>$null) -join "`n"
Dump 'pre_power_supply.txt' $railRaw
$railOk = $railRaw -match 'current_now=(-?\d+)'
if (-not $railOk) { Say "  ⚠ sysfs rail 电流不可读（很多机型需 root）。将退回 batterystats 的 Discharge/level 差值，精度下降但仍可比。" }

Say "=== [1/6] 记录环境指纹（可比性的前提）==="
$fp = @()
$fp += "serial=$Serial"
$fp += "model=$((A shell getprop ro.product.model 2>$null) -join '')"
$fp += "brand=$((A shell getprop ro.product.brand 2>$null) -join '')"
$fp += "release=$((A shell getprop ro.build.version.release 2>$null) -join '')"
$fp += "sdk=$((A shell getprop ro.build.version.sdk 2>$null) -join '')"
$fp += "fingerprint=$((A shell getprop ro.build.fingerprint 2>$null) -join '')"
$fp += "time=$((A shell date 2>$null) -join '')"
$fp += "uptime=$((A shell cat /proc/uptime 2>$null) -join '')"
$fp | Out-File -Encoding utf8 (Join-Path $OutDir "fingerprint.txt")

$brightVal = (A shell settings get system screen_brightness 2>$null) -join ''
$brightMode = (A shell settings get system screen_brightness_mode 2>$null) -join ''
$wakeful = (A shell dumpsys power 2>$null) -join "`n" | Select-String -Pattern 'mWakefulness=(\w+)' | ForEach-Object { $_.Matches[0].Groups[1].Value }
$keepOn = (A shell dumpsys window windows 2>$null) -join "`n" | Select-String -Pattern 'KEEP_SCREEN_ON' -Quiet
$keepOnTxt = if ($keepOn) { 'yes(FLAG_KEEP_SCREEN_ON present)' } else { 'no' }
Say "  亮度=$brightVal (mode=$brightMode)  唤醒=$wakeful  keepScreenOn=$keepOnTxt"
if ($brightVal -match '^-1$' -or $brightMode -eq '1') { Say "  ⚠ 亮度是**自动**（-1/mode=1）⇒ 两轮之间可能自动变亮 ⇒ 不可比。建议先在设置里固定为手动，或用 -SetBrightness 固定。" }

# ---- 亮度：默认不改；显式要求才改，且必定复原 --------------------------------
$origBright = $null; $origBrightMode = $null; $brightChanged = $false
if ($SetBrightness -ge 0) {
  $origBright = $brightVal; $origBrightMode = $brightMode
  A shell settings put system screen_brightness_mode 0 | Out-Null
  A shell settings put system screen_brightness $SetBrightness | Out-Null
  $brightChanged = $true
  $got = (A shell settings get system screen_brightness 2>$null) -join ''
  Say "  已设亮度 $SetBrightness（实测回读 $got），结束时复原原值 '$origBright' / mode '$origBrightMode'"
  if ($got -ne "$SetBrightness") { Die 5 "亮度写入失败" "设备拒绝了 settings put（可能是权限/厂商限制）。请手动在设置里固定亮度，然后不加 -SetBrightness 重跑。" }
}

# ---- Backend / Password：只记录与校验，绝不落盘口令 ---------------------------
$backendReach = 'not_checked'
if ($Backend) {
  try { $r = Invoke-WebRequest -Uri $Backend -Method Head -TimeoutSec 5 -UseBasicParsing; $backendReach = "ok($($r.StatusCode))" }
  catch { $backendReach = "unreachable_from_host($($_.Exception.Message.Split([char]10)[0]))" }
  Say "  Backend=$Backend  从本机探测=$backendReach"
  if ($backendReach -like 'unreachable*') { Say "  ⚠ 本机连不上 Backend。设备可能在别的网段。只要**两轮用的是同一个地址**就不影响可比性，但请在报告里写明。" }
}
$pwSupplied = -not [string]::IsNullOrWhiteSpace($Password)

# =============================================================================
# 1. 固定动作序列（两组必须逐字相同 —— 这是可比性的核心）
# =============================================================================
#  一个 cycle（时长 ≈ 45 s，两组完全一致）：
#    ① 上滑 NScroll 次：窗口相对 (0.50, 0.80) → (0.50, 0.20)，250 ms，间隔 250 ms
#    ② 下滑 NScroll 次：(0.50, 0.20) → (0.50, 0.80)，250 ms，间隔 250 ms
#    ③ 抽屉左进 NSwitch 次：(0.15, 0.50) → (0.15, 0.85) 然后 (0.85, 0.50) → (0.85, 0.15)
#    ④ 点开-返回 NTap 次：点 (0.50, 0.45) → 等 800 ms → KEYCODE_BACK → 等 700 ms → 重新起 Activity
#  窗口坐标全部按 `dumpsys window` 的真实窗口矩形换算，**不写死 1080x2400**。
function GetWinFrame {
  $raw = (A shell "dumpsys window windows | grep -A30 '$Package/'" 2>$null) -join "`n"
  if ($raw -match 'Frames:\s*parent=\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    $l = [int]$Matches[1]; $t = [int]$Matches[2]; $r = [int]$Matches[3]; $b = [int]$Matches[4]
    return [pscustomobject]@{ L = $l; T = $t; W = ($r - $l); H = ($b - $t) }
  }
  $sz = (A shell wm size 2>$null) -join ' '
  $w = 1080; $h = 2400
  if ($sz -match '(\d+)x(\d+)') { $w = [int]$Matches[1]; $h = [int]$Matches[2] }
  return [pscustomobject]@{ L = 0; T = 0; W = $w; H = $h }
}
function WPt { param($f, [double]$fx, [double]$fy) [int]($f.L + $f.W * $fx), [int]($f.T + $f.H * $fy) }
function RTap { param($f, [double]$fx, [double]$fy, [int]$ms = 600) $p = WPt $f $fx $fy; A shell input tap $p[0] $p[1] | Out-Null; Start-Sleep -Milliseconds $ms }
function RSwipe { param($f, [double]$x, [double]$y1, [double]$y2, [int]$ms = 250, [int]$gap = 250)
  $a = WPt $f $x $y1; $b = WPt $f $x $y2
  A shell input swipe $a[0] $a[1] $b[0] $b[1] $ms | Out-Null; Start-Sleep -Milliseconds $gap }
function OneCycle {
  param($f)
  for ($i = 0; $i -lt $NScroll; $i++) { RSwipe $f 0.50 0.80 0.20 }
  for ($i = 0; $i -lt $NScroll; $i++) { RSwipe $f 0.50 0.20 0.80 }
  for ($i = 0; $i -lt $NSwitch; $i++) { RSwipe $f 0.15 0.50 0.85 250 350; RSwipe $f 0.85 0.50 0.15 250 350 }
  for ($i = 0; $i -lt $NTap; $i++) {
    RTap $f 0.50 0.45 800
    A shell input keyevent KEYCODE_BACK | Out-Null; Start-Sleep -Milliseconds 700
    A shell am start -n $resolvedAct | Out-Null; Start-Sleep -Milliseconds 900
  }
  return $f
}

# rail 电流采样：整段窗口后台跑，只落 CSV，不干扰前台交互
$railCsv = Join-Path $OutDir 'rail_samples.csv'
$railTask = $null
if ($railOk) {
  $railTask = Start-Job -ScriptBlock {
    param($serial, $csv, $interval)
    $loop = {
      while ($true) {
        $r = (& adb -s $serial shell 'for d in /sys/class/power_supply/*; do for f in current_now; do [ -r "$d/$f" ] && echo "$(basename $d)=$($d/$f 2>/dev/null || cat $d/$f 2>/dev/null)"; done; done' 2>$null) -join ' '
        "$([DateTimeOffset]::Now.ToUnixTimeMilliseconds()),$r" | Out-File -Encoding utf8 -Append $csv
        Start-Sleep -Seconds $interval
      }
    }
    & $loop
  } -ArgumentList $Serial, $railCsv, ([int]$SampleSec)
  Set-Content -Encoding utf8 $railCsv "epoch_ms,rails"
}

# =============================================================================
# 2. 预热 → 3. 清零 → 4. 窗口内固定交互
# =============================================================================
Say "=== [2/6] 预热 ${WarmupSec}s（等界面稳定/JIT 预热，**不计入**测量窗口）==="
A shell am force-stop $Package | Out-Null
Start-Sleep -Milliseconds 1200
$startOut = (A shell am start -W -n $resolvedAct 2>&1) -join "`n"
Dump 'amstart.txt' $startOut
Start-Sleep -Seconds $WarmupSec
$frame = GetWinFrame
Say "  窗口矩形 = $($frame.W)x$($frame.H) @ ($($frame.L),$($frame.T))"

Say "=== [3/6] 清零统计：dumpsys batterystats --reset ==="
$resetOut = (A shell dumpsys batterystats --reset 2>&1) -join "`n"
Dump 'reset.txt' $resetOut
Start-Sleep -Milliseconds 800

$loopTxt = if ($LoopInteraction) { '循环填满' } else { '跑一轮后静置' }
Say "=== [4/6] 测量窗口 $Minutes min（固定交互：$loopTxt）==="
$devUp0 = ((A shell cat /proc/uptime 2>$null) -join ' ') -split ' ' | Select-Object -First 1
$hostUp0 = ((A shell date +%s 2>$null) -join '').Trim()

$bsStart = (A shell dumpsys batterystats --charged $Package 2>$null) -join "`n"
Dump 'batterystats_start.txt' $bsStart
$cpuStart = (A shell dumpsys cpuinfo 2>$null) -join "`n"
Dump 'cpuinfo_start.txt' $cpuStart
$batStart = (A shell dumpsys battery 2>$null) -join "`n"
Dump 'battery_start.txt' $batStart

$sw = [Diagnostics.Stopwatch]::StartNew()
$cycles = 0
if ($LoopInteraction) {
  while ($sw.Elapsed.TotalMinutes -lt $Minutes) { $frame = OneCycle $frame; $cycles++ ; Say ("  cycle #{0} 完成，累计 {1:N0}s / {2:N0}s" -f $cycles, $sw.Elapsed.TotalSeconds, ($Minutes * 60)) }
} else {
  $frame = OneCycle $frame; $cycles = 1
  $remain = ($Minutes * 60) - $sw.Elapsed.TotalSeconds
  if ($remain -gt 0) { Say ("  交互 {0:N0}s 跑完，余下 {1:N0}s 保持屏幕亮静置" -f $sw.Elapsed.TotalSeconds, $remain); Start-Sleep -Milliseconds ([int]($remain * 1000)) }
}
$sw.Stop()
Say ("  窗口墙钟 = {0:N1} s，固定交互 cycle = {1}" -f $sw.Elapsed.TotalSeconds, $cycles)

# =============================================================================
# 5. 取读数
# =============================================================================
Say "=== [5/6] 取读数 ==="
if ($railTask) { Stop-Job $railTask -ErrorAction SilentlyContinue; Remove-Job $railTask -Force -ErrorAction SilentlyContinue }

$bsEnd = (A shell dumpsys batterystats --charged $Package 2>$null) -join "`n"
Dump 'batterystats_end.txt' $bsEnd
$bsEndFull = (A shell dumpsys batterystats --charged 2>$null) -join "`n"
Dump 'batterystats_end_global.txt' $bsEndFull
$cpuEnd = (A shell dumpsys cpuinfo 2>$null) -join "`n"
Dump 'cpuinfo_end.txt' $cpuEnd
$batEnd = (A shell dumpsys battery 2>$null) -join "`n"
Dump 'battery_end.txt' $batEnd
$wl = (A shell dumpsys power 2>$null) -join "`n"
Dump 'power_wakelocks.txt' $wl
$net = (A shell dumpsys netstats 2>$null) -join "`n"
Dump 'netstats.txt' $net
$railEnd = (A shell 'for d in /sys/class/power_supply/*; do for f in current_now voltage_now capacity current; do [ -r "$d/$f" ] && echo "$(basename $d)/$f=$(cat $d/$f 2>/dev/null)"; done; done' 2>$null) -join "`n"
Dump 'post_power_supply.txt' $railEnd
$devUp1 = ((A shell cat /proc/uptime 2>$null) -join ' ') -split ' ' | Select-Object -First 1

if (-not $bsEnd -or $bsEnd.Length -lt 200) { Die 5 "dumpsys batterystats 返回为空或过短" "设备可能在窗口中途重启/断连/usb 休眠。检查 adb devices，加 -Minutes 5 缩短窗口重跑。不要把这次的空结果当成 0。" }

# ---- 解析 -------------------------------------------------------------------
# 全局段优先用 "since last unplug"（= 拔电以来的电池窗口），这才是耗电窗口
function PickUnplugBlock { param($t) if ($t -match '(?s)Statistics since last unplug:(.*?)(?:Statistics since last charge:|Uid )') { return $Matches[1] } return $t }
$blk = PickUnplugBlock $bsEnd
$blkFull = PickUnplugBlock $bsEndFull

function Num { param($txt, $key) $v = Grab $txt $key; if ($v -match '(-?[\d.]+)') { return [double]$Matches[1] } return $null }
$b = [ordered]@{}
$b['on_battery']          = $(if ($null -ne (Grab $bsEnd 'currently on battery')) { Grab $bsEnd 'currently on battery' } else { NA 'no-field' })
$b['discharge_mah']        = $(if ($null -ne (Num $blk 'Discharge'))    { Num $blk 'Discharge' }    else { NA 'no-Discharge' })
$b['screen_on_mah']        = $(if ($null -ne (Num $blk 'Screen on discharge'))  { Num $blk 'Screen on discharge' }  else { NA 'no-field' })
$b['screen_off_mah']       = $(if ($null -ne (Num $blk 'Screen off discharge')) { Num $blk 'Screen off discharge' } else { NA 'no-field' })
$b['screen_doze_mah']      = $(if ($null -ne (Num $blk 'Screen doze discharge')){ Num $blk 'Screen doze discharge'} else { NA 'no-field' })
$b['capacity_mah']         = $(if ($null -ne (Num $blk 'Estimated battery capacity')) { Num $blk 'Estimated battery capacity' } else { NA 'no-field' })
$b['time_on_battery_ms']   = $(if ($null -ne (Num $blk 'Time on battery')) { Num $blk 'Time on battery' } else { NA 'no-field' })
$b['estimated_power_use']  = $(if ($null -ne (Num $blk 'Estimated power use')) { Num $blk 'Estimated power use' } else { NA 'no-field' })
$b['battery_level_start']  = $(if ($batStart -match 'level:\s*(\d+)') { [int]$Matches[1] } else { -1 })
$b['battery_level_end']    = $(if ($batEnd -match 'level:\s*(\d+)')   { [int]$Matches[1] } else { -1 })
$b['battery_temp_c']       = $(if ($batEnd -match 'temperature:\s*(\d+)') { [math]::Round(([int]$Matches[1]) / 10.0, 1) } else { NA 'no-field' })
$b['battery_health']       = $(if ($batEnd -match 'health:\s*(\w+)') { $Matches[1] } else { NA 'no-field' })
$b['screen_brightness']    = $(if ($brightVal -ne '') { $brightVal } else { NA 'no-field' })
$b['screen_brightness_mode'] = $(if ($brightMode -ne '') { $brightMode } else { NA 'no-field' })
$b['keep_screen_on']       = $keepOnTxt
$b['wakefulness']          = $(if ($wakeful) { ($wakeful | Select-Object -First 1) } else { NA 'no-field' })

# ---- rail 电流 → 积分成 mAh -------------------------------------------------
$rails = [ordered]@{ available = $false; samples = 0; rails = [ordered]@{}; integrated_mah = $null; mean_ma = $null; csv = 'rail_samples.csv' }
if (Test-Path $railCsv) {
  $rows = Get-Content $railCsv | Where-Object { $_ -match '^\d+,' -and $_ -notmatch '^epoch_ms' }
  $rails['samples'] = $rows.Count
  $acc = @{}; $cnt = @{}; $durMs = $null; $prevTs = $null; $sumUA = 0.0; $sumDur = 0.0
  foreach ($r in $rows) {
    $c = $r.Split(',')
    $ts = [double]$c[0]
    if ($null -ne $prevTs) { $sumDur += ($ts - $prevTs) }
    $prevTs = $ts
    for ($i = 1; $i -lt $c.Count; $i++) {
      if ($c[$i] -match '^([^=]+)=(-?\d+)$') {
        $k = $Matches[1]; $v = [double]$Matches[2]
        if (-not $acc.ContainsKey($k)) { $acc[$k] = 0.0; $cnt[$k] = 0 }
        $acc[$k] += $v; $cnt[$k]++
        if ($k -match 'current') { $sumUA += $v }
      }
    }
  }
  if ($acc.Count -gt 0) {
    $rails['available'] = $true
    foreach ($k in $acc.Keys) { $rails['rails'][$k] = [ordered]@{ mean = [math]::Round($acc[$k] / $cnt[$k], 1); n = $cnt[$k] } }
    $rails['integrated_mah'] = [math]::Round(($sumUA / $cnt.Values[0]) * ($sumDur / 3600000.0) / 1000.0, 2)
    $rails['mean_ma'] = [math]::Round(($sumUA / $cnt.Values[0]) / 1000.0, 2)
  }
}
if (-not $rails['available']) { $rails['integrated_mah'] = NA 'sysfs-current_now-unreadable' }

# ---- UID ---------------------------------------------------------------------
$uid = NA 'unresolved'
foreach ($pat in @('appId=(\d+)', 'userId=(\d+)')) {
  $p = (A shell dumpsys package $Package 2>$null | Select-String -Pattern $pat | Select-Object -First 1)
  if ($p) { $uid = $p.Matches[0].Groups[1].Value; break }
}
$u = [ordered]@{}
$u['uid'] = $uid
$u['cpu_ms']          = $(if ($null -ne (Num $blk 'CPU')) { Num $blk 'CPU' } else { NA 'no-CPU-field' })
$u['run_time_ms']     = $(if ($null -ne (Num $blk 'Run time')) { Num $blk 'Run time' } else { NA 'no-field' })
$u['mobile_radio_active_ms'] = $(if ($null -ne (Num $blk 'Mobile radio active time')) { Num $blk 'Mobile radio active time' } else { NA 'no-field' })
$u['wifi_running_ms'] = $(if ($null -ne (Num $blk 'Wifi running time')) { Num $blk 'Wifi running time' } else { NA 'no-field' })
$u['apk_uid_active_ms'] = $(if ($null -ne (Num $blk 'Apk foreground active time')) { Num $blk 'Apk foreground active time' } else { NA 'no-field' })

# ---- WakeLock ----------------------------------------------------------------
$wkl = [ordered]@{ count = 0; held_ms = $null; entries = @(); provenance = '' }
$wLines = $wl -split "`r?`n" | Where-Object { $_ -match 'Wake Locks: Size=(\d+)' }
if ($wLines) { $wkl['size_all_apps'] = [int]([regex]::Match($wLines[0], 'Size=(\d+)').Groups[1].Value) }
$mine = $wl -split "`r?`n" | Where-Object { $_ -match [regex]::Escape($Package) -and $_ -match 'ms=\d+' }
foreach ($m in $mine) {
  $ms = [int][regex]::Match($m, 'ms=(\d+)').Groups[1].Value
  $tag = ($m -replace '\s+', ' ').Trim()
  $wkl['entries'] += [ordered]@{ line = $tag.Substring(0, [math]::Min(160, $tag.Length)); ms = $ms }
}
$wkl['count'] = $wkl['entries'].Count
$wkl['held_ms'] = if ($wkl['count'] -gt 0) { ($wkl['entries'] | Measure-Object -Property ms -Sum).Sum } else { 0 }
$wkl['provenance'] = 'dumpsys power "Wake Locks:" (holder=该包)'

# ---- 网络字节（best-effort）--------------------------------------------------
$netx = [ordered]@{ rx_bytes = NA 'not-parsed'; tx_bytes = NA 'not-parsed'; radio_active_ms = $u['mobile_radio_active_ms']; wifi_running_ms = $u['wifi_running_ms']; note = 'dumpsys netstats 原样已落盘 netstats.txt' }
if ($uid -is [int] -or ($uid -match '^\d+$')) {
  $rx = 0.0; $tx = 0.0; $hit = $false
  foreach ($l in ($net -split "`r?`n")) {
    if ($l -match "uid=$uid\b" -or $l -match "uid=$uid/") {
      $hit = $true
      if ($l -match 'rxBytes=(\d+)') { $rx += [double]$Matches[1] }
      if ($l -match 'txBytes=(\d+)') { $tx += [double]$Matches[1] }
    }
  }
  if ($hit -and ($rx -gt 0 -or $tx -gt 0)) { $netx['rx_bytes'] = $rx; $netx['tx_bytes'] = $tx; $netx['note'] = 'dumpsys netstats (uid 行 rx/tx 累加)' }
}

# ---- Alarm / Wakeup ----------------------------------------------------------
$al = [ordered]@{}
function CountBlock { param($t, $key) if ($t -match ("(?ms)^\s*" + [regex]::Escape($key) + ":\s*\r?\n(.*?)(?=^\s{2}\S|\z)")) { return ($Matches[1] -split "`r?`n" | Where-Object { $_.Trim() -ne '' }).Count } return $null }
$al['all_alarm_lines']      = $(if ($null -ne (CountBlock $blkFull 'Alarms'))       { CountBlock $blkFull 'Alarms' }       else { NA 'no-block' })
$al['all_wakeup_alarm_lines'] = $(if ($null -ne (CountBlock $blkFull 'Wakeup alarms')) { CountBlock $blkFull 'Wakeup alarms' } else { NA 'no-block' })
$al['uid_wakeup_alarms'] = $(if ($uid -match '^\d+$') {
    $m = $blkFull -split "`r?`n" | Where-Object { $_ -match "^\s*$uid\:" -and $_ -match '\+\d' }
    if ($m) { $m.Count } else { 0 } } else { NA 'uid-unresolved' })
$al['uid_wakeup_ms'] = $(if ($uid -match '^\d+$') {
    $tot = 0.0
    foreach ($m in ($blkFull -split "`r?`n" | Where-Object { $_ -match "^\s*$uid\:" -and $_ -match '\+(\d+)([smh])(\d+)ms' })) {
      if ($m -match '\+(\d+)h(\d+)m(\d+)s') { $tot += ([int]$Matches[1] * 3600 + [int]$Matches[2] * 60 + [int]$Matches[3]) * 1000 }
    }
    $tot } else { NA 'uid-unresolved' })

# ---- 有效性最终判定：绝不静默给 0 ------------------------------------------
$invalid = $null
if ($b['on_battery'] -eq 'false') { $invalid = 'batterystats 报 currently on battery=false（无电池模型）' }
if ($b['battery_level_start'] -ge 0 -and $b['battery_level_end'] -ge 0 -and $b['battery_level_start'] -eq $b['battery_level_end'] -and $b['discharge_mah'] -is [double] -and $b['discharge_mah'] -eq 0 -and $rails['integrated_mah'] -is [string]) {
  $invalid = '窗口内电量无下降、Discharge=0、rail 电流不可读 ⇒ 拿不到任何耗电量（这不是"省电"，是"没测到"）'
}

$devUpDelta = $null
if ($devUp0 -and $devUp1) { $devUpDelta = [math]::Round(([double]$devUp1 - [double]$devUp0), 1) }

$reading = [ordered]@{
  tag = $Tag; valid = ($null -eq $invalid); invalid_reason = $invalid
  generated_at = (Get-Date).ToString('s')
  package = $Package; activity = $resolvedAct
  window_minutes = $Minutes; wall_seconds = [math]::Round($sw.Elapsed.TotalSeconds, 1)
  device_uptime_delta_s = $devUpDelta
  interaction_cycles = $cycles; loop_interaction = [bool]$LoopInteraction
  interaction_script = [ordered]@{ n_scroll = $NScroll; n_tap = $NTap; n_switch = $NSwitch; settle_ms = $SettleMs; swipe_ms = 250; gap_ms = 250 }
  backend = $(if ($Backend) { $Backend } else { 'not_recorded' }); backend_reach_from_host = $backendReach
  password_supplied = $pwSupplied
  device = [ordered]@{
    model = (($fp | Where-Object { $_ -like 'model=*' }) -replace '^model=', '')
    release = (($fp | Where-Object { $_ -like 'release=*' }) -replace '^release=', '')
    sdk = (($fp | Where-Object { $_ -like 'sdk=*' }) -replace '^sdk=', '')
    fingerprint = (($fp | Where-Object { $_ -like 'fingerprint=*' }) -replace '^fingerprint=', '')
  }
  battery = $b; rails = $rails; uid = $u; wakelock = $wkl; network = $netx; alarms = $al
  brightness_was_changed_by_script = $brightChanged
}
$reading | ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 (Join-Path $OutDir 'reading.json')

# =============================================================================
# 6. 复原设置 + 出表
# =============================================================================
Say "=== [6/6] 复原设备设置 ==="
$restore = @("restored_at=$((Get-Date).ToString('s'))", "script_changed_brightness=$brightChanged")
if ($brightChanged) {
  A shell settings put system screen_brightness_mode $origBrightMode | Out-Null
  A shell settings put system screen_brightness $origBright | Out-Null
  $rb = (A shell settings get system screen_brightness 2>$null) -join ''
  $rm = (A shell settings get system screen_brightness_mode 2>$null) -join ''
  $ok = ($rb -eq "$origBright" -and $rm -eq "$origBrightMode")
  $restore += "expected_brightness=$origBright"; $restore += "actual_brightness=$rb"
  $restore += "expected_mode=$origBrightMode"; $restore += "actual_mode=$rm"
  $restore += "verify=$ok"
  Say "  亮度复原：期望 '$origBright'/mode '$origBrightMode'，实测 '$rb'/mode '$rm' ⇒ $(if($ok){'OK'}else{'**复原失败，请手动改回**'})"
} else {
  $restore += "note=本脚本未修改任何设备设置（仅 dumpsys batterystats --reset 清统计）"
  Say "  未改动任何设备设置（仅清了 batterystats 统计）"
}
$restore += "note=未执行 adb kill-server；未 force-stop 其他 App；未触碰任何模拟器"
$restore | Out-File -Encoding utf8 (Join-Path $OutDir 'restore_check.txt')

# ---- 单轮 Markdown 表（可直接粘）-------------------------------------------
function Fmt { param($v) if ($null -eq $v) { 'N/A' } elseif ($v -is [double] -or $v -is [int] -or $v -is [long]) { '{0:N2}' -f $v } else { [string]$v } }
$md = @()
$md += "## 真机耗电读数 — $Tag"
$md += ""
$md += "- 时间：$($reading.generated_at)　窗口：$($reading.wall_seconds)s（$Minutes min）　交互 cycle：$cycles"
$md += "- 设备：$($reading.device.model) / Android $($reading.device.release) (SDK $($reading.device.sdk))"
$md += "- 被测：**$Package**"
if ($Backend) { $md += "- Backend：**$Backend**（本机探测：$backendReach）" }
$md += "- 有效性：$(if ($reading.valid) { '✅ 有效' } else { "❌ **无效** — $($reading.invalid_reason)" })"
$md += ""
$md += "| 指标 | 读数 | 单位/来源 |"
$md += "|---|---:|---|"
$md += "| 总耗电 Discharge | $(Fmt $b['discharge_mah']) | mAh（batterystats，since last unplug） |"
$md += "| 屏幕亮时耗电 | $(Fmt $b['screen_on_mah']) | mAh |"
$md += "| 屏幕灭时耗电 | $(Fmt $b['screen_off_mah']) | mAh |"
$md += "| Doze 耗电 | $(Fmt $b['screen_doze_mah']) | mAh |"
$md += "| Estimated power use | $(Fmt $b['estimated_power_use']) | batterystats 相对值 |"
$md += "| rail 积分耗电 | $(Fmt $rails['integrated_mah']) | mAh（current_now 积分，采样 $($rails['samples']) 点） |"
$md += "| 平均电流 | $(Fmt $rails['mean_ma']) | mA |"
if ($rails['rails'].Count -gt 0) { foreach ($k in $rails['rails'].Keys) { $md += "| rail $k 均值 | $(Fmt $rails['rails'][$k].mean) | µA（n=$($rails['rails'][$k].n)） |" } }
$md += "| 电量起→止 | $($b['battery_level_start']) → $($b['battery_level_end']) | %（drop=$(if($b['battery_level_start'] -ge 0 -and $b['battery_level_end'] -ge 0){$b['battery_level_start']-$b['battery_level_end']}else{'N/A'})） |"
$md += "| 电池温度 | $(Fmt $b['battery_temp_c']) | °C（可比性用） |"
$md += "| **UID CPU 时间** | $(Fmt $u['cpu_ms']) | ms |"
$md += "| UID run time | $(Fmt $u['run_time_ms']) | ms |"
$md += "| **WakeLock 数（持有者=本包）** | $($wkl['count']) | 个 |"
$md += "| **WakeLock 持有时长** | $(Fmt $wkl['held_ms']) | ms |"
$md += "| 移动网络射频活跃 | $(Fmt $u['mobile_radio_active_ms']) | ms |"
$md += "| WiFi running | $(Fmt $u['wifi_running_ms']) | ms |"
$md += "| 网络 rx / tx | $(Fmt $netx['rx_bytes']) / $(Fmt $netx['tx_bytes']) | bytes（$($netx['note'])） |"
$md += "| **Wakeup alarms（UID）** | $(Fmt $al['uid_wakeup_alarms']) | 次 |"
$md += "| Wakeup alarms 唤醒时长 | $(Fmt $al['uid_wakeup_ms']) | ms |"
$md += "| 全局 wakeup alarm 条目 | $(Fmt $al['all_wakeup_alarm_lines']) | 条（背景噪音） |"
$md += "| **屏幕亮度** | $($b['screen_brightness']) | 0-255（-1=自动）；mode=$($b['screen_brightness_mode']) |"
$md += "| **是否常亮** | $($b['keep_screen_on']) | dumpsys window |"
$md += "| 唤醒状态 | $($b['wakefulness']) | Awake/Asleep |"
$md += ""
$md += "> 原始输出全部落盘在 research/perf/out_battery/$Tag/ （batterystats / cpuinfo / power wakelocks / netstats / rail_samples.csv / pre_&_post_power_supply）。"
$md += "> 可比性规则与判定阈值见 research/perf/BATTERY_HOWTO.md。"
$mdOut = $md -join "`n"
$mdOut | Out-File -Encoding utf8 (Join-Path $OutDir 'reading.md')
Write-Output ""
Write-Output $mdOut

# ---- 两组都在 ⇒ 自动出对比表 -----------------------------------------------
$nativeP = Join-Path $OutRoot 'native\reading.json'
$webP = Join-Path $OutRoot 'web\reading.json'
if ((Test-Path $nativeP) -and (Test-Path $webP)) {
  $rn = Get-Content $nativeP -Raw | ConvertFrom-Json
  $rw = Get-Content $webP -Raw | ConvertFrom-Json
  $lines = @()
  $lines += "## 真机耗电对比：原生壳 vs web 壳"
  $lines += ""
  $lines += "同机：$($rn.device.model) / $($rw.device.model)　（**必须同机**）"
  $lines += ""
  $lines += "| 指标 | 原生 $Tag(native) | web | 差异 | 可比？ |"
  $lines += "|---|---:|---:|---:|---|"
  # 注意：CmpRow 必须 **返回字符串**。若在函数体内写 `$lines += ...`，
  # PowerShell 会在函数作用域里新建局部副本，父作用域的 $lines 不会变 ⇒ 整张表会丢行。
  function CmpRow { param($label, $nv, $wv, $better)
    $ok = ($nv -is [double] -and $wv -is [double] -and $nv -ne 0)
    $d = if ($ok) { '{0:+0.0;-0.0;0.0}%' -f ((($wv - $nv) / $nv) * 100) } else { 'N/A' }
    $mark = if ($nv -is [double] -and $wv -is [double]) { $better } else { '❌ 不可比(N/A)' }
    return "| $label | $(Fmt $nv) | $(Fmt $wv) | $d | $mark |"
  }
  $lines += CmpRow '总耗电 Discharge (mAh)' $rn.battery.discharge_mah $rw.battery.discharge_mah '✅ 主指标'
  $lines += CmpRow 'rail 积分 (mAh)' $rn.rails.integrated_mah $rw.rails.integrated_mah '✅ 主指标'
  $lines += CmpRow '电量下降 (%)' ($rn.battery.battery_level_start - $rn.battery.battery_level_end) ($rw.battery.battery_level_start - $rw.battery.battery_level_end) '✅ 主指标'
  $lines += CmpRow 'UID CPU (ms)' $rn.uid.cpu_ms $rw.uid.cpu_ms '✅ 次指标'
  $lines += CmpRow 'WakeLock 持有 (ms)' $rn.wakelock.held_ms $rw.wakelock.held_ms '✅ 次指标'
  $lines += CmpRow 'Wakeup alarms (次)' $rn.alarms.uid_wakeup_alarms $rw.alarms.uid_wakeup_alarms '✅ 次指标'
  $lines += CmpRow '射频活跃 (ms)' $rn.uid.mobile_radio_active_ms $rw.uid.mobile_radio_active_ms '✅ 次指标'
  $lines += CmpRow 'WiFi running (ms)' $rn.uid.wifi_running_ms $rw.uid.wifi_running_ms '✅ 次指标'
  $lines += CmpRow '窗口墙钟 (s)' $rn.wall_seconds $rw.wall_seconds '⚠ 必须相等'
  $lines += ""
  $lines += "| 可比性前置 | 原生 | web | 判定 |"
  $lines += "|---|---|---|---|"
  $sameBright = ($rn.battery.screen_brightness -eq $rw.battery.screen_brightness)
  $sameWin = ([math]::Abs($rn.wall_seconds - $rw.wall_seconds) -le 30)
  $lines += "| 屏幕亮度 | $($rn.battery.screen_brightness) | $($rw.battery.screen_brightness) | $(if($sameBright){'✅'}else{'❌ 不可比'}) |"
  $lines += "| 亮度模式 | $($rn.battery.screen_brightness_mode) | $($rw.battery.screen_brightness_mode) | $(if($rn.battery.screen_brightness_mode -eq $rw.battery.screen_brightness_mode){'✅'}else{'❌'}) |"
  $tN = 0.0; $tW = 0.0
  $tNok = [double]::TryParse([string]$rn.battery.battery_temp_c, [ref]$tN)
  $tWok = [double]::TryParse([string]$rw.battery.battery_temp_c, [ref]$tW)
  $tempMark = if ($tNok -and $tWok) { if ([math]::Abs($tN - $tW) -le 3) { '✅ 温差<=3C' } else { '❌ 温差过大，重跑' } } else { 'N/A' }
  $lines += "| 电池温度 | $(Fmt $rn.battery.battery_temp_c) | $(Fmt $rw.battery.battery_temp_c) | $tempMark |"
  $lines += "| 窗口时长 | $(Fmt $rn.wall_seconds)s | $(Fmt $rw.wall_seconds)s | $(if($sameWin){'✅'}else{'❌ 不可比'}) |"
  $lines += "| 交互 cycle | $($rn.interaction_cycles) | $($rw.interaction_cycles) | $(if($rn.interaction_script.n_scroll -eq $rw.interaction_script.n_scroll -and $rn.interaction_script.n_tap -eq $rw.interaction_script.n_tap -and $rn.interaction_script.n_switch -eq $rw.interaction_script.n_switch){'✅ 脚本一致'}else{'❌ 脚本不一致'}) |"
  $lines += "| Backend | $($rn.backend) | $($rw.backend) | $(if($rn.backend -eq $rw.backend){'✅'}else{'⚠'}) |"
  $lines += "| 数据有效性 | $(if($rn.valid){'✅'}else{'❌ '+$rn.invalid_reason}) | $(if($rw.valid){'✅'}else{'❌ '+$rw.invalid_reason}) | $(if($rn.valid -and $rw.valid){'✅'}else{'❌ 不能比'}) |"
  $lines += ""
  $lines += "> 判定阈值（X）与解读规则见 research/perf/BATTERY_HOWTO.md 。**在可比性那一列全绿之前，任何百分比差异都不成立。**"
  $cmp = $lines -join "`n"
  $cmp | Out-File -Encoding utf8 (Join-Path $OutRoot 'COMPARE.md')
  Write-Output ""
  Write-Output "================ 对比表已写出：research/perf/out_battery/COMPARE.md ================"
  Write-Output $cmp
}

Say "DONE tag=$Tag  产物：$OutDir"
