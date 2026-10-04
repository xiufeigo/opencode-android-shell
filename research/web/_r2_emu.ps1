$ErrorActionPreference = 'Continue'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$emu = "$sdk\emulator\emulator.exe"
$adb = "$sdk\platform-tools\adb.exe"
$S = "emulator-5900"
$avd = "OC_Deliver_R2"
$root = "C:\sensorsdata\main\program\opencode-android-shell"

$p = Get-NetTCPConnection -LocalPort 5900 -State Listen -ErrorAction SilentlyContinue
if ($p) { Write-Host "ABORT: port 5900 busy"; exit 1 }
Write-Host "port 5900 free -> launching $avd"
Start-Process -FilePath $emu -ArgumentList @("-avd",$avd,"-port","5900","-no-window","-no-audio","-no-boot-anim","-no-snapshot","-gpu","swiftshader_indirect","-camera-back","none","-camera-front","none") -WindowStyle Hidden -RedirectStandardError "$root\research\web\_r2_emu_5900.err" -RedirectStandardOutput "$root\research\web\_r2_emu_5900.log"

& $adb -s $S wait-for-device
$booted = $false
for ($i=0; $i -lt 100; $i++) {
  if ((& $adb -s $S shell getprop sys.boot_completed 2>$null | Out-String).Trim() -eq '1') { Write-Host "BOOTED (poll $i, ~$($i*3)s)"; $booted=$true; break }
  Start-Sleep -Seconds 3
}
if (-not $booted) { Write-Host "BOOT TIMEOUT"; exit 1 }

# geometry 1080x2400@420
& $adb -s $S shell wm size 1080x2400 | Out-Null
& $adb -s $S shell wm density 420 | Out-Null
Write-Host "--- geometry ---"
& $adb -s $S shell wm size
& $adb -s $S shell wm density
Write-Host "--- webview ---"
(& $adb -s $S shell dumpsys package com.google.android.webview 2>$null | Select-String 'versionName' | Select-Object -First 1)

# backend reverse only (backend already up by Lead)
& $adb -s $S reverse tcp:4095 tcp:4095 | Out-Null
Write-Host "--- reverse ---"
& $adb -s $S reverse --list
Write-Host "--- backend reachable from device? ---"
& $adb -s $S shell "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:4095/ 2>/dev/null || echo nocurl"
Write-Host ""
Write-Host "=== EMU READY ==="
