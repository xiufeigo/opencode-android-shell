$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s = "emulator-6170"
$out = "C:\sensorsdata\main\program\opencode-android-shell\research\web\_f6_devprep.txt"
"=== F6 device prep $s $(Get-Date -Format o) ===" | Set-Content $out

function L($m) { $m | Tee-Object -FilePath $out -Append }

L ("serial_state=" + ((& $adb -s $s get-state) 2>&1))
L "--- geometry (wm, no -skin: pixel_tablet is not a legal skin name) ---"
L (& $adb -s $s shell wm size 2>&1)
L (& $adb -s $s shell wm density 2>&1)
& $adb -s $s shell wm size 1080x2400 | Out-Null
& $adb -s $s shell wm density 420 | Out-Null
Start-Sleep -Seconds 3
L "--- after override ---"
L (& $adb -s $s shell wm size 2>&1)
L (& $adb -s $s shell wm density 2>&1)

L "--- backend alive on host 4095 (do NOT stop it) ---"
try {
  $r = Invoke-WebRequest -Uri "http://127.0.0.1:4095/api/info" -TimeoutSec 5
  L "HOST_NOAUTH=$($r.StatusCode)"
} catch { L "HOST_NOAUTH_ERR=$($_.Exception.Response.StatusCode.value__)" }
$tok = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("opencode:opencode"))
try {
  $r2 = Invoke-WebRequest -Uri "http://127.0.0.1:4095/api/info" -Headers @{Authorization="Basic $tok"} -TimeoutSec 5
  L "HOST_AUTH_STATUS=$($r2.StatusCode)"
  L "HOST_AUTH_BODY=$($r2.Content)"
} catch { L "HOST_AUTH_ERR=$($_.Exception.Response.StatusCode.value__)" }

L "--- adb reverse (correct port) ---"
& $adb -s $s reverse tcp:4095 tcp:4095 | Out-Null
L (& $adb -s $s reverse --list 2>&1)

L "--- install release apk ---"
$apk = "C:\sensorsdata\main\program\opencode-android-shell\research\deliver\opencode-shell-web-release-nor8.apk"
L ("installing from (may be stale until we copy): " + $apk)
L "PREP_DONE"
