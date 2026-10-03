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
function Ime { (A shell dumpsys input_method | Select-String 'mInputShown' | Select-Object -First 1).ToString().Trim() }

# ================= POSE 2: running + content, IME CLOSED =================
"##### POSE 2 (IME closed) #####"
ClearEditor
$ed = Ed (Nodes (Dump 'p2_pre.xml'))
TapXY $ed.cx $ed.cy
TypeText 'E2ECHK5554u1r trace the full history of the Silk Road trade routes in over 2500 words'
$n = Nodes (Dump 'p2_typed.xml')
$snd = $n | Where-Object { $_.desc -eq 'Send' -and $_.clickable -eq 'true' } | Select-Object -First 1
TapXY $snd.cx $snd.cy
$run = $false
for ($i=0; $i -lt 15; $i++) { Start-Sleep -Milliseconds 500; if (Nodes (Dump "p2_run$i.xml") | Where-Object { $_.desc -eq 'Stop' }) { $run = $true; "RUNNING at poll $i"; break } }
"running=$run  ime=$(Ime)"
$e2 = Ed (Nodes (Dump 'p2_e.xml'))
TapXY $e2.cx $e2.cy
TypeText 'E2ECHK5554u1r followup while ime hidden'
"ime after typing: $(Ime)"

# close the IME WITHOUT BACK(4): the nav-bar IME-hide chevron, then verify
A shell input tap 1330 744 | Out-Null
Start-Sleep -Seconds 2
"ime after nav-bar hide tap: $(Ime)"
if ((Ime) -notmatch 'true') { "IME HIDDEN (nav-bar chevron)" }
else {
  "nav-bar tap did not hide it; NOT forcing (no BACK, no ime reset). Reporting as-is."
}
$n2 = Nodes (Dump 'p2_noime.xml')
$pb = [int](($n2 | Where-Object { $_.x1 -le 4 -and $_.wd -ge 900 -and $_.y1 -ge 100 } | Sort-Object ht | Select-Object -Last 1).y2)
"===== CHIP ROW @ p2_noime (paneBottom=$pb, ime=$(Ime), running=$((($n2|Where-Object {$_.desc -eq 'Stop'}).Count) -gt 0)) ====="
foreach ($c in $n2) { if ($c.y1 -ge ($pb-60) -and $c.y2 -le $pb) { "  x1=$($c.x1) y1=$($c.y1) x2=$($c.x2) y2=$($c.y2) w=$($c.wd) h=$($c.ht) clk=$($c.clickable) desc=[$($c.desc)] text=[$($c.text)]" } }
$chip = FindQueueChip $n2
"QUEUE CHIP (ime closed): $(if($chip){"$($chip.wd)x$($chip.ht) clk=$($chip.clickable) @[$($chip.x1),$($chip.y1)][$($chip.x2),$($chip.y2)]"}else{'NONE'})"
$st2 = $n2 | Where-Object { $_.text -match 'Steer' -or $_.desc -eq 'Remove' }
foreach ($c in $st2) { "  panel: text=[$($c.text)] desc=[$($c.desc)] w=$($c.wd) h=$($c.ht) clk=$($c.clickable) [$($c.x1),$($c.y1)][$($c.x2),$($c.y2)]" }
Shot 'p2_noime' | Out-Null
"##### POSE 2 DONE #####"

# ================= CLEANUP =================
"`n##### CLEANUP #####"
A shell am force-stop com.xiufeigo.opencodeshell | Out-Null
Start-Sleep -Seconds 2
A shell wm size 1366x768 | Out-Null
A shell wm density 160 | Out-Null
Start-Sleep -Seconds 3
"wm size   : $((A shell wm size | Select-String Override).ToString().Trim())"
"wm density: $((A shell wm density | Select-String Override).ToString().Trim())"
"pidof after force-stop: '$(AppPid)'  (empty == stopped)"
"--- /sdcard residue (png/xml/s.png/w.xml) ---"
A shell "ls -la /sdcard/*.png /sdcard/*.xml" 2>&1 | ForEach-Object { "  " + $_.ToString().Trim() }
A shell "ls /sdcard/" | ForEach-Object { "  SDCARD: " + $_.ToString().Trim() }
"##### CLEANUP DONE #####"
