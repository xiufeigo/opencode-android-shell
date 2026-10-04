$ErrorActionPreference='Stop'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s="emulator-6090"
$apk="C:\sensorsdata\main\program\opencode-android-shell\research\deliver\opencode-shell-web-release-nor8.apk"
$pkg="com.xiufeigo.opencodeshell.web.debug"
$out="C:\sensorsdata\main\program\opencode-android-shell\research\audit\v6"
$deadline=(Get-Date).AddSeconds(240)
while((Get-Date) -lt $deadline){
  $st = & $adb -s $s shell getprop sys.boot_completed 2>$null
  if($st -match '1'){ "BOOT_COMPLETED=1" | Out-File "$out\setup2.log" -Encoding utf8; break }
  Start-Sleep -Seconds 5
}
& $adb -s $s shell wm size 1080x2400        | Out-File "$out\setup2.log" -Append -Encoding utf8
& $adb -s $s shell wm density 420           | Out-File "$out\setup2.log" -Append -Encoding utf8
& $adb -s $s shell wm size                  | Out-File "$out\setup2.log" -Append -Encoding utf8
& $adb -s $s shell wm density               | Out-File "$out\setup2.log" -Append -Encoding utf8
& $adb -s $s reverse tcp:4095 tcp:4095      | Out-File "$out\setup2.log" -Append -Encoding utf8
"install:" | Out-File "$out\setup2.log" -Append -Encoding utf8
(& $adb -s $s install -r -g $apk 2>&1)     | Out-File "$out\setup2.log" -Append -Encoding utf8
"ondevice md5:" | Out-File "$out\setup2.log" -Append -Encoding utf8
$remote = (& $adb -s $s shell pm path $pkg) -replace '^package:',''
(& $adb -s $s shell "md5sum $remote" 2>&1) | Out-File "$out\setup2.log" -Append -Encoding utf8
& $adb -s $s logcat -c
& $adb -s $s shell am start -n "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity" 2>&1 | Out-File "$out\setup2.log" -Append -Encoding utf8
Start-Sleep -Seconds 20
"READY" | Out-File "$out\setup2_done.flag" -Encoding utf8
