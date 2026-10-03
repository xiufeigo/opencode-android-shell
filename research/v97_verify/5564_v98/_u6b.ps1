$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5564_v98"
. "$d\_lib.ps1"
$log="$d\u6_log.txt"
function L($m){ $ts=(Get-Date).ToString('HH:mm:ss.fff'); ($ts + '  ' + $m) | Out-File $log -Append -Encoding UTF8 }
"" | Out-File $log -Encoding UTF8
L "=== U6 lean run START ==="

Adb shell input tap 600 1143
Start-Sleep -Seconds 2
foreach($w in @('E2ECHK5564n','u6','third')){ Adb shell input text $w; Adb shell input keyevent 62 }
Adb shell input text 'turn'
Adb shell input keyevent 62
Adb shell input text 'idle'
Adb shell input keyevent 62
Adb shell input text 'probe'
Start-Sleep -Seconds 1
Adb logcat -c
L "typed+sent, logcat cleared"
Adb shell input keyevent 66

# background quickly, before/around the idle->recheck window
Start-Sleep -Seconds 5
Adb shell input keyevent 3
Start-Sleep -Seconds 2
$f=Focus
L "HOME issued; focus=$f"
if($f -match 'opencodeshell'){
  Adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME | Out-Null
  Start-Sleep -Seconds 2
  L "keyevent3 ineffective -> am start; focus=$(Focus)"
}
$bg=(Get-Date)
L "BG_MARKER"

# poll up to 6 min, log every poll
$found=$false; $secs=0.0
for($i=1;$i -le 72;$i++){
  $n=NotifCount
  $el=((Get-Date)-$bg).TotalSeconds
  L "poll#$i t=$([math]::Round($el,1))s notifCount=$n"
  if($n -gt 0){ $found=$true; $secs=$el; break }
  if($el -gt 360){ break }
  Start-Sleep -Seconds 5
}
L "RESULT found=$found bg_to_notif=$([math]::Round($secs,1))s"
L "--- OcNotify ---"
(Adb logcat -d -s OcNotify:D "*:S" | Out-String) | Out-File $log -Append -Encoding UTF8
L "--- NotificationRecord ---"
(Adb shell dumpsys notification --noredact | Select-String -Pattern 'NotificationRecord.*opencodeshell' | ForEach-Object { $_.ToString() }) | Out-File $log -Append -Encoding UTF8
L "--- focus ---"
(Focus) | Out-File $log -Append -Encoding UTF8
Shot "u6_banner" | Out-Null
L "BANNER=$script:D\u6_banner.png"
L "DONE"
