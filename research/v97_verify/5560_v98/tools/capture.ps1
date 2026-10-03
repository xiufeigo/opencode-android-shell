param(
  [Parameter(Mandatory=$true)][string]$Tag,
  [int]$SettleSec = 10
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$ev  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5560_v98\evidence"
New-Item -ItemType Directory -Force -Path $ev | Out-Null

if ($SettleSec -gt 0) { Start-Sleep -Seconds $SettleSec }

# --- uiautomator dump (full node tree, compressed default) ---
& $adb -s emulator-5560 shell uiautomator dump /sdcard/dump.xml | Out-Null
Start-Sleep -Milliseconds 400
$xml = Join-Path $ev "$Tag.dump.xml"
& $adb -s emulator-5560 pull /sdcard/dump.xml $xml | Out-Null

# --- screencap -p + pull (NO exec-out) ---
& $adb -s emulator-5560 shell screencap -p /sdcard/shot.png
Start-Sleep -Milliseconds 300
$png = Join-Path $ev "$Tag.png"
& $adb -s emulator-5560 pull /sdcard/shot.png $png | Out-Null

# --- parse ---
[xml]$doc = Get-Content $xml -Raw -Encoding UTF8
$nodes = $doc.SelectNodes("//node")
$withText = @($nodes | Where-Object { $_.text -and $_.text.Trim() -ne "" })
$desc    = @($nodes | Where-Object { $_.'content-desc' -and $_.'content-desc'.Trim() -ne "" })
Write-Host "=== $Tag ==="
Write-Host ("nodes_total   : " + $nodes.Count)
Write-Host ("nodes_text    : " + $withText.Count)
Write-Host ("nodes_desc    : " + $desc.Count)
Write-Host "--- non-empty text/desc nodes (bounds) ---"
$i = 0
foreach ($n in $nodes) {
  $t = $n.text; $d = $n.'content-desc'
  if ((($t -ne $null) -and $t.Trim() -ne "") -or (($d -ne $null) -and $d.Trim() -ne "")) {
    $i++
    $label = if (($t -ne $null) -and $t.Trim() -ne "") { $t } else { $d }
    Write-Host ("{0,3}. [{1}] {2}  cls={3}" -f $i, $n.bounds, $label.Trim(), ($n.class -replace '.*\.',''))
  }
}
Write-Host ("XML: " + $xml)
Write-Host ("PNG: " + $png)
