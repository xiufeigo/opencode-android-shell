# debug_leg.ps1 — 干净卸载（清 app data）→ 装 debug → 首启应弹口令框 → 播种 → 冷启动测量
$ErrorActionPreference='Continue'
$SDK="$env:LOCALAPPDATA\Android\Sdk"; $A="$SDK\platform-tools\adb.exe"
$Dev='emulator-5860'
$PKG='com.xiufeigo.opencodeshell.web.debug'; $ACT='com.xiufeigo.opencodeshell.web.WebHostActivity'
$APK='C:\sensorsdata\main\program\opencode-android-shell\research\deliver\opencode-shell-web.apk'

function A { & $A -s $Dev @args }
function Say { param($m) Write-Output ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }

Say "=== uninstall release (clears app data + SecretStore) ==="
A uninstall $PKG
Start-Sleep -Seconds 2
Say "=== confirm gone ==="
A shell pm list packages | Select-String $PKG
Say "=== install debug ==="
A install -r $APK 2>&1 | ForEach-Object { Write-Output "  $_" }
Start-Sleep -Seconds 2
Say "=== first launch: does the password gate fire on a clean SecretStore? ==="
A logcat -c | Out-Null
A shell am start -W -n "$PKG/$ACT" 2>&1 | Select-String "Status|TotalTime|LaunchState" | ForEach-Object { Write-Output "  $_" }
Start-Sleep -Seconds 6
A shell uiautomator dump /sdcard/_ui_gate.xml | Out-Null
$xml = (A shell cat /sdcard/_ui_gate.xml) -join ''
$texts = [regex]::Matches($xml,'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne "" }
Say "  non-empty text nodes = $($texts.Count)"
$texts | Select-Object -First 12 | ForEach-Object { Write-Output "  | $_" }
$hasEdit = $xml -match 'class="android\.widget\.EditText"'
Say "  EditText present = $hasEdit  => password gate $(if($hasEdit){'FIRED (as expected on clean SecretStore)'}else{'did NOT fire'})"
Say "=== logcat gate evidence ==="
A logcat -d -s OcWebHost:V 2>&1 | Select-String "SecretStore|password|credentials stored" | Select-Object -First 6 | ForEach-Object { Write-Output "  $($_.Line)" }
