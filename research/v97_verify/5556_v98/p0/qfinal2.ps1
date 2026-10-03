$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"

Dump-UI "c0" | Out-Null
$e = $script:N | Where-Object { $_.GetAttribute('class') -match 'EditText' } | Select-Object -First 1
$b = $e.GetAttribute('bounds')
if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
  $cx = [int](([int]$Matches[1]+[int]$Matches[3])/2); $cy = [int](([int]$Matches[2]+[int]$Matches[4])/2)
} else { Write-Output "NOB"; exit 5 }
Write-Output "composer=$cx,$cy"

# start a turn
A shell input tap $cx $cy | Out-Null; Start-Sleep -Milliseconds 150
A shell input text "E2ECHK5556u1f+shrinkdeep+Z01" | Out-Null; Start-Sleep -Milliseconds 200
A shell input keyevent KEYCODE_ENTER | Out-Null; Start-Sleep -Milliseconds 800

# rapidly queue 12 more while it runs
2..13 | ForEach-Object {
  $n = "{0:D2}" -f $_
  A shell input tap $cx $cy | Out-Null; Start-Sleep -Milliseconds 100
  A shell input text "E2ECHK5556u1f+shrinkdeep+Z$n" | Out-Null; Start-Sleep -Milliseconds 150
  A shell input keyevent KEYCODE_ENTER | Out-Null; Start-Sleep -Milliseconds 200
}
Start-Sleep -Seconds 1
Write-Output "pid: $(Get-Pid)  logcat: $(Test-CrashLog)"

# raw grep of the dump for a queue-count label
Dump-UI "qq" | Out-Null
$raw = Get-Content "$EV\qq.xml" -Raw -Encoding utf8
Write-Output "--- 'queued' in raw xml: $([regex]::Matches($raw,'queued').Count) ---"
Write-Output "TOTAL: $($script:N.Count)"
foreach ($n in $script:N) {
  $d = $n.GetAttribute('content-desc')
  if (-not $d) { $d = $n.GetAttribute('text') }
  $bb = $n.GetAttribute('bounds')
  if ($d -and $d -match 'queued|Steer|Remove|Stop|Working') {
    Write-Output ("  [{0}] {1} clk={2}" -f $d, $bb, $n.GetAttribute('clickable'))
  }
  if ($bb -match '^\[0,162\]\[1366,(\d+)\]') { Write-Output "  TIMELINE h=$([int]$Matches[1]-162)" }
}
Shot "E2ECHK5556u1f_g768_q2" | Out-Null
