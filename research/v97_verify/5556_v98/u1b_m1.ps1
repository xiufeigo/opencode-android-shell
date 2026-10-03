. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

# Assert IME is shown, then measure. Judge 1 (timeline) + Judge 2 (Steer/x + row heights)
$f = DumpIt "10_q11_measure"
$m = Measure $f "A: queue>=11, IME open"
ShotIt "10_q11_measure" | Out-Null

# dump full node list (compact) for the record
$ns = GetNodes $f
Write-Output "--- ALL NONEMPTY NODES (queue state) ---"
foreach ($n in $ns) { if ($n.Txt -and $n.W -gt 0) { Write-Output ("  [{0},{1}][{2},{3}] w={4} h={5} clk={6} scr={7} '{8}'" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.W,$n.H,$n.Click,$n.Scroll,$n.Txt) } }
Write-Output "--- SCROLLABLES (all) ---"
foreach ($n in ($ns | Where-Object { $_.Scroll -eq 'true' })) { Write-Output ("  [{0},{1}][{2},{3}] w={4} h={5} cls={6}" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.W,$n.H,$n.Cls) }
Write-Output "--- CLICKABLE NODES (all) ---"
foreach ($n in ($ns | Where-Object { $_.Click -eq 'true' })) { Write-Output ("  [{0},{1}][{2},{3}] w={4} h={5} cls={6} '{7}'" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.W,$n.H,$n.Cls,$n.Txt) }
