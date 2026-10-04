# v157 Task A: monotonicity sweep with CONFOUND CONTROL (per-level measured ime)
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\v157_lib.ps1"
$log = "$W\_v157_A.log"
if (Test-Path $log) { Remove-Item $log }
function W_ { param($m) $s = ($m | Out-String).TrimEnd(); Write-Output $s; Add-Content -Path $log -Value $s }

W_ "### A0 setup: fill composer + force IME"
SetDisp '1366x768' 4
$fc = FillComposer
W_ "  composer after fill: $fc"
W_ "  imeShown=$(ImeState)"

$levels = @(768,700,660,620,600,595,590,589,588,587,586,585,580,570,560)
W_ ""
W_ "### A1 per-level sweep: measured ime + composer form"
W_ "  H      imeTop imeBot imeH  room=H-imeH  FORM(byW/byRow) ETw  chipH sendY  note"
$rows = @()
foreach ($H in $levels) {
  SetDisp "1366x$H" 4
  $ok = EnsureIme "A@$H"
  if (-not $ok) { W_ "  1366x$H  IME NOT SHOWN -- skipping"; continue }
  $im = ImeRaw
  $x = Dump "A_$H"; $ns = Nodes $x
  $f = FormOf $ns
  $room = if ($null -ne $im.H) { $H - $im.H } else { 'NA' }
  $etw = if ($f.Et -match '(\d+)x(\d+)') { $Matches[1] } else { 'NA' }
  $chiph = if ($f.Chip -match '(\d+)x(\d+)') { $Matches[2] } else { 'NA' }
  $sendY = if ($f.Send -match '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') { "$($Matches[2])-$($Matches[4])" } else { 'NA' }
  W_ ("  {0,-6} {1,-6} {2,-6} {3,-5} {4,-11} {5,-7}/{6,-7} {7,-5} {8,-5} {9,-9}" -f `
      $H, $im.Top, $im.Bot, $im.H, $room, $f.ByWidth, $f.ByRow, $etw, $chiph, $sendY)
  $rows += [pscustomobject]@{ H=$H; ImeTop=$im.Top; ImeH=$im.H; Room=$room; Form=$f.Form; ByW=$f.ByWidth; ByRow=$f.ByRow
                            Et=$f.Et; Chip=$f.Chip; Send=$f.Send; Raw=$im.Raw }
  Shot "A_$H"
}
W_ ""
W_ "### A1b RAW ime lines captured (verbatim)"
foreach ($r in $rows) { W_ ("  H={0,-5} {1}" -f $r.H, $r.Raw) }

W_ ""
W_ "### A2 monotonicity verdict (form must be non-increasing as H shrinks)"
$prev = $null
$flips = @()
foreach ($r in $rows) {
  $rank = switch ($r.Form) { 'STACKED' {2} 'CONFLICT' {1} 'ONEROW' {0} 'NO_ET' {-1} default {1} }
  if ($null -ne $prev) {
    if ($rank -gt $prev.Rank) {
      $flips += "  !! REVERSE JUMP: H $prev.H ($prev.Form) -> H $($r.H) ($r.Form)  [form got BIGGER while window got SHORTER]"
    }
  }
  $prev = [pscustomobject]@{ H=$r.H; Form=$r.Form; Rank=$rank }
}
if ($flips.Count -eq 0) { W_ "  OK: form is monotonic non-increasing across all $($rows.Count) levels (no reverse jump)" }
else { $flips | ForEach-Object { W_ $_ } }

W_ ""
W_ "### A3 ime behavior: does the measured ime height track the window height?"
$imes = $rows | ForEach-Object { $_.ImeH } | Where-Object { $null -ne $_ }
$distinct = ($imes | Sort-Object -Unique) -join ','
W_ "  distinct measured imeH values across levels: $distinct"
$first=$rows[0]; $last=$rows[-1]
W_ ("  first: H={0} imeH={1} (top {2} bot {3})" -f $first.H,$first.ImeH,$first.ImeTop,($first.ImeTop+$first.ImeH))
W_ ("  last : H={0} imeH={1} (top {2} bot {2})" -f $last.H,$last.ImeH,$last.ImeTop)
if ($distinct -eq "$($first.ImeH)") { W_ "  => ime is FIXED across the whole sweep (confound EXCLUDED)" }
else { W_ "  => ime VARIES across the sweep (confound PRESENT) -- see raw lines above" }

W_ ""
W_ "### A4 588 neighbourhood (the claimed breakpoint)"
$rows | Where-Object { $_.H -ge 580 -and $_.H -le 600 } | ForEach-Object {
  W_ ("  H={0,-5} room={1,-5} FORM={2,-8} byW={3,-8} byRow={4,-8} Et={5} Chip={6} Send={7}" -f $_.H,$_.Room,$_.Form,$_.ByW,$_.ByRow,$_.Et,$_.Chip,$_.Send)
}
W_ "### A done"
