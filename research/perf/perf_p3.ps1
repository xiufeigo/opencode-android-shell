# perf_p3.ps1 — P3：帧时归因分层 + gzip 合入复测 + 同轮两侧对照
#
# 设计：全部用**同一份 APK**，只切 `--es oc_perf_mode`：
#   baseline(改前,无gzip) / gzip(P3合入) / min / noanim / flat
# 交互脚本 ScriptAB 与 P2 的 perf_run.ps1 **逐字相同**（NScroll=10 / NTap=6），
# 保证 p50 与 P2 / AB1 可比。
param(
  [string]$Dev = 'emulator-5636',
  [string]$OutRoot = "$PSScriptRoot\out\p3"
)
$ErrorActionPreference = 'Continue'
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$WebPkg = 'com.xiufeigo.opencodeshell.web.debug'
$WebAct = 'com.xiufeigo.opencodeshell.web.WebHostActivity'
$NatPkg = 'com.xiufeigo.opencodeshell'
New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null
$NScroll = 10; $NTap = 6

function A { & $Adb -s $Dev @args }
function Say { param($m) Write-Output ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }

function GetWinFrame {
  $raw = A shell "dumpsys window windows | grep -A30 '$WebPkg/'"
  $t = $raw -join "`n"
  if ($t -match 'Frames:\s*parent=\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    return [pscustomobject]@{ L=[int]$Matches[1];T=[int]$Matches[2];R=[int]$Matches[3];B=[int]$Matches[4]
      W=([int]$Matches[3]-[int]$Matches[1]);H=([int]$Matches[4]-[int]$Matches[2]) } }
  return [pscustomobject]@{ L=0;T=0;R=1366;B=768;W=1366;H=768 } }
function WPt { param($f,[double]$fx,[double]$fy) [int]($f.L+$f.W*$fx),[int]($f.T+$f.H*$fy) }
function RTap { param($f,[double]$fx,[double]$fy,[int]$ms=450)
  $p=WPt $f $fx $fy; A shell input tap $p[0] $p[1] | Out-Null; Start-Sleep -Milliseconds $ms }
function RSwipe { param($f,[double]$x,[double]$y1,[double]$y2,[int]$ms=300,[int]$ms2=350)
  $a=WPt $f $x $y1; $b=WPt $f $x $y2
  A shell input swipe $a[0] $a[1] $b[0] $b[1] $ms | Out-Null; Start-Sleep -Milliseconds $ms2 }
function Back { A shell input keyevent KEYCODE_BACK | Out-Null; Start-Sleep -Milliseconds 700 }

# ---- ScriptAB：与 P2 perf_run.ps1 逐字相同 ----
function ScriptAB { param($pkg,$act,$mode)
  $f = GetWinFrame
  A shell am force-stop $pkg; Start-Sleep -Milliseconds 800
  if ($mode) { A shell am start -n "$pkg/$act" --es oc_perf_mode $mode | Out-Null }
  else { A shell am start -n "$pkg/$act" | Out-Null }
  Start-Sleep -Seconds 3
  $f = GetWinFrame
  for ($i=0; $i -lt $NScroll; $i++) { RSwipe $f 0.5 0.80 0.20 }
  for ($i=0; $i -lt $NScroll; $i++) { RSwipe $f 0.5 0.20 0.80 }
  for ($i=0; $i -lt $NTap; $i++) {
    RTap $f 0.5 0.45 800; Back
    if ($mode) { A shell am start -n "$pkg/$act" --es oc_perf_mode $mode | Out-Null }
    else { A shell am start -n "$pkg/$act" | Out-Null }
    Start-Sleep -Milliseconds 800; $f = GetWinFrame }
  for ($i=0; $i -lt [int]($NScroll/2); $i++) { RSwipe $f 0.85 0.5 0.15 }
  for ($i=0; $i -lt [int]($NScroll/2); $i++) { RSwipe $f 0.15 0.5 0.85 }
  RTap $f 0.5 0.5 600 }

function ParseGfx { param($outDir)
  $gfx = A shell dumpsys gfxinfo $WebPkg
  $gfx | Out-File -Encoding utf8 (Join-Path $outDir 'gfxinfo_full.txt')
  $gt = $gfx -join "`n"
  function Grab { param($k) if ($gt -match ("(?m)^\s*"+[regex]::Escape($k)+":\s*(.+)$")) { $Matches[1].Trim() } else { 'N/A' } }
  $r=[ordered]@{}
  foreach ($k in 'Total frames rendered','Janky frames','Janky frames (legacy)','50th percentile','90th percentile','95th percentile','99th percentile','Number Missed Vsync','Number High input latency','Number Slow UI thread','Number Slow bitmap uploads','Number Slow issue draw commands','50th gpu percentile') { $r[$k]=Grab $k }
  return $r }

# ================= 设备指纹 =================
Say "=== fingerprint ==="
$fp = @("date: $((Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))")
foreach ($g in 'ro.product.name','ro.build.version.release','ro.build.version.sdk','ro.product.cpu.abi','ro.hardware.egl') { $fp += "$g = $((A shell getprop $g) -join '')" }
$fp += "uptime: $((A shell uptime) -join ' ')"
$fp += "wm size: $((A shell wm size) -join ' / ')"
$fp += "wm density: $((A shell wm density) -join ' / ')"
$fp += "anim scales: $((A shell 'settings get global window_animation_scale; settings get global transition_animation_scale') -join ' / ')"
$fp += "web pkg: $((A shell "dumpsys package $WebPkg | grep -E 'lastUpdateTime|versionName'") -join ' | ')"
$fp += "nat pkg: $((A shell "dumpsys package $NatPkg | grep -E 'lastUpdateTime|versionName'") -join ' | ')"
$fp += "reverse: $((& $Adb -s $Dev reverse --list) -join ' ')"
$fp | Out-File -Encoding utf8 (Join-Path $OutRoot 'fingerprint.txt')
$fp | Write-Output

$results = @()

# ================= [A..E] 帧时归因分层 =================
foreach ($m in 'baseline','gzip','min','noanim','flat') {
  $dir = Join-Path $OutRoot $m; New-Item -ItemType Directory -Force -Path $dir | Out-Null
  Say "=== frame-time mode=$m (ScriptAB) ==="
  A shell dumpsys gfxinfo $WebPkg reset | Out-Null
  $sw=[Diagnostics.Stopwatch]::StartNew(); ScriptAB $WebPkg $WebAct $m; $sw.Stop()
  Start-Sleep -Seconds 2
  A shell am start -n "$WebPkg/$WebAct" --es oc_perf_mode $m | Out-Null; Start-Sleep -Seconds 2
  $g = ParseGfx $dir
  $row=[pscustomobject]@{ side='web'; mode=$m; wall_s=[math]::Round($sw.Elapsed.TotalSeconds,1)
    p50=$g['50th percentile']; p90=$g['90th percentile']; p99=$g['99th percentile']
    frames=$g['Total frames rendered']; janky=$g['Janky frames']; jankyLegacy=$g['Janky frames (legacy)']
    slowUI=$g['Number Slow UI thread']; slowDraw=$g['Number Slow issue draw commands']; gpuP50=$g['50th gpu percentile'] }
  $results += $row; $row | Format-List | Out-String | Write-Output
  $row | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $dir 'row.json')
}

# ================= [F] 原生同轮 p50（连上后端） =================
$natAct = (A shell "cmd package resolve-activity --brief $NatPkg" | Select-Object -Last 1) -replace '.*/',''
Say "=== native ScriptAB (act=$natAct) ==="
$dirN = Join-Path $OutRoot 'native'; New-Item -ItemType Directory -Force -Path $dirN | Out-Null
A shell dumpsys gfxinfo $NatPkg reset | Out-Null
$sw=[Diagnostics.Stopwatch]::StartNew(); ScriptAB $NatPkg $natAct ''; $sw.Stop()
Start-Sleep -Seconds 2
A shell am start -n "$NatPkg/$natAct" | Out-Null; Start-Sleep -Seconds 2
$gfxN = A shell dumpsys gfxinfo $NatPkg
$gfxN | Out-File -Encoding utf8 (Join-Path $dirN 'gfxinfo_full.txt')
$gtn = $gfxN -join "`n"
function GrabN { param($k) if ($gtn -match ("(?m)^\s*"+[regex]::Escape($k)+":\s*(.+)$")) { $Matches[1].Trim() } else { 'N/A' } }
$rowN=[pscustomobject]@{ side='native'; mode='native'; wall_s=[math]::Round($sw.Elapsed.TotalSeconds,1)
  p50=(GrabN '50th percentile'); p90=(GrabN '90th percentile'); p99=(GrabN '99th percentile')
  frames=(GrabN 'Total frames rendered'); janky=(GrabN 'Janky frames'); jankyLegacy=(GrabN 'Janky frames (legacy)')
  slowUI=(GrabN 'Number Slow UI thread'); slowDraw=(GrabN 'Number Slow issue draw commands'); gpuP50=(GrabN '50th gpu percentile') }
$results += $rowN; $rowN | Format-List | Out-String | Write-Output
$rowN | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $dirN 'row.json')

$results | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $OutRoot 'frametime_matrix.json')
Say "=== FRAMETIME MATRIX ==="
$results | Format-Table -AutoSize | Out-String | Write-Output

# ================= [G] gzip 合入复测：传输量 + 冷启逐段 + 输入延迟 =================
foreach ($m in 'baseline','gzip') {
  $dir = Join-Path $OutRoot "xfer_$m"; New-Item -ItemType Directory -Force -Path $dir | Out-Null
  Say "=== transfer+startup mode=$m ==="
  A shell am force-stop $WebPkg; Start-Sleep -Seconds 2
  A logcat -c | Out-Null
  $w = A shell am start -W -n "$WebPkg/$WebAct" --es oc_perf_mode $m
  $w | Out-File -Encoding utf8 (Join-Path $dir 'amstart.txt')
  Start-Sleep -Seconds 32
  A logcat -d -s OcPerf:V OcPerfJS:V OcWebHost:V OcWebHost/Proxy:V 2>$null |
    Out-File -Encoding utf8 (Join-Path $dir 'logcat.txt')
  # 输入延迟（滑动驱动，两侧同法）
  A shell dumpsys gfxinfo $WebPkg reset | Out-Null
  A shell am force-stop $WebPkg; Start-Sleep -Seconds 2
  A shell am start -n "$WebPkg/$WebAct" --es oc_perf_mode $m | Out-Null; Start-Sleep -Seconds 22
  $f = GetWinFrame
  for ($i=0; $i -lt $NScroll; $i++) { RSwipe $f 0.5 0.80 0.20 250 250 }
  for ($i=0; $i -lt $NScroll; $i++) { RSwipe $f 0.5 0.20 0.80 250 250 }
  Start-Sleep -Seconds 2
  A shell am start -n "$WebPkg/$WebAct" --es oc_perf_mode $m | Out-Null; Start-Sleep -Seconds 2
  $fs = A shell dumpsys gfxinfo $WebPkg framestats
  $fs | Out-File -Encoding utf8 (Join-Path $dir 'framestats_raw.txt')
  $ft = $fs -join "`n"
  $lines = $ft -split "`r?`n"; $hdr=$null; $rows=@()
  foreach ($l in $lines) {
    $c = $l.Trim()
    if ($c -like 'Flags,FrameTimelineVsyncId*') { $hdr = $c -split ','; continue }
    if ($hdr -and $c -match '^\d+,' -and $c.Split(',').Count -eq $hdr.Count) { $rows += ,$c.Split(',') } }
  if ($hdr) {
    $iH=[array]::IndexOf($hdr,'HandleInputStart'); $iF=[array]::IndexOf($hdr,'FrameCompleted')
    $i2f=@()
    foreach ($r in $rows) { $h=[double]$r[$iH]; $fc=[double]$r[$iF]; if ($h -gt 0 -and $fc -gt 0) { $i2f += (($fc-$h)/1e6) } }
    function Pct { param($a,[double]$p) if (-not $a -or $a.Count -eq 0) { return $null }
      $s2=$a|Sort-Object; $i=[math]::Min($s2.Count-1,[math]::Max(0,[math]::Ceiling($p*$s2.Count)-1)); [math]::Round($s2[$i],2) }
    $il=[pscustomobject]@{ mode=$m; n=$i2f.Count; p50=(Pct $i2f 0.5); p90=(Pct $i2f 0.9); p99=(Pct $i2f 0.99)
      mean=if($i2f.Count){[math]::Round(($i2f|Measure-Object -Average).Average,2)}else{$null} }
    $il | Format-List | Out-String | Write-Output
    $il | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $dir 'inputlatency.json')
  } else { Say "  WARN: framestats header not found ($m)" }
}

Say "=== DONE -> $OutRoot ==="
