$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$log="C:\sensorsdata\main\program\opencode-android-shell\research\env\logs\oc_phoneC_boot.log"
$t0=Get-Date
for($i=0;$i -lt 140;$i++){
  Start-Sleep -Seconds 5
  $st = (& $adb -s emulator-5802 get-state 2>&1) -join ''
  $el=[int]((Get-Date)-$t0).TotalSeconds
  if($st -match 'device'){
    $bc = (& $adb -s emulator-5802 shell getprop sys.boot_completed 2>&1) -join ''
    "[{0}] +{1}s state=device boot_completed={2}" -f (Get-Date -Format HH:mm:ss),$el,$bc | Out-File $log -Append -Encoding UTF8
    if($bc -match '1'){ "[{0}] BOOT_COMPLETED after {1}s (watch start 18:41:41, process start ~18:41:45)" -f (Get-Date -Format HH:mm:ss),$el | Out-File $log -Append -Encoding UTF8; break }
  } elseif($i % 6 -eq 0){
    "[{0}] +{1}s state={2}" -f (Get-Date -Format HH:mm:ss),$el,$st | Out-File $log -Append -Encoding UTF8
  }
}
