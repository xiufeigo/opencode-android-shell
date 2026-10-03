# E2ECHK5558_del.ps1 - delete SSH entries from the Servers panel
#   -Target <entry name substring>   (deletes every match, top-most first)
param([string]$Target = 'V95', [string]$Tag = 'del')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5558_nav.ps1"
function Nodes { python "$PSScriptRoot\E2ECHK5558_nodes.py" "$DIR\ui_E2ECHK5558_nav_tmp.xml" }
function Grab { Ui 'E2ECHK5558_nav_tmp'; Nodes }
function B($line) {
    if ($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
        return ,@([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2))
    }
    return $null
}

for ($round = 1; $round -le 6; $round++) {
    $n = Grab
    $names = @($n | Select-String -Pattern "^$Target" | ForEach-Object { $_.Line })
    if ($names.Count -eq 0) { Write-Host "no '$Target' entries left"; break }
    $top = $null; $topY = 99999
    foreach ($ln in $names) { $y = B $ln; if ($y -and $y[1] -lt $topY) { $topY = $y[1]; $top = $y } }
    $nm = ($names | Where-Object { (B $_)[1] -eq $topY } | Select-Object -First 1) -split "`t"
    Write-Host "round $round : delete entry '$($nm[0])' at $($top[0]),$($top[1])"

    # scroll the panel so the entry is comfortably inside the viewport (the menu of a
    # bottom-anchored row sits against the screen edge, where Delete gets eaten)
    if ($topY -lt 1300) {
        & $ADB -s emulator-5558 shell "input swipe 540 1400 540 1000 350" 2>&1 | Out-Null
        Start-Sleep -Milliseconds 900
    }
    $n = Grab
    $row = $n | Select-String -Pattern "^$Target" | Sort-Object { [int]($_ -replace '^.*\[(\d+),(\d+)\].*$', '$1') } | Select-Object -First 1
    if (-not $row) { Write-Host '  entry vanished'; continue }
    $rp = B $row.Line
    # the overflow button is the content-less "Server actions" view on the same row
    $acts = @($n | Select-String -Pattern '^Server actions\t' | ForEach-Object { $_.Line } |
              Sort-Object { [int](($_ -replace '^.*\[(\d+),(\d+)\].*$', '$1')) })
    if ($acts.Count -eq 0) { Write-Host '  no Server actions view'; break }
    $best = $null; $bestD = 99999
    foreach ($a in $acts) {
        $ap = B $a
        $d = [Math]::Abs($ap[1] - $rp[1])
        if ($d -lt $bestD) { $bestD = $d; $best = $ap }
    }
    $menuX = $best[0]; $menuY = $best[1]
    Write-Host "  tap menu @ $menuX,$menuY (row y=$($rp[1]))"
    Tap $menuX $menuY
    Start-Sleep -Seconds 1
    $n = Grab
    $del = $n | Select-String -Pattern '^Delete\t|^Remove\t' | Select-Object -First 1
    if ($del) {
        $dp = B $del.Line
        Write-Host "  tap Delete @ $($dp[0]),$($dp[1])"
        Tap $dp[0] $dp[1]
        Start-Sleep -Milliseconds 800
        $n = Grab
        $ok = $n | Select-String -Pattern '^Delete\t|^Confirm' | Select-Object -First 1
        if ($ok) { $op = B $ok.Line; Tap $op[0] $op[1]; Start-Sleep -Seconds 1 }
    } else {
        Write-Host '  WARN no Delete item; menu items:'
        ($n | Select-String -Pattern 'Delete|Remove|Edit|Disconnect|Connect') | Select-Object -First 6 | ForEach-Object { Write-Host "     $($_.Line)" }
        break
    }
    Shot "E2ECHK5558_${Tag}_$round"
}
Shot "E2ECHK5558_${Tag}_final"
$n = Grab
Write-Host '--- remaining ---'
($n | Select-String -Pattern 'SSH tunnels|Reconnect|10.0.2.2|^V95') | ForEach-Object { Write-Host "  $($_.Line)" }
