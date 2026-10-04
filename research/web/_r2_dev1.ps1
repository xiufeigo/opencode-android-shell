$ErrorActionPreference='Continue'
$root="C:\sensorsdata\main\program\opencode-android-shell"
$sdk="$env:LOCALAPPDATA\Android\Sdk"
$adb="$sdk\platform-tools\adb.exe"
$emu="$sdk\emulator\emulator.exe"
$S="emulator-5900"
$PKG='com.xiufeigo.opencodeshell.web.debug'
$ACT='com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity'
$out="$root\research\web\_r2_dev.log"
$realSid="ses_efc722bf9ffeWaRdoFTwbmYj1Y"
function Say($m){ $s="[$(Get-Date -Format 'HH:mm:ss')] $m"; Write-Host $s; $s | Out-File $out -Append -Encoding UTF8 }
function A { & $adb -s $S @args }
function EvalJS($js,$wait=9){
  A shell "am start -n $ACT --es oc_debug_eval '$js'" | Out-Null
  Start-Sleep -Seconds $wait
  (A logcat -d 2>&1 | Select-String 'OcWebHost/Eval' | Select-Object -Last 1 | Out-String).Trim()
}

# ================= boot =================
if (-not (A get-state 2>$null | Out-String).Trim().StartsWith('device')) {
  Say "BOOT: launching OC_Deliver_R2 on 5900"
  Start-Process -FilePath $emu -ArgumentList @("-avd","OC_Deliver_R2","-port","5900","-no-window","-no-audio","-no-boot-anim","-no-snapshot","-gpu","swiftshader_indirect","-camera-back","none","-camera-front","none") -WindowStyle Hidden -RedirectStandardError "$root\research\web\_r2_emu_5900.err" -RedirectStandardOutput "$root\research\web\_r2_emu_5900.log"
  A wait-for-device
  for($i=0;$i -lt 100;$i++){ if((A shell getprop sys.boot_completed 2>$null|Out-String).Trim() -eq '1'){ Say "BOOTED poll=$i"; break }; Start-Sleep -Seconds 3 }
}
A shell wm size 1080x2400 | Out-Null
A shell wm density 420 | Out-Null
Say "GEOM: $(((A shell wm size|Out-String).Trim()) + ' | ' + ((A shell wm density|Out-String).Trim()))"
A reverse tcp:4095 tcp:4095 | Out-Null
Say "REVERSE: $((A reverse --list|Out-String).Trim())"

# ================= PHASE 1: DEBUG build =================
$dbg="$root\webapp\build\outputs\apk\debug\webapp-debug.apk"
Say "=== PHASE 1: DEBUG apk (eval channel available) md5=$((Get-FileHash $dbg -Algorithm MD5).Hash.ToLower()) ==="
A install -r -g $dbg 2>&1 | Select-Object -Last 1 | ForEach-Object { Say "install: $_" }
A shell am force-stop $PKG | Out-Null; Start-Sleep -Seconds 2
A logcat -c | Out-Null
A shell am start -n $ACT | Out-Null
Start-Sleep -Seconds 18

# seed if dialog
A shell uiautomator dump /sdcard/r2s.xml | Out-Null
$xml=(A shell cat /sdcard/r2s.xml 2>&1|Out-String)
$pb=$null
foreach($m in [regex]::Matches($xml,'<node[^>]*')){ $n=$m.Value; if($n -match 'password="true"' -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){ $pb=@([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4]); break } }
if($pb){
  Say "SEED: dialog found field=[$($pb[0]),$($pb[1])][$($pb[2]),$($pb[3])] -> REAL tap + type opencode"
  A shell input tap ([int](($pb[0]+$pb[2])/2)) ([int](($pb[1]+$pb[3])/2)) | Out-Null; Start-Sleep -Milliseconds 900
  A shell input text "opencode" | Out-Null; Start-Sleep -Milliseconds 900
  $btns=@(); foreach($m in [regex]::Matches($xml,'<node[^>]*')){ $n=$m.Value; if($n -match 'class="android\.widget\.Button"' -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' -and $n -notmatch 'enabled="false"'){ $btns += ,@([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4]) } }
  if($btns.Count -gt 0){ $ok=$btns|Sort-Object{($_[0]+$_[2])/2}|Select-Object -Last 1; Say "SEED: rightmost btn [$($ok[0]),$($ok[1])][$($ok[2]),$($ok[3])] -> REAL tap"; A shell input tap ([int](($ok[0]+$ok[2])/2)) ([int](($ok[1]+$ok[3])/2)) | Out-Null } else { A shell input keyevent KEYCODE_ENTER | Out-Null }
  Start-Sleep -Seconds 20
} else { Say "SEED: no password dialog on this launch" }
$pw=(A logcat -d 2>&1 | Select-String 'passwordPresent|credentials stored|no password' | Select-Object -Last 2 | Out-String).Trim()
Say "SEED evidence: $pw"
$pw | Out-File "$root\research\web\_r2_seed_evidence.txt" -Encoding UTF8

# ---- IDENTITY (CDP-equivalent = repo's own oc_debug_eval channel) ----
Say "--- IDENTITY: title / DOM / visible text ---"
$id = EvalJS 'JSON.stringify({title:document.title,ready:document.readyState,href:location.href,rootKids:(document.getElementById("root")||{children:[]}).children.length,txt:(document.body.innerText||"").replace(/\s+/g," ").trim().slice(0,300)})'
Say "IDENTITY: $id"
$id | Out-File "$root\research\web\_r2_identity_debug.txt" -Encoding UTF8

# ---- FIRST SCREEN (cold relaunch, UI-usable markers) ----
Say "--- FIRST SCREEN: cold relaunch, capture UI-usable markers ---"
A shell am force-stop $PKG | Out-Null; Start-Sleep -Seconds 3
A logcat -c | Out-Null
A shell am start -n $ACT | Out-Null
Start-Sleep -Seconds 22
$perf=(A logcat -d -s OcPerf:V OcWebHost:V 2>&1 | Out-String)
$mk=$perf -split "`r?`n" | Where-Object { $_ -match 'OcPerf\s*:' -and $_ -notmatch 'P\.fwd' }
$mk | ForEach-Object { Say "  MARK: $_" }
$mk | Out-File "$root\research\web\_r2_markers_debug.txt" -Encoding UTF8
$assets=([regex]::Matches($perf,'P\.fwd')).Count
Say "  (total P.fwd asset GETs in this window = $assets  <- NOT used as first screen)"
$home=(A logcat -d 2>&1 | Select-String 'OcWebHost: home=' | Select-Object -Last 1 | Out-String).Trim()
Say "  $home"
Say "=== PHASE 1 DONE ==="
