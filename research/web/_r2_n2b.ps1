$ErrorActionPreference='Continue'
$root="C:\sensorsdata\main\program\opencode-android-shell"
$sdk="$env:LOCALAPPDATA\Android\Sdk"
$adb="$sdk\platform-tools\adb.exe"
$S="emulator-5900"
$PKG='com.xiufeigo.opencodeshell.web.debug'
$ACT='com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity'
$out="$root\research\web\_r2_n2b.log"
$realSid="ses_efc722bf9ffeWaRdoFTwbmYj1Y"
function Say($m){ $s="[$(Get-Date -Format 'HH:mm:ss')] $m"; Write-Host $s; $s | Out-File $out -Append -Encoding UTF8 }
function A { & $adb -s $S @args }
function EvalJS($js,$wait=8){
  A shell "am start -n $ACT --es oc_debug_eval '$js'" | Out-Null
  Start-Sleep -Seconds $wait
  (A logcat -d 2>&1 | Select-String 'OcWebHost/Eval' | Select-Object -Last 1 | Out-String).Trim()
}
Say "=== N2 attempt 2: wait for facade, verify post, then REAL tap ==="
if((A get-state 2>$null|Out-String).Trim() -ne 'device'){ Say "device gone"; exit 1 }
A shell input keyevent KEYCODE_BACK | Out-Null; Start-Sleep -Seconds 2

# --- wait until the eval channel actually answers ---
$ready=$false
for($i=1;$i -le 10;$i++){
  $r = EvalJS 'JSON.stringify({t:document.title,rs:document.readyState,f:typeof window.__ocAndroid})' 7
  if($r -match 'readyState'){ Say "eval alive (try $i): $r"; $ready=$true; break }
  Say "eval not answering (try $i) - waiting"
  Start-Sleep -Seconds 6
}
if(-not $ready){ Say "!! eval channel never answered - CANNOT post notification"; exit 2 }

$base = EvalJS 'JSON.stringify({loc:location.pathname,href:location.href,title:document.title})' 7
Say "BASELINE: $base"

Say "--- STEP 1 [INJECTED - not a user action]: postMessage ---"
A logcat -c | Out-Null
$post = EvalJS 'JSON.stringify({ret:window.__ocAndroid.postMessage("N2-R2","R2 deliver re-verify","'+$realSid+'"),perm:window.__ocAndroid.hasNotificationPermission()})' 8
Say "POST RESULT: $post"

# --- verify it really reached the system notification manager BEFORE tapping ---
Start-Sleep -Seconds 3
$nd = (A shell dumpsys notification --noredact 2>&1 | Out-String)
$nd | Out-File "$root\research\web\_r2_n2_notifydump.txt" -Encoding UTF8
$hasIt = $nd -match 'N2-R2'
Say "SYSTEM NOTIFICATION CONTAINS 'N2-R2' = $hasIt"
($nd -split "`r?`n" | Where-Object { $_ -match 'N2-R2|opencodeshell' } | Select-Object -First 8) | ForEach-Object { Say "  ND: $_" }

Say "--- STEP 2 [REAL]: expand shade, dump, parse bounds NOW, input tap ---"
A shell cmd statusbar expand-notifications | Out-Null
Start-Sleep -Seconds 5
A shell uiautomator dump /sdcard/r2t2.xml | Out-Null
$tx=(A shell cat /sdcard/r2t2.xml 2>&1|Out-String)
$titles=[regex]::Matches($tx,'text="([^"]+)"')|ForEach-Object{$_.Groups[1].Value}|Select-Object -Unique
Say "shade texts: $((($titles|Select-Object -First 20) -join ' | '))"
$tm=[regex]::Match($tx,'<node[^>]*text="N2-R2"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if(-not $tm.Success){ $tm=[regex]::Match($tx,'text="N2-R2"[\s\S]{0,800}?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') }
$realTap=$false
if(-not $tm.Success){
  Say "!! N2-R2 card NOT found in shade (system has it: $hasIt)"
} else {
  $raw=$tm.Value; $b=@([int]$tm.Groups[1].Value,[int]$tm.Groups[2].Value,[int]$tm.Groups[3].Value,[int]$tm.Groups[4].Value)
  $x=[int](($b[0]+$b[2])/2); $y=[int](($b[1]+$b[3])/2)
  Say "NODE RAW: $($raw -replace '\s+',' ')"
  Say "PARSED bounds=[$($b[0]),$($b[1])][$($b[2]),$($b[3])] -> tap=$x $y"
  ($raw -replace '\s+',' ') | Out-File "$root\research\web\_r2_n2_node.txt" -Encoding UTF8
  A shell input tap $x $y | Out-Null; $realTap=$true
  Say ">>> REAL TAP: `input tap $x $y` on the SystemUI card (system-generated touch) <<<"
}
Say "REAL_TAP_PERFORMED = $realTap"
Start-Sleep -Seconds 14
Say "FOCUS: $((A shell dumpsys window 2>&1|Select-String 'mCurrentFocus'|Select-Object -Last 1|Out-String).Trim())"
Say "TOP:   $((A shell dumpsys activity activities 2>&1|Select-String 'topResumedActivity'|Select-Object -First 1|Out-String).Trim())"
$hl=(A logcat -d 2>&1|Select-String 'OcWebHost'|Select-String -NotMatch 'Proxy: GET'|Select-Object -Last 12|Out-String)
Say "HOST LOG (non-proxy):`n$hl"
$hl | Out-File "$root\research\web\_r2_n2_hostlog.txt" -Encoding UTF8
$after = EvalJS 'JSON.stringify({loc:location.pathname,href:location.href,lastRoute:window.__lastOpenSessionRoute||null,lastSid:window.__lastOpenSessionId||null})' 8
Say "AFTER: $after"
$after | Out-File "$root\research\web\_r2_n2_after.txt" -Encoding UTF8
$pass = ($after -match '/session/') -and ($after -match [regex]::Escape($realSid))
Say "ASSERT routed-to-session = $pass"
$pass | Out-File "$root\research\web\_r2_n2_assert.txt" -Encoding ASCII
Say "=== N2b DONE ==="
