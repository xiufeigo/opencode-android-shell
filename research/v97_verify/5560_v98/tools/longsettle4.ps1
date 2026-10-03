$ErrorActionPreference = 'Stop'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$root = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5560_v98"
$ev = "$root\ev4"
$probe = "$root\tools\probe4.ps1"
$pkg = "com.xiufeigo.opencodeshell"

function Get-Strip([string]$tag) {
  [xml]$doc = Get-Content (Join-Path $ev "$tag.dump.xml") -Raw -Encoding UTF8
  $res = @()
  foreach ($n in @($doc.SelectNodes("//node"))) {
    if ($n.clickable -ne "true") { continue }
    if ($n.bounds -notmatch '\[(\d+),(14\d\d)\]\[(\d+),(15\d\d)\]') { continue }
    $x1=[int]$Matches[1];$y1=[int]$Matches[2];$x2=[int]$Matches[3];$y2=[int]$Matches[4]
    if ($y1 -lt 1400) { continue }
    $res += [pscustomobject]@{ desc=$n.'content-desc'; x1=$x1;y1=$y1;x2=$x2;y2=$y2; cx=[int](($x1+$x2)/2); cy=[int](($y1+$y2)/2); w=($x2-$x1) }
  }
  return $res
}
function Sig([string]$tag) {
  $raw = Get-Content (Join-Path $ev "$tag.sig.txt") -Encoding UTF8
  $i=[array]::IndexOf($raw,"SIG_BEGIN"); $j=[array]::IndexOf($raw,"SIG_END")
  $raw[($i+1)..($j-1)] | Where-Object { $_.Trim() -ne "" }
}
function Cnt([string]$tag) {
  $raw = Get-Content (Join-Path $ev "$tag.sig.txt") -Encoding UTF8
  [int]((($raw | Where-Object { $_ -like "NODES_TOTAL=*" }) -split "=")[1])
}
# generous settle: 20s after session open, to rule out the known slow-first-interaction effect
function RestoreLong([string]$tag) {
  & $adb -s emulator-5560 shell am force-stop $pkg | Out-Null
  Start-Sleep -Seconds 2
  & $adb -s emulator-5560 shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
  Start-Sleep -Seconds 10
  & $adb -s emulator-5560 shell input tap 600 894
  Start-Sleep -Seconds 20
  & $probe -Tag $tag -SettleSec 1 | Out-Null
}

# G1: CONTROL (palette, rightmost) with long settle
Write-Host "##### G1 CONTROL palette, long settle #####"
RestoreLong "G1_pre"
$strip = Get-Strip "G1_pre"
$right = $strip | Sort-Object x1 | Select-Object -Last 1
Write-Host ("  palette center=({0},{1}) x1={2} w={3}" -f $right.cx,$right.cy,$right.x1,$right.w)
& $adb -s emulator-5560 shell input tap $right.cx $right.cy
Start-Sleep -Seconds 4
& $probe -Tag "G1_post" -SettleSec 1 | Out-Null
$d1 = Compare-Object (Sig "G1_pre") (Sig "G1_post")
Write-Host ("  G1 CONTROL pre={0} post={1} diff={2}" -f (Cnt "G1_pre"),(Cnt "G1_post"), $(if($null -ne $d1){($d1|Measure-Object).Count}else{0}))
if ($null -ne $d1) { $d1 | Select-Object -First 8 | ForEach-Object { Write-Host ("    {0} {1}" -f $_.SideIndicator,$_.InputObject) } }
# close the palette by tapping a neutral area high up (not ESC, not BACK)
& $adb -s emulator-5560 shell input tap 600 300
Start-Sleep -Seconds 2

# G2: SessionTab, same long settle
Write-Host "##### G2 SessionTab, long settle #####"
RestoreLong "G2_pre"
$strip = Get-Strip "G2_pre"
$tab = $strip | Where-Object { ($_.desc -eq $null -or $_.desc.Trim() -eq "") -and $_.w -gt 150 } | Select-Object -First 1
$close = $strip | Where-Object { $_.w -le 80 -and $_.x1 -gt $tab.x1 } | Select-Object -First 1
$safeX = [int]($tab.x1 + ($close.x1 - $tab.x1)/2)
Write-Host ("  SessionTab safeCenter=({0},{1})" -f $safeX,$tab.cy)
& $adb -s emulator-5560 shell input tap $safeX $tab.cy
Start-Sleep -Seconds 4
& $probe -Tag "G2_post" -SettleSec 1 | Out-Null
$d2 = Compare-Object (Sig "G2_pre") (Sig "G2_post")
Write-Host ("  G2 DRAWER  pre={0} post={1} diff={2}" -f (Cnt "G2_pre"),(Cnt "G2_post"), $(if($null -ne $d2){($d2|Measure-Object).Count}else{0}))
$rows = (Sig "G2_post") | Where-Object { $_ -match "\[.*\].*(TextView|Button)$" } | Measure-Object
Write-Host ("  G2 post labelled={0}" -f $rows.Count)
if ($null -ne $d2) { $d2 | Select-Object -First 8 | ForEach-Object { Write-Host ("    {0} {1}" -f $_.SideIndicator,$_.InputObject) } }
Write-Host "G-RUN DONE"
