param(
  [Parameter(Mandatory=$true)][string]$Tag
)
$ErrorActionPreference='Continue'
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dev="emulator-5556"
$out="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98"

# focus check
$focus = (& $adb -s $dev shell dumpsys window 2>$null | Select-String "mCurrentFocus" | Select-Object -First 1)
"### FOCUS: $focus"

# dump (compressed preferred)
& $adb -s $dev shell uiautomator dump --compressed /sdcard/$Tag.xml | Out-Null
& $adb -s $dev pull /sdcard/$Tag.xml "$out\$Tag.xml" 2>&1 | Out-Null
& $adb -s $dev shell rm /sdcard/$Tag.xml
"### DUMP: $out\$Tag.xml  exists=$([bool](Test-Path "$out\$Tag.xml"))"

# screenshot: screencap -p + pull only
& $adb -s $dev shell screencap -p /sdcard/$Tag.png
& $adb -s $dev pull /sdcard/$Tag.png "$out\$Tag.png" 2>&1 | Out-Null
& $adb -s $dev shell rm /sdcard/$Tag.png
"### SHOT: $out\$Tag.png  exists=$([bool](Test-Path "$out\$Tag.png"))"
