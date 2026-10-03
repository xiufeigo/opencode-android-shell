$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"

# ensure drawer open
Dump-UI "w0" | Out-Null
if (-not ($script:N | Where-Object { $_.GetAttribute('content-desc') -eq 'Close navigation menu' })) {
  Write-Output (TapDesc "Open sessions"); Start-Sleep -Seconds 2
}
# locate my row y from a fresh dump (runtime-parsed)
Dump-UI "w1" | Out-Null
$row = $script:N | Where-Object { $_.GetAttribute('text') -like 'E2ECHK5556u1f*' -and $_.GetAttribute('bounds') -match '^\[24,' } | Select-Object -First 1
if (-not $row) { Write-Output "ROW MISSING"; }
else {
  $b = $row.GetAttribute('bounds')
  Write-Output "row=$b"
  if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    $cx = [int](([int]$Matches[1]+[int]$Matches[3])/2); $cy = [int](([int]$Matches[2]+[int]$Matches[4])/2)
    Write-Output "long-press @ $cx,$cy"
    A shell input motionevent DOWN $cx $cy | Out-Null
    Start-Sleep -Milliseconds 1300
    A shell input motionevent UP $cx $cy | Out-Null
    Start-Sleep -Milliseconds 1500
    # immediately dump and tap Delete in the SAME flow (no long sleep)
    Dump-UI "w2" | Out-Null
    $del = $script:N | Where-Object { $_.GetAttribute('content-desc') -eq 'Delete' } | Select-Object -First 1
    if ($del) {
      $db = $del.GetAttribute('bounds')
      Write-Output "Delete node @$db clk=$($del.GetAttribute('clickable')) en=$($del.GetAttribute('enabled'))"
      if ($db -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $dx = [int](([int]$Matches[1]+[int]$Matches[3])/2); $dy = [int](([int]$Matches[2]+[int]$Matches[4])/2)
        Write-Output "tap Delete @ $dx,$dy"
        A shell input tap $dx $dy | Out-Null
        Start-Sleep -Seconds 2
        Dump-UI "w3" | Out-Null
        Write-Output "--- after Delete tap ---"
        foreach ($n in $script:N) {
          $d = $n.GetAttribute('content-desc'); if (-not $d) { $d = $n.GetAttribute('text') }
          if ($d -and $d -match 'Delete|Cancel|Are you|Remove') {
            Write-Output ("  [{0}] {1} clk={2} en={3}" -f $d, $n.GetAttribute('bounds'), $n.GetAttribute('clickable'), $n.GetAttribute('enabled'))
          }
        }
        Shot "E2ECHK5556u1f_delconfirm" | Out-Null
      }
    } else {
      Write-Output "DELETE NOT IN TREE after long-press"
    }
  }
}
Write-Output "pid: $(Get-Pid)  logcat: $(Test-CrashLog)  cnt: $(Get-CrashCount)"
Write-Output "=== /sdcard residue check ==="
A shell ls /sdcard/ | Select-String 'E2ECHK5556u1f|q_|s0|s1|zz|cxy|tmpxml|u1f|p0' | Out-String | Write-Output
Write-Output "(end sdcard scan)"
