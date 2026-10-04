$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$log="C:\sensorsdata\main\program\opencode-android-shell\research\env\logs\oc_phoneC_boot.log"
"[{0}] BOOT start serial=emulator-5802" -f (Get-Date -Format HH:mm:ss) | Tee-Object -FilePath $log
Start-Process "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -ArgumentList `
 "-avd","OC_Phone_Test_C","-port","5802","-no-window","-no-audio","-no-boot-anim","-no-snapshot-load","-no-snapshot-save",`
 "-cores","2","-memory","2048","-gpu","swiftshader_indirect" `
 -RedirectStandardOutput "C:\sensorsdata\main\program\opencode-android-shell\research\env\logs\em5802.out.log" `
 -RedirectStandardError  "C:\sensorsdata\main\program\opencode-android-shell\research\env\logs\em5802.err.log"
$t0=Get-Date
for($i=0;$i -lt 120;$i++){
  Start-Sleep -Seconds 5
  $st = (& $adb -s emulator-5802 get-state 2>&1) -join ''
  $el=[int]((Get-Date)-$t0).TotalSeconds
  if($st -match 'device'){
    $bc = (& $adb -s emulator-5802 shell getprop sys.boot_completed 2>&1) -join ''
    "[{0}] +{1}s state=device boot_completed={2}" -f (Get-Date -Format HH:mm:ss),$el,$bc | Tee-Object -FilePath $log -Append
    if($bc -match '1'){ "[{0}] BOOT_COMPLETED after {1}s" -f (Get-Date -Format HH:mm:ss),$el | Tee-Object -FilePath $log -Append; break }
  } else {
    if($i % 6 -eq 0){ "[{0}] +{1}s state={2}" -f (Get-Date -Format HH:mm:ss),$el,$st | Tee-Object -FilePath $log -Append }
  }
}
