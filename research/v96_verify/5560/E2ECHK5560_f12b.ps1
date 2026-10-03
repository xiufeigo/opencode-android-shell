# E2ECHK5560_f12b.ps1 - F12b Cancel regression on a row-level Reconnect.
#
# Checks, on ONE row: Reconnect renders the card inline in the Servers panel; Cancel
# makes the card go away and KEEPS the row; a second Reconnect (new promptId) re-offers
# the card. High-frequency sampling right after Cancel is the whole point - a single
# end-state screenshot cannot tell "cancelled" from "never re-offered".
param(
    [Parameter(Mandatory = $true)][string]$Title,
    [string]$Tag = 'B',
    # Reconnect -> card latency is not fixed: the server issues a fresh kbdint
    # challenge and the app only renders the card once it arrives. 3s was too
    # short on this build and produced a false "no card after Reconnect".
    [int]$CardWaitSec = 8
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5560_run.ps1" -Dev emulator-5560

function Grab { Nodes 'E2ECHK5560_nav_tmp' }
function B($line) {
    if ($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]
        $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
        return ,@([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2))
    }
    return $null
}
function Has($n, [string]$p) { [bool]($n | Select-String -Pattern $p -Quiet) }

# settle: cancel any card already on screen
for ($i = 0; $i -lt 6; $i++) {
    $n = Grab
    if (-not (Has $n 'SSH authentication')) { break }
    $c = $n | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
    if (-not $c) { break }
    $p = B $c.Line
    Write-Host "  settling: cancel card @ $($p[0]),$($p[1])"
    A shell "input tap $($p[0]) $($p[1])" | Out-Null
    Start-Sleep -Seconds 2
}

# --- 1st Reconnect ---
ResetFaillock | Out-Null
$n = Grab
$rows = $n | Select-String -Pattern "^$Title`t" | Select-Object -First 1
if (-not $rows) { Write-Host "ABORT: row '$Title' not on screen"; exit 2 }
$ty = 0
if ($rows.Line -match '\[(\d+),(\d+)\]') { $ty = [int]$Matches[2] }
$rc = $n | Select-String -Pattern '^Reconnect\t' |
    Where-Object { $_ -match '\[(\d+),(\d+)\]' -and [int]$Matches[2] -gt $ty -and [int]$Matches[2] -lt ($ty + 300) } |
    Select-Object -First 1
if (-not $rc) { Write-Host 'ABORT: Reconnect button not found'; exit 2 }
$p = B $rc.Line
Write-Host "RECONNECT#1 @ $($p[0]),$($p[1])  (row title y=$ty)"
A shell "input tap $($p[0]) $($p[1])" | Out-Null
Start-Sleep -Seconds $CardWaitSec
Shot "E2ECHK5560_${Tag}_10_card1"
$n = Grab
Write-Host "card1 present = $(Has $n 'SSH authentication')"
($n | Select-String -Pattern 'SSH authentication|Password:|^Cancel\t|^Continue\t|Enter a host|failed|Connected') |
    Select-Object -First 8 | ForEach-Object { Write-Host "   $($_.Line)" }
if (-not (Has $n 'SSH authentication')) { Write-Host 'ABORT: no card after Reconnect'; exit 3 }

# --- Cancel + high-frequency sampling ---
$c = $n | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
$p = B $c.Line
Write-Host "CANCEL @ $($p[0]),$($p[1])"
A shell "input tap $($p[0]) $($p[1])" | Out-Null
foreach ($ms in @(150, 300, 600, 1000, 2000, 3000, 5000)) {
    Start-Sleep -Milliseconds $ms
    $n = Grab
    $card = Has $n 'SSH authentication'
    $row = Has $n "^$Title`t"
    Write-Output ("  t+{0,-5} card={1,-5} row_kept={2}" -f $ms, $card, $row)
}
Shot "E2ECHK5560_${Tag}_11_after_cancel"

# --- 2nd Reconnect: a new promptId must re-offer the card ---
Start-Sleep -Seconds 2
$n = Grab
$rows = $n | Select-String -Pattern "^$Title`t" | Select-Object -First 1
$ty = 0
if ($rows -and $rows.Line -match '\[(\d+),(\d+)\]') { $ty = [int]$Matches[2] }
$rc = $n | Select-String -Pattern '^Reconnect\t' |
    Where-Object { $_ -match '\[(\d+),(\d+)\]' -and [int]$Matches[2] -gt $ty -and [int]$Matches[2] -lt ($ty + 300) } |
    Select-Object -First 1
if (-not $rc) { Write-Host 'ABORT: Reconnect#2 not found'; exit 2 }
$p = B $rc.Line
Write-Host "RECONNECT#2 @ $($p[0]),$($p[1])"
A shell "input tap $($p[0]) $($p[1])" | Out-Null
Start-Sleep -Seconds $CardWaitSec
Shot "E2ECHK5560_${Tag}_12_card2"
$n = Grab
Write-Output "card2 present = $(Has $n 'SSH authentication')"
($n | Select-String -Pattern 'SSH authentication|Password:|^Cancel\t|^Continue\t|Enter a host|failed') |
    Select-Object -First 8 | ForEach-Object { Write-Host "   $($_.Line)" }
