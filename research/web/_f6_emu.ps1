$ErrorActionPreference = 'Stop'
$emu = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$port = 6170
$avd  = 'OC_Deliver_R2'
$log  = "C:\sensorsdata\main\program\opencode-android-shell\research\web\_f6_emu.log"
$err  = "C:\sensorsdata\main\program\opencode-android-shell\research\web\_f6_emu.log.err"

# pre-flight: refuse if port busy
$busy = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if ($busy) { Write-Output "ABORT: port $port already in use"; exit 1 }
$other = Get-NetTCPConnection -LocalPort ($port+2) -State Listen -ErrorAction SilentlyContinue
if ($other) { Write-Output "ABORT: port $($port+2) in use"; exit 1 }

Write-Output "LAUNCH avd=$avd port=$port at $(Get-Date -Format HH:mm:ss)"
$p = Start-Process -FilePath $emu -ArgumentList @(
  '-avd', $avd, '-port', $port,
  '-no-snapshot', '-no-boot-anim',
  '-camera-back', 'virtualscene', '-camera-front', 'emulated',
  '-gpu', 'swiftshader_indirect', '-no-audio'
) -PassThru -RedirectStandardOutput $log -RedirectStandardError $err
Write-Output "EMU_PID=$($p.Id)"
Write-Output "SERIAL=emulator-$port"
