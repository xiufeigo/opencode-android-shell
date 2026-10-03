. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

$f = DumpIt "87_hdr"
Write-Output "FOCUS=$(FocusNow)"
Write-Output "--- header row y 100..170, all nodes ---"
foreach ($n in (GetNodes $f)) { if ($n.Y1 -ge 100 -and $n.Y1 -le 170 -and $n.W -gt 4) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click) '$($n.Txt)'") } }
