$ErrorActionPreference='Continue'
$root="C:\sensorsdata\main\program\opencode-android-shell"
$sdk="$env:LOCALAPPDATA\Android\Sdk"
$adb="$sdk\platform-tools\adb.exe"
$S="emulator-5900"
$PKG='com.xiufeigo.opencodeshell.web.debug'
$ACT='com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity'
$out="$root\research\web\_r2_ime.log"
function Say($m){ $s="[$(Get-Date -Format 'HH:mm:ss')] $m"; Write-Host $s; $s | Out-File $out -Append -Encoding UTF8 }
function A { & $adb -s $S @args }
function EvalJS($js,$wait=8){
  A shell "am start -n $ACT --es oc_debug_eval '$js'" | Out-Null
  Start-Sleep -Seconds $wait
  (A logcat -d 2>&1 | Select-String 'OcWebHost/Eval' | Select-Object -Last 1 | Out-String).Trim()
}
Say "=== IME occlusion measurement (assert mInputShown BEFORE reading) ==="
if((A get-state 2>$null|Out-String).Trim() -ne 'device'){ Say "device gone - IME NOT MEASURED"; exit 1 }

# bring a text field up: real tap on the composer area, then check
Say "--- tap composer area (real input tap) to raise IME ---"
A shell input tap 540 1900 | Out-Null
Start-Sleep -Seconds 4

$im = (A shell dumpsys input_method 2>&1 | Out-String)
$im | Out-File "$root\research\web\_r2_ime_dump.txt" -Encoding UTF8
$shown = if($im -match 'mInputShown=(\w+)'){ $Matches[1] } else { 'NOT_FOUND' }
Say "ASSERT mInputShown = $shown"
$rawFrame = ($im -split "`r?`n" | Where-Object { $_ -match 'type=ime\s+frame=' } | Select-Object -First 1)
Say "RAW type=ime frame= LINE: $rawFrame"
$rawFrame | Out-File "$root\research\web\_r2_ime_frame.txt" -Encoding UTF8
if($rawFrame -match 'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){
  $imeTop=[int]$Matches[2]; $imeBot=[int]$Matches[4]
  Say "IME frame top=$imeTop bottom=$imeBot"
  $cr = EvalJS 'JSON.stringify((function(){var c=document.querySelector("textarea,[contenteditable=true],input[type=text]");if(!c)return{err:"no composer found"};var r=c.getBoundingClientRect();return{top:Math.round(r.top),bottom:Math.round(r.bottom),h:Math.round(r.height),vh:window.innerHeight,cc:Math.round(r.bottom+window.visualViewport.offsetTop)};})())' 8
  Say "COMPOSER RECT: $cr"
  $cr | Out-File "$root\research\web\_r2_ime_composer.txt" -Encoding UTF8
  $j = $cr -replace '^.*-> "','' -replace '"$',''
  try {
    $o = $j | ConvertFrom-Json
    if($o.err){ Say "OVERLAP: not computable ($($o.err))" }
    else {
      $composerBottom = $o.cc
      $ov = [Math]::Max(0, $composerBottom - $imeTop)
      Say "composer.bottom(+visualViewport.offsetTop) = $composerBottom ; imeTop = $imeTop"
      Say "IME OVERLAP = $ov px"
      $ov | Out-File "$root\research\web\_r2_ime_overlap.txt" -Encoding ASCII
    }
  } catch { Say "OVERLAP: parse failed on '$j'" }
} else { Say "!! type=ime frame= line not found" }
Say "=== IME DONE ==="
