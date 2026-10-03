$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134\tk.ps1"
$logf="$Ev\_B.log"; Set-Content $logf ""
function L { param($m) $s="$(Get-Date -Format 'HH:mm:ss.fff') $m"; Write-Output $s; Add-Content $logf $s }
function QuickClear {
  $d = DumpUI "tmp_bc"; $e = EditCenter $d.Path
  if($e){ & $Adb -s $Serial shell input tap $e[0] $e[1] | Out-Null; Start-Sleep -Milliseconds 300 }
  & $Adb -s $Serial shell input keyevent 123 | Out-Null
  & $Adb -s $Serial shell "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48 49 50 51 52 53 54 55 56 57 58 59 60 61 62 63 64 65 66 67 68 69 70; do input keyevent 67; done" | Out-Null
  Start-Sleep -Milliseconds 400
}
# ---- 切 600 档 ----
& $Adb -s $Serial shell wm size 1366x600 | Out-Null
& $Adb -s $Serial shell wm density 160 | Out-Null
Start-Sleep -Seconds 2
L "600: $((& $Adb -s $Serial shell wm size) -join ' | ')  $((& $Adb -s $Serial shell wm density) -join ' | ')"
$d = DumpUI "h600_00"
L "after resize: nodes=$($d.Nodes) ime=$(ImeShownV2) composer='$(ComposerText $d.Path)' stop=$(DescCount $d.Path 'Stop') work=$(DescCount $d.Path 'Working')"

# ---- 起长回合 + 排队行 + 开队列条 ----
QuickClear
$d = DumpUI "h600_01"; $e = EditCenter $d.Path
L "composerBox=$($e -join ',')"
& $Adb -s $Serial shell input tap $e[0] $e[1] | Out-Null; Start-Sleep -Milliseconds 300
& $Adb -s $Serial shell "input text 'E2ECHK5554u1v134%scount%sfrom%s1%sto%s900000,%sone%snumber%sper%sline,%snever%sstop'" | Out-Null
Start-Sleep -Milliseconds 700
$d = DumpUI "h600_02"; $s = DescCenter $d.Path "Send"
L "typed='$(ComposerText $d.Path)' send=$($s -join ',')"
& $Adb -s $Serial shell input tap $s[0] $s[1] | Out-Null
Start-Sleep -Seconds 4
$d = DumpUI "h600_03_running"
L "RUNNING: stop=$(DescCount $d.Path 'Stop') work=$(DescCount $d.Path 'Working') ime=$(ImeShownV2)"
$e = EditCenter $d.Path
& $Adb -s $Serial shell input tap $e[0] $e[1] | Out-Null; Start-Sleep -Milliseconds 300
& $Adb -s $Serial shell "input text 'E2ECHK5554u1v134%sparkeps600'" | Out-Null
Start-Sleep -Milliseconds 900
$d = DumpUI "h600_04"; $q = TextCenter $d.Path "Queue"
L "followup='$(ComposerText $d.Path)' QueueChip=$($q -join ',') work=$(DescCount $d.Path 'Working')"
if($q){ & $Adb -s $Serial shell input tap $q[0] $q[1] | Out-Null; Start-Sleep -Milliseconds 1300 }
$d = DumpUI "h600_05_panelon"
L "PANEL ON: Steer=$(TextCount $d.Path 'Steer') Send=$(TextCount $d.Path 'Send') Rem=$(DescCount $d.Path 'Remove') ime=$(ImeShownV2)"
ShowQueue $d.Path "h600_panelon"
ShotV2 "h600_05_panelon" | Out-Null
