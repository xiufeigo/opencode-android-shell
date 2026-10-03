$ErrorActionPreference = 'Continue'
$env:PATH = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools;" + $env:PATH
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$W = "$env:TEMP\e2echk5564"
$log = New-Object System.Collections.ArrayList

function DX {
  adb -s emulator-5564 shell "uiautomator dump --compressed /data/local/tmp/d.xml" 2>&1 | Out-Null
  Start-Sleep -Milliseconds 450
  $b64 = (adb -s emulator-5564 shell "base64 -w0 /data/local/tmp/d.xml" 2>&1) -join ''
  return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b64.Trim()))
}
function Items($xml) {
  $a = @()
  foreach ($m in [regex]::Matches($xml, '<node\b[^>]*>')) {
    $n = $m.Value; $t = $null
    if ($n -match 'text="([^"]*)"') { $t = $Matches[1] } elseif ($n -match "text='([^']*)'") { $t = $Matches[1] }
    $d = $null; if ($n -match 'content-desc="([^"]+)"') { $d = $Matches[1] }
    $ck = ($n -match 'clickable="true"')
    $x = -1; $y = -1
    if ($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
      $x = [int][math]::Floor(([int]$Matches[1] + [int]$Matches[3]) / 2)
      $y = [int][math]::Floor(([int]$Matches[2] + [int]$Matches[4]) / 2)
    }
    $l = if ($t -and $t.Trim()) { $t } elseif ($d) { $d } else { "" }
    if ($l) { $a += [pscustomobject]@{ L = $l; CK = $ck; X = $x; Y = $y } }
  }
  return , $a
}

for ($i = 0; $i -lt 6; $i++) {
  $items = Items (DX)
  $row = $items | Where-Object { $_.L -like 'E2ECHK5564p*' } | Select-Object -First 1
  if (-not $row) { [void]$log.Add("iter $i : no E2ECHK5564p* rows left"); break }
  $name = $row.L
  $kebab = $items | Where-Object { $_.L -eq '服务器操作' -and [math]::Abs($_.Y - $row.Y) -le 40 } | Select-Object -First 1
  if (-not $kebab) { [void]$log.Add("iter $i : no kebab for $name"); break }
  adb -s emulator-5564 shell input tap $kebab.X $kebab.Y
  Start-Sleep -Seconds 2
  $mi = Items (DX)
  $del = $mi | Where-Object { $_.L -match '^(删除|Remove|Delete)$' } | Select-Object -First 1
  if (-not $del) { [void]$log.Add("iter $i : menu had no 删除; got: " + (($mi | ForEach-Object { $_.L }) -join ' | ')); break }
  adb -s emulator-5564 shell input tap $del.X $del.Y
  Start-Sleep -Seconds 2
  $ci = Items (DX)
  $conf = $ci | Where-Object { $_.L -match '^(删除|Remove)$' } | Select-Object -First 1
  if ($conf) { adb -s emulator-5564 shell input tap $conf.X $conf.Y; Start-Sleep -Seconds 2 }
  [void]$log.Add("iter $i : requested delete of $name")
}
[void]$log.Add("--- remaining rows ---")
Items (DX) | ForEach-Object { [void]$log.Add("  ctr=($($_.X),$($_.Y)) t='$($_.L)'") }
[IO.File]::WriteAllLines("$W\cleanup2.txt", $log, (New-Object Text.UTF8Encoding($false)))
Write-Output "done"
