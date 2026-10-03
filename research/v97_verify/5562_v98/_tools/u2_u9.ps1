. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\_tools\u2_lib.ps1"

"=== U9 PRE: full-window Changes baseline (1366x768) ==="
& $adb -s $S shell wm size
& $adb -s $S shell wm density
Dump "u9_00_base" | Out-Null
"--- candidates ---"
Find "u9_00_base" "Toggle review panel"

$rv = Find "u9_00_base" "Toggle review panel" | Select-Object -First 1
if($rv -match 'c=(\d+),(\d+)'){ $rvx=$Matches[1]; $rvy=$Matches[2] }
else { "no review toggle"; exit 1 }
"tapping review panel at $rvx,$rvy"
& $adb -s $S shell input tap $rvx $rvy
Start-Sleep -Seconds 3
Dump "u9_01_panel" | Out-Null
"--- panel nodes ---"
Find "u9_01_panel" "Changes|Context|Files|Refresh|Relative to|uncommitted|timed out"

$ch = Find "u9_01_panel" "^Changes$" | Select-Object -First 1
if($ch -match 'c=(\d+),(\d+)'){ $chx=$Matches[1]; $chy=$Matches[2] } else { "no Changes tab"; exit 1 }
"tapping Changes at $chx,$chy"
& $adb -s $S shell input tap $chx $chy

"=== T+0 baseline sample ==="
Dump "u9_02_t0" | Out-Null
Find "u9_02_t0" "Changes|Context|Files|Refresh|Relative to|uncommitted|timed out|Loading|insertions"

"=== now override wm size 560x768 / density 160 ==="
& $adb -s $S shell wm size 560x768
& $adb -s $S shell wm density 160
Start-Sleep -Seconds 4
& $adb -s $S shell wm size
& $adb -s $S shell wm density
Dump "u9_03_narrow" | Out-Null
Shot "u9_03_narrow"
"--- narrow panel nodes ---"
Find "u9_03_narrow" "Changes|Context|Files|Refresh|Relative to|uncommitted|timed out|Loading"

"=== tapping Refresh in narrow window, then sampling ==="
$rf = Find "u9_03_narrow" "Refresh" | Select-Object -First 1
if($rf -match 'c=(\d+),(\d+)'){ $rfx=$Matches[1]; $rfy=$Matches[2]; "tapping Refresh at $rfx,$rfy"; & $adb -s $S shell input tap $rfx $rfy }
else { "no Refresh found in narrow window" }

$sw=[Diagnostics.Stopwatch]::StartNew()
foreach($t in 1,3,10,20){
  while($sw.Elapsed.TotalSeconds -lt $t){ Start-Sleep -Milliseconds 200 }
  if(Dump ("u9_04_t{0}" -f $t)){
    $all = Find ("u9_04_t{0}" -f $t) "No uncommitted changes yet|uncommitted|timed out|Tap Refresh|Loading|reading|Relative to|\+[0-9]+|-[0-9]+"
    $fake = Select-String -Path "$d\u9_04_t$t.xml" -Pattern "No uncommitted changes yet" -Quiet
    "T+${t}s  fakeEmpty=$fake  nodes: $all"
  } else { "T+${t}s DUMP-FAIL" }
}
Start-Sleep -Seconds 3
while($sw.Elapsed.TotalSeconds -lt 32){ Start-Sleep -Milliseconds 300 }
if(Dump "u9_05_t32"){
  $fake = Select-String -Path "$d\u9_05_t32.xml" -Pattern "No uncommitted changes yet" -Quiet
  "T+32s fakeEmpty=$fake  nodes: $(Find 'u9_05_t32' 'uncommitted|timed out|Tap Refresh|Loading|Relative to')"
} else { "T+32s DUMP-FAIL" }
Shot "u9_06_t32"

"=== RESET wm ==="
& $adb -s $S shell wm size reset
& $adb -s $S shell wm density reset
Start-Sleep -Seconds 3
& $adb -s $S shell wm size
& $adb -s $S shell wm density
"=== U9 DONE ==="
