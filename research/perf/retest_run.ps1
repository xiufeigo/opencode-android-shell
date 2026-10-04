# retest_run.ps1 — P4：用"修好的 APK"重跑四量（web 壳 vs 原生壳）
#
# 与 research/perf/perf_run.ps1 的关系（**故意且必须写明的差异**）：
#   1) perf_run.ps1 传 `--es oc_perf_mode baseline|fixed` 做同 APK 内分流。
#      本轮**不传**该 extra：交付 APK 的默认值已是 `perfMode="gzip"`
#      （见 WebHostActivity.kt:92，且 gzip 透传已合入），
#      传 `fixed` 会**重新打开 P2 已判定"净效果为负"的整体缓冲定界**，不是交付态。
#      ⇒ 本轮量的是**交付态默认行为**，与旧数的对照基线是 P2 的 **fixed#1(gzip)**。
#   2) perf_run.ps1 的 `$Pkg`/`$Act` 写死 web 壳；本轮要跑原生壳做对照，故参数化。
#   3) ScriptAB / RTap / RSwipe / NScroll / NTap / settle 时长 **逐字未改**。
#   4) 首屏改为**显式读 FCP/DOM 打点**（P2 教训：不得用"最后一个 _assets GET"当首屏）。
param(
  [ValidateSet('web','native')][string]$Side = 'web',
  [string]$Dev = 'emulator-5830',
  [string]$OutRoot = "$PSScriptRoot\out_p4"
)

$ErrorActionPreference = 'Continue'
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$OutDir = Join-Path $OutRoot $Side
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$NScroll = 10
$NTap = 6

if ($Side -eq 'web') {
  $Pkg = 'com.xiufeigo.opencodeshell.web.debug'
  $Act = 'com.xiufeigo.opencodeshell.web.WebHostActivity'
} else {
  $Pkg = 'com.xiufeigo.opencodeshell'
  $Act = 'com.xiufeigo.opencodeshell.OcActivity'
}

function A { & $Adb -s $Dev @args }
function Say { param($m) Write-Output ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }

function GetWinFrame {
  $raw = A shell "dumpsys window windows | grep -A30 '$Pkg/'"
  $t = $raw -join "`n"
  if ($t -match 'Frames:\s*parent=\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    return [pscustomobject]@{ L=[int]$Matches[1];T=[int]$Matches[2];R=[int]$Matches[3];B=[int]$Matches[4]
      W=([int]$Matches[3]-[int]$Matches[1]);H=([int]$Matches[4]-[int]$Matches[2]) }
  }
  return [pscustomobject]@{ L=0;T=0;R=1080;B=2400;W=1080;H=2400 }
}
function WPt { param($f,[double]$fx,[double]$fy) [int]($f.L+$f.W*$fx),[int]($f.T+$f.H*$fy) }
function RTap { param($f,[double]$fx,[double]$fy,[int]$ms=450)
  $p=WPt $f $fx $fy; A shell input tap $p[0] $p[1] | Out-Null; Start-Sleep -Milliseconds $ms }
function RSwipe { param($f,[double]$x,[double]$y1,[double]$y2,[int]$ms=300,[int]$ms2=350)
  $a=WPt $f $x $y1; $b=WPt $f $x $y2
  A shell input swipe $a[0] $a[1] $b[0] $b[1] $ms | Out-Null; Start-Sleep -Milliseconds $ms2 }
function Back { A shell input keyevent KEYCODE_BACK | Out-Null; Start-Sleep -Milliseconds 700 }

# ---- ScriptAB：与 ab_run.ps1 / perf_run.ps1 逐字相同 ------------------------
function ScriptAB {
  $f = GetWinFrame
  A shell am force-stop $Pkg; Start-Sleep -Milliseconds 800
  A shell am start -n "$Pkg/$Act" | Out-Null; Start-Sleep -Seconds 3
  $f = GetWinFrame
  for ($i=0; $i -lt $NScroll; $i++) { RSwipe $f 0.5 0.80 0.20 }
  for ($i=0; $i -lt $NScroll; $i++) { RSwipe $f 0.5 0.20 0.80 }
  for ($i=0; $i -lt $NTap; $i++) {
    RTap $f 0.5 0.45 800; Back
    A shell am start -n "$Pkg/$Act" | Out-Null; Start-Sleep -Milliseconds 800
    $f = GetWinFrame
  }
  for ($i=0; $i -lt [int]($NScroll/2); $i++) { RSwipe $f 0.85 0.5 0.15 }
  for ($i=0; $i -lt [int]($NScroll/2); $i++) { RSwipe $f 0.15 0.5 0.85 }
  RTap $f 0.5 0.5 600
}

# ================= 设备指纹 =================
Say "=== fingerprint (side=$Side) ==="
$fp = @("label=$Side pkg=$Pkg act=$Act", "no oc_perf_mode extra (APK default perfMode=gzip)",
        "date: $((Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))")
foreach ($g in 'ro.product.name','ro.product.model','ro.build.version.release','ro.build.version.sdk','ro.product.cpu.abi','ro.build.version.incremental') {
  $fp += "$g = $((A shell getprop $g) -join '')" }
$fp += "uptime: $((A shell uptime) -join ' ')"
$fp += "proc_uptime: $((A shell cat /proc/uptime) -join ' ')"
$fp += "wm size: $((A shell wm size) -join ' / ')"
$fp += "wm density: $((A shell wm density) -join ' / ')"
$fp += "webview: $((A shell dumpsys package com.google.android.webview | Select-String 'versionName' | Select-Object -First 1) -join '')"
$fp += "pkg: $((A shell "dumpsys package $Pkg | grep -E 'lastUpdateTime|versionName'") -join ' | ')"
$fp += "reverse: $((& $Adb -s $Dev reverse --list) -join ' ')"
$fp += "system_server_pid: $((A shell pidof system_server) -join '')"
$fp | Out-File -Encoding utf8 (Join-Path $OutDir 'fingerprint.txt')
$fp | Write-Output

# ================= [1] 首屏：冷启逐段剖面 =================
Say "=== [1] first-screen: cold start segment profile (FCP / DOM from marks) ==="
A shell am force-stop $Pkg; Start-Sleep -Seconds 2
A logcat -c | Out-Null
$w = A shell am start -W -n "$Pkg/$Act"
$w | Out-File -Encoding utf8 (Join-Path $OutDir 'amstart.txt')
$wt = $w -join "`n"
if ($wt -match '(?m)^\s*LaunchState:\s*(\S+)') { Say "  LaunchState=$($Matches[1])" }
if ($wt -match '(?m)^\s*TotalTime:\s*(\d+)')  { Say "  TotalTime=$($Matches[1]) ms" }
if ($wt -match '(?m)^\s*WaitTime:\s*(\d+)')   { Say "  WaitTime=$($Matches[1]) ms" }
# settle 30s（与 perf_run.ps1 一致；本轮尾巴更长，不截断）
Start-Sleep -Seconds 30
A logcat -d -s OcPerf:V OcPerfJS:V OcWebHost:V Chromium:E 2>$null |
  Out-File -Encoding utf8 (Join-Path $OutDir 'logcat.txt')
A logcat -d | Out-File -Encoding utf8 (Join-Path $OutDir 'logcat_full.txt')
$lt = (A logcat -d -s OcPerf:V OcPerfJS:V OcWebHost:V) -join "`n"
$lt | Out-File -Encoding utf8 (Join-Path $OutDir 'logcat_marks.txt')
Say "  logcat captured: $((Get-Item (Join-Path $OutDir 'logcat.txt')).Length) bytes"

# --- 显式解析首屏（**不用**"最后一个 _assets GET"当首屏 —— P2 §2.3 教训）---
# ⚠️ 两条实测事实（上一版解析器踩过）：
#   (a) 浏览器侧打点**不走 OcPerfJS tag**，走 `OcWebHost: web[LOG] OcPerfJS …` ⇒ 必须一并抓 OcWebHost；
#   (b) 原生侧格式是 `A.onCreate.enter t=585ms`（**t=…ms**，不是 P2 文档里的 at=…ms）⇒ 正则要吃 `t=`。
$first = [ordered]@{}
$marks = [ordered]@{}
foreach ($m in [regex]::Matches($lt, '\s([A-Z][A-Za-z0-9]*\.[A-Za-z0-9.+\-]+)\s+t=(\d+)ms')) {
  $marks[$m.Groups[1].Value] = [int]$m.Groups[2].Value }
# 浏览器侧
foreach ($m in [regex]::Matches($lt, 'first-contentful-paint\D{0,12}?(\d+)')) { $first['FCP_ms'] = [int]$m.Groups[1].Value; break }
if (-not $first.Contains('FCP_ms')) {
  foreach ($m in [regex]::Matches($lt, 'first-paint\D{0,12}?(\d+)')) { $first['FCP_ms'] = [int]$m.Groups[1].Value; break } }
foreach ($m in [regex]::Matches($lt, 'tick\+?\d*\s+t=(\d+)\s+nodes=(\d+)')) {
  if (-not $first.Contains('DOM_readyMs') -or [int]$m.Groups[2].Value -gt 0) {
    $first['DOM_readyMs'] = [int]$m.Groups[1].Value; $first['DOM_nodes'] = [int]$m.Groups[2].Value } }
foreach ($m in [regex]::Matches($lt, '\bDCL\s+(\d+)'))    { $first['DCL_ms'] = [int]$m.Groups[1].Value; break }
foreach ($m in [regex]::Matches($lt, 'doc-start\D{0,40}?navStart=(\d+)')) { $first['navStart_ms'] = [int]$m.Groups[1].Value; break }
# 传输量（gzip 是否生效的自证）
$lastRT = $null
foreach ($m in [regex]::Matches($lt, 'res-total n=(\d+)\s+xfer=(\d+)\s+dec=(\d+)\s+ratio=([\d.]+)')) {
  $lastRT = [ordered]@{ n=[int]$m.Groups[1].Value; xfer=[int]$m.Groups[2].Value; dec=[int]$m.Groups[3].Value; ratio=[double]$m.Groups[4].Value } }
if ($lastRT) { $first['res_total'] = $lastRT }
foreach ($k in $marks.Keys) { if ($k -in @('Z.firstDraw','K.onPageStarted','L.onPageFinished','G.onCreate.exit','J.loadUrlIssued','I.proxyListening','H.launch.enter')) { $first[$k] = $marks[$k] } }
# 尾巴（单独记，**明确标注不是首屏**）
$lastAsset = $null; $nFwd = 0
foreach ($m in [regex]::Matches($lt, 'P\.fwd\D*?t=(\d+)ms')) { $lastAsset = [int]$m.Groups[1].Value; $nFwd++ }
if ($null -ne $lastAsset) { $first['LAST_asset_fwd_at_NOT_firstscreen'] = $lastAsset; $first['fwd_count'] = $nFwd }
$first | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $OutDir 'first_screen.json')
Write-Output "  FIRST_SCREEN: $($first | ConvertTo-Json -Compress)"

# ================= [2] 稳态帧时 =================
Say "=== [2] steady-state gfxinfo: reset -> ScriptAB -> dump ==="
A shell dumpsys gfxinfo $Pkg reset | Out-Null
$sw = [Diagnostics.Stopwatch]::StartNew()
ScriptAB
$sw.Stop()
Say ("  interaction wall = {0:N1} s" -f $sw.Elapsed.TotalSeconds)
Start-Sleep -Seconds 2
A shell am start -n "$Pkg/$Act" | Out-Null; Start-Sleep -Seconds 2
$gfx = A shell dumpsys gfxinfo $Pkg
$gfx | Out-File -Encoding utf8 (Join-Path $OutDir 'gfxinfo_full.txt')
$gt = $gfx -join "`n"
function Grab { param($k) if ($gt -match ("(?m)^\s*"+[regex]::Escape($k)+":\s*(.+)$")) { $Matches[1].Trim() } else { 'N/A' } }
$parsed = [ordered]@{}
foreach ($k in 'Total frames rendered','Janky frames','Janky frames (legacy)','50th percentile','90th percentile','95th percentile','99th percentile','Number Slow UI thread','Number Slow issue draw commands','Number Missed Vsync','50th gpu percentile','90th gpu percentile','99th gpu percentile','Number High input latency','Number Slow bitmap uploads','Number Slow issue draw commands') {
  $v = Grab $k; $parsed[$k] = $v; Write-Output ("  {0,-34}= {1}" -f $k,$v) }
$parsed['interaction_wall_s'] = [math]::Round($sw.Elapsed.TotalSeconds,1)
$parsed | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $OutDir 'gfxinfo_parsed.json')

# ================= [3] 输入延迟代理（滑动驱动，两侧同参数）=================
Say "=== [3] input-latency PROXY (swipe-driven, equal-sample method) ==="
A shell dumpsys gfxinfo $Pkg reset | Out-Null
A shell am force-stop $Pkg; Start-Sleep -Seconds 2
A shell am start -n "$Pkg/$Act" | Out-Null; Start-Sleep -Seconds 22
$f = GetWinFrame
for ($i=0; $i -lt $NScroll; $i++) { RSwipe $f 0.5 0.80 0.20 250 250 }
for ($i=0; $i -lt $NScroll; $i++) { RSwipe $f 0.5 0.20 0.80 250 250 }
Start-Sleep -Seconds 2
A shell am start -n "$Pkg/$Act" | Out-Null; Start-Sleep -Seconds 2
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
  $res=[ordered]@{ n=$i2f.Count
    i2f_p50=Pct $i2f 0.5; i2f_p90=Pct $i2f 0.9; i2f_p95=Pct $i2f 0.95
    i2f_p99=Pct $i2f 0.99
    i2f_mean=if($i2f.Count){[math]::Round(($i2f|Measure-Object -Average).Average,2)}else{$null}
    i2f_max=if($i2f.Count){[math]::Round(($i2f|Measure-Object -Maximum).Maximum,2)}else{$null}
    i2s_p50=Pct $i2s 0.5 }
  Say "  frames parsed n=$($i2f.Count)"
  Write-Output "  INPUT_LATENCY: $($res | ConvertTo-Json -Compress)"
  $res | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $OutDir 'inputlatency.json')
} else { Say "  WARN: PROFILEDATA header not found" }

# ================= [4] 耗电：模拟器可测性取证 =================
Say "=== [4] battery / power: measurability probe ==="
$bat = [ordered]@{}
$bat['batterystats_discharge'] = ((A shell dumpsys batterystats | Select-String -m2 'Discharge|discharge') -join ' | ')
$bat['batterystats_level']    = ((A shell dumpsys batterystats | Select-String -m1 'level=') -join ' | ')
$bat['battery_present']       = ((A shell dumpsys battery | Select-String -Pattern 'level|scale|voltage|AC powered|USB powered|wireless powered|status|health|powered') -join ' ; ')
$bat['cpu_power_supply']      = ((A shell 'cat /sys/class/power_supply/ 2>/dev/null') -join ' ')
$bat['current_now_files']     = ((A shell 'ls /sys/class/power_supply/*/current_now 2>/dev/null') -join ' ')
$bat['ro_hw']                 = ((A shell 'getprop | grep -E "ro.hardware|ro.boot.qemu|ro.kernel.qemu|ro.product.cpu.abi"') -join ' ; ')
$bat | Out-File -Encoding utf8 (Join-Path $OutDir 'battery_probe.txt')
Write-Output "  BATTERY: $($bat | ConvertTo-Json -Compress)"

Say "=== DONE side=$Side -> $OutDir ==="
