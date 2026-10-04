# v155: control (does the room really shrink?) + Task B (baselines + six criteria + round trips)
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\v155_lib.ps1"
$log = "$W\_v155_B.log"
if (Test-Path $log) { Remove-Item $log }
function W_ { param($m) $m | Tee-Object -FilePath $log -Append }

# FORM via EditText WIDTH: narrow (~375-381) = one row (chip+send share the row)
#                          full width (~1310)  = stacked/full (send sits below)
function FormOf { param($ns)
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  $sd = $ns | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
  $ot = $ns | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
  if (-not $et) { return 'NO_ET' }
  $w = $et.w
  $f = if ($w -lt 800) { 'ONEROW' } else { 'STACKED' }
  return $f
}
# robust IME height: prefer dumpsys window frame, fall back to a visible-key heuristic
function ImeH {
  $d = adbx shell dumpsys window windows 2>&1 | Out-String
  $best = $null
  foreach ($m in [regex]::Matches($d, 'Window\{[^}]*\}[^\r\n]*inputmethod[^\r\n]*')) { $best = $m.Value }
  if (-not $best) { foreach ($m in [regex]::Matches($d, '(?s)Window\{[^}]*latin[^}]*\}.*?mFrame=(\d+)\.(\d+),(\d+)\.(\d+)\]\[(\d+)\.(\d+),(\d+)\.(\d+)\]')) { $best = $m.Value } }
  $h = $null
  if ($best) {
    $mm = [regex]::Match($best, 'mFrame=([\d.]+),([\d.]+)\]\[([\d.]+),([\d.]+)\]')
    if ($mm.Success) { $h = [double]$mm.Groups[4].Value - [double]$mm.Groups[2].Value }
  }
  if ($null -eq $h) {
    $im = adbx shell dumpsys input_method 2>&1 | Out-String
    $mm = [regex]::Match($im, 'mCurFrame=Rect\(([\d.]+), ([\d.]+)\) - \(([\d.]+), ([\d.]+)\)')
    if (-not $mm.Success) { $mm = [regex]::Match($im, 'mCurFrame=\[?([\d.]+),([\d.]+)\]\[([\d.]+),([\d.]+)\]?') }
    if ($mm.Success) { $h = [double]$mm.Groups[4].Value - [double]$mm.Groups[2].Value }
  }
  return $h
}
function EnsureIme {
  param([string]$tag)
  if (ImeState) { return $true }
  $x = Dump "${tag}_rec"; $ns = Nodes $x
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  if ($et) { adbx shell input tap $et.cx $et.cy | Out-Null }
  Start-Sleep -Seconds 2
  return (ImeState)
}
# Geo with a pane selector that EXCLUDES the full-window root (y1=0) and never
# falls back to the root: require y1>0 so the ComposeView root cannot win.
function Geo2 {
  param([string]$Tag)
  $ime = AssertIme "geo2 ($Tag)" | Select-Object -Last 1
  $x = Dump $Tag; $ns = Nodes $x
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  if (-not $et) { return [pscustomobject]@{ Tag=$Tag; Err='NO EditText' } }
  $kids = $ns | Where-Object {
    $_.y1 -ge ($et.y1 - 2) -and $_.y2 -le ($et.y2 + 60) -and
    -not ($_.text -eq 'Open terminal' -or $_.desc -eq 'Open terminal') }
  $uy1=[int](($kids|Measure-Object y1 -Minimum).Minimum)
  $uy2=[int](($kids|Measure-Object y2 -Maximum).Maximum)
  $hosts = $ns | Where-Object {
    $_.x1 -le 2 -and $_.x2 -ge 1364 -and $_.y1 -gt 0 -and $_.y1 -le $et.y1 -and
    $_.y2 -ge ($uy2 - 2) -and $_.h -lt 900 -and $_.h -ge 20 }
  $pane = $hosts | Sort-Object { $_.h } | Select-Object -First 1
  if (-not $pane) { return [pscustomobject]@{ Tag=$Tag; Err='NO pane host (excl root)' } }
  $paneH=$pane.h
  $readable = if ($paneH -ge 150) { $paneH - 96 } else { $paneH - 48 }
  return [pscustomobject]@{
    Tag=$Tag; PaneH=$paneH; TimelineBox=($paneH-48); Readable=$readable
    UnionPx=($uy2-$uy1); UnionBottom=$uy2; PaneBottom=$pane.y2; C1=($uy2-$pane.y2)
    Form=(FormOf $ns); Ime=$ime; Pid=(AppPid)
    Et=$(if($et){"[$($et.x1),$($et.y1)][$($et.x2),$($et.y2)] $($et.w)x$($et.h)"}else{'NA'})
  }
}

W_ "### B0 CONTROL: room = windowH - imeH per level (form via EditText width)"
W_ "  level    winH  imeH    room     FORM"
$prevRoom = $null
foreach ($H in @(768,700,660,620,600,580,560)) {
  SetDisp "1366x$H" 4
  $ok = EnsureIme "B0@$H"
  $ih = ImeH
  $x = Dump "B0_$H"; $ns = Nodes $x
  $form = FormOf $ns
  $room = if ($null -ne $ih) { $H - [int]$ih } else { 'NA' }
  W_ ("  1366x{0,-4} {1,-6} {2,-8} {3,-9} {4}" -f $H, $H, $(if($null -ne $ih){[int]$ih}else{'NA'}), $room, $form)
  if ($null -ne $ih) { $prevRoom = $H - [int]$ih }
}

W_ ""
W_ "### B1 reproducibility of the 600->580->560 edge, 3 passes (form via ET width)"
foreach ($p in 1..3) {
  foreach ($H in @(600,580,560)) {
    SetDisp "1366x$H" 4
    $ok = EnsureIme "B1p$p@$H"
    $x = Dump "B1_p${p}_$H"; $ns = Nodes $x
    $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
    $sd = $ns | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
    $ot = $ns | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
    W_ ("  pass{0} {1,-4} FORM={2,-8} ET.w={3,-5} chipH={4,-4} send=[{5}]" -f $p, $H, (FormOf $ns), `
        $(if($et){$et.w}else{'NA'}), $(if($ot){$ot.h}else{'NA'}), $(if($sd){"$($sd.x1),$($sd.y1)-$($sd.x2),$($sd.y2)"}else{'NA'}))
  }
}

W_ ""
W_ "### B2 baselines (768: 140/92/-10 ; 600: 51/-12)"
SetDisp '1366x768' 4; EnsureIme 'B2@768' | Out-Null
$g = Geo2 'B2_768'
W_ ("  B2_768 paneH={0} timelineBox={1} readable={2} 1i={3} FORM={4} ET={5} ime={6}" -f $g.PaneH,$g.TimelineBox,$g.Readable,$g.C1,$g.Form,$g.Et,$g.Ime)
SetDisp '1366x600' 4; EnsureIme 'B2@600' | Out-Null
$g6 = Geo2 'B2_600'
W_ ("  B2_600 paneH={0} timelineBox={1} readable={2} 1i={3} FORM={4} ET={5} ime={6}" -f $g6.PaneH,$g6.TimelineBox,$g6.Readable,$g6.C1,$g6.Form,$g6.Et,$g6.Ime)

W_ ""
W_ "### B3 six criteria (v147 caliber: coverage is ZERO unless the intersection is non-empty in BOTH axes)"
function Crit {
  param([string]$tag)
  $ime = AssertIme "crit ($tag)" | Select-Object -Last 1
  $x = Dump $tag; $ns = Nodes $x
  $ot = $ns | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
  $sd = $ns | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
  if (-not $ot -or -not $sd) { W_ "  $tag : MISSING target node"; return }
  $ix1=[Math]::Max($ot.x1,$sd.x1); $iy1=[Math]::Max($ot.y1,$sd.y1)
  $ix2=[Math]::Min($ot.x2,$sd.x2); $iy2=[Math]::Min($ot.y2,$sd.y2)
  $ow=[Math]::Max(0,$ix2-$ix1); $oh=[Math]::Max(0,$iy2-$iy1)
  $overlap=$ow*$oh
  if ($ow -gt 0 -and $oh -gt 0) { $covW=$ow; $covH=$oh } else { $covW=0; $covH=0 }
  $resW=$ot.w-$covW; $resH=$ot.h-$covH
  $narrow= if (($resW*$resH) -le 0) { 0 } else { [Math]::Min($resW,$resH) }
  W_ ("  === {0} === ime={1} FORM={2}" -f $tag, $ime, (FormOf $ns))
  W_ ("   chip = [{0},{1}][{2},{3}] {4}x{5} clickable={6}" -f $ot.x1,$ot.y1,$ot.x2,$ot.y2,$ot.w,$ot.h,$ot.clickable)
  W_ ("   send = [{0},{1}][{2},{3}] {4}x{5} clickable={6} enabled={7}" -f $sd.x1,$sd.y1,$sd.x2,$sd.y2,$sd.w,$sd.h,$sd.clickable,$sd.enabled)
  W_ ("   C1 overlapArea={0} (int {1}x{2}) -> {3}" -f $overlap,$ow,$oh,$(if($overlap -eq 0){'PASS'}else{'FAIL'}))
  W_ ("   C2 coverage={0}x{1} residual={2}x{3} narrowest={4} -> {5}" -f $covW,$covH,$resW,$resH,$narrow,$(if($narrow -ge 16){'PASS'}else{'FAIL'}))
  W_ ("   C4 chip.h={0} send.h={1} -> {2}" -f $ot.h,$sd.h,$(if($ot.h -eq 48 -and $sd.h -eq 48){'PASS'}else{'FAIL'}))
  W_ ("   C6 clickable {0}/{1} -> {2}" -f $ot.clickable,$sd.clickable,$(if($ot.clickable -eq 'true' -and $sd.clickable -eq 'true'){'PASS'}else{'FAIL'}))
  adbx shell "screencap -p /sdcard/__e2e.png" | Out-Null
  adbx pull /sdcard/__e2e.png "$script:ART\crit_$tag.png" 2>&1 | Out-Null
}
SetDisp '1366x600' 4; EnsureIme 'B3@600' | Out-Null
Crit 'B3_600'
SetDisp '1366x768' 4; EnsureIme 'B3@768' | Out-Null
Crit 'B3_768'

W_ ""
W_ "### B4 round trips 768->600->768 x2"
adbx logcat -c 2>&1 | Out-Null
foreach ($r in 1..2) {
  SetDisp '1366x600' 4; EnsureIme "B4r$r@600" | Out-Null
  $a = Geo2 "B4_rt${r}_600"
  W_ ("  RT{0} 600 paneH={1} readable={2} 1i={3} FORM={4}" -f $r,$a.PaneH,$a.Readable,$a.C1,$a.Form)
  SetDisp '1366x768' 4; EnsureIme "B4r$r@768" | Out-Null
  $b = Geo2 "B4_rt${r}_768"
  W_ ("  RT{0} 768 paneH={1} readable={2} 1i={3} FORM={4}" -f $r,$b.PaneH,$b.Readable,$b.C1,$b.Form)
}
W_ ("### B done  appPid=" + (AppPid))
