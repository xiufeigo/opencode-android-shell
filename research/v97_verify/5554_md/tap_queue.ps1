# Helper: queue the current composer content by finding the Queue chip at runtime
param([string]$Tag = "q")
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$d   = "emulator-5554"
$ev  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\evidence"

& $adb -s $d shell uiautomator dump --compressed /sdcard/qc.xml 2>&1 | Out-Null
& $adb -s $d pull /sdcard/qc.xml "$ev\qc.xml" 2>&1 | Out-Null
$raw = Get-Content "$ev\qc.xml" -Raw -Encoding UTF8

$m = [regex]::Match($raw, '<node[^>]*text="Queue"[^>]*>')
if (-not $m.Success) { "Queue chip NOT FOUND"; exit 1 }
$b = [regex]::Match($m.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
$cx = [int](([int]$b.Groups[1].Value + [int]$b.Groups[3].Value)/2)
$cy = [int](([int]$b.Groups[2].Value + [int]$b.Groups[4].Value)/2)
"Queue chip center = $cx,$cy"
& $adb -s $d shell input tap $cx $cy
Start-Sleep -Seconds 2
