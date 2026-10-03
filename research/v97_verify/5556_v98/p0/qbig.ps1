$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"

function ComposerXY {
  # dump, find EditText (or the Ask-anything placeholder View) -> center, runtime-parsed
  Dump-UI "cxy" | Out-Null
  $e = $script:N | Where-Object { $_.GetAttribute('class') -match 'EditText' } | Select-Object -First 1
  if (-not $e) { return $null }
  $b = $e.GetAttribute('bounds')
  if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    return @{ x = [int](([int]$Matches[1]+[int]$Matches[3])/2); y = [int](([int]$Matches[2]+[int]$Matches[4])/2) }
  }
  return $null
}

$xy = ComposerXY
if (-not $xy) { Write-Output "NO COMPOSER"; exit 5 }
Write-Output "composer center = $($xy.x),$($xy.y)"

# send N messages via ENTER while turn runs
2..14 | ForEach-Object {
  $n = "{0:D2}" -f $_
  A shell input tap $xy.x $xy.y | Out-Null
  Start-Sleep -Milliseconds 150
  A shell input text "E2ECHK5556u1f+shrinkshrink+Q$n" | Out-Null
  Start-Sleep -Milliseconds 200
  A shell input keyevent KEYCODE_ENTER | Out-Null
  Start-Sleep -Milliseconds 350
  if ($_ % 4 -eq 0) { Write-Output "  ...through Q$n" }
}
Start-Sleep -Seconds 2
Write-Output "pid: $(Get-Pid)  logcat: $(Test-CrashLog)"
Dump-UI "qbig" | Out-Null
Write-Output "TOTAL NODES: $($script:N.Count)"
Write-Output "--- queue / steer / stop nodes ---"
foreach($n in $script:N){
  $d=$n.GetAttribute('content-desc'); $t=$n.GetAttribute('text')
  foreach($v in @($d,$t)){ if($v -and $v -match 'queued|Queue|Steer|Stop|Working|Remove'){ "  [$v] $($n.GetAttribute('bounds')) clk=$($n.GetAttribute('clickable'))" } }
}
