# boot5980.ps1 -- N-6 negative branch: android-30 image (WebView ~83) to exercise ShowDiagnostic
# Housekeeping: port 5980 (>=5940, even), only my OC_* AVD, no adb kill-server, no -a/wildcard.
$ErrorActionPreference = 'Continue'
$Emu = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$P   = $PSScriptRoot
$Out = "$P\out5980"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Log = "$Out\_boot.log"
"" | Out-File -Encoding utf8 $Log
function Say { param($m) $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m; Write-Host $s; $s | Out-File -Append -Encoding utf8 $Log }

$Port = 5980
$Avd  = 'OC_Preflight_WebView83'
$Dev  = "emulator-$Port"

$busy = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue).Count
if ($busy -gt 0) { Say "!! port $Port occupied"; exit 9 }

Say "=== boot AVD=$Avd port=$Port (android-30 => expect old WebView) ==="
Start-Process -FilePath $Emu -ArgumentList '-avd',$Avd,'-port',"$Port",'-no-snapshot-load','-no-boot-anim','-no-audio','-gpu','swiftshader_indirect','-no-metrics' `
  -RedirectStandardOutput "$Out\_emu_$Port.log" -RedirectStandardError "$Out\_emu_$Port.err" -WindowStyle Hidden
$sw = [Diagnostics.Stopwatch]::StartNew()
$boot = ''
while ($sw.Elapsed.TotalSeconds -lt 420) {
  $boot = ((& $Adb -s $Dev shell getprop sys.boot_completed 2>$null) | Out-String).Trim()
  if ($boot -eq '1') { break }
  Start-Sleep -Seconds 2
}
if ($boot -ne '1') { Say "!! BOOT TIMEOUT $([math]::Round($sw.Elapsed.TotalSeconds,1))s"; exit 2 }
Say ("  boot_completed=1 after {0:N1}s" -f $sw.Elapsed.TotalSeconds)
Start-Sleep -Seconds 5
& $Adb -s $Dev shell wm size 1080x2400 | Out-Null
& $Adb -s $Dev shell wm density 420 | Out-Null
Start-Sleep -Seconds 2
& $Adb -s $Dev reverse --remove-all 2>&1 | Out-Null
& $Adb -s $Dev reverse tcp:4095 tcp:4095 2>&1 | Out-Null

$fp = @()
$fp += "serial=$Dev avd=$Avd port=$Port"
$fp += "ro.build.version.sdk = " + (((& $Adb -s $Dev shell getprop ro.build.version.sdk) | Out-String).Trim())
$fp += "wm size: " + (((& $Adb -s $Dev shell wm size) | Out-String).Trim() -replace "`r?`n", ' / ')
$fp += "wm density: " + (((& $Adb -s $Dev shell wm density) | Out-String).Trim() -replace "`r?`n", ' / ')
$fp += "WebView provider packages:"
$d = (& $Adb -s $Dev shell pm list packages 2>&1 | Out-String)
foreach ($pkg in @('com.android.webview','com.google.android.webview','com.android.chrome')) {
  if ($d -match [regex]::Escape($pkg)) {
    $vn = (& $Adb -s $Dev shell dumpsys package $pkg 2>&1 | Select-String 'versionName' | Select-Object -First 1)
    $fp += "   $pkg -> " + (($vn | Out-String).Trim())
  }
}
$fp += "webview provider impl: " + (((& $Adb -s $Dev shell dumpsys webviewupdate 2>&1 | Select-String 'Current WebView package|versionName' | Select-Object -First 3) | Out-String).Trim() -replace "`r?`n", ' | ')
$fp -join "`n" | Out-File -Encoding utf8 "$Out\fingerprint.txt"
$fp | ForEach-Object { Say "  $_" }
Say "BOOT DONE"
