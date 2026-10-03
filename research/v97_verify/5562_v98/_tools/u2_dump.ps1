param([Parameter(Mandatory=$true)][string]$Name)
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$S="emulator-5562"
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98"
$ok=$false
foreach($try in 1..5){
  & $adb -s $S shell uiautomator dump /sdcard/u2.xml | Out-Null
  if(Test-Path "$d\u2_local_probe.xml"){ Remove-Item "$d\u2_local_probe.xml" -ErrorAction SilentlyContinue }
  & $adb -s $S pull /sdcard/u2.xml "$d\$Name.xml" 2>$null | Out-Null
  if(Test-Path "$d\$Name.xml"){ $ok=$true; break }
  Start-Sleep -Milliseconds 1200
}
if(-not $ok){ Write-Error "dump failed after 5 tries: $Name"; exit 1 }
& $adb -s $S shell rm /sdcard/u2.xml 2>$null | Out-Null
& $adb -s $S shell screencap -p /sdcard/u2.png | Out-Null
& $adb -s $S pull /sdcard/u2.png "$d\$Name.png" | Out-Null
& $adb -s $S shell rm /sdcard/u2.png 2>$null | Out-Null
"dumped: $Name"
