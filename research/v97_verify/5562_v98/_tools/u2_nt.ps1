. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\_tools\u2_lib.ps1"

# U6/U7/U8 — notification verification per NOTIFICATION_DOMAIN.md method
# Precondition: turn must actually reach IDLE, and app must be truly backgrounded.
# Real backgrounding (keyevent 3 does NOT work on this freeform AVD):
#   am start -a android.intent.action.MAIN -c android.intent.category.HOME

"NOW: $(& $adb -s $S shell date)"
& $adb -s $S logcat -c
"--- session id / title from UI ---"
Dump "nt_00_state" | Out-Null
Find "nt_00_state" "E2ECHK5562u|Ask anything|Stop|Send"

"--- compose the turn ---"
$cmp = Find "nt_00_state" "Ask anything" | Select-Object -First 1
if($cmp -match 'c=(\d+),(\d+)'){ $cx=$Matches[1]; $cy=$Matches[2]; "tap composer $cx,$cy"; & $adb -s $S shell input tap $cx $cy }
Start-Sleep -Seconds 2
& powershell -File "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\_tools\u2_input.ps1" -Text "Count slowly from 1 to 60, one number per line."
Start-Sleep -Seconds 1
Probe "typed"

$send = Find "nt_00_state" "Send" | Select-Object -First 1
if($send -match 'c=(\d+),(\d+)'){ $sx=$Matches[1]; $sy=$Matches[2]; "tap Send $sx,$sy"; & $adb -s $S shell input tap $sx $sy }
Start-Sleep -Seconds 3
Dump "nt_01_sent" | Out-Null
"--- after send ---"
Find "nt_01_sent" "Stop|Working|Count|Counting|Error"

"--- go truly background ---"
& $adb -s $S shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
Start-Sleep -Seconds 3
& $adb -s $S shell "dumpsys activity activities | grep ResumedActivity:"
& $adb -s $S shell "dumpsys input_method | grep mInputShown"

"--- wait for IDLE (turn = 60 lines, ~60s+) ---"
$idleSeen = $false
for($i=1; $i -le 24; $i++){
  Start-Sleep -Seconds 5
  $l = & $adb -s $S logcat -d -v time -s OcNotify:D *:S 2>$null
  if($l -match "trigger idle"){ $idleSeen = $true; "IDLE observed at ~$($i*5)s"; break }
}
"idleSeen=$idleSeen"

"--- notification records ---"
& $adb -s $S shell cmd notification list com.xiufeigo.opencodeshell
& $adb -s $S shell "dumpsys notification --noredact | grep -E 'NotificationRecord.*xiufeigo|mId=.opencode.sessions'" | Select-Object -First 4
Dump "nt_02_bg" | Out-Null
Shot "nt_02_bg"

"--- OcNotify log (raise / withheld / posted) ---"
& $adb -s $S logcat -d -v time -s OcNotify:D *:S

"--- U6: heads-up — expand shade immediately ---"
& $adb -s $S shell cmd statusbar expand-notifications
Start-Sleep -Seconds 1
Shot "nt_03_shade"
Dump "nt_03_shade" | Out-Null
Find "nt_03_shade" "E2ECHK|Response|ready|Counted|Clear|opencode"
& $adb -s $S shell cmd notification list com.xiufeigo.opencodeshell
"=== NT PHASE-1 DONE ==="
