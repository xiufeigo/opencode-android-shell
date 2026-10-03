$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98"
. "$dir\d11_helper.ps1"

function FindRefresh() {
  $c = Get-Content "$dir\d11_probe_state.xml" -Raw
  $rx = $null; $ry = $null
  foreach ($m in [regex]::Matches($c, '<node[^>]*>')) {
    $s = $m.Value
    $cd = ""; if ($s -match 'content-desc="([^"]*)"') { $cd = $Matches[1] }
    $t = "";  if ($s -match 'text="([^"]*)"') { $t = $Matches[1] }
    if (($cd -eq "Refresh") -or ($t -eq "Refresh")) {
      if ($s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
        $rx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        $ry = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
      }
    }
  }
  return @($rx, $ry)
}

# current state
& $adb -s emulator-5556 shell uiautomator dump /sdcard/d11_dump.xml | Out-Null
& $adb -s emulator-5556 pull /sdcard/d11_dump.xml "$dir\d11_probe_state.xml" | Out-Null
$r = FindRefresh
if (-not $r[0]) { "Refresh NOT FOUND"; exit 1 }
"Refresh center = $($r[0]),$($r[1])  (parsed at runtime)"
Tap $r[0] $r[1]
"Refresh tapped T0"
$sw = [Diagnostics.Stopwatch]::StartNew()
$rows = @()
for ($i = 1; $i -le 26; $i++) {
  $xml = "$dir\d11_p$i.xml"
  & $adb -s emulator-5556 shell uiautomator dump /sdcard/d11_dump.xml | Out-Null
  & $adb -s emulator-5556 pull /sdcard/d11_dump.xml $xml | Out-Null
  $cc = Get-Content $xml -Raw
  $vals = @()
  $vals += [regex]::Matches($cc, 'text="([^"]*)"') | ForEach-Object { $_.Groups[1].Value }
  $vals += [regex]::Matches($cc, "text='([^']*)'") | ForEach-Object { $_.Groups[1].Value }
  $fake = if ($vals -contains "No uncommitted changes yet") { "FAKE-EMPTY" } else { "honest" }
  $fail = if ($vals -contains "The diff request timed out or failed. Tap Refresh to try again.") { "FAIL" } else { "-" }
  $load = if (($vals -match "Loading|loading")) { "loading" } else { "-" }
  $t = [int]$sw.Elapsed.TotalSeconds
  if ($fake -eq "FAKE-EMPTY") { Shot "p${i}_FAKE_T$t" | Out-Null }
  $state = "$fake|$fail|$load"
  $rows += [pscustomobject]@{ T = $t; Fake = $fake; Fail = $fail; Load = $load; State = $state }
  "T+{0,3}s fake={1,-11} fail={2,-5} load={3}" -f $t, $fake, $fail, $load
  if ($t -ge 32) { break }
  Start-Sleep -Milliseconds 1200
}
""
"=== TRANSITIONS ==="
$prev = ""
foreach ($r2 in $rows) {
  if ($r2.State -ne $prev) { "  --> T+$($r2.T)s : $($r2.State)"; $prev = $r2.State }
}
$ft = ($rows | Where-Object { $_.Fake -eq "FAKE-EMPTY" } | ForEach-Object { $_.T }) -join ","
"FAKE-EMPTY ticks at T+: ${ft:-none}"
$rows | Export-Csv "$dir\d11_timeline2.csv" -NoTypeInformation
