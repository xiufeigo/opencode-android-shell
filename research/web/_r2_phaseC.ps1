$ErrorActionPreference='Continue'
$root="C:\sensorsdata\main\program\opencode-android-shell"
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S="emulator-5900"
$PKG='com.xiufeigo.opencodeshell.web.debug'
$ACT='com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity'
$out="$root\research\web\_r2_phaseC.log"
$realSid="ses_efc722bf9ffeWaRdoFTwbmYj1Y"
function Say($m){ $s="[$(Get-Date -Format 'HH:mm:ss')] $m"; Write-Host $s; $s | Out-File $out -Append -Encoding UTF8 }
function A { & $adb -s $S @args }
function EvalJS($js,$wait=8){
  A shell "am start -n $ACT --es oc_debug_eval '$js'" | Out-Null
  Start-Sleep -Seconds $wait
  (A logcat -d 2>&1 | Select-String 'OcWebHost/Eval' | Select-Object -Last 1 | Out-String).Trim()
}

Say "=== PHASE C: N2 real-tap verification ==="

# ---------- baseline ----------
Say "--- BASELINE location ---"
$base = EvalJS 'JSON.stringify({loc:location.pathname,href:location.href,txt:(document.body.innerText||"").replace(/\s+/g," ").trim().slice(0,160)})'
Say "BASELINE: $base"

# ---------- INJECTED: post the notification (NOT a user action) ----------
Say "--- STEP 1: post notification  [INJECTED call via oc_debug_eval, NOT a user tap] ---"
A logcat -c | Out-Null
$jsPost='JSON.stringify({ret:window.__ocAndroid.postMessage("N2-RC","rootcause click probe","'+$realSid+'"),perm:window.__ocAndroid.hasNotificationPermission()})'
$postRes = EvalJS $jsPost
Say "POST RESULT: $postRes"

# ---------- REAL tap ----------
Say "--- STEP 2: expand shade, dump, parse bounds AT THAT MOMENT, then REAL input tap ---"
A shell cmd statusbar expand-notifications | Out-Null
Start-Sleep -Seconds 4
A logcat -c | Out-Null
A shell uiautomator dump /sdcard/r2t.xml | Out-Null
$tx = (A shell cat /sdcard/r2t.xml 2>&1 | Out-String)
$titles = [regex]::Matches($tx,'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
Say "shade texts (first 20): $(($titles | Select-Object -First 20) -join ' | ')"

$tm=[regex]::Match($tx,'<node[^>]*text="N2-RC"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if(-not $tm.Success){ $tm=[regex]::Match($tx,'text="N2-RC"[\s\S]{0,500}?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') }
$realTap=$false
if(-not $tm.Success){
  Say "!! N2-RC card NOT found in shade"
} else {
  $rawNode=$tm.Value
  $b=@([int]$tm.Groups[1].Value,[int]$tm.Groups[2].Value,[int]$tm.Groups[3].Value,[int]$tm.Groups[4].Value)
  $x=[int](($b[0]+$b[2])/2); $y=[int](($b[1]+$b[3])/2)
  Say "NODE RAW MATCH: $($rawNode.Substring(0,[Math]::Min(300,$rawNode.Length)))"
  Say "PARSED bounds=[$($b[0]),$($b[1])][$($b[2]),$($b[3])] -> tap point = $x $y"
  A shell input tap $x $y | Out-Null
  $realTap=$true
  Say ">>> REAL TAP SENT: input tap $x $y (system-generated touch, NOT an injected call) <<<"
}
Say "REAL_TAP_PERFORMED = $realTap"

# ---------- read back ----------
Start-Sleep -Seconds 16
Say "--- STEP 3: read back focus / top / host log / page location ---"
$focus=(A shell dumpsys window 2>&1 | Select-String 'mCurrentFocus' | Select-Object -Last 1 | Out-String).Trim()
$top=(A shell dumpsys activity activities 2>&1 | Select-String 'topResumedActivity' | Select-Object -First 1 | Out-String).Trim()
Say "FOCUS: $focus"
Say "TOP:   $top"
$hostLog=(A logcat -d 2>&1 | Select-String 'OcWebHost' | Select-Object -Last 12 | Out-String)
Say "HOST LOG:`n$hostLog"
$hostLog | Out-File "$root\research\web\_r2_n2_hostlog.txt" -Encoding UTF8

$after = EvalJS 'JSON.stringify({loc:location.pathname,href:location.href,lastRoute:window.__lastOpenSessionRoute||null,lastSid:window.__lastOpenSessionId||null,txt:(document.body.innerText||"").replace(/\s+/g," ").trim().slice(0,200)})' 9
Say "AFTER: $after"
$after | Out-File "$root\research\web\_r2_n2_after.txt" -Encoding UTF8

# ---------- ASSERT ----------
$assertPass = ($after -match 'session') -and ($after -match [regex]::Escape($realSid.Substring(0,12)))
Say "ASSERT: location routed to the session = $assertPass"
$after | Out-File "$root\research\web\_r2_n2_assert.txt" -Encoding UTF8
Say "=== PHASE C DONE ==="
