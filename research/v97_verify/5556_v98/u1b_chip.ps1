. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

$f = DumpIt "11_chipprobe"
Write-Output "IME=$(ImeState) IMETOP=$(ImeTopPx)"
Write-Output "--- composer tool row (y 240..300) ---"
foreach ($n in (GetNodes $f)) { if ($n.Y1 -ge 240 -and $n.Y1 -le 300 -and $n.W -gt 0) { Write-Output ("  [{0},{1}][{2},{3}] w={4} h={5} clk={6} '{7}'" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.W,$n.H,$n.Click,$n.Txt) } }
Write-Output "--- any node with 'Queue'/'Steer'/'follow' ---"
foreach ($n in (GetNodes $f)) { if ($n.Txt -and ($n.Txt -match 'Queue|Steer|ollow')) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] '$($n.Txt)'") } }
