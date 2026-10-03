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

$loc = $args[0]
"===== LOCALE $loc : create failed entry, arm confirm, capture ====="
& $adb -s $S shell cmd locale set-app-locales com.xiufeigo.opencodeshell --locales $loc | Out-Null
Start-Sleep -Seconds 4
Tap 540 81 2000          # Home
Tap 583 631 2500         # Settings
for($i=0;$i -lt 3;$i++){ & $adb -s $S shell input swipe 830 194 520 194 250; Start-Sleep -Milliseconds 1100 }
Tap 552 194 2500         # Servers tab
D "M0_${loc}_srv"
"  panel: $((Txt "M0_${loc}_srv" | Select-Object -Last 6) -join ' / ')"
# Add SSH (the button is beside the section title, same slot in every locale)
Tap 702 271 2500
D "M1_${loc}_form"
"  add form: $((Txt "M1_${loc}_form" | Select-Object -Last 4) -join ' / ')"
Tap 683 538 2000
Type8 '10.255.'; Type8 '255.1:2'; Type8 '2'
KbdOff
Tap 770 594 2500         # submit -> password gate
D "M2_${loc}_gate"; "  gate: $((Txt "M2_${loc}_gate" | Select-Object -Last 4) -join ' / ')"
Tap 683 523 2000
Type8 'pw123'; Type8 '456'
KbdOff
Tap 770 594 2000         # connect
Start-Sleep -Seconds 20
D "M3_${loc}_failed"; S "M3_${loc}_failed"
"  failed: $((Txt "M3_${loc}_failed" | Select-Object -Last 6) -join ' / ')"
Tap 678 594 2500         # close dialog
D "M4_${loc}_list"
$ry = RowY "M4_${loc}_list"
"  ssh row cy=$ry"
# scroll the row up so the confirm bar is not clipped
& $adb -s $S shell input swipe 700 550 700 380 250
Start-Sleep -Seconds 2
D "M5_${loc}_pos"; $ry2 = RowY "M5_${loc}_pos"; S "M5_${loc}_pos"
"  ssh row cy after scroll=$ry2"
