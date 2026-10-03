. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"
# $args[0] = round number
$R = $args[0]
function Reading($gear) {
  "---- R$R $gear ----"
  "  pid      : $(Get-Pid)"
  "  focus    : $((A shell dumpsys window | Select-String 'mCurrentFocus') -join '')"
  "  logcat   : $(Test-CrashLog)"
  "  crashcnt : $(Get-CrashCount)"
}
# --- gear A: 1366x768 / 160 ---
A shell wm size 1366x768 | Out-Null
A shell wm density 160 | Out-Null
Start-Sleep -Seconds 3
Reading "A 1366x768 (settled)"
# --- gear B: shrink to 1366x600 ---
A shell wm size 1366x600 | Out-Null
A shell wm density 160 | Out-Null
Start-Sleep -Milliseconds 800
Reading "B 1366x600 t+0.8s (transient)"
Start-Sleep -Seconds 3
Reading "B2 1366x600 (settled)"
# --- gear C: back to 1366x768 ---
A shell wm size 1366x768 | Out-Null
A shell wm density 160 | Out-Null
Start-Sleep -Milliseconds 800
Reading "C 1366x768 t+0.8s (transient)"
Start-Sleep -Seconds 3
Reading "C2 1366x768 (settled)"
