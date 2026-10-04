# p4_close.ps1 — P4 收尾：① 临时起一台做**耗电可测性取证** ② **关掉自己起的模拟器** ③ 抄复核输出。
# 纪律（research/HOUSEKEEPING.md）：
#   - 谁起谁关：只关 `OC_Perf_Test`（AVD 名判定），serial emulator-5830，端口 5830
#   - 禁 `adb kill-server`、禁 `-a`/通配符杀进程、禁碰非 `OC_*` 实例
$ErrorActionPreference = 'Continue'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$perf = $PSScriptRoot
$out  = "$perf\out_p4"
$dev='emulator-5830'; $port=5830; $avd='OC_Perf_Test'
$log = New-Object System.Collections.Generic.List[string]
function Note { param($m) $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Output $s; $log.Add($s)
  ($log -join "`n") | Out-File -Encoding utf8 "$out\_closeout.txt" }

# ---------- 关掉前：先记录"我要关的是什么" ----------
Note "=== [A] 关停前基线：当前全部 emulator 进程 ==="
Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue |
  ForEach-Object { $a=''; if($_.CommandLine -match '-avd\s+(\S+)'){$a=$Matches[1]}; $p=''; if($_.CommandLine -match '-port\s+(\d+)'){$p=$Matches[1]}
    Note ("  pid=$($_.ProcessId) name=$($_.Name) avd='$a' port='$p'") }
Note "=== 端口 5830 关停前监听数 = $((Get-NetTCPConnection -LocalPort 5830 -State Listen -ErrorAction SilentlyContinue | Measure-Object).Count) ==="

# ---------- ① 耗电取证（需要一台活设备） ----------
Note "=== [B] 耗电可测性取证（临时起 $avd） ==="
$st = (& $adb -s $dev shell getprop sys.boot_completed 2>$null | Out-String).Trim()
if ($st -ne '1') {
  $p = Start-Process -FilePath "$sdk\emulator\emulator.exe" `
    -ArgumentList "-avd $avd -port $port -no-snapshot-load -no-boot-anim -no-audio -gpu swiftshader_indirect -no-metrics" `
    -RedirectStandardOutput "$perf\_emu_close.log" -RedirectStandardError "$perf\_emu_close.log.err" -PassThru -WindowStyle Hidden
  Note "  EMU_PID=$($p.Id) (取证用，随后立即关掉)"
  $dl=(Get-Date).AddSeconds(150); $ok=$false
  while((Get-Date) -lt $dl){ Start-Sleep -Seconds 3
    $t=(& $adb -s $dev shell getprop sys.boot_completed 2>$null | Out-String).Trim()
    if($t -eq '1'){ $ok=$true; break }
    if($p.HasExited){ break } }
  Note "  boot_for_probe=$ok"
} else { Note "  device already up, reuse" }

if ($ok -or $st -eq '1') {
  Start-Sleep -Seconds 3
  Note "  --- dumpsys battery ---"
  (& $adb -s $dev shell dumpsys battery) | ForEach-Object { Note "    $_" }
  Note "  --- batterystats: 电流/放电相关行 ---"
  $b = (& $adb -s $dev shell dumpsys batterystats) -join "`n"
  $hits = [regex]::Matches($b, '(?m)^.*(Discharge|current_now|charge counter|ENERGY_COUNTER).*$') | ForEach-Object { $_.Value.Trim() }
  if ($hits) { $hits | Select-Object -First 8 | ForEach-Object { Note "    $_" } } else { Note "    (无 Discharge / current_now 读数)" }
  Note "  --- /sys/class/power_supply/ ---"
  $ps = (& $adb -s $dev shell 'ls /sys/class/power_supply/ 2>/dev/null') -join ' '
  Note "    '$ps'"
  $cur = (& $adb -s $dev shell 'ls /sys/class/power_supply/*/current_now 2>/dev/null') -join ' '
  Note "    current_now files: '$cur'"
  $cap = (& $adb -s $dev shell 'ls /sys/class/power_supply/battery/capacity 2>/dev/null') -join ' '
  Note "    capacity node: '$cap'"
  Note "  --- hw 事实 ---"
  (& $adb -s $dev shell getprop ro.hardware) | ForEach-Object { Note "    ro.hardware=$_" }
  (& $adb -s $dev shell getprop ro.kernel.qemu) | ForEach-Object { Note "    ro.kernel.qemu=$_" }
} else { Note "  !! 设备起不来，耗电取证放弃" }

# ---------- ② 关掉自己起的模拟器 ----------
Note "=== [C] 关停 $avd / $dev ==="
$st = (& $adb -s $dev shell getprop sys.boot_completed 2>$null | Out-String).Trim()
if ($st -eq '1') {
  Note ("  adb -s $dev emu kill -> " + ((& $adb -s $dev emu kill) -join ' '))
  Start-Sleep -Seconds 8
} else {
  Note "  设备已不在（被外部 kill），本轮实例已消失；仍做进程/端口复核"
}
# 兜底：只按 **AVD 名** 匹配本轮的 OC_* 进程（非 OC_* 一律不碰）
$deadline=(Get-Date).AddSeconds(45)
while((Get-Date) -lt $deadline){
  $mine = Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match '-avd\s+OC_Perf_Test' }
  if(-not $mine){ break }
  Start-Sleep -Seconds 3
}
$mine2 = Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match '-avd\s+OC_Perf_Test' }
Note ("  残留 OC_Perf_Test 进程数 = " + (@($mine2).Count))

# ---------- ③ 复核输出 ----------
Note "=== [D] 复核 1：进程（应 0 残留 OC_Perf_Test）==="
Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue |
  ForEach-Object { $a=''; if($_.CommandLine -match '-avd\s+(\S+)'){$a=$Matches[1]}; $p=''; if($_.CommandLine -match '-port\s+(\d+)'){$p=$Matches[1]}
    Note ("  pid=$($_.ProcessId) name=$($_.Name) avd='$a' port='$p'  <== $(if($a -eq 'OC_Perf_Test'){'本轮的，异常！'}else{'别人的，未碰'})") }
Note "=== [E] 复核 2：端口 5830 监听（应为空）==="
$c = Get-NetTCPConnection -LocalPort 5830 -State Listen -ErrorAction SilentlyContinue
Note ("  Get-NetTCPConnection -LocalPort 5830 -State Listen => " + $(if($c){"仍有 $((($c|Measure-Object).Count)) 条监听，异常！"}else{"<空>，端口已释放"}))
Note "=== [F] 复核 3：adb devices（不应再有 emulator-5830）==="
(& $adb devices) | ForEach-Object { Note "    $_" }
Note "  (未执行 adb kill-server；未使用 -a / 通配符；非 OC_* 实例一次都没碰)"
Note "=== CLOSEOUT DONE ==="
