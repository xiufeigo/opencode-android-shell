param([string]$Path, [int]$Y0, [int]$Y1, [int]$Thresh = 248, [string]$Mode = 'white')
# E2ECHK5558 keyboard-top-edge scanner.
# The Gboard panel is a near-white full-width band; below its top edge the row is
# almost entirely dark (keys). We look for the sharp transition.
Add-Type -AssemblyName System.Drawing
$bmp = [System.Drawing.Bitmap]::FromFile($Path)
"FILE $(Split-Path $Path -Leaf)  rows $Y0..$Y1  mode=$Mode  thresh=$Thresh"
$prev = $null
for ($y = $Y0; $y -le $Y1; $y++) {
    $hit = 0
    for ($x = 100; $x -le 980; $x += 2) {
        $c = $bmp.GetPixel($x, $y)
        $lum = 0.299 * $c.R + 0.587 * $c.G + 0.114 * $c.B
        if ($Mode -eq 'white') { if ($lum -gt $Thresh) { $hit++ } } else { if ($lum -lt $Thresh) { $hit++ } }
    }
    $flag = ''
    if ($null -ne $prev -and [Math]::Abs($hit - $prev) -gt 200) { $flag = '   <<< TRANSITION' }
    "{0,4} : {1,4} {2}{3}" -f $y, $hit, ('#' * [Math]::Min(60, [int]($hit / 15))), $flag
    $prev = $hit
}
$bmp.Dispose()
