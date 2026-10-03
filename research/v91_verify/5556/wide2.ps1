$ErrorActionPreference='Continue'
$nav = 'C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556\nav.ps1'
$adb = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$S='emulator-5556'
$dir='C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556'
function Tap($x,$y,$ms=1800){ & $adb -s $S shell input tap $x $y; Start-Sleep -Milliseconds $ms }
function Type8($t){ & $adb -s $S shell input text $t; Start-Sleep -Milliseconds 1600 }
function KbdOff(){ & $adb -s $S shell input keyevent 111; Start-Sleep -Seconds 2 }
function D($n){ & powershell -NoProfile -File $nav -Dump $n 2>&1 | Out-Null }
function S($n){ cmd /c "`"$adb`" -s $S exec-out screencap -p > `"$dir\$n.png`""; Start-Sleep -Milliseconds 500 }
function Nodes($n){ $x=Get-Content "$dir\ui_$n.xml" -Raw; $o=@(); foreach($m in [regex]::Matches($x,'<node[^>]*>')){$s=$m.Value;$t='';if($s -match 'text="([^"]*)"'){$t=$Matches[1]};$cd='';if($s -match 'content-desc="([^"]*)"'){$cd=$Matches[1]};$lb=if($t.Trim().Length -gt 0){$t}else{$cd}; $b=$null; if($s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){$b=[pscustomobject]@{t=$lb;x=[int](([int]$Matches[1]+[int]$Matches[3])/2);y=[int](([int]$Matches[2]+[int]$Matches[4])/2)}}; if($b -and $lb.Trim().Length -gt 0){$o+=$b}}; $o }
function Find($n,$re){ (Nodes $n) | Where-Object { $_.t -match $re } }

Tap 1034 189 2500                       # Add SSH server
D 'W1_form'; S 'W1_form'
$eds = @(); foreach($m in [regex]::Matches((Get-Content "$dir\ui_W1_form.xml" -Raw),'<node[^>]*>')){$s=$m.Value;if($s -match 'class="([^"]*EditText[^"]*)"' -and $s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){$eds+=[pscustomobject]@{x=[int](([int]$Matches[1]+[int]$Matches[3])/2);y=[int](([int]$Matches[2]+[int]$Matches[4])/2)}}}
"edit fields: $(($eds | ForEach-Object { "$($_.x),$($_.y)" }) -join ' ')"
Tap $eds[-1].x $eds[-1].y 2000
Type8 '10.255.'; Type8 '255.1:2'; Type8 '2'
KbdOff
D 'W2_host'
$sub = Find 'W2_host' '^(Add server|Connect)$'
"submit btn: $(($sub | ForEach-Object { "$($_.t)@$($_.x),$($_.y)" }) -join ' ')"
if($sub.Count){ Tap $sub[-1].x $sub[-1].y 2500 }
D 'W3_gate'; "gate: $(((Find 'W3_gate' 'password|Enter the password') | ForEach-Object { $_.t }) -join ' / ')"
$eds2 = @(); foreach($m in [regex]::Matches((Get-Content "$dir\ui_W3_gate.xml" -Raw),'<node[^>]*>')){$s=$m.Value;if($s -match 'class="([^"]*EditText[^"]*)"' -and $s -match 'password="true"' -and $s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){$eds2+=[pscustomobject]@{x=[int](([int]$Matches[1]+[int]$Matches[3])/2);y=[int](([int]$Matches[2]+[int]$Matches[4])/2)}}}
"pw field: $(($eds2 | ForEach-Object { "$($_.x),$($_.y)" }) -join ' ')"
if($eds2.Count){ Tap $eds2[-1].x $eds2[-1].y 2000; Type8 'pw123'; Type8 '456'; KbdOff }
$sub2 = Find 'W3_gate' '^(Add server|Connect)$'
D 'W3b'
$sub2 = Find 'W3b' '^(Add server|Connect)$'
if($sub2.Count){ Tap $sub2[-1].x $sub2[-1].y 2500 }
"waiting 22s for FAILED..."
Start-Sleep -Seconds 22
D 'W4_failed'; S 'W4_failed'
"failed: $(((Find 'W4_failed' 'Connection failed|Could not establish') | ForEach-Object { $_.t }) -join ' / ')"
$can = Find 'W4_failed' '^Cancel$'
"cancel btn: $(($can | ForEach-Object { "$($_.x),$($_.y)" }) -join ' ')"
if($can.Count){ Tap $can[-1].x $can[-1].y 2500 }
D 'W5_list'; S 'W5_list'
"list after close: $(((Nodes 'W5_list') | Select-Object -Last 8 | ForEach-Object { $_.t }) -join ' / ')"
