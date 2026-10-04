$ErrorActionPreference='Continue'
$root="C:\sensorsdata\main\program\opencode-android-shell"
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S="emulator-5900"
$PKG='com.xiufeigo.opencodeshell.web.debug'
$ACT='com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity'
$out="$root\research\web\_r2_phaseB.log"
$realSid="ses_efc722bf9ffeWaRdoFTwbmYj1Y"
function Say($m){ $s="[$(Get-Date -Format 'HH:mm:ss')] $m"; Write-Host $s; $s | Out-File $out -Append -Encoding UTF8 }
function A { & $adb -s $S @args }
Say "=== PHASE B: launch / seed / identity / first screen ==="

A shell am force-stop $PKG | Out-Null
Start-Sleep -Seconds 2
A logcat -c | Out-Null
Say "launch: $ACT"
A shell am start -n $ACT | Out-Null
Start-Sleep -Seconds 14

# ---- seed password ----
A shell uiautomator dump /sdcard/r2b.xml | Out-Null
$xml = (A shell cat /sdcard/r2b.xml 2>&1 | Out-String)
$pb=$null
foreach($m in [regex]::Matches($xml,'<node[^>]*')){
  $n=$m.Value
  if($n -match 'password="true"' -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){ $pb=@([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4]); break }
}
if($pb){
  Say "SEED: password field bounds=[$($pb[0]),$($pb[1])][$($pb[2]),$($pb[3])]"
  A shell input tap ([int](($pb[0]+$pb[2])/2)) ([int](($pb[1]+$pb[3])/2)) | Out-Null
  Start-Sleep -Milliseconds 900
  A shell input text "opencode" | Out-Null
  Start-Sleep -Milliseconds 900
  $btns=@()
  foreach($m in [regex]::Matches($xml,'<node[^>]*')){
    $n=$m.Value
    if($n -match 'class="android\.widget\.Button"' -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' -and $n -notmatch 'enabled="false"'){ $btns += ,@([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4]) }
  }
  if($btns.Count -gt 0){
    $ok=$btns|Sort-Object{($_[0]+$_[2])/2}|Select-Object -Last 1
    Say "SEED: buttons=$($btns.Count) rightmost=[$($ok[0]),$($ok[1])][$($ok[2]),$($ok[3])] -> real tap"
    A shell input tap ([int](($ok[0]+$ok[2])/2)) ([int](($ok[1]+$ok[3])/2)) | Out-Null
  } else { Say "SEED: no button -> ENTER"; A shell input keyevent KEYCODE_ENTER | Out-Null }
  Start-Sleep -Seconds 20
} else { Say "SEED: no password dialog found" }

$lc=(A logcat -d -s OcWebHost:V OcPerf:V OcPerfJS:V 2>&1 | Out-String)
$lv = if($lc -match 'credentials stored'){'SEEDED OK'} elseif($lc -match 'no password in SecretStore'){'STILL EMPTY'} else {'UNKNOWN'}
Say "SEED VERDICT = $lv"
$lc | Out-File "$root\research\web\_r2_seed_logcat.txt" -Encoding UTF8

# ---- identity precheck via CDP (the repo's own oc_debug_eval channel) ----
Say "--- IDENTITY PRECHECK (title / DOM / visible text) ---"
$js='JSON.stringify({title:document.title,href:location.href,ready:document.readyState,rootChildren:(document.getElementById("root")||{children:[]}).children.length,txt:(document.body.innerText||"").replace(/\s+/g," ").trim().slice(0,400)})'
A shell "am start -n $ACT --es oc_debug_eval '$js'" | Out-Null
Start-Sleep -Seconds 8
$idLine = (A logcat -d 2>&1 | Select-String 'OcWebHost/Eval' | Select-Object -Last 1 | Out-String).Trim()
Say "IDENTITY: $idLine"
$idLine | Out-File "$root\research\web\_r2_identity.txt" -Encoding UTF8

# ---- official UI render + first screen (perf markers, NOT last _assets GET) ----
Say "--- FIRST SCREEN (OcPerf/OcPerfJS markers) ---"
$perf = (A logcat -d -s OcPerf:V OcPerfJS:V 2>&1 | Out-String)
$perfLines = $perf -split "`r?`n" | Where-Object { $_ -match 'OcPerf' }
$perfLines | ForEach-Object { Say "  PERF: $_" }
$perfLines | Out-File "$root\research\web\_r2_perf.txt" -Encoding UTF8

# ---- foreground check ----
Say "--- FOREGROUND ---"
$focus=(A shell dumpsys window 2>&1 | Select-String 'mCurrentFocus' | Select-Object -Last 1 | Out-String).Trim()
$top=(A shell dumpsys activity activities 2>&1 | Select-String 'topResumedActivity' | Select-Object -First 1 | Out-String).Trim()
Say "FOCUS: $focus"
Say "TOP:   $top"
Say "=== PHASE B DONE ==="
