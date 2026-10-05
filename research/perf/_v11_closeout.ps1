$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6230"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\perf\out_v11"
$RAW="$OUT\closeout.txt"
function L($m){ Add-Content -Path $RAW -Value $m -Encoding utf8; Write-Output $m }
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }

L "=== V11 CLOSEOUT $(Get-Date -Format 'yyyy-MM-ddTHH:mm:ss') ==="
L "--- 关闭前确认归属（housekeeping 规则 2：按 AVD 名判定）---"
$mine = Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match '-avd\s+(OC_\S+|Medium_Desktop_B)' -and $_.CommandLine -match '6230' }
foreach($p in $mine){ L "MINE pid=$($p.ProcessId) avd=$(([regex]::Match($p.CommandLine,'-avd\s+(\S+)')).Groups[1].Value) port=6230 MINE=True" }
L "MINE_COUNT=$($mine.Count)"
$b = Get-NetTCPConnection -LocalPort 4095 -State Listen -ErrorAction SilentlyContinue
L "4095_BEFORE: $(if($b){"LISTEN pid=$($b.OwningProcess)"}else{'NOT LISTEN'}) (Lead's; never touched by me)"

L "--- wm size/density reset ---"
L "RESET_SIZE: $(A @('shell','wm','size','reset'))"
L "RESET_DENSITY: $(A @('shell','wm','density','reset'))"
Start-Sleep -Seconds 3
L "RESET_READBACK_SIZE: $(A @('shell','wm','size'))"
L "RESET_READBACK_DENSITY: $(A @('shell','wm','density'))"

L "--- adb reverse 清理（仅 tcp:4095）---"
L "REVERSE_LIST_BEFORE: $(A @('reverse','--list'))"
L "REVERSE_REMOVE: $(A @('reverse','--remove','tcp:4095'))"
L "REVERSE_LIST_AFTER: $(A @('reverse','--list'))"

L "--- emu kill（仅 emulator-6230，按 serial）---"
L "EMU_KILL: $(A @('emu','kill'))"
Start-Sleep -Seconds 25
L "--- 复核（kill 后 25s）---"
$procs = Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue
L "emulator/qemu proc count = $($procs.Count)"
foreach($p in $procs){ L "  RESIDUAL_PROC pid=$($p.ProcessId) cmd=$($p.CommandLine)" }
$ours = $procs | Where-Object { $_.CommandLine -match '-avd\s+(OC_\S+|Medium_Desktop_B)' }
L "OC_*/Medium_Desktop_B residual = $($ours.Count)"
foreach($pn in 6230,6232,6234){
  $busy = Get-NetTCPConnection -LocalPort $pn -State Listen -ErrorAction SilentlyContinue
  L "port $pn : $(if($busy){"LISTEN pid=$($busy.OwningProcess)"}else{'FREE'})"
}
L "adb devices: $((& $adb devices 2>&1) -join ' / ')"
$b2 = Get-NetTCPConnection -LocalPort 4095 -State Listen -ErrorAction SilentlyContinue
L "4095_AFTER: $(if($b2){"LISTEN pid=$($b2.OwningProcess)"}else{'NOT LISTEN'})  <- same pid as before = backend never started/stopped by me"
L "gradle lock exists = $(Test-Path 'C:\sensorsdata\main\program\opencode-android-shell\.agent-gradle-lock')"
L "=== 未执行 adb kill-server / 未用 -a / 未用通配符 kill / 只按 serial 关 ==="
L "=== CLOSEOUT DONE ==="
