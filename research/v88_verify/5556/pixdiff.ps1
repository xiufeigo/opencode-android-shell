param([string]$A, [string]$B)
Add-Type -AssemblyName System.Drawing
$ia = [System.Drawing.Bitmap]::FromFile($A)
$ib = [System.Drawing.Bitmap]::FromFile($B)
"WxH  A=$($ia.Width)x$($ia.Height)  B=$($ib.Width)x$($ib.Height)"
$diff = 0
$firstRows = New-Object System.Collections.Generic.List[string]
for ($y = 0; $y -lt $ia.Height; $y++) {
  $rowDiff = 0
  for ($x = 0; $x -lt $ia.Width; $x++) {
    $ca = $ia.GetPixel($x, $y); $cb = $ib.GetPixel($x, $y)
    if ($ca.R -ne $cb.R -or $ca.G -ne $cb.G -or $ca.B -ne $cb.B) { $rowDiff++ }
  }
  if ($rowDiff -gt 0) { $diff += $rowDiff; if ($firstRows.Count -lt 12) { $firstRows.Add("y=$y diff=$rowDiff") } }
}
"TOTAL_DIFF_PIXELS = $diff"
if ($diff -gt 0) { "ROWS: " + ($firstRows -join "  ") } else { "IMAGES ARE PIXEL-IDENTICAL" }
$ia.Dispose(); $ib.Dispose()
