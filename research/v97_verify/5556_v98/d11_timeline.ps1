$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98"
. "$dir\d11_helper.ps1"

# Locate Refresh freshly from the current dump (runtime-parsed, per discipline)
$cur = "d11_d_ref_p30.xml"
$c = Get-Content "$dir\$cur" -Raw
$rfx = $null
foreach ($m in [regex]::Matches($c, '<node[^>]*>')) {
  $s = $m.Value
  $cd = ""
  if ($s -match 'content-desc="([^"]*)"') { $cd = $Matches[1] }
  $t = ""
  if ($s -match 'text="([^"]*)"') { $t = $Matches[1] }
  if (($cd -eq "Refresh") -or ($t -eq "Refresh")) {
    if ($s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
      $rfx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
      $rfy = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    }
  }
}
if (-not $rfx) { "Refresh node NOT FOUND - abort"; exit 1 }
"Refresh center parsed at runtime = $rfx,$rfy"

Tap $rfx $rfy
"Refresh tapped. T+0"
$sw = [Diagnostics.Stopwatch]::StartNew()
$rows = @()
for ($i = 1; $i -le 16; $i++) {
  Start-Sleep -Seconds 2
  $xml = "$dir\d11_tick$i.xml"
  & $adb -s emulator-5556 shell uiautomator dump /sdcard/d11_dump.xml | Out-Null
  & $adb -s emulator-5556 pull /sdcard/d11_dump.xml $xml | Out-Null
  $cc = Get-Content $xml -Raw
  $vals = @()
  $vals += [regex]::Matches($cc, 'text="([^"]*)"') | ForEach-Object { $_.Groups[1].Value }
  $vals += [regex]::Matches($cc, "text='([^']*)'") | ForEach-Object { $_.Groups[1].Value }
  $fake = if ($vals -contains "No uncommitted changes yet") { "FAKE-EMPTY" } else { "honest" }
  $fail = if ($vals -contains "The diff request timed out or failed. Tap Refresh to try again.") { "FAIL-BANNER" } else { "-" }
  $load = if (($vals -match "Loading|loading")) { "loading" } else { "-" }
  $t = [int]$sw.Elapsed.TotalSeconds
  $state = "$fake|$fail|$load"
  $rows += [pscustomobject]@{ T = $t; Fake = $fake; Fail = $fail; Load = $load; State = $state }
  "T+{0,3}s  fake={1,-11} fail={2,-12} load={3}" -f $t, $fake, $fail, $load
}
""
"=== TRANSITION SUMMARY ==="
$prev = ""
foreach ($r in $rows) {
  if ($r.State -ne $prev) { "  --> T+$($r.T)s : $($r.State)"; $prev = $r.State }
}
$fakeTimes = ($rows | Where-Object { $_.Fake -eq "FAKE-EMPTY" } | ForEach-Object { $_.T }) -join ", "
$rows | Export-Csv "$dir\d11_timeline.csv" -NoTypeInformation
"FAKE-EMPTY observed at T+: $fakeTimes"
