# helpers for emulator-5556 v98 SWEEPC2 (round 2) - no gradle, no install, no kill
$ErrorActionPreference = 'Continue'
$ADB = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$DEV = "emulator-5556"
$OUT = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98"

function a { & $ADB -s $DEV @args }
function shot($n) { a shell screencap -p "/sdcard/$n.png" | Out-Null; a pull "/sdcard/$n.png" "$OUT\$n.png" | Out-Null; "$OUT\$n.png" }
function dump($n) { a shell uiautomator dump "/sdcard/$n.xml" | Out-Null; a pull "/sdcard/$n.xml" "$OUT\$n.xml" | Out-Null; "$OUT\$n.xml" }
function tap($x,$y) { a shell input tap $x $y; Start-Sleep -Milliseconds 800 }
function sp($b) { a shell input swipe $b; Start-Sleep -Milliseconds 900 }
function txt($s) {
  $i=0
  while ($i -lt $s.Length) {
    $len=[Math]::Min(8, $s.Length-$i); $chunk=$s.Substring($i,$len)
    & $ADB -s $DEV shell input text $chunk
    Start-Sleep -Milliseconds 350
    $i+=$len
  }
}
# print every node line that matches pattern, with bounds, so coords are always dump-derived
function nodes($xml,$pat) {
  $c = Get-Content $xml -Raw
  foreach ($m in [regex]::Matches($c, '<node[^>]*')) {
    if ($m.Value -match $pat) {
      $t=''; if($m.Value -match 'text="([^"]*)"'){$t=$Matches[1]}
      $d=''; if($m.Value -match 'content-desc="([^"]*)"'){$d=$Matches[1]}
      $b=''; if($m.Value -match 'bounds="(\[[^"]+\])"'){$b=$Matches[1]}
      "text=[$t] desc=[$d] $b"
    }
  }
}
function findb($xml,$pat) {
  $c = Get-Content $xml -Raw
  foreach ($m in [regex]::Matches($c, '<node[^>]*')) {
    if ($m.Value -match $pat) { if ($m.Value -match 'bounds="(\[[^"]+\])"') { return $Matches[1] } }
  }
  return $null
}
function center($b) { $n=[regex]::Matches($b,'\d+') | ForEach-Object { [int]$_.Value }; "$([int](($n[0]+$n[2])/2)) $([int](($n[1]+$n[3])/2))" }
function tapb($b) { $c = center $b; $p = $c -split ' '; tap $p[0] $p[1] }
function dumpok($n) { # retry dump up to 3x, return local path of a non-empty xml
  for ($i=1; $i -le 3; $i++) {
    dump $n | Out-Null
    if ((Test-Path "$OUT\$n.xml") -and ((Get-Item "$OUT\$n.xml").Length -gt 200)) { return "$OUT\$n.xml" }
    Start-Sleep -Milliseconds 800
  }
  return "$OUT\$n.xml"
}
