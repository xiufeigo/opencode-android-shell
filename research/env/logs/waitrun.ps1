$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$log="C:\sensorsdata\main\program\opencode-android-shell\research\env\logs\bootC3.log"
$t0=Get-Date
for($i=0;$i -lt 100;$i++){
  $st=(& $adb -s emulator-5802 get-state 2>&1) -join ''
  if($st -match 'device'){
    $bc=(& $adb -s emulator-5802 shell getprop sys.boot_completed 2>&1) -join ''
    "[{0}] state=device boot_completed={1} (+{2}s since 18:41:42)" -f (Get-Date -Format HH:mm:ss),$bc.Trim(),[int]((Get-Date)-(Get-Date '18:41:42')).TotalSeconds | Out-File $log -Append -Encoding UTF8
    if($bc -match '1'){ "BOOT_COMPLETED" | Out-File $log -Append -Encoding UTF8; break }
  } else {
    "[{0}] {1}" -f (Get-Date -Format HH:mm:ss),$st | Out-File $log -Append -Encoding UTF8
  }
  Start-Sleep -Seconds 5
}
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s emulator-5802 shell getprop sys.boot_completed
pwsh -NoProfile -File "C:\sensorsdata\main\program\opencode-android-shell\research\env\logs\phoneC_pipeline.ps1"
