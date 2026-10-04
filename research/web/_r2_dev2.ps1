$ErrorActionPreference='Continue'
$root="C:\sensorsdata\main\program\opencode-android-shell"
$sdk="$env:LOCALAPPDATA\Android\Sdk"
$adb="$sdk\platform-tools\adb.exe"
$S="emulator-5900"
$PKG='com.xiufeigo.opencodeshell.web.debug'
$ACT='com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity'
$out="$root\research\web\_r2_dev2.log"
$realSid="ses_efc722bf9ffeWaRdoFTwbmYj1Y"
function Say($m){ $s="[$(Get-Date -Format 'HH:mm:ss')] $m"; Write-Host $s; $s | Out-File $out -Append -Encoding UTF8 }
function A { & $adb -s $S @args }
function EvalJS($js,$wait=9){
  A shell "am start -n $ACT --es oc_debug_eval '$js'" | Out-Null
  Start-Sleep -Seconds $wait
  (A logcat -d 2>&1 | Select-String 'OcWebHost/Eval' | Select-Object -Last 1 | Out-String).Trim()
}

# ============ PHASE 2: N2 real-tap verification on DEBUG (eval channel available) ============
Say "=== PHASE 2: N2 real-tap verification (DEBUG apk; same oc-host.js bytes as release) ==="
$base = EvalJS 'JSON.stringify({loc:location.pathname,href:location.href})' 8
Say "BASELINE: $base"
Say "--- STEP 1 [INJECTED]: post notification via oc_debug_eval -> NOT a user action ---"
A logcat -c | Out-Null
$post = EvalJS 'JSON.stringify({ret:window.__ocAndroid.postMessage("N2-R2","R2 deliver re-verify","'+$realSid+'"),perm:window.__ocAndroid.hasNotificationPermission()})' 8
Say "POST RESULT: $post"
Say "--- STEP 2: expand shade, dump, parse bounds AT THAT MOMENT, then REAL `input tap` ---"
A shell cmd statusbar expand-notifications | Out-Null
Start-Sleep -Seconds 5
A logcat -c | Out-Null
A shell uiautomator dump /sdcard/r2t.xml | Out-Null
$tx=(A shell cat /sdcard/r2t.xml 2>&1|Out-String)
$titles=[regex]::Matches($tx,'text="([^"]+)"')|ForEach-Object{$_.Groups[1].Value}|Select-Object -Unique
Say "shade texts: $((($titles|Select-Object -First 18) -join ' | '))"
$tm=[regex]::Match($tx,'<node[^>]*text="N2-R2"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if(-not $tm.Success){ $tm=[regex]::Match($tx,'text="N2-R2"[\s\S]{0,600}?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') }
$realTap=$false
if(-not $tm.Success){
  Say "!! N2-R2 card NOT found in shade"
} else {
  $raw=$tm.Value; $b=@([int]$tm.Groups[1].Value,[int]$tm.Groups[2].Value,[int]$tm.Groups[3].Value,[int]$tm.Groups[4].Value)
  $x=[int](($b[0]+$b[2])/2); $y=[int](($b[1]+$b[3])/2)
  Say "NODE RAW: $($raw.Substring(0,[Math]::Min(320,$raw.Length)))"
  Say "PARSED bounds=[$($b[0]),$($b[1])][$($b[2]),$($b[3])] -> tap=$x $y"
  A shell input tap $x $y | Out-Null; $realTap=$true
  Say ">>> REAL TAP: `input tap $x $y` on the SystemUI card (system-generated touch) <<<"
  $raw | Out-File "$root\research\web\_r2_n2_node.txt" -Encoding UTF8
}
Say "REAL_TAP_PERFORMED = $realTap"
Start-Sleep -Seconds 16
Say "--- STEP 3: read back ---"
Say "FOCUS: $((A shell dumpsys window 2>&1|Select-String 'mCurrentFocus'|Select-Object -Last 1|Out-String).Trim())"
Say "TOP:   $((A shell dumpsys activity activities 2>&1|Select-String 'topResumedActivity'|Select-Object -First 1|Out-String).Trim())"
$hl=(A logcat -d 2>&1|Select-String 'OcWebHost'|Select-Object -Last 14|Out-String)
Say "HOST LOG:`n$hl"
$hl | Out-File "$root\research\web\_r2_n2_hostlog.txt" -Encoding UTF8
$after = EvalJS 'JSON.stringify({loc:location.pathname,href:location.href,lastRoute:window.__lastOpenSessionRoute||null,lastSid:window.__lastOpenSessionId||null})' 9
Say "AFTER: $after"
$after | Out-File "$root\research\web\_r2_n2_after.txt" -Encoding UTF8
$pass = ($after -match '/session/') -and ($after -match [regex]::Escape($realSid))
Say "ASSERT routed-to-session = $pass"
$pass | Out-File "$root\research\web\_r2_n2_assert.txt" -Encoding ASCII
Say "=== PHASE 2 DONE ==="
