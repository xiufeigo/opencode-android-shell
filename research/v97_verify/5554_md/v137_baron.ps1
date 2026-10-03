# v137 Task A1: 600-dimension, "queue bar on screen" form.
# Form: long turn running + IME on + composer has content => chip row shows 'Queue' => tap it.
# Only touches self-created content (prefix E2ECHK5554u1v).
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"

function Nsof { param([string]$t) return (Nodes (Dump $t)) }
function ETof { param($ns) return ($ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1) }
function TapTxt {
  param($ns, [string]$t)
  $n = $ns | Where-Object { $_.text -eq $t -or $_.desc -eq $t } | Select-Object -First 1
  if ($n) { Tap $n; return $true }
  return $false
}
function ClearComposer {
  adbx shell "input keyevent KEYCODE_MOVE_END" | Out-Null
  adbx shell 'i=0; while [ $i -lt 260 ]; do input keyevent 67; i=$((i+1)); done' | Out-Null
}
function TypeText { param([string]$s) adbx shell input text ($s -replace ' ','%s') | Out-Null }

"### step 1: make sure a long turn is RUNNING"
$ns = Nsof 'b0'
$stop = $ns | Where-Object { $_.text -eq 'Stop' } | Select-Object -First 1
if (-not $stop) {
  $et = ETof $ns
  "  editor found: [$($et.x1),$($et.y1)][$($et.x2),$($et.y2)]"
  Tap $et
  Start-Sleep -Milliseconds 600
  ClearComposer
  Start-Sleep -Milliseconds 500
  TypeText "E2ECHK5554u1v count from 1 to 30000, one number per line"
  Start-Sleep -Milliseconds 900
  $ns = Nsof 'b1'
  if (TapTxt $ns 'Send') { "  sent long turn" } else { "  WARN: no Send chip" }
  Start-Sleep -Seconds 4
  $ns = Nsof 'b2'
  if ($ns | Where-Object { $_.text -eq 'Stop' }) { "  running = YES (Stop present)" } else { "  WARN: still no Stop" }
} else { "  already running" }

"### step 2: composer content + tap the Queue chip"
$et = ETof $ns
Tap $et
Start-Sleep -Milliseconds 600
TypeText "E2ECHK5554u1v rowV first"
Start-Sleep -Milliseconds 1200
$ns = Nsof 'b3'
$q = $ns | Where-Object { $_.text -eq 'Queue' -or $_.desc -eq 'Queue' } | Select-Object -First 1
if ($q) {
  "  Queue chip at [$($q.x1),$($q.y1)][$($q.x2),$($q.y2)] h=$($q.h)"
  Tap $q
  Start-Sleep -Seconds 2
} else {
  "  WARN: no Queue chip; visible labels:"
  $ns | ForEach-Object { $l = if($_.text){$_.text}else{$_.desc}; if($l){"    '$l'" } } | Select-Object -Unique
}
Start-Sleep -Seconds 2
$ime = AssertIme 'bar-on-screen form'
"  mInputShown=$ime"
Shot 'v137_BARON' | Out-Null
Copy-Item "$script:ART\b3.xml" "$script:ART\v137_BARON.xml" -Force -ErrorAction SilentlyContinue
"### done"
