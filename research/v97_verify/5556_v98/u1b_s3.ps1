. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

# Create session (coords parsed from live dump u1b_04)
$c = Ctr "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_04_afterproj.xml" 'Create session'
if ($c) { Ado "input tap $($c[0]) $($c[1])" | Out-Null } else { Write-Output "CREATEFAIL" }
Start-Sleep -Seconds 4
$f = DumpIt "05_created"
Write-Output "FOCUS=$(FocusNow) IME=$(ImeState) IMETOP=$(ImeTopPx)"
Write-Output "--- session view ---"
foreach ($n in (GetNodes $f)) { if ($n.Txt -and $n.W -gt 0 -and $n.Txt.Length -lt 50) { Write-Output ("  [{0},{1}][{2},{3}] '{4}'" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.Txt) } }

# focus composer to raise IME, type the long turn (>=30 numbered points request)
$e = Ctr $f 'Ask anything'
if ($e) { Ado "input tap $($e[0]) $($e[1])" | Out-Null; Start-Sleep -Milliseconds 900 }
Write-Output "IME_AFTER_FOCUS=$(ImeState)"
TypeTxt "Write a long detailed explanation of how Android window insets work, covering at least 30 numbered points."
Start-Sleep -Milliseconds 600
$f2 = DumpIt "06_typed"
$hits = @(GetNodes $f2 | Where-Object { $_.Txt -and $_.Txt.Contains('numbered points') })
Write-Output "TYPED_HITS=$($hits.Count)"
foreach ($n in $hits) { Write-Output ("  [{0},{1}][{2},{3}] h={4} '{5}'" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.H,$n.Txt) }
Write-Output "IME=$(ImeState) IMETOP=$(ImeTopPx)"

# send
$s = Ctr $f2 'Send'
if ($s) { Write-Output "TAP_SEND at $($s[0]),$($s[1])"; Ado "input tap $($s[0]) $($s[1])" | Out-Null } else { Write-Output "SENDFAIL" }
Start-Sleep -Seconds 3
$f3 = DumpIt "07_streaming"
$tl = GetNodes $f3 | Where-Object { $_.Scroll -eq 'true' -and $_.W -gt 800 } | Select-Object -First 1
Write-Output "STREAMING TL=$($tl.bounds) h=$($tl.H) IME=$(ImeState) IMETOP=$(ImeTopPx)"
ShotIt "07_streaming" | Out-Null
