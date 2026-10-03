# E2ECHK5560_answer2.ps1 - tight answer + rapid post-Continue sampling.
# Captures the row status every ~350ms so a transient second prompt / Connected state
# is not missed (uiautomator dump costs ~0.8s, so sampling is coarse but continuous).
param(
    [string]$Secret = 'opencode',
    [string]$Tag = 'T2b'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5560_run.ps1" -Dev emulator-5560

ResetFaillock | Out-Null
Write-Host 'faillock reset'

# settle: cancel any card on screen
for ($i = 0; $i -lt 8; $i++) {
    Ui "E2ECHK5560_${Tag}_pre" | Out-Null
    $x = Join-Path $global:DIR "ui_E2ECHK5560_${Tag}_pre.xml"
    if (-not (Select-String -Path $x -Pattern 'SSH authentication' -Quiet)) { break }
    A shell "input tap 643 1762" | Out-Null
    Start-Sleep -Seconds 2
}
# find the Reconnect TextView by its exact bounds (x1=323 in this build)
$rx = 408; $ry = 1434
Ui "E2ECHK5560_${Tag}_pre" | Out-Null
$xml = [xml](Get-Content (Join-Path $global:DIR "ui_E2ECHK5560_${Tag}_pre.xml") -Raw)
foreach ($n in $xml.SelectNodes('//node')) {
    if ($n.GetAttribute('text') -eq 'Reconnect' -and $n.GetAttribute('bounds') -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $rx = [int](($Matches[1] + $Matches[3]) / 2); $ry = [int](($Matches[2] + $Matches[4]) / 2)
    }
}
Write-Host "Reconnect @ $rx,$ry"
A shell "input tap $rx $ry" | Out-Null
Start-Sleep -Milliseconds 2600

# ---- TIGHT BLOCK ----
A shell "input tap 540 1641" | Out-Null
Start-Sleep -Milliseconds 800
foreach ($seg in @($Secret)) { A shell "input text '$seg'" | Out-Null; Start-Sleep -Milliseconds 900 }
A shell "input tap 866 1347" | Out-Null
# ---- END TIGHT BLOCK ----

for ($k = 1; $k -le 14; $k++) {
    $t = "E2ECHK5560_${Tag}_s$k"
    Ui $t | Out-Null
    $x = Join-Path $global:DIR "ui_$t.xml"
    $stage = @()
    foreach ($p in @('Connected', 'Authentication required', 'Could not establish', 'Connecting over SSH', 'Disconnected', 'left running', 'SSH authentication')) {
        if (Select-String -Path $x -Pattern $p -Quiet) { $stage += $p }
    }
    Write-Output ("  s{0,-3} {1}" -f $k, ($stage -join ' | '))
    if ($stage -contains 'Connected') { Write-Host '  *** CONNECTED ***'; Shot "E2ECHK5560_${Tag}_connected"; break }
}
Shot "E2ECHK5560_${Tag}_final"
