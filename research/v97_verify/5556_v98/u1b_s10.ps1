. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'
$f = DumpIt "43_after_toggle_full"
$ns = GetNodes $f
Write-Output "IME=$(ImeState) IMETOP=$(ImeTopPx) NODES=$($ns.Count)"
Write-Output "--- ALL clickable + scrollable + text nodes y 150..330 ---"
foreach ($n in $ns) {
    if ($n.Y1 -ge 150 -and $n.Y1 -le 330 -and ($n.W -gt 4)) {
        if ($n.Txt -or $n.Click -eq 'true' -or $n.Scroll -eq 'true') {
            Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click) scr=$($n.Scroll) '$($n.Txt)'")
        }
    }
}
ShotIt "43_after_toggle_full" | Out-Null
