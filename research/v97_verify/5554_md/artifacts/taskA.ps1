# v140 Task A: reach 600 + queue-bar-ON + one-line form, then capture the BandUnion probe
# in the SAME frame that is dumped. Verifies the frame before trusting the probe.
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
  $stop = @($n | Where-Object { $_.desc -eq 'Stop' }).Count
  $work = @($n | Where-Object { $_.desc -eq 'Working' }).Count
  $steer= @($n | Where-Object { $_.desc -eq 'Steer' }).Count
  $rm   = @($n | Where-Object { $_.desc -eq ('Rem'+'ove') }).Count
  $q    = @($n | Where-Object { $_.desc -eq 'Queue' }).Count
  "[$tag] editor.txt='$($e.txt)' Stop=$stop Working=$work Steer=$steer Rem=$rm QueueChip=$q"
  return [pscustomobject]@{ stop=$stop; work=$work; steer=$steer; rm=$rm; q=$q; edit=$e }
}

# --- 1) ensure a running long turn ---
$d = Dump 'v140A_a'
$s = State 'pre' $d
if($s.stop -eq 0){
  ">> no running turn; sending a long one"
  & $Adb -s $Ser shell input tap 1340 215 | Out-Null
  Start-Sleep -Seconds 3
}
# --- 2) put content in composer so the Queue chip appears ---
$d = Dump 'v140A_b'
$s = State 'before-type' $d
$e = Nodes $d | Where-Object { $_.cls -eq 'android.widget.EditText' } | Select-Object -First 1
& $Adb -s $Ser shell input tap ([int](($e.x1+$e.x2)/2)) ([int](($e.y1+$e.y2)/2)) | Out-Null
Start-Sleep -Milliseconds 600
& $Adb -s $Ser shell input text ("E2ECHK5554u1w%sq" + (Get-Random -Maximum 9999) + "%sprobe%srow") | Out-Null
Start-Sleep -Seconds 2
$d = Dump 'v140A_c'
$s = State 'chip' $d
"QueueChip count = $($s.q)"
if($s.q -lt 1){ "!! Queue chip did not appear — abort"; exit 2 }
$qn = Nodes $d | Where-Object { $_.desc -eq 'Queue' } | Select-Object -First 1
$qcx=[int](($qn.x1+$qn.x2)/2); $qcy=[int](($qn.y1+$qn.y2)/2)
"Queue chip bounds=[$($qn.x1),$($qn.y1)][$($qn.x2),$($qn.y2)] center=($qcx,$qcy)"

# --- 3) clear buffer, tap Queue, then capture probe + dump in the same window ---
& $Adb -s $Ser logcat -c
& $Adb -s $Ser shell input tap $qcx $qcy | Out-Null
Start-Sleep -Milliseconds 900
& $Adb -s $Ser logcat -d -t 200 -v threadtime -s BandUnion:I 2>&1 | Tee-Object "$Art\probe_A.log"
$d = Dump 'v140A_bar'
$s = State 'BAR-ON' $d
$dump = & $Adb -s $Ser shell dumpsys input_method 2>&1 | Select-String 'mInputShown' | Select-Object -First 1
"IME: $dump"
"BAR-ON frame: Steer=$($s.steer) Rem=$($s.rm) Stop=$($s.stop) Working=$($s.work) editor.txt='$($s.edit.txt)'"
