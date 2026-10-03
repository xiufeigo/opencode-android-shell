$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98"

function ReadState($xml) {
  $cc = Get-Content $xml -Raw
  $vals = @()
  $vals += [regex]::Matches($cc, 'text="([^"]*)"') | ForEach-Object { $_.Groups[1].Value }
  $vals += [regex]::Matches($cc, "text='([^']*)'") | ForEach-Object { $_.Groups[1].Value }
  $fake = if ($vals -contains "No uncommitted changes yet") { "FAKE" } else { "honest" }
  $fail = if ($vals -contains "The diff request timed out or failed. Tap Refresh to try again.") { "FAIL" } else { "-" }
  $load = if (($vals -match "Loading|loading")) { "load" } else { "-" }
  return "$fake|$fail|$load"
}

function FindRefresh($xml) {
  $c = Get-Content $xml -Raw
  foreach ($m in [regex]::Matches($c, '<node[^>]*>')) {
    $s = $m.Value
    $cd = ""; if ($s -match 'content-desc="([^"]*)"') { $cd = $Matches[1] }
    $t = "";  if ($s -match 'text="([^"]*)"') { $t = $Matches[1] }
    if (($cd -eq "Refresh") -or ($t -eq "Refresh")) {
      if ($s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
        return @([int](([int]$Matches[1] + [int]$Matches[3]) / 2), [int](([int]$Matches[2] + [int]$Matches[4]) / 2))
      }
    }
  }
  return $null
}

$all = @()
for ($cycle = 4; $cycle -le 6; $cycle++) {
  $st = "$dir\d11_c${cycle}_state.xml"
  & $adb -s emulator-5556 shell uiautomator dump /sdcard/d11_dump.xml | Out-Null
  & $adb -s emulator-5556 pull /sdcard/d11_dump.xml $st | Out-Null
  $r = FindRefresh $st
  if (-not $r) { "cycle ${cycle}: Refresh NOT FOUND"; continue }
  "=== cycle $cycle : Refresh at $($r[0]),$($r[1]) ==="
  & $adb -s emulator-5556 shell input tap $r[0] $r[1] | Out-Null
  $sw = [Diagnostics.Stopwatch]::StartNew()
  $seq = @()
  for ($i = 1; $i -le 20; $i++) {
    $xml = "$dir\d11_c${cycle}_t$i.xml"
    & $adb -s emulator-5556 shell uiautomator dump /sdcard/d11_dump.xml | Out-Null
    & $adb -s emulator-5556 pull /sdcard/d11_dump.xml $xml | Out-Null
    $stt = ReadState $xml
    $t = [int]$sw.Elapsed.TotalSeconds
    $seq += "$t=$stt"
    if ($stt -like "FAKE*") {
      & $adb -s emulator-5556 shell screencap -p "/sdcard/d11_c${cycle}_FAKE_T$t.png" | Out-Null
      & $adb -s emulator-5556 pull "/sdcard/d11_c${cycle}_FAKE_T$t.png" "$dir\d11_c${cycle}_FAKE_T$t.png" | Out-Null
    }
    $all += [pscustomobject]@{ Cycle = $cycle; T = $t; State = $stt }
    if ($t -ge 28) { break }
    Start-Sleep -Milliseconds 1000
  }
  ($seq -join "  ")
  if ($seq -match "FAKE") { ">>> cycle ${cycle}: FAKE-EMPTY REPRODUCED" } else { ">>> cycle ${cycle}: honest throughout" }
  ""
}
$all | Export-Csv "$dir\d11_cycles456.csv" -NoTypeInformation
$fakeCycles = ($all | Where-Object { $_.State -like "FAKE*" } | Select-Object -ExpandProperty Cycle -Unique) -join ","
"=== SUMMARY: cycles 4,5,6 fake-empty in cycles: ${fakeCycles:-none} ==="
"=== FINAL STATE NOW ==="
$fs = "$dir\d11_final_state.xml"
& $adb -s emulator-5556 shell uiautomator dump /sdcard/d11_dump.xml | Out-Null
& $adb -s emulator-5556 pull /sdcard/d11_dump.xml $fs | Out-Null
ReadState $fs
