$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s = "emulator-6170"
$pkg = "com.xiufeigo.opencodeshell.web.debug"
$W = "C:\sensorsdata\main\program\opencode-android-shell\research\web"
$log = "$W\_f6_path23.txt"
"" | Set-Content $log
function L($m){ $s2=($m|Out-String).TrimEnd(); Write-Output $s2; $s2 | Add-Content $log }

# ================= PATH 2: WRONG password =================
L "############ PATH 2: WRONG PASSWORD ############"
& $adb -s $s shell am force-stop $pkg
& $adb -s $s shell pm clear $pkg | Out-Null
Start-Sleep -Seconds 2
& $adb -s $s logcat -c
& $adb -s $s shell am start -n "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity" | Out-Null
Start-Sleep -Seconds 6
& $adb -s $s shell input tap 540 1117 | Out-Null
Start-Sleep -Seconds 1
& $adb -s $s shell input text "wrongpw9" | Out-Null
Start-Sleep -Seconds 1
& $adb -s $s shell input tap 853 1388 | Out-Null
Start-Sleep -Seconds 9
& $adb -s $s exec-out screencap -p > "$W\_f6_path2_wrongpw.png"
& $adb -s $s shell uiautomator dump /sdcard/f6_p2.xml 2>&1 | Out-Null
& $adb -s $s pull /sdcard/f6_p2.xml "$W\_f6_p2.xml" 2>&1 | Out-Null
$a2 = Get-Content "$W\_f6_p2.xml" -Raw
L "PATH2_VISIBLE_TEXT="
L ((([regex]::Matches($a2,'text="([^"]*)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne "" }) -join " | "))
L ("PATH2_HAS_登录没通过=" + ($a2 -match '登录没通过'))
L ("PATH2_HAS_用户名或口令不对=" + ($a2 -match '用户名或口令不对'))
L ("PATH2_HAS_重试=" + ($a2 -match '重试'))
L ("PATH2_HAS_继续加载=" + ($a2 -match '继续加载'))
L "PATH2_LOGCAT:"
L (& $adb -s $s logcat -d -s "OcWebHost:*" "OcWebHost/Probe:*" 2>&1 | Select-String -Pattern "Probe|F5 probe|credentials stored|unreachable" | ForEach-Object { $_.ToString() })

# ================= PATH 3: UNREACHABLE (wrong reverse port) =================
L ""
L "############ PATH 3: UNREACHABLE (reverse -> dead port 4099) ############"
# reverse --remove is blocked by policy; use the "point at a wrong port" method
& $adb -s $s reverse tcp:4095 tcp:4099 | Out-Null
L (& $adb -s $s reverse --list 2>&1)
& $adb -s $s shell am force-stop $pkg
& $adb -s $s logcat -c
& $adb -s $s shell am start -n "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity" | Out-Null
Start-Sleep -Seconds 14
& $adb -s $s exec-out screencap -p > "$W\_f6_path3_unreachable.png"
& $adb -s $s shell uiautomator dump /sdcard/f6_p3.xml 2>&1 | Out-Null
& $adb -s $s pull /sdcard/f6_p3.xml "$W\_f6_p3.xml" 2>&1 | Out-Null
$a3 = Get-Content "$W\_f6_p3.xml" -Raw
L ("PATH3_DUMP_BYTES=" + $a3.Length)
L "PATH3_LOGCAT:"
L (& $adb -s $s logcat -d -s "OcWebHost:*" "OcWebHost/Probe:*" 2>&1 | Select-String -Pattern "Probe|F5 probe|unreachable|friendly" | ForEach-Object { $_.ToString() })
# restore correct reverse for any later step
& $adb -s $s reverse tcp:4095 tcp:4095 | Out-Null
L "REVERSE_RESTORED:"
L (& $adb -s $s reverse --list 2>&1)
L "PATH23_DONE"
