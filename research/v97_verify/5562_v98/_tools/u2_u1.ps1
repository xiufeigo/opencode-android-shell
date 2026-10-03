. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\_tools\u2_lib.ps1"

# U1 — keyboard + queue bar coexisting: the most extreme timeline height.
# Method: send turn A (long), while streaming send turn B -> queue bar appears,
# and with the IME up. Then read the timeline container bounds.
# U2 — resize source: dumpsys window insets, so the y=0..350 resize origin is evidenced at runtime.
# U15 — Home search IME layout.

"=== U2: runtime insets read (no code change) ==="
& $adb -s $S shell "dumpsys window windows | grep -iE 'InsetsSourceProvider|ime|mFrame|requestedVisibleTypes' " | Select-Object -First 25
& $adb -s $S shell "dumpsys window displays | grep -iE 'mDisplayFrames|ime|InsetsState' " | Select-Object -First 20

"=== U1: composer focus -> IME up (baseline) ==="
Dump "u1_00_base" | Out-Null
Find "u1_00_base" "Ask anything|Stop|Count|Working"
$cmp = Find "u1_00_base" "Ask anything" | Select-Object -First 1
if($cmp -match 'c=(\d+),(\d+)'){ $cx=$Matches[1]; $cy=$Matches[2]; & $adb -s $S shell input tap $cx $cy; "tapped composer $cx,$cy" }
Start-Sleep -Seconds 2
Probe "ime-up"
Dump "u1_01_imeup" | Out-Null
Shot "u1_01_imeup"
"--- timeline pane bounds with IME up, no queue bar ---"
Find "u1_01_imeup" "Build anything|Working|Ask anything|Stop"

"=== U1: send turn A ==="
& powershell -File "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\_tools\u2_input.ps1" -Text "Count slowly from 1 to 90, one number per line."
Start-Sleep -Seconds 1
$send = Find "u1_01_imeup" "Send" | Select-Object -First 1
if($send -match 'c=(\d+),(\d+)'){ & $adb -s $S shell input tap $Matches[1] $Matches[2]; "sent A" }
Start-Sleep -Seconds 4
Dump "u1_02_streaming" | Out-Null
"--- streaming, no queue bar ---"
Find "u1_02_streaming" "Stop|Working|Count|queued|Queue|Ask anything"

"=== U1: send turn B while streaming -> queue bar + IME ==="
$cmp2 = Find "u1_02_streaming" "Ask anything" | Select-Object -First 1
if($cmp2 -match 'c=(\d+),(\d+)'){ & $adb -s $S shell input tap $Matches[1] $Matches[2] }
Start-Sleep -Seconds 1
& powershell -File "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\_tools\u2_input.ps1" -Text "Second turn queued."
Start-Sleep -Seconds 1
$send2 = Find "u1_02_streaming" "Send" | Select-Object -First 1
if($send2 -match 'c=(\d+),(\d+)'){ & $adb -s $S shell input tap $Matches[1] $Matches[2]; "sent B" }
Start-Sleep -Seconds 3
Probe "queue+ime"
Dump "u1_03_queue" | Out-Null
Shot "u1_03_queue"
"--- KEY READ: queue bar + IME coexisting ---"
Find "u1_03_queue" "Stop|Working|queued|Queued|Queue|Ask anything|Count"

"--- all container bounds in that frame (to find the timeline pane) ---"
$x = Get-Content "$d\u1_03_queue.xml" -Raw
[regex]::Matches($x,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') | ForEach-Object {
  $a=[int]$_.Groups[1].Value;$b=[int]$_.Groups[2].Value;$c=[int]$_.Groups[3].Value;$e=[int]$_.Groups[4].Value
  if(($c-$a) -gt 600){ "WIDE BOX [{0},{1}][{2},{3}]  h={4}" -f $a,$b,$c,$e,($e-$b) }
} | Select-Object -Unique
"=== U1 DONE ==="
