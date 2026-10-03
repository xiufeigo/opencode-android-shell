# v140 Task A final: tight sequence to a VERIFIED bar-on frame with the probe captured in it.
param(
  [string]$Adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe",
  [string]$Ser = "emulator-5554",
  [string]$Art = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\artifacts"
)
function Dump([string]$t){ $l="$Art\$t.xml"; & $Adb -s $Ser shell uiautomator dump "/sdcard/$t.xml" 2>&1|Out-Null; & $Adb -s $Ser pull "/sdcard/$t.xml" $l 2>&1|Out-Null; $l }
function N([string]$f){ $x=Get-Content $f -Raw
  [regex]::Matches($x,'<node\s[^>]*>')|ForEach-Object{ $v=$_.Value
    [pscustomobject]@{
      cls=if($v -match 'class="([^"]*)"'){$Matches[1]}else{''}
      desc=if($v -match 'content-desc="([^"]*)"'){$Matches[1]}else{''}
      txt=if($v -match 'text="([^"]*)"'){$Matches[1]}else{''}
      x1=if($v -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){[int]$Matches[1]}else{0}
      y1=if($v -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){[int]$Matches[2]}else{0}
      x2=if($v -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){[int]$Matches[3]}else{0}
      y2=if($v -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){[int]$Matches[4]}else{0} }}}
function C([string]$f){ $n=N $f; $e=$n|Where-Object{$_.cls -eq 'android.widget.EditText'}|Select-Object -First 1
  [pscustomobject]@{ stop=@($n|?{$_.desc -eq 'Stop'}).Count; work=@($n|?{$_.desc -eq 'Working'}).Count
    steer=@($n|?{$_.desc -eq 'Steer'}).Count; rm=@($n|?{$_.desc -eq ('Rem'+'ove')}).Count
    q=@($n|?{$_.desc -eq 'Queue'}).Count; edit=$e; n=$n } }

for($round=1; $round -le 3; $round++){
  "===== round $round ====="
  $d = Dump "v140F_r$round"; $s = C $d
  # 1) long turn
  if($s.stop -eq 0){
    $e=$s.edit
    & $Adb -s $Ser shell input tap ([int](($e.x1+$e.x2)/2)) ([int](($e.y1+$e.y2)/2)) | Out-Null
    Start-Sleep -Milliseconds 300
    & $Adb -s $Ser shell input keyevent KEYCODE_MOVE_END | Out-Null
    for($i=0;$i -lt 130;$i++){ & $Adb -s $Ser shell input keyevent KEYCODE_DEL | Out-Null }
    & $Adb -s $Ser shell input text "E2ECHK5554u1w%scount%sfrom%s1%sto%s3000000%sone%sper%sline" | Out-Null
    Start-Sleep -Milliseconds 700
    $d = Dump "v140F_t$round"; $s = C $d
    $snd = $s.n | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
    if(-not $snd){ "no Send"; continue }
    & $Adb -s $Ser shell input tap ([int](($snd.x1+$snd.x2)/2)) ([int](($snd.y1+$snd.y2)/2)) | Out-Null
    Start-Sleep -Milliseconds 1500
    $d = Dump "v140F_r$round`r"; $s = C $d
  }
  "  after send: Stop=$($s.stop) Work=$($s.work)"
  if($s.stop -eq 0){ "  not running, next round"; continue }
  # 2) followup -> Queue chip
  $e=$s.edit
  & $Adb -s $Ser shell input tap ([int](($e.x1+$e.x2)/2)) ([int](($e.y1+$e.y2)/2)) | Out-Null
  Start-Sleep -Milliseconds 300
  & $Adb -s $Ser shell input text ("E2ECHK5554u1w%szz$round%sfollowup") | Out-Null
  Start-Sleep -Milliseconds 1200
  $d = Dump "v140F_q$round"; $s = C $d
  "  QueueChip=$($s.q) Stop=$($s.stop)"
  if($s.q -lt 1){ "  no chip, next round"; continue }
  $qn = $s.n | Where-Object { $_.desc -eq 'Queue' } | Select-Object -First 1
  $qcx=[int](($qn.x1+$qn.x2)/2); $qcy=[int](($qn.y1+$qn.y2)/2)
  "  Queue chip [$($qn.x1),$($qn.y1)][$($qn.x2),$qn.y2)] center=($qcx,$qcy)"
  # 3) clear -> tap Queue -> capture probe + dump
  & $Adb -s $Ser logcat -c
  & $Adb -s $Ser shell input tap $qcx $qcy | Out-Null
  Start-Sleep -Milliseconds 1400
  "  --- probe ---"
  & $Adb -s $Ser logcat -d -t 200 -v threadtime -s BandUnion:I 2>&1 | Tee-Object "$Art\probe_A.log"
  $d = Dump 'v140F_BAR'; $s = C $d
  (& $Adb -s $Ser shell dumpsys input_method 2>&1 | Select-String 'mInputShown' | Select-Object -First 1)
  "  BAR-ON FRAME: Steer=$($s.steer) Rem=$($s.rm) Stop=$($s.stop) Work=$($s.work) txt='$($s.edit.txt)'"
  if($s.steer -ge 1 -or $s.rm -ge 1){ "  *** BAR CONFIRMED ON ***"; break }
  else { "  bar not confirmed; retry" }
}
