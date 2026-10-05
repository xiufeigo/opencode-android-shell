$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6230"
$PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$APK="C:\sensorsdata\main\program\opencode-android-shell\research\deliver\opencode-shell-web-release-nor8.apk"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\perf\out_v11"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m; Add-Content -Path "$OUT\seed.log" -Value $s -Encoding utf8; Write-Output $s }

L "=== install ==="
L "INSTALL: $(A @('install','-r','-g',$APK))"
L "verify device-side md5: $(A @('shell','md5sum',(A @('shell','pm','path',$PKG) -replace '^package:','')))"
L "=== reverse (Lead backend untouched) ==="
L "REVERSE: $(A @('reverse','tcp:4095','tcp:4095'))"
L "REVERSE_LIST: $(A @('reverse','--list'))"
L "4095 host: $((Get-NetTCPConnection -LocalPort 4095 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { "LISTEN pid=$($_.OwningProcess)" }) -join ',')"

# --- clear stale SecretStore password, then seed the CORRECT one ---
L "=== pm clear (drops stale password) ==="
L "CLEAR: $(A @('shell','pm','clear',$PKG))"
L "LAUNCH: $(A @('shell','am','start','-n',$ACT))"
Start-Sleep -Seconds 7
A @('shell','uiautomator','dump','/sdcard/s1.xml') | Out-Null
$d = A @('shell','cat','/sdcard/s1.xml')
L "SETUP_DIALOG_PRESENT=$($d -match '配置')"
$et = [regex]::Matches($d,'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
L "EDITTEXT_COUNT=$($et.Count)"
if ($et.Count -ge 2) {
  $c1x=[int](($et[0].Groups[1].Value -as [int])+($et[0].Groups[3].Value -as [int]))/2
  $c1y=[int](($et[0].Groups[2].Value -as [int])+($et[0].Groups[4].Value -as [int]))/2
  L "PASS_FIELD tap=($c1x,$c1y)"
  A @('shell','input','tap',"$c1x","$c1y") | Out-Null
  Start-Sleep -Milliseconds 1500
  # INPUT FIDELITY (F5 lesson): per-character, one adb call each, with gap
  foreach ($ch in 'o','p','e','n','c','o','d','e') {
    A @('shell','input','text',$ch) | Out-Null
    Start-Sleep -Milliseconds 220
  }
  L "typed 8 chars one-by-one"
}
$b = [regex]::Match($d,'text="保存并连接"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if ($b.Success) {
  $bx=[int](($b.Groups[1].Value -as [int])+($b.Groups[3].Value -as [int]))/2
  $by=[int](($b.Groups[2].Value -as [int])+($b.Groups[4].Value -as [int]))/2
  L "SAVE tap=($bx,$by)"
  A @('shell','input','tap',"$bx","$by") | Out-Null
} else { L "SAVE_BTN_NOT_FOUND" }
Start-Sleep -Seconds 9
$log = A @('shell','logcat','-d','-s','OcWebHost','OcWebHost/Proxy')
L "SEED_LOG: $log"
$ok = $log -match 'probe OK code=200'
$bad = $log -match 'AUTH FAILED'
L "AUTH_OK=$ok AUTH_FAILED=$bad"
# how many API GETs actually landed
$gets = ([regex]::Matches($log,'GET http://127.0.0.1:4095/api/[^ ]*') | ForEach-Object { $_.Value } | Select-Object -Unique)
L "API_GETS_COUNT=$($gets.Count)"
foreach ($g in $gets) { L "  API_GET $g" }
L "PROXY_401=$(([regex]::Matches($log,'401')).Count)"

# --- CDP availability probe (empirical, not assumed) ---
$u = A @('shell','cat','/proc/net/unix')
$socks = ([regex]::Matches($u,'webview_devtools_remote_\d+') | ForEach-Object { $_.Value } | Select-Object -Unique)
L "DEVTOOLS_SOCKETS=$($socks -join ',')"
A @('shell','screencap','-p','/sdcard/seed.png') | Out-Null
A @('pull','/sdcard/seed.png',"$OUT\seed.png") | Out-Null
L "SEED_SCREENSHOT bytes=$((Get-Item "$OUT\seed.png" -ErrorAction SilentlyContinue).Length)"
L "=== seed done ==="
