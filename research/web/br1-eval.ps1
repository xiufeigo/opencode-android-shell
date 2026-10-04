# BR1 验收辅助：往 5636 上的 WebHostActivity 注入一段 JS（debug 构建的 oc_debug_eval 通道）
# 注意：JS 里**只能用双引号**，本脚本用单引号包住整段给远端 sh。
# 用法: .\br1-eval.ps1 "JSON.stringify({a:1})"
param([Parameter(Mandatory=$true)][string]$Js, [int]$WaitMs = 3000)

$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$d   = "emulator-5636"
$pkg = "com.xiufeigo.opencodeshell.web.debug"
$act = "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity"

if ($Js.Contains("'")) { throw "JS must not contain single quotes" }

& $adb -s $d logcat -c
$cmd = "am start -n $act --es oc_debug_eval '$Js'"
& $adb -s $d shell $cmd | Out-Null
Start-Sleep -Milliseconds $WaitMs
# logcat -s 不认带 '/' 的 tag，直接全量 grep
& $adb -s $d logcat -d | Select-String -Pattern 'OcWebHost/Eval:' | Select-Object -Last 4
