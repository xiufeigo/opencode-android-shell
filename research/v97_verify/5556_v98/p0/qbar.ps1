$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"
# close the review panel overlay (it covers x=1316..1364 where the queue bar lives)
Dump-UI "rp0" | Out-Null
Write-Output (TapDesc "Toggle review panel")
Start-Sleep -Seconds 2
Write-Output "pid: $(Get-Pid)  logcat: $(Test-CrashLog)"
Dump-UI "qbar" | Out-Null
Write-Output "TOTAL: $($script:N.Count)"
Write-Output "--- timeline box + queue bar zone ---"
foreach ($n in $script:N) {
  $b = $n.GetAttribute('bounds')
  $d = $n.GetAttribute('content-desc')
  if (-not $d) { $d = $n.GetAttribute('text') }
  if ($b -match '^\[0,162\]\[1366,') { Write-Output "  TIMELINE $b" }
  if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
    if ($x1 -ge 1150 -and $y1 -ge 190 -and $y1 -le 260) {
      Write-Output ("  ZONE [{0}] {1} clk={2} h={3}" -f $d, $b, $n.GetAttribute('clickable'), ($y2-$y1))
    }
  }
  if ($d -and $d -match 'queued|Queue|Steer|Remove') {
    Write-Output ("  KW [{0}] {1} clk={2}" -f $d, $b, $n.GetAttribute('clickable'))
  }
}
Shot "E2ECHK5556u1f_g768_queuebar" | Out-Null
