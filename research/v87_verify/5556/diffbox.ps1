Add-Type -AssemblyName System.Drawing
$ia = [System.Drawing.Bitmap]::FromFile($args[0]); $ib = [System.Drawing.Bitmap]::FromFile($args[1])
$xs=@(); $ys=@()
for ($y=680; $y -lt 720; $y++) { for ($x=0; $x -lt $ia.Width; $x++) {
  $ca=$ia.GetPixel($x,$y); $cb=$ib.GetPixel($x,$y)
  if ($ca.R -ne $cb.R -or $ca.G -ne $cb.G -or $ca.B -ne $cb.B) { $xs+=$x; $ys+=$y } } }
"diff bbox: x $($xs | Measure-Object -Min -Max | ForEach-Object { "$($_.Minimum)..$($_.Maximum)" })  y $($ys | Measure-Object -Min -Max | ForEach-Object { "$($_.Minimum)..$($_.Maximum)" })"
$x0=($xs | Measure-Object -Min).Minimum; $x1=($xs | Measure-Object -Max).Maximum; $y0=($ys | Measure-Object -Min).Minimum
foreach($p in @(@($x0,$y0),@(($x0+$x1)/2,$y0),@($x1,$y0))){
  $ca=$ia.GetPixel([int]$p[0],[int]$p[1]); $cb=$ib.GetPixel([int]$p[0],[int]$p[1])
  "at $($p[0]),$($p[1])  v87=#{0:X2}{1:X2}{2:X2}  v86=#{3:X2}{4:X2}{5:X2}" -f $ca.R,$ca.G,$ca.B,$cb.R,$cb.G,$cb.B }
$ia.Dispose(); $ib.Dispose()
