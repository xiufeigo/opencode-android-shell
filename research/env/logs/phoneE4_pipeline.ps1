# E4 · 手机形态取证管线（不依赖截图）
# 判据优先级：1) uiautomator a11y 树命中官方 UI  2) oc_debug_eval 注入 JS 回传  3) logcat
$ErrorActionPreference = 'Continue'
$adb  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S    = "emulator-5802"
$PKG  = "com.xiufeigo.opencodeshell.web.debug"
$ACT  = "$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$apk  = "C:\sensorsdata\main\program\opencode-android-shell\research\deliver\opencode-shell-web.apk"
$out  = "C:\sensorsdata\main\program\opencode-android-shell\research\env\phone4_shots"
$L    = "C:\sensorsdata\main\program\opencode-android-shell\research\env\logs\phoneE4.log"
New-Item -ItemType Directory -Force -Path $out | Out-Null
function Say($m) { $s = "[{0}] {1}" -f (Get-Date -Format HH:mm:ss), $m; Write-Host $s; Add-Content $L $s }
function Adb { & $adb -s $S @args }
function Shot($name) {
  Adb shell screencap -p /data/local/tmp/$name.png 2>&1 | Out-Null
  Adb pull /data/local/tmp/$name.png "$out\$name.png" 2>&1 | Out-Null
  if (-not (Test-Path "$out\$name.png")) { & $adb -s $S exec-out screencap -p > "$out\$name.png" 2>$null }
  $f = Get-Item "$out\$name.png" -ErrorAction SilentlyContinue
  if ($f) { Say ("  shot {0} = {1} bytes" -f $name, $f.Length); return $true }
  Say ("  shot {0} = FAILED (screencap)" -f $name); return $false
}
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
function Eval($js, $label) {
  $js64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($js))
  Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
  Adb shell am start -n $ACT --es oc_debug_eval "$js" | Out-Null
  Start-Sleep -Seconds 10
  $log = Adb logcat -d -t 2500 2>&1
  $ev = $log | Select-String 'OcWebHost/Eval' | Select-Object -Last 3
  if ($ev) { $ev | ForEach-Object { Say ("  [$label] " + $_.ToString().Trim()) } } else { Say "  [$label] !! no OcWebHost/Eval line" }
  return $ev
}

Say "=== E4 pipeline start ($S, gpu=host) ==="
for ($i=0; $i -lt 90; $i++) {
  $bc = (Adb shell getprop sys.boot_completed 2>&1) -join ''
  if ($bc.Trim() -eq '1') { Say "BOOT_COMPLETED"; break }
  if ($i % 8 -eq 0) { Say ("  waiting +{0}s boot='{1}'" -f ($i*6), $bc.Trim()) }
  Start-Sleep -Seconds 6
}

Say "== STABILITY GATE: system_server pid x3 (10s apart) =="
$pids = @()
1..3 | ForEach-Object {
  $p = (Adb shell pidof system_server 2>&1) -join ''
  Say ("  pid@{0} = '{1}'" -f (Get-Date -Format HH:mm:ss), $p.Trim())
  $pids += $p.Trim(); Start-Sleep -Seconds 10
}
$stable = (($pids | Select-Object -Unique).Count -eq 1)
Say ("  STABLE=$stable  (values: " + ($pids -join ',') + ")")
Say ("  service check: " + (((Adb shell service check activity) -join '').Trim()) + " | " + (((Adb shell service check window) -join '').Trim()) + " | " + (((Adb shell service check package) -join '').Trim()))

Say "== FINGERPRINT =="
foreach ($p in 'ro.product.name','ro.product.model','ro.build.version.sdk','ro.build.version.release') { Say ("  {0} = {1}" -f $p, ((Adb shell getprop $p) -join '').Trim()) }
Say ("  wm size: " + (((Adb shell wm size) -join '').Trim()))
Say ("  wm density: " + (((Adb shell wm density) -join '').Trim()))
Say ("  uptime: " + (((Adb shell cat /proc/uptime) -join '').Trim()))
Say ("  webview: " + (((Adb shell dumpsys webviewupdate | Select-String 'Current WebView package' | Select-Object -First 1) -join '').Trim()))
Say ("  gpu: " + (((Adb shell getprop ro.hardware.egl) -join '').Trim()))

Say "== SCREENCAP SELFTEST =="
Shot 'E4_00_screencap_selftest'

Say "== INSTALL / REVERSE =="
Say ("  install: " + (((Adb install -r -g $apk) -join ' ').Trim()))
Say ("  reverse: " + (((Adb reverse tcp:4095 tcp:4095) -join ' ').Trim()))
Adb shell settings put secure show_ime_with_hard_keyboard 1 | Out-Null
Say ("  show_ime readback: '" + (((Adb shell settings get secure show_ime_with_hard_keyboard) -join '').Trim()) + "'")
Say ("  pkg lastUpdateTime: " + (((Adb shell dumpsys package $PKG | Select-String 'lastUpdateTime' | Select-Object -First 1) -join '').Trim()))
Say ("  pkg versionName: " + (((Adb shell dumpsys package $PKG | Select-String 'versionName' | Select-Object -First 1) -join '').Trim()))
Adb shell pm clear $PKG | Out-Null
Adb logcat -c
Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
Adb shell wm dismiss-keyguard | Out-Null

Say "== LAUNCH (web 壳首启流程) =="
Say ("  resolve: " + (((Adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $PKG) -join ' ').Trim()))
Say ("  am start: " + (((Adb shell am start -n $ACT) 2>&1) -join ' ').Trim())
Start-Sleep -Seconds 12
Say ("  mCurrentFocus: " + (((Adb shell dumpsys window | Select-String 'mCurrentFocus' | Select-Object -First 1) -join '').Trim()))

$xml = DumpUi 'dialog1'
Say ("SETUP DIALOG dump len=" + $xml.Length)
$pass = B $xml '<node[^>]*class="android\.widget\.EditText"[^>]*password="true"[^>]*>'
if (-not $pass) { $pass = B $xml '<node[^>]*password="true"[^>]*>' }
$btn1 = B $xml '<node[^>]*resource-id="android:id/button1"[^>]*>'
$btn2 = B $xml '<node[^>]*resource-id="android:id/button2"[^>]*>'
if ($pass) { Say ("  password EditText = [{0},{1}][{2},{3}] size={4}x{5}px" -f $pass[0],$pass[1],$pass[2],$pass[3],($pass[2]-$pass[0]),($pass[3]-$pass[1])) } else { Say "  !! password field not found" }
if ($btn1) { Say ("  button1(save) = [{0},{1}][{2},{3}] size={4}x{5}px" -f $btn1[0],$btn1[1],$btn1[2],$btn1[3],($btn1[2]-$btn1[0]),($btn1[3]-$btn1[1])) }
if ($btn2) { Say ("  button2(skip)  = [{0},{1}][{2},{3}] size={4}x{5}px" -f $btn2[0],$btn2[1],$btn2[2],$btn2[3],($btn2[2]-$btn2[0]),($btn2[3]-$btn2[1])) }
Shot 'E4_01_setup_dialog'

if ($pass) {
  Say "== Q2 IME: assert mInputShown BEFORE / AFTER =="
  Say ("  before: " + (((Adb shell dumpsys window insets | Select-String 'mInputShown|mShowRequested|mIsImeShowing') -join ' ; ').Trim()))
  Adb shell input tap ([int](($pass[0]+$pass[2])/2)) ([int](($pass[1]+$pass[3])/2)) | Out-Null
  Start-Sleep -Seconds 4
  Say ("  after:  " + (((Adb shell dumpsys window insets | Select-String 'mInputShown|mShowRequested|mShowExplicitlyRequested|mIsImeShowing') -join ' ; ').Trim()))
  Say ("  ime RAW: " + (((Adb shell dumpsys window insets | Select-String 'ITYPE_IME|type=ime|frame=' | Select-Object -First 6) -join ' ; ').Trim()))
  Adb shell input text opencode | Out-Null
  Start-Sleep -Seconds 2
  # bounds 会跳变：IME 弹出后重新 dump 再取按钮
  $xml2 = DumpUi 'dialog2'
  $pass2 = B $xml2 '<node[^>]*class="android\.widget\.EditText"[^>]*password="true"[^>]*>'
  $btn1b = B $xml2 '<node[^>]*resource-id="android:id/button1"[^>]*>'
  if (-not $btn1b) { $btn1b = $btn1 }
  if ($pass) { Say ("  pass field SHIFT = {0}px (was y={1}, now y={2})" -f ($pass2[1]-$pass[1]), $pass[1], $pass2[1]) }
  Shot 'E4_02_ime_dialog'
  Adb shell input tap ([int](($btn1b[0]+$btn1b[2])/2)) ([int](($btn1b[1]+$btn1b[3])/2)) | Out-Null
  Say ("  tapped SAVE at ({0},{1})" -f [int](($btn1b[0]+$btn1b[2])/2), [int](($btn1b[1]+$btn1b[3])/2))
  Start-Sleep -Seconds 30
} else { Say "!! no password field -> cannot configure; abort flow" }

Say "== OFFICIAL UI =="
Say ("  mCurrentFocus: " + (((Adb shell dumpsys window | Select-String 'mCurrentFocus' | Select-Object -First 1) -join '').Trim()))
Shot 'E4_03_official_ui'

$xml3 = DumpUi 'official'
Say ("OFFICIAL UI dump len=" + $xml3.Length)
$txts = [regex]::Matches($xml3, 'text="([^"]{2,})"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne '' } | Select-Object -Unique
Say ("  distinct visible texts (" + $txts.Count + "): " + (($txts | Select-Object -First 40) -join ' | '))
[regex]::Matches($xml3, '<node[^>]*class="android\.webkit\.WebView"[^>]*>') | ForEach-Object { Say ("  WEBVIEW NODE: " + $_.Value) }
$hits = $xml3 | Select-String '会话|Session|侧栏|sidebar|composer|Composer|命令|Command|发送|Send' -AllMatches
if ($hits) { Say "  !! official-UI keywords FOUND in a11y tree" } else { Say "  -- no official-UI keywords in a11y tree (WebView content not exposed)" }

Say "== JUDGE 2: JS injection (evaluateJavascript via oc_debug_eval) =="
$js1 = 'JSON.stringify({t:document.title,u:location.href,origin:location.origin,cw:document.documentElement.clientWidth,sw:document.documentElement.scrollWidth,bodySW:document.body.scrollWidth,bodyCW:document.body.clientWidth,n:document.querySelectorAll("*").length,txt:(document.body.innerText||"").slice(0,200),now:Math.round(performance.now())})'
Eval $js1 'BASIC' | Out-Null

Say "== Q1 SIDEBAR =="
$js2 = 'JSON.stringify({side:[...document.querySelectorAll("aside,nav,[data-sidebar],[class*=sidebar i],[class*=Sidebar]")].slice(0,8).map(e=>{const r=e.getBoundingClientRect();return [e.tagName,e.className&&e.className.toString().slice(0,30),Math.round(r.x),Math.round(r.y),Math.round(r.width),Math.round(r.height),getComputedStyle(e).display]})})'
Eval $js2 'SIDEBAR' | Out-Null

Say "== Q2/Q3 COMPOSER + bounds (dp = px / (density/160)) =="
$js3 = 'JSON.stringify({dpr:window.devicePixelRatio,vw:innerWidth,vh:innerHeight,comp:[...document.querySelectorAll("textarea,[contenteditable=true],input,button,[role=button],[role=textbox]")].slice(0,40).map(e=>{const r=e.getBoundingClientRect();return [(e.getAttribute("aria-label")||e.getAttribute("placeholder")||e.textContent||e.tagName).slice(0,18),Math.round(r.x),Math.round(r.y),Math.round(r.width),Math.round(r.height)]})})'
Eval $js3 'COMPOSER' | Out-Null

Say "== Q4 horizontal overflow =="
$js4 = 'JSON.stringify({docSW:document.documentElement.scrollWidth,docCW:document.documentElement.clientWidth,bodySW:document.body.scrollWidth,bodyCW:document.body.clientWidth,overflow:document.documentElement.scrollWidth>document.documentElement.clientWidth,wide:[...document.querySelectorAll("*")].filter(e=>e.getBoundingClientRect().right>innerWidth+1).slice(0,8).map(e=>[e.tagName,(e.className&&e.className.toString().slice(0,24)),Math.round(e.getBoundingClientRect().right)])})'
Eval $js4 'OVERFLOW' | Out-Null

Say "== LOGCAT (no --pid=, per E3) =="
$log = Adb logcat -d -t 3000 2>&1
$log | Out-File "$out\E4_logcat.txt" -Encoding UTF8
$log | Select-String 'OcWebHost' | Where-Object { $_ -notmatch '_assets/' } | Select-Object -Last 25 | ForEach-Object { Say ("  " + $_.ToString().Trim()) }
$bad = $log | Select-String 'SyntaxError|Uncaught|net::ERR_|web\[ERROR\]|console'
if ($bad) { $bad | Select-Object -First 10 | ForEach-Object { Say ("  !! " + $_.ToString().Trim()) } } else { Say "  -- no SyntaxError/Uncaught/ERR_ in logcat" }
Say "=== E4 pipeline end ==="
