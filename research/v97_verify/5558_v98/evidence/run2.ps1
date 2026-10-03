$ErrorActionPreference = 'Continue'
$ADB = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$S   = "emulator-5558"
$EV  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5558_v98\evidence"
$LOG = "$EV\run_log.txt"
"" | Set-Content $LOG -Encoding UTF8
function W($m) { $m | Out-File -FilePath $LOG -Append -Encoding UTF8 }

function Dmp([string]$tag) {
  for ($i = 1; $i -le 6; $i++) {
    $r = (& $ADB -s $S shell "uiautomator dump /sdcard/$tag.xml 2>&1") -join ' '
    if ($r -match 'dumped to') {
      & $ADB -s $S pull "/sdcard/$tag.xml" "$EV\$tag.xml" | Out-Null
      & $ADB -s $S shell rm -f "/sdcard/$tag.xml"
      W "  [dump $tag OK attempt=$i]"
      return "$EV\$tag.xml"
    }
    W "  [dump $tag FAIL attempt=$i out='$r']"
    Start-Sleep -Seconds 1
  }
  return $null
}
function Shot([string]$tag) {
  & $ADB -s $S shell screencap -p "/sdcard/$tag.png" | Out-Null
  Start-Sleep -Milliseconds 250
  & $ADB -s $S pull "/sdcard/$tag.png" "$EV\$tag.png" | Out-Null
  & $ADB -s $S shell rm -f "/sdcard/$tag.png"
  W "  [shot $tag]"
}
function A([string]$v, [string]$a) {
  if ($v -match ($a + '="([^"]*)"')) { return $Matches[1] }
  if ($v -match ($a + "='([^"]*)'"))  { return $Matches[1] }
  if ($v -match ($a + "='([^']*)'"))  { return $Matches[1] }
  return ''
}
function Lbl([string]$path) {
  if (-not (Test-Path $path)) { return @() }
  $x = Get-Content $path -Raw
  $o = @()
  foreach ($n in [regex]::Matches($x, '<node[^>]*>')) {
    $v = $n.Value
    $t = A $v 'text'; $d = A $v 'content-desc'
    if ($t -ne '' -or $d -ne '') {
      $c = (A $v 'class') -split '\.'; $c = $c[-1]
      $o += [pscustomobject]@{ C=$c; T=$t; D=$d; B=(A $v 'bounds'); P=(A $v 'package'); K=(A $v 'clickable') }
    }
  }
  return $o
}
function NCount([string]$path) {
  if (-not (Test-Path $path)) { return -1 }
  return ([regex]::Matches((Get-Content $path -Raw), '<node[^>]*>')).Count
}
function Ctr([string]$b) {
  if ($b -notmatch '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') { return $null }
  return @([int](($Matches[1]+$Matches[3])/2), [int](($Matches[2]+$Matches[4])/2))
}
function DumpLines([string]$path) {
  if (-not (Test-Path $path)) { return @() }
  $x = Get-Content $path -Raw
  $s = @()
  foreach ($n in [regex]::Matches($x, '<node[^>]*>')) {
    $v = $n.Value
    $s += ((A $v 'class') + '|' + (A $v 'text') + '|' + (A $v 'content-desc') + '|' + (A $v 'bounds'))
  }
  return $s
}
function ShowLbl([string]$label, [string]$path) {
  W "[$label] NODES=$(NCount $path)"
  foreach ($n in (Lbl $path)) { W ("   {0} | t='{1}' | d='{2}' | {3} | click={4} | pkg={5}" -f $n.C,$n.T,$n.D,$n.B,$n.K,$n.P) }
}
function PaletteOpen([string]$path) {
  foreach ($n in (Lbl $path)) {
    if ($n.T -match 'Search files, commands' -or $n.D -match 'Command palette|palette search') { return $true }
  }
  return $false
}

# ---------- P1: panel open state (already open on device) ----------
W "===== P1 PANEL-OPEN ====="
$p1 = Dmp "p1_open"
ShowLbl "P1_OPEN" $p1
Shot "p1_open" | Out-Null
$open1 = PaletteOpen $p1
W "P1_PALETTE_OPEN=$open1"

# ---------- locate tab strip in P1, verify package ----------
W "----- tab strip candidates in P1 -----"
$strip = @(Lbl $p1 | Where-Object { $_.D -in @('Home','New session','Command palette') })
foreach ($n in $strip) { $c = Ctr $n.B; W "  STRIP '$($n.D)' $($n.B) center=$($c[0]),$($c[1]) pkg=$($n.P) click=$($n.K)" }
if ($strip.Count -eq 0) {
  W "  (none in P1 -- falling back to base.xml)"
  foreach ($n in @(Lbl "$EV\base.xml" | Where-Object { $_.D -in @('Home','New session','Command palette') })) {
    $c = Ctr $n.B; W "  BASE_STRIP '$($n.D)' $($n.B) center=$($c[0]),$($c[1]) pkg=$($n.P) click=$($n.K)"
  }
  $strip = @(Lbl "$EV\base.xml" | Where-Object { $_.D -eq 'Home' })
}
if ($strip.Count -eq 0) { W "FATAL_NO_STRIP"; exit 1 }
$tgt = $strip[0]
$tc = Ctr $tgt.B
W "TAP_TARGET='$($tgt.D)' bounds=$($tgt.B) center=$($tc[0]),$($tc[1]) pkg=$($tgt.P)"

# ---------- STEP 2: tap the strip while panel is open ----------
W "===== STEP2 TAP TAB STRIP ====="
& $ADB -s $S shell input tap $tc[0] $tc[1] | Out-Null
W "  tap issued at $($tc[0]),$($tc[1])"
Start-Sleep -Seconds 3
Shot "p2_after_strip_tap" | Out-Null
$p2 = Dmp "p2_after_strip_tap"
ShowLbl "P2_AFTER_STRIP" $p2
$open2 = PaletteOpen $p2
W "P2_PALETTE_OPEN=$open2"
$l1 = DumpLines $p1; $l2 = DumpLines $p2
W "P1_NODES=$($l1.Count) P2_NODES=$($l2.Count) IDENTICAL=$([bool](($l1 -join '|') -eq ($l2 -join '|')))"
if ($p1 -and $p2) {
  W "P1_MD5=$((Get-FileHash $p1 -Algorithm MD5).Hash) P2_MD5=$((Get-FileHash $p2 -Algorithm MD5).Hash)"
  $d = Compare-Object $l1 $l2
  W "----- SIGNATURE DIFF (P1 -> P2) -----"
  if ($null -eq $d) { W "  <no differences>" } else { foreach ($r in $d) { W ("  {0} {1}" -f $r.SideIndicator, $r.InputObject) } }
  $d | ForEach-Object { "{0} {1}" -f $_.SideIndicator, $_.InputObject } | Set-Content "$EV\step2_diff.txt" -Encoding UTF8
}
W "STEP2_VERDICT_PANET_CLOSED=$(-not $open2)"
W "DONE_STEP2"
