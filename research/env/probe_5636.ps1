# E5 窄屏几何调查辅助脚本（仅用于 emulator-5636）
# 用法: .\probe_5636.ps1 -Js '<js expression>' [-Settle 4]
# JS 内部字符串请用单引号，避免与设备 shell 的双引号冲突。
param(
  [Parameter(Mandatory = $true)][string]$Js,
  [int]$Settle = 4
)
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = 'emulator-5636'
$pkg = 'com.xiufeigo.opencodeshell.web.debug'
$act = "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity"

& $adb -s $serial logcat -c | Out-Null
# 用单引号包 JS（设备 shell 会剥掉外层单引号，JS 内部用双引号）
& $adb -s $serial shell am start -n $act --es oc_debug_eval "$Js" 2>&1 | Out-Null
Start-Sleep -Seconds $Settle
$lines = & $adb -s $serial logcat -d -s "OcWebHost/Eval:*" 2>&1
$hit = $lines | Select-String -Pattern "eval=" | Select-Object -Last 1
if ($hit) {
  # 去掉时间戳/PID 前缀，只留 payload
  $payload = ($hit -replace '^\s*\S+\s+\S+\s+\S+\s+I\s+OcWebHost/Eval:\s*eval=.*?->\s*', '')
  $payload
} else {
  Write-Output "NO_EVAL_RESULT"
}
