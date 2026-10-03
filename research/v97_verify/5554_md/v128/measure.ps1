$ErrorActionPreference='Stop'
. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v128\helpers.ps1
function Ed($n) { $n | Where-Object { $_.class -match 'EditText' -and $_.wd -ge 200 } | Select-Object -First 1 }

function ClearEditor {
  A shell input keyevent KEYCODE_MOVE_END | Out-Null
  # explicit repeated KEYCODE_DEL (67) as separate argv entries
  $keys = 1..160 | ForEach-Object { '67' }
  & $global:ADB -s $global:SERIAL shell input keyevent @keys | Out-Null
  Start-Sleep -Milliseconds 800
}

# smallest clickable ancestor of the `Queue` label == the chip itself
function FindQueueChip($n) {
  $lab = $n | Where-Object { $_.text -eq 'Queue' } | Select-Object -First 1
  if (-not $lab) { return $null }
  $anc = $n | Where-Object { $_.clickable -eq 'true' -and $_.x1 -le $lab.x1 -and $_.x2 -ge $lab.x2 -and
    $_.y1 -le $lab.y1 -and $_.y2 -ge $lab.y2 }
  if (-not $anc) { return $null }
  $anc | Sort-Object { $_.wd * $_.ht } | Select-Object -First 1
}

"##### QUEUE PANEL MEASURE ##### appPid=$(AppPid)"
ClearEditor
"cleared -> [$((Ed (Nodes (Dump 'm_c0.xml'))).text)]"

$ed = Ed (Nodes (Dump 'm_pre.xml'))
TapXY $ed.cx $ed.cy
TypeText 'E2ECHK5554u1r list every Roman emperor in order with their reign dates, one per line, at least 2000 words of detail'
"typed: [$((Ed (Nodes (Dump 'm_typed.xml'))).text)]"
$n = Nodes (Dump 'm_typed.xml')
$snd = $n | Where-Object { $_.desc -eq 'Send' -and $_.clickable -eq 'true' } | Select-Object -First 1
TapXY $snd.cx $snd.cy

# wait for running
$running = $false
for ($i=0; $i -lt 15; $i++) {
  Start-Sleep -Milliseconds 600
  $n = Nodes (Dump "m_run$i.xml")
  if ($n | Where-Object { $_.desc -eq 'Stop' }) { $running = $true; "RUNNING at poll $i"; break }
}
if (-not $running) { "!! turn not running" }

# load the composer WHILE running
$ed2 = Ed (Nodes (Dump 'm_e.xml'))
TapXY $ed2.cx $ed2.cy
TypeText 'E2ECHK5554u1r queued followup beta'
$n = Nodes (Dump 'm_content.xml')
$stillRun = (($n | Where-Object { $_.desc -eq 'Stop' }).Count) -gt 0
"composer=[$((Ed $n).text)]  running=$stillRun  ime=$((A shell dumpsys input_method|Select-String mInputShown|Select-Object -First 1).ToString().Trim())"

$chip = FindQueueChip $n
if ($chip) {
  "QUEUE CHIP: [$($chip.x1),$($chip.y1)][$($chip.x2),$($chip.y2)] w=$($chip.wd) h=$($chip.ht) clk=$($chip.clickable) class=$($chip.class)"
  Shot 'm_chip' | Out-Null
  TapXY $chip.cx $chip.cy
  Start-Sleep -Milliseconds 700
  $n2 = Nodes (Dump 'm_chipped.xml')
  "after chip: running=$((($n2|Where-Object {$_.desc -eq 'Stop'}).Count) -gt 0)"
  $s2 = $n2 | Where-Object { ($_.desc -eq 'Send' -or $_.desc -eq 'Stop') -and $_.clickable -eq 'true' } | Select-Object -First 1
  "btn=[$($s2.desc)] cx=$($s2.cx) cy=$($s2.cy)"
  if ($s2.desc -eq 'Send') { TapXY $s2.cx $s2.cy; "SUBMITTED armed-as-queue"; Start-Sleep -Seconds 2 }
} else { "NO QUEUE CHIP" }

# poll for the panel
for ($j=0; $j -lt 8; $j++) {
  $p = Nodes (Dump "m_panel$j.xml")
  $st = $p | Where-Object { $_.text -match 'Steer' -or $_.desc -match 'Steer' }
  $xm = $p | Where-Object { $_.text -eq ([char]0x00D7) -or $_.desc -eq ([char]0x00D7) }
  "panel$j : queueLabel=$(($p|Where-Object {$_.text -match 'Queue'}).Count) steer=$($st.Count) xmark=$($xm.Count)"
  if ($st.Count -gt 0 -or $xm.Count -gt 0) {
    "  === QUEUE PANEL ROW INVENTORY ==="
    $p | Where-Object { $_.text -match 'Steer|E2ECHK5554u1r queued followup beta' -or $_.desc -match 'Steer' -or $_.text -eq ([char]0x00D7) -or $_.desc -eq ([char]0x00D7) } |
      ForEach-Object { "  text=[$($_.text)] desc=[$($_.desc)] [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] w=$($_.wd) h=$($_.ht) clk=$($_.clickable) class=$($_.class)" }
    "  --- all clickable nodes in the panel band ---"
    $paneNode = $p | Where-Object { $_.x1 -le 4 -and $_.wd -ge 900 -and $_.y1 -ge 100 } | Sort-Object ht | Select-Object -Last 1
    $pb = [int]$paneNode.y2
    $lo = $pb - 260
    foreach ($c in $p) {
      if ($c.clickable -eq 'true' -and $c.y1 -ge $lo -and $c.y2 -le $pb) {
        "  clk x1=$($c.x1) y1=$($c.y1) x2=$($c.x2) y2=$($c.y2) w=$($c.wd) h=$($c.ht) desc=[$($c.desc)] text=[$($c.text)]"
      }
    }
    Shot "m_panel$j" | Out-Null
    break
  }
  Start-Sleep -Seconds 2
}
"##### DONE #####"
