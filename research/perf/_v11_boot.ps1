$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$emu = Join-Path $sdk "emulator\emulator.exe"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$OUT = "C:\sensorsdata\main\program\opencode-android-shell\research\perf\out_v11"
New-Item -ItemType Directory -Force -Path $OUT | Out-Null
$log = "$OUT\boot.log"
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m; Add-Content -Path $log -Value $s -Encoding utf8; Write-Output $s }

# 归属登记（housekeeping：谁起谁关，按 AVD 名判定）
$mine = "V11_FORMFACTOR_REDO|avd=OC_Deliver_R2|port=6230|serial=emulator-6230"
Set-Content -Path "$OUT\_mine.txt" -Value $mine -Encoding utf8
L "=== V11 boot start: avd=OC_Deliver_R2 port=6230 (MINE) ==="

# 起前记录环境
L "PRECHECK adb devices: $((& $adb devices 2>&1) -join ' / ')"
$preProcs = (Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue | Measure-Object).Count
L "PRECHECK emulator/qemu proc count = $preProcs"
foreach ($pnum in 6230,6232,6234) {
  $busy = Get-NetTCPConnection -LocalPort $pnum -State Listen -ErrorAction SilentlyContinue
  L "PRECHECK port $pnum : $(if($busy){"BUSY pid=$($busy.OwningProcess)"}else{'FREE'})"
}
$b = Get-NetTCPConnection -LocalPort 4095 -State Listen -ErrorAction SilentlyContinue
L "PRECHECK 4095 backend : $(if($b){"LISTEN pid=$($b.OwningProcess)"}else{'NOT LISTEN'}) (do not touch)"

# 注意：不传 -skin（pixel_tablet 不是合法 skin 名）
$args = @('-avd','OC_Deliver_R2','-port','6230','-no-snapshot','-no-boot-anim','-camera-back','virtualscene')
$p = Start-Process -FilePath $emu -ArgumentList $args -WindowStyle Hidden -PassThru -RedirectStandardError "$OUT\emu.err.log" -RedirectStandardOutput "$OUT\emu.out.log"
L "emulator launched pid=$($p.Id)"

$sw = [Diagnostics.Stopwatch]::StartNew()
$booted = $false
while ($sw.Elapsed.TotalSeconds -lt 300) {
  $st = (& $adb -s emulator-6230 shell getprop sys.boot_completed 2>&1) -join ''
  if ($st -match '1') { $booted = $true; L "BOOT_COMPLETED after $([int]$sw.Elapsed.TotalSeconds) s"; break }
  Start-Sleep -Seconds 3
}
if (-not $booted) { L "BOOT TIMEOUT"; exit 1 }
Start-Sleep -Seconds 5
L "wm size: $((& $adb -s emulator-6230 shell wm size) -join ' | ')"
L "wm density: $((& $adb -s emulator-6230 shell wm density) -join ' | ')"
L "webview: $((& $adb -s emulator-6230 shell dumpsys package com.google.android.webview 2>&1 | Select-String 'versionName' | Select-Object -First 1) -join '')"
L "=== V11 boot done ==="
