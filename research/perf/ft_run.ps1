# ft_run.ps1 — P4 精简版：只测「稳态帧时」与「输入延迟代理」。
#
# 为什么不直接用 retest_run.ps1：本轮设备**两次被外部 kill**（21:37:44、21:42:00，
# 两次都在 ScriptAB 期间），所以砍掉 [1] 首屏那 30 s settle 与整段 logcat 抓取，
# 把设备占用窗口压到最短。**被砍的部分已在 out_p4/web/first_screen.json 留下两个样本**。
#
# 没被砍、也**一个字没改**的部分（保证与 AB1 / P2 可比）：
#   ScriptAB 全文、NScroll=10 / NTap=6、窗口相对手势、
#   输入延迟的冷启后固定 22 s 等待 + 10 上滑 + 10 下滑（swipe 250ms / 间隔 250ms）、
#   `dumpsys gfxinfo <pkg> framestats` 解析 HandleInputStart→FrameCompleted。
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
if ($Side -eq 'web') { $Pkg='com.xiufeigo.opencodeshell.web.debug'; $Act='com.xiufeigo.opencodeshell.web.WebHostActivity' }
else { $Pkg='com.xiufeigo.opencodeshell'; $Act='com.xiufeigo.opencodeshell.OcActivity' }

function A { & $Adb -s $Dev @args }
function Say { param($m) Write-Output ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }
function GetWinFrame {
  $raw = A shell "dumpsys window windows | grep -A30 '$Pkg/'"
  $t = $raw -join "`n"
  if ($t -match 'Frames:\s*parent=\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    return [pscustomobject]@{ L=[int]$Matches[1];T=[int]$Matches[2];R=[int]$Matches[3];B=[int]$Matches[4]
      W=([int]$Matches[3]-[int]$Matches[1]);H=([int]$Matches[4]-[int]$Matches[2]) } }
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

Say "=== [$Side] [A] steady-state frametime (ScriptAB) ==="
Say ("  load: " + ((A shell uptime) -join ' '))
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
foreach ($k in 'Total frames rendered','Janky frames','Janky frames (legacy)','50th percentile','90th percentile','95th percentile','99th percentile','Number Slow UI thread','Number Slow issue draw commands','Number Missed Vsync','50th gpu percentile','90th gpu percentile','Number High input latency','Number Slow bitmap uploads') {
  $v = Grab $k; $parsed[$k] = $v; Write-Output ("  {0,-34}= {1}" -f $k,$v) }
$parsed['interaction_wall_s'] = [math]::Round($sw.Elapsed.TotalSeconds,1)
$parsed | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $OutDir 'gfxinfo_parsed.json')
Write-Output "  GFX: $($parsed | ConvertTo-Json -Compress)"

Say "=== [$Side] [B] input-latency PROXY (swipe-driven) ==="
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
    i2f_p50=Pct $i2f 0.5; i2f_p90=Pct $i2f 0.9; i2f_p95=Pct $i2f 0.95; i2f_p99=Pct $i2f 0.99
    i2f_mean=if($i2f.Count){[math]::Round(($i2f|Measure-Object -Average).Average,2)}else{$null}
    i2f_max=if($i2f.Count){[math]::Round(($i2f|Measure-Object -Maximum).Maximum,2)}else{$null}
    i2s_p50=Pct $i2s 0.5 }
  Say "  frames parsed n=$($i2f.Count)"
  Write-Output "  INPUT_LATENCY: $($res | ConvertTo-Json -Compress)"
  $res | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $OutDir 'inputlatency.json')
} else { Say "  WARN: PROFILEDATA header not found" }

# 耗电可测性取证（x86_64 模拟器无 PMIC —— 给证据而不是空口说测不到）
$bat = [ordered]@{}
$bat['battery'] = ((A shell dumpsys battery | Select-String 'level|scale|voltage|powered|status|health') -join ' ; ')
$bat['power_supply_sysfs'] = ((A shell 'ls /sys/class/power_supply/ 2>/dev/null') -join ' ')
$bat['current_now_files'] = ((A shell 'ls /sys/class/power_supply/*/current_now 2>/dev/null') -join ' ')
$bat['discharge_lines'] = ((A shell 'dumpsys batterystats | grep -i discharge' | Select-Object -First 3) -join ' | ')
$bat | Out-File -Encoding utf8 (Join-Path $OutDir 'battery_probe.txt')
Write-Output "  BATTERY: $($bat | ConvertTo-Json -Compress)"
Say "=== DONE $Side ==="
