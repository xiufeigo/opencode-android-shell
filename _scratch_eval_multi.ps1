<#
  UX14-P4 只读探查: 批量注入小工具（临时件，跑完删）。
  用法: pwsh -NoProfile -File _scratch_eval_multi.ps1 -ProbesFile probes.json
  probes.json: [{"label":"...","js":"...","wait":7}, ...]
  每次注入独立起一个 logcat 写文件进程夹日志（4173 空转会冲掉 ring buffer）。
#>
param(
  [Parameter(Mandatory = $true)][string]$ProbesFile,
  [string]$Cap = "_scratch_logcat.txt"
)

$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$d = "emulator-6790"
$pkg = "com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity"
$capPath = Join-Path (Get-Location) $Cap

$probes = Get-Content $ProbesFile -Raw | ConvertFrom-Json

function Invoke-OcEval {
  param([string]$Js, [int]$WaitSec, [string]$CapPath, [string]$Cap)
  $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Js))
  & $adb -s $d logcat -c 2>$null
  if (Test-Path $CapPath) { Remove-Item $CapPath -Force }
  $proc = Start-Process -FilePath $adb `
    -ArgumentList @('-s', $d, 'logcat', '-v', 'threadtime') `
    -RedirectStandardOutput $CapPath -RedirectStandardError "$Cap.err" -WindowStyle Hidden -PassThru
  Start-Sleep -Milliseconds 1200
  & $adb -s $d shell am start -n $pkg --es oc_debug_eval "b64:$b64" 2>&1 | Out-Null
  Start-Sleep -Seconds $WaitSec
  Stop-Process -Id $proc.Id -Force 2>$null
  Start-Sleep -Milliseconds 500
  $hits = Select-String -Path $CapPath -Pattern 'OcWebHost/Eval:' -ErrorAction SilentlyContinue
  if (-not $hits) { return "!!NO_EVAL_LINE" }
  $line = $hits[-1].Line
  if ($line -match '->\s*(.*)$') {
    $res = $Matches[1]
    $res = $res -replace '^"', '' -replace '"$', ''
    $res = $res -replace '\\"', '"' -replace '\\\\', '\'
    return $res
  }
  return $line
}

foreach ($p in $probes) {
  $wait = if ($p.PSObject.Properties.Name -contains 'wait') { [int]$p.wait } else { 7 }
  Write-Output "===== [$($p.label)] ====="
  Write-Output (Invoke-OcEval -Js $p.js -WaitSec $wait -CapPath $capPath -Cap $Cap)
  Write-Output ""
}
