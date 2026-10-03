# helpers for emulator-5556 v98 verification (no gradle, no install, no kill)
$ErrorActionPreference = 'Continue'
$ADB = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$DEV = "emulator-5556"
$OUT = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98"

function a { & $ADB -s $DEV @args }
function shot($n) { a shell screencap -p "/sdcard/$n.png" | Out-Null; a pull "/sdcard/$n.png" "$OUT\$n.png" | Out-Null; "$OUT\$n.png" }
function dump($n) { a shell uiautomator dump "/sdcard/$n.xml" | Out-Null; a pull "/sdcard/$n.xml" "$OUT\$n.xml" | Out-Null; "$OUT\$n.xml" }
function tap($x,$y) { a shell input tap $x $y; Start-Sleep -Milliseconds 700 }
function txt($s) { # <=8 char chunks, no %s
  $i=0
  while ($i -lt $s.Length) {
    $len=[Math]::Min(8, $s.Length-$i); $chunk=$s.Substring($i,$len)
    & $ADB -s $DEV shell input text $chunk
    Start-Sleep -Milliseconds 350
    $i+=$len
  }
}
function findb($xml,$pat) { # returns bounds string of first node whose attr matches
  $c = Get-Content $xml -Raw
  foreach ($m in [regex]::Matches($c, '<node[^>]*')) {
    if ($m.Value -match $pat) { if ($m.Value -match 'bounds="(\[[^"]+\])"') { return $Matches[1] } }
  }
  return $null
}
function findall($xml,$pat) {
  $c = Get-Content $xml -Raw; $r=@()
  foreach ($m in [regex]::Matches($c, '<node[^>]*')) { if ($m.Value -match $pat) { if ($m.Value -match 'bounds="(\[[^"]+\])"') { $r += $Matches[1] } } }
  return $r
}
function center($b) { $n=[regex]::Matches($b,'\d+') | ForEach-Object { [int]$_.Value }; "$([int](($n[0]+$n[2])/2)) $([int](($n[1]+$n[3])/2))" }
function tapb($b) { $c = center $b; $p = $c -split ' '; tap $p[0] $p[1] }
