param([string]$Serial = 'emulator-6190', [int]$Port = 9222)
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$out = "$PSScriptRoot\out_v9"; New-Item -ItemType Directory -Force -Path $out | Out-Null
function Say($m) { Write-Host $m }

# ---- [1] boot wait ----
Say "=== [1] boot wait ==="
$t0 = Get-Date
$boot = ''
for ($i = 0; $i -lt 150; $i++) {
  $boot = ((& $adb -s $Serial shell getprop sys.boot_completed 2>$null) | Out-String).Trim()
  if ($boot -eq '1') { break }
  Start-Sleep -Seconds 2
}
$bootSec = [int]((Get-Date) - $t0).TotalSeconds
Say "  boot_completed='$boot'  elapsed=${bootSec}s"
if ($boot -ne '1') { Say "  !! BOOT NOT COMPLETED"; }

# stability: system_server pid stable
$p1 = ((& $adb -s $Serial shell pidof system_server) | Out-String).Trim(); Start-Sleep -Seconds 6
$p2 = ((& $adb -s $Serial shell pidof system_server) | Out-String).Trim()
Say "  system_server pid: $p1 / $p2  stable=$($p1 -eq $p2)"

Say "=== [2] geometry baseline (must be untouched physical) ==="
$gsz = (& $adb -s $Serial shell wm size) 2>&1 | Out-String
$gd  = (& $adb -s $Serial shell wm density) 2>&1 | Out-String
Say "  $($gsz.Trim())"
Say "  $($gd.Trim())"

Say "=== [3] install deliverable ==="
$apk = "C:\sensorsdata\main\program\opencode-android-shell\research\deliver\opencode-shell-web-release-nor8.apk"
$md5 = (Get-FileHash $apk -Algorithm MD5).Hash
$len = (Get-Item $apk).Length
Say "  apk=$apk bytes=$len md5=$md5"
$pkgsBefore = (& $adb -s $Serial shell pm list packages 2>$null) -join ' '
$inst = (& $adb -s $Serial install -r -t $apk 2>&1) | Out-String
Say "  install: $($inst.Trim())"

Say "=== [4] package / activity identity ==="
$aapt = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
if ($aapt) {
  $bad = (& $aapt.FullName\aapt2.exe dump badging $apk 2>$null) -join "`n"
  $bad | Select-String -Pattern "^package:|launchable-activity:|sdkVersion|targetSdkVersion" | ForEach-Object { "  $($_.Line)" }
}

Say "=== [5] adb reverse (backend: NO start/stop, only reverse) ==="
$l = (& $adb -s $Serial reverse tcp:4095 tcp:4095 2>&1) | Out-String; Say "  reverse: $($l.Trim())"
Say "  reverse --list:"; (& $adb -s $Serial reverse --list) 2>&1 | ForEach-Object { "    $_" }

Say "=== [6] device fingerprint ==="
$fp = @()
$fp += "ro.product.name       = " + ((& $adb -s $Serial shell getprop ro.product.name) | Out-String).Trim()
$fp += "ro.product.model      = " + ((& $adb -s $Serial shell getprop ro.product.model) | Out-String).Trim()
$fp += "ro.build.version.sdk   = " + ((& $adb -s $Serial shell getprop ro.build.version.sdk) | Out-String).Trim()
$fp += "ro.build.version.release = " + ((& $adb -s $Serial shell getprop ro.build.version.release) | Out-String).Trim()
$fp += "ro.product.cpu.abi    = " + ((& $adb -s $Serial shell getprop ro.product.cpu.abi) | Out-String).Trim()
$fp += "wm size               = " + (($gsz -replace "`r?`n", ' | ').Trim())
$fp += "wm density            = " + (($gd  -replace "`r?`n", ' | ').Trim())
$up = (& $adb -s $Serial shell "cat /proc/uptime") | Out-String
$fp += "uptime(raw)           = " + $up.Trim()
$fp += "uptime(human)          = $([int]([double]($up.Trim().Split(' ')[0])/3600)) h"
$wv = (& $adb -s $Serial shell "dumpsys package com.google.android.webview | grep -m1 versionName") 2>&1 | Out-String
$fp += "webview               = " + $wv.Trim()
$sd = (& $adb -s $Serial shell "df -h /data | tail -1") | Out-String
$fp += "data                  = " + $sd.Trim()
$fp += "apk bytes             = $len"
$fp += "apk md5               = $md5"
$fp += "serial                = $Serial"
$fp += "boot elapsed          = ${bootSec}s"
$fp | Set-Content "$out\fingerprint.txt" -Encoding UTF8
$fp | ForEach-Object { Say "  $_" }
Say "=== V9 BOOT DONE ==="
