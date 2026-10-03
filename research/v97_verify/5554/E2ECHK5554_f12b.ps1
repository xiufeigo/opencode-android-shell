# E2ECHK5554_f12b.ps1 - F12b regression on the row-level prompt exit:
#   1) Reconnect -> prompt card appears (panel-rendered, same slot)
#   2) Cancel -> card must NOT re-appear for at least 5 s (sampled 7 points),
#      the row must stay intact
#   3) a SECOND Reconnect MUST re-appear the card (new promptId)
#
# Run this on the F13b entry (password auth) or any row that triggers a prompt.
# Every Reconnect is preceded by ResetFaillock (wrong password burns faillock).
#
# ASSERTIONS
#   A1 card1 present = True
#   A2 after Cancel: 7/7 samples show card=False AND row kept
#   A3 card2 present = True (second Reconnect; expect the "account is locked"
#      wording if faillock burned - that is EXPECTED and recorded, not a bug)
param(
    [string]$Dev = 'emulator-5554',
    [string]$RowName = 'E2E5554C1'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5554_run.ps1" -Dev $Dev
AssertDevice

function RowY($n) {
    $r = $n | Select-String -Pattern ([regex]::Escape($RowName)) | Select-Object -First 1
    if (-not $r) { return $null }
    if ($r.Line -match '\[(\d+),(\d+)\]') { return [int]$Matches[2] }
    return $null
}
function ResolveReconnect($n) {
    $r = $n | Select-String -Pattern '^Reconnect\t' | Select-Object -First 1
    if (-not $r) { return $null }
    return CenterOf $r.Line
}
function CardPresent($n) { return (Has $n 'SSH authentication') }
function RowKept($n) { return (Has $n ([regex]::Escape($RowName))) }

# make sure we are on the Servers panel with a settled row (no card up)
GotoServers
$settled = $false
for ($i = 0; $i -lt 8; $i++) {
    $n = Grab
    if (-not (CardPresent $n)) { $settled = $true; break }
    $can = $n | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
    if (-not $can) { break }
    $p = CenterOf $can.Line
    Tap $p[0] $p[1]
    Start-Sleep -Seconds 2
}
if (-not $settled) { Shot 'E2ECHK5554_F12B_00_settle_fail'; throw 'could not settle row (card keeps re-appearing)' }

# ---- 1) first Reconnect -> card1
ResetFaillock | Out-Null
$n = Grab
$p = ResolveReconnect $n
if (-not $p) { Shot 'E2ECHK5554_F12B_00_no_rc'; throw 'no Reconnect affordance' }
Write-Host "RECONNECT#1 @ $($p[0]),$($p[1])"
Tap $p[0] $p[1]
Start-Sleep -Milliseconds 3500
$n = Grab
Shot 'E2ECHK5554_F12B_10_card1'
$a1 = CardPresent $n
Write-Host "ASSERT A1 card1 present   : $a1   (expect True)"
($n | Select-String -Pattern 'SSH authentication|Password:|Passphrase|Cancel|Continue') |
    Select-Object -First 8 | ForEach-Object { Write-Host "  $($_.Line)" }

# ---- 2) Cancel + 7-point sampling (150/300/600/1000/2000/3000/5000 ms)
$can = $n | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
if (-not $can) { throw 'card1 up but Cancel not found' }
$cp = CenterOf $can.Line
Write-Host "CANCEL @ $($cp[0]),$($cp[1])"
Tap $cp[0] $cp[1]
$delays = @(150, 300, 600, 1000, 2000, 3000, 5000)
$ok2 = $true
for ($i = 0; $i -lt $delays.Count; $i++) {
    Start-Sleep -Milliseconds $delays[$i]
    $n = Grab
    $card = CardPresent $n
    $kept = RowKept $n
    Write-Output ("  t+{0,-5} card={1} row_kept={2}  (ui_E2ECHK5554_F12B_2x_{3}.xml)" -f $delays[$i], $card, $kept, $i)
    Ui "E2ECHK5554_F12B_2x_$i"
    if ($card -or -not $kept) { $ok2 = $false }
}
$a2 = $ok2
Write-Host "ASSERT A2 no re-pop + row kept (7/7): $a2   (expect True)"
Shot 'E2ECHK5554_F12B_30_after_cancel'

# ---- 3) second Reconnect -> card2 MUST re-appear
ResetFaillock | Out-Null
$n = Grab
$p = ResolveReconnect $n
if (-not $p) { throw 'no Reconnect affordance for round 2' }
Write-Host "RECONNECT#2 @ $($p[0]),$($p[1])"
Tap $p[0] $p[1]
Start-Sleep -Milliseconds 3500
$n = Grab
Shot 'E2ECHK5554_F12B_40_card2'
$a3 = CardPresent $n
Write-Host "ASSERT A3 card2 present   : $a3   (expect True)"
($n | Select-String -Pattern 'SSH authentication|locked|Password:|Cancel|Continue') |
    Select-Object -First 8 | ForEach-Object { Write-Host "  $($_.Line)" }

# leave a clean state: cancel card2
$can = $n | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
if ($can) { $cp = CenterOf $can.Line; Tap $cp[0] $cp[1] }
ResetFaillock | Out-Null

Write-Host '=== F12b VERDICT ==='
if ($a1 -and $a2 -and $a3) { Write-Host 'F12b PASS' } else { Write-Host 'F12b FAIL - see F12B_* evidence' }
