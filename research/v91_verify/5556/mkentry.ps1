$ErrorActionPreference='Continue'
$nav = 'C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556\nav.ps1'
$adb = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$S='emulator-5556'
$dir='C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556'
function Tap($x,$y,$ms=1800){ & $adb -s $S shell input tap $x $y; Start-Sleep -Milliseconds $ms }
function Type8($t){ & $adb -s $S shell input text $t; Start-Sleep -Milliseconds 1600 }
function KbdOff(){ & $adb -s $S shell input keyevent 111; Start-Sleep -Seconds 2 }
function D($n){ & powershell -NoProfile -File $nav -Dump $n 2>&1 | Out-Null }
function S($n){ cmd /c "`"$adb`" -s $S exec-out screencap -p > `"$dir\$n.png`""; Start-Sleep -Milliseconds 400 }
function Has($n,$p){ $x=Get-Content "$dir\ui_$n.xml" -Raw; if($x -match $p){'YES'}else{'no'} }
function Step($t,$n,$p){ "[$t] $(Has $n $p)" }
function RowY($n){ $x=Get-Content "$dir\ui_$n.xml" -Raw; $v=@(); foreach($m in [regex]::Matches($x,'<node[^>]*>')){$s=$m.Value;$cd='';if($s -match 'content-desc="([^"]*)"'){$cd=$Matches[1]}; if($cd -eq 'Server actions' -and $s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){$v+=[int](([int]$Matches[2]+[int]$Matches[4])/2)}}; if($v.Count){$v[0]}else{0} }

# --- create the failed entry ---
Tap 702 271 2500
Tap 683 538 2000
Type8 '10.255.'; Type8 '255.1:2'; Type8 '2'
KbdOff
D '80_h'; Step 'host' '80_h' '10\.255\.255\.1:22'
Tap 770 594 2500
D '80_g'; Step 'pw gate' '80_g' 'Enter the password'
Tap 683 523 2000
Type8 'pw123'; Type8 '456'
KbdOff
Tap 770 594 2000
Start-Sleep -Seconds 20
D '80_f'; S '80_f'
Step 'SSH sect' '80_f' 'SSH tunnels'
Step 'failed' '80_f' 'Connection failed'
Tap 678 594 2500
D '80_l'; Step 'after close SSH sect' '80_l' 'SSH tunnels'

# scroll row into a good position
& $adb -s $S shell input swipe 700 550 700 380 250
Start-Sleep -Seconds 2
D '80_p'; $ry = RowY '80_p'; S '80_p'
"row cy=$ry"
