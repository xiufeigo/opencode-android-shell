$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$d   = "emulator-5554"
$cd  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md"
$ev  = "$cd\evidence"

function Dump { & $adb -s $d shell uiautomator dump --compressed /sdcard/pp.xml 2>&1 | Out-Null
  & $adb -s $d pull /sdcard/pp.xml "$ev\pp.xml" 2>&1 | Out-Null
  Get-Content "$ev\pp.xml" -Raw -Encoding UTF8 }
function CP {
  $raw = Dump
  foreach ($m in [regex]::Matches($raw, '<node[^>]*>')) {
    if ($m.Value -notmatch 'class="[^"]*EditText"') { continue }
    $b=[regex]::Match($m.Value,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    return @([int](([int]$b.Groups[1].Value+[int]$b.Groups[3].Value)/2), [int](([int]$b.Groups[2].Value+[int]$b.Groups[4].Value)/2))
  }
  return @(683,268)
}
function RowLines([string]$raw) {
  $res=@()
  foreach ($m in [regex]::Matches($raw, '<node[^>]*>')) {
    $n=$m.Value
    $tx=[regex]::Match($n,'text="([^"]*)"').Groups[1].Value
    $b=[regex]::Match($n,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if(-not $b.Success){continue}
    $lb=$b.Groups[1].Value+','+$b.Groups[2].Value+'-'+$b.Groups[3].Value+','+$b.Groups[4].Value
    if ($tx -match '^E2ECHK5554u1s row') { $res += "ROW@$lb '$tx'" }
    elseif ($tx -eq 'Steer' -or $tx -eq 'Send') { $res += "ACT@$lb '$tx'" }
  }
  return $res
}

# ensure a turn is running; if not, start a long one
$raw = Dump
if ($raw -notmatch 'text="Stop"') {
  $p = CP
  & $adb -s $d shell input tap $p[0] $p[1]
  Start-Sleep -Milliseconds 500
  & $adb -s $d shell input text ("E2ECHK5554u1s count from 1 to 4000, one number per line" -replace ' ','%s')
  Start-Sleep -Milliseconds 600
  pwsh -NoProfile -File "$cd\tapby.ps1" -What "Send"
  Start-Sleep -Seconds 3
}

foreach ($t in @("E2ECHK5554u1s rowD fourth","E2ECHK5554u1s rowE fifth","E2ECHK5554u1s rowF sixth")) {
  $q = CP
  & $adb -s $d shell input tap $q[0] $q[1]
  Start-Sleep -Milliseconds 400
  & $adb -s $d shell input text ($t -replace ' ','%s')
  Start-Sleep -Milliseconds 500
  pwsh -NoProfile -File "$cd\tap_queue.ps1" -Tag zq
}

"### pre-stop"
$raw = Dump
RowLines $raw | ForEach-Object { "  $_" }
"running=" + ($raw -match 'text="Stop"')

"### Stop + fast poll"
& $adb -s $d shell input tap 1332 316
for ($i=1; $i -le 12; $i++) {
  $raw = Dump
  $st = if($raw -match 'text="Stop"'){'RUNNING'}else{'IDLE'}
  $lab = if($raw -match 'text="Send"'){'*firstRowLabel=SEND*'}elseif($raw -match 'text="Steer"'){'firstRowLabel=STEER'}else{'noActLabel'}
  "poll#$i $st $lab"
  (RowLines $raw) | ForEach-Object { "    $_" }
  if ($raw -match 'text="Send"') {
    & $adb -s $d shell screencap -p /sdcard/s_send_lab.png
    & $adb -s $d pull /sdcard/s_send_lab.png "$ev\s_send_lab.png" 2>&1 | Out-Null
    Copy-Item "$ev\pp.xml" "$ev\send_lab.xml" -Force
    ">>> CAUGHT parked at poll#$i"
    break
  }
}
