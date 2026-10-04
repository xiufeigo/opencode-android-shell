# E4 · 机会主义取证：死盯"活窗口"，一旦 activity 服务可用就立刻推进
$ErrorActionPreference = 'Continue'
$adb  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S    = "emulator-5802"
$PKG  = "com.xiufeigo.opencodeshell.web.debug"
$ACT  = "$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$apk  = "C:\sensorsdata\main\program\opencode-android-shell\research\deliver\opencode-shell-web.apk"
$out  = "C:\sensorsdata\main\program\opencode-android-shell\research\env\phone4_shots"
$L    = "C:\sensorsdata\main\program\opencode-android-shell\research\env\logs\phoneE4_opportunistic.log"
New-Item -ItemType Directory -Force -Path $out | Out-Null
function Say($m) { $s = "[{0}] {1}" -f (Get-Date -Format HH:mm:ss), $m; Write-Host $s; Add-Content $L $s }
function Adb { & $adb -s $S @args }
function B($xml, $rx) {
  $m = [regex]::Match($xml, $rx); if (-not $m.Success) { return $null }
  $b = [regex]::Match($m.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if ($b.Success) { return @([int]$b.Groups[1].Value,[int]$b.Groups[2].Value,[int]$b.Groups[3].Value,[int]$b.Groups[4].Value) }
  return $null
}
function DumpUi($tag) {
  Adb shell uiautomator dump /data/local/tmp/$tag.xml | Out-Null
  Adb pull /data/local/tmp/$tag.xml "$out\E4_$tag.xml" 2>&1 | Out-Null
  $p = "$out\E4_$tag.xml"; if (Test-Path $p) { return (Get-Content $p -Raw) } else { return "" }
}
function EvalJs($js, $label) {
  Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
  Adb shell am start -n $ACT --es oc_debug_eval "$js" 2>&1 | Out-Null
  Start-Sleep -Seconds 8
  $log = Adb logcat -d -t 2000 2>&1
  $ev = $log | Select-String 'OcWebHost/Eval' | Select-Object -Last 2
  if ($ev) { $ev | ForEach-Object { Say ("  [$label] " + $_.ToString().Trim()) }; return $true }
  Say "  [$label] !! no Eval line"; return $false
}
function SvcOk {
  $a = (Adb shell service check activity 2>&1) -join ''
  $w = (Adb shell service check window 2>&1) -join ''
  return (($a -match 'found') -and ($w -match 'found'))
}

Say "=== OPPORTUNISTIC start ==="
$deadline = (Get-Date).AddSeconds(600)
$installed = $false
$launched = $false
$round = 0

while ((Get-Date) -lt $deadline) {
  $round++
  if (-not (SvcOk)) { Start-Sleep -Seconds 3; continue }
  Say ("ROUND $round :: live window (activity+window found)")

  if (-not $installed) {
    $r = (Adb install -r -g $apk 2>&1) -join ' '
    if ($r -match 'Success') { $installed = $true; Say "  INSTALL OK" }
    else { Say ("  install: " + $r.Trim()); Start-Sleep -Seconds 3; continue }
  }
  if (-not $launched) {
    $res = (Adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $PKG 2>&1) -join ' '
    Say ("  resolve: " + $res.Trim())
    $st = (Adb shell am start -n $ACT 2>&1) -join ' '
    Say ("  am start: " + $st.Trim())
    if ($st -match 'Error|error|Can.t find') { Start-Sleep -Seconds 3; continue }
    $launched = $true
    Start-Sleep -Seconds 12
  }

  $xml = DumpUi 'dlg'
  $pass = B $xml '<node[^>]*class="android\.widget\.EditText"[^>]*password="true"[^>]*>'
  $btn1 = B $xml '<node[^>]*resource-id="android:id/button1"[^>]*>'
  Say ("  dialog dump len=" + $xml.Length + " pass=" + $(if($pass){"[$($pass -join ',')]"}else{'null'}) + " btn1=" + $(if($btn1){"[$($btn1 -join ',')]"}else{'null'}))

  if ($pass -and $btn1) {
    Say "  ==> FIRST-RUN DIALOG REACHED, configuring"
    Adb shell input tap ([int](($pass[0]+$pass[2])/2)) ([int](($pass[1]+$pass[3])/2)) | Out-Null
    Start-Sleep -Seconds 3
    Say ("  ime after tap: " + (((Adb shell dumpsys window insets 2>&1 | Select-String 'mInputShown|mShowRequested|mIsImeShowing' | Select-Object -First 4) -join ' ; ').Trim()))
    Say ("  ime RAW: " + (((Adb shell dumpsys window insets 2>&1 | Select-String 'ITYPE_IME|type=ime|frame=' | Select-Object -First 4) -join ' ; ').Trim()))
    Adb shell input text opencode | Out-Null
    Start-Sleep -Seconds 2
    $xml2 = DumpUi 'dlg2'
    $btn1b = B $xml2 '<node[^>]*resource-id="android:id/button1"[^>]*>'
    if (-not $btn1b) { $btn1b = $btn1 }
    $p2 = B $xml2 '<node[^>]*class="android\.widget\.EditText"[^>]*password="true"[^>]*>'
    if ($p2) { Say ("  dialog SHIFT = {0}px" -f ($p2[1]-$pass[1])) }
    Adb shell input tap ([int](($btn1b[0]+$btn1b[2])/2)) ([int](($btn1b[1]+$btn1b[3])/2)) | Out-Null
    Say ("  tapped SAVE ({0},{1})" -f [int](($btn1b[0]+$btn1b[2])/2), [int](($btn1b[1]+$btn1b[3])/2))
    Start-Sleep -Seconds 25
    Say ("  focus: " + (((Adb shell dumpsys window 2>&1 | Select-String 'mCurrentFocus' | Select-Object -First 1) -join '').Trim()))
    break
  } else {
    Say "  dialog not up yet / dump too small (restart loop) - retrying"
    Start-Sleep -Seconds 4
  }
}

Say "== POST-FLOW EVIDENCE =="
$xml3 = DumpUi 'official'
Say ("OFFICIAL UI dump len=" + $xml3.Length)
$txts = [regex]::Matches($xml3, 'text="([^"]{2,})"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne '' } | Select-Object -Unique
Say ("  distinct visible texts (" + $txts.Count + "): " + (($txts | Select-Object -First 40) -join ' | '))
[regex]::Matches($xml3, '<node[^>]*class="android\.webkit\.WebView"[^>]*>') | ForEach-Object { Say ("  WEBVIEW NODE: " + $_.Value) }

Say "== JS JUDGE =="
EvalJs 'JSON.stringify({t:document.title,u:location.href,origin:location.origin,cw:document.documentElement.clientWidth,sw:document.documentElement.scrollWidth,bodySW:document.body.scrollWidth,bodyCW:document.body.clientWidth,n:document.querySelectorAll("*").length,txt:(document.body.innerText||"").slice(0,200),now:Math.round(performance.now())})' 'BASIC' | Out-Null
EvalJs 'JSON.stringify({side:[...document.querySelectorAll("aside,nav,[data-sidebar],[class*=sidebar i],[class*=Sidebar]")].slice(0,8).map(e=>{const r=e.getBoundingClientRect();return [e.tagName,String(e.className).slice(0,30),Math.round(r.x),Math.round(r.y),Math.round(r.width),Math.round(r.height),getComputedStyle(e).display]})})' 'SIDEBAR' | Out-Null
EvalJs 'JSON.stringify({dpr:window.devicePixelRatio,vw:innerWidth,vh:innerHeight,comp:[...document.querySelectorAll("textarea,[contenteditable=true],input,button,[role=button],[role=textbox]")].slice(0,40).map(e=>{const r=e.getBoundingClientRect();return [(e.getAttribute("aria-label")||e.getAttribute("placeholder")||e.textContent||e.tagName).slice(0,18),Math.round(r.x),Math.round(r.y),Math.round(r.width),Math.round(r.height)]})})' 'COMPOSER' | Out-Null
EvalJs 'JSON.stringify({docSW:document.documentElement.scrollWidth,docCW:document.documentElement.clientWidth,bodySW:document.body.scrollWidth,bodyCW:document.body.clientWidth,overflow:document.documentElement.scrollWidth>document.documentElement.clientWidth,wide:[...document.querySelectorAll("*")].filter(e=>e.getBoundingClientRect().right>innerWidth+1).slice(0,8).map(e=>[e.tagName,String(e.className).slice(0,24),Math.round(e.getBoundingClientRect().right)])})' 'OVERFLOW' | Out-Null

Say "== LOGCAT =="
$log = Adb logcat -d -t 3000 2>&1
$log | Out-File "$out\E4_logcat.txt" -Encoding UTF8
$log | Select-String 'OcWebHost' | Where-Object { $_ -notmatch '_assets/' } | Select-Object -Last 25 | ForEach-Object { Say ("  " + $_.ToString().Trim()) }
$bad = $log | Select-String 'SyntaxError|Uncaught|net::ERR_|web\[ERROR\]'
if ($bad) { $bad | Select-Object -First 10 | ForEach-Object { Say ("  !! " + $_.ToString().Trim()) } } else { Say "  -- no SyntaxError/Uncaught/ERR_" }
Say "=== OPPORTUNISTIC end ==="
