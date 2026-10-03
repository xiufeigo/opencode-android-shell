. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

# restore entry baseline override (REPORT_U1 discipline: reset, then restore what was there at entry)
Ado "wm size 1366x768" | Out-Null
Ado "wm density 160" | Out-Null
Start-Sleep -Seconds 3
Write-Output ("WM_SIZE: " + ((Ado "wm size") -join ' | '))
Write-Output ("WM_DENSITY: " + ((Ado "wm density") -join ' | '))
Write-Output ("IME_SETTING: " + ((Ado "settings get secure show_ime_with_hard_keyboard") -join ' '))

# delete the self-built session via Home -> More options -> Delete
Ado "am start -n com.xiufeigo.opencodeshell/.OcActivity" | Out-Null
Start-Sleep -Seconds 3
$f = DumpIt "80_home"
$h = FindTxt $f 'Home'
if ($h) { Ado "input tap $([int](($h.X1+$h.X2)/2)) $([int](($h.Y1+$h.Y2)/2))" | Out-Null; Start-Sleep -Seconds 2 }
$f2 = DumpIt "81_home"
$hits = @(GetNodes $f2 | Where-Object { $_.Txt -and $_.Txt.Contains('Android window insets') })
Write-Output "SESSION_ROWS=$($hits.Count)"
foreach ($n in $hits) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] '$($n.Txt)'") }
ShotIt "81_home" | Out-Null
