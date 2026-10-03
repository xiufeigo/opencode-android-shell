# F14 step 3: scroll the form body UP to re-verify host/name survived, then report.
# v97b lesson honoured: pick the smallest ScrollView that CONTAINS a form anchor and
# start the swipe on a y-band covered by NO EditText (never on a field, never the footer).
. "$PSScriptRoot\f14lib.ps1"

function FindFormScroll([string]$xmlPath) {
    [xml]$x = Get-Content $xmlPath -Raw
    $edits = @()
    foreach ($n in $x.SelectNodes('//node')) {
        if ($n.class -ne 'android.widget.EditText') { continue }
        if ($n.bounds -match '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') {
            $edits += , @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
        }
    }
    $best = $null
    foreach ($n in $x.SelectNodes('//node')) {
        if ($n.class -ne 'androidx.core.widget.NestedScrollView' -and $n.class -notlike '*ScrollView*') { continue }
        if ($n.bounds -notmatch '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') { continue }
        $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
        $contains = 0
        foreach ($e in $edits) { if ($e[0] -ge $x1 -and $e[2] -le $x2 -and $e[1] -ge $y1 -and $e[3] -le $y2) { $contains++ } }
        if ($contains -eq 0) { continue }
        $area = ($x2 - $x1) * ($y2 - $y1)
        if (-not $best -or $area -lt $best.Area) {
            $best = [pscustomobject]@{ X1 = $x1; Y1 = $y1; X2 = $x2; Y2 = $y2; Area = $area; Contains = $contains }
        }
    }
    return $best
}

$xml = Dump 'F14_pre_scroll'
ShowEdits $xml 'before scroll up'
$sc = FindFormScroll $xml
if (-not $sc) { Write-Host 'NO FORM SCROLLVIEW FOUND'; exit 1 }
Write-Host "form ScrollView = [$($sc.X1),$($sc.Y1)][$($sc.X2),$($sc.Y2)] contains $($sc.Contains) EditTexts"

# find a y band inside the scrollview that overlaps NO EditText
$eds = EditTexts $xml
$used = $eds | Where-Object { $_.X1 -ge $sc.X1 -and $_.X2 -le $sc.X2 -and $_.Y1 -ge $sc.Y1 -and $_.Y2 -le $sc.Y2 }
$usedY = ($used | ForEach-Object { $_.Y1..$_.Y2 }) | Sort-Object -Unique
$band = $null
for ($y = $sc.Y1 + 8; $y -le $sc.Y2 - 8; $y++) {
    if ($usedY -notcontains $y) { $band = $y; break }
}
if (-not $band) { Write-Host 'no free y band inside scrollview'; exit 1 }
Write-Host "free swipe band y=$band ; scrolling UP (content down) 120px"
$cx = [int](($sc.X1 + $sc.X2) / 2)
# fast swipe: ~120px over 80ms
SwipeForm $cx $band ($cx) ($band + 120) 80

$x2 = Dump 'F14_scrolled_up'
Write-Host '--- after scroll up ---'
ShowEdits $x2 'after scroll up'
Write-Host '--- labels ---'
foreach ($t in (Texts $x2)) { if ($t.Y1 -gt 600 -and $t.Y1 -lt 1800) { Write-Host ("  '{0}' @({1},{2}) [{3},{4}][{5},{6}]" -f $t.Text, $t.CX, $t.CY, $t.X1, $t.Y1, $t.X2, $t.Y2) } }
