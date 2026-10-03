. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c5lib.ps1
$ErrorActionPreference='Continue'

# ---------- ground truth: prefs key table (key NAMES are AES-SIV ciphertext, so ssh_json
#            is identified by its WRITE-TIME DELTA, not by name) ----------
function GTRead {
  $raw = (Sh @('run-as',$script:PKG,'cat','shared_prefs/oc_server_config.xml')) -join "`n"
  $ms = [regex]::Matches($raw, '<string name="([^"]+)">([^<]*)</string>')
  $rows = @()
  foreach ($m in $ms) { $rows += [pscustomobject]@{ Name=$m.Groups[1].Value; ValLen=$m.Groups[2].Value.Length } }
  return @($rows | Where-Object { $_.Name -notlike '__androidx*' })
}
function GTPrint([string]$tag) {
  $d = GTRead
  $s0 = "[{0}] GT_DATA_KEYS={1}" -f $tag,$d.Count
  [Console]::Out.WriteLine($s0)
  foreach ($r in $d) { $s1 = "    key {0,-46} vlen={1}" -f $r.Name,$r.ValLen; [Console]::Out.WriteLine($s1) }
  return $d.Count
}

function ScrollToTop {
  for ($i=0;$i -lt 4;$i++){ A @('shell','input','swipe','540','1000','540','1900','80') | Out-Null; Start-Sleep -Milliseconds 250 }
}

# ---------- row title: nearest text above the kebab, inside the panel body (y>=900),
#            left edge in the row indent band. The tab strip (y~744) and the panel
#            header (x=148) are excluded by those two bounds. ----------
function RowTitleFor($doc, $keb) {
  $best=$null; $bestD=1e9
  foreach ($n in (AllNodes $doc)) {
    $t = NText $n; if (-not $t) { continue }
    $b = NBnd $n; if ($null -eq $b) { continue }
    if ($b.T -lt 900) { continue }          # panel body only
    if ($b.L -lt 190 -or $b.L -gt 260) { continue }
    if ($b.R -gt $keb.Bnd.R) { continue }   # a title never runs past the kebab's right edge
    if ($b.B -gt $keb.Bnd.T + 5) { continue }
    $d = $keb.Bnd.T - $b.B
    if ($d -ge 0 -and $d -lt $bestD) { $bestD=$d; $best=$t }
  }
  if ($null -eq $best) { return '' }
  return $best
}
# ---------- UI row count: scroll-traverse the whole list, collect deduped row titles ----------
function UITraverse([string]$tag, [int]$steps) {
  $seen = [ordered]@{}
  for ($i=0; $i -lt $steps; $i++) {
    $doc = Dump ("{0}_s{1}" -f $tag,$i)
    foreach ($k in (KebabList $doc)) {
      $t = RowTitleFor $doc $k
      if ($t) { $seen[$t] = 1 }
    }
    $sstep = "  step{0}: {1}" -f $i, ((@($seen.Keys) | ForEach-Object { "'$_'" }) -join ', ')
    [Console]::Out.WriteLine($sstep)
    # 100px / 80ms fast scroll, start point inside the list body
    A @('shell','input','swipe','540','1850','540','1750','80') | Out-Null
    Start-Sleep -Milliseconds 320
  }
  return ,@($seen.Keys)
}
# ---------- the Add server button: TextView reports enabled=true always; the real gate is
#            the nearest clickable ancestor ----------
function AddServerBtn($doc) {
  $as = FindByLabel $doc 'Add server'
  if (@($as).Count -eq 0) { return $null }
  $an = @($as)[0]
  $p = $an
  while ($p -ne $null -and (NClk $p) -ne 'true') { $p = $p.ParentNode }
  if ($null -eq $p) { $p = $an }
  return [pscustomobject]@{ An=$an; Node=$p; B=NBnd $p }
}

$label = $args[0]

"### ===== A: GROUND TRUTH $label ====="
$before = GTPrint ("{0}-BEFORE" -f $label)

"### ===== B0: UI ROWS $label (pre) ====="
ScrollToTop
$pre = UITraverse ("{0}_pre" -f $label) 5
"UI_ROWS_PRE = {0} -> {1}" -f @($pre).Count, ((@($pre) | ForEach-Object {"'$_'"}) -join ', ')

"### ===== SUBMIT $label ====="
ScrollToTop
$doc = Dump ("{0}_o1" -f $label)
$formOpen = @(FindByLabel $doc 'Enter the password for this connection.').Count
if ($formOpen -eq 0) {
  $btn = FindByLabel $doc 'Add SSH server'
  if (@($btn).Count -eq 0) { "NO_ADDSSH_BTN"; exit 1 }
  $b = NBnd @($btn)[0]
  "open form: tap Add SSH server"
  TapXY ([int](($b.L+$b.R)/2)) ([int](($b.T+$b.B)/2))
  Start-Sleep -Seconds 1
  $doc = Dump ("{0}_o2" -f $label)
} else { "form already open (previous validation error visible)" }

# --- host field: the form EditText with the largest height, y>=800 ---
function FormETs($doc) {
  $ets = @()
  foreach ($n in (AllNodes $doc)) {
    if ((NCls $n) -ne 'android.widget.EditText') { continue }
    $b = NBnd $n; if ($null -eq $b) { continue }
    if ($b.T -lt 800) { continue }
    $ets += ,@($n,$b)
  }
  return @($ets)
}
$doc = Dump ("{0}_o3" -f $label)
$ets = FormETs $doc
if (@($ets).Count -eq 0) { "NO_FORM_ET"; Cap ("{0}_o3fail" -f $label); exit 1 }
$hsel = @($ets | Sort-Object { -$_[1].H })[0]
$hb = $hsel[1]
"host field [{0},{1}][{2},{3}] h={4} text='{5}'" -f $hb.L,$hb.T,$hb.R,$hb.B,$hb.H,(NText $hsel[0])
TapXY ([int](($hb.L+$hb.R)/2)) ([int](($hb.T+$hb.B)/2))
Start-Sleep -Milliseconds 400
# clear properly: MOVE_END then N x DEL (bare DEL deletes backwards from the caret)
KeyEv 123
for ($i=0;$i -lt 50;$i++){ A @('shell','input','keyevent','67') | Out-Null }
Start-Sleep -Milliseconds 300
$doc = Dump ("{0}_o4" -f $label)
$ets = FormETs $doc
$hsel = @($ets | Sort-Object { -$_[1].H })[0]
"host after clear: '{0}'" -f (NText $hsel[0])
TypeS 'ssh -p 2222 sshtest@127.0.0.1'
Start-Sleep -Milliseconds 700
$doc = Dump ("{0}_o5" -f $label)
$ets = FormETs $doc
$hsel = @($ets | Sort-Object { -$_[1].H })[0]
"SENTINEL host = '{0}'" -f (NText $hsel[0])

# --- password field: PRUNED from the a11y tree, so derive it from the 'Password' label
#     (host label bottom -> field top is +17px, field height 126px) ---
$pl = FindByLabel $doc 'Password'
if (@($pl).Count -eq 0) { "NO_PASSWORD_LABEL"; Cap ("{0}_pwf" -f $label); exit 1 }
$pb = NBnd @($pl)[0]
# pick the LABEL (x1=106), not the auth selector (x1=138) or the button
$plab = @($pl | Where-Object { $b=NBnd $_; $b.L -ge 100 -and $b.L -le 120 -and $b.W -lt 300 })[0]
if ($null -eq $plab) { $plab = @($pl)[0] }
$pbb = NBnd $plab
$pwT = $pbb.B + 17; $pwB = $pwT + 126
"password label [{0},{1}][{2},{3}] -> derived field [{4},{5}][943,{6}]" -f $pbb.L,$pbb.T,$pbb.R,$pbb.B,138,$pwT,$pwB
TapXY 540 ([int](($pwT+$pwB)/2))
Start-Sleep -Milliseconds 500
TypeS 'pass123456'
Start-Sleep -Milliseconds 700
$doc = Dump ("{0}_o6" -f $label)
$ets = FormETs $doc
foreach ($e in $ets) { "  ET [{0},{1}][{2},{3}] h={4} text='{5}'" -f $e[1].L,$e[1].T,$e[1].R,$e[1].B,$e[1].H,(NText $e[0]) }
$err = FindByLabel $doc 'Enter the password for this connection.'
"PASSWORD_STILL_BLANK = {0}" -f (@($err).Count -gt 0)

$as = AddServerBtn $doc
if ($null -eq $as) { "NO_ADDSERVER"; exit 1 }
"Add server: anc_en={0} anc_bounds=[{1},{2}][{3},{4}]" -f (NEn $as.Node),$as.B.L,$as.B.T,$as.B.R,$as.B.B
TapXY ([int](($as.B.L+$as.B.R)/2)) ([int](($as.B.T+$as.B.B)/2))
Start-Sleep -Seconds 3
Cap ("{0}_post" -f $label)
$doc = Dump ("{0}_o7" -f $label)
"--- after submit: text y>=700 ---"
foreach ($n in (AllNodes $doc)) {
  $t = NText $n; if (-not $t) { continue }
  $b = NBnd $n; if ($null -eq $b) { continue }
  if ($b.T -lt 700) { continue }
  "  [{0},{1}][{2},{3}] {4}" -f $b.L,$b.T,$b.R,$b.B,$t
}
"--- kebabs ---"
foreach ($k in (KebabList $doc)) { "  KEB [{0},{1}][{2},{3}] title='{4}'" -f $k.Bnd.L,$k.Bnd.T,$k.Bnd.R,$k.Bnd.B,(RowTitleFor $doc $k) }

"### ===== A: GROUND TRUTH $label AFTER ====="
$after = GTPrint ("{0}-AFTER" -f $label)
"GT_KEY_DELTA = {0} - {1} = {2}" -f $after,$before,($after-$before)
