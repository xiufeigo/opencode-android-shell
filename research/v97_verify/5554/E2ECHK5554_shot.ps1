# E2ECHK5554_shot.ps1 - standalone screenshot helper.
# Discipline: screencap -p /sdcard/... + adb pull; exec-out is NEVER used (adb
# warning text pollutes PNG bytes). Usage:
#   .\E2ECHK5554_shot.ps1 -Name prep_01_home
param(
    [Parameter(Mandatory = $true)][string]$Name,
    [string]$Dev = 'emulator-5554'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5554_run.ps1" -Dev $Dev
Shot "E2ECHK5554_$Name"
