# Long-running turn + 3-row queue strip.
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$d   = "emulator-5554"
$cd  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md"

# clear the composer
& $adb -s $d shell input tap 683 268
Start-Sleep -Milliseconds 500
& $adb -s $d shell input keyevent KEYCODE_MOVE_END
for ($i=0; $i -lt 170; $i++) { & $adb -s $d shell input keyevent KEYCODE_DEL }
Start-Sleep -Milliseconds 800

# long task that cannot finish quickly
& $adb -s $d shell input text ("E2ECHK5554u1s count from 1 to 3000, one number per line" -replace ' ','%s')
Start-Sleep -Milliseconds 800
pwsh -NoProfile -File "$cd\tapby.ps1" -What "Send"
Start-Sleep -Seconds 2

"--- is it running? (expect Stop present) ---"
pwsh -NoProfile -File "$cd\parse.ps1" -Tag longstart -YMin 285 -YMax 345
