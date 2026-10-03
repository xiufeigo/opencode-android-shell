. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

# chip coords from live dump u1b_34
$f = DumpIt "35_pre_toggle"
$chip = Ctr $f 'Queue'
if (-not $chip) { Write-Output "NO_CHIP"; exit 1 }
Write-Output "TOGGLE_CHIP at $($chip -join ',')"
Ado "input tap $($chip[0]) $($chip[1])" | Out-Null
Start-Sleep -Seconds 2

# resolve Send coords once (layout is stable while turn runs)
$f2 = DumpIt "36_after_toggle"
$send = Ctr $f2 'Send'
$edit = Ctr $f2 'Ask anything'
Write-Output "SEND=$($send -join ',') EDIT=$($edit -join ',') IME=$(ImeState)"
if (-not $send) { Write-Output "NO_SEND_ABORT"; exit 1 }

# rapid-fire: keep composer focused, type + tap send, NO dumps in between
if ($edit) { Ado "input tap $($edit[0]) $($edit[1])" | Out-Null; Start-Sleep -Milliseconds 300 }
for ($i = 1; $i -le 13; $i++) {
    TypeTxt "Q$i u1b"
    Start-Sleep -Milliseconds 120
    Ado "input tap $($send[0]) $($send[1])" | Out-Null
    Start-Sleep -Milliseconds 260
}
Write-Output "FIRED_13 IME=$(ImeState) IMETOP=$(ImeTopPx)"

$v = DumpIt "37_qdepth"
$ns = GetNodes $v
Write-Output "NODES=$($ns.Count)"
Write-Output "--- scrollables ---"
foreach ($n in ($ns | Where-Object { $_.Scroll -eq 'true' })) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) cls=$($n.Cls)") }
Write-Output "--- Steer/Remove/Queued ---"
foreach ($n in $ns) { if ($n.Txt -and ($n.Txt -match 'Steer|Remove|Queued')) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click) '$($n.Txt)'") } }
ShotIt "37_qdepth" | Out-Null
