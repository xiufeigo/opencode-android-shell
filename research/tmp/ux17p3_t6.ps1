$ErrorActionPreference='Continue'
$adb  = "C:\Users\xiufeigo\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dev  = "emulator-6790"
$pkg  = "com.xiufeigo.opencodeshell.web.debug"
$act  = "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity"
$log  = "D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux17p3_t6_logcat.txt"

& $adb -s $dev logcat -c
$job = Start-Job -ScriptBlock { param($a,$s,$l) & $a -s $s logcat -v threadtime | Out-File -FilePath $l -Encoding utf8 } -ArgumentList $adb,$dev,$log
Start-Sleep -Seconds 2

# focus composer
& $adb -s $dev shell input tap 324 2102
Start-Sleep -Milliseconds 1500

$msg = "Count%sfrom%s1%sto%s500%scomma%sseparated"
& $adb -s $dev shell input text $msg
Start-Sleep -Milliseconds 1200

# SEND (enter)
& $adb -s $dev shell input keyevent 66
$sent = Get-Date
Start-Sleep -Milliseconds 900
# HOME immediately (<1.5s after send)
& $adb -s $dev shell input keyevent 3
$home = Get-Date
"SENT   at $($sent.ToString('HH:mm:ss.fff'))"
"HOME   at $($home.ToString('HH:mm:ss.fff'))  delta_ms=$([int]($home - $sent).TotalMilliseconds)"

Start-Sleep -Seconds 25
& $adb -s $dev shell dumpsys notification --noredact > "D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux17p3_t6_notif.txt"
& $adb -s $dev exec-out screencap -p > "D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux17p3_t6_shade.png"
Start-Sleep -Seconds 2
Stop-Job $job; Remove-Job $job -Force
"captured"
