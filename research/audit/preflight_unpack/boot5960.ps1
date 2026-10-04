# boot5960.ps1 -- N-6 preflight-in-package verification round
# Rules (research/HOUSEKEEPING.md): port >= 5940 EVEN, check occupancy first, only kill OC_*/Medium_Desktop_B.
# ONLY adb reverse; do NOT start/stop backend 4095; no adb kill-server; no -a/wildcard.
$ErrorActionPreference = 'Continue'
$Emu = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$P   = $PSScriptRoot
$Out = "$P\out5960"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Log = "$Out\_boot.log"
"" | Out-File -Encoding utf8 $Log
function Say { param($m) $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m; Write-Host $s; $s | Out-File -Append -Encoding utf8 $Log }

$Port = 5960
$Avd  = 'OC_Preflight_V3'
$Dev  = "emulator-$Port"

$busy = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue).Count
if ($busy -gt 0) { Say "!! port $Port occupied, aborting"; exit 9 }
$dup = Get-CimInstance Win32_Process -Filter "Name='emulator.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match "-avd\s+$Avd\b" }
if ($dup) { Say "!! AVD $Avd already running, aborting"; exit 9 }

Say "=== [1/4] boot AVD=$Avd port=$Port, geometry 1080x2400@420 ==="
Start-Process -FilePath $Emu -ArgumentList '-avd',$Avd,'-port',"$Port",'-no-snapshot-load','-no-boot-anim','-no-audio','-gpu','swiftshader_indirect','-no-metrics' `
  -RedirectStandardOutput "$Out\_emu_$Port.log" -RedirectStandardError "$Out\_emu_$Port.err" -WindowStyle Hidden
$sw = [Diagnostics.Stopwatch]::StartNew()
$boot = ''
while ($sw.Elapsed.TotalSeconds -lt 420) {
  $boot = ((& $Adb -s $Dev shell getprop sys.boot_completed 2>$null) | Out-String).Trim()
  if ($boot -eq '1') { break }
  Start-Sleep -Seconds 2
}
if ($boot -ne '1') { Say "!! BOOT TIMEOUT after $([math]::Round($sw.Elapsed.TotalSeconds,1))s"; exit 2 }
Say ("  boot_completed=1 after {0:N1}s" -f $sw.Elapsed.TotalSeconds)

Say "=== [2/4] settle 5s then set 1080x2400@420 ==="
Start-Sleep -Seconds 5
& $Adb -s $Dev shell wm size 1080x2400 | Out-Null
& $Adb -s $Dev shell wm density 420 | Out-Null
Start-Sleep -Seconds 2
Say ("  wm size: " + (((& $Adb -s $Dev shell wm size) | Out-String).Trim() -replace "`r?`n", ' / '))
Say ("  wm density: " + (((& $Adb -s $Dev shell wm density) | Out-String).Trim() -replace "`r?`n", ' / '))

Say "=== [3/4] adb reverse tcp:4095 (reverse only, no backend start/stop) ==="
& $Adb -s $Dev reverse --remove-all 2>&1 | Out-Null
& $Adb -s $Dev reverse tcp:4095 tcp:4095 2>&1 | Out-Null
Say ("  reverse: " + (((& $Adb -s $Dev reverse --list) | Out-String).Trim() -replace "`r?`n", ' | '))

Say "=== [4/4] fingerprint ==="
$fp = @()
$fp += "serial=$Dev avd=$Avd port=$Port"
$fp += "flags: -no-snapshot-load -no-boot-anim -no-audio -gpu swiftshader_indirect -no-metrics"
$fp += "ro.product.model  = " + (((& $Adb -s $Dev shell getprop ro.product.model) | Out-String).Trim())
$fp += "ro.build.version.release = " + (((& $Adb -s $Dev shell getprop ro.build.version.release) | Out-String).Trim())
$fp += "ro.build.version.sdk = " + (((& $Adb -s $Dev shell getprop ro.build.version.sdk) | Out-String).Trim())
$fp += "ro.product.cpu.abi = " + (((& $Adb -s $Dev shell getprop ro.product.cpu.abi) | Out-String).Trim())
$fp += "boot_wall_s = " + [math]::Round($sw.Elapsed.TotalSeconds,1)
$fp += "wm size: " + (((& $Adb -s $Dev shell wm size) | Out-String).Trim() -replace "`r?`n", ' / ')
$fp += "wm density: " + (((& $Adb -s $Dev shell wm density) | Out-String).Trim() -replace "`r?`n", ' / ')
$fp += "webview versionName: " + (((& $Adb -s $Dev shell dumpsys package com.google.android.webview | Select-String 'versionName') | Out-String).Trim() -replace "`r?`n", ' ')
$fp += "reverse: " + (((& $Adb -s $Dev reverse --list) | Out-String).Trim() -replace "`r?`n", ' | ')
$fp -join "`n" | Out-File -Encoding utf8 "$Out\fingerprint.txt"
$fp | ForEach-Object { Say "  $_" }
Say "BOOT DONE"
