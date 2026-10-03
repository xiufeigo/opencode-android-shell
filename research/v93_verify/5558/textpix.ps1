param([string]$Path, [int]$X0, [int]$Y0, [int]$X1, [int]$Y1, [int]$Thresh = 150)
Add-Type -AssemblyName System.Drawing
$bmp = [System.Drawing.Bitmap]::FromFile($Path)
$total = 0
$detail = New-Object System.Collections.Generic.List[string]
for ($y = $Y0; $y -le $Y1; $y++) {
  $r = 0
  for ($x = $X0; $x -le $X1; $x++) {
    $c = $bmp.GetPixel($x, $y)
    $lum = 0.299*$c.R + 0.587*$c.G + 0.114*$c.B
    if ($lum -gt $Thresh) { $r++ }
  }
  $total += $r
  if ($r -gt 0) { $detail.Add("y=$y : $r") }
}
"LIGHT_PIXELS(>$Thresh) in [$X0,$Y0]-[$X1,$Y1] = $total"
"ROWS_WITH_TEXT ($($detail.Count) rows): " + ($detail -join "  ")
$bmp.Dispose()
