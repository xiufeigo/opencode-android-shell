# Queue N rows, then Stop and poll fast to catch the parked (not-running) window
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$d   = "emulator-5554"
$cd  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md"
$ev  = "$cd\evidence"

function Dump {
  & $adb -s $d shell uiautomator dump --compressed /sdcard/pp.xml 2>&1 | Out-Null
  & $adb -s $d pull /sdcard/pp.xml "$ev\pp.xml" 2>&1 | Out-Null
  Get-Content "$ev\pp.xml" -Raw -Encoding UTF8
}
function RowLabels([string]$raw) {
  $res = @()
  foreach ($m in [regex]::Matches($raw, '<node[^>]*>')) {
    $n=$m.Value
    $tx=[regex]::Match($n,'text="([^"]*)"').Groups[1].Value
    $cd2=[regex]::Match($n,'content-desc="([^"]*)"').Groups[1].Value
    $b=[regex]::Match($n,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if(-not $b.Success){continue}
    $lb=$b.Groups[1].Value+','+$b.Groups[2].Value+'-'+$b.Groups[3].Value+','+$b.Groups[4].Value
    if ($tx -match '^E2ECHK5554u1s row') { $res += "ROW@$lb '$tx'" }
    elseif ($tx -eq 'Steer' -or $tx -eq 'Send') { $res += "ACT@$lb '$tx'" }
    elseif ($cd2 -eq 'Send' -or $cd2 -eq 'Stop' -or $cd2 -eq 'Steer') { $res += "BTN@$lb '$cd2'" }
  }
  return $res
}

"### pre-stop snapshot"
$raw = Dump
RowLabels $raw | ForEach-Object { "  $_" }
$hasStop = $raw -match 'text="Stop"'
"running(Stop present)=$hasStop"

"### tapping Stop"
& $adb -s $d shell input tap 1332 316

for ($i=1; $i -le 10; $i++) {
  $raw = Dump
  $r = RowLabels $raw
  $stop = if($raw -match 'text="Stop"'){'Stop(running)'}else{'Send(not running)'}
  $send = if($raw -match 'text="Send"'){'ROWWAY-Send-label'}else{''}
  "poll#$i [$stop] $send"
  $r | ForEach-Object { "    $_" }
  if ($raw -match 'text="Send"') {
    & $adb -s $d shell screencap -p /sdcard/s_send_lab.png
    & $adb -s $d pull /sdcard/s_send_lab.png "$ev\s_send_lab.png" 2>&1 | Out-Null
    & $adb -s $d shell uiautomator dump --compressed /sdcard/send_lab.xml 2>&1 | Out-Null
    & $adb -s $d pull /sdcard/send_lab.xml "$ev\send_lab.xml" 2>&1 | Out-Null
    ">>> CAUGHT parked state at poll#$i"
    break
  }
}
