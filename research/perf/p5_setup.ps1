# p5_setup.ps1 — P5 前置流程：等 boot → 几何 → 装包 → adb reverse → 播种口令（web+native）
# 纪律：只 -s 自己起的 emulator-5840；只 adb reverse，不起停后端。
param(
  [string]$Dev = 'emulator-5840',
  [int]$BootTimeoutSec = 240
)
$ErrorActionPreference = 'Continue'
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Out = "$PSScriptRoot\out_p5"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
function A { & $Adb -s $Dev @args }
function Say { param($m) Write-Output ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }

Say "=== [1/6] 等待 boot ==="
$sw = [Diagnostics.Stopwatch]::StartNew()
$boot = ''
while ($sw.Elapsed.TotalSeconds -lt $BootTimeoutSec) {
  $boot = ((A shell getprop sys.boot_completed 2>$null) | Out-String).Trim()
  if ($boot -eq '1') { break }
  Start-Sleep -Seconds 2
}
if ($boot -ne '1') { Say "!! BOOT TIMEOUT after $($sw.Elapsed.TotalSeconds)s"; exit 2 }
Say ("  boot_completed=1 after {0:N1}s" -f $sw.Elapsed.TotalSeconds)

Say "=== [2/6] 几何 1080x2400@420（与 P4 同，便于对比） ==="
A shell wm size 1080x2400 | Out-Null
A shell wm density 420 | Out-Null
Start-Sleep -Seconds 2
(A shell wm size)  | Write-Output
(A shell wm density) | Write-Output

Say "=== [3/6] 安装 APK（web + 原生） ==="
$webApk = "$PSScriptRoot\..\deliver\opencode-shell-web.apk"
$natApk = "$PSScriptRoot\..\deliver\opencode-shell-debug.apk"
Say "  web apk exists=$(Test-Path $webApk) md5=$((Get-FileHash $webApk -Algorithm MD5).Hash)"
Say "  native apk exists=$(Test-Path $natApk) md5=$((Get-FileHash $natApk -Algorithm MD5).Hash)"
A install -r -g $webApk 2>&1 | Select-Object -Last 2 | Write-Output
A install -r -g $natApk 2>&1 | Select-Object -Last 2 | Write-Output

Say "=== [4/6] adb reverse tcp:4095（只映射，不起停后端） ==="
A reverse --remove-all 2>&1 | Out-Null
A reverse tcp:4095 tcp:4095 2>&1 | Write-Output
(A reverse --list) | Write-Output

Say "=== [5/6] 播种口令（web） ==="
& "$PSScriptRoot\seed_password.ps1" -Side web -Dev $Dev 2>&1 | Write-Output
Say "=== [5b/6] 播种口令（native） ==="
& "$PSScriptRoot\seed_native.ps1" -Side native -Dev $Dev 2>&1 | Write-Output

Say "=== [6/6] 指纹 ==="
$fp = @()
$fp += "label=p5 pkg=web act=WebHostActivity"
$fp += "ro.product.model  = " + (((A shell getprop ro.product.model) | Out-String).Trim())
$fp += "ro.build.version.release = " + (((A shell getprop ro.build.version.release) | Out-String).Trim())
$fp += "ro.build.version.sdk = " + (((A shell getprop ro.build.version.sdk) | Out-String).Trim())
$fp += "ro.product.cpu.abi = " + (((A shell getprop ro.product.cpu.abi) | Out-String).Trim())
$fp += "uptime: " + (((A shell cat /proc/uptime) | Out-String).Trim())
$fp += "wm size: " + (((A shell wm size) | Out-String).Trim() -replace "`r?`n", ' / ')
$fp += "wm density: " + (((A shell wm density) | Out-String).Trim() -replace "`r?`n", ' / ')
$fp += "webview: " + (((A shell dumpsys package com.google.android.webview | Select-String 'versionName') | Out-String).Trim() -replace "`r?`n", ' ')
$fp += "reverse: " + (((A reverse --list) | Out-String).Trim() -replace "`r?`n", ' | ')
$fp -join "`n" | Out-File -Encoding utf8 (Join-Path $Out 'fingerprint.txt')
$fp | Write-Output
Say "SETUP DONE"
