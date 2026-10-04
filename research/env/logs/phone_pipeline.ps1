$ErrorActionPreference = 'Continue'
$adb   = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = "emulator-5714"
$repo  = "C:\sensorsdata\main\program\opencode-android-shell"
$shots = "$repo\research\env\phone_shots"
New-Item -ItemType Directory -Force -Path $shots | Out-Null
$apk   = "$repo\research\deliver\opencode-shell-web.apk"

function W($m){ Write-Output ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }

W "stage1: waiting for boot_completed"
$deadline = (Get-Date).AddSeconds(720)
$booted = $false
while ((Get-Date) -lt $deadline) {
  $dev = (& $adb -s $serial get-state 2>&1 | Out-String).Trim()
  if ($dev -eq 'device') {
    $bc = (& $adb -s $serial shell getprop sys.boot_completed 2>&1 | Out-String).Trim()
    if ($bc -eq '1') { $booted = $true; W "BOOT_COMPLETED"; break }
    W "online, boot_completed='$bc'"
  } else { W "state=$dev" }
  Start-Sleep -Seconds 15
}
if (-not $booted) { W "FAILED: boot timeout"; exit 1 }

W "stage2: ime-with-hard-keyboard"
& $adb -s $serial shell settings put secure show_ime_with_hard_keyboard 1
W "  -> " + ((& $adb -s $serial shell settings get secure show_ime_with_hard_keyboard 2>&1 | Out-String).Trim())

W "stage3: unlock / wake / dismiss keyguard"
& $adb -s $serial shell input keyevent KEYCODE_WAKEUP | Out-Null
& $adb -s $serial shell wm dismiss-keyguard 2>&1 | Out-Null

W "stage4: install web apk"
& $adb -s $serial install -r -g $apk 2>&1 | ForEach-Object { W "  $_" }

W "stage5: reverse 4095"
& $adb -s $serial reverse tcp:4095 tcp:4095 2>&1 | ForEach-Object { W "  $_" }
W "  reverse list: " + ((& $adb -s $serial reverse --list 2>&1 | Out-String).Trim())

W "stage6: fingerprint"
foreach ($p in @('ro.product.name','ro.product.model','ro.build.version.release','ro.build.version.sdk')) {
  W "  $p = " + ((& $adb -s $serial shell getprop $p 2>&1 | Out-String).Trim())
}
W "  wm size: " + ((& $adb -s $serial shell wm size 2>&1 | Out-String).Trim() -replace "`r?`n",' | ')
W "  wm density: " + ((& $adb -s $serial shell wm density 2>&1 | Out-String).Trim() -replace "`r?`n",' | ')
W "  uptime: " + ((& $adb -s $serial shell cat /proc/uptime 2>&1 | Out-String).Trim())

W "stage7: launch"
& $adb -s $serial shell am start -n "com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity" 2>&1 | ForEach-Object { W "  $_" }
Start-Sleep -Seconds 25

W "stage8: screenshot + dump"
& $adb -s $serial shell screencap -p /sdcard/phone01.png 2>&1 | Out-Null
& $adb -s $serial pull /sdcard/phone01.png "$shots\phone01_after_launch.png" 2>&1 | ForEach-Object { W "  $_" }
& $adb -s $serial shell uiautomator dump /sdcard/ui01.xml 2>&1 | Out-Null
& $adb -s $serial pull /sdcard/ui01.xml "$shots\ui01_after_launch.xml" 2>&1 | ForEach-Object { W "  $_" }

W "stage9: focused window + ime state"
W "  mCurrentFocus: " + ((& $adb -s $serial shell dumpsys window 2>&1 | Select-String 'mCurrentFocus' | Select-Object -Last 1) -join '')
W "  mInputShown: " + ((& $adb -s $serial shell dumpsys input_method 2>&1 | Select-String 'mInputShown' | Select-Object -First 2) -join ' ;; ')
W "DONE-PIPELINE"
