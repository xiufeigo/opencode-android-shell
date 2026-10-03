$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134\tk.ps1"
$logf = "$Ev\_A_taskA.log"
Set-Content $logf ""
function L { param($m) $s = "$(Get-Date -Format 'HH:mm:ss.fff') $m"; Write-Output $s; Add-Content $logf $s }
function QuickClear {
  $d = DumpUI "tmp_qc"; $e = EditCenter $d.Path
  if($e){ & $Adb -s $Serial shell input tap $e[0] $e[1] | Out-Null; Start-Sleep -Milliseconds 300 }
  & $Adb -s $Serial shell input keyevent 123 | Out-Null
  & $Adb -s $Serial shell "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48 49 50 51 52 53 54 55 56 57 58 59 60 61 62 63 64 65 66 67 68 69 70; do input keyevent 67; done" | Out-Null
  Start-Sleep -Milliseconds 400
}
function QRows { param([string]$File)
  $rows = @()
  foreach($n in (NodeList $File)){
    if($n.text -eq 'Steer' -or $n.text -eq 'Send' -or $n.desc -eq 'Rem[o]ve' -or $n.desc -eq 'Remove'){ $rows += $n }
  }
  return $rows
}

# ---------- 1) 起长回合 ----------
QuickClear
$d = DumpUI "e01_typed"; $e = EditCenter $d.Path
& $Adb -s $Serial shell input tap $e[0] $e[1] | Out-Null; Start-Sleep -Milliseconds 300
& $Adb -s $Serial shell "input text 'E2ECHK5554u1v134%scount%sfrom%s1%sto%s900000,%sone%snumber%sper%sline,%snever%sstop'" | Out-Null
Start-Sleep -Milliseconds 600
$d = DumpUI "e02_typed"; $s = DescCenter $d.Path "Send"
L "typed='$(ComposerText $d.Path)' send=$($s -join ',')"
& $Adb -s $Serial shell input tap $s[0] $s[1] | Out-Null
Start-Sleep -Seconds 3
$d = DumpUI "e03_running"
L "RUNNING: nodes=$($d.Nodes) stop=$(DescCount $d.Path 'Stop') working=$(DescCount $d.Path 'Working')"

# ---------- 2) followup + 开队列条 ----------
$e = EditCenter $d.Path
& $Adb -s $Serial shell input tap $e[0] $e[1] | Out-Null; Start-Sleep -Milliseconds 300
& $Adb -s $Serial shell "input text 'E2ECHK5554u1v134%sparkgamma'" | Out-Null
Start-Sleep -Milliseconds 800
$d = DumpUI "e04_followup"; $q = TextCenter $d.Path "Queue"
L "followup='$(ComposerText $d.Path)' QueueChip=$($q -join ',') working=$(DescCount $d.Path 'Working')"
& $Adb -s $Serial shell input tap $q[0] $q[1] | Out-Null
Start-Sleep -Milliseconds 1200
$d = DumpUI "e05_queueopen"
L "QUEUEOPEN: stop=$(DescCount $d.Path 'Stop') working=$(DescCount $d.Path 'Working') nodes=$($d.Nodes)"
ShowQueue $d.Path "open"

# ---------- 3) 清 composer 取 pre-stop 前帧 ----------
QuickClear
$d = DumpUI "e06_pre_stop"
L "PRE_STOP: stop=$(DescCount $d.Path 'Stop') working=$(DescCount $d.Path 'Working') composer='$(ComposerText $d.Path)' nodes=$($d.Nodes)"
ShowQueue $d.Path "pre"
ShotV2 "e06_pre_stop" | Out-Null

# ---------- 4) 读坐标后立即落点 Stop（零间隔）----------
$d2 = DumpUI "e07_pre_stop_tap"
$sp = DescCenter $d2.Path "Stop"
L "PRE_TAP: stop=$(DescCount $d2.Path 'Stop') stopBtn=$($sp -join ',')"
& $Adb -s $Serial shell input tap $sp[0] $sp[1] | Out-Null
L "TAPPED STOP at $($sp[0]),$($sp[1])"

# ---------- 5) 高频快轮询（只认真帧）----------
for($i=1; $i -le 22; $i++){
  try{
    $d3 = DumpUI ("e08_p{0:d2}" -f $i) 3
    $sN = DescCount $d3.Path "Stop"; $wN = DescCount $d3.Path "Working"
    $cT = ComposerText $d3.Path
    $qr = QRows $d3.Path
    $lab = "none"
    foreach($r in $qr){ if($r.text -eq 'Steer' -or $r.text -eq 'Send'){ $lab = $r.text } }
    L ("p{0:d2} nodes={1} stop={2} work={3} ime={4} composer='{5}' rowLabel={6} nRows={7}" -f $i,$d3.Nodes,$sN,$wN,(ImeShownV2),$cT,$lab,$qr.Count)
    if($sN -eq 0 -and $wN -eq 0 -and $cT -eq '' -and $lab -ne 'none'){
      L ">>> 真 idle 且 parked 行在场: label=$lab"
      ShowQueue $d3.Path "idle_frame"
      ShotV2 ("e08_IDLE_p{0:d2}" -f $i) | Out-Null
      break
    }
    if($sN -eq 0 -and $wN -eq 0 -and $cT -eq '' -and $lab -eq 'none'){
      L ">>> idle 已达成，但 parked 行不在场 (nRows=$($qr.Count))"
      ShotV2 ("e08_IDLE_norow_p{0:d2}" -f $i) | Out-Null
      break
    }
  } catch { L "p{0:d2} DUMP_FAIL: $($_.Exception.Message)" }
  Start-Sleep -Milliseconds 250
}
