# Smoke: clean relaunch -> Settings -> Servers tab -> Add SSH form -> dump REAL bounds.
param([string]$Dev = 'emulator-5558')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5558_run.ps1" -Dev $Dev

AssertDevice
Write-Host '=== clean launch ==='
LaunchApp | Out-Null
Start-Sleep -Seconds 2

$n = Grab
if (-not ($n | Select-String -Pattern '^Settings\t' -Quiet)) {
    Write-Host 'Home has no Settings row; dump tail:'
    ($n | Select-Object -Last 8) | ForEach-Object { Write-Host "  $_" }
    throw 'no Settings affordance after clean launch'
}
Write-Host 'Home OK (Settings affordance present)'

Write-Host '=== open settings ==='
GotoSettings
$n = Grab
if (-not (SettingsOpen $n)) { Shot 'E2ECHK5558_NAV_abort'; throw 'Settings did not open' }
Write-Host 'Settings open'

# Verbose tab scroll: log the visible tab list on every pass.
for ($i = 0; $i -lt 8; $i++) {
    $n = Grab
    $row = TabRow $n
    $tabs = (TabNodes $n | ForEach-Object { $_[0] }) -join ','
    if ($row) { Write-Host "  pass ${i}: TABROW y=$($row[0]) x=$($row[1])..$($row[2]) tabs=[$tabs]" }
    else { Write-Host "  pass ${i}: NO TABROW tabs=[$tabs]" }
    if ($n | Select-String -Pattern '^Servers\t' -Quiet) { Write-Host '  FOUND Servers'; break }
    if (-not $row) { Start-Sleep -Milliseconds 400; continue }
    $ty = $row[0]
    A shell "input swipe $($row[2] - 10) $ty $($row[1] + 10) $ty 80" | Out-Null
    Start-Sleep -Milliseconds 600
}

$srv = Grab | Select-String -Pattern '^Servers\t' | Select-Object -First 1
if (-not $srv) { Shot 'E2ECHK5558_NAV_abort'; Write-Host 'visible tabs were:'; (TabNodes (Grab)) | ForEach-Object { Write-Host "  $_" }; throw 'Servers tab not found' }
$p = CenterOf $srv.Line
Write-Host "TAP Servers @ $($p[0]),$($p[1])"
Tap $p[0] $p[1]
Start-Sleep -Seconds 1
Shot 'E2ECHK5558_smoke_03_servers'

Write-Host '=== add form ==='
OpenAddForm
Start-Sleep -Seconds 1
Shot 'E2ECHK5558_smoke_04_form'

$n = Grab
Write-Host '=== FORM RAW NODES ==='
$n | ForEach-Object { Write-Host "  $_" }
Write-Host '=== FormScrollRect ==='
$r = FormScrollRect
if ($r) { Write-Host "RECT $($r[0]),$($r[1]) .. $($r[2]),$($r[3])  w=$($r[2]-$r[0]) h=$($r[3]-$r[1])" }
else { Write-Host 'RECT none' }
Write-Host '=== Fields() [recalibrated] ==='
(Fields $n) | ForEach-Object { Write-Host "  FLD $_" }
Write-Host '=== FieldNear ==='
Write-Host "  name-> $(FieldNear $n 'Server name (optional)' 'below')"
Write-Host "  host-> $(FieldNear $n 'Host or SSH command' 'below')"
Write-Host '=== FieldsAboveAuth ==='
(FieldsAboveAuth $n) | ForEach-Object { Write-Host "  ABOVE $_" }
Write-Host 'DONE smoke'
