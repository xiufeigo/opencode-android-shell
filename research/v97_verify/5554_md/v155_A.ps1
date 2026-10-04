# v155 Task A: form monotonicity. Shrink the window = shrink the room.
# Every level: wm density 160 + wm size 1366x<H>, assert IME, dump, re-parse.
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\v155_lib.ps1"

$log = "$W\_v155_A.log"
if (Test-Path $log) { Remove-Item $log }
function W_ { param($m) $m | Tee-Object -FilePath $log -Append }

# Ensure the IME is up before ANY height read. Re-tap the composer if it collapsed.
function EnsureIme {
  param([string]$tag)
  if (ImeState) { return $true }
  W_ "  !! IME collapsed at $tag -> re-tapping composer"
  $x = Dump "${tag}_imerecover"
  $ns = Nodes $x
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  if ($et) { adbx shell input tap $et.cx $et.cy | Out-Null }
  Start-Sleep -Seconds 2
  if (-not (ImeState)) { W_ "  !! still collapsed, tapping again"; Start-Sleep -Seconds 2 }
  return (ImeState)
}

W_ "### A0 composer population (IME kept open)"
SetDisp '1366x768' 4
W_ ("  appPid=" + (AppPid))
W_ ("  focus=" + ((adbx shell dumpsys window 2>&1 | Select-String 'mCurrentFocus') -join ''))

# put real content in the composer so the form decision actually moves
$x = Dump 'A0_pre'
$ns = Nodes $x
$et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
if ($et) {
  adbx shell input tap $et.cx $et.cy | Out-Null
  Start-Sleep -Seconds 2
  adbx shell input text "E2ECHK5554u1a%smonotone%sform" | Out-Null
  Start-Sleep -Seconds 2
  W_ ("  typed into composer; mInputShown=" + (ImeState))
} else { W_ "  !! no EditText to type into" }

$levels = @('1366x768','1366x700','1366x660','1366x620','1366x600','1366x580','1366x560')
$rows = @()
foreach ($sz in $levels) {
  $H = ($sz -split 'x')[1]
  SetDisp $sz 3
  $script:sz_now = $sz
  $ok = EnsureIme "A@$sz"
  if (-not $ok) { W_ "  ABORT: cannot restore IME at $sz"; break }
  $g = Geo "A_${H}"
  if ($g.Err) { W_ "  A_${H} ERR $($g.Err)"; $rows += [pscustomobject]@{H=$H;Win=$sz;Err=$g.Err}; continue }
  W_ ("  A_{0,-4} paneH={1,-4} timelineBox={2,-4} readable={3,-4} unionPx={4,-4} 1i={5,-4} FORM={6,-8} {7}" -f `
       $H, $g.PaneH, $g.TimelineBox, $g.Readable, $g.UnionPx, $g.C1, $g.Form, $g.RowNote)
  W_ ("           chip={0}" -f $g.Chip)
  W_ ("           send={0}" -f $g.Send)
  $rows += [pscustomobject]@{ H=$H; Win=$sz; PaneH=$g.PaneH; TimelineBox=$g.TimelineBox
    Readable=$g.Readable; UnionPx=$g.UnionPx; C1=$g.C1; Form=$g.Form; Ime=$g.Ime
    Chip=$g.Chip; Send=$g.Send; RowNote=$g.RowNote; Xml=$g.Xml }
}

W_ ""
W_ "### A1 monotonicity verdict (window shrinking 768 -> 560)"
$seq = ($rows | Where-Object { $_.Form }).Form
W_ ("  form sequence: " + ($seq -join ' -> '))
$viol = 0
for ($i = 1; $i -lt $seq.Count; $i++) {
  # shrinking room: once ONEROW (cheapest) is taken, a bigger form may never return
  if ($seq[$i-1] -eq 'ONEROW' -and $seq[$i] -eq 'STACKED') {
    W_ ("  !! REVERSE JUMP at level $($rows[$i].H): ONEROW -> STACKED  (room got SMALLER, form got BIGGER)")
    $viol++
  }
}
$prevB = $null
foreach ($r in $rows) {
  if ($null -ne $prevB -and $r.Readable -ne $null -and $r.Readable -gt $prevB) {
    W_ ("  !! BODY GREW at $($r.H): readable $prevB -> $($r.Readable) while the room got smaller")
    $viol++
  }
  if ($r.Readable -ne $null) { $prevB = $r.Readable }
}
if ($viol -eq 0) { W_ "  MONOTONE: no reverse jump, body never grew. PASS" } else { W_ "  violations=$viol FAIL" }

$rows | Export-Csv "$script:ART\_v155_A_rows.csv" -NoTypeInformation -Encoding UTF8
W_ ("### A done, rows=" + $rows.Count)
