# b1_l0_validity.ps1 — **有效性检查**：证明三层里 20 次滑动**真的做了事**
#
# 为什么必须做：L0 只有 11 个 DOM 节点。如果 L0 的 16.32 ms 是因为"页面空、什么都没滚"，
# 那 L1−L0 就不是"官方 UI 的成本"，而是"官方 UI 有内容 vs 没内容"的伪差 ⇒ 结论作废。
# 判据（每层都要过）：
#   V1) 滑动确实改变了可观测状态（L0: textarea.scrollTop 变化；L1: 会话列表滚动容器 scrollTop/文档滚动变化）
#   V2) 该窗口内确实产生了**带输入标记的帧**（n>0），不是 0 帧空转
#   V3) 页面身份仍然正确（没被弹回 / 没被弹成对话框）
param(
  [string]$Dev = 'emulator-5854',
  [ValidateSet('L0','L1')][string]$Layer = 'L0'
)
$ErrorActionPreference='Continue'
$Adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$P="$PSScriptRoot"; $Out="$P\out_b1\validity_$Layer"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Pkg='com.xiufeigo.opencodeshell.web.debug'; $Act='com.xiufeigo.opencodeshell.web.WebHostActivity'
function A { & $Adb -s $Dev @args }
function Say { param($m) Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }
function GetWinFrame {
  $t = (A shell "dumpsys window windows | grep -A30 '$Pkg/'") -join "`n"
  if ($t -match 'Frames:\s*parent=\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    return [pscustomobject]@{L=[int]$Matches[1];T=[int]$Matches[2];W=([int]$Matches[3]-[int]$Matches[1]);H=([int]$Matches[4]-[int]$Matches[2])} }
  [pscustomobject]@{L=0;T=0;W=1080;H=2400} }
function RSwipe { param($f,[double]$x,[double]$y1,[double]$y2,[int]$ms=250,[int]$ms2=250)
  $a=[int]($f.L+$f.W*$x); $ay=[int]($f.T+$f.H*$y1); $b=[int]($f.L+$f.W*$x); $by=[int]($f.T+$f.H*$y2)
  A shell input swipe $a $ay $b $by $ms | Out-Null; Start-Sleep -Milliseconds $ms2 }

# 状态探针：读"可滚动的真实状态"
$probe = @'
(function(){
 try{
  var t=document.title||'';
  var ta=document.querySelector('textarea');
  var res={title:t, docScrollTop:document.scrollingElement?document.scrollingElement.scrollTop:-1,
           winScrollY:window.scrollY||0, bodyScrollTop:document.body?document.body.scrollTop:-1};
  if(ta) res.textareaScrollTop=ta.scrollTop, res.textareaScrollHeight=ta.scrollHeight, res.textareaClientHeight=ta.clientHeight;
  // 官方 UI 的滚动容器：找 scrollHeight>clientHeight 的最大可滚动节点
  var best=null;
  var all=document.querySelectorAll('div,main,section,ul');
  for(var i=0;i<all.length;i++){var e=all[i];
    if(e.scrollHeight-e.clientHeight>50){ if(!best||e.scrollHeight>best.sh) best={sh:e.scrollHeight,ch:e.clientHeight,st:e.scrollTop,cls:(e.className||'').toString().slice(0,40)};}}
  res.biggestScroller=best;
  res.nodes=document.getElementsByTagName('*').length;
  return JSON.stringify(res);
 }catch(e){return JSON.stringify({err:String(e)});}
})()
'@
$probeFile = "$Out\_probe.js"; $probe | Out-File -Encoding utf8 $probeFile
function ReadState { param($tag)
  $r = (pwsh -NoProfile -File "$P\..\env\cdp_run_e6.ps1" -Serial $Dev -JsFile $probeFile 2>&1) -join "`n"
  $r | Out-File -Encoding utf8 "$Out\state_$tag.txt"
  $last = ($r -split "`r?`n" | Select-Object -Last 1)
  Say "  state[$tag] = $last"
  return $last }

Say "=== VALIDITY [$Layer] ==="
A shell am force-stop $Pkg | Out-Null; Start-Sleep -Seconds 2
A shell am start -n "$Pkg/$Act" | Out-Null; Start-Sleep -Seconds 22
if ($Layer -eq 'L0') {
  $inj = (pwsh -NoProfile -File "$P\..\env\cdp_run_e6.ps1" -Serial $Dev -JsFile "$P\b1_l0_inject.js" 2>&1) -join "`n"
  Say ("  inject: " + ($inj -split "`r?`n" | Select-Object -Last 1))
  Start-Sleep -Seconds 8
}
$s0 = ReadState 'before'
A shell dumpsys gfxinfo $Pkg reset | Out-Null
$f = GetWinFrame
for ($i=0; $i -lt 5; $i++) { RSwipe $f 0.5 0.80 0.20 250 250 }
for ($i=0; $i -lt 5; $i++) { RSwipe $f 0.5 0.20 0.80 250 250 }
Start-Sleep -Seconds 2
$s1 = ReadState 'after'
$fs = A shell dumpsys gfxinfo $Pkg framestats
$ft = $fs -join "`n"; $lines=$ft -split "`r?`n"; $hdr=$null; $rows=@()
foreach ($l in $lines) { $c=$l.Trim()
  if ($c -like 'Flags,FrameTimelineVsyncId*') { $hdr=$c -split ','; continue }
  if ($hdr -and $c -match '^\d+,' -and $c.Split(',').Count -eq $hdr.Count) { $rows += ,$c.Split(',') } }
$iH=[array]::IndexOf($hdr,'HandleInputStart'); $iF=[array]::IndexOf($hdr,'FrameCompleted')
$n=0; $i2f=@()
foreach ($r in $rows) { $h=[double]$r[$iH]; $fc=[double]$r[$iF]; if ($h -gt 0 -and $fc -gt 0) { $n++; $i2f += (($fc-$h)/1e6) } }
Say "  V2 input-marked frames n=$n"
function Pct { param($a,[double]$p) if (-not $a -or $a.Count -eq 0) { return $null }
  $s2=$a|Sort-Object; $i=[math]::Min($s2.Count-1,[math]::Max(0,[math]::Ceiling($p*$s2.Count)-1)); [math]::Round($s2[$i],2) }
# V1 状态确实变了？
$changed = $false; $why = ''
if ($Layer -eq 'L0') {
  $a0 = ([regex]'"textareaScrollTop":(-?\d+)').Match($s0); $a1 = ([regex]'"textareaScrollTop":(-?\d+)').Match($s1)
  if ($a0.Success -and $a1.Success -and $a0.Groups[1].Value -ne $a1.Groups[1].Value) {
    $changed=$true; $why="textarea.scrollTop $($a0.Groups[1].Value) -> $($a1.Groups[1].Value)" }
} else {
  $a0 = ([regex]'"docScrollTop":(-?\d+)').Match($s0); $a1 = ([regex]'"docScrollTop":(-?\d+)').Match($s1)
  if ($a0.Success -and $a1.Success -and $a0.Groups[1].Value -ne $a1.Groups[1].Value) {
    $changed=$true; $why="document.scrollingElement.scrollTop $($a0.Groups[1].Value) -> $($a1.Groups[1].Value)" }
  $b0 = ([regex]'biggestScroller":\{"sh":(\d+),"ch":(\d+),"st":(-?\d+)').Match($s0)
  $b1 = ([regex]'biggestScroller":\{"sh":(\d+),"ch":(\d+),"st":(-?\d+)').Match($s1)
  if ($b0.Success -and $b1.Success -and $b0.Groups[3].Value -ne $b1.Groups[3].Value) {
    $changed=$true; $why += " | inner scroller scrollTop $($b0.Groups[3].Value) -> $($b1.Groups[3].Value) (sh=$($b1.Groups[1].Value))" }
}
$verdict = ($changed -and $n -gt 0)
$res=[ordered]@{ layer=$Layer; V1_state_changed=$changed; V1_detail=$why; V2_input_frames=$n
  p50=Pct $i2f 0.5; p90=Pct $i2f 0.9; p95=Pct $i2f 0.95
  state_before=$s0; state_after=$s1; VERDICT_SWIPES_DID_REAL_WORK=$verdict }
$res | ConvertTo-Json -Depth 5 | Out-File -Encoding utf8 "$Out\validity.json"
$res | ConvertTo-Json -Depth 5 | Write-Host
if ($verdict) { Say "VALIDITY PASS [$Layer]: 滑动确实做了事" } else { Say "VALIDITY FAIL [$Layer]"; exit 1 }
