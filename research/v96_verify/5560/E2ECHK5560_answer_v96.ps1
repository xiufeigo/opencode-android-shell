# E2ECHK5560_answer_v96.ps1 - submit the open form and answer the SSH challenge in ONE
# uninterrupted block, then sample the outcome.
#
# WHY: the kbdint / passphrase auth session has a server-side timeout. Any screencap or
# uiautomator dump between "card is up" and "Continue tapped" lets it expire, and the
# app then re-offers the card with the STORED credential, so a correctly typed secret
# still lands as a failure (v95 REPORT section 6.7). Everything from the submit tap to
# the Continue tap therefore runs with no dumps in between.
param(
    [Parameter(Mandatory = $true)][int]$SubmitX,
    [Parameter(Mandatory = $true)][int]$SubmitY,
    [string]$Secret = 'opencode',
    [string]$Tag = 'A',
    [int]$FieldX = 540,
    [int]$FieldY = 1641,   # credential field, keyboard-DOWN position
    [int]$ContX = 866,
    [int]$ContY = 1347,    # Continue, keyboard-UP position
    [int]$CardWaitMs = 2600,
    [int]$Samples = 10
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5560_run.ps1" -Dev emulator-5560

ResetFaillock | Out-Null
Write-Host 'faillock reset'

Write-Host "SUBMIT @ $SubmitX,$SubmitY"
A shell "input tap $SubmitX $SubmitY" | Out-Null
Start-Sleep -Milliseconds $CardWaitMs

# ---------------- TIGHT BLOCK: no dumps, no screenshots ----------------
A shell "input tap $FieldX $FieldY" | Out-Null      # focus credential field -> IME up
Start-Sleep -Milliseconds 800
$words = @($Secret -split ' ' | Where-Object { $_ -ne '' })
for ($w = 0; $w -lt $words.Count; $w++) {
    $word = $words[$w]
    for ($i = 0; $i -lt $word.Length; $i += 8) {
        $seg = $word.Substring($i, [Math]::Min(8, $word.Length - $i))
        A shell "input text '$seg'" | Out-Null
        Start-Sleep -Milliseconds 900
    }
    if ($w -lt $words.Count - 1) { A shell "input keyevent 62" | Out-Null; Start-Sleep -Milliseconds 300 }
}
A shell "input tap $ContX $ContY" | Out-Null        # Continue
# ---------------- END TIGHT BLOCK ----------------
Start-Sleep -Milliseconds 2500

Shot "E2ECHK5560_${Tag}_40_result"
Write-Host '--- outcome samples ---'
for ($s = 1; $s -le $Samples; $s++) {
    $n = Nodes "E2ECHK5560_${Tag}_41_s$s"
    $bits = @()
    foreach ($pat in @('Connected', 'Could not establish', 'Authentication required', 'Connecting', 'Disconnected', 'left running', 'SSH authentication', 'passphrase', 'Passphrase', 'left to unlock')) {
        if ($n | Select-String -Pattern $pat -Quiet) { $bits += $pat }
    }
    Write-Output ("  s{0,-2} {1}" -f $s, ($bits -join ' | '))
    if ($bits -contains 'Connected') { break }
}
Shot "E2ECHK5560_${Tag}_42_final"
Write-Host '--- final row text ---'
Nodes "E2ECHK5560_${Tag}_43_row" | Select-String -Pattern 'E2EC5560|Connected|Could not|required|left running|Reconnect|Disconnect|10\.0\.2\.2' |
    Select-Object -First 20 | ForEach-Object { Write-Output "  $($_.Line)" }
