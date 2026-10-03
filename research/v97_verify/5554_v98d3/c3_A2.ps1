. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c3lib.ps1"
$ErrorActionPreference = 'Stop'

# ---------- A.2 : DEFAULT-STATE submit (no field touched) ----------
Say "=== A.2 default-state Add server ==="
$null = Cap "A04_before_default_tap"
$addN = FindN "Add server"
if ($addN) {
    $b = Bnd $addN
    Say ("Add server node: bounds=[{0},{1}][{2},{3}] en={4} cl={5} focusable={6}" -f `
        $b['l'],$b['t'],$b['r'],$b['b'],$addN.GetAttribute("enabled"),$addN.GetAttribute("clickable"),$addN.GetAttribute("focusable"))
    TapXY $b['cx'] $b['cy']
} else { Say "Add server node NOT FOUND" }
Start-Sleep -Milliseconds 1500
$null = Cap "A05_after_default_tap"
Say "FORM_OPEN_AFTER_DEFAULT=$(FormOpen)"
$vp = Viewport
if ($vp) { Say ("VIEWPORT_AFTER [{0},{1}][{2},{3}]" -f $vp['l'],$vp['t'],$vp['r'],$vp['b']) }
DumpT "AFTER-DEFAULT-TAP"

# ---------- A.1 : enumerate every field by scrolling the form ----------
Say ""
Say "=== A.1 full field enumeration (scroll form down) ==="
for ($i = 0; $i -lt 5; $i++) {
    Sh "input swipe 500 1650 500 1150 300"
    Start-Sleep -Milliseconds 800
    $null = Cap "A06_formscroll$i"
    Say "--- scroll $i ---"
    foreach ($n in (Nodes)) {
        $t = TxtOf $n
        if (-not $t) { continue }
        $b = Bnd $n
        if (-not $b) { continue }
        if ($b['t'] -lt 800 -or $b['b'] -gt 1940) { continue }
        Write-Host ("   [{0},{1}][{2},{3}] en={4} cl={5} | {6}" -f `
            $b['l'],$b['t'],$b['r'],$b['b'],$n.GetAttribute("enabled"),$n.GetAttribute("clickable"),$t)
    }
}
Say "DONE_A1A2"
