param([string]$Path, [int]$X0, [int]$Y0, [int]$X1, [int]$Y1, [int]$Thresh = 120)
Add-Type -AssemblyName System.Drawing
$bmp = [System.Drawing.Bitmap]::FromFile($Path)
"FILE $(Split-Path $Path -Leaf)  rows $($Y0)..$($Y1)  thresh=$Thresh"
for ($y = $Y0; $y -le $Y1; $y++) {
  $light = 0
  for ($x = $X0; $x -le $X1; $x++) {
    $c = $bmp.GetPixel($x, $y)
    $lum = 0.299*$c.R + 0.587*$c.G + 0.114*$c.B
    if ($lum -gt $Thresh) { $light++ }
  }
  $bar = '#' * [Math]::Min(60, $light)
  "{0,4} : {1,4} {2}" -f $y, $light, $bar
}
$bmp.Dispose()
