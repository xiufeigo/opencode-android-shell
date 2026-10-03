. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

# close review panel (parsed from live dump u1b_87)
$f = DumpIt "88_pre_close"
$cl = $null
foreach ($n in (GetNodes $f)) { if ($n.Txt -eq 'Close' -and $n.Y1 -ge 100 -and $n.Y1 -le 150) { $cl = $n; break } }
if ($cl) { Ado "input tap $([int](($cl.X1+$cl.X2)/2)) $([int](($cl.Y1+$cl.Y2)/2))" | Out-Null; Start-Sleep -Seconds 2 }
Write-Output "review panel closed"

# now header More options
$f2 = DumpIt "89_hdr2"
$mo = $null
foreach ($n in (GetNodes $f2)) { if ($n.Txt -eq 'More options') { $mo = $n; break } }
if (-not $mo) { Write-Output "STILL_NO_MORE"; foreach ($n in (GetNodes $f2)) { if ($n.Y1 -ge 100 -and $n.Y1 -le 170 -and $n.W -gt 4) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] '$($n.Txt)'") } }; exit 1 }
Write-Output "TAP_MORE at $([int](($mo.X1+$mo.X2)/2)),$([int](($mo.Y1+$mo.Y2)/2))"
Ado "input tap $([int](($mo.X1+$mo.X2)/2)) $([int](($mo.Y1+$mo.Y2)/2))" | Out-Null
Start-Sleep -Seconds 2
$f3 = DumpIt "90_menu"
$del = $null
foreach ($n in (GetNodes $f3)) { if ($n.Txt -eq 'Delete') { $del = $n; break } }
if (-not $del) {
    Write-Output "--- menu items (no Delete yet) ---"
    foreach ($n in (GetNodes $f3)) { if ($n.Txt -and $n.W -gt 0 -and $n.Txt.Length -lt 30) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] '$($n.Txt)'") } }
    exit 1
}
Write-Output "TAP_DELETE at $([int](($del.X1+$del.X2)/2)),$([int](($del.Y1+$del.Y2)/2))"
Ado "input tap $([int](($del.X1+$del.X2)/2)) $([int](($del.Y1+$del.Y2)/2))" | Out-Null
Start-Sleep -Seconds 2
$f4 = DumpIt "91_confirm"
Write-Output "--- confirm layer ---"
foreach ($n in (GetNodes $f4)) { if ($n.Txt -and $n.W -gt 0 -and $n.Txt.Length -lt 70) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] '$($n.Txt)'") } }
ShotIt "91_confirm" | Out-Null
