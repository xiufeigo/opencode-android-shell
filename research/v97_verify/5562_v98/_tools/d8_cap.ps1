param([Parameter(Mandatory=$true)][string]$Name)
$ErrorActionPreference='Stop'
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$S="emulator-5562"
$dir="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98"
# screenshot: screencap -p + pull (NEVER exec-out)
& $adb -s $S shell screencap -p /sdcard/$Name.png | Out-Null
& $adb -s $S pull /sdcard/$Name.png "$dir\$Name.png" | Out-Null
& $adb -s $S shell rm /sdcard/$Name.png | Out-Null
# ui dump with retry (uiautomator sometimes returns 0 bytes)
$ok=$false
for($i=1;$i -le 3;$i++){
  & $adb -s $S shell uiautomator dump /sdcard/$Name.xml | Out-Null
  & $adb -s $S pull /sdcard/$Name.xml "$dir\$Name.xml" | Out-Null
  $sz = (Get-Item "$dir\$Name.xml" -ErrorAction SilentlyContinue).Length
  if($sz -and $sz -gt 500){ $ok=$true; $tries=$i; break }
  Start-Sleep -Milliseconds 900
}
& $adb -s $S shell rm /sdcard/$Name.xml | Out-Null
"captured: $Name xml_ok=$ok bytes=$sz tries=$tries"
