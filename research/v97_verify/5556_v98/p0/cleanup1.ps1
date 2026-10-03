$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"

Write-Output "=== 1. stop the running turn (enables delete) ==="
Dump-UI "cl0" | Out-Null
Write-Output (TapDesc "Stop")
Start-Sleep -Seconds 3
Write-Output "pid: $(Get-Pid)  logcat: $(Test-CrashLog)  cnt: $(Get-CrashCount)"

Write-Output "=== 2. wm reset (as instructed) ==="
A shell wm size reset
A shell wm density reset
Start-Sleep -Seconds 3
Write-Output "--- after reset ---"
A shell wm size
A shell wm density
Write-Output "pid: $(Get-Pid)  logcat: $(Test-CrashLog)  cnt: $(Get-CrashCount)"

Write-Output "=== 3. re-apply 1366x768 / 160 and verify ==="
A shell wm size 1366x768 | Out-Null
A shell wm density 160 | Out-Null
Start-Sleep -Seconds 4
Write-Output "--- verified ---"
A shell wm size
A shell wm density
Write-Output "focus: $(Get-Focus)"
Write-Output "pid: $(Get-Pid)"
Write-Output "logcat: $(Test-CrashLog)"
Write-Output "cnt: $(Get-CrashCount)"
