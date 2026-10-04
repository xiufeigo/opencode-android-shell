$ErrorActionPreference='Continue'
$root="C:\sensorsdata\main\program\opencode-android-shell"
$sdk="$env:LOCALAPPDATA\Android\Sdk"
$adb="$sdk\platform-tools\adb.exe"
$S="emulator-5900"
$PKG='com.xiufeigo.opencodeshell.web.debug'
$ACT='com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity'
$out="$root\research\web\_r2_n2c.log"
$sid="ses_efc722bf9ffeWaRdoFTwbmYj1Y"
function Say($m){ $s="[$(Get-Date -Format 'HH:mm:ss')] $m"; Write-Host $s; $s | Out-File $out -Append -Encoding UTF8 }
function A { & $adb -s $S @args }
function EvalJS($js,$wait=8){
  A shell "am start -n $ACT --es oc_debug_eval '$js'" | Out-Null
  Start-Sleep -Seconds $wait
  (A logcat -d 2>&1 | Select-String 'OcWebHost/Eval' | Select-Object -Last 1 | Out-String).Trim()
}
Say "=== N2c + IME (single boot reuse) ==="
if((A get-state 2>$null|Out-String).Trim() -ne 'device'){ Say "device gone"; exit 1 }
A shell input keyevent KEYCODE_BACK | Out-Null; Start-Sleep -Seconds 3

# ---------- IME FIRST (while app is foreground) ----------
Say "########## IME ##########"
A shell input tap 540 1900 | Out-Null
Start-Sleep -Seconds 5
$im=(A shell dumpsys input_method 2>&1|Out-String)
$im | Out-File "$root\research\web\_r2_ime_dump.txt" -Encoding UTF8
$shown='NOT_FOUND'; if($im -match 'mInputShown=(\w+)'){ $shown=$Matches[1] }
Say "ASSERT mInputShown = $shown"
$rawFrame=($im -split "`r?`n" | Where-Object { $_ -match 'type=ime\s+frame=' } | Select-Object -First 1)
Say "RAW type=ime frame= LINE:`n$rawFrame"
$rawFrame | Out-File "$root\research\web\_r2_ime_frame.txt" -Encoding UTF8
if($rawFrame -and $rawFrame -match 'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){
  $imeTop=[int]$Matches[2]
  Say "imeTop = $imeTop"
  $cr=EvalJS 'JSON.stringify((function(){var c=document.querySelector("textarea,[contenteditable=true]");if(!c)return{err:"noc"};var r=c.getBoundingClientRect();var vv=window.visualViewport;return{b:Math.round(r.bottom),o:vv?Math.round(vv.offsetTop):0,vh:window.innerHeight};})())' 8
  Say "COMPOSER: $cr"
  $cr | Out-File "$root\research\web\_r2_ime_composer.txt" -Encoding UTF8
  $j=($cr -replace '^.*-> "','') -replace '"$',''
  try{
    $o=$j|ConvertFrom-Json
    if($o.err){ Say "OVERLAP: not computable ($($o.err))" }
    else{
      $cb=[int]$o.b + [int]$o.o
      $ov=[Math]::Max(0,$cb-$imeTop)
      Say "composer.bottom=$($o.b) + visualViewport.offsetTop=$($o.o) => $cb ; imeTop=$imeTop"
      Say "IME OVERLAP = $ov px"
      $ov | Out-File "$root\research\web\_r2_ime_overlap.txt" -Encoding ASCII
    }
  }catch{ Say "OVERLAP parse failed: $j" }
}

# ---------- N2 ----------
Say "########## N2 ##########"
A shell input keyevent KEYCODE_BACK | Out-Null; Start-Sleep -Seconds 2
Say "--- probe postMessage exists ---"
Say (EvalJS 'typeof window.__ocAndroid.postMessage' 7)
Say "--- STEP 1 [INJECTED, not a user action] ---"
A logcat -c | Out-Null
Say "POST: $(EvalJS ('window.__ocAndroid.postMessage("N2-R2","R2 recheck","'+$sid+'")') 8)"
Start-Sleep -Seconds 3
$nd=(A shell dumpsys notification --noredact 2>&1|Out-String)
$nd | Out-File "$root\research\web\_r2_n2_notifydump.txt" -Encoding UTF8
$hasIt = $nd -match 'N2-R2'
Say "SYSTEM NOTIFICATION HAS 'N2-R2' = $hasIt"
($nd -split "`r?`n" | Where-Object { $_ -match 'N2-R2' } | Select-Object -First 6) | ForEach-Object { Say "  ND: $($_ -replace '\s+',' ')" }

Say "--- STEP 2 [REAL tap] ---"
A shell cmd statusbar expand-notifications | Out-Null
Start-Sleep -Seconds 5
A shell uiautomator dump /sdcard/r2t4.xml | Out-Null
$tx=(A shell cat /sdcard/r2t4.xml 2>&1|Out-String)
$titles=[regex]::Matches($tx,'text="([^"]+)"')|ForEach-Object{$_.Groups[1].Value}|Select-Object -Unique
Say "shade texts: $((($titles|Select-Object -First 20) -join ' | '))"
$tm=[regex]::Match($tx,'<node[^>]*text="N2-R2"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if(-not $tm.Success){ $tm=[regex]::Match($tx,'text="N2-R2"[\s\S]{0,800}?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') }
$realTap=$false
if(-not $tm.Success){ Say "!! N2-R2 NOT in shade (system has: $hasIt)" }
else{
  $raw=($tm.Value -replace '\s+',' ')
  $b=@([int]$tm.Groups[1].Value,[int]$tm.Groups[2].Value,[int]$tm.Groups[3].Value,[int]$tm.Groups[4].Value)
  $x=[int](($b[0]+$b[2])/2); $y=[int](($b[1]+$b[3])/2)
  Say "NODE RAW: $raw"
  Say "PARSED bounds=[$($b[0]),$($b[1])][$($b[2]),$($b[3])] -> tap=$x $y"
  $raw | Out-File "$root\research\web\_r2_n2_node.txt" -Encoding UTF8
  A shell input tap $x $y | Out-Null; $realTap=$true
  Say ">>> REAL TAP: input tap $x $y (system touch) <<<"
}
Say "REAL_TAP_PERFORMED = $realTap"
Start-Sleep -Seconds 14
Say "FOCUS: $((A shell dumpsys window 2>&1|Select-String 'mCurrentFocus'|Select-Object -Last 1|Out-String).Trim())"
Say "TOP:   $((A shell dumpsys activity activities 2>&1|Select-String 'topResumedActivity'|Select-Object -First 1|Out-String).Trim())"
$hl=(A logcat -d 2>&1|Select-String 'OcWebHost'|Select-String -NotMatch 'Proxy: GET'|Select-Object -Last 10|Out-String)
Say "HOST LOG:`n$hl"
$hl | Out-File "$root\research\web\_r2_n2_hostlog.txt" -Encoding UTF8
$after=EvalJS 'JSON.stringify({loc:location.pathname,href:location.href,r:window.__lastOpenSessionRoute||null,s:window.__lastOpenSessionId||null})' 8
Say "AFTER: $after"
$after | Out-File "$root\research\web\_r2_n2_after.txt" -Encoding UTF8
$pass=($after -match '/session/') -and ($after -match [regex]::Escape($sid))
Say "ASSERT routed-to-session = $pass"
$pass | Out-File "$root\research\web\_r2_n2_assert.txt" -Encoding ASCII
Say "=== N2c DONE ==="
