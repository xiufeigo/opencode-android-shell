# p6_boot.ps1 — P6（省电落实轮）设备引导
# 纪律（research/HOUSEKEEPING.md）：只起自己的 OC_* AVD（端口 >=5930 偶数，先查占用）；
# 只 adb reverse，不起停后端 4095；不 adb kill-server、不用 -a/通配符。
# 本脚本**只引导不装包**（baseline/after 两个 APK 由 P6 主流程分别安装，保证同机同轮次）。
$ErrorActionPreference = 'Continue'
$Emu = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$P   = "$PSScriptRoot"
$Out = "$P\out_p6"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Log = "$Out\_p6_boot.log"
"" | Out-File -Encoding utf8 $Log
function Say { param($m) $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m; Write-Host $s; $s | Out-File -Append -Encoding utf8 $Log }

$Port = 5940
$Avd  = 'OC_Deliver_R2'
$Dev  = "emulator-$Port"

# 端口占用先查，冲突就换不抢
$busy = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue).Count
if ($busy -gt 0) { Say "!! 端口 $Port 已被占用($busy)，中止（不抢）"; exit 9 }
# 已有同 AVD 进程？先报，不重复起（避免双开 adb 抢设备，E7 1.2 已知问题）
$dup = Get-CimInstance Win32_Process -Filter "Name='emulator.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match "-avd\s+$Avd\b" }
if ($dup) { Say "!! AVD $Avd 已在运行 pid=$($dup.ProcessId)，不重复启动"; exit 9 }

Say "=== [1/4] 启动 AVD=$Avd port=$Port（几何 1080x2400@420） ==="
Start-Process -FilePath $Emu -ArgumentList '-avd',$Avd,'-port',"$Port",'-no-snapshot-load','-no-boot-anim','-no-audio','-gpu','swiftshader_indirect','-no-metrics' `
  -RedirectStandardOutput "$P\_p6_emu_$Port.log" -RedirectStandardError "$P\_p6_emu_$Port.err" -WindowStyle Hidden
$sw = [Diagnostics.Stopwatch]::StartNew()
$boot = ''
while ($sw.Elapsed.TotalSeconds -lt 300) {
  $boot = ((& $Adb -s $Dev shell getprop sys.boot_completed 2>$null) | Out-String).Trim()
  if ($boot -eq '1') { break }
  Start-Sleep -Seconds 2
}
if ($boot -ne '1') { Say "!! BOOT TIMEOUT after $([math]::Round($sw.Elapsed.TotalSeconds,1))s"; exit 2 }
Say ("  boot_completed=1 after {0:N1}s" -f $sw.Elapsed.TotalSeconds)

Say "=== [2/4] 稳定 5s + 几何 1080x2400@420 ==="
Start-Sleep -Seconds 5
& $Adb -s $Dev shell wm size 1080x2400 | Out-Null
& $Adb -s $Dev shell wm density 420 | Out-Null
Start-Sleep -Seconds 2
Say ("  wm size: " + (((& $Adb -s $Dev shell wm size) | Out-String).Trim() -replace "`r?`n", ' / '))
Say ("  wm density: " + (((& $Adb -s $Dev shell wm density) | Out-String).Trim() -replace "`r?`n", ' / '))

Say "=== [3/4] adb reverse tcp:4095（只映射，不起停后端） ==="
& $Adb -s $Dev reverse --remove-all 2>&1 | Out-Null
& $Adb -s $Dev reverse tcp:4095 tcp:4095 2>&1 | Out-Null
Say ("  reverse: " + (((& $Adb -s $Dev reverse --list) | Out-String).Trim() -replace "`r?`n", ' | '))

Say "=== [4/4] 记录指纹（用于可比性留证） ==="
$fp = @()
$fp += "serial=$Dev avd=$Avd port=$Port"
$fp += "fingerprint(同 B1/P5): -no-snapshot-load -no-boot-anim -no-audio -gpu swiftshader_indirect -no-metrics"
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
