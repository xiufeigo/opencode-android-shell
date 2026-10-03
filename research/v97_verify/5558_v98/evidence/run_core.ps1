$ErrorActionPreference = 'Continue'
$ADB = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$S   = "emulator-5558"
$EV  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5558_v98\evidence"

function Dmp([string]$tag) {
  for ($i = 1; $i -le 5; $i++) {
    $r = (& $ADB -s $S shell "uiautomator dump /sdcard/$tag.xml 2>&1") -join ' '
    if ($r -match 'dumped to') {
      & $ADB -s $S pull "/sdcard/$tag.xml" "$EV\$tag.xml" | Out-Null
      & $ADB -s $S shell rm -f "/sdcard/$tag.xml"
      return "$EV\$tag.xml"
    }
    Start-Sleep -Seconds 2
  }
  return $null
}
function Shot([string]$tag) {
  & $ADB -s $S shell screencap -p "/sdcard/$tag.png" | Out-Null
  Start-Sleep -Milliseconds 300
  & $ADB -s $S pull "/sdcard/$tag.png" "$EV\$tag.png" | Out-Null
  & $ADB -s $S shell rm -f "/sdcard/$tag.png"
}
function Attr([string]$v, [string]$a) {
  if ($v -match ($a + '="([^"]*)"')) { return $Matches[1] }
  if ($v -match ($a + "='([^']*)'"))  { return $Matches[1] }
  return ''
}
function Nodes([string]$path) {
  if (-not (Test-Path $path)) { return @() }
  $x = Get-Content $path -Raw
  $out = @()
  foreach ($n in [regex]::Matches($x, '<node[^>]*>')) {
    $v = $n.Value
    $t = Attr $v 'text'; $d = Attr $v 'content-desc'
    $b = Attr $v 'bounds'; $c = (Attr $v 'class') -split '\.' ; $c = $c[-1]
    $p = Attr $v 'package'; $k = Attr $v 'clickable'
    if ($t -ne '' -or $d -ne '') { $out += [pscustomobject]@{C=$c;T=$t;D=$d;B=$b;P=$p;K=$k} }
  }
  return $out
}
function Count([string]$path) {
  if (-not (Test-Path $path)) { return -1 }
  return ([regex]::Matches((Get-Content $path -Raw), '<node[^>]*>')).Count
}
function Ctr([string]$b) {
  if ($b -notmatch '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') { return $null }
  return @([int](($Matches[1]+$Matches[3])/2), [int](($Matches[2]+$Matches[4])/2))
}
function DumpAll([string]$path) {
  if (-not (Test-Path $path)) { return 'NODUMP' }
  $x = Get-Content $path -Raw
  $s = @()
  foreach ($n in [regex]::Matches($x, '<node[^>]*>')) {
    $v = $n.Value
    $s += ((Attr $v 'class') + '|' + (Attr $v 'text') + '|' + (Attr $v 'content-desc') + '|' + (Attr $v 'bounds'))
  }
  return ($s -join "`n")
}
function Show([string]$label, [string]$path) {
  $ns = Nodes $path
  Write-Output "[$label] nodes=$(Count $path) labeled=$($ns.Count)"
  foreach ($n in $ns) { Write-Output ("   {0} | t='{1}' | d='{2}' | {3} | click={4} | pkg={5}" -f $n.C,$n.T,$n.D,$n.B,$n.K,$n.P) }
}

Write-Output "########## STEP 1: open palette (Ctrl+K) ##########"
& $ADB -s $S shell input keycombination 113 39 | Out-Null
Start-Sleep -Seconds 3
$p1 = Dmp "p1_open"
Show "P1_OPEN" $p1
Shot "p1_open" | Out-Null

Write-Output "########## tab strip bounds from P1 (must be in this app pkg) ##########"
$strip = @()
foreach ($n in (Nodes $p1)) {
  if ($n.D -in @('Home','New session','Command palette')) { $strip += $n }
}
foreach ($n in $strip) { Write-Output "  STRIP $($n.D) $($n.B) center=$(Ctr $n.B) pkg=$($n.P)" }
if ($strip.Count -eq 0) { Write-Output "NO_STRIP_IN_P1" }

# fallback: use base.xml strip coords
$baseStrip = @()
foreach ($n in (Nodes "$EV\base.xml")) {
  if ($n.D -in @('Home','New session','Command palette')) { $baseStrip += $n }
}
foreach ($n in $baseStrip) { Write-Output "  BASE_STRIP $($n.D) $($n.B) center=$(Ctr $n.B) pkg=$($n.P)" }

# pick the strip node we will tap: prefer one visible in P1
$target = $null
if ($strip.Count -gt 0) { $target = $strip[0] } elseif ($baseStrip.Count -gt 0) { $target = $baseStrip[0] }
if ($null -eq $target) { Write-Output "FATAL: no tab strip node"; exit 1 }
$tc = Ctr $target.B
Write-Output "TAP_TARGET $($target.D) bounds=$($target.B) center=$($tc[0]),$($tc[1]) pkg=$($target.P)"

Write-Output "########## STEP 2: tap tab strip while palette open ##########"
& $ADB -s $S shell input tap $tc[0] $tc[1] | Out-Null
Start-Sleep -Seconds 3
$p2 = Dmp "p2_after_strip_tap"
Show "P2_AFTER_STRIP" $p2
Shot "p2_after_strip_tap" | Out-Null
$s1 = DumpAll $p1; $s2 = DumpAll $p2
Write-Output "BYTE_IDENTICAL=$(($s1 -eq $s2))  p1hash=$((Get-FileHash $p1 -Algorithm MD5).Hash)  p2hash=$((Get-FileHash $p2 -Algorithm MD5).Hash)"
Set-Content "$EV\step2_diff.txt" "P1=$s1`n=====DIFF=====`nP2=$s2" -Encoding UTF8

Write-Output "########## STATE: is palette still open? ##########"
$hasSearch = $false
foreach ($n in (Nodes $p2)) { if ($n.T -match 'Ask anything|Search|command' -or $n.D -match 'palette') { $hasSearch = $true } }
Write-Output "P2_has_palette_marker=$hasSearch"
Write-Output "DONE_CORE"
