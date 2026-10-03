param([string]$Path, [int]$X0, [int]$Y0, [int]$X1, [int]$Y1)
Add-Type -AssemblyName System.Drawing
$bmp = [System.Drawing.Bitmap]::FromFile($Path)
$red = 0; $redRows = @{}
$best = ''
$maxSat = 0
for ($y = $Y0; $y -le $Y1; $y++) {
  for ($x = $X0; $x -le $X1; $x++) {
    $c = $bmp.GetPixel($x, $y)
    $mx = [Math]::Max($c.R, [Math]::Max($c.G, $c.B))
    $mn = [Math]::Min($c.R, [Math]::Min($c.G, $c.B))
    $sat = if ($mx -eq 0) { 0 } else { ($mx - $mn) / $mx }
    # red-dominant: R clearly above G and B
    if (($c.R - $c.G) -gt 30 -and ($c.R - $c.B) -gt 30) {
      $red++
      if (-not $redRows.ContainsKey($y)) { $redRows[$y] = 0 }
      $redRows[$y]++
      if ($sat -gt $maxSat) { $maxSat = $sat; $best = "x=$x y=$y R=$($c.R) G=$($c.G) B=$($c.B)" }
    }
  }
}
"RED_PIXELS in [$X0,$Y0]-[$X1,$Y1] = $red"
"ROWS_WITH_RED ($($redRows.Count)): " + (($redRows.Keys | Sort-Object | ForEach-Object { "y$_=$($redRows[$_])" }) -join "  ")
"STRONGEST: $best"
$bmp.Dispose()
