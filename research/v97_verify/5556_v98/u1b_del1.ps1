. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

# long-press / menu the session row in Home list
$f = DumpIt "82_row"
$rows = @(GetNodes $f | Where-Object { $_.Txt -eq 'Android window insets: detailed guide in 30+ points' -and $n.Y1 -gt 200 })
$row = $null
foreach ($n in (GetNodes $f)) { if ($n.Txt -eq 'Android window insets: detailed guide in 30+ points' -and $n.Y1 -gt 200) { $row = $n; break } }
if (-not $row) {
    foreach ($n in (GetNodes $f)) { if ($n.Txt -eq 'Android window insets: detailed guide in 30+ points') { $row = $n } }
}
Write-Output "ROW=[$($row.X1),$($row.Y1)][$($row.X2),$($row.Y2)]"
# long press to open row menu
$cx = [int](($row.X1 + $row.X2) / 2); $cy = [int](($row.Y1 + $row.Y2) / 2)
Ado "input swipe $cx $cy $cx $cy 900" | Out-Null
Start-Sleep -Seconds 2
$f2 = DumpIt "83_rowmenu"
Write-Output "--- menu candidates ---"
foreach ($n in (GetNodes $f2)) { if ($n.Txt -and $n.W -gt 0 -and $n.Txt.Length -lt 30) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] '$($n.Txt)'") } }
ShotIt "83_rowmenu" | Out-Null
