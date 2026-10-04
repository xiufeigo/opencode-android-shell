# T3/T4/T1：文件选择(SAF) + 分享 chooser + 返回键
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$d   = "emulator-5636"
$pkg = "com.xiufeigo.opencodeshell.web.debug"
$act = "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity"

# 恢复基准窗口
& $adb -s $d shell wm size 1366x768
& $adb -s $d shell wm density 160
Start-Sleep -Seconds 2
& $adb -s $d shell am force-stop $pkg
Start-Sleep -Seconds 1
& $adb -s $d shell am start -n $act | Out-Null
Start-Sleep -Seconds 8
Write-Output "=== baseline focus ==="
& $adb -s $d shell dumpsys window | Select-String 'mCurrentFocus'

# ---------- T3: pickFile -> SAF ----------
Write-Output ""
Write-Output "########## T3 pickFile -> SAF ##########"
& $adb -s $d logcat -c
& $adb -s $d shell "am start -n $act --es oc_debug_eval 'window.__ocAndroid.pickFile(\"text/plain\",false)'" | Out-Null
Start-Sleep -Seconds 3
Write-Output "--- mCurrentFocus (期望 SAF/DocumentsUI) ---"
& $adb -s $d shell dumpsys window | Select-String 'mCurrentFocus'
& $adb -s $d shell screencap -p /sdcard/t3.png; & $adb -s $d pull /sdcard/t3.png .\BR1_T3_saf.png | Out-Null
Write-Output "--- 取消 SAF（back）---"
& $adb -s $d shell input keyevent 4
Start-Sleep -Seconds 3
Write-Output "--- 取消后 mCurrentFocus + 宿主日志 ---"
& $adb -s $d shell dumpsys window | Select-String 'mCurrentFocus'
& $adb -s $d logcat -d | Select-String -Pattern 'OcWebHost/FileChooser|OcWebHost/Eval|AndroidRuntime|FATAL' | Select-Object -Last 4
Write-Output "--- 取消后宿主进程还活着？ ---"
& $adb -s $d shell pidof $pkg

# ---------- T4: share -> chooser ----------
Write-Output ""
Write-Output "########## T4 share -> chooser ##########"
& $adb -s $d logcat -c
& $adb -s $d shell "am start -n $act --es oc_debug_eval 'window.__ocAndroid.share(\"BR1 分享正文\",\"BR1 title\")'" | Out-Null
Start-Sleep -Seconds 3
Write-Output "--- mCurrentFocus (期望 ResolverActivity/chooser) ---"
& $adb -s $d shell dumpsys window | Select-String 'mCurrentFocus'
& $adb -s $d shell screencap -p /sdcard/t4.png; & $adb -s $d pull /sdcard/t4.png .\BR1_T4_chooser.png | Out-Null
Write-Output "--- 关闭 chooser ---"
& $adb -s $d shell input keyevent 4
Start-Sleep -Seconds 2
& $adb -s $d shell dumpsys window | Select-String 'mCurrentFocus'
Write-Output "--- 宿主还活着？ ---"
& $adb -s $d shell pidof $pkg
