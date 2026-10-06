$ErrorActionPreference = 'Continue'
$adb  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$ser  = "emulator-6790"
$pkg  = "com.xiufeigo.opencodeshell.web.debug"
$act  = "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity"
$shots= "D:\XiaoZhuTouTou\opencode-android-shell\research\web\shots_p13"
$log  = "D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\p13_logcat_fix.txt"

function T($x,$y,$ms=2500){ & $adb -s $ser shell input tap $x $y; Start-Sleep -Milliseconds $ms }
function EvalJs($js){
  $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($js))
  $cmd = "am start -n $act --es oc_debug_eval '(function(){var s=atob(`"$b64`");return (0,eval)(s);})()'"
  & $adb -s $ser shell $cmd | Out-Null
}
function EvalRead($js){
  EvalJs $js
  Start-Sleep -Seconds 5
  (& $adb -s $ser logcat -d -s OcWebHost/Eval | Select-Object -Last 1) -replace '.*-> ',''
}

& $adb -s $ser reverse tcp:4095 tcp:4095 | Out-Null
& $adb -s $ser shell am start -n $act | Out-Null
Start-Sleep -Seconds 16
"[1] registered? -> " + (EvalRead "JSON.stringify({s:(localStorage.getItem('opencode.global.dat:server')||'').slice(0,160)})")

"[2] register 4095 via UI"
T 65 120 3000          # drawer
T 180 2329 3000         # Settings
T 1048 215 2500         # Preferences dropdown
T 769 988 3000          # Server
T 900 482 3500          # Add server
& $adb -s $ser shell input text "http://127.0.0.1:4095"; Start-Sleep -Seconds 2
"[3] url field -> " + (EvalRead "JSON.stringify({u:Array.from(document.querySelectorAll('input')).map(function(i){return i.type+'='+i.value}).join('|')})")
T 540 1398 1500         # password
& $adb -s $ser shell input text "opencode"; Start-Sleep -Seconds 1
& $adb -s $ser shell input keyevent 4; Start-Sleep -Seconds 2
T 907 1685 5000         # submit Add server
"[4] after submit -> " + (EvalRead "JSON.stringify({s:(localStorage.getItem('opencode.global.dat:server')||'').slice(0,200)})")

T 193 215 5000          # Back to app
T 994 263 3500          # All projects
T 308 467 7000          # select 4095
T 248 599 8000          # enter session
"[5] in session -> " + (EvalRead "JSON.stringify({path:location.pathname})")

"[6] warmup -> permission"
T 540 2135 2500
& $adb -s $ser shell input text "warmup"; Start-Sleep -Seconds 1
& $adb -s $ser shell input keyevent 66; Start-Sleep -Seconds 9
& $adb -s emulator-6790 exec-out screencap -p > "$shots\p13_fix_permdlg.png"
T 540 1306 13000        # Allow
"[7] perm -> " + (EvalRead "JSON.stringify({perm:Notification.permission})")