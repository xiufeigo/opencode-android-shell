# Rebuild: launch app -> Settings -> Servers tab
$ErrorActionPreference='Continue'
$nav = 'C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556\nav.ps1'
$adb = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$S = 'emulator-5556'

function Focus-App {
  & $adb -s $S shell input tap 23 745 | Out-Null
  Start-Sleep -Milliseconds 900
  & $adb -s $S shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.xiufeigo.opencodeshell/.OcActivity | Out-Null
  Start-Sleep -Seconds 4
}
function Tap($x,$y,$ms=1600){ & $adb -s $S shell input tap $x $y; Start-Sleep -Milliseconds $ms }
function Type8($t){ & $adb -s $S shell input text $t; Start-Sleep -Milliseconds 1600 }
function KbdOff(){ & $adb -s $S shell input keyevent 111; Start-Sleep -Seconds 2 }
function Dump($n){ & powershell -NoProfile -File $nav -Dump $n 2>&1 | Out-Null; "  dump $n" }
function Shot($n){ & powershell -NoProfile -File $nav -Shot $n 2>&1 | Out-String | ForEach-Object { $_.Trim() } }
function State { (& $adb -s $S shell dumpsys window 2>&1 | Select-String 'mCurrentFocus' | Out-String).Trim() }

Focus-App
"STEP1 focus: $(State)"
# Settings
Tap 583 631 2200
"STEP2 focus: $(State)"
# scroll tab strip to Servers
for($i=0;$i -lt 3;$i++){ & $adb -s $S shell input swipe 830 194 520 194 250; Start-Sleep -Milliseconds 1100 }
# Servers tab
Tap 552 194 2500
"STEP3 focus: $(State)"
Shot '42_servers_ready'
