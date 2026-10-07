param([string]$Dev="emulator-6790",[string]$Act="com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity",[int]$Settle=4)
$ErrorActionPreference='Continue'
$adb = "C:\Users\xiufeigo\AppData\Local\Android\Sdk\platform-tools\adb.exe"

function Eval([string]$Js,[int]$Wait=4){
  $b64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Js))
  & $adb -s $Dev shell am start -n $Act --es oc_debug_eval "b64:$b64" | Out-Null
  Start-Sleep -Seconds $Wait
  $l = & $adb -s $Dev logcat -d -t 4000 2>$null | Select-String "OcWebHost/Eval" | Select-Object -Last 1
  if($l){ $l.ToString() } else { "NO_EVAL_RESULT" }
}

function UiText(){
  & $adb -s $Dev shell rm -f /sdcard/d.xml 2>&1 | Out-Null
  & $adb -s $Dev shell uiautomator dump /sdcard/d.xml 2>&1 | Out-Null
  & $adb -s $Dev pull /sdcard/d.xml "$env:TEMP\ux18p1.xml" 2>&1 | Out-Null
  if(Test-Path "$env:TEMP\ux18p1.xml"){
    [xml]$x = Get-Content "$env:TEMP\ux18p1.xml" -Raw
    ($x.SelectNodes('//node[@text!=""]') | ForEach-Object { "$($_.text)@$($_.bounds)" }) -join ' | '
  } else { "DUMP_FAILED" }
}

function Shot([string]$name){
  & $adb -s $Dev shell screencap -p /sdcard/s.png 2>&1 | Out-Null
  & $adb -s $Dev pull /sdcard/s.png "D:\XiaoZhuTouTou\opencode-android-shell\research\v18_verify\$name.png" 2>&1 | Out-Null
  "shot: $name"
}

function EvLog([int]$sec=20){
  & $adb -s $Dev logcat -c 2>&1 | Out-Null
  Start-Sleep -Seconds $sec
  & $adb -s $Dev logcat -d -t 3000 2>$null | Select-String -Pattern "api/event|oc-pow|oc-locui|4096|4173|4095" | Select-Object -Last 40
}