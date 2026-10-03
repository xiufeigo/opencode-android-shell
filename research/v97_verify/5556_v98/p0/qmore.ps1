$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"
Dump-UI "cxy2" | Out-Null
$e = $script:N | Where-Object { $_.GetAttribute('class') -match 'EditText' } | Select-Object -First 1
$b = $e.GetAttribute('bounds')
if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
  $cx = [int](([int]$Matches[1]+[int]$Matches[3])/2); $cy = [int](([int]$Matches[2]+[int]$Matches[4])/2)
} else { Write-Output "NO BOUNDS"; exit 5 }
Write-Output "composer=$cx,$cy"
15..24 | ForEach-Object {
  $n = "{0:D2}" -f $_
  A shell input tap $cx $cy | Out-Null
  Start-Sleep -Milliseconds 120
  A shell input text "E2ECHK5556u1f+shrinkshrink+Q$n" | Out-Null
  Start-Sleep -Milliseconds 180
  A shell input keyevent KEYCODE_ENTER | Out-Null
  Start-Sleep -Milliseconds 280
}
Start-Sleep -Seconds 2
Write-Output "pid: $(Get-Pid)  logcat: $(Test-CrashLog)  cnt: $(Get-CrashCount)"
Dump-UI "qbig2" | Out-Null
Write-Output "TOTAL: $($script:N.Count)"
foreach($n in $script:N){
  $d=$n.GetAttribute('content-desc'); $t=$n.GetAttribute('text')
  foreach($v in @($d,$t)){ if($v -and $v -match 'queued|Queue|Steer|Stop|Remove|×'){ "  [$v] $($n.GetAttribute('bounds')) clk=$($n.GetAttribute('clickable'))" } }
}
Shot "E2ECHK5556u1f_g768_bigqueue"
