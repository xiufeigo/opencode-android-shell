$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S   = 'emulator-5680'
$PKG = 'com.xiufeigo.opencodeshell.web.debug'
$ACT = "$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$APK = 'D:\XiaoZhuTouTou\opencode-android-shell\research\deliver\opencode-shell-web.apk'
$OUT = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\out_p5t6'

function Sh($c){ $o = & $adb -s $S shell $c 2>&1; return (($o | Out-String) -replace "`r`n",' | ') -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss.fff'), $m; Add-Content -Path "$OUT\setup.log" -Value $s -Encoding utf8; Write-Host $s }

L "=== RE-INSTALL v2 (top-level adb push) ==="
L ("force-stop: " + (Sh "am force-stop $PKG"))
$push = & $adb -s $S push $APK /data/local/tmp/oc.apk 2>&1
L ("push: " + (($push | Out-String) -replace "`r`n",' | '))
L ("remote size: " + (Sh "ls -l /data/local/tmp/oc.apk"))
L ("install: " + (Sh "pm install -r /data/local/tmp/oc.apk"))
L ("version: " + (Sh "dumpsys package $PKG | grep -E 'versionName|lastUpdateTime' | head -4"))
L ("pm clear: " + (Sh "pm clear $PKG"))
L ("am start: " + (Sh "am start -n $ACT"))
Start-Sleep -Seconds 8
$pid1 = (Sh "pidof $PKG")
L ("app pid: " + $pid1)
& $adb -s $S forward --remove-all 2>&1 | Out-Null
$unix = (Sh "cat /proc/net/unix | grep -o 'webview_devtools_remote_[0-9]*' | head -1")
& $adb -s $S forward tcp:9222 "localabstract:$unix" 2>&1 | Out-Null
L ("devtools: $unix ; forward ok")
L "=== SETUP READY ==="
