param([string]$Js,[int]$Port=6732)
$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s "emulator-$Port" logcat -c 2>&1 | Out-Null
& $adb -s "emulator-$Port" shell am start -n com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity --es oc_debug_eval "$Js" 2>&1 | Out-Null
Start-Sleep -Seconds 3
& $adb -s "emulator-$Port" logcat -d -s "OcWebHost/Eval:*" 2>&1 | Select-Object -Last 30
