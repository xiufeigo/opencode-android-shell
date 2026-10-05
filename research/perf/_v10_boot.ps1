$ErrorActionPreference = 'Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$emu = Join-Path $sdk "emulator\emulator.exe"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$log = "C:\sensorsdata\main\program\opencode-android-shell\research\perf\out_v10\boot.log"
New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m; Add-Content -Path $log -Value $s -Encoding utf8; Write-Output $s }

L "=== V10 boot start: avd=OC_Deliver_R2 port=6210 ==="
$args = @('-avd','OC_Deliver_R2','-port','6210','-no-snapshot','-no-boot-anim','-camera-back','virtualscene')
$p = Start-Process -FilePath $emu -ArgumentList $args -WindowStyle Hidden -PassThru -RedirectStandardError "C:\sensorsdata\main\program\opencode-android-shell\research\perf\out_v10\emu.err.log" -RedirectStandardOutput "C:\sensorsdata\main\program\opencode-android-shell\research\perf\out_v10\emu.out.log"
L "emulator launched pid=$($p.Id)"

$sw = [Diagnostics.Stopwatch]::StartNew()
$booted = $false
while ($sw.Elapsed.TotalSeconds -lt 300) {
  $st = (& $adb -s emulator-6210 shell getprop sys.boot_completed 2>&1) -join ''
  if ($st -match '1') { $booted = $true; L "BOOT_COMPLETED after $([int]$sw.Elapsed.TotalSeconds) s"; break }
  Start-Sleep -Seconds 3
}
if (-not $booted) { L "BOOT TIMEOUT"; exit 1 }
Start-Sleep -Seconds 5
L "wm size: $((& $adb -s emulator-6210 shell wm size) -join ' | ')"
L "wm density: $((& $adb -s emulator-6210 shell wm density) -join ' | ')"
L "packages matching opencode: $((& $adb -s emulator-6210 shell pm list packages 2>&1 | Select-String 'opencodeshell') -join ' | ')"
L "=== V10 boot done ==="
