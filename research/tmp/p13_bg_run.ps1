$ErrorActionPreference = 'Stop'
$adb  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$ser  = "emulator-6790"
$log  = "D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\p13_logcat.txt"
$shots= "D:\XiaoZhuTouTou\opencode-android-shell\research\web\shots_p13"

# 清空缓冲区后开始全程落盘采集
& $adb -s $ser logcat -c
$job = Start-Job -ScriptBlock {
  param($a,$s,$l)
  & $a -s $s logcat -v threadtime | Out-File -FilePath $l -Encoding utf8
} -ArgumentList $adb,$ser,$log
Start-Sleep -Seconds 3
"T0 logcat started"

# 真实点击 composer -> 输入 ping -> Enter 发送
& $adb -s $ser shell input tap 540 2135
Start-Sleep -Seconds 2
& $adb -s $ser shell input text "ping"
Start-Sleep -Seconds 1
& $adb -s $ser shell input keyevent 66
Start-Sleep -Milliseconds 1500
"T1 ping sent"

# 立刻 HOME
& $adb -s $ser shell input keyevent 3
"T2 HOME at $(Get-Date -Format 'HH:mm:ss.fff')"

# 后台 30s
Start-Sleep -Seconds 30
"T3 background window over"

# 回前台
& $adb -s $ser shell am start -n com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity | Out-Null
"T4 foreground return at $(Get-Date -Format 'HH:mm:ss.fff')"
Start-Sleep -Seconds 6
& $adb -s $ser exec-out screencap -p > "$shots\p13_s15_after_bg.png"

Start-Sleep -Seconds 4
Stop-Job $job; Remove-Job $job -Force
"T5 logcat stopped"