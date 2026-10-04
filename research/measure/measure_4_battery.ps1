# measure_4_battery.ps1 — power baseline over a FIXED 5-minute scripted run.
# Usage: pwsh -File research\measure\measure_4_battery.ps1
#
# Protocol (repeatable):
#   1. screen on, airplane-ish idle settle, app cold-started
#   2. dumpsys batterystats --reset
#   3. run SCRIPT-A in a loop until >= 300 s of wall time have elapsed
#   4. dumpsys batterystats <pkg>  -> mAh, per-rail, wakelocks, network
#
# NOTE: on an emulator there is no real battery; the mAh figure is derived
# from the emulated power HAL's uA/ms model, so it is a *relative* baseline
# only meaningful when compared against another run on the SAME emulator image.

. (Join-Path $PSScriptRoot 'common.ps1')

$WINDOW_SEC = 300

Write-Output "### settle (wake screen, dismiss keyguard)"
A shell input keyevent KEYCODE_WAKEUP | Out-Null
A shell wm dismiss-keyguard 2>&1 | Out-Null
A shell am force-stop $Pkg
Start-Sleep -Seconds 2
A shell am start -n $Act | Out-Null
Start-Sleep -Seconds 3

Write-Output "### dumpsys batterystats --reset"
A shell dumpsys batterystats --reset | Out-Null
Start-Sleep -Seconds 2

Write-Output "### start fixed ${WINDOW_SEC}s window ..."
$sw = [Diagnostics.Stopwatch]::StartNew()
$rounds = 0
$log = @()
while ($sw.Elapsed.TotalSeconds -lt $WINDOW_SEC) {
  $rounds++
  $r0 = $sw.Elapsed.TotalSeconds
  ScriptA
  $r1 = $sw.Elapsed.TotalSeconds
  $log += [pscustomobject]@{ round=$rounds; start_s=[math]::Round($r0,1); end_s=[math]::Round($r1,1) }
  Write-Output ("  round {0}: {1:N1}s -> {2:N1}s" -f $rounds, $r0, $r1)
}
$sw.Stop()
$actual = [math]::Round($sw.Elapsed.TotalSeconds, 1)
Write-Output "### window actual = ${actual}s over $rounds rounds"
$log | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $OutDir 'battery_rounds.json')

# stop the app so the drain stat is attributable, then read
A shell am force-stop $Pkg
Start-Sleep -Seconds 3

$bs = A shell dumpsys batterystats $Pkg
$bs | Out-File -Encoding utf8 (Join-Path $OutDir 'batterystats_full.txt')
$t = $bs -join "`n"
$t | Out-File -Encoding utf8 (Join-Path $OutDir 'batterystats_full_join.txt')

Write-Output "### ----- parsed batterystats -----"
function Show($label, $pattern) {
  if ($t -match "(?m)^\s*" + [regex]::Escape($label) + ":\s*(.+)$") { Write-Output ("{0,-40} = {1}" -f $label, $Matches[1].Trim()) }
  else { Write-Output ("{0,-40} = <not present>" -f $label) }
}
Show 'Realtime power consumption'
Show 'Statistics since last charge'
Show 'Capacity'
Show 'Discharge'
Show 'mAh'
Show 'Capacity percent'
Show 'Time on battery'

Write-Output "### ----- power rails (uA/ms) -----"
[regex]::Matches($t, '(?m)^\s*([\w\-\.]+):\s*(\d+)uAms:?\s*(.*)$') | ForEach-Object { Write-Output ("  {0,-22} {1,12} uAms  {2}" -f $_.Groups[1].Value, $_.Groups[2].Value, $_.Groups[3].Value) }

Write-Output "### ----- wakelocks -----"
if ($t -match '(?ms)#?\s*Wake lock.*?\n(.*?)\n\s*\n') { Write-Output $Matches[1] }
$wl = [regex]::Matches($t, '(?m)^\s*(?:\*\*)?([\w\.\-]+(?: wakelock| Wake Locks)?)[:\s]+(\d+)ms') 
if ($wl.Count) { $wl | ForEach-Object { Write-Output ("  {0,-40} {1} ms" -f $_.Groups[1].Value, $_.Groups[2].Value) } }

Write-Output "### ----- network -----"
if ($t -match '(?m)Network:.*') { Write-Output $Matches[0] }
[regex]::Matches($t, '(?m)^\s*(\w+):\s*(\d+) packets') | ForEach-Object { Write-Output ("  pkt {0} = {1}" -f $_.Groups[1].Value, $_.Groups[2].Value) }

Write-Output "### DONE. raw dump: research/measure/out/batterystats_full.txt"
