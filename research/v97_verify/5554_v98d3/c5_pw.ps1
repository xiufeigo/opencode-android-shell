. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c5lib.ps1
$ErrorActionPreference='Continue'

function GTRead {
  $raw = (Sh @('run-as',$script:PKG,'cat','shared_prefs/oc_server_config.xml')) -join "`n"
  $ms = [regex]::Matches($raw, '<string name="([^"]+)">([^<]*)</string>')
  $rows = @()
  foreach ($m in $ms) { $rows += [pscustomobject]@{ Name=$m.Groups[1].Value; ValLen=$m.Groups[2].Value.Length } }
  return @($rows | Where-Object { $_.Name -notlike '__androidx*' })
}
function GTSig([string]$tag) {
  $d = GTRead
  $s = "[{0}] GT_KEYS={1}  sig: {2}" -f $tag,$d.Count,(($d | ForEach-Object { "$($_.Name.Substring(0,8))=$($_.ValLen)" }) -join ' ')
  [Console]::Out.WriteLine($s); return $d.Count
}
function O([string]$m) { [Console]::Out.WriteLine($m) }
function FormETs($doc) {
  $ets = @()
  foreach ($n in (AllNodes $doc)) {
    if ((NCls $n) -ne 'android.widget.EditText') { continue }
    $b = NBnd $n; if ($null -eq $b) { continue }
    if ($b.T -lt 900) { continue }
    $ets += ,@($n,$b)
  }
  return @($ets | Sort-Object { $_[1].T })
}
function ScrollToTop { for ($i=0;$i -lt 4;$i++){ A @('shell','input','swipe','540','1000','540','1900','80') | Out-Null; Start-Sleep -Milliseconds 250 } }
function PwdFieldBounds($doc) {
  # the field is pruned from the a11y tree, but its LABEL is present.
  # label [x1=106 .. ] -> field top = label.B + 17, height 126 (same geometry as the host field,
  # measured across 4 independent readings in round 4: label.B -> field.T = +17, field.H = 126)
  $pl = @(FindByLabel $doc 'Password' | Where-Object { $b=NBnd $_; $b.L -ge 100 -and $b.L -le 120 -and $b.W -lt 320 })
  if ($pl.Count -eq 0) { return $null }
  $lb = NBnd $pl[0]
  return [pscustomobject]@{ L=138; T=($lb.B + 17); R=943; B=($lb.B + 17 + 126); Label="[$($lb.L),$($lb.T)][$($lb.R),$($lb.B)]" }
}
function SubmitNow([string]$tag) {
  $doc = Dump $tag
  $as = FindByLabel $doc 'Add server'
  if (@($as).Count -eq 0) { O "NO_ADDSERVER"; return $null }
  $an = @($as)[0]; $p = $an
  while ($p -ne $null -and (NClk $p) -ne 'true') { $p = $p.ParentNode }
  if ($null -eq $p) { $p = $an }
  $pb = NBnd $p
  O ("Add server: anc_en={0} anc=[{1},{2}][{3},{4}]" -f (NEn $p),$pb.L,$pb.T,$pb.R,$pb.B)
  TapXY ([int](($pb.L+$pb.R)/2)) ([int](($pb.T+$pb.B)/2))
  Start-Sleep -Seconds 4
  return (Dump ($tag + '_post'))
}

$label = $args[0]
O "### GT start"
$g0 = GTSig "$label-T0"

ScrollToTop
$doc = Dump "$label_s0"
if (@(FindByLabel $doc 'Enter the password for this connection.').Count -eq 0) {
  $b = NBnd @(FindByLabel $doc 'Add SSH server')[0]
  O "open form @($([int](($b.L+$b.R)/2)),$([int](($b.T+$b.B)/2)))"
  TapXY ([int](($b.L+$b.R)/2)) ([int](($b.T+$b.B)/2))
  Start-Sleep -Seconds 1
  $doc = Dump "$label_s1"
}

# ---- host ----
$ets = FormETs $doc
if (@($ets).Count -eq 0) { O "NO_FORM_ET"; exit 1 }
$hsel = @($ets | Sort-Object { -$_[1].H })[0]; $hb = $hsel[1]
O ("host [{0},{1}][{2},{3}] '{4}'" -f $hb.L,$hb.T,$hb.R,$hb.B,(NText $hsel[0]))
TapXY ([int](($hb.L+$hb.R)/2)) ([int](($hb.T+$hb.B)/2))
Start-Sleep -Milliseconds 400
KeyEv 123
for ($i=0;$i -lt 50;$i++){ A @('shell','input','keyevent','67') | Out-Null }
TypeS 'ssh -p 2222 sshtest@127.0.0.1'
Start-Sleep -Milliseconds 800
$doc = Dump "$label_s2"
$ets = FormETs $doc
$hsel = @($ets | Sort-Object { -$_[1].H })[0]
O ("SENTINEL host = '{0}'" -f (NText $hsel[0]))

# ---- password by derived geometry (label is in the tree, the field is not) ----
$pf = PwdFieldBounds $doc
if ($null -eq $pf) {
  O "PASSWORD_LABEL_NOT_IN_TREE - cannot derive field position"
  # fall back: if the form has not been scrolled at all, the password field sits directly
  # below the host field by the same +200px field pitch round 4 measured
  $ets = FormETs $doc
  $hsel = @($ets | Sort-Object { -$_[1].H })[0]; $hb = $hsel[1]
  $pf = [pscustomobject]@{ L=$hb.L; T=($hb.B + 74); R=$hb.R; B=($hb.B + 74 + 126); Label="<derived from host pitch>" }
  O "falling back to host+74 geometry"
}
O ("password label {0} -> field [{1},{2}][{3},{4}] center=({5},{6})" -f $pf.Label,$pf.L,$pf.T,$pf.R,$pf.B,[int](($pf.L+$pf.R)/2),[int](($pf.T+$pf.B)/2))
TapXY ([int](($pf.L+$pf.R)/2)) ([int](($pf.T+$pf.B)/2))
Start-Sleep -Milliseconds 700
TypeS 'pass123456'
Start-Sleep -Milliseconds 900
$doc = Dump "$label_s3"
$ets = FormETs $doc
O ("EditText count after typing password = {0}" -f @($ets).Count)
for ($i=0;$i -lt @($ets).Count;$i++){ O ("  role{0} [{1},{2}][{3},{4}] h={5} '{6}'" -f $i,$ets[$i][1].L,$ets[$i][1].T,$ets[$i][1].R,$ets[$i][1].B,$ets[$i][1].H,(NText $ets[$i][0])) }
$stillBad = @(FindByLabel $doc 'Enter the password for this connection.').Count
O ("PASSWORD_ERROR_STILL_SHOWN = {0}" -f ($stillBad -gt 0))

$doc = SubmitNow "$label_sub"
O "--- after submit: text y>=700 ---"
foreach ($n in (AllNodes $doc)) {
  $t = NText $n; if (-not $t) { continue }
  $b = NBnd $n; if ($null -eq $b) { continue }
  if ($b.T -lt 700) { continue }
  O ("  [{0},{1}][{2},{3}] {4}" -f $b.L,$b.T,$b.R,$b.B,$t)
}
Cap "$label_post"
O "### GT after submit"
$g1 = GTSig "$label-T1"
O ("GT_KEY_DELTA = {0} - {1} = {2}" -f $g1,$g0,($g1-$g0))
