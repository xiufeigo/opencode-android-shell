param([string]$Name,[int]$Tries=8)
$ErrorActionPreference='Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98"
$pkg = "com.xiufeigo.opencodeshell"
$ok = $false
for($i=1; $i -le $Tries; $i++){
  $f = (& $adb -s emulator-5554 shell dumpsys window | Select-String "mCurrentFocus").ToString()
  if($f -notmatch "opencodeshell"){
    # try to reclaim foreground before giving up this attempt
    & $adb -s emulator-5554 shell am start -n "$pkg/.OcActivity" 2>&1 | Out-Null
    Start-Sleep -Milliseconds 1200
    $f2 = (& $adb -s emulator-5554 shell dumpsys window | Select-String "mCurrentFocus").ToString()
    if($f2 -notmatch "opencodeshell"){ Start-Sleep -Seconds 2; continue }
  }
  # focus is ours NOW: dump immediately
  & $adb -s emulator-5554 shell uiautomator dump --compressed /sdcard/w.xml 2>&1 | Out-Null
  & $adb -s emulator-5554 shell screencap -p /sdcard/w.png 2>&1 | Out-Null
  $sz = & $adb -s emulator-5554 shell "stat -c %s /sdcard/w.xml" 2>$null
  if([int]$sz -lt 2000){ & $adb -s emulator-5554 shell rm -f /sdcard/w.xml /sdcard/w.png; Start-Sleep -Seconds 2; continue }
  & $adb -s emulator-5554 pull /sdcard/w.xml "$dir\$Name.xml" 2>&1 | Out-Null
  & $adb -s emulator-5554 pull /sdcard/w.png "$dir\$Name.png" 2>&1 | Out-Null
  & $adb -s emulator-5554 shell rm -f /sdcard/w.xml /sdcard/w.png
  $ok = $true
  Write-Output "OK $Name try=$i xml=$sz"
  break
}
if(-not $ok){ Write-Output "FAIL $Name (focus never ours for $Tries tries)" }
