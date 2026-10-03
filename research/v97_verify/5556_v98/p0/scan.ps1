$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"
Write-Output "focus: $(Get-Focus)  pid: $(Get-Pid)  logcat: $(Test-CrashLog)  cnt: $(Get-CrashCount)"
Dump-UI "qscan" | Out-Null
Write-Output "TOTAL: $($script:N.Count)"
Write-Output "--- keyword scan ---"
foreach ($n in $script:N) {
  $d = $n.GetAttribute('content-desc'); $t = $n.GetAttribute('text')
  foreach ($v in @($d, $t)) {
    if ($v -and $v -match 'queued|Queue|Steer|Remove|Working|Stop') {
      Write-Output ("  [{0}] {1} clk={2}" -f $v, $n.GetAttribute('bounds'), $n.GetAttribute('clickable'))
    }
  }
}
Write-Output "--- timeline box (full width at y=162) ---"
foreach ($n in $script:N) {
  $b = $n.GetAttribute('bounds')
  if ($b -match '^\[0,162\]\[1366,') { Write-Output "  TIMELINE $b" }
}
Write-Output "--- right-side zone x>=1200, y in 190..260 (queue bar) ---"
foreach ($n in $script:N) {
  $b = $n.GetAttribute('bounds')
  if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]
    if ($x1 -ge 1200 -and $y1 -ge 190 -and $y1 -le 260) {
      Write-Output ("  [{0}] {1} clk={2}" -f $n.GetAttribute('content-desc'), $b, $n.GetAttribute('clickable'))
    }
  }
}
Shot "E2ECHK5556u1f_g768_scan" | Out-Null
