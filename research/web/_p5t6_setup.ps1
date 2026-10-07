$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S   = 'emulator-5680'
$PKG = 'com.xiufeigo.opencodeshell.web.debug'
$ACT = "$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$APK = 'D:\XiaoZhuTouTou\opencode-android-shell\research\deliver\opencode-shell-web.apk'
$OUT = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\out_p5t6'
New-Item -ItemType Directory -Force -Path $OUT | Out-Null

function Sh($c){ $o = & $adb -s $S shell $c 2>&1; return (($o | Out-String) -replace "`r`n",' | ') -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss.fff'), $m; Add-Content -Path "$OUT\setup.log" -Value $s -Encoding utf8; Write-Host $s }

L "=== SETUP START ==="
L ("apk md5 = " + (Get-FileHash $APK -Algorithm MD5).Hash)

# reverse
& $adb -s $S reverse tcp:4095 tcp:4095 2>&1 | Out-Null
L ("reverse list: " + ((& $adb -s $S reverse --list) | Out-String).Trim())

# install -r
L ("install: " + (Sh "pm install -r '$APK'"))

# start logcat EARLY (covers whole window, superset of requirement)
if (Test-Path "$OUT\logcat_full.txt") { Remove-Item "$OUT\logcat_full.txt" -Force }
$lp = Start-Process -FilePath $adb -ArgumentList @('-s',$S,'logcat','-v','threadtime') `
      -RedirectStandardOutput "$OUT\logcat_full.txt" -RedirectStandardError "$OUT\logcat.err" -PassThru -NoNewWindow
$lp.Id | Out-File "$OUT\logcat.pid" -Encoding ascii
L ("logcat started pid=" + $lp.Id)

# clear + launch
L ("pm clear: " + (Sh "pm clear $PKG"))
L ("am start: " + (Sh "am start -n $ACT"))
Start-Sleep -Seconds 6
$pid1 = (Sh "pidof $PKG")
L ("app pid: " + $pid1)
Start-Sleep -Seconds 6

# devtools forward
$unix = (Sh "cat /proc/net/unix | grep -o 'webview_devtools_remote_[0-9]*' | head -1")
L ("devtools: " + $unix)
& $adb -s $S forward tcp:9222 "localabstract:$unix" 2>&1 | Out-Null
L ("setup done pid=$pid1")
