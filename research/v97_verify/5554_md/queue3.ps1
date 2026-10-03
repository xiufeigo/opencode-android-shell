$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$d   = "emulator-5554"
$cd  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md"

function ComposerPos {
  & $adb -s $d shell uiautomator dump --compressed /sdcard/cp.xml 2>&1 | Out-Null
  & $adb -s $d pull /sdcard/cp.xml "$cd\evidence\cp.xml" 2>&1 | Out-Null
  $raw = Get-Content "$cd\evidence\cp.xml" -Raw -Encoding UTF8
  foreach ($m in [regex]::Matches($raw, '<node[^>]*>')) {
    $n=$m.Value
    if ($n -notmatch 'class="[^"]*EditText"') { continue }
    $b=[regex]::Match($n,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    return @([int](([int]$b.Groups[1].Value+[int]$b.Groups[3].Value)/2), [int](([int]$b.Groups[2].Value+[int]$b.Groups[4].Value)/2))
  }
  return @(683,268)
}

$p = ComposerPos
"composer center = $($p[0]),$($p[1])"
& $adb -s $d shell input tap $p[0] $p[1]
Start-Sleep -Seconds 1
& $adb -s $d shell input keyevent KEYCODE_MOVE_END
for ($i=0; $i -lt 170; $i++) { & $adb -s $d shell input keyevent KEYCODE_DEL }
Start-Sleep -Milliseconds 800
"cleared. IME:"
& $adb -s $d shell dumpsys input_method 2>$null | Select-String "mInputShown" | Select-Object -First 1

foreach ($t in @("E2ECHK5554u1s rowA first","E2ECHK5554u1s rowB second","E2ECHK5554u1s rowC third")) {
  $q = ComposerPos
  & $adb -s $d shell input tap $q[0] $q[1]
  Start-Sleep -Milliseconds 500
  & $adb -s $d shell input text ($t -replace ' ','%s')
  Start-Sleep -Milliseconds 700
  pwsh -NoProfile -File "$cd\tap_queue.ps1" -Tag qz
}
"--- final strip ---"
pwsh -NoProfile -File "$cd\parse.ps1" -Tag final_strip -YMin 150 -YMax 700
