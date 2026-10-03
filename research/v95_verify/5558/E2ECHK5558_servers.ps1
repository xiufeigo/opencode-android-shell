# E2ECHK5558_servers.ps1 — navigate Home -> Settings -> Servers tab, verify, land on panel
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

function GoServers {
    $n = Grab
    if ($n -notmatch 'Search settings') {
        $s = $n | Select-String -Pattern '^Settings\t' | Select-Object -First 1
        if (-not $s) { Write-Host 'ABORT: no Settings row on Home'; return $false }
        $p = B $s.Line
        Write-Host "open Settings @ $($p[0]),$($p[1])"
        Tap $p[0] $p[1]
        Start-Sleep -Seconds 2
    }
    $n = Grab
    $srv = $n | Select-String -Pattern '^Servers\t\[[0-9]+,7\d\d\]' | Select-Object -First 1
    for ($i = 0; $i -lt 8 -and -not $srv; $i++) {
        $sb = $n | Select-String -Pattern 'Search settings\t' | Select-Object -First 1
        $ty = 744
        if ($sb -and $sb.Line -match '\[(\d+),(\d+)\]') { $ty = [int]$Matches[2] + 184 }
        & $ADB -s $DEV shell "input swipe 950 $ty 150 $ty 350" 2>&1 | Out-Null
        Start-Sleep -Milliseconds 800
        $n = Grab
        $srv = $n | Select-String -Pattern '^Servers\t\[[0-9]+,7\d\d\]' | Select-Object -First 1
    }
    if (-not $srv) { Write-Host 'ABORT: Servers tab not found'; return $false }
    $p = B $srv.Line
    Write-Host "tab Servers @ $($p[0]),$($p[1])"
    Tap $p[0] $p[1]
    Start-Sleep -Seconds 2
    return $true
}

if (GoServers) {
    $n = Grab
    Write-Host '--- servers panel ---'
    ($n | Select-String -Pattern 'Servers\t|SSH tunnels|Reconnect|Disconnect|Connected|Connecting|Default|10\.0\.2\.2|127\.0\.0\.1|^V9') |
        ForEach-Object { Write-Host "  $($_.Line)" }
    Shot 'E2ECHK5558_p_panel'
}
