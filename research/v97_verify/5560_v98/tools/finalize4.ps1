$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$root = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5560_v98"
$ev = "$root\ev4"
$probe = "$root\tools\probe4.ps1"
$pkg = "com.xiufeigo.opencodeshell"

Write-Host "=== FINALIZE: restore defaults ==="
& $adb -s emulator-5560 shell am force-stop $pkg
Start-Sleep -Seconds 2
& $adb -s emulator-5560 shell wm size reset
& $adb -s emulator-5560 shell wm density reset
Start-Sleep -Seconds 3
Write-Host "--- wm readback ---"
(& $adb -s emulator-5560 shell wm size; & $adb -s emulator-5560 shell wm density) | Out-File "$ev\Z1_wm_reset.txt" -Encoding utf8
Get-Content "$ev\Z1_wm_reset.txt"

& $adb -s emulator-5560 shell run-as $pkg sed -i "s/vertical/horizontal/" shared_prefs/oc_settings.xml
Start-Sleep -Seconds 1
(& $adb -s emulator-5560 shell run-as $pkg cat shared_prefs/oc_settings.xml) | Out-File "$ev\Z2_prefs.txt" -Encoding utf8
Write-Host "--- tabLayout readback ---"
Get-Content "$ev\Z2_prefs.txt" | Select-String "tabLayout"

Write-Host "--- cold start, verify default form ---"
& $adb -s emulator-5560 shell am force-stop $pkg
Start-Sleep -Seconds 2
& $adb -s emulator-5560 logcat -c
& $adb -s emulator-5560 shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 10
& $probe -Tag "Z3_final_default" -SettleSec 3
(& $adb -s emulator-5560 logcat -d 2>&1 | Select-String -Pattern "FATAL|AndroidRuntime|ANR in") | Out-File "$ev\Z4_logcat.txt" -Encoding utf8
Write-Host "--- logcat FATAL/ANR count ---"
(Get-Content "$ev\Z4_logcat.txt" | Measure-Object).Count
Write-Host "FINALIZE DONE"
