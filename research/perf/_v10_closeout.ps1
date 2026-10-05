$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6210"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\perf\out_v10"
$CL=Join-Path $OUT "closeout.txt"
"=== V10 CLOSEOUT $(Get-Date -Format 'yyyy-MM-ddTHH:mm:ss') ===" | Tee-Object -FilePath $CL

"--- pre-close: confirm this instance is MINE ---" | Tee-Object -FilePath $CL -Append
Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue |
  ForEach-Object { $m=if($_.CommandLine -match '-port\s+(\d+)'){$Matches[1]}else{'?'}; $a=if($_.CommandLine -match '-avd\s+(\S+)'){$Matches[1]}else{'?'}; "pid=$($_.ProcessId) avd=$a port=$m MINE=$($a -match '^OC_')" } | Tee-Object -FilePath $CL -Append

"--- wm size/density reset ---" | Tee-Object -FilePath $CL -Append
(& $adb -s $S shell wm size reset 2>&1) | Out-String | Tee-Object -FilePath $CL -Append
(& $adb -s $S shell wm density reset 2>&1) | Out-String | Tee-Object -FilePath $CL -Append
Start-Sleep -Seconds 3
"RESET_READBACK_SIZE: $(((& $adb -s $S shell wm size 2>&1)|Out-String) -replace "`r`n",' | ')" | Tee-Object -FilePath $CL -Append
"RESET_READBACK_DENSITY: $(((& $adb -s $S shell wm density 2>&1)|Out-String) -replace "`r`n",' | ')" | Tee-Object -FilePath $CL -Append

"--- adb reverse --remove (only tcp:4095, mine) ---" | Tee-Object -FilePath $CL -Append
(& $adb -s $S reverse --remove tcp:4095 2>&1) | Out-String | Tee-Object -FilePath $CL -Append
(& $adb -s $S forward --remove-all 2>&1) | Out-String | Tee-Object -FilePath $CL -Append
"REVERSE_LIST_AFTER: $(((& $adb -s $S reverse --list 2>&1)|Out-String) -replace "`r`n",' | ')" | Tee-Object -FilePath $CL -Append

"--- emu kill (only emulator-6210) ---" | Tee-Object -FilePath $CL -Append
(& $adb -s $S emu kill 2>&1) | Out-String | Tee-Object -FilePath $CL -Append
Start-Sleep -Seconds 20
"--- verify: emulator/qemu procs ---" | Tee-Object -FilePath $CL -Append
$procs = Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue
if($procs){ $procs | ForEach-Object { $a=if($_.CommandLine -match '-avd\s+(\S+)'){$Matches[1]}else{'?'}; "STILL: pid=$($_.ProcessId) avd=$a" } | Tee-Object -FilePath $CL -Append } else { "emulator/qemu proc count = 0" | Tee-Object -FilePath $CL -Append }
"OC_*/Medium_Desktop_B count = $((($procs | Where-Object { $_.CommandLine -match '-avd\s+(OC_\S+|Medium_Desktop_B)' }) | Measure-Object).Count)" | Tee-Object -FilePath $CL -Append
"--- verify: port 6210 ---" | Tee-Object -FilePath $CL -Append
foreach($pt in 6210,6212,6214){ $c=Get-NetTCPConnection -LocalPort $pt -State Listen -ErrorAction SilentlyContinue; "port $pt : $(if($c){"LISTEN pid=$($c.OwningProcess)"}else{'FREE'})" | Tee-Object -FilePath $CL -Append }
"--- verify: adb devices ---" | Tee-Object -FilePath $CL -Append
((& $adb devices 2>&1)|Out-String) | Tee-Object -FilePath $CL -Append
"--- backend 4095 (Lead's, must stay up) ---" | Tee-Object -FilePath $CL -Append
$b=Get-NetTCPConnection -LocalPort 4095 -State Listen -ErrorAction SilentlyContinue
if($b){ "4095 STILL LISTEN pid=$($b[0].OwningProcess) (untouched by me)" | Tee-Object -FilePath $CL -Append } else { "4095 NOT LISTENING" | Tee-Object -FilePath $CL -Append }
"gradle lock exists = $(Test-Path 'C:\sensorsdata\main\program\opencode-android-shell\.agent-gradle-lock')" | Tee-Object -FilePath $CL -Append
"=== CLOSEOUT DONE (no adb kill-server, no -a, no wildcard kill) ===" | Tee-Object -FilePath $CL -Append
