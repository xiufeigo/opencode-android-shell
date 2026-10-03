. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c3lib.ps1"
$ErrorActionPreference = 'Stop'
InitDir
$null = FocusInfo
$null = Cap "A00_state"

# --- go Home first (cold boot may sit in a session view) ---
$hN = FindN "Home"
if ($hN) { $b = Bnd $hN; Say "tap Home $($b['cx']),$($b['cy'])"; TapXY $b['cx'] $b['cy']; Start-Sleep -Milliseconds 900 }

$sN = FindN "Settings"
if (-not $sN) { DumpT "NO-SETTINGS" }
if ($sN) { $b = Bnd $sN; Say "tap Settings $($b['cx']),$($b['cy'])"; TapXY $b['cx'] $b['cy']; Start-Sleep -Milliseconds 1200 }

# --- tab strip: scrollable, height<200, y<1000 ---
function GetTab {
    $t = $null
    foreach ($n in (Nodes)) {
        if ($n.GetAttribute("scrollable") -ne "true") { continue }
        $b = Bnd $n
        if (-not $b) { continue }
        if (($b['b'] - $b['t']) -lt 200 -and $b['t'] -lt 1000) { $t = $b }
    }
    return $t
}
$tab = GetTab
if ($tab) { Say ("TABSTRIP [{0},{1}][{2},{3}]" -f $tab['l'], $tab['t'], $tab['r'], $tab['b']) }

for ($i = 0; $i -lt 6; $i++) {
    if (InServers) { break }
    $tab = GetTab
    if ($tab) {
        $y = $tab['cy']
        Sh "input swipe $($tab['r'] - 40) $y $($tab['l'] + 40) $y 250"
        Start-Sleep -Milliseconds 800
    }
    $null = Cap "A01_tab$i"
    $srv = FindN "Servers"
    if ($srv) { $b = Bnd $srv; Say "tap Servers $($b['cx']),$($b['cy'])"; TapXY $b['cx'] $b['cy']; Start-Sleep -Milliseconds 1000 }
}
$null = Cap "A02_servers"
Say "IN_SERVERS=$(InServers)"
$vp = Viewport
if ($vp) { Say ("VIEWPORT [{0},{1}][{2},{3}]" -f $vp['l'], $vp['t'], $vp['r'], $vp['b']) }
DumpT "SERVERS-PANEL"

# --- open the Add SSH server form ---
if ($null -ne (TapTxt "Add SSH server")) {
    Start-Sleep -Milliseconds 1200
}
$null = Cap "A03_form_default"
Say "FORM_OPEN=$(FormOpen)"
DumpT "FORM-DEFAULT-STATE"
$vp = Viewport
if ($vp) { Say ("FORM_VIEWPORT [{0},{1}][{2},{3}]" -f $vp['l'], $vp['t'], $vp['r'], $vp['b']) }
Say "DONE_A03"
