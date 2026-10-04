$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s   = "emulator-6090"
$out = "C:\sensorsdata\main\program\opencode-android-shell\research\audit\v6"
$ev  = "$out\keepawake_sampling.txt"

function Win-Keep {
  $r = & $adb -s $s shell "dumpsys window windows 2>/dev/null | grep -E 'Window\{.*opencodeshell'" 2>$null
  $r
}
function Win-KeepFlag {
  # HIT/MISS 口径：dumpsys window 里带 KEEP_SCREEN_ON 的窗口行
  $r = & $adb -s $s shell "dumpsys window windows 2>/dev/null | grep 'fl=' | grep 'KEEP_SCREEN_ON'" 2>$null
  if ($r) { $r } else { "MISS" }
}
function Power-Raw {
  & $adb -s $s shell "dumpsys power | grep -E 'mWakefulness=|mScreenBrightnessOverrideFromWindowManager=|mHoldingDisplaySuspendBlocker=|Display Power'" 2>$null
}

"ROUND KEEP-AWAKE V6 SAMPLE LOG  (device clock via adb shell date)" | Out-File $ev -Encoding utf8
"=================================================================" | Out-File $ev -Append -Encoding utf8

& $adb -s $s logcat -c
$sendAt = (Get-Date)
"T0 host=$($sendAt.ToString('HH:mm:ss.fff'))  -- before send" | Out-File $ev -Append -Encoding utf8
$devNow = & $adb -s $s shell date
"T0 dev=$devNow" | Out-File $ev -Append -Encoding utf8
("T0 window_flag : " + ((Win-KeepFlag) -join ' | ')) | Out-File $ev -Append -Encoding utf8
("T0 power        : " + ((Power-Raw) -join ' | ')) | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8

# ---- 发送一条真实 prompt，触发真实回合 ----
& $adb -s $s shell input tap 540 2150      # 点输入框
Start-Sleep -Seconds 2
& $adb -s $s shell input text "V6_KEEPAWAKE_ROUND"
Start-Sleep -Seconds 2
& $adb -s $s shell input tap 973 2288      # Send
"SENT at host=$((Get-Date).ToString('HH:mm:ss.fff'))  dev=$( & $adb -s $s shell date )" | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8

# ---- 采样 250s，每 5s 一次 ----
$end = (Get-Date).AddSeconds(250)
while ((Get-Date) -lt $end) {
  $el = [int]((Get-Date) - $sendAt).TotalSeconds
  $devT = (& $adb -s $s shell date) -replace '\s+',' '
  $flag = ((Win-KeepFlag) -join ' ')
  $pw   = ((Power-Raw) -join ' ')
  $short = if ($flag.Length -gt 150) { $flag.Substring(0,150) + '...' } else { $flag }
  "T+{0,3}s dev={1}  WINDOW_FLAG={2}" -f $el, $devT, $short | Out-File $ev -Append -Encoding utf8
  "        POWER: $pw" | Out-File $ev -Append -Encoding utf8
  Start-Sleep -Seconds 5
}

"=== DONE host=$((Get-Date).ToString('HH:mm:ss.fff')) ===" | Out-File $ev -Append -Encoding utf8

# ---- logcat 里的 D1 判定原文 ----
$log = & $adb -s $s logcat -d -v time 2>&1
"=== applyKeepScreenOn (D1) 原文 ===" | Out-File $ev -Append -Encoding utf8
($log | Select-String -Pattern "applyKeepScreenOn" | ForEach-Object { $_.Line }) | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8
"=== RoundActivity / round 相关 ===" | Out-File $ev -Append -Encoding utf8
($log | Select-String -Pattern "round|Round" | Where-Object { $_ -notmatch "GET http" } | ForEach-Object { $_.Line }) | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8
"=== 回合请求 (POST/session/message) ===" | Out-File $ev -Append -Encoding utf8
($log | Select-String -Pattern "POST /" | ForEach-Object { $_.Line }) | Out-File $ev -Append -Encoding utf8

"DONE" | Out-File "$out\keepawake_done.flag" -Encoding utf8
