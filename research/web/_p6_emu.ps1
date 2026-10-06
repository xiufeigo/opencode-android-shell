$ErrorActionPreference='Continue'
$adb = "C:\Users\xiufeigo\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$s   = "emulator-6790"
$enc = [System.Text.Encoding]::UTF8
$SH  = "D:\XiaoZhuTouTou\opencode-android-shell\research\web"
# NOTE: never embed PowerShell pipes inside the device-shell string; filter locally.
function Sh { param([string]$c) (& $adb -s $s shell $c) }
function Fil { param([string]$c,[string]$p) ((& $adb -s $s shell $c) | Select-String -Pattern $p | ForEach-Object { $_.Line }) }
function Lg { param([string]$p) (& $adb -s $s logcat -d) | Select-String -Pattern $p | ForEach-Object { $_.Line } }
function Shot { param([string]$n) & $adb -s $s exec-out screencap -p > "$SH\$n.png" }
function Tap { param([int]$x,[int]$y) & $adb -s $s shell input tap $x $y; Start-Sleep -Milliseconds 700 }
function TypeText { param([string]$t) & $adb -s $s shell input text ($t -replace ' ','%s'); Start-Sleep -Milliseconds 400 }
function Back { & $adb -s $s shell input keyevent 4; Start-Sleep -Milliseconds 500 }
# eval JS (read-only probe) -> result appears in logcat under OcWebHost/Eval
function Js {
  param([string]$code)
  $b64 = [Convert]::ToBase64String($enc.GetBytes($code))
  & $adb -s $s shell "am start -n com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity --es oc_debug_eval b64:$b64" | Out-Null
  Start-Sleep -Milliseconds 1000
  (& $adb -s $s logcat -d -t 500) | Select-String -Pattern 'OcWebHost/Eval' | Select-Object -Last 2 | ForEach-Object { $_.Line }
}
function Home { & $adb -s $s shell input keyevent 3; Start-Sleep -Milliseconds 500 }
