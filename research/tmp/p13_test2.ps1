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
  EvalJs $js; Start-Sleep -Seconds 5
  (& $adb -s $ser logcat -d -s OcWebHost/Eval | Select-Object -Last 1) -replace '.*-> ',''
}

"[A] force-stop -> cold start"
& $adb -s $ser shell am force-stop $pkg; Start-Sleep -Seconds 3
& $adb -s $ser shell am start -n $act | Out-Null
Start-Sleep -Seconds 16
T 248 599 9000         # 进会话
"[B] re-entered -> " + (EvalRead "JSON.stringify({path:location.pathname,perm:Notification.permission})")

"[C] arm gate ring buffer"
EvalRead "(function(){window.__gateLog=[];var d=Object.getOwnPropertyDescriptor(document,'visibilityState');var g=d&&d.get;if(g){Object.defineProperty(document,'visibilityState',{configurable:true,get:function(){var v=g.call(document);window.__gateLog.push(['vs',v,Date.now()]);return v;}});}var hf=document.hasFocus.bind(document);document.hasFocus=function(){var f=hf();window.__gateLog.push(['hf',f,Date.now()]);return f;};return JSON.stringify({setBg:typeof window.__ocSetBackground,vsWrapped:!!g,perm:Notification.permission});})()"

"[D] ping -> HOME, 30s full logcat"
& $adb -s $ser logcat -c
$job = Start-Job -ScriptBlock { param($a,$s,$l) & $a -s $s logcat -v threadtime | Out-File -FilePath $l -Encoding utf8 } -ArgumentList $adb,$ser,$log
Start-Sleep -Seconds 3
T 540 2135 2500
& $adb -s $ser shell input text "ping"; Start-Sleep -Seconds 1
& $adb -s $ser shell input keyevent 66; Start-Sleep -Milliseconds 1600
& $adb -s $ser shell input keyevent 3
"HOME issued"
Start-Sleep -Seconds 30
& $adb -s $ser shell am start -n $act | Out-Null
Start-Sleep -Seconds 7
& $adb -s $ser exec-out screencap -p > "$shots\p13_fix_after_bg.png"
Start-Sleep -Seconds 3
Stop-Job $job
"[E] logcat stopped"