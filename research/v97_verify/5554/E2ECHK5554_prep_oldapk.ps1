# E2ECHK5554_prep_oldapk.ps1 - NON-DESTRUCTIVE readiness probes on whatever APK is
# currently installed. Run BEFORE the new build lands (old APK) and REPEAT after
# install (new APK) as the smoke gate. NO F14 assertions here, no entries created,
# nothing deleted, no faillock burns.
#
# Checks:
#   P1 device identity            (emu avd name == Pixel_9a)
#   P2 app installed + version    (pm path / dumpsys)
#   P3 app launches               (am start .OcActivity, focus check)
#   P4 Settings dialog opens      (any tab label present)
#   P5 Servers panel reachable    (Servers tab -> rows / Add SSH server button)
#   P6 Add SSH form dumps         (form labels present in the node table; form is
#                                  CLOSED again via its own Cancel button - no BACK)
#   P7 screenshot pipeline sane   (PNG size sanity on every Shot)
param([string]$Dev = 'emulator-5554')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5554_run.ps1" -Dev $Dev
AssertDevice

Write-Host '=== P2 app installed ==='
$path = (A shell "pm path $global:PKG" | Out-String).Trim()
Write-Host "  pm path -> $path"
if ($path -notmatch 'base\.apk') { throw 'app not installed' }
AppVersion | ForEach-Object { Write-Host "  $_" }

Write-Host '=== P3 launch ==='
$foc = LaunchApp
Write-Host "  focus -> $foc"
Shot 'E2ECHK5554_prep_01_home'
$n = Grab
Write-Host '--- home nodes (first 12) ---'
($n | Select-String -Pattern 'EditText|TextView|Button') | Select-Object -First 12 | ForEach-Object { Write-Host "  $($_.Line)" }

Write-Host '=== P4/P5 Settings -> Servers ==='
GotoServers
Shot 'E2ECHK5554_prep_02_servers_panel'
$n = Grab
Write-Host '--- Servers panel nodes ---'
($n | Select-String -Pattern 'Server|healthy|Healthy|10\.0\.2\.2|Add SSH') | Select-Object -First 20 | ForEach-Object { Write-Host "  $($_.Line)" }
if (-not (Has $n 'Add SSH server')) { throw 'Servers panel: Add SSH server button not found' }

Write-Host '=== P6 form dump (then Cancel; no BACK, no ESC) ==='
OpenAddForm
Shot 'E2ECHK5554_prep_03_form_open'
$n = Grab
Write-Host '--- form nodes ---'
($n | Select-String -Pattern 'Host or SSH command|Server name|Authentication|Password|Private key|Passphrase|Add server|Cancel|EditText') |
    Select-Object -First 20 | ForEach-Object { Write-Host "  $($_.Line)" }
if (-not ((Has $n 'Host or SSH command') -or (Has $n '^Authentication\t'))) { throw 'form did not open / not dumpable' }
$can = $n | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
if ($can) {
    $p = CenterOf $can.Line
    Tap $p[0] $p[1]
    Write-Host "form cancelled via its Cancel button @ $($p[0]),$($p[1])"
} else {
    Write-Host 'WARN: Cancel not visible; leave form open for the operator to close manually'
}
Shot 'E2ECHK5554_prep_04_after_cancel'

Write-Host '=== PREP DONE (no verdicts, readiness only) ==='
