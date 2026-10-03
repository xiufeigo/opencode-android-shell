. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

# confirm Delete (parsed from live dump u1b_91)
$f = DumpIt "92_pre_cf"
$d = $null
foreach ($n in (GetNodes $f)) { if ($n.Txt -eq 'Delete' -and $n.Y1 -gt 400) { $d = $n; break } }
if ($d) {
    Ado "input tap $([int](($d.X1+$d.X2)/2)) $([int](($d.Y1+$d.Y2)/2))" | Out-Null
    Write-Output "CONFIRM_DELETE tapped"
} else { Write-Output "NO_CONFIRM_BUTTON" }
Start-Sleep -Seconds 3

# verify gone
Ado "am start -n com.xiufeigo.opencodeshell/.OcActivity" | Out-Null
Start-Sleep -Seconds 3
$f2 = DumpIt "93_home"
$h = $null
foreach ($n in (GetNodes $f2)) { if ($n.Txt -eq 'Home') { $h = $n; break } }
if ($h) { Ado "input tap $([int](($h.X1+$h.X2)/2)) $([int](($h.Y1+$h.Y2)/2))" | Out-Null; Start-Sleep -Seconds 2 }
$f3 = DumpIt "94_verify"
$hits = @(GetNodes $f3 | Where-Object { $_.Txt -and $_.Txt.Contains('Android window insets') })
Write-Output "DELETE_VERIFY_HITS=$($hits.Count)"
foreach ($n in $hits) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] '$($n.Txt)'") }

Write-Output "=== FINAL STATE ==="
Write-Output ("WM_SIZE: " + ((Ado "wm size") -join ' | '))
Write-Output ("WM_DENSITY: " + ((Ado "wm density") -join ' | '))
Write-Output ("IME_SETTING: " + ((Ado "settings get secure show_ime_with_hard_keyboard") -join ' '))
Write-Output ("FOCUS: " + (FocusNow))
Write-Output "=== SDCARD RESIDUE (u1b_) ==="
Write-Output ((Ado "ls /sdcard | grep -c u1b") -join '')
Write-Output ((Ado "ls /sdcard | grep u1b") -join ' ')
