param(
  [Parameter(Mandatory=$true)][string]$Tag,
  [int]$SettleSec = 2
)
$ErrorActionPreference = 'Stop'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$ev  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5560_v98\ev4"
New-Item -ItemType Directory -Force -Path $ev | Out-Null

if ($SettleSec -gt 0) { Start-Sleep -Seconds $SettleSec }

& $adb -s emulator-5560 shell uiautomator dump /sdcard/p.xml | Out-Null
Start-Sleep -Milliseconds 350
$xml = Join-Path $ev "$Tag.dump.xml"
& $adb -s emulator-5560 pull /sdcard/p.xml $xml | Out-Null
& $adb -s emulator-5560 shell screencap -p /sdcard/p.png | Out-Null
Start-Sleep -Milliseconds 250
$png = Join-Path $ev "$Tag.png"
& $adb -s emulator-5560 pull /sdcard/p.png $png | Out-Null

[xml]$doc = Get-Content $xml -Raw -Encoding UTF8
$nodes = @($doc.SelectNodes("//node"))

$lines = @()
$lines += "TAG=$Tag"
$lines += "NODES_TOTAL=$($nodes.Count)"

# signature: every node carrying text or content-desc
$sig = @()
foreach ($n in $nodes) {
  $t = $n.text; $d = $n.'content-desc'
  $lab = if ($t -and $t.Trim() -ne "") { $t.Trim() } elseif ($d -and $d.Trim() -ne "") { $d.Trim() } else { $null }
  if ($lab) { $sig += ("{0}|{1}|{2}" -f $lab, $n.bounds, ($n.class -replace '.*\.','')) }
}
$lines += "NODES_LABELLED=$($sig.Count)"

# bottom strip geometry
$strip = @($nodes | Where-Object { $_.bounds -match '\[(\d+),14\d\d\]\[\d+,15\d\d\]' })
$lines += "BOTTOM_STRIP_NODES=$($strip.Count)"
foreach ($n in $strip) { $lines += "  STRIP $($n.bounds) text='$($n.text)' desc='$($n.'content-desc')' clickable=$($n.clickable)" }

# scrim detector: a clickable/none node covering >=85% of the 1200x1600 window
foreach ($n in $nodes) {
  if ($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    $x1=[int]$Matches[1];$y1=[int]$Matches[2];$x2=[int]$Matches[3];$y2=[int]$Matches[4]
    $w=$x2-$x1; $h=$y2-$y1
    if ($w -ge 1020 -and $h -ge 1360) { $lines += "  FULLSCREEN_NODE bounds=$($n.bounds) area=$($w*$h) text='$($n.text)' desc='$($n.'content-desc')'" }
  }
}
$lines += "SIG_BEGIN"
$lines += $sig
$lines += "SIG_END"
$lines | Out-File (Join-Path $ev "$Tag.sig.txt") -Encoding utf8
$lines | Select-Object -First 40
Write-Host "---- saved: $Tag.sig.txt / $Tag.png ----"
