$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s   = "emulator-6090"
$out = "C:\sensorsdata\main\program\opencode-android-shell\research\audit\v6"
$ev  = "$out\keepawake_sampling3.txt"

function AppWindowBlock {
  # 取本应用窗口在 dumpsys window 里的整块（含 flags 行），用于人工核对 KEEP_SCREEN_ON 是否真的出现
  & $adb -s $s shell "dumpsys window windows | grep -A6 'opencodeshell.*WebHostActivity'" 2>$null
}
"ROUND KEEP-AWAKE V6 (run#3) -- dumpsys window 原文对照" | Out-File $ev -Encoding utf8
"=================================================================" | Out-File $ev -Append -Encoding utf8

& $adb -s $s logcat -c
"--- 回合前 dumpsys window 原文 ---" | Out-File $ev -Append -Encoding utf8
(AppWindowBlock) | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8

& $adb -s $s shell input tap 540 2000    # 输入框
Start-Sleep -Seconds 2
& $adb -s $s shell input text "V6_KEEP_DUMPSYS_ROUND2"
Start-Sleep -Seconds 2
$sendAt = Get-Date
& $adb -s $s shell input tap 973 2162    # Send
"--- SENT host=$($sendAt.ToString('HH:mm:ss.fff')) dev=$( & $adb -s $s shell date ) ---" | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8

# 回合进行中密集抓 dumpsys window 原文
foreach ($t in 4,9,14,20,28,36) {
  while (((Get-Date) - $sendAt).TotalSeconds -lt $t) { Start-Sleep -Milliseconds 400 }
  $el=[int]((Get-Date)-$sendAt).TotalSeconds
  "===== T+${el}s  dev=$( & $adb -s $s shell date )  dumpsys window (app block) =====" | Out-File $ev -Append -Encoding utf8
  (AppWindowBlock) | Out-File $ev -Append -Encoding utf8
  "" | Out-File $ev -Append -Encoding utf8
}

# 等回合结束 + 90s 兜底点 + 再多等
"===== 等待回合结束与 90s 兜底复查 =====" | Out-File $ev -Append -Encoding utf8
$end=(Get-Date).AddSeconds(210)
while((Get-Date) -lt $end){
  $el=[int]((Get-Date)-$sendAt).TotalSeconds
  if ($el % 15 -lt 6) {
    "T+{0,3}s dev={1} KEEP_SCREEN_ON_in_dump={2}" -f $el, ((& $adb -s $s shell date) -replace '\s+',' '), ((& $adb -s $s shell "dumpsys window windows | grep -c KEEP_SCREEN_ON") -join '') | Out-File $ev -Append -Encoding utf8
  }
  Start-Sleep -Seconds 5
}

$log = & $adb -s $s logcat -d -v time 2>&1
"=== applyKeepScreenOn (D1) 原文 run#3 ===" | Out-File $ev -Append -Encoding utf8
($log | Select-String -Pattern "applyKeepScreenOn|roundActive=" | ForEach-Object { $_.Line }) | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8
"=== 结束时刻 dumpsys window 原文 ===" | Out-File $ev -Append -Encoding utf8
(AppWindowBlock) | Out-File $ev -Append -Encoding utf8
"DONE" | Out-File "$out\keepawake3_done.flag" -Encoding utf8
