. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

# New session (from live dump coordinates)
Ado "input tap 1185 248" | Out-Null; Start-Sleep -Seconds 2
$f = DumpIt "03_newses"
Write-Output "--- NEW SESSION sheet ---"
foreach ($n in (GetNodes $f)) { if ($n.Txt -and $n.W -gt 0 -and $n.Txt.Length -lt 45) { Write-Output ("  [{0},{1}][{2},{3}] '{4}'" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.Txt) } }

# pick the OpenCode project card: from dump, find card with 'opencode-android-shell' if present, else use known picker coords
$proj = $null
foreach ($n in (GetNodes $f)) { if ($n.Txt -like '*opencode-android-shell*' -and $n.W -gt 60) { $proj = $n; break } }
if ($proj) {
    $cx = [int](($proj.X1 + $proj.X2) / 2); $cy = [int](($proj.Y1 + $proj.Y2) / 2)
    Write-Output "TAP_PROJ at $cx,$cy"
    Ado "input tap $cx $cy" | Out-Null; Start-Sleep -Seconds 3
} else { Write-Output "PROJ_NOT_FOUND" }
$f2 = DumpIt "04_afterproj"
Write-Output "FOCUS=$(FocusNow)"
Write-Output "--- after project select ---"
foreach ($n in (GetNodes $f2)) { if ($n.Txt -and $n.W -gt 0 -and $n.Txt.Length -lt 50) { Write-Output ("  [{0},{1}][{2},{3}] '{4}'" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.Txt) } }
ShotIt "04_afterproj" | Out-Null
