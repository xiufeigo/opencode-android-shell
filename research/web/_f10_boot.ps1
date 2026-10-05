$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$emu = Join-Path $sdk "emulator\emulator.exe"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$OUT = "C:\sensorsdata\main\program\opencode-android-shell\research\web\out_f10"
New-Item -ItemType Directory -Force -Path $OUT | Out-Null
$log = "$OUT\boot.log"
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m; Add-Content -Path $log -Value $s -Encoding utf8; Write-Output $s }

$S = "emulator-6270"
$mine = "F10_IME_CONTENT_OVERLAP|avd=OC_Deliver_R2|port=6270|serial=emulator-6270"
Set-Content -Path "$OUT\_mine.txt" -Value $mine -Encoding utf8
L "=== F10 boot: avd=OC_Deliver_R2 port=6270 (MINE) ==="

L "PRECHECK adb devices: $((& $adb devices 2>&1) -join ' / ')"
$preProcs = (Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue | Measure-Object).Count
L "PRECHECK emulator/qemu proc count = $preProcs"
foreach ($pn in 6270,6272,6274,6276,6278) {
  $busy = Get-NetTCPConnection -LocalPort $pn -State Listen -ErrorAction SilentlyContinue
  L "PRECHECK port ${pn}: $(if($busy){"BUSY pid=$($busy.OwningProcess)"}else{'FREE'})"
}
$b = Get-NetTCPConnection -LocalPort 4095 -State Listen -ErrorAction SilentlyContinue
L "PRECHECK 4095 backend: $(if($b){"LISTEN pid=$($b.OwningProcess)"}else{'NOT LISTEN'}) (DO NOT TOUCH)"

# 不传 -skin（pixel_tablet 不是合法 skin 名，沿用 V7/F5 教训）
# 几何高度 <= 1600，避开 F4/R2/V6 记录的「2400 > 物理 1600」静默退出形态
$args = @('-avd','OC_Deliver_R2','-port','6270','-no-snapshot','-no-boot-anim','-camera-back','virtualscene')
$p = Start-Process -FilePath $emu -ArgumentList $args -WindowStyle Hidden -PassThru -RedirectStandardError "$OUT\emu.err.log" -RedirectStandardOutput "$OUT\emu.out.log"
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
L "SET size: $((& $adb -s $S shell wm size 1080x1600) -join ' | ')"
L "SET density: $((& $adb -s $S shell wm density 420) -join ' | ')"
L "AFTER wm size: $((& $adb -s $S shell wm size) -join ' | ')"
L "AFTER wm density: $((& $adb -s $S shell wm density) -join ' | ')"
$wv = (& $adb -s $S shell dumpsys package com.google.android.webview 2>&1 | Select-String 'versionName' | Select-Object -First 1) -join ''
L "webview: $wv"
& $adb -s $S reverse tcp:4095 tcp:4095 | Out-Null
L "REVERSE_LIST: $((& $adb -s $S reverse --list 2>&1) -join ' / ')"
L "=== F10 boot done ==="
