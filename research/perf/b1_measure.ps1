# b1_measure.ps1 — B1 延迟地板分离：同一把尺子测三层
#
# 【尺子定义（与 P5 / chunk.ps1 逐字相同，保证与 32.00/58.76、28.14/32.84 可比）】
#   1) dumpsys gfxinfo <pkg> reset
#   2) am force-stop <pkg> ; am start <activity> ; 固定等 22 s
#   3) 20 次窗口相对滑动：10 上滑(0.80->0.20) + 10 下滑(0.20->0.80)，swipe 250ms / 间隔 250ms
#   4) dumpsys gfxinfo <pkg> framestats
#   5) 解析 HandleInputStart -> FrameCompleted（i2f）与 -> SwapBuffersCompleted（i2s），单位 ms
#   6) 分位 p50/p90/p95/p99（Nearest-rank，与 P5 同一 Pct 实现）
#   ⚠️ 口径说明：这是 **Android 平台帧时间线里的 输入处理开始→该帧完成** 代理量，
#      **不是** 硬件到像素的端到端触摸延迟（端到端需要外部高速相机/触控示波器，模拟器上测不到）。
#      三层用**完全相同**的口径与参数，所以层间差值可比。
#
# 【层】
#   L1 = 官方 UI（web 壳正常启动，不注入）
#   L0 = 同一 WebView 宿主 + CDP 注入的极简页（只有 textarea/input，无框架）
#   L2 = 原生壳 com.xiufeigo.opencodeshell
param(
  [Parameter(Mandatory)][ValidateSet('L1','L0','L2')][string]$Layer,
  [string]$Dev = 'emulator-5854'
)
$ErrorActionPreference = 'Continue'
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$P   = "$PSScriptRoot"
$OutDir = "$P\out_b1\$Layer"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$NScroll = 10
if ($Layer -eq 'L2') { $Pkg='com.xiufeigo.opencodeshell'; $Act='com.xiufeigo.opencodeshell.OcActivity' }
else { $Pkg='com.xiufeigo.opencodeshell.web.debug'; $Act='com.xiufeigo.opencodeshell.web.WebHostActivity' }
function A { & $Adb -s $Dev @args }
# 注意：用 Write-Host（不进 success stream）。P5 的教训：Say 用 Write-Output 会让
# "if (Func ...)" 恒为真，把一次失败误判成成功。
function Say { param($m) Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }
function GetWinFrame {
  $raw = A shell "dumpsys window windows | grep -A30 '$Pkg/'"
  $t = $raw -join "`n"
  if ($t -match 'Frames:\s*parent=\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    return [pscustomobject]@{ L=[int]$Matches[1];T=[int]$Matches[2];R=[int]$Matches[3];B=[int]$Matches[4]
      W=([int]$Matches[3]-[int]$Matches[1]);H=([int]$Matches[4]-[int]$Matches[2]) } }
  return [pscustomobject]@{ L=0;T=0;R=1080;B=2400;W=1080;H=2400 }
}
function WPt { param($f,[double]$fx,[double]$fy) [int]($f.L+$f.W*$fx),[int]($f.T+$f.H*$fy) }
function RSwipe { param($f,[double]$x,[double]$y1,[double]$y2,[int]$ms=250,[int]$ms2=250)
  $a=WPt $f $x $y1; $b=WPt $f $x $y2
  A shell input swipe $a[0] $a[1] $b[0] $b[1] $ms | Out-Null; Start-Sleep -Milliseconds $ms2 }
function Pct { param($a,[double]$p) if (-not $a -or $a.Count -eq 0) { return $null }
  $s2=$a|Sort-Object; $i=[math]::Min($s2.Count-1,[math]::Max(0,[math]::Ceiling($p*$s2.Count)-1)); [math]::Round($s2[$i],2) }

Say "=== [$Layer] 输入延迟（尺子 = P5 逐字口径）pkg=$Pkg ==="
if (-not (A get-state 2>$null)) { Say "DEVICE GONE - abort $Layer"; exit 3 }

# --- 第 1 步：reset（与 P5 同序） ---
A shell dumpsys gfxinfo $Pkg reset | Out-Null
# --- 第 2 步：冷启 + 固定 22s ---
A shell am force-stop $Pkg; Start-Sleep -Seconds 2
A shell am start -n "$Pkg/$Act" | Out-Null; Start-Sleep -Seconds 22

# --- L0 专属：CDP 就地注入极简页（官方 UI 侧无此步） ---
if ($Layer -eq 'L0') {
  Say "  [L0] CDP 注入极简页（document.open/write/close，就地替换；不改 webapp/src）"
  $inj = (pwsh -NoProfile -File "$P\..\env\cdp_run_e6.ps1" -Serial $Dev -JsFile "$P\b1_l0_inject.js" 2>&1) -join "`n"
  $inj | Out-File -Encoding utf8 "$OutDir\inject_out.txt"
  Say ("  inject: " + ($inj -split "`r?`n" | Select-Object -Last 1))
  Start-Sleep -Seconds 8   # 稳定窗口（官方 UI 侧无此步；已在报告中声明）
  $v = (pwsh -NoProfile -File "$P\..\env\cdp_run_e6.ps1" -Serial $Dev -JsFile "$P\b1_verify.js" 2>&1) -join "`n"
  $v | Out-File -Encoding utf8 "$OutDir\l0_identity_before.json"
  Say ("  identity BEFORE: " + ($v -split "`r?`n" | Select-Object -Last 1))
  if ($v -notmatch '"title":"L0-MINIMAL"') { Say "!! L0 注入未生效（title 不是 L0-MINIMAL）=> 本层作废"; exit 7 }
}

# --- 第 3 步：20 次窗口相对滑动（与 P5 逐字相同） ---
$f = GetWinFrame
Say ("  window frame = L{0} T{1} W{2} H{3}" -f $f.L,$f.T,$f.W,$f.H)
$sw=[Diagnostics.Stopwatch]::StartNew()
for ($i=0; $i -lt $NScroll; $i++) { RSwipe $f 0.5 0.80 0.20 250 250 }
for ($i=0; $i -lt $NScroll; $i++) { RSwipe $f 0.5 0.20 0.80 250 250 }
$sw.Stop()
Say ("  interaction wall = {0:N1} s" -f $sw.Elapsed.TotalSeconds)
Start-Sleep -Seconds 2

# --- L0 专属：再验一次身份（证明测量期间没被弹回官方 UI） ---
if ($Layer -eq 'L0') {
  $v2 = (pwsh -NoProfile -File "$P\..\env\cdp_run_e6.ps1" -Serial $Dev -JsFile "$P\b1_verify.js" 2>&1) -join "`n"
  $v2 | Out-File -Encoding utf8 "$OutDir\l0_identity_after.json"
  Say ("  identity AFTER : " + ($v2 -split "`r?`n" | Select-Object -Last 1))
}

# --- 第 4/5/6 步：取 framestats 并解析（与 P5 逐字相同） ---
A shell am start -n "$Pkg/$Act" | Out-Null; Start-Sleep -Seconds 2
$fs = A shell dumpsys gfxinfo $Pkg framestats
if (-not $fs) { Say "framestats EMPTY (device died?)"; exit 4 }
$fs | Out-File -Encoding utf8 "$OutDir\framestats_raw.txt"
$ft = $fs -join "`n"
$lines = $ft -split "`r?`n"; $hdr=$null; $rows=@()
foreach ($l in $lines) { $c=$l.Trim()
  if ($c -like 'Flags,FrameTimelineVsyncId*') { $hdr=$c -split ','; continue }
  if ($hdr -and $c -match '^\d+,' -and $c.Split(',').Count -eq $hdr.Count) { $rows += ,$c.Split(',') } }
if (-not $hdr) { Say "no PROFILEDATA header"; exit 5 }
$iH=[array]::IndexOf($hdr,'HandleInputStart'); $iF=[array]::IndexOf($hdr,'FrameCompleted'); $iS=[array]::IndexOf($hdr,'SwapBuffersCompleted')
$i2f=@(); $i2s=@()
foreach ($r in $rows) { $h=[double]$r[$iH]; $fc=[double]$r[$iF]; $s=[double]$r[$iS]
  if ($h -gt 0 -and $fc -gt 0) { $i2f += (($fc-$h)/1e6); if ($s -gt 0) { $i2s += (($s-$h)/1e6) } } }
$res=[ordered]@{ layer=$Layer; serial=$Dev; pkg=$Pkg
  method='gfxinfo framestats HandleInputStart->FrameCompleted; 10 up + 10 down window-relative swipe, 250ms/250ms; cold start + 22s settle'
  metric='platform input-handle-start -> frame-completed (NOT end-to-end hardware-to-photon)'
  interaction_wall_s=[math]::Round($sw.Elapsed.TotalSeconds,1)
  n=$i2f.Count
  i2f_p50=Pct $i2f 0.5; i2f_p90=Pct $i2f 0.9; i2f_p95=Pct $i2f 0.95; i2f_p99=Pct $i2f 0.99
  i2f_mean=if($i2f.Count){[math]::Round(($i2f|Measure-Object -Average).Average,2)}else{$null}
  i2f_max=if($i2f.Count){[math]::Round(($i2f|Measure-Object -Maximum).Maximum,2)}else{$null}
  i2s_p50=Pct $i2s 0.5 }
# 原始读数落盘（可复核）
[ordered]@{ layer=$Layer; n=$i2f.Count; i2f_ms=$i2f; i2s_ms=$i2s } | ConvertTo-Json -Depth 5 |
  Out-File -Encoding utf8 "$OutDir\raw_samples.json"
$res | ConvertTo-Json | Out-File -Encoding utf8 "$OutDir\inputlatency.json"
Say "  n=$($res.n)"
Say "  RESULT_INPUT: $($res | ConvertTo-Json -Compress)"
Say "MEASURE OK $Layer"
