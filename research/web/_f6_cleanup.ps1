$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s = "emulator-6170"
$avd = "OC_Deliver_R2"
$out = "C:\sensorsdata\main\program\opencode-android-shell\research\web\_f6_cleanup.txt"
"" | Set-Content $out
function L($m){ $v=($m|Out-String).TrimEnd(); Write-Output $v; $v | Add-Content $out }

L "=== F6 cleanup $(Get-Date -Format o) ==="

# 1) confirm this emulator is MINE before touching anything
$mine = Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match "-avd\s+$([regex]::Escape($avd))" -and $_.CommandLine -match '-port\s+6170' }
L "MINE_COUNT=$($mine.Count)"
foreach ($p in $mine) { L "  pid=$($p.ProcessId) avd=$avd port=6170 MINE=True" }

# 2) reset wm overrides BEFORE killing (so the AVD is left clean for the next agent)
L "--- wm reset ---"
L (& $adb -s $s shell wm size reset 2>&1)
L (& $adb -s $s shell wm density reset 2>&1)
L (& $adb -s $s shell wm size 2>&1)
L (& $adb -s $s shell wm density 2>&1)

# 3) remove the reverse mapping we added (policy blocks `reverse --remove`; it expires with the device)
L "--- reverse list (4095 restored to real backend earlier) ---"
L (& $adb -s $s reverse --list 2>&1)

# 4) kill ONLY our serial
L "--- emu kill $s ---"
L (& $adb -s $s emu kill 2>&1)
Start-Sleep -Seconds 25
L "post-kill emulator/qemu process count (expect 0):"
L (Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue | Measure-Object).Count
L "OC_*/Medium_Desktop_B process count (expect 0):"
$ours = Get-CimInstance Win32_Process -Filter "Name='emulator.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match '-avd\s+(OC_\S+|Medium_Desktop_B)' }
L ($ours | Measure-Object).Count
foreach ($o in $ours) { L "  STILL RUNNING: pid=$($o.ProcessId) $($o.CommandLine)" }

L "--- ports 6170/6172/6174 (expect none listening) ---"
foreach ($p in 6170,6172,6174) {
  $c = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
  L ("  $p : " + $(if($c){"BUSY pid=$($c[0].OwningProcess)"}else{"FREE"}))
}

L "--- adb devices ---"
L (& $adb devices 2>&1)

L "--- backend 4095 MUST still be alive (we never touched it) ---"
L (& $adb -s $s shell "true" 2>&1)
$c4095 = Get-NetTCPConnection -LocalPort 4095 -State Listen -ErrorAction SilentlyContinue
L ("  4095 LISTEN pid=" + $(if($c4095){$c4095[0].OwningProcess}else{"NONE"}))

L "--- gradle lock ---"
L ("  .agent-gradle-lock exists = " + (Test-Path "C:\sensorsdata\main\program\opencode-android-shell\.agent-gradle-lock"))
L "CLEANUP_DONE"
