# 3-round idempotency, CORRECTED row selection (row text paired with its 'Queued' label)
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"

$log = @()

# --- robust queue-row finder: the row text sits on the same y-band as a 'Queued' label ---
function FindQueuedRow($ns, [int]$idx = 0) {
  $q = @($ns | Where-Object { $_.text -eq 'Queued' })
  if ($q.Count -eq 0) { return $null }
  if ($idx -ge $q.Count) { $idx = 0 }
  $lbl = $q[$idx]
  $cands = $ns | Where-Object {
    $_.text -and $_.text -like 'E2ECHK5554u1t*' -and $_.w -lt 500 -and
    ([math]::Abs($_.cy - $lbl.cy) -le 12) -and ($_.x1 -gt $lbl.x2)
  }
  if ($cands) { return ($cands | Select-Object -First 1) }
  return $null
}

function BarOpen($ns) {
  return (@($ns | Where-Object { $_.text -eq 'Steer' -and $_.clickable -eq 'true' }).Count -gt 0)
}

function ComposerChip($ns) {
  # composer chip = non-clickable TextView labelled Queue/Steer (row actions are clickable Buttons)
  $c = $ns | Where-Object { ($_.text -eq 'Queue' -or $_.text -eq 'Steer') -and $_.clickable -eq 'false' } | Select-Object -First 1
  if ($c) { return $c.text }
  return '<none>'
}

function EnsureContent([string]$t) {
  $x = Dump 'x'; $ns = Nodes $x
  $c = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  if ($c -and $c.text -and $c.text.Trim()) { return }
  adbx shell input text $t | Out-Null
  Start-Sleep -Milliseconds 1300
}

function EnsureIme($ns) {
  if (-not (ImeState)) {
    $c = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
    if ($c) { adbx shell input tap $c.cx $c.cy | Out-Null; Start-Sleep -Milliseconds 1000 }
  }
}

# --- 0. make sure a long turn is running so the Queue chip can exist ---
$x = Dump 'p0'; $ns = Nodes $x
$running = (@($ns | Where-Object { $_.text -eq 'Working' -and $_.w -lt 40 }).Count -gt 0)
Write-Output "turn running at start: $running"
if (-not $running) {
  EnsureIme $ns
  # clear + send a long prompt
  1..50 | ForEach-Object { adbx shell input keyevent 67 | Out-Null }
  Start-Sleep -Milliseconds 400
  adbx shell input text "Counting%sfrom%s1%sto%s6000,%sone%snumber%sper%sline" | Out-Null
  Start-Sleep -Milliseconds 1200
  $x = Dump 'p1'; $ns = Nodes $x
  $send = $ns | Where-Object { ($_.text -eq 'Send' -or $_.desc -eq 'Send') -and $_.clickable -eq 'true' } | Select-Object -First 1
  if ($send) { Tap $send; Write-Output "sent long prompt" }
  Start-Sleep -Seconds 4
  $x = Dump 'p2'; $ns = Nodes $x
  $running = (@($ns | Where-Object { $_.text -eq 'Working' -and $_.w -lt 40 }).Count -gt 0)
  Write-Output "turn running after send: $running"
}

for ($i = 1; $i -le 3; $i++) {
  $action = if ($i % 2 -eq 1) { 'submit' } else { 'cancel' }
  Write-Output "===== ROUND $i ($action) ====="

  $x = Dump "R${i}_0"; $ns = Nodes $x
  EnsureIme $ns

  # ensure composer has content so the chip is observable
  EnsureContent ("E2ECHK5554u1t%sr${i}%spayload")

  # ensure the bar is open
  $x = Dump "R${i}_1"; $ns = Nodes $x
  if (-not (BarOpen $ns)) {
    $q = $ns | Where-Object { $_.text -eq 'Queue' } | Select-Object -First 1
    if ($q) { Tap $q; Start-Sleep -Milliseconds 1600 }
  }

  # find a real queued row and tap it
  $x = Dump "R${i}_2"; $ns = Nodes $x
  $row = FindQueuedRow $ns 0
  if (-not $row) { $row = FindQueuedRow $ns 1 }
  if (-not $row) { Write-Output "  NO QUEUED ROW"; $log += [pscustomobject]@{Round=$i;Action=$action;EnteredEdit='NO';EditChip='';ChipAfter='';BarReopens='';Note='no row'}; continue }
  Write-Output "  row='$($row.text)' [$($row.x1),$($row.y1)][$($row.x2),$($row.y2)]"
  Tap $row
  Start-Sleep -Milliseconds 1600

  $x = Dump "R${i}_3"; $ns = Nodes $x
  $editChip = ComposerChip $ns
  $entered = ($editChip -eq 'Steer')
  Write-Output "  entered edit = $entered ; chip in edit = '$editChip'"
  Shot "R${i}_edit" | Out-Null
  if (-not $entered) {
    $log += [pscustomobject]@{Round=$i;Action=$action;EnteredEdit='NO';EditChip=$editChip;ChipAfter='';BarReopens='';Note='edit not entered'}
    continue
  }

  if ($action -eq 'submit') {
    $st = $ns | Where-Object { $_.text -eq 'Steer' -and $_.clickable -eq 'false' } | Select-Object -First 1
    if ($st) { Tap $st } else { Write-Output "  no Steer chip to submit" }
    Start-Sleep -Milliseconds 2000
  } else {
    adbx shell input keyevent 111 | Out-Null   # 1st ESC: IME down
    Start-Sleep -Milliseconds 1000
    $x = Dump "R${i}_3b"; $ns = Nodes $x
    if ((ComposerChip $ns) -eq 'Steer') {
      adbx shell input keyevent 111 | Out-Null   # 2nd ESC: cancelQueueEdit
      Start-Sleep -Milliseconds 1600
    }
  }

  # restore content so the chip is observable
  EnsureContent ("E2ECHK5554u1t%sr${i}%sprobe")

  $x = Dump "R${i}_4"; $ns = Nodes $x
  $chip = ComposerChip $ns
  Shot "R${i}_after" | Out-Null

  # re-open the bar from the Queue chip
  $q = $ns | Where-Object { $_.text -eq 'Queue' } | Select-Object -First 1
  $reopen = 'NO-CHIP'
  if ($q) {
    Tap $q; Start-Sleep -Milliseconds 1700
    $x2 = Dump "R${i}_5"; $ns2 = Nodes $x2
    $reopen = if (BarOpen $ns2) { 'YES' } else { 'NO' }
    Shot "R${i}_reopen" | Out-Null
  }
  Write-Output "  chip after $action = '$chip' ; barReopens = $reopen"
  $log += [pscustomobject]@{Round=$i;Action=$action;EnteredEdit='YES';EditChip=$editChip;ChipAfter=$chip;BarReopens=$reopen;Note=''}
}

Write-Output ""
Write-Output "===== 3-ROUND TABLE ====="
$log | Format-Table -AutoSize | Out-String | Write-Output
$log | Export-Csv "$W\artifacts\rounds.csv" -NoTypeInformation -Encoding UTF8

