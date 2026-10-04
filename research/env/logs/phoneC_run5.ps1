# E3 · run5 —— 抓住 system_server 的"活窗口"，一有机会就把官方 UI 跑起来
$ErrorActionPreference = 'Continue'
$adb  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S    = "emulator-5802"
$PKG  = "com.xiufeigo.opencodeshell.web.debug"
$ACT  = "$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$apk  = "C:\sensorsdata\main\program\opencode-android-shell\research\deliver\opencode-shell-web.apk"
$out  = "C:\sensorsdata\main\program\opencode-android-shell\research\env\phone3_shots"
$L    = "C:\sensorsdata\main\program\opencode-android-shell\research\env\logs\phoneC_run5.log"
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
  Adb pull /data/local/tmp/$tag.xml "$out\PC5802_$tag.xml" | Out-Null
  $p = "$out\PC5802_$tag.xml"; if (Test-Path $p) { return (Get-Content $p -Raw) } else { return "" }
}

Say "=== RUN5: 抢 system_server 活窗口 (deadline 硬切) ==="
$deadline = (Get-Date).AddSeconds(150)
$won = $false
while ((Get-Date) -lt $deadline) {
  $act = (Adb shell service check activity 2>&1) -join ''
  $pkg = (Adb shell service check package 2>&1) -join ''
  if ($act -match 'found' -and $pkg -match 'found') {
    Say "WINDOW OPEN: activity+package both found -> acting NOW"
    $won = $true; break
  }
  Start-Sleep -Milliseconds 700
}
if (-not $won) { Say "!! never got a window where activity+package were both up (system_server restart loop)"; Say "=== RUN5 END (no window) ==="; exit }

Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
Say ("  reverse: " + ((((Adb reverse tcp:4095 tcp:4095) 2>&1) -join ' ').Trim()))
Adb shell settings put secure show_ime_with_hard_keyboard 1 2>&1 | Out-Null
Say ("  install: " + ((((Adb install -r -g $apk) 2>&1) -join ' ').Trim()))
Say ("  pm clear: " + ((((Adb shell pm clear $PKG) 2>&1) -join ' ').Trim()))
Adb logcat -c
Say ("  am start: " + ((((Adb shell am start -n $ACT) 2>&1) -join ' ').Trim()))
Start-Sleep -Seconds 8
$foc = Adb shell dumpsys window 2>&1 | Select-String 'mCurrentFocus' | Select-Object -First 1
Say ("  mCurrentFocus: " + (($foc | Out-String).Trim()))

$xml = DumpUi 'dialog1'
Say ("  dialog dump len=" + $xml.Length)
$pass = B $xml '<node[^>]*class="android\.widget\.EditText"[^>]*password="true"[^>]*>'
$btn1 = B $xml '<node[^>]*resource-id="android:id/button1"[^>]*>'
$btn2 = B $xml '<node[^>]*resource-id="android:id/button2"[^>]*>'
if ($pass) { Say ("  password EditText = [{0},{1}][{2},{3}] size={4}x{5}px" -f $pass[0],$pass[1],$pass[2],$pass[3],($pass[2]-$pass[0]),($pass[3]-$pass[1])) } else { Say "  !! password field not found" }
if ($btn1) { Say ("  button1 = [{0},{1}][{2},{3}] size={4}x{5}px" -f $btn1[0],$btn1[1],$btn1[2],$btn1[3],($btn1[2]-$btn1[0]),($btn1[3]-$btn1[1])) }
if ($btn2) { Say ("  button2 = [{0},{1}][{2},{3}] size={4}x{5}px" -f $btn2[0],$btn2[1],$btn2[2],$btn2[3],($btn2[2]-$btn2[0]),($btn2[3]-$btn2[1])) }

if ($pass) {
  $ib = Adb shell dumpsys window insets 2>&1 | Select-String 'mInputShown|mShowRequested|mIsImeShowing|ITYPE_IME|frame='
  Say ("  insets BEFORE: " + (($ib | Out-String).Trim()))
  Adb shell input tap ([int](($pass[0]+$pass[2])/2)) ([int](($pass[1]+$pass[3])/2)) | Out-Null
  Start-Sleep -Seconds 3
  $ia = Adb shell dumpsys window insets 2>&1 | Select-String 'mInputShown|mShowRequested|mShowExplicitlyRequested|mIsImeShowing|ITYPE_IME|frame='
  Say ("  insets AFTER:  " + (($ia | Out-String).Trim()))
  Adb shell input text opencode | Out-Null
  Start-Sleep -Seconds 2
  $xml2 = DumpUi 'dialog2'
  $btn1b = B $xml2 '<node[^>]*resource-id="android:id/button1"[^>]*>'
  if ($btn1b) { Say ("  button1 RE-DUMP = [{0},{1}][{2},{3}] (shifted {4}px)" -f $btn1b[0],$btn1b[1],$btn1b[2],$btn1b[3],($btn1b[1]-$btn1[1])) } else { $btn1b = $btn1 }
  Adb shell input tap ([int](($btn1b[0]+$btn1b[2])/2)) ([int](($btn1b[1]+$btn1b[3])/2)) | Out-Null
  Start-Sleep -Seconds 22
}

Say "OFFICIAL UI on phone small screen"
$xml3 = DumpUi 'ui'
Say ("  UI dump len=" + $xml3.Length)
$wv = [regex]::Matches($xml3,'<node[^>]*class="android\.webkit\.WebView"[^>]*>')
foreach ($m in $wv) { Say ("  " + $m.Value) }
# a11y 文本节点 = 官方 UI 元素（侧栏/会话/composer）
$txt = [regex]::Matches($xml3,'text="([^"]{2,60})"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -notmatch '^\s*$' } | Select-Object -Unique
Say ("  distinct visible texts (" + $txt.Count + "):")
$txt | Select-Object -First 40 | ForEach-Object { Say ("    | " + $_) }
$log = Adb logcat -d -t 1200 2>&1
$log | Out-File "$out\PC5802_logcat.txt" -Encoding UTF8
$log | Select-String 'OcWebHost' | Where-Object { $_ -notmatch '_assets/' } | Select-Object -Last 18 | ForEach-Object { Say ("  " + $_.ToString().Trim()) }
$bad = $log | Select-String 'SyntaxError|Uncaught'
if ($bad) { $bad | Select-Object -First 5 | ForEach-Object { Say ("  !! " + $_.ToString().Trim()) } } else { Say "  !! NO SyntaxError/Uncaught" }
Say "=== RUN5 END ==="
