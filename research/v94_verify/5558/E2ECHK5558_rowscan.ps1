param([string]$Path, [int]$Y0, [int]$Y1, [int]$Thresh = 120, [string]$Mode = 'dark')
Add-Type -AssemblyName System.Drawing
$bmp = [System.Drawing.Bitmap]::FromFile($Path)
"FILE $(Split-Path $Path -Leaf) rows $Y0..$Y1 mode=$Mode thresh=$Thresh"
for ($y = $Y0; $y -le $Y1; $y++) {
  $hit = 0
  for ($x = 100; $x -le 980; $x++) {
    $c = $bmp.GetPixel($x, $y)
    $lum = 0.299*$c.R + 0.587*$c.G + 0.114*$c.B
    if ($Mode -eq 'dark') { if ($lum -lt $Thresh) { $hit++ } } else { if ($lum -gt $Thresh) { $hit++ } }
  }
  "{0,4} : {1,4} {2}" -f $y, $hit, ('#' * [Math]::Min(60, $hit))
}
$bmp.Dispose()
