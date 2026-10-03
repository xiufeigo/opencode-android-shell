param([string]$A, [string]$B, [int]$X0 = 434, [int]$Y0 = 341, [int]$X1 = 914, [int]$Y1 = 636)
Add-Type -AssemblyName System.Drawing
$ba = [System.Drawing.Bitmap]::FromFile($A)
$bb = [System.Drawing.Bitmap]::FromFile($B)
$diff = 0; $tot = 0
$rows = @{}
for ($y = $Y0; $y -le $Y1; $y++) {
  for ($x = $X0; $x -le $X1; $x++) {
    $ca = $ba.GetPixel($x,$y); $cb = $bb.GetPixel($x,$y)
    $d = [Math]::Abs([int]$ca.R-[int]$cb.R) + [Math]::Abs([int]$ca.G-[int]$cb.G) + [Math]::Abs([int]$ca.B-[int]$cb.B)
    $tot++
    if ($d -gt 30) { $diff++; if (-not $rows.ContainsKey($y)) { $rows[$y]=0 }; $rows[$y]++ }
  }
}
"REGION [$X0,$Y0]-[$X1,$Y1]"
"DIFF_PIXELS(>30) = $diff / $tot  (" + ('{0:P2}' -f ($diff/$tot)) + ")"
"ROWS_WITH_DIFF ($($rows.Count)): " + (($rows.Keys | Sort-Object | Select-Object -First 30 | ForEach-Object { "y$_=$($rows[$_])" }) -join "  ")
$ba.Dispose(); $bb.Dispose()
