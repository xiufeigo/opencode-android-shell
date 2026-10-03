# Build a queue in a self-created session (prefix E2ECHK5556u1f)
$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"
$T = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\tap.ps1"

# 1) New session
& $T -Match "New session" -Dump "q_new" 2>&1 | Out-String | Write-Output
Start-Sleep -Seconds 2

# 2) focus EditText and type
& $T -Match "Ask anything" -Dump "q_edit" 2>&1 | Out-String | Write-Output
Start-Sleep -Milliseconds 800

function Send-Msg([string]$msg) {
  # clear field then type
  A shell input tap 683 232 | Out-Null
  Start-Sleep -Milliseconds 300
  A shell input keyevent KEYCODE_MOVE_END | Out-Null
  A shell input text $msg | Out-Null
  Start-Sleep -Milliseconds 400
  # Send button coords from a fresh dump
  $f = Dump-UI "q_send"
  [xml]$x = Get-Content $f -Raw -Encoding utf8
  $nodes = @(); function Walk($n){ foreach($c in $n.ChildNodes){ if($c.Name -eq 'node'){ $script:nodes += $c; Walk $c } } }
  Walk $x.hierarchy.node
  $s = $nodes | Where-Object { $_.GetAttribute('content-desc') -eq 'Send' } | Select-Object -First 1
  if (-not $s) { "SEND NOT FOUND"; return $false }
  $b = $s.GetAttribute('bounds')
  if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    $cx = [int](([int]$Matches[1]+[int]$Matches[3])/2); $cy = [int](([int]$Matches[2]+[int]$Matches[4])/2)
    A shell input tap $cx $cy | Out-Null
    "sent '$msg' @ $cx,$cy"
  }
  Start-Sleep -Milliseconds 500
  return $true
}

# first message starts the turn
Send-Msg "E2ECHK5556u1f+shrinkshrink+Q01" | Out-String | Write-Output
Start-Sleep -Seconds 2

# queue up the rest rapidly
2..13 | ForEach-Object {
  $n = "{0:D2}" -f $_
  Send-Msg "E2ECHK5556u1f+shrinkshrink+Q$n" | Out-String | Write-Output
}
Start-Sleep -Seconds 2
Write-Output "=== queue dump ==="
Dump-UI "q_after" | Out-Null
Write-Output "pid: $(Get-Pid)"
Write-Output "logcat: $(Test-CrashLog)"
