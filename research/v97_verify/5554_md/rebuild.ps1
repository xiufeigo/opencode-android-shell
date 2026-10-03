# Rebuild a running turn + a 3-row queue strip, then open the strip.
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$d   = "emulator-5554"
$cd  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md"

function TapBy([string]$w) { pwsh -NoProfile -File "$cd\tapby.ps1" -What $w }

# 1. long turn
& $adb -s $d shell input tap 683 268
Start-Sleep -Milliseconds 700
& $adb -s $d shell input text ("E2ECHK5554u1s explain the Silk Road trade network in exhaustive detail, at least 30 numbered points" -replace ' ','%s')
Start-Sleep -Milliseconds 800
TapBy "Send"
Start-Sleep -Seconds 4

# 2. queue item A
& $adb -s $d shell input tap 683 268
Start-Sleep -Milliseconds 700
& $adb -s $d shell input text ("E2ECHK5554u1s queue A first" -replace ' ','%s')
Start-Sleep -Milliseconds 800
pwsh -NoProfile -File "$cd\tap_queue.ps1" -Tag rA

# 3. queue item B
& $adb -s $d shell input tap 683 268
Start-Sleep -Milliseconds 700
& $adb -s $d shell input text ("E2ECHK5554u1s queue B second" -replace ' ','%s')
Start-Sleep -Milliseconds 800
pwsh -NoProfile -File "$cd\tap_queue.ps1" -Tag rB

# 4. queue item C
& $adb -s $d shell input tap 683 268
Start-Sleep -Milliseconds 700
& $adb -s $d shell input text ("E2ECHK5554u1s queue C third" -replace ' ','%s')
Start-Sleep -Milliseconds 800
pwsh -NoProfile -File "$cd\tap_queue.ps1" -Tag rC

"--- resulting strip ---"
pwsh -NoProfile -File "$cd\parse.ps1" -Tag rebuilt -YMin 150 -YMax 265
