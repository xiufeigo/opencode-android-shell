$ErrorActionPreference='Continue'
$nav = 'C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556\nav.ps1'
$adb = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$S='emulator-5556'
$dir='C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556'
function Tap($x,$y,$ms=1800){ & $adb -s $S shell input tap $x $y; Start-Sleep -Milliseconds $ms }
function D($n){ & powershell -NoProfile -File $nav -Dump $n 2>&1 | Out-Null }
function S($n){ cmd /c "`"$adb`" -s $S exec-out screencap -p > `"$dir\$n.png`""; Start-Sleep -Milliseconds 400 }
function Txt($n){ $x=Get-Content "$dir\ui_$n.xml" -Raw; $o=@(); foreach($m in [regex]::Matches($x,'<node[^>]*>')){$s=$m.Value;$t='';if($s -match 'text="([^"]*)"'){$t=$Matches[1]};$cd='';if($s -match 'content-desc="([^"]*)"'){$cd=$Matches[1]};$lb=if($t.Trim().Length -gt 0){$t}else{$cd}; if($lb.Trim().Length -gt 0){$o+=$lb}}; $o }
# SSH row overflow = the content-desc "Server actions" (locale-independent content-desc)
function RowY($n){ $x=Get-Content "$dir\ui_$n.xml" -Raw; $v=@(); foreach($m in [regex]::Matches($x,'<node[^>]*>')){$s=$m.Value;$cd='';if($s -match 'content-desc="([^"]*)"'){$cd=$Matches[1]}; if($cd -eq 'Server actions' -and $s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){$v+=[int](([int]$Matches[2]+[int]$Matches[4])/2)}}; if($v.Count){$v[0]}else{0} }

# Positional: Servers panel tab is the 1st tab after 3 left-swipes (stable in all locales)
foreach($loc in @('zh-CN','zh-TW','en')){
  "===== LOCALE $loc ====="
  & $adb -s $S shell cmd locale set-app-locales com.xiufeigo.opencodeshell --locales $loc | Out-Null
  Start-Sleep -Seconds 4
  # from wherever we are, go home first
  Tap 540 81 2000
  D "L1_${loc}_home"
  Tap 583 631 2500
  D "L2_${loc}_set"
  "  settings opened: rows=$((Txt "L2_${loc}_set").Count)"
  for($i=0;$i -lt 3;$i++){ & $adb -s $S shell input swipe 830 194 520 194 250; Start-Sleep -Milliseconds 1100 }
  D "L3_${loc}_tabs"
  "  tabstrip labels: $((Txt "L3_${loc}_tabs") | Select-Object -Skip 5 -First 5) -join ' | '"
  Tap 552 194 2500
  D "L4_${loc}_srv"; S "L4_${loc}_srv"
  $l = Txt "L4_${loc}_srv"
  "  panel labels: $(($l | Select-Object -Skip 5) -join ' / ')"
  $ry = RowY "L4_${loc}_srv"
  "  ssh row overflow cy=$ry"
}
