# F14 step 7: F12b - cancel semantics.
#   (1) Reconnect -> passphrase card must RE-POP
#   (2) Cancel it -> 7 timed samples must show NO re-pop and the ENTRY must survive
#   (3) second Reconnect -> card must re-pop again (cancel is not sticky/latching)
. "$PSScriptRoot\f14lib.ps1"

function Snap([string]$tag) {
    $x = Dump $tag
    $texts = (Texts $x) | Where-Object { $_.Y1 -gt 1000 } | ForEach-Object { $_.Text }
    $rowState = ($texts | Where-Object { $_ -in @('Connected', 'Connecting over SSH', 'Disconnected', 'Failed') }) -join '|'
    $hasCard = [bool]($texts | Where-Object { $_ -like 'Passphrase for*' -or $_ -eq 'SSH authentication' })
    $hasHostCard = [bool]($texts | Where-Object { $_ -eq 'Verify host identity' })
    return [pscustomobject]@{ Tag = $tag; Row = $rowState; Card = $hasCard; HostCard = $hasHostCard; All = $texts }
}

function FindBtn([string]$xml, [string]$label) {
    (Texts $xml) | Where-Object { $_.Text -eq $label } | Select-Object -First 1
}

$x0 = Dump 'F12b_00_connected'
Write-Host '=== state 0 (entry is Connected) ==='
$s = Snap 'F12b_00b'
Write-Host "  row='$($s.Row)' card=$($s.Card)"

# ---- (1) first Reconnect must re-pop the card -------------------------
Write-Host ''
Write-Host '=== F12b-1: first Reconnect must re-pop the card ==='
$rec = FindBtn $x0 'Reconnect'
if (-not $rec) { Write-Host 'FAIL: no Reconnect button'; exit 1 }
Write-Host "tapping 'Reconnect' @($($rec.CX),$($rec.CY))"
TapXY $rec.CX $rec.CY
Start-Sleep -Seconds 3
$s1 = Snap 'F12b_01_card'
Write-Host "  card re-popped = $($s1.Card)   row='$($s1.Row)'"
if (-not $s1.Card) {
    Write-Host '  card did not re-pop; card-ish texts:'
    $s1.All | ForEach-Object { Write-Host "    '$_'" }
    exit 1
}

# ---- (2) Cancel, then 7 timed samples --------------------------------
Write-Host ''
Write-Host '=== F12b-2: Cancel then 7 timed samples ==='
$cx = Dump 'F12b_02_preCancel'
$cancel = FindBtn $cx 'Cancel'
if (-not $cancel) { Write-Host 'FAIL: no Cancel on card'; exit 1 }
Write-Host "tapping 'Cancel' @($($cancel.CX),$($cancel.CY))"
TapXY $cancel.CX $cancel.CY

$repop = 0
for ($i = 1; $i -le 7; $i++) {
    Start-Sleep -Seconds 5
    $si = Snap ("F12b_03_sample$i")
    $popped = $si.Card -or $si.HostCard
    if ($popped) { $repop++ }
    Write-Host ("  sample {0} (+{1}s) row='{2}' card={3} hostcard={4}" -f $i, ($i * 5), $si.Row, $si.Card, $si.HostCard)
}
Write-Host ''
Write-Host "F12b RESULT: re-pop after cancel = $repop / 7  (expect 0)"

# ---- (3) second Reconnect must re-pop again --------------------------
Write-Host ''
Write-Host '=== F12b-3: second Reconnect must re-pop the card ==='
$x4 = Dump 'F12b_04_preReconnect2'
Write-Host '  row texts now:'
(Snap 'F12b_04b').All | ForEach-Object { Write-Host "    '$_'" }
$rec2 = FindBtn $x4 'Reconnect'
if (-not $rec2) { Write-Host '  (no Reconnect button present)'; }
else {
    Write-Host "tapping 'Reconnect' again @($($rec2.CX),$($rec2.CY))"
    TapXY $rec2.CX $rec2.CY
    Start-Sleep -Seconds 3
    $s2 = Snap 'F12b_05_card2'
    Write-Host "  card re-popped 2nd time = $($s2.Card)"
}
