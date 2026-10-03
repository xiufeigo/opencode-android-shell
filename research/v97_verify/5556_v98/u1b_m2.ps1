. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

# SCROLL the queue bar internally (swipe up inside the queue container), then re-measure.
# Coordinates parsed at runtime from the pre-scroll dump.
$pre = DumpIt "20_prescroll"
$ns = GetNodes $pre
Write-Output "=== BEFORE SCROLL ==="
$m0 = Measure $pre "B-before-internal-scroll"

# locate queue container = scrollable that is NOT the wide timeline
$tl = $ns | Where-Object { $_.Scroll -eq 'true' -and $_.W -gt 800 } | Select-Object -First 1
$qc = $ns | Where-Object { $_.Scroll -eq 'true' -and $_.W -le 800 -and $_.H -gt 0 } | Sort-Object { $_.H } -Descending | Select-Object -First 1
if ($qc) {
    $cx = [int](($qc.X1 + $qc.X2) / 2)
    Write-Output "QUEUE_CONTAINER=[$($qc.X1),$($qc.Y1)][$($qc.X2),$($qc.Y2)] scroll=$($qc.Scroll) -> swipe at x=$cx"
    $y0 = [int]($qc.Y1 + 8); $y1 = [int]($qc.Y2 - 8)
    if ($y1 -le $y0) { $y0 = [int]$qc.Y1 + 4; $y1 = [int]$qc.Y2 - 4 }
    Ado "input swipe $cx $y0 $cx $y1 400" | Out-Null
    Start-Sleep -Seconds 2
} else { Write-Output "NO_QUEUE_CONTAINER_SCROLLABLE" }

$f = DumpIt "21_postscroll"
Write-Output "=== AFTER SCROLL ==="
$m1 = Measure $f "C-after-internal-scroll"
ShotIt "21_postscroll" | Out-Null

# second scroll deeper
$ns2 = GetNodes $f
$qc2 = $ns2 | Where-Object { $_.Scroll -eq 'true' -and $_.W -le 800 -and $_.H -gt 0 } | Sort-Object { $_.H } -Descending | Select-Object -First 1
if ($qc2) {
    $cx = [int](($qc2.X1 + $qc2.X2) / 2)
    $y0 = [int]($qc2.Y1 + 8); $y1 = [int]($qc2.Y2 - 8)
    if ($y1 -le $y0) { $y0 = [int]$qc2.Y1 + 4; $y1 = [int]$qc2.Y2 - 4 }
    Ado "input swipe $cx $y0 $cx $y1 400" | Out-Null
    Start-Sleep -Seconds 2
}
$f3 = DumpIt "22_scroll2"
Write-Output "=== AFTER SCROLL x2 ==="
$m2 = Measure $f3 "D-after-second-scroll"
ShotIt "22_scroll2" | Out-Null
