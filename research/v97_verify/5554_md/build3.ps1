$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$d   = "emulator-5554"
$cd  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md"

# long-running turn
& $adb -s $d shell input tap 683 268
Start-Sleep -Milliseconds 600
& $adb -s $d shell input text ("E2ECHK5554u1s count from 1 to 4000, one number per line" -replace ' ','%s')
Start-Sleep -Milliseconds 700
pwsh -NoProfile -File "$cd\tapby.ps1" -What "Send"
Start-Sleep -Seconds 3

function CP {
  & $adb -s $d shell uiautomator dump --compressed /sdcard/cx.xml 2>&1 | Out-Null
  & $adb -s $d pull /sdcard/cx.xml "$cd\evidence\cx.xml" 2>&1 | Out-Null
  $raw = Get-Content "$cd\evidence\cx.xml" -Raw -Encoding UTF8
  foreach ($m in [regex]::Matches($raw, '<node[^>]*>')) {
    if ($m.Value -notmatch 'class="[^"]*EditText"') { continue }
    $b=[regex]::Match($m.Value,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    return @([int](([int]$b.Groups[1].Value+[int]$b.Groups[3].Value)/2), [int](([int]$b.Groups[2].Value+[int]$b.Groups[4].Value)/2))
  }
  return @(683,268)
}

foreach ($t in @("E2ECHK5554u1s rowA first","E2ECHK5554u1s rowB second","E2ECHK5554u1s rowC third")) {
  $q = CP
  & $adb -s $d shell input tap $q[0] $q[1]
  Start-Sleep -Milliseconds 450
  & $adb -s $d shell input text ($t -replace ' ','%s')
  Start-Sleep -Milliseconds 600
  pwsh -NoProfile -File "$cd\tap_queue.ps1" -Tag qq
}
"--- strip ---"
pwsh -NoProfile -File "$cd\parse.ps1" -Tag strip3 -YMin 150 -YMax 300
