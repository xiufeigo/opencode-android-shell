param([string]$serial = "emulator-5730")
$ErrorActionPreference = 'Continue'
$adb   = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$repo  = "C:\sensorsdata\main\program\opencode-android-shell"
$shots = "$repo\research\env\phone_shots"
New-Item -ItemType Directory -Force -Path $shots | Out-Null
$apk   = "$repo\research\deliver\opencode-shell-web.apk"
function W($m){ Write-Output ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }

W "wait boot_completed ($serial)"
$deadline=(Get-Date).AddSeconds(240); $booted=$false
while((Get-Date) -lt $deadline){
  $bc = (& $adb -s $serial shell getprop sys.boot_completed 2>&1 | Out-String).Trim()
  if($bc -eq '1'){ $booted=$true; W "BOOT_COMPLETED"; break }
  W "  boot_completed='$bc'"
  Start-Sleep -Seconds 12
}
if(-not $booted){ W "boot timeout (continuing anyway - device is usable)"; }

W "ime setting"
& $adb -s $serial shell settings put secure show_ime_with_hard_keyboard 1 | Out-Null
W "  -> " + ((& $adb -s $serial shell settings get secure show_ime_with_hard_keyboard 2>&1 | Out-String).Trim())

W "wake + dismiss keyguard"
& $adb -s $serial shell input keyevent KEYCODE_WAKEUP | Out-Null
& $adb -s $serial shell wm dismiss-keyguard 2>&1 | Out-Null
& $adb -s $serial shell input keyevent KEYCODE_MENU | Out-Null

W "install web apk"
& $adb -s $serial install -r -g $apk 2>&1 | ForEach-Object { W "  $_" }

W "reverse 4095"
& $adb -s $serial reverse tcp:4095 tcp:4095 2>&1 | ForEach-Object { W "  $_" }
W "  reverse list: " + ((& $adb -s $serial reverse --list 2>&1 | Out-String).Trim() -replace "`r?`n",' | ')

W "fingerprint"
foreach($p in @('ro.product.name','ro.product.model','ro.build.version.release','ro.build.version.sdk')){
  W "  $p = " + ((& $adb -s $serial shell getprop $p 2>&1 | Out-String).Trim())
}
W "  wm size: " + ((& $adb -s $serial shell wm size 2>&1 | Out-String).Trim() -replace "`r?`n",' | ')
W "  wm density: " + ((& $adb -s $serial shell wm density 2>&1 | Out-String).Trim() -replace "`r?`n",' | ')
W "  uptime: " + ((& $adb -s $serial shell cat /proc/uptime 2>&1 | Out-String).Trim())

W "launch web host"
& $adb -s $serial shell am start -n "com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity" 2>&1 | ForEach-Object { W "  $_" }
Start-Sleep -Seconds 30

W "screenshot + ui dump"
& $adb -s $serial shell screencap -p /sdcard/p01.png 2>&1 | Out-Null
& $adb -s $serial pull /sdcard/p01.png "$shots\phone01_after_launch.png" 2>&1 | ForEach-Object { W "  $_" }
& $adb -s $serial shell uiautomator dump /sdcard/u01.xml 2>&1 | Out-Null
& $adb -s $serial pull /sdcard/u01.xml "$shots\ui01_after_launch.xml" 2>&1 | ForEach-Object { W "  $_" }

W "focus / ime"
W "  mCurrentFocus: " + ((& $adb -s $serial shell dumpsys window 2>&1 | Select-String 'mCurrentFocus' | Select-Object -Last 1) -join '')
W "  mInputShown: " + ((& $adb -s $serial shell dumpsys input_method 2>&1 | Select-String 'mInputShown' | Select-Object -First 2) -join ' ;; ')
W "DONE-PIPELINE"
