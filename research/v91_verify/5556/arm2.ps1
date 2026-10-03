$ErrorActionPreference='Continue'
$nav = 'C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556\nav.ps1'
$adb = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$S='emulator-5556'
$dir='C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556'
function Tap($x,$y,$ms=1800){ & $adb -s $S shell input tap $x $y; Start-Sleep -Milliseconds $ms }
function D($n){ & powershell -NoProfile -File $nav -Dump $n 2>&1 | Out-Null }
function S($n){ cmd /c "`"$adb`" -s $S exec-out screencap -p > `"$dir\$n.png`""; Start-Sleep -Milliseconds 400 }
function Has($n,$p){ $x=Get-Content "$dir\ui_$n.xml" -Raw; if($x -match $p){'YES'}else{'no'} }
function Step($t,$n,$p){ "[$t] $(Has $n $p)" }
# find the row's overflow (Server actions) cy from current dump
function RowY($n){ $x=Get-Content "$dir\ui_$n.xml" -Raw; $v=@(); foreach($m in [regex]::Matches($x,'<node[^>]*>')){$s=$m.Value;$cd='';if($s -match 'content-desc="([^"]*)"'){$cd=$Matches[1]}; if($cd -eq 'Server actions' -and $s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){$v+=[int](([int]$Matches[2]+[int]$Matches[4])/2)}}; if($v.Count){$v[0]}else{0} }

# scroll so the row sits HIGH in the panel, leaving room below for the confirm bar
& $adb -s $S shell input swipe 700 550 700 620 200
Start-Sleep -Seconds 2
D '68_pos'; $ry = RowY '68_pos'
"row overflow cy=$ry"
S '68_pos'

# open menu, tap Delete at its live position
Tap 782 $ry
D '69_m'; $dm = 0
$xml = Get-Content "$dir\ui_69_m.xml" -Raw
foreach($m in [regex]::Matches($xml,'<node[^>]*>')){ $s=$m.Value; $t=''; if($s -match 'text="([^"]*)"'){$t=$Matches[1]}; if($t -eq 'Delete' -and $s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){ $dm=[int](([int]$Matches[2]+[int]$Matches[4])/2) } }
"menu Delete cy=$dm"
Step 'menu opened' '69_m' 'Delete'
Tap 701 $dm
D '70_arm'; S '70_arm'
Step 'confirm title in tree' '70_arm' 'Delete SSH server\?'
Step 'confirm msg in tree' '70_arm' 'This removes'
Step 'Cancel btn' '70_arm' 'Cancel'
Step 'Delete btn' '70_arm' 'Delete'
