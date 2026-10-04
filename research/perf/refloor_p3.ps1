# refloor_p3.ps1 — min/flat 用**可滚动**长页重测地板（样本量补齐）
param([string]$Dev='emulator-5636')
$ErrorActionPreference='Continue'
$Adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$P='com.xiufeigo.opencodeshell.web.debug'; $A='com.xiufeigo.opencodeshell.web.WebHostActivity'
$Out="$PSScriptRoot\out\p3"
$NScroll=10;$NTap=6
function Adb2 { & $Adb -s $Dev @args }
function GetWinFrame { $raw=Adb2 shell "dumpsys window windows | grep -A30 '$P/'"; $t=$raw -join "`n"
  if($t -match 'Frames:\s*parent=\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ [pscustomobject]@{L=[int]$Matches[1];T=[int]$Matches[2];R=[int]$Matches[3];B=[int]$Matches[4];W=([int]$Matches[3]-[int]$Matches[1]);H=([int]$Matches[4]-[int]$Matches[2])} }
  else { [pscustomobject]@{L=0;T=0;R=1366;B=768;W=1366;H=768} } }
function WPt { param($f,[double]$fx,[double]$fy) [int]($f.L+$f.W*$fx),[int]($f.T+$f.H*$fy) }
function RSwipe { param($f,[double]$x,[double]$y1,[double]$y2,[int]$ms=300,[int]$ms2=350)
  $a=WPt $f $x $y1;$b=WPt $f $x $y2; Adb2 shell input swipe $a[0] $a[1] $b[0] $b[1] $ms|Out-Null; Start-Sleep -Milliseconds $ms2 }
function RTap { param($f,[double]$fx,[double]$fy,[int]$ms=450) $p=WPt $f $fx $fy; Adb2 shell input tap $p[0] $p[1]|Out-Null; Start-Sleep -Milliseconds $ms }
function Back { Adb2 shell input keyevent KEYCODE_BACK|Out-Null; Start-Sleep -Milliseconds 700 }
foreach($m in 'min','flat'){
  $d="$Out\${m}_v2"; New-Item -ItemType Directory -Force -Path $d|Out-Null
  Adb2 shell dumpsys gfxinfo $P reset|Out-Null
  Adb2 shell am force-stop $P; Start-Sleep -Milliseconds 800
  Adb2 shell am start -n "$P/$A" --es oc_perf_mode $m|Out-Null; Start-Sleep -Seconds 3
  $f=GetWinFrame
  for($i=0;$i -lt $NScroll;$i++){ RSwipe $f 0.5 0.80 0.20 }
  for($i=0;$i -lt $NScroll;$i++){ RSwipe $f 0.5 0.20 0.80 }
  for($i=0;$i -lt $NTap;$i++){ RTap $f 0.5 0.45 800; Back; Adb2 shell am start -n "$P/$A" --es oc_perf_mode $m|Out-Null; Start-Sleep -Milliseconds 800; $f=GetWinFrame }
  for($i=0;$i -lt 5;$i++){ RSwipe $f 0.85 0.5 0.15 }
  for($i=0;$i -lt 5;$i++){ RSwipe $f 0.15 0.5 0.85 }
  RTap $f 0.5 0.5 600
  Start-Sleep -Seconds 2
  Adb2 shell am start -n "$P/$A" --es oc_perf_mode $m|Out-Null; Start-Sleep -Seconds 2
  $g=Adb2 shell dumpsys gfxinfo $P; $g|Out-File -Encoding utf8 "$d\gfxinfo_full.txt"
  $gt=$g -join "`n"
  foreach($k in '50th percentile','90th percentile','99th percentile','Total frames rendered','Janky frames','Number Slow UI thread','Number Slow issue draw commands','50th gpu percentile'){
    if($gt -match ("(?m)^\s*"+[regex]::Escape($k)+":\s*(.+)$")){ "  $m : $k = $($Matches[1].Trim())" } }
}
