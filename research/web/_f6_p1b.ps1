$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s = "emulator-6170"
$pkg = "com.xiufeigo.opencodeshell.web.debug"
$W = "C:\sensorsdata\main\program\opencode-android-shell\research\web"
$log = "$W\_f6_path1.txt"
"" | Set-Content $log
function L($m){ $s2 = ($m | Out-String).TrimEnd(); Write-Output $s2; $s2 | Add-Content $log }

# focus password field, type the CORRECT password
& $adb -s $s shell input tap 540 1117 | Out-Null
Start-Sleep -Seconds 1
& $adb -s $s shell input text "opencode" | Out-Null
Start-Sleep -Seconds 1
& $adb -s $s shell uiautomator dump /sdcard/f6_typed.xml 2>&1 | Out-Null
& $adb -s $s pull /sdcard/f6_typed.xml "$W\_f6_typed.xml" 2>&1 | Out-Null
$t = Get-Content "$W\_f6_typed.xml" -Raw
$pwNode = [regex]::Match($t,'<node[^>]*password="true"[^>]*>').Value
L "TYPED_PASSWORD_NODE_LENGTH=$($pwNode.Length)  (8 chars => 8 dots, NOT mojibake)"

# tap 保存并继续
& $adb -s $s shell input tap 853 1388 | Out-Null
Start-Sleep -Seconds 10
& $adb -s $s exec-out screencap -p > "$W\_f6_path1_officialui.png"
& $adb -s $s shell uiautomator dump /sdcard/f6_after.xml 2>&1 | Out-Null
& $adb -s $s pull /sdcard/f6_after.xml "$W\_f6_after.xml" 2>&1 | Out-Null
$a = Get-Content "$W\_f6_after.xml" -Raw
L "AFTER_DUMP_BYTES=$($a.Length)"
L "--- visible text after save ---"
L ((([regex]::Matches($a,'text="([^"]*)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne "" }) -join " | "))
L "--- dialog present? (用户名或口令不对 / 登录没通过) ---"
L ("HAS_AUTH_DIALOG=" + ($a -match '登录没通过|用户名或口令不对'))
L ("HAS_RETRY_BTN=" + ($a -match '重试'))
L "--- logcat OcWebHost ---"
L (& $adb -s $s logcat -d -s "OcWebHost:*" "OcWebHost/Probe:*" 2>&1 | Out-String)
L "PATH1_DONE"
