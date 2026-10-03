param(
  [Parameter(Mandatory=$true)][string]$Tag,
  [int]$SettleSec = 3
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$ev  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5560_v98\ev4"
New-Item -ItemType Directory -Force -Path $ev | Out-Null

if ($SettleSec -gt 0) { Start-Sleep -Seconds $SettleSec }

& $adb -s emulator-5560 shell uiautomator dump /sdcard/dump4.xml | Out-Null
Start-Sleep -Milliseconds 400
$xml = Join-Path $ev "$Tag.dump.xml"
& $adb -s emulator-5560 pull /sdcard/dump4.xml $xml | Out-Null

& $adb -s emulator-5560 shell screencap -p /sdcard/shot4.png | Out-Null
Start-Sleep -Milliseconds 300
$png = Join-Path $ev "$Tag.png"
& $adb -s emulator-5560 pull /sdcard/shot4.png $png | Out-Null

[xml]$doc = Get-Content $xml -Raw -Encoding UTF8
$nodes = @($doc.SelectNodes("//node"))
$click = @($nodes | Where-Object { $_.clickable -eq "true" })
$sem = @($nodes | Where-Object { $_.'content-desc' -match 'mobile-drawer' -or $_.'resource-id' -match 'mobile-drawer' })
$report = Join-Path $ev "$Tag.nodes.txt"
$out = @()
$out += "=== $Tag ==="
$out += ("nodes_total    : " + $nodes.Count)
$out += ("nodes_clickable: " + $click.Count)
$out += ("drawer_semantic: " + $sem.Count)
$i = 0
foreach ($n in $click) {
  $i++
  $label = if ($n.'content-desc' -and $n.'content-desc'.Trim() -ne "") { $n.'content-desc' } else { $n.text }
  if ($label -eq $null) { $label = "<none>" }
  $b = $n.bounds -replace '[\[\]]',''
  $p = $b -split ','
  $cx = [int](($p[0] + $p[2]) / 2); $cy = [int](($p[1] + $p[3]) / 2)
  $out += ("{0,3}. {1,-24} c=({2},{3}) {4,-22} {5}" -f $i, $n.bounds, $cx, $cy, $label.Trim(), ($n.class -replace '.*\.',''))
}
$out += ("XML: " + $xml)
$out += ("PNG: " + $png)
$out | Out-File $report -Encoding utf8
Write-Host "=== $Tag ==="
Write-Host ("nodes_total    : " + $nodes.Count)
Write-Host ("nodes_clickable: " + $click.Count)
Write-Host ("drawer_semantic: " + $sem.Count)
Write-Host "--- clickable nodes (idx | bounds | desc/text | class) ---"
$i = 0
foreach ($n in $click) {
  $i++
  $label = if ($n.'content-desc' -and $n.'content-desc'.Trim() -ne "") { $n.'content-desc' } else { $n.text }
  if ($label -eq $null) { $label = "<none>" }
  Write-Host ("{0,3}. {1,-24} {2,-22} {3}" -f $i, $n.bounds, $label.Trim(), ($n.class -replace '.*\.',''))
}
Write-Host ("XML: " + $xml)
Write-Host ("PNG: " + $png)
