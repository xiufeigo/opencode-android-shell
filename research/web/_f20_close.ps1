# _f20_close.ps1 - F20 收尾：只关本轮自建的 emulator-6450 (OC_Deliver_R2)，抄复核输出
$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$D="emulator-6450"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web"
function L { param($m) $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Output $s; Add-Content -Path "$OUT\_f20_closeout.log" -Value $s -Encoding utf8 }

L "=== BEFORE kill ==="
L ("adb devices: " + ((& $adb devices) -join ' | '))
Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
  $avd=if($_.CommandLine -match '-avd\s+(\S+)'){$Matches[1]}else{'(no -avd)'}
  $port=if($_.CommandLine -match '-port\s+(\d+)'){$Matches[1]}else{'?'}
  $mine = if($_.CommandLine -match '-avd\s+(OC_\S+|Medium_Desktop_B)'){'MINE=True'}else{'MINE=False'}
  L "  pid=$($_.ProcessId) avd=$avd port=$port $mine"
}
L "=== geometry reset (before kill) ==="
& $adb -s $D shell wm size reset 2>&1 | ForEach-Object { L "  wm size reset: $_" }
& $adb -s $D shell wm density reset 2>&1 | ForEach-Object { L "  wm density reset: $_" }
Start-Sleep -Seconds 2
& $adb -s $D shell wm size 2>&1 | ForEach-Object { L "  $($_.ToString().Trim())" }
& $adb -s $D shell wm density 2>&1 | ForEach-Object { L "  $($_.ToString().Trim())" }
L "=== reverse remove (only ours) ==="
& $adb -s $D reverse --remove tcp:4095 2>&1 | ForEach-Object { L "  reverse rm 4095: $_" }
L ("  reverse --list after: '" + ((& $adb -s $D reverse --list) -join ' | ') + "'")
L "=== emu kill (ONLY $D) ==="
& $adb -s $D emu kill 2>&1 | ForEach-Object { L "  emu kill: $_" }
Start-Sleep -Seconds 25
L "=== AFTER: 复核 ==="
$all = Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue
L ("  emulator/qemu process count = " + ($all | Measure-Object).Count)
$res = $all | Where-Object { $_.CommandLine -match '-avd\s+(OC_\S+|Medium_Desktop_B)' }
L ("  OC_*/Medium_Desktop_B residual = " + ($res | Measure-Object).Count)
$all | ForEach-Object {
  $avd=if($_.CommandLine -match '-avd\s+(\S+)'){$Matches[1]}else{'(no -avd)'}
  $port=if($_.CommandLine -match '-port\s+(\d+)'){$Matches[1]}else{'?'}
  L "  remaining: pid=$($_.ProcessId) avd=$avd port=$port"
}
foreach($p in 6450,6452,6454,6456,6458,6460){
  $c=Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
  L ("  port {0} LISTEN = {1}" -f $p, $(if($c){$c.Count}else{0}))
}
L ("  adb devices now: " + ((& $adb devices) -join ' | '))
$b=Get-NetTCPConnection -LocalPort 4095 -State Listen -ErrorAction SilentlyContinue
L ("  backend 4095 LISTEN = " + $(if($b){"yes pid=$($b[0].OwningProcess)"}else{'NO'}))
L ("  .agent-gradle-lock exists = " + (Test-Path "C:\sensorsdata\main\program\opencode-android-shell\.agent-gradle-lock"))
L "=== closeout done (未执行 adb kill-server；未用 -a/通配符；只按 serial) ==="
