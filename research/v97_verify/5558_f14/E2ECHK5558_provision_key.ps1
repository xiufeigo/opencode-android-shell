# E2ECHK5554_provision_key.ps1 - put the ed25519 test key where the app can read it.
#
# WHY REPLAYABLE: every APK reinstall can change the app uid (v96: u0_a218 -> u0_a230).
# The /sdcard copy (owner u0_a218:media_rw 660) stays UNREADABLE for the new uid
# (sdcardfs ignores chmod), so the ONLY reliable location is the app private dir:
#   /data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519
# Form to use in the SSH entry afterwards:
#   /data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519
# (or /data/data/... - same inode on this build).
#
# RUN AFTER EVERY APK INSTALL, before F14b/F12a/F13b scenarios.
# Verifies byte identity via md5 on both sides; exits non-zero on mismatch.
param([string]$Dev = 'emulator-5554')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5554_run.ps1" -Dev $Dev
AssertDevice
Write-Host '--- before: app uid ---'
(A shell "dumpsys package $global:PKG" | Out-String) -split "`n" |
    Select-String -Pattern 'uid=|userId=' | Select-Object -First 3 | ForEach-Object { Write-Host "  $($_.ToString().Trim())" }
EnsureKey
Write-Host '--- negative control: /sdcard copy must stay UNREADABLE for the app ---'
$denied = A shell "run-as $global:PKG cat /sdcard/v95/id_ed25519" 2>&1 | Out-String
Write-Host "  run-as cat /sdcard/v95/id_ed25519 -> $($denied.Trim())"
if ($denied -match 'Permission denied') { Write-Host 'NEGATIVE CONTROL OK (unreadable - matches F14a assumption)' }
else { Write-Host 'WARN: /sdcard copy unexpectedly readable; F14a scenario needs a different unreadable path' }
Shot 'E2ECHK5554_KEY_00_after_provision' | Out-Null
