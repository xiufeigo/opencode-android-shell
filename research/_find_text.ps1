param([string]$Path)
Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Image]::FromFile((Resolve-Path $Path))
$bmp = New-Object System.Drawing.Bitmap($img)
for ($y = 600; $y -lt 760; $y += 2) {
    for ($x = 0; $x -lt 400; $x += 4) {
        $c = $bmp.GetPixel($x, $y)
        if ($c.R -lt 100 -and $c.G -lt 100 -and $c.B -lt 100) {
            Write-Output ("({0},{1}): dark R={2}" -f $x, $y, $c.R)
            break
        }
    }
}
