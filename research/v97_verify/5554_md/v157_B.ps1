# v157 Task B: baselines (no drift) + six criteria + round trips
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\v157_lib.ps1"
$log = "$W\_v157_B.log"
if (Test-Path $log) { Remove-Item $log }
function W_ { param($m) $s = ($m | Out-String).TrimEnd(); Write-Output $s; Add-Content -Path $log -Value $s }

# v145/v147 caliber geometry: pane host excludes full-window root (y1>0)
function Geo {
  param([string]$Tag)
  $null = AssertIme "geo ($Tag)"
  $im = ImeRaw
  $x = Dump $Tag; $ns = Nodes $x
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  if (-not $et) { return [pscustomobject]@{ Tag=$Tag; Err='NO EditText' } }
  $kids = $ns | Where-Object {
    $_.y1 -ge ($et.y1 - 2) -and $_.y2 -le ($et.y2 + 60) -and
    -not ($_.text -eq 'Open terminal' -or $_.desc -eq 'Open terminal') }
  if (-not $kids) { return [pscustomobject]@{ Tag=$Tag; Err='NO composer kids' } }
  $uy1=[int](($kids|Measure-Object y1 -Minimum).Minimum)
  $uy2=[int](($kids|Measure-Object y2 -Maximum).Maximum)
  $unionPx=$uy2-$uy1
  $hosts = $ns | Where-Object {
    $_.x1 -le 2 -and $_.x2 -ge 1364 -and $_.y1 -gt 0 -and $_.y1 -le $et.y1 -and
    $_.y2 -ge ($uy2 - 2) -and $_.h -lt 900 -and $_.h -ge 20 }
  $pane = $hosts | Sort-Object { $_.h } | Select-Object -First 1
  if (-not $pane) { return [pscustomobject]@{ Tag=$Tag; Err='NO pane host' } }
  $paneH=$pane.h
  $readable = if ($paneH -ge 150) { $paneH - 96 } else { $paneH - 48 }
  $f = FormOf $ns
  return [pscustomobject]@{
    Tag=$Tag; PaneH=$paneH; TimelineBox=($paneH-48); Readable=$readable
    UnionPx=$unionPx; UnionBottom=$uy2; PaneBottom=$pane.y2; C1=($uy2-$pane.y2)
    Form=$f.Form; ByW=$f.ByWidth; ByRow=$f.ByRow; ImeH=$im.H; ImeTop=$im.Top; Xml=$x
  }
}

W_ "### B1 baselines: 768 -> 140 / 92 / (i) -10 ; 600 -> 51 / (i) -12"
SetDisp '1366x768' 4
$null = EnsureIme 'B1@768'
$g = Geo 'B_768'
W_ ("  768: paneH={0} timelineBox={1} readable={2} (i)C1={3} imeH={4} form={5}" -f $g.PaneH,$g.TimelineBox,$g.Readable,$g.C1,$g.ImeH,$g.Form)
W_ ("       expect readable=140 timelineBox=92 (i)=-10  => 140:{1} 92:{2} -10:{3}" -f $g.Readable,$g.TimelineBox,$g.C1)
W_ ("       readable 140 -> {0}" -f $(if($g.Readable -eq 140){'MATCH'}else{"DRIFT($($g.Readable))"}))
W_ ("       timeline  92 -> {0}" -f $(if($g.TimelineBox -eq 92){'MATCH'}else{"DRIFT($($g.TimelineBox))"}))
W_ ("       (i)      -10 -> {0}" -f $(if($g.C1 -eq -10){'MATCH'}else{"DRIFT($($g.C1))"}))
Shot 'B_768'

SetDisp '1366x600' 4
$null = EnsureIme 'B1@600'
$g6 = Geo 'B_600'
W_ ("  600: paneH={0} timelineBox={1} readable={2} (i)C1={3} imeH={4} form={5}" -f $g6.PaneH,$g6.TimelineBox,$g6.Readable,$g6.C1,$g6.ImeH,$g6.Form)
W_ ("       expect readable=51 (i)=-12  =>  51:{0} -12:{1}" -f $g6.Readable,$g6.C1)
W_ ("       readable  51 -> {0}" -f $(if($g6.Readable -eq 51){'MATCH'}else{"DRIFT($($g6.Readable))"}))
W_ ("       (i)     -12 -> {0}" -f $(if($g6.C1 -eq -12){'MATCH'}else{"DRIFT($($g6.C1))"}))
Shot 'B_600'

W_ ""
W_ "### B2 six criteria (one-row form)"
function Crit {
  param([string]$tag)
  $null = AssertIme "crit ($tag)"
  $x = Dump $tag; $ns = Nodes $x
  $f = FormOf $ns
  $ot = $ns | Where-Object { $_.desc -eq 'Open terminal' -or $_.text -eq 'Open terminal' } | Select-Object -First 1
  $sd = $ns | Where-Object { $_.desc -eq 'Send' -or $_.text -eq 'Send' } | Select-Object -First 1
  if (-not $ot -or -not $sd) { W_ "  $tag : MISSING target node (chip=$($f.Chip) send=$($f.Send))"; return }
  $ix1=[Math]::Max($ot.x1,$sd.x1); $iy1=[Math]::Max($ot.y1,$sd.y1)
  $ix2=[Math]::Min($ot.x2,$sd.x2); $iy2=[Math]::Min($ot.y2,$sd.y2)
  $ow=[Math]::Max(0,$ix2-$ix1); $oh=[Math]::Max(0,$iy2-$iy1)
  $overlap=$ow*$oh
  if ($ow -gt 0 -and $oh -gt 0) { $covW=$ow; $covH=$oh } else { $covW=0; $covH=0 }
  $resW=$ot.w-$covW; $resH=$ot.h-$covH
  $narrow= if (($resW*$resH) -le 0) { 0 } else { [Math]::Min($resW,$resH) }
  W_ ("  === {0} === FORM={1} (byW={2} byRow={3})" -f $tag,$f.Form,$f.ByWidth,$f.ByRow)
  W_ ("   chip=[{0},{1}][{2},{3}] {4}x{5} clickable={6}" -f $ot.x1,$ot.y1,$ot.x2,$ot.y2,$ot.w,$ot.h,$ot.clickable)
  W_ ("   send=[{0},{1}][{2},{3}] {4}x{5} clickable={6} enabled={7}" -f $sd.x1,$sd.y1,$sd.x2,$sd.y2,$sd.w,$sd.h,$sd.clickable,$sd.enabled)
  W_ ("   C1 overlapArea={0} (int {1}x{2}) -> {3}" -f $overlap,$ow,$oh,$(if($overlap -eq 0){'PASS'}else{'FAIL'}))
  W_ ("   C2 coverage={0}x{1} residual={2}x{3} narrowest={4} -> {5}" -f $covW,$covH,$resW,$resH,$narrow,$(if($narrow -ge 16){'PASS'}else{'FAIL'}))
  W_ ("   C4 chip.h={0} send.h={1} (48/48) -> {2}" -f $ot.h,$sd.h,$(if($ot.h -eq 48 -and $sd.h -eq 48){'PASS'}else{'FAIL'}))
  W_ ("   C6 clickable {0}/{1} -> {2}" -f $ot.clickable,$sd.clickable,$(if($ot.clickable -eq 'true' -and $sd.clickable -eq 'true'){'PASS'}else{'FAIL'}))
  Shot "crit_$tag"
}
SetDisp '1366x600' 4; $null = EnsureIme 'B2@600'; Crit 'B2_600'
SetDisp '1366x768' 4; $null = EnsureIme 'B2@768'; Crit 'B2_768'

W_ ""
W_ "### B3 round trips 768<->600 x2, with logcat scan"
adbx logcat -c 2>&1 | Out-Null
foreach ($r in 1..2) {
  SetDisp '1366x600' 4; $null = EnsureIme "B3r$r@600"
  $a = Geo "B3_rt${r}_600"
  W_ ("  RT{0} 600 paneH={1} readable={2} C1={3} imeH={4} FORM={5}" -f $r,$a.PaneH,$a.Readable,$a.C1,$a.ImeH,$a.Form)
  SetDisp '1366x768' 4; $null = EnsureIme "B3r$r@768"
  $b = Geo "B3_rt${r}_768"
  W_ ("  RT{0} 768 paneH={1} readable={2} C1={3} imeH={4} FORM={5}" -f $r,$b.PaneH,$b.Readable,$b.C1,$b.ImeH,$b.Form)
}
Start-Sleep -Seconds 2
$lc = adbx logcat -d -t 3000 2>&1 | Out-String
$hits = @()
foreach ($p in @('FATAL EXCEPTION','FATAL','IllegalArgumentException','maxHeight')) {
  $m = [regex]::Matches($lc, [regex]::Escape($p))
  $hits += "$p = $($m.Count)"
}
W_ ("### B3 logcat (-t 3000): " + ($hits -join ' ; '))
W_ "### B done"
