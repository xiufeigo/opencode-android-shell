# u1h regression 1: shrink round trip, 2 rounds, with liveness + focus + logcat gate
. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1h\helper.ps1

$pkgPid0 = Get-Pidof
Write-Output "BASELINE pid=$pkgPid0 focus=$(Get-Focus)"

function Gate([string]$label) {
  $pidNow = Get-Pidof
  $f = (Get-Focus) + ''
  $raw = A shell logcat -d 2>&1 | Select-String 'FATAL EXCEPTION|IllegalArgumentException|maxHeight'
  $rawCount = @($raw).Count
  # split: lines whose pid == our app pid
  $appLines = @()
  $imeLines = @()
  foreach ($l in $raw) {
    $s = $l.Line
    if ($s -match '^\s*\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+\s+(\d+)\s') {
      $lp = $Matches[1]
      if ($lp -eq $pidNow) { $appLines += $s } else { $imeLines += $s }
    } else { $imeLines += $s }
  }
  $fatal = @($appLines | Select-String 'FATAL EXCEPTION')
  $illArg = @($appLines | Select-String 'IllegalArgumentException')
  $ok = ($f -match 'opencodeshell')
  Write-Output "[$label] wm=$(A shell wm size | Select-String Override) | pid=$pidNow (base=$pkgPid0) | focusOK=$ok | focus=$($f.Trim()) | rawFilterHits=$rawCount | appPidHits=$($appLines.Count) | appFATAL=$($fatal.Count) | appIllegalArgumentException=$($illArg.Count)"
  if ($rawCount -gt 0) {
    Write-Output "    sample noise: $(@($raw | Select-Object -First 1).Line)"
  }
}

# ensure start at 768
A shell wm size 1366x768 | Out-Null
A shell wm density 160 | Out-Null
Start-Sleep -Seconds 4
Assert-Focus "R0-768" | Out-Null
Gate "R0-768"

for ($r = 1; $r -le 2; $r++) {
  A shell wm size 1366x600 | Out-Null
  A shell wm density 160 | Out-Null
  Start-Sleep -Seconds 5
  Assert-Focus "R$r-600" | Out-Null
  Gate "R$r-SHRINK-600"

  A shell wm size 1366x768 | Out-Null
  A shell wm density 160 | Out-Null
  Start-Sleep -Seconds 5
  Assert-Focus "R$r-768" | Out-Null
  Gate "R$r-EXPAND-768"
}

Write-Output "--- dumpsys activity processes (crash check) ---"
$cr = A shell dumpsys activity processes 2>&1 | Select-String 'opencodeshell' -Context 0,6 | Out-String
Write-Output $cr
Write-Output "--- grep crashed ---"
A shell "dumpsys activity processes | grep -i 'crashed' | grep -i opencodeshell"
Write-Output "--- end ---"
