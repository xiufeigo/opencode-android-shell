$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"
# queue by typing + ENTER while the turn runs
2..4 | ForEach-Object {
  $n = "{0:D2}" -f $_
  A shell input tap 683 232 | Out-Null
  Start-Sleep -Milliseconds 200
  A shell input text "E2ECHK5556u1f+shrinkshrink+Q$n" | Out-Null
  Start-Sleep -Milliseconds 250
  A shell input keyevent KEYCODE_ENTER | Out-Null
  Start-Sleep -Milliseconds 500
  Write-Output "queued Q$n"
}
Start-Sleep -Seconds 2
Write-Output "pid: $(Get-Pid)  logcat: $(Test-CrashLog)"
Dump-UI "qchk" | Out-Null
Write-Output "--- queue-related nodes ---"
foreach($n in $script:N){
  $d=$n.GetAttribute('content-desc'); $t=$n.GetAttribute('text')
  foreach($v in @($d,$t)){ if($v -and $v -match 'queued|Queue|Steer|Stop|Working'){ "  $v | $($n.GetAttribute('bounds')) | clk=$($n.GetAttribute('clickable'))" } }
}
Write-Output "TOTAL NODES: $($script:N.Count)"
