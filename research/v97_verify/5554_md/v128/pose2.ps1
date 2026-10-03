$ErrorActionPreference='Stop'
. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v128\helpers.ps1

# Move caret to end, then backspace enough to empty the editor.
function ClearEditor {
  A shell input keyevent KEYCODE_MOVE_END | Out-Null
  $dels = @(67) * 200
  A shell input keyevent $dels | Out-Null
  Start-Sleep -Milliseconds 700
}

function Ed($n) { $n | Where-Object { $_.class -match 'EditText' -and $_.wd -ge 200 } | Select-Object -First 1 }

function ChipRow([string]$Tag) {
  $f = Dump "${Tag}.xml"
  $n = Nodes $f
  $pane = $n | Where-Object { $_.x1 -le 4 -and $_.wd -ge 900 -and $_.y1 -ge 100 } | Sort-Object ht | Select-Object -Last 1
  "===== CHIP ROW @ $Tag (paneBottom=$($pane.y2)) ====="
  $n | Where-Object { $_.y1 -ge ($pane.y2 - 60) -and $_.y2 -le $pane.y2 } | ForEach-Object {
    "  [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] w=$($_.wd) h=$($_.ht) clk=$($_.clickable) desc=[$($_.desc)] text=[$($_.text)]"
  }
  $hits = $n | Where-Object { $_.desc -match 'Queue|Steer' -or $_.text -match 'Queue|Steer' }
  if ($hits) { $hits | ForEach-Object { "  >> HIT desc=[$($_.desc)] text=[$($_.text)] [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] clk=$($_.clickable)" } }
  else { "  >> NO Queue/Steer node in full tree" }
}

$pose = $args[0]
"##### POSE $pose ##### appPid=$(AppPid) ime=$((A shell dumpsys input_method|Select-String mInputShown|Select-Object -First 1).ToString().Trim())"

# --- 1. clear editor, type long turn, send ---
$n = Nodes (Dump "${pose}_pre.xml")
$ed = Ed $n
ClearEditor
"after clear: [$(Ed (Nodes (Dump "${pose}_cleared.xml"))).text]"
$ed = Ed (Nodes (Dump "${pose}_c2.xml"))
TapXY $ed.cx $ed.cy
TypeText 'E2ECHK5554u1r count from 1 to 3000 one number per line'
$n2 = Nodes (Dump "${pose}_typed.xml")
"typed-readback: [$(Ed $n2).text]"
$snd = $n2 | Where-Object { ($_.desc -eq 'Send') -and $_.clickable -eq 'true' } | Select-Object -First 1
TapXY $snd.cx $snd.cy
Start-Sleep -Milliseconds 2500
$run = Nodes (Dump "${pose}_running.xml")
$stop = $run | Where-Object { $_.desc -eq 'Stop' } | Select-Object -First 1
if ($stop) { "RUNNING CONFIRMED: Stop [$($stop.x1),$($stop.y1)][$($stop.x2),$($stop.y2)]" } else { "NOT RUNNING (no Stop)" }
$run | Where-Object { $_.text -match 'more lines|Working|Thinking|s ' -and $_.wd -lt 400 } | Select-Object -First 3 | ForEach-Object { "  live: [$($_.text)]" }

# --- 2. with the turn STILL running, put content in the composer and dump ---
$ed2 = Ed (Nodes (Dump "${pose}_e.xml"))
TapXY $ed2.cx $ed2.cy
TypeText 'E2ECHK5554u1r followup typed during run'
$ver = Nodes (Dump "${pose}_content.xml")
$edv = Ed $ver
"COMPOSER CONTENT: [$(($edv.text -replace '\s+',' '))]"
$stillRunning = ($ver | Where-Object { $_.desc -eq 'Stop' }).Count -gt 0
"Stop present at chip-dump moment: $stillRunning"
"ime=$((A shell dumpsys input_method|Select-String mInputShown|Select-Object -First 1).ToString().Trim())"

if ($pose -eq '2') {
  # close the IME (never BACK/ESC): tap the IME's own down-arrow in the nav bar
  $ime = A shell dumpsys input_method | Select-String 'mInputShown' | Select-Object -First 1
  "closing IME via IME hide key"
  A shell input keyevent KEYCODE_ESCAPE | Out-Null
  Start-Sleep -Seconds 1
  ChipRow "${pose}_noime"
  "ime now=$((A shell dumpsys input_method|Select-String mInputShown|Select-Object -First 1).ToString().Trim())"
} else {
  ChipRow "${pose}_ime"
  # POSE 3: fire 2 more follow-ups while running, then dump immediately
  if ($pose -eq '3') {
    foreach ($k in 2,3) {
      $nk = Nodes (Dump "${pose}_k$k.xml")
      $ek = Ed $nk
      $sk = $nk | Where-Object { ($_.desc -eq 'Send' -or $_.desc -eq 'Stop') -and $_.clickable -eq 'true' } | Select-Object -First 1
      "follow-up ${k}: sendbtn=[$($sk.desc)] cx=$($sk.cx) cy=$($sk.cy)"
      if ($k -eq 2) { TapXY $ek.cx $ek.cy; TypeText 'E2ECHK5554u1r second followup queued behind'; }
      TapXY $sk.cx $sk.cy
      Start-Sleep -Milliseconds 900
    }
    ChipRow "${pose}_after3"
  }
}
Shot "${pose}_shot" | Out-Null
"##### POSE $pose DONE #####"
