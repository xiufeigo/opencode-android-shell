$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = 'emulator-6130'
$port = 6130
$avd = 'OC_Deliver_R2'
$out = 'C:\sensorsdata\main\program\opencode-android-shell\research\env\f5'

function Say($m) { "$((Get-Date).ToString('HH:mm:ss')) $m" | Tee-Object -FilePath "$out\boot.log" -Append }

# STEP 0: assert port free + even + >= 6130
if ($port % 2 -ne 0 -or $port -lt 6130) { throw "bad port $port" }
$busy = (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Measure-Object).Count
if ($busy -ne 0) { throw "port $port busy" }
Say "STEP0 ok port=$port even ge6130 free"

# STEP 1: boot. NOTE: no -skin (pixel_tablet is NOT a valid skin name, per V7 finding)
$p = Start-Process -FilePath "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" `
  -ArgumentList @('-avd', $avd, '-port', $port, '-no-snapshot-save', '-no-boot-anim',
                  '-camera-back', 'virtualscene', '-camera-front', 'none') `
  -RedirectStandardOutput "$out\emu_6130.log" -RedirectStandardError "$out\emu_6130.log.err" `
  -PassThru -WindowStyle Hidden
Say "STEP1 launched pid=$($p.Id) avd=$avd port=$port"

# STEP 2: wait for device + boot_completed (serial-scoped, single adb client)
& $adb -s $serial wait-for-device
$deadline = (Get-Date).AddSeconds(300)
$booted = $false
while ((Get-Date) -lt $deadline) {
  $v = (& $adb -s $serial shell getprop sys.boot_completed 2>$null) -join ''
  if ($v.Trim() -eq '1') { $booted = $true; break }
  Start-Sleep -Seconds 3
}
if (-not $booted) { Say "STEP2 TIMEOUT waiting for boot_completed"; exit 1 }
Say "STEP2 boot_completed=1"

# STEP 3: geometry override via wm (NOT -skin)
& $adb -s $serial shell wm size 1080x2400 | Out-Null
& $adb -s $serial shell wm density 420 | Out-Null
Start-Sleep -Seconds 2
$gs = (& $adb -s $serial shell wm size) -join ' '
$gd = (& $adb -s $serial shell wm density) -join ' '
Say "STEP3 wm size='$gs' wm density='$gd'"
if ($gs -notmatch 'Override size: 1080x2400') { Say "STEP3 FAIL no Override size" }
if ($gd -notmatch 'Override density: 420') { Say "STEP3 FAIL no Override density" }

# STEP 4: reverse to backend (never start/stop backend)
& $adb -s $serial reverse tcp:4095 tcp:4095
$rl = (& $adb -s $serial reverse --list) -join ' | '
Say "STEP4 reverse list='$rl'"

# STEP 5: unlock + wake
& $adb -s $serial shell input keyevent 82 | Out-Null
$w = (& $adb -s $serial shell dumpsys window | Select-String -Pattern 'mWakefulness|awake=' | Select-Object -First 3) -join ' ; '
Say "STEP5 wakefulness: $w"
Say "BOOT-OK serial=$serial"
