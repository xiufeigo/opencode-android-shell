$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5564_v98"
. "$d\_lib.ps1"
$log="$d\u8_log.txt"
function L($m){ $ts=(Get-Date).ToString('HH:mm:ss.fff'); ($ts + '  ' + $m) | Out-File $log -Append -Encoding UTF8 }
"" | Out-File $log -Encoding UTF8
L "=== U8 START notifCount=$(NotifCount) ==="

# generate a fresh notification
Adb shell input tap 600 1143
Start-Sleep -Seconds 2
foreach($w in @('E2ECHK5564n','u8','clear')){ Adb shell input text $w; Adb shell input keyevent 62 }
Adb shell input text 'test'
Adb shell input keyevent 62
Adb shell input text 'turn'
Adb shell input keyevent 62
Adb shell input text 'four'
Start-Sleep -Seconds 1
Adb logcat -c
Adb shell input keyevent 66
Start-Sleep -Seconds 5
Adb shell input keyevent 3
$bg=(Get-Date)
L "HOME issued; focus=$(Focus)"

$found=$false
for($i=1;$i -le 40;$i++){
  $n=NotifCount
  if($n -gt 0){ $found=$true; L "notif appeared t=$([math]::Round(((Get-Date)-$bg).TotalSeconds,1))s count=$n"; break }
  Start-Sleep -Seconds 5
}
if(-not $found){ L "NO NOTIF within 200s" }
L "pre-clear count=$(NotifCount)"

# open shade and use Clear all
Adb shell input swipe 1000 5 1000 1900 300
Start-Sleep -Seconds 3
$x=DumpUI "u8_shade"
L "shade focus=$(Focus)"
$ca = FindNode "u8_shade" "Clear all"
$ca | ForEach-Object { L "node T=[$($_.Text)] D=[$($_.Desc)] bounds=$($_.Bounds)" }
$target = $ca | Where-Object { $_.Desc -like '*Clear all notifications*' } | Select-Object -First 1
if(-not $target){ $target = $ca | Select-Object -First 1 }
if($target){
  $c = CenterOf $target.Bounds
  L "tapping Clear all at $($c.X),$($c.Y)"
  Adb shell input tap $c.X $c.Y
  Start-Sleep -Seconds 3
  L "post-clear count=$(NotifCount)"
} else {
  L "Clear all NOT FOUND"
}
L "--- dump after clear ---"
(Adb shell dumpsys notification --noredact | Select-String -Pattern 'NotificationRecord.*opencodeshell' | ForEach-Object { $_.ToString() }) | Out-File $log -Append -Encoding UTF8
Shot "u8_shade" | Out-Null
L "DONE"
Get-Content $log
