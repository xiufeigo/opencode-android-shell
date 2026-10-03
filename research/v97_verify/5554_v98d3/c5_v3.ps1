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
  [Console]::Out.WriteLine($s)
  return $d.Count
}
function RowTitleFor($doc, $keb) {
  $best=$null; $bestD=1e9
  foreach ($n in (AllNodes $doc)) {
    $t = NText $n; if (-not $t) { continue }
    $b = NBnd $n; if ($null -eq $b) { continue }
    if ($b.L -lt 190 -or $b.L -gt 260) { continue }
    if ($b.R -gt $keb.Bnd.R) { continue }
    if ($b.B -gt $keb.Bnd.T + 5) { continue }
    $d = $keb.Bnd.T - $b.B
    if ($d -ge 0 -and $d -lt $bestD) { $bestD=$d; $best=$t }
  }
  if ($null -eq $best) { return '' } ; return $best
}
function UITraverse([string]$tag, [int]$steps) {
  $seen = [ordered]@{}
  for ($i=0; $i -lt $steps; $i++) {
    $doc = Dump ("{0}_s{1}" -f $tag,$i)
    foreach ($k in (KebabList $doc)) {
      $t = RowTitleFor $doc $k
      if ($t) { $seen[$t] = 1 }
    }
    $s = "  step{0}: {1}" -f $i, ((@($seen.Keys) | ForEach-Object { "'$_'" }) -join ', ')
    [Console]::Out.WriteLine($s)
    A @('shell','input','swipe','540','1850','540','1750','80') | Out-Null
    Start-Sleep -Milliseconds 320
  }
  return ,@($seen.Keys)
}
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
function ScrollToTop {
  for ($i=0;$i -lt 4;$i++){ A @('shell','input','swipe','540','1000','540','1900','80') | Out-Null; Start-Sleep -Milliseconds 250 }
}

$label = $args[0]
"### ===== A: GROUND TRUTH $label (start) ====="
$g0 = GTSig ("{0}-T0" -f $label)

"### ===== reveal the password field by fast-scrolling the INNER form ScrollView ====="
$doc = Dump ("{0}_p0" -f $label)
$ets = FormETs $doc
"form EditTexts (y-sorted), count={0}" -f @($ets).Count
for ($i=0;$i -lt @($ets).Count;$i++){ $s="  role{0} [{1},{2}][{3},{4}] h={5} '{6}'" -f $i,$ets[$i][1].L,$ets[$i][1].T,$ets[$i][1].R,$ets[$i][1].B,$ets[$i][1].H,(NText $ets[$i][0]); [Console]::Out.WriteLine($s) }
# inner form ScrollView = the scrollable that CONTAINS the EditTexts
$fsv = $null
foreach ($b in (ScrollableBounds $doc)) {
  foreach ($e in $ets) { if ($b.L -le $e[1].L -and $e[1].R -le $b.R -and $b.T -le $e[1].T -and $e[1].B -le $b.B) { $fsv = $b } }
}
if ($null -eq $fsv) { "NO_INNER_FORM_SCROLLVIEW"; Cap ("{0}_fsv" -f $label); exit 1 }
$fs = "inner form ScrollView [{0},{1}][{2},{3}]" -f $fsv.L,$fsv.T,$fsv.R,$fsv.B
[Console]::Out.WriteLine($fs)
# x=1020 is inside the ScrollView but OUTSIDE every EditText (which end at x=943),
# so the drag cannot be stolen by text selection.
for ($k=0; $k -lt 5; $k++) {
  $ets = FormETs (Dump ("{0}_p{1}" -f $label,($k+1)))
  $n = @($ets).Count
  $msg = "  scroll pass{0}: EditText count={1}" -f $k,$n
  for ($i=0;$i -lt $n;$i++){ $msg += ("  role{0}=[{1},{2}][{3},{4}]h{5}" -f $i,$ets[$i][1].L,$ets[$i][1].T,$ets[$i][1].R,$ets[$i][1].B,$ets[$i][1].H) }
  [Console]::Out.WriteLine($msg)
  if ($n -ge 3) { break }
  A @('shell','input','swipe','1020',"$([int](($fsv.B)-40))",'1020',"$([int](($fsv.T)+40))",'80') | Out-Null
  Start-Sleep -Milliseconds 350
}
$doc = Dump ("{0}_p9" -f $label)
$ets = FormETs $doc
$n = @($ets).Count
"FINAL form EditText count = {0}" -f $n
for ($i=0;$i -lt $n;$i++){ $s="  role{0} [{1},{2}][{3},{4}] h={5} '{6}'" -f $i,$ets[$i][1].L,$ets[$i][1].T,$ets[$i][1].R,$ets[$i][1].B,$ets[$i][1].H,(NText $ets[$i][0]); [Console]::Out.WriteLine($s) }

if ($n -ge 3) {
  $pb = $ets[2][1]
  "password field [{0},{1}][{2},{3}] -> tap" -f $pb.L,$pb.T,$pb.R,$pb.B
  TapXY ([int](($pb.L+$pb.R)/2)) ([int](($pb.T+$pb.B)/2))
  Start-Sleep -Milliseconds 500
  TypeS 'pass123456'
  Start-Sleep -Milliseconds 800
  $doc = Dump ("{0}_p10" -f $label)
  $ets = FormETs $doc
  for ($i=0;$i -lt @($ets).Count;$i++){ $s="  AFTER role{0} [{1},{2}][{3},{4}] h={5} '{6}'" -f $i,$ets[$i][1].L,$ets[$i][1].T,$ets[$i][1].R,$ets[$i][1].B,$ets[$i][1].H,(NText $ets[$i][0]); [Console]::Out.WriteLine($s) }
} else {
  "PASSWORD_FIELD_NEVER_APPEARED - cannot fill the form password"
  Cap ("{0}_pwf2" -f $label)
}

# submit
$as = FindByLabel $doc 'Add server'
if (@($as).Count -eq 0) { "NO_ADDSERVER"; exit 1 }
$an = @($as)[0]; $p = $an
while ($p -ne $null -and (NClk $p) -ne 'true') { $p = $p.ParentNode }
if ($null -eq $p) { $p = $an }
$pb2 = NBnd $p
$s = "Add server: self_en={0} anc_en={1} anc=[{2},{3}][{4},{5}]" -f (NEn $an),(NEn $p),$pb2.L,$pb2.T,$pb2.R,$pb2.B
[Console]::Out.WriteLine($s)
TapXY ([int](($pb2.L+$pb2.R)/2)) ([int](($pb2.T+$pb2.B)/2))
Start-Sleep -Seconds 4
Cap ("{0}_post" -f $label)
$doc = Dump ("{0}_post" -f $label)
"--- after submit: text y>=700 ---"
foreach ($n2 in (AllNodes $doc)) {
  $t = NText $n2; if (-not $t) { continue }
  $b = NBnd $n2; if ($null -eq $b) { continue }
  if ($b.T -lt 700) { continue }
  $s = "  [{0},{1}][{2},{3}] {4}" -f $b.L,$b.T,$b.R,$b.B,$t
  [Console]::Out.WriteLine($s)
}
"### ===== A: GROUND TRUTH $label (after submit) ====="
$g1 = GTSig ("{0}-T1" -f $label)
$s = "GT_KEY_DELTA = {0} - {1} = {2}" -f $g1,$g0,($g1-$g0)
[Console]::Out.WriteLine($s)
"### ===== B: UI ROWS $label (post-submit traversal) ====="
ScrollToTop
$rows = UITraverse ("{0}_post" -f $label) 6
$s = "UI_ROWS_POST = {0} -> {1}" -f @($rows).Count, ((@($rows) | ForEach-Object {"'$_'"}) -join ', ')
[Console]::Out.WriteLine($s)
