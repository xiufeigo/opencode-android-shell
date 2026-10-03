. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

# Make composer have content while turn is running -> Queue chip should appear
$e = Ctr (DumpIt "12_pre_type") 'Ask anything'
if ($e) { Ado "input tap $($e[0]) $($e[1])" | Out-Null; Start-Sleep -Milliseconds 500 }
TypeTxt "toggle queue"
Start-Sleep -Milliseconds 500
$f = DumpIt "13_chip_visible"
Write-Output "--- tool row y248..296 ---"
foreach ($n in (GetNodes $f)) { if ($n.Y1 -ge 246 -and $n.Y1 -le 298 -and $n.W -gt 0 -and $n.X1 -gt 1000) { Write-Output ("  [{0},{1}][{2},{3}] w={4} h={5} clk={6} '{7}'" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.W,$n.H,$n.Click,$n.Txt) } }

# tap the Queue chip
if (TapOn $f 'Queue') { Write-Output "CHIP_TAPPED" } else { Write-Output "CHIP_NOT_FOUND" }
Start-Sleep -Seconds 2
$f2 = DumpIt "14_chip_toggled"
Write-Output "--- after toggle, tool row ---"
foreach ($n in (GetNodes $f2)) { if ($n.Y1 -ge 246 -and $n.Y1 -le 298 -and $n.W -gt 0 -and $n.X1 -gt 1000) { Write-Output ("  [{0},{1}][{2},{3}] w={4} h={5} clk={6} '{7}'" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.W,$n.H,$n.Click,$n.Txt) } }
Write-Output "IME=$(ImeState) IMETOP=$(ImeTopPx)"
ShotIt "14_chip_toggled" | Out-Null
