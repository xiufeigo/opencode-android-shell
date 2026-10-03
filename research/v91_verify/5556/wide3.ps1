$ErrorActionPreference='Continue'
$nav = 'C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556\nav.ps1'
$adb = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$S='emulator-5556'
$dir='C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556'
function Tap($x,$y,$ms=1800){ & $adb -s $S shell input tap $x $y; Start-Sleep -Milliseconds $ms }
function Type8($t){ & $adb -s $S shell input text $t; Start-Sleep -Milliseconds 1600 }
function D($n){ & powershell -NoProfile -File $nav -Dump $n 2>&1 | Out-Null }
function S($n){ cmd /c "`"$adb`" -s $S exec-out screencap -p > `"$dir\$n.png`""; Start-Sleep -Milliseconds 500 }
function Pws($n){ $x=Get-Content "$dir\ui_$n.xml" -Raw; $o=@(); foreach($m in [regex]::Matches($x,'<node[^>]*>')){$s=$m.Value; if($s -match 'class="([^"]*EditText[^"]*)"' -and $s -match 'password="true"' -and $s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){$o+=[pscustomobject]@{x=[int](([int]$Matches[1]+[int]$Matches[3])/2);y=[int](([int]$Matches[2]+[int]$Matches[4])/2)}}}; $o }
function Btn($n,$re){ $x=Get-Content "$dir\ui_$n.xml" -Raw; $o=@(); foreach($m in [regex]::Matches($x,'<node[^>]*>')){$s=$m.Value;$t='';if($s -match 'text="([^"]*)"'){$t=$Matches[1]}; if($t -match $re -and $s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){$o+=[pscustomobject]@{t=$t;x=[int](([int]$Matches[1]+[int]$Matches[3])/2);y=[int](([int]$Matches[2]+[int]$Matches[4])/2)}}}; $o }

Tap 1034 189 2500
D 'X1'; "form opens: $(if((Btn 'X1' 'Add SSH server').Count){'yes'}else{'no'})"
$eds=@(); foreach($m in [regex]::Matches((Get-Content "$dir\ui_X1.xml" -Raw),'<node[^>]*>')){$s=$m.Value;if($s -match 'class="([^"]*EditText[^"]*)"' -and $s -match 'password="false"' -and $s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){$eds+=[pscustomobject]@{x=[int](([int]$Matches[1]+[int]$Matches[3])/2);y=[int](([int]$Matches[2]+[int]$Matches[4])/2)}}}
"text fields: $(($eds | ForEach-Object { "$($_.x),$($_.y)" }) -join ' ')"
$host=$eds[-1]
Tap $host.x $host.y 2000
Type8 '10.255.'; Type8 '255.1:2'; Type8 '2'
& $adb -s $S shell input keyevent 111 | Out-Null; Start-Sleep -Seconds 2
$sb = Btn 'X2' '^Add server$'
D 'X2'; $sb = Btn 'X2' '^Add server$'
"submit at $($sb[-1].x),$($sb[-1].y)"
Tap $sb[-1].x $sb[-1].y 2500
# scroll to reveal the password field
& $adb -s $S shell input swipe 674 500 674 380 250
Start-Sleep -Seconds 2
D 'X3'; $pw = Pws 'X3'
"pw field: $(($pw | ForEach-Object { "$($_.x),$($_.y)" }) -join ' ')"
if($pw.Count){ Tap $pw[-1].x $pw[-1].y 2000; Type8 'pw123'; Type8 '456' }
D 'X4'; $sb2 = Btn 'X4' '^(Add server|Connect)$'
"resubmit at $($sb2[-1].x),$($sb2[-1].y)"
Tap $sb2[-1].x $sb2[-1].y 2500
"waiting 30s..."
Start-Sleep -Seconds 30
D 'X5'; S 'X5'
"labels: $(((Btn 'X5' 'Connection failed|Could not establish|Servers|Add SSH|Server actions|Default') | ForEach-Object { "$($_.t)@$($_.x),$($_.y)" }) -join ' ')"
