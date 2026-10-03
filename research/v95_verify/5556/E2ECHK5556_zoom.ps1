param([string]$Src, [string]$Dst, [int]$X, [int]$Y, [int]$W, [int]$H, [int]$Scale = 3)
# Crop a region and scale it up (nearest-neighbour) for legible evidence
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$bmpIn = [System.Drawing.Bitmap]::FromFile($Src)
$crop = New-Object System.Drawing.Bitmap $W, $H
$g = [System.Drawing.Graphics]::FromImage($crop)
$g.DrawImage($bmpIn, (New-Object System.Drawing.Rectangle 0, 0, $W, $H), (New-Object System.Drawing.Rectangle $X, $Y, $W, $H), [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()
$out = New-Object System.Drawing.Bitmap ($W * $Scale), ($H * $Scale)
$g2 = [System.Drawing.Graphics]::FromImage($out)
$g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g2.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$g2.DrawImage($crop, 0, 0, $W * $Scale, $H * $Scale)
$g2.Dispose()
$out.Save($Dst, [System.Drawing.Imaging.ImageFormat]::Png)
"ZOOM $Dst  src=($X,$Y ${W}x${H}) scale=${Scale} -> $((Get-Item $Dst).Length) bytes"
$out.Dispose(); $crop.Dispose(); $bmpIn.Dispose()

