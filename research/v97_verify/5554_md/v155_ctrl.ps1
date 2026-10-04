# v155 control: is the "room" actually shrinking? Measure the REAL IME height per level.
# If Gboard shrinks with the window, room = window - ime may be NON-monotonic and the
# form change would be driven by a constant room, not by the criterion under test.
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\v155_lib.ps1"
$log = "$W\_v155_ctrl.log"
if (Test-Path $log) { Remove-Item $log }
function W_ { param($m) $m | Tee-Object -FilePath $log -Append }

function ImeGeom {
  $d = adbx shell dumpsys input_method 2>&1 | Out-String
  $shown = $false
  if ($d -match 'mInputShown=(true|false)') { $shown = ($Matches[1] -eq 'true') }
  $top = $null; $bot = $null
  if ($d -match 'mCurFrame=\[?[^\r\n]*?([\d.]+),([\d.]+)\]\[([\d.]+),([\d.]+)\]?') {
    $top = [double]$Matches[2]; $bot = [double]$Matches[4]
  }
  return [pscustomobject]@{ Shown=$shown; Top=$top; Bottom=$bot; H=($(if($null -ne $top){$bot-$top}else{$null})) }
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

$levels = @('1366x768','1366x700','1366x660','1366x620','1366x600','1366x580','1366x560')
W_ "### CTRL-1 IME geometry per level (room = window - ime)"
W_ "  level   win_H  imeTop  imeBot  imeH   room=winH-imeH   FORM"
$prevRoom = $null; $prevForm = $null
foreach ($sz in $levels) {
  $H = [int](($sz -split 'x')[1])
  SetDisp $sz 4
  $ok = EnsureIme "C@$sz"
  $g = ImeGeom
  $x = Dump "C_$H"; $ns = Nodes $x
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  $sd = $ns | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
  $form = 'UNKNOWN'
  if ($et -and $sd) { if ($sd.y1 -le $et.y2 + 2) { $form = 'ONEROW' } else { $form = 'STACKED' } }
  $room = if ($null -ne $g.H) { $H - $g.H } else { $null }
  W_ ("  {0,-8} {1,-6} {2,-7} {3,-7} {4,-6} {5,-16} {6}" -f $sz, $H, $g.Top, $g.Bottom, $g.H, $room, $form)
  if ($null -ne $prevRoom -and $null -ne $room) {
    if ($room -gt $prevRoom) { W_ ("  !! ROOM GREW: $prevRoom -> $room while the window got SHORTER") }
  }
  if ($null -ne $room) { $prevRoom = $room }
  $prevForm = $form
}

W_ ""
W_ "### CTRL-2 reproducibility of the 600 -> 580 -> 560 edge (3 passes)"
foreach ($pass in 1..3) {
  foreach ($H in @('600','580','560','600')) {
    SetDisp "1366x$H" 4
    $ok = EnsureIme "C2p$pass@$H"
    $g = ImeGeom
    $x = Dump "C2_p${pass}_$H"; $ns = Nodes $x
    $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
    $sd = $ns | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
    $ot = $ns | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
    $form = 'UNKNOWN'
    if ($et -and $sd) { if ($sd.y1 -le $et.y2 + 2) { $form = 'ONEROW' } else { $form = 'STACKED' } }
    $chipH = if ($ot) { $ot.h } else { 'NA' }
    $room = if ($null -ne $g.H) { [int]$H - $g.H } else { 'NA' }
    W_ ("  pass{0} {1,-5} imeH={2,-7} room={3,-8} FORM={4,-8} chipH={5,-4} ET=[{6}]" -f `
        $pass, $H, $g.H, $room, $form, $chipH, $(if($et){"$($et.x1),$($et.y1)-$($et.x2),$($et.y2)"}else{'NA'}))
  }
}
W_ "### ctrl done"
