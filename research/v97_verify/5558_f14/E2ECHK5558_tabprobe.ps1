# Probe: find the real Settings tab strip band and locate the Servers tab on 5558.
param([string]$Dev = 'emulator-5558')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5558_run.ps1" -Dev $Dev

function TabRow($n) {
    # real tab labels live in one horizontal band; section headers do not.
    $names = 'General|Appearance|Pairing|Worktrees|Providers|Models|Servers|Shell|Experimental|About'
    $pat = '^[(' + ($names -replace '\|', '|') + ')]\t'
    $hits = @($n | Select-String -Pattern $pat | ForEach-Object { RectOf $_.Line } | Where-Object { $_ })
    if ($hits.Count -eq 0) { return $null }
    $ys = @($hits | ForEach-Object { [int](($_[1] + $_[3]) / 2) } | Sort-Object)
    $med = $ys[[int]($ys.Count / 2)]
    $xmin = ($hits | ForEach-Object { $_[0] } | Measure-Object -Minimum).Minimum
    $xmax = ($hits | ForEach-Object { $_[2] } | Measure-Object -Maximum).Maximum
    return ,@($med, $xmin, $xmax)
}

$n = Grab
Write-Host "SettingsOpen = $(SettingsOpen $n)"
for ($pass = 1; $pass -le 4; $pass++) {
    $n = Grab
    $row = TabRow $n
    if (-not $row) { Write-Host "pass ${pass}: no tab row found"; Shot "E2ECHK5558_tab_probe_$pass"; break }
    $ty = $row[0]
    Write-Host "pass ${pass}: TABROW y=$ty x=$($row[1])..$($row[2])"
    Write-Host ('  tabs: ' + (($n | Select-String -Pattern "^(General|Appearance|Pairing|Worktrees|Providers|Models|Servers|Shell|Experimental|About)\t" | ForEach-Object { ($_.Line -split "`t")[0] }) -join ', '))
    if ($n | Select-String -Pattern '^Servers\t' -Quiet) { Write-Host '  FOUND Servers'; break }
    $x1 = $row[1] + 10
    $x2 = $row[2] - 10
    Write-Host "  swipe $x2 -> $x1 at y=$ty"
    A shell "input swipe $x2 $ty $x1 $ty 80" | Out-Null
    Start-Sleep -Milliseconds 700
}
Shot 'E2ECHK5558_tab_probe_final'
$n = Grab
Write-Host '--- final tab-ish nodes ---'
($n | Select-String -Pattern "^(General|Appearance|Pairing|Worktrees|Providers|Models|Servers|Shell|Experimental|About|Notifications|Appearance)\t") | ForEach-Object { Write-Host "  $_" }
