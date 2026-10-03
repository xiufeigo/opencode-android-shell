$ErrorActionPreference='Continue'
$nav = 'C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556\nav.ps1'
$adb = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$S='emulator-5556'
$dir='C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556'
function Tap($x,$y,$ms=1600){ & $adb -s $S shell input tap $x $y; Start-Sleep -Milliseconds $ms }
function Type8($t){ & $adb -s $S shell input text $t; Start-Sleep -Milliseconds 1600 }
function KbdOff(){ & $adb -s $S shell input keyevent 111; Start-Sleep -Seconds 2 }
function D($n){ & powershell -NoProfile -File $nav -Dump $n 2>&1 | Out-Null }
function Has($n,$p){ $x=Get-Content "$dir\ui_$n.xml" -Raw; if($x -match $p){'YES'}else{'no'} }
function Step($tag,$n,$p){ $r = Has $n $p; "[$tag] $r" }

# --- recover to home ---
& $adb -s $S shell input tap 23 745 | Out-Null; Start-Sleep -Milliseconds 900
& $adb -s $S shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.xiufeigo.opencodeshell/.OcActivity | Out-Null
Start-Sleep -Seconds 4
D '53_home'; Step 'HOME has Settings link' '53_home' 'Settings'

Tap 583 631 2500
D '53_set'; Step 'SETTINGS open' '53_set' 'General'
for($i=0;$i -lt 3;$i++){ & $adb -s $S shell input swipe 830 194 520 194 250; Start-Sleep -Milliseconds 1100 }
D '53_tabs'; Step 'TABS Servers visible' '53_tabs' 'Servers'
Tap 552 194 2500
D '53_srv'; Step 'SERVERS panel' '53_srv' '127\.0\.0\.1:4095'; Step '  SSH sect absent' '53_srv' 'SSH tunnels'

# --- Add SSH ---
Tap 702 271 2500
D '53_addform'; Step 'ADD form open' '53_addform' 'Host or SSH command'
Tap 683 538 2000
Type8 '10.255.'; Type8 '255.1:2'; Type8 '2'
KbdOff
D '53_hostin'; Step 'HOST typed' '53_hostin' '10\.255\.255\.1:22'
Tap 770 594 2500
D '53_pwgate'; Step 'PW gate shown' '53_pwgate' 'Enter the password'
