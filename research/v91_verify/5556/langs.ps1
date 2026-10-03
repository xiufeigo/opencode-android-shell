$ErrorActionPreference='Continue'
$nav = 'C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556\nav.ps1'
$adb = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$S='emulator-5556'
$dir='C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556'
function Tap($x,$y,$ms=1800){ & $adb -s $S shell input tap $x $y; Start-Sleep -Milliseconds $ms }
function D($n){ & powershell -NoProfile -File $nav -Dump $n 2>&1 | Out-Null }
function S($n){ cmd /c "`"$adb`" -s $S exec-out screencap -p > `"$dir\$n.png`""; Start-Sleep -Milliseconds 400 }
function Has($n,$p){ $x=Get-Content "$dir\ui_$n.xml" -Raw; if($x -match $p){'YES'}else{'no'} }
function RowY($n){ $x=Get-Content "$dir\ui_$n.xml" -Raw; $v=@(); foreach($m in [regex]::Matches($x,'<node[^>]*>')){$s=$m.Value;$cd='';if($s -match 'content-desc="([^"]*)"'){$cd=$Matches[1]}; if($cd -eq 'Server actions' -and $s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){$v+=[int](([int]$Matches[2]+[int]$Matches[4])/2)}}; if($v.Count){$v[0]}else{0} }
function DelY($n){ $x=Get-Content "$dir\ui_$n.xml" -Raw; $v=@(); foreach($m in [regex]::Matches($x,'<node[^>]*>')){$s=$m.Value;$t='';if($s -match 'text="([^"]*)"'){$t=$Matches[1]};$cd='';if($s -match 'content-desc="([^"]*)"'){$cd=$Matches[1]};$lb=if($t.Trim().Length -gt 0){$t}else{$cd}; if($lb -eq 'Delete' -and $s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){$v+=[int](([int]$Matches[2]+[int]$Matches[4])/2)}}; if($v.Count){$v[0]}else{0} }

foreach($loc in @('zh-CN','zh-TW','en')){
  "===== LOCALE $loc ====="
  & $adb -s $S shell cmd locale set-app-locales com.xiufeigo.opencodeshell --locales $loc | Out-Null
  Start-Sleep -Seconds 4
  # re-navigate: close settings, reopen, Servers tab
  D "81_${loc}_pre"
  Tap 798 97 2500
  D "82_${loc}_home"; "  home Settings link: $(Has "82_${loc}_home" 'Settings')"
  Tap 583 631 2500
  for($i=0;$i -lt 3;$i++){ & $adb -s $S shell input swipe 830 194 520 194 250; Start-Sleep -Milliseconds 1100 }
  D "83_${loc}_tabs"
  # find the Servers tab label position in the current locale
  $xml = Get-Content "$dir\ui_83_${loc}_tabs.xml" -Raw
  $sy=0
  foreach($m in [regex]::Matches($xml,'<node[^>]*>')){$s=$m.Value;$t='';if($s -match 'text="([^"]*)"'){$t=$Matches[1]}; if($t -match '^(Servers|\u670d\u52a1\u5668|\u4f3c\u670d\u5668)' -and $s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){ $b="[$($Matches[1]),$($Matches[2])][$($Matches[3]),$($Matches[4])]"; if($Matches[2] -lt 210){ $sy=[int](([int]$Matches[2]+[int]$Matches[4])/2) } }}
  "  servers tab cy=$sy"
  if($sy -gt 0){ Tap 552 $sy 2500 }
  D "84_${loc}_srv"; S "84_${loc}_srv"
  "  panel open: $(Has "84_${loc}_srv" '127\.0\.0\.1:4095')"
  "  ssh sect  : $(Has "84_${loc}_srv" 'SSH tunnels|\u7ba1\u9053|\u96a7\u9053')"
  $ry = RowY "84_${loc}_srv"
  "  ssh row cy=$ry"
}
