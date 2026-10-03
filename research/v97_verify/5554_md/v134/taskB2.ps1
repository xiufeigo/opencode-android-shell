$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134\tk.ps1"
function L { param($m) Write-Output "$(Get-Date -Format 'HH:mm:ss.fff') $m" }
function QuickClear {
  $d = DumpUI "tmp_c"; $e = EditCenter $d.Path
  if($e){ & $Adb -s $Serial shell input tap $e[0] $e[1] | Out-Null; Start-Sleep -Milliseconds 300 }
  & $Adb -s $Serial shell input keyevent 123 | Out-Null
  & $Adb -s $Serial shell "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48 49 50 51 52 53 54 55 56 57 58 59 60 61 62 63 64 65 66 67 68 69 70; do input keyevent 67; done" | Out-Null
  Start-Sleep -Milliseconds 400
}
L "600 现状: $((& $Adb -s $Serial shell wm size) -join ' ') ime=$(ImeShownV2)"
QuickClear
$d = DumpUI "k600_01"; $e = EditCenter $d.Path
& $Adb -s $Serial shell input tap $e[0] $e[1] | Out-Null; Start-Sleep -Milliseconds 300
& $Adb -s $Serial shell "input text 'E2ECHK5554u1v134%scount%sfrom%s1%sto%s900000,%sone%snumber%sper%sline,%snever%sstop'" | Out-Null
Start-Sleep -Milliseconds 700
$d = DumpUI "k600_02"; $s = DescCenter $d.Path "Send"
& $Adb -s $Serial shell input tap $s[0] $s[1] | Out-Null
Start-Sleep -Seconds 4
$d = DumpUI "k600_03"; L "RUNNING stop=$(DescCount $d.Path 'Stop') work=$(DescCount $d.Path 'Working')"
$e = EditCenter $d.Path
& $Adb -s $Serial shell input tap $e[0] $e[1] | Out-Null; Start-Sleep -Milliseconds 300
& $Adb -s $Serial shell "input text 'E2ECHK5554u1v134%sparkeps600b'" | Out-Null
Start-Sleep -Milliseconds 900
$d = DumpUI "k600_04"; $q = TextCenter $d.Path "Queue"
L "QueueChip=$($q -join ',') composer='$(ComposerText $d.Path)'"
if($q){ & $Adb -s $Serial shell input tap $q[0] $q[1] | Out-Null; Start-Sleep -Milliseconds 1400 }
$d = DumpUI "k600_05_panelon"
L "PANELON Steer=$(TextCount $d.Path 'Steer') Send=$(TextCount $d.Path 'Send') Rem=$(DescCount $d.Path 'Remove') ime=$(ImeShownV2) work=$(DescCount $d.Path 'Working')"
ShowQueue $d.Path "panelon600"
