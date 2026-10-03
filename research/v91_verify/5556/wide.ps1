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
function Txt($n){ $x=Get-Content "$dir\ui_$n.xml" -Raw; $o=@(); foreach($m in [regex]::Matches($x,'<node[^>]*>')){$s=$m.Value;$t='';if($s -match 'text="([^"]*)"'){$t=$Matches[1]};$cd='';if($s -match 'content-desc="([^"]*)"'){$cd=$Matches[1]};$lb=if($t.Trim().Length -gt 0){$t}else{$cd}; if($lb.Trim().Length -gt 0){$o+=$lb}}; $o }
function Cy($n,$re){ $x=Get-Content "$dir\ui_$n.xml" -Raw; $v=@(); foreach($m in [regex]::Matches($x,'<node[^>]*>')){$s=$m.Value;$t='';if($s -match 'text="([^"]*)"'){$t=$Matches[1]};$cd='';if($s -match 'content-desc="([^"]*)"'){$cd=$Matches[1]};$lb=if($t.Trim().Length -gt 0){$t}else{$cd}; if($lb -match $re -and $s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){$v+=[pscustomobject]@{t=$lb;x=[int](([int]$Matches[1]+[int]$Matches[3])/2);y=[int](([int]$Matches[2]+[int]$Matches[4])/2)}}}; $v }

# clear the search box
& $adb -s $S shell input keyevent 111 | Out-Null; Start-Sleep -Seconds 1
for($i=0;$i -lt 14;$i++){ & $adb -s $S shell input keyevent 67 | Out-Null }
Start-Sleep -Seconds 2
& $adb -s $S shell input keyevent 111 | Out-Null; Start-Sleep -Seconds 2
D 'R0_cleared'; "panel: $((Txt 'R0_cleared' | Select-Object -Last 5) -join ' / ')"

# Add SSH server (wide layout: button beside the Servers title)
$add = (Cy 'R0_cleared' 'Add SSH server')
if($add.Count){ "add btn at $($add[0].x),$($add[0].y)"; Tap $add[0].x $add[0].y 2500 }
D 'R1_form'; "form: $((Txt 'R1_form' | Select-Object -Last 5) -join ' / ')"
$host = @(); foreach($m in [regex]::Matches((Get-Content "$dir\ui_R1_form.xml" -Raw),'<node[^>]*>')){$s=$m.Value;if($s -match 'class="([^"]*EditText[^"]*)"'){if($s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){$host+=[pscustomobject]@{x=[int](([int]$Matches[1]+[int]$Matches[3])/2);y=[int](([int]$Matches[2]+[int]$Matches[4])/2)}}}}
"edit fields: $(($host | ForEach-Object { "$($_.x),$($_.y)" }) -join ' ')"
$hf = $host[-1]
Tap $hf.x $hf.y 2000
Type8 '10.255.'; Type8 '255.1:2'; Type8 '2'
KbdOff
D 'R2_host'; "host typed: $((Txt 'R2_host' | Select-Object -Last 4) -join ' / ')"
