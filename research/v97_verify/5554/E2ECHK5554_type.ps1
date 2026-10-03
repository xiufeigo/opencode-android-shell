# E2ECHK5554_type.ps1 - type ASCII text into the ALREADY-FOCUSED field.
# Segments of <=8 chars, 1.5 s between segments, KEYCODE_SPACE(62) between words.
# Never sends BACK(4). The field must be focused first (tap it via E2ECHK5554_tap.ps1).
# Usage: .\E2ECHK5554_type.ps1 -Text 'ssh -p 2222 sshtest@127.0.0.1'
param(
    [Parameter(Mandatory = $true)][string]$Text,
    [string]$Dev = 'emulator-5554'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5554_run.ps1" -Dev $Dev
TypeText $Text
Write-Host "TYPED: $Text"
