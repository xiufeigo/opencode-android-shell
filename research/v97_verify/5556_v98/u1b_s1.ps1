. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

Write-Output "=== LAUNCH ==="
Ado "am start -n com.xiufeigo.opencodeshell/.OcActivity" | Out-Null
Start-Sleep -Seconds 4
Write-Output "FOCUS=$(FocusNow)"

# go Home
$f = DumpIt "01_home"
if (TapOn $f 'Home') { Write-Output "HOME_TAPPED" } else { Write-Output "HOME_NOT_FOUND" }
Start-Sleep -Seconds 2
$f = DumpIt "02_homeview"
Write-Output "--- HOME visible texts ---"
foreach ($n in (GetNodes $f)) { if ($n.Txt -and $n.W -gt 0 -and $n.Txt.Length -lt 45) { Write-Output ("  [{0},{1}][{2},{3}] '{4}'" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.Txt) } }
ShotIt "02_homeview" | Out-Null
Write-Output "FOCUS2=$(FocusNow)"
