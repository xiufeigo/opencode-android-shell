# ab_run.ps1 — AB harness: 原生壳 vs Web 壳，同设备 / 同脚本 / 同轮次 / 同参数。
#
# 用法（两边跑的是**同一个文件**，只换 MEASURE_PKG / MEASURE_ACT / MEASURE_LABEL）：
#   $env:MEASURE_DEV='emulator-5636'
#   $env:MEASURE_PKG='com.xiufeigo.opencodeshell'
#   $env:MEASURE_ACT='.OcActivity'
#   $env:MEASURE_LABEL='native'
#   pwsh -NoProfile -File research\measure\ab_run.ps1
#
# 为什么交互是"窗口相对"而不是绝对坐标：
#   两个 app 在 5636 上都是**浮动小窗**（原生 303x618，Web ~300x600），
#   且浮动窗位置/尺寸随 app 变。绝对坐标对两边不公平也不同命中。
#   因此本脚本每次都用 `dumpsys window` 读**该 app 自己窗口的 frame**，
#   再在窗口内按**相同比例**做手势 —— 两边的手势次数 / 距离 / 时长完全一致。

$ErrorActionPreference = 'Continue'

$Adb   = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Dev   = if ($env:MEASURE_DEV)    { $env:MEASURE_DEV }    else { 'emulator-5636' }
$Pkg   = if ($env:MEASURE_PKG)    { $env:MEASURE_PKG }    else { 'com.xiufeigo.opencodeshell' }
$Act   = if ($env:MEASURE_ACT)    { $env:MEASURE_ACT }    else { '.OcActivity' }
$Label = if ($env:MEASURE_LABEL)  { $env:MEASURE_LABEL }  else { 'run' }
# 电量窗口秒数：两边必须相同（可 env 覆盖，但一次 round 内不许改）
$BatWin = if ($env:MEASURE_BATWIN) { [int]$env:MEASURE_BATWIN } else { 120 }
# 手势强度：两边必须相同
$NScroll = if ($env:MEASURE_NSCROLL) { [int]$env:MEASURE_NSCROLL } else { 10 }
$NTap    = if ($env:MEASURE_NTAP)    { [int]$env:MEASURE_NTAP }    else { 6 }

$OutDir = Join-Path $PSScriptRoot "out_ab\$Label"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function A { & $Adb -s $Dev @args }
function Say { param($m) Write-Output ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }

# ---- 读该 app 自己的窗口 frame（相对手势的基准） ----------------------
function GetWinFrame {
  $raw = A shell "dumpsys window windows | grep -A30 '$Pkg/'"
  $t = $raw -join "`n"
  if ($t -match 'Frames:\s*parent=\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    return [pscustomobject]@{
      L=[int]$Matches[1]; T=[int]$Matches[2]; R=[int]$Matches[3]; B=[int]$Matches[4]
      W=([int]$Matches[3]-[int]$Matches[1]); H=([int]$Matches[4]-[int]$Matches[2])
    }
  }
  return [pscustomobject]@{ L=0;T=0;R=1366;B=768;W=1366;H=768 }
}
# 窗口内比例点
function WPt { param($f,[double]$fx,[double]$fy)
  [int]($f.L + $f.W*$fx), [int]($f.T + $f.H*$fy)
}
function RTap { param($f,[double]$fx,[double]$fy,[int]$ms=450)
  $p = WPt $f $fx $fy ; A shell input tap $p[0] $p[1] | Out-Null ; Start-Sleep -Milliseconds $ms }
function RSwipe { param($f,[double]$x,[double]$y1,[double]$y2,[int]$ms=300,[int]$ms2=350)
  $a = WPt $f $x $y1 ; $b = WPt $f $x $y2
  A shell input swipe $a[0] $a[1] $b[0] $b[1] $ms | Out-Null ; Start-Sleep -Milliseconds $ms2 }
function Back { A shell input keyevent KEYCODE_BACK | Out-Null ; Start-Sleep -Milliseconds 700 }

# ---- SCRIPT-AB：两边逐字相同的固定交互 -------------------------------
function ScriptAB {
  $f = GetWinFrame
  Say ("win frame: L=$($f.L) T=$($f.T) R=$($f.R) B=$($f.B)  (W=$($f.W) H=$($f.H))")
  # 1) 冷启动
  A shell am force-stop $Pkg ; Start-Sleep -Milliseconds 800
  A shell am start -n "$Pkg/$Act" | Out-Null ; Start-Sleep -Seconds 3
  $f = GetWinFrame
  # 2) 窗口内垂直滚动：上 N 次 + 下 N 次
  for ($i=0; $i -lt $NScroll; $i++) { RSwipe $f 0.5 0.80 0.20 }
  for ($i=0; $i -lt $NScroll; $i++) { RSwipe $f 0.5 0.20 0.80 }
  # 3) 窗口内点按 + BACK + 立即拉回前台（避免进程被 freeze）
  for ($i=0; $i -lt $NTap; $i++) {
    RTap $f 0.5 0.45 800 ; Back
    A shell am start -n "$Pkg/$Act" | Out-Null ; Start-Sleep -Milliseconds 800
    $f = GetWinFrame
  }
  # 4) 水平滑动（切页/侧栏）
  for ($i=0; $i -lt [int]($NScroll/2); $i++) { RSwipe $f 0.85 0.5 0.15 }
  for ($i=0; $i -lt [int]($NScroll/2); $i++) { RSwipe $f 0.15 0.5 0.85 }
  RTap $f 0.5 0.5 600
}

function EnsureForeground {
  A shell am start -n "$Pkg/$Act" | Out-Null ; Start-Sleep -Seconds 2
  $p = (A shell pidof $Pkg | Select-Object -First 1)
  if (-not $p) { A shell am start -n "$Pkg/$Act" | Out-Null ; Start-Sleep -Seconds 2 ; $p = (A shell pidof $Pkg | Select-Object -First 1) }
  "$p"
}

# ================= 设备指纹 =================
Say "=== device fingerprint ==="
$fp = @()
$fp += "label=$Label pkg=$Pkg act=$Act"
$fp += "date: $((Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))"
foreach ($g in 'ro.product.name','ro.build.version.release','ro.build.version.sdk','ro.product.cpu.abi') {
  $fp += "$g = $((A shell getprop $g) -join '')"
}
$fp += "uptime: $((A shell uptime) -join ' ')"
$fp += "wm size: $((A shell wm size) -join ' / ')"
$fp += "wm density: $((A shell wm density) -join ' / ')"
$pk = (A shell "dumpsys package $Pkg | grep -E 'lastUpdateTime|versionName|versionCode'" | Where-Object { $_ -match '=' })
foreach ($l in $pk) { $fp += "pkg: $($l.Trim())" }
$fp += "reverse: $((& $Adb -s $Dev reverse --list) -join ' ')"
$fp | Out-File -Encoding utf8 (Join-Path $OutDir 'fingerprint.txt')
$fp | Write-Output

# ================= 量一：冷启首帧 am start -W x3 =================
Say "=== [1] first frame  am start -W x3 ==="
$ff = @()
for ($i=1; $i -le 3; $i++) {
  A shell am force-stop $Pkg ; Start-Sleep -Seconds 2
  $o = A shell am start -W -n "$Pkg/$Act"
  $o | Out-File -Encoding utf8 (Join-Path $OutDir "amstart_$i.txt")
  $t = $o -join "`n"
  $num = { param($k) if ($t -match ("(?m)^\s*"+[regex]::Escape($k)+":\s*(-?\d+)")) { [int]$Matches[1] } else { $null } }
  $ls = if ($t -match '(?m)^\s*LaunchState:\s*(\S+)') { $Matches[1] } else { '?' }
  $ff += [pscustomobject]@{ run=$i; LaunchState=$ls; TotalTime=(& $num 'TotalTime'); WaitTime=(& $num 'WaitTime'); ThisTimeTotal=(& $num 'ThisTimeTotal') }
  Write-Output ("  run {0}: {1} TotalTime={2} WaitTime={3} ThisTimeTotal={4}" -f $i,$ls,(& $num 'TotalTime'),(& $num 'WaitTime'),(& $num 'ThisTimeTotal'))
  Start-Sleep -Seconds 2
}
$ff | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $OutDir 'firstframe.json')
$tt = $ff | ForEach-Object { $_.TotalTime } | Where-Object { $_ }
if ($tt) {
  $s = $tt | Measure-Object -Minimum -Maximum -Average
  Say ("  TotalTime  min={0} max={1} mean={2:N0} median={3}" -f $s.Minimum,$s.Maximum,$s.Average,($tt | Sort-Object)[[int]($tt.Count/2)])
}

# ================= 量二：gfxinfo janky / percentile =================
Say "=== [2] gfxinfo: reset -> ScriptAB -> dump ==="
A shell dumpsys gfxinfo $Pkg reset | Out-Null
$sw = [Diagnostics.Stopwatch]::StartNew()
ScriptAB
$sw.Stop()
Say ("  interaction wall time = {0:N1} s" -f $sw.Elapsed.TotalSeconds)
Start-Sleep -Seconds 2
$pid_ = EnsureForeground
Say "  pid=$pid_"
$gfx = A shell dumpsys gfxinfo $Pkg
$gfx | Out-File -Encoding utf8 (Join-Path $OutDir 'gfxinfo_full.txt')
$gt = $gfx -join "`n"
$grab = { param($k) if ($gt -match ("(?m)^\s*"+[regex]::Escape($k)+":\s*(.+)$")) { $Matches[1].Trim() } else { 'N/A' } }
$keys = 'Total frames rendered','Janky frames','Janky frames (legacy)','50th percentile','90th percentile','95th percentile','99th percentile','Number Missed Vsync','Number High input latency','Number Slow UI thread','Number Slow bitmap uploads','Number Slow issue draw commands','Number Frame deadline missed','50th gpu percentile'
$parsed = @()
foreach ($k in $keys) { $v = & $grab $k ; $parsed += [pscustomobject]@{ key=$k; value=$v } ; Write-Output ("  {0,-34}= {1}" -f $k,$v) }
$parsed | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $OutDir 'gfxinfo_parsed.json')
Write-Output ("  interaction_wall_s = {0:N1}" -f $sw.Elapsed.TotalSeconds)

# ================= 量三：输入延迟代理 framestats =================
Say "=== [3] input-latency PROXY: framestats HandleInputStart->FrameCompleted ==="
A shell dumpsys gfxinfo $Pkg reset | Out-Null
A shell am force-stop $Pkg ; Start-Sleep -Milliseconds 800
A shell am start -n "$Pkg/$Act" | Out-Null ; Start-Sleep -Seconds 4
$f = GetWinFrame
# 12 次输入驱动点击（点在窗口内正文区），每次都应产生可见界面变化
for ($i=0; $i -lt 12; $i++) { RTap $f 0.5 0.45 500 ; Back ; A shell am start -n "$Pkg/$Act" | Out-Null ; Start-Sleep -Milliseconds 700 }
Start-Sleep -Seconds 2
$null = EnsureForeground
$fs = A shell dumpsys gfxinfo $Pkg framestats
$fs | Out-File -Encoding utf8 (Join-Path $OutDir 'framestats_raw.txt')
$ft = $fs -join "`n"
# 解析 PROFILEDATA
$lines = $ft -split "`r?`n"
$hdr = $null; $rows = @()
foreach ($l in $lines) {
  $c = $l.Trim()
  if ($c -like 'Flags,FrameTimelineVsyncId*') { $hdr = $c -split ',' ; continue }
  if ($hdr -and $c -match '^\d+,' -and $c.Split(',').Count -eq $hdr.Count) { $rows += ,$c.Split(',') }
}
if ($hdr) {
  $iH = [array]::IndexOf($hdr,'HandleInputStart'); $iF = [array]::IndexOf($hdr,'FrameCompleted')
  $iS = [array]::IndexOf($hdr,'SwapBuffersCompleted'); $iI = [array]::IndexOf($hdr,'IntendedVsync'); $iV = [array]::IndexOf($hdr,'FrameInterval')
  $i2f=@(); $i2s=@(); $fr=@(); $fiv=@()
  foreach ($r in $rows) {
    $h=[double]$r[$iH]; $f=[double]$r[$iF]; $s=[double]$r[$iS]; $iv=[double]$r[$iI]; $fi=[double]$r[$iV]
    if ($h -gt 0 -and $f -gt 0) { $i2f += (($f-$h)/1e6); if ($s -gt 0) { $i2s += (($s-$h)/1e6) }; if ($iv -gt 0) { $fr += (($f-$iv)/1e6); $fiv += ($fi/1e6) } }
  }
  function Pct { param($a,[double]$p) if (-not $a -or $a.Count -eq 0) { return $null }
    $s2 = $a | Sort-Object ; $i = [math]::Min($s2.Count-1,[math]::Max(0,[math]::Ceiling($p*$s2.Count)-1)) ; return [math]::Round($s2[$i],2) }
  $res = [pscustomobject]@{
    n = $i2f.Count
    i2f_p50 = Pct $i2f 0.5 ; i2f_p90 = Pct $i2f 0.9 ; i2f_max = if($i2f.Count){[math]::Round(($i2f|Measure-Object -Maximum).Maximum,2)}else{$null}
    i2f_mean = if($i2f.Count){[math]::Round(($i2f|Measure-Object -Average).Average,2)}else{$null}
    i2s_p50 = Pct $i2s 0.5
    frame_p50 = Pct $fr 0.5 ; frame_p90 = Pct $fr 0.9
    frame_interval_ms = if($fiv.Count){[math]::Round(($fiv|Measure-Object -Average).Average,3)}else{$null}
  }
  Say ("  frames parsed = {0}" -f $i2f.Count)
  $res | Format-List | Out-String | Write-Output
  $res | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $OutDir 'inputlatency.json')
} else { Say "  WARN: PROFILEDATA header not found" }

# ================= 量四：耗电（batterystats 固定窗口） =================
Say ("=== [4] batterystats fixed window = {0}s (mAh 大概率测不到，取 CPU 代理) ===" -f $BatWin)
A shell input keyevent KEYCODE_WAKEUP | Out-Null
A shell am force-stop $Pkg ; Start-Sleep -Milliseconds 500
A shell am start -n "$Pkg/$Act" | Out-Null ; Start-Sleep -Seconds 2
A shell dumpsys batterystats --reset | Out-Null
$bw = [Diagnostics.Stopwatch]::StartNew()
$rounds = 0
while ($bw.Elapsed.TotalSeconds -lt $BatWin) {
  ScriptAB | Out-Null ; $rounds++
  Say ("  round {0} @ {1:N0}s" -f $rounds, $bw.Elapsed.TotalSeconds)
}
A shell am force-stop $Pkg
$bw.Stop()
Say ("  window actual = {0:N1}s over {1} rounds" -f $bw.Elapsed.TotalSeconds, $rounds)
$bs = A shell dumpsys batterystats $Pkg
$bs | Out-File -Encoding utf8 (Join-Path $OutDir 'batterystats_full.txt')
$bt = $bs -join "`n"
$bl = $bt -split "`r?`n"
$keep = $bl | Where-Object { $_ -match 'currently on battery|Discharge:|Time on battery|Capacity:|UID u0a|CPU power brackets|Total cpu time reads|Wake lock' }
$keep | Select-Object -First 25 | Write-Output
$uid = ($bl | Where-Object { $_ -match '^\s*UID u0a\d+:' } | Select-Object -First 1)
Say "  uid line: $uid"
[pscustomobject]@{ window_s=[math]::Round($bw.Elapsed.TotalSeconds,1); rounds=$rounds; uid_line=$uid } |
  ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $OutDir 'battery.json')

Say "=== DONE $Label -> $OutDir ==="
