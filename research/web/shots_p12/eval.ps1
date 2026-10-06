# P12 read-only eval injection helper (injected probes are labeled and non-mutating)
param([Parameter(Mandatory=$true)][string]$Js)

$ErrorActionPreference='Stop'
$adb="C:\Users\xiufeigo\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$s="emulator-6790"
$P="com.xiufeigo.opencodeshell.web.debug"
$A="$P/com.xiufeigo.opencodeshell.web.WebHostActivity"

# encode JS to base64 for --es oc_debug_eval "b64:..."
$b64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Js))
$marker="P12_"+[guid]::NewGuid().ToString("N").Substring(0,8)

$full="console.log('$marker'+JSON.stringify((function(){try{return ($Js)}catch(e){return {__err:String(e)}}})()))"

$b64full=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($full))

& $adb -s $s logcat -c | Out-Null
& $adb -s $s shell am start -n $A --es oc_debug_eval "b64:$b64full" | Out-Null
Start-Sleep -Milliseconds 1400
& $adb -s $s logcat -d 2>&1 | Select-String $marker