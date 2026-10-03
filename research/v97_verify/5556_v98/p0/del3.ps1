$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"

# Remove ONLY artifacts this round created (s*.png came from my SendOne helper; q_*/cxy*/tmpxml were pulled+rm'd)
Write-Output "=== remove my own s*.png residue ==="
A shell "rm -f /sdcard/s0.png /sdcard/s1.png /sdcard/s2.png /sdcard/s3.png /sdcard/s4.png /sdcard/s5.png /sdcard/s6.png /sdcard/s7.png /sdcard/s8.png /sdcard/s9.png /sdcard/s10.png /sdcard/s11.png /sdcard/s12.png /sdcard/s13.png /sdcard/s14.png /sdcard/s15.png /sdcard/s16.png /sdcard/s17.png /sdcard/s18.png /sdcard/s19.png"
Write-Output "=== re-scan sdcard ==="
A shell ls /sdcard/ | Select-String 'E2ECHK5556u1f|^s[0-9]|^q_|^cxy|^tmpxml' | Out-String | Write-Output
Write-Output "(scan end)"
Write-Output "=== session delete: re-enter drawer via tab strip session button ==="
Dump-UI "v0" | Out-Null
Write-Output "focus: $(Get-Focus)  pid: $(Get-Pid)"
$btns = @($script:N | Where-Object { $_.GetAttribute('content-desc') -eq 'Open sessions' })
Write-Output "Open sessions count: $($btns.Count)"
if ($btns.Count -gt 0) {
  $b = $btns[0].GetAttribute('bounds')
  if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    $x = [int](([int]$Matches[1]+[int]$Matches[3])/2); $y = [int](([int]$Matches[2]+[int]$Matches[4])/2)
    Write-Output "tap drawer @ $x,$y"
    A shell input tap $x $y | Out-Null; Start-Sleep -Seconds 2
  }
}
Dump-UI "v1" | Out-Null
$rows = @($script:N | Where-Object { $_.GetAttribute('text') -like 'E2ECHK5556u1f*' })
Write-Output "my rows: $($rows.Count)"
foreach ($r in $rows) { Write-Output "  $($r.GetAttribute('bounds'))" }
