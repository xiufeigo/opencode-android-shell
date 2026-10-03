$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134\tk.ps1"
$logf = "$Ev\_A2.log"; Set-Content $logf ""
function L { param($m) $s = "$(Get-Date -Format 'HH:mm:ss.fff') $m"; Write-Output $s; Add-Content $logf $s }
function QuickClear {
  $d = DumpUI "tmp_qc2"; $e = EditCenter $d.Path
  if($e){ & $Adb -s $Serial shell input tap $e[0] $e[1] | Out-Null; Start-Sleep -Milliseconds 300 }
  & $Adb -s $Serial shell input keyevent 123 | Out-Null
  & $Adb -s $Serial shell "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48 49 50 51 52 53 54 55 56 57 58 59 60 61 62 63 64 65 66 67 68 69 70 71 72 73 74 75 76 77 78 79 80; do input keyevent 67; done" | Out-Null
  Start-Sleep -Milliseconds 400
}
function StateOf { param([string]$File,[string]$tag)
  $sN = DescCount $File "Stop"; $wN = DescCount $File "Working"; $cT = ComposerText $File
  $steer = TextCount $File "Steer"; $send = TextCount $File "Send"; $rm = DescCount $File "Remove"
  $queuedChip = TextCount $File "Queue"
  L ("$tag stop=$sN work=$wN composer='$cT' Steer=$steer Send=$send Remove=$rm QueueChip=$queuedChip")
  return @{ stop=$sN; work=$wN; composer=$cT; steer=$steer; send=$send; remove=$rm; chip=$queuedChip }
}

# 1) 起长回合
QuickClear
$d = DumpUI "g01"; $e = EditCenter $d.Path
& $Adb -s $Serial shell input tap $e[0] $e[1] | Out-Null; Start-Sleep -Milliseconds 300
& $Adb -s $Serial shell "input text 'E2ECHK5554u1v134%scount%sfrom%s1%sto%s900000,%sone%snumber%sper%sline,%snever%sstop'" | Out-Null
Start-Sleep -Milliseconds 600
$d = DumpUI "g02"; $s = DescCenter $d.Path "Send"
& $Adb -s $Serial shell input tap $s[0] $s[1] | Out-Null
Start-Sleep -Seconds 3
$d = DumpUI "g03_running"; StateOf $d.Path "RUNNING" | Out-Null

# 2) followup + 开面板
$e = EditCenter $d.Path
& $Adb -s $Serial shell input tap $e[0] $e[1] | Out-Null; Start-Sleep -Milliseconds 300
& $Adb -s $Serial shell "input text 'E2ECHK5554u1v134%sparkdelta'" | Out-Null
Start-Sleep -Milliseconds 800
$d = DumpUI "g04"; $q = TextCenter $d.Path "Queue"
L "QueueChip=$($q -join ',')"
& $Adb -s $Serial shell input tap $q[0] $q[1] | Out-Null
Start-Sleep -Milliseconds 1200
$d = DumpUI "g05_panelopen"; StateOf $d.Path "PANEL_OPEN" | Out-Null
ShowQueue $d.Path "panelopen"
ShotV2 "g05_panelopen" | Out-Null

# 3) 清 composer -> Stop 在场
QuickClear
$d = DumpUI "g06_pre"; StateOf $d.Path "PRE_STOP" | Out-Null
ShowQueue $d.Path "pre"

# 4) 关面板（再点 chip 切换）
$q2 = TextCenter $d.Path "Queue"
if($q2){ L "toggle close panel at $($q2 -join ',')"; & $Adb -s $Serial shell input tap $q2[0] $q2[1] | Out-Null; Start-Sleep -Milliseconds 900 }
$d = DumpUI "g07_panelclosed"; StateOf $d.Path "PANEL_CLOSED" | Out-Null

# 5) 零间隔点 Stop
$d = DumpUI "g08_pretap"; $sp = DescCenter $d.Path "Stop"
L "StopBtn=$($sp -join ',')"
if($sp){ & $Adb -s $Serial shell input tap $sp[0] $sp[1] | Out-Null; L "TAPPED STOP" }
Start-Sleep -Milliseconds 700

# 6) 快轮询
for($i=1; $i -le 14; $i++){
  try{
    $d = DumpUI ("g09_p{0:d2}" -f $i) 3
    $st = StateOf $d.Path ("p{0:d2}" -f $i)
    if($st.stop -eq 0 -and $st.work -eq 0 -and $st.composer -eq ''){
      L ">>> IDLE (Stop desc GONE)"
      if($st.steer -gt 0 -or $st.send -gt 0){
        L ">>> PARKED ROW PRESENT, label(send)=$($st.send) label(steer)=$($st.steer)"
        ShowQueue $d.Path "IDLE_FRAME"
        ShotV2 ("g09_IDLE_p{0:d2}" -f $i) | Out-Null
      } else {
        L ">>> panel not open at idle (steer=0 send=0 remove=$($st.remove) chip=$($st.chip))"
        ShotV2 ("g09_IDLE_nopanel_p{0:d2}" -f $i) | Out-Null
      }
      break
    }
  } catch { L "p$i DUMP_FAIL" }
  Start-Sleep -Milliseconds 200
}
