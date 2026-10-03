$ErrorActionPreference='Continue'
$nav = 'C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556\nav.ps1'
$adb = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$S = 'emulator-5556'
function Tap($x,$y,$ms=1600){ & $adb -s $S shell input tap $x $y; Start-Sleep -Milliseconds $ms }
function Type8($t){ & $adb -s $S shell input text $t; Start-Sleep -Milliseconds 1600 }
function KbdOff(){ & $adb -s $S shell input keyevent 111; Start-Sleep -Seconds 2 }
function Dump($n){ & powershell -NoProfile -File $nav -Dump $n 2>&1 | Out-Null }
function Shot($n){ cmd /c "`"$adb`" -s $S exec-out screencap -p > `"C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556\$n.png`""; Start-Sleep -Milliseconds 400; "  SHOT $n" }
$dir='C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556'

# Add SSH server
Tap 702 271 2200
Tap 683 538 2000
Type8 '10.255.'
Type8 '255.1:2'
Type8 '2'
KbdOff
"--- host entered, submitting for password gate ---"
Tap 770 594 2500
Tap 683 523 2000
Type8 'pw123'
Type8 '456'
KbdOff
Dump '43a_pwready'
Shot '43a_pwready'
"--- submitting (connect attempt starts) ---"
Tap 770 594 2000
Shot '43b_t0'
foreach($t in 3,8,15,25){
  Start-Sleep -Seconds ($t - $global:prev); $global:prev = $t
  Shot "43c_t${t}s"
  Dump "43c_t${t}s"
}
