$ErrorActionPreference = 'Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$OUT = "C:\sensorsdata\main\program\opencode-android-shell\research\web\out_f10"
$log = "$OUT\closeout.log"
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m; Add-Content -Path $log -Value $s -Encoding utf8; Write-Output $s }

$S = "emulator-6270"
L "=== F10 closeout (who started, who closes: OC_Deliver_R2 / emulator-6270 / port 6270) ==="
$mine = Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match '-avd\s+OC_Deliver_R2' -and $_.CommandLine -match '-port\s+6270' }
L "BEFORE emulator/qemu procs = $((Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue | Measure-Object).Count)"
foreach ($p in $mine) { L "MINE pid=$($p.ProcessId) avd=OC_Deliver_R2 port=6270 MINE=True" }
L "MINE_COUNT=$(@($mine).Count)"
$b = Get-NetTCPConnection -LocalPort 4095 -State Listen -ErrorAction SilentlyContinue
L "BACKEND_4095: $(if($b){"LISTEN pid=$($b.OwningProcess)"}else{'NOT LISTEN'}) (never started/stopped by me)"

# reverse removal
L "REVERSE_BEFORE: $((& $adb -s $S reverse --list 2>&1) -join ' / ')"
& $adb -s $S reverse --remove tcp:4095 2>&1 | Out-Null
L "REVERSE_AFTER: $((& $adb -s $S reverse --list 2>&1) -join ' / ')"

# wm reset
& $adb -s $S shell wm size reset 2>&1 | Out-Null
& $adb -s $S shell wm density reset 2>&1 | Out-Null
Start-Sleep -Seconds 2
L "WM_SIZE_AFTER: $((& $adb -s $S shell wm size 2>&1) -join ' | ')"
L "WM_DENSITY_AFTER: $((& $adb -s $S shell wm density 2>&1) -join ' | ')"

# kill by serial only
L "EMU_KILL: $((& $adb -s $S emu kill 2>&1) -join ' ')"
Start-Sleep -Seconds 25
$after = (Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue | Measure-Object).Count
L "VERIFY emulator/qemu procs = $after"
$res = Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match '-avd\s+(OC_\S+|Medium_Desktop_B)' }
L "VERIFY OC_*/Medium_Desktop_B residual = $(@($res).Count)"
foreach ($r in $res) { L "  RESIDUAL pid=$($r.ProcessId) cmd=$($r.CommandLine)" }
foreach ($p in 6270,6272,6274) { $c = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue; L "VERIFY port ${p}: $(if($c){"LISTEN pid=$($c.OwningProcess)"}else{'FREE'})" }
L "VERIFY adb devices: $((& $adb devices 2>&1) -join ' / ')"
$b2 = Get-NetTCPConnection -LocalPort 4095 -State Listen -ErrorAction SilentlyContinue
L "VERIFY 4095 backend still: $(if($b2){"LISTEN pid=$($b2.OwningProcess)"}else{'NOT LISTEN'})"
L "LOCK .agent-gradle-lock exists = $(Test-Path 'C:\sensorsdata\main\program\opencode-android-shell\.agent-gradle-lock')"
L "=== F10 closeout done ==="
