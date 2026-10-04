$ErrorActionPreference='Continue'
$root="C:\sensorsdata\main\program\opencode-android-shell"
$sdk="$env:LOCALAPPDATA\Android\Sdk"
$adb="$sdk\platform-tools\adb.exe"
$emu="$sdk\emulator\emulator.exe"
$S="emulator-5900"
$PKG='com.xiufeigo.opencodeshell.web.debug'
$ACT='com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity'
$out="$root\research\web\_r2_n2final.log"
$realSid="ses_efc722bf9ffeWaRdoFTwbmYj1Y"
function Say($m){ $s="[$(Get-Date -Format 'HH:mm:ss')] $m"; Write-Host $s; $s | Out-File $out -Append -Encoding UTF8 }
function A { & $adb -s $S @args }
function EvalJS($js,$wait=8){
  A shell "am start -n $ACT --es oc_debug_eval '$js'" | Out-Null
  Start-Sleep -Seconds $wait
  (A logcat -d 2>&1 | Select-String 'OcWebHost/Eval' | Select-Object -Last 1 | Out-String).Trim()
}
Say "=== R2 FINAL: boot + facade wait + post + REAL tap ==="
Start-Process -FilePath $emu -ArgumentList @("-avd","OC_Deliver_R2","-port","5900","-no-window","-no-audio","-no-boot-anim","-no-snapshot","-gpu","swiftshader_indirect","-camera-back","none","-camera-front","none") -WindowStyle Hidden -RedirectStandardError "$root\research\web\_r2_emu3.err" -RedirectStandardOutput "$root\research\web\_r2_emu3.log"
A wait-for-device
for($i=0;$i -lt 90;$i++){ if((A shell getprop sys.boot_completed 2>$null|Out-String).Trim() -eq '1'){ Say "BOOTED poll=$i"; break }; Start-Sleep -Seconds 3 }
A shell wm size 1080x2400 | Out-Null
A shell wm density 420 | Out-Null
Say "GEOM: $(((A shell wm size|Out-String).Trim()) + ' | ' + ((A shell wm density|Out-String).Trim()))"
A reverse tcp:4095 tcp:4095 | Out-Null
A install -r -g "$root\webapp\build\outputs\apk\debug\webapp-debug.apk" 2>&1 | Select-Object -Last 1 | ForEach-Object { Say "install: $_" }
A shell am force-stop $PKG | Out-Null; Start-Sleep -Seconds 2
A logcat -c | Out-Null
A shell am start -n $ACT | Out-Null
Say "launched; settling 55s (facade + asset burst)"
Start-Sleep -Seconds 55
Say "alive = $((A get-state 2>$null|Out-String).Trim())"

$ready=$false
for($i=1;$i -le 8;$i++){
  $r = EvalJS 'JSON.stringify({rs:document.readyState,f:typeof window.__ocAndroid,t:document.title})' 7
  if($r -match 'readyState'){ Say "EVAL ALIVE (try ${i}): $r"; $ready=$true; break }
  Say "eval silent (try ${i}); waiting"
  Start-Sleep -Seconds 8
}
if(-not $ready){ Say "!! eval channel never answered - cannot post notification"; exit 2 }

$base = EvalJS 'JSON.stringify({loc:location.pathname,href:location.href})' 7
Say "BASELINE: $base"

Say "--- STEP 1 [INJECTED, not a user action]: postMessage ---"
A logcat -c | Out-Null
$post = EvalJS 'JSON.stringify({ret:window.__ocAndroid.postMessage("N2-R2","R2 deliver re-verify","'+$realSid+'"),perm:window.__ocAndroid.hasNotificationPermission()})' 8
Say "POST RESULT: $post"

Start-Sleep -Seconds 3
$nd = (A shell dumpsys notification --noredact 2>&1 | Out-String)
$nd | Out-File "$root\research\web\_r2_n2_notifydump.txt" -Encoding UTF8
$hasIt = $nd -match 'N2-R2'
Say "SYSTEM NOTIFICATION HAS 'N2-R2' = $hasIt"

Say "--- STEP 2 [REAL]: expand shade, dump, parse bounds NOW, input tap ---"
A shell cmd statusbar expand-notifications | Out-Null
Start-Sleep -Seconds 5
A shell uiautomator dump /sdcard/r2t3.xml | Out-Null
$tx=(A shell cat /sdcard/r2t3.xml 2>&1|Out-String)
$titles=[regex]::Matches($tx,'text="([^"]+)"')|ForEach-Object{$_.Groups[1].Value}|Select-Object -Unique
Say "shade texts: $((($titles|Select-Object -First 20) -join ' | '))"
$tm=[regex]::Match($tx,'<node[^>]*text="N2-R2"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if(-not $tm.Success){ $tm=[regex]::Match($tx,'text="N2-R2"[\s\S]{0,800}?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') }
$realTap=$false
if(-not $tm.Success){
  Say "!! N2-R2 card NOT found in shade (system has it: $hasIt)"
} else {
  $raw=($tm.Value -replace '\s+',' ')
  $b=@([int]$tm.Groups[1].Value,[int]$tm.Groups[2].Value,[int]$tm.Groups[3].Value,[int]$tm.Groups[4].Value)
  $x=[int](($b[0]+$b[2])/2); $y=[int](($b[1]+$b[3])/2)
  Say "NODE RAW: $raw"
  Say "PARSED bounds=[$($b[0]),$($b[1])][$($b[2]),$($b[3])] -> tap=$x $y"
  $raw | Out-File "$root\research\web\_r2_n2_node.txt" -Encoding UTF8
  A shell input tap $x $y | Out-Null; $realTap=$true
  Say ">>> REAL TAP: input tap $x $y on SystemUI card (system-generated touch) <<<"
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
Say "=== FINAL N2 DONE ==="
