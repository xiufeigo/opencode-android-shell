# E3 手机形态验证流水线 —— emulator-5802 (OC_Phone_Test_C, android-37.2 ps16k 手机镜像)
# 纪律：只操作 5802（自建）+ 5730（E2 自建）。不碰他人设备。
$ErrorActionPreference = 'Continue'
$adb  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S    = "emulator-5802"
$PKG  = "com.xiufeigo.opencodeshell.web.debug"
$ACT  = "$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$apk  = "C:\sensorsdata\main\program\opencode-android-shell\research\deliver\opencode-shell-web.apk"
$out  = "C:\sensorsdata\main\program\opencode-android-shell\research\env\phone3_shots"
$logs = "C:\sensorsdata\main\program\opencode-android-shell\research\env\logs"
$L    = "$logs\phoneC_pipeline.log"
New-Item -ItemType Directory -Force -Path $out, $logs | Out-Null
function Say($m) { $s = "[{0}] {1}" -f (Get-Date -Format HH:mm:ss), $m; Write-Host $s; Add-Content $L $s }
function Adb { & $adb -s $S @args }

Say "STEP1 fingerprint"
foreach ($p in 'ro.product.name','ro.product.model','ro.build.version.sdk','ro.build.version.release','ro.build.version.security_patch') {
  Say ("  {0} = {1}" -f $p, ((Adb shell getprop $p) -join '').Trim())
}
Say ("  wm size: " + (((Adb shell wm size) -join '') ).Trim())
Say ("  wm density: " + (((Adb shell wm density) -join '')).Trim())
Say ("  uptime: " + (((Adb shell cat /proc/uptime) -join '')).Trim())
Say ("  avd: " + (((Adb shell getprop ro.boot.qemu.avd_name) -join '')).Trim())

Say "STEP2 webview version (decides whether official UI can parse modern ESM)"
foreach ($p in 'com.android.webview','com.google.android.webview','com.google.android.trichromelibrary') {
  $v = (Adb shell dumpsys package $p 2>&1 | Select-String 'versionName' | Select-Object -First 1)
  if ($v) { Say ("  {0} -> {1}" -f $p, $v.ToString().Trim()) } else { Say ("  {0} -> not installed" -f $p) }
}

Say "STEP3 install + reverse + ime setting"
Say ("  install: " + (((Adb install -r -g $apk) -join ' ').Trim()))
Say ("  reverse: " + (((Adb reverse tcp:4095 tcp:4095) -join ' ').Trim()))
Say ("  reverse --list: " + (((Adb reverse --list) -join ' | ').Trim()))
Adb shell settings put secure show_ime_with_hard_keyboard 1 | Out-Null
Say ("  show_ime_with_hard_keyboard readback: '" + (((Adb shell settings get secure show_ime_with_hard_keyboard) -join '').Trim()) + "'")
Say ("  pkg lastUpdateTime: " + (((Adb shell dumpsys package $PKG | Select-String 'lastUpdateTime' | Select-Object -First 1) -join '').Trim()))
Say ("  pkg versionName: " + (((Adb shell dumpsys package $PKG | Select-String 'versionName' | Select-Object -First 1) -join '').Trim()))

# 强制走首启配置流：清数据保证弹窗出现
Adb shell pm clear $PKG | Out-Null
Say "  pm clear done (forces showSetupDialog)"
Adb logcat -c
Say "STEP4 launch"
Say ("  " + (((Adb shell am start -n $ACT) -join ' ').Trim()))
Start-Sleep -Seconds 8

function DumpUi($tag) {
  Adb shell uiautomator dump /sdcard/pc_$tag.xml | Out-Null
  Adb pull /sdcard/pc_$tag.xml "$out\PC5802_$tag.xml" | Out-Null
  return (Get-Content "$out\PC5802_$tag.xml" -Raw)
}
function Bounds($xml, $rx) {
  $m = [regex]::Match($xml, $rx)
  if ($m.Success) {
    $b = [regex]::Match($m.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($b.Success) { return @([int]$b.Groups[1].Value,[int]$b.Groups[2].Value,[int]$b.Groups[3].Value,[int]$b.Groups[4].Value) }
  }
  return $null
}

Say "STEP5 setup dialog: dump + locate password field (dynamic bounds)"
$xml = DumpUi 'dialog1'
$pass = Bounds $xml 'password="true"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"'
if (-not $pass) { $pass = Bounds $xml 'class="android\.widget\.EditText"[^>]*password="true"' }
if ($pass) { Say ("  password EditText bounds = [{0},{1}][{2},{3}]  center=({4},{5})" -f $pass[0],$pass[1],$pass[2],$pass[3],[int](($pass[0]+$pass[2])/2),[int](($pass[1]+$pass[3])/2)) } else { Say "  !! password field NOT found" }
$urlf = Bounds $xml 'class="android\.widget\.EditText"[^>]*password="false"'
if ($urlf) { Say ("  url EditText bounds      = [{0},{1}][{2},{3}]  text='" -f $urlf[0],$urlf[1],$urlf[2],$urlf[3]) }
if ($xml -match 'text="(http[^"]*)"') { Say ("  url field default = " + $Matches[1]) }
$btn1 = Bounds $xml 'resource-id="android:id/button1"'
$btn2 = Bounds $xml 'resource-id="android:id/button2"'
if ($btn1) { Say ("  button1(save)  = [{0},{1}][{2},{3}] size={4}x{5}px" -f $btn1[0],$btn1[1],$btn1[2],$btn1[3],($btn1[2]-$btn1[0]),($btn1[3]-$btn1[1])) }
if ($btn2) { Say ("  button2(skip)  = [{0},{1}][{2},{3}] size={4}x{5}px" -f $btn2[0],$btn2[1],$btn2[2],$btn2[3],($btn2[2]-$btn2[0]),($btn2[3]-$btn2[1])) }
Adb shell screencap -p /sdcard/pc_dialog1.png | Out-Null
Adb pull /sdcard/pc_dialog1.png "$out\PC5802_01_setup_dialog.png" | Out-Null

Say "STEP6 tap password field, assert IME state, type"
$pcx = [int](($pass[0]+$pass[2])/2); $pcy = [int](($pass[1]+$pass[3])/2)
Say ("  pre-tap insets mInputShown: " + (((Adb shell dumpsys window insets | Select-String 'mInputShown|mShowRequested|mIsImeShowing') -join ' ; ').Trim()))
Adb shell input tap $pcx $pcy | Out-Null
Start-Sleep -Seconds 3
Say ("  post-tap insets raw: " + (((Adb shell dumpsys window insets | Select-String 'mInputShown|mShowRequested|mIsImeShowing|mShowExplicitlyRequested') -join ' ; ').Trim()))
Say ("  post-tap IME insets source: " + (((Adb shell dumpsys window insets | Select-String 'ITYPE_IME|type=ime|ime\(') -join ' ; ').Trim()))
Adb shell input text opencode | Out-Null
Start-Sleep -Seconds 2
Adb shell screencap -p /sdcard/pc_ime.png | Out-Null
Adb pull /sdcard/pc_ime.png "$out\PC5802_02_ime_typed.png" | Out-Null

Say "STEP7 RE-dump (bounds jump lesson) and tap save"
$xml2 = DumpUi 'dialog2'
$btn1b = Bounds $xml2 'resource-id="android:id/button1"'
if (-not $btn1b) { Say "  !! button1 gone after IME; falling back to pre-IME bounds"; $btn1b = $btn }
Say ("  button1 now = [{0},{1}][{2},{3}]  (dialog moved {4}px)" -f $btn1b[0],$btn1b[1],$btn1b[2],$btn1b[3],($btn1b[1]-$btn[1]))
Adb shell input tap ([int](($btn1b[0]+$btn1b[2])/2)) ([int](($btn1b[1]+$btn1b[3])/2)) | Out-Null
Start-Sleep -Seconds 20

Say "STEP8 official UI on phone small screen"
Adb shell screencap -p /sdcard/pc_ui.png | Out-Null
Adb pull /sdcard/pc_ui.png "$out\PC5802_03_official_ui.png" | Out-Null
$xml3 = DumpUi 'ui'
Say ("  UI dump len=" + $xml3.Length)
$wc = [regex]::Match($xml3, 'class="android\.webkit\.WebView"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if ($wc.Success) { Say ("  WebView node bounds = " + $wc.Value) }
Save-Content "$logs\phoneC_webview_node.txt" $wc.Value
Say "STEP9 logcat key lines (-t + grep; --pid is false-negative on this host)"
$log = Adb logcat -d -t 800 2>&1
$log | Select-String 'OcWebHost' | Select-Object -Last 40 | ForEach-Object { Say ("  " + $_.ToString().Trim()) }
$err = $log | Select-String 'SyntaxError|Uncaught|SEVERE|E chromium|web\[ERROR\]'
if ($err) { $err | Select-Object -First 10 | ForEach-Object { Say ("  !! " + $_.ToString().Trim()) } } else { Say "  !! no JS SyntaxError/Uncaught found" }
Save-Content "$logs\phoneC_logcat.txt" ($log | Out-String)
Say "STEP10 DONE"
