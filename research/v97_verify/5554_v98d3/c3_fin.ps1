. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c3_entry.ps1"
$ErrorActionPreference = 'Stop'
InitDir
$null = FocusInfo

# tap the CONFIRM BAR's Delete (not the menu item) — coordinates come from the V2 dump
$dl = FindN "Delete"
if ($dl) {
    $b = Bnd $dl
    Say "TAP confirm-bar Delete @ $($b['cx']),$($b['cy']) bounds=[$($b['l']),$($b['t'])][$($b['r']),$($b['b'])]"
    TapXY $b['cx'] $b['cy']
} else { Say "confirm-bar Delete not found" }
Start-Sleep -Milliseconds 2500
$null = Cap "V3_deleted"

Say "--- AFTER row inventory ---"
$mine = $null; $acts = 0
foreach ($n in (Nodes)) {
    $t = TxtOf $n; if (-not $t) { continue }
    $b = Bnd $n; if (-not $b) { continue }
    if ($t -eq "Server actions") { $acts++ }
    if ($t -eq "keypass123") { $mine = (Bnd $n) }
    if ($b['t'] -lt 800 -or $b['t'] -gt 1995) { continue }
    if ($t -match 'Server actions|^keypass123|^10\.0\.2\.2|^127\.0\.0\.1|Server (healthy|not reachable)|^v2|^sshtest|SSH tunnels|Delete SSH') {
        Write-Host ("   [{0},{1}][{2},{3}] {4}" -f $b['l'],$b['t'],$b['r'],$b['b'],$t)
    }
}
Say "TARGET_GONE=$($null -eq $mine)   SERVER_ACTIONS_REMAINING=$acts"
Say "CONFIRM_BAR_GONE=$($null -eq (FindN 'Delete SSH server?'))"

# ---------- CLEANUP ----------
Say "--- faillock before ---"
& $global:ADB -s emulator-5554 shell "faillock --user sshtest"
& $global:ADB -s emulator-5554 shell "faillock -u sshtest"
Say "--- faillock after ---"
& $global:ADB -s emulator-5554 shell "faillock --user sshtest"
Say "--- /sdcard cleanup ---"
& $global:ADB -s emulator-5554 shell "rm -rf /sdcard/c3"
& $global:ADB -s emulator-5554 shell "ls /sdcard/c3"
& $global:ADB -s emulator-5554 shell "ls /sdcard"
Say "DONE_DEL_CLEAN"
