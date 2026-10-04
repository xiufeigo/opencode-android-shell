# p4_final.ps1 — P4 一体化：重启设备 → 设几何/reverse → 跑 web 四量中的帧时+输入延迟 → 跑 native 同两项。
# 起因：本轮设备**两次被外部 kill**（21:37:44 / 21:42:00），所以把所有步骤串成一条不间断的链，
# 减少人工往返造成的空窗。任何一步失败都会在 status 里留痕，不静默。
$ErrorActionPreference = 'Continue'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$perf = $PSScriptRoot
$st = @()
function Note { param($m) $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m; Write-Output $s; $script:st += $s }

$dev = 'emulator-5830'
$port = 5830
$avdName = 'OC_Perf_Test'

# ---------- 1) 重启自己的模拟器 ----------
$alive = (& $adb devices) -match $dev
if ($alive) { Note "device $dev already present, reuse" }
else {
  Note "device gone (killed externally) -> relaunching $avdName on $port"
  $log = "$perf\_emulator_boot3.log"
  $p = Start-Process -FilePath "$sdk\emulator\emulator.exe" `
    -ArgumentList "-avd $avdName -port $port -no-snapshot-load -no-boot-anim -no-audio -gpu swiftshader_indirect -no-metrics" `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -WindowStyle Hidden
  Note "EMU_PID=$($p.Id)"
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  $deadline = (Get-Date).AddSeconds(200); $ok = $false
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    $t = (& $adb -s $dev shell getprop sys.boot_completed 2>$null | Out-String).Trim()
    if ($t -eq '1') { $ok = $true; $sw.Stop(); Note ("REBOOT_OK_WALL_SEC=" + [math]::Round($sw.Elapsed.TotalSeconds,1)); break }
    if ($p.HasExited) { Note "EXITED EARLY code=$($p.ExitCode)"; break }
  }
  if (-not $ok) { Note "REBOOT_FAILED"; $st -join "`n" | Out-File -Encoding utf8 "$perf\out_p4\_status.txt"; exit 1 }
}

# ---------- 2) 几何 + reverse ----------
& $adb -s $dev shell wm size 1080x2400 | Out-Null
& $adb -s $dev shell wm density 420 | Out-Null
& $adb -s $dev reverse tcp:4095 tcp:4095 | Out-Null
Start-Sleep -Seconds 2
Note ("wm size: " + ((& $adb -s $dev shell wm size) -join ' | '))
Note ("wm density: " + ((& $adb -s $dev shell wm density) -join ' | '))
Note ("reverse: " + ((& $adb -s $dev reverse --list) -join ' '))

# ---------- 3) 凭据复核（新建 AVD 必须播种；重启后应仍保留）----------
& $adb -s $dev logcat -c | Out-Null
& $adb -s $dev shell am start -n com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity | Out-Null
Start-Sleep -Seconds 7
$w = (& $adb -s $dev logcat -d -s OcWebHost:V) -join "`n"
if ($w -match 'passwordPresent=true') { Note "web creds: PRESENT (passwordPresent=true)" }
elseif ($w -match 'no password in SecretStore') { Note "web creds: MISSING -> re-seed needed!" }
Note (($w -split "`n" | Where-Object { $_ -match 'home=' } | Select-Object -First 1))
& $adb -s $dev shell am force-stop com.xiufeigo.opencodeshell.web.debug
& $adb -s $dev shell am start -n com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.OcActivity | Out-Null
Start-Sleep -Seconds 7
& $adb -s $dev shell uiautomator dump /sdcard/_c.xml | Out-Null
$xml = (& $adb -s $dev cat /sdcard/_c.xml 2>$null) -join ''
if (-not $xml) { $xml = (& $adb -s $dev shell cat /sdcard/_c.xml) -join '' }
$t = @(); foreach ($m in [regex]::Matches($xml, '<node[^>]*')) { if ($m.Value -match 'text="([^"]+)"') { $t += $Matches[1] } }
Note ("native screen: " + (($t | Select-Object -Unique -First 6) -join ' | '))
& $adb -s $dev shell am force-stop com.xiufeigo.opencodeshell
Start-Sleep -Seconds 2

# ---------- 4) 两侧帧时 + 输入延迟 ----------
Note ("load at start: " + ((& $adb -s $dev shell uptime) -join ' '))
Note ">>> WEB ft_run"
& pwsh -NoProfile -File "$perf\ft_run.ps1" -Side web -Dev $dev
Note ">>> NATIVE ft_run"
& pwsh -NoProfile -File "$perf\ft_run.ps1" -Side native -Dev $dev
Note "ALL DONE"
$st -join "`n" | Out-File -Encoding utf8 "$perf\out_p4\_status.txt"
Write-Output "status written"
