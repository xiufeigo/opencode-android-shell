$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S = 'emulator-5680'
$PKG = 'com.xiufeigo.opencodeshell.web.debug'
$OUT = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\out_p5t6'
$SHOTS = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\p5t6_shots'
$CDP = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\ui1\ui1_cdp.ps1'
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss.fff'), $m; Add-Content -Path "$OUT\t6.log" -Value $s -Encoding utf8; Write-Host $s }
function Sh($c){ $o = & $adb -s $S shell $c 2>&1; return (($o | Out-String) -replace "`r`n",' | ') -replace '\s{2,}',' ' }
function Ev([string]$js){
  $o = & powershell -NoProfile -ExecutionPolicy Bypass -File $CDP -Js $js -Port 9222 -Serial $S 2>&1
  return ((($o | Out-String) -split "`r?`n" | Where-Object { $_ -and $_ -notmatch 'VoidTaskResult|socket=' }) -join '')
}
function Shot($n){ & $adb -s $S shell "screencap -p /sdcard/$n.png" 2>&1|Out-Null; & $adb -s $S pull "/sdcard/$n.png" "$SHOTS\$n.png" 2>&1|Out-Null; L "shot $n" }

L "=== TAP NOTIFICATION -> return to session ==="
L ("fg before: " + (Sh "dumpsys activity activities | grep -m1 topResumedActivity"))
L "tapping notification at (600,700)"
Sh "input tap 600 700" | Out-Null
Start-Sleep -Seconds 6
L ("fg after: " + (Sh "dumpsys activity activities | grep -m1 topResumedActivity"))
Shot '36-returned'
L ("app pid: " + (Sh "pidof $PKG"))
# re-forward devtools for new pid if changed
$p = (Sh "pidof $PKG")
$unix = (Sh "cat /proc/net/unix | grep -o 'webview_devtools_remote_[0-9]*' | head -1")
& $adb -s $S forward --remove-all 2>&1|Out-Null
& $adb -s $S forward tcp:9222 "localabstract:$unix" 2>&1|Out-Null
Start-Sleep -Seconds 2
L ("url: " + (Ev "location.href"))
L ("body: " + (Ev "document.body.innerText.slice(0,500)"))
L ("notif after tap: " + (Sh "dumpsys notification --noredact | grep -c 'pkg=$PKG'"))
L "=== D CELL DONE ==="
