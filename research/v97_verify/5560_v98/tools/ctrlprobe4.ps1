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
function Restore([string]$tag) {
  & $adb -s emulator-5560 shell am force-stop $pkg | Out-Null
  Start-Sleep -Seconds 2
  & $adb -s emulator-5560 shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
  Start-Sleep -Seconds 7
  & $adb -s emulator-5560 shell input tap 600 894
  Start-Sleep -Seconds 4
  & $probe -Tag $tag -SettleSec 1 | Out-Null
}

# F1: CONTROL - rightmost strip element (the palette button). Proves taps DO land on the strip.
Write-Host "########## F1 CONTROL: rightmost strip element (palette) ##########"
Restore "F1_pre"
$strip = Get-Strip "F1_pre"
$right = $strip | Sort-Object x1 | Select-Object -Last 1
Write-Host ("  rightmost strip: x1={0} w={1} center=({2},{3})" -f $right.x1,$right.w,$right.cx,$right.cy)
& $adb -s emulator-5560 shell input tap $right.cx $right.cy
Start-Sleep -Seconds 2
& $probe -Tag "F1_post" -SettleSec 1 | Out-Null
$d = Compare-Object (Sig "F1_pre") (Sig "F1_post")
Write-Host ("  F1 pre={0} post={1} diff={2}" -f (Cnt "F1_pre"),(Cnt "F1_post"), $(if($null -ne $d){($d|Measure-Object).Count}else{0}))
if ($null -ne $d) { $d | Select-Object -First 10 | ForEach-Object { Write-Host ("    {0} {1}" -f $_.SideIndicator,$_.InputObject) } }

# F2: SessionTab left-of-close center (x1 + (closeX1-x1)/2) - avoids close button overlap
Write-Host "########## F2: SessionTab safe-area center (parsed) ##########"
Restore "F2_pre"
$strip = Get-Strip "F2_pre"
$tab = $strip | Where-Object { ($_.desc -eq $null -or $_.desc.Trim() -eq "") -and $_.w -gt 150 } | Select-Object -First 1
$close = $strip | Where-Object { $_.w -le 80 -and $_.x1 -gt $tab.x1 } | Select-Object -First 1
$safeX = [int]($tab.x1 + ($close.x1 - $tab.x1)/2)
Write-Host ("  SessionTab x1={0} closeX1={1} safeCenter=({2},{3})" -f $tab.x1,$close.x1,$safeX,$tab.cy)
& $adb -s emulator-5560 logcat -c
& $adb -s emulator-5560 shell input tap $safeX $tab.cy
Start-Sleep -Seconds 2
& $probe -Tag "F2_post" -SettleSec 1 | Out-Null
$d2 = Compare-Object (Sig "F2_pre") (Sig "F2_post")
Write-Host ("  F2 pre={0} post={1} diff={2}" -f (Cnt "F2_pre"),(Cnt "F2_post"), $(if($null -ne $d2){($d2|Measure-Object).Count}else{0}))
$err = (& $adb -s emulator-5560 logcat -d 2>&1 | Select-String -Pattern "FATAL|AndroidRuntime|ANR in")
Write-Host ("  F2 logcat FATAL/ANR count = {0}" -f @($err).Count)
$err | Out-File "$ev\F2_logcat_fatal.txt" -Encoding utf8
if ($null -ne $d2) { $d2 | Select-Object -First 10 | ForEach-Object { Write-Host ("    {0} {1}" -f $_.SideIndicator,$_.InputObject) } }
Write-Host "F-RUN DONE"
