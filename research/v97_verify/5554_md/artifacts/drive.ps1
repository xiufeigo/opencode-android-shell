# v140 Task A driver: 600-size, queue-bar ON, one-line form. Coordinates parsed at runtime from dumps.
param(
  [string]$Adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe",
  [string]$Ser = "emulator-5554",
  [string]$Art = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\artifacts"
)
$ErrorActionPreference = 'Continue'
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
      cls  = if($v -match 'class="([^"]*)"'){$Matches[1]}else{''}
      txt  = if($v -match 'text="([^"]*)"'){$Matches[1]}else{''}
      desc = if($v -match 'content-desc="([^"]*)"'){$Matches[1]}else{''}
      clk  = if($v -match 'clickable="([^"]*)"'){$Matches[1]}else{'false'}
      x1   = if($v -match 'bounds="\[(\d+),(\d+)\]'){[int]$Matches[1]}else{0}
      y1   = if($v -match 'bounds="\[(\d+),(\d+)\]'){[int]$Matches[2]}else{0}
      x2   = if($v -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){[int]$Matches[3]}else{0}
      y2   = if($v -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){[int]$Matches[4]}else{0}
    }
  }
}
function TapDesc([string]$xml, [string]$desc) {
  $n = Nodes $xml | Where-Object { $_.desc -eq $desc } | Select-Object -First 1
  if($n -eq $null){ "  !! no node desc='$desc'"; return $null }
  $cx = [int](($n.x1 + $n.x2)/2); $cy = [int](($n.y1 + $n.y2)/2)
  "  tap '$desc' center=($cx,$cy)  bounds=[$($n.x1),$($n.y1)][$($n.x2),$($n.y2)]"
  & $Adb -s $Ser shell input tap $cx $cy | Out-Null
  return $n
}

$step = $args[0]
if($step -eq 'focus'){
  $d = Dump 'v140_f0'
  $n = Nodes $d | Where-Object { $_.cls -eq 'android.widget.EditText' } | Select-Object -First 1
  "EditText [$($n.x1),$($n.y1)][$($n.x2),$($n.y2)] txt='$($n.txt)'"
  & $Adb -s $Ser shell input tap ([int](($n.x1+$n.x2)/2)) ([int](($n.y1+$n.y2)/2)) | Out-Null
  Start-Sleep -Milliseconds 700
}
elseif($step -eq 'clear'){
  # select-all + delete via keyevents (no BACK)
  & $Adb -s $Ser shell input keyevent KEYCODE_MOVE_END | Out-Null
  for($i=0;$i -lt 90;$i++){ & $Adb -s $Ser shell input keyevent KEYCODE_DEL | Out-Null }
  $d = Dump 'v140_c1'
  $n = Nodes $d | Where-Object { $_.cls -eq 'android.widget.EditText' } | Select-Object -First 1
  "after clear: txt='$($n.txt)'"
}
