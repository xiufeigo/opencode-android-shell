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
function Txt($n){ $x=Get-Content "$dir\ui_$n.xml" -Raw; $o=@(); foreach($m in [regex]::Matches($x,'<node[^>]*>')){$s=$m.Value;$t='';if($s -match 'text="([^"]*)"'){$t=$Matches[1]};$cd='';if($s -match 'content-desc="([^"]*)"'){$cd=$Matches[1]};$lb=if($t.Trim().Length -gt 0){$t}else{$cd}; if($lb.Trim().Length -gt 0){$o+=$lb}}; $o }
function RowY($n){ $x=Get-Content "$dir\ui_$n.xml" -Raw; $v=@(); foreach($m in [regex]::Matches($x,'<node[^>]*>')){$s=$m.Value;$cd='';if($s -match 'content-desc="([^"]*)"'){$cd=$Matches[1]}; if($cd -eq 'Server actions' -and $s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){$v+=[int](([int]$Matches[2]+[int]$Matches[4])/2)}}; if($v.Count){$v[0]}else{0} }
# find a label by position among all text nodes: returns cy of the LAST match of $re
function LastY($n,$re){ $x=Get-Content "$dir\ui_$n.xml" -Raw; $v=@(); foreach($m in [regex]::Matches($x,'<node[^>]*>')){$s=$m.Value;$t='';if($s -match 'text="([^"]*)"'){$t=$Matches[1]}; if($t -match $re -and $s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){$v+=[int](([int]$Matches[2]+[int]$Matches[4])/2)}}; if($v.Count){$v[-1]}else{0} }

$loc = $args[0]
"===== LOCALE $loc ====="
& $adb -s $S shell cmd locale set-app-locales com.xiufeigo.opencodeshell --locales $loc | Out-Null
Start-Sleep -Seconds 4
Tap 540 81 2000
Tap 583 631 2500
for($i=0;$i -lt 3;$i++){ & $adb -s $S shell input swipe 830 194 520 194 250; Start-Sleep -Milliseconds 1100 }
Tap 552 194 2500
D "N0_${loc}"; "  panel: $((Txt "N0_${loc}" | Select-Object -Last 5) -join ' / ')"
Tap 702 271 2500
D "N1_${loc}"; "  form:  $((Txt "N1_${loc}" | Select-Object -Last 4) -join ' / ')"
Tap 683 538 2000
Type8 '10.255.'; Type8 '255.1:2'; Type8 '2'
KbdOff
Tap 770 594 2500
D "N2_${loc}"; "  gate:  $((Txt "N2_${loc}" | Select-Object -Last 3) -join ' / ')"
Tap 683 523 2000
Type8 'pw123'; Type8 '456'
KbdOff
Tap 770 594 2000
Start-Sleep -Seconds 20
D "N3_${loc}"; "  failed:$((Txt "N3_${loc}" | Select-Object -Last 3) -join ' / ')"
# close the dialog: its Cancel is the left of the two footer buttons
$cy = LastY "N3_${loc}" '^(.{2,4})$'
D "N3b_${loc}"
$xml = Get-Content "$dir\ui_N3_${loc}.xml" -Raw
$cands=@(); foreach($m in [regex]::Matches($xml,'<node[^>]*>')){$s=$m.Value;$t='';if($s -match 'text="([^"]*)"'){$t=$Matches[1]}; if($s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){ $y=[int](([int]$Matches[2]+[int]$Matches[4])/2); if($y -gt 575 -and $y -lt 615){$cands+=[pscustomobject]@{t=$t;x=[int](([int]$Matches[1]+[int]$Matches[3])/2);y=$y}}}}
"  footer: $(($cands | ForEach-Object { "$($_.t)@$($_.x),$($_.y)" }) -join '  ')"
if($cands.Count -ge 2){ Tap $cands[0].x $cands[0].y 2500 }
D "N4_${loc}"; $ry = RowY "N4_${loc}"
"  ssh row cy=$ry"
& $adb -s $S shell input swipe 700 600 700 400 250
Start-Sleep -Seconds 2
D "N5_${loc}"; $ry2 = RowY "N5_${loc}"
"  ssh row cy after scroll=$ry2"
S "N5_${loc}"
