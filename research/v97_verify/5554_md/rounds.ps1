# 3-round idempotency: edit -> submit / cancel ; assert composer chip == 'Queue' each round
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"

$log = @()
function Rec($r,$action,$chipAfter,$rowAfter,$barAfter,$note) {
  $script:log += [pscustomobject]@{ Round=$r; Action=$action; ChipAfter=$chipAfter; RowState=$rowAfter; QueueBarReopens=$barAfter; Note=$note }
}

function EnsureContent([string]$text) {
  $x = Dump 'r_tmp'; $ns = Nodes $x
  $c = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  if ($c -and $c.text -and $c.text.Trim()) { return }
  adbx shell input text $text | Out-Null
  Start-Sleep -Milliseconds 1200
}

function EnsureQueueBar {
  # returns $true if bar is/was open (Steer row-action present)
  $x = Dump 'r_tmp'; $ns = Nodes $x
  $steerBtn = $ns | Where-Object { $_.text -eq 'Steer' -and $_.clickable -eq 'true' } | Select-Object -First 1
  if ($steerBtn) { return $true }
  $q = $ns | Where-Object { $_.text -eq 'Queue' } | Select-Object -First 1
  if ($q) { Tap $q; Start-Sleep -Milliseconds 1500; return $true }
  return $false
}

function ChipLabel {
  $x = Dump 'r_tmp'; $ns = Nodes $x
  # composer chip lives in the composer row (largest y, not clickable Button)
  $c = $ns | Where-Object { ($_.text -eq 'Queue' -or $_.text -eq 'Steer' -or $_.text -eq 'Send') -and $_.clickable -eq 'false' } | Select-Object -First 1
  if ($c) { return $c.text }
  # fallback: any of the labels
  $c2 = $ns | Where-Object { $_.text -eq 'Queue' -or $_.text -eq 'Steer' } | Select-Object -First 1
  if ($c2) { return $c2.text }
  return '<none>'
}

for ($i=1; $i -le 3; $i++) {
  $action = if ($i % 2 -eq 1) { 'submit' } else { 'cancel' }
  Write-Output "===== ROUND $i ($action) ====="

  # 1. make sure IME is up + composer has content
  $x = Dump "r${i}_a"; $ns = Nodes $x
  $c = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  if (-not (ImeState)) {
    if ($c) { adbx shell input tap $c.cx $c.cy | Out-Null; Start-Sleep -Milliseconds 900 }
  }
  EnsureContent ("E2ECHK5554u1t%sr${i}%spayload")

  # 2. ensure the bar is open (Queue chip -> bar)
  $barOpen = EnsureQueueBar
  Write-Output "  queue bar open: $barOpen"

  # 3. tap a queued row body -> edit
  $x = Dump "r${i}_b"; $ns = Nodes $x
  $row = $ns | Where-Object { $_.text -like 'E2ECHK5554u1t r*payload*' } | Select-Object -First 1
  if (-not $row) { $row = $ns | Where-Object { $_.text -like '*E2ECHK5554u1t*' -and $_.w -lt 400 -and $_.y1 -lt 200 } | Select-Object -First 1 }
  if (-not $row) { Write-Output "  NO ROW FOUND"; Rec $i $action '<no-row>' '' 'n/a' 'no row'; continue }
  Write-Output "  row: '$($row.text)' [$($row.x1),$($row.y1)][$($row.x2),$($row.y2)]"
  Tap $row
  Start-Sleep -Milliseconds 1500

  $chipEdit = ChipLabel
  Write-Output "  chip in edit mode: $chipEdit"

  # 4. the action
  if ($action -eq 'submit') {
    $x = Dump "r${i}_c"; $ns = Nodes $x
    $st = $ns | Where-Object { $_.text -eq 'Steer' -and $_.clickable -eq 'false' } | Select-Object -First 1
    if ($st) { Tap $st } else { Write-Output "  no Steer chip!" }
    Start-Sleep -Milliseconds 1800
  } else {
    adbx shell input keyevent 111 | Out-Null   # 1st: IME down
    Start-Sleep -Milliseconds 900
    adbx shell input keyevent 111 | Out-Null   # 2nd: cancelQueueEdit
    Start-Sleep -Milliseconds 1500
  }

  # 5. restore composer content so the chip is observable
  EnsureContent ("E2ECHK5554u1t%sr${i}%sprobe")

  # 6. assertions
  $chip = ChipLabel
  $x = Dump "r${i}_d"; $ns = Nodes $x
  Shot "r${i}_d" | Out-Null
  $rowsNow = ($ns | Where-Object { $_.text -like '*E2ECHK5554u1t*' -and $_.w -lt 500 -and $_.y1 -lt 200 }).Count
  $queuedLbl = ($ns | Where-Object { $_.text -eq 'Queued' }).Count

  # re-open the bar from the Queue chip
  $q = $ns | Where-Object { $_.text -eq 'Queue' } | Select-Object -First 1
  $reopen = 'n/a'
  if ($q) {
    Tap $q; Start-Sleep -Milliseconds 1500
    $x2 = Dump "r${i}_e"; $ns2 = Nodes $x2
    $hasBar = ($ns2 | Where-Object { $_.text -eq 'Steer' -and $_.clickable -eq 'true' }).Count -gt 0
    $reopen = if ($hasBar) { 'YES' } else { 'NO' }
    Shot "r${i}_e" | Out-Null
  }
  Write-Output "  chip after $action = '$chip' | rows=$rowsNow queuedLabels=$queuedLbl | barReopens=$reopen"
  Rec $i $action $chip "rows=$rowsNow/q=$queuedLbl" $reopen "editChip=$chipEdit"
}

Write-Output ""
Write-Output "===== 3-ROUND IDEMPOTENCY TABLE ====="
$log | Format-Table -AutoSize | Out-String | Write-Output
$log | Export-Csv "$W\artifacts\rounds.csv" -NoTypeInformation -Encoding UTF8
