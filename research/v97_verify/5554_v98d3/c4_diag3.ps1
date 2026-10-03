. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4lib.ps1"
InitDir
# tap the FORM's own Cancel (form is the only card open, no confirm bar armed)
$cn = FindN "Cancel" 0
if ($cn) { $cb = Bnd $cn; Say ("form Cancel @{0},{1}" -f $cb['cx'],$cb['cy']); TapXY $cb['cx'] $cb['cy'] }
$x = Cap "c4_diag03_after_formclose"
Say "XML=$x"
$null = IsANR
Say "---- ALL y>500 ----"
foreach ($n in (Nodes)) {
  $b = Bnd $n; if (-not $b) { continue }
  if ($b['t'] -lt 500) { continue }
  $t = TxtOf $n
  Say ("  [{0},{1}][{2},{3}] {5} cl={6} | {4}" -f $b['l'],$b['t'],$b['r'],$b['b'],$t,$n.GetAttribute("class"),$n.GetAttribute("clickable"))
}
