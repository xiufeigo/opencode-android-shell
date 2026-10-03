$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"
# close the diff overlay opened by the review panel toggle
Dump-UI "rp1" | Out-Null
Write-Output (TapDesc "Toggle review panel")
Start-Sleep -Seconds 2
Write-Output "--- measure 768 cell ---"
Write-Output "focus: $(Get-Focus)  pid: $(Get-Pid)  logcat: $(Test-CrashLog)  cnt: $(Get-CrashCount)"
Write-Output "ime: $(Get-IMEState)"
Dump-UI "m768" | Out-Null
Write-Output "TOTAL: $($script:N.Count)"
$tb = $null
foreach ($n in $script:N) {
  $b = $n.GetAttribute('bounds')
  $d = $n.GetAttribute('content-desc')
  if (-not $d) { $d = $n.GetAttribute('text') }
  if ($b -match '^\[0,162\]\[1366,(\d+)\]') { $tb = [int]$Matches[1]; Write-Output "  TIMELINE BOX $b  => h=$($tb-162)" }
  if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    $x1=[int]$Matches[1];$y1=[int]$Matches[2];$x2=[int]$Matches[3];$y2=[int]$Matches[4]
    if ($x1 -ge 1200 -and $y1 -ge 190 -and $y1 -le 260) {
      Write-Output ("  ZONE [{0}] {1} clk={2} h={3}" -f $d, $b, $n.GetAttribute('clickable'), ($y2-$y1))
    }
  }
  if ($d -and $d -match 'queued|Steer|Remove') { Write-Output ("  KW [{0}] {1} clk={2}" -f $d, $b, $n.GetAttribute('clickable')) }
}
if ($tb) { Write-Output "  READABLE(no queue bar) = $tb" }
Shot "E2ECHK5556u1f_g768_final" | Out-Null
