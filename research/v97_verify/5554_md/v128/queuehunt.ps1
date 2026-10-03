$ErrorActionPreference='Stop'
. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v128\helpers.ps1
function Ed($n) { $n | Where-Object { $_.class -match 'EditText' -and $_.wd -ge 200 } | Select-Object -First 1 }
function ClearEditor { A shell input keyevent KEYCODE_MOVE_END | Out-Null; A shell input keyevent @(67)*200 | Out-Null; Start-Sleep -Milliseconds 600 }

function FindQueueChip($n) {
  # the clickable chip is the parent View; the label TextView carries text=Queue
  $lab = $n | Where-Object { $_.text -eq 'Queue' } | Select-Object -First 1
  if (-not $lab) { return $null }
  $n | Where-Object { $_.clickable -eq 'true' -and $_.x1 -le $lab.x1 -and $_.x2 -ge $lab.x2 -and
    $_.y1 -le $lab.y1 -and $_.y2 -ge $lab.y2 } | Select-Object -First 1
}

"##### QUEUE PANEL HUNT ##### appPid=$(AppPid)"

# ---- 1. long turn ----
ClearEditor
$ed = Ed (Nodes (Dump "qh_pre.xml"))
TapXY $ed.cx $ed.cy
TypeText 'E2ECHK5554u1r write a detailed step by step history of the Roman Empire in at least 4000 words, section by section, with dates and names'
$n = Nodes (Dump "qh_typed.xml")
"typed: [$((Ed $n).text)]"
$snd = $n | Where-Object { $_.desc -eq 'Send' -and $_.clickable -eq 'true' } | Select-Object -First 1
TapXY $snd.cx $snd.cy

# ---- 2. poll until running, then immediately load the composer ----
$running = $false
for ($i=0; $i -lt 12; $i++) {
  Start-Sleep -Milliseconds 700
  $n = Nodes (Dump "qh_run$i.xml")
  if ($n | Where-Object { $_.desc -eq 'Stop' }) { $running = $true; "running at poll $i"; break }
}
if (-not $running) { "WARNING: turn not running" }
$ed2 = Ed (Nodes (Dump "qh_e.xml"))
TapXY $ed2.cx $ed2.cy
TypeText 'E2ECHK5554u1r followup that will be queued'

$n = Nodes (Dump "qh_content.xml")
"composer=[$((Ed $n).text)]  running=$((($n|Where-Object {$_.desc -eq 'Stop'}).Count) -gt 0)  ime=$((A shell dumpsys input_method|Select-String mInputShown|Select-Object -First 1).ToString().Trim())"

# ---- 3. tap the Queue chip (arms the alternate=queue submit) ----
$chip = FindQueueChip $n
if ($chip) {
  "QUEUE CHIP: [$($chip.x1),$($chip.y1)][$($chip.x2),$($chip.y2)] w=$($chip.wd) h=$($chip.ht) clk=$($chip.clickable)"
  Shot "qh_chip" | Out-Null
  TapXY $chip.cx $chip.cy
  Start-Sleep -Milliseconds 800
  $n2 = Nodes (Dump "qh_chipped.xml")
  "after chip tap, running=$((($n2|Where-Object {$_.desc -eq 'Stop'}).Count) -gt 0)  composer=[$((Ed $n2).text)]"
  $snd2 = $n2 | Where-Object { ($_.desc -eq 'Send' -or $_.desc -eq 'Stop') -and $_.clickable -eq 'true' } | Select-Object -First 1
  "submit btn=[$($snd2.desc)] at cx=$($snd2.cx) cy=$($snd2.cy)"
  if ($snd2.desc -eq 'Send') { TapXY $snd2.cx $snd2.cy; "SUBMITTED (armed as queue)"; Start-Sleep -Seconds 2 }
  else { "turn still running -> cannot submit; will retry" }
} else { "NO QUEUE CHIP THIS ROUND" }

# ---- 4. look for the queue panel + Steer / x rows ----
for ($j=0; $j -lt 6; $j++) {
  $p = Nodes (Dump "qh_panel$j.xml")
  $steer = $p | Where-Object { $_.text -match 'Steer' -or $_.desc -match 'Steer' }
  $xmark = $p | Where-Object { $_.text -eq ([char]0x00D7) -or $_.desc -eq ([char]0x00D7) -or $_.text -match '^(x|X)$' }
  $qcnt  = ($p | Where-Object { $_.text -match 'Queue' }).Count
  "panel$j : queueTextNodes=$qcnt  steer=$($steer.Count)  xmark=$($xmark.Count)"
  if ($steer.Count -gt 0 -or $xmark.Count -gt 0) {
    "  ---- QUEUE ROW INVENTORY ----"
    $p | Where-Object { $_.text -match 'Steer|E2ECHK5554u1r followup that will be queued' -or $_.desc -match 'Steer' -or $_.text -eq ([char]0x00D7) } |
      ForEach-Object { "  text=[$($_.text)] desc=[$($_.desc)] [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] w=$($_.wd) h=$($_.ht) clk=$($_.clickable) class=$($_.class)" }
    Shot "qh_panel$j" | Out-Null
    break
  }
  Start-Sleep -Seconds 2
}
"##### HUNT DONE #####"
