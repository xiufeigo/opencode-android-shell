param([string]$Path, [string]$Mode, [int]$At, [int]$From, [int]$To, [int]$MinRun = 4)
Add-Type -AssemblyName System.Drawing
$bmp = [System.Drawing.Bitmap]::FromFile($Path)
$isCol = ($Mode -eq 'col')
$pos = $From
$prev = $null
$start = $From
for ($i = $From; $i -le $To; $i++) {
  $x = $i; $y = $At
  if ($isCol) { $x = $At; $y = $i }
  $c = $bmp.GetPixel($x, $y)
  $key = "{0:X2}{1:X2}{2:X2}" -f $c.R, $c.G, $c.B
  if ($key -ne $prev) {
    if ($prev -ne $null) {
      $len = $i - $start
      if ($len -ge $MinRun) { ("{0}={1,-6} len={2,-5} #{3}" -f $pos, $start, $len, $prev) }
    }
    $prev = $key; $start = $i
  }
}
$len = $To - $start + 1
if ($len -ge $MinRun) { ("{0}={1,-6} len={2,-5} #{3}" -f $pos, $start, $len, $prev) }
$bmp.Dispose()
