# _f20_p6.ps1 - read isSecureContext inside OUR WebView (eval channel needs BuildConfig.DEBUG)
$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$D="emulator-6450"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web"
$Pkg='com.xiufeigo.opencodeshell.web.debug'
$Act='com.xiufeigo.opencodeshell.web.WebHostActivity'
function L { param($m) $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Output $s; Add-Content -Path "$OUT\_f20_p6.log" -Value $s -Encoding utf8 }

# WebHostActivity.kt:82 EXTRA_DEBUG_EVAL ; :857 if(!BuildConfig.DEBUG) return ; :863 Log.i(EVAL_TAG,...)
$js = "'ISEC='+window.isSecureContext+' ORIGIN='+location.origin+' HOST='+location.host+' PROTO='+location.protocol+' MEDIA='+!!navigator.mediaDevices+' GUM='+!!(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia)+' BARCODE='+(typeof BarcodeDetector!=='undefined')"

$dbg = (Resolve-Path "$OUT\..\deliver\opencode-shell-web.apk").Path
L "=== install DEBUG apk (eval channel only when BuildConfig.DEBUG) ==="
L ("debug apk md5=" + (Get-FileHash $dbg -Algorithm MD5).Hash.ToLower() + " size=" + (Get-Item $dbg).Length)
& $adb -s $D install -r -t $dbg 2>&1 | ForEach-Object { L "  install: $_" }

L "=== launch with oc_debug_eval ==="
& $adb -s $D shell am force-stop $Pkg | Out-Null
Start-Sleep -Seconds 1
& $adb -s $D logcat -c | Out-Null
& $adb -s $D shell am start -n "$Pkg/$Act" --es oc_debug_eval "$js" 2>&1 | ForEach-Object { L "  amstart: $_" }
Start-Sleep -Seconds 18
$ev = ((& $adb -s $D logcat -d 2>&1 | Select-String -Pattern 'OcWebHost/Eval|ISEC=') | ForEach-Object { $_.ToString() }) -join "`n"
L "=== eval 原文 ==="
if($ev.Trim()){ $ev -split "`n" | ForEach-Object { L "  $_" } } else { L "  (EMPTY)" }
$ev | Out-File -Encoding utf8 "$OUT\_f20_eval_isec.txt"
$lc = ((& $adb -s $D logcat -d -s OcWebHost:V OcPerf:V 2>&1) -join "`n")
$lc | Out-File -Encoding utf8 "$OUT\_f20_p6.logcat.txt"
L "=== precond ==="
$lc -split "`n" | Where-Object { $_ -match 'probe OK|passwordPresent|proxyListening|loadUrlIssued|webview version' } | Select-Object -First 6 | ForEach-Object { L "  $_" }
L "=== F20 P6 done ==="
