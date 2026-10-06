$ErrorActionPreference = 'Stop'
$adb  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$ser  = "emulator-6790"
$pkg  = "com.xiufeigo.opencodeshell.web.debug"
$act  = "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity"
$shots= "D:\XiaoZhuTouTou\opencode-android-shell\research\web\shots_p13"
$log  = "D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\p13_logcat_fix.txt"
$apk  = "D:\XiaoZhuTouTou\opencode-android-shell\research\deliver\opencode-shell-web.apk"

function T($x,$y,$ms=2500){ & $adb -s $ser shell input tap $x $y; Start-Sleep -Milliseconds $ms }
function EvalJs($js){
  $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($js))
  $cmd = "am start -n $act --es oc_debug_eval '(function(){var s=atob(`"$b64`");return (0,eval)(s);})()'"
  & $adb -s $ser shell $cmd | Out-Null
}

"=== install + clear ==="
& $adb -s $ser install -r $apk
& $adb -s $ser shell pm clear $pkg | Out-Null
& $adb -s $ser shell am start -n $act | Out-Null
Start-Sleep -Seconds 13

"=== register 4095 ==="
T 65 120 3000          # 顶栏左图标 -> 抽屉
T 180 2329 3000         # Settings
T 1048 215 2000         # Preferences 下拉
T 769 988 3000          # Server
T 900 482 2000          # Add server
& $adb -s $ser shell input text "http://127.0.0.1:4095"; Start-Sleep -Milliseconds 800
T 540 1398 1200         # 密码框
& $adb -s $ser shell input text "opencode"; Start-Sleep -Milliseconds 800
& $adb -s $ser shell input keyevent 4; Start-Sleep -Milliseconds 1200
T 907 1685 4000         # Add server 提交
T 193 215 4000          # Back to app
T 994 263 3000          # All projects 下拉
T 308 467 6000          # 选 4095
T 248 599 7000          # 进会话

"=== warmup -> permission ==="
T 540 2135 2000
& $adb -s $ser shell input text "warmup"; Start-Sleep -Milliseconds 800
& $adb -s $ser shell input keyevent 66; Start-Sleep -Seconds 9
T 540 1306 12000        # Allow

"=== force-stop -> cold start ==="
& $adb -s $ser shell am force-stop $pkg; Start-Sleep -Seconds 2
& $adb -s $ser shell am start -n $act | Out-Null
Start-Sleep -Seconds 13
T 248 599 8000          # 冷启重进：再进会话

"=== arm gate ring buffer (wrapper) ==="
EvalJs "(function(){window.__gateLog=[];var d=Object.getOwnPropertyDescriptor(document,'visibilityState');var g=d&&d.get;if(g){Object.defineProperty(document,'visibilityState',{configurable:true,get:function(){var v=g.call(document);window.__gateLog.push(['vs',v,Date.now()]);return v;}});}var hf=document.hasFocus.bind(document);document.hasFocus=function(){var f=hf();window.__gateLog.push(['hf',f,Date.now()]);return f;};return JSON.stringify({setBg:typeof window.__ocSetBackground,ocBg:typeof window.__ocBg,vsWrapped:!!g,perm:Notification.permission,path:location.pathname});})()"
Start-Sleep -Seconds 6
& $adb -s $ser logcat -d -s OcWebHost/Eval | Select-Object -Last 1

"=== ping -> HOME (30s, full logcat) ==="
& $adb -s $ser logcat -c
$job = Start-Job -ScriptBlock { param($a,$s,$l) & $a -s $s logcat -v threadtime | Out-File -FilePath $l -Encoding utf8 } -ArgumentList $adb,$ser,$log
Start-Sleep -Seconds 3
T 540 2135 2000
& $adb -s $ser shell input text "ping"; Start-Sleep -Milliseconds 800
& $adb -s $ser shell input keyevent 66; Start-Sleep -Milliseconds 1500
& $adb -s $ser shell input keyevent 3
"HOME at $(Get-Date -Format 'HH:mm:ss.fff')"
Start-Sleep -Seconds 30
& $adb -s $ser shell am start -n $act | Out-Null
Start-Sleep -Seconds 6
& $adb -s $ser exec-out screencap -p > "$shots\p13_fix_after_bg.png"
Start-Sleep -Seconds 3
Stop-Job $job; Remove-Job $job -Force
"logcat stopped"