$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6250"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_v12"
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $m; Add-Content -Path "$OUT\closeout.log" -Value $s -Encoding utf8; Write-Output $s }

L "########## CLOSEOUT (housekeeping) ##########"
# 0) who do I own
$procs = Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue
L "BEFORE emulator/qemu procs = $(($procs|Measure-Object).Count)"
$mine = @()
foreach($p in $procs){ if($p.CommandLine -match '-avd\s+(OC_\S+|Medium_Desktop_B)'){ $mine += $p } }
foreach($p in $mine){ $avd=([regex]::Match($p.CommandLine,'-avd\s+(\S+)')).Groups[1].Value; $port=([regex]::Match($p.CommandLine,'-port\s+(\d+)')).Groups[1].Value
  L "MINE pid=$($p.Id) avd=$avd port=$port" }
L "MINE_COUNT=$($mine.Count)"

# 1) backend untouched check
$b = Get-NetTCPConnection -LocalPort 4095 -State Listen -ErrorAction SilentlyContinue
L "BACKEND_4095: $(if($b){"LISTEN pid=$($b.OwningProcess)"}else{'NOT LISTEN'})"

# 2) wm reset
L "WM_SIZE_RESET: $((& $adb -s $S shell wm size reset) -join ' ')"
L "WM_DENSITY_RESET: $((& $adb -s $S shell wm density reset) -join ' ')"
L "WM_SIZE_AFTER: $((& $adb -s $S shell wm size) -join ' | ')"
L "WM_DENSITY_AFTER: $((& $adb -s $S shell wm density) -join ' | ')"

# 3) emu kill (only my own serial)
L "EMU_KILL: $((& $adb -s $S emu kill) -join ' ')"
Start-Sleep -Seconds 25
L "VERIFY adb devices: $((& $adb devices) -join ' / ')"
$procs2 = Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue
L "VERIFY emulator/qemu procs = $(($procs2|Measure-Object).Count)"
$mine2 = @(); foreach($p in $procs2){ if($p.CommandLine -match '-avd\s+(OC_\S+|Medium_Desktop_B)'){ $mine2 += $p } }
foreach($p in $mine2){ L "VERIFY_RESIDUAL pid=$($p.Id) cmd=$($p.CommandLine)" }
L "VERIFY OC_*/Medium_Desktop_B residual = $($mine2.Count)"
foreach($pn in 6250,6252,6254){ $c=Get-NetTCPConnection -LocalPort $pn -State Listen -ErrorAction SilentlyContinue; L "VERIFY port ${pn}: $(if($c){"LISTEN pid=$($c.OwningProcess)"}else{'FREE'})" }
L "VERIFY 4095 backend still: $(if($b2=Get-NetTCPConnection -LocalPort 4095 -State Listen -ErrorAction SilentlyContinue){"LISTEN pid=$($b2.OwningProcess)"}else{'NOT LISTEN'})"
L "LOCK .agent-gradle-lock exists = $(Test-Path .agent-gradle-lock)"
L "NOTE: no adb kill-server, no -a, no wildcard kill. Only serial emulator-6250."
L "=== CLOSEOUT DONE ==="
