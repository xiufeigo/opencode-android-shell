# v140 Task A v3: use a LONG count command (so the turn survives) and verify the bar-on frame.
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
function St([string]$xml) {
  $n = Nodes $xml
  $e = $n | Where-Object { $_.cls -eq 'android.widget.EditText' } | Select-Object -First 1
  $o = [pscustomobject]@{
    stop=@($n|Where-Object{$_.desc -eq 'Stop'}).Count
    work=@($n|Where-Object{$_.desc -eq 'Working'}).Count
    steer=@($n|Where-Object{$_.desc -eq 'Steer'}).Count
    rm=@($n|Where-Object{$_.desc -eq ('Rem'+'ove')}).Count
    q=@($n|Where-Object{$_.desc -eq 'Queue'}).Count
    edit=$e; n=$n }
  "[$(Split-Path $xml -Leaf)] txt='$($e.txt)' Stop=$($o.stop) Work=$($o.work) Steer=$($o.steer) Rem=$($o.rm) Q=$($o.q)"
  return $o
}

# ---- start a genuinely long turn ----
$d = Dump 'v140C_0'; $s = St $d
if($s.stop -eq 0){
  $e=$s.edit
  & $Adb -s $Ser shell input tap ([int](($e.x1+$e.x2)/2)) ([int](($e.y1+$e.y2)/2)) | Out-Null
  Start-Sleep -Milliseconds 400
  for($i=0;$i -lt 120;$i++){ & $Adb -s $Ser shell input keyevent KEYCODE_DEL | Out-Null }
  & $Adb -s $Ser shell input text "E2ECHK5554u1w%scount%sfrom%s1%sto%s3000000%sone%snumber%sper%sline" | Out-Null
  Start-Sleep -Milliseconds 800
  $d = Dump 'v140C_1'; $s = St $d
  $snd = $s.n | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
  if($snd){ & $Adb -s $Ser shell input tap ([int](($snd.x1+$snd.x2)/2)) ([int](($snd.y1+$snd.y2)/2)) | Out-Null }
  else { "no Send node" }
  Start-Sleep -Seconds 2
}
$d = Dump 'v140C_2'; $s = St $d
"long turn: Stop=$($s.stop) Work=$($s.work)"

# ---- summon Queue chip ----
$ok=$false
for($t=1; $t -le 5 -and -not $ok; $t++){
  $d = Dump "v140C_s$t"; $s = St $d
  if($s.stop -eq 0){ "turn ended (try $t)"; Start-Sleep -Milliseconds 500; continue }
  $e=$s.edit
  & $Adb -s $Ser shell input tap ([int](($e.x1+$e.x2)/2)) ([int](($e.y1+$e.y2)/2)) | Out-Null
  Start-Sleep -Milliseconds 400
  & $Adb -s $Ser shell input text ("E2ECHK5554u1w%sq$t%sprobe%srow") | Out-Null
  Start-Sleep -Milliseconds 1500
  $d = Dump "v140C_q$t"; $s = St $d
  if($s.q -ge 1){ $ok=$true }
}
if(-not $ok){ "!! no Queue chip after 5 tries"; exit 2 }
$qn = $s.n | Where-Object { $_.desc -eq 'Queue' } | Select-Object -First 1
$qcx=[int](($qn.x1+$qn.x2)/2); $qcy=[int](($qn.y1+$qn.y2)/2)
"Queue chip [$($qn.x1),$($qn.y1)][$($qn.x2),$($qn.y2)] center=($qcx,$qcy)"

# ---- clear buffer, tap Queue, capture probe AND dump in the same window ----
& $Adb -s $Ser logcat -c
& $Adb -s $Ser shell input tap $qcx $qcy | Out-Null
Start-Sleep -Milliseconds 1500
& $Adb -s $Ser logcat -d -t 200 -v threadtime -s BandUnion:I 2>&1 | Tee-Object "$Art\probe_A.log"
$d = Dump 'v140C_BAR'; $s = St $d
(& $Adb -s $Ser shell dumpsys input_method 2>&1 | Select-String 'mInputShown' | Select-Object -First 1)
"BAR-ON: Steer=$($s.steer) Rem=$($s.rm) Stop=$($s.stop) Work=$($s.work) txt='$($s.edit.txt)'"
