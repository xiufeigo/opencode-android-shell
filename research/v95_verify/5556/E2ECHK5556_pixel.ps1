param([string]$Png, [int[]]$Points)
# Sample exact pixel colours at given x,y points (RGB hex)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$b = [System.Drawing.Bitmap]::FromFile($Png)
for ($i = 0; $i -lt $Points.Count; $i += 2) {
  $x = $Points[$i]; $y = $Points[$i + 1]
  if ($x -ge 0 -and $y -ge 0 -and $x -lt $b.Width -and $y -lt $b.Height) {
    $c = $b.GetPixel($x, $y)
    "({0},{1}) = #{2:X2}{3:X2}{4:X2}" -f $x, $y, $c.R, $c.G, $c.B
  }
}
$b.Dispose()

