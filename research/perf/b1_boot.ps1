# b1_boot.ps1 — B1（延迟地板分离）设备引导
# 纪律：只起自己的 OC_Perf_Test（端口 5854）；只 adb reverse，不起停后端 4095。
$ErrorActionPreference = 'Continue'
$Emu = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$P   = "$PSScriptRoot"
$Out = "$P\out_b1"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Log = "$Out\_b1_boot.log"
"" | Out-File -Encoding utf8 $Log
function Say { param($m) $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m; Write-Host $s; $s | Out-File -Append -Encoding utf8 $Log }
$Port = 5854
$Avd  = 'OC_Perf_Test'
$Dev  = "emulator-$Port"

# 端口占用先查，冲突就换不抢
$busy = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue).Count
if ($busy -gt 0) { Say "!! 端口 $Port 已被占用($busy)，中止（不抢）"; exit 9 }
# 已有同 AVD 进程？先报，不重复起
$dup = Get-CimInstance Win32_Process -Filter "Name='emulator.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match "-avd\s+$Avd\b" }
if ($dup) { Say "!! AVD $Avd 已在运行 pid=$($dup.ProcessId)，不重复启动"; exit 9 }

Say "=== [1/5] 启动 AVD=$Avd port=$Port (flags 与 P5 逐字相同) ==="
Start-Process -FilePath $Emu -ArgumentList '-avd',$Avd,'-port',"$Port",'-no-snapshot-load','-no-boot-anim','-no-audio','-gpu','swiftshader_indirect','-no-metrics' `
  -RedirectStandardOutput "$P\_b1_emu_$Port.log" -RedirectStandardError "$P\_b1_emu_$Port.err" -WindowStyle Hidden
$sw = [Diagnostics.Stopwatch]::StartNew()
$boot = ''
while ($sw.Elapsed.TotalSeconds -lt 300) {
  $boot = ((& $Adb -s $Dev shell getprop sys.boot_completed 2>$null) | Out-String).Trim()
  if ($boot -eq '1') { break }
  Start-Sleep -Seconds 2
}
if ($boot -ne '1') { Say "!! BOOT TIMEOUT after $([math]::Round($sw.Elapsed.TotalSeconds,1))s"; exit 2 }
Say ("  boot_completed=1 after {0:N1}s" -f $sw.Elapsed.TotalSeconds)

Say "=== [2/5] 稳定 5s + 几何 1080x2400@420 ==="
Start-Sleep -Seconds 5
& $Adb -s $Dev shell wm size 1080x2400    | Out-Null
& $Adb -s $Dev shell wm density 420        | Out-Null
Start-Sleep -Seconds 2
Say ("  wm size: " + (((& $Adb -s $Dev shell wm size) | Out-String).Trim() -replace "`r?`n", ' / '))
Say ("  wm density: " + (((& $Adb -s $Dev shell wm density) | Out-String).Trim() -replace "`r?`n", ' / '))

Say "=== [3/5] 装 APK（web 壳 = L0/L1 宿主；native 壳 = L2） ==="
$webApk = "$P\..\deliver\opencode-shell-web.apk"
$natApk = "$P\..\deliver\opencode-shell-debug.apk"
Say ("  web apk md5    = " + (Get-FileHash $webApk -Algorithm MD5).Hash)
Say ("  native apk md5 = " + (Get-FileHash $natApk -Algorithm MD5).Hash)
Say ("  install web:    " + (((& $Adb -s $Dev install -r -g $webApk 2>&1) | Out-String).Trim()))
Say ("  install native: " + (((& $Adb -s $Dev install -r -g $natApk 2>&1) | Out-String).Trim()))

Say "=== [4/5] adb reverse tcp:4095（只映射，不起停后端） ==="
& $Adb -s $Dev reverse --remove-all 2>&1 | Out-Null
& $Adb -s $Dev reverse tcp:4095 tcp:4095 2>&1 | Out-Null
Say ("  reverse: " + (((& $Adb -s $Dev reverse --list) | Out-String).Trim() -replace "`r?`n", ' | '))

Say "=== [5/5] 播种口令（web + native），否则会弹配置框 ==="
Say ("  " + (((& pwsh -NoProfile -File "$P\seed_password.ps1" -Side web -Dev $Dev 2>&1) | Out-String).Trim() -replace "`r?`n", ' ;; '))
Say ("  " + (((& pwsh -NoProfile -File "$P\seed_native.ps1" -Side native -Dev $Dev 2>&1) | Out-String).Trim() -replace "`r?`n", ' ;; '))

$fp = @()
$fp += "serial=$Dev avd=$Avd port=$Port"
$fp += "fingerprint(同 P5): -no-snapshot-load -no-boot-anim -no-audio -gpu swiftshader_indirect -no-metrics"
$fp += "ro.product.model  = " + (((& $Adb -s $Dev shell getprop ro.product.model) | Out-String).Trim())
$fp += "ro.build.version.release = " + (((& $Adb -s $Dev shell getprop ro.build.version.release) | Out-String).Trim())
$fp += "ro.build.version.sdk = " + (((& $Adb -s $Dev shell getprop ro.build.version.sdk) | Out-String).Trim())
$fp += "ro.product.cpu.abi = " + (((& $Adb -s $Dev shell getprop ro.product.cpu.abi) | Out-String).Trim())
$fp += "boot_wall_s = " + [math]::Round($sw.Elapsed.TotalSeconds,1)
$fp += "wm size: " + (((& $Adb -s $Dev shell wm size) | Out-String).Trim() -replace "`r?`n", ' / ')
$fp += "wm density: " + (((& $Adb -s $Dev shell wm density) | Out-String).Trim() -replace "`r?`n", ' / ')
$fp += "webview: " + (((& $Adb -s $Dev shell dumpsys package com.google.android.webview | Select-String 'versionName') | Out-String).Trim() -replace "`r?`n", ' ')
$fp += "reverse: " + (((& $Adb -s $Dev reverse --list) | Out-String).Trim() -replace "`r?`n", ' | ')
$fp += "uptime: " + (((& $Adb -s $Dev shell cat /proc/uptime) | Out-String).Trim())
$fp -join "`n" | Out-File -Encoding utf8 "$Out\fingerprint.txt"
$fp | ForEach-Object { Say "  $_" }
Say "BOOT DONE"
