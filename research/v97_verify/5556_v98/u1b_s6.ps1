. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

# Chip is toggled to queue mode. Send N messages fast, minimal dumps.
$e = Ctr (DumpIt "20_pre") 'Ask anything'
if ($e) { Ado "input tap $($e[0]) $($e[1])" | Out-Null; Start-Sleep -Milliseconds 300 }

for ($i = 1; $i -le 13; $i++) {
    TypeTxt "Q$i u1b"
    Start-Sleep -Milliseconds 150
    $f = DumpIt "20_t$i"
    $s = Ctr $f 'Send'
    if ($s) { Ado "input tap $($s[0]) $($s[1])" | Out-Null }
    else { Write-Output "SENDFAIL $i" }
    Start-Sleep -Milliseconds 400
}
Write-Output "SENT_13 IME=$(ImeState) IMETOP=$(ImeTopPx)"
$v = DumpIt "21_qstate"
$ns = GetNodes $v
Write-Output "--- all scrollables ---"
foreach ($n in ($ns | Where-Object { $_.Scroll -eq 'true' })) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) cls=$($n.Cls)") }
Write-Output "--- Steer/Remove/Queued nodes ---"
foreach ($n in $ns) { if ($n.Txt -and ($n.Txt -match 'Steer|Remove|Queued')) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click) '$($n.Txt)'") } }
Write-Output "--- tool row ---"
foreach ($n in $ns) { if ($n.Y1 -ge 240 -and $n.Y1 -le 300 -and $n.W -gt 0 -and $n.X1 -gt 900) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click) '$($n.Txt)'") } }
ShotIt "21_qstate" | Out-Null
