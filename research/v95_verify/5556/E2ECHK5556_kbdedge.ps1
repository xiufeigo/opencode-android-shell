param([string]$Src, [int]$X = 300, [int]$Y1 = 300, [int]$Y2 = 380)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$full = "C:\sensorsdata\main\program\opencode-android-shell\research\v95_verify\5556\$Src.png"
$bm = [System.Drawing.Bitmap]::FromFile($full)
"Src=$Src  size=$($bm.Width)x$($bm.Height)  probeX=$X"
"--- column x=$X rows $Y1..$Y2 ---"
for ($y = $Y1; $y -le $Y2; $y++) {
  $p = $bm.GetPixel($X, $y)
  "y={0,4}  #{1:X2}{2:X2}{3:X2}" -f $y, $p.R, $p.G, $p.B
}
$bm.Dispose()
