$ErrorActionPreference='Stop'
. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v128\helpers.ps1
function Ed($n) { $n | Where-Object { $_.class -match 'EditText' -and $_.wd -ge 200 } | Select-Object -First 1 }
function ClearEditor {
  A shell input keyevent KEYCODE_MOVE_END | Out-Null
  $keys = 1..160 | ForEach-Object { '67' }
  & $global:ADB -s $global:SERIAL shell input keyevent @keys | Out-Null
  Start-Sleep -Milliseconds 800
}
function FindQueueChip($n) {
  $lab = $n | Where-Object { $_.text -eq 'Queue' } | Select-Object -First 1
  if (-not $lab) { return $null }
  $anc = $n | Where-Object { $_.clickable -eq 'true' -and $_.x1 -le $lab.x1 -and $_.x2 -ge $lab.x2 -and $_.y1 -le $lab.y1 -and $_.y2 -ge $lab.y2 }
  if (-not $anc) { return $null }
  $anc | Sort-Object { $_.wd * $_.ht } | Select-Object -First 1
}
function DumpPanel([string]$Tag) {
  $p = Nodes (Dump "${Tag}.xml")
  $pb = [int](($p | Where-Object { $_.x1 -le 4 -and $_.wd -ge 900 -and $_.y1 -ge 100 } | Sort-Object ht | Select-Object -Last 1).y2)
  "===== PANEL @ $Tag (paneBottom=$pb) ====="
  foreach ($c in $p) {
    if ($c.text -match 'Steer' -or $c.desc -eq 'Remove' -or $c.text -match 'E2ECHK5554u1r follow' -or $c.desc -eq 'Queue') {
      "  text=[$($c.text)] desc=[$($c.desc)] x1=$($c.x1) y1=$($c.y1) x2=$($c.x2) y2=$($c.y2) w=$($c.wd) h=$($c.ht) clk=$($c.clickable) class=$($c.class)"
    }
  }
  "  -- queue row containers (clickable, w>=200, above composer) --"
  foreach ($c in $p) {
    if ($c.clickable -eq 'true' -and $c.wd -ge 200 -and $c.y2 -le $pb -and $c.y1 -ge ($pb - 200) -and $c.desc -eq '' -and $c.text -eq '') {
      "  ROW x1=$($c.x1) y1=$($c.y1) x2=$($c.x2) y2=$($c.y2) w=$($c.wd) h=$($c.ht) clk=$($c.clickable)"
    }
  }
}

# ============ POSE 3: 3 follow-ups queued behind one running turn ============
"##### POSE 3: 3 follow-ups behind a running turn #####"
ClearEditor
$ed = Ed (Nodes (Dump 'p3_pre.xml'))
TapXY $ed.cx $ed.cy
TypeText 'E2ECHK5554u1r give a full encyclopedic account of Byzantine emperors and their wars, over 3000 words'
$n = Nodes (Dump 'p3_typed.xml')
"typed=[$((Ed $n).text)]"
$snd = $n | Where-Object { $_.desc -eq 'Send' -and $_.clickable -eq 'true' } | Select-Object -First 1
TapXY $snd.cx $snd.cy
$run = $false
for ($i=0; $i -lt 15; $i++) { Start-Sleep -Milliseconds 500; if (Nodes (Dump "p3_run$i.xml") | Where-Object { $_.desc -eq 'Stop' }) { $run = $true; "RUNNING at poll $i"; break } }
if (-not $run) { "!! not running" }

# queue three follow-ups: each time load composer, arm Queue chip, press Send
foreach ($k in 1,2,3) {
  $e = Ed (Nodes (Dump "p3_e$k.xml"))
  TapXY $e.cx $e.cy
  TypeText "E2ECHK5554u1r followup number $k queued behind"
  $n = Nodes (Dump "p3_c$k.xml")
  $chip = FindQueueChip $n
  "k=$k running=$((($n|Where-Object {$_.desc -eq 'Stop'}).Count) -gt 0) chip=$(if($chip){"$($chip.wd)x$($chip.ht)@$($chip.cx),$($chip.cy)"}else{'NONE'})"
  if ($chip) { TapXY $chip.cx $chip.cy; Start-Sleep -Milliseconds 600 }
  $n2 = Nodes (Dump "p3_s$k.xml")
  $btn = $n2 | Where-Object { ($_.desc -eq 'Send' -or $_.desc -eq 'Stop') -and $_.clickable -eq 'true' } | Select-Object -First 1
  "k=$k submit btn=[$($btn.desc)]"
  if ($btn.desc -eq 'Send') { TapXY $btn.cx $btn.cy; "k=$k submitted-as-queue"; Start-Sleep -Milliseconds 1500 }
  else { "k=$k SKIPPED (turn not running)" }
}
Start-Sleep -Seconds 2
DumpPanel 'p3_panel'
Shot 'p3_panel' | Out-Null
"##### POSE 3 DONE #####"
