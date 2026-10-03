param([string]$Png, [string]$Mode = 'vscan', [int]$X = 200, [int]$Y1 = 0, [int]$Y2 = 2151, [int]$X1 = 0, [int]$X2 = 0, [int]$Thr = 110)
# Pixel probe for E2ECHK5560 verification. Modes:
#   vscan  : walk a column top->bottom, print the rows where the colour changes (band edges)
#   bright : count pixels with luminance > Thr inside the rect (text / filled button pixels)
#   rowdark: count dark pixels per row inside the rect (one row per line, for wrap detection)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$dir = 'C:\sensorsdata\main\program\opencode-android-shell\research\v93_verify\5560'
$path = if ($Png -like '*.*') { $Png } else { Join-Path $dir "$Png.png" }
$bmp = [System.Drawing.Bitmap]::FromFile($path)
"IMG $($bmp.Width)x$($bmp.Height) $path"

function Lum($c) { [int](0.299 * $c.R + 0.587 * $c.G + 0.114 * $c.B) }

if ($Mode -eq 'vscan') {
  $prev = $null
  for ($y = $Y1; $y -le $Y2; $y++) {
    $c = $bmp.GetPixel($X, $y)
    if ($null -eq $prev) { $prev = $c; "y=$y $($c.R),$($c.G),$($c.B)"; continue }
    $d = [Math]::Abs($c.R - $prev.R) + [Math]::Abs($c.G - $prev.G) + [Math]::Abs($c.B - $prev.B)
    if ($d -ge 24) { "y=$y $($c.R),$($c.G),$($c.B) (d=$d)" }
    $prev = $c
  }
}
elseif ($Mode -eq 'bright') {
  $n = 0; $max = 0
  for ($y = $Y1; $y -le $Y2; $y++) { for ($x = $X1; $x -le $X2; $x++) { $l = Lum($bmp.GetPixel($x, $y)); if ($l -gt $Thr) { $n++ }; if ($l -gt $max) { $max = $l } } }
  "BRIGHT rect=[$X1,$Y1]-[$X2,$Y2] thr=$Thr count=$n maxLum=$max"
}
elseif ($Mode -eq 'rowdark') {
  for ($y = $Y1; $y -le $Y2; $y++) {
    $n = 0
    for ($x = $X1; $x -le $X2; $x++) { if ((Lum($bmp.GetPixel($x, $y))) -lt $Thr) { $n++ } }
    if ($n -gt 0) { "y=$y dark=$n" }
  }
}
$bmp.Dispose()
