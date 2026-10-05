# _f20_p7.ps1 - read isSecureContext inside OUR WebView (fix: escape quotes for device shell)
$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$D="emulator-6450"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web"
$Pkg='com.xiufeigo.opencodeshell.web.debug'
$Act='com.xiufeigo.opencodeshell.web.WebHostActivity'
function L { param($m) $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Output $s; Add-Content -Path "$OUT\_f20_p7.log" -Value $s -Encoding utf8 }

# JS uses double-quoted literals; backslash-escape them so the DEVICE shell keeps the quotes.
$jsRaw = '"ISEC="+window.isSecureContext+" ORIGIN="+location.origin+" HOST="+location.host+" PROTO="+location.protocol+" MEDIA="+(!!navigator.mediaDevices)+" GUM="+(!!(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia))+" BARCODE="+(typeof BarcodeDetector!=="undefined")'
$jsEsc = $jsRaw.Replace('"','\"')
L "jsEsc = $jsEsc"

& $adb -s $D shell am force-stop $Pkg | Out-Null
Start-Sleep -Seconds 1
& $adb -s $D logcat -c | Out-Null
& $adb -s $D shell am start -n "$Pkg/$Act" --es oc_debug_eval $jsEsc 2>&1 | ForEach-Object { L "  amstart: $_" }
Start-Sleep -Seconds 18
$ev = ((& $adb -s $D logcat -d 2>&1 | Select-String -Pattern 'OcWebHost/Eval') | ForEach-Object { $_.ToString() }) -join "`n"
L "=== eval 原文 ==="
if($ev.Trim()){ $ev -split "`n" | ForEach-Object { L "  $_" } } else { L "  (EMPTY)" }
$ev | Out-File -Encoding utf8 "$OUT\_f20_eval_isec.txt"
$lc = ((& $adb -s $D logcat -d -s OcWebHost:V OcPerf:V 2>&1) -join "`n")
$lc | Out-File -Encoding utf8 "$OUT\_f20_p7.logcat.txt"
L "=== precond ==="
$lc -split "`n" | Where-Object { $_ -match 'probe OK|passwordPresent|proxyListening|webview version' } | Select-Object -First 5 | ForEach-Object { L "  $_" }
L "=== F20 P7 done ==="
