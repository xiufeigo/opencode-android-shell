# perf_run.ps1 — P2 性能归因：冷启逐段剖面 + 稳态 p50 帧时 + 输入延迟代理
#
# 关键设计：**同一个 APK 跑两次**（`--es oc_perf_mode baseline|fixed`），
# 改前/改后二进制完全一致，排除"重编译差异"污染对比。
#
# 用法：
#   pwsh -NoProfile -File research\perf\perf_run.ps1 -Mode baseline
#   pwsh -NoProfile -File research\perf\perf_run.ps1 -Mode fixed
#
# 交互（ScriptAB）与 research\measure\ab_run.ps1 **逐字相同**，保证 p50 可比。

param(
  [ValidateSet('baseline','fixed')][string]$Mode = 'baseline',
  [string]$Dev = 'emulator-5636',
  [string]$OutRoot = "$PSScriptRoot\out"
)

$ErrorActionPreference = 'Continue'
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Pkg = 'com.xiufeigo.opencodeshell.web.debug'
$Act = 'com.xiufeigo.opencodeshell.web.WebHostActivity'
$OutDir = Join-Path $OutRoot $Mode
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$NScroll = 10
$NTap = 6

function A { & $Adb -s $Dev @args }
function Say { param($m) Write-Output ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }

function GetWinFrame {
  $raw = A shell "dumpsys window windows | grep -A30 '$Pkg/'"
  $t = $raw -join "`n"
  if ($t -match 'Frames:\s*parent=\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    return [pscustomobject]@{ L=[int]$Matches[1];T=[int]$Matches[2];R=[int]$Matches[3];B=[int]$Matches[4]
      W=([int]$Matches[3]-[int]$Matches[1]);H=([int]$Matches[4]-[int]$Matches[2]) }
  }
  return [pscustomobject]@{ L=0;T=0;R=1366;B=768;W=1366;H=768 }
}
function WPt { param($f,[double]$fx,[double]$fy) [int]($f.L+$f.W*$fx),[int]($f.T+$f.H*$fy) }
function RTap { param($f,[double]$fx,[double]$fy,[int]$ms=450)
  $p=WPt $f $fx $fy; A shell input tap $p[0] $p[1] | Out-Null; Start-Sleep -Milliseconds $ms }
function RSwipe { param($f,[double]$x,[double]$y1,[double]$y2,[int]$ms=300,[int]$ms2=350)
  $a=WPt $f $x $y1; $b=WPt $f $x $y2
  A shell input swipe $a[0] $a[1] $b[0] $b[1] $ms | Out-Null; Start-Sleep -Milliseconds $ms2 }
function Back { A shell input keyevent KEYCODE_BACK | Out-Null; Start-Sleep -Milliseconds 700 }

# ---- ScriptAB：与 ab_run.ps1 逐字相同 -----------------------------------
function ScriptAB {
  $f = GetWinFrame
  A shell am force-stop $Pkg; Start-Sleep -Milliseconds 800
  A shell am start -n "$Pkg/$Act" --es oc_perf_mode $Mode | Out-Null; Start-Sleep -Seconds 3
  $f = GetWinFrame
  for ($i=0; $i -lt $NScroll; $i++) { RSwipe $f 0.5 0.80 0.20 }
  for ($i=0; $i -lt $NScroll; $i++) { RSwipe $f 0.5 0.20 0.80 }
  for ($i=0; $i -lt $NTap; $i++) {
    RTap $f 0.5 0.45 800; Back
    A shell am start -n "$Pkg/$Act" --es oc_perf_mode $Mode | Out-Null; Start-Sleep -Milliseconds 800
    $f = GetWinFrame
  }
  for ($i=0; $i -lt [int]($NScroll/2); $i++) { RSwipe $f 0.85 0.5 0.15 }
  for ($i=0; $i -lt [int]($NScroll/2); $i++) { RSwipe $f 0.15 0.5 0.85 }
  RTap $f 0.5 0.5 600
}

# ================= 设备指纹 =================
Say "=== fingerprint (mode=$Mode) ==="
$fp = @("label=$Mode pkg=$Pkg act=$Act", "mode=$Mode", "date: $((Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))")
foreach ($g in 'ro.product.name','ro.build.version.release','ro.build.version.sdk','ro.product.cpu.abi') {
  $fp += "$g = $((A shell getprop $g) -join '')" }
$fp += "uptime: $((A shell uptime) -join ' ')"
$fp += "wm size: $((A shell wm size) -join ' / ')"
$fp += "wm density: $((A shell wm density) -join ' / ')"
$fp += "pkg: $((A shell "dumpsys package $Pkg | grep -E 'lastUpdateTime|versionName'") -join ' | ')"
$fp += "reverse: $((& $Adb -s $Dev reverse --list) -join ' ')"
$fp | Out-File -Encoding utf8 (Join-Path $OutDir 'fingerprint.txt')
$fp | Write-Output

# ================= [1] 冷启逐段剖面 =================
Say "=== [1] startup segment profile (cold) ==="
A shell am force-stop $Pkg; Start-Sleep -Seconds 2
A logcat -c | Out-Null
$w = A shell am start -W -n "$Pkg/$Act" --es oc_perf_mode $Mode
$w | Out-File -Encoding utf8 (Join-Path $OutDir 'amstart.txt')
$wt = $w -join "`n"
if ($wt -match '(?m)^\s*LaunchState:\s*(\S+)') { Say "  LaunchState=$($Matches[1])" }
if ($wt -match '(?m)^\s*TotalTime:\s*(\d+)')  { Say "  TotalTime=$($Matches[1]) ms" }
# 等内容真的出来（baseline 已知 ~15.4s，留 30s 窗口）
Start-Sleep -Seconds 30
A logcat -d -s OcPerf:V OcPerfJS:V OcWebHost:V Chromium:E 2>$null |
  Out-File -Encoding utf8 (Join-Path $OutDir 'logcat.txt')
A logcat -d | Out-File -Encoding utf8 (Join-Path $OutDir 'logcat_full.txt')
Say "  logcat captured: $((Get-Item (Join-Path $OutDir 'logcat.txt')).Length) bytes"

# ================= [2] 稳态 p50 帧时（ScriptAB，与 AB 脚本逐字相同）=====
Say "=== [2] steady-state gfxinfo: reset -> ScriptAB -> dump ==="
A shell dumpsys gfxinfo $Pkg reset | Out-Null
$sw = [Diagnostics.Stopwatch]::StartNew()
ScriptAB
$sw.Stop()
Say ("  interaction wall = {0:N1} s" -f $sw.Elapsed.TotalSeconds)
Start-Sleep -Seconds 2
A shell am start -n "$Pkg/$Act" --es oc_perf_mode $Mode | Out-Null; Start-Sleep -Seconds 2
$gfx = A shell dumpsys gfxinfo $Pkg
$gfx | Out-File -Encoding utf8 (Join-Path $OutDir 'gfxinfo_full.txt')
$gt = $gfx -join "`n"
function Grab { param($k) if ($gt -match ("(?m)^\s*"+[regex]::Escape($k)+":\s*(.+)$")) { $Matches[1].Trim() } else { 'N/A' } }
$parsed = @()
foreach ($k in 'Total frames rendered','Janky frames','Janky frames (legacy)','50th percentile','90th percentile','95th percentile','99th percentile','Number Slow UI thread','Number Slow issue draw commands','50th gpu percentile') {
  $v = Grab $k; $parsed += [pscustomobject]@{ key=$k; value=$v }
  Write-Output ("  {0,-34}= {1}" -f $k,$v) }
$parsed | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $OutDir 'gfxinfo_parsed.json')
Write-Output ("  interaction_wall_s = {0:N1}" -f $sw.Elapsed.TotalSeconds)

# ================= [3] 输入延迟代理：等样本量的滑动采样 =================
# 上一轮 n=3 vs n=22 不可比，根因是"点按在两侧产生的帧数不同"。
# 本轮改用**滑动**驱动帧：两侧都能稳定产生大量帧，样本量自然对齐。
Say "=== [3] input-latency PROXY (swipe-driven, equal-sample method) ==="
A shell dumpsys gfxinfo $Pkg reset | Out-Null
A shell am force-stop $Pkg; Start-Sleep -Seconds 2
A shell am start -n "$Pkg/$Act" --es oc_perf_mode $Mode | Out-Null; Start-Sleep -Seconds 22
$f = GetWinFrame
for ($i=0; $i -lt $NScroll; $i++) { RSwipe $f 0.5 0.80 0.20 250 250 }
for ($i=0; $i -lt $NScroll; $i++) { RSwipe $f 0.5 0.20 0.80 250 250 }
Start-Sleep -Seconds 2
A shell am start -n "$Pkg/$Act" --es oc_perf_mode $Mode | Out-Null; Start-Sleep -Seconds 2
$fs = A shell dumpsys gfxinfo $Pkg framestats
$fs | Out-File -Encoding utf8 (Join-Path $OutDir 'framestats_raw.txt')
$ft = $fs -join "`n"
$lines = $ft -split "`r?`n"; $hdr=$null; $rows=@()
foreach ($l in $lines) {
  $c = $l.Trim()
  if ($c -like 'Flags,FrameTimelineVsyncId*') { $hdr = $c -split ','; continue }
  if ($hdr -and $c -match '^\d+,' -and $c.Split(',').Count -eq $hdr.Count) { $rows += ,$c.Split(',') } }
if ($hdr) {
  $iH=[array]::IndexOf($hdr,'HandleInputStart'); $iF=[array]::IndexOf($hdr,'FrameCompleted')
  $iS=[array]::IndexOf($hdr,'SwapBuffersCompleted')
  $i2f=@(); $i2s=@()
  foreach ($r in $rows) {
    $h=[double]$r[$iH]; $fc=[double]$r[$iF]; $s=[double]$r[$iS]
    if ($h -gt 0 -and $fc -gt 0) { $i2f += (($fc-$h)/1e6); if ($s -gt 0) { $i2s += (($s-$h)/1e6) } } }
  function Pct { param($a,[double]$p) if (-not $a -or $a.Count -eq 0) { return $null }
    $s2=$a|Sort-Object; $i=[math]::Min($s2.Count-1,[math]::Max(0,[math]::Ceiling($p*$s2.Count)-1)); [math]::Round($s2[$i],2) }
  $res=[pscustomobject]@{ n=$i2f.Count
    i2f_p50=Pct $i2f 0.5; i2f_p90=Pct $i2f 0.9
    i2f_p99=Pct $i2f 0.99
    i2f_mean=if($i2f.Count){[math]::Round(($i2f|Measure-Object -Average).Average,2)}else{$null}
    i2f_max=if($i2f.Count){[math]::Round(($i2f|Measure-Object -Maximum).Maximum,2)}else{$null}
    i2s_p50=Pct $i2s 0.5 }
  Say "  frames parsed n=$($i2f.Count)"
  $res | Format-List | Out-String | Write-Output
  $res | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $OutDir 'inputlatency.json')
} else { Say "  WARN: PROFILEDATA header not found" }

Say "=== DONE $Mode -> $OutDir ==="
