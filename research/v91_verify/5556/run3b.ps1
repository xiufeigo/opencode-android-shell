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
function Step($t,$n,$p){ "[$t] $(Has $n $p)" }

# password field is at (683,523) per earlier geometry
Tap 683 523 2000
Type8 'pw123'; Type8 '456'
KbdOff
D '56_pw'; Step 'PW entered' '56_pw' 'Add server'
Tap 770 594 2000
"waiting for FAILED (20s)..."
Start-Sleep -Seconds 20
D '56_failed'; S '56_failed'
Step 'SSH section' '56_failed' 'SSH tunnels'
Step 'Connection failed' '56_failed' 'Connection failed'
# close the dialog with its footer Cancel (not BACK)
Tap 678 594 2500
D '56_list'; S '56_list'
Step 'after close: SSH sect' '56_list' 'SSH tunnels'
Step 'after close: Reconnect btn' '56_list' 'Reconnect'
Step 'after close: Default WS' '56_list' '127\.0\.0\.1:4095'
