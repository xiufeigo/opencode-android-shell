param([string]$Js)
$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S="emulator-6792"
& $adb -s $S logcat -c
$b64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Js))
& $adb -s $S shell am start --es oc_debug_eval "b64:$b64" -n com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity | Out-Null
Start-Sleep -Seconds 5
$raw = (& $adb -s $S logcat -d -s "OcWebHost/Eval:V" 2>$null) | Out-String
$m = [regex]::Match($raw, '->\s*"([\s\S]*?)"\s*$', 'Multiline')
if($m.Success){
  $b = $m.Groups[1].Value
  $b = $b -replace '\\n','' -replace '\\r',''
  try { $b = $b | ConvertFrom-Json } catch {}
  $b
} else { "NO_EVAL_RESULT"; ($raw -split "`n" | Select-Object -Last 4) }
