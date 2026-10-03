. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'
function Dump2([string]$t) { for ($i=1; $i -le 3; $i++) { $p = DumpIt $t; if ($p -and (Test-Path $p) -and (Get-Item $p).Length -gt 500) { return $p }; Start-Sleep -Milliseconds 400 }; return $null }

$f = Dump2 "c01_hdr"
$mo = $null
foreach ($n in (GetNodes $f)) { if ($n.Txt -eq 'More options') { $mo = $n; break } }
if ($mo) {
    Ado "input tap $([int](($mo.X1+$mo.X2)/2)) $([int](($mo.Y1+$mo.Y2)/2))" | Out-Null
    Start-Sleep -Seconds 2
    $f2 = Dump2 "c02_menu"
    $del = $null; foreach ($n in (GetNodes $f2)) { if ($n.Txt -eq 'Delete') { $del = $n; break } }
    if ($del) {
        Ado "input tap $([int](($del.X1+$del.X2)/2)) $([int](($del.Y1+$del.Y2)/2))" | Out-Null
        Start-Sleep -Seconds 2
        $f3 = Dump2 "c03_confirm"
        $cd = $null; foreach ($n in (GetNodes $f3)) { if ($n.Txt -eq 'Delete' -and $n.Y1 -gt 400) { $cd = $n; break } }
        if ($cd) { Ado "input tap $([int](($cd.X1+$cd.X2)/2)) $([int](($cd.Y1+$cd.Y2)/2))" | Out-Null; Write-Output "CONFIRMED_DELETE"; Start-Sleep -Seconds 3 }
        else { Write-Output "NO_CONFIRM" }
    } else { Write-Output "NO_DELETE_ITEM" }
} else { Write-Output "NO_MORE_OPTIONS" }

Ado "am start -n com.xiufeigo.opencodeshell/.OcActivity" | Out-Null
Start-Sleep -Seconds 3
$f4 = Dump2 "c04_home"
$h = $null; foreach ($n in (GetNodes $f4)) { if ($n.Txt -eq 'Home') { $h = $n; break } }
if ($h) { Ado "input tap $([int](($h.X1+$h.X2)/2)) $([int](($h.Y1+$h.Y2)/2))" | Out-Null; Start-Sleep -Seconds 2 }
$f5 = Dump2 "c05_verify"
Write-Output "=== SESSIONS IN LIST ==="
foreach ($n in (GetNodes $f5)) { if ($n.Txt -and $n.W -gt 100 -and $n.Y1 -gt 200) { Write-Output ("  [$($n.X1),$($n.Y1)] '$($n.Txt)'") } }
Write-Output "=== FINAL ==="
Write-Output ((Ado "wm size") -join ' | ')
Write-Output ((Ado "wm density") -join ' | ')
Write-Output ("IME_SETTING=" + ((Ado "settings get secure show_ime_with_hard_keyboard") -join ' '))
Write-Output ("SDCARD_u1b_COUNT=" + ((Ado "ls /sdcard | grep -c u1b") -join ''))
Write-Output ("FOCUS=" + (FocusNow))
