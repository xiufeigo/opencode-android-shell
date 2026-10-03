$ErrorActionPreference = 'Stop'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$root = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5560_v98"
$ev = "$root\ev4"
$probe = "$root\tools\probe4.ps1"
$pkg = "com.xiufeigo.opencodeshell"

# Parse the strip from a dump: returns list of @{desc;x1;y1;x2;y2;cx;cy;w}
function Get-Strip([string]$tag) {
  $p = Join-Path $ev "$tag.dump.xml"
  [xml]$doc = Get-Content $p -Raw -Encoding UTF8
  $res = @()
  foreach ($n in @($doc.SelectNodes("//node"))) {
    if ($n.clickable -ne "true") { continue }
    if ($n.bounds -notmatch '\[(\d+),(14\d\d)\]\[(\d+),(15\d\d)\]') { continue }
    $x1=[int]$Matches[1];$y1=[int]$Matches[2];$x2=[int]$Matches[3];$y2=[int]$Matches[4]
    if ($y1 -lt 1400) { continue }
    $res += @{ desc=$n.'content-desc'; x1=$x1;y1=$y1;x2=$x2;y2=$y2; cx=[int](($x1+$x2)/2); cy=[int](($y1+$y2)/2); w=($x2-$x1) }
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

# E1: find the SessionTab (wide clickable in strip with EMPTY desc) and tap ITS true center
Write-Host "########## E1: parsed SessionTab center tap ##########"
Restore "E1_pre"
$strip = Get-Strip "E1_pre"
foreach ($s in $strip) {
  $d = if ($s.desc) { $s.desc } else { "<empty>" }
  Write-Host ("  STRIP desc='{0}' bounds=[{1},{2}][{3},{4}] w={5} center=({6},{7})" -f $d,$s.x1,$s.y1,$s.x2,$s.y2,$s.w,$s.cx,$s.cy)
}
$tab = $strip | Where-Object { ($_.desc -eq $null -or $_.desc.Trim() -eq "") -and $_.w -gt 150 } | Select-Object -First 1
if ($null -eq $tab) { Write-Host "  !! NO SessionTab FOUND"; }
else {
  Write-Host ("  --> TAPPING SessionTab center ({0},{1})" -f $tab.cx, $tab.cy)
  & $adb -s emulator-5560 logcat -c
  & $adb -s emulator-5560 shell input tap $tab.cx $tab.cy
  Start-Sleep -Seconds 2
  & $probe -Tag "E1_post" -SettleSec 1 | Out-Null
  $preS = Sig "E1_pre"; $postS = Sig "E1_post"
  $d = Compare-Object $preS $postS
  Write-Host ("  E1 pre={0} post={1} diff={2}" -f (Cnt "E1_pre"), (Cnt "E1_post"), $(if($null -ne $d){($d|Measure-Object).Count}else{0}))
  if ($null -ne $d) { $d | ForEach-Object { Write-Host ("    {0} {1}" -f $_.SideIndicator, $_.InputObject) } }
  # drawer content detector
  $hit = $postS | Where-Object { $_ -match "工作区|上一个|下一个|重开|帮助|设置" }
  Write-Host ("  drawer_rows_found={0}" -f @($hit).Count)
}

# E2: control - tap the palette button at ITS parsed center (proves tap lands)
Write-Host "########## E2: control - parsed palette center ##########"
Restore "E2_pre"
$strip = Get-Strip "E2_pre"
$pal = $strip | Where-Object { $_.desc -and $_.desc -match "命令面板" } | Select-Object -First 1
if ($null -eq $pal) { Write-Host "  !! palette not found" }
else {
  Write-Host ("  --> TAPPING palette center ({0},{1})" -f $pal.cx, $pal.cy)
  & $adb -s emulator-5560 shell input tap $pal.cx $pal.cy
  Start-Sleep -Seconds 2
  & $probe -Tag "E2_post" -SettleSec 1 | Out-Null
  $preS = Sig "E2_pre"; $postS = Sig "E2_post"
  $d = Compare-Object $preS $postS
  Write-Host ("  E2 pre={0} post={1} diff={2}" -f (Cnt "E2_pre"), (Cnt "E2_post"), $(if($null -ne $d){($d|Measure-Object).Count}else{0}))
  if ($null -ne $d) { $d | Select-Object -First 12 | ForEach-Object { Write-Host ("    {0} {1}" -f $_.SideIndicator, $_.InputObject) } }
}
Write-Host "E-RUN DONE"
