param([string]$Js, [string]$Dev="emulator-6790", [string]$Act="com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity", [int]$Settle=4)
$ErrorActionPreference='Continue'
$adb = "C:\Users\xiufeigo\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Js))
& $adb -s $Dev shell am start -n $Act --es oc_debug_eval "b64:$b64" | Out-Null
Start-Sleep -Seconds $Settle
& $adb -s $Dev logcat -d -t 2500 2>$null | Select-String "OcWebHost/Eval" | Select-Object -Last 1
