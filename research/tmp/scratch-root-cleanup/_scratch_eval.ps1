<#
  UX14-P4 只读探查用的调试注入小工具（临时件，跑完删）。
  用法: pwsh -NoProfile -File _scratch_eval.ps1 -Js "<js>" [-WaitSec 6]

  机制: am start --es oc_debug_eval "b64:<base64(utf8 js)>"，结果在 logcat 的 OcWebHost/Eval。

  ⚠ 关键教训（本轮踩到）: 4173 的 event stream 每 ~1s 空转一次、每次刷 ~6 行 logcat，
    Android ring buffer 只有 100 行级别 ⇒ 直接 `logcat -d` 读结果会被冲掉，表现为"注入没回显"。
    解法: 先起一个**持续写文件**的 logcat 进程夹住日志，注入后再停。
#>
param(
  [Parameter(Mandatory = $true)][string]$Js,
  [int]$WaitSec = 6,
  [string]$Cap = "_scratch_logcat.txt"
)

$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$d = "emulator-6790"
# applicationId 带 .debug 后缀，但类名仍是 com.xiufeigo.opencodeshell.web.WebHostActivity
$pkg = "com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity"
$capPath = Join-Path (Get-Location) $Cap

$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Js))

& $adb -s $d logcat -c 2>$null
if (Test-Path $capPath) { Remove-Item $capPath -Force }

$proc = Start-Process -FilePath $adb `
  -ArgumentList @('-s', $d, 'logcat', '-v', 'threadtime') `
  -RedirectStandardOutput $capPath -RedirectStandardError "$Cap.err" -WindowStyle Hidden -PassThru

Start-Sleep -Milliseconds 1500
$start = & $adb -s $d shell am start -n $pkg --es oc_debug_eval "b64:$b64" 2>&1
Start-Sleep -Seconds $WaitSec
Stop-Process -Id $proc.Id -Force 2>$null
Start-Sleep -Milliseconds 600

if ($start -match 'Error') { Write-Output "AM_START_ERR: $start" }

$hits = Select-String -Path $capPath -Pattern 'OcWebHost/Eval:' -ErrorAction SilentlyContinue
if (-not $hits) {
  Write-Output "!! NO Eval LINE (captured $((Get-Content $capPath -EA SilentlyContinue | Measure-Object).Count) log lines)"
} else {
  $line = $hits[-1].Line
  if ($line -match '->\s*(.*)$') {
    $res = $Matches[1]
    # logcat 里 JSON 字符串是带引号转义的，解一层便于阅读
    $res = $res -replace '^"', '' -replace '"$', ''
    $res = $res -replace '\\"', '"' -replace '\\\\', '\'
    Write-Output $res
  } else { Write-Output $line }
}
