param([string]$Path, [string]$ProbeX, [string]$ProbeY)
Add-Type -AssemblyName System.Drawing
$bmp = [System.Drawing.Bitmap]::FromFile($Path)
"Size: $($bmp.Width)x$($bmp.Height)"
$xs = $ProbeX.Split(',') | ForEach-Object { [int]$_ }
$ys = $ProbeY.Split(',') | ForEach-Object { [int]$_ }
foreach ($y in $ys) {
  $parts = @()
  foreach ($x in $xs) {
    $c = $bmp.GetPixel($x, $y)
    $parts += ("#{0:X2}{1:X2}{2:X2}" -f $c.R, $c.G, $c.B)
  }
  ("y={0,-4} " -f $y) + ($parts -join " ")
}
"      x= " + (($xs | ForEach-Object { "{0,-6}" -f $_ }) -join "")
$bmp.Dispose()
