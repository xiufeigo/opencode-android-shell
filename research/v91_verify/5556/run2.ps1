$ErrorActionPreference='Continue'
$nav = 'C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556\nav.ps1'
$adb = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$S='emulator-5556'
$dir='C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556'
function Tap($x,$y,$ms=1600){ & $adb -s $S shell input tap $x $y; Start-Sleep -Milliseconds $ms }
function Type8($t){ & $adb -s $S shell input text $t; Start-Sleep -Milliseconds 1600 }
function KbdOff(){ & $adb -s $S shell input keyevent 111; Start-Sleep -Seconds 2 }
function D($n){ & powershell -NoProfile -File $nav -Dump $n 2>&1 | Out-Null }
function S($n){ cmd /c "`"$adb`" -s $S exec-out screencap -p > `"$dir\$n.png`""; Start-Sleep -Milliseconds 400 }
function Has($n,$p){ $x=Get-Content "$dir\ui_$n.xml" -Raw; if($x -match $p){'YES'}else{'no'} }

# ensure app focused + servers panel
& $adb -s $S shell input tap 23 745 | Out-Null; Start-Sleep -Milliseconds 900
& $adb -s $S shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.xiufeigo.opencodeshell/.OcActivity | Out-Null
Start-Sleep -Seconds 4
D '51_home'
"home: Settings link = $(Has '51_home' 'Settings')"
Tap 583 631 2200
for($i=0;$i -lt 3;$i++){ & $adb -s $S shell input swipe 830 194 520 194 250; Start-Sleep -Milliseconds 1100 }
Tap 552 194 2500
D '51_servers'
"servers: DefaultWS = $(Has '51_servers' '127\.0\.0\.1:4095')  SSHsect = $(Has '51_servers' 'SSH tunnels')"

# add entry
Tap 702 271 2200
Tap 683 538 2000
Type8 '10.255.'; Type8 '255.1:2'; Type8 '2'
KbdOff
Tap 770 594 2500
Tap 683 523 2000
Type8 'pw123'; Type8 '456'
KbdOff
Tap 770 594 2000
"waiting for FAILED..."
Start-Sleep -Seconds 20
D '51_submitted'
"after submit: failedText = $(Has '51_submitted' 'Connection failed')  SSHsect = $(Has '51_submitted' 'SSH tunnels')"
# close dialog with Cancel footer
Tap 678 594 2500
D '51_afterdlg'
"after dialog close: SSHsect = $(Has '51_afterdlg' 'SSH tunnels')  Reconnect = $(Has '51_afterdlg' 'Reconnect')"
