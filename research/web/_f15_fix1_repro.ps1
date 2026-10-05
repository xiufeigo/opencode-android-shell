# F15 item1: minimal, device-free repro of the F13 VERDICT `release` bug.
# Bug: L() ends with Write-Output -> its log lines enter the PS pipeline ->
#      any function calling L() returns an ARRAY instead of 'true'/'false' ->
#      `$seg2 -eq 'false'` is False even when the flag really is false.
$ErrorActionPreference='Stop'

function L_BEFORE($m){ $s="[12:00:00.000] $m"; Write-Output $s }   # <-- F13 original
function L_AFTER ($m){ $s="[12:00:00.000] $m"; Write-Host $s }      # <-- fixed

# Faithful reproduction of F13 KeepState's structure: logs, then `return $fs`
function KeepState_BEFORE($label){
  $fs='false'
  L_BEFORE "${label}_WINDOWFLAGRAW: fl=1234 KEEP_SCREEN_ON"
  L_BEFORE "${label}_WINDOW_KEEP_SCREEN_ON=$fs"
  L_BEFORE "${label}_POWER_mWakefulness=Awake mHoldingDisplaySuspendBlocker=false"
  return $fs
}
function KeepState_AFTER($label){
  $fs='false'
  L_AFTER "${label}_WINDOWFLAGRAW: fl=1234 KEEP_SCREEN_ON"
  L_AFTER "${label}_WINDOW_KEEP_SCREEN_ON=$fs"
  L_AFTER "${label}_POWER_mWakefulness=Awake mHoldingDisplaySuspendBlocker=false"
  return $fs
}

"===== BEFORE (F13 original, Write-Output) ====="
$seg2_before = KeepState_BEFORE "SEG2"
"  seg2 raw type      : $($seg2_before.GetType().FullName)"
"  seg2 element count : $(@($seg2_before).Count)"
"  seg2 joined        : $(@($seg2_before) -join ' ~ ')"
"  seg2 -eq 'false'   : $($seg2_before -eq 'false')     <-- VERDICT release field"
"  VERDICT_before     : raised=true release=$($seg2_before -eq 'false') plus90=false"

""
"===== AFTER (fixed, Write-Host) ====="
$seg2_after = KeepState_AFTER "SEG2"
"  seg2 raw type      : $($seg2_after.GetType().FullName)"
"  seg2 element count : $(@($seg2_after).Count)"
"  seg2 joined        : $(@($seg2_after) -join ' ~ ')"
"  seg2 -eq 'false'   : $($seg2_after -eq 'false')     <-- VERDICT release field"
"  VERDICT_after      : raised=true release=$($seg2_after -eq 'false') plus90=false"

""
"===== SANITY: the flag is false in BOTH runs; only the comparison differs ====="
"  ground truth flag value = 'false'"
"  BEFORE release = $($seg2_before -eq 'false')  (WRONG: reports not-released while it is released)"
"  AFTER  release = $($seg2_after  -eq 'false')  (RIGHT)"
