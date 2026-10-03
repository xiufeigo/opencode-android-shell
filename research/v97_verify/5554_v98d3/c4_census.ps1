. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4lib.ps1"
InitDir
# close any open form so the list is fully visible: tap the form Cancel if present
$cn = FindN "Cancel" 0
if ($cn) { $cb = Bnd $cn; Say ("tap Cancel @{0},{1}" -f $cb['cx'],$cb['cy']); TapXY $cb['cx'] $cb['cy']; Start-Sleep -Milliseconds 600 }
$x = Cap "c4_census01"
Say "XML=$x"
$null = IsANR
Say "---- ALL y>800 ----"
foreach ($n in (Nodes)) { $b = Bnd $n; if (-not $b) { continue }
    if ($b['t'] -lt 800) { continue }
    $t = TxtOf $n; if (-not $t) { continue }
    Say ("  [{0},{1}][{2},{3}] {5} cl={6} | {4}" -f $b['l'],$b['t'],$b['r'],$b['b'],$t,$n.GetAttribute("class"),$n.GetAttribute("clickable")) }
