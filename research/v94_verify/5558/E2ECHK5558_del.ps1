param([int]$MenuX, [int]$MenuY, [int]$WaitMs = 1800)
$ErrorActionPreference = 'Continue'
$adb = "C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
$S = 'emulator-5558'
$p = "C:\sensorsdata\main\program\opencode-android-shell\research\v94_verify\5558\E2ECHK5558_nav.ps1"

& $adb -s $S shell input tap $MenuX $MenuY
Start-Sleep -Milliseconds $WaitMs
& $p -Dump 'E2ECHK5558_tmp' 2>&1 | Out-Null
$c = Get-Content "C:\sensorsdata\main\program\opencode-android-shell\research\v94_verify\5558\_tmp.xml" -Raw
$m = [regex]::Match($c, '<node[^>]*text="Delete"[^>]*>')
if (-not $m.Success) { "FAIL: no Delete menu item"; exit 1 }
if ($m.Value -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
  $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
  $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
  "menu Delete -> $x,$y"
  & $adb -s $S shell input tap $x $y
  Start-Sleep -Milliseconds $WaitMs
} else { "FAIL: no bounds"; exit 1 }

& $p -Dump 'E2ECHK5558_tmp' 2>&1 | Out-Null
$c = Get-Content "C:\sensorsdata\main\program\opencode-android-shell\research\v94_verify\5558\_tmp.xml" -Raw
$d = [regex]::Match($c, '<node[^>]*text="Delete SSH server\?[^"]*"[^>]*>')
if (-not $d.Success) { "FAIL: no confirm dialog"; exit 1 }
$all = [regex]::Matches($c, '<node[^>]*text="Delete"[^>]*>')
$btn = $null
foreach ($n in $all) { if ($n.Value -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' -and [int]$Matches[2] -gt 1400) { $btn = $n; break } }
if ($btn -and $btn.Value -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
  $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
  $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
  "confirm Delete -> $x,$y"
  & $adb -s $S shell input tap $x $y
  Start-Sleep -Milliseconds 2200
  "DONE"
} else { "FAIL: no confirm button"; exit 1 }
