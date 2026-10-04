# v157 Task C: restore display, restore the setting I changed, clean up
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\v157_lib.ps1"
$log = "$W\_v157_C.log"
if (Test-Path $log) { Remove-Item $log }
function W_ { param($m) $s = ($m | Out-String).TrimEnd(); Write-Output $s; Add-Content -Path $log -Value $s }

W_ "### C1 restore display to 1366x768 / density 160"
adbx shell wm density 160 | Out-Null
adbx shell wm size 1366x768 | Out-Null
Start-Sleep -Seconds 3
W_ ("  wm size    -> " + ((adbx shell wm size 2>&1 | Out-String).Trim() -replace "`r?`n"," | "))
W_ ("  wm density -> " + ((adbx shell wm density 2>&1 | Out-String).Trim() -replace "`r?`n"," | "))

W_ ""
W_ "### C2 restore show_ime_with_hard_keyboard to the value read BEFORE any change (1)"
W_ ("  before this round it was already: " + (adbx shell settings get secure show_ime_with_hard_keyboard 2>&1 | Out-String).Trim())
adbx shell settings put secure show_ime_with_hard_keyboard 1 | Out-Null
W_ ("  after restore: " + (adbx shell settings get secure show_ime_with_hard_keyboard 2>&1 | Out-String).Trim())

W_ ""
W_ "### C3 clear the self-authored composer text (v157monotonecheck), no message sent"
$null = AssertIme 'C3' 2>$null
SetDisp '1366x768' 4
$x = Dump 'C3_pre'; $ns = Nodes $x
$et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
if ($et) {
  W_ "  composer before: '$($et.text)'"
  adbx shell input tap $et.cx $et.cy | Out-Null
  Start-Sleep -Seconds 2
  # select-all + delete
  adbx shell input keyevent KEYCODE_MOVE_END | Out-Null
  1..40 | ForEach-Object { adbx shell input keyevent KEYCODE_DEL | Out-Null }
  Start-Sleep -Seconds 2
  $x2 = Dump 'C3_post'; $ns2 = Nodes $x2
  $et2 = $ns2 | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  W_ "  composer after:  '$(if($et2){$et2.text}else{'<no EditText>'})'"
} else { W_ "  no EditText found" }

W_ ""
W_ "### C4 /sdcard residue: only the harness scratch files may exist, and they are removed"
$before = adbx shell ls /sdcard 2>&1 | Out-String
W_ ("  /sdcard before: " + (($before -split "`n" | Where-Object { $_ -match '__e2e|__v157|\.xml|\.png' }) -join ', '))
adbx shell rm -f /sdcard/__e2e.xml | Out-Null
adbx shell rm -f /sdcard/__v157.png | Out-Null
Start-Sleep -Seconds 1
$after = adbx shell ls /sdcard 2>&1 | Out-String
$resid = ($after -split "`n" | Where-Object { $_ -match '__e2e|__v157' })
if ($resid) { W_ ("  /sdcard residue: " + ($resid -join ', ')) } else { W_ "  /sdcard clean: no __e2e/__v157 residue" }
W_ "### C done"
