param([string]$Path, [int]$X0, [int]$Y0, [int]$X1, [int]$Y1)
Add-Type -AssemblyName System.Drawing
$bmp = [System.Drawing.Bitmap]::FromFile($Path)
$h = @{}
for ($y = $Y0; $y -le $Y1; $y++) {
  for ($x = $X0; $x -le $X1; $x++) {
    $c = $bmp.GetPixel($x, $y)
    $k = "{0:X2}{1:X2}{2:X2}" -f $c.R, $c.G, $c.B
    if ($h.ContainsKey($k)) { $h[$k]++ } else { $h[$k] = 1 }
  }
}
"FILE : $(Split-Path $Path -Leaf)   REGION [$X0,$Y0]-[$X1,$Y1]"
$h.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 12 | ForEach-Object { "  #{0}  x{1}" -f $_.Key, $_.Value }
$bmp.Dispose()
