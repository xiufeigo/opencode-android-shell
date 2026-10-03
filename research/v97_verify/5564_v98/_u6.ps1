$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5564_v98"
. "$d\_lib.ps1"
$log="$d\u6_log.txt"
"=== U6 run $(Get-Date -Format 'HH:mm:ss.fff') ===" | Out-File $log -Encoding UTF8

# 1) type + send a fresh turn
Adb shell input tap 600 1143
Start-Sleep -Seconds 2
foreach($w in @('E2ECHK5564n','u6','second')){ Adb shell input text $w; Adb shell input keyevent 62 }
Adb shell input text 'turn'
Adb shell input keyevent 62
Adb shell input text 'idle'
Adb shell input keyevent 62
Adb shell input text 'probe'
Start-Sleep -Seconds 1
Adb logcat -c
"typed; logcat cleared" | Out-File $log -Append -Encoding UTF8
Adb shell input keyevent 66      # send

# 2) tight watch for 'trigger idle' then IMMEDIATELY HOME
$sw=[System.Diagnostics.Stopwatch]::StartNew()
$hit=$false
$idleAt=0.0
while($sw.Elapsed.TotalSeconds -lt 90){
  $l = (Adb logcat -d -s OcNotify:D *:S | Out-String)
  if($l -match 'trigger idle'){ $hit=$true; $idleAt=$sw.Elapsed.TotalSeconds; break }
  Start-Sleep -Milliseconds 150
}
"trigger idle hit=$hit at t=${idleAt}s" | Out-File $log -Append -Encoding UTF8
if(-not $hit){ "NO IDLE within 90s" | Out-File $log -Append -Encoding UTF8 }

# 3) HOME immediately
Adb shell input keyevent 3
$homeAt=$sw.Elapsed.TotalSeconds
Start-Sleep -Seconds 2
$f=Focus
"HOME issued at t=$homeAt s; focus=$f" | Out-File $log -Append -Encoding UTF8
if($f -match 'opencodeshell'){
  "keyevent 3 ineffective -> am start HOME" | Out-File $log -Append -Encoding UTF8
  Adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME | Out-Null
  Start-Sleep -Seconds 2
  $f=Focus
  "focus after am start=$f" | Out-File $log -Append -Encoding UTF8
}
$bgAt=$sw.Elapsed.TotalSeconds   # = moment app is in background
"BG_MARKER t=$bgAt s" | Out-File $log -Append -Encoding UTF8

# 4) poll up to 6 min for NotificationRecord
$found=$false
$notifAt=0.0
$polls=0
while($sw.Elapsed.TotalSeconds -lt ($bgAt+360)){
  $n=NotifCount
  $polls++
  if($n -gt 0){
    $found=$true; $notifAt=$sw.Elapsed.TotalSeconds; break
  }
  Start-Sleep -Seconds 5
}
"NOTIF found=$found  bg->notif=$([math]::Round($notifAt-$bgAt,1))s  polls=$polls  total=$([math]::Round($sw.Elapsed.TotalSeconds,1))s" | Out-File $log -Append -Encoding UTF8
"--- OcNotify full ---" | Out-File $log -Append -Encoding UTF8
(Adb logcat -d -s OcNotify:D *:S | Out-String) | Out-File $log -Append -Encoding UTF8
"--- NotificationRecord ---" | Out-File $log -Append -Encoding UTF8
(Adb shell dumpsys notification --noredact | Select-String -Pattern 'NotificationRecord.*opencodeshell' | ForEach-Object { $_.ToString() }) | Out-File $log -Append -Encoding UTF8
"--- focus now ---" | Out-File $log -Append -Encoding UTF8
(Focus) | Out-File $log -Append -Encoding UTF8
Shot "u6_banner" | Out-Null
"BANNER_SHOT=$script:D\u6_banner.png" | Out-File $log -Append -Encoding UTF8
"DONE" | Out-File $log -Append -Encoding UTF8
Get-Content $log
