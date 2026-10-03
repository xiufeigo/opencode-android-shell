param([string]$A, [string]$B, [int]$X0 = 0, [int]$Y0 = 0, [int]$X1 = 1365, [int]$Y1 = 719)
# Region pixel diff between two PNGs (authoritative screencap comparison)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$ba = [System.Drawing.Bitmap]::FromFile($A)
$bb = [System.Drawing.Bitmap]::FromFile($B)
if ($ba.Width -ne $bb.Width -or $ba.Height -ne $bb.Height) { "SIZE MISMATCH $($ba.Width)x$($ba.Height) vs $($bb.Width)x$($bb.Height)" }
$diff = 0; $maxd = 0; $n = 0; $rows = @{}
for ($y = $Y0; $y -le $Y1; $y++) {
  for ($x = $X0; $x -le $X1; $x++) {
    $ca = $ba.GetPixel($x, $y); $cb = $bb.GetPixel($x, $y)
    $d = [Math]::Abs([int]$ca.R - [int]$cb.R) + [Math]::Abs([int]$ca.G - [int]$cb.G) + [Math]::Abs([int]$ca.B - [int]$cb.B)
    $n++
    if ($d -gt 12) { $diff++; if (-not $rows.ContainsKey($y)) { $rows[$y] = 0 }; $rows[$y]++ }
    if ($d -gt $maxd) { $maxd = $d }
  }
}
"REGION x=$X0..$X1 y=$Y0..$Y1  pixels=$n  diffpx(>12)=$diff  ($([Math]::Round(100.0*$diff/$n,3))%)  maxsum=$maxd"
if ($rows.Count -gt 0) {
  "diff rows (y:count): " + (($rows.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Key):$($_.Value)" }) -join ' ')
} else { "diff rows: none" }
$ba.Dispose(); $bb.Dispose()

