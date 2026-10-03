# E2ECHK5560_answer.ps1 - tight, single-shot auth answer.
#
# WHY TIGHT: the SSH kbdint auth session has a server-side timeout. Doing
# "card -> focus -> type -> Continue" as separate slow tool calls (screenshot + full
# uiautomator dump between each) lets the session expire; the app then re-offers the card
# with the STORED credential, and a correctly typed password still lands as
# "Could not establish the SSH connection". The predecessor flagged this too.
#
# Everything between "card is up" and "Continue tapped" runs in one uninterrupted block
# with no screencap / uiautomator in between.
param(
    [string]$Secret = 'opencode',
    [int]$TapX = 408,          # row-level Reconnect
    [int]$TapY = 1434,
    [string]$Tag = 'T2'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5560_run.ps1" -Dev emulator-5560

ResetFaillock | Out-Null
Write-Host 'faillock reset'

# make sure we start from a settled row (no card on screen)
for ($i = 0; $i -lt 8; $i++) {
    Ui "E2ECHK5560_${Tag}_pre" | Out-Null
    $x = Join-Path $global:DIR "ui_E2ECHK5560_${Tag}_pre.xml"
    $hasCard = Select-String -Path $x -Pattern 'SSH authentication' -Quiet
    if (-not $hasCard) { break }
    $c = Select-String -Path $x -Pattern 'text="Cancel"' | Select-Object -First 1
    A shell "input tap 643 1762" | Out-Null
    Start-Sleep -Seconds 2
    Write-Host "  settling: card still up, cancelling again ($($i + 1))"
}
Ui "E2ECHK5560_${Tag}_pre" | Out-Null
$x = Join-Path $global:DIR "ui_E2ECHK5560_${Tag}_pre.xml"
$rc = Select-String -Path $x -Pattern 'text="Reconnect"' | Select-Object -First 1
if ($rc -and $rc.Line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    $TapX = [int](($Matches[1] + $Matches[3]) / 2)
    $TapY = [int](($Matches[2] + $Matches[4]) / 2)
}
Write-Host "Reconnect @ $TapX,$TapY"

A shell "input tap $TapX $TapY" | Out-Null
Start-Sleep -Milliseconds 2600      # card appears

# ---- TIGHT BLOCK: no dumps, no screenshots ----
A shell "input tap 540 1641" | Out-Null   # focus password field -> IME up, card shifts
Start-Sleep -Milliseconds 800
$words = @($Secret -split ' ' | Where-Object { $_ -ne '' })
for ($w = 0; $w -lt $words.Count; $w++) {
    $word = $words[$w]
    for ($i = 0; $i -lt $word.Length; $i += 8) {
        $seg = $word.Substring($i, [Math]::Min(8, $word.Length - $i))
        A shell "input text '$seg'" | Out-Null
        Start-Sleep -Milliseconds 900
    }
    if ($w -lt $words.Count - 1) { A shell "input keyevent 62" | Out-Null; Start-Sleep -Milliseconds 300 }
}
A shell "input tap 866 1347" | Out-Null   # Continue, keyboard-up position
# ---- END TIGHT BLOCK ----
Start-Sleep -Milliseconds 2500

Shot "E2ECHK5560_${Tag}_result"
Nodes "E2ECHK5560_${Tag}_result" | Select-String -Pattern 'E2EC5560C|Connected|Connect|Reconnect|Disconnect|SSH authentication|Could not|left running|Healthy|healthy'
