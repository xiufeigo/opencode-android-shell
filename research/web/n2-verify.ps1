$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$emu = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
$root = "C:\sensorsdata\main\program\opencode-android-shell"
$ACT = "com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity"
$realSid = "ses_efc722bf9ffeWaRdoFTwbmYj1Y"
$S = "emulator-5880"
function Say($m) { Write-Host "### $m" }

# ---------- 1. boot ----------
Say "launching OC_NotifyRC on 5880"
Start-Process -FilePath $emu -ArgumentList @("-avd","OC_NotifyRC","-port","5880","-no-window","-no-audio","-no-boot-anim","-no-snapshot","-gpu","swiftshader_indirect","-camera-back","none","-camera-front","none") -WindowStyle Hidden
& $adb -s $S wait-for-device
for ($i=0; $i -lt 100; $i++) {
  if ((& $adb -s $S shell getprop sys.boot_completed 2>$null | Out-String).Trim() -eq '1') { Say "BOOTED (poll $i)"; break }
  Start-Sleep -Seconds 3
}
Say "geometry"
& $adb -s $S shell wm size 1080x2400 | Out-Null
& $adb -s $S shell wm density 420 | Out-Null
& $adb -s $S shell wm size
& $adb -s $S shell wm density
& $adb -s $S reverse tcp:4095 tcp:4095 | Out-Null
Say "reverse: $(& $adb -s $S reverse --list)"

# ---------- 2. install + launch ----------
Say "install APK"
& $adb -s $S install -r -g "$root\webapp\build\outputs\apk\debug\webapp-debug.apk" 2>&1 | Select-Object -Last 1
& $adb -s $S shell am force-stop com.xiufeigo.opencodeshell.web.debug
Start-Sleep -Seconds 2
& $adb -s $S logcat -c
Say "launch app"
& $adb -s $S shell am start -n $ACT | Out-Null
Start-Sleep -Seconds 25

# ---------- 3. password dialog if present ----------
$dump = & $adb -s $S shell uiautomator dump /sdcard/n2p.xml 2>$null | Out-Null
$xml = & $adb -s $S shell cat /sdcard/n2p.xml 2>&1 | Out-String
if ($xml -match 'alertTitle') {
  Say "password dialog present -> entering"
  $pm = [regex]::Match($xml, 'password="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if (-not $pm.Success) { $pm = [regex]::Match($xml, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*password="true"') }
  if ($pm.Success) {
    $px = [int](([int]$pm.Groups[1].Value + [int]$pm.Groups[3].Value)/2)
    $py = [int](([int]$pm.Groups[2].Value + [int]$pm.Groups[4].Value)/2)
    & $adb -s $S shell input tap $px $py
    Start-Sleep -Seconds 1
    & $adb -s $S shell input text "opencode"
    Start-Sleep -Seconds 1
    & $adb -s $S shell input keyevent 111
    Start-Sleep -Seconds 2
    & $adb -s $S shell uiautomator dump /sdcard/n2q.xml 2>$null | Out-Null
    $xml2 = & $adb -s $S shell cat /sdcard/n2q.xml 2>&1 | Out-String
    $bm = [regex]::Match($xml2, 'resource-id="android:id/button1"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    $bx = [int](([int]$bm.Groups[1].Value + [int]$bm.Groups[3].Value)/2)
    $by = [int](([int]$bm.Groups[2].Value + [int]$bm.Groups[4].Value)/2)
    Say "tap save at $bx $by"
    & $adb -s $S shell input tap $bx $by
    Start-Sleep -Seconds 30
  }
} else { Say "no password dialog (credential persisted)" }

# ---------- 4. baseline ----------
Say "BASELINE location"
$js = 'JSON.stringify({loc:location.pathname,href:location.href})'
& $adb -s $S shell "am start -n $ACT --es oc_debug_eval '$js'" | Out-Null
Start-Sleep -Seconds 7
& $adb -s $S logcat -d 2>&1 | Select-String 'OcWebHost/Eval' | Select-Object -Last 1

# ---------- 5. post notification (INJECTED, labelled) ----------
Say "posting notification (INJECTED call, NOT a user action)"
& $adb -s $S logcat -c
$jsPost = 'JSON.stringify({ret:window.__ocAndroid.postMessage("N2-RC","rootcause click probe","' + $realSid + '"),perm:window.__ocAndroid.hasNotificationPermission()})'
& $adb -s $S shell "am start -n $ACT --es oc_debug_eval '$jsPost'" | Out-Null
Start-Sleep -Seconds 7
Say "post result:"
& $adb -s $S logcat -d 2>&1 | Select-String 'OcWebHost/Eval' | Select-Object -Last 1

# ---------- 6. REAL tap ----------
Say "clear logcat, expand, dump, parse, REAL tap"
& $adb -s $S shell cmd statusbar expand-notifications
Start-Sleep -Seconds 3
& $adb -s $S logcat -c
& $adb -s $S shell uiautomator dump /sdcard/n2t.xml 2>$null | Out-Null
$tx = & $adb -s $S shell cat /sdcard/n2t.xml 2>&1 | Out-String
$tm = [regex]::Match($tx, '<node[^>]*text="N2-RC"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if (-not $tm.Success) { $tm = [regex]::Match($tx, 'text="N2-RC"[\s\S]{0,400}?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') }
if (-not $tm.Success) {
  Say "!! N2-RC card not found in shade. Titles seen:"
  [regex]::Matches($tx,'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique | Select-Object -First 15
} else {
  $tb = "bounds=`"$($tm.Groups[1].Value),$($tm.Groups[2].Value)][$($tm.Groups[3].Value),$($tm.Groups[4].Value)`""
  $x = [int](([int]$tm.Groups[1].Value + [int]$tm.Groups[3].Value)/2)
  $y = [int](([int]$tm.Groups[2].Value + [int]$tm.Groups[4].Value)/2)
  Say "NODE raw: $tb"
  Say "parsed tap = $x $y"
  & $adb -s $S shell input tap $x $y
  Say "TAPPED (real input tap on systemui card)"
}

# ---------- 7. AFTER ----------
Start-Sleep -Seconds 14
Say "=== AFTER: focus ==="
& $adb -s $S shell dumpsys window 2>&1 | Select-String 'mCurrentFocus'
Say "=== AFTER: topResumedActivity ==="
& $adb -s $S shell dumpsys activity activities 2>&1 | Select-String 'topResumedActivity'
Say "=== AFTER: host log (click consumption) ==="
& $adb -s $S logcat -d 2>&1 | Select-String 'OcWebHost' | Select-Object -Last 10
Say "=== AFTER: page location ==="
$js2 = 'JSON.stringify({loc:location.pathname,href:location.href,txt:(document.body.innerText||"").slice(0,200)})'
& $adb -s $S shell "am start -n $ACT --es oc_debug_eval '$js2'" | Out-Null
Start-Sleep -Seconds 8
& $adb -s $S logcat -d 2>&1 | Select-String 'OcWebHost/Eval' | Select-Object -Last 1
Say "=== DONE ==="
