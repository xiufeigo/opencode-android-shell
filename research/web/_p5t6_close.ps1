$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S = 'emulator-5680'
$OUT = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\out_p5t6'
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss.fff'), $m; Add-Content -Path "$OUT\closeout.log" -Value $s -Encoding utf8; Write-Host $s }

L "=== CLOSEOUT ==="
L ("serial touched: $S")

# 1. reverse / forward remove
& $adb -s $S reverse --remove-all 2>&1 | Out-Null
& $adb -s $S forward --remove-all 2>&1 | Out-Null
$rl = (& $adb -s $S reverse --list 2>&1 | Out-String).Trim()
$fl = (& $adb -s $S forward --list 2>&1 | Out-String).Trim()
L ("reverse --list => '" + $rl + "'  (empty=clean)")
L ("forward --list => '" + $fl + "'  (empty=clean)")

# 2. stop logcat collector
$lpid = [int](Get-Content "$OUT\logcat.pid" -ErrorAction SilentlyContinue)
$p = Get-Process -Id $lpid -ErrorAction SilentlyContinue
if ($p) { Stop-Process -Id $lpid -Force; Start-Sleep -Milliseconds 700 }
L ("logcat pid $lpid stopped = " + ((Get-Process -Id $lpid -ErrorAction SilentlyContinue) -eq $null))
L ("logcat file size = " + (Get-Item "$OUT\logcat_full.txt").Length + " bytes")

# 3. remove pushed apk
& $adb -s $S shell "rm -f /data/local/tmp/oc.apk" 2>&1 | Out-Null
$chk = (& $adb -s $S shell "ls /data/local/tmp/oc.apk 2>&1" | Out-String).Trim()
L ("remote apk removed (ls => '$chk')")

# 4. backend pid unchanged + serving
$bp = Get-Process -Id 21136 -ErrorAction SilentlyContinue
L ("backend pid 21136 alive = " + ($bp -ne $null) + " start=" + $(if($bp){$bp.StartTime}else{'n/a'}))
$net = (netstat -ano | Select-String '127.0.0.1:4095' | Select-Object -First 2)
L ("netstat: " + ($net -join ' | '))
try { $r = Invoke-WebRequest -Uri 'http://127.0.0.1:4095/api/info' -TimeoutSec 8 -UseBasicParsing; L ("http 200? " + $r.StatusCode) }
catch { L ("http probe (expect 401 = serving): " + $_.Exception.Message) }

# 5. emulators NOT killed
$emu = Get-Process | Where-Object { $_.ProcessName -like 'emulator*' -or $_.ProcessName -like 'qemu*' }
L ("emulator/qemu procs still running: " + (($emu | ForEach-Object { $_.Id }) -join ','))

# 6. serials
L ("adb devices: " + ((& $adb devices -l) | Out-String).Trim())
L "=== CLOSEOUT DONE ==="
