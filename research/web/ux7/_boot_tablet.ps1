$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$emu = Join-Path $sdk "emulator\emulator.exe"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$OUT = "C:\sensorsdata\main\program\opencode-android-shell\research\web\ux7"
New-Item -ItemType Directory -Force -Path $OUT | Out-Null
$log = "$OUT\_boot_tablet.log"
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m; Add-Content -Path $log -Value $s -Encoding utf8; Write-Output $s }

$S = "emulator-6632"
$mine = "UX7_TABLET_SWEEP|avd=OC_Deliver_R2|port=6632|serial=emulator-6632"
Set-Content -Path "$OUT\_mine.txt" -Value $mine -Encoding utf8
L "=== UX7 boot TABLET: avd=OC_Deliver_R2 port=6632 (MINE) ==="

L "PRECHECK adb devices: $((& $adb devices 2>&1) -join ' / ')"
foreach ($pn in 6632,6632,6634) {
  $busy = Get-NetTCPConnection -LocalPort $pn -State Listen -ErrorAction SilentlyContinue
  L "PRECHECK port ${pn}: $(if($busy){"BUSY pid=$($busy.OwningProcess)"}else{'FREE'})"
}

# 手机档几何：2560x1600 @ 420（与产品负责人 1200x2670 同档比例 20:9）
$args = @('-avd','OC_Deliver_R2','-port','6632','-no-snapshot','-no-boot-anim','-camera-back','virtualscene')
$p = Start-Process -FilePath $emu -ArgumentList $args -WindowStyle Hidden -PassThru -RedirectStandardError "$OUT\_emu_TABLET.err.log" -RedirectStandardOutput "$OUT\_emu_TABLET.out.log"
L "emulator launched pid=$($p.Id)"

$sw = [Diagnostics.Stopwatch]::StartNew()
$booted = $false
while ($sw.Elapsed.TotalSeconds -lt 240) {
  $st = (& $adb -s $S shell getprop sys.boot_completed 2>&1) -join ''
  if ($st -match '1') { $booted = $true; L "BOOT_COMPLETED after $([int]$sw.Elapsed.TotalSeconds) s"; break }
  Start-Sleep -Seconds 3
}
if (-not $booted) { L "BOOT TIMEOUT"; exit 1 }
Start-Sleep -Seconds 4
L "PHYS wm size: $((& $adb -s $S shell wm size) -join ' | ')"
L "PHYS wm density: $((& $adb -s $S shell wm density) -join ' | ')"
L "SET size: $((& $adb -s $S shell wm size 2560x1600) -join ' | ')"
L "SET density: $((& $adb -s $S shell wm density 420) -join ' | ')"
L "AFTER wm size: $((& $adb -s $S shell wm size) -join ' | ')"
L "AFTER wm density: $((& $adb -s $S shell wm density) -join ' | ')"
$wv = (& $adb -s $S shell dumpsys package com.google.android.webview 2>&1 | Select-String 'versionName' | Select-Object -First 1) -join ''
L "webview: $wv"
& $adb -s $S reverse tcp:4095 tcp:4095 | Out-Null
L "REVERSE_LIST: $((& $adb -s $S reverse --list 2>&1) -join ' / ')"
L "=== UX7 boot TABLET done ==="
