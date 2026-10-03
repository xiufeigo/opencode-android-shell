. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

# 1) new long turn to make the turn RUNNING again
$e = Ctr (DumpIt "30_pre") 'Ask anything'
if ($e) { Ado "input tap $($e[0]) $($e[1])" | Out-Null; Start-Sleep -Milliseconds 400 }
TypeTxt "Write a long detailed explanation of how Android window insets work, covering at least 30 numbered points, with a worked example for each."
Start-Sleep -Milliseconds 300
$f = DumpIt "31_typed"
$s = Ctr $f 'Send'
if ($s) { Ado "input tap $($s[0]) $($s[1])" | Out-Null; Write-Output "SENT_LONGTURN" }
Start-Sleep -Seconds 2

# 2) draft content so the Queue chip appears
$e2 = Ctr (DumpIt "32_pre2") 'Ask anything'
if ($e2) { Ado "input tap $($e2[0]) $($e2[1])" | Out-Null; Start-Sleep -Milliseconds 300 }
TypeTxt "q1"
Start-Sleep -Milliseconds 400
$f2 = DumpIt "33_chip"
$chip = Ctr $f2 'Queue'
$send = Ctr $f2 'Send'
Write-Output "CHIP=$($chip -join ',') SEND=$($send -join ',') IME=$(ImeState)"
if (-not $chip) { Write-Output "NO_CHIP_ABORT"; exit }

# 3) toggle chip
Ado "input tap $($chip[0]) $($chip[1])" | Out-Null
Start-Sleep -Seconds 2
$f3 = DumpIt "34_toggled"
$send2 = Ctr $f3 'Send'
if (-not $send2) { $send2 = Ctr $f3 'Stop' }
Write-Output "SEND_AFTER=$($send2 -join ',') IME=$(ImeState)"
foreach ($n in (GetNodes $f3)) { if ($n.Y1 -ge 246 -and $n.Y1 -le 298 -and $n.X1 -gt 900 -and $n.W -gt 0) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] '$($n.Txt)'") } }
