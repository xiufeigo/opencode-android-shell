# relaunch_p4.ps1 — 重启本轮自己的模拟器（上一台被外部 kill 掉了）。
# 复现：跑帧时阶段时 `device 'emulator-5830' not found`，boot log 停在
# "Boot completed in 36525 ms" 无崩溃信息 ⇒ **被别的 lane 的清理扫掉**，非本轮崩溃。
# 端口 5830 已释放，AVD 数据盘仍在 ⇒ 凭据应保留，重启后复核。
$ErrorActionPreference = 'Continue'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$avdName = 'OC_Perf_Test'
$log = "C:\sensorsdata\main\program\opencode-android-shell\research\perf\_emulator_boot2.log"
$sw = [System.Diagnostics.Stopwatch]::StartNew()
Write-Output "RELAUNCH_T0=$([DateTime]::Now.ToString('o'))"
$p = Start-Process -FilePath "$sdk\emulator\emulator.exe" `
  -ArgumentList "-avd $avdName -port 5830 -no-snapshot-load -no-boot-anim -no-audio -gpu swiftshader_indirect -no-metrics" `
  -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -WindowStyle Hidden
Write-Output "EMU_PID=$($p.Id)"
$deadline = (Get-Date).AddSeconds(240)
$ok = $false
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 3
  $st = (& $adb -s emulator-5830 shell getprop sys.boot_completed 2>$null | Out-String).Trim()
  if ($st -eq '1') { $ok = $true; $sw.Stop(); break }
  if ($p.HasExited) { Write-Output "EXITED EARLY code=$($p.ExitCode)"; Get-Content "$log.err" -Tail 20; break }
}
if ($ok) { Write-Output "REBOOT_OK_WALL_SEC=$([math]::Round($sw.Elapsed.TotalSeconds,1))" }
else { Write-Output "REBOOT_FAILED"; exit 1 }

# 重设几何（override 可能随实例丢失）
& $adb -s emulator-5830 shell wm size 1080x2400
& $adb -s emulator-5830 shell wm density 420
& $adb -s emulator-5830 reverse tcp:4095 tcp:4095
Start-Sleep -Seconds 2
Write-Output "wm size: $((& $adb -s emulator-5830 shell wm size) -join ' | ')"
Write-Output "wm density: $((& $adb -s emulator-5830 shell wm density) -join ' | ')"
Write-Output "reverse: $((& $adb -s emulator-5830 reverse --list) -join ' ')"
Write-Output "proc_uptime: $((& $adb -s emulator-5830 shell cat /proc/uptime) -join ' ')"

# 复核 web 凭据是否随 AVD 数据盘保留
& $adb -s emulator-5830 logcat -c | Out-Null
& $adb -s emulator-5830 shell am start -n com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity | Out-Null
Start-Sleep -Seconds 7
Write-Output "=== web creds check ==="
& $adb -s emulator-5830 logcat -d -s OcWebHost:V | Select-String 'no password in SecretStore|home=' | Select-Object -First 3 | ForEach-Object { $_.Line }
& $adb -s emulator-5830 shell am force-stop com.xiufeigo.opencodeshell.web.debug

# 复核 native 凭据
& $adb -s emulator-5830 shell am start -n com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.OcActivity | Out-Null
Start-Sleep -Seconds 8
& $adb -s emulator-5830 shell uiautomator dump /sdcard/_c.xml | Out-Null
$xml = (& $adb -s emulator-5830 shell cat /sdcard/_c.xml) -join ''
$t = @(); foreach ($m in [regex]::Matches($xml, '<node[^>]*')) { if ($m.Value -match 'text="([^"]+)"') { $t += $Matches[1] } }
Write-Output ("native first texts: " + (($t | Select-Object -Unique -First 8) -join ' | '))
& $adb -s emulator-5830 shell am force-stop com.xiufeigo.opencodeshell
