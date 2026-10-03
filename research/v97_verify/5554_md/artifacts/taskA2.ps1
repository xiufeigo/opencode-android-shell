# v140 Task A v2: robustly reach 600 + queue-bar-ON + one-line form and capture the probe.
param(
  [string]$Adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe",
  [string]$Ser = "emulator-5554",
  [string]$Art = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\artifacts"
)
function Dump([string]$tag) {
  $l = "$Art\$tag.xml"
  & $Adb -s $Ser shell uiautomator dump "/sdcard/$tag.xml" 2>&1 | Select-Object -Last 1 | Out-Null
  & $Adb -s $Ser pull "/sdcard/$tag.xml" $l 2>&1 | Select-Object -Last 1 | Out-Null
  return $l
}
function Nodes([string]$xml) {
  $x = Get-Content $xml -Raw
  return [regex]::Matches($x, '<node\s[^>]*>') | ForEach-Object {
    $v = $_.Value
    [pscustomobject]@{
      cls=if($v -match 'class="([^"]*)"'){$Matches[1]}else{''}
      txt=if($v -match 'text="([^"]*)"'){$Matches[1]}else{''}
      desc=if($v -match 'content-desc="([^"]*)"'){$Matches[1]}else{''}
      clk=if($v -match 'clickable="([^"]*)"'){$Matches[1]}else{'false'}
      x1=if($v -match 'bounds="\[(\d+),(\d+)\]'){[int]$Matches[1]}else{0}
      y1=if($v -match 'bounds="\[(\d+),(\d+)\]'){[int]$Matches[2]}else{0}
      x2=if($v -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){[int]$Matches[3]}else{0}
      y2=if($v -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){[int]$Matches[4]}else{0}
    }
  }
}
function State([string]$tag, [string]$xml) {
  $n = Nodes $xml
  $e = $n | Where-Object { $_.cls -eq 'android.widget.EditText' } | Select-Object -First 1
  [pscustomobject]@{
    stop=@($n|Where-Object{$_.desc -eq 'Stop'}).Count
    work=@($n|Where-Object{$_.desc -eq 'Working'}).Count
    steer=@($n|Where-Object{$_.desc -eq 'Steer'}).Count
    rm=@($n|Where-Object{$_.desc -eq ('Rem'+'ove')}).Count
    q=@($n|Where-Object{$_.desc -eq 'Queue'}).Count
    edit=$e; n=$n
  } | ForEach-Object { "[$tag] editor.txt='$($_.edit.txt)' Stop=$($_.stop) Working=$($_.work) Steer=$($_.steer) Rem=$($_.rm) QueueChip=$($_.q)"; $_ }
}

# ---------- A1: long-running turn ----------
$tag = "E2ECHK5554u1w longrun $(Get-Random -Maximum 99999)"
$d = Dump 'v140B_a'; $s = State 'start' $d
if($s.stop -eq 0){
  $e = $s.edit
  & $Adb -s $Ser shell input tap ([int](($e.x1+$e.x2)/2)) ([int](($e.y1+$e.y2)/2)) | Out-Null
  Start-Sleep -Milliseconds 500
  for($i=0;$i -lt 100;$i++){ & $Adb -s $Ser shell input keyevent KEYCODE_DEL | Out-Null }
  & $Adb -s $Ser shell input text ($tag -replace ' ','%s') | Out-Null
  Start-Sleep -Seconds 1
  $d = Dump 'v140B_b'; $s = State 'typed' $d
  # Send is at the right edge of the band; find it at runtime
  $snd = $s.n | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
  "Send bounds=[$($snd.x1),$($snd.y1)][$($snd.x2),$($snd.y2)]"
  & $Adb -s $Ser shell input tap ([int](($snd.x1+$snd.x2)/2)) ([int](($snd.y1+$snd.y2)/2)) | Out-Null
  Start-Sleep -Seconds 2
}
$d = Dump 'v140B_c'; $s = State 'running' $d
"RUNNING? Stop=$($s.stop) Working=$($s.work)"

# ---------- A2: followup text -> Queue chip (retry loop) ----------
$ok = $false
for($try=1; $try -le 4 -and -not $ok; $try++){
  $d = Dump "v140B_t$try"; $s = State "try$try" $d
  if($s.stop -eq 0){ ">> turn ended, resending"; & $Adb -s $Ser shell input tap 1340 215 | Out-Null; Start-Sleep -Seconds 2; continue }
  $e = $s.edit
  & $Adb -s $Ser shell input tap ([int](($e.x1+$e.x2)/2)) ([int](($e.y1+$e.y2)/2)) | Out-Null
  Start-Sleep -Milliseconds 500
  & $Adb -s $Ser shell input text ("E2ECHK5554u1w%sq$try%sprobe%srow") | Out-Null
  Start-Sleep -Seconds 2
  $d = Dump "v140B_q$try"; $s = State "chip$try" $d
  if($s.q -ge 1){ $ok = $true; break }
  ">> Queue chip absent (try $try)"
}
if(-not $ok){ "!! could not summon Queue chip"; exit 2 }
$qn = $s.n | Where-Object { $_.desc -eq 'Queue' } | Select-Object -First 1
$qcx=[int](($qn.x1+$qn.x2)/2); $qcy=[int](($qn.y1+$qn.y2)/2)
"Queue chip bounds=[$($qn.x1),$($qn.y1)][$($qn.x2),$($qn.y2)] center=($qcx,$qcy)"

# ---------- A3: clear buffer -> tap Queue -> capture probe + dump together ----------
& $Adb -s $Ser logcat -c
& $Adb -s $Ser shell input tap $qcx $qcy | Out-Null
Start-Sleep -Milliseconds 1200
& $Adb -s $Ser logcat -d -t 200 -v threadtime -s BandUnion:I 2>&1 | Tee-Object "$Art\probe_A.log"
$d = Dump 'v140B_bar'; $s = State 'BAR-ON' $d
(& $Adb -s $Ser shell dumpsys input_method 2>&1 | Select-String 'mInputShown' | Select-Object -First 1)
"BAR-ON: Steer=$($s.steer) Rem=$($s.rm) Stop=$($s.stop) Working=$($s.work) editor.txt='$($s.edit.txt)'"
