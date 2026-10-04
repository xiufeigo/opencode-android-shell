# p5_ft_resilient.ps1 — 帧时的"抗杀"采集
#
# 背景：web/ft 在 P4 失败 4 次、P5 又失败 2 次，**每次都是 ScriptAB 跑完（wall 31~45s）之后
# 最后那次 `dumpsys gfxinfo` 时设备已被别的 lane 杀掉**（P4 §2.2 已定性为环境问题）。
#
# 办法：**交互进行中就并行轮询 gfxinfo 并落盘**，最后一次成功的 dump 至少留下一个
# "已经渲染了 N 帧"的真实快照；脚本结束再补一次终局 dump。
# 口径与 chunk.ps1 逐字相同（ScriptAB 全文、NScroll=10 / NTap=6、窗口相对手势、
# reset 在交互前），**不削减交互次数、不跳冷启、不改参数** —— 只加冗余。
param(
  [Parameter(Mandatory)][ValidateSet('web','native')][string]$Side,
  [string]$Dev = 'emulator-5842',
  [int]$PollSec = 3
)
$ErrorActionPreference = 'Continue'
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Out = "$PSScriptRoot\out_p5\$Side"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$NScroll = 10; $NTap = 6
if ($Side -eq 'web') { $Pkg='com.xiufeigo.opencodeshell.web.debug'; $Act='com.xiufeigo.opencodeshell.web.WebHostActivity' }
else { $Pkg='com.xiufeigo.opencodeshell'; $Act='com.xiufeigo.opencodeshell.OcActivity' }
function A { & $Adb -s $Dev @args }
function Log { param($m) $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m; Write-Host $s; $s | Out-File -Append -Encoding utf8 "$Out/_ft_resilient.log" }
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
    $f = GetWinFrame }
  for ($i=0; $i -lt [int]($NScroll/2); $i++) { RSwipe $f 0.85 0.5 0.15 }
  for ($i=0; $i -lt [int]($NScroll/2); $i++) { RSwipe $f 0.15 0.5 0.85 }
  RTap $f 0.5 0.5 600
}

if (-not ((A get-state 2>$null) | Out-String).Trim()) { Log "DEVICE GONE"; exit 3 }
Log "=== RESILIENT FT [$Side] (ScriptAB 逐字未改 + 并行 gfxinfo 轮询) ==="
A shell dumpsys gfxinfo $Pkg reset | Out-Null
Log "  gfxinfo reset done; starting parallel poller (every ${PollSec}s)"

# 并行轮询：**独立进程**（P5 实测 Start-Job 版本一个快照都没落盘）
$pollProc = Start-Process -FilePath "pwsh" `
  -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File',"$PSScriptRoot\p5_snap_poller.ps1",'-Dev',$Dev,'-Pkg',$Pkg,'-Out',$Out,'-Every',"$PollSec",'-MaxSec','200' `
  -WindowStyle Hidden -PassThru
Log "  poller pid=$($pollProc.Id) started (independent process, every ${PollSec}s)"

$sw=[Diagnostics.Stopwatch]::StartNew(); ScriptAB; $sw.Stop()
Log ("  interaction wall = {0:N1} s" -f $sw.Elapsed.TotalSeconds)

# 终局 dump（去掉原 chunk 的 2s+2s 睡眠窗口）
$gfx = A shell dumpsys gfxinfo $Pkg
if ($pollProc -and -not $pollProc.HasExited) { try { $pollProc.Kill() } catch {} }
Start-Sleep -Seconds 1
if (-not $gfx) {
  Log "  FINAL gfxinfo EMPTY (device died) -> 退回并行轮询的快照"
  # ⚠️ 不能在 Sort-Object 的 scriptblock 里用 $Matches（不会可靠继承上一次的 -match 结果）⇒ 显式投影成属性
  $snaps = Get-ChildItem "$Out\_snap_*" -ErrorAction SilentlyContinue |
    ForEach-Object { $fr = 0; if ($_.Name -match 'frames_(\d+)') { $fr = [int]$Matches[1] }
                     [pscustomobject]@{ File = $_; Frames = $fr } } |
    Sort-Object -Property Frames
  Log "  轮询共落 $($snaps.Count) 个快照（frames: $((($snaps | ForEach-Object { $_.Frames }) -join ','))）"
  $last = $snaps | Where-Object { $_.Frames -gt 0 } | Select-Object -Last 1
  if ($last) {
    $snapFrames = $last.Frames
    Log "  best snapshot = $($last.File.Name) (frames=$snapFrames)"
    $gfx = Get-Content $last.File.FullName
  } else {
    Log "  NO usable snapshot (frames>0) -> 帧时测不到"
    exit 4
  }
  $parsed['complete_interaction'] = $false
  Log "  !! 注意：这是**交互途中**的快照（frames=$snapFrames），**不是**完整 ScriptAB 的终局值；仅作趋势，不与旧轮直接比"
} else {
  Log "  FINAL gfxinfo OK (完整交互)"
  $parsed['complete_interaction'] = $true
}
$gfx | Out-File -Encoding utf8 "$Out\gfxinfo_full.txt"
$gt = $gfx -join "`n"
function Grab { param($k) if ($gt -match ("(?m)^\s*"+[regex]::Escape($k)+":\s*(.+)$")) { $Matches[1].Trim() } else { 'N/A' } }
$parsed=[ordered]@{ side=$Side }
foreach ($k in 'Total frames rendered','Janky frames','Janky frames (legacy)','50th percentile','90th percentile','95th percentile','99th percentile','Number Slow UI thread','Number Slow issue draw commands','Number Missed Vsync','50th gpu percentile','90th gpu percentile','Number High input latency','Number Slow bitmap uploads') {
  $v=Grab $k; $parsed[$k]=$v; Write-Host ("  {0,-34}= {1}" -f $k,$v) }
$parsed['interaction_wall_s']=[math]::Round($sw.Elapsed.TotalSeconds,1)
$parsed['final_dump_complete']= ($gt -match 'Total frames rendered:\s*[1-9]')
$parsed['method']='ScriptAB verbatim + parallel gfxinfo poller (anti-external-kill)'
$parsed | ConvertTo-Json | Out-File -Encoding utf8 "$Out\gfxinfo_parsed.json"
Log "  RESULT_FT: $($parsed | ConvertTo-Json -Compress)"
Log "FT DONE $Side"
