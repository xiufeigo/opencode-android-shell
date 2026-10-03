$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134\tk.ps1"
function L { param($m) Write-Output "$(Get-Date -Format 'HH:mm:ss') $m" }

# 1) 显式重设 wm 回 1366x768 / 160
& $Adb -s $Serial shell wm size 1366x768 | Out-Null
& $Adb -s $Serial shell wm density 160 | Out-Null
Start-Sleep -Seconds 2
L "wm: $((& $Adb -s $Serial shell wm size) -join ' | ')"
L "wm: $((& $Adb -s $Serial shell wm density) -join ' | ')"

# 2) Stop 当前回合
$d = DumpUI "n01"; $sp = DescCenter $d.Path "Stop"
L "stopBtn=$($sp -join ',') stop=$(DescCount $d.Path 'Stop') work=$(DescCount $d.Path 'Working')"
if($sp){ & $Adb -s $Serial shell input tap $sp[0] $sp[1] | Out-Null; Start-Sleep -Seconds 2 }

# 3) 清空 composer（自建文本不留）
$d = DumpUI "n02"; $e = EditCenter $d.Path
if($e -and $e[3] -ne ''){
  & $Adb -s $Serial shell input tap $e[0] $e[1] | Out-Null; Start-Sleep -Milliseconds 300
  & $Adb -s $Serial shell input keyevent 123 | Out-Null
  & $Adb -s $Serial shell "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48 49 50 51 52 53 54 55 56 57 58 59 60 61 62 63 64 65 66 67 68 69 70; do input keyevent 67; done" | Out-Null
  Start-Sleep -Milliseconds 600
}
$d = DumpUI "n03"
L "after stop: stop=$(DescCount $d.Path 'Stop') work=$(DescCount $d.Path 'Working') composer='$(ComposerText $d.Path)'"
L "queue panel: Steer=$(TextCount $d.Path 'Steer') Send=$(TextCount $d.Path 'Send') Rem=$(DescCount $d.Path 'Remove')"
ShotV2 "n03_after_stop" | Out-Null

# 4) 残留盘点
L "--- /sdcard 新增残留自检（本轮用 exec-out，预期 0 新增）---"
$sd = (& $Adb -s $Serial shell "ls -1 /sdcard/" 2>$null)
$mine = $sd | Where-Object { $_ -match 'u1v134|v134|_p\d\d|c07|e0|h600|k600|m768|tmp_' }
L ("本轮相关命名残留: " + $(if($mine){($mine -join ', ')}else{'NONE'}))
$cnt = ($sd | Measure-Object).Count
L "/sdcard 顶层条目总数 = $cnt"
$sd | Out-File "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134\_sdcard_list.txt" -Encoding utf8
