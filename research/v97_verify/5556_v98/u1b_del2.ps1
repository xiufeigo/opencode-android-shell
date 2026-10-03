. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

# in-session More options (parsed from live dump u1b_83)
$f = DumpIt "84_pre_menu"
$mo = $null
foreach ($n in (GetNodes $f)) { if ($n.Txt -eq 'More options' -and $n.Y1 -ge 100 -and $n.Y1 -le 170) { $mo = $n; break } }
if (-not $mo) { Write-Output "NO_MORE_OPTIONS"; exit 1 }
Write-Output "TAP_MORE at $([int](($mo.X1+$mo.X2)/2)),$([int](($mo.Y1+$mo.Y2)/2))"
Ado "input tap $([int](($mo.X1+$mo.X2)/2)) $([int](($mo.Y1+$mo.Y2)/2))" | Out-Null
Start-Sleep -Seconds 2
$f2 = DumpIt "85_menu"
Write-Output "--- menu items ---"
foreach ($n in (GetNodes $f2)) { if ($n.Txt -and $n.W -gt 0 -and $n.Txt.Length -lt 30) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] '$($n.Txt)'") } }
# tap Delete if present
$del = $null
foreach ($n in (GetNodes $f2)) { if ($n.Txt -eq 'Delete' -or $n.Txt -like '*Delete*') { $del = $n; break } }
if ($del) {
    Write-Output "TAP_DELETE at $([int](($del.X1+$del.X2)/2)),$([int](($del.Y1+$del.Y2)/2))"
    Ado "input tap $([int](($del.X1+$del.X2)/2)) $([int](($del.Y1+$del.Y2)/2))" | Out-Null
    Start-Sleep -Seconds 2
    $f3 = DumpIt "86_confirm"
    Write-Output "--- confirm layer ---"
    foreach ($n in (GetNodes $f3)) { if ($n.Txt -and $n.W -gt 0 -and $n.Txt.Length -lt 60) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] '$($n.Txt)'") } }
    ShotIt "86_confirm" | Out-Null
} else { Write-Output "NO_DELETE_ITEM"; ShotIt "85_menu" | Out-Null }
