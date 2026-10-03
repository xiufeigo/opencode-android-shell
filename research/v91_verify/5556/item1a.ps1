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
function Cy($n,$p){ $x=Get-Content "$dir\ui_$n.xml" -Raw; $v=@(); foreach($m in [regex]::Matches($x,'<node[^>]*>')){$s=$m.Value;$t='';if($s -match 'text="([^"]*)"'){$t=$Matches[1]};$cd='';if($s -match 'content-desc="([^"]*)"'){$cd=$Matches[1]};$lb=if($t.Trim().Length -gt 0){$t}else{$cd}; if($lb -match $p -and $s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){$v+=[int](([int]$Matches[2]+[int]$Matches[4])/2)}}; if($v.Count){$v[0]}else{0} }

"=== ITEM 1: failed-state delete ==="
Step 'baseline: SSH sect' '58_row2' 'SSH tunnels'
Step 'baseline: row buttons' '58_row2' 'Reconnect'

# 1) open the SSH row overflow
Tap 782 543
D '59_menu'; S '59_menu'
Step 'menu shows Delete' '59_menu' 'Delete'
$dy = Cy '59_menu' '^Delete$'
"  Delete item cy=$dy"

# 2) tap Delete -> arms confirm bar
Tap 701 $dy
D '60_armed'; S '60_armed'
Step 'confirm title' '60_armed' 'Delete SSH server\?'
Step 'confirm message' '60_armed' 'This removes'
