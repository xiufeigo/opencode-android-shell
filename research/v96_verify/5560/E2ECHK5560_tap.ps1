# E2ECHK5560_tap.ps1 - tap the centre of a node-table line matched by a regex.
#
# Exists because "$Matches[1] + $Matches[3]" on two STRINGS concatenates
# ("132" + "263" = "132263"), which silently produced taps at coordinates like
# 66132,10886109. Always cast the capture groups to [int] BEFORE arithmetic.
param(
    [Parameter(Mandatory = $true)][string]$Pattern,
    [string]$Dump = 'E2ECHK5560_tap_tmp',
    [int]$Index = 0,
    [switch]$NoDump
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5560_run.ps1" -Dev emulator-5560

function B($line) {
    if ($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]
        $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
        return ,@([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2))
    }
    return $null
}

if ($NoDump) { $n = @() } else { $n = Nodes $Dump }
$m = @($n | Select-String -Pattern $Pattern)
if ($m.Count -le $Index) {
    Write-Host "NOMATCH '$Pattern' (have $($m.Count) match(es))"
    ($n | Select-String -Pattern 'EditText|TextView|Button') | Select-Object -First 14 |
        ForEach-Object { Write-Host "   $($_.Line)" }
    exit 3
}
$line = $m[$Index].Line
$p = B $line
if (-not $p) { Write-Host "NOBOUNDS for '$Pattern'"; exit 3 }
Write-Host "TAP $line  ->  $($p[0]),$($p[1])"
A shell "input tap $($p[0]) $($p[1])" | Out-Null
Start-Sleep -Milliseconds 900
