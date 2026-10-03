$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"

function SendOne([string]$msg, [string]$tag) {
  Dump-UI $tag | Out-Null
  $s = FindNode "Send"
  if (-not $s) { return "SEND-MISS" }
  $b = $s.GetAttribute('bounds')
  if ($b -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { return "BADB" }
  $cx = [int](([int]$Matches[1]+[int]$Matches[3])/2); $cy = [int](([int]$Matches[2]+[int]$Matches[4])/2)
  # tap EditText, clear, type
  A shell input tap 683 232 | Out-Null
  Start-Sleep -Milliseconds 250
  A shell input keyevent KEYCODE_MOVE_END | Out-Null
  A shell input text $msg | Out-Null
  Start-Sleep -Milliseconds 350
  A shell input tap $cx $cy | Out-Null
  Start-Sleep -Milliseconds 450
  return "sent '$msg' @ $cx,$cy"
}

Write-Output (SendOne "E2ECHK5556u1f+shrinkshrink+Q01" "s01")
Start-Sleep -Seconds 2
2..13 | ForEach-Object {
  $n = "{0:D2}" -f $_
  Write-Output (SendOne "E2ECHK5556u1f+shrinkshrink+Q$n" "s$n")
}
Start-Sleep -Seconds 2
Write-Output "pid: $(Get-Pid)  logcat: $(Test-CrashLog)  cnt: $(Get-CrashCount)"
