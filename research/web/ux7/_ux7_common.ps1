# _ux7_common.ps1 - UX7 共用 adb helpers。用法: . .\ux7\_ux7_common.ps1 -D emulator-6630
param([string]$D = "emulator-6630")
$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Pkg='com.xiufeigo.opencodeshell.web.debug'
$Act='com.xiufeigo.opencodeshell.web.WebHostActivity'
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\ux7"
function A { & $adb -s $D @args }
function L { param($m) $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Output $s; Add-Content -Path "$OUT\_run_$D.log" -Value $s -Encoding utf8 }
function Dump { A shell uiautomator dump /sdcard/_ux7.xml | Out-Null; (A shell cat /sdcard/_ux7.xml) -join '' }
function FindB { param($X,$Pat) foreach($m in [regex]::Matches($X,'<node[^>]*')){ $n=$m.Value; if($n -match $Pat -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){ return @([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4]) } } return $null }
function Tap { param($b,[int]$ms=700) A shell input tap ([int](($b[0]+$b[2])/2)) ([int](($b[1]+$b[3])/2)) | Out-Null; Start-Sleep -Milliseconds $ms }
function Shot { param($name) A shell input keyevent KEYCODE_WAKEUP | Out-Null; Start-Sleep -Milliseconds 400; A shell screencap -p /sdcard/_ux7.png | Out-Null; A pull /sdcard/_ux7.png "$OUT\$name" 2>&1 | Select-Object -Last 1 | Out-Null; Write-Host "shot -> $name" }
# 宿主 DEBUG 注入通道：JS 进页面执行，结果打 logcat OcWebHost/Eval
function Eval { param($js) A logcat -c | Out-Null; A shell "am start -n $Pkg/$Act --es oc_debug_eval '$js'" | Out-Null; Start-Sleep -Seconds 2; (A logcat -d -s OcWebHost:V) -join "`n" }
function Key { param([string]$k,[int]$ms=300) A shell input keyevent $k | Out-Null; Start-Sleep -Milliseconds $ms }
function Type { param([string]$t,[int]$ms=400) A shell input text $t | Out-Null; Start-Sleep -Milliseconds $ms }
