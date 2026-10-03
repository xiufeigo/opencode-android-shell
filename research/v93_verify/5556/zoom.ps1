param([string]$Src, [string]$Out, [int]$X = 0, [int]$Y = 0, [int]$W = 100, [int]$H = 50, [int]$Zoom = 3)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$b = [System.Drawing.Bitmap]::FromFile($Src)
$rect = New-Object System.Drawing.Rectangle($X, $Y, $W, $H)
$crop = $b.Clone($rect, $b.PixelFormat)
$big = New-Object System.Drawing.Bitmap(($W * $Zoom), ($H * $Zoom))
$g = [System.Drawing.Graphics]::FromImage($big)
$g.InterpolationMode = 'NearestNeighbor'
$g.PixelOffsetMode = 'Half'
$g.DrawImage($crop, 0, 0, ($W * $Zoom), ($H * $Zoom))
$g.Dispose()
$big.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$big.Dispose(); $crop.Dispose(); $b.Dispose()
"ZOOM $Out  src($X,$Y ${W}x${H}) x$Zoom"
