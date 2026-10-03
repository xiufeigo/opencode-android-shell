. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c3lib.ps1"
$ErrorActionPreference = 'Stop'
InitDir

Focus
$null = Cap "S0_start"

# Home first (cold boot may land in a session view)
$hNode = FindNode "Home"
if ($hNode) { $b = Bounds $home; Write-Host "tap Home $($b.cx),$($b.cy)"; Tap $b.cx $b.cy }

# Settings
$set = FindNode "Settings"
if (-not $set) { DumpTexts "no-settings" }
if ($set) { $b = Bounds $set; Tap $b.cx $b.cy; Write-Host "tapped Settings" }
$null = Cap "S1_settings"

# Find the tab strip: scrollable, height<200, y<1000
$tab = $null
foreach ($n in Nodes) {
    if ($n.GetAttribute("scrollable") -ne "true") { continue }
    $b = Bounds $n
    if ($null -eq $b) { continue }
    if (($b.b - $b.t) -lt 200 -and $b.t -lt 1000) { $tab = $b }
}
Write-Host "TABSTRIP: $($tab | Out-String)"

if (-not (InServers)) {
    # swipe the tab strip left until 'Servers' shows
    for ($i = 0; $i -lt 6; $i++) {
        $srv = FindNode "Servers"
        if ($srv) { break }
        if ($tab) {
            $y = $tab.cy
            & $global:ADB $global:SER shell "input swipe $($tab.r - 40) $y $($tab.l + 40) $y 250" | Out-Null
            Start-Sleep -Milliseconds 700
        }
        $null = Cap "S2_tab$i"
    }
    $srv = FindNode "Servers"
    if ($srv) { $b = Bounds $srv; Write-Host "tapped Servers at $($b.cx),$($b.cy)"; Tap $b.cx $b.cy }
}
Start-Sleep -Milliseconds 900
$null = Cap "S3_servers"
Write-Host "IN_SERVERS=$(InServers)"
DumpTexts "servers-panel"

