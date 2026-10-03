# E2ECHK5554_v82five.ps1 - SKELETON for the "v82 five criteria" re-run.
#
# !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
# TODO(TASK-2 TEXT): `team_task_get task-2` is NOT available in this tester session
# (no such command/tool), so the EXACT five criteria wording could not be fetched.
# The five slots below are reconstructed from research/HANDOFF.md (v82 fix surface).
# BEFORE JUDGING: have the Lead paste the task-2 wording, adjust slot bodies, then run.
# !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
#
# Reconstructed v82 fix surface (HANDOFF.md):
#   S1 chord Ctrl+K  -> model/command palette      (keycombination 113 39; NEVER 75:
#                                                     75 = APOSTROPHE = Ctrl+' which is
#                                                     model.choose BY DESIGN - v82 trap)
#   S2 chord Ctrl+Shift+P -> palette               (keycombination 113 44; P=44)
#   S3 Settings dialog IME band: with the soft keyboard up, the settings card stays
#      usable - card top edge y is unchanged (about (window-600dp)/2 on 1366x768@160;
#      on this 1080x2424@420 device measure from the dump) and content above the IME
#      is reachable (settingsDialogCardHeightDp = min(window-92, 600))
#   S4 Add/Edit server dialog: Save/Cancel footer reachable with IME up
#      (serverDialogBandDp; footer pinned below the scroll body)
#   S5 SSE transport recovery: a "Not connected - ..." card must retire by itself once
#      the transport recovers (BUG-C fix: isClientNotConnectedText joins the
#      TRANSPORT retirement class). NOTE: verifying this requires bouncing the SHARED
#      backend (4095) -> FORBIDDEN for a tester. Slot S5 therefore only collects
#      evidence passively; the disruptive part needs Lead coordination or a skip.
#
# Everything here is dump-driven; no hardcoded assertion coordinates.
param(
    [string]$Dev = 'emulator-5554',
    [switch]$SkipS5
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5554_run.ps1" -Dev $Dev
AssertDevice

$verdicts = @{}

# ---------- S1: Ctrl+K opens the palette ----------
Write-Host '=== S1 Ctrl+K (113 39) ==='
LaunchApp | Out-Null
Start-Sleep -Seconds 2
Shot 'E2ECHK5554_V82_S1_00_before'
A shell "input keycombination 113 39" | Out-Null
Start-Sleep -Seconds 2
$n = Grab
Shot 'E2ECHK5554_V82_S1_01_after'
# EXPECT: a palette/model-picker layer node appears (exact marker per task-2 wording;
# candidates: "Choose model", palette container, command list). Record raw evidence.
Write-Host '--- nodes after Ctrl+K (scan for palette markers) ---'
($n | Select-String -Pattern 'model|Model|palette|Palette|command|Command|Search') |
    Select-Object -First 12 | ForEach-Object { Write-Host "  $($_.Line)" }
# TODO(TASK-2 TEXT): replace the marker below with the exact expectation, then judge.
$verdicts.S1 = 'UNSET (marker TBD from task-2 text; evidence captured)'
# close whatever opened: dismiss via TAB focus move only; never BACK, never ESC here.

# ---------- S2: Ctrl+Shift+P opens the palette ----------
Write-Host '=== S2 Ctrl+Shift+P (113 44) ==='
A shell "input keycombination 113 44" | Out-Null
Start-Sleep -Seconds 2
$n = Grab
Shot 'E2ECHK5554_V82_S2_01_after'
($n | Select-String -Pattern 'model|Model|palette|Palette|command|Command|Search') |
    Select-Object -First 12 | ForEach-Object { Write-Host "  $($_.Line)" }
$verdicts.S2 = 'UNSET (marker TBD from task-2 text; evidence captured)'

# ---------- S3: Settings dialog IME band ----------
Write-Host '=== S3 settings dialog IME band ==='
GotoSettings
$n = Grab
Shot 'E2ECHK5554_V82_S3_00_dialog'
# card top edge from the dump: any visible tab label marks the card header row
$tab = $n | Select-String -Pattern '^(General|Appearance|Notifications|Servers|Shell|Experimental|About)\t' | Select-Object -First 1
$topNoIme = $null
if ($tab -and $tab.Line -match '\[(\d+),(\d+)\]') { $topNoIme = [int]$Matches[2] }
Write-Host "  card header y (IME down): $topNoIme"
# focus the search field to raise the IME
$fld = $n | Select-String -Pattern 'EditText\t' | Select-Object -First 1
if ($fld) {
    $p = CenterOf $fld.Line
    Tap $p[0] $p[1]
    Start-Sleep -Milliseconds 1200
    $n = Grab
    Shot 'E2ECHK5554_V82_S3_01_ime_up'
    $tab2 = $n | Select-String -Pattern '^(General|Appearance|Notifications|Servers|Shell|Experimental|About)\t' | Select-Object -First 1
    $topIme = $null
    if ($tab2 -and $tab2.Line -match '\[(\d+),(\d+)\]') { $topIme = [int]$Matches[2] }
    Write-Host "  card header y (IME up):   $topIme"
    # EXPECT per v82 fix: header y unchanged (card shrinks/scrolls BELOW, top stays).
    if ($null -ne $topNoIme -and $null -ne $topIme) {
        if ([Math]::Abs($topNoIme - $topIme) -le 12) { $verdicts.S3 = "PASS (top $topNoIme -> $topIme, within 12px)" }
        else { $verdicts.S3 = "MEASURED top $topNoIme -> $topIme; judge against task-2 wording" }
    } else { $verdicts.S3 = 'UNSET (header not found in one of the dumps)' }
} else { $verdicts.S3 = 'UNSET (no EditText to focus)' }
# collapse IME with TAB focus move (ESC is FORBIDDEN inside the dialog)
A shell "input keyevent 61" | Out-Null
Start-Sleep -Milliseconds 600

# ---------- S4: Add/Edit server dialog footer reachable with IME up ----------
Write-Host '=== S4 server dialog footer with IME up ==='
GotoServers
OpenAddForm
$n = Grab
$fld = $n | Select-String -Pattern 'EditText\t' | Select-Object -First 1
if (-not $fld) { $verdicts.S4 = 'UNSET (no field found in form)' }
else {
    $p = CenterOf $fld.Line
    Tap $p[0] $p[1]
    Start-Sleep -Milliseconds 1200
    $n = Grab
    Shot 'E2ECHK5554_V82_S4_01_ime_up'
    $ft = $n | Select-String -Pattern '^Add server\t|^Cancel\t' | Select-Object -First 1
    if ($ft) {
        $fb = $ft.Line
        if ($fb -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            $fY1 = [int]$Matches[2]; $fY2 = [int]$Matches[4]
            $fLabel = ($ft.Line -split "`t")[0]
            Write-Host "  footer '$fLabel' y=$fY1..$fY2"
            $verdicts.S4 = "MEASURED footer y=$fY1..$fY2 with IME up; PASS if fully above the IME top edge (see V82_S4_01 evidence)"
        }
    } else { $verdicts.S4 = 'UNSET (footer not in dump; scroll band may need FormSwipe first)' }
}
# close the form via its own Cancel
$n = Grab
$can = $n | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
if ($can) { $cp = CenterOf $can.Line; Tap $cp[0] $cp[1] }

# ---------- S5: SSE recovery card retirement (PASSIVE ONLY) ----------
if (-not $SkipS5) {
    Write-Host '=== S5 SSE recovery (PASSIVE; bouncing the shared 4095 backend is FORBIDDEN) ==='
    Shot 'E2ECHK5554_V82_S5_00_state'
    $n = Grab
    ($n | Select-String -Pattern 'Not connected|offline|reconnect|Retrying') |
        Select-Object -First 10 | ForEach-Object { Write-Host "  $($_.Line)" }
    $verdicts.S5 = 'NEEDS LEAD - requires a backend bounce on the shared 4095; passive evidence captured only'
}

Write-Host '=== V82 FIVE - SKELETON VERDICTS (judge only after pasting task-2 text) ==='
$verdicts.Keys | Sort-Object | ForEach-Object { Write-Output ("  {0} : {1}" -f $_, $verdicts[$_]) }
