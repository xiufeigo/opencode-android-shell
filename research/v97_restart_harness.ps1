# Restart the DeepSeek Harness app so the edited cordis.patch.yml allowlist
# (minimax-cn subagent routes) is loaded. Launched detached via Win32_Process
# so it survives the death of the harness process itself.
$ErrorActionPreference = 'Continue'
$log = Join-Path $env:TEMP 'dsh_restart.log'
function Log([string]$m) { "$(Get-Date -Format o) $m" | Out-File -Encoding utf8 -Append $log }

Log 'begin; sleeping 12s before kill'
Start-Sleep -Seconds 12

$target = Get-Process -Id 28012 -ErrorAction SilentlyContinue
if ($target) {
  Log "stopping pid 28012 ($($target.ProcessName))"
  Stop-Process -Id 28012 -Force -ErrorAction SilentlyContinue
} else {
  Log 'pid 28012 not found; nothing to stop'
}

Start-Sleep -Seconds 12

$exe = 'C:\Users\xiufe\AppData\Local\Programs\DeepSeek Harness\DeepSeek Harness.exe'
$args = @(
  '--expose-internals',
  'C:\Users\xiufe\AppData\Local\Programs\DeepSeek Harness\resources\app.asar\dsh\node_modules\@deepseek-ai\dsh-desktop-host\lib\index.js',
  'C:\Users\xiufe\AppData\Local\Programs\DeepSeek Harness\resources\app.asar\dsh',
  'C:\Users\xiufe\.dsh\profiles\desktop',
  'C:\Users\xiufe\AppData\Local\Programs\DeepSeek Harness\resources\runtime\primary-runtime',
  'C:\Users\xiufe\AppData\Local\Programs\DeepSeek Harness\resources\runtime\pnpm\bin\pnpm.mjs',
  'C:\Users\xiufe\AppData\Local\Programs\DeepSeek Harness\resources\runtime\bin'
)
try {
  Start-Process -FilePath $exe -ArgumentList $args
  Log 'relaunch issued'
} catch {
  Log "relaunch FAILED: $($_.Exception.Message)"
}

Start-Sleep -Seconds 30
$p = Get-Process -Name 'DeepSeek Harness' -ErrorAction SilentlyContinue
if ($p) { Log "harness up: pid $($p.Id -join ',')" } else { Log 'harness NOT running after relaunch' }
$listen = Get-NetTCPConnection -LocalPort 19387 -State Listen -ErrorAction SilentlyContinue
if ($listen) { Log "web server listening on 19387 (pid $($listen.OwningProcess -join ','))" } else { Log 'port 19387 not listening yet' }
