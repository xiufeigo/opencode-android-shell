# E3 · run4 —— 5802 (swiftshader_indirect, zygote=running) 取证
$ErrorActionPreference = 'Continue'
$adb  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S    = "emulator-5802"
$PKG  = "com.xiufeigo.opencodeshell.web.debug"
$ACT  = "$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$apk  = "C:\sensorsdata\main\program\opencode-android-shell\research\deliver\opencode-shell-web.apk"
$out  = "C:\sensorsdata\main\program\opencode-android-shell\research\env\phone3_shots"
$L    = "C:\sensorsdata\main\program\opencode-android-shell\research\env\logs\phoneC_run4.log"
function Say($m) { $s = "[{0}] {1}" -f (Get-Date -Format HH:mm:ss), $m; Write-Host $s; Add-Content $L $s }
function Adb { & $adb -s $S @args }
function Shot($name) {
  Adb shell screencap -p /data/local/tmp/$name.png 2>&1 | Out-Null
  Adb pull /data/local/tmp/$name.png "$out\$name.png" 2>&1 | Out-Null
  $f = Get-Item "$out\$name.png" -ErrorAction SilentlyContinue
  if ($f -and $f.Length -gt 3000) { Say ("  shot {0} = {1} bytes" -f $name, $f.Length); return $true }
  Say ("  shot {0} = FAILED/absent" -f $name); return $false
}

Say "=== RUN4 start (relaunch 19:04:24, swiftshader_indirect) ==="
for ($i=0; $i -lt 100; $i++) {
  $bc = (Adb shell getprop sys.boot_completed 2>&1) -join ''
  $zy = (Adb shell getprop init.svc.zygote 2>&1) -join ''
  if ($bc -match '1') { Say ("BOOT_COMPLETED at {0} (+{1}s) zygote={2}" -f (Get-Date -Format HH:mm:ss), [int]((Get-Date)-(Get-Date '19:04:24')).TotalSeconds, $zy.Trim()); break }
  if ($i % 8 -eq 0) { Say ("  waiting +{0}s zygote={1} boot={2}" -f [int]((Get-Date)-(Get-Date '19:04:24')).TotalSeconds, $zy.Trim(), $bc.Trim()) }
  Start-Sleep -Seconds 5
}

Say "AMS HEALTH"
Say ("  service check activity: " + (((Adb shell service check activity) -join '').Trim()))
Say ("  zygote=" + (((Adb shell getprop init.svc.zygote) -join '').Trim()) + "  dev.bootcomplete=" + (((Adb shell getprop dev.bootcomplete) -join '').Trim()))
Shot 'PC5802_00_screencap_selftest'

Say "FINGERPRINT"
foreach ($p in 'ro.product.name','ro.build.version.sdk','ro.build.version.release') { Say ("  {0} = {1}" -f $p, ((Adb shell getprop $p) -join '').Trim()) }
Say ("  wm size: " + (((Adb shell wm size) -join '').Trim()))
Say ("  wm density: " + (((Adb shell wm density) -join '').Trim()))
Say ("  uptime: " + (((Adb shell cat /proc/uptime) -join '').Trim()))
Say ("  webview: " + (((Adb shell dumpsys webviewupdate | Select-String 'Current WebView package' | Select-Object -First 1) -join '').Trim()))

Say "INSTALL / REVERSE"
Say ("  install: " + (((Adb install -r -g $apk) -join ' ').Trim()))
Say ("  reverse: " + (((Adb reverse tcp:4095 tcp:4095) -join ' ').Trim()))
Adb shell settings put secure show_ime_with_hard_keyboard 1 | Out-Null
Say ("  show_ime readback: '" + (((Adb shell settings get secure show_ime_with_hard_keyboard) -join '').Trim()) + "'")
Say ("  pkg lastUpdateTime: " + (((Adb shell dumpsys package $PKG | Select-String 'lastUpdateTime' | Select-Object -First 1) -join '').Trim()))
Adb shell pm clear $PKG | Out-Null
Adb logcat -c
Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
Adb shell wm dismiss-keyguard | Out-Null

Say "RESOLVE + LAUNCH"
Say ("  resolve: " + (((Adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $PKG) -join ' ').Trim()))
Say ("  am start: " + ((((Adb shell am start -n $ACT) 2>&1) -join ' ').Trim()))
Start-Sleep -Seconds 10
Say ("  mCurrentFocus: " + (((Adb shell dumpsys window | Select-String 'mCurrentFocus' | Select-Object -First 1) -join '').Trim()))

function DumpUi($tag) {
  Adb shell uiautomator dump /data/local/tmp/$tag.xml | Out-Null
  Adb pull /data/local/tmp/$tag.xml "$out\PC5802_$tag.xml" | Out-Null
  $p = "$out\PC5802_$tag.xml"; if (Test-Path $p) { return (Get-Content $p -Raw) } else { return "" }
}
function B($xml, $rx) {
  $m = [regex]::Match($xml, $rx); if (-not $m.Success) { return $null }
  $b = [regex]::Match($m.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if ($b.Success) { return @([int]$b.Groups[1].Value,[int]$b.Groups[2].Value,[int]$b.Groups[3].Value,[int]$b.Groups[4].Value) }
  return $null
}

$xml = DumpUi 'dialog1'
Say ("STEP dialog dump len=" + $xml.Length)
$pass = B $xml '<node[^>]*class="android\.widget\.EditText"[^>]*password="true"[^>]*>'
if (-not $pass) { $pass = B $xml '<node[^>]*password="true"[^>]*>' }
$btn1 = B $xml '<node[^>]*resource-id="android:id/button1"[^>]*>'
$btn2 = B $xml '<node[^>]*resource-id="android:id/button2"[^>]*>'
if ($pass) { Say ("  password EditText = [{0},{1}][{2},{3}] size={4}x{5}px" -f $pass[0],$pass[1],$pass[2],$pass[3],($pass[2]-$pass[0]),($pass[3]-$pass[1])) } else { Say "  !! password field NOT found" }
if ($btn1) { Say ("  button1(save) = [{0},{1}][{2},{3}] size={4}x{5}px" -f $btn1[0],$btn1[1],$btn1[2],$btn1[3],($btn1[2]-$btn1[0]),($btn1[3]-$btn1[1])) }
if ($btn2) { Say ("  button2(skip) = [{0},{1}][{2},{3}] size={4}x{5}px" -f $btn2[0],$btn2[1],$btn2[2],$btn2[3],($btn2[2]-$btn2[0]),($btn2[3]-$btn2[1])) }
Shot 'PC5802_01_setup_dialog'

if ($pass) {
  Say "IME ASSERT + TYPE"
  Say ("  before: " + (((Adb shell dumpsys window insets | Select-String 'mInputShown|mShowRequested|mIsImeShowing') -join ' ; ').Trim()))
  Adb shell input tap ([int](($pass[0]+$pass[2])/2)) ([int](($pass[1]+$pass[3])/2)) | Out-Null
  Start-Sleep -Seconds 3
  Say ("  after:  " + (((Adb shell dumpsys window insets | Select-String 'mInputShown|mShowRequested|mShowExplicitlyRequested|mIsImeShowing') -join ' ; ').Trim()))
  Say ("  ime raw: " + (((Adb shell dumpsys window insets | Select-String 'ITYPE_IME|type=ime|frame=') -join ' ; ').Trim()))
  Adb shell input text opencode | Out-Null
  Start-Sleep -Seconds 2
  Shot 'PC5802_02_ime_typed'
  $xml2 = DumpUi 'dialog2'
  $btn1b = B $xml2 '<node[^>]*resource-id="android:id/button1"[^>]*>'
  if ($btn1b) { Say ("  button1 RE-DUMP = [{0},{1}][{2},{3}] (shifted {4}px)" -f $btn1b[0],$btn1b[1],$btn1b[2],$btn1b[3],($btn1b[1]-$btn1[1])) } else { $btn1b = $btn1 }
  Adb shell input tap ([int](($btn1b[0]+$btn1b[2])/2)) ([int](($btn1b[1]+$btn1b[3])/2)) | Out-Null
  Start-Sleep -Seconds 25
}

Say "OFFICIAL UI"
Shot 'PC5802_03_official_ui'
$xml3 = DumpUi 'ui'
Say ("  UI dump len=" + $xml3.Length)
[regex]::Matches($xml3,'<node[^>]*class="android\.webkit\.WebView"[^>]*>') | ForEach-Object { Say ("  " + $_.Value) }
Say "LOGCAT"
$log = Adb logcat -d -t 1500 2>&1
$log | Out-File "$out\PC5802_logcat.txt" -Encoding UTF8
$log | Select-String 'OcWebHost' | Where-Object { $_ -notmatch '_assets/' } | Select-Object -Last 20 | ForEach-Object { Say ("  " + $_.ToString().Trim()) }
$bad = $log | Select-String 'SyntaxError|Uncaught|web\[ERROR\]'
if ($bad) { $bad | Select-Object -First 6 | ForEach-Object { Say ("  !! " + $_.ToString().Trim()) } } else { Say "  !! NO SyntaxError / Uncaught in log" }
Say "=== RUN4 END ==="
