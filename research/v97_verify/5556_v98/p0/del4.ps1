$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"
# remove last png residue from this round
A shell rm -f /sdcard/s20.png
Write-Output "=== /sdcard residue (this round) ==="
A shell ls /sdcard/ | Select-String 'E2ECHK5556u1f|^s[0-9]|^q_|^cxy|^tmpxml' | Out-String | Write-Output
Write-Output "(scan end - empty means clean)"
Write-Output "=== focus / pid / crash (read-only) ==="
Write-Output "focus: $(Get-Focus)"
Write-Output "pid: $(Get-Pid)"
Write-Output "logcat: $(Test-CrashLog)"
Write-Output "cnt: $(Get-CrashCount)"
Write-Output "=== wm verify ==="
A shell wm size
A shell wm density
