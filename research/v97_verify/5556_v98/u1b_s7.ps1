. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'
$f = DumpIt "22_qbar_probe"
$ns = GetNodes $f
Write-Output "IME=$(ImeState) IMETOP=$(ImeTopPx) NODES=$($ns.Count)"
Write-Output "--- nodes containing 'u1b' (my queue payloads) ---"
foreach ($n in $ns) { if ($n.Txt -and $n.Txt -match 'u1b|Steer|Remove|Queue|Send|Steering') { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click) scr=$($n.Scroll) '$($n.Txt)'") } }
Write-Output "--- ALL nodes y in 160..210 (where queue bar would sit) ---"
foreach ($n in $ns) { if ($n.Y1 -ge 160 -and $n.Y1 -le 212 -and $n.W -gt 4) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click) scr=$($n.Scroll) cls=$($n.Cls) '$($n.Txt)'") } }
Write-Output "--- ALL nodes y in 206..302 (composer/queue region) ---"
foreach ($n in $ns) { if ($n.Y1 -ge 206 -and $n.Y1 -le 302 -and $n.W -gt 4 -and $n.X1 -lt 1120) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click) scr=$($n.Scroll) cls=$($n.Cls) '$($n.Txt)'") } }
Write-Output "--- queue bar header/chrome candidates (short texts) ---"
foreach ($n in $ns) { if ($n.Txt -and $n.Txt.Length -le 22 -and $n.W -gt 4 -and $n.Y1 -gt 160 -and $n.Y1 -lt 320) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) '$($n.Txt)'") } }
ShotIt "22_qbar_probe" | Out-Null
