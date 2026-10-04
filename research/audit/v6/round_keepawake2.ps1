$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s   = "emulator-6090"
$out = "C:\sensorsdata\main\program\opencode-android-shell\research\audit\v6"
$ev  = "$out\keepawake_sampling2.txt"

function Win-FlagLines {
  # 口径同 F4：dumpsys window 里含 KEEP_SCREEN_ON 的窗口 flags 行
  $r = & $adb -s $s shell "dumpsys window windows 2>/dev/null | grep 'KEEP_SCREEN_ON'" 2>$null
  if ($r) { $r } else { @("MISS") }
}
function Power-Raw {
  & $adb -s $s shell "dumpsys power | grep -E 'mWakefulness=|mScreenOn|mHoldingDisplaySuspendBlocker=|mWindowManagerDraw|state=' " 2>$null
}

"ROUND KEEP-AWAKE V6 (run#2)  send @ correct coords" | Out-File $ev -Encoding utf8
"=================================================================" | Out-File $ev -Append -Encoding utf8

& $adb -s $s logcat -c
$sendAt = Get-Date
"T0 host=$($sendAt.ToString('HH:mm:ss.fff')) dev=$( & $adb -s $s shell date )" | Out-File $ev -Append -Encoding utf8
("T0 WINDOW_FLAG : " + ((Win-FlagLines) -join ' ~ ')) | Out-File $ev -Append -Encoding utf8
("T0 POWER       : " + ((Power-Raw) -join ' ~ ')) | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8

& $adb -s $s shell input tap 973 2162     # Send (enabled=true @ [939,2128][1008,2197])
"--- SENT at host=$((Get-Date).ToString('HH:mm:ss.fff')) dev=$( & $adb -s $s shell date ) ---" | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8

$end = (Get-Date).AddSeconds(240)
while ((Get-Date) -lt $end) {
  $el = [int]((Get-Date) - $sendAt).TotalSeconds
  $devT = (& $adb -s $s shell date) -replace '\s+',' '
  $f = ((Win-FlagLines) -join ' ~ ')
  if ($f.Length -gt 200) { $f = $f.Substring(0,200) + '...' }
  "T+{0,3}s dev={1} WINDOW_FLAG={2}" -f $el, $devT, $f | Out-File $ev -Append -Encoding utf8
  "         POWER: " + ((Power-Raw) -join ' ~ ') | Out-File $ev -Append -Encoding utf8
  Start-Sleep -Seconds 5
}
"=== SAMPLE LOOP DONE host=$((Get-Date).ToString('HH:mm:ss.fff')) ===" | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8

$log = & $adb -s $s logcat -d -v time 2>&1
"=== applyKeepScreenOn (D1) 原文 ===" | Out-File $ev -Append -Encoding utf8
($log | Select-String -Pattern "applyKeepScreenOn" | ForEach-Object { $_.Line }) | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8
"=== POST 请求 (回合开始证据) ===" | Out-File $ev -Append -Encoding utf8
($log | Select-String -Pattern "POST /" | ForEach-Object { $_.Line }) | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8
"=== round 相关 (RoundActivityTracker / proxy) ===" | Out-File $ev -Append -Encoding utf8
($log | Select-String -Pattern "round" | Where-Object { $_ -notmatch "GET http" } | ForEach-Object { $_.Line }) | Out-File $ev -Append -Encoding utf8
"DONE" | Out-File "$out\keepawake2_done.flag" -Encoding utf8
