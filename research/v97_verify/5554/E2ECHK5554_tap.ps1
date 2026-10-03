# E2ECHK5554_tap.ps1 - tap the centre of the Nth node matching a regex, from a FRESH
# dump (coordinates are never hardcoded). Bounds arithmetic always casts to [int]
# first: "$Matches[1] + $Matches[3]" on strings concatenates (v96 lesson).
# Usage:
#   .\E2ECHK5554_tap.ps1 -Pattern '^Servers\t'
#   .\E2ECHK5554_tap.ps1 -Pattern 'Add SSH server' -Dump mydump -Index 0
param(
    [Parameter(Mandatory = $true)][string]$Pattern,
    [string]$Dump = 'E2ECHK5554_tap_tmp',
    [int]$Index = 0,
    [string]$Dev = 'emulator-5554'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5554_run.ps1" -Dev $Dev
TapMatch $Pattern $Dump $Index | Out-Null
