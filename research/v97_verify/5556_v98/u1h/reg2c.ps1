# u1h regression 2c: attempt to build a real queue (>=11 msgs then 2-5 more)
. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1h\helper.ps1

$sent = 0
$fail = 0
for ($i = 1; $i -le 11; $i++) {
  Assert-Focus "q-send-$i" | Out-Null
  $n = Get-Nodes (Dump "q10_pre$i")
  $et = $n | Where-Object { $_.class -match 'EditText' } | Select-Object -First 1
  if (-not $et) { Write-Output "  [$i] NO-EDITTEXT, stop"; break }
  $eb = Bnd $et.bounds
  A shell input tap $eb.Cx $eb.Cy | Out-Null
  Start-Sleep -Milliseconds 700
  $msg = "q$i"
  A shell input text $msg | Out-Null
  Start-Sleep -Milliseconds 900
  $n2 = Get-Nodes (Dump "q11_typed$i")
  $snd = $n2 | Where-Object { $_.'content-desc' -eq 'Send' } | Select-Object -First 1
  if ($snd) {
    $sb = Bnd $snd.bounds
    A shell input tap $sb.Cx $sb.Cy | Out-Null
    $sent++
  } else {
    $stp = $n2 | Where-Object { $_.'content-desc' -eq 'Stop' } | Select-Object -First 1
    if ($stp) { Write-Output "  [$i] btn=Stop (no Send) - text='$msg' left in composer" }
    else { Write-Output "  [$i] NO-SEND-AND-NO-STOP" }
    $fail++
  }
  Start-Sleep -Milliseconds 800
}
Write-Output "SENT=$sent  NOT_SENT=$fail"
Write-Output "pid=$(Get-Pidof) focus=$(Get-Focus)"
