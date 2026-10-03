# E2ECHK5560_openform.ps1 - idempotent navigation to the Add SSH server form.
#
# v95's addentry.ps1 did this non-idempotently: on a second run the "Search settings"
# box is already present, so the 8x tab-strip swipe fires INSIDE the open dialog and is
# read as drag-to-dismiss -> the whole Settings dialog (and the form) goes away. So:
#   1. already on the form  -> no-op
#   2. Settings open        -> find the Servers tab by text, tap Add SSH server
#   3. otherwise            -> tap Settings first, scroll the tab strip to its END,
#                              then look for Servers. Never swipe when Settings is
#                              already open.
# ASCII ONLY (PS 5.1 parses BOM-less .ps1 as ANSI).
param(
    [switch]$JustSettings
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
function Has($nodes, [string]$pat) { [bool]($nodes | Select-String -Pattern $pat -Quiet) }
# ANY Settings tab label counts as "the dialog is open": the strip is horizontally
# scrollable, so after a previous run "General" can be scrolled out of view entirely
# and a `^General\t` check alone reports a false negative (taps the scrim, closes it).
function SettingsOpen($nodes) {
    [bool]($nodes | Select-String -Pattern '^(General|Appearance|Notifications|Servers|Shell|Experimental|About)\t' -Quiet)
}

$n = Grab

# ---- state 1: form already open ----
if ((Has $n 'Host or SSH command') -or (Has $n '^Authentication\t')) {
    Write-Host 'STATE=form-already-open'
    Shot 'E2ECHK5560_01_form_open'
    exit 0
}

# ---- state 2: Settings dialog already open ----
# NOTE: "Search settings" is an EditText HINT, so it never reaches the node table
# (nodes.py prints text|content-desc only). The always-visible tab label is the
# reliable "dialog is open" marker, and it also gives the tab-strip y.
# NOTE: checks are line-based via Has -match -notmatch on the JOINED string would
# anchor ^ at the start of the WHOLE string, not per line, and always miss.
if (-not (SettingsOpen $n)) {
    $s = $n | Select-String -Pattern '^Settings\t' | Select-Object -First 1
    if (-not $s) { Write-Host 'ABORT: no Settings affordance on the home screen'; exit 2 }
    $p = B $s.Line
    Write-Host "open Settings @ $($p[0]),$($p[1])"
    Tap $p[0] $p[1]
    Start-Sleep -Seconds 1
    $n = Grab
    if (-not (SettingsOpen $n)) { Write-Host 'ABORT: Settings dialog did not open'; exit 2 }
    # tab strip sits on the same row as the visible tab label; scroll it to the far end
    # ONCE, while the dialog was just opened (safe: no dialog to lose).
    $g = $n | Select-String -Pattern '^(General|Appearance|Notifications)\t' | Select-Object -First 1
    $ty = 744
    if ($g -and $g.Line -match '\[(\d+),(\d+)\]') { $ty = [int]$Matches[2] }
    for ($i = 0; $i -lt 6; $i++) { A shell "input swipe 200 $ty 960 $ty 250" | Out-Null; Start-Sleep -Milliseconds 350 }
    $n = Grab
} else {
    Write-Host 'Settings already open - no swipe (drag-to-dismiss guard)'
}

# ---- reach the Servers tab ----
$srv = $null
for ($i = 0; $i -lt 6; $i++) {
    $n = Grab
    $srv = $n | Select-String -Pattern '^Servers\t' | Select-Object -First 1
    if ($srv) { break }
    Write-Host "  Servers tab not visible (attempt $($i + 1))"
    A shell "input swipe 950 744 150 744 350" | Out-Null
    Start-Sleep -Milliseconds 700
}
if (-not $srv) { Write-Host 'ABORT: Servers tab not found'; exit 2 }
$p = B $srv.Line
Write-Host "tab Servers @ $($p[0]),$($p[1])"
Tap $p[0] $p[1]
Start-Sleep -Seconds 1

Shot 'E2ECHK5560_00_servers_panel'
$n = Grab
Write-Host '--- Servers panel rows ---'
($n | Select-String -Pattern 'E2EC5560|10\.0\.2\.2|Server actions|Add SSH server|healthy|Healthy') |
    Select-Object -First 30 | ForEach-Object { Write-Host "  $($_.Line)" }

if ($JustSettings) { Write-Host 'JustSettings - stop'; exit 0 }

# ---- open the form ----
$add = $n | Select-String -Pattern '^Add SSH server\t' | Select-Object -First 1
if (-not $add) { Write-Host 'ABORT: Add SSH server button not found'; exit 2 }
$p = B $add.Line
Write-Host "AddSSH @ $($p[0]),$($p[1])"
Tap $p[0] $p[1]
Start-Sleep -Seconds 1
Shot 'E2ECHK5560_01_form_open'
$n = Grab
Write-Host '--- form open ---'
($n | Select-String -Pattern 'Host or SSH command|Server name|Authentication|Add server|Cancel|EditText') |
    Select-Object -First 20 | ForEach-Object { Write-Host "  $($_.Line)" }
