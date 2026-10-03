# E2ECHK5554_reset.ps1 - put the device back to a known-clean state between
# scenarios: close any auth card (its own Cancel), close the Add/Edit form (its own
# Cancel), relaunch the app. NEVER BACK(4), NEVER ESC(111).
param([string]$Dev = 'emulator-5554')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5554_run.ps1" -Dev $Dev
AssertDevice
Write-Host '--- cancel any auth card / open form (own Cancel only) ---'
for ($i = 0; $i -lt 8; $i++) {
    $n = Grab
    $can = $n | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
    if (-not $can) { break }
    $p = CenterOf $can.Line
    $lbl = (($can.Line -split "`t")[0])
    Write-Host "  tap Cancel ('$lbl') @ $($p[0]),$($p[1])"
    Tap $p[0] $p[1]
    Start-Sleep -Milliseconds 1200
}
Shot 'E2ECHK5554_RESET_after_cancel' | Out-Null
LaunchApp | Out-Null
Start-Sleep -Seconds 2
$n = Grab
Write-Host '--- state after reset (card/form/settings presence) ---'
foreach ($k in @('SSH authentication', 'Host or SSH command', 'Authentication', '^Servers\t', '^Settings\t', 'E2E5554')) {
    Write-Host ("  {0,-24} {1}" -f $k, (Has $n $k))
}
Write-Host 'RESET DONE'
